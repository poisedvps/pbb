<template>
  <el-card>
    <template #header>
      <div class="head">
        <b>排班表</b>
        <el-tag v-if="data" :type="statusTag.type" size="small">{{ statusTag.text }}</el-tag>
      </div>
    </template>

    <div class="toolbar">
      <el-button @click="prevMonth">‹</el-button>
      <b class="month">{{ monthText }}</b>
      <el-button @click="nextMonth">›</el-button>
      <el-input v-model="keyword" class="kw" placeholder="搜索姓名/工号" clearable />
      <div class="sp"></div>
      <el-button @click="print">打印</el-button>
    </div>

    <!-- 规则说明只给科长看，成员不需要关心默认规则怎么来的 -->
    <div v-if="auth.isAdmin" class="info">
      <b>排班规则：</b>工作日（周一至周五）默认<b>白班</b>，双休日与节假日默认<b>休息</b>；“调休上班”日按工作日处理。
      夜班 / 值班 / 备班 / 请假在此基础上<b>手工调整</b>（手工改过的格子下方显示橙色线）。
    </div>

    <div v-loading="loading" class="grid-wrap">
      <table class="grid">
        <thead>
          <tr>
            <th class="name">姓名</th>
            <th v-for="day in days" :key="day.date" :class="headClass(day)">
              <div>{{ dayNo(day.date) }}</div>
              <small>{{ weekLabel(day) }}</small>
              <small v-if="day.holidayName" class="hol">{{ day.holidayName }}</small>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in visibleRows" :key="row.staffId">
            <td class="name">
              {{ row.name }}
              <small>{{ row.empNo }}</small>
            </td>
            <td v-for="day in days" :key="day.date">
              <span v-if="cellOf(row, day.date)" class="chip" :class="{ manual: cellOf(row, day.date).manual }" :style="chipStyle(row, day.date)">{{
                chipText(row, day.date)
              }}</span>
            </td>
          </tr>
          <tr class="cov">
            <td class="name">在岗</td>
            <td v-for="day in days" :key="day.date">{{ onDuty[day.date] || 0 }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="legend">
      <span v-for="s in shifts" :key="s.code">
        <i class="swatch" :style="{ background: s.color }"></i>{{ s.name }}
      </span>
    </div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getSchedule } from '../api/schedules'
import { listShiftTypes } from '../api/shifts'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const pad = (n) => String(n).padStart(2, '0')
const today = new Date()
// ym 是唯一的月份状态，切月份只改它，再由 reload 拉数据
const ym = ref(`${today.getFullYear()}-${pad(today.getMonth() + 1)}`)
const data = ref(null)
const shifts = ref([])
const keyword = ref('')
const loading = ref(false)

// 快速连点 ‹ › 会并发发出多个 getSchedule，慢的旧响应不能盖掉新的月份
let reqSeq = 0

const reload = async () => {
  const seq = ++reqSeq
  loading.value = true
  try {
    const resp = await getSchedule(ym.value)
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
    // 班次取不到只影响色块配色，表格仍然照常显示
  }
})

const shiftMonth = (delta) => {
  const [y, m] = ym.value.split('-').map(Number)
  const date = new Date(y, m - 1 + delta, 1)
  ym.value = `${date.getFullYear()}-${pad(date.getMonth() + 1)}`
  reload()
}
const prevMonth = () => shiftMonth(-1)
const nextMonth = () => shiftMonth(1)
const print = () => window.print()

const monthText = computed(() => {
  const [y, m] = ym.value.split('-').map(Number)
  return `${y}年${m}月`
})

const statusTag = computed(() => {
  if (data.value?.status === 'PUBLISHED') return { type: 'success', text: `已发布 v${data.value.version}` }
  // 成员读的是已发布快照，未发布对他们没有「草稿」这一说
  return auth.isAdmin
    ? { type: 'warning', text: '草稿 · 未发布' }
    : { type: 'warning', text: '本月排班尚未发布' }
})

