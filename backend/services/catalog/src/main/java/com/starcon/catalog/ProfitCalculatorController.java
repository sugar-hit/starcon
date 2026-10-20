package com.starcon.catalog;

import static com.starcon.catalog.ProfitCalculatorModels.*;

import com.starcon.catalog.ChannelPlatformPort.ChannelPlatformUnavailableException;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 5.3.1.1 利润测算器。
 *
 * <p>命名空间 {@code /catalog/profit-calculator}：页面路由 {@code /pricing/calculator}。
 * 与 5.3.1.2 批量测算**共用页面路由**（前端按分区块组织），但端点各自独立 ——
 * 批量测算是「对一批 SKU 跑测算」，本模块是「一次测算 + 方案」。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 跨服务调用透传调用方自己的 Authorization 头</b>：平台目录（2.1.1.1）的唯一来源是
 * channel 的授权只读端点（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图），
 * 本服务把**调用方自己的** bearer 原样带过去，租户由 channel 从成员关系解析 ——
 * 本服务**不**替对方决定租户，也**不**发明 {@code X-Tenant-Id} 之类的头。
 */
@RestController
@RequestMapping("/catalog/profit-calculator")
public class ProfitCalculatorController {

  private final AccessService access;
  private final ProfitCalculatorService calculator;

  public ProfitCalculatorController(AccessService access, ProfitCalculatorService calculator) {
    this.access = access;
    this.calculator = calculator;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：七个扣减项的来源、能不能取到、归谁管、缺了会怎样。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「必须显式列出每一项扣减」的第一层落点。
   */
  @GetMapping("/capabilities")
  public ProfitCalculatorCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> calculator.capabilities());
  }

  /**
   * 目标店铺（站点）列表：来自 2.1.1.1 平台目录。
   *
   * <p>🔴 上游不可达时返回 <b>503</b>（理由写「读不到上游」），不用空列表冒充「没有站点」。
   */
  @GetMapping("/sites")
  public List<SiteOption> sites(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "spu.read",
        a -> {
          try {
            return calculator.sites(auth);
          } catch (ChannelPlatformUnavailableException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
          }
        });
  }

  /**
   * 一次测算（不落库）。
   *
   * <p>🔴 扣减不全时**不返回**毛利率数字（{@code grossProfit} / {@code grossMarginRate} 为 null），
   * 只返回缺口清单与「剩余可覆盖缺失扣减的金额」。
   */
  @PostMapping("/calculate")
  public ProfitCalculation calculate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CalculateRequest request) {
    return call(auth, "spu.read", a -> calculator.calculate(request, auth));
  }

  /**
   * 保存测算方案（参数 + 手工假设 + 物流阶梯 + 成本窗口）。
   *
   * <p>方案是**租户内**的：分享链接仍然要求登录并落在分享者自己的租户上下文里。
   */
  @PostMapping("/scenarios")
  public ScenarioDetail saveScenario(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CalculateRequest request) {
    return call(auth, "spu.update", a -> calculator.saveScenario(a, request, auth));
  }

  /** 方案列表（分页）。 */
  @GetMapping("/scenarios")
  public ScenarioPage listScenarios(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a -> calculator.listScenarios(page == null ? 0 : page, size == null ? 20 : size));
  }

  /** 方案详情 = 方案本身 + 用它复算出来的结果。 */
  @GetMapping("/scenarios/{id}")
  public ScenarioDetail getScenario(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> calculator.getScenario(id, auth));
  }

  /** 删除方案（连同它的物流阶梯）。 */
  @DeleteMapping("/scenarios/{id}")
  public void deleteScenario(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    call(auth, "spu.update", a -> {
      calculator.deleteScenario(id);
      return null;
    });
  }
}
