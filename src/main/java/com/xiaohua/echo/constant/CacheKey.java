package com.xiaohua.echo.constant;

import java.time.Duration;

/**
 * 统一缓存 Key 管理
 */
public enum CacheKey {

    // ========== 索引层 ZSET ==========
    /** 性别倒排索引，score 随机数 */
    GENDER_INDEX("recommend:gender:%s", Duration.ofMinutes(10)),

    /** 标签倒排索引，score 固定 1，多标签 ZUNIONSTORE 自动加权 */
    TAG_INDEX("tag:%s", Duration.ofMinutes(30)),

    // ========== 数据层 String ==========
    /** 用户脱敏信息 JSON */
    USER_INFO("user:info:%s", Duration.ofMinutes(30)),

    // ========== 临时 Key ==========
    /** 标签搜索临时合并 ZSET */
    SEARCH_TMP("tmp:search:%s", Duration.ofMinutes(1)),

    // ========== 业务 ==========
    /** 邮箱验证码 */
    EMAIL_CODE("email:code:%s", Duration.ofMinutes(5)),
    ;

    private final String pattern;
    private final Duration ttl;

    CacheKey(String pattern, Duration ttl) {
        this.pattern = pattern;
        this.ttl = ttl;
    }

    public String key(Object... args) {
        return String.format(pattern, args);
    }

    public Duration ttl() {
        return ttl;
    }

    public long ttlSeconds() {
        return ttl.getSeconds();
    }

    public long ttlMinutes() {
        return ttl.toMinutes();
    }
}
