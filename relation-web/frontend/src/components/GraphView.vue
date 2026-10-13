<template>
  <div class="graph-wrap">
    <div v-if="nodes.length === 0" class="empty">暂无数据</div>
    <div v-show="nodes.length > 0" ref="containerRef" class="graph"></div>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import G6 from '@antv/g6'

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] }
})

const containerRef = ref(null)
let graph = null

const EDGE_COLORS = {
  FATHER_OF: '#409EFF',
  MOTHER_OF: '#F56C6C',
  MARRIED_TO: '#E6A23C'
}

// 与户主关系代码 -> 中文
const REL_LABELS = {
  '01': '户主',
  '02': '配偶',
  '03': '子',
  '04': '女',
  '05': '孙子女',
  '06': '外孙子女',
  '10': '儿媳',
  '11': '女婿'
}

/**
 * 计算族谱树布局：
 * 1. 配偶合并为同一“家庭单元”（并查集），父子/母子关系决定代际；
 * 2. 自底向上让每个家庭单元居中到其子女上方；
 * 3. 家庭单元内：有父辈的“血缘成员”放在单元中心，其配偶放在右侧。
 * 返回 Map<id, {x, y}>。
 */
function computeTreeLayout(nodes, edges) {
  const ids = nodes.map(n => n.id)

  // 并查集：配偶 -> 同一家庭单元
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

  const anchorIds = new Set() // 有父辈的成员（血缘锚点）
  const unitChildren = new Map()
  const unitParents = new Map()
  for (const [p, c] of childEdges) {
    const pc = find(p)
    const cc = find(c)
    if (pc === cc) continue
    anchorIds.add(c)
    if (!unitChildren.has(pc)) unitChildren.set(pc, [])
    unitChildren.get(pc).push(cc)
    if (!unitParents.has(cc)) unitParents.set(cc, new Set())
    unitParents.get(cc).add(pc)
  }

  // 代际（BFS）
  const allUnits = new Set(ids.map(id => find(id)))
  const unitLevel = new Map()
  const unitRoots = [...allUnits].filter(u => !unitParents.has(u))
  const queue = [...unitRoots]
  unitRoots.forEach(r => unitLevel.set(r, 0))
  while (queue.length) {
    const u = queue.shift()
    const lv = unitLevel.get(u)
    for (const c of (unitChildren.get(u) || [])) {
      if (!unitLevel.has(c)) {
        unitLevel.set(c, lv + 1)
        queue.push(c)
      }
    }
  }
  allUnits.forEach(u => { if (!unitLevel.has(u)) unitLevel.set(u, 0) })

  // 自底向上分配横向位置（叶子依次排开，父单元居中到子女上方）
  const unitX = new Map()
  let leafCounter = 0
  function assignX(u) {
    if (unitX.has(u)) return unitX.get(u)
    const kids = (unitChildren.get(u) || []).filter(k => k !== u)
    let cx
    if (kids.length === 0) {
      cx = leafCounter++
    } else {
      const xs = kids.map(k => assignX(k))
      cx = (Math.min(...xs) + Math.max(...xs)) / 2
    }
    unitX.set(u, cx)
    return cx
  }
  unitRoots.forEach(r => assignX(r))
  allUnits.forEach(u => { if (!unitX.has(u)) unitX.set(u, leafCounter++) })

  // 节点坐标
  const colGap = 200
  const rowGap = 140
  const memberGap = 180
  const unitMembers = new Map()
  ids.forEach(id => {
    const u = find(id)
    if (!unitMembers.has(u)) unitMembers.set(u, [])
    unitMembers.get(u).push(id)
  })

  const pos = new Map()
  for (const [u, members] of unitMembers) {
    const cx = unitX.get(u) * colGap
    const y = unitLevel.get(u) * rowGap
    if (members.length === 1) {
      pos.set(members[0], { x: cx, y })
    } else {
      const anchor = members.find(m => anchorIds.has(m))
      if (anchor) {
        // 血缘成员在单元中心，配偶在右侧
        const spouse = members.find(m => m !== anchor)
        pos.set(anchor, { x: cx, y })
        pos.set(spouse, { x: cx + memberGap, y })
      } else {
        // 顶层夫妻：围绕中心对称排布
        const sorted = [...members].sort()
        const total = (sorted.length - 1) * memberGap
        sorted.forEach((m, i) => {
          pos.set(m, { x: cx + i * memberGap - total / 2, y })
        })
      }
    }
  }
  return pos
}

function buildNodeStyle(n) {
  if (n.center) {
    return { fill: '#FF9800', stroke: '#FFD54F', lineWidth: 3 }
  }
  if (n.xbdm === '1') {
    return { fill: '#409EFF', stroke: '#79BBFF', lineWidth: 2 }
  }
  return { fill: '#F56C6C', stroke: '#F89898', lineWidth: 2 }
}

