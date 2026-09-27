<template>
  <el-card>
    <template #header>
      <div class="bar">
        <b>调班申请</b>
        <span class="note">换班、请假、替班都在这里发起和审批。</span>
      </div>
    </template>

    <div class="toolbar">
      <!-- 三个 scope 各自一条后端查询，切换就是重新拉一次列表，不在前端挑挑拣拣 -->
      <el-radio-group v-model="scope" :disabled="busy" @change="reload">
        <el-radio-button v-for="s in SCOPES" :key="s.value" :value="s.value">{{ s.label }}</el-radio-button>
      </el-radio-group>
      <div class="sp"></div>
      <div class="flow">流程：<b>申请人发起</b> → <b>对方确认</b> → <b>科长审批</b> → <b>自动更新排班</b></div>
      <div class="sp"></div>
      <!-- 没关联人员的账号既当不了申请人也当不了对方，发起也没人可换，直接不给按钮 -->
      <el-button v-if="auth.user?.staffId" type="primary" @click="dialogVisible = true">＋ 新建申请</el-button>
    </div>

    <el-table v-loading="loading" :data="list" border empty-text="暂无调班申请">
      <el-table-column prop="no" label="编号" width="92" />
      <el-table-column label="类型" width="80">
        <template #default="{ row }">{{ TYPE_TEXT[row.type] || row.type }}</template>
      </el-table-column>
      <el-table-column label="申请人" width="100">
        <template #default="{ row }">{{ row.applicantName || '—' }}</template>
      </el-table-column>
      <el-table-column label="原班次" width="140">
        <template #default="{ row }">{{ dateShift(row.applicantDate, row.applicantShift) }}</template>
      </el-table-column>
      <el-table-column label="对方 / 目标" min-width="180">
        <template #default="{ row }">{{ targetText(row) }}</template>
      </el-table-column>
      <el-table-column label="原因" min-width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.reason || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="STATUS_TAG[row.status]?.type || 'info'" size="small">
            {{ STATUS_TAG[row.status]?.text || row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150">
        <template #default="{ row }">
          <!-- 按钮全按后端算好的 canXxx 显示，前端不自己推状态机 -->
          <el-button v-if="row.canConfirm" link type="primary" :disabled="busy" @click="confirmRow(row)">同意</el-button>
          <el-button v-if="row.canConfirm" link type="danger" :disabled="busy" @click="rejectPeerRow(row)">拒绝</el-button>
          <el-button v-if="row.canCancel" link type="primary" :disabled="busy" @click="cancelRow(row)">撤销</el-button>
          <el-button v-if="row.canApprove" link type="primary" :disabled="busy" @click="reviewRow(row, true)">通过</el-button>
          <el-button v-if="row.canApprove" link type="danger" :disabled="busy" @click="reviewRow(row, false)">驳回</el-button>
          <span v-if="!row.canConfirm && !row.canCancel && !row.canApprove" class="none">—</span>
        </template>
      </el-table-column>
    </el-table>

    <p class="tip">系统内提示，不发送短信/企业微信通知。</p>
  </el-card>

  <SwapDialog v-model="dialogVisible" @created="reload" />
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { approveSwap, cancelSwap, confirmSwap, listSwaps, rejectPeerSwap, rejectSwap } from '../api/swaps'
import { listShiftTypes } from '../api/shifts'
import SwapDialog from '../components/SwapDialog.vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()

const SCOPES = [
  { value: 'ALL', label: '全部' },
  { value: 'TODO', label: '待我处理' },
  { value: 'MINE', label: '我发起的' }
]

const TYPE_TEXT = { SWAP: '换班', LEAVE: '请假', COVER: '替班' }

const STATUS_TAG = {
  PENDING_PEER: { type: 'warning', text: '待对方确认' },
  PENDING_ADMIN: { type: 'primary', text: '待科长审批' },
  APPROVED: { type: 'success', text: '已通过' },
  REJECTED: { type: 'danger', text: '已驳回' },
  CANCELLED: { type: 'info', text: '已撤销' }
}

const scope = ref('ALL')
const list = ref([])
const loading = ref(false)
const dialogVisible = ref(false)
// 一次只走一条流程：确认框还开着或请求还在路上时，别的行的按钮先禁用掉
const busy = ref(false)

const shifts = ref([])
const shiftName = (code) => shifts.value.find((s) => s.code === code)?.name || code

// 日期一律写成「10-13 夜班」这种原型口径；班次查不到（人员已删/没发布）只剩日期也比空白强
const dateShift = (date, code) => {
  if (!date) return '—'
  const md = date.slice(5, 10)
  return code ? `${md} ${shiftName(code)}` : md
}

// 替班没有「对方那天」——对方来上的是本人那一天，所以只显示「替班」；请假本来就没对方
const targetText = (row) => {
  if (row.type === 'LEAVE' || !row.targetName) return '—'
  if (row.type === 'COVER') return `${row.targetName} 替班`
  return `${row.targetName} ${dateShift(row.targetDate, row.targetShift)}`
}

// 快速切 scope 会并发发出多个 listSwaps，慢的旧响应不能盖掉新 scope 的列表
let reqSeq = 0

const reload = async () => {
  const seq = ++reqSeq
  loading.value = true
  try {
    const data = await listSwaps(scope.value)
    if (seq === reqSeq) list.value = data ?? []
  } catch {
    // 失败提示由 http 拦截器统一弹出；拉取失败不动当前列表
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

onMounted(async () => {
  reload()
  try {
    shifts.value = await listShiftTypes()
  } catch {
    // 班次取不到只影响格子里的班次名称，列表照常显示
  }
})

// 请求 + 提示 + 刷新；成功后列表由后端重算，按钮标志也就跟着更新了
const run = async (action, done) => {
  if (busy.value) return
  busy.value = true
  try {
    await action()
    ElMessage.success(done)
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1608 当前状态不允许此操作）
  } finally {
    busy.value = false
  }
}

const confirmRow = (row) => run(() => confirmSwap(row.id), '已同意，等待科长审批')
const rejectPeerRow = (row) => run(() => rejectPeerSwap(row.id), '已拒绝')

const cancelRow = async (row) => {
  const ok = await ElMessageBox.confirm(`撤销申请 ${row.no}？撤销后对方不再收到这条请求。`, '撤销', {
    type: 'warning',
    confirmButtonText: '确 定',
    cancelButtonText: '取 消'
  })
    .then(() => true)
    .catch(() => false)
  if (ok) await run(() => cancelSwap(row.id), '已撤销')
}

// 审批意见是选填的：留空 = 没有意见，仍然算点了确定；取消弹窗返回 ok:false，什么都不做
const askComment = async (title) => {
  try {
    const { value } = await ElMessageBox.prompt('可填写审批意见，留空也可以。', title, {
      confirmButtonText: '确 定',
      cancelButtonText: '取 消',
      inputPlaceholder: '审批意见（选填）',
      inputValidator: (v) => ((v || '').length <= 200 ? true : '审批意见最多 200 字')
    })
    return { ok: true, value: (value || '').trim() || null }
  } catch {
    return { ok: false }
  }
}

const reviewRow = async (row, pass) => {
  const review = await askComment(pass ? '通过' : '驳回')
  if (!review.ok) return
  await run(
    () => (pass ? approveSwap(row.id, review.value) : rejectSwap(row.id, review.value)),
    pass ? '已通过，排班已自动更新' : '已驳回'
  )
}
</script>

<style scoped>
.bar { display: flex; align-items: baseline; gap: 10px; }
.toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 10px; margin-bottom: 14px; }
.toolbar .sp { flex: 1; }
.flow { font-size: 12px; color: #6b7280; }
.flow b { color: #374151; font-weight: 600; }
.note { font-size: 12px; color: #6b7280; }
.none { color: #9ca3af; }
.tip { margin: 12px 0 0; font-size: 12px; color: #6b7280; }
</style>
