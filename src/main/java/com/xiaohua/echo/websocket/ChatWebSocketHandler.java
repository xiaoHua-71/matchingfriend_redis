package com.xiaohua.echo.websocket;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.xiaohua.echo.mapper.ConversationMapper;
import com.xiaohua.echo.model.entity.Conversation;
import com.xiaohua.echo.model.vo.MessageVO;
import com.xiaohua.echo.service.ChatService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Date;

@Component
@Slf4j
public class ChatWebSocketHandler extends TextWebSocketHandler {

    @Resource
    private WebSocketSessionManager sessionManager;

    @Resource
    private MessageSender messageSender;

    @Resource
    private ChatService chatService;

    @Resource
    private ConversationMapper conversationMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessionManager.addSession(userId, session);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId == null) {
            sendToSession(session, "{\"type\":\"ERROR\",\"message\":\"未登录\"}");
            return;
        }
        try {
            JSONObject json = JSONUtil.parseObj(message.getPayload());
            String type = json.getStr("type");
            if ("SEND".equals(type)) {
                handleSend(session, userId, json);
            } else if ("READ".equals(type)) {
                handleRead(session, userId, json);
            } else if ("PING".equals(type)) {
                sendToSession(session, "{\"type\":\"PONG\"}");
            } else {
                sendToSession(session, "{\"type\":\"ERROR\",\"message\":\"未知消息类型: " + type + "\"}");
            }
        } catch (Exception e) {
            log.error("WebSocket 消息处理失败: userId={}", userId, e);
            sendToSession(session, "{\"type\":\"ERROR\",\"message\":\"消息格式错误\"}");
        }
    }

    private void handleSend(WebSocketSession session, Long senderId, JSONObject json) {
        Long conversationId = json.getLong("conversationId");
        Long receiverId = json.getLong("receiverId");
        String content = json.getStr("content");
        Integer msgType = json.getInt("msgType", 0);

        if (content == null || content.trim().isEmpty()) {
            sendToSession(session, "{\"type\":\"ERROR\",\"message\":\"消息内容不能为空\"}");
            return;
        }
        if (receiverId == null || receiverId <= 0) {
            sendToSession(session, "{\"type\":\"ERROR\",\"message\":\"接收者ID不能为空\"}");
            return;
        }

        MessageVO msg = messageSender.send(senderId, receiverId, conversationId, content, msgType);

        // ACK via WebSocket
        JSONObject ack = new JSONObject();
        ack.set("type", "ACK");
        ack.set("messageId", msg.getMessageId());
        ack.set("conversationId", msg.getConversationId());
        ack.set("content", msg.getContent());
        ack.set("createTime", msg.getCreateTime().getTime());
        sendToSession(session, ack.toString());

        // Push to receiver if not already pushed by sender
        JSONObject push = new JSONObject();
        push.set("type", "NEW_MSG");
        push.set("messageId", msg.getMessageId());
        push.set("conversationId", msg.getConversationId());
        push.set("senderId", senderId);
        push.set("content", content);
        push.set("msgType", msgType);
        push.set("createTime", msg.getCreateTime().getTime());
        sessionManager.sendToUser(receiverId, push.toString());
    }

    private void handleRead(WebSocketSession session, Long userId, JSONObject json) {
        Long conversationId = json.getLong("conversationId");
        if (conversationId == null) {
            sendToSession(session, "{\"type\":\"ERROR\",\"message\":\"conversationId不能为空\"}");
            return;
        }
        chatService.markAsRead(conversationId, userId);

        // Notify partner
        Conversation conv = conversationMapper.selectById(conversationId);
        if (conv != null) {
            Long partnerId = conv.getUserAId().equals(userId) ? conv.getUserBId() : conv.getUserAId();
            JSONObject readReceipt = new JSONObject();
            readReceipt.set("type", "READ");
            readReceipt.set("conversationId", conversationId);
            readReceipt.set("readerId", userId);
            readReceipt.set("time", new Date().getTime());
            sessionManager.sendToUser(partnerId, readReceipt.toString());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessionManager.removeSession(userId, session);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("WebSocket 传输错误: userId={}", session.getAttributes().get("userId"), exception);
        Long userId = (Long) session.getAttributes().get("userId");
        if (userId != null) {
            sessionManager.removeSession(userId, session);
        }
    }

    private void sendToSession(WebSocketSession session, String message) {
        try {
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(message));
                }
            }
        } catch (IOException e) {
            log.error("WebSocket 发送消息失败", e);
        }
    }
}
