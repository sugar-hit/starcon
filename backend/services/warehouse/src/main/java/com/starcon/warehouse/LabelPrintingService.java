package com.starcon.warehouse;

import static com.starcon.warehouse.LabelPrintModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.3.4.2 面单与标签打印。
 *
 * <p>蓝图：「打印面单、装箱单、SKU 条码、库位标签、箱唛，支持批量打印、模板配置、打印次数记录 （平台对重复打印有要求）。」输入：「打印任务（对象集合、模板）；打印机配置。」
 * 输出：「打印任务记录 + 打印内容（PDF/指令流）；打印日志。」红线：「**面单打印次数需记录**， 部分平台对重复面单有明确规则。」
 *
 * <h2>打印次数怎么算的（本模块最容易被做成假逻辑的地方）</h2>
 *
 * <p>一次打印动作 = 一个任务里 N 个对象 × M 份数，于是产生 N×M 条打印凭据，而每个对象的 <b>权威次数</b>（{@code
 * print_target.print_count}）只按实际份数累加。累加走 SQL 原子更新， 不是「读出来 +1 再写回去」：
 *
 * <pre>{@code
 * update print_target set print_count = print_count + ?
 *  where tenant_id = ? and id = ? returning print_count
 * }</pre>
 *
 * <p>并发下两个任务同时重打同一条面单，靠这条 UPDATE 各自的 RETURNING 拿到不同的序号， 不会互相覆盖。拿到的新次数就是本次凭据的 {@code
 * print_sequence}（第几次打印）， 再写 {@code label_print_log}。DB 层有三道闸门兜底：{@code
 * guard_print_count_monotonic} （次数只增不减）、{@code label_print_log_append_only}（凭据不可改）、{@code
 * guard_print_log_sequence} （凭据序号不得超过权威次数）。
 *
 * <h2>重复打印判据</h2>
 *
 * <p>判定用的是「<b>下一次打印的序号 = 当前次数 + 份数</b>」而不是「当前次数」，因为一次任务可能打多份。 规则按「平台 + 对象类型」取，取不到就用 {@code '*'}
 * 通配规则，都没有则不限次（但仍然计数、仍然留凭据）。 三段区间：
 *
 * <pre>
 *   next &lt;= warning        → NORMAL  照打
 *   warning &lt; next &lt;= max → WARNING 照打但留告警标记（前端与日志都要说）
 *   next &gt; max              → BLOCKED 默认拒绝；要打必须 overrideReprint + overrideReason
 * </pre>
 *
 * <p>超限拒绝是 <b>整体拒绝</b>，不是「跳过超限的那几条继续打剩下的」——半张面单贴出去比不贴更糟。
 *
 * <h2>为什么不自己建 outbound_order</h2>
 *
 * <p>出库单（8.3.1.1）与物流（10）都不在本服务的事实边界内（AGENTS.md 禁止跨服务 SQL / FK）。 打印对象是「本地快照 + 纯文本
 * external_ref」，快照由调用方传入；快照被 DB 触发器冻成不可变， 所以重打第 2 次贴出来的内容与第 1 次逐字一致，计数与告警也就对得上。
 */
@Service
public class LabelPrintingService {

  private static final int MAX_PAGE_SIZE = 200;
  private static final int MAX_BATCH_TARGETS = 500;

  /** 作业号前缀：打印任务与其它单据序列区分开，现场报单号时一眼看出是哪类。 */
  private static final String JOB_PREFIX = "PRJ";

  private final DSLContext sql;
  private final ObjectMapper json;

