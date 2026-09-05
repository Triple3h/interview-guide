package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.exception.BusinessException;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KbBatchItemStatus;
import interview.guide.modules.knowledgebase.model.KbUploadBatchItemEntity;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KbUploadBatchItemRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("知识库向量化消费者测试（解析异步化 + 批次明细联动）")
class VectorizeStreamConsumerTest {

    @Mock
    private RedisService redisService;
    @Mock
    private KnowledgeBaseVectorService vectorService;
    @Mock
    private KnowledgeBaseParseService parseService;
    @Mock
    private KnowledgeBaseRepository knowledgeBaseRepository;
    @Mock
    private KbUploadBatchItemRepository batchItemRepository;

    private VectorizeStreamConsumer consumer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consumer = new VectorizeStreamConsumer(
            redisService, vectorService, parseService, knowledgeBaseRepository, batchItemRepository);
    }

    private KnowledgeBaseEntity kb(long id, String storageKey, VectorStatus status) {
        KnowledgeBaseEntity entity = new KnowledgeBaseEntity();
        entity.setId(id);
        entity.setName("测试知识库");
        entity.setOriginalFilename("test.pdf");
        entity.setStorageKey(storageKey);
        entity.setVectorStatus(status);
        return entity;
    }

    private KbUploadBatchItemEntity item(long id, long kbId, KbBatchItemStatus status) {
        KbUploadBatchItemEntity entity = new KbUploadBatchItemEntity();
        entity.setId(id);
        entity.setBatchId(1L);
        entity.setKbId(kbId);
        entity.setFileName("test.pdf");
        entity.setStatus(status);
        return entity;
    }

    @Nested
    @DisplayName("任务领取与完成状态联动")
    class StatusTransition {

        @Test
        @DisplayName("领取任务时知识库与批次明细同步置为 PROCESSING")
        void shouldMarkBothProcessing() {
            KnowledgeBaseEntity entity = kb(100L, "key", VectorStatus.PENDING);
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.of(entity));
            KbUploadBatchItemEntity item = item(9L, 100L, KbBatchItemStatus.PENDING);
            when(batchItemRepository.findByKbId(100L)).thenReturn(Optional.of(item));

            consumer.markProcessing(new VectorizeStreamConsumer.VectorizePayload(100L));

            assertThat(entity.getVectorStatus()).isEqualTo(VectorStatus.PROCESSING);
            assertThat(item.getStatus()).isEqualTo(KbBatchItemStatus.PROCESSING);
            verify(batchItemRepository).save(item);
        }

        @Test
        @DisplayName("完成时知识库与批次明细同步置为 COMPLETED")
        void shouldMarkBothCompleted() {
            KnowledgeBaseEntity entity = kb(100L, "key", VectorStatus.PROCESSING);
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.of(entity));
            KbUploadBatchItemEntity item = item(9L, 100L, KbBatchItemStatus.PROCESSING);
            when(batchItemRepository.findByKbId(100L)).thenReturn(Optional.of(item));

            consumer.markCompleted(new VectorizeStreamConsumer.VectorizePayload(100L));

            assertThat(entity.getVectorStatus()).isEqualTo(VectorStatus.COMPLETED);
            assertThat(item.getStatus()).isEqualTo(KbBatchItemStatus.COMPLETED);
        }

        @Test
        @DisplayName("失败时知识库与批次明细同步置为 FAILED 并记录错误")
        void shouldMarkBothFailed() {
            KnowledgeBaseEntity entity = kb(100L, "key", VectorStatus.PROCESSING);
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.of(entity));
            KbUploadBatchItemEntity item = item(9L, 100L, KbBatchItemStatus.PROCESSING);
            when(batchItemRepository.findByKbId(100L)).thenReturn(Optional.of(item));

            consumer.markFailed(new VectorizeStreamConsumer.VectorizePayload(100L), "解析失败");

            assertThat(entity.getVectorStatus()).isEqualTo(VectorStatus.FAILED);
            assertThat(entity.getVectorError()).isEqualTo("解析失败");
            assertThat(item.getStatus()).isEqualTo(KbBatchItemStatus.FAILED);
            assertThat(item.getError()).isEqualTo("解析失败");
        }

        @Test
        @DisplayName("非批次上传没有批次明细时只更新知识库状态")
        void shouldSkipItemUpdateWhenNoBatchItem() {
            KnowledgeBaseEntity entity = kb(100L, "key", VectorStatus.PENDING);
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.of(entity));
            when(batchItemRepository.findByKbId(100L)).thenReturn(Optional.empty());

            consumer.markProcessing(new VectorizeStreamConsumer.VectorizePayload(100L));

            assertThat(entity.getVectorStatus()).isEqualTo(VectorStatus.PROCESSING);
            verify(batchItemRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("任务跳过判定")
    class SkipCheck {

        @Test
        @DisplayName("知识库已被删除时明细记为 FAILED 并跳过")
        void shouldFailItemWhenKbDeleted() {
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.empty());
            KbUploadBatchItemEntity item = item(9L, 100L, KbBatchItemStatus.PROCESSING);
            when(batchItemRepository.findByKbId(100L)).thenReturn(Optional.of(item));

            boolean skip = consumer.shouldSkip(new VectorizeStreamConsumer.VectorizePayload(100L));

            assertThat(skip).isTrue();
            assertThat(item.getStatus()).isEqualTo(KbBatchItemStatus.FAILED);
        }

        @Test
        @DisplayName("知识库已完成后明细置为 COMPLETED 并跳过")
        void shouldCompleteItemWhenKbAlreadyCompleted() {
            when(knowledgeBaseRepository.findById(100L))
                .thenReturn(Optional.of(kb(100L, "key", VectorStatus.COMPLETED)));
            KbUploadBatchItemEntity item = item(9L, 100L, KbBatchItemStatus.PROCESSING);
            when(batchItemRepository.findByKbId(100L)).thenReturn(Optional.of(item));

            boolean skip = consumer.shouldSkip(new VectorizeStreamConsumer.VectorizePayload(100L));

            assertThat(skip).isTrue();
            assertThat(item.getStatus()).isEqualTo(KbBatchItemStatus.COMPLETED);
        }

        @Test
        @DisplayName("待处理任务不跳过")
        void shouldNotSkipPendingTask() {
            when(knowledgeBaseRepository.findById(100L))
                .thenReturn(Optional.of(kb(100L, "key", VectorStatus.PENDING)));

            boolean skip = consumer.shouldSkip(new VectorizeStreamConsumer.VectorizePayload(100L));

            assertThat(skip).isFalse();
            verify(batchItemRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("解析与向量化执行")
    class BusinessProcess {

        @Test
        @DisplayName("正常流程：从存储下载并解析后执行向量化")
        void shouldDownloadParseAndVectorize() {
            KnowledgeBaseEntity entity = kb(100L, "knowledgebases/2026/09/05/test.pdf", VectorStatus.PROCESSING);
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.of(entity));
            when(parseService.downloadAndParseContent("knowledgebases/2026/09/05/test.pdf", "test.pdf"))
                .thenReturn("提取的文本内容");

            consumer.processBusiness(new VectorizeStreamConsumer.VectorizePayload(100L));

            verify(vectorService).vectorizeAndStore(100L, "提取的文本内容");
        }

        @Test
        @DisplayName("知识库已被删除时静默跳过，不执行向量化")
        void shouldNoopWhenKbDeleted() {
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.empty());

            consumer.processBusiness(new VectorizeStreamConsumer.VectorizePayload(100L));

            verify(vectorService, never()).vectorizeAndStore(anyLong(), anyString());
        }

        @Test
        @DisplayName("存储信息缺失时抛出解析失败异常")
        void shouldThrowWhenStorageKeyMissing() {
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.of(kb(100L, null, VectorStatus.PROCESSING)));

            assertThatThrownBy(() -> consumer.processBusiness(new VectorizeStreamConsumer.VectorizePayload(100L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("文件存储信息缺失");

            verify(vectorService, never()).vectorizeAndStore(anyLong(), anyString());
        }

        @Test
        @DisplayName("解析结果为空时抛出解析失败异常")
        void shouldThrowWhenContentBlank() {
            KnowledgeBaseEntity entity = kb(100L, "key", VectorStatus.PROCESSING);
            when(knowledgeBaseRepository.findById(100L)).thenReturn(Optional.of(entity));
            when(parseService.downloadAndParseContent(anyString(), anyString())).thenReturn("   ");

            assertThatThrownBy(() -> consumer.processBusiness(new VectorizeStreamConsumer.VectorizePayload(100L)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无法从文件中提取文本内容");
        }
    }

    @Nested
    @DisplayName("重试入队")
    class Retry {

        @Test
        @DisplayName("重试时重新入队只携带 kbId 与重试次数")
        void shouldRequeueWithKbIdOnly() {
            consumer.retryMessage(new VectorizeStreamConsumer.VectorizePayload(100L), 2);

            ArgumentCaptor<java.util.Map<String, String>> captor = ArgumentCaptor.forClass(java.util.Map.class);
            verify(redisService).streamAdd(anyString(), captor.capture(), eq(1000));
            assertThat(captor.getValue())
                .containsEntry("kbId", "100")
                .containsEntry("retryCount", "2");
            assertThat(captor.getValue()).doesNotContainKey("content");
        }
    }
}
