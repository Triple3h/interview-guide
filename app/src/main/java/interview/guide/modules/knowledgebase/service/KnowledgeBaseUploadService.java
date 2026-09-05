package interview.guide.modules.knowledgebase.service;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import interview.guide.infrastructure.file.FileHashService;
import interview.guide.infrastructure.file.FileStorageService;
import interview.guide.infrastructure.file.FileValidationService;
import interview.guide.modules.knowledgebase.listener.VectorizeStreamProducer;
import interview.guide.modules.knowledgebase.model.KnowledgeBaseEntity;
import interview.guide.modules.knowledgebase.model.VectorStatus;
import interview.guide.modules.knowledgebase.repository.KnowledgeBaseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.Optional;

/**
 * 知识库上传服务
 * 处理知识库上传、解析的业务逻辑
 * 向量化改为异步处理，通过 Redis Stream 实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseUploadService {

    private final KnowledgeBaseParseService parseService;
    private final KnowledgeBasePersistenceService persistenceService;
    private final FileStorageService storageService;
    private final KnowledgeBaseRepository knowledgeBaseRepository;
    private final FileValidationService fileValidationService;
    private final FileHashService fileHashService;
    private final VectorizeStreamProducer vectorizeStreamProducer;

    private static final long MAX_FILE_SIZE = 50 * 1024 * 1024; // 50MB
    
    /**
     * 上传知识库文件
     * 上传请求只做校验、去重、存储和入队；文本解析与向量化由消费端异步完成
     *
     * @param file 知识库文件
     * @param name 知识库名称（可选，如果为空则从文件名提取）
     * @param category 分类（可选）
     * @return 上传结果和存储信息（包含duplicate字段，表示是否为重复上传）
     */
    public Map<String, Object> uploadKnowledgeBase(MultipartFile file, String name, String category) {
        // 1. 验证文件
        fileValidationService.validateFile(file, MAX_FILE_SIZE, "知识库");

        String fileName = file.getOriginalFilename();
        log.info("收到知识库上传请求: {}, 大小: {} bytes, category: {}", fileName, file.getSize(), category);

        // 2. 验证文件类型
        String contentType = parseService.detectContentType(file);
        validateContentType(contentType, fileName);

        // 3. 检查知识库是否已存在（去重）
        String fileHash = fileHashService.calculateHash(file);
        Optional<KnowledgeBaseEntity> existingKb = knowledgeBaseRepository.findByFileHash(fileHash);
        if (existingKb.isPresent()) {
            log.info("检测到重复知识库: hash={}", fileHash);
            return persistenceService.handleDuplicateKnowledgeBase(existingKb.get(), fileHash);
        }

        // 4. 保存文件到RustFS
        String fileKey = storageService.uploadKnowledgeBase(file);
        String fileUrl = storageService.getFileUrl(fileKey);
        log.info("知识库已存储到RustFS: {}", fileKey);

        // 5. 保存知识库元数据到数据库（状态为 PENDING，等待异步解析）
        KnowledgeBaseEntity savedKb = persistenceService.saveKnowledgeBase(file, name, category, fileKey, fileUrl, fileHash);

        // 6. 发送解析+向量化任务到 Redis Stream（异步处理）
        boolean sent = vectorizeStreamProducer.sendVectorizeTask(savedKb.getId());
        if (!sent) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析任务入队失败，请稍后重试");
        }

        log.info("知识库上传完成，解析任务已入队: {}, kbId={}", fileName, savedKb.getId());

        // 7. 返回结果（状态为 PENDING，前端可轮询获取最新状态）
        return Map.of(
            "knowledgeBase", Map.of(
                "id", savedKb.getId(),
                "name", savedKb.getName(),
                "category", savedKb.getCategory() != null ? savedKb.getCategory() : "",
                "fileSize", savedKb.getFileSize(),
                "vectorStatus", VectorStatus.PENDING.name()
            ),
            "storage", Map.of(
                "fileKey", fileKey,
                "fileUrl", fileUrl
            ),
            "duplicate", false
        );
    }

    /**
     * 验证文件类型
     */
    private void validateContentType(String contentType, String fileName) {
        fileValidationService.validateContentType(
            contentType,
            fileName,
            fileValidationService::isKnowledgeBaseMimeType,
            fileValidationService::isMarkdownExtension,
            "不支持的文件类型: " + contentType + "，支持的类型：PDF、DOCX、DOC、TXT、MD等"
        );
    }
    
    /**
     * 重新向量化知识库（手动重试）
     * 解析与向量化由消费端异步完成，这里只负责重置状态并重新入队
     *
     * @param kbId 知识库ID
     */
    public void revectorize(Long kbId) {
        KnowledgeBaseEntity kb = knowledgeBaseRepository.findById(kbId)
            .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "知识库不存在"));

        if (kb.getStorageKey() == null || kb.getStorageKey().isBlank()) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件存储信息缺失，无法重新解析");
        }

        log.info("开始重新解析知识库: kbId={}, name={}", kbId, kb.getName());

        // 1. 更新状态为 PENDING（通过单独的 Service 保证事务生效）
        persistenceService.updateVectorStatusToPending(kbId);

        // 2. 发送解析+向量化任务到 Stream
        boolean sent = vectorizeStreamProducer.sendVectorizeTask(kbId);
        if (!sent) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "解析任务入队失败，请稍后重试");
        }

        log.info("重新解析任务已发送: kbId={}", kbId);
    }
}

