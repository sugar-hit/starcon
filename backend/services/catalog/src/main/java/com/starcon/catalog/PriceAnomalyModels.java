package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.5.2.1 异常告警：价格异常的**观测台账**、**判定规则**、**异常事件**与**预警通知**。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：监测价格异常：平台价与内部价长期不一致、售价低于成本线、平台自动降价、多站点价格倒挂（同商品不同站点价差导致窜货）。
 *
 * **输入**：平台价回流；内部价；成本线；站点价差规则。
 *
 * **输出**：异常清单 + 预警通知。
 *
 * **关系**：上游 = 4.3.2.2、5.2.1.1；下游 = 1 工作台预警、16 合规（窜货风控）。**站点价差倒挂是跨境特有的风险**：德国站定价低于美国站会引发跨区窜货与合规问题。
 *
 * </blockquote>
 *
 * <p><b>🔴 四类异常的数据来源互不相同</b>，这是本模块最容易做错的地方：
 *
 * <table border="1">
 *
 * <caption>四类异常</caption>
 *
 * <tr><th>类型</th><th>判定依据</th><th>来源</th></tr>
 *
 * <tr><td>平台价与内部价长期不一致</td><td>同一站点的一串观测（要判「持续多久」）</td><td>本服务自持的观测台账（V222）</td></tr>
 *
 * <tr><td>售价低于成本线</td><td>售价 + 成本线</td><td>成本线 = 5.2.1.1 的 {@code sku_cost_revision.total_unit_cost}（V215，<b>同服务</b>）</td></tr>
 *
 * <tr><td>平台自动降价</td><td>相邻两次观测 + 「这次降价是谁动的」</td><td>本服务自持的观测台账（V222）</td></tr>
 *
 * <tr><td>多站点价格倒挂</td><td><b>站点两两比对</b></td><td>本服务自持的观测台账（V222，站点维度进表进 SQL）</td></tr>
 *
 * </table>
 *
 * <p><b>🔴 「长期」与「发生过」是两种事件</b>：①②④ 是**当前状态**（不再命中就自动 RESOLVED），
 * ③ 是**发生过的事**（不会因窗口移走而消失，只能由人确认或消解）。
 */
public final class PriceAnomalyModels {

  private PriceAnomalyModels() {}

  // ================================================================ 词表

  /** 四类价格异常。<b>枚举只增不改</b>：它会进事件表与契约，改语义会让历史事件读起来是错的。 */
  public enum AnomalyType {
    /** ① 平台价与内部价长期不一致。 */
    PLATFORM_INTERNAL_DIVERGENCE("平台价与内部价长期不一致"),
    /** ② 售价低于成本线。 */
    BELOW_COST("售价低于成本线"),
    /** ③ 平台自动降价。 */
    PLATFORM_AUTO_DISCOUNT("平台自动降价"),
    /** ④ 多站点价格倒挂（跨区窜货风险）。 */
    CROSS_SITE_INVERSION("多站点价格倒挂");

    private final String label;

