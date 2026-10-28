package com.devmind.auth;

import com.devmind.auth.repo.UserRepository;
import com.devmind.auth.security.DevMindPrincipal;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.knowledge.KnowledgeIdentityProvider;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Re-read account status and role, including for authenticated requests with an older JWT. */
@Component
public class KnowledgeIdentityProviderImpl implements KnowledgeIdentityProvider {
    private final UserRepository users;
    public KnowledgeIdentityProviderImpl(UserRepository users) { this.users = users; }

    @Override public Subject current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()
                || !(auth.getPrincipal() instanceof DevMindPrincipal principal)) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "知识访问需要有效身份");
        }
        return resolve(principal.username());
    }

    @Override public Subject resolve(String actor) {
        if (actor == null || actor.isBlank()) {
            throw new DevMindException(ErrorCode.UNAUTHORIZED, "知识访问需要有效身份");
        }
        return users.findKnowledgeIdentity(actor).filter(u -> "ACTIVE".equals(u.getStatus()))
                .filter(u -> java.util.Set.of("ADMIN", "DEVELOPER", "VIEWER").contains(String.valueOf(u.getRole())))
                .map(u -> new Subject(u.getUsername(), u.getRole()))
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "知识访问身份已失效"));
    }
}
