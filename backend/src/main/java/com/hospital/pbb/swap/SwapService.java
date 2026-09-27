package com.hospital.pbb.swap;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.schedule.ScheduleService;
import com.hospital.pbb.schedule.dto.CellChange;
import com.hospital.pbb.staff.Staff;
import com.hospital.pbb.staff.StaffRepository;
import com.hospital.pbb.swap.dto.CreateSwapRequest;
import com.hospital.pbb.swap.dto.SwapVO;
import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调班申请（设计 §5.3，任务单 M3-03 列表、M3-04 发起、M3-05 确认/拒绝/撤销/审批）。
 *
 * <p>同一条记录在不同人眼里的可见范围不一样：科长看得到全科的，成员只关心
 * “我发起的”和“对方是我”的，所以三个 scope 各自走一条仓库查询，而不是查全量再在内存里过滤。</p>
 */
@Service
public class SwapService {

    /** 单据号前缀，{@code no} = 前缀 + id 左补零到 4 位，只用于人眼识别，唯一性仍以 id 为准 */
    private static final String NO_PREFIX = "TB-";

    /** 请假落地的班次代号（§5.3：LEAVE 通过后 A@那天 → L） */
    private static final String SHIFT_LEAVE = "L";

    /** 休息班次代号（§5.3：COVER 通过后申请人自己不再上班） */
    private static final String SHIFT_REST = "X";

    private final SwapRequestRepository repo;
    private final StaffRepository staffRepo;
    private final ScheduleQueryService query;
    // schedule 本单用不到：M3-05 确认/撤销/审批要回写排班，先注入好直接拿来用
    private final ScheduleService schedule;
    private final OpLogService opLog;
    private final Clock clock;

    public SwapService(SwapRequestRepository repo, StaffRepository staffRepo, ScheduleQueryService query,
                       ScheduleService schedule, OpLogService opLog, Clock clock) {
        this.repo = repo;
        this.staffRepo = staffRepo;
        this.query = query;
        this.schedule = schedule;
        this.opLog = opLog;
        this.clock = clock;
    }

    /**
     * 发起调班申请（任务单 M3-04）。
     *
     * <p>校验按“先便宜后贵”的顺序排：先账号、日期这类不查库的判断，再查对方人员，
     * 然后才是几次要发布快照的查询，最后查有没有进行中的申请，先失败先返回。</p>
     *
     * <p>请假没有对方，跳过全部对方校验直接进科长审批；换班要两头四天都有已发布班次才允许互换。</p>
     *
     * @param req 请求体
     * @param me  当前登录账号，申请人取 {@code me.staffId()}
     * @return 保存后的申请
     * @throws BizException 1601 账号未关联人员、1602 没有已发布的班次、1603 日期早于今天、
     *                      1604 对方是自己、1605 对方人员无效、1606 缺少对方人员或日期、
     *                      1607 该日期已有进行中的申请
     */
    @Transactional
    public SwapVO create(CreateSwapRequest req, AuthUser me) {
        Long staffId = me.staffId();
        if (staffId == null) {
            throw new BizException(1601, "当前账号未关联人员，不能申请调班");
        }
        LocalDate today = LocalDate.now(clock);
        LocalDate applicantDate = req.applicantDate();
        if (applicantDate.isBefore(today)) {
            throw new BizException(1603, "只能申请今天及以后的日期");
        }
        SwapType type = req.type();

        // LEAVE 不存在“对方”，前端就算传了也一律丢弃，避免留下一条永远确认不了的记录
        Long targetStaffId = type == SwapType.LEAVE ? null : req.targetStaffId();
        LocalDate targetDate = type == SwapType.LEAVE ? null : req.targetDate();
        if (type != SwapType.LEAVE) {
            if (targetStaffId == null) {
                throw new BizException(1606, "请选择对方人员");
            }
            if (targetStaffId.equals(staffId)) {
                throw new BizException(1604, "对方不能是自己");
            }
            requireTargetStaff(targetStaffId);
            if (type == SwapType.SWAP) {
                // 换班是两格换两格，缺对方日期就不知道换到哪一天；替班只要对方来上本人那天，targetDate 保持 null
                if (targetDate == null) {
                    throw new BizException(1606, "换班必须选择对方日期");
                }
                if (targetDate.isBefore(today)) {
                    throw new BizException(1603, "只能申请今天及以后的日期");
                }
            } else {
                targetDate = null;
            }
        }

        requirePublished(staffId, applicantDate, "该日期没有已发布的班次");
        if (type == SwapType.SWAP) {
            // 缺任何一格，审批时都换不成：本人那天换给对方后，对方那天得有条理地换给本人
            requirePublished(staffId, targetDate, "对方日期没有已发布的班次");
            requirePublished(targetStaffId, applicantDate, "对方日期没有已发布的班次");
            requirePublished(targetStaffId, targetDate, "对方日期没有已发布的班次");
        } else if (type == SwapType.COVER) {
            requirePublished(targetStaffId, applicantDate, "对方日期没有已发布的班次");
        }

        if (repo.existsByApplicantStaffIdAndApplicantDateAndStatusIn(staffId, applicantDate,
                List.of(SwapStatus.PENDING_PEER, SwapStatus.PENDING_ADMIN))) {
            throw new BizException(1607, "该日期已有进行中的申请");
        }

        SwapRequest request = new SwapRequest();
        request.setType(type);
        request.setApplicantStaffId(staffId);
        request.setApplicantDate(applicantDate);
        request.setTargetStaffId(targetStaffId);
        request.setTargetDate(targetDate);
        request.setReason(blankToNull(req.reason()));
        // 请假不需要对方点头，直接进科长审批；换班、替班先等对方确认
        request.setStatus(type == SwapType.LEAVE ? SwapStatus.PENDING_ADMIN : SwapStatus.PENDING_PEER);
        SwapRequest saved = repo.save(request);

        opLog.record(OpAction.CREATE_SWAP, noOf(saved.getId()), type + " " + applicantDate);
        return toVO(saved, me, staffById());
    }

