package com.starcon.trade;

import java.math.BigDecimal;
import java.util.List;

/**
 * 6.1.2.1 **外部货源平台采集端口**（蓝图：「抓取与同步在售货源」）。
 *
 * <p>AGENTS.md 模块边界纪律：「外部适配器（Shopify / Amazon / ...）放在对应服务里，<b>每个 vendor 独立包，只暴露统一接口</b>」。
 * 本接口就是那个统一接口，服务层只认它，不认任何 vendor 的报文形状。
 *
 * <p><b>本仓纪律里最要紧的一条：绝不伪造平台响应。</b> 蓝图加粗句写死了「货源数据是外部快照，会变」，
 * 而快照一旦被编造，比价、比价排序、最低价汇总全都会变成谎言。所以接口的契约是：
 *
 * <ul>
 *   <li>接不上的 vendor 必须<b>显式失败</b>（{@link SourceUnavailableException}），并说明「还差什么」；</li>
 *   <li>平台返回失败 / 不可达，一律显式失败，并把平台给的错误信息如实带出来；</li>
 *   <li>平台返回的**坏数据**（价格区间写反、起订量为 0）不静默丢弃，而是计入
 *       {@link CollectedBatch#skipped()} 并写明原因 —— 「采到 12 条、2 条价格区间不自洽被跳过」
 *       是一条事实，「采到 10 条」是另一条事实，混起来就查不清货为什么少了。</li>
 * </ul>
 *
 * <p>两个实现（各自独立，不互相引用对方的 vendor 概念）：
 *
 * <ul>
 *   <li>{@link Alibaba1688SourceGateway} —— <b>1688 开放平台</b>：真实 HTTP 客户端，
 *       必须先在 {@code supplier_source_collector_endpoint} 登记 {@code base_url} 与 {@code credential_ref}
 *       才能调用；未登记时显式报「未接线」并说明缺哪个字段。</li>
 *   <li>{@link YiwuGouSourceGateway} —— <b>义乌购</b>：同一套形状的独立适配器，同样默认未接线。</li>
 * </ul>
 */
public interface SupplierSourceGateway {

  /** 平台标识，与 DB 的 {@code supplier_source_stall.platform} CHECK 同词表。 */
  String platform();

  /** 展示名（能力声明用，如「1688 开放平台」）。 */
  String displayName();

  /**
   * 本 vendor 当前<b>是否已接线</b>（当前租户是否登记了采集接入）。
   *
   * <p>未接线不是异常，是如实报出。报 true 却实际调不通，比直接报 false 更糟 ——
   * 前者会让人一直重试一个永远不存在的集成。
   */
  boolean wired();

  /** 未接线时给人看的「还差什么」（接上了返回空串）。 */
  String wiringRequirement();

  /**
   * 采集一个档口当前在售的货源。
   *
   * @throws SourceUnavailableException 未接线 / 平台不可达 / 平台返回失败
   */
  CollectedBatch collect(StallRequest request);

  /** 平台不可达 / 未接线 / 平台返回失败：调用方据此落 FAILED 批次并<b>如实</b>记下原因。 */
  class SourceUnavailableException extends RuntimeException {
    private final String reason;

    public SourceUnavailableException(String reason) {
      super(reason);
      this.reason = reason;
    }

    public String reason() {
      return reason;
    }
  }

  /** 采集请求：只带外部平台需要的东西，本服务的库结构不外泄给外部。 */
  record StallRequest(
      /** 平台侧的档口 ID（1688 的 shopId）。 */
      String platformStallId,
      /** 档口 URL（拼请求用；平台不需要时适配器可忽略）。 */
      String stallUrl,
      /** 单次采集最多取多少条（防止一次拉爆库）。 */
      int maxItems) {}

  /**
   * 一次采集的结果。
   *
   * @param items 采到的货源（价格区间与起订量都必须是**区间**与**真实起订量**，不是精确价与猜的量）
   * @param skipped 被跳过的条目及其原因（坏数据不静默丢弃）
   */
  record CollectedBatch(List<CollectedItem> items, List<String> skipped) {
    public CollectedBatch {
      items = items == null ? List.of() : List.copyOf(items);
      skipped = skipped == null ? List.of() : List.copyOf(skipped);
    }
  }

  /**
   * 一条被采到的货源。
   *
   * @param priceMin 价格区间**下限**
   * @param priceMax 价格区间**上限**（不得低于下限；外部只有单一价时上下限相同）
   * @param moq 起订量
   */
  record CollectedItem(
      String platformItemId,
      String title,
      BigDecimal priceMin,
      BigDecimal priceMax,
      String currency,
      BigDecimal moq,
      String spec,
      String imageUrl,
      String itemUrl,
      boolean onSale) {}

  /** 未接线时构造的那个异常，附带「还差什么」的人话说明。 */
  default SourceUnavailableException unwired() {
    return new SourceUnavailableException(
        displayName()
            + " 尚未接线："
            + wiringRequirement()
            + "。请先在「货源采集接入」登记网关地址与凭证引用"
            + "（credential_ref 指向 secret manager 里的键，本系统不保存任何明文凭证），"
            + "或把该档口改用「插件采集」推送货源条目。");
  }
}
