package com.starcon.channel;

import static com.starcon.channel.VatIossModels.*;

import java.time.LocalDate;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

/**
 * 16.3.2.1 VAT/IOSS。
 *
 * <p>蓝图：「管理 VAT 与 IOSS 制度：适用店铺与商品、申报周期、税率适用、IOSS 订单识别与代收代缴统计、
 * 与财务申报数据的对接。输入：IOSS 配置；订单税务数据；12.6.1.2 申报底稿。
 * 输出：IOSS 订单清单与金额统计；申报状态。
 * 关系：上游 = 12.6.1.1 税率、9 订单；下游 = 12.6.1.2。
 * <b>IOSS 订单与一般 VAT 订单必须区分统计</b>，混在一起申报是最常见的错误。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：哪些上游真读得到、哪些下游没交付、判定的口径是什么。
 *   <li>{@code GET /options} —— 筛选项（分档 / 原因 / 申报状态 / 周期 / 辖区 / 币种）。
 *   <li>{@code GET /shops} —— 店铺目录（**读本服务自己的 {@code shop} 表**）+ 是否落在 IOSS 覆盖范围内。
 *   <li>{@code GET /registrations} / {@code POST /registrations} / {@code GET /registrations/{id}} /
 *       {@code PUT /registrations/{id}} —— IOSS 登记（含**适用店铺与商品**）。
 *   <li>{@code GET /filing-cycles} / {@code POST /filing-cycles} / {@code PUT /filing-cycles/{id}} ——
 *       申报周期配置。
 *   <li>{@code GET /filing-periods} / {@code POST /filing-periods} / {@code GET /filing-periods/{id}} —
 *       申报期与**申报状态**。
 *   <li>{@code POST /filing-periods/{id}/draft} / {@code POST /filing-periods/{id}/confirm} ——
 *       🔴 申报状态的**真实流转**（DRAFT → DRAFTED → CONFIRMED，只能前进）。
 *   <li>{@code GET /filing-periods/{id}/statistics} —— 🔴 本期底稿统计（**按分档 × 币种**分组）。
 *   <li>{@code POST /orders/sync} —— 🔴 从 trade 拉**真实订单** → 识别 IOSS → 落库。
 *   <li>{@code GET /orders} / {@code GET /orders/{id}} —— IOSS 订单清单与单笔详情（**真分页 + 真筛选**）。
 *   <li>{@code GET /statistics} —— 🔴 全量统计（**按分档 × 币种**分组）。
 *   <li>{@code GET /tax-rates} —— 🔴 税率适用（读 finance 12.6.1.1，**读不到就说读不到**）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「向税务机构提交申报」的端点（本系统只保证数据完整可核）、
 * 没有「申报底稿」端点（12.6.1.2 未交付）、没有「申报日历」端点（16.3.1.2 未交付）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/vat-ioss")
public class VatIossController {

  private final AccessService access;
  private final VatIossService service;

  public VatIossController(AccessService access, VatIossService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的下游逐条列出，上游逐条报「读得到 / 读不到」。 */
  @GetMapping("/capabilities")
  public VatIossCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 筛选项 ----

  /** 筛选项（前端下拉直接用，不写死在前端）。 */
  @GetMapping("/options")
  public VatIossOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.options());
  }

  // ---- 店铺目录 ----

  /** 店铺目录（读本服务自己的 shop 表）+ 是否落在 IOSS 覆盖范围内。 */
  @GetMapping("/shops")
  public VatIossShopList shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platformCode) {
    return call(auth, "channel.operation.read", a -> service.shops(keyword, platformCode));
  }

  // ---- IOSS 登记 ----

  /** IOSS 登记清单（真分页）。 */
  @GetMapping("/registrations")
  public VatIossRegistrationPage registrations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.registrations(page, size));
  }

  /** 单条登记详情。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/registrations/{id}")
  public VatIossRegistrationView registration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.registration(id));
  }

  /** 登记一条 IOSS 登记（含适用店铺与适用商品）。 */
  @PostMapping("/registrations")
  public VatIossRegistrationView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody VatIossRegistrationInput input) {
    return call(auth, "channel.operation.create", a -> service.create(input));
  }

  /**
   * 改一条登记 —— <b>登记号与生效时刻不可改</b>（DB 触发器钉死）：
   * 就地改号等于把当初申报用的号偷偷换掉。想换号 = 关闭当前登记 + 另登记一条。
   */
  @PutMapping("/registrations/{id}")
  public VatIossRegistrationView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody VatIossRegistrationInput input) {
    return call(auth, "channel.operation.update", a -> service.update(id, input));
  }

  // ---- 申报周期 ----

  /** 申报周期配置清单（真分页 + 真筛选）。 */
  @GetMapping("/filing-cycles")
  public VatIossFilingCyclePage filingCycles(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.filingCycles(jurisdictionCode, page, size));
  }

  /** 登记一条申报周期配置。 */
  @PostMapping("/filing-cycles")
  public VatIossFilingCycleView createFilingCycle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody VatIossFilingCycleInput input) {
    return call(auth, "channel.operation.create", a -> service.createFilingCycle(input));
  }

  /** 改一条申报周期配置。 */
  @PutMapping("/filing-cycles/{id}")
  public VatIossFilingCycleView updateFilingCycle(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody VatIossFilingCycleInput input) {
    return call(auth, "channel.operation.update", a -> service.updateFilingCycle(id, input));
  }

  // ---- 申报期与申报状态 ----

  /** 申报期清单（真分页 + 真筛选 + 三档状态计数）。 */
  @GetMapping("/filing-periods")
  public VatIossFilingPeriodPage filingPeriods(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.filingPeriods(jurisdictionCode, status, page, size));
  }

  /** 开一期申报。 */
  @PostMapping("/filing-periods")
  public VatIossFilingPeriodView createFilingPeriod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody VatIossFilingPeriodInput input) {
    return call(auth, "channel.operation.create", a -> service.createFilingPeriod(input));
  }

  /** 一期申报的详情（含申报状态）。 */
  @GetMapping("/filing-periods/{id}")
  public VatIossFilingPeriodView filingPeriod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.filingPeriod(id));
  }

  /** 🔴 出底稿：DRAFT 待提交 → DRAFTED 已出底稿。 */
  @PostMapping("/filing-periods/{id}/draft")
  public VatIossFilingPeriodView draftFilingPeriod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.draftFilingPeriod(id));
  }

  /**
   * 🔴 确认：DRAFTED 已出底稿 → CONFIRMED 已确认。
   *
   * <p>本系统**只保证数据完整可核**，<b>不代用户向税务机构提交申报</b> ⇒ 刻意没有「已提交」状态。
   */
  @PostMapping("/filing-periods/{id}/confirm")
  public VatIossFilingPeriodView confirmFilingPeriod(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.confirmFilingPeriod(id));
  }

  /** 🔴 本期底稿统计（**按分档 × 币种**分组）—— 这正是 12.6.1.2 要的输入。 */
  @GetMapping("/filing-periods/{id}/statistics")
  public VatIossStatistics filingPeriodStatistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.filingPeriodStatistics(id));
  }

  // ---- 订单 ----

  /**
   * 🔴 识别一批订单：从 trade 拉**真实订单** → 按十步判定表归档 → 落库。
   *
   * <p>上游读不到 ⇒ 整体 503，**一条都不写**（绝不拿空集合冒充「上游没有订单」）。
   */
  @PostMapping("/orders/sync")
  public VatIossOrderSyncResult syncOrders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode) {
    return call(
        auth, "channel.operation.create", a -> service.syncOrders(shopCode, auth));
  }

  /** IOSS 订单清单（真分页 + 真筛选 + 四档计数）。 */
  @GetMapping("/orders")
  public VatIossOrderPage orders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String filingScope,
      @RequestParam(required = false) String reasonCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.orders(
                filingScope,
                reasonCode,
                currency,
                jurisdictionCode,
                shopCode,
                keyword,
                dateFrom,
                dateTo,
                page,
                size));
  }

  /** 单笔订单详情（判定 + 行明细）。 */
  @GetMapping("/orders/{id}")
  public VatIossOrderDetail order(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.order(id));
  }

  // ---- 统计 ----

  /** 🔴 全量统计（**按分档 × 币种**分组 —— 不同币种绝不直接相加）。 */
  @GetMapping("/statistics")
  public VatIossStatistics statistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String filingScope,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.statistics(jurisdictionCode, filingScope, currency, shopCode, dateFrom, dateTo));
  }

  // ---- 税率 ----

  /** 🔴 税率适用（读 finance 12.6.1.1；**读不到就如实说读不到**，绝不退化成 0%）。 */
  @GetMapping("/tax-rates")
  public VatIossTaxRateView taxRate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String jurisdictionCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
    return call(
        auth, "channel.operation.read", a -> service.taxRate(jurisdictionCode, shopCode, asOf, auth));
  }
}
