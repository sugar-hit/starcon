package com.starcon.identity;

import com.starcon.identity.AccountLocaleModels.AccountLocaleDisplayConvention;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 1.4.2.1 语言与时区 —— <b>纯函数</b>规则（不碰数据库、不碰 HTTP）。
 *
 * <p>做成纯函数是为了能被单元测试逐条钉住：哪些时区被拒、哪些语言被拒、
 * 回落时回落成什么、显示口径是什么 —— 而不是只能靠集成测试间接观察。
 *
 * <p><b>本类只做「展示层」的判断，一行存储时区的代码都没有</b>：
 * 蓝图把「存储统一 {@code timestamptz}，只在展示层转换」写成硬约束，
 * 所以这里校验的是「用户选的**展示**时区是不是一个真实存在的 IANA 时区」，
 * 而<b>不是</b>「入库前把时间换算成本地时间」。
 */
public final class AccountLocaleRules {

  private AccountLocaleRules() {}

  /** 本模块占用的偏好页面 key（与 1.4.1.2 共用 {@code user_preference} 表，key 各不相同）。 */
  public static final String PAGE_KEY = "account.locale";

  /**
   * 平台默认展示时区。
   *
   * <p><b>为什么是 {@code Asia/Shanghai}</b>：工作台首页现在就是按 {@code Asia/Shanghai}
   * 渲染日期的（本模块不改变既有渲染）。默认值取它 ⇒ <b>没设置过偏好的用户看到的渲染与今天一模一样</b>；
   * 取别的值就等于让所有存量用户第一次进页面就发现日期变了。
   */
  public static final String DEFAULT_TIMEZONE = "Asia/Shanghai";

  /** 平台默认界面语言。 */
  public static final String DEFAULT_LOCALE = "zh-CN";

  /**
   * 当前**真正可用**的界面语言。
   *
   * <p><b>现在只有 {@code zh-CN}</b>：本仓没有 i18n 运行时（没有语言上下文、没有语言包），
   * 全站文案是硬编码中文 —— 在没有语言包的情况下声称支持英文，就是那个被明令禁止的「假开关」。
   * i18n 运行时与语言包交付后，往这个列表里加一项即可。
   */
  public static final List<String> SUPPORTED_LOCALES = List.of("zh-CN");

  /**
   * 规划中的界面语言（<b>尚未交付</b>）。
   *
   * <p>列出来是为了让「为什么现在不能切英文」有具体答案，而不是一句「暂不支持」。
   */
  public static final List<String> PLANNED_LOCALES = List.of("zh-CN", "en-US");

  /** 语言标签形状：BCP-47 的一个子集（语言[-脚本][-地区]）。 */
  private static final Pattern LOCALE_TAG =
      Pattern.compile("^[a-z]{2,3}(-[A-Z][a-z]{3})?(-([A-Z]{2}|[0-9]{3}))?$");

  /** JDK 自带的 tzdb 里全部 IANA 时区 id。 */
  private static final Set<String> IANA_ZONES = ZoneId.getAvailableZoneIds();

  /**
   * 时间显示口径（{@code Intl.DateTimeFormat} 的选项集）。
   *
   * <p>口径取自 {@code packages/ui/src/lib/world-clock.ts} 已经在用的写法
   * （{@code hourCycle: 'h23'} + {@code timeZoneName: 'shortOffset'}），
   * <b>不另起一套</b>。{@code timeZone} 不在这里 —— 它是每个用户自己的选择。
   */
  public static final AccountLocaleDisplayConvention DISPLAY_CONVENTION =
      new AccountLocaleDisplayConvention(DEFAULT_LOCALE, displayOptions());

  private static Map<String, Object> displayOptions() {
    Map<String, Object> options = new LinkedHashMap<>();
    options.put("year", "numeric");
    options.put("month", "2-digit");
    options.put("day", "2-digit");
    options.put("hour", "2-digit");
    options.put("minute", "2-digit");
    options.put("second", "2-digit");
    options.put("hourCycle", "h23");
    options.put("timeZoneName", "shortOffset");
    return Collections.unmodifiableMap(options);
  }

  /**
   * 这个时区是不是一个真实存在的 IANA 时区。
   *
   * <p><b>为什么不能用 {@code ZoneId.of()} 的宽松语义</b>：它接受 {@code UTC+8}、
   * {@code GMT+8:00} 这类<b>偏移量</b>写法，而偏移量没有夏令时规则 ——
   * 用户选了它，跨夏令时的那一天显示的时间就会比真实当地时间差一小时。
   * 所以这里只认 tzdb 里的**区域 id**（{@code Asia/Shanghai}），
   * 偏移量写法一律拒掉。
   */
  public static boolean isKnownTimezone(String id) {
    return id != null && IANA_ZONES.contains(id);
  }

  /** 校验并归一化展示时区；不合法一律 400（不是静默改成默认 —— 静默改会让用户以为自己改成功了）。 */
  public static String normalizeTimezone(String raw) {
    String zone = raw == null ? "" : raw.trim();
    if (zone.isEmpty()) {
      throw badRequest("展示时区不能为空（请选一个 IANA 时区，如 Asia/Shanghai）");
    }
    if (!isKnownTimezone(zone)) {
      throw badRequest("不是有效的 IANA 时区：" + zone + "（例：Asia/Shanghai；不收 UTC+8 这类偏移量写法）");
    }
    return zone;
  }

  /** 语言标签形状是否合法（不管它当前有没有语言包）。 */
  public static boolean isLocaleTag(String raw) {
    return raw != null && LOCALE_TAG.matcher(raw.trim()).matches();
  }

  /**
   * 校验并归一化界面语言。
   *
   * <p>形状合法<b>且</b>当前有语言包才放行：只合法但没有语言包的（比如 {@code en-US}）
   * 会让界面出现「选了就变英文」却什么都没变的那种假开关，所以这里直接 400 并说清原因。
   */
  public static String normalizeLocale(String raw) {
    String tag = raw == null ? "" : raw.trim();
    if (tag.isEmpty()) {
      throw badRequest("界面语言不能为空");
    }
    if (!isLocaleTag(tag)) {
      throw badRequest("不是合法的语言标签：" + tag + "（例：zh-CN、en-US）");
    }
    if (!SUPPORTED_LOCALES.contains(tag)) {
      throw badRequest(
          "界面语言 "
              + tag
              + " 当前不可用：本仓还没有 i18n 运行时与对应语言包（当前可用："
              + String.join(" / ", SUPPORTED_LOCALES)
              + "）");
    }
    return tag;
  }

  /** 回落时用的人话原因（供 {@code fallbackReason} 用）。 */
  public static String localeFallbackReason(String stored) {
    return "记录的界面语言 "
        + stored
        + " 当前不可用（本仓还没有 i18n 运行时与对应语言包），已回落到 "
        + DEFAULT_LOCALE;
  }

  /** 回落时用的人话原因。 */
  public static String timezoneFallbackReason(String stored) {
    return "记录的展示时区 " + stored + " 不是有效的 IANA 时区，已回落到 " + DEFAULT_TIMEZONE;
  }

  /** 本模块的 400：带一句能直接显示在界面上的原因（见 {@link AccountLocaleException}）。 */
  private static AccountLocaleException badRequest(String message) {
    return new AccountLocaleException(message);
  }
}
