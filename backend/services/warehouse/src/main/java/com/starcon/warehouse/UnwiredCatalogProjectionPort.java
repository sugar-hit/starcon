package com.starcon.warehouse;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 跨服务筛选端口的当前实现：warehouse 尚未接入 catalog / channel 的读取适配器。
 *
 * <p>类目与品牌属 03 商品主数据、店铺属 02 渠道与店铺，这两者在本阶段均未接入，
 * 因此 {@link #supportedDimensions()} 为空、{@link #resolve} 一律抛
 * {@link CatalogProjectionPort.DimensionUnavailableException}。总览据此把对应筛选项
 * 标成「未接入」，并在请求真的带上该筛选时返回 422。
 *
 * <p>反向刻意不做的事：既不返回空集合（会被读成「按此条件没有库存」），也不在
 * warehouse 库里复制一份类目/品牌/店铺当权威表（那会让同一份类目在两个服务各有一份，
 * 迟早对不上）。上游接入后用真实适配器替换本 Bean，总览逻辑无需改动。
 */
@Component
public class UnwiredCatalogProjectionPort implements CatalogProjectionPort {
  @Override
  public List<Dimension> supportedDimensions() {
    return List.of();
  }

  @Override
  public Set<UUID> resolve(List<UUID> skuIds, Dimension dimension) {
    throw new DimensionUnavailableException(dimension);
  }
}
