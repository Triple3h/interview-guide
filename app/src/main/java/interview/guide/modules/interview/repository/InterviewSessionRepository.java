package interview.guide.modules.interview.repository;

import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.model.InterviewSessionEntity.SessionStatus;
import interview.guide.modules.resume.model.ResumeEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 面试会话Repository
 */
@Repository
public interface InterviewSessionRepository extends JpaRepository<InterviewSessionEntity, Long> {

    /**
     * 根据会话ID查找
     */
    Optional<InterviewSessionEntity> findBySessionId(String sessionId);

    Optional<InterviewSessionEntity> findByRequestId(String requestId);

    /**
     * 幂等创建请求查找（按用户隔离，避免复用他人 requestId 拿到他人会话）
     */
    Optional<InterviewSessionEntity> findByRequestIdAndUserId(String requestId, Long userId);

    /**
     * 根据会话ID查找（同时加载关联的简历）
     */
    @Query("SELECT s FROM InterviewSessionEntity s LEFT JOIN FETCH s.resume WHERE s.sessionId = :sessionId")
    Optional<InterviewSessionEntity> findBySessionIdWithResume(@Param("sessionId") String sessionId);
    
    /**
     * 根据简历查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeOrderByCreatedAtDesc(ResumeEntity resume);
    
    /**
     * 根据简历ID查找所有面试记录
     */
    List<InterviewSessionEntity> findByResumeIdOrderByCreatedAtDesc(Long resumeId);

    /**
     * 根据简历ID查找最近的面试记录（用于历史题去重）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdOrderByCreatedAtDesc(Long resumeId);
    
    /**
     * 查找简历的未完成面试（CREATED或IN_PROGRESS状态）
     */
    Optional<InterviewSessionEntity> findFirstByResumeIdAndStatusInOrderByCreatedAtDesc(
        Long resumeId, 
        List<SessionStatus> statuses
    );
    
    /**
     * 根据简历ID和状态查找会话
     */
    Optional<InterviewSessionEntity> findByResumeIdAndStatusIn(
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 查找所有面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findAllByOrderByCreatedAtDesc();

    /**
     * 查找用户的面试会话（按创建时间倒序）
     */
    List<InterviewSessionEntity> findAllByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 查找用户某份简历的面试会话
     */
    List<InterviewSessionEntity> findByUserIdAndResumeIdOrderByCreatedAtDesc(Long userId, Long resumeId);

    /**
     * 查找用户的未完成面试（指定简历）
     */
    Optional<InterviewSessionEntity> findFirstByUserIdAndResumeIdAndStatusInOrderByCreatedAtDesc(
        Long userId,
        Long resumeId,
        List<SessionStatus> statuses
    );

    /**
     * 根据 skillId 查找最近的面试记录（用于通用模式历史题去重）
     */
    List<InterviewSessionEntity> findTop10BySkillIdOrderByCreatedAtDesc(String skillId);

    /**
     * 根据 userId + skillId 查找最近的面试记录（通用模式历史题去重，按用户隔离）
     */
    List<InterviewSessionEntity> findTop10ByUserIdAndSkillIdOrderByCreatedAtDesc(Long userId, String skillId);

    /**
     * 根据 resumeId + skillId 查找最近的面试记录（精确匹配）
     */
    List<InterviewSessionEntity> findTop10ByResumeIdAndSkillIdOrderByCreatedAtDesc(Long resumeId, String skillId);

    /**
     * 根据 userId + resumeId + skillId 查找最近的面试记录（按用户隔离）
     */
    List<InterviewSessionEntity> findTop10ByUserIdAndResumeIdAndSkillIdOrderByCreatedAtDesc(
        Long userId, Long resumeId, String skillId);
}
