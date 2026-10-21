package com.starcon.channel;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 14.5.1.2 建联与样品 的<b>纯计算规则</b>（不碰数据库，可单独单测）。
 *
 * <p>蓝图：「达人建联流程：按规则筛选达人 → 模板化私信邀约（走官方接口）→
 * 寄样管理（样品单、物流、签收）→ 合作跟进 → 佣金设置。输入：达人筛选条件；邀约模板；
 * 样品寄送信息；佣金比例。输出：建联记录 + 样品单 + 合作状态跟踪。关系：上游 = 14.5.1.1；
 * 下游 = 8 仓库（样品出库）、10 物流（样品物流）、12 财务（佣金计提）。
 * <b>建联必须走官方接口</b>，批量私信是平台风控重点，第三方工具易导致账号风险。」
 *
 * <p>这里算五件事：
 *
 * <ol>
 *   <li><b>建联状态机</b>（DRAFT → SENT → REPLIED → …）：哪一步能到哪一步，
 *       终态没有出边。
 *   <li><b>样品单状态机</b>与<b>包裹状态机</b>：样品单的 DELIVERED / SIGNED
 *       <b>由包裹推导</b>（签收只能来自包裹），所以这里有一个纯函数。
 *   <li><b>模板渲染</b>：占位符是<b>闭合词表</b>，写错的变量名直接报错，
 *       不把 {@code {{xxx}}} 原样发给达人。
 *   <li><b>佣金计提</b>：RATE 按比例、FIXED 按固定额，两种形态不混算。
 *   <li><b>频率上限判定</b>：每日发送上限 + 最小间隔秒数。
 * </ol>
 *
 * <p>🔴 <b>为什么状态机写在服务层又写进 DB CHECK</b>：服务层管「合法的下一步」，
 * DB 管「合法的取值」。两层都写，是因为绕过服务层直接改库（运维脚本、误操作的 SQL）
 * 也必须撞墙 —— 单靠服务层，一次手滑的 UPDATE 就能造出「DRAFT 却带发送时刻」的行。
 */
public final class InfluencerOutreachRules {
  private InfluencerOutreachRules() {}

  // ==================================================================
  // 封闭词表（与 V289 的 CHECK 逐字一致）
  // ==================================================================

  /** 建联状态（与 V289 `influencer_outreach.status` 的 CHECK 一致）。 */
  public static final List<String> OUTREACH_STATUSES =
      List.of(
          "DRAFT", "SENT", "REPLIED", "ACCEPTED", "REJECTED", "NO_REPLY", "COOPERATING",
          "COMPLETED", "CANCELLED");

  /** 样品单状态（与 V289 `influencer_sample_order.status` 的 CHECK 一致）。 */
  public static final List<String> SAMPLE_ORDER_STATUSES =
      List.of("REQUESTED", "OUTBOUNDED", "DELIVERED", "SIGNED", "CANCELLED");

  /** 包裹状态（与 V289 `influencer_sample_shipment.status` 的 CHECK 一致）。 */
  public static final List<String> SHIPMENT_STATUSES =
      List.of("SHIPPED", "IN_TRANSIT", "DELIVERED", "SIGNED", "LOST", "RETURNED");

  /** 跟进方式（与 V289 `influencer_outreach_followup.followup_type` 的 CHECK 一致）。 */
  public static final List<String> FOLLOWUP_TYPES =
      List.of("CALL", "IM", "EMAIL", "SAMPLE_FEEDBACK", "NEGOTIATION", "OTHER");

  /** 佣金形态（与 V289 `influencer_commission_rule.commission_type` 的 CHECK 一致）。 */
  public static final List<String> COMMISSION_TYPES = List.of("RATE", "FIXED");

  /** 佣金规则适用范围（与 V289 `influencer_commission_rule.scope_type` 的 CHECK 一致）。 */
  public static final List<String> COMMISSION_SCOPES = List.of("GLOBAL", "SEGMENT", "INFLUENCER");

  /** 计提状态（与 V289 `influencer_commission_accrual.status` 的 CHECK 一致）。 */
  public static final List<String> ACCRUAL_STATUSES =
      List.of("PENDING", "ACCRUED", "POSTED", "CANCELLED");

  /** 配置类事实的通用状态。 */
  public static final List<String> CONFIG_STATUSES = List.of("ACTIVE", "PAUSED");

  /** 粉丝层级（筛选条件用；与 V288 触发器同口径）。 */
  public static final List<String> FOLLOWER_TIERS =
      List.of("NANO", "MICRO", "MID", "MACRO", "MEGA", "TOP");

