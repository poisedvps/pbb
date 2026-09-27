package com.hospital.pbb.screen;

import com.hospital.pbb.schedule.SchedulePublishedEntry;
import com.hospital.pbb.schedule.SchedulePublishedEntryRepository;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.screen.dto.ScreenVO;
import com.hospital.pbb.screen.dto.TodayVO;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大屏数据（设计 §5、任务单 M2-07）：整月已发布排班 + 今日概况。
 *
 * <p>大屏是公开展示的一块屏，只读发布出去的快照，草稿一律不上屏，
 * 所以月份数据直接复用 {@link ScheduleQueryService#getMonth} 的 {@code draft=false} 那条链路，
 * 本模块不碰 {@code schedule} 包里的任何写操作。</p>
 */
@Service
public class ScreenService {

    /** 今日概况只统计这四类班次，X（休息）、B（备班）不上大屏 */
    private static final String SHIFT_DAY = "D";
    private static final String SHIFT_NIGHT = "N";
    private static final String SHIFT_DUTY = "Z";
    private static final String SHIFT_LEAVE = "L";

    private final ScheduleQueryService query;
    private final SchedulePublishedEntryRepository publishedRepo;
    private final StaffRepository staffRepo;
    private final Clock clock;

    public ScreenService(ScheduleQueryService query, SchedulePublishedEntryRepository publishedRepo,
                         StaffRepository staffRepo, Clock clock) {
        this.query = query;
        this.publishedRepo = publishedRepo;
        this.staffRepo = staffRepo;
        this.clock = clock;
    }

    /**
     * 大屏一次刷新取到的全部数据。
     *
     * <p>整月和今日两次查询放在同一个只读事务里，避免大屏上"月历已经翻到 11 月、今日概况还停在 10 月"。</p>
     *
     * @param yearMonth {@code YYYY-MM}，为 null 或空串时取当前月；格式不对由 {@code getMonth} 抛 code=1500
     */
    @Transactional(readOnly = true)
    public ScreenVO get(String yearMonth) {
        LocalDate today = LocalDate.now(clock);
        String ym = yearMonth == null || yearMonth.isBlank() ? YearMonth.from(today).toString() : yearMonth;
        MonthScheduleVO month = query.getMonth(ym, false);
        return new ScreenVO(month, todayOverview(today));
    }

    /**
     * 今日概况。姓名和顺序都取当前有效人员列表，而不是排班数据自带的顺序——
     * 某人今天没排班也不会打乱后面几位的次序。
     */
    private TodayVO todayOverview(LocalDate today) {
        Map<Long, String> shiftCodeByStaff = new HashMap<>();
        for (SchedulePublishedEntry entry : publishedRepo.findByWorkDateBetween(today, today)) {
            shiftCodeByStaff.put(entry.getStaffId(), entry.getShiftCode());
        }

        int dayCount = 0;
        List<String> night = new ArrayList<>();
        List<String> duty = new ArrayList<>();
        List<String> leave = new ArrayList<>();
        for (Staff staff : staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            switch (shiftCodeByStaff.getOrDefault(staff.getId(), "")) {
                case SHIFT_DAY -> dayCount++;
                case SHIFT_NIGHT -> night.add(staff.getName());
                case SHIFT_DUTY -> duty.add(staff.getName());
                case SHIFT_LEAVE -> leave.add(staff.getName());
                default -> {
                }
            }
        }
        return new TodayVO(today, dayCount, night, duty, leave);
    }
}
