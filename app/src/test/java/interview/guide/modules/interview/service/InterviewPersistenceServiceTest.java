package interview.guide.modules.interview.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interview.model.InterviewSessionEntity;
import interview.guide.modules.interview.repository.InterviewAnswerRepository;
import interview.guide.modules.interview.repository.InterviewSessionRepository;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InterviewPersistenceServiceTest {

  @Mock
  private InterviewSessionRepository sessionRepository;

  @Mock
  private InterviewAnswerRepository answerRepository;

  @Mock
  private ResumeRepository resumeRepository;

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  @DisplayName("知识库面试保存时写入 interviewCategory")
  void shouldSaveInterviewCategoryForKnowledgeBaseSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid1", 1L, null, 1, List.of(), "dashscope",
        "knowledge-base", "mid", "KNOWLEDGE_BASE", 9L, "MySQL");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isEqualTo("MySQL");
    assertThat(saved.getKnowledgeBaseId()).isEqualTo(9L);
    assertThat(saved.getSourceType()).isEqualTo("KNOWLEDGE_BASE");
  }

  @Test
  @DisplayName("普通面试保存时 interviewCategory 保持 null")
  void shouldKeepInterviewCategoryNullForNormalSession() {
    InterviewPersistenceService service = newService();
    when(sessionRepository.save(any(InterviewSessionEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    service.saveSession("sid2", 1L, null, 1, List.of(), "dashscope", "java-backend", "mid");

    ArgumentCaptor<InterviewSessionEntity> captor = ArgumentCaptor.forClass(InterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    InterviewSessionEntity saved = captor.getValue();
    assertThat(saved.getInterviewCategory()).isNull();
    assertThat(saved.getSourceType()).isEqualTo("NORMAL");
    assertThat(saved.getKnowledgeBaseId()).isNull();
  }

  @Nested
  @DisplayName("会话归属校验")
  class Ownership {

    @Test
    @DisplayName("本人会话按归属返回")
    void shouldReturnOwnedSession() {
      InterviewPersistenceService service = newService();
      InterviewSessionEntity entity = session("sid-a", 7L);
      when(sessionRepository.findBySessionId("sid-a")).thenReturn(Optional.of(entity));

      assertThat(service.requireOwnedSession("sid-a", 7L)).isSameAs(entity);
    }

    @Test
    @DisplayName("他人会话按不存在处理（404 语义）")
    void shouldTreatForeignSessionAsMissing() {
      InterviewPersistenceService service = newService();
      when(sessionRepository.findBySessionId("sid-b")).thenReturn(Optional.of(session("sid-b", 8L)));

      assertThatThrownBy(() -> service.requireOwnedSession("sid-b", 7L))
          .isInstanceOfSatisfying(BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo(ErrorCode.INTERVIEW_SESSION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("历史无归属会话按不存在处理")
    void shouldTreatUnownedSessionAsMissing() {
      InterviewPersistenceService service = newService();
      when(sessionRepository.findBySessionId("sid-c")).thenReturn(Optional.of(session("sid-c", null)));

      assertThatThrownBy(() -> service.requireOwnedSession("sid-c", 7L))
          .isInstanceOfSatisfying(BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo(ErrorCode.INTERVIEW_SESSION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("会话不存在时抛 404")
    void shouldThrowWhenSessionMissing() {
      InterviewPersistenceService service = newService();
      when(sessionRepository.findBySessionId("sid-d")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.requireOwnedSession("sid-d", 7L))
          .isInstanceOfSatisfying(BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo(ErrorCode.INTERVIEW_SESSION_NOT_FOUND.getCode()));
    }

    @Test
    @DisplayName("面试记录列表按用户过滤")
    void shouldListSessionsByUser() {
      InterviewPersistenceService service = newService();
      when(sessionRepository.findAllByUserIdOrderByCreatedAtDesc(7L)).thenReturn(List.of());

      assertThat(service.findAll(7L)).isEmpty();

      verify(sessionRepository).findAllByUserIdOrderByCreatedAtDesc(7L);
    }

    @Test
    @DisplayName("创建会话时写入归属用户")
    void shouldPersistOwnerOnCreate() {
      InterviewPersistenceService service = newService();
      when(sessionRepository.save(any(InterviewSessionEntity.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      InterviewSessionEntity saved = service.saveSession("sid-e", 7L, null, 1, List.of(),
          "dashscope", "java-backend", "mid");

      assertThat(saved.getUserId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("引用他人简历创建会话时按简历不存在处理")
    void shouldRejectForeignResumeReference() {
      InterviewPersistenceService service = newService();
      ResumeEntity foreignResume = new ResumeEntity();
      foreignResume.setUserId(8L);
      when(resumeRepository.findById(3L)).thenReturn(Optional.of(foreignResume));

      assertThatThrownBy(() -> service.saveSession("sid-f", 7L, 3L, 1, List.of(),
          "dashscope", "java-backend", "mid"))
          .isInstanceOfSatisfying(BusinessException.class,
              exception -> assertThat(exception.getCode())
                  .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
    }

    private InterviewSessionEntity session(String sessionId, Long userId) {
      InterviewSessionEntity entity = new InterviewSessionEntity();
      entity.setSessionId(sessionId);
      entity.setUserId(userId);
      return entity;
    }
  }

  private InterviewPersistenceService newService() {
    return new InterviewPersistenceService(
        sessionRepository,
        answerRepository,
        resumeRepository,
        objectMapper
    );
  }
}
