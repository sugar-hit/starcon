package com.starcon.trade;

import static com.starcon.trade.BomBindingModels.*;

import com.starcon.trade.BomBindingModels.BomBinding;
import com.starcon.trade.BomBindingModels.BomFreezeInput;
import com.starcon.trade.BomBindingModels.BomFreezeResult;
import com.starcon.trade.BomBindingModels.BomSnapshot;
import com.starcon.trade.BomBindingModels.BomSnapshotLine;
import com.starcon.trade.BomBindingModels.SnapshotStatus;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.4.1.2 BOM 关联：加工单引用 BOM 版本并**冻结展开结果**。
 *
 * <p>蓝图 06 卷 6.4.1.2 原话：
 *
 * <blockquote>
 *
 * 职责：加工单引用 BOM 版本并冻结展开结果，后续 BOM 修改不影响已下达的加工单。 输入：BOM 版本选择；冻结动作。输出：冻结的组件清单快照。 关系：上游 = 3.5.2.2 展开；下游
 * = 6.4.1.1。 <b>冻结与订单冻结组件同理</b>：加工周期长，BOM 中途改配会导致实物与系统不符。
 *
 * </blockquote>
 *
 * <p><b>本模块的本质是快照，不是引用。</b>冻结动作把 catalog 3.5.2.2 展开出的 <b>整棵展开树</b>连同它的推导依据（单位用量、损耗率、成品 SKU、组件 SKU
 * 及顺序、 所在层与物化路径、来源版本）拍平进 {@code processing_bom_snapshot} / {@code
 * processing_bom_snapshot_line}。读快照**不 join catalog、不 join 当前 BOM** —— 所以 BOM
 * 中途改配、展开重算、库存变动，都影响不到已下达的加工单。
 *
 * <p><b>与 6.4.1.1 的分工（不是重复建设）</b>：6.4.1.1 在建单时冻结的是 <b>单层</b>领料清单（{@code
 * processing_order_component}，问「要领哪些料」）； 本模块冻结的是<b>多层</b>展开树（问「这批料当时按哪棵树、哪一版算的」）。 一个成品用半成品 SA、SA
 * 又由面料 + 拉链组成时，单层清单答不出 「这 3 个 SA 的面料按哪一版、什么损耗率算的」—— 而加工周期长达数周， 这正是蓝图点名要消灭的「实物与系统不符」。
 *
 * <p><b>口径不另立一套</b>：展开由 catalog 3.5.2.2 真算，本类经 {@link BomExplosionPort} 原样搬运，<b>不重算、不取整</b>。
 * 尤其损耗率是<b>百分数</b>（V25 规则 R6，5 = 5%），需求量是规则 R7 的 6 位小数结果。
 */
@Service
public class BomBindingService {

  /** 数量刻度：与 DB numeric(19,6) 及 catalog V25 / V26 逐字对齐。 */
  private static final int QTY_SCALE = 6;

  /** 损耗率刻度：与 DB numeric(7,4) 及 catalog V25 的 scrap_rate 对齐。 */
  private static final int SCRAP_SCALE = 4;

  private static final String SNAPSHOT_COLUMNS =
      "id,order_id,explosion_run_id,bom_id,bom_version_id,bom_version_no,output_sku_id,"
          + "output_sku_code,output_quantity,warehouse_id,as_of,max_depth,reached_depth,"
          + "snapshot_status,line_count,purchase_line_count,total_required,total_gap,"
          + "frozen_by,frozen_at,note,created_at";

  private final DSLContext sql;
  private final BomExplosionPort explosions;

  public BomBindingService(DSLContext sql, BomExplosionPort explosions) {
    this.sql = sql;
    this.explosions = explosions;
  }

  // ================================================================ 冻结

