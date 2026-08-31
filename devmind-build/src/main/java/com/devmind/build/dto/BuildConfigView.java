package com.devmind.build.dto;

public record BuildConfigView(String projectId, String executor, Long remoteServerId, int concurrencyLimit) {
}
