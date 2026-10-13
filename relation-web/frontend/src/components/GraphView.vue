<template>
  <div class="graph-wrap">
    <div v-if="nodes.length === 0" class="empty">暂无数据</div>
    <div v-show="nodes.length > 0" ref="chartRef" class="graph"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch } from 'vue'
import * as echarts from 'echarts'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] }
})

const chartRef = ref(null)
let chart = null

/**
 * 计算族谱树布局：
 * 配偶视为同一“家庭单元”（同一层级），父子/母子关系决定代际（父/母在上、子女在下）。
 * 返回 Map<id, {x, y}>，y 表示代际（0 为最顶层），x 为同层横向位置。
 */
function computeTreeLayout(nodes, edges) {
  const ids = nodes.map(n => n.id)

  // 并查集：把配偶关系连成同一家庭单元
  const parent = new Map(ids.map(id => [id, id]))
  const find = (x) => {
    let r = x
    while (parent.get(r) !== r) r = parent.get(r)
    while (parent.get(x) !== r) {
      const nx = parent.get(x)
      parent.set(x, r)
      x = nx
    }
    return r
  }
  const union = (a, b) => {
    const ra = find(a)
    const rb = find(b)
    if (ra !== rb) parent.set(ra, rb)
  }

  const childEdges = [] // [父母id, 子女id]（后端数据：source=父母, target=子女）
  for (const e of edges) {
    if (e.type === 'MARRIED_TO') {
      union(e.source, e.target)
    } else {
      childEdges.push([e.source, e.target])
    }
  }

  // 家庭单元之间的父子关系
  const compChildren = new Map()
  const compParents = new Map()
  for (const [p, c] of childEdges) {
    const pc = find(p)
    const cc = find(c)
    if (pc === cc) continue
    if (!compChildren.has(pc)) compChildren.set(pc, [])
    compChildren.get(pc).push(cc)
    if (!compParents.has(cc)) compParents.set(cc, new Set())
    compParents.get(cc).add(pc)
  }

  // BFS 计算家庭单元代际
  const allComps = new Set(ids.map(id => find(id)))
  const compLevel = new Map()
  const roots = [...allComps].filter(c => !compParents.has(c))
  const queue = [...roots]
  roots.forEach(r => compLevel.set(r, 0))
  while (queue.length) {
    const cur = queue.shift()
    const lv = compLevel.get(cur)
    for (const child of (compChildren.get(cur) || [])) {
      if (!compLevel.has(child)) {
        compLevel.set(child, lv + 1)
        queue.push(child)
      }
    }
  }
  allComps.forEach(c => { if (!compLevel.has(c)) compLevel.set(c, 0) })

  // 每个节点的代际
  const nodeLevel = new Map()
  ids.forEach(id => nodeLevel.set(id, compLevel.get(find(id))))

  // 按代际分组
  const byLevel = new Map()
  nodes.forEach(n => {
    const lv = nodeLevel.get(n.id)
    if (!byLevel.has(lv)) byLevel.set(lv, [])
    byLevel.get(lv).push(n)
  })

  // 分配坐标（同层横向排列，夫妻相邻）
  const pos = new Map()
  const rowGap = 140
  const colGap = 160
  const levels = [...byLevel.keys()].sort((a, b) => a - b)
  for (const lv of levels) {
    const arr = byLevel.get(lv).sort((a, b) => {
      const ca = find(a.id)
      const cb = find(b.id)
      if (ca !== cb) return ca.localeCompare(cb)
      return a.id.localeCompare(b.id)
    })
    const total = (arr.length - 1) * colGap
    arr.forEach((n, i) => {
      pos.set(n.id, { x: i * colGap - total / 2, y: lv * rowGap })
    })
  }
  return pos
}

function renderChart() {
  if (!chart || !chartRef.value) return
  if (props.nodes.length === 0) {
    chart.clear()
    return
  }

  const pos = computeTreeLayout(props.nodes, props.edges)

  const data = props.nodes.map(n => {
    const p = pos.get(n.id)
    return {
      id: n.id,
      name: n.name,
      gmsfhm: n.gmsfhm,
      x: p.x,
      y: p.y,
      symbolSize: n.center ? 62 : 46,
      itemStyle: n.center
        ? { color: '#FF9800', borderColor: '#E65100', borderWidth: 3 }
        : { color: n.xbdm === '1' ? '#409EFF' : '#F56C6C' }
    }
  })

  // 父子/母子关系的箭头指向父母（子女 -> 父母）
  const links = props.edges.map(e => {
    const reverse = e.type === 'FATHER_OF' || e.type === 'MOTHER_OF'
    return {
      source: reverse ? e.target : e.source,
      target: reverse ? e.source : e.target,
      label: { show: true, formatter: e.label || e.type }
    }
  })

  chart.setOption({
    tooltip: {
      formatter: (p) => {
        if (p.dataType === 'node') {
          return `${p.data.name}<br/>身份证号：${p.data.gmsfhm || '-'}`
        }
        return p.data.label ? p.data.label.formatter : ''
      }
    },
    series: [{
      type: 'graph',
      layout: 'none',
      data,
      links,
      roam: true,
      draggable: false,
      label: { show: true, position: 'bottom', fontSize: 13 },
      edgeLabel: { show: true, fontSize: 10 },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: [4, 10],
      lineStyle: { color: '#b0b0b0' }
    }]
  }, true)
}

function onResize() {
  if (chart) chart.resize()
}

onMounted(() => {
  chart = echarts.init(chartRef.value)
  renderChart()
  window.addEventListener('resize', onResize)
})

watch(() => [props.nodes, props.edges], renderChart, { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) {
    chart.dispose()
    chart = null
  }
})
</script>

<style scoped>
.graph-wrap {
  width: 100%;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  overflow: hidden;
}
.graph {
  width: 100%;
  height: 480px;
}
.empty {
  padding: 40px;
  text-align: center;
  color: #909399;
}
</style>
