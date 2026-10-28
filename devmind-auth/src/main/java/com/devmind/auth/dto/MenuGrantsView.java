package com.devmind.auth.dto;

import java.util.List;
import java.util.Map;

/** CAP-61 角色→可见 menuKey 集合。ADMIN 列恒为全量（前端置灰不可改）。 */
public record MenuGrantsView(List<String> roles, Map<String, List<String>> grants) {
}
