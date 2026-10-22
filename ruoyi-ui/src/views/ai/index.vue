<template>
  <div class="ai-container">
    <div class="ai-header">
      <div class="ai-title">
        <span class="ai-title-dot"></span>
        AI 助手
      </div>
      <el-tooltip content="设置：添加大模型与 MCP" placement="bottom">
        <el-button class="ai-setting-btn" text circle @click="openSettings">
          <el-icon :size="20"><Setting /></el-icon>
        </el-button>
      </el-tooltip>
    </div>

    <div ref="msgListRef" class="ai-messages">
      <div v-if="messages.length === 0" class="ai-empty">
        <div class="ai-empty-icon">✦</div>
        <p>你好，我是徐州数据治理应用平台的 AI 助手</p>
        <p class="ai-empty-sub">有什么可以帮你的？可点击右上角设置图标配置大模型与 MCP 工具</p>
      </div>

      <div v-for="(msg, idx) in messages" :key="idx" class="ai-msg" :class="msg.role">
        <div class="ai-msg-bubble">
          <template v-if="msg.role === 'assistant'">
            <span v-if="msg.status" class="ai-status">{{ msg.status }}</span>
            <span v-else-if="!msg.content" class="ai-status">思考中…</span>
            <span v-else class="ai-content">{{ msg.content }}</span>
          </template>
          <template v-else>
            <span class="ai-content">{{ msg.content }}</span>
          </template>
        </div>
      </div>
    </div>

    <div class="ai-input">
      <el-input
        v-model="input"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="输入你的问题，Enter 发送，Shift+Enter 换行"
        @keydown.enter.exact.prevent="send"
      />
      <div class="ai-input-actions">
        <span class="ai-input-hint">内容由 AI 生成，请谨慎甄别</span>
        <el-button type="primary" :loading="loading" @click="send">
          <el-icon v-if="!loading"><Promotion /></el-icon>
          发送
        </el-button>
      </div>
    </div>

    <el-drawer v-model="drawerVisible" title="AI 助手设置" size="560px">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="大模型" name="llm">
          <div class="tab-toolbar">
            <el-button type="primary" size="small" @click="openLlmDialog()">新增模型</el-button>
          </div>
          <el-table :data="llmList" border size="small">
            <el-table-column prop="name" label="名称" min-width="120" />
            <el-table-column prop="model" label="模型" min-width="140" />
            <el-table-column prop="baseUrl" label="API 地址" min-width="180" show-overflow-tooltip />
            <el-table-column label="默认" width="60" align="center">
              <template #default="{ row }">
                <el-tag v-if="row.isDefault === 1" type="success" size="small">默认</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="120" align="center">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="openLlmDialog(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeLlm(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="MCP 服务器" name="mcp">
          <div class="tab-toolbar">
            <el-button type="primary" size="small" @click="openMcpDialog()">新增 MCP</el-button>
          </div>
          <el-table :data="mcpList" border size="small">
            <el-table-column prop="name" label="名称" min-width="120" />
            <el-table-column prop="transport" label="传输类型" width="120" />
            <el-table-column prop="url" label="地址" min-width="200" show-overflow-tooltip />
            <el-table-column label="操作" width="180" align="center">
              <template #default="{ row }">
                <el-button link type="primary" size="small" @click="testMcp(row)">测试</el-button>
                <el-button link type="primary" size="small" @click="openMcpDialog(row)">编辑</el-button>
                <el-button link type="danger" size="small" @click="removeMcp(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="tab-tip">MCP 采用 Streamable HTTP 传输，配置后 AI 可自动调用其工具完成任务。</div>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>

    <el-dialog v-model="llmDialogVisible" :title="llmForm.id ? '编辑模型' : '新增模型'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="llmForm.name" placeholder="如 DeepSeek" />
        </el-form-item>
        <el-form-item label="API 地址" required>
          <el-input v-model="llmForm.baseUrl" placeholder="如 https://api.deepseek.com/v1" />
        </el-form-item>
        <el-form-item label="API Key" required>
          <el-input v-model="llmForm.apiKey" type="password" show-password placeholder="OpenAI 兼容密钥" />
        </el-form-item>
        <el-form-item label="模型" required>
          <el-input v-model="llmForm.model" placeholder="如 deepseek-chat" />
        </el-form-item>
        <el-form-item label="温度">
          <el-input-number v-model="llmForm.temperature" :min="0" :max="2" :step="0.1" />
        </el-form-item>
        <el-form-item label="最大Token">
          <el-input-number v-model="llmForm.maxTokens" :min="1" :max="128000" :step="1024" />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="llmForm.isDefault" :active-value="1" :inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="llmDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitLlm">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="mcpDialogVisible" :title="mcpForm.id ? '编辑 MCP' : '新增 MCP'" width="520px">
      <el-form label-width="90px">
        <el-form-item label="名称" required>
          <el-input v-model="mcpForm.name" placeholder="如 户籍查询服务" />
        </el-form-item>
        <el-form-item label="传输类型" required>
          <el-select v-model="mcpForm.transport" style="width: 100%">
            <el-option label="Streamable HTTP" value="streamable-http" />
            <el-option label="SSE" value="sse" />
          </el-select>
        </el-form-item>
        <el-form-item label="地址" required>
          <el-input v-model="mcpForm.url" placeholder="如 http://127.0.0.1:8088/mcp" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="mcpDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitMcp">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Setting, Promotion } from '@element-plus/icons-vue';
