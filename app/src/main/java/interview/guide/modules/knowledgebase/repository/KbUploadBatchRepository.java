package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KbUploadBatchEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * 知识库批量上传批次Repository
 */
@Repository
public interface KbUploadBatchRepository extends JpaRepository<KbUploadBatchEntity, Long> {
}
