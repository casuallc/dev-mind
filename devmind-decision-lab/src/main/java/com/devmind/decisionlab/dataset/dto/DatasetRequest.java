package com.devmind.decisionlab.dataset.dto;

/**
 * CAP-56 建集/改集请求。{@code kind} 只认 BENCHMARK / REPLAY（认不出报 400，不猜默认值——
 * 猜错会让回流集被当成基准集用，两者的可信度不是一回事）。
 */
public record DatasetRequest(String name, String kind, String note) {
}
