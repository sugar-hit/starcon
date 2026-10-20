package com.starcon.trade;

import static com.starcon.trade.PurchasePlanModels.*;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
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
 * 6.2.1.3 采购计划。
 *
 * <p>命名空间 {@code /trade/purchase-plans}：后端命名空间<b>按服务走</b>（与 6.2.1.2 的
 * {@code /trade/replenishment-suggestions}、6.2.1.1 的 {@code /trade/stockout-risk} 同一纪律）。
 * 前端路由是 {@code /purchase/demand/plans} —— 那是<b>前端</b>路由，与后端命名空间是两件事。
 *
 * <p>权限码沿用 {@code trade.<page>.<action>}：{@code trade.purchase-plan.read} /
 * {@code trade.purchase-plan.update}。本车道<b>不改 identity 的权限真相源</b>（那是另一个服务的
 * 迁移版本号），而是沿用 trade 服务既有的权限码体系。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/purchase-plans")
public class PurchasePlanController {

  private final AccessService access;
  private final PurchasePlanService service;

  public PurchasePlanController(AccessService access, PurchasePlanService service) {
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
   * <p>存在的理由：补货建议在 6.2.1.2、目标仓与法人在 warehouse、锁库的物理事实在 warehouse 的
   * 库存预占、大促日历本模块自带。哪一个接通了必须由接口自己报出。
   */
  @GetMapping("/capabilities")
  public PurchasePlanCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-plan.read", a -> service.capabilities());
  }

  // ================================================================ 参照下拉

