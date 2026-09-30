package com.hospital.pbb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface SchedulePublishedEntryRepository extends JpaRepository<SchedulePublishedEntry, Long> {

    /** 大屏/成员按月查看时传整月的首末日 */
    List<SchedulePublishedEntry> findByWorkDateBetween(LocalDate start, LocalDate end);

    /** “我的排班”按日期升序展示 */
    List<SchedulePublishedEntry> findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(Long staffId, LocalDate start, LocalDate end);

    /** 库里 (staff_id, work_date) 唯一，最多一条 */
    Optional<SchedulePublishedEntry> findByStaffIdAndWorkDate(Long staffId, LocalDate workDate);

    /** 发布前清空当期快照，随后整体复制草稿；调用方须持有当月 advisory lock */
    @Modifying
    @Query("delete from SchedulePublishedEntry e where e.workDate between :start and :end")
    int deleteByWorkDateRange(@Param("start") LocalDate start, @Param("end") LocalDate end);

    /** 删除人员：删掉此人全部已发布排班快照，不限月份（任务单 M5-02） */
    @Modifying
    @Query("delete from SchedulePublishedEntry e where e.staffId = :staffId")
    int deleteByStaffId(@Param("staffId") Long staffId);
}
