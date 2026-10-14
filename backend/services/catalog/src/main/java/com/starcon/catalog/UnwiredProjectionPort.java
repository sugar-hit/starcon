package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 生命周期投影端口的当前实现：catalog 尚未接入任何跨服务投影。
 *
 * <p>库存属 07（阶段 D3）、销量属 13（阶段 D7）、平台刊登属 04，这三个服务在本阶段均未落地， 因此 {@link #supportedMetrics()} 为空、{@link
 * #quantity} 一律返回 available=false。 批量规则引用这些指标时由 {@link LifecycleService} 显式拒绝执行，而不是返回 0 让规则"跑通"。
 * 07/13/04 落地后，用真实适配器替换本 Bean 即可，规则与求值逻辑无需改动。
 */
@Component
public class UnwiredProjectionPort implements LifecycleProjectionPort {
  @Override
  public List<LifecycleMetric> supportedMetrics() {
    return List.of();
  }

  @Override
  public Quantity quantity(UUID tenantId, UUID spuId, LifecycleMetric metric) {
    return new Quantity(spuId, metric, BigDecimal.ZERO, false, metric.ownerNote());
  }
}
