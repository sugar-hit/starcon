package com.starcon.trade;

import static com.starcon.trade.ShipmentWritebackModels.*;

import jakarta.validation.Valid;
import java.util.Locale;
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
 * 9.4.2.1 平台回传入口。
 *
 * <p>命名空间 {@code /trade/fulfillment/shipment-writeback}。<b>刻意不挂在
 * {@code shipment-records} 之下</b>：那是 9.4.1.2 发货记录的命名空间，
 * 而本模块是对那份记录<b>做回传并跟踪结果</b>，两者生命周期不同 ——
 * 挂到它下面会让界面暗示「发货记录详情页里能管回传」，而实际它们是两个独立入口：
 * 一份事实台账、一个回传执行与状态机。
 *
 * <p>同样刻意<b>不叫</b> {@code platform-writeback}：本服务里已有 V119
 * {@code order_platform_writeback_task}（改单/取消回写，只登记不投递），
 * 名字挨太近会让事后追责指错对象。
 *
 * <p>十二个形状，刻意逐个列出、不做通配：
 *
 * <ul>
 *   <li>{@code capabilities} 能力与上下游可用性（含「店铺目录读不到 ⇒ shopId 由调用方给」）</li>
 *   <li>{@code POST /tasks} 从发货记录建回传任务（幂等键由服务端构造）</li>
 *   <li>{@code GET /board} 分组看板（状态 / 平台 / 承运商 / 查证结论 四选一，真 GROUP BY）</li>
 *   <li>{@code GET /tasks} 任务清单（真分页 + 真筛选 + 排序白名单 + 汇总）</li>
 *   <li>{@code GET /tasks/{id}} 任务明细（单头 + 冻结 payload + 尝试凭据 + 查证 + 时间线）</li>
 *   <li>{@code POST /tasks/{id}/submit} 提交投递（入 2 队列）</li>
 *   <li>{@code POST /tasks/{id}/outcome} 登记平台结果三态（成功 / 失败 / 结果未知）</li>
 *   <li>{@code POST /tasks/{id}/verify} 查证结论（未知态<b>必须</b>先走这里）</li>
 *   <li>{@code POST /tasks/{id}/retry} 单条重试（放行判据与批量重试同一段代码）</li>
 *   <li>{@code POST /retry-batch} 批量重试（逐条说清谁被重投、谁被哪道闸门拦下）</li>
 *   <li>{@code POST /tasks/{id}/corrections} 人工修正（新建一版，幂等键随之变化）</li>
 *   <li>{@code GET /events} 事件流（append-only，真分页）</li>
 * </ul>
 *
 * <p>刻意<b>没有</b> PUT / DELETE：凭据行是 append-only 的举证凭据（V147 的 REVOKE
 * 与触发器已经这么钉了），要更正就走<b>修正**（新建一版 + 原任务转 SUPERSEDED），
 * 而不是改掉上一版 —— 改掉之后「这一版当时报的是什么、谁改的、为什么改」就再也答不出了。
 *
 * <p>权限码<b>复用</b> {@code trade.fulfillment.read} / {@code trade.fulfillment.update}，
 * 不新起 {@code trade.writeback.*}：identity 只注册了既有的 fulfillment 页面资源
 * 权限码，用一个没有被注册的码会让连 OWNER 都 403（8.1.2.1 车道踩过这个坑）。
 */
@RestController
@RequestMapping("/trade/fulfillment/shipment-writeback")
public class ShipmentWritebackController {

  private final AccessService access;
  private final ShipmentWritebackService writeback;

