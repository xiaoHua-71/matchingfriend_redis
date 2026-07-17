package com.xiaohua.echo.request;

import lombok.Data;

import java.io.Serializable;

@Data
public class SendEmailCodeRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private String email;
}
