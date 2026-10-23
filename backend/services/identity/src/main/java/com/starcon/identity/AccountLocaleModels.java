package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 1.4.2.1 语言与时区 —— identity 侧的形状。
 *
 * <p>蓝图原文：「界面语言（中文/英文等）与展示时区，影响时间显示与日期分组，**不改变存储时区**。
 * 输入：用户选择；平台站点时区清单。输出：界面语言包与时间显示口径。」
 *
 * <p><b>本模块不建表</b>：偏好落在 1.4.1.2 建好的 {@code user_preference} 上，
 * 页面 key 是 {@code account.locale}（同一张表、同一套载荷形状校验、同一套 jOOQ 显式 SQL）。
 *
 * <p><b>为什么「时间显示口径」由服务端声明</b>：口径若散落在各页面的
 * {@code Intl.DateTimeFormat(...)} 调用里，全站会出现两套写法、两种偏移标签。
 * 所以服务端声明一份 {@link AccountLocaleDisplayConvention}（就是 {@code Intl.DateTimeFormat} 的选项集），
 * 前端**原样**传给 {@code Intl}，不在前端另写一份常量 —— 两边各写一份必然漂移。
 */
public final class AccountLocaleModels {

  private AccountLocaleModels() {}

  /**
   * 我当前生效的界面语言与展示时区。
   *
   * @param stored 服务端是否真的存过这一页（false = 从没保存过，用的是默认值）
   * @param locale 当前**生效**的界面语言（不可用时回落到默认，并写进 {@code fallbackReason}）
   * @param timezone 当前**生效**的展示时区（同上）
   * @param defaultLocale 平台默认界面语言
   * @param defaultTimezone 平台默认展示时区
   * @param updatedAt 最近一次保存时刻；{@code stored=false} 时为 null
   * @param fallbackReason 发生了回落时的原因（没有回落则为 null）
   */
  public record AccountLocaleView(
      boolean stored,
      String locale,
      String timezone,
      String defaultLocale,
      String defaultTimezone,
      @Schema(nullable = true) OffsetDateTime updatedAt,
      @Schema(nullable = true) String fallbackReason) {}

  /** 保存入参：两个字段都可以只给一个（没给的保持原值）。 */
  public record AccountLocaleInput(
      @Schema(nullable = true) String locale, @Schema(nullable = true) String timezone) {}

  /**
   * 时间显示口径 —— {@code Intl.DateTimeFormat} 的选项集。
   *
   * <p><b>名字带模块前缀 {@code AccountLocale} 的理由同 {@link AccountLocaleCapabilityDimension}</b>：
   * 契约跨服务扁平导出，同名类型字段不同时会互相覆盖。{@code DisplayConvention} 这种通用名
   * 早晚会被别的模块抢走（数字格式、金额格式都可能叫它），所以这里一开始就带前缀。
   *
   * @param locale 渲染用的语言标签（当前只有 zh-CN 可用）
   * @param options {@code Intl.DateTimeFormat} 的选项（不含 {@code timeZone}，那是每个用户自己的）
   */
  public record AccountLocaleDisplayConvention(String locale, Map<String, Object> options) {}

  /**
   * 能力声明的一个维度：能做 / 不能做，缺什么，归属谁。
   *
   * <p><b>名字带模块前缀 {@code AccountLocale} 是必须的</b>，不是风格偏好：
   * 契约是<b>跨服务扁平导出</b>的（{@code tools/codegen/catalog-contracts.py}），
   * 同名类型在两个服务里字段不同时会互相覆盖 —— codegen 的闸会直接中止。
   * channel 服务已经有两处叫 {@code CapabilityDimension} 的记录
   * （{@code LogisticsExceptionModels} / {@code ReturnDispositionModels}），
   * 所以这里叫 {@code AccountLocaleCapabilityDimension}。
   * 这也是本仓既有惯例（{@code SaasCapabilityDimension} /
   * {@code UsageCapabilityDimension} / {@code ListingStatusCapabilityDimension} …）。
   */
  public record AccountLocaleCapabilityDimension(
      String key,
      boolean available,
      @Schema(nullable = true) String ownerModule,
      String summary,
      List<String> missing) {}

  /**
   * 本模块的能力声明。
   *
   * <p><b>逐条如实报</b>：做不到的维度写 {@code available=false} 并写清缺什么、归谁，
   * 绝不报一个「看起来能用」的假开关。
   */
  public record AccountLocaleCapabilities(
      String module,
      List<AccountLocaleCapabilityDimension> dimensions,
      List<String> supportedLocales,
      List<String> plannedLocales,
      String defaultLocale,
      String defaultTimezone,
      AccountLocaleDisplayConvention displayConvention,
      List<String> boundaries) {}
}
