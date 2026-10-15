package com.starcon.trade;

import static com.starcon.trade.WaybillListingModels.*;

import com.starcon.trade.WaybillModels.WaybillCancelInput;
import com.starcon.trade.WaybillModels.WaybillRequestStatus;
import com.starcon.trade.WaybillModels.WaybillRetryInput;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
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
 * 10.2.1.1 运单列表。
 *
 * <p>本模块<b>不拥有运单号</b>：运单申请是 10.2.1.2 的
 * {@code logistics_waybill_request}（trade V128），本模块对它只做<b>只读</b>查询 ——
 * 不 ALTER、不建跨模块 FK、不写任何一列。清单行在**查询时**现算，不落投影表，
 * 因此不存在「投影没刷新 → 列表空着 → 现场以为今天没单」这种假活。
 *
 * <p>本模块自己只拥有两件事（V132）：
 *
 * <ul>
 *   <li>{@code logistics_waybill_freight} —— 运费<b>登记</b>。运费的真实来源是
 *       10.1.2.1 / 10.1.2.2（两卷全未交付），所以本模块只能登记、不能算，
 *       并把「未登记」显式标成一个可读状态而不是留空。
 *   <li>{@code logistics_waybill_print_log} —— 批量打印的逐件留痕。8.3.4.2
 *       面单打印未交付，本模块只发**已登记的凭据**、不渲染 ZPL。
 * </ul>
 *
 * <p>三个批量动作都**真作用**，但写入一律回到唯一的写入所有者：
 * 「申请运单」与「取消」都委派给 {@link WaybillApplicationService}
 * （10.2.1.2 的 retry / cancel），本模块**不**自己 UPDATE
 * {@code logistics_waybill_request} —— 那会让「一张事实表只有一个写入所有者」
 * 变成两个。
 */
@Service
public class WaybillListingService {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int MAX_BULK = 200;

  /** 清单行的 SELECT 列。带 {@code f_} 前缀的是本模块 V132 登记表的现行行。 */
  private static final String ROW_COLUMNS =
      "w.id, w.request_no, w.waybill_no, w.sales_order_no, w.outbound_order_no,"
          + " w.pending_parcel_id, w.warehouse_ref, w.carrier_code, w.carrier_channel,"
          + " w.ship_to_country, w.status, w.gross_weight_kg, w.label_format, w.label_uri,"
          + " w.created_at, w.issued_at, w.not_before,"
          + " f.amount as f_amount, f.currency as f_currency, f.source as f_source";

  /** 现行运费用 LATERAL 挂：一票最多一条现行行（LATERAL 天然保持一行一票）。 */
  private static final String FREIGHT_JOIN =
      " left join lateral (select amount, currency, source from logistics_waybill_freight f"
          + " where f.tenant_id = w.tenant_id and f.request_id = w.id and f.is_current) f on true";

  private final DSLContext sql;
  private final WaybillApplicationService waybills;

