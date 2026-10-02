package com.devmind.bookmark.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * FR-09 浏览器书签导入入参：Netscape HTML 已由前端 DOMParser 解析成结构化树，
 * 服务端只收 JSON，不在 Java 侧写 HTML 解析器。
 */
public record BookmarkImportRequest(@NotEmpty(message = "导入内容不能为空") List<Node> nodes) {

    /**
     * 树节点：type=folder 时 name/children 有效；type=bookmark 时 title/url/description/tags 有效。
     * 不在任何文件夹内的松散书签直接挂在根 nodes 里，导入后落未分组。
     */
    public record Node(
            String type,
            String name,
            String title,
            String url,
            String description,
            List<String> tags,
            List<Node> children) {
    }
}
