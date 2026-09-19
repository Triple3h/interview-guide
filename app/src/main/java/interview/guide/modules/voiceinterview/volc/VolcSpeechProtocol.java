package interview.guide.modules.voiceinterview.volc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 火山方舟 Agent Plan 语音的二进制帧协议（ASR 双向流式 / TTS 双向流式共用）。
 *
 * <p>帧结构（官方协议，4 字节头 + 字段区 + payload）：
 * <pre>
 *   byte0 = (版本 0b0001 &lt;&lt; 4) | 头长（1 = 4 字节）
 *   byte1 = (消息类型 &lt;&lt; 4) | flags
 *   byte2 = (序列化 0b0001=JSON &lt;&lt; 4) | 压缩（0b0001=GZIP）
 *   byte3 = 保留 0x00
 *   之后按 flags 依次为：序号(int32) / 事件号(int32) / payloadSize(uint32) + payload
 * </pre>
 * 客户端上行使用 JSON + GZIP；服务端下行可能是 GZIP 或未压缩（按压缩位判断）。
 *
 * <p>参考：https://docs.volcengine.com/docs/ark/agent-plan-personal-voice-model
 */
public final class VolcSpeechProtocol {

    public static final int VERSION_V1 = 0b0001;
    public static final int HEADER_SIZE_4 = 0b0001;

    public static final int MSG_TYPE_FULL_CLIENT_REQUEST = 0b0001;
    public static final int MSG_TYPE_AUDIO_ONLY_REQUEST = 0b0010;
    public static final int MSG_TYPE_FULL_SERVER_RESPONSE = 0b1001;
    public static final int MSG_TYPE_ERROR_RESPONSE = 0b1111;

    public static final int FLAG_NO_SEQUENCE = 0b0000;
    public static final int FLAG_POS_SEQUENCE = 0b0001;
    public static final int FLAG_LAST_PACKAGE = 0b0010;
    public static final int FLAG_NEG_WITH_SEQUENCE = 0b0011;
    public static final int FLAG_WITH_EVENT = 0b0100;

    public static final int SERIALIZATION_JSON = 0b0001;
    public static final int COMPRESSION_NONE = 0b0000;
    public static final int COMPRESSION_GZIP = 0b0001;

    /** 事件号：连接/会话生命周期（下行 50 起为连接类，100 起为会话类）。 */
    public static final int EVENT_START_CONNECTION = 1;
    public static final int EVENT_FINISH_CONNECTION = 2;
    public static final int EVENT_START_SESSION = 100;
    public static final int EVENT_FINISH_SESSION = 102;
    public static final int EVENT_CONNECTION_STARTED = 50;
    public static final int EVENT_CONNECTION_FAILED = 51;
    public static final int EVENT_CONNECTION_FINISHED = 52;
    public static final int EVENT_SESSION_STARTED = 150;
    public static final int EVENT_SESSION_FINISHED = 152;
    public static final int EVENT_SESSION_FAILED = 153;

    private VolcSpeechProtocol() {
    }

    /** 完整客户端请求（ASR 首包：音频参数 + 请求参数，GZIP 压缩的 JSON）。 */
    public static byte[] buildFullClientRequest(int seq, byte[] jsonPayload) {
        return buildFrame(MSG_TYPE_FULL_CLIENT_REQUEST, FLAG_POS_SEQUENCE, seq, gzip(jsonPayload));
    }

    /** 纯音频包；{@code last=true} 时序号取负并置 NEG_WITH_SEQUENCE，表示音频发送结束。 */
    public static byte[] buildAudioPacket(int seq, byte[] audio, boolean last) {
        int flags = last ? FLAG_NEG_WITH_SEQUENCE : FLAG_POS_SEQUENCE;
        int sequence = last ? -Math.abs(seq) : seq;
        return buildFrame(MSG_TYPE_AUDIO_ONLY_REQUEST, flags, sequence, gzip(audio));
    }

