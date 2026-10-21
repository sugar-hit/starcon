package com.starcon.catalog;

import static com.starcon.catalog.ExchangeRateSnapshotModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
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
 * 5.2.2.2 汇率快照。
 *
 * <p>命名空间 {@code /catalog/exchange-rate-snapshots}：与同 L3 的 5.2.2.1 汇率查询**共用页面路由
 * {@code /pricing/rates}**（前端按分区块组织），但端点各自独立 ——
 * 5.2.2.1 是「汇率事实」，本模块是「冻结下来的换算过程」。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 */
@RestController
@RequestMapping("/catalog/exchange-rate-snapshots")
public class ExchangeRateSnapshotController {

  private final AccessService access;
  private final ExchangeRateSnapshotService snapshots;

  public ExchangeRateSnapshotController(AccessService access, ExchangeRateSnapshotService snapshots) {
    this.access = access;
    this.snapshots = snapshots;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：单据类型、舍入方式、舍入规则、方向规则、冻结规则、下游与数据归属。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「今天汇率变了不能让上个月的利润报表数字跟着变」靠它落地。
   */
  @GetMapping("/capabilities")
  public SnapshotCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> snapshots.capabilities());
  }

  /** 快照台账：分页 + 单据 / 币种对 / 生效日区间 / 关键词筛选。 */
  @GetMapping
  public SnapshotListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String documentType,
      @RequestParam(required = false) UUID documentId,
      @RequestParam(required = false) String sourceCurrency,
      @RequestParam(required = false) String targetCurrency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate dateTo,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            snapshots.list(
                new SnapshotListQuery(
                    documentType,
                    documentId,
                    sourceCurrency,
                    targetCurrency,
                    dateFrom,
                    dateTo,
                    keyword,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /** 单条快照详情。 */
  @GetMapping("/{id}")
  public SnapshotView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> snapshots.snapshot(id));
  }

  /**
   * 冻结一条汇率快照。
   *
   * <p>🔴 冻结的是**已登记**的汇率事实（5.2.2.1）：想冻结一条还没登记的汇率，先去登记它。
   */
  @PostMapping
  public SnapshotView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SnapshotInput input) {
    return call(auth, "spu.update", a -> snapshots.create(a, input));
  }

  /**
   * 复算：拿快照里冻结的原价、汇率、方向、舍入方式重新算一遍目标币金额并逐笔比对。
   *
   * <p>这是蓝图输出里的「利润复算能力」—— 纯计算，不写库。
   */
  @PostMapping("/replay")
  public SnapshotReplayReport replay(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReplayRequest request) {
    return call(auth, "spu.read", a -> snapshots.replay(a, request));
  }

  /** 舍入规则配置列表。 */
  @GetMapping("/rounding-rules")
  public List<RoundingRuleView> roundingRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> snapshots.roundingRules());
  }

  /** 新建舍入规则。 */
  @PostMapping("/rounding-rules")
  public RoundingRuleView createRoundingRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RoundingRuleInput input) {
    return call(auth, "spu.update", a -> snapshots.createRoundingRule(a, input));
  }

  /**
   * 初始化**内置舍入规则**（通用两位 + 常见币种 + 日元/韩元零位）。
   *
   * <p>幂等：已存在的规则不覆盖，只补齐缺失的。
   */
  @PostMapping("/rounding-rules/initialize")
  public List<RoundingRuleView> initializeRoundingRules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.update", a -> snapshots.initializeRoundingRules(a));
  }

  /** 修改舍入规则（走乐观锁）。 */
  @PutMapping("/rounding-rules/{code}")
  public RoundingRuleView updateRoundingRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @Valid @RequestBody RoundingRuleUpdate input) {
    return call(auth, "spu.update", a -> snapshots.updateRoundingRule(a, code, input));
  }
}
