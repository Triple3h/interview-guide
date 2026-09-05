package interview.guide.modules.knowledgebase.listener;

import interview.guide.common.async.AbstractStreamConsumer;
import interview.guide.common.constant.AsyncTaskStreamConstants;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.DocumentOcrService;
import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.infrastructure.redis.RedisService;
import interview.guide.modules.knowledgebase.model.KbBatchItemStatus;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KbUploadBatchItemRepository;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseParseService;
import interview.guide.modules.knowledgebase.service.KnowledgeBaseVectorService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.stream.StreamMessageId;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * 知识库向量化 Stream 消费者
 * 负责从 Redis Stream 消费消息并执行 文件下载 -> 文本解析 -> 向量化
 * 同步更新批量上传批次明细状态，驱动解析进度展示
 */
@Slf4j
@Component
public class VectorizeStreamConsumer extends AbstractStreamConsumer<VectorizeStreamConsumer.VectorizePayload> {

    private final KnowledgeBaseVectorService vectorService;
    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KbUploadBatchItemRepository batchItemRepository;
    private final DocumentOcrService documentOcrService;

    public VectorizeStreamConsumer(
        RedisService redisService,
        KnowledgeBaseVectorService vectorService,
        KnowledgeBaseParseService parseService,
        KnowledgeBaseRepository knowledgeBaseRepository,
        KbUploadBatchItemRepository batchItemRepository,
        DocumentOcrService documentOcrService
    ) {
        super(redisService);
        this.vectorService = vectorService;
        this.parseService = parseService;
        this.knowledgeBaseRepository = knowledgeBaseRepository;
        this.batchItemRepository = batchItemRepository;
        this.documentOcrService = documentOcrService;
    }

    record VectorizePayload(Long kbId) {}

    @Override
    protected String taskDisplayName() {
        return "向量化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_GROUP_NAME;
    }

    @Override
    protected String consumerPrefix() {
        return AsyncTaskStreamConstants.KB_VECTORIZE_CONSUMER_PREFIX;
    }

    @Override
    protected String threadName() {
        return "vectorize-consumer";
    }

    @Override
    protected VectorizePayload parsePayload(StreamMessageId messageId, Map<String, String> data) {
        String kbIdStr = data.get(AsyncTaskStreamConstants.FIELD_KB_ID);
        if (kbIdStr == null) {
            log.warn("消息格式错误，跳过: messageId={}", messageId);
            return null;
        }
        return new VectorizePayload(Long.parseLong(kbIdStr));
    }

    @Override
    protected String payloadIdentifier(VectorizePayload payload) {
        return "kbId=" + payload.kbId();
    }

    @Override
    protected boolean shouldSkip(VectorizePayload payload) {
        Optional<KnowledgeBaseEntity> kbOpt = knowledgeBaseRepository.findById(payload.kbId());
        if (kbOpt.isEmpty()) {
            // 实体已被删除，同步把批次明细推到终态，避免进度永久卡住
            updateBatchItem(payload.kbId(), KbBatchItemStatus.FAILED, "知识库已被删除，任务跳过");
            return true;
        }
        if (kbOpt.get().getVectorStatus() == VectorStatus.COMPLETED) {
            updateBatchItem(payload.kbId(), KbBatchItemStatus.COMPLETED, null);
            return true;
        }
        return false;
    }

    @Override
    protected void markProcessing(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.PROCESSING, null);
        updateBatchItem(payload.kbId(), KbBatchItemStatus.PROCESSING, null);
    }

    @Override
    protected void processBusiness(VectorizePayload payload) {
        Long kbId = payload.kbId();
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId).orElse(null);
        if (kb == null) {
            log.warn("知识库已被删除，跳过向量化任务: kbId={}", kbId);
            return;
        }
        if (kb.getStorageKey() == null || kb.getStorageKey().isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "文件存储信息缺失，无法解析");
        }

        ParsedDocument parsed = parseService.downloadAndParseDocument(kb.getStorageKey(), kb.getOriginalFilename());
        if (documentOcrService.needsOcr(parsed, kb.getOriginalFilename())) {
            // Tika 提取不到足够文本（典型为扫描版 PDF），走视觉模型逐页 OCR 兜底
            log.info("Tika 提取文本不足，启用 OCR 兜底解析: kbId={}, fileName={}", kbId, kb.getOriginalFilename());
            parsed = parseService.ocrScannedDocument(kb.getStorageKey(), kb.getOriginalFilename());
        }
        if (parsed == null || parsed.isBlank()) {
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "无法从文件中提取文本内容");
        }
        int chunkCount = vectorService.vectorizeAndStore(kbId, parsed);
        kb.setChunkCount(chunkCount);
        knowledgeBaseRepository.save(kb);
    }

    @Override
    protected void markCompleted(VectorizePayload payload) {
        updateVectorStatus(payload.kbId(), VectorStatus.COMPLETED, null);
        updateBatchItem(payload.kbId(), KbBatchItemStatus.COMPLETED, null);
    }

    @Override
    protected void markFailed(VectorizePayload payload, String error) {
        updateVectorStatus(payload.kbId(), VectorStatus.FAILED, error);
        updateBatchItem(payload.kbId(), KbBatchItemStatus.FAILED, error);
    }

    @Override
    protected void retryMessage(VectorizePayload payload, int retryCount) {
        Long kbId = payload.kbId();
        try {
            Map<String, String> message = Map.of(
                AsyncTaskStreamConstants.FIELD_KB_ID, kbId.toString(),
                AsyncTaskStreamConstants.FIELD_RETRY_COUNT, String.valueOf(retryCount)
            );

            redisService().streamAdd(
                AsyncTaskStreamConstants.KB_VECTORIZE_STREAM_KEY,
                message,
                AsyncTaskStreamConstants.STREAM_MAX_LEN
            );
            log.info("向量化任务已重新入队: kbId={}, retryCount={}", kbId, retryCount);

        } catch (Exception e) {
            log.error("重试入队失败: kbId={}, error={}", kbId, e.getMessage(), e);
            markFailed(payload, truncateError("重试入队失败: " + e.getMessage()));
        }
    }

    /**
     * 更新向量化状态
     */
    private void updateVectorStatus(Long kbId, VectorStatus status, String error) {
        try {
            knowledgeBaseRepository.findById(kbId).ifPresent(kb -> {
                kb.setVectorStatus(status);
                kb.setVectorError(error);
                knowledgeBaseRepository.save(kb);
                log.debug("向量化状态已更新: kbId={}, status={}", kbId, status);
            });
        } catch (Exception e) {
            log.error("更新向量化状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }

    /**
     * 同步更新批量上传批次明细状态（非批次上传没有明细，自动跳过）
     */
    private void updateBatchItem(Long kbId, KbBatchItemStatus status, String error) {
        try {
            batchItemRepository.findByKbId(kbId).ifPresent(item -> {
                item.setStatus(status);
                item.setError(truncateError(error));
                item.touchUpdatedAt();
                batchItemRepository.save(item);
                log.debug("批次明细状态已更新: itemId={}, kbId={}, status={}", item.getId(), kbId, status);
            });
        } catch (Exception e) {
            log.error("更新批次明细状态失败: kbId={}, status={}, error={}", kbId, status, e.getMessage(), e);
        }
    }

}
