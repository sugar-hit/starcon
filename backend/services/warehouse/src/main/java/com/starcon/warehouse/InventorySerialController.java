package com.starcon.warehouse;

import static com.starcon.warehouse.InventorySerialModels.*;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 7.3.2.1 序列号。
 *
 * <p>独立于 {@link InventoryController} 而不是往里加方法：那个类已经是总览 + 明细 + 台账 + 核对四条线，再塞序列号会让「谁改了什么」在 diff
 * 里看不出来。命名空间仍是 {@code /warehouse}， 与 catalog 的 {@code /catalog}、channel 的 {@code /channel} 同一套约定。
 *
 * <p>历史与操作头走 {@code /inventory/serial-movements} 与 {@code /inventory/serial-operations} 而不是 {@code
 * /inventory/serials/movements}：后者会与 {@code /inventory/serials/{id}} 撞形状， 读代码的人得先想一遍 Spring
 * 的路径优先级才知道哪条规则生效。换个前缀就没有这个歧义。
 *
 * <p>权限码：读用 {@code warehouse.inventory.read}，一切写入用 {@code warehouse.inventory.update}。
 * 序列号档案**不另开一个权限码** —— 它就是库存的一种更细的粒度， 多设一组只会让「能看库存却看不到序列号」变成一个没人记得开的开关。 校验端点用 read 而不是
 * update：它只算不写，把「什么都不改」挂在写权限上会逼着收货员申请写权限。
 */
@RestController
@RequestMapping("/warehouse")
public class InventorySerialController {

  private final AccessService access;
  private final InventorySerialService serials;

  public InventorySerialController(AccessService access, InventorySerialService serials) {
    this.access = access;
    this.serials = serials;
  }

