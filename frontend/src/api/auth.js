import http from './http'

// 登录相关接口。返回体已由 http 拦截器解包，拿到的就是 data
export const login = (username, password) => http.post('/auth/login', { username, password })
export const getMe = () => http.get('/auth/me')
export const logout = () => http.post('/auth/logout')
export const changePassword = (oldPassword, newPassword) =>
  http.post('/auth/change-password', { oldPassword, newPassword })
