<template>
  <el-card>
    <template #header>
      <div class="bar">
        <b>统计报表</b>
        <span class="note">按已发布排班统计，科长看全科，成员只看自己。</span>
      </div>
    </template>

    <div class="toolbar">
      <!-- 年份只影响下面这批候选范围，换年份时仍停在原来选中的那个月 / 那个季度 -->
      <el-input-number v-model="year" class="year" :min="MIN_YEAR" :max="MAX_YEAR" :step="1" :controls="false" step-strictly />
      <el-select v-model="rangeKey" class="range">
        <el-option v-for="opt in options" :key="opt.value" :label="opt.label" :value="opt.value" />
      </el-select>
      <!-- 区间以返回体为准：换范围的请求还在路上时，屏幕上仍是上一份数据，导出的也必须是那一份 -->
      <span v-if="data" class="note">{{ data.from }} 至 {{ data.to }}</span>
      <div class="sp"></div>
      <el-button :disabled="!data" @click="exportExcel">导出 Excel</el-button>
    </div>

    <el-table
      v-loading="loading"
      :data="rows"
      border
      :show-summary="auth.isAdmin"
      :summary-method="summary"
      empty-text="这段时间还没有已发布的排班"
    >
      <el-table-column prop="name" label="姓名" min-width="110" />
      <el-table-column v-for="s in shifts" :key="s.code" :label="s.name" width="92" align="center">
        <template #default="{ row }">{{ row.counts?.[s.code] ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="节假日/周末上班" width="140" align="center">
        <template #default="{ row }">{{ row.offDayWork ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="总工时" width="100" align="center">
        <template #default="{ row }">{{ row.totalHours ?? 0 }}</template>
      </el-table-column>
    </el-table>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { getStats } from '../api/stats'
import { listShiftTypes } from '../api/shifts'
import { download } from '../api/download'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const pad = (n) => String(n).padStart(2, '0')
const now = new Date()
const MIN_YEAR = 2000
const MAX_YEAR = 2100
const QUARTER_CN = ['一', '二', '三', '四']

const year = ref(now.getFullYear())
// 选中的范围只存「相对年份」的键（M10 / Q4 / Y），年份另存在 year 里，两者拼成真正的区间，
// 这样改年份时选中的那个月不会跟着跳到 1 月。默认当前月。
const rangeKey = ref(`M${pad(now.getMonth() + 1)}`)
const shifts = ref([])
const data = ref(null)
const loading = ref(false)

// 改年份 / 换范围都会并发发出 getStats，慢的旧响应不能盖掉新选中的区间
let reqSeq = 0

const lastDay = (y, m) => new Date(y, m, 0).getDate()

const options = computed(() => {
  const y = year.value
  const list = []
  for (let m = 1; m <= 12; m++) list.push({ label: `${y}年${m}月`, value: `M${pad(m)}` })
  for (let q = 1; q <= 4; q++) list.push({ label: `${y}年第${QUARTER_CN[q - 1]}季度`, value: `Q${q}` })
  list.push({ label: `${y}年全年`, value: 'Y' })
  return list
})

// 年份被清空时（el-input-number 允许）没有区间可算，返回 null 由 reload / 导出各自让路
const range = computed(() => {
  const y = year.value
  const key = rangeKey.value
  let from = 1
  let to = 12
  if (key.startsWith('M')) from = to = Number(key.slice(1))
  else if (key.startsWith('Q')) {
    const q = Number(key.slice(1))
    from = (q - 1) * 3 + 1
    to = q * 3
  } else if (key !== 'Y') {
    return null
  }
  if (!Number.isInteger(y) || y < MIN_YEAR || y > MAX_YEAR) return null
  return { from: `${y}-${pad(from)}-01`, to: `${y}-${pad(to)}-${pad(lastDay(y, to))}` }
})

const reload = async () => {
  const target = range.value
  const seq = ++reqSeq
  if (!target) {
    data.value = null
    return
  }
  loading.value = true
  try {
    const resp = await getStats(target.from, target.to)
    if (seq === reqSeq) data.value = resp
  } catch {
    // 失败提示由 http 拦截器统一弹出；旧请求失败不动当前数据
    if (seq === reqSeq) data.value = null
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

onMounted(async () => {
  reload()
  try {
    shifts.value = await listShiftTypes()
  } catch {
    // 班次取不到只是少了各班次那几列，姓名 / 节假日 / 工时照常显示
  }
})

watch([year, rangeKey], reload)

const rows = computed(() => data.value?.rows || [])

const sum = (pick) => rows.value.reduce((acc, row) => acc + (Number(pick(row)) || 0), 0)

// 合计行按列序拼：姓名 + 各班次（顺序同 listShiftTypes）+ 节假日/周末上班 + 总工时。
// 列数和「班次列数 + 3」对不上说明表格渲染的列和 shifts 不是同一份，宁可不给合计也不串列。
const summary = ({ columns }) => {
  const cells = new Array(columns.length).fill('')
  if (columns.length !== shifts.value.length + 3) return cells
  cells[0] = '合计'
  shifts.value.forEach((s, i) => {
    cells[i + 1] = sum((row) => row.counts?.[s.code])
  })
  cells[shifts.value.length + 1] = sum((row) => row.offDayWork)
  cells[shifts.value.length + 2] = sum((row) => row.totalHours).toFixed(1)
  return cells
}

// 导出的就是屏幕上这份表：区间取返回体的 from / to，不是下拉框里正在切换的那个
const exportExcel = () => {
  const view = data.value
  if (!view) return
  download('/stats/export', { from: view.from, to: view.to }, '统计-' + view.from + '至' + view.to + '.xlsx')
}
</script>

<style scoped>
.bar { display: flex; align-items: baseline; gap: 10px; }
.note { font-size: 12px; color: #6b7280; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 14px; }
.toolbar .sp { flex: 1; }
.year { width: 96px; }
.range { width: 180px; }
</style>