  /**
   * 冻结动作：按 BOM 版本展开一次，把整棵展开树拍平进本服务的库。
   *
   * <p><b>为什么必须调 catalog 而不是自己 join</b>：多层递归展开（V26 规则 E1 的逐层结转）、 子 BOM 判定（E3）、深度截断（E4）、缺口公式（E2）都是
   * catalog 的职责， 而 BOM 表在它的库里。两处各实现一遍必然漂移，而漂移的后果是 「按不同口径冻结的料」—— 那正是本模块要消灭的东西。
   *
   * <p><b>已冻结过就不覆盖</b>：UNIQUE(tenant_id, order_id) + 触发器钉死一份单只有一份快照。 覆盖它会让「这张单当时按哪棵树算的」在历史里出现两个答案，
   * 而领料与回溯只能选一个。正确做法是另开一张加工单。
   */
  @Transactional
  public BomFreezeResult freeze(UUID orderId, BomFreezeInput input, UUID actorId, String bearer) {
    UUID tenantId = currentTenant();
    Record order = requireOrder(orderId);
    if (!"DRAFT".equals(text(order, "status"))) {
      throw bad(
          "加工单「"
              + text(order, "order_no")
              + "」当前是 "
              + text(order, "status")
              + "，不能再冻结 BOM 展开结果：它已经按当时的组件领料清单发出去了，"
              + "这时补一份快照会让「这张单当时按哪棵树算的」出现两个答案。"
              + "要按新 BOM 加工请另开一张加工单");
    }

    // 已经冻结过：把既有那份原样返回，绝不覆盖（见方法注释）。
    Record existing = snapshotRow(orderId);
    if (existing != null) {
      return new BomFreezeResult(snapshot(existing), true);
    }

    UUID versionId = input.bomVersionId();
    BigDecimal outputQuantity = positiveQty(input.outputQuantity(), "产量");
    OffsetDateTime asOf = parseAsOf(input.asOf());
    String note = fit(input.note(), 500, "备注");

    BomExplosionPort.ExplosionRun run =
        explosions.explode(
            versionId, outputQuantity, input.warehouseId(), asOf, input.maxDepth(), note, bearer);

    // BOM 版本必须**已发布**：草稿版本可以随手改组件，按它冻结的树无从回溯
    // （与 6.4.1.1 建单时的同一判据，规则 R3：只有已发布版本参与判定）。
    if (!"PUBLISHED".equals(run.versionStatus())) {
      throw bad(
          "BOM 版本「"
              + run.versionNo()
              + "」当前是 "
              + run.versionStatus()
              + "，不能冻结展开结果。冻结的是「这批料当时按什么口径算的」，"
              + "只有**已发布**版本的组件与损耗率才稳定（3.5.2.1 规则 R2/R3）");
    }
    if (run.lines().isEmpty()) {
      throw bad(
          "BOM 版本「"
              + run.versionNo()
              + "」展开后没有任何组件，不能冻结：一张零行的快照等于"
              + "「这批料不用领任何组件」。请先在 3.5.2.1 维护该版本的组件清单");
    }

    // 冻结的是**按哪一版算的**：加工单上绑的版本必须与本次展开的版本一致。
    // 不一致意味着「冻结的树」与「这张单认的版本」是两回事，
    // 事后回溯就会出现两个答案 —— 而 UNIQUE(tenant_id, order_id) 只能存一份。
    UUID boundVersion = order.get("bom_version_id", UUID.class);
    if (boundVersion != null && !boundVersion.equals(run.versionId())) {
      throw bad(
          "加工单「"
              + text(order, "order_no")
              + "」绑定的 BOM 版本是「"
              + text(order, "bom_version_no")
              + "」，与本次展开的版本「"
              + run.versionNo()
              + "」不是同一版。一张加工单只能冻结一棵树；"
              + "换 BOM 版本请改加工单的绑定（草稿态）或另开一张单");
    }

    OffsetDateTime now = now();
    UUID snapshotId = UUID.randomUUID();
    // 展开结果的基准时刻按 catalog 返回的落库（catalog 会对 asOf 的空值取「此刻」，
    // 本服务不自己猜一个时刻，否则快照记的基准与实际算的基准可能差一秒）。
    OffsetDateTime effectiveAsOf = parseInstant(run.asOf());
    boolean truncated = "TRUNCATED".equals(run.status());

    try {
      sql.execute(
          "insert into processing_bom_snapshot(id,tenant_id,order_id,explosion_run_id,bom_id,"
              + "bom_version_id,bom_version_no,output_sku_id,output_sku_code,output_quantity,"
              + "warehouse_id,as_of,max_depth,reached_depth,snapshot_status,line_count,"
              + "purchase_line_count,total_required,total_gap,frozen_by,frozen_at,note,version,"
              + "created_at,updated_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz,?,?,?,?,?,?,?,?,?::timestamptz,?,0,"
              + "?::timestamptz,?::timestamptz)",
          snapshotId,
          tenantId,
          orderId,
          run.id(),
          run.bomId(),
          run.versionId(),
          fit(run.versionNo(), 32, "BOM 版本号"),
          run.outputSkuId(),
          fit(run.outputSkuCode(), 64, "成品 SKU 编码"),
          qty(run.outputQuantity()),
          run.warehouseId(),
          effectiveAsOf,
          run.maxDepth(),
          run.reachedDepth(),
          truncated ? "TRUNCATED" : "COMPLETED",
          run.lines().size(),
          run.purchaseLineCount(),
          qty(run.totalRequired()),
          qty(run.totalGap()),
          actorId,
          now,
          note,
          now,
          now);
    } catch (DuplicateKeyException e) {
      // 并发冻结同一张单：UNIQUE(tenant_id, order_id) 挡住了第二份快照。
      // 返回既有那份而不是报错 —— 两边冻结的是同一棵展开树（同一 order_id），
      // 谁先谁后不影响「这张单按哪棵树算的」这个答案。
      Record raced = snapshotRow(orderId);
      if (raced == null) {
        throw bad("加工单「" + text(order, "order_no") + "」的 BOM 冻结快照已被他人冻结，请刷新后重试");
      }
      return new BomFreezeResult(snapshot(raced), true);
    }

    // 拍平明细：catalog 的行 id → 本地行 id，父行指回本地 id（自引用）。
    // catalog 的行是按 path 顺序返回的，父行必先于子行，所以一次遍历即可完成映射。
    Map<UUID, UUID> localIds = new HashMap<>();
    for (BomExplosionPort.ExplosionLine line : run.lines()) {
      localIds.put(line.id(), UUID.randomUUID());
    }
    for (BomExplosionPort.ExplosionLine line : run.lines()) {
      UUID parent = line.parentLineId() == null ? null : localIds.get(line.parentLineId());
      if (line.parentLineId() != null && parent == null) {
        // catalog 返回了指向不存在行的父引用：这是上游契约被破坏，
        // 继续写下去会造出一棵断掉的树，而页面只会照着断树渲染。
        throw bad(
            "商品中心返回的展开结果不完整：第 "
                + line.path()
                + " 层的父行在结果里不存在。冻结快照必须是一棵完整的树，"
                + "请重试；若反复出现请检查商品中心的展开实现");
      }
      sql.execute(
          "insert into processing_bom_snapshot_line(id,tenant_id,snapshot_id,parent_line_id,depth,"
              + "path,sku_id,sku_code,sku_name,component_id,source_version_id,source_version_no,"
              + "source_bom_code,unit_quantity,scrap_rate,required_quantity,available,in_transit,"
              + "gap,surplus,is_sub_assembly,sub_bom_id,sub_bom_code,sub_version_id,"
              + "sub_version_no,ambiguous_sub_bom,expanded,covered,truncated,role,note,created_at)"
              + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
          localIds.get(line.id()),
          tenantId,
          snapshotId,
          parent,
          line.depth(),
          fit(line.path(), 1000, "展开路径"),
          line.skuId(),
          fit(line.skuCode(), 64, "组件 SKU 编码"),
          fit(line.skuName(), 200, "组件 SKU 名称"),
          line.componentId(),
          line.sourceVersionId(),
          fit(line.sourceVersionNo(), 32, "来源版本号"),
          fit(line.sourceBomCode(), 64, "来源 BOM 编码"),
          qty(line.unitQuantity()),
          scrap(line.scrapRate()),
          qty(line.requiredQuantity()),
          qty(line.available()),
          qty(line.inTransit()),
          qty(line.gap()),
          qty(line.surplus()),
          line.isSubAssembly(),
          line.subBomId(),
          line.subBomCode(),
          line.subVersionId(),
          line.subVersionNo(),
          line.ambiguousSubBom(),
          line.expanded(),
          line.covered(),
          line.truncated(),
          nz(line.role()),
          fit(line.note(), 1000, "明细备注"),
          now);
    }
    return new BomFreezeResult(snapshot(snapshotRow(orderId)), false);
  }

