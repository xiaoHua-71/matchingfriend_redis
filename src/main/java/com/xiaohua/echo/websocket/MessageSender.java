package com.xiaohua.echo.websocket;

import com.xiaohua.echo.model.vo.MessageVO;

/**
 * 消息发送抽象——方案A直接发送，方案B换成MQ生产者
 */
public interface MessageSender {

    MessageVO send(Long senderId, Long receiverId, Long conversationId, String content, Integer msgType);
}
