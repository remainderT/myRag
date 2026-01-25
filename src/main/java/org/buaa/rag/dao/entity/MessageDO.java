package org.buaa.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 对话消息记录
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("messages")
public class MessageDO {

    private Long id;

    private String sessionId;

    private String userId;

    private String role;

    private String content;

    private LocalDateTime createdAt;
}
