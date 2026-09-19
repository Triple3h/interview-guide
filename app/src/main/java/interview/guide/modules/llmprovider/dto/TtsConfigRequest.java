package interview.guide.modules.llmprovider.dto;

/**
 * TTS 配置更新请求。
 * provider 非空时切换生效的提供方；dashscope / volcengine 子块按字段非空覆盖（apiKey 留空表示保持原值）。
 */
public record TtsConfigRequest(
    String provider,
    DashscopePart dashscope,
    VolcPart volcengine
) {

  public record DashscopePart(
      String model,
      String apiKey,
      String voice,
      String format,
      Integer sampleRate,
      String mode,
      String languageType,
      Float speechRate,
      Integer volume
  ) {}

  public record VolcPart(
      String url,
      String apiKey,
      String resourceId,
      String speaker,
      String format,
      Integer sampleRate
  ) {}
}
