package com.starcon.warehouse;

import static com.starcon.warehouse.PutawayTaskModels.*;

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
 * 8.2.2.1 上架任务入口（收货暂存位 → 正式库位）。
 *
 * <p>命名空间 {@code /warehouse/putaway-tasks}。<b>刻意不是 {@code goods-receipts} 的子路径</b>：
 * 上架任务是一张**独立的作业单**，它有派工、有 PDA 扫码、有执行中状态 ——
 * 挂在收货单底下会让「这张收货单还没上完架」与「这张收货单还没收完货」在契约上分不清，
 * 而现场看板正是按「还有多少货等着上架」排班的，不是按「有多少单子没签收」。
 *
 * <p>九个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游声明（含「只改位置不改数量」与硬规则）
 *   <li>{@code POST /} 建上架任务（PENDING，<b>不动库存</b>；<b>入参没有数量</b>）
 *   <li>{@code GET /} 列表（真分页 / 真筛选 / 排序白名单 / 汇总 / <b>目标库位分布</b>）
 *   <li>{@code GET /{id}} 详情（单头 + 明细源位/目标位/两量 + 扫码台账 + <b>成对分录与净额</b>）
 *   <li>{@code POST /{id}/assign} 派工（不动库存，可改派）
 *   <li>{@code POST /{id}/start} 开始作业（不动库存，只锁死路由字段）
 *   <li>{@code POST /{id}/scan} <b>PDA 扫码（唯一动库存的动作）</b>：源位扣、目标位加、一个本地事务
 *   <li>{@code POST /{id}/complete} 完成（数据库回读账本核对 P1/P2/P3）
 *   <li>{@code POST /{id}/cancel} 取消（<b>不动库存</b>，且仅在尚未扫码时允许）
 * </ul>
 *
 * <p>刻意<b>没有</b>任何 PUT / DELETE：
 * 建单之后要改目标库位，用「取消重开」而不是原地改 —— 因为一旦扫码，货已经在目标库位上了，
 * 改单据不改事实就是账实不符（V109 的明细守卫从数据库层挡住）。而「把已完成的上架任务删掉」
 * 更是不可能：终态守卫会直接拒绝。
 *
 * <p>权限码用 {@code warehouse.putaway.read} / {@code warehouse.putaway.update}：
 * 扫码会真的把货从一个库位搬到另一个库位，不是读权限能带过去的。
 */
@RestController
@RequestMapping("/warehouse/putaway-tasks")
public class PutawayTaskController {

  private final AccessService access;
  private final PutawayTaskService putaway;

  public PutawayTaskController(AccessService access, PutawayTaskService putaway) {
    this.access = access;
    this.putaway = putaway;
  }

