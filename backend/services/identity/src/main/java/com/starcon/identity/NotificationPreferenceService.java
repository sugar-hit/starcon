package com.starcon.identity;

import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceCapabilities;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceChannelSetting;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceInput;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceResolution;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceTypeSetting;
import static com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceView;

import com.starcon.identity.UserPreferenceModels.UserPreferenceInput;
import com.starcon.identity.UserPreferenceModels.UserPreferenceView;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 1.3.1.2 通知偏好 —— 「接收渠道 + 免打扰时段」的读写。
 *
 * <p><b>存储复用 1.4.1.2 的 {@code user_preference} 表</b>（页面 key = {@code notifications.delivery}）：
 * 本模块记的是「每一类通知我走哪些渠道、什么时段别打扰我」，与「我上次把列表调成什么样」
 * （1.4.1.2）、「我选的界面语言与展示时区」（1.4.2.1）同属<b>纯个人偏好</b>，
 * 同属 identity 服务，同一张表、同一套 jOOQ 显式 SQL、同一套载荷形状校验
 * （{@link UserPreferencePayloadRules}）。所以这里<b>不建表、不 ALTER</b>，
 * 读写直接走 {@link UserPreferenceService}。
 *
 * <p>🔴 <b>为什么 JSONB 载荷撑得住这个模块</b>（1.3.1.1 的迁移注释里明确把
 * {@code user_preference} 留给本模块，这里把账算清楚）：
 *
 * <ul>
 *   <li><b>容量</b>：通知类型是<b>受控词表</b>（五个码），不是无上界的集合 ——
 *       载荷里最多 5 个类型桶 × 3 个渠道码 + 4 个免打扰时段，
 *       离 {@link UserPreferencePayloadRules#MAX_PAYLOAD_BYTES}（16 KB）
 *       与 {@code MAX_ARRAY_ITEMS}（64 项）都差着两个数量级；</li>
 *   <li><b>语义</b>：通知偏好是<b>一份偏好</b>（整体替换正合适），
 *       与「逐条已读状态」那种增量集合（1.3.1.1 因此才另建表）不是一回事。</li>
 * </ul>
 *
 * <p>🔴 <b>本模块额外做两件事</b>：{@link UserPreferencePayloadRules} 守的是「结构安全」
 * （键名、深度、体积、敏感字段），<b>不</b>守「这个渠道码存不存在」「这条底线项关没关掉」。
 * 所以本服务在写入前补一层语义校验（{@link NotificationPreferenceRules}），
 * 读取时再做一层<b>防御性兜底</b>：万一有人绕过本服务、用通用偏好端点写了
 * {@code {"types":[{"type":"TIMELINESS_OVERDUE","channels":[]}]}}，
 * 读回来时**底线渠道仍然在**、渠道集合**仍然不为空**，
 * 而不是把一个「关键业务通知被完全关掉」的配置发给前端或下游推送。
 *
 * <p>🔴 <b>免打扰时段是「当地时刻 + 时区」语义</b>：存的是 {@code HH:mm} 的墙上时间 +
 * 一个 IANA 时区，<b>不存</b> {@code timestamptz}。一个每天重复的墙上时间存成 UTC 时刻
 * 会在跨时区用户身上差几小时，夏令时切换那天还会再差一小时。
 * 判断「现在是不是免打扰时段」时才用那个时区把当下换算成当地时刻
 * （{@link NotificationPreferenceRules#quietWindowsAt}）。
 *
 * <p>🔴 <b>时区的口径</b>：免打扰时段按<b>用户自己的时区</b>算（不是租户时区、不是平台时区）。
 * 用户在 1.4.2.1 选的展示时区就是「用户自己的时区」，所以本模块<b>继承</b>它；
 * 用户在本模块显式设过时区，则以本模块的为准（显式 > 继承）。
 *
 * <p><b>租户与 membership_id 都来自已认证会话</b>：两者都不是请求参数，更不是请求头。
 */
@Service
public class NotificationPreferenceService {

  private final UserPreferenceService preferences;
  private final AccountLocaleService locale;

  public NotificationPreferenceService(UserPreferenceService preferences, AccountLocaleService locale) {
    this.preferences = preferences;
    this.locale = locale;
  }

  // ------------------------------------------------------------------
  // 读
  // ------------------------------------------------------------------

  /** 读我的通知偏好（**生效值**：含强制保留的底线渠道）。 */
  public NotificationPreferenceView read(UUID tenantId, UUID membershipId) {
    return read(tenantId, membershipId, Instant.now());
  }

  /** 读我的通知偏好（带显式的「当下」，供解析与测试用）。 */
  public NotificationPreferenceView read(UUID tenantId, UUID membershipId, Instant now) {
    UserPreferenceView stored = preferences.read(tenantId, membershipId, NotificationPreferenceRules.PAGE_KEY);
    return toView(stored, timezoneOf(tenantId, membershipId, stored), now);
  }

  // ------------------------------------------------------------------
  // 写
  // ------------------------------------------------------------------

  /**
   * 保存我的通知偏好（**upsert**：同一 page key 覆盖，不新增行）。
   *
   * <p><b>没给的字段保持原值</b>（与 1.4.2.1 同一套约定）：
   *
   * <ul>
   *   <li>{@code types} 里没提到的类型保持原值（没存过就是默认）；</li>
   *   <li>{@code windows} 给 {@code null} 保持原值，给<b>空列表</b>是<b>清空</b>；</li>
   *   <li>{@code timezone} 给 {@code null} 保持原值。</li>
   * </ul>
   *
   * <p>写入前先过 {@link NotificationPreferenceRules}：渠道词表、渠道集合非空、
   * 底线项不可关闭、时段形状与条数、时区合法性，都在这里被拒（400）。
   * <b>存进表里的东西一定是校验过的</b>。
   */
  public NotificationPreferenceView save(
      UUID tenantId, UUID membershipId, NotificationPreferenceInput input) {
    UserPreferenceView current =
        preferences.read(tenantId, membershipId, NotificationPreferenceRules.PAGE_KEY);
    Map<String, Object> payload = current.preference();

    // ---- 类型 → 渠道集合（没提到的类型保持原值）----
    Map<String, List<String>> channels = new LinkedHashMap<>();
    for (NotificationInboxModels.NotificationType type : NotificationInboxRules.TYPES) {
      channels.put(type.code(), effectiveChannels(payload, type.code()));
    }
    if (input != null && input.types() != null) {
      for (NotificationPreferenceChannelSetting setting : input.types()) {
        if (setting == null) continue;
        String type = NotificationInboxRules.normalizeType(setting.type());
        channels.put(
            type, NotificationPreferenceRules.normalizeChannels(type, setting.channels()));
      }
    }
    // 没提到的类型也要守住底线：万一历史载荷里那一类少了底线渠道
    //（比如有人用通用偏好端点写坏过），存回去时补回来 ——
    // 存进表里的东西必须是**合规**的，别的消费者直接读载荷也不会踩坑。
    for (Map.Entry<String, List<String>> entry : channels.entrySet()) {
      if (!NotificationPreferenceRules.isMandatoryType(entry.getKey())) continue;
      if (entry.getValue().contains(NotificationPreferenceRules.BASELINE_CHANNEL)) continue;
      entry.setValue(withBaseline(entry.getValue()));
    }

    // ---- 免打扰时段（null 保持原值，空列表清空）----
    List<String> windows = storedWindows(payload).parsed();
    if (input != null && input.windows() != null) {
      windows = NotificationPreferenceRules.normalizeWindows(input.windows());
    }

    // ---- 时区（null 保持原值）----
    String timezone = storedTimezone(payload);
    if (input != null && input.timezone() != null) {
      timezone = NotificationPreferenceRules.normalizeTimezone(input.timezone());
    }

    Map<String, Object> body = new LinkedHashMap<>();
    body.put("version", 1);
    List<Map<String, Object>> typeBuckets = new ArrayList<>();
    for (NotificationInboxModels.NotificationType type : NotificationInboxRules.TYPES) {
      Map<String, Object> bucket = new LinkedHashMap<>();
      bucket.put("type", type.code());
      bucket.put("channels", channels.get(type.code()));
      typeBuckets.add(bucket);
    }
    body.put("types", typeBuckets);
    body.put("windows", windows);
    if (timezone != null) body.put("timezone", timezone);

    UserPreferenceView saved =
        preferences.save(
            tenantId,
            membershipId,
            NotificationPreferenceRules.PAGE_KEY,
            new UserPreferenceInput(body));
    return toView(saved, timezoneOf(tenantId, membershipId, saved), Instant.now());
  }

  /** 重置（删掉那一行 = 回到默认）。幂等：本来没存过也算成功。 */
  public NotificationPreferenceView reset(UUID tenantId, UUID membershipId) {
    preferences.reset(tenantId, membershipId, NotificationPreferenceRules.PAGE_KEY);
    return read(tenantId, membershipId, Instant.now());
  }

  // ------------------------------------------------------------------
  // 解析（供 1.3.2.2 外部渠道推送过滤）
  // ------------------------------------------------------------------

  /**
   * 解析某一类通知**现在**该走哪些渠道。
   *
   * <p>这是蓝图「输出：偏好配置；<b>供 1.3.2.2 推送时过滤</b>」的落点：
   * 判定全在服务端做完（频道词表、底线项、免打扰时段），
   * 下游拿到的是「走哪几个渠道、哪几个被压制」的**结论**，不是原始载荷。
   */
  public NotificationPreferenceResolution resolve(
      UUID tenantId, UUID membershipId, String rawType) {
    return resolve(tenantId, membershipId, rawType, Instant.now());
  }

  /** 解析某一类通知现在该走哪些渠道（带显式的「当下」）。 */
  public NotificationPreferenceResolution resolve(
      UUID tenantId, UUID membershipId, String rawType, Instant now) {
    String type = NotificationInboxRules.normalizeType(rawType);
    NotificationPreferenceView view = read(tenantId, membershipId, now);
    List<String> channels = List.of();
    boolean mandatory = false;
    for (NotificationPreferenceTypeSetting setting : view.types()) {
      if (setting.type().equals(type)) {
        channels = setting.channels();
        mandatory = setting.mandatory();
      }
    }
    List<String> hits = NotificationPreferenceRules.quietWindowsAt(view.windows(), view.timezone(), now);
    List<String> suppressed = new ArrayList<>();
    if (!hits.isEmpty()) {
      for (String channel : channels) {
        if (NotificationPreferenceRules.isExternalChannel(channel)) suppressed.add(channel);
      }
    }
    return new NotificationPreferenceResolution(
        type,
        NotificationPreferenceRules.typeLabel(type),
        channels,
        suppressed,
        !hits.isEmpty(),
        hits,
        view.timezone(),
        mandatory);
  }

  // ------------------------------------------------------------------
  // 能力声明
  // ------------------------------------------------------------------

  /** 能力声明：渠道词表 + **不可关闭的底线清单** + 时段上限 + 边界。 */
  public NotificationPreferenceCapabilities capabilities() {
    List<String> boundaries =
        List.of(
            "1.3.2.1 预警规则已交付（命中产出归它，本模块只负责偏好配置与过滤结论）",
            "1.3.2.2 外部渠道推送已交付（外部渠道投递归它；本模块只负责把偏好配置存好并可读，不负责投递）",
            "免打扰时段只压制外部渠道（邮件 / 企业 IM），站内通知照常进收件箱",
            "免打扰时段按用户自己的时区算：继承 1.4.2.1 的展示时区，用户在本模块显式设置时以本模块为准",
            "关键业务通知（超期发货、账务差异）不允许被完全关闭，站内渠道不可关闭");
    return new NotificationPreferenceCapabilities(
        "1.3.1.2",
        NotificationPreferenceRules.CHANNELS,
        NotificationPreferenceRules.typeRules(),
        NotificationPreferenceRules.BASELINE_CHANNEL,
        NotificationPreferenceRules.MAX_WINDOWS,
        AccountLocaleRules.DEFAULT_TIMEZONE,
        NotificationPreferenceRules.QUIET_HOURS_SCOPE,
        boundaries);
  }

  // ------------------------------------------------------------------
  // 内部：载荷 → 生效值
  // ------------------------------------------------------------------

  /**
   * 把存下来的载荷映射成「当前生效」的值。
   *
   * <p><b>兜底而不是报错</b>：偏好是锦上添花，读不到 / 不认识就按默认值继续，
   * 绝不因为一条老数据让整页崩掉。但兜底必须<b>说清原因</b>，
   * 否则用户会以为自己选的渠道生效了。
   *
   * <p>🔴 <b>兜底里最重要的一条</b>：关键业务通知的底线渠道**必须**在生效值里。
   * 就算存储的载荷被绕过服务层改坏了（渠道集合为空 / 少了底线渠道），
   * 生效值也会把底线渠道补回去，并记一条原因。
   */
  private NotificationPreferenceView toView(
      UserPreferenceView stored, TimezoneChoice choice, Instant now) {
    Map<String, Object> payload = stored.preference();
    List<String> reasons = new ArrayList<>();

    List<NotificationPreferenceTypeSetting> settings = new ArrayList<>();
    for (NotificationInboxModels.NotificationType type : NotificationInboxRules.TYPES) {
      String code = type.code();
      boolean mandatory = NotificationPreferenceRules.isMandatoryType(code);
      List<String> channels = storedChannels(payload, code);
      if (channels.isEmpty()) {
        channels = defaultChannels();
        if (stored.stored()) {
          reasons.add("「" + type.label() + "」的渠道集合为空或不可识别，已按默认渠道显示");
        }
      }
      if (mandatory && !channels.contains(NotificationPreferenceRules.BASELINE_CHANNEL)) {
        channels = withBaseline(channels);
        reasons.add(
            "「"
                + type.label()
                + "」的关键业务通知底线渠道（"
                + NotificationPreferenceRules.BASELINE_CHANNEL
                + "）缺失，已强制补回");
      }
      settings.add(
          new NotificationPreferenceTypeSetting(
              code,
              type.label(),
              channels,
              mandatory,
              mandatory ? NotificationPreferenceRules.BASELINE_CHANNEL : null));
    }

    Windows windows = storedWindows(payload);
    if (stored.stored() && windows.dropped()) {
      reasons.add("有免打扰时段不可识别，已按「不免打扰」显示");
    }

    return new NotificationPreferenceView(
        stored.stored(),
        settings,
        windows.parsed(),
        choice.timezone(),
        choice.source(),
        AccountLocaleRules.DEFAULT_TIMEZONE,
        NotificationPreferenceRules.QUIET_HOURS_SCOPE,
        stored.updatedAt(),
        reasons);
  }

  /** 默认渠道集合：三类通知全开（与 1.3.1.1「没自定义过 = 全部类型」同一套默认口径）。 */
  private List<String> defaultChannels() {
    return NotificationPreferenceRules.channelCodes();
  }

  /** 把底线渠道补回渠道集合（保持词表顺序）。 */
  private List<String> withBaseline(List<String> channels) {
    List<String> merged = new ArrayList<>();
    for (String code : NotificationPreferenceRules.channelCodes()) {
      if (code.equals(NotificationPreferenceRules.BASELINE_CHANNEL)
          || channels.contains(code)) {
        merged.add(code);
      }
    }
    return merged;
  }

  /** 从载荷里读某个类型的渠道集合（读不出来就是空列表）。 */
  private List<String> storedChannels(Map<String, Object> payload, String type) {
    Object raw = payload.get("types");
    if (!(raw instanceof List<?> buckets)) return List.of();
    for (Object bucket : buckets) {
      if (!(bucket instanceof Map<?, ?> map)) continue;
      if (!type.equals(String.valueOf(map.get("type")))) continue;
      Object channels = map.get("channels");
      if (!(channels instanceof List<?> items)) return List.of();
      List<String> out = new ArrayList<>();
      for (Object item : items) {
        String code = String.valueOf(item);
        if (NotificationPreferenceRules.isKnownChannel(code) && !out.contains(code)) out.add(code);
      }
      // 按词表顺序返回（不是按存储顺序）—— 界面上的勾选顺序不该取决于历史写入顺序。
      List<String> ordered = new ArrayList<>();
      for (String candidate : NotificationPreferenceRules.channelCodes()) {
        if (out.contains(candidate)) ordered.add(candidate);
      }
      return ordered;
    }
    return List.of();
  }

  /**
   * 某个类型**生效**的渠道集合（读路径用）：存的是空 / 不可识别时回落成默认渠道。
   *
   * <p>写路径也用它取「原值」—— 免得把一条历史坏数据原样写回去，
   * 让「渠道集合不允许为空」这条纪律在存储里被绕过。
   */
  private List<String> effectiveChannels(Map<String, Object> payload, String type) {
    List<String> stored = storedChannels(payload, type);
    return stored.isEmpty() ? defaultChannels() : stored;
  }

  /**
   * 从载荷里读免打扰时段。
   *
   * @param parsed 读得出的时段（按开始时刻排序）
   * @param dropped 有没有**读不出来**的时段（形状不对的那些被丢掉了）
   */
  private Windows storedWindows(Map<String, Object> payload) {
    Object raw = payload.get("windows");
    if (!(raw instanceof List<?> items)) return new Windows(List.of(), false);
    List<String> out = new ArrayList<>();
    boolean dropped = false;
    for (Object item : items) {
      String window = String.valueOf(item);
      try {
        List<String> normalized = NotificationPreferenceRules.normalizeWindows(List.of(window));
        if (!out.contains(normalized.get(0))) out.add(normalized.get(0));
      } catch (RuntimeException e) {
        // 形状不对的时段直接丢掉（读取路径不抛错，理由记在生效值里）
        dropped = true;
      }
    }
    out.sort(
        (left, right) ->
            NotificationPreferenceRules.startOf(left).compareTo(NotificationPreferenceRules.startOf(right)));
    return new Windows(out, dropped);
  }

  /** 从载荷里读显式设置的时区（没设就是 null = 继承 1.4.2.1）。 */
  private String storedTimezone(Map<String, Object> payload) {
    Object raw = payload.get("timezone");
    if (!(raw instanceof String text)) return null;
    String zone = text.trim();
    return AccountLocaleRules.isKnownTimezone(zone) ? zone : null;
  }

  /**
   * 时区的最终口径：**显式设置 &gt; 继承 1.4.2.1 的展示时区 &gt; 平台默认**。
   *
   * @param stored 本人存下来的那一行
   */
  private TimezoneChoice timezoneOf(
      UUID tenantId, UUID membershipId, UserPreferenceView stored) {
    String explicit = storedTimezone(stored.preference());
    if (explicit != null) return new TimezoneChoice(explicit, "PREFERENCE");
    AccountLocaleModels.AccountLocaleView localeView = locale.read(tenantId, membershipId);
    if (localeView.stored() && AccountLocaleRules.isKnownTimezone(localeView.timezone())) {
      return new TimezoneChoice(localeView.timezone(), "ACCOUNT_LOCALE");
    }
    return new TimezoneChoice(AccountLocaleRules.DEFAULT_TIMEZONE, "DEFAULT");
  }

  /** 时区 + 它的来源（来源会随响应一起给前端，避免用户不知道自己用的是哪个时区）。 */
  private record TimezoneChoice(String timezone, String source) {}

  /**
   * 免打扰时段的读取结果。
   *
   * @param parsed 读得出的时段
   * @param dropped 有没有**读不出来**的时段（形状不对的那些被丢掉了）
   */
  private record Windows(List<String> parsed, boolean dropped) {}
}
