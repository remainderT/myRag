package buaa.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 评测结果DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvaluationResultDto {
    private String itemId;
    private String question;
    private Boolean hit;
    private Double similarity;
    private String answer;
}
