package com.devmind.integration.service;

import com.devmind.auth.IdentityService;
import com.devmind.auth.model.UserEntity;
import com.devmind.auth.repo.UserRepository;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.common.integration.GitIdentityProvider;
import com.devmind.integration.config.IntegrationCipher;
import com.devmind.integration.dto.UserGitCredentialRequest;
import com.devmind.integration.dto.UserGitCredentialView;
import com.devmind.integration.model.UserGitCredentialEntity;
import com.devmind.integration.repo.UserGitCredentialRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * CAP-24 用户级 Git 凭证与提交身份：CRUD（本人作用域隔离）+ 连通性自检 +
 * {@link GitIdentityProvider} 实现（会话 env 注入用）+ push 个人 token 解析（模块内部用）。
 *
 * <p>PAT 复用 {@link IntegrationCipher} 加密（enc1: AES-GCM），解密仅发生在本模块内存中；
 * 所有匹配按 baseUrl/remoteUrl 的 host 归一化（小写）比较。</p>
 */
@Service
public class UserGitCredentialService implements GitIdentityProvider {

    private static final Logger log = LoggerFactory.getLogger(UserGitCredentialService.class);
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private final UserGitCredentialRepository credRepo;
    private final UserRepository userRepo;
    private final IdentityService identityService;
    private final IntegrationCipher cipher;
    private final GitRemoteOps gitOps;

    public UserGitCredentialService(UserGitCredentialRepository credRepo,
                                    UserRepository userRepo,
                                    IdentityService identityService,
                                    IntegrationCipher cipher,
                                    GitRemoteOps gitOps) {
        this.credRepo = credRepo;
        this.userRepo = userRepo;
        this.identityService = identityService;
        this.cipher = cipher;
        this.gitOps = gitOps;
    }

    // ---------------- FR-01 CRUD（本人作用域） ----------------

    public List<UserGitCredentialView> listMine() {
        String userId = currentUser().getId();
        return credRepo.findByUserIdOrderByIdAsc(userId).stream().map(this::toView).toList();
    }

