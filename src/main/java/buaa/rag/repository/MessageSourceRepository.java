package buaa.rag.repository;

import buaa.rag.model.MessageSource;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface MessageSourceRepository extends JpaRepository<MessageSource, Long> {

    List<MessageSource> findByMessageId(Long messageId);

    @Query("select s.documentMd5, avg(f.score) " +
           "from MessageSource s " +
           "join MessageFeedback f on s.messageId = f.messageId " +
           "where s.documentMd5 in :md5s " +
           "group by s.documentMd5")
    List<Object[]> findAverageScoreByDocumentMd5In(@Param("md5s") Collection<String> md5s);
}
