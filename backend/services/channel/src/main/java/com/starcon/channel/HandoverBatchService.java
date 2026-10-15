package com.starcon.channel;

import static com.starcon.channel.HandoverBatchModels.*;

import com.starcon.channel.carrier.CarrierHandoverAdapter;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.3.2.1 交运批次。
 *
 * <p>蓝图：「把已发货包裹按承运商与交运时间组批，交接给承运商并登记交接清单，
 * 减少交接差错」。
 *
 * <p>🔴 本类唯一不能妥协的约束是加粗那句：**批次交接是责任分界点 ——
 * 批次内包裹的丢失责任在承运商，需要交接清单留证**。它在本类里有三处落点：
 *
 * <ol>
 *   <li>{@code handover_manifest_item} 逐件登记（交接时刻 / 包裹标识 / 运单号 /
 *       重量尺寸 / 经手人），且在 DB 层 append-only（V129 双保险）；
 *   <li>一条批次<b>不许跨承运商</b>：组批的候选必须与批次同一承运商，
 *       DB 触发器 {@code guard_handover_candidate_carrier} 在写入路径上钉死 ——
 *       混批会让「丢失责任在承运商」的分界直接失效；
 *   <li>批次头计数<b>不接受调用方直接指定</b>：由逐件清单聚合，DB 侧
 *       {@code guard_handover_batch_counts} 重算比对（照抄 9.4.1.1）。
 * </ol>
 *
 * <p>数据来源纪律：候选池<b>只</b>经 {@link PendingShipmentQueryPort} 读上游
 * 9.4.1.1 的授权 HTTP API 落成本服务投影表，本类<b>一条 SQL 都不碰 trade 的库</b>
 * （AGENTS.md 禁止跨服务 SQL / FK / 共享视图）。
 */
@Service
public class HandoverBatchService {
  /** 数量统一 HALF_UP 到 6 位小数，与 DB 的 numeric(19,6) 对齐（禁浮点）。 */
  private static final int QUANTITY_SCALE = 6;

  private final DSLContext sql;
  private final PendingShipmentQueryPort upstream;
  private final Map<String, CarrierHandoverAdapter> adapters;

