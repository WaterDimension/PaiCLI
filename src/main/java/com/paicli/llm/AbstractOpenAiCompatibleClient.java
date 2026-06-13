package com.paicli.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import okio.BufferedSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public abstract class AbstractOpenAiCompatibleClient implements LlmClient {

    protected static final ObjectMapper mapper = new ObjectMapper();

    // SSE 流式接口下，OkHttp 的 readTimeout 是"两次 read 之间的最大间隔"，不是请求总时长。
    // GLM-5.1 在生成大段 reasoning_content 时服务端可能长时间静默，所以默认值放宽到 300s；
    // callTimeout 作为整体兜底，覆盖极端情况下的连接半死状态。
    // 三项均可通过系统属性覆盖，便于不同模型 / 网络环境调优。
    protected static final OkHttpClient SHARED_HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(readTimeoutSeconds("paicli.llm.connect.timeout.seconds", 60), TimeUnit.SECONDS)
            .readTimeout(readTimeoutSeconds("paicli.llm.read.timeout.seconds", 300), TimeUnit.SECONDS)
            .writeTimeout(readTimeoutSeconds("paicli.llm.write.timeout.seconds", 60), TimeUnit.SECONDS)
            .callTimeout(readTimeoutSeconds("paicli.llm.call.timeout.seconds", 600), TimeUnit.SECONDS)
            .build();

    private static long readTimeoutSeconds(String key, long defaultValue) {
        String raw = System.getProperty(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? parsed : defaultValue;
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    protected abstract String getApiUrl();

    protected abstract String getModel();

    protected abstract String getApiKey();

    /**
     * 检查是否应该在请求历史中发送 reasoning_content。
     *
     * @return 如果应该发送 reasoning_content，则返回true；否则返回false。
     */
    protected boolean shouldSendReasoningContentInRequestHistory() {
        return false;
    }

    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools) throws IOException {
        return chat(messages, tools, StreamListener.NO_OP);
    }


    /**
     * 发送对话请求到 LLM API， 流式接收 响应，并实时通知 UI 更新。
     *
     * @param messages 消息列表，包含用户输入和工具调用。
     * @param tools 工具列表，包含可用的工具。
     * @param listener 流式响应监听器，用于处理响应数据。
     * @return 包含模型响应的 ChatResponse 对象。
     * @throws IOException 如果请求失败或响应解析失败。
     */
    @Override
    public ChatResponse chat(List<Message> messages, List<Tool> tools, StreamListener listener) throws IOException {
        /*
            1. 初始化流式监听器
            - StreamListener 是一个接口，定义了流式输出回调方法
            - NO_OP 是空实现（什么都不做），用于测试或不需要实时显示的场景
            作用 ：防止 listener 为 null 导致后续 NPE（空指针异常）
        */
        StreamListener streamListener = listener == null ? StreamListener.NO_OP : listener;
        RequestBody body = RequestBody.create(
                buildRequestBody(messages, tools).toString(),
                MediaType.parse("application/json")
        );

        // 2. 构建并发送 HTTP 请求
        Request.Builder request = new Request.Builder()
                .url(getApiUrl())
                .header("Authorization", "Bearer " + getApiKey())
                .header("Content-Type", "application/json")
                .post(body);
        customizeRequest(request);
        Request builtRequest = request.build();

        // 3. 执行请求并处理响应
        // newCall(request) 创建一个调用对象（Call）；execute() 同步执行 请求（阻塞当前线程直到收到响应）
        try (Response response = httpClient().newCall(builtRequest).execute()) {
            ResponseBody responseBodyObj = response.body();
            if (!response.isSuccessful()) {
                String errorBody = responseBodyObj != null ? responseBodyObj.string() : "无响应体";
                throw new IOException("API请求失败: " + response.code() + " - " + errorBody);
            }
            if (responseBodyObj == null) {
                throw new IOException("API返回空响应体");
            }
        // 4. 解析响应体
        // BufferedSource 是 OkHttp 提供的缓冲输入流，用于高效读取响应体
        // readUtf8Line() 读取 UTF-8 编码的行，直到遇到换行符或文件结束
            BufferedSource source = responseBodyObj.source();
            // 初始化累加变量
            String role = "assistant";
            StringBuilder content = new StringBuilder();
            StringBuilder reasoning = new StringBuilder();
            List<ToolCallAccumulator> toolAccumulators = new ArrayList<>();
            int inputTokens = 0;
            int outputTokens = 0;
            int cachedInputTokens = 0;

            // 逐行解析 SSE 流，直到遇到 [DONE] 标志或流结束
            while (!source.exhausted()) {   // 检查流是否读完
                String line = source.readUtf8Line(); // 读取一行 UTF-8 文本（遇到 \n 停止）
                if (line == null) {
                    break;
                }

                String trimmed = line.trim();
                if (trimmed.isEmpty() || !trimmed.startsWith("data:")) {
                    continue;
                }

                String payload = trimmed.substring("data:".length()).trim();
                if (payload.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(payload)) {
                    break;
                }

                // 解析 JSON 并提取token统计
                JsonNode root = mapper.readTree(payload);
                JsonNode error = root.path("error");
                if (!error.isMissingNode() && !error.isNull()) {
                    throw new IOException("API请求失败: " + formatStreamingError(error));
                }
                JsonNode usage = root.path("usage");
                if (!usage.isMissingNode()) {
                    inputTokens = usage.path("prompt_tokens").asInt(inputTokens);   // 输入token数
                    outputTokens = usage.path("completion_tokens").asInt(outputTokens);  // 输出token数
                    cachedInputTokens = parseCachedInputTokens(usage, cachedInputTokens); // 缓存命中token数
                }

                JsonNode choices = root.path("choices");
                if (!choices.isArray() || choices.isEmpty()) {
                    continue;
                }

                JsonNode choice = choices.get(0);
                JsonNode delta = choice.path("delta");
                if (delta.isMissingNode() || delta.isNull()) {
                    delta = choice.path("message");
                }
                if (delta.isMissingNode() || delta.isNull()) {
                    continue;
                }

                String deltaRole = delta.path("role").asText("");
                if (!deltaRole.isEmpty()) {
                    role = deltaRole;
                }

                String reasoningDelta = extractReasoningDelta(delta);
                if (!reasoningDelta.isEmpty()) {
                    reasoning.append(reasoningDelta);
                    streamListener.onReasoningDelta(reasoningDelta);
                }

                String contentDelta = delta.path("content").asText("");
                if (!contentDelta.isEmpty()) {
                    content.append(contentDelta);
                    streamListener.onContentDelta(contentDelta);
                }

                mergeToolCallDeltas(toolAccumulators, delta.path("tool_calls"));
            }

            List<ToolCall> toolCalls = buildToolCalls(toolAccumulators);
            if (content.isEmpty() && reasoning.isEmpty() && (toolCalls == null || toolCalls.isEmpty())) {
                throw new IOException("API返回空内容，请检查 provider/model 配置或该模型是否支持当前请求参数");
            }

            return new ChatResponse(
                    role,
                    content.toString(),
                    reasoning.toString(),
                    toolCalls,
                    inputTokens,
                    outputTokens,
                    cachedInputTokens
            );
        }
    }

    private String formatStreamingError(JsonNode error) {
        String message = error.path("message").asText("");
        String code = error.path("code").asText("");
        if (!code.isEmpty() && !message.isEmpty()) {
            return code + " - " + message;
        }
        if (!message.isEmpty()) {
            return message;
        }
        return error.toString();
    }

    private String extractReasoningDelta(JsonNode delta) {
        String reasoningContent = delta.path("reasoning_content").asText("");
        if (!reasoningContent.isEmpty()) {
            return reasoningContent;
        }
        String reasoning = delta.path("reasoning").asText("");
        if (!reasoning.isEmpty()) {
            return reasoning;
        }
        JsonNode details = delta.path("reasoning_details");
        if (details.isArray() && !details.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode detail : details) {
                String text = detail.path("text").asText("");
                if (text.isEmpty()) {
                    text = detail.path("content").asText("");
                }
                if (!text.isEmpty()) {
                    sb.append(text);
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 解析缓存命中token数
     * @param usage 包含缓存命中token数的 JSON 节点
     * @param fallback 缓存命中token数的默认值，用于处理 JSON 节点缺失或为空的情况
     * @return 缓存命中token数的整数表示
     */
    private int parseCachedInputTokens(JsonNode usage, int fallback) {
        int cached = usage.path("cached_tokens").asInt(fallback);
        cached = usage.path("prompt_cache_hit_tokens").asInt(cached);
        cached = usage.path("input_cache_hit_tokens").asInt(cached);
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        if (!promptDetails.isMissingNode()) {
            cached = promptDetails.path("cached_tokens").asInt(cached);
        }
        JsonNode inputDetails = usage.path("input_tokens_details");
        if (!inputDetails.isMissingNode()) {
            cached = inputDetails.path("cached_tokens").asInt(cached);
        }
        return cached;
    }

    /**
     * 构建 OpenAI 兼容的请求体，包含模型、流式响应、消息和工具调用。
     * @param messages 对话历史（system、user、assistant、tool 消息）
     * @param tools 工具列表，包含可用的工具（write_file、read_file 等）
     * @return 包含模型、流式响应、消息和工具调用的 JSON 对象。
     */
    private ObjectNode buildRequestBody(List<Message> messages, List<Tool> tools) {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", getModel());   //getModel抽象类，子类实现返回模型名称
        requestBody.put("stream", true);

        ArrayNode messagesArray = requestBody.putArray("messages");

        // 遍历并序列化每条消息，包括 reasoning_content 和 tool_calls
        for (Message msg : messages) {
            ObjectNode msgNode = messagesArray.addObject();  //messagesArray对象的数组末尾追加一个新的空对象
            // "messages": [
            //     {"role": "assistant"}
            // ]
            msgNode.put("role", msg.role());
            // 1. 处理消息内容(纯文本或多模态消息)，为msgNode添加content字段或content数组
            appendMessageContent(msgNode, msg);

            // 2. 处理 reasoning_content（推理过程）
            if (shouldSendReasoningContentInRequestHistory()
                    && "assistant".equals(msg.role())   //只有 assistant 消息才有 reasoning
                    && msg.reasoningContent() != null
                    && !msg.reasoningContent().isBlank()) {
                msgNode.put("reasoning_content", msg.reasoningContent());
            }

            // 3. 处理 tool_calls（工具调用），assistant发起工具调用
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                // 3.1 在当前消息对象中创建 tool_calls 数组
                ArrayNode toolCallsArray = msgNode.putArray("tool_calls");
                for (ToolCall tc : msg.toolCalls()) {
                    ObjectNode tcNode = toolCallsArray.addObject();
                    tcNode.put("id", tc.id());
                    tcNode.put("type", "function");
                    ObjectNode functionNode = tcNode.putObject("function");
                    functionNode.put("name", tc.function().name());
                    functionNode.put("arguments", tc.function().arguments());
                }
            }
        /**
            {
                "role": "assistant",
                "content": "",
                "tool_calls": [
                    {
                    "id": "call_abc123",
                    "type": "function",
                    "function": {
                        "name": "write_file",
                        "arguments": "{\"path\":\"test.java\",\"content\":\"public class Test {}\"}"
                    }
                    }
                ]
            }
            */
            // 4. 处理 tool_call_id（工具调用ID）：当工具执行完成，需要把结果返回给 LLM，用于后续的推理
            if (msg.toolCallId() != null) {
                msgNode.put("tool_call_id", msg.toolCallId());
            }
        }

        // 5. 添加工具定义列表，用于 LLM 调用工具
        if (tools != null && !tools.isEmpty()) {
            ArrayNode toolsArray = requestBody.putArray("tools");
            for (Tool tool : tools) {
                ObjectNode toolNode = toolsArray.addObject();
                toolNode.put("type", "function");
                ObjectNode functionNode = toolNode.putObject("function");
                functionNode.put("name", tool.name());
                functionNode.put("description", tool.description());
                functionNode.set("parameters", tool.parameters());
            }
        }
        /**
            {
                "tools": [
                    {
                    "type": "function",
                    "function": {
                        "name": "write_file",
                        "description": "写入文件内容（仅限项目根目录之内，单文件 5MB 上限）",
                        "parameters": {
                        "type": "object",
                        "properties": {
                            "path": {"type": "string", "description": "文件路径"},
                            "content": {"type": "string", "description": "文件内容"}
                        },
                        "required": ["path", "content"]
                        }
                    }
                    }
                ]
            }
         */
        // 6. 钩子方法和返回
        customizeRequestBody(requestBody);
        return requestBody;
    }

    protected void customizeRequestBody(ObjectNode requestBody) {
    }

    protected void customizeRequest(Request.Builder request) {
    }

    protected OkHttpClient httpClient() {
        return SHARED_HTTP_CLIENT;
    }

    /**
     * 处理消息内容(纯文本或多模态消息)
     * @param msgNode 消息节点，用于存储处理后的内容
     * @param msg 消息对象，包含原始内容和可能的多模态内容
     */
    private void appendMessageContent(ObjectNode msgNode, Message msg) {
        // 处理纯文本消息
        if (!msg.hasContentParts()) {
            msgNode.put("content", msg.content());
            return;
        }

        // 处理多模态消息
        ArrayNode contentArray = msgNode.putArray("content");
        for (LlmClient.ContentPart part : msg.contentParts()) {
            if (part == null) {
                continue;
            }
            if (part.isText()) {
                if (part.text() != null && !part.text().isBlank()) {
                    ObjectNode textNode = contentArray.addObject();
                    textNode.put("type", "text");
                    textNode.put("text", part.text());
                }
                continue;
            }
            if (part.isImage()) {
                String imageUrl = toImageUrl(part);
                if (imageUrl == null || imageUrl.isBlank()) {
                    continue;
                }
                ObjectNode imageNode = contentArray.addObject();
                imageNode.put("type", "image_url");
                ObjectNode imageUrlNode = imageNode.putObject("image_url");
                imageUrlNode.put("url", imageUrl);
            }
        }

        // 降级处理，将所有内容转换为纯文本格式
        if (contentArray.isEmpty()) {
            msgNode.put("content", msg.content());
        }
    }

    protected String toImageUrl(LlmClient.ContentPart part) {
        if ("image_url".equals(part.type())) {
            return part.imageUrl();
        }
        if ("image_base64".equals(part.type())) {
            String mimeType = part.mimeType() == null || part.mimeType().isBlank() ? "image/png" : part.mimeType();
            return "data:" + mimeType + ";base64," + part.imageBase64();
        }
        return null;
    }

    /**
     * 合并工具调用增量数据
     * @param accumulators
     * @param toolCallsNode
     */
    private void mergeToolCallDeltas(List<ToolCallAccumulator> accumulators, JsonNode toolCallsNode) {
        if (toolCallsNode == null || !toolCallsNode.isArray()) {
            return;  // 本帧没有 tool_calls，直接返回
        }

        for (JsonNode tc : toolCallsNode) {
            int index = tc.path("index").asInt(accumulators.size());
            while (accumulators.size() <= index) {
                accumulators.add(new ToolCallAccumulator());
            }

            ToolCallAccumulator acc = accumulators.get(index);
            String id = tc.path("id").asText("");
            if (!id.isEmpty()) {
                acc.id = id;
            }

            JsonNode function = tc.path("function");
            String name = function.path("name").asText("");
            if (!name.isEmpty()) {
                acc.name.append(name);
            }
            String arguments = function.path("arguments").asText("");
            if (!arguments.isEmpty()) {
                acc.arguments.append(arguments);
            }
        }
    }

    private List<ToolCall> buildToolCalls(List<ToolCallAccumulator> accumulators) {
        if (accumulators.isEmpty()) {
            return null;
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (ToolCallAccumulator acc : accumulators) {
            if (acc.id == null || acc.id.isBlank()) {
                continue;
            }
            toolCalls.add(new ToolCall(
                    acc.id,
                    new ToolCall.Function(acc.name.toString(), acc.arguments.toString())
            ));
        }
        return toolCalls.isEmpty() ? null : toolCalls;
    }

    private static final class ToolCallAccumulator {
        private String id;
        private final StringBuilder name = new StringBuilder();
        private final StringBuilder arguments = new StringBuilder();
    }
}
