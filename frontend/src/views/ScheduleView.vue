<template>
  <el-card>
    <template #header>
      <div class="head">
        <b>排班表</b>
        <el-tag v-if="data" :type="statusTag.type" size="small">{{ statusTag.text }}</el-tag>
        <el-tag v-if="pendingCount > 0" type="danger" size="small">有 {{ pendingCount }} 处未暂存</el-tag>
      </div>
    </template>

    <div class="toolbar">
      <!-- 整月写入与暂存进行到时不许切月：确认文案说的是哪个月，请求就只能打给哪个月 -->
      <el-button :disabled="monthLocked || saving" @click="prevMonth">‹</el-button>
      <b class="month">{{ monthText }}</b>
      <el-button :disabled="monthLocked || saving" @click="nextMonth">›</el-button>
      <el-input v-model="keyword" class="kw" placeholder="搜索姓名/工号" clearable />
      <div class="sp"></div>
      <el-button v-if="auth.isAdmin" :loading="working" :disabled="monthLocked || saving || loading" @click="generate">⚡ 按规则生成</el-button>
      <el-button :disabled="loading" @click="exportExcel">导出 Excel</el-button>
      <el-button @click="print">打印</el-button>
      <el-button v-if="auth.isAdmin" class="screen-btn" @click="openScreen">🖥 大屏展示</el-button>
      <!-- 暂存把页面上的待暂存修改一次性写回草稿；有未暂存修改时发布仍可点，由 runOnMonth 弹提示拦下 -->
      <el-button
        v-if="auth.isAdmin"
        type="primary"
        :loading="saving"
        :disabled="pendingCount === 0 || monthLocked || saving || loading"
        @click="saveDraftNow"
      >
        暂存（{{ pendingCount }}）
      </el-button>
      <el-button v-if="auth.isAdmin" :disabled="pendingCount === 0 || monthLocked || saving || loading" @click="dropPending">放弃修改</el-button>
      <el-button v-if="auth.isAdmin" type="primary" :loading="working" :disabled="monthLocked || saving || loading" @click="publish">发布排班</el-button>
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
            <!-- 只有科长能改格子，成员挂了同一个 onClick 也在 openEditor 里被挡回去；
                 切月请求在途时屏幕上还是旧月那份数据，不开格子 -->
            <td
              v-for="day in days"
              :key="day.date"
              :class="{ cell: cellEditable, pending: isPending(row, day.date) }"
              @click="openEditor(row, day.date)"
            >
              <span
                v-if="viewCell(row, day.date)"
                class="chip"
                :class="{ manual: viewCell(row, day.date).manual, plain: viewCell(row, day.date).shiftCode === null }"
                :style="chipStyle(row, day.date)"
                >{{ chipText(row, day.date) }}</span
              >
            </td>
          </tr>
          <tr class="cov">
            <td class="name">在岗</td>
            <td v-for="day in days" :key="day.date">{{ onDuty[day.date] || 0 }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="foot">
      <div class="legend">
        <span v-for="s in shifts" :key="s.code">
          <i class="swatch" :style="{ background: s.color }"></i>{{ s.name }}
        </span>
      </div>
      <div class="sp"></div>
      <span v-if="auth.isAdmin" class="note">点击单元格修改班次，修改后点【暂存】保存；底部行 = 每日在岗人数</span>
    </div>
  </el-card>

  <!-- 改格子：【确定】只把修改留在页面上，点【暂存】才发给后端；「恢复规则默认」存 shiftCode=null -->
  <el-dialog v-model="editorVisible" :title="editorTitle" width="520px">
    <div class="opts">
      <button
        v-for="s in enabledShifts"
        :key="s.code"
        type="button"
        class="opt"
        :class="{ on: pick === s.code }"
        :style="{ background: s.color }"
        @click="pick = s.code"
      >
        {{ s.name }}
      </button>
      <button type="button" class="opt plain" :class="{ on: pick === DEFAULT_PICK }" @click="pick = DEFAULT_PICK">恢复规则默认</button>
    </div>
    <el-input
      v-model="remark"
      class="remark"
      type="textarea"
      :rows="2"
      maxlength="200"
      show-word-limit
      placeholder="备注（选填，最多 200 字）"
    />
    <template #footer>
      <el-button :disabled="saving" @click="editorVisible = false">取消</el-button>
      <el-button type="primary" :disabled="saving || loading" @click="confirmPick">确定</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { onBeforeRouteLeave } from 'vue-router'
import { generateSchedule, getSchedule, publishSchedule, saveDraft } from '../api/schedules'
import { download } from '../api/download'
import { listShiftTypes } from '../api/shifts'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

// 「恢复规则默认」既不是某个班次也不是没选，用一个不可能当班时代号的取值表示，保存时换成 null
const DEFAULT_PICK = '__default__'

const pad = (n) => String(n).padStart(2, '0')
const today = new Date()
// ym 是唯一的月份状态，切月份只改它，再由 reload 拉数据
const ym = ref(`${today.getFullYear()}-${pad(today.getMonth() + 1)}`)
const data = ref(null)
const shifts = ref([])
const keyword = ref('')
const loading = ref(false)
// 生成与发布都会整月重写，互斥进行，按钮共用一个忙碌态
const working = ref(false)
// 暂存请求进行中
const saving = ref(false)

// 未暂存的格子修改：key = `${staffId}|${workDate}`，点【暂存】才一次性发给后端（设计 §8.1 第 1 条）
// value = { staffId, workDate, shiftCode（null=恢复规则默认）, remark }
const pendingCells = ref({})
// 这批修改属于哪个月：切月的请求还在路上时屏幕上仍是旧月那份数据，
// 不绑月份就会把旧月的 workDate 和新月的 viewYm() 拼到同一个 PUT 里（后端 1503 整月拒）
const pendingYm = ref('')
// 未暂存修改的数量（M4-15 会把值班电话的数量加进来）
const pendingCount = computed(() => Object.keys(pendingCells.value).length)
const cellKey = (staffId, date) => `${staffId}|${date}`
const pendingOf = (row, date) =>
  pendingYm.value !== '' && pendingYm.value === viewYm() ? pendingCells.value[cellKey(row.staffId, date)] || null : null
const isPending = (row, date) => !!pendingOf(row, date)
// 格子什么时候能改：切月的 GET 在途 / 暂存未完成 / 整月重写在写时，屏幕上挂的都可能是旧一份数据，
// 这时候开格或确定就会把旧数据上的修改当成当前这份表的内容存下来
const cellEditable = computed(
  () => auth.isAdmin && !loading.value && !saving.value && !working.value && !monthLocked.value
)
const clearPending = () => {
  pendingCells.value = {}
  pendingYm.value = ''
}

// 新月份的数据落地后，旧月那批待暂存修改在新这份表上没有对应的格子，整批丢弃
const dropPendingOutside = (target) => {
  if (pendingYm.value && pendingYm.value !== target) clearPending()
}

// 快速连点 ‹ › 会并发发出多个 getSchedule，慢的旧响应不能盖掉新的月份
let reqSeq = 0

const reload = async (target = ym.value) => {
  const seq = ++reqSeq
  loading.value = true
  try {
    const resp = await getSchedule(target)
    // 慢的旧响应既不能盖掉新月份，也不能盖掉科长已经翻过去的视图
    if (seq === reqSeq && target === ym.value) {
      data.value = resp
      // 屏幕换成新月份的数据后，旧月那批待暂存修改没有格子可显示了，不能跟着新月份发出去
      dropPendingOutside(resp?.yearMonth)
      // 旧月份开着的弹窗里那一格已经不在屏幕上，一并关掉
      if (editYm.value && editYm.value !== resp?.yearMonth) editorVisible.value = false
    }
  } catch {
    // 失败提示由 http 拦截器统一弹出；旧请求失败不动当前数据
    if (seq === reqSeq && target === ym.value) {
      data.value = null
      // 屏幕上没有表了，待暂存的修改也没有格子可指，只能跟着丢掉
      clearPending()
    }
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

// 页面上还有未暂存的修改时，关掉浏览器/刷新都由浏览器弹原生离开提示
const beforeUnload = (e) => {
  if (pendingCount.value === 0) return
  e.preventDefault()
  e.returnValue = ''
}

onMounted(async () => {
  // 监听要在拉到数据之前就挂上：数据请求失败也不能让刷新绕过离开保护
  window.addEventListener('beforeunload', beforeUnload)
  reload()
  try {
    shifts.value = await listShiftTypes()
  } catch {
    // 班次取不到只影响色块配色，表格仍然照常显示
  }
})

onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))

// 左侧菜单、面包屑等站内跳走：同样要确认，取消就留在本页
onBeforeRouteLeave(async () => {
  if (pendingCount.value === 0) return true
  return confirmBox(`有 ${pendingCount.value} 处修改未暂存，切换页面将放弃这些修改，是否继续？`, '离开排班表')
})

const shiftMonth = async (delta) => {
  // 整月写入或暂存还挂在半途时切月，会让在途请求的目标月份和屏幕上的月份对不上
  if (monthLocked.value || saving.value) return
  if (pendingCount.value > 0) {
    const ok = await confirmBox(`有 ${pendingCount.value} 处修改未暂存，切换月份将放弃这些修改，是否继续？`, '切换月份')
    if (!ok) return
    clearPending()
  }
  const [y, m] = ym.value.split('-').map(Number)
  const date = new Date(y, m - 1 + delta, 1)
  ym.value = `${date.getFullYear()}-${pad(date.getMonth() + 1)}`
  // 弹窗里那条记录属于上一个月，切月份后既没意义也存不回去
  editorVisible.value = false
  reload()
}
const prevMonth = () => shiftMonth(-1)
const nextMonth = () => shiftMonth(1)
const print = () => window.print()
const openScreen = () => window.open('/screen', '_blank')

const labelOf = (v) => {
  const [y, m] = v.split('-').map(Number)
  return `${y}年${m}月`
}
const monthText = computed(() => labelOf(ym.value))
// 屏幕上这份表到底是哪个月以 data 为准：切月请求还在路上时它仍是上一份成功数据，
// 科长此刻看到、能点到的格子也都属于这个月，写入必须打给这个月而不是 ym
const viewYm = () => data.value?.yearMonth || ''

// 导出跟着屏幕走而不是 ym：切月请求还在路上时 data 仍是上一份成功数据，此刻看到的也只有那个月
const exportExcel = () => {
  const target = viewYm()
  if (!target) return
  download('/schedules/' + target + '/export', null, '排班表-' + target + '.xlsx')
}

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
// 停用的班次不能选，但表格里它的历史格子照常显示
const enabledShifts = computed(() => shifts.value.filter((s) => s.enabled))

const cellOf = (row, date) => row.cells?.[date] || null

// 屏幕上这一格当前该显示什么：未暂存的修改优先于后端数据，
// shiftCode=null 表示科长选了「恢复规则默认」，它也是一次修改，不能当成空格
const viewCell = (row, date) => {
  const pending = pendingOf(row, date)
  if (pending) return { shiftCode: pending.shiftCode, remark: pending.remark, manual: false }
  return cellOf(row, date)
}

// 班次被停用后旧排班仍带着它的 code，取不到就退成灰底代号，不留白格
const chipStyle = (row, date) => {
  const cell = viewCell(row, date)
  // 「恢复规则默认」没有色块，灰字由 .chip.plain 负责
  if (cell.shiftCode === null) return {}
  const shift = shiftByCode.value[cell.shiftCode]
  return { background: shift?.color || '#9ca3af' }
}
const chipText = (row, date) => {
  const code = viewCell(row, date).shiftCode
  if (code === null) return '默认'
  return shiftByCode.value[code]?.name || code
}

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

const confirmBox = (message, title) =>
  ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确 定', cancelButtonText: '取 消' })
    .then(() => true)
    .catch(() => false)

// 整月写入（确认框 + 请求 + 刷新）全程独占：确认文案里的月份与实际请求的月份必须始终是同一个
const monthLocked = ref(false)

const runOnMonth = async (action, message, title, done) => {
  // 暂存没过、上一份月数据没落定之前不发起整月写入，免得两条写入交错在同一张表上
  if (working.value || monthLocked.value || saving.value || loading.value) return
  // 整月写入会盖掉页面上未暂存的修改，先让科长自己暂存或放弃
  if (pendingCount.value > 0) {
    ElMessageBox.alert(`有 ${pendingCount.value} 处修改未暂存，请先暂存或放弃修改`, '提示').catch(() => {})
    return
  }
  const target = viewYm()
  if (!target) return
  monthLocked.value = true
  try {
    const ok = await confirmBox(message(labelOf(target)), title)
    if (!ok) return
    working.value = true
    const resp = await action(target)
    ElMessage.success(done(resp))
    // 只在还停在这一个月时刷新视图；换月了就把屏幕留给那边的请求
    if (viewYm() === target && ym.value === target) await reload(target)
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1504 本月还没有草稿）
  } finally {
    working.value = false
    monthLocked.value = false
  }
}

const generate = () =>
  runOnMonth(
    generateSchedule,
    (label) => `将按规则生成 ${label} 排班，手工调整过的格子不会被覆盖。`,
    '按规则生成',
    (resp) => `已生成 ${resp.generated} 格，跳过手工 ${resp.skippedManual} 格`
  )

const publish = () =>
  runOnMonth(
    publishSchedule,
    (label) => `确认发布 ${label} 排班？发布后全科成员与大屏可见。`,
    '发布排班',
    (resp) => `已发布 v${resp.version}`
  )

const editorVisible = ref(false)
const editRow = ref(null)
const editDate = ref('')
// 弹窗打开时屏幕上那个月：旧月开的弹窗不能把修改写进新月份的待暂存集合
const editYm = ref('')
const pick = ref(DEFAULT_PICK)
const remark = ref('')

const editorTitle = computed(() => {
  const day = days.value.find((d) => d.date === editDate.value)
  const week = day ? ` 周${WD[(day.weekday || 1) - 1]}` : ''
  return `${editRow.value?.name || ''} · ${editDate.value.slice(5, 10)}${week}`
})

const openEditor = (row, date) => {
  if (!cellEditable.value) return
  const cell = viewCell(row, date)
  editRow.value = row
  editDate.value = date
  editYm.value = viewYm()
  // 未暂存的修改优先于后端值，重开弹窗时预选的就是科长自己刚改上去的那个值；
  // 没排过班（整月还没生成过）时没有当前值，落在「恢复规则默认」上
  pick.value = cell?.shiftCode ?? DEFAULT_PICK
  remark.value = cell?.remark || ''
  editorVisible.value = true
}

// 【确定】只写页面：不发请求，同一格再改一次就是覆盖
const confirmPick = () => {
  // 切月的数据还在路上：此刻屏幕上这份表随时会被换掉，先不把这一格写进待暂存
  if (loading.value) return
  const staffId = editRow.value?.staffId
  const workDate = editDate.value
  // 弹窗开着的时候屏幕已经换月：这一格不属于现在这张表，关掉弹窗而不是写错月份
  if (!staffId || !workDate || editYm.value !== viewYm()) {
    editorVisible.value = false
    return
  }
  // 待暂存的永远只属于一个月；月份变了就是上一批该整批作废（切月本应先清掉，这里是兜底）
  if (pendingYm.value && pendingYm.value !== editYm.value) clearPending()
  pendingCells.value = {
    ...pendingCells.value,
    [cellKey(staffId, workDate)]: {
      staffId,
      workDate,
      shiftCode: pick.value === DEFAULT_PICK ? null : pick.value,
      remark: remark.value.trim() || null
    }
  }
  pendingYm.value = editYm.value
  editorVisible.value = false
}

const dropPending = async () => {
  const n = pendingCount.value
  if (n === 0) return
  const ok = await confirmBox(`确认放弃这 ${n} 处未暂存的修改？放弃后这些修改不会存进草稿。`, '放弃修改')
  if (!ok) return
  clearPending()
  ElMessage.info(`已放弃 ${n} 处修改`)
}

// 一次性把页面上的修改存回草稿；成功后才清空，失败时全留着让科长重试
const saveDraftNow = async () => {
  if (saving.value) return
  const target = viewYm()
  const entries = Object.values(pendingCells.value)
  if (!target || entries.length === 0) return
  // 兜底：一批待暂存的日期必须全属于要发的那个月，否则后端会整批拒（1503）。
  // 走到这里说明页面和数据对不上，宁可作废这批修改也不发跨月日期
  if (pendingYm.value !== target || entries.some((e) => !e.workDate.startsWith(target))) {
    clearPending()
    ElMessage.warning('待暂存的修改不属于当前月份，已作废，请重新修改')
    return
  }
  saving.value = true
  try {
    await saveDraft(target, { entries, dutyPhones: [] })
    clearPending()
    ElMessage.success(`已暂存 ${entries.length} 处修改`)
    // 只在还停在这一个月时刷新视图；换月了就把屏幕留给那边的请求
    if (viewYm() === target && ym.value === target) await reload(target)
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1503 日期不在本月）；pendingCells 原样保留
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.head { display: flex; align-items: center; gap: 10px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; }
.toolbar .sp { flex: 1; }
.toolbar .screen-btn { background: #0f172a; border-color: #0f172a; color: #fff; }
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
/* 科长的格子可以点， hover 描边提示这里能改（成员的 td 不加这个 class） */
table.grid td.cell { cursor: pointer; }
table.grid td.cell:hover { outline: 2px solid #1677c8; outline-offset: -2px; }
/* 未暂存的格子：橙色虚线框，与已落库的格子区分（与 hover 同位，后写者胜） */
table.grid td.pending { outline: 2px dashed #f59e0b; outline-offset: -2px; }

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
/* 待暂存的「恢复规则默认」：还没有落库，只能以灰字提示规则值 */
.chip.plain { background: #f3f4f6; color: #6b7280; font-weight: 400; }

.foot { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; margin-top: 10px; }
.foot .sp { flex: 1; }
.foot .note { font-size: 12px; color: #6b7280; }
.legend { display: flex; gap: 14px; flex-wrap: wrap; font-size: 12px; color: #6b7280; }
.legend span { display: inline-flex; align-items: center; gap: 4px; }
.swatch { display: inline-block; width: 14px; height: 14px; border-radius: 3px; }

/* 班次按钮组：背景用班次自己的颜色，选中的加一圈描边 */
.opts { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.opt {
  border: 0;
  border-radius: 6px;
  color: #fff;
  cursor: pointer;
  font-size: 13px;
  font-weight: 600;
  padding: 8px 4px;
}
.opt.plain { background: #fff; border: 1px dashed #9ca3af; color: #374151; font-weight: 400; }
.opt.on { box-shadow: 0 0 0 2px #fff, 0 0 0 4px #111827; }
.remark { margin-top: 14px; }
</style>
