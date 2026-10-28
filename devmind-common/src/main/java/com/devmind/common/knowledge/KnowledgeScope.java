package com.devmind.common.knowledge;

import java.util.*;
import static com.devmind.common.knowledge.KnowledgeApplicability.*;

/** JSON-safe, immutable contract for applicability and confirmed question environments. */
public record KnowledgeScope(boolean confirmedUniversal, List<Group> includes, List<Group> excludes) {
    public record Condition(Role role, String component, String attribute, Set<String> values) {
        public Condition { Objects.requireNonNull(role); values = Set.copyOf(values); new Clause(Map.of(new Field(role, component, attribute), values)); }
    }
    public record Group(List<Condition> conditions) {
        public Group { conditions = List.copyOf(conditions); if (conditions.isEmpty()) throw new IllegalArgumentException("Empty applicability group"); }
        Clause clause() {
            Map<Field, Set<String>> fields = new LinkedHashMap<>();
            for (var c : conditions) if (fields.put(new Field(c.role(), c.component(), c.attribute()), c.values()) != null)
                throw new IllegalArgumentException("Repeated field in applicability group");
            return new Clause(fields);
        }
    }
    public record Value(Role role, String component, String attribute, String value) { }
    public record Environment(List<Value> values) {
        public Environment { values = List.copyOf(values); asMap(values); }
        public Map<Field,String> asMap() { return asMap(values); }
        private static Map<Field,String> asMap(List<Value> values) {
            Map<Field,String> result = new LinkedHashMap<>();
            for (var v : values) {
                if (v.value() == null || v.value().isBlank() || result.put(new Field(v.role(), v.component(), v.attribute()), v.value()) != null)
                    throw new IllegalArgumentException("Missing or repeated environment field");
            }
            return Map.copyOf(result);
        }
    }
    public KnowledgeScope {
        includes = List.copyOf(includes); excludes = List.copyOf(excludes);
        new Rule(confirmedUniversal, includes.stream().map(Group::clause).toList(), excludes.stream().map(Group::clause).toList());
    }
    public Rule rule() { return new Rule(confirmedUniversal, includes.stream().map(Group::clause).toList(), excludes.stream().map(Group::clause).toList()); }
    public Match evaluate(Environment environment) { return rule().evaluate(environment == null ? Map.of() : environment.asMap()); }
    /** Conservative overlap check: uncertain overlap requires explicit replacement approval. */
    public boolean mayOverlap(KnowledgeScope other) {
        if (confirmedUniversal || other.confirmedUniversal) return true;
        for (var a : includes) for (var b : other.includes) {
            var left = a.clause().constraints(); var right = b.clause().constraints();
            boolean disjoint = left.entrySet().stream().anyMatch(e -> right.containsKey(e.getKey()) && Collections.disjoint(e.getValue(), right.get(e.getKey())));
            if (!disjoint) return true;
        }
        return false;
    }
}
