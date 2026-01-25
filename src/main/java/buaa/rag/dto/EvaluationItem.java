package buaa.rag.dto;

import lombok.Data;

import java.util.List;

/**
 * 评测数据项
 */
@Data
public class EvaluationItem {
    private String id;
    private String question;
    private String expectedAnswer;
    private List<String> expectedKeywords;
    private List<String> expectedSources;
}
