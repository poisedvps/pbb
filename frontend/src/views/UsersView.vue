<template>
  <el-card>
    <template #header>
      <div class="card-head">
        <b>账号管理</b>
        <el-button type="primary" @click="openCreate">新增大屏账号</el-button>
      </div>
    </template>

    <p class="note">
      系统自带账号密码登录。新增人员时自动创建账号（用户名 = 工号），初始密码首次登录强制修改。
    </p>

    <el-table v-loading="loading" :data="users" border>
      <el-table-column prop="username" label="用户名" min-width="130" />
      <el-table-column prop="displayName" label="姓名" min-width="110" />
      <el-table-column label="角色" width="90">
        <template #default="{ row }">{{ ROLE_TEXT[row.role] || row.role }}</template>
      </el-table-column>
      <el-table-column label="状态" width="96">
        <template #default="{ row }">
          <el-tag :type="statusOf(row).type" size="small">{{ statusOf(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="最近登录" width="150">
        <template #default="{ row }">{{ formatLastLogin(row.lastLoginAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" min-width="230">
        <template #default="{ row }">
          <el-button link type="primary" :disabled="busy" @click="submitReset(row)">重置密码</el-button>
          <el-button v-if="row.locked" link type="warning" :disabled="busy" @click="submitUnlock(row)">解锁</el-button>
          <el-button v-if="row.enabled" link type="danger" :disabled="busy" @click="submitDisable(row)">停用</el-button>
          <el-button v-else link type="success" :disabled="busy" @click="submitEnable(row)">启用</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h3 class="sub-title">角色权限</h3>
    <el-table :data="ROLE_PERMISSIONS" border>
      <el-table-column v-for="col in PERMISSION_COLS" :key="col.prop" :prop="col.prop" :label="col.label" />
    </el-table>
  </el-card>

  <!-- 新增大屏账号 -->
  <el-dialog v-model="createVisible" title="新增大屏账号" width="440px" @closed="resetCreateForm">
    <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="90px">
      <el-form-item label="用户名" prop="username">
        <el-input v-model="createForm.username" maxlength="32" placeholder="3-32 位字母、数字或下划线" @keyup.enter="submitCreate" />
      </el-form-item>
      <el-form-item label="显示名称" prop="displayName">
        <el-input v-model="createForm.displayName" maxlength="32" placeholder="如：值班大屏 2" @keyup.enter="submitCreate" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button :disabled="creating" @click="createVisible = false">取 消</el-button>
      <el-button type="primary" :loading="creating" @click="submitCreate">创 建</el-button>
    </template>
  </el-dialog>

  <!-- 临时密码：只在这里出现一次，关闭后无法再次查看 -->
  <el-dialog v-model="tempVisible" :title="tempTitle" width="440px" @closed="tempPassword = ''">
    <p class="temp-label">{{ tempUser }}</p>
    <p class="temp-value">{{ tempPassword }}</p>
    <el-alert type="warning" :closable="false" show-icon :title="tempTip" />
    <template #footer>
      <el-button @click="copyTemp">复 制</el-button>
      <el-button type="primary" @click="tempVisible = false">我已记录</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as usersApi from '../api/users'

const ROLE_TEXT = { ADMIN: '科长', MEMBER: '成员', SCREEN: '大屏' }

// 角色权限表（静态文案，与原型「账号管理」页一致）
const PERMISSION_COLS = [
  { prop: 'role', label: '角色' },
  { prop: 'view', label: '查看排班' },
  { prop: 'edit', label: '编辑排班' },
  { prop: 'publish', label: '发布' },
  { prop: 'swap', label: '调班' },
  { prop: 'setting', label: '基础设置 / 账号' }
]
const ROLE_PERMISSIONS = [
  { role: '科长（管理员）', view: '全部（含草稿）', edit: '✔', publish: '✔', swap: '发起 + 审批', setting: '✔' },
  { role: '普通成员', view: '仅已发布', edit: '✘', publish: '✘', swap: '发起 + 确认', setting: '✘' },
  { role: '大屏账号', view: '仅已发布，只读大屏页', edit: '✘', publish: '✘', swap: '✘', setting: '✘' }
]

const users = ref([])
const loading = ref(false)
const busy = ref(false)

// 状态优先级：停用 > 已锁定 > 正常
const statusOf = (row) => {
  if (!row.enabled) return { text: '停用', type: 'danger' }
  if (row.locked) return { text: '已锁定', type: 'warning' }
  return { text: '正常', type: 'success' }
}

const pad = (n) => String(n).padStart(2, '0')

// 后端返回 ISO 或 'yyyy-MM-dd HH:mm:ss'，统一裁成 'YYYY-MM-DD HH:mm'
const formatLastLogin = (value) => {
  if (!value) return '—'
  const hit = String(value).match(/^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})/)
  if (hit) return `${hit[1]}-${hit[2]}-${hit[3]} ${hit[4]}:${hit[5]}`
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

const load = async () => {
  loading.value = true
  try {
    users.value = await usersApi.listUsers()
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
onMounted(load)

// 临时密码弹窗
const tempVisible = ref(false)
const tempTitle = ref('临时密码')
const tempUser = ref('')
const tempRole = ref('')
const tempPassword = ref('')
// SCREEN 账号后端不落 mustChangePassword（UserAdminService.java:67 / :92），
// 只有会被真人登录的账号才提示首次登录必须改密
const TIP_SCREEN = '该密码只显示一次，关闭后无法再次查看，请立即复制并妥善保存。'
const TIP_STAFF = '该密码只显示一次，请立即复制并交给本人，首次登录后需修改。'
const tempTip = computed(() => (tempRole.value === 'SCREEN' ? TIP_SCREEN : TIP_STAFF))
const showTempPassword = (title, username, role, password) => {
  tempTitle.value = title
  tempUser.value = `账号：${username}`
  tempRole.value = role
  tempPassword.value = password
  tempVisible.value = true
}

// 内网 http 下 clipboard 不可用，退回 execCommand；它返回 false 代表真没复制成功，不能当成成功
const execCommandCopy = (text) => {
  const input = document.createElement('textarea')
  input.value = text
  input.setAttribute('readonly', '')
  input.style.position = 'fixed'
  input.style.top = '-9999px'
  document.body.appendChild(input)
  input.select()
  try {
    return document.execCommand('copy')
  } finally {
    input.remove() // 抛异常也要清掉，不把明文密码留在页面上
  }
}

const copyTemp = async () => {
  if (!tempPassword.value) return
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(tempPassword.value)
    } else if (!execCommandCopy(tempPassword.value)) {
      throw new Error('execCommand copy returned false')
    }
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动选中复制')
  }
}

// 请求成功后统一重新加载列表，返回 { data }；失败返回 null（提示由拦截器弹出）
const run = async (action) => {
  if (busy.value) return null
  busy.value = true
  try {
    const data = await action()
    await load()
    return { data }
  } catch {
    return null
  } finally {
    busy.value = false
  }
}

const confirm = (message, title) =>
  ElMessageBox.confirm(message, title, { type: 'warning', confirmButtonText: '确 定', cancelButtonText: '取 消' })
    .then(() => true)
    .catch(() => false)

const submitReset = async (row) => {
  if (!(await confirm(`确定重置「${row.displayName}」的密码？`, '重置密码'))) return
  const done = await run(() => usersApi.resetPassword(row.id))
  if (done) showTempPassword('新密码已生成', row.username, row.role, done.data.tempPassword)
}

const submitUnlock = async (row) => {
  if (await run(() => usersApi.unlockUser(row.id))) ElMessage.success('已解锁')
}

const submitDisable = async (row) => {
  if (!(await confirm(`确定停用「${row.displayName}」？停用后该账号无法登录。`, '停用账号'))) return
  if (await run(() => usersApi.disableUser(row.id))) ElMessage.success('已停用')
}

const submitEnable = async (row) => {
  if (await run(() => usersApi.enableUser(row.id))) ElMessage.success('已启用')
}

// 新增大屏账号
const createVisible = ref(false)
const creating = ref(false)
const createFormRef = ref()
const createForm = reactive({ username: '', displayName: '' })
const createRules = {
  username: [
    { required: true, message: '请填写用户名', trigger: 'blur' },
    { pattern: /^[A-Za-z0-9_]{3,32}$/, message: '3-32 位字母、数字或下划线', trigger: 'blur' }
  ],
  displayName: [{ required: true, message: '请填写显示名称', trigger: 'blur' }]
}
const resetCreateForm = () => {
  createForm.username = ''
  createForm.displayName = ''
  createFormRef.value?.clearValidate()
}
const openCreate = () => {
  createVisible.value = true
}
const submitCreate = async () => {
  if (creating.value || !(await createFormRef.value.validate().catch(() => false))) return
  creating.value = true
  try {
    const data = await usersApi.createScreenUser(createForm.username.trim(), createForm.displayName.trim())
    const username = createForm.username.trim()
    createVisible.value = false
    ElMessage.success('账号已创建')
    showTempPassword('初始密码', username, 'SCREEN', data.tempPassword)
    await load()
  } catch {
    // 失败提示由 http 拦截器统一弹出，弹窗保留以便修改
  } finally {
    creating.value = false
  }
}
</script>

<style scoped>
.card-head { display: flex; align-items: center; justify-content: space-between; }
.note { margin: 0 0 12px; font-size: 12px; color: #6b7280; }
.sub-title { font-size: 15px; margin: 18px 0 8px; }
.temp-label { margin: 0 0 6px; font-size: 12px; color: #6b7280; }
.temp-value {
  margin: 0 0 12px;
  padding: 8px 12px;
  font-family: Menlo, Consolas, monospace;
  font-size: 18px;
  letter-spacing: 1px;
  background: #f5f7fa;
  border-radius: 4px;
  user-select: all;
}
</style>
