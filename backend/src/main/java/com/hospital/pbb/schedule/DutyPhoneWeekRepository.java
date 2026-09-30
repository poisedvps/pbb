package com.hospital.pbb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DutyPhoneWeekRepository extends JpaRepository<DutyPhoneWeek, LocalDate> {

    /** 一段日期范围内的值班电话草稿，按周升序（区间两端不要求是周一） */
    List<DutyPhoneWeek> findByWeekStartBetweenOrderByWeekStartAsc(LocalDate from, LocalDate to);

    /** 删除人员：删掉此人负责的全部值班电话草稿周，不限月份（任务单 M5-02） */
    @Modifying
    @Query("delete from DutyPhoneWeek d where d.staffId = :staffId")
    int deleteByStaffId(@Param("staffId") Long staffId);

    /** 删除账号：此人留下的“最近修改人”置空，草稿本身留着（任务单 M5-02） */
    @Modifying
    @Query("update DutyPhoneWeek d set d.updatedBy = null where d.updatedBy = :userId")
    int clearUpdatedBy(@Param("userId") Long userId);
}
