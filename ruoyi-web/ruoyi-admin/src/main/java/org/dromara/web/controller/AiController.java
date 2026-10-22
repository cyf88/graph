package org.dromara.web.controller;

import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.web.domain.AiLlmConfig;
import org.dromara.web.domain.AiMcpConfig;
import org.dromara.web.domain.vo.AiChatRequest;
import org.dromara.web.service.AiAgentService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

/**
 * AI 助手
 *
 * @author hukou
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/ai")
public class AiController {

    private final AiAgentService aiAgentService;

    // ==================== 大模型配置 ====================

    @GetMapping("/llm/list")
    public R<List<AiLlmConfig>> listLlm() {
        return R.ok(aiAgentService.listLlm());
    }

    @PostMapping("/llm")
    public R<Void> saveLlm(@RequestBody AiLlmConfig config) {
        aiAgentService.saveLlm(config);
        return R.ok();
    }

    @PutMapping("/llm")
    public R<Void> updateLlm(@RequestBody AiLlmConfig config) {
        aiAgentService.updateLlm(config);
        return R.ok();
    }

    @DeleteMapping("/llm/{id}")
    public R<Void> deleteLlm(@PathVariable Long id) {
        aiAgentService.deleteLlm(id);
        return R.ok();
    }

    // ==================== MCP 配置 ====================

    @GetMapping("/mcp/list")
    public R<List<AiMcpConfig>> listMcp() {
        return R.ok(aiAgentService.listMcp());
    }

    @PostMapping("/mcp")
    public R<Void> saveMcp(@RequestBody AiMcpConfig config) {
        aiAgentService.saveMcp(config);
        return R.ok();
    }

    @PutMapping("/mcp")
    public R<Void> updateMcp(@RequestBody AiMcpConfig config) {
        aiAgentService.updateMcp(config);
        return R.ok();
    }

    @DeleteMapping("/mcp/{id}")
    public R<Void> deleteMcp(@PathVariable Long id) {
        aiAgentService.deleteMcp(id);
        return R.ok();
    }

    @GetMapping("/mcp/{id}/tools")
    public R<List<Map<String, Object>>> listMcpTools(@PathVariable Long id) {
        try {
            return R.ok(aiAgentService.listMcpTools(id));
        } catch (Exception e) {
            return R.fail(e.getMessage());
        }
    }

    // ==================== 对话 ====================

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestBody AiChatRequest request) {
        return aiAgentService.chat(request);
    }
}
