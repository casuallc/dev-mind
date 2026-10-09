package com.devmind.common.egress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EgressHostMatcherTest {

    @Test
    void exactMatchCaseAndPortInsensitive() {
        assertTrue(EgressHostMatcher.matches("gitlab.corp.com", "GitLab.Corp.COM"));
        assertTrue(EgressHostMatcher.matches("gitlab.corp.com", "gitlab.corp.com:8443"));
        assertFalse(EgressHostMatcher.matches("gitlab.corp.com", "other.corp.com"));
        assertFalse(EgressHostMatcher.matches("gitlab.corp.com", "gitlab.corp.com.evil.com"));
    }

    @Test
    void wildcardSuffix() {
        assertTrue(EgressHostMatcher.matches("*.corp.com", "gitlab.corp.com"));
        assertTrue(EgressHostMatcher.matches("*.corp.com", "a.b.corp.com"));
        // 运维直觉：*.corp.com 覆盖 corp.com 本身
        assertTrue(EgressHostMatcher.matches("*.corp.com", "corp.com"));
        assertFalse(EgressHostMatcher.matches("*.corp.com", "corp.com.evil.com"));
        assertFalse(EgressHostMatcher.matches("*.corp.com", "notcorp.com"));
    }

    @Test
    void normalizePattern() {
        assertEquals("*.corp.com", EgressHostMatcher.normalizePattern("  *.Corp.COM. "));
        assertEquals("gitlab.corp.com", EgressHostMatcher.normalizePattern("GitLab.corp.com:443"));
        assertEquals("", EgressHostMatcher.normalizePattern(null));
        assertEquals("", EgressHostMatcher.normalizePattern("*."));
    }

    @Test
    void blankNeverMatches() {
        assertFalse(EgressHostMatcher.matches("", "a.com"));
        assertFalse(EgressHostMatcher.matches("a.com", ""));
        assertFalse(EgressHostMatcher.matches(null, null));
    }

    @Test
    void ipLiteralHost() {
        assertTrue(EgressHostMatcher.matches("10.0.0.8", "10.0.0.8:8080"));
        assertTrue(EgressHostMatcher.matches("[2001:db8::1]", "[2001:db8::1]"));
    }
}
