package com.hospital.pbb.oplog;

import com.hospital.pbb.common.PageVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OpLogQueryService 的分页参数归一化与筛选分支（M3-09）。
 */
class OpLogQueryServiceTest {

    private OperationLogRepository repository;
    private OpLogQueryService service;

    @BeforeEach
    void setUp() {
        repository = mock(OperationLogRepository.class);
        // 4 个分支的默认返回值：未命中数据时就是空页，让不关心内容的用例少配一层 mock
        Page<OperationLog> empty = pageOf();
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(empty);
        when(repository.findByUsernameOrderByIdDesc(anyString(), any(Pageable.class))).thenReturn(empty);
        when(repository.findByActionOrderByIdDesc(anyString(), any(Pageable.class))).thenReturn(empty);
        when(repository.findByUsernameAndActionOrderByIdDesc(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(empty);
        service = new OpLogQueryService(repository);
    }

    private static OperationLog log(long id, String username, String action) {
        OperationLog row = new OperationLog();
        row.setId(id);
        row.setUsername(username);
        row.setAction(action);
        return row;
    }

    private static Page<OperationLog> pageOf(OperationLog... rows) {
        return new PageImpl<>(List.of(rows));
    }

    /** 捕获传给仓库的 Pageable，避免依赖 PageRequest 的 equals 实现 */
    private Pageable captureUnfilteredPageable() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAllByOrderByIdDesc(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------------ 分页参数归一化

    @Test
    void noFilterUsesFindAllWithRequestedPageAndSize() {
        Page<OperationLog> rows = pageOf(log(1L, "admin", "登录"));
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(rows);

        PageVO<OperationLog> vo = service.page(0, 20, null, null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAllByOrderByIdDesc(captor.capture());
        Pageable used = captor.getValue();
        assertEquals(0, used.getPageNumber());
        assertEquals(20, used.getPageSize());
        // 倒序由方法名 OrderByIdDesc 决定，不能在这里额外加排序把 id 倒序改成别的
        assertTrue(used.getSort().isUnsorted(), () -> "排序交给方法名，Pageable 不该带排序：" + used.getSort());
        assertEquals(rows.getTotalElements(), vo.total());
        assertEquals(rows.getContent(), vo.items());
    }

    /** page 是负数、size 是 0 或超大值时不能把非法值直接交给 PageRequest */
    @Test
    void negativePageAndOversizedSizeAreNormalized() {
        when(repository.findByUsernameOrderByIdDesc(anyString(), any(Pageable.class))).thenReturn(pageOf());

        service.page(-1, 500, "admin", "");

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUsernameOrderByIdDesc(eq("admin"), captor.capture());
        Pageable used = captor.getValue();
        assertEquals(0, used.getPageNumber());
        assertEquals(100, used.getPageSize());
    }

    @Test
    void sizeBelowOneFallsBackToDefaultTwenty() {
        service.page(0, 0, null, null);

        Pageable used = captureUnfilteredPageable();
        assertEquals(20, used.getPageSize());
    }

    @ParameterizedTest
    @CsvSource({"1,1", "99,99", "100,100", "101,100", "500,100", "-5,20"})
    void sizeIsClampedIntoOneToOneHundred(int requested, int expected) {
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(pageOf());

        service.page(2, requested, null, null);

        Pageable used = captureUnfilteredPageable();
        assertEquals(expected, used.getPageSize(), "size=" + requested);
        assertEquals(2, used.getPageNumber());
    }

    // ---------------------------------------------------------------- 筛选分支

    @Test
    void onlyUsernameFiltersByUsername() {
        when(repository.findByUsernameOrderByIdDesc(anyString(), any(Pageable.class))).thenReturn(pageOf());

        service.page(0, 20, "admin", null);

        verify(repository).findByUsernameOrderByIdDesc(eq("admin"), any(Pageable.class));
        verify(repository, never()).findAllByOrderByIdDesc(any(Pageable.class));
        verify(repository, never()).findByActionOrderByIdDesc(anyString(), any(Pageable.class));
    }

    @Test
    void onlyActionFiltersByAction() {
        when(repository.findByActionOrderByIdDesc(anyString(), any(Pageable.class))).thenReturn(pageOf());

        service.page(0, 20, null, "发布排班");

        verify(repository).findByActionOrderByIdDesc(eq("发布排班"), any(Pageable.class));
        verify(repository, never()).findAllByOrderByIdDesc(any(Pageable.class));
        verify(repository, never()).findByUsernameOrderByIdDesc(anyString(), any(Pageable.class));
    }

    @Test
    void bothFiltersUseTheCombinedQuery() {
        when(repository.findByUsernameAndActionOrderByIdDesc(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        service.page(1, 30, "admin", "登录");

        verify(repository).findByUsernameAndActionOrderByIdDesc(eq("admin"), eq("登录"),
                eq(PageRequest.of(1, 30)));
        verify(repository, never()).findAllByOrderByIdDesc(any(Pageable.class));
    }

    /** 空白筛选框等于不筛选，不能真的拿去 where username = '  ' */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void blankFiltersAreIgnored(String value) {
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(pageOf());

        service.page(0, 20, value, value);

        verify(repository).findAllByOrderByIdDesc(any(Pageable.class));
        verify(repository, never()).findByUsernameOrderByIdDesc(anyString(), any(Pageable.class));
        verify(repository, never()).findByActionOrderByIdDesc(anyString(), any(Pageable.class));
        verify(repository, never()).findByUsernameAndActionOrderByIdDesc(anyString(), anyString(),
                any(Pageable.class));
    }

    /** 筛选值两侧的空格是误输入，条件里要用去掉空格后的值 */
    @Test
    void filterValueIsTrimmedBeforeQuerying() {
        when(repository.findByUsernameAndActionOrderByIdDesc(anyString(), anyString(), any(Pageable.class)))
                .thenReturn(pageOf());

        service.page(0, 20, "  admin  ", "  登录  ");

        verify(repository).findByUsernameAndActionOrderByIdDesc(eq("admin"), eq("登录"),
                any(Pageable.class));
    }

    // ------------------------------------------------------------------ 返回值

    @Test
    void returnsTotalElementsAndContentOfTheQuery() {
        List<OperationLog> items = List.of(log(3L, "admin", "登录"), log(2L, "nurse01", "修改排班"));
        when(repository.findAllByOrderByIdDesc(any(Pageable.class)))
                .thenReturn(new PageImpl<>(items, PageRequest.of(0, 2), 57L));

        PageVO<OperationLog> vo = service.page(0, 2, null, null);

        assertEquals(57L, vo.total(), "total 是符合条件的总行数，不是当前页条数");
        assertEquals(items, vo.items());
        assertEquals(2, vo.items().size());
    }

    @Test
    void emptyResultReturnsZeroTotalAndEmptyItems() {
        when(repository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(pageOf());

        PageVO<OperationLog> vo = service.page(9, 20, null, null);

        assertEquals(0L, vo.total());
        assertTrue(vo.items().isEmpty());
    }
}
