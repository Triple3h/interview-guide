package interview.guide.infrastructure.file;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.OcrProperties;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 扫描版 PDF OCR 兜底解析
 * Tika 提取不到文本时，用 PDFBox 把每页渲染成 PNG，
 * 逐页调用视觉模型转写为 Markdown 后拼接
 * 仅用于知识库异步向量化链路；resume 同步链路不接入
 */
@Slf4j
@Service
public class DocumentOcrService {

    private final LlmProviderRegistry llmProviderRegistry;
    private final TextCleaningService textCleaningService;
    private final OcrProperties properties;
    private final ResourceLoader resourceLoader;

    public DocumentOcrService(
        LlmProviderRegistry llmProviderRegistry,
        TextCleaningService textCleaningService,
        OcrProperties properties,
        ResourceLoader resourceLoader
    ) {
        this.llmProviderRegistry = llmProviderRegistry;
        this.textCleaningService = textCleaningService;
        this.properties = properties;
        this.resourceLoader = resourceLoader;
    }

    /**
     * 判断解析结果是否需要 OCR 兜底：
     * 文件为 PDF 且（解析结果为空或文本量低于阈值），即典型的扫描件症状
     */
    public boolean needsOcr(ParsedDocument parsed, String fileName) {
        if (!properties.isEnabled() || !isPdfFileName(fileName)) {
            return false;
        }
        if (parsed == null) {
            return true;
        }
        if (parsed.format() != ParsedDocument.DocumentFormat.RICH_TEXT) {
            return false;
        }
        String content = parsed.content();
        return content == null || content.trim().length() < properties.getMinParsedChars();
    }

    /**
     * 下载 PDF 并逐页 OCR，返回转写后的文档
     */
    public ParsedDocument parseScannedDocument(FileStorageService storageService, String storageKey, String fileName) {
        log.info("开始 OCR 兜底解析: 文件={}, 页数上限={}, 模型={}",
            fileName, properties.getMaxPages(), properties.getModel());
        byte[] fileBytes = storageService.downloadFile(storageKey);
        if (fileBytes == null || fileBytes.length == 0) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "下载文件失败");
        }

        String promptText = loadPrompt();
        List<String> pageTexts = ocrPdfPages(fileBytes, fileName, promptText);
        String cleaned = textCleaningService.cleanText(String.join("\n\n", pageTexts));
        log.info("OCR 兜底解析完成: 文件={}, 页数={}, 文本长度={}", fileName, pageTexts.size(), cleaned.length());
        return new ParsedDocument(cleaned, ParsedDocument.DocumentFormat.MARKDOWN);
    }

    private List<String> ocrPdfPages(byte[] fileBytes, String fileName, String promptText) {
        try (PDDocument document = PDDocument.load(fileBytes)) {
            int pageCount = document.getNumberOfPages();
            if (pageCount > properties.getMaxPages()) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED,
                    "扫描版 PDF 共 " + pageCount + " 页，超过 OCR 上限 " + properties.getMaxPages()
                        + " 页，如有需要可调大 app.ai.ocr.max-pages");
            }

            PDFRenderer renderer = new PDFRenderer(document);
            float scale = properties.getDpi() / 72.0f;
            List<String> pageTexts = new ArrayList<>(pageCount);
            int failedPages = 0;
            for (int i = 0; i < pageCount; i++) {
                try {
                    String pageText = ocrPage(renderer.renderImage(i, scale), i + 1, promptText);
                    pageTexts.add(pageText.isBlank()
                        ? "【第 " + (i + 1) + " 页无文字内容】"
                        : pageText.trim());
                } catch (Exception e) {
                    // 单页失败不中断整个文档
                    failedPages++;
                    log.error("OCR 单页识别失败: 文件={}, 页码={}, error={}", fileName, i + 1, e.getMessage(), e);
                    pageTexts.add("\n【第 " + (i + 1) + " 页识别失败】\n");
                }
                log.info("OCR 进度: 文件={}, 第 {}/{} 页完成", fileName, i + 1, pageCount);
            }
            if (failedPages == pageCount) {
                throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "OCR 识别失败：所有页面均无法识别");
            }
            return pageTexts;
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            log.error("读取 PDF 失败: 文件={}, error={}", fileName, e.getMessage(), e);
            throw new BusinessException(ErrorCode.KNOWLEDGE_BASE_PARSE_FAILED, "读取 PDF 失败: " + e.getMessage());
        }
    }

    private String ocrPage(BufferedImage image, int pageNumber, String promptText) throws IOException {
        byte[] pngBytes = toPng(image);
        log.info("OCR 视觉模型调用开始: 页码={}, 模型={}, 图片大小={}KB",
            pageNumber, properties.getModel(), pngBytes.length / 1024);

        ChatClient chatClient = llmProviderRegistry.getPlainChatClient(properties.getProvider());
        String content = chatClient.prompt()
            // 请求级覆盖模型：vision 模型与 chat 模型共用同一 provider endpoint
            .options(OpenAiChatOptions.builder().model(properties.getModel()))
            .user(user -> user.text(promptText)
                .media(MimeTypeUtils.IMAGE_PNG, new ByteArrayResource(pngBytes)))
            .call()
            .content();

        if (content == null || content.isBlank()) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "视觉模型返回空内容");
        }
        return content;
    }

    private byte[] toPng(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) {
            throw new IOException("PNG 编码失败");
        }
        return out.toByteArray();
    }

    private String loadPrompt() {
        try {
            return resourceLoader.getResource(properties.getPromptPath())
                .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "加载 OCR 提示词失败: " + e.getMessage());
        }
    }

    private boolean isPdfFileName(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return false;
        }
        return fileName.substring(dotIndex + 1).equalsIgnoreCase("pdf");
    }
}
