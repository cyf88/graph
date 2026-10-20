<template>
  <div class="graph-wrap">
    <div class="graph-toolbar">
      <div>
        <strong>关系图</strong>
        <span class="view-hint">{{ viewMode === '3d' ? '拖拽旋转 · 滚轮缩放 · 悬停查看详情' : '拖拽节点或画布 · 滚轮缩放' }}</span>
      </div>
      <div class="view-switch" role="group" aria-label="关系图显示模式">
        <button :class="{ active: viewMode === '2d' }" :aria-pressed="viewMode === '2d'" @click="setViewMode('2d')">2D</button>
        <button :class="{ active: viewMode === '3d' }" :aria-pressed="viewMode === '3d'" @click="setViewMode('3d')">3D</button>
      </div>
    </div>
    <div v-if="nodes.length === 0" class="empty">暂无数据</div>
    <template v-else>
      <div v-show="viewMode === '2d'" ref="containerRef" class="graph"></div>
      <div v-show="viewMode === '3d'" ref="threeContainerRef" class="graph graph-3d">
        <div v-if="threeError" class="graph-error">{{ threeError }}</div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, watch, nextTick } from 'vue';
import G6 from '@antv/g6';

const props = defineProps({
  nodes: { type: Array, default: () => [] },
  edges: { type: Array, default: () => [] }
});

const containerRef = ref<HTMLElement | null>(null);
const threeContainerRef = ref<HTMLElement | null>(null);
const viewMode = ref('2d');
const threeError = ref('');
let graph: any = null;
let graph3d: any = null;
let graph3dFactory: any = null;
let THREE: any = null;
let highlighted3dNodeIds: Set<string> | null = null;
let graph3dHasFitted = false;

const EDGE_COLORS: Record<string, string> = {
  FATHER_OF: '#409EFF',
  MOTHER_OF: '#F56C6C',
  MARRIED_TO: '#E6A23C'
};

// 与户主关系代码 -> 中文
const REL_LABELS: Record<string, string> = {
  '01': '户主',
  '02': '配偶',
  '03': '子',
  '04': '女',
  '05': '孙子女',
  '06': '外孙子女',
  '10': '儿媳',
  '11': '女婿'
};

/**
 * 计算族谱树布局
 */
