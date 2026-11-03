package com.devmind.auth.authz;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthorizationDecisionRepository extends JpaRepository<AuthorizationDecisionEntity, Long> {
}
