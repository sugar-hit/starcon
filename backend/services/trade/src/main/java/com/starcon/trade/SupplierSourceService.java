package com.starcon.trade;

import static com.starcon.trade.SupplierSourceModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.1.2.1 档口与货源池。
 *
 * <p>蓝图原话：「管理 1688/义乌购等货源渠道的店铺档口，抓取与同步在售货源（标题、起订量、价格区间、规格、
 * 图片），支持收藏、比价、建立与内部 SKU 的货源关联。」
 *
 * <p><b>本类的三条纪律</b>：
 *
 * <ol>
 *   <li><b>快照只追加</b>：每次采集写一批新的 {@code supplier_source_snapshot} 并把「当前指针」移到新版本；
 *       旧版本原地保留（DB 触发器钉死不可改写）。于是「上周三看到的那个价是什么」永远答得出来，
 *       而界面上显示的「当前价」永远是**当前指针所指那一版**的值 —— 不是另存一份会被改写的副本。
 *   <li><b>外部平台抓不到就显式失败</b>：未接线 / 平台不可达 / 平台返回失败，一律落一条 FAILED 采集批次并写明原因，
 *       绝不返回一批编造的货源。这条比什么都重要：编造的货源会一路流进比价与采购单。
 *   <li><b>价格永远是区间</b>：下限与上限同时存在、同时返回；比价时区间重叠就返回区间，
 *       绝不把区间压成一个「精确价」交给运营做决策。
 * </ol>
 *
 * <p><b>跨服务只经授权 API</b>：供应商（catalog）与内部 SKU（catalog）都只存逻辑 ID + 名称 / 编码快照，
 * 校验走 {@link SupplierPartyQueryPort} / {@link SourceSkuQueryPort}，不跨库 SQL、不跨库 FK、不跨库视图。
 */
@Service
public class SupplierSourceService {

  /** 单页最大条数。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 单次采集最多取多少条货源（防止一次拉爆库）。 */
  private static final int MAX_COLLECT_ITEMS = 500;

  /** 价格刻度：与 DB 的 numeric(19,4) 逐字对齐。 */
  private static final int PRICE_SCALE = 4;

  /** 数量刻度：与 DB 的 numeric(19,6) 逐字对齐。 */
  private static final int QTY_SCALE = 6;

  private final DSLContext sql;
  private final TransactionTemplate tx;
  private final SupplierPartyQueryPort parties;
  private final SourceSkuQueryPort skus;
  private final Map<String, SupplierSourceGateway> gateways;

  public SupplierSourceService(
      DSLContext sql,
      PlatformTransactionManager manager,
      SupplierPartyQueryPort parties,
      SourceSkuQueryPort skus,
      List<SupplierSourceGateway> gatewayList) {
    this.sql = sql;
    this.tx = new TransactionTemplate(manager);
    this.parties = parties;
    this.skus = skus;
    var map = new LinkedHashMap<String, SupplierSourceGateway>();
    for (var gateway : gatewayList) {
      map.put(gateway.platform(), gateway);
    }
    this.gateways = map;
  }

  // ================================================================ 能力声明

  /**
   * 能力与上下游可用性。
   *
   * <p>存在的理由与 9.3.2.2 一致：把「哪些输入真有依据」「哪些下游还没交付」显式报出来，
   * 而不是让调用方从 404 或空数组里去猜哪一环没通。
   */
  public SupplierSourceCapabilities capabilities() {
    return new SupplierSourceCapabilities(
        platformOptions(),
        collectMethodOptions(),
        collectorEndpoints(),
        List.of(
            new SourceGap(
                "6.1.2.2",
                "同款货源推荐",
                "trade",
                false,
                "同款 / 相似货源的判定与置信度未交付：本模块只提供「已关联的 SKU ↔ 候选货源」这条**人工确认**的关联，"
                    + "不做图片相似度这类自动判定（误判会导致采购到错误商品）。"),
            new SourceGap(
                "6.1.2.3",
                "供应商资质",
                "catalog",
                false,
                "供应商资质附件属 6.1.1.2 / 6.1.2.3，本模块的档口只关联供应商身份，不呈现资质。"),
            new SourceGap(
                "3.6.2",
                "供应商报价",
                "catalog",
                false,
                "报价（阶梯价 + 有效期 + 币种）的主数据在 03，本模块的货源快照**不是报价**："
                    + "它是外部平台的一次性观测，没有有效期，也没有采购价承诺。"),
            new SourceGap(
                "6.3.1.2",
                "采购单创建与编辑",
                "trade",
                false,
                "采购单未交付：货源快照当前只能被读（比价、关联），还不能被带入采购单。"
                    + "蓝图加粗句「采购下单时必须以实际下单金额为准，货源快照仅作参考」已在本模块结构里落位 —— "
                    + "快照的价格区间与起订量一律标着采集时刻，没有「当前价」列。"),
            new SourceGap(
                "12.1.1",
                "汇率快照",
                "finance",
                false,
                "汇率属 12 财务：本模块**不跨币种比价**，比价按币种分组给出最低价，不做汇率换算。")),
        "货源是**外部快照**：价格区间、起订量、标题与在售状态都是「某一次采集看到的值」，"
            + "带采集时刻，永不改写。采购下单时必须以实际下单金额为准，货源快照仅作参考。",
        "上游是外部货源平台（1688 / 义乌购），采集是**主动抓取**：接不上的平台显式失败并写明缺什么，"
            + "绝不返回编造的货源。供应商与内部 SKU 的事实在 catalog，本服务只经授权只读接口取，"
            + "库里只存逻辑 ID 与名称 / 编码快照。");
  }

  /** 词表选项：前端下拉的数据源，与后端同源。 */
  public SupplierSourceOptions options() {
    return new SupplierSourceOptions(
        platformOptions(),
        collectMethodOptions(),
        List.of(
            new SupplierSourceOption("ACTIVE", StallStatus.ACTIVE.label(), "启用：可采集、可挂货源"),
            new SupplierSourceOption("PAUSED", StallStatus.PAUSED.label(), "停用：不再采集，历史货源照常可读")),
        List.of(
            new SupplierSourceSortOption("collectedAt", "最近采集时刻", true),
            new SupplierSourceSortOption("priceMin", "价格下限（低到高）", false),
            new SupplierSourceSortOption("moq", "起订量（低到高）", false),
            new SupplierSourceSortOption("title", "标题", false)),
        collectorEndpoints(),
        "价格与起订量是**某一次采集的快照值**，界面上必须带着采集时刻一起看；"
            + "平台词表是封闭的（每个平台一个 vendor 适配器），加平台要加适配器。");
  }

