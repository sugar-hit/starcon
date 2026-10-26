package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 1.3.1.2 通知偏好 —— identity 侧的「接收渠道 + 免打扰时段」形状。
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
 * <p><b>本模块不建表</b>：偏好落在 1.4.1.2 建好的 {@code user_preference} 上，
 * 页面 key 是 {@code notifications.delivery}（同一张表、同一套载荷形状校验、同一套 jOOQ 显式 SQL），
 * 与 1.4.2.1「语言与时区」共用那张表的道理完全一样。
 *
 * <p>🔴 <b>契约里刻意不用裸 {@code Map}</b>：springdoc 对 {@code Map<String, List<String>>}
 * 只会生成一个空的 {@code {}}，前端拿到的是 {@code unknown} 而不是结构化数据
 * （11.3.1.2 已经踩过一次这个坑）。所以「按类型的渠道集合」用**列表桶**表达。
 */
public final class NotificationPreferenceModels {

  private NotificationPreferenceModels() {}

  /**
   * 一个接收渠道（**受控词表**，不是自由文本）。
   *
   * @param code 渠道码
   * @param label 中文名
   * @param note 这个渠道实际会发生什么（前端直接显示，避免「IM 是什么意思」）
   */
  public record NotificationPreferenceChannel(String code, String label, String note) {}

  /**
   * 一个通知类型的偏好规则（**类型词表来自 1.3.1.1**，本模块只补「是不是底线项」）。
   *
   * @param code 类型码
   * @param label 中文名
   * @param mandatory 是不是关键业务通知（不允许被完全关闭）
   * @param baselineChannel 强制保留的底线渠道（{@code null} = 不是底线类型）
   * @param rule 人话规则说明（前端照着显示，不要自己编）
   */
  public record NotificationPreferenceTypeRule(
      String code,
      String label,
      boolean mandatory,
      @Schema(nullable = true) String baselineChannel,
      String rule) {}

  /**
   * 某个通知类型当前**生效**的渠道集合。
   *
   * @param type 类型码
   * @param label 中文名
   * @param channels 生效的渠道码集合（**永远不为空**）
   * @param mandatory 是不是关键业务通知
   * @param baselineChannel 这一类被强制保留的底线渠道（{@code null} = 没有）
   */
  public record NotificationPreferenceTypeSetting(
      String type,
      String label,
      List<String> channels,
      boolean mandatory,
      @Schema(nullable = true) String baselineChannel) {}

  /**
   * 我的通知偏好（**生效值**）。
   *
   * @param stored 服务端是否真的存过这一页（false = 从没保存过，用的是默认值）
   * @param types 按词表顺序的每一类通知（**永远五类齐全**，不存在的类型也列出并给生效值）
   * @param windows 免打扰时段（当地时间 {@code HH:mm-HH:mm}；空 = 不免打扰）
   * @param timezone 免打扰时段用的时区（IANA id）
   * @param timezoneSource 时区从哪来：{@code PREFERENCE} = 我自己在这里设的；
   *     {@code ACCOUNT_LOCALE} = 跟 1.4.2.1 的展示时区一致；{@code DEFAULT} = 平台默认
   * @param defaultTimezone 平台默认时区
   * @param quietHoursScope 免打扰时段的作用范围（{@code EXTERNAL_CHANNELS} = 只压制外部渠道）
   * @param updatedAt 最近一次保存时刻；{@code stored=false} 时为 null
   * @param fallbackReasons 发生了回落/兜底时的原因（没有就是空列表）
   */
  public record NotificationPreferenceView(
      boolean stored,
      List<NotificationPreferenceTypeSetting> types,
      List<String> windows,
      String timezone,
      String timezoneSource,
      String defaultTimezone,
      String quietHoursScope,
      @Schema(nullable = true) OffsetDateTime updatedAt,
      List<String> fallbackReasons) {}

  /**
   * 保存入参：**没给的字段保持原值**（与 1.4.2.1 同一套约定）。
   *
   * <p>刻意用**列表桶**而不是 {@code Map}：裸 Map 经 springdoc 只生成空的 {@code {}}，
   * 前端拿到的是 {@code unknown}（11.3.1.2 踩过一次）。契约里必须是结构化的。
   *
   * @param types 要改的类型渠道集合（**没提到的类型保持原值**；{@code null} = 整体不动）
   * @param windows 免打扰时段（{@code null} = 保持原值；<b>空列表 = 清空</b>）
   * @param timezone 免打扰时段用的时区（{@code null} = 保持原值）
   */
  public record NotificationPreferenceInput(
      @Schema(nullable = true) List<NotificationPreferenceChannelSetting> types,
      @Schema(nullable = true) List<String> windows,
      @Schema(nullable = true) String timezone) {}

  /**
   * 「某一类通知的渠道集合」这一桶。
   *
   * @param type 通知类型码（受控词表）
   * @param channels 该类型的接收渠道集合（**不允许为空**）
   */
  public record NotificationPreferenceChannelSetting(String type, List<String> channels) {}

  /**
   * 解析结果：某一类通知**现在**该走哪些渠道。
   *
   * <p>这是给下游 1.3.2.2（外部渠道推送）用的**过滤依据**：
   * 「这条通知要不要发？走哪几个渠道？现在的免打扰时段压不压制它？」
   *
   * @param type 类型码
   * @param label 中文名
   * @param channels 生效的渠道集合（**永远不为空**）
   * @param suppressedChannels 被当前免打扰时段压制的渠道（外部渠道；站内渠道永不被压制）
   * @param quietNow 现在是不是落在免打扰时段里
   * @param quietWindows 命中的免打扰时段（没命中就是空列表）
   * @param timezone 时区
   * @param mandatory 是不是关键业务通知
   */
  public record NotificationPreferenceResolution(
      String type,
      String label,
      List<String> channels,
      List<String> suppressedChannels,
      boolean quietNow,
      List<String> quietWindows,
      String timezone,
      boolean mandatory) {}

  /** 解析入参：要解析哪一个通知类型。 */
  public record NotificationPreferenceResolveRequest(String type) {}

  /**
   * 能力声明。
   *
   * @param module 模块号
   * @param channels 渠道词表
   * @param types 类型词表 + 哪些是**不可关闭的底线项**
   * @param baselineChannel 底线渠道码
   * @param maxWindows 免打扰时段条数上限
   * @param defaultTimezone 平台默认时区
   * @param quietHoursScope 免打扰时段的作用范围
   * @param boundaries **未交付**的相邻模块与语义边界（前端据此说明「这里没有」而不是假装有）
   */
  public record NotificationPreferenceCapabilities(
      String module,
      List<NotificationPreferenceChannel> channels,
      List<NotificationPreferenceTypeRule> types,
      String baselineChannel,
      int maxWindows,
      String defaultTimezone,
      String quietHoursScope,
      List<String> boundaries) {}
}
