package com.starcon.warehouse;

import static com.starcon.warehouse.PickTaskModels.*;

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
 * 8.3.2.1 拣货任务入口。
 *
 * <p>命名空间 {@code /warehouse/pick-tasks}。<b>刻意不挂在 {@code outbound-orders} 之下</b>：
 * 出库单是履约单据（8.3.1.x），拣货任务是现场作业工单，两者的生命周期不一样 ——单据取消后工单还在，单据在「待复核」时工单已结束。挂在一条枝下会让
 * 「拣货任务」在契约上看起来像出库单的一个子资源，而实际上它横跨 8.3.2.1 与 8.3.2.2（波次未交付，但合并拣货同样作用在这张任务上）。
 *
 * <p>十个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游声明
 *   <li>{@code POST /} 生成拣货任务（读 8.3.1.1 的交接请求 + 排拣货路径，<b>不动库存</b>）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总）
 *   <li>{@code GET /{id}} 详情（任务 + 路径计划段 + 实拣 + 异常 + 时间线 + 路径报告）
 *   <li>{@code POST /{id}/assign} 接单（作业人员 / PDA 扫码）
 *   <li>{@code POST /{id}/picks} 登记实拣（实际库位/批次/序列号/数量，<b>搬库存</b>）
 *   <li>{@code POST /{id}/exceptions} 上报异常（缺货 / 破损）
 *   <li>{@code POST /{id}/exceptions/{exceptionId}/resolve} 处置异常
 *   <li>{@code POST /{id}/complete} 完成拣货（出库单 → 待复核，<b>不是已发货</b>）
 *   <li>{@code POST /{id}/reopen} 重开（复核返工）
 *   <li>{@code POST /{id}/cancel} 取消（<b>不动库存</b>）
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：拣货明细与实拣记录是事实（V110 的 append-only 守卫会直接拒），已完成的任务是交给复核的凭据。要更正就「重开 + 补拣」，
 * 要放弃就取消留原因。
 *
 * <p>权限码<b>复用</b> {@code warehouse.inventory.read} / {@code warehouse.inventory.update}， 不新起 {@code
 * warehouse.picking.*}：identity 只注册了 {@code warehouse.inventory} 与 {@code warehouse.facilities}
 * 两个页面资源的权限码（V8 / V69 / V77），用一个 <b>没有被注册</b>的码会让连 OWNER 都 403 —— 8.1.2.1 车道就踩过这个坑并在台账里
 * 记了原因。拣货属「出入库作业」这同一族，与 8.2.1.1 收货单、8.3.1.1 出库单、 8.3.4.1 首程发货四个兄弟模块用同一个前缀。
 *
 * <p>写权限单列：登记实拣会真的把货从一个库位搬到打包区、预占跟着走， 不是读权限能带过去的。
 */
@RestController
@RequestMapping("/warehouse/pick-tasks")
public class PickTaskController {

  private final AccessService access;
  private final PickTaskService pickTasks;

  public PickTaskController(AccessService access, PickTaskService pickTasks) {
    this.access = access;
    this.pickTasks = pickTasks;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：路径优化口径、库存动作边界（<b>balance 不变、预占不释放</b>）、 与 8.5.2.1 那两条建议的对应关系，以及上游下游的可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「哪些输入真有依据」「哪条不能填默认值」 显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public PickTaskCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "inventory.read", a -> pickTasks.capabilities());
  }

  /**
   * 生成拣货任务：读 8.3.1.1 的交接请求 + 库存分配结果，按库位层级路径排出拣货顺序。
   *
   * <p><b>不动库存</b>：货还在原库位、预占不变。实拣在 {@code /picks} 里才发生。 没有交接请求就没有任务 —— 凭空生成一张拣货任务在账上说不通。
   */
  @PostMapping
  public PickTaskCreated generate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PickTaskGenerateInput input) {
    return call(auth, "inventory.update", a -> pickTasks.generate(input, a.userId()));
  }

  /** 拣货任务列表（真分页 + 真筛选 + 排序白名单 + 汇总，汇总不受筛选影响）。 */
  @GetMapping
  public PickTaskPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID assigneeUserId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_AT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a -> pickTasks.page(warehouseId, status, assigneeUserId, keyword, sort, page, size));
  }

  /** 详情：路径计划段 + 实拣（实际库位/批次/序列号）+ 异常 + 时间线 + 路径报告。 */
  @GetMapping("/{id}")
  public PickTaskDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> pickTasks.detail(id));
  }

  /** 接单 / 分配作业人员（PDA 扫码接单同一条动作）。 */
  @PostMapping("/{id}/assign")
  public PickTaskRow assign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PickTaskAssignInput input) {
    return call(auth, "inventory.update", a -> pickTasks.assign(id, input, a.userId()));
  }

  /**
   * 登记实拣：把货从<b>实际</b>取货库位搬到打包区（on_hand 与 reserved 一起走， balance 层净变化为 0，所以不写 inventory_entry）。
   *
   * <p>实际库位允许与计划不同：分配是建议，现场是事实。真正被拒的是「那个库位上 根本没有这件货」与「拣超过本段计划」。
   */
  @PostMapping("/{id}/picks")
  public PickTaskPickRow recordPick(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PickTaskPickInput input) {
    return call(auth, "inventory.update", a -> pickTasks.recordPick(id, input, a.userId()));
  }

  /** 上报异常（缺货 / 破损 / 序列号缺失 / 错位 / 其它）。缺货必填缺几件。 */
  @PostMapping("/{id}/exceptions")
  public PickTaskExceptionRow reportException(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PickTaskExceptionInput input) {
    return call(auth, "inventory.update", a -> pickTasks.reportException(id, input, a.userId()));
  }

  /** 处置异常。<b>只能写一次</b>：异常处置是审计对象。 */
  @PostMapping("/{id}/exceptions/{exceptionId}/resolve")
  public PickTaskExceptionRow resolveException(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @PathVariable UUID exceptionId,
      @Valid @RequestBody PickTaskResolveInput input) {
    return call(
        auth,
        "inventory.update",
        a -> pickTasks.resolveException(id, exceptionId, input, a.userId()));
  }

  /**
   * 完成拣货：出库单只推到「待复核」（8.3.3.1）。
   *
   * <p>没有「强制完成」：一个 9/10 的任务强行提交，复核看到的仍然是缺件， 而缺件的原因（本该缺货、还是有人漏拣）在那一刻已经分不清。
   */
  @PostMapping("/{id}/complete")
  public PickTaskDetail complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.update", a -> pickTasks.complete(id, a.userId()));
  }

  /** 重开（复核返工）。实拣量在原有基础上累加，不覆盖。 */
  @PostMapping("/{id}/reopen")
  public PickTaskRow reopen(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PickTaskReopenInput input) {
    return call(auth, "inventory.update", a -> pickTasks.reopen(id, input, a.userId()));
  }

  /** 取消拣货任务。<b>不动库存</b>：货仍在原库位、预占不变。 */
  @PostMapping("/{id}/cancel")
  public PickTaskRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PickTaskCancelInput input) {
    return call(auth, "inventory.update", a -> pickTasks.cancel(id, input, a.userId()));
  }
}