const days = computed(() => data.value?.days || [])
const rows = computed(() => data.value?.rows || [])

const visibleRows = computed(() => {
  const kw = keyword.value.trim()
  if (!kw) return rows.value
  return rows.value.filter((r) => (r.name || '').includes(kw) || (r.empNo || '').includes(kw))
})

const shiftByCode = computed(() => Object.fromEntries(shifts.value.map((s) => [s.code, s])))

const cellOf = (row, date) => row.cells?.[date] || null

// 班次被停用后旧排班仍带着它的 code，取不到就退成灰底代号，不留白格
const chipStyle = (row, date) => {
  const shift = shiftByCode.value[cellOf(row, date).shiftCode]
  return { background: shift?.color || '#9ca3af' }
}
const chipText = (row, date) => shiftByCode.value[cellOf(row, date).shiftCode]?.name || cellOf(row, date).shiftCode

const dayNo = (date) => Number(date.slice(8, 10))
const WD = ['一', '二', '三', '四', '五', '六', '日']
const weekLabel = (day) => WD[(day.weekday || 1) - 1] + (day.kind === 'ADJUSTED_WORKDAY' ? ' 班' : '')
const headClass = (day) => (day.kind === 'WEEKEND' || day.kind === 'HOLIDAY' ? 'we' : '')

// 在岗人数按整列统计，不受搜索过滤影响
const onDuty = computed(() => {
  const count = {}
  for (const row of rows.value) {
    for (const [date, cell] of Object.entries(row.cells || {})) {
      if (shiftByCode.value[cell.shiftCode]?.countsAsWork) count[date] = (count[date] || 0) + 1
    }
  }
  return count
})
</script>

<style scoped>
.head { display: flex; align-items: center; gap: 10px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }
.toolbar .sp { flex: 1; }
.month { min-width: 90px; text-align: center; }
.kw { width: 150px; }
.info {
  border-left: 3px solid #1677c8;
  background: #e8f2fb;
  padding: 8px 12px;
  border-radius: 4px;
  font-size: 13px;
  margin-bottom: 12px;
}

.grid-wrap { overflow-x: auto; border: 1px solid #e3e7ee; border-radius: 6px; }
/* 表头与姓名列都要 sticky，border-collapse 会丢边框，所以用 separate */
table.grid { border-collapse: separate; border-spacing: 0; width: max-content; min-width: 100%; }
table.grid th, table.grid td {
  border-right: 1px solid #e3e7ee;
  border-bottom: 1px solid #e3e7ee;
  text-align: center;
  padding: 0;
  height: 38px;
}
table.grid thead th { background: #f9fafb; min-width: 46px; padding: 4px 2px; font-weight: 500; line-height: 1.2; }
table.grid thead th small { display: block; color: #6b7280; font-size: 11px; }
table.grid thead th.we { background: #f3f4f6; }
table.grid thead th .hol { color: #b91c1c; }
table.grid .name {
  position: sticky;
  left: 0;
  z-index: 1;
  background: #fff;
  text-align: left;
  padding: 0 10px;
  min-width: 110px;
  white-space: nowrap;
}
table.grid .name small { display: block; color: #6b7280; font-size: 11px; font-weight: 400; }
table.grid thead th.name { z-index: 2; background: #f9fafb; }
table.grid td { min-width: 46px; }
table.grid tr.cov td { background: #f9fafb; font-size: 12px; height: 30px; }
table.grid tr.cov .name { background: #f9fafb; }

.chip {
  display: inline-block;
  min-width: 34px;
  padding: 2px 4px;
  border-radius: 4px;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}
/* 手工调整过的格子：橙色底线，与规则生成的默认值区分 */
.chip.manual { border-bottom: 3px solid #f59e0b; }

.legend { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: #6b7280; margin-top: 10px; }
.legend span { display: inline-flex; align-items: center; gap: 4px; }
.swatch { display: inline-block; width: 14px; height: 14px; border-radius: 3px; }
</style>
