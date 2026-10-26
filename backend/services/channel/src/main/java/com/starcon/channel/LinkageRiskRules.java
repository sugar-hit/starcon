package com.starcon.channel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * 16.5.2.2 关联风险的<b>受控值域与纯函数</b>。
 *
 * <p>蓝图：「识别账号关联风险：同一支付方式/收款账户/设备/IP/地址/法人关联的店铺，
 * 输出关联图谱与风险提示，提示避免触发平台关联处罚。输入：店铺与支付/设备/地址/法人关系数据；
 * 平台政策要求。输出：关联图谱 + 风险提示 + 整改建议。关系：上游 = 2 店铺、12.7.1 资金账户；
 * 下游 = 1 工作台预警。<b>关联封号是跨境多店经营的头号风险</b>：官方 API 授权是规避关联的关键，
 * 本系统只做官方 API，绝不提供任何模拟登录/指纹浏览器能力。」
 *
 * <p>🔴 本文件里的每一条枚举都是<b>DB 层 CHECK 钉死的值域</b>的镜像。
 * 服务层与 DB 层必须一致 —— 两处不一致时，DB 会拒掉一批服务层认为合法的写入，
 * 排查起来极难，所以这里显式声明「改这里必须同时改迁移」。
 *
 * <p>🔴🔴 <b>本文件不提供任何「模拟登录 / 指纹浏览器 / 绕关联」相关的能力</b>：
 * 值域里没有「浏览器指纹」这一类，规则里也没有生成或采集设备指纹的动作。
 * 设备维度的标识<b>只能由租户登记已观测到的事实</b>，本系统不制造它。
 */
public final class LinkageRiskRules {

  private LinkageRiskRules() {}

  // ================================================================ 值域

  /**
   * 关联维度。
   *
   * <p>🔴 <b>PAYMENT_METHOD / PAYOUT_ACCOUNT 在本仓读不到</b>：真相归
   * <b>12.7.1 资金账户（12.7.1.1 账户管理，未交付）</b>。这两个维度只出现在能力声明里
   * （{@code available=false} + {@code ownerModule=12.7.1.1}），
   * <b>不出现在任何可写入的值域里</b> —— 建了枚举却不让写，等于留一个假入口。
   */
  public enum Dimension {
    LEGAL_ENTITY("法人", true),
    DEVICE("设备", true),
    NETWORK_IP("网络 IP", true),
    ADDRESS("地址", true),
    PAYMENT_METHOD("支付方式", false),
    PAYOUT_ACCOUNT("收款账户", false),
    OTHER("其他", true);

    private final String label;

    /** 这个维度在本仓能不能拿到事实。 */
    private final boolean supported;

    Dimension(String label, boolean supported) {
      this.label = label;
      this.supported = supported;
    }

    public String label() {
      return label;
    }

    /** 本仓能不能拿到这个维度的事实（false ⇒ 能力声明里 available=false）。 */
    public boolean supported() {
      return supported;
    }

    /** 🔴 事实是不是自动发现的（法人来自 {@code shop.legal_entity_id}，其余靠登记）。 */
    public boolean autoDiscovered() {
      return this == LEGAL_ENTITY;
    }

    /**
     * 这个维度能不能由租户<b>手工登记</b>。
     *
     * <p>🔴 法人维度<b>不能</b>登记：真相在 {@code shop.legal_entity_id}，
     * 另开一个登记面就等于造出第二份法人真相。
     */
    public boolean registrable() {
      return supported && this != LEGAL_ENTITY;
    }
  }

  /** 维度敏感度（平台政策口径，受控值域，DB 层 CHECK 钉死）。 */
  public enum Sensitivity {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低");

    private final String label;

    Sensitivity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 排序权重：数值越大越敏感。🔴 <b>不能用 ordinal()</b> —— 本枚举按 HIGH/MEDIUM/LOW 声明，
     *  ordinal 会把 HIGH 排成 0（比 LOW 还小），「取最高敏感度」的比较就整个反了。 */
    public int weight() {
      return switch (this) {
        case LOW -> 1;
        case MEDIUM -> 2;
        case HIGH -> 3;
      };
    }

