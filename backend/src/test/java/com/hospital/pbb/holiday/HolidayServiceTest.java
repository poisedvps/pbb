package com.hospital.pbb.holiday;

import com.hospital.pbb.common.BizException;
import com.hospital.pbb.holiday.dto.HolidayRequest;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HolidayService 的业务规则 + 并发回归。
 *
 * <p>并发部分用 {@link FakeDb}：共享一份表数据、未提交的写入别人读不到、外加与
 * {@code pg_advisory_xact_lock(1400, year)} 等价的按年互斥锁，跑真正的多线程，验证
 * "同年相交区间只能成功一条""复制与目标年新建不能双双成功"。</p>
 */
class HolidayServiceTest {

    private static final LocalDate NATIONAL_DAY_START = LocalDate.of(2026, 10, 1);
    private static final LocalDate NATIONAL_DAY_END = LocalDate.of(2026, 10, 7);
    private static final long WAIT_SECONDS = 10;

    private HolidayRepository repo;
    private OpLogService opLog;
    private HolidayService service;

    @BeforeEach
    void setUp() {
        repo = mock(HolidayRepository.class);
        opLog = mock(OpLogService.class);
        when(repo.save(any(Holiday.class))).thenAnswer(inv -> inv.getArgument(0));
        when(repo.existsById(any(Long.class))).thenReturn(true);
        service = new HolidayService(repo, opLog);
    }

    private static HolidayRequest request(String name, String start, String end,
                                         HolidayType type, String remark) {
        return new HolidayRequest(name, LocalDate.parse(start), LocalDate.parse(end), type, remark);
    }

