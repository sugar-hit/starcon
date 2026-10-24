package com.starcon.identity;

import static org.assertj.core.api.Assertions.*;

import com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceChannel;
import com.starcon.identity.NotificationPreferenceModels.NotificationPreferenceTypeRule;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 1.3.1.2 通知偏好 —— 纯函数规则的单元测试。
 *
 * <p>本类**不碰数据库、不碰 HTTP**：只钉住规则本身
 * （渠道词表、底线项不可关闭、渠道集合非空、时段形状、跨午夜判定）。
 *
 * <p>这些规则是最容易被「后来人改坏」的地方：改坏了不会立刻报错，
 * 只会表现为「用户明明关了某个渠道，却还在收邮件」——
 * 所以逐条钉住。
 */
class NotificationPreferenceRulesTest {

  // ================================================================ 渠道词表

  @Test
  void 渠道词表是站内邮件与企业IM三个() {
    assertThat(NotificationPreferenceRules.CHANNELS)
        .extracting(NotificationPreferenceChannel::code)
        .containsExactly("IN_APP", "EMAIL", "IM");
    assertThat(NotificationPreferenceRules.CHANNELS)
        .extracting(NotificationPreferenceChannel::label)
        .containsExactly("站内", "邮件", "企业 IM");
  }

  @Test
  void 底线类型就是蓝图点名的超期发货与账务差异() {
    assertThat(NotificationPreferenceRules.MANDATORY_TYPES)
        .containsExactly("TIMELINESS_OVERDUE", "RECONCILIATION_DIFF");
    assertThat(NotificationPreferenceRules.isMandatoryType("TIMELINESS_OVERDUE")).isTrue();
    assertThat(NotificationPreferenceRules.isMandatoryType("RECONCILIATION_DIFF")).isTrue();
    assertThat(NotificationPreferenceRules.isMandatoryType("MESSAGE")).isFalse();
    assertThat(NotificationPreferenceRules.isMandatoryType("STOCKOUT")).isFalse();
    assertThat(NotificationPreferenceRules.isMandatoryType("SYNC_FAILURE")).isFalse();
  }

  @Test
  void 底线渠道是站内() {
    assertThat(NotificationPreferenceRules.BASELINE_CHANNEL).isEqualTo("IN_APP");
    assertThat(NotificationPreferenceRules.isExternalChannel("IN_APP")).isFalse();
    assertThat(NotificationPreferenceRules.isExternalChannel("EMAIL")).isTrue();
    assertThat(NotificationPreferenceRules.isExternalChannel("IM")).isTrue();
  }

  @Test
  void 类型规则表覆盖全部五类且标出底线项() {
    List<NotificationPreferenceTypeRule> rules = NotificationPreferenceRules.typeRules();
    assertThat(rules).hasSize(5);
    assertThat(rules)
        .filteredOn(NotificationPreferenceTypeRule::mandatory)
        .extracting(NotificationPreferenceTypeRule::code)
        .containsExactly("TIMELINESS_OVERDUE", "RECONCILIATION_DIFF");
    assertThat(rules)
        .filteredOn(NotificationPreferenceTypeRule::mandatory)
        .extracting(NotificationPreferenceTypeRule::baselineChannel)
        .containsOnly("IN_APP");
  }

  // ================================================================ 渠道归一

  @Test
  void 渠道集合去重并按词表顺序排列() {
    assertThat(NotificationPreferenceRules.normalizeChannels("MESSAGE", List.of("IM", "IN_APP", "IM")))
        .containsExactly("IN_APP", "IM");
  }

