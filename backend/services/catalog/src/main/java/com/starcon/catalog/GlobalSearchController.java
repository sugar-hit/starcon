package com.starcon.catalog;

import static com.starcon.catalog.GlobalSearchModels.*;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.2.1.1 全局搜索框 —— catalog 侧的只读检索端点。
 *
 * <p>命名空间 {@code /catalog/global-search}：本服务已有的商品端点都在 {@code /catalog/...} 之下，
 * 检索是**另一类**动作（跨实体前缀查找），单独占一个子资源比混进 {@code /catalog/products}
 * 更容易看出「它不写任何东西」。
 *
 * <p><b>权限</b>：入口权限码是 {@code identity.global_search.read}（「谁能用全局搜索」是一个
 * 独立决定，而不是「恰好拥有某个域的读权限就能搜」）。域权限（{@code catalog.sku.read} /
 * {@code catalog.spu.read}）决定**哪些域会返回**；没有权限的域进 {@code unavailableDomains}，
 * 而不是让整个搜索 403 —— 一个没有仓库读权限的运营仍然应该能搜订单与商品。
 *
 * <p><b>租户绝不来自请求头</b>：先向 identity 确认已认证成员关系，再逐域开事务绑定
 * {@code app.current_tenant}，由 RLS 在数据库层强制隔离。伪造 {@code X-Tenant-Id} 头改不动它。
 *
 * <p><b>本控制器刻意不提供任何写端点</b>：蓝图的「工作台只读 + 跳转」是硬约束，
 * 一切状态变更回到归属系统完成。
 */
@RestController
@RequestMapping("/catalog/global-search")
public class GlobalSearchController {

  /** 入口权限码：全局搜索是一个独立能力，单独授权（identity 侧在 V330 注册）。 */
  static final String SEARCH = "identity.global_search.read";

  private final AccessService access;
  private final GlobalSearchService search;

  public GlobalSearchController(AccessService access, GlobalSearchService search) {
    this.access = access;
    this.search = search;
  }

  /**
   * 跨商品与往来单位主数据的检索。
   *
   * @param q 关键词（至少 2 个字符，后端强制）
   * @param limit 单域最多返回多少条（上限 50）
   */
  @GetMapping
  public GlobalSearchResponse search(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(name = "q", defaultValue = "") String q,
      @RequestParam(defaultValue = "20") int limit) {
    var actor = access.require(auth, SEARCH);
    return search.search(actor, q, limit);
  }

  /** 能力声明：哪些域接通了、依据是哪张表哪一列、哪些域拿不到及其归属模块。 */
  @GetMapping("/capabilities")
  public GlobalSearchCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    access.require(auth, SEARCH);
    return search.capabilities();
  }
}
