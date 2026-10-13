package com.starcon.warehouse;

import static com.starcon.warehouse.DisassemblyOrderModels.*;

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
 * 8.4.2.1 拆解单入口（成品拆解回组件）。
 *
 * <p>命名空间 {@code /warehouse/disassembly-orders}。<b>刻意不是 {@code assembly-orders} 的子路径</b>：
 * 「组装」与「拆解」是两个可分别统计的模块（这个月组装多少件、拆解多少件），挂在同一支下
 * 会让它们在契约与路由上混成一团，也让「月结时哪些单据动过库存」这个问题有两个答案。
 *
 * <p>六个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游声明（含<b>去向必填</b>这条硬约束）
 *   <li>{@code POST /} 建拆解单（草稿，<b>不动库存</b>）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总 / <b>去向分布</b>）
 *   <li>{@code GET /{id}} 详情（单据 + 组件反向快照明细 + 账本分录）
 *   <li>{@code POST /{id}/execute} 执行拆解（成品出库 + 组件入库，<b>一个本地事务</b>）
 *   <li>{@code POST /{id}/cancel} 取消草稿（<b>不动库存</b>）
 * </ul>
 *
 * <p>刻意<b>没有</b>任何 PUT / DELETE，与 8.4.1.1 同一纪律：组件明细是「建单时按 BOM 反推冻结的
 * 快照 + 逐行确定的去向」，改它等于改账的输入；要改就作废重开一张。「把已完成的拆解单删掉」
 * 更是不可能 —— 成品已经出库、组件已经入库，V108 的终态守卫会直接拒绝。
 *
 * <p>权限码用 {@code warehouse.disassembly.read} / {@code warehouse.disassembly.update}：
 * 执行拆解会真的扣掉成品库存并增加组件库存，不是读权限能带过去的。
 */
@RestController
@RequestMapping("/warehouse/disassembly-orders")
public class DisassemblyOrderController {

  private final AccessService access;
  private final DisassemblyOrderService disassembly;

  public DisassemblyOrderController(AccessService access,
      DisassemblyOrderService disassembly) {
    this.access = access;
    this.disassembly = disassembly;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：跨服务边界、<b>去向必填没有默认值</b>这条约束与上下游可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「哪些输入真有依据」「哪条不能填默认值」
   * 显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public DisassemblyCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "disassembly.read", a -> disassembly.capabilities());
  }

  /**
   * 建拆解单（草稿，<b>不动库存</b>）。
   *
   * <p>组件应回量由调用方按「单位用量 × 拆解数量 × 回收率/100」冻结传入 —— 本服务不跨库回查
   * catalog，也不重算 BOM。每一行都必须显式给去向（可用/待检/残次），<b>没有默认值</b>。
   */
  @PostMapping
  public DisassemblyOrderCreated create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DisassemblyOrderCreateInput input) {
    return call(auth, "disassembly.update", a -> disassembly.create(input, a.userId()));
  }

  /** 拆解单列表（真分页 + 真筛选 + 排序白名单 + 汇总 + 去向分布）。 */
  @GetMapping
  public DisassemblyOrderPage page(
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
        "disassembly.read",
        a ->
            disassembly.page(
                status, productSkuId, warehouseId, sourceSystem, referenceId, keyword, sort, page,
                size));
  }

  /** 拆解单详情：单据 + 组件反向快照明细（BOM 反推快照 + 逐行去向）+ 账本分录。 */
  @GetMapping("/{id}")
  public DisassemblyOrderDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "disassembly.read", a -> disassembly.detail(id));
  }

  /**
   * 执行拆解：成品出库 + 组件入库，<b>一个本地事务</b>。
   *
   * <p>任一环节失败（成品不足 / 库位不存在 / 已执行过）整体回滚，不会留下
   * 「成品扣了组件没进」或反之的半截状态。同一张拆解单重复执行是 409。
   *
   * <p>每个组件按<b>建单时确定</b>的去向入库 —— 去向不在执行这一步改，也不在事后改
   * （V108 的明细守卫会拒）。
   */
  @PostMapping("/{id}/execute")
  public DisassemblyOrderExecuted execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) DisassemblyOrderExecuteInput input) {
    var payload = input == null ? new DisassemblyOrderExecuteInput(null) : input;
    return call(auth, "disassembly.update", a -> disassembly.execute(id, payload, a.userId()));
  }

  /** 取消草稿拆解单（不动库存：草稿本来就没拆过东西）。 */
  @PostMapping("/{id}/cancel")
  public DisassemblyOrderRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DisassemblyOrderCancelInput input) {
    return call(auth, "disassembly.update", a -> disassembly.cancel(id, input, a.userId()));
  }
}