function computeTreeLayout(nodes: any[], edges: any[]) {
  const ids = nodes.map((n) => n.id);
  const nodeMap = new Map(nodes.map((n) => [n.id, n]));

  // 并查集：配偶 -> 同一家庭单元
  const parent = new Map(ids.map((id) => [id, id]));
  const find = (x: any) => {
    let r = x;
    while (parent.get(r) !== r) r = parent.get(r);
    while (parent.get(x) !== r) {
      const nx = parent.get(x);
      parent.set(x, r);
      x = nx;
    }
    return r;
  };
  const union = (a: any, b: any) => {
    const ra = find(a);
    const rb = find(b);
    if (ra !== rb) parent.set(ra, rb);
  };

  const childEdges: any[] = [];
  for (const e of edges) {
    if (e.type === 'MARRIED_TO') {
      union(e.source, e.target);
    } else {
      childEdges.push([e.source, e.target]);
    }
  }

  const parentOfChild = new Map();
  for (const [p, c] of childEdges) {
    if (!parentOfChild.has(c)) parentOfChild.set(c, []);
    parentOfChild.get(c).push(p);
  }
  for (const e of edges) {
    if (e.type !== 'MARRIED_TO') continue;
    const pa = parentOfChild.get(e.source) || [];
    const pb = parentOfChild.get(e.target) || [];
    const allParents = [...pa, ...pb];
    for (let i = 1; i < allParents.length; i++) {
      union(allParents[0], allParents[i]);
    }
  }

  const parentIds = new Set(childEdges.map(([p]) => p));

  const anchorIds = new Set();
  const unitChildren = new Map();
  const unitParents = new Map();
  for (const [p, c] of childEdges) {
    const pc = find(p);
    const cc = find(c);
    if (pc === cc) continue;
    anchorIds.add(c);
    if (!unitChildren.has(pc)) unitChildren.set(pc, []);
    if (!unitChildren.get(pc).includes(cc)) unitChildren.get(pc).push(cc);
    if (!unitParents.has(cc)) unitParents.set(cc, new Set());
    unitParents.get(cc).add(pc);
  }

  const allUnits = new Set(ids.map((id) => find(id)));
  const unitRoots = [...allUnits].filter((u) => !unitParents.has(u));
  const unitLevel = new Map();
  const visiting = new Set();
  function computeLevel(u: any) {
    if (unitLevel.has(u)) return unitLevel.get(u);
    if (visiting.has(u)) return 0;
    visiting.add(u);
    const parents = unitParents.get(u);
    let lv = 0;
    if (parents && parents.size > 0) {
      for (const p of parents) {
        lv = Math.max(lv, computeLevel(p) + 1);
      }
    }
    visiting.delete(u);
    unitLevel.set(u, lv);
    return lv;
  }
  allUnits.forEach((u) => computeLevel(u));

  const unitMembers = new Map();
  ids.forEach((id) => {
    const u = find(id);
    if (!unitMembers.has(u)) unitMembers.set(u, []);
    unitMembers.get(u).push(id);
  });

  const unitAnchor = new Map();
  for (const [u, members] of unitMembers) {
    const anchor =
      members.find((m: any) => anchorIds.has(m)) ||
      members.find((m: any) => parentIds.has(m) && nodeMap.get(m).xbdm === '1') ||
      members.find((m: any) => parentIds.has(m)) ||
      members[0];
    unitAnchor.set(u, anchor);
  }

  const subW = new Map();
  function calcSubW(u: any) {
    if (subW.has(u)) return subW.get(u);
    const kids = (unitChildren.get(u) || []).filter((k: any) => k !== u);
    const own = unitMembers.get(u).length;
    let sum = 0;
    for (const k of kids) sum += calcSubW(k);
    const w = Math.max(own, sum);
    subW.set(u, w);
    return w;
  }
  unitRoots.forEach((r) => calcSubW(r));

  const unitX = new Map();
  let cursor = 0;
  function place(u: any) {
    if (unitX.has(u)) return;
    const kids = (unitChildren.get(u) || []).filter((k: any) => k !== u);
    const own = unitMembers.get(u).length;
    if (kids.length === 0) {
      unitX.set(u, cursor);
      cursor += own;
      return;
    }
    const startKids = cursor;
    for (const k of kids) place(k);
    const kidAnchors = kids.map((k: any) => unitX.get(k));
    const mean = (Math.min(...kidAnchors) + Math.max(...kidAnchors)) / 2;
    const ax = own === 2 ? mean - 0.5 : mean;
    unitX.set(u, ax);
    cursor = startKids + subW.get(u);
  }
  unitRoots.forEach((r) => place(r));
  allUnits.forEach((u) => {
    if (!unitX.has(u)) {
      unitX.set(u, cursor);
      cursor += unitMembers.get(u).length;
    }
  });

  const colGap = 240;
  const rowGap = 140;

  const pos = new Map();
  for (const [u, members] of unitMembers) {
    const ax = unitX.get(u) * colGap;
    const y = unitLevel.get(u) * rowGap;
    if (members.length === 1) {
      pos.set(members[0], { x: ax, y });
    } else {
      const anchor = unitAnchor.get(u);
      const spouse = members.find((m: any) => m !== anchor);
      pos.set(anchor, { x: ax, y });
      pos.set(spouse, { x: ax + colGap, y });
    }
  }
  const minGapX = 110;
  const rows = new Map();
  for (const p of pos.values()) {
    const row = Math.round(p.y / rowGap);
    if (!rows.has(row)) rows.set(row, []);
    rows.get(row).push(p);
  }
  for (const list of rows.values()) {
    list.sort((a: any, b: any) => a.x - b.x);
    for (let i = 1; i < list.length; i++) {
      if (list[i].x - list[i - 1].x < minGapX) {
        list[i].x = list[i - 1].x + minGapX;
      }
    }
  }
  let minX = Infinity;
  let maxX = -Infinity;
  for (const p of pos.values()) {
    if (p.x < minX) minX = p.x;
    if (p.x > maxX) maxX = p.x;
  }
  const offsetX = (minX + maxX) / 2;
  for (const p of pos.values()) {
    p.x -= offsetX;
  }
  return pos;
}

