package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 6.1.1.1 供应商列表的 DTO。
 *
 * <p>刻意**独立成文件**而不是并进 {@code CatalogModels}：那个文件有 5200 行且被并行开发的多个模块共享，与 {@code ZombieCandidateModels} /
 * {@code ImportWizardModels} 同一处理方式。
 *
 * <p>**每个类型名都带 {@code Vendor} 前缀**，这不是审美偏好而是硬约束：{@code tools/codegen/catalog-contracts.py} 把六个服务的 schemas 拍平进同一个命名空间，
 * 重名的类型后者直接覆盖前者（trade 有 {@code DropshipSupplierStockRow} 这类名字，再造一个 {@code SupplierListRow} 就是在赌运气）。
 *
 * <p><b>本模块不建供应商主数据</b>：下列 record 里带 partyCode / partyName / email / phone / cooperationLevel 的字段，
 * 全部**读自** 3.6.1.1 的 party 与 3.6.1.2 的 party_supplier_profile，本服务一个字都不写 —— 理由见 {@code V137__vendor_supply_perspective.sql} 第一节。
 */
public final class VendorListModels {
  private VendorListModels() {}

  // ================================================================ 筛选 / 排序 / 分页

  /**
   * 供应商列表的筛选条件。null 表示不筛；空串按「不过滤」处理。
   *
   * <p>{@code metricFrom} / {@code metricTo} 是**准时交付率与质量合格率的时间窗**（规则 R3）：
   * 这两个是窗口内的统计而不是终身累计，所以「哪一段窗口」必须能显式指定并在响应里回显。
   */
  public record VendorListQuery(
      /** 关键词：编码 / 名称 / 邮箱 / 电话。 */
      @Schema(nullable = true) @Size(max = 200) String keyword,
      /** 合作状态（本模块自己的采购侧状态，vendor_supply_profile.supply_status）。 */
      @Schema(nullable = true) @Size(max = 24) String supplyStatus,
      /** 主供类目（product_category.id）。 */
      @Schema(nullable = true) UUID categoryId,
      /** 合作等级（**只读** 3.6.1.2 的 party_supplier_profile.cooperation_level，规则 R1）。 */
      @Schema(nullable = true) @Size(max = 24) String cooperationLevel,
      /** 主体状态（3.6.1.1 的 party.status，只读）。 */
      @Schema(nullable = true) @Size(max = 24) String partyStatus,
      /** 币种：采购金额累计只在这个币种上汇总（跨币种不可加，规则 R4）。 */
      @Schema(nullable = true) @Size(max = 3) String currency,
      /** 只看还没建采购侧运营档案的供应商（治理视图）。 */
      @Schema(nullable = true) Boolean missingProfile,
      /**
       * 指标窗口起（含）。不传时默认「最近 365 天」，且实际窗口会回显在响应的 metricWindow 上。
       *
       * <p>显式声明 ISO 格式：不写的话 Spring 的默认绑定在 query 参数上会要求
       * {@code 2026-01-01T00:00:00Z} 这类完整形态，少一个时区就 400，
       * 而前端往往只是想传一个日期 —— 400 里没有任何线索说明是格式问题。
       */
      @Schema(nullable = true)
      @org.springframework.format.annotation.DateTimeFormat(
          iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricFrom,
      /** 指标窗口止（含）。 */
      @Schema(nullable = true)
      @org.springframework.format.annotation.DateTimeFormat(
          iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime metricTo,
      /** 排序键，必须命中 {@link VendorListRules#sorts()} 白名单。 */
      @Schema(nullable = true) @Size(max = 32) String sort,
      /** 是否降序；null 走 {@link VendorListRules#defaultDescFor} 的按列默认方向。 */
      @Schema(nullable = true) Boolean desc,
      int page,
      int size) {}

  // ================================================================ 列表行

  /**
   * 一个币种上的采购金额累计。
   *
   * <p>**刻意按币种分组而不是给一个总数**（规则 R4）：本库没有汇率表，折算是 12 财务的资产。
   * 把 100 USD 与 90 EUR 相加得到的 190 是一个看起来对、实际错的数字，而且错得极难被发现。
   */
  public record VendorPurchaseAmount(String currency, BigDecimal amount) {}

  /** 供应商列表的一行。字段分三段，每段的所有者是不同的模块。 */
  public record VendorRow(
      // ---- 段一：读自 3.6.1.1 party（本模块只读）
      UUID roleId,
      UUID partyId,
      String partyCode,
      String partyName,
      String partyType,
      @Schema(nullable = true) String countryCode,
      @Schema(nullable = true) String email,
      @Schema(nullable = true) String phone,
      @Schema(nullable = true) String website,
      String partyStatus,
      @Schema(nullable = true) String statusReason,
      // ---- 段二：读自 3.6.1.2 party_supplier_profile（本模块只读，规则 R1）
      @Schema(nullable = true) String cooperationLevel,
      @Schema(nullable = true) Integer paymentTermsDays,
      @Schema(nullable = true) String settlementMethod,
      boolean supplierProfileFound,
      // ---- 段三：本模块自有（vendor_supply_profile）
      /** 采购侧合作状态。null = 还没建运营档案（列表默认按 PENDING_REVIEW 口径看待，见 service 的 note）。 */
      @Schema(nullable = true) String supplyStatus,
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) String categoryName,
      @Schema(nullable = true) String supplyNote,
      boolean supplyProfileFound,
      @Schema(nullable = true) Long supplyProfileVersion,
      // ---- 段四：四个关键指标，全部是 SQL 聚合（没有一个是存下来的字段）
      /** M1 供货 SKU 数：有效报价覆盖的不同 SKU 数。 */
      long supplySkuCount,
      /** M2 采购金额累计（已到货口径），按币种分组。 */
      List<VendorPurchaseAmount> purchaseAmounts,
      /** M2 的代表值：排序用的那一笔（金额最大的币种，或 currency 筛选指定的币种）。 */
      @Schema(nullable = true) BigDecimal purchaseAmount,
      @Schema(nullable = true) String purchaseAmountCurrency,
      /** M3 准时交付率的分子：窗口内 received_date <= due_date 的记录数。 */
      @Schema(nullable = true) Long onTimeDeliveryCount,
      /** M3 准时交付率的分母：窗口内到货记录数。 */
      @Schema(nullable = true) Long deliveryCount,
      /** M3 准时交付率。**窗口内无到货记录时为 null 而不是 0**（规则 R3）。 */
      @Schema(nullable = true) BigDecimal onTimeRate,
      /** M4 质量合格率的分子：窗口内 sum(qualified_qty)。 */
      @Schema(nullable = true) BigDecimal qualifiedQuantity,
      /** M4 质量合格率的分母：窗口内 sum(inspected_qty)。 */
      @Schema(nullable = true) BigDecimal inspectedQuantity,
      /** M4 质量合格率（按数量比而非批次数比）。无记录时为 null。 */
      @Schema(nullable = true) BigDecimal qualityRate,
      @Schema(nullable = true) String lastDeliveryAt,
      @Schema(nullable = true) String lastInspectionAt) {}

  /** 指标窗口的回显：界面上必须显示它，否则「准时率 96%」是一个没有意义的时间外的数字。 */
  public record VendorMetricWindow(String from, String to) {}

  /** 供应商列表的分页结果。计数与取行引同一处筛选条件，所以总数与翻页一定对得上。 */
  public record VendorListPage(
      List<VendorRow> items,
      long total,
      int page,
      int size,
      VendorMetricWindow metricWindow,
      String note) {}

  /** 列表顶部的规模指标。 */
  public record VendorListOverview(
      long supplierCount,
      long supplyProfileCount,
      long missingProfileCount,
      long activeSupplyCount,
      long suspendedSupplyCount,
      /** 窗口内**有到货记录**的供应商数 —— 比率指标只在这些行上有意义，其余行的比率是 null。 */
      long withDeliveryRecord,
      /** 窗口内有质检记录的供应商数。 */
      long withQualityRecord,
      /** 全租户口径的窗口内准时交付率（按记录数加权，不是各家比率的平均）。 */
      @Schema(nullable = true) BigDecimal onTimeRate,
      /** 全租户口径的窗口内质量合格率（按数量加权）。 */
      @Schema(nullable = true) BigDecimal qualityRate,
      VendorMetricWindow metricWindow,
      String note) {}

  /** 词表选项：前端下拉的数据源，与 {@link VendorListRules} 同源（枚举只增不改这条承诺要在**一处**兑现）。 */
  public record VendorListOptions(
      List<VendorListRules.Option> supplyStatuses,
      /** 合作等级**只读**，但下拉仍然要给 —— 它是蓝图点名的筛选条件（规则 R1）。 */
      List<VendorListRules.Option> cooperationLevels,
      /** 主体状态（3.6.1.1 的词表，只读）。 */
      List<VendorListRules.Option> partyStatuses,
      /** 类目下拉（来自 product_category，运营真正会按它筛的是这一项）。 */
      List<VendorCategoryOption> categories,
      /** 排序键白名单（含中文名，前端直接渲染成列头可点项）。 */
      List<VendorSortOption> sorts,
      String note) {}

  /** 一个可选类目。 */
  public record VendorCategoryOption(UUID id, String code, String name) {}

  /** 一个可排序键。{@code defaultDesc} 是这一列的默认方向（比率类默认降序 = 差的排前面）。 */
  public record VendorSortOption(String key, String label, boolean defaultDesc) {}

  // ================================================================ 采购侧运营动作（写入）

  /**
   * 采购侧运营档案（新建或改）。
   *
   * <p>**刻意不含 code / name / email / phone / cooperationLevel**：那些是 3.6.1.1 与 3.6.1.2 的字段，写入所有权在那边。
   * 本 record 里出现的每一个字段都是「采购视角独有」的，没有一个能被用来第二次编辑主体档案（规则 R1、R2）。
   */
  public record VendorSupplyProfileInput(
      @NotBlank @Size(max = 24) String supplyStatus,
      /** 主供类目；留空表示「还没归类」（归不了类的供应商在 06 拆单时是兜底候选）。 */
      @Schema(nullable = true) UUID categoryId,
      @Schema(nullable = true) @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 到货记录（{@code POST /catalog/supply/vendors/{roleId}/deliveries}）。 */
  public record VendorDeliveryInput(
      @NotBlank @Size(max = 64) String deliveryRef,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) @Size(max = 32) String purchaseUnitCode,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
      @NotNull @DecimalMin("0") BigDecimal amount,
      @NotBlank @Size(max = 3) String currency,
      @NotNull OffsetDateTime orderedAt,
      @NotNull LocalDate dueDate,
      @NotNull LocalDate receivedDate,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /** 质检记录（{@code POST /catalog/supply/vendors/{roleId}/quality-inspections}）。 */
  public record VendorQualityInspectionInput(
      @Schema(nullable = true) UUID deliveryId,
      @NotBlank @Size(max = 64) String inspectionRef,
      @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal inspectedQuantity,
      @NotNull @DecimalMin("0") BigDecimal qualifiedQuantity,
      @NotNull OffsetDateTime inspectedAt,
      @Schema(nullable = true) @Size(max = 2000) String note) {}

  /** 作废一条到货 / 质检记录（只追加表的唯一「改」动作，规则 R5）。 */
  public record VendorVoidInput(@NotBlank @Size(max = 2000) String reason) {}

  /** 追加 / 保存的结果。{@code version=0} 表示这是只追加事实表里的新行（到货与质检没有版本号）。 */
  public record VendorFactSaved(UUID id, @PositiveOrZero long version) {}

  // ================================================================ 采购视角详情（列表下钻）

  /** 一条到货记录（列表下钻用；6.1.1.2 的全景详情是另一个模块，不在这里复制）。 */
  public record VendorDeliveryRow(
      UUID id,
      String deliveryRef,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String skuCode,
      @Schema(nullable = true) String skuName,
      @Schema(nullable = true) String purchaseUnitCode,
      BigDecimal quantity,
      BigDecimal amount,
      String currency,
      @Schema(nullable = true) String orderedAt,
      LocalDate dueDate,
      LocalDate receivedDate,
      /** 由 received_date <= due_date 推出，**不落库**（规则 R5 的理由见 V137 第六节）。 */
      boolean onTime,
      int daysLate,
      boolean isVoid,
      @Schema(nullable = true) String voidReason,
      @Schema(nullable = true) String note) {}

  /** 一条质检记录。 */
  public record VendorQualityInspectionRow(
      UUID id,
      @Schema(nullable = true) UUID deliveryId,
      String inspectionRef,
      BigDecimal inspectedQuantity,
      BigDecimal qualifiedQuantity,
      @Schema(nullable = true) BigDecimal qualifiedRate,
      @Schema(nullable = true) String inspectedAt,
      boolean isVoid,
      @Schema(nullable = true) String voidReason,
      @Schema(nullable = true) String note) {}

  /**
   * 供应商的**采购视角**详情。
   *
   * <p>刻意不是「供应商详情」：6.1.1.2 才是那个模块（基础信息 + 账期 + 报价 + 采购历史 + 附件资质）。
   * 本 record 只回答采购侧的一件事 —— <b>这个供应商在我们这儿现在是什么状态、表现如何、事实是什么</b>，
   * 它是列表下钻的目标，不与 6.1.1.2 抢同一份内容（V137 第一节的同一纪律）。
   */
  public record VendorPurchaseView(
      VendorRow summary,
      List<VendorDeliveryRow> deliveries,
      List<VendorQualityInspectionRow> inspections,
      List<String> warnings,
      String note) {}
}
