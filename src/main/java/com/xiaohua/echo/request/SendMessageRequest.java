package com.xiaohua.echo.request;

import lombok.Data;

import java.io.Serializable;

@Data
public class SendMessageRequest implements Serializable {

    private Long conversationId;

    private Long receiverId;

    private String content;

    private Integer msgType;

    private static final long serialVersionUID = 1L;
}
