package com.starcon.warehouse;

import static com.starcon.warehouse.AllocationResultModels.*;

import jakarta.validation.Valid;
import java.time.OffsetDateTime;
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
 * 7.8.2.1 分摊结果查看。
 *
 * <p>蓝图：「展示某 SKU/仓在各店铺的分摊量、已用、剩余、回写平台的数值，以及回写失败或延迟导致的实际风险敞口。输入：分摊结果；平台回写状态（4.3.2.1）。输出：分摊明细表 + 平台回写一致性核对。」
 *
 * <p>命名空间 {@code /warehouse/inventory/allocation-results}，权限码复用
 * {@code warehouse.inventory.read} / {@code warehouse.inventory.update} —— 与 7.8.1.1 / 7.8.1.2 同一套。
 * 刻意**不**新造权限码：看分摊结果与登记回写状态是同一份事实的两面，
 * 拆成两个权限码只会让人以为存在「能看但不许登记回写状态」这种中间状态。
 *
 * <p><b>跨服务边界写在端点上而不是只写在文档里</b>：平台回写状态（4.3.2.1）在 channel 服务，
 * 本服务**不读它的表** —— 回写状态经事件 / 授权 API 推送或显式录入进入本地投影表
 * {@code allocation_writeback_snapshot}。本地没收到时，响应里的 {@code writebackState} 是
 * {@code UNKNOWN}（尚未回填），**不是 0**。
 *
 * <p><b>整个路径上没有 DELETE 端点，而且这不是遗漏</b>：回写状态的观测是 append-only
 * （V189 的 REVOKE UPDATE/DELETE + 不可变触发器会在权限层与数据库层各挡一次）。
 */
@RestController
@RequestMapping("/warehouse")
public class AllocationResultController {

  private final AccessService access;
  private final AllocationResultService results;

  public AllocationResultController(AccessService access, AllocationResultService results) {
    this.access = access;
    this.results = results;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在一个事务里绑定 RLS 租户上下文执行查询。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  // ------------------------------------------------------------ 能力

  /** 能力清单：跨服务边界、UNKNOWN 与 0 的区别、三个缓冲的区分、敞口算式、仓维度来源。 */
  @GetMapping("/inventory/allocation-results/capabilities")
  public AllocationResultCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> results.capabilities());
  }

  // ------------------------------------------------------------ 分摊明细

  /**
   * 分摊明细表（真分页）。
   *
   * <p>筛选维度覆盖蓝图的「某 SKU/仓在各店铺」：SKU、仓、店铺、分摊轮次，
   * 外加分摊方法、分摊时刻区间与 {@code scope}（只看哪一类问题）。
   */
  @GetMapping("/inventory/allocation-results/results")
  public AllocationResultPage results(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) String method,
      @RequestParam(required = false) String allocatedFrom,
      @RequestParam(required = false) String allocatedTo,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            results.results(
                new AllocationResultQuery(
                    runId,
                    skuId,
                    warehouseId,
                    referenceId,
                    method,
                    moment(allocatedFrom),
                    moment(allocatedTo),
                    scope,
                    page,
                    size)));
  }

  // ------------------------------------------------------------ 分摊轮次

  /** 分摊轮次列表（真分页）。分摊轮次是本模块的一个筛选维度。 */
  @GetMapping("/inventory/allocation-results/runs")
  public AllocationResultRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String method,
      @RequestParam(required = false) String allocatedFrom,
      @RequestParam(required = false) String allocatedTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            results.runs(
                skuId, method, moment(allocatedFrom), moment(allocatedTo), page, size));
  }

  // ------------------------------------------------------------ 一致性核对

  /**
   * 平台回写一致性核对：总量口径 + 逐轮结论。
   *
   * <p>这是蓝图输出里的「平台回写一致性核对」。它与分摊明细表读的是**同一份**判定，
   * 所以两处的结论永远一致 —— 不存在「表里说一致、核对说超卖」这种事。
   */
  @GetMapping("/inventory/allocation-results/reconciliation")
  public AllocationReconciliationReport reconciliation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) String method,
      @RequestParam(required = false) String allocatedFrom,
      @RequestParam(required = false) String allocatedTo,
      @RequestParam(required = false) String scope) {
    return call(
        auth,
        "inventory.read",
        a ->
            results.reconciliation(
                new AllocationResultQuery(
                    runId,
                    skuId,
                    warehouseId,
                    referenceId,
                    method,
                    moment(allocatedFrom),
                    moment(allocatedTo),
                    scope,
                    0,
                    50)));
  }

  // ------------------------------------------------------------ 回写状态观测

  /** 回写状态观测列表（真分页）。append-only 事实的时间线。 */
  @GetMapping("/inventory/allocation-results/writebacks")
  public AllocationWritebackPage writebacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID runId,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String sourceKind,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> results.writebacks(runId, referenceId, warehouseId, state, sourceKind, page, size));
  }

  /**
   * 登记一次平台回写状态的观测。
   *
   * <p>权限码是 {@code warehouse.inventory.update} 而不是 read：登记回写状态会直接改变
   * 「平台挂着的数」这个事实，进而改变风险敞口的结论 —— 把它挂在读权限上，
   * 那个结论就等于没有防线。
   *
   * <p>这是**唯一**的写入端点。刻意不给「改一条观测」或「删一条观测」的入口：
   * 观测是「当时平台挂的是多少」的唯一凭据。
   */
  @PostMapping("/inventory/allocation-results/writebacks")
  public AllocationWritebackSaved record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AllocationWritebackInput input) {
    return call(auth, "inventory.update", a -> results.record(input, a.userId()));
  }

  /** 解析可选的 ISO-8601 时刻；解析不了就报 400 而不是悄悄用当前时刻。 */
  private static OffsetDateTime moment(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return OffsetDateTime.parse(raw);
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.BAD_REQUEST,
          "时刻格式非法："
              + raw
              + "（应为 ISO-8601 带偏移，例如 2026-10-19T10:00:00+08:00）。"
              + "悄悄改成当前时刻会让「这一轮分摊发生在什么时候」这个问题得到一个假的答案。");
    }
  }
}