    AnomalyType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析失败返回 null（调用方据此给出可读错误，而不是抛枚举异常）。 */
    public static AnomalyType parse(String raw) {
      if (raw == null) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) {
          return t;
        }
      }
      return null;
    }
  }

  /** 异常事件的生命周期。 */
  public enum EventStatus {
    /** 待处理。 */
    OPEN("待处理"),
    /** 已确认（人已经看到并接手了）。 */
    ACKNOWLEDGED("已确认"),
    /** 已消解。 */
    RESOLVED("已消解");

    private final String label;

    EventStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static EventStatus parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) {
          return s;
        }
      }
      return null;
    }
  }

  /** 严重度。 */
  public enum Severity {
    HIGH("高"),
    MEDIUM("中"),
    LOW("低");

    private final String label;

    Severity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static Severity parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) {
          return s;
        }
      }
      return null;
    }
  }

  /** 这次价格是**谁动的**（③ 平台自动降价的判定依据）。 */
  public enum PriceChangeSource {
    /** 平台自动降的（平台侧促销 / 自动比价规则）。 */
    AUTOMATIC_PLATFORM("平台自动降价"),
    /** 我们自己改的（内部调价）。 */
    MANUAL_INTERNAL("内部调价"),
    /** 平台回流带来的价，来源未进一步区分。 */
    PLATFORM_FEEDBACK("平台回流"),
    /** 不知道（登记时没标）。 */
    UNKNOWN("来源未标注");

    private final String label;

    PriceChangeSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static PriceChangeSource parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) {
          return s;
        }
      }
      return null;
    }
  }

  /** 观测从哪个通道进来。 */
  public enum ObservationSource {
    /** 平台价回流。 */
    PLATFORM_FEEDBACK("平台价回流"),
    /** 内部价目（5.1 未交付 ⇒ 现阶段由调用方带价登记）。 */
    PRICE_LIST("内部价目"),
    /** 人工登记 / 补录。 */
    MANUAL_ATTESTATION("人工登记");

    private final String label;

    ObservationSource(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static ObservationSource parse(String raw) {
      if (raw == null) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) {
          return s;
        }
      }
      return null;
    }
  }

  /** 被判异常的那个价是哪一个。 */
  public enum SubjectKind {
    PLATFORM_PRICE("平台价"),
    INTERNAL_PRICE("内部价");

    private final String label;

    SubjectKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 能力声明

  /**
   * 一个输入维度的能力声明。
   *
   * @param available 当前取得到吗
   * @param ownerModule 该输入归谁管（未交付时点名）
   * @param impact 未交付时本模块怎么处理
   */
  public record InputCapability(
      String code,
      String label,
      String source,
      boolean available,
      String ownerModule,
      String impact,
      String basis,
      String note) {}

  /** 一类异常的判定口径声明。 */
  public record AnomalyCapability(
      String code,
      String label,
      String detectedBy,
      boolean available,
      String thresholdNote,
      String basis,
      String note) {}

  /** 能力声明。页面顶部固定显示它 —— 蓝图那句话（「读不到」与「没有」是两件事）靠它落地。 */
  public record PriceAnomalyCapabilities(
      List<InputCapability> inputs,
      List<AnomalyCapability> anomalyTypes,
      List<AnomalyRuleRow> rules,
      String detectionNote,
      String upstreamNote,
      String downstreamNote,
      String sourceNote,
      String actionsNote) {}

  // ================================================================ 规则

  /**
   * 一条判定规则。
   *
   * @param overridden 该类型是否被租户显式覆盖过（false = 用服务层内置默认值）
   */
  public record AnomalyRuleRow(
      UUID id,
      String anomalyType,
      String anomalyTypeLabel,
      String name,
      boolean enabled,
      @Schema(nullable = true) BigDecimal ratioThreshold,
      @Schema(nullable = true) Integer minDurationDays,
      String severity,
      String note,
      boolean overridden,
      OffsetDateTime updatedAt) {}

  /** 保存规则。阈值列可空 = 用内置默认值；显式给 0 是合法的。 */
  public record AnomalyRuleInput(
      @NotBlank String anomalyType,
      @NotBlank @Size(max = 100) String name,
      Boolean enabled,
      @Schema(nullable = true) BigDecimal ratioThreshold,
      @Schema(nullable = true) Integer minDurationDays,
      @NotBlank String severity,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  // ================================================================ 观测

  /** 观测台账的查询条件。 */
  public record ObservationQuery(
      String keyword,
      String platformCode,
      String siteCode,
      String currency,
      String priceChangeSource,
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      int page,
      int size) {}

  /** 一行 = 一个 SKU 在一个站点上的一次价格观测。 */
  public record ObservationRow(
      UUID id,
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String currency,
      @Schema(nullable = true) BigDecimal internalPrice,
      @Schema(nullable = true) BigDecimal platformPrice,
      String priceChangeSource,
      String priceChangeSourceLabel,
      String source,
      String sourceLabel,
      @Schema(nullable = true) String comparisonCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true) LocalDate fxRateDate,
      @Schema(nullable = true) String fxRateSource,
      OffsetDateTime observedAt,
      String note) {}

  /** 观测台账分页结果。 */
  public record ObservationListPage(
      List<ObservationRow> items, long total, int page, int size, String note) {}

  /**
   * 登记一次价格观测。
   *
   * <p>🔴 至少要给一个价（内部价或平台价）—— 两个都没有的观测没有任何信息量。
   *
   * <p>🔴 {@code fxRate} / {@code comparisonCurrency} **必须同时给或同时不给**：
   * 只给一个，比价口径就是残缺的，而残缺的口径会静默地把站点对判成「不可比」。
   */
  public record ObservationInput(
      @NotNull UUID skuId,
      @NotBlank @Size(max = 32) String platformCode,
      @NotBlank @Size(max = 32) String siteCode,
      @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
      @Schema(nullable = true) BigDecimal internalPrice,
      @Schema(nullable = true) BigDecimal platformPrice,
      @NotBlank String priceChangeSource,
      @NotBlank String source,
      @Schema(nullable = true) @Pattern(regexp = "[A-Z]{3}") String comparisonCurrency,
      @Schema(nullable = true) BigDecimal fxRate,
      @Schema(nullable = true) LocalDate fxRateDate,
      @Schema(nullable = true) @Size(max = 64) String fxRateSource,
      @NotNull OffsetDateTime observedAt,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /** 登记结果。 */
  public record ObservationSaved(UUID id, UUID skuId, String platformCode, String siteCode, String note) {}

  // ================================================================ 事件

  /** 异常清单的查询条件。 */
  public record EventQuery(
      String keyword,
      String anomalyType,
      String status,
      String severity,
      String platformCode,
      String siteCode,
      @Schema(nullable = true) LocalDate windowFrom,
      @Schema(nullable = true) LocalDate windowTo,
      int page,
      int size) {}

  /** 异常事件一行。 */
  public record EventRow(
      UUID id,
      String anomalyType,
      String anomalyTypeLabel,
      UUID skuId,
      String skuCode,
      String skuName,
      String platformCode,
      String siteCode,
      String relatedPlatformCode,
      String relatedSiteCode,
      String currency,
      String subjectKind,
      @Schema(nullable = true) BigDecimal subjectPrice,
      @Schema(nullable = true) BigDecimal referencePrice,
      @Schema(nullable = true) BigDecimal gapAmount,
      @Schema(nullable = true) BigDecimal gapRatio,
      @Schema(nullable = true) BigDecimal costLine,
      String costCurrency,
      boolean costComplete,
      @Schema(nullable = true) OffsetDateTime costSnapshotAt,
      OffsetDateTime firstSeenAt,
      OffsetDateTime lastSeenAt,
      int durationDays,
      String severity,
      String severityLabel,
      String status,
      String statusLabel,
      @Schema(nullable = true) UUID ruleId,
      String fingerprint,
      String evidence,
      OffsetDateTime updatedAt) {}

  /** 异常清单分页结果。 */
  public record EventListPage(
      List<EventRow> items, long total, int page, int size, String note) {}

  /** 异常事件详情：事件 + 通知 + 相关观测。 */
  public record EventDetail(
      EventRow event,
      List<NotificationRow> notifications,
      List<ObservationRow> observations,
      String basis,
      String note) {}

  /** 事件状态变更结果。 */
  public record EventStatusSaved(UUID id, String status, String statusLabel, OffsetDateTime updatedAt) {}

  // ================================================================ 通知

  /** 预警通知一行。 */
  public record NotificationRow(
      UUID id,
      UUID eventId,
      String anomalyType,
      String anomalyTypeLabel,
      String severity,
      String severityLabel,
      String title,
      String body,
      String channel,
      String recipientScope,
      String ownerModule,
      boolean delivered,
      @Schema(nullable = true) OffsetDateTime deliveredAt,
      OffsetDateTime createdAt) {}

  /** 通知清单分页结果。 */
  public record NotificationListPage(
      List<NotificationRow> items, long total, int page, int size, String note) {}

  // ================================================================ 检测

  /** 一个**不可比**的站点对（跨币种且没有汇率）。 */
  public record UncomparablePair(
      UUID skuId,
      String skuCode,
      String platformCode,
      String siteCode,
      String currency,
      String relatedPlatformCode,
      String relatedSiteCode,
      String relatedCurrency,
      String reason) {}

  /** 一次检测的逐类小结。 */
  public record DetectionTypeSummary(
      String anomalyType, String anomalyTypeLabel, int evaluated, int detected, String note) {}

  /**
   * 一次检测的结果。
   *
   * @param truncated 观测条数超过扫描上限（**不静默截断**：这里明确说出来）
   */
  public record DetectionRun(
      LocalDate windowFrom,
      LocalDate windowTo,
      int observationsScanned,
      int eventsOpened,
      int eventsUpdated,
      int eventsResolved,
      int notificationsRaised,
      List<DetectionTypeSummary> typeSummary,
      List<UncomparablePair> uncomparablePairs,
      boolean truncated,
      String note) {}
}
