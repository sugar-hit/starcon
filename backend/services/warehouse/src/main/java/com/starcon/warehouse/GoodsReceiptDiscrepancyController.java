package com.starcon.warehouse;

import static com.starcon.warehouse.GoodsReceiptDiscrepancyModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 8.2.1.2 收货差异（**处理**）。
 *
 * <p>命名空间 {@code /warehouse/goods-receipt-discrepancies}：与 8.2.1.1 的
 * {@code /warehouse/goods-receipts} **并列**而不是挂在它下面 ——
 * 差异处理是一条独立的业务流程（接手 → 留证 → 决定 → 索赔 → 结案），
 * 挂在收货单子树里会让「一张收货单」与「一条差异的处理过程」两种生命周期混成一个资源。
 *
 * <p>刻意**不**复用 {@code /warehouse/goods-receipts/{id}/discrepancies}：
 * 那样收货单的 id 与差异处理的 id 会在同一段路径里长得一模一样，
 * 而它们分属两张表、两套 id、两种含义，接口一旦混淆，404 会变成最难查的那类错。
 *
 * <p>权限码沿用 {@code warehouse.}：读用 {@code warehouse.inventory.read}，
 * 全部写动作（接手 / 留证 / 决定 / 索赔 / 提交 / 撤回 / 结案）挂
 * {@code warehouse.inventory.update} —— 拒收撤出是真的减库存，权限等级与调拨发货同档。
 *
 * <p>租户**不**来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p>动作端点全是 **POST**：它们都是「发生一次业务事件」
 * （接手、留证、决定、发起索赔、提交、撤回、结案），用 PUT 会被 HTTP 语义
 * 读成「把记录设置成某个状态」，而实际发生的是库存移动 + 分录落库。
 */
@RestController
@RequestMapping("/warehouse/goods-receipt-discrepancies")
public class GoodsReceiptDiscrepancyController {

  private final AccessService access;
  private final GoodsReceiptDiscrepancyService handling;