    /** 映射成严重度（同一套受控值域）。 */
    public Severity toSeverity() {
      return switch (this) {
        case HIGH -> Severity.HIGH;
        case MEDIUM -> Severity.MEDIUM;
        case LOW -> Severity.LOW;
      };
    }
  }

  /** 严重度（受控值域，DB 层 CHECK 钉死）。 */
  public enum Severity {
    LOW("低"),
    MEDIUM("中"),
    HIGH("高"),
    CRITICAL("严重");

    private final String label;

    Severity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 排序权重：数值越大越严重。 */
    public int weight() {
      return ordinal();
    }

    /** 升一档（封顶 CRITICAL）。 */
    public Severity escalate() {
      return switch (this) {
        case LOW -> MEDIUM;
        case MEDIUM -> HIGH;
        case HIGH, CRITICAL -> CRITICAL;
      };
    }

    /** 取更严重的那一个（不会把已发生的严重度改小）。 */
    public Severity max(Severity other) {
      return other == null || other.weight() > weight() ? other : this;
    }
  }

  /** 风险提示的处置状态机（DB 层由 {@code guard_linkage_alert_status} 守卫）。 */
  public enum AlertStatus {
    OPEN("待处置"),
    ACKNOWLEDGED("已确认"),
    REMEDIATED("已整改"),
    DISMISSED("已忽略");

    private final String label;

    AlertStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：已整改 / 已忽略不可再改。 */
    public boolean terminal() {
      return this == REMEDIATED || this == DISMISSED;
    }
  }

  /** 整改建议的处置状态机。 */
  public enum RemediationStatus {
    PROPOSED("待处理"),
    ACCEPTED("已采纳"),
    DISMISSED("已忽略"),
    DONE("已完成");

    private final String label;

    RemediationStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public boolean terminal() {
      return this == DISMISSED || this == DONE;
    }
  }

  /** 整改建议码（受控值域，DB 层 CHECK 钉死）。 */
  public enum RecommendationCode {
    SEPARATE_DEVICE("分离设备"),
    SEPARATE_NETWORK("分离网络出口"),
    SEPARATE_ADDRESS("分离地址"),
    VERIFY_LEGAL_ENTITY("核对法人绑定"),
    REVIEW_LINKAGE("人工复核关联"),
    OFFICIAL_API_AUTHORIZATION("用官方 API 授权把店铺分开");

    private final String label;

    RecommendationCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 判定范围。 */
  public enum EvaluationScope {
    TENANT("全部店铺之间"),
    PLATFORM("仅同一平台之间");

    private final String label;

    EvaluationScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 关联标识的状态。 */
  public enum IdentifierStatus {
    ACTIVE("生效中"),
    RETIRED("已停用");

    private final String label;

    IdentifierStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 关联事实的来源（租户自己登记的观测）。 */
  public enum ObservationSource {
    MANUAL("手工登记"),
    PLATFORM_NOTICE("平台通知"),
    OPS_REPORT("运营上报");

    private final String label;

    ObservationSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 状态机

  /**
   * 风险提示的状态机：新警 → 已确认 / 已整改 / 已忽略。
   *
   * <p>🔴 与迁移里的 {@code guard_linkage_alert_status} 守卫<b>同一张转换表</b>。
   * 服务层先判一次（好报错），DB 层再判一次（防绕过）。
   */
  public static boolean canTransition(AlertStatus from, AlertStatus to) {
    if (from == to) return true;
    return switch (from) {
      case OPEN -> to == AlertStatus.ACKNOWLEDGED || to == AlertStatus.REMEDIATED || to == AlertStatus.DISMISSED;
      case ACKNOWLEDGED -> to == AlertStatus.REMEDIATED || to == AlertStatus.DISMISSED;
      case REMEDIATED, DISMISSED -> false;
    };
  }

  /** 整改建议的状态机：建议 → 已采纳 / 已忽略 / 已完成。 */
  public static boolean canTransition(RemediationStatus from, RemediationStatus to) {
    if (from == to) return true;
    return switch (from) {
      case PROPOSED -> to == RemediationStatus.ACCEPTED || to == RemediationStatus.DISMISSED || to == RemediationStatus.DONE;
      case ACCEPTED -> to == RemediationStatus.DISMISSED || to == RemediationStatus.DONE;
      case DISMISSED, DONE -> false;
    };
  }

