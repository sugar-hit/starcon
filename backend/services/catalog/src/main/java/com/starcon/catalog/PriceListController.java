package com.starcon.catalog;

import static com.starcon.catalog.PriceListModels.*;

import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.1.1.1 价目表列表。
 *
 * <p>命名空间 {@code /catalog/price-lists}。页面路由 {@code /pricing/lists}。
 *
 * <p>权限沿用商品主数据域：读 {@code catalog.spu.read}，写 {@code catalog.spu.update}。
 * 不新造权限词表 —— 新词表要改 identity 服务的迁移，那是另一个服务的写入所有权。
 *
 * <p><b>租户不来自任何请求头</b>：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 客户端伪造的 {@code X-Tenant-Id} 进不来也改不动它。
 *
 * <p><b>🔴 一价目一币种</b>：{@code POST /lists} 的入参只有**一个**币种字段。
 * 混币种在 DB 层被命名约束 {@code price_list_one_currency} 挡住。
 */
@RestController
@RequestMapping("/catalog/price-lists")
public class PriceListController {

  private final AccessService access;
  private final PriceListService priceLists;

  public PriceListController(AccessService access, PriceListService priceLists) {
    this.access = access;
    this.priceLists = priceLists;
  }

  /** 统一入口：先向 identity 确认身份与权限（租户**不**来自任何客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "catalog." + permission);
    return access.run(actor, "catalog." + permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：三类适用范围 + 七条口径说明（含「一价目一币种」与「5.1.2.1 是下游守门员」）。
   */
  @GetMapping("/capabilities")
  public PriceListCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> priceLists.capabilities());
  }

  /**
   * 适用范围目录：法人（identity）+ 店铺与站点（channel）。
   *
   * <p>🔴 上游不可达时返回 <b>503</b>（理由写「读不到上游」），不用空列表冒充「没有法人 / 没有店铺」。
   */
  @GetMapping("/scope-directory")
  public ScopeDirectory scopeDirectory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "spu.read",
        a -> {
          try {
            return priceLists.scopeDirectory(auth);
          } catch (PriceListScopePort.ScopeUnavailableException
              | ChannelPlatformPort.ChannelPlatformUnavailableException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
          }
        });
  }

  /** 价目表台账：真分页 + 关键词 / 币种 / 适用范围 / 状态 / 此刻是否生效筛选。 */
  @GetMapping("/lists")
  public PriceListPage lists(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String currency,
      @RequestParam(required = false) String scopeType,
      @RequestParam(required = false) String scopeRef,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false) Boolean effectiveNow,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        "spu.read",
        a ->
            priceLists.list(
                new PriceListService.PriceListQuery(
                    keyword,
                    currency,
                    scopeType == null ? null : ScopeType.parse(scopeType),
                    scopeRef,
                    enabled,
                    effectiveNow,
                    page == null ? 0 : page,
                    size == null ? 20 : size)));
  }

  /** 覆盖范围摘要（蓝图输出句：「价目表记录 + 覆盖范围摘要」）。 */
  @GetMapping("/coverage")
  public CoverageOverview coverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "spu.read", a -> priceLists.coverage());
  }

  /** 价目表详情 = 表头 + 适用范围 + 覆盖范围摘要。 */
  @GetMapping("/lists/{priceListId}")
  public PriceListDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId) {
    return call(auth, "spu.read", a -> priceLists.detail(priceListId));
  }

  /** 新建价目表。 */
  @PostMapping("/lists")
  public PriceListDetail create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody PriceListInput input) {
    return call(auth, "spu.update", a -> priceLists.save(a, null, input, auth));
  }

  /** 修改价目表（乐观锁：版本对不上时 409）。适用范围整体替换。 */
  @PutMapping("/lists/{priceListId}")
  public PriceListDetail update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId,
      @Valid @RequestBody PriceListInput input) {
    return call(auth, "spu.update", a -> priceLists.save(a, priceListId, input, auth));
  }

  /** 删除价目表（表里还有价目项时 409）。 */
  @DeleteMapping("/lists/{priceListId}")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID priceListId) {
    call(
        auth,
        "spu.update",
        a -> {
          priceLists.delete(priceListId);
          return null;
        });
  }
}
