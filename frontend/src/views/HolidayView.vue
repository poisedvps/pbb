<template>
  <el-card>
    <template #header>
      <div class="bar">
        <b>节假日设置</b>
        <el-select v-model="year" class="year" @change="reload">
          <el-option v-for="y in yearOptions" :key="y" :label="`${y} 年`" :value="y" />
        </el-select>
        <span class="note">每年初由科长录入当年法定节假日与调休上班日（依据国务院放假安排）。</span>
        <div class="sp"></div>
        <el-button :loading="copying" @click="copyFromLastYear">从上一年复制</el-button>
        <el-button type="primary" @click="openCreate">＋ 添加节假日</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border empty-text="暂无，点击『添加节假日』录入">
      <el-table-column prop="name" label="名称" min-width="120" />
      <el-table-column label="日期范围" width="180">
        <template #default="{ row }">{{ rangeText(row) }}</template>
      </el-table-column>
      <el-table-column label="天数" width="80" align="center">
        <template #default="{ row }">{{ dayCount(row) }}</template>
      </el-table-column>
      <el-table-column label="类型" width="110" align="center">
        <template #default="{ row }">
          <el-tag :type="row.type === 'WORKDAY' ? 'primary' : 'danger'" size="small">
            {{ TYPE_TEXT[row.type] || row.type }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="说明" min-width="200">
        <template #default="{ row }">{{ row.remark || '—' }}</template>
      </el-table-column>
      <el-table-column label="操作" width="130" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="saving" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" :disabled="saving" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <p class="note type-note">
      类型说明：<el-tag type="danger" size="small">放假</el-tag> 当日默认休息；
      <el-tag type="primary" size="small">调休上班</el-tag> 周末但按工作日排白班。修改节假日后，未发布月份可点“按规则生成”刷新。
    </p>
  </el-card>

  <!-- 添加 / 编辑共用一个弹窗 -->
  <el-dialog v-model="dialogVisible" :title="editing ? '编辑节假日' : '添加节假日'" width="520px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="32" placeholder="如：春节" />
      </el-form-item>
      <el-form-item label="类型" prop="type">
        <el-radio-group v-model="form.type">
          <el-radio value="HOLIDAY">放假</el-radio>
          <el-radio value="WORKDAY">调休上班</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item label="日期" prop="range">
        <el-date-picker
          v-model="form.range"
          type="daterange"
          value-format="YYYY-MM-DD"
          range-separator="~"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
        />
      </el-form-item>
      <el-form-item label="说明" prop="remark">
        <el-input v-model="form.remark" maxlength="200" placeholder="选填" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="saving" @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listHolidays, createHoliday, updateHoliday, deleteHoliday, copyHolidays } from '../api/holidays'

const TYPE_TEXT = { HOLIDAY: '放假', WORKDAY: '调休上班' }

const THIS_YEAR = new Date().getFullYear()
// 只允许维护今年-1、今年、今年+1 三年
const yearOptions = [THIS_YEAR - 1, THIS_YEAR, THIS_YEAR + 1]
const year = ref(THIS_YEAR)

const list = ref([])
const loading = ref(false)
const copying = ref(false)
const saving = ref(false)

const reload = async () => {
  loading.value = true
  try {
    list.value = await listHolidays(year.value)
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
onMounted(reload)

// 后端返回 'YYYY-MM-DD'，单日只显示 MM-DD，多日显示 MM-DD ~ MM-DD
const md = (d) => String(d).slice(5)
const rangeText = (row) => (row.startDate === row.endDate ? md(row.startDate) : `${md(row.startDate)} ~ ${md(row.endDate)}`)
// 两个日期串都按 UTC 零点解析，差值不受本地时区影响
const dayCount = (row) => (Date.parse(row.endDate) - Date.parse(row.startDate)) / 86400000 + 1

const dialogVisible = ref(false)
const editing = ref(false)
const editId = ref(null)
const formRef = ref()
const form = reactive({ name: '', type: 'HOLIDAY', range: null, remark: '' })

const rules = {
  name: [{ required: true, message: '请填写名称', trigger: 'blur' }],
  type: [{ required: true, message: '请选择类型', trigger: 'change' }],
  range: [{ required: true, message: '请选择日期', trigger: 'change' }]
}

const resetForm = () => {
  Object.assign(form, { name: '', type: 'HOLIDAY', range: null, remark: '' })
}

const openCreate = async () => {
  editing.value = false
  editId.value = null
  resetForm()
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

const openEdit = async (row) => {
  editing.value = true
  editId.value = row.id
  Object.assign(form, {
    name: row.name,
    type: row.type,
    range: [row.startDate, row.endDate],
    remark: row.remark || ''
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
  saving.value = true
  const payload = {
    name: form.name.trim(),
    startDate: form.range[0],
    endDate: form.range[1],
    type: form.type,
    remark: form.remark.trim()
  }
  try {
    if (editing.value) {
      await updateHoliday(editId.value, payload)
      ElMessage.success('已保存')
    } else {
      await createHoliday(payload)
      ElMessage.success('已添加')
    }
    dialogVisible.value = false
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1403 日期重叠），弹窗保留以便修改
  } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  const ok = await ElMessageBox.confirm(`确定删除「${row.name}」（${rangeText(row)}）？`, '删除节假日', {
    type: 'warning',
    confirmButtonText: '确 定',
    cancelButtonText: '取 消'
  })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    await deleteHoliday(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    saving.value = false
  }
}

// 整年复制到当前选中年份，日期由后端整体顺延一年
const copyFromLastYear = async () => {
  const from = year.value - 1
  const to = year.value
  const ok = await ElMessageBox.confirm(
    `将 ${from} 年节假日复制到 ${to} 年，日期顺延一年，复制后请逐条核对`,
    '从上一年复制',
    { type: 'warning', confirmButtonText: '确 定', cancelButtonText: '取 消' }
  )
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  copying.value = true
  try {
    const count = await copyHolidays(from, to)
    ElMessage.success(`已复制 ${count} 条`)
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1404 目标年已有节假日）
  } finally {
    copying.value = false
  }
}
</script>

<style scoped>
.bar { display: flex; align-items: center; gap: 10px; }
.bar .year { width: 110px; }
.sp { flex: 1; }
.note { font-size: 12px; color: #6b7280; }
.type-note { margin: 12px 0 0; }
</style>
