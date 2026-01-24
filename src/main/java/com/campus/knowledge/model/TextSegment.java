package com.campus.knowledge.model;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 文本片段实体
 * 存储文档分块后的文本内容
 * 
 * @author campus-team
 */
@Data
@Entity
@Table(name = "text_segments")
public class TextSegment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "segment_id")
    private Long segmentId;

    @Column(name = "document_md5", nullable = false, length = 32)
    private String documentMd5;

    @Column(name = "fragment_index", nullable = false)
    private Integer fragmentIndex;

    @Lob
    @Column(name = "text_data")
    private String textData;

    @Column(name = "encoding_model", length = 32)
    private String encodingModel;

    /**
     * 获取完整标识符
     */
    public String getFullIdentifier() {
        return String.format("%s-%d", documentMd5, fragmentIndex);
    }

    /**
     * 判断文本是否有效
     */
    public boolean hasValidText() {
        return textData != null && !textData.trim().isEmpty();
    }
}
