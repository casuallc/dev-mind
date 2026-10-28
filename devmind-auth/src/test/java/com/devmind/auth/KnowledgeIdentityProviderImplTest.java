package com.devmind.auth;

import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.exception.DevMindException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeIdentityProviderImplTest {
    private final UserRepository users = mock(UserRepository.class);
    private final KnowledgeIdentityProviderImpl identities = new KnowledgeIdentityProviderImpl(users);
    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void missingIdentityDoesNotResolveTheLocalAccount() {
        assertThrows(DevMindException.class, identities::current);
        verifyNoInteractions(users);
    }
    @Test void currentRoleAndDisabledStatusAreReadFromDatabase() {
        var user = new UserEntity(); user.setUsername("reader");
        user.setStatus("ACTIVE"); user.setRole("VIEWER");
        when(users.findKnowledgeIdentity("reader")).thenAnswer(inv -> Optional.of(new UserRepository.KnowledgeIdentityRef() {
            public String getUsername() { return user.getUsername(); }
            public String getRole() { return user.getRole(); }
            public String getStatus() { return user.getStatus(); }
        }));
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                new DevMindPrincipal("reader", "ADMIN"), null, List.of()));
        assertEquals("VIEWER", identities.current().role());
        user.setStatus("DISABLED");
        assertThrows(DevMindException.class, identities::current);
    }
}
