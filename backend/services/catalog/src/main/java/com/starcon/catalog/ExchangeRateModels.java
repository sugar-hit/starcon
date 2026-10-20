package com.starcon.catalog;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 5.2.2.1 汇率查询：汇率记录（汇率、方向、生效日期、来源）、汇率来源配置与外部拉取日志。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 *
 * **职责**：查询各币种对各本位币的汇率（来源：中行/央行/平台结算汇率，可配置），展示汇率日期与来源，支持手动录入特定日期的汇率。
 *
 * **输入**：币种对与日期；汇率来源配置；手工汇率录入。
 *
 * **输出**：汇率记录（汇率、方向、生效日期、来源）。
 *
 * **关系**：上游 = 外部汇率源；下游 = 5.2.2.2、5.3 测算、12 财务。**汇率方向必须显式**（1 USD = X CNY 与 1 CNY = X USD 混淆是常见错源）。
 *
 * </blockquote>
 *
 * <p><b>🔴 方向是结构，不是注释</b>：每条汇率记录都带 {@code baseCurrency} 与 {@code quoteCurrency}，
 * 换算方向由这两个字段唯一确定 —— 少一个都写不进来。反向汇率（{@code inverseRate} /
 * {@code inverseDisplay}）是**由正向显式推导**并标注的派生值，**不落库**：手工再录一条方向相反的
 * 会造出两个互相矛盾的事实，DB 层用触发器挡住。
 *
 * <p><b>同一条记录在接口上也必须看得出方向</b>：{@link RateView#display()} 给出
 * 「1 USD = 7.1234 CNY」这样的整句，页面直接用它，不允许把裸数字 {@code rate} 单独显示。
 */
public final class ExchangeRateModels {

  private ExchangeRateModels() {}

  // ================================================================ 词表

  /**
   * 汇率来源的类型。
   *
   * <p>它决定「这条汇率能怎么来」：{@link #MANUAL} 只接受手工录入；{@link #EXTERNAL} 从
   * {@code endpoint} 拉取，且**必须**配了地址（DB 层 CHECK + 触发器双重约束）。
   */
  public enum SourceKind {
    /** 手工录入：只接受运营手工登记的汇率。 */
    MANUAL("手工录入", "只接受人工登记的汇率，不做外部拉取。"),
    /** 外部拉取：从配置的地址真实发 HTTP 请求取数。 */
    EXTERNAL("外部拉取", "从配置的地址真实发起 HTTP 请求拉取汇率；拉取失败会留失败日志与错误原因。");

    private final String label;
    private final String basis;

    SourceKind(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    public static SourceKind parse(String raw) {
      if (raw == null) return null;
      for (var k : values()) {
        if (k.name().equalsIgnoreCase(raw.trim())) return k;
      }
      return null;
    }
  }

  /**
   * 汇率的录入方式。
   *
   * <p>与 {@link SourceKind} 值域相同但语义不同：这里是「**这一条**是怎么来的」。
   * 触发器要求两者一致 —— 来源是手工却把这条标成外部拉取，就是在编造来源。
   */
  public enum EntryMode {
    /** 手工录入。 */
    MANUAL("手工录入", "由运营手工登记。"),
    /** 外部拉取。 */
    EXTERNAL("外部拉取", "由外部汇率源拉取入库。");

    private final String label;
    private final String basis;

    EntryMode(String label, String basis) {
      this.label = label;
      this.basis = basis;
    }

    public String label() {
      return label;
    }

    public String basis() {
      return basis;
    }

    public static EntryMode parse(String raw) {
      if (raw == null) return null;
      for (var m : values()) {
        if (m.name().equalsIgnoreCase(raw.trim())) return m;
      }
      return null;
    }
  }

  // ================================================================ 汇率来源配置

  /** 一个汇率来源（租户级配置）。 */
  public record RateSourceView(
      UUID id,
      String code,
      String name,
      String kind,
      String kindLabel,
      String kindBasis,
      boolean enabled,
      int priority,
      String endpoint,
      String note,
      long rateCount,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 新建汇率来源。 */
  public record RateSourceInput(
      @NotBlank
          @Pattern(regexp = "[A-Za-z0-9_]{2,32}", message = "来源代码请使用 2~32 位字母、数字或下划线")
          String code,
      @NotBlank @Size(max = 100) String name,
      @NotBlank String kind,
      Boolean enabled,
      Integer priority,
      @Size(max = 500) String endpoint,
      @Size(max = 500) String note) {}

  /** 修改汇率来源（走乐观锁）。 */
  public record RateSourceUpdate(
      @NotBlank @Size(max = 100) String name,
      @NotBlank String kind,
      @NotNull Boolean enabled,
      @NotNull Integer priority,
      @Size(max = 500) String endpoint,
      @Size(max = 500) String note,
      @NotNull Long version) {}

  // ================================================================ 汇率记录

  /**
   * 一条汇率记录。
   *
   * <p>{@code display} 是**整句**（「1 USD = 7.1234 CNY」）；{@code inverseRate} /
   * {@code inverseDisplay} 是**由正向显式推导**的反向汇率，带 {@code inverseDerived=true} 标注，
   * 页面必须按「推导」呈现，不许当成一条独立事实。
   */
  public record RateView(
      UUID id,
      String baseCurrency,
      String quoteCurrency,
      BigDecimal rate,
      LocalDate effectiveDate,
      String sourceCode,
      String sourceName,
      String sourceKind,
      String entryMode,
      String entryModeLabel,
      String sourceRef,
      String note,
      OffsetDateTime acquiredAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      /** 「1 USD = 7.1234 CNY」—— 方向在句子里，不靠调用方自己拼。 */
      String display,
      /** 反向汇率（1 quote = inverseRate base），由正向推导。 */
      BigDecimal inverseRate,
      /** 「1 CNY = 0.1404 USD（由 1 USD = 7.1234 CNY 推导）」。 */
      String inverseDisplay,
      /** 反向汇率是推导值，不是独立事实。恒为 true。 */
      boolean inverseDerived) {}

  /** 手工录入一条汇率。 */
  public record RateInput(
      @NotBlank
          @Pattern(regexp = "[A-Za-z]{3}", message = "基准币种请用三位大写币种代码（如 USD）")
          String baseCurrency,
      @NotBlank
          @Pattern(regexp = "[A-Za-z]{3}", message = "报价币种请用三位大写币种代码（如 CNY）")
          String quoteCurrency,
      @NotNull @Positive(message = "汇率必须大于 0") BigDecimal rate,
      @NotNull LocalDate effectiveDate,
      @NotBlank String sourceCode,
      @Size(max = 200) String sourceRef,
      @Size(max = 500) String note) {}

  /** 台账查询条件。 */
  public record RateListQuery(
      String baseCurrency,
      String quoteCurrency,
      String sourceCode,
      LocalDate dateFrom,
      LocalDate dateTo,
      String keyword,
      int page,
      int size) {}

  /** 台账分页结果。 */
  public record RateListPage(
      List<RateView> items, long total, int page, int size, String note) {}

  /** 能力声明：来源清单、方向规则、录入方式与下游状态。 */
  @JsonInclude(JsonInclude.Include.ALWAYS)
  public record RateCapabilities(
      List<RateSourceView> sources,
      /** 方向规则的完整说明（页面上原样显示）。 */
      String directionRule,
      /** 同一币种对 + 同一生效日的唯一性说明。 */
      String uniquenessRule,
      /** 反向汇率的处理说明。 */
      String inverseRule,
      List<String> entryModes,
      /** 下游 5.2.2.2 的交付状态：如实写明未交付。 */
      String downstreamNote,
      /** 数据归属说明（本表在 catalog，不在 12 财务）。 */
      String ownershipNote,
      /** 本位币来源说明。 */
      String functionalCurrencyNote,
      /** 外部拉取的响应格式约定。 */
      String fetchContractNote) {}

  // ================================================================ 外部拉取

  /** 发起一次外部拉取。 */
  public record FetchRequest(
      @NotBlank String sourceCode,
      @NotBlank
          @Pattern(regexp = "[A-Za-z]{3}", message = "基准币种请用三位大写币种代码")
          String baseCurrency,
      @NotBlank
          @Pattern(regexp = "[A-Za-z]{3}", message = "报价币种请用三位大写币种代码")
          String quoteCurrency,
      @NotNull LocalDate effectiveDate) {}

  /**
   * 一次外部拉取的结果。失败时 {@code status=FAILED} 且 {@code errorMessage} 有话。
   *
   * <p><b>为什么不抛异常</b>：端点层把失败映射成 503 / 400 的**响应体**而不是异常 ——
   * 抛异常会让外层事务回滚，而失败日志正是这次拉取**唯一**留下的痕迹，
   * 一起回滚掉就等于「失败什么都没留下」。返回体 + 显式状态码既让客户端看到明确错误态，
   * 又让失败日志真的落库。
   */
  public record FetchResult(
      String status,
      String sourceCode,
      Integer httpStatus,
      int rowsFetched,
      int rowsSaved,
      String errorMessage,
      OffsetDateTime requestedAt,
      List<RateView> rates,
      /** 失败时端点应返回的 HTTP 状态（400 / 503）；成功为 null。 */
      Integer failureStatus) {}

  /** 拉取日志一行（只追加）。 */
  public record FetchLogRow(
      UUID id,
      String sourceCode,
      String baseCurrency,
      String quoteCurrency,
      LocalDate requestedDate,
      String status,
      Integer httpStatus,
      int rowsFetched,
      int rowsSaved,
      String errorMessage,
      OffsetDateTime requestedAt) {}

  // ================================================================ 本位币

  /** 一个法人（17.1.2.2）的本位币，以及该本位币下已登记的最新汇率。 */
  public record FunctionalCurrencyRow(
      UUID legalEntityId,
      String legalEntityCode,
      String legalEntityName,
      String baseCurrency,
      List<RateView> rates) {}

  /** 本位币面板。 */
  public record FunctionalCurrencyPanel(List<FunctionalCurrencyRow> rows, String note) {}

  /** 能力声明里的一条来源说明。 */
  @Schema(description = "汇率来源类型")
  public record SourceKindOption(String value, String label, String basis) {}
}
