package com.starcon.catalog;

import java.math.BigDecimal;
import java.util.List;

/**
 * 僵尸主数据清理的四个**引用维度**。
 *
 * <p>每个维度显式标注事实归属：catalog 自己不拥有其中任何一个的原始数据， 取值一律来自 {@code master_data_reference_stats}
 * 本地快照表，由上游服务经授权 API 推送。
 *
 * <p>与 {@link LifecycleMetric} 的分工：那边是「批量生命周期规则要读的指标」， 其中 catalog 自有的（SKU
 * 数等）直接查本库；这边**四个维度全部是跨服务的**， 所以本枚举里没有 {@code CATALOG} 取值 —— 这是 3.9.2.2 与 3.1.1.3 最大的结构差异。
 *
 * <p><b>三个计数 + 一个金额，同一列 {@code numeric(19,6)}</b>：判定上它们只有一种用法 ——「是否为零」。分成 count / amount
 * 两列会让四行里有三行恒有一列为 NULL， 而 NULL 在覆盖判定里含义是「不知道」，那是语义污染：填 0 的那行与从没观测过的那行 会长得完全一样。
 */
public enum ZombieMetric {
  /** 订单引用数。归属 trade（订单）服务 —— 本仓库当前**不存在**该服务。 */
  ORDER_LINE_COUNT("订单引用数", "trade", "trade（订单）"),
  /** 在仓库存量。归属 warehouse（07 库存）—— 该服务**已交付**，接入只差推送方。 */
  INVENTORY_ON_HAND("在仓库存量", "warehouse", "07 库存"),
  /** 平台刊登数。归属 channel（04 刊登）—— 该服务已交付平台/连接/店铺/同步，**尚无刊登实体**。 */
  LISTING_COUNT("平台刊登数", "channel", "04 刊登"),
  /** 单位成本。归属 purchase（06 采购）—— 本仓库当前**不存在**该服务。 */
  UNIT_COST("单位成本", "purchase", "06 采购");

  private final String label;
  private final String source;
  private final String owner;

  ZombieMetric(String label, String source, String owner) {
    this.label = label;
    this.source = source;
    this.owner = owner;
  }

  public String label() {
    return label;
  }

  /** 推送方标识（与 {@code master_data_reference_stats.source} 同口径）。 */
  public String source() {
    return source;
  }

  /** 事实所有者的人读名。 */
  public String owner() {
    return owner;
  }

  /** 该维度的零值。计数与金额共用零 —— 判定只问「是不是零」。 */
  public static BigDecimal zero() {
    return BigDecimal.ZERO;
  }

  public static ZombieMetric parse(String value) {
    for (var m : values()) if (m.name().equals(value)) return m;
    throw new IllegalArgumentException("未知的引用维度：" + value);
  }

  /** 词表顺序固定：概览与规则配置界面按这个顺序渲染，避免每次刷新顺序不同。 */
  public static List<ZombieMetric> ordered() {
    return List.of(ORDER_LINE_COUNT, INVENTORY_ON_HAND, LISTING_COUNT, UNIT_COST);
  }
}
