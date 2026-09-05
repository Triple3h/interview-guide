package interview.guide.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 扫描版 PDF OCR 兜底解析配置
 * 视觉模型在请求级通过 options 覆盖（与 chat 模型共用同一 provider endpoint）
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.ai.ocr")
public class OcrProperties {

    /** 是否启用 OCR 兜底 */
    private boolean enabled = true;

    /** 执行 OCR 的 provider，留空使用默认 chat provider */
    private String provider;

    /** 视觉模型名（如 qwen-vl-plus / qwen-vl-flash） */
    private String model = "qwen-vl-plus";

    /** 最多识别的页数，超过直接报错（避免占用向量消费线程过久） */
    private int maxPages = 20;

    /** 渲染 DPI（scale = dpi / 72） */
    private int dpi = 144;

    /** Tika 提取文本少于该字符数时判定为扫描件 */
    private int minParsedChars = 50;

    /** OCR 转写提示词模板路径 */
    private String promptPath = "classpath:prompts/ocr-page-transcribe.st";
}
