package interview.guide.modules.voiceinterview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import interview.guide.modules.voiceinterview.config.VoiceInterviewProperties;
import interview.guide.modules.voiceinterview.volc.VolcSpeechProtocol;
import interview.guide.modules.voiceinterview.volc.VolcSpeechProtocol.VolcServerMessage;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 火山方舟 Agent Plan 实时语音识别（双向流式 bigmodel_async）。
 *
 * <p>协议：WebSocket + 自定义二进制帧（见 {@link VolcSpeechProtocol}），
 * 上行 JSON/gzip，音频按段分包推送；下行逐包返回，含 VAD 分句的
 * {@code definite} 标记，用来区分「中间结果」与「定稿句子」。
 *
 * <p>未配置专属 API Key 时不阻塞应用启动，仅在真正开始识别时通过 onError 报错。
 */
@Slf4j
@Service
public class VolcAsrService implements AsrService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 官方建议 100-200ms 分包；VAD 判停窗口与其配套 */
    private static final int END_WINDOW_MS = 800;
    private static final long READY_WAIT_MS = 1200;
    private static final long SEND_TIMEOUT_SECONDS = 3;

    private final VoiceInterviewProperties properties;
    private final HttpClient httpClient;

    private String url;
    private String apiKey;
    private String resourceId;
    private String modelName;
    private String format;
    private int sampleRate;
    private int bits;
    private int channel;
    private boolean enableItn;
    private boolean enablePunc;
    private boolean enableDdc;
    private boolean enableNonstream;

    private final Map<String, VolcSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public VolcAsrService(VoiceInterviewProperties properties) {
        this.properties = properties;
        applyConfig(properties);
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    @Override
    public void reload(VoiceInterviewProperties properties) {
        applyConfig(properties);
        log.info("VolcAsrService reloaded: url={}, resourceId={}, format={}/{}Hz",
            url, resourceId, format, sampleRate);
    }

    private void applyConfig(VoiceInterviewProperties properties) {
        VoiceInterviewProperties.VolcAsrConfig asr = properties.getVolc().getAsr();
        this.url = asr.getUrl();
        this.apiKey = asr.getApiKey();
        this.resourceId = asr.getResourceId();
        this.modelName = asr.getModelName();
        this.format = asr.getFormat();
        this.sampleRate = asr.getSampleRate();
        this.bits = asr.getBits();
        this.channel = asr.getChannel();
        this.enableItn = asr.isEnableItn();
        this.enablePunc = asr.isEnablePunc();
        this.enableDdc = asr.isEnableDdc();
        this.enableNonstream = asr.isEnableNonstream();
    }

    @PostConstruct
    public void init() {
        if (trimToNull(apiKey) == null) {
            log.warn("VolcAsrService 未配置专属 API Key（VOLC_AGENT_PLAN_VOICE_API_KEY）；若 asr-provider=volcengine 将无法识别");
        } else {
            log.info("VolcAsrService initialized: url={}, resourceId={}", url, resourceId);
        }
    }

    // ===== AsrService =====

    @Override
    public void startTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            startLocked(sessionId, onFinal, onPartial, onReady, onError);
        }
    }

    @Override
    public void restartTranscription(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError) {
        synchronized (lockForSession(sessionId)) {
            log.info("[Session: {}] 重启火山 ASR（stop + start）", sessionId);
            stopTranscription(sessionId);
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            startLocked(sessionId, onFinal, onPartial, onReady, onError);
            for (int attempt = 0; attempt < 10; attempt++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (isReady(sessionId)) {
                    log.info("[Session: {}] 火山 ASR 重连已就绪", sessionId);
                    return;
                }
            }
            log.warn("[Session: {}] 火山 ASR 重连后 1s 内未就绪", sessionId);
        }
    }

    private void startLocked(
        String sessionId,
        Consumer<String> onFinal,
        Consumer<String> onPartial,
        Runnable onReady,
        Consumer<Throwable> onError) {
        if (sessions.containsKey(sessionId)) {
            throw new IllegalStateException("Session already exists: " + sessionId);
        }
        if (trimToNull(apiKey) == null) {
            IllegalStateException ex = new IllegalStateException(
                "火山方舟 ASR 未配置专属 API Key（Agent Plan 专属 Key），请在设置页配置");
            if (onError != null) {
                onError.accept(ex);
            }
            return;
        }

        VolcSession session = new VolcSession(sessionId, onFinal, onPartial, onReady, onError);
        sessions.put(sessionId, session);

        try {
            httpClient.newWebSocketBuilder()
                .header("X-Api-Key", apiKey)
                .header("X-Api-Resource-Id", resourceId)
                .header("X-Api-Request-Id", UUID.randomUUID().toString())
                .header("X-Api-Connect-Id", UUID.randomUUID().toString())
                .header("X-Api-Sequence", "-1")
                .buildAsync(URI.create(url), new VolcAsrListener(session))
                .whenComplete((ws, error) -> {
                    if (error != null) {
                        session.fail(error);
                    }
                });
            log.info("[Session: {}] 火山 ASR 连接中: {}", sessionId, url);
        } catch (Exception e) {
            sessions.remove(sessionId, session);
            session.fail(e);
        }
    }

    @Override
    public void sendAudio(String sessionId, byte[] audioData) {
        VolcSession session = sessions.get(sessionId);
        if (session == null) {
            throw new IllegalStateException("No active session found: " + sessionId);
        }
        if (audioData == null || audioData.length == 0) {
            return;
        }
        try {
            if (!session.awaitReady(READY_WAIT_MS)) {
                throw new IllegalStateException("ASR session not ready: " + sessionId);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR session ready wait interrupted: " + sessionId, e);
        }

        WebSocket webSocket = session.webSocket;
        if (webSocket == null || !session.ready) {
            throw new IllegalStateException("ASR session not ready: " + sessionId);
        }
        try {
            byte[] packet = VolcSpeechProtocol.buildAudioPacket(session.nextSeq(), audioData, false);
            webSocket.sendBinary(ByteBuffer.wrap(packet), true).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("[Session: {}] 火山 ASR 音频发送失败", sessionId, e);
            throw new IllegalStateException("ASR append failed: " + sessionId, e);
        }
    }

    @Override
    public void stopTranscription(String sessionId) {
        synchronized (lockForSession(sessionId)) {
            VolcSession session = sessions.remove(sessionId);
            sessionLocks.remove(sessionId);
            if (session == null) {
                log.warn("[Session: {}] 尝试停止不存在的火山 ASR 会话", sessionId);
                return;
            }
            session.closing = true;
            WebSocket webSocket = session.webSocket;
            if (webSocket != null) {
                try {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "stop")
                        .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (Exception e) {
                    log.debug("[Session: {}] 关闭火山 ASR 连接时异常: {}", sessionId, e.getMessage());
                }
            }
            session.ready = false;
            log.info("[Session: {}] 火山 ASR 会话已停止", sessionId);
        }
    }

    @Override
    public boolean isReady(String sessionId) {
        VolcSession session = sessions.get(sessionId);
        return session != null && session.ready;
    }

    @Override
    public boolean hasActiveSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    @PreDestroy
    public void destroy() {
        log.info("Destroying VolcAsrService with {} active sessions", sessions.size());
        sessions.keySet().forEach(sessionId -> {
            try {
                stopTranscription(sessionId);
            } catch (Exception e) {
                log.error("[Session: {}] 火山 ASR 清理失败", sessionId, e);
            }
        });
    }

    // ===== 内部实现 =====

    private Object lockForSession(String sessionId) {
        return sessionLocks.computeIfAbsent(sessionId, key -> new Object());
    }

    private final class VolcAsrListener implements WebSocket.Listener {

        private final VolcSession session;
        private final ByteArrayOutputStream partialFrame = new ByteArrayOutputStream();

        private VolcAsrListener(VolcSession session) {
            this.session = session;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            session.webSocket = webSocket;
            try {
                byte[] request = VolcSpeechProtocol.buildFullClientRequest(
                    session.nextSeq(), buildRequestPayload());
                webSocket.sendBinary(ByteBuffer.wrap(request), true)
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                session.markReady();
                log.info("[Session: {}] 火山 ASR 连接就绪: {}", session.sessionId, resourceId);
                if (session.onReady != null) {
                    session.onReady.run();
                }
            } catch (Exception e) {
                session.fail(e);
            }
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            if (!last) {
                partialFrame.writeBytes(chunk);
                webSocket.request(1);
                return null;
            }
            byte[] frame = chunk;
            if (partialFrame.size() > 0) {
                partialFrame.writeBytes(chunk);
                frame = partialFrame.toByteArray();
                partialFrame.reset();
            }
            handleFrame(session, frame);
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            session.ready = false;
            if (!session.closing) {
                log.warn("[Session: {}] 火山 ASR 连接被关闭: status={}, reason={}",
                    session.sessionId, statusCode, reason);
                sessions.remove(session.sessionId, session);
                session.fail(new IllegalStateException("火山 ASR 连接已关闭: " + statusCode));
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            session.ready = false;
            if (!session.closing) {
                log.error("[Session: {}] 火山 ASR 连接异常: {}", session.sessionId, error.getMessage(), error);
                sessions.remove(session.sessionId, session);
                session.fail(error);
            }
        }
    }

    private byte[] buildRequestPayload() {
        ObjectNode root = MAPPER.createObjectNode();
        root.putObject("user").put("uid", "interview-guide");
        ObjectNode audio = root.putObject("audio");
        audio.put("format", format);
        audio.put("codec", "raw");
        audio.put("rate", sampleRate);
        audio.put("bits", bits);
        audio.put("channel", channel);
        ObjectNode request = root.putObject("request");
        request.put("model_name", modelName);
        request.put("enable_itn", enableItn);
        request.put("enable_punc", enablePunc);
        request.put("enable_ddc", enableDdc);
        request.put("show_utterances", true);
        request.put("enable_nonstream", enableNonstream);
        request.put("end_window_size", END_WINDOW_MS);
        try {
            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalStateException("构建火山 ASR 请求参数失败", e);
        }
    }

    private void handleFrame(VolcSession session, byte[] frame) {
        VolcServerMessage message;
        try {
            message = VolcSpeechProtocol.parseFrame(frame);
        } catch (Exception e) {
            log.warn("[Session: {}] 火山 ASR 帧解析失败: {}", session.sessionId, e.getMessage());
            return;
        }
        if (message.isError()) {
            log.error("[Session: {}] 火山 ASR 返回错误: code={}, payload={}",
                session.sessionId, message.code(), abbreviate(message.jsonPayload()));
            session.fail(new IllegalStateException("火山 ASR 服务错误 code=" + message.code()));
            return;
        }
        if (message.jsonPayload().isBlank()) {
            return;
        }
        try {
            JsonNode result = MAPPER.readTree(message.jsonPayload()).path("result");
            emitResults(session, result, message.lastPackage());
        } catch (Exception e) {
            log.warn("[Session: {}] 火山 ASR 结果解析失败: {}", session.sessionId, e.getMessage());
        }
    }

    /**
     * 从下行结果中提取文本：
     * <ul>
     *   <li>{@code utterances[*].definite=true} 的句子（按出现顺序去重）→ onFinal</li>
     *   <li>末尾未定稿的分句 → onPartial</li>
     *   <li>末包兜底：还有未定稿文本时按定稿处理</li>
     * </ul>
     */
    private void emitResults(VolcSession session, JsonNode result, boolean lastPackage) {
        JsonNode utterances = result.path("utterances");
        List<JsonNode> items = new ArrayList<>();
        if (utterances.isArray()) {
            utterances.forEach(items::add);
        }

        StringBuilder newlyFinal = new StringBuilder();
        String pendingPartial = "";
        for (int i = 0; i < items.size(); i++) {
            JsonNode utterance = items.get(i);
            String text = utterance.path("text").asText("");
            if (utterance.path("definite").asBoolean(false)) {
                if (i >= session.finalizedUtteranceCount) {
                    newlyFinal.append(text);
                    session.finalizedUtteranceCount = i + 1;
                }
            } else {
                pendingPartial = text;
            }
        }

        if (newlyFinal.length() > 0) {
            String sentence = newlyFinal.toString().trim();
            if (!sentence.isEmpty()) {
                session.onFinal.accept(sentence);
            }
            return;
        }
        if (!pendingPartial.isBlank()) {
            if (session.onPartial != null) {
                session.onPartial.accept(pendingPartial.trim());
            }
            return;
        }
        String text = result.path("text").asText("").trim();
        if (text.isEmpty()) {
            return;
        }
        if (lastPackage || session.finalizedUtteranceCount == 0) {
            session.onFinal.accept(text);
        } else if (session.onPartial != null) {
            session.onPartial.accept(text);
        }
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

    private static final class VolcSession {

        private final String sessionId;
        private final Consumer<String> onFinal;
        private final Consumer<String> onPartial;
        private final Runnable onReady;
        private final Consumer<Throwable> onError;
        private final CountDownLatch readyLatch = new CountDownLatch(1);
        private final AtomicInteger sequence = new AtomicInteger(1);

        private volatile WebSocket webSocket;
        private volatile boolean ready;
        private volatile boolean closing;
        private volatile int finalizedUtteranceCount;

        private VolcSession(
            String sessionId,
            Consumer<String> onFinal,
            Consumer<String> onPartial,
            Runnable onReady,
            Consumer<Throwable> onError) {
            this.sessionId = sessionId;
            this.onFinal = onFinal;
            this.onPartial = onPartial;
            this.onReady = onReady;
            this.onError = onError;
        }

        private int nextSeq() {
            return sequence.getAndIncrement();
        }

        private void markReady() {
            ready = true;
            readyLatch.countDown();
        }

        private boolean awaitReady(long millis) throws InterruptedException {
            if (ready) {
                return true;
            }
            readyLatch.await(millis, TimeUnit.MILLISECONDS);
            return ready;
        }

        private void fail(Throwable error) {
            ready = false;
            if (onError != null) {
                onError.accept(error);
            }
        }
    }
}
