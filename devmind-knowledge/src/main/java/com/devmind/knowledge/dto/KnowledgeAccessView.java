package com.devmind.knowledge.dto;

import com.devmind.common.knowledge.KnowledgeAccessPolicy.Action;
import java.util.List;

public record KnowledgeAccessView(String ownerId, String visibility, long revision,
                                  List<Action> effectiveActions, List<Grant> grants) {
    public record Grant(String username, Action action, String effect,java.time.Instant expiresAt) {
        public Grant(String username,Action action,String effect){this(username,action,effect,null);}
    }
    public record Update(long expectedRevision, String visibility, List<Grant> grants) { }
    public record AssignOwner(String username) { }
    public record PendingBase(Long id, String name) { }
}
