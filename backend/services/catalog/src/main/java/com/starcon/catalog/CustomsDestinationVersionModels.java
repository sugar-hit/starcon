package com.starcon.catalog;

import com.starcon.catalog.CustomsAttributeModels.DeclarationElementsObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 3.7.1.2 目的地报关版本的请求与响应记录。
 *
 * <p><b>为什么单独成文件而不是并进 {@code CatalogModels}</b>：理由与
 * {@link CustomsAttributeModels} 完全相同 —— {@code CatalogModels} 是并行车道都会追加的
 * 公共文件，模块自成一个文件则天然无交集，与 {@code PhysicalSafetyModels} 同一做法。
 *
 * <p>蓝图 3.7.1.2：
 * 「维护同一 SKU 在不同目的地国家的报关差异（不同税号、不同申报品名、不同认证要求），
 * 按目的地版本化。」「输出：按目的地查询的有效报关版本。」
 * 「<b>不同目的地必须用不同版本，不能用一套申报资料打通全球</b>。」
 *
 * <p><b>命名纪律（codegen 撞名）</b>：契约生成器把<strong>所有服务</strong>的 schema
 * 拍平进同一个 {@code packages/api-contracts/src/catalog.ts}，同名 component 会被
 * 后合并的服务<strong>静默覆盖</strong>（3.7.1.1 就撞过一次 {@code CustomsSaved}：
 * warehouse 的报关单据记录把 catalog 的同名记录顶掉了，而 typecheck 全绿）。
 * 所以本文件的记录名一律带 {@code DestinationVersion} / {@code ResolvedDeclaration}
 * 这一层限定词，且交付前在 catalog / warehouse / identity / channel / trade
 * 五个服务的 Java 源码里全局搜过一遍确认未被占用。
 */
public final class CustomsDestinationVersionModels {
  private CustomsDestinationVersionModels() {}

  // ============================================================ 读取：某目的地的版本面板

  /**
   * 一个 SKU 在<b>一个目的地国家</b>的报关版本面板。
   *
   * <p>{@code base} 是 3.7.1.1 的基础报关属性摘要 —— 它存在的唯一理由是让运营在同一个
   * 画面里看到「基础是什么、这个目的地改了什么」，因为本模块的核心语义是
   * <b>覆盖与继承</b>（V95 第二节）：只列差异列而不列基础值，运营无法判断
   * 「这个格子空着」是「沿用基础」还是「忘了填」。
   *
   * <p>{@code effectiveVersionId} 为 {@code null} 是一个<b>真实且要紧的状态</b>：
   * 该目的地此刻没有生效版本。此时 10 报关会取不到数（后端 409），而界面必须
   * <b>显式说出来</b>，不能让它看起来像「这个目的地不需要差异声明」。
   */
  public record DestinationVersionProfile(
      UUID skuId,
      String skuCode,
      String skuName,
      String spuCode,
      /** 本次查询的目的地（已归一为两位大写）。 */
      String destinationCountryCode,
      /** 基础报关属性摘要；{@code baseAttributeId} 为 null = 这个 SKU 还没维护基础属性。 */
      BaseAttribute base,
      List<DestinationVersionRow> versions,
      /** 此刻生效的版本 id；null = 该目的地此刻没有生效版本（后端会 409，不是错误）。 */
      @Schema(nullable = true) UUID effectiveVersionId,
      /** 是否存在任何草稿（工作区据此决定要不要提示「还有没发布的草稿」）。 */
      int draftCount,
      String updatedAt) {}

  /**
   * 3.7.1.1 基础报关属性的只读摘要。
   *
   * <p><b>刻意只有五格</b>：目的地版本能覆盖的（税号、品名）与它不能覆盖但界面必须
   * 让运营看到的（法定计量单位、基础审核态）。申报要素不在这里 ——
   * 它是 {@link ResolvedDeclaration} 才给的东西，因为工作区不编辑它（V95 第二节：
   * 申报要素只来自基础属性，本模块**不复制**它）。
   */
  public record BaseAttribute(
      @Schema(nullable = true) UUID attributeId,
      @Schema(nullable = true) String hsCode,
      @Schema(nullable = true) String declaredName,
      @Schema(nullable = true) String statutoryUnitCode,
      /** DRAFT / APPROVED / REJECTED；没有这一行时为 null（尚未维护）。 */
      @Schema(nullable = true) String status,
      /** 基础属性是否已审核通过：未通过时目的地版本再完整也不能用于报关。 */
      boolean baseApproved) {}

