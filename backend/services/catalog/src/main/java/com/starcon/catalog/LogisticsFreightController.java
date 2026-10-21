package com.starcon.catalog;

import static com.starcon.catalog.LogisticsFreightModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 5.2.3.2 物流与头程费用。
 *
 * <p>命名空间 {@code /catalog/logistics-freight}。页面路由 {@code /pricing/fees}，
 * 与同 L3 的 5.2.3.1 平台费用**共用**（前端按分区块组织），但端点各自独立 ——
 * 本模块**不**挂��� {@code platform-fees/} 之下（那是另一个模块的形状）。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 跨服务调用 = 零</b>：渠道 / 承运商 / 运输方式都归 10 物流与头程（卷级引用），
 * 本服务只存**逻辑引用码 + 名称快照**，不建跨库 FK / 视图 / SQL。
 */
@RestController
@RequestMapping("/catalog/logistics-freight")
public class LogisticsFreightController {

  private final AccessService access;
  private final LogisticsFreightService freight;

  public LogisticsFreightController(AccessService access, LogisticsFreightService freight) {
    this.access = access;
    this.freight = freight;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：四张词表 + 六个维度（逐个写明取不到没有 / 归谁管）+ 七条口径说明。
   *
   * <p>🔴 页面固定显示它 —— 「上游没有实际运费结算事实」这一条必须写在脸上，
   * 否则运营会把人工登记的数当成物流商的结算结果。
   */
  @GetMapping("/capabilities")
  public LogisticsFreightCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> freight.capabilities());
  }

  // ================================================================ 渠道计费规则

  /** 渠道计费规则台账：分页 + 关键词 / 渠道 / 承运商 / 运输方式 / 计费口径 / 状态筛选。 */
  @GetMapping("/rules")
  public FreightRuleListPage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String channelCode,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String shippingMethodCode,
      @RequestParam(required = false) String chargeBasis,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            freight.listRules(
                new LogisticsFreightService.RuleQuery(
                    keyword, channelCode, carrierCode, shippingMethodCode, chargeBasis, status,
                    page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 规则明细：规则 + 阶梯 + 作用域口径。 */
  @GetMapping("/rules/{ruleId}")
  public FreightRuleDetail rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "spu.read", a -> freight.ruleDetail(ruleId));
  }

  /** 新建一条渠道计费规则（规则与阶梯同事务写入）。 */
  @PostMapping("/rules")
  public FreightRuleSaved createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FreightRuleInput input) {
    return call(auth, "spu.update", a -> freight.createRule(a, input));
  }

  /**
   * 新增一个版本并取代旧版本。
   *
   * <p>🔴 已生效的规则**不可改写** —— 「把费率从 0.15 改成 0.12」在数据上表达为
   * 「新增一条 version_no+1 的规则 + 旧版本置为已被取代」。
   */
  @PostMapping("/rules/{ruleId}/supersede")
  public FreightRuleSaved supersedeRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody FreightRuleInput input) {
    return call(auth, "spu.update", a -> freight.supersedeRule(a, ruleId, input));
  }

  /** 停用一条规则版本（必须写理由；停用不是删除，旧版本仍在库里可追溯）。 */
  @PostMapping("/rules/{ruleId}/disable")
  public FreightRuleSaved disableRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody FreightDisableInput input) {
    return call(auth, "spu.update", a -> freight.disableRule(a, ruleId, input.reason()));
  }

  // ================================================================ 附加费

  /** 附加费台账：分页 + 关键词 / 渠道 / 承运商 / 运输方式 / 类型 / 状态筛选。 */
  @GetMapping("/surcharges")
  public FreightSurchargeListPage surcharges(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String channelCode,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String shippingMethodCode,
      @RequestParam(required = false) String surchargeType,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            freight.listSurcharges(
                new LogisticsFreightService.SurchargeQuery(
                    keyword, channelCode, carrierCode, shippingMethodCode, surchargeType, status,
                    page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 登记一条附加费（燃油 / 旺季 / 偏远）。 */
  @PostMapping("/surcharges")
  public FreightSurchargeSaved createSurcharge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FreightSurchargeInput input) {
    return call(auth, "spu.update", a -> freight.createSurcharge(a, input));
  }

  /** 停用一条附加费（必须写理由）。 */
  @PostMapping("/surcharges/{surchargeId}/disable")
  public FreightSurchargeSaved disableSurcharge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID surchargeId,
      @Valid @RequestBody FreightDisableInput input) {
    return call(auth, "spu.update", a -> freight.disableSurcharge(a, surchargeId, input.reason()));
  }

  // ================================================================ 头程费用结构

  /** 头程费用结构台账：分页 + 关键词 / 运输方式 / 起运地 / 目的地 / 状态筛选。 */
  @GetMapping("/head-freight/structures")
  public HeadFreightStructureListPage headStructures(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String shippingMethodCode,
      @RequestParam(required = false) String originCode,
      @RequestParam(required = false) String destinationCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            freight.listHeadStructures(
                new LogisticsFreightService.HeadStructureQuery(
                    keyword, shippingMethodCode, originCode, destinationCode, status,
                    page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 头程费用结构明细（结构 + 成本项）。 */
  @GetMapping("/head-freight/structures/{structureId}")
  public HeadFreightStructureDetail headStructure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID structureId) {
    return call(auth, "spu.read", a -> freight.headStructureDetail(structureId));
  }

  /** 新建一条头程费用结构（结构与成本项同事务写入）。 */
  @PostMapping("/head-freight/structures")
  public HeadFreightStructureSaved createHeadStructure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody HeadFreightStructureInput input) {
    return call(auth, "spu.update", a -> freight.createHeadStructure(a, input));
  }

  /** 停用一条头程费用结构（必须写理由）。 */
  @PostMapping("/head-freight/structures/{structureId}/disable")
  public HeadFreightStructureSaved disableHeadStructure(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID structureId,
      @Valid @RequestBody FreightDisableInput input) {
    return call(auth, "spu.update", a -> freight.disableHeadStructure(a, structureId, input.reason()));
  }

  // ================================================================ 测算

  /**
   * 测算一次发运的渠道运费（逐步拆解：计费量 → 命中档位 → 基础运费 → 附加费 → 合计）。
   *
   * <p>🔴 算不出来的步骤是 {@code applicable=false} + {@code amount=null}，
   * **不是 0**；合计不完整时 {@code totalAmount=null}。
   */
  @GetMapping("/estimate")
  public FreightEstimate estimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String channelCode,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String shippingMethodCode,
      @RequestParam(required = false) String chargeBasis,
      @RequestParam(required = false) BigDecimal weightKg,
      @RequestParam(required = false) BigDecimal lengthCm,
      @RequestParam(required = false) BigDecimal widthCm,
      @RequestParam(required = false) BigDecimal heightCm,
      @RequestParam(required = false) BigDecimal pieces,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime at) {
    return call(
        auth,
        "spu.read",
        a ->
            freight.estimate(
                new LogisticsFreightService.EstimateQuery(
                    channelCode, carrierCode, shippingMethodCode, chargeBasis,
                    weightKg, lengthCm, widthCm, heightCm, pieces, at)));
  }

  /** 测算一次头程的费用（逐步拆解成本项）。 */
  @GetMapping("/head-freight/estimate")
  public HeadFreightEstimate headEstimate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shippingMethodCode,
      @RequestParam(required = false) String originCode,
      @RequestParam(required = false) String destinationCode,
      @RequestParam(required = false) BigDecimal shipments,
      @RequestParam(required = false) BigDecimal cbm,
      @RequestParam(required = false) BigDecimal containers,
      @RequestParam(required = false) BigDecimal weightKg,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime at) {
    return call(
        auth,
        "spu.read",
        a ->
            freight.headEstimate(
                new LogisticsFreightService.HeadEstimateQuery(
                    shippingMethodCode, originCode, destinationCode,
                    shipments, cbm, containers, weightKg, at)));
  }

  // ================================================================ 实际运费

  /** 实际运费台账（事后对账口径）：分页 + 渠道 / 承运商 / 运输方式 / 区间筛选。 */
  @GetMapping("/actuals")
  public FreightActualListPage actuals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String channelCode,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String shippingMethodCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            freight.listActuals(
                new LogisticsFreightService.ActualQuery(
                    channelCode, carrierCode, shippingMethodCode, from, to,
                    page == null ? 0 : page, size == null ? 20 : size)));
  }

  /** 登记一条实际运费（人工登记的物流商账单观察值；不是 10 的结算结果）。 */
  @PostMapping("/actuals")
  public FreightActualSaved recordActual(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody FreightActualInput input) {
    return call(auth, "spu.update", a -> freight.recordActual(a, input));
  }

  /** 作废一条实际运费（必须写理由；作废不是删除，旧行仍在库里）。 */
  @PostMapping("/actuals/{actualId}/void")
  public FreightActualSaved voidActual(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID actualId,
      @Valid @RequestBody FreightDisableInput input) {
    return call(auth, "spu.update", a -> freight.voidActual(a, actualId, input.reason()));
  }

  /**
   * 运费差异对照（规则测算 vs 实际）。
   *
   * <p>🔴 没有实际运费时整份报告 {@code available=false} + 原因 ——
   * **不是**「差异 0.00」。
   */
  @GetMapping("/variance")
  public FreightVarianceReport variance(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String channelCode,
      @RequestParam(required = false) String carrierCode,
      @RequestParam(required = false) String shippingMethodCode,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return call(
        auth,
        "spu.read",
        a ->
            freight.variance(
                new LogisticsFreightService.VarianceQuery(
                    channelCode, carrierCode, shippingMethodCode, from, to)));
  }
}