import {
  listLlm,
  saveLlm,
  updateLlm,
  deleteLlm,
  listMcp,
  saveMcp,
  updateMcp,
  deleteMcp,
  listMcpTools,
  chatAi
} from '@/api/ai';
import type { AiLlmConfig, AiMcpConfig, AiChatMessage } from '@/api/ai/types';

const SYSTEM_PROMPT = '你是徐州数据治理应用平台的 AI 助手，请用简洁清晰的中文回答用户问题。';

interface Msg {
  role: 'user' | 'assistant';
  content: string;
  status?: string;
}

const msgListRef = ref<HTMLElement | null>(null);
const messages = ref<Msg[]>([]);
const input = ref('');
const loading = ref(false);

function scrollToBottom() {
  nextTick(() => {
    if (msgListRef.value) {
      msgListRef.value.scrollTop = msgListRef.value.scrollHeight;
    }
  });
}

async function send() {
  const text = input.value.trim();
  if (!text || loading.value) return;
  input.value = '';

  const history: AiChatMessage[] = messages.value
    .filter((m) => m.content)
    .map((m) => ({ role: m.role, content: m.content }));
  const chatMessages: AiChatMessage[] = [
    { role: 'system', content: SYSTEM_PROMPT },
    ...history,
    { role: 'user', content: text }
  ];

  messages.value.push({ role: 'user', content: text });
  messages.value.push({ role: 'assistant', content: '', status: '思考中…' });
  loading.value = true;
  scrollToBottom();

  const assistantMsg = messages.value[messages.value.length - 1];

  try {
    await chatAi({ messages: chatMessages }, (event) => {
      if (event.type === 'status') {
        assistantMsg.status = event.content;
        scrollToBottom();
      } else if (event.type === 'delta') {
        assistantMsg.status = undefined;
        assistantMsg.content += event.content;
        scrollToBottom();
      } else if (event.type === 'error') {
        assistantMsg.status = undefined;
        assistantMsg.content = event.content || '对话发生异常';
      }
    });
  } catch (e: any) {
    assistantMsg.status = undefined;
    assistantMsg.content = e?.message || '对话请求失败';
  } finally {
    loading.value = false;
    if (!assistantMsg.content && !assistantMsg.status) {
      assistantMsg.content = '（无响应）';
    }
    scrollToBottom();
  }
}

const drawerVisible = ref(false);
const activeTab = ref('llm');

function openSettings() {
  drawerVisible.value = true;
  loadLlm();
  loadMcp();
}

const llmList = ref<AiLlmConfig[]>([]);
const llmDialogVisible = ref(false);
const llmForm = ref<AiLlmConfig>(defaultLlmForm());

function defaultLlmForm(): AiLlmConfig {
  return { name: '', baseUrl: '', apiKey: '', model: '', temperature: 0.7, maxTokens: 2048, isDefault: 0, enabled: 1 };
}

async function loadLlm() {
  const res = await listLlm();
  llmList.value = res.data || [];
}

