package com.devmind.session.dto;

import com.devmind.session.model.SessionTemplateEntity;

public record TemplateView(Long id, String code, String name, String prompt, int sortOrder, boolean enabled) {

    public static TemplateView from(SessionTemplateEntity e) {
        return new TemplateView(e.getId(), e.getCode(), e.getName(), e.getPrompt(), e.getSortOrder(), e.isEnabled());
    }
}
