package com.hospital.pbb.oplog;

import com.hospital.pbb.user.AuthUser;
import com.hospital.pbb.user.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OpLogServiceTest {

    private OperationLogRepository repository;
    private OpLogService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        repository = mock(OperationLogRepository.class);
        service = new OpLogService(repository);
        request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    private void loginAs(Long id, String username) {
        AuthUser principal = new AuthUser(id, username, Role.MEMBER, 3L);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null));
    }

    private OperationLog captured() {
        ArgumentCaptor<OperationLog> captor = ArgumentCaptor.forClass(OperationLog.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void recordUsesCurrentUserAndRemoteAddr() {
        loginAs(7L, "IT001");

        service.record(OpAction.CREATE_STAFF, "IT002", "x");

        OperationLog saved = captured();
        assertEquals(7L, saved.getUserId());
        assertEquals("IT001", saved.getUsername());
        assertEquals("新增人员", saved.getAction());
        assertEquals("IT002", saved.getTarget());
        assertEquals("127.0.0.1", saved.getIp());
    }

    @Test
    void recordTakesFirstIpFromForwardedFor() {
        loginAs(7L, "IT001");
        request.addHeader("X-Forwarded-For", "127.0.0.2, 127.0.0.3");

        service.record(OpAction.CREATE_STAFF, "IT002", "x");

        assertEquals("127.0.0.2", captured().getIp());
    }

    @Test
    void recordFallsBackToRemoteAddrWhenForwardedForIsMalformed() {
        request.addHeader("X-Forwarded-For", ",,");

        service.recordAs(1L, "admin", OpAction.LOGIN, "admin", null);

        assertEquals("127.0.0.1", captured().getIp());
    }

    @Test
    void recordFallsBackToRemoteAddrWhenFirstForwardedIpIsTooLong() {
        request.addHeader("X-Forwarded-For", "f".repeat(46) + ", 127.0.0.2");

        service.recordAs(1L, "admin", OpAction.LOGIN, "admin", null);

        assertEquals("127.0.0.1", captured().getIp());
    }

    @Test
    void recordKeepsFirstForwardedIpAtMaxLength() {
        String ip45 = "f".repeat(45);
        request.addHeader("X-Forwarded-For", ip45 + ", 127.0.0.2");

        service.recordAs(1L, "admin", OpAction.LOGIN, "admin", null);

        assertEquals(ip45, captured().getIp());
    }

    @Test
    void recordWithoutLoginLeavesUserNull() {
        service.record(OpAction.CREATE_STAFF, "IT002", "x");

        OperationLog saved = captured();
        assertNull(saved.getUserId());
        assertNull(saved.getUsername());
    }

    @Test
    void recordTruncatesLongDetail() {
        service.record(OpAction.UPDATE_STAFF, "IT002", "详".repeat(600));

        assertEquals(500, captured().getDetail().length());
    }

    @Test
    void recordAsKeepsExplicitUserAndNullDetail() {
        service.recordAs(1L, "admin", OpAction.LOGIN, "admin", null);

        OperationLog saved = captured();
        assertEquals(1L, saved.getUserId());
        assertEquals("admin", saved.getUsername());
        assertEquals("登录", saved.getAction());
        assertNull(saved.getDetail());
    }

    @Test
    void recordOutsideRequestContextHasNullIp() {
        RequestContextHolder.resetRequestAttributes();

        service.recordAs(1L, "admin", OpAction.LOGIN, "admin", null);

        assertNull(captured().getIp());
    }

    private void stubEachDeleteReturnsOne() {
        when(repository.deleteByUserId(7L)).thenReturn(1);
        when(repository.deleteByTarget("A01")).thenReturn(1);
        when(repository.deleteByActionAndTargetLike(OpAction.UPDATE_SCHEDULE, "张三 %")).thenReturn(1);
        when(repository.deleteByActionAndDetail(OpAction.SET_DUTY_PHONE, "张三")).thenReturn(1);
    }

    @Test
    void purgeStaffDeletesAllFourKindsInOrder() {
        stubEachDeleteReturnsOne();

        assertEquals(4, service.purgeStaff(7L, "A01", "张三", true));

        InOrder inOrder = inOrder(repository);
        inOrder.verify(repository).deleteByUserId(7L);
        inOrder.verify(repository).deleteByTarget("A01");
        inOrder.verify(repository).deleteByActionAndTargetLike("修改排班", "张三 %");
        inOrder.verify(repository).deleteByActionAndDetail("设置值班电话", "张三");
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    void purgeStaffSkipsUserIdRuleWhenUserIdIsNull() {
        stubEachDeleteReturnsOne();

        assertEquals(3, service.purgeStaff(null, "A01", "张三", true));

        verify(repository, never()).deleteByUserId(any());
        verify(repository).deleteByTarget("A01");
        verify(repository).deleteByActionAndTargetLike("修改排班", "张三 %");
        verify(repository).deleteByActionAndDetail("设置值班电话", "张三");
    }

    @Test
    void purgeStaffSkipsNameRulesWhenByNameIsFalse() {
        stubEachDeleteReturnsOne();

        assertEquals(2, service.purgeStaff(7L, "A01", "张三", false));

        verify(repository).deleteByUserId(7L);
        verify(repository).deleteByTarget("A01");
        verify(repository, never()).deleteByActionAndTargetLike(any(), any());
        verify(repository, never()).deleteByActionAndDetail(any(), any());
    }

    @Test
    void purgeStaffSkipsNameRulesWhenNameContainsWildcard() {
        stubEachDeleteReturnsOne();

        assertEquals(2, service.purgeStaff(7L, "A01", "张_三", true));

        verify(repository).deleteByUserId(7L);
        verify(repository).deleteByTarget("A01");
        verify(repository, never()).deleteByActionAndTargetLike(any(), any());
        verify(repository, never()).deleteByActionAndDetail(any(), any());
    }
}
