package com.devmind.common.knowledge;
import java.util.Set;
/** Trusted organization adapter. Membership must be resolved live, not copied from request/JWT claims. */
public interface KnowledgeGroupDirectory {
    record Membership(Set<String> groups,String revision) { public Membership { groups=Set.copyOf(groups); } }
    Membership resolve(String actor);
    boolean knownGroup(String groupId);
}
