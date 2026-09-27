<template>
  <div class="screen">
    <button class="exit" @click="onExit">退出</button>

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

    <div class="cards">
      <div class="card">
        <div class="card-label">今日白班在岗</div>
        <div class="card-value">{{ today ? today.dayCount + ' 人' : '—' }}</div>
      </div>
      <div class="card">
        <div class="card-label">今晚夜班</div>
        <div class="card-value names">{{ names(today?.night) }}</div>
      </div>
      <div class="card">
        <div class="card-label">今日值班</div>
        <div class="card-value names">{{ names(today?.duty) }}</div>
      </div>
      <div class="card">
        <div class="card-label">今日请假</div>
        <div class="card-value names">{{ names(today?.leave) }}</div>
      </div>
    </div>

    <div class="grid-wrap">
      <div v-if="!data" class="tip">等待数据…</div>
      <div v-else-if="!published" class="tip">本月排班尚未发布</div>
      <table v-else class="grid">
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
          <tr v-for="row in pageRows" :key="row.staffId">
            <td class="name">{{ row.name }}</td>
            <td v-for="day in days" :key="day.date" :class="{ now: day.date === todayDate }">
              <span v-if="cellOf(row, day.date)" class="chip" :style="chipStyle(row, day.date)">{{ chipText(row, day.date) }}</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="foot">
      <span v-if="paged" class="page">第 {{ page }}/{{ totalPages }} 页 · 每 15 秒自动翻页</span>
      <span class="sp"></span>
      <span>数据每 5 分钟自动刷新 · 仅显示已发布排班</span>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { getScreen } from '../api/screen'
import { listShiftTypes } from '../api/shifts'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const pad = (n) => String(n).padStart(2, '0')
const ymd = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

const now = ref(new Date())
const data = ref(null)
const shifts = ref([])
const page = ref(1)
const rowsPerPage = ref(10)

let clockTimer
let pageTimer
let refreshTimer

// 大屏只展示当月，跨月后下一次刷新自动换到新月份
const load = async () => {
  const d = new Date()
  try {
    data.value = await getScreen(`${d.getFullYear()}-${pad(d.getMonth() + 1)}`)
  } catch {
    // 失败提示由 http 拦截器统一弹出（含 403「无权限」），这里只保留上一次画面，不让页面崩
  }
}

// 表头时钟每 30 秒走一次，大屏挂在那里没人盯着，分钟级足够
const tick = () => {
  now.value = new Date()
  updateRowsPerPage()
}

// 一行 40px，表格区约占 62% 视口高，按窗口高度算出每页行数
const updateRowsPerPage = () => {
  rowsPerPage.value = Math.max(5, Math.floor((window.innerHeight * 0.62) / 40))
  if (page.value > totalPages.value) page.value = 1
}

const nextPage = () => {
  if (totalPages.value > 1) page.value = (page.value % totalPages.value) + 1
}

onMounted(() => {
  updateRowsPerPage()
  load()
  listShiftTypes()
    .then((r) => {
      shifts.value = r
    })
    .catch(() => {}) // 班次取不到只影响色块配色，表格照常显示
  clockTimer = setInterval(tick, 30000)
  pageTimer = setInterval(nextPage, 15000)
  refreshTimer = setInterval(load, 5 * 60000)
  window.addEventListener('resize', updateRowsPerPage)
})

onUnmounted(() => {
  clearInterval(clockTimer)
  clearInterval(pageTimer)
  clearInterval(refreshTimer)
  window.removeEventListener('resize', updateRowsPerPage)
})

const month = computed(() => data.value?.month || null)
const today = computed(() => data.value?.today || null)
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

const totalPages = computed(() => Math.max(1, Math.ceil(rows.value.length / rowsPerPage.value)))
const paged = computed(() => rows.value.length > rowsPerPage.value)
const pageRows = computed(() => {
  const start = (page.value - 1) * rowsPerPage.value
  return rows.value.slice(start, start + rowsPerPage.value)
})

const shiftByCode = computed(() => Object.fromEntries(shifts.value.map((s) => [s.code, s])))
const cellOf = (row, date) => row.cells?.[date] || null

// 班次被停用后快照里仍带着它的 code，取不到就退成灰底代号，不留白格
const chipStyle = (row, date) => ({ background: shiftByCode.value[cellOf(row, date).shiftCode]?.color || '#9ca3af' })
const chipText = (row, date) => shiftByCode.value[cellOf(row, date).shiftCode]?.name || cellOf(row, date).shiftCode

const dayNo = (date) => Number(date.slice(8, 10))
const WD = ['一', '二', '三', '四', '五', '六', '日']
const weekLabel = (day) => WD[(day.weekday || 1) - 1]
const isOff = (day) => day.kind === 'WEEKEND' || day.kind === 'HOLIDAY'

const names = (list) => (list?.length ? list.join('、') : '—')

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

.exit {
  position: fixed;
  top: 2vh;
  right: 1.8vw;
  z-index: 3;
  border: 1px solid rgba(229, 231, 235, 0.3);
  background: rgba(229, 231, 235, 0.12);
  color: #e5e7eb;
  border-radius: 6px;
  padding: 0.6vh 1.2vw;
  font-size: 1.5vh;
  cursor: pointer;
}
.exit:hover {
  background: rgba(229, 231, 235, 0.24);
}

.head { display: flex; align-items: flex-end; justify-content: space-between; padding-right: 8vw; }
.title { font-size: 3.6vh; font-weight: 700; letter-spacing: 0.08em; color: #fff; }
.sub { font-size: 1.9vh; color: #93c5fd; margin-top: 0.4vh; }
.head-right { text-align: right; }
.clock { font-size: 5.4vh; font-weight: 700; line-height: 1; font-variant-numeric: tabular-nums; color: #fff; }
.today { font-size: 1.9vh; color: #94a3b8; margin-top: 0.4vh; }

.cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 1.2vw; margin: 2.2vh 0; }
.card { background: #111c33; border: 1px solid #1e293b; border-radius: 8px; padding: 1.4vh 1vw; min-height: 9vh; overflow: hidden; }
.card-label { font-size: 1.7vh; color: #94a3b8; }
.card-value { font-size: 2.6vh; font-weight: 700; color: #fff; margin-top: 0.8vh; line-height: 1.3; }
.card-value.names { font-size: 2vh; font-weight: 500; }

.grid-wrap { flex: 1; overflow: auto; border: 1px solid #1e293b; border-radius: 8px; }
.tip { height: 100%; display: flex; align-items: center; justify-content: center; font-size: 3vh; color: #64748b; }

table.grid { border-collapse: separate; border-spacing: 0; width: max-content; min-width: 100%; }
table.grid th, table.grid td {
  border-right: 1px solid #1e293b;
  border-bottom: 1px solid #1e293b;
  text-align: center;
  padding: 0;
  height: 40px;
}
table.grid thead th { background: #111c33; color: #cbd5e1; min-width: 3.4vw; padding: 0.4vh 0; font-weight: 500; line-height: 1.2; font-size: 1.9vh; }
table.grid thead th small { display: block; color: #64748b; font-size: 1.5vh; }
table.grid thead th.we { background: #0e1729; color: #64748b; }
/* 今天所在整列高亮，表头一起亮，大屏上一眼能看到今天 */
table.grid th.now, table.grid td.now { background: #1e3a8a; }
table.grid .name {
  position: sticky;
  left: 0;
  z-index: 1;
  background: #0b1220;
  text-align: left;
  padding: 0 0.8vw;
  min-width: 7vw;
  white-space: nowrap;
  font-size: 1.9vh;
}
table.grid thead th.name { z-index: 2; background: #111c33; }
table.grid td { min-width: 3.4vw; }

.chip {
  display: inline-block;
  min-width: 2.4vw;
  padding: 0.3vh 0.2vw;
  border-radius: 4px;
  color: #fff;
  font-size: 1.5vh;
  font-weight: 600;
}

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
.foot .page { color: #93c5fd; }
</style>
