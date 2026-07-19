package com.xiaohua.echo.strategy;

import com.xiaohua.echo.request.UserRegisterRequest;

public interface RegisterStrategy {

    long register(UserRegisterRequest request);
}
