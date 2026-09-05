package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KbBatchItemDTO;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 知识库批量上传服务
 * 一次批量上传（多选文件/选择文件夹）作为一个批次解析任务：
 * 批次创建 -> 逐个文件校验/去重/存储/落库/入队 -> 消费端串行解析并联动明细状态
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseBatchService {

    private final KbUploadBatchRepository batchRepository;
    private final KbUploadBatchItemRepository batchItemRepository;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final KnowledgeBaseParseService parseService;
    private final VectorizeStreamProducer vectorizeStreamProducer;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    private static final int DEFAULT_BATCH_LIMIT = 20;
    private static final int MAX_BATCH_LIMIT = 100;
    private static final DateTimeFormatter DEFAULT_BATCH_NAME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 创建上传批次
     *
     * @param name 批次名称（可选，默认"批量上传 yyyy-MM-dd HH:mm"）
     */
    public CreateKbBatchResponse createBatch(String name) {
        KbUploadBatchEntity batch = new KbUploadBatchEntity();
        batch.setName(normalizeBatchName(name));
        KbUploadBatchEntity saved = batchRepository.save(batch);
        log.info("上传批次已创建: batchId={}, name={}", saved.getId(), saved.getName());
        return new CreateKbBatchResponse(saved.getId(), saved.getName());
    }

    /**
     * 批次内上传单个文件
     * 任何结果（成功入队/重复跳过/校验拒绝/处理失败）都会留痕到批次明细，驱动进度展示
     *
     * @param batchId      批次ID
     * @param file         上传文件
     * @param category     分类（可选，文件夹上传时来自第一级子文件夹名）
     * @param relativePath 相对路径（可选，展示用）
     * @param name         知识库名称（可选，单文件自定义命名）
     */
    public KbBatchFileUploadResult uploadBatchFile(Long batchId, MultipartFile file,
                                                   String category, String relativePath, String name) {
        KbUploadBatchEntity batch = batchRepository.findById(batchId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上传批次不存在"));

        String normalizedCategory = normalizeCategory(category);
        String fileName = file.getOriginalFilename();

        KbUploadBatchItemEntity item = new KbUploadBatchItemEntity();
        item.setBatchId(batch.getId());
        item.setFileName(fileName != null && !fileName.isBlank() ? fileName : "未命名文件");
        item.setRelativePath(normalizeRelativePath(relativePath));
        item.setCategory(normalizedCategory);
        item.setFileSize(file.getSize());

        // 1. 文件校验，失败记为 REJECTED 并回传错误
        try {
            fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");
            String contentType = parseService.detectContentType(file);
            fileValidationService.validateContentType(
                contentType,
                fileName,
                fileValidationService::isKnowledgeBaseMimeType,
                fileValidationService::isMarkdownExtension,
                "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
            );
        } catch (BusinessException e) {
            saveItemTerminal(item, KbBatchItemStatus.REJECTED, e.getMessage());
            throw e;
        }

        // 2. 去重、存储、落库、入队，失败记为 FAILED 并回传错误
        try {
            String fileHash = fileHashService.calculateHash(file);
            Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
            if (existingKb.isPresent()) {
                log.info("批量上传检测到重复知识库: batchId={}, hash={}, existingKbId={}",
                    batchId, fileHash, existingKb.get().getId());
                persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
                item.setKbId(existingKb.get().getId());
                saveItemTerminal(item, KbBatchItemStatus.DUPLICATE_SKIPPED, "与已有知识库重复，已跳过");
                return new KbBatchFileUploadResult(
                    true, existingKb.get().getId(), item.getId(), KbBatchItemStatus.DUPLICATE_SKIPPED.name());
            }

            // S3 调用在事务外
            String storageKey = storageService.uploadKnowledgeBase(file);
            String storageUrl = storageService.getFileUrl(storageKey);

            // 同一事务保存知识库元数据与批次明细
            KbUploadBatchItemEntity savedItem = persistenceService.saveKnowledgeBaseWithBatchItem(
                file, name, normalizedCategory, storageKey, storageUrl, fileHash, batch.getId(), item);

            // 发送解析+向量化任务
            boolean sent = vectorizeStreamProducer.sendVectorizeTask(savedItem.getKbId());
            if (!sent) {
                saveItemTerminal(savedItem, KbBatchItemStatus.FAILED, "解析任务入队失败");
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析任务入队失败，请稍后重试");
            }

            log.info("批量上传文件已入队: batchId={}, itemId={}, kbId={}, file={}",
                batchId, savedItem.getId(), savedItem.getKbId(), savedItem.getFileName());
            return new KbBatchFileUploadResult(
                false, savedItem.getKbId(), savedItem.getId(), KbBatchItemStatus.PENDING.name());

        } catch (BusinessException e) {
            saveItemTerminal(item, KbBatchItemStatus.FAILED, e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("批量上传文件处理失败: batchId={}, file={}", batchId, fileName, e);
            saveItemTerminal(item, KbBatchItemStatus.FAILED, "文件处理失败: " + e.getMessage());
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件处理失败，请稍后重试");
        }
    }

    /**
     * 批次列表（含聚合计数，最新在前）
     */
    @Transactional(readOnly = true)
    public List<KbBatchListItemDTO> listBatches(int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_BATCH_LIMIT);
        Pageable pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "id"));
        List<KbUploadBatchEntity> batches = batchRepository.findAll(pageable).getContent();
        if (batches.isEmpty()) {
            return List.of();
        }

        List<Long> batchIds = batches.stream().map(KbUploadBatchEntity::getId).toList();
        Map<Long, Map<KbBatchItemStatus, Long>> countMap = aggregateCounts(
            batchItemRepository.countByBatchIdsGroupByStatus(batchIds));

        return batches.stream()
            .map(batch -> toListItemDTO(batch, countMap.getOrDefault(batch.getId(), Map.of())))
            .toList();
    }

    /**
     * 批次详情（含全部文件明细）
     */
    @Transactional(readOnly = true)
    public KbBatchDetailDTO getBatchDetail(Long batchId) {
        KbUploadBatchEntity batch = batchRepository.findById(batchId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "上传批次不存在"));

        List<KbUploadBatchItemEntity> items = batchItemRepository.findByBatchIdOrderByIdAsc(batchId);
        Map<KbBatchItemStatus, Long> counts = new EnumMap<>(KbBatchItemStatus.class);
        items.forEach(i -> counts.merge(i.getStatus(), 1L, Long::sum));

        List<KbBatchItemDTO> itemDTOs = items.stream()
            .map(item -> new KbBatchItemDTO(
                item.getId(),
                item.getKbId(),
                item.getFileName(),
                item.getRelativePath(),
                item.getCategory(),
                item.getFileSize(),
                item.getStatus().name(),
                item.getError(),
                item.getCreatedAt()))
            .toList();

        long total = items.size();
        long pending = counts.getOrDefault(KbBatchItemStatus.PENDING, 0L);
        long processing = counts.getOrDefault(KbBatchItemStatus.PROCESSING, 0L);

        return new KbBatchDetailDTO(
            batch.getId(),
            batch.getName(),
            deriveBatchStatus(pending, processing),
            total,
            pending,
            processing,
            counts.getOrDefault(KbBatchItemStatus.COMPLETED, 0L),
            counts.getOrDefault(KbBatchItemStatus.FAILED, 0L),
            counts.getOrDefault(KbBatchItemStatus.DUPLICATE_SKIPPED, 0L),
            counts.getOrDefault(KbBatchItemStatus.REJECTED, 0L),
            batch.getCreatedAt(),
            itemDTOs);
    }

    /**
     * 统计进行中的批次数量（管理页角标）
     */
    @Transactional(readOnly = true)
    public long countActiveBatches() {
        return batchItemRepository.countByStatusIn(List.of(KbBatchItemStatus.PENDING, KbBatchItemStatus.PROCESSING));
    }

    private KbBatchListItemDTO toListItemDTO(KbUploadBatchEntity batch, Map<KbBatchItemStatus, Long> counts) {
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        long pending = counts.getOrDefault(KbBatchItemStatus.PENDING, 0L);
        long processing = counts.getOrDefault(KbBatchItemStatus.PROCESSING, 0L);
        return new KbBatchListItemDTO(
            batch.getId(),
            batch.getName(),
            total,
            pending,
            processing,
            counts.getOrDefault(KbBatchItemStatus.COMPLETED, 0L),
            counts.getOrDefault(KbBatchItemStatus.FAILED, 0L),
            counts.getOrDefault(KbBatchItemStatus.DUPLICATE_SKIPPED, 0L),
            counts.getOrDefault(KbBatchItemStatus.REJECTED, 0L),
            deriveBatchStatus(pending, processing),
            batch.getCreatedAt());
    }

    /**
     * 批次状态：存在待处理/处理中明细即为进行中
     */
    private String deriveBatchStatus(long pending, long processing) {
        return pending + processing > 0 ? "PROCESSING" : "COMPLETED";
    }

    private Map<Long, Map<KbBatchItemStatus, Long>> aggregateCounts(List<Object[]> rows) {
        Map<Long, Map<KbBatchItemStatus, Long>> result = new HashMap<>();
        for (Object[] row : rows) {
            Long batchId = (Long) row[0];
            KbBatchItemStatus status = (KbBatchItemStatus) row[1];
            Long count = (Long) row[2];
            result.computeIfAbsent(batchId, k -> new EnumMap<>(KbBatchItemStatus.class)).put(status, count);
        }
        return result;
    }

    private void saveItemTerminal(KbUploadBatchItemEntity item, KbBatchItemStatus status, String error) {
        item.setStatus(status);
        item.setError(truncateError(error));
        item.touchUpdatedAt();
        batchItemRepository.save(item);
    }

    private String truncateError(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > 500 ? error.substring(0, 500) : error;
    }

    private String normalizeBatchName(String name) {
        if (name != null && !name.isBlank()) {
            String trimmed = name.trim();
            return trimmed.length() > 200 ? trimmed.substring(0, 200) : trimmed;
        }
        return "批量上传 " + LocalDateTime.now().format(DEFAULT_BATCH_NAME_FORMAT);
    }

    private String normalizeCategory(String category) {
        if (category == null) {
            return null;
        }
        String trimmed = category.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 100 ? trimmed.substring(0, 100) : trimmed;
    }

    private String normalizeRelativePath(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        String trimmed = relativePath.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > 1000 ? trimmed.substring(0, 1000) : trimmed;
    }
}
