package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KbBatchItemStatus;
import interview.guide.modules.knowledgebase.model.KbBatchDetailDTO;
import interview.guide.modules.knowledgebase.model.KbBatchListItemDTO;
import interview.guide.modules.knowledgebase.model.KbBatchFileUploadResult;
import interview.guide.modules.knowledgebase.model.KbUploadBatchEntity;
import interview.guide.modules.knowledgebase.model.KbUploadBatchItemEntity;
import interview.guide.modules.knowledgebase.model.CreateKbBatchResponse;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.repository.KbUploadBatchItemRepository;
import interview.guide.modules.knowledgebase.repository.KbUploadBatchRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识库批量上传服务测试")
class KnowledgeBaseBatchServiceTest {

    @Mock
    private KbUploadBatchRepository batchRepository;
    @Mock
    private KbUploadBatchItemRepository batchItemRepository;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private KnowledgeBasePersistenceService persistenceService;
    @Mock
    private FileStorageService storageService;
    @Mock
    private FileValidationService fileValidationService;
    @Mock
    private FileHashService fileHashService;
    @Mock
    private KnowledgeBaseParseService parseService;
    @Mock
    private VectorizeStreamProducer vectorizeStreamProducer;

    private KnowledgeBaseBatchService batchService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        batchService = new KnowledgeBaseBatchService(
            batchRepository,
            batchItemRepository,
            knowledgeBaseRepository,
            persistenceService,
            storageService,
            fileValidationService,
            fileHashService,
            parseService,
            vectorizeStreamProducer);
    }

    private MockMultipartFile pdfFile() {
        return new MockMultipartFile("file", "test.pdf", "application/pdf", "pdf-content".getBytes());
    }

    private KbUploadBatchEntity batch(long id) {
        KbUploadBatchEntity entity = new KbUploadBatchEntity();
        entity.setId(id);
        entity.setName("批量上传测试批次");
        return entity;
    }

    @Nested
    @DisplayName("创建批次")
    class CreateBatch {

        @Test
        @DisplayName("未传名称时使用默认名称")
        void shouldUseDefaultNameWhenNameBlank() {
            when(batchRepository.save(any(KbUploadBatchEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            CreateKbBatchResponse response = batchService.createBatch("  ");

            assertThat(response.batchId()).isNull();
            assertThat(response.name()).startsWith("批量上传 ");
        }

        @Test
        @DisplayName("传入名称时使用自定义名称")
        void shouldUseCustomName() {
            when(batchRepository.save(any(KbUploadBatchEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

            CreateKbBatchResponse response = batchService.createBatch("MySQL 面试题库");

            assertThat(response.name()).isEqualTo("MySQL 面试题库");
        }
    }

    @Nested
    @DisplayName("批次内上传文件")
    class UploadBatchFile {

        @Test
        @DisplayName("批次不存在时抛出 NOT_FOUND 且不留痕")
        void shouldThrowWhenBatchNotFound() {
            when(batchRepository.findById(1L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> batchService.uploadBatchFile(1L, pdfFile(), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传批次不存在");

            verify(batchItemRepository, never()).save(any());
        }

        @Test
        @DisplayName("文件校验失败时明细记为 REJECTED 并回传错误")
        void shouldRecordRejectedWhenValidationFailed() {
            when(batchRepository.findById(1L)).thenReturn(Optional.of(batch(1L)));
            doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "文件大小超出限制"))
                .when(fileValidationService).validateFile(any(), anyLong(), anyString());

            assertThatThrownBy(() -> batchService.uploadBatchFile(1L, pdfFile(), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件大小超出限制");

            ArgumentCaptor<KbUploadBatchItemEntity> captor = ArgumentCaptor.forClass(KbUploadBatchItemEntity.class);
            verify(batchItemRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(KbBatchItemStatus.REJECTED);
            assertThat(captor.getValue().getError()).contains("文件大小超出限制");
        }

        @Test
        @DisplayName("文件类型不合法时明细记为 REJECTED")
        void shouldRecordRejectedWhenContentTypeInvalid() {
            when(batchRepository.findById(1L)).thenReturn(Optional.of(batch(1L)));
            doNothing().when(fileValidationService).validateFile(any(), anyLong(), anyString());
            when(parseService.detectContentType(any())).thenReturn("application/zip");
            doThrow(new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件类型: application/zip"))
                .when(fileValidationService).validateContentType(
                    eq("application/zip"), any(), any(), any(), anyString());

            assertThatThrownBy(() -> batchService.uploadBatchFile(1L, pdfFile(), null, null, null))
                .isInstanceOf(BusinessException.class);

            ArgumentCaptor<KbUploadBatchItemEntity> captor = ArgumentCaptor.forClass(KbUploadBatchItemEntity.class);
            verify(batchItemRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(KbBatchItemStatus.REJECTED);
        }

        @Test
        @DisplayName("文件重复时明细记为 DUPLICATE_SKIPPED 并关联已有知识库")
        void shouldRecordDuplicateSkipped() {
            when(batchRepository.findById(1L)).thenReturn(Optional.of(batch(1L)));
            doNothing().when(fileValidationService).validateFile(any(), anyLong(), anyString());
            when(parseService.detectContentType(any())).thenReturn("application/pdf");

            KnowledgeBaseEntity existing = new KnowledgeBaseEntity();
            existing.setId(66L);
            existing.setName("已有知识库");
            when(fileHashService.calculateHash(any(MultipartFile.class))).thenReturn("hash-1");
            when(knowledgeBaseRepository.findByFileHash("hash-1")).thenReturn(Optional.of(existing));

            KbBatchFileUploadResult result = batchService.uploadBatchFile(1L, pdfFile(), "MySQL 实战", "题库/MySQL/test.pdf", null);

            assertThat(result.duplicate()).isTrue();
            assertThat(result.kbId()).isEqualTo(66L);
            assertThat(result.status()).isEqualTo(KbBatchItemStatus.DUPLICATE_SKIPPED.name());

            ArgumentCaptor<KbUploadBatchItemEntity> captor = ArgumentCaptor.forClass(KbUploadBatchItemEntity.class);
            verify(batchItemRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(KbBatchItemStatus.DUPLICATE_SKIPPED);
            assertThat(captor.getValue().getKbId()).isEqualTo(66L);
            assertThat(captor.getValue().getCategory()).isEqualTo("MySQL 实战");
            assertThat(captor.getValue().getRelativePath()).isEqualTo("题库/MySQL/test.pdf");

            verify(persistenceService).handleDuplicateKnowledgeBase(existing, "hash-1");
            verify(storageService, never()).uploadKnowledgeBase(any());
        }

        @Test
        @DisplayName("正常上传时存储、落库、入队依次执行并返回 PENDING")
        void shouldUploadAndEnqueueSuccessfully() {
            when(batchRepository.findById(1L)).thenReturn(Optional.of(batch(1L)));
            doNothing().when(fileValidationService).validateFile(any(), anyLong(), anyString());
            when(parseService.detectContentType(any())).thenReturn("application/pdf");
            when(fileHashService.calculateHash(any(MultipartFile.class))).thenReturn("hash-2");
            when(knowledgeBaseRepository.findByFileHash("hash-2")).thenReturn(Optional.empty());
            when(storageService.uploadKnowledgeBase(any())).thenReturn("knowledgebases/2026/09/05/test.pdf");
            when(storageService.getFileUrl(anyString())).thenReturn("http://rustfs/test.pdf");
            when(vectorizeStreamProducer.sendVectorizeTask(100L)).thenReturn(true);
            when(persistenceService.saveKnowledgeBaseWithBatchItem(
                any(), any(), any(), anyString(), anyString(), anyString(), eq(1L), any(KbUploadBatchItemEntity.class)))
                .thenAnswer(inv -> {
                    KbUploadBatchItemEntity item = inv.getArgument(7);
                    item.setKbId(100L);
                    return item;
                });

            KbBatchFileUploadResult result = batchService.uploadBatchFile(1L, pdfFile(), null, null, null);

            assertThat(result.duplicate()).isFalse();
            assertThat(result.kbId()).isEqualTo(100L);
            assertThat(result.status()).isEqualTo(KbBatchItemStatus.PENDING.name());

            verify(persistenceService).saveKnowledgeBaseWithBatchItem(
                any(), any(), any(), eq("knowledgebases/2026/09/05/test.pdf"), anyString(), eq("hash-2"), eq(1L), any());
            verify(vectorizeStreamProducer).sendVectorizeTask(100L);
        }

        @Test
        @DisplayName("入队失败时明细记为 FAILED 并抛出异常")
        void shouldRecordFailedWhenEnqueueFailed() {
            when(batchRepository.findById(1L)).thenReturn(Optional.of(batch(1L)));
            doNothing().when(fileValidationService).validateFile(any(), anyLong(), anyString());
            when(parseService.detectContentType(any())).thenReturn("application/pdf");
            when(fileHashService.calculateHash(any(MultipartFile.class))).thenReturn("hash-3");
            when(knowledgeBaseRepository.findByFileHash("hash-3")).thenReturn(Optional.empty());
            when(storageService.uploadKnowledgeBase(any())).thenReturn("key");
            when(storageService.getFileUrl(anyString())).thenReturn("url");
            when(vectorizeStreamProducer.sendVectorizeTask(100L)).thenReturn(false);
            when(persistenceService.saveKnowledgeBaseWithBatchItem(
                any(), any(), any(), anyString(), anyString(), anyString(), eq(1L), any(KbUploadBatchItemEntity.class)))
                .thenAnswer(inv -> {
                    KbUploadBatchItemEntity item = inv.getArgument(7);
                    item.setKbId(100L);
                    return item;
                });

            assertThatThrownBy(() -> batchService.uploadBatchFile(1L, pdfFile(), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("解析任务入队失败");

            ArgumentCaptor<KbUploadBatchItemEntity> captor = ArgumentCaptor.forClass(KbUploadBatchItemEntity.class);
            verify(batchItemRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(KbBatchItemStatus.FAILED);
        }

        @Test
        @DisplayName("存储异常时明细记为 FAILED 并抛出异常")
        void shouldRecordFailedWhenStorageFailed() {
            when(batchRepository.findById(1L)).thenReturn(Optional.of(batch(1L)));
            doNothing().when(fileValidationService).validateFile(any(), anyLong(), anyString());
            when(parseService.detectContentType(any())).thenReturn("application/pdf");
            when(fileHashService.calculateHash(any(MultipartFile.class))).thenReturn("hash-4");
            when(knowledgeBaseRepository.findByFileHash("hash-4")).thenReturn(Optional.empty());
            when(storageService.uploadKnowledgeBase(any()))
                .thenThrow(new BusinessException(ErrorCode.STORAGE_UPLOAD_FAILED, "文件存储失败"));

            assertThatThrownBy(() -> batchService.uploadBatchFile(1L, pdfFile(), null, null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件存储失败");

            ArgumentCaptor<KbUploadBatchItemEntity> captor = ArgumentCaptor.forClass(KbUploadBatchItemEntity.class);
            verify(batchItemRepository).save(captor.capture());
            assertThat(captor.getValue().getStatus()).isEqualTo(KbBatchItemStatus.FAILED);
            verify(persistenceService, never()).saveKnowledgeBaseWithBatchItem(
                any(), any(), any(), anyString(), anyString(), anyString(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("批次列表与详情")
    class BatchQueries {

        @Test
        @DisplayName("批次列表按状态聚合，存在未完成明细即为进行中")
        void shouldListBatchesWithAggregatedCounts() {
            KbUploadBatchEntity b1 = batch(1L);
            KbUploadBatchEntity b2 = batch(2L);
            Pageable pageable = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "id"));
            when(batchRepository.findAll(pageable)).thenReturn(new PageImpl<>(List.of(b1, b2), pageable, 2));

            List<Object[]> rows = new ArrayList<>();
            rows.add(new Object[]{1L, KbBatchItemStatus.PENDING, 1L});
            rows.add(new Object[]{1L, KbBatchItemStatus.COMPLETED, 2L});
            rows.add(new Object[]{2L, KbBatchItemStatus.FAILED, 1L});
            rows.add(new Object[]{2L, KbBatchItemStatus.DUPLICATE_SKIPPED, 1L});
            when(batchItemRepository.countByBatchIdsGroupByStatus(List.of(1L, 2L))).thenReturn(rows);

            List<KbBatchListItemDTO> result = batchService.listBatches(20);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).total()).isEqualTo(3);
            assertThat(result.get(0).pending()).isEqualTo(1);
            assertThat(result.get(0).status()).isEqualTo("PROCESSING");
            assertThat(result.get(1).status()).isEqualTo("COMPLETED");
            assertThat(result.get(1).failed()).isEqualTo(1);
            assertThat(result.get(1).duplicateSkipped()).isEqualTo(1);
        }

        @Test
        @DisplayName("批次详情返回全部明细和计数")
        void shouldReturnBatchDetailWithItems() {
            when(batchRepository.findById(1L)).thenReturn(Optional.of(batch(1L)));

            KbUploadBatchItemEntity done = new KbUploadBatchItemEntity();
            done.setBatchId(1L);
            done.setKbId(11L);
            done.setFileName("a.pdf");
            done.setStatus(KbBatchItemStatus.COMPLETED);
            KbUploadBatchItemEntity failed = new KbUploadBatchItemEntity();
            failed.setBatchId(1L);
            failed.setKbId(12L);
            failed.setFileName("b.pdf");
            failed.setStatus(KbBatchItemStatus.FAILED);
            failed.setError("解析失败");
            when(batchItemRepository.findByBatchIdOrderByIdAsc(1L)).thenReturn(List.of(done, failed));

            KbBatchDetailDTO detail = batchService.getBatchDetail(1L);

            assertThat(detail.total()).isEqualTo(2);
            assertThat(detail.completed()).isEqualTo(1);
            assertThat(detail.failed()).isEqualTo(1);
            assertThat(detail.status()).isEqualTo("COMPLETED");
            assertThat(detail.items()).hasSize(2);
            assertThat(detail.items().get(1).error()).isEqualTo("解析失败");
        }

        @Test
        @DisplayName("批次详情不存在时抛出 NOT_FOUND")
        void shouldThrowWhenDetailNotFound() {
            when(batchRepository.findById(9L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> batchService.getBatchDetail(9L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("上传批次不存在");
        }
    }
}
