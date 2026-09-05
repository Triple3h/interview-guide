package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningPlanItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 学习计划条目 Repository
 */
@Repository
public interface LearningPlanItemRepository extends JpaRepository<LearningPlanItemEntity, Long> {

    List<LearningPlanItemEntity> findByUserIdOrderBySortOrderAscIdAsc(Long userId);

    Optional<LearningPlanItemEntity> findByUserIdAndTopicIgnoreCase(Long userId, String topic);

    Optional<LearningPlanItemEntity> findFirstByUserIdOrderBySortOrderDesc(Long userId);
}
