package com.devmind.auth.controller;

import com.devmind.auth.AuthService;
import com.devmind.auth.dto.LoginRequest;
import com.devmind.auth.dto.LoginResponse;
import com.devmind.auth.dto.LogoutRequest;
import com.devmind.auth.dto.RefreshRequest;
import com.devmind.auth.dto.UserView;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
    private final UserRepository userRepo;

    public AuthController(AuthService authService, UserRepository userRepo) {
        this.authService = authService;
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
}
