package com.starcon.identity;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.3.2.2 外部渠道推送 —— 受控词表与入参形状的**纯函数**校验规则。
 *
 * <p>刻意做成不碰数据库的纯函数：这些规则要能被单元测试逐条钉住
 * （哪些词被拒、哪个引用串被拒、上限是多少），而不是只能靠集成测试间接观察。
 *
 * <p><b>它守的是三件事</b>：
 *
 * <ul>
 *   <li><b>词表受控</b>：渠道码 / 消息样式 / 来源种类 / 通知类型 / 状态 全部是受控码；</li>
 *   <li><b>凭据不落库</b>：{@code secretRef} 必须是 secret manager 引用串，
 *       {@code https://…} 这类**地址本体**一律拒绝（V385 的 CHECK 还会再挡一道）；</li>
 *   <li><b>跳转目标只允许站内相对路径</b>：一条通知可以把收件人带到站外去，
 *       而那个地址没有任何审核环节。</li>
 * </ul>
 *
 * <p><b>为什么不在前端校验就够</b>：前端是可以被绕过的 ——
 * 任何人拿自己的 token 就能直接打这些接口。所以词表与形状
 * 必须在服务端再钉一遍，否则它只是「我们约定不改」，不是「改不了」。
 */
public final class ExternalPushRules {

  private ExternalPushRules() {}

  // ==========================================================================
  // 一、受控词表（与 V385 的 CHECK 约束逐字同词表）
  // ==========================================================================

  /** 渠道码词表（蓝图点名的四个：企业微信 / 钉钉 / 飞书 / 邮件）。 */
  public static final List<String> CHANNEL_CODES =
      List.of("WECHAT_WORK", "DINGTALK", "FEISHU", "EMAIL");

  /** 消息样式词表：卡片化 / 纯文本。 */
  public static final List<String> CARD_STYLES = List.of("CARD", "TEXT");

  /** 来源种类词表。 */
  public static final List<String> SOURCE_KINDS = List.of("ALERT_HIT", "NOTIFICATION");

  /** 投递状态词表（四个码，语义各不相同，见 ExternalPushModels 的类注释）。 */
  public static final List<String> DELIVERY_STATUSES =
      List.of("DELIVERED", "FAILED", "FAILED_EXHAUSTED", "DEFERRED");

  /** 告警状态词表。 */
  public static final List<String> ALERT_STATES = List.of("NONE", "RAISED", "ACKNOWLEDGED");

  /** 渠道启停状态词表。 */
  public static final List<String> CHANNEL_STATUSES = List.of("ENABLED", "DISABLED");

  /**
   * **secret manager 引用**的形状：{@code <scheme>://<路径>}。
   *
   * <p>scheme 只收四个：secret / vault / external-secrets / k8s ——
   * 都是「凭据存在 secret manager 里」的引用形态。
   *
   * <p>🔴 <b>{@code https://} 不在名单里</b>：那不是引用，那是**凭据本体**
   * （Webhook 地址本身）。蓝图加粗句「凭据不落库」在这条正则上落成一道闸。
   */
  public static final Pattern SECRET_REF =
      Pattern.compile("^(secret|vault|external-secrets|k8s)://[A-Za-z0-9][A-Za-z0-9._/-]{0,180}$");

  /** 站内相对路径的形状（跳转目标）。 */
  private static final Pattern RELATIVE_PATH = Pattern.compile("^/[^\\s]{1,499}$");

  /** 渠道码的字符集（受控词表之外的自由文本一律拒绝）。 */
  private static final Pattern CHANNEL_CODE = Pattern.compile("^[A-Z][A-Z_]{1,23}$");

  /** 默认最大尝试次数（含首次）。 */
  public static final int DEFAULT_MAX_ATTEMPTS = 3;

  /** 最大尝试次数上限（防呆：不让调用方把重试次数设成几百次）。 */
  public static final int MAX_ATTEMPTS_LIMIT = 10;

  /**
   * 重试退避表（秒）：第 1 次失败后等 1 分钟，第 2 次后等 5 分钟，第 3 次后等 15 分钟。
   *
   * <p>🔴 <b>退避按「尝试次数」算，不按「当天第几次」算</b> ——
   * 用墙钟/日历做退避会在跨午夜时把两次重试挤到一起，
   * 也会让「重试间隔」在某一天突然变成 0。
   */
  private static final long[] BACKOFF_SECONDS = {60L, 300L, 900L};

  // ==========================================================================
  // 二、逐项归一
  // ==========================================================================

  /** 渠道码归一。 */
  public static String normalizeChannelCode(String raw) {
    String code = raw == null ? "" : raw.trim();
    if (!CHANNEL_CODE.matcher(code).matches() || !CHANNEL_CODES.contains(code)) {
      throw badRequest("外部渠道不在受控词表里：" + describe(code) + "（可选：" + String.join(" / ", CHANNEL_CODES) + "）");
    }
    return code;
  }

  /** 渠道显示名归一。 */
  public static String normalizeDisplayName(String raw) {
    String name = raw == null ? "" : raw.trim();
    if (name.isEmpty()) {
      throw badRequest("渠道显示名不能为空");
    }
    if (name.length() > 100) {
      throw badRequest("渠道显示名不能超过 100 个字符");
    }
    return name;
  }

  /**
   * secret manager 引用归一。
   *
   * <p>🔴 <b>这是本模块最重要的一条校验</b>：它拒绝的是「凭据本体」。
   *
   * @throws ResponseStatusException 形状不合法（400）
   */
  public static String normalizeSecretRef(String raw) {
    String ref = raw == null ? "" : raw.trim();
    if (ref.isEmpty()) {
      throw badRequest("secret manager 引用不能为空");
    }
    if (ref.toLowerCase(java.util.Locale.ROOT).startsWith("http://")
        || ref.toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
      throw badRequest(
          "凭据不落库：这里只接受 secret manager 引用（如 secret://projects/starcon/tenants/x/wecom），"
              + "不接受 Webhook 地址本身。地址请先存进 secret manager，再把它的引用填到这里。");
    }
    if (!SECRET_REF.matcher(ref).matches()) {
      throw badRequest(
          "secret manager 引用形状不合法：应为 <scheme>://<路径>，"
              + "scheme 只允许 secret / vault / external-secrets / k8s，收到：" + describe(ref));
    }
    return ref;
  }

  /** 渠道启停状态归一。 */
  public static String normalizeChannelStatus(String raw) {
    String status = raw == null ? "" : raw.trim();
    if (!CHANNEL_STATUSES.contains(status)) {
      throw badRequest("渠道状态不在受控词表里：" + describe(status) + "（可选：" + String.join(" / ", CHANNEL_STATUSES) + "）");
    }
    return status;
  }

  /** 消息样式归一（不给按 CARD 处理）。 */
  public static String normalizeCardStyle(String raw) {
    String style = raw == null || raw.isBlank() ? "CARD" : raw.trim();
    if (!CARD_STYLES.contains(style)) {
      throw badRequest("消息样式不在受控词表里：" + describe(style) + "（可选：" + String.join(" / ", CARD_STYLES) + "）");
    }
    return style;
  }

  /** 来源种类归一。 */
  public static String normalizeSourceKind(String raw) {
    String kind = raw == null ? "" : raw.trim();
    if (!SOURCE_KINDS.contains(kind)) {
      throw badRequest("推送来源不在受控词表里：" + describe(kind) + "（可选：" + String.join(" / ", SOURCE_KINDS) + "）");
    }
    return kind;
  }

  /** 投递状态归一（筛选投递记录时用）。 */
  public static String normalizeDeliveryStatus(String raw) {
    String status = raw == null ? "" : raw.trim();
    if (!DELIVERY_STATUSES.contains(status)) {
      throw badRequest(
          "投递状态不在受控词表里：" + describe(status) + "（可选：" + String.join(" / ", DELIVERY_STATUSES) + "）");
    }
    return status;
  }

  /** 告警状态归一（筛选投递记录时用）。 */
  public static String normalizeAlertState(String raw) {
    String state = raw == null ? "" : raw.trim();
    if (!ALERT_STATES.contains(state)) {
      throw badRequest(
          "告警状态不在受控词表里：" + describe(state) + "（可选：" + String.join(" / ", ALERT_STATES) + "）");
    }
    return state;
  }

  /**
   * 通知类型归一（**复用 1.3.1.1 的受控词表**，不另立一套）。
   *
   * <p>通知类型是**系统枚举**：订阅、偏好、投递三处引用的是同一批码。
   * 这里另写一份词表，迟早会与 1.3.1.1 的漂移。
   */
  public static String normalizeNotificationType(String raw) {
    try {
      return NotificationInboxRules.normalizeType(raw);
    } catch (ResponseStatusException e) {
      throw badRequest(e.getReason() == null ? "通知类型不在受控词表里" : e.getReason());
    }
  }

  /**
   * 上游主键归一（**复用 1.3.1.1 的字符集**：字母 / 数字 / 点 / 下划线 / 连字符 / 冒号）。
   */
  public static String normalizeNotificationRef(String raw) {
    String ref = raw == null ? "" : raw.trim();
    if (!NotificationInboxRules.REF.matcher(ref).matches()) {
      throw badRequest("上游主键不合法（只允许字母 / 数字 / 点 / 下划线 / 连字符 / 冒号，长度 1~120）");
    }
    return ref;
  }

  /** 标题归一。 */
  public static String normalizeTitle(String raw) {
    String title = raw == null ? "" : raw.trim();
    if (title.isEmpty()) {
      throw badRequest("推送标题不能为空");
    }
    if (title.length() > 200) {
      throw badRequest("推送标题不能超过 200 个字符");
    }
    return title;
  }

  /** 正文归一（可空；给空串按「没有正文」处理）。 */
  public static String normalizeBody(String raw) {
    if (raw == null) return null;
    String body = raw.trim();
    if (body.isEmpty()) return null;
    if (body.length() > 1000) {
      throw badRequest("推送正文不能超过 1000 个字符");
    }
    return body;
  }

  /**
   * 跳转目标归一（**只允许站内相对路径**）。
   *
   * <p>🔴 <b>绝对地址一律拒绝</b>：一条通知可以把收件人带到站外去，
   * 而那个地址没有任何审核环节 —— 这既是安全边界，也是「点击跳转」本来的意思
   * （跳回本系统里那件事的处理页）。
   */
  public static String normalizeLinkUrl(String raw) {
    if (raw == null || raw.isBlank()) return null;
    String link = raw.trim();
    if (!RELATIVE_PATH.matcher(link).matches() || link.contains("://")) {
      throw badRequest("跳转目标只允许站内相对路径（以 / 开头），收到：" + describe(link));
    }
    return link;
  }

  /** 目标渠道 id 集合归一（**去重 + 至少一个**）。 */
  public static List<UUID> normalizeChannelIds(List<UUID> raw) {
    if (raw == null || raw.isEmpty()) {
      throw badRequest("至少选一个目标渠道");
    }
    LinkedHashSet<UUID> wanted = new LinkedHashSet<>();
    for (UUID id : raw) {
      if (id == null) {
        throw badRequest("目标渠道 id 不能为空");
      }
      wanted.add(id);
    }
    return new ArrayList<>(wanted);
  }

  // ==========================================================================
  // 三、重试退避
  // ==========================================================================

  /**
   * 下次重试时刻 = 本次尝试时刻 + 退避间隔。
   *
   * <p>🔴 <b>退避按尝试次数算</b>（{@link #BACKOFF_SECONDS}），不写死、也不看日历 ——
   * 看日历会让跨午夜那两次重试挤到一起。
   *
   * @param attemptedAt 本次尝试时刻
   * @param attemptCount 本次是第几次尝试（1 起）
   */
  public static OffsetDateTime nextAttemptAt(OffsetDateTime attemptedAt, int attemptCount) {
    int index = Math.min(Math.max(attemptCount, 1), BACKOFF_SECONDS.length) - 1;
    return attemptedAt.plusSeconds(BACKOFF_SECONDS[index]);
  }

  /** 列表每页条数上限（防呆）。 */
  public static int clampLimit(int limit) {
    return Math.max(1, Math.min(limit <= 0 ? 50 : limit, 200));
  }

  /** 偏移量归一。 */
  public static int clampOffset(int offset) {
    return Math.max(0, offset);
  }

  // ==========================================================================
  // 四、内部
  // ==========================================================================

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static String describe(String value) {
    if (value == null) return "(空)";
    return value.length() > 80 ? value.substring(0, 80) + "…" : value;
  }

  /** 保留 Duration 的导入（退避表以秒为单位时用它做一次断言，避免读者猜单位）。 */
  static Duration backoffOf(int attemptCount) {
    int index = Math.min(Math.max(attemptCount, 1), BACKOFF_SECONDS.length) - 1;
    return Duration.ofSeconds(BACKOFF_SECONDS[index]);
  }
}
