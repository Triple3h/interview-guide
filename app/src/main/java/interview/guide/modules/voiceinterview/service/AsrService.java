package interview.guide.modules.voiceinterview.service;

import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;

import java.util.function.Consumer;

/**
 * 实时语音识别（ASR）服务接口。
 *
 * <p>存在多套实现（DashScope Qwen3 Realtime / 火山方舟 Agent Plan），运行时由
 * {@link AsrServiceRouter} 按 {@code app.voice-interview.asr-provider} 选择。
 *
 * <p>异常约定（调用方依赖这些文案做降级处理，实现必须保持一致）：
 * <ul>
 *   <li>{@code "Session already exists: {id}"} —— 重复 start</li>
 *   <li>{@code "No active session found: {id}"} —— 无会话（可触发重连）</li>
 *   <li>{@code "ASR session not ready: {id}"} —— 尚未就绪（该片段应丢弃）</li>
 *   <li>{@code "ASR append failed: {id}"} —— 发送失败（可触发重连）</li>
 * </ul>
 */
public interface AsrService {

    /**
     * 开始一路识别会话（连接建立是异步的，通过 {@code onReady} 通知就绪）。
     *
     * @param onFinal   一句话定稿回调
     * @param onPartial 中间结果回调（可为 null）
     * @param onReady   连接就绪回调（可为 null）
     * @param onError   错误回调
     */
    void startTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError);

    /** 停止旧连接并重新建立（用于连接被服务端关闭后的恢复）。 */
    void restartTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError);

    /** 发送一段 PCM 音频（16kHz / 16bit / 单声道）。 */
    void sendAudio(String sessionId, byte[] audioData);

    /** 结束并关闭会话；会话不存在时仅告警。 */
    void stopTranscription(String sessionId);

    /** 会话存在且已就绪。 */
    boolean isReady(String sessionId);

    /** 会话是否存在。 */
    boolean hasActiveSession(String sessionId);

    /** 配置热更新（设置页保存后调用）。 */
    void reload(VoiceInterviewProperties properties);
}
