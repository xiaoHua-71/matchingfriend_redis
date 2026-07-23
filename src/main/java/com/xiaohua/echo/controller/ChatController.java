package com.xiaohua.echo.controller;

import com.xiaohua.echo.common.BaseResponse;
import com.xiaohua.echo.common.ErrorCode;
import com.xiaohua.echo.common.ResultUtils;
import com.xiaohua.echo.exception.BusinessException;
import com.xiaohua.echo.model.entity.User;
import com.xiaohua.echo.model.vo.ConversationVO;
import com.xiaohua.echo.model.vo.MessageVO;
import com.xiaohua.echo.request.SendMessageRequest;
import com.xiaohua.echo.service.ChatService;
import com.xiaohua.echo.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/chat")
@Slf4j
public class ChatController {

    @Resource
    private ChatService chatService;

    @Resource
    private UserService userService;

    @PostMapping("/conversation/start")
    public BaseResponse<ConversationVO> startConversation(@RequestParam Long targetId, HttpServletRequest request) {
        if (targetId == null || targetId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        ConversationVO vo = chatService.createOrGetConversation(loginUser.getId(), targetId);
        return ResultUtils.success(vo);
    }

    @GetMapping("/conversations")
    public BaseResponse<List<ConversationVO>> listConversations(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        List<ConversationVO> list = chatService.listConversations(loginUser.getId());
        return ResultUtils.success(list);
    }

    @GetMapping("/messages/{conversationId}")
    public BaseResponse<List<MessageVO>> getMessages(
            @PathVariable Long conversationId,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        if (conversationId == null || conversationId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        List<MessageVO> list = chatService.getMessages(conversationId, loginUser.getId(), pageNum, pageSize);
        return ResultUtils.success(list);
    }

    @PostMapping("/send")
    public BaseResponse<MessageVO> sendMessage(@RequestBody SendMessageRequest sendRequest, HttpServletRequest request) {
        if (sendRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        MessageVO vo = chatService.sendMessage(loginUser.getId(), sendRequest);
        return ResultUtils.success(vo);
    }

    @PutMapping("/read/{conversationId}")
    public BaseResponse<Boolean> markAsRead(@PathVariable Long conversationId, HttpServletRequest request) {
        if (conversationId == null || conversationId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chatService.markAsRead(conversationId, loginUser.getId());
        return ResultUtils.success(true);
    }

    @DeleteMapping("/conversation/{conversationId}")
    public BaseResponse<Boolean> deleteConversation(@PathVariable Long conversationId, HttpServletRequest request) {
        if (conversationId == null || conversationId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        chatService.deleteConversation(conversationId, loginUser.getId());
        return ResultUtils.success(true);
    }

    @GetMapping("/unread")
    public BaseResponse<Integer> getUnreadCount(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        int count = chatService.getUnreadCount(loginUser.getId());
        return ResultUtils.success(count);
    }
}
