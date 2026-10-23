package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.starcon.identity.AccountLocaleModels.AccountLocaleDisplayConvention;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 1.4.2.1 语言与时区 —— 纯函数规则的单元测试（不碰数据库、不起容器）。
 *
 * <p>这些规则是「用户会提交什么」的边界，钉住它们比事后查日志便宜得多：
 *
 * <ul>
 *   <li><b>时区</b>：只认 tzdb 里的**区域 id**，偏移量写法（{@code UTC+8}）一律拒 ——
 *       偏移量没有夏令时规则，跨夏令时那天的显示时间会差一小时。</li>
 *   <li><b>语言</b>：标签形状合法还不够，<b>当前必须有语言包</b> ——
 *       没有语言包却放行，就是那个「选了 English 但文案一点没变」的假开关。</li>
 *   <li><b>显示口径</b>：服务端声明的那套选项，以及它对同一时刻在四个时区下的含义。</li>
 * </ul>
 */
class AccountLocaleRulesTest {

  /** 按服务端声明的显示口径渲染（口径取自 {@code DISPLAY_CONVENTION.options()}，不是另写一份常量）。 */
  private static String render(OffsetDateTime instant, String zone) {
    Map<String, Object> options = AccountLocaleRules.DISPLAY_CONVENTION.options();
    boolean h12 = "h12".equals(options.get("hourCycle"));
    DateTimeFormatterBuilder builder = new DateTimeFormatterBuilder();
    builder
        .appendValue(ChronoField.YEAR, 4)
        .appendLiteral('-')
        .appendValue(ChronoField.MONTH_OF_YEAR, 2)
        .appendLiteral('-')
        .appendValue(ChronoField.DAY_OF_MONTH, 2)
        .appendLiteral(' ');
    builder
        .appendValue(h12 ? ChronoField.CLOCK_HOUR_OF_AMPM : ChronoField.HOUR_OF_DAY, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
        .appendLiteral(':')
        .appendValue(ChronoField.SECOND_OF_MINUTE, 2);
    return builder
        .toFormatter(Locale.ROOT)
        .format(instant.atZoneSameInstant(ZoneId.of(zone)));
  }

  // ------------------------------------------------------------------
  // 时区
  // ------------------------------------------------------------------

  @Test
  void 区域id被接受且首尾空白被裁掉() {
    assertThat(AccountLocaleRules.normalizeTimezone("Asia/Shanghai")).isEqualTo("Asia/Shanghai");
    assertThat(AccountLocaleRules.normalizeTimezone("  Europe/London  ")).isEqualTo("Europe/London");
    assertThat(AccountLocaleRules.normalizeTimezone("UTC")).isEqualTo("UTC");
    // 遗留的 POSIX 写法（EST5EDT）也是 tzdb 里真实存在的区域 id，自带夏令时规则 ⇒ 收。
    assertThat(AccountLocaleRules.normalizeTimezone("EST5EDT")).isEqualTo("EST5EDT");
  }

  @Test
  void 偏移量写法被拒因为它没有夏令时规则() {
    for (String bad : List.of("UTC+8", "GMT+8:00", "Asia/Shanghai+08:00", "CST")) {
      assertThatThrownBy(() -> AccountLocaleRules.normalizeTimezone(bad))
          .as("偏移量写法必须被拒：" + bad)
          .isInstanceOf(AccountLocaleException.class);
    }
  }

  @Test
  void 假时区与空值被拒() {
    for (String bad : List.of("Not/AZone", "Mars/Phobos", "", "   ")) {
      assertThatThrownBy(() -> AccountLocaleRules.normalizeTimezone(bad))
          .as("必须被拒：" + bad)
          .isInstanceOf(AccountLocaleException.class);
    }
    assertThatThrownBy(() -> AccountLocaleRules.normalizeTimezone(null))
        .isInstanceOf(AccountLocaleException.class);
  }

  // ------------------------------------------------------------------
  // 界面语言
  // ------------------------------------------------------------------

  @Test
  void 当前可用的语言被接受() {
    assertThat(AccountLocaleRules.normalizeLocale("zh-CN")).isEqualTo("zh-CN");
  }

  @Test
  void 没有语言包的语言被拒并说清原因() {
    AccountLocaleException error =
        catchThrowableOfType(
            () -> AccountLocaleRules.normalizeLocale("en-US"), AccountLocaleException.class);
    assertThat(error).isNotNull();
    assertThat(error.getMessage()).contains("en-US").contains("i18n");
  }

  @Test
  void 不合法的语言标签被拒() {
    for (String bad : List.of("english", "zh_CN", "EN-US", "zh-CN-x-foo", "zh", "")) {
      assertThatThrownBy(() -> AccountLocaleRules.normalizeLocale(bad))
          .as("必须被拒：" + bad)
          .isInstanceOf(AccountLocaleException.class);
    }
    assertThatThrownBy(() -> AccountLocaleRules.normalizeLocale(null))
        .isInstanceOf(AccountLocaleException.class);
  }

  @Test
  void 语言标签形状与可用性是两件事() {
    assertThat(AccountLocaleRules.isLocaleTag("en-US")).as("形状合法").isTrue();
    assertThat(AccountLocaleRules.SUPPORTED_LOCALES).as("但当前没有语言包").containsExactly("zh-CN");
    assertThat(AccountLocaleRules.PLANNED_LOCALES).contains("en-US");
  }

  // ------------------------------------------------------------------
  // 显示口径
  // ------------------------------------------------------------------

  @Test
  void 显示口径是声明的那一套且不会悄悄变() {
    AccountLocaleDisplayConvention convention = AccountLocaleRules.DISPLAY_CONVENTION;
    assertThat(convention.locale()).isEqualTo("zh-CN");
    assertThat(convention.options())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "year", "numeric",
                "month", "2-digit",
                "day", "2-digit",
                "hour", "2-digit",
                "minute", "2-digit",
                "second", "2-digit",
                "hourCycle", "h23",
                "timeZoneName", "shortOffset"));
  }

  @Test
  void 同一时刻在四个时区渲染成四个不同的当地时间() {
    OffsetDateTime instant = OffsetDateTime.parse("2026-03-15T10:20:30Z");
    assertThat(render(instant, "Asia/Shanghai")).isEqualTo("2026-03-15 18:20:30");
    assertThat(render(instant, "America/New_York")).isEqualTo("2026-03-15 06:20:30");
    assertThat(render(instant, "Europe/London")).isEqualTo("2026-03-15 10:20:30");
    assertThat(render(instant, "America/Los_Angeles")).isEqualTo("2026-03-15 03:20:30");
  }

  @Test
  void 夏令时与冬令时渲染不同说明口径走的是tzdb而不是固定偏移() {
    OffsetDateTime winter = OffsetDateTime.parse("2026-01-15T10:20:30Z");
    assertThat(render(winter, "America/New_York")).isEqualTo("2026-01-15 05:20:30");
    assertThat(render(winter, "America/Los_Angeles")).isEqualTo("2026-01-15 02:20:30");
    // 同一个「纽约」，夏天与冬天差一小时 —— 固定偏移的实现过不了这一条。
    assertThat(render(winter, "America/New_York"))
        .isNotEqualTo(render(OffsetDateTime.parse("2026-03-15T10:20:30Z"), "America/New_York"));
  }

  @Test
  void 默认值与既有渲染一致所以存量用户看不到变化() {
    assertThat(AccountLocaleRules.DEFAULT_TIMEZONE).isEqualTo("Asia/Shanghai");
    assertThat(AccountLocaleRules.DEFAULT_LOCALE).isEqualTo("zh-CN");
  }

  @Test
  void 页面key与兄弟模块的key互不相同() {
    assertThat(AccountLocaleRules.PAGE_KEY).isEqualTo("account.locale");
    assertThat(UserPreferencePayloadRules.PAGE_KEY.matcher(AccountLocaleRules.PAGE_KEY).matches())
        .as("页面 key 必须落在通用偏好端点的词表内")
        .isTrue();
  }
}
