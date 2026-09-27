<template>
  <el-card>
    <template #header>
      <b>我的排班</b>
    </template>

    <div class="toolbar">
      <el-button @click="prevMonth">‹</el-button>
      <b class="month">{{ monthText }}</b>
      <el-button @click="nextMonth">›</el-button>
    </div>

    <div class="kpis">
      <div class="kpi">
        <div class="v">{{ kpiDay }}</div>
        <div class="l">本月白班</div>
      </div>
      <div class="kpi">
        <div class="v">{{ kpiNight }}</div>
        <div class="l">本月夜班 / 值班</div>
      </div>
      <div class="kpi">
        <div class="v">{{ kpiHours }}</div>
        <div class="l">本月工时（小时）</div>
      </div>
      <div class="kpi">
        <div class="v sm">{{ kpiNext }}</div>
        <div class="l">下一个班次</div>
      </div>
    </div>

    <div v-loading="loading" class="body">
      <!-- 成员只看已发布快照，没发布时整月都没有班次，日历也就没有意义 -->
      <el-empty v-if="data && !data.published" description="本月排班尚未发布" />

      <div v-else-if="data" class="cal">
        <div v-for="w in WD" :key="w" class="h">{{ w }}</div>
        <div v-for="(day, i) in cells" :key="i" class="d" :class="{ empty: !day, off: day && isOff(day) }">
          <template v-if="day">
            <div class="n">{{ dayNo(day.date) }}</div>
            <small v-if="day.holidayName" class="hol">{{ day.holidayName }}</small>
            <span v-if="day.shiftCode" class="chip" :style="{ background: colorOf(day.shiftCode) }">{{
              nameOf(day.shiftCode)
            }}</span>
          </template>
        </div>
      </div>
    </div>
  </el-card>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { getMine } from '../api/schedules'
import { listShiftTypes } from '../api/shifts'

const pad = (n) => String(n).padStart(2, '0')
const today = new Date()
// ym 是唯一的月份状态，切月份只改它，再由 reload 拉数据
const ym = ref(`${today.getFullYear()}-${pad(today.getMonth() + 1)}`)
const data = ref(null)
const shifts = ref([])
const loading = ref(false)

// 快速连点 ‹ › 会并发发出多个 getMine，慢的旧响应不能盖掉新的月份
let reqSeq = 0

const reload = async () => {
  const seq = ++reqSeq
  loading.value = true
  try {
    const resp = await getMine(ym.value)
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
    // 班次取不到只影响色块配色，日历和指标卡照常显示
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

const monthText = computed(() => {
  const [y, m] = ym.value.split('-').map(Number)
  return `${y}年${m}月`
})

const counts = computed(() => data.value?.counts || {})
const kpiDay = computed(() => counts.value.D || 0)
const kpiNight = computed(() => (counts.value.N || 0) + (counts.value.Z || 0))
const kpiHours = computed(() => data.value?.workHours ?? 0)

const shiftByCode = computed(() => Object.fromEntries(shifts.value.map((s) => [s.code, s])))
// 班次被停用后旧排班仍带着它的 code，取不到就退成灰底代号，不留白块
const colorOf = (code) => shiftByCode.value[code]?.color || '#9ca3af'
const nameOf = (code) => shiftByCode.value[code]?.name || code

const kpiNext = computed(() => {
  const next = data.value?.next
  if (!next) return '—'
  return `${next.date.slice(5, 7)}-${next.date.slice(8, 10)} ${nameOf(next.shiftCode)}`
})

const days = computed(() => data.value?.days || [])
// 1 号是周几，前面就补几个空格，格子才能对齐“一”~“日”
const cells = computed(() => {
  const list = days.value
  if (!list.length) return list
  return [...Array(list[0].weekday - 1).keys()].map(() => null).concat(list)
})

const dayNo = (date) => Number(date.slice(8, 10))
const WD = ['一', '二', '三', '四', '五', '六', '日']
const isOff = (day) => day.kind === 'WEEKEND' || day.kind === 'HOLIDAY'
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; }
.month { min-width: 90px; text-align: center; }

.kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; margin-bottom: 16px; }
.kpi { background: #fff; border: 1px solid #e3e7ee; border-radius: 8px; padding: 14px; }
.kpi .v { font-size: 24px; font-weight: 600; }
.kpi .v.sm { font-size: 16px; }
.kpi .l { color: #6b7280; font-size: 12px; }

.body { min-height: 200px; }
.cal { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; }
.cal .h { text-align: center; color: #6b7280; font-size: 12px; }
.cal .d { background: #fff; border: 1px solid #e3e7ee; border-radius: 6px; min-height: 78px; padding: 6px; }
/* 周末与放假整格压成灰底，一眼看出哪天不用上班 */
.cal .d.off { background: #f8fafc; }
.cal .d.empty { background: transparent; border: 0; }
.cal .d .n { font-size: 12px; color: #6b7280; }
.cal .d .hol { display: block; color: #b91c1c; font-size: 11px; }
.chip {
  display: inline-block;
  margin-top: 4px;
  min-width: 34px;
  padding: 2px 4px;
  border-radius: 4px;
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  text-align: center;
}
</style>