  /**
   * 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头）， 再在一个事务里绑定 RLS 租户上下文执行查询。
   *
   * <p>与 {@link InventoryController} 同一套：同一个请求里的读与写必须落在同一个租户上下文里， 两处各写一份迟早会有一份漏掉绑定。
   */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "warehouse." + permission);
    return access.run(actor, "warehouse." + permission, () -> work.apply(actor));
  }

  /**
   * 序列号档案列表（真分页）。
   *
   * <p>{@code warehouseId} 筛的是**当前所在仓库**：已售 / 在途 / 报废的号没有库位， 也就属于任何仓库，传了仓库筛选它们就不出现。这是有意的 ——
   * 「上海仓现在有哪些号」不该列出一堆已经卖掉的号。
   */
  @GetMapping("/inventory/serials")
  public SerialPage serials(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID lotId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            serials.page(
                new SerialQuery(
                    skuId, warehouseId, locationId, lotId, status, keyword, sort, page, size)));
  }

  /**
   * 状态分布汇总。
   *
   * <p>{@code sumMatches} 是「五档之和 == 总数」的显式校验，界面必须把它画出来： 不相等说明聚合口径漂了，那种情况下每个单独的数字都不可信。
   */
  @GetMapping("/inventory/serials/stats")
  public SerialStats stats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID warehouseId,
      @RequestParam(required = false) UUID locationId,
      @RequestParam(required = false) UUID lotId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword) {
    return call(
        auth,
        "inventory.read",
        a ->
            serials.stats(
                new SerialQuery(
                    skuId, warehouseId, locationId, lotId, status, keyword, null, 0, 50)));
  }

  /** 单个序列号档案。跨租户在 RLS 下查不到 → 404，不泄露「这个号存在」。 */
  @GetMapping("/inventory/serials/{id}")
  public SerialProfile serial(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "inventory.read", a -> serials.profile(id));
  }

  /**
   * 扫码 / 单条录入。
   *
   * <p>用 POST 而不是 GET：它建档并写下第一条流转历史，是写操作。 录入即入库（号扫到哪儿就在哪儿），所以它天然满足蓝图那条「数量为 1 时恰好 1 个序列号」。
   */
  @PostMapping("/inventory/serials")
  public SerialValidationResult register(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SerialRegisterRequest request) {
    return call(auth, "inventory.update", a -> serials.register(request, a.userId()));
  }

  /**
   * 批量导入（一个 SKU 的若干台设备）。
   *
   * <p>{@code dryRun=true} 只校验不落库：收货现场先看一遍「哪几个号有问题」再决定是否提交， 比先落库再逐条撤销现实得多。
   */
  @PostMapping("/inventory/serials/import")
  public SerialValidationResult importBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SerialImportRequest request) {
    return call(auth, "inventory.update", a -> serials.importBatch(request, a.userId()));
  }

  /** 编辑档案：只能改批次绑定与备注。SKU、状态、位置都不在这里改。 */
  @PutMapping("/inventory/serials/{id}")
  public SerialProfile update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody SerialUpdateRequest request) {
    return call(auth, "inventory.update", a -> serials.update(id, request));
  }

  /**
   * 删除档案 —— 只删「录错了」。
   *
   * <p>已被任何单据引用过的号删不掉（V58 触发器 + 服务双保险），那种情况只能走报废。
   */
  @DeleteMapping("/inventory/serials/{id}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(
        auth,
        "inventory.update",
        a -> {
          serials.delete(id);
          return null;
        });
  }

  /**
   * 出入库强校验（只算不写）。
   *
   * <p>用 POST：请求体是一组序列号（最坏 500 个字符串），放 URL 里会撞长度限制， 也会把整份清单塞进访问日志。
   *
   * <p>请求体形状与 {@link #move} 完全一致 —— 「先验后提」用的是同一套规则， 少一套 DTO 就少一处校验规则与提交规则悄悄分叉的可能。
   */
  @PostMapping("/inventory/serials/validate-movement")
  public SerialValidationResult validate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SerialValidateRequest request) {
    return call(auth, "inventory.read", a -> serials.validate(request));
  }

  /**
   * 出入库 / 库位转移 / 状态流转 / 批次改绑。
   *
   * <p>响应里 {@code issues} 逐条列出校验错误而不是只回一个失败标志： 只回一句「校验不通过」等于把定位的活儿全推给操作员，而他手里正拿着上百个号。
   * 校验不过时**一条都不写**。
   */
  @PostMapping("/inventory/serials/movements")
  public SerialValidationResult move(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody SerialMoveRequest request) {
    return call(auth, "inventory.update", a -> serials.move(request, a.userId()));
  }

  /** 位置流转历史分页（真分页）。可按序列号 / 操作 / SKU / 来源单据筛。 */
  @GetMapping("/inventory/serial-movements")
  public SerialMovementPage movements(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID serialId,
      @RequestParam(required = false) UUID operationId,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String refType,
      @RequestParam(required = false) UUID refId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            serials.movements(
                new SerialMovementQuery(
                    serialId, operationId, skuId, kind, refType, refId, keyword, page, size)));
  }

  /** 某个序列号的时间线（倒序）。档案页右侧的历史面板走它。 */
  @GetMapping("/inventory/serials/{id}/movements")
  public SerialMovementPage timeline(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "inventory.read", a -> serials.timeline(id, page, size));
  }

  /** 出入库操作头分页（真分页）：每一次「声明几件、实际动了几个号」的原始凭据。 */
  @GetMapping("/inventory/serial-operations")
  public SerialOperationPage operations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String refType,
      @RequestParam(required = false) UUID refId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "inventory.read",
        a ->
            serials.operations(
                new SerialOperationQuery(direction, skuId, refType, refId, page, size)));
  }

  /** 词表：状态、流转种类、操作方向。界面上这些下拉不该硬编码在前端。 */
  @GetMapping("/inventory/serial-vocabularies")
  public SerialVocabularies vocabularies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "inventory.read",
        a ->
            new SerialVocabularies(
                List.of(SerialStatus.values()),
                List.of(SerialMovementKind.values()),
                List.of(SerialMovementDirection.values()),
                List.of(SerialIssueCode.values())));
  }
}