  public GoodsReceiptDiscrepancyController(
      AccessService access, GoodsReceiptDiscrepancyService handling) {
    this.access = access;
    this.handling = handling;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 差异处理能力：四态状态机、四种处理决定、索赔与凭证词表、五条下游出口接入情况，
   * 以及可售硬规则 / 索赔单归属 / 拒收不入账 / 不许改判四条口径说明。
   *
   * <p>前端据此渲染处理决定按钮与去向质量状态选择器 ——
   * 未交付的下游**显式报不可用并给出归属模块**，而不是给一个点下去没反应的选项。
   */
  @GetMapping("/capabilities")
  public GoodsReceiptDiscrepancyCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> handling.capabilities());
  }

  /** 处理记录列表 + 全量汇总（**合计不受分页影响**）+ 状态 / 去向质量状态 / 下拉词表。 */
  @GetMapping
  public DiscrepancyHandlingPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID receiptId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) List<String> status,
      @RequestParam(required = false) List<String> disposition,
      @RequestParam(required = false) List<String> discrepancyType,
      @RequestParam(required = false) List<String> claimStatus,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            handling.page(
                new DiscrepancyHandlingQuery(
                    receiptId, warehouseId, status, disposition, discrepancyType, claimStatus,
                    keyword, sort, page, size),
                a));
  }

  /**
   * 处理记录详情：处理记录 + 原始差异事实 + 凭证 + 库存流转 + 索赔 + 当前状态的合法下一步。
   *
   * <p>跨租户在 RLS 下查不到 → 404（403 会泄露「这条差异存在」）。
   */
  @GetMapping("/{handlingId}")
  public DiscrepancyHandlingDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID handlingId) {
    return call(auth, "inventory.read", a -> handling.detail(handlingId));
  }

  /**
   * 接手一条 8.2.1.1 记录下来的差异事实，建处理记录（进入待处理）。
   *
   * <p>一条差异一个处理过程（DB UNIQUE）：重复接手返回 409 并提示「要改判请另登记一条差异」，
   * 而不是默默返回既有的那条 —— 那样现场会以为新接手成功而其实什么都没发生。
   */
  @PostMapping
  public DiscrepancyHandlingSaved intake(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DiscrepancyHandlingIntakeRequest request) {
    return call(
        auth, "inventory.update", a -> handling.intake(a, request == null ? emptyIntake() : request));
  }

  /**
   * 拍照留证：凭证本体落 PostgreSQL（bytea），单条上限 1.5 MB。
   *
   * <p>待处理与已决定状态都能留证（货损照片常在「先拒收、后补证据」时补拍）；
   * 已关闭是终态，再挂证据会让「当时结案时有什么凭证」永远答不出来。
   */
  @PostMapping("/{handlingId}/evidence")
  public DiscrepancyEvidenceRow addEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID handlingId,
      @Valid @RequestBody DiscrepancyEvidenceRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> handling.addEvidence(a, handlingId, request == null ? emptyEvidence() : request));
  }

  /**
   * 提交处理决定：拒收 / 部分接收 / 待检入库 / 正常入库，并写对应的库存流转。
   *
   * <p>蓝图硬规则「破损与短少必须拒收或待检入库」在这里生效：风险差异类型按「正常入库」
   * 会被 422 拒绝，数据库层还有两道触发器独立兜底。
   */
  @PostMapping("/{handlingId}/decide")
  public DiscrepancyHandlingSaved decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID handlingId,
      @Valid @RequestBody DiscrepancyDecisionRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> handling.decide(a, handlingId, request == null ? emptyDecision() : request));
  }

  /**
   * 发起索赔：只记来源单据与索赔意图 / 状态，**不建假采购索赔单**。
   * 6.3.2.2 交付后由上游订阅事件建自己的索赔单并回填 claimRefId。
   */
  @PostMapping("/{handlingId}/claim")
  public DiscrepancyHandlingSaved claim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID handlingId,
      @Valid @RequestBody DiscrepancyClaimRequest request) {
    return call(
        auth, "inventory.update", a -> handling.claim(a, handlingId, request == null ? emptyClaim() : request));
  }

  /** 提交索赔给对方。已提交不可撤回。 */
  @PostMapping("/{handlingId}/claim/submit")
  public DiscrepancyClaimRow submitClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID handlingId,
      @Valid @RequestBody DiscrepancyClaimSubmitRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> handling.submitClaim(a, handlingId, request == null ? emptySubmit() : request));
  }

  /** 提交前撤回索赔。已提交不可撤回 —— 否则对账那边会出现一笔凭空消失的索赔。 */
  @PostMapping("/{handlingId}/claim/withdraw")
  public DiscrepancyClaimRow withdrawClaim(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID handlingId,
      @Valid @RequestBody DiscrepancyClaimWithdrawRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> handling.withdrawClaim(a, handlingId, request == null ? emptyWithdraw() : request));
  }

  /**
   * 结案。要求已决定，且必须有凭证 / 库存流转 / 索赔三者之一 ——
   * 关掉一条空白的差异处理等于把货损痕迹抹掉（DB 触发器钉死）。
   */
  @PostMapping("/{handlingId}/close")
  public DiscrepancyHandlingSaved close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID handlingId,
      @Valid @RequestBody DiscrepancyHandlingCloseRequest request) {
    return call(
        auth,
        "inventory.update",
        a -> handling.close(a, handlingId, request == null ? emptyClose() : request));
  }

  // 空请求体落到这里：{@code @Valid} 对 null 不生效，必须由服务层报出可读错误
  // （例如「缺少 expectedVersion」），而不是 NPE。

  private static DiscrepancyHandlingIntakeRequest emptyIntake() {
    return new DiscrepancyHandlingIntakeRequest(null);
  }

  private static DiscrepancyEvidenceRequest emptyEvidence() {
    return new DiscrepancyEvidenceRequest(null, null, null, null, null, null);
  }

  private static DiscrepancyDecisionRequest emptyDecision() {
    return new DiscrepancyDecisionRequest(
        null, null, null, null, null, null, null, null, null, null);
  }

  private static DiscrepancyClaimRequest emptyClaim() {
    return new DiscrepancyClaimRequest(null, null, null, null, null, null, null, null);
  }

  private static DiscrepancyClaimSubmitRequest emptySubmit() {
    return new DiscrepancyClaimSubmitRequest(null);
  }

  private static DiscrepancyClaimWithdrawRequest emptyWithdraw() {
    return new DiscrepancyClaimWithdrawRequest(null, null);
  }

  private static DiscrepancyHandlingCloseRequest emptyClose() {
    return new DiscrepancyHandlingCloseRequest(null, null);
  }
}
