package com.devmind.session.dto;

import java.util.List;

/**
 * worktree diff 摘要。
 */
public record DiffView(String stat, List<String> files, boolean hasChanges) {
}
