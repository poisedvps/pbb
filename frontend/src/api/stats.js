import http from './http'

// 统计报表接口（后端 /api/stats）。返回体已由 http 拦截器解包，拿到的就是 data
// from、to 形如 '2026-10-01'，含首尾两天，区间不能超过 366 天（1700/1701 由后端校验）
export const getStats = (from, to) => http.get('/stats', { params: { from, to } }) // → { from, to, rows }
