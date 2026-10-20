package com.starcon.catalog;

import static com.starcon.catalog.LandedCostModels.*;

import jakarta.validation.Valid;
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
 * 5.2.1.2 到岸成本。
 *
 * <p>命名空间 {@code /catalog/landed-costs}：与 5.2.1.1 成本构成、5.2.1.3 成本分摊
 * **共用页面路由 {@code /pricing/cost}**（前端按分区块组织），但端点各自独立 ——
 * 到岸成本是**单据级**（一批货的 CIF + 关税 + 到港），
 * 与 5.2.1.1 的「SKU 级单位成本构成」不同源，定价时必须选对口径。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 跨服务调用透传调用方自己的 Authorization 头</b>：头程与关税的唯一来源是
 * warehouse 的授权只读端点（AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图），
 * 本服务把**调用方自己的** bearer 原样带过去，租户由 warehouse 从成员关系解析 ——
 * 本服务**不**替对方决定租户，也**不**发明 {@code X-Tenant-Id} 之类的头。
 */
@RestController
@RequestMapping("/catalog/landed-costs")
public class LandedCostController {

  private final AccessService access;
  private final LandedCostService landed;

  public LandedCostController(AccessService access, LandedCostService landed) {
    this.access = access;
    this.landed = landed;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：六个分量各自的来源、能不能取到、归谁管；成本口径；未交付的依赖。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「到岸成本与仓库成本是两个口径」靠它落地。
   */
  @GetMapping("/capabilities")
  public LandedCostCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> landed.capabilities());
  }

  /**
   * 到岸成本单台账：分页 + 关键词 / 目的国 / 币种 / 状态 / 计算时间窗口筛选。
   *
   * <p>每一行都带**覆盖度**（算了六项里的几项、缺了哪几项）—— 覆盖范围不明的
   * 到岸成本不能用来算利润。
   */
  @GetMapping
  public LandedCostListPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String destinationCountryCode,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime computedFrom,
      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
          OffsetDateTime computedTo,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            landed.list(
                new LandedCostQuery(
                    keyword,
                    destinationCountryCode,
                    currency,
                    status,
                    computedFrom,
                    computedTo,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /**
   * 不同目的地的成本对比（蓝图输出之一）。
   *
   * <p>🔴 **必须指定一个 SKU**：不同 SKU 的单位成本不可比。没指定就 400，
   * 而不是给一个「所有 SKU 的平均到岸成本」—— 那个数没有意义。
   */
  @GetMapping("/comparisons")
  public LandedCostComparison comparisons(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID skuId) {
    return call(auth, "spu.read", a -> landed.comparison(skuId));
  }

  /** 租户维护的单件参数（清关费 / 尾程预备）。 */
  @GetMapping("/parameters")
  public LandedCostParameterPage parameters(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> landed.parameters());
  }

  /** 维护一个参数（新增或改，带版本号乐观锁）。 */
  @PutMapping("/parameters")
  public LandedCostParameterRow saveParameter(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LandedCostParameterInput input) {
    return call(auth, "spu.update", a -> landed.saveParameter(a, input));
  }

  /**
   * 到岸成本单明细：单头 + 逐 SKU 行（单位到岸成本）+ 每行六个分量。
   *
   * <p>🔴 上游读不到时返回 **503**（理由写「读不到上游」），不用空集合冒充「上游没有这笔成本」。
   */
  @GetMapping("/{statementId}")
  public LandedCostDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID statementId) {
    return call(auth, "spu.read", a -> landed.detail(statementId));
  }

  /** 逐项可审阅：这张单上的每一个分量（真分页，每一项都带来源单号）。 */
  @GetMapping("/{statementId}/components")
  public LandedCostComponentPage components(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID statementId,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a -> landed.components(statementId, page == null ? 0 : page, size == null ? 50 : size));
  }

  /**
   * 计算一张到岸成本单（按上游 10.4.1.1 头程发运单）。
   *
   * <p>🔴 **幂等**：一张发运单对应一张到岸成本单，重复提交是**重算**（覆盖同一张单，
   * {@code compute_count} 加一），不是再开一张。
   */
  @PostMapping
  public LandedCostComputed compute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody LandedCostComputeInput input) {
    return call(auth, "spu.update", a -> landed.compute(a, auth, input));
  }

  /** 重算一张到岸成本单（按它自己的来源发运单重新取数）。 */
  @PostMapping("/{statementId}/recompute")
  public LandedCostComputed recompute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID statementId) {
    return call(
        auth,
        "spu.update",
        a -> {
          var sourceShipmentId = landed.sourceShipmentId(statementId);
          return landed.compute(
              a, auth, new LandedCostComputeInput(sourceShipmentId, null));
        });
  }
}