  @Test
  void 渠道集合不允许为空() {
    assertThatThrownBy(() -> NotificationPreferenceRules.normalizeChannels("MESSAGE", List.of()))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("接收渠道集合不能为空");
    assertThatThrownBy(() -> NotificationPreferenceRules.normalizeChannels("MESSAGE", null))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 词表外的渠道被拒() {
    assertThatThrownBy(() -> NotificationPreferenceRules.normalizeChannels("MESSAGE", List.of("SMS")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不在受控词表里");
    assertThatThrownBy(() -> NotificationPreferenceRules.normalizeChannels("MESSAGE", List.of("everything")))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 关键业务通知的底线渠道不可关闭() {
    assertThatThrownBy(
            () -> NotificationPreferenceRules.normalizeChannels("TIMELINESS_OVERDUE", List.of("EMAIL")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不允许被完全关闭");
    assertThatThrownBy(
            () -> NotificationPreferenceRules.normalizeChannels("RECONCILIATION_DIFF", List.of("IM")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不允许被完全关闭");
  }

  @Test
  void 关键业务通知可以再加别的渠道() {
    assertThat(
            NotificationPreferenceRules.normalizeChannels(
                "TIMELINESS_OVERDUE", List.of("EMAIL", "IN_APP")))
        .containsExactly("IN_APP", "EMAIL");
  }

  @Test
  void 非底线类型可以只留一个渠道() {
    assertThat(NotificationPreferenceRules.normalizeChannels("MESSAGE", List.of("EMAIL")))
        .containsExactly("EMAIL");
  }

  // ================================================================ 免打扰时段

  @Test
  void 时段形状不对被拒() {
    for (String bad :
        List.of("22:00", "22:00-8:00", "24:00-08:00", "22:60-08:00", "22:00-08:00-09:00", "晚上十点到八点")) {
      assertThatThrownBy(() -> NotificationPreferenceRules.normalizeWindows(List.of(bad)))
          .as("时段 " + bad + " 必须被拒")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  @Test
  void 起止相同的时段被拒() {
    assertThatThrownBy(() -> NotificationPreferenceRules.normalizeWindows(List.of("08:00-08:00")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("起止时刻不能相同");
  }

  @Test
  void 时段重复被拒() {
    assertThatThrownBy(() -> NotificationPreferenceRules.normalizeWindows(List.of("22:00-08:00", "22:00-08:00")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重复");
  }

  @Test
  void 超过上限的时段被拒() {
    List<String> tooMany =
        List.of("01:00-02:00", "03:00-04:00", "05:00-06:00", "07:00-08:00", "09:00-10:00");
    assertThatThrownBy(() -> NotificationPreferenceRules.normalizeWindows(tooMany))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("最多");
  }

  @Test
  void 时段按开始时刻排序() {
    assertThat(
            NotificationPreferenceRules.normalizeWindows(
                List.of("22:00-08:00", "09:00-12:00", "13:00-14:00")))
        .containsExactly("09:00-12:00", "13:00-14:00", "22:00-08:00");
  }

  @Test
  void 空时段列表是合法的即不免打扰() {
    assertThat(NotificationPreferenceRules.normalizeWindows(List.of())).isEmpty();
    assertThat(NotificationPreferenceRules.normalizeWindows(null)).isEmpty();
  }

  // ================================================================ 免打扰判定（当地时刻语义）

  @Test
  void 命中时段按当地时间判定() {
    // 2026-03-01T12:00Z 在 Asia/Shanghai 是当地 20:00
    Instant noon = Instant.parse("2026-03-01T12:00:00Z");
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("19:00-21:00"), "Asia/Shanghai", noon))
        .containsExactly("19:00-21:00");
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("09:00-12:00"), "Asia/Shanghai", noon))
        .isEmpty();
  }

  @Test
  void 跨午夜的时段命中前半夜与后半夜() {
    // 当地 23:30（上海）⇒ 命中 22:00-08:00
    Instant late = Instant.parse("2026-03-01T15:30:00Z");
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("22:00-08:00"), "Asia/Shanghai", late))
        .containsExactly("22:00-08:00");
    // 当地 02:30（上海）⇒ 同样命中
    Instant early = Instant.parse("2026-03-01T18:30:00Z");
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("22:00-08:00"), "Asia/Shanghai", early))
        .containsExactly("22:00-08:00");
  }

  @Test
  void 同一时刻在不同时区下判定不同() {
    // 2026-03-01T23:30Z：上海是当地 07:30（命中 22:00-08:00），纽约是当地 18:30（不命中）
    Instant moment = Instant.parse("2026-03-01T23:30:00Z");
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("22:00-08:00"), "Asia/Shanghai", moment))
        .containsExactly("22:00-08:00");
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("22:00-08:00"), "America/New_York", moment))
        .isEmpty();
  }

  @Test
  void 时段边界是左闭右开() {
    Instant atStart = Instant.parse("2026-03-01T11:00:00Z"); // 上海当地 19:00
    Instant atEnd = Instant.parse("2026-03-01T13:00:00Z"); // 上海当地 21:00
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("19:00-21:00"), "Asia/Shanghai", atStart))
        .containsExactly("19:00-21:00");
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("19:00-21:00"), "Asia/Shanghai", atEnd))
        .isEmpty();
  }

  @Test
  void 时区非法时判定不了就当没命中而不是抛错() {
    assertThat(NotificationPreferenceRules.quietWindowsAt(List.of("22:00-08:00"), "Not/AZone", Instant.now()))
        .isEmpty();
  }

  @Test
  void 时段开始时刻可排序() {
    assertThat(NotificationPreferenceRules.startOf("22:00-08:00")).isEqualTo(LocalTime.of(22, 0));
    assertThat(NotificationPreferenceRules.startOf("08:00-22:00")).isEqualTo(LocalTime.of(8, 0));
  }

  @Test
  void 页面key与其它模块各占一个() {
    assertThat(NotificationPreferenceRules.PAGE_KEY).isEqualTo("notifications.delivery");
    assertThat(NotificationPreferenceRules.PAGE_KEY)
        .as("页面 key 必须落在 user_preference 的词表里")
        .matches("^[a-z0-9][a-z0-9._-]{0,159}$");
    assertThat(NotificationPreferenceRules.PAGE_KEY)
        .as("不能与兄弟模块的页面 key 撞车")
        .isNotEqualTo(AccountLocaleRules.PAGE_KEY);
  }

  @Test
  void 类型词表与通知列表那一模块逐字一致() {
    assertThat(NotificationPreferenceRules.typeRules())
        .extracting(NotificationPreferenceTypeRule::code)
        .containsExactlyElementsOf(NotificationInboxRules.typeCodes());
    assertThat(ZoneId.of("Asia/Shanghai")).isNotNull();
  }
}
