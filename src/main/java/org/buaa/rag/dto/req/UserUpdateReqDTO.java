package org.buaa.rag.dto.req;

import lombok.Data;

/**
 * 更新用户信息请求参数
 */
@Data
public class UserUpdateReqDTO {

    /**
     * 新用户名
     */
    private String newUsername;

    /**
     * 密码
     */
    private String password;

    /**
     * 头像
     */
    private String avatar;

}
