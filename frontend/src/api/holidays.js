import http from './http'

// 节假日接口（后端 /api/holidays）。返回体已由 http 拦截器解包，拿到的就是 data
export const listHolidays = (year) => http.get('/holidays', { params: { year } })
export const createHoliday = (data) => http.post('/holidays', data) // data: { name, startDate, endDate, type, remark }
export const updateHoliday = (id, data) => http.put(`/holidays/${id}`, data)
export const deleteHoliday = (id) => http.delete(`/holidays/${id}`)
export const copyHolidays = (fromYear, toYear) =>
  http.post('/holidays/copy', null, { params: { fromYear, toYear } }) // → 条数
