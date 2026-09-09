package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.integration.config.IntegrationCipher;
import com.devmind.integration.connector.IntegrationConnector;
import com.devmind.integration.dto.PlatformAccountUpsertRequest;
import com.devmind.integration.dto.PlatformAccountView;
import com.devmind.integration.model.IntegrationEntity;
import com.devmind.integration.model.UserPlatformAccountEntity;
import com.devmind.integration.repo.IntegrationRepository;
import com.devmind.integration.repo.UserPlatformAccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CAP-35 用户平台账号：绑定到 Integration 实例的个人凭据（git PAT+署名 / Jira PAT·BASIC）。
 * 取代 CAP-24 user_git_credentials——base_url 不再手填，匹配经实例 host 归一化。
 *
 * <p>职责：FR-01 CRUD（本人作用域）+ FR-02 连通性自检（复用 Connector，凭据为个人）+
 * {@link GitIdentityProvider} 实现（会话 env 注入）+ 模块内个人凭据解析
 * （{@link #personalSecretFor} / {@link #personalTokenFor}，token 不出模块边界）。
 * 密文复用 {@link IntegrationCipher}（enc1: AES-GCM），解密仅发生在本模块内存中。</p>
 */
@Service
public class UserPlatformAccountService implements GitIdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(UserPlatformAccountService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final UserPlatformAccountRepository accountRepo;
    private final IntegrationRepository integrationRepo;
    private final UserRepository userRepo;
    private final IdentityService identityService;
    private final IntegrationCipher cipher;
    private final Map<String, IntegrationConnector> connectors;

    public UserPlatformAccountService(UserPlatformAccountRepository accountRepo,
                                      IntegrationRepository integrationRepo,
                                      UserRepository userRepo,
                                      IdentityService identityService,
                                      IntegrationCipher cipher,
                                      List<IntegrationConnector> connectorList) {
        this.accountRepo = accountRepo;
        this.integrationRepo = integrationRepo;
        this.userRepo = userRepo;
        this.identityService = identityService;
        this.cipher = cipher;
        this.connectors = connectorList.stream()
                .collect(Collectors.toMap(IntegrationConnector::type, Function.identity()));
    }

    // ---------------- FR-01 CRUD（本人作用域） ----------------

    /** 全部 ENABLED 实例 + 我的绑定状态（未绑定也列出，前端引导配置） */
    public List<PlatformAccountView> listOverview() {
        String userId = currentUser().getId();
        Map<Long, UserPlatformAccountEntity> mine = accountRepo.findByUserIdOrderByIdAsc(userId)
                .stream().collect(Collectors.toMap(UserPlatformAccountEntity::getIntegrationId,
                        Function.identity()));
        List<PlatformAccountView> out = new ArrayList<>();
        for (IntegrationEntity i : integrationRepo.findAllByOrderByIdAsc()) {
            if (!IntegrationEntity.STATUS_ENABLED.equals(i.getStatus())) {
                continue;
            }
            out.add(toView(i, mine.get(i.getId())));
        }
        return out;
    }

    /** 绑定/更新我的账号（upsert；(user_id, integration_id) 唯一天然满足） */
    public PlatformAccountView upsert(Long integrationId, PlatformAccountUpsertRequest req) {
        UserEntity user = currentUser();
        IntegrationEntity integration = requireEnabledIntegration(integrationId);
        UserPlatformAccountEntity e = accountRepo
                .findByUserIdAndIntegrationId(user.getId(), integrationId)
                .orElse(null);
        boolean isCreate = e == null;
        if (isCreate) {
            e = new UserPlatformAccountEntity();
            e.setUserId(user.getId());
            e.setIntegrationId(integrationId);
            e.setCreatedAt(Instant.now());
        }
        applyEditable(e, integration, req, isCreate);
        e.setUpdatedAt(Instant.now());
        UserPlatformAccountEntity saved = accountRepo.save(e);
        log.info("用户平台账号已{}: user={} integration=#{} {}", isCreate ? "绑定" : "更新",
                user.getUsername(), integrationId, integration.getName());
        return toView(integration, saved);
    }

    public void unbind(Long integrationId) {
        String userId = currentUser().getId();
        UserPlatformAccountEntity e = accountRepo.findByUserIdAndIntegrationId(userId, integrationId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "未绑定该实例的账号: " + integrationId));
        accountRepo.delete(e);
    }

    // ---------------- FR-02 连通性自检（我的凭据走 Connector） ----------------

    public IntegrationConnector.TestResult test(Long integrationId) {
        String userId = currentUser().getId();
        IntegrationEntity integration = requireEnabledIntegration(integrationId);
        UserPlatformAccountEntity account = accountRepo.findByUserIdAndIntegrationId(userId, integrationId)
                .orElseThrow(() -> new DevMindException(ErrorCode.BAD_REQUEST, "请先绑定该实例的账号再自检"));
        IntegrationConnector connector = connectors.get(integration.getType());
        if (connector == null) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "平台类型 " + integration.getType() + " 无可用连接器实现");
        }
        try {
            return connector.testConnection(integration, cipher.decrypt(account.getSecretEnc()));
        } catch (Exception ex) {
            return new IntegrationConnector.TestResult(false, "连接异常：" + ex.getMessage(),
                    integration.getBaseUrl());
        }
    }

    // ---------------- FR-04 GitIdentityProvider（会话身份注入） ----------------

    @Override
    public Optional<GitAuthor> resolveAuthor(String username, String repoHost) {
        Optional<UserEntity> user = userRepo.findByUsername(username);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        UserEntity u = user.get();
        if (repoHost != null && !repoHost.isBlank()) {
            Optional<UserPlatformAccountEntity> account = findGitAccountByHost(u.getId(), repoHost);
            if (account.isPresent()) {
                return Optional.of(new GitAuthor(account.get().getGitAuthorName(),
                        account.get().getGitAuthorEmail()));
            }
        }
        // 无个人账号：回退 displayName/username，email 不注入（留给系统 git 配置）
        String name = u.getDisplayName() != null && !u.getDisplayName().isBlank()
                ? u.getDisplayName() : u.getUsername();
        return Optional.of(new GitAuthor(name, null));
    }

    // ---------------- 模块内个人凭据解析（token 不出模块） ----------------

    /** 用户在某实例上的个人凭据（内存解密，BASIC 为 "username\npassword" 格式）；无绑定返回 empty。 */
    public Optional<String> personalSecretFor(String username, Long integrationId) {
        if (username == null || username.isBlank() || integrationId == null) {
            return Optional.empty();
        }
        return userRepo.findByUsername(username)
                .flatMap(u -> accountRepo.findByUserIdAndIntegrationId(u.getId(), integrationId))
                .map(a -> cipher.decrypt(a.getSecretEnc()));
    }

    /** 用户在指定 host 的 git 平台个人 PAT（内存解密）；无匹配返回 empty。供 resolveGitToken 复用。 */
    public Optional<String> personalTokenFor(String username, String repoHost) {
        if (repoHost == null || repoHost.isBlank()) {
            return Optional.empty();
        }
        return userRepo.findByUsername(username)
                .flatMap(u -> findGitAccountByHost(u.getId(), repoHost))
                .map(a -> cipher.decrypt(a.getSecretEnc()));
    }

    // ---------------- 内部 ----------------

    private void applyEditable(UserPlatformAccountEntity e, IntegrationEntity integration,
                               PlatformAccountUpsertRequest req, boolean isCreate) {
        String oldAuthType = isCreate ? null : e.getAuthType();
        String oldUsername = isCreate ? null : e.getUsername();
        boolean isGit = IntegrationEntity.TYPE_GITLAB.equals(integration.getType())
                || IntegrationEntity.TYPE_GITHUB.equals(integration.getType());
        // authType：git 平台仅 PAT；Jira 支持 PAT / BASIC
        String authType = IntegrationService.normalizeAuthType(req.authType());
        if (isGit && !IntegrationEntity.AUTH_PAT.equals(authType)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "代码平台账号仅支持 PAT");
        }
        e.setAuthType(authType);
        // BASIC 用户名：更新且留空时沿用原用户名
        if (IntegrationEntity.AUTH_BASIC.equals(authType)) {
            String username = req.username() == null ? "" : req.username().trim();
            if (username.isEmpty() && IntegrationEntity.AUTH_BASIC.equals(oldAuthType)
                    && oldUsername != null) {
                username = oldUsername;
            }
            if (username.isEmpty()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "Basic Auth 需要填写用户名");
            }
            e.setUsername(username);
        } else {
            e.setUsername(null);
        }
        // secret：创建必填；更新留空 = 不修改（认证方式/用户名变更时必须重填）
        boolean secretRequired = isCreate
                || !authType.equals(oldAuthType)
                || (IntegrationEntity.AUTH_BASIC.equals(authType)
                        && !java.util.Objects.equals(e.getUsername(), oldUsername));
        if (req.secret() != null && !req.secret().isBlank()) {
            String plain = IntegrationEntity.AUTH_BASIC.equals(authType)
                    ? IntegrationService.encodeSecret(authType, e.getUsername(), req.secret().trim())
                    : req.secret().trim();
            e.setSecretEnc(cipher.encrypt(plain));
        } else if (secretRequired || e.getSecretEnc() == null || e.getSecretEnc().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    IntegrationEntity.AUTH_BASIC.equals(authType) ? "密码不能为空" : "PAT 不能为空");
        }
        // git 平台署名必填；Jira 恒空
        if (isGit) {
            String authorName = req.gitAuthorName() == null ? "" : req.gitAuthorName().trim();
            if (authorName.isEmpty()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "gitAuthorName 不能为空");
            }
            String authorEmail = req.gitAuthorEmail() == null ? "" : req.gitAuthorEmail().trim();
            if (!EMAIL_PATTERN.matcher(authorEmail).matches()) {
                throw new DevMindException(ErrorCode.BAD_REQUEST, "gitAuthorEmail 不是合法邮箱：" + authorEmail);
            }
            e.setGitAuthorName(authorName);
            e.setGitAuthorEmail(authorEmail);
        } else {
            e.setGitAuthorName(null);
            e.setGitAuthorEmail(null);
        }
    }

    private IntegrationEntity requireEnabledIntegration(Long integrationId) {
        IntegrationEntity integration = integrationRepo.findById(integrationId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND,
                        "平台实例不存在: " + integrationId));
        if (!IntegrationEntity.STATUS_ENABLED.equals(integration.getStatus())) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "实例已停用，不可绑定: " + integration.getName());
        }
        return integration;
    }

    /** 按 remoteUrl host 找我的 git 平台账号：ENABLED git 实例 host 匹配 → 查绑定 */
    private Optional<UserPlatformAccountEntity> findGitAccountByHost(String userId, String repoHost) {
        List<Long> candidateIds = new ArrayList<>();
        for (IntegrationEntity i : integrationRepo.findAllByOrderByIdAsc()) {
            if (!IntegrationEntity.STATUS_ENABLED.equals(i.getStatus())) {
                continue;
            }
            if (!IntegrationEntity.TYPE_GITLAB.equals(i.getType())
                    && !IntegrationEntity.TYPE_GITHUB.equals(i.getType())) {
                continue;
            }
            String h = hostOf(i.getBaseUrl());
            if (h != null && h.equalsIgnoreCase(repoHost)) {
                candidateIds.add(i.getId());
            }
        }
        if (candidateIds.isEmpty()) {
            return Optional.empty();
        }
        return accountRepo.findByUserIdOrderByIdAsc(userId).stream()
                .filter(a -> candidateIds.contains(a.getIntegrationId()))
                .findFirst();
    }

    private UserEntity currentUser() {
        return identityService.currentUser()
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "未登录"));
    }

    private PlatformAccountView toView(IntegrationEntity i, UserPlatformAccountEntity a) {
        return new PlatformAccountView(i.getId(), i.getType(), i.getName(), i.getBaseUrl(),
                a != null,
                a != null ? a.getAuthType() : null,
                a != null ? a.getUsername() : null,
                a != null && a.getSecretEnc() != null && !a.getSecretEnc().isBlank(),
                a != null ? a.getGitAuthorName() : null,
                a != null ? a.getGitAuthorEmail() : null,
                a != null ? a.getUpdatedAt() : null);
    }

    /** URL host 归一化（小写）；非法/空返回 null。供 IntegrationService / RepoGitGatewayImpl 复用。 */
    public static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
