package buaa.rag.repository;

import buaa.rag.model.EvalRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface EvalRunRepository extends JpaRepository<EvalRun, Long> {
}
