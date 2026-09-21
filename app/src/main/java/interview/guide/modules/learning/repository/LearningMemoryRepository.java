package interview.guide.modules.learning.repository;

import interview.guide.modules.learning.model.LearningMemoryEntity;
import interview.guide.modules.learning.model.LearningMemoryEntity.Kind;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 个人记忆 Repository
 */
@Repository
public interface LearningMemoryRepository extends JpaRepository<LearningMemoryEntity, Long> {

    List<LearningMemoryEntity> findByUserIdOrderByUpdatedAtDesc(Long userId);

    /**
     * 最近 N 条（注入 system prompt / 抽取对照用，避免整表加载）
     */
    List<LearningMemoryEntity> findByUserIdOrderByUpdatedAtDesc(Long userId, Limit limit);

    /**
     * 同类型记忆（抽取落库去重兜底用，命中 idx_learning_memory_kind）
     */
    List<LearningMemoryEntity> findByUserIdAndKind(Long userId, Kind kind);

    long deleteByUserIdAndSourceMessageId(Long userId, Long sourceMessageId);
}
