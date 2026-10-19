package com.starcon.channel;

import static com.starcon.channel.ListingBundleComponentModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 4.1.2.3 套装组件映射。
 *
 * <p>蓝图：「当平台商品对应内部虚拟套装时，维护组件 SKU 与数量的映射，支持平台变体与不同套装版本对应，并校验组件 SKU 在目标仓库可售。输入：组件映射维护；套装版本选择。输出：<b>组件映射记录 + 可售性校验结果</b>。关系：上游 = 3.5.1 套装版本、4.1.2.1；下游 = 07 库存（按组件预占）。<b>组件数量必须大于 0</b>，缺任一组件时整个套装不可售。」
 *
 * <p>本类按职责句逐项落点：
 *
 * <ul>
 *   <li><b>「维护组件 SKU 与数量的映射」</b> → {@link #saveComponent}（写入，逐条校验）+ {@link #components}（读出）
 *       + V186 的 {@code listing_bundle_component}。数量恒 &gt; 0 由 <b>CHECK + 触发器</b>双重保证。</li>
 *   <li><b>「支持平台变体与不同套装版本对应」</b> → {@link #saveBinding} + V186 的
 *       {@code listing_bundle_binding}：换版生成<b>新的一行</b>（生效区间），不就地改 ——
 *       与 4.1.2.2 的 {@code listing_mapping_version} 同一套纪律。「至多一个当前生效」由
 *       部分唯一索引挡，并发下也成立。</li>
 *   <li><b>「校验组件 SKU 在目标仓库可售」</b> → {@link #sellableCheck}，数据<b>只经</b>
 *       warehouse 的授权 API（{@link SellableQuantityQueryPort}）读，本类一条 SQL 都不碰对方库。</li>
 *   <li><b>🔴「组件数量必须大于 0」</b> → 服务层先给可读原因，DB 的 CHECK 与
 *       {@code guard_listing_bundle_component_quantity} 再兜一道。</li>
 *   <li><b>🔴「缺任一组件时整个套装不可售」</b> → {@link ListingBundleSellableCheck#bundleSellable()}：
 *       组件数为 0、或<b>任一</b>组件可售量不足整套用量，整套即不可售。逐行看每个组件都还有货，
 *       但只要有一个不够，这一整套就卖不出去 —— 所以这个判定必须是<b>整体</b>的。</li>
 * </ul>
 *
 * <p>🔴 <b>「查不到」与「确实不可售」必须分得开</b>：{@link SellableQuantityQueryPort} 在上游不可达时
 * 抛 503 并原样透出，<b>本类不吞掉、也不降级成「全部可售」</b>。返回「可售」会让运营把
 * 「我们查不到」读成「能卖」，而那正是会真的超卖的路径。
 *
 * <p>🔴 <b>数据来源纪律</b>：平台侧（listing / variant / shop / mapping）与组件映射<b>同库同服务</b>读；
 * catalog 的 {@code bundleVersionId} / {@code componentSkuId} 与 warehouse 的可售量都是<b>跨服务</b>，
 * 前者只存逻辑 ID、写入时经授权 API 校验构成归属，后者<b>只经 API 读</b>、不落任何一列
 * （V186 第一节）。
 */
@Service
public class ListingBundleComponentService {

  /** 单页上限：藏在代码里就等于没告诉调用方。 */
  static final int MAX_PAGE_SIZE = 200;

  static final int DEFAULT_PAGE_SIZE = 50;

  /** 排序取值 —— 前端只翻译 query string，排序全在服务端。 */
  static final List<String> SORTS = List.of("RECENT", "EFFECTIVE", "OLDEST", "MAPPING");

  private static final String TENANT_FILTER =
      " where {alias}.tenant_id = nullif(current_setting('app.current_tenant',true),'')::uuid";

  private static final String SELLABLE_NOTE =
      "可售量口径来自 07 库存（warehouse）：合格可售 + 待检 + 残次 + 未上架/不可发货 + 批次冻结 = available，"
          + "再扣安全缓冲得到可售。本模块原样采用该口径，不自行重算 —— 两处各算一次必然漂移，"
          + "而漂移的方向一定是「这里说能卖、扣的时候不够」。AGENTS.md 禁止跨服务 SQL，因此可售量不落本地任何一列。";

  /**
   * 绑定行的主查询骨架（列表与详情共用）。
   *
   * <p>🔴 用 {@code inner join} 映射与 listing：{@code listing_bundle_binding} 的复合 FK 已经
   * 保证「映射存在且同租户」，而映射的 FK 又保证「listing 存在」。所以 join 落空是数据完整性事故，
   * {@code left join} 只会把它变成界面上一个空白格子。
   */
  private static final String BINDING_COLUMNS =
      """
      select b.id, b.mapping_id, b.bundle_version_id, b.effective_from, b.effective_to,
             b.change_reason, b.actor_id, b.created_at,
             m.listing_id, m.listing_variant_id,
             coalesce(s.code,'') as shop_code, l.external_item_id, v.external_variant_id,
             (select count(*)    from listing_bundle_component c
               where c.tenant_id=b.tenant_id and c.binding_id=b.id) as component_count,
             (select coalesce(sum(c.quantity),0) from listing_bundle_component c
               where c.tenant_id=b.tenant_id and c.binding_id=b.id) as total_quantity
      from listing_bundle_binding b
      join listing_mapping m on m.tenant_id = b.tenant_id and m.id = b.mapping_id
      join listing        l on l.tenant_id = m.tenant_id and l.id = m.listing_id
      join listing_variant v on v.tenant_id = m.tenant_id and v.id = m.listing_variant_id
      left join shop      s on s.tenant_id = l.tenant_id and s.id = l.shop_id
      """;

  private final DSLContext sql;
  private final BundleVersionQueryPort bundles;
  private final SellableQuantityQueryPort sellable;

  public ListingBundleComponentService(
      DSLContext sql, BundleVersionQueryPort bundles, SellableQuantityQueryPort sellable) {
    this.sql = sql;
    this.bundles = bundles;
    this.sellable = sellable;
  }

  // ==================================================================
  // 能力声明 / 筛选项
  // ==================================================================

  public ListingBundleComponentCapabilities capabilities() {
    return new ListingBundleComponentCapabilities(
        List.of(
            new ListingBundleCapabilityDimension(
                "component_maintenance",
                "🔴 维护组件 SKU 与数量的映射（逐条写入，数量恒 > 0）",
                true,
                "listing_bundle_component（V186）",
                "",
                "套装只知道「内部是一组 SKU」，刊登侧不知道每卖 1 件要扣几件，07 库存无法按组件预占"),
            new ListingBundleCapabilityDimension(
                "bundle_version_binding",
                "🔴 套装版本选择：平台变体与不同套装版本对应（换版生成新绑定，不就地改）",
                true,
                "listing_bundle_binding（V186）",
                "",
                "内部套装升版后，平台侧无法跟着换版，「扣减口径不变」这句话就没有载体"),
            new ListingBundleCapabilityDimension(
                "component_list",
                "组件映射记录（逐组件 SKU 与数量，可分页筛选）",
                true,
                "listing_bundle_component（V186）",
                "",
                "只能逐条点开猜这一版配了什么"),
            new ListingBundleCapabilityDimension(
                "quantity_positive",
                "🔴 组件数量必须大于 0（DB CHECK + 触发器双重保证，写不进 0 或负数）",
                true,
                "listing_bundle_component（V186）",
                "",
                "数量为 0 的组件会让 07 库存扣出一条 0 扣减，两边都答不出「这个组件到底要不要」"),
            new ListingBundleCapabilityDimension(
                "sellable_check",
                "🔴 校验组件 SKU 在目标仓库可售（缺任一组件 ⇒ 整套不可售）",
                true,
                "warehouse · GET /warehouse/inventory/sellable/skus",
                "",
                "组件全部有货才算能卖这件事没有判定位，运营只能自己按单个 SKU 逐个查再心算"),
            new ListingBundleCapabilityDimension(
                "component_identity",
                "组件 SKU 构成归属校验（写入时经 catalog 授权 API 核对该 SKU 确属这一版）",
                true,
                "catalog · GET /catalog/versions/{id}/components",
                "",
                "一个手滑的 uuid 能写进组件表，而 07 库存会照着它扣一个与套装无关的 SKU —— 扣得动，所以更危险"),
            new ListingBundleCapabilityDimension(
                "component_snapshot",
                "「当时这一版由哪些 SKU 组成」的构成冻结快照",
                false,
                "—",
                "09 订单履约（trade 服务 · order_line_component 冻结）",
                "组件构成的历史只能现查 catalog，而 catalog 的套装版本是可以改草稿的；"
                    + "订单冻结要的是「这单当时按哪一版展开」的那一份，本模块**不复制一份构成快照**"
                    + "（复制就会出现两个真相）"),
            new ListingBundleCapabilityDimension(
                "inventory_reservation",
                "按组件预占库存（下游写回）",
                false,
                "—",
                "07 库存（warehouse 服务 · 按组件预占）",
                "本模块只<b>读</b>可售量做校验，不发起预占：预占是 07 库存自己的写路径，"
                    + "由它按本模块的组件口径执行，避免两个服务各写一份预占")
        ),
        SORTS,
        List.of("CURRENT", "RETIRED"),
        "构成冻结快照与按组件预占两段未交付：前者属于 09 订单侧 order_line_component 的冻结职责，"
            + "后者是 07 库存自己的写路径。本模块交付的是「刊登侧记什么、扣几件」与「能不能卖」，"
            + "其余维度全部已交付。",
        "上游 = 3.5.1 套装版本（catalog，写入时经授权 API 校验构成归属）、"
            + "4.1.2.1 映射管理（listing_bundle_binding 的 mapping_id 指向它）；"
            + "下游 = 07 库存（可售量只经授权 API 读，按组件预占由 warehouse 自己执行）。"
            + "构成的真值永远在 catalog：同一套组件在 3.5.1 里是产品定义，在本模块里是刊登侧的扣减口径，"
            + "「平台侧 1 件 = 内部几件」这件事两家可以不同，那正是本表存在的理由。");
  }

  public ListingBundleFilterOptions filterOptions() {
    return new ListingBundleFilterOptions(List.of("CURRENT", "RETIRED"), SORTS, MAX_PAGE_SIZE);
  }

  // ==================================================================
  // 绑定列表（套装版本选择的读出口）
  // ==================================================================

  /** 绑定列表：真分页 + 筛选。 */
  public ListingBundleBindingPage listBindings(
      UUID mappingId,
      UUID listingId,
      UUID listingVariantId,
      UUID bundleVersionId,
      String shopCode,
      String effectiveState,
      String search,
      String sort,
      int page,
      int size) {
    String sortKey = sort == null || !SORTS.contains(sort) ? "RECENT" : sort;
    int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int pageNo = Math.max(page, 0);

    List<Object> binds = new ArrayList<>();
    String where =
        bindingFilterWhere(
            binds, mappingId, listingId, listingVariantId, bundleVersionId, shopCode, effectiveState,
            search);

    long total = count("select count(*) from listing_bundle_binding b"
        + " join listing_mapping m on m.tenant_id = b.tenant_id and m.id = b.mapping_id"
        + " join listing        l on l.tenant_id = m.tenant_id and l.id = m.listing_id"
        + " join listing_variant v on v.tenant_id = m.tenant_id and v.id = m.listing_variant_id"
        + " left join shop      s on s.tenant_id = l.tenant_id and s.id = l.shop_id"
        + where, binds.toArray());
    if (total == 0) return new ListingBundleBindingPage(List.of(), 0, pageNo, pageSize, 0);

    String orderBy =
        switch (sortKey) {
          case "EFFECTIVE" -> " order by b.effective_from desc, b.id desc";
          case "OLDEST" -> " order by b.effective_from asc, b.id asc";
          case "MAPPING" -> " order by b.mapping_id, b.effective_from desc, b.id desc";
          default -> " order by b.created_at desc, b.id desc";
        };

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(pageSize);
    pageBinds.add(pageNo * pageSize);
    List<ListingBundleBindingRow> items =
        sql.fetch(BINDING_COLUMNS + where + orderBy + " limit ? offset ?", pageBinds.toArray())
            .stream()
            .map(ListingBundleComponentService::bindingRow)
            .toList();

    int currentInPage = (int) items.stream().filter(ListingBundleBindingRow::current).count();
    return new ListingBundleBindingPage(items, total, pageNo, pageSize, currentInPage);
  }

  private String bindingFilterWhere(
      List<Object> binds,
      UUID mappingId,
      UUID listingId,
      UUID listingVariantId,
      UUID bundleVersionId,
      String shopCode,
      String effectiveState,
      String search) {
    StringBuilder where = new StringBuilder(" where b.tenant_id = ?");
    binds.add(currentTenant());

    if (mappingId != null) {
      where.append(" and b.mapping_id = ?");
      binds.add(mappingId);
    }
    if (listingId != null) {
      where.append(" and m.listing_id = ?");
      binds.add(listingId);
    }
    if (listingVariantId != null) {
      where.append(" and m.listing_variant_id = ?");
      binds.add(listingVariantId);
    }
    if (bundleVersionId != null) {
      where.append(" and b.bundle_version_id = ?");
      binds.add(bundleVersionId);
    }
    if (shopCode != null && !shopCode.isBlank()) {
      where.append(" and s.code = ?");
      binds.add(shopCode.trim());
    }
    if ("CURRENT".equals(effectiveState)) {
      where.append(" and b.effective_to is null");
    } else if ("RETIRED".equals(effectiveState)) {
      where.append(" and b.effective_to is not null");
    }
    if (search != null && !search.isBlank()) {
      String like = "%" + escape(search.trim()) + "%";
      where.append(
          " and (l.external_item_id like ? escape '!' or v.external_variant_id like ? escape '!'"
              + " or s.code like ? escape '!' or b.change_reason like ? escape '!'"
              + " or cast(b.bundle_version_id as text) like ? escape '!')");
      for (int i = 0; i < 5; i++) binds.add(like);
    }
    return where.toString();
  }

  // ==================================================================
  // 套装版本选择（写入）
  // ==================================================================

  /**
   * 套装版本选择：把 4.1.2.1 的套装映射指向某个内部套装版本。
   *
   * <p>🔴 换版<b>不就地改</b>：旧绑定被关闭（{@code retireCurrentAt}），新绑定另起一行，
   * 「同一映射至多一个当前生效」由 V186 的部分唯一索引挡 —— 并发下也成立，服务层先查只是为了
   * 给出可读提示。
   *
   * <p>🔴 映射必须是<b>生效中的套装映射</b>：一对一 / 多对一的映射没有组件可言，
   * 给它配组件会让「一套套装要扣几件」这个问题指向一个不存在的套装。
   */
  public BundleBindingDetail saveBinding(SaveBundleBindingRequest request, String bearer) {
    if (request.mappingId() == null) throw unprocessable("必须指定 4.1.2.1 的套装映射 id");
    if (request.bundleVersionId() == null) throw unprocessable("必须指定套装版本");

    Record mapping =
        sql.fetchOne(
            "select id, relation_type, status, internal_sku_id from listing_mapping"
                + TENANT_FILTER.replace("{alias}", "listing_mapping")
                + " and id=? for update",
            request.mappingId());
    if (mapping == null) throw notFound();
    if (!"ACTIVE".equals(mapping.get("status", String.class))) {
      throw conflict("这条映射已被解除；已解除的映射没有组件可言，请先在 4.1.2.1 重新建立映射");
    }
    if (!"ONE_TO_MANY".equals(mapping.get("relation_type", String.class))) {
      throw unprocessable(
          "只有一对多（套装）映射才有组件；当前这条是 "
              + mapping.get("relation_type", String.class)
              + "，给它配组件会让「一套要扣几件」指向一个不存在的套装");
    }

    String reason = text(request.changeReason());
    if (reason.isEmpty()) {
      throw unprocessable("变更原因必填：没有原因的套装版本历史无法回溯「当时为什么换版」");
    }

    // 🔴 套装版本必须真的存在于 catalog：写进去一个不存在的版本 id，
    //   组件表会挂在一个查不出构成的东西上，而 07 库存照着扣。
    List<BundleVersionQueryPort.CatalogComponentRow> upstream =
        bundles.components(request.bundleVersionId(), bearer);

    OffsetDateTime effectiveFrom =
        request.effectiveFrom() == null ? OffsetDateTime.now().withNano(0) : request.effectiveFrom();

    // 关闭既有当前绑定（换版）
    Record current =
        sql.fetchOne(
            "select id, effective_from from listing_bundle_binding"
                + TENANT_FILTER.replace("{alias}", "listing_bundle_binding")
                + " and mapping_id=? and effective_to is null for update",
            request.mappingId());
    if (current != null) {
      OffsetDateTime retireAt =
          request.retireCurrentAt() == null ? effectiveFrom : request.retireCurrentAt();
      if (!retireAt.isAfter(current.get("effective_from", OffsetDateTime.class))) {
        throw unprocessable(
            "退役时刻必须晚于当前绑定的生效起点（" + current.get("effective_from", OffsetDateTime.class) + "）");
      }
      sql.execute(
          "update listing_bundle_binding set effective_to=?::timestamptz, updated_at=now(),"
              + " version=version+1"
              + TENANT_FILTER.replace("{alias}", "listing_bundle_binding")
              + " and id=?",
          retireAt, current.get("id", UUID.class));
    } else if (request.retireCurrentAt() != null) {
      throw unprocessable("这条映射当前没有生效中的套装版本绑定，无需退役");
    }

    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into listing_bundle_binding"
            + " (id,tenant_id,mapping_id,bundle_version_id,effective_from,effective_to,change_reason)"
            + " values (?::uuid,?,?::uuid,?::uuid,?::timestamptz,null,?)",
        id, currentTenant(), request.mappingId(), request.bundleVersionId(), effectiveFrom, reason);

    // 🔴 「组件数量必须大于 0」不能靠上游构成兜底：上游 bundle_component 自己也有 CHECK(quantity>0)，
    //   但本表的 quantity 允许与上游不同（同一套装在不同店铺按不同倍数卖），
    //   所以必须由调用方逐条给出 —— 且**一个组件都不给就建绑定，等于建出一个永久不可售的套装**。
    //   这里如实把上游构成带进响应，让运营看到「这一版在商品中心是这么配的」，而不是悄悄替他填一份。
    BundleBindingDetail detail = detail(id.toString());
    return new BundleBindingDetail(
        detail.binding(),
        detail.components(),
        "该套装版本在商品中心的构成是 "
            + upstream.size()
            + " 个组件："
            + upstream.stream()
                .map(
                    c ->
                        (c.skuCode() == null || c.skuCode().isBlank() ? String.valueOf(c.skuId()) : c.skuCode())
                            + "×"
                            + c.quantity())
                .reduce((a, b) -> a + "、")
                .orElse("（无）")
            + "。本表不复制这份构成（真值在 catalog，避免两个真相）：刊登侧按下面逐条登记的数量扣减，"
            + "两者可以不同（例如同一套装在 A 店 1:1、B 店 2:1）。"
            + "注意：当前登记了 "
            + detail.components().size()
            + " 个组件；按蓝图「缺任一组件时整个套装不可售」，没有组件的套装不可售。");
  }

  // ==================================================================
  // 组件映射维护（写入）
  // ==================================================================

  /**
   * 组件映射维护：新增或改一条组件。
   *
   * <p>🔴 <b>「组件数量必须大于 0」在这里先给可读原因</b>，DB 的 CHECK 与触发器再兜一道
   * （纪律与 4.1.2.1 一致：服务层自查 + DB 触发器兜底）。
   *
   * <p>🔴 <b>构成归属经 catalog 授权 API 核对</b>：组件 SKU 必须是<b>这一版套装</b>的组件。
   * 逻辑 ID 在数据库层面表达不了这层归属，硬写一个手滑的 uuid 会让 07 库存去扣一个
   * 与套装无关的 SKU —— 扣得动，所以更危险。
   */
  public ListingBundleComponentRow saveComponent(SaveBundleComponentRequest request, String bearer) {
    if (request.bindingId() == null) throw unprocessable("必须指定绑定 id");
    if (request.componentSkuId() == null) throw unprocessable("必须指定组件 SKU");

    BigDecimal quantity = request.quantity();
    if (quantity == null || quantity.signum() <= 0) {
      throw unprocessable(
          "组件数量必须大于 0（蓝图加粗句）：数量为 0 的组件会让 07 库存扣出一条 0 扣减，"
              + "两边都答不出「这个组件到底要不要」");
    }

    Record binding = requireCurrentBinding(request.bindingId().toString());
    UUID bundleVersionId = binding.get("bundle_version_id", UUID.class);

    // 🔴 构成归属核对：catalog 是这套版本构成的真值来源
    List<BundleVersionQueryPort.CatalogComponentRow> upstream =
        bundles.components(bundleVersionId, bearer);
    Set<UUID> allowed = new HashSet<>();
    for (var row : upstream) {
      if (row.skuId() != null) allowed.add(row.skuId());
    }
    if (!allowed.contains(request.componentSkuId())) {
      String upstreamText =
          upstream.isEmpty()
              ? "（这一版在商品中心一个组件都没有）"
              : upstream.stream()
                  .map(
                      c ->
                          (c.skuCode() == null || c.skuCode().isBlank() ? String.valueOf(c.skuId()) : c.skuCode()))
                  .reduce((a, b) -> a + "、")
                  .orElse("（无）");
      throw unprocessable(
          "组件 SKU 不属于所选套装版本（"
              + bundleVersionId
              + "）。商品中心这一版的组件是："
              + upstreamText
              + "。往里塞别的 SKU 会让 07 库存扣一个与套装无关的东西 —— 扣得动，所以更危险");
    }

    UUID id = request.id();
    if (id != null) {
      Record existing =
          sql.fetchOne(
              "select id, binding_id from listing_bundle_component"
                  + TENANT_FILTER.replace("{alias}", "listing_bundle_component")
                  + " and id=? for update",
              id);
      if (existing == null) throw notFound();
      if (!request.bindingId().equals(existing.get("binding_id", UUID.class))) {
        throw unprocessable("这条组件属于另一个绑定，不能挪过来：组件的历史按绑定串起来");
      }
      sql.execute(
          "update listing_bundle_component set component_sku_id=?::uuid, quantity=?, role=?, note=?,"
              + " updated_at=now(), version=version+1"
              + TENANT_FILTER.replace("{alias}", "listing_bundle_component")
              + " and id=?",
          request.componentSkuId(), quantity, text(request.role()), text(request.note()), id);
      return componentRow(id.toString());
    }

    id = UUID.randomUUID();
    try {
      sql.execute(
          "insert into listing_bundle_component"
              + " (id,tenant_id,binding_id,component_sku_id,quantity,role,note)"
              + " values (?::uuid,?,?::uuid,?::uuid,?,?,?)",
          id,
          currentTenant(),
          request.bindingId(),
          request.componentSkuId(),
          quantity,
          text(request.role()),
          text(request.note()));
    } catch (org.springframework.dao.DataIntegrityViolationException
        | org.jooq.exception.DataAccessException e) {
      // 同一绑定下同一 SKU 只允许一行（V186 的 UNIQUE(tenant_id,binding_id,component_sku_id)）：
      // 两行时「这个组件要几件」无法回答，而 07 库存预占只能取一列。
      // 🔴 两种都要接：Spring 的 DefaultExceptionTranslatorExecuteListener 会先把 jOOQ 的
      //   DataAccessException 翻成 DuplicateKeyException / DataIntegrityViolationException，
      //   只接 jOOQ 那一支时，用户看到的是裸的约束名而不是人话（实测）。
      throw conflict(
          "这条组件已经登记过了（同一套装版本下每个组件 SKU 只能有一行）。要改数量请改这一行，"
              + "不要新增第二行 —— 两行时「这个组件要几件」就没有答案了");
    }
    return componentRow(id.toString());
  }

  /** 删除一条组件登记。只对当前生效的绑定开放（已退役绑定的组件是历史，由 V186 触发器兜住）。 */
  public ListingBundleComponentDetail removeComponent(String rawId) {
    UUID id = parseId(rawId);
    Record row =
        sql.fetchOne(
            "select id, binding_id from listing_bundle_component"
                + TENANT_FILTER.replace("{alias}", "listing_bundle_component")
                + " and id=? for update",
            id);
    if (row == null) throw notFound();
    UUID bindingId = row.get("binding_id", UUID.class);
    requireCurrentBinding(bindingId.toString());

    BundleBindingDetail before = detail(bindingId.toString());
    sql.execute(
        "delete from listing_bundle_component"
            + TENANT_FILTER.replace("{alias}", "listing_bundle_component")
            + " and id=?",
        id);
    return new ListingBundleComponentDetail(
        id, bindingId, before.components(), before.binding(),
        "组件已删除。注意按蓝图「缺任一组件时整个套装不可售」：删到一条不剩时整套不可售，"
            + "运营界面应当立即可见。");
  }

  // ==================================================================
  // 组件映射记录（读）
  // ==================================================================

  /** 组件列表：真分页 + 可按绑定 / SKU 筛选。 */
  public ListingBundleComponentPage listComponents(
      UUID bindingId, UUID componentSkuId, String search, int page, int size) {
    int pageSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    int pageNo = Math.max(page, 0);

    List<Object> binds = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where c.tenant_id = ?");
    binds.add(currentTenant());
    if (bindingId != null) {
      where.append(" and c.binding_id = ?");
      binds.add(bindingId);
    }
    if (componentSkuId != null) {
      where.append(" and c.component_sku_id = ?");
      binds.add(componentSkuId);
    }
    if (search != null && !search.isBlank()) {
      String like = "%" + escape(search.trim()) + "%";
      where.append(
          " and (c.role like ? escape '!' or c.note like ? escape '!'"
              + " or cast(c.component_sku_id as text) like ? escape '!')");
      for (int i = 0; i < 3; i++) binds.add(like);
    }

    String from =
        " from listing_bundle_component c"
            + " join listing_bundle_binding b on b.tenant_id = c.tenant_id and b.id = c.binding_id";
    long total = count("select count(*)" + from + where, binds.toArray());
    if (total == 0) return new ListingBundleComponentPage(List.of(), 0, pageNo, pageSize);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(pageSize);
    pageBinds.add(pageNo * pageSize);
    List<ListingBundleComponentRow> items =
        sql.fetch(
            "select c.id, c.binding_id, c.component_sku_id, c.quantity, c.role, c.note,"
                + " b.effective_to, c.created_at, c.updated_at"
                + from
                + where
                + " order by c.created_at asc, c.id asc limit ? offset ?",
            pageBinds.toArray())
            .stream()
            .map(ListingBundleComponentService::componentRowOf)
            .toList();
    return new ListingBundleComponentPage(items, total, pageNo, pageSize);
  }

  /** 单个绑定详情：套装版本选择 + 逐组件明细。 */
  public BundleBindingDetail detail(String rawId) {
    UUID id = parseId(rawId);
    Record row =
        sql.fetchOne(
            BINDING_COLUMNS + TENANT_FILTER.replace("{alias}", "b") + " and b.id = ?", id);
    // 跨租户与不存在都表现为这一条：不泄露「别的租户有这么一条」
    if (row == null) throw notFound();

    List<ListingBundleComponentRow> components =
        sql.fetch(
                "select c.id, c.binding_id, c.component_sku_id, c.quantity, c.role, c.note,"
                    + " b.effective_to, c.created_at, c.updated_at"
                    + " from listing_bundle_component c"
                    + " join listing_bundle_binding b on b.tenant_id = c.tenant_id and b.id = c.binding_id"
                    + " where c.tenant_id = ? and c.binding_id = ?"
                    + " order by c.created_at asc, c.id asc",
                currentTenant(), id)
            .stream()
            .map(ListingBundleComponentService::componentRowOf)
            .toList();

    return new BundleBindingDetail(
        bindingRow(row),
        components,
        "构成的真值在 catalog 3.5.1；本表只记刊登侧的扣减口径「每卖 1 件平台套装扣几件」。"
            + "按蓝图「缺任一组件时整个套装不可售」，请用 sellable-check 判定能不能卖。");
  }

  // ==================================================================
  // 可售性校验结果（模块的输出）
  // ==================================================================

  /**
   * 校验组件 SKU 在<b>目标仓库</b>可售。
   *
   * <p>🔴 蓝图加粗句「缺任一组件时整个套装不可售」就落在 {@link ListingBundleSellableCheck#bundleSellable()}：
   * 逐行看每个组件都还有货，但只要<b>有一个</b>不够整套用量，这一整套就卖不出去。
   *
   * <p>🔴 <b>上游读不到就抛 503，不降级</b>：本方法不会返回「全部可售」——
   * 「我们查不到 warehouse」被读成「能卖」，就是超卖的第一步（与
   * {@link SellableQuantityQueryPort} 同一纪律）。
   *
   * <p>🔴 组件数为 0 时<b>不调用</b> warehouse：没有组件就没有「缺任一组件」，
   * 直接判定整套不可售并说清原因，比发一个必然为空的请求更有用。
   */
  public ListingBundleSellableCheck sellableCheck(String rawBindingId, UUID warehouseId, String bearer) {
    UUID bindingId = parseId(rawBindingId);
    if (warehouseId == null) {
      throw unprocessable("必须指定目标仓库：可售量按仓库计算，不指定仓库就没有唯一的答案");
    }

    Record binding =
        sql.fetchOne(
            "select id, mapping_id, bundle_version_id from listing_bundle_binding"
                + TENANT_FILTER.replace("{alias}", "listing_bundle_binding")
                + " and id=?",
            bindingId);
    if (binding == null) throw notFound();

    List<ListingBundleComponentRow> components =
        sql.fetch(
                "select c.id, c.binding_id, c.component_sku_id, c.quantity, c.role, c.note,"
                    + " b.effective_to, c.created_at, c.updated_at"
                    + " from listing_bundle_component c"
                    + " join listing_bundle_binding b on b.tenant_id = c.tenant_id and b.id = c.binding_id"
                    + " where c.tenant_id = ? and c.binding_id = ?"
                    + " order by c.created_at asc, c.id asc",
                currentTenant(), bindingId)
            .stream()
            .map(ListingBundleComponentService::componentRowOf)
            .toList();

    if (components.isEmpty()) {
      return new ListingBundleSellableCheck(
          bindingId,
          binding.get("mapping_id", UUID.class),
          binding.get("bundle_version_id", UUID.class),
          warehouseId,
          true,
          false,
          0,
          0,
          "这一版还没有登记任何组件：按蓝图「缺任一组件时整个套装不可售」，没有组件的套装不可售",
          List.of(),
          SELLABLE_NOTE);
    }

    List<UUID> skuIds = components.stream().map(ListingBundleComponentRow::componentSkuId).toList();
    SellableQuantityQueryPort.SellablePage page = sellable.sellable(warehouseId, skuIds, bearer);

    List<ListingBundleComponentSellable> perComponent = new ArrayList<>();
    int unsellable = 0;
    List<String> shortNames = new ArrayList<>();
    for (ListingBundleComponentRow component : components) {
      SellableQuantityQueryPort.SellableRow upstream =
          page.rows().stream()
              .filter(r -> Objects.equals(r.skuId(), component.componentSkuId()))
              .findFirst()
              .orElse(null);

      // 🔴 上游查询成功但没有这一行 = 这个 SKU 在该仓库没有可售量。这是**结论**，不是故障。
      BigDecimal sellableQty = upstream == null || upstream.sellable() == null
          ? BigDecimal.ZERO
          : upstream.sellable();

      BigDecimal need = component.quantity();
      boolean ok = sellableQty.compareTo(need) >= 0;
      BigDecimal sets = need.signum() == 0 ? BigDecimal.ZERO : sellableQty.divide(need, 0, RoundingMode.DOWN);

      String reason = null;
      if (upstream == null) {
        reason = "目标仓库查不到这个组件 SKU 的库存行：可售量按 0 计";
      } else if (!ok) {
        reason = "可售量 " + sellableQty.stripTrailingZeros().toPlainString() + " 不足每件所需的 "
            + need.stripTrailingZeros().toPlainString();
      }
      if (!ok) {
        unsellable++;
        shortNames.add(component.componentSkuId() + "（" + reason + "）");
      }

      perComponent.add(
          new ListingBundleComponentSellable(
              component.id(), component.componentSkuId(), need, sellableQty, sets, ok, reason));
    }

    boolean bundleSellable = unsellable == 0;
    return new ListingBundleSellableCheck(
        bindingId,
        binding.get("mapping_id", UUID.class),
        binding.get("bundle_version_id", UUID.class),
        warehouseId,
        true,
        bundleSellable,
        components.size(),
        unsellable,
        bundleSellable ? null : "缺任一组件 ⇒ 整个套装不可售。不足的组件：" + String.join("；", shortNames),
        perComponent,
        SELLABLE_NOTE);
  }

  // ==================================================================
  // 辅助
  // ==================================================================

  /** 取一个处于当前生效状态的绑定；否则给出可读原因。 */
  private Record requireCurrentBinding(String rawId) {
    UUID id = parseId(rawId);
    Record binding =
        sql.fetchOne(
            "select id, bundle_version_id, effective_to from listing_bundle_binding"
                + TENANT_FILTER.replace("{alias}", "listing_bundle_binding")
                + " and id=? for update",
            id);
    if (binding == null) throw notFound();
    if (binding.get("effective_to", OffsetDateTime.class) != null) {
      throw conflict(
          "这条套装版本绑定已退役（"
              + binding.get("effective_to", OffsetDateTime.class)
              + "）：它的组件是历史，只读。要改组件请先在当前生效的绑定上改");
    }
    return binding;
  }

  private ListingBundleComponentRow componentRow(String rawId) {
    UUID id = parseId(rawId);
    Record row =
        sql.fetchOne(
            "select c.id, c.binding_id, c.component_sku_id, c.quantity, c.role, c.note,"
                + " b.effective_to, c.created_at, c.updated_at"
                + " from listing_bundle_component c"
                + " join listing_bundle_binding b on b.tenant_id = c.tenant_id and b.id = c.binding_id"
                + " where c.tenant_id = ? and c.id = ?",
            currentTenant(), id);
    if (row == null) throw notFound();
    return componentRowOf(row);
  }

  private static ListingBundleBindingRow bindingRow(Record r) {
    OffsetDateTime effectiveTo = r.get("effective_to", OffsetDateTime.class);
    return new ListingBundleBindingRow(
        r.get("id", UUID.class),
        r.get("mapping_id", UUID.class),
        r.get("listing_id", UUID.class),
        r.get("listing_variant_id", UUID.class),
        r.get("shop_code", String.class),
        r.get("external_item_id", String.class),
        r.get("external_variant_id", String.class),
        r.get("bundle_version_id", UUID.class),
        r.get("effective_from", OffsetDateTime.class),
        effectiveTo,
        effectiveTo == null,
        r.get("change_reason", String.class),
        r.get("actor_id", UUID.class),
        r.get("component_count", Integer.class) == null ? 0 : r.get("component_count", Integer.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static ListingBundleComponentRow componentRowOf(Record r) {
    return new ListingBundleComponentRow(
        r.get("id", UUID.class),
        r.get("binding_id", UUID.class),
        r.get("component_sku_id", UUID.class),
        r.get("quantity", BigDecimal.class),
        r.get("role", String.class),
        r.get("note", String.class),
        r.get("effective_to", OffsetDateTime.class) == null,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private long count(String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? 0L : row.get(0, Long.class);
  }

  private UUID currentTenant() {
    Object raw = sql.fetchValue("select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    return UUID.fromString(String.valueOf(raw));
  }

  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      // 与「不存在」同一条路径：不把「格式不对」暴露成一个可区分的信号
      throw notFound();
    }
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  private static ResponseStatusException notFound() {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "不存在");
  }

  private static ResponseStatusException unprocessable(String message) {
    return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }
}
