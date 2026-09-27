<template>
  <div class="wrap">
    <el-card class="box">
      <h2>信息科排班系统</h2>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input
            v-model="form.username"
            placeholder="用户名（工号）"
            :disabled="loading"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-form-item>
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码"
            show-password
            :disabled="loading"
            @keyup.enter="submit"
          />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="submit">登 录</el-button>
      </el-form>
      <p class="note">仅限医院内网访问</p>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()
const form = reactive({ username: '', password: '' })
const loading = ref(false)

const submit = async () => {
  if (loading.value) return
  loading.value = true
  try {
    const user = await auth.login(form.username, form.password)
    if (user.role === 'SCREEN') router.push('/screen')
    else if (user.mustChangePassword) router.push('/password')
    else router.push('/schedule')
  } catch {
    // 失败提示由 http 拦截器统一弹出
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.wrap { display: flex; justify-content: center; padding-top: 12vh; }
.box { width: 360px; }
h2 { text-align: center; margin-top: 0; }
.note { text-align: center; font-size: 12px; color: #6b7280; }
</style>
