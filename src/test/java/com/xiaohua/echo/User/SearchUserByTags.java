package com.xiaohua.echo.User;

import com.xiaohua.echo.model.entity.User;
import com.xiaohua.echo.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * @description: 转JavaAgent开发加油!
 * @author: XiaoHua
 *
 **/
@SpringBootTest
public class SearchUserByTags {

    @Autowired
    private UserService userService;
    @Test
    public void test() {
        List<String> tagNameList  = new java.util.ArrayList<>();
        tagNameList.add("java");
        tagNameList.add("python");
        tagNameList.add("c++");
        List<User> userList = userService.searchUsersByTags(tagNameList);
        System.out.println(userList);

    }

}