  // ================================================================ 读取

  /**
   * 读一张加工单的 BOM 绑定：冻结快照 + 「此刻生效的是不是同一版」。
   *
   * <p><b>快照本身不 join catalog。</b>唯一一次跨服务调用是规则 R4 的时刻查询， 而且它<b>只用于生成提示</b>（{@code drifted} / {@code
   * effectiveVersionMissing}）， 绝不参与计算快照里的任何一行 —— 那才是「快照不是引用」的分界线。
   */
  public BomBinding binding(UUID orderId, String bearer) {
    UUID tenantId = currentTenant();
    Record order = requireOrder(orderId);
    Record row = snapshotRow(orderId);
    BomSnapshot snapshot = row == null ? null : snapshot(row);

    String effectiveVersionNo = null;
    boolean effectiveVersionMissing = false;
    boolean drifted = false;
    var notes = new ArrayList<String>();
    if (snapshot == null) {
      notes.add("这张加工单还没有冻结 BOM 展开结果：加工周期内 BOM 一旦改配，" + "就无法回答「这批料当时按哪一版、什么损耗率领的」。请在草稿态执行冻结。");
    } else {
      if (snapshot.truncated()) {
        notes.add(
            "这份快照是**按深度上限截断**的，清单不完整："
                + "第 "
                + snapshot.reachedDepth()
                + " 层之下还有下级 BOM 没展开。"
                + "被展开到的部分已冻结，未展开的部分这张单答不出「按哪一版算」。");
      }
      BomExplosionPort.EffectiveVersion effective =
          explosions.effective(snapshot.bomId(), now(), bearer);
      if (!effective.effective()) {
        effectiveVersionMissing = true;
        notes.add(
            "这个 BOM 此刻没有生效版本（草稿未发布、区间有缝或已发布版本全部关闭）：" + "不影响已冻结的快照，但「现在新单该按哪一版算」需要先在 3.5.2.1 处理。");
      } else {
        effectiveVersionNo = effective.versionNo();
        if (!effectiveVersionNo.equals(snapshot.bomVersionNo())) {
          drifted = true;
          notes.add(
              "BOM 已接替新版本：这张单冻结的是「"
                  + snapshot.bomVersionNo()
                  + "」，此刻生效的是「"
                  + effectiveVersionNo
                  + "」。**已下达的加工单不受影响**（快照已冻结，领料按 "
                  + snapshot.bomVersionNo()
                  + " 走）；但按新版本生产的下一批货要另开单。");
        }
      }
      notes.add(
          "快照里的单位用量 / 损耗率 / 需求量都是冻结时刻的值，"
              + "读它们不 join 当前 BOM：损耗率是**百分数**（5 = 5%），"
              + "需求量按规则 R7 = 单位用量 × 产量 × (1 + 损耗率/100)，6 位小数不取整。");
    }
    return new BomBinding(
        orderId,
        text(order, "order_no"),
        text(order, "status"),
        snapshot,
        effectiveVersionNo,
        drifted,
        effectiveVersionMissing,
        notes);
  }

