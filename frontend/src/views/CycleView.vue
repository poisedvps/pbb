<template>
  <el-card>
    <template #header>
      <div class="card-head">
        <b>排班周期</b>
        <el-button type="primary" @click="openCreate">新增模板</el-button>
      </div>
    </template>

    <p class="note">按规则生成排班时选用。法定节假日固定为休息，调休上班日固定为白班。</p>

    <el-table v-loading="loading" :data="list" border empty-text="暂无模板">
      <el-table-column prop="name" label="名称" min-width="110" />
      <el-table-column v-for="(label, i) in DAY_LABELS" :key="label" :label="label" width="92" align="center">
        <template #default="{ row }">
          <div class="day-cell">
            <span class="code-chip" :style="{ background: colorOf(row.days[i]) }">{{ row.days[i] }}</span>
            <span class="shift-name">{{ nameOf(row.days[i]) }}</span>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="默认" width="76" align="center">
        <template #default="{ row }">
          <el-tag v-if="row.isDefault" type="success" size="small">默认</el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="120" align="center">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="saving" @click="openEdit(row)">编辑</el-button>
          <!-- 后端 1802 会拒绝删除默认模板，这里先把按钮置灰，不让科长白点一次 -->
          <el-tooltip content="默认模板不能删除" placement="top" :disabled="!row.isDefault">
            <span>
              <el-button link type="danger" :disabled="row.isDefault || saving" @click="remove(row)">删除</el-button>
            </span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <!-- 新增 / 编辑共用一个弹窗 -->
  <el-dialog v-model="dialogVisible" :title="editId === null ? '新增模板' : '编辑模板'" width="520px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="76px">
      <el-form-item label="名称" prop="name">
        <el-input v-model="form.name" maxlength="32" placeholder="如：夜班周期" />
      </el-form-item>
      <el-form-item v-for="(label, i) in DAY_LABELS" :key="label" :label="label">
        <el-select v-model="form.days[i]" class="day-select">
          <el-option v-for="s in enabledShifts" :key="s.code" :value="s.code" :label="`${s.code} ${s.name}`">
            <span class="opt">
              <span class="code-chip" :style="{ background: s.color }">{{ s.code }}</span>
              <span>{{ s.name }}</span>
            </span>
          </el-option>
        </el-select>
      </el-form-item>
      <el-form-item label="设为默认">
        <el-switch v-model="form.isDefault" />
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
import { ElMessage, ElMessageBox } from 'element-plus'
import { listCycleTemplates, createCycleTemplate, updateCycleTemplate, deleteCycleTemplate } from '../api/cycles'
import { listShiftTypes } from '../api/shifts'

const DAY_LABELS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
// 新增时预填「周一至周五白班、周六周日休息」，与 V2 迁移里的「标准周期」一致
const DEFAULT_DAYS = ['D', 'D', 'D', 'D', 'D', 'X', 'X']

const list = ref([])
const shifts = ref([])
const loading = ref(false)
const saving = ref(false)

// 色块和名称都来自班次设置，页面不再自己维护颜色
const shiftMap = computed(() => Object.fromEntries(shifts.value.map((s) => [s.code, s])))
const nameOf = (code) => shiftMap.value[code]?.name || code
const colorOf = (code) => shiftMap.value[code]?.color || '#9ca3af'
// 下拉只列启用中的班次：停用班次进了模板，按模板生成会被后端拦成 1803
const enabledShifts = computed(() => shifts.value.filter((s) => s.enabled))

const reload = async () => {
  loading.value = true
  try {
    list.value = await listCycleTemplates()
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}

// 班次表只有本页下拉与色块要用，进页面取一次即可
const loadShifts = async () => {
  try {
    shifts.value = await listShiftTypes()
  } catch {
    // 失败提示由 http 拦截器统一弹出
  }
}

onMounted(() => {
  loadShifts()
  reload()
})

const dialogVisible = ref(false)
const editId = ref(null)
const formRef = ref()
const form = reactive({ name: '', days: [...DEFAULT_DAYS], isDefault: false })

const rules = {
  name: [{ required: true, message: '请填写名称', trigger: 'blur' }]
}

const openDialog = async () => {
  dialogVisible.value = true
  await nextTick()
  formRef.value?.clearValidate()
}

const openCreate = () => {
  editId.value = null
  Object.assign(form, { name: '', days: [...DEFAULT_DAYS], isDefault: false })
  openDialog()
}

const openEdit = (row) => {
  editId.value = row.id
  Object.assign(form, { name: row.name, days: [...row.days], isDefault: row.isDefault })
  openDialog()
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
  const payload = { name: form.name.trim(), days: [...form.days], isDefault: form.isDefault }
  try {
    if (editId.value === null) await createCycleTemplate(payload)
    else await updateCycleTemplate(editId.value, payload)
    ElMessage.success('已保存')
    dialogVisible.value = false
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出（1801 重名、1803 班次不存在或已停用、1804 至少保留一个默认模板），
    // 弹窗保留，科长改完可以直接再保存
  } finally {
    saving.value = false
  }
}

const remove = async (row) => {
  const ok = await ElMessageBox.confirm(`确认删除模板「${row.name}」？`, '删除排班周期', {
    type: 'warning',
    confirmButtonText: '确 定',
    cancelButtonText: '取 消'
  })
    .then(() => true)
    .catch(() => false)
  if (!ok) return
  saving.value = true
  try {
    await deleteCycleTemplate(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.card-head { display: flex; align-items: center; justify-content: space-between; }
.note { margin: 0 0 12px; font-size: 12px; color: #6b7280; }
.day-cell { display: flex; flex-direction: column; align-items: center; gap: 2px; }
.shift-name { font-size: 12px; color: #6b7280; }
.day-select { width: 200px; }
.opt { display: flex; align-items: center; gap: 8px; }
/* 色块底色取班次自己的 color，与班次设置、排班表里的填充色一致 */
.code-chip {
  display: inline-block;
  min-width: 34px;
  padding: 1px 8px;
  border-radius: 4px;
  color: #fff;
  font-size: 12px;
  line-height: 20px;
  text-align: center;
}
</style>
