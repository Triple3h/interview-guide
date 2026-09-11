package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.common.transaction.TransactionalExecutor;
import interview.guide.infrastructure.file.ParsedDocument;
import interview.guide.modules.knowledgebase.repository.VectorRepository;
import interview.guide.modules.knowledgebase.service.chunking.DocumentChunkingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * 知识库向量存储服务
 * 负责文档分块、向量化和检索
 */
@Slf4j
@Service
public class KnowledgeBaseVectorService {
    
    /**
     * 阿里云 DashScope Embedding API 批量大小限制
     */
    private static final int MAX_BATCH_SIZE = 10;
    private static final String TEMP_KB_ID_PREFIX = "pending:";
    private static final String METADATA_KB_ID = "kb_id";
    private static final String METADATA_TARGET_KB_ID = "kb_target_id";
    private static final String METADATA_VECTOR_JOB_ID = "kb_vector_job_id";
    private final VectorStore vectorStore;
    private final DocumentChunkingService chunkingService;
    private final VectorRepository vectorRepository;
    private final TransactionalExecutor transactionalExecutor;
    private final KnowledgeBaseVectorizeProperties vectorizeProperties;
    /** 上一次 embedding 请求的发起时间，用于把请求速率压在供应商限流阈值内 */
    private final AtomicLong lastEmbeddingRequestAt = new AtomicLong(0L);
    private final Object embeddingRequestLock = new Object();

    @Autowired
    public KnowledgeBaseVectorService(
        VectorStore vectorStore,
        DocumentChunkingService chunkingService,
        VectorRepository vectorRepository,
        TransactionalExecutor transactionalExecutor,
        KnowledgeBaseVectorizeProperties vectorizeProperties
    ) {
        this.vectorStore = vectorStore;
        this.chunkingService = chunkingService;
        this.vectorRepository = vectorRepository;
        this.transactionalExecutor = transactionalExecutor;
        this.vectorizeProperties = vectorizeProperties;
    }

    KnowledgeBaseVectorService(VectorStore vectorStore, DocumentChunkingService chunkingService, VectorRepository vectorRepository) {
        this(vectorStore, chunkingService, vectorRepository, null, disabledThrottleProperties());
    }

    KnowledgeBaseVectorService(
        VectorStore vectorStore,
        DocumentChunkingService chunkingService,
        VectorRepository vectorRepository,
        KnowledgeBaseVectorizeProperties vectorizeProperties
    ) {
        this(vectorStore, chunkingService, vectorRepository, null, vectorizeProperties);
    }

    /** 单测专用：关闭请求间隔与退避等待，避免用例被节流拖慢 */
    private static KnowledgeBaseVectorizeProperties disabledThrottleProperties() {
        KnowledgeBaseVectorizeProperties properties = new KnowledgeBaseVectorizeProperties();
        properties.setRequestIntervalMillis(0L);
        properties.setRetryBackoffMillis(0L);
        return properties;
    }

    /**
     * 将知识库内容向量化并存储
     * @param knowledgeBaseId 知识库ID
     * @param parsed 解析后的文档内容
     * @return 写入的 chunk 数量
     */
    public int vectorizeAndStore(Long knowledgeBaseId, ParsedDocument parsed) {
        String jobId = null;
        try {
            if (knowledgeBaseId == null) {
                throw new IllegalArgumentException("knowledgeBaseId不能为空");
            }
            jobId = UUID.randomUUID().toString();
            log.info("开始向量化知识库: kbId={}, jobId={}, contentLength={}, format={}",
                knowledgeBaseId, jobId, parsed == null ? 0 : parsed.content().length(),
                parsed == null ? null : parsed.format());

            // 1. 按文档类型选择分块策略
            List<Document> chunks = chunkingService.chunk(parsed);

            log.info("文本分块完成: {} 个chunks", chunks.size());

            // 2. 为每个 chunk 添加临时 metadata，成功后再提升为正式 kb_id。
            applyPendingMetadata(chunks, knowledgeBaseId, jobId);

            // 3. 分批向量化并存储（阿里云 DashScope API 限制 batch size <= 10）
            int totalChunks = chunks.size();
            int batchCount = (totalChunks + MAX_BATCH_SIZE - 1) / MAX_BATCH_SIZE; // 向上取整
            log.info("开始分批向量化: 总共 {} 个chunks，分 {} 批处理，每批最多 {} 个",
                    totalChunks, batchCount, MAX_BATCH_SIZE);
            for (int i = 0; i < batchCount; i++) {
                int start = i * MAX_BATCH_SIZE;
                int end = Math.min(start + MAX_BATCH_SIZE, totalChunks);
                List<Document> batch = chunks.subList(start, end);
                log.debug("处理第 {}/{} 批: chunks {}-{}", i + 1, batchCount, start + 1, end);
                addBatchWithThrottle(knowledgeBaseId, batch, i + 1, batchCount);
            }
            activateVectorJob(knowledgeBaseId, jobId);
            log.info("知识库向量化完成: kbId={}, jobId={}, chunks={}, batches={}",
                    knowledgeBaseId, jobId, totalChunks, batchCount);
            return totalChunks;
        } catch (Exception e) {
            cleanupPendingVectorJob(knowledgeBaseId, jobId);
            log.error("向量化知识库失败: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED,
                "向量化知识库失败: " + e.getMessage());
        }
    }

