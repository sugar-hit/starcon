package com.starcon.catalog;

import static com.starcon.catalog.PlatformFeeModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
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
 * 5.2.3.1 平台费用。
 *
 * <p>命名空间 {@code /catalog/platform-fees}。页面路由 {@code /pricing/fees}，
 * 与同 L3 的 5.2.3.2 物流与头程费用**共用**（前端按分区块组织），
 * 但端点各自独立 —— 5.2.3.2 未交付，本模块**不代它开形状**。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 跨服务调用透传调用方自己的 Authorization 头</b>：平台 / 站点目录的唯一来源是
 * channel 的授权只读端点（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图），
 * 本服务把**调用方自己的** bearer 原样带过去，租户由 channel 从成员关系解析 ——
 * 本服务**不**替对方决定租户，也**不**发明 {@code X-Tenant-Id} 之类的头。
 */
@RestController
@RequestMapping("/catalog/platform-fees")
public class PlatformFeeController {

  private final AccessService access;
  private final PlatformFeeService fees;

  public PlatformFeeController(AccessService access, PlatformFeeService fees) {
    this.access = access;
    this.fees = fees;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：三张词表 + 六个维度（逐个写明取不到没有 / 归谁管）+ 四条口径说明。
   *
   * <p>🔴 页面固定显示它 —— 蓝图加粗句要的是「差额要显性展示」，
   * 而「实际费用现在来自哪里」必须写在脸上，否则运营会把人工登记的数当成 12 的对账结果。
   */
  @GetMapping("/capabilities")
  public PlatformFeeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> fees.capabilities());
  }

  /**
   * 平台 / 站点目录（上游 2 平台目录，channel 服务）。
   *
   * <p>🔴 上游读不到时返回 **503**（理由写「读不到上游」），不用空清单冒充「这个租户没有平台」。
   */
  @GetMapping("/directory")
  public PlatformDirectory directory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> fees.directory(auth));
  }

  /** 费用规则台账：分页 + 关键词 / 平台 / 站点 / 费用类型 / 是否启用筛选。 */
  @GetMapping("/rules")
  public FeeRuleListPage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String feeType,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            fees.list(
                new PlatformFeeService.RuleQuery(
                    keyword, platformCode, siteCode, feeType, enabled,
                    page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 规则明细：规则 + 阶梯 + 作用域口径。 */
  @GetMapping("/rules/{ruleId}")
  public FeeRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "spu.read", a -> fees.detail(ruleId));
  }

  /** 新建费用规则。 */
  @PostMapping("/rules")
  public FeeRuleSaved createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FeeRuleInput input) {
    return call(auth, "spu.update", a -> fees.save(a, null, input, auth));
  }

  /** 修改费用规则（乐观锁：版本对不上时 409）。 */
  @PutMapping("/rules/{ruleId}")
  public FeeRuleSaved updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody FeeRuleInput input) {
    return call(auth, "spu.update", a -> fees.save(a, ruleId, input, auth));
  }

  /** 整体替换一条规则的阶梯（同事务删 + 插：要么全换要么全不换）。 */
  @PutMapping("/rules/{ruleId}/tiers")
  public FeeRuleDetail replaceTiers(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody TierListInput input) {
    return call(auth, "spu.update", a -> fees.replaceTiers(a, ruleId, input.tiers()));
  }

  /**
   * 测算一次销售会产生的平台费用项（蓝图输出句「测算时的费用项」）。
   *
   * <p>🔴 算不出来的费用项是 {@code applicable=false} + {@code amount=null}，
   * **不是 0**；合计不完整时 {@code totalAmount=null}。
   */
  @GetMapping("/estimate")
  public FeeEstimate estimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) UUID internalCategoryId,
      @RequestParam(required = false) UUID platformCategoryId,
      @RequestParam(required = false) BigDecimal saleAmount,
      @RequestParam(required = false) BigDecimal quantity,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime at) {
    return call(
        auth,
        "spu.read",
        a ->
            fees.estimate(
                new PlatformFeeService.EstimateQuery(
                    platformCode, siteCode, internalCategoryId, platformCategoryId,
                    saleAmount, quantity, currency, at)));
  }

  /** 实际费用台账（事后对账口径）：分页 + 平台 / 站点 / 费用类型 / 区间筛选。 */
  @GetMapping("/actuals")
  public FeeActualListPage actuals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String feeType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            fees.listActuals(
                new PlatformFeeService.ActualQuery(
                    platformCode, siteCode, feeType, from, to,
                    page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 登记一条实际费用（人工登记的平台账单观察值；不是 12 财务的结算对账结果）。 */
  @PostMapping("/actuals")
  public FeeActualSaved recordActual(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FeeActualInput input) {
    return call(auth, "spu.update", a -> fees.recordActual(a, input, auth));
  }

  /** 作废一条实际费用（必须写理由；作废不是删除，旧行仍在库里）。 */
  @PostMapping("/actuals/{actualId}/void")
  public FeeActualSaved voidActual(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID actualId,
      @Valid @RequestBody FeeVoidInput input) {
    return call(auth, "spu.update", a -> fees.voidActual(a, actualId, input.reason()));
  }

  /**
   * 费用差异对照（规则测算 vs 实际）。
   *
   * <p>🔴 这是蓝图加粗句的落点：差额必须能算出来并被展示。
   * 没有实际费用时整份报告 {@code available=false} + 原因 ——
   * **不是**「差异 0.00」。
   */
  @GetMapping("/variance")
  public FeeVarianceReport variance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String feeType,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "spu.read",
        a ->
            fees.variance(
                new PlatformFeeService.VarianceQuery(platformCode, siteCode, feeType, from, to)));
  }
}
