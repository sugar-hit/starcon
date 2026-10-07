package com.starcon.warehouse;

import java.util.List;
import java.util.UUID;

/**
 * 7.1.1.1 总览需要的**跨服务**筛选维度读取端口（类目 / 品牌属 catalog，店铺属 channel）。
 *
 * <p>warehouse 是库存的唯一所有者，但**不是**商品档案与店铺的档案所有者：类目与品牌属
 * 03 商品主数据，店铺属 02 渠道与店铺。蓝图要求总览「支持按类目/品牌/店铺筛选」，
 * 所以这三个维度必须真读上游 —— 但既不能跨库 JOIN，也不能在 warehouse 里复制一份
 * 权威类目/品牌/店铺表（那会出现两份"类目"各说各话，正是模块边界纪律要避免的）。
 *
 * <p>上游未接入前，本端口不提供任何假数据：取不到就是取不到，由 {@link UnwiredCatalogProjectionPort}
 * 显式声明未接入，依赖它的筛选条件被**显式拒绝（422）**，而不是返回空列表假装
 * 「按这个类目筛不出任何 SKU」—— 那会让用户误判成真的没有库存。
 *
 * <p>本端口只读，warehouse 不通过它写任何上游数据。
 */
public interface CatalogProjectionPort {

  /** 总览可用的跨服务筛选维度。 */
  enum Dimension {
    /** 类目：catalog 拥有（spu.category 目前是自由文本列，类目树 3.3.1.1 未开发）。 */
    CATEGORY("类目", "catalog", "03 商品主数据"),
    /** 品牌：catalog 拥有。 */
    BRAND("品牌", "catalog", "03 商品主数据"),
    /** 店铺：channel 拥有。 */
    SHOP("店铺", "channel", "02 渠道与店铺");

    private final String label;
    private final String owner;
    private final String ownerNote;

    Dimension(String label, String owner, String ownerNote) {
      this.label = label;
      this.owner = owner;
      this.ownerNote = ownerNote;
    }

    public String label() {
      return label;
    }

    /** 归属服务。 */
    public String owner() {
      return owner;
    }

    /** 未接入时对外说明：归哪个服务哪个域。 */
    public String ownerNote() {
      return ownerNote;
    }
  }

  /** 该实现能提供的维度；未列出的维度视为未接入。 */
  List<Dimension> supportedDimensions();

  /**
   * 把一组 SKU 解析为该维度下的取值集合，用于按维度筛选。
   *
   * @param skuIds 本租户内参与筛选的 SKU（来自 warehouse 自己的余额行，不是全量 SKU）
   * @param dimension 目标维度
   * @return 该维度下命中的 SKU 标识集合；未接入的实现**必须**抛
   *     {@link DimensionUnavailableException}，不得返回空集合冒充"都不命中"
   */
  java.util.Set<UUID> resolve(java.util.List<UUID> skuIds, Dimension dimension);

  default boolean supports(Dimension dimension) {
    return supportedDimensions().contains(dimension);
  }

  /** 依赖未接入维度的筛选被显式拒绝时抛出，由 ApiErrorHandler 翻译成 422。 */
  class DimensionUnavailableException extends RuntimeException {
    public DimensionUnavailableException(Dimension dimension) {
      super(
          "「"
              + dimension.label()
              + "」筛选依赖的"
              + dimension.owner()
              + "数据尚未接入本服务："
              + dimension.ownerNote()
              + "。"
              + "该筛选已被显式拒绝，而不是按空结果返回——否则会把「查不了」误显示成「没有库存」。");
    }
  }
}