  // ================================================================ 严重度实算

  /**
   * 关联组的严重度：因子最高敏感度 + 店铺数超阈值时升一档。
   *
   * <p>🔴 <b>为什么店铺数要升档</b>：同样「共享一个设备」，两家店与五家店不是一回事 ——
   * 平台判定的是「一个主体控制了多少账号」，不是「有没有关联」。但阈值本身由租户在
   * 策略里显式选（{@code min_shared_shop_count}），系统**不替租户定**这个数。
   *
   * @param factorSeverity 组内因子里的最高敏感度
   * @param shopCount 组内店铺数
   * @param minSharedShopCount 租户显式选的最少店铺数（{@code >= 2}）
   */
  public static Severity clusterSeverity(Sensitivity factorSeverity, int shopCount, int minSharedShopCount) {
    Severity severity = factorSeverity.toSeverity();
    if (shopCount >= minSharedShopCount + 2) {
      severity = severity.escalate();
    }
    return severity;
  }

  /**
   * 关联组的风险等级：跨平台再升一档。
   *
   * <p>🔴 <b>为什么跨平台更危险</b>：平台封号是**按主体**判的 —— 同一套设备 / 网络 /
   * 地址在 Amazon 与 eBay 同时出现，会被两边各自的「关联主体」规则命中；
   * 同平台内部的多店则通常已被官方 API 授权覆盖。
   * ⇒ 跨平台是**实算**出来的（组内出现了两个平台），不是猜的。
   */
  public static Severity clusterRiskLevel(Severity severity, boolean crossPlatform) {
    return crossPlatform ? severity.escalate() : severity;
  }

  /**
   * 某条关联边（一个共享因子）成立所需的店铺数是否达到该维度的阈值。
   *
   * @param shopCount 共享该因子的店铺数
   * @param minShopCount 该维度在策略里显式选的阈值
   */
  public static boolean factorQualifies(int shopCount, int minShopCount) {
    return shopCount >= minShopCount;
  }

  // ================================================================ 标识归一化

  /**
   * 归一化标识的判定键。
   *
   * <p>🔴 <b>网络 IP 维度必须先判定合法性</b>：非法字面量直接丢给 PG 会在事务里抛一个
   * 驱动层异常（SQLState 22P02），界面拿到的是 500 而不是「你填的 IP 不合法」。
   * 这里复用 {@link AccessSecurityRules#isValidCidr}（同一套 IPv4/IPv6 字面量判定），
   * <b>绝不接受主机名</b>（那会让我们的服务变成 DNS 客户端）。
   *
   * <p>🔴 设备 / 其他维度：只做去首尾空白 + 转大写，<b>不做任何猜测式归一化</b> ——
   * 我们不知道租户的设备指纹是怎么算的，胡乱归一化会把两个不同的因子并成一个。
   *
   * @param dimension 维度
   * @param raw 原始标识
   * @return 归一化后的判定键
   * @throws IllegalArgumentException IP 维度下 IP 字面量不合法
   */
  public static String normalizeIdentifierKey(Dimension dimension, String raw) {
    if (raw == null) return "";
    String trimmed = raw.trim();
    return switch (dimension) {
      case NETWORK_IP -> AccessSecurityRules.normalizeCidr(trimmed);
      case DEVICE, ADDRESS, OTHER -> trimmed.toUpperCase(Locale.ROOT);
      case LEGAL_ENTITY, PAYMENT_METHOD, PAYOUT_ACCOUNT -> trimmed;
    };
  }

  /**
   * 校验标识字面量在该维度下是否可用。
   *
   * @param dimension 维度
   * @param raw 原始标识
   * @return 合法返回 true
   */
  public static boolean isValidIdentifier(Dimension dimension, String raw) {
    if (raw == null || raw.isBlank()) return false;
    return switch (dimension) {
      case NETWORK_IP -> AccessSecurityRules.isValidCidr(raw);
      case DEVICE, ADDRESS, OTHER -> raw.trim().length() <= 200;
      case LEGAL_ENTITY, PAYMENT_METHOD, PAYOUT_ACCOUNT -> false;
    };
  }

