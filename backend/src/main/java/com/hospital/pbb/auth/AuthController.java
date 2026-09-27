package com.hospital.pbb.auth;

import com.hospital.pbb.auth.dto.ChangePasswordRequest;
import com.hospital.pbb.auth.dto.LoginRequest;
import com.hospital.pbb.auth.dto.LoginResponse;
import com.hospital.pbb.auth.dto.UserInfo;
import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.oplog.OpAction;
import com.hospital.pbb.oplog.OpLogService;
import com.hospital.pbb.user.AuthUser;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final OpLogService opLog;

    public AuthController(AuthService authService, OpLogService opLog) {
        this.authService = authService;
        this.opLog = opLog;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest req) {
        return ApiResponse.ok(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public ApiResponse<UserInfo> me(@AuthenticationPrincipal AuthUser user) {
        return ApiResponse.ok(authService.me(user.id()));
    }

    /** 后端无状态，登出只留痕，token 由前端自行清除 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout(@AuthenticationPrincipal AuthUser user) {
        opLog.record(OpAction.LOGOUT, user.username(), null);
        return ApiResponse.ok(null);
    }

    @PostMapping("/change-password")
    public ApiResponse<Void> changePassword(@AuthenticationPrincipal AuthUser user,
                                            @Valid @RequestBody ChangePasswordRequest req) {
        authService.changePassword(user.id(), req.oldPassword(), req.newPassword());
        return ApiResponse.ok(null);
    }
}