  public ShipmentWritebackController(AccessService access, ShipmentWritebackService writeback) {
    this.access = access;
    this.writeback = writeback;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "trade." + permission);
    return access.run(actor, "trade." + permission, () -> work.apply(actor));
  }

  /** 能力声明：逐条如实报出谁真读得到、谁读不到（读不到就说明处置方式）。 */
  @GetMapping("/capabilities")
  public ShipmentWritebackCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "fulfillment.read", a -> writeback.capabilities());
  }

  /** 从发货记录建回传任务（幂等：重复调用复用既有任务，不产生第二次平台动作）。 */
  @PostMapping("/tasks")
  public ShipmentWritebackCreateResult create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ShipmentWritebackCreateInput input) {
    return call(auth, "fulfillment.update", a -> writeback.create(input, a.membershipId()));
  }

  /** 分组看板：状态 / 平台 / 承运商 / 查证结论 四选一，真 GROUP BY。 */
  @GetMapping("/board")
  public ShipmentWritebackBoard board(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false, defaultValue = "STATUS") String groupBy) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            writeback.board(
                status,
                platformCode,
                carrierCode,
                groupBy == null
                    ? null
                    : WritebackGroupBy.valueOf(groupBy.toUpperCase(Locale.ROOT))));
  }

  /** 任务清单（真分页 + 真筛选 + 排序白名单；汇总不受分页影响）。 */
  @GetMapping("/tasks")
  public ShipmentWritebackTaskPage tasks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String verification,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "CREATED_AT_DESC") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "fulfillment.read",
        a ->
            writeback.tasks(
                status, platformCode, carrierCode, verification, keyword, sort, page, size));
  }

  /** 任务明细：单头 + 冻结 payload + 尝试凭据 + 查证记录 + 时间线 + 边界声明。 */
  @GetMapping("/tasks/{id}")
  public ShipmentWritebackTaskDetail task(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "fulfillment.read", a -> writeback.task(id));
  }

  /** 提交投递：入 2 队列（幂等键复用本版，channel 侧命中即复用既有任务）。 */
  @PostMapping("/tasks/{id}/submit")
  public ShipmentWritebackTaskDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody(required = false) ShipmentWritebackSubmitInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> writeback.submit(id, input, a.membershipId(), auth));
  }

  /** 登记平台侧结果三态：平台已受理 / 平台拒绝 / 结果未知。 */
  @PostMapping("/tasks/{id}/outcome")
  public ShipmentWritebackTaskDetail outcome(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentWritebackOutcomeInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> writeback.registerOutcome(id, input, a.membershipId(), auth));
  }

  /** 查证结论 —— 结果未知态<b>必须</b>先走这里才谈得上重发。 */
  @PostMapping("/tasks/{id}/verify")
  public ShipmentWritebackTaskDetail verify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentWritebackVerifyInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> writeback.verify(id, input, a.membershipId(), auth));
  }

  /** 单条重试。未知态 / 终态 / 达上限 / 退避未到点都会被如实拒并说清拦在哪一道。 */
  @PostMapping("/tasks/{id}/retry")
  public ShipmentWritebackTaskDetail retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody(required = false) ShipmentWritebackRetryInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> writeback.retry(id, input, a.membershipId(), auth));
  }

  /** 批量重试：真 SQL 取候选集，逐条说清谁被重投、谁被哪道闸门拦下。 */
  @PostMapping("/retry-batch")
  public ShipmentWritebackBatchRetryResult retryBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ShipmentWritebackBatchRetryInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> writeback.batchRetry(input, a.membershipId(), auth));
  }

  /** 人工修正：改回传内容并重新发起（新建一版，幂等键随之变化，原任务保留作证据）。 */
  @PostMapping("/tasks/{id}/corrections")
  public ShipmentWritebackTaskDetail correct(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ShipmentWritebackCorrectionInput input) {
    return call(
        auth,
        "fulfillment.update",
        a -> writeback.correct(id, input, a.membershipId()));
  }

  /** 事件流（append-only，真分页；刻意没有任何写形状与 DELETE）。 */
  @GetMapping("/events")
  public ShipmentWritebackEventPage events(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String eventKind,
      @RequestParam(required = false) UUID taskId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "fulfillment.read", a -> writeback.events(eventKind, taskId, page, size));
  }
}
