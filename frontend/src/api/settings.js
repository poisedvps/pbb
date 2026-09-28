import http from './http'

// 系统设置接口（后端 /api/settings）。返回体已由 http 拦截器解包，拿到的就是 data
export const getDutyPhoneColor = () => http.get('/settings/duty-phone-color') // → { color }
export const updateDutyPhoneColor = (color) => http.put('/settings/duty-phone-color', { color })
