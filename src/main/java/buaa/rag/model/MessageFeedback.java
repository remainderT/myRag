package buaa.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 对话消息反馈
 */
@Data
@Entity
@Table(name = "conversation_message_feedback")
public class MessageFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "score", nullable = false)
    private Integer score;

    @Column(name = "comment", length = 255)
    private String comment;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
