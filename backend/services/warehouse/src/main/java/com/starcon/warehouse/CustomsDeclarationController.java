package com.starcon.warehouse;

import static com.starcon.warehouse.CustomsDeclarationModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 10.5.1.1 报关单管理。
 *
 * <p>命名空间 {@code /warehouse/customs-declarations}：与 10.4.1.1 头程发运单
 * （{@code /warehouse/first-mile-shipments}）同一 {@code /warehouse} 前缀，浏览器只经
 * /api/warehouse 代理访问。本模块的货物来源在 warehouse 服务内，明细行对
 * {@code first_mile_shipment_line} 建了复合外键，所以放进 {@code /inventory/} 会让
 * 「8.2 现场作业」与「10 物流报关」两套语义混在一个前缀里。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}，
 * 全部写动作（建单 / 改单 / 复核 / 生成单据 / 提交报关行 / 登记海关结果 / 改单 / 取消）
 * 挂 {@code warehouse.inventory.update} —— 提交报关行那一刻申报资料就出去了，
 * 权限等级与调拨发货同档。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p>动作端点是 **POST**：复核、提交、登记结果都是「发生一次业务事件」，
 * 用 PUT 会被 HTTP 语义读成「把单据设置成某个状态」，而实际发生的是申报资料对外递交。
 */
@RestController
@RequestMapping("/warehouse/customs-declarations")
public class CustomsDeclarationController {

  private final AccessService access;
  private final CustomsDeclarationService declarations;

