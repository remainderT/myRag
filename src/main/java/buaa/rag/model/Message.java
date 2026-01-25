package buaa.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 对话消息记录
 */
@Data
@Entity
@Table(name = "conversation_messages")
public class Message {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", length = 64, nullable = false)
    private String sessionId;

    @Column(name = "user_id", length = 64, nullable = false)
    private String userId;

    @Column(name = "role", length = 16, nullable = false)
    private String role;

    @Lob
    @Column(name = "content", nullable = false, columnDefinition = "LONGTEXT")
    private String content;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
