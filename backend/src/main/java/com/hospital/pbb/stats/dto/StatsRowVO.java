package com.hospital.pbb.stats.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 统计报表的一行（一名人员），口径见设计 §5.4。
 *
 * @param staffId    人员 id
 * @param empNo      工号
 * @param name       姓名
 * @param counts     班次代号 → 天数，含全部班次（没有排班的代号为 0），顺序同班次 sort_order
 * @param offDayWork 节假日/周末上班天数：班次计工时且当天是 WEEKEND 或 HOLIDAY，调休上班日不算
 * @param totalHours 总工时：各格对应班次当前的 work_hours 之和
 */
public record StatsRowVO(Long staffId, String empNo, String name, Map<String, Integer> counts,
                         int offDayWork, BigDecimal totalHours) {}
