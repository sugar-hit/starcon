package com.starcon.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 5.5.1.1 价格历史的**写端口**：上游把「发生了一次价格变更」这件事投递进来。
 *
 * <p><b>为什么要有这个端口</b>：蓝图说「输入：价格变更事件（人工与自动规则都记）」，
 * 而**产生**价格变更的模块不止一个：
 *
 * <ul>
 *   <li><b>5.4.1.1 调价单</b>（与本模块同批并行开发，**本仓此刻尚不存在**）——
 *       它是调价的写入所有者，改完价要把变更投递给本模块。
 *   <li><b>5.4.1.2 批量调价</b>（**已交付**）—— 批量改完价后逐条投递。
 *   <li><b>5.3.2.2 价格规则</b>（**已交付**，但它只产出**建议**）——
 *       规则触发的改价由 5.4.1.1 执行后投递；本模块**只读**它的建议表作为「待执行建议」。
 * </ul>
 *
 * <p>🔴 <b>「每张事实表只有一个写入所有者」</b>：{@code price_change_history} 的写入所有者
 * **只有本模块**（{@link PriceHistoryService}）。这个端口是本模块**对外**的唯一入口 ——
 * 上游**不**直接写表，它们调这个端口。这条纪律保证了「一次改价只留下一条变更事实」，
 * 而不是三个上游各写一遍。
 *
 * <p>🔴 <b>幂等</b>：{@link PriceChangeEvent#eventKey()} 在租户内唯一。
 * 同一个投递方重复投递同一次变更（重试、重复执行、补偿任务）**不会**堆出第二行 ——
 * 调用方拿回的是**同一条**变更事实。
 *
 * <p>🔴 <b>利润快照由本模块算</b>：{@link PriceChangeEvent#profitBasis()} 里带的是
 * 5.3.1.1 测算器的**输入参数**，不是算好的数字 —— 本模块统一调
 * {@link ProfitCalculatorService#calculate}，保证时间轴上的利润与 5.3.1.1 / 5.4.1.2
 * **同一套算术**（上游若自己算一遍，两套口径必然对不上）。
 * {@code profitBasis} 为 null ⇒ **不测算**，利润四列为 null 且 {@code decisionReady=false}
 * （「没测准」不是「毛利为 0」）。
 *
 * <p><b>字段映射（上游接入时按这张表填）</b>：
 *
 * <table border="1">
 *
 * <caption>写端口字段映射</caption>
 *
 * <tr><th>端口字段</th><th>上游对应</th><th>必填</th></tr>
 *
 * <tr><td>{@code skuId}</td><td>被改的 SKU</td><td>是</td></tr>
 *
 * <tr><td>{@code platformCode} / {@code siteCode}</td><td>店铺（= 一个 listing）</td><td>是</td></tr>
 *
 * <tr><td>{@code currency}</td><td>新旧价的币种（**两价同币**，跨币种请先折算再投递）</td><td>是</td></tr>
 *
 * <tr><td>{@code oldPrice}</td><td>改价前的价；null = 首次定价</td><td>否</td></tr>
 *
 * <tr><td>{@code newPrice}</td><td>改价后的价（**已生效**的价）</td><td>是</td></tr>
 *
 * <tr><td>{@code changeSource}</td><td>变更来源枚举名（{@code MANUAL_ADJUSTMENT} / {@code BATCH_ADJUSTMENT} / {@code PRICE_RULE} …）</td><td>是</td></tr>
 *
 * <tr><td>{@code reasonCode} / {@code reasonText}</td><td>原因字典编码 + 人话快照</td><td>否 / 是</td></tr>
 *
 * <tr><td>{@code operatorId}</td><td>操作人；自动规则触发的可空</td><td>否</td></tr>
 *
 * <tr><td>{@code sourceModule}</td><td>投递方模块编号（如 {@code 5.4.1.1}）</td><td>是</td></tr>
 *
 * <tr><td>{@code sourceReference}</td><td>投递方的单据号 / id</td><td>否</td></tr>
 *
 * <tr><td>{@code eventKey}</td><td>幂等键（建议 {@code <模块>:<单据>:<行>}）</td><td>是</td></tr>
 *
 * <tr><td>{@code effectiveAt}</td><td>变更**生效**的时刻</td><td>是</td></tr>
 *
 * <tr><td>{@code profitBasis}</td><td>5.3.1.1 测算器的输入参数</td><td>否</td></tr>
 *
 * <tr><td>{@code profitSnapshot}</td><td>上游已算好的利润快照（也来自 5.3.1.1 的引擎）</td><td>否</td></tr>
 *
 * </table>
 *
 * <p>🔴 {@code profitBasis} 与 {@code profitSnapshot} **给一个就行**，两个都给时**以 snapshot 为准**
 * （上游已经算过就别让它再算一遍）。
 */
public interface PriceChangeHistoryPort {

  /**
   * 登记一次价格变更。
   *
   * <p>🔴 **不抛业务异常**：本端口被上游在**自己的事务里**调用，
   * 而写历史是**记账**而不是**改价** —— 记账失败不该让已经改成功的价回滚，
   * 也不该让上游的整批调价失败。
   * 所以：利润测算失败 ⇒ 利润四列留 null 并写明原因；只有**入账本身**失败才抛出。
   *
   * @param actor 调用者（租户上下文只来自这里）
   * @param bearer 调用者的 Authorization 头。**利润快照要用它** ——
   *     5.3.1.1 的成本构成要跨服务读 warehouse 的头程与关税，没有它就算不出完整利润。
   *     传 null / 空 ⇒ **不测算利润**（利润四列留 null，「没测准」不是「毛利为 0」）。
   * @return 落库后的变更 id（幂等命中时是**已有那条**的 id）
   */
  UUID record(AccessService.Access actor, String bearer, PriceChangeEvent event);

  /** 一次价格变更。字段含义见本类的字段映射表。 */
  record PriceChangeEvent(
      UUID skuId,
      String platformCode,
      String siteCode,
      String currency,
      BigDecimal oldPrice,
      BigDecimal newPrice,
      String changeSource,
      String reasonCode,
      String reasonText,
      UUID operatorId,
      String sourceModule,
      String sourceReference,
      String eventKey,
      OffsetDateTime effectiveAt,
      ProfitBasis profitBasis,
      ProfitSnapshot profitSnapshot) {}

  /**
   * **上游已经算好的**利润快照。
   *
   * <p>🔴 上游（如 5.4.1.2 批量调价）在改价时**本来就要**跑一遍 5.3.1.1 的引擎
   * （改价前 / 改价后各一次），并把结果存在自己的明细行里。
   * 让本模块再跑一遍 = 同一批 listing 多翻一倍 warehouse 成本，
   * 而且 warehouse 抖一下就会把**已经算准的**利润降级成「没测准」。
   *
   * <p>⇒ 上游**已经用 5.3.1.1 的引擎算过**时，把结果经本记录传进来，
   * 本模块**原样落库**（不重算、不四舍五入、不改口径）。
   * 两边仍然是**同一套算术** —— 因为上游用的就是同一个引擎。
   *
   * @param decisionReady 上游那次测算的决策就绪度。false 时四个利润列必为 null。
   */
  record ProfitSnapshot(
      BigDecimal grossProfitBefore,
      BigDecimal grossMarginBefore,
      BigDecimal grossProfitAfter,
      BigDecimal grossMarginAfter,
      boolean decisionReady,
      String note) {}

  /**
   * 利润测算口径（5.3.1.1 的输入）。
   *
   * <p>🔴 这些是**假设**，不是事实：5.2.3.1 平台费用与 5.2.3.2 物流费用未交付，
   * 费率与运费只能由调用方作为手工假设给出。缺了它们，5.3.1.1 的**决策就绪闸**
   * 会让毛利率变成 null —— 那正是我们要的（「没测准」不许被渲染成 0）。
   *
   * @param costCurrency 成本币种；null = 与售价同币种
   * @param fxRate 1 成本币种 = fxRate 售价币种；跨币种时必填，**绝不按 1:1 折算**
   * @param quantity 数量（用于阶梯与运费）；null = 1
   */
  record ProfitBasis(
      String costCurrency,
      BigDecimal fxRate,
      BigDecimal quantity,
      BigDecimal platformFeeRate,
      BigDecimal platformFeeFixed,
      BigDecimal logisticsCostPerUnit,
      BigDecimal monthlyFixedCost,
      LocalDate costWindowFrom,
      LocalDate costWindowTo) {}
}