    /** 对方人员必须存在且还在排班，停用了或不排班的人换不了班。 */
    private void requireTargetStaff(Long targetStaffId) {
        Staff target = staffRepo.findById(targetStaffId).orElse(null);
        if (target == null || !target.isActive() || !target.isSchedulable()) {
            throw new BizException(1605, "对方人员不存在或不参与排班");
        }
    }

    /** 那一天必须有已发布班次，草稿不算——成员看到的才是他想换的那个班。 */
    private void requirePublished(Long staffId, LocalDate date, String message) {
        if (query.publishedShift(staffId, date).isEmpty()) {
            throw new BizException(1602, message);
        }
    }

    /**
     * 对方同意（任务单 M3-05）。
     *
     * <p>对方点头不等于事情成了，只是把球踢给科长：状态进 {@code PENDING_ADMIN}，
     * 记录 {@code peer_confirmed_at} 作为“对方是哪天答应的”凭证，排班一格都不动。</p>
     *
     * @param id 申请 id
     * @param me 当前登录账号，必须是这条申请的对方
     * @return 进入待审批的申请
     * @throws BizException 1600 申请不存在；1608 已不是等对方确认；1609 操作的不是自己那条
     */
    @Transactional
    public SwapVO confirm(Long id, AuthUser me) {
        SwapRequest request = loadPendingPeer(id, me);
        request.setStatus(SwapStatus.PENDING_ADMIN);
        request.setPeerConfirmedAt(OffsetDateTime.now(clock));
        return saveAndLog(request, me, OpAction.CONFIRM_SWAP, null);
    }

    /**
     * 对方拒绝（任务单 M3-05）。
     *
     * <p>对方说不换就到此为止，不需要科长再裁一次，直接 {@code REJECTED} 归档，排班不动。</p>
     *
     * @param id 申请 id
     * @param me 当前登录账号，必须是这条申请的对方
     * @return 已拒绝的申请
     * @throws BizException 1600 申请不存在；1608 已不是等对方确认；1609 操作的不是自己那条
     */
    @Transactional
    public SwapVO rejectPeer(Long id, AuthUser me) {
        SwapRequest request = loadPendingPeer(id, me);
        request.setStatus(SwapStatus.REJECTED);
        return saveAndLog(request, me, OpAction.REJECT_SWAP_PEER, null);
    }

