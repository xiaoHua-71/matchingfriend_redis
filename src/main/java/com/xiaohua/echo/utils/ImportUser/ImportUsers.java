package com.xiaohua.echo.utils.ImportUser;

import cn.hutool.core.date.StopWatch;
import com.xiaohua.echo.model.entity.User;
import com.xiaohua.echo.service.UserService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;

/**
 * @description: 转JavaAgent开发加油!
 * @author: XiaoHua
 *
 **/
@Component
/**
 * 定时任务,启动服务就进行导入
 *
 */

public class ImportUsers {

    @Resource
    private UserService userService;
    private boolean alreadyExecuted = false;

    /**
     * 一次性任务批量导入用户，1000w条数据进行真实模拟
     */
    @Scheduled(cron = "0 0/1 * * * ?") // 每分钟触发一次
    public void importUsers() {
        if (alreadyExecuted){
            return;
        }
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        final int IMPORT_COUNT = 100000;
        ArrayList<User> usersList = new ArrayList<>();
        for (int i = 0; i <IMPORT_COUNT; i++) {
            User users = new User();
            users.setUsername("假小花");
            users.setUserAccount("fakeXiaohua");
            users.setAvatarUrl("https://tse2-mm.cn.bing.net/th/id/OIP-C.JK62-RRs-5Za0DDkAqVTigAAAA?w=208&h=208&c=7&r=0&o=7&dpr=1.3&pid=1.7&rm=3");
            users.setGender(0);
            users.setUserPassword("12345678910");
            users.setPhone("12345678901");
            users.setEmail("12345678901@163.com");
            users.setTags("[]");
            users.setUserStatus(0);
            users.setUserRole(0);
            usersList.add(users);
        }
        userService.saveBatch(usersList,10000);
        stopWatch.stop();
        System.out.println("耗时：" + stopWatch.getTotalTimeMillis());
        alreadyExecuted = true;

    }
}
