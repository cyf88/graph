<template>
  <div class="app">
    <header class="header">
      <h1>户籍关系图谱</h1>
      <p class="subtitle">基于 Neo4j 的家庭亲属关系查询与展示</p>
    </header>

    <!-- 需求一：直系亲属 -->
    <section class="card">
      <h2>需求一 · 查询直系亲属</h2>
      <div class="row">
        <input
          v-model="singleId"
          class="input"
          placeholder="请输入身份证号"
          @keyup.enter="queryDirect"
        />
        <button class="btn" :disabled="loading.direct" @click="queryDirect">
          {{ loading.direct ? '查询中…' : '查询' }}
        </button>
      </div>
      <div v-if="directResult" class="result">
        <div class="desc" :class="{ ok: directResult.hasRelation }">{{ directResult.description }}</div>
        <GraphView :nodes="directResult.nodes" :edges="directResult.edges" />
      </div>
    </section>

    <!-- 需求二：两人关系 -->
    <section class="card">
      <h2>需求二 · 查询两人关系</h2>
      <div class="row">
        <input
          v-model="idA"
          class="input"
          placeholder="身份证号 A"
          @keyup.enter="queryRelation"
        />
        <input
          v-model="idB"
          class="input"
          placeholder="身份证号 B"
          @keyup.enter="queryRelation"
        />
        <button class="btn" :disabled="loading.relation" @click="queryRelation">
          {{ loading.relation ? '查询中…' : '查询关系' }}
        </button>
      </div>
      <div v-if="relationResult" class="result">
        <div class="desc" :class="{ ok: relationResult.hasRelation }">{{ relationResult.description }}</div>
        <GraphView v-if="relationResult.hasRelation" :nodes="relationResult.nodes" :edges="relationResult.edges" />
      </div>
    </section>

    <section class="hint">
      <h3>测试数据示例</h3>
      <p>张德福（户主）：320311193805120012　张建国（子）：320311196308150038</p>
      <p>张伟（孙）：320311198806100112　王丽（张家孙媳妇，跨家族）：320311199011050048</p>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import GraphView from './components/GraphView.vue'
import { getDirectRelatives, getRelationPath } from './api'

const singleId = ref('')
const idA = ref('')
const idB = ref('')
const directResult = ref(null)
const relationResult = ref(null)
const loading = reactive({ direct: false, relation: false })

async function queryDirect() {
  const val = singleId.value.trim()
  if (!val) {
    alert('请输入身份证号')
    return
  }
  loading.direct = true
  try {
    const { data } = await getDirectRelatives(val)
    directResult.value = data
  } catch (e) {
    alert('查询失败：' + (e.response?.data?.message || e.message))
  } finally {
    loading.direct = false
  }
}

async function queryRelation() {
  const a = idA.value.trim()
  const b = idB.value.trim()
  if (!a || !b) {
    alert('请输入两个身份证号')
    return
  }
  loading.relation = true
  try {
    const { data } = await getRelationPath(a, b)
    relationResult.value = data
  } catch (e) {
    alert('查询失败：' + (e.response?.data?.message || e.message))
  } finally {
    loading.relation = false
  }
}
</script>

<style>
* { box-sizing: border-box; }
body { margin: 0; background: #f5f7fa; color: #303133; font-family: "Microsoft YaHei", sans-serif; }
.app { max-width: 960px; margin: 0 auto; padding: 24px; }
.header { text-align: center; margin-bottom: 24px; }
.header h1 { margin: 0; font-size: 26px; }
.subtitle { margin: 8px 0 0; color: #909399; font-size: 14px; }
.card {
  background: #fff;
  border-radius: 8px;
  padding: 20px;
  margin-bottom: 20px;
  box-shadow: 0 1px 4px rgba(0,0,0,0.06);
}
.card h2 { margin: 0 0 16px; font-size: 18px; }
.row { display: flex; gap: 10px; flex-wrap: wrap; }
.input {
  flex: 1;
  min-width: 220px;
  padding: 9px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 14px;
}
.input:focus { outline: none; border-color: #409eff; }
.btn {
  padding: 9px 20px;
  border: none;
  border-radius: 4px;
  background: #409eff;
  color: #fff;
  font-size: 14px;
  cursor: pointer;
}
.btn:disabled { background: #a0cfff; cursor: not-allowed; }
.result { margin-top: 16px; }
.desc {
  padding: 10px 14px;
  border-radius: 4px;
  background: #fdf6ec;
  color: #e6a23c;
  margin-bottom: 12px;
  font-size: 14px;
}
.desc.ok { background: #f0f9eb; color: #67c23a; }
.hint { color: #909399; font-size: 13px; }
.hint h3 { margin: 0 0 8px; color: #606266; }
.hint p { margin: 4px 0; }
</style>
