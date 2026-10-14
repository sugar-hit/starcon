package com.starcon.trade;

import static com.starcon.trade.FulfillmentRoutingModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.3.2.2 FBA 与自发货选择：路径评估、决策落库与缺货切换留痕。
 *
 * <p><b>四条实现纪律</b>：
 *
 * <ol>
 *   <li><b>判定只有一处</b>：{@link #evaluate} 是唯一的判定实现， {@code decide} 落库时也调它 —— 页面预览与实际落库结论不一致时，
 *       运营看到的和系统做的是两件事，这是最不能容忍的一种不一致。
 *   <li><b>金额只有一处口径</b>：结算一律 {@code setScale(2, HALF_UP)}， DB 侧 {@code round(x,2)} 复核（照抄 10.5.1.3）。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 集中在语句最前面的 CTE 里， cast 不出现在绑定序列末位。
 *   <li><b>不用 JPA 懒加载</b>：全部 jOOQ 显式 SQL，聚合在 DB 侧完成。
 * </ol>
 *
 * <p><b>为什么自发货路径在没有 MCF 资质时一律不可行</b>：蓝图关系段加粗句 「平台仓缺货自动转自发货是高价值能力，但受平台政策约束，必须先验证该店铺的
 * 多渠道配送资质」。所以「资质未核验」在本模块等于「不许切」， 而不是「默认可以、出了问题再说」—— 后者会把一次库存缺口变成一次平台违规。
 */
@Service
public class FulfillmentRoutingService {

  /** 单页最大行数。决策与切换都是配置/凭据，不是流水，但仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QTY_SCALE = 6;

  /** 金额刻度：与 DB 的 numeric(19,4) 对齐。 */
  private static final int MONEY_SCALE = 4;

  /** 结算口径：统一 HALF_UP 到分（与 10.5.1.3 同一约定，DB 侧 round(x,2) 复核）。 */
  private static final int SETTLE_SCALE = 2;

  private static final BigDecimal ZERO_QTY = new BigDecimal("0.000000");
  private static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");

  private final DSLContext sql;

  public FulfillmentRoutingService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上游可用性。
   *
   * <p>{@code crossServiceReadAvailable=false} 是如实报出：7.1.3.1 与 2.1.1.2 **均已交付**，但它们分别属于 warehouse /
   * channel 的数据库，AGENTS.md 禁止 跨服务 SQL，所以本模块把这两类输入落成自维护的真实表并标注来源， 而不是假装能直接读到上游。
   */
  public FulfillmentRoutingCapabilities capabilities() {
    return new FulfillmentRoutingCapabilities(
        List.of(
            new FulfillmentRoutingCapabilities.Dimension(
                "CHANNEL_STOCK", "各渠道库存", true, "7.1.3.1 多仓分布（warehouse 服务，已交付）→ 本服务自维护快照", null),
            new FulfillmentRoutingCapabilities.Dimension(
                "MCF_PERMISSION",
                "平台多渠道配送权限",
                true,
                "2.1.1.2 平台能力矩阵（channel 服务，已交付）→ 本服务自维护资质登记",
                null),
            new FulfillmentRoutingCapabilities.Dimension(
                "COST_LEADTIME",
                "成本与时效对比",
                true,
                "10.1.2.1 运费计算器（**未开发**）→ 本服务租户维护参数表",
                "10 运费试算尚未交付，成本与时效口径由租户在本模块维护；" + "本模块只在**同币种**下比较路径，不做跨币种换算"),
            new FulfillmentRoutingCapabilities.Dimension(
                "ORDER_LINE",
                "订单行（决策对象）",
                false,
                "9.1.1.2 订单详情（trade 服务，未开发）",
                "订单表尚未交付，因此本模块只存 orderRef / orderLineRef 逻辑引用，" + "不校验订单存在性；订单落地后由那条车道补引用或外键"),
            new FulfillmentRoutingCapabilities.Dimension(
                "FULFILLMENT_REQUEST",
                "履约请求下发",
                false,
                "9.3.3.1 履约请求（trade 服务，未开发）",
                "决策可确认并落库，但尚未下发到履约请求；下发由 9.3.3.1 负责")),
        List.of(PathKind.PLATFORM_FBA, PathKind.MERCHANT_OWN, PathKind.THIRD_PARTY),
        List.of(
            RoutingPolicy.PLATFORM_FIRST, RoutingPolicy.COST_FIRST, RoutingPolicy.MERCHANT_FIRST),
        List.of(SwitchTriggerReason.values()),
        false,
        "7.1.3.1 与 2.1.1.2 已交付但分属 warehouse / channel 的独立数据库，"
            + "按模块边界纪律不得跨库读取；本模块把渠道库存与 MCF 资质登记为租户可维护的真实表，"
            + "并用 stock_source / authorization_source 如实标注来源。"
            + "16 合规与渠道侧正式接口到位后，由同步器按同一形状写入并把来源字段改为 MODULE_* 即可，"
            + "本模块的表结构与判定逻辑不需要改动。",
        "缺货切换记录是本模块的硬输出：选了非平台仓的决策若没有切换记录，" + "提交时会被延迟约束触发器拒绝（23514）。");
  }

  // ================================================================ MCF 资质

  /** MCF 资质列表（真分页，total 与分页无关）。 */
  public McfPage pageMcf(McfQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim());
    }
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      where.add("(platform_code ilike ? or shop_code ilike ? or note ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 3; i++) {
        binds.add(k);
      }
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_mcf_authorization" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<McfAuthorizationRow> items =
        sql.fetch(
                "select id,platform_code,shop_code,status,authorization_source,source_reference,"
                    + "verified_at,expires_at,note,created_by_membership_id,created_at,updated_at"
                    + " from fulfillment_mcf_authorization"
                    + sqlWhere
                    + " order by platform_code,shop_code,id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentRoutingService::mcfRow);
    return new McfPage(items, total, pageNo, size);
  }

  /**
   * 登记 / 更新一个店铺在一个平台上的 MCF 资质。
   *
   * <p>「已授权」必须给出处：{@code verifiedAt} 非空，且要么是外部来源单号、 要么有说明。少了出处，一次缺货自动切自发货就没有合规依据 ——
   * 平台判违规时没人能说清凭什么认为这店有资质。
   */
  @Transactional
  public McfSaved saveMcf(McfAuthorizationInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    McfStatus status = mcfStatus(input.status());
    OffsetDateTime verifiedAt = input.verifiedAt();
    if (status == McfStatus.AUTHORIZED) {
      if (verifiedAt == null) {
        throw bad("登记「已授权」必须给 verifiedAt：没有核验时间的授权是一句没有依据的断言");
      }
      if (!notBlank(input.sourceReference()) && !notBlank(input.note())) {
        throw bad("登记「已授权」必须给出来源单号（sourceReference）或至少一句说明（note）：" + "缺货自动切自发货受平台政策约束，这个出处就是合规依据");
      }
    }
    OffsetDateTime now = OffsetDateTime.now();
    UUID id = UUID.randomUUID();
    // upsert：同一 (平台,店铺) 只有一条当前资质（DB 唯一约束兜底），
    // 这里先查后写是为了给出「新增」还是「更新」的可读结果。
    var existing =
        sql.fetchOne(
            "select id from fulfillment_mcf_authorization where platform_code = ? and shop_code = ?",
            input.platformCode().trim(),
            input.shopCode().trim());
    boolean created = existing == null;
    if (created) {
      sql.execute(
          "insert into fulfillment_mcf_authorization"
              + "(id,tenant_id,platform_code,shop_code,status,authorization_source,source_reference,"
              + "verified_at,verified_by_membership_id,expires_at,note,created_by_membership_id,"
              + "created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?::timestamptz,?,?::timestamptz,?,?,?::timestamptz,?::timestamptz)",
          id,
          input.platformCode().trim(),
          input.shopCode().trim(),
          status.name(),
          "TENANT_MAINTAINED",
          nz(input.sourceReference()),
          verifiedAt,
          actorId,
          input.expiresAt(),
          nz(input.note()),
          actorId,
          now,
          now);
    } else {
      id = existing.get(0, UUID.class);
      sql.execute(
          "update fulfillment_mcf_authorization set status = ?, authorization_source = ?,"
              + " source_reference = ?, verified_at = ?, verified_by_membership_id = ?,"
              + " expires_at = ?, note = ?, version = version + 1, updated_at = ?::timestamptz"
              + " where id = ?",
          status.name(),
          "TENANT_MAINTAINED",
          nz(input.sourceReference()),
          verifiedAt,
          status == McfStatus.AUTHORIZED ? actorId : null,
          input.expiresAt(),
          nz(input.note()),
          now,
          id);
    }
    return new McfSaved(
        id,
        input.platformCode().trim(),
        input.shopCode().trim(),
        status,
        created ? "资质已登记" : "资质已更新");
  }

  // ================================================================ 成本与时效参数

  /** 成本参数列表（真分页）。 */
  public CostProfilePage pageCostProfiles(CostProfileQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim());
    }
    if (notBlank(query.pathKind())) {
      where.add("path_kind = ?");
      binds.add(pathKind(query.pathKind()).name());
    }
    if (notBlank(query.destinationCountryCode())) {
      where.add("destination_country_code = ?");
      binds.add(query.destinationCountryCode().trim().toUpperCase(Locale.ROOT));
    }
    if (Boolean.TRUE.equals(query.effectiveOnly())) {
      where.add("effective_from <= now() and (effective_to is null or effective_to > now())");
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_path_cost_profile" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<CostProfileRow> items =
        sql.fetch(
                "select id,platform_code,path_kind,destination_country_code,unit_cost,lead_time_days,"
                    + "platform_fee,currency_code,effective_from,effective_to,notes,created_at,updated_at"
                    + " from fulfillment_path_cost_profile"
                    + sqlWhere
                    + " order by platform_code,path_kind,destination_country_code,id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentRoutingService::costRow);
    return new CostProfilePage(items, total, pageNo, size);
  }

  /** 维护一条成本与时效参数。 */
  @Transactional
  public CostProfileSaved saveCostProfile(CostProfileInput input) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    PathKind path = pathKind(input.pathKind());
    if (input.unitCost().signum() < 0) {
      throw bad("单件成本不能为负：负成本会让「成本优先」永远选它");
    }
    if (input.leadTimeDays() != null && input.leadTimeDays().signum() < 0) {
      throw bad("预计时效不能为负");
    }
    String country = input.destinationCountryCode().trim().toUpperCase(Locale.ROOT);
    if (!country.equals("*") && country.length() != 2) {
      throw bad("目的国必须是两位国家码，或用 * 表示不限");
    }
    BigDecimal unitCost = money(input.unitCost());
    BigDecimal fee = money(input.platformFee() == null ? ZERO_MONEY : input.platformFee());
    BigDecimal lead =
        input.leadTimeDays() == null
            ? null
            : input.leadTimeDays().setScale(2, RoundingMode.HALF_UP);
    OffsetDateTime from =
        input.effectiveFrom() == null ? OffsetDateTime.now() : input.effectiveFrom();
    OffsetDateTime to = input.effectiveTo();
    if (to != null && !to.isAfter(from)) {
      throw bad("失效时间必须晚于生效时间");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from fulfillment_path_cost_profile"
                + " where platform_code = ? and path_kind = ? and destination_country_code = ?",
            input.platformCode().trim(),
            path.name(),
            country);
    if (existing == null) {
      sql.execute(
          "insert into fulfillment_path_cost_profile"
              + "(id,tenant_id,platform_code,path_kind,destination_country_code,unit_cost,"
              + "lead_time_days,platform_fee,currency_code,effective_from,effective_to,notes,"
              + "updated_by_membership_id,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?::timestamptz,?::timestamptz)",
          id,
          input.platformCode().trim(),
          path.name(),
          country,
          unitCost,
          lead,
          fee,
          "USD",
          from,
          to,
          nz(input.notes()),
          null,
          now,
          now);
    } else {
      id = existing.get(0, UUID.class);
      sql.execute(
          "update fulfillment_path_cost_profile set unit_cost = ?, lead_time_days = ?,"
              + " platform_fee = ?, effective_from = ?::timestamptz, effective_to = ?::timestamptz,"
              + " notes = ?, version = version + 1, updated_at = ?::timestamptz where id = ?",
          unitCost,
          lead,
          fee,
          from,
          to,
          nz(input.notes()),
          now,
          id);
    }
    return new CostProfileSaved(
        id, input.platformCode().trim(), path, path.label() + " 的成本与时效参数已保存");
  }

  // ================================================================ 渠道库存快照

  /** 库存快照列表（真分页）。 */
  public StockSnapshotPage pageStockSnapshots(StockSnapshotQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim());
    }
    if (notBlank(query.shopCode())) {
      where.add("shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add("sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.pathKind())) {
      where.add("path_kind = ?");
      binds.add(pathKind(query.pathKind()).name());
    }
    if (notBlank(query.stockSource())) {
      where.add("stock_source = ?");
      binds.add(query.stockSource().trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_platform_stock_snapshot" + sqlWhere,
                binds.toArray())
            .get("c", Long.class);
    List<StockSnapshotRow> items =
        sql.fetch(
                "select id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,warehouse_name,"
                    + "available_qty,collected_at,stock_source,source_reference,notes,created_at"
                    + " from fulfillment_platform_stock_snapshot"
                    + sqlWhere
                    + " order by collected_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentRoutingService::stockRow);
    return new StockSnapshotPage(items, total, pageNo, size);
  }

  /** 登记一条渠道库存快照。 */
  @Transactional
  public StockSnapshotSaved saveStockSnapshot(StockSnapshotInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    PathKind path = pathKind(input.pathKind());
    if (input.availableQty().signum() < 0) {
      throw bad("可用量不能为负");
    }
    String source =
        notBlank(input.stockSource())
            ? input.stockSource().trim().toUpperCase(Locale.ROOT)
            : "TENANT_MAINTAINED";
    if (!source.equals("TENANT_MAINTAINED") && !source.equals("MODULE_7_1_3_1")) {
      throw bad("stockSource 只能是 TENANT_MAINTAINED 或 MODULE_7_1_3_1");
    }
    if (source.equals("TENANT_MAINTAINED")
        && !notBlank(input.sourceReference())
        && !notBlank(input.warehouseRef())) {
      throw bad(
          "租户自维护的库存快照必须给出来源单号（sourceReference）或仓库引用（warehouseRef）：" + "没有出处的库存等于凭空捏一个数去决定走哪条路");
    }
    BigDecimal qty = qty(input.availableQty());
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into fulfillment_platform_stock_snapshot"
            + "(id,tenant_id,platform_code,shop_code,sku_ref,path_kind,warehouse_ref,warehouse_name,"
            + "available_qty,collected_at,stock_source,source_reference,notes,created_by_membership_id)"
            + " values (?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?)",
        id,
        input.platformCode().trim(),
        input.shopCode().trim(),
        input.skuRef().trim(),
        path.name(),
        nz(input.warehouseRef()),
        nz(input.warehouseName()),
        qty,
        input.collectedAt(),
        source,
        nz(input.sourceReference()),
        nz(input.notes()),
        actorId);
    return new StockSnapshotSaved(id, input.skuRef().trim(), path, "库存快照已登记");
  }

  // ================================================================ 路径评估（判定唯一实现）

  /**
   * 只读评估：给一组输入，算出「应该走哪条路、为什么、三条候选分别如何」。
   *
   * <p><b>不落库</b>：页面用它做预演。落库走 {@link #decide}，那里也调本方法 —— 预览与落库结论必须同源，否则运营看到的和系统做的是两件事。
   *
   * <p>判定顺序刻意是「资格 → 库存 → 成本 → 时效」： 资质不过时任何成本优势都不成立（平台会判违规）， 所以 MCF 闸门排在成本比较**之前**而不是之后。
   */
  public RoutingEvaluation evaluate(RoutingEvaluationRequest request) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    return evaluate(
        request.platformCode(),
        request.shopCode(),
        request.skuRef(),
        request.requestedQty(),
        RoutingPolicy.PLATFORM_FIRST,
        false,
        request.platformAvailableQty(),
        request.at());
  }

  /**
   * 判定主体。
   *
   * <p>{@code platformAvailableOverride} 非空时用它代替库存快照 —— 落库路径会把刚写下的快照算进去，预览路径则允许运营手工试一个数。
   * 两条路径共用同一段判定，避免「预览说走 A、真跑走 B」。
   */
  private RoutingEvaluation evaluate(
      String platformCode,
      String shopCode,
      String skuRef,
      BigDecimal requestedQty,
      RoutingPolicy policy,
      boolean forceMerchantPath,
      BigDecimal platformAvailableOverride,
      OffsetDateTime at) {
    if (requestedQty == null || requestedQty.signum() <= 0) {
      throw bad("请求数量必须大于 0");
    }
    OffsetDateTime now = at == null ? OffsetDateTime.now() : at;
    BigDecimal requested = qty(requestedQty);

    // ---- 1. MCF 资质闸门（DB 词表与本枚举同源，取不到就是没有登记）
    var mcf =
        sql.fetchOne(
            "select id,status,expires_at from fulfillment_mcf_authorization"
                + " where platform_code = ? and shop_code = ?",
            platformCode.trim(),
            shopCode.trim());
    McfStatus mcfStatus =
        mcf == null ? McfStatus.UNKNOWN : mcfStatus(mcf.get("status", String.class));
    OffsetDateTime mcfExpires = mcf == null ? null : mcf.get("expires_at", OffsetDateTime.class);
    UUID mcfId = mcf == null ? null : mcf.get("id", UUID.class);
    boolean mcfExpired = mcfExpires != null && !mcfExpires.isAfter(now);
    // 「有资质」= AUTHORIZED 且**此刻未过期**。分两列而不是合成一个字段，
    // 是因为「有资质但已过期」必须能答出来：它是需要重新核验的信号，
    // 不是一个可以直接忽略的中间态。
    boolean mcfUsable = mcfStatus == McfStatus.AUTHORIZED && !mcfExpired;

    List<String> warnings = new ArrayList<>();
    if (mcf == null) {
      warnings.add("该平台与店铺没有任何多渠道配送资质登记：缺货时无法切自发货（本模块按「不许」处理）");
    } else if (mcfStatus == McfStatus.PENDING) {
      warnings.add("多渠道配送资质审核中：审核通过前缺货不能切自发货");
    } else if (mcfStatus == McfStatus.DENIED) {
      warnings.add("平台明确无多渠道配送资质：任何缺货都不能切自发货");
    } else if (mcfExpired) {
      warnings.add("多渠道配送资质已于 " + mcfExpires + " 过期：需重新核验后才能用于缺货切换");
    }

    // ---- 2. 三条候选各自的库存与成本
    PathKind[] paths = PathKind.values();
    List<Candidate> candidates = new ArrayList<>();
    for (PathKind path : paths) {
      var stock = latestStock(platformCode, shopCode, skuRef, path, now);
      var cost = effectiveCost(platformCode, path, now);
      // 手工覆盖的平台仓可用量要**同时**进入候选行：
      // 只改最终判定、不改候选，界面上就会出现「平台仓候选可行」
      // 与「判定了平台仓不可用」两处互相打脸的结论。
      if (path == PathKind.PLATFORM_FBA && platformAvailableOverride != null) {
        stock =
            new LatestStock(
                stock.snapshotId(), qty(platformAvailableOverride), stock.collectedAt());
      }
      candidates.add(buildCandidate(path, stock, cost, requested, mcfUsable, mcfStatus));
    }
    Candidate platformCandidate = candidates.get(0);
    Candidate ownCandidate = candidates.get(1);
    Candidate thirdPartyCandidate = candidates.get(2);

    BigDecimal platformAvailable =
        platformAvailableOverride != null
            ? qty(platformAvailableOverride)
            : platformCandidate.availableQty();

    // ---- 3. 平台仓是否可用（资格与库存两道，与 MCF 无关：货已在平台仓里）
    boolean platformEligible =
        platformAvailable != null
            && platformAvailable.compareTo(requested) >= 0
            && platformCandidate.isFeasible();
    if (!platformEligible) {
      warnings.add(
          platformAvailable == null
              ? "平台仓没有库存快照：无法判定可用量（0 与「没测过」在决策上不等价）"
              : "平台仓可用量 " + platformAvailable + " 不足本次需求 " + requested);
    }

    // ---- 4. 策略判定（PLATFORM_FIRST / COST_FIRST / MERCHANT_FIRST）
    List<Candidate> merchantCandidates = new ArrayList<>();
    if (ownCandidate.isFeasible()) {
      merchantCandidates.add(ownCandidate);
    }
    if (thirdPartyCandidate.isFeasible()) {
      merchantCandidates.add(thirdPartyCandidate);
    }
    // 成本优先 / 自发货优先需要一个**唯一**的最优候选；同价时按 path_kind 字典序定序，
    // 否则同样的输入在不同时候会给出不同结论（配置表最典型的坑，这里同样适用）。
    merchantCandidates.sort(
        Comparator.comparing(
                (Candidate c) -> c.cost(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(c -> c.path().name()));
    Candidate bestMerchant = merchantCandidates.isEmpty() ? null : merchantCandidates.get(0);

    PathKind chosen;
    String reasonCode;
    String reason;
    BigDecimal platformCost = platformCandidate.cost();
    BigDecimal merchantCost = bestMerchant == null ? null : bestMerchant.cost();

    if (forceMerchantPath) {
      if (bestMerchant == null) {
        throw bad("人工改判自发货不可行：" + (mcfUsable ? "两条自发货路径都没有可用库存快照或成本参数" : "该店铺无有效多渠道配送资质"));
      }
      chosen = bestMerchant.path();
      reasonCode = "POLICY_OVERRIDE";
      reason =
          "人工改判为自发货（"
              + chosen.label()
              + "）：平台仓"
              + (platformAvailable == null
                  ? "无库存快照"
                  : "可用量 " + platformAvailable + " 不足以覆盖 " + requested)
              + "，且自发货路径可行。";
    } else if (platformEligible && policy == RoutingPolicy.PLATFORM_FIRST) {
      chosen = PathKind.PLATFORM_FBA;
      reasonCode = "PLATFORM_STOCK_SUFFICIENT";
      reason = "按「优先平台仓」策略：平台仓可用量 " + platformAvailable + " 可覆盖需求 " + requested + "。";
    } else if (bestMerchant != null
        && policy == RoutingPolicy.COST_FIRST
        && (platformCost == null || bestMerchant.cost().compareTo(platformCost) < 0)) {
      chosen = bestMerchant.path();
      reasonCode = "COST_ADVANTAGE";
      reason =
          "按「成本优先」策略：自发货（"
              + chosen.label()
              + "）单件成本 "
              + bestMerchant.cost()
              + " 低于平台仓 "
              + (platformCost == null ? "（未配成本参数）" : platformCost.toPlainString())
              + "。";
    } else if (bestMerchant != null && policy == RoutingPolicy.MERCHANT_FIRST) {
      chosen = bestMerchant.path();
      reasonCode = "COST_ADVANTAGE";
      reason = "按「优先自发货」策略：选择自发货（" + chosen.label() + "）。";
    } else if (platformEligible) {
      chosen = PathKind.PLATFORM_FBA;
      reasonCode = "PLATFORM_STOCK_SUFFICIENT";
      reason = "平台仓可用量 " + platformAvailable + " 可覆盖需求 " + requested + "，且没有更优的自发货候选。";
    } else if (bestMerchant != null) {
      // **蓝图的主线场景**：平台仓缺货且自发货可行 → 切自发货。
      // 少这一支的话「平台仓不可用 + 自发货可行」会一路掉进下面的「无可行路径」，
      // 于是最该发生的自动切换反而报成错误，而 DB 里的
      // PLATFORM_STOCK_INSUFFICIENT 也永远没人写。
      chosen = bestMerchant.path();
      reasonCode = "PLATFORM_STOCK_INSUFFICIENT";
      reason =
          "平台仓"
              + (platformAvailable == null
                  ? "没有库存快照"
                  : "可用量 " + platformAvailable + " 不足 " + requested)
              + "，按「"
              + policy.label()
              + "」策略切到自发货（"
              + chosen.label()
              + "）。";
    } else {
      // 平台仓不可用且自发货也不可行 —— 这是必须**显式报出**的结论，
      // 不能悄悄回落到平台仓（那会让「这单没货」显示成「已决策走 FBA」）。
      // 逐条列出**每条**路径被排除的原因：只说「自发货不可用」时，
      // 运营既不知道是缺资质、缺库存还是缺成本参数，也就没法去补。
      String detail =
          candidates.stream()
              .filter(c -> !c.isFeasible())
              .map(c -> c.path().label() + "=" + nz(c.blockReason()))
              .reduce((a, b) -> a + "；" + b)
              .orElse("");
      throw bad(
          "无可行履约路径："
              + (platformAvailable == null
                  ? "平台仓无库存快照"
                  : "平台仓可用量 " + platformAvailable + " 不足 " + requested)
              + "。"
              + (detail.isEmpty() ? "没有任何候选被排除，说明判定本身有问题，请报给研发。" : "被排除的候选：" + detail));
    }

    // ---- 5. 缺货切换的触发原因（只在真的从平台仓切走时才有）
    SwitchTriggerReason trigger = null;
    if (chosen != PathKind.PLATFORM_FBA) {
      if (!mcfUsable) {
        throw bad("该店铺无有效多渠道配送资质，不允许切自发货：" + "缺货自动转自发货受平台政策约束，资质未验证时切过去会被平台判违规发货");
      }
      if (platformAvailable == null) {
        trigger = SwitchTriggerReason.PLATFORM_STOCK_MISSING;
      } else if (platformAvailable.compareTo(requested) < 0) {
        trigger = SwitchTriggerReason.PLATFORM_STOCK_SHORTAGE;
      } else if (reasonCode.equals("COST_ADVANTAGE")) {
        trigger = SwitchTriggerReason.COST_ADVANTAGE;
      } else {
        trigger = SwitchTriggerReason.MANUAL_OVERRIDE;
      }
    }

    List<RoutingCandidateRow> candidateRows = new ArrayList<>();
    BigDecimal selectedCost = null;
    for (Candidate c : candidates) {
      boolean isChosen = c.path() == chosen;
      if (isChosen) {
        selectedCost = c.cost();
      }
      candidateRows.add(c.toRow(isChosen));
    }
    return new RoutingEvaluation(
        platformCode.trim(),
        shopCode.trim(),
        skuRef.trim(),
        requested,
        policy,
        mcfStatus,
        mcfUsable,
        platformEligible,
        platformAvailable,
        platformCost,
        merchantCost,
        settle(selectedCost),
        chosen,
        reasonCode,
        reason,
        candidateRows,
        warnings,
        now);
  }

  // ================================================================ 决策落库

  /**
   * 落一条履约路径决策（含候选、缺货切换记录与事件流）。
   *
   * <p><b>写入顺序</b>：头 → 候选 → 切换记录 → 事件。 头先落是因为候选与切换都要对它建外键；而延迟约束触发器 （{@code
   * guard_routing_decision_switch}）在**提交时**回表读当前行， 所以「头先落、切换后补」这个顺序不会被中途的半成品状态误判 ——
   * 换成即时触发器就会在写切换行之前先报「没有切换记录」。
   */
  @Transactional
  public RoutingDecisionSaved decide(RoutingDecisionRequest request, UUID actorId) {
    if (request == null) {
      throw bad("请求体不能为空");
    }
    RoutingPolicy policy = routingPolicy(request.selectedPolicy());
    String policySource =
        notBlank(request.policySource())
            ? request.policySource().trim().toUpperCase(Locale.ROOT)
            : "TENANT_POLICY";
    if (!policySource.equals("TENANT_POLICY") && !policySource.equals("PLATFORM_POLICY")) {
      throw bad("policySource 只能是 TENANT_POLICY 或 PLATFORM_POLICY");
    }
    OffsetDateTime now = request.at() == null ? OffsetDateTime.now() : request.at();

    RoutingEvaluation evaluation =
        evaluate(
            request.platformCode(),
            request.shopCode(),
            request.skuRef(),
            request.requestedQty(),
            policy,
            Boolean.TRUE.equals(request.forceMerchantPath()),
            null,
            now);

    // 决策行上抄 MCF 资质引用与状态：决策之后资质可能被收回，
    // 只查当前资质会让「当时凭什么敢切」永远答不出来。
    UUID decisionId = UUID.randomUUID();
    BigDecimal platformCost = evaluation.platformUnitCost();
    BigDecimal merchantCost = evaluation.merchantUnitCost();
    String reason = notBlank(request.reason()) ? request.reason().trim() : evaluation.reason();
    sql.execute(
        DECISION_INSERT,
        decisionInsertArgs(decisionId, request, policy, policySource, evaluation, reason, actorId));

    // 候选行：把三条路径当时的库存与成本抄进来。
    // 查询时联算是错的 —— 库存会被补上、成本参数会被改，历史候选会跟着变。
    int candidateCount = 0;
    for (RoutingCandidateRow c : evaluation.candidates()) {
      CandidateSnapshot snap =
          candidateSnapshot(
              request.platformCode().trim(),
              request.shopCode().trim(),
              request.skuRef().trim(),
              c.pathKind(),
              now);
      sql.execute(
          "insert into fulfillment_routing_candidate"
              + "(id,tenant_id,decision_id,path_kind,available_qty,unit_cost,lead_time_days,"
              + "cost_profile_id,stock_snapshot_id,feasible,infeasible_reason_code,infeasible_reason)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          currentTenant(),
          decisionId,
          c.pathKind().name(),
          c.availableQty(),
          c.unitCost(),
          c.leadTimeDays(),
          snap.costProfileId(),
          snap.stockSnapshotId(),
          c.feasible(),
          nz(c.infeasibleReasonCode()),
          nz(c.infeasibleReason()));
      candidateCount++;
    }

    // 缺货切换记录：蓝图的第二项输出。非平台仓决策没有它提交不了。
    UUID switchId = null;
    SwitchTriggerReason trigger = switchTrigger(evaluation, request);
    if (evaluation.chosenPath() != PathKind.PLATFORM_FBA) {
      switchId = UUID.randomUUID();
      sql.execute(
          "insert into fulfillment_routing_switch"
              + "(id,tenant_id,decision_id,from_path,to_path,trigger_reason,reason,"
              + "platform_available_qty,requested_qty,switched_at,switched_by_membership_id,"
              + "switch_source,source_reference)"
              + " values (?,?,?,'PLATFORM_FBA',?,?,?,?,?,?::timestamptz,?,?,?)",
          switchId,
          currentTenant(),
          decisionId,
          evaluation.chosenPath().name(),
          trigger.name(),
          reason,
          evaluation.platformAvailableQty() == null ? ZERO_QTY : evaluation.platformAvailableQty(),
          evaluation.requestedQty(),
          evaluation.at(),
          actorId,
          "SYSTEM",
          nz(request.orderRef()));
    }

    sql.execute(
        "insert into fulfillment_routing_event"
            + "(id,tenant_id,decision_id,event_kind,from_status,to_status,detail,actor_id,occurred_at)"
            + " values (?,?,?,'DECIDED','','DECIDED',?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        decisionId,
        reason,
        actorId,
        evaluation.at());

    return new RoutingDecisionSaved(
        decisionId,
        request.orderRef().trim(),
        evaluation.chosenPath(),
        evaluation.reasonCode(),
        reason,
        switchId,
        trigger,
        candidateCount,
        switchId == null
            ? "已选择平台仓路径"
            : "平台仓不可用，已切自发货并留下切换记录（" + (trigger == null ? "" : trigger.label()) + "）");
  }

  private SwitchTriggerReason switchTrigger(
      RoutingEvaluation evaluation, RoutingDecisionRequest request) {
    if (evaluation.chosenPath() == PathKind.PLATFORM_FBA) {
      return null;
    }
    if (evaluation.platformAvailableQty() == null) {
      return SwitchTriggerReason.PLATFORM_STOCK_MISSING;
    }
    if (evaluation.platformAvailableQty().compareTo(evaluation.requestedQty()) < 0) {
      return SwitchTriggerReason.PLATFORM_STOCK_SHORTAGE;
    }
    if (Boolean.TRUE.equals(request.forceMerchantPath())) {
      return SwitchTriggerReason.MANUAL_OVERRIDE;
    }
    return SwitchTriggerReason.COST_ADVANTAGE;
  }

  /**
   * 决策行的固定列清单。
   *
   * <p>values 子句**由同一个数组生成**：手写占位符曾经与列数差一位， 报出来的是「未设定参数值 22 的内容」—— 一句与真实原因完全无关的话。
   */
  // tenant_id 与其它列一样是**绑定参数**，但绑的不是请求头：值来自 currentTenant()，
  // 也就是 backend/libraries/tenancy 在事务内设的 app.current_tenant（RLS 的 WITH CHECK 会核对）。
  private static final String[] DECISION_COLUMNS_IN = {
    "id",
    "tenant_id",
    "order_ref",
    "order_line_ref",
    "platform_code",
    "shop_code",
    "sku_ref",
    "requested_qty",
    "selected_policy",
    "policy_source",
    "chosen_path",
    "reason_code",
    "reason",
    "platform_available_qty",
    "platform_unit_cost",
    "merchant_unit_cost",
    "selected_unit_cost",
    "currency_code",
    "mcf_authorization_id",
    "mcf_status",
    "status",
    "decided_at",
    "created_by_membership_id"
  };

  /** 与 {@link #DECISION_COLUMNS_IN} 一一对应：空串 = 普通绑定，其余是 PG 侧 cast。 */
  private static final String[] DECISION_CASTS_IN = {
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "",
    "::timestamptz",
    ""
  };

  private static final String DECISION_INSERT = decisionInsert();

  private static String decisionInsert() {
    if (DECISION_CASTS_IN.length != DECISION_COLUMNS_IN.length) {
      throw new IllegalStateException(
          "决策写入的列与 cast 数量不一致：" + DECISION_COLUMNS_IN.length + " vs " + DECISION_CASTS_IN.length);
    }
    StringBuilder columns = new StringBuilder();
    StringBuilder values = new StringBuilder();
    for (int i = 0; i < DECISION_COLUMNS_IN.length; i++) {
      if (i > 0) {
        columns.append(',');
        values.append(',');
      }
      columns.append(DECISION_COLUMNS_IN[i]);
      values.append('?').append(DECISION_CASTS_IN[i]);
    }
    return "insert into fulfillment_routing_decision (" + columns + ") values (" + values + ")";
  }

  private Object[] decisionInsertArgs(
      UUID decisionId,
      RoutingDecisionRequest request,
      RoutingPolicy policy,
      String policySource,
      RoutingEvaluation evaluation,
      String reason,
      UUID actorId) {
    return new Object[] {
      decisionId,
      currentTenant(),
      request.orderRef().trim(),
      nz(request.orderLineRef()),
      request.platformCode().trim(),
      request.shopCode().trim(),
      request.skuRef().trim(),
      evaluation.requestedQty(),
      policy.name(),
      policySource,
      evaluation.chosenPath().name(),
      evaluation.reasonCode(),
      reason,
      evaluation.platformAvailableQty() == null ? ZERO_QTY : evaluation.platformAvailableQty(),
      evaluation.platformUnitCost(),
      evaluation.merchantUnitCost(),
      evaluation.selectedUnitCost(),
      "USD",
      mcfAuthorizationId(request.platformCode().trim(), request.shopCode().trim()),
      evaluation.mcfStatus().name(),
      "DECIDED",
      evaluation.at(),
      actorId
    };
  }

  // ================================================================ 决策读

  /** 决策列表（真分页；四组汇总额外单独查，不受分页影响）。 */
  public DecisionPage pageDecisions(DecisionQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    // prefix 同时给决策表与「决策 ⋈ 切换」的 join 用：筛选条件都落在决策表上，
    // 所以 join 侧传 "d."。**不为省事而把同一段字符串 replace 一遍** ——
    // 那种写法在上游出现同名子串时会静默改错列名，报错还会指向一个无关的地方。
    decisionConditions(query, where, binds, "");
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    long total =
        sql.fetchOne(
                "select count(*) c from fulfillment_routing_decision" + sqlWhere, binds.toArray())
            .get("c", Long.class);
    List<RoutingDecisionRow> items =
        sql.fetch(
                "select "
                    + DECISION_COLUMNS
                    + " from fulfillment_routing_decision"
                    + sqlWhere
                    + " order by decided_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentRoutingService::decisionRow);

    // 汇总与筛选条件一致但**不受分页影响**：看板上的数字不能因为翻页而变。
    long platformCount =
        sql.fetchOne(
                "select count(*) c from fulfillment_routing_decision"
                    + sqlWhere
                    + (sqlWhere.isEmpty() ? " where " : " and ")
                    + "chosen_path = 'PLATFORM_FBA'",
                binds.toArray())
            .get("c", Long.class);
    long merchantCount =
        sql.fetchOne(
                "select count(*) c from fulfillment_routing_decision"
                    + sqlWhere
                    + (sqlWhere.isEmpty() ? " where " : " and ")
                    + "chosen_path <> 'PLATFORM_FBA'",
                binds.toArray())
            .get("c", Long.class);
    // 切换数走 join：切换挂在决策上，按同一组筛选条件统计（列加 d. 前缀）。
    List<String> switchWhere = new ArrayList<>();
    List<Object> switchBinds = new ArrayList<>();
    decisionConditions(query, switchWhere, switchBinds, "d.");
    long switched =
        sql.fetchOne(
                "select count(*) c from fulfillment_routing_switch sw join fulfillment_routing_decision d"
                    + " on d.tenant_id = sw.tenant_id and d.id = sw.decision_id"
                    + whereClause(switchWhere),
                switchBinds.toArray())
            .get("c", Long.class);
    long pending =
        sql.fetchOne(
                "select count(*) c from fulfillment_routing_decision"
                    + sqlWhere
                    + (sqlWhere.isEmpty() ? " where " : " and ")
                    + "status = 'DECIDED'",
                binds.toArray())
            .get("c", Long.class);
    return new DecisionPage(
        items, total, pageNo, size, platformCount, merchantCount, switched, pending);
  }

  /**
   * 决策筛选条件（列表与切换数两处共用，只有列前缀不同）。
   *
   * <p>抽出来是因为两处条件必须**永远一致**：切换数如果少了一个筛选条件， 看板上的「切过货」就会与列表筛出来的条数对不上，而这种错位极难排查。
   */
  private static void decisionConditions(
      DecisionQuery query, List<String> where, List<Object> binds, String prefix) {
    if (notBlank(query.orderRef())) {
      where.add(prefix + "order_ref = ?");
      binds.add(query.orderRef().trim());
    }
    if (notBlank(query.platformCode())) {
      where.add(prefix + "platform_code = ?");
      binds.add(query.platformCode().trim());
    }
    if (notBlank(query.shopCode())) {
      where.add(prefix + "shop_code = ?");
      binds.add(query.shopCode().trim());
    }
    if (notBlank(query.skuRef())) {
      where.add(prefix + "sku_ref = ?");
      binds.add(query.skuRef().trim());
    }
    if (notBlank(query.chosenPath())) {
      where.add(prefix + "chosen_path = ?");
      binds.add(pathKind(query.chosenPath()).name());
    }
    if (notBlank(query.reasonCode())) {
      where.add(prefix + "reason_code = ?");
      binds.add(query.reasonCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.add(prefix + "status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.keyword())) {
      where.add(
          "("
              + prefix
              + "order_ref ilike ? or "
              + prefix
              + "order_line_ref ilike ? or "
              + prefix
              + "sku_ref ilike ? or "
              + prefix
              + "reason ilike ?)");
      String k = "%" + query.keyword().trim() + "%";
      for (int i = 0; i < 4; i++) {
        binds.add(k);
      }
    }
  }

  private static String whereClause(List<String> where) {
    return where.isEmpty() ? "" : " where " + String.join(" and ", where);
  }

  /** 决策详情：决策 + 候选 + 切换记录 + 事件流。跨租户在 RLS 下查不到 → 404。 */
  public RoutingDecisionDetail decision(UUID decisionId) {
    var head =
        sql.fetchOne(
            "select " + DECISION_COLUMNS + " from fulfillment_routing_decision where id = ?",
            decisionId);
    if (head == null) {
      throw notFound("决策 " + decisionId + " 不存在");
    }
    List<RoutingCandidateRow> candidates =
        sql.fetch(
                "select c.path_kind,c.available_qty,c.unit_cost,c.lead_time_days,c.feasible,"
                    + "c.infeasible_reason_code,c.infeasible_reason,d.chosen_path"
                    + " from fulfillment_routing_candidate c"
                    + " join fulfillment_routing_decision d"
                    + " on d.tenant_id = c.tenant_id and d.id = c.decision_id"
                    + " where c.decision_id = ? order by c.path_kind",
                decisionId)
            .map(
                r ->
                    new RoutingCandidateRow(
                        pathKind(r.get("path_kind", String.class)),
                        r.get("available_qty", BigDecimal.class),
                        r.get("unit_cost", BigDecimal.class),
                        r.get("lead_time_days", BigDecimal.class),
                        r.get("feasible", Boolean.class),
                        nz(r.get("infeasible_reason_code", String.class)),
                        nz(r.get("infeasible_reason", String.class)),
                        pathKind(r.get("chosen_path", String.class))
                            == pathKind(r.get("path_kind", String.class))));
    List<RoutingSwitchRow> switches = switchesOf(decisionId);
    List<RoutingEventRow> events =
        sql.fetch(
                "select id,event_kind,from_status,to_status,detail,actor_id,occurred_at"
                    + " from fulfillment_routing_event where decision_id = ? order by occurred_at, id",
                decisionId)
            .map(FulfillmentRoutingService::eventRow);
    return new RoutingDecisionDetail(
        decisionRow(head), candidates, switches, events, "候选行是决策当时的库存与成本快照：库存与成本参数事后会变，历史候选不会被回算。");
  }

  /** 推进决策状态（DECIDED → CONFIRMED / CANCELLED）。终态不可离开。 */
  @Transactional
  public RoutingDecisionSaved advanceStatus(
      UUID decisionId, DecisionStatusInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    DecisionStatus target;
    try {
      target = DecisionStatus.valueOf(input.status().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw bad("状态只能是 CONFIRMED 或 CANCELLED");
    }
    if (target == DecisionStatus.DECIDED) {
      throw bad("状态只能推进到 CONFIRMED 或 CANCELLED");
    }
    if (target == DecisionStatus.CANCELLED && notBlank(input.reason()) == false) {
      throw bad("作废必须给原因：已确认下发的路径被撤回，没人说得清为什么");
    }
    var head =
        sql.fetchOne(
            "select order_ref,chosen_path,reason_code,reason from fulfillment_routing_decision"
                + " where id = ?",
            decisionId);
    if (head == null) {
      throw notFound("决策 " + decisionId + " 不存在");
    }
    // 状态推进走 SECURITY DEFINER 窄函数：决策表对 trade_app 已 REVOKE UPDATE，
    // append-only 才是真的（见迁移第 8.5 节）。
    sql.execute(
        "select advance_routing_decision_status(?::uuid, ?, ?::uuid, ?)",
        decisionId,
        target.name(),
        actorId,
        nz(input.reason()));
    sql.execute(
        "insert into fulfillment_routing_event"
            + "(id,tenant_id,decision_id,event_kind,from_status,to_status,detail,actor_id,occurred_at)"
            + " values (?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        decisionId,
        target == DecisionStatus.CONFIRMED ? "CONFIRMED" : "CANCELLED",
        "DECIDED",
        target.name(),
        target == DecisionStatus.CONFIRMED ? "路径已确认下发" : "决策已作废：" + nz(input.reason()),
        actorId,
        OffsetDateTime.now());
    return new RoutingDecisionSaved(
        decisionId,
        head.get("order_ref", String.class),
        pathKind(head.get("chosen_path", String.class)),
        head.get("reason_code", String.class),
        head.get("reason", String.class),
        null,
        null,
        0,
        target == DecisionStatus.CONFIRMED ? "路径已确认" : "决策已作废");
  }

  // ================================================================ 切换记录读

  /** 缺货切换记录列表（真分页）。这是「为什么这单没走平台仓」的查询入口。 */
  public SwitchPage pageSwitches(SwitchQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.orderRef())) {
      where.add("d.order_ref = ?");
      binds.add(query.orderRef().trim());
    }
    if (query.decisionId() != null) {
      where.add("sw.decision_id = ?");
      binds.add(query.decisionId());
    }
    if (notBlank(query.platformCode())) {
      where.add("d.platform_code = ?");
      binds.add(query.platformCode().trim());
    }
    if (notBlank(query.triggerReason())) {
      where.add("sw.trigger_reason = ?");
      binds.add(query.triggerReason().trim().toUpperCase(Locale.ROOT));
    }
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);
    String from =
        " from fulfillment_routing_switch sw join fulfillment_routing_decision d"
            + " on d.tenant_id = sw.tenant_id and d.id = sw.decision_id";
    long total =
        sql.fetchOne("select count(*) c" + from + sqlWhere, binds.toArray()).get("c", Long.class);
    List<RoutingSwitchRow> items =
        sql.fetch(
                "select sw.id,sw.decision_id,d.order_ref,sw.from_path,sw.to_path,sw.trigger_reason,"
                    + "sw.reason,sw.platform_available_qty,sw.requested_qty,sw.switched_at,"
                    + "sw.switch_source,sw.source_reference"
                    + from
                    + sqlWhere
                    + " order by sw.switched_at desc, sw.id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(FulfillmentRoutingService::switchRow);
    return new SwitchPage(items, total, pageNo, size);
  }

  private List<RoutingSwitchRow> switchesOf(UUID decisionId) {
    return sql.fetch(
            "select sw.id,sw.decision_id,d.order_ref,sw.from_path,sw.to_path,sw.trigger_reason,"
                + "sw.reason,sw.platform_available_qty,sw.requested_qty,sw.switched_at,"
                + "sw.switch_source,sw.source_reference"
                + " from fulfillment_routing_switch sw join fulfillment_routing_decision d"
                + " on d.tenant_id = sw.tenant_id and d.id = sw.decision_id"
                + " where sw.decision_id = ? order by sw.switched_at desc, sw.id",
            decisionId)
        .map(FulfillmentRoutingService::switchRow);
  }

  // ================================================================ 判定内部结构

  /** 候选的内部计算结果（尚未决定 selected / 是否被选中）。 */
  private record Candidate(
      PathKind path,
      BigDecimal availableQty,
      BigDecimal cost,
      BigDecimal leadTimeDays,
      boolean feasible,
      String blockCode,
      String blockReason) {

    /** 该候选是否可用（不可用时 blockCode 非空）。 */
    boolean isFeasible() {
      return feasible;
    }

    RoutingCandidateRow toRow(boolean chosen) {
      return new RoutingCandidateRow(
          path, availableQty, cost, leadTimeDays, feasible, nz(blockCode), nz(blockReason), chosen);
    }
  }

  /** 候选行上引用的两张表的主键（响应不暴露，DB 侧可追）。 */
  private record CandidateSnapshot(UUID costProfileId, UUID stockSnapshotId) {}

  private Candidate buildCandidate(
      PathKind path,
      LatestStock stock,
      LatestCost cost,
      BigDecimal requested,
      boolean mcfUsable,
      McfStatus mcfStatus) {
    String block = "";
    String reason = "";
    boolean merchant = path != PathKind.PLATFORM_FBA;
    // MCF 闸门排在最前：资质不过时库存与成本都不必看了 ——
    // 「有货有便宜但没资质」在平台上是非法的，不是「可以权衡的选项」。
    if (merchant && !mcfUsable) {
      block = CandidateBlockReason.MCF_NOT_AUTHORIZED.name();
      reason =
          mcfStatus == McfStatus.AUTHORIZED
              ? "多渠道配送资质已于评估时刻过期，需重新核验"
              : "店铺多渠道配送资质为「" + mcfStatus.label() + "」，缺货时不得切自发货";
    } else if (cost.cost() == null) {
      block = CandidateBlockReason.NO_COST_PROFILE.name();
      reason = "该平台与路径未维护成本与时效参数：没有成本口径就不能参与路径比较";
    } else if (stock.snapshotId() == null) {
      block = CandidateBlockReason.NO_STOCK_RECORD.name();
      reason = "该路径没有库存快照：0 与「没测过」在决策上不等价";
    } else if (stock.availableQty() != null && stock.availableQty().signum() == 0) {
      block = CandidateBlockReason.STOCK_ZERO.name();
      reason = "该路径可用量为 0";
    } else if (stock.availableQty() != null && stock.availableQty().compareTo(requested) < 0) {
      block = CandidateBlockReason.INSUFFICIENT_STOCK.name();
      reason = "该路径可用量 " + stock.availableQty() + " 不足需求 " + requested;
    }
    return new Candidate(
        path,
        stock.availableQty() == null ? null : qty(stock.availableQty()),
        cost.cost() == null ? null : money(cost.cost()),
        cost.leadTimeDays(),
        block.isEmpty(),
        block,
        reason);
  }

  /** 取某路径当前生效的成本参数：目的国精确匹配优先于 '*' 兜底。 */
  private LatestCost effectiveCost(String platformCode, PathKind path, OffsetDateTime at) {
    Record row =
        sql.fetchOne(
            "select id,unit_cost,lead_time_days from fulfillment_path_cost_profile"
                + " where platform_code = ? and path_kind = ?"
                + "   and effective_from <= ?::timestamptz"
                + "   and (effective_to is null or effective_to > ?::timestamptz)"
                + " order by case when destination_country_code = '*' then 1 else 0 end,"
                + "          effective_from desc limit 1",
            platformCode.trim(),
            path.name(),
            at,
            at);
    if (row == null) {
      return new LatestCost(null, null, null);
    }
    return new LatestCost(
        row.get("id", UUID.class),
        row.get("unit_cost", BigDecimal.class),
        row.get("lead_time_days", BigDecimal.class));
  }

  /** 取某路径最新一条库存快照（按 collected_at，而不是写入顺序）。 */
  private LatestStock latestStock(
      String platformCode, String shopCode, String skuRef, PathKind path, OffsetDateTime at) {
    Record row =
        sql.fetchOne(
            "select id,available_qty,collected_at from fulfillment_platform_stock_snapshot"
                + " where platform_code = ? and shop_code = ? and sku_ref = ? and path_kind = ?"
                + " order by collected_at desc, id limit 1",
            platformCode.trim(),
            shopCode.trim(),
            skuRef.trim(),
            path.name());
    if (row == null) {
      return new LatestStock(null, null, null);
    }
    OffsetDateTime collected = row.get("collected_at", OffsetDateTime.class);
    BigDecimal qtyValue = row.get("available_qty", BigDecimal.class);
    // 采集时间**在评估时刻之后**的快照不能用：那是未来数据，
    // 用它判「够不够」等于让决策依赖尚未发生的事实。
    if (collected.isAfter(at)) {
      // 采集时间在评估时刻之后 = 这条快照**不是当前值**。
      // 返回 null 而不只是把数量置空：否则下游 buildCandidate 会认为「有记录」，
      // 于是路径被判成可行却带着一个 null 可用量 —— 那是最坏的一种状态。
      return new LatestStock(null, null, collected);
    }
    return new LatestStock(row.get("id", UUID.class), qtyValue, collected);
  }

  private CandidateSnapshot candidateSnapshot(
      String platformCode, String shopCode, String skuRef, PathKind path, OffsetDateTime at) {
    return new CandidateSnapshot(
        effectiveCost(platformCode, path, at).costProfileId(),
        latestStock(platformCode, shopCode, skuRef, path, at).snapshotId());
  }

  private record LatestStock(
      UUID snapshotId, BigDecimal availableQty, OffsetDateTime collectedAt) {}

  private record LatestCost(UUID costProfileId, BigDecimal cost, BigDecimal leadTimeDays) {}

  // ================================================================ 映射与工具

  private static final String DECISION_COLUMNS =
      "id,order_ref,order_line_ref,platform_code,shop_code,sku_ref,requested_qty,selected_policy,"
          + "policy_source,chosen_path,reason_code,reason,platform_available_qty,platform_unit_cost,"
          + "merchant_unit_cost,selected_unit_cost,currency_code,mcf_status,status,decided_at,"
          + "confirmed_at,created_at";

  private static McfAuthorizationRow mcfRow(Record r) {
    OffsetDateTime expires = r.get("expires_at", OffsetDateTime.class);
    return new McfAuthorizationRow(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        mcfStatus(r.get("status", String.class)),
        expires != null && !expires.isAfter(OffsetDateTime.now()),
        r.get("authorization_source", String.class),
        r.get("source_reference", String.class),
        r.get("verified_at", OffsetDateTime.class),
        expires,
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static CostProfileRow costRow(Record r) {
    return new CostProfileRow(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        pathKind(r.get("path_kind", String.class)),
        r.get("destination_country_code", String.class),
        r.get("unit_cost", BigDecimal.class),
        r.get("lead_time_days", BigDecimal.class),
        r.get("platform_fee", BigDecimal.class),
        r.get("currency_code", String.class),
        r.get("effective_from", OffsetDateTime.class),
        r.get("effective_to", OffsetDateTime.class),
        r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static StockSnapshotRow stockRow(Record r) {
    return new StockSnapshotRow(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("sku_ref", String.class),
        pathKind(r.get("path_kind", String.class)),
        r.get("warehouse_ref", String.class),
        r.get("warehouse_name", String.class),
        r.get("available_qty", BigDecimal.class),
        r.get("collected_at", OffsetDateTime.class),
        r.get("stock_source", String.class),
        r.get("source_reference", String.class),
        r.get("notes", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static RoutingDecisionRow decisionRow(Record r) {
    return new RoutingDecisionRow(
        r.get("id", UUID.class),
        r.get("order_ref", String.class),
        nz(r.get("order_line_ref", String.class)),
        r.get("platform_code", String.class),
        r.get("shop_code", String.class),
        r.get("sku_ref", String.class),
        r.get("requested_qty", BigDecimal.class),
        routingPolicy(r.get("selected_policy", String.class)),
        r.get("policy_source", String.class),
        pathKind(r.get("chosen_path", String.class)),
        r.get("reason_code", String.class),
        r.get("reason", String.class),
        r.get("platform_available_qty", BigDecimal.class),
        r.get("platform_unit_cost", BigDecimal.class),
        r.get("merchant_unit_cost", BigDecimal.class),
        r.get("selected_unit_cost", BigDecimal.class),
        r.get("currency_code", String.class),
        mcfStatus(r.get("mcf_status", String.class)),
        decisionStatus(r.get("status", String.class)),
        r.get("decided_at", OffsetDateTime.class),
        r.get("confirmed_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static RoutingSwitchRow switchRow(Record r) {
    return new RoutingSwitchRow(
        r.get("id", UUID.class),
        r.get("decision_id", UUID.class),
        r.get("order_ref", String.class),
        pathKind(r.get("from_path", String.class)),
        pathKind(r.get("to_path", String.class)),
        SwitchTriggerReason.valueOf(r.get("trigger_reason", String.class)),
        r.get("reason", String.class),
        r.get("platform_available_qty", BigDecimal.class),
        r.get("requested_qty", BigDecimal.class),
        r.get("switched_at", OffsetDateTime.class),
        r.get("switch_source", String.class),
        r.get("source_reference", String.class));
  }

  private static RoutingEventRow eventRow(Record r) {
    return new RoutingEventRow(
        r.get("id", UUID.class),
        r.get("event_kind", String.class),
        r.get("from_status", String.class),
        r.get("to_status", String.class),
        r.get("detail", String.class),
        r.get("actor_id", UUID.class),
        r.get("occurred_at", OffsetDateTime.class));
  }

  private UUID mcfAuthorizationId(String platformCode, String shopCode) {
    var row =
        sql.fetchOne(
            "select id from fulfillment_mcf_authorization where platform_code = ? and shop_code = ?",
            platformCode,
            shopCode);
    return row == null ? null : row.get(0, UUID.class);
  }

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private static Object[] append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /** 数量刻度对齐 numeric(19,6)。 */
  private static BigDecimal qty(BigDecimal value) {
    return value == null ? null : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额刻度对齐 numeric(19,4)。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? null : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  /** 结算口径：统一 HALF_UP 到分（DB 侧 round(x,2) 复核，见迁移第 8 节）。 */
  private static BigDecimal settle(BigDecimal value) {
    return value == null ? null : money(value).setScale(SETTLE_SCALE, RoundingMode.HALF_UP);
  }

  private static PathKind pathKind(String value) {
    try {
      return PathKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的履约路径「" + value + "」：只能是 PLATFORM_FBA / MERCHANT_OWN / THIRD_PARTY");
    }
  }

  private static RoutingPolicy routingPolicy(String value) {
    try {
      return RoutingPolicy.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的决策策略「" + value + "」：只能是 PLATFORM_FIRST / COST_FIRST / MERCHANT_FIRST");
    }
  }

  private static McfStatus mcfStatus(String value) {
    try {
      return McfStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
    } catch (RuntimeException e) {
      throw bad("未知的资质状态「" + value + "」：只能是 AUTHORIZED / DENIED / PENDING / UNKNOWN");
    }
  }

  private static DecisionStatus decisionStatus(String value) {
    return DecisionStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>表达式里的 nullif 不是洁癖：事务局部的 set_config 提交之后，PostgreSQL 会把自定义 GUC **复位成空串**而不是「回到未设置」，直接 cast 会报
   * uuid 语法错。 与 warehouse / trade V1 同一句写法。
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
