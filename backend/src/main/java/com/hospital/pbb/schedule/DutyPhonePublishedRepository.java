package com.hospital.pbb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface DutyPhonePublishedRepository extends JpaRepository<DutyPhonePublished, LocalDate> {

    /** 成员/大屏/统计取一段日期范围内的值班电话快照，按周升序（区间两端不要求是周一） */
    List<DutyPhonePublished> findByWeekStartBetweenOrderByWeekStartAsc(LocalDate from, LocalDate to);

    /** 发布前清空；调用方须持有相关月份的 advisory lock */
    @Modifying
    @Query("delete from DutyPhonePublished d where d.weekStart between :from and :to")
    int deleteByWeekStartRange(@Param("from") LocalDate from, @Param("to") LocalDate to);

    /** 删除人员：删掉此人负责的全部值班电话已发布快照周，不限月份（任务单 M5-02） */
    @Modifying
    @Query("delete from DutyPhonePublished d where d.staffId = :staffId")
    int deleteByStaffId(@Param("staffId") Long staffId);
}
