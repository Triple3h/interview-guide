package interview.guide.infrastructure.file;

/**
 * 文档解析策略
 * 按文件类型选择不同的解析实现，返回带格式标记的解析结果。
 * 策略只负责内容提取，文本清洗由 DocumentParseService 统一处理。
 */
public interface DocumentParseStrategy {

    /**
     * 是否支持解析该文件
     *
     * @param fileName 文件名（用于扩展名判断）
     * @return true 表示可处理
     */
    boolean supports(String fileName);

    /**
     * 解析文件字节内容
     *
     * @param fileBytes 文件字节内容
     * @param fileName  原始文件名（用于日志）
     * @return 解析结果
     */
    ParsedDocument parse(byte[] fileBytes, String fileName);
}