  /**
   * 一个目的地版本。
   *
   * <p>{@code effective} 是<b>此刻</b>是否生效（规则 R4），与 {@code status} 是两件事：
   * 一个已发布的版本可能早已被下一个接替而不再生效，界面上必须分开显示，
   * 否则运营会把「历史版本」当成「现在该用的版本」。
   *
   * <p>{@code requirements} 内联返回而不是另开一次请求：运营编辑一个草稿时
   * 需要同时看到税号、品名与认证要求，分成两次取数会看到互相不一致的两个瞬间。
   */
  public record DestinationVersionRow(
      UUID id,
      int versionNo,
      String destinationCountryCode,
      /** 目的地税号；空串 = 沿用基础 HS 编码（V95 第二节）。 */
      String destinationHsCode,
      /** 目的地申报品名；空串 = 沿用基础申报品名。 */
      String destinationDeclaredName,
      /** 该目的地是否承认这个优惠（与基础的「有资格」是两件事）。 */
      boolean preferentialApplicable,
      String status,
      @Schema(nullable = true) UUID publishedBy,
      @Schema(nullable = true) String publishedAt,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      /** 此刻是否生效（规则 R4）。 */
      boolean effective,
      String note,
      Long version,
      List<DestinationRequirement> requirements,
      String updatedAt) {}

  /**
   * 一个认证要求（CE / FCC / RoHS / UKCA / 危包证 …）。
   *
   * <p>{@code required=false} 不是「不需要」而是「可选」：
   * 「已确认不需要」与「不知道要不要」在报关单上是两个不同状态，
   * 16 合规要能分开渲染，所以这是一个显式布尔而不是「没有这一行」。
   */
  public record DestinationRequirement(
      UUID id,
      String requirementCode,
      String requirementName,
      boolean required,
      String note) {}

  /**
   * 某目的地某时刻的有效版本（规则 R4）+ 它的认证要求。
   *
   * <p><b>没有生效版本时后端一律报错，不返回空列表</b>（V95 第零节第 2 条）：
   * 静默回退到基础属性看起来是优雅降级，实际后果是运营没维护 DE 的版本、
   * 系统却拿 CN 的税号申报到德国，而错误永远不会被看到。
   */
  public record EffectiveDestinationVersion(
      UUID skuId,
      String destinationCountryCode,
      UUID destinationVersionId,
      int versionNo,
      /** 本次判定用的时刻（ISO-8601）。10 报关要能回填「按哪一刻的版本报的」。 */
      String effectiveAt,
      String destinationHsCode,
      String destinationDeclaredName,
      boolean preferentialApplicable,
      @Schema(nullable = true) UUID publishedBy,
      @Schema(nullable = true) String publishedAt,
      List<DestinationRequirement> requirements) {}

  /**
   * 认证要求清单（{@code GET /requirements}），供 16 合规的认证台账读。
   *
   * <p>刻意带上 {@code requiredOnlyCount}：16 合规关心的从来不是「这个目的地列了几项」
   * 而是「其中几项是强制而当前没有证书的」，两个数字都得在一次取数里拿到。
   */
  public record DestinationRequirementList(
      UUID skuId,
      String destinationCountryCode,
      UUID destinationVersionId,
      int versionNo,
      String effectiveAt,
      List<DestinationRequirement> items,
      int requiredOnlyCount) {}

  // ============================================================ 写入

