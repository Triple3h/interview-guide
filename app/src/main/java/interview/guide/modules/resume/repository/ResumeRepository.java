package interview.guide.modules.resume.repository;

import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 简历Repository
 */
@Repository
public interface ResumeRepository extends JpaRepository<ResumeEntity, Long> {

    /**
     * 用户在指定哈希下是否已有简历（同一用户内去重）
     */
    Optional<ResumeEntity> findByFileHashAndUserId(String fileHash, Long userId);

    /**
     * 用户的简历列表（按上传时间倒序）
     */
    List<ResumeEntity> findAllByUserIdOrderByUploadedAtDesc(Long userId);

    /**
     * 按 ID + 归属查找（越权按不存在处理）
     */
    Optional<ResumeEntity> findByIdAndUserId(Long id, Long userId);
}
