package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * ASR 提供方路由：按 {@code app.voice-interview.asr-provider}（dashscope | volcengine）
 * 把调用分发给对应实现，支持设置页保存后即时切换。
 */
@Slf4j
@Service
@Primary
public class AsrServiceRouter implements AsrService {

    private static final String PROVIDER_VOLCENGINE = "volcengine";

    private final QwenAsrService qwenAsrService;
    private final VolcAsrService volcAsrService;
    private final VoiceInterviewProperties properties;

    public AsrServiceRouter(
        QwenAsrService qwenAsrService,
        VolcAsrService volcAsrService,
        VoiceInterviewProperties properties) {
        this.qwenAsrService = qwenAsrService;
        this.volcAsrService = volcAsrService;
        this.properties = properties;
    }

    private AsrService active() {
        String provider = properties.getAsrProvider();
        return provider != null && PROVIDER_VOLCENGINE.equalsIgnoreCase(provider.trim())
            ? volcAsrService
            : qwenAsrService;
    }

    @Override
    public void startTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError) {
        active().startTranscription(sessionId, onFinal, onPartial, onReady, onError);
    }

    @Override
    public void restartTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError) {
        active().restartTranscription(sessionId, onFinal, onPartial, onReady, onError);
    }

    @Override
    public void sendAudio(String sessionId, byte[] audioData) {
        active().sendAudio(sessionId, audioData);
    }

    @Override
    public void stopTranscription(String sessionId) {
        // 两个实现都停，避免切换提供方后旧连接残留
        qwenAsrService.stopTranscription(sessionId);
        volcAsrService.stopTranscription(sessionId);
    }

    @Override
    public boolean isReady(String sessionId) {
        return active().isReady(sessionId);
    }

    @Override
    public boolean hasActiveSession(String sessionId) {
        return active().hasActiveSession(sessionId);
    }

    @Override
    public void reload(VoiceInterviewProperties properties) {
        qwenAsrService.reload(properties);
        volcAsrService.reload(properties);
        log.info("ASR 提供方已刷新，当前使用: {}", properties.getAsrProvider());
    }
}
