package com.starcon.trade;

import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleConfirmationResult;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleOrderStatus;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleSourceType;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleStatus;
import static com.starcon.trade.DevelopmentSampleModels.DevelopmentSampleUsageType;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.1.2 打样与样品管理的纯规则（不碰数据库，可单测）。
 *
 * <p><b>为什么单独一个类</b>：本模块最容易出错的三件事是「状态迁移合不合法」、
 * 「确认结果与目标状态对不对得上」与「打样还是达人寄样」。这三件事一旦混进 jOOQ 的 SQL 里，
 * 读代码的人就看不出「为什么这个样品不能直接跳过打样中变成已确认」，
 * 也看不出「为什么营销寄样进不来」——
 * 而这正是蓝图加粗句「打样费用是新品开发的真实成本」与
 * 「与达人寄样（14.5.1.2）区分打样与营销样品」要防的分叉。
 *
 * <p><b>七条纪律</b>：
 *
 * <ol>
 *   <li><b>打样单状态机是单向的</b>：{@code REQUESTED → IN_PROGRESS → COMPLETED → CLOSED}，
 *       外加任意非终态 → {@code CANCELLED}。{@code CLOSED} / {@code CANCELLED} 是**终态**，
 *       没有出边，**不提供「恢复」**。</li>
 *   <li><b>样品状态机</b>：{@code REQUESTED → MAKING → SHIPPED → RECEIVED}，
 *       确认后进 {@code CONFIRMED}（通过）/ {@code REVISING}（修改）/ {@code ABANDONED}（放弃）。
 *       {@code CONFIRMED} / {@code ABANDONED} 是**终态**；
 *       {@code REVISING} **不是**终态 —— 它回到 {@code MAKING} 重新打样，或直接
 *       {@code CONFIRMED}（改后通过）。</li>
 *   <li><b>确认类状态必须由一条确认记录授权</b>：目标状态是
 *       {@code CONFIRMED / REVISING / ABANDONED} 时，服务层会先落一条**不可改写**的确认记录，
 *       再改状态；数据库守卫触发器再挡一道（没有对应记录的状态变更直接被拒）。</li>
 *   <li><b>「修改」必须带修改意见</b>：只说「要改」不说改什么，下一轮打样的人还是得猜。</li>
 *   <li><b>用途只有一个值</b>：{@link DevelopmentSampleUsageType} 只有
 *       {@code DEVELOPMENT}。达人寄样（14.5.1.2）是**营销样品**，属 14 卷，
 *       **不在本模块**；这里**不提供「其它」桶**，把它挡在门外。</li>
 *   <li><b>来源与来源实体必须自洽</b>：供应商打样必须挂供应商；自有产打样**不许**挂供应商或档口；
 *       档口打样必须挂档口。</li>
 *   <li><b>金额与数量</b>：金额必须 &gt; 0（一条 0 元的成本明细不携带任何信息），
 *       数量必须 &gt; 0，币种三位大写字母。**禁浮点**：全链路 BigDecimal。</li>
 * </ol>
 */
public final class DevelopmentSampleRules {

  private DevelopmentSampleRules() {}

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  public static final int MONEY_SCALE = 4;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  public static final int QUANTITY_SCALE = 6;

  // ================================================================ 打样单状态机

  /**
   * 打样单从某个状态**允许**迁到哪些状态。
   *
   * <p>与 V261 迁移里 {@code guard_development_sample_order_status()} 的 CASE 分支**逐条同构** ——
   * 服务层先挡一道（给出可读的 400），数据库层再挡一道（挡住绕过服务的写入）。
   */
  public static Set<DevelopmentSampleOrderStatus> allowedOrderTargets(DevelopmentSampleOrderStatus from) {
    return switch (from) {
      case REQUESTED -> ordered(DevelopmentSampleOrderStatus.IN_PROGRESS, DevelopmentSampleOrderStatus.CANCELLED);
      case IN_PROGRESS -> ordered(DevelopmentSampleOrderStatus.COMPLETED, DevelopmentSampleOrderStatus.CANCELLED);
      case COMPLETED -> ordered(DevelopmentSampleOrderStatus.CLOSED);
      // 🔴 已关闭 / 已取消是终态：没有出边，不提供「恢复」。
      case CLOSED -> ordered();
      case CANCELLED -> ordered();
    };
  }

  /** 打样单状态是否终态。 */
  public static boolean isTerminalOrderStatus(DevelopmentSampleOrderStatus status) {
    return status == DevelopmentSampleOrderStatus.CLOSED || status == DevelopmentSampleOrderStatus.CANCELLED;
  }

