package com.devmind.integration.connector.github;

import com.devmind.common.exception.DevMindException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * GitHubConnector 纯函数单测：API 基址分流（github.com → api.github.com，
 * 其余按 GHE 拼 /api/v3）与 owner/repo 路径逐段编码。
 */
class GitHubConnectorUrlTest {

    @Test
    void githubCom走公共API入口() {
        assertEquals("https://api.github.com", GitHubConnector.apiBase("https://github.com"));
        assertEquals("https://api.github.com", GitHubConnector.apiBase("https://api.github.com"));
        assertEquals("https://api.github.com", GitHubConnector.apiBase("https://github.com/"));
    }

    @Test
    void GHE拼apiV3后缀() {
        assertEquals("https://ghe.acme.cn/api/v3", GitHubConnector.apiBase("https://ghe.acme.cn"));
        assertEquals("https://ghe.acme.cn/api/v3", GitHubConnector.apiBase("https://ghe.acme.cn/"));
    }

    @Test
    void 空base默认githubCom() {
        assertEquals("https://api.github.com", GitHubConnector.apiBase(null));
        assertEquals("https://api.github.com", GitHubConnector.apiBase("  "));
    }

    @Test
    void repoPath逐段编码保留斜杠() {
        assertEquals("apusic/dev-mind", GitHubConnector.encodeRepoPath("apusic/dev-mind"));
        assertEquals("my%20org/my%20repo", GitHubConnector.encodeRepoPath("my org/my repo"));
    }

    @Test
    void 非ownerRepo形式报错() {
        assertThrows(DevMindException.class, () -> GitHubConnector.encodeRepoPath("dev-mind"));
        assertThrows(DevMindException.class, () -> GitHubConnector.encodeRepoPath(null));
    }
}
