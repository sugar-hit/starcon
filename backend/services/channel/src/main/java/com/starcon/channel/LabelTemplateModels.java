package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 10.2.2.2 面单模板 的传输模型。
 *
 * <p>蓝图：「维护面单模板与打印规格（尺寸、条码类型、附加声明、电池标识、语言版本），
 * 支持按渠道与国家配置。」输出「模板 + 打印任务（经 8.3.4.2）」，并加粗
 * 「**附加声明（如电池、液体）漏打会导致清关问题**，模板配置要与渠道规则强绑定」。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（9.6.1.3 已踩过）。
 *
 * <p>🔴 尺寸与几何一律 {@link BigDecimal}（V141 里是 {@code NUMERIC(8,2)}，单位 mm），
 * **没有 double**：一张 100×150mm 的面单用 float 存，会在毫米→dot 换算里攒出 ±1 dot，
 * 而 1 dot 在 203dpi 下是 0.125mm —— 条码高度被吃掉一角，扫码枪开始读不出。
 *
 * <p>🔴 {@link LabelDeclaration} 与 {@link LabelBatteryMark} 都**必须**指向一个真实的
 * 打印元素（{@code elementKey}）。「声明挂在哪个位置上」是这张模板唯一能回答
 * 「它到底印没印出来」的方式 —— 这就是蓝图加粗句在本模块的物理落点。
 */
public final class LabelTemplateModels {
  private LabelTemplateModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」**。
   */
  public record LabelCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record LabelTemplateCapabilities(
      List<LabelCapabilityDimension> dimensions,
      List<String> labelFormats,
      List<String> symbologies,
      List<String> barcodeKinds,
      List<String> elementKinds,
      List<String> declarationKinds,
      List<String> batteryTypes,
      List<String> languageCodes,
      List<String> statusCodes,
      List<String> storedReadinessCodes,
      List<String> effectiveReadinessCodes,
      List<String> verdictCodes,
      String boundaryNote,
      String bindingNote) {}

  // ================================================================ 模板与子表

  /** 打印元素：纸上印什么、印在哪（mm）。 */
  public record LabelElement(
      String elementKey,
      String elementKind,
      @Schema(nullable = true) String symbology,
      BigDecimal xMm,
      BigDecimal yMm,
      BigDecimal wMm,
      BigDecimal hMm,
      @Schema(nullable = true) Integer fontPt,
      Integer rotation,
      boolean mandatory,
      Integer sequence,
      String note) {}

  /** 附加声明（电池 / 液体 / 磁力 / 危险品 …）。**必须**指向一个存在的 {@code elementKey}。 */
  public record LabelDeclaration(
      String declarationKind,
      String declarationCode,
      String displayText,
      String elementKey,
      boolean mandatory,
      String note) {}

  /** 电池标识：瓦时 / 重量 / UN38.3 / 标识符号，同样锚定到一个打印位。 */
  public record LabelBatteryMark(
      String batteryType,
      @Schema(nullable = true) BigDecimal wattHours,
      @Schema(nullable = true) BigDecimal batteryWeightKg,
      boolean un38_3Attested,
      String markingSymbol,
      String elementKey,
      String note) {}

