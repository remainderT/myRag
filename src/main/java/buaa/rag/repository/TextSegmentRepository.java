package buaa.rag.repository;

import buaa.rag.model.TextSegment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 文本片段数据访问接口
 */
@Repository
public interface TextSegmentRepository extends JpaRepository<TextSegment, Long> {
    
    /**
     * 查询指定文档的所有文本片段
     */
    List<TextSegment> findByDocumentMd5(String documentMd5);

    /**
     * 删除指定文档的所有片段
     */
    @Transactional
    @Modifying
    @Query(value = "DELETE FROM text_segments WHERE document_md5 = ?1", nativeQuery = true)
    void deleteByDocumentMd5(String documentMd5);

    /**
     * 统计指定文档的片段数量
     */
    long countByDocumentMd5(String documentMd5);

    /**
     * 查询指定文档的特定片段
     */
    TextSegment findByDocumentMd5AndFragmentIndex(String documentMd5, Integer fragmentIndex);
}