  public WaybillListingService(DSLContext sql, WaybillApplicationService waybills) {
    this.sql = sql;
    this.waybills = waybills;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>三处「拿不到却必须说」：承运商侧物流状态（10.3 未交付）、运费计算
   * （10.1.2.1 / 10.1.2.2 未交付）、面单重新渲染（8.3.4.2 未交付）。
   * 三处一律 {@code available=false} 并点名 ownerModule 与影响。
   */
  public WaybillListingCapabilities capabilities() {
    return new WaybillListingCapabilities(
        List.of(
            new WaybillListingCapabilityDimension(
                "WAYBILL_ROW",
                "运单号、订单/包裹、承运商渠道、发货仓、下单时间、重量",
                true,
                "同服务 10.2.1.2 的 logistics_waybill_request（trade V128，**库内只读**，"
                    + "不 ALTER、不建跨模块 FK、不写任何一列；运单号的唯一写入所有者永远是 10.2.1.2）",
                null,
                null),
            new WaybillListingCapabilityDimension(
                "DISPLAY_STATUS",
                "待取号 / 已取号 / 已取消 三个展示态",
                true,
                "10.2.1.2 的内部申请状态（QUEUED+SUBMITTING→待取号，ISSUED→已取号，"
                    + "CANCELLED→已取消）经本模块映射；另有「申请失败」= 内部申请未成功",
                null,
                "**内部状态仅作展示**：这些状态来自我们的申请流程，不是承运商轨迹"),
            new WaybillListingCapabilityDimension(
                "CARRIER_TRACKING_STATUS",
                "已揽收 / 运输中 / 已签收 / 承运商侧异常",
                false,
                "10.3 物流跟踪未交付，集成头内没有任何承运商轨迹数据源",
                "10.3.1.1 物流轨迹 / 10.3.1.2 异常件处置",
                "蓝图加粗句要求「运单状态从承运商侧获取为准」—— 没有 10.3 就取不到。"
                    + "本模块**不产出**这四个态：不画 0、不留空、更不默认「运输中」，"
                    + "留空会被读成「一切正常」。因此「已取号」**不代表货已在路上**"),
            new WaybillListingCapabilityDimension(
                "FREIGHT_AMOUNT",
                "运费",
                false,
                "10.1.2.1 运费计算器与 10.1.2.2 渠道比价均未开发，"
                    + "没有费率与折扣配置，也无承运商连接可取回执金额",
                "10.1.2.1 运费计算器 / 10.1.2.2 渠道比价",
                "本模块改为**登记**：人工登记或渠道对账单登记，逐票一条现行行，"
                    + "更正走「追加新行 + 撤下旧行」（凭据不可改写）。"
                    + "**未登记是一个可读状态**（NOT_REGISTERED），不是空单元格；"
                    + "汇总按**币种分组**给，不给混币总数（跨币种相加属 10 运费试算职责）"),
            new WaybillListingCapabilityDimension(
                "LABEL_PRINT",
                "批量打印面单",
                false,
                "8.3.4.2 面单与标签打印未交付，本模块**不渲染 ZPL、不建第二个打印引擎**",
                "8.3.4.2 面单与标签打印",
                "批量打印只把 10.2.1.2 申请时登记的 label_uri / label_format 作为**凭据**"
                    + "逐件发出，并逐件落一条打印留痕；「这一票有没有面单凭据」如实标出，"
                    + "**重新渲染 / 补打不可用**"),
            new WaybillListingCapabilityDimension(
                "WAYBILL_CANCEL",
                "已出号运单的取消与改址",
                false,
                "10.2.1.3 改址与取消未开发，没有渠道时限规则也没有承运商接口",
                "10.2.1.3 改址与取消",
                "本模块的批量取消**只作用于还没出号的申请**（委派 10.2.1.2 作废）；"
                    + "已取号的一律 409 并点名 10.2.1.3，本模块**不代做**"),
            new WaybillListingCapabilityDimension(
                "HANDOVER_STATE",
                "交运状态（货是否已交承运商）",
                false,
                "8.3.4.1 发货交接在 warehouse 服务且未同步；本模块刻意**不**引入这条跨服务依赖",
                "8.3.4.1 发货交接 / 9.4.1.2 发货记录",
                "清单行在**出号那一刻**就成立，而交运发生在出号之后。为一个高频读列表"
                    + "引入「仓库不可达就整页 503」的跨服务依赖，会让出号成功但仓库宕机时"
                    + "现场连单号都看不到 —— 故不进本清单"),
            new WaybillListingCapabilityDimension(
                "BATCH_APPLY",
                "批量申请运单（失败重试）",
                true,
                "委派同服务 10.2.1.2 的 WaybillApplicationService.retry —— "
                    + "运单申请是唯一写入所有者，本模块只发起不自己写",
                null,
                "只对「申请失败」的运单有意义；已取号 / 已取消 / 待取号的行会**逐条**"
                    + "返回跳过原因，不会被这一批操作悄悄改掉")),
        statusOptions(),
        Arrays.stream(WaybillCarrierChannel.values())
            .map(c -> c.name() + ":" + c.displayName)
            .toList(),
        List.of("MANUAL_REGISTERED:人工登记", "CHANNEL_FEE_STATEMENT:渠道对账单登记"),
        List.of("APPLY:批量申请运单（失败重试）", "CANCEL:批量取消（仅未出号）", "PRINT:批量打印（只发凭据）"),
        "**内部状态仅作展示**：待取号/已取号/已取消来自 10.2.1.2 的内部申请状态，"
            + "不是承运商轨迹。蓝图要求「运单状态从承运商侧获取为准」，而 10.3 未交付，"
            + "所以已揽收/运输中/已签收/异常这四个态本模块**不产出**",
        "运费是**登记**不是计算：10.1.2.1 / 10.1.2.2 未交付。"
            + "未登记 ≠ 0 运费，两者在清单与汇总里分开计数；汇总按币种分组",
        "下游 10.3.1.1 轨迹、10.2.1.3 改址取消、8.3.4.2 面单打印均未交付，本模块**不代做**，"
            + "逐条在 capabilities 里 available=false 并点名 ownerModule");
  }
  /** 每个展示态的可用性与归因（available=false 的四个态在界面上应当禁用而不是灰掉显示）。 */
  private List<WaybillDisplayStatusOption> statusOptions() {
    List<WaybillDisplayStatusOption> out = new ArrayList<>();
    for (WaybillDisplayStatus s : WaybillDisplayStatus.values()) {
      out.add(
          new WaybillDisplayStatusOption(
              s.name(), s.displayName, s.available, s.source,
              s.available ? null : "10.3.1.1 物流轨迹 / 10.3.1.2 异常件处置",
              s.available ? null : "上游未交付，本模块不产出该状态（留空会被读成「一切正常」）"));
    }
    return out;
  }

  // ================================================================ 筛选选项

  /**
   * 下拉选项，**全部来自真实数据 DISTINCT**。
   *
   * <p>刻意不写死渠道 / 承运商 / 国家数组：写死后租户 A 的下拉里会出现
   * 租户 B 才有的渠道，点下去永远 0 条 —— 9.4.1.2 与 10.2.1.2 都钉过同一条。
   */
  public WaybillListFilterOptions filterOptions() {
    // 刻意用 forEach + ArrayList 而不是 Result.map().collect()：本仓 jOOQ 版本的
    // Result 不吃 Collectors（9.4.1.2 / 10.2.1.2 全仓都走 forEach 这一条写法）。
    List<WaybillCarrierOption> carriers = new ArrayList<>();
    sql.fetch("select carrier_code, count(*) as c from logistics_waybill_request"
            + " group by carrier_code order by c desc, carrier_code asc")
        .forEach(
            r ->
                carriers.add(
                    new WaybillCarrierOption(
                        r.get("carrier_code", String.class), longOf(r.get("c", Long.class)))));
    List<String> channels = new ArrayList<>();
    sql.fetch("select distinct carrier_channel from logistics_waybill_request"
            + " order by carrier_channel")
        .forEach(r -> channels.add(r.get(0, String.class)));
    List<String> countries = new ArrayList<>();
    sql.fetch("select distinct ship_to_country from logistics_waybill_request"
            + " where btrim(ship_to_country) <> '' order by ship_to_country")
        .forEach(r -> countries.add(r.get(0, String.class)));
    List<String> warehouses = new ArrayList<>();
    sql.fetch("select distinct warehouse_ref from logistics_waybill_request"
            + " where btrim(warehouse_ref) <> '' order by warehouse_ref")
        .forEach(r -> warehouses.add(r.get(0, String.class)));
    return new WaybillListFilterOptions(
        carriers, channels, countries, warehouses, statusOptions(),
        List.of("CREATED_AT_DESC", "CREATED_AT_ASC", "WAYBILL_NO_ASC", "CARRIER_CODE_ASC",
            "WEIGHT_DESC", "FREIGHT_DESC"),
        "选项来自**当前租户真实数据**的 DISTINCT，不是写死的词表；"
            + "某个维度一条数据都没有时它就不出现（而不是出现一个空选项）");
  }

  // ================================================================ 清单

  /**
   * 运单清单：真分页 + 真筛选 + 排序白名单，<b>汇总独立于分页</b>。
   *
   * @param status 展示态（PENDING_NUMBER / NUMBER_ISSUED / APPLICATION_FAILED / CANCELLED）。
   *     传一个 available=false 的态（已揽收/运输中/已签收/异常）**不会**退化成「0 条」，
   *     而是直接返回空页并在 {@code statusFilterNote} 里说清「这个态没有数据源」。
   */
  public WaybillListPage waybills(
      String carrierCode,
      String carrierChannel,
      String status,
      String destinationCountry,
      String warehouseRef,
      String freight,
      OffsetDateTime dateFrom,
      OffsetDateTime dateTo,
      String keyword,
      String sort,
      int page,
      int size) {
    int limit = pageSize(size);
    Filters filters = filters(carrierCode, carrierChannel, status, destinationCountry,
        warehouseRef, freight, dateFrom, dateTo, keyword);

    // 不可用的展示态：不是「筛不到」，是「没有这个数据源」。两种返回要分得开，
    // 否则运营会以为今天真的一票都没发出去。
    if (filters.unavailableStatus != null) {
      return new WaybillListPage(
          List.of(), Math.max(page, 0), limit, 0L, 0,
          emptySummary("筛选状态「" + filters.unavailableStatus + "」没有数据源："
              + "10.3 物流跟踪未交付，承运商侧没有可读的轨迹回执，"
              + "本模块不产出该状态（留空会被读成「一切正常」）"),
          "该状态**没有上游数据源**（ownerModule=10.3.1.1 / 10.3.1.2），"
              + "所以筛不出任何行是如实结果，不是「这一票不存在」");
    }

    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;

    long total = countOf("select count(*) from logistics_waybill_request w" + where,
        filters.params.toArray());

    List<WaybillListRow> items = new ArrayList<>();
    if (total > 0) {
      // 负页码钳到 0：否则 OFFSET 是负数，PG 直接报错，页面笔误会被显示成
      // 「数据冲突」（10.2.1.2 踩过这个坑并补了反例）。
      sql.fetch(
              "select " + ROW_COLUMNS + " from logistics_waybill_request w" + FREIGHT_JOIN + where
                  + " order by " + orderBy(sort) + " limit ? offset ?",
              concat(filters.params, limit, (long) Math.max(page, 0) * limit))
          .forEach(r -> items.add(row(r)));
    }

    return new WaybillListPage(
        items, Math.max(page, 0), limit, total, (int) Math.ceil((double) total / limit),
        summary(filters), filters.statusNote);
  }

  /**
   * 汇总 —— <b>一条不带 LIMIT/OFFSET 的独立查询</b>。
   *
   * <p>这是列表页最常见的假活：汇总跟着分页走，第一页就显示「共 20 条、运费 0」，
   * 而实际筛选出 380 条。这里 summary() 只吃 filters，<b>不</b>吃 page/limit。
   */
  private WaybillListSummary summary(Filters filters) {
    String where = filters.clause.isEmpty() ? "" : " where " + filters.clause;
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where w.status in ('QUEUED','SUBMITTING')) as pending,"
                + " count(*) filter (where w.status = 'ISSUED') as issued,"
                + " count(*) filter (where w.status = 'FAILED') as failed,"
                + " count(*) filter (where w.status = 'CANCELLED') as cancelled,"
                + " count(*) filter (where btrim(w.waybill_no) = '') as without_no,"
                + " count(*) filter (where f.amount is null) as freight_missing,"
                + " count(*) filter (where f.amount is not null) as freight_present,"
                + " count(*) filter (where btrim(w.waybill_no) <> ''"
                + "   and btrim(w.label_uri) <> '') as printable,"
                + " count(*) filter (where btrim(w.waybill_no) <> ''"
                + "   and btrim(w.label_uri) = '') as no_label,"
                + " count(distinct w.carrier_code) as carriers,"
                + " count(distinct w.ship_to_country) as countries"
                + " from logistics_waybill_request w" + FREIGHT_JOIN + where,
            filters.params.toArray());

