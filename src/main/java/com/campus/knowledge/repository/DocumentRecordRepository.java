package com.campus.knowledge.repository;

import com.campus.knowledge.model.DocumentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档记录数据访问接口
 * 
 * @author campus-team
 */
@Repository
public interface DocumentRecordRepository extends JpaRepository<DocumentRecord, Long> {
    
    /**
     * 根据MD5哈希查找文档
     */
    Optional<DocumentRecord> findByMd5Hash(String md5Hash);
    
    /**
     * 根据文件名查找文档
     */
    Optional<DocumentRecord> findByOriginalFileName(String fileName);
    
    /**
     * 统计指定MD5的文档数量
     */
    long countByMd5Hash(String md5Hash);
    
    /**
     * 删除指定MD5的文档
     */
    void deleteByMd5Hash(String md5Hash);
    
    /**
     * 批量查找指定MD5列表的文档
     */
    List<DocumentRecord> findByMd5HashIn(List<String> md5List);
}
