package com.starcon.catalog;

import static com.starcon.catalog.BatchPriceAdjustmentModels.*;

import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentDetail;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentExecuteRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentItemPage;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentPage;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentPreview;
import com.starcon.catalog.BatchPriceAdjustmentModels.BatchPriceAdjustmentRequest;
import com.starcon.catalog.BatchPriceAdjustmentModels.ItemQuery;
import com.starcon.catalog.ChannelPlatformPort.ChannelPlatformUnavailableException;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.1.2 批量调价。
 *
 * <p>命名空间 {@code /catalog/batch-price-adjustments}：页面路由 {@code /pricing/adjust/batch}。
 * 与 5.4.1.1 调价单**不共用端点** —— 5.4.1.1 未交付，本模块也不替它开形状。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 */
@RestController
@RequestMapping("/catalog/batch-price-adjustments")
public class BatchPriceAdjustmentController {

  private final AccessService access;
  private final BatchPriceAdjustmentService adjustments;

  public BatchPriceAdjustmentController(
      AccessService access, BatchPriceAdjustmentService adjustments) {
    this.access = access;
    this.adjustments = adjustments;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：listing 的定义、售价来源、上游真实交付状态、预览闸、反向指标、舍入与上限。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「**必须先看预览再执行**」意味着运营会拿预览里的
   * 数字去决定「这批到底改不改」，所以口径必须摆在数字旁边。
   */
  @GetMapping("/capabilities")
  public BatchPriceAdjustmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> adjustments.capabilities());
  }

  /**
   * 目标店铺（站点）列表：来自 2.1.1.1 平台目录。
   *
   * <p>🔴 上游不可达时返回 <b>503</b>，不用空列表冒充「没有店铺」。
   */
  @GetMapping("/sites")
  public java.util.List<ProfitCalculatorModels.SiteOption> sites(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "spu.read",
        a -> {
          try {
            return adjustments.sites(auth);
          } catch (ChannelPlatformUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
          }
        });
  }

  /**
   * 影响预览（**无副作用**）：算出影响范围与反向指标，并给出一个预览令牌。
   *
   * <p>🔴 预览不落库。它跑在一个**注定回滚**的事务里 ——
   * 「按模板」方式必须调用 5.3.2.1 的 apply()（它会写应用留痕），
   * 而预览阶段留下「模板被应用过」的留痕是假事实。
   *
   * <p>🔴 所以这个端点**不**走 {@link #call}（那会开一个外层事务）：
   * 预览要自己开事务、自己绑租户上下文、自己回滚。
   * 权限检查照旧（租户**不**来自任何客户端头，只来自已认证成员关系）。
   */
  @PostMapping("/preview")
  public BatchPriceAdjustmentPreview preview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BatchPriceAdjustmentRequest request) {
    var actor = access.require(auth, "catalog.spu.update");
    return adjustments.preview(actor, request, auth);
  }

  /**
   * 执行批量调价：重算预览 → 比对预览令牌 → 落库（调价单 + 明细 + 预览报告快照）。
   *
   * <p>🔴 令牌对不上 ⇒ 409（预览之后目标集合或当前价变了），
   * 绝不照着过期的数字改价。
   */
  @PostMapping
  public BatchPriceAdjustmentDetail execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody BatchPriceAdjustmentExecuteRequest request) {
    return call(auth, "spu.update", a -> adjustments.execute(a, request, auth));
  }

  /** 调价单列表（真分页）。 */
  @GetMapping
  public BatchPriceAdjustmentPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a -> adjustments.listAdjustments(page == null ? 0 : page, size == null ? 20 : size));
  }

  /** 调价单详情（= 调价单 + 预览报告快照）。 */
  @GetMapping("/{id}")
  public BatchPriceAdjustmentDetail get(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> adjustments.getAdjustment(id));
  }

  /** 明细列表（真分页 + 真筛选 + 真排序）。 */
  @GetMapping("/{id}/items")
  public BatchPriceAdjustmentItemPage items(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String loss,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    var query =
        new ItemQuery(
            status,
            loss,
            keyword,
            sort,
            page == null ? 0 : page,
            size == null ? 20 : size);
    return call(auth, "spu.read", a -> adjustments.listItems(id, query));
  }
}