    List<WaybillFreightCurrencyTotal> byCurrency = new ArrayList<>();
    sql.fetch(
            "select f.currency as currency, count(*) as c, sum(f.amount) as total"
                + " from logistics_waybill_freight f"
                + " join logistics_waybill_request w on w.tenant_id = f.tenant_id"
                + "   and w.id = f.request_id"
                + " where f.is_current" + andWhere(where)
                + " group by f.currency order by f.currency",
            filters.params.toArray())
        .forEach(
            x ->
                byCurrency.add(
                    new WaybillFreightCurrencyTotal(
                        x.get("currency", String.class),
                        longOf(x.get("c", Long.class)),
                        x.get("total", BigDecimal.class))));

    long issued = longOf(r.get("issued", Long.class));
    return new WaybillListSummary(
        longOf(r.get("total", Long.class)),
        longOf(r.get("pending", Long.class)),
        issued,
        longOf(r.get("failed", Long.class)),
        longOf(r.get("cancelled", Long.class)),
        // 「已取号」与「承运商已知它在途」**永远不相等**：10.3 未交付，
        // 所以每一个已取号的运单都同时是「没有承运商轨迹」的一票，如实计。
        issued,
        longOf(r.get("without_no", Long.class)),
        longOf(r.get("freight_present", Long.class)),
        longOf(r.get("freight_missing", Long.class)),
        byCurrency,
        longOf(r.get("printable", Long.class)),
        longOf(r.get("no_label", Long.class)),
        longOf(r.get("carriers", Long.class)),
        longOf(r.get("countries", Long.class)),
        List.of(
            "汇总覆盖**筛选后的全量**，与分页无关（分页只切 items）",
            "issuedWithoutCarrierTracking = 已取号票数：10.3 未交付，"
                + "没有任何一票有承运商侧轨迹，所以「已取号」**不等于**「在途中」",
            "freightNotRegistered 是「没人登记运费」，与「运费登记为 0」是两个独立计数",
            "运费按币种分组给：跨币种相加属 10.1.2.1 运费试算职责，那条线未交付"));
  }

  private WaybillListSummary emptySummary(String note) {
    return new WaybillListSummary(
        0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), 0, 0, 0, 0, List.of(note));
  }

  // ================================================================ 详情与运费登记

  /** 单票详情：清单行 + 运费登记历史（含已撤下的）+ 打印留痕 + 可执行动作。 */
  public WaybillListDetail waybill(String id) {
    UUID requestId = parseUuid(id, "运单不存在：" + id);
    Record r =
        sql.fetchOne(
            "select " + ROW_COLUMNS + " from logistics_waybill_request w" + FREIGHT_JOIN
                + " where w.id = ?",
            requestId);
    if (r == null) throw notFound("运单不存在：" + id);

    List<WaybillFreightRow> freight = new ArrayList<>();
    sql.fetch(
            "select id, request_id, request_no, carrier_code, currency, amount, charge_type,"
                + " source, evidence_ref, is_current, registered_at"
                + " from logistics_waybill_freight where request_id = ?"
                + " order by is_current desc, registered_at desc",
            requestId)
        .forEach(
            x ->
                freight.add(
                    new WaybillFreightRow(
                        x.get("id", UUID.class), x.get("request_id", UUID.class),
                        x.get("request_no", String.class), x.get("carrier_code", String.class),
                        x.get("currency", String.class), x.get("amount", BigDecimal.class),
                        x.get("charge_type", String.class), x.get("source", String.class),
                        x.get("evidence_ref", String.class),
                        Boolean.TRUE.equals(x.get("is_current", Boolean.class)),
                        x.get("registered_at", OffsetDateTime.class))));

    List<WaybillPrintLogRow> prints = new ArrayList<>();
    sql.fetch(
            "select id, batch_no, request_id, request_no, waybill_no, carrier_code,"
                + " label_format, label_uri, label_availability, printer_target, remark,"
                + " printed_at from logistics_waybill_print_log where request_id = ?"
                + " order by printed_at desc",
            requestId)
        .forEach(
            x ->
                prints.add(
                    new WaybillPrintLogRow(
                        x.get("id", UUID.class), x.get("batch_no", String.class),
                        x.get("request_id", UUID.class), x.get("request_no", String.class),
                        x.get("waybill_no", String.class), x.get("carrier_code", String.class),
                        x.get("label_format", String.class), x.get("label_uri", String.class),
                        WaybillLabelAvailability.valueOf(x.get("label_availability", String.class)),
                        x.get("printer_target", String.class), x.get("remark", String.class),
                        x.get("printed_at", OffsetDateTime.class))));

    WaybillListRow row = row(r);
    return new WaybillListDetail(
        row, freight, prints, nextActions(row), List.of(
            "运单号与出号时刻由 10.2.1.2 拥有，本模块只读",
            "运费是登记不是计算（10.1.2.1 / 10.1.2.2 未交付），更正走「追加新行 + 撤下旧行」",
            "打印只发已登记的面单凭据，**不渲染 ZPL**（8.3.4.2 未交付）",
            "承运商侧轨迹（已揽收/在途/已签收/异常）**没有数据源**（10.3 未交付）"));
  }

  /**
   * 登记一票运费。
   *
   * <p>更正 = 追加新行 + 把旧行的 is_current 撤下（凭据不可改写，V132 触发器钉死）。
   * 刻意<b>不提供</b>「删除运费」：运费进了 10.4.1 的物流成本口径，
   * 删一笔等于让成本报表少一块而没人知道。
   */
  @Transactional
  public WaybillFreightRow registerFreight(String id, WaybillFreightInput input, UUID membershipId) {
    UUID requestId = parseUuid(id, "运单不存在：" + id);
    Record req =
        sql.fetchOne(
            "select request_no, carrier_code, status from logistics_waybill_request where id = ?",
            requestId);
    if (req == null) throw notFound("运单不存在：" + id);

    // 还没出号就登记运费并不禁止：运营常在出号前就拿到渠道报价。允许，只是别
    // 让它看起来像「这一票已经发出去了」—— 那一层由清单行的展示态负责。
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    String source = input.source().trim().toUpperCase(Locale.ROOT);
    String chargeType = input.chargeType().trim().toUpperCase(Locale.ROOT);

    // 先撤旧、再插新：列级 UPDATE 只放行 is_current 这一列，金额/币种永远写不改。
    sql.execute(
        "update logistics_waybill_freight set is_current = false"
            + " where request_id = ? and is_current",
        requestId);
    sql.execute(
        "insert into logistics_waybill_freight"
            + " (id, tenant_id, request_id, request_no, carrier_code, currency, amount,"
            + "  charge_type, source, evidence_ref, is_current, registered_by_membership_id,"
            + "  registered_at)"
            + " values (?,?,?,?,?,?,?,?,?,?,true,?,?::timestamptz)",
        UUID.randomUUID(), currentTenant(), requestId, req.get("request_no", String.class),
        req.get("carrier_code", String.class), input.currency().trim().toUpperCase(Locale.ROOT),
        input.amount(), chargeType, source, nullSafe(input.evidenceRef(), ""), membershipId, now);

    Record row =
        sql.fetchOne(
            "select id, request_id, request_no, carrier_code, currency, amount, charge_type,"
                + " source, evidence_ref, is_current, registered_at"
                + " from logistics_waybill_freight where request_id = ? and is_current",
            requestId);
    if (row == null) throw new IllegalStateException("运费登记写入后读不回来");
    return new WaybillFreightRow(
        row.get("id", UUID.class), row.get("request_id", UUID.class),
        row.get("request_no", String.class), row.get("carrier_code", String.class),
        row.get("currency", String.class), row.get("amount", BigDecimal.class),
        row.get("charge_type", String.class), row.get("source", String.class),
        row.get("evidence_ref", String.class), true,
        row.get("registered_at", OffsetDateTime.class));
  }

  // ================================================================ 批量动作

  /**
   * 批量申请运单 —— <b>委派</b> 10.2.1.2 的 retry，本模块不自己写运单申请。
   *
   * <p>只对「申请失败」有意义（那才是需要再试一次的状态）。其余状态**逐条**返回
   * 跳过原因：待取号的已在队列里等窗口、已取号的号已经在手上、已取消的要重开一张。
   * 绝不让一次批量把不属于它的行悄悄改掉。
   */
  public WaybillBulkActionResult applyBatch(
      WaybillBulkActionInput input, UUID membershipId) {
    var reason = nullSafe(input.reason(), "");
    List<WaybillBulkActionEntry> entries = new ArrayList<>();
    for (UUID requestId : ids(input)) {
      Snapshot snap = snapshot(requestId);
      if (snap == null) {
        entries.add(entry(requestId, null, "APPLY", false, "NOT_FOUND",
            "运单不存在（或不属于当前租户）"));
        continue;
      }
      if (snap.status != WaybillRequestStatus.FAILED) {
        entries.add(entry(requestId, snap, "APPLY", false, "NOT_APPLICABLE",
            skipReasonForApply(snap)));
        continue;
      }
      try {
        waybills.retry(requestId, new WaybillRetryInput(reason), membershipId);
        entries.add(entry(requestId, snap, "APPLY", true, "REQUEUED",
            "已重新入队，等待承运商限流窗口放行（沿用同一条幂等键，不会产生第二张运单）"));
      } catch (RuntimeException e) {
        entries.add(entry(requestId, snap, "APPLY", false, "FAILED", message(e)));
      }
    }
    return result("APPLY", entries, "", List.of(
        "运单申请的写入所有者是 10.2.1.2；本动作**委派**它的 retry，"
            + "不在本模块 UPDATE logistics_waybill_request",
        "只对「申请失败」生效；其余状态逐条给出跳过原因",
        "重试沿用同一条 idempotency_key，不会产生第二张运单"));
  }

  /**
   * 批量取消 —— 委派 10.2.1.2 的 cancel（作废还没出号的申请）。
   *
   * <p>已取号的**逐条 409 并点名 10.2.1.3**，本模块不代做：在没有渠道时限规则
   * 也没有承运商接口的情况下把货「取消成功」，是最贵的一种显示与事实不一致。
   */
  public WaybillBulkActionResult cancelBatch(
      WaybillBulkActionInput input, UUID membershipId) {
    var reason = input.reason() == null ? "" : input.reason().trim();
    if (reason.isEmpty()) {
      throw badRequest("批量取消必须写明原因：作废一张运单申请是有凭据后果的动作");
    }
    List<WaybillBulkActionEntry> entries = new ArrayList<>();
    for (UUID requestId : ids(input)) {
      Snapshot snap = snapshot(requestId);
      if (snap == null) {
        entries.add(entry(requestId, null, "CANCEL", false, "NOT_FOUND",
            "运单不存在（或不属于当前租户）"));
        continue;
      }
      if (snap.status == WaybillRequestStatus.ISSUED) {
        entries.add(entry(requestId, snap, "CANCEL", false, "NOT_APPLICABLE",
            "已取号（运单号 " + snap.waybillNo + "），取消运单属于 10.2.1.3，本模块不代做"));
        continue;
      }
      try {
        waybills.cancel(requestId, new WaybillCancelInput(reason), membershipId);
        entries.add(entry(requestId, snap, "CANCEL", true, "CANCELLED",
            "已作废（还没出号，作废不影响已交运的货）"));
      } catch (RuntimeException e) {
        entries.add(entry(requestId, snap, "CANCEL", false, "FAILED", message(e)));
      }
    }
    return result("CANCEL", entries, "", List.of(
        "作废由 10.2.1.2 执行（运单申请的写入所有者）",
        "已取号的运单取消属 10.2.1.3（未交付），本模块 409 并点名，**不代做**"));
  }

  /**
   * 批量打印 —— 逐件发**已登记的面单凭据**并落一条打印留痕。
   *
   * <p>刻意**不渲染 ZPL、不建第二个打印引擎**（8.3.4.2 未交付）。真的部分是：
   * 逐件读出凭据、逐件标出「有没有凭据」、逐件落库（批次号 + 打印时刻 + 打印人），
   * 所以「漏打 / 重复打」事后查得到。没出号的行**逐条失败**而不是打一张空面单。
   */
  @Transactional
  public WaybillBulkActionResult printBatch(
      WaybillBulkActionInput input, UUID membershipId) {
    List<WaybillBulkActionEntry> entries = new ArrayList<>();
    var now = OffsetDateTime.now(ZoneOffset.UTC);
    String batchNo = nextBatchNo(now);

    for (UUID requestId : ids(input)) {
      Snapshot snap = snapshot(requestId);
      if (snap == null) {
        entries.add(entry(requestId, null, "PRINT", false, "NOT_FOUND",
            "运单不存在（或不属于当前租户）"));
        continue;
      }
      if (snap.status != WaybillRequestStatus.ISSUED || snap.waybillNo == null
          || snap.waybillNo.isBlank()) {
        entries.add(entry(requestId, snap, "PRINT", false, "NOT_APPLICABLE",
            "尚未取得运单号，没有面单可打印（先走「申请运单」）"));
        continue;
      }
      boolean hasReference = snap.labelUri != null && !snap.labelUri.isBlank();
      sql.execute(
          "insert into logistics_waybill_print_log"
              + " (id, tenant_id, batch_no, request_id, request_no, waybill_no, carrier_code,"
              + "  label_format, label_uri, label_availability, printer_target, remark,"
              + "  printed_by_membership_id, printed_at)"
              + " values (?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(), currentTenant(), batchNo, requestId, snap.requestNo,
          snap.waybillNo, snap.carrierCode, nullSafe(snap.labelFormat, ""),
          nullSafe(snap.labelUri, ""),
          hasReference ? "RECORDED_REFERENCE" : "NO_REFERENCE",
          nullSafe(input.printerTarget(), ""), nullSafe(input.remark(), ""),
          membershipId, now);
      entries.add(entry(requestId, snap, "PRINT", true,
          hasReference ? "PRINT_CREDENTIAL" : "PRINTED_WITHOUT_REFERENCE",
          hasReference
              ? "已发出面单凭据（label_format=" + nullSafe(snap.labelFormat, "-") + "）"
              : "这一票**没有登记面单凭据**，只留了打印记录 —— 重新渲染需 8.3.4.2（未交付）"));
    }
    return result("PRINT", entries, batchNo, List.of(
        "打印只发 10.2.1.2 申请时登记的凭据，**不渲染 ZPL**（8.3.4.2 未交付）",
        "批次号 " + batchNo + " 把这一批的逐件结果重新聚在一起，可用于排查漏打/重复打",
        "没有登记凭据的运单会照实记 NO_REFERENCE，不会被当成「已打印成功」"));
  }

  // ================================================================ 内部

  /** 运单申请在本模块批量动作里需要的最小快照。 */
  private record Snapshot(
      UUID id, String requestNo, String waybillNo, String carrierCode, String labelFormat,
      String labelUri, WaybillRequestStatus status) {}

  private Snapshot snapshot(UUID requestId) {
    Record r =
        sql.fetchOne(
            "select id, request_no, waybill_no, carrier_code, label_format, label_uri, status"
                + " from logistics_waybill_request where id = ?",
            requestId);
    if (r == null) return null;
    return new Snapshot(
        r.get("id", UUID.class), r.get("request_no", String.class),
        r.get("waybill_no", String.class), r.get("carrier_code", String.class),
        r.get("label_format", String.class), r.get("label_uri", String.class),
        WaybillRequestStatus.valueOf(r.get("status", String.class)));
  }

  private record Filters(
      String clause, List<Object> params, String unavailableStatus, String statusNote) {}

  private Filters filters(
      String carrierCode, String carrierChannel, String status, String destinationCountry,
      String warehouseRef, String freight, OffsetDateTime dateFrom, OffsetDateTime dateTo,
      String keyword) {
    List<String> clauses = new ArrayList<>();
    List<Object> params = new ArrayList<>();
    String unavailable = null;
    String note = null;

    if (notBlank(carrierCode)) {
      clauses.add("w.carrier_code = ?");
      params.add(carrierCode.trim());
    }
    if (notBlank(carrierChannel)) {
      clauses.add("w.carrier_channel = ?");
      params.add(carrierChannel.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(destinationCountry)) {
      clauses.add("w.ship_to_country = ?");
      params.add(destinationCountry.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(warehouseRef)) {
      clauses.add("w.warehouse_ref = ?");
      params.add(warehouseRef.trim());
    }
    if (notBlank(freight)) {
      String gap = freight.trim().toUpperCase(Locale.ROOT);
      String exists =
          " exists (select 1 from logistics_waybill_freight fx"
              + " where fx.tenant_id = w.tenant_id and fx.request_id = w.id and fx.is_current)";
      if ("REGISTERED".equals(gap)) {
        clauses.add(exists);
      } else if ("NOT_REGISTERED".equals(gap)) {
        clauses.add("not " + exists);
      } else {
        throw badRequest("运费筛选只支持 REGISTERED / NOT_REGISTERED，收到：" + freight);
      }
    }
    // 时间：下单时间 = 10.2.1.2 的 created_at。
    // ⚠️ timestamptz 绑定必须写 ?::timestamptz 并绑 OffsetDateTime：
    //   用 Timestamp.valueOf(LocalDateTime) 会**静默偏 −28800 秒**（把 UTC 当本地），
    //   不抛异常，只是写进去的时刻错了 8 小时。测试因此断言**写回的值**。
    if (dateFrom != null) {
      clauses.add("w.created_at >= ?::timestamptz");
      params.add(dateFrom);
    }
    if (dateTo != null) {
      clauses.add("w.created_at < ?::timestamptz");
      params.add(dateTo);
    }
    if (notBlank(keyword)) {
      clauses.add(
          "(w.waybill_no ilike ? or w.request_no ilike ? or w.sales_order_no ilike ?"
              + " or w.outbound_order_no ilike ? or w.carrier_code ilike ?)");
      String like = "%" + keyword.trim() + "%";
      for (int i = 0; i < 5; i++) params.add(like);
    }

    if (notBlank(status)) {
      String want = status.trim().toUpperCase(Locale.ROOT);
      WaybillDisplayStatus display;
      try {
        display = WaybillDisplayStatus.valueOf(want);
      } catch (IllegalArgumentException e) {
        throw badRequest("运单状态筛选不认识：" + status
            + "；可用值见 /capabilities 的 statuses（七个蓝图态 + 申请失败）");
      }
      if (!display.available) {
        unavailable = display.name();
      } else {
        switch (display) {
          case PENDING_NUMBER -> {
            clauses.add("w.status in ('QUEUED','SUBMITTING')");
            note = "待取号 = 10.2.1.2 的 QUEUED（已入队等渠道窗口）与 SUBMITTING（已取出在提交）";
          }
          case NUMBER_ISSUED -> {
            clauses.add("w.status = 'ISSUED'");
            note = "已取号 = 承运商已返回运单号；**不代表已在路上**（10.3 未交付）";
          }
          case APPLICATION_FAILED -> {
            clauses.add("w.status = 'FAILED'");
            note = "申请失败 = 我们还没拿到运单号，**不是**承运商侧物流异常";
          }
          case CANCELLED -> clauses.add("w.status = 'CANCELLED'");
          default -> throw new IllegalStateException("不可达分支：" + display);
        }
      }
    }
    return new Filters(String.join(" and ", clauses), params, unavailable, note);
  }

  /** 把 {@code " where ..."} 变成 {@code " and ..."}，好拼到已有 where 后面。 */
  private static String andWhere(String where) {
    return where.isEmpty() ? "" : " and " + where.substring(" where ".length());
  }

  private String orderBy(String sort) {
    if (sort == null || sort.isBlank()) return "w.created_at desc, w.id";
    return switch (sort.trim().toUpperCase(Locale.ROOT)) {
      case "CREATED_AT_ASC" -> "w.created_at asc, w.id";
      case "WAYBILL_NO_ASC" -> "w.waybill_no asc, w.created_at desc";
      case "CARRIER_CODE_ASC" -> "w.carrier_code asc, w.created_at desc";
      case "WEIGHT_DESC" -> "w.gross_weight_kg desc, w.created_at desc";
      // 排序白名单里的运费维度：未登记的排在最后，而不是当成 0 混在前面
      case "FREIGHT_DESC" -> "f.amount desc nulls last, w.created_at desc";
      default -> "w.created_at desc, w.id";
    };
  }

  /** 内部申请状态 → 展示态。**只映射能兑现的四个**，其余四个没有数据源。 */
  private static WaybillDisplayStatus displayStatus(WaybillRequestStatus status) {
    return switch (status) {
      case QUEUED, SUBMITTING -> WaybillDisplayStatus.PENDING_NUMBER;
      case ISSUED -> WaybillDisplayStatus.NUMBER_ISSUED;
      case FAILED -> WaybillDisplayStatus.APPLICATION_FAILED;
      case CANCELLED -> WaybillDisplayStatus.CANCELLED;
    };
  }

  private static String statusNote(WaybillRequestStatus status, String waybillNo) {
    return switch (status) {
      case QUEUED -> "已入队，等待承运商限流窗口放行取号（内部状态，非承运商轨迹）";
      case SUBMITTING -> "已从队列取出，正在调承运商取号（内部状态，非承运商轨迹）";
      case ISSUED -> "承运商已返回运单号 " + nullSafe(waybillNo, "")
          + "；**已揽收/在途/已签收没有数据源**（10.3 未交付），所以这一行不代表货已在路上";
      case FAILED -> "申请未成功取得运单号（内部申请失败，**不是**承运商侧物流异常）";
      case CANCELLED -> "已作废（作废时还没出号；已出号的取消属 10.2.1.3）";
    };
  }

  private static String nextAction(WaybillDisplayStatus display) {
    return switch (display) {
      case PENDING_NUMBER -> "等待渠道窗口放行取号（10.2.1.2 运单申请）";
      case NUMBER_ISSUED -> "可批量打印面单；承运商轨迹未交付，无法显示在途状态";
      case APPLICATION_FAILED -> "先看失败原因，再批量申请运单（重试）";
      case CANCELLED -> "已作废，无后续动作；需要发货请重新提交一条申请";
      default -> "无";
    };
  }

  private static List<String> nextActions(WaybillListRow row) {
    return switch (row.displayStatus()) {
      case PENDING_NUMBER -> List.of("等待渠道窗口放行取号（10.2.1.2）", "可批量取消（还没出号）");
      case NUMBER_ISSUED -> List.of("批量打印（只发已登记的面单凭据）",
          "**已取号的取消与改址属 10.2.1.3，本模块不代做**");
      case APPLICATION_FAILED -> List.of("批量申请运单（重试，沿用同一幂等键）", "批量取消（还没出号）");
      case CANCELLED -> List.of("重新提交一条运单申请");
      // 下面四个态 available=false（10.3 未交付），本模块**产不出**这样的行。
      // 显式写出来而不是笼统的 default：哪天真把承运商轨迹接上，这里必须被
      // 有意识地处理 —— 静默走 default 会让动作清单悄悄变空。
      case PICKED_UP, IN_TRANSIT, DELIVERED, EXCEPTION -> List.of(
          "本模块不产出该状态（10.3 未交付）；承运商侧动作请到 10.3 处理");
    };
  }

  private WaybillListRow row(Record r) {
    WaybillRequestStatus status = WaybillRequestStatus.valueOf(r.get("status", String.class));
    WaybillDisplayStatus display = displayStatus(status);
    String waybillNo = r.get("waybill_no", String.class);
    BigDecimal freight = r.get("f_amount", BigDecimal.class);
    boolean hasLabel = waybillNo != null && !waybillNo.isBlank();
    WaybillFreightGapCode gap =
        freight == null ? WaybillFreightGapCode.NOT_REGISTERED : WaybillFreightGapCode.REGISTERED;
    WaybillLabelAvailability label =
        hasLabel ? WaybillLabelAvailability.RECORDED_REFERENCE : WaybillLabelAvailability.NO_REFERENCE;
    return new WaybillListRow(
        r.get("id", UUID.class),
        r.get("request_no", String.class),
        waybillNo,
        r.get("sales_order_no", String.class),
        r.get("outbound_order_no", String.class),
        r.get("pending_parcel_id", UUID.class),
        r.get("warehouse_ref", String.class),
        r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class),
        channelLabel(r.get("carrier_channel", String.class)),
        r.get("ship_to_country", String.class),
        display,
        display.displayName,
        "INTERNAL_REQUEST:10.2.1.2 的内部申请状态（**不是**承运商轨迹）",
        statusNote(status, waybillNo),
        status.name(),
        status.displayName,
        r.get("gross_weight_kg", BigDecimal.class),
        freight,
        freight == null ? "" : r.get("f_currency", String.class),
        gap,
        gap.displayName,
        freight == null ? "" : r.get("f_source", String.class),
        label,
        label.displayName,
        r.get("label_format", String.class),
        r.get("label_uri", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("not_before", OffsetDateTime.class),
        nextAction(display));
  }

  private static String channelLabel(String code) {
    if (code == null || code.isBlank()) return "";
    try {
      return WaybillCarrierChannel.valueOf(code).displayName;
    } catch (IllegalArgumentException e) {
      return code;
    }
  }

  private static String skipReasonForApply(Snapshot snap) {
    return switch (snap.status) {
      case ISSUED -> "已取号（运单号 " + snap.waybillNo + "），不需要再申请";
      case CANCELLED -> "已作废；需要发货请重新提交一条新的运单申请";
      case QUEUED -> "已在队列中等待渠道限流窗口，重试只会把放行时刻往后推";
      case SUBMITTING -> "已取出、正在调承运商取号，这一刻重试没有意义";
      case FAILED -> "";
    };
  }

  private WaybillBulkActionEntry entry(
      UUID requestId, Snapshot snap, String action, boolean applied, String code, String message) {
    return new WaybillBulkActionEntry(
        requestId,
        snap == null ? "" : snap.requestNo,
        snap == null ? "" : nullSafe(snap.waybillNo, ""),
        action,
        applied,
        code,
        message,
        snap == null ? "" : snap.status.name(),
        snap == null ? "" : snap.status.displayName);
  }

  private WaybillBulkActionResult result(
      String action, List<WaybillBulkActionEntry> entries, String batchNo, List<String> notes) {
    int applied = 0;
    int skipped = 0;
    int failed = 0;
    for (WaybillBulkActionEntry e : entries) {
      if (e.applied()) applied++;
      else if ("NOT_APPLICABLE".equals(e.resultCode())) skipped++;
      else failed++;
    }
    return new WaybillBulkActionResult(
        action, entries.size(), applied, skipped, failed, batchNo, entries, notes);
  }

  private List<UUID> ids(WaybillBulkActionInput input) {
    if (input.requestIds().size() > MAX_BULK) {
      throw badRequest("一次最多操作 " + MAX_BULK + " 张运单，收到 " + input.requestIds().size());
    }
    List<UUID> out = new ArrayList<>();
    for (String raw : input.requestIds()) {
      if (raw == null || raw.isBlank()) continue;
      // 去重：同一批里重复勾选不应把同一票算两次（否则 applied 计数虚高）
      UUID id = parseUuid(raw, "运单 id 不是合法 UUID：" + raw);
      if (!out.contains(id)) out.add(id);
    }
    if (out.isEmpty()) throw badRequest("必须至少选择一张运单");
    return out;
  }

  private String nextBatchNo(OffsetDateTime now) {
    Long n = sql.fetchOne("select nextval('waybill_print_batch_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT, "WPL-%s%06d", now.toLocalDate().toString().replace("-", ""), n);
  }

  // ================================================================ 工具

  private int pageSize(int size) {
    if (size <= 0) return 50;
    return Math.min(size, MAX_PAGE_SIZE);
  }

  private long countOf(String text, Object... binds) {
    Record record = binds.length == 0 ? sql.fetchOne(text) : sql.fetchOne(text, binds);
    if (record == null) return 0L;
    Number value = (Number) record.get(0);
    return value == null ? 0L : value.longValue();
  }

  /** 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。 */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid as t")
        .get("t", UUID.class);
  }

  /** PG 的 count(*) 是 bigint；用**有类型的** getter 取，避免泛型推断拿到 Object。 */
  private static long longOf(Long value) {
    return value == null ? 0L : value;
  }

  private static String message(RuntimeException e) {
    if (e instanceof ResponseStatusException rse) {
      return rse.getReason() == null ? rse.getStatusCode().toString() : rse.getReason();
    }
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String nullSafe(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private static Object[] concat(List<Object> base, Object... tail) {
    Object[] all = new Object[base.size() + tail.length];
    for (int i = 0; i < base.size(); i++) all[i] = base.get(i);
    System.arraycopy(tail, 0, all, base.size(), tail.length);
    return all;
  }

  private static UUID parseUuid(String value, String message) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException e) {
      throw badRequest(message);
    }
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }
}