    /** 解析服务端下行帧。 */
    public static VolcServerMessage parseFrame(byte[] frame) {
        if (frame == null || frame.length < 4) {
            throw new IllegalArgumentException("火山协议帧长度不足: " + (frame == null ? 0 : frame.length));
        }
        int headerSize = (frame[0] & 0x0f) * 4;
        int messageType = (frame[1] >> 4) & 0x0f;
        int flags = frame[1] & 0x0f;
        int serialization = (frame[2] >> 4) & 0x0f;
        int compression = frame[2] & 0x0f;

        int offset = Math.min(headerSize, frame.length);
        ByteBuffer buffer = ByteBuffer.wrap(frame, offset, frame.length - offset);

        int sequence = 0;
        boolean lastPackage = false;
        int event = 0;
        if ((flags & FLAG_POS_SEQUENCE) != 0 && buffer.remaining() >= Integer.BYTES) {
            sequence = buffer.getInt();
        }
        if ((flags & FLAG_LAST_PACKAGE) != 0 || (flags & FLAG_NEG_WITH_SEQUENCE) == FLAG_NEG_WITH_SEQUENCE) {
            lastPackage = true;
        }
        if ((flags & FLAG_WITH_EVENT) != 0 && buffer.remaining() >= Integer.BYTES) {
            event = buffer.getInt();
        }

        int code = 0;
        byte[] payload = new byte[0];
        if (messageType == MSG_TYPE_ERROR_RESPONSE && buffer.remaining() >= 2 * Integer.BYTES) {
            code = buffer.getInt();
            payload = readPayload(buffer, buffer.getInt());
        } else if (messageType == MSG_TYPE_FULL_SERVER_RESPONSE && buffer.remaining() >= Integer.BYTES) {
            payload = readPayload(buffer, buffer.getInt());
        } else if (buffer.hasRemaining()) {
            payload = readPayload(buffer, buffer.remaining());
        }

        if (compression == COMPRESSION_GZIP && payload.length > 0) {
            payload = gunzip(payload);
        }
        String json = serialization == SERIALIZATION_JSON && payload.length > 0
            ? new String(payload, StandardCharsets.UTF_8)
            : "";
        return new VolcServerMessage(messageType, flags, sequence, lastPackage, event, code, json);
    }

    public static byte[] gzip(byte[] data) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
            gzip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("火山协议 gzip 压缩失败", e);
        }
    }

    public static byte[] gunzip(byte[] data) {
        try (GZIPInputStream in = new GZIPInputStream(new java.io.ByteArrayInputStream(data));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("火山协议 gzip 解压失败", e);
        }
    }

    private static byte[] buildFrame(int messageType, int flags, int seq, byte[] payload) {
        return buildFrame(messageType, flags, seq, payload, null);
    }

    private static byte[] buildFrame(int messageType, int flags, int seq, byte[] payload, Integer event) {
        byte[] body = payload == null ? new byte[0] : payload;
        boolean withEvent = event != null;
        int size = 4
            + Integer.BYTES                              // seq
            + (withEvent ? Integer.BYTES : 0)            // event（可选）
            + Integer.BYTES                              // payloadSize
            + body.length;
        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.put((byte) ((VERSION_V1 << 4) | HEADER_SIZE_4));
        buffer.put((byte) ((messageType << 4) | flags));
        buffer.put((byte) ((SERIALIZATION_JSON << 4) | COMPRESSION_GZIP));
        buffer.put((byte) 0x00);
        buffer.putInt(seq);
        if (withEvent) {
            buffer.putInt(event);
        }
        buffer.putInt(body.length);
        buffer.put(body);
        return buffer.array();
    }

    private static byte[] readPayload(ByteBuffer buffer, int declaredSize) {
        int size = Math.min(Math.max(declaredSize, 0), buffer.remaining());
        if (size <= 0) {
            return new byte[0];
        }
        byte[] payload = new byte[size];
        buffer.get(payload);
        return payload;
    }

    /** 服务端下行帧的解析结果。 */
    public record VolcServerMessage(
        int messageType,
        int flags,
        int sequence,
        boolean lastPackage,
        int event,
        int code,
        String jsonPayload
    ) {
        public boolean isError() {
            return messageType == MSG_TYPE_ERROR_RESPONSE || code != 0;
        }
    }
}
