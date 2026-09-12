package interview.guide.modules.interviewschedule.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.modules.interviewschedule.model.CreateInterviewRequest;
import interview.guide.modules.interviewschedule.model.InterviewScheduleDTO;
import interview.guide.modules.interviewschedule.model.InterviewScheduleEntity;
import interview.guide.modules.interviewschedule.model.InterviewStatus;
import interview.guide.modules.interviewschedule.repository.InterviewScheduleRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class InterviewScheduleService {

    private final InterviewScheduleRepository repository;

    private static final String[] COPYABLE_FIELDS = {
        "companyName", "position", "interviewTime", "interviewType",
        "meetingLink", "roundNumber", "interviewer", "notes"
    };

    @Transactional
    public InterviewScheduleDTO create(CreateInterviewRequest request, Long userId) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setUserId(userId);
        entity.setStatus(InterviewStatus.PENDING);

        return toDTO(repository.save(entity));
    }

    @Transactional
    public InterviewScheduleDTO update(Long id, CreateInterviewRequest request, Long userId) {
        InterviewScheduleEntity entity = getOwnedOrThrow(id, userId);
        BeanUtils.copyProperties(request, entity, "id", "status", "userId");
        return toDTO(repository.save(entity));
    }

    @Transactional
    public void delete(Long id, Long userId) {
        repository.delete(getOwnedOrThrow(id, userId));
    }

    @Transactional
    public InterviewScheduleDTO updateStatus(Long id, InterviewStatus status, Long userId) {
        InterviewScheduleEntity entity = getOwnedOrThrow(id, userId);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    public List<InterviewScheduleDTO> getAll(String status, LocalDateTime start, LocalDateTime end, Long userId) {
        List<InterviewScheduleEntity> entities;

        if (start != null && end != null) {
            entities = repository.findByUserIdAndInterviewTimeBetween(userId, start, end);
        } else if (status != null) {
            entities = repository.findByUserIdAndStatus(userId, InterviewStatus.valueOf(status));
        } else {
            entities = repository.findByUserId(userId);
        }

        return entities.stream()
            .map(this::toDTO)
            .collect(Collectors.toList());
    }

    public InterviewScheduleDTO getById(Long id, Long userId) {
        return toDTO(getOwnedOrThrow(id, userId));
    }

    /**
     * 获取归属校验后的日程：非本人日程一律按不存在处理（404 语义）
     */
    private InterviewScheduleEntity getOwnedOrThrow(Long id, Long userId) {
        InterviewScheduleEntity entity = repository.findById(id)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id));
        if (entity.getUserId() == null || !entity.getUserId().equals(userId)) {
            throw new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id);
        }
        return entity;
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
