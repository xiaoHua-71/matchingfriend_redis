package com.xiaohua.echo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.xiaohua.echo.common.ErrorCode;
import com.xiaohua.echo.constant.CacheKey;
import com.xiaohua.echo.constant.UserConstant;
import com.xiaohua.echo.exception.BusinessException;
import com.xiaohua.echo.mapper.UserMapper;
import com.xiaohua.echo.model.entity.User;
import com.xiaohua.echo.request.UserRegisterRequest;
import com.xiaohua.echo.service.EmailService;
import com.xiaohua.echo.service.UserService;
import com.xiaohua.echo.strategy.RegisterStrategy;
import com.xiaohua.echo.strategy.RegisterStrategyFactory;
import cn.hutool.json.JSONUtil;
import com.xiaohua.echo.utils.AlgorithmUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.math3.util.Pair;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xiaohua.echo.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 用户服务实现类
 *
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    @Resource
    private UserMapper userMapper;

    @Resource
    private RegisterStrategyFactory registerStrategyFactory;

    @Resource
    private EmailService emailService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 盐值，混淆密码
     */
    private static final String SALT = "xiaohua";

    @Override
    public long userRegister(UserRegisterRequest userRegisterRequest) {
        String registerType = StringUtils.isBlank(userRegisterRequest.getRegisterType())
                ? "password" : userRegisterRequest.getRegisterType();
        RegisterStrategy strategy = registerStrategyFactory.getStrategy(registerType);
        return strategy.register(userRegisterRequest);
    }

    @Override
    public User userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        if (StringUtils.isAnyBlank(userAccount, userPassword)) {
            return null;
        }
        if (userAccount.length() < 4) {
            return null;
        }
        if (userPassword.length() < 8) {
            return null;
        }
        // 账户不能包含特殊字符
        //String validPattern = "[`~!@#$%^&*()+=|{}':;',\\\\[\\\\].<>/?~！@#￥%……&*（）——+|{}【】‘；：”“’。，、？]";
        //Matcher matcher = Pattern.compile(validPattern).matcher(userAccount);