    /**
     * 带节流与限流退避地写入一批向量：
     * 1）每次请求前保证与上一次请求间隔 {@code requestIntervalMillis}，避免打满供应商 QPS；
     * 2）命中限流（429）时按退避基数翻倍等待后重试，重试耗尽才抛出异常交给上层重试。
     */
    private void addBatchWithThrottle(Long knowledgeBaseId, List<Document> batch, int batchIndex, int batchCount) {
        int maxRetries = Math.max(0, vectorizeProperties.getRateLimitMaxRetries());
        for (int attempt = 0; ; attempt++) {
            awaitEmbeddingSlot();
            try {
                vectorStore.add(batch);
                return;
            } catch (RuntimeException e) {
                if (attempt >= maxRetries || !isRateLimitError(e)) {
                    throw e;
                }
                long backoffMillis = vectorizeProperties.retryDelayMillis(attempt);
                log.warn("embedding 请求被限流，{}ms 后重试第 {}/{} 批（kbId={}，第 {} 次重试）: {}",
                    backoffMillis, batchIndex, batchCount, knowledgeBaseId, attempt + 1, e.getMessage());
                sleepQuietly(backoffMillis);
            }
        }
    }

    /**
     * 保证两次 embedding 请求之间的最小间隔。本服务是单例，
     * 多线程同时进入时也会被串行化，等价于把请求排成一个队列逐个发出。
     */
    private void awaitEmbeddingSlot() {
        long intervalMillis = vectorizeProperties.getRequestIntervalMillis();
        if (intervalMillis <= 0) {
            return;
        }
        synchronized (embeddingRequestLock) {
            long waitMillis = lastEmbeddingRequestAt.get() + intervalMillis - System.currentTimeMillis();
            if (waitMillis > 0) {
                sleepQuietly(waitMillis);
            }
            lastEmbeddingRequestAt.set(System.currentTimeMillis());
        }
    }

