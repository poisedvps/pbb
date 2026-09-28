package com.hospital.pbb.schedule;

import com.hospital.pbb.holiday.HolidayRepository;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.DayVO;
import com.hospital.pbb.schedule.dto.MineDayVO;
import com.hospital.pbb.schedule.dto.MineVO;
import com.hospital.pbb.schedule.dto.MonthScheduleVO;
import com.hospital.pbb.schedule.dto.StaffRowVO;
import com.hospital.pbb.shift.AppSettingRepository;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 排班月视图查询（设计 §5、§5.2）。
 *
 * <p>同一个月视图有两个数据来源：科长改的是草稿 {@code schedule_entry}，成员和大屏看的是已发布快照
 * {@code schedule_published_entry}，两条链路只有 {@code draft} 这一个开关的差别，其余（月份解析、
 * 规则日历、人员行）完全一样，所以合成一个 {@link #getMonth} 而不是两套接口。</p>
 */
@Service
public class ScheduleQueryService {

    /** "下一个班次"往后找的天数上限，超出就不显示了（原型上只显示"下次班"一行） */
    private static final int NEXT_LOOKAHEAD_DAYS = 60;

    private final ScheduleMonthRepository monthRepo;
    private final ScheduleEntryRepository entryRepo;
    private final SchedulePublishedEntryRepository publishedRepo;
    private final StaffRepository staffRepo;
    private final HolidayRepository holidayRepo;
    private final ShiftTypeRepository shiftRepo;
    private final Clock clock;
    /** 下面三个仓库从 M4-06（月视图返回值班电话与底色）起使用，本单只注入不使用 */
    private final DutyPhoneWeekRepository dutyRepo;
    private final DutyPhonePublishedRepository dutyPublishedRepo;
    private final AppSettingRepository settingRepo;

    public ScheduleQueryService(ScheduleMonthRepository monthRepo, ScheduleEntryRepository entryRepo,
                                SchedulePublishedEntryRepository publishedRepo, StaffRepository staffRepo,
                                HolidayRepository holidayRepo, ShiftTypeRepository shiftRepo, Clock clock,
                                DutyPhoneWeekRepository dutyRepo, DutyPhonePublishedRepository dutyPublishedRepo,
                                AppSettingRepository settingRepo) {
        this.monthRepo = monthRepo;
        this.entryRepo = entryRepo;
        this.publishedRepo = publishedRepo;
        this.staffRepo = staffRepo;
        this.holidayRepo = holidayRepo;
        this.shiftRepo = shiftRepo;
        this.clock = clock;
        this.dutyRepo = dutyRepo;
        this.dutyPublishedRepo = dutyPublishedRepo;
        this.settingRepo = settingRepo;
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

    /**
     * 本人某月的已发布排班（设计 §5 {@code /schedules/mine}）。
     *
     * <p>成员只看已发布快照，草稿与自己无关，所以这里只查 {@code schedule_published_entry}；
     * 是否"已发布"以 {@code schedule_month.version} 为准，比快照表有没有行可靠——
     * 整月本来就全是休息日时快照也可能一条都没有。</p>
     *
     * @param staffId   本人对应的人员 id，账号未关联人员（科长、大屏账号）时为 null，
     *                  此时整月日历照常返回，只是每一格都没有班次
     * @param yearMonth {@code YYYY-MM}，格式不对由 {@link ScheduleMonths#parse} 抛 code=1500
     * @throws BizException code=1500，月份格式不合法
     */
    @Transactional(readOnly = true)
    public MineVO mine(Long staffId, String yearMonth) {
        YearMonth ym = ScheduleMonths.parse(yearMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        RuleCalendar calendar = calendar(start, end);
        Map<String, ShiftType> shifts = shiftByCode();
        Map<LocalDate, String> codeByDate = publishedShiftCodes(staffId, start, end);

        List<MineDayVO> days = new ArrayList<>(ym.lengthOfMonth());
        // counts 按本月出现顺序统计，前端按这个顺序排各班次的图例
        Map<String, Integer> counts = new LinkedHashMap<>();
        BigDecimal workHours = BigDecimal.ZERO;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            String shiftCode = codeByDate.get(date);
            days.add(new MineDayVO(date, date.getDayOfWeek().getValue(),
                    calendar.kindOf(date), calendar.holidayName(date), shiftCode));
            if (shiftCode == null) {
                continue;
            }
            counts.merge(shiftCode, 1, Integer::sum);
            workHours = workHours.add(workHoursOf(shifts.get(shiftCode)));
        }

        ScheduleMonth month = monthRepo.findById(yearMonth).orElse(null);
        return new MineVO(yearMonth, month != null && month.getVersion() > 0, days, counts, workHours,
                nextShift(staffId, shifts));
    }

    /**
     * 某人某天的已发布班次代码（任务单 M3-01，给调班模块做只读校验）。
     *
     * <p>调班只能换“成员已经看到的那个班”，所以取的是已发布快照而不是草稿；
     * 库里 {@code (staff_id, work_date)} 唯一，最多一条。没排到班（或还没发布）时返回空。</p>
     *
     * @param staffId 人员 id
     * @param date    日期
     * @return 已发布的班次代号，无已发布记录时 {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<String> publishedShift(Long staffId, LocalDate date) {
        return publishedRepo.findByStaffIdAndWorkDate(staffId, date).map(SchedulePublishedEntry::getShiftCode);
    }

    /**
     * 今天起 {@value #NEXT_LOOKAHEAD_DAYS} 天内第一个计工时的已发布班次。
     *
     * <p>休息、请假虽然也是排班，但不是"下次班"，所以按 {@code counts_as_work} 跳过；
     * 查询已按日期升序，第一条满足条件的就是答案。</p>
     */
    private MineDayVO nextShift(Long staffId, Map<String, ShiftType> shifts) {
        if (staffId == null) {
            return null;
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate until = today.plusDays(NEXT_LOOKAHEAD_DAYS);
        RuleCalendar calendar = calendar(today, until);
        for (SchedulePublishedEntry entry : publishedRepo
                .findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(staffId, today, until)) {
            ShiftType shift = shifts.get(entry.getShiftCode());
            if (shift == null || !shift.isCountsAsWork()) {
                continue;
            }
            LocalDate date = entry.getWorkDate();
            return new MineDayVO(date, date.getDayOfWeek().getValue(), calendar.kindOf(date),
                    calendar.holidayName(date), entry.getShiftCode());
        }
        return null;
    }

    /** 本人 {@code [start, end]} 的已发布班次：workDate → shiftCode；未关联人员时空表。 */
    private Map<LocalDate, String> publishedShiftCodes(Long staffId, LocalDate start, LocalDate end) {
        Map<LocalDate, String> codes = new HashMap<>();
        if (staffId == null) {
            return codes;
        }
        for (SchedulePublishedEntry entry : publishedRepo
                .findByStaffIdAndWorkDateBetweenOrderByWorkDateAsc(staffId, start, end)) {
            codes.put(entry.getWorkDate(), entry.getShiftCode());
        }
        return codes;
    }

    /** 班次总共 6 条，一次取出建索引，免得逐格 findById。 */
    private Map<String, ShiftType> shiftByCode() {
        Map<String, ShiftType> shifts = new HashMap<>();
        for (ShiftType shift : shiftRepo.findAllByOrderBySortOrderAsc()) {
            shifts.put(shift.getCode(), shift);
        }
        return shifts;
    }

    /** 班次查不到（代号被改、数据残留）按 0 工时计，不能让整页统计报错。 */
    private static BigDecimal workHoursOf(ShiftType shift) {
        return shift == null || shift.getWorkHours() == null ? BigDecimal.ZERO : shift.getWorkHours();
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
