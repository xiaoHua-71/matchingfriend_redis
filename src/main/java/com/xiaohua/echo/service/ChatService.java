package com.xiaohua.echo.service;

import com.xiaohua.echo.model.vo.ConversationVO;
import com.xiaohua.echo.model.vo.MessageVO;
import com.xiaohua.echo.request.SendMessageRequest;

import java.util.List;

public interface ChatService {

    ConversationVO createOrGetConversation(Long userId, Long targetId);

    List<ConversationVO> listConversations(Long userId);

    List<MessageVO> getMessages(Long conversationId, Long userId, int pageNum, int pageSize);

    MessageVO sendMessage(Long senderId, SendMessageRequest request);

    void markAsRead(Long conversationId, Long userId);

    void deleteConversation(Long conversationId, Long userId);

    int getUnreadCount(Long userId);
}
