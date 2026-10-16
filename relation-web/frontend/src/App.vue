<template>
  <div class="app">
    <header class="header">
      <h1>户籍关系图谱</h1>
    </header>

    <!-- 需求一：直系亲属 -->
    <section class="card">
      <h2>查询直系亲属</h2>
      <div class="row">
        <input
          v-model="singleId"
          class="input"
          placeholder="请输入身份证号"
          @keyup.enter="queryDirect"
        />
        <select v-model="directLevel" class="select" title="查询扩展级数">
          <option :value="0">默认模式</option>
          <option v-for="n in 8" :key="n" :value="n">{{ n }} 级关系</option>
        </select>
        <button class="btn" :disabled="loading.direct" @click="queryDirect">
          {{ loading.direct ? '查询中…' : '查询' }}
        </button>
      </div>
      <div v-if="directResult" class="result">
        <div class="desc" :class="{ ok: directResult.hasRelation }">{{ directResult.description }}</div>
        <div v-if="directResult.nodes?.length" class="relative-list-wrap">
          <h3>关联人员列表</h3>
          <div class="table-scroll">
            <table class="relative-list">
              <thead><tr><th>与查询人关系</th><th>姓名</th><th>身份证号</th><th>性别</th></tr></thead>
              <tbody>
                <tr v-for="person in listedRelatives" :key="person.id">
                  <td><span class="relation-tag" :class="{ center: person.center }">{{ relationToCenter(person) }}</span></td>
                  <td>{{ person.name || '未知' }}</td>
                  <td>{{ person.gmsfhm || '-' }}</td>
                  <td>{{ person.xbdm === '1' ? '男' : person.xbdm === '2' ? '女' : '-' }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
        <GraphView :nodes="directResult.nodes" :edges="directResult.edges" />
      </div>
    </section>

    <!-- 需求二：两人关系 -->
    <section class="card">
      <h2>查询两人关系</h2>
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
        <select v-model="relationLevel" class="select" title="查询最大级数">
          <option :value="0">默认模式</option>
          <option v-for="n in 8" :key="n" :value="n">{{ n }} 级以内</option>
        </select>
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
import { ref, reactive, computed } from 'vue'
import GraphView from './components/GraphView.vue'
import { getDirectRelatives, getRelationPath } from './api'

const singleId = ref('')
const directLevel = ref(0)
const idA = ref('')
const idB = ref('')
const relationLevel = ref(0)
const directResult = ref(null)
const relationResult = ref(null)
const loading = reactive({ direct: false, relation: false })
const listedRelatives = computed(() => {
  const nodes = directResult.value?.nodes || []
  const seen = new Set()
  const unique = nodes.filter(n => {
    if (seen.has(n.id)) return false
    seen.add(n.id)
    return true
  })
  // 查询人（中心节点）固定排第一行，其余保持原顺序
  return [...unique.filter(n => n.center), ...unique.filter(n => !n.center)]
})

function relationToCenter(person) {
  if (person.center) return '查询人'
  const center = directResult.value?.nodes?.find(node => node.center)
  if (!center) return '-'
  const edges = directResult.value.edges || []
  const edge = edges.find(item => item.type === 'MARRIED_TO'
    ? (item.source === center.id && item.target === person.id) || (item.target === center.id && item.source === person.id)
    : (item.source === center.id && item.target === person.id) || (item.target === center.id && item.source === person.id))
  if (edge?.type === 'MARRIED_TO') return '配偶'
  if (edge && edge.target === center.id) return edge.type === 'MOTHER_OF' ? '母亲' : '父亲'
  if (edge && edge.source === center.id) return person.xbdm === '2' ? '女儿' : '儿子'

  // 共同父母节点表示兄弟姐妹关系，也用于识别兄弟姐妹的配偶和子女。
  const centerParents = new Set(edges.filter(item => item.target === center.id && item.type !== 'MARRIED_TO').map(item => item.source))
  const siblingIds = new Set(edges
    .filter(item => item.target !== center.id && centerParents.has(item.source) && item.type !== 'MARRIED_TO')
    .map(item => item.target))
  const siblingParentEdge = edges.find(item => item.target === person.id && centerParents.has(item.source) && item.type !== 'MARRIED_TO')
  if (siblingParentEdge) {
    const siblingBirthDate = birthDateFromId(person.gmsfhm)
    const centerBirthDate = birthDateFromId(center.gmsfhm)
    if (siblingBirthDate && centerBirthDate && siblingBirthDate !== centerBirthDate) {
      const isOlder = siblingBirthDate < centerBirthDate
      if (person.xbdm === '2') return isOlder ? '姐姐' : '妹妹'
      return isOlder ? '哥哥' : '弟弟'
    }
    return person.xbdm === '2' ? '姐妹' : '兄弟'
  }

  const siblingChildEdge = edges.find(item => item.target === person.id && siblingIds.has(item.source) && item.type !== 'MARRIED_TO')
  if (siblingChildEdge) {
    const sibling = directResult.value.nodes.find(node => node.id === siblingChildEdge.source)
    const isNiece = person.xbdm === '2'
    if (sibling?.xbdm === '2') return isNiece ? '外甥女' : '外甥'
    return isNiece ? '侄女' : '侄子'
  }

  const siblingSpouseEdge = edges.find(item => item.type === 'MARRIED_TO'
    && ((item.source === person.id && siblingIds.has(item.target))
      || (item.target === person.id && siblingIds.has(item.source))))
  if (siblingSpouseEdge) {
    const siblingId = siblingSpouseEdge.source === person.id ? siblingSpouseEdge.target : siblingSpouseEdge.source
    const sibling = directResult.value.nodes.find(node => node.id === siblingId)
    const siblingBirthDate = birthDateFromId(sibling?.gmsfhm)
    const centerBirthDate = birthDateFromId(center.gmsfhm)
    if (siblingBirthDate && centerBirthDate && siblingBirthDate !== centerBirthDate) {
      const isOlder = siblingBirthDate < centerBirthDate
      if (sibling?.xbdm === '2') return isOlder ? '姐夫' : '妹夫'
      return isOlder ? '嫂子' : '弟媳'
    }
    return sibling?.xbdm === '2' ? '姐妹的配偶' : '兄弟的配偶'
  }
  return '亲属'
}

function birthDateFromId(gmsfhm) {
  const match = String(gmsfhm || '').match(/^\d{6}(\d{8})\d{4}$/)
  if (!match) return null
  const date = match[1]
  const year = Number(date.slice(0, 4))
  const month = Number(date.slice(4, 6))
  const day = Number(date.slice(6, 8))
  const parsed = new Date(Date.UTC(year, month - 1, day))
  if (parsed.getUTCFullYear() !== year || parsed.getUTCMonth() !== month - 1 || parsed.getUTCDate() !== day) return null
  return date
}

async function queryDirect() {
  const val = singleId.value.trim()
  if (!val) {
    alert('请输入身份证号')
    return
  }
  loading.direct = true
  try {
    const { data } = await getDirectRelatives(val, directLevel.value)
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
    const { data } = await getRelationPath(a, b, relationLevel.value)
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
.select {
  padding: 9px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  font-size: 14px;
  background: #fff;
  color: #303133;
}
.select:focus { outline: none; border-color: #409eff; }
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
.relative-list-wrap { margin: 16px 0; }
.relative-list-wrap h3 { margin: 0 0 10px; font-size: 15px; }
.table-scroll { overflow-x: auto; }
.relative-list { width: 100%; border-collapse: collapse; font-size: 14px; }
.relative-list th, .relative-list td { padding: 10px 12px; border-bottom: 1px solid #ebeef5; text-align: left; white-space: nowrap; }
.relative-list th { color: #606266; background: #f5f7fa; font-weight: 600; }
.relation-tag { display: inline-block; padding: 3px 9px; border-radius: 12px; color: #337ecc; background: #ecf5ff; }
.relation-tag.center { color: #606266; background: #f2f3f5; }
</style>
