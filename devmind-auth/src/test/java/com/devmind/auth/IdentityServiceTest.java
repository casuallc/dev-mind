package com.devmind.auth;

import com.devmind.auth.config.AuthProperties;
import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class IdentityServiceTest {

    private IdentityService service() {
        return new IdentityService(mock(UserRepository.class),
                new BCryptPasswordEncoder(), new AuthProperties());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 无认证上下文回退local() {
        SecurityContextHolder.clearContext();
        assertEquals("local", service().currentActor());
    }

    @Test
    void 有认证上下文返回真实用户名() {
        var auth = new UsernamePasswordAuthenticationToken(
                new DevMindPrincipal("bob", "DEVELOPER"), null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER")));
        SecurityContextHolder.getContext().setAuthentication(auth);
        assertEquals("bob", service().currentActor());
    }
}
