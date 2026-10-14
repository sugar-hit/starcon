package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SKU 投影端口的当前实现：catalog 尚未接入任何跨服务投影。
 *
 * <p>库存属 07（阶段 D3）、售价属 05（阶段 D5）、成本与采购价属 06 采购、在售平台属 04 刊登， 这四个服务在本阶段均未落地，因此 {@link
 * #supportedFields()} 为空、{@link #value} 一律返回 available=false。列表据此把对应列标成「未接入」，依赖它们的筛选条件被显式拒绝（422），
 * 而不是返回 0 或空列表——那会把"数据还没接"说成"没有库存、没有售价"。
 *
 * <p>07/05/06/04 落地后，用真实适配器替换本 Bean 即可，列表与筛选逻辑无需改动。
 */
@Component
public class UnwiredSkuProjectionPort implements SkuProjectionPort {
  @Override
  public List<SkuField> supportedFields() {
    return List.of();
  }

  @Override
  public Projection value(UUID tenantId, UUID skuId, SkuField field) {
    return new Projection(skuId, field, BigDecimal.ZERO, "", false, field.ownerNote());
  }
}
