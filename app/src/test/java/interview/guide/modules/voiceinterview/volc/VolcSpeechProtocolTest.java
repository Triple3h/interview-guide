package interview.guide.modules.voiceinterview.volc;

import interview.guide.modules.voiceinterview.volc.VolcSpeechProtocol.VolcServerMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("火山语音二进制协议编解码")
class VolcSpeechProtocolTest {

    @Nested
    @DisplayName("上行帧")
    class ClientFrames {

        @Test
        @DisplayName("完整客户端请求：4 字节头 + 序号 + gzip(JSON)")
        void buildFullClientRequest() throws Exception {
            String json = "{\"request\":{\"model_name\":\"bigmodel\"}}";
            byte[] frame = VolcSpeechProtocol.buildFullClientRequest(7, json.getBytes(StandardCharsets.UTF_8));

            assertEquals(0x11, frame[0] & 0xff, "版本 1 + 头长 4 字节");
            assertEquals(VolcSpeechProtocol.MSG_TYPE_FULL_CLIENT_REQUEST, (frame[1] >> 4) & 0x0f);
            assertEquals(VolcSpeechProtocol.FLAG_POS_SEQUENCE, frame[1] & 0x0f);
            assertEquals(VolcSpeechProtocol.SERIALIZATION_JSON, (frame[2] >> 4) & 0x0f);
            assertEquals(VolcSpeechProtocol.COMPRESSION_GZIP, frame[2] & 0x0f);
            assertEquals(0x00, frame[3]);

            ByteBuffer buffer = ByteBuffer.wrap(frame, 4, frame.length - 4);
            assertEquals(7, buffer.getInt());
            int payloadSize = buffer.getInt();
            byte[] payload = new byte[payloadSize];
            buffer.get(payload);
            assertEquals(json, new String(VolcSpeechProtocol.gunzip(payload), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("音频包：普通包序号为正，末包序号为负并置 NEG_WITH_SEQUENCE")
        void buildAudioPacket() {
            byte[] audio = new byte[]{1, 2, 3, 4};

            byte[] normal = VolcSpeechProtocol.buildAudioPacket(3, audio, false);
            assertEquals(VolcSpeechProtocol.MSG_TYPE_AUDIO_ONLY_REQUEST, (normal[1] >> 4) & 0x0f);
            assertEquals(VolcSpeechProtocol.FLAG_POS_SEQUENCE, normal[1] & 0x0f);
            assertEquals(3, ByteBuffer.wrap(normal, 4, 4).getInt());

            byte[] last = VolcSpeechProtocol.buildAudioPacket(4, audio, true);
            assertEquals(VolcSpeechProtocol.FLAG_NEG_WITH_SEQUENCE, last[1] & 0x0f);
            assertEquals(-4, ByteBuffer.wrap(last, 4, 4).getInt());
        }
    }

    @Nested
    @DisplayName("下行帧")
    class ServerFrames {

        @Test
        @DisplayName("解析识别结果：带序号、末包标记与 gzip JSON")
        void parseServerResult() throws Exception {
            String json = "{\"result\":{\"text\":\"你好\",\"utterances\":[]}}";
            byte[] frame = serverFrame(
                VolcSpeechProtocol.MSG_TYPE_FULL_SERVER_RESPONSE,
                VolcSpeechProtocol.FLAG_POS_SEQUENCE | VolcSpeechProtocol.FLAG_LAST_PACKAGE,
                json,
                true);

            VolcServerMessage message = VolcSpeechProtocol.parseFrame(frame);

            assertEquals(VolcSpeechProtocol.MSG_TYPE_FULL_SERVER_RESPONSE, message.messageType());
            assertTrue(message.lastPackage());
            assertFalse(message.isError());
            assertEquals(json, message.jsonPayload());
        }

        @Test
        @DisplayName("解析错误响应：错误码置位")
        void parseErrorResponse() {
            byte[] frame = errorFrame(40000003, "{\"message\":\"invalid resource id\"}");

            VolcServerMessage message = VolcSpeechProtocol.parseFrame(frame);

            assertTrue(message.isError());
            assertEquals(40000003, message.code());
            assertEquals("{\"message\":\"invalid resource id\"}", message.jsonPayload());
        }

        @Test
        @DisplayName("未压缩的下行 JSON 也能解析")
        void parseUncompressedPayload() throws Exception {
            byte[] payload = "{\"result\":{\"text\":\"hi\"}}".getBytes(StandardCharsets.UTF_8);
            byte[] frame = serverFrame(
                VolcSpeechProtocol.MSG_TYPE_FULL_SERVER_RESPONSE,
                VolcSpeechProtocol.FLAG_POS_SEQUENCE,
                new String(payload, StandardCharsets.UTF_8),
                false);

            VolcServerMessage message = VolcSpeechProtocol.parseFrame(frame);

            assertEquals("{\"result\":{\"text\":\"hi\"}}", message.jsonPayload());
        }

        private byte[] serverFrame(int messageType, int flags, String json, boolean gzip) throws Exception {
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            if (gzip) {
                payload = VolcSpeechProtocol.gzip(payload);
            }
            ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + payload.length);
            buffer.put((byte) ((VolcSpeechProtocol.VERSION_V1 << 4) | VolcSpeechProtocol.HEADER_SIZE_4));
            buffer.put((byte) ((messageType << 4) | flags));
            buffer.put((byte) ((VolcSpeechProtocol.SERIALIZATION_JSON << 4)
                | (gzip ? VolcSpeechProtocol.COMPRESSION_GZIP : VolcSpeechProtocol.COMPRESSION_NONE)));
            buffer.put((byte) 0x00);
            buffer.putInt(1);
            buffer.putInt(payload.length);
            buffer.put(payload);
            return buffer.array();
        }

        private byte[] errorFrame(int code, String json) {
            byte[] payload = json.getBytes(StandardCharsets.UTF_8);
            ByteBuffer buffer = ByteBuffer.allocate(4 + 4 + 4 + payload.length);
            buffer.put((byte) ((VolcSpeechProtocol.VERSION_V1 << 4) | VolcSpeechProtocol.HEADER_SIZE_4));
            buffer.put((byte) (VolcSpeechProtocol.MSG_TYPE_ERROR_RESPONSE << 4));
            buffer.put((byte) (VolcSpeechProtocol.SERIALIZATION_JSON << 4));
            buffer.put((byte) 0x00);
            buffer.putInt(code);
            buffer.putInt(payload.length);
            buffer.put(payload);
            return buffer.array();
        }
    }

    @Test
    @DisplayName("gzip 往返保持一致")
    void gzipRoundTrip() throws Exception {
        byte[] data = "火山方舟语音协议压缩测试".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(data);
        }
        assertEquals("火山方舟语音协议压缩测试",
            new String(VolcSpeechProtocol.gunzip(out.toByteArray()), StandardCharsets.UTF_8));
        assertEquals("火山方舟语音协议压缩测试",
            new String(VolcSpeechProtocol.gunzip(VolcSpeechProtocol.gzip(data)), StandardCharsets.UTF_8));
    }
}