  /** 概览统计（不受筛选与分页影响）。 */
  public SourceOverview overview() {
    Record stallSummary =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where status='ACTIVE') as active,"
                + " count(*) filter (where status='PAUSED') as paused"
                + " from supplier_source_stall");
    Record itemSummary =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where sn.on_sale) as on_sale,"
                + " count(*) filter (where not sn.on_sale) as off_sale"
                + " from supplier_source_item i"
                + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id");
    long snapshotCount = sql.fetchOne("select count(*) from supplier_source_snapshot").get(0, Long.class);
    long favoriteCount = sql.fetchOne("select count(*) from supplier_source_favorite").get(0, Long.class);
    long skuLinkCount = sql.fetchOne("select count(*) from supplier_source_sku_link").get(0, Long.class);
    long failedCount =
        sql.fetchOne(
                "select count(*) from supplier_source_collection where status='FAILED'")
            .get(0, Long.class);
    Record last =
        sql.fetchOne("select max(collected_at) as last_collected_at from supplier_source_collection");
    return new SourceOverview(
        count(stallSummary, "total"),
        count(stallSummary, "active"),
        count(stallSummary, "paused"),
        count(itemSummary, "total"),
        count(itemSummary, "on_sale"),
        count(itemSummary, "off_sale"),
        favoriteCount,
        skuLinkCount,
        snapshotCount,
        failedCount,
        last == null ? null : instant(last, "last_collected_at"),
        "在售 / 下架是**当前快照**里平台显式返回的状态；本模块不把「本次采集没出现」判成下架 —— "
            + "平台列表可能是分页或局部返回，把它判成下架是在编造事实。");
  }

  // ================================================================ 档口

  /** 档口列表（真分页 + 真筛选；汇总不受分页影响）。 */
  public StallPage pageStalls(StallQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (query.platform() != null && !query.platform().isBlank()) {
      where.add("s.platform = ?");
      binds.add(platform(query.platform()).name());
    }
    if (query.collectMethod() != null && !query.collectMethod().isBlank()) {
      where.add("s.collect_method = ?");
      binds.add(collectMethod(query.collectMethod()).name());
    }
    if (query.status() != null && !query.status().isBlank()) {
      where.add("s.status = ?");
      binds.add(stallStatus(query.status()).name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      // 真实模糊检索：命中编码 / 名称 / 平台档口 ID（LIKE 通配符已转义）。
      where.add("(s.code ilike ? or s.name ilike ? or s.platform_stall_id ilike ?)");
      String pattern = likePattern(query.keyword());
      binds.add(pattern);
      binds.add(pattern);
      binds.add(pattern);
    }
    if (query.favoritedOnly()) {
      where.add(
          "exists (select 1 from supplier_source_favorite f"
              + " join supplier_source_item fi on fi.tenant_id=f.tenant_id and fi.id=f.item_id"
              + " where fi.stall_id = s.id)");
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total = sql.fetchOne("select count(*) from supplier_source_stall s" + clause, binds.toArray()).get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where status='ACTIVE') as active,"
                + " count(*) filter (where status='PAUSED') as paused"
                + " from supplier_source_stall");
    List<StallRow> items =
        sql.fetch(
                "select s.id,s.code,s.platform,s.platform_stall_id,s.name,s.url,s.collect_method,"
                    + " s.status,s.supplier_party_id,s.supplier_name_snapshot,s.note,s.version,"
                    + " s.last_collected_at,s.last_collection_status,s.last_failure_reason,"
                    + " c.item_count, c.total_count, f.favorite_count, l.sku_link_count"
                    + " from supplier_source_stall s"
                    + " left join lateral ("
                    + "   select count(*) filter (where sn.on_sale) as item_count, count(*) as total_count"
                    + "   from supplier_source_item i"
                    + "   join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                    + "   where i.stall_id = s.id) c on true"
                    + " left join lateral ("
                    + "   select count(*) as favorite_count from supplier_source_favorite fv"
                    + "   where fv.item_id in (select i2.id from supplier_source_item i2 where i2.stall_id = s.id)) f on true"
                    + " left join lateral ("
                    + "   select count(*) as sku_link_count from supplier_source_sku_link lk"
                    + "   where lk.item_id in (select i3.id from supplier_source_item i3 where i3.stall_id = s.id)) l on true"
                    + clause
                    + " order by s.code, s.id limit ? offset ?",
                append(binds, size, page * size).toArray())
            .map(SupplierSourceService::stallRow);
    return new StallPage(
        items,
        page,
        size,
        total,
        count(summary, "active"),
        count(summary, "paused"),
        failedCollectionCount(),
        "档口的「在售货源数」是当前快照里平台显式标了在售的条数；"
            + "「最近采集」一列带失败原因，抓不到时会原样显示，不做美化。");
  }

  /** 档口详情：档口头 + 当前货源条目 + 采集批次历史。 */
  public StallDetail stall(UUID stallId) {
    Record head =
        sql.fetchOne(
            "select s.id,s.code,s.platform,s.platform_stall_id,s.name,s.url,s.collect_method,"
                + " s.status,s.supplier_party_id,s.supplier_name_snapshot,s.note,s.version,"
                + " s.last_collected_at,s.last_collection_status,s.last_failure_reason,"
                + " c.item_count, c.total_count, f.favorite_count, l.sku_link_count"
                + " from supplier_source_stall s"
                + " left join lateral ("
                + "   select count(*) filter (where sn.on_sale) as item_count, count(*) as total_count"
                + "   from supplier_source_item i"
                + "   join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                + "   where i.stall_id = s.id) c on true"
                + " left join lateral ("
                + "   select count(*) as favorite_count from supplier_source_favorite fv"
                + "   where fv.item_id in (select i2.id from supplier_source_item i2 where i2.stall_id = s.id)) f on true"
                + " left join lateral ("
                + "   select count(*) as sku_link_count from supplier_source_sku_link lk"
                + "   where lk.item_id in (select i3.id from supplier_source_item i3 where i3.stall_id = s.id)) l on true"
                + " where s.id = ?",
            stallId);
    if (head == null) {
      throw notFound("档口 " + stallId + " 不存在或无权查看");
    }
    List<SourceItemRow> items = itemsOfStall(stallId);
    List<CollectionRow> collections =
        sql.fetch(
                "select id,stall_id,collect_method,status,trigger_source,collected_at,started_at,"
                    + " finished_at,item_count,skipped_count,skipped_reason,failure_reason"
                    + " from supplier_source_collection where stall_id = ?"
                    + " order by collected_at desc, id desc limit 50",
                stallId)
            .map(SupplierSourceService::collectionRow);
    return new StallDetail(
        stallRow(head),
        items,
        collections,
        collectorEndpoint(head.get("platform", String.class)),
        "货源条目显示的是**当前快照**的值（带采集时刻）；历史版本在货源详情里可查，"
            + "旧版本永不改写。");
  }

  /** 登记档口。编码租户内唯一；关联供应商时经 catalog 授权接口核对并留名快照。 */
  public StallRow createStall(StallInput input, String bearer) {
    SourcePlatform platform = platform(input.platform());
    CollectMethod method = collectMethod(input.collectMethod());
    String code = fit(input.code(), 64, "档口编码");
    String platformStallId = fit(input.platformStallId(), 128, "平台档口 ID");
    String name = fit(input.name(), 200, "档口名称");
    if (code.isEmpty() || platformStallId.isEmpty() || name.isEmpty()) {
      throw bad("档口编码、平台档口 ID 与档口名称都不能为空");
    }
    if (exists("select 1 from supplier_source_stall where code = ?", code)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "档口编码「" + code + "」已被本租户其它档口占用");
    }
    String supplierName = "";
    if (input.supplierPartyId() != null) {
      // 供应商事实在 catalog：核对 + 取名快照，本库只存逻辑 ID 与快照字段。
      var supplier = parties.require(input.supplierPartyId(), bearer);
      supplierName = supplier.partyName();
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = now();
    sql.execute(
        "insert into supplier_source_stall(id,tenant_id,code,platform,platform_stall_id,name,url,"
            + "collect_method,supplier_party_id,supplier_name_snapshot,status,note,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,'ACTIVE',?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        platform.name(),
        platformStallId,
        name,
        fit(input.url(), 2000, "档口 URL"),
        method.name(),
        input.supplierPartyId(),
        supplierName,
        fit(input.note(), 2000, "备注"),
        now,
        now);
    return stallRow(
        sql.fetchOne(
            "select s.id,s.code,s.platform,s.platform_stall_id,s.name,s.url,s.collect_method,"
                + " s.status,s.supplier_party_id,s.supplier_name_snapshot,s.note,s.version,"
                + " s.last_collected_at,s.last_collection_status,s.last_failure_reason,"
                + " 0 as item_count, 0 as total_count, 0 as favorite_count, 0 as sku_link_count"
                + " from supplier_source_stall s where s.id = ?",
            id));
  }

  /** 修改档口（名称 / URL / 采集方式 / 供应商关联 / 启停）。编码与平台建后不可改。 */
  public StallRow updateStall(UUID stallId, StallUpdateInput input, String bearer) {
    Record head = sql.fetchOne("select * from supplier_source_stall where id = ?", stallId);
    if (head == null) {
      throw notFound("档口 " + stallId + " 不存在或无权修改");
    }
    requireVersion(head.get("version", Long.class), input.version());
    String supplierName = head.get("supplier_name_snapshot", String.class);
    if (input.supplierPartyId() != null) {
      var supplier = parties.require(input.supplierPartyId(), bearer);
      supplierName = supplier.partyName();
    } else {
      supplierName = "";
    }
    sql.execute(
        "update supplier_source_stall set name=?,url=?,collect_method=?,supplier_party_id=?,"
            + "supplier_name_snapshot=?,status=?,note=?,version=version+1,updated_at=now() where id=?",
        fit(input.name(), 200, "档口名称"),
        fit(input.url(), 2000, "档口 URL"),
        collectMethod(input.collectMethod()).name(),
        input.supplierPartyId(),
        supplierName,
        stallStatus(input.status()).name(),
        fit(input.note(), 2000, "备注"),
        stallId);
    return stallRow(
        sql.fetchOne(
            "select s.id,s.code,s.platform,s.platform_stall_id,s.name,s.url,s.collect_method,"
                + " s.status,s.supplier_party_id,s.supplier_name_snapshot,s.note,s.version,"
                + " s.last_collected_at,s.last_collection_status,s.last_failure_reason,"
                + " 0 as item_count, 0 as total_count, 0 as favorite_count, 0 as sku_link_count"
                + " from supplier_source_stall s where s.id = ?",
            stallId));
  }

  // ================================================================ 采集 / 同步

  /**
   * 触发一次主动抓取（{@code URL_SYNC} 档口）。
   *
   * <p>三段式：
   *
   * <ol>
   *   <li>读档口；</li>
   *   <li>调 vendor 适配器（外部 HTTP，必须有明确超时上界 —— 平台不可达时不能无限期挂着）；</li>
   *   <li>落采集批次 + 快照（写入前锁住档口行，保证同一档口的并发采集串行、snapshot_seq 不撞号）。</li>
   * </ol>
   *
   * <p>失败（未接线 / 平台不可达 / 平台返回失败）时落一条 **FAILED** 批次并写明原因，零快照写入 ——
   * 半截数据比没有数据更危险。失败路径**不抛异常**：抛了会把失败记录一起回滚掉，
   * 那样界面上就只剩「同步失败」四个字而没有原因。
   */
  public SyncResult sync(UUID stallId) {
    Record head = sql.fetchOne("select * from supplier_source_stall where id = ?", stallId);
    if (head == null) {
      throw notFound("档口 " + stallId + " 不存在或无权同步");
    }
    String status = head.get("status", String.class);
    if (!"ACTIVE".equals(status)) {
      throw bad("档口「" + head.get("name", String.class) + "」已停用，先启用再同步");
    }
    String method = head.get("collect_method", String.class);
    if (!"URL_SYNC".equals(method)) {
      throw bad(
          "档口「"
              + head.get("name", String.class)
              + "」的采集方式是「插件采集推送」："
              + "它不会由本服务主动发起外部请求，请由插件把采到的货源条目推送上来。");
    }
    String platform = head.get("platform", String.class);
    SupplierSourceGateway gateway = gateways.get(platform);
    if (gateway == null) {
      return recordFailure(
          stallId,
          head,
          "平台 " + platform + " 还没有采集适配器，这次没有采到任何货源");
    }
    OffsetDateTime startedAt = now();
    try {
      var batch =
          gateway.collect(
              new SupplierSourceGateway.StallRequest(
                  head.get("platform_stall_id", String.class),
                  head.get("url", String.class),
                  MAX_COLLECT_ITEMS));
      return writeCollection(stallId, head, batch, TriggerSource.MANUAL, startedAt);
    } catch (SupplierSourceGateway.SourceUnavailableException e) {
      return recordFailure(stallId, head, e.reason(), startedAt);
    }
  }

  /**
   * 插件推送一次采集结果。
   *
   * <p>这是蓝图输入句「插件采集」那条路径：外部插件把采到的货源条目推上来，本服务不发起外部请求，
   * 因此<b>不需要</b>任何外部凭证 —— 这是本模块在未接线状态下仍然能真实运转的方式。
   *
   * <p>采集时刻取**服务端**写入时刻，不采信客户端时钟：客户端时钟漂移会把快照时间线搅乱，
   * 而时间线正是本模块的价值所在。
   */
  public SyncResult pushCollection(UUID stallId, CollectionPushInput input) {
    Record head = sql.fetchOne("select * from supplier_source_stall where id = ?", stallId);
    if (head == null) {
      throw notFound("档口 " + stallId + " 不存在或无权推送");
    }
    if (!"ACTIVE".equals(head.get("status", String.class))) {
      throw bad("档口「" + head.get("name", String.class) + "」已停用，先启用再采集");
    }
    if (!"PLUGIN_PUSH".equals(head.get("collect_method", String.class))) {
      throw bad(
          "档口「"
              + head.get("name", String.class)
              + "」的采集方式是「按档口 URL 主动抓取」：请点「同步」由本服务去抓，"
              + "而不是由插件推送。");
    }
    List<SupplierSourceGateway.CollectedItem> items = new ArrayList<>();
    List<String> skipped = new ArrayList<>();
    for (CollectedItemInput raw : input.items()) {
      BigDecimal min = price(raw.priceMin());
      BigDecimal max = price(raw.priceMax());
      if (max.compareTo(min) < 0) {
        skipped.add("货源 " + raw.platformItemId() + " 的价格区间写反了（下限高于上限），已跳过");
        continue;
      }
      BigDecimal moq = quantity(raw.moq());
      if (moq.signum() <= 0) {
        skipped.add("货源 " + raw.platformItemId() + " 的起订量必须大于 0，已跳过");
        continue;
      }
      String currency = raw.currency() == null ? "" : raw.currency().trim().toUpperCase(Locale.ROOT);
      if (!currency.matches("^[A-Z]{3}$")) {
        skipped.add("货源 " + raw.platformItemId() + " 的币种「" + raw.currency() + "」不是三位大写，已跳过");
        continue;
      }
      items.add(
          new SupplierSourceGateway.CollectedItem(
              fit(raw.platformItemId(), 128, "平台货源 ID"),
              fit(raw.title(), 400, "货源标题"),
              min,
              max,
              currency,
              moq,
              fit(raw.spec(), 200, "规格"),
              fit(raw.imageUrl(), 2000, "图片地址"),
              fit(raw.itemUrl(), 2000, "货源链接"),
              raw.onSale() == null || raw.onSale()));
    }
    OffsetDateTime startedAt = now();
    return writeCollection(
        stallId,
        head,
        new SupplierSourceGateway.CollectedBatch(items, skipped),
        TriggerSource.PLUGIN,
        startedAt);
  }

  /** 落一次采集批次（成功路径）：批次 + 快照 + 当前指针 + 档口的最近采集结果。 */
  private SyncResult writeCollection(
      UUID stallId,
      Record head,
      SupplierSourceGateway.CollectedBatch batch,
      TriggerSource trigger,
      OffsetDateTime startedAt) {
    UUID collectionId = UUID.randomUUID();
    OffsetDateTime collectedAt = now();
    int[] written =
        tx.execute(
            status -> {
              // 锁住档口行：同一档口的并发采集因此串行，snapshot_seq 不会撞号。
              Record locked =
                  sql.fetchOne("select id from supplier_source_stall where id = ? for update", stallId);
              if (locked == null) {
                throw notFound("档口 " + stallId + " 不存在或无权采集");
              }
              sql.execute(
                  "insert into supplier_source_collection(id,tenant_id,stall_id,collect_method,status,"
                      + "trigger_source,collected_at,started_at,finished_at,item_count,skipped_count,"
                      + "skipped_reason,failure_reason,created_at)"
                      + " values(?,?,?,?,'SUCCEEDED',?,?::timestamptz,?::timestamptz,?::timestamptz,?,?,?,'',?::timestamptz)",
                  collectionId,
                  currentTenant(),
                  stallId,
                  head.get("collect_method", String.class),
                  trigger.name(),
                  collectedAt,
                  startedAt,
                  now(),
                  batch.items().size(),
                  batch.skipped().size(),
                  joinSkipped(batch.skipped()),
                  now());
              for (var item : batch.items()) {
                upsertSnapshot(stallId, collectionId, item, collectedAt);
              }
              sql.execute(
                  "update supplier_source_stall set last_collection_id=?,last_collected_at=?::timestamptz,"
                      + "last_collection_status='SUCCEEDED',last_failure_reason='',version=version+1,"
                      + "updated_at=now() where id=?",
                  collectionId,
                  collectedAt,
                  stallId);
              return new int[] {batch.items().size()};
            });
    long count = written == null ? 0 : written[0];
    return new SyncResult(
        stallId,
        collectionId,
        CollectionStatus.SUCCEEDED.name(),
        CollectionStatus.SUCCEEDED.label(),
        count,
        batch.skipped().size(),
        joinSkipped(batch.skipped()),
        "",
        collectedAt.toString());
  }

  /**
   * 落一次**失败**的采集批次：零快照写入，只把「为什么没采到」记成一条事实。
   *
   * <p>失败也要留痕：界面上「同步失败」四个字没有原因，等于让运营猜。
   */
  private SyncResult recordFailure(
      UUID stallId, Record head, String reason) {
    return recordFailure(stallId, head, reason, now());
  }

  private SyncResult recordFailure(
      UUID stallId, Record head, String reason, OffsetDateTime startedAt) {
    UUID collectionId = UUID.randomUUID();
    OffsetDateTime collectedAt = now();
    tx.executeWithoutResult(
        status -> {
          sql.execute(
              "insert into supplier_source_collection(id,tenant_id,stall_id,collect_method,status,"
                  + "trigger_source,collected_at,started_at,finished_at,item_count,skipped_count,"
                  + "skipped_reason,failure_reason,created_at)"
                  + " values(?,?,?,?,'FAILED','MANUAL',?::timestamptz,?::timestamptz,?::timestamptz,0,0,'',?,?::timestamptz)",
              collectionId,
              currentTenant(),
              stallId,
              head.get("collect_method", String.class),
              collectedAt,
              startedAt,
              now(),
              fit(reason, 2000, "失败原因"),
              now());
          sql.execute(
              "update supplier_source_stall set last_collection_id=?,last_collected_at=?::timestamptz,"
                  + "last_collection_status='FAILED',last_failure_reason=?,version=version+1,"
                  + "updated_at=now() where id=?",
              collectionId,
              collectedAt,
              fit(reason, 2000, "失败原因"),
              stallId);
        });
    return new SyncResult(
        stallId,
        collectionId,
        CollectionStatus.FAILED.name(),
        CollectionStatus.FAILED.label(),
        0,
        0,
        "",
        fit(reason, 2000, "失败原因"),
        collectedAt.toString());
  }

  /**
   * 写入一版快照：身份行 upsert + 快照行 append + 当前指针前移。
   *
   * <p><b>旧快照一字不改</b>：只往 {@code supplier_source_snapshot} 追加一行，
   * 再把 {@code supplier_source_item.current_snapshot_id} 指到新行。
   */
  private void upsertSnapshot(
      UUID stallId,
      UUID collectionId,
      SupplierSourceGateway.CollectedItem item,
      OffsetDateTime collectedAt) {
    UUID tenantId = currentTenant();
    Record existing =
        sql.fetchOne(
            "select id from supplier_source_item where stall_id = ? and platform_item_id = ?",
            stallId,
            item.platformItemId());
    UUID itemId;
    if (existing == null) {
      itemId = UUID.randomUUID();
      sql.execute(
          "insert into supplier_source_item(id,tenant_id,stall_id,platform_item_id,first_seen_at,"
              + "last_seen_at,created_at,updated_at) values(?,?,?,?,?::timestamptz,?::timestamptz,"
              + "?::timestamptz,?::timestamptz)",
          itemId,
          tenantId,
          stallId,
          item.platformItemId(),
          collectedAt,
          collectedAt,
          collectedAt,
          collectedAt);
    } else {
      itemId = existing.get(0, UUID.class);
      sql.execute(
          "update supplier_source_item set last_seen_at=?::timestamptz,updated_at=?::timestamptz where id=?",
          collectedAt,
          collectedAt,
          itemId);
    }
    int seq =
        sql.fetchOne(
                "select coalesce(max(snapshot_seq),0) + 1 from supplier_source_snapshot where item_id = ?",
                itemId)
            .get(0, Integer.class);
    UUID snapshotId = UUID.randomUUID();
    sql.execute(
        "insert into supplier_source_snapshot(id,tenant_id,item_id,collection_id,snapshot_seq,title,"
            + "price_min,price_max,currency,moq,spec,image_url,item_url,on_sale,collected_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        snapshotId,
        tenantId,
        itemId,
        collectionId,
        seq,
        item.title(),
        item.priceMin(),
        item.priceMax(),
        item.currency(),
        item.moq(),
        item.spec(),
        item.imageUrl(),
        item.itemUrl(),
        item.onSale(),
        collectedAt,
        collectedAt);
    sql.execute(
        "update supplier_source_item set current_snapshot_id=?,version=version+1,updated_at=?::timestamptz where id=?",
        snapshotId,
        collectedAt,
        itemId);
  }

  // ================================================================ 货源条目

  /** 货源条目列表（真分页 + 真筛选 + 真排序）。 */
  public SourceItemPage pageItems(SourceItemQuery query) {
    int page = Math.max(0, query.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    if (query.stallId() != null) {
      where.add("i.stall_id = ?");
      binds.add(query.stallId());
    }
    if (query.platform() != null && !query.platform().isBlank()) {
      where.add("s.platform = ?");
      binds.add(platform(query.platform()).name());
    }
    if (query.keyword() != null && !query.keyword().isBlank()) {
      where.add("(sn.title ilike ? or i.platform_item_id ilike ?)");
      String pattern = likePattern(query.keyword());
      binds.add(pattern);
      binds.add(pattern);
    }
    if (query.onSale() != null) {
      where.add("sn.on_sale = ?");
      binds.add(query.onSale());
    }
    if (query.favoritedOnly()) {
      where.add("exists (select 1 from supplier_source_favorite f where f.item_id = i.id)");
    }
    if (query.skuId() != null) {
      where.add(
          "exists (select 1 from supplier_source_sku_link lk where lk.item_id = i.id and lk.sku_id = ?)");
      binds.add(query.skuId());
    }
    if (query.priceFrom() != null && query.priceTo() != null) {
      // 区间**重叠**即命中：区间与区间相交，不是「价格落在区间内」。
      where.add("sn.price_max >= ? and sn.price_min <= ?");
      binds.add(price(query.priceFrom()));
      binds.add(price(query.priceTo()));
    } else if (query.priceFrom() != null) {
      where.add("sn.price_max >= ?");
      binds.add(price(query.priceFrom()));
    } else if (query.priceTo() != null) {
      where.add("sn.price_min <= ?");
      binds.add(price(query.priceTo()));
    }
    String clause = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String order = itemOrder(query.sort(), query.desc());
    long total =
        sql.fetchOne(
                "select count(*) from supplier_source_item i"
                    + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                    + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                    + clause,
                binds.toArray())
            .get(0, Long.class);
    Record summary =
        sql.fetchOne(
            "select count(*) filter (where sn.on_sale) as on_sale,"
                + " count(*) filter (where not sn.on_sale) as off_sale,"
                + " count(*) filter (where exists (select 1 from supplier_source_favorite f where f.item_id=i.id)) as favorited,"
                + " count(*) filter (where exists (select 1 from supplier_source_sku_link lk where lk.item_id=i.id)) as linked"
                + " from supplier_source_item i"
                + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id");
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add(page * size);
    List<Record> rows =
        sql.fetch(
                "select i.id,i.stall_id,i.platform_item_id,i.current_snapshot_id,"
                    + " s.name as stall_name,s.platform,s.supplier_name_snapshot,"
                    + " sn.title,sn.price_min,sn.price_max,sn.currency,sn.moq,sn.spec,sn.image_url,"
                    + " sn.item_url,sn.on_sale,sn.collected_at,sn.snapshot_seq,"
                    + " exists (select 1 from supplier_source_favorite f where f.item_id=i.id) as favorited"
                    + " from supplier_source_item i"
                    + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                    + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                    + clause
                    + " order by "
                    + order
                    + " limit ? offset ?",
                pageBinds.toArray());
    // 一次查询取回整页的 SKU 编码（不是每行查一次）。
    Map<UUID, List<String>> skuCodes =
        skuCodesByItem(rows.stream().map(r -> r.get("id", UUID.class)).toList());
    List<SourceItemRow> items =
        rows.stream()
            .map(r -> itemRow(r, skuCodes.getOrDefault(r.get("id", UUID.class), List.of())))
            .toList();
    return new SourceItemPage(
        items,
        page,
        size,
        total,
        count(summary, "on_sale"),
        count(summary, "off_sale"),
        count(summary, "favorited"),
        count(summary, "linked"),
        "价格与起订量是**当前快照**的值，采集时刻就在旁边；比价请用「SKU 货源比价」，"
            + "它按币种分组给出最低价（本模块不跨币种换算）。");
  }

  /** 货源条目详情：条目 + 它的**全部快照版本**（价格历史）。 */
  public SourceItemDetail item(UUID itemId) {
    Record row =
        sql.fetchOne(
            "select i.id,i.stall_id,i.platform_item_id,i.current_snapshot_id,"
                + " s.name as stall_name,s.platform,s.supplier_name_snapshot,"
                + " sn.title,sn.price_min,sn.price_max,sn.currency,sn.moq,sn.spec,sn.image_url,"
                + " sn.item_url,sn.on_sale,sn.collected_at,sn.snapshot_seq,"
                + " exists (select 1 from supplier_source_favorite f where f.item_id=i.id) as favorited"
                + " from supplier_source_item i"
                + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                + " where i.id = ?",
            itemId);
    if (row == null) {
      throw notFound("货源条目 " + itemId + " 不存在或无权查看");
    }
    List<SourceSnapshotRow> snapshots =
        sql.fetch(
                "select sn.id,sn.item_id,sn.snapshot_seq,sn.title,sn.price_min,sn.price_max,sn.currency,"
                    + " sn.moq,sn.spec,sn.image_url,sn.item_url,sn.on_sale,sn.collected_at,sn.collection_id,"
                    + " c.status as collection_status,"
                    + " (sn.id = i.current_snapshot_id) as current"
                    + " from supplier_source_snapshot sn"
                    + " join supplier_source_item i on i.tenant_id=sn.tenant_id and i.id=sn.item_id"
                    + " left join supplier_source_collection c on c.tenant_id=sn.tenant_id and c.id=sn.collection_id"
                    + " where sn.item_id = ? order by sn.snapshot_seq desc",
                itemId)
            .map(SupplierSourceService::snapshotRow);
    return new SourceItemDetail(
        itemRow(row, skuCodesByItem(List.of(itemId)).getOrDefault(itemId, List.of())), snapshots,
        "每一行都是**一次采集**看到的值，旧版本永不改写 —— 这就是「上周三看到的那个价是什么」的答案。");
  }

  // ================================================================ 收藏

  /** 收藏货源（事实，不是临时标记）。 */
  public FavoriteSaved favorite(UUID itemId, FavoriteInput input, UUID userId) {
    if (!itemExists(itemId)) {
      throw notFound("货源条目 " + itemId + " 不存在或无权收藏");
    }
    OffsetDateTime now = now();
    sql.execute(
        "insert into supplier_source_favorite(id,tenant_id,item_id,favorited_by,favorited_at,note,created_at)"
            + " values(?,?,?,?,?::timestamptz,?,?::timestamptz)"
            + " on conflict (tenant_id,item_id) do update set favorited_by=excluded.favorited_by,"
            + " favorited_at=excluded.favorited_at,note=excluded.note",
        UUID.randomUUID(),
        currentTenant(),
        itemId,
        userId,
        now,
        fit(input == null ? null : input.note(), 2000, "备注"),
        now);
    Record row =
        sql.fetchOne(
            "select favorited_at from supplier_source_favorite where item_id = ?", itemId);
    return new FavoriteSaved(itemId, true, instant(row, "favorited_at"));
  }

  /** 取消收藏。 */
  public FavoriteSaved unfavorite(UUID itemId) {
    int deleted =
        sql.execute("delete from supplier_source_favorite where item_id = ?", itemId);
    if (deleted == 0) {
      throw notFound("货源条目 " + itemId + " 没有收藏记录（或无权操作）");
    }
    return new FavoriteSaved(itemId, false, null);
  }

  // ================================================================ SKU 关联与比价

  /**
   * 建立货源与内部 SKU 的关联。
   *
   * <p>SKU 事实（catalog）经 {@link SourceSkuQueryPort} 核对，本库只存逻辑 ID + 编码 / 名称快照。
   */
  public SkuLinkRow linkSku(UUID itemId, SkuLinkInput input, String bearer, UUID userId) {
    if (!itemExists(itemId)) {
      throw notFound("货源条目 " + itemId + " 不存在或无权关联");
    }
    var sku = skus.require(input.skuId(), bearer);
    OffsetDateTime now = now();
    UUID linkId = UUID.randomUUID();
    sql.execute(
        "insert into supplier_source_sku_link(id,tenant_id,item_id,sku_id,sku_code,sku_name,"
            + "linked_at,linked_by,note,created_at) values(?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)"
            + " on conflict (tenant_id,item_id,sku_id) do update set sku_code=excluded.sku_code,"
            + "sku_name=excluded.sku_name,linked_at=excluded.linked_at,linked_by=excluded.linked_by,"
            + "note=excluded.note",
        linkId,
        currentTenant(),
        itemId,
        sku.id(),
        fit(sku.code(), 64, "SKU 编码"),
        fit(sku.name(), 200, "SKU 名称"),
        now,
        userId,
        fit(input.note(), 2000, "备注"),
        now);
    return skuLinkRow(
        sql.fetchOne(
            "select l.id,l.item_id,l.sku_id,l.sku_code,l.sku_name,l.linked_at,l.note,"
                + " i.platform_item_id,i.stall_id,s.name as stall_name,s.platform,s.supplier_name_snapshot,"
                + " sn.title,sn.price_min,sn.price_max,sn.currency,sn.moq,sn.spec,sn.collected_at,sn.on_sale,"
                + " exists (select 1 from supplier_source_favorite f where f.item_id=i.id) as favorited"
                + " from supplier_source_sku_link l"
                + " join supplier_source_item i on i.tenant_id=l.tenant_id and i.id=l.item_id"
                + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                + " where l.item_id = ? and l.sku_id = ?",
            itemId,
            sku.id()));
  }

  /** 解除货源与内部 SKU 的关联。 */
  public Map<String, Boolean> unlinkSku(UUID itemId, UUID linkId) {
    int deleted =
        sql.execute(
            "delete from supplier_source_sku_link where id = ? and item_id = ?", linkId, itemId);
    if (deleted == 0) {
      throw notFound("该 SKU 关联不存在或无权解除");
    }
    return Map.of("deleted", true);
  }

  /**
   * 某内部 SKU 的货源关联视图 + 比价。
   *
   * <p>排序口径：**先按币种、再按价格下限升序**。刻意**不跨币种比价** —— 汇率属 12 财务，
   * 本模块没有汇率快照，把 ¥ 与 $ 放在一起排序就是在编一个假的「最低价」。
   */
  public SkuSourceView skuSources(UUID skuId) {
    Record link =
        sql.fetchOne(
            "select sku_code, sku_name from supplier_source_sku_link where sku_id = ? limit 1", skuId);
    List<Record> rows =
        sql.fetch(
            "select i.id as item_id,i.stall_id,s.name as stall_name,s.platform,s.supplier_name_snapshot,"
                + " i.platform_item_id,sn.title,sn.price_min,sn.price_max,sn.currency,sn.moq,sn.spec,"
                + " sn.collected_at,sn.on_sale,"
                + " exists (select 1 from supplier_source_favorite f where f.item_id=i.id) as favorited"
                + " from supplier_source_sku_link l"
                + " join supplier_source_item i on i.tenant_id=l.tenant_id and i.id=l.item_id"
                + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                + " where l.sku_id = ?"
                + " order by sn.currency, sn.price_min, sn.price_max, i.id",
            skuId);
    long linkCount =
        sql.fetchOne("select count(*) from supplier_source_sku_link where sku_id = ?", skuId)
            .get(0, Long.class);
    List<SkuCandidate> candidates = new ArrayList<>();
    List<LowestPrice> lowest = new ArrayList<>();
    // 先按币种、再按价格下限升序取候选（SQL 已排序），最低价在**整批**候选上算一遍 ——
    // 边遍历边判「最低」会在后面出现更便宜的一条时把上一条也标成最低。
    Map<String, LowestPrice> bestByCurrency = new LinkedHashMap<>();
    for (Record row : rows) {
      String rowCurrency = row.get("currency", String.class);
      BigDecimal priceMin = decimal(row, "price_min");
      BigDecimal priceMax = decimal(row, "price_max");
      BigDecimal moq = decimal(row, "moq");
      LowestPrice current = bestByCurrency.get(rowCurrency);
      if (current == null
          || priceMin.compareTo(current.priceMin()) < 0
          || (priceMin.compareTo(current.priceMin()) == 0
              && priceMax.compareTo(current.priceMax()) < 0)) {
        bestByCurrency.put(
            rowCurrency,
            new LowestPrice(
                rowCurrency,
                row.get("item_id", UUID.class),
                row.get("title", String.class),
                priceMin,
                priceMax,
                moq));
      }
      candidates.add(
          new SkuCandidate(
              row.get("item_id", UUID.class),
              row.get("stall_id", UUID.class),
              row.get("stall_name", String.class),
              row.get("platform", String.class),
              platform(row.get("platform", String.class)).label(),
              row.get("platform_item_id", String.class),
              row.get("title", String.class),
              row.get("supplier_name_snapshot", String.class),
              priceMin,
              priceMax,
              rowCurrency,
              moq,
              row.get("spec", String.class),
              instant(row, "collected_at"),
              Boolean.TRUE.equals(row.get("on_sale", Boolean.class)),
              Boolean.TRUE.equals(row.get("favorited", Boolean.class)),
              false));
    }
    // 标出每个币种下的最低价候选。
    List<SkuCandidate> marked = new ArrayList<>();
    for (var candidate : candidates) {
      LowestPrice best = bestByCurrency.get(candidate.currency());
      boolean isLowest = best != null && best.itemId().equals(candidate.itemId());
      marked.add(
          new SkuCandidate(
              candidate.itemId(),
              candidate.stallId(),
              candidate.stallName(),
              candidate.platform(),
              candidate.platformLabel(),
              candidate.platformItemId(),
              candidate.title(),
              candidate.supplierName(),
              candidate.priceMin(),
              candidate.priceMax(),
              candidate.currency(),
              candidate.moq(),
              candidate.spec(),
              candidate.collectedAt(),
              candidate.onSale(),
              candidate.favorited(),
              isLowest));
    }
    candidates = marked;
    lowest.addAll(bestByCurrency.values());
    return new SkuSourceView(
        skuId,
        link == null ? "" : nullToEmpty(link.get("sku_code", String.class)),
        link == null ? "" : nullToEmpty(link.get("sku_name", String.class)),
        linkCount,
        candidates,
        lowest,
        "比价按**币种分组**给出最低价：本模块不跨币种换算（汇率属 12 财务），"
            + "所以「最便宜」只在同一币种内成立。价格是采集时刻的快照值，"
            + "下单时必须以实际下单金额为准。");
  }

  // ================================================================ 采集接入登记

  /**
   * 供应商候选（建档口时选供应商用）。
   *
   * <p>事实来源是 catalog 的授权只读接口（6.1.1.1 供应商列表）；取不到就<b>显式失败</b>，
   * 绝不返回空清单冒充「这个租户一个供应商都没有」。
   */
  public List<SupplierCandidateRow> supplierCandidates(String keyword, String bearer) {
    return parties.suppliers(keyword, bearer).stream()
        .map(
            view ->
                new SupplierCandidateRow(
                    view.roleId(), view.partyCode(), view.partyName(), view.partyStatus()))
        .toList();
  }

  /** 某个平台的采集接入登记（未登记 = 未接线）。 */
  public CollectorEndpointRow collectorEndpoint(String platformCode) {
    SourcePlatform platform = platform(platformCode);
    Record row =
        sql.fetchOne(
            "select base_url,credential_ref,wired,status,note,updated_at"
                + " from supplier_source_collector_endpoint where platform = ?",
            platform.name());
    if (row == null) {
      return new CollectorEndpointRow(
          platform.name(),
          platform.label(),
          "",
          "",
          false,
          "ACTIVE",
          "未登记：主动抓取会显式失败并写明缺什么。",
          null);
    }
    return new CollectorEndpointRow(
        platform.name(),
        platform.label(),
        nullToEmpty(row.get("base_url", String.class)),
        nullToEmpty(row.get("credential_ref", String.class)),
        Boolean.TRUE.equals(row.get("wired", Boolean.class)),
        nullToEmpty(row.get("status", String.class)),
        nullToEmpty(row.get("note", String.class)),
        instant(row, "updated_at"));
  }

  /** 所有平台的采集接入登记。 */
  public List<CollectorEndpointRow> collectorEndpoints() {
    List<CollectorEndpointRow> rows = new ArrayList<>();
    for (var platform : SourcePlatform.values()) {
      rows.add(collectorEndpoint(platform.name()));
    }
    return rows;
  }

  /**
   * 登记 / 更新采集接入。
   *
   * <p>只存**引用**（secret:// / kv:// / vault:// / external-secrets:// 开头），DB 层用 CHECK 钉死前缀 ——
   * 粘一个真实 token 在写库那一刻就被拒。
   */
  public CollectorEndpointSaved saveCollectorEndpoint(
      String platformCode, CollectorEndpointInput input) {
    SourcePlatform platform = platform(platformCode);
    String baseUrl = fit(input.baseUrl(), 300, "网关地址");
    String credentialRef = fit(input.credentialRef(), 300, "凭证引用");
    boolean wired = Boolean.TRUE.equals(input.wired());
    if (wired && (baseUrl.isEmpty() || credentialRef.isEmpty())) {
      throw bad("声称已接线就必须同时给出网关地址与凭证引用（只给一个，调用必然失败）");
    }
    if (!credentialRef.isEmpty()
        && !credentialRef.matches("^(secret://|kv://|vault://|external-secrets://).+")) {
      throw bad(
          "凭证只能是**引用**（secret:// / kv:// / vault:// / external-secrets:// 开头），"
              + "本系统不保存任何明文密钥");
    }
    OffsetDateTime now = now();
    sql.execute(
        "insert into supplier_source_collector_endpoint(id,tenant_id,platform,base_url,credential_ref,"
            + "status,wired,note,created_at,updated_at) values(?,?,?,?,?,'ACTIVE',?,?,?::timestamptz,"
            + "?::timestamptz) on conflict (tenant_id,platform) do update set base_url=excluded.base_url,"
            + "credential_ref=excluded.credential_ref,wired=excluded.wired,note=excluded.note,"
            + "updated_at=excluded.updated_at",
        UUID.randomUUID(),
        currentTenant(),
        platform.name(),
        baseUrl,
        credentialRef,
        wired,
        fit(input.note(), 2000, "备注"),
        now,
        now);
    return new CollectorEndpointSaved(platform.name(), baseUrl, credentialRef, wired, 0);
  }

  // ================================================================ 工具

  private List<SourceItemRow> itemsOfStall(UUID stallId) {
    List<Record> rows =
        sql.fetch(
            "select i.id,i.stall_id,i.platform_item_id,i.current_snapshot_id,"
                + " s.name as stall_name,s.platform,s.supplier_name_snapshot,"
                + " sn.title,sn.price_min,sn.price_max,sn.currency,sn.moq,sn.spec,sn.image_url,"
                + " sn.item_url,sn.on_sale,sn.collected_at,sn.snapshot_seq,"
                + " exists (select 1 from supplier_source_favorite f where f.item_id=i.id) as favorited"
                + " from supplier_source_item i"
                + " join supplier_source_stall s on s.tenant_id=i.tenant_id and s.id=i.stall_id"
                + " join supplier_source_snapshot sn on sn.tenant_id=i.tenant_id and sn.id=i.current_snapshot_id"
                + " where i.stall_id = ? order by sn.collected_at desc, i.id",
            stallId);
    Map<UUID, List<String>> skuCodes = skuCodesByItem(rows.stream().map(r -> r.get("id", UUID.class)).toList());
    return rows.stream()
        .map(r -> itemRow(r, skuCodes.getOrDefault(r.get("id", UUID.class), List.of())))
        .toList();
  }

  /**
   * 一批货源各自关联到的 SKU 编码。
   *
   * <p>一次查询取回整批（不是每行查一次）—— 列表页每行查一次会把 200 行的页面打成 200 次往返。
   */
  private Map<UUID, List<String>> skuCodesByItem(List<UUID> itemIds) {
    Map<UUID, List<String>> grouped = new LinkedHashMap<>();
    if (itemIds.isEmpty()) {
      return grouped;
    }
    String placeholders = String.join(",", Collections.nCopies(itemIds.size(), "?"));
    sql.fetch(
            "select item_id, sku_code from supplier_source_sku_link where item_id in ("
                + placeholders
                + ") order by sku_code",
            itemIds.toArray())
        .forEach(
            r -> {
              UUID id = r.get("item_id", UUID.class);
              grouped.computeIfAbsent(id, k -> new ArrayList<>()).add(r.get("sku_code", String.class));
            });
    return grouped;
  }

  private boolean itemExists(UUID itemId) {
    return sql.fetchOne("select 1 from supplier_source_item where id = ?", itemId) != null;
  }

  private boolean exists(String sqlText, Object... binds) {
    return sql.fetchOne(sqlText, binds) != null;
  }

  private long failedCollectionCount() {
    return sql.fetchOne("select count(*) from supplier_source_collection where status='FAILED'")
        .get(0, Long.class);
  }

  private static String itemOrder(String sort, boolean desc) {
    String direction = desc ? " desc" : " asc";
    String key = sort == null ? "" : sort.trim();
    return switch (key) {
      case "priceMin" -> "sn.price_min" + direction + ", sn.price_max" + direction + ", i.id";
      case "moq" -> "sn.moq" + direction + ", sn.price_min" + direction + ", i.id";
      case "title" -> "sn.title" + direction + ", i.id";
      default -> "sn.collected_at desc, i.id";
    };
  }

  private static StallRow stallRow(Record r) {
    return new StallRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("platform", String.class),
        platform(r.get("platform", String.class)).label(),
        r.get("platform_stall_id", String.class),
        r.get("name", String.class),
        nullToEmpty(r.get("url", String.class)),
        r.get("collect_method", String.class),
        collectMethod(r.get("collect_method", String.class)).label(),
        r.get("status", String.class),
        stallStatus(r.get("status", String.class)).label(),
        r.get("supplier_party_id", UUID.class),
        nullToEmpty(r.get("supplier_name_snapshot", String.class)),
        instant(r, "last_collected_at"),
        r.get("last_collection_status", String.class),
        nullToEmpty(r.get("last_failure_reason", String.class)),
        count(r, "item_count"),
        count(r, "favorite_count"),
        count(r, "sku_link_count"),
        nullToEmpty(r.get("note", String.class)),
        r.get("version", Long.class) == null ? 0L : r.get("version", Long.class));
  }

  private static SourceItemRow itemRow(Record r, List<String> skuCodes) {
    return new SourceItemRow(
        r.get("id", UUID.class),
        r.get("stall_id", UUID.class),
        nullToEmpty(r.get("stall_name", String.class)),
        r.get("platform", String.class),
        platform(r.get("platform", String.class)).label(),
        r.get("platform_item_id", String.class),
        nullToEmpty(r.get("title", String.class)),
        decimal(r, "price_min"),
        decimal(r, "price_max"),
        nullToEmpty(r.get("currency", String.class)),
        decimal(r, "moq"),
        nullToEmpty(r.get("spec", String.class)),
        nullToEmpty(r.get("image_url", String.class)),
        nullToEmpty(r.get("item_url", String.class)),
        Boolean.TRUE.equals(r.get("on_sale", Boolean.class)),
        instant(r, "collected_at"),
        r.get("snapshot_seq", Integer.class) == null ? 0 : r.get("snapshot_seq", Integer.class),
        Boolean.TRUE.equals(r.get("favorited", Boolean.class)),
        nullToEmpty(r.get("supplier_name_snapshot", String.class)),
        skuCodes,
        r.get("current_snapshot_id", UUID.class));
  }

  private static SourceSnapshotRow snapshotRow(Record r) {
    return new SourceSnapshotRow(
        r.get("id", UUID.class),
        r.get("item_id", UUID.class),
        r.get("snapshot_seq", Integer.class) == null ? 0 : r.get("snapshot_seq", Integer.class),
        nullToEmpty(r.get("title", String.class)),
        decimal(r, "price_min"),
        decimal(r, "price_max"),
        nullToEmpty(r.get("currency", String.class)),
        decimal(r, "moq"),
        nullToEmpty(r.get("spec", String.class)),
        nullToEmpty(r.get("image_url", String.class)),
        nullToEmpty(r.get("item_url", String.class)),
        Boolean.TRUE.equals(r.get("on_sale", Boolean.class)),
        instant(r, "collected_at"),
        r.get("collection_id", UUID.class),
        nullToEmpty(r.get("collection_status", String.class)),
        Boolean.TRUE.equals(r.get("current", Boolean.class)));
  }

  private static CollectionRow collectionRow(Record r) {
    return new CollectionRow(
        r.get("id", UUID.class),
        r.get("stall_id", UUID.class),
        r.get("collect_method", String.class),
        collectMethod(r.get("collect_method", String.class)).label(),
        r.get("status", String.class),
        CollectionStatus.valueOf(r.get("status", String.class)).label(),
        r.get("trigger_source", String.class),
        TriggerSource.valueOf(r.get("trigger_source", String.class)).label(),
        instant(r, "collected_at"),
        instant(r, "started_at"),
        instant(r, "finished_at"),
        count(r, "item_count"),
        count(r, "skipped_count"),
        nullToEmpty(r.get("skipped_reason", String.class)),
        nullToEmpty(r.get("failure_reason", String.class)));
  }

  private static SkuLinkRow skuLinkRow(Record r) {
    return new SkuLinkRow(
        r.get("id", UUID.class),
        r.get("item_id", UUID.class),
        nullToEmpty(r.get("platform_item_id", String.class)),
        nullToEmpty(r.get("title", String.class)),
        r.get("stall_id", UUID.class),
        nullToEmpty(r.get("stall_name", String.class)),
        r.get("platform", String.class),
        nullToEmpty(r.get("supplier_name_snapshot", String.class)),
        decimal(r, "price_min"),
        decimal(r, "price_max"),
        nullToEmpty(r.get("currency", String.class)),
        decimal(r, "moq"),
        nullToEmpty(r.get("spec", String.class)),
        instant(r, "collected_at"),
        Boolean.TRUE.equals(r.get("on_sale", Boolean.class)),
        Boolean.TRUE.equals(r.get("favorited", Boolean.class)),
        instant(r, "linked_at"),
        nullToEmpty(r.get("note", String.class)));
  }

  private static List<SupplierSourceOption> platformOptions() {
    List<SupplierSourceOption> options = new ArrayList<>();
    for (var platform : SourcePlatform.values()) {
      options.add(new SupplierSourceOption(platform.name(), platform.label(), platformHint(platform)));
    }
    return options;
  }

  private static List<SupplierSourceOption> collectMethodOptions() {
    return List.of(
        new SupplierSourceOption(
            CollectMethod.URL_SYNC.name(),
            CollectMethod.URL_SYNC.label(),
            "本服务按档口 URL 主动抓取，需要先登记该平台的采集接入（网关地址 + 凭证引用）。"),
        new SupplierSourceOption(
            CollectMethod.PLUGIN_PUSH.name(),
            CollectMethod.PLUGIN_PUSH.label(),
            "外部插件把采到的货源条目推上来；不需要任何外部凭证。"));
  }

  private static String platformHint(SourcePlatform platform) {
    return switch (platform) {
      case ALIBABA_1688 -> "1688 开放平台：默认未接线，需登记网关地址与凭证引用后主动抓取。";
      case YIWU_GOU -> "义乌购：默认未接线，可改用插件采集推送。";
    };
  }

  private static String joinSkipped(List<String> skipped) {
    if (skipped.isEmpty()) {
      return "";
    }
    return String.join("；", skipped);
  }

  /** LIKE 通配符转义：搜 `%` 不会变成匹配全部。 */
  private static String likePattern(String keyword) {
    String escaped =
        keyword.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    return "%" + escaped + "%";
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private static String instant(Record r, String column) {
    OffsetDateTime value = r.get(column, OffsetDateTime.class);
    return value == null ? null : value.toString();
  }

  private static long count(Record r, String column) {
    Object value = r.get(column);
    return value == null ? 0L : ((Number) value).longValue();
  }

  /** 读金额 / 数量。纯文本 SQL 里的表达式列 jOOQ 拿不到列类型，收口成不依赖驱动返回类型的读法。 */
  private static BigDecimal decimal(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }

  private static BigDecimal price(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(PRICE_SCALE, RoundingMode.HALF_UP)
        : value.setScale(PRICE_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal quantity(BigDecimal value) {
    return value == null ? BigDecimal.ZERO.setScale(QTY_SCALE, RoundingMode.HALF_UP)
        : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw new IllegalArgumentException(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value.trim();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  private static void requireVersion(long actual, long expected) {
    if (actual != expected) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "档口已被他人修改（当前版本 " + actual + "，你的版本 " + expected + "）。请刷新后重试，绝不静默覆盖");
    }
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  private static SourcePlatform platform(String value) {
    try {
      return SourcePlatform.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的货源平台「" + value + "」：只能是 ALIBABA_1688 / YIWU_GOU");
    }
  }

  private static CollectMethod collectMethod(String value) {
    try {
      return CollectMethod.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的采集方式「" + value + "」：只能是 URL_SYNC / PLUGIN_PUSH");
    }
  }

  private static StallStatus stallStatus(String value) {
    try {
      return StallStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的档口状态「" + value + "」：只能是 ACTIVE / PAUSED");
    }
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC
   * **复位成空串**而不是「回到未设置」，直接 cast 会报 uuid 语法错（与 V92 同一句写法）。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }
}
