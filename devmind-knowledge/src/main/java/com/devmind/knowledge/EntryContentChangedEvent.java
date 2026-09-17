package com.devmind.knowledge;

/**
 * 条目内容变更事件（CAP-44 FR-04）：保存/更新/采纳/重同步后发布，异步摄入管线
 * （分块 + embedding）据此重索引该条目。事件在事务提交后由监听器消费，
 * 发布方不阻塞、不关心索引结果。
 *
 * @param entryId 条目 ID
 * @param kbId    所属知识库 ID
 */
public record EntryContentChangedEvent(Long entryId, Long kbId) {
}
