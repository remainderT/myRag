package buaa.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 单条评测结果
 */
@Data
@Entity
@Table(name = "rag_evaluation_results")
public class EvalResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id", nullable = false)
    private Long runId;

    @Column(name = "item_id", length = 64)
    private String itemId;

    @Lob
    @Column(name = "question", columnDefinition = "LONGTEXT")
    private String question;

    @Lob
    @Column(name = "expected_answer", columnDefinition = "LONGTEXT")
    private String expectedAnswer;

    @Lob
    @Column(name = "actual_answer", columnDefinition = "LONGTEXT")
    private String actualAnswer;

    @Column(name = "hit")
    private Boolean hit;

    @Column(name = "similarity")
    private Double similarity;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
