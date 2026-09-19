package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 火山方舟语音真实接口探针：默认跳过，设置环境变量后才会真正调用云端接口。
 *
 * <pre>
 * VOLC_AGENT_PLAN_VOICE_API_KEY=xxx ./gradlew :app:test --tests '*VolcVoiceProbeTest*' --no-daemon
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "VOLC_AGENT_PLAN_VOICE_API_KEY", matches = ".+")
@DisplayName("火山方舟语音接口探针（需 VOLC_AGENT_PLAN_VOICE_API_KEY）")
class VolcVoiceProbeTest {

    private static final int SAMPLE_RATE = 16000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final int CHUNK_MS = 200;

    private VoiceInterviewProperties properties() {
        VoiceInterviewProperties properties = new VoiceInterviewProperties();
        String apiKey = System.getenv("VOLC_AGENT_PLAN_VOICE_API_KEY");
        properties.getVolc().getAsr().setApiKey(apiKey);
        properties.getVolc().getTts().setApiKey(apiKey);
        return properties;
    }

    @Test
    @DisplayName("TTS：合成一句文本应返回 PCM 字节")
    void synthesizeReturnsAudio() {
        VolcTtsService service = new VolcTtsService(properties());

        byte[] audio = service.synthesize("你好，这是一次语音合成探针测试。");

        assertTrue(audio.length > 0, "未收到音频，请检查专属 API Key / Resource ID / 音色配置");
    }

    @Test
    @DisplayName("ASR：连接就绪并能持续推送音频包")
    void asrSessionBecomesReady() throws Exception {
        VolcAsrService service = new VolcAsrService(properties());
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        String sessionId = "volc-probe-session";

        service.startTranscription(sessionId, text -> {}, text -> {}, ready::countDown,
            failure::set);

        try {
            assertTrue(ready.await(10, TimeUnit.SECONDS),
                "ASR 未就绪: " + (failure.get() != null ? failure.get().getMessage() : "超时"));
            assertTrue(service.isReady(sessionId), "ASR 会话未处于就绪状态");

            byte[] chunk = new byte[SAMPLE_RATE * BYTES_PER_SAMPLE * CHUNK_MS / 1000];
            for (int i = 0; i < 5; i++) {
                service.sendAudio(sessionId, chunk);
            }
            assertDoesNotThrow(() -> service.sendAudio(sessionId, chunk));
        } finally {
            service.stopTranscription(sessionId);
        }
    }
}