  public CustomsDeclarationController(AccessService access, CustomsDeclarationService declarations) {
    this.access = access;
    this.declarations = declarations;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 报关能力：贸易方式、运输方式、状态机、单据类型、申报要素状态、
   * **未交付依赖逐条声明**与四条必须讲清的口径。
   *
   * <p>前端据此渲染各下拉与提示 —— 3.7.1.1 HS 编码字典与外部报关行接口**显式报不可用
   * 并给出归属模块**，而不是给一个点下去没反应的选项。
   */
  @GetMapping("/capabilities")
  public CustomsCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> declarations.capabilities());
  }

  /** 报关单列表 + 全量汇总（**合计不受分页影响**）+ 状态/贸易方式/运输方式下拉。 */
  @GetMapping
  public CustomsDeclarationPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String tradeMode,
      @RequestParam(required = false) String transportMode,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) UUID sourceShipmentId,
      @RequestParam(required = false) Boolean pendingReviewOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            declarations.page(
                new CustomsQuery(
                    status,
                    tradeMode,
                    transportMode,
                    destinationCountryCode,
                    sourceShipmentId,
                    pendingReviewOnly,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /**
   * 报关单详情：头 + 明细 + 三单据（含被改版取代的历史版本）+ 提交回执 + 海关结果回执
   * + 事件时间线 + 当前状态的合法下一步。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这张单存在」）。
   */
  @GetMapping("/{declarationId}")
  public CustomsDeclarationDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId) {
    return call(auth, "inventory.read", a -> declarations.detail(declarationId));
  }

  /** 本单的三单据（含历史版本）—— 被退单时要举证「上一版报的是什么」靠的就是它。 */
  @GetMapping("/{declarationId}/documents")
  public List<CustomsDocumentRow> documents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          // 详情先行：单不存在时 404，而不是返回一个空数组冒充「没有单据」。
          declarations.detail(declarationId);
          return declarations.documents(declarationId);
        });
  }

  /** 海关结果回执：放行 / 退单 / 改单逐条留痕，按时间倒序。 */
  @GetMapping("/{declarationId}/results")
  public List<CustomsResultRow> results(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          declarations.detail(declarationId);
          return declarations.results(declarationId);
        });
  }

  /** 事件时间线：一次业务动作 = 一行，逐笔回放。 */
  @GetMapping("/{declarationId}/events")
  public List<CustomsEventRow> events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          declarations.detail(declarationId);
          return declarations.events(declarationId);
        });
  }

  /** 建单（草稿）。用 POST：它真的落库；草稿不动任何账。 */
  @PostMapping
  public CustomsSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CustomsCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.create(a.tenantId(), request == null ? emptyCreate() : request));
  }

  /** 改单：仅草稿与复核退回可改。已复核之后一行都不让改，否则「复核」就变成走过场。 */
  @PutMapping("/{declarationId}")
  public CustomsSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.update(declarationId, request == null ? emptyUpdate() : request));
  }

  /** 送人工复核：草稿 / 复核退回 → 待复核。 */
  @PostMapping("/{declarationId}/submit-for-review")
  public CustomsSaved submitForReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsSubmitForReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            declarations.submitForReview(
                declarationId, request == null ? new CustomsSubmitForReviewRequest("") : request));
  }

  /**
   * 人工复核通过：待复核 → 已复核。这是提交报关行的**唯一前置状态**。
   *
   * <p>蓝图原文——报关数据错误导致的扣货与罚款由卖家承担，所以这一闸不能省，
   * 而且 DB 层会逐行检查申报资料齐备性，缺哪几项一次全报出来。
   */
  @PostMapping("/{declarationId}/review")
  public CustomsSaved review(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.review(declarationId, request == null ? new CustomsReviewRequest("") : request));
  }

  /** 复核退回：待复核 → 复核退回。必须给原因。改完必须重新走复核才能再次提交。 */
  @PostMapping("/{declarationId}/reject-review")
  public CustomsSaved rejectReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsRejectReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            declarations.rejectReview(
                declarationId, request == null ? new CustomsRejectReviewRequest("") : request));
  }

  /** 撤回复核：已复核 → 待复核。撤回复核后明细才允许再改。 */
  @PostMapping("/{declarationId}/withdraw-review")
  public CustomsSaved withdrawReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsWithdrawReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            declarations.withdrawReview(
                declarationId, request == null ? new CustomsWithdrawReviewRequest("") : request));
  }

  /** 生成三单据之一：按类型递增版本链，旧版本置为 SUPERSEDED（**不覆盖**）。 */
  @PostMapping("/{declarationId}/documents")
  public CustomsSaved generateDocument(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsGenerateDocumentRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.generateDocument(declarationId, request == null ? new CustomsGenerateDocumentRequest("", "") : request));
  }

  /** 定稿单据：报关单 / 装箱单 / 商业发票三份都定稿后才允许提交报关行。 */
  @PostMapping("/{declarationId}/documents/{documentId}/finalize")
  public CustomsSaved finalizeDocument(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @PathVariable UUID documentId,
      @Valid @RequestBody CustomsFinalizeDocumentRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> {
          // 校验这张单据确实属于本单，避免拿别的单据 id 去定稿
          boolean owned =
              declarations.documents(declarationId).stream()
                  .anyMatch(d -> d.id().equals(documentId));
          if (!owned) {
            throw new IllegalArgumentException("单据不存在或不属于这张报关单：" + documentId);
          }
          return declarations.finalizeDocument(
              documentId, request == null ? new CustomsFinalizeDocumentRequest("") : request);
        });
  }

  /**
   * 提交报关行：已复核 → 已提交。
   *
   * <p>必须先完成人工复核、三单据齐备且最新版本都已定稿 —— 三道闸里最硬的一道（人工复核）
   * 由 DB 触发器钉死，绕过服务层直接改状态同样被挡。
   */
  @PostMapping("/{declarationId}/submit")
  public CustomsSaved submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsSubmitRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.submit(declarationId, request == null ? emptySubmit() : request));
  }

  /** 登记海关结果：放行 / 退单 / 改单三种都落审计。 */
  @PostMapping("/{declarationId}/results")
  public CustomsSaved recordResult(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsResultRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.recordResult(declarationId, request == null ? new CustomsResultRequest("", "", "", null, "", "") : request));
  }

  /** 改单：已提交 / 已改单 / 已放行 → 已改单。必须给原因。 */
  @PostMapping("/{declarationId}/amend")
  public CustomsSaved amend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsAmendRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.amend(declarationId, request == null ? new CustomsAmendRequest("", "") : request));
  }

  /** 取消：必须给原因。报关资料已生成过，取消后无法追责。 */
  @PostMapping("/{declarationId}/cancel")
  public CustomsSaved cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID declarationId,
      @Valid @RequestBody CustomsCancelRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> declarations.cancel(declarationId, request == null ? new CustomsCancelRequest("") : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「提交报关行前必须人工复核」），而不是 NPE。

  // 每个 null 对应 record 的一个组件（顺序与 Models 里逐字一致）。组件一多就容易数错，
  // 所以这里按 Models 的字段顺序分组注释；改 Models 时这里必须同步改，否则编译期就报参数不匹配。
  private static CustomsCreateRequest emptyCreate() {
    return new CustomsCreateRequest(
        // sourceShipmentId, legalEntityId, tradeMode,
        null, null, null,
        // exporterName, exporterCountryCode, consigneeName, consigneeCountryCode,
        null, null, null, null,
        // iorName, iorCountryCode, brokerName, brokerCode, brokerContact,
        null, null, null, null, null,
        // destinationCountryCode, destinationCustomsDistrict, departurePort, destinationPort,
        null, null, null, null,
        // transportMode, billOfLadingNo, declarationCurrency, baseCurrency,
        null, null, null, null,
        // exchangeRate, exchangeRateScale, exchangeRateDate, notes, lines
        null, null, null, null, List.of());
  }

  private static CustomsUpdateRequest emptyUpdate() {
    return new CustomsUpdateRequest(
        // expectedVersion, tradeMode, exporterName, exporterCountryCode,
        null, null, null, null,
        // consigneeName, consigneeCountryCode, iorName, iorCountryCode,
        null, null, null, null,
        // brokerName, brokerCode, brokerContact, destinationCountryCode,
        null, null, null, null,
        // destinationCustomsDistrict, departurePort, destinationPort, transportMode,
        null, null, null, null,
        // billOfLadingNo, declarationCurrency, baseCurrency, exchangeRate,
        null, null, null, null,
        // exchangeRateScale, exchangeRateDate, notes, lines
        null, null, null, List.of());
  }

  private static CustomsSubmitRequest emptySubmit() {
    return new CustomsSubmitRequest(null, null, null, null);
  }
}