package com.hospital.pbb.schedule.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * "我的排班"页的某一月（设计 §5）。
 *
 * <p>成员只看已发布快照，所以这里没有 draft / status / version，只有一个 {@code published} 布尔：
 * false 时前端提示"本月排班尚未发布"。</p>
 *
 * @param yearMonth  {@code YYYY-MM}
 * @param published  该月是否已发布（schedule_month 存在且 version &gt; 0）
 * @param days       整月每一天，未排班的天 shiftCode 为 null
 * @param counts     班次代号 → 天数，只含本月出现过的代号，按本月出现顺序排列
 * @param workHours  本月工时合计，不计工时的班次（休息、请假）按 0 计
 * @param next       今天起 60 天内第一个计工时的已发布班次，没有则为 null
 */
public record MineVO(String yearMonth, boolean published, List<MineDayVO> days, Map<String, Integer> counts,
                     BigDecimal workHours, MineDayVO next) {}
