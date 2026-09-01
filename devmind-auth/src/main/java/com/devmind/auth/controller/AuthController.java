package com.devmind.auth.controller;

import com.devmind.auth.AuthService;
import com.devmind.auth.UserAdminService;
import com.devmind.auth.dto.ChangePasswordRequest;
import com.devmind.auth.dto.CreateUserRequest;
import com.devmind.auth.dto.LoginRequest;
import com.devmind.auth.dto.LoginResponse;
import com.devmind.auth.dto.LogoutRequest;
import com.devmind.auth.dto.RefreshRequest;
import com.devmind.auth.dto.ResetPasswordRequest;
import com.devmind.auth.dto.UpdateUserRequest;
import com.devmind.auth.dto.UserView;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * CAP-01 认证端点。login/refresh/logout 在过滤器链中 permitAll；me 需认证。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final UserAdminService userAdminService;
    private final UserRepository userRepo;

    public AuthController(AuthService authService, UserAdminService userAdminService, UserRepository userRepo) {
        this.authService = authService;
        this.userAdminService = userAdminService;
        this.userRepo = userRepo;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return authService.login(req.username(), req.password());
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest req) {
        return authService.refresh(req.refreshToken());
    }

    @PostMapping("/logout")
    public void logout(@RequestBody(required = false) LogoutRequest req) {
        authService.logout(req == null ? null : req.refreshToken());
    }

    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal DevMindPrincipal principal) {
        if (principal == null) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        return userRepo.findByUsername(principal.username())
                .map(authService::toView)
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "用户不存在"));
    }

    @PostMapping("/change-password")
    public void changePassword(@AuthenticationPrincipal DevMindPrincipal principal,
                               @Valid @RequestBody ChangePasswordRequest req) {
        if (principal == null) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "未登录");
        }
        userAdminService.changePassword(principal.username(), req.oldPassword(), req.newPassword());
    }

    // ---------------- 用户管理（仅 ADMIN，过滤器链控制） ----------------

    @GetMapping("/users")
    public java.util.List<UserView> listUsers() {
        return userAdminService.list();
    }

    @PostMapping("/users")
    public UserView createUser(@Valid @RequestBody CreateUserRequest req) {
        return userAdminService.create(req);
    }

    @PutMapping("/users/{id}")
    public UserView updateUser(@PathVariable String id, @RequestBody UpdateUserRequest req,
                               @AuthenticationPrincipal DevMindPrincipal principal) {
        return userAdminService.update(id, req, principal == null ? null : principal.username());
    }

    @PostMapping("/users/{id}/reset-password")
    public void resetPassword(@PathVariable String id, @Valid @RequestBody ResetPasswordRequest req) {
        userAdminService.resetPassword(id, req.password());
    }
}
