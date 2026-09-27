<template>
  <div class="wrap">
    <el-card class="box">
      <h2>修改密码</h2>
      <el-alert
        v-if="auth.user?.mustChangePassword"
        class="alert"
        type="warning"
        :closable="false"
        show-icon
        title="首次登录，请先修改初始密码"
      />
      <el-form ref="formRef" :model="form" :rules="rules" label-width="92px" @submit.prevent="submit">
        <el-form-item label="原密码" prop="oldPassword">
          <el-input v-model="form.oldPassword" type="password" show-password placeholder="原密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 8 位，需包含字母和数字" />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="submit">确认修改</el-button>
        </el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changePassword } from '../api/auth'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const formRef = ref()
const loading = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

// 与后端 PasswordUtil.STRONG_REGEX 保持一致
const STRONG = /^(?=.*[A-Za-z])(?=.*\d)[\x21-\x7E]{8,64}$/
const trigger = ['blur', 'change']
const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger },
    {
      validator: (_r, value, cb) =>
        cb(!value || STRONG.test(value) ? undefined : new Error('至少 8 位，需包含字母和数字')),
      trigger
    }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger },
    {
      validator: (_r, value, cb) =>
        cb(!value || value === form.newPassword ? undefined : new Error('两次输入不一致')),
      trigger
    }
  ]
}

const submit = async () => {
  if (loading.value) return
  if (!(await formRef.value.validate().catch(() => false))) return
  loading.value = true
  try {
    await changePassword(form.oldPassword, form.newPassword)
    ElMessage.success('密码已修改')
    await auth.fetchMe()
    router.push(auth.role === 'SCREEN' ? '/screen' : '/schedule')
  } catch {
    // 失败提示由 http 拦截器统一弹出（原密码错误 / 新密码不符合要求 等）
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.wrap { display: flex; justify-content: center; padding-top: 4vh; }
.box { width: 460px; }
h2 { text-align: center; margin-top: 0; }
.alert { margin-bottom: 18px; }
</style>
