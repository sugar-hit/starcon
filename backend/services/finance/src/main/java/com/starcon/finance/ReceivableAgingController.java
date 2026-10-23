package com.starcon.finance;

import static com.starcon.finance.ReceivableAgingModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 12.1.1.2 应收账龄的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/receivable-aging}</b>：与 12.1.1.1 的 {@code /finance/receivables}（应收单管理）**刻意不共用一个前缀** ——
 * 那是另一个模块的命名空间（它的端点、它的契约、它的页面都归它所有），本模块另起一个前缀，
 * 「哪个模块的哪个端点」才说得清。等 12.1.1.3 坏账核销、12.2.x 收付款落地时，它们各自也有自己的前缀。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限， 再在一个事务里绑定 {@code app.current_tenant}，
 * 由 RLS 在数据库层强制隔离。 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码沿用 12.1.1.1 的 {@code finance.receivable.read / .update}：账龄是应收单的**投影**，
 * 能看应收单的人就能看账龄；账龄区间是**配置**，走写权限。
 */
@RestController
@RequestMapping("/finance/receivable-aging")
public class ReceivableAgingController {

  private final AccessService access;
  private final ReceivableAgingService aging;

  public ReceivableAgingController(AccessService access, ReceivableAgingService aging) {
    this.access = access;
    this.aging = aging;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /** 能力声明：哪些上游真接通、哪些还没交付、归谁。 */
  @GetMapping("/capabilities")
  public FinanceReceivableAgingCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> aging.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（全部来自真实数据 + 当前生效的账龄区间）。 */
  @GetMapping("/options")
  public FinanceReceivableAgingOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> aging.options());
  }

  // ================================================================ 账龄分布

  /**
   * 账龄分布报表：按店铺 / 客户 / 币种三个维度下钻，争议中金额单列，按币种分开汇总。
   *
   * <p>⚠️ 汇总覆盖**整个筛选结果集**，不是当前页。
   */
  @GetMapping("/distribution")
  public FinanceReceivableAgingDistribution distribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) String bucketCode,
      @RequestParam(required = false) Boolean disputeOnly,
      @RequestParam(required = false) String dimension) {
    return call(
        auth,
        "read",
        actor ->
            aging.distribution(
                new ReceivableAgingQuery(
                    currency,
                    shopId,
                    counterpartyRef,
                    bucketCode,
                    disputeOnly,
                    dimension,
                    0,
                    50)));
  }

  // ================================================================ 逾期清单

  /** 逾期清单：真分页 + 真筛选 + 真排序 + 按币种汇总（覆盖整个筛选结果集）。 */
  @GetMapping("/overdue")
  public FinanceReceivableAgingOverduePage overdue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) UUID counterpartyRef,
      @RequestParam(required = false) String bucketCode,
      @RequestParam(required = false) Boolean disputeOnly,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        actor ->
            aging.overdue(
                new ReceivableAgingQuery(
                    currency,
                    shopId,
                    counterpartyRef,
                    bucketCode,
                    disputeOnly,
                    null,
                    page,
                    size)));
  }

  // ================================================================ 账龄区间配置

  /** 生效中的账龄区间配置（租户配的，或蓝图默认的）。 */
  @GetMapping("/buckets")
  public FinanceReceivableAgingBucketConfig buckets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> aging.bucketConfig());
  }

  /**
   * 配置账龄区间（**整体替换**）。
   *
   * <p>⚠️ 档位必须连续覆盖 1 天起：留缝会让落在缝里的应收不属于任何一档（分布凭空少钱），重叠会让后面的档永远命中不到。服务端会整体校验后拒绝不合法的配置。
   * 传空列表 = 回到**蓝图给定的五档**。
   */
  @PutMapping("/buckets")
  public FinanceReceivableAgingBucketConfig updateBuckets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody FinanceAgingBucketConfigInput input) {
    return call(auth, "update", actor -> aging.updateBuckets(input, actor));
  }
}
