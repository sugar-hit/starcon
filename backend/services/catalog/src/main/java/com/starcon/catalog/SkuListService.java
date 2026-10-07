package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.1.2.1 SKU 列表：分页、多维筛选、批量操作与导出。
 *
 * <p>分页是**真分页**：LIMIT/OFFSET 下推到 PostgreSQL，排序键末尾恒定追加 {@code id} 作为
 * 决胜列，保证翻页时同一批结果不会因为 updated_at 相同而错位或重复。绝不在内存里全量捞出再切片。
 *
 * <p>跨服务字段（库存 / 成本 / 售价 / 在售平台）一律经 {@link SkuProjectionPort} 读取，
 * 未接入时如实标成未接入；依赖它们的筛选条件被显式拒绝，而不是返回空列表假装"都没有缺货"。
 */
@Service
public class SkuListService {
  /** 单页最大行数：防止一次性把整个租户的 SKU 拉进 JVM。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 导出单次上限。SKU 明细是仓储/采购/财务共用的事实入口，无上限导出等于开放全量下载。 */
  static final int MAX_EXPORT_ROWS = 50_000;

  /** 导出分批读取的行数。分批而不是一次 fetchSize，避免大结果集在客户端内存里堆成一个大数组。 */
  static final int EXPORT_CHUNK = 1000;

  private static final String FROM =
      " from sku s join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id";

  private final DSLContext sql;
  private final SkuProjectionPort projections;
  private final AccessService access;

  public SkuListService(DSLContext sql, SkuProjectionPort projections, AccessService access) {
    this.sql = sql;
    this.projections = projections;
    this.access = access;
  }

  // ------------------------------------------------------------ 能力声明

  /**
   * 列表页顶部的能力声明：逐字段标注可用性，前端据此禁用库存/价格区间等控件。
   *
   * <p>这比"前端自己猜哪些能筛"可靠——投影接入与否是后端的运行时事实，编进前端就会漂移。
   */
  public SkuListCapabilities capabilities() {
    var fields =
        Arrays.stream(SkuField.values())
            .map(
                f ->
                    new SkuFieldInfo(
                        f.name(),
                        f.label(),
                        f.source().label(),
                        f.kind().name(),
                        projections.supports(f),
                        f.rangeFilterable(),
                        projections.supports(f)
                            ? "已接入" + f.source().label() + "投影"
                            : f.ownerNote()))
            .toList();
    return new SkuListCapabilities(
        fields, List.of("spuId", "category", "statuses", "keyword", "barcode", "spec", "sort", "page"));
  }

  // ------------------------------------------------------------ 列表

