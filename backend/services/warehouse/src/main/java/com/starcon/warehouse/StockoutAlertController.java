package com.starcon.warehouse;

import static com.starcon.warehouse.StockoutAlertModels.*;

import jakarta.validation.Valid;
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
 * 7.8.2.2 缺货与饥饿预警。
 *
 * <p>蓝图：「识别因分摊/优先级导致的店铺"饥饿"（长期分不到货、影响绩效），以及真正的全网缺货，区分两者给出不同处置建议。输入：分摊结果；店铺销售与绩效；总可用量。输出：饥饿店铺清单 + 缺货清单 + 建议（调优先级/调保留/补货）。」
 *
 * <p><b>路径命名空间 {@code /warehouse/inventory/stockout-alerts} 之下，两类问题各有各的路径</b>：
 * {@code /starvations} 与 {@code /stockouts}。刻意**不**用
 * 「一个列表端点 + 一个 type 参数」—— 那正是「混在一个缺货告警里」：
 * 两类问题的判定依据、字段、处置全都不一样，一个端点返回它们必然要在
 * 前端再分一次，而分错一次就意味着「把该补货的当成该调保底的」。
 *
 * <p>权限码复用 {@code warehouse.inventory.read} / {@code warehouse.inventory.update}
 * —— 与 7.8.1.1 / 7.8.1.2 / 7.8.2.1 同一套，不新造码。
 *
 * <p><b>整个路径上没有 DELETE 端点，而且这不是遗漏</b>：预警行不可删
 * （V192 的 REVOKE DELETE + 触发器会在权限层与数据库层各挡一次），
 * 停止跟踪走 resolve（处置）。
 */
@RestController
@RequestMapping("/warehouse")
public class StockoutAlertController {

  private final AccessService access;
  private final StockoutAlertService alerts;

  public StockoutAlertController(AccessService access, StockoutAlertService alerts) {
    this.access = access;
    this.alerts = alerts;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在一个事务里绑定 RLS 租户上下文执行查询。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /**
   * 能力清单：两类判定的依据与差别、「不知道」的语义、建议的边界、未交付的上游。
   *
   * <p>未交付的三环（17 绩效 / 6.2.1.2 补货 / 1 工作台）在响应里是
   * {@code available=false} + {@code ownerModule}，不是靠让人从 404 去猜。
   */
  @GetMapping("/inventory/stockout-alerts/capabilities")
  public StockoutAlertCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> alerts.capabilities());
  }

  // ------------------------------------------------------------ 评估

  /**
   * 一次评估：分别判定「缺货」与「饥饿」两类问题，各自落各自的表。
   *
   * <p>权限码是 {@code warehouse.inventory.update}：它会写入预警行并改变
   * 「哪些问题还没人管」这个事实 —— 挂在读权限上，这个事实就没有防线。
   */
  @PostMapping("/inventory/stockout-alerts/evaluate")
  public StockoutEvaluationResult evaluate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) StockoutEvaluationRequest request) {
    return call(auth, "inventory.update", a -> alerts.evaluate(request));
  }

  // ------------------------------------------------------------ 饥饿清单

  /** 店铺饥饿清单（真分页）。筛选维度：SKU / 店铺 / 策略 / 店铺类型 / 严重度 / 处置状态。 */
  @GetMapping("/inventory/stockout-alerts/starvations")
  public StarvationAlertPage starvations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) UUID policyId,
      @RequestParam(required = false) String shopOperatingMode,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String lifecycle,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            alerts.starvations(
                new StarvationAlertQuery(
                    skuId,
                    referenceId,
                    policyId,
                    shopOperatingMode,
                    scope,
                    severity,
                    lifecycle,
                    page,
                    size)));
  }

  /** 一条饥饿预警的详情。 */
  @GetMapping("/inventory/stockout-alerts/starvations/{id}")
  public StarvationAlertRow starvation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> alerts.starvation(id));
  }

  /** 认领一条饥饿预警（有人接了这件事）。 */
  @PostMapping("/inventory/stockout-alerts/starvations/{id}/acknowledge")
  public StockoutAlertActionResult acknowledgeStarvation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.update", a -> alerts.acknowledgeStarvation(id, a.userId()));
  }

  /** 处置一条饥饿预警（按建议做完了）。处置说明必填。 */
  @PostMapping("/inventory/stockout-alerts/starvations/{id}/resolve")
  public StockoutAlertActionResult resolveStarvation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody StockoutAlertResolutionInput input) {
    return call(
        auth, "inventory.update", a -> alerts.resolveStarvation(id, input.note(), a.userId()));
  }

  // ------------------------------------------------------------ 缺货清单

  /** 缺货清单（真分页）。筛选维度：SKU / 仓 / 严重度 / 处置状态。 */
  @GetMapping("/inventory/stockout-alerts/stockouts")
  public StockoutAlertPage stockouts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String lifecycle,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> alerts.stockouts(new StockoutAlertQuery(skuId, warehouseId, scope, severity, lifecycle, page, size)));
  }

  /** 一条缺货预警的详情。 */
  @GetMapping("/inventory/stockout-alerts/stockouts/{id}")
  public StockoutAlertRow stockout(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> alerts.stockout(id));
  }

  /** 认领一条缺货预警。 */
  @PostMapping("/inventory/stockout-alerts/stockouts/{id}/acknowledge")
  public StockoutAlertActionResult acknowledgeStockout(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.update", a -> alerts.acknowledgeStockout(id, a.userId()));
  }

  /** 处置一条缺货预警。处置说明必填。 */
  @PostMapping("/inventory/stockout-alerts/stockouts/{id}/resolve")
  public StockoutAlertActionResult resolveStockout(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody StockoutAlertResolutionInput input) {
    return call(auth, "inventory.update", a -> alerts.resolveStockout(id, input.note(), a.userId()));
  }
}