function buildNodeStyle(n: any) {
  let style: any = n.xbdm === '1'
    ? { fill: '#409EFF', stroke: '#79BBFF', lineWidth: 2 }
    : { fill: '#F56C6C', stroke: '#F89898', lineWidth: 2 };

  if (n.center || n.endpoint) {
    style = { ...style, stroke: '#FFD700', lineWidth: 3 };
  }
  return style;
}

function initGraph() {
  if (!containerRef.value) return;
  const width = containerRef.value.clientWidth || 800;

  const tooltip = new G6.Tooltip({
    offsetX: 12,
    offsetY: 12,
    itemTypes: ['node'],
    getContent(e: any) {
      const m = e.item.getModel();
      const gender = m.xbdm === '1' ? '男' : m.xbdm === '2' ? '女' : '-';
      const rel = REL_LABELS[m.yhzgxdm] || m.yhzgxdm || '-';
      return `<div style="background:#1c1f36;color:#e8e8ee;padding:10px 14px;border-radius:6px;border:1px solid #3a3f5c;font-size:13px;line-height:1.9;min-width:180px;">
        <div style="font-size:15px;font-weight:700;color:#fff;margin-bottom:2px;">${m.label}</div>
        <div>身份证号：${m.gmsfhm || '-'}</div>
        <div>性别：${gender}</div>
        <div>与户主关系：${rel}</div>
      </div>`;
    }
  });

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
  });

  graph.get('canvas').set('localRefresh', false);

  graph.on('node:mouseenter', (e: any) => highlightNeighbors(e.item.getID()));
  graph.on('node:mouseleave', clearHighlight);
}

function renderGraph() {
  if (!containerRef.value || viewMode.value !== '2d') return;
  if (!graph) initGraph();
  if (!graph) return;

  const pos = computeTreeLayout(props.nodes, props.edges);

  const gNodes = props.nodes.map((n: any) => {
    const p = pos.get(n.id);
    return {
      id: n.id,
      label: n.name,
      x: p.x,
      y: p.y,
      gmsfhm: n.gmsfhm,
      xbdm: n.xbdm,
      yhzgxdm: n.yhzgxdm,
      size: [84, 36],
      style: buildNodeStyle(n)
    };
  });

  const gEdges = props.edges.map((e: any) => {
    const reverse = e.type === 'FATHER_OF' || e.type === 'MOTHER_OF';
    const color = EDGE_COLORS[e.type] || '#888';
    const edge: any = {
      source: reverse ? e.target : e.source,
      target: reverse ? e.source : e.target,
      label: e.label || e.type,
      style: { stroke: color }
    };
    if (e.type !== 'MARRIED_TO') {
      edge.sourceAnchor = 1;
      edge.targetAnchor = 3;
      edge.style.endArrow = { path: G6.Arrow.triangle(6, 8, 0), fill: color };
    }
    return edge;
  });

  graph.data({ nodes: gNodes, edges: gEdges });
  graph.render();
  graph.fitView(60);
}

