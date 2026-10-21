<template>
  <div class="dashboard">
    <div class="dashboard-header">
      <h2>数据总览</h2>
      <p>户籍关系图谱统计</p>
    </div>

    <el-row :gutter="20" class="stat-row">
      <el-col v-for="card in statCards" :key="card.label" :xs="24" :sm="12" :md="6">
        <div class="stat-card" :style="{ background: card.gradient }">
          <div class="stat-card-inner">
            <div class="stat-icon">
              <el-icon :size="32"><component :is="card.icon" /></el-icon>
            </div>
            <div class="stat-meta">
              <div class="stat-value">{{ card.value }}</div>
              <div class="stat-label">{{ card.label }}</div>
            </div>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-row :gutter="20">
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">关系类型分布</div>
          <div ref="pieChartRef" class="chart"></div>
        </div>
      </el-col>
      <el-col :xs="24" :md="12">
        <div class="chart-card">
          <div class="chart-title">关系类型数量</div>
          <div ref="barChartRef" class="chart"></div>
        </div>
      </el-col>
    </el-row>
  </div>
</template>

<script setup name="Index" lang="ts">
import { getGraphStats } from '@/api/graph';
import type { GraphStats } from '@/api/graph/types';
import * as echarts from 'echarts';

// 关系类型配色（与关系图谱边颜色一致）
const COLOR_MAP: Record<string, string> = {
  FATHER_OF: '#409EFF',
  MOTHER_OF: '#F56C6C',
  MARRIED_TO: '#E6A23C',
  SIBLING_OF: '#13C2C2',
  GRANDPARENT_OF: '#722ED1',
  GRANDCHILD_OF: '#9254DE',
  MATERNAL_GRANDCHILD_OF: '#531DAB',
  GREAT_GRANDCHILD_OF: '#7B2FBE',
  PARENT_SIBLING_OF: '#2F54EB',
  NEPHEW_OF: '#FA8C16',
  IN_LAW: '#EB2F96',
  OTHER_RELATIVE: '#8C8C8C'
};

const pieChartRef = ref<HTMLElement | null>(null);
const barChartRef = ref<HTMLElement | null>(null);
let pieChart: echarts.ECharts | null = null;
let barChart: echarts.ECharts | null = null;

const display = reactive({ nodeCount: 0, edgeCount: 0, male: 0, female: 0 });

const statCards = computed(() => [
  { label: '人员总数', value: display.nodeCount, icon: 'User', gradient: 'linear-gradient(135deg, #4f7cff 0%, #4fa8ff 100%)' },
  { label: '关系总数', value: display.edgeCount, icon: 'Connection', gradient: 'linear-gradient(135deg, #9b59ff 0%, #c471ff 100%)' },
  { label: '男性人数', value: display.male, icon: 'Male', gradient: 'linear-gradient(135deg, #00c9a7 0%, #00d2ff 100%)' },
  { label: '女性人数', value: display.female, icon: 'Female', gradient: 'linear-gradient(135deg, #ff6b9a 0%, #ff8fa3 100%)' }
]);

/** 数字滚动动画 */
function animateTo(target: number, setter: (v: number) => void, duration = 1200) {
  const start = performance.now();
  const tick = (now: number) => {
    const progress = Math.min((now - start) / duration, 1);
    const eased = 1 - Math.pow(1 - progress, 3);
    setter(Math.round(target * eased));
    if (progress < 1) {
      requestAnimationFrame(tick);
    }
  };
  requestAnimationFrame(tick);
}

function renderPie(stats: GraphStats) {
  if (!pieChartRef.value) return;
  pieChart = echarts.init(pieChartRef.value);
  const data = stats.relationTypes.map((item) => ({
    name: item.label,
    value: item.count,
    itemStyle: { color: COLOR_MAP[item.type] || '#909399' }
  }));
  pieChart.setOption({
    tooltip: { trigger: 'item', formatter: '{b}：{c} 条（{d}%）' },
    legend: { bottom: 0, icon: 'circle', textStyle: { color: '#606266' } },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        center: ['50%', '42%'],
        avoidLabelOverlap: true,
        itemStyle: { borderRadius: 8, borderColor: '#fff', borderWidth: 3 },
        label: { show: false },
        emphasis: {
          label: { show: true, fontSize: 18, fontWeight: 'bold' },
          scaleSize: 8
        },
        data,
        animationType: 'scale',
        animationEasing: 'cubicInOut',
        animationDuration: 1000
      }
    ],
    graphic: [
      {
        type: 'text',
        left: 'center',
        top: '36%',
        style: {
          text: String(stats.edgeCount),
          fontSize: 40,
          fontWeight: 700,
          fill: '#303133',
          textAlign: 'center'
        }
      },
      {
        type: 'text',
        left: 'center',
        top: '52%',
        style: {
          text: '关系总数',
          fontSize: 13,
          fill: '#909399',
          textAlign: 'center'
        }
      }
    ]
  });
}

