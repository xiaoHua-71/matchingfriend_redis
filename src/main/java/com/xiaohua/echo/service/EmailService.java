package com.xiaohua.echo.service;

public interface EmailService {

    void sendVerificationCode(String toEmail, String code);
}
