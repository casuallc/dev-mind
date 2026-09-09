package com.devmind.build.dto;

public record BuildConfigView(String projectId, String executor, String agentNodeId, int concurrencyLimit) {
}