function renderBar(stats: GraphStats) {
  if (!barChartRef.value) return;
  barChart = echarts.init(barChartRef.value);
  const labels = stats.relationTypes.map((item) => item.label);
  const values = stats.relationTypes.map((item) => item.count);
  const colors = stats.relationTypes.map((item) => COLOR_MAP[item.type] || '#909399');
  barChart.setOption({
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    grid: { left: 20, right: 20, top: 30, bottom: 20, containLabel: true },
    xAxis: {
      type: 'category',
      data: labels,
      axisLine: { lineStyle: { color: '#dcdfe6' } },
      axisTick: { show: false },
      axisLabel: { color: '#606266', fontSize: 13 }
    },
    yAxis: {
      type: 'value',
      splitLine: { lineStyle: { type: 'dashed', color: '#ebeef5' } },
      axisLabel: { color: '#909399' }
    },
    series: [
      {
        type: 'bar',
        data: values.map((value, i) => ({
          value,
          itemStyle: {
            borderRadius: [8, 8, 0, 0],
            color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
              { offset: 0, color: colors[i] },
              { offset: 1, color: colors[i] + '88' }
            ])
          }
        })),
        barMaxWidth: 56,
        label: { show: true, position: 'top', color: '#303133', fontWeight: 600 },
        animationEasing: 'cubicInOut',
        animationDuration: 1000
      }
    ]
  });
}

function handleResize() {
  pieChart?.resize();
  barChart?.resize();
}

const loadStats = async () => {
  try {
    const res = await getGraphStats();
    const stats = res.data;
    animateTo(stats.nodeCount, (v) => (display.nodeCount = v));
    animateTo(stats.edgeCount, (v) => (display.edgeCount = v));
    const male = stats.genderStats.find((g) => g.code === '1')?.count ?? 0;
    const female = stats.genderStats.find((g) => g.code === '2')?.count ?? 0;
    animateTo(male, (v) => (display.male = v));
    animateTo(female, (v) => (display.female = v));
    renderPie(stats);
    renderBar(stats);
  } catch (e: any) {
    console.error('加载统计失败', e);
  }
};

onMounted(() => {
  loadStats();
  window.addEventListener('resize', handleResize);
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize);
  pieChart?.dispose();
  barChart?.dispose();
});
</script>

<style lang="scss" scoped>
.dashboard {
  padding: 4px;
}

.dashboard-header {
  margin-bottom: 20px;

  h2 {
    margin: 0;
    font-size: 22px;
    font-weight: 700;
    color: #303133;
  }

  p {
    margin: 6px 0 0;
    font-size: 13px;
    color: #909399;
  }
}

.stat-row {
  margin-bottom: 4px;
}

.stat-card {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  margin-bottom: 20px;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.08);
  transition: transform 0.3s ease, box-shadow 0.3s ease;

  &::after {
    content: '';
    position: absolute;
    right: -30px;
    top: -30px;
    width: 110px;
    height: 110px;
    border-radius: 50%;
    background: rgba(255, 255, 255, 0.18);
  }

  &:hover {
    transform: translateY(-4px);
    box-shadow: 0 14px 32px rgba(0, 0, 0, 0.16);
  }
}

.stat-card-inner {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 24px 22px;
  color: #fff;
}

.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 56px;
  height: 56px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.22);
  flex-shrink: 0;
}

.stat-meta {
  overflow: hidden;
}

.stat-value {
  font-size: 34px;
  font-weight: 700;
  line-height: 1;
  text-shadow: 0 2px 6px rgba(0, 0, 0, 0.15);
}

.stat-label {
  margin-top: 8px;
  font-size: 13px;
  opacity: 0.92;
}

.chart-card {
  background: #fff;
  border-radius: 12px;
  padding: 20px;
  margin-bottom: 20px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.06);
}

.chart-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.chart {
  width: 100%;
  height: 320px;
}
</style>
