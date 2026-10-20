package com.starcon.channel;

import static com.starcon.channel.StockForecastModels.*;

import com.starcon.channel.warehouse.OverseasWarehouseAdapter;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.4.2.2 备货预报。
 *
 * <p>蓝图：「向海外仓/平台仓提交备货计划：SKU 与数量、目的仓、期望到货时间、平台货件编号，
 * 接收仓端接收与拒收反馈。」
 *
 * <p>🔴 蓝图 §10.4.2.2 加粗句：「**平台仓备货有严格规格**（箱型、贴标、托盘、混装限制），
 * 规格不符会被拒收造成额外成本与延误。」
 *
 * <p>本类把这句话落成三件事：
 *
 * <ol>
 *   <li>规格是<b>可维护的结构化数据</b>（{@code warehouse_forecast_spec}），不是注释里的散文；
 *   <li>预报单<b>必须先过规格校验</b>才能提交（{@link ForecastStatus#DRAFT} → {@code VALIDATED} → {@code SUBMITTED}），
 *       校验不过**逐条点名违反哪一条**（{@link SpecViolation}），而不是「提交成功然后被仓端拒收」；
 *   <li>规格版本在建单时<b>冻结</b>在预报单上（{@code spec_id}），后来改规格不影响已提交的预报单 ——
 *       否则「按当时的规定验的」这句话没法追溯，拒收纠纷就没有依据。
 * </ol>
 *
 * <p>🔴 <b>货件任务不写假 HTTP 客户端</b>：本仓库没有真实的平台账号与网络出口，
 * 所以「创建平台货件」这一跳由 {@link OverseasWarehouseAdapter#submitForecast} 承接，
 * 实现读的是租户/通道登记的<b>平台货件报送行</b>（{@code warehouse_shipment_report}），
 * 任务状态可推进、可查、可重试，平台货件编号是<b>登记进来的</b>而不是本服务生成的。
 *
 * <p>🔴 <b>库存与在途走只读 Port</b>：自营可售量与头程在途的真值都在 warehouse 服务的库里，
 * AGENTS.md 禁止跨服务 SQL / 跨库 FK / 共享视图，所以只能经
 * {@link ForecastInventoryQueryPort} / {@link ForecastInTransitQueryPort} 读。
 * 读不到时端口抛 503，**绝不**用 0 冒充（0 会被读成「没有库存需求」）。
 */
@Service
public class StockForecastService {
  /** 数量统一 HALF_UP 到 6 位小数，与 DB 的 numeric(19,6) 对齐（禁浮点）。 */
  private static final int QUANTITY_SCALE = 6;

  /** 单张预报单最多多少行明细：不给上限时，一张单能把上游端口的线程占住。 */
  public static final int MAX_LINES_PER_FORECAST = 200;

  /** 规格校验时单行重量换算的中间精度。 */
  private static final int WEIGHT_SCALE = 6;

  private final DSLContext sql;
  private final Map<String, OverseasWarehouseAdapter> adapters;
  private final ForecastInventoryQueryPort inventoryPort;
  private final ForecastInTransitQueryPort inTransitPort;

  public StockForecastService(
      DSLContext sql,
      List<OverseasWarehouseAdapter> adapterList,
      ForecastInventoryQueryPort inventoryPort,
      ForecastInTransitQueryPort inTransitPort) {
    this.sql = sql;
    this.adapters =
        adapterList.stream()
            .collect(Collectors.toMap(OverseasWarehouseAdapter::providerCode, Function.identity()));
    this.inventoryPort = inventoryPort;
    this.inTransitPort = inTransitPort;
  }

  // ==================================================================
  // 目的仓与规格
  // ==================================================================

  /** 目的仓列表（10.4.2.1 的仓账户，跨服务不可查，只能在本服务库内读）。 */
  public List<Map<String, Object>> destinationAccounts() {
    return sql
        .fetch(
            "select a.id,a.name,a.external_account_code,a.country_code,a.warehouse_kind,"
                + " p.code provider_code,c.display_name connection_name"
                + " from warehouse_account a"
                + " join warehouse_connection c on c.tenant_id=a.tenant_id and c.id=a.connection_id"
                + " join warehouse_provider p on p.id=c.provider_id"
                + " where a.status='ACTIVE' order by a.name,a.external_account_code")
        .stream()
        .map(
            r ->
                Map.<String, Object>of(
                    "id", r.get("id", UUID.class),
                    "name", nullSafe(r.get("name", String.class)),
                    "externalAccountCode", nullSafe(r.get("external_account_code", String.class)),
                    "countryCode", nullSafe(r.get("country_code", String.class)),
                    "warehouseKind", nullSafe(r.get("warehouse_kind", String.class)),
                    "providerCode", nullSafe(r.get("provider_code", String.class)),
                    "connectionName", nullSafe(r.get("connection_name", String.class))))
        .toList();
  }

  private static final String SPEC_SELECT =
      "select s.id,s.account_id,a.name account_name,s.version_no,s.status,"
          + " s.allowed_box_types,s.max_units_per_carton,s.max_weight_per_carton_kg,"
          + " s.max_skus_per_carton,s.requires_fnsku_label,s.requires_carton_label,"
          + " s.allowed_label_formats,s.pallet_required,s.max_cartons_per_pallet,"
          + " s.max_pallet_weight_kg,s.mixed_loading_allowed,s.min_arrival_lead_days,"
          + " s.note,s.created_at,s.retired_at"
          + " from warehouse_forecast_spec s"
          + " join warehouse_account a on a.tenant_id=s.tenant_id and a.id=s.account_id";

  private ForecastSpecRow mapSpec(Record r) {
    return new ForecastSpecRow(
        r.get("id", UUID.class),
        r.get("account_id", UUID.class),
        nullSafe(r.get("account_name", String.class)),
        r.get("version_no", Integer.class),
        r.get("status", String.class),
        textArray(r.get("allowed_box_types", String[].class)),
        r.get("max_units_per_carton", Integer.class),
        r.get("max_weight_per_carton_kg", BigDecimal.class),
        r.get("max_skus_per_carton", Integer.class),
        Boolean.TRUE.equals(r.get("requires_fnsku_label", Boolean.class)),
        Boolean.TRUE.equals(r.get("requires_carton_label", Boolean.class)),
        textArray(r.get("allowed_label_formats", String[].class)),
        Boolean.TRUE.equals(r.get("pallet_required", Boolean.class)),
        r.get("max_cartons_per_pallet", Integer.class),
        r.get("max_pallet_weight_kg", BigDecimal.class),
        Boolean.TRUE.equals(r.get("mixed_loading_allowed", Boolean.class)),
        r.get("min_arrival_lead_days", Integer.class),
        nullSafe(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class),
        r.get("retired_at", OffsetDateTime.class));
  }

  /** 规格列表（可按目的仓筛；不筛时返回全部版本，含已停用）。 */
  public List<ForecastSpecRow> specs(UUID accountId) {
    boolean filtered = accountId != null;
    var rows =
        sql.fetch(
            SPEC_SELECT
                + " where true"
                + (filtered ? " and s.account_id=?" : "")
                + " order by s.account_id,s.version_no desc",
            filtered ? new Object[] {accountId} : new Object[0]);
    return rows.stream().map(this::mapSpec).toList();
  }

  /** 某目的仓当前生效的规格（没有则返回 null —— 调用方必须显式处理「没有规格」）。 */
  public ForecastSpecRow activeSpec(UUID accountId) {
    Record r = sql.fetchOne(SPEC_SELECT + " where s.account_id=? and s.status='ACTIVE'", accountId);
    return r == null ? null : mapSpec(r);
  }

  /**
   * 新建一版备货规格。
   *
   * <p>换规格 = 建新版 + 停用旧版（**不就地改**）：预报单上冻结的是 spec_id，
   * 已提交的那一单必须还能说清「按第几版规格备的货」。
   */
  public UUID createSpec(CreateForecastSpecRequest request) {
    if (count("select count(*) from warehouse_account where id=?", request.accountId()) == 0L)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目的仓不存在");
    if (count("select count(*) from warehouse_account where id=? and status='ACTIVE'", request.accountId()) == 0L)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "目的仓已停用，不能作为备货目的仓");

    // 版本号取「该目的仓已有最大版本 + 1」
    int version =
        (int)
            count(
                    "select coalesce(max(version_no),0)+1 from warehouse_forecast_spec where account_id=?",
                    request.accountId());
    // 停用旧版：同一目的仓同时只能有一版 ACTIVE（DB 上还有 partial unique index 兜底）
    sql.execute(
        "update warehouse_forecast_spec set status='RETIRED',retired_at=now()"
            + " where account_id=? and status='ACTIVE'",
        request.accountId());

    UUID id = Ids.next();
    sql.execute(
        "insert into warehouse_forecast_spec(id,tenant_id,account_id,version_no,status,"
            + " allowed_box_types,max_units_per_carton,max_weight_per_carton_kg,max_skus_per_carton,"
            + " requires_fnsku_label,requires_carton_label,allowed_label_formats,pallet_required,"
            + " max_cartons_per_pallet,max_pallet_weight_kg,mixed_loading_allowed,min_arrival_lead_days,note)"
            + " values (?,current_setting('app.current_tenant',true)::uuid,?,?, 'ACTIVE',"
            + " ?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        request.accountId(),
        version,
        request.allowedBoxTypes() == null ? new String[0] : request.allowedBoxTypes().toArray(new String[0]),
        request.maxUnitsPerCarton(),
        request.maxWeightPerCartonKg(),
        request.maxSkusPerCarton(),
        Boolean.TRUE.equals(request.requiresFnskuLabel()),
        Boolean.TRUE.equals(request.requiresCartonLabel()),
        request.allowedLabelFormats() == null
            ? new String[0]
            : request.allowedLabelFormats().toArray(new String[0]),
        Boolean.TRUE.equals(request.palletRequired()),
        request.maxCartonsPerPallet(),
        request.maxPalletWeightKg(),
        request.mixedLoadingAllowed() == null || request.mixedLoadingAllowed(),
        request.minArrivalLeadDays() == null ? 0 : request.minArrivalLeadDays(),
        request.note() == null ? "" : request.note());
    return id;
  }

  /** 停用一版规格。停用后不能有新预报单用它，但已建的单仍按冻结的版本走。 */
  public void retireSpec(UUID specId) {
    int n =
        sql.execute(
            "update warehouse_forecast_spec set status='RETIRED',retired_at=now()"
                + " where id=? and status='ACTIVE'",
            specId);
    if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "规格不存在或已停用");
  }

  // ==================================================================
  // 预报单
  // ==================================================================

  private static final String FORECAST_SELECT =
      "select f.id,f.forecast_no,f.account_id,a.name account_name,p.code provider_code,"
          + " f.source,f.status,f.expected_arrival_at,f.platform_shipment_id,f.submitted_at,"
          + " f.closed_at,f.last_error,f.note,f.created_at,"
          + " coalesce(sum(l.quantity),0) total_quantity,count(l.id) line_count"
          + " from warehouse_forecast f"
          + " join warehouse_account a on a.tenant_id=f.tenant_id and a.id=f.account_id"
          + " join warehouse_connection c on c.tenant_id=f.tenant_id and c.id=a.connection_id"
          + " join warehouse_provider p on p.id=c.provider_id"
          + " left join warehouse_forecast_line l on l.tenant_id=f.tenant_id and l.forecast_id=f.id";

  private ForecastRow mapForecast(Record r) {
    return new ForecastRow(
        r.get("id", UUID.class),
        r.get("forecast_no", String.class),
        r.get("account_id", UUID.class),
        nullSafe(r.get("account_name", String.class)),
        nullSafe(r.get("provider_code", String.class)),
        r.get("source", String.class),
        r.get("status", String.class),
        r.get("expected_arrival_at", OffsetDateTime.class),
        r.get("total_quantity", BigDecimal.class),
        r.get("line_count", Integer.class),
        nullSafe(r.get("platform_shipment_id", String.class)),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("closed_at", OffsetDateTime.class),
        nullSafe(r.get("last_error", String.class)),
        nullSafe(r.get("note", String.class)),
        r.get("created_at", OffsetDateTime.class));
  }

  /** 预报单列表：真分页 + 真筛选 + 真排序。 */
  public ForecastPage forecasts(
      String status, UUID accountId, String keyword, String sort, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> binds = new ArrayList<>();
    if (status != null && !status.isBlank()) {
      where.append(" and f.status=?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (accountId != null) {
      where.append(" and f.account_id=?");
      binds.add(accountId);
    }
    String kw = keyword == null ? "" : keyword.trim();
    if (!kw.isEmpty()) {
      where.append(
          " and (f.forecast_no ilike ? or f.note ilike ? or exists (select 1"
              + " from warehouse_forecast_line kl where kl.tenant_id=f.tenant_id"
              + " and kl.forecast_id=f.id and kl.sku_code ilike ?))");
      String like = "%" + kw + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }

    long total =
        count(
            "select count(*) from warehouse_forecast f"
                + " join warehouse_account a on a.tenant_id=f.tenant_id and a.id=f.account_id"
                + where,
            binds.toArray());

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    var rows =
        sql.fetch(
            FORECAST_SELECT
                + where
                + " group by f.id,a.name,p.code"
                + " order by "
                + forecastOrderBy(sort)
                + " limit ? offset ?",
            pageBinds.toArray());

    return new ForecastPage(
        rows.stream().map(this::mapForecast).toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        forecastSummary());
  }

  /** 状态汇总：**全量**（不受分页与筛选影响）—— 合计要能用来核对，翻页时它不能变。 */
  public ForecastSummary forecastSummary() {
    Record r =
        sql.fetchOne(
            "select count(*) total,"
                + " count(*) filter (where status='DRAFT') draft,"
                + " count(*) filter (where status='VALIDATED') validated,"
                + " count(*) filter (where status='SUBMITTED') submitted,"
                + " count(*) filter (where status='SHIPMENT_CREATED') shipment_created,"
                + " count(*) filter (where status='ACCEPTED') accepted,"
                + " count(*) filter (where status='REJECTED') rejected,"
                + " count(*) filter (where status='CANCELLED') cancelled"
                + " from warehouse_forecast");
    BigDecimal qty =
        sql.fetchOne(
                "select coalesce(sum(l.quantity),0) from warehouse_forecast_line l"
                    + " join warehouse_forecast f on f.tenant_id=l.tenant_id and f.id=l.forecast_id")
            .get(0, BigDecimal.class);
    return new ForecastSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("validated", Long.class),
        r.get("submitted", Long.class),
        r.get("shipment_created", Long.class),
        r.get("accepted", Long.class),
        r.get("rejected", Long.class),
        r.get("cancelled", Long.class),
        qty);
  }

  private static String forecastOrderBy(String sort) {
    String s = sort == null ? "EXPECTED_ARRIVAL_DESC" : sort.toUpperCase(Locale.ROOT);
    return switch (s) {
      case "EXPECTED_ARRIVAL_ASC" -> "f.expected_arrival_at asc, f.id";
      case "CREATED_AT_ASC" -> "f.created_at asc, f.id";
      case "CREATED_AT_DESC" -> "f.created_at desc, f.id";
      case "FORECAST_NO_ASC" -> "f.forecast_no asc";
      case "STATUS_ASC" -> "f.status asc, f.expected_arrival_at desc, f.id";
      default -> "f.expected_arrival_at desc, f.id";
    };
  }

  /** 预报单详情：头 + 明细 + 冻结的规格 + 货件任务 + 回执 + 规格校验结果。 */
  public ForecastDetail forecast(UUID id) {
    Record r = sql.fetchOne(FORECAST_SELECT + " where f.id=? group by f.id,a.name,p.code", id);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");
    ForecastRow row = mapForecast(r);
    return new ForecastDetail(
        row,
        lines(id),
        specOf(row.accountId(), row.id()),
        shipmentTask(id),
        receipts(id),
        specCheck(id));
  }

  /** 预报明细。 */
  public List<ForecastLineRow> lines(UUID forecastId) {
    return sql
        .fetch(
            "select id,sku_code,quantity,unit,box_type,carton_count,pallet_count,label_format,"
                + " carton_label_ref,unit_weight_kg,note,position"
                + " from warehouse_forecast_line where forecast_id=? order by position,id",
            forecastId)
        .stream()
        .map(
            l ->
                new ForecastLineRow(
                    l.get("id", UUID.class),
                    l.get("sku_code", String.class),
                    l.get("quantity", BigDecimal.class),
                    l.get("unit", String.class),
                    nullSafe(l.get("box_type", String.class)),
                    l.get("carton_count", Integer.class),
                    l.get("pallet_count", Integer.class),
                    nullSafe(l.get("label_format", String.class)),
                    nullSafe(l.get("carton_label_ref", String.class)),
                    l.get("unit_weight_kg", BigDecimal.class),
                    nullSafe(l.get("note", String.class)),
                    l.get("position", Integer.class)))
        .toList();
  }

  /** 预报单冻结的那一版规格（按 spec_id 取，不是「当前生效版」）。 */
  public ForecastSpecRow specOf(UUID accountId, UUID forecastId) {
    UUID specId =
        sql.fetchOne("select spec_id from warehouse_forecast where id=?", forecastId)
            .get(0, UUID.class);
    Record r = sql.fetchOne(SPEC_SELECT + " where s.id=?", specId);
    return r == null ? activeSpec(accountId) : mapSpec(r);
  }

  /**
   * 建预报单（SKU 与数量 + 目的仓 + 期望到货时间）。
   *
   * <p>🔴 目的仓必须已有<b>生效的备货规格</b>：没有规格就没有「规格不符」的判据，
   * 这样的预报单永远过不了校验 —— 与其留一张注定被拒的单，不如当场说清缺什么。
   */
  public UUID createForecast(CreateForecastRequest request) {
    if (request.lines() == null || request.lines().isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预报单至少要有一行明细");
    if (request.lines().size() > MAX_LINES_PER_FORECAST)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一张预报单最多 " + MAX_LINES_PER_FORECAST + " 行明细");

    if (count("select count(*) from warehouse_account where id=?", request.accountId()) == 0L)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目的仓不存在");

    ForecastSpecRow spec = activeSpec(request.accountId());
    if (spec == null)
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "该目的仓还没有生效的备货规格：请先在「备货规格」里配置箱型、贴标、托盘与混装限制，"
              + "否则规格不符被仓端拒收时没有依据可查");

    String source =
        request.source() == null || request.source().isBlank()
            ? ForecastSource.MANUAL.name()
            : request.source().trim().toUpperCase(Locale.ROOT);
    if (!source.equals(ForecastSource.MANUAL.name())
        && !source.equals(ForecastSource.FBA_SHIPMENT_PLAN.name())
        && !source.equals(ForecastSource.REPLENISHMENT_SUGGESTION.name()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预报来源不合法：" + request.source());

    UUID id = Ids.next();
    String forecastNo = nextForecastNo(request.expectedArrivalAt());
    sql.execute(
        "insert into warehouse_forecast(id,tenant_id,forecast_no,account_id,spec_id,source,status,"
            + " expected_arrival_at,note) values (?,current_setting('app.current_tenant',true)::uuid,"
            + " ?,?,?,?, 'DRAFT',?::timestamptz,?)",
        id,
        forecastNo,
        request.accountId(),
        spec.id(),
        source,
        request.expectedArrivalAt().toString(),
        request.note() == null ? "" : request.note());

    writeLines(id, request.lines());
    return id;
  }

  /**
   * 替换预报明细（只在草稿态允许）。
   *
   * <p>离开草稿态后明细就是「已提交给平台仓的承诺」，DB 触发器会挡住改写 ——
   * 改内容只能作废后另建一张单，留下可追溯的作废痕迹。
   */
  public void replaceLines(UUID forecastId, ReplaceForecastLinesRequest request) {
    if (request.lines() == null || request.lines().isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "预报单至少要有一行明细");
    if (request.lines().size() > MAX_LINES_PER_FORECAST)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一张预报单最多 " + MAX_LINES_PER_FORECAST + " 行明细");

    String status = statusOf(forecastId);
    if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");
    if (!ForecastStatus.DRAFT.name().equals(status))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "预报单已离开草稿态，明细不可修改（请作废后另建一张）");

    sql.execute("delete from warehouse_forecast_line where forecast_id=?", forecastId);
    writeLines(forecastId, request.lines());
  }

  private void writeLines(UUID forecastId, List<ForecastLineInput> lines) {
    int position = 0;
    for (ForecastLineInput line : lines) {
      String sku = line.skuCode() == null ? "" : line.skuCode().trim();
      if (sku.isEmpty())
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "明细的 SKU 编码不能为空");
      if (line.quantity() == null || line.quantity().signum() <= 0)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "明细 " + sku + " 的数量必须大于 0");
      sql.execute(
          "insert into warehouse_forecast_line(id,tenant_id,forecast_id,sku_code,quantity,unit,"
              + " box_type,carton_count,pallet_count,label_format,carton_label_ref,unit_weight_kg,note,position)"
              + " values (?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?,?,?,?,?,?,?,?)",
          Ids.next(),
          forecastId,
          sku,
          normalizeQuantity(line.quantity()),
          line.unit() == null || line.unit().isBlank() ? "PCS" : line.unit().trim(),
          line.boxType() == null ? "" : line.boxType().trim(),
          line.cartonCount(),
          line.palletCount(),
          line.labelFormat() == null ? "" : line.labelFormat().trim(),
          line.cartonLabelRef() == null ? "" : line.cartonLabelRef().trim(),
          line.unitWeightKg(),
          line.note() == null ? "" : line.note(),
          position++);
    }
  }

  private String nextForecastNo(OffsetDateTime expectedArrivalAt) {
    Long n = sql.fetchOne("select nextval('stock_forecast_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT,
        "SF-%s%06d",
        expectedArrivalAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString().replace("-", ""),
        n);
  }

  // ==================================================================
  // 规格校验（蓝图加粗句的落点）
  // ==================================================================

  /**
   * 规格校验（只读，不改状态）。
   *
   * <p>逐条对照规格的箱型 / 贴标 / 托盘 / 混装四组限制，**逐条点名违反哪一条**。
   * 返回的 {@code violations} 是结构化的，不是「规格不符」四个字。
   */
  public SpecCheckResult specCheck(UUID forecastId) {
    Record f = sql.fetchOne("select id,account_id,spec_id,expected_arrival_at from warehouse_forecast where id=?", forecastId);
    if (f == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");

    UUID specId = f.get("spec_id", UUID.class);
    Record specRow = sql.fetchOne(SPEC_SELECT + " where s.id=?", specId);
    List<ForecastLineRow> lines = lines(forecastId);
    OffsetDateTime expectedArrival = f.get("expected_arrival_at", OffsetDateTime.class);

    if (specRow == null) {
      return failed(
          List.of(
              new SpecViolation(
                  SpecRule.SPEC_MISSING.name(),
                  SpecRule.SPEC_MISSING.label(),
                  null,
                  "预报单冻结的备货规格版本不存在，无法核对规格",
                  "一个生效的备货规格版本",
                  null)),
          null,
          null);
    }
    ForecastSpecRow spec = mapSpec(specRow);
    List<SpecViolation> violations = new ArrayList<>();
    checkLines(spec, lines, violations);
    checkBoxTypeMixing(spec, lines, violations);
    checkLeadTime(spec, expectedArrival, violations);

    return violations.isEmpty()
        ? new SpecCheckResult(
            true, List.of(), spec.id(), spec.versionNo(), OffsetDateTime.now(), "规格校验通过")
        : failed(violations, spec.id(), spec.versionNo());
  }

  /**
   * 提交前的规格闸：校验不过就**明确拒绝**并指出违反哪一条。
   *
   * <p>蓝图明说规格不符会被仓端**拒收**、造成额外成本与延误 —— 所以这一步必须是
   * 提交前的硬闸，而不是「提交成功然后被拒收」。
   */
  public SpecCheckResult validate(UUID forecastId) {
    String status = statusOf(forecastId);
    if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");
    if (ForecastStatus.VALIDATED.name().equals(status)) {
      // 已经过闸：幂等返回当前结果，不重复推进状态
      return specCheck(forecastId);
    }
    if (!ForecastStatus.DRAFT.name().equals(status))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有草稿态的预报单可以过规格校验（当前 " + status + "）");

    SpecCheckResult result = specCheck(forecastId);
    if (!result.passed()) {
      throw new SpecValidationException(result.violations());
    }
    sql.execute(
        "update warehouse_forecast set status='VALIDATED',validated_at=now(),updated_at=now(),"
            + " version=version+1 where id=? and status='DRAFT'",
        forecastId);
    return result;
  }

  private void checkLines(
      ForecastSpecRow spec, List<ForecastLineRow> lines, List<SpecViolation> violations) {
    Set<String> allowedBoxTypes = new LinkedHashSet<>(spec.allowedBoxTypes());
    Set<String> allowedLabelFormats = new LinkedHashSet<>(spec.allowedLabelFormats());
    Map<String, Integer> skuSeen = new LinkedHashMap<>();

    for (ForecastLineRow line : lines) {
      String sku = line.skuCode();

      // 同一预报单内 SKU 重复
      Integer seen = skuSeen.get(sku);
      if (seen != null) {
        int times = seen + 1;
        skuSeen.put(sku, times);
        violations.add(
            new SpecViolation(
                SpecRule.DUPLICATE_SKU.name(),
                SpecRule.DUPLICATE_SKU.label(),
                sku,
                "SKU " + sku + " 在同一张预报单里出现了 " + times + " 次",
                "同一 SKU 只出现一行",
                "出现 " + times + " 次"));
      } else {
        skuSeen.put(sku, 1);
      }

      if (line.quantity() == null || line.quantity().signum() <= 0) {
        violations.add(
            new SpecViolation(
                SpecRule.QUANTITY_NOT_POSITIVE.name(),
                SpecRule.QUANTITY_NOT_POSITIVE.label(),
                sku,
                "SKU " + sku + " 的数量必须大于 0",
                "> 0",
                String.valueOf(line.quantity())));
        continue;
      }

      // ---- 箱型 ----
      if (!allowedBoxTypes.isEmpty() && !allowedBoxTypes.contains(line.boxType())) {
        violations.add(
            new SpecViolation(
                SpecRule.BOX_TYPE_NOT_ALLOWED.name(),
                SpecRule.BOX_TYPE_NOT_ALLOWED.label(),
                sku,
                "SKU " + sku + " 的箱型「" + line.boxType() + "」不在目的仓允许范围内",
                String.join(" / ", allowedBoxTypes),
                line.boxType()));
      }

      // ---- 箱数（单箱件数/单箱重量/单托盘箱数都要靠它换算）----
      boolean needsCartons =
          spec.maxUnitsPerCarton() != null
              || spec.maxWeightPerCartonKg() != null
              || spec.maxCartonsPerPallet() != null;
      Integer cartons = line.cartonCount();
      if (needsCartons && (cartons == null || cartons <= 0)) {
        violations.add(
            new SpecViolation(
                SpecRule.CARTON_COUNT_MISSING.name(),
                SpecRule.CARTON_COUNT_MISSING.label(),
                sku,
                "SKU " + sku + " 缺少箱数，无法核对单箱限制",
                "箱数 > 0",
                cartons == null ? "未填" : String.valueOf(cartons)));
      }

      if (cartons != null && cartons > 0) {
        if (spec.maxUnitsPerCarton() != null) {
          BigDecimal perCarton =
              line.quantity().divide(BigDecimal.valueOf(cartons), WEIGHT_SCALE, RoundingMode.HALF_UP);
          if (perCarton.compareTo(BigDecimal.valueOf(spec.maxUnitsPerCarton())) > 0) {
            violations.add(
                new SpecViolation(
                    SpecRule.MAX_UNITS_PER_CARTON_EXCEEDED.name(),
                    SpecRule.MAX_UNITS_PER_CARTON_EXCEEDED.label(),
                    sku,
                    "SKU " + sku + " 单箱 " + perCarton.toPlainString() + " 件，超出上限 "
                        + spec.maxUnitsPerCarton(),
                    "≤ " + spec.maxUnitsPerCarton(),
                    perCarton.toPlainString()));
          }
        }
        if (spec.maxCartonsPerPallet() != null && cartons > spec.maxCartonsPerPallet()) {
          violations.add(
              new SpecViolation(
                  SpecRule.MAX_CARTONS_PER_PALLET_EXCEEDED.name(),
                  SpecRule.MAX_CARTONS_PER_PALLET_EXCEEDED.label(),
                  sku,
                  "SKU " + sku + " 单托盘 " + cartons + " 箱，超出上限 "
                      + spec.maxCartonsPerPallet(),
                  "≤ " + spec.maxCartonsPerPallet(),
                  String.valueOf(cartons)));
        }
      }

      // ---- 重量（规格有重量上限时才需要单重；缺单重就报「没给」，不按 0 算）----
      boolean needsWeight = spec.maxWeightPerCartonKg() != null || spec.maxPalletWeightKg() != null;
      if (needsWeight) {
        if (line.unitWeightKg() == null) {
          violations.add(
              new SpecViolation(
                  SpecRule.UNIT_WEIGHT_MISSING.name(),
                  SpecRule.UNIT_WEIGHT_MISSING.label(),
                  sku,
                  "SKU " + sku + " 缺少单件重量，无法核对重量上限",
                  "单件重量 > 0",
                  "未填"));
        } else {
          BigDecimal totalWeight =
              line.quantity().multiply(line.unitWeightKg()).setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
          if (spec.maxWeightPerCartonKg() != null && cartons != null && cartons > 0) {
            BigDecimal perCarton =
                totalWeight.divide(BigDecimal.valueOf(cartons), WEIGHT_SCALE, RoundingMode.HALF_UP);
            if (perCarton.compareTo(spec.maxWeightPerCartonKg()) > 0) {
              violations.add(
                  new SpecViolation(
                      SpecRule.MAX_WEIGHT_PER_CARTON_EXCEEDED.name(),
                      SpecRule.MAX_WEIGHT_PER_CARTON_EXCEEDED.label(),
                      sku,
                      "SKU " + sku + " 单箱 " + perCarton.toPlainString() + " kg，超出上限 "
                          + spec.maxWeightPerCartonKg().toPlainString() + " kg",
                      "≤ " + spec.maxWeightPerCartonKg().toPlainString() + " kg",
                      perCarton.toPlainString() + " kg"));
            }
          }
          if (spec.maxPalletWeightKg() != null
              && totalWeight.compareTo(spec.maxPalletWeightKg()) > 0) {
            violations.add(
                new SpecViolation(
                    SpecRule.PALLET_WEIGHT_EXCEEDED.name(),
                    SpecRule.PALLET_WEIGHT_EXCEEDED.label(),
                    sku,
                    "SKU " + sku + " 托盘重量 " + totalWeight.toPlainString()
                        + " kg，超出上限 " + spec.maxPalletWeightKg().toPlainString() + " kg",
                    "≤ " + spec.maxPalletWeightKg().toPlainString() + " kg",
                    totalWeight.toPlainString() + " kg"));
          }
        }
      }

      // ---- 贴标 ----
      if (spec.requiresFnskuLabel() && line.labelFormat().isBlank()) {
        violations.add(
            new SpecViolation(
                SpecRule.LABEL_FORMAT_MISSING.name(),
                SpecRule.LABEL_FORMAT_MISSING.label(),
                sku,
                "SKU " + sku + " 缺少贴标格式，目的仓要求按 SKU 贴标",
                "贴标格式（如 FNSKU）",
                "未填"));
      } else if (!line.labelFormat().isBlank()
          && !allowedLabelFormats.isEmpty()
          && !allowedLabelFormats.contains(line.labelFormat())) {
        violations.add(
            new SpecViolation(
                SpecRule.LABEL_FORMAT_NOT_ALLOWED.name(),
                SpecRule.LABEL_FORMAT_NOT_ALLOWED.label(),
                sku,
                "SKU " + sku + " 的贴标格式「" + line.labelFormat() + "」不在允许范围内",
                String.join(" / ", allowedLabelFormats),
                line.labelFormat()));
      }
      if (spec.requiresCartonLabel() && line.cartonLabelRef().isBlank()) {
        violations.add(
            new SpecViolation(
                SpecRule.CARTON_LABEL_MISSING.name(),
                SpecRule.CARTON_LABEL_MISSING.label(),
                sku,
                "SKU " + sku + " 缺少箱唛编号，目的仓要求逐箱贴标",
                "箱唛编号",
                "未填"));
      }

      // ---- 托盘 ----
      if (spec.palletRequired()
          && (line.palletCount() == null || line.palletCount() <= 0)) {
        violations.add(
            new SpecViolation(
                SpecRule.PALLET_REQUIRED_MISSING.name(),
                SpecRule.PALLET_REQUIRED_MISSING.label(),
                sku,
                "SKU " + sku + " 未给出托盘数，目的仓要求整托发运",
                "托盘数 > 0",
                line.palletCount() == null ? "未填" : String.valueOf(line.palletCount())));
      }
    }
  }

  /** 混装限制：同一箱型下能出现几个 SKU。 */
  private void checkBoxTypeMixing(
      ForecastSpecRow spec, List<ForecastLineRow> lines, List<SpecViolation> violations) {
    Map<String, Set<String>> byBoxType = new LinkedHashMap<>();
    for (ForecastLineRow line : lines) {
      if (line.boxType().isBlank()) continue;
      byBoxType.computeIfAbsent(line.boxType(), k -> new LinkedHashSet<>()).add(line.skuCode());
    }
    for (var entry : byBoxType.entrySet()) {
      String boxType = entry.getKey();
      Set<String> skus = entry.getValue();
      if (!spec.mixedLoadingAllowed() && skus.size() > 1) {
        violations.add(
            new SpecViolation(
                SpecRule.MIXED_LOADING_FORBIDDEN.name(),
                SpecRule.MIXED_LOADING_FORBIDDEN.label(),
                null,
                "箱型「" + boxType + "」下有 " + skus.size() + " 个 SKU（" + String.join(" / ", skus)
                    + "），目的仓禁止该箱型混装",
                "该箱型只允许 1 个 SKU",
                String.join(" / ", skus)));
      }
      if (spec.maxSkusPerCarton() != null && skus.size() > spec.maxSkusPerCarton()) {
        violations.add(
            new SpecViolation(
                SpecRule.MAX_SKUS_PER_CARTON_EXCEEDED.name(),
                SpecRule.MAX_SKUS_PER_CARTON_EXCEEDED.label(),
                null,
                "箱型「" + boxType + "」下有 " + skus.size() + " 个 SKU，超出单箱上限 "
                    + spec.maxSkusPerCarton(),
                "≤ " + spec.maxSkusPerCarton(),
                String.join(" / ", skus)));
      }
    }
  }

  /** 期望到货时间的提前期要求。 */
  private void checkLeadTime(
      ForecastSpecRow spec, OffsetDateTime expectedArrival, List<SpecViolation> violations) {
    int lead = spec.minArrivalLeadDays();
    if (lead <= 0 || expectedArrival == null) return;
    OffsetDateTime earliest = OffsetDateTime.now().plusDays(lead);
    if (expectedArrival.isBefore(earliest)) {
      violations.add(
          new SpecViolation(
              SpecRule.EXPECTED_ARRIVAL_TOO_SOON.name(),
              SpecRule.EXPECTED_ARRIVAL_TOO_SOON.label(),
              null,
              "期望到货时间 " + expectedArrival + " 早于目的仓要求的备货提前期 " + lead + " 天",
              "不早于 " + earliest.toLocalDate(),
              expectedArrival.toLocalDate().toString()));
    }
  }

  private SpecCheckResult failed(
      List<SpecViolation> violations, UUID specId, Integer version) {
    return new SpecCheckResult(
        false,
        violations,
        specId,
        version,
        OffsetDateTime.now(),
        "规格校验未通过：" + violations.size() + " 条违规");
  }

  // ==================================================================
  // 提交 / 货件任务
  // ==================================================================

  /**
   * 提交预报单并生成<b>平台货件创建任务</b>。
   *
   * <p>提交前必须已过规格校验（{@code VALIDATED}）—— 这是蓝图加粗句的硬闸。
   */
  public UUID submit(UUID forecastId) {
    String status = statusOf(forecastId);
    if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");
    if (!ForecastStatus.VALIDATED.name().equals(status)) {
      if (ForecastStatus.DRAFT.name().equals(status))
        throw new ResponseStatusException(
            HttpStatus.UNPROCESSABLE_ENTITY, "预报单还没过规格校验，不能提交");
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "预报单当前状态不允许提交（" + status + "）");
    }
    if (shipmentTask(forecastId) != null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "这张预报单已经有货件任务了");

    UUID accountId =
        sql.fetchOne("select account_id from warehouse_forecast where id=?", forecastId)
            .get(0, UUID.class);
    String providerCode = providerCodeOf(accountId);

    // 规格闸再跑一次：建单之后规格可能被换版（虽然预报单冻结的是旧版，
    // 但冻结版可能被停用），提交那一刻必须按**冻结的那一版**再确认一次。
    SpecCheckResult check = specCheck(forecastId);
    if (!check.passed()) throw new SpecValidationException(check.violations());

    Long seq = sql.fetchOne("select nextval('stock_shipment_task_no_seq')").get(0, Long.class);
    String taskNo = String.format(Locale.ROOT, "SFT-%06d", seq);
    UUID taskId = Ids.next();
    sql.execute(
        "insert into warehouse_shipment_task(id,tenant_id,forecast_id,account_id,provider_code,task_no,status)"
            + " values (?,current_setting('app.current_tenant',true)::uuid,?,?,?,?, 'PENDING')",
        taskId,
        forecastId,
        accountId,
        providerCode,
        taskNo);
    sql.execute(
        "update warehouse_forecast set status='SUBMITTED',submitted_at=now(),updated_at=now(),"
            + " version=version+1 where id=? and status='VALIDATED'",
        forecastId);
    return taskId;
  }

  /** 货件任务。 */
  public ShipmentTaskRow shipmentTask(UUID forecastId) {
    Record r =
        sql.fetchOne(
            "select id,forecast_id,task_no,status,attempts,platform_shipment_id,last_error,"
                + " acknowledged_at,created_at,updated_at from warehouse_shipment_task where forecast_id=?",
            forecastId);
    return r == null ? null : mapTask(r);
  }

  private ShipmentTaskRow mapTask(Record r) {
    return new ShipmentTaskRow(
        r.get("id", UUID.class),
        r.get("forecast_id", UUID.class),
        r.get("task_no", String.class),
        r.get("status", String.class),
        r.get("attempts", Integer.class),
        nullSafe(r.get("platform_shipment_id", String.class)),
        nullSafe(r.get("last_error", String.class)),
        r.get("acknowledged_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 推进平台货件创建任务：{@code PENDING → SUBMITTED → SUCCEEDED / FAILED}。
   *
   * <p>🔴 这一跳<b>不打真实平台域名</b>：适配器读的是租户/通道登记的平台货件报送行。
   * 平台侧没有登记时任务进入 {@code FAILED}（可重试），**绝不**凭空造一个货件编号。
   *
   * <p>🔴 <b>服务商侧失败时本方法不抛异常</b>：失败状态必须真的落库（可查、可重试）。
   * 租户事务在 RuntimeException 上会回滚 —— 抛异常会把「FAILED」这笔记录一起回滚掉，
   * 现场看到的就是「任务还是 PENDING，什么都没发生过」。所以这里把失败**如实返回**，
   * 由控制器决定用 502 把「没成功」如实告诉调用方。
   */
  public ShipmentTaskRow dispatchTask(UUID forecastId, String bearer) {
    ShipmentTaskRow task = shipmentTask(forecastId);
    if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "货件任务不存在");
    ShipmentTaskStatus status = ShipmentTaskStatus.valueOf(task.status());
    if (status != ShipmentTaskStatus.PENDING)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有待推进（PENDING）的货件任务可以推进（当前 " + status + "）");

    UUID accountId =
        sql.fetchOne("select account_id from warehouse_forecast where id=?", forecastId)
            .get(0, UUID.class);
    String providerCode = providerCodeOf(accountId);
    OverseasWarehouseAdapter adapter = adapters.get(providerCode);
    if (adapter == null)
      throw new ResponseStatusException(
          HttpStatus.SERVICE_UNAVAILABLE, "该服务商尚未接入适配器（" + providerCode + "）");

    sql.execute(
        "update warehouse_shipment_task set status='SUBMITTED',attempts=attempts+1,updated_at=now()"
            + " where id=? and status='PENDING'",
        task.id());

    List<OverseasWarehouseAdapter.ForecastLine> planLines =
        lines(forecastId).stream()
            .map(
                l ->
                    new OverseasWarehouseAdapter.ForecastLine(
                        l.skuCode(), l.quantity(), l.unit(), l.boxType()))
            .toList();
    Record f =
        sql.fetchOne("select id,forecast_no,expected_arrival_at from warehouse_forecast where id=?", forecastId);
    OverseasWarehouseAdapter.ForecastPlan plan =
        new OverseasWarehouseAdapter.ForecastPlan(
            forecastId,
            accountId,
            f.get("forecast_no", String.class),
            planLines,
            f.get("expected_arrival_at", OffsetDateTime.class).toInstant());

    try {
      OverseasWarehouseAdapter.ShipmentAck ack =
          adapter.submitForecast(connectionSettings(accountId), plan);
      sql.execute(
          "update warehouse_shipment_task set status='SUCCEEDED',platform_shipment_id=?,"
              + " acknowledged_at=?::timestamptz,last_error='',updated_at=now() where id=?",
          ack.platformShipmentId(),
          ack.acknowledgedAt() == null ? OffsetDateTime.now().toString() : ack.acknowledgedAt().toString(),
          task.id());
      sql.execute(
          "update warehouse_forecast set status='SHIPMENT_CREATED',platform_shipment_id=?,"
              + " updated_at=now(),version=version+1 where id=? and status='SUBMITTED'",
          ack.platformShipmentId(),
          forecastId);
      return shipmentTask(forecastId);
    } catch (OverseasWarehouseAdapter.WarehouseProviderException e) {
      // 如实记 FAILED（可重试），**不抛异常** —— 抛出去会把这两笔更新一起回滚掉。
      sql.execute(
          "update warehouse_shipment_task set status='FAILED',last_error=?,updated_at=now()"
              + " where id=? and status='SUBMITTED'",
          e.getMessage(),
          task.id());
      sql.execute(
          "update warehouse_forecast set last_error=?,updated_at=now() where id=?",
          e.getMessage(),
          forecastId);
      return shipmentTask(forecastId);
    }
  }

  /** 重试失败���货件任务：{@code FAILED → PENDING}，attempts 继续累加。 */
  public ShipmentTaskRow retryTask(UUID forecastId) {
    ShipmentTaskRow task = shipmentTask(forecastId);
    if (task == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "货件任务不存在");
    if (!ShipmentTaskStatus.FAILED.name().equals(task.status()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有失败的货件任务可以重试（当前 " + task.status() + "）");
    sql.execute(
        "update warehouse_shipment_task set status='PENDING',last_error='',updated_at=now()"
            + " where id=? and status='FAILED'",
        task.id());
    return shipmentTask(forecastId);
  }

  /**
   * 登记平台货件报送行（货件任务的真实输入）。
   *
   * <p>本仓库没有真实平台账号与网络出口，所以平台侧回报由**租户/通道登记**承接：
   * 登记行里那个货件编号是**真的**（来自平台回调或人工登记），不是本服务生成的。
   */
  public UUID registerShipmentReport(UUID forecastId, CreateShipmentReportRequest request) {
    String status = statusOf(forecastId);
    if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");
    ShipmentTaskRow existing = shipmentTask(forecastId);
    if (existing != null && ShipmentTaskStatus.SUCCEEDED.name().equals(existing.status()))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "货件任务已成功，不需要再登记货件编号");

    UUID accountId =
        sql.fetchOne("select account_id from warehouse_forecast where id=?", forecastId)
            .get(0, UUID.class);
    String source =
        request.source() == null || request.source().isBlank()
            ? "MANUAL_REGISTRATION"
            : request.source().trim().toUpperCase(Locale.ROOT);
    if (!source.equals("PLATFORM_CALLBACK") && !source.equals("MANUAL_REGISTRATION"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "报送来源不合法：" + request.source());

    UUID id = Ids.next();
    sql.execute(
        "insert into warehouse_shipment_report(id,tenant_id,account_id,forecast_id,"
            + " external_shipment_ref,source,reported_at) values "
            + "(?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?::timestamptz)"
            + " on conflict (tenant_id,forecast_id,external_shipment_ref) do nothing",
        id,
        accountId,
        forecastId,
        request.externalShipmentRef().trim(),
        source,
        request.reportedAt().toString());
    return id;
  }

  /** 某张预报单的平台货件报送行。 */
  public List<ShipmentReportRow> shipmentReports(UUID forecastId) {
    return sql
        .fetch(
            "select id,external_shipment_ref,source,reported_at,created_at"
                + " from warehouse_shipment_report where forecast_id=? order by reported_at desc,id",
            forecastId)
        .stream()
        .map(
            r ->
                new ShipmentReportRow(
                    r.get("id", UUID.class),
                    r.get("external_shipment_ref", String.class),
                    r.get("source", String.class),
                    r.get("reported_at", OffsetDateTime.class),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  // ==================================================================
  // 仓端回执（接收 / 拒收）
  // ==================================================================

  /**
   * 登记仓端回执。
   *
   * <p>🔴 <b>拒收是真实业务结果</b>：规格不符被拒收会造成额外成本与延误，
   * 这条链路必须能落库并可查，所以接收与拒收两条回执都落同一张 append-only 表。
   */
  public UUID registerReceipt(UUID forecastId, CreateReceiptRequest request) {
    String status = statusOf(forecastId);
    if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");
    if (!ForecastStatus.SHIPMENT_CREATED.name().equals(status))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "只有货件已创建的预报单才能登记仓端回执（当前 " + status + "）");

    ReceiptType type;
    try {
      type = ReceiptType.valueOf(request.receiptType().trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回执类型不合法：" + request.receiptType());
    }
    if (type == ReceiptType.REJECTED
        && (request.rejectedReason() == null || request.rejectedReason().isBlank()))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "拒收回执必须写明拒收理由");

    String source =
        request.source() == null || request.source().isBlank()
            ? "MANUAL_REGISTRATION"
            : request.source().trim().toUpperCase(Locale.ROOT);
    if (!source.equals("WAREHOUSE_FEEDBACK") && !source.equals("MANUAL_REGISTRATION"))
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回执来源不合法：" + request.source());

    UUID id = Ids.next();
    sql.execute(
        "insert into warehouse_forecast_receipt(id,tenant_id,forecast_id,receipt_type,receipt_ref,"
            + " detail,rejected_reason,received_at,source) values "
            + "(?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?,?::timestamptz,?)",
        id,
        forecastId,
        type.name(),
        request.receiptRef() == null ? "" : request.receiptRef().trim(),
        request.detail() == null ? "" : request.detail(),
        request.rejectedReason() == null ? "" : request.rejectedReason(),
        request.receivedAt().toString(),
        source);

    String next = type == ReceiptType.ACCEPTED ? "ACCEPTED" : "REJECTED";
    sql.execute(
        "update warehouse_forecast set status=?,closed_at=now(),updated_at=now(),version=version+1"
            + " where id=? and status='SHIPMENT_CREATED'",
        next,
        forecastId);
    return id;
  }

  /** 仓端回执列表（append-only，历史全留）。 */
  public List<ForecastReceiptRow> receipts(UUID forecastId) {
    return sql
        .fetch(
            "select id,receipt_type,receipt_ref,detail,rejected_reason,received_at,source,created_at"
                + " from warehouse_forecast_receipt where forecast_id=? order by received_at desc,id",
            forecastId)
        .stream()
        .map(
            r ->
                new ForecastReceiptRow(
                    r.get("id", UUID.class),
                    r.get("receipt_type", String.class),
                    nullSafe(r.get("receipt_ref", String.class)),
                    nullSafe(r.get("detail", String.class)),
                    nullSafe(r.get("rejected_reason", String.class)),
                    r.get("received_at", OffsetDateTime.class),
                    r.get("source", String.class),
                    r.get("created_at", OffsetDateTime.class)))
        .toList();
  }

  // ==================================================================
  // 作废
  // ==================================================================

  /** 作废预报单（草稿/已校验/已提交都可以作废；已收/已拒是终态）。 */
  public void cancel(UUID forecastId, String reason) {
    String status = statusOf(forecastId);
    if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");
    ForecastStatus current = ForecastStatus.valueOf(status);
    if (!current.canTransitionTo(ForecastStatus.CANCELLED))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "预报单当前状态不可作废（" + status + "）");
    sql.execute(
        "update warehouse_forecast set status='CANCELLED',closed_at=now(),updated_at=now(),"
            + " last_error=?,version=version+1 where id=?",
        reason == null ? "" : reason,
        forecastId);
    sql.execute(
        "update warehouse_shipment_task set status='CANCELLED',updated_at=now()"
            + " where forecast_id=? and status in ('PENDING','FAILED')",
        forecastId);
  }

  // ==================================================================
  // 备货可用性（库存 + 在途，只读 Port）
  // ==================================================================

  /**
   * 备货可用性：自营可售量 + 头程在途量。
   *
   * <p>🔴 两个口径都经 warehouse 的**授权只读接口**读（AGENTS.md 禁止跨服务 SQL / 跨库视图）。
   * 端口在上游不可达时抛 503，**绝不**返回 0 冒充 —— 0 会被读成「没有库存需求」，
   * 那正是会真的少备一批货的路径。
   *
   * <p>口径边界如实标注：在途腿的目的仓编码与海外仓账户编码不是同一套，
   * 所以这里只能给<b>按 SKU 汇总</b>的头程在途量，不声称「其中多少是去这个目的仓的」。
   */
  public ForecastAvailabilityPage availability(UUID forecastId, int page, int size, String bearer) {
    if (statusOf(forecastId) == null)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "预报单不存在");

    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    // 同一 SKU 可能出现在多行：汇总请求量，其余按 SKU 逐个读上游
    Map<String, BigDecimal> requested = new LinkedHashMap<>();
    Map<String, String> units = new LinkedHashMap<>();
    for (ForecastLineRow line : lines(forecastId)) {
      requested.merge(line.skuCode(), line.quantity(), BigDecimal::add);
      units.putIfAbsent(line.skuCode(), line.unit());
    }

    List<String> skuCodes = new ArrayList<>(requested.keySet());
    long total = skuCodes.size();
    int from = Math.min(p * s, skuCodes.size());
    int to = Math.min(from + s, skuCodes.size());
    List<String> pageSkus = skuCodes.subList(from, to);

    List<ForecastAvailabilityLine> rows = new ArrayList<>();
    for (String skuCode : pageSkus) {
      ForecastInventoryQueryPort.InventoryAvailability inv = inventoryPort.lookup(skuCode, bearer);
      ForecastInTransitQueryPort.InTransitAvailability transit =
          inTransitPort.lookup(skuCode, bearer);
      BigDecimal need = requested.get(skuCode);
      Boolean coverable =
          inv.sellableKnown() ? inv.sellable().compareTo(need) >= 0 : null;
      rows.add(
          new ForecastAvailabilityLine(
              skuCode,
              need,
              units.get(skuCode),
              inv.sellable(),
              inv.sellableKnown(),
              transit.inTransitQuantity(),
              transit.inTransitKnown(),
              coverable,
              inv.sellableKnown()
                  ? "可售量口径来自 07 库存（warehouse 授权只读接口）"
                  : inv.note()));
    }

    return new ForecastAvailabilityPage(
        rows,
        p,
        s,
        total,
        "在途口径按 SKU 汇总头程在途腿：仓端账户编码与 warehouse 的仓编码不是同一套，"
            + "本模块不声称分目的仓的可售/在途拆分");
  }

  // ==================================================================
  // 能力声明
  // ==================================================================

  /**
   * 能力声明：哪些能力已接通、哪些还没有及归属模块。
   *
   * <p>前端据此禁用不可用的动作并在界面上讲清原因，而不是让用户填完再收一个 422。
   */
  public ForecastCapabilities capabilities() {
    return new ForecastCapabilities(
        List.of(
            new ForecastCapabilityDimension(
                "specMaintenance", "备货规格维护", true, "10.4.2.2", "箱型/贴标/托盘/混装四组限制可维护"),
            new ForecastCapabilityDimension(
                "specGate", "提交前规格闸", true, "10.4.2.2", "规格不过不能提交，逐条点名违反项"),
            new ForecastCapabilityDimension(
                "shipmentTask", "平台货件创建任务", true, "10.4.2.2",
                "任务可推进/可查/可重试；平台侧无网络出口，货件编号靠登记带回"),
            new ForecastCapabilityDimension(
                "warehouseReceipt", "仓端接收/拒收回执", true, "10.4.2.2", "两条回执都落库并可查"),
            new ForecastCapabilityDimension(
                "inventoryAvailability", "自营可售量", true, "10.4.2.2",
                "经 warehouse 授权只读接口读（7.2.2.1 口径）"),
            new ForecastCapabilityDimension(
                "inTransitAvailability", "头程在途量", true, "10.4.2.2",
                "经 warehouse 授权只读接口读（7.5.1.2 口径），按 SKU 汇总，不分目的仓"),
            new ForecastCapabilityDimension(
                "platformShipmentApi", "平台货件接口直连", false, "10.4.2.2",
                "本仓库没有真实平台账号与网络出口，改用租户/通道登记的报送行承接外部边界"),
            new ForecastCapabilityDimension(
                "replenishmentSuggestionFeed", "补货建议自动带入", false, "6.2.1.2",
                "6.2.1.2 已交付但尚未提供预报直连入口，本模块只登记来源标记"),
            new ForecastCapabilityDimension(
                "perDestinationTransit", "按目的仓的在途量", false, "10.4.1.2",
                "在途腿的目的仓编码与海外仓账户编码不是同一套，本模块只能给 SKU 汇总口径")),
        List.of("CARTON_SMALL", "CARTON_STANDARD", "CARTON_LARGE", "PALLET"),
        List.of("FNSKU", "FBA_LABEL", "GS1", "PLAIN"),
        List.of(
            ForecastSource.REPLENISHMENT_SUGGESTION.name(),
            ForecastSource.FBA_SHIPMENT_PLAN.name(),
            ForecastSource.MANUAL.name()),
        "平台仓备货规格（箱型/贴标/托盘/混装）是提交前的硬闸：规格不符会被仓端拒收，"
            + "造成额外成本与延误。");
  }

  // ==================================================================
  // 工具
  // ==================================================================

  private long count(String text, Object... binds) {
    return sql.fetchOne(text, binds).get(0, Long.class);
  }

  private String statusOf(UUID forecastId) {
    Record r = sql.fetchOne("select status from warehouse_forecast where id=?", forecastId);
    return r == null ? null : r.get("status", String.class);
  }

  private String providerCodeOf(UUID accountId) {
    Record r =
        sql.fetchOne(
            "select p.code from warehouse_account a"
                + " join warehouse_connection c on c.tenant_id=a.tenant_id and c.id=a.connection_id"
                + " join warehouse_provider p on p.id=c.provider_id where a.id=?",
            accountId);
    return r == null ? "" : nullSafe(r.get("code", String.class));
  }

  private OverseasWarehouseAdapter.ConnectionSettings connectionSettings(UUID accountId) {
    Record r =
        sql.fetchOne(
            "select a.id account_id,a.external_account_code,a.connection_id,c.authorized_account,"
                + " c.api_endpoint_ref,cr.external_ref credential_ref"
                + " from warehouse_account a"
                + " join warehouse_connection c on c.tenant_id=a.tenant_id and c.id=a.connection_id"
                + " left join credential_ref cr on cr.tenant_id=c.tenant_id and cr.id=c.credential_id"
                + " where a.id=?",
            accountId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目的仓不存在");
    return new OverseasWarehouseAdapter.ConnectionSettings(
        r.get("connection_id", UUID.class),
        r.get("account_id", UUID.class),
        nullSafe(r.get("authorized_account", String.class)),
        nullSafe(r.get("external_account_code", String.class)),
        nullSafe(r.get("credential_ref", String.class)),
        nullSafe(r.get("api_endpoint_ref", String.class)));
  }

  private static String nullSafe(String v) {
    return v == null ? "" : v;
  }

  private static List<String> textArray(String[] values) {
    if (values == null) return List.of();
    return List.of(values);
  }

  private static BigDecimal normalizeQuantity(BigDecimal q) {
    return q.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  /** 规格违规异常：带结构化违规清单，交给 {@code StockForecastErrorHandler} 渲染成 422。 */
  public static class SpecValidationException extends RuntimeException {
    private final List<SpecViolation> violations;

    public SpecValidationException(List<SpecViolation> violations) {
      super("规格校验未通过：" + violations.size() + " 条违规");
      this.violations = List.copyOf(violations);
    }

    public List<SpecViolation> violations() {
      return violations;
    }
  }
}
