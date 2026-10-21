package com.starcon.catalog;

import static com.starcon.catalog.PlatformPriceSyncModels.*;

import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceReceiptRequest;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleInput;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceSyncRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 5.4.2.1 平台价格同步。
 *
 * <p>命名空间 {@code /catalog/price-sync}：页面路由 {@code /pricing/sync}。
 * <b>不</b>用 {@code /catalog/price-adjustments/**} —— 那是 5.4.1.1 调价单与
 * 5.4.1.2 批量调价的命名空间（两个模块回答的是两个不同的问题：
 * 一个是「这次改价批不批」，一个是「这个价推上平台会怎样」）。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 */
@RestController
@RequestMapping("/catalog/price-sync")
public class PlatformPriceSyncController {

  private final AccessService access;
  private final PlatformPriceSyncService sync;

  public PlatformPriceSyncController(AccessService access, PlatformPriceSyncService sync) {
    this.access = access;
    this.sync = sync;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：规则类型与语义、回写结局、差异来源、上下游与队列归属。
   *
   * <p>页面顶部固定显示它 —— 蓝图那句「**平台可能拒绝或调整价格**」意味着运营会拿这页的
   * 口径去决定「这个价推上去会怎样」，所以口径必须摆在数字旁边。
   */
  @GetMapping("/capabilities")
  public PlatformPriceSyncCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> sync.capabilities());
  }

  // ================================================================ 平台定价规则

  /** 平台定价规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public PlatformPriceRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String ruleType,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            sync.listRules(
                ruleType, platformCode, siteCode, status, skuId, page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /** 建规则。 */
  @PostMapping("/rules")
  public PlatformPriceRuleView createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PlatformPriceRuleInput input) {
    return call(auth, "spu.update", a -> sync.createRule(input));
  }

  /** 规则详情。 */
  @GetMapping("/rules/{id}")
  public PlatformPriceRuleView rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> sync.getRule(id));
  }

  /** 改规则（含停用：停用后保留历史口径，不再参与判定）。 */
  @PatchMapping("/rules/{id}")
  public PlatformPriceRuleView updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PlatformPriceRuleInput input) {
    return call(auth, "spu.update", a -> sync.updateRule(id, input));
  }

  // ================================================================ 回写结果与差异

  /** 回写结果列表（真分页 + 真筛选）。 */
  @GetMapping("/records")
  public PlatformPriceSyncRecordPage records(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            sync.listRecords(
                outcome, platformCode, siteCode, skuId, page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /** 回写结果详情（含全部差异记录）。 */
  @GetMapping("/records/{id}")
  public PlatformPriceSyncRecordDetail record(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> sync.getRecord(id));
  }

  /** 差异台账（真分页 + 真筛选）：实际价与期望价的差异逐条可查。 */
  @GetMapping("/diffs")
  public PlatformPriceDiffPage diffs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String outcome,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) UUID syncId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            sync.listDiffs(
                outcome, platformCode, siteCode, skuId, syncId, page == null ? 0 : page,
                size == null ? 20 : size));
  }

  // ================================================================ 同步与回执

  /**
   * 消费上游的待回写事实并投递到平台。
   *
   * <p>🔴 <b>每条事实一个独立事务</b>：一条失败不许拖垮其余。
   * 所以本端点**不**走 {@link #call} 的外层事务，而是只解析身份，
   * 让服务层逐条开事务。
   */
  @PostMapping("/sync")
  public PlatformPriceSyncRunView sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody(required = false) PlatformPriceSyncRequest request) {
    var actor = access.require(auth, "catalog.spu.update");
    return sync.sync(actor, request, auth);
  }

  /**
   * 平台回执（由平台适配器 / 核验通道调用）。
   *
   * <p>🔴 收不到回执时记录停在 PENDING —— <b>不假设回写即成功</b>。
   */
  @PostMapping("/records/{id}/receipt")
  public PlatformPriceSyncRecordDetail receipt(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PlatformPriceReceiptRequest request) {
    return call(auth, "spu.update", a -> sync.receipt(id, request, auth));
  }
}