function initGraph() {
  if (!containerRef.value) return
  const width = containerRef.value.clientWidth || 800

  // 悬浮提示：显示身份证号等信息
  const tooltip = new G6.Tooltip({
    offsetX: 12,
    offsetY: 12,
    itemTypes: ['node'],
    getContent(e) {
      const m = e.item.getModel()
      const gender = m.xbdm === '1' ? '男' : (m.xbdm === '2' ? '女' : '-')
      const rel = REL_LABELS[m.yhzgxdm] || m.yhzgxdm || '-'
      return `<div style="background:#1c1f36;color:#e8e8ee;padding:10px 14px;border-radius:6px;border:1px solid #3a3f5c;font-size:13px;line-height:1.9;min-width:180px;">
        <div style="font-size:15px;font-weight:700;color:#fff;margin-bottom:2px;">${m.label}</div>
        <div>身份证号：${m.gmsfhm || '-'}</div>
        <div>性别：${gender}</div>
        <div>与户主关系：${rel}</div>
      </div>`
    }
  })

  graph = new G6.Graph({
    container: containerRef.value,
    width,
    height: 520,
    fitView: true,
    pixelRatio: 1,
    minZoom: 0.3,
    maxZoom: 4,
    plugins: [tooltip],
    defaultNode: {
      type: 'rect',
      size: [84, 36],
      // 锚点：上下左右四条边的中点，纵向关系从上下边中点连接
      anchorPoints: [[0, 0.5], [0.5, 0], [1, 0.5], [0.5, 1]],
      labelCfg: {
        position: 'center',
        style: { fill: '#fff', fontSize: 13, fontWeight: 600 }
      }
    },
    defaultEdge: {
      type: 'polyline',
      style: {
        stroke: '#888',
        lineWidth: 1.8,
        endArrow: { path: G6.Arrow.triangle(6, 8, 0), fill: '#888' }
      },
      labelCfg: {
        autoRotate: false,
        style: {
          fill: '#d5d5d5',
          fontSize: 11,
          background: { fill: '#1a1a2e', padding: [2, 5], radius: 3 }
        }
      }
    },
    modes: {
      default: ['drag-canvas', 'zoom-canvas', 'drag-node']
    }
  })

  // 关闭画布局部刷新（localRefresh），否则缩放/拖拽时 canvas 只重绘局部区域导致拖影
  graph.get('canvas').set('localRefresh', false)

  graph.on('node:mouseenter', (e) => highlightNeighbors(e.item.getID()))
  graph.on('node:mouseleave', clearHighlight)
}

function renderGraph() {
  if (!containerRef.value) return
  if (!graph) initGraph()
  if (!graph) return

  const pos = computeTreeLayout(props.nodes, props.edges)

  const gNodes = props.nodes.map(n => {
    const p = pos.get(n.id)
    return {
      id: n.id,
      label: n.name,
      x: p.x,
      y: p.y,
      gmsfhm: n.gmsfhm,
      xbdm: n.xbdm,
      yhzgxdm: n.yhzgxdm,
      size: n.center ? [104, 42] : [84, 36],
      style: buildNodeStyle(n)
    }
  })

  const gEdges = props.edges.map(e => {
    const reverse = e.type === 'FATHER_OF' || e.type === 'MOTHER_OF'
    const color = EDGE_COLORS[e.type] || '#888'
    const edge = {
      source: reverse ? e.target : e.source,
      target: reverse ? e.source : e.target,
      label: e.label || e.type,
      style: { stroke: color }
    }
    if (e.type !== 'MARRIED_TO') {
      edge.style.endArrow = { path: G6.Arrow.triangle(6, 8, 0), fill: color }
    }
    return edge
  })

  graph.data({ nodes: gNodes, edges: gEdges })
  graph.render()
  graph.fitView(60)
}

function highlightNeighbors(nodeId) {
  const node = graph.findById(nodeId)
  if (!node) return
  const neighbors = node.getNeighbors()
  const neighborIds = new Set([nodeId, ...neighbors.map(n => n.getID())])

  graph.getNodes().forEach(n => {
    graph.updateItem(n, { style: { opacity: neighborIds.has(n.getID()) ? 1 : 0.12 } })
  })
  graph.getEdges().forEach(e => {
    const m = e.getModel()
    const related = m.source === nodeId || m.target === nodeId
    graph.updateItem(e, { style: { opacity: related ? 1 : 0.08 } })
  })
}

function clearHighlight() {
  if (!graph) return
  graph.getNodes().forEach(n => graph.updateItem(n, { style: { opacity: 1 } }))
  graph.getEdges().forEach(e => graph.updateItem(e, { style: { opacity: 1 } }))
}

function onResize() {
  if (graph && containerRef.value) {
    graph.changeSize(containerRef.value.clientWidth || 800, 520)
    graph.fitView(60)
  }
}

onMounted(() => {
  nextTick(() => renderGraph())
  window.addEventListener('resize', onResize)
})

watch(() => [props.nodes, props.edges], () => renderGraph(), { deep: true })

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (graph) {
    graph.destroy()
    graph = null
  }
})
</script>

<style scoped>
.graph-wrap {
  width: 100%;
  border: 1px solid #2a2d3f;
  border-radius: 6px;
  overflow: hidden;
  background: #0f1226;
}
.graph {
  width: 100%;
  height: 520px;
}
.empty {
  padding: 40px;
  text-align: center;
  color: #909399;
  background: #fff;
}
</style>
