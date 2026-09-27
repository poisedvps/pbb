import http from './http'

// 操作日志接口（后端 /api/logs，仅科长可访问）。返回体已由 http 拦截器解包，拿到的就是 data
export const listLogs = (params) => http.get('/logs', { params }) // params: { page, size, username, action } → { total, items }