  /** 展开树（按 path 稳定输出，父行必先于子行）。快照不存在时返回空表而不是 null。 */
  public List<BomSnapshotLine> lines(UUID orderId) {
    UUID tenantId = currentTenant();
    requireOrder(orderId);
    Record row = snapshotRow(orderId);
    if (row == null) {
      return List.of();
    }
    return sql.fetch(
            "select id,parent_line_id,depth,path,sku_id,sku_code,sku_name,component_id,"
                + "source_version_id,source_version_no,source_bom_code,unit_quantity,scrap_rate,"
                + "required_quantity,available,in_transit,gap,surplus,is_sub_assembly,sub_bom_id,"
                + "sub_bom_code,sub_version_id,sub_version_no,ambiguous_sub_bom,expanded,"
                + "covered,truncated,role,note from processing_bom_snapshot_line"
                + " where snapshot_id = ? order by path, id",
            row.get("id", UUID.class))
        .map(BomBindingService::lineRow);
  }

  // ================================================================ 内部

  private Record requireOrder(UUID orderId) {
    Record order =
        sql.fetchOne(
            "select id,order_no,status,bom_id,bom_version_id,bom_version_no,"
                + "planned_output_quantity from processing_order where id = ?",
            orderId);
    if (order == null) {
      // 跨租户在 FORCE RLS 下查不到 → 同样 404，不泄露「别家有没有这张单」。
      throw notFound("加工单不存在或无权查看：" + orderId);
    }
    return order;
  }

