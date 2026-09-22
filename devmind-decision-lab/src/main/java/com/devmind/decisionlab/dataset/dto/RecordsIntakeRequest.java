package com.devmind.decisionlab.dataset.dto;

/**
 * CAP-56 「从决策记录收编」的筛选条件。
 *
 * <p>{@code since} 是 {@code yyyy-MM-dd} 字符串（本机时区当天 00:00 起，与记录页的日期筛选同口径），
 * 空 = 不限时间。</p>
 *
 * <p><b>为什么收字符串而不是 {@code LocalDate}</b>：预览（查询串）与收编（请求体）两个入口必须
 * 用同一个解析器。两处各自解析的话，某天只有一处改了格式，就会出现"预览说能收 120 条、
 * 收编只收了 3 条"——预告与实际不符比没有预告更糟。串的解析与报错都在
 * {@code DatasetService.parseSince} 一处。</p>
 */
public record RecordsIntakeRequest(String capability, String since) {
}
