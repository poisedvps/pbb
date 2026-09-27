package com.hospital.pbb.schedule;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * 一条已发布排班快照（设计 §4 schedule_published_entry）。
 *
 * <p>发布时整体复制当期草稿，成员和大屏只读这一张表，
 * 因此草稿再怎么改都不影响已展示的内容，直到下次发布。</p>
 */
@Entity
@Table(name = "schedule_published_entry")
public class SchedulePublishedEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_id", nullable = false)
    private Long staffId;

    @Column(name = "work_date", nullable = false)
    private LocalDate workDate;

    /** 班次代号，取值见 shift_type.code */
    @Column(name = "shift_code", nullable = false, length = 4)
    private String shiftCode;

    @Column(length = 200)
    private String remark;

    /** 发布时的版本号，来自 schedule_month.version */
    @Column(nullable = false)
    private int version;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getStaffId() {
        return staffId;
    }

    public void setStaffId(Long staffId) {
        this.staffId = staffId;
    }

    public LocalDate getWorkDate() {
        return workDate;
    }

    public void setWorkDate(LocalDate workDate) {
        this.workDate = workDate;
    }

    public String getShiftCode() {
        return shiftCode;
    }

    public void setShiftCode(String shiftCode) {
        this.shiftCode = shiftCode;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}
