package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * SKU 列表需要的跨服务投影读取端口（库存 / 成本 / 售价 / 在售平台）。
 *
 * <p>catalog 是 SKU 档案的事实所有者，但**不是**这些数字的所有者：库存属 07（阶段 D3）， 售价属 05（阶段 D5），成本与采购价属 06 采购，在售平台属 04
 * 刊登。蓝图明确要求 「库存数以 07 为准，售价以 05 为准」，所以 catalog 既不能把它们落成自己的权威列， 也不能在内存里补一个 0 —— 那会让「没接入」看起来像「库存为零」。
 *
 * <p>上游服务未接入前，本端口不提供任何假数据，取不到就是取不到：由 {@link UnwiredSkuProjectionPort} 显式声明未接入，列表返回
 * available=false，依赖它的筛选条件被显式拒绝（422）， 而不是返回空列表假装「所有 SKU 都没缺货」。
 *
 * <p>只依赖 catalog 自身权威数据的字段（编码、名称、条码、规格、状态）不经过本端口，直接走 jOOQ 读本库。
 */
public interface SkuProjectionPort {

  /**
   * 单个 SKU 上某个投影字段的取值与可用性。
   *
   * @param skuId SKU 标识
   * @param field 投影字段
   * @param value 数值型字段的取值；{@code available=false} 时不可被当作真实数据使用
   * @param text 文本型字段的取值（如在售平台名）
   * @param available 归属服务是否已接入；false 表示"取不到"，不是"值为 0"
   * @param note 未接入时说明归哪个服务、哪个阶段
   */
  record Projection(
      UUID skuId, SkuField field, BigDecimal value, String text, boolean available, String note) {}

  /** 该实现能提供的字段；未列出的字段视为未接入。 */
  List<SkuField> supportedFields();

  /** 单个 SKU 的投影值。未接入的字段必须返回 available=false，而不是返回 0。 */
  Projection value(UUID tenantId, UUID skuId, SkuField field);

  default boolean supports(SkuField field) {
    return supportedFields().contains(field);
  }

  /** catalog 自有字段不经过本端口，直接读本库；跨服务字段必须经本端口取真实值。 */
  default boolean catalogAuthoritative(SkuField field) {
    return field.catalogAuthoritative();
  }
}
