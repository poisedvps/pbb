import http from './http'

// 排班接口（后端 /api/schedules）。返回体已由 http 拦截器解包，拿到的就是 data
export const getSchedule = (ym) => http.get(`/schedules/${ym}`) // → MonthScheduleVO
export const generateSchedule = (ym, templateId) =>
  http.post(`/schedules/${ym}/generate`, null, { params: templateId ? { templateId } : {} }) // → { generated, skippedManual }
export const saveDraft = (ym, data) => http.put(`/schedules/${ym}/draft`, data)
// data: { entries: [{ staffId, workDate, shiftCode|null, remark|null }], dutyPhones: [{ weekStart, staffId|null }] } → { entries, dutyPhones }
export const updateEntry = (ym, data) => http.put(`/schedules/${ym}/entries`, data) // data: { staffId, workDate, shiftCode, remark } → CellVO
export const publishSchedule = (ym) => http.post(`/schedules/${ym}/publish`) // → { version, count }
export const getMine = (ym) => http.get('/schedules/mine', { params: { yearMonth: ym } }) // → MineVO
