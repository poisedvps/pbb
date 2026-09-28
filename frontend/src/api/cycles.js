import http from './http'

// 排班周期模板接口（后端 /api/cycle-templates）。返回体已由 http 拦截器解包，拿到的就是 data
export const listCycleTemplates = () => http.get('/cycle-templates') // → [{ id, name, days[7], isDefault }]
export const createCycleTemplate = (data) => http.post('/cycle-templates', data) // data: { name, days[7], isDefault }
export const updateCycleTemplate = (id, data) => http.put(`/cycle-templates/${id}`, data)
export const deleteCycleTemplate = (id) => http.delete(`/cycle-templates/${id}`)
