package com.starcon.warehouse;

import static com.starcon.warehouse.InventoryHealthModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.6.2.1 库存健康。
 *
 * <p>命名空间 {@code /warehouse/inventory/health}，与总览、明细、台账、核对同一前缀， 浏览器只经 /api/warehouse 代理访问（白名单登记在
 * apps/web/src/lib/api-proxy.ts 的 {@code inventory/(?:…)} 组内）。
 *
 * <p>权限码沿用 {@code warehouse.}：读 {@code warehouse.inventory.read}， 触发评估（写）{@code
 * warehouse.inventory.update} —— 评估会落一份不可变的结论凭据， 而结算前置校验会引用它，因此它不是只读动作。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code
 * app.current_tenant}，由 RLS 在数据库层强制隔离。
 *
 * <p><b>红线判定的接口边界</b>：{@code GET /gate} 返回的是一个<b>判定</b>， 不是一次阻断。真正的阻断动作发生在 12 财务结算与 7.1.2.2
 * 对账各自的边界内 —— 本服务既不知道它们的单据状态，也不应该替它们扣单。下游要接的是 「结算前先问一句 gate，被阻断就拒绝」，而不是「由 warehouse 去改财务的单据状态」。
 */
@RestController
@RequestMapping("/warehouse/inventory/health")
public class InventoryHealthController {

  private final AccessService access;
  private final InventoryHealthService health;

  public InventoryHealthController(AccessService access, InventoryHealthService health) {
    this.access = access;
    this.health = health;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 评分卡列表（读最近一次评估的结果）。
   *
   * <p>页面上必须同时看到「得分」与「覆盖 N/6」：六个指标里当前只有 2 个能算， 分数高只说明已覆盖的维度没问题。
   */
  @GetMapping
  public InventoryHealthScorecardPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) java.time.OffsetDateTime from,
      @RequestParam(required = false) java.time.OffsetDateTime to,
      @RequestParam(required = false) java.math.BigDecimal accuracyThreshold,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        actor ->
            health.page(
                new InventoryHealthQuery(from, to, warehouseId, accuracyThreshold), page, size));
  }

  /** 准确率趋势（核对口径与盘点口径各自成序列）。 */
  @GetMapping("/trend")
  public InventoryHealthTrend trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) java.time.OffsetDateTime from,
      @RequestParam(required = false) java.time.OffsetDateTime to) {
    return call(
        auth,
        "inventory.read",
        actor -> health.trend(new InventoryHealthQuery(from, to, warehouseId, null)));
  }

  /**
   * 准确率红线判定 —— 蓝图那条「低于阈值应阻断结算与对账」的可查询入口。
   *
   * <p>返回 {@code blocked=true} 时给出的 {@code blockReason} 必须能自证： 要么带准确率与阈值，要么说明「无证据」是哪一段缺证据。
   */
  @GetMapping("/gate")
  public InventoryHealthGate gate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) java.time.OffsetDateTime from,
      @RequestParam(required = false) java.time.OffsetDateTime to,
      @RequestParam(required = false) java.math.BigDecimal accuracyThreshold) {
    return call(
        auth,
        "inventory.read",
        actor -> health.gate(new InventoryHealthQuery(from, to, warehouseId, accuracyThreshold)));
  }

  /** 问题清单（从最近一次评估的指标行重算，扣分与问题永远同一口径）。 */
  @GetMapping("/issues")
  public InventoryHealthIssuePage issues(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        actor ->
            health.issues(new InventoryHealthQuery(null, null, warehouseId, null), page, size));
  }

  /**
   * 触发一次评估并落库。
   *
   * <p>POST：它「发生一次业务事件」（落一份不可变的结论凭据），不是「把某对象设置成某状态」。
   */
  @PostMapping("/assess")
  public InventoryHealthAssessed assess(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) InventoryHealthQuery request) {
    var query = request == null ? new InventoryHealthQuery(null, null, null, null) : request;
    return call(auth, "inventory.update", actor -> health.assess(query, actor.userId()));
  }
}
