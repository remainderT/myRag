package buaa.rag.repository;

import buaa.rag.model.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对话消息数据访问接口
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    List<Message> findTop20BySessionIdOrderByCreatedAtAsc(String sessionId);

    Optional<Message> findTop1ByUserIdOrderByCreatedAtDesc(String userId);
}
