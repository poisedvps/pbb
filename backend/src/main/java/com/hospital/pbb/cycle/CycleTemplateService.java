package com.hospital.pbb.cycle;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.cycle.dto.CycleTemplateRequest;
import com.hospital.pbb.cycle.dto.CycleTemplateVO;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.shift.ShiftType;
import com.hospital.pbb.shift.ShiftTypeRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 排班周期模板维护（设计 §8.3、§8.4）。
 *
 * <p>一个模板规定周一..周日各排什么班，按规则生成排班时选定一个模板；
 * 全系统最多一个默认模板，这条约束由库里的部分唯一索引
 * {@code uq_cycle_template_default} 兜底，代码里"先取消旧默认、再写新默认"的顺序就是为了不撞它。</p>
 *
 * <p>默认模板是"按规则生成"不指定模板时的依据，也是该月"恢复规则默认"的落点，
 * 所以不允许把最后一个默认模板改掉（1804）或删掉（1802）。</p>
 *
 * <p>三个写操作都先取 {@code cycle_template} 的表级 advisory lock 再读写："读当前默认 → 摘掉它 → 写自己"
 * 是跨语句的读—改—写，只靠部分唯一索引挡得住脏数据，挡不住输的一方撞索引落 500。</p>
 */
@Service
public class CycleTemplateService {

    /** V2 迁移里 cycle_template.name 的 UNIQUE 约束名 */
    static final String NAME_UNIQUE_CONSTRAINT = "cycle_template_name_key";
    /** cycle_template 表级 advisory lock 的 key，库里固定只用一把（表只有几行，不存在粒度不够） */
    static final int LOCK_KEY = 0;

    private final CycleTemplateRepository repo;
    private final ShiftTypeRepository shiftRepo;
    private final OpLogService opLog;
    private final Clock clock;

    public CycleTemplateService(CycleTemplateRepository repo, ShiftTypeRepository shiftRepo,
                                OpLogService opLog, Clock clock) {
        this.repo = repo;
        this.shiftRepo = shiftRepo;
        this.opLog = opLog;
        this.clock = clock;
    }

    /** 模板列表，按 id 升序 */
    public List<CycleTemplateVO> list() {
        return repo.findAllByOrderByIdAsc().stream().map(CycleTemplateVO::of).toList();
    }