  public HandoverBatchService(
      DSLContext sql,
      PendingShipmentQueryPort upstream,
      List<CarrierHandoverAdapter> adapterList) {
    this.sql = sql;
    this.upstream = upstream;
    // 没有 vendor 实现时这里是空表 —— 「未接通」如实表达为查不到适配器，
    // 而不是塞一个假的进去（见 CarrierHandoverAdapter 的文件头）。
    this.adapters =
        adapterList.stream()
            .collect(Collectors.toMap(CarrierHandoverAdapter::carrierCode, Function.identity()));
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private static String text(String value) {
    return value == null ? "" : value.trim();
  }

  private static BigDecimal money(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }

  private ResponseStatusException notFound() {
    // 跨租户与不存在返回**同一个 404 且理由逐字相同**：
    // 不给「拿别的租户的 id 探测存在性」留信息面。
    return new ResponseStatusException(HttpStatus.NOT_FOUND, "交运批次不存在");
  }

  // ==================================================================
  // 能力声明：未交付的段显式 available=false + ownerModule + 影响
  // ==================================================================

  public HandoverCapabilities capabilities() {
    List<HandoverCapabilityDimension> dimensions =
        List.of(
            new HandoverCapabilityDimension(
                "carrier_grouping",
                "按承运商组批，一条批次不许跨承运商",
                true,
                "本服务 V129 handover_batch.carrier_code + DB 触发器",
                "",
                "混批会让交接现场按承运商分拣时漏发，且丢失责任分界失效"),
            new HandoverCapabilityDimension(
                "handover_time_bucketing",
                "按交运时间分档（handover_bucket）",
                true,
                "本服务 V129 handover_batch.handover_bucket",
                "",
                "交运时间是现场排班依据，缺了看不出哪一批该几点交"),
            new HandoverCapabilityDimension(
                "handover_manifest",
                "交接清单逐件留证（交接时刻/包裹标识/运单号/重量尺寸/经手人），append-only",
                true,
                "本服务 V129 handover_manifest_item + REVOKE 与触发器双保险",
                "",
                "批次交接后包裹丢失责任在承运商，清单是唯一凭据"),
            new HandoverCapabilityDimension(
                "waybill_number",
                "运单号",
                false,
                "上游 9.4.1.1 shipment_waybill；10.2.1.2 运单申请未交付",
                "10.2.1.2",
                "运单号可能尚无来源，清单里如实留空并写明原因；绝不出库单号冒充运单号"),
            new HandoverCapabilityDimension(
                "carrier_dispatch",
                "交接给承运商（系统对接那一跳）",
                false,
                "无承运商连接；适配器端口已就位但无 vendor 实现",
                "10.1.1.1",
                "批次停在 DISPATCH_PENDING 如实表达「等待通道接通」，不假装交接成功"),
            new HandoverCapabilityDimension(
                "carrier_receipt",
                "承运商回执（系统取回）",
                false,
                "无承运商连接；回执改由人工登记带回",
                "10.1.1.1",
                "回执有主体、有时刻、有来源才算凭据；系统取回能力缺失不影响凭据成立"),
            new HandoverCapabilityDimension(
                "carrier_rate_time",
                "渠道时效与限制（预计到达、截单时间）",
                false,
                "10.1.1.3 时效与限制未开发",
                "10.1.1.3",
                "无法提示某批次是否会错过截单，只能靠现场经验"),
            new HandoverCapabilityDimension(
                "pickup_confirmation",
                "揽收确认（承运商已收走）",
                false,
                "10.3.2.2 揽收与签收确认未开发",
                "10.3.2.2",
                "批次记为已交接不等于已揽收，平台履约指标仍需人工盯"),
            new HandoverCapabilityDimension(
                "tracking_timeline",
                "包裹轨迹时间线",
                false,
                "10.3.1.1 轨迹查询未开发",
                "10.3.1.1",
                "交接后无法看到包裹在途位置，异常发现依赖承运商主动通知"),
            new HandoverCapabilityDimension(
                "exception_handling",
                "异常件识别与处置",
                false,
                "10.3.1.2 异常件未开发",
                "10.3.1.2",
                "清单里只能留现场备注，轨迹停滞/妥投失败等异常无人负责到底"));
    return new HandoverCapabilities(
        dimensions,
        List.of("DRAFT", "DISPATCH_PENDING", "HANDED_OVER", "CANCELLED"),
        List.of("PLATFORM_INTEGRATED", "THIRD_PARTY", "SELF_PICKUP", "POSTAL", "FREIGHT_FORWARDER"),
        List.of("UPSTREAM_NOT_ISSUED", "UPSTREAM_UNREADABLE", "MANUAL_OVERRIDE"),
        List.of("ACCEPTED", "ACCEPTED_WITH_DISCREPANCY", "REJECTED"),
        List.of("DRAFT", "DISPATCH", "RECEIPT", "CANCEL"),
        "承运商来自上游 9.4.1.1 单元读数（carrier_code），本服务不维护承运商目录 —— "
            + "承运商主数据属 10.1.1.1，该模块未交付。",
        "候选池只读上游 9.4.1.1 的 HANDED_OVER 单元（V124），经其授权 HTTP API 取数，"
            + "一条跨服务 SQL 都没有；租户由调用方 bearer 在上游侧解析，隔离是两侧 RLS 两道。",
        "本模块**不产生**已发货与已揽收：批次交接只是责任分界点，"
            + "承运商揽收确认属 10.3.2.2，轨迹去重属 10.3.1.1。");
  }

  // ==================================================================
  // 候选池：同步（唯一写入口）
  // ==================================================================

  /**
   * 从上游 9.4.1.1 拉 HANDED_OVER 单元进候选池。
   *
   * <p>写入路径<b>只有一个</b>：本方法。所以候选池是「只读投影」而不是事实表 ——
   * 事实仍以 trade 的待发货单元为准，每一行都能追溯回 {@code source_unit_id}。
   *
   * <p>重跑是幂等的：按 {@code (tenant_id, source_unit_id)} 唯一，已存在的更新读数，
   * 不新增重复候选（重跑同步绝不制造「同一票货交两次」的假象）。
   */
  public SyncCandidatesResult syncCandidates(
      SyncCandidatesRequest request, String bearer) {
    String carrierCode = text(request.carrierCode());
    String carrierChannel = text(request.carrierChannel());
    UUID warehouseId = request.warehouseId();
    int page = Math.max(request.page(), 0);
    int size = request.size() <= 0 ? 50 : Math.min(request.size(), 200);

    // 先在候选池里回读本租户的租户上下文（RLS 已绑定），本方法只在它的
    // 事务里跑，所以这里不需要也不应该自己再绑一次租户。
    var fetched = upstream.handedOverPage(warehouseId, carrierCode, carrierChannel, page, size, bearer);

    int created = 0;
    int updated = 0;
    int seen = 0;
    int skippedNoCarrier = 0;
    OffsetDateTime syncedAt = OffsetDateTime.now(ZoneOffset.UTC);
    UUID tenant = currentTenant();

    for (PendingShipmentQueryPort.PendingUnitView unit : fetched.items()) {
      seen++;
      // 上游那一票还没有承运商 ⇒ 交运不出去（没有交运对象）。
      // 如实跳过并计数，不拿空承运商造一个候选出来。
      if (text(unit.carrierCode()).isEmpty()) {
        skippedNoCarrier++;
        continue;
      }
      // 逐件读详情：交接清单要逐件重量尺寸，还要读运单号来源（有没有单号）。
      var detail = upstream.detail(unit.id(), bearer);
      var d = detail.unit();
      String waybillNo = firstIssuedWaybill(detail);
      String gapCode = waybillNo.isEmpty() ? "UPSTREAM_NOT_ISSUED" : "";
      String gapReason =
          waybillNo.isEmpty()
              ? "上游 9.4.1.1 尚未签发运单号（10.2.1.2 运单申请未交付）"
              : "";

      // 重跑幂等：先看这一票此前在不在候选池里、是什么状态。
      // 已进批次的候选**不降级回 PENDING** —— 一次重跑同步绝不能把凭据悄悄撤掉。
      var prior =
          sql.fetchOne(
              "select status from handover_candidate where tenant_id=? and source_unit_id=?",
              tenant, d.id());
      boolean existed = prior != null;

      sql.execute(
          "insert into handover_candidate (id,tenant_id,source_unit_id,source_packing_order_id,"
              + " packed_at,handed_over_at,parcel_count,total_weight_kg,warehouse_ref,outbound_order_no,"
              + " sales_order_no,destination_country,carrier_code,carrier_channel,waybill_no,"
              + " waybill_gap_reason,waybill_gap_code,status,batch_id,synced_at)"
              + " values (?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?,?,?,?,?,?, 'PENDING', NULL,"
              + " ?::timestamptz)"
              + " on conflict (tenant_id,source_unit_id) do update set"
              + " packed_at = excluded.packed_at, handed_over_at = excluded.handed_over_at,"
              + " parcel_count = excluded.parcel_count, total_weight_kg = excluded.total_weight_kg,"
              + " warehouse_ref = excluded.warehouse_ref, outbound_order_no = excluded.outbound_order_no,"
              + " sales_order_no = excluded.sales_order_no,"
              + " destination_country = excluded.destination_country,"
              + " carrier_code = excluded.carrier_code, carrier_channel = excluded.carrier_channel,"
              + " waybill_no = excluded.waybill_no, waybill_gap_reason = excluded.waybill_gap_reason,"
              + " waybill_gap_code = excluded.waybill_gap_code,"
              + " synced_at = excluded.synced_at, updated_at = excluded.synced_at",
          Ids.next(),
          tenant,
          d.id(),
          d.sourcePackingOrderId(),
          d.packedAt(),
          d.handedOverAt(),
          Math.max(d.parcelCount(), 1),
          money(d.totalWeightKg()),
          d.warehouseRef(),
          d.outboundOrderNo(),
          d.salesOrderNo(),
          d.destinationCountry(),
          text(d.carrierCode()),
          d.carrierChannel(),
          waybillNo,
          gapReason,
          gapCode,
          syncedAt);
      if (existed) {
        updated++;
      } else {
        created++;
      }
    }

    return new SyncCandidatesResult(
        seen,
        created,
        updated,
        carrierCode,
        syncedAt,
        "只取上游 HANDED_OVER 单元；其中 " + skippedNoCarrier
            + " 条没有承运商已如实跳过（没有交运对象就不该进候选池）。");
  }

  /** 上游签发过的运单号才算数：REQUESTED 不算（只有一张「已申请」的勾等于没申请）。 */
  private String firstIssuedWaybill(PendingShipmentQueryPort.PendingUnitDetail detail) {
    for (var w : detail.waybills() == null ? List.<PendingShipmentQueryPort.PendingWaybillView>of() : detail.waybills()) {
      if ("ISSUED".equals(w.status()) && !text(w.waybillNo()).isEmpty()) {
        return text(w.waybillNo());
      }
    }
    // 详情里没有单号但列表行带了单号时也认（两边读数可能来自不同投影时点）
    for (var w : detail.waybills() == null ? List.<PendingShipmentQueryPort.PendingWaybillView>of() : detail.waybills()) {
      if (!text(w.waybillNo()).isEmpty()) {
        return text(w.waybillNo());
      }
    }
    return "";
  }

  /** 当前事务内的租户（由 backend/libraries/tenancy 在进入事务时绑定）。 */
  private UUID currentTenant() {
    String raw = one(String.class, "select nullif(current_setting('app.current_tenant',true),'')");
    if (raw == null || raw.isBlank()) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少租户上下文");
    }
    return UUID.fromString(raw);
  }

