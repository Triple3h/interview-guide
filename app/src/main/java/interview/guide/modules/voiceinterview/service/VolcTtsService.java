package interview.guide.modules.voiceinterview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 火山方舟 Agent Plan 语音合成（HTTP 单向流式 {@code /api/v3/plan/tts/unidirectional}）。
 *
 * <p>逐句合成：与现有「LLM 输出 → 切句 → 每句合成 → 拼音频推送」流程天然对齐，
 * 一次请求拿到整句音频（服务端分片返回 base64，编码格式由配置决定）。
 *
 * <p>输出约定：运行时统一按 <b>PCM 24kHz / 16bit / 单声道</b> 播放，
 * 因此配置的 {@code format} 应为 {@code pcm}（非 pcm 会告警，仍按原样返回字节）。
 */
@Slf4j
@Service
public class VolcTtsService implements TtsService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_HTTP_ENDPOINT =
        "https://openspeech.bytedance.com/api/v3/plan/tts/unidirectional";
    private static final int STREAM_END_CODE = 20000000;

    private final VoiceInterviewProperties properties;
    private final HttpClient httpClient;

    private String url;
    private String apiKey;
    private String resourceId;
    private String speaker;
    private String format;
    private int sampleRate;
    private int timeoutSeconds;

    public VolcTtsService(VoiceInterviewProperties properties) {
        this.properties = properties;
        applyConfig(properties);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTtsConnectTimeoutSeconds())))
            .build();
    }

    @Override
    public void reload(VoiceInterviewProperties properties) {
        applyConfig(properties);
        log.info("VolcTtsService reloaded: endpoint={}, resourceId={}, speaker={}, format={}/{}Hz",
            resolveHttpEndpoint(), resourceId, speaker, format, sampleRate);
    }

    private void applyConfig(VoiceInterviewProperties properties) {
        VoiceInterviewProperties.VolcTtsConfig tts = properties.getVolc().getTts();
        this.url = tts.getUrl();
        this.apiKey = tts.getApiKey();
        this.resourceId = tts.getResourceId();
        this.speaker = tts.getSpeaker();
        this.format = tts.getFormat();
        this.sampleRate = tts.getSampleRate();
        this.timeoutSeconds = Math.max(1, properties.getTtsTimeoutSeconds());
    }

    @PostConstruct
    public void init() {
        if (trimToNull(apiKey) == null) {
            log.warn("VolcTtsService 未配置专属 API Key（VOLC_AGENT_PLAN_VOICE_API_KEY）；若 tts-provider=volcengine 将无法合成");
        } else {
            log.info("VolcTtsService initialized: endpoint={}, speaker={}", resolveHttpEndpoint(), speaker);
        }
    }

    @Override
    public byte[] synthesize(String text) {
        if (text == null || text.trim().isEmpty()) {
            return new byte[0];
        }
        String key = trimToNull(apiKey);
        if (key == null) {
            log.error("火山 TTS 未配置专属 API Key，跳过合成");
            return new byte[0];
        }
        if (!"pcm".equalsIgnoreCase(trimToNull(format))) {
            log.warn("火山 TTS format={} 非 pcm，前端按 PCM 播放会异常，建议在设置页改回 pcm", format);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(resolveHttpEndpoint()))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("X-Api-Key", key)
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                .header("X-Control-Require-Usage-Tokens-Return", "*")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(buildRequestBody(text)))
                .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("火山 TTS HTTP {}: {}", response.statusCode(), abbreviate(response.body()));
                return new byte[0];
            }
            byte[] audio = parseAudio(response.body());
            if (audio.length == 0) {
                log.warn("火山 TTS 未返回音频: {}", abbreviate(response.body()));
            }
            return audio;
        } catch (Exception e) {
            log.error("火山 TTS 合成失败: {}", e.getMessage(), e);
            return new byte[0];
        }
    }

    private byte[] buildRequestBody(String text) throws Exception {
        ObjectNode root = MAPPER.createObjectNode();
        ObjectNode reqParams = root.putObject("req_params");
        reqParams.put("text", text);
        reqParams.put("speaker", speaker);
        ObjectNode audioParams = reqParams.putObject("audio_params");
        audioParams.put("format", format);
        audioParams.put("sample_rate", sampleRate);
        return MAPPER.writeValueAsBytes(root);
    }

    /** 响应为按行返回的 JSON：{@code {"code":0,"data":"<base64 音频>"}}，code=20000000 表示结束。 */
    private byte[] parseAudio(String body) {
        ByteArrayOutputStream audio = new ByteArrayOutputStream();
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            JsonNode node;
            try {
                node = MAPPER.readTree(trimmed);
            } catch (Exception e) {
                log.debug("忽略非 JSON 响应行: {}", abbreviate(trimmed));
                continue;
            }
            int code = node.path("code").asInt(0);
            if (code == STREAM_END_CODE) {
                break;
            }
            if (code > 0) {
                log.error("火山 TTS 返回错误: {}", abbreviate(trimmed));
                break;
            }
            String data = node.path("data").asText("");
            if (!data.isEmpty()) {
                try {
                    audio.writeBytes(Base64.getDecoder().decode(data));
                } catch (IllegalArgumentException e) {
                    log.debug("忽略非法 base64 音频分片");
                }
            }
        }
        return audio.toByteArray();
    }

    /**
     * 把配置里的 WebSocket 双向流地址映射到 HTTP 单向流式地址；
     * 配置了其它地址时原样使用。
     */
    String resolveHttpEndpoint() {
        String configured = trimToNull(url);
        if (configured == null) {
            return DEFAULT_HTTP_ENDPOINT;
        }
        String http = configured
            .replaceFirst("^wss://", "https://")
            .replaceFirst("^ws://", "http://");
        if (http.endsWith("/tts/bidirection")) {
            return http.substring(0, http.length() - "/tts/bidirection".length()) + "/tts/unidirectional";
        }
        if (http.endsWith("/tts/unidirectional/stream")) {
            return http.substring(0, http.length() - "/tts/unidirectional/stream".length()) + "/tts/unidirectional";
        }
        return http;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String abbreviate(String text) {
        if (text == null || text.isBlank()) {
            return "[empty]";
        }
        String normalized = text.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 200 ? normalized : normalized.substring(0, 200) + "...";
    }
}
