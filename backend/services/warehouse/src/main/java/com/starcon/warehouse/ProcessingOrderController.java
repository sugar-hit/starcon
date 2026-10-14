package com.starcon.warehouse;

import static com.starcon.warehouse.ProcessingOrderModels.*;

import jakarta.validation.Valid;
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
 * 6.4.1.1 加工单的仓库侧消费入口（组件出库 + 成品入库）。
 *
 * <p>命名空间 {@code /warehouse/processing-orders}：卷 06 的前端路由是 {@code
 * /purchase/processing}，但**后端路径按服务命名空间走** （与 8.2.1.2 收货差异走 {@code
 * /warehouse/goods-receipt-discrepancies} 同一纪律）。
 *
 * <p><b>为什么入库动作在 warehouse 而不是 trade</b>：蓝图 6.4.1.1 关系段写死 「加工是『消耗组件产生成品』的库存动作，必须在 warehouse 本地事务完成，
 * 不能由 trade 直接改库存」。trade 拥有加工单据（V97）， 库存动作归本服务 —— 两个服务各有独立数据库，不跨库读写。
 *
 * <p>权限码用 {@code warehouse.processing.read} / {@code warehouse.processing.update}：
 * 这个动作会真的扣掉组件库存并增加成品库存，不是读权限能带过去的。
 */
@RestController
@RequestMapping("/warehouse/processing-orders")
public class ProcessingOrderController {

  private final AccessService access;
  private final ProcessingOrderService processing;

  public ProcessingOrderController(AccessService access, ProcessingOrderService processing) {
    this.access = access;
    this.processing = processing;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：库存动作归属、上游可用性与未交付下游。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「哪些输入真有依据」显式报出来， 而不是让调用方从 404 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public ProcessingStockCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "processing.read", a -> processing.capabilities());
  }

  /** 库存动作归属：单据归 trade，库存动作归 warehouse（蓝图加粗句的界面化说明）。 */
  @GetMapping("/boundary")
  public StockMovementBoundary boundary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "processing.read", a -> processing.boundary());
  }

  /**
   * 执行加工单：组件出库 + 成品入库，**一个本地事务**。
   *
   * <p>任一环节失败（组件不足 / 库位不存在 / 重复投递）整体回滚， 不会留下「组件扣了成品没进」或反之的半截状态。 同一张加工单重复投递靠 {@code
   * UNIQUE(tenant_id,ref_type,ref_id)} 去重（409）。
   */
  @PostMapping("/issues")
  public ProcessingOrderIssueSaved issue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ProcessingOrderIssueInput input) {
    return call(auth, "processing.update", a -> processing.issue(input, a.userId()));
  }

  /**
   * 加工单在仓库的执行记录（真分页 + 全量汇总）。
   *
   * <p>这是「组件出库与成品入库到底记在哪」的查询出口： 一次执行 = 一条 {@code inventory_transaction} + N 条带符号分录。
   */
  @GetMapping("/executions")
  public ProcessingStockPage executions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID processingOrderId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "processing.read", a -> processing.pageExecutions(processingOrderId, page, size));
  }
}
