package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;

/**
 * 语音合成（TTS）服务接口。
 *
 * <p>存在多套实现（DashScope Qwen3 Realtime / 火山方舟 Agent Plan），运行时由
 * {@link TtsServiceRouter} 按 {@code app.voice-interview.tts-provider} 选择。
 * 所有实现统一返回 <b>24kHz / 16bit / 单声道 PCM</b>（前端按该规格播放）。
 */
public interface TtsService {

    /**
     * 将一段文本合成为 PCM 音频。
     *
     * @param text 待合成文本；null / 空白返回空数组
     * @return PCM 音频字节；合成失败返回空数组（调用方按“该句跳过”处理）
     */
    byte[] synthesize(String text);

    /** 配置热更新（设置页保存后调用）。 */
    void reload(VoiceInterviewProperties properties);
}
