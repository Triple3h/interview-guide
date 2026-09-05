package interview.guide.infrastructure.file;

import interview.guide.common.ai.LlmProviderRegistry;
import interview.guide.common.config.OcrProperties;
import interview.guide.common.exception.BusinessException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("扫描版 PDF OCR 兜底解析测试")
class DocumentOcrServiceTest {

    @Mock
    private LlmProviderRegistry llmProviderRegistry;
    @Mock
    private TextCleaningService textCleaningService;
    @Mock
    private ResourceLoader resourceLoader;
    @Mock
    private FileStorageService fileStorageService;

    private OcrProperties properties;
    private DocumentOcrService ocrService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        properties = new OcrProperties();
        ocrService = new DocumentOcrService(llmProviderRegistry, textCleaningService, properties, resourceLoader);

        when(resourceLoader.getResource(anyString()))
            .thenReturn(new ByteArrayResource("OCR 转写提示词".getBytes(StandardCharsets.UTF_8)));
        when(textCleaningService.cleanText(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    /**
     * 程序化生成一个空的 N 页 PDF（空白页可正常渲染）
     */
    private byte[] generatePdf(int pageCount) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pageCount; i++) {
                document.addPage(new PDPage());
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    /**
     * 按 VoiceContextCompressorTest 的惯例手动 stub ChatClient 三层 fluent 链
     */
    private ChatClient mockChatClient(ChatClient.CallResponseSpec callSpec) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(spec);
        when(spec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(spec);
        when(spec.user(any(Consumer.class))).thenReturn(spec);
        when(spec.call()).thenReturn(callSpec);
        when(llmProviderRegistry.getPlainChatClient(any())).thenReturn(chatClient);
        return chatClient;
    }

    @Nested
    @DisplayName("OCR 兜底判定")
    class NeedsOcr {

        @Test
        @DisplayName("PDF 且提取文本为空/过短时需要 OCR")
        void shouldNeedOcrForShortPdfText() {
            assertTrue(ocrService.needsOcr(
                new ParsedDocument("", ParsedDocument.DocumentFormat.RICH_TEXT), "scan.pdf"));
            assertTrue(ocrService.needsOcr(
                new ParsedDocument("短", ParsedDocument.DocumentFormat.RICH_TEXT), "scan.pdf"));
            assertTrue(ocrService.needsOcr(null, "scan.pdf"));
        }

        @Test
        @DisplayName("PDF 文本量充足时不需要 OCR")
        void shouldNotNeedOcrForRichPdfText() {
            String longText = "这是一段足够长的文本内容，超过了 OCR 触发的最小阈值判定。".repeat(3);
            assertFalse(ocrService.needsOcr(
                new ParsedDocument(longText, ParsedDocument.DocumentFormat.RICH_TEXT), "doc.pdf"));
        }

        @Test
        @DisplayName("非 PDF 文件与 Markdown/纯文本格式不需要 OCR")
        void shouldNotNeedOcrForOtherTypes() {
            assertFalse(ocrService.needsOcr(
                new ParsedDocument("", ParsedDocument.DocumentFormat.RICH_TEXT), "doc.docx"));
            assertFalse(ocrService.needsOcr(
                new ParsedDocument("", ParsedDocument.DocumentFormat.MARKDOWN), "scan.pdf"));
            assertFalse(ocrService.needsOcr(
                new ParsedDocument("", ParsedDocument.DocumentFormat.PLAIN_TEXT), "notes.txt"));
            assertFalse(ocrService.needsOcr(null, null));
        }

        @Test
        @DisplayName("关闭配置后一律不需要 OCR")
        void shouldRespectEnabledFlag() {
            properties.setEnabled(false);

            assertFalse(ocrService.needsOcr(
                new ParsedDocument("", ParsedDocument.DocumentFormat.RICH_TEXT), "scan.pdf"));
        }
    }

    @Nested
    @DisplayName("逐页 OCR 解析")
    class ParseScannedDocument {

        @Test
        @DisplayName("两页 PDF 逐页调用视觉模型并拼接结果")
        void shouldOcrEachPageAndJoin() throws IOException {
            byte[] pdfBytes = generatePdf(2);
            when(fileStorageService.downloadFile("key")).thenReturn(pdfBytes);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            mockChatClient(callSpec);
            when(callSpec.content()).thenReturn("第一页的题目内容", "第二页的答案内容");

            ParsedDocument parsed = ocrService.parseScannedDocument(fileStorageService, "key", "scan.pdf");

            assertEquals(ParsedDocument.DocumentFormat.MARKDOWN, parsed.format());
            assertTrue(parsed.content().contains("第一页的题目内容"));
            assertTrue(parsed.content().contains("第二页的答案内容"));
            verify(llmProviderRegistry, times(2)).getPlainChatClient(any());
        }

        @Test
        @DisplayName("请求级覆盖视觉模型")
        void shouldOverrideModelPerRequest() throws IOException {
            properties.setModel("qwen-vl-test");
            byte[] pdfBytes = generatePdf(1);
            when(fileStorageService.downloadFile("key")).thenReturn(pdfBytes);
            ChatClient chatClient = mock(ChatClient.class);
            ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            when(llmProviderRegistry.getPlainChatClient(any())).thenReturn(chatClient);
            when(chatClient.prompt()).thenReturn(spec);
            when(spec.options(any(OpenAiChatOptions.Builder.class))).thenReturn(spec);
            when(spec.user(any(Consumer.class))).thenReturn(spec);
            when(spec.call()).thenReturn(callSpec);
            when(callSpec.content()).thenReturn("识别内容");

            ocrService.parseScannedDocument(fileStorageService, "key", "scan.pdf");

            var optionsCaptor = org.mockito.ArgumentCaptor.forClass(OpenAiChatOptions.Builder.class);
            verify(spec).options(optionsCaptor.capture());
            assertEquals("qwen-vl-test", optionsCaptor.getValue().build().getModel());
        }

        @Test
        @DisplayName("单页识别失败时占位继续，其余页正常")
        void shouldContinueWhenSinglePageFails() throws IOException {
            byte[] pdfBytes = generatePdf(2);
            when(fileStorageService.downloadFile("key")).thenReturn(pdfBytes);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            mockChatClient(callSpec);
            when(callSpec.content()).thenThrow(new RuntimeException("模型限流")).thenReturn("第二页内容");

            ParsedDocument parsed = ocrService.parseScannedDocument(fileStorageService, "key", "scan.pdf");

            assertTrue(parsed.content().contains("【第 1 页识别失败】"));
            assertTrue(parsed.content().contains("第二页内容"));
        }

        @Test
        @DisplayName("视觉模型返回空白时按失败页处理")
        void shouldTreatBlankModelResponseAsFailure() throws IOException {
            byte[] pdfBytes = generatePdf(2);
            when(fileStorageService.downloadFile("key")).thenReturn(pdfBytes);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            mockChatClient(callSpec);
            when(callSpec.content()).thenReturn("", "第二页内容");

            ParsedDocument parsed = ocrService.parseScannedDocument(fileStorageService, "key", "scan.pdf");

            assertTrue(parsed.content().contains("【第 1 页识别失败】"));
            assertTrue(parsed.content().contains("第二页内容"));
        }

        @Test
        @DisplayName("全部页面失败时抛出解析失败异常")
        void shouldThrowWhenAllPagesFail() throws IOException {
            byte[] pdfBytes = generatePdf(2);
            when(fileStorageService.downloadFile("key")).thenReturn(pdfBytes);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            mockChatClient(callSpec);
            when(callSpec.content()).thenThrow(new RuntimeException("模型不可用"));

            BusinessException exception = assertThrows(BusinessException.class,
                () -> ocrService.parseScannedDocument(fileStorageService, "key", "scan.pdf"));

            assertTrue(exception.getMessage().contains("所有页面均无法识别"));
        }

        @Test
        @DisplayName("页数超过上限时直接报错")
        void shouldThrowWhenPageCountExceedsLimit() throws IOException {
            properties.setMaxPages(1);
            byte[] pdfBytes = generatePdf(2);
            when(fileStorageService.downloadFile("key")).thenReturn(pdfBytes);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            mockChatClient(callSpec);
            when(callSpec.content()).thenReturn("内容");

            BusinessException exception = assertThrows(BusinessException.class,
                () -> ocrService.parseScannedDocument(fileStorageService, "key", "scan.pdf"));

            assertTrue(exception.getMessage().contains("超过 OCR 上限"));
        }

        @Test
        @DisplayName("下载文件失败时抛出异常")
        void shouldThrowWhenDownloadFails() {
            when(fileStorageService.downloadFile("missing")).thenReturn(null);

            assertThrows(BusinessException.class,
                () -> ocrService.parseScannedDocument(fileStorageService, "missing", "scan.pdf"));
        }

        @Test
        @DisplayName("OCR 结果经过文本清洗")
        void shouldCleanTranscribedText() throws IOException {
            byte[] pdfBytes = generatePdf(1);
            when(fileStorageService.downloadFile("key")).thenReturn(pdfBytes);
            ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
            mockChatClient(callSpec);
            when(callSpec.content()).thenReturn("转写文本");
            when(textCleaningService.cleanText(eq("转写文本"))).thenReturn("清洗后的文本");

            ParsedDocument parsed = ocrService.parseScannedDocument(fileStorageService, "key", "scan.pdf");

            assertEquals("清洗后的文本", parsed.content());
        }
    }
}
