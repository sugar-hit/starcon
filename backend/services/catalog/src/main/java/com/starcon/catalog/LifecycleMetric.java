package com.starcon.catalog;

/**
 * 批量规则可引用的指标。每个指标显式标注事实归属：catalog 自己的表可以真实求值， 归属其他服务的指标必须经 {@link LifecycleProjectionPort}
 * 读取，未接入则拒绝执行。
 */
public enum LifecycleMetric {
  /** SKU 总数。归属 catalog 自身权威数据。 */
  SKU_COUNT("SKU 总数", Source.CATALOG, 0),
  /** 已启用 SKU 数。归属 catalog 自身权威数据。 */
  ACTIVE_SKU_COUNT("已启用 SKU 数", Source.CATALOG, 0),
  /** 必填属性齐备的已启用 SKU 数，是"能否上架"的权威判据。归属 catalog 自身权威数据。 */
  READY_SKU_COUNT("资料齐备的已启用 SKU 数", Source.CATALOG, 0),
  /** 在仓可售库存量。归属 07 库存（阶段 D3），catalog 无权威数据。 */
  INVENTORY_ON_HAND("在仓可售库存量", Source.INVENTORY, 0),
  /** 近 30 日销量。归属 13 分析（阶段 D7），catalog 无权威数据。 */
  SALES_QTY_30D("近 30 日销量", Source.ANALYTICS, 0),
  /** 已发布平台刊登数。归属 04 刊登，catalog 无权威数据。 */
  LISTING_PUBLISHED_COUNT("已发布平台刊登数", Source.LISTING, 0);

  /** 指标事实归属。 */
  public enum Source {
    CATALOG("catalog 自身"),
    INVENTORY("07 库存"),
    ANALYTICS("13 分析"),
    LISTING("04 刊登");

    private final String label;

    Source(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private final String label;
  private final Source source;
  private final int scale;

  LifecycleMetric(String label, Source source, int scale) {
    this.label = label;
    this.source = source;
    this.scale = scale;
  }

  public String label() {
    return label;
  }

  public Source source() {
    return source;
  }

  public int scale() {
    return scale;
  }

  /** 该指标能否由 catalog 直接求值（不依赖外部服务）。 */
  public boolean catalogAuthoritative() {
    return source == Source.CATALOG;
  }

  public String ownerNote() {
    return switch (source) {
      case CATALOG -> "由 catalog 自身数据求值";
      case INVENTORY -> "上游投影未接入：库存量归 07 库存所有（阶段 D3），catalog 无权威数据";
      case ANALYTICS -> "上游投影未接入：销量归 13 分析所有（阶段 D7），catalog 无权威数据";
      case LISTING -> "上游投影未接入：平台刊登归 04 刊登所有，catalog 无权威数据";
    };
  }

  public static LifecycleMetric parse(String value) {
    for (var m : values()) if (m.name().equals(value)) return m;
    throw new IllegalArgumentException("未知的生命周期指标：" + value);
  }
}
