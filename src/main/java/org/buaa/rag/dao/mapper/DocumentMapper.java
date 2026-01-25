package org.buaa.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.buaa.rag.dao.entity.DocumentDO;


import java.util.List;
import java.util.Optional;

public interface DocumentMapper extends BaseMapper<DocumentDO> {

    /**
     * 根据MD5哈希查找文档
     */
    Optional<DocumentDO> findByMd5Hash(String md5Hash);

    /**
     * 删除指定MD5的文档
     */
    void deleteByMd5Hash(String md5Hash);

    /**
     * 批量查找指定MD5列表的文档
     */
    List<DocumentDO> findByMd5HashIn(List<String> md5List);

    /**
     * 获取指定用户上传的文档MD5列表
     */
    @Select("SELECT d.md5Hash FROM Document d WHERE d.ownerId = #{ownerId}")
    List<String> findMd5HashByOwnerId(@Param("ownerId") String ownerId);

    /**
     * 获取指定可见性的文档MD5列表
     */
    @Select("SELECT d.md5Hash FROM Document d WHERE d.visibility = #{visibility}")
    List<String> findMd5HashByVisibility(@Param("visibility") String visibility);

    /**
     * 按上传时间倒序获取用户文档列表
     */
    List<DocumentDO> findByOwnerIdOrderByUploadedAtDesc(String ownerId);
}