  /** 建计划需要的目标仓 / 法人 / 大促下拉项。 */
  @GetMapping("/options")
  public PurchasePlanOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "purchase-plan.read", a -> service.options(auth));
  }

  // ================================================================ 大促日历

  /** 大促日历列表（真分页 + 真筛选）。 */
  @GetMapping("/campaigns")
  public PurchasePlanCampaignPage campaigns(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "purchase-plan.read", a -> service.pageCampaigns(code, status, shopCode, page, size));
  }

  /** 登记一条大促档期。 */
  @PostMapping("/campaigns")
  public PurchasePlanCampaignRow createCampaign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchasePlanCampaignInput input) {
    return call(
        auth, "purchase-plan.update", a -> service.createCampaign(input, a.userId()));
  }

  /** 更新一条大促档期。 */
  @PutMapping("/campaigns/{id}")
  public PurchasePlanCampaignRow updateCampaign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchasePlanCampaignInput input) {
    return call(
        auth, "purchase-plan.update", a -> service.updateCampaign(id, input, a.userId()));
  }

  /**
   * 删除一条大促档期。
   *
   * <p><b>被计划引用时删不掉</b>（409）：大促档期是计划的一部分依据。
   */
  @DeleteMapping("/campaigns/{id}")
  public ResponseEntity<Void> deleteCampaign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(auth, "purchase-plan.update", a -> {
      service.deleteCampaign(id);
      return null;
    });
    return ResponseEntity.noContent().build();
  }

  // ================================================================ 锁库策略

  /** 锁库策略列表（真分页）。 */
  @GetMapping("/lock-policies")
  public PurchasePlanLockPolicyPage lockPolicies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String planType,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "purchase-plan.read", a -> service.pageLockPolicies(planType, page, size));
  }

  /** 登记一条锁库策略（版本化：新策略收口旧策略）。 */
  @PostMapping("/lock-policies")
  public PurchasePlanLockPolicyRow upsertLockPolicy(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchasePlanLockPolicyInput input) {
    return call(
        auth, "purchase-plan.update", a -> service.upsertLockPolicy(input, a.userId()));
  }

  // ================================================================ 汇总视图

  /**
   * 汇总视图：<b>日常一段、大促一段</b>。
   *
   * <p>刻意不给一个把两种备货量加在一起的总数字 —— 那个数字会让日常补货失焦。
   */
  @GetMapping("/summary")
  public PurchasePlanSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) LocalDate periodStart,
      @RequestParam(required = false) LocalDate periodEnd,
      @RequestParam(required = false) UUID targetWarehouseId) {
    return call(
        auth,
        "purchase-plan.read",
        a -> service.summary(periodStart, periodEnd, targetWarehouseId));
  }

  // ================================================================ 采购计划

  /** 采购计划列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public PurchasePlanPage plans(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String planType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID campaignId,
      @RequestParam(required = false) UUID targetWarehouseId,
      @RequestParam(required = false) String priority,
      @RequestParam(required = false) LocalDate periodStart,
      @RequestParam(required = false) LocalDate periodEnd,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "period") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "purchase-plan.read",
        a ->
            service.pagePlans(
                new PurchasePlanService.PurchasePlanQuery(
                    planType,
                    status,
                    campaignId,
                    targetWarehouseId,
                    priority,
                    periodStart,
                    periodEnd,
                    keyword,
                    sort,
                    page,
                    size)));
  }

  /** 建一张采购计划（周期 / 类型 / 目标仓 / 法人）。 */
  @PostMapping
  public PurchasePlanRow createPlan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PurchasePlanCreateInput input) {
    return call(
        auth, "purchase-plan.update", a -> service.createPlan(input, a.userId(), auth));
  }

  /** 计划详情：头 + 计划行。 */
  @GetMapping("/{id}")
  public PurchasePlanDetail plan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "purchase-plan.read", a -> service.planDetail(id));
  }

  /** 确认一张计划（草稿 → 已确认）。没有计划行不许确认。 */
  @PostMapping("/{id}/confirm")
  public PurchasePlanRow confirm(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "purchase-plan.update", a -> service.confirmPlan(id, a.userId()));
  }

  /**
   * 从 6.2.1.2 的补货建议汇总成计划行。
   *
   * <p>这是一次<b>读上游 + 写本地</b>的动作：来源是 6.2.1.2 的补货建议批次（同一服务，只读）。
   */
  @PostMapping("/{id}/aggregate")
  public PurchasePlanAggregateResult aggregate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PurchasePlanAggregateInput input) {
    return call(
        auth, "purchase-plan.update", a -> service.aggregate(id, input, a.userId()));
  }

  // ================================================================ 计划行

  /** 计划行列表（真分页 + 真筛选）。 */
  @GetMapping("/{id}/lines")
  public PurchasePlanLinePage lines(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String lockStatus,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "purchase-plan.read",
        a -> service.pageLines(id, skuCode, keyword, status, lockStatus, page, size));
  }

  /** 计划行详情：行 + 调整留痕 + 锁定留痕。 */
  @GetMapping("/{id}/lines/{lineId}")
  public PurchasePlanLineDetail line(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId) {
    return call(auth, "purchase-plan.read", a -> service.lineDetail(id, lineId));
  }

  /** 调整一个计划行（数量 / 期望到货日 / 优先级 / 目标仓 / 备注），理由必填。 */
  @PostMapping("/{id}/lines/{lineId}/adjust")
  public PurchasePlanLineRow adjust(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @Valid @RequestBody PurchasePlanLineAdjustInput input) {
    return call(
        auth,
        "purchase-plan.update",
        a -> service.adjustLine(id, lineId, input, a.userId(), auth));
  }

  /**
   * 锁定一个计划行 —— 经 warehouse 的授权写接口真登记一笔库存预占。
   *
   * <p>端口未接线 / 上游拒了 ⇒ 服务层<b>先记下失败痕迹再返回</b>（整条链路在一个事务里，
   * 抛异常会把痕迹一起回滚掉），由本控制器在<b>事务提交之后</b>翻成对应的 HTTP 状态码：
   * 绝不会返回「已锁定」却没在仓库占住货。
   */
  @PostMapping("/{id}/lines/{lineId}/lock")
  public PurchasePlanLockResult lock(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID lineId,
      @RequestBody(required = false) PurchasePlanLockInput input) {
    PurchasePlanLockResult result =
        call(
            auth,
            "purchase-plan.update",
            a ->
                service.lockLine(
                    id,
                    lineId,
                    input == null ? new PurchasePlanLockInput(null) : input,
                    a.userId(),
                    auth));
    if (result.failed()) {
      throw new ResponseStatusException(
          HttpStatusCode.valueOf(result.errorStatus() == null ? 503 : result.errorStatus()),
          result.message());
    }
    return result;
  }

  // ================================================================ 导出

  /**
   * 导出 CSV 给采购员。
   *
   * <p>真导出：内容就是这张计划的全部计划行。计划类型与周期各占一列 ——
   * 大促与日常必须能在文件里分开看。
   */
  @GetMapping(value = "/{id}/export", produces = "text/csv")
  public ResponseEntity<byte[]> export(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    byte[] bytes = call(auth, "purchase-plan.read", a -> service.exportCsv(id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, "text/csv; charset=UTF-8")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"purchase-plan-" + id + ".csv\"")
        .body(bytes);
  }
}
