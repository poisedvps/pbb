import http from './http'

// 账号管理接口（仅科长可用）。返回体已由 http 拦截器解包，拿到的就是 data
export const listUsers = () => http.get('/users')
export const createScreenUser = (username, displayName) =>
  http.post('/users', { username, displayName, role: 'SCREEN' }) // → { tempPassword }
export const resetPassword = (id) => http.post(`/users/${id}/reset-password`) // → { tempPassword }
export const unlockUser = (id) => http.post(`/users/${id}/unlock`)
export const disableUser = (id) => http.post(`/users/${id}/disable`)
export const enableUser = (id) => http.post(`/users/${id}/enable`)
