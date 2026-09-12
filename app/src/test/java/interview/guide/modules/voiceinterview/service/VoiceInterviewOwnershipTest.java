package interview.guide.modules.voiceinterview.service;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.resume.repository.ResumeRepository;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.dto.CreateSessionRequest;
import interview.guide.modules.voiceinterview.listener.VoiceEvaluateStreamProducer;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionEntity;
import interview.guide.modules.voiceinterview.model.VoiceInterviewSessionStatus;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewEvaluationRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewMessageRepository;
import interview.guide.modules.voiceinterview.repository.VoiceInterviewSessionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("语音面试数据归属")
class VoiceInterviewOwnershipTest {

  @Mock
  private VoiceInterviewSessionRepository sessionRepository;
  @Mock
  private VoiceInterviewMessageRepository messageRepository;
  @Mock
  private VoiceInterviewEvaluationRepository evaluationRepository;
  @Mock
  private ResumeRepository resumeRepository;
  @Mock
  private RedissonClient redissonClient;
  @Mock
  private VoiceInterviewProperties properties;
  @Mock
  private VoiceEvaluateStreamProducer voiceEvaluateStreamProducer;
  @Mock
  private LlmProviderRegistry llmProviderRegistry;
  @Mock
  private RBucket<VoiceInterviewSessionEntity> sessionBucket;

  private VoiceInterviewService service;

  @BeforeEach
  void setUp() {
    service = new VoiceInterviewService(
        sessionRepository,
        messageRepository,
        evaluationRepository,
        resumeRepository,
        redissonClient,
        properties,
        voiceEvaluateStreamProducer,
        llmProviderRegistry
    );
    lenient().when(redissonClient.<VoiceInterviewSessionEntity>getBucket(anyString()))
        .thenReturn(sessionBucket);
  }

  @Test
  @DisplayName("会话归属校验：本人会话正常返回")
  void shouldReturnOwnedSession() {
    VoiceInterviewSessionEntity session = session(1L, 7L);
    when(sessionBucket.get()).thenReturn(session);

    assertThat(service.requireOwnedSession(1L, 7L)).isSameAs(session);
  }

  @Test
  @DisplayName("会话归属校验：他人会话按不存在处理（404 语义）")
  void shouldTreatForeignSessionAsMissing() {
    when(sessionBucket.get()).thenReturn(session(2L, 8L));

    assertThatThrownBy(() -> service.requireOwnedSession(2L, 7L))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.VOICE_SESSION_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("会话归属校验：历史无归属会话按不存在处理")
  void shouldTreatUnownedSessionAsMissing() {
    when(sessionBucket.get()).thenReturn(session(3L, null));

    assertThatThrownBy(() -> service.requireOwnedSession(3L, 7L))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  @DisplayName("会话归属判断：他人会话返回 false")
  void shouldReportForeignSessionNotOwned() {
    when(sessionBucket.get()).thenReturn(session(4L, 8L));

    assertThat(service.isOwnedBy(4L, 7L)).isFalse();
    assertThat(service.isOwnedBy(4L, 8L)).isTrue();
  }

  @Test
  @DisplayName("会话列表只查当前用户")
  void shouldListSessionsByUser() {
    when(sessionRepository.findByUserIdOrderByUpdatedAtDesc(7L)).thenReturn(List.of());

    assertThat(service.getAllSessions(7L, null)).isEmpty();

    verify(sessionRepository).findByUserIdOrderByUpdatedAtDesc(7L);
    verify(sessionRepository, never()).findAll();
  }

  @Test
  @DisplayName("创建语音会话时写入归属用户")
  void shouldPersistOwnerOnCreate() {
    VoiceInterviewSessionEntity saved = session(5L, 7L);
    saved.setRoleType("java-backend");
    saved.setCurrentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO);
    when(sessionRepository.save(any(VoiceInterviewSessionEntity.class))).thenReturn(saved);

    CreateSessionRequest request = CreateSessionRequest.builder()
        .roleType("java-backend")
        .introEnabled(true)
        .plannedDuration(30)
        .build();

    service.createSession(request, 7L);

    ArgumentCaptor<VoiceInterviewSessionEntity> captor =
        ArgumentCaptor.forClass(VoiceInterviewSessionEntity.class);
    verify(sessionRepository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(7L);
  }

  @Test
  @DisplayName("引用他人简历创建语音会话时按简历不存在处理")
  void shouldRejectForeignResumeReference() {
    interview.guide.modules.resume.model.ResumeEntity foreign =
        new interview.guide.modules.resume.model.ResumeEntity();
    foreign.setUserId(8L);
    when(resumeRepository.findById(3L)).thenReturn(Optional.of(foreign));

    CreateSessionRequest request = CreateSessionRequest.builder()
        .roleType("java-backend")
        .introEnabled(true)
        .resumeId(3L)
        .build();

    assertThatThrownBy(() -> service.createSession(request, 7L))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
    verify(sessionRepository, never()).save(any());
  }

  @Test
  @DisplayName("删除他人会话被拒且不落删除操作")
  void shouldRejectDeletingForeignSession() {
    when(sessionBucket.get()).thenReturn(session(6L, 8L));

    assertThatThrownBy(() -> service.deleteSession(6L, 7L))
        .isInstanceOf(BusinessException.class);

    verify(sessionRepository, never()).deleteById(eq(6L));
  }

  private VoiceInterviewSessionEntity session(Long id, Long userId) {
    return VoiceInterviewSessionEntity.builder()
        .id(id)
        .userId(userId)
        .roleType("java-backend")
        .currentPhase(VoiceInterviewSessionEntity.InterviewPhase.INTRO)
        .status(VoiceInterviewSessionStatus.IN_PROGRESS)
        .startTime(LocalDateTime.now())
        .plannedDuration(30)
        .build();
  }
}