  /**
   * 面单模板的一行。
   *
   * <p>{@code channelCode} / {@code channelName} / {@code carrierName} 是**同库只读
   * 10.1.1.2 渠道配置**（V136）得到的展示快照，**不可编辑** —— 改渠道身份要回
   * /logistics/channels 去做，本模块的写请求只提交 {@code channelId}。
   *
   * <p>🔴 {@code readiness}（落库的本地齐备度，DB 触发器守护）与 {@code effectiveReadiness}
   * （读取时合成的「现在能不能用」）<b>刻意是两个字段</b>：后者多看 10.1.1.2 那边的
   * 渠道可用性，而渠道被停用时本表一行都不会被写、DB 触发器也就不会跑 ——
   * 只给一个字段的话，页面会一直显示「齐备」，直到有人真去印一张才发现渠道已经停了。
   */
  public record LabelTemplateRow(
      UUID id,
      // === 上游 10.1.1.2 只读引用 ===
      UUID channelId,
      String channelCode,
      String channelName,
      String carrierName,
      String carrierCode,
      String channelReadiness,
      String channelStatus,
      // === 本模块主权 ===
      String templateCode,
      String templateName,
      String countryCode,
      String languageCode,
      String status,
      BigDecimal paperWidthMm,
      BigDecimal paperHeightMm,
      int dpi,
      String orientation,
      BigDecimal originOffsetXMm,
      BigDecimal originOffsetYMm,
      String primaryBarcodeSymbology,
      BigDecimal primaryBarcodeHeightMm,
      boolean humanReadable,
      List<LabelElement> elements,
      List<LabelDeclaration> declarations,
      List<LabelBatteryMark> batteryMarks,
      // === 判定 ===
      String readiness,
      String readinessMessage,
      String effectiveReadiness,
      String effectiveReadinessMessage,
      boolean activatable,
      String note,
      long version,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 列表分页信封；{@code summary} 不受 limit/offset 影响。 */
  public record LabelTemplatePage(
      List<LabelTemplateRow> items,
      int page,
      int size,
      long total,
      int totalPages,
      LabelTemplateSummary summary) {}

  /** 筛选结果上的真聚合（独立查询，不随 limit/offset 变）。 */
  public record LabelTemplateSummary(
      long total,
      long ready,
      long noLayout,
      long noBarcode,
      long declarationGap,
      long noChannel,
      long draft,
      long active,
      long withBatteryMark,
      long multiLanguage) {}

  /** 概览：全量口径的格式 / 条码类型 / 语言分布，不受列表筛选与分页影响。 */
  public record LabelTemplateCount(String code, String label, long count) {}

  public record LabelTemplateOverview(
      LabelTemplateSummary summary,
      List<LabelTemplateCount> bySymbology,
      List<LabelTemplateCount> byLanguage,
      /**
       * 附加声明分布 —— 刻意**没有** byFormat：模板表没有输出格式列（见 V141 注释），
       * 渲染产物的格式记在 render 行上。加一个空的 byFormat 等于给界面一个永远为 0 的
       * 分布，比没有更糟。
       */
      List<LabelTemplateCount> byDeclarationKind,
      String note) {}

  // ================================================================ 写入口

  /**
   * 登记 / 改一个面单模板。
   *
   * <p>整体替换语义：打印元素、附加声明、电池标识三项都按提交的整份替换。少发一条
   * 就是静默丢失 —— 尤其是<b>强制附加声明</b>，少发一条意味着一个本该被拦的组合放行了，
   * 所以前端必须提交全集。
   *
   * <p>⚠️ <b>子表必须先于主表</b>：readiness 的触发器是在主表每一行写入时按<b>当时</b>的
   * 子表重算比对的，所以主表那次写入看到的必须是最终形态。顺序反了，触发器会在
   * 中间那次写入上拒掉一个其实正确的中间态。
   *
   * <p>{@code templateId} 为空 = 新建（此时 {@code channelId} 必填）；非空 = 改模板，
   * 此时模板代码与所属渠道都不可改（模板代码由 DB 层 trigger b 钉死，所属渠道由服务层
   * 比对），{@code version} 是乐观锁。
   */
  public record SaveLabelTemplateRequest(
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) UUID channelId,
      @NotBlank @Size(max = 64) String templateCode,
      @NotBlank @Size(max = 200) String templateName,
      @NotBlank @Size(min = 2, max = 2) String countryCode,
      @NotBlank @Size(max = 10) String languageCode,
      @NotBlank @Size(max = 16) String status,
      @NotNull @DecimalMin("0.01") BigDecimal paperWidthMm,
      @NotNull @DecimalMin("0.01") BigDecimal paperHeightMm,
      @NotNull Integer dpi,
      @NotBlank @Size(max = 16) String orientation,
      @Schema(nullable = true) BigDecimal originOffsetXMm,
      @Schema(nullable = true) BigDecimal originOffsetYMm,
      @NotBlank @Size(max = 16) String primaryBarcodeSymbology,
      @NotNull @DecimalMin("0.01") BigDecimal primaryBarcodeHeightMm,
      boolean humanReadable,
      @NotNull @Size(max = 60) List<LabelElement> elements,
      @NotNull @Size(max = 20) List<LabelDeclaration> declarations,
      @NotNull @Size(max = 10) List<LabelBatteryMark> batteryMarks,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  // ==================================================================
  // 启用 / 停用
  // ==================================================================

  /**
   * 启用 / 停用 / 归档。
   *
   * <p>{@code version} 是乐观锁 —— 齐备度是靠三张子表算出来的，两个人同时改版面时
   * 后一个保存的人不该看到一份自己没排过的版式。
   */
  public record SetLabelStatusRequest(
      @NotBlank @Size(max = 16) String status, @PositiveOrZero long version) {}

  // ==================================================================== 渲染预览

  /** 一个打印位在预览里要印的值（**用具名 record 而不是 Map**，理由见类注释）。 */
  public record LabelSampleValue(String elementKey, String value) {}

  /**
   * 真渲染预览请求。
   *
   * <p>预览必须带**真实形状**的数据：一张全是空白格的预览证明不了任何事，
   * 而「元素印出来是空的」恰恰是漏打声明最常见的表现形式。
   */
  public record LabelPreviewRequest(
      @NotNull @Size(max = 60) List<LabelSampleValue> elementValues,
      /** 电池条上印的瓦时；与登记的电池标识比对，不一致会出一条 REVIEW。 */
      @Schema(nullable = true) @PositiveOrZero BigDecimal wattHours,
      /** 预览结果是否落库（默认落库：渲染历史是启用前的证据链）。 */
      boolean persist) {}

  /**
   * 一条判定明细。
   *
   * <p><b>逐条给出，不合并成一句话</b> —— 运营需要知道是「电池」还是「液体」没打，
   * 而不是一个笼统的「不合规」。
   */
  public record LabelFinding(
      /** BLOCK 不可用（清关会被打回）· REVIEW 需人工确认（不拦，但不允许自动放行） */
      String severity,
      String code,
      String message) {}

  /**
   * 真渲染结果。
   *
   * <p>{@code zplContent} 是**真的热敏指令流**（{@code ^XA … ^XZ} 包边，条码用
   * {@code ^BC} / {@code ^BQ}），现场机器可以直接吃；{@code barcodeModules} 是一维码
   * 真实的模块宽度序列（含校验位），可拿去核对条码机算得对不对。
   */
  public record LabelRenderPreview(
      UUID templateId,
      String templateCode,
      String verdict,
      String verdictMessage,
      List<LabelFinding> findings,
      long blockCount,
      long reviewCount,
      String labelFormat,
      String symbology,
      String encodedValue,
      String barcodeModules,
      String zplContent,
      String contentSha256,
      boolean persisted,
      String previewNote) {}

  /** 渲染历史的一行（分页读）。 */
  public record LabelRenderRow(
      UUID id,
      String triggerSource,
      String symbology,
      String verdict,
      long findingCount,
      String contentSha256,
      String zplContent,
      OffsetDateTime renderedAt) {}

  public record LabelRenderPage(
      List<LabelRenderRow> items, int page, int size, long total, int totalPages) {}

  // ================================================================ 渠道规则强绑定

  /**
   * 渠道规则绑定核对请求。
   *
   * <p>这就是蓝图加粗句「模板配置要与渠道规则强绑定」的判定入口：给定渠道 + 国家 +
   * 货件属性，问出「哪些模板能用来打这一票、哪个必须补哪个附加声明」。
   *
   * <p>刻意做成**一次请求带多件货件属性 + 服务端判定**，而不是「先把渠道限制字段读回去
   * 自己判断」：后者就是那个判断规则会散落到每个调用方、而总有一个调用方会漏掉
   * 「液体」这一条的假活。
   */
  public record LabelBindingRequest(
      @Schema(nullable = true) UUID channelId,
      @NotBlank @Size(min = 2, max = 2) String countryCode,
      /** 货件涉及品类编码（与渠道的禁限品类比对，决定这条货件能不能走这个渠道）。 */
      @NotNull @Size(max = 50) List<@Size(max = 64) String> categoryCodes,
      boolean containsBattery,
      boolean containsLiquid,
      boolean containsMagnet,
      /** 指定只看某几张模板（留空 = 判定这个渠道该国家下全部模板）。 */
      @Schema(nullable = true) @Size(max = 100) List<UUID> templateIds) {}

  /** 一张模板对某一票货的绑定结论。 */
  public record LabelBindingVerdict(
      UUID templateId,
      String templateCode,
      String templateName,
      String status,
      String effectiveReadiness,
      /** PASS 可用于这一票 · REVIEW 需人工确认 · BLOCK 不可用 */
      String verdict,
      /** 渠道要求必须有、而这张模板<b>没有</b>声明的种类，逐条点名。 */
      List<String> missingDeclarations,
      /** 渠道要求必须有、而这张模板**没有**电池标识的种类。 */
      List<String> missingBatteryMarks,
      /** 这张模板多印了、但本票货性用不到的声明（不拦，但要让人看见）。 */
      List<String> redundantDeclarations,
      List<LabelFinding> findings) {}

  public record LabelBindingResult(
      List<LabelBindingVerdict> verdicts,
      long pass,
      long review,
      long block,
      String note) {}

  // ================================================================ 判定口径

  /**
   * 判定三档的<b>单一实现</b>（预览端点与绑定核对端点都调它）。
   *
   * <p>三档而不是「是/否」两档，是因为把「需要人看一眼」混进「可以自动放行」，
   * 就等于把清关风险交给了运气。
   */
  public final class LabelVerdict {
    private LabelVerdict() {}

    public static final String PASS = "PASS";
    public static final String REVIEW = "REVIEW";
    public static final String BLOCK = "BLOCK";

    /** 三档里更严重的一档（BLOCK &gt; REVIEW &gt; PASS）。 */
    public static String worse(String left, String right) {
      return rank(left) >= rank(right) ? left : right;
    }

    private static int rank(String verdict) {
      return switch (verdict) {
        case BLOCK -> 2;
        case REVIEW -> 1;
        default -> 0;
      };
    }

    /** 给界面看的一句话解释；与三档一一对应，不猜。 */
    public static String message(String verdict) {
      return switch (verdict) {
        case BLOCK -> "不可用：漏打会导致清关问题（整批货可能被扣下退回）";
        case REVIEW -> "需人工确认：不会自动放行";
        default -> "可用于这一票";
      };
    }
  }

  /**
   * 元素级条码类型与模板主条码类型的一致性口径。
   *
   * <p>一条面单不会同时把 Code128 与 QR 声明成主条码 —— 那是配置没想清楚，
   * 而现场的处置方式是「随便挑一个」，最后发现打印机上少打了一种。
   */
  public final class LabelSpec {
    private LabelSpec() {}

    /** 元素级条码类型必须与模板主条码类型一致（主类型为 NONE 时元素不许带条码）。 */
    public static boolean symbologyMatches(String primary, String element) {
      String p = primary == null ? "NONE" : primary;
      if ("NONE".equals(p)) {
        return element == null || element.isBlank();
      }
      return p.equals(element);
    }

    /** 渠道限制的 restriction_kind ⇄ 本模块声明 kind 的映射（同一套语义，两个命名空间）。 */
    public static String declarationKindFor(String restrictionKind) {
      return switch (restrictionKind == null ? "" : restrictionKind) {
        case "BATTERY" -> "BATTERY";
        case "LIQUID" -> "LIQUID";
        case "MAGNET" -> "MAGNET";
        default -> "";
      };
    }

    /** 受控枚举快照（供前端渲染下拉；与 V141 的 CHECK 白名单逐条对应）。 */
    public static Map<String, List<String>> enums() {
      return Map.of(
          "symbologies",
          List.of("NONE", "CODE128_B", "CODE128_A", "CODE39", "QR", "DATAMATRIX"),
          "elementKinds",
          List.of(
              "SHIP_TO_NAME", "SHIP_TO_ADDRESS", "SHIP_FROM_ADDRESS", "WAYBILL_NUMBER",
              "WAYBILL_BARCODE", "SERVICE_NAME", "ROUTING_CODE", "CUSTOMS_DECLARATION",
              "DECLARATION_TEXT", "BATTERY_MARK", "LOGO", "TEXT", "QR_CODE"),
          "declarationKinds",
          List.of(
              "BATTERY", "LIQUID", "MAGNET", "DANGEROUS_GOODS", "BIOHAZARD",
              "RETURN_DECLARATION", "OTHER"),
          "batteryTypes",
          List.of("LITHIUM_ION", "LITHIUM_METAL", "LITHIUM_POLYMER", "DRY_CELL", "OTHER"),
          "languageCodes",
          List.of(
              "en-US", "en-GB", "de-DE", "fr-FR", "es-ES", "it-IT", "nl-NL", "pt-BR",
              "ja-JP", "zh-CN"));
    }
  }
}
