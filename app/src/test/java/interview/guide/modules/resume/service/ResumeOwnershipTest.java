package interview.guide.modules.resume.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.mapper.ResumeMapper;
import interview.guide.modules.resume.model.ResumeEntity;
import interview.guide.modules.resume.repository.ResumeAnalysisRepository;
import interview.guide.modules.resume.repository.ResumeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("简历数据归属")
class ResumeOwnershipTest {

  @Mock
  private ResumeRepository resumeRepository;

  @Mock
  private ResumeAnalysisRepository analysisRepository;

  @Mock
  private ObjectMapper objectMapper;

  @Mock
  private ResumeMapper resumeMapper;

  @Mock
  private FileHashService fileHashService;

  @Mock
  private MultipartFile file;

  @Test
  @DisplayName("本人简历按归属返回")
  void shouldReturnOwnedResume() {
    ResumePersistenceService service = newService();
    ResumeEntity resume = resume(1L, 7L);
    when(resumeRepository.findById(1L)).thenReturn(Optional.of(resume));

    assertThat(service.requireOwnedResume(1L, 7L)).isSameAs(resume);
  }

  @Test
  @DisplayName("他人简历按不存在处理（404 语义）")
  void shouldTreatForeignResumeAsMissing() {
    ResumePersistenceService service = newService();
    when(resumeRepository.findById(2L)).thenReturn(Optional.of(resume(2L, 8L)));

    assertThatThrownBy(() -> service.requireOwnedResume(2L, 7L))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("历史无归属简历按不存在处理")
  void shouldTreatUnownedResumeAsMissing() {
    ResumePersistenceService service = newService();
    when(resumeRepository.findById(3L)).thenReturn(Optional.of(resume(3L, null)));

    assertThatThrownBy(() -> service.requireOwnedResume(3L, 7L))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("简历不存在时抛 404")
  void shouldThrowWhenResumeMissing() {
    ResumePersistenceService service = newService();
    when(resumeRepository.findById(9L)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.requireOwnedResume(9L, 7L))
        .isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode())
                .isEqualTo(ErrorCode.RESUME_NOT_FOUND.getCode()));
  }

  @Test
  @DisplayName("简历列表只查当前用户")
  void shouldListResumesByUser() {
    ResumePersistenceService service = newService();
    when(resumeRepository.findAllByUserIdOrderByUploadedAtDesc(7L)).thenReturn(List.of());

    assertThat(service.findAllResumes(7L)).isEmpty();

    verify(resumeRepository).findAllByUserIdOrderByUploadedAtDesc(7L);
  }

  @Test
  @DisplayName("上传去重只在同一用户内命中")
  void shouldDeduplicateWithinUserOnly() {
    ResumePersistenceService service = newService();
    ResumeEntity existing = resume(1L, 7L);
    existing.setAccessCount(1);
    when(fileHashService.calculateHash(file)).thenReturn("hash-1");
    when(resumeRepository.findByFileHashAndUserId("hash-1", 7L)).thenReturn(Optional.of(existing));

    Optional<ResumeEntity> found = service.findExistingResume(file, 7L);

    assertThat(found).containsSame(existing);
    assertThat(existing.getAccessCount()).isEqualTo(2);
    verify(resumeRepository).save(existing);
  }

  private ResumeEntity resume(Long id, Long userId) {
    ResumeEntity entity = new ResumeEntity();
    entity.setId(id);
    entity.setUserId(userId);
    entity.setFileHash("hash-" + id);
    return entity;
  }

  private ResumePersistenceService newService() {
    return new ResumePersistenceService(
        resumeRepository,
        analysisRepository,
        objectMapper,
        resumeMapper,
        fileHashService
    );
  }
}