  /** 分页查询。catalog 自有字段直接走本库；投影字段不做列，只在行组装时按可用性表达。 */
  public SkuPage page(SkuListQuery query) {
    requireProjections(query);
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size()));
    int page = Math.max(0, query.page());
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*)" + FROM + sqlWhere, binds.toArray()).get(0, Long.class);

    // 决胜列 id 恒定追加：updated_at 相同的行也不会在翻页边界上错位。
    var rows =
        sql.fetch(
            "select s.tenant_id,s.id,s.spu_id,s.code,s.name,s.barcode,s.base_unit,s.status,s.version,"
                + "s.variant_signature,s.updated_at,p.code as spu_code,p.name as spu_name,p.category"
                + FROM
                + sqlWhere
                + order(query)
                + " limit ? offset ?",
            append(binds, size, (long) page * size).toArray());
    return new SkuPage(rows.stream().map(this::row).toList(), total, page, size);
  }

  private List<Object> append(List<Object> binds, Object... more) {
    var all = new ArrayList<>(binds);
    all.addAll(Arrays.asList(more));
    return all;
  }

  /** 拼 WHERE。所有值都走绑定，标识符只用常量白名单。 */
  private void conditions(SkuListQuery q, List<String> where, List<Object> binds) {
    if (q.spuId() != null) {
      where.add("s.spu_id = ?");
      binds.add(q.spuId());
    }
    if (q.category() != null && !q.category().isBlank()) {
      where.add("p.category = ?");
      binds.add(q.category().trim());
    }
    var statuses =
        q.statuses() == null
            ? List.<String>of()
            : q.statuses().stream().map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
    if (!statuses.isEmpty()) {
      for (String s : statuses)
        CatalogService.check(
            Set.of("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED").contains(s), "无效状态：" + s);
      where.add("s.status in (" + String.join(",", Collections.nCopies(statuses.size(), "?")) + ")");
      binds.addAll(statuses);
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      String escaped = escape(q.keyword());
      where.add(
          "(s.code ilike ? escape '!' or s.name ilike ? escape '!' or s.barcode ilike ? escape '!')");
      binds.add("%" + escaped + "%");
      binds.add("%" + escaped + "%");
      binds.add("%" + escaped + "%");
    }
    if (q.barcode() != null && !q.barcode().isBlank()) {
      where.add("s.barcode = ?");
      binds.add(q.barcode().trim());
    }
    if (q.spec() != null && !q.spec().isBlank()) {
      // 规格关键字查 variant_signature（规格轴取值拼出的签名），不是某个冗余字符串列。
      where.add("s.variant_signature ilike ? escape '!'");
      binds.add("%" + escape(q.spec()) + "%");
    }
  }

  /** 排序白名单 + 恒定决胜列。输入只能挑列，改不了 SQL 结构。 */
  private String order(SkuListQuery q) {
    String sort = q.sort() == null ? "updated_at" : q.sort().trim().toLowerCase(Locale.ROOT);
    boolean desc = q.order() == null || q.order().trim().isBlank() || "desc".equalsIgnoreCase(q.order().trim());
    var column =
        switch (sort) {
          case "code" -> "s.code";
          case "name" -> "s.name";
          case "status" -> "s.status";
          case "barcode" -> "s.barcode";
          case "created_at" -> "s.created_at";
          default -> "s.updated_at";
        };
    return " order by " + column + (desc ? " desc" : " asc") + ", s.id desc";
  }

  /** LIKE 模式转义：让用户输入里的 % 和 _ 只当字面量，不当通配符。 */
  private static String escape(String value) {
    return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }

  /**
   * 依赖未接入投影的筛选条件在这里被显式拒绝。
   *
   * <p>静默忽略这些条件会得到"看起来筛过了、其实没筛"的结果列表——用户以为在按缺货筛选，
   * 看到的却是全量数据，比直接报错危险得多。
   */
  void requireProjections(SkuListQuery q) {
    var missing = new ArrayList<SkuField>();
    if (q.onHandMin() != null || q.onHandMax() != null) missing.add(SkuField.ON_HAND);
    if (q.priceMin() != null || q.priceMax() != null) missing.add(SkuField.SALE_PRICE);
    if (q.outOfStock() != null) missing.add(SkuField.AVAILABLE_QTY);
    if (q.onSale() != null) missing.add(SkuField.LISTED_PLATFORMS);
    if (q.platform() != null && !q.platform().isBlank()) missing.add(SkuField.LISTED_PLATFORMS);
    var unavailable = missing.stream().filter(f -> !projections.supports(f)).distinct().toList();
    if (!unavailable.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "以下筛选条件依赖尚未接入的上游投影，无法求值："
              + unavailable.stream().map(f -> f.label() + "（" + f.ownerNote() + "）").collect(Collectors.joining("；"))
              + "。请先接入对应服务的投影，或改用其他条件。");
  }

  /** 行组装：catalog 自有字段来自本库，投影字段按可用性如实表达。 */
  private SkuListRow row(Record r) {
    UUID tenant = r.get("tenant_id", UUID.class);
    UUID id = r.get("id", UUID.class);
    return new SkuListRow(
        id,
        r.get("spu_id", UUID.class),
        r.get("spu_code", String.class),
        r.get("spu_name", String.class),
        r.get("category", String.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("barcode", String.class),
        r.get("base_unit", String.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        r.get("variant_signature", String.class),
        specs(id),
        cells(tenant, id),
        r.get("updated_at", OffsetDateTime.class).toInstant().toString());
  }

  /**
   * 从规格轴解析展示值，而不是读某个冗余字符串列。
   *
   * <p>取 attribute_definition.variant_axis 为真的轴，解析该 SKU 在每个轴上 SET 的取值；
   * 规格轴按 V1 约束必填单选，所以每个轴最多一个值。
   */
  private List<SkuSpec> specs(UUID skuId) {
    return sql.fetch(
            "select d.name as axis, i.option_value as value"
                + " from attribute_value v"
                + " join attribute_definition d on d.tenant_id=v.tenant_id and d.id=v.attribute_id"
                + " join attribute_value_item i on i.tenant_id=v.tenant_id and i.value_id=v.id"
                + " where v.sku_id=? and d.variant_axis and v.state='SET'"
                + " order by d.position,d.name",
            skuId)
        .map(r -> new SkuSpec(r.get("axis", String.class), r.get("value", String.class)));
  }

  private List<SkuProjectionCell> cells(UUID tenantId, UUID skuId) {
    return Arrays.stream(SkuField.values())
        .map(
            f -> {
              var p = projections.value(tenantId, skuId, f);
              // 未接入时 value/text 一律不下发，避免前端把 ZERO 当成真实库存画成 0。
              return new SkuProjectionCell(
                  f.name(),
                  f.label(),
                  f.source().label(),
                  p.available() ? p.value() : null,
                  p.available() ? p.text() : null,
                  p.available(),
                  p.available() ? "已接入" + f.source().label() + "投影" : f.ownerNote());
            })
        .toList();
  }

  // ------------------------------------------------------------ 批量操作

  /**
   * 批量停售 / 批量改状态。
   *
   * <p>{@code dryRun=true} 只算影响范围不落状态变更（预览），否则逐个 SKU 走与单条编辑
   * 相同的不变量：已启用/已停用/已归档的 SKU 不能回到草稿。
   *
   * <p>批量分配与批量生成刊登依赖 04 刊登（未开发），这里显式拒绝并指明归属服务，
   * 不做假实现——返回一个"成功但什么都没做"的任务比报错更糟。
   */
  public SkuBatchResult batch(AccessService.Access actor, SkuBatchInput input) {
    if (Set.of("ASSIGN", "GENERATE_LISTING").contains(input.action()))
      throw new ResponseStatusException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "「"
              + ("ASSIGN".equals(input.action()) ? "批量分配" : "批量生成刊登")
              + "」依赖 04 刊登服务，该服务本阶段尚未开发，因此无法执行。请在 04 刊登落地后从 SKU 列表发起。");
    if (!Set.of("STOP_SELLING", "SET_STATUS").contains(input.action()))
      throw new IllegalArgumentException("不支持的批量操作：" + input.action());
    CatalogService.check(!input.skuIds().isEmpty(), "请先选择要批量操作的 SKU");
    CatalogService.check(
        new HashSet<>(input.skuIds()).size() == input.skuIds().size(), "SKU 不能重复提交");
    String target =
        "STOP_SELLING".equals(input.action())
            ? "INACTIVE"
            : input.targetStatus().trim().toUpperCase(Locale.ROOT);
    CatalogService.check(
        Set.of("ACTIVE", "INACTIVE", "ARCHIVED").contains(target),
        "批量改状态只能改为 ACTIVE / INACTIVE / ARCHIVED");
    String reason = input.reason().trim();
    CatalogService.check(!reason.isBlank(), "批量操作必须填写原因");
    OffsetDateTime occurredAt = instant(input.occurredAt());

    var planned = new ArrayList<SkuBatchItem>();
    var currentStatus = new LinkedHashMap<UUID, String>();
    var visible = new ArrayList<SkuBatchItem>();
    for (UUID id : input.skuIds()) {
      var found = sql.fetchOne("select status,code from sku where id=?", id);
      // 取不到就是「不存在或没有访问权限」：RLS 在下面生效，不泄露跨租户是否存在。
      if (found == null) {
        planned.add(new SkuBatchItem(id, "", "SKIPPED", "记录不存在或没有访问权限"));
        continue;
      }
      String current = found.get("status", String.class);
      String code = found.get("code", String.class);
      currentStatus.put(id, current);
      if (current.equals(target)) {
        var skipped = new SkuBatchItem(id, code, "SKIPPED", "已处于目标状态");
        planned.add(skipped);
        visible.add(skipped);
      } else if (!current.equals("DRAFT") && target.equals("DRAFT")) {
        var skipped = new SkuBatchItem(id, code, "SKIPPED", "已启用 SKU 不能恢复草稿");
        planned.add(skipped);
        visible.add(skipped);
      } else {
        var apply = new SkuBatchItem(id, code, "APPLIED", reason);
        planned.add(apply);
        visible.add(apply);
      }
    }

    UUID batchId = Ids.next();
    int applied = (int) planned.stream().filter(i -> i.outcome().equals("APPLIED")).count();
    sql.execute(
        "insert into sku_batch_operation(id,tenant_id,action,target_status,reason,status,requested_count,"
            + "matched_count,applied_count,skipped_count,actor_id,occurred_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        batchId,
        actor.tenantId(),
        input.action(),
        target,
        reason,
        input.dryRun() ? "PREVIEW" : "APPLIED",
        input.skuIds().size(),
        currentStatus.size(),
        applied,
        planned.size() - applied,
        actor.userId(),
        occurredAt);
    // 只为**本租户可见**的 SKU 落明细：sku_batch_operation_item 的 data_scope 策略要求对应 SKU
    // 可见，越权提交的 ID 连写入都不允许——这正是我们想要的，写一条指向看不见的 SKU 的行
    // 等于把别人的 ID 存进自己的表。看不见的那些只在响应里回一条 SKIPPED，
    // requested_count 与 matched_count 的差额已经如实记录了这件事。
    for (var item : visible)
      sql.execute(
          "insert into sku_batch_operation_item(tenant_id,batch_id,sku_id,outcome,reason) values(?,?,?,?,?)",
          actor.tenantId(),
          batchId,
          item.skuId(),
          item.outcome(),
          item.reason());

    if (input.dryRun())
      // 预览只落"看过影响范围"这条痕，不动任何 SKU 状态。
      return new SkuBatchResult(
          batchId,
          input.action(),
          target,
          true,
          input.skuIds().size(),
          currentStatus.size(),
          applied,
          planned.size() - applied,
          reason,
          planned);

    var done = new ArrayList<SkuBatchItem>();
    var skipped = new ArrayList<SkuBatchItem>();
    for (var item : planned) {
      if (!item.outcome().equals("APPLIED")) {
        skipped.add(item);
        continue;
      }
      // 逐条 CAS：并发的另一批操作可能已经改掉状态，status 不匹配就不覆盖。
      int n =
          sql.execute(
              "update sku set status=?,version=version+1,updated_at=now() where id=? and status=?",
              target,
              item.skuId(),
              currentStatus.get(item.skuId()));
      if (n != 1) {
        String note = "状态已被其他操作变更";
        sql.execute(
            "update sku_batch_operation_item set outcome='SKIPPED',reason=? where tenant_id=? and batch_id=? and sku_id=?",
            note,
            actor.tenantId(),
            batchId,
            item.skuId());
        skipped.add(new SkuBatchItem(item.skuId(), item.code(), "SKIPPED", note));
        continue;
      }
      sql.execute(
          "insert into sku_status_event(id,tenant_id,sku_id,from_status,to_status,reason,source,batch_id,actor_id,occurred_at)"
              + " values(?,?,?,?,?,?,'BATCH',?,?,?::timestamptz)",
          Ids.next(),
          actor.tenantId(),
          item.skuId(),
          currentStatus.get(item.skuId()),
          target,
          reason,
          batchId,
          actor.userId(),
          occurredAt);
      sql.execute(
          "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
          Ids.next(),
          actor.tenantId(),
          actor.userId(),
          item.skuId(),
          "SkuBatchStatusChanged");
      done.add(item);
    }
    applied = done.size();
    sql.execute(
        "update sku_batch_operation set applied_count=?,skipped_count=? where tenant_id=? and id=?",
        applied,
        skipped.size(),
        actor.tenantId(),
        batchId);
    // 批量改状态要发 outbox 事件给下游（平台商品是否下架由 04 决定，catalog 不越界）。
    // tenant_id 取自 sku 行本身，不是另绑一个参数。
    for (var item : done)
      sql.execute(
          "insert into outbox_event(id,tenant_id,aggregate_id,aggregate_version,event_type,payload)"
              + " select ?,tenant_id,id,version,'SkuBatchStatusChanged',"
              + " jsonb_build_object('batchId',?::text,'skuId',id,'status',status::text,'reason',?::text)"
              + " from sku where id=?",
          Ids.next(),
          batchId.toString(),
          reason,
          item.skuId());
    var merged = new ArrayList<SkuBatchItem>(done);
    merged.addAll(skipped);
    return new SkuBatchResult(
        batchId,
        input.action(),
        target,
        false,
        input.skuIds().size(),
        currentStatus.size(),
        applied,
        skipped.size(),
        reason,
        merged.stream().sorted(Comparator.comparing(SkuBatchItem::skuId)).toList());
  }

  static OffsetDateTime instant(String value) {
    return value == null || value.isBlank()
        ? OffsetDateTime.now(java.time.ZoneOffset.UTC)
        : OffsetDateTime.parse(value);
  }

  // ------------------------------------------------------------ 导出

  /**
   * 导出 CSV：与列表共用同一套条件，所以导出的就是当前查到的那些行，不是另一个数据源。
   *
   * <p>分批读取 + 逐批写出：每批只把 {@value #EXPORT_CHUNK} 行读进 JVM，立刻交给 emit 写进响应流，
   * 因此峰值内存与结果集大小无关。上限 {@value #MAX_EXPORT_ROWS} 行，超限明确报错让操作人
   * 收窄条件，而不是悄悄截断。
   *
   * <p>每批各自开一个租户事务：租户上下文是事务级设置（{@code app.current_tenant}），
   * 流式响应的工作线程拿不到 controller 那个事务的连接，所以按批重新绑定而不是共用一个长事务。
   */
  public void streamCsv(AccessService.Access actor, SkuListQuery query, Consumer<String> emit) {
    requireProjections(query);
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    conditions(query, where, binds);
    String sqlWhere = where.isEmpty() ? "" : " where " + String.join(" and ", where);

    long total =
        access.run(actor, "catalog.sku.read", () -> sql.fetchOne("select count(*)" + FROM + sqlWhere, binds.toArray()).get(0, Long.class));
    CatalogService.check(
        total <= MAX_EXPORT_ROWS,
        "命中 " + total + " 行，超过单次导出上限 " + MAX_EXPORT_ROWS + " 行，请收窄筛选条件");

    // BOM 让 Excel 正确识别 UTF-8 中文表头。
    emit.accept(
        "﻿"
            + String.join(
                ",",
                List.of(
                    "SKU编码",
                    "SKU名称",
                    "所属SPU编码",
                    "所属SPU名称",
                    "分类",
                    "条码",
                    "基础单位",
                    "状态",
                    "规格",
                    "在仓数量",
                    "在途数量",
                    "可售数量",
                    "单位成本",
                    "采购价",
                    "售价",
                    "在售平台",
                    "更新时间"))
            + "\n");

    String order = order(query);
    long offset = 0;
    while (offset < total) {
      int limit = (int) Math.min(EXPORT_CHUNK, total - offset);
      // offset 每轮递增，不能直接被 lambda 捕获——固定成本轮的不可变视图。
      long at = offset;
      var batch =
          access.run(
              actor,
              "catalog.sku.read",
              () ->
                  sql.fetch(
                      "select s.tenant_id,s.id,s.code,s.name,s.barcode,s.base_unit,s.status,"
                          + "s.variant_signature,s.updated_at,p.code as spu_code,p.name as spu_name,p.category"
                          + FROM
                          + sqlWhere
                          + order
                          + " limit ? offset ?",
                      append(binds, limit, at).toArray()));
      if (batch.isEmpty()) break;
      var sb = new StringBuilder();
      for (Record r : batch) {
        UUID id = r.get("id", UUID.class);
        var cells = cells(r.get("tenant_id", UUID.class), id);
        var line = new ArrayList<String>();
        line.add(r.get("code", String.class));
        line.add(r.get("name", String.class));
        line.add(r.get("spu_code", String.class));
        line.add(r.get("spu_name", String.class));
        line.add(r.get("category", String.class));
        line.add(r.get("barcode", String.class));
        line.add(r.get("base_unit", String.class));
        line.add(r.get("status", String.class));
        line.add(specs(id).stream().map(s -> s.axis() + "=" + s.value()).collect(Collectors.joining(" / ")));
        for (var c : cells) line.add(c.available() ? value(c) : "未接入");
        line.add(r.get("updated_at", OffsetDateTime.class).toInstant().toString());
        sb.append(String.join(",", line.stream().map(SkuListService::csv).toList())).append('\n');
      }
      // 每批立刻写出并让出内存，峰值只与单批大小相关。
      emit.accept(sb.toString());
      offset += batch.size();
    }
  }

  private static String value(SkuProjectionCell c) {
    if (c.value() != null) return c.value().toPlainString();
    return c.text() == null ? "" : c.text();
  }

  /** CSV 转义按 RFC 4180；前导 =/+/-/@ 会被 Excel 当成公式执行，加前缀单引号切断。 */
  static String csv(String value) {
    String text = value == null ? "" : value;
    if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
    return '"' + text.replace("\"", "\"\"") + '"';
  }
}
