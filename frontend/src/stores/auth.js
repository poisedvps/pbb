import { defineStore } from 'pinia'
import * as authApi from '../api/auth'

const TOKEN_KEY = 'pbb_token'

// role 取值 ADMIN / MEMBER / SCREEN
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null
  }),
  getters: {
    isAdmin: (s) => s.user?.role === 'ADMIN',
    role: (s) => s.user?.role || ''
  },
  actions: {
    async login(username, password) {
      const { token, user } = await authApi.login(username, password)
      this.token = token
      localStorage.setItem(TOKEN_KEY, token)
      this.user = user
      return user
    },
    async fetchMe() {
      this.user = await authApi.getMe()
      return this.user
    },
    async logout() {
      try {
        await authApi.logout()
      } catch {
        // 后端无状态，登出失败也要清掉本地登录态
      }
      this.token = ''
      this.user = null
      localStorage.removeItem(TOKEN_KEY)
    }
  }
})
