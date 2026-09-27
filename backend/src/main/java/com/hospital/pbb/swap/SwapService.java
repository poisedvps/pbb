package com.hospital.pbb.swap;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.schedule.ScheduleQueryService;
import com.hospital.pbb.schedule.ScheduleService;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 调班申请（设计 §5.3，任务单 M3-03 列表、M3-04 发起）。
 *
 * <p>同一条记录在不同人眼里的可见范围不一样：科长看得到全科的，成员只关心
 * “我发起的”和“对方是我”的，所以三个 scope 各自走一条仓库查询，而不是查全量再在内存里过滤。</p>
 */
@Service
public class SwapService {

    /** 单据号前缀，{@code no} = 前缀 + id 左补零到 4 位，只用于人眼识别，唯一性仍以 id 为准 */
    private static final String NO_PREFIX = "TB-";

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
