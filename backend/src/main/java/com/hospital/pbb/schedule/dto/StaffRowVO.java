package com.hospital.pbb.schedule.dto;

import java.util.Map;

/**
 * 月视图里的一行（一名人员）。
 *
 * @param staffId  人员 id
 * @param empNo    工号
 * @param name     姓名
 * @param position 岗位
 * @param cells    该人这个月排了班的日期，key 为 {@code "2026-10-01"} 形式的日期字符串；
 *                 没排班的日期不放进 map，前端按 {@link DayVO} 逐日取值
 */
public record StaffRowVO(Long staffId, String empNo, String name, String position, Map<String, CellVO> cells) {}
