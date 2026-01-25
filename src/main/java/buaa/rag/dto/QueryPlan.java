package buaa.rag.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 查询规划结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryPlan {
    private String originalQuery;
    private List<String> rewrittenQueries;
    private String hydeAnswer;
}
