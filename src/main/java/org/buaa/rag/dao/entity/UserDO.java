package org.buaa.rag.dao.entity;

import org.buaa.rag.common.database.BaseDO;

import com.baomidou.mybatisplus.annotation.TableName;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
@TableName("user")
public class UserDO extends BaseDO {

    private Long id;

    private String username;

    private String password;

    private String avatar;

    private String mail;

    private String salt;

    private String introduction;

}