function escapeHtml(value: any) {
  return String(value ?? '').replace(/[&<>"']/g, (char: string) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[char] as string
  );
}

function hashDepth(id: string) {
  let hash = 0;
  for (const char of String(id)) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  return ((Math.abs(hash) % 100) - 50) * 1.2;
}

function build3dData() {
  const pos = computeTreeLayout(props.nodes, props.edges);
  const nodes = props.nodes.map((n: any) => {
    const p = pos.get(n.id) || { x: 0, y: 0 };
    const gender = n.xbdm === '1' ? '男' : n.xbdm === '2' ? '女' : '-';
    const rel = REL_LABELS[n.yhzgxdm] || n.yhzgxdm || '-';
    return {
      id: n.id,
      name: n.name,
      color: n.xbdm === '1' ? '#409EFF' : '#F56C6C',
      x: p.x,
      y: -p.y,
      z: hashDepth(n.id),
      gmsfhm: n.gmsfhm,
      gender,
      rel
    };
  });
  const links = props.edges.map((e: any) => {
    const reverse = e.type === 'FATHER_OF' || e.type === 'MOTHER_OF';
    const color = EDGE_COLORS[e.type] || '#8892a8';
    return {
      source: reverse ? e.target : e.source,
      target: reverse ? e.source : e.target,
      label: e.label || e.type,
      color,
      directional: e.type !== 'MARRIED_TO'
    };
  });
  return { nodes, links };
}

async function renderGraph3d() {
  if (!threeContainerRef.value || viewMode.value !== '3d' || props.nodes.length === 0) return;
  threeError.value = '';
  try {
    if (!graph3dFactory) {
      const [module, threeModule] = await Promise.all([import('3d-force-graph'), import('three')]);
      graph3dFactory = module.default;
      THREE = threeModule;
    }
    if (!graph3d && threeContainerRef.value) {
      graph3d = new graph3dFactory(threeContainerRef.value, { controlType: 'orbit' })
        .enableNavigationControls(true)
        .backgroundColor('#0f1226')
        .showNavInfo(false)
        .nodeRelSize(5)
        .nodeVal(2.2)
        .nodeColor((node: any) => node.color)
        .nodeOpacity(0.98)
        .nodeThreeObject((node: any) => {
          const canvas = document.createElement('canvas');
          canvas.width = 256;
          canvas.height = 64;
          const context = canvas.getContext('2d')!;
          context.fillStyle = node.color;
          context.font = 'bold 28px "Microsoft YaHei", sans-serif';
          context.textAlign = 'center';
          context.textBaseline = 'middle';
          context.fillText(node.name || '未知', canvas.width / 2, canvas.height / 2, 240);
          const texture = new THREE.CanvasTexture(canvas);
          texture.colorSpace = THREE.SRGBColorSpace;
          const label = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true, depthWrite: false, depthTest: false }));
          label.position.set(0, 11, 0);
          label.scale.set(42, 10.5, 1);
          label.renderOrder = 999;
          return label;
        })
        .nodeThreeObjectExtend(true)
        .nodeLabel((node: any) => `<div style="background:#1c1f36;color:#e8e8ee;padding:10px 14px;border-radius:6px;border:1px solid #3a3f5c;font-size:13px;line-height:1.9;min-width:180px"><strong style="font-size:15px;color:#fff">${escapeHtml(node.name)}</strong><br>身份证号：${escapeHtml(node.gmsfhm || '-')}<br>性别：${escapeHtml(node.gender)}<br>与户主关系：${escapeHtml(node.rel)}</div>`)
        .linkColor((link: any) => highlighted3dNodeIds && link.source.id && link.target.id && !highlighted3dNodeIds.has(link.source.id) && !highlighted3dNodeIds.has(link.target.id) ? '#777777' : '#ffffff')
        .linkOpacity(0.82)
        .linkWidth(0.28)
        .linkResolution(4)
        .linkThreeObject((link: any) => {
          const canvas = document.createElement('canvas');
          canvas.width = 320;
          canvas.height = 88;
          const context = canvas.getContext('2d')!;
          context.fillStyle = '#ffffff';
          context.font = '300 24px "Microsoft YaHei", sans-serif';
          context.textAlign = 'center';
          context.textBaseline = 'middle';
          context.shadowColor = 'rgba(15, 18, 38, 0.9)';
          context.shadowBlur = 3;
          context.fillText(link.label || '关系', canvas.width / 2, canvas.height / 2, 296);
          const texture = new THREE.CanvasTexture(canvas);
          texture.colorSpace = THREE.SRGBColorSpace;
          const label = new THREE.Sprite(new THREE.SpriteMaterial({ map: texture, transparent: true, depthWrite: false }));
          label.scale.set(30, 8.25, 1);
          return label;
        })
        .linkThreeObjectExtend(true)
        .linkPositionUpdate((label: any, { start, end }: any) => {
          label.position.set((start.x + end.x) / 2, (start.y + end.y) / 2 + 5, (start.z + end.z) / 2);
          return true;
        })
        .linkDirectionalArrowLength((link: any) => (link.directional ? 3.5 : 0))
        .linkDirectionalArrowRelPos(0.92)
        .linkLabel((link: any) => escapeHtml(link.label))
        .cooldownTicks(140)
        .onEngineStop(() => {
          if (!graph3dHasFitted) {
            graph3dHasFitted = true;
            graph3d?.zoomToFit(650, 90);
          }
        })
        .onNodeHover((node: any) => {
          const neighbors = new Set(node ? [node.id] : []);
          if (node) {
            props.edges.forEach((edge: any) => {
              if (edge.source === node.id) neighbors.add(edge.target);
              if (edge.target === node.id) neighbors.add(edge.source);
            });
          }
          highlighted3dNodeIds = node ? (neighbors as Set<string>) : null;
          graph3d.nodeColor((item: any) => (highlighted3dNodeIds && !highlighted3dNodeIds.has(item.id) ? '#34394f' : item.color));
          graph3d.linkColor((link: any) => highlighted3dNodeIds && link.source.id && link.target.id && !highlighted3dNodeIds.has(link.source.id) && !highlighted3dNodeIds.has(link.target.id) ? '#777777' : '#ffffff');
        });
    }
    const controls = graph3d.controls();
    if (controls) controls.enableZoom = true;
    graph3d.width(threeContainerRef.value.clientWidth || 800).height(520);
    graph3d.graphData(build3dData());
  } catch (error: any) {
    threeError.value = `3D 图谱初始化失败：${error.message || '浏览器可能不支持 WebGL'}`;
  }
}

