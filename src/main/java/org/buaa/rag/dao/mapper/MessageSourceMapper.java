package org.buaa.rag.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.buaa.rag.dao.entity.MessageSourceDO;

import java.util.Collection;
import java.util.List;

public interface MessageSourceMapper extends BaseMapper<MessageSourceDO> {

    @Select("SELECT s.documentMd5, AVG(f.score) " +
           "FROM MessageSource s " +
           "JOIN MessageFeedback f ON s.messageId = f.messageId " +
           "WHERE s.documentMd5 IN " +
           "<foreach collection=\"md5s\" item=\"item\" open=\"(\" separator=\",\" close=\")\">#{item}</foreach> " +
           "GROUP BY s.documentMd5")
    List<Object[]> findAverageScoreByDocumentMd5In(@Param("md5s") Collection<String> md5s);
}
