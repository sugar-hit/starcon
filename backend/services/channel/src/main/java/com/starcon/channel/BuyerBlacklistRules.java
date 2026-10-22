package com.starcon.channel;

import java.util.Locale;

/**
 * 16.5.1.1 黑名单的**受控值域**与判定规则。
 *
 * <p>蓝图：「维护买家黑名单：买家标识、店铺、原因、风险等级、生效范围（仅本店铺/全店铺），
 * 下单时自动标记并可拦截。输入：黑名单录入（手工/自动，来自风控信号）；解除操作。
 * 输出：黑名单记录；订单拦截标记。关系：上游 = 16.5.1.2 评分、9.2 审单；下游 = 9.2.1.3 拦截、11 售后。
 * <b>黑名单应支持申诉解除</b>，永久不解除会让误判的正常买家永久损失。」
 *
 * <p>🔴 <b>受控值域在 DB 层用 CHECK 钉死，这里是同一套值域的第二道</b>：两层必须逐字一致 ——
 * 服务层认不出来的值，DB 层也会拒；DB 层放得过的值，服务层不会自己发明新的。
 *
 * <p>🔴 <b>「风险等级 ⇒ 建议动作」是一张固定的映射表，不给租户配</b>：配错了就会出现
 * 「等级最高的那档反而建议放行」这种荒唐配置。等级与动作的关系是领域知识，不是策略。
 */
public final class BuyerBlacklistRules {

  private BuyerBlacklistRules() {}

  // ================================================================ 生效范围

  /** 生效范围（受控值域）。 */
  public enum Scope {
    /** 仅本店铺生效（此时 shopCode 必填）。 */
    SHOP_ONLY("仅本店铺"),
    /** 全店铺生效（此时 shopCode 必须为空串）。 */
    ALL_SHOPS("全店铺");

    private final String label;

    Scope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /**
     * 该范围是否覆盖「下单的那家店」。
     *
     * @param entryShopCode 这条登记挂的店铺（{@code ALL_SHOPS} 时为空串）
     * @param orderShopCode 下单的那家店（调用方没给店铺时为空串 ⇒ 只有全店铺范围能覆盖）
     */
    public boolean covers(String entryShopCode, String orderShopCode) {
      if (this == ALL_SHOPS) return true;
      String entry = entryShopCode == null ? "" : entryShopCode.trim();
      String order = orderShopCode == null ? "" : orderShopCode.trim();
      return !entry.isEmpty() && entry.equals(order);
    }

    /**
     * 解析生效范围。
     *
     * @throws IllegalArgumentException 值域外的取值（由服务层转成 422）
     */
    public static Scope parse(String raw) {
      if (raw == null) throw new IllegalArgumentException("生效范围不能为空");
      for (Scope value : values()) {
        if (value.name().equals(raw.trim().toUpperCase(Locale.ROOT))) return value;
      }
      throw new IllegalArgumentException("生效范围只能是 SHOP_ONLY（仅本店铺）或 ALL_SHOPS（全店铺）");
    }
  }

  // ================================================================ 风险等级

  /** 风险等级（受控值域）。 */
  public enum RiskLevel {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    CRITICAL("极高");

    private final String label;

    RiskLevel(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 严重度（用于排序与「只升不降」的判定）。 */
    public int severity() {
      return switch (this) {
        case LOW -> 1;
        case MEDIUM -> 2;
        case HIGH -> 3;
        case CRITICAL -> 4;
      };
    }

    /** 该等级对应的**建议**拦截动作（只出建议 —— 真的拦不拦单是 9.2.1.3 的事）。 */
    public FlagAction action() {
      return switch (this) {
        case LOW -> FlagAction.WATCH;
        case MEDIUM -> FlagAction.REVIEW;
        case HIGH, CRITICAL -> FlagAction.BLOCK;
      };
    }

    /** 解析风险等级。 */
    public static RiskLevel parse(String raw) {
      if (raw == null) throw new IllegalArgumentException("风险等级不能为空");
      for (RiskLevel value : values()) {
        if (value.name().equals(raw.trim().toUpperCase(Locale.ROOT))) return value;
      }
      throw new IllegalArgumentException("风险等级只能是 LOW / MEDIUM / HIGH / CRITICAL");
    }
  }

  // ================================================================ 建议动作

  /** 订单拦截建议动作（受控值域）。 */
  public enum FlagAction {
    /** 建议拦截。 */
    BLOCK("建议拦截"),
    /** 建议转人工复核。 */
    REVIEW("建议人工复核"),
    /** 建议仅观察（记录但不拦）。 */
    WATCH("建议观察");

    private final String label;

    FlagAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 登记来源

  /** 登记来源（受控值域）。 */
  public enum Source {
    /** 手工录入。 */
    MANUAL("手工录入"),
    /** 自动 —— 来自风控信号（9.2.2.2）。 */
    RISK_SIGNAL("风控信号自动"),
    /** 自动 —— 由 16.5.1.2 的评分规则触发。 */
    SCORE_RULE("风险评分规则自动");

    private final String label;

    Source(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析登记来源。 */
    public static Source parse(String raw) {
      if (raw == null) throw new IllegalArgumentException("登记来源不能为空");
      for (Source value : values()) {
        if (value.name().equals(raw.trim().toUpperCase(Locale.ROOT))) return value;
      }
      throw new IllegalArgumentException("登记来源只能是 MANUAL / RISK_SIGNAL / SCORE_RULE");
    }
  }

  // ================================================================ 状态与事件

  /** 黑名单当前状态（受控值域）。 */
  public enum Status {
    /** 生效中。 */
    ACTIVE("生效中"),
    /** 已解除。 */
    RELEASED("已解除");

    private final String label;

    Status(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 状态流转事件类型（受控值域，与 DB 层 CHECK 逐字一致）。 */
  public enum EventType {
    /** 登记（seq 恒为 1）。 */
    REGISTERED("登记"),
    /** 升级风险等级（只升不降）。 */
    ESCALATED("升级"),
    /** 申诉驳回（维持黑名单）。 */
    APPEAL_REJECTED("申诉驳回"),
    /** 人工解除。 */
    RELEASED("解除"),
    /** 申诉通过并解除。 */
    APPEAL_APPROVED("申诉通过并解除"),
    /** 解除后复发，重新生效。 */
    REINSTATED("重新生效");

    private final String label;

    EventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 校验

  /**
   * 🔴 <b>校验 scope 与 shopCode 自洽</b>。
   *
   * <p>DB 层有 CHECK 咬死这两者，服务层也查一次：服务层的报错信息能说清「哪一条错了」，
   * 而 DB 层那句 CHECK 报错只有一句 Postgres 的话。
   */
  public static void requireScopeConsistent(Scope scope, String shopCode) {
    String code = shopCode == null ? "" : shopCode.trim();
    if (scope == Scope.ALL_SHOPS) {
      if (!code.isEmpty()) {
        throw new IllegalArgumentException("生效范围是「全店铺」时不能指定店铺（当前指定了 " + code + "）");
      }
      return;
    }
    if (code.isEmpty()) {
      throw new IllegalArgumentException("生效范围是「仅本店铺」时必须指定店铺");
    }
  }
}
