export interface AiLlmConfig {
  id?: number;
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  temperature?: number;
  maxTokens?: number;
  isDefault?: number;
  enabled?: number;
  remark?: string;
}

export interface AiMcpConfig {
  id?: number;
  name: string;
  transport?: string;
  url?: string;
  enabled?: number;
  remark?: string;
}

export interface AiChatMessage {
  role: 'system' | 'user' | 'assistant';
  content: string;
}
