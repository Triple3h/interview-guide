package interview.guide.modules.knowledgebase.repository;

import interview.guide.modules.knowledgebase.model.KbBatchItemStatus;
import interview.guide.modules.knowledgebase.model.KbUploadBatchItemEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 知识库批量上传批次明细Repository
 */
@Repository
public interface KbUploadBatchItemRepository extends JpaRepository<KbUploadBatchItemEntity, Long> {

    /**
     * 根据知识库ID查找批次明细（一个知识库最多归属一个批次文件明细）
     */
    Optional<KbUploadBatchItemEntity> findByKbId(Long kbId);

    /**
     * 按批次ID查找明细（按入库顺序）
     */
    List<KbUploadBatchItemEntity> findByBatchIdOrderByIdAsc(Long batchId);

    /**
     * 按批次聚合各状态的数量，用于批次列表一次性统计（避免逐批次循环查库）
     * 返回 [batchId, status, count]
     */
    @Query("SELECT i.batchId, i.status, COUNT(i) FROM KbUploadBatchItemEntity i "
        + "WHERE i.batchId IN :batchIds GROUP BY i.batchId, i.status")
    List<Object[]> countByBatchIdsGroupByStatus(@Param("batchIds") List<Long> batchIds);

    /**
     * 统计存在未完成明细（PENDING/PROCESSING）的批次数量，用于角标
     */
    long countByStatusIn(List<KbBatchItemStatus> statuses);
}