function openLlmDialog(row?: AiLlmConfig) {
  llmForm.value = row ? { ...row } : defaultLlmForm();
  llmDialogVisible.value = true;
}

async function submitLlm() {
  const f = llmForm.value;
  if (!f.name || !f.baseUrl || !f.apiKey || !f.model) {
    ElMessage.warning('请填写名称、API 地址、API Key 和模型');
    return;
  }
  if (f.id) {
    await updateLlm(f);
  } else {
    await saveLlm(f);
  }
  ElMessage.success('保存成功');
  llmDialogVisible.value = false;
  loadLlm();
}

async function removeLlm(row: AiLlmConfig) {
  await ElMessageBox.confirm(`确认删除大模型「${row.name}」？`, '提示', { type: 'warning' });
  await deleteLlm(row.id!);
  ElMessage.success('删除成功');
  loadLlm();
}

const mcpList = ref<AiMcpConfig[]>([]);
const mcpDialogVisible = ref(false);
const mcpForm = ref<AiMcpConfig>(defaultMcpForm());

function defaultMcpForm(): AiMcpConfig {
  return { name: '', transport: 'streamable-http', url: '', enabled: 1 };
}

async function loadMcp() {
  const res = await listMcp();
  mcpList.value = res.data || [];
}

function openMcpDialog(row?: AiMcpConfig) {
  mcpForm.value = row ? { ...row } : defaultMcpForm();
  mcpDialogVisible.value = true;
}

async function submitMcp() {
  const f = mcpForm.value;
  if (!f.name || !f.url) {
    ElMessage.warning('请填写名称和地址');
    return;
  }
  if (f.id) {
    await updateMcp(f);
  } else {
    await saveMcp(f);
  }
  ElMessage.success('保存成功');
  mcpDialogVisible.value = false;
  loadMcp();
}

async function removeMcp(row: AiMcpConfig) {
  await ElMessageBox.confirm(`确认删除 MCP「${row.name}」？`, '提示', { type: 'warning' });
  await deleteMcp(row.id!);
  ElMessage.success('删除成功');
  loadMcp();
}

async function testMcp(row: AiMcpConfig) {
  try {
    const res = await listMcpTools(row.id!);
    const tools = res.data || [];
    if (tools.length === 0) {
      ElMessage.warning('连接成功，但未发现工具');
      return;
    }
    ElMessageBox.alert(
      tools.map((t: any) => `· ${t.name}：${t.description || '无描述'}`).join('<br/>'),
      `「${row.name}」共 ${tools.length} 个工具`,
      { dangerouslyUseHTMLString: true }
    );
  } catch (e: any) {
    ElMessage.error(`连接失败：${e?.message || '请检查地址与网络'}`);
  }
}
</script>

<style scoped>
.ai-container {
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #f5f7fa;
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 14px 20px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}

.ai-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.ai-title-dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: #409eff;
  box-shadow: 0 0 8px rgba(64, 158, 255, 0.6);
}

.ai-setting-btn {
  color: #606266;
}

.ai-messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px;
}

.ai-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  height: 100%;
  color: #909399;
  gap: 6px;
}

.ai-empty-icon {
  font-size: 40px;
  color: #c0c4cc;
  margin-bottom: 6px;
}

.ai-empty-sub {
  font-size: 12px;
}

.ai-msg {
  display: flex;
  margin-bottom: 16px;
}

.ai-msg.user {
  justify-content: flex-end;
}

.ai-msg.assistant {
  justify-content: flex-start;
}

.ai-msg-bubble {
  max-width: 72%;
  padding: 10px 14px;
  border-radius: 8px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 14px;
}

.ai-msg.user .ai-msg-bubble {
  background: #409eff;
  color: #fff;
}

.ai-msg.assistant .ai-msg-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #ebeef5;
}

.ai-status {
  color: #909399;
  font-size: 13px;
}

.ai-content {
  color: inherit;
}

.ai-input {
  padding: 14px 20px;
  background: #fff;
  border-top: 1px solid #e4e7ed;
}

.ai-input-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 10px;
}

.ai-input-hint {
  font-size: 12px;
  color: #c0c4cc;
}

.tab-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}

.tab-tip {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
}
</style>