  /** 校验一次打样单状态迁移；不合法就 400。 */
  public static void requireOrderTransition(
      DevelopmentSampleOrderStatus from, DevelopmentSampleOrderStatus to) {
    if (from == to) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "目标状态与当前状态相同（" + from.label() + "），这不是一次状态迁移");
    }
    Set<DevelopmentSampleOrderStatus> allowed = allowedOrderTargets(from);
    if (!allowed.contains(to)) {
      String targets =
          allowed.isEmpty()
              ? "无（已关闭 / 已取消是终态，不提供恢复）"
              : allowed.stream().map(DevelopmentSampleOrderStatus::label).toList().toString();
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "打样单不能从「" + from.label() + "」迁到「" + to.label() + "」。允许的目标状态：" + targets);
    }
  }

  // ================================================================ 样品状态机

  /**
   * 样品从某个状态**允许**迁到哪些状态。
   *
   * <p>与 V261 迁移里 {@code guard_development_sample_status()} 的 CASE 分支**逐条同构**。
   */
  public static Set<DevelopmentSampleStatus> allowedSampleTargets(DevelopmentSampleStatus from) {
    return switch (from) {
      case REQUESTED -> ordered(DevelopmentSampleStatus.MAKING, DevelopmentSampleStatus.ABANDONED);
      case MAKING -> ordered(DevelopmentSampleStatus.SHIPPED, DevelopmentSampleStatus.ABANDONED);
      case SHIPPED -> ordered(DevelopmentSampleStatus.RECEIVED, DevelopmentSampleStatus.ABANDONED);
      case RECEIVED ->
          ordered(
              DevelopmentSampleStatus.CONFIRMED,
              DevelopmentSampleStatus.REVISING,
              DevelopmentSampleStatus.ABANDONED);
      // 🔴 「修改」**不是**终态：可以重新打样，也可以改完直接通过。
      case REVISING ->
          ordered(
              DevelopmentSampleStatus.MAKING,
              DevelopmentSampleStatus.CONFIRMED,
              DevelopmentSampleStatus.ABANDONED);
      // 🔴 已确认 / 已放弃是终态：没有出边，不提供「恢复」。
      case CONFIRMED -> ordered();
      case ABANDONED -> ordered();
    };
  }

  /** 样品状态是否终态。 */
  public static boolean isTerminalSampleStatus(DevelopmentSampleStatus status) {
    return status == DevelopmentSampleStatus.CONFIRMED || status == DevelopmentSampleStatus.ABANDONED;
  }

  /**
   * 这条边是不是「确认边」—— 即目标状态必须由一条<b>确认记录</b>授权。
   *
   * <p>确认边（目标 = 已确认 / 修改中 / 已放弃）走
   * {@code POST .../confirmations}，由服务层先落不可改写的确认记录再改状态；
   * 进度边（目标 = 打样中 / 已发出 / 已到样）走 {@code POST .../transitions}。
   */
  public static boolean isConfirmationEdge(DevelopmentSampleStatus to) {
    return to == DevelopmentSampleStatus.CONFIRMED
        || to == DevelopmentSampleStatus.REVISING
        || to == DevelopmentSampleStatus.ABANDONED;
  }

  /**
   * 目标状态对应的**确认结果**。
   *
   * <p>与 V261 迁移里 {@code guard_development_sample_status()} 的 CASE 映射**逐条同构** ——
   * 已确认 ⇒ 通过；修改中 ⇒ 修改；已放弃 ⇒ 放弃。
   */
  public static DevelopmentSampleConfirmationResult confirmationResultFor(DevelopmentSampleStatus to) {
    return switch (to) {
      case CONFIRMED -> DevelopmentSampleConfirmationResult.PASSED;
      case REVISING -> DevelopmentSampleConfirmationResult.MODIFY;
      case ABANDONED -> DevelopmentSampleConfirmationResult.ABANDONED;
      default ->
          throw new IllegalArgumentException(
              "状态「" + to.label() + "」不是确认结果能驱动的目标状态（确认边只到已确认 / 修改中 / 已放弃）");
    };
  }

  /**
   * 确认结果对应的**目标样品状态**。
   *
   * <p>与 V261 迁移里 {@code guard_development_sample_status()} 的 CASE 映射**反向同构** ——
   * 通过 ⇒ 已确认；修改 ⇒ 修改中；放弃 ⇒ 已放弃。
   */
  public static DevelopmentSampleStatus targetStatusFor(DevelopmentSampleConfirmationResult result) {
    return switch (result) {
      case PASSED -> DevelopmentSampleStatus.CONFIRMED;
      case MODIFY -> DevelopmentSampleStatus.REVISING;
      case ABANDONED -> DevelopmentSampleStatus.ABANDONED;
    };
  }

  /** 校验一次样品状态迁移；不合法就 400。 */
  public static void requireSampleTransition(DevelopmentSampleStatus from, DevelopmentSampleStatus to) {
    if (from == to) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "目标状态与当前状态相同（" + from.label() + "），这不是一次状态迁移");
    }
    Set<DevelopmentSampleStatus> allowed = allowedSampleTargets(from);
    if (!allowed.contains(to)) {
      String targets =
          allowed.isEmpty()
              ? "无（已确认 / 已放弃是终态，不提供恢复）"
              : allowed.stream().map(DevelopmentSampleStatus::label).toList().toString();
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "样品不能从「" + from.label() + "」迁到「" + to.label() + "」。允许的目标状态：" + targets);
    }
  }

  // ================================================================ 来源 / 用途

  /**
   * 校验样品<b>用途</b>。
   *
   * <p>🔴 本模块的用途词表**只有一个值**（开发打样）。达人寄样（14.5.1.2）是**营销样品**，
   * 属 14 卷，**不在本模块**：混进来会让营销费用污染开发成本 ——
   * 那正是蓝图担心的「新品利润被高估」。
   */
  public static void requireUsageType(DevelopmentSampleUsageType usageType) {
    if (usageType != DevelopmentSampleUsageType.DEVELOPMENT) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "样品用途只能是「开发打样」：达人寄样（14.5.1.2）是营销样品，归 14 卷管，"
              + "不属本模块范围（混进来会污染开发成本）");
    }
  }

  /** 这个来源是否必须挂供应商。 */
  public static boolean requiresSupplier(DevelopmentSampleSourceType sourceType) {
    return sourceType == DevelopmentSampleSourceType.SUPPLIER;
  }

  /** 这个来源是否必须挂档口。 */
  public static boolean requiresStall(DevelopmentSampleSourceType sourceType) {
    return sourceType == DevelopmentSampleSourceType.STALL;
  }

  /** 这个来源是否**不许**挂任何外部来源实体（自有产打样）。 */
  public static boolean forbidsExternalSource(DevelopmentSampleSourceType sourceType) {
    return sourceType == DevelopmentSampleSourceType.OWN_FACTORY;
  }

  /**
   * 校验「来源 ↔ 来源实体」自洽。
   *
   * @param supplierRefId 供应商（catalog SUPPLIER 角色 id）
   * @param stallId 档口（6.1.2.1）
   */
  public static void requireSourceConsistency(
      DevelopmentSampleSourceType sourceType, UUID supplierRefId, UUID stallId) {
    if (requiresSupplier(sourceType) && supplierRefId == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "来源是「供应商打样」时必须指定供应商");
    }
    if (requiresStall(sourceType) && stallId == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源是「档口打样」时必须指定档口");
    }
    if (forbidsExternalSource(sourceType) && (supplierRefId != null || stallId != null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "来源是「自有产打样」时不应挂供应商或档口（自有产没有外部来源）");
    }
  }

  // ================================================================ 确认结果

  /**
   * 校验一条确认记录。
   *
   * <p>🔴 {@code result=MODIFY} 时**修改意见必填**：只说「要改」不说改什么，
   * 下一轮打样的人还是得猜。
   */
  public static void requireConfirmation(
      DevelopmentSampleConfirmationResult result, String reason, String issueNote) {
    if (reason == null || reason.isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "确认结论必须写明原因（一句「就这样」不是留痕）");
    }
    if (result == DevelopmentSampleConfirmationResult.MODIFY
        && (issueNote == null || issueNote.isBlank())) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "确认结果是「修改」时必须写明修改意见：只说「要改」不说改什么，下一轮打样的人还是得猜");
    }
  }

  // ================================================================ 金额 / 数量 / 币种

  /** 币种归一：三位大写字母。 */
  public static String normalizeCurrency(String currency) {
    if (currency == null || currency.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "币种必填（ISO 4217 三位大写字母）");
    }
    String normalized = currency.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("^[A-Z]{3}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "币种必须是三位大写字母，收到的是 " + currency);
    }
    return normalized;
  }

  /** 成本明细的金额必须 &gt; 0 —— 一条 0 元的明细不携带任何信息。 */
  public static void requirePositiveAmount(BigDecimal amount) {
    if (amount == null || amount.signum() <= 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "成本金额必须大于 0（0 元的一条明细不携带任何信息）");
    }
  }

  /** 样品数量必须 &gt; 0。 */
  public static void requirePositiveQuantity(BigDecimal quantity) {
    if (quantity == null || quantity.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "样品数量必须大于 0");
    }
  }

  /** 保留顺序的不可变集合。 */
  private static <T> Set<T> ordered(T... values) {
    return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(List.of(values)));
  }
}
