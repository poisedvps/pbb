package com.hospital.pbb.schedule.dto;

import com.hospital.pbb.schedule.ScheduleStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 某一个月的整张排班表（设计 §5.2），排班表页、大屏、导出共用这一份结构。
 *
 * @param yearMonth  月份，{@code YYYY-MM}
 * @param status     当月状态，{@code schedule_month} 里没有记录时为 DRAFT
 * @param version    发布版本号，从未发布为 0
 * @param publishedAt 最近一次发布时间，从未发布为 null
 * @param draft      true 表示返回的是草稿（科长），false 表示已发布快照（成员、大屏）
 * @param days       当月每一天，按月内日期升序
 * @param rows       参与排班的人员，按 sort_order、id 升序
 * @param cycleTemplateId 该月最近一次按规则生成所用的周期模板 id，从没生成过或模板已删除时为 null
 * @param dutyPhoneColor  值班电话整格标亮用的底色（{@code #RRGGBB}），app_setting 无记录时为默认黄色
 * @param dutyPhones  与该月有交集的各周值班电话，按 weekStart 升序，不含手机号
 */
public record MonthScheduleVO(String yearMonth, ScheduleStatus status, int version, OffsetDateTime publishedAt,
                              boolean draft, List<DayVO> days, List<StaffRowVO> rows,
                              Long cycleTemplateId, String dutyPhoneColor, List<DutyPhoneVO> dutyPhones) {}
