package com.hospital.pbb.oplog;

import com.hospital.pbb.user.AuthUser;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 统一的写操作留痕入口（设计 §6），后续各模块的写操作都调用这里。
 *
 * <p><b>detail 里禁止写密码、token、手机号</b>，只写能定位到对象的标识。</p>
 */
@Service
public class OpLogService {

    private static final int TARGET_MAX = 100;
    private static final int DETAIL_MAX = 500;
    private static final int IP_MAX = 45;
    private static final String X_FORWARDED_FOR = "X-Forwarded-For";

    private final OperationLogRepository repository;

    public OpLogService(OperationLogRepository repository) {
        this.repository = repository;
    }

    /** 当前登录用户从 SecurityContext 取（principal 为 AuthUser）；未登录则 userId/username 为 null */
    public void record(String action, String target, String detail) {
        Long userId = null;
        String username = null;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AuthUser current) {
            userId = current.id();
            username = current.username();
        }
        recordAs(userId, username, action, target, detail);
    }

    /** 登录场景：此时 SecurityContext 里还没有用户，显式传入 */
    public void recordAs(Long userId, String username, String action, String target, String detail) {
        OperationLog log = new OperationLog();
        log.setUserId(userId);
        log.setUsername(username);
        log.setAction(action);
        log.setTarget(truncate(target, TARGET_MAX));
        log.setDetail(truncate(detail, DETAIL_MAX));
        log.setIp(currentIp());
        repository.save(log);
    }

    /** 反向代理下取 X-Forwarded-For 的第一个地址，否则取连接的远端地址；不在请求上下文里时为 null */
    private static String currentIp() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return null;
        }
        HttpServletRequest req = attrs.getRequest();
        String forwarded = req.getHeader(X_FORWARDED_FOR);
        if (forwarded != null) {
            int comma = forwarded.indexOf(',');
            String first = (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
            // 请求头内容由客户端可控，空白或长度超过字段长度时不能直接入库
            if (!first.isEmpty() && first.length() <= IP_MAX) {
                return first;
            }
        }
        return req.getRemoteAddr();
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }
}
