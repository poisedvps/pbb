<template>
  <div class="screen">
    <div class="btns">
      <button class="tool" @click="toggleFullscreen">{{ isFull ? '退出全屏' : '全屏' }}</button>
      <button class="tool" @click="onExit">退出</button>
    </div>

    <div class="head">
      <div class="head-left">
        <div class="title">信息科排班表</div>
        <div class="sub">{{ monthText }} · {{ versionText }}</div>
      </div>
      <div class="head-right">
        <div class="clock">{{ clockText }}</div>
        <div class="today">{{ dateText }}</div>
      </div>
    </div>

    <div ref="gridWrap" class="grid-wrap">
      <div v-if="!data" class="tip">等待数据…</div>
      <div v-else-if="!published" class="tip">本月排班尚未发布</div>
      <table v-else class="grid" :style="gridStyle" :data-row-h="layout.rowH"
             :data-cell-font="layout.cellFont" :data-name-font="layout.nameFont">
        <colgroup>
          <col :style="{ width: layout.nameW + 'px' }" />
          <col v-for="day in days" :key="day.date" :style="{ width: layout.colW + 'px' }" />
        </colgroup>
        <thead>
          <tr>
            <th class="name">姓名</th>
            <th v-for="day in days" :key="day.date" :class="{ we: isOff(day), now: day.date === todayDate }">
              <div>{{ dayNo(day.date) }}</div>
              <small>{{ weekLabel(day) }}</small>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.staffId">
            <td class="name">{{ row.name }}</td>
            <td v-for="day in days" :key="day.date" :class="{ now: day.date === todayDate }"
                :style="isDutyCell(row.staffId, day.date) ? { background: month.dutyPhoneColor } : null">
              <span v-if="cellOf(row, day.date)" class="chip" :style="chipStyle(row, day.date)">{{ chipText(row, day.date) }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="foot">
      <span v-for="s in legendShifts" :key="s.code" class="legend"><i class="swatch" :style="{ background: s.color }"></i>{{ s.name }}</span>
      <span class="legend"><i class="swatch" :style="{ background: month?.dutyPhoneColor }"></i>值班电话</span>
      <span class="sp"></span>
      <span>数据每 5 分钟自动刷新 · 仅显示已发布排班</span>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getScreen } from '../api/screen'
import { listShiftTypes } from '../api/shifts'
import { useAuthStore } from '../stores/auth'
import { screenLayout } from '../utils/screenLayout'

const auth = useAuthStore()
const router = useRouter()

const pad = (n) => String(n).padStart(2, '0')
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

const now = ref(new Date())
const data = ref(null)
const shifts = ref([])
const gridWrap = ref(null)
const box = ref({ width: 0, height: 0 })
const isFull = ref(false)
const layout = computed(() =>
  screenLayout({ width: box.value.width, height: box.value.height, rows: rows.value.length, days: days.value.length })
)
const gridStyle = computed(() => ({
  width: `${box.value.width}px`,
  height: `${box.value.height}px`,
  '--head-h': `${layout.value.headH}px`,
  '--row-h': `${layout.value.rowH}px`,
  '--cell-font': `${layout.value.cellFont}px`,
  '--name-font': `${layout.value.nameFont}px`,
  '--head-font': `${layout.value.headFont}px`
}))
const legendShifts = computed(() => shifts.value.filter((s) => s.enabled))

const measure = () => {
  if (gridWrap.value) box.value = { width: gridWrap.value.clientWidth, height: gridWrap.value.clientHeight }
  isFull.value = !!document.fullscreenElement
}
const onFullscreenChange = async () => { await nextTick(); measure() }
const toggleFullscreen = async () => {
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await document.documentElement.requestFullscreen()
  } catch {
    // 浏览器拒绝（如未由点击触发）时保持原样
  }
}

let clockTimer
let refreshTimer

// 大屏只展示当月，跨月后下一次刷新自动换到新月份
const load = async () => {
  const d = new Date()
  try {
    data.value = await getScreen(`${d.getFullYear()}-${pad(d.getMonth() + 1)}`)
    await nextTick()
    measure()
  } catch {
    // 失败提示由 http 拦截器统一弹出（含 403「无权限」），这里只保留上一次画面，不让页面崩
  }
}

// 表头时钟每 30 秒走一次，大屏挂在那里没人盯着，分钟级足够
const tick = () => {
  now.value = new Date()
}

onMounted(() => {
  measure()
  load()
  listShiftTypes()
    .then((r) => {
      shifts.value = r
    })
    .catch(() => {}) // 班次取不到只影响色块配色，表格照常显示
  clockTimer = setInterval(tick, 30000)
  refreshTimer = setInterval(load, 5 * 60000)
  window.addEventListener('resize', measure)
  document.addEventListener('fullscreenchange', onFullscreenChange)
})

onUnmounted(() => {
  clearInterval(clockTimer)
  clearInterval(refreshTimer)
  window.removeEventListener('resize', measure)
  document.removeEventListener('fullscreenchange', onFullscreenChange)
})

const month = computed(() => data.value?.month || null)
const days = computed(() => month.value?.days || [])
const rows = computed(() => month.value?.rows || [])
const todayDate = computed(() => ymd(now.value))

const clockText = computed(() => `${pad(now.value.getHours())}:${pad(now.value.getMinutes())}`)
const WEEK_CN = ['星期日', '星期一', '星期二', '星期三', '星期四', '星期五', '星期六']
const dateText = computed(() => {
  const d = now.value
  return `${d.getFullYear()}年${d.getMonth() + 1}月${d.getDate()}日 ${WEEK_CN[d.getDay()]}`
})

