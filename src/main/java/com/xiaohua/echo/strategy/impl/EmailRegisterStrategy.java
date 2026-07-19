package com.xiaohua.echo.strategy.impl;

import com.xiaohua.echo.common.ErrorCode;
import com.xiaohua.echo.exception.BusinessException;
import com.xiaohua.echo.mapper.UserMapper;
import com.xiaohua.echo.model.entity.User;
import com.xiaohua.echo.request.UserRegisterRequest;
import com.xiaohua.echo.strategy.RegisterStrategy;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;

@Component
public class EmailRegisterStrategy implements RegisterStrategy {

    private static final String EMAIL_CODE_PREFIX = "email:code:";
    private static final String SALT = "xiaohua";

    @Resource
    private UserMapper userMapper;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public long register(UserRegisterRequest request) {
        String email = request.getEmail();
        String code = request.getCode();
        String userPassword = request.getUserPassword();
        if (StringUtils.isAnyBlank(email, code, userPassword)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "邮箱、验证码或密码不能为空");
        }
        if (userPassword.length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "密码长度不能小于8位");
        }
        String redisKey = EMAIL_CODE_PREFIX + email;
        String cachedCode = stringRedisTemplate.opsForValue().get(redisKey);
        if (cachedCode == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码已过期，请重新获取");
        }
        if (!cachedCode.equals(code)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "验证码错误");
        }
        stringRedisTemplate.delete(redisKey);
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        User user = new User();
        user.setUserAccount(email);
        user.setEmail(email);
        user.setUserPassword(encryptPassword);
        user.setUsername(email.split("@")[0]);
        boolean saveResult = userMapper.insert(user) > 0;
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "用户注册失败");
        }
        return user.getId();
    }
}
