package com.devmind.session.service;

import com.devmind.common.agent.SessionOutputSink;
import com.devmind.session.model.SessionOutputEntity;
import com.devmind.session.repo.SessionOutputRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 会话产出存储（CAP-37 FR-01）：{@link SessionOutputSink} 实现，供 devmind-agent 上传端点
 * 经 ObjectProvider 探测调用；flow 引擎直接注入本服务按文件名读产出（不再依赖 worktree 路径）。
 * 同 sessionId+fileName 覆盖旧值（重发/重新分析幂等）。
 */
@Service
public class SessionOutputService implements SessionOutputSink {

    private static final Logger log = LoggerFactory.getLogger(SessionOutputService.class);

    private final SessionOutputRepository repo;

    public SessionOutputService(SessionOutputRepository repo) {
        this.repo = repo;
    }

    /** 逐文件 upsert（controller 同步链路，save 自身事务即时提交，不加类级事务）。 */
    @Override
    public void store(String sessionId, List<OutputFile> files) {
        for (OutputFile f : files) {
            SessionOutputEntity e = repo.findBySessionIdAndFileName(sessionId, f.fileName())
                    .orElseGet(() -> {
                        SessionOutputEntity n = new SessionOutputEntity();
                        n.setSessionId(sessionId);
                        n.setFileName(f.fileName());
                        return n;
                    });
            e.setContent(f.content());
            e.setCreatedAt(Instant.now());
            repo.save(e);
        }
        log.info("会话产出已存储: session={} files={}", sessionId,
                files.stream().map(OutputFile::fileName).toList());
    }

    /** 按会话+文件名读产出内容（flow 引擎读取入口）；无记录返回 empty。 */
    public Optional<String> findContent(String sessionId, String fileName) {
        return repo.findBySessionIdAndFileName(sessionId, fileName).map(SessionOutputEntity::getContent);
    }
}
