package com.devmind.common.knowledge;

import java.util.Collection;
import java.util.List;

/** Mandatory resource authorization, independent of menu permissions and requested KB scope. */
public interface KnowledgeAccessPolicy {
    enum Action { READ, EDIT, PUBLISH, MANAGE_ACL }
    void requireBase(String actor, Long kbId, Action action);
    void requireEntries(String actor, Collection<Long> entryIds);
    /** null requested IDs means all readable bases; an empty collection always means none. */
    List<Long> readableBaseIds(String actor, Collection<Long> requested);
}
