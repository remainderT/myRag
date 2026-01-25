package org.buaa.rag.service;

import java.util.List;

import com.baomidou.mybatisplus.extension.service.IService;

import jakarta.servlet.ServletRequest;

public interface UserService extends IService<UserDO> {

    /**
     *  查询邮箱是否已注册
     */
    Boolean hasMail(String email);

    /**
     * 发送验证码
     */
    Boolean sendCode(String mail);

    /**
     * 注册用户
     */
    void register(UserRegisterReqDTO requestParam);

    /**
     * 用户登录
     */
    UserLoginRespDTO login(UserLoginReqDTO requestParam, ServletRequest request);

    /**
     * 检查用户是否登录
     */
    Boolean checkLogin(String username, String token);

    /**
     * 退出登录
     */
    void logout(String username, String token);

    /**
     * 更新用户信息
     */
    void update(UserUpdateReqDTO requestParam);

    /**
     * 找回用户名
     */
    Boolean forgetUsername(String mail);

    /**
     * 发送重置密码验证码
     */
    Boolean sendResetPasswordCode(String mail);

    /**
     * 重置密码
     */
    Boolean resetPassword(UserResetPwdReqDTO requestParam);
}
