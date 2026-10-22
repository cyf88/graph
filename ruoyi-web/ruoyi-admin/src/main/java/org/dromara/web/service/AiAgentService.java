package org.dromara.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.web.domain.AiLlmConfig;
import org.dromara.web.domain.AiMcpConfig;
import org.dromara.web.domain.vo.AiChatRequest;
import org.dromara.web.mapper.AiLlmConfigMapper;
import org.dromara.web.mapper.AiMcpConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 助手服务：大模型/MCP 配置管理 + 对话 Agent 循环
 *
 * @author hukou
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class AiAgentService {

    private static final int MAX_TOOL_ITERATIONS = 8;
    private static final String MCP_PROTOCOL_VERSION = "2024-11-05";

    private final AiLlmConfigMapper llmMapper;
    private final AiMcpConfigMapper mcpMapper;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-agent");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .build();

    // ==================== 大模型配置 ====================

    public List<AiLlmConfig> listLlm() {
        return llmMapper.selectList(new LambdaQueryWrapper<AiLlmConfig>().orderByDesc(AiLlmConfig::getId));
    }

    @Transactional
    public void saveLlm(AiLlmConfig config) {
        fillLlmDefaults(config);
        if (Integer.valueOf(1).equals(config.getIsDefault())) {
            clearDefaultLlm();
        }
        llmMapper.insert(config);
    }

    @Transactional
    public void updateLlm(AiLlmConfig config) {
        fillLlmDefaults(config);
        if (Integer.valueOf(1).equals(config.getIsDefault())) {
            clearDefaultLlm();
        }
        llmMapper.updateById(config);
    }

    @Transactional
    public void deleteLlm(Long id) {
        llmMapper.deleteById(id);
    }

    private void fillLlmDefaults(AiLlmConfig config) {
        if (config.getTemperature() == null) {
            config.setTemperature(0.7);
        }
        if (config.getMaxTokens() == null) {
            config.setMaxTokens(2048);
        }
        if (config.getIsDefault() == null) {
            config.setIsDefault(0);
        }
        if (config.getEnabled() == null) {
            config.setEnabled(1);
        }
    }

    private void clearDefaultLlm() {
        AiLlmConfig update = new AiLlmConfig();
        update.setIsDefault(0);
        llmMapper.update(update, new LambdaQueryWrapper<AiLlmConfig>().eq(AiLlmConfig::getIsDefault, 1));
    }

    // ==================== MCP 配置 ====================

    public List<AiMcpConfig> listMcp() {
        return mcpMapper.selectList(new LambdaQueryWrapper<AiMcpConfig>().orderByDesc(AiMcpConfig::getId));
    }

    @Transactional
    public void saveMcp(AiMcpConfig config) {
        fillMcpDefaults(config);
        mcpMapper.insert(config);
    }

    @Transactional
    public void updateMcp(AiMcpConfig config) {
        fillMcpDefaults(config);
        mcpMapper.updateById(config);
    }

    @Transactional
    public void deleteMcp(Long id) {
        mcpMapper.deleteById(id);
    }

    private void fillMcpDefaults(AiMcpConfig config) {
        if (config.getTransport() == null || config.getTransport().isBlank()) {
            config.setTransport("streamable-http");
        }
        if (config.getEnabled() == null) {
            config.setEnabled(1);
        }
    }

    /**
     * 测试 MCP 连接并返回工具列表
     */
    public List<Map<String, Object>> listMcpTools(Long id) throws Exception {
        AiMcpConfig mcp = mcpMapper.selectById(id);
        if (mcp == null) {
            throw new RuntimeException("MCP 配置不存在");
        }
        McpClient client = new McpClient(mcp.getUrl());
        client.initialize();
        List<JsonNode> tools = client.listTools();
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode t : tools) {
            Map<String, Object> m = new HashMap<>();
            m.put("name", t.path("name").asText(""));
            m.put("description", t.path("description").asText(""));
            m.put("inputSchema", objectMapper.convertValue(t.path("inputSchema"), Map.class));
            result.add(m);
        }
        return result;
    }

    // ==================== 对话 ====================

    public SseEmitter chat(AiChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        executor.execute(() -> {
            try {
                runAgent(request, emitter);
            } catch (Exception e) {
                log.error("AI 对话异常", e);
                send(emitter, "error", e.getMessage() == null ? "对话发生异常" : e.getMessage());
                emitter.complete();
            }
        });
        return emitter;
    }

    private void runAgent(AiChatRequest request, SseEmitter emitter) throws Exception {
        AiLlmConfig llm = resolveLlm(request.getLlmId());
        if (llm == null) {
            send(emitter, "error", "尚未配置可用的大模型，请点击右上角设置添加");
            send(emitter, "done", "");
            emitter.complete();
            return;
        }

        // 收集所有已启用 MCP 的工具
        Map<String, McpClient> toolClientMap = new HashMap<>();
        List<JsonNode> tools = new ArrayList<>();
        List<AiMcpConfig> mcpList = mcpMapper.selectList(
            new LambdaQueryWrapper<AiMcpConfig>().eq(AiMcpConfig::getEnabled, 1));
        for (AiMcpConfig mcp : mcpList) {
            try {
                McpClient client = new McpClient(mcp.getUrl());
                client.initialize();
                for (JsonNode tool : client.listTools()) {
                    String name = tool.path("name").asText("");
                    if (name.isBlank()) {
                        continue;
                    }
                    ObjectNode fn = objectMapper.createObjectNode();
                    fn.put("name", name);
                    fn.put("description", tool.path("description").asText(""));
                    fn.set("parameters", tool.path("inputSchema"));
                    ObjectNode toolDef = objectMapper.createObjectNode();
                    toolDef.put("type", "function");
                    toolDef.set("function", fn);
                    tools.add(toolDef);
                    toolClientMap.put(name, client);
                }
            } catch (Exception e) {
                log.warn("MCP 服务器 [{}] 连接失败：{}", mcp.getName(), e.getMessage());
            }
        }

        // 组装消息
        List<JsonNode> messages = new ArrayList<>();
        if (request.getMessages() != null) {
            for (AiChatRequest.Message m : request.getMessages()) {
                ObjectNode node = objectMapper.createObjectNode();
                node.put("role", m.getRole());
                node.put("content", m.getContent());
                messages.add(node);
            }
        }

        // Agent 循环
        for (int i = 0; i < MAX_TOOL_ITERATIONS; i++) {
            JsonNode resp = callLlm(llm, messages, tools);
            JsonNode choice = resp.path("choices").get(0);
            if (choice == null || choice.isMissingNode()) {
                send(emitter, "error", "大模型返回异常：" + resp.toString());
                send(emitter, "done", "");
                emitter.complete();
                return;
            }
            JsonNode message = choice.path("message");
            JsonNode toolCalls = message.path("tool_calls");

            if (toolCalls.isArray() && toolCalls.size() > 0) {
                messages.add(message);
                for (JsonNode tc : toolCalls) {
                    String callId = tc.path("id").asText("");
                    String fnName = tc.path("function").path("name").asText("");
                    String argsStr = tc.path("function").path("arguments").asText("{}");
                    send(emitter, "status", "正在调用工具：" + fnName);
                    String toolResult = executeTool(toolClientMap, fnName, argsStr);
                    ObjectNode toolMsg = objectMapper.createObjectNode();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", callId);
                    toolMsg.put("content", toolResult == null ? "" : toolResult);
                    messages.add(toolMsg);
                }
                continue;
            }

            String content = message.path("content").asText("");
            send(emitter, "delta", content);
            break;
        }

        send(emitter, "done", "");
        emitter.complete();
    }

    private AiLlmConfig resolveLlm(Long llmId) {
        if (llmId != null) {
            AiLlmConfig c = llmMapper.selectById(llmId);
            if (c != null && (c.getEnabled() == null || c.getEnabled() == 1)) {
                return c;
            }
        }
        AiLlmConfig def = llmMapper.selectOne(new LambdaQueryWrapper<AiLlmConfig>()
            .eq(AiLlmConfig::getIsDefault, 1)
            .eq(AiLlmConfig::getEnabled, 1)
            .last("limit 1"));
        if (def != null) {
            return def;
        }
        return llmMapper.selectOne(new LambdaQueryWrapper<AiLlmConfig>()
            .eq(AiLlmConfig::getEnabled, 1)
            .last("limit 1"));
    }

    private String executeTool(Map<String, McpClient> toolClientMap, String name, String argsStr) {
        McpClient client = toolClientMap.get(name);
        if (client == null) {
            return "未找到工具 " + name;
        }
        try {
            JsonNode args = (argsStr == null || argsStr.isBlank())
                ? objectMapper.createObjectNode()
                : objectMapper.readTree(argsStr);
            return client.callTool(name, args);
        } catch (Exception e) {
            return "工具调用失败：" + e.getMessage();
        }
    }

    private JsonNode callLlm(AiLlmConfig llm, List<JsonNode> messages, List<JsonNode> tools) throws Exception {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", llm.getModel());
        body.put("stream", false);
        body.put("temperature", llm.getTemperature() == null ? 0.7 : llm.getTemperature());
        if (llm.getMaxTokens() != null) {
            body.put("max_tokens", llm.getMaxTokens());
        }
        ArrayNode msgs = body.putArray("messages");
        for (JsonNode m : messages) {
            msgs.add(m);
        }
        if (!tools.isEmpty()) {
            ArrayNode t = body.putArray("tools");
            for (JsonNode x : tools) {
                t.add(x);
            }
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(buildEndpoint(llm.getBaseUrl())))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + llm.getApiKey())
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .timeout(Duration.ofSeconds(120))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new RuntimeException("大模型接口错误 " + response.statusCode() + "：" + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private String buildEndpoint(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("大模型 API 地址为空");
        }
        String url = baseUrl.trim();
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        return url.endsWith("/") ? url + "chat/completions" : url + "/chat/completions";
    }

    private void send(SseEmitter emitter, String type, String content) {
        try {
            Map<String, String> payload = new HashMap<>();
            payload.put("type", type);
            payload.put("content", content);
            emitter.send(payload);
        } catch (Exception e) {
            log.warn("SSE 发送失败：{}", e.getMessage());
        }
    }

    // ==================== MCP 客户端（Streamable HTTP） ====================

    private String extractJson(String raw) {
        String trimmed = raw == null ? "" : raw.trim();
        if (trimmed.startsWith("{")) {
            return trimmed;
        }
        String last = null;
        for (String line : trimmed.split("\\r?\\n")) {
            if (line.startsWith("data:")) {
                last = line.substring(5).trim();
            }
        }
        return last != null ? last : trimmed;
    }

    private class McpClient {
        private final String url;
        private String sessionId;
        private int nextId = 1;

        McpClient(String url) {
            this.url = url;
        }

        void initialize() throws Exception {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("protocolVersion", MCP_PROTOCOL_VERSION);
            params.putObject("capabilities");
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "hukou-ai");
            clientInfo.put("version", "1.0.0");
            request("initialize", params);
            // 发送 initialized 通知（忽略失败）
            try {
                notify("notifications/initialized");
            } catch (Exception ignore) {
                // 部分服务器不强依赖该通知
            }
        }

        List<JsonNode> listTools() throws Exception {
            JsonNode result = request("tools/list", objectMapper.createObjectNode());
            List<JsonNode> list = new ArrayList<>();
            JsonNode tools = result.path("tools");
            if (tools.isArray()) {
                for (JsonNode t : tools) {
                    list.add(t);
                }
            }
            return list;
        }

        String callTool(String name, JsonNode args) throws Exception {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("name", name);
            params.set("arguments", args);
            JsonNode result = request("tools/call", params);
            StringBuilder sb = new StringBuilder();
            JsonNode content = result.path("content");
            if (content.isArray()) {
                for (JsonNode c : content) {
                    String type = c.path("type").asText("text");
                    if ("text".equals(type)) {
                        sb.append(c.path("text").asText(""));
                    } else {
                        sb.append(c.toString());
                    }
                }
            } else {
                sb.append(result.toString());
            }
            return sb.toString();
        }

        synchronized JsonNode request(String method, JsonNode params) throws Exception {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("id", nextId++);
            body.put("method", method);
            if (params != null) {
                body.set("params", params);
            }
            JsonNode json = post(body);
            if (json.has("error")) {
                throw new RuntimeException(json.path("error").path("message").asText("MCP 错误"));
            }
            return json.path("result");
        }

        synchronized void notify(String method) throws Exception {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("jsonrpc", "2.0");
            body.put("method", method);
            post(body);
        }

        private JsonNode post(ObjectNode body) throws Exception {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream");
            if (sessionId != null) {
                builder.header("Mcp-Session-Id", sessionId);
            }
            HttpRequest request = builder.POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .timeout(Duration.ofSeconds(60))
                .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(v -> sessionId = v);
            if (response.statusCode() >= 400) {
                throw new RuntimeException("MCP 服务器错误 " + response.statusCode() + "：" + response.body());
            }
            return objectMapper.readTree(extractJson(response.body()));
        }
    }
}
