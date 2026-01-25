package buaa.rag.dto;

import lombok.Data;

/**
 * 反馈请求
 */
@Data
public class FeedbackRequest {
    private Long messageId;
    private String userId;
    private Integer score;
    private String comment;
}
