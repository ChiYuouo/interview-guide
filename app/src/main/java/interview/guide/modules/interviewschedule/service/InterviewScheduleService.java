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
    public InterviewScheduleDTO create(Long userId, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = new InterviewScheduleEntity();
        BeanUtils.copyProperties(request, entity);
        entity.setUserId(userId);
        entity.setStatus(InterviewStatus.PENDING);

        return toDTO(repository.save(entity));
    }

    @Transactional
    public InterviewScheduleDTO update(Long userId, Long id, CreateInterviewRequest request) {
        InterviewScheduleEntity entity = getByIdOrThrow(userId, id);
        BeanUtils.copyProperties(request, entity, "id", "userId", "status");
        return toDTO(repository.save(entity));
    }

    @Transactional
    public void delete(Long userId, Long id) {
        repository.delete(getByIdOrThrow(userId, id));
    }

    @Transactional
    public InterviewScheduleDTO updateStatus(Long userId, Long id, InterviewStatus status) {
        InterviewScheduleEntity entity = getByIdOrThrow(userId, id);
        entity.setStatus(status);
        return toDTO(repository.save(entity));
    }

    @Transactional(readOnly = true)
    public List<InterviewScheduleDTO> getAll(
        Long userId, String status, LocalDateTime start, LocalDateTime end) {
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

    @Transactional(readOnly = true)
    public InterviewScheduleDTO getById(Long userId, Long id) {
        return toDTO(getByIdOrThrow(userId, id));
    }

    private InterviewScheduleEntity getByIdOrThrow(Long userId, Long id) {
        return repository.findByIdAndUserId(id, userId)
            .orElseThrow(() -> new BusinessException(ErrorCode.INTERVIEW_SCHEDULE_NOT_FOUND, "面试日程不存在: " + id));
    }

    private InterviewScheduleDTO toDTO(InterviewScheduleEntity entity) {
        InterviewScheduleDTO dto = new InterviewScheduleDTO();
        BeanUtils.copyProperties(entity, dto);
        return dto;
    }
}