    private static HolidayRequest nationalDay2026() {
        return request("国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY, null);
    }

    private static Holiday row(long id, String name, String start, String end, HolidayType type) {
        Holiday holiday = new Holiday();
        holiday.setId(id);
        holiday.setYear(LocalDate.parse(start).getYear());
        holiday.setName(name);
        holiday.setStartDate(LocalDate.parse(start));
        holiday.setEndDate(LocalDate.parse(end));
        holiday.setType(type);
        return holiday;
    }

    /** 让 [start, end] 区间查不到任何已有记录，即"没有重叠" */
    private void noOverlap() {
        when(repo.findOverlapping(any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of());
    }

    private Holiday captureSave() {
        ArgumentCaptor<Holiday> captor = ArgumentCaptor.forClass(Holiday.class);
        verify(repo).save(captor.capture());
        return captor.getValue();
    }

    private Holiday captureSaveAfterCreate(HolidayRequest req) {
        service.create(req);
        return captureSave();
    }

    // ---------------------------------------------------------------- 业务规则

    @Test
    void createUsesStartYearAndKeepsFields() {
        noOverlap();

        Holiday saved = captureSaveAfterCreate(request("国庆节", "2026-10-01", "2026-10-07",
                HolidayType.HOLIDAY, "国庆七天假"));

        assertEquals(2026, saved.getYear());
        assertEquals("国庆节", saved.getName());
        assertEquals(NATIONAL_DAY_START, saved.getStartDate());
        assertEquals(NATIONAL_DAY_END, saved.getEndDate());
        assertEquals(HolidayType.HOLIDAY, saved.getType());
        assertEquals("国庆七天假", saved.getRemark());
        verify(opLog).record(OpAction.CREATE_HOLIDAY, "国庆节", "2026-10-01~2026-10-07 HOLIDAY");
    }

    @Test
    void createReturnsTheSavedRow() {
        noOverlap();
        Holiday created = service.create(nationalDay2026());
        assertSame(captureSave(), created);
    }

    /** 前端清空备注框传的是空串，存进去必须是 null，不能是空白字符串 */
    @Test
    void createStoresNullForBlankRemark() {
        noOverlap();
        Holiday saved = captureSaveAfterCreate(request("元旦", "2026-01-01", "2026-01-03",
                HolidayType.HOLIDAY, "   "));
        assertNull(saved.getRemark());
    }

    @Test
    void createRejectsEndDateBeforeStartDate() {
        BizException e = assertThrows(BizException.class, () -> service.create(request("国庆节",
                "2026-10-01", "2026-09-30", HolidayType.HOLIDAY, null)));

        assertEquals(1401, e.getCode());
        assertEquals("结束日期不能早于开始日期", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsCrossYearRange() {
        BizException e = assertThrows(BizException.class, () -> service.create(request("元旦",
                "2026-12-31", "2027-01-01", HolidayType.HOLIDAY, null)));

        assertEquals(1402, e.getCode());
        assertEquals("节假日不能跨年，请分两条录入", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void createRejectsOverlappingDatesAndNamesTheConflict() {
        when(repo.findOverlapping(LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 8)))
                .thenReturn(List.of(row(7L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY)));

        BizException e = assertThrows(BizException.class, () -> service.create(request("调休",
                "2026-10-05", "2026-10-08", HolidayType.WORKDAY, null)));

        assertEquals(1403, e.getCode());
        assertTrue(e.getMessage().contains("国庆节"), () -> "错误信息里要指出冲突的是哪一条，实际：" + e.getMessage());
        verify(repo, never()).save(any());
    }

    /** 修改自己时日期区间必然与自身重合，排除 selfId 之后才算没有重叠 */
    @Test
    void updateSelfWithSameDatesIsAllowed() {
        Holiday existing = row(7L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));
        when(repo.findOverlapping(NATIONAL_DAY_START, NATIONAL_DAY_END)).thenReturn(List.of(existing));

        Holiday updated = service.update(7L, request("国庆节", "2026-10-01", "2026-10-07",
                HolidayType.HOLIDAY, "按国务院安排"));

        assertEquals(2026, updated.getYear());
        assertEquals("按国务院安排", updated.getRemark());
        assertSame(existing, updated);
        verify(opLog).record(OpAction.UPDATE_HOLIDAY, "国庆节", "2026-10-01~2026-10-07 HOLIDAY");
    }

    @Test
    void updateRecomputesYearAndRejectsAnotherRowsDates() {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, "国庆节", "2026-10-01",
                "2026-10-07", HolidayType.HOLIDAY)));
        when(repo.findOverlapping(LocalDate.of(2027, 10, 1), LocalDate.of(2027, 10, 3)))
                .thenReturn(List.of(row(9L, "中秋", "2027-10-02", "2027-10-04", HolidayType.HOLIDAY)));

        BizException e = assertThrows(BizException.class, () -> service.update(7L, request("国庆节",
                "2027-10-01", "2027-10-03", HolidayType.HOLIDAY, null)));

        assertEquals(1403, e.getCode());
        assertTrue(e.getMessage().contains("中秋"));
    }

    @Test
    void updateMissingIdReturns1400() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.update(999L, nationalDay2026()));

