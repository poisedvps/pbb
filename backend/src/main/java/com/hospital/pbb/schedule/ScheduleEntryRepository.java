package com.hospital.pbb.schedule;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ScheduleEntryRepository extends JpaRepository<ScheduleEntry, Long> {

    /** 按月取草稿时传整月的首末日 */
    List<ScheduleEntry> findByWorkDateBetween(LocalDate start, LocalDate end);

    /** 库里 (staff_id, work_date) 唯一，最多一条 */
    Optional<ScheduleEntry> findByStaffIdAndWorkDate(Long staffId, LocalDate workDate);
}
