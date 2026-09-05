package interview.guide.infrastructure.file;

import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.exception.TikaException;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Tika 解析策略
 * 兜底处理 PDF/Word 等二进制文档，从版面中提取纯文本。
 * supports 恒为 true，始终放在策略链最后
 */
@Slf4j
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class TikaDocumentParseStrategy implements DocumentParseStrategy {

    private static final int MAX_TEXT_LENGTH = 5 * 1024 * 1024; // 5MB

    @Override
    public boolean supports(String fileName) {
        return true;
    }

    @Override
    public ParsedDocument parse(byte[] fileBytes, String fileName) {
        try (InputStream inputStream = new ByteArrayInputStream(fileBytes)) {
            // 1. 创建自动检测解析器
            AutoDetectParser parser = new AutoDetectParser();

            // 2. 创建内容处理器，只接收正文，限制最大长度为 5MB
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);

            // 3. 创建元数据对象
            Metadata metadata = new Metadata();

            // 4. 创建解析上下文
            ParseContext context = new ParseContext();

            // 5. 显式指定 Parser 到 Context（增强健壮性）
            context.set(Parser.class, parser);

            // 6. 禁用嵌入文档解析（关键：避免提取图片引用和临时文件路径）
            context.set(EmbeddedDocumentExtractor.class, new NoOpEmbeddedDocumentExtractor());

            // 7. PDF 专用配置：关闭图片提取，按位置排序文本
            PDFParserConfig pdfConfig = new PDFParserConfig();
            pdfConfig.setExtractInlineImages(false);
            pdfConfig.setSortByPosition(true); // 按 x/y 坐标排序文本，改善多栏布局解析顺序
            context.set(PDFParserConfig.class, pdfConfig);

            // 8. 执行解析
            parser.parse(inputStream, handler, metadata, context);

            // 9. 返回提取的文本内容
            return new ParsedDocument(handler.toString(), ParsedDocument.DocumentFormat.RICH_TEXT);

        } catch (IOException | TikaException | SAXException e) {
            log.error("Tika 解析文件失败: {}", fileName, e);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, "文件解析失败: " + e.getMessage());
        }
    }
}
