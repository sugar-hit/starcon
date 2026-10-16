package com.starcon.trade;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.5.1.2 申报要素的接口形状。
 *
 * <p><b>类型名前缀 {@code ElementSet*} 不是随手起的</b>：契约生成器把每个服务的 schema
 * 拍平进 {@code packages/api-contracts/src/index.ts}（各服务一个文件后 {@code export *} 汇总），
 * <b>同名 DTO 会互相覆盖且没有任何警告</b> —— 前端 import 到的是字段完全不相干的类型，
 * 而 typecheck 全绿。catalog 的 {@code CustomsSaved} 与 warehouse 的 {@code CustomsSaved}
 * 就撞过一次（见 {@code CustomsAttributeModels.CustomsProfileSaved} 的类注释）。
 * 本模块刻意避开 trade 已有的 {@code CustomsInput} / {@code CustomsItemInput} /
 * {@code CustomsItemRow}，用全库唯一的前缀。
 */
public final class CustomsElementSetModels {

  private CustomsElementSetModels() {}

  // ==========================================================================
  // 词表（枚举在 DB 与 Java 各持一份，由集成测试逐项钉住不漂移）
  // ==========================================================================

  /**
   * 申报币种（ISO 4217）。
   *
   * <p><b>为什么是枚举而不是 {@code [A-Z]{3}} 正则</b>：V85 第 270 行那条正则会放行
   * {@code ZZZ} / {@code XXX} / {@code ABC} —— 它们形状对、但根本不是货币。一个填错的
   * 币种会让整票货值算错一个数量级，而货值错误的代价由卖家承担（V85 文件头第 9 行）。
   *
   * <p><b>为什么不复用 12 财务的完整 ISO 4217 词表</b>：那个资产未交付（照 catalog
   * {@code SupplierQuoteService} 对 ISO 币种表的同一取舍）。本模块只枚举**跨境申报实际
   * 会用到的**主流结算币，取值域经 {@code capabilities} 暴露给界面，
   * <b>前端不抄一份</b>（照 10.4.1.1「风险承担方从前端不抄枚举」的纪律）。
   *
   * <p>与 V138 的 {@code customs_element_set_currency_iso4217} CHECK 逐项一致，
   * 由 {@code CustomsElementSetIntegrationTest} 的「币种枚举与 DB CHECK 逐项相等」一例钉死。
   */
  public enum ElementSetCurrency {
    AED("阿联酋迪拉姆"),
    AUD("澳元"),
    BRL("巴西雷亚尔"),
    CAD("加拿大元"),
    CHF("瑞士法郎"),
    CLP("智利比索"),
    CNY("人民币"),
    COP("哥伦比亚比索"),
    CZK("捷克克朗"),
    DKK("丹麦克朗"),
    EGP("埃及镑"),
    EUR("欧元"),
    GBP("英镑"),
    HKD("港元"),
    HUF("匈牙利福林"),
    IDR("印度尼西亚盾"),
    ILS("以色列新谢克尔"),
    INR("印度卢比"),
    JPY("日元"),
    KRW("韩元"),
    KZT("哈萨克斯坦坚戈"),
    MXN("墨西哥比索"),
    MYR("马来西亚林吉特"),
    NGN("尼日利亚奈拉"),
    NOK("挪威克朗"),
    NZD("新西兰元"),
    PEN("秘鲁索尔"),
    PHP("菲律宾比索"),
    PLN("波兰兹罗提"),
    RUB("俄罗斯卢布"),
    SAR("沙特里亚尔"),
    SEK("瑞典克朗"),
    SGD("新加坡元"),
    THB("泰铢"),
    TRY("土耳其里拉"),
    TWD("新台币"),
    USD("美元"),
    VND("越南盾"),
    ZAR("南非兰特");

    private final String label;

    ElementSetCurrency(String label) {
      this.label = label;
    }

    public String code() {
      return name();
    }

    public String label() {
      return label;
    }

    /** 空串归 null（未填）；非空时必须是本枚举的一员，否则返回中文原因。 */
    public static String problem(String value) {
      if (value == null || value.isBlank()) return null;
      try {
        valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        return null;
      } catch (IllegalArgumentException e) {
        return "币种「" + value.trim() + "」不是本模块支持的 ISO 4217 申报币种"
            + "（跨境申报常用币种，如 USD / EUR / CNY / GBP / JPY；完整词表是 12 财务的资产）";
      }
    }

