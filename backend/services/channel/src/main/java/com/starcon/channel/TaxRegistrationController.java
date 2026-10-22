package com.starcon.channel;

import static com.starcon.channel.TaxRegistrationModels.*;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.3.1.1 税号与辖区。
 *
 * <p>蓝图：「维护各店铺/法人在各销售辖区的税务登记：税号、VAT 状态、是否注册、EPR 注册号、
 * 注册有效期与申报周期。输入：税号录入；辖区选择；有效期。输出：税务登记档案 + 缺失项提示。
 * 关系：上游 = 17 法人、2 店铺；下游 = 16.3.2.1、12.6 税务。
 * <b>未注册辖区的销售额是合规炸弹</b>：欧洲没有 VAT 号还在欧盟销售，是稽查重点。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：哪些下游未交付、量不到归谁、缺口口径是什么。
 *   <li>{@code GET /jurisdictions} —— 受控辖区码表（**不是自由文本**，值域钉死在 DB 层）。
 *   <li>{@code GET /shops} —— 店铺目录（**读本服务自己的 `shop` 表**）+ 推导出���应当登记的辖区」。
 *   <li>{@code GET /registrations} —— 登记档案清单（**真分页 + 真筛选**）。
 *   <li>{@code POST /registrations} —— 登记一条（**一个主体一个辖区只有一条**）。
 *   <li>{@code GET /registrations/{id}} —— 单条登记详情。
 *   <li>{@code PUT /registrations/{id}} —— 改登记（含撤下：status=ARCHIVED；**不做物理删除**）。
 *   <li>{@code GET /gaps} —— 🔴 缺失项清单（**真分页 + 真筛选** + 汇总计数）。
 *   <li>{@code GET /gap-summary} —— 🔴 缺失项汇总（按类型计数 + 受影响店铺 / 主体数）。
 *   <li>{@code GET /coverage} —— 逐店铺的辖区覆盖情况（登记齐了没有）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「申报日历」端点（16.3.1.2 未交付）、
 * 没有「VAT/IOSS」端点（16.3.2.1 未交付）、没有「EPR 费用」端点（16.3.2.2 未交付）、
 * 没有「税额计算」端点（12.6 财务在本仓尚未落地）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/tax-registration")
public class TaxRegistrationController {

  private final AccessService access;
  private final TaxRegistrationService service;

  public TaxRegistrationController(AccessService access, TaxRegistrationService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：未交付的下游逐条列出（available=false + ownerModule + 影响说明）。 */
  @GetMapping("/capabilities")
  public TaxRegistrationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities(auth));
  }

  // ---- 辖区码表 ----

  /** 受控辖区码表（值域钉死在 DB 层，不是自由文本）。 */
  @GetMapping("/jurisdictions")
  public TaxJurisdictionList jurisdictions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.jurisdictions());
  }

  // ---- 店铺目录 ----

  /** 店铺目录（读本服务自己的 shop 表）+ 推导出的「应当登记的辖区」。 */
  @GetMapping("/shops")
  public TaxShopList shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platformCode) {
    return call(auth, "channel.operation.read", a -> service.shops(keyword, platformCode));
  }

  // ---- 登记档案 ----

  /** 登记档案清单（真分页 + 真筛选）。 */
  @GetMapping("/registrations")
  public TaxRegistrationPage registrations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String subjectId,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false) String vatStatus,
      @RequestParam(required = false) String registered,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.registrations(
                subjectType, subjectId, jurisdictionCode, vatStatus, registered, status, page, size,
                auth));
  }

  /** 单条登记详情。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/registrations/{id}")
  public TaxRegistrationView registration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.registration(id, auth));
  }

  /** 登记一条税务登记（一个主体一个辖区只有一条，重复 ⇒ 409）。 */
  @PostMapping("/registrations")
  public TaxRegistrationView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody TaxRegistrationInput input) {
    return call(auth, "channel.operation.create", a -> service.create(input, auth));
  }

  /** 改一条登记（含撤下 status=ARCHIVED；登记是事实，不做物理删除）。 */
  @PutMapping("/registrations/{id}")
  public TaxRegistrationView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody TaxRegistrationInput input) {
    return call(auth, "channel.operation.update", a -> service.update(id, input, auth));
  }

  // ---- 缺失项 ----

  /** 🔴 缺失项清单（真分页 + 真筛选 + 汇总计数）。 */
  @GetMapping("/gaps")
  public TaxGapPage gaps(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String gapType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String jurisdictionCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.gaps(
                gapType, severity, subjectType, shopId, jurisdictionCode, page, size, auth));
  }

  /** 🔴 缺失项汇总（按类型计数 + 受影响店铺 / 主体数）。 */
  @GetMapping("/gap-summary")
  public TaxGapSummary gapSummary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String gapType,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String subjectType,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String jurisdictionCode) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.gapSummary(gapType, severity, subjectType, shopId, jurisdictionCode, auth));
  }

  // ---- 覆盖情况 ----

  /** 逐店铺的辖区覆盖情况（登记齐了没有）。 */
  @GetMapping("/coverage")
  public TaxCoverageList coverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.coverage(auth));
  }
}
