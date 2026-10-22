package org.dromara.web.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.dromara.web.service.McpServerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 内置 MCP 服务器端点（Streamable HTTP），供 AI 助手调用图谱业务工具。
 *
 * @author hukou
 */
@RequiredArgsConstructor
@RestController
public class McpServerController {

    private final McpServerService mcpServerService;

    @PostMapping("/mcp")
    public ResponseEntity<JsonNode> mcp(@RequestBody JsonNode body) {
        JsonNode resp = mcpServerService.handle(body);
        if (resp == null) {
            // 通知类请求无需响应体
            return ResponseEntity.status(202).build();
        }
        return ResponseEntity.ok(resp);
    }
}
