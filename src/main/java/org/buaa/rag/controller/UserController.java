package org.buaa.rag.controller;

import java.util.List;

import org.buaa.rag.common.convention.result.Result;
import org.buaa.rag.common.convention.result.Results;
import org.buaa.rag.service.UserService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.ServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * 用户管理控制层
 */
@RestController
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 根据用户名查找用户信息
     */
    @GetMapping("/api/answerly/v1/user/{username}")
    public Result<UserRespDTO> getUserByUsername(@PathVariable("username") String username) {
        return Results.success(userService.getUserByUsername(username));
    }

    /**
     * 根据用户名查询无脱敏用户信息
     */
    @GetMapping("/api/answerly/v1/actual/user/{username}")
    public Result<UserActualRespDTO> getActualUserByUsername(@PathVariable("username") String username) {
        return Results.success(BeanUtil.toBean(userService.getUserByUsername(username), UserActualRespDTO.class));
    }

    /**
     * 查询用户名是否存在
     */
    @GetMapping("/api/answerly/v1/user/has-username")
    public Result<Boolean> hasUsername(@RequestParam("username") String username) {
        return Results.success(userService.hasUsername(username));
    }

    /**
     * 注册时候获得验证码
     */
    @GetMapping("/api/answerly/v1/user/send-code")
    public Result<Boolean> sendCode(@RequestParam("mail") String mail) {
        return Results.success(userService.sendCode(mail));
    }

    /**
     * 注册用户
     */
    @PostMapping("/api/answerly/v1/user")
    public Result<Void> register(@RequestBody UserRegisterReqDTO requestParam) {
        userService.register(requestParam);
        return Results.success();
    }

    /**
     * 用户登录
     */
    @PostMapping("/api/answerly/v1/user/login")
    public Result<UserLoginRespDTO> login(@RequestBody UserLoginReqDTO requestParam, ServletRequest request) {
        return Results.success(userService.login(requestParam, request));
    }

    /**
     * 检查用户是否登录
     */
    @GetMapping("/api/answerly/v1/user/check-login")
    public Result<Boolean> checkLogin(@RequestParam("username") String username, @RequestParam("token") String token) {
        return Results.success(userService.checkLogin(username, token));
    }

    /**
     * 用户退出登录
     */
    @DeleteMapping("/api/answerly/v1/user/logout")
    public Result<Void> logout(@RequestParam("username") String username, @RequestParam("token") String token) {
        userService.logout(username, token);
        return Results.success();
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/api/answerly/v1/user")
    public Result<Void> update(@RequestBody UserUpdateReqDTO requestParam) {
        userService.update(requestParam);
        return Results.success();
    }

    /**
     * 用户活跃排行榜
     */
    @GetMapping("/api/answerly/v1/user/activity/rank")
    public Result<List<UserActivityRankRespDTO>> rank() {
        return Results.success(userService.activityRank());
    }

    /**
     * 查看用户的活跃度
     */
    @GetMapping("/api/answerly/v1/user/activity/score")
    public Result<Integer> activity() {
        return Results.success(userService.activityScore());
    }

    /**
     * 找回用户名
     */
    @GetMapping("/api/answerly/v1/user/forget-username")
    public Result<Boolean> forgetUsername(@RequestParam("mail") String mail) {
        return Results.success(userService.forgetUsername(mail));
    }

    /**
     * 重置密码验证码
     */
    @GetMapping("/api/answerly/v1/user/send-reset-password-code")
    public Result<Boolean> resetPasswordCode(@RequestParam("mail") String mail) {
        return Results.success(userService.sendResetPasswordCode(mail));
    }
    /**
     * 重置密码
     */
    @PostMapping("/api/answerly/v1/user/reset-password")
    public Result<Boolean> resetPassword(@RequestBody UserResetPwdReqDTO requestParam) {
        return Results.success(userService.resetPassword(requestParam));
    }
}

