package com.devmind.common.knowledge;

/** Trusted identity boundary. No anonymous/local fallback is allowed for knowledge access. */
public interface KnowledgeIdentityProvider {
    Subject current();
    /** For server-owned task/tool contexts only; never bind this value from request parameters. */
    Subject resolve(String actor);
    record Subject(String actor, String role) { }
}
