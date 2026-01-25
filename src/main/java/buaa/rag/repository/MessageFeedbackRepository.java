package buaa.rag.repository;

import buaa.rag.model.MessageFeedback;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageFeedbackRepository extends JpaRepository<MessageFeedback, Long> {
}
