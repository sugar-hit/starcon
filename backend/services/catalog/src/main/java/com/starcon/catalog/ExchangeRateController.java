package com.starcon.catalog;

import static com.starcon.catalog.ExchangeRateModels.*;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
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
 * 5.2.2.1 汇率查询。
 *
 * <p>命名空间 {@code /catalog/exchange-rates}：与同 L3 的 5.2.2.2 汇率快照**共用页面路由
 * {@code /pricing/rates}**（前端按分区块组织），但端点各自独立 ——
 * 快照是「单据/定价生效时冻结所用的汇率」，本模块是「汇率事实」。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 */
@RestController
@RequestMapping("/catalog/exchange-rates")
public class ExchangeRateController {

  private final AccessService access;
  private final ExchangeRateService rates;

  public ExchangeRateController(AccessService access, ExchangeRateService rates) {
    this.access = access;
    this.rates = rates;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：来源清单、方向规则、唯一性规则、反向汇率规则、下游状态与数据归属。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「汇率方向必须显式」靠它落地。
   */
  @GetMapping("/capabilities")
  public RateCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> rates.capabilities());
  }

  /**
   * 汇率台账：分页 + 币种对 / 来源 / 日期区间 / 关键词筛选。
   *
   * <p>每行都带**完整方向**（{@code display}），页面上不允许只显示裸的 rate。
   */
  @GetMapping
  public RateListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String baseCurrency,
      @RequestParam(required = false) String quoteCurrency,
      @RequestParam(required = false) String sourceCode,
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
            rates.list(
                new RateListQuery(
                    baseCurrency,
                    quoteCurrency,
                    sourceCode,
                    dateFrom,
                    dateTo,
                    keyword,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /**
   * 某个币种对在指定日期当天或之前**最新**的一条汇率。
   *
   * <p>🔴 查不到返回 404 并写明原因，**不**回退到「最近的一版」。
   */
  @GetMapping("/latest")
  public RateView latest(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String baseCurrency,
      @RequestParam String quoteCurrency,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate asOf) {
    return call(auth, "spu.read", a -> rates.latest(baseCurrency, quoteCurrency, asOf));
  }

  /**
   * 「各币种对**各本位币**的汇率」面板。
   *
   * <p>本位币来自 identity 的法人档案（17.1.2.2）；🔴 读不到时返回 **503**
   * （理由写「读不到本位币」），不用空集合冒充「没有本位币」。
   */
  @GetMapping("/functional-currencies")
  public FunctionalCurrencyPanel functionalCurrencies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> rates.functionalCurrencyPanel(auth));
  }

  /** 汇率来源配置列表。 */
  @GetMapping("/sources")
  public List<RateSourceView> sources(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> rates.sources());
  }

  /** 新建汇率来源。 */
  @PostMapping("/sources")
  public RateSourceView createSource(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RateSourceInput input) {
    return call(auth, "spu.update", a -> rates.createSource(a, input));
  }

  /**
   * 初始化**内置来源**（中行 / 央行 / 平台结算 / 手工录入）。
   *
   * <p>幂等：已存在的来源不覆盖，只补齐缺失的。
   */
  @PostMapping("/sources/initialize")
  public List<RateSourceView> initializeSources(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.update", a -> rates.initializeSources(a));
  }

  /** 修改汇率来源（走乐观锁）。 */
  @PutMapping("/sources/{code}")
  public RateSourceView updateSource(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String code,
      @Valid @RequestBody RateSourceUpdate input) {
    return call(auth, "spu.update", a -> rates.updateSource(a, code, input));
  }

  /**
   * 手工录入一条汇率。
   *
   * <p>🔴 方向由「基准币种 / 报价币种」两个字段确定；反向汇率不许手工再录一条
   * （那是两个互相矛盾的事实），反向只由正向推导。
   */
  @PostMapping
  public RateView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody RateInput input) {
    return call(auth, "spu.update", a -> rates.create(a, input));
  }

  /** 单条汇率详情。 */
  @GetMapping("/{id}")
  public RateView detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> rates.rate(id));
  }

  /** 更正一条已登记的汇率（同一生效日的同一条记录，不新增第二条）。 */
  @PutMapping("/{id}")
  public RateView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody RateInput input) {
    return call(auth, "spu.update", a -> rates.update(a, id, input));
  }

  /**
   * 从配置的外部源拉一次汇率（真的发 HTTP 请求）。
   *
   * <p>🔴 拉取失败返回 **503 / 400**（响应体里带原因与失败状态），不用空集合冒充
   * 「这个源今天没有汇率」。
   *
   * <p><b>为什么不抛异常</b>：抛异常会让外层事务回滚，把刚写的失败日志一起回滚掉 ——
   * 失败日志是这次拉取唯一留下的痕迹。所以这里返回带状态码的响应体。
   */
  @PostMapping("/fetch")
  public ResponseEntity<FetchResult> fetch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FetchRequest request) {
    var result = call(auth, "spu.update", a -> rates.fetch(a, request));
    if ("FAILED".equals(result.status())) {
      var status = result.failureStatus() == null ? 503 : result.failureStatus();
      return ResponseEntity.status(status).body(result);
    }
    return ResponseEntity.ok(result);
  }

  /** 外部拉取日志（只读，倒序）。 */
  @GetMapping("/fetch-log")
  public List<FetchLogRow> fetchLog(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(auth, "spu.read", a -> rates.fetchLog(page == null ? 0 : page, size == null ? 20 : size));
  }
}
