package com.starcon.catalog;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SKU 详情投影端口的当前实现：catalog 尚未接入任何跨服务聚合。
 *
 * <p>07 库存 / 06 采购 / 05 定价 / 04 刊登在本阶段均未落地，因此 {@link #supportedSources()} 为空、
 * {@link #value} 一律返回 available=false 且 rows 为空。详情页据此把库存分布、成本与售价、
 * 平台 listing、各单据引用标成「未接入」，并写明归哪个服务——而不是回 0 或空数组：
 * 回 0 会被读成"库存为零"，空数组会被读成"没有任何平台 listing"，两者都是编造出来的事实。
 *
 * <p>这些服务落地后，用真实适配器替换本 Bean 即可，详情页的分区结构与有效值解析无需改动。
 */
@Component
public class UnwiredSkuDetailProjectionPort implements SkuDetailProjectionPort {

  @Override
  public List<Source> supportedSources() {
    return List.of();
  }

  @Override
  public Projection value(UUID tenantId, UUID skuId, Source source) {
    return new Projection(source, false, List.of(), source.unwiredNote());
  }
}