  // ================================================================ 关联组键

  /**
   * 关联组的确定性键：成员店铺集合的 sha256。
   *
   * <p>🔴 <b>为什么必须是确定性的</b>：重扫同一批事实必须落到<b>同一个</b>关联组，
   * 否则每次扫描都刷出一条新的风险提示，把运营的注意力淹掉。
   * 键只由「成员店铺集合」决定（排序后拼接），<b>不含时间、不含严重度</b> ——
   * 严重度变了组还是同一组，那条提示应当被<b>更新</b>而不是新增。
   *
   * @param shopIds 组内店铺 id（顺序不限）
   */
  public static String clusterKey(List<UUID> shopIds) {
    List<String> sorted = new ArrayList<>();
    for (UUID id : shopIds) {
      if (id != null) sorted.add(id.toString());
    }
    sorted.sort(Comparator.naturalOrder());
    String joined = String.join(",", sorted);
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(joined.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder(hash.length * 2);
      for (byte b : hash) {
        out.append(Character.forDigit((b >> 4) & 0xF, 16));
        out.append(Character.forDigit(b & 0xF, 16));
      }
      return out.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 风险提示的幂等键：同一关联组至多一条提示。 */
  public static String alertDedupKey(String clusterKey) {
    return "SHARED_FACTOR|" + (clusterKey == null ? "" : clusterKey);
  }

  /** 整改建议的幂等键：同一条提示下同一个建议码只留一条。 */
  public static String remediationDedupKey(String alertId, RecommendationCode code) {
    return alertId + "|" + code.name();
  }

  // ================================================================ 整改建议

  /**
   * 维度 ⇒ 对应的整改建议码。
   *
   * <p>🔴 每条建议都指向<b>官方 API 授权口径</b>下的整改动作
   * （分离设备 / 分离网络 / 分离地址 / 核对法人绑定 / 人工复核），
   * <b>没有一条</b>是「用模拟登录绕开关联」—— 那种能力本仓不提供，也不建议。
   */
  public static RecommendationCode recommendationFor(Dimension dimension) {
    return switch (dimension) {
      case DEVICE -> RecommendationCode.SEPARATE_DEVICE;
      case NETWORK_IP -> RecommendationCode.SEPARATE_NETWORK;
      case ADDRESS -> RecommendationCode.SEPARATE_ADDRESS;
      case LEGAL_ENTITY -> RecommendationCode.VERIFY_LEGAL_ENTITY;
      case OTHER -> RecommendationCode.REVIEW_LINKAGE;
      case PAYMENT_METHOD, PAYOUT_ACCOUNT -> RecommendationCode.REVIEW_LINKAGE;
    };
  }

  /** 整改建议的优先级：由关联组的严重度决定（不是另设一套阈值）。 */
  public static String remediationPriority(Severity severity) {
    return switch (severity) {
      case LOW -> "LOW";
      case MEDIUM -> "MEDIUM";
      case HIGH, CRITICAL -> "HIGH";
    };
  }

  // ================================================================ 展示映射

  /** 维度标签。 */
  public static String dimensionLabel(String dimension) {
    for (Dimension value : Dimension.values()) {
      if (value.name().equals(dimension)) return value.label();
    }
    return dimension;
  }

  /** 严重度标签。 */
  public static String severityLabel(String severity) {
    for (Severity value : Severity.values()) {
      if (value.name().equals(severity)) return value.label();
    }
    return severity;
  }

  /** 提示状态标签。 */
  public static String alertStatusLabel(String status) {
    for (AlertStatus value : AlertStatus.values()) {
      if (value.name().equals(status)) return value.label();
    }
    return status;
  }

  /** 建议状态标签。 */
  public static String remediationStatusLabel(String status) {
    for (RemediationStatus value : RemediationStatus.values()) {
      if (value.name().equals(status)) return value.label();
    }
    return status;
  }

  /** 建议码标签。 */
  public static String recommendationLabel(String code) {
    for (RecommendationCode value : RecommendationCode.values()) {
      if (value.name().equals(code)) return value.label();
    }
    return code;
  }
}