  public LabelPrintingService(DSLContext sql, ObjectMapper json) {
    this.sql = sql;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：五种对象的渲染形态与必填字段、重复打印判据的三段区间与强制重打口径， 以及「面单数据来自调用方」这一事实的显式声明。
   *
   * <p>前端据此渲染表单与提示，而不是把规则硬编码在界面上 —— 重复打印判据一旦只在界面上， 换个入口打就绕过去了。
   */
  public LabelPrintCapabilities capabilities() {
    var types = new ArrayList<ObjectTypeCapability>();
    for (var type : PrintObjectType.values()) {
      var required =
          LabelRenderService.FieldLayout.defaultsFor(type).stream()
              .filter(LabelRenderService.FieldBlock::required)
              .map(LabelRenderService.FieldBlock::key)
              .toList();
      types.add(
          new ObjectTypeCapability(
              type.name(),
              type.label(),
              type.description(),
              LabelRenderService.defaultFormatFor(type),
              required,
              type.reprintGoverned()));
    }
    return new LabelPrintCapabilities(
        types,
        new ReprintCapability(
            "判定基准 = 下一次打印的序号 = 该对象当前打印次数 + 本次份数",
            List.of(
                new VerdictRange("NORMAL", "正常", "next <= warningCount", false),
                new VerdictRange("WARNING", "重复打印告警", "warningCount < next <= maxCount", false),
                new VerdictRange("BLOCKED", "超出平台重复打印上限", "next > maxCount", true)),
            "超限默认整体拒绝；确需强制重打时必须 overrideReprint=true 并填写 overrideReason（DB 层也会校验）",
            true,
            "规则按「平台 + 对象类型」匹配，取不到平台专属规则时回落到 platform_code='*' 的通配规则；两者都没有则不限次"),
        "面单号、路由等物流数据来自调用方传入的快照：上游 10 物流与 3.1.2.3 条码尚未交付，" + "本服务不建物流表也不跨库读取",
        List.of(
            "打印次数由 print_target.print_count 权威记录，只增不减（DB 触发器强制）",
            "每份打印落一条 label_print_log 凭据，append-only 不可改",
            "渲染内容按模板与快照现算并记录 SHA-256 校验和",
            "同一任务只能打印一次；要重打请新建任务，这样「第几次」永远是真的第几次"));
  }

  // ================================================================ 打印机配置

  private static final String DEVICE_COLUMNS =
      "id,code,name,device_kind,model,paper_size,dpi,location,is_active,created_at,updated_at";

  public List<LabelPrintDevice> devices(boolean activeOnly) {
    return sql
        .fetch(
            "select "
                + DEVICE_COLUMNS
                + " from print_device where tenant_id = ?"
                + (activeOnly ? " and is_active" : "")
                + " order by code",
            tenant())
        .stream()
        .map(
            r ->
                new LabelPrintDevice(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("device_kind", PrintDeviceKind.class),
                    r.get("model", String.class),
                    r.get("paper_size", String.class),
                    r.get("dpi", Integer.class),
                    r.get("location", String.class),
                    r.get("is_active", Boolean.class),
                    r.get("created_at", OffsetDateTime.class),
                    r.get("updated_at", OffsetDateTime.class)))
        .toList();
  }

  public LabelPrintDevice createDevice(LabelPrintDeviceRequest request) {
    requireText(request.code(), "打印机编码");
    requireText(request.name(), "打印机名称");
    if (request.deviceKind() == null) throw badRequest("打印机类型不能为空");
    var paper = LabelRenderService.Paper.of(request.paperSize(), true);
    var id = UUID.randomUUID();
    sql.execute(
        "insert into print_device(id,tenant_id,code,name,device_kind,model,paper_size,dpi,location)"
            + " values(?,?,?,?,?,?,?,?,?)",
        id,
        tenant(),
        request.code().trim(),
        request.name().trim(),
        request.deviceKind().name(),
        trim(request.model()),
        paperSizeLabel(request.paperSize()),
        request.dpi(),
        trim(request.location()));
    return devices(false).stream().filter(d -> d.id().equals(id)).findFirst().orElseThrow();
  }

  public LabelPrintDevice updateDevice(UUID deviceId, LabelPrintDeviceRequest request) {
    requireExists("print_device", deviceId, "打印机不存在");
    requireText(request.code(), "打印机编码");
    requireText(request.name(), "打印机名称");
    if (request.deviceKind() == null) throw badRequest("打印机类型不能为空");
    sql.execute(
        "update print_device set code=?,name=?,device_kind=?,model=?,paper_size=?,dpi=?,location=?,"
            + "is_active=true,updated_at=?::timestamptz where tenant_id=? and id=?",
        request.code().trim(),
        request.name().trim(),
        request.deviceKind().name(),
        trim(request.model()),
        paperSizeLabel(request.paperSize()),
        request.dpi(),
        trim(request.location()),
        OffsetDateTime.now(),
        tenant(),
        deviceId);
    return devices(false).stream().filter(d -> d.id().equals(deviceId)).findFirst().orElseThrow();
  }

  // ================================================================ 模板

  private static final String TEMPLATE_COLUMNS =
      "id,code,name,object_type,paper_size,orientation,field_layout,is_active,created_at,updated_at";

  public LabelPrintTemplatePage templates(String objectType, String keyword, int page, int size) {
    var pageNo = Math.max(0, page);
    var limit = clampSize(size);
    long offset = (long) pageNo * limit;

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("t.tenant_id = ?");
    binds.add(tenant());
    if (notBlank(objectType)) {
      where.add("t.object_type = ?");
      binds.add(objectType.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add("(t.code ilike ? or t.name ilike ?)");
      String k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    var sqlWhere = " where " + String.join(" and ", where);
    var total =
        sql.fetchOne("select count(*) from print_template t" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select "
                + TEMPLATE_COLUMNS
                + ","
                + "(select count(*) from print_job j where j.tenant_id = t.tenant_id and j.template_id = t.id)"
                + " as job_count from print_template t"
                + sqlWhere
                + " order by t.object_type, t.code limit ? offset ?",
            append(binds, limit, offset).toArray());
    return new LabelPrintTemplatePage(
        rows.stream().map(LabelPrintingService::template).toList(), total, pageNo, limit);
  }

  /**
   * 模板行 → 传输模型。
   *
   * <p>{@code job_count} 只在列表查询里带（关联统计），单条查询没有这一列， 所以这里按「有就给、没有就 0」取 —— 一个 mapper 服务两种查询，不要为了取 0
   * 而多查一次。
   */
  private static LabelPrintTemplate template(org.jooq.Record r) {
    var jobCount = r.field("job_count") == null ? null : r.get("job_count", Integer.class);
    return new LabelPrintTemplate(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("object_type", PrintObjectType.class),
        r.get("paper_size", String.class),
        r.get("orientation", String.class),
        r.get("field_layout", String.class),
        r.get("is_active", Boolean.class),
        jobCount == null ? 0 : jobCount,
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  public LabelPrintTemplate createTemplate(LabelPrintTemplateRequest request) {
    requireText(request.code(), "模板编码");
    requireText(request.name(), "模板名称");
    if (request.objectType() == null) throw badRequest("打印对象类型不能为空");
    var layout = normalizeLayout(request.fieldLayout(), request.objectType());
    var paper = paperSizeLabel(request.paperSize());
    var id = UUID.randomUUID();
    sql.execute(
        "insert into print_template(id,tenant_id,code,name,object_type,paper_size,orientation,"
            // jsonb 必须显式 ?::jsonb：jOOQ 把 String 绑成 varchar，PG 会拒
            + "field_layout,is_active) values(?,?,?,?,?,?,?,?::jsonb,?)",
        id,
        tenant(),
        request.code().trim(),
        request.name().trim(),
        request.objectType().name(),
        paper,
        orientationOf(request.orientation()),
        layout,
        request.active() == null || request.active());
    return templateById(id);
  }

  public LabelPrintTemplate updateTemplate(UUID templateId, LabelPrintTemplateRequest request) {
    var current = templateById(templateId);
    requireText(request.code(), "模板编码");
    requireText(request.name(), "模板名称");
    // 模板一旦被任务用过就不能换对象类型：历史任务按类型渲染过，换类型会让旧任务的语义失真
    if (request.objectType() != null && request.objectType() != current.objectType()) {
      var used =
          sql.fetchOne(
                  "select count(*) from print_job where tenant_id = ? and template_id = ?",
                  tenant(),
                  templateId)
              .get(0, Long.class);
      if (used > 0) {
        throw conflict("该模板已被 " + used + " 个打印任务使用，不能改打印对象类型；要换类型请新建模板");
      }
    }
    var type = request.objectType() == null ? current.objectType() : request.objectType();
    var layout =
        request.fieldLayout() == null
            ? current.fieldLayout()
            : normalizeLayout(request.fieldLayout(), type);
    sql.execute(
        "update print_template set code=?,name=?,object_type=?,paper_size=?,orientation=?,"
            + "field_layout=?::jsonb,is_active=?,updated_at=?::timestamptz where tenant_id=? and id=?",
        request.code().trim(),
        request.name().trim(),
        type.name(),
        request.paperSize() == null ? current.paperSize() : paperSizeLabel(request.paperSize()),
        orientationOf(
            request.orientation() == null ? current.orientation() : request.orientation()),
        layout,
        request.active() == null ? current.active() : request.active(),
        OffsetDateTime.now(),
        tenant(),
        templateId);
    return templateById(templateId);
  }

  /**
   * 模板布局规范化：验证是对象数组且每个 block 有 key，原样存回。
   *
   * <p>刻意<b>不</b>静默回落默认布局后假装成功 —— 存进去一份坏布局，下一次渲染才发现， 那时已经贴出去一张版式不对的标签了。校验在保存时就拒。
   */
  private String normalizeLayout(String fieldLayout, PrintObjectType type) {
    if (fieldLayout == null || fieldLayout.isBlank()) return defaultLayoutJson(type);
    try {
      var root = json.readTree(fieldLayout);
      var blocks = root.path("blocks");
      if (!blocks.isArray()) throw badRequest("模板布局格式不对：blocks 必须是数组");
      if (blocks.isEmpty()) throw badRequest("模板布局不能为空数组");
      for (var node : blocks) {
        if (!node.path("key").asText("").trim().isEmpty()) continue;
        throw badRequest("模板布局里每个 block 都必须有 key");
      }
      return fieldLayout;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw badRequest("模板布局不是合法 JSON");
    }
  }

  private String defaultLayoutJson(PrintObjectType type) {
    try {
      var blocks = new ArrayList<Map<String, Object>>();
      for (var block : LabelRenderService.FieldLayout.defaultsFor(type)) {
        var map = new LinkedHashMap<String, Object>();
        map.put("key", block.key());
        map.put("label", block.label());
        map.put("scale", block.scale());
        map.put("required", block.required());
        blocks.add(map);
      }
      return json.writeValueAsString(Map.of("blocks", blocks));
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public LabelPrintTemplate templateById(UUID templateId) {
    var r =
        sql.fetchOne(
            "select " + TEMPLATE_COLUMNS + " from print_template where tenant_id = ? and id = ?",
            tenant(),
            templateId);
    if (r == null) throw notFound("模板", templateId);
    return template(r);
  }

  // ================================================================ 打印对象（快照）

  private static final String TARGET_COLUMNS =
      "id,object_type,external_ref,waybill_no,carrier_code,platform_code,ship_to_name,"
          + "ship_to_address,sku_code,sku_name,location_code,carton_code,package_count,weight_kg,"
          + "remark,print_count,first_printed_at,last_printed_at,created_at";

  public LabelPrintTargetPage targets(
      String objectType,
      String externalRef,
      String keyword,
      Integer minPrintCount,
      int page,
      int size) {
    var pageNo = Math.max(0, page);
    var limit = clampSize(size);
    long offset = (long) pageNo * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("tenant_id = ?");
    binds.add(tenant());
    if (notBlank(objectType)) {
      where.add("object_type = ?");
      binds.add(objectType.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(externalRef)) {
      where.add("external_ref = ?");
      binds.add(externalRef.trim());
    }
    if (notBlank(keyword)) {
      where.add(
          "(external_ref ilike ? or waybill_no ilike ? or sku_code ilike ?"
              + " or location_code ilike ? or carton_code ilike ? or carrier_code ilike ?)");
      var k = "%" + keyword.trim() + "%";
      for (int i = 0; i < 6; i++) binds.add(k);
    }
    if (minPrintCount != null && minPrintCount > 0) {
      where.add("print_count >= ?");
      binds.add(minPrintCount);
    }
    var sqlWhere = " where " + String.join(" and ", where);
    var total =
        sql.fetchOne("select count(*) from print_target" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select "
                + TARGET_COLUMNS
                + " from print_target"
                + sqlWhere
                + " order by print_count desc, created_at desc, id limit ? offset ?",
            append(binds, limit, offset).toArray());
    return new LabelPrintTargetPage(
        rows.stream().map(r -> withVerdict(target(r))).toList(), total, pageNo, limit);
  }

  /**
   * 打印对象列表 + <b>下一次打印的判定</b>。
   *
   * <p>把判定前置到列表里，是因为现场最需要的判断是「我现在去按打印会不会超限」， 等到点了打印才被拒已经晚了（标签纸都进了机器）。
   */
  private LabelPrintTarget withVerdict(LabelPrintTarget t) {
    var rule = ruleFor(t.platformCode(), t.objectType());
    int next = t.printCount() + 1;
    // 没有规则 = 该平台对此类型不限次：判定仍是 NORMAL（次数照记、凭据照留），
    // 阈值两格留 null，前端据此显示「不限次」而不是显示成 0 次上限。
    return new LabelPrintTarget(
        t.id(),
        t.objectType(),
        t.externalRef(),
        t.waybillNo(),
        t.carrierCode(),
        t.platformCode(),
        t.shipToName(),
        t.shipToAddress(),
        t.skuCode(),
        t.skuName(),
        t.locationCode(),
        t.cartonCode(),
        t.packageCount(),
        t.weightKg(),
        t.remark(),
        t.printCount(),
        t.firstPrintedAt(),
        t.lastPrintedAt(),
        verdictOf(rule, next),
        rule == null ? null : rule.warningCount(),
        rule == null ? null : rule.maxCount(),
        t.createdAt());
  }

  private LabelPrintTarget target(org.jooq.Record r) {
    return new LabelPrintTarget(
        r.get("id", UUID.class),
        r.get("object_type", PrintObjectType.class),
        r.get("external_ref", String.class),
        r.get("waybill_no", String.class),
        r.get("carrier_code", String.class),
        r.get("platform_code", String.class),
        r.get("ship_to_name", String.class),
        r.get("ship_to_address", String.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("location_code", String.class),
        r.get("carton_code", String.class),
        r.get("package_count", Integer.class),
        r.get("weight_kg", BigDecimal.class),
        r.get("remark", String.class),
        r.get("print_count", Integer.class),
        r.get("first_printed_at", OffsetDateTime.class),
        r.get("last_printed_at", OffsetDateTime.class),
        ReprintVerdict.NORMAL,
        null,
        null,
        r.get("created_at", OffsetDateTime.class));
  }

  public LabelPrintTarget targetById(UUID targetId) {
    var r =
        sql.fetchOne(
            "select " + TARGET_COLUMNS + " from print_target where tenant_id = ? and id = ?",
            tenant(),
            targetId);
    if (r == null) throw notFound("打印对象", targetId);
    return withVerdict(target(r));
  }

  /**
   * 登记打印对象（快照）。
   *
   * <p>上游 10 物流未交付，所以这里是面单数据进入本系统的唯一入口；调用方传什么就存什么， 本服务不去别处补数据（跨库读取是被禁止的）。重复的 external_ref 返回已有的那条，
   * 而不是报错 —— 上游重推同一张面单是很正常的。
   */
  public LabelPrintTarget registerTarget(LabelPrintTargetRequest request) {
    if (request.objectType() == null) throw badRequest("打印对象类型不能为空");
    requireText(request.externalRef(), "外部引用");
    var ref = request.externalRef().trim();
    if (ref.length() > 200) throw badRequest("外部引用过长（上限 200 字符）");
    if (request.weightKg() != null
        && (request.weightKg().signum() < 0 || request.weightKg().scale() > 6)) {
      throw badRequest("重量必须是非负数且最多 6 位小数");
    }
    if (request.packageCount() != null && request.packageCount() < 0) {
      throw badRequest("件数不能为负");
    }

    var existing =
        sql.fetchOne(
            "select id from print_target where tenant_id = ? and object_type = ? and external_ref = ?",
            tenant(),
            request.objectType().name(),
            ref);
    if (existing != null) return targetById(existing.get("id", UUID.class));

    var id = UUID.randomUUID();
    sql.execute(
        "insert into print_target(id,tenant_id,object_type,external_ref,waybill_no,carrier_code,"
            + "platform_code,ship_to_name,ship_to_address,sku_code,sku_name,location_code,"
            + "carton_code,package_count,weight_kg,remark) values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        tenant(),
        request.objectType().name(),
        ref,
        trim(request.waybillNo()),
        trim(request.carrierCode()),
        trim(request.platformCode()),
        trim(request.shipToName()),
        trim(request.shipToAddress()),
        trim(request.skuCode()),
        trim(request.skuName()),
        trim(request.locationCode()),
        trim(request.cartonCode()),
        request.packageCount(),
        request.weightKg(),
        trim(request.remark()));
    return targetById(id);
  }

  // ================================================================ 重复打印规则

  private static final String RULE_COLUMNS =
      "id,platform_code,object_type,warning_count,max_count,note,created_at,updated_at";

  public List<LabelReprintRule> rules() {
    return sql
        .fetch(
            "select "
                + RULE_COLUMNS
                + " from print_reprint_rule where tenant_id = ?"
                + " order by platform_code, object_type",
            tenant())
        .stream()
        .map(LabelPrintingService::rule)
        .toList();
  }

  private static LabelReprintRule rule(org.jooq.Record r) {
    return new LabelReprintRule(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("object_type", PrintObjectType.class),
        r.get("warning_count", Integer.class),
        r.get("max_count", Integer.class),
        r.get("note", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  /**
   * 取某平台的规则：先找平台专属，再回落 {@code '*'} 通配规则，都没有返回 null（不限次）。
   *
   * <p>通配规则的存在是必要的：租户往往先给一个全局口径（「所有平台面单最多打 3 次」）， 之后再对某个平台收紧，而不是反过来一上来就把每个平台都配一遍。
   */
  private LabelReprintRule ruleFor(String platformCode, PrintObjectType type) {
    if (notBlank(platformCode)) {
      var exact =
          sql.fetchOne(
              "select "
                  + RULE_COLUMNS
                  + " from print_reprint_rule where tenant_id = ?"
                  + " and platform_code = ? and object_type = ?",
              tenant(),
              platformCode.trim().toUpperCase(Locale.ROOT),
              type.name());
      if (exact != null) return rule(exact);
    }
    var wildcard =
        sql.fetchOne(
            "select "
                + RULE_COLUMNS
                + " from print_reprint_rule where tenant_id = ?"
                + " and platform_code = '*' and object_type = ?",
            tenant(),
            type.name());
    return wildcard == null ? null : rule(wildcard);
  }

  /** 判定：{@code next} 落在哪一段。没规则 → 永远 NORMAL（但仍然计数、仍然留凭据）。 */
  private static ReprintVerdict verdictOf(LabelReprintRule rule, int next) {
    if (rule == null || rule.maxCount() == null) return ReprintVerdict.NORMAL;
    if (next > rule.maxCount()) return ReprintVerdict.BLOCKED;
    if (rule.warningCount() != null && next > rule.warningCount()) return ReprintVerdict.WARNING;
    return ReprintVerdict.NORMAL;
  }

  public LabelReprintRule upsertRule(LabelReprintRuleRequest request) {
    requireText(request.platformCode(), "平台编码");
    if (request.objectType() == null) throw badRequest("打印对象类型不能为空");
    validateRule(request);
    var platform = request.platformCode().trim().toUpperCase(Locale.ROOT);
    var now = OffsetDateTime.now();
    var existing =
        sql.fetchOne(
            "select id from print_reprint_rule where tenant_id = ? and platform_code = ? and object_type = ?",
            tenant(),
            platform,
            request.objectType().name());
    if (existing != null) {
      var id = existing.get("id", UUID.class);
      sql.execute(
          "update print_reprint_rule set warning_count=?,max_count=?,note=?,updated_at=?::timestamptz"
              + " where tenant_id=? and id=?",
          request.warningCount(),
          request.maxCount(),
          trim(request.note()),
          now,
          tenant(),
          id);
      return ruleById(id);
    }
    var id = UUID.randomUUID();
    sql.execute(
        "insert into print_reprint_rule(id,tenant_id,platform_code,object_type,warning_count,max_count,note)"
            + " values(?,?,?,?,?,?,?)",
        id,
        tenant(),
        platform,
        request.objectType().name(),
        request.warningCount(),
        request.maxCount(),
        trim(request.note()));
    return ruleById(id);
  }

  private void validateRule(LabelReprintRuleRequest request) {
    if (request.warningCount() != null && request.warningCount() < 1) {
      throw badRequest("告警次数必须大于等于 1");
    }
    if (request.maxCount() != null && request.maxCount() < 1) {
      throw badRequest("上限次数必须大于等于 1");
    }
    if (request.warningCount() != null
        && request.maxCount() != null
        && request.warningCount() > request.maxCount()) {
      throw badRequest("告警次数不能大于上限次数（否则「允许但告警」这个区间不存在）");
    }
  }

  private LabelReprintRule ruleById(UUID ruleId) {
    var r =
        sql.fetchOne(
            "select " + RULE_COLUMNS + " from print_reprint_rule where tenant_id = ? and id = ?",
            tenant(),
            ruleId);
    if (r == null) throw notFound("重复打印规则", ruleId);
    return rule(r);
  }

  // ================================================================ 打印任务

  private static final String JOB_COLUMNS =
      "j.id,j.job_no,j.template_id,j.printer_id,j.status,j.copies,j.target_count,j.label_count,"
          + "j.reprint_warning_count,j.blocked_count,j.override_reprint,j.override_reason,j.remark,"
          + "j.created_by,j.created_at,j.queued_at,j.started_at,j.finished_at";
  private static final String JOB_FROM =
      " from print_job j"
          + " left join print_template t on t.tenant_id = j.tenant_id and t.id = j.template_id"
          + " left join print_device d on d.tenant_id = j.tenant_id and d.id = j.printer_id";

  public LabelPrintJobPage jobs(
      String status, String objectType, String keyword, int page, int size) {
    var pageNo = Math.max(0, page);
    var limit = clampSize(size);
    long offset = (long) pageNo * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("j.tenant_id = ?");
    binds.add(tenant());
    if (notBlank(status)) {
      where.add("j.status = ?");
      binds.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(objectType)) {
      where.add(
          "exists (select 1 from print_job_item i where i.tenant_id = j.tenant_id"
              + " and i.job_id = j.id and i.object_type = ?)");
      binds.add(objectType.trim().toUpperCase(Locale.ROOT));
    }
    if (notBlank(keyword)) {
      where.add("(j.job_no ilike ? or j.remark ilike ?)");
      var k = "%" + keyword.trim() + "%";
      binds.add(k);
      binds.add(k);
    }
    var sqlWhere = " where " + String.join(" and ", where);
    var total =
        sql.fetchOne("select count(*)" + JOB_FROM + sqlWhere, binds.toArray()).get(0, Long.class);
    var rows =
        sql.fetch(
            "select "
                + JOB_COLUMNS
                + ",t.name as template_name,d.name as printer_name"
                + JOB_FROM
                + sqlWhere
                + " order by j.created_at desc, j.id limit ? offset ?",
            append(binds, limit, offset).toArray());
    return new LabelPrintJobPage(
        rows.stream().map(LabelPrintingService::job).toList(), total, pageNo, limit);
  }

  private static LabelPrintJob job(org.jooq.Record r) {
    return new LabelPrintJob(
        r.get("id", UUID.class),
        r.get("job_no", String.class),
        r.get("template_id", UUID.class),
        r.get("template_name", String.class),
        r.get("printer_id", UUID.class),
        r.get("printer_name", String.class),
        r.get("status", PrintJobStatus.class),
        r.get("copies", Integer.class),
        r.get("target_count", Integer.class),
        r.get("label_count", Integer.class),
        r.get("reprint_warning_count", Integer.class),
        r.get("blocked_count", Integer.class),
        r.get("override_reprint", Boolean.class),
        r.get("override_reason", String.class),
        r.get("remark", String.class),
        r.get("created_by", String.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("queued_at", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class));
  }

  public LabelPrintJob jobById(UUID jobId) {
    var r =
        sql.fetchOne(
            "select "
                + JOB_COLUMNS
                + ",t.name as template_name,d.name as printer_name"
                + JOB_FROM
                + " where j.tenant_id = ? and j.id = ?",
            tenant(),
            jobId);
    if (r == null) throw notFound("打印任务", jobId);
    return job(r);
  }

  public LabelPrintJobDetail jobDetail(UUID jobId) {
    var head = jobById(jobId);
    var items =
        sql
            .fetch(
                "select id,line_no,target_id,external_ref,object_type,print_count_at_render,"
                    + "content_format,content_checksum,length(rendered_content) as content_bytes"
                    + " from print_job_item where tenant_id = ? and job_id = ? order by line_no",
                tenant(),
                jobId)
            .stream()
            .map(
                r ->
                    new LabelPrintJobItem(
                        r.get("id", UUID.class),
                        r.get("line_no", Long.class),
                        r.get("target_id", UUID.class),
                        r.get("external_ref", String.class),
                        r.get("object_type", PrintObjectType.class),
                        r.get("print_count_at_render", Integer.class),
                        r.get("content_format", PrintContentFormat.class),
                        r.get("content_checksum", String.class),
                        r.get("content_bytes", Integer.class)))
            .toList();
    return new LabelPrintJobDetail(head, items);
  }

  /**
   * 建打印任务（批量：一次给多个对象，真批量展开）。
   *
   * <p>建任务时就做一次重复打印判定，并把结论（告警数 / 超限数）落在任务上： 判定要等到真打印那一刻才做的话，现场可能已经把纸塞进打印机了才发现超限。 超限且未 override
   * 时<b>整个建单被拒</b>，并把超限对象逐条列出来。
   */
  public LabelPrintJob createJob(LabelPrintJobRequest request, UUID actorId) {
    if (request.templateId() == null) throw badRequest("模板不能为空");
    if (request.targetIds() == null || request.targetIds().isEmpty()) {
      throw badRequest("至少要选一个打印对象");
    }
    if (request.targetIds().size() > MAX_BATCH_TARGETS) {
      throw badRequest("单次批量打印最多 " + MAX_BATCH_TARGETS + " 个对象，请分批打印");
    }
    var copies = request.copies() == null ? 1 : request.copies();
    if (copies < 1 || copies > 20) throw badRequest("打印份数必须在 1 到 20 之间");
    var override = request.overrideReprint() != null && request.overrideReprint();
    if (override && !notBlank(request.overrideReason())) {
      throw badRequest("强制重打必须填写理由");
    }

    var template = templateById(request.templateId());
    if (!template.active()) throw conflict("模板已停用，不能用于新建打印任务");
    if (request.printerId() != null) {
      var device =
          sql.fetchOne(
              "select device_kind,is_active from print_device where tenant_id = ? and id = ?",
              tenant(),
              request.printerId());
      if (device == null) throw notFound("打印机", request.printerId());
      if (!device.get("is_active", Boolean.class)) throw conflict("打印机已停用");
    }

    var targets = loadTargets(request.targetIds());

    // 模板与对象的类型必须一致：拿装箱单模板去打库位标签，打出来的东西没有意义
    var mismatched = targets.stream().filter(t -> t.objectType() != template.objectType()).toList();
    if (!mismatched.isEmpty()) {
      throw badRequest(
          "模板与打印对象类型不一致：模板是「"
              + template.objectType().label()
              + "」，但选中的对象里有「"
              + mismatched.get(0).objectType().label()
              + "」（"
              + mismatched.get(0).externalRef()
              + "）");
    }

    // 重复打印判定：用「下一次序号 = 当前次数 + 份数」
    var warned = new ArrayList<String>();
    var blocked = new ArrayList<String>();
    for (var t : targets) {
      var rule = ruleFor(t.platformCode(), t.objectType());
      var verdict = verdictOf(rule, t.printCount() + copies);
      if (verdict == ReprintVerdict.BLOCKED) {
        blocked.add(
            t.externalRef()
                + "（已印 "
                + t.printCount()
                + " 次，"
                + (rule == null || rule.platformCode() == null ? "—" : rule.platformCode())
                + " 上限 "
                + (rule == null ? "—" : rule.maxCount())
                + " 次）");
      } else if (verdict == ReprintVerdict.WARNING) {
        warned.add(t.externalRef() + "（将印到第 " + (t.printCount() + copies) + " 次）");
      }
    }
    if (!blocked.isEmpty() && !override) {
      throw conflict(
          "以下对象已超出平台重复打印上限，本次建单被整体拒绝：" + String.join("；", blocked) + "。确认需要强制重打时，请勾选强制重打并填写理由。");
    }

    var jobId = UUID.randomUUID();
    var now = OffsetDateTime.now();
    sql.execute(
        "insert into print_job(id,tenant_id,job_no,template_id,printer_id,status,copies,remark,"
            + "target_count,label_count,reprint_warning_count,blocked_count,override_reprint,"
            + "override_reason,created_by,created_at)"
            + " values(?,?,?,?,?,'DRAFT',?,?,?,?,?,?,?,?,?,?::timestamptz)",
        jobId,
        tenant(),
        nextJobNo(),
        request.templateId(),
        request.printerId(),
        copies,
        trim(request.remark()),
        targets.size(),
        targets.size() * copies,
        warned.size(),
        blocked.isEmpty() ? 0 : blocked.size(),
        override,
        trim(request.overrideReason()),
        actorId,
        now);

    int lineNo = 1;
    for (var t : targets) {
      var rendered = LabelRenderService.render(t, template);
      sql.execute(
          "insert into print_job_item(id,tenant_id,job_id,target_id,line_no,object_type,external_ref,"
              + "print_count_at_render,content_format,rendered_content,content_checksum)"
              + " values(?,?,?,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          tenant(),
          jobId,
          t.id(),
          lineNo++,
          t.objectType().name(),
          t.externalRef(),
          t.printCount(),
          rendered.format().name(),
          rendered.content(),
          rendered.checksum());
    }
    return jobById(jobId);
  }

  private List<LabelPrintTarget> loadTargets(List<UUID> ids) {
    var distinct = new ArrayList<>(new java.util.LinkedHashSet<>(ids));
    var found = new ArrayList<LabelPrintTarget>();
    for (var id : distinct) {
      var r =
          sql.fetchOne(
              "select " + TARGET_COLUMNS + " from print_target where tenant_id = ? and id = ?",
              tenant(),
              id);
      // 跨租户 / 不存在一律 404：不泄露「这个 id 在别的租户下存在」
      if (r == null) throw notFound("打印对象", id);
      found.add(target(r));
    }
    return found;
  }

  /**
   * 执行打印：真落库真计数。
   *
   * <p>整个动作在调用方的事务里（与 AccessService.run 同一个 TransactionTemplate）：
   *
   * <ol>
   *   <li>推进状态 DRAFT → QUEUED → PRINTING → COMPLETED（每一步都过 DB 状态机）
   *   <li>每个对象按份数<b>原子累加</b>权威次数，RETURNING 拿到本次序号
   *   <li>每份写一条 append-only 凭据，序号 = 权威次数
   * </ol>
   *
   * <p>任何一步失败整单回滚：次数与凭据要么都在，要么都不在 —— 不会出现「次数加了但没凭据」。
   */
  public LabelPrintExecution executeJob(UUID jobId, UUID actorId) {
    var job = jobById(jobId);
    if (job.status() != PrintJobStatus.DRAFT && job.status() != PrintJobStatus.QUEUED) {
      throw conflict("打印任务当前状态为「" + job.status().label() + "」，不能再次执行打印");
    }
    // 模板必须还在（被停用 / 删掉的任务打不出来）。内容不重算 —— 渲染在建任务时就冻结了，
    // 这里用的是 print_job_item 里落库的那一份，所以模板后来改了也不影响已建的凭据。
    requireExists("print_template", job.templateId(), "模板不存在，打印内容无处可依");
    var items =
        sql.fetch(
            "select id,line_no,target_id,object_type,external_ref,print_count_at_render,"
                + "content_format,content_checksum"
                + " from print_job_item where tenant_id = ? and job_id = ? order by line_no",
            tenant(),
            jobId);

    var now = OffsetDateTime.now();
    if (job.status() == PrintJobStatus.DRAFT) {
      sql.execute(
          "update print_job set status='QUEUED',queued_at=?::timestamptz where tenant_id=? and id=?",
          now,
          tenant(),
          jobId);
    }
    sql.execute(
        "update print_job set status='PRINTING',started_at=?::timestamptz where tenant_id=? and id=?",
        now,
        tenant(),
        jobId);

    // 落库前再判一次超限：建单到执行之间次数可能已经被别的任务推上去了
    var blocked = new ArrayList<String>();
    for (var item : items) {
      var t = targetById(item.get("target_id", UUID.class));
      var rule = ruleFor(t.platformCode(), t.objectType());
      var verdict = verdictOf(rule, t.printCount() + job.copies());
      if (verdict == ReprintVerdict.BLOCKED) {
        blocked.add(item.get("external_ref", String.class));
      }
    }
    if (!blocked.isEmpty() && !job.overrideReprint()) {
      throw conflict(
          "以下对象在执行时已超出平台重复打印上限：" + String.join("；", blocked) + "。如确需强制重打，请取消本任务后带理由重新建单。");
    }

    var logs = new ArrayList<LabelPrintLog>();
    var counts = new ArrayList<LabelPrintTargetCount>();
    var warnedTargets = 0;
    for (var item : items) {
      var targetId = item.get("target_id", UUID.class);
      var itemId = item.get("id", UUID.class);
      var format = item.get("content_format", PrintContentFormat.class);
      var checksum = item.get("content_checksum", String.class);
      var objectType = item.get("object_type", PrintObjectType.class);
      var externalRef = item.get("external_ref", String.class);
      var t = targetById(targetId);
      var rule = ruleFor(t.platformCode(), t.objectType());

      Integer finalCount = null;
      for (int copy = 1; copy <= job.copies(); copy++) {
        // 原子累加并取回新次数：并发重打同一条面单时各自拿到不同序号，不会互相覆盖
        var after =
            sql.fetchOne(
                    "update print_target set print_count = print_count + 1,"
                        + " last_printed_at = ?::timestamptz,"
                        + " first_printed_at = coalesce(first_printed_at, ?::timestamptz)"
                        + " where tenant_id = ? and id = ? returning print_count",
                    now,
                    now,
                    tenant(),
                    targetId)
                .get("print_count", Integer.class);
        var verdict = verdictOf(rule, after);
        var logId = UUID.randomUUID();
        sql.execute(
            "insert into label_print_log(id,tenant_id,job_id,job_item_id,target_id,object_type,"
                + "external_ref,print_sequence,copy_index,content_format,content_checksum,"
                + "reprint_warning,override_applied,operator_id,printed_at)"
                + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
            logId,
            tenant(),
            jobId,
            itemId,
            targetId,
            objectType.name(),
            externalRef,
            after,
            copy,
            format.name(),
            checksum,
            verdict == ReprintVerdict.WARNING,
            job.overrideReprint() && verdict == ReprintVerdict.BLOCKED,
            actorId,
            now);
        logs.add(
            new LabelPrintLog(
                logId,
                jobId,
                itemId,
                targetId,
                externalRef,
                objectType,
                after,
                copy,
                format,
                checksum,
                verdict == ReprintVerdict.WARNING,
                job.overrideReprint() && verdict == ReprintVerdict.BLOCKED,
                actorId == null ? null : actorId.toString(),
                now));
        finalCount = after;
      }
      if (verdictOf(rule, finalCount) == ReprintVerdict.WARNING) warnedTargets++;
      counts.add(new LabelPrintTargetCount(targetId, externalRef, finalCount));
    }

    sql.execute(
        "update print_job set status='COMPLETED',finished_at=?::timestamptz where tenant_id=? and id=?",
        now,
        tenant(),
        jobId);
    return new LabelPrintExecution(jobById(jobId), logs, counts, warnedTargets, job.blockedCount());
  }

  /** 取消任务（只能从 DRAFT / QUEUED 取消；已打印的不能取消 —— 凭据已经发生了）。 */
  public LabelPrintJob cancelJob(UUID jobId) {
    var job = jobById(jobId);
    if (job.status() == PrintJobStatus.CANCELLED) return job;
    if (job.status() != PrintJobStatus.DRAFT && job.status() != PrintJobStatus.QUEUED) {
      throw conflict("只有草稿或已排队的打印任务可以取消；当前状态为「" + job.status().label() + "」");
    }
    sql.execute(
        "update print_job set status='CANCELLED',finished_at=?::timestamptz where tenant_id=? and id=?",
        OffsetDateTime.now(),
        tenant(),
        jobId);
    return jobById(jobId);
  }

  /**
   * 取渲染内容用于下载。
   *
   * <p>返回的是<b>当初打印时落库的那一份</b>（连同校验和），不是现算的：这样「打印日志里那条 凭据指向的内容」永远等于下载到的东西，不会因为模板后来被改而对不上。
   */
  public String renderedContent(UUID jobId, UUID itemId, PrintContentFormat expected) {
    var r =
        sql.fetchOne(
            "select content_format,rendered_content,content_checksum from print_job_item"
                + " where tenant_id = ? and job_id = ? and id = ?",
            tenant(),
            jobId,
            itemId);
    if (r == null) throw notFound("打印任务明细", itemId);
    var checksum = r.get("content_checksum", String.class);
    var content = r.get("rendered_content", String.class);
    // 校验和在这里再验一次：凭据被改过（append-only 挡的是应用角色，不是 DBA）时必须拒
    if (!LabelRenderService.sha256(content).equals(checksum)) {
      throw new IllegalStateException("打印内容校验和不匹配，内容可能已被篡改：" + itemId);
    }
    var format = r.get("content_format", PrintContentFormat.class);
    if (expected != null && expected != format) {
      throw badRequest("该明细的打印格式是「" + format.label() + "」，与请求的格式不一致");
    }
    return content;
  }

  public PrintContentFormat itemFormat(UUID jobId, UUID itemId) {
    var r =
        sql.fetchOne(
            "select content_format from print_job_item where tenant_id = ? and job_id = ? and id = ?",
            tenant(),
            jobId,
            itemId);
    if (r == null) throw notFound("打印任务明细", itemId);
    return r.get("content_format", PrintContentFormat.class);
  }

  // ================================================================ 打印日志

  private static final String LOG_COLUMNS =
      "id,job_id,job_item_id,target_id,object_type,external_ref,print_sequence,copy_index,"
          + "content_format,content_checksum,reprint_warning,override_applied,operator_id,printed_at";

  public LabelPrintLogPage logs(
      UUID jobId, UUID targetId, Boolean warningOnly, int page, int size) {
    var pageNo = Math.max(0, page);
    var limit = clampSize(size);
    long offset = (long) pageNo * limit;
    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    where.add("tenant_id = ?");
    binds.add(tenant());
    if (jobId != null) {
      where.add("job_id = ?");
      binds.add(jobId);
    }
    if (targetId != null) {
      where.add("target_id = ?");
      binds.add(targetId);
    }
    if (warningOnly != null && warningOnly) where.add("reprint_warning");
    var sqlWhere = " where " + String.join(" and ", where);
    var total =
        sql.fetchOne("select count(*) from label_print_log" + sqlWhere, binds.toArray())
            .get(0, Long.class);
    var rows =
        sql.fetch(
            "select "
                + LOG_COLUMNS
                + " from label_print_log"
                + sqlWhere
                + " order by printed_at desc, print_sequence desc, id limit ? offset ?",
            append(binds, limit, offset).toArray());
    return new LabelPrintLogPage(
        rows.stream().map(LabelPrintingService::log).toList(), total, pageNo, limit);
  }

  /** 某对象对象的完整打印历史（含每一次是第几次、有没有告警、是不是强制放行的）。 */
  public List<LabelPrintLog> targetHistory(UUID targetId) {
    requireExists("print_target", targetId, "打印对象不存在");
    return sql
        .fetch(
            "select "
                + LOG_COLUMNS
                + " from label_print_log where tenant_id = ? and target_id = ?"
                + " order by print_sequence desc, printed_at",
            tenant(),
            targetId)
        .stream()
        .map(LabelPrintingService::log)
        .toList();
  }

  private static LabelPrintLog log(org.jooq.Record r) {
    return new LabelPrintLog(
        r.get("id", UUID.class),
        r.get("job_id", UUID.class),
        r.get("job_item_id", UUID.class),
        r.get("target_id", UUID.class),
        r.get("external_ref", String.class),
        r.get("object_type", PrintObjectType.class),
        r.get("print_sequence", Integer.class),
        r.get("copy_index", Integer.class),
        r.get("content_format", PrintContentFormat.class),
        r.get("content_checksum", String.class),
        r.get("reprint_warning", Boolean.class),
        r.get("override_applied", Boolean.class),
        r.get("operator_id", String.class),
        r.get("printed_at", OffsetDateTime.class));
  }

  // ================================================================ 助手

  /** 作业号：日期 + 当日序号。并发建单可能撞号，撞了就换一个再试。 */
  private String nextJobNo() {
    var prefix =
        JOB_PREFIX
            + java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "")
            + "-";
    for (int attempt = 0; attempt < 20; attempt++) {
      var candidate =
          prefix
              + String.format(
                  "%05d", java.util.concurrent.ThreadLocalRandom.current().nextInt(0, 99999));
      var exists =
          sql.fetchOne(
                  "select count(*) from print_job where tenant_id = ? and job_no = ?",
                  tenant(),
                  candidate)
              .get(0, Long.class);
      if (exists == 0) return candidate;
    }
    throw new IllegalStateException("作业号生成失败：已重试 20 次仍冲突");
  }

  private UUID tenant() {
    return sql.fetchOne("select current_setting('app.current_tenant',true)::uuid as t")
        .get("t", UUID.class);
  }

  private void requireExists(String table, UUID id, String message) {
    var exists =
        sql.fetchOne(
                "select count(*) from " + table + " where tenant_id = ? and id = ?", tenant(), id)
            .get(0, Long.class);
    if (exists == 0) throw notFound("对象", id, message);
  }

  private static List<Object> append(List<Object> binds, Object... more) {
    binds.addAll(List.of(more));
    return binds;
  }

  private static int clampSize(int size) {
    return Math.min(MAX_PAGE_SIZE, Math.max(1, size <= 0 ? 50 : size));
  }

  private static boolean notBlank(String value) {
    return value != null && !value.isBlank();
  }

  private static String trim(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static void requireText(String value, String field) {
    if (!notBlank(value)) throw badRequest(field + "不能为空");
  }

  private static String paperSizeLabel(String paperSize) {
    return notBlank(paperSize) ? paperSize.trim().toUpperCase(Locale.ROOT) : "A4";
  }

  private static String orientationOf(String orientation) {
    return "LANDSCAPE".equalsIgnoreCase(trim(orientation)) ? "LANDSCAPE" : "PORTRAIT";
  }

  private static ResponseStatusException badRequest(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private static ResponseStatusException conflict(String message) {
    return new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static ResponseStatusException notFound(String label, UUID id) {
    return notFound(label, id, label + "不存在");
  }

  private static ResponseStatusException notFound(String label, UUID id, String message) {
    return new ResponseStatusException(HttpStatus.NOT_FOUND, message + "（" + id + "）");
  }
}