  /**
   * 保存一个目的地版本（草稿态）的入参。
   *
   * <p><b>一次提交同时写版本与认证要求全量清单</b>：税号、品名与认证要求是同一份
   * 目的地申报资料的三面，运营在页面上也是一次填完。分成两个端点只会让
   * 「填了 DE 的税号忘了填 UKCA」变成常态，而那正是货到港被扣的那一类。
   *
   * <p>{@code requirements} 传<b>完整清单</b>而不是增量：
   * 「同一版本里同一个认证码唯一」（{@code UNIQUE(tenant_id,destination_version_id,
   * requirement_code)}）是跨行不变量，增量提交时前端要自己算最终集合，
   * 漏对账会留下一组幽灵认证码。
   *
   * <p><b>这里没有申报要素、也没有法定计量单位</b>，且这是刻意的：
   * V95 第二节写死「这两项只能来自基础属性，本模块不复制」。
   * 入参里出现它们就等于在 API 层复制了第二个事实来源。
   */
  public record DestinationVersionInput(
      @NotBlank @Size(max = 2) String destinationCountryCode,
      @Min(1) int versionNo,
      /** 目的地税号；留空 = 沿用基础 HS 编码。 */
      @Schema(nullable = true) @Size(max = 16) String destinationHsCode,
      /** 目的地申报品名；留空 = 沿用基础申报品名。 */
      @Schema(nullable = true) @Size(max = 500) String destinationDeclaredName,
      boolean preferentialApplicable,
      @NotNull OffsetDateTime effectiveFrom,
      /** 留空 = 一直有效（+∞，规则 R1）。 */
      @Schema(nullable = true) OffsetDateTime effectiveTo,
      @Schema(nullable = true) @Size(max = 1000) String note,
      @NotNull @Valid List<@NotNull DestinationRequirementInput> requirements,
      @PositiveOrZero long version) {}

  /** 认证要求入参。 */
  public record DestinationRequirementInput(
      @NotBlank @Size(max = 64) String requirementCode,
      @Schema(nullable = true) @Size(max = 200) String requirementName,
      boolean required,
      @Schema(nullable = true) @Size(max = 500) String note) {}

  /**
   * 发布的入参。乐观锁凭据防止「基于旧版本发布」。
   *
   * <p><b>发布没有别的字段</b>：发布不是「改点什么再发」，它是一个<b>状态流转</b>
   * 并留下发布留痕。给它加上字段等于让「我只是想改个字」与「我把它交上去了」
   * 在界面上无法区分（与 3.7.1.1 的 SubmitInput 同一形状）。
   */
  public record DestinationPublishInput(@PositiveOrZero long version) {}

  /**
   * 写入结果。回传写入后的版本行，界面不必为了看结果再拉一次。
   *
   * <p><b>名字里带 {@code DestinationVersion} 是被迫的</b>：见类注释的 codegen 撞名说明
   * —— 所有服务的 schema 会拍平进同一个 {@code catalog.ts}，重名会被后合并的服务
   * 静默覆盖。改名只影响 OpenAPI 的 component 名字，JSON 字段名仍是 {@code row}。
   */
  public record DestinationVersionSaved(DestinationVersionRow row) {}

  // ============================================================ 供 10 报关引用：合成后的申报资料

