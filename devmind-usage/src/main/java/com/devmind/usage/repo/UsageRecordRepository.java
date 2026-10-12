package com.devmind.usage.repo;

import com.devmind.usage.model.UsageRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 逐回合用量账本仓储。写入由 {@code UsageRecordService}（UsageRecordSink 实现）发起；
 * 只读聚合查询（请求日志分页/按小时分桶）随查询端点一并加在本接口。
 */
public interface UsageRecordRepository extends JpaRepository<UsageRecordEntity, Long> {
}
