package com.devmind.auth.authz;

import com.devmind.auth.model.RoleEntity;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.RoleRepository;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.authz.ExecutionContext;
import com.devmind.common.authz.ReasonCodes;
import com.devmind.common.authz.ResolvedSubject;
import com.devmind.common.authz.SubjectType;
import org.springframework.stereotype.Component;

/**
 * CAP-108 FR-02 主体解析：按可信主体引用实时读取当前有效用户、roleCode、roles.base_role 与状态。
 * 不存在、停用、角色删除均拒绝；不信任 token/确认单中的角色快照；不回退 local/ADMIN/VIEWER。
 */
@Component
public class SubjectResolver {

    /** M1 兼容：等价 ADMIN 的历史开放接口 key（M3 以显式集成主体替换，见 CAP-108 FR-13）。 */
    public static final String LEGACY_KEY_ROLE_CODE = "LEGACY_ADMIN";
    public static final String LEGACY_KEY_PREFIX = "apikey:";

    /** 解析结果：subject 为 null 时 denyReason/message 给出拒绝原因。 */
    public record Resolution(ResolvedSubject subject, String denyReason, String message) {

        static Resolution ok(ResolvedSubject subject) {
            return new Resolution(subject, null, null);
        }

        static Resolution deny(String reason, String message) {
            return new Resolution(null, reason, message);
        }

        public boolean resolved() {
            return subject != null;
        }
    }

    private final UserRepository users;
    private final RoleRepository roles;

    public SubjectResolver(UserRepository users, RoleRepository roles) {
        this.users = users;
        this.roles = roles;
    }

    public Resolution resolve(ExecutionContext ctx) {
        if (ctx == null) {
            return Resolution.deny(ReasonCodes.SUBJECT_UNKNOWN, "缺少可信执行上下文");
        }
        if (ctx.subjectType() == SubjectType.SYSTEM) {
            return Resolution.ok(new ResolvedSubject(SubjectType.SYSTEM,
                    ctx.subjectId() == null ? ctx.username() : ctx.subjectId(),
                    ctx.username(), ResolvedSubject.BASE_ADMIN, "SYSTEM"));
        }
        if (ctx.subjectType() == SubjectType.SERVICE) {
            // M1：仅接纳历史 apikey 主体（等价 ADMIN 的既有兼容行为，带缺口标识）；M3 换显式集成主体授权
            if (ctx.username() != null && ctx.username().startsWith(LEGACY_KEY_PREFIX)) {
                return Resolution.ok(new ResolvedSubject(SubjectType.SERVICE, ctx.subjectId(),
                        ctx.username(), ResolvedSubject.BASE_ADMIN, LEGACY_KEY_ROLE_CODE));
            }
            return Resolution.deny(ReasonCodes.SUBJECT_UNKNOWN, "未登记的集成主体");
        }
        UserEntity user = users.findByUsername(ctx.username()).orElse(null);
        if (user == null) {
            return Resolution.deny(ReasonCodes.SUBJECT_UNKNOWN, "用户不存在: " + ctx.username());
        }
        if (!user.isActive()) {
            return Resolution.deny(ReasonCodes.SUBJECT_INACTIVE, "账号已停用: " + ctx.username());
        }
        String roleCode = user.getRoleCode() == null || user.getRoleCode().isBlank()
                ? user.getRole() : user.getRoleCode();
        RoleEntity role = roleCode == null ? null : roles.findById(roleCode).orElse(null);
        if (role == null) {
            return Resolution.deny(ReasonCodes.SUBJECT_INACTIVE, "角色已删除: " + roleCode);
        }
        SubjectType type = ctx.subjectType() == SubjectType.DELEGATED_USER
                ? SubjectType.DELEGATED_USER : SubjectType.USER;
        return Resolution.ok(new ResolvedSubject(type, user.getId(), user.getUsername(),
                role.getBaseRole(), role.getCode()));
    }
}
