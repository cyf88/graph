<template>
  <div class="graph-page">
    <el-card shadow="never" class="mb-4">
      <template #header>
        <span>查询直系亲属</span>
      </template>
      <div class="query-row">
        <el-input
          v-model="singleId"
          placeholder="请输入身份证号"
          clearable
          style="max-width: 320px"
          @keyup.enter="queryDirect"
        />
        <el-select v-model="directLevel" style="width: 160px" title="查询扩展级数">
          <el-option :value="0" label="默认模式" />
          <el-option v-for="n in 8" :key="n" :value="n" :label="n + ' 级关系'" />
        </el-select>
        <el-button type="primary" :loading="loading.direct" @click="queryDirect">
          {{ loading.direct ? '查询中…' : '查询' }}
        </el-button>
      </div>

      <div v-if="directResult" class="result">
        <el-alert :type="directResult.hasRelation ? 'success' : 'warning'" :title="directResult.description" :closable="false" show-icon class="mb-3" />
        <div v-if="directResult.nodes?.length" class="mb-3">
          <h4 class="sub-title">关联人员列表</h4>
          <el-table :data="listedRelatives" border size="small" max-height="280">
            <el-table-column label="与查询人关系" min-width="130">
              <template #default="scope">
                <el-tag :type="scope.row.center ? 'info' : 'primary'" effect="plain">{{ relationToCenter(scope.row) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="name" label="姓名" min-width="110">
              <template #default="scope">{{ scope.row.name || '未知' }}</template>
            </el-table-column>
            <el-table-column prop="gmsfhm" label="身份证号" min-width="190">
              <template #default="scope">{{ scope.row.gmsfhm || '-' }}</template>
            </el-table-column>
            <el-table-column label="性别" width="80">
              <template #default="scope">{{ scope.row.xbdm === '1' ? '男' : scope.row.xbdm === '2' ? '女' : '-' }}</template>
            </el-table-column>
          </el-table>
        </div>
        <GraphView :nodes="directResult.nodes" :edges="directResult.edges" />
      </div>
    </el-card>

    <el-card shadow="never" class="mb-4">
      <template #header>
        <span>查询两人关系</span>
      </template>
      <div class="query-row">
        <el-input v-model="idA" placeholder="身份证号 A" clearable style="max-width: 260px" @keyup.enter="queryRelation" />
        <el-input v-model="idB" placeholder="身份证号 B" clearable style="max-width: 260px" @keyup.enter="queryRelation" />
        <el-select v-model="relationLevel" style="width: 160px" title="查询最大级数">
          <el-option :value="0" label="默认模式" />
          <el-option v-for="n in 8" :key="n" :value="n" :label="n + ' 级以内'" />
        </el-select>
        <el-button type="primary" :loading="loading.relation" @click="queryRelation">
          {{ loading.relation ? '查询中…' : '查询关系' }}
        </el-button>
      </div>

      <div v-if="relationResult" class="result">
        <el-alert :type="relationResult.hasRelation ? 'success' : 'warning'" :title="relationResult.description" :closable="false" show-icon class="mb-3" />
        <GraphView v-if="relationResult.hasRelation" :nodes="relationResult.nodes" :edges="relationResult.edges" />
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <span>测试数据示例</span>
      </template>
      <div class="hint">
        <p>张德福（户主）：320311193805120012　张建国（子）：320311196308150038</p>
        <p>张伟（孙）：320311198806100112　王丽（张家孙媳妇，跨家族）：320311199011050048</p>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed } from 'vue';
import { ElMessage } from 'element-plus';
import GraphView from './components/GraphView.vue';
import { getDirectRelatives, getRelationPath } from '@/api/graph';
import type { GraphResult, GraphNode, GraphEdge } from '@/api/graph/types';

const singleId = ref('');
const directLevel = ref(0);
const idA = ref('');
const idB = ref('');
const relationLevel = ref(0);
const directResult = ref<GraphResult | null>(null);
const relationResult = ref<GraphResult | null>(null);
const loading = reactive({ direct: false, relation: false });

const listedRelatives = computed(() => {
  const nodes = directResult.value?.nodes || [];
  const seen = new Set<string>();
  const unique = nodes.filter((n) => {
    if (seen.has(n.id)) return false;
    seen.add(n.id);
    return true;
  });
  return [...unique.filter((n) => n.center), ...unique.filter((n) => !n.center)];
});

function relationToCenter(person: GraphNode): string {
  if (person.center) return '查询人';
  const center = directResult.value?.nodes?.find((node) => node.center);
  if (!center) return '-';
  const edges = directResult.value?.edges || [];
  const edge = edges.find((item) =>
    item.type === 'MARRIED_TO'
      ? (item.source === center.id && item.target === person.id) || (item.target === center.id && item.source === person.id)
      : (item.source === center.id && item.target === person.id) || (item.target === center.id && item.source === person.id)
  );
  if (edge?.type === 'MARRIED_TO') return '配偶';
  if (edge && edge.target === center.id) return edge.type === 'MOTHER_OF' ? '母亲' : '父亲';
  if (edge && edge.source === center.id) return person.xbdm === '2' ? '女儿' : '儿子';

  const centerParents = new Set(edges.filter((item) => item.target === center.id && item.type !== 'MARRIED_TO').map((item) => item.source));
  const siblingIds = new Set(
    edges.filter((item) => item.target !== center.id && centerParents.has(item.source) && item.type !== 'MARRIED_TO').map((item) => item.target)
  );
  const siblingParentEdge = edges.find((item) => item.target === person.id && centerParents.has(item.source) && item.type !== 'MARRIED_TO');
  if (siblingParentEdge) {
    const siblingBirthDate = birthDateFromId(person.gmsfhm);
    const centerBirthDate = birthDateFromId(center.gmsfhm);
    if (siblingBirthDate && centerBirthDate && siblingBirthDate !== centerBirthDate) {
      const isOlder = siblingBirthDate < centerBirthDate;
      if (person.xbdm === '2') return isOlder ? '姐姐' : '妹妹';
      return isOlder ? '哥哥' : '弟弟';
    }
    return person.xbdm === '2' ? '姐妹' : '兄弟';
  }

  const siblingChildEdge = edges.find((item) => item.target === person.id && siblingIds.has(item.source) && item.type !== 'MARRIED_TO');
  if (siblingChildEdge) {
    const sibling = directResult.value?.nodes.find((node) => node.id === siblingChildEdge.source);
    const isNiece = person.xbdm === '2';
    if (sibling?.xbdm === '2') return isNiece ? '外甥女' : '外甥';
    return isNiece ? '侄女' : '侄子';
  }

  const siblingSpouseEdge = edges.find(
    (item) => item.type === 'MARRIED_TO' && ((item.source === person.id && siblingIds.has(item.target)) || (item.target === person.id && siblingIds.has(item.source)))
  );
  if (siblingSpouseEdge) {
    const siblingId = siblingSpouseEdge.source === person.id ? siblingSpouseEdge.target : siblingSpouseEdge.source;
    const sibling = directResult.value?.nodes.find((node) => node.id === siblingId);
    const siblingBirthDate = birthDateFromId(sibling?.gmsfhm);
    const centerBirthDate = birthDateFromId(center.gmsfhm);
    if (siblingBirthDate && centerBirthDate && siblingBirthDate !== centerBirthDate) {
      const isOlder = siblingBirthDate < centerBirthDate;
      if (sibling?.xbdm === '2') return isOlder ? '姐夫' : '妹夫';
      return isOlder ? '嫂子' : '弟媳';
    }
    return sibling?.xbdm === '2' ? '姐妹的配偶' : '兄弟的配偶';
  }
  return '亲属';
}

function birthDateFromId(gmsfhm?: string | null): string | null {
  const match = String(gmsfhm || '').match(/^\d{6}(\d{8})\d{4}$/);
  if (!match) return null;
  const date = match[1];
  const year = Number(date.slice(0, 4));
  const month = Number(date.slice(4, 6));
  const day = Number(date.slice(6, 8));
  const parsed = new Date(Date.UTC(year, month - 1, day));
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) return null;
  return date;
}

