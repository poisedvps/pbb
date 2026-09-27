package com.hospital.pbb.shift;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * 班次类型（设计 §4 shift_type）。
 *
 * <p>6 种班次由 {@code V1__init_schema.sql} 预置，运行期既不能新增也不能删除，
 * 科长只能改名称、时间、工时、颜色和启停状态（见 {@link ShiftTypeService#update}）。</p>
 */
@Entity
@Table(name = "shift_type")
public class ShiftType {

    /** 班次代号，前端排班单元格里存的就是它 */
    @Id
    @Column(length = 4)
    private String code;

    @Column(nullable = false, length = 16)
    private String name;

    /** 上班时间，备班/请假/休息没有时间为 null */
    @JsonFormat(pattern = "HH:mm")
    @Column(name = "start_time")
    private LocalTime startTime;

    @JsonFormat(pattern = "HH:mm")
    @Column(name = "end_time")
    private LocalTime endTime;

    /** 下班时间落在次日 */
    @Column(name = "cross_day", nullable = false)
    private boolean crossDay;

    /** 计工时数，NUMERIC(4,1)，最多 24.0 */
    @Column(name = "work_hours", nullable = false, precision = 4, scale = 1)
    private BigDecimal workHours;

    /** 是否计入工作量（请假、休息不计） */
    @Column(name = "counts_as_work", nullable = false)
    private boolean countsAsWork;

    /** 排班表格子里的填充色 */
    @Column(nullable = false, length = 16)
    private String color;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean enabled;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalTime getStartTime() {
        return startTime;
    }

    public void setStartTime(LocalTime startTime) {
        this.startTime = startTime;
    }

    public LocalTime getEndTime() {
        return endTime;
    }

    public void setEndTime(LocalTime endTime) {
        this.endTime = endTime;
    }

    public boolean isCrossDay() {
        return crossDay;
    }

    public void setCrossDay(boolean crossDay) {
        this.crossDay = crossDay;
    }

    public BigDecimal getWorkHours() {
        return workHours;
    }

    public void setWorkHours(BigDecimal workHours) {
        this.workHours = workHours;
    }

    public boolean isCountsAsWork() {
        return countsAsWork;
    }

    public void setCountsAsWork(boolean countsAsWork) {
        this.countsAsWork = countsAsWork;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        this.color = color;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}