  private Record snapshotRow(UUID orderId) {
    return sql.fetchOne(
        "select " + SNAPSHOT_COLUMNS + " from processing_bom_snapshot where order_id = ?", orderId);
  }

  private BomSnapshot snapshot(Record r) {
    UUID snapshotId = r.get("id", UUID.class);
    String status = text(r, "snapshot_status");
    return new BomSnapshot(
        snapshotId,
        r.get("order_id", UUID.class),
        r.get("explosion_run_id", UUID.class),
        r.get("bom_id", UUID.class),
        r.get("bom_version_id", UUID.class),
        text(r, "bom_version_no"),
        r.get("output_sku_id", UUID.class),
        text(r, "output_sku_code"),
        qty(decimal(r, "output_quantity")),
        r.get("warehouse_id", UUID.class),
        instant(r, "as_of"),
        r.get("max_depth", Integer.class),
        r.get("reached_depth", Integer.class),
        status,
        "TRUNCATED".equals(status),
        r.get("line_count", Integer.class),
        r.get("purchase_line_count", Integer.class),
        qty(decimal(r, "total_required")),
        qty(decimal(r, "total_gap")),
        r.get("frozen_by", UUID.class),
        instant(r, "frozen_at"),
        text(r, "note"),
        lineList(snapshotId));
  }

  private List<BomSnapshotLine> lineList(UUID snapshotId) {
    return sql.fetch(
            "select id,parent_line_id,depth,path,sku_id,sku_code,sku_name,component_id,"
                + "source_version_id,source_version_no,source_bom_code,unit_quantity,scrap_rate,"
                + "required_quantity,available,in_transit,gap,surplus,is_sub_assembly,sub_bom_id,"
                + "sub_bom_code,sub_version_id,sub_version_no,ambiguous_sub_bom,expanded,"
                + "covered,truncated,role,note from processing_bom_snapshot_line"
                + " where snapshot_id = ? order by path, id",
            snapshotId)
        .map(BomBindingService::lineRow);
  }

  private static BomSnapshotLine lineRow(Record r) {
    return new BomSnapshotLine(
        r.get("id", UUID.class),
        r.get("parent_line_id", UUID.class),
        r.get("depth", Integer.class),
        text(r, "path"),
        r.get("sku_id", UUID.class),
        text(r, "sku_code"),
        text(r, "sku_name"),
        r.get("component_id", UUID.class),
        r.get("source_version_id", UUID.class),
        text(r, "source_version_no"),
        text(r, "source_bom_code"),
        qty(decimal(r, "unit_quantity")),
        scrap(decimal(r, "scrap_rate")),
        qty(decimal(r, "required_quantity")),
        qty(decimal(r, "available")),
        qty(decimal(r, "in_transit")),
        qty(decimal(r, "gap")),
        qty(decimal(r, "surplus")),
        r.get("is_sub_assembly", Boolean.class),
        r.get("sub_bom_id", UUID.class),
        text(r, "sub_bom_code"),
        r.get("sub_version_id", UUID.class),
        text(r, "sub_version_no"),
        r.get("ambiguous_sub_bom", Boolean.class),
        r.get("expanded", Boolean.class),
        r.get("covered", Boolean.class),
        r.get("truncated", Boolean.class),
        text(r, "role"),
        text(r, "note"));
  }

