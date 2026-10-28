package com.devmind.common.knowledge;

import java.util.*;
import org.junit.jupiter.api.Test;
import static com.devmind.common.knowledge.KnowledgeApplicability.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeApplicabilityTest {
    private final Field version = new Field(Role.CURRENT, "server", "version");
    private final Field os = new Field(Role.CURRENT, "server", "os");
    private Clause clause(String v, String system) { return new Clause(Map.of(version, Set.of(v), os, Set.of(system))); }

    @Test void groupedConditionsDoNotAdmitUnsupportedCrossProduct() {
        var rule = new Rule(false, List.of(clause("8.0", "Linux"), clause("8.1", "Windows")), List.of());
        assertEquals(Match.MATCH, rule.evaluate(Map.of(version, "8.0", os, "Linux")));
        assertEquals(Match.MATCH, rule.evaluate(Map.of(version, "8.1", os, "Windows")));
        assertEquals(Match.NO_MATCH, rule.evaluate(Map.of(version, "8.0", os, "Windows")));
        assertEquals(Match.UNKNOWN, rule.evaluate(Map.of(version, "8.0")));
    }
    @Test void unknownPatchExclusionCannotAuthorizeAnExecutableAnswer() {
        var patch = new Field(Role.CURRENT, "server", "patch");
        var rule = new Rule(false, List.of(clause("8.0", "Linux")), List.of(new Clause(Map.of(patch, Set.of("P3")))));
        assertEquals(Match.UNKNOWN, rule.evaluate(Map.of(version, "8.0", os, "Linux")));
        assertEquals(Match.NO_MATCH, rule.evaluate(Map.of(version, "8.0", os, "Linux", patch, "P3")));
        assertEquals(Match.MATCH, rule.evaluate(Map.of(version, "8.0", os, "Linux", patch, "P4")));
    }
    @Test void sourceTargetAndDependencyVersionsStayIndependent() {
        var source = new Field(Role.SOURCE, "server", "version");
        var target = new Field(Role.TARGET, "server", "version");
        var database = new Field(Role.TARGET, "database", "version");
        var rule = new Rule(false, List.of(new Clause(Map.of(source, Set.of("8.0"), target, Set.of("8.1"), database, Set.of("5.7")))), List.of());
        assertEquals(Match.MATCH, rule.evaluate(Map.of(source, "8.0", target, "8.1", database, "5.7")));
        assertEquals(Match.NO_MATCH, rule.evaluate(Map.of(source, "8.1", target, "8.0", database, "5.7")));
        assertEquals(Match.UNKNOWN, rule.evaluate(Map.of(source, "8.0", target, "8.1")));
    }
    @Test void noImplicitUniversalOrUnregisteredVersionOrdering() {
        assertThrows(IllegalArgumentException.class, () -> new Clause(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new Rule(false, List.of(), List.of()));
        var rule = new Rule(false, List.of(new Clause(Map.of(version, Set.of("R9", "R10")))), List.of());
        assertEquals(Match.NO_MATCH, rule.evaluate(Map.of(version, "R11")));
        var section = new Rule(true, List.of(), List.of());
        assertEquals(Match.NO_MATCH, rule.evaluateSection(section, Map.of(version, "R11")));
    }
    @Test void rulesAreImmutableAfterApproval() {
        var values = new HashSet<>(Set.of("8.0"));
        var conditions = new HashMap<Field, Set<String>>(); conditions.put(version, values);
        var clause = new Clause(conditions); values.add("8.1"); conditions.clear();
        assertEquals(Match.NO_MATCH, clause.evaluate(Map.of(version, "8.1")));
    }
}
