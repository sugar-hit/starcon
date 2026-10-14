package com.starcon.catalog;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 3.7.1.1 HS 编码与申报要素的请求与响应记录。
 *
 * <p><b>为什么单独成文件而不是并进 {@code CatalogModels}</b>：{@code CatalogModels} 是 并行车道都会追加的公共文件，且它<b>不在</b>
 * {@code merge-union} 驱动器的按键求并范围内 （驱动器只覆盖 {@code packages/api-contracts} 与台账）。本模块的记录放进公共文件会让
 * 并行车道在同一处各追加一段，行级三方合并必然冲突。模块自成一个文件则天然无交集， 与 {@code PhysicalSafetyModels} 的做法一致。
 *
 * <p>蓝图 3.7.1.1： 「维护商品的 HS 编码、原产地、申报品名、申报价值参考、法定计量单位、是否享惠（RCEP 等）， 供 10 报关清关生成申报资料。」
 */
public final class CustomsAttributeModels {
  private CustomsAttributeModels() {}

  // ============================================================ 报关属性

  /**
   * 一个 SKU 的报关属性 + 申报要素 + 现算的可报关性判定。
   *
   * <p><b>{@code declarationReady} 是本模块最重要的一格</b>（V94 第一节）： {@code false} 表示这份申报资料**不可被 10
   * 报关引用**，与「字段填得不全」是<b>两件事</b>： 后者的下一步动作是「补齐字段」，前者的下一步动作是「走审核」。 {@code declarationBlockers}
   * 说清是哪一条不满足，让页面不靠猜。
   *
   * <p>{@code version} 必须回给调用方：前端拿它做 CAS， 不回传就等于让界面永远拿 0 去提交，第二次保存必然 409。
   */
  public record CustomsProfile(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      /** 主键即下游 {@code declaration_elements_ref} 的取值（UUID，36 字符）。 */
      @Schema(nullable = true) UUID attributeId,
      @Schema(nullable = true) String hsCode,
      @Schema(nullable = true) String originCountryCode,
      @Schema(nullable = true) String declaredName,
      /** 申报价值参考：<b>参考值不是申报值</b>（V94 零之三），可空。 */
      @Schema(nullable = true) BigDecimal declaredValueReference,
      @Schema(nullable = true) String statutoryUnitCode,
      @Schema(nullable = true) Boolean preferentialOriginEligible,
      @Schema(nullable = true) String preferenceAgreement,
      /** DRAFT / APPROVED / REJECTED；没有这一行时为 null（尚未维护）。 */
      @Schema(nullable = true) String status,
      @Schema(nullable = true) UUID submittedBy,
      @Schema(nullable = true) String submittedAt,
      @Schema(nullable = true) UUID reviewedBy,
      @Schema(nullable = true) String reviewedAt,
      @Schema(nullable = true) String rejectReason,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) Long version,
      List<DeclarationElement> elements,
      /** 审核通过申报要素才可被 10 报关引用；false 时 {@code blockers} 非空。 */
      boolean declarationReady,
      List<String> blockers,
      List<String> blockerLabels,
      String updatedAt) {}

  /**
   * 保存报关属性与申报要素的入参。
   *
   * <p><b>一次提交同时写两张表</b>：属性与申报要素是同一份申报资料的两面， 运营在页面上也是一次填完。分成两个端点只会让「填了 HS 码忘了填申报要素」 变成常态，而那正是规则 A4
   * 要堵的洞。
   *
   * <p>{@code elements} 传的是<b>完整清单</b>而不是增量：申报要素的位次唯一 （{@code
   * UNIQUE(tenant_id,customs_attribute_id,ordinal)}）与「有没有 required 空值」
   * 都是跨行不变量，增量提交时前端要自己算最终集合，漏对账会留下一组幽灵位次。
   */
  public record CustomsInput(
      @NotBlank @Size(max = 16) String hsCode,
      @NotBlank @Size(max = 2) String originCountryCode,
      @NotBlank @Size(max = 200) String declaredName,
      @Schema(nullable = true) @DecimalMin("0") BigDecimal declaredValueReference,
      @NotBlank @Size(max = 8) String statutoryUnitCode,
      boolean preferentialOriginEligible,
      @Schema(nullable = true) @Size(max = 32) String preferenceAgreement,
      @Schema(nullable = true) @Size(max = 1000) String note,
      @NotNull @Valid List<@NotNull DeclarationElementInput> elements,
      @PositiveOrZero long version) {}

  /** 申报要素项入参。{@code ordinal} 从 1 起（DB 有 CHECK 钉死 &gt; 0）。 */
  public record DeclarationElementInput(
      @Positive int ordinal,
      @NotBlank @Size(max = 32) String elementCode,
      @NotBlank @Size(max = 200) String elementName,
      @Schema(nullable = true) @Size(max = 2000) String elementValue,
      boolean required) {}

  /**
   * 申报要素项的读出形状。
   *
   * <p>{@code required} 与 {@code elementValue} 为空<b>是两件事</b>： 「必填且已填」与「必填但还空着」在报关单上是两个完全不同的状态，
   * 页面必须能分开渲染，规则 A4 才拦得住。
   */
  public record DeclarationElement(
      UUID id,
      int ordinal,
      String elementCode,
      String elementName,
      String elementValue,
      boolean required,
      /** 必填却还空着：规则 A4 的未满足项，页面高亮它。 */
      boolean incomplete) {}

  /**
   * 写入结果。回传写入后的档案，界面不必为了看结果再拉一次。
   *
   * <p><b>名字里带 {@code Profile} 是被迫的</b>：契约生成器把**所有服务**的 schema 拍平进同一个 {@code
   * packages/api-contracts/src/catalog.ts}，而 10.5.1.1 （warehouse 的报关单据）已经有一个叫 {@code CustomsSaved}
   * 的记录。重名时 后合并的 warehouse 会把本模块的这个**静默覆盖**掉，于是 {@code catalog.ts} 里 那个 {@code CustomsSaved}
   * 指的根本不是本端点的返回形状 —— 前端 import 到它会拿到字段完全不相干的类型，且 typecheck 全绿。 改名只影响 OpenAPI 的 component 名字，JSON
   * 字段名仍是 {@code profile}，接口行为不变。
   */
  public record CustomsProfileSaved(CustomsProfile profile) {}

  // ============================================================ 审核

  /** 提交审核的入参。乐观锁凭据防止「基于旧版本提交」。 */
  public record SubmitInput(@PositiveOrZero long version) {}

  /**
   * 审核入参：{@code approve=true} 通过，{@code false} 驳回。
   *
   * <p><b>{@code rejectReason} 在驳回时必填</b>（DB CHECK 钉死）：驳回不写理由， 运营就只能猜要怎么改，改完再提交还是被驳回。
   */
  public record ReviewInput(
      boolean approve, @Schema(nullable = true) @Size(max = 1000) String rejectReason) {}

  // ============================================================ 筛选

  /**
   * 按报关属性筛选（供 16 合规税则校验与 10 报关批量核）。
   *
   * <p>{@code status} 与 {@code preferenceAgreement} 都可以筛空值： {@code preferenceAgreement=""}
   * 显式表示「筛不适用任何协定的那些」， 而不传表示「不筛」—— 运营真的会问「哪些货完全不走协定」。
   */
  public record CustomsQuery(
      String keyword,
      @Schema(nullable = true) String hsCode,
      @Schema(nullable = true) String originCountryCode,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String preferenceAgreement,
      @Schema(nullable = true) Boolean preferentialOriginEligible,
      int page,
      int size) {}

  /** 筛选结果行。带审核状态与申报要素计数，供审核队列与税则校验页直接读。 */
  public record CustomsRow(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      String hsCode,
      String originCountryCode,
      String declaredName,
      @Schema(nullable = true) BigDecimal declaredValueReference,
      String statutoryUnitCode,
      boolean preferentialOriginEligible,
      String preferenceAgreement,
      @Schema(nullable = true) String status,
      int elementCount,
      int requiredMissingCount,
      @Schema(nullable = true) String submittedAt,
      @Schema(nullable = true) String updatedAt) {}

  public record CustomsPage(
      List<CustomsRow> items, int page, int size, long total, int totalPages) {}

  // ============================================================ 供 10 报关引用

  /**
   * 供 10 报关调用的申报资料（{@code GET /declaration-ready}）。
   *
   * <p><b>本记录是给 V85 {@code customs_declaration_line} 预留的回填口</b>： 字段命名与语义与那一行逐一对应，回填时不需要做名称翻译。
   * 该迁移的文件头第四节写明「3.7.1.1 交付后由它回填 jsonb 并把来源改成 MODULE_3_7_1_1，<b>不需要改结构</b>」—— 前提就是本记录里的键名对得上。
   *
   * <p><b>{@code declarationElements} 已经是 jsonb 对象形态</b> （键 = {@code elementCode}、值 = {@code
   * elementValue}，按 ordinal 有序生成）， 正好满足 V85 的 {@code
   * CHECK(jsonb_typeof(declaration_elements)='object')}。 本表内部仍然用<b>有序行</b>存储有序性 —— 拍平只发生在响应上（V94
   * 第一节）。
   *
   * <p><b>不含申报货值</b>：V85 的 {@code declared_value} / {@code declared_unit_value} 取决于实际成交价与汇率快照，那是 10
   * 报关的事实。本记录只给 {@code declaredValueReference} 作为参考值，并明确注明它不是申报值。
   */
  public record DeclarationReady(
      UUID skuId,
      String skuCode,
      String skuName,
      /** 回填 {@code declaration_elements_ref} 的定位键（= 本表 id）。 */
      String declarationElementsRef,
      String hsCode,
      /** 回填 {@code hs_code_source}：本端点只对 APPROVED 放行，所以恒为 MODULE_3_7_1_1。 */
      String hsCodeSource,
      /** 回填 {@code hs_code_verified}：APPROVED 就是「这个码被人审过」的可信来源。 */
      boolean hsCodeVerified,
      /** 回填 {@code declared_item_name}。 */
      String declaredName,
      /** 回填 {@code origin_country_code}。 */
      String originCountryCode,
      /** 回填 {@code uom}：法定计量单位代码。 */
      String statutoryUnitCode,
      /** 回填 {@code declaration_elements}（已是 jsonb 对象形态）。 */
      DeclarationElementsObject declarationElements,
      /** 回填 {@code declaration_elements_source}：恒为 MODULE_3_7_1_1。 */
      String declarationElementsSource,
      /** 回填 {@code declaration_element_status}：本端点只对齐备的行放行，恒为 COMPLETE。 */
      String declarationElementStatus,
      /** 回填 {@code preferential_origin_eligible} 相关口径的**参考值**，不是申报货值。 */
      @Schema(nullable = true) BigDecimal declaredValueReference,
      boolean preferentialOriginEligible,
      String preferenceAgreement,
      /** 审核留痕：报关税据要能回答「谁在什么时候放行了这个申报事实」。 */
      UUID reviewedBy,
      String reviewedAt,
      String note) {}

  /**
   * jsonb 对象形态的申报要素。
   *
   * <p><b>同时带 {@code entries} 与 {@code byCode}</b>：{@code entries} 是有序的
   * （报关单要显示与人工核对，顺序本身是业务事实），{@code byCode} 是可直接落库的 jsonb 键值对象。一个类型给两种形态，免得下游自己拼 jsonb —— 那正是 V85
   * 刻意不写死键名要避免的「上游拼一份、库里存一份」的漂移。
   */
  public record DeclarationElementsObject(
      List<DeclarationElement> entries, Map<String, String> byCode) {}
}
