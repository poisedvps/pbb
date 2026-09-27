import { defineStore } from 'pinia'

// 占位：登录逻辑由 M1 任务单实现。role 取值 ADMIN / MEMBER / SCREEN
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('pbb_token') || '',
    user: null
  }),
  getters: {
    isAdmin: (s) => s.user?.role === 'ADMIN'
  }
})
