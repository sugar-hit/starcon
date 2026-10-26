package com.starcon.identity;

import com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceChannel;
import com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceTypeRule;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.3.1.2 通知偏好 —— 渠道词表、**不可关闭底线项**与免打扰时段的**纯函数**校验规则。
 *
 * <p>蓝图原文（本模块要兑现的全部内容）：
 *
 * <blockquote>
 *
 * <b>职责</b>：用户按通知类型选择接收渠道（站内/邮件/企业 IM）与免打扰时段。
 *
 * <b>输入</b>：用户的偏好修改；通知类型的系统枚举。
 *
 * <b>输出</b>：偏好配置；供 1.3.2.2 推送时过滤。
 *
 * <b>关系</b>：上游 = 1.3.1.1；下游 = 1.3.2.2 外部渠道推送。共享数据：{@code user_preference}。
 * <b>关键业务通知（超期发货、账务差异）不允许被完全关闭</b>，系统强制保留下不可关闭的底线项。
 *
 * </blockquote>
 *
 * <p>刻意做成不碰数据库的纯函数：这些规则要能被单元测试逐条钉住
 * （哪些渠道被拒、哪些类型是底线项、时段怎么算、跨午夜怎么算），
 * 而不是只能靠集成测试间接观察。
 *
 * <p>🔴 <b>类型词表复用 1.3.1.1 的那一份</b>（{@link NotificationInboxRules#TYPES}）：
 * 本模块**不另立一套枚举**。两份词表一旦漂移，表现是「订阅了某类型但偏好页上没有这一行」
 * —— 通知类型是**系统枚举**，不是每个模块各写一份的局部概念。
 *
 * <p>🔴 <b>免打扰时段是「当地时刻 + 时区」语义</b>，不是 UTC：
 * 一个每天重复的墙上时间（22:00–08:00）存成 UTC 时刻会在跨时区用户身上差几小时，
 * 而且夏令时切换那天会差一小时。所以这里存的是 {@code HH:mm} 的**当地时间** + 一个 IANA 时区，
 * 判断「现在是不是免打扰时段」时才用那个时区把当下换算成当地时刻。
 */
public final class NotificationPreferenceRules {

  private NotificationPreferenceRules() {}

  /**
   * 本模块占用的偏好页面 key（与 1.4.1.2 / 1.4.2.1 共用 {@code user_preference} 表，key 各不相同）。
   *
   * <p>命名与 {@link AccountLocaleRules#PAGE_KEY} 同一套约定：{@code <域>.<面>}。
   */
  public static final String PAGE_KEY = "notifications.delivery";

  /**
   * 接收渠道词表（**受控**，不是自由文本）。
   *
   * <p>蓝图原文：「用户按通知类型选择接收渠道（<b>站内/邮件/企业 IM</b>）」—— 三个渠道，逐字对应。
   */
  public static final List<NotificationPreferenceChannel> CHANNELS =
      List.of(
          new NotificationPreferenceChannel("IN_APP", "站内", "通知进站内收件箱（1.3.1.1）"),
          new NotificationPreferenceChannel("EMAIL", "邮件", "外部渠道，依赖 1.3.2.2 的投递通道"),
          new NotificationPreferenceChannel("IM", "企业 IM", "外部渠道，依赖 1.3.2.2 的投递通道"));

  /**
   * **不可关闭的底线类型**（蓝图点名的那两个：超期发货、账务差异）。
   *
   * <p>蓝图加粗句「**关键业务通知（超期发货、账务差异）不允许被完全关闭**，系统强制保留下
   * 不可关闭的底线项」在这里落成**结构**，不是前端把复选框置灰：
   *
   * <ul>
   *   <li>这两个类型的**渠道集合永远不为空**（写入时空集合被 400 拒掉）；</li>
   *   <li>这两个类型**永远保留 {@link #BASELINE_CHANNEL} 这一条底线渠道**（去掉它同样被 400 拒掉）；</li>
   *   <li>读的时候**再兜一次底**：万一有人绕过本服务、用通用偏好端点写了不合法的载荷，
   *       生效值里底线渠道仍然在（见 {@code NotificationPreferenceService} 的归一化）。</li>
   * </ul>
   *
   * <p>类型码与 1.3.1.1 的词表逐字一致：{@code TIMELINESS_OVERDUE} = 09 订单的履约超时（= 超期发货），
   * {@code RECONCILIATION_DIFF} = 12 财务的对账差异（= 账务差异）。
   */
  public static final List<String> MANDATORY_TYPES = List.of("TIMELINESS_OVERDUE", "RECONCILIATION_DIFF");

  /**
   * 底线渠道：关键业务通知**永远**保留的那一条。
   *
   * <p>取「站内」是因为它是**唯一不依赖外部通道**的渠道：邮件与企业 IM 的投递通道属于
   * 1.3.2.2（尚未交付），若把底线挂在外部渠道上，1.3.2.2 没交付时关键通知就等于没有底线了。
   */
  public static final String BASELINE_CHANNEL = "IN_APP";

  /**
   * 免打扰时段**只压制外部渠道**（邮件 / 企业 IM），站内通知照常进收件箱。
   *
   * <p>这不是「漏做一半」，而是**明确的口径**：免打扰管的是「别来打扰我」——
   * 半夜一封邮件和一条企业 IM 才是打扰；收件箱里的通知不打断任何人，
   * 而且 1.3.1.1 的收件箱是各域投影的聚合，与本模块的投递过滤是两件事。
   * 这个口径随能力声明一起对外公布（{@code quietHoursScope}），不是隐含行为。
   */
  public static final String QUIET_HOURS_SCOPE = "EXTERNAL_CHANNELS";

  /** 免打扰时段条数上限（防呆，同时把请求体压在合理尺寸内）。 */
  public static final int MAX_WINDOWS = 4;

  /** 时段形状：{@code HH:mm-HH:mm}（当地时间，24 小时制，允许跨午夜）。 */
  private static final Pattern WINDOW = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)-([01]\\d|2[0-3]):([0-5]\\d)$");

  /** 渠道码的字符集（受控词表之外的自由文本一律拒绝）。 */
  private static final Pattern CHANNEL_CODE = Pattern.compile("^[A-Z][A-Z_]{1,39}$");

  private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

  // ------------------------------------------------------------------
  // 渠道
  // ------------------------------------------------------------------

  /** 渠道码是否在受控词表里。 */
  public static boolean isKnownChannel(String code) {
    if (code == null) return false;
    for (NotificationPreferenceChannel channel : CHANNELS) {
      if (channel.code().equals(code)) return true;
    }
    return false;
  }

  /** 词表里的渠道码（按词表顺序）。 */
  public static List<String> channelCodes() {
    List<String> codes = new ArrayList<>(CHANNELS.size());
    for (NotificationPreferenceChannel channel : CHANNELS) codes.add(channel.code());
    return codes;
  }

  /** 该渠道是不是外部渠道（受免打扰时段压制）。 */
  public static boolean isExternalChannel(String code) {
    return isKnownChannel(code) && !BASELINE_CHANNEL.equals(code);
  }

  /** 该类型是不是「关键业务通知」（不可完全关闭的底线项）。 */
  public static boolean isMandatoryType(String type) {
    return type != null && MANDATORY_TYPES.contains(type);
  }

  /**
   * 归一化一个类型的渠道集合：**去重 + 按词表顺序排序 + 强制底线**。
   *
   * <p><b>三条纪律都在这里落地</b>：
   *
   * <ol>
   *   <li><b>渠道集合不允许为空</b>（与 1.3.1.1「订阅集合不允许为空」同一条纪律）：
   *       空集合一律 400 —— 想彻底不要某一类通知，去 1.3.1.1 取消订阅那一类。</li>
   *   <li><b>关键业务通知不允许被完全关闭</b>：底线类型的渠道集合里必须留着底线渠道，
   *       少了它就是 400（而不是静默补上 —— 静默补上会让用户以为自己关成功了）。</li>
   *   <li><b>词表外的渠道码一律 400</b>。</li>
   * </ol>
   *
   * @param type 通知类型码（受控词表，1.3.1.1 的那一份）
   * @param rawChannels 调用方给的渠道码集合
   * @throws ResponseStatusException 渠道码不在词表里 / 集合为空 / 试图关掉底线渠道（400）
   */
  public static List<String> normalizeChannels(String type, List<String> rawChannels) {
    if (rawChannels == null || rawChannels.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "接收渠道集合不能为空（想彻底不要这一类通知，请到通知列表里取消订阅）");
    }
    LinkedHashSet<String> wanted = new LinkedHashSet<>();
    for (String raw : rawChannels) {
      String code = raw == null ? "" : raw.trim();
      if (!CHANNEL_CODE.matcher(code).matches() || !isKnownChannel(code)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "接收渠道不在受控词表里：" + describe(code));
      }
      wanted.add(code);
    }
    if (isMandatoryType(type) && !wanted.contains(BASELINE_CHANNEL)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "关键业务通知「" + typeLabel(type) + "」不允许被完全关闭：「" + BASELINE_CHANNEL_LABEL + "」渠道不可关闭");
    }
    List<String> ordered = new ArrayList<>(wanted.size());
    for (NotificationPreferenceChannel channel : CHANNELS) {
      if (wanted.contains(channel.code())) ordered.add(channel.code());
    }
    return ordered;
  }

  /** 底线渠道的中文名（给错误消息用，避免把英文码直接甩给用户）。 */
  public static final String BASELINE_CHANNEL_LABEL = "站内";

  // ------------------------------------------------------------------
  // 免打扰时段
  // ------------------------------------------------------------------

  /**
   * 归一化一批免打扰时段：形状校验 + 去重 + 按开始时刻排序。
   *
   * <p><b>时段是当地时间</b>：{@code start} 晚于 {@code end} 表示**跨午夜**
   * （如 {@code 22:00-08:00} = 夜里十点到次日早上八点）。起止相同一律拒绝 ——
   * 那等于「全天免打扰」，会让关键业务通知在外部渠道上永远静默，是个危险的歧义。
   *
   * @throws ResponseStatusException 形状不合法 / 起止相同 / 重复 / 超过上限（400）
   */
  public static List<String> normalizeWindows(List<String> rawWindows) {
    if (rawWindows == null) return List.of();
    if (rawWindows.size() > MAX_WINDOWS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "免打扰时段最多 " + MAX_WINDOWS + " 段（收到 " + rawWindows.size() + " 段）");
    }
    LinkedHashSet<String> seen = new LinkedHashSet<>();
    for (String raw : rawWindows) {
      String window = raw == null ? "" : raw.trim();
      if (!WINDOW.matcher(window).matches()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "免打扰时段形状不合法（应为 HH:mm-HH:mm 的当地时间）：" + describe(window));
      }
      int dash = window.indexOf('-');
      LocalTime start = LocalTime.parse(window.substring(0, dash), HH_MM);
      LocalTime end = LocalTime.parse(window.substring(dash + 1), HH_MM);
      if (start.equals(end)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "免打扰时段的起止时刻不能相同（那等于全天免打扰）：" + window);
      }
      if (!seen.add(window)) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "免打扰时段重复：" + window);
      }
    }
    List<String> ordered = new ArrayList<>(seen);
    ordered.sort((left, right) -> startOf(left).compareTo(startOf(right)));
    return ordered;
  }

  /**
   * 现在是不是落在某个免打扰时段里。
   *
   * <p>🔴 <b>这里的时区换算是整个模块唯一一处时间换算</b>，而且换算方向是
   * 「<b>当下 → 当地时刻</b>」，不是「当地时刻 → UTC」：
   * 存的是墙上时间，判的是墙上时间，中间不需要任何 UTC 偏移，
   * 所以 {@code Timestamp.valueOf(LocalDateTime)} 那一类静默偏 −28800s 的错误在这里根本不会出现。
   *
   * @param windows 已归一化的免打扰时段（当地时间）
   * @param timezone IANA 时区 id
   * @param now 判定的当下
   * @return 命中的时段（没有命中就是空列表）
   */
  public static List<String> quietWindowsAt(List<String> windows, String timezone, Instant now) {
    if (windows == null || windows.isEmpty()) return List.of();
    ZoneId zone;
    try {
      zone = ZoneId.of(timezone);
    } catch (RuntimeException e) {
      return List.of();
    }
    LocalTime local = LocalTime.ofInstant(now, zone);
    List<String> hits = new ArrayList<>();
    for (String window : windows) {
      int dash = window.indexOf('-');
      LocalTime start = LocalTime.parse(window.substring(0, dash), HH_MM);
      LocalTime end = LocalTime.parse(window.substring(dash + 1), HH_MM);
      boolean hit = start.isBefore(end) ? (!local.isBefore(start) && local.isBefore(end)) : (!local.isBefore(start) || local.isBefore(end));
      if (hit) hits.add(window);
    }
    return hits;
  }

  /** 时段的**开始时刻**（当地时间）。排序用。 */
  public static LocalTime startOf(String window) {
    return LocalTime.parse(window.substring(0, window.indexOf('-')), HH_MM);
  }

  // ------------------------------------------------------------------
  // 时区
  // ------------------------------------------------------------------

  /**
   * 校验并归一化时区（IANA **区域 id**）。
   *
   * <p>🔴 <b>刻意不直接复用 {@link AccountLocaleRules#normalizeTimezone}</b>：
   * 那个方法抛的是 1.4.2.1 自己的 {@code AccountLocaleException}，
   * 由 {@code AccountLocaleController} 的**控制器局部**异常处理器转成 400。
   * 本模块的控制器没有那个处理器，直接抛过去会变成 500 ——
   * 「时区写错了」必须是 400，不是服务端故障。
   *
   * <p>所以这里自己判、抛本模块统一的 {@link ResponseStatusException}（400），
   * 判定口径与 1.4.2.1 逐字一致（只认 tzdb 里的区域 id，不收 {@code UTC+8} 这类偏移量写法）。
   */
  public static String normalizeTimezone(String raw) {
    String zone = raw == null ? "" : raw.trim();
    if (!AccountLocaleRules.isKnownTimezone(zone)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "不是有效的 IANA 时区："
              + describe(zone)
              + "（例：Asia/Shanghai；不收 UTC+8 这类偏移量写法）");
    }
    return zone;
  }

  // ------------------------------------------------------------------
  // 类型词表
  // ------------------------------------------------------------------

  /** 类型的中文名（取自 1.3.1.1 的那一份词表，不另立一份）。 */
  public static String typeLabel(String type) {
    for (NotificationInboxModels.NotificationType candidate : NotificationInboxRules.TYPES) {
      if (candidate.code().equals(type)) return candidate.label();
    }
    return type;
  }

  /** 类型 → 是否为底线项（供能力声明用）。 */
  public static List<NotificationPreferenceTypeRule> typeRules() {
    List<NotificationPreferenceTypeRule> rules = new ArrayList<>();
    for (NotificationInboxModels.NotificationType type : NotificationInboxRules.TYPES) {
      boolean mandatory = isMandatoryType(type.code());
      rules.add(
          new NotificationPreferenceTypeRule(
              type.code(),
              type.label(),
              mandatory,
              mandatory ? BASELINE_CHANNEL : null,
              mandatory
                  ? "关键业务通知：不允许被完全关闭，「" + BASELINE_CHANNEL_LABEL + "」渠道不可关闭"
                  : "可以只留一个渠道，但渠道集合不能为空"));
    }
    return rules;
  }

  private static String describe(String value) {
    if (value == null) return "(空)";
    String trimmed = value.trim();
    if (trimmed.isEmpty()) return "(空)";
    return trimmed.length() > 64 ? trimmed.substring(0, 64) + "…" : trimmed;
  }
}
