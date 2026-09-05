package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 学习记录 Repository
 */
@Repository
public interface LearningRecordRepository extends JpaRepository<LearningRecordEntity, Long> {

    List<LearningRecordEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    Optional<LearningRecordEntity> findByUserIdAndTopicIgnoreCase(Long userId, String topic);
}
