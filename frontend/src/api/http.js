import axios from 'axios'
import { ElMessage } from 'element-plus'

// 统一请求实例：后端返回 { code, message, data }，code = 0 为成功
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('pbb_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (resp) => {
    const body = resp.data
    if (body && body.code === 0) return body.data
    ElMessage.error(body?.message || '请求失败')
    return Promise.reject(body)
  },
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('pbb_token')
      if (location.pathname !== '/login') location.href = '/login'
    } else {
      ElMessage.error(err.response?.data?.message || '网络错误')
    }
    return Promise.reject(err)
  }
)

export default http