  // ==================================================================
  // 一、建联状态机
  // ==================================================================

  /**
   * 建联状态机的合法边。
   *
   * <p>🔴 终态（REJECTED / NO_REPLY / COMPLETED / CANCELLED）**没有出边** ——
   * 「已拒绝」再变回「已回复」是改历史，不是推进。
   *
   * <p>🔴 SENT 允许直接到 ACCEPTED / REJECTED：有些平台在收到邀约后直接给结论，
   * 不一定先回一句话。把它堵死会让运营只能先手填一条「已回复」的假跟进。
   *
   * <p>🔴 <b>DRAFT 没有到 SENT 的边</b>：SENT 只由「登记平台回执」这一个动作产生
   * （那条路径同时写 platform_message_id / platform_receipt / sent_at 三个事实）。
   * 通用状态流转端点拿不到这些信息，所以**不允许**它凭空把草稿推成已发送。
   */
  private static final Map<String, Set<String>> OUTREACH_TRANSITIONS =
      Map.of(
          "DRAFT", Set.of("CANCELLED"),
          "SENT", Set.of("REPLIED", "ACCEPTED", "REJECTED", "NO_REPLY", "CANCELLED"),
          "REPLIED", Set.of("ACCEPTED", "REJECTED", "COOPERATING", "CANCELLED"),
          "ACCEPTED", Set.of("COOPERATING", "COMPLETED", "CANCELLED"),
          "COOPERATING", Set.of("COMPLETED", "CANCELLED"),
          "REJECTED", Set.of(),
          "NO_REPLY", Set.of(),
          "COMPLETED", Set.of(),
          "CANCELLED", Set.of());

  /** 从 {@code from} 能不能直接到 {@code to}。 */
  public static boolean canTransitionOutreach(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    Set<String> next = OUTREACH_TRANSITIONS.get(from);
    return next != null && next.contains(to);
  }

  /** 从 {@code from} 能到的全部状态（给界面渲染可选项用）。 */
  public static List<String> nextOutreachStatuses(String from) {
    Set<String> next = OUTREACH_TRANSITIONS.get(from);
    return next == null ? List.of() : List.copyOf(new LinkedHashSet<>(next));
  }

  /** 是不是终态（没有出边）。 */
  public static boolean isTerminalOutreach(String status) {
    Set<String> next = OUTREACH_TRANSITIONS.get(status);
    return next != null && next.isEmpty();
  }

  /** 终态集合（partial unique index 用的是同一组值）。 */
  public static final Set<String> CLOSED_OUTREACH_STATUSES =
      Set.of("REJECTED", "NO_REPLY", "COMPLETED", "CANCELLED");

  // ==================================================================
  // 二、样品单 / 包裹状态机
  // ==================================================================

  /**
   * 样品单状态**由包裹推导**。
   *
   * <p>🔴 这是本模块最要紧的一条事实边界：「达人说没收到」和「仓库说已寄出」
   * 是寄样最常吵的一个点。答它必须有唯一口径：**看包裹**。
   * 样品单的 DELIVERED / SIGNED **不是人填的**，是包裹状态推出来的 ——
   * 所以人手动改样品单状态时只能改 REQUESTED → OUTBOUNDED / CANCELLED。
   *
   * @param shipmentStatuses 包裹状态列表（可空 = 一个包裹都没有）
   * @param cancelled 样品单是否已被取消
   * @return 推导出的样品单状态
   */
  public static String deriveSampleOrderStatus(List<String> shipmentStatuses, boolean cancelled) {
    if (cancelled) {
      return "CANCELLED";
    }
    if (shipmentStatuses == null || shipmentStatuses.isEmpty()) {
      return "REQUESTED";
    }
    boolean allSigned = true;
    boolean allDeliveredOrSigned = true;
    for (String status : shipmentStatuses) {
      if (!"SIGNED".equals(status)) {
        allSigned = false;
      }
      if (!"SIGNED".equals(status) && !"DELIVERED".equals(status)) {
        allDeliveredOrSigned = false;
      }
    }
    if (allSigned) {
      return "SIGNED";
    }
    if (allDeliveredOrSigned) {
      return "DELIVERED";
    }
    return "OUTBOUNDED";
  }

