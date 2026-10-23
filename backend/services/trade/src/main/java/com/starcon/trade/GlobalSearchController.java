package com.starcon.trade;

import static com.starcon.trade.GlobalSearchModels.*;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 1.2.1.1 全局搜索框 —— trade 侧的只读检索端点。
 *
 * <p>命名空间 {@code /trade/global-search}：与 {@code /trade/sales-orders/list}（9.1.1.1）、
 * {@code /trade/purchase-orders}（6.3.2.1）并列，是**另一个类别的动作**（跨实体前缀查找）。
 *
 * <p><b>权限</b>：入口权限码 {@code identity.global_search.read}；域权限
 * {@code trade.orders.read} / {@code trade.purchase.read} 决定哪些域会返回。
 * 没有某个域的权限时，该域进 {@code unavailableDomains} 而不是让整个搜索 403。
 *
 * <p><b>本控制器刻意不提供任何写端点</b>：工作台只读 + 跳转，一切状态变更回到归属系统完成。
 */
@RestController
@RequestMapping("/trade/global-search")
public class GlobalSearchController {

  /** 入口权限码：全局搜索是一个独立能力，单独授权。 */
  static final String SEARCH = "identity.global_search.read";

  private final AccessService access;
  private final GlobalSearchService search;

  public GlobalSearchController(AccessService access, GlobalSearchService search) {
    this.access = access;
    this.search = search;
  }

  /**
   * 销售订单 + 采购单的前缀检索。
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
