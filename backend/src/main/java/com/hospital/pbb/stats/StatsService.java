package com.hospital.pbb.stats;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.schedule.RuleCalendar;
import com.hospital.pbb.schedule.SchedulePublishedEntry;
import com.hospital.pbb.schedule.SchedulePublishedEntryRepository;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import com.hospital.pbb.stats.dto.StatsRowVO;
import com.hospital.pbb.stats.dto.StatsVO;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 统计报表（设计 §5.4，任务单 M3-06）。
 *
 * <p>只统计已发布快照 {@code schedule_published_entry}——草稿是科长没定下来的东西，
 * 进了报表就会和成员看到的对不上。节假日/周末的判断复用
 * {@link ScheduleQueryService#calendar}，避免"这天算不算上班"在统计里再写一遍。</p>
 */
@Service
public class StatsService {

    /** 统计区间上限（含首尾两端），一次跨年太长，报表也没人看 */
    private static final long MAX_RANGE_DAYS = 366;

    private final SchedulePublishedEntryRepository publishedRepo;
    private final StaffRepository staffRepo;
    private final ShiftTypeRepository shiftRepo;
    private final ScheduleQueryService query;

    public StatsService(SchedulePublishedEntryRepository publishedRepo, StaffRepository staffRepo,
                        ShiftTypeRepository shiftRepo, ScheduleQueryService query) {
        this.publishedRepo = publishedRepo;
        this.staffRepo = staffRepo;
        this.shiftRepo = shiftRepo;
        this.query = query;
    }

    /**
     * 区间统计。
     *
     * <p>整份报表由快照、人员、班次、节假日四次查询拼成，放进只读事务里读，
     * 免得科长刚好在改班次工时时读出"天数对、工时不对"的半截结果。</p>
     *
     * @param from 开始日期（含）
     * @param to   结束日期（含）
     * @param me   当前登录账号：科长看全部可排班人员，成员只看自己
     * @throws BizException code=1700 开始日期晚于结束日期；code=1701 区间超过 {@value #MAX_RANGE_DAYS} 天
     */
    @Transactional(readOnly = true)
    public StatsVO stats(LocalDate from, LocalDate to, AuthUser me) {
        if (from.isAfter(to)) {
            throw new BizException(1700, "开始日期不能晚于结束日期");
        }
        if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_RANGE_DAYS) {
            throw new BizException(1701, "统计范围不能超过 366 天");
        }

        Map<String, ShiftType> shifts = shiftByCode();
        RuleCalendar calendar = query.calendar(from, to);
        Map<Long, List<SchedulePublishedEntry>> entriesByStaff = entriesByStaff(from, to);

        List<StatsRowVO> rows = new ArrayList<>();
        for (Staff staff : staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            if (!staff.isSchedulable() || !visibleTo(staff, me)) {
                continue;
            }
            rows.add(rowOf(staff, shifts, calendar,
                    entriesByStaff.getOrDefault(staff.getId(), List.of())));
        }
        return new StatsVO(from, to, rows);
    }

    /**
     * 导出表头（任务单 M3-08）：工号、姓名、各班次名称（顺序同班次 sort_order）、节假日/周末上班、总工时。
     *
     * <p>列顺序以 {@code shift_type} 为准，不是以某一行的 counts 为准：表头要永远和统计页的列
     * 一一对应，不能因为某个人一个班没排就少一列。</p>
     */
    @Transactional(readOnly = true)
    public List<String> exportHeaders() {
        List<ShiftType> shifts = shiftRepo.findAllByOrderBySortOrderAsc();
        List<String> headers = new ArrayList<>(shifts.size() + 4);
        headers.add("工号");
        headers.add("姓名");
        for (ShiftType shift : shifts) {
            headers.add(shift.getName());
        }
        headers.add("节假日/周末上班");
        headers.add("总工时");
        return headers;
    }

    /**
     * 把 {@link #stats} 的结果摊平成导出用的行，列顺序与 {@link #exportHeaders()} 完全一致；
     * 各班次天数、节假日/周末上班是 {@code Integer}，总工时是 {@code BigDecimal}，
     * 交给 {@link com.hospital.pbb.common.ExcelWriter} 后都是能求和的数值格。
     *
     * <p>counts 里查不到对应班次的残留代号（{@code shift_type} 已删）在这里被丢掉——
     * 没有表头的列真写出来反而会和表头错位。</p>
     */
    @Transactional(readOnly = true)
    public List<List<Object>> exportRows(StatsVO vo) {
        List<String> codes = shiftCodes();
        List<List<Object>> rows = new ArrayList<>(vo.rows().size());
        for (StatsRowVO row : vo.rows()) {
            List<Object> cells = new ArrayList<>(codes.size() + 4);
            cells.add(row.empNo());
            cells.add(row.name());
            for (String code : codes) {
                cells.add(row.counts().get(code));
            }
            cells.add(row.offDayWork());
            cells.add(row.totalHours());
            rows.add(cells);
        }
        return rows;
    }

    /** 成员只能看自己那一行；账号没关联人员（科长之外的账号）时一行都没有。 */
    private static boolean visibleTo(Staff staff, AuthUser me) {
        if (me.role() == Role.ADMIN) {
            return true;
        }
        return Objects.equals(me.staffId(), staff.getId());
    }

    /** 一行 = 全班次天数（顺序按班次 sort_order）+ 节假日/周末上班天数 + 总工时。 */
    private static StatsRowVO rowOf(Staff staff, Map<String, ShiftType> shifts, RuleCalendar calendar,
                                    List<SchedulePublishedEntry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String code : shifts.keySet()) {
            counts.put(code, 0);
        }
        int offDayWork = 0;
        BigDecimal totalHours = BigDecimal.ZERO;
        for (SchedulePublishedEntry entry : entries) {
            String code = entry.getShiftCode();
            counts.merge(code, 1, Integer::sum);
            ShiftType shift = shifts.get(code);
            if (shift == null) {
                // 代号在 shift_type 里查不到（数据残留）：天数照计，工时按 0，不能让整张报表报错
                continue;
            }
            if (shift.isCountsAsWork() && calendar.isOffDay(entry.getWorkDate())) {
                offDayWork++;
            }
            totalHours = totalHours.add(shift.getWorkHours() == null ? BigDecimal.ZERO : shift.getWorkHours());
        }
        return new StatsRowVO(staff.getId(), staff.getEmpNo(), staff.getName(), counts, offDayWork, totalHours);
    }

    /** 快照按人分组；区间内一次查完，不按人逐个查（人数 × 天数会打出很多条 SQL）。 */
    private Map<Long, List<SchedulePublishedEntry>> entriesByStaff(LocalDate from, LocalDate to) {
        Map<Long, List<SchedulePublishedEntry>> entries = new HashMap<>();
        for (SchedulePublishedEntry entry : publishedRepo.findByWorkDateBetween(from, to)) {
            entries.computeIfAbsent(entry.getStaffId(), k -> new ArrayList<>()).add(entry);
        }
        return entries;
    }

    /** 班次固定几条，一次取出建索引并保留 sort_order 顺序（counts 的列顺序就是它）。 */
    private Map<String, ShiftType> shiftByCode() {
        Map<String, ShiftType> shifts = new LinkedHashMap<>();
        for (ShiftType shift : shiftRepo.findAllByOrderBySortOrderAsc()) {
            shifts.put(shift.getCode(), shift);
        }
        return shifts;
    }

    /** 班次代号，按 sort_order 升序；导出的班次列顺序、以及取哪几列 counts 都听它。 */
    private List<String> shiftCodes() {
        List<String> codes = new ArrayList<>();
        for (ShiftType shift : shiftRepo.findAllByOrderBySortOrderAsc()) {
            codes.add(shift.getCode());
        }
        return codes;
    }
}
