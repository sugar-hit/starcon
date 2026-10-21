package com.starcon.channel;

import static com.starcon.channel.ListingMappingGapModels.*;

import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.springframework.web.bind.annotation.*;

/**
 * 2.4.2.3 映射缺失队列。
 *
 * <p>蓝图：「**职责**：平台商品已同步但未映射到内部 SKU 的记录，等待人工建立映射或创建新 SKU。
 * **输入**：{@code listing_variant} 无映射的记录；{@code listing_mapping_version} 的错误状态。
 * **输出**：待映射清单（平台商品、变体、店铺、价格）；操作入口：映射到已有 SKU / 新建 SKU / 标记忽略。
 * **关系**：上游 = 04 刊登与内容、09 订单（缺映射订单也进此队列）；下游 = 04 映射管理。
 * <b>不按名称猜 SKU</b>；映射建立后回填受影响订单，历史订单冻结当时映射版本。」
 *
 * <p>端点按蓝图模块号标注，方便对照 02-channel-and-shop.md。
 *
 * <p>🔴 权限沿用 identity 已有的 {@code channel.operation.*}（4.1.2.1 同一口径，**identity 零改动**）：
 * 读用 {@code channel.operation.read}，写用 {@code channel.operation.create}。
 * 租户**不来自请求头** —— 由已认证成员关系在 identity 侧解析，channel 只在事务里绑定
 * {@code app.current_tenant} 给 RLS 用。
 */
@RestController
@RequestMapping("/channel")
public class ListingMappingGapController {
  private final AccessService access;
  private final ListingMappingGapService service;

  public ListingMappingGapController(AccessService access, ListingMappingGapService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(
      String bearer, String permission, java.util.function.Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  private void callVoid(String bearer, String permission, Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, permission);
    access.run(
        actor,
        permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  // ---- 2.4.2.3 待映射清单 ----

  /**
   * 待映射清单（真分页 + 六个筛选维度：店铺 / 平台 / 变体关键词 / 价格区间 / 状态 / 来源）。
   *
   * <p>🔴 价格区间是**真 SQL**（{@code price >= ?} / {@code price <= ?}），精确筛「有平台价快照
   * 且落在区间内」的行。本批次平台商品来源没有价格快照（05 定价未交付），
   * 能力声明里 {@code priceAvailable=false} 会把这件事写明。
   */
  @GetMapping("/mapping-gaps")
  public MappingGapPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String source,
      @RequestParam(required = false) String gapReason,
      @RequestParam(required = false) BigDecimal minPrice,
      @RequestParam(required = false) BigDecimal maxPrice,
      @RequestParam(required = false) String sort,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.list(
                shopCode, platformCode, search, status, source, gapReason, minPrice, maxPrice, sort,
                page, size));
  }

  /** 队列概览：状态 / 原因 / 店铺分布 + 最老的一条 + 价格快照条数。 */
  @GetMapping("/mapping-gaps/summary")
  public MappingGapSummary summary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.summary());
  }

  /** 筛选面板词表（不硬编码在前端）。 */
  @GetMapping("/mapping-gaps/filter-options")
  public MappingGapFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  /** 能力声明：未交付段 available=false + ownerModule + 影响。 */
  @GetMapping("/mapping-gaps/capabilities")
  public MappingGapCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 缺口详情：清单行 + 建档请求 + 订单回填的如实说明。 */
  @GetMapping("/mapping-gaps/{id}")
  public MappingGapDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "channel.operation.read", a -> service.detail(id));
  }

  /**
   * 操作一：映射到已有 SKU。
   *
   * <p>🔴 {@code internalSkuId} 必须由人明确给出 —— 本端点**不做任何自动匹配**
   * （蓝图加粗句「不按名称猜 SKU」）。
   */
  @PostMapping("/mapping-gaps/{id}/map")
  public MappingGapActionResult map(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MappingGapMapRequest input) {
    return call(auth, "channel.operation.create", a -> service.mapToExistingSku(a, id, input));
  }

  /**
   * 操作二：新建 SKU。
   *
   * <p>🔴 只**登记意图**（落库、可查、可撤）。03 侧建档尚无接线 ⇒ 请求停在 PENDING，
   * **不假装 SKU 已创建**。
   */
  @PostMapping("/mapping-gaps/{id}/create-sku")
  public MappingGapSkuRequestResult createSku(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MappingGapCreateSkuRequest input) {
    return call(auth, "channel.operation.create", a -> service.createSku(a, id, input));
  }

  /** 操作三：标记忽略（原因必填）。 */
  @PostMapping("/mapping-gaps/{id}/ignore")
  public MappingGapActionResult ignore(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody MappingGapIgnoreRequest input) {
    return call(auth, "channel.operation.create", a -> service.ignore(a, id, input));
  }

  /**
   * 订单来源的**接入点**（事件入口）。
   *
   * <p>🔴 当前 trade 侧**没有推**这条事件 —— 本端点是接入点本身（字段与校验都是真的），
   * 但**尚未有生产者**；能力声明里 {@code orderSourceWired=false} 说清"还没接"。
   */
  @PostMapping("/mapping-gaps/orders")
  public MappingGapOrderAccepted acceptOrder(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody MappingGapOrderRequest input) {
    return call(auth, "channel.operation.create", a -> service.acceptOrderGap(input));
  }
}