  /**
   * 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：跨服务边界、「只改位置不改数量」这条硬约束与上下游可用性。
   *
   * <p>存在的理由与既有 warehouse 模块一致：把「数量能不能改」「质量状态决定库位类型」
   * 「8.3.2.1 拣货能不能直接消费」显式报出来，而不是让调用方从 400 或空数组里猜。
   */
  @GetMapping("/capabilities")
  public PutawayCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "putaway.read", a -> putaway.capabilities());
  }

  /**
   * 建上架任务（待执行，<b>不动库存</b>）。
   *
   * <p><b>入参刻意没有数量字段</b>：蓝图说「上架只改位置不改数量，数量在收货时已确定」。
   * 要上架哪一行、要搬到哪，由调用方指明；搬多少取自那条收货明细行的实收量，
   * 并在建单时冻结 —— 收货方事后改单不会让在场的作业单凭空变形。
   */
  @PostMapping
  public PutawayTaskCreated create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PutawayTaskCreateInput input) {
    return call(auth, "putaway.update", a -> putaway.create(input, a.userId()));
  }

  /** 上架任务列表（真分页 + 真筛选 + 排序白名单 + 汇总 + 目标库位分布）。 */
  @GetMapping
  public PutawayTaskPage page(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID receiptId,
      @RequestParam(required = false) UUID assigneeId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_AT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "putaway.read",
        a ->
            putaway.page(
                status, warehouseId, receiptId, assigneeId, skuId, keyword, sort, page, size));
  }

  /**
   * 任务详情：单头 + 明细（源位/目标位/两量）+ 扫码台账 + 成对分录。
   *
   * <p>{@code netOnHandDelta} 直接返回当前净额：完成的单据必须是 0，
   * 未完成的允许非 0（货正在路上，那是进行中的真实中间态）。
   */
  @GetMapping("/{id}")
  public PutawayTaskDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "putaway.read", a -> putaway.detail(id));
  }

  /**
   * 派工：把任务指给一位作业人员（不动库存，可改派）。
   *
   * <p>现场换人顶班是常态，所以派工允许覆盖；已完成或已取消的单子拒绝 ——
   * 那会让「这批货是谁搬的」有第二个答案。
   */
  @PostMapping("/{id}/assign")
  public PutawayTaskRow assign(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PutawayTaskAssignInput input) {
    return call(auth, "putaway.update", a -> putaway.assign(id, input, a.userId()));
  }

  /**
   * 开始作业：待执行 → 执行中（<b>不动库存</b>）。
   *
   * <p>「开始」本身不搬货，它的作用是把路由字段锁死：一旦执行中，目标库位 / 源位 /
   * 应上架量就不能再改 —— 部分扫码已经把货搬到目标库位了，此时改路由会让已入账的
   * 分录与单据上的「搬到哪」对不上。V109 的明细守卫从数据库层执行这条。
   */
  @PostMapping("/{id}/start")
  public PutawayTaskRow start(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) PutawayTaskStartInput input) {
    var payload = input == null ? new PutawayTaskStartInput(null) : input;
    return call(auth, "putaway.update", a -> putaway.start(id, payload, a.userId()));
  }

  /**
   * PDA 扫码上架 —— <b>本模块唯一动库存的动作</b>。
   *
   * <p>一次扫码在**同一条余额行**上写一对反向分录（源位 -Q、目标位 +Q），
   * 源位扣、目标位加、成对分录、扫码台账、明细实量、单头合计，<b>一个本地事务</b>：
   * 任一环节失败整体回滚，不会留下「货已从源位拿走但没进目标位」的半截账。
   *
   * <p>{@code quantity} 是<b>本次</b>搬的量而不是「最终要搬的量」：PDA 作业一张单可能
   * 扫三次，每次搬一部分，累计到应上架量才允许完成任务。
   * {@code scanReference} 是幂等锚点 —— PDA 网络抖动重发是现场常态，
   * 没有这道，同一批货会被搬两遍<b>而「净额仍为 0」抓不到</b>。
   */
  @PostMapping("/{id}/scan")
  public PutawayTaskScanned scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PutawayTaskScanInput input) {
    return call(auth, "putaway.update", a -> putaway.scan(id, input, a.userId()));
  }

  /**
   * 完成上架：执行中 → 已完成。
   *
   * <p>本端点<b>自己几乎不校验</b>：三条不变量（P1 净额 = 0 / P2 实量 = 计划量 /
   * P3 出库分录之和 = 实量）由 V109 的守卫触发器在这条 UPDATE 上回读账本裁决。
   * 「半张单搬到一半就说完成」一定会被拒（23514），而不是靠应用层记得判断。
   */
  @PostMapping("/{id}/complete")
  public PutawayTaskRow complete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) PutawayTaskCompleteInput input) {
    var payload = input == null ? new PutawayTaskCompleteInput(null) : input;
    return call(auth, "putaway.update", a -> putaway.complete(id, payload, a.userId()));
  }

  /**
   * 取消任务（<b>不动库存</b>：货还在收货暂存位，从没搬走）。
   *
   * <p>只对尚未扫码的任务开放：一旦扫过，货已经在往目标库位搬了，
   * 取消等于宣布「这批货还在收货暂存位」，而库里它已经不在那儿了。
   */
  @PostMapping("/{id}/cancel")
  public PutawayTaskRow cancel(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PutawayTaskCancelInput input) {
    return call(auth, "putaway.update", a -> putaway.cancel(id, input, a.userId()));
  }
}