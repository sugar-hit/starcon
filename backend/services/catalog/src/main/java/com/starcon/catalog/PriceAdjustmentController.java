package com.starcon.catalog;

import static com.starcon.catalog.PriceAdjustmentModels.*;

import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentCurrentPrice;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentExpectedVersionRequest;
import com.starcon.catalog.PriceAdjustmentModels.OrderQuery;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentDetail;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentPage;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentRequest;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentWriteback;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentWritebackPage;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentRejectRequest;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentWritebackAckRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 5.4.1.1 调价单。
 *
 * <p>命名空间 {@code /catalog/price-adjustments}：页面路由 {@code /pricing/adjust}。
 * 与 5.4.1.2 批量调价**不共用端点**（那条是 {@code /catalog/batch-price-adjustments}）——
 * 一张调价单与一批批量调价的生命周期、审批口径、版本闸都不一样。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 */
@RestController
@RequestMapping("/catalog/price-adjustments")
public class PriceAdjustmentController {

  private final AccessService access;
  private final PriceAdjustmentService adjustments;

  public PriceAdjustmentController(AccessService access, PriceAdjustmentService adjustments) {
    this.access = access;
    this.adjustments = adjustments;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：listing 的定义、当前价来源、审批口径、状态机、版本闸、舍入与上下游。
   *
   * <p>页面上把它固定显示在顶部 —— 蓝图那句「**调价是受控动作**」意味着运营会拿这页的
   * 口径去决定「这张单要不要提、提了会不会被卡」，所以口径必须摆在数字旁边。
   */
  @GetMapping("/capabilities")
  public PriceAdjustmentCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> adjustments.capabilities());
  }

  /**
   * 当前价解析（填单之前先看旧价）。
   *
   * <p>🔴 取不到时 {@code price} 是 <b>null</b>（**不是 0**），{@code reason} 写明为什么。
   */
  @GetMapping("/current-price")
  public PriceAdjustmentCurrentPrice currentPrice(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID skuId,
      @RequestParam String platformCode,
      @RequestParam String siteCode,
      @RequestParam(required = false) String currency) {
    return call(
        auth,
        "spu.read",
        a -> adjustments.currentPrice(skuId, platformCode, siteCode, currency));
  }

  /** 创建调价单（**草稿**）。 */
  @PostMapping
  public PriceAdjustmentDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PriceAdjustmentRequest request) {
    return call(auth, "spu.update", a -> adjustments.create(a, request, auth));
  }

  /** 调价单台账（真分页 + 真筛选 + 真排序）。 */
  @GetMapping
  public PriceAdjustmentPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    var query =
        new OrderQuery(
            status,
            skuId,
            platformCode,
            siteCode,
            keyword,
            sort,
            page == null ? 0 : page,
            size == null ? 20 : size);
    return call(auth, "spu.read", a -> adjustments.list(query));
  }

  /**
   * 调价单详情。
   *
   * <p>🔴 读详情会**顺带拉一次**审批状态（拉取式同步）—— 审批人在 17 组织那边决定后，
   * 不必额外点一次刷新才能看到结论。拉不到时单子停在「待审」并把失败原因如实带回来。
   */
  @GetMapping("/{id}")
  public PriceAdjustmentDetail get(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.read", a -> adjustments.get(id, true, auth));
  }

  /**
   * 提交（草稿 → 待审 / 已批）。
   *
   * <p>🔴 版本对不上 ⇒ 409；提交时向 17 组织求值，命中规则 ⇒ 停在待审并挂上审批实例；
   * 免审 ⇒ 直接进已批。
   */
  @PostMapping("/{id}/submit")
  public PriceAdjustmentDetail submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PriceAdjustmentExpectedVersionRequest request) {
    return call(auth, "spu.update", a -> adjustments.submit(a, id, request, auth));
  }

  /** 执行（已批 → 已执行）：新价生效，并产出「待回写」事实给 5.4.2.1。 */
  @PostMapping("/{id}/execute")
  public PriceAdjustmentDetail execute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PriceAdjustmentExpectedVersionRequest request) {
    return call(auth, "spu.update", a -> adjustments.execute(a, id, request, auth));
  }

  /**
   * 驳回（草稿 / 待审 → 已驳回）。
   *
   * <p>🔴 待审且已有在办实例时**拒绝**：identity 那边没有撤销实例的端点，
   * 本地单方面撤掉会让那边留下一条永远没人管的待办。
   */
  @PostMapping("/{id}/reject")
  public PriceAdjustmentDetail reject(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody PriceAdjustmentRejectRequest request) {
    return call(auth, "spu.update", a -> adjustments.reject(a, id, request, auth));
  }

  /** 刷新审批状态（**拉取式**同步：把 17 组织那边的结论取回来）。 */
  @PostMapping("/{id}/approval/refresh")
  public PriceAdjustmentDetail refreshApproval(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "spu.update", a -> adjustments.refreshApproval(a, id, auth));
  }

  /** 待回写事实列表（下游 5.4.2.1 消费它；真分页 + 真筛选）。 */
  @GetMapping("/writebacks")
  public PriceAdjustmentWritebackPage writebacks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID skuId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String siteCode,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            adjustments.listWritebacks(
                status, skuId, platformCode, siteCode, page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /** 待回写事实的回执（由下游 5.4.2.1 调用）。 */
  @PostMapping("/writebacks/{id}/ack")
  public PriceAdjustmentWriteback ack(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) PriceAdjustmentWritebackAckRequest request) {
    return call(auth, "spu.update", a -> adjustments.ackWriteback(id, request));
  }
}
