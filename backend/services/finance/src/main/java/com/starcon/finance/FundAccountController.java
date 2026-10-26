package com.starcon.finance;

import static com.starcon.finance.FundAccountModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
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
 * 12.7.1.1 账户管理的 HTTP 端点。
 *
 * <p><b>命名空间是 {@code /finance/fund-accounts}</b>：与同 L3 的 12.7.2.1 资金分析
 * （{@code /finance/fund-analysis}）、12.2.1.1 收款单（{@code /finance/receipts}）、
 * 12.1.1.1 应收单（{@code /finance/receivables}）**刻意不共用一个前缀** ——
 * 那些是别的模块的命名空间（端点、契约、页面都归它们所有）。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p><b>权限码沿用同 L3 兄弟模块 12.7.2.1 的 {@code finance.receivable.read / .update}</b>：
 * 12.7 资金家族共用同一套词汇表。刻意<b>不新造权限词表</b> ——
 * 权限码在 identity 的 {@code permission(code)} 目录里，新造一档要改另一个服务的迁移
 * （那是它的写入所有权，且迁移版本号由调度者统一分配）。
 */
@RestController
@RequestMapping("/finance/fund-accounts")
public class FundAccountController {

  private final AccessService access;
  private final FundAccountService accounts;

  public FundAccountController(AccessService access, FundAccountService accounts) {
    this.access = access;
    this.accounts = accounts;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "finance.receivable." + permission);
    return access.run(actor, "finance.receivable." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些能力真接通、哪些还没交付、归谁。
   *
   * <p>页面把它固定显示在顶部 —— 「本模块不做跨币种折算」「12.7.1.2 对账未交付」
   * 这些事必须让人在账户旁边看到，而不是让人以为「没折算就是余额是 0」。
   */
  @GetMapping("/capabilities")
  public FinanceFundAccountCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> accounts.capabilities());
  }

  // ================================================================ 筛选项

  /** 筛选项下拉（账户类型词表 + 真实币种 + identity 的法人清单）。 */
  @GetMapping("/options")
  public FinanceFundAccountOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> accounts.options(auth));
  }

  // ================================================================ 账户档案

  /** 账户列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public FinanceFundAccountPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) FinanceFundAccountType accountType,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) UUID legalEntityId,
      @RequestParam(required = false) FinanceFundAccountStatus status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) String direction,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(
        auth,
        "read",
        actor ->
            accounts.list(
                new FinanceFundAccountQuery(
                    accountType,
                    currency,
                    legalEntityId,
                    status,
                    keyword,
                    sort,
                    direction,
                    page,
                    size)));
  }

  /**
   * 建账户。
   *
   * <p>⚠️ 法人**实时校验**：读不到法人清单 ⇒ 503；读到了但没有这个法人 ⇒ 400。
   * 账号**脱敏**存储与返回（原始账号不落库、不写日志）。
   */
  @PostMapping
  public FinanceFundAccountRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FinanceFundAccountCreateInput input) {
    return call(auth, "update", actor -> accounts.create(input, auth, actor));
  }

  /** 账户详情（档案 + 最近若干条余额历史）。 */
  @GetMapping("/{id}")
  public FinanceFundAccountDetail get(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> accounts.get(id));
  }

  /**
   * 改账户档案。
   *
   * <p>⚠️ <b>币种与账户编号不可改</b>（单账户单币种是硬约束，账户编号是身份）。
   * 换绑法人要实时校验。
   */
  @PutMapping("/{id}")
  public FinanceFundAccountRow update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceFundAccountUpdateInput input) {
    return call(auth, "update", actor -> accounts.update(id, input, auth, actor));
  }

  // ================================================================ 余额

  /**
   * 登记一次余额（同步 / 手工更新）。
   *
   * <p>⚠️ <b>追加</b>一条余额历史，历史记录不许改写；手工更新必须写理由。
   */
  @PostMapping("/{id}/balances")
  public FinanceFundAccountBalanceRow recordBalance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody FinanceFundAccountBalanceInput input) {
    return call(auth, "update", actor -> accounts.recordBalance(id, input, actor));
  }

  /** 某个账户的余额历史（真分页，按事实时刻倒序）。 */
  @GetMapping("/{id}/balances")
  public FinanceFundAccountBalancePage balances(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> accounts.balances(id, page, size));
  }

  /**
   * 余额汇总（**按币种分组**，币种内按法人分组）。
   *
   * <p>⚠️ <b>绝不跨币种相加</b>，也<b>不</b>做本币折算 ⇒ 「全部账户加在一起」这个数
   * 没有意义，响应里给 {@code null} 并写明归属模块。
   */
  @GetMapping("/balances/summary")
  public FinanceFundCurrencyBalanceSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> accounts.summary());
  }

  // ================================================================ 启用 / 停用

  /** 停用账户（不再接受新的余额登记；<b>不做物理删除</b> —— 资金凭据删不得）。 */
  @PostMapping("/{id}/deactivate")
  public FinanceFundAccountRow deactivate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> accounts.deactivate(id, actor));
  }

  /** 启用账户（停用之后可以重新启用）。 */
  @PostMapping("/{id}/activate")
  public FinanceFundAccountRow activate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "update", actor -> accounts.activate(id, actor));
  }
}
