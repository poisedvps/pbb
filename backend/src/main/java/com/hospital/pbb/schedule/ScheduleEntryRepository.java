package com.hospital.pbb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, Long> {

    /** 按月取草稿时传整月的首末日 */
    List<ScheduleEntry> findByWorkDateBetween(LocalDate start, LocalDate end);

    /** 库里 (staff_id, work_date) 唯一，最多一条 */
    Optional<ScheduleEntry> findByStaffIdAndWorkDate(Long staffId, LocalDate workDate);

    /** 删除人员：删掉此人全部排班草稿，不限月份（任务单 M5-02） */
    @Modifying
    @Query("delete from ScheduleEntry e where e.staffId = :staffId")
    int deleteByStaffId(@Param("staffId") Long staffId);

    /** 删除账号：此人留下的“最后修改人”置空，草稿本身留着（任务单 M5-02） */
    @Modifying
    @Query("update ScheduleEntry e set e.updatedBy = null where e.updatedBy = :userId")
    int clearUpdatedBy(@Param("userId") Long userId);
}
