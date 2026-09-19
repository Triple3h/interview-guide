package interview.guide.modules.llmprovider.dto;

import lombok.Builder;
import lombok.Data;

/**
 * TTS 语音合成配置：provider 表示当前生效的提供方，两个子块分别是各提供方的独立配置。
 */
@Data
@Builder
public class TtsConfigDTO {
  private String provider;
  private DashscopeTtsConfig dashscope;
  private VolcTtsConfig volcengine;

  @Data
  @Builder
  public static class DashscopeTtsConfig {
    private String model;
    private String maskedApiKey;
    private String voice;
    private String format;
    private int sampleRate;
    private String mode;
    private String languageType;
    private float speechRate;
    private int volume;
  }

  @Data
  @Builder
  public static class VolcTtsConfig {
    private String url;
    private String resourceId;
    private String speaker;
    private String maskedApiKey;
    private String format;
    private int sampleRate;
  }
}
