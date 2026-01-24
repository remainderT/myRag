package com.campus.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

/**
 * 内容片段DTO
 * 用于传递文本分块数据
 * 
 * @author campus-team
 */
@Getter
@Setter
@AllArgsConstructor
public class ContentFragment {
    
    /** 片段序号 */
    private int fragmentId;
    
    /** 片段内容 */
    private String textContent;

    /**
     * 获取内容长度
     */
    public int getContentLength() {
        return textContent != null ? textContent.length() : 0;
    }

    /**
     * 判断内容是否为空
     */
    public boolean isEmpty() {
        return textContent == null || textContent.trim().isEmpty();
    }

    /**
     * 获取内容摘要
     */
    public String getSummary(int maxChars) {
        if (textContent == null || textContent.length() <= maxChars) {
            return textContent;
        }
        return textContent.substring(0, maxChars) + "…";
    }
}
