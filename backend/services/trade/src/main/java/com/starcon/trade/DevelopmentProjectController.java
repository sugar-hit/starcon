package com.starcon.trade;

import static com.starcon.trade.DevelopmentProjectModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.6.1.1 开发立项。
 *
 * <p>命名空间 {@code /trade/development-projects}：后端命名空间<b>按服务走</b>（与 6.2.1.3 的
 * {@code /trade/purchase-plans}、6.2.1.2 的 {@code /trade/replenishment-suggestions} 同一纪律）。
 * 前端路由是 {@code /purchase/development/projects} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.development-project.read} /
 * {@code trade.development-project.update}。本车道<b>不改 identity 的权限真相源</b>（那是另一个服务的
 * 迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>🔴 状态不是「前端传什么就存什么」</b>：状态只能通过 {@code POST /{id}/transitions} 推进，
 * 且只能沿状态机上存在的边走。服务层与数据库层各挡一次。
 */
@RestController
@RequestMapping("/trade/development-projects")
public class DevelopmentProjectController {

  private final AccessService access;
  private final DevelopmentProjectService service;

  public DevelopmentProjectController(AccessService access, DevelopmentProjectService service) {
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
   * <p>存在的理由：目标成本在 5.3.1.1（catalog，已交付）、市场数据在 14 广告
   * （**reporting 服务在本仓不存在**）、下游打样 / 试产未交付、建档归 catalog。
   * 哪一个接通了必须由接口自己报出。
   */
  @GetMapping("/capabilities")
  public DevelopmentProjectCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "development-project.read", a -> service.capabilities());
  }

  // ================================================================ 参照下拉

  /** 状态下拉（含每个状态允许迁到哪些状态）、里程碑类型、成本来源、交接单类型。 */
  @GetMapping("/options")
  public DevelopmentProjectOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "development-project.read", a -> service.options());
  }

  // ================================================================ 概览

  /** 按状态分组的概览。 */
  @GetMapping("/summary")
  public DevelopmentProjectSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "development-project.read", a -> service.summary());
  }

  // ================================================================ 清单

  /** 立项清单（真分页 + 真筛选）。 */
  @GetMapping
  public DevelopmentProjectPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) UUID ownerId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "development-project.read",
        a -> service.list(status, category, ownerId, keyword, page, size));
  }

  // ================================================================ 立项

  /** 立项（建单）。初始状态恒为「评估」。 */
  @PostMapping
  public DevelopmentProjectDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DevelopmentProjectCreateInput input) {
    return call(
        auth,
        "development-project.update",
        a -> service.create(input, a.userId()));
  }

  /** 立项详情：立项记录 + 里程碑 + 目标市场 + 规格候选 + 市场观察 + 迁移历史 + 交接单。 */
  @GetMapping("/{id}")
  public DevelopmentProjectDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-project.read", a -> service.detail(id));
  }

  /**
   * 改立项（<b>整体替换</b>语义：请求体就是期望的最终状态）。
   *
   * <p>🔴 目标售价 / 目标成本变了 ⇒ 目标毛利率由<b>服务端</b>重算。
   */
  @PutMapping("/{id}")
  public DevelopmentProjectDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentProjectUpdateInput input) {
    return call(auth, "development-project.update", a -> service.update(id, input));
  }

  // ================================================================ 状态迁移

  /**
   * 推进状态。
   *
   * <p>🔴 只能沿状态机上存在的边走；终止是终态；跳过打样 / 试产必须有显式豁免记录。
   * 每次迁移都会落一条不可改写的事件（谁 / 何时 / 从哪到哪 / 为什么）。
   */
  @PostMapping("/{id}/transitions")
  public DevelopmentProjectDetail transition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentTransitionInput input) {
    return call(
        auth,
        "development-project.update",
        a -> service.transition(id, input, a.userId()));
  }

  // ================================================================ 里程碑计划

  /** 里程碑计划清单。 */
  @GetMapping("/{id}/milestones")
  public List<DevelopmentMilestoneRow> milestones(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-project.read", a -> service.listMilestones(id));
  }

  /** 登记里程碑计划。 */
  @PostMapping("/{id}/milestones")
  public List<DevelopmentMilestoneRow> createMilestone(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentMilestoneInput input) {
    return call(auth, "development-project.update", a -> service.createMilestone(id, input));
  }

  /** 改里程碑计划。 */
  @PutMapping("/{id}/milestones/{milestoneId}")
  public List<DevelopmentMilestoneRow> updateMilestone(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID milestoneId,
      @Valid @RequestBody DevelopmentMilestoneInput input) {
    return call(
        auth,
        "development-project.update",
        a -> service.updateMilestone(id, milestoneId, input));
  }

  /** 删里程碑计划。 */
  @DeleteMapping("/{id}/milestones/{milestoneId}")
  public List<DevelopmentMilestoneRow> deleteMilestone(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID milestoneId) {
    return call(auth, "development-project.update", a -> service.deleteMilestone(id, milestoneId));
  }

  // ================================================================ 目标市场与平台

  /** 目标市场与平台清单。 */
  @GetMapping("/{id}/markets")
  public List<DevelopmentMarketRow> markets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-project.read", a -> service.listMarkets(id));
  }

  /** 登记目标市场与平台。 */
  @PostMapping("/{id}/markets")
  public List<DevelopmentMarketRow> createMarket(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentMarketInput input) {
    return call(auth, "development-project.update", a -> service.createMarket(id, input));
  }

  /** 删目标市场与平台。 */
  @DeleteMapping("/{id}/markets/{marketId}")
  public List<DevelopmentMarketRow> deleteMarket(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID marketId) {
    return call(auth, "development-project.update", a -> service.deleteMarket(id, marketId));
  }

  // ================================================================ 规格候选

  /** 规格候选清单。 */
  @GetMapping("/{id}/spec-candidates")
  public List<DevelopmentSpecCandidateRow> specCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-project.read", a -> service.listSpecCandidates(id));
  }

  /** 登记规格候选。 */
  @PostMapping("/{id}/spec-candidates")
  public List<DevelopmentSpecCandidateRow> createSpecCandidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentSpecCandidateInput input) {
    return call(
        auth,
        "development-project.update",
        a -> service.createSpecCandidate(id, input));
  }

  /** 删规格候选。 */
  @DeleteMapping("/{id}/spec-candidates/{candidateId}")
  public List<DevelopmentSpecCandidateRow> deleteSpecCandidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID candidateId) {
    return call(
        auth,
        "development-project.update",
        a -> service.deleteSpecCandidate(id, candidateId));
  }

  // ================================================================ 市场观察（手工录入）

  /** 手工录入的市场数据清单（**系统侧市场数据取不到**，只有人工观察）。 */
  @GetMapping("/{id}/market-observations")
  public List<DevelopmentMarketObservationRow> marketObservations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-project.read", a -> service.listMarketObservations(id));
  }

  /** 登记一条手工录入的市场数据（**来源与采集时间必填**）。 */
  @PostMapping("/{id}/market-observations")
  public List<DevelopmentMarketObservationRow> createMarketObservation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentMarketObservationInput input) {
    return call(
        auth,
        "development-project.update",
        a -> service.createMarketObservation(id, input));
  }

  // ================================================================ 目标成本估算

  /** 预览目标成本估算（**不落库**）：从 5.3.1.1 利润测算器（catalog）读参考 SKU 的采购成本。 */
  @GetMapping("/{id}/cost-estimate")
  public DevelopmentCostEstimateView previewCostEstimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) UUID referenceSkuId) {
    return call(
        auth,
        "development-project.read",
        a -> service.previewCostEstimate(id, referenceSkuId, auth));
  }

  /** 应用目标成本估算（落库快照 + 服务端重算目标毛利率）。 */
  @PostMapping("/{id}/cost-estimate")
  public DevelopmentProjectDetail applyCostEstimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) UUID referenceSkuId,
      @RequestParam(required = false) String referenceSkuCode) {
    return call(
        auth,
        "development-project.update",
        a -> service.applyCostEstimate(id, referenceSkuId, referenceSkuCode, auth));
  }

  // ================================================================ SKU 建档候选

  /** 可转 SKU 建档的候选参数（🔴 本模块**只产出候选参数**，不建 SKU）。 */
  @GetMapping("/{id}/sku-candidate")
  public DevelopmentSkuCandidate skuCandidate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-project.read", a -> service.skuCandidate(id));
  }

  // ================================================================ 交接单

  /** 交接单清单（打样单 / 试产单 / SKU 建档）。 */
  @GetMapping("/{id}/handoffs")
  public List<DevelopmentHandoffRow> handoffs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "development-project.read", a -> service.listHandoffs(id));
  }

  /**
   * 产出一条下游交接单。
   *
   * <p>🔴 6.6.1.2 / 6.6.2.1 未交付，3.1.1.1 建档归 catalog ⇒ 本模块**只产交接单**，不代下游建单。
   */
  @PostMapping("/{id}/handoffs")
  public DevelopmentHandoffRow createHandoff(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DevelopmentHandoffInput input) {
    return call(
        auth,
        "development-project.update",
        a -> service.createHandoff(id, input, a.userId()));
  }
}
