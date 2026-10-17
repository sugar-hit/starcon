package com.starcon.trade;

import static com.starcon.trade.OrderSplitModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 9.3.1.1 拆单：规则与仓档案、可用库存分布、拆单方案落库、预占指令登记、发货回传留痕。
 *
 * <p><b>七条实现纪律</b>：
 *
 * <ol>
 *   <li><b>候选与库存是真 SQL</b>：{@link #preview} 与 {@link #split} 共用 {@link #plan}，
 *       判定全部基于一条「仓 × SKU 可用量」的真实联表查询，**不在 Java 里过滤一个假列表**。
 *   <li><b>每条查询都带 tenant_id 谓词</b>：除 FORCE RLS 之外再显式写一遍
 *       {@code nullif(current_setting('app.current_tenant',true),'')::uuid}。
 *       拆单的查询形状是「本租户全部可用仓」的**宽条件**，一旦漏掉租户谓词就是
 *       <b>跨租户拆单</b> —— 两家互不相识的公司的订单会被切到对方的仓去，双方互相看到对方买家地址。
 *   <li><b>🔴 金额守恒是硬不变量</b>：各履约单元分摊额 + 尾差 <b>恰好等于</b>订单总金额，
 *       由 V151 的延迟约束触发器在提交时独立复核。拆单全程不改订单总额
 *       （蓝图加粗句「订单总金额与买家视角不变」）。
 *   <li><b>尾差必须有归属</b>：HALF_UP 到 4 位产生的几分钱落进 {@code residual_amount}，
 *       确定性归到一行，<b>不静默吞掉</b> —— 差了几分钱会让每个单元都对不上账。
 *   <li><b>确定性</b>：同样输入必得同样方案。策略是租户配置（不是 Java if），
 *       分组后按四元组<b>字典序</b>定序发单元号，tiebreak 最终落到字典序。
 *   <li><b>汇总数由 DB 实算</b>：unit_count / total_qty / allocated_amount_total /
 *       residual_amount_total 由 V151 的触发器从明细算，服务层<b>不</b>自己写这几个数。
 *   <li><b>时间一律显式绑定</b>：timestamptz 列用 {@code ?::timestamptz}，
 *       绝不用 {@code Timestamp.valueOf(LocalDateTime...)}（那会静默偏 −28800s）。
 *       「按时效拆」直接依赖 ship_by 时刻的正确性。
 * </ol>
 */
@Service
public class OrderSplitService {

  /** 单页最大行数。拆单是凭据不是流水，但仍设上限防一次拉爆 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  private static final int QTY_SCALE = 6;
  private static final int MONEY_SCALE = 4;

  static final BigDecimal ZERO_QTY = new BigDecimal("0.000000");
  static final BigDecimal ZERO_MONEY = new BigDecimal("0.0000");

  private static final String TENANT_PREDICATE =
      " nullif(current_setting('app.current_tenant',true),'')::uuid";

  private final DSLContext sql;

  public OrderSplitService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>{@code reservesUpstreamStock=false} 与 {@code orderReviewConnected=false} 都是<b>如实报出</b>：
   * 7 预占在 warehouse 独立库，按 AGENTS.md 禁止跨库读写 —— 现在假装预占，
   * 等下游落地时就会出现「两边都以为占了同一批货」（V116 里踩过的坑）；
   * 9.2 审单整条线未交付（实查 trade 迁移里没有任何 review 词表的建表语句），
   * 所以本模块只存逻辑引用与快照列，不校验审单结论存在性。
   */
  public OrderSplitCapabilities capabilities() {
    boolean configured = ruleOrNull() != null;
    return new OrderSplitCapabilities(
        List.of(
            new OrderSplitCapabilities.SplitDimension(
                "ORDER_REVIEW",
                "9.2 审单（拆单的蓝图上游）",
                false,
                "9.2.1.1 待审队列 / 9.2.1.2 审单规则 / 9.2.1.3 异常拦截（**均未交付**）",
                "实查 trade 服务 db/migration 目录：没有任何 review 词表的建表语句，9.2 整条线未开发。"
                    + "因此本模块**只存 order_ref 逻辑引用 + 收货/货权快照列**，"
                    + "不建指向审单记录的引用、也不校验审单结论存在性。"
                    + "拆单接上审单后只需在本表补一条审单结论引用，既有读法不用变。"),
            new OrderSplitCapabilities.SplitDimension(
                "AVAILABLE_STOCK",
                "可用库存分布（拆单的蓝图输入）",
                true,
                "7.1.1.3 可用量（warehouse 服务）→ 本服务自维护 order_split_stock_snapshot",
                null),
            new OrderSplitCapabilities.SplitDimension(
                "STOCK_RESERVATION",
                "7 预占（蓝图输出的预占指令要发给谁）",
                false,
                "7.x 预占（inventory_reservation / reservation_entry，**warehouse 独立库**）",
                "实查 warehouse 服务迁移：inventory_reservation / reservation_entry / "
                    + "stock_bucket 全在 warehouse 自己的库里，按模块边界纪律不得跨库读写。"
                    + "本模块落 order_split_reservation_command 是**指令登记**、不是预占事实："
                    + "upstream_reserved 由 DB CHECK 恒钉为 false。真正的预占要调 warehouse 的授权 API。"),
            new OrderSplitCapabilities.SplitDimension(
                "FULFILLMENT_REQUEST",
                "9.3.3.1 履约请求（蓝图输出的消费方）",
                false,
                "9.3.3.1 履约请求（trade 服务，本批并行车道）",
                "「多个履约请求」在本模块落成 order_split_unit 履约单元行（带状态）。"
                    + "本模块**不建也不读** 9.3.3.1 的表 —— 那条车道与本批同时提交，"
                    + "跨车道读会让整批验证炸掉；它落地后按同一形状消费这些单元行即可。"),
            new OrderSplitCapabilities.SplitDimension(
                "WRITEBACK",
                "发货回传按拆分执行",
                false,
                "9.4.2.1 回传（未交付）",
                "本模块按蓝图输出③为**每个履约单元**各建一条回传任务"
                    + "（UNIQUE(tenant_id,unit_id) 保证不多不少）并登记物流号与结果，"
                    + "但真正向平台发请求由 9.4.2.1 负责。"),
            new OrderSplitCapabilities.SplitDimension(
                "SALES_ORDER",
                "订单真值（可选只读）",
                true,
                "9.1.1.1 订单（trade 服务 V99，已交付）→ 本服务按 tenant 只读 "
                    + "sales_order / sales_order_line",
                null),
            new OrderSplitCapabilities.SplitDimension(
                "LEGAL_ENTITY",
                "货权归属（法人）",
                true,
                "2.1.1.1 法人档案（跨库不可读）→ 本服务按仓登记 legal_entity_code",
                "从 A 法人的仓发货意味着收入记在 A 法人。拆单把一单切到多个仓，"
                    + "货权逐单元可追是硬要求，故登记在 order_split_warehouse 上并随单元行落库。"),
            new OrderSplitCapabilities.SplitDimension(
                "SHIPMENT_TIMELINESS",
                "发货时效（按时效拆的依据）",
                true,
                "9.3.2.1 分仓（V116 已交付；时效估算同属租户维护口径）",
                "本模块的 ship_by_hours 取自 order_split_warehouse 的租户登记值。"
                    + "仓的承诺时效**留空 = 未知而不是 0**：未知仓排不进时效档，由服务层报出，"
                    + "不当成「最快」—— 那会让时效拆分把最慢的仓排到第一刀。")),
        List.of(SplitStatus.values()),
        List.of(SplitStrategy.values()),
        List.of(SplitReservationStatus.values()),
        List.of(SplitWritebackStatus.values()),
        List.of(RejectReason.values()),
        configured,
        false,
        false,
        "四个拆分维度（仓库 / 承运商 / 时效 / 平台限制）是**租户口径的落库存档配置**"
            + "（order_split_rule 的四个布尔 + primary_strategy），不是 Java 里的 if 链 —— "
            + "「这一单要不要按承运商再切一刀」是运营口径，写进 if 分支就再也改不动。"
            + "同样输入必得同样方案：分组后按 warehouse_ref / carrier_code / ship_by_hours / "
            + "platform_code 四元组字典序定序发单元号，tiebreak 最终落到字典序。",
        "上游缺口：9.2 审单整条线未交付（本模块只存逻辑引用与快照列）。"
            + "下游缺口：9.3.3.1 履约请求（本批并行车道）、7 预占（warehouse 独立库，跨库不可写）、"
            + "9.4.2.1 回传（未交付）。本模块不假装接通任何一条。");
  }

  // ================================================================ 拆单规则

  /** 拆单规则读取。没登记过就是 null —— 不是一份带默认值的规则。 */
  public SplitRule rule() {
    return ruleOrNull();
  }

  private SplitRule ruleOrNull() {
    Record r =
        sql.fetchOne(
            "select id,enabled,split_by_warehouse,split_by_carrier,split_by_timeliness,"
                + "split_by_platform_restriction,primary_strategy,max_units_per_split,"
                + "allow_partial_fulfillment,max_ship_by_hours,stock_freshness_hours,"
                + "version,updated_at from order_split_rule");
    return r == null ? null : ruleRow(r);
  }

  private SplitRule requireRule() {
    var rule = ruleOrNull();
    if (rule == null) {
      throw conflict(
          "本租户尚未登记拆单规则。拆单会真实改变包裹形态、发货时效与逐单元回传口径，"
              + "「没配规则 = 默认全拆」会把一次运营疏忽变成一批错发的货，"
              + "本模块不提供隐含默认值：请先 PUT /trade/fulfillment/split/rule 登记规则。");
    }
    if (!rule.enabled()) {
      throw conflict("本租户的拆单规则当前处于停用状态（enabled=false）：先启用再拆单");
    }
    return rule;
  }

  /** 登记 / 更新拆单规则（upsert，一租户一条）。 */
  @Transactional
  public SplitRule saveRule(SplitRuleInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (input.maxUnitsPerSplit() == null) {
      throw bad("单次拆单最多拆几个履约单元必须给出");
    }
    boolean byWarehouse = nzBool(input.splitByWarehouse(), true);
    boolean byCarrier = nzBool(input.splitByCarrier(), true);
    boolean byTimeliness = nzBool(input.splitByTimeliness(), false);
    boolean byPlatform = nzBool(input.splitByPlatformRestriction(), true);
    if (!byWarehouse && !byCarrier && !byTimeliness && !byPlatform) {
      throw bad("四个拆分维度至少要开一个：全关就是「不许拆」，"
          + "而「暂停拆单」请用 enabled=false，两者是不同的意思");
    }
    SplitStrategy primary =
        input.primaryStrategy() == null ? SplitStrategy.WAREHOUSE : input.primaryStrategy();
    boolean primaryOn =
        switch (primary) {
          case WAREHOUSE -> byWarehouse;
          case CARRIER -> byCarrier;
          case TIMELINESS -> byTimeliness;
          case PLATFORM -> byPlatform;
        };
    if (!primaryOn) {
      throw bad("主维度 " + primary.name()
          + " 对应的开关必须同时打开：规则说「主维度是它」而它没开，分组时无据可依");
    }
    if (input.maxShipByHours() != null && input.maxShipByHours() <= 0) {
      throw bad("时效档位上限必须大于 0 小时；不限就留空，不要填 0");
    }
    if (input.stockFreshnessHours() != null && input.stockFreshnessHours() <= 0) {
      throw bad("库存新鲜度上限必须大于 0 小时；不限就留空，不要填 0");
    }
    boolean allowPartial = nzBool(input.allowPartialFulfillment(), true);
    UUID tenant = currentTenant();
    var existing = sql.fetchOne("select id from order_split_rule where tenant_id = ?", tenant);
    if (existing == null) {
      sql.execute(
          "insert into order_split_rule(id,tenant_id,enabled,split_by_warehouse,split_by_carrier,"
              + "split_by_timeliness,split_by_platform_restriction,primary_strategy,"
              + "max_units_per_split,allow_partial_fulfillment,max_ship_by_hours,"
              + "stock_freshness_hours,version,updated_by_membership_id,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,0,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          tenant,
          nzBool(input.enabled(), true),
          byWarehouse,
          byCarrier,
          byTimeliness,
          byPlatform,
          primary.name(),
          input.maxUnitsPerSplit(),
          allowPartial,
          input.maxShipByHours(),
          input.stockFreshnessHours(),
          actorId,
          OffsetDateTime.now(),
          OffsetDateTime.now());
      return rule();
    }
    sql.execute(
        "update order_split_rule set enabled=?,split_by_warehouse=?,split_by_carrier=?,"
            + "split_by_timeliness=?,split_by_platform_restriction=?,primary_strategy=?,"
            + "max_units_per_split=?,allow_partial_fulfillment=?,max_ship_by_hours=?,"
            + "stock_freshness_hours=?,version=version+1,updated_by_membership_id=?,"
            + "updated_at=?::timestamptz where tenant_id=?",
        nzBool(input.enabled(), true),
        byWarehouse,
        byCarrier,
        byTimeliness,
        byPlatform,
        primary.name(),
        input.maxUnitsPerSplit(),
        allowPartial,
        input.maxShipByHours(),
        input.stockFreshnessHours(),
        actorId,
        OffsetDateTime.now(),
        tenant);
    return rule();
  }

  // ================================================================ 发货仓

  public List<SplitWarehouseRow> warehouses(boolean activeOnly) {
    String where = activeOnly ? " where active = true" : "";
    return sql.fetch("select * from order_split_warehouse" + where + " order by warehouse_ref")
        .map(OrderSplitService::warehouseRow);
  }

  /** 仓档案读取。查不到就是 null（跨租户在 FORCE RLS 下同样查不到）。 */
  public SplitWarehouseRow warehouse(String warehouseRef) {
    Record r =
        sql.fetchOne(
            "select * from order_split_warehouse where tenant_id = " + TENANT_PREDICATE
                + " and warehouse_ref = ?", warehouseRef);
    return r == null ? null : warehouseRow(r);
  }

  /** 登记 / 更新发货仓（upsert，按 warehouse_ref）。 */
  @Transactional
  public SplitWarehouseRow saveWarehouse(SplitWarehouseInput input, UUID actorId) {
    if (input == null || notBlank(input.warehouseRef()) == false) {
      throw bad("仓引用码不能为空");
    }
    String platforms = nz(input.allowedPlatforms()).toUpperCase(Locale.ROOT).trim()
        .replace(" ", "");
    validatePlatformList(platforms);
    if (input.shipByHours() != null && input.shipByHours() < 0) {
      throw bad("承诺发货时效不能为负数；未知请留空（留空 = 未知，不是 0）");
    }
    String ref = input.warehouseRef().trim();
    var existing =
        sql.fetchOne(
            "select id from order_split_warehouse where tenant_id = " + TENANT_PREDICATE
                + " and warehouse_ref = ?", ref);
    if (existing == null) {
      sql.execute(
          "insert into order_split_warehouse(id,tenant_id,warehouse_ref,warehouse_name,"
              + "legal_entity_code,carrier_code,ship_by_hours,allowed_platforms,active,notes,"
              + "version,created_by_membership_id,created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,0,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          ref,
          nz(input.warehouseName()),
          nz(input.legalEntityCode()),
          nz(input.carrierCode()),
          input.shipByHours(),
          platforms,
          nzBool(input.active(), true),
          nz(input.notes()),
          actorId,
          OffsetDateTime.now(),
          OffsetDateTime.now());
    } else {
      sql.execute(
          "update order_split_warehouse set warehouse_name=?,legal_entity_code=?,carrier_code=?,"
              + "ship_by_hours=?,allowed_platforms=?,active=?,notes=?,version=version+1,"
              + "updated_at=?::timestamptz where tenant_id=" + TENANT_PREDICATE
              + " and warehouse_ref=?",
          nz(input.warehouseName()),
          nz(input.legalEntityCode()),
          nz(input.carrierCode()),
          input.shipByHours(),
          platforms,
          nzBool(input.active(), true),
          nz(input.notes()),
          OffsetDateTime.now(),
          ref);
    }
    return warehouse(ref);
  }

  private static void validatePlatformList(String platforms) {
    if (platforms.isEmpty()) {
      return;
    }
    for (String token : platforms.split(",", -1)) {
      if (token.isEmpty()) {
        throw bad("平台限制清单里有空项（如 'AMAZON,,SHOPIFY'）：空项会被当成一个匹配不到的平台，"
            + "于是限平台的仓被静默当成不限平台 —— 那正是错发");
      }
      if (!token.matches("[A-Z0-9_]+")) {
        throw bad("平台码 " + token + " 只能含大写字母、数字与下划线");
      }
    }
  }

  // ================================================================ 库存分布

  public List<SplitStockSnapshotRow> stockSnapshots(String warehouseRef, String skuRef) {
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(warehouseRef)) {
      where.add("warehouse_ref = ?");
      binds.add(warehouseRef.trim());
    }
    if (notBlank(skuRef)) {
      where.add("sku_ref = ?");
      binds.add(skuRef.trim());
    }
    String predicate =
        " where tenant_id = " + TENANT_PREDICATE
            + (where.isEmpty() ? "" : " and " + String.join(" and ", where));
    return sql.fetch(
            "select * from order_split_stock_snapshot" + predicate
                + " order by warehouse_ref, sku_ref limit 500", binds.toArray())
        .map(OrderSplitService::stockRow);
  }

  /** 登记 / 更新可用库存分布（upsert，同仓同 SKU 一条当前值）。 */
  @Transactional
  public SplitStockSnapshotRow saveStockSnapshot(SplitStockSnapshotInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (notBlank(input.warehouseRef()) == false) {
      throw bad("仓引用码不能为空");
    }
    if (notBlank(input.skuRef()) == false) {
      throw bad("SKU 逻辑引用不能为空");
    }
    if (input.availableQty() == null || input.availableQty().compareTo(BigDecimal.ZERO) < 0) {
      throw bad("可用量不能为负数");
    }
    if (input.collectedAt() == null) {
      throw bad("采集时刻必须显式给出：它表示「这批数据是什么时候观测到的」，"
          + "用写入时刻冒充就再也判断不出数据有多旧");
    }
    String source =
        notBlank(input.stockSource())
            ? input.stockSource().trim().toUpperCase(Locale.ROOT)
            : "TENANT_MAINTAINED";
    if (!source.equals("TENANT_MAINTAINED") && !source.equals("MODULE_7_1_1_3")) {
      throw bad("stockSource 只能是 MODULE_7_1_1_3 或 TENANT_MAINTAINED");
    }
    String ref = nz(input.sourceReference()).trim();
    if (source.equals("MODULE_7_1_1_3") && ref.isEmpty()) {
      throw bad("来源标为 MODULE_7_1_1_3 时必须给出处单号："
          + "7.1.1.3 是同步来的快照，没有出处的库存快照等于凭空捏一个数去决定从哪个仓发货");
    }
    String whRef = input.warehouseRef().trim();
    // 仓必须已登记：库存分布挂在已知的仓上，否则拆单时「这个仓走哪个承运商」无解。
    var wh =
        sql.fetchOne(
            "select id from order_split_warehouse where tenant_id = " + TENANT_PREDICATE
                + " and warehouse_ref = ?", whRef);
    if (wh == null) {
      throw bad("仓 " + whRef + " 未登记：先 POST /warehouses 登记仓档案，"
          + "否则这个仓的承运商、时效与平台限制都无处可查");
    }
    String sku = input.skuRef().trim();
    var existing =
        sql.fetchOne(
            "select id from order_split_stock_snapshot where tenant_id = " + TENANT_PREDICATE
                + " and warehouse_ref = ? and sku_ref = ?", whRef, sku);
    if (existing == null) {
      sql.execute(
          "insert into order_split_stock_snapshot(id,tenant_id,warehouse_ref,sku_ref,available_qty,"
              + "collected_at,stock_source,source_reference,notes,created_by_membership_id,"
              + "created_at,updated_at)"
              + " values(?,?,?,?,?,?::timestamptz,?,?,?,?,?::timestamptz,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          whRef,
          sku,
          qty(input.availableQty()),
          input.collectedAt(),
          source,
          ref,
          nz(input.notes()),
          actorId,
          OffsetDateTime.now(),
          OffsetDateTime.now());
    } else {
      sql.execute(
          "update order_split_stock_snapshot set available_qty=?,collected_at=?::timestamptz,"
              + "stock_source=?,source_reference=?,notes=?,updated_at=?::timestamptz"
              + " where tenant_id=" + TENANT_PREDICATE + " and warehouse_ref=? and sku_ref=?",
          qty(input.availableQty()),
          input.collectedAt(),
          source,
          ref,
          nz(input.notes()),
          OffsetDateTime.now(),
          whRef,
          sku);
    }
    return stockRow(
        sql.fetchOne(
            "select * from order_split_stock_snapshot where tenant_id = " + TENANT_PREDICATE
                + " and warehouse_ref = ? and sku_ref = ?", whRef, sku));
  }

  // ================================================================ 判定核心（预览与落库共用）

  /** plan 阶段的订单行（不可变）。 */
  record PlanLine(String orderLineRef, String skuRef, String skuCode, String skuName,
      BigDecimal qty, BigDecimal amount) {}

  /** plan 阶段某单元里对本行切走的一刀（数量定案后金额才回填）。 */
  static final class PlanSlice {
    final PlanLine line;
    final BigDecimal qty;
    BigDecimal amount = ZERO_MONEY;
    BigDecimal residual = ZERO_MONEY;

    PlanSlice(PlanLine line, BigDecimal qty) {
      this.line = line;
      this.qty = qty;
    }
  }

  /** plan 阶段的一个履约单元。 */
  static final class PlanUnit {
    final int unitSeq;
    final SplitStrategy strategy;
    final String warehouseRef;
    final String carrierCode;
    final Integer shipByHours;
    final OffsetDateTime shipByAt;
    final String platformCode;
    final String legalEntityCode;
    final List<PlanSlice> slices = new ArrayList<>();

    PlanUnit(int unitSeq, SplitStrategy strategy, String warehouseRef, String carrierCode,
        Integer shipByHours, OffsetDateTime shipByAt, String platformCode, String legalEntityCode) {
      this.unitSeq = unitSeq;
      this.strategy = strategy;
      this.warehouseRef = warehouseRef;
      this.carrierCode = carrierCode;
      this.shipByHours = shipByHours;
      this.shipByAt = shipByAt;
      this.platformCode = platformCode;
      this.legalEntityCode = legalEntityCode;
    }

    BigDecimal totalQty() {
      return slices.stream().map(s -> s.qty).reduce(ZERO_QTY, BigDecimal::add)
          .setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    BigDecimal allocAmount() {
      return slices.stream().map(s -> s.amount).reduce(ZERO_MONEY, BigDecimal::add)
          .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
  }

  /** plan 阶段的一条候选依据。groupKey 为空的落选候选挂到第一个单元上，保证可查。 */
  record PlanCandidate(
      String groupKey, String orderLineRef, String warehouseRef, String carrierCode,
      boolean selected, String rejectReason, String rejectDetail, BigDecimal availableQty,
      BigDecimal demandQty, OffsetDateTime collectedAt, String stockSource, int rankNo) {}

  /** 完整拆单计划。 */
  record Plan(
      SplitStrategy strategy, List<PlanUnit> units, List<PlanCandidate> candidates,
      BigDecimal orderTotal, BigDecimal allocatedTotal, BigDecimal residualTotal,
      String currency, String orderSource, String platformCode, String shopCode,
      String platformOrderRef, OffsetDateTime plannedAt, SplitRule rule, String blockedReason,
      int reservationCount) {

    boolean amountConserved() {
      return allocatedTotal.add(residualTotal).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
          .compareTo(orderTotal.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) == 0;
    }
  }

  private record ResolvedOrder(List<PlanLine> lines, String currency, String orderSource) {}

  private record Unfilled(PlanLine line, BigDecimal remaining) {}

  /** 一个仓的一格库存（某 SKU）。 */
  private record StockCell(BigDecimal availableQty, OffsetDateTime collectedAt, String stockSource) {}

  /**
   * 拆单预览（<b>无副作用</b>：不写 order_split* 任何一张表）。
   *
   * <p>与 {@link #split} 共用 {@link #plan} —— 两边各算一次必然出现
   * 「预览说是 3 个单元、落库变 2 个」这种最消耗信任的偏差。
   */
  public SplitPreview preview(SplitInput input) {
    Plan plan = plan(input);
    List<PreviewUnit> units = new ArrayList<>();
    for (PlanUnit u : plan.units()) {
      units.add(
          new PreviewUnit(
              u.unitSeq, u.strategy, u.warehouseRef, u.carrierCode, u.shipByHours, u.shipByAt,
              u.platformCode, u.legalEntityCode, u.slices.size(), u.totalQty(), u.allocAmount()));
    }
    List<PreviewCandidate> candidates = new ArrayList<>();
    for (PlanCandidate c : plan.candidates()) {
      candidates.add(
          new PreviewCandidate(
              c.orderLineRef(), c.warehouseRef(), c.carrierCode(), c.selected(), c.rejectReason(),
              c.rejectReason().isEmpty() ? "" : RejectReason.valueOf(c.rejectReason()).label(),
              c.rejectDetail(), qty(c.availableQty()), qty(c.demandQty()), c.rankNo()));
    }
    return new SplitPreview(
        plan.strategy(), units, candidates, money(plan.orderTotal()),
        money(plan.allocatedTotal()), money(plan.residualTotal()), plan.amountConserved(),
        false, plan.orderSource(), plan.rule() != null, plan.blockedReason());
  }

  /**
   * 落一次拆单（头 + 单元 + 行级明细 + 候选依据 + 预占指令 + 回传任务）。
   *
   * <p><b>汇总数与金额守恒一个都不在这里写</b>：unit_count / total_qty /
   * allocated_amount_total / residual_amount_total 与守恒等式全部由 V151 的
   * 延迟约束触发器在提交时从明细实算并复核。服务层自己算一遍再写进去，
   * 就会出现「明细 3 个单元、头写 2 个」这种自相矛盾的凭据。
   */
  @Transactional
  public SplitSaved split(SplitInput input, UUID actorId) {
    if (input == null) {
      throw bad("请求体不能为空");
    }
    if (ruleOrNull() == null) {
      throw conflict("本租户尚未登记拆单规则：拆单会真实改变包裹形态与逐单元回传口径，"
          + "本模块不提供隐含默认值，请先 PUT /trade/fulfillment/split/rule 登记规则。");
    }
    Plan plan = plan(input);
    if (plan.blockedReason() != null) {
      throw bad(plan.blockedReason());
    }
    UUID tenant = currentTenant();
    UUID splitId = UUID.randomUUID();
    String splitNo = newSplitNo(plan.plannedAt(), splitId);

    sql.execute(
        "insert into order_split(id,tenant_id,split_no,status,order_ref,order_no,order_source,"
            + "order_origin_platform,order_origin_shop,platform_order_ref,recipient_name,"
            + "recipient_phone,recipient_email,country_code,address_line,currency_code,"
            + "order_total_amount,creates_sales_order,strategy,planned_at,note,version,"
            + "created_by_membership_id,created_at,updated_at)"
            + " values(?,?,?,'PLANNED',?,?,?,?,?,?,?,?,?,?,?,?,?,false,?,?::timestamptz,?,0,?,"
            + "?::timestamptz,?::timestamptz)",
        splitId, tenant, splitNo,
        input.orderRef().trim(), nz(input.orderNo()), plan.orderSource(),
        nz(input.platformCode()), nz(input.shopCode()), nz(input.platformOrderRef()),
        nz(input.recipientName()), nz(input.recipientPhone()), nz(input.recipientEmail()),
        nz(input.countryCode()).toUpperCase(Locale.ROOT), nz(input.addressLine()),
        plan.currency(), money(plan.orderTotal()), plan.strategy().name(), plan.plannedAt(),
        nz(input.note()), actorId, OffsetDateTime.now(), OffsetDateTime.now());

    Map<String, UUID> unitIdsByGroupKey = new LinkedHashMap<>();
    for (PlanUnit u : plan.units()) {
      UUID unitId = UUID.randomUUID();
      unitIdsByGroupKey.put(groupKeyOf(u), unitId);
      sql.execute(
          "insert into order_split_unit(id,tenant_id,split_id,unit_seq,unit_status,unit_strategy,"
              + "warehouse_ref,carrier_code,ship_by_at,ship_by_hours,platform_code,"
              + "legal_entity_code,note,created_at,updated_at)"
              + " values(?,?,?,?,'PLANNED',?,?,?,?::timestamptz,?,?,?,?,?::timestamptz,?::timestamptz)",
          unitId, tenant, splitId, u.unitSeq, u.strategy.name(), u.warehouseRef, u.carrierCode,
          u.shipByAt, u.shipByHours, u.platformCode, u.legalEntityCode, "", OffsetDateTime.now(),
          OffsetDateTime.now());

      for (PlanSlice s : u.slices) {
        sql.execute(
            "insert into order_split_unit_line(id,tenant_id,split_id,unit_id,order_line_ref,"
                + "sku_ref,sku_code,sku_name,allocated_qty,allocated_amount,residual_amount,"
                + "created_at) values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
            UUID.randomUUID(), tenant, splitId, unitId, s.line.orderLineRef(), s.line.skuRef(),
            s.line.skuCode(), s.line.skuName(), qty(s.qty), money(s.amount), money(s.residual),
            OffsetDateTime.now());

        // 蓝图输出②：预占**指令**登记（不是预占事实 —— 7 预占在 warehouse 独立库）。
        sql.execute(
            "insert into order_split_reservation_command(id,tenant_id,split_id,unit_id,sku_ref,"
                + "order_line_ref,reserved_qty,warehouse_ref,target_service,command_source,"
                + "command_status,upstream_reserved,created_by_membership_id,created_at,updated_at)"
                + " values(?,?,?,?,?,?,?,?,'WAREHOUSE','SPLIT_PLAN','REQUESTED',false,?,"
                + "?::timestamptz,?::timestamptz)",
            UUID.randomUUID(), tenant, splitId, unitId, s.line.skuRef(), s.line.orderLineRef(),
            qty(s.qty), u.warehouseRef, actorId, OffsetDateTime.now(), OffsetDateTime.now());
      }

      // 蓝图输出③：发货回传按拆分执行 —— **一单元一条**（UNIQUE(tenant_id,unit_id)）。
      // 非平台单不建回传任务：手工单没有平台可回传，给它造一个任务是给「回传零失败」
      // 这个指标掺水（与 V144 合单同一纪律）。
      if (notBlank(plan.platformCode())) {
        sql.execute(
            "insert into order_split_writeback_task(id,tenant_id,split_id,unit_id,platform_code,"
                + "shop_code,platform_order_ref,carrier_code,status,attempt_count,created_at,"
                + "updated_at) values(?,?,?,?,?,?,?,?,'PENDING',0,?::timestamptz,?::timestamptz)",
            UUID.randomUUID(), tenant, splitId, unitId, plan.platformCode(), nz(plan.shopCode()),
            nz(plan.platformOrderRef()), u.carrierCode, OffsetDateTime.now(),
            OffsetDateTime.now());
      }
    }

    // 候选依据落库：库存会被补上、仓会被停用、规则会被改，
    // 查询时联算只能回答「现在重拆会怎么拆」，答不出「当时为什么这么拆」。
    UUID fallbackUnitId = unitIdsByGroupKey.values().stream().findFirst().orElse(null);
    for (PlanCandidate c : plan.candidates()) {
      UUID unitId =
          c.groupKey() == null ? fallbackUnitId : unitIdsByGroupKey.getOrDefault(c.groupKey(),
              fallbackUnitId);
      if (unitId == null) {
        continue;
      }
      sql.execute(
          "insert into order_split_candidate(id,tenant_id,split_id,unit_id,order_line_ref,"
              + "warehouse_ref,carrier_code,selected,reject_reason,reject_detail,available_qty,"
              + "demand_qty,collected_at,stock_source,rank_no,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz)",
          UUID.randomUUID(), tenant, splitId, unitId, c.orderLineRef(), c.warehouseRef(),
          c.carrierCode(), c.selected(), c.rejectReason(), c.rejectDetail(),
          qty(c.availableQty()), qty(c.demandQty()), c.collectedAt(), nz(c.stockSource()),
          c.rankNo(), OffsetDateTime.now());
    }

    appendEvent(
        splitId, "SPLIT_PLANNED", "", "PLANNED", actorId,
        "按 " + plan.strategy().label() + " 拆出 " + plan.units().size() + " 个履约单元；"
            + "订单总额 " + money(plan.orderTotal()) + " 守恒（分摊 " + money(plan.allocatedTotal())
            + " + 尾差 " + money(plan.residualTotal()) + "）；**未产生新的销售订单**",
        plan.plannedAt());
    appendEvent(
        splitId, "RESERVATION_COMMAND_ISSUED", "", "REQUESTED", actorId,
        "已登记 " + plan.reservationCount() + " 条库存预占指令（目标 warehouse 服务；"
            + "本模块不预占上游库存）",
        OffsetDateTime.now());

    // 读回一次：返回值的守恒结论来自 DB 实算，而不是服务层的自说自话。
    Record fresh = headOr404(splitId);
    return new SplitSaved(
        splitId, splitNo, SplitStatus.PLANNED, fresh.get("unit_count", Integer.class),
        money(fresh.get("allocated_amount_total", BigDecimal.class)),
        money(fresh.get("residual_amount_total", BigDecimal.class)), amountConservedOf(fresh),
        "已按" + plan.strategy().label() + "拆成 " + fresh.get("unit_count", Integer.class)
            + " 个履约单元并登记 " + plan.reservationCount()
            + " 条预占指令；订单总额与买家视角不变（"
            + money(fresh.get("order_total_amount", BigDecimal.class))
            + "），未产生新的销售订单。");
  }

  /**
   * 拆单判定核心：把订单切成履约单元，并逐条记录候选与落选原因。
   *
   * <p><b>两条口径必须写死</b>：
   *
   * <ul>
   *   <li><b>守恒的是订单总额（买家视角）</b>，不是行货值。各单元分摊额之和 + 尾差
   *       恰好等于 order_total_amount；行金额只作<b>分摊权重</b>
   *       （行金额占比 × 数量占比）—— 运费与税挂在订单上而不挂在某一行上，
   *       按行切货值会让买家看到的总额变。
   *   <li><b>排不下的货怎么办</b>：能切多少切多少，切不动的部分<b>显式落成落选候选</b>
   *       并在 blockedReason 里说清「还剩多少没排上」。绝不静默塞给某一个仓 ——
   *       那等于货权错记，比晚发严重得多。
   * </ul>
   */
  private Plan plan(SplitInput input) {
    if (notBlank(input.orderRef()) == false) {
      throw bad("订单引用不能为空");
    }
    if (input.orderTotalAmount() == null) {
      throw bad("订单总金额必须给出：拆单全程不变的是它，守恒校验也比的是它");
    }
    if (input.orderTotalAmount().compareTo(BigDecimal.ZERO) < 0) {
      throw bad("订单总金额不能为负数");
    }
    OffsetDateTime at = input.plannedAt();
    if (at == null) {
      throw bad("拆分时刻必须显式给出：本模块记账的是一个已发生的动作，不取服务器当前时间");
    }

    SplitRule rule = ruleOrNull();
    ResolvedOrder order = resolveOrder(input);
    String platform = nz(input.platformCode()).toUpperCase(Locale.ROOT);
    String shop = nz(input.shopCode());
    String platformOrderRef = nz(input.platformOrderRef());

    BigDecimal totalLineAmount =
        order.lines().stream().map(PlanLine::amount).reduce(ZERO_MONEY, BigDecimal::add)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (rule != null && totalLineAmount.compareTo(BigDecimal.ZERO) <= 0) {
      throw bad("订单行金额合计为 0，无法按行金额占比分摊订单总额：守恒要求各单元分摊之和"
          + "等于订单总额，没有分摊权重就只能靠拍一个数");
    }

    // --- 候选仓与库存分布（一条真 SQL；按 warehouse_ref 字典序，天然确定性）-----
    List<String> skuRefs =
        order.lines().stream().map(PlanLine::skuRef).filter(s -> !s.isBlank()).distinct().toList();
    if (skuRefs.isEmpty()) {
      throw bad("订单行没有 SKU 引用，无法与可用库存分布对上："
          + "请在订单行快照里给出 skuRef（它是 catalog 的逻辑引用）");
    }
    Map<String, WarehouseFacts> warehouses = loadWarehouses(skuRefs);
    if (warehouses.isEmpty()) {
      throw conflict(
          "本租户尚未登记任何在用的发货仓：请先 POST /trade/fulfillment/split/warehouses 登记仓档案"
              + "并 POST /stock-snapshots 登记可用库存分布，否则没有可拆的依据");
    }

    // --- 逐行逐仓判定 ---------------------------------------------------------
    // 策略必须在循环前定下来：eligibility 要用它判「这个仓能不能成为一个时效单元」。
    SplitStrategy strategy = rule == null ? SplitStrategy.WAREHOUSE : rule.primaryStrategy();
    List<PlanCandidate> candidates = new ArrayList<>();
    Map<String, List<PlanSlice>> groups = new LinkedHashMap<>();
    Map<String, WarehouseFacts> groupOwner = new LinkedHashMap<>();
    // 每一行被切走的总数量（跨仓累加）。金额分摊要用它算「本单元占本行的比例」。
    Map<PlanLine, BigDecimal> lineTotalTaken = new LinkedHashMap<>();
    List<Unfilled> unfilled = new ArrayList<>();
    int rank = 0;

    List<PlanLine> lines = new ArrayList<>(order.lines());
    // 行按引用字典序处理：同一份输入两次拆单，行与仓的配对顺序完全一致。
    lines.sort(Comparator.comparing(PlanLine::orderLineRef));

    for (PlanLine line : lines) {
      BigDecimal remaining = qty(line.qty());
      Map<String, BigDecimal> takenPerWarehouse = new LinkedHashMap<>();

      for (WarehouseFacts w : warehouses.values()) {
        rank++;
        StockCell cell = w.stock.get(line.skuRef());
        BigDecimal available = cell == null ? ZERO_QTY : cell.availableQty();
        OffsetDateTime collected = cell == null ? null : cell.collectedAt();
        String stockSource = cell == null ? "" : cell.stockSource();

        String reject = eligibility(w, platform, cell, remaining, takenPerWarehouse, rule, strategy,
            at);
        String key = w.groupKey(platform);
        if (reject != null) {
          candidates.add(
              candidate(key, line, w, false, reject, rejectDetail(reject, available, remaining),
                  available, remaining, collected, stockSource, rank));
          continue;
        }

        BigDecimal take = remaining.min(available).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (take.compareTo(BigDecimal.ZERO) <= 0) {
          candidates.add(
              candidate(key, line, w, false, "INSUFFICIENT_STOCK",
                  "该仓对该行已无可用库存", available, remaining, collected, stockSource, rank));
          continue;
        }
        if (rule != null && !rule.allowPartialFulfillment()
            && take.compareTo(remaining) < 0) {
          candidates.add(
              candidate(key, line, w, false, "PARTIAL_FULFILMENT_DISABLED",
                  "本租户规则不允许部分先发，而该仓可用 " + qty(available) + " 装不下本行剩余 "
                      + qty(remaining),
                  available, remaining, collected, stockSource, rank));
          continue;
        }

        takenPerWarehouse.merge(w.ref, take, BigDecimal::add);
        remaining = remaining.subtract(take).setScale(QTY_SCALE, RoundingMode.HALF_UP);
        lineTotalTaken.merge(line, take, BigDecimal::add);
        groups.computeIfAbsent(key, k -> new ArrayList<>()).add(new PlanSlice(line, take));
        groupOwner.putIfAbsent(key, w);
        candidates.add(
            candidate(key, line, w, true, "",
                "按 warehouse_ref 字典序第 " + rank + " 个候选命中，切走 " + qty(take),
                available, take, collected, stockSource, rank));
      }
      if (remaining.compareTo(BigDecimal.ZERO) > 0) {
        unfilled.add(new Unfilled(line, remaining));
      }
    }

    // ⚠️ 「一个仓都承接不了」**不抛异常**，而是产出空方案 + 落选候选：
    // 直接抛 409 会把「为什么这批货一个仓都发不出去」这句话吃掉，
    // 而这恰恰是运营唯一需要看到的答案。真正落库时由 blockedReason 挡住。
    // --- 分组成履约单元（字典序定序）-----------------------------------------
    List<String> keys = new ArrayList<>(groups.keySet());
    keys.sort(Comparator.naturalOrder());

    List<PlanUnit> units = new ArrayList<>();
    Map<String, PlanUnit> unitByKey = new LinkedHashMap<>();
    int seq = 1;
    for (String key : keys) {
      WarehouseFacts w = groupOwner.get(key);
      boolean timelinessUnit = strategy == SplitStrategy.TIMELINESS && w.hours != null;
      OffsetDateTime shipByAt = timelinessUnit ? at.plusHours(w.hours) : null;
      PlanUnit u =
          new PlanUnit(
              seq++, strategy, w.ref, w.carrier,
              w.hours, shipByAt, platform, w.legalEntity);
      u.slices.addAll(groups.get(key));
      units.add(u);
      unitByKey.put(key, u);
    }

    // --- 金额分摊与尾差归属 ---------------------------------------------------
    assignAmounts(units, lineTotalTaken, totalLineAmount, money(input.orderTotalAmount()));

    BigDecimal allocated =
        units.stream().map(PlanUnit::allocAmount).reduce(ZERO_MONEY, BigDecimal::add)
            .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal residual =
        units.stream().flatMap(u -> u.slices.stream()).map(s -> s.residual)
            .reduce(ZERO_MONEY, BigDecimal::add).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    String blocked = blockedReason(rule, units, unfilled);

    // 落选候选的 groupKey 指向它本来的仓；挂不到单元（该仓没分到货）就挂第一个单元，
    // 保证「为什么没选它」永远查得到。
    List<PlanCandidate> finalCandidates = new ArrayList<>();
    for (PlanCandidate c : candidates) {
      finalCandidates.add(
          unitByKey.containsKey(c.groupKey())
              ? c
              : new PlanCandidate(null, c.orderLineRef(), c.warehouseRef(), c.carrierCode(),
                  c.selected(), c.rejectReason(), c.rejectDetail(), c.availableQty(),
                  c.demandQty(), c.collectedAt(), c.stockSource(), c.rankNo()));
    }

    int reservationCount =
        units.stream().mapToInt(u -> u.slices.size()).sum();
    return new Plan(
        strategy, units, finalCandidates, money(input.orderTotalAmount()), allocated, residual,
        order.currency(), order.orderSource(), platform, shop, platformOrderRef, at, rule,
        blocked, reservationCount);
  }

  private static PlanCandidate candidate(
      String key, PlanLine line, WarehouseFacts w, boolean selected, String rejectReason,
      String rejectDetail, BigDecimal available, BigDecimal demand, OffsetDateTime collected,
      String stockSource, int rank) {
    return new PlanCandidate(
        key, line.orderLineRef(), w.ref, w.carrier, selected, rejectReason, rejectDetail,
        available, demand, collected, stockSource, rank);
  }

  /**
   * 金额分摊与尾差归属。
   *
   * <p><b>分摊口径</b>：一个单元行的分摊额 = 订单总额 × (该行金额 / 全部行金额)
   * × (本单元切走的数量 / 该行切走的总数量)，HALF_UP 到 4 位。
   *
   * <p><b>尾差归属</b>：舍入后各行之和与订单总额的差额，确定性地归到一行 ——
   * 金额最大者，同额按 (unit_seq, order_line_ref) 字典序。差额有承载，不静默吞掉
   * （DB 侧另有部分唯一索引与触发器复核「至多一行承载」）。
   */
  private static void assignAmounts(
      List<PlanUnit> units, Map<PlanLine, BigDecimal> lineTotalTaken,
      BigDecimal totalLineAmount, BigDecimal orderTotal) {

    BigDecimal allocatedSum = ZERO_MONEY;
    for (PlanUnit u : units) {
      for (PlanSlice s : u.slices) {
        BigDecimal lineTaken = lineTotalTaken.getOrDefault(s.line, ZERO_QTY);
        BigDecimal qtyShare =
            lineTaken.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : s.qty.divide(lineTaken, 12, RoundingMode.HALF_UP);
        BigDecimal amountShare =
            totalLineAmount.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : s.line.amount().divide(totalLineAmount, 12, RoundingMode.HALF_UP);
        s.amount = money(orderTotal.multiply(qtyShare).multiply(amountShare));
        allocatedSum = allocatedSum.add(s.amount);
      }
    }
    allocatedSum = allocatedSum.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal residual = orderTotal.subtract(allocatedSum).setScale(MONEY_SCALE, RoundingMode.HALF_UP);

    // 「金额最大者；同额按 (unit_seq, order_line_ref) 字典序」——确定性归属。
    PlanSlice target = null;
    int targetSeq = Integer.MAX_VALUE;
    String targetRef = null;
    for (PlanUnit u : units) {
      for (PlanSlice s : u.slices) {
        boolean take;
        if (target == null) {
          take = true;
        } else if (s.amount.compareTo(target.amount) != 0) {
          take = s.amount.compareTo(target.amount) > 0;
        } else {
          take =
              u.unitSeq < targetSeq
                  || (u.unitSeq == targetSeq
                      && s.line.orderLineRef().compareTo(targetRef) < 0);
        }
        if (take) {
          target = s;
          targetSeq = u.unitSeq;
          targetRef = s.line.orderLineRef();
        }
      }
    }
    if (target != null) {
      target.residual = residual;
    }
  }

  /**
   * 解析订单内容。
   *
   * <p>两条路径都是真实数据，没有 mock：
   *
   * <ul>
   *   <li><b>SALES_ORDER</b>：按 tenant 从 sales_order + sales_order_line 真实联表读取
   *       （V99 已交付）。显式指定而查不到 → 404（调用方要知道自己给错了）。
   *   <li><b>REQUEST_SNAPSHOT</b>：用请求里的 lines —— 蓝图输入写的就是「拆单请求」，
   *       快照是真实业务输入。显式指定 SALES_ORDER 而读不到时**不回落到快照**，
   *       否则界面会以为读到了订单真值。
   * </ul>
   */
  private ResolvedOrder resolveOrder(SplitInput input) {
    String requested =
        notBlank(input.orderSource())
            ? input.orderSource().trim().toUpperCase(Locale.ROOT) : "AUTO";

    if (requested.equals("SALES_ORDER") || requested.equals("AUTO")) {
      List<Record> rows =
          sql.fetch(
              "select l.id::text line_ref, l.sku_id::text sku_ref, l.sku_code, l.sku_name,"
                  + " l.quantity, l.line_amount, o.currency"
                  + " from sales_order o join sales_order_line l"
                  + "   on l.tenant_id = o.tenant_id and l.order_id = o.id"
                  + " where o.tenant_id = " + TENANT_PREDICATE + " and o.id::text = ?"
                  + " order by l.line_no",
              input.orderRef().trim());
      if (!rows.isEmpty()) {
        Record head =
            sql.fetchOne(
                "select currency from sales_order where tenant_id = " + TENANT_PREDICATE
                    + " and id::text = ?", input.orderRef().trim());
        List<PlanLine> lines = new ArrayList<>();
        for (Record r : rows) {
          lines.add(
              new PlanLine(
                  r.get("line_ref", String.class), nz(r.get("sku_ref", String.class)),
                  nz(r.get("sku_code", String.class)), nz(r.get("sku_name", String.class)),
                  qty(r.get("quantity", BigDecimal.class)),
                  money(r.get("line_amount", BigDecimal.class))));
        }
        return new ResolvedOrder(
            lines, head == null ? nz(input.currencyCode()) : head.get("currency", String.class),
            "SALES_ORDER");
      }
      if (requested.equals("SALES_ORDER")) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND,
            "订单引用 " + input.orderRef().trim() + " 在本租户的 sales_order 里读不到："
                + "本模块不校验订单存在性（上游 9.2 审单未交付），"
                + "请改用 orderSource=REQUEST_SNAPSHOT 并随请求带上订单行快照");
      }
    }

    List<OrderLineInput> inputLines = input.lines();
    if (inputLines == null || inputLines.isEmpty()) {
      throw bad("订单行快照不能为空：orderSource=REQUEST_SNAPSHOT 时必须随请求带上订单行，"
          + "否则没有可切分的数量与金额");
    }
    if (nz(input.currencyCode()).isEmpty()) {
      throw bad("币种必须给出：订单总金额是带着币种口径的，缺币种会让守恒校验失去意义");
    }
    List<PlanLine> lines = new ArrayList<>();
    for (OrderLineInput l : inputLines) {
      if (l.qty() == null || l.qty().compareTo(BigDecimal.ZERO) <= 0) {
        throw bad("订单行 " + l.orderLineRef() + " 的数量必须大于 0");
      }
      if (l.amount() == null || l.amount().compareTo(BigDecimal.ZERO) < 0) {
        throw bad("订单行 " + l.orderLineRef() + " 的金额不能为负数");
      }
      lines.add(
          new PlanLine(l.orderLineRef().trim(), nz(l.skuRef()), nz(l.skuCode()), nz(l.skuName()),
              qty(l.qty()), money(l.amount())));
    }
    return new ResolvedOrder(
        lines, input.currencyCode().trim().toUpperCase(Locale.ROOT), "REQUEST_SNAPSHOT");
  }

  /** 一个仓在本租户的判定事实。 */
  private static final class WarehouseFacts {
    final String ref;
    final String carrier;
    final Integer hours;
    final String legalEntity;
    final List<String> allowedPlatforms;
    final Map<String, StockCell> stock = new LinkedHashMap<>();

    WarehouseFacts(String ref, String carrier, Integer hours, String legalEntity,
        List<String> allowedPlatforms) {
      this.ref = ref;
      this.carrier = carrier;
      this.hours = hours;
      this.legalEntity = legalEntity;
      this.allowedPlatforms = allowedPlatforms;
    }

    String groupKey(String platform) {
      return ref + "|" + carrier + "|" + (hours == null ? "" : String.format("%06d", hours))
          + "|" + platform;
    }
  }

  private static String groupKeyOf(PlanUnit u) {
    return u.warehouseRef + "|" + u.carrierCode + "|"
        + (u.shipByHours == null ? "" : String.format("%06d", u.shipByHours)) + "|"
        + u.platformCode;
  }

  /**
   * 候选仓与库存分布（<b>一条真 SQL</b>，按 warehouse_ref 字典序返回）。
   *
   * <p>用 LEFT JOIN 而不是 INNER JOIN：没登记库存的仓也要出现在候选里 ——
   * 它会以 INSUFFICIENT_STOCK 落选，而「这个仓根本没录库存」与「这个仓卖光了」
   * 是两件要区分的事，INNER JOIN 会把前者直接藏掉。
   */
  private Map<String, WarehouseFacts> loadWarehouses(List<String> skuRefs) {
    StringBuilder in = new StringBuilder("(");
    List<Object> binds = new ArrayList<>();
    for (int i = 0; i < skuRefs.size(); i++) {
      in.append(i == 0 ? "?" : ",?");
      binds.add(skuRefs.get(i));
    }
    in.append(')');
    List<Record> rows =
        sql.fetch(
            "select w.warehouse_ref, w.carrier_code, w.ship_by_hours, w.allowed_platforms,"
                + " w.legal_entity_code, s.sku_ref, s.available_qty, s.collected_at, s.stock_source"
                + " from order_split_warehouse w"
                + " left join order_split_stock_snapshot s"
                + "   on s.tenant_id = w.tenant_id and s.warehouse_ref = w.warehouse_ref"
                + "  and s.sku_ref in " + in
                + " where w.tenant_id = " + TENANT_PREDICATE + " and w.active = true"
                + " order by w.warehouse_ref, s.sku_ref",
            binds.toArray());

    Map<String, WarehouseFacts> out = new LinkedHashMap<>();
    for (Record r : rows) {
      String ref = r.get("warehouse_ref", String.class);
      WarehouseFacts w =
          out.computeIfAbsent(
              ref,
              k -> new WarehouseFacts(
                  k, nz(r.get("carrier_code", String.class)),
                  r.get("ship_by_hours", Integer.class),
                  nz(r.get("legal_entity_code", String.class)),
                  allowedPlatforms(nz(r.get("allowed_platforms", String.class)))));
      String sku = r.get("sku_ref", String.class);
      if (sku != null) {
        w.stock.put(
            sku,
            new StockCell(
                qty(r.get("available_qty", BigDecimal.class)),
                r.get("collected_at", OffsetDateTime.class),
                nz(r.get("stock_source", String.class))));
      }
    }
    return out;
  }

  private static List<String> allowedPlatforms(String list) {
    return list.isEmpty() ? List.of() : List.of(list.split(","));
  }

  /**
   * 候选仓对这一行是否可用（返回 null 表示通过）。
   *
   * <p>顺序：时效可成单元 → 平台限制 → 时效上限 → 库存新鲜度 → 承运商 → 库存量。
   * 事实类排前面，运营看到的拒绝原因才是「真正卡住它的那一条」。
   */
  private static String eligibility(
      WarehouseFacts w, String platform, StockCell cell, BigDecimal remaining,
      Map<String, BigDecimal> takenPerWarehouse, SplitRule rule, SplitStrategy strategy,
      OffsetDateTime at) {
    // 🔴 按时效拆单时，**时效未知的仓成不了单元**：DB 上 order_split_unit 有
    //    CHECK(unit_strategy<>'TIMELINESS' OR (ship_by_at IS NOT NULL AND ship_by_hours IS NOT NULL))。
    //    让它进来就会在提交时被那条 CHECK 拒掉，错误信息还是数据库的形状，
    //    运营看不懂「为什么这个仓不能按时效拆」—— 所以在这里就带上可读原因挡掉。
    if (strategy == SplitStrategy.TIMELINESS && w.hours == null) {
      return "SHIP_BY_TOO_SLOW";
    }
    // 平台限制：无平台归属（非平台单）时无从判定，按「不限平台」处理并在 capability 里说明。
    if (!platform.isEmpty() && !w.allowedPlatforms.isEmpty()
        && !w.allowedPlatforms.contains(platform)) {
      return "PLATFORM_RESTRICTED";
    }
    if (rule != null && rule.maxShipByHours() != null) {
      // 时效未知 ≠ 时效达标：未知仓排不进时效档，当成最快就是把最慢的仓排到第一刀。
      if (w.hours == null || w.hours > rule.maxShipByHours()) {
        return "SHIP_BY_TOO_SLOW";
      }
    }
    if (rule != null && rule.stockFreshnessHours() != null) {
      if (cell == null || cell.collectedAt() == null
          || cell.collectedAt().isBefore(at.minusHours(rule.stockFreshnessHours()))) {
        return "STOCK_TOO_STALE";
      }
    }
    if (rule != null && rule.splitByCarrier() && w.carrier.isEmpty()) {
      return "NO_CARRIER";
    }
    BigDecimal available = cell == null ? ZERO_QTY : cell.availableQty();
    if (available.compareTo(BigDecimal.ZERO) <= 0) {
      return "INSUFFICIENT_STOCK";
    }
    BigDecimal already = takenPerWarehouse.getOrDefault(w.ref, ZERO_QTY);
    if (already.compareTo(BigDecimal.ZERO) > 0 && available.compareTo(already) <= 0) {
      return "SAME_WAREHOUSE_ALREADY_FULL";
    }
    return null;
  }

  private static String rejectDetail(String reason, BigDecimal available, BigDecimal remaining) {
    return switch (RejectReason.valueOf(reason)) {
      case INSUFFICIENT_STOCK -> "该仓对该行可用 " + qty(available) + "，需求 " + qty(remaining);
      case PLATFORM_RESTRICTED -> "该仓的平台限制清单里没有本单所属平台";
      case SHIP_BY_TOO_SLOW -> "该仓的承诺发货时效不满足本租户规则的上限（或该仓时效未知）";
      case STOCK_TOO_STALE -> "该仓的库存采集时刻早于本租户规则允许的新鲜度（或根本没登记）";
      case NO_CARRIER -> "该仓没有登记承运商";
      case WAREHOUSE_INACTIVE -> "该仓已停用";
      case SAME_WAREHOUSE_ALREADY_FULL -> "该仓对本行的可用量已被本行前面的切分用完";
      case BEATEN_BY_TIEBREAK -> "同条件下按 warehouse_ref 字典序排在更前面";
      case MAX_UNITS_EXCEEDED -> "再切会超过本租户规则的单元数上限";
      case PARTIAL_FULFILMENT_DISABLED -> "本租户规则不允许部分先发";
    };
  }

  private static String blockedReason(SplitRule rule, List<PlanUnit> units, List<Unfilled> unfilled) {
    if (rule == null) {
      return "本租户尚未登记拆单规则：可以预览方案，但落拆单会被拒绝（见 capabilities）";
    }
    if (units.size() > rule.maxUnitsPerSplit()) {
      return "本次拆出 " + units.size() + " 个履约单元，超过本租户规则的上限 "
          + rule.maxUnitsPerSplit() + " 个：请缩小拆分范围或调高上限";
    }
    if (units.isEmpty()) {
      return "没有任何仓能承接这张订单的任何一行：候选仓全部落选，"
          + "逐条原因见预览接口的 candidates（通常是库存未登记、平台受限或时效不达标）";
    }
    if (!unfilled.isEmpty()) {
      BigDecimal lost = ZERO_QTY;
      for (Unfilled u : unfilled) {
        lost = lost.add(u.remaining());
      }
      return "还有 " + qty(lost) + " 件在所有仓都排不下。"
          + "本模块不静默塞给某一个仓 —— 货权错记比晚发严重得多。"
          + "请补库存分布、放宽平台限制，或调整规则";
    }
    return null;
  }

  // ================================================================ 拆单查询

  /** 拆单列表（真分页；三组汇总额不受分页影响）。 */
  public SplitPage pageSplits(SplitQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (notBlank(query.keyword())) {
      where.add("(s.split_no ilike ? or s.order_no ilike ? or s.order_ref ilike ?)");
      String kw = "%" + query.keyword().trim() + "%";
      binds.add(kw);
      binds.add(kw);
      binds.add(kw);
    }
    if (notBlank(query.status())) {
      where.add("s.status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.strategy())) {
      where.add("s.strategy = ?");
      binds.add(query.strategy().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.orderRef())) {
      where.add("s.order_ref = ?");
      binds.add(query.orderRef().trim());
    }
    if (notBlank(query.warehouseRef())) {
      // 仓在单元行上，用 EXISTS 而不是 JOIN，避免同一仓多单元把头行放大成多行。
      where.add("exists (select 1 from order_split_unit u where u.tenant_id = s.tenant_id"
          + " and u.split_id = s.id and u.warehouse_ref = ?)");
      binds.add(query.warehouseRef().trim());
    }
    String predicate =
        " where s.tenant_id = " + TENANT_PREDICATE
            + (where.isEmpty() ? "" : " and " + String.join(" and ", where));

    long total = count("select count(*) c from order_split s" + predicate, binds.toArray());
    List<SplitRow> items =
        sql.fetch(
                "select s.* from order_split s" + predicate
                    + " order by s.planned_at desc nulls last, s.id desc limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderSplitService::splitRow);

    long dispatched =
        countSingle("select count(*) c from order_split where tenant_id = " + TENANT_PREDICATE
            + " and status = 'DISPATCHED'");
    long pendingReservation =
        countSingle("select count(*) c from order_split_reservation_command where tenant_id = "
            + TENANT_PREDICATE + " and command_status = 'REQUESTED'");
    long pendingWriteback =
        countSingle("select count(*) c from order_split_writeback_task where tenant_id = "
            + TENANT_PREDICATE + " and status = 'PENDING'");
    return new SplitPage(
        items, total, pageNo, size, dispatched, pendingReservation, pendingWriteback);
  }

  /** 拆单详情：头 + 单元 + 行级明细 + 候选 + 预占 + 回传 + 事件流。 */
  public SplitDetail split(UUID id) {
    Record head = headOr404(id);
    List<SplitUnit> units =
        sql.fetch(
                "select * from order_split_unit where tenant_id = " + TENANT_PREDICATE
                    + " and split_id = ? order by unit_seq", id)
            .map(OrderSplitService::unitRow);
    List<UnitLine> lines =
        sql.fetch(
                "select ul.* from order_split_unit_line ul"
                    + " join order_split_unit u on u.tenant_id = ul.tenant_id and u.id = ul.unit_id"
                    + " where ul.tenant_id = " + TENANT_PREDICATE
                    + " and ul.split_id = ? order by u.unit_seq, ul.order_line_ref", id)
            .map(OrderSplitService::unitLineRow);
    List<CandidateRow> candidates =
        sql.fetch(
                "select * from order_split_candidate where tenant_id = " + TENANT_PREDICATE
                    + " and split_id = ? order by order_line_ref, rank_no", id)
            .map(OrderSplitService::candidateRow);
    List<SplitReservationCommand> reservations =
        sql.fetch(
                "select r.* from order_split_reservation_command r"
                    + " join order_split_unit u on u.tenant_id = r.tenant_id and u.id = r.unit_id"
                    + " where r.tenant_id = " + TENANT_PREDICATE
                    + " and r.split_id = ? order by u.unit_seq, r.sku_ref", id)
            .map(OrderSplitService::reservationRow);
    List<SplitWritebackTask> writebacks = writebacksOf(id);
    List<SplitEvent> events =
        sql.fetch(
                "select * from order_split_event where tenant_id = " + TENANT_PREDICATE
                    + " and split_id = ? order by occurred_at desc, id", id)
            .map(OrderSplitService::eventRow);

    return new SplitDetail(
        splitRow(head), units, lines, candidates, reservations, writebacks, events,
        amountConservedOf(head),
        Boolean.TRUE.equals(head.get("creates_sales_order", Boolean.class)),
        "本拆单**未产生新的销售订单**（creates_sales_order 由 DB CHECK 恒钉为 false）；订单总额 "
            + money(head.get("order_total_amount", BigDecimal.class))
            + " 在拆分前后不变，各履约单元分摊合计 + 尾差恰好等于它（金额守恒由 DB 延迟约束触发器"
            + "在提交时独立复核，不是服务层的自说自话）。"
            + "库存预占是**指令登记**：真实预占住在 warehouse 独立库，本模块不预占上游库存。");
  }

  /** 推进拆单状态（PLANNED → CONFIRMED → DISPATCHED，或 → CANCELLED）。 */
  @Transactional
  public SplitSaved advanceStatus(UUID splitId, SplitStatusInput input, UUID actorId) {
    if (input == null || input.status() == null) {
      throw bad("请求体与目标状态都不能为空");
    }
    if (input.occurredAt() == null) {
      throw bad("发生时刻必须显式给出");
    }
    if (input.status() == SplitStatus.CANCELLED && notBlank(input.reason()) == false) {
      throw bad("作废必须给原因：拆单是履约凭据，「为什么拆了又撤」是最常被追问的一问");
    }
    String from = headOr404(splitId).get("status", String.class);
    // 状态推进在 DB 函数里做（单向机 + 连带撤销未落定的预占指令），
    // 服务层不自己拼 UPDATE —— 那会让状态机规则散到 Java 与 SQL 两处。
    sql.execute(
        "select advance_order_split_status(?::uuid,?,?::uuid,?,?::timestamptz)",
        splitId, input.status().name(), actorId, nz(input.reason()), input.occurredAt());
    appendEvent(splitId, eventTypeFor(input.status()), from, input.status().name(), actorId,
        nz(input.reason()), input.occurredAt());
    Record fresh = headOr404(splitId);
    return new SplitSaved(
        splitId, fresh.get("split_no", String.class),
        splitStatus(nz(fresh.get("status", String.class))), fresh.get("unit_count", Integer.class),
        money(fresh.get("allocated_amount_total", BigDecimal.class)),
        money(fresh.get("residual_amount_total", BigDecimal.class)), amountConservedOf(fresh),
        switch (input.status()) {
          case CONFIRMED -> "已确认拆单方案：可以下发生预占指令与发货回传任务。";
          case DISPATCHED -> "已下发：履约单元、预占指令与回传任务交由 9.3.3.1 / warehouse 消费；"
              + "订单总额与买家视角不变。";
          case CANCELLED -> "已作废：未落定的预占指令同步撤销。";
          case PLANNED -> "已回到已拆分状态。";
        });
  }

  private static String eventTypeFor(SplitStatus s) {
    return switch (s) {
      case CONFIRMED -> "CONFIRMED";
      case DISPATCHED -> "DISPATCHED";
      case CANCELLED -> "CANCELLED";
      case PLANNED -> "SPLIT_PLANNED";
    };
  }

  // ================================================================ 预占指令

  public SplitReservationPage pageReservations(SplitReservationQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.splitId() != null) {
      where.add("split_id = ?");
      binds.add(query.splitId());
    }
    if (query.unitId() != null) {
      where.add("unit_id = ?");
      binds.add(query.unitId());
    }
    if (notBlank(query.status())) {
      where.add("command_status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.skuRef())) {
      where.add("sku_ref ilike ?");
      binds.add("%" + query.skuRef().trim() + "%");
    }
    String predicate =
        " where tenant_id = " + TENANT_PREDICATE
            + (where.isEmpty() ? "" : " and " + String.join(" and ", where));
    long total =
        count("select count(*) c from order_split_reservation_command" + predicate, binds.toArray());
    List<SplitReservationCommand> items =
        sql.fetch(
                "select * from order_split_reservation_command" + predicate
                    + " order by created_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderSplitService::reservationRow);
    long requested =
        countSingle("select count(*) c from order_split_reservation_command where tenant_id = "
            + TENANT_PREDICATE + " and command_status = 'REQUESTED'");
    long acknowledged =
        countSingle("select count(*) c from order_split_reservation_command where tenant_id = "
            + TENANT_PREDICATE + " and command_status = 'ACKNOWLEDGED'");
    long rejected =
        countSingle("select count(*) c from order_split_reservation_command where tenant_id = "
            + TENANT_PREDICATE + " and command_status = 'REJECTED'");
    return new SplitReservationPage(items, total, pageNo, size, requested, acknowledged, rejected);
  }

  /**
   * 登记 warehouse 侧对预占指令的回执。
   *
   * <p>⚠️ 这里只登记<b>回执</b>（预占号 / 拒绝原因），<b>不把 upstream_reserved 置真</b> ——
   * DB 层有 CHECK 恒钉为 false。真实预占住在 warehouse 自己的库存账里，
   * 本服务没有资格也没有能力去断言「这批货被锁住了」。
   */
  @Transactional
  public SplitReservationCommand recordReservation(
      UUID splitId, UUID unitId, SplitReservationInput input, UUID actorId) {
    if (input == null || input.status() == null) {
      throw bad("请求体与指令状态都不能为空");
    }
    if (input.status() == SplitReservationStatus.REQUESTED) {
      throw bad("回执不能把指令退回 REQUESTED：状态只能前进");
    }
    if (input.status() == SplitReservationStatus.CANCELLED) {
      throw bad("撤销预占指令请走拆单作废（split/{id}/status status=CANCELLED），"
          + "否则方案还活着而预占没了，货权就悬空了");
    }
    if (input.occurredAt() == null) {
      throw bad("回执时刻必须显式给出");
    }
    if (input.status() == SplitReservationStatus.ACKNOWLEDGED
        && notBlank(input.externalReservationRef()) == false) {
      throw bad("已预占必须带 warehouse 侧返回的预占号：没有它「已预占」是无内容的一句谎");
    }
    if (input.status() == SplitReservationStatus.REJECTED && notBlank(input.rejectReason()) == false) {
      throw bad("下游拒绝必须给原因，否则这条指令会一直躺在清单里没人知道怎么处理");
    }
    Record cmd =
        sql.fetchOne(
            "select id,command_status from order_split_reservation_command where tenant_id = "
                + TENANT_PREDICATE + " and split_id = ? and unit_id = ?",
            splitId, unitId);
    if (cmd == null) {
      throw notFound("拆单 " + splitId + " 的单元 " + unitId + " 没有预占指令");
    }
    if ("CANCELLED".equals(nz(cmd.get("command_status", String.class)))) {
      throw conflict("这条预占指令已随拆单作废撤销：终态不可改写");
    }
    String from = nz(cmd.get("command_status", String.class));
    UUID cmdId = cmd.get("id", UUID.class);
    sql.execute(
        "update order_split_reservation_command set command_status=?,external_reservation_ref=?,"
            + "reject_reason=?,acknowledged_at=coalesce(acknowledged_at,?::timestamptz),"
            + "updated_at=?::timestamptz where tenant_id=" + TENANT_PREDICATE + " and id=?",
        input.status().name(), nz(input.externalReservationRef()), nz(input.rejectReason()),
        input.occurredAt(), OffsetDateTime.now(), cmdId);
    appendEvent(splitId, "RESERVATION_COMMAND_ISSUED", from, input.status().name(), actorId,
        "预占回执 " + nz(input.externalReservationRef()) + " → " + input.status().label(),
        input.occurredAt());
    return reservationRow(
        sql.fetchOne(
            "select * from order_split_reservation_command where tenant_id = " + TENANT_PREDICATE
                + " and id = ?", cmdId));
  }

  // ================================================================ 发货回传

  public SplitWritebackPage pageWritebacks(SplitWritebackQuery query) {
    int size = pageSize(query.size());
    int pageNo = Math.max(0, query.page());
    List<String> where = new ArrayList<>();
    List<Object> binds = new ArrayList<>();
    if (query.splitId() != null) {
      where.add("split_id = ?");
      binds.add(query.splitId());
    }
    if (notBlank(query.platformCode())) {
      where.add("platform_code = ?");
      binds.add(query.platformCode().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.status())) {
      where.add("status = ?");
      binds.add(query.status().trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(query.platformOrderRef())) {
      where.add("platform_order_ref ilike ?");
      binds.add("%" + query.platformOrderRef().trim() + "%");
    }
    String predicate =
        " where tenant_id = " + TENANT_PREDICATE
            + (where.isEmpty() ? "" : " and " + String.join(" and ", where));
    long total =
        count("select count(*) c from order_split_writeback_task" + predicate, binds.toArray());
    List<SplitWritebackTask> items =
        sql.fetch(
                "select * from order_split_writeback_task" + predicate
                    + " order by created_at desc, id limit ? offset ?",
                append(binds, size, (long) pageNo * size))
            .map(OrderSplitService::writebackRow);
    long pending =
        countSingle("select count(*) c from order_split_writeback_task where tenant_id = "
            + TENANT_PREDICATE + " and status = 'PENDING'");
    long rejected =
        countSingle("select count(*) c from order_split_writeback_task where tenant_id = "
            + TENANT_PREDICATE + " and status = 'REJECTED'");
    long verification =
        countSingle("select count(*) c from order_split_writeback_task where tenant_id = "
            + TENANT_PREDICATE + " and status = 'PENDING_VERIFICATION'");
    return new SplitWritebackPage(items, total, pageNo, size, pending, rejected, verification);
  }

  private List<SplitWritebackTask> writebacksOf(UUID splitId) {
    return sql.fetch(
            "select * from order_split_writeback_task where tenant_id = " + TENANT_PREDICATE
                + " and split_id = ? order by unit_id", splitId)
        .map(OrderSplitService::writebackRow);
  }

  /**
   * 登记一个履约单元的发货回传结果（蓝图输出③：回传按拆分执行）。
   *
   * <p>表上 UNIQUE(tenant_id,unit_id) 已把「一单元一条回传任务」钉死 ——
   * 不存在「给整单记一个物流号」的形状。
   */
  @Transactional
  public SplitWritebackTask recordWriteback(
      UUID splitId, UUID unitId, SplitWritebackInput input, UUID actorId) {
    if (input == null || input.status() == null) {
      throw bad("请求体与回传状态都不能为空");
    }
    if (input.occurredAt() == null) {
      throw bad("发生时刻必须显式给出");
    }
    if (input.status() == SplitWritebackStatus.CONFIRMED && notBlank(input.trackingNumber()) == false) {
      throw bad("回传成功必须带物流号：平台要的就是这个值，没有它「已回传」是一句空话");
    }
    if (input.status() == SplitWritebackStatus.REJECTED && notBlank(input.lastError()) == false) {
      throw bad("平台拒绝必须给失败原因，否则这条任务会一直躺在清单里没人知道怎么处理");
    }
    Record task =
        sql.fetchOne(
            "select id,status from order_split_writeback_task where tenant_id = "
                + TENANT_PREDICATE + " and split_id = ? and unit_id = ?",
            splitId, unitId);
    if (task == null) {
      throw notFound("履约单元 " + unitId + " 没有回传任务：非平台单（无平台码）不建回传任务，"
          + "因为没有平台可以回传");
    }
    String from = nz(task.get("status", String.class));
    if ("CONFIRMED".equals(from)) {
      throw conflict("这条回传任务已经是「已回传」：终态不可改写，"
          + "需要更正请走平台侧申诉而不是改本地状态");
    }
    UUID taskId = task.get("id", UUID.class);
    sql.execute(
        "update order_split_writeback_task set status=?,tracking_number=?,carrier_code=?,"
            + "attempt_count=attempt_count+1,last_error=?,"
            + "submitted_at=case when ?='PENDING' then submitted_at"
            + "   else coalesce(submitted_at,?::timestamptz) end,"
            + "settled_at=case when ? in ('CONFIRMED','REJECTED','PENDING_VERIFICATION')"
            + "   then ?::timestamptz else settled_at end,"
            // ⚠️ updated_at 是**行的记账时刻**，必须是 now()，不能拿入参的业务时刻去填：
            // 回传结果经常是补录的，业务时刻早于建行时刻，拿它填会撞上 CHECK(updated_at>=created_at)。
            + "updated_at=?::timestamptz where tenant_id=" + TENANT_PREDICATE + " and id=?",
        input.status().name(), nz(input.trackingNumber()), nz(input.carrierCode()),
        nz(input.lastError()), input.status().name(), input.occurredAt(),
        input.status().name(), input.occurredAt(), OffsetDateTime.now(), taskId);
    appendEvent(splitId, "WRITEBACK_RECORDED", from, input.status().name(), actorId,
        "物流号 " + nz(input.trackingNumber()) + " → " + input.status().label(),
        input.occurredAt());
    return writebackRow(
        sql.fetchOne(
            "select * from order_split_writeback_task where tenant_id = " + TENANT_PREDICATE
                + " and id = ?", taskId));
  }

  // ================================================================ 内部

  private void appendEvent(
      UUID splitId, String type, String from, String to, UUID actorId, String detail,
      OffsetDateTime occurredAt) {
    OffsetDateTime at = occurredAt == null ? OffsetDateTime.now() : occurredAt;
    sql.execute(
        "insert into order_split_event(id,tenant_id,split_id,event_type,from_status,to_status,"
            + "actor_membership_id,detail,occurred_at,created_at)"
            + " values(?,?,?,?,?,?,?,?,?::timestamptz,?::timestamptz)",
        UUID.randomUUID(), currentTenant(), splitId, type, nz(from), nz(to), actorId, detail,
        at, OffsetDateTime.now());
  }

  private Record headOr404(UUID id) {
    Record head =
        sql.fetchOne(
            "select * from order_split where tenant_id = " + TENANT_PREDICATE + " and id = ?", id);
    if (head == null) {
      throw notFound("拆单 " + id + " 不存在");
    }
    return head;
  }

  /** 金额守恒复算（读头行的 DB 实算值，而不是服务层的自说自话）。 */
  private static boolean amountConservedOf(Record head) {
    BigDecimal total = head.get("order_total_amount", BigDecimal.class);
    BigDecimal alloc = head.get("allocated_amount_total", BigDecimal.class);
    BigDecimal resid = head.get("residual_amount_total", BigDecimal.class);
    if (total == null || alloc == null || resid == null) {
      return false;
    }
    return alloc.add(resid).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
        .compareTo(total.setScale(MONEY_SCALE, RoundingMode.HALF_UP)) == 0;
  }

  private String newSplitNo(OffsetDateTime at, UUID id) {
    // 拆单号 = SP-<UTC 秒级时刻>-<id 前 6 位>：时刻给可读性，后缀给唯一性。
    // 用 UTC 格式化，避免同一时刻在不同时区下拼出两个单号。
    return "SP-"
        + java.time.LocalDateTime.ofInstant(at.toInstant(), java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
        + "-" + id.toString().substring(0, 6).toUpperCase(Locale.ROOT);
  }

  // ================================================================ 行映射

  private static SplitRule ruleRow(Record r) {
    return new SplitRule(
        r.get("id", UUID.class),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        Boolean.TRUE.equals(r.get("split_by_warehouse", Boolean.class)),
        Boolean.TRUE.equals(r.get("split_by_carrier", Boolean.class)),
        Boolean.TRUE.equals(r.get("split_by_timeliness", Boolean.class)),
        Boolean.TRUE.equals(r.get("split_by_platform_restriction", Boolean.class)),
        SplitStrategy.valueOf(nz(r.get("primary_strategy", String.class))),
        r.get("max_units_per_split", Integer.class),
        Boolean.TRUE.equals(r.get("allow_partial_fulfillment", Boolean.class)),
        r.get("max_ship_by_hours", Integer.class),
        r.get("stock_freshness_hours", Integer.class),
        r.get("version", Long.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private static SplitWarehouseRow warehouseRow(Record r) {
    return new SplitWarehouseRow(
        r.get("id", UUID.class), r.get("warehouse_ref", String.class),
        nz(r.get("warehouse_name", String.class)), nz(r.get("legal_entity_code", String.class)),
        nz(r.get("carrier_code", String.class)), r.get("ship_by_hours", Integer.class),
        nz(r.get("allowed_platforms", String.class)),
        Boolean.TRUE.equals(r.get("active", Boolean.class)), nz(r.get("notes", String.class)),
        r.get("version", Long.class), r.get("created_at", OffsetDateTime.class));
  }

  private static SplitStockSnapshotRow stockRow(Record r) {
    return new SplitStockSnapshotRow(
        r.get("id", UUID.class), r.get("warehouse_ref", String.class),
        r.get("sku_ref", String.class), qty(r.get("available_qty", BigDecimal.class)),
        r.get("collected_at", OffsetDateTime.class), r.get("stock_source", String.class),
        nz(r.get("source_reference", String.class)), nz(r.get("notes", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  private static SplitRow splitRow(Record r) {
    SplitStatus status = splitStatus(nz(r.get("status", String.class)));
    return new SplitRow(
        r.get("id", UUID.class), r.get("split_no", String.class), status, status.label(),
        r.get("order_ref", String.class), nz(r.get("order_no", String.class)),
        nz(r.get("order_source", String.class)), nz(r.get("order_origin_platform", String.class)),
        nz(r.get("order_origin_shop", String.class)),
        nz(r.get("platform_order_ref", String.class)), nz(r.get("recipient_name", String.class)),
        nz(r.get("recipient_phone", String.class)), nz(r.get("recipient_email", String.class)),
        nz(r.get("country_code", String.class)), nz(r.get("address_line", String.class)),
        r.get("currency_code", String.class),
        money(r.get("order_total_amount", BigDecimal.class)),
        Boolean.TRUE.equals(r.get("creates_sales_order", Boolean.class)),
        SplitStrategy.valueOf(nz(r.get("strategy", String.class))),
        r.get("unit_count", Integer.class), r.get("distinct_warehouse_count", Integer.class),
        r.get("distinct_carrier_count", Integer.class),
        money(r.get("allocated_amount_total", BigDecimal.class)),
        money(r.get("residual_amount_total", BigDecimal.class)),
        qty(r.get("total_qty", BigDecimal.class)), r.get("planned_at", OffsetDateTime.class),
        r.get("confirmed_at", OffsetDateTime.class), r.get("dispatched_at", OffsetDateTime.class),
        r.get("cancelled_at", OffsetDateTime.class), nz(r.get("cancel_reason", String.class)),
        nz(r.get("note", String.class)), r.get("version", Long.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static SplitUnit unitRow(Record r) {
    UnitStatus status = UnitStatus.valueOf(nz(r.get("unit_status", String.class)));
    return new SplitUnit(
        r.get("id", UUID.class), r.get("unit_seq", Integer.class), status, status.label(),
        SplitStrategy.valueOf(nz(r.get("unit_strategy", String.class))),
        nz(r.get("warehouse_ref", String.class)), nz(r.get("carrier_code", String.class)),
        r.get("ship_by_at", OffsetDateTime.class), r.get("ship_by_hours", Integer.class),
        nz(r.get("platform_code", String.class)), nz(r.get("legal_entity_code", String.class)),
        r.get("line_count", Integer.class), qty(r.get("total_qty", BigDecimal.class)),
        money(r.get("allocated_amount", BigDecimal.class)), r.get("dispatched_at", OffsetDateTime.class),
        nz(r.get("note", String.class)));
  }

  private static UnitLine unitLineRow(Record r) {
    return new UnitLine(
        r.get("id", UUID.class), r.get("unit_id", UUID.class),
        r.get("order_line_ref", String.class), nz(r.get("sku_ref", String.class)),
        nz(r.get("sku_code", String.class)), nz(r.get("sku_name", String.class)),
        qty(r.get("allocated_qty", BigDecimal.class)),
        money(r.get("allocated_amount", BigDecimal.class)),
        money(r.get("residual_amount", BigDecimal.class)));
  }

  private static CandidateRow candidateRow(Record r) {
    String reason = nz(r.get("reject_reason", String.class));
    return new CandidateRow(
        r.get("id", UUID.class), r.get("order_line_ref", String.class),
        nz(r.get("warehouse_ref", String.class)), nz(r.get("carrier_code", String.class)),
        Boolean.TRUE.equals(r.get("selected", Boolean.class)), reason,
        reason.isEmpty() ? "" : RejectReason.valueOf(reason).label(),
        nz(r.get("reject_detail", String.class)), qty(r.get("available_qty", BigDecimal.class)),
        qty(r.get("demand_qty", BigDecimal.class)), r.get("collected_at", OffsetDateTime.class),
        nz(r.get("stock_source", String.class)), r.get("rank_no", Integer.class));
  }

  private static SplitReservationCommand reservationRow(Record r) {
    SplitReservationStatus status = SplitReservationStatus.valueOf(nz(r.get("command_status", String.class)));
    return new SplitReservationCommand(
        r.get("id", UUID.class), r.get("unit_id", UUID.class), r.get("sku_ref", String.class),
        nz(r.get("order_line_ref", String.class)), qty(r.get("reserved_qty", BigDecimal.class)),
        nz(r.get("warehouse_ref", String.class)), r.get("target_service", String.class),
        r.get("command_source", String.class), status, status.label(),
        nz(r.get("external_reservation_ref", String.class)),
        Boolean.TRUE.equals(r.get("upstream_reserved", Boolean.class)),
        nz(r.get("reject_reason", String.class)), r.get("acknowledged_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private static SplitWritebackTask writebackRow(Record r) {
    SplitWritebackStatus status = SplitWritebackStatus.valueOf(nz(r.get("status", String.class)));
    return new SplitWritebackTask(
        r.get("id", UUID.class), r.get("unit_id", UUID.class), r.get("split_id", UUID.class),
        nz(r.get("platform_code", String.class)), nz(r.get("shop_code", String.class)),
        nz(r.get("platform_order_ref", String.class)), nz(r.get("tracking_number", String.class)),
        nz(r.get("carrier_code", String.class)), status, status.label(),
        r.get("attempt_count", Integer.class), nz(r.get("last_error", String.class)),
        r.get("submitted_at", OffsetDateTime.class), r.get("settled_at", OffsetDateTime.class));
  }

  private static SplitEvent eventRow(Record r) {
    return new SplitEvent(
        r.get("id", UUID.class), r.get("event_type", String.class),
        nz(r.get("from_status", String.class)), nz(r.get("to_status", String.class)),
        r.get("actor_membership_id", UUID.class), nz(r.get("detail", String.class)),
        r.get("occurred_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  private static SplitStatus splitStatus(String value) {
    return SplitStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static boolean nzBool(Boolean value, boolean fallback) {
    return value == null ? fallback : value;
  }

  private static BigDecimal qty(BigDecimal value) {
    return value == null ? ZERO_QTY : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /** 金额一律 HALF_UP 到 4 位（DB 的 numeric(19,4) 口径），禁浮点。 */
  private static BigDecimal money(BigDecimal value) {
    return value == null ? ZERO_MONEY : value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  private static int pageSize(int requested) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, requested <= 0 ? 50 : requested));
  }

  private long count(String sqlText, Object... binds) {
    Record r = sql.fetchOne(sqlText, binds);
    return r == null ? 0 : r.get("c", Long.class);
  }

  private long countSingle(String sqlText) {
    Record r = sql.fetchOne(sqlText);
    return r == null ? 0 : r.get("c", Long.class);
  }

  private static Object[] append(List<Object> binds, Object... more) {
    List<Object> all = new ArrayList<>(binds);
    all.addAll(List.of(more));
    return all.toArray();
  }
}
