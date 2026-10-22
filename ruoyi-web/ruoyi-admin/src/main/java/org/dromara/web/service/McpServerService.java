package org.dromara.web.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 内置 MCP 服务器（Streamable HTTP）：把户籍图谱业务能力暴露为 MCP 工具，
 * 供 AI 助手调用，实现自然语言查询图谱。
 *
 * @author hukou
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class McpServerService {

    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final GraphService graphService;
    private final ObjectMapper objectMapper;

    /**
     * 处理 MCP JSON-RPC 请求，返回响应；通知类方法返回 null。
     */
    public JsonNode handle(JsonNode request) {
        String method = request.path("method").asText("");
        if (method.startsWith("notifications/")) {
            return null;
        }
        JsonNode id = request.get("id");
        try {
            JsonNode result = switch (method) {
                case "initialize" -> initialize();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(request.path("params"));
                default -> throw new RuntimeException("未知方法：" + method);
            };
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id);
            resp.set("result", result);
            return resp;
        } catch (Exception e) {
            ObjectNode resp = objectMapper.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id);
            ObjectNode error = resp.putObject("error");
            error.put("code", -32603);
            error.put("message", e.getMessage() == null ? "内部错误" : e.getMessage());
            return resp;
        }
    }

    private JsonNode initialize() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.putObject("capabilities").putObject("tools");
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", "hukou-graph");
        serverInfo.put("version", "1.0.0");
        return result;
    }

    private JsonNode toolsList() {
        ArrayNode tools = objectMapper.createArrayNode();
        tools.add(tool("person_direct",
            "根据身份证号查询某人的直系亲属关系图谱，包含配偶、父母、子女、兄弟姐妹及其配偶子女等。",
            schema(Map.of("gmsfhm", "身份证号", "level", "查询级数，0 为默认"), List.of("gmsfhm"))));
        tools.add(tool("relation_path",
            "查询两个人之间的亲属关系路径及称谓。",
            schema(Map.of("from", "人员 A 身份证号", "to", "人员 B 身份证号", "level", "查询级数，0 为默认"), List.of("from", "to"))));
        tools.add(tool("graph_stats",
            "统计户籍关系图谱的节点总数、关系总数、各关系类型数量及性别分布。",
            schema(Map.of(), List.of())));
        tools.add(tool("search_person",
            "根据姓名或身份证号模糊搜索人员，返回匹配的人员列表（身份证号、姓名、性别）。",
            schema(Map.of("keyword", "姓名或身份证号关键词"), List.of("keyword"))));
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", tools);
        return result;
    }

    private JsonNode toolsCall(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        String text = switch (name) {
            case "person_direct" -> toJson(graphService.getDirectRelatives(
                args.path("gmsfhm").asText(""),
                args.has("level") ? args.path("level").asInt() : 0));
            case "relation_path" -> toJson(graphService.getRelationPath(
                args.path("from").asText(""),
                args.path("to").asText(""),
                args.has("level") ? args.path("level").asInt() : 0));
            case "graph_stats" -> toJson(graphService.getStats());
            case "search_person" -> toJson(graphService.searchPerson(args.path("keyword").asText("")));
            default -> throw new RuntimeException("未知工具：" + name);
        };
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", text);
        result.put("isError", false);
        return result;
    }

    private ObjectNode tool(String name, String description, ObjectNode inputSchema) {
        ObjectNode t = objectMapper.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        t.set("inputSchema", inputSchema);
        return t;
    }

    private ObjectNode schema(Map<String, String> fields, List<String> required) {
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        ObjectNode props = schema.putObject("properties");
        for (Map.Entry<String, String> e : fields.entrySet()) {
            ObjectNode p = props.putObject(e.getKey());
            p.put("type", "level".equals(e.getKey()) ? "integer" : "string");
            p.put("description", e.getValue());
        }
        if (!required.isEmpty()) {
            ArrayNode req = schema.putArray("required");
            for (String r : required) {
                req.add(r);
            }
        }
        return schema;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("序列化失败：" + e.getMessage());
        }
    }
}
