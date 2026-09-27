package com.hospital.pbb.schedule.dto;

import java.time.LocalDate;

/**
 * 一条要回写到排班表上的调班结果（任务单 M3-01）。
 *
 * <p>调班模块（swap）审批通过后用它描述"某人某天的班改成什么"，
 * 由 schedule 模块的 {@code ScheduleService.applyChanges} 落库——写排班表的只有 schedule 一处。</p>
 *
 * @param staffId   人员 id
 * @param workDate  日期，决定这次回写要锁哪一个月
 * @param shiftCode 改后的班次代号，取值见 {@code shift_type.code}
 */
public record CellChange(Long staffId, LocalDate workDate, String shiftCode) {}
