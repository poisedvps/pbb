import http from './http'

// 班次设置接口（后端 /api/shift-types）。返回体已由 http 拦截器解包，拿到的就是 data
export const listShiftTypes = () => http.get('/shift-types')
export const updateShiftType = (code, data) => http.put(`/shift-types/${code}`, data)
