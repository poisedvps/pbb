<template>
  <div class="wrap">
    <el-card class="box">
      <h2>信息科排班系统</h2>
      <el-form @submit.prevent>
        <el-form-item><el-input v-model="form.username" placeholder="用户名（工号）" /></el-form-item>
        <el-form-item><el-input v-model="form.password" type="password" placeholder="密码" show-password /></el-form-item>
        <el-button type="primary" style="width: 100%" @click="$router.push('/schedule')">登 录</el-button>
      </el-form>
      <p class="note">登录功能待实现（M1）· 仅限医院内网访问</p>
    </el-card>
  </div>
</template>

<script setup>
import { reactive } from 'vue'

const form = reactive({ username: '', password: '' })
</script>

<style scoped>
.wrap { display: flex; justify-content: center; padding-top: 12vh; }
.box { width: 360px; }
h2 { text-align: center; margin-top: 0; }
.note { text-align: center; font-size: 12px; color: #6b7280; }
</style>
