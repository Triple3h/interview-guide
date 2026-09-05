package interview.guide.infrastructure.file;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * 文本文档解析策略基类
 * 直接按文本解码（不经 Tika），共用扩展名匹配与字符集探测：
 * BOM 识别 -> 严格 UTF-8 解码 -> 失败回退 GBK（中文文本文档最常见的非 UTF-8 编码）
 */
public abstract class AbstractTextDocumentParseStrategy implements DocumentParseStrategy {

    @Override
    public boolean supports(String fileName) {
        String extension = extractExtension(fileName);
        return extension != null && extensions().contains(extension);
    }

    @Override
    public ParsedDocument parse(byte[] fileBytes, String fileName) {
        return new ParsedDocument(decode(fileBytes), format());
    }

    /**
     * 该策略处理的文件扩展名（小写、不含点）
     */
    protected abstract Set<String> extensions();

    /**
     * 解析结果对应的文档格式
     */
    protected abstract ParsedDocument.DocumentFormat format();

    private String extractExtension(String fileName) {
        if (fileName == null) {
            return null;
        }
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String byBom = decodeByBom(bytes);
        if (byBom != null) {
            return byBom;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("GBK"));
        }
    }

    private String decodeByBom(byte[] bytes) {
        if (hasBom(bytes, 0xEF, 0xBB, 0xBF)) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        if (hasBom(bytes, 0xFF, 0xFE)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16LE);
        }
        if (hasBom(bytes, 0xFE, 0xFF)) {
            return new String(bytes, 2, bytes.length - 2, StandardCharsets.UTF_16BE);
        }
        return null;
    }

    private boolean hasBom(byte[] bytes, int... expected) {
        if (bytes.length < expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if ((bytes[i] & 0xFF) != expected[i]) {
                return false;
            }
        }
        return true;
    }
}