    /** 币种代码集合，供 capabilities 与漂移测试使用。 */
    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(ElementSetCurrency::code).sorted().toList();
    }
  }

  /**
   * 品牌类型。
   *
   * <p>与「品牌」分成两列的理由在 V138 第 1 节：海关的品牌申报要素常问的是**类型**
   * （US 的 brand type 要求区分 own brand / OEM / no brand），只报一个品牌名答不出这个问题。
   *
   * <p>{@code NOT_APPLICABLE} 与 {@code NO_BRAND} 分开：前者是「这一栏不适用」
   * （例纺织品没有品牌栏），后者是「明确没有品牌」（申报资料里要写「无品牌」/ N/A）。
   * 两者在海关侧是不同的答复，所以压成一个值就丢了一次申报机会。
   * 与 V138 的 {@code customs_element_set_brand_type_domain} CHECK 逐项一致。
   */
  public enum ElementSetBrandType {
    OWN_BRAND("自有品牌"),
    OEM("贴牌 / 代工"),
    NO_BRAND("无品牌"),
    NOT_APPLICABLE("不适用");

    private final String label;

    ElementSetBrandType(String label) {
      this.label = label;
    }

    public String code() {
      return name();
    }

    public String label() {
      return label;
    }

    /** 空串归 null（未填）；非空时必须在枚举内。 */
    public static String problem(String value) {
      if (value == null || value.isBlank()) return null;
      for (var v : values()) {
        if (v.name().equals(value.trim().toUpperCase(java.util.Locale.ROOT))) return null;
      }
      return "品牌类型「"
          + value.trim()
          + "」不是支持的值（自有品牌 OWN_BRAND / 贴牌 OEM / 无品牌 NO_BRAND / 不适用 NOT_APPLICABLE）";
    }

    public static List<String> codes() {
      return java.util.Arrays.stream(values()).map(ElementSetBrandType::code).sorted().toList();
    }
  }

  /**
   * 状态。**只有草稿与已发布两态**，与 V25 / V95 同一纪律。
   *
   * <p>刻意**没有**「退回 / 作废」态：已发布集合不可原地改正文（V138 规则 C7），
   * 要改正文就是新建版本，所以不存在「这一版被退回」这种状态 ——
   * 有它反而会诱导实现去写一条「已发布 → 草稿」的回退路径，而那正是 C7 要消灭的。
   */
  public enum ElementSetStatus {
    DRAFT("草稿"),
    PUBLISHED("已发布");

    private final String label;

    ElementSetStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 缺失项提示的严重度。
   *
   * <p>{@code BLOCKER} = 齐备性缺口（不补就不能发布，海关逐项比对会判填报不符）；
   * {@code NOTICE} = 口径提示（不挡发布，但界面要点名，避免被读成「一切正常」）。
   */
  public enum ElementSetMissingSeverity {
    BLOCKER("阻断发布"),
    NOTICE("口径提示");

    private final String label;

    ElementSetMissingSeverity(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 一个能力维度（capabilities 用；逐条说清哪个维度真能用、哪个没有上游）。 */
  public record ElementSetCapabilityDimension(
      String dimension,
      String summary,
      boolean available,
      String source,
      String ownerModule,
      String note) {}

  /** 词表项：代码 + 中文名 + 是否必填。 */
  public record ElementSetVocabularyItem(String code, String label, boolean required) {}

  public record ElementSetCapabilities(
      /** 能力维度声明（逐条）。 */
      List<ElementSetCapabilityDimension> dimensions,
      /** 申报币种词表（前端**从这里读**，不抄一份常量）。 */
      List<ElementSetVocabularyItem> currencies,
      /** 品牌类型词表。 */
      List<ElementSetVocabularyItem> brandTypes,
      /** 原产地规则代码词表（协定代码；空串 = 不主张优惠）。 */
      List<ElementSetVocabularyItem> originRules,
      /** 申报要素本体九件的字段名与是否必填（界面按它渲染表单与缺失提示）。 */
      List<ElementSetVocabularyItem> elementFields,
      /** 状态词表。 */
      List<ElementSetVocabularyItem> statuses,
      /** 缺失项严重度词表。 */
      List<ElementSetVocabularyItem> severities,
      /** 生效区间口径说明（常驻页面，避免被读成「永久有效」）。 */
      String effectiveIntervalNote,
      /** 申报品名口径说明（蓝图加粗句，常驻页面）。 */
      String declaredNameNote,
      /** 与 catalog 的边界说明。 */
      String catalogBoundaryNote) {}

  /** 一个缺失项提示。 */
  public record ElementSetMissingItem(
      String field, String label, ElementSetMissingSeverity severity, String reason) {}

  /** 列表行。 */
  public record ElementSetRow(
      UUID id,
      UUID skuId,
      String destinationCountryCode,
      int versionNo,
      String declaredName,
      String specMaterial,
      String brand,
      BigDecimal quantity,
      BigDecimal unitPrice,
      String currency,
      String originCountryCode,
      String originRuleCode,
      boolean preferentialRateApplicable,
      String status,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      /** 此刻是否落在 [effective_from, effective_to) 内；半开区间，to 为 null 记 true。 */
      boolean effectiveNow,
      /** 齐备性：缺失项条数（0 = 齐备）。列表里逐行给出，运营不必点进详情才发现。 */
      int missingCount,
      int blockingMissingCount,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 全量汇总（**不带 LIMIT/OFFSET**，永远覆盖筛选后的全量）。 */
  public record ElementSetSummary(
      int total, int draft, int published, int effectiveNow, int missingAny, int publishedMissing) {}

  /** 真实分页结果。 */
  public record ElementSetPage(
      List<ElementSetRow> items,
      ElementSetSummary summary,
      int page,
      int size,
      long totalElements,
      int totalPages,
      /** 当前排序说明。 */
      String sortNote) {}

  /** 补充申报要素项（有序）。 */
  public record ElementSetItemRow(
      UUID id, int ordinal, String elementCode, String elementName, String elementValue,
      boolean required) {}

  /**
   * 上游合成申报资料预览（3.7.1.2 目的地报关版本，经授权只读 HTTP 取）。
   *
   * <p><b>跨服务</b>：catalog 服务，端口 8082 段。AGENTS.md 禁止跨服务 SQL / FK / 共享视图，
   * 所以这里只能走 {@code GET /catalog/customs-declaration/resolve} 这一个授权只读端点。
   * 取不到一律 503，**不返回空对象**（理由见 {@link CustomsElementSetPort}）。
   */
  public record ElementSetUpstreamPreview(
      UUID skuId,
      String skuCode,
      /** catalog 的 sku_name，也就是**销售标题** —— 界面上与申报品名并排显示，让人一眼看出两者不同。 */
      String salesTitle,
      String destinationCountryCode,
      String destinationVersionRef,
      int destinationVersionNo,
      String declaredName,
      String declaredNameSource,
      String hsCode,
      String originCountryCode,
      String statutoryUnitCode,
      boolean preferentialOriginEligible,
      boolean preferentialApplicable,
      boolean preferentialGranted,
      String preferenceAgreement,
      /** catalog 侧的基础申报要素（有序码值），本模块只读不改。 */
      List<ElementSetItemRow> upstreamElements,
      /** 上游要素是否齐备（catalog 的 declarationElementStatus）。 */
      String upstreamElementStatus) {}

  /** 详情。 */
  public record ElementSetDetail(
      ElementSetRow row,
      List<ElementSetItemRow> items,
      /** 缺失项提示（蓝图输出句的「提示」那一半，逐项点名）。 */
      List<ElementSetMissingItem> missing,
      boolean complete,
      boolean publishable,
      /** 建集时记下的销售标题快照（规则 C1 的比对基准）。 */
      String salesTitleSnapshot,
      /** 合法可执行动作。 */
      List<String> actions,
      /** 边界声明：这个集合与 catalog 各是什么关系。 */
      String boundaryNote) {}

  /** 写形状：建集 / 改集（同一份输入）。 */
  public record ElementSetInput(
      @NotNull UUID skuId,
      @NotNull @Pattern(regexp = "[A-Za-z]{2}", message = "目的地必须是两位 ISO 3166-1 alpha-2 代码") String destinationCountryCode,
      @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(999) int versionNo,
      @Size(max = 500) String declaredName,
      @Size(max = 500) String specMaterial,
      @Size(max = 500) String usage,
      String brandType,
      @Size(max = 200) String brand,
      /** 数量：numeric(19,6)。给了必须 > 0（V138 CHECK）。 */
      @Digits(integer = 13, fraction = 6) @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      /** 单价：numeric(19,4) + BigDecimal，禁浮点（与 V85 declared_value 同一刻度）。 */
      @Digits(integer = 15, fraction = 4) @DecimalMin(value = "0", inclusive = false) BigDecimal unitPrice,
      String currency,
      @Pattern(regexp = "^$|^[A-Za-z]{2}$", message = "产地必须是两位 ISO 3166-1 alpha-2 代码") String originCountryCode,
      @Pattern(regexp = "^$|^[A-Z0-9_]{1,32}$", message = "原产地规则代码只能是大写字母、数字或下划线") String originRuleCode,
      Boolean preferentialRateApplicable,
      @NotNull OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveTo,
      @Size(max = 1000) String note,
      /** 乐观锁凭据：改集时必填，与既有模块同一口径。 */
      Long version,
      /** 补充申报要素项（整体替换）。 */
      List<ElementSetItemInput> items) {}

  /** 补充要素项的写形状。 */
  public record ElementSetItemInput(
      @NotNull @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(999) int ordinal,
      @NotNull @Pattern(regexp = "^[A-Za-z0-9._-]{1,32}$") String elementCode,
      @NotNull @Size(max = 200) String elementName,
      @Size(max = 500) String elementValue,
      Boolean required) {}

  /** 发布形状。 */
  public record ElementSetPublishInput(@NotNull Long version) {}

  /** 向前关闭生效区间的形状。 */
  public record ElementSetCloseInput(
      @NotNull Long version,
      @NotNull OffsetDateTime effectiveTo) {}

  /** 写结果的统一回执。 */
  public record ElementSetSaved(ElementSetDetail detail) {}

  /** 筛选下拉（**真实数据 DISTINCT**，不是硬编码词表）。 */
  public record ElementSetFilterOptions(
      List<ElementSetVocabularyItem> destinations,
      List<ElementSetVocabularyItem> statuses,
      List<String> skuIds,
      List<ElementSetSortOption> sorts,
      String defaultSort,
      long total) {}

  /** 排序白名单一项。 */
  public record ElementSetSortOption(String key, String label) {}
}
