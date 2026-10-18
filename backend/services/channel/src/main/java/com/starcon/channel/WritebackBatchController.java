package com.starcon.channel;

import static com.starcon.channel.WritebackBatchModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 2.5.2.1 常用回写操作 —— 批量改库存 / 改价 / 下架 / 回传物流。
 *
 * <p>端点按蓝图模块号标注，方便对照 02-channel-and-shop.md。
 *
 * <p>权限沿用 2.5.1.1 的既有权限码，**不新增**（新增要动 identity 的权限真相源，那是别的车道的事）：
 *
 * <ul>
 *   <li>{@code channel.operation.read} —— 读目标池与批次
 *   <li>{@code channel.operation.create} —— 登记目标 / 执行批量（都是「发起一次写」）
 *   <li>{@code channel.operation.update} —— 封禁解封目标
 * </ul>
 */
@RestController
@RequestMapping("/channel")
public class WritebackBatchController {
  private final AccessService access;
  private final WritebackBatchService service;

  public WritebackBatchController(AccessService access, WritebackBatchService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(
      String bearer, String permission, java.util.function.Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 可回写目标 ----

  /** 登记 / 更新可回写目标。上游（04 刊登、07 库存、09 订单、10 物流）推事件后落在这里。 */
  @PostMapping("/writeback-targets")
  public UUID targetUpsert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WritebackTargetInput input) {
    return call(auth, "channel.operation.create", a -> service.upsertTarget(a, input));
  }

  /** 候选池读：真分页 + 真筛选（按店铺、类型、平台键/SKU 关键词、状态）。 */
  @GetMapping("/writeback-targets")
  public WritebackTargetPage targets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String targetType,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.targets(a.tenantId(), shopId, targetType, keyword, status, page, size));
  }

  /** 封禁 / 解封。封禁是批量回写里最常见的「单条失败」真实来源。 */
  @PostMapping("/writeback-targets/{id}/status")
  public void targetStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody WritebackTargetStatusInput input) {
    call(
        auth,
        "channel.operation.update",
        a -> {
          service.setTargetStatus(a, id, input.status(), input.reason());
          return null;
        });
  }

  // ---- 批量回写 ----

  /**
   * 执行一次批量回写：生成 N 条独立任务，逐条回报结果。
   *
   * <p>重复提交同一幂等键返回既有批次，不会产生第二批平台动作。
   */
  @PostMapping("/writeback-batches")
  public WritebackBatchView execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WritebackBatchInput input) {
    return call(auth, "channel.operation.create", a -> service.execute(a, input));
  }

  /** 试算：同样的筛选与校验，不落任何一行 —— 避免对着几百条盲点「执行」。 */
  @PostMapping("/writeback-batches/preview")
  public WritebackBatchPreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody WritebackBatchInput input) {
    return call(auth, "channel.operation.read", a -> service.preview(a, input));
  }

  @GetMapping("/writeback-batches")
  public WritebackBatchPage batches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String outcome,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.batches(a.tenantId(), kind, shopId, outcome, page, size));
  }

  /** 批次详情：逐条结果 + 现查的真实平台任务状态。 */
  @GetMapping("/writeback-batches/{id}")
  public WritebackBatchView batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.read", a -> service.detail(a, id));
  }
}
