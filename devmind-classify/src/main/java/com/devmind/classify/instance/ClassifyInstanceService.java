package com.devmind.classify.instance;

import com.devmind.auth.IdentityService;
import com.devmind.classify.instance.dto.ClassifyInstanceRequest;
import com.devmind.classify.instance.dto.ClassifyInstanceView;
import com.devmind.classify.instance.dto.ClassifyInstanceViews;
import com.devmind.classify.instance.model.ClassifyInstanceEntity;
import com.devmind.classify.instance.repo.ClassifyInstanceRepository;
import com.devmind.classify.pkg.model.ClassifyPackageInstallEntity;
import com.devmind.classify.pkg.repo.ClassifyPackageInstallRepository;
import com.devmind.common.agent.AgentNodeConnector;
import com.devmind.common.agent.AgentProcCommand;
import com.devmind.common.agent.AgentProcResult;
import com.devmind.common.exception.DevMindException;
import com.devmind.common.exception.ErrorCode;
import com.devmind.execution.runner.AgentNodeRouter;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * CAP-57 FR-02 实例管控：CRUD + 起停/重启/状态。
 *
 * <p><b>起停 = 组 proc 帧下发绑定节点</b>（协议 v15，{@link AgentNodeRouter#requireProcCapable}
 * 在操作触发阶段门控）：spec 路径一律相对节点 {@code <workspaceRoot>/classify/} 根——
 * workdir 走约定 {@code packages/pkg-<appPackageId>}（先确认该包 INSTALLED 到本节点，否则 409
 * 指到安装包页），pidFile/logFile 在 {@code run/} 与 {@code logs/} 下。服务端从不感知节点绝对路径。</p>
 *
 * <p><b>env 的 {@code ${PKG_DIR:<id>}} 占位符</b>在组帧这一刻展开为该包在绑定节点的 installDir
 * （节点侧绝对路径，取自 installs 表 pkg_ack 回写）——{@code LAYA_SLOT_MODELS} 这类配置需要的
 * 就是节点本地路径；引用了未安装的包 → 409 指明缺哪个。</p>
 *
 * <p>异步触发方法（start/stop/restart）<b>禁 @Transactional</b>（红线）：状态翻转靠 save 自身
 * 事务即时提交，proc 帧 ack 是同步等的（60s 上限），不存在异步线程读未提交行。</p>
 */
@Service
public class ClassifyInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ClassifyInstanceService.class);

    /** ${PKG_DIR:<id>}} 占位符（id 为安装包主键） */
    private static final Pattern PKG_DIR_PLACEHOLDER = Pattern.compile("\\$\\{PKG_DIR:(\\d+)}");

    private static final String DEFAULT_PYTHON_BIN = "venv/bin/python";
    private static final TypeReference<Map<String, String>> ENV_TYPE = new TypeReference<>() {
    };

    private final ClassifyInstanceRepository repo;
    private final ClassifyPackageInstallRepository installRepo;
    private final AgentNodeRouter nodeRouter;
    private final ObjectProvider<AgentNodeConnector> connectorProvider;
    private final IdentityService identityService;
    private final ObjectMapper mapper;

    public ClassifyInstanceService(ClassifyInstanceRepository repo,
                                   ClassifyPackageInstallRepository installRepo,
                                   AgentNodeRouter nodeRouter,
                                   ObjectProvider<AgentNodeConnector> connectorProvider,
                                   IdentityService identityService, ObjectMapper mapper) {
        this.repo = repo;
        this.installRepo = installRepo;
        this.nodeRouter = nodeRouter;
        this.connectorProvider = connectorProvider;
        this.identityService = identityService;
        this.mapper = mapper;
    }

    // ---------------- CRUD ----------------

    public List<ClassifyInstanceView> list() {
        return repo.findAll(org.springframework.data.domain.Sort.by(
                        org.springframework.data.domain.Sort.Direction.DESC, "id"))
                .stream().map(e -> ClassifyInstanceViews.of(e, mapper)).toList();
    }

    public ClassifyInstanceView get(long id) {
        return ClassifyInstanceViews.of(require(id), mapper);
    }

    public ClassifyInstanceView create(ClassifyInstanceRequest req) {
        String name = requireText(req == null ? null : req.name(), "名称必填", 128);
        if (repo.findByName(name).isPresent()) {
            throw new DevMindException(ErrorCode.CONFLICT, "实例名已存在: " + name);
        }
        ClassifyInstanceEntity e = new ClassifyInstanceEntity();
        e.setName(name);
        applyEditable(e, req);
        e.setStatus(ClassifyInstanceEntity.STATUS_STOPPED);
        e.setCreatedBy(identityService.currentActor());
        e.setCreatedAt(Instant.now());
        e.setUpdatedAt(e.getCreatedAt());
        ClassifyInstanceEntity saved = repo.save(e);
        log.info("分类实例创建: id={} name={} node={} port={} by={}", saved.getId(), saved.getName(),
                saved.getAgentNodeId(), saved.getPort(), saved.getCreatedBy());
        return ClassifyInstanceViews.of(saved, mapper);
    }

    public ClassifyInstanceView update(long id, ClassifyInstanceRequest req) {
        ClassifyInstanceEntity e = require(id);
        String name = requireText(req == null ? null : req.name(), "名称必填", 128);
        repo.findByName(name).filter(other -> !other.getId().equals(id)).ifPresent(other -> {
            throw new DevMindException(ErrorCode.CONFLICT, "实例名已存在: " + name);
        });
        e.setName(name);
        applyEditable(e, req);
        e.setUpdatedAt(Instant.now());
        // 改了节点/端口/包/env 不影响在跑的进程：生效时机是下一次 start（视图原样展示当前配置）
        return ClassifyInstanceViews.of(repo.save(e), mapper);
    }

    public void delete(long id) {
        ClassifyInstanceEntity e = require(id);
        if (!ClassifyInstanceEntity.STATUS_STOPPED.equals(e.getStatus())) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "实例当前状态 " + e.getStatus() + "，先停止再删除");
        }
        repo.delete(e);
        log.info("分类实例删除: id={} name={}", e.getId(), e.getName());
    }

    // ---------------- 起停管控 ----------------

    public ClassifyInstanceView start(long id) {
        return operate(id, AgentProcCommand.ACTION_START);
    }

    public ClassifyInstanceView stop(long id) {
        return operate(id, AgentProcCommand.ACTION_STOP);
    }

    public ClassifyInstanceView restart(long id) {
        return operate(id, AgentProcCommand.ACTION_RESTART);
    }

    /**
     * 实时状态：向节点发 status 帧，并按「进程已退出」这一种情况回写实体——
     * proc 说 STOPPED 而库里还以为在跑（STARTING/RUNNING/UNHEALTHY），说明进程死了，
     * 按 CAP-57「不自动拉起」口径标回 STOPPED 并留下说明（不标 UNHEALTHY：UNHEALTHY 是
     * 「进程在、healthz 不过」，进程没了就是没了）。
     */
    public ClassifyInstanceView liveStatus(long id) {
        ClassifyInstanceEntity e = require(id);
        nodeRouter.requireProcCapable(e.getAgentNodeId());
        AgentProcResult r = connector().proc(e.getAgentNodeId(),
                new AgentProcCommand(newRequestId(id), AgentProcCommand.ACTION_STATUS,
                        String.valueOf(id), List.of(), "", Map.of(), "", pidFile(id), ""));
        if (r.ok() && AgentProcResult.STOPPED.equals(r.status())
                && !ClassifyInstanceEntity.STATUS_STOPPED.equals(e.getStatus())) {
            e.setStatus(ClassifyInstanceEntity.STATUS_STOPPED);
            e.setLastError("节点报告进程已退出（可能崩溃或被外部结束）；平台不自动拉起，请排查后手动启动");
            e.setUpdatedAt(Instant.now());
            repo.save(e);
            log.warn("分类实例进程已退出: id={} name={}", e.getId(), e.getName());
        }
        return ClassifyInstanceViews.of(e, mapper);
    }

    private ClassifyInstanceView operate(long id, String action) {
        ClassifyInstanceEntity e = require(id);
        nodeRouter.requireProcCapable(e.getAgentNodeId());
        AgentProcResult r = connector().proc(e.getAgentNodeId(), buildProcCommand(e, action));
        e.setUpdatedAt(Instant.now());
        if (!r.ok()) {
            e.setLastError(trimTo(r.error(), 1024));
            repo.save(e);
            throw new DevMindException(ErrorCode.CONFLICT,
                    "节点执行 " + action + " 失败: " + r.error());
        }
        switch (action) {
            case AgentProcCommand.ACTION_START, AgentProcCommand.ACTION_RESTART -> {
                e.setStatus(ClassifyInstanceEntity.STATUS_STARTING);
                e.setLastStartAt(Instant.now());
                e.setLastError(null);
            }
            case AgentProcCommand.ACTION_STOP -> {
                e.setStatus(ClassifyInstanceEntity.STATUS_STOPPED);
                e.setLastError(null);
            }
            default -> {
            }
        }
        ClassifyInstanceEntity saved = repo.save(e);
        log.info("分类实例 {}: id={} name={} pid={}", action, saved.getId(), saved.getName(), r.pid());
        return ClassifyInstanceViews.of(saved, mapper);
    }

    /**
     * 组 proc 帧（包级可见供测试钉帧）。<b>红线：AgentProcCommand 全部字段都要给值</b>，
     * 缺了的服务端这里就空串/空表，runner 侧 ack 拒绝。
     */
    AgentProcCommand buildProcCommand(ClassifyInstanceEntity e, String action) {
        long id = e.getId();
        List<String> argv;
        String workdir = "";
        Map<String, String> env = Map.of();
        if (!AgentProcCommand.ACTION_STOP.equals(action) && !AgentProcCommand.ACTION_STATUS.equals(action)) {
            argv = buildArgv(e);
            workdir = requireAppPackageInstalled(e);
            env = expandEnv(e);
        } else {
            argv = List.of();
        }
        return new AgentProcCommand(newRequestId(id), action, String.valueOf(id), argv,
                String.join(" ", argv), env, workdir, pidFile(id), logFile(id));
    }

    /** 默认 {@code <pythonBin> -m uvicorn app:app --host 0.0.0.0 --port <port>}；覆盖命令按空白拆 argv */
    private List<String> buildArgv(ClassifyInstanceEntity e) {
        if (e.getCommandOverride() != null && !e.getCommandOverride().isBlank()) {
            return Arrays.asList(e.getCommandOverride().trim().split("\\s+"));
        }
        String python = e.getPythonBin() == null || e.getPythonBin().isBlank()
                ? DEFAULT_PYTHON_BIN : e.getPythonBin().trim();
        return List.of(python, "-m", "uvicorn", "app:app", "--host", "0.0.0.0",
                "--port", String.valueOf(e.getPort()));
    }

    /** workdir 相对路径约定 + 安装前置校验（未装到本节点 → 409 指到安装包页） */
    private String requireAppPackageInstalled(ClassifyInstanceEntity e) {
        if (e.getAppPackageId() == null) {
            throw new DevMindException(ErrorCode.CONFLICT,
                    "实例「" + e.getName() + "」未绑定应用包：请先在编辑里选择 SIDECAR_APP 类安装包");
        }
        ClassifyPackageInstallEntity install = installRepo
                .findByPackageIdAndNodeId(e.getAppPackageId(), e.getAgentNodeId())
                .filter(i -> ClassifyPackageInstallEntity.STATUS_INSTALLED.equals(i.getStatus()))
                .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                        "应用包 #" + e.getAppPackageId() + " 尚未安装到节点 " + e.getAgentNodeId()
                                + "：请到「分类服务 → 安装包」先分发"));
        return "packages/pkg-" + install.getPackageId();
    }

    /** env_json 解析 + ${PKG_DIR:<id>} 展开为该包在本节点的 installDir（未安装 → 409 报缺哪个包） */
    private Map<String, String> expandEnv(ClassifyInstanceEntity e) {
        Map<String, String> env = new LinkedHashMap<>();
        if (e.getEnvJson() == null || e.getEnvJson().isBlank()) {
            return env;
        }
        Map<String, String> raw;
        try {
            raw = mapper.readValue(e.getEnvJson(), ENV_TYPE);
        } catch (Exception ex) {
            throw new DevMindException(ErrorCode.CONFLICT, "实例 env JSON 解析失败: " + ex.getMessage());
        }
        if (raw == null) {
            return env;
        }
        for (Map.Entry<String, String> entry : raw.entrySet()) {
            env.put(entry.getKey(), expandPlaceholders(entry.getValue(), e));
        }
        return env;
    }

    private String expandPlaceholders(String value, ClassifyInstanceEntity e) {
        if (value == null) {
            return "";
        }
        Matcher m = PKG_DIR_PLACEHOLDER.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            long packageId = Long.parseLong(m.group(1));
            ClassifyPackageInstallEntity install = installRepo
                    .findByPackageIdAndNodeId(packageId, e.getAgentNodeId())
                    .filter(i -> ClassifyPackageInstallEntity.STATUS_INSTALLED.equals(i.getStatus()))
                    .orElseThrow(() -> new DevMindException(ErrorCode.CONFLICT,
                            "env 引用的安装包 #" + packageId + " 尚未安装到节点 " + e.getAgentNodeId()
                                    + "（" + value + "）：请到「分类服务 → 安装包」先分发"));
            m.appendReplacement(sb, Matcher.quoteReplacement(install.getInstallDir()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ---------------- internals ----------------

    private void applyEditable(ClassifyInstanceEntity e, ClassifyInstanceRequest req) {
        e.setAgentNodeId(requireText(req.agentNodeId(), "必须绑定 agent 节点", 64));
        if (req.port() == null || req.port() < 1 || req.port() > 65535) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "端口非法: " + req.port());
        }
        e.setPort(req.port());
        String baseUrl = requireText(req.baseUrl(), "baseUrl 必填（服务端打 healthz/试分类用）", 512);
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            throw new DevMindException(ErrorCode.BAD_REQUEST,
                    "baseUrl 须以 http(s):// 开头: " + baseUrl);
        }
        e.setBaseUrl(baseUrl.replaceAll("/+$", ""));
        e.setAppPackageId(req.appPackageId());
        e.setPythonBin(req.pythonBin() == null || req.pythonBin().isBlank()
                ? DEFAULT_PYTHON_BIN : req.pythonBin().trim());
        e.setEnvJson(writeEnv(req.env()));
        e.setCommandOverride(trimTo(req.commandOverride(), 1024));
    }

    private String writeEnv(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return null;
        }
        try {
            return mapper.writeValueAsString(env);
        } catch (Exception ex) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, "env 序列化失败: " + ex.getMessage());
        }
    }

    private ClassifyInstanceEntity require(long id) {
        return repo.findById(id).orElseThrow(
                () -> new DevMindException(ErrorCode.NOT_FOUND, "分类实例不存在: id=" + id));
    }

    private AgentNodeConnector connector() {
        AgentNodeConnector connector = connectorProvider.getIfAvailable();
        if (connector == null) {
            throw new DevMindException(ErrorCode.CONFLICT, "agent 模块未装配，无法下发实例管控指令");
        }
        return connector;
    }

    private static String newRequestId(long id) {
        return "proc-" + id + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String pidFile(long id) {
        return "run/inst-" + id + "/proc.pid";
    }

    private static String logFile(long id) {
        return "logs/inst-" + id + ".log";
    }

    private static String requireText(String value, String what, int max) {
        if (value == null || value.isBlank()) {
            throw new DevMindException(ErrorCode.BAD_REQUEST, what);
        }
        return trimTo(value, max);
    }

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String t = value.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
