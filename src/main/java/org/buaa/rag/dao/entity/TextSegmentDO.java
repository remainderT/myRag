package org.buaa.rag.dao.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文本片段实体
 * 存储文档分块后的文本内容
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("text_segments")
public class TextSegmentDO {

    private Long segmentId;

    private String documentMd5;

    private Integer fragmentIndex;

    /**
     * 文本片段内容。
     * 使用 LONGTEXT 避免超长文本导致数据库截断。
     */
    private String textData;

    private String encodingModel;

}
