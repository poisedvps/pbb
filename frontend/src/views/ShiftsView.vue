<template>
  <el-card>
    <template #header>
      <b>班次设置</b>
    </template>

    <p class="note">排班表、大屏的班次颜色与时间都取自这里。班次由系统预置，不能新增或删除。</p>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column label="简称" width="90" align="center">
        <template #default="{ row }">
          <span class="code-chip" :style="{ background: row.color }">{{ row.code }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ timeText(row) }}</template>
      </el-table-column>
      <el-table-column label="工时" width="90" align="center">
        <template #default="{ row }">{{ hoursText(row.workHours) }}</template>
      </el-table-column>
      <el-table-column label="计入出勤" width="100" align="center">
        <template #default="{ row }">{{ yesNo(row.countsAsWork) }}</template>
      </el-table-column>
      <el-table-column label="跨天" width="90" align="center">
        <template #default="{ row }">{{ yesNo(row.crossDay) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
            {{ row.enabled ? '启用' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="90" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <!-- 班次只有修改，没有新增：code 与排序不开放编辑 -->
  <el-dialog v-model="dialogVisible" title="编辑班次" width="460px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="16" placeholder="如：白班" />
      </el-form-item>
      <el-form-item label="上班时间" prop="startTime">
        <el-time-picker v-model="form.startTime" format="HH:mm" value-format="HH:mm" clearable placeholder="选择时间" />
      </el-form-item>
      <el-form-item label="下班时间" prop="endTime">
        <el-time-picker v-model="form.endTime" format="HH:mm" value-format="HH:mm" clearable placeholder="选择时间" />
      </el-form-item>
      <el-form-item label="跨天" prop="crossDay">
        <el-switch v-model="form.crossDay" />
      </el-form-item>
      <el-form-item label="工时" prop="workHours">
        <el-input-number v-model="form.workHours" :min="0" :max="24" :step="0.5" />
      </el-form-item>
      <el-form-item label="计入出勤" prop="countsAsWork">
        <el-switch v-model="form.countsAsWork" />
      </el-form-item>
      <el-form-item label="颜色" prop="color">
        <el-color-picker v-model="form.color" />
      </el-form-item>
      <el-form-item label="启用" prop="enabled">
        <!-- 禁用态自身不响应鼠标事件，tooltip 要包一层 span -->
        <el-tooltip content="规则排班必需" placement="top" :disabled="!isRequired">
          <span><el-switch v-model="form.enabled" :disabled="isRequired" /></span>
        </el-tooltip>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listShiftTypes, updateShiftType } from '../api/shifts'

// 白班、休息是规则排班的兜底班次，后端 1301 会拒绝停用，前端直接锁死开关
const REQUIRED_CODES = ['D', 'X']

const list = ref([])
const loading = ref(false)

// 两个时间都为空 = 备班/请假/休息这类无时间班次
const timeText = (row) => {
  if (!row.startTime && !row.endTime) return '—'
  const end = row.crossDay && row.endTime ? `次日${row.endTime}` : row.endTime
  return `${row.startTime || '—'} - ${end || '—'}`
}

// 工时是 NUMERIC(4,1)，15.0 显示成 15，14.5 保持 14.5
const hoursText = (value) => (value == null ? '—' : String(Number(value)))
const yesNo = (value) => (value ? '是' : '否')

const reload = async () => {
  loading.value = true
  try {
    list.value = await listShiftTypes()
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
onMounted(reload)

const dialogVisible = ref(false)
const saving = ref(false)
const editCode = ref('')
const formRef = ref()
const form = reactive({
  name: '',
  startTime: null,
  endTime: null,
  crossDay: false,
  workHours: 0,
  countsAsWork: true,
  color: '#1d4ed8',
  enabled: true
})

const isRequired = computed(() => REQUIRED_CODES.includes(editCode.value))

const rules = {
  name: [{ required: true, message: '请填写名称', trigger: 'blur' }]
}

const empty = (value) => value === null || value === undefined || value === ''

const openEdit = async (row) => {
  editCode.value = row.code
  Object.assign(form, {
    name: row.name,
    startTime: row.startTime || null,
    endTime: row.endTime || null,
    crossDay: row.crossDay,
    workHours: Number(row.workHours ?? 0),
    countsAsWork: row.countsAsWork,
    color: row.color,
    enabled: row.enabled
  })
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

const submit = async () => {
  if (saving.value) return
  try {
    // 校验不通过直接返回，不发请求
    await formRef.value.validate()
  } catch {
    return
  }
  // 一有一空后端会拦成 1302，这里用同一句提示，不白跑一次请求
  if (empty(form.startTime) !== empty(form.endTime)) {
    ElMessage.error('上下班时间需同时填写或同时为空')
    return
  }
  saving.value = true
  try {
    await updateShiftType(editCode.value, {
      name: form.name.trim(),
      startTime: empty(form.startTime) ? null : form.startTime,
      endTime: empty(form.endTime) ? null : form.endTime,
      crossDay: form.crossDay,
      workHours: form.workHours,
      countsAsWork: form.countsAsWork,
      color: form.color,
      enabled: form.enabled
    })
    ElMessage.success('已保存')
    dialogVisible.value = false
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1301 不能停用、1303 时间倒挂）
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.note { margin: 0 0 12px; font-size: 12px; color: #6b7280; }
/* 色块用班次自己的背景色，文字为 code，与排班表格里填充色一致 */
.code-chip {
  display: inline-block;
  min-width: 34px;
  padding: 1px 8px;
  border-radius: 4px;
  color: #fff;
  font-size: 12px;
  line-height: 20px;
}
</style>
