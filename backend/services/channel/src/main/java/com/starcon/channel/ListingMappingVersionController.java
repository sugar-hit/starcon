package com.starcon.channel;

import static com.starcon.channel.ListingMappingVersionModels.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 4.1.2.2 映射版本。
 *
 * <p>蓝图：「映射变更时生成新版本而非原地修改，保留每个版本对应的商品/订单范围，支持查看某订单使用的是哪个版本、按版本回溯受影响订单。输入：映射变更；生效时间点；变更原因。输出：<b>版本列表 + 受影响订单范围查询</b>。关系：上游 = 4.1.2.1；下游 = 09 订单（{@code order_line_component} 冻结）。<b>改映射不影响已产生的订单</b>，只对新订单生效——这是财务与售后追溯的前提。」
 *
 * <p>端点按蓝图模块号标注，方便对照 04-listing-and-content.md。四个入口各自对应职责句里的一个词：
 *
 * <ul>
 *   <li>{@code GET /} —— 蓝图输出句的「<b>版本列表</b>」。跨映射的真分页列表，
 *       生效区间筛选是<b>重叠</b>语义（一版只要在窗口内曾经生效过就该被列出来）。</li>
 *   <li>{@code GET /{id}} —— 「按版本回溯」。单版详情 + <b>与上一版的真比对</b>，
 *       回答「改了什么、什么时候改的、为什么改」。</li>
 *   <li>{@code GET /{id}/product-scope} —— 职责句的「<b>保留每个版本对应的商品范围</b>」。
 *       读 V182 的冻结快照，并同时给出平台侧当前值对照（漂移了要说出来）。</li>
 *   <li>{@code GET /{id}/affected-orders} —— 输出句的「<b>受影响订单范围查询</b>」。
 *       🔴 <b>刻意 available=false</b>：受影响订单是 09 订单（trade）的真实数据，
 *       trade 侧目前没有任何可供 channel 查询订单的已交付端点，
 *       {@code order_line_component} 的冻结也未落地。AGENTS.md 禁止跨服务 SQL / 直连对方库，
 *       所以这里<b>不造假数据、不返回空数组</b>（空数组会被读成"确实没有受影响订单"），
 *       只如实声明归属模块与变成可查的前置条件。</li>
 * </ul>
 *
 * <p>🔴 本控制器<b>全是读端点</b>：映射变更生成新版本是 4.1.2.1 的写入路径
 * （{@code POST /channel/listing-mappings}），本模块不提供任何能改写历史版本的入口 ——
 * 那正是「改映射不影响已产生的订单」在 API 层的样子。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.read}（与 4.1.2.1、4.1.1.1 同族），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/mapping-versions")
public class ListingMappingVersionController {

  private final AccessService access;
  private final ListingMappingVersionService service;

  public ListingMappingVersionController(AccessService access, ListingMappingVersionService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(
      String bearer, String permission, java.util.function.Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 能力声明：逐维度 available + ownerModule + 影响。
   *
   * <p>🔴 未交付段显式 {@code available=false}，不用空数组或 0 冒充「这一项没有数据」。
   */
  @GetMapping("/capabilities")
  public ListingMappingVersionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选面板的可选值：全部来自本模块的词表，不硬编码在前端。 */
  @GetMapping("/filter-options")
  public VersionFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  /**
   * 版本列表（蓝图输出句的「版本列表」）。
   *
   * <p>🔴 {@code windowFrom} / {@code windowTo} 是<b>生效区间重叠</b>筛选而不是"起点落在窗口内"：
   * 一版只要在窗口内曾经生效过就该被列出来，否则「查某个月的变更」会漏掉跨月仍在生效的那一版。
   */
  @GetMapping
  public VersionPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID mappingId,
      @RequestParam(required = false) UUID listingId,
      @RequestParam(required = false) UUID listingVariantId,
      @RequestParam(required = false) UUID internalSkuId,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String relationType,
      @RequestParam(required = false) String changeSource,
      @RequestParam(required = false) String effectiveState,
      @RequestParam(required = false) OffsetDateTime windowFrom,
      @RequestParam(required = false) OffsetDateTime windowTo,
      @RequestParam(required = false) String search,
      @RequestParam(required = false, defaultValue = "RECENT") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.list(
                mappingId, listingId, listingVariantId, internalSkuId, shopCode, relationType,
                changeSource, effectiveState, windowFrom, windowTo, search, sort, page, size));
  }

  /** 单版详情：版本 + 冻结的商品范围 + 与上一版的真比对（改了什么）。 */
  @GetMapping("/{id}")
  public MappingVersionDetail load(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.detail(id));
  }

  /**
   * 按版本回溯商品范围（职责句的「保留每个版本对应的商品范围」）。
   *
   * <p>🔴 读的是 V182 的<b>冻结快照</b>，不是现查 listing：listing 会被重新同步、也可以被删除，
   * 现查会让历史被后来的事实改写。响应同时给出平台侧当前值与 {@code driftedFromPlatform}，
   * 「当时叫什么」与「现在叫什么」两件事分开说。
   */
  @GetMapping("/{id}/product-scope")
  public VersionProductScope productScope(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.productScope(id));
  }

  /**
   * 受影响订单范围查询（输出句的后半）。
   *
   * <p>🔴 <b>available=false</b>：受影响订单属于 09 订单（trade），trade 侧目前没有任何可供
   * channel 查询订单的已交付端点，{@code order_line_component} 的冻结也未落地。
   * AGENTS.md 禁止跨服务 SQL / 直连对方库 / 共享视图，所以本端点<b>不跨服务读取</b>，
   * 也<b>不返回空数组</b> —— 空数组会被读成"这一版没有影响任何订单"，而真相是"查不了"。
   * {@code items} 因此恒为 {@code null}。
   *
   * <p>下游落地后本端点可直接作为它的读出口，版本列表与商品范围口径不需要改。
   */
  @GetMapping("/{id}/affected-orders")
  public VersionAffectedOrders affectedOrders(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.affectedOrders(id));
  }
}