    public UserGitCredentialView create(UserGitCredentialRequest req) {
        UserEntity user = currentUser();
        UserGitCredentialEntity e = new UserGitCredentialEntity();
        e.setUserId(user.getId());
        applyEditable(e, req);
        if (req.secret() == null || req.secret().isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "创建时 PAT 不能为空");
        }
        e.setSecretEnc(cipher.encrypt(req.secret().trim()));
        Instant now = Instant.now();
        e.setCreatedAt(now);
        e.setUpdatedAt(now);
        UserGitCredentialEntity saved = credRepo.save(e);
        log.info("用户 Git 凭证已创建: user={} label={} host={}", user.getUsername(), saved.getLabel(), hostOf(saved.getBaseUrl()));
        return toView(saved);
    }

    public UserGitCredentialView update(Long id, UserGitCredentialRequest req) {
        UserGitCredentialEntity e = requireMine(id);
        applyEditable(e, req);
        // secret 留空 = 不修改
        if (req.secret() != null && !req.secret().isBlank()) {
            e.setSecretEnc(cipher.encrypt(req.secret().trim()));
        }
        e.setUpdatedAt(Instant.now());
        return toView(credRepo.save(e));
    }

    public void delete(Long id) {
        credRepo.delete(requireMine(id));
    }

    /**
     * FR-02 连通性自检：以该凭证 PAT 对指定仓库地址执行 git ls-remote。
     * remoteUrl 的 host 必须与凭证 baseUrl host 一致（防拿凭证撞别的平台）。
     */
    public String test(Long id, String remoteUrl) {
        UserGitCredentialEntity e = requireMine(id);
        if (remoteUrl == null || remoteUrl.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "remoteUrl 不能为空（自检需要一个可访问的仓库地址）");
        }
        String repoHost = hostOf(remoteUrl);
        String credHost = hostOf(e.getBaseUrl());
        if (repoHost == null || credHost == null || !repoHost.equalsIgnoreCase(credHost)) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "仓库地址 host（" + repoHost + "）与凭证平台（" + credHost + "）不一致");
        }
        GitRemoteOps.GitResult result = gitOps.lsRemote(remoteUrl.trim(), cipher.decrypt(e.getSecretEnc()));
        if (!result.ok()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "连通性自检失败：" + result.output());
        }
        return "连接成功";
    }

    // ---------------- FR-05 GitIdentityProvider（会话身份注入） ----------------

    @Override
    public Optional<GitAuthor> resolveAuthor(String username, String repoHost) {
        Optional<UserEntity> user = userRepo.findByUsername(username);
        if (user.isEmpty()) {
            return Optional.empty();
        }
        UserEntity u = user.get();
        if (repoHost != null && !repoHost.isBlank()) {
            Optional<UserGitCredentialEntity> cred = findByHost(u.getId(), repoHost);
            if (cred.isPresent()) {
                return Optional.of(new GitAuthor(cred.get().getGitAuthorName(), cred.get().getGitAuthorEmail()));
            }
        }
        // 无个人凭证：回退 displayName/username，email 不注入（留给系统 git 配置）
        String name = u.getDisplayName() != null && !u.getDisplayName().isBlank()
                ? u.getDisplayName() : u.getUsername();
        return Optional.of(new GitAuthor(name, null));
    }

    // ---------------- FR-04 push 个人 token（模块内部用，token 不出模块） ----------------

    /** 用户在指定 host 的个人 PAT（内存解密）；无匹配返回 empty。 */
    public Optional<String> personalTokenFor(String username, String repoHost) {
        if (repoHost == null || repoHost.isBlank()) {
            return Optional.empty();
        }
        return userRepo.findByUsername(username)
                .flatMap(u -> findByHost(u.getId(), repoHost))
                .map(c -> cipher.decrypt(c.getSecretEnc()));
    }

    // ---------------- 内部 ----------------

    private void applyEditable(UserGitCredentialEntity e, UserGitCredentialRequest req) {
        String label = req.label() == null ? "" : req.label().trim();
        if (label.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "label 不能为空");
        }
        String baseUrl = req.baseUrl() == null ? "" : req.baseUrl().trim();
        String host = hostOf(baseUrl);
        if (host == null || !(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "baseUrl 必须是可解析出 host 的 http/https 地址：" + baseUrl);
        }
        String authorName = req.gitAuthorName() == null ? "" : req.gitAuthorName().trim();
        if (authorName.isEmpty()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "gitAuthorName 不能为空");
        }
        String authorEmail = req.gitAuthorEmail() == null ? "" : req.gitAuthorEmail().trim();
        if (!EMAIL_PATTERN.matcher(authorEmail).matches()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "gitAuthorEmail 不是合法邮箱：" + authorEmail);
        }
        // 同一用户同一 host 唯一（判重排除自身）
        String finalHost = host;
        boolean dup = credRepo.findByUserIdOrderByIdAsc(e.getUserId()).stream()
                .anyMatch(x -> !x.getId().equals(e.getId()) && finalHost.equalsIgnoreCase(hostOf(x.getBaseUrl())));
        if (dup) {
            throw new DevMindException(ErrorCode.CONFLICT, "该平台（" + host + "）已有凭证，请编辑既有记录");
        }
        e.setLabel(label);
        e.setBaseUrl(baseUrl);
        e.setGitAuthorName(authorName);
        e.setGitAuthorEmail(authorEmail);
    }

    private UserGitCredentialEntity requireMine(Long id) {
        String userId = currentUser().getId();
        return credRepo.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new DevMindException(ErrorCode.NOT_FOUND, "凭证不存在: " + id));
    }

    private UserEntity currentUser() {
        return identityService.currentUser()
                .orElseThrow(() -> new DevMindException(ErrorCode.UNAUTHORIZED, "未登录"));
    }

    private Optional<UserGitCredentialEntity> findByHost(String userId, String repoHost) {
        return credRepo.findByUserIdOrderByIdAsc(userId).stream()
                .filter(x -> {
                    String h = hostOf(x.getBaseUrl());
                    return h != null && h.equalsIgnoreCase(repoHost);
                })
                .findFirst();
    }

    private UserGitCredentialView toView(UserGitCredentialEntity e) {
        return new UserGitCredentialView(e.getId(), e.getLabel(), e.getBaseUrl(),
                e.getGitAuthorName(), e.getGitAuthorEmail(),
                e.getSecretEnc() != null && !e.getSecretEnc().isBlank(),
                e.getCreatedAt(), e.getUpdatedAt());
    }

    /** URL host 归一化（小写）；非法/空返回 null。CAP-24 起供 IntegrationService push 复用。 */
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
