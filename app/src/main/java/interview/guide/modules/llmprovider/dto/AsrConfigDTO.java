package interview.guide.modules.llmprovider.dto;

import lombok.Builder;
import lombok.Data;

/**
 * ASR 语音识别配置：provider 表示当前生效的提供方，两个子块分别是各提供方的独立配置。
 */
@Data
@Builder
public class AsrConfigDTO {
  private String provider;
  private DashscopeAsrConfig dashscope;
  private VolcAsrConfig volcengine;

  @Data
  @Builder
  public static class DashscopeAsrConfig {
    private String url;
    private String model;
    private String maskedApiKey;
    private String language;
    private String format;
    private int sampleRate;
    private boolean enableTurnDetection;
    private String turnDetectionType;
    private float turnDetectionThreshold;
    private int turnDetectionSilenceDurationMs;
  }

  @Data
  @Builder
  public static class VolcAsrConfig {
    private String url;
    private String resourceId;
    private String modelName;
    private String maskedApiKey;
    private String format;
    private int sampleRate;
    private int bits;
    private int channel;
    private boolean enableItn;
    private boolean enablePunc;
    private boolean enableDdc;
    private boolean enableNonstream;
    private int segmentMs;
  }
}
