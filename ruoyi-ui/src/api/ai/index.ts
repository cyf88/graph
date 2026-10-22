import request from '@/utils/request';
import { getToken } from '@/utils/auth';
import type { AxiosPromise } from 'axios';
import type { AiLlmConfig, AiMcpConfig, AiChatMessage } from './types';

// ==================== 大模型配置 ====================

export const listLlm = (): AxiosPromise<AiLlmConfig[]> => {
  return request({ url: '/ai/llm/list', method: 'get' });
};

export const saveLlm = (data: AiLlmConfig): AxiosPromise<void> => {
  return request({ url: '/ai/llm', method: 'post', data });
};

export const updateLlm = (data: AiLlmConfig): AxiosPromise<void> => {
  return request({ url: '/ai/llm', method: 'put', data });
};

export const deleteLlm = (id: number): AxiosPromise<void> => {
  return request({ url: `/ai/llm/${id}`, method: 'delete' });
};

// ==================== MCP 配置 ====================

export const listMcp = (): AxiosPromise<AiMcpConfig[]> => {
  return request({ url: '/ai/mcp/list', method: 'get' });
};

export const saveMcp = (data: AiMcpConfig): AxiosPromise<void> => {
  return request({ url: '/ai/mcp', method: 'post', data });
};

export const updateMcp = (data: AiMcpConfig): AxiosPromise<void> => {
  return request({ url: '/ai/mcp', method: 'put', data });
};

export const deleteMcp = (id: number): AxiosPromise<void> => {
  return request({ url: `/ai/mcp/${id}`, method: 'delete' });
};

export const listMcpTools = (id: number): AxiosPromise<{ name: string; description: string; inputSchema: object }[]> => {
  return request({ url: `/ai/mcp/${id}/tools`, method: 'get' });
};

// ==================== 对话（SSE 流式） ====================

export interface AiChatEvent {
  type: string;
  content: string;
}

/**
 * 发起对话，通过 SSE 流式接收事件
 * @param payload 对话请求
 * @param onEvent 事件回调：type 可为 status/delta/done/error
 */
export async function chatAi(
  payload: { llmId?: number; messages: AiChatMessage[] },
  onEvent: (event: AiChatEvent) => void
): Promise<void> {
  const baseURL = import.meta.env.VITE_APP_BASE_API;
  const resp = await fetch(`${baseURL}/ai/chat`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: 'Bearer ' + getToken(),
      clientid: import.meta.env.VITE_APP_CLIENT_ID
    },
    body: JSON.stringify(payload)
  });

  if (!resp.ok) {
    throw new Error(`对话请求失败：HTTP ${resp.status}`);
  }
  if (!resp.body) {
    throw new Error('当前浏览器不支持流式响应');
  }

  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';

  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    const parts = buffer.split('\n\n');
    buffer = parts.pop() || '';
    for (const part of parts) {
      const line = part.trim();
      if (!line.startsWith('data:')) continue;
      const jsonStr = line.substring(5).trim();
      if (!jsonStr) continue;
      try {
        const event = JSON.parse(jsonStr) as AiChatEvent;
        onEvent(event);
      } catch {
        // 忽略无法解析的数据帧
      }
    }
  }
}