  /**
   * <b>合成基础属性 + 目的地覆盖 = 一份可直接申报的资料</b>
   * （{@code GET /catalog/customs-declaration/resolve}）——本模块对下游的核心价值。
   *
   * <p><b>字段命名与 V85 的 {@code customs_declaration_line} 逐一对齐</b>，
   * 回填时不需要做名称翻译：
   *
   * <table>
   *   <caption>回填对应关系</caption>
   *   <tr><td>{@code declaredName}</td><td>declared_item_name（<b>已按目的地覆盖</b>）</td></tr>
   *   <tr><td>{@code hsCode}</td><td>hs_code（<b>已按目的地覆盖</b>）</td></tr>
   *   <tr><td>{@code hsCodeSource}</td><td>hs_code_source（MODULE_3_7_1_1 / MODULE_3_7_1_2）</td></tr>
   *   <tr><td>{@code statutoryUnitCode}</td><td>uom（<b>只来自基础属性</b>，目的地不覆盖）</td></tr>
   *   <tr><td>{@code declarationElements}</td><td>declaration_elements（<b>只来自基础属性</b>，jsonb 对象形态）</td></tr>
   *   <tr><td>{@code declarationElementsRef}</td><td>declaration_elements_ref（= 3.7.1.1 的 id）</td></tr>
   * </table>
   *
   * <p><b>两格「来源标记」是本记录存在的技术理由</b>：覆盖之后，「这个税号是谁给的」
   * 必须在返回值里说清楚 —— 10 报关要把它落到 {@code hs_code_source}，
   * 而事后追责「这份资料是从基础属性继承的还是目的地版本覆盖的」也只有这一格能回答。
   * {@code *Overridden} 三态布尔则让界面能把「这一格被这个目的地改过」显式高亮出来，
   * 因为继承与覆盖在页面上长得一模一样正是运营漏维护目的地的起点。
   *
   * <p><b>不含申报货值</b>：V85 的 {@code declared_value} / {@code declared_unit_value}
   * 取决于实际成交价与汇率快照，那是 10 报关的事实。本模块只给
   * {@code declaredValueReference} 作为参考值，并明确注明它不是申报值。
   */
  public record ResolvedDeclaration(
      UUID skuId,
      String skuCode,
      String skuName,
      String destinationCountryCode,
      /** 生效的目的地版本 id（定位键；10 报关可填进自己的版本引用列，UUID 36 字符）。 */
      String destinationVersionRef,
      int destinationVersionNo,
      /** 本次合成用的时刻（ISO-8601）：回填时要知道「按哪一刻的版本报的」。 */
      String effectiveAt,
      /** 回填 declared_item_name（已按目的地覆盖）。 */
      String declaredName,
      /** 本格来自哪一层：MODULE_3_7_1_2 = 目的地覆盖，MODULE_3_7_1_1 = 继承基础。 */
      String declaredNameSource,
      boolean declaredNameOverridden,
      /** 回填 hs_code（已按目的地覆盖）。 */
      String hsCode,
      /** 回填 hs_code_source。 */
      String hsCodeSource,
      /** 回填 hs_code_verified：合成链路上基础属性必须已审核通过，所以恒为 true。 */
      boolean hsCodeVerified,
      boolean hsCodeOverridden,
      /** 回填 origin_country_code（**只来自基础属性**，目的地不覆盖）。 */
      String originCountryCode,
      /** 回填 uom：法定计量单位，**只来自基础属性**（V95 第二节）。 */
      String statutoryUnitCode,
      /** 回填 declaration_elements（**只来自基础属性**，已是 jsonb 对象形态）。 */
      DeclarationElementsObject declarationElements,
      /** 回填 declaration_elements_source：恒为 MODULE_3_7_1_1（要素永不来自目的地版本）。 */
      String declarationElementsSource,
      /** 回填 declaration_element_status：合成链路只对要素齐备的基础属性放行，恒为 COMPLETE。 */
      String declarationElementStatus,
      /** 回填 declaration_elements_ref：= 3.7.1.1 的 customs_attribute.id，与该模块一致。 */
      String declarationElementsRef,
      /** 参考值，不是申报货值（V85 的 declared_value 取决于成交价与汇率快照）。 */
      @Schema(nullable = true) java.math.BigDecimal declaredValueReference,
      /** 基础属性说的「有资格」（3.7.1.1 的 preferential_origin_eligible）。 */
      boolean preferentialOriginEligible,
      /** 目的地版本说的「该目的地承认与否」。 */
      boolean preferentialApplicable,
      /** <b>两者都满足才为 true</b> —— 这是 12 财务判断能否用协定税率的依据。 */
      boolean preferentialGranted,
      String preferenceAgreement,
      List<DestinationRequirement> requirements,
      /** 发布留痕：报关单上出问题时要能回答「谁在什么时候发布了这个目的地版本」。 */
      @Schema(nullable = true) UUID destinationPublishedBy,
      @Schema(nullable = true) String destinationPublishedAt,
      /** 基础属性的审核留痕（3.7.1.1 那一层）。 */
      @Schema(nullable = true) UUID baseReviewedBy,
      @Schema(nullable = true) String baseReviewedAt,
      String note) {}
}
