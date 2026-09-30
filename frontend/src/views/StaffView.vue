<template>
  <el-card>
    <template #header>
      <div class="bar">
        <el-checkbox v-model="includeInactive" @change="reload">显示已停用</el-checkbox>
        <div class="sp"></div>
        <el-button type="primary" @click="openCreate">新增人员</el-button>
      </div>
    </template>

    <el-table v-loading="loading" :data="list" border>
      <el-table-column label="序号" width="70" align="center">
        <template #default="{ $index }">{{ $index + 1 }}</template>
      </el-table-column>
      <el-table-column prop="empNo" label="工号" width="130" />
      <el-table-column prop="name" label="姓名" width="120" />
      <el-table-column label="岗位" width="130">
        <template #default="{ row }">{{ row.position || '—' }}</template>
      </el-table-column>
      <el-table-column prop="phone" label="联系电话" width="140" />
      <el-table-column label="参与排班" width="100" align="center">
        <template #default="{ row }">{{ row.schedulable ? '是' : '否' }}</template>
      </el-table-column>
      <el-table-column label="角色" width="100" align="center">
        <template #default="{ row }">{{ roleText(row.role) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.active ? 'success' : 'info'" size="small">
            {{ row.active ? '在职' : '停用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="260">
        <template #default="{ row, $index }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button v-if="row.role !== 'ADMIN'" link type="danger" @click="remove(row)">删除</el-button>
          <el-button link :disabled="$index === 0 || savingOrder" @click="move($index, -1)">上移</el-button>
          <el-button link :disabled="$index === list.length - 1 || savingOrder" @click="move($index, 1)">下移</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <!-- 新增 / 编辑共用：编辑时工号只读、无角色；在职与否已由【删除】取代 -->
  <el-dialog v-model="dialogVisible" :title="editing ? '编辑人员' : '新增人员'" width="460px">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
      <el-form-item label="工号" prop="empNo">
        <el-input v-model="form.empNo" :disabled="editing" placeholder="3-32 位字母、数字或下划线" />
      </el-form-item>
      <el-form-item label="姓名" prop="name">
        <el-input v-model="form.name" />
      </el-form-item>
      <el-form-item label="岗位" prop="position">
        <el-input v-model="form.position" />
      </el-form-item>
      <el-form-item label="联系电话" prop="phone">
        <el-input v-model="form.phone" />
      </el-form-item>
      <el-form-item label="参与排班" prop="schedulable">
        <el-switch v-model="form.schedulable" />
      </el-form-item>
      <el-form-item v-if="!editing" label="角色" prop="role">
        <el-select v-model="form.role">
          <el-option label="成员" value="MEMBER" />
          <el-option label="科长" value="ADMIN" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
    </template>
  </el-dialog>

  <!-- 初始密码只在这里出现一次 -->
  <el-dialog v-model="tempVisible" title="新增成功" width="460px">
    <p class="temp">账号：{{ tempInfo.username }}　初始密码：{{ tempInfo.tempPassword }}（只显示一次，首次登录需修改）</p>
    <template #footer>
      <el-button type="primary" @click="tempVisible = false">我已记录</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listStaff, createStaff, updateStaff, saveStaffOrder, deleteStaff } from '../api/staff'

const list = ref([])
const includeInactive = ref(false)
const loading = ref(false)
const savingOrder = ref(false)

const dialogVisible = ref(false)
const editing = ref(false)
const editId = ref(null)
const saving = ref(false)
const formRef = ref()
const form = reactive({ empNo: '', name: '', position: '', phone: '', schedulable: true, role: 'MEMBER', active: true })

const tempVisible = ref(false)
const tempInfo = reactive({ username: '', tempPassword: '' })

const rules = {
  empNo: [
    { required: true, message: '请填写工号', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '工号为 3-32 位字母、数字或下划线', trigger: 'blur' }
  ],
  name: [{ required: true, message: '请填写姓名', trigger: 'blur' }]
}

const roleText = (role) => (role === 'ADMIN' ? '科长' : role === 'MEMBER' ? '成员' : '—')

const reload = async () => {
  loading.value = true
  try {
    list.value = await listStaff(includeInactive.value)
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
onMounted(reload)

const resetForm = () => {
  Object.assign(form, { empNo: '', name: '', position: '', phone: '', schedulable: true, role: 'MEMBER', active: true })
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
    empNo: row.empNo,
    name: row.name,
    position: row.position || '',
    phone: row.phone || '',
    schedulable: row.schedulable,
    role: row.role || 'MEMBER',
    active: row.active
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
  try {
    if (editing.value) {
      await updateStaff(editId.value, {
        name: form.name,
        position: form.position,
        phone: form.phone,
        schedulable: form.schedulable,
        active: form.active
      })
      ElMessage.success('已保存')
      dialogVisible.value = false
      await reload()
    } else {
      const res = await createStaff({
        empNo: form.empNo,
        name: form.name,
        position: form.position,
        phone: form.phone,
        schedulable: form.schedulable,
        role: form.role
      })
      dialogVisible.value = false
      tempInfo.username = res.username
      tempInfo.tempPassword = res.tempPassword
      tempVisible.value = true
      await reload()
    }
  } catch {
    // 失败提示由 http 拦截器统一弹出（如 1201 工号已存在）
  } finally {
    saving.value = false
  }
}

// 删除人员：输入本人姓名确认后真删（账号、排班、值班电话、调班、日志一并删除）
const remove = async (row) => {
  try {
    await ElMessageBox.prompt(
      `删除后，${row.name} 的登录账号、全部排班（含已发布的历史月份）、值班电话、调班申请和相关操作日志将被永久删除，无法恢复。请输入该人员姓名确认：`,
      '删除人员',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
        inputValidator: (v) => v === row.name || '姓名不一致'
      }
    )
  } catch {
    return // 取消
  }
  try {
    await deleteStaff(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch {
    // 失败提示由 http 拦截器统一弹出
  }
}

// 本地交换位置后把完整顺序提交给后端，成功后重新加载
const move = async (index, offset) => {
  const target = index + offset
  if (target < 0 || target >= list.value.length) return
  const next = [...list.value]
  const tmp = next[index]
  next[index] = next[target]
  next[target] = tmp
  list.value = next
  savingOrder.value = true
  try {
    await saveStaffOrder(next.map((s) => s.id))
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    savingOrder.value = false
  }
  await reload()
}
</script>

<style scoped>
.bar { display: flex; align-items: center; }
.sp { flex: 1; }
.temp { margin: 6px 0; font-size: 14px; }
</style>