async function queryDirect() {
  const val = singleId.value.trim();
  if (!val) {
    ElMessage.warning('请输入身份证号');
    return;
  }
  loading.direct = true;
  try {
    const res = await getDirectRelatives(val, directLevel.value);
    directResult.value = res.data;
  } catch (e: any) {
    ElMessage.error('查询失败：' + (e?.message || '未知错误'));
  } finally {
    loading.direct = false;
  }
}

async function queryRelation() {
  const a = idA.value.trim();
  const b = idB.value.trim();
  if (!a || !b) {
    ElMessage.warning('请输入两个身份证号');
    return;
  }
  loading.relation = true;
  try {
    const res = await getRelationPath(a, b, relationLevel.value);
    relationResult.value = res.data;
  } catch (e: any) {
    ElMessage.error('查询失败：' + (e?.message || '未知错误'));
  } finally {
    loading.relation = false;
  }
}
</script>

<style scoped>
.graph-page {
  padding: 4px;
}
.mb-4 {
  margin-bottom: 16px;
}
.mb-3 {
  margin-bottom: 12px;
}
.query-row {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  align-items: center;
}
.result {
  margin-top: 16px;
}
.sub-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
}
.hint {
  color: #909399;
  font-size: 13px;
  line-height: 1.9;
}
.hint p {
  margin: 4px 0;
}
</style>