  /** 包裹状态机的合法边（终态：SIGNED / LOST / RETURNED 没有出边）。 */
  private static final Map<String, Set<String>> SHIPMENT_TRANSITIONS =
      Map.of(
          "SHIPPED", Set.of("IN_TRANSIT", "DELIVERED", "LOST", "RETURNED"),
          "IN_TRANSIT", Set.of("DELIVERED", "LOST", "RETURNED"),
          "DELIVERED", Set.of("SIGNED"),
          "SIGNED", Set.of(),
          "LOST", Set.of(),
          "RETURNED", Set.of());

  /** 从 {@code from} 能不能直接到 {@code to}。 */
  public static boolean canTransitionShipment(String from, String to) {
    if (from == null || to == null) {
      return false;
    }
    Set<String> next = SHIPMENT_TRANSITIONS.get(from);
    return next != null && next.contains(to);
  }

  /** 从 {@code from} 能到的全部状态。 */
  public static List<String> nextShipmentStatuses(String from) {
    Set<String> next = SHIPMENT_TRANSITIONS.get(from);
    return next == null ? List.of() : List.copyOf(new LinkedHashSet<>(next));
  }

  // ==================================================================
  // 三、模板渲染
  // ==================================================================

  /**
   * 模板变量<b>闭合词表</b>。
   *
   * <p>🔴 词表里**没有「其它」桶**：模板变量是渲染契约，
   *   放一个自定义变量等于让渲染器去猜「这个 {{xxx}} 是什么」——
   *   猜错的代价是把一堆占位符原样发给达人。
   *
   * @param name 变量名（不含大括号）
   * @param required 是不是必填（必填变量取不到值 ⇒ 渲染失败，不发空）
   */
  public record TemplateVariable(String name, String label, boolean required) {}

  /** 可用变量清单（给界面的「插入变量」按钮用）。 */
  public static final List<TemplateVariable> TEMPLATE_VARIABLES =
      List.of(
          new TemplateVariable("display_name", "达人名称", true),
          new TemplateVariable("handle", "达人账号", false),
          new TemplateVariable("category", "达人类目", false),
          new TemplateVariable("shop_name", "店铺名称", true),
          new TemplateVariable("platform_name", "平台名称", true),
          new TemplateVariable("segment_name", "分群名称", false),
          new TemplateVariable("commission_rate", "佣金比例", false));

  private static final Map<String, TemplateVariable> VARIABLE_INDEX =
      TEMPLATE_VARIABLES.stream().collect(
          java.util.stream.Collectors.toMap(TemplateVariable::name, v -> v));

  /** 变量名是否在闭合词表里。 */
  public static boolean isKnownVariable(String name) {
    return VARIABLE_INDEX.containsKey(name);
  }

  /** 取变量的必填标记（未知变量返回 null）。 */
  public static Boolean variableRequired(String name) {
    TemplateVariable v = VARIABLE_INDEX.get(name);
    return v == null ? null : v.required();
  }

  /**
   * 渲染模板。
   *
   * <p>🔴 两条硬规则：
   *
   * <ol>
   *   <li><b>未知变量 ⇒ 直接失败</b>（不是原样留下）。留着的后果是
   *       「{{达人名字}}」这种中文占位符被原样发给达人 —— 那比发不出去糟得多。</li>
   *   <li><b>必填变量取不到值 ⇒ 直接失败</b>。私信里出现空的店铺名会让达人
   *       以为这是群发（群发是风控重点）。</li>
   * </ol>
   *
   * @param text 模板文本（标题或正文）
   * @param values 变量取值（缺失的必填变量 ⇒ 失败）
   * @return 渲染结果
   * @throws IllegalArgumentException 未知变量或必填变量缺值
   */
  public static String render(String text, Map<String, String> values) {
    if (text == null || text.isEmpty()) {
      return "";
    }
    StringBuilder out = new StringBuilder(text.length() + 64);
    int cursor = 0;
    while (cursor < text.length()) {
      int open = text.indexOf("{{", cursor);
      if (open < 0) {
        out.append(text, cursor, text.length());
        break;
      }
      int close = text.indexOf("}}", open + 2);
      if (close < 0) {
        // 🔴 有 "{{" 没有 "}}" ⇒ 模板写坏了，直接失败（不是原样留下）。
        throw new IllegalArgumentException("模板里有没写完的占位符（缺少 }}）");
      }
      out.append(text, cursor, open);
      String name = text.substring(open + 2, close).trim();
      TemplateVariable variable = VARIABLE_INDEX.get(name);
      if (variable == null) {
        throw new IllegalArgumentException("模板里有未登记的占位符：" + name);
      }
      String value = values == null ? null : values.get(name);
      if (value == null || value.isBlank()) {
        if (variable.required()) {
          throw new IllegalArgumentException("模板里的必填占位符没有值：" + name);
        }
        value = "";
      }
      out.append(value);
      cursor = close + 2;
    }
    return out.toString();
  }