  /**
   * 当前 RLS 租户。由 backend/libraries/tenancy 在事务内设置，客户端头改不动它。
   *
   * <p>nullif 不是洁癖：事务局部的 set_config 提交后，PostgreSQL 把自定义 GUC <b>复位成空串</b>而不是「回到未设置」，直接 cast 会报 uuid
   * 语法错 （与 ProcessingOrderService 同一句写法）。
   */
  private UUID currentTenant() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** 解析 asOf 入参。**必须带偏移量**：用 LocalDateTime 猜一个会在东八区静默偏 −28800s。 */
  private static OffsetDateTime parseAsOf(String value) {
    String text = nz(value).trim();
    if (text.isEmpty()) {
      return null; // 不传 = 此刻，由 catalog 决定
    }
    try {
      return OffsetDateTime.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw bad("展开基准时刻「" + text + "」格式不对：请带时区偏移量，例如 2026-10-10T08:00:00+08:00");
    }
  }

  private static OffsetDateTime parseInstant(String value) {
    try {
      return OffsetDateTime.parse(value);
    } catch (RuntimeException e) {
      throw bad("商品中心返回的展开基准时刻无法解析：" + value);
    }
  }

  private static String instant(Record r, String column) {
    OffsetDateTime value = r.get(column, OffsetDateTime.class);
    return value == null ? null : value.toString();
  }

  /** 读数量。纯文本 SQL 里的表达式列 jOOQ 拿不到列类型， 收口成不依赖驱动返回类型的读法（null 被静默读成 0 是同一个坑）。 */
  private static BigDecimal decimal(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return BigDecimal.ZERO;
    }
    return value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
  }

  /** 数量刻度：与 DB numeric(19,6) 及 catalog 的来源列对齐。 */
  private static BigDecimal qty(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(QTY_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * 损耗率刻度：与 DB numeric(7,4) 及 catalog V25 的 scrap_rate 对齐。
   *
   * <p>这里<b>只做刻度对齐，不做语义换算</b>：catalog 给的百分数（5 = 5%） 原样落库。绝不在这里除以 100 —— 那是 R6 明确禁止的比率化， 一次漂移就让领料量翻
   * 6 倍且不报错。
   */
  private static BigDecimal scrap(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value.setScale(SCRAP_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal positiveQty(BigDecimal value, String label) {
    if (value == null || value.signum() <= 0) {
      throw bad(label + "必须大于 0");
    }
    if (value.scale() > QTY_SCALE) {
      throw bad(label + "最多 6 位小数（与 DB numeric(19,6) 及 catalog 规则 R7 的口径一致）");
    }
    return qty(value);
  }

  private static String fit(String value, int max, String label) {
    if (value != null && value.length() > max) {
      throw bad(label + "长度不能超过 " + max + " 个字符");
    }
    return value == null ? "" : value.trim();
  }

  private static String nz(String value) {
    return value == null ? "" : value;
  }

  /**
   * 读文本列。纯文本 SQL 里 jOOQ 拿不到列类型，{@code Record.get(String)} 返回 Object， 直接塞进 record 的 String 位会编译不过 ——
   * 收口到一处，不在每个调用点写强转。
   */
  private static String text(Record r, String column) {
    Object value = r.get(column);
    return value == null ? "" : value.toString();
  }

  private static ResponseStatusException bad(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException notFound(String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
  }

  /** 供契约文档引用：状态词表只有 DB CHECK 那一处。 */
  public static List<SnapshotStatus> statuses() {
    return List.of(SnapshotStatus.values());
  }
}
