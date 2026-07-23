package com.xiaohua.echo.websocket;

import com.xiaohua.echo.model.vo.MessageVO;
import com.xiaohua.echo.request.SendMessageRequest;
import com.xiaohua.echo.service.ChatService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

@Component
public class DirectMessageSender implements MessageSender {

    @Resource
    private ChatService chatService;

    @Override
    public MessageVO send(Long senderId, Long receiverId, Long conversationId, String content, Integer msgType) {
        SendMessageRequest request = new SendMessageRequest();
        request.setConversationId(conversationId);
        request.setReceiverId(receiverId);
        request.setContent(content);
        request.setMsgType(msgType != null ? msgType : 0);
        return chatService.sendMessage(senderId, request);
    }
}
