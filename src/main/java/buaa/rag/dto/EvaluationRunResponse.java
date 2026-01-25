package buaa.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 评测运行响应
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationRunResponse {
    private Long runId;
    private Integer total;
    private Double hitRate;
    private Double avgSimilarity;
    private List<EvaluationResultDto> results;
}
