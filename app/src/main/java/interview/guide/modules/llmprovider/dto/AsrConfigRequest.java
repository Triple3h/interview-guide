package interview.guide.modules.llmprovider.dto;

/**
 * ASR 配置更新请求。
 * provider 非空时切换生效的提供方；dashscope / volcengine 子块按字段非空覆盖（apiKey 留空表示保持原值）。
 */
public record AsrConfigRequest(
    String provider,
    DashscopePart dashscope,
    VolcPart volcengine
) {

  public record DashscopePart(
      String url,
      String model,
      String apiKey,
      String language,
      String format,
      Integer sampleRate,
      Boolean enableTurnDetection,
      String turnDetectionType,
      Float turnDetectionThreshold,
      Integer turnDetectionSilenceDurationMs
  ) {}

  public record VolcPart(
      String url,
      String apiKey,
      String resourceId,
      String modelName,
      String format,
      Integer sampleRate,
      Integer bits,
      Integer channel,
      Boolean enableItn,
      Boolean enablePunc,
      Boolean enableDdc,
      Boolean enableNonstream,
      Integer segmentMs
  ) {}
}
