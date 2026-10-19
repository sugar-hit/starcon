package com.starcon.trade;

import static com.starcon.trade.ProductIssueAttributionModels.*;

import java.util.UUID;
import java.util.function.Function;
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
 * 11.1.2.2 商品问题归因。
 *
 * <p>蓝图 11-aftersales-and-returns.md：「把质量问题归因到具体环节（生产、运输、仓储、描述），
 * 支持批次追溯与照片比对，形成对供应商或流程的改进建议。」
 * 输出：「<b>归因结论 + 批次质量标记</b>。」
 * 加粗句：「<b>批次归因是「同一个批次退回来 10 件」和「10 个批次各退 1 件」的区别</b>，
 * 前者是供应商责任，后者可能是运输或买家因素。」
 *
 * <p><b>命名空间独立于 {@code /trade/return-quality}</b>：本模块的产物是<b>另一种单据</b>
 * （归因结论 = 一个责任判定 + 一张批次质量标记），不是质检单的另一个视图。
 * 11.2.3.1 判「这批货怎么样」，本模块判「这事该谁负责」—— 两者的输出句不同，形状就不能混。
 *
 * <p><b>权限码沿用</b> {@code trade.aftersales.read} / {@code trade.aftersales.write}：
 * 本模块是 11.1.2 锚点下的售后原因侧动作，与售后单同一业务面。
 *
 * <p><b>刻意不提供</b>：索赔发起（6.4.2.2）、评级写入（6.1.1.3）、质量报表（11.5.2.1），
 * 三者<b>均未交付</b>，capabilities 逐条报 delivered=false 与归属模块。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 本模块的端点大多是<b>按 id 直达</b>的 URL —— 拿别的租户的归因单 ID 来读，
 * 得到的是与「不存在」<b>完全相同</b>的错误（不告诉调用方「这个 ID 存在但不属于你」）。
 */
@RestController
@RequestMapping("/trade/product-attribution")
public class ProductIssueAttributionController {

  private static final String READ = "aftersales.read";
  private static final String WRITE = "aftersales.write";

  private final AccessService access;
  private final ProductIssueAttributionService attributions;

  public ProductIssueAttributionController(
      AccessService access, ProductIssueAttributionService attributions) {
    this.access = access;
    this.attributions = attributions;
  }

  /** 统一读入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号、
   * 本模块<b>刻意不做</b>的事、环节六值与照片比对口径。
   */
  @GetMapping("/capabilities")
  public ProductAttributionCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> attributions.capabilities());
  }

  // ================================================================ 归因单

  /** 归因单列表（真分页 + 多维筛选 + 排序白名单 + 跨全表汇总）。 */
  @GetMapping("/attributions")
  public AttributionPage attributions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String stage,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String lotId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String sort) {
    return call(
        auth,
        actor ->
            attributions.listAttributions(
                new ProductIssueAttributionService.AttributionListQuery(
                    page == null ? 0 : page,
                    size == null ? 20 : size,
                    status,
                    stage,
                    skuCode,
                    lotId,
                    keyword,
                    sort)));
  }

  /** 单张归因单全景（头 + 照片 + 比对 + 批次标记 + 留痕 + 当前可执行动作）。 */
  @GetMapping("/attributions/{id}")
  public AttributionDetail attribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, actor -> attributions.detail(id));
  }

  /**
   * 建归因单（对一张售后单）。
   *
   * <p>⚠️ <b>不传</b> status / attributedStage / attributedAt：建单恒为草稿，
   * 归因只走 {@code /attribute} 一个入口。
   * 批次（{@code lotId}）会在建单那一刻<b>经 warehouse 的授权端点真查一次</b>并写快照 ——
   * 批次事实的写入所有者是 warehouse，本服务只经 HTTP 读（AGENTS.md 模块边界纪律）。
   */
  @PostMapping("/attributions")
  public AttributionDetail createAttribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AttributionCreate request) {
    return write(
        auth,
        actor ->
            attributions.create(
                request, actor.userId(), auth /* 转发调用方自己的 bearer 读 warehouse */));
  }

  /** 登记 / 替换照片（整单替换）。只接<b>对象键 + 内容指纹</b>，不接二进制。 */
  @PutMapping("/attributions/{id}/photos")
  public AttributionDetail photos(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PhotoRegisterRequest request) {
    return write(auth, actor -> attributions.registerPhotos(id, request, actor.userId()));
  }

  /**
   * 照片比对（<b>内容指纹</b>口径）。
   *
   * <p>⚠️ 请求体里**没有** {@code compareResult}：结果由服务层按两张照片的 sha256
   * 真比对得出，再由 DB 触发器独立比对一次。本仓不做图像相似度。
   */
  @PostMapping("/attributions/{id}/photos/compare")
  public AttributionDetail compare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody PhotoCompareRequest request) {
    return write(auth, actor -> attributions.comparePhotos(id, request, actor.userId()));
  }

  /**
   * 出归因结论（唯一的状态流转入口）。
   *
   * <p>⚠️ <b>环节 + 理由 + 改进建议三者缺一不可</b>：职责句明写「形成对供应商或流程的改进建议」，
   * DB 层 CHECK 再拒一次。归因到「生产」必须挂批次。
   */
  @PostMapping("/attributions/{id}/attribute")
  public AttributionDetail attribute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AttributionConclude request) {
    return write(auth, actor -> attributions.attribute(id, request, actor.userId()));
  }

  /** 改判（终态不可逆：原单标记 SUPERSEDED 留痕，另起一版直接归因）。 */
  @PostMapping("/attributions/{id}/supersede")
  public AttributionDetail supersede(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AttributionSupersede request) {
    return write(auth, actor -> attributions.supersede(id, request, actor.userId()));
  }

  // ================================================================ 批次质量标记

  /**
   * 批次质量标记列表（输出句第二样）。
   *
   * <p>⚠️ 本表在 <b>trade 库</b>：它是 trade 侧基于归因事实的<b>统计结论</b>，
   * 与 warehouse 的 inventory_lot（记「这个批次有多少货」）是<b>不同的东西</b>。
   * 本模块只经 warehouse 授权 HTTP 端点读批次档案，<b>绝不写对方的表</b>。
   */
  @GetMapping("/lot-flags")
  public LotFlagPage lotFlags(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String flagStatus,
      @RequestParam(required = false) String skuCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Integer page,
      @RequestParam(required = false) Integer size) {
    return call(
        auth,
        actor ->
            attributions.listLotFlags(
                flagStatus,
                skuCode,
                keyword,
                page == null ? 0 : page,
                size == null ? 20 : size));
  }

  /** 单个批次的质量标记（按批次读，跨租户在 RLS 下查不到 → 404）。 */
  @GetMapping("/lot-flags/by-lot")
  public LotFlagRow lotFlagByLot(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID lotId) {
    return call(auth, actor -> attributions.lotFlag(lotId));
  }

  /**
   * 刷新批次质量标记。
   *
   * <p>⚠️ 请求体里**没有** suspectReturnCount / affectedReturnQty / stageBreakdown：
   * 这三列是统计结论，由服务层聚合 + DB 触发器重算比对（V180 守卫⑤），传输层根本收不到。
   */
  @PostMapping("/attributions/{id}/lot-flag/refresh")
  public LotFlagRow refreshLotFlag(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody(required = false) LotFlagRefresh request) {
    return write(
        auth,
        actor ->
            attributions.refreshLotFlag(
                id,
                request == null ? null : request.flagNote(),
                request == null ? null : request.flagStatus(),
                actor.userId()));
  }
}
