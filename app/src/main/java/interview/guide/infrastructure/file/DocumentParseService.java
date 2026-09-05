package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * 通用文档解析服务
 * 按文件类型分发到解析策略链（Markdown/纯文本直读，其余走 Tika 兜底），
 * 统一做文本清洗后返回带格式标记的解析结果
 * 供知识库和简历模块共同使用
 */
@Slf4j
@Service
public class DocumentParseService {

    private final TextCleaningService textCleaningService;
    private final List<DocumentParseStrategy> strategies;

    public DocumentParseService(TextCleaningService textCleaningService, List<DocumentParseStrategy> strategies) {
        this.textCleaningService = textCleaningService;
        this.strategies = strategies;
    }

    /**
     * 解析上传的文件
     *
     * @param file 上传的文件（支持PDF、DOCX、DOC、TXT、MD等）
     * @return 解析结果
     */
    public ParsedDocument parseDocument(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        log.info("开始解析文件: {}", fileName);

        // 处理空文件
        if (file.isEmpty() || file.getSize() == 0) {
            log.warn("文件为空: {}", fileName);
            return ParsedDocument.empty();
        }

        try (InputStream inputStream = file.getInputStream()) {
            return parseInternal(inputStream.readAllBytes(), fileName);
        } catch (IOException e) {
            log.error("读取上传文件失败: {}", fileName, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析字节数组形式的文件内容
     *
     * @param fileBytes 文件字节数组
     * @param fileName  原始文件名（用于日志）
     * @return 解析结果
     */
    public ParsedDocument parseDocument(byte[] fileBytes, String fileName) {
        log.info("开始解析文件（从字节数组）: {}", fileName);

        // 处理空文件
        if (fileBytes == null || fileBytes.length == 0) {
            log.warn("文件为空: {}", fileName);
            return ParsedDocument.empty();
        }
        return parseInternal(fileBytes, fileName);
    }

    /**
     * 从存储下载文件并解析内容
     *
     * @param storageService   文件存储服务
     * @param storageKey       存储键
     * @param originalFilename 原始文件名
     * @return 解析结果
     */
    public ParsedDocument downloadAndParseDocument(
        FileStorageService storageService, String storageKey, String originalFilename) {
        try {
            byte[] fileBytes = storageService.downloadFile(storageKey);
            if (fileBytes == null || fileBytes.length == 0) {
                throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
            }
            return parseDocument(fileBytes, originalFilename);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("下载并解析文件失败: storageKey={}, error={}", storageKey, e.getMessage(), e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载并解析文件失败: " + e.getMessage());
        }
    }

    // ===== 以下 String API 保留给只需要纯文本的调用方（简历模块等） =====

    /**
     * 解析上传的文件，提取文本内容
     */
    public String parseContent(MultipartFile file) {
        return parseDocument(file).content();
    }

    /**
     * 解析字节数组形式的文件内容
     */
    public String parseContent(byte[] fileBytes, String fileName) {
        return parseDocument(fileBytes, fileName).content();
    }

    /**
     * 从存储下载文件并解析内容
     */
    public String downloadAndParseContent(
        FileStorageService storageService, String storageKey, String originalFilename) {
        return downloadAndParseDocument(storageService, storageKey, originalFilename).content();
    }

    /**
     * 选择首个支持的解析策略执行，并统一做文本清洗
     */
    private ParsedDocument parseInternal(byte[] fileBytes, String fileName) {
        DocumentParseStrategy strategy = strategies.stream()
            .filter(candidate -> candidate.supports(fileName))
            .findFirst()
            .orElseThrow(() -> new BusinessException(
                ErrorCode.INTERNAL_ERROR, "没有支持该文件类型的解析策略: " + fileName));

        ParsedDocument parsed = strategy.parse(fileBytes, fileName);
        String cleaned = textCleaningService.cleanText(parsed.content());
        log.info("文件解析成功: 文件={}, 策略={}, 格式={}, 文本长度={}",
            fileName, strategy.getClass().getSimpleName(), parsed.format(), cleaned.length());
        return new ParsedDocument(cleaned, parsed.format());
    }
}
