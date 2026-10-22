package com.starcon.channel;

import static com.starcon.channel.EprRegistrationModels.*;

import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.3.2.2 EPR/包装法。
 *
 * <p>蓝图：「管理 EPR 与包装法合规（德国 LUCID、包装注册、电池法、WEEE 等）：注册号、适用国家与品类、
 * 申报周期与关联费用。输入：注册信息；适用规则；申报数据。输出：EPR 档案 + 合规状态 + 费用记录。
 * 关系：上游 = 16.3.1.1；下游 = 12 财务（费用）、4 刊登（是否允许在相关国家销售）。
 * <b>EPR 未注册的国家应直接限制刊登</b>，这是避免下架与罚款最有效的做法。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：哪些下游未交付、量不到归谁、判定的口径是什么。
 *   <li>{@code GET /regimes} —— 制度码表 + 每个制度在哪些国家适用、适用哪些品类。
 *   <li>{@code GET /categories} —— 适用品类码表（受控值域）。
 *   <li>{@code GET /shops} —— 店铺目录（**读本服务自己的 `shop` 表**）+ 推导出的「需要 EPR 的国家」。
 *   <li>{@code GET /registrations} —— EPR 档案清单（**真分页 + 真筛选**）。
 *   <li>{@code POST /registrations} —— 新建一条登记。
 *   <li>{@code GET /registrations/{id}} —— 单条登记详情。
 *   <li>{@code PUT /registrations/{id}} —— 改登记（**版本化**：关旧版 + 建新版，**不回填**）。
 *   <li>{@code GET /registrations/{id}/versions} —— 生效版本历史。
 *   <li>{@code GET /coverage} —— 🔴 覆盖情况（**国家 × 品类** 的反查，真分页）。
 *   <li>{@code GET /compliance-status} —— 按国家的合规状态。
 *   <li>{@code POST /publish-gate} —— 🔴 刊登闸门（**服务端真算**的结论 + 逐条原因）。
 *   <li>{@code POST /publish-gate/batch} —— 批量刊登闸门。
 *   <li>{@code GET /filings} —— 申报期状态（**当前是否在申报期内**）。
 *   <li>{@code GET /fees} / {@code POST /fees} / {@code PUT /fees/{id}} —— 费用记录。
 *   <li>{@code GET /fee-summary} —— 🔴 费用汇总（**按币种分组**，不把不同币种直接相加）。
 *   <li>{@code GET /exemptions} / {@code POST /exemptions} / {@code PUT /exemptions/{id}} ——
 *       「本制度在本国不适用我的业务」声明（**NOT_APPLICABLE ≠ 未注册**）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「费用入账」端点（12 财务在本仓尚未落地）、
 * 没有「VAT/IOSS」端点（16.3.2.1 未交付）、没有「申报日历」端点（16.3.1.2 未交付）——
 * <b>放行一个不存在的入口比 404 更糟</b>（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/epr-registrations")
public class EprRegistrationController {

  private final AccessService access;
  private final EprRegistrationService service;