//        if (matcher.find()) {
//            return null;
//        }
        // 2. 加密
        String encryptPassword = DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
        log.info("encryptPassword = " + encryptPassword);
        log.info("userAccount = " + userAccount);
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = userMapper.selectOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            return null;
        }
        // 3. 用户脱敏
        User safetyUser = getSafetyUser(user);
        // 4. 记录用户的登录态
        request.getSession().setAttribute(USER_LOGIN_STATE, safetyUser);
        return safetyUser;
    }

    @Override
    public void sendEmailCode(String email) {
        String code = String.valueOf((int) ((Math.random() * 9 + 1) * 100000));
        stringRedisTemplate.opsForValue().set(CacheKey.EMAIL_CODE.key(email), code,
                CacheKey.EMAIL_CODE.ttlMinutes(), TimeUnit.MINUTES);
        emailService.sendVerificationCode(email, code);
    }

    /**
     * 用户脱敏
     *
     * @param originUser
     * @return
     */
    @Override
    public User getSafetyUser(User originUser) {
        if (originUser == null) {
            return null;
        }
        User safetyUser = new User();
        safetyUser.setId(originUser.getId());
        safetyUser.setUsername(originUser.getUsername());
        safetyUser.setUserAccount(originUser.getUserAccount());
        safetyUser.setAvatarUrl(originUser.getAvatarUrl());
        safetyUser.setGender(originUser.getGender());
        safetyUser.setPhone(originUser.getPhone());
        safetyUser.setEmail(originUser.getEmail());
        safetyUser.setPlanetCode(originUser.getPlanetCode());
        safetyUser.setUserRole(originUser.getUserRole());
        safetyUser.setUserStatus(originUser.getUserStatus());
        safetyUser.setCreateTime(originUser.getCreateTime());
        safetyUser.setTags(originUser.getTags());
        safetyUser.setProfile(originUser.getProfile());
        return safetyUser;
    }

    /**
     * 用户注销
     *
     * @param request
     */
    @Override
    public int userLogout(HttpServletRequest request) {
        // 移除登录态
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return 1;
    }

    /**
     * 根据标签搜索用户（Redis 缓存：倒排索引 ZSET）
     */
    @Override
    public List<User> searchUsersByTags(List<String> tagNameList, int pageNum, int pageSize) {
        if (CollectionUtils.isEmpty(tagNameList)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        log.info("=== 开始标签搜索 === tags={}, pageNum={}, pageSize={}", tagNameList, pageNum, pageSize);

        // 1. 确保每个 tag 的 ZSET 缓存存在（懒加载）
        long t1 = System.currentTimeMillis();
        for (String tagName : tagNameList) {
            ensureTagZSet(tagName);
        }
        long t2 = System.currentTimeMillis();
        log.info("[步骤1] 确保 tag ZSET 完成，耗时={}ms", t2 - t1);

        int start = (pageNum - 1) * pageSize;
        int end = start + pageSize - 1;

        Set<String> userIdSet;
        if (tagNameList.size() == 1) {
            // 单个标签：直接 ZREVRANGE
            String tagKey = CacheKey.TAG_INDEX.key(tagNameList.get(0));
            userIdSet = stringRedisTemplate.opsForZSet().reverseRange(tagKey, start, end);
            log.info("[步骤2] 单标签查询，ZREVRANGE {} {} {}，结果={}", tagKey, start, end,
                    userIdSet != null ? userIdSet.size() : 0);
        } else {
            // 多个标签：ZUNIONSTORE 合并
            String tmpKey = CacheKey.SEARCH_TMP.key(UUID.randomUUID().toString().replace("-", ""));
            List<String> tagKeys = tagNameList.stream()
                    .map(t -> CacheKey.TAG_INDEX.key(t))
                    .collect(Collectors.toList());
            log.info("[步骤2] 多标签 ZUNIONSTORE，keys={}, dest={}", tagKeys, tmpKey);

            stringRedisTemplate.opsForZSet().unionAndStore(
                    tagKeys.get(0),
                    tagKeys.subList(1, tagKeys.size()),
                    tmpKey
            );
            stringRedisTemplate.expire(tmpKey, CacheKey.SEARCH_TMP.ttlMinutes(), TimeUnit.MINUTES);

            userIdSet = stringRedisTemplate.opsForZSet().reverseRange(tmpKey, start, end);
            log.info("[步骤2] ZREVRANGE 临时key {} {} {}，结果={}", tmpKey, start, end,
                    userIdSet != null ? userIdSet.size() : 0);

            stringRedisTemplate.delete(tmpKey);
        }

        if (userIdSet == null || userIdSet.isEmpty()) {
            log.warn("[步骤3] 无匹配用户");
            return Collections.emptyList();
        }

        // 3. 复用 fetchUserInfoBatch 获取用户信息
        List<String> userIdList = new ArrayList<>(userIdSet);
        log.info("[步骤3] 获取用户信息，userIds={}", userIdList);
        long t3 = System.currentTimeMillis();
        List<User> result = fetchUserInfoBatch(userIdList);
        long t4 = System.currentTimeMillis();
        log.info("=== 标签搜索结束 === 返回 {} 个用户，总耗时={}ms", result.size(), t4 - t1);

        return result;
    }

    /**
     * 确保 tag ZSET 存在，不存在则从 DB 懒加载
     */
    private void ensureTagZSet(String tagName) {
        String tagKey = CacheKey.TAG_INDEX.key(tagName);
        if (Boolean.TRUE.equals(stringRedisTemplate.hasKey(tagKey))) {
            log.info("  [tag缓存] {} 已存在，跳过", tagKey);
            return;
        }
        log.info("  [tag缓存] {} 不存在，从DB加载...", tagKey);
        long t1 = System.currentTimeMillis();
        List<Object> userIds = userMapper.selectObjs(
                new QueryWrapper<User>()
                        .select("id")
                        .like("tags", tagName)
        );
        long t2 = System.currentTimeMillis();
        log.info("  [tag缓存] DB查询完成，耗时={}ms，查到 {} 个用户", t2 - t1, userIds.size());

        if (CollectionUtils.isEmpty(userIds)) {
            // 占位：写一个空集合标记位，防止缓存穿透，下次在遇到不存在的标签时，缓存层直接返回空集合
            stringRedisTemplate.opsForZSet().add(tagKey, "__EMPTY__", 0);
            stringRedisTemplate.expire(tagKey, CacheKey.TAG_INDEX.ttlMinutes(), TimeUnit.MINUTES);
            return;
        }


        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] keyBytes = tagKey.getBytes(StandardCharsets.UTF_8);
            for (Object obj : userIds) {
                Long id = (Long) obj;
                connection.zAdd(keyBytes, 1, String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        stringRedisTemplate.expire(tagKey, CacheKey.TAG_INDEX.ttlMinutes(), TimeUnit.MINUTES);
        log.info("  [tag缓存] {} 写入完成，共 {} 个 member", tagKey, userIds.size());
    }

    @Override
    public int updateUser(User user, User loginUser) {
        long userId = user.getId();
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // todo 补充校验，如果用户没有传任何要更新的值，就直接报错，不用执行 update 语句
        // 如果是管理员，允许更新任意用户
        // 如果不是管理员，只允许更新当前（自己的）信息
        if (!isAdmin(loginUser) && userId != loginUser.getId()) {
            throw new BusinessException(ErrorCode.NO_AUTH);
        }
        User oldUser = userMapper.selectById(userId);
        if (oldUser == null) {
            throw new BusinessException(ErrorCode.NULL_ERROR);
        }
        return userMapper.updateById(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.NO_AUTH);
        }
        return (User) userObj;
    }

    /**
     * 是否为管理员
     *
     * @param request
     * @return
     */
    @Override
    public boolean isAdmin(HttpServletRequest request) {
        // 仅管理员可查询
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        User user = (User) userObj;
        return user != null && user.getUserRole() == UserConstant.ADMIN_ROLE;
    }

    /**
     * 是否为管理员
     *
     * @param loginUser
     * @return
     */
    @Override
    public boolean isAdmin(User loginUser) {
        return loginUser != null && loginUser.getUserRole() == UserConstant.ADMIN_ROLE;
    }

    @Override
    public List<User> matchUsers(long num, User loginUser) {
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.select("id", "tags");
        queryWrapper.isNotNull("tags");
        List<User> userList = this.list(queryWrapper);
        String tags = loginUser.getTags();
        Gson gson = new Gson();
        List<String> tagList = gson.fromJson(tags, new TypeToken<List<String>>() {
        }.getType());
        // 用户列表的下标 => 相似度
        List<Pair<User, Long>> list = new ArrayList<>();
        // 依次计算所有用户和当前用户的相似度
        for (int i = 0; i < userList.size(); i++) {
            User user = userList.get(i);
            String userTags = user.getTags();
            // 无标签或者为当前用户自己
            if (StringUtils.isBlank(userTags) || user.getId() == loginUser.getId()) {
                continue;
            }
            List<String> userTagList = gson.fromJson(userTags, new TypeToken<List<String>>() {
            }.getType());
            // 计算分数
            long distance = AlgorithmUtils.minDistance(tagList, userTagList);
            list.add(new Pair<>(user, distance));
        }
        // 按编辑距离由小到大排序
        List<Pair<User, Long>> topUserPairList = list.stream()
                .sorted((a, b) -> (int) (a.getValue() - b.getValue()))
                .limit(num)
                .collect(Collectors.toList());
        // 原本顺序的 userId 列表
        List<Long> userIdList = topUserPairList.stream().map(pair -> pair.getKey().getId()).collect(Collectors.toList());
        QueryWrapper<User> userQueryWrapper = new QueryWrapper<>();
        userQueryWrapper.in("id", userIdList);
        // 1, 3, 2
        // User1、User2、User3
        // 1 => User1, 2 => User2, 3 => User3
        Map<Long, List<User>> userIdUserListMap = this.list(userQueryWrapper)
                .stream()
                .map(user -> getSafetyUser(user))
                .collect(Collectors.groupingBy(User::getId));
        List<User> finalUserList = new ArrayList<>();
        for (Long userId : userIdList) {
            finalUserList.add(userIdUserListMap.get(userId).get(0));
        }
        return finalUserList;
    }

    @Override
    public List<User> recommendUsers(int pageNum, int pageSize, User currentUser) {
        int gender = currentUser.getGender();
        long currentUserId = currentUser.getId();
        String zsetKey = CacheKey.GENDER_INDEX.key(gender);
        int start = (pageNum - 1) * pageSize;
        int end = start + pageSize - 1;

        log.info("=== 开始推荐用户 === pageNum={}, pageSize={}, gender={}, currentUserId={}",
                pageNum, pageSize, gender, currentUserId);
        log.info("[步骤1] ZSET key={}, ZREVRANGE {} {}", zsetKey, start, end);

        // 1. 从 ZSET 分页取 userId
        long t1 = System.currentTimeMillis();
        Set<String> userIdSet = stringRedisTemplate.opsForZSet()
                .reverseRange(zsetKey, start, end);
        long t2 = System.currentTimeMillis();

        if (userIdSet == null || userIdSet.isEmpty()) {
            log.info("[步骤1] ZSET 缓存未命中，耗时={}ms，准备重建...", t2 - t1);

            // 2. ZSET 未命中 → DB 重建
            long t3 = System.currentTimeMillis();
            rebuildGenderZSet(zsetKey, gender, currentUserId);
            long t4 = System.currentTimeMillis();
            log.info("[步骤2] ZSET 重建完成，耗时={}ms", t4 - t3);

            userIdSet = stringRedisTemplate.opsForZSet()
                    .reverseRange(zsetKey, start, end);
            log.info("[步骤2] 重建后重新查询，结果数量={}", userIdSet != null ? userIdSet.size() : 0);
        } else {
            log.info("[步骤1] ZSET 缓存命中，耗时={}ms，得到 {} 个 userId", t2 - t1, userIdSet.size());
        }

        if (userIdSet == null || userIdSet.isEmpty()) {
            log.warn("[步骤2] 最终结果为空，返回空列表");
            return Collections.emptyList();
        }

        // 3. 过滤掉当前用户
        List<String> userIdList = userIdSet.stream()
                .filter(id -> !id.equals(String.valueOf(currentUserId)))
                .collect(Collectors.toList());
        log.info("[步骤3] 过滤当前用户后，剩余 {} 个 userId（过滤掉 {} 个）",
                userIdList.size(), userIdSet.size() - userIdList.size());

        if (userIdList.isEmpty()) {
            log.warn("[步骤3] 过滤后为空，返回空列表");
            return Collections.emptyList();
        }

        // 4. 批量获取用户信息（MGET + 未命中查 DB 回填）
        log.info("[步骤4] 开始批量获取用户信息，userIds={}", userIdList);
        long t5 = System.currentTimeMillis();
        List<User> result = fetchUserInfoBatch(userIdList);
        long t6 = System.currentTimeMillis();
        log.info("[步骤4] 批量获取完成，耗时={}ms，返回 {} 个用户", t6 - t5, result.size());

        log.info("=== 推荐用户结束 ===");
        return result;
    }

    /**
     * 从 DB 重建 ZSET
     */
    private void rebuildGenderZSet(String zsetKey, int gender, long excludeUserId) {
        log.info("  [重建ZSET] 从数据库查询 gender={} 的所有 userId（排除 {}）...", gender, excludeUserId);
        long t1 = System.currentTimeMillis();
        List<Object> idObjects = userMapper.selectObjs(
                new QueryWrapper<User>()
                        .select("id")
                        .eq("gender", gender)
                        .ne("id", excludeUserId)
        );
        long t2 = System.currentTimeMillis();
        log.info("  [重建ZSET] DB查询完成，耗时={}ms，查到 {} 个用户", t2 - t1, idObjects.size());

        if (CollectionUtils.isEmpty(idObjects)) {
            log.warn("  [重建ZSET] 未查到任何用户，跳过");
            return;
        }

        // 用 pipeline 批量写入 ZSET，随机 score 打散顺序
        log.info("  [重建ZSET] 开始 pipeline 写入 Redis ZSET...");
        long t3 = System.currentTimeMillis();
        stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
            byte[] keyBytes = zsetKey.getBytes(StandardCharsets.UTF_8);
            for (Object obj : idObjects) {
                Long id = (Long) obj;
                double score = ThreadLocalRandom.current().nextDouble();
                connection.zAdd(keyBytes, score,
                        String.valueOf(id).getBytes(StandardCharsets.UTF_8));
            }
            return null;
        });
        long t4 = System.currentTimeMillis();
        log.info("  [重建ZSET] pipeline 写入完成，耗时={}ms", t4 - t3);

        stringRedisTemplate.expire(zsetKey, CacheKey.GENDER_INDEX.ttlMinutes(), TimeUnit.MINUTES);
        log.info("  [重建ZSET] 设置过期时间 {} 分钟，key={}", CacheKey.GENDER_INDEX.ttlMinutes(), zsetKey);
    }

    /**
     * 批量获取用户信息：先 MGET Redis，未命中查 DB 并回填
     */
    private List<User> fetchUserInfoBatch(List<String> userIdList) {
        // 构建 cache key 列表
        List<String> cacheKeys = userIdList.stream()
                .map(id -> CacheKey.USER_INFO.key(id))
                .collect(Collectors.toList());

        // MGET 批量查
        log.info("    [获取用户] MGET {} 个 key", cacheKeys.size());
        long t1 = System.currentTimeMillis();
        List<String> cachedJsonList = stringRedisTemplate.opsForValue().multiGet(cacheKeys);
        long t2 = System.currentTimeMillis();
        log.info("    [获取用户] MGET 完成，耗时={}ms", t2 - t1);

        List<User> result = new ArrayList<>();
        List<Long> missIds = new ArrayList<>();

        int hitCount = 0;
        for (int i = 0; i < userIdList.size(); i++) {
            String json = cachedJsonList != null ? cachedJsonList.get(i) : null;
            if (StringUtils.isNotBlank(json)) {
                result.add(JSONUtil.toBean(json, User.class));
                hitCount++;
            } else {
                missIds.add(Long.valueOf(userIdList.get(i)));
            }
        }
        log.info("    [获取用户] 缓存命中={}，未命中={}", hitCount, missIds.size());

        // 未命中 → 批量查 DB 并回填
        if (!missIds.isEmpty()) {
            log.info("    [获取用户] 批量查 DB，missIds={}", missIds);
            long t3 = System.currentTimeMillis();
            List<User> dbUsers = userMapper.selectBatchIds(missIds);
            long t4 = System.currentTimeMillis();
            log.info("    [获取用户] DB查询完成，耗时={}ms，查到 {} 个", t4 - t3, dbUsers.size());

            if (!dbUsers.isEmpty()) {
                log.info("    [获取用户] 开始 pipeline 回填缓存...");
                long t5 = System.currentTimeMillis();
                stringRedisTemplate.executePipelined((RedisCallback<Object>) connection -> {
                    for (User user : dbUsers) {
                        User safe = getSafetyUser(user);
                        byte[] keyBytes = CacheKey.USER_INFO.key(user.getId())
                                .getBytes(StandardCharsets.UTF_8);
                        byte[] valueBytes = JSONUtil.toJsonStr(safe)
                                .getBytes(StandardCharsets.UTF_8);
                        connection.setEx(keyBytes, CacheKey.USER_INFO.ttlSeconds(), valueBytes);
                    }
                    return null;
                });
                long t6 = System.currentTimeMillis();
                log.info("    [获取用户] pipeline 回填完成，耗时={}ms", t6 - t5);

                dbUsers.forEach(user -> result.add(getSafetyUser(user)));
            }
        }
        return result;
    }

    /**
     * 根据标签搜索用户（SQL 查询版）
     *
     * @param tagNameList 用户要拥有的标签
     * @return
     */
    @Deprecated
    private List<User> searchUsersByTagsBySQL(List<String> tagNameList) {
        if (CollectionUtils.isEmpty(tagNameList)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        // 拼接 and 查询
        // like '%Java%' and like '%Python%'
        for (String tagName : tagNameList) {
            queryWrapper = queryWrapper.like("tags", tagName);
        }
        List<User> userList = userMapper.selectList(queryWrapper);
        return userList.stream().map(this::getSafetyUser).collect(Collectors.toList());
    }

}




