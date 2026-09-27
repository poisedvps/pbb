package com.hospital.pbb.user;

import com.hospital.pbb.common.ApiResponse;
import com.hospital.pbb.user.dto.CreateUserRequest;
import com.hospital.pbb.user.dto.TempPasswordVO;
import com.hospital.pbb.user.dto.UserVO;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {

    private final UserAdminService userAdminService;

    public UserController(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }

    @GetMapping("")
    public ApiResponse<List<UserVO>> list() {
        return ApiResponse.ok(userAdminService.list());
    }

    @PostMapping("")
    public ApiResponse<TempPasswordVO> createScreenUser(@Valid @RequestBody CreateUserRequest req) {
        return ApiResponse.ok(userAdminService.createScreenUser(req));
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<TempPasswordVO> resetPassword(@PathVariable Long id) {
        return ApiResponse.ok(userAdminService.resetPassword(id));
    }

    @PostMapping("/{id}/unlock")
    public ApiResponse<Void> unlock(@PathVariable Long id) {
        userAdminService.unlock(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/disable")
    public ApiResponse<Void> disable(@PathVariable Long id, @AuthenticationPrincipal AuthUser current) {
        userAdminService.disable(id, current.id());
        return ApiResponse.ok(null);
    }

    @PostMapping("/{id}/enable")
    public ApiResponse<Void> enable(@PathVariable Long id) {
        userAdminService.enable(id);
        return ApiResponse.ok(null);
    }
}
