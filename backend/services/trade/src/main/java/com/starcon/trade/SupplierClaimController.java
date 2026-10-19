package com.starcon.trade;

import static com.starcon.trade.SupplierClaimModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 11.2.3.3 供应商索赔。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「对质量原因退货向供应商发起索赔：关联批次与责任判定、
 * 索赔金额、协商记录、赔付与冲减应付处理。」
 * 输入：「质检结论与责任判定；索赔金额；协商记录。」
 * 输出：「索赔单 + 冲减应付或退款记录。」
 * 加粗句：「<b>索赔不是自动到账</b>，系统只跟踪流程与金额，冲减应付需财务确认。」
 *
 * <p><b>命名空间独立于 {@code /trade/return-quality} 与 {@code /trade/product-attribution}</b>：
 * 本模块的产物是**另一种单据**（索赔 = 一次对外的商务主张 + 一串协商 + 一笔待确认的赔付），
 * 不是那两个模块的另一个视图。
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.read}；写 {@code trade.aftersales.write}；
 * <b>财务确认冲减</b>用独立的 {@code trade.aftersales.finance} ——
 * 「冲减应付需财务确认」这条规则的落点不只是状态机，还有**权限分离**：
 * 登记赔付的人不该顺手把冲减确认掉。
 *
 * <p><b>刻意不提供</b>：任何「直接置为已结算」的端点 —— 已结算只能由
 * 「财务确认赔付分录」这一个动作驱动。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} / {@link #finance} 先向 identity
 * 确认已认证成员关系，再在一个事务里绑定 {@code app.current_tenant}，
 * 由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的索赔单 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误。
 */
@RestController
@RequestMapping("/trade/supplier-claims")
public class SupplierClaimController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";
  /** 财务确认冲减用的**独立权限**：登记赔付的人不该顺手把冲减确认掉。 */
  private static final String FINANCE = "aftersales.finance";

  private final AccessService access;
  private final SupplierClaimService claims;

  public SupplierClaimController(AccessService access, SupplierClaimService claims) {
    this.access = access;
    this.claims = claims;
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
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、两条硬规则与状态机的口径。
   *
   * <p>路径刻意是 {@code capabilities}（租户级静态事实），不带 id 段。
   */
  @GetMapping("/capabilities")
  public SupplierClaimCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> claims.capabilities());
  }

  // ================================================================ 可索赔候选

  /**
   * 可索赔候选：已判定 + 已归因 + 环节可索赔 + 尚未被索赔过。
   *
   * <p>这是「发起索赔」的<b>唯一入口素材</b>：界面不该让操作员手填质检结论 id 与责任判定 id。
   */
  @GetMapping("/candidates")
  public SupplierClaimCandidatePage candidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, actor -> claims.candidates(keyword, page, size));
  }

  // ================================================================ 索赔单

  /** 索赔单列表（真分页 + 多维筛选 + 排序白名单 + 跨全表汇总）。 */
  @GetMapping
  public SupplierClaimPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID supplierRefId,
      @RequestParam(required = false) String liabilityStage,
      @RequestParam(required = false) String offsetStatus,
      @RequestParam(required = false) java.math.BigDecimal minAmount,
      @RequestParam(required = false) java.math.BigDecimal maxAmount,
      @RequestParam(required = false) java.time.OffsetDateTime from,
      @RequestParam(required = false) java.time.OffsetDateTime to,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            claims.listClaims(
                new SupplierClaimListQuery(
                    page, size, status, supplierRefId, liabilityStage, offsetStatus, minAmount,
                    maxAmount, from, to, keyword, sort)));
  }

  /** 单张索赔单全景（头 + 明细 + 协商 + 赔付 + 留痕 + 当前可执行动作）。 */
  @GetMapping("/{id}")
  public SupplierClaimDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> claims.claimDetail(id));
  }

  /**
   * 发起索赔（建单，恒为草稿）。
   *
   * <p><b>请求体里没有金额字段</b>：索赔金额是<b>明细</b>上的量，
   * 头上的 claimedAmount 由 DB 触发器从明细重算（V188 守卫⑤）。
   */
  @PostMapping
  public SupplierClaimDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SupplierClaimCreate request) {
    return write(auth, actor -> claims.createClaim(request, actor.userId()));
  }

  /**
   * 加入索赔明细。
   *
   * <p><b>不接受</b>基数、原值、币种、SKU 与批次快照：这些都由 DB 触发器从
   * 质检结论与责任判定取，传进来也传不进来（V188 守卫②）。
   */
  @PostMapping("/{id}/items")
  public SupplierClaimDetail addItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierClaimItemCreate request) {
    return write(auth, actor -> claims.addItem(id, request, actor.userId()));
  }

  /** 移除一条索赔明细（仅草稿）。 */
  @DeleteMapping("/{id}/items/{itemId}")
  public SupplierClaimDetail removeItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID itemId) {
    return write(auth, actor -> claims.removeItem(id, itemId, actor.userId()));
  }

  /** 提交索赔（草稿 → 已提交）。 */
  @PostMapping("/{id}/submit")
  public SupplierClaimDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierClaimSubmit request) {
    return write(auth, actor -> claims.submit(id, request, actor.userId()));
  }

  /** 记一条协商记录（已提交 / 协商中）。 */
  @PostMapping("/{id}/negotiations")
  public SupplierClaimDetail recordNegotiation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierNegotiationCreate request) {
    return write(auth, actor -> claims.recordNegotiation(id, request, actor.userId()));
  }

  /** 进入协商（已提交 → 协商中）。必须先有协商记录。 */
  @PostMapping("/{id}/start-negotiation")
  public SupplierClaimDetail startNegotiation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) Long expectedVersion) {
    return write(auth, actor -> claims.startNegotiation(id, expectedVersion, actor.userId()));
  }

  /** 置为已达成（协商中 → 已达成）。达成金额由 DB 从「达成一致」那条协商记录取。 */
  @PostMapping("/{id}/agree")
  public SupplierClaimDetail agree(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierClaimAgree request) {
    return write(auth, actor -> claims.agree(id, request, actor.userId()));
  }

  /** 驳回索赔。必须写理由。 */
  @PostMapping("/{id}/reject")
  public SupplierClaimDetail reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierClaimReject request) {
    return write(auth, actor -> claims.rejectClaim(id, request, actor.userId()));
  }

  /** 撤回索赔。必须写理由。 */
  @PostMapping("/{id}/withdraw")
  public SupplierClaimDetail withdraw(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierClaimWithdraw request) {
    return write(auth, actor -> claims.withdrawClaim(id, request, actor.userId()));
  }

  // ================================================================ 赔付 / 冲减应付

  /**
   * 登记赔付 / 冲减应付（<b>幂等</b>）。
   *
   * <p>⚠️ 登记时生效金额恒为 0 —— 「索赔不是自动到账」这句话在这一行落成结构。
   */
  @PostMapping("/{id}/settlements")
  public SupplierClaimDetail registerSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SupplierSettlementCreate request) {
    return write(auth, actor -> claims.registerSettlement(id, request, actor.userId()));
  }

  /**
   * 财务确认冲减生效。
   *
   * <p>需要<b>独立</b>的 {@code trade.aftersales.finance} 权限。
   */
  @PostMapping("/{id}/settlements/{settlementId}/confirm")
  public SupplierClaimDetail confirmSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID settlementId,
      @RequestBody SupplierSettlementConfirm request) {
    return finance(auth, actor -> claims.confirmSettlement(id, settlementId, request, actor.userId()));
  }

  /** 财务驳回赔付。必须写理由。 */
  @PostMapping("/{id}/settlements/{settlementId}/reject")
  public SupplierClaimDetail rejectSettlement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID settlementId,
      @RequestBody SupplierSettlementReject request) {
    return finance(
        auth, actor -> claims.rejectSettlement(id, settlementId, request, actor.userId()));
  }
}
