import http from './http'

// 大屏数据接口（后端 /api/screen）。返回体已由 http 拦截器解包，拿到的就是 data
export const getScreen = (ym) => http.get('/screen', { params: { yearMonth: ym } }) // → { month: MonthScheduleVO, today: TodayVO }
