package buaa.rag.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * 评测运行记录
 */
@Data
@Entity
@Table(name = "rag_evaluation_runs")
public class EvalRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "total_items")
    private Integer totalItems;

    @Column(name = "hit_rate")
    private Double hitRate;

    @Column(name = "avg_similarity")
    private Double avgSimilarity;
}