    /**
     * 申请人撤销（任务单 M3-05）。
     *
     * <p>两个待处理状态都能撤——对方还没点头可以自己反悔，已经点头但科长还没批也可以反悔
     * （此时科长看到的待办会少一条）。已经批完的记录不允许“撤成没发生过”，要反悔只能再提一条。</p>
     *
     * @param id 申请 id
     * @param me 当前登录账号，必须是这条申请的申请人
     * @return 已撤销的申请
     * @throws BizException 1600 申请不存在；1608 流程已结束；1609 操作的不是自己那条
     */
    @Transactional
    public SwapVO cancel(Long id, AuthUser me) {
        SwapRequest request = load(id);
        if (request.getStatus() != SwapStatus.PENDING_PEER && request.getStatus() != SwapStatus.PENDING_ADMIN) {
            throw new BizException(1608, "当前状态不允许此操作");
        }
        if (!sameStaff(me.staffId(), request.getApplicantStaffId())) {
            // 对方只负责点头或拒绝，撤销是申请人的权利，不能替他撤
            throw new BizException(1609, "无权操作该申请");
        }
        request.setStatus(SwapStatus.CANCELLED);
        return saveAndLog(request, me, OpAction.CANCEL_SWAP, null);
    }

    /**
     * 科长审批通过，并把结果回写排班（任务单 M3-05，设计 §5.3）。
     *
     * <p>要改的格子按<b>审批时</b>的已发布班次现算，不取发起时存下的快照：从发起到科长点通过
     * 中间可能过了几天，排班可能已被科长改过，按旧值算会把人排到不存在的班上去。
     * 算不出完整格子（任意一格已无已发布）就直接 1602 报错，事务回滚，
     * 绝不先改一半排班再把申请标成通过。</p>
     *
     * @param id      申请 id
     * @param comment 审批意见，可空
     * @param me      当前登录账号（科长），写入排班格子的 {@code updated_by}
     * @return 已通过的申请（班次字段已是回写后的值）
     * @throws BizException 1600 申请不存在；1608 不在待审批；1602 排班已变化
     */
    @Transactional
    public SwapVO approve(Long id, String comment, AuthUser me) {
        SwapRequest request = loadPendingAdmin(id);
        List<CellChange> changes = changesOf(request);
        schedule.applyChanges(changes, "调班 " + noOf(request.getId()), me.id());

        request.setStatus(SwapStatus.APPROVED);
        markReviewed(request, comment, me);
        return saveAndLog(request, me, OpAction.APPROVE_SWAP, "回写" + changes.size() + "格");
    }

    /**
     * 科长驳回调班申请（任务单 M3-05）。
     *
     * <p>驳回什么都不改——排班一格不动，只把申请归档并记下是谁驳的、理由是什么，
     * 让申请人能看到原因后重新发一条。</p>
     *
     * @param id      申请 id
     * @param comment 驳回理由，可空（全空格按空存）
     * @param me      当前登录账号（科长）
     * @return 已驳回的申请
     * @throws BizException 1600 申请不存在；1608 不在待审批
     */
    @Transactional
    public SwapVO reject(Long id, String comment, AuthUser me) {
        SwapRequest request = loadPendingAdmin(id);
        request.setStatus(SwapStatus.REJECTED);
        markReviewed(request, comment, me);
        return saveAndLog(request, me, OpAction.REJECT_SWAP, null);
    }

