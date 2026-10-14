package com.starcon.warehouse;

import static com.starcon.warehouse.TradeDocumentModels.*;

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
 * 10.5.2.1 贸易单证。
 *
 * <p>命名空间 {@code /warehouse/trade-documents}：与 10.5.1.1 报关单（{@code
 * /warehouse/customs-declarations}）、 10.5.1.3 关税（{@code /warehouse/duty-taxes}）、10.4.1.1
 * 头程发运单（{@code /warehouse/first-mile-shipments}） 同一 {@code /warehouse} 前缀，浏览器只经 /api/warehouse
 * 代理访问。**刻意不进 {@code inventory/}**： 那是 8.2 现场作业的语义，把「8.2 现场作业」与「10 物流贸易单证」混在一个前缀里会让两边都读不懂。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}，全部写动作挂 {@code
 * warehouse.inventory.update} —— 提交下游那一刻备证资料就出去了，权限等级与调拨发货同档。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，由
 * RLS 在数据库层强制隔离。 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p>动作端点是 **POST**：复核、跑校验、主张优惠、业务确认、提交、接收登记都是 「发生一次业务事件」，用 PUT 会被 HTTP 语义读成「把单据设置成某个状态」。
 */
@RestController
@RequestMapping("/warehouse/trade-documents")
public class TradeDocumentController {

  private final AccessService access;
  private final TradeDocumentService tradeDocuments;

