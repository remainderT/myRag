package org.buaa.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.buaa.rag.dao.entity.TextSegmentDO;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface TextSegmentMapper extends BaseMapper<TextSegmentDO> {
    
    /**
     * 查询指定文档的所有文本片段
     */
    List<TextSegmentDO> findByDocumentMd5(String documentMd5);

    /**
     * 删除指定文档的所有片段
     */
    @Transactional
    void deleteByDocumentMd5(String documentMd5);
}