  // ==================================================================
  // 四、佣金计提
  // ==================================================================

  /** 金额保留的小数位（与列的 NUMERIC(19,4) 对齐）。 */
  static final int MONEY_SCALE = 4;

  /**
   * 算一笔佣金计提。
   *
   * <p>🔴 <b>两种形态不混算</b>：RATE 按 {@code baseAmount × rate}，
   *   FIXED 就是固定额（基数只作为留痕，不参与计算）。
   *   混合形态（「先给 5% 再减 20 美元」）在 V289 的 DB CHECK 里就被拒了。
   *
   * @param commissionType RATE / FIXED
   * @param commissionRate 比例（RATE 形态必填）
   * @param fixedAmount 固定额（FIXED 形态必填）
   * @param baseAmount 计提基数（GMV）
   * @return 计提金额（保留 4 位，HALF_UP）
   */
  public static BigDecimal accrue(
      String commissionType, BigDecimal commissionRate, BigDecimal fixedAmount, BigDecimal baseAmount) {
    if ("FIXED".equals(commissionType)) {
      if (fixedAmount == null) {
        throw new IllegalArgumentException("固定额形态必须给固定金额");
      }
      return fixedAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    if ("RATE".equals(commissionType)) {
      if (commissionRate == null) {
        throw new IllegalArgumentException("比例形态必须给佣金比例");
      }
      BigDecimal base = baseAmount == null ? BigDecimal.ZERO : baseAmount;
      return base.multiply(commissionRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
    throw new IllegalArgumentException("未知的佣金形态：" + commissionType);
  }

  // ==================================================================
  // 五、频率上限判定
  // ==================================================================

  /**
   * 频率上限判定结果。
   *
   * @param allowed 允不允许再发一条
   * @param reason 不允许的原因（人能读，允许时为空串）
   * @param sentToday 今天已登记的发送条数
   * @param dailyLimit 每日上限
   * @param secondsSinceLast 距上一条发送的秒数（null = 今天还没发过）
   * @param minIntervalSeconds 最小间隔秒数
   */
  public record RateCheck(
      boolean allowed,
      String reason,
      long sentToday,
      int dailyLimit,
      Long secondsSinceLast,
      int minIntervalSeconds) {}

  /**
   * 判定「今天还能不能再发一条私信」。
   *
   * <p>🔴 蓝图加粗句「批量私信是平台风控重点」—— 所以上限是**配置事实**
   *   （{@code influencer_outreach_policy}），判定在这里做纯计算，服务层只负责
   *   把「今天已发几条」「距上一条多久」查出来。
   *
   * <p>两条判据：
   *
   * <ol>
   *   <li><b>每日上限</b>：今天已登记的条数 < 上限。</li>
   *   <li><b>最小间隔</b>：距上一条发送至少间隔 N 秒（防抖）。
   *       间隔为 0 时不判（那等于运营显式说「不限间隔」）。</li>
   * </ol>
   *
   * @param sentToday 今天已登记的发送条数
   * @param dailyLimit 每日上限
   * @param secondsSinceLast 距上一条发送的秒数（null = 今天还没发过）
   * @param minIntervalSeconds 最小间隔秒数
   */
  public static RateCheck checkRate(
      long sentToday, int dailyLimit, Long secondsSinceLast, int minIntervalSeconds) {
    if (sentToday >= dailyLimit) {
      return new RateCheck(
          false,
          "今天已登记发送 " + sentToday + " 条，达到该平台的每日上限 " + dailyLimit + " 条。"
              + "批量私信是平台风控重点，请明天再发或调高上限。",
          sentToday,
          dailyLimit,
          secondsSinceLast,
          minIntervalSeconds);
    }
    if (minIntervalSeconds > 0
        && secondsSinceLast != null
        && secondsSinceLast < minIntervalSeconds) {
      return new RateCheck(
          false,
          "距上一条发送只隔了 " + secondsSinceLast + " 秒，少于该平台要求的最小间隔 "
              + minIntervalSeconds + " 秒。",
          sentToday,
          dailyLimit,
          secondsSinceLast,
          minIntervalSeconds);
    }
    return new RateCheck(true, "", sentToday, dailyLimit, secondsSinceLast, minIntervalSeconds);
  }
}
