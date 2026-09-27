package com.hospital.pbb.schedule;

import com.hospital.pbb.holiday.HolidayRepository;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.DayVO;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.schedule.dto.StaffRowVO;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 排班月视图查询（设计 §5、§5.2）。
 *
 * <p>同一个月视图有两个数据来源：科长改的是草稿 {@code schedule_entry}，成员和大屏看的是已发布快照
 * {@code schedule_published_entry}，两条链路只有 {@code draft} 这一个开关的差别，其余（月份解析、
 * 规则日历、人员行）完全一样，所以合成一个 {@link #getMonth} 而不是两套接口。</p>
 */
@Service
public class ScheduleQueryService {

    private final ScheduleMonthRepository monthRepo;
    private final ScheduleEntryRepository entryRepo;
    private final SchedulePublishedEntryRepository publishedRepo;
    private final StaffRepository staffRepo;
    private final HolidayRepository holidayRepo;

    public ScheduleQueryService(ScheduleMonthRepository monthRepo, ScheduleEntryRepository entryRepo,
                                SchedulePublishedEntryRepository publishedRepo, StaffRepository staffRepo,
                                HolidayRepository holidayRepo) {
        this.monthRepo = monthRepo;
        this.entryRepo = entryRepo;
        this.publishedRepo = publishedRepo;
        this.staffRepo = staffRepo;
        this.holidayRepo = holidayRepo;
    }

    /**
     * 取某一月的整张排班表。
     *
     * <p>只读，且一个月视图由节假日、人员、排班三次查询拼成，放进只读事务里读，
     * 避免读到"人员已改名、排班还没跟上"的中间状态。</p>
     *
     * @param yearMonth {@code YYYY-MM}，格式不对由 {@link ScheduleMonths#parse} 抛 code=1500
     * @param draft     true 读草稿（科长），false 读已发布快照（成员、大屏）
     * @throws BizException code=1500，月份格式不合法
     */
    @Transactional(readOnly = true)
    public MonthScheduleVO getMonth(String yearMonth, boolean draft) {
        YearMonth ym = ScheduleMonths.parse(yearMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        RuleCalendar calendar = calendar(start, end);

        List<DayVO> days = new ArrayList<>(ym.lengthOfMonth());
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            days.add(new DayVO(date, date.getDayOfWeek().getValue(),
                    calendar.kindOf(date), calendar.holidayName(date)));
        }

        Map<Long, Map<String, CellVO>> cellsByStaff = draft
                ? draftCells(start, end) : publishedCells(start, end);

        List<StaffRowVO> rows = new ArrayList<>();
        for (Staff staff : staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            if (!staff.isSchedulable()) {
                continue;
            }
            Map<String, CellVO> cells = cellsByStaff.get(staff.getId());
            rows.add(new StaffRowVO(staff.getId(), staff.getEmpNo(), staff.getName(), staff.getPosition(),
                    cells != null ? cells : new LinkedHashMap<>()));
        }

        // 从没生成、从没发布过的月份在 schedule_month 里没有记录，一律按"DRAFT、0 版、从未发布"回答
        ScheduleMonth month = monthRepo.findById(yearMonth).orElse(null);
        return new MonthScheduleVO(yearMonth,
                month != null ? month.getStatus() : ScheduleStatus.DRAFT,
                month != null ? month.getVersion() : 0,
                month != null ? month.getPublishedAt() : null,
                draft, days, rows);
    }

    /**
     * {@code [start, end]} 范围内的规则日历，供本模块和 screen、stats 复用，
     * 免得每个用到"这天该不该上班"的地方各查一次节假日表。
     */
    public RuleCalendar calendar(LocalDate start, LocalDate end) {
        return new RuleCalendar(holidayRepo.findOverlapping(start, end));
    }

    /** 草稿：manual 原样带出，科长改过的格子重排规则时不再被覆盖。 */
    private Map<Long, Map<String, CellVO>> draftCells(LocalDate start, LocalDate end) {
        Map<Long, Map<String, CellVO>> cells = new HashMap<>();
        for (ScheduleEntry entry : entryRepo.findByWorkDateBetween(start, end)) {
            put(cells, entry.getStaffId(), entry.getWorkDate(),
                    new CellVO(entry.getShiftCode(), entry.isManual(), entry.getRemark()));
        }
        return cells;
    }

    /** 已发布快照：表里没有 manual 这一列，一律回 false。 */
    private Map<Long, Map<String, CellVO>> publishedCells(LocalDate start, LocalDate end) {
        Map<Long, Map<String, CellVO>> cells = new HashMap<>();
        for (SchedulePublishedEntry entry : publishedRepo.findByWorkDateBetween(start, end)) {
            put(cells, entry.getStaffId(), entry.getWorkDate(),
                    new CellVO(entry.getShiftCode(), false, entry.getRemark()));
        }
        return cells;
    }

    private static void put(Map<Long, Map<String, CellVO>> cells, Long staffId, LocalDate workDate, CellVO cell) {
        cells.computeIfAbsent(staffId, k -> new LinkedHashMap<>()).put(workDate.toString(), cell);
    }
}
