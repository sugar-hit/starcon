package com.starcon.trade;

import static com.starcon.trade.ProductionTrialModels.*;

import jakarta.validation.Valid;
import java.util.List;
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
 * 6.6.2.1 试产单。
 *
 * <p>命名空间 {@code /trade/trial-productions}：后端命名空间<b>按服务走</b>（与 6.6.1.1 的
 * {@code /trade/development-projects}、6.6.1.2 的 {@code /trade/development-samples} 同一纪律）。
 * 前端路由是 {@code /purchase/development/trials} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.production-trial.read} /
 * {@code trade.production-trial.update}。本车道<b>不改 identity 的权限真相源</b>（那是另一个服务的
 * 迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>🔴 状态不是「前端传什么就存什么」</b>：试产单状态只能通过
 * {@code POST /{id}/transitions} 推进，且只能沿状态机上存在的边走。
 *
 * <p><b>🔴 组件领用需求止步于「需求」</b>：本模块<b>不扣库存</b> ——
 * 真实领用由 warehouse 侧 8.4.1 组装单承接，跨服务衔接留待授权 API / event。
 */
@RestController
@RequestMapping("/trade/trial-productions")
public class ProductionTrialController {

  private final AccessService access;
  private final ProductionTrialService service;

  public ProductionTrialController(AccessService access, ProductionTrialService service) {
    this.access = access;
    this.service = service;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：**真实库存扣减**（8.4.1 组装单）、**转量产建档**（6.6.2.2）、
   * **汇率**（5.2.2.1）三件事做不到，而**BOM 版本（3.5.2.1）**与**打样单（6.6.1.2）**可用 ——
   * 哪一个接通了必须由接口自己报出。
   */
  @GetMapping("/capabilities")
  public ProductionTrialCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "production-trial.read", a -> service.capabilities());
  }

  // ================================================================ 参照下拉

  /** 状态下拉（含每个状态允许迁到哪些状态）、需求状态、判定结果、成本类型、建议结论。 */
  @GetMapping("/options")
  public ProductionTrialOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "production-trial.read", a -> service.options());
  }

  // ================================================================ 概览

  /** 按状态分组的概览 + 按币种分组的成本与成本对比。 */
  @GetMapping("/summary")
  public ProductionTrialSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "production-trial.read", a -> service.summary());
  }

  // ================================================================ BOM 版本候选

  /** catalog 的 BOM 版本候选（跨服务只读；建单时要选的就是它）。 */
  @GetMapping("/bom-version-candidates")
  public List<ProductionTrialBomVersionCandidateRow> bomVersionCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "production-trial.read", a -> service.bomVersionCandidates(keyword, page, size, auth));
  }

  // ================================================================ 试产单清单

  /** 试产单清单（真分页 + 真筛选）。 */
  @GetMapping
  public ProductionTrialOrderPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID bomVersionId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "production-trial.read",
        a -> service.listOrders(status, bomVersionId, keyword, page, size));
  }

  // ================================================================ 试产单

  /**
   * 建试产单。初始状态恒为「已申请」。
   *
   * <p>🔴 建单时同步产出<b>组件领用需求</b>（从 catalog 的 BOM 版本需求量算，初始为「草稿」）
   * 与<b>成本头</b>（一个币种 + 可选的量产目标成本）。
   */
  @PostMapping
  public ProductionTrialOrderDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ProductionTrialCreateInput input) {
    return call(
        auth, "production-trial.update", a -> service.createOrder(input, a.userId(), auth));
  }

  /** 试产单详情：试产单 + 组件领用需求 + 质检标准 + 结果 + 成本 + 转量产建议 + 迁移历史。 */
  @GetMapping("/{id}")
  public ProductionTrialOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "production-trial.read", a -> service.orderDetail(id));
  }

  /**
   * 改试产单（<b>整体替换</b>语义：请求体就是期望的最终状态）。
   *
   * <p>🔴 BOM 版本、目标数量与币种<b>不可改</b>：改了它们，已算好的组件领用需求与成本对比
   * 就全部对不上了 —— 所以它们根本不在改单输入里。
   */
  @PutMapping("/{id}")
  public ProductionTrialOrderDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ProductionTrialUpdateInput input) {
    return call(auth, "production-trial.update", a -> service.updateOrder(id, input));
  }

  /**
   * 推进试产单状态。
   *
   * <p>🔴 只能沿状态机上存在的边走；已关闭 / 已取消是终态；
   * 每次迁移都会落一条不可改写的事件。
   */
  @PostMapping("/{id}/transitions")
  public ProductionTrialOrderDetail transition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ProductionTrialTransitionInput input) {
    return call(
        auth, "production-trial.update", a -> service.transitionOrder(id, input, a.userId()));
  }

  // ================================================================ 组件领用需求

  /** 组件领用需求清单（🔴 需求，不是库存）。 */
  @GetMapping("/{id}/component-requirements")
  public List<ProductionTrialComponentRequirementRow> componentRequirements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "production-trial.read", a -> service.listRequirements(id));
  }

  /**
   * 提交一条组件领用需求（草稿 → 已提交领用需求）。
   *
   * <p>🔴 <b>这一步不扣库存</b>：它只是把「要领什么、领多少」交出去。
   */
  @PostMapping("/{id}/component-requirements/{requirementId}/submit")
  public List<ProductionTrialComponentRequirementRow> submitRequirement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID requirementId) {
    return call(
        auth,
        "production-trial.update",
        a -> service.submitRequirement(id, requirementId, a.userId()));
  }

  /**
   * 记录「已由加工单承接」（已提交领用需求 → 已由加工单承接）。
   *
   * <p>🔴 存的是 8.4.1 组装单的<b>逻辑引用 + 展示快照</b>，<b>不跨库写</b>。
   */
  @PostMapping("/{id}/component-requirements/{requirementId}/release")
  public List<ProductionTrialComponentRequirementRow> releaseRequirement(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID requirementId,
      @Valid @RequestBody ProductionTrialRequirementReleaseInput input) {
    return call(
        auth,
        "production-trial.update",
        a -> service.releaseRequirement(id, requirementId, input, a.userId()));
  }

  // ================================================================ 质检标准

  /** 质检标准项清单（含判定结果）。 */
  @GetMapping("/{id}/inspection-items")
  public List<ProductionTrialInspectionItemRow> inspectionItems(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "production-trial.read", a -> service.listInspectionItems(id));
  }

  /** 登记一条质检标准项。 */
  @PostMapping("/{id}/inspection-items")
  public List<ProductionTrialInspectionItemRow> createInspectionItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ProductionTrialInspectionItemInput input) {
    return call(
        auth,
        "production-trial.update",
        a -> service.createInspectionItem(id, input, a.userId()));
  }

  /**
   * 记录一条质检判定。
   *
   * <p>🔴 判定<b>不可撤回成「待判定」</b> —— 撤回等于把「当时判过不合格」这段证据抹掉。
   */
  @PutMapping("/{id}/inspection-items/{itemId}")
  public List<ProductionTrialInspectionItemRow> judgeInspectionItem(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID itemId,
      @Valid @RequestBody ProductionTrialInspectionJudgmentInput input) {
    return call(
        auth,
        "production-trial.update",
        a -> service.judgeInspectionItem(id, itemId, input, a.userId()));
  }

  // ================================================================ 试产结果

  /** 试产结果（良率）。没记录过时返回 {@code null}（「没记录」与「良率 0」是两件事）。 */
  @GetMapping("/{id}/result")
  public ProductionTrialResultRow result(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "production-trial.read", a -> service.resultView(id));
  }

  /**
   * 记录试产结果（产出 / 合格数量 + 结论）。
   *
   * <p>🔴 <b>良率不收</b>：由数据库按 合格 ÷ 产出 推导（比例，6 位小数）。
   */
  @PostMapping("/{id}/result")
  public ProductionTrialResultRow recordResult(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ProductionTrialResultInput input) {
    return call(
        auth, "production-trial.update", a -> service.recordResult(id, input, a.userId()));
  }

  // ================================================================ 试产成本

  /** 试产成本（成本头 + 明细行）。 */
  @GetMapping("/{id}/cost")
  public ProductionTrialCostView cost(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "production-trial.read", a -> service.costView(id));
  }

  /** 追加一条成本明细行。 */
  @PostMapping("/{id}/cost/lines")
  public ProductionTrialCostView addCostLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ProductionTrialCostLineInput input) {
    return call(
        auth, "production-trial.update", a -> service.addCostLine(id, input, a.userId()));
  }

  /** 删一条成本明细行（合计金额由数据库触发器自动重算）。 */
  @DeleteMapping("/{id}/cost/lines/{lineId}")
  public ProductionTrialCostView deleteCostLine(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId) {
    return call(
        auth, "production-trial.update", a -> service.deleteCostLine(id, lineId, a.userId()));
  }

  /**
   * 「试产成本 vs 量产目标成本」的对比（**同一张单、同一个币种**）。
   *
   * <p>🔴 量产目标成本取不到 ⇒ {@code comparable=false} 且目标成本为 {@code null}
   * （<b>不是 0</b>），<b>不做固定汇率折算</b>。
   */
  @GetMapping("/{id}/cost-comparison")
  public ProductionTrialCostComparisonRow costComparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "production-trial.read", a -> service.costComparison(id));
  }

  // ================================================================ 转量产建议

  /** 转量产建议。没记录过时返回 {@code null}。 */
  @GetMapping("/{id}/mass-production-recommendation")
  public ProductionTrialMassProductionRecommendationRow recommendation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "production-trial.read", a -> service.recommendationView(id));
  }

  /**
   * 记录一条转量产建议（**建议事实**，不是 SKU 建档）。
   *
   * <p>🔴 「建议转量产」需要<b>举证</b>（良率达标 + 成本门槛达标）；
   * 「暂不建议转量产」不需要举证。
   */
  @PostMapping("/{id}/mass-production-recommendation")
  public ProductionTrialMassProductionRecommendationRow recordRecommendation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody ProductionTrialMassProductionRecommendationInput input) {
    return call(
        auth,
        "production-trial.update",
        a -> service.recordRecommendation(id, input, a.userId()));
  }
}