  public EprRegistrationController(AccessService access, EprRegistrationService service) {
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
  public EprCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 参考数据 ----

  /** 制度码表 + 每个制度在哪些国家适用、适用哪些品类。 */
  @GetMapping("/regimes")
  public EprRegimeList regimes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.regimes());
  }

  /** 适用品类码表（受控值域）。 */
  @GetMapping("/categories")
  public EprCategoryList categories(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.categories());
  }

  // ---- 店铺目录 ----

  /** 店铺目录（读本服务自己的 shop 表）+ 推导出的「需要 EPR 的国家」。 */
  @GetMapping("/shops")
  public EprShopList shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String platformCode) {
    return call(auth, "channel.operation.read", a -> service.shops(keyword, platformCode));
  }

  // ---- EPR 档案 ----

  /** EPR 档案清单（真分页 + 真筛选）。 */
  @GetMapping("/registrations")
  public EprRegistrationPage registrations(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String regimeCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String validityKind,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.registrations(regimeCode, status, validityKind, countryCode, page, size));
  }

  /** 单条登记详情。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/registrations/{id}")
  public EprRegistrationView registration(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.registration(id));
  }

  /** 登记一条 EPR 注册（一个制度 × 国家 × 品类一个号）。 */
  @PostMapping("/registrations")
  public EprRegistrationView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody EprRegistrationInput input) {
    return call(auth, "channel.operation.create", a -> service.create(input));
  }

  /**
   * 改一条登记 —— <b>版本化</b>：关掉当前版本 + 插入新版本，<b>不许回填</b>。
   *
   * <p>「改一个数字就等于说谎」的防线：就地改当前版本的事实在 DB 层会被触发器直接拒掉。
   */
  @PutMapping("/registrations/{id}")
  public EprRegistrationView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody EprRegistrationInput input) {
    return call(auth, "channel.operation.update", a -> service.update(id, input));
  }

  /** 生效版本历史（同一逻辑登记的各个版本）。 */
  @GetMapping("/registrations/{id}/versions")
  public EprVersionList versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.versions(id));
  }

  // ---- 覆盖与合规状态 ----

  /** 🔴 覆盖情况：「这个国家这个品类有没有覆盖」的反查（真分页 + 真筛选）。 */
  @GetMapping("/coverage")
  public EprCoveragePage coverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String regimeCode,
      @RequestParam(required = false) String categoryCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.coverage(countryCode, regimeCode, categoryCode, page, size));
  }

  /** 按国家的合规状态（国家清单来自店铺启用的站点）。 */
  @GetMapping("/compliance-status")
  public EprComplianceStatus complianceStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String countryCode) {
    return call(auth, "channel.operation.read", a -> service.complianceStatus(countryCode));
  }

  // ---- 刊登闸门 ----

  /**
   * 🔴 刊登闸门：<b>服务端真算</b>的「这份内容能不能发」。
   *
   * <p>结论 {@code allowed=false} + 逐条原因（哪个制度 / 哪个国家 / 没注册 / 有效期已过）。
   */
  @PostMapping("/publish-gate")
  public EprPublishGate publishGate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody EprPublishGateRequest request) {
    return call(auth, "channel.operation.read", a -> service.publishGate(request));
  }

  /** 批量刊登闸门（只要有一个目标被阻断，整批就不放行）。 */
  @PostMapping("/publish-gate/batch")
  public EprPublishGateBatch publishGateBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody EprPublishGateBatchRequest request) {
    return call(auth, "channel.operation.read", a -> service.publishGateBatch(request));
  }

  // ---- 申报期 ----

  /** 申报期状态清单：当前是否在申报期内 + 距下一期还有几天（真分页 + 真筛选）。 */
  @GetMapping("/filings")
  public EprFilingPage filings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String regimeCode,
      @RequestParam(required = false) String inPeriod,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.filings(regimeCode, inPeriod, page, size));
  }

  // ---- 费用 ----

  /** 费用清单（真分页 + 真筛选）。 */
  @GetMapping("/fees")
  public EprFeePage fees(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String registrationKey,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String feeType,
      @RequestParam(required = false) String billingCycle,
      @RequestParam(required = false) String status,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.fees(
                registrationKey, currency, feeType, billingCycle, status, page, size));
  }

  /** 记一笔费用（金额是十进制字符串，币种必带）。 */
  @PostMapping("/fees")
  public EprFeeView createFee(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody EprFeeInput input) {
    return call(auth, "channel.operation.create", a -> service.createFee(input));
  }

  /** 改一笔费用。 */
  @PutMapping("/fees/{id}")
  public EprFeeView updateFee(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody EprFeeInput input) {
    return call(auth, "channel.operation.update", a -> service.updateFee(id, input));
  }

  /** 🔴 费用汇总：**按币种分组**（不同币种绝不直接相加）。 */
  @GetMapping("/fee-summary")
  public EprFeeSummary feeSummary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String registrationKey,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String feeType) {
    return call(
        auth, "channel.operation.read", a -> service.feeSummary(registrationKey, currency, feeType));
  }

  // ---- 不适用声明 ----

  /** 「本制度在本国不适用我的业务」声明清单（真分页）。 */
  @GetMapping("/exemptions")
  public EprExemptionPage exemptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String countryCode,
      @RequestParam(required = false) String regimeCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.exemptions(countryCode, regimeCode, page, size));
  }

  /** 声明「本制度在本国不适用我的业务」（理由必填）。 */
  @PostMapping("/exemptions")
  public EprExemptionView createExemption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody EprExemptionInput input) {
    return call(auth, "channel.operation.create", a -> service.createExemption(input));
  }

  /** 撤销一条不适用声明（关窗口，不删行）。 */
  @PutMapping("/exemptions/{id}")
  public EprExemptionView revokeExemption(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.revokeExemption(id));
  }
}