    /** 新增模板；isDefault=true 时原来的默认模板先让位 */
    @Transactional
    public CycleTemplateVO create(CycleTemplateRequest req) {
        lockTemplate();
        String name = validateName(req.name(), null);
        validateDays(req.days());
        if (req.isDefault()) {
            demoteCurrentDefault();
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        CycleTemplate template = new CycleTemplate();
        template.setName(name);
        template.setDays(req.days());
        template.setDefaultTemplate(req.isDefault());
        template.setCreatedAt(now);
        template.setUpdatedAt(now);
        flushOrNameConflict(template);

        opLog.record(OpAction.CREATE_CYCLE, name, detailOf(req.days()));
        return CycleTemplateVO.of(template);
    }

    /**
     * 修改模板：名称、7 个班次代号、是否默认。
     *
     * <p>已是默认模板的这一条不允许被改成非默认（1804），否则全库就没有默认模板了；
     * 把非默认改成默认时要先摘掉别的模板的默认标记。</p>
     */
    @Transactional
    public CycleTemplateVO update(Long id, CycleTemplateRequest req) {
        lockTemplate();
        CycleTemplate template = load(id);
        String name = validateName(req.name(), id);
        validateDays(req.days());
        if (template.isDefaultTemplate() && !req.isDefault()) {
            throw new BizException(1804, "至少保留一个默认模板");
        }
        if (req.isDefault() && !template.isDefaultTemplate()) {
            demoteCurrentDefault();
        }

        template.setName(name);
        template.setDays(req.days());
        template.setDefaultTemplate(req.isDefault());
        template.setUpdatedAt(OffsetDateTime.now(clock));
        flushOrNameConflict(template);

        opLog.record(OpAction.UPDATE_CYCLE, name, detailOf(req.days()));
        return CycleTemplateVO.of(template);
    }

    /** 删除模板；库里 schedule_month.cycle_template_id 是 ON DELETE SET NULL，历史月份自动退回内置规则 */
    @Transactional
    public void delete(Long id) {
        lockTemplate();
        CycleTemplate template = load(id);
        if (template.isDefaultTemplate()) {
            throw new BizException(1802, "默认模板不能删除");
        }
        repo.delete(template);
        opLog.record(OpAction.DELETE_CYCLE, template.getName(), "");
    }

    /**
     * 取 cycle_template 的表级事务 advisory lock，必须是写方法的第一条语句。
     *
     * <p>“全表最多一个默认模板”要同时读旧默认、改旧默认、写自己，两个事务各自读到同一个旧默认
     * 再去抢部分唯一索引时，输的一方会撞 {@code uq_cycle_template_default} 落 500。锁必须在任何读取
     * 之前拿：先读后锁的话，本事务的持久化上下文里已经缓存了旧值，1804 / 1802 用的也是过期的默认标记。
     * 锁对象是表这个常量（key 固定 0），不依赖任何可能变动的行——旧默认会被换掉，库里也可能一行默认都没有。
     * 后到的事务在锁上等前一个提交，再读到最新的当前默认，所以两个请求都能成功。{@code list} 只读，不加锁。</p>
     */
    private void lockTemplate() {
        repo.lockTemplate(LOCK_KEY);
    }

    /**
     * 名称校验：去首尾空格后查重，返回去空格后的名称（入库和记日志都用它）。
     *
     * <p>这里只是“先查”，查重与写入之间没有锁，真正的兜底是 {@code cycle_template.name} 的
     * UNIQUE 约束，见 {@link #flushOrNameConflict}。</p>
     *
     * @param selfId 修改时传自身 id（改自己不算重名），新增时传 null
     */
    private String validateName(String rawName, Long selfId) {
        String name = rawName.trim();
        boolean taken = selfId == null ? repo.existsByName(name) : repo.existsByNameAndIdNot(name, selfId);
        if (taken) {
            throw new BizException(1801, "模板名称已存在");
        }
        return name;
    }

    /** 7 个代号逐个确认是已启用的班次：模板里的班次一旦停用，按这个模板生成的排班会出现排不出名字的格子 */
    private void validateDays(List<String> days) {
        for (String code : days) {
            shiftRepo.findById(code)
                    .filter(ShiftType::isEnabled)
                    .orElseThrow(() -> new BizException(1803, "模板中的班次不存在或已停用"));
        }
    }

    /**
     * 把当前的默认模板改成非默认。
     *
     * <p>必须 {@code saveAndFlush}：部分唯一索引 {@code uq_cycle_template_default} 只看已落库的行，
     * 光在实体上改标记、等 Hibernate 提交时按任意顺序刷写，会让新旧两条默认同时落到索引里。</p>
     */
    private void demoteCurrentDefault() {
        repo.findFirstByDefaultTemplateTrue().ifPresent(current -> {
            current.setDefaultTemplate(false);
            repo.saveAndFlush(current);
        });
    }

    /**
     * 写入并立即落库。
     *
     * <p>必须 flush：预检查到提交之间没有锁，两个同名请求可能都通过 {@code existsByName}，
     * 后提交的一方在 {@code cycle_template.name} 的 UNIQUE 约束上撞下。flush 让冲突在本方法
     * 里就被抓到手，翻译成 1801，而不是留到事务提交时变成全局 500。</p>
     */
    private void flushOrNameConflict(CycleTemplate template) {
        try {
            repo.saveAndFlush(template);
        } catch (DataIntegrityViolationException e) {
            if (!isNameConflict(e)) {
                throw e; // 其他完整性冲突（外键、默认模板唯一索引等）原样抛出，事务照常回滚
            }
            throw new BizException(1801, "模板名称已存在");
        }
    }

    /**
     * 只认 {@code cycle_template.name} 的唯一约束冲突：异常链里出现该约束名才算。
     *
     * <p>光看 SQLState 23505 不够：{@code uq_cycle_template_default} 撞的也是 23505，
     * 那是并发切默认模板，不是重名，翻成 1801 会把调用方引导到错误的修法。其余完整性冲突
     * （外键、非空、默认模板唯一索引）一律返回 false，由调用方原样抛出。</p>
     */
    private static boolean isNameConflict(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null && t != t.getCause(); t = t.getCause()) {
            String msg = t.getMessage();
            if (msg != null && msg.contains(NAME_UNIQUE_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private CycleTemplate load(Long id) {
        return repo.findById(id).orElseThrow(() -> new BizException(1800, "排班周期模板不存在"));
    }

    /** 操作日志的 detail：{@code 周一至周日：D D D D D X X}，只有班次代号，没有敏感信息 */
    private static String detailOf(List<String> days) {
        return "周一至周日：" + String.join(" ", days);
    }
}
