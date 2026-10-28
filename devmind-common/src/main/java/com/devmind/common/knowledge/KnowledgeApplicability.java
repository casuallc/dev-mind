package com.devmind.common.knowledge;

import java.util.*;

/** Deterministic applicability predicates. No LLM judgment, string version ordering or cross-group flattening. */
public final class KnowledgeApplicability {
    private KnowledgeApplicability() { }
    public enum Match { MATCH, NO_MATCH, UNKNOWN }
    public enum Role { CURRENT, SOURCE, TARGET }
    public record Field(Role role, String component, String attribute) {
        public Field {
            Objects.requireNonNull(role);
            if (component == null || component.isBlank() || !Set.of("product", "version", "patch", "os", "architecture", "deployment", "edition").contains(attribute))
                throw new IllegalArgumentException("Unsupported applicability field");
        }
    }
    public record Clause(Map<Field, Set<String>> constraints) {
        public Clause {
            if (constraints == null || constraints.isEmpty()) throw new IllegalArgumentException("Empty condition is not universal");
            var copy = new LinkedHashMap<Field, Set<String>>();
            constraints.forEach((field, values) -> {
                Objects.requireNonNull(field);
                if (values == null || values.isEmpty() || values.stream().anyMatch(v -> v == null || v.isBlank()))
                    throw new IllegalArgumentException("Empty applicability value");
                copy.put(field, Set.copyOf(values));
            });
            constraints = Map.copyOf(copy);
        }
        public Match evaluate(Map<Field, String> environment) {
            boolean unknown = false;
            for (var condition : constraints.entrySet()) {
                String value = environment.get(condition.getKey());
                if (value == null || value.isBlank()) unknown = true;
                else if (!condition.getValue().contains(value)) return Match.NO_MATCH;
            }
            return unknown ? Match.UNKNOWN : Match.MATCH;
        }
    }
    public record Rule(boolean confirmedUniversal, List<Clause> includes, List<Clause> excludes) {
        public Rule {
            includes = List.copyOf(Objects.requireNonNull(includes));
            excludes = List.copyOf(Objects.requireNonNull(excludes));
            if (!confirmedUniversal && includes.isEmpty()) throw new IllegalArgumentException("Unknown applicability must be confirmed");
            if (confirmedUniversal && !includes.isEmpty()) throw new IllegalArgumentException("Universal and conditional modes cannot be combined");
        }
        public Match evaluate(Map<Field, String> environment) {
            Objects.requireNonNull(environment);
            Match included = confirmedUniversal ? Match.MATCH : any(includes, environment);
            Match excluded = any(excludes, environment);
            if (included == Match.NO_MATCH || excluded == Match.MATCH) return Match.NO_MATCH;
            if (included == Match.UNKNOWN || excluded == Match.UNKNOWN) return Match.UNKNOWN;
            return Match.MATCH;
        }
        /** A section can narrow its parent but cannot widen it. */
        public Match evaluateSection(Rule section, Map<Field, String> environment) {
            Match parent = evaluate(environment);
            Match child = section == null ? Match.MATCH : section.evaluate(environment);
            if (parent == Match.NO_MATCH || child == Match.NO_MATCH) return Match.NO_MATCH;
            return parent == Match.UNKNOWN || child == Match.UNKNOWN ? Match.UNKNOWN : Match.MATCH;
        }
    }
    private static Match any(List<Clause> clauses, Map<Field, String> environment) {
        boolean unknown = false;
        for (Clause clause : clauses) {
            Match match = clause.evaluate(environment);
            if (match == Match.MATCH) return Match.MATCH;
            unknown |= match == Match.UNKNOWN;
        }
        return unknown ? Match.UNKNOWN : Match.NO_MATCH;
    }
}
