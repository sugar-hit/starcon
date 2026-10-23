package com.starcon.finance;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 12.1.2.1 应付单的**上游事实**的统一形状。
 *
 * <p>三个上游（trade 的到货应付部分确认 / warehouse 的头程发运单 / channel 的广告花费）
 * 各自的原始形状差别很大，但它们要变成应付单时**需要的是同一组东西**：
 *
 * <ul>
 *   <li><b>幂等键</b>（{@code sourceReference}）—— 同一笔上游事实重复导入只会命中同一条；
 *   <li><b>应付对象</b>（供应商 / 承运商 / 平台）与名字；
 *   <li><b>金额与币种</b>；
 *   <li><b>数量与单价快照</b>（可空：服务费用天然没有数量单价；但**采购收货必须有单价快照** ——
 *       没有单价就没有「合同依据」，那正是蓝图禁止的「仅凭收货推导应付」）；
 *   <li><b>发生时刻与说明</b>。
 * </ul>
 *
 * <p>把三个上游压成这一个形状，是为了让「建应付单」只有**一条**代码路径 ——
 * 三条路径各写一遍的话，「采购收货必须带单价」「服务费用可以不带数量」这类差异
 * 会散落在三处，改一处忘另一处。
 */
public record PayableSourceFact(
    /** 上游单号（幂等键的一部分）。 */
    String sourceReference,
    /** 上游单据的主键（写回用：确认动作要带它）。 */
    UUID sourceId,
    /** 应付对象类型。 */
    PayableModels.CounterpartyType counterpartyType,
    /** 应付对象主档 id（上游给得出就有，给不出就是 null）。 */
    UUID counterpartyRef,
    /** 应付对象名字（**必填**：没有名字的单子在账上等于没有对手方）。 */
    String counterpartyName,
    /** 币种（三位大写）。 */
    String currency,
    /** 金额。 */
    BigDecimal amount,
    /** 数量（可空）。 */
    BigDecimal quantity,
    /** 单价快照（可空；采购收货来源**必填**）。 */
    BigDecimal unitPrice,
    /** 明细描述。 */
    String description,
    /** 事实发生时刻（可空）。 */
    OffsetDateTime occurredAt,
    /** 备注 / 依据说明。 */
    String note) {}