const monthText = computed(() => {
  const src = month.value?.yearMonth || ymd(now.value).slice(0, 7)
  const [y, m] = src.split('-').map(Number)
  return `${y}年${m}月`
})
const versionText = computed(() => (month.value?.version > 0 ? `已发布 v${month.value.version}` : '未发布'))

// 所有行都没格子才算未发布，个别人员没排班不算
const published = computed(() => rows.value.some((r) => Object.keys(r.cells || {}).length > 0))

const shiftByCode = computed(() => Object.fromEntries(shifts.value.map((s) => [s.code, s])))
const cellOf = (row, date) => row.cells?.[date] || null

const dutyPhones = computed(() => month.value?.dutyPhones || [])

// 值班电话负责人当周 7 天整格标亮（设计 §8.5）。weekStart/weekEnd 是 YYYY-MM-DD，与 day.date 同为字符串，直接比大小即可，
// 跨月的那一周在两个月的大屏里都会命中
const isDutyCell = (staffId, date) =>
  dutyPhones.value.some((d) => d.weekStart <= date && date <= d.weekEnd && d.staffId === staffId)

// 班次被停用后快照里仍带着它的 code，取不到就退成灰底代号，不留白格
const chipStyle = (row, date) => ({ background: shiftByCode.value[cellOf(row, date).shiftCode]?.color || '#9ca3af' })
const chipText = (row, date) => {
  const code = cellOf(row, date).shiftCode
  const name = shiftByCode.value[code]?.name || code
  return name.length > 2 ? name.slice(0, 2) : name
}

const dayNo = (date) => Number(date.slice(8, 10))
const WD = ['一', '二', '三', '四', '五', '六', '日']
const weekLabel = (day) => WD[(day.weekday || 1) - 1]
const isOff = (day) => day.kind === 'WEEKEND' || day.kind === 'HOLIDAY'

// 大屏账号退出要清登录态回登录页；科长是开新标签页过来的，直接关掉
const onExit = async () => {
  if (auth.role === 'SCREEN') {
    await auth.logout()
    router.push('/login')
  } else {
    window.close()
  }
}
</script>

<style scoped>
.screen {
  position: fixed;
  inset: 0;
  background: #0b1220;
  color: #e5e7eb;
  padding: 2.4vh 1.8vw 6.4vh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.btns { position: fixed; top: 2vh; right: 1.8vw; z-index: 3; display: flex; gap: 0.6vw; }
.tool {
  border: 1px solid rgba(229, 231, 235, 0.3);
  background: rgba(229, 231, 235, 0.12);
  color: #e5e7eb;
  border-radius: 6px;
  padding: 0.6vh 1.2vw;
  font-size: 1.5vh;
  cursor: pointer;
}
.tool:hover {
  background: rgba(229, 231, 235, 0.24);
}

.head { display: flex; align-items: flex-end; justify-content: space-between; padding-right: 8vw; }
.title { font-size: 3.6vh; font-weight: 700; letter-spacing: 0.08em; color: #fff; }
.sub { font-size: 1.9vh; color: #93c5fd; margin-top: 0.4vh; }
.head-right { text-align: right; }
.clock { font-size: 5.4vh; font-weight: 700; line-height: 1; font-variant-numeric: tabular-nums; color: #fff; }
.today { font-size: 1.9vh; color: #94a3b8; margin-top: 0.4vh; }

.grid-wrap { flex: 1; min-height: 0; margin-top: 1.6vh; overflow: hidden; border: 1px solid #1e293b; border-radius: 8px; }
.tip { height: 100%; display: flex; align-items: center; justify-content: center; font-size: 3vh; color: #64748b; }

table.grid { border-collapse: separate; border-spacing: 0; table-layout: fixed; }
table.grid th, table.grid td { padding: 0; text-align: center; border: 0; box-shadow: inset -1px -1px 0 #1e293b; overflow: hidden; }
table.grid thead tr { height: var(--head-h); }
table.grid tbody tr { height: var(--row-h); }
table.grid thead th { background: #111c33; color: #cbd5e1; font-weight: 500; line-height: 1.2; font-size: var(--head-font); }
table.grid thead th small { display: block; color: #64748b; font-size: calc(var(--head-font) * 0.7); }
table.grid thead th.we { background: #0e1729; color: #64748b; }
/* 今天所在整列高亮，表头一起亮，大屏上一眼能看到今天 */
table.grid th.now, table.grid td.now { background: #1e3a8a; }
table.grid .name { text-align: left; padding: 0 0.6vw; white-space: nowrap; text-overflow: ellipsis; font-size: var(--name-font); background: #0b1220; }
table.grid thead th.name { background: #111c33; }

.chip { display: flex; align-items: center; justify-content: center; height: calc(var(--row-h) - 8px); margin: 0 3px; border-radius: 4px; color: #fff; font-size: var(--cell-font); font-weight: 700; white-space: nowrap; }

.foot {
  position: fixed;
  left: 1.8vw;
  right: 1.8vw;
  bottom: 1.6vh;
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 1.7vh;
  color: #94a3b8;
}
.foot .sp { flex: 1; }
.foot .legend { display: inline-flex; align-items: center; gap: 6px; }
.foot .swatch { display: inline-block; width: 1.6vh; height: 1.6vh; border-radius: 3px; border: 1px solid rgba(229, 231, 235, 0.3); }
</style>
