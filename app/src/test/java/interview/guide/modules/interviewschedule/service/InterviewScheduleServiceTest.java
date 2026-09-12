package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("面试日程数据归属")
class InterviewScheduleServiceTest {

  @Mock
  private InterviewScheduleRepository repository;

  @Test
  @DisplayName("创建日程时写入归属用户")
  void shouldPersistOwnerOnCreate() {
    InterviewScheduleService service = newService();
    when(repository.save(any(InterviewScheduleEntity.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    InterviewScheduleDTO dto = service.create(createRequest("字节跳动"), 7L);

    ArgumentCaptor<InterviewScheduleEntity> captor =
        ArgumentCaptor.forClass(InterviewScheduleEntity.class);
    verify(repository).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(7L);
    assertThat(captor.getValue().getStatus()).isEqualTo(InterviewStatus.PENDING);
    assertThat(dto.getCompanyName()).isEqualTo("字节跳动");
  }

  @Test
  @DisplayName("无筛选列表只查当前用户")
  void shouldListOwnSchedules() {
    InterviewScheduleService service = newService();
    when(repository.findByUserId(7L)).thenReturn(List.of());

    assertThat(service.getAll(null, null, null, 7L)).isEmpty();

    verify(repository).findByUserId(7L);
  }

  @Test
  @DisplayName("按状态筛选时叠加用户条件")
  void shouldListOwnSchedulesByStatus() {
    InterviewScheduleService service = newService();
    when(repository.findByUserIdAndStatus(7L, InterviewStatus.PENDING)).thenReturn(List.of());

    service.getAll("PENDING", null, null, 7L);

    verify(repository).findByUserIdAndStatus(7L, InterviewStatus.PENDING);
  }

  @Test
  @DisplayName("按时间段筛选时叠加用户条件")
  void shouldListOwnSchedulesByTimeRange() {
    InterviewScheduleService service = newService();
    LocalDateTime start = LocalDateTime.of(2026, 9, 1, 0, 0);
    LocalDateTime end = LocalDateTime.of(2026, 9, 30, 23, 59);
    when(repository.findByUserIdAndInterviewTimeBetween(7L, start, end)).thenReturn(List.of());

    service.getAll(null, start, end, 7L);

    verify(repository).findByUserIdAndInterviewTimeBetween(7L, start, end);
  }

  @Test
  @DisplayName("他人日程详情按不存在处理（404 语义）")
  void shouldTreatForeignScheduleAsMissing() {
    InterviewScheduleService service = newService();
    when(repository.findById(1L)).thenReturn(Optional.of(schedule(1L, 8L)));

    assertThatThrownBy(() -> service.getById(1L, 7L))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("他人日程不可更新、不可删除")
  void shouldRejectUpdatingForeignSchedule() {
    InterviewScheduleService service = newService();
    when(repository.findById(1L)).thenReturn(Optional.of(schedule(1L, 8L)));

    assertThatThrownBy(() -> service.update(1L, createRequest("甲方"), 7L))
        .isInstanceOf(BusinessException.class);
    assertThatThrownBy(() -> service.delete(1L, 7L))
        .isInstanceOf(BusinessException.class);

    verify(repository, never()).save(any());
    verify(repository, never()).delete(any());
  }

  private InterviewScheduleEntity schedule(Long id, Long userId) {
    InterviewScheduleEntity entity = new InterviewScheduleEntity();
    entity.setId(id);
    entity.setUserId(userId);
    entity.setCompanyName("某公司");
    entity.setPosition("后端工程师");
    entity.setInterviewTime(LocalDateTime.now());
    entity.setStatus(InterviewStatus.PENDING);
    return entity;
  }

  private CreateInterviewRequest createRequest(String companyName) {
    CreateInterviewRequest request = new CreateInterviewRequest();
    request.setCompanyName(companyName);
    request.setPosition("后端工程师");
    request.setInterviewTime(LocalDateTime.now().plusDays(1));
    return request;
  }

  private InterviewScheduleService newService() {
    return new InterviewScheduleService(repository);
  }
}
