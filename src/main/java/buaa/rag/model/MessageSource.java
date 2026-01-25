package buaa.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 对话消息来源记录
 */
@Data
@Entity
@Table(name = "conversation_message_sources")
public class MessageSource {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_id", nullable = false)
    private Long messageId;

    @Column(name = "document_md5", length = 32, nullable = false)
    private String documentMd5;

    @Column(name = "chunk_id")
    private Integer chunkId;

    @Column(name = "relevance_score")
    private Double relevanceScore;

    @Column(name = "source_file_name", length = 255)
    private String sourceFileName;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
