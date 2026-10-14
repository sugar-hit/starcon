package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * SKU 详情需要的跨服务聚合读取端口。
 *
 * <p>3.1.2.2 的详情页是整个 SKU 档案的入口，但它聚合的库存分布（07）、成本与采购价（06）、 售价（05）、平台
 * listing（04）、各单据引用（04/06/07/08/09/11）都不是 catalog 的事实。 蓝图明确「库存数以 07 为准，售价以 05
 * 为准」，所以这些数据**不落成本地列**—— 落了就等于 catalog 成了第二个事实来源，两边数字迟早对不上，而且没人说得清该信哪个。
 *
 * <p>未接入时取不到就是取不到：{@link UnwiredSkuDetailProjectionPort} 显式声明未接入， 详情页把对应分区标成「未接入」并写明归哪个服务，而不是回 0
 * 或空数组。 0 会被读成"库存为零"，空数组会被读成"这个 SKU 没有任何平台 listing"——两者都是假事实。
 *
 * <p>本端口只承载 catalog 之外的数据；catalog 自有的字段（编码、规格、有效值、包装、换算） 直接走 jOOQ 读本库，不经过这里。
 */
public interface SkuDetailProjectionPort {

  /** 跨服务子分区的归属与可用性。详情页按这个逐区渲染「未接入」而不是编数字。 */
  enum Source {
    /** 库存分布（分仓）。归 07 库存。 */
    INVENTORY_DISTRIBUTION("INVENTORY_DISTRIBUTION", "库存分布", "07 库存"),
    /** 成本与采购价。归 06 采购。 */
    COST("COST", "成本与采购价", "06 采购"),
    /** 售价。归 05 定价。 */
    SALE_PRICE("SALE_PRICE", "售价", "05 定价"),
    /**
     * 平台 listing。归 04 刊登。
     *
     * <p>店铺的 {@code seller_sku} 是**平台属性**，不是内部编码（蓝图 3.1.2.3 的口径）： 内部编码由 catalog 拥有，映射关系在 04
     * 维护，所以这个分区不能反向写回 sku.code。
     */
    LISTINGS("LISTINGS", "平台 listing", "04 刊登"),
    /** 各单据引用（刊登 / 采购单 / 出入库 / 订单 / 售后）。分属 04/06/07/08/09/11。 */
    DOCUMENT_REFERENCES(
        "DOCUMENT_REFERENCES", "各单据引用", "04 刊登 / 06 采购 / 07 库存 / 08 仓储 / 09 订单 / 11 售后");

    private final String code;
    private final String label;
    private final String ownerLabel;

    Source(String code, String label, String ownerLabel) {
      this.code = code;
      this.label = label;
      this.ownerLabel = ownerLabel;
    }

    public String code() {
      return code;
    }

    public String label() {
      return label;
    }

    /** 事实归属服务的说明，例如「07 库存」。 */
    public String ownerLabel() {
      return ownerLabel;
    }

    /** 未接入时给界面看的一句话：说明归属与为什么不显示数字。 */
    public String unwiredNote() {
      return "上游投影未接入：" + label + "的事实来源是" + ownerLabel + "，这些服务本阶段尚未开发，catalog 无权威数据。";
    }
  }

  /**
   * 某个子分区的取值。
   *
   * @param source 子分区
   * @param available 归属服务是否已接入；false 表示"取不到"，**不是**"值为 0"
   * @param rows 分区内的明细行（分仓明细、平台 listing、各单据引用……）
   * @param note 未接入时说明归哪个服务、哪个阶段
   */
  record Projection(Source source, boolean available, List<Row> rows, String note) {}

  /**
   * 子分区的一行。字段全部可空：不同分区用不同列，硬凑成一张宽表只会多出一堆永远为 null 的字段。
   *
   * @param key 行内唯一键（例如仓库 code、平台 code、单据类型）
   * @param label 展示名
   * @param quantity 数量型取值（分仓在库量等），归属服务未接入时为 null
   * @param amount 金额型取值（售价、成本），归属服务未接入时为 null
   * @param state 行状态文本（在售 / 停售 / 已冻结……）
   * @param detail 行补充说明（例如平台的 seller_sku，与内部编码明确区分）
   */
  record Row(
      String key,
      String label,
      BigDecimal quantity,
      BigDecimal amount,
      String state,
      String detail) {}

  /** 该实现能提供的子分区；未列出的视为未接入。 */
  List<Source> supportedSources();

  /** 单个 SKU 的某个子分区。未接入的分区必须返回 available=false，而不是返回空行或 0。 */
  Projection value(UUID tenantId, UUID skuId, Source source);

  default boolean supports(Source source) {
    return supportedSources().contains(source);
  }
}