  public TradeDocumentController(AccessService access, TradeDocumentService tradeDocuments) {
    this.access = access;
    this.tradeDocuments = tradeDocuments;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力

  /**
   * 贸易单证能力：单证类型、要求级别、确认状态、校验结论、状态机、 **未交付依赖逐条声明**与四条必须讲清的口径。
   *
   * <p>前端据此渲染各下拉与提示 —— 16 合规与外部报关行/目的仓对接**显式报不可用并给出归属模块**， 而不是给一个点下去没反应的选项。
   */
  @GetMapping("/capabilities")
  public TradeDocumentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> tradeDocuments.capabilities());
  }

  // ================================================================ 目的地国家单证要求规则

  /** 规则列表 + 全量汇总（**合计不受分页影响**）。规则是数据不是常量。 */
  @GetMapping("/rules")
  public TradeRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String documentKind,
      @RequestParam(required = false) String requirementLevel,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tradeDocuments.rules(
                new TradeDocumentService.RuleQuery(
                    countryCode,
                    documentKind,
                    requirementLevel,
                    status,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  @PostMapping("/rules")
  public TradeRuleSaved createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TradeRuleCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.createRule(a.tenantId(), request == null ? emptyRuleCreate() : request));
  }

  @PutMapping("/rules/{ruleId}")
  public TradeRuleSaved updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody TradeRuleUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> tradeDocuments.updateRule(ruleId, request == null ? emptyRuleUpdate() : request));
  }

  // ================================================================ 单证模板

  @GetMapping("/templates")
  public TradeTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentKind,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tradeDocuments.templates(
                new TradeDocumentService.TemplateQuery(
                    documentKind, countryCode, status, keyword, sort, page, size)));
  }

  @PostMapping("/templates")
  public TradeTemplateSaved createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TradeTemplateCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.createTemplate(
                a.tenantId(), request == null ? emptyTemplateCreate() : request));
  }

  @PutMapping("/templates/{templateId}")
  public TradeTemplateSaved updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody TradeTemplateUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.updateTemplate(
                templateId, request == null ? emptyTemplateUpdate() : request));
  }

  // ================================================================ 认证状态（16 合规替代来源）

  @GetMapping("/certifications")
  public TradeCertificationPage certifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String certificationSource,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tradeDocuments.certifications(
                new TradeDocumentService.CertificationQuery(
                    skuId, status, certificationSource, keyword, sort, page, size)));
  }

  @PostMapping("/certifications")
  public TradeCertificationSaved createCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TradeCertificationCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.createCertification(
                a.tenantId(), request == null ? emptyCertificationCreate() : request));
  }

  @PutMapping("/certifications/{certificationId}")
  public TradeCertificationSaved updateCertification(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID certificationId,
      @Valid @RequestBody TradeCertificationUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.updateCertification(
                certificationId, request == null ? emptyCertificationUpdate() : request));
  }

  // ================================================================ FTA 优惠规则

  @GetMapping("/preference-rules")
  public TradePreferenceRulePage preferenceRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scheme,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tradeDocuments.preferenceRules(
                new TradeDocumentService.PreferenceRuleQuery(
                    scheme, destinationCountryCode, status, sort, page, size)));
  }

  @PostMapping("/preference-rules")
  public TradePreferenceRuleSaved createPreferenceRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TradePreferenceRuleCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.createPreferenceRule(
                a.tenantId(), request == null ? emptyPreferenceRuleCreate() : request));
  }

  @PutMapping("/preference-rules/{ruleId}")
  public TradePreferenceRuleSaved updatePreferenceRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody TradePreferenceRuleUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.updatePreferenceRule(
                ruleId, request == null ? emptyPreferenceRuleUpdate() : request));
  }

  /** 优惠主张清单（跨单证包）：谁主张了优惠、确认到哪一步、按哪一版规则确认的。 */
  @GetMapping("/preference-confirmations")
  public TradePreferenceConfirmationPage preferenceConfirmations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID setId,
      @RequestParam(required = false) String substantiveChangeStatus,
      @RequestParam(required = false) String ftaScheme,
      @RequestParam(required = false) Boolean claimedOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tradeDocuments.preferenceConfirmations(
                new TradeDocumentService.PreferenceConfirmationQuery(
                    setId, substantiveChangeStatus, ftaScheme, claimedOnly, page, size)));
  }

  // ================================================================ 缺失提醒工作台

  /** 缺失提醒工作台（蓝图输出之三）。汇总是**全量**口径，不随筛选与分页变。 */
  @GetMapping("/reminders")
  public TradeReminderPage reminders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID setId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String reminderType,
      @RequestParam(required = false) String documentKind,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tradeDocuments.reminderPage(
                new TradeDocumentService.ReminderQuery(
                    setId,
                    status,
                    severity,
                    reminderType,
                    documentKind,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /** 处理 / 忽略一条缺失提醒。必须写说明：静默消失的提醒等于没提醒过。 */
  @PostMapping("/reminders/{reminderId}/resolve")
  public TradeReminderSaved resolveReminder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID reminderId,
      @Valid @RequestBody TradeReminderResolveRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.resolveReminder(
                reminderId, request == null ? new TradeReminderResolveRequest("", "") : request));
  }

  // ================================================================ 单证包

  /** 单证包列表 + 全量汇总（**合计不受分页影响**）。 */
  @GetMapping
  public TradeSetPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) UUID sourceDeclarationId,
      @RequestParam(required = false) String latestCheckResult,
      @RequestParam(required = false) Boolean blockedOnly,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            tradeDocuments.page(
                new TradeSetQuery(
                    status,
                    destinationCountryCode,
                    sourceDeclarationId,
                    latestCheckResult,
                    blockedOnly,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /**
   * 单证包详情：头 + 单证（含被改版取代的历史版本）+ 完整性校验历史 + 缺失提醒 + 事件时间线 + 当前状态的合法下一步。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这套单证存在」）。
   */
  @GetMapping("/{setId}")
  public TradeSetDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId) {
    return call(auth, "inventory.read", a -> tradeDocuments.detail(setId));
  }

  /** 单证清单（含历史版本）—— 举证「上一版出的是什么」靠的就是它。 */
  @GetMapping("/{setId}/documents")
  public List<TradeDocumentRow> documents(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          // 详情先行：单证包不存在时 404，而不是返回一个空数组冒充「没有单证」。
          tradeDocuments.detail(setId);
          return tradeDocuments.documents(setId);
        });
  }

  /** 完整性校验历史（append-only 留档）。 */
  @GetMapping("/{setId}/checks")
  public List<TradeCheckRow> checks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          tradeDocuments.detail(setId);
          return tradeDocuments.checks(setId);
        });
  }

  /** 该单证包的缺失提醒。 */
  @GetMapping("/{setId}/reminders")
  public List<TradeReminderRow> remindersOf(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          tradeDocuments.detail(setId);
          return tradeDocuments.reminders(setId);
        });
  }

  /** 事件时间线：一次业务动作 = 一行，逐笔回放。 */
  @GetMapping("/{setId}/events")
  public List<TradeEventRow> events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId) {
    return call(
        auth,
        "inventory.read",
        a -> {
          tradeDocuments.detail(setId);
          return tradeDocuments.events(setId);
        });
  }

  /** 建包（草稿）。来源固定是 10.5.1.1 报关单。 */
  @PostMapping
  public TradeSaved create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody TradeSetCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> tradeDocuments.createSet(a.tenantId(), request == null ? emptySetCreate() : request));
  }

  @PutMapping("/{setId}")
  public TradeSaved update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetUpdateRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> tradeDocuments.updateSet(setId, request == null ? emptySetUpdate() : request));
  }

  /** 送人工复核：草稿 / 复核退回 → 待复核。一张单证都没有时不允许送（复核空气没有意义）。 */
  @PostMapping("/{setId}/submit-for-review")
  public TradeSaved submitForReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.submitForReview(
                setId, request == null ? new TradeSetReviewRequest("") : request));
  }

  /**
   * 人工复核通过：待复核 → 已复核通过。这是提交下游的**唯一前置状态**。
   *
   * <p>有优惠主张却还没业务确认痕迹时，复核**过不去**（不是复核完才发现提交不了）。
   */
  @PostMapping("/{setId}/review")
  public TradeSaved review(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.review(
                setId, request == null ? new TradeSetReviewRequest("") : request));
  }

  /** 复核退回：必须给原因。改完必须重新走复核才能再次提交。 */
  @PostMapping("/{setId}/reject-review")
  public TradeSaved rejectReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetRejectReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.rejectReview(
                setId, request == null ? new TradeSetRejectReviewRequest("") : request));
  }

  /** 撤回复核：待复核 → 草稿。撤回复核后单证才允许再改。 */
  @PostMapping("/{setId}/withdraw-review")
  public TradeSaved withdrawReview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetWithdrawReviewRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.withdrawReview(
                setId, request == null ? new TradeSetWithdrawReviewRequest("") : request));
  }

  /**
   * 跑一次完整性校验（蓝图输出之二）。
   *
   * <p>结论只有三档，**「待业务确认」不算通过**。DB CHECK 也钉死了这个区分。
   */
  @PostMapping("/{setId}/check")
  public TradeSaved runCheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId) {
    return call(auth, "inventory.update", a -> tradeDocuments.runCheck(setId));
  }

  /** 生成 / 改版单证：按类型递增版本链，旧版本置为被取代（**不覆盖**）。 */
  @PostMapping("/{setId}/documents")
  public TradeDocumentSaved generateDocument(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeDocumentCreateRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.generateDocument(
                setId, request == null ? emptyDocumentCreate() : request));
  }

  /** 定稿单证：必须有来源定位 —— 定稿前的单证不是备证资料。 */
  @PostMapping("/{setId}/documents/{documentId}/finalize")
  public TradeDocumentSaved finalizeDocument(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @PathVariable UUID documentId,
      @Valid @RequestBody TradeDocumentFinalizeRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> {
          boolean owned =
              tradeDocuments.documents(setId).stream().anyMatch(d -> d.id().equals(documentId));
          if (!owned) {
            throw new IllegalArgumentException("单证不存在或不属于这套单证包：" + documentId);
          }
          return tradeDocuments.finalizeDocument(
              setId,
              documentId,
              request == null ? new TradeDocumentFinalizeRequest("", "") : request);
        });
  }

  /** 登记 FTA 优惠主张。**只登记主张，不判定成立** —— 登记后状态停在「待业务确认」， 直到业务来确认为止。系统不代替业务判断「实质性改变」是否成立。 */
  @PostMapping("/{setId}/documents/{documentId}/preference-claim")
  public TradeDocumentSaved claimPreference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @PathVariable UUID documentId,
      @Valid @RequestBody TradePreferenceClaimRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> {
          boolean owned =
              tradeDocuments.documents(setId).stream().anyMatch(d -> d.id().equals(documentId));
          if (!owned) {
            throw new IllegalArgumentException("单证不存在或不属于这套单证包：" + documentId);
          }
          return tradeDocuments.claimPreference(
              setId, documentId, request == null ? emptyPreferenceClaim() : request);
        });
  }

  /** 业务确认（或确认不成立）。这是**唯一**能把优惠从「待确认」推进去的入口， 且只做三件事：收下确认人与依据、把规则版本钉在单证上、记事件。 */
  @PostMapping("/{setId}/documents/{documentId}/preference-confirm")
  public TradeDocumentSaved confirmPreference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @PathVariable UUID documentId,
      @Valid @RequestBody TradePreferenceClaimRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> {
          boolean owned =
              tradeDocuments.documents(setId).stream().anyMatch(d -> d.id().equals(documentId));
          if (!owned) {
            throw new IllegalArgumentException("单证不存在或不属于这套单证包：" + documentId);
          }
          return tradeDocuments.confirmPreference(
              setId, documentId, request == null ? emptyPreferenceClaim() : request);
        });
  }

  /**
   * 提交报关行 / 目的仓：已复核通过 → 已提交。
   *
   * <p>三道闸由 DB 触发器钉死，绕过服务层直接改状态同样被挡：人工复核通过、 完整性校验必须 PASS 且无阻断级提醒、优惠主张已获业务确认。
   */
  @PostMapping("/{setId}/submit")
  public TradeSaved submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetSubmitRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.submit(
                setId, request == null ? new TradeSetSubmitRequest("", "") : request));
  }

  /** 登记下游接收：已提交 → 已接收（终态）。 */
  @PostMapping("/{setId}/acknowledge")
  public TradeSaved acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetAcknowledgeRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.acknowledge(
                setId, request == null ? new TradeSetAcknowledgeRequest("") : request));
  }

  /** 登记下游退回：已提交 → 被退回。必须给原因。 */
  @PostMapping("/{setId}/send-back")
  public TradeSaved sendBack(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetSendBackRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.sendBack(
                setId, request == null ? new TradeSetSendBackRequest("") : request));
  }

  /** 取消：必须给原因。备证资料已生成过，取消后无法追责。 */
  @PostMapping("/{setId}/cancel")
  public TradeSaved cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID setId,
      @Valid @RequestBody TradeSetCancelRequest request) {
    return call(
        auth,
        "inventory.update",
        a ->
            tradeDocuments.cancel(
                setId, request == null ? new TradeSetCancelRequest("") : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「提交前必须人工复核」），而不是 NPE。
  // 每个 null 对应 record 的一个组件（顺序与 Models 里逐字一致）。

  private static TradeRuleCreateRequest emptyRuleCreate() {
    return new TradeRuleCreateRequest(null, null, null, null, null, null, null, null, null, null);
  }

  private static TradeRuleUpdateRequest emptyRuleUpdate() {
    return new TradeRuleUpdateRequest(null, null, null, null, null, null, null, null, null, null);
  }

  private static TradeTemplateCreateRequest emptyTemplateCreate() {
    return new TradeTemplateCreateRequest(
        null, null, null, null, java.util.Map.of(), null, null, null, null);
  }

  private static TradeTemplateUpdateRequest emptyTemplateUpdate() {
    return new TradeTemplateUpdateRequest(null, null, null, null, null, null);
  }

  private static TradeCertificationCreateRequest emptyCertificationCreate() {
    return new TradeCertificationCreateRequest(
        // skuId, certificationCode, certificationName, status, certificateNo, issuingBody,
        // issuedOn, expiresOn, documentRef, certificationSource, sourceReference, notes
        null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static TradeCertificationUpdateRequest emptyCertificationUpdate() {
    return new TradeCertificationUpdateRequest(
        // expectedVersion, status, certificateNo, issuingBody, issuedOn, expiresOn,
        // documentRef, certificationSource, sourceReference, notes
        null, null, null, null, null, null, null, null, null, null);
  }

  private static TradePreferenceRuleCreateRequest emptyPreferenceRuleCreate() {
    return new TradePreferenceRuleCreateRequest(
        // scheme, destinationCountryCode, productScope, hsPrefix, preferenceCriteria,
        // substantiveChangeCriterion, requiresBusinessConfirmation, validFrom, validTo,
        // status, ruleSource, sourceReference, notes
        null, null, null, null, null, null, null, null, null, null, null, null, null);
  }

  private static TradePreferenceRuleUpdateRequest emptyPreferenceRuleUpdate() {
    return new TradePreferenceRuleUpdateRequest(
        null, null, null, null, null, null, null, null, null, null);
  }

  private static TradeSetCreateRequest emptySetCreate() {
    return new TradeSetCreateRequest(null, null, null);
  }

  private static TradeSetUpdateRequest emptySetUpdate() {
    return new TradeSetUpdateRequest(null, null, null);
  }

  private static TradeDocumentCreateRequest emptyDocumentCreate() {
    return new TradeDocumentCreateRequest(
        // documentKind, templateId, documentNo, currency, quantityTotal, packageCount, content,
        // notes
        null, null, null, null, null, null, java.util.Map.of(), null);
  }

  private static TradePreferenceClaimRequest emptyPreferenceClaim() {
    return new TradePreferenceClaimRequest(null, null, null, null, null);
  }
}