    /** 按 id 取申请，查不到就是已经没了，1600。 */
    private SwapRequest load(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(1600, "调班申请不存在"));
    }

    /** 对方确认/拒绝的入口条件：还在等对方点头，而且当前登录人就是那个“对方”。 */
    private SwapRequest loadPendingPeer(Long id, AuthUser me) {
        SwapRequest request = load(id);
        if (request.getStatus() != SwapStatus.PENDING_PEER) {
            throw new BizException(1608, "当前状态不允许此操作");
        }
        if (!sameStaff(me.staffId(), request.getTargetStaffId())) {
            throw new BizException(1609, "无权操作该申请");
        }
        return request;
    }

    /**
     * 科长审批的入口条件：只有“等科长审批”这一种状态可批。
     *
     * <p>对方同不同意由 {@code confirm}/{@code rejectPeer} 把关，能不能轮到科长批由这里把关，
     * 至于是不是科长——已由 Controller 的 {@code @PreAuthorize} 拦在前面。</p>
     */
    private SwapRequest loadPendingAdmin(Long id) {
        SwapRequest request = load(id);
        if (request.getStatus() != SwapStatus.PENDING_ADMIN) {
            throw new BizException(1608, "当前状态不允许此操作");
        }
        return request;
    }

    /**
     * 按设计 §5.3 算出审批通过后“某人某天的班改成什么”，口径一律取审批时的已发布班次。
     *
     * <p>换班按 {@code {本人那天, 对方那天}} 去重后的每一天逐日互换：同一天互换（两人都是 10-12）
     * 只算一次，不然是把同一格写两遍，第二遍还会把第一遍的结果又换回去。</p>
     *
     * @throws BizException 1602 任意一格已无已发布班次
     */
    private List<CellChange> changesOf(SwapRequest request) {
        Long applicantStaffId = request.getApplicantStaffId();
        LocalDate applicantDate = request.getApplicantDate();
        List<CellChange> changes = new ArrayList<>();
        switch (request.getType()) {
            case LEAVE -> changes.add(new CellChange(applicantStaffId, applicantDate, SHIFT_LEAVE));
            case COVER -> {
                // 对方来上本人那个班，本人自己改成休息（不是 L：他是“不用上了”，不是请假）
                String original = shiftOf(applicantStaffId, applicantDate);
                changes.add(new CellChange(request.getTargetStaffId(), applicantDate, original));
                changes.add(new CellChange(applicantStaffId, applicantDate, SHIFT_REST));
            }
            case SWAP -> {
                Long targetStaffId = request.getTargetStaffId();
                for (LocalDate date : daysOf(applicantDate, request.getTargetDate())) {
                    String applicantShift = shiftOf(applicantStaffId, date);
                    String targetShift = shiftOf(targetStaffId, date);
                    changes.add(new CellChange(applicantStaffId, date, targetShift));
                    changes.add(new CellChange(targetStaffId, date, applicantShift));
                }
            }
        }
        return changes;
    }

    /** {@code {本人那天, 对方那天}}：去重且本人那天在前，审批后的日志与列表顺序保持一致。 */
    private static List<LocalDate> daysOf(LocalDate applicantDate, LocalDate targetDate) {
        return targetDate == null || targetDate.equals(applicantDate)
                ? List.of(applicantDate)
                : List.of(applicantDate, targetDate);
    }

    /** 审批时重新取已发布班次：取不到 = 发起之后排班被动过，这笔换不成了。 */
    private String shiftOf(Long staffId, LocalDate date) {
        return query.publishedShift(staffId, date)
                .orElseThrow(() -> new BizException(1602, "排班已变化，请驳回后重新申请"));
    }

    /** 审批留痕：审批人取账号 id（不是人员 id），意见全空格与不填一样存 null。 */
    private void markReviewed(SwapRequest request, String comment, AuthUser me) {
        request.setReviewedBy(me.id());
        request.setReviewedAt(OffsetDateTime.now(clock));
        request.setReviewComment(blankToNull(comment));
    }

    /**
     * 入库 + 留痕 + 转 VO，五个流程方法共同的尾巴。
     *
     * <p>操作日志 target 一律是单据号 {@code TB-xxxx}，detail 带上类型和本人那天，
     * 免得日志里十几条“同意调班”分不清是哪一笔；{@code extra} 只给审批通过补上回写格数。</p>
     */
    private SwapVO saveAndLog(SwapRequest request, AuthUser me, String action, String extra) {
        SwapRequest saved = repo.save(request);
        String detail = saved.getType() + " " + saved.getApplicantDate();
        opLog.record(action, noOf(saved.getId()), extra == null ? detail : detail + "，" + extra);
        return toVO(saved, me, staffById());
    }

    /**
     * 调班列表。
     *
     * @param me    当前登录账号
     * @param scope {@code ALL} 全部 / {@code MINE} 我发起的 / {@code TODO} 待我处理，null 视为 ALL
     * @return 按 id 倒序（新申请在前）；账号没关联人员的成员返回空列表
     */
    @Transactional(readOnly = true)
    public List<SwapVO> list(AuthUser me, String scope) {
        List<SwapRequest> requests = requestsOf(me, scope);
        if (requests.isEmpty()) {
            return List.of();
        }
        Map<Long, Staff> staffById = staffById();
        List<SwapVO> list = new ArrayList<>(requests.size());
        for (SwapRequest request : requests) {
            list.add(toVO(request, me, staffById));
        }
        return list;
    }

    /**
     * 一条记录转 VO：姓名查人员表，班次取已发布快照，按钮标志按当前登录人算。
     * 供本类其它方法（M3-04 发起、M3-05 审批后的返回值）复用。
     */
    SwapVO toVO(SwapRequest r, AuthUser me, Map<Long, Staff> staffById) {
        SwapStatus status = r.getStatus();
        String applicantShift = query.publishedShift(r.getApplicantStaffId(), r.getApplicantDate()).orElse(null);
        return new SwapVO(r.getId(), noOf(r.getId()), r.getType(),
                r.getApplicantStaffId(), nameOf(staffById, r.getApplicantStaffId()),
                r.getApplicantDate(), applicantShift,
                r.getTargetStaffId(), nameOf(staffById, r.getTargetStaffId()),
                r.getTargetDate(), targetShiftOf(r),
                r.getReason(), status, r.getReviewComment(), r.getCreatedAt(),
                status == SwapStatus.PENDING_PEER && sameStaff(me.staffId(), r.getTargetStaffId()),
                (status == SwapStatus.PENDING_PEER || status == SwapStatus.PENDING_ADMIN)
                        && sameStaff(me.staffId(), r.getApplicantStaffId()),
                status == SwapStatus.PENDING_ADMIN && me.role() == Role.ADMIN);
    }

    /**
     * 按角色 + scope 选一条仓库查询。
     *
     * <p>科长是审批人，“待我处理”= 等科长审批（PENDING_ADMIN）；成员的“待我处理”= 等对方确认（PENDING_PEER）
     * 且对方是我自己。</p>
     */
    private List<SwapRequest> requestsOf(AuthUser me, String scope) {
        // 只有这三个取值，前端传别的一律按 ALL 处理，列表页不该因为一个拼错的参数白屏
        String sc = scope == null ? "ALL" : scope;
        if (me.role() == Role.ADMIN) {
            return switch (sc) {
                case "MINE" -> repo.findByApplicantStaffIdOrderByIdDesc(me.staffId());
                case "TODO" -> repo.findByStatusOrderByIdDesc(SwapStatus.PENDING_ADMIN);
                default -> repo.findAllByOrderByIdDesc();
            };
        }
        Long staffId = me.staffId();
        if (staffId == null) {
            // 账号没关联人员，既当不了申请人也当不了对方，一条都不属于他，没必要查库
            return List.of();
        }
        return switch (sc) {
            case "MINE" -> repo.findByApplicantStaffIdOrderByIdDesc(staffId);
            case "TODO" -> repo.findByStatusAndTargetStaffIdOrderByIdDesc(SwapStatus.PENDING_PEER, staffId);
            default -> repo.findByApplicantStaffIdOrTargetStaffIdOrderByIdDesc(staffId, staffId);
        };
    }

    /** 姓名：人员表整表建索引（几十条），列表页每行两次 findById 不值当。 */
    private Map<Long, Staff> staffById() {
        Map<Long, Staff> staffById = new HashMap<>();
        for (Staff staff : staffRepo.findAll()) {
            staffById.put(staff.getId(), staff);
        }
        return staffById;
    }

    /** 对方那天的班次：只有换班才是“两头都要看”，请假、替班列表上不显示对方的班次。 */
    private String targetShiftOf(SwapRequest r) {
        if (r.getType() != SwapType.SWAP || r.getTargetStaffId() == null || r.getTargetDate() == null) {
            return null;
        }
        return query.publishedShift(r.getTargetStaffId(), r.getTargetDate()).orElse(null);
    }

    /** 人员查不到（被删了）时姓名为 null，列表显示空，不能让整页报错。 */
    private static String nameOf(Map<Long, Staff> staffById, Long staffId) {
        Staff staff = staffId == null ? null : staffById.get(staffId);
        return staff == null ? null : staff.getName();
    }

    /** 理由全空格与不填没有区别，存 null，列表上不会显示一串空白。 */
    private static String blankToNull(String reason) {
        return reason == null || reason.isBlank() ? null : reason;
    }

    /** 单据号：TB-0003。 */
    private static String noOf(Long id) {
        return id == null ? null : String.format("%s%04d", NO_PREFIX, id);
    }

    /** staffId 为 null 的账号（科长、大屏）不算和任何人相同，否则 LEAVE 的空 target 会被当成“对方是我”。 */
    private static boolean sameStaff(Long left, Long right) {
        return left != null && left.equals(right);
    }
}
