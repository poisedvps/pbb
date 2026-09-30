package com.hospital.pbb.schedule;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.cycle.CycleTemplate;
import com.hospital.pbb.cycle.CycleTemplateRepository;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.dto.CellChange;
import com.hospital.pbb.schedule.dto.CellVO;
import com.hospital.pbb.schedule.dto.DutyPhoneChange;
import com.hospital.pbb.schedule.dto.GenerateResultVO;
import com.hospital.pbb.schedule.dto.PublishResultVO;
import com.hospital.pbb.schedule.dto.SaveDraftRequest;
import com.hospital.pbb.schedule.dto.SaveDraftResultVO;
import com.hospital.pbb.schedule.dto.UpdateEntryRequest;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * 排班写入：按规则生成整月、改单个单元格（设计 §5.1、任务单 M2-04）。
 *
 * <p>写口径只有两条：生成是"整月按规则铺一遍"，改格子是"整月按规则铺过之后的人工修正"。
 * 手工改过的格子打 {@code is_manual}，重新生成时原样保留，科长不至于被一键生成冲掉已排好的班。</p>
 *
 * <p>写方法都先取当月 advisory lock 再读写：生成和改格子作用在同一批 {@code (staff_id, work_date)}
 * 行上，不锁月的话两个科长会互相覆盖，操作日志也会记下一条库里并不存在的变更。
 * 锁是事务级的，随事务提交或回滚释放，所以整个方法必须待在同一个事务里。</p>
 *
 * <p>任何写入都把月份状态打回 {@link ScheduleStatus#DRAFT}：已发布的快照是另一张表，
 * 草稿改了不等于成员看到的变了，必须重新发布（M2-05）才会生效。</p>
 */
@Service
public class ScheduleService {

    private static final DateTimeFormatter MONTH_DAY = DateTimeFormatter.ofPattern("MM-dd");

    private final ScheduleMonthRepository monthRepo;
    private final ScheduleEntryRepository entryRepo;
    private final SchedulePublishedEntryRepository publishedRepo;
    private final StaffRepository staffRepo;
    private final ShiftTypeRepository shiftRepo;
    private final ScheduleQueryService query;
    private final OpLogService opLog;
    private final Clock clock;
    /** 按周期模板生成用（M4-05） */
    private final CycleTemplateRepository templateRepo;
    /** 值班电话草稿：暂存（M4-09）写它 */
    private final DutyPhoneWeekRepository dutyRepo;
    /** 值班电话已发布快照：发布（M4-13）把草稿复制过去，暂存不碰 */
    private final DutyPhonePublishedRepository dutyPublishedRepo;

    public ScheduleService(ScheduleMonthRepository monthRepo, ScheduleEntryRepository entryRepo,
                           SchedulePublishedEntryRepository publishedRepo, StaffRepository staffRepo,
                           ShiftTypeRepository shiftRepo, ScheduleQueryService query,
                           OpLogService opLog, Clock clock,
                           CycleTemplateRepository templateRepo, DutyPhoneWeekRepository dutyRepo,
                           DutyPhonePublishedRepository dutyPublishedRepo) {
        this.monthRepo = monthRepo;
        this.entryRepo = entryRepo;
        // 快照表由 M2-05 的发布写入，这里注入是为了锁与读写口径一致，本单不碰快照
        this.publishedRepo = publishedRepo;
        this.staffRepo = staffRepo;
        this.shiftRepo = shiftRepo;
        this.query = query;
        this.opLog = opLog;
        this.clock = clock;
        this.templateRepo = templateRepo;
        this.dutyRepo = dutyRepo;
        this.dutyPublishedRepo = dutyPublishedRepo;
    }

    /**
     * 按规则生成整月默认班次。
     *
     * <p>已手工改过的格子跳过不覆盖，其余格子一律重写成规则默认值；
     * 已经不存在于可排班名单里的人员的历史草稿不在本次范围内，保持不变。</p>
     *
     * <p>默认值取自选定的周期模板（周一..周日各上什么班），节假日仍然压过模板；
     * 本次用的模板 id 记到 {@code schedule_month.cycle_template_id} 上，单格“恢复规则默认”
     * 据此重算，否则科长按模板排好的班会被内置规则的周六日默认值冲掉（设计 §8.5）。</p>
     *
     * @param yearMonth  {@code YYYY-MM}，格式不对由 {@link ScheduleMonths#parse} 抛 code=1500
     * @param templateId 周期模板 id；null 表示用默认模板，没有默认模板时用内置规则
     * @param operatorId 操作人（科长）id，写入格子的 {@code updated_by}
     * @return 写入格数与跳过的手工格数
     * @throws BizException code=1507 模板不存在；1502 模板中的班次不存在或已停用
     */
    @Transactional
    public GenerateResultVO generate(String yearMonth, Long templateId, Long operatorId) {
        YearMonth ym = ScheduleMonths.parse(yearMonth);
        monthRepo.lockMonth(ScheduleMonths.lockKey(ym));

        // 模板在取锁之后、写第一格之前解析完：模板有问题时整月一格都不写
        CycleTemplate template = resolveTemplate(templateId);
        List<String> days = template == null ? null : template.days();

        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        RuleCalendar calendar = query.calendar(start, end);
        Map<String, ScheduleEntry> existing = existingEntries(start, end);
        OffsetDateTime now = OffsetDateTime.now(clock);

        int generated = 0;
        int skippedManual = 0;
        for (Staff staff : schedulableStaff()) {
            for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
                ScheduleEntry entry = existing.get(key(staff.getId(), date));
                if (entry != null && entry.isManual()) {
                    skippedManual++;
                    continue;
                }
                if (entry == null) {
                    entry = newEntry(staff.getId(), date);
                }
                entry.setShiftCode(calendar.defaultShift(date, days));
                entry.setManual(false);
                entry.setUpdatedBy(operatorId);
                entry.setUpdatedAt(now);
                entryRepo.save(entry);
                generated++;
            }
        }

        ScheduleMonth month = markDraft(yearMonth);
        month.setCycleTemplateId(template == null ? null : template.getId());
        monthRepo.save(month);
        opLog.record(OpAction.GENERATE_SCHEDULE, yearMonth,
                (template == null ? "内置规则" : "模板【" + template.getName() + "】")
                        + "，生成" + generated + "格，跳过手工" + skippedManual + "格");
        return new GenerateResultVO(generated, skippedManual);
    }

    /**
     * 取本次生成要用的模板：指定 id 时它必须存在；未指定时退回默认模板，
     * 一个默认模板也没有时返回 null（= 按内置规则生成）。
     *
     * @throws BizException code=1507 指定的模板不存在
     */
    private CycleTemplate resolveTemplate(Long templateId) {
        CycleTemplate template;
        if (templateId != null) {
            template = templateRepo.findById(templateId)
                    .orElseThrow(() -> new BizException(1507, "排班周期模板不存在"));
        } else {
            // 没指定模板就用默认模板；一个默认模板也没有 → null，退回内置规则
            template = templateRepo.findFirstByDefaultTemplateTrue().orElse(null);
        }
        if (template != null) {
            validateTemplateShifts(template);
        }
        return template;
    }

    /**
     * 模板里某一天排的班必须是启用中的班次，否则整月不生成：按一个半废的模板铺完全月，
     * 等于把已停用的班种塞进排班表。
     *
     * @throws BizException code=1502 模板中的班次不存在或已停用
     */
    private void validateTemplateShifts(CycleTemplate template) {
        for (String code : template.days().stream().distinct().toList()) {
            shiftRepo.findById(code).filter(ShiftType::isEnabled)
                    .orElseThrow(() -> new BizException(1502, "模板中的班次不存在或已停用"));
        }
    }

    /**
     * 改一个单元格。
     *
     * @param yearMonth  {@code YYYY-MM}，接口路径上的月份；{@code req.workDate()} 必须落在这个月内
     * @param req        人员、日期、班次代号（null 表示恢复规则默认）、备注
     * @param operatorId 操作人（科长）id
     * @return 改动后的格子
     * @throws BizException code=1500 月份格式不合法；1501 人员不存在或不参与排班；
     *                      1502 班次不存在或已停用；1503 日期不在该月内
     */
    @Transactional
    public CellVO updateEntry(String yearMonth, UpdateEntryRequest req, Long operatorId) {
        YearMonth ym = ScheduleMonths.parse(yearMonth);
        monthRepo.lockMonth(ScheduleMonths.lockKey(ym));

        LocalDate workDate = req.workDate();
        if (workDate.isBefore(ym.atDay(1)) || workDate.isAfter(ym.atEndOfMonth())) {
            throw new BizException(1503, "日期不在该月内");
        }

        CellVO cell = writeEntry(yearMonth, req, operatorId, query.calendar(ym.atDay(1), ym.atEndOfMonth()));
        markDraft(yearMonth);
        return cell;
    }

    /**
     * 写一格草稿并留痕，单格保存与暂存共用（取锁、校验之后）。
     *
     * <p>{@code shiftCode=null} 是“恢复规则默认”：按该月记录的模板重算默认值，并且摘掉 manual 标记，
     * 下次按规则生成时这一格会被正常覆盖；传了班次就是科长的手工修正，打 {@code is_manual}。</p>
     *
     * @param calendar 该月的规则日历，由调用方取一次复用，不必逐格查节假日
     */
    private CellVO writeEntry(String yearMonth, UpdateEntryRequest req, Long operatorId, RuleCalendar calendar) {
        Staff staff = validStaff(req.staffId());
        LocalDate workDate = req.workDate();

        String shiftCode;
        boolean manual;
        if (req.shiftCode() == null) {
            // 恢复默认不算手工修改：按该月记录的模板重算默认值，并且摘掉 manual 标记，下次生成可以正常覆盖
            shiftCode = calendar.defaultShift(workDate, templateDaysOf(yearMonth));
            manual = false;
        } else {
            shiftCode = validShift(req.shiftCode()).getCode();
            manual = true;
        }

        ScheduleEntry entry = entryRepo.findByStaffIdAndWorkDate(staff.getId(), workDate)
                .orElseGet(() -> newEntry(staff.getId(), workDate));
        String oldCode = entry.getShiftCode();
        String remark = (req.remark() == null || req.remark().isEmpty()) ? null : req.remark();
        entry.setShiftCode(shiftCode);
        entry.setManual(manual);
        entry.setRemark(remark);
        entry.setUpdatedBy(operatorId);
        entry.setUpdatedAt(OffsetDateTime.now(clock));
        entryRepo.save(entry);

        // 旧 code 为空 = 这个格子以前没排过班，日志里留成"空 → 新 code"，便于区分"新增"和"改班"
        opLog.record(OpAction.UPDATE_SCHEDULE, staff.getName() + " " + workDate.format(MONTH_DAY),
                (oldCode == null ? "" : oldCode) + " → " + shiftCode);
        return new CellVO(shiftCode, manual, remark);
    }

    /**
     * 暂存：把页面上改过的全部格子与值班电话一次性写回草稿（设计 §8.5“暂存”）。
     *
     * <p>先校验全部再写：科长一次提交几十格，如果写到第 12 格才发现班次已停用，前 11 格已经落库，
     * 页面上的“已改”和库里的“已存”就对不上了。所以校验阶段任何一条不合法都直接抛错，一格都不写。</p>
     *
     * <p>值班电话按周存，跨月那一周同时属于上个月和本月，所以锁与打回草稿都要覆盖
     * “本月 + 每个周跨到的月份”，去重升序逐个取（与 {@link #applyChanges} 同一口径，逆序取锁会死锁）。
     * 只处理本月的话，上个月的状态还停在已发布，可它边界那一周的值班电话已经被改掉了。</p>
     *
     * @param yearMonth  {@code YYYY-MM}，格子日期必须落在这个月内；值班电话的周只要与该月有交集即可
     * @param req        整批格子与值班电话，{@code dutyPhones.staffId=null} 表示清除该周
     * @param operatorId 操作人（科长）id，写入格子与值班电话的 {@code updated_by}
     * @return 本次写入的格数与周数
     * @throws BizException code=1500 月份格式不合法；1501 人员不存在或不参与排班；
     *                      1502 班次不存在或已停用；1503 格子日期不在该月内；
     *                      1505 值班电话的周起始日不是周一；1506 该周与本月没有交集
     */
    @Transactional
    public SaveDraftResultVO saveDraft(String yearMonth, SaveDraftRequest req, Long operatorId) {
        YearMonth ym = ScheduleMonths.parse(yearMonth);
        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();

        for (UpdateEntryRequest entry : req.entries()) {
            LocalDate workDate = entry.workDate();
            if (workDate.isBefore(start) || workDate.isAfter(end)) {
                throw new BizException(1503, "日期不在该月内");
            }
            validStaff(entry.staffId());
            if (entry.shiftCode() != null) {
                validShift(entry.shiftCode());
            }
        }
        for (DutyPhoneChange duty : req.dutyPhones()) {
            LocalDate weekStart = duty.weekStart();
            if (weekStart.getDayOfWeek() != DayOfWeek.MONDAY) {
                throw new BizException(1505, "值班电话的周起始日必须是周一");
            }
            if (weekStart.plusDays(6).isBefore(start) || weekStart.isAfter(end)) {
                throw new BizException(1506, "该周与本月没有交集");
            }
            if (duty.staffId() != null) {
                validStaff(duty.staffId());
            }
        }

        List<YearMonth> months = monthsInvolved(ym, req.dutyPhones());
        for (YearMonth month : months) {
            monthRepo.lockMonth(ScheduleMonths.lockKey(month));
        }

        RuleCalendar calendar = query.calendar(start, end);
        for (UpdateEntryRequest entry : req.entries()) {
            writeEntry(yearMonth, entry, operatorId, calendar);
        }
        for (DutyPhoneChange duty : req.dutyPhones()) {
            writeDutyPhone(duty, operatorId);
        }
        // 这里统一打回草稿，写每格时不单独 markDraft：整批只动一次 schedule_month
        for (YearMonth month : months) {
            markDraft(keyOf(month));
        }

        opLog.record(OpAction.SAVE_DRAFT, yearMonth,
                req.entries().size() + "格，值班电话" + req.dutyPhones().size() + "周");
        return new SaveDraftResultVO(req.entries().size(), req.dutyPhones().size());
    }

    /**
     * 写一周的值班电话草稿并留痕：{@code staffId=null} 是清除该周，库里没有这一行时不去删。
     *
     * <p>跨月那一周只有主键为周一的这一行，上个月、本月各自暂存时改的是同一行，
     * 所以谁最后暂存谁说了算（与 §8.5 发布的口径一致）。</p>
     */
    private void writeDutyPhone(DutyPhoneChange duty, Long operatorId) {
        LocalDate weekStart = duty.weekStart();
        String target = "值班电话 " + weekStart.format(MONTH_DAY) + " 周";
        if (duty.staffId() == null) {
            if (dutyRepo.existsById(weekStart)) {
                dutyRepo.deleteById(weekStart);
            }
            opLog.record(OpAction.SET_DUTY_PHONE, target, "清除");
            return;
        }
        Staff staff = validStaff(duty.staffId());
        DutyPhoneWeek week = dutyRepo.findById(weekStart).orElseGet(() -> {
            DutyPhoneWeek created = new DutyPhoneWeek();
            created.setWeekStart(weekStart);
            return created;
        });
        week.setStaffId(staff.getId());
        week.setUpdatedBy(operatorId);
        week.setUpdatedAt(OffsetDateTime.now(clock));
        dutyRepo.save(week);
        opLog.record(OpAction.SET_DUTY_PHONE, target, staff.getName());
    }

    /**
     * 本次暂存涉及的月份：接口路径上的月份，加上每个值班电话周跨到的月份（跨月那周会带出相邻月），
     * 去重升序——取锁和打回草稿用的是同一份，顺序必须固定，否则与相邻月份的暂存互相死锁。
     */
    private static List<YearMonth> monthsInvolved(YearMonth ym, List<DutyPhoneChange> dutyPhones) {
        TreeSet<YearMonth> months = new TreeSet<>();
        months.add(ym);
        for (DutyPhoneChange duty : dutyPhones) {
            months.addAll(ScheduleMonths.monthsOfWeek(duty.weekStart()));
        }
        return List.copyOf(months);
    }

    /** {@link YearMonth} 还原成 {@code schedule_month} 的主键写法，如 {@code 2026-10} */
    private static String keyOf(YearMonth ym) {
        return String.format("%04d-%02d", ym.getYear(), ym.getMonthValue());
    }

    /**
     * 人员必须存在、启用且参与排班（值班电话只能从可排班名单里选，设计 §8.1 第 3 条）。
     *
     * @throws BizException code=1501 人员不存在或不参与排班
     */
    private Staff validStaff(Long staffId) {
        return staffRepo.findById(staffId)
                .filter(s -> s.isActive() && s.isSchedulable())
                .orElseThrow(() -> new BizException(1501, "人员不存在或不参与排班"));
    }

    /**
     * 班次必须存在且启用：已停用的班种不能再排进排班表。
     *
     * @throws BizException code=1502 班次不存在或已停用
     */
    private ShiftType validShift(String shiftCode) {
        return shiftRepo.findById(shiftCode).filter(ShiftType::isEnabled)
                .orElseThrow(() -> new BizException(1502, "班次不存在或已停用"));
    }

    /**
     * 发布整月排班：把当前草稿整体复制成一份已发布快照。
     *
     * <p>快照是整月重写，不是增量补：先删掉当期快照再按草稿逐格插入，成员和大屏看到的
     * 永远是同一次发布的那一份。发布之后科长继续改草稿只影响草稿表，月份状态被打回
     * {@link ScheduleStatus#DRAFT}，要再次发布才生效。</p>
     *
     * <p>同样先取当月 advisory lock：不锁的话两个科长同时发布，删除与插入会交叉执行，
     * 快照里会混进两个版本的数据。</p>
     *
     * <p>值班电话一并发布：把该月涉及各周的草稿整体复制成已发布快照，成员和大屏从此看得到
     * （设计 §8.5“发布”）。该月第一周的周一常常落在上个月，这一周的草稿是上个月和本月共用的，
     * 所以先按月份升序取上个月的锁再取本月的锁，否则会与上个月正在进行的发布同时写同一周。</p>
     *
     * @param yearMonth  {@code YYYY-MM}，格式不对由 {@link ScheduleMonths#parse} 抛 code=1500
     * @param operatorId 操作人（科长）id，写入 {@code schedule_month.published_by}
     * @return 本次发布的版本号与复制格数
     * @throws BizException code=1504 整月还没有任何草稿，无从发布
     */
    @Transactional
    public PublishResultVO publish(String yearMonth, Long operatorId) {
        YearMonth ym = ScheduleMonths.parse(yearMonth);
        // 该月第一周的周一可能落在上个月（如 2026-10 → 09-28），值班电话按周存，这一周两个月共用
        LocalDate firstWeekStart = ScheduleMonths.firstWeekStart(ym);
        YearMonth firstMonth = YearMonth.from(firstWeekStart);
        if (firstMonth.isBefore(ym)) {
            monthRepo.lockMonth(ScheduleMonths.lockKey(firstMonth));
        }
        monthRepo.lockMonth(ScheduleMonths.lockKey(ym));

        LocalDate start = ym.atDay(1);
        LocalDate end = ym.atEndOfMonth();
        List<ScheduleEntry> entries = entryRepo.findByWorkDateBetween(start, end);
        if (entries.isEmpty()) {
            throw new BizException(1504, "本月还没有排班，请先按规则生成");
        }

        // 从没生成过的月份在 schedule_month 里没有记录，先按 version=0 补一条，自增后正好是 v1
        ScheduleMonth month = monthRepo.findById(yearMonth).orElseGet(() -> {
            ScheduleMonth created = new ScheduleMonth();
            created.setYearMonth(yearMonth);
            created.setVersion(0);
            return created;
        });
        int newVersion = month.getVersion() + 1;

        publishedRepo.deleteByWorkDateRange(start, end);
        publishedRepo.saveAll(entries.stream().map(entry -> newSnapshot(entry, newVersion)).toList());

        // 值班电话同样整段重写：范围从第一周的周一起，跨月那一周以本次发布为准（设计 §8.5）
        dutyPublishedRepo.deleteByWeekStartRange(firstWeekStart, end);
        List<DutyPhonePublished> dutySnapshots =
                dutyRepo.findByWeekStartBetweenOrderByWeekStartAsc(firstWeekStart, end).stream()
                        .map(ScheduleService::newDutySnapshot)
                        .toList();
        dutyPublishedRepo.saveAll(dutySnapshots);

        month.setVersion(newVersion);
        month.setStatus(ScheduleStatus.PUBLISHED);
        month.setPublishedAt(OffsetDateTime.now(clock));
        month.setPublishedBy(operatorId);
        monthRepo.save(month);

        opLog.record(OpAction.PUBLISH_SCHEDULE, yearMonth,
                "版本 v" + newVersion + "，共" + entries.size() + "格，值班电话" + dutySnapshots.size() + "周");
        return new PublishResultVO(newVersion, entries.size());
    }

    /** 值班电话快照只带走周一与人员：草稿上的 updated_by / updated_at 是编辑痕迹，不进快照表 */
    private static DutyPhonePublished newDutySnapshot(DutyPhoneWeek week) {
        DutyPhonePublished snapshot = new DutyPhonePublished();
        snapshot.setWeekStart(week.getWeekStart());
        snapshot.setStaffId(week.getStaffId());
        return snapshot;
    }

    /**
     * 把调班结果同时写入草稿和已发布快照（设计 §5.3、任务单 M3-01）。
     *
     * <p>调班是"科长已经批准的事实"，不是又一次手工改格子：月份状态和 version 一律不动，
     * 也不走 {@code markDraft}——否则审批通过一条申请会把整月打回草稿，成员看到的还是上一次发布的班，
     * 科长却以为已经变了。已发布快照里已有这个格子时才跟着改，没有就只写草稿，
     * 补插一条快照等于替科长发布了他没发布过的内容。</p>
     *
     * <p>锁按涉及的月份<b>去重后升序</b>逐个取：跨月调班（10-31 和 11-01 换）如果两个事务逆序取锁，
     * 会互相死锁。取完锁才写库，锁随事务释放，所以整个方法必须待在同一个事务里。</p>
     *
     * @param changes    调班结果，每条是"某人某天的班改成什么"
     * @param target     写入格子 remark 和操作日志 target，如 {@code 调班 TB-0003}
     * @param operatorId 操作人 id，写入格子的 {@code updated_by}
     */
    @Transactional
    public void applyChanges(List<CellChange> changes, String target, Long operatorId) {
        List<YearMonth> months = changes.stream()
                .map(change -> YearMonth.from(change.workDate()))
                .distinct()
                .sorted()
                .toList();
        for (YearMonth ym : months) {
            monthRepo.lockMonth(ScheduleMonths.lockKey(ym));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        for (CellChange change : changes) {
            ScheduleEntry entry = entryRepo.findByStaffIdAndWorkDate(change.staffId(), change.workDate())
                    .orElseGet(() -> newEntry(change.staffId(), change.workDate()));
            entry.setShiftCode(change.shiftCode());
            // 调班落地按手工格处理：重排规则时不能被规则默认值冲掉
            entry.setManual(true);
            entry.setRemark(target);
            entry.setUpdatedBy(operatorId);
            entry.setUpdatedAt(now);
            entryRepo.save(entry);

            publishedRepo.findByStaffIdAndWorkDate(change.staffId(), change.workDate())
                    .ifPresent(snapshot -> {
                        snapshot.setShiftCode(change.shiftCode());
                        snapshot.setRemark(target);
                        publishedRepo.save(snapshot);
                    });
        }

        opLog.record(OpAction.APPLY_SWAP_TO_SCHEDULE, target, "共" + changes.size() + "格");
    }

    /**
     * 删除人员：删此人全部草稿 / 已发布排班与值班电话（设计 §9.4，任务单 M5-02）。
     *
     * <p>不限月份，历史月份的已发布快照一并删掉——人都不在了，历史表里留着一行只会在大屏和
     * “我的排班”里挂一个查不到名字的空位。调用方（M5-07）只传 id，这里不再校验人员是否存在。</p>
     *
     * <p>{@code userId} 是该人员登录账号的 id（没有账号时为 null）：账号要被删，外键会卡住，
     * 所以先把 {@code schedule_entry.updated_by}、{@code duty_phone_week.updated_by}、
     * {@code schedule_month.published_by} 里该账号留下的痕迹置空，格子本身保留。</p>
     *
     * <p>不记操作日志（那条“删除人员”由 staff 模块统一记，记在这里会被随后的日志清理删掉），
     * 不取当月 advisory lock（整段和 staff 模块的其余删除跑在同一个事务里，
     * 删的是指定 staff 的行，不碰别人的格子），
     * 也不改 {@code schedule_month.status}：人删了不等于这一月要被打回草稿。</p>
     *
     * @param staffId 被删除的人员 id
     * @param userId  该人员的登录账号 id，为 null 时跳过操作人字段清理
     */
    @Transactional
    public void purgeStaff(Long staffId, Long userId) {
        entryRepo.deleteByStaffId(staffId);
        publishedRepo.deleteByStaffId(staffId);
        dutyRepo.deleteByStaffId(staffId);
        dutyPublishedRepo.deleteByStaffId(staffId);

        if (userId != null) {
            entryRepo.clearUpdatedBy(userId);
            dutyRepo.clearUpdatedBy(userId);
            monthRepo.clearPublishedBy(userId);
        }
    }

    /** 快照只带走会展示的那几列，{@code is_manual}、{@code updated_by} 这些管理字段留在草稿表里 */
    private static SchedulePublishedEntry newSnapshot(ScheduleEntry entry, int version) {
        SchedulePublishedEntry snapshot = new SchedulePublishedEntry();
        snapshot.setStaffId(entry.getStaffId());
        snapshot.setWorkDate(entry.getWorkDate());
        snapshot.setShiftCode(entry.getShiftCode());
        snapshot.setRemark(entry.getRemark());
        snapshot.setVersion(version);
        return snapshot;
    }

    /**
     * 把当月状态打回草稿，并返回这个月对象供调用方继续改其余字段（生成要顺带记模板 id）。
     *
     * <p>从没碰过的月份在 {@code schedule_month} 里没有记录，先按初始值补一条再置状态，
     * version 和 publishedAt 都不动——那是发布（M2-05）维护的字段。</p>
     *
     * <p>返回的是这里构造或查到的对象本身，不是 {@code save} 的返回值：调用方还要在它上面继续赋值，
     * 而打桩测试里的 {@code save} 可能返回 null。</p>
     */
    private ScheduleMonth markDraft(String yearMonth) {
        ScheduleMonth month = monthRepo.findById(yearMonth).orElseGet(() -> {
            ScheduleMonth created = new ScheduleMonth();
            created.setYearMonth(yearMonth);
            created.setStatus(ScheduleStatus.DRAFT);
            created.setVersion(0);
            return created;
        });
        month.setStatus(ScheduleStatus.DRAFT);
        monthRepo.save(month);
        return month;
    }

    /**
     * 该月记录的模板的 7 天班次；月份无记录、未记录模板或模板已删除时返回 null（= 退回内置规则）。
     * 生成之后模板可能被删除，这里只负责取值，取值失败就退回内置规则，不再报错。
     */
    private List<String> templateDaysOf(String yearMonth) {
        Long templateId = monthRepo.findById(yearMonth).map(ScheduleMonth::getCycleTemplateId).orElse(null);
        if (templateId == null) {
            return null;
        }
        return templateRepo.findById(templateId).map(CycleTemplate::days).orElse(null);
    }

    /** 生成只铺"启用中且参与排班"的人员，顺序沿用月视图的人员排序 */
    private List<Staff> schedulableStaff() {
        return staffRepo.findByActiveTrueOrderBySortOrderAscIdAsc().stream()
                .filter(Staff::isSchedulable)
                .toList();
    }

    /** 整月已有草稿一次读出来建索引，避免逐人逐日各查一次库 */
    private Map<String, ScheduleEntry> existingEntries(LocalDate start, LocalDate end) {
        Map<String, ScheduleEntry> map = new HashMap<>();
        for (ScheduleEntry entry : entryRepo.findByWorkDateBetween(start, end)) {
            map.put(key(entry.getStaffId(), entry.getWorkDate()), entry);
        }
        return map;
    }

    private static ScheduleEntry newEntry(Long staffId, LocalDate workDate) {
        ScheduleEntry entry = new ScheduleEntry();
        entry.setStaffId(staffId);
        entry.setWorkDate(workDate);
        return entry;
    }

    private static String key(Long staffId, LocalDate workDate) {
        return staffId + "|" + workDate;
    }
}
