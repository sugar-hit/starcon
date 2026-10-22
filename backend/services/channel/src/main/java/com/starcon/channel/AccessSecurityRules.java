package com.starcon.channel;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 16.5.2.1 登录与访问安全的<b>受控值域与纯函数</b>。
 *
 * <p>蓝图：「管理平台账号与系统的访问安全：多因子认证、登录审计、异常登录告警、IP 白名单、
 * 会话管理；平台授权凭据的访问审计。输入：认证配置；登录事件；访问审计事件。
 * 输出：安全配置 + 审计记录 + 告警。关系：上游 = 17 identity；下游 = 2 凭据引用。
 * <b>平台凭据是最值钱的资产</b>，访问必须有审计且最小权限。」
 *
 * <p>🔴 本文件里的每一条枚举都是<b>DB 层 CHECK 钉死的值域</b>的镜像。
 * 服务层与 DB 层必须一致 —— 两处不一致时，DB 会拒掉一批服务层认为合法的写入，
 * 排查起来极难，所以这里显式声明「改这里必须同时改迁移」。
 */
public final class AccessSecurityRules {

  private AccessSecurityRules() {}

  // ================================================================ 值域

  /** 多因子认证因子类型。 */
  public enum MfaFactor {
    TOTP("认证器应用"),
    SMS("短信验证码"),
    EMAIL("邮件验证码");

    private final String label;