    /** 判断异常链中是否为供应商限流（口径与 DashscopeLlmService 的错误归类一致） */
    private static boolean isRateLimitError(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) {
                continue;
            }
            String normalized = message.toLowerCase(Locale.ROOT);
            if (normalized.contains("429")
                || normalized.contains("too frequent")
                || normalized.contains("rate limit")
                || normalized.contains("throttl")) {
                return true;
            }
        }
        return false;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_VECTORIZATION_FAILED, "向量化任务被中断");
        }
    }

    private void applyPendingMetadata(List<Document> chunks, Long knowledgeBaseId, String jobId) {
        String pendingKbId = TEMP_KB_ID_PREFIX + knowledgeBaseId + ":" + jobId;
        chunks.forEach(chunk -> {
            chunk.getMetadata().put(METADATA_KB_ID, pendingKbId);
            chunk.getMetadata().put(METADATA_TARGET_KB_ID, knowledgeBaseId.toString());
            chunk.getMetadata().put(METADATA_VECTOR_JOB_ID, jobId);
        });
    }
    
    /**
     * 基于多个知识库进行相似度搜索
     * 
     * @param query 查询文本
     * @param knowledgeBaseIds 知识库ID列表（如果为空则搜索所有）
     * @param topK 返回top K个结果
     * @return 相关文档列表
     */
    public List<Document> similaritySearch(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        log.info("向量相似度搜索: query={}, kbIds={}, topK={}, minScore={}",
            query, knowledgeBaseIds, topK, minScore);
        
        try {
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK, 1));

            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                builder.filterExpression(buildKbFilterExpression(knowledgeBaseIds));
            }

            List<Document> results = vectorStore.similaritySearch(builder.build());
            if (results == null) {
                return List.of();
            }

            // Apply topK limiting in case VectorStore returns more than requested
            List<Document> limitedResults = results.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("搜索完成: 找到 {} 个相关文档", limitedResults.size());
            return limitedResults;
            
        } catch (Exception e) {
            log.warn("向量搜索前置过滤失败，回退到本地过滤: {}", e.getMessage());
            return similaritySearchFallback(query, knowledgeBaseIds, topK, minScore);
        }
    }

    private List<Document> similaritySearchFallback(String query, List<Long> knowledgeBaseIds, int topK, double minScore) {
        try {
            // 回退检索仍保留 topK/minScore，避免兜底路径引入过多弱相关命中
            SearchRequest.Builder builder = SearchRequest.builder()
                .query(query)
                .topK(Math.max(topK * 3, topK));
            if (minScore > 0) {
                builder.similarityThreshold(minScore);
            }

            List<Document> allResults = vectorStore.similaritySearch(builder.build());
            if (allResults == null || allResults.isEmpty()) {
                return List.of();
            }

            if (knowledgeBaseIds != null && !knowledgeBaseIds.isEmpty()) {
                allResults = allResults.stream()
                    .filter(doc -> isDocInKnowledgeBases(doc, knowledgeBaseIds))
                    .collect(Collectors.toList());
            }

            List<Document> results = allResults.stream()
                .limit(topK)
                .collect(Collectors.toList());

            log.info("回退检索完成: 找到 {} 个相关文档", results.size());
            return results;
        } catch (Exception e) {
            log.error("向量搜索失败: {}", e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_QUERY_FAILED,
                "向量搜索失败: " + e.getMessage());
        }
    }

    private boolean isDocInKnowledgeBases(Document doc, List<Long> knowledgeBaseIds) {
        Object kbId = doc.getMetadata().get("kb_id");
        if (kbId == null) {
            return false;
        }
        try {
            Long kbIdLong = kbId instanceof Long
                ? (Long) kbId
                : Long.parseLong(kbId.toString());
            return knowledgeBaseIds.contains(kbIdLong);
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private String buildKbFilterExpression(List<Long> knowledgeBaseIds) {
        String values = knowledgeBaseIds.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .map(id -> "'" + id + "'")
            .collect(Collectors.joining(", "));
        return "kb_id in [" + values + "]";
    }
    
    /**
     * 删除指定知识库的所有向量数据
     * 委托给 VectorRepository 处理
     * 
     * @param knowledgeBaseId 知识库ID
     */
    public void deleteByKnowledgeBaseId(Long knowledgeBaseId) {
        try {
            deleteByKnowledgeBaseIdStrict(knowledgeBaseId);
        } catch (Exception e) {
            log.error("删除向量数据失败: kbId={}, error={}", knowledgeBaseId, e.getMessage(), e);
            // 不抛出异常，允许继续执行其他删除操作
            // 如果确实需要严格保证，可以取消下面的注释
            // throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_DELETE_FAILED, "删除向量数据失败");
        }
    }

    private void deleteByKnowledgeBaseIdStrict(Long knowledgeBaseId) {
        runVectorRepositoryMutation(() -> vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId));
    }

    private void activateVectorJob(Long knowledgeBaseId, String jobId) {
        runVectorRepositoryMutation(() -> {
            vectorRepository.deleteByKnowledgeBaseId(knowledgeBaseId);
            vectorRepository.promoteVectorJob(knowledgeBaseId, jobId);
        });
    }

    private void cleanupPendingVectorJob(Long knowledgeBaseId, String jobId) {
        if (jobId == null) {
            return;
        }
        try {
            runVectorRepositoryMutation(() -> vectorRepository.deleteByVectorJobId(jobId));
        } catch (Exception cleanupError) {
            log.warn("清理临时向量数据失败，可后续按 jobId 补偿: kbId={}, jobId={}, error={}",
                knowledgeBaseId, jobId, cleanupError.getMessage(), cleanupError);
        }
    }

    private void runVectorRepositoryMutation(Runnable action) {
        if (transactionalExecutor == null) {
            action.run();
            return;
        }
        transactionalExecutor.run(action);
    }
}
