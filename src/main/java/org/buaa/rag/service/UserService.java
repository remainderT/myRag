package org.buaa.rag.service;

import java.util.List;

import org.buaa.rag.dao.entity.UserDO;
import org.buaa.rag.dto.req.UserLoginReqDTO;
import org.buaa.rag.dto.req.UserRegisterReqDTO;
import org.buaa.rag.dto.req.UserUpdateReqDTO;
import org.buaa.rag.dto.resp.UserLoginRespDTO;
import org.buaa.rag.dto.resp.UserRespDTO;

import com.baomidou.mybatisplus.extension.service.IService;

import jakarta.servlet.ServletRequest;

public interface UserService extends IService<UserDO> {

    /**
     * 根据邮箱查询用户信息
     */
    UserRespDTO getUserByMail(String mail);

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

}
