package com.starcon.warehouse;

import static com.starcon.warehouse.AssemblyOrderModels.*;

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
 * 8.4.1.1 组装单入口（实物套装组装）。
 *
 * <p>命名空间 {@code /warehouse/assembly-orders}：卷 08 的组装单是仓库现场作业，
 * 归 warehouse 服务（与 6.4.1.1 加工单归 trade 同一条纪律 —— 后端路径按服务命名空间走）。
 *
 * <p>六个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游声明（含虚拟套装边界）
 *   <li>{@code POST /} 建组装单（草稿，**不动库存**）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总）
 *   <li>{@code GET /{id}} 详情（单据 + 组件快照明细 + 账本分录）
 *   <li>{@code POST /{id}/issue} 执行组装（组件出库 + 成品入库，**一个本地事务**）
 *   <li>{@code POST /{id}/cancel} 取消草稿（**不动库存**）
 * </ul>
 *
 * <p>刻意<b>没有</b>任何 PUT / DELETE：组装单的组件明细是「建单时按 BOM 冻结的快照」，
 * 改它等于改账的输入（那张 BOM 可能已经换了版本）；要改就作废重开一张。
 * 「把已完成的组装单删掉」更是不可能 —— 组件已经出库、成品已经入库，V103 的终态守卫会直接拒绝。
 *
 * <p>权限码用 {@code warehouse.assembly.read} / {@code warehouse.assembly.update}：
 * 执行组装会真的扣掉组件库存并增加成品库存，不是读权限能带过去的。
 */
@RestController
@RequestMapping("/warehouse/assembly-orders")
public class AssemblyOrderController {

  private final AccessService access;
  private final AssemblyOrderService assembly;

  public AssemblyOrderController(AccessService access, AssemblyOrderService assembly) {
    this.access = access;
    this.assembly = assembly;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：跨服务边界、虚拟套装边界与上下游可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「哪些输入真有依据」显式报出来，
   * 而不是让调用方从 404 或空数组里猜 —— 尤其是「虚拟套装为什么走不到这里」。
   */
  @GetMapping("/capabilities")
  public AssemblyCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "assembly.read", a -> assembly.capabilities());
  }

  /**
   * 建组装单（草稿，**不动库存**）。
   *
   * <p>BOM 组件明细由调用方按 catalog 规则 R7 的结果冻结传入 —— 本服务不跨库回查 catalog，
   * 也不重算 BOM。成品 SKU 必须是 catalog 真实 SKU，虚拟套装填不出来。
   */
  @PostMapping
  public AssemblyOrderCreated create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody AssemblyOrderCreateInput input) {
    return call(auth, "assembly.update", a -> assembly.create(input, a.userId()));
  }

  /** 组装单列表（真分页 + 真筛选 + 排序白名单 + 汇总）。 */
  @GetMapping
  public AssemblyOrderPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID productSkuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String sourceSystem,
      @RequestParam(required = false) UUID referenceId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_AT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "assembly.read",
        a ->
            assembly.page(
                status, productSkuId, warehouseId, sourceSystem, referenceId, keyword, sort, page,
                size));
  }

  /** 组装单详情：单据 + 组件快照明细（BOM 冻结副本）+ 账本分录。 */
  @GetMapping("/{id}")
  public AssemblyOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "assembly.read", a -> assembly.detail(id));
  }

  /**
   * 执行组装：组件出库 + 成品入库，**一个本地事务**。
   *
   * <p>任一环节失败（组件不足 / 库位不存在 / 已执行过）整体回滚，不会留下
   * 「组件扣了成品没进」或反之的半截状态。同一张组装单重复执行是 409。
   */
  @PostMapping("/{id}/issue")
  public AssemblyOrderIssued issue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) AssemblyOrderIssueInput input) {
    var payload = input == null ? new AssemblyOrderIssueInput(null) : input;
    return call(auth, "assembly.update", a -> assembly.issue(id, payload, a.userId()));
  }

  /** 取消草稿组装单（不动库存：草稿本来就没发过料）。 */
  @PostMapping("/{id}/cancel")
  public AssemblyOrderRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody AssemblyOrderCancelInput input) {
    return call(auth, "assembly.update", a -> assembly.cancel(id, input, a.userId()));
  }
}