package com.xiaohua.echo.service.impl;

import cn.hutool.json.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xiaohua.echo.common.ErrorCode;
import com.xiaohua.echo.constant.CacheKey;
import com.xiaohua.echo.exception.BusinessException;
import com.xiaohua.echo.mapper.ConversationMapper;
import com.xiaohua.echo.mapper.MessageMapper;
import com.xiaohua.echo.mapper.UserMapper;
import com.xiaohua.echo.model.entity.Conversation;
import com.xiaohua.echo.model.entity.Message;
import com.xiaohua.echo.model.entity.User;
import com.xiaohua.echo.model.vo.ConversationVO;
import com.xiaohua.echo.model.vo.MessageVO;
import com.xiaohua.echo.request.SendMessageRequest;
import com.xiaohua.echo.service.ChatService;
import com.xiaohua.echo.websocket.WebSocketSessionManager;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;

import javax.annotation.Resource;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatServiceImpl implements ChatService {

    @Resource
    private ConversationMapper conversationMapper;

    @Resource
    private MessageMapper messageMapper;

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private WebSocketSessionManager sessionManager;

    // ======================== 会话 ========================

    @Override
    public ConversationVO createOrGetConversation(Long userId, Long targetId) {
        if (targetId == null || targetId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "目标用户ID不能为空");
        }
        if (targetId.equals(userId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能和自己创建会话");
        }
        User targetUser = userMapper.selectById(targetId);
        if (targetUser == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "目标用户不存在");
        }
        Conversation conv = createOrGetConversationInternal(userId, targetId);

        ConversationVO vo = new ConversationVO();
        vo.setConversationId(conv.getId());
        vo.setTargetUserId(targetId);
        vo.setTargetUsername(targetUser.getUsername());
        vo.setTargetAvatarUrl(targetUser.getAvatarUrl());
        vo.setUnreadCount(0);
        vo.setOnline(sessionManager.isOnline(targetId));
        return vo;
    }

    @Override
    public List<ConversationVO> listConversations(Long userId) {
        // 1. 查询用户的会话列表
        QueryWrapper<Conversation> wrapper = new QueryWrapper<>();
        wrapper.and(w -> w
                .eq("userAId", userId).eq("userADeleted", 0)
                .or()
                .eq("userBId", userId).eq("userBDeleted", 0))
                .orderByDesc("lastMsgTime");
        List<Conversation> conversations = conversationMapper.selectList(wrapper);

        if (CollectionUtils.isEmpty(conversations)) {
            return Collections.emptyList();
        }

        // 2. 收集目标用户ID和最后消息ID
        Set<Long> targetUserIds = new HashSet<>();
        Set<Long> lastMsgIds = new HashSet<>();
        for (Conversation conv : conversations) {
            targetUserIds.add(conv.getUserAId().equals(userId) ? conv.getUserBId() : conv.getUserAId());
            if (conv.getLastMsgId() != null) {
                lastMsgIds.add(conv.getLastMsgId());
            }
        }

        // 3. 批量加载目标用户信息
        Map<Long, User> userMap = new HashMap<>();
        if (!targetUserIds.isEmpty()) {
            List<User> users = userMapper.selectBatchIds(targetUserIds);
            for (User u : users) {
                userMap.put(u.getId(), u);
            }
        }

        // 4. 批量加载最后消息内容
        Map<Long, String> lastMsgContentMap = new HashMap<>();
        if (!lastMsgIds.isEmpty()) {
            List<Message> lastMsgs = messageMapper.selectBatchIds(lastMsgIds);
            for (Message m : lastMsgs) {
                String preview = m.getContent();
                if (preview != null && preview.length() > 50) {
                    preview = preview.substring(0, 50) + "...";
                }
                lastMsgContentMap.put(m.getId(), preview);
            }
        }

        // 5. 批量查询未读数（一条SQL）
        Map<Long, Integer> unreadMap = new HashMap<>();
        QueryWrapper<Message> unreadWrapper = new QueryWrapper<>();
        unreadWrapper.select("conversationId", "COUNT(*) AS cnt")
                .eq("receiverId", userId)
                .eq("status", 0)
                .in("conversationId", conversations.stream().map(Conversation::getId).collect(Collectors.toList()))
                .groupBy("conversationId");
        List<Map<String, Object>> unreadMaps = messageMapper.selectMaps(unreadWrapper);
        for (Map<String, Object> row : unreadMaps) {
            Long convId = (Long) row.get("conversationId");
            Long cnt = (Long) row.get("cnt");
            unreadMap.put(convId, cnt != null ? cnt.intValue() : 0);
        }

        // 6. 组装VO
        List<ConversationVO> result = new ArrayList<>();
        for (Conversation conv : conversations) {
            Long targetId = conv.getUserAId().equals(userId) ? conv.getUserBId() : conv.getUserAId();
            User targetUser = userMap.get(targetId);

            ConversationVO vo = new ConversationVO();
            vo.setConversationId(conv.getId());
            vo.setTargetUserId(targetId);
            vo.setTargetUsername(targetUser != null ? targetUser.getUsername() : null);
            vo.setTargetAvatarUrl(targetUser != null ? targetUser.getAvatarUrl() : null);
            vo.setLastMessage(lastMsgContentMap.getOrDefault(conv.getLastMsgId(), ""));
            vo.setLastMessageTime(conv.getLastMsgTime());
            vo.setUnreadCount(unreadMap.getOrDefault(conv.getId(), 0));
            vo.setOnline(sessionManager.isOnline(targetId));
            result.add(vo);
        }
        return result;
    }

    // ======================== 消息 ========================

    @Override
    public List<MessageVO> getMessages(Long conversationId, Long userId, int pageNum, int pageSize) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || (!conv.getUserAId().equals(userId) && !conv.getUserBId().equals(userId))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该会话");
        }

        Page<Message> page = new Page<>(pageNum, pageSize);
        QueryWrapper<Message> wrapper = new QueryWrapper<>();
        wrapper.eq("conversationId", conversationId)
                .orderByDesc("createTime");
        Page<Message> result = messageMapper.selectPage(page, wrapper);

        return result.getRecords().stream().map(this::toMessageVO).collect(Collectors.toList());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MessageVO sendMessage(Long senderId, SendMessageRequest request) {
        String content = request.getContent();
        Long receiverId = request.getReceiverId();
        Integer msgType = request.getMsgType();

        // 参数校验
        if (StringUtils.isBlank(content)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容不能为空");
        }
        if (content.length() > 5000) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "消息内容过长");
        }
        if (receiverId == null || receiverId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "接收者ID不能为空");
        }
        if (receiverId.equals(senderId)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "不能给自己发消息");
        }
        User receiver = userMapper.selectById(receiverId);
        if (receiver == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "接收者不存在");
        }
        if (msgType == null) {
            msgType = 0;
        }

        // 获取或创建会话
        Long conversationId = request.getConversationId();
        Conversation conv;
        if (conversationId == null) {
            conv = createOrGetConversationInternal(senderId, receiverId);
        } else {
            conv = conversationMapper.selectById(conversationId);
            if (conv == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "会话不存在");
            }
            if (!conv.getUserAId().equals(senderId) && !conv.getUserBId().equals(senderId)) {
                throw new BusinessException(ErrorCode.FORBIDDEN, "不属于该会话");
            }
        }
        conversationId = conv.getId();

        // 写入消息
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setSenderId(senderId);
        message.setReceiverId(receiverId);
        message.setContent(content.trim());
        message.setMsgType(msgType);
        message.setStatus(0);
        messageMapper.insert(message);

        // 更新会话（最后消息 + 双向恢复隐藏）
        Conversation updateConv = new Conversation();
        updateConv.setId(conversationId);
        updateConv.setLastMsgId(message.getId());
        updateConv.setLastMsgTime(message.getCreateTime());
        updateConv.setUserADeleted(0);
        updateConv.setUserBDeleted(0);
        conversationMapper.updateById(updateConv);

        // WebSocket 推送给接收者
        try {
            JSONObject push = new JSONObject();
            push.set("type", "NEW_MSG");
            push.set("messageId", message.getId());
            push.set("conversationId", conversationId);
            push.set("senderId", senderId);
            push.set("receiverId", receiverId);
            push.set("content", content.trim());
            push.set("msgType", msgType);
            push.set("createTime", message.getCreateTime().getTime());
            boolean pushed = sessionManager.sendToUser(receiverId, push.toString());
            if (!pushed) {
                log.info("接收者 {} 不在线，消息已持久化到DB", receiverId);
            }
        } catch (Exception e) {
            log.warn("WebSocket 推送失败: receiverId={}, error={}", receiverId, e.getMessage());
        }

        // Redis 未读数 +1
        stringRedisTemplate.opsForValue().increment(CacheKey.CHAT_UNREAD.key(receiverId));

        return toMessageVO(message);
    }

    @Override
    public void markAsRead(Long conversationId, Long userId) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || (!conv.getUserAId().equals(userId) && !conv.getUserBId().equals(userId))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "会话不存在");
        }

        UpdateWrapper<Message> wrapper = new UpdateWrapper<>();
        wrapper.eq("conversationId", conversationId)
                .eq("receiverId", userId)
                .eq("status", 0)
                .set("status", 1);
        messageMapper.update(null, wrapper);

        // 重新计算总未读数并同步到Redis
        Long totalUnread = messageMapper.selectCount(
                new QueryWrapper<Message>().eq("receiverId", userId).eq("status", 0));
        stringRedisTemplate.opsForValue().set(
                CacheKey.CHAT_UNREAD.key(userId),
                String.valueOf(totalUnread != null ? totalUnread : 0),
                CacheKey.CHAT_UNREAD.ttl());
    }

    @Override
    public void deleteConversation(Long conversationId, Long userId) {
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv == null || (!conv.getUserAId().equals(userId) && !conv.getUserBId().equals(userId))) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "会话不存在");
        }

        Conversation update = new Conversation();
        update.setId(conversationId);
        if (conv.getUserAId().equals(userId)) {
            update.setUserADeleted(1);
        } else {
            update.setUserBDeleted(1);
        }
        conversationMapper.updateById(update);
    }

    @Override
    public int getUnreadCount(Long userId) {
        String cached = stringRedisTemplate.opsForValue().get(CacheKey.CHAT_UNREAD.key(userId));
        if (cached != null) {
            try {
                return Integer.parseInt(cached);
            } catch (NumberFormatException e) {
                // 缓存数据异常，查DB
            }
        }
        Long count = messageMapper.selectCount(
                new QueryWrapper<Message>().eq("receiverId", userId).eq("status", 0));
        int unread = count != null ? count.intValue() : 0;
        stringRedisTemplate.opsForValue().set(
                CacheKey.CHAT_UNREAD.key(userId),
                String.valueOf(unread),
                CacheKey.CHAT_UNREAD.ttl());
        return unread;
    }

    // ======================== 内部方法 ========================

    private Conversation createOrGetConversationInternal(Long userId1, Long userId2) {
        Long userAId = Math.min(userId1, userId2);
        Long userBId = Math.max(userId1, userId2);

        Conversation existing = conversationMapper.selectOne(
                new QueryWrapper<Conversation>().eq("userAId", userAId).eq("userBId", userBId));
        if (existing != null) {
            return existing;
        }

        Conversation conv = new Conversation();
        conv.setUserAId(userAId);
        conv.setUserBId(userBId);
        conv.setUserADeleted(0);
        conv.setUserBDeleted(0);
        try {
            conversationMapper.insert(conv);
            return conv;
        } catch (DuplicateKeyException e) {
            return conversationMapper.selectOne(
                    new QueryWrapper<Conversation>().eq("userAId", userAId).eq("userBId", userBId));
        }
    }

    private MessageVO toMessageVO(Message msg) {
        MessageVO vo = new MessageVO();
        vo.setMessageId(msg.getId());
        vo.setConversationId(msg.getConversationId());
        vo.setSenderId(msg.getSenderId());
        vo.setReceiverId(msg.getReceiverId());
        vo.setContent(msg.getContent());
        vo.setMsgType(msg.getMsgType());
        vo.setStatus(msg.getStatus());
        vo.setCreateTime(msg.getCreateTime());
        return vo;
    }
}
