package buaa.rag.repository;

import buaa.rag.model.Document;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 文档记录数据访问接口
 */
@Repository
public interface DocumentRepository extends JpaRepository<Document, Long> {

    /**
     * 根据MD5哈希查找文档
     */
    Optional<Document> findByMd5Hash(String md5Hash);

    /**
     * 根据文件名查找文档
     */
    Optional<Document> findByOriginalFileName(String fileName);

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
    List<Document> findByMd5HashIn(List<String> md5List);

    /**
     * 获取指定用户上传的文档MD5列表
     */
    @Query("select d.md5Hash from Document d where d.ownerId = :ownerId")
    List<String> findMd5HashByOwnerId(@Param("ownerId") String ownerId);

    /**
     * 获取指定可见性的文档MD5列表
     */
    @Query("select d.md5Hash from Document d where d.visibility = :visibility")
    List<String> findMd5HashByVisibility(@Param("visibility") String visibility);

    /**
     * 按上传时间倒序获取用户文档列表
     */
    List<Document> findByOwnerIdOrderByUploadedAtDesc(String ownerId);
}
