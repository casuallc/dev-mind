package com.devmind.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.info.GitProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 「关于」弹框数据源：产品名称/版本/构建时间/commit 信息。
 * 构建信息来自 spring-boot build-info 与 git-commit-id 插件产物（target/classes 下
 * META-INF/build-info.properties、git.properties）；IDE 直跑未走 package 时无这两份资源，
 * ObjectProvider 拿不到 bean，各字段兜底为 null/默认版本。
 */
@RestController
@RequestMapping("/api")
public class AboutController {

    private static final String FALLBACK_VERSION = "0.1.0-SNAPSHOT";

    private final ObjectProvider<BuildProperties> buildProperties;
    private final ObjectProvider<GitProperties> gitProperties;

    public AboutController(ObjectProvider<BuildProperties> buildProperties,
                           ObjectProvider<GitProperties> gitProperties) {
        this.buildProperties = buildProperties;
        this.gitProperties = gitProperties;
    }

    @GetMapping("/about")
    public Map<String, Object> about() {
        BuildProperties build = buildProperties.getIfAvailable();
        GitProperties git = gitProperties.getIfAvailable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "Dev-Mind");
        body.put("description", "积木式研发能力平台");
        body.put("version", build != null ? build.getVersion() : FALLBACK_VERSION);
        body.put("buildTime", build != null ? build.getTime() : null);
        body.put("commit", git != null ? git.getShortCommitId() : null);
        body.put("branch", git != null ? git.getBranch() : null);
        body.put("commitTime", git != null ? git.getCommitTime() : null);
        return body;
    }
}
