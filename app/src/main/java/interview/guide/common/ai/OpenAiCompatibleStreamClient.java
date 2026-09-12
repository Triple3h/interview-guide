package interview.guide.common.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import interview.guide.common.exception.BusinessException;
import interview.guide.common.exception.ErrorCode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * OpenAI 兼容 /chat/completions 的原生流式读取器（不经过 Spring AI 的聚合层）。
 * <p>
 * 为什么需要它：Spring AI 2.0 的流式聚合会在转换过程中丢掉供应商扩展字段 {@code reasoning_content}
 * （实测每个 chunk 的 metadata.reasoningContent 恒为空串），思考模型的思维链拿不到。这里直接读 SSE
 * 原始分片：思维链与正文边收边通过回调抛出（真流式），工具调用按 {@code index} 聚合（首个分片带
 * id/name/type，后续只带 arguments），聚合结果可转回 Spring AI 的 {@link AssistantMessage} 与
 * {@link ChatResponse}，继续交给 {@code ToolCallingManager} 执行工具。
 * <p>
 * 注意：思考模式下 assistant 消息必须回传 {@code reasoning_content}，否则部分供应商会返回 400
 * （reasoning_content in the thinking mode must be passed back），回传逻辑见 {@link #assistantMessage}。
 */
@Component
public class OpenAiCompatibleStreamClient {

    /** 与 Spring AI 保持一致的 reasoning metadata 键，便于 ToolCallingManager 链路共用 */
    public static final String REASONING_METADATA_KEY = "reasoningContent";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);
    private static final int ERROR_BODY_LIMIT = 300;

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public OpenAiCompatibleStreamClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    /**
     * provider 连接信息：baseUrl 必须带版本号（如 {@code https://ark.cn-beijing.volces.com/api/plan/v3}）
     */
    public record Connection(String baseUrl, String apiKey, String model, Double temperature) {
    }

    /** 单个流式分片里的工具调用增量 */
    public record ToolCallDelta(Integer index, String id, String type, String name, String argumentsFragment) {
    }

    /** 单个流式分片（三个增量字段都可能为空，finishReason 只在最后出现） */
    public record StreamDelta(String reasoningContent, String content, List<ToolCallDelta> toolCalls,
                              String finishReason) {
    }

    /** 一轮流式调用的聚合结果 */
    public record StreamedAssistant(String text, String reasoningContent,
                                    List<AssistantMessage.ToolCall> toolCalls, String finishReason) {

        public boolean hasToolCalls() {
            return toolCalls != null && !toolCalls.isEmpty();
        }

        public AssistantMessage toAssistantMessage() {
            Map<String, Object> metadata = new HashMap<>();
            if (reasoningContent != null && !reasoningContent.isBlank()) {
                metadata.put(REASONING_METADATA_KEY, reasoningContent);
            }
            return AssistantMessage.builder()
                .content(text == null ? "" : text)
                .properties(metadata)
                .toolCalls(toolCalls == null ? List.of() : toolCalls)
                .build();
        }

        public ChatResponse toChatResponse() {
            return new ChatResponse(List.of(new Generation(toAssistantMessage())));
        }
    }

    /**
     * 流式跑一轮模型调用：每个分片回调 {@code onDelta}，方法返回后给出聚合好的一轮结果。
     * 阻塞式，调用方需在独立线程上执行。
     */
    public StreamedAssistant streamTurn(Connection connection, List<Message> conversation,
                                        List<ToolDefinition> toolDefinitions,
                                        Consumer<StreamDelta> onDelta) {
        byte[] requestBody = writeJson(buildRequestBody(connection, conversation, toolDefinitions))
            .getBytes(StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(connection.baseUrl() + "/chat/completions"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + connection.apiKey())
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofByteArray(requestBody))
            .build();

        StringBuilder reasoning = new StringBuilder();
        StringBuilder text = new StringBuilder();
        Map<Integer, ToolCallBuilder> toolCallBuilders = new TreeMap<>();
        String[] finishReason = new String[1];

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "模型流式请求异常：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "模型流式请求被中断");
        }

        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                String error = new String(body.readAllBytes(), StandardCharsets.UTF_8);
                throw new BusinessException(ErrorCode.AI_SERVICE_ERROR,
                    "模型流式请求失败（HTTP " + response.statusCode() + "）：" + abbreviate(error));
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 忽略空行与 ": keep-alive" 保活行，只处理 data 帧
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String payload = line.substring(5).trim();
                    if ("[DONE]".equals(payload)) {
                        break;
                    }
                    handleChunk(payload, reasoning, text, toolCallBuilders, finishReason, onDelta);
                }
            }
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "模型流式响应读取失败：" + e.getMessage());
        }

        List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
        for (Map.Entry<Integer, ToolCallBuilder> entry : toolCallBuilders.entrySet()) {
            toolCalls.add(entry.getValue().toToolCall(entry.getKey()));
        }
        return new StreamedAssistant(text.toString(), reasoning.toString(), toolCalls, finishReason[0]);
    }

    private void handleChunk(String payload, StringBuilder reasoning, StringBuilder text,
                             Map<Integer, ToolCallBuilder> toolCallBuilders, String[] finishReason,
                             Consumer<StreamDelta> onDelta) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (JsonProcessingException e) {
            return;
        }
        JsonNode choice = root.path("choices").path(0);
        JsonNode delta = choice.path("delta");

        String reasoningDelta = textValue(delta, "reasoning_content");
        String contentDelta = textValue(delta, "content");
        String finish = textValue(choice, "finish_reason");
        if (finish != null) {
            finishReason[0] = finish;
        }

        List<ToolCallDelta> deltas = new ArrayList<>();
        for (JsonNode toolCall : delta.path("tool_calls")) {
            JsonNode function = toolCall.path("function");
            ToolCallDelta callDelta = new ToolCallDelta(
                toolCall.path("index").asInt(0),
                textValue(toolCall, "id"),
                textValue(toolCall, "type"),
                textValue(function, "name"),
                textValue(function, "arguments"));
            deltas.add(callDelta);
            toolCallBuilders.computeIfAbsent(callDelta.index(), index -> new ToolCallBuilder()).merge(callDelta);
        }

        if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
            reasoning.append(reasoningDelta);
        }
        if (contentDelta != null && !contentDelta.isEmpty()) {
            text.append(contentDelta);
        }
        if (onDelta != null) {
            onDelta.accept(new StreamDelta(reasoningDelta, contentDelta, deltas, finish));
        }
    }

    private Map<String, Object> buildRequestBody(Connection connection, List<Message> conversation,
                                                 List<ToolDefinition> toolDefinitions) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", connection.model());
        body.put("stream", true);
        body.put("stream_options", Map.of("include_usage", true));
        // 与 LlmProviderRegistry 建 OpenAiChatOptions 时保持一致：未配置 temperature 时用 0.2
        body.put("temperature", connection.temperature() != null ? connection.temperature() : 0.2);
        body.put("messages", toWireMessages(conversation));
        if (toolDefinitions != null && !toolDefinitions.isEmpty()) {
            body.put("tools", toWireTools(toolDefinitions));
        }
        return body;
    }

    private List<Map<String, Object>> toWireMessages(List<Message> conversation) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (Message message : conversation) {
            switch (message.getMessageType()) {
                case SYSTEM -> messages.add(plainMessage("system", message.getText()));
                case USER -> messages.add(plainMessage("user", message.getText()));
                case ASSISTANT -> messages.add(assistantMessage((AssistantMessage) message));
                case TOOL -> {
                    for (ToolResponseMessage.ToolResponse toolResponse
                        : ((ToolResponseMessage) message).getResponses()) {
                        Map<String, Object> wire = new LinkedHashMap<>();
                        wire.put("role", "tool");
                        wire.put("tool_call_id", toolResponse.id());
                        wire.put("content", toolResponse.responseData() == null ? "" : toolResponse.responseData());
                        messages.add(wire);
                    }
                }
                default -> {
                    // 该 Agent 只用 system/user/assistant/tool 四类消息，其余类型忽略
                }
            }
        }
        return messages;
    }

    private static Map<String, Object> plainMessage(String role, String content) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", role);
        wire.put("content", content == null ? "" : content);
        return wire;
    }

    private Map<String, Object> assistantMessage(AssistantMessage message) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("role", "assistant");
        wire.put("content", message.getText() == null ? "" : message.getText());
        if (message.getMetadata() != null) {
            Object reasoning = message.getMetadata().get(REASONING_METADATA_KEY);
            if (reasoning instanceof String reasoningText && !reasoningText.isBlank()) {
                // 思考模式多轮工具调用必须回传，缺失会被供应商拒绝（400）
                wire.put("reasoning_content", reasoningText);
            }
        }
        if (message.getToolCalls() != null && !message.getToolCalls().isEmpty()) {
            List<Map<String, Object>> toolCalls = new ArrayList<>();
            for (AssistantMessage.ToolCall toolCall : message.getToolCalls()) {
                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", toolCall.name());
                function.put("arguments", toolCall.arguments() == null ? "" : toolCall.arguments());
                Map<String, Object> wireCall = new LinkedHashMap<>();
                wireCall.put("id", toolCall.id());
                wireCall.put("type", toolCall.type() == null ? "function" : toolCall.type());
                wireCall.put("function", function);
                toolCalls.add(wireCall);
            }
            wire.put("tool_calls", toolCalls);
        }
        return wire;
    }

    private List<Map<String, Object>> toWireTools(List<ToolDefinition> toolDefinitions) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (ToolDefinition definition : toolDefinitions) {
            Map<String, Object> function = new LinkedHashMap<>();
            function.put("name", definition.name());
            function.put("description", definition.description());
            function.put("parameters", parseSchema(definition.inputSchema()));
            Map<String, Object> tool = new LinkedHashMap<>();
            tool.put("type", "function");
            tool.put("function", function);
            tools.add(tool);
        }
        return tools;
    }

    private Object parseSchema(String inputSchema) {
        if (inputSchema == null || inputSchema.isBlank()) {
            return Map.of("type", "object", "properties", Map.of());
        }
        try {
            return objectMapper.readValue(inputSchema, new TypeReference<Map<String, Object>>() { });
        } catch (JsonProcessingException e) {
            return Map.of("type", "object", "properties", Map.of());
        }
    }

    private static String textValue(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.AI_SERVICE_ERROR, "构造模型请求失败：" + e.getMessage());
        }
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= ERROR_BODY_LIMIT ? text : text.substring(0, ERROR_BODY_LIMIT) + "…";
    }

    /** 按 index 聚合工具调用的分片 */
    private static final class ToolCallBuilder {

        private String id;
        private String type;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();

        void merge(ToolCallDelta delta) {
            if (delta.id() != null && !delta.id().isEmpty()) {
                id = delta.id();
            }
            if (delta.type() != null && !delta.type().isEmpty()) {
                type = delta.type();
            }
            if (delta.name() != null && !delta.name().isEmpty()) {
                name.append(delta.name());
            }
            if (delta.argumentsFragment() != null && !delta.argumentsFragment().isEmpty()) {
                arguments.append(delta.argumentsFragment());
            }
        }

        AssistantMessage.ToolCall toToolCall(int index) {
            return new AssistantMessage.ToolCall(
                id == null || id.isEmpty() ? "call_" + index : id,
                type == null || type.isEmpty() ? "function" : type,
                name.toString(),
                arguments.toString());
        }
    }
}
