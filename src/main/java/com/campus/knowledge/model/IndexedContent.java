package com.campus.knowledge.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 索引内容模型
 * 用于Elasticsearch存储的文档结构
 * 
 * @author campus-team
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexedContent {
    
    /** 文档唯一标识 */
    private String documentId;
    
    /** 原文件MD5哈希 */
    private String sourceMd5;
    
    /** 分块序号 */
    private Integer segmentNumber;
    
    /** 文本内容 */
    private String textPayload;
    
    /** 向量表示 */
    private float[] vectorEmbedding;
    
    /** 编码模型版本 */
    private String encoderVersion;

    /**
     * 判断是否包含有效向量
     */
    public boolean hasVectorEmbedding() {
        return vectorEmbedding != null && vectorEmbedding.length > 0;
    }

    /**
     * 获取向量维度
     */
    public int getVectorDimension() {
        return vectorEmbedding != null ? vectorEmbedding.length : 0;
    }

    /**
     * 判断文本是否为空
     */
    public boolean isTextEmpty() {
        return textPayload == null || textPayload.trim().isEmpty();
    }
}
