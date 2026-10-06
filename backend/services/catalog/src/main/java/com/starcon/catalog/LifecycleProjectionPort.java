package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 生命周期批量规则需要的外部投影（库存 / 销量 / 刊登）读取端口。
 *
 * <p>catalog 是商品主数据的事实所有者，但不是库存与销量的所有者：库存属 07（阶段 D3），销量属 13（阶段 D7），
 * 刊登属 04。上游服务未接入前，本端口不提供任何假数据，取不到就是取不到——由 {@link UnwiredProjectionPort}
 * 显式声明未接入，由规则引擎拒绝执行，而不是返回 0 让规则"跑通"。
 *
 * <p>只依赖 catalog 自身权威数据的指标（SKU 是否齐备、是否可上架）不经过本端口，直接走 jOOQ 读本库。
 */
public interface LifecycleProjectionPort {

  /** 单个 SPU 上某个投影指标的取值与可用性。 */
  record Quantity(UUID spuId, LifecycleMetric metric, BigDecimal value, boolean available, String note) {}

  /** 该实现能提供的指标；未列出的指标视为未接入。 */
  List<LifecycleMetric> supportedMetrics();

  /** 单个 SPU 的投影值。未接入的指标应返回 available=false，而不是返回 0。 */
  Quantity quantity(UUID tenantId, UUID spuId, LifecycleMetric metric);

  default boolean supports(LifecycleMetric metric) {
    return supportedMetrics().contains(metric);
  }

  /** catalog 自有指标不经过本端口，直接读本库；跨服务指标必须经本端口取真实值。 */
  default boolean catalogAuthoritative(LifecycleMetric metric) {
    return metric.catalogAuthoritative();
  }
}