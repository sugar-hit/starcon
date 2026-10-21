package com.starcon.trade;

import static com.starcon.trade.MassProductionModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
 * 6.6.2.2 转量产与建档。
 *
 * <p>命名空间 {@code /trade/mass-productions}：后端命名空间<b>按服务走</b>（与 6.6.1.1 的
 * {@code /trade/development-projects}、6.6.1.2 的 {@code /trade/development-samples}、
 * 6.6.2.1 的 {@code /trade/trial-productions} 同一纪律）。
 * 前端路由是 {@code /purchase/development/mass-production} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.mass-production.read} /
 * {@code trade.mass-production.update}。本车道<b>不改 identity 的权限真相源</b>（那是另一个服务的
 * 迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>🔴 状态不是「前端传什么就存什么」</b>：量产计划状态只能通过
 * {@code POST /{id}/transitions} 推进，且只能沿状态机上存在的边走。
 *
 * <p><b>🔴 正式 SKU 不在本服务建</b>：写入所有权在 catalog（3.1.2.1）。
 * 本模块只落<b>建档请求事实</b>，真正的建档在提交时经 catalog 的授权接口完成。
 */
@RestController
@RequestMapping("/trade/mass-productions")
public class MassProductionController {

  private final AccessService access;
  private final MassProductionService service;

  public MassProductionController(AccessService access, MassProductionService service) {
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
   * <p>存在的理由：**正式 SKU 建档**（catalog）**确实有入口** ⇒ 可用；
   * **采购需求**（6.2.1.2，同库）只能**重算触发** ⇒ 可用但参数进不去；
   * **价格模型**（5.3.1.1，未开发）与**生产计划下达**（8.4.1 组装单）**做不到** ——
   * 哪一个接通了必须由接口自己报出。
   */
  @GetMapping("/capabilities")
  public MassProductionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "mass-production.read", a -> service.capabilities());
  }

  // ================================================================ 下拉项

  /** 状态下拉（含每个状态允许迁到哪些状态）、决策结论、决策来源、建档状态、属性状态。 */
  @GetMapping("/options")
  public MassProductionOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "mass-production.read", a -> service.options());
  }

  // ================================================================ 概览

  /** 按状态分组的概览 + 按币种分组的目标成本。 */
  @GetMapping("/summary")
  public MassProductionSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "mass-production.read", a -> service.summary());
  }

  // ================================================================ 候选

  /** 可转量产的试产单候选（上游 6.6.2.1，同库只读）。 */
  @GetMapping("/trial-candidates")
  public List<MassProductionTrialCandidateRow> trialCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "mass-production.read", a -> service.trialCandidates());
  }

  /** catalog 的 BOM 版本候选（跨服务只读；量产 BOM 要选的就是它）。 */
  @GetMapping("/bom-version-candidates")
  public List<ProductionTrialModels.ProductionTrialBomVersionCandidateRow> bomVersionCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "mass-production.read", a -> service.bomVersionCandidates(keyword, page, size, auth));
  }

  /** catalog 的 SPU 候选（SKU 建档的「目标键」）。 */
  @GetMapping("/spu-candidates")
  public List<MassProductionCatalogSpuRow> spuCandidates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "mass-production.read", a -> service.spuCandidates(keyword, auth));
  }

  /** catalog 的商品属性候选（建档载荷里「属性」那一段的取值范围）。 */
  @GetMapping("/spu-attributes")
  public List<MassProductionCatalogAttributeRow> spuAttributes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID spuId) {
    return call(auth, "mass-production.read", a -> service.spuAttributes(spuId, auth));
  }

  // ================================================================ 量产计划清单

  /** 量产计划清单（真分页 + 真筛选）。 */
  @GetMapping
  public MassProductionPlanPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID trialOrderId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "mass-production.read",
        a -> service.listPlans(status, trialOrderId, keyword, page, size));
  }

  // ================================================================ 量产计划

  /** 建量产计划。初始状态恒为「已建计划」。 */
  @PostMapping
  public MassProductionPlanDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MassProductionCreateInput input) {
    return call(
        auth, "mass-production.update", a -> service.createPlan(input, a.userId(), auth));
  }

  /** 量产计划详情：计划 + BOM 快照 + 建档请求 + 交接留痕 + 迁移历史 + 成本对比。 */
  @GetMapping("/{id}")
  public MassProductionPlanDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "mass-production.read", a -> service.planDetail(id));
  }

  /**
   * 改量产计划（<b>整体替换</b>语义：请求体就是期望的最终状态）。
   *
   * <p>🔴 上游试产单与 BOM 版本<b>不可改</b>：改了它们，已定版的组件快照与成本对比
   * 就全部对不上了 —— 所以它们根本不在改单输入里。
   */
  @PutMapping("/{id}")
  public MassProductionPlanDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MassProductionUpdateInput input) {
    return call(auth, "mass-production.update", a -> service.updatePlan(id, input));
  }

  /**
   * 推进量产计划状态。
   *
   * <p>🔴 只能沿状态机上存在的边走；已关闭 / 已取消是终态；
   * 每次迁移都会落一条不可改写的事件。
   */
  @PostMapping("/{id}/transitions")
  public MassProductionPlanDetail transition(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MassProductionTransitionInput input) {
    var actor = access.require(auth, "trade.mass-production.update");
    // ---- 第一段事务：状态迁移（「已下达」是硬事实，先落库） ----
    var detail =
        access.run(
            actor,
            "trade.mass-production.update",
            () -> service.transitionPlan(id, input, actor.userId(), auth));
    // ---- 第二段事务：下达之后的**下游交接** ----
    //   🔴 交接**可能失败**（上游不可达），而「已下达」已经落库 ⇒ 交接失败**不能**回滚下达。
    //   ⇒ 交接在**它自己的事务**里做；失败时再开一个新事务把 FAILED 留痕写下去
    //   （与 6.3.2.1 跟单交期的做法同构）。
    if (MassProductionPlanStatus.RELEASED.name().equals(detail.plan().status())) {
      try {
        access.run(
            actor,
            "trade.mass-production.update",
            () -> {
              service.writeDownstreamHandoffs(id, actor.userId(), auth);
              return Boolean.TRUE;
            });
      } catch (RuntimeException e) {
        final RuntimeException failure = e;
        access.run(
            actor,
            "trade.mass-production.update",
            () -> {
              service.recordHandoffFailure(id, actor.userId(), failure);
              return Boolean.TRUE;
            });
      }
    }
    return detail;
  }

  // ================================================================ 量产 BOM 组件行

  /** 量产 BOM 组件行快照（定版那一刻从 catalog 原样搬过来的）。 */
  @GetMapping("/{id}/bom-lines")
  public List<MassProductionBomLineRow> bomLines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "mass-production.read", a -> service.listBomLines(id));
  }

  // ================================================================ SKU 建档

  /** SKU 建档请求。没建过时返回 {@code null}。 */
  @GetMapping("/{id}/sku-filing")
  public MassProductionSkuFilingRow filing(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "mass-production.read", a -> service.filingOf(id));
  }

  /**
   * 建一条 SKU 建档请求（**本服务侧的待办事实**，不是 SKU 本身）。
   *
   * <p>🔴 一张量产计划**只允许一条**建档请求：两条建档请求 = 两个 SKU。
   */
  @PostMapping("/{id}/sku-filing")
  public MassProductionSkuFilingRow createFiling(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MassProductionSkuFilingInput input) {
    return call(
        auth, "mass-production.update", a -> service.createFiling(id, input, a.userId(), auth));
  }

  /**
   * 提交 SKU 建档（<b>真调商品中心</b>）。
   *
   * <p>🔴 <b>幂等纪律</b>：已建成（{@code CONFIRMED}）与<b>结果未知</b>（{@code UNKNOWN}）都禁止再提交 ——
   * 商品中心的建档端点不接受幂等键，盲目重试会建出重复 SKU。
   */
  @PostMapping("/{id}/sku-filing/{filingId}/submit")
  public ResponseEntity<MassProductionSkuFilingRow> submitFiling(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID filingId) {
    var filing =
        call(
            auth,
            "mass-production.update",
            a -> service.submitFiling(id, filingId, a.userId(), auth));
    // 🔴 状态码由**落库的状态**映射，而不是由异常决定：
    //   失败 / 部分完成 / 结果未知都必须**留下记录**（抛异常会回滚事务，把记录一起抹掉），
    //   所以这里返回 4xx/5xx 但**不抛**，让事务正常提交。
    HttpStatus status =
        switch (filing.status()) {
          case "CONFIRMED" -> HttpStatus.OK;
          case "FAILED" -> HttpStatus.BAD_REQUEST;
          case "UNKNOWN" -> HttpStatus.SERVICE_UNAVAILABLE;
          case "PARTIAL" -> HttpStatus.BAD_REQUEST;
          default -> HttpStatus.OK;
        };
    return ResponseEntity.status(status).body(filing);
  }

  // ================================================================ 下游交接

  /** 下游交接留痕（6.2.1.2 采购需求 / 5.3.1.1 价格模型）。 */
  @GetMapping("/{id}/handoffs")
  public List<MassProductionHandoffRow> handoffs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "mass-production.read", a -> service.listHandoffs(id));
  }

  /**
   * 手动重新触发 6.2.1.2 补货建议重算。
   *
   * <p>🔴 仍然只是<b>重算触发</b>：量产参数<b>没有</b>进到 6.2.1.2 的计算里
   * （它的起订额与交期是人工维护的策略），如实标 {@code massProductionInputApplied=false}。
   */
  @PostMapping("/{id}/handoffs/replenishment")
  public List<MassProductionHandoffRow> triggerReplenishment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var actor = access.require(auth, "trade.mass-production.update");
    try {
      access.run(
          actor,
          "mass-production.update",
          () -> {
            service.triggerReplenishment(id, actor.userId(), auth);
            return Boolean.TRUE;
          });
    } catch (RuntimeException e) {
      final RuntimeException failure = e;
      access.run(
          actor,
          "mass-production.update",
          () -> {
            service.recordHandoffFailure(id, actor.userId(), failure);
            return Boolean.TRUE;
          });
    }
    return call(auth, "mass-production.read", a -> service.listHandoffs(id));
  }

  // ================================================================ 成本对比

  /**
   * 量产目标成本 vs 上游试产实测成本。
   *
   * <p>🔴 不可比时 {@code comparable=false} 且各金额字段为 {@code null}（<b>不是 0</b>），
   * <b>不做固定汇率折算</b>。
   */
  @GetMapping("/{id}/cost-comparison")
  public MassProductionCostComparisonRow costComparison(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "mass-production.read", a -> service.costComparison(id));
  }
}
