package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

/**
 * TTS 提供方路由：按 {@code app.voice-interview.tts-provider}（dashscope | volcengine）
 * 把调用分发给对应实现，支持设置页保存后即时切换。
 */
@Slf4j
@Service
@Primary
public class TtsServiceRouter implements TtsService {

    private static final String PROVIDER_VOLCENGINE = "volcengine";

    private final QwenTtsService qwenTtsService;
    private final VolcTtsService volcTtsService;
    private final VoiceInterviewProperties properties;

    public TtsServiceRouter(
        QwenTtsService qwenTtsService,
        VolcTtsService volcTtsService,
        VoiceInterviewProperties properties) {
        this.qwenTtsService = qwenTtsService;
        this.volcTtsService = volcTtsService;
        this.properties = properties;
    }

    private TtsService active() {
        String provider = properties.getTtsProvider();
        return provider != null && PROVIDER_VOLCENGINE.equalsIgnoreCase(provider.trim())
            ? volcTtsService
            : qwenTtsService;
    }

    @Override
    public byte[] synthesize(String text) {
        return active().synthesize(text);
    }

    @Override
    public void reload(VoiceInterviewProperties properties) {
        qwenTtsService.reload(properties);
        volcTtsService.reload(properties);
        log.info("TTS 提供方已刷新，当前使用: {}", properties.getTtsProvider());
    }
}
