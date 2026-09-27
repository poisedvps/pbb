import http from './http'

// 人员管理接口（后端 /api/staff）。返回体已由 http 拦截器解包，拿到的就是 data
export const listStaff = (includeInactive = false) => http.get('/staff', { params: { includeInactive } })
export const createStaff = (data) => http.post('/staff', data) // → { staff, username, tempPassword }
export const updateStaff = (id, data) => http.put(`/staff/${id}`, data)
export const saveStaffOrder = (ids) => http.put('/staff/order', ids)