function setViewMode(mode: string) {
  if (viewMode.value === mode) return;
  viewMode.value = mode;
  nextTick(() => {
    if (mode === '3d') {
      renderGraph3d();
    } else if (graph && containerRef.value) {
      graph.changeSize(containerRef.value.clientWidth || 800, 520);
      graph.fitView(60);
    } else {
      renderGraph();
    }
  });
}

function highlightNeighbors(nodeId: string) {
  const node = graph.findById(nodeId);
  if (!node) return;
  const neighbors = node.getNeighbors();
  const neighborIds = new Set([nodeId, ...neighbors.map((n: any) => n.getID())]);

  graph.getNodes().forEach((n: any) => {
    graph.updateItem(n, { style: { opacity: neighborIds.has(n.getID()) ? 1 : 0.12 } });
  });
  graph.getEdges().forEach((e: any) => {
    const m = e.getModel();
    const related = m.source === nodeId || m.target === nodeId;
    graph.updateItem(e, { style: { opacity: related ? 1 : 0.08 } });
  });
}

function clearHighlight() {
  if (!graph) return;
  graph.getNodes().forEach((n: any) => graph.updateItem(n, { style: { opacity: 1 } }));
  graph.getEdges().forEach((e: any) => graph.updateItem(e, { style: { opacity: 1 } }));
}

function onResize() {
  if (graph && containerRef.value) {
    graph.changeSize(containerRef.value.clientWidth || 800, 520);
    graph.fitView(60);
  }
  if (graph3d && threeContainerRef.value && viewMode.value === '3d') {
    graph3d.width(threeContainerRef.value.clientWidth || 800).height(520);
  }
}

onMounted(() => {
  nextTick(() => renderGraph());
  window.addEventListener('resize', onResize);
});

watch(
  () => [props.nodes, props.edges],
  () => {
    if (viewMode.value === '3d') renderGraph3d();
    else renderGraph();
  },
  { deep: true }
);

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize);
  if (graph) {
    graph.destroy();
    graph = null;
  }
  if (graph3d) {
    graph3d._destructor();
    graph3d = null;
  }
});
</script>

<style scoped>
.graph-wrap {
  width: 100%;
  border: 1px solid #2a2d3f;
  border-radius: 6px;
  overflow: hidden;
  background: #0f1226;
}
.graph-toolbar {
  min-height: 48px;
  padding: 8px 12px 8px 16px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #292d43;
  color: #e8e8ee;
}
.graph-toolbar strong {
  font-size: 14px;
}
.view-hint {
  margin-left: 12px;
  color: #9298b1;
  font-size: 12px;
}
.view-switch {
  display: inline-flex;
  padding: 3px;
  border: 1px solid #353a54;
  border-radius: 6px;
  background: #171a2d;
}
.view-switch button {
  padding: 5px 12px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #aeb4cc;
  font-size: 12px;
  cursor: pointer;
}
.view-switch button.active {
  background: #409eff;
  color: #fff;
}
.graph {
  width: 100%;
  height: 520px;
}
.graph-3d {
  position: relative;
}
.graph-error {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: #f89898;
  background: rgba(15, 18, 38, 0.92);
  font-size: 14px;
}
.empty {
  padding: 40px;
  text-align: center;
  color: #909399;
  background: #fff;
}
</style>