    MfaFactor(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** MFA 登记的绑定状态机（DB 层由 {@code guard_access_security_mfa_status} 守卫）。 */
  public enum MfaStatus {
    PENDING("待绑定"),
    ACTIVE("已启用"),
    DISABLED("已停用"),
    REVOKED("已撤销");

    private final String label;

    MfaStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 合法转换（与迁移里的守卫触发器同一张表）。 */
    public boolean canTransitionTo(MfaStatus next) {
      if (next == this) return true;
      return switch (this) {
        case PENDING -> next == ACTIVE || next == DISABLED || next == REVOKED;
        case ACTIVE -> next == DISABLED || next == REVOKED;
        case DISABLED -> next == ACTIVE || next == REVOKED;
        case REVOKED -> false;
      };
    }
  }

  /** MFA 挑战的一次尝试结果。 */
  public enum ChallengeOutcome {
    ISSUED("已下发"),
    VERIFIED("已验证"),
    FAILED("验证失败"),
    EXPIRED("已过期");

    private final String label;

    ChallengeOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 告警严重度（受控值域，DB 层 CHECK 钉死）。 */
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

    /** 取更严重的那一个（升级时用；不降级 —— 已发生的严重度不会被后续扫描改小）。 */
    public Severity max(Severity other) {
      return other == null || other.weight() > weight() ? other : this;
    }
  }

  /** 告警类型（受控值域）。 */
  public enum AlertType {
    FAILED_LOGIN_BURST("登录失败激增"),
    SUCCESS_LOGIN_BURST("短时多次成功登录"),
    IP_NOT_ALLOWED("IP 不在白名单"),
    ALLOWLIST_EMPTY("白名单为空但口径是强制");

    private final String label;

    AlertType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 告警处置状态机。 */
  public enum AlertStatus {
    NEW("新警"),
    ACKNOWLEDGED("已确认"),
    IGNORED("已忽略"),
    RESOLVED("已处置");

    private final String label;

    AlertStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 终态：已忽略 / 已处置不可再改（要重新处置就再开一条告警）。 */
    public boolean terminal() {
      return this == IGNORED || this == RESOLVED;
    }
  }

  /** 告警处置动作。 */
  public enum AlertAction {
    ACKNOWLEDGE("确认"),
    IGNORE("忽略"),
    RESOLVE("处置");

    private final String label;

    AlertAction(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 动作对应的目标状态。 */
    public AlertStatus targetStatus() {
      return switch (this) {
        case ACKNOWLEDGE -> AlertStatus.ACKNOWLEDGED;
        case IGNORE -> AlertStatus.IGNORED;
        case RESOLVE -> AlertStatus.RESOLVED;
      };
    }
  }

  /** IP 白名单执行口径。 */
  public enum IpAllowlistMode {
    DISABLED("不限制"),
    REPORT_ONLY("只告警不拦截"),
    ENFORCE("强制拒绝");

    private final String label;

    IpAllowlistMode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 凭据访问动作（受控值域）。 */
  public enum CredentialAccessKind {
    READ_METADATA("读取凭据元信息"),
    READ_SECRET("读取凭据明文"),
    WRITE_SECRET("写入凭据"),
    ROTATE("轮换凭据"),
    REVOKE("吊销凭据"),
    HEALTH_CHECK("健康诊断");

    private final String label;

    CredentialAccessKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 凭据访问结果。 */
  public enum CredentialAccessOutcome {
    SUCCESS("成功"),
    DENIED("被拒"),
    FAILED("失败");

    private final String label;

    CredentialAccessOutcome(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ CIDR

  /**
   * IPv4 字面量（严格四段十进制）。
   *
   * <p>🔴 <b>不接受「前导零」</b>：{@code 010.0.0.1} 在不同实现里被读成八进制或十进制，
   * 同一条白名单在不同机器上覆盖不同网段 —— 那是白名单最不能出的错。
   */
  private static final Pattern IPV4 =
      Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

  /** IPv6 字面量（十六进制组 + 可选 {@code ::} 压缩 + 可选尾部 IPv4 映射）。 */
  private static final Pattern IPV6 =
      Pattern.compile(
          "^((?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}"
              + "|(?:[0-9A-Fa-f]{1,4}:){1,7}:"
              + "|(?:[0-9A-Fa-f]{1,4}:){1,6}:[0-9A-Fa-f]{1,4}"
              + "|(?:[0-9A-Fa-f]{1,4}:){1,5}(?::[0-9A-Fa-f]{1,4}){1,2}"
              + "|(?:[0-9A-Fa-f]{1,4}:){1,4}(?::[0-9A-Fa-f]{1,4}){1,3}"
              + "|(?:[0-9A-Fa-f]{1,4}:){1,3}(?::[0-9A-Fa-f]{1,4}){1,4}"
              + "|(?:[0-9A-Fa-f]{1,4}:){1,2}(?::[0-9A-Fa-f]{1,4}){1,5}"
              + "|[0-9A-Fa-f]{1,4}:(?::[0-9A-Fa-f]{1,4}){1,6}"
              // IPv4 映射写法（::ffff:1.2.3.4）—— 尾部四段另判
              + "|::(?:ffff:)?\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}"
              + "|:(?:(?::[0-9A-Fa-f]{1,4}){1,7}|:))$");

  /**
   * 校验一个 CIDR / 单 IP 字面量是否合法。
   *
   * <p>🔴 <b>本仓没有真正的「网段判定」库依赖，判定在 PostgreSQL 里做</b>
   * （{@code ?::inet <<= cidr_range}）。但**入库前**必须先判合法性：
   * 非法字面量如果直接丢给 PG，会在事务里抛一个驱动层的异常（SQLState 22P02），
   * 界面拿到的是 500 而不是「你填的网段不合法」—— 那不是好的报错。
   *
   * <p>🔴 <b>绝不接受主机名</b>：{@code InetAddress.getByName("evil.example.com")} 会去解析 DNS，
   * 一个请求就能把我们的服务变成 DNS 客户端。这里用纯正则判定，不碰 DNS。
   *
   * @param value 用户填的网段（{@code 10.0.0.0/8}、{@code 10.0.0.5}、{@code 2001:db8::/32}）
   * @return 合法返回 true
   */
  public static boolean isValidCidr(String value) {
    if (value == null) return false;
    var trimmed = value.trim();
    if (trimmed.isEmpty()) return false;
    var slash = trimmed.indexOf('/');
    var address = slash < 0 ? trimmed : trimmed.substring(0, slash);
    var prefix = slash < 0 ? null : trimmed.substring(slash + 1);
    // 🔴 只允许出现一个 '/'
    if (slash >= 0 && trimmed.indexOf('/', slash + 1) >= 0) return false;
    boolean v4 = isIpv4(address);
    boolean v6 = !v4 && isIpv6(address);
    if (!v4 && !v6) return false;
    if (prefix == null) {
      // 裸地址：隐式 /32（IPv4）或 /128（IPv6）
      return true;
    }
    // 🔴 前缀必须是纯十进制数字（拒绝 "+8" / " 8" / "08x"）
    if (prefix.isEmpty() || !prefix.chars().allMatch(Character::isDigit)) return false;
    int bits;
    try {
      bits = Integer.parseInt(prefix);
    } catch (NumberFormatException e) {
      return false;
    }
    return v4 ? bits >= 0 && bits <= 32 : bits >= 0 && bits <= 128;
  }

  private static boolean isIpv4(String address) {
    var matcher = IPV4.matcher(address);
    if (!matcher.matches()) return false;
    for (int i = 1; i <= 4; i++) {
      var part = matcher.group(i);
      // 🔴 拒绝前导零（'010'）：同一串在不同解析器下含义不同
      if (part.length() > 1 && part.charAt(0) == '0') return false;
      int value = Integer.parseInt(part);
      if (value > 255) return false;
    }
    return true;
  }

  private static boolean isIpv6(String address) {
    if (!IPV6.matcher(address).matches()) return false;
    // IPv4 映射写法（::ffff:1.2.3.4）单独校验尾部四段
    var lastColon = address.lastIndexOf(':');
    if (lastColon >= 0 && lastColon + 1 < address.length()) {
      var tail = address.substring(lastColon + 1);
      if (tail.indexOf('.') >= 0) {
        return isIpv4(tail);
      }
    }
    return true;
  }

  /**
   * 归一化网段字面量（去掉首尾空白、统一小写）。
   *
   * <p>🔴 <b>刻意不做「归一化到网段边界」</b>（把 {@code 10.0.0.5/24} 改成 {@code 10.0.0.0/24}）：
   * PG 的 {@code inet} 在比较时**按掩码长度**判定包含关系，主机位不影响结果，
   * 而服务层擅自改写用户填的网段会让界面显示的与实际生效的不一致。
   */
  public static String normalizeCidr(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  // ================================================================ 异常判定

  /**
   * 失败登录激增的严重度。
   *
   * <p>🔴 **到阈值的 2 倍升一档**：同样是「超过阈值」，20 次失败和 5 次失败不是一回事，
   * 但都报 HIGH 会让最严重的那批淹没在中等那批里。
   */
  public static Severity failedBurstSeverity(int count, int threshold) {
    if (threshold <= 0) return Severity.MEDIUM;
    if (count >= threshold * 2L) return Severity.CRITICAL;
    if (count >= threshold) return Severity.HIGH;
    return Severity.MEDIUM;
  }

  /**
   * 短时多次成功登录的严重度。
   *
   * <p>🔴 成功登录激增的严重度**低于**失败激增：连续失败是明确的攻击信号，
   * 而连续成功可能只是运营在两台机器上切来切去 —— 报成 HIGH 会造成大量误报，
   * 误报一多，告警就没人看了。
   */
  public static Severity successBurstSeverity(int count, int threshold) {
    if (threshold <= 0) return Severity.LOW;
    if (count >= threshold * 2L) return Severity.HIGH;
    if (count >= threshold) return Severity.MEDIUM;
    return Severity.LOW;
  }

  // ================================================================ 状态机

  /**
   * 告警状态机：新警 → 已确认 / 已忽略 / 已处置。
   *
   * <p>🔴 与迁移里的 {@code guard_access_security_alert_status} 守卫**同一张转换表**。
   * 服务层先判一次（好报错），DB 层再判一次（防绕过）。
   */
  public static boolean canTransition(AlertStatus from, AlertStatus to) {
    if (from == to) return true;
    return switch (from) {
      case NEW -> to == AlertStatus.ACKNOWLEDGED || to == AlertStatus.IGNORED || to == AlertStatus.RESOLVED;
      case ACKNOWLEDGED -> to == AlertStatus.IGNORED || to == AlertStatus.RESOLVED;
      case IGNORED, RESOLVED -> false;
    };
  }

  // ================================================================ 幂等键

  /**
   * 告警的幂等键：同一 (类型, 主体) 的未关闭告警至多一条。
   *
   * <p>🔴 键里**不含窗口**：窗口每次扫描都变，带上窗口就等于每次扫描都开一条新告警，
   * 重复扫描会把运营的告警列表淹掉。键只由「类型 + 主体」决定，
   * 重复扫描命中同一条告警时**更新**它的事件数与窗口。
   */
  public static String alertDedupKey(AlertType type, String subjectValue) {
    return type.name() + "|" + (subjectValue == null ? "" : subjectValue.trim());
  }
}
