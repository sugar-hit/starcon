package com.starcon.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 1.3.2.1 预警规则 —— identity 侧的形状。
 *
 * <p>🔴 <b>阈值与观测值在契约里是十进制<b>字符串</b>，不是 number。</b>
 *
 * <p>本仓其它模块把 {@code BigDecimal} 直接放进契约（渲染成 {@code number}）。
 * 本模块<b>刻意不这么做</b>：阈值是「低 14 天」「跌超 20%」这种要给人看、要给人改的数，
 * 一旦渲染成 JS number，{@code 0.1 + 0.2 = 0.30000000000000004} 那类误差就会
 * 从「展示」一路漂到「存回去的值」，而阈值漂一点就意味着告警线漂了。
 * 所以：<b>线上永远是十进制文本</b>，Java 侧 {@code BigDecimal}，库里 {@code NUMERIC(19,6)}，
 * 前端拿到的就是字符串，<b>全程不碰浮点</b>。
 */
public final class AlertRuleModels {

  private AlertRuleModels() {}

  /** 受控指标词表里的一条。 */
  public record AlertRuleMetric(
      String code,
      String displayName,
      String unit,
      String unitLabel,
      String ownerSystem,
      String ownerModule,
      boolean available,
      @Schema(nullable = true) String unavailableReason) {}

  /**
   * 受控词表（全部在数据层钉死，服务端另拒一次）。
   *
   * @param metrics 指标码词表
   * @param objectTypes 对象类型词表
   * @param timeWindows 时间窗词表
   * @param comparisons 比较方向词表
   * @param changeBases 比较基准词表
   * @param objectScopes 对象范围词表
   */
  public record AlertRuleVocabulary(
      List<AlertRuleMetric> metrics,
      List<String> objectTypes,
      List<String> timeWindows,
      List<String> comparisons,
      List<String> changeBases,
      List<String> objectScopes) {}

  /** 一条预置规则（系统事实，来自代码里的目录，不落库）。 */
  public record AlertRulePreset(
      String ruleCode,
      String displayName,
      String description,
      String objectType,
      String metricCode,
      String threshold,
      String unit,
      String unitLabel,
      String timeWindow,
      String comparison,
      String changeBasis,
      String objectScope,
      String defaultTimezone,
      String upstreamSystem,
      String upstreamModule,
      String upstreamEndpoint,
      boolean metricAvailable,
      @Schema(nullable = true) String metricUnavailableReason) {}

  /**
   * 一条<b>生效</b>的规则（= 预置默认值 ⨯ 租户覆盖，或租户自建）。
   *
   * @param id 租户覆盖行 / 自定义行的 id（预置规则**没被改过**时没有行 ⇒ id 为 null）
   * @param ruleCode 规则码
   * @param origin PRESET（预置，可能已被本租户改过）/ CUSTOM（自建）
   * @param customized 是否被本租户改过（改了参数或关了）
   * @param enabled 是否生效（关掉后为 false）
   * @param timezoneSource 时区来源：PRESET_DEFAULT / EXPLICIT / ACCOUNT_LOCALE
   * @param upstreamSystem 上游系统（预置规则有；自建规则为 null）
   * @param upstreamModule 上游模块（预置规则有；自建规则为 null）
   */
  public record AlertRuleEffective(
      @Schema(nullable = true) UUID id,
      String ruleCode,
      String origin,
      String displayName,
      @Schema(nullable = true) String description,
      String objectType,
      String metricCode,
      String threshold,
      String unit,
      String unitLabel,
      String timeWindow,
      String comparison,
      String changeBasis,
      String objectScope,
      @Schema(nullable = true) String objectRef,
      String timezone,
      boolean enabled,
      boolean customized,
      String timezoneSource,
      @Schema(nullable = true) String upstreamSystem,
      @Schema(nullable = true) String upstreamModule,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /**
   * 生效规则清单。
   *
   * @param rules 生效规则（预置在前，自定义在后）
   * @param presetCount 预置规则条数
   * @param customCount 自定义规则条数
   * @param customizedCount 被本租户改过的条数（含改参数与关闭）
   * @param disabledCount 已关闭的条数
   */
  public record AlertRuleList(
      List<AlertRuleEffective> rules,
      int presetCount,
      int customCount,
      int customizedCount,
      int disabledCount) {}

  /**
   * 建 / 改规则的入参。
   *
   * <p>🔴 <b>刻意不接受 tenantId / userId / membershipId</b>：
   * 租户来自已认证的成员关系，请求里带 tenantId 就是把隔离边界交给客户端了。
   *
   * <p><b>阈值为十进制文本</b>（见本类的类注释）。
   *
   * @param threshold 阈值（十进制文本，如 {@code 14} / {@code 20.5}）
   * @param changeBasis 比较基准；不给时按 NONE 处理
   * @param timezone 时区；不给时继承当前操作人的生效展示时区
   */
  public record AlertRuleSaveRequest(
      @Schema(nullable = true) String name,
      @Schema(nullable = true) String objectType,
      @Schema(nullable = true) String metricCode,
      @Schema(nullable = true) String threshold,
      @Schema(nullable = true) String timeWindow,
      @Schema(nullable = true) String comparison,
      @Schema(nullable = true) String changeBasis,
      @Schema(nullable = true) String objectScope,
      @Schema(nullable = true) String objectRef,
      @Schema(nullable = true) String timezone) {}

  /** 一条命中记录（引擎写入，本模块只读）。 */
  public record AlertRuleHit(
      UUID id,
      String ruleCode,
      String objectType,
      @Schema(nullable = true) String objectRef,
      String metricCode,
      @Schema(nullable = true) String observedValue,
      @Schema(nullable = true) String baseValue,
      String threshold,
      String comparison,
      String changeBasis,
      String timeWindow,
      String timezone,
      String resolution,
      @Schema(nullable = true) String reason,
      OffsetDateTime hitAt) {}

  /**
   * 命中历史。
   *
   * @param hits 命中记录（**引擎未接通时恒为空集合**，不是「0 次命中」）
   * @param engineAvailable 规则引擎是否已接通
   * @param engineOwnerModule 引擎归哪个模块（未接通时写清楚）
   * @param engineNote 未接通时的说明（界面上要显示，不能是空白）
   * @param total 命中记录总数
   */
  public record AlertRuleHitHistory(
      List<AlertRuleHit> hits,
      boolean engineAvailable,
      String engineOwnerModule,
      String engineNote,
      int total) {}

  /** 能力声明的一维（做得到 / 没做到 / 归谁 / 缺什么）。 */
  public record AlertRuleCapabilityDimension(
      String code, boolean available, String ownerModule, String impact, List<String> missing) {}

  /**
   * 能力声明。
   *
   * @param presets 预置规则集（系统事实）
   * @param vocabulary 受控词表
   * @param engineAvailable 规则引擎是否已接通
   * @param engineOwnerModule 引擎归 18 系统哪个模块
   * @param engineNote 未接通时的说明
   * @param dimensions 逐条能力声明
   * @param boundaries 本模块的边界（明说哪些不归这里）
   */
  public record AlertRuleCapabilities(
      List<AlertRulePreset> presets,
      AlertRuleVocabulary vocabulary,
      boolean engineAvailable,
      String engineOwnerModule,
      String engineNote,
      List<AlertRuleCapabilityDimension> dimensions,
      List<String> boundaries) {}
}