  // ==================================================================
  // 候选池读：分页 + 筛选 + 真聚合汇总
  // ==================================================================

  public HandoverCandidatePage candidates(
      String warehouseRef, String carrierCode, String status, String sort, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);
    String orderBy = candidateOrderBy(sort);

    List<Object> filters = new ArrayList<>();
    StringBuilder where = new StringBuilder(" where 1=1");
    // 仓库按编号筛选：上游单元带的是 warehouse_ref 字符串，
    // 本服务不维护 warehouse uuid 字典（那是 warehouse 服务的主权，跨服务不建引用）。
    String ref = text(warehouseRef);
    if (!ref.isEmpty()) {
      where.append(" and warehouse_ref = ?");
      filters.add(ref);
    }
    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and carrier_code = ?");
      filters.add(carrier);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and status = ?");
      filters.add(st);
    }

    long total =
        sql.fetchOne("select count(*) from handover_candidate" + where, filters.toArray())
            .get(0, Long.class);

    List<Object> binds = new ArrayList<>(filters);
    binds.add(s);
    binds.add(p * s);
    var rows =
        sql.fetch(
            "select id,source_unit_id,source_packing_order_id,warehouse_ref,outbound_order_no,"
                + " sales_order_no,destination_country,carrier_code,carrier_channel,waybill_no,"
                + " waybill_gap_reason,waybill_gap_code,parcel_count,total_weight_kg,status,batch_id,"
                + " packed_at,handed_over_at,synced_at from handover_candidate"
                + where
                + " order by "
                + orderBy
                + " limit ? offset ?",
            binds.toArray());

    return new HandoverCandidatePage(
        rows.stream().map(HandoverBatchService::candidateOf).toList(),
        p, s, total, (int) Math.ceil(total / (double) s), candidateSummary());
  }

  /**
   * 候选池汇总：全部真聚合。
   *
   * <p>按蓝图输入句的两个维度分组（承运商 × 交运日期档）。交运日期档取
   * {@code handed_over_at} 的 UTC 日期 —— 它是「这一票什么时候从待发货交出来的」，
   * 是分拣现场真正看到的分组维度。
   *
   * <p>刻意<b>不</b>拉全量在 Java 里 filter：那样汇总会与分页耦合
   * （只看得到当前页的数），分拣现场会照着一个偏小的数安排车辆。
   */
  public HandoverCandidateSummary candidateSummary() {
    var totals =
        sql.fetchOne(
            "select count(*) filter (where status='PENDING') as pending,"
                + " count(*) filter (where status='BATCHED') as batched,"
                + " coalesce(sum(parcel_count) filter (where status='PENDING'),0) as pending_parcels,"
                + " coalesce(sum(total_weight_kg) filter (where status='PENDING'),0) as pending_weight,"
                + " count(*) filter (where status='PENDING' and waybill_no='') as missing_waybill"
                + " from handover_candidate");
    var byCarrier =
        sql.fetch(
            "select carrier_code, carrier_channel,"
                + " (handed_over_at at time zone 'UTC')::date as bucket,"
                + " count(*) as unit_count, sum(parcel_count) as parcel_count,"
                + " sum(total_weight_kg) as weight,"
                + " count(*) filter (where waybill_no='') as missing_waybill"
                + " from handover_candidate where status='PENDING'"
                + " group by carrier_code, carrier_channel, bucket"
                + " order by carrier_code, bucket");
    var bucketOptions =
        sql.fetch(
            "select distinct (handed_over_at at time zone 'UTC')::date as bucket"
                + " from handover_candidate order by bucket desc");

    return new HandoverCandidateSummary(
        totals.get("pending", Long.class),
        totals.get("batched", Long.class),
        totals.get("pending_parcels", Integer.class),
        money(totals.get("pending_weight", BigDecimal.class)),
        totals.get("missing_waybill", Long.class),
        byCarrier.stream().map(HandoverBatchService::bucketOf).toList(),
        bucketOptions.stream().map(r -> r.get("bucket", LocalDate.class).toString()).toList());
  }

  private static HandoverGroupBucket bucketOf(Record r) {
    LocalDate bucket = r.get("bucket", LocalDate.class);
    return new HandoverGroupBucket(
        r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class),
        bucket,
        bucket.toString(),
        r.get("unit_count", Long.class),
        r.get("parcel_count", Integer.class),
        money(r.get("weight", BigDecimal.class)),
        r.get("missing_waybill", Long.class));
  }

  private static HandoverCandidateRow candidateOf(Record r) {
    return new HandoverCandidateRow(
        r.get("id", UUID.class),
        r.get("source_unit_id", UUID.class),
        r.get("source_packing_order_id", UUID.class),
        r.get("warehouse_ref", String.class),
        r.get("outbound_order_no", String.class),
        r.get("sales_order_no", String.class),
        r.get("destination_country", String.class),
        r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class),
        r.get("waybill_no", String.class),
        r.get("waybill_gap_reason", String.class),
        r.get("waybill_gap_code", String.class),
        r.get("parcel_count", Integer.class),
        money(r.get("total_weight_kg", BigDecimal.class)),
        r.get("status", String.class),
        r.get("batch_id", UUID.class),
        r.get("packed_at", OffsetDateTime.class),
        r.get("handed_over_at", OffsetDateTime.class),
        r.get("synced_at", OffsetDateTime.class));
  }

  /** 排序白名单：值不进 SQL 拼接。 */
  private String candidateOrderBy(String sort) {
    String s = sort == null ? "HANDED_OVER_AT_DESC" : sort.toUpperCase(Locale.ROOT);
    return switch (s) {
      case "HANDED_OVER_AT_ASC" -> "handed_over_at asc";
      case "HANDED_OVER_AT_DESC" -> "handed_over_at desc";
      case "PACKED_AT_ASC" -> "packed_at asc";
      case "PACKED_AT_DESC" -> "packed_at desc";
      case "WEIGHT_DESC" -> "total_weight_kg desc";
      default -> "handed_over_at desc";
    };
  }

  // ==================================================================
  // 组批：承运商 + 交运时间，真 GROUP BY
  // ==================================================================

  /**
   * 组批预览：按<b>承运商 + 交运日期档</b>真聚合，告诉你每个分桶会组出几件、几票、多重。
   *
   * <p>这是「组批规则要有真聚合」在读侧落点：SQL 里 GROUP BY 出结果，
   * <b>不</b>把候选全拉进 Java 再 filter。
   */
  public List<HandoverGroupBucket> groupPreview(String carrierCode, LocalDate bucket) {
    StringBuilder where = new StringBuilder(" where status='PENDING'");
    List<Object> binds = new ArrayList<>();
    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and carrier_code = ?");
      binds.add(carrier);
    }
    if (bucket != null) {
      where.append(" and (handed_over_at at time zone 'UTC')::date = ?");
      binds.add(bucket);
    }
    return sql.fetch(
            "select carrier_code, carrier_channel,"
                + " (handed_over_at at time zone 'UTC')::date as bucket,"
                + " count(*) as unit_count, sum(parcel_count) as parcel_count,"
                + " sum(total_weight_kg) as weight,"
                + " count(*) filter (where waybill_no='') as missing_waybill"
                + " from handover_candidate"
                + where
                + " group by carrier_code, carrier_channel, bucket"
                + " order by carrier_code, bucket",
            binds.toArray())
        .stream()
        .map(HandoverBatchService::bucketOf)
        .toList();
  }

  /**
   * 组批并登记交接清单。
   *
   * <p>顺序刻意是「先建批次（计数 0）→ 写清单 → 回填计数」，因为 DB 侧的
   * {@code guard_handover_batch_counts} 在每次写批次头时都会重算一遍逐件清单：
   * 建批瞬间还没有清单，所以计数必须是 0；清单写完后回填真实计数。
   * 这样「调用方直接把计数写成好看的数字」在 DB 层就过不去。
   *
   * <p>承运商一致性由 DB 触发器兜底，但本方法**也**先自己查一遍给出可读的错误 ——
   * 服务层报错说清是哪一件、哪个承运商，比让用户读触发器的 PG 错误强。
   */
  public HandoverBatchDetail createBatch(
      CreateBatchRequest request, UUID membershipId) {
    String carrierCode = text(request.carrierCode());
    if (carrierCode.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "承运商必填：一条交运批次不许跨承运商");
    }
    OffsetDateTime handoverAt = request.handoverAt();
    if (handoverAt == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "交运时间必填");
    }
    List<ManifestItemInput> items =
        request.items() == null ? List.of() : request.items();
    if (items.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "交接清单不能为空：没有逐件清单就没有留证");
    }

    String carrierChannel =
        one(
            String.class,
            "select distinct carrier_channel from handover_candidate where carrier_code=?",
            carrierCode);
    if (carrierChannel == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "候选池里没有该承运商的包裹：" + carrierCode);
    }

    // 逐件前置校验：承运商一致 + 候选仍是 PENDING（已被别的批次组走的不许重复组批）
    for (ManifestItemInput item : items) {
      var row =
          sql.fetchOne(
              "select carrier_code, status, waybill_no, waybill_gap_reason, waybill_gap_code,"
                  + " warehouse_ref, outbound_order_no"
                  + " from handover_candidate where id=?",
              item.candidateId());
      if (row == null) {
        throw new ResponseStatusException(
            HttpStatus.NOT_FOUND, "候选包裹不存在：" + item.candidateId());
      }
      String itemCarrier = row.get("carrier_code", String.class);
      if (!carrierCode.equals(itemCarrier)) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "一条交运批次不许跨承运商：批次是 " + carrierCode + "，而件 "
                + item.candidateId() + " 是 " + itemCarrier);
      }
      if (!"PENDING".equals(row.get("status", String.class))) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT, "件 " + item.candidateId() + " 已在别的交运批次里");
      }
    }

    UUID batchId = Ids.next();
    String batchNo = nextBatchNo(handoverAt);
    LocalDate bucket = handoverAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();

    sql.execute(
        "insert into handover_batch (id,tenant_id,batch_no,carrier_code,carrier_channel,"
            + " handover_at,handover_bucket,unit_count,parcel_count,total_weight_kg,status,"
            + " status_detail,version) values (?,?,?,?,?,?::timestamptz,?,0,0,0,'DRAFT','',0)",
        batchId, currentTenant(), batchNo, carrierCode, carrierChannel, handoverAt, bucket);

    // 逐件登记交接清单（append-only，一旦写入不可改）
    for (ManifestItemInput item : items) {
      String waybillNo = text(item.waybillNo());
      String gapCode = text(item.waybillGapCode());
      String gapReason = text(item.waybillGapReason());
      if (waybillNo.isEmpty() && gapReason.isEmpty()) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "运单号为空时必须写明原因（10.2.1.2 未交付时如实留空，不拿出库单号冒充）");
      }
      sql.execute(
          "insert into handover_manifest_item (id,tenant_id,batch_id,candidate_id,parcel_no,"
              + " parcel_ref,handover_at,handed_by_membership_id,waybill_no,waybill_gap_reason,"
              + " waybill_gap_code,gross_weight_kg,length_mm,width_mm,height_mm,remark)"
              + " values (?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?)",
          Ids.next(), currentTenant(), batchId, item.candidateId(), item.parcelNo(),
          text(item.parcelRef()), handoverAt, membershipId,
          waybillNo, gapReason, gapCode,
          money(item.grossWeightKg()), money(item.lengthMm()),
          money(item.widthMm()), money(item.heightMm()), text(item.remark()));
    }

    // 回填计数：由逐件清单聚合（DB 触发器会再算一遍比对）
    sql.execute(
        "update handover_batch set unit_count = (select count(distinct candidate_id) from"
            + " handover_manifest_item where tenant_id=handover_batch.tenant_id and"
            + " batch_id=handover_batch.id),"
            + " parcel_count = (select count(*) from handover_manifest_item"
            + " where tenant_id=handover_batch.tenant_id and batch_id=handover_batch.id),"
            + " total_weight_kg = (select coalesce(sum(gross_weight_kg),0) from"
            + " handover_manifest_item where tenant_id=handover_batch.tenant_id and"
            + " batch_id=handover_batch.id), version = version + 1, updated_at = now()"
            + " where id=?",
        batchId);

    // 候选进批次（DB 触发器复查承运商一致性 + 批次存在性）
    for (ManifestItemInput item : items) {
      sql.execute(
          "update handover_candidate set status='BATCHED', batch_id=?, updated_at=now()"
              + " where id=?",
          batchId, item.candidateId());
    }

    sql.execute(
        "insert into handover_event (id,tenant_id,batch_id,event_kind,from_status,to_status,detail,"
            + " actor_membership_id) values (?,?,?,'BATCH_CREATED','','DRAFT',?,?)",
        Ids.next(), currentTenant(), batchId,
        "按承运商 " + carrierCode + " 与交运时间 " + handoverAt + " 组批，逐件清单 "
            + items.size() + " 件",
        membershipId);

    return detail(batchId);
  }

  private String nextBatchNo(OffsetDateTime handoverAt) {
    Long n = sql.fetchOne("select nextval('handover_batch_no_seq')").get(0, Long.class);
    return String.format(
        Locale.ROOT, "HNB-%s%06d",
        handoverAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate().toString().replace("-", ""), n);
  }

  // ==================================================================
  // 批次读
  // ==================================================================

  public HandoverBatchPage batches(      String carrierCode, String status, LocalDate bucket, String sort, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    StringBuilder where = new StringBuilder(" where 1=1");
    List<Object> binds = new ArrayList<>();
    String carrier = text(carrierCode);
    if (!carrier.isEmpty()) {
      where.append(" and carrier_code = ?");
      binds.add(carrier);
    }
    String st = text(status);
    if (!st.isEmpty()) {
      where.append(" and status = ?");
      binds.add(st);
    }
    if (bucket != null) {
      where.append(" and handover_bucket = ?");
      binds.add(bucket);
    }

    long total =
        sql.fetchOne("select count(*) from handover_batch" + where, binds.toArray())
            .get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    var rows =
        sql.fetch(
            BATCH_SELECT + where + " order by " + batchOrderBy(sort) + " limit ? offset ?",
            pageBinds.toArray());

    return new HandoverBatchPage(
        rows.stream().map(r -> batchOf(r)).toList(),
        p, s, total, (int) Math.ceil(total / (double) s), batchSummary());
  }

  private static final String BATCH_SELECT =
      "select id,batch_no,carrier_code,carrier_channel,handover_at,handover_bucket,unit_count,"
          + " parcel_count,total_weight_kg,status,status_detail,dispatched_by_membership_id,"
          + " dispatched_at,receipt_ref,receipt_status,receipt_detail,receipt_recorded_at,"
          + " version,created_at,"
          + " (select count(*) from handover_manifest_item m"
          + "  where m.tenant_id=handover_batch.tenant_id and m.batch_id=handover_batch.id)"
          + " as manifest_count"
          + " from handover_batch";

  public HandoverBatchSummary batchSummary() {
    var t =
        sql.fetchOne(
            "select count(*) filter (where status='DRAFT') as draft,"
                + " count(*) filter (where status='DISPATCH_PENDING') as pending,"
                + " count(*) filter (where status='HANDED_OVER') as handed,"
                + " count(*) filter (where status='CANCELLED') as cancelled,"
                + " coalesce(sum(parcel_count) filter (where status='HANDED_OVER'),0) as hp,"
                + " coalesce(sum(total_weight_kg) filter (where status='HANDED_OVER'),0) as hw,"
                + " count(*) filter (where receipt_status='ACCEPTED_WITH_DISCREPANCY'"
                + "   or receipt_status='REJECTED') as mismatch"
                + " from handover_batch");
    var byStatus =
        sql.fetch(
            "select status, count(*) as n, sum(parcel_count) as parcels,"
                + " sum(total_weight_kg) as weight from handover_batch"
                + " group by status order by status");
    var byCarrier =
        sql.fetch(
            "select carrier_code, carrier_channel, handover_bucket as bucket, count(*) as unit_count,"
                + " sum(parcel_count) as parcel_count, sum(total_weight_kg) as weight,"
                + " 0 as missing_waybill from handover_batch where status <> 'CANCELLED'"
                + " group by carrier_code, carrier_channel, handover_bucket"
                + " order by carrier_code, bucket");

    return new HandoverBatchSummary(
        t.get("draft", Long.class),
        t.get("pending", Long.class),
        t.get("handed", Long.class),
        t.get("cancelled", Long.class),
        t.get("hp", Integer.class),
        money(t.get("hw", BigDecimal.class)),
        t.get("mismatch", Long.class),
        byStatus.stream()
            .map(r -> new HandoverStatusBucket(
                r.get("status", String.class),
                statusLabel(r.get("status", String.class)),
                r.get("n", Long.class),
                r.get("parcels", Integer.class),
                money(r.get("weight", BigDecimal.class))))
            .toList(),
        byCarrier.stream().map(HandoverBatchService::bucketOf).toList());
  }

  public HandoverBatchDetail detail(UUID batchId) {
    var row = sql.fetchOne(BATCH_SELECT + " where id=?", batchId);
    if (row == null) {
      throw notFound();
    }
    var batch = batchOf(row);
    var manifest = manifestItems(batchId);
    var timeline = events(batchId);
    return new HandoverBatchDetail(
        batch,
        manifest,
        timeline,
        List.of(
            "交接清单逐件齐备（每件有交接时刻、包裹标识、重量尺寸与经手人）",
            "一条批次全部同一承运商（DB 触发器在写入路径上钉死）",
            "运单号有则记；无则必须写明缺口原因（绝不拿出库单号冒充）"),
        List.of(
            "交接清单是 append-only 的法律凭据：批次交接后包裹丢失责任在承运商，"
                + "清单不可修改或删除（DB REVOKE + 触发器双保险）",
            "本批次记为已交接不等于承运商已揽收：揽收确认属 10.3.2.2，尚未交付",
            "承运商交接/回执的系统对接属 10.1.1.1，尚未交付；"
                + "当前批次停在 DISPATCH_PENDING，回执由人工登记带回"));
  }

  /** 交接清单导出：追责时直接给对方的那一份（与详情同一份凭据，不是另算一套）。 */
  public HandoverManifestExport manifest(UUID batchId) {
    var row = sql.fetchOne(BATCH_SELECT + " where id=?", batchId);
    if (row == null) {
      throw notFound();
    }
    var batch = batchOf(row);
    var items = manifestItems(batchId);
    BigDecimal weight =
        items.stream()
            .map(HandoverManifestItemRow::grossWeightKg)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new HandoverManifestExport(
        batch.id(), batch.batchNo(), batch.carrierCode(), batch.carrierChannel(),
        batch.handoverAt(), batch.status(), batch.statusLabel(), items.size(),
        money(weight), items,
        List.of(
            "本清单逐件登记交接时刻、包裹标识、运单号（有则记，无则写明缺口原因）、"
                + "重量尺寸与经手人",
            "清单为 append-only 凭据：批次交接后包裹丢失责任在承运商，本清单不可事后改写",
            "运单号缺失是上游 10.2.1.2 未交付所致，不等于该件没有运单"));
  }

  public List<HandoverManifestItemRow> manifestItems(UUID batchId) {
    return sql.fetch(
            "select m.id,m.batch_id,m.candidate_id,m.parcel_no,m.parcel_ref,c.warehouse_ref,"
                + " c.outbound_order_no,c.carrier_code,m.waybill_no,m.waybill_gap_reason,"
                + " m.waybill_gap_code,m.gross_weight_kg,m.length_mm,m.width_mm,m.height_mm,"
                + " m.remark,m.handover_at,m.handed_by_membership_id,m.created_at"
                + " from handover_manifest_item m"
                + " join handover_candidate c"
                + "   on c.tenant_id=m.tenant_id and c.id=m.candidate_id"
                + " where m.batch_id=? order by m.parcel_no, m.created_at",
            batchId)
        .stream()
        .map(HandoverBatchService::manifestOf)
        .toList();
  }

  private static HandoverManifestItemRow manifestOf(Record r) {
    return new HandoverManifestItemRow(
        r.get("id", UUID.class),
        r.get("batch_id", UUID.class),
        r.get("candidate_id", UUID.class),
        r.get("parcel_no", Integer.class),
        r.get("parcel_ref", String.class),
        r.get("warehouse_ref", String.class),
        r.get("outbound_order_no", String.class),
        r.get("carrier_code", String.class),
        r.get("waybill_no", String.class),
        r.get("waybill_gap_reason", String.class),
        r.get("waybill_gap_code", String.class),
        money(r.get("gross_weight_kg", BigDecimal.class)),
        money(r.get("length_mm", BigDecimal.class)),
        money(r.get("width_mm", BigDecimal.class)),
        money(r.get("height_mm", BigDecimal.class)),
        r.get("remark", String.class),
        r.get("handover_at", OffsetDateTime.class),
        r.get("handed_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class));
  }

  public List<HandoverBatchEventRow> events(UUID batchId) {
    return sql.fetch(
            "select id,batch_id,candidate_id,event_kind,from_status,to_status,detail,"
                + " actor_membership_id,occurred_at from handover_event where batch_id=?"
                + " order by occurred_at desc, id desc",
            batchId)
        .stream()
        .map(r -> new HandoverBatchEventRow(
            r.get("id", UUID.class),
            r.get("batch_id", UUID.class),
            r.get("candidate_id", UUID.class),
            r.get("event_kind", String.class),
            r.get("from_status", String.class),
            r.get("to_status", String.class),
            r.get("detail", String.class),
            r.get("actor_membership_id", UUID.class),
            r.get("occurred_at", OffsetDateTime.class)))
        .toList();
  }

  // ==================================================================
  // 交接 / 回执 / 作废
  // ==================================================================

  /**
   * 登记交接（DRAFT → DISPATCH_PENDING）。
   *
   * <p>⚠️ 承运商那一跳没有真实对手方（10.1.1.1 未交付），所以本方法
   * <b>不</b>调用适配器、<b>不</b>伪造回执：它登记的是「本租户已把这个批次
   * 交给承运商现场」这一事实（有主体、有时刻），批次停在 DISPATCH_PENDING
   * 如实表达「等待真实通道接通」。适配器一旦接通，由同一个入口在拿到
   * {@code DispatchReceipt} 后推进到 HANDED_OVER。
   */
  public HandoverBatchDetail dispatch(UUID batchId, DispatchRequest request, UUID membershipId) {
    var row = sql.fetchOne("select status, batch_no from handover_batch where id=?", batchId);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!"DRAFT".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有 DRAFT 批次可以登记交接，当前状态：" + status);
    }
    int manifestCount =
        sql.fetchOne(
                "select count(*) from handover_manifest_item where tenant_id=?"
                    + " and batch_id=?",
                currentTenant(), batchId)
            .get(0, Integer.class);
    if (manifestCount == 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "交接清单为空：没有逐件清单就没有留证，不能交接");
    }
    OffsetDateTime at =
        request.handoverAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : request.handoverAt();

    String detail =
        text(request.note()).isEmpty()
            ? "已登记交接；承运商系统对接未接通（10.1.1.1 未交付），批次停在等待接通状态"
            : text(request.note());

    sql.execute(
        "update handover_batch set status='DISPATCH_PENDING', status_detail=?,"
            + " dispatched_by_membership_id=?, dispatched_at=?::timestamptz,"
            + " version=version+1, updated_at=now() where id=?",
        detail, membershipId, at, batchId);
    appendEvent(batchId, "DISPATCH_PENDING", "DRAFT", "DISPATCH_PENDING", detail, membershipId);
    return detail(batchId);
  }

  /**
   * 承运商回执登记。
   *
   * <p>10.1.1.1 未交付 ⇒ 回执<b>由人工登记带回</b>（有主体、有时刻、有来源的
   * 真实凭据），而不是系统对接取回（那是 {@link CarrierHandoverAdapter#fetchReceipt}
   * 的职责，接通后由本服务调用）。
   *
   * <p>回执状态 {@code ACCEPTED_WITH_DISCREPANCY} / {@code REJECTED} 是**现场事实**：
   * 承运商承认少收或拒收，界面必须把它显式标出来，而不是并进「已交接」里。
   */
  public HandoverBatchDetail recordReceipt(
      UUID batchId, ReceiptRequest request, UUID membershipId) {
    var row = sql.fetchOne("select status, batch_no from handover_batch where id=?", batchId);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!"DISPATCH_PENDING".equals(status) && !"HANDED_OVER".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有已登记交接的批次可以带回回执，当前状态：" + status);
    }
    String ref = text(request.receiptRef());
    if (ref.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "回执必须有回执号：没有回执号的回执无法用于事后追责");
    }
    String receiptStatus = text(request.receiptStatus()).toUpperCase(Locale.ROOT);
    if (!List.of("ACCEPTED", "ACCEPTED_WITH_DISCREPANCY", "REJECTED").contains(receiptStatus)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回执状态不合法：" + receiptStatus);
    }
    String detail = text(request.receiptDetail());
    if (List.of("ACCEPTED_WITH_DISCREPANCY", "REJECTED").contains(receiptStatus)
        && detail.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "有差异或被拒的交接必须写明原因，否则无法发起理赔");
    }
    OffsetDateTime at =
        request.receiptRecordedAt() == null
            ? OffsetDateTime.now(ZoneOffset.UTC)
            : request.receiptRecordedAt();

    // 回执确认接收 ⇒ 责任分界点已过，批次推进到 HANDED_OVER（终态）
    boolean accepted = !"REJECTED".equals(receiptStatus);
    sql.execute(
        "update handover_batch set receipt_ref=?, receipt_status=?, receipt_detail=?,"
            + " receipt_recorded_by_membership_id=?, receipt_recorded_at=?::timestamptz,"
            + " status = case when ? then 'HANDED_OVER' else status end,"
            + " status_detail = case when ? then ? else status_detail end,"
            + " version=version+1, updated_at=now() where id=?",
        ref, receiptStatus, detail, membershipId, at,
        accepted, accepted,
        accepted ? "承运商回执确认接收：责任分界点已过" : "承运商拒收：" + detail,
        batchId);
    appendEvent(
        batchId,
        "RECEIPT_RECORDED",
        status,
        accepted ? "HANDED_OVER" : status,
        "回执 " + ref + "：" + receiptLabel(receiptStatus) + (detail.isEmpty() ? "" : "（" + detail + "）"),
        membershipId);
    return detail(batchId);
  }

  /** 作废（DRAFT/DISPATCH_PENDING → CANCELLED），成员退回候选池。 */
  public HandoverBatchDetail cancel(UUID batchId, String note, UUID membershipId) {
    var row = sql.fetchOne("select status, batch_no from handover_batch where id=?", batchId);
    if (row == null) {
      throw notFound();
    }
    String status = row.get("status", String.class);
    if (!"DRAFT".equals(status) && !"DISPATCH_PENDING".equals(status)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "只有 DRAFT 或 DISPATCH_PENDING 批次可以作废，当前状态：" + status);
    }
    sql.execute(
        "update handover_batch set status='CANCELLED', status_detail=?,"
            + " version=version+1, updated_at=now() where id=?",
        note.isEmpty() ? "已作废" : note, batchId);
    // 成员退回候选池。**已登记交接的批次成员不可被摘出** —— 由 DB 触发器
    // guard_handover_candidate_frozen 拒绝，所以这里只在 DRAFT 批次真正退回。
    sql.execute(
        "update handover_candidate set status='PENDING', batch_id=NULL, updated_at=now()"
            + " where batch_id=?",
        batchId);
    appendEvent(batchId, "CANCELLED", status, "CANCELLED", note, membershipId);
    return detail(batchId);
  }

  private void appendEvent(
      UUID batchId, String kind, String from, String to, String detail, UUID membershipId) {
    sql.execute(
        "insert into handover_event (id,tenant_id,batch_id,event_kind,from_status,to_status,detail,"
            + " actor_membership_id) values (?,?,?,?,?,?,?,?)",
        Ids.next(), currentTenant(), batchId, kind, from, to, text(detail), membershipId);
  }

  // ==================================================================
  // 行映射与展示词表
  // ==================================================================

  private static HandoverBatchRow batchOf(Record r) {
    String status = r.get("status", String.class);
    return new HandoverBatchRow(
        r.get("id", UUID.class),
        r.get("batch_no", String.class),
        r.get("carrier_code", String.class),
        r.get("carrier_channel", String.class),
        r.get("handover_at", OffsetDateTime.class),
        r.get("handover_bucket", LocalDate.class),
        r.get("unit_count", Integer.class),
        r.get("parcel_count", Integer.class),
        money(r.get("total_weight_kg", BigDecimal.class)),
        status,
        r.get("status_detail", String.class),
        statusLabel(status),
        r.get("dispatched_by_membership_id", UUID.class),
        r.get("dispatched_at", OffsetDateTime.class),
        r.get("receipt_ref", String.class),
        r.get("receipt_status", String.class),
        receiptLabel(r.get("receipt_status", String.class)),
        r.get("receipt_detail", String.class),
        r.get("receipt_recorded_at", OffsetDateTime.class),
        r.get("manifest_count", Integer.class),
        r.get("version", Integer.class),
        r.get("created_at", OffsetDateTime.class));
  }

  static String statusLabel(String status) {
    return switch (status == null ? "" : status) {
      case "DRAFT" -> "已组批待交接";
      case "DISPATCH_PENDING" -> "已登记交接 · 待承运商接通";
      case "HANDED_OVER" -> "已交接 · 责任在承运商";
      case "CANCELLED" -> "已作废";
      default -> status == null ? "" : status;
    };
  }

  static String receiptLabel(String status) {
    return switch (status == null ? "" : status) {
      case "" -> "";
      case "ACCEPTED" -> "全部接收";
      case "ACCEPTED_WITH_DISCREPANCY" -> "有差异接收";
      case "REJECTED" -> "拒收";
      default -> status;
    };
  }

  static String carrierChannelLabel(String channel) {
    return switch (channel == null ? "" : channel) {
      case "PLATFORM_INTEGRATED" -> "平台面单";
      case "THIRD_PARTY" -> "第三方物流";
      case "SELF_PICKUP" -> "自提";
      case "POSTAL" -> "邮政";
      case "FREIGHT_FORWARDER" -> "货代";
      default -> channel == null ? "" : channel;
    };
  }

  static String waybillGapLabel(String code) {
    return switch (code == null ? "" : code) {
      case "" -> "";
      case "UPSTREAM_NOT_ISSUED" -> "上游尚未签发";
      case "UPSTREAM_UNREADABLE" -> "上游通道不可读";
      case "MANUAL_OVERRIDE" -> "人工指定";
      default -> code;
    };
  }

  private String batchOrderBy(String sort) {
    String s = sort == null ? "HANDOVER_AT_DESC" : sort.toUpperCase(Locale.ROOT);
    return switch (s) {
      case "HANDOVER_AT_ASC" -> "handover_at asc";
      case "HANDOVER_AT_DESC" -> "handover_at desc";
      case "CREATED_AT_ASC" -> "created_at asc";
      case "CREATED_AT_DESC" -> "created_at desc";
      case "BATCH_NO_ASC" -> "batch_no asc";
      default -> "handover_at desc";
    };
  }

  /** 供界面提示用：当前**已接通**的承运商（没有一个 vendor 实现 → 空列表）。 */
  public List<String> connectedCarriers() {
    return List.copyOf(adapters.keySet());
  }
}