        assertEquals(1400, e.getCode());
        assertEquals("节假日不存在", e.getMessage());
    }

    @Test
    void copyShiftsDatesToTargetYearAndReturnsCount() {
        when(repo.findByYearOrderByStartDateAsc(2027)).thenReturn(List.of());
        when(repo.findByYearOrderByStartDateAsc(2026)).thenReturn(List.of(
                row(1L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY),
                row(2L, "节后上班", "2026-10-10", "2026-10-10", HolidayType.WORKDAY)));

        int copied = service.copy(2026, 2027);

        assertEquals(2, copied);
        ArgumentCaptor<Holiday> captor = ArgumentCaptor.forClass(Holiday.class);
        verify(repo, times(2)).save(captor.capture());
        List<Holiday> saved = captor.getAllValues();

        assertEquals(LocalDate.of(2027, 10, 1), saved.get(0).getStartDate());
        assertEquals(LocalDate.of(2027, 10, 7), saved.get(0).getEndDate());
        assertEquals(LocalDate.of(2027, 10, 10), saved.get(1).getStartDate());
        assertEquals(LocalDate.of(2027, 10, 10), saved.get(1).getEndDate());
        for (Holiday holiday : saved) {
            assertEquals(2027, holiday.getYear());
            assertNull(holiday.getId(), "复制出来的是新记录，不能带上源记录的 id");
            assertTrue(holiday.getRemark().contains("从2026年复制"), holiday.getRemark());
        }
        assertEquals("国庆节", saved.get(0).getName());
        assertEquals(HolidayType.HOLIDAY, saved.get(0).getType());
        assertEquals("节后上班", saved.get(1).getName());
        assertEquals(HolidayType.WORKDAY, saved.get(1).getType());
        verify(opLog).record(OpAction.COPY_HOLIDAY, "2027年", "从2026年复制2条");
    }

    @Test
    void copyRejectsWhenTargetYearNotEmpty() {
        when(repo.findByYearOrderByStartDateAsc(2027))
                .thenReturn(List.of(row(3L, "元旦", "2027-01-01", "2027-01-03", HolidayType.HOLIDAY)));

        BizException e = assertThrows(BizException.class, () -> service.copy(2026, 2027));

        assertEquals(1404, e.getCode());
        assertEquals("2027 年已有节假日，不能复制", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void copyRejectsWhenSourceYearEmpty() {
        when(repo.findByYearOrderByStartDateAsc(2026)).thenReturn(List.of());
        when(repo.findByYearOrderByStartDateAsc(2025)).thenReturn(List.of());

        BizException e = assertThrows(BizException.class, () -> service.copy(2025, 2026));

        assertEquals(1405, e.getCode());
        assertEquals("2025 年没有节假日可复制", e.getMessage());
        verify(repo, never()).save(any());
    }

    @Test
    void deleteMissingIdReturns1400() {
        when(repo.findById(999L)).thenReturn(Optional.empty());

        BizException e = assertThrows(BizException.class, () -> service.delete(999L));

        assertEquals(1400, e.getCode());
        assertEquals("节假日不存在", e.getMessage());
        verify(repo, never()).delete(any());
    }

    @Test
    void deleteExistingRowLogsTheAction() {
        Holiday existing = row(7L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY);
        when(repo.findById(7L)).thenReturn(Optional.of(existing));

        service.delete(7L);

        verify(repo).delete(existing);
        verify(opLog).record(OpAction.DELETE_HOLIDAY, "国庆节", "2026-10-01~2026-10-07 HOLIDAY");
    }

    @Test
    void listReturnsRowsOfThatYearInDateOrder() {
        List<Holiday> rows = List.of(row(1L, "元旦", "2026-01-01", "2026-01-03", HolidayType.HOLIDAY));
        when(repo.findByYearOrderByStartDateAsc(eq(2026))).thenReturn(rows);

        assertSame(rows, service.list(2026));
    }

    // ------------------------------------------------------- 年份锁的取用位置

    /** 重叠校验和写入必须落在年份锁之后，否则锁形同虚设 */
    @Test
    void createLocksTheYearBeforeCheckingOverlapAndBeforeSaving() {
        noOverlap();

        service.create(nationalDay2026());

        InOrder order = inOrder(repo);
        order.verify(repo).lockYear(2026);
        order.verify(repo).findOverlapping(NATIONAL_DAY_START, NATIONAL_DAY_END);
        order.verify(repo).save(any(Holiday.class));
    }

    @Test
    void createRejectsBadDatesBeforeTakingAnyLock() {
        assertThrows(BizException.class, () -> service.create(request("元旦", "2026-12-31",
                "2027-01-01", HolidayType.HOLIDAY, null)));

        verify(repo, never()).lockYear(anyInt());
    }

    /** 跨年搬动时旧年份和新年份都要锁，且一律升序，避免并发双方交叉等待 */
    @Test
    void updateAcrossYearsLocksBothYearsAscendingBeforeWriting() {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, "国庆节", "2026-10-01",
                "2026-10-07", HolidayType.HOLIDAY)));
        noOverlap();

        service.update(7L, request("国庆节", "2027-10-01", "2027-10-07", HolidayType.HOLIDAY, null));

        InOrder order = inOrder(repo);
        order.verify(repo).lockYear(2026);
        order.verify(repo).lockYear(2027);
        order.verify(repo).findOverlapping(LocalDate.of(2027, 10, 1), LocalDate.of(2027, 10, 7));
        order.verify(repo).save(any(Holiday.class));
    }

    @Test
    void updateWithinSameYearTakesTheYearLockOnlyOnce() {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, "国庆节", "2026-10-01",
                "2026-10-07", HolidayType.HOLIDAY)));
        noOverlap();

        service.update(7L, nationalDay2026());

        verify(repo, times(1)).lockYear(2026);
        verify(repo, never()).lockYear(2027);
    }

    /** 等锁期间这一条被别人删掉了：锁到手后必须重新确认，不能再往下写 */
    @Test
    void updateRejectsWhenTheRowDisappearedWhileWaitingForTheLock() {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, "国庆节", "2026-10-01",
                "2026-10-07", HolidayType.HOLIDAY)));
        when(repo.existsById(7L)).thenReturn(false);

        BizException e = assertThrows(BizException.class, () -> service.update(7L, nationalDay2026()));

        assertEquals(1400, e.getCode());
        verify(repo, never()).save(any());
    }

    @Test
    void deleteRejectsWhenTheRowDisappearedWhileWaitingForTheLock() {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, "国庆节", "2026-10-01",
                "2026-10-07", HolidayType.HOLIDAY)));
        when(repo.existsById(7L)).thenReturn(false);

        BizException e = assertThrows(BizException.class, () -> service.delete(7L));

        assertEquals(1400, e.getCode());
        verify(repo, never()).delete(any());
    }

    @Test
    void deleteLocksTheRowsYearBeforeDeleting() {
        when(repo.findById(7L)).thenReturn(Optional.of(row(7L, "国庆节", "2026-10-01",
                "2026-10-07", HolidayType.HOLIDAY)));

        service.delete(7L);

        InOrder order = inOrder(repo);
        order.verify(repo).lockYear(2026);
        order.verify(repo).existsById(7L);
        order.verify(repo).delete(any(Holiday.class));
    }

    /** 复制的源年份和目标年份都受锁保护，"目标年是不是空的"必须在锁内判 */
    @Test
    void copyLocksSourceAndTargetYearAscendingBeforeReadingThem() {
        when(repo.findByYearOrderByStartDateAsc(2026)).thenReturn(List.of());
        when(repo.findByYearOrderByStartDateAsc(2028)).thenReturn(List.of(
                row(1L, "国庆节", "2028-10-01", "2028-10-07", HolidayType.HOLIDAY)));

        assertEquals(1, service.copy(2028, 2026));

        InOrder order = inOrder(repo);
        order.verify(repo).lockYear(2026);
        order.verify(repo).lockYear(2028);
        order.verify(repo, times(2)).findByYearOrderByStartDateAsc(anyInt());
    }

    // ---------------------------------------------------------------- 并发回归

    /** 同年相交的创建同时进来：只有第一条成功，其余全部 1403，库里只留一行 */
    @Test
    void concurrentOverlappingCreatesInSameYearLeaveExactlyOneRow() throws Exception {
        FakeDb db = new FakeDb();
        HolidayService svc = new HolidayService(FakeDb.repository(db), opLog);

        List<Integer> codes = raceTimes(8, () -> runTx(db, () -> svc.create(nationalDay2026())));

        assertEquals(1, countCode(codes, 0), () -> "只允许一条成功：" + codes);
        assertEquals(7, countCode(codes, 1403), () -> "其余都应撞 1403：" + codes);
        assertEquals(1, db.rowCount(2026));
    }

    /** 对照组：把年份锁换成空实现（= 修复前的代码），同一个竞态确实会双双成功 */
    @Test
    void overlappingCreatesBothWinWhenTheYearLockIsDisabled() throws Exception {
        FakeDb db = new FakeDb();
        HolidayRepository unlocked = FakeDb.repository(db);
        doAnswer(inv -> null).when(unlocked).lockYear(anyInt());   // 关掉锁，复现修复前的行为
        HolidayService svc = new HolidayService(unlocked, opLog);
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Integer> a = pool.submit(() -> {
                db.begin();
                svc.create(nationalDay2026());
                aWrote.countDown();
                await(aMayCommit);   // A 的事务先不提交
                db.commit();
                return 0;
            });
            await(aWrote);
            // B 在 A 提交之前录入同一段日期：读到的仍是空的 2026 年
            int bCode = runTx(db, () -> svc.create(nationalDay2026()));
            aMayCommit.countDown();

            assertEquals(0, a.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(0, bCode, "没有年份锁时读不到对方未提交的行，两条都会成功——这正是要修的竞态");
            assertEquals(2, db.rowCount(2026));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 同样的交错，加上真实的年份锁：B 必须等 A 提交，然后被 1403 挡回 */
    @Test
    void overlappingCreateWaitsForTheHolderThenRejects() throws Exception {
        FakeDb db = new FakeDb();
        HolidayService svc = new HolidayService(FakeDb.repository(db), opLog);
        CountDownLatch aWrote = new CountDownLatch(1);
        CountDownLatch aMayCommit = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> a = pool.submit(() -> {
                db.begin();
                svc.create(nationalDay2026());
                aWrote.countDown();
                await(aMayCommit);
                db.commit();
                return 0;
            });
            await(aWrote);
            // B 与 A 抢同一年：create 会阻塞在年份锁上，直到 A 提交
            Future<Integer> b = pool.submit(() -> runTx(db, () -> svc.create(nationalDay2026())));
            aMayCommit.countDown();

            assertEquals(0, a.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1403, b.get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertEquals(1, db.rowCount(2026));
        } finally {
            pool.shutdownNow();
        }
    }

    /** 复制与往目标年新建：两边都要拿目标年的锁，不能都成功，也不能留下重叠的两行 */
    @Test
    void copyAndCreateInTargetYearNeverBothSucceed() throws Exception {
        for (int round = 1; round <= 6; round++) {
            FakeDb db = new FakeDb();
            db.seed(row(1L, "国庆节", "2026-10-01", "2026-10-07", HolidayType.HOLIDAY));
            HolidayService svc = new HolidayService(FakeDb.repository(db), opLog);
            int expected = round;

            List<Integer> codes = race(
                    () -> runTx(db, () -> svc.copy(2026, 2027)),
                    () -> runTx(db, () -> svc.create(request("撞进来的2027", "2027-10-01",
                            "2027-10-07", HolidayType.HOLIDAY, null))));

            assertEquals(1, countCode(codes, 0), () -> "第 " + expected + " 轮只允许一个成功：" + codes);
            assertEquals(1, countCode(codes, 1403) + countCode(codes, 1404),
                    () -> "另一个必须是 1403 或 1404：" + codes);
            assertEquals(1, db.rowCount(2027), () -> "第 " + expected + " 轮 2027 年只该留一行");
        }
    }

    /** 两条记录反向跨年对调：按年份升序取锁，双方不会互相等死，且各自落位 */
    @Test
    void crossYearUpdatesInOppositeDirectionsDoNotDeadlock() throws Exception {
        FakeDb db = new FakeDb();
        db.seed(row(1L, "A", "2026-05-01", "2026-05-02", HolidayType.HOLIDAY),
                row(2L, "B", "2027-06-01", "2027-06-02", HolidayType.HOLIDAY));
        HolidayService svc = new HolidayService(FakeDb.repository(db), opLog);

        List<Integer> codes = race(
                () -> runTx(db, () -> svc.update(1L, request("A", "2027-05-01", "2027-05-02",
                        HolidayType.HOLIDAY, null))),
                () -> runTx(db, () -> svc.update(2L, request("B", "2026-06-01", "2026-06-02",
                        HolidayType.HOLIDAY, null))));

        assertEquals(List.of(0, 0), codes, () -> "两条搬动都该成功（等锁即可，不该死锁或报错）：" + codes);
        assertEquals(1, db.rowCount(2026));
        assertEquals(1, db.rowCount(2027));
        List<String> names2027 = db.byYearCommitted(2027).stream().map(Holiday::getName).toList();
        assertEquals(List.of("A"), names2027);
    }

    // ---------------------------------------------------------------- 测试脚手架

    /** 成功记 0，业务异常记它的错误码；body 正常返回即提交，抛异常即回滚 */
    private static int runTx(FakeDb db, Callable<Object> body) {
        db.begin();
        try {
            body.call();
            db.commit();
            return 0;
        } catch (BizException e) {
            db.rollback();
            return e.getCode();
        } catch (Exception e) {
            db.rollback();
            throw new IllegalStateException(e);
        }
    }

    @SafeVarargs
    private static List<Integer> race(Callable<Integer>... tasks) throws Exception {
        return runConcurrently(List.of(tasks));
    }

    /** 同一个任务并发改手，模拟多个管理员同时点保存 */
    private static List<Integer> raceTimes(int times, Callable<Integer> task) throws Exception {
        List<Callable<Integer>> tasks = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            tasks.add(task);
        }
        return runConcurrently(tasks);
    }

    /** 全部线程准备就绪后一起开跑；超时即失败，不让测试挂住 */
    private static List<Integer> runConcurrently(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    await(go);
                    return task.call();
                }));
            }
            await(ready);
            go.countDown();
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> future : futures) {
                codes.add(future.get(WAIT_SECONDS, TimeUnit.SECONDS));
            }
            return codes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long countCode(List<Integer> codes, int code) {
        return codes.stream().filter(c -> c == code).count();
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "等锁或等信号超时，双方可能互相等住了");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * 内存版 holiday 表：共享数据 + 与 {@code pg_advisory_xact_lock(1400, year)} 等价的按年互斥锁。
     *
     * <p>写入先进本事务的写集，提交时才合并到已提交数据（删除记 tombstone），所以对方在事务提交前
     * 读不到——这是"先查后写"竞态能被抓起来的关键。按年锁只在事务结束（提交或回滚）时释放，
     * 与 PostgreSQL 的事务级 advisory lock 一致。</p>
     */
    private static final class FakeDb {

        private final Map<Long, Holiday> committed = new ConcurrentHashMap<>();
        private final Map<Integer, ReentrantLock> yearLocks = new ConcurrentHashMap<>();
        /** 本事务的写集：value 为 null 表示这一条被本事务删掉了 */
        private final ThreadLocal<Map<Long, Holiday>> writes = ThreadLocal.withInitial(LinkedHashMap::new);
        private final ThreadLocal<List<Integer>> heldYears = ThreadLocal.withInitial(ArrayList::new);
        private long seq;

        static HolidayRepository repository(FakeDb db) {
            HolidayRepository repo = mock(HolidayRepository.class);
            doAnswer(inv -> {
                db.lockYear(inv.getArgument(0, Integer.class));
                return null;
            }).when(repo).lockYear(anyInt());
            when(repo.save(any(Holiday.class))).thenAnswer(inv -> db.save(inv.getArgument(0)));
            when(repo.findOverlapping(any(LocalDate.class), any(LocalDate.class)))
                    .thenAnswer(inv -> db.overlapping(inv.getArgument(0), inv.getArgument(1)));
            when(repo.findByYearOrderByStartDateAsc(anyInt()))
                    .thenAnswer(inv -> db.byYear(inv.getArgument(0, Integer.class)));
            when(repo.findById(any(Long.class))).thenAnswer(inv -> db.find(inv.getArgument(0)));
            when(repo.existsById(any(Long.class))).thenAnswer(inv -> db.exists(inv.getArgument(0)));
            doAnswer(inv -> {
                db.remove(inv.getArgument(0, Holiday.class));
                return null;
            }).when(repo).delete(any(Holiday.class));
            return repo;
        }

        /** 直接放进"已提交"数据，用于准备前置状态 */
        synchronized void seed(Holiday... rows) {
            for (Holiday row : rows) {
                committed.put(row.getId(), snapshot(row));
                seq = Math.max(seq, row.getId());
            }
        }

        void begin() {
            writes.get().clear();
        }

        void commit() {
            synchronized (this) {
                writes.get().forEach((id, row) -> {
                    if (row == null) {
                        committed.remove(id);
                    } else {
                        committed.put(id, row);
                    }
                });
                writes.get().clear();
            }
            releaseLocks();
        }

        void rollback() {
            writes.get().clear();
            releaseLocks();
        }

        /** 事务级锁：拿到后一直持到事务结束；等锁的一方在此期间读不到持锁方的写入 */
        void lockYear(int year) {
            ReentrantLock lock = yearLocks.computeIfAbsent(year, y -> new ReentrantLock());
            lock.lock();
            heldYears.get().add(year);
        }

        private void releaseLocks() {
            List<Integer> years = heldYears.get();
            for (int i = years.size() - 1; i >= 0; i--) {
                yearLocks.get(years.get(i)).unlock();
            }
            years.clear();
        }

        synchronized Holiday save(Holiday entity) {
            if (entity.getId() == null) {
                entity.setId(++seq);
            }
            writes.get().put(entity.getId(), snapshot(entity));
            return entity;
        }

        synchronized Optional<Holiday> find(Long id) {
            return Optional.ofNullable(visible().get(id)).map(FakeDb::snapshot);
        }

        synchronized boolean exists(Long id) {
            return visible().get(id) != null;
        }

        synchronized List<Holiday> overlapping(LocalDate start, LocalDate end) {
            return visible().values().stream()
                    .filter(row -> row != null)
                    .filter(row -> !row.getStartDate().isAfter(end) && !row.getEndDate().isBefore(start))
                    .map(FakeDb::snapshot)
                    .toList();
        }

        synchronized List<Holiday> byYear(int year) {
            return byYearOf(visible(), year);
        }

        synchronized List<Holiday> byYearCommitted(int year) {
            return byYearOf(new LinkedHashMap<>(committed), year);
        }

        private List<Holiday> byYearOf(Map<Long, Holiday> source, int year) {
            return source.values().stream()
                    .filter(row -> row != null && row.getYear() == year)
                    .map(FakeDb::snapshot)
                    .sorted(Comparator.comparing(Holiday::getStartDate))
                    .toList();
        }

        /** 删除只进写集，别的事务在提交前仍然看得到这一条 */
        synchronized void remove(Holiday entity) {
            writes.get().put(entity.getId(), null);
        }

        /** 已提交的行数（按年） */
        synchronized long rowCount(int year) {
            return committed.values().stream().filter(row -> row.getYear() == year).count();
        }

        /** 本事务看到的是"已提交 + 自己写过"的并集，与数据库读自己的写入一致 */
        private Map<Long, Holiday> visible() {
            Map<Long, Holiday> view = new LinkedHashMap<>(committed);
            view.putAll(writes.get());
            return view;
        }

        private static Holiday snapshot(Holiday src) {
            Holiday copy = new Holiday();
            copy.setId(src.getId());
            copy.setYear(src.getYear());
            copy.setName(src.getName());
            copy.setStartDate(src.getStartDate());
            copy.setEndDate(src.getEndDate());
            copy.setType(src.getType());
            copy.setRemark(src.getRemark());
            copy.setCreatedAt(src.getCreatedAt());
            return copy;
        }
    }
}
