package com.starcon.trade;

import static com.starcon.trade.PlatformDisputeModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * 11.4.1.1 平台纠纷列表。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、
 * 速卖通纠纷）：类型、金额、平台时限、举证要求、我方立场、结果记录。」
 * 输入：「平台纠纷事件/同步；责任判定；举证材料。」
 * 输出：「纠纷单 + 时效倒计时 + 举证清单。」
 * 加粗句：「<b>平台纠纷有严格举证时限</b>，超期自动判败，是硬性时效要求。」
 *
 * <p><b>命名空间独立于 {@code /trade/supplier-claims}（11.2.3.3）</b>：
 * 本模块的产物是**另一种单据**（平台纠纷 = 一次平台发起的指控 + 一份限时举证清单 +
 * 一个时限裁决），不是供应商索赔的另一个视图。
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.read}；写 {@code trade.aftersales.write}；
 * <b>财务确认结算生效</b>用独立的 {@code trade.aftersales.finance} ——
 * 「争议金额影响结算需财务确认」这条规则的落点不只是状态机，还有**权限分离**：
 * 登记结算影响的人不该顺手把结算确认掉。
 *
 * <p><b>刻意不提供</b>：任何「手动标记超期判负」的端点 —— 超期自动判败是**系统做的**
 * （DB 守卫①），一个能提前把自己判负的按钮只会让人用它来提前结案。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} / {@link #finance} 先向 identity
 * 确认已认证成员关系，再在一个事务里绑定 {@code app.current_tenant}，
 * 由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的纠纷 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误。
 */
@RestController
@RequestMapping("/trade/platform-disputes")
public class PlatformDisputeController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";
  /** 财务确认结算生效用的**独立权限**：登记结算影响的人不该顺手把它确认掉。 */
  private static final String FINANCE = "aftersales.finance";

  private final AccessService access;
  private final PlatformDisputeService disputes;

  public PlatformDisputeController(AccessService access, PlatformDisputeService disputes) {
    this.access = access;
    this.disputes = disputes;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  /** 财务确认入口：需要**独立**的 {@code trade.aftersales.finance}。 */
  private <T> T finance(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + FINANCE);
    return access.run(actor, "trade." + FINANCE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的六个要素逐块报可用性，上下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、状态机与三条硬规则的口径。
   *
   * <p>⚠️ 特别地，这里**如实报出上游缺口**：channel 服务**没有纠纷专用同步端点**，
   * crm 服务的客服工单 / 客户模块**未开发** ⇒ 本模块提供**自己的建单入口**，
   * 不假装存在一个「平台推送接口」。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public PlatformDisputeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> disputes.capabilities());
  }

  // ================================================================ 纠纷单

  /**
   * 纠纷单列表（真分页 + 多维筛选 + 排序白名单 + 按币种分组的金额口径）。
   *
   * <p>⚠️ **倒计时是派生态**：响应里的 {@code secondsRemaining} 与 {@code deadlineState}
   * 由举证截止时刻在**本次读取时刻**算出 —— 库里没有会自己过期的「剩余小时数」列。
   */
  @GetMapping
  public PlatformDisputePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String disputeType,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String ourPosition,
      @RequestParam(required = false) String liabilityStage,
      @RequestParam(required = false) String settlementStatus,
      @RequestParam(required = false) Boolean overdueOnly,
      @RequestParam(required = false) java.math.BigDecimal minAmount,
      @RequestParam(required = false) java.math.BigDecimal maxAmount,
      @RequestParam(required = false) java.time.OffsetDateTime from,
      @RequestParam(required = false) java.time.OffsetDateTime to,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            disputes.listDisputes(
                new PlatformDisputeListQuery(
                    page, size, status, disputeType, platformCode, currency, ourPosition,
                    liabilityStage, settlementStatus, overdueOnly, minAmount, maxAmount, from, to,
                    keyword, sort)));
  }

  /** 单张纠纷单全景（头 + 举证清单 + 结算分录 + 留痕 + 当前可执行动作）。 */
  @GetMapping("/{id}")
  public PlatformDisputeDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> disputes.disputeDetail(id));
  }

  /**
   * 登记平台纠纷（建单）。
   *
   * <p>⚠️ 这是本模块**自己的建单入口**：channel 服务没有纠纷专用同步端点，
   * crm 的客服工单未开发 ⇒ 平台纠纷由平台侧或人工登记，平台来源信息以**快照**落库。
   *
   * <p>⚠️ <b>没有生效金额字段、也没有剩余时长字段</b>：
   * 前者由财务确认那一刻的 DB 触发器写，后者是读取时刻的派生态。
   */
  @PostMapping
  public PlatformDisputeDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody PlatformDisputeCreate request) {
    return write(auth, actor -> disputes.createDispute(request, actor.userId()));
  }

  // ================================================================ 举证清单

  /**
   * 加一项举证材料。
   *
   * <p>⚠️ 这里**没有**「材料本体」字段：11.4.1.2 证据与申诉未开发，
   * 本模块只管**清单事实**（该交什么、齐不齐、缺什么）。
   */
  @PostMapping("/{id}/evidence-items")
  public PlatformDisputeDetail addEvidenceItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformEvidenceItemCreate request) {
    return write(auth, actor -> disputes.addEvidenceItem(id, request, actor.userId()));
  }

  /**
   * 进入举证准备（已立案 → 举证准备中）。
   *
   * <p>⚠️ **必须已有举证清单**：举证要求是平台给的，先把该交的列出来，
   * 才知道缺哪一样。
   */
  @PostMapping("/{id}/start-evidence")
  public PlatformDisputeDetail startEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) Long expectedVersion) {
    return write(auth, actor -> disputes.startEvidence(id, expectedVersion, actor.userId()));
  }

  /**
   * 更新一项举证材料的状态（待核查 / 已具备 / 确认缺失）。
   *
   * <p>⚠️ 确认缺失**必须**写清缺什么（表级 CHECK 钉死）。
   */
  @PutMapping("/{id}/evidence-items/{itemId}")
  public PlatformDisputeDetail markEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @RequestBody PlatformEvidenceMark request) {
    return write(auth, actor -> disputes.markEvidence(id, itemId, request, actor.userId()));
  }

  /** 移除一项举证材料（仅未超期且未提交举证时）。 */
  @DeleteMapping("/{id}/evidence-items/{itemId}")
  public PlatformDisputeDetail removeEvidenceItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID itemId) {
    return write(auth, actor -> disputes.removeEvidenceItem(id, itemId, actor.userId()));
  }

  // ================================================================ 立场与结果

  /** 更新我方立场 + 责任判定。 */
  @PostMapping("/{id}/position")
  public PlatformDisputeDetail updatePosition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformDisputePositionUpdate request) {
    return write(auth, actor -> disputes.updatePosition(id, request, actor.userId()));
  }

  /**
   * 提交举证（举证准备中 → 举证已提交）。
   *
   * <p>⚠️ **必举证项必须全部具备**（清单不齐就没有「举证」这回事），
   * 且**超期不可提交** —— 超期自动判负是硬性时效要求。
   */
  @PostMapping("/{id}/submit-evidence")
  public PlatformDisputeDetail submitEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformDisputeSubmitEvidence request) {
    return write(auth, actor -> disputes.submitEvidence(id, request, actor.userId()));
  }

  /**
   * 记录平台裁决结果（我方胜诉 / 败诉 / 双方达成一致）。
   *
   * <p>⚠️ 结果码**不接受**「超期自动判负」：那是系统判的，不接受人工声明。
   */
  @PostMapping("/{id}/resolve")
  public PlatformDisputeDetail resolve(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformDisputeResolve request) {
    return write(auth, actor -> disputes.resolve(id, request, actor.userId()));
  }

  /** 我方撤诉。必须写理由。 */
  @PostMapping("/{id}/withdraw")
  public PlatformDisputeDetail withdraw(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformDisputeWithdraw request) {
    return write(auth, actor -> disputes.withdraw(id, request, actor.userId()));
  }

  // ================================================================ 结算影响

  /**
   * 登记结算影响（<b>幂等</b>）。
   *
   * <p>⚠️ 登记时生效金额恒为 0 —— 「没有财务确认就没有生效」这句话在这一行落成结构。
   */
  @PostMapping("/{id}/settlements")
  public PlatformDisputeDetail registerSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PlatformDisputeSettlementCreate request) {
    return write(auth, actor -> disputes.registerSettlement(id, request, actor.userId()));
  }

  /**
   * 财务确认结算生效。
   *
   * <p>需要<b>独立</b>的 {@code trade.aftersales.finance} 权限。
   */
  @PostMapping("/{id}/settlements/{settlementId}/confirm")
  public PlatformDisputeDetail confirmSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID settlementId,
      @RequestBody PlatformDisputeSettlementConfirm request) {
    return finance(
        auth, actor -> disputes.confirmSettlement(id, settlementId, request, actor.userId()));
  }

  /** 财务驳回结算影响。必须写理由。 */
  @PostMapping("/{id}/settlements/{settlementId}/reject")
  public PlatformDisputeDetail rejectSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID settlementId,
      @RequestBody PlatformDisputeSettlementReject request) {
    return finance(
        auth, actor -> disputes.rejectSettlement(id, settlementId, request, actor.userId()));
  }
}
