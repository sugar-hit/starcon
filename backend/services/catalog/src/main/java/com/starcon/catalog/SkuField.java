package com.starcon.catalog;

/**
 * SKU 列表列与筛选会用到的字段，并显式标注每个字段的事实归属。
 *
 * <p>归属 catalog 自身的字段可以由本库真实求值；归属其他服务的字段必须经 {@link SkuProjectionPort}
 * 读取，未接入时按 {@link #ownerNote()} 说明原因，而不是静默给出 0。
 */
public enum SkuField {
  /** 在仓数量。归属 07 库存（阶段 D3），catalog 无权威数据。 */
  ON_HAND("在仓数量", Source.INVENTORY, Kind.NUMERIC, true),
  /** 在途数量。归属 07 库存（阶段 D3），catalog 无权威数据。 */
  IN_TRANSIT("在途数量", Source.INVENTORY, Kind.NUMERIC, true),
  /** 可售数量。归属 07 库存（阶段 D3），catalog 无权威数据。 */
  AVAILABLE_QTY("可售数量", Source.INVENTORY, Kind.NUMERIC, true),
  /** 单位成本。归属 06 采购，catalog 无权威数据。 */
  UNIT_COST("单位成本", Source.PURCHASE, Kind.AMOUNT, true),
  /** 采购价。归属 06 采购，catalog 无权威数据。 */
  PURCHASE_PRICE("采购价", Source.PURCHASE, Kind.AMOUNT, true),
  /** 售价。归属 05 定价（阶段 D5），catalog 无权威数据。 */
  SALE_PRICE("售价", Source.PRICING, Kind.AMOUNT, true),
  /** 在售平台名。归属 04 刊登，catalog 无权威数据。 */
  LISTED_PLATFORMS("在售平台", Source.LISTING, Kind.TEXT, false);

  /** 字段事实归属。 */
  public enum Source {
    INVENTORY("07 库存"),
    PURCHASE("06 采购"),
    PRICING("05 定价"),
    LISTING("04 刊登");

    private final String label;

    Source(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 字段的数据形态，决定筛选条件是区间还是包含。 */
  public enum Kind {
    NUMERIC,
    AMOUNT,
    TEXT
  }

  private final String label;
  private final Source source;
  private final Kind kind;
  private final boolean rangeFilterable;

  SkuField(String label, Source source, Kind kind, boolean rangeFilterable) {
    this.label = label;
    this.source = source;
    this.kind = kind;
    this.rangeFilterable = rangeFilterable;
  }

  public String label() {
    return label;
  }

  public Source source() {
    return source;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * 该字段是否可能成为区间/布尔筛选条件。
   *
   * <p>文本型字段（在售平台）只能做包含匹配，不参与库存区间或价格区间。
   */
  public boolean rangeFilterable() {
    return rangeFilterable;
  }

  /** 本枚举里的字段全部由上游服务所有，catalog 无权威数据；恒为 false。 */
  public boolean catalogAuthoritative() {
    return false;
  }

  public String ownerNote() {
    return switch (source) {
      case INVENTORY ->
          "上游投影未接入：库存数量归 07 库存所有（阶段 D3），catalog 无权威数据";
      case PURCHASE -> "上游投影未接入：成本与采购价归 06 采购所有，catalog 无权威数据";
      case PRICING -> "上游投影未接入：售价归 05 定价所有（阶段 D5），catalog 无权威数据";
      case LISTING -> "上游投影未接入：在售平台归 04 刊登所有，catalog 无权威数据";
    };
  }

  public static SkuField parse(String value) {
    for (var f : values()) if (f.name().equals(value)) return f;
    throw new IllegalArgumentException("未知的 SKU 投影字段：" + value);
  }
}
