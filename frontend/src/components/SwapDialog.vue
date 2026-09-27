<template>
  <el-dialog v-model="visible" title="新建调班申请" width="520px" @closed="reset">
    <el-form label-width="88px">
      <el-form-item label="类型">
        <el-radio-group v-model="form.type" @change="onTypeChange">
          <el-radio value="SWAP">与同事换班</el-radio>
          <el-radio value="LEAVE">请假</el-radio>
          <el-radio value="COVER">替班（对方替我上）</el-radio>
        </el-radio-group>
      </el-form-item>

      <el-form-item label="我的日期">
        <el-date-picker
          v-model="form.applicantDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
        />
        <div class="hint">{{ myShiftText }}</div>
      </el-form-item>

      <el-form-item v-if="form.type !== 'LEAVE'" label="对方">
        <el-select v-model="form.targetStaffId" placeholder="选择同事" filterable clearable :loading="loading">
          <el-option v-for="p in peers" :key="p.staffId" :label="p.label" :value="p.staffId" />
        </el-select>
      </el-form-item>

      <el-form-item v-if="form.type === 'SWAP'" label="对方日期">
        <el-date-picker
          v-model="form.targetDate"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="选择日期"
          :disabled-date="notMyMonth"
        />
        <div class="hint">{{ targetShiftText }}</div>
      </el-form-item>

      <el-form-item label="原因">
        <el-input v-model="form.reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="选填，最多 200 字" />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">提交</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getSchedule } from '../api/schedules'
import { listShiftTypes } from '../api/shifts'
import { createSwap } from '../api/swaps'
import { useAuthStore } from '../stores/auth'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  defaultDate: { type: String, default: '' }
})
const emit = defineEmits(['update:modelValue', 'created'])

const auth = useAuthStore()
const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v)
})

const emptyForm = () => ({ type: 'SWAP', applicantDate: '', targetStaffId: null, targetDate: '', reason: '' })
const form = reactive(emptyForm())

const loading = ref(false)
const saving = ref(false)
const month = ref(null)
const shifts = ref([])

// 快速改日期会并发发出多个 getSchedule，慢的旧响应不能盖掉新日期的数据
let reqSeq = 0

const shiftName = (code) => shifts.value.find((s) => s.code === code)?.name || code
const rowOf = (staffId) => (month.value?.rows || []).find((r) => r.staffId === staffId)
const shiftCodeAt = (staffId, date) => rowOf(staffId)?.cells?.[date]?.shiftCode || ''

// 默认没人排班时三处小字都保持为空，不显示“我的班次：”这种半截文案
const myShiftText = computed(() => {
  if (!form.applicantDate) return ''
  const code = shiftCodeAt(auth.user?.staffId, form.applicantDate)
  return code ? `我的班次：${shiftName(code)}` : '该日无已发布班次'
})

const targetShiftText = computed(() => {
  if (!form.targetStaffId || !form.targetDate) return ''
  const code = shiftCodeAt(form.targetStaffId, form.targetDate)
  return code ? `对方班次：${shiftName(code)}` : '对方该日无已发布班次'
})

// 对方只能换同月内的班，跨月要拆成两张单子，这里直接不给选；
// 未选“我的日期”时整月都禁用，否则算不出范围
const notMyMonth = (d) => {
  if (!form.applicantDate) return true
  const [y, m] = form.applicantDate.split('-').map(Number)
  return d.getFullYear() !== y || d.getMonth() !== m - 1
}

// 下拉里只列本月排过班、且不是自己的人
const peers = computed(() =>
  (month.value?.rows || [])
    .filter((r) => r.staffId !== auth.user?.staffId)
    .map((r) => ({ staffId: r.staffId, label: `${r.name}（${r.empNo}）` }))
)

const loadMonth = async (date) => {
  const seq = ++reqSeq
  if (!date) {
    month.value = null
    return
  }
  loading.value = true
  try {
    const resp = await getSchedule(date.slice(0, 7))
    if (seq === reqSeq) month.value = resp
  } catch {
    // 失败提示由 http 拦截器统一弹出；旧请求失败不动当前数据
    if (seq === reqSeq) month.value = null
  } finally {
    if (seq === reqSeq) loading.value = false
  }
}

const onTypeChange = () => {
  // 请假没有对方，替班不要对方日期，提交前先把用不上的字段清掉
  if (form.type === 'LEAVE') form.targetStaffId = null
  if (form.type !== 'SWAP') form.targetDate = ''
}

// “我的日期”一变（手选或由 defaultDate 预填）就重拉整月：对方、对方班次都只看这一份数据
watch(
  () => form.applicantDate,
  (date) => {
    // 换月后原来的对方、对方日期都不在这一天了，一律重选
    form.targetStaffId = null
    form.targetDate = ''
    loadMonth(date)
  }
)

const reset = () => {
  Object.assign(form, emptyForm())
  month.value = null
}

// 班次名称全局不变，开一次弹窗取一次就够
onMounted(async () => {
  try {
    shifts.value = await listShiftTypes()
  } catch {
    // 班次取不到只影响小字里的班次名称，表单照常能填
  }
})

watch(visible, (open) => {
  // 只预填日期，拉数据交给上面的 watch，预填和手选走同一条路
  if (open && props.defaultDate) form.applicantDate = props.defaultDate
})

const submit = async () => {
  if (saving.value) return
  if (!form.applicantDate) {
    ElMessage.warning('请选择我的日期')
    return
  }
  saving.value = true
  try {
    await createSwap({
      type: form.type,
      applicantDate: form.applicantDate,
      // LEAVE 没有对方，COVER 只要对方来上本人那天，按类型只送用得上的字段
      targetStaffId: form.type === 'LEAVE' ? null : form.targetStaffId,
      targetDate: form.type === 'SWAP' ? form.targetDate : null,
      reason: form.reason
    })
    ElMessage.success(form.type === 'LEAVE' ? '已提交，等待科长审批' : '已提交，等待对方确认')
    emit('created')
    visible.value = false
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1606 请选择对方人员）
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.hint { color: #6b7280; font-size: 12px; line-height: 1.6; }
</style>
