package com.starcon.platform;

import static com.starcon.platform.DataMigrationModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.1.3.2「数据迁移」的服务层。
 *
 * <p>蓝图原文：「<b>职责</b>：支持从其他 ERP 迁移历史数据：映射配置（外部字段 → 内部模型）、
 * 试运行预览、正式迁移、失败回滚与核对报告。<b>输入</b>：源数据文件；字段映射配置。
 * <b>输出</b>：迁移任务 + 迁移核对报告（成功/失败/跳过数量与原因）。
 * <b>关系</b>：上游 = 18.1.3.1；下游 = 各业务域。
 * <b>历史数据迁移必须支持试运行</b>，直接正式跑会产生大量脏数据难以回退。」
 *
 * <p><b>八条纪律：</p>
 *
 * <ol>
 *   <li><b>复用 18.1.3.1 的骨架，不另起体系</b>：文件存储用 {@link ImportExportStorage}、
 *       CSV 读写用 {@link ImportExportCsv}、异步执行用 {@link DataMigrationExecutor}、
 *       鉴权与租户用 {@link AccessService}。本模块只新增「迁移」自己的语义。
 *   <li><b>试运行一个写端点都不调</b>：{@link Mode#DRY_RUN} 走的是**只读**分支 ——
 *       只查目标库判断「是不是已经有了」。数据库层还有一道触发器兜底
 *       （试运行的行不许带 {@code upstream_id}）。
 *   <li><b>回滚是真的回滚</b>：按逐行记下的 {@code upstream_id} 去目标库**真删**。
 *       删不掉的行如实记「未删除」并写明原因 —— 不把「提交了删除请求」当成「删掉了」。
 *   <li><b>核对报告的三个口径加起来等于处理过的行数</b>（数据库 CHECK 钉死）：
 *       成功 / 失败 / 跳过加不到总数上，那份报告在账上就对不平。
 *   <li><b>「查不清」不等于「不存在」</b>：目标库查询被截断时**跳过**这一行并写明原因，
 *       绝不把「可能已存在」的记录再迁一遍（那会造出重复主数据）。
 *   <li><b>跨服务只走授权端口</b>：本模块一条跨库 SQL 都没有；只转发调用方自己的 bearer。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话。
 *   <li><b>时间一律 DB 时钟</b>：落库时间戳取 {@code clock_timestamp()}，
 *       用 {@link OffsetDateTime} 绑定并在 values 位置写 {@code ?::timestamptz}。
 * </ol>
 */
@Service
public class DataMigrationService {

  /** 列表分页上限。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 源文件大小上限（10 MB）：再大的文件应该走对象存储直传。 */
  static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;

  /** 源文件最多多少行。 */
  static final int MAX_SOURCE_ROWS = 20_000;

  /** 报告文件的默认有效期（小时）。 */
  static final long DEFAULT_EXPIRY_HOURS = 24;

  private final DSLContext sql;
  private final ImportExportStorage storage;
  private final ObjectMapper json;
  private final DataMigrationCatalogPort catalog;

  public DataMigrationService(
      DSLContext sql,
      ImportExportStorage storage,
      ObjectMapper json,
      DataMigrationCatalogPort catalog) {
    this.sql = sql;
    this.storage = storage;
    this.json = json;
    this.catalog = catalog;
  }

  // ================================================================ 工具

  private static void check(boolean condition, String message) {
    if (!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  /** 状态冲突（409）：不是「参数不对」，是「当前状态不允许」。 */
  private static void conflict(String message) {
    throw new ResponseStatusException(HttpStatus.CONFLICT, message);
  }

  private static String required(String value, String label, int max) {
    var trimmed = value == null ? "" : value.trim();
    check(!trimmed.isEmpty(), label + "不能为空");
    check(trimmed.length() <= max, label + "长度不能超过 " + max + " 个字符");
    return trimmed;
  }

  /** 事务局部 GUC：租户由 AccessService 绑定，这里读的是**同一个**绑定。 */
  private UUID currentTenant() {
    var raw =
        sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    check(raw != null && !raw.isBlank(), "缺少可信租户上下文");
    return UUID.fromString(raw);
  }

  /** DB 时钟：读一次 {@code clock_timestamp()} 再绑定。 */
  private OffsetDateTime dbNow() {
    return sql.fetchOne("select clock_timestamp()").get(0, OffsetDateTime.class);
  }

  private long count(String sqlText, Object... args) {
    return sql.fetchOne(sqlText, args).get(0, Long.class);
  }

  private String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (IOException e) {
      throw new IllegalStateException("迁移快照序列化失败", e);
    }
  }

  /** 内容指纹（sha256，十六进制 64 位）。 */
  static String sha256(byte[] content) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /**
   * catalog 的编码规则（与 catalog 的 {@code CatalogService.code()} 逐字同构）。
   *
   * <p>🔴 本地先判一遍：不然上游会返回 400，而那一堆 400 里混着「版本冲突」，
   * 逐行分不清是「编码不合法」还是「被别人改了」。
   */
  static String normalizeCode(String raw) {
    var normalized = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
    if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{0,63}")) {
      throw new IllegalArgumentException("编码请使用英文字母、数字、下划线或短横线（最长 64 位）");
    }
    return normalized;
  }

  // ================================================================ 能力声明

  /**
   * 18.1.3.2 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样、归谁」。
   *
   * <p>🔴 <b>没接通的格子如实写 {@code available=false}</b>，并写明缺什么、归谁 ——
   * 绝不为让测试绿而写成假的 {@code true}。
   */
  public DataMigrationCapabilities capabilities() {
    var capabilities =
        List.of(
            new DataMigrationCapability(
                "18.1.3.2",
                "映射配置（外部字段 → 内部模型）",
                true,
                "platform_migration_mapping 表存**字段级映射**（源列名 → 内部字段键 + 转换方式 + 兜底值）；"
                    + "目标模型与字段清单由**一份代码**（DataMigrationTargets）定义，"
                    + "映射校验与迁移执行共用它 —— 两处各写一份字段名必然对不上",
                "",
                "只给**有真实写入 / 删除对**的域建映射：没有 create/delete 对的域，"
                    + "「失败回滚」只能是假的"),
            new DataMigrationCapability(
                "18.1.3.2",
                "试运行预览",
                true,
                "Mode.DRY_RUN 走**只读**分支：解析源文件、逐行校验、查目标库判重，"
                    + "**一个写端点都不调**；数据库触发器 guard_platform_migration_row_success "
                    + "额外兜底（试运行的行不许带 upstream_id）",
                "",
                "试运行也会读目标库（判重）—— 读不是写；目标库读不到时该行「跳过」并写明原因"),
            new DataMigrationCapability(
                "18.1.3.2",
                "正式迁移",
                true,
                "Mode.APPLY 逐行调 catalog 的授权写入端点（POST /catalog/products、"
                    + "POST /catalog/products/{id}/skus），**只转发调用方自己的 bearer**；"
                    + "每迁成一行就把目标库的记录 id 记进 platform_migration_row.upstream_id",
                "",
                "落库的 SPU / SKU 一律是**草稿**：catalog 的新建规则要求先存草稿。"
                    + "外部系统里的「在售」直接写进去会被 catalog 拒 —— 迁移不绕过这条规则"),
            new DataMigrationCapability(
                "18.1.3.2",
                "失败回滚",
                true,
                "按逐行记下的 upstream_id 去 catalog **真删**（DELETE /catalog/products/{id}、"
                    + "DELETE /catalog/products/{id}/skus/{sid}）；"
                    + "回滚结论与迁移结论**分两列**存（rollback_status / rollback_message），"
                    + "「迁成了什么」与「删掉了什么」不揉在一起",
                "",
                "回滚的前提是迁移**已经跑完**（数据库触发器钉死）；试运行没有写任何业务数据，"
                    + "没有可回滚的东西（回滚它是无中生有）"),
            new DataMigrationCapability(
                "18.1.3.2",
                "核对报告（成功/失败/跳过数量与原因）",
                true,
                "platform_migration_run 上的四个计数 + platform_migration_row 的**逐行结论**；"
                    + "另出一份可下载的 CSV 报告（摘要块 + 逐行明细）",
                "",
                "三个口径加起来**必须**等于处理过的行数（数据库 CHECK 钉死）—— "
                    + "加不到总数上，那份报告在账上就对不平"),
            new DataMigrationCapability(
                "18.1.3.2",
                "**迁移到 catalog（SPU / SKU）**",
                true,
                "catalog 有真实的写入 / 删除对：POST /catalog/products、DELETE /catalog/products/{id}?version=、"
                    + "POST /catalog/products/{id}/skus、DELETE /catalog/products/{id}/skus/{sid}?version= "
                    + "⇒ 回滚可以是**真的**",
                "3.1.2 / 3.1.3",
                "⚠️ **属性值不在本模块范围内**：属性是 SPU 级的定义（catalog 的属性字典），"
                    + "迁移属性值要先重建属性字典再逐 SKU 覆写 —— 本模块不假装做了这件事"),
            new DataMigrationCapability(
                "18.1.3.2",
                "**迁移到订单 / 库存 / 凭证**",
                false,
                "这些域的属主服务**没有面向迁移的写入端点**，而且它们不是可以「导进去」的数据："
                    + "订单要占库存 / 过信用校验 / 触发平台回传，库存是由单据推导出来的余量，"
                    + "凭证是会计事实",
                "9.1 / 7.1 / 12.5.2.1",
                "强行迁移会绕过属主服务的校验链，把账实关系与对账关系写坏。"
                    + "这些域的历史数据补录走它们自己的处置流程"));
    var gaps =
        List.of(
            new DataMigrationGap(
                "18.1.3.1",
                "导入导出任务",
                "platform",
                true,
                "上游",
                "本模块**复用**它的文件存储（ImportExportStorage）、CSV 读写（ImportExportCsv）、"
                    + "进程内执行器骨架与 AccessService 守卫；"
                    + "**不复制**它的四张表 —— 迁移有「回滚」这一维、逐行结论要记「上游 id」才能回滚，"
                    + "硬塞进同一张表会把两套状态机揉在一起"),
            new DataMigrationGap(
                "18.1.3.1",
                "数据域目录（导入导出方向）",
                "platform",
                true,
                "邻居",
                "两个模块的「数据域」**不是一回事**：那边是「这个域能不能导 / 能导什么列」，"
                    + "这边是「这个域能不能**迁历史数据**」—— 判据是**有没有 create/delete 对**，"
                    + "不是有没有文件型导入作业"),
            new DataMigrationGap(
                "3.1.3",
                "商品属性字典",
                "catalog",
                false,
                "下游",
                "属性值迁移**未交付**：属性是 SPU 级的定义，要先重建属性字典再逐 SKU 覆写。"
                    + "本模块的 SKU 迁移只覆盖编码 / 名称 / 条码 / 单位 / 所属商品五个字段，"
                    + "能力声明里如实写明"));
    return new DataMigrationCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public DataMigrationOverview overview() {
    return new DataMigrationOverview(
        count("select count(*) from platform_migration_run"),
        count("select count(*) from platform_migration_run where mode = 'DRY_RUN'"),
        count("select count(*) from platform_migration_run where mode = 'APPLY'"),
        count("select count(*) from platform_migration_run where status = 'RUNNING'"),
        count("select count(*) from platform_migration_run where succeeded_rows > 0"),
        count("select count(*) from platform_migration_run where failed_rows > 0"),
        count("select count(*) from platform_migration_run where skipped_rows > 0"),
        count("select count(*) from platform_migration_run where rollback_status = 'SUCCEEDED'"),
        count("select count(*) from platform_migration_mapping"),
        count("select count(*) from platform_migration_row"),
        dbNow());
  }

  /** 目标模型目录（**接不通的那几个照样出现**，标未接通并写明缺什么、归谁）。 */
  public List<DataMigrationTargetRow> targets() {
    var out = new ArrayList<DataMigrationTargetRow>();
    for (var model : DataMigrationTargets.ALL) {
      out.add(
          new DataMigrationTargetRow(
              model.code(),
              model.label(),
              model.ownerService(),
              model.ownerModule(),
              model.fields(),
              model.limitation()));
    }
    return out;
  }

  // ================================================================ 映射配置

  /** 映射配置列表（真分页 + 真筛选）。 */
  public DataMigrationMappingPage mappings(String targetModel, String q, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (targetModel != null && !targetModel.isBlank()) {
      where.append(" and target_model = ?");
      params.add(targetModel.trim().toUpperCase(Locale.ROOT));
    }
    if (q != null && !q.isBlank()) {
      where.append(" and (code ilike ? or name ilike ? or source_system ilike ?)");
      params.add("%" + q.trim() + "%");
      params.add("%" + q.trim() + "%");
      params.add("%" + q.trim() + "%");
    }
    var total = count("select count(*) from platform_migration_mapping" + where, params.toArray());
    var rows =
        sql.fetch(
            "select id,code,name,source_system,target_model,fields,enabled,remark,created_by,"
                + " created_at,updated_at from platform_migration_mapping"
                + where
                + " order by created_at desc, code limit ? offset ?",
            concat(params, size2, (long) page2 * size2));
    return new DataMigrationMappingPage(rows.map(this::toMappingRow), page2, size2, total);
  }

  /** 映射配置详情。 */
  public DataMigrationMappingRow mapping(UUID id) {
    var row =
        sql.fetchOne(
            "select id,code,name,source_system,target_model,fields,enabled,remark,created_by,"
                + " created_at,updated_at from platform_migration_mapping where id = ?",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "映射配置不存在");
    return toMappingRow(row);
  }

  /** 新建映射配置。 */
  public DataMigrationMappingRow createMapping(
      DataMigrationMappingInput input, AccessService.Access actor) {
    var code = required(input.code(), "映射编码", 64);
    check(code.matches("[A-Za-z0-9_-]{1,64}"), "映射编码只能包含英文字母、数字、下划线或短横线");
    var name = required(input.name(), "映射名称", 120);
    var model = DataMigrationTargets.require(input.targetModel());
    var fields = validateFields(model, input.fields());
    var sourceSystem =
        input.sourceSystem() == null ? "" : input.sourceSystem().trim();
    check(sourceSystem.length() <= 64, "外部系统名长度不能超过 64 个字符");
    var remark = input.remark() == null ? "" : input.remark().trim();
    check(remark.length() <= 500, "备注长度不能超过 500 个字符");
    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_migration_mapping(id,tenant_id,code,name,source_system,target_model,"
            + " fields,enabled,remark,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?::jsonb,?,?,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        code,
        name,
        sourceSystem,
        model.code(),
        toJson(fields),
        input.enabled() == null || input.enabled(),
        remark,
        actor.userId(),
        now,
        now);
    return mapping(id);
  }

  /** 修改映射配置（**改了就是改了契约** —— 已发起的迁移任务用的是它冻结的快照，不受影响）。 */
  public DataMigrationMappingRow updateMapping(UUID id, DataMigrationMappingInput input) {
    var existing = mapping(id);
    var code = required(input.code(), "映射编码", 64);
    check(code.matches("[A-Za-z0-9_-]{1,64}"), "映射编码只能包含英文字母、数字、下划线或短横线");
    var name = required(input.name(), "映射名称", 120);
    var model = DataMigrationTargets.require(input.targetModel());
    var fields = validateFields(model, input.fields());
    var sourceSystem = input.sourceSystem() == null ? "" : input.sourceSystem().trim();
    check(sourceSystem.length() <= 64, "外部系统名长度不能超过 64 个字符");
    var remark = input.remark() == null ? "" : input.remark().trim();
    check(remark.length() <= 500, "备注长度不能超过 500 个字符");
    sql.execute(
        "update platform_migration_mapping set code = ?, name = ?, source_system = ?,"
            + " target_model = ?, fields = ?::jsonb, enabled = ?, remark = ?,"
            + " updated_at = ?::timestamptz where id = ?",
        code,
        name,
        sourceSystem,
        model.code(),
        toJson(fields),
        input.enabled() == null || input.enabled(),
        remark,
        dbNow(),
        id);
    return mapping(existing.id());
  }

  /** 删除映射配置（已被迁移任务引用的**不能删** —— 停用请改 enabled）。 */
  public void deleteMapping(UUID id) {
    mapping(id);
    var referenced = count("select count(*) from platform_migration_run where mapping_id = ?", id);
    if (referenced > 0) {
      conflict(
          "这份映射已经被 "
              + referenced
              + " 个迁移任务引用，不能删 —— 审计要回答的是「当时按什么映射迁的」。"
              + "要停用它请把「启用」关掉。");
    }
    sql.execute("delete from platform_migration_mapping where id = ?", id);
  }

  /**
   * 校验字段映射。
   *
   * <p>🔴 <b>模型必填字段必须全被映射，且不能靠兜底值蒙混</b> ——
   * 一个缺了「商品编码」映射的配置文件，运行时会让**每一行**都失败，
   * 而根因在配置里，不在数据里。与其跑一遍才发现，不如建映射时就拒。
   */
  private List<DataMigrationField> validateFields(
      DataMigrationTargets.Model model, List<DataMigrationField> fields) {
    check(fields != null && !fields.isEmpty(), "至少要配置一条字段映射");
    check(fields.size() <= 100, "字段映射不能超过 100 条");
    var known = DataMigrationTargets.fieldsOf(model.code());
    var mappedTargets = new LinkedHashSet<String>();
    var out = new ArrayList<DataMigrationField>();
    for (var f : fields) {
      var source = required(f.source(), "外部列名", 128);
      var target = required(f.target(), "内部字段", 64);
      check(known.containsKey(target), "内部字段「" + target + "」不属于目标模型 " + model.code()
          + "（可用：" + known.keySet() + "）");
      check(mappedTargets.add(target), "内部字段「" + target + "」被映射了两次");
      var transform = Transform.parse(f.transform());
      check(transform != null, "未知的转换方式「" + f.transform() + "」（可用：NONE / TRIM / UPPER / LOWER）");
      var defaultValue = f.defaultValue() == null ? "" : f.defaultValue();
      check(defaultValue.length() <= 2000, "兜底值长度不能超过 2000 个字符");
      var modelField = known.get(target);
      if (modelField.required()) {
        check(f.required(), "内部字段「" + target + "」是必填的，映射里也必须标成必填（不能用兜底值蒙混）");
      }
      out.add(new DataMigrationField(source, target, f.required(), transform.name(), defaultValue));
    }
    for (var requiredKey : DataMigrationTargets.requiredFields(model.code())) {
      check(
          mappedTargets.contains(requiredKey),
          "目标模型 " + model.code() + " 的必填字段「" + requiredKey + "」没有映射 —— "
              + "缺了它，源文件的每一行都会失败");
    }
    return out;
  }

  private DataMigrationMappingRow toMappingRow(org.jooq.Record r) {
    return new DataMigrationMappingRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("source_system", String.class),
        r.get("target_model", String.class),
        readFields(r.get("fields")),
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("remark", String.class),
        r.get("created_by", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  @SuppressWarnings("unchecked")
  private List<DataMigrationField> readFields(Object raw) {
    if (raw == null) return List.of();
    try {
      var text = raw instanceof String s ? s : String.valueOf(raw);
      var parsed =
          PlatformUpstreamClient.JSON.readValue(
              text, new TypeReference<List<DataMigrationField>>() {});
      return parsed == null ? List.of() : parsed;
    } catch (Exception e) {
      return List.of();
    }
  }

  // ================================================================ 迁移任务

  /** 迁移任务列表（真分页 + 真筛选）。 */
  public DataMigrationRunPage runs(String mode, String status, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (mode != null && !mode.isBlank()) {
      where.append(" and mode = ?");
      params.add(mode.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var total = count("select count(*) from platform_migration_run" + where, params.toArray());
    var rows =
        sql.fetch(
            "select id,run_no,mode,target_model,mapping_id,mapping_code,source_filename,source_sha256,"
                + " status,rollback_status,total_rows,processed_rows,succeeded_rows,failed_rows,"
                + " skipped_rows,rolled_back_rows,report_file_id,rollback_report_file_id,error,actor_id,"
                + " requested_at,started_at,finished_at,rolled_back_at,created_at"
                + " from platform_migration_run"
                + where
                + " order by created_at desc, run_no limit ? offset ?",
            concat(params, size2, (long) page2 * size2));
    return new DataMigrationRunPage(rows.map(this::toRunRow), page2, size2, total);
  }

  /** 迁移任务详情（**进度 + 核对结论**）。 */
  public DataMigrationRunRow run(UUID id) {
    var row =
        sql.fetchOne(
            "select id,run_no,mode,target_model,mapping_id,mapping_code,source_filename,source_sha256,"
                + " status,rollback_status,total_rows,processed_rows,succeeded_rows,failed_rows,"
                + " skipped_rows,rolled_back_rows,report_file_id,rollback_report_file_id,error,actor_id,"
                + " requested_at,started_at,finished_at,rolled_back_at,created_at"
                + " from platform_migration_run where id = ?",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "迁移任务不存在");
    return toRunRow(row);
  }

  /**
   * 发起一次迁移（**已受理**：真正的执行在进程内执行器上跑）。
   *
   * <p>🔴 <b>源文件立刻落存储</b>：这样即使执行器还没跑到，文件也已经在
   * （失败行报告要引用它）。
   */
  public DataMigrationRunRow createRun(
      DataMigrationRunInput input, AccessService.Access actor) {
    var mapping = mapping(input.mappingId());
    check(mapping.enabled(), "映射配置「" + mapping.code() + "」已停用");
    var model = DataMigrationTargets.require(mapping.targetModel());
    check(
        DataMigrationTargets.available(model.code()),
        "「" + model.label() + "」没有接通迁移：" + model.limitation());
    var mode = Mode.parse(input.mode());
    check(mode != null, "迁移模式必须是 DRY_RUN（试运行）或 APPLY（正式迁移）");
    check(input.filename() != null && !input.filename().isBlank(), "源文件名不能为空");
    byte[] content;
    try {
      content = java.util.Base64.getDecoder().decode(input.contentBase64() == null ? "" : input.contentBase64());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不是合法的 Base64");
    }
    check(content.length > 0, "上传的文件是空的");
    check(
        content.length <= MAX_SOURCE_BYTES,
        "上传文件超过单次上限（" + (MAX_SOURCE_BYTES / 1024 / 1024) + " MB），请拆成多个文件");
    // 🔴 **先解析一遍再落库**：文件根本不是合法 CSV 时，别制造一个注定失败的任务。
    var rows = ImportExportCsv.read(content);
    check(!rows.isEmpty(), "上传的文件里没有任何内容（连表头都没有）");
    check(
        rows.size() - 1 <= MAX_SOURCE_ROWS,
        "文件行数超过单次上限（" + MAX_SOURCE_ROWS + " 行）。请拆成多个文件。");

    var runNo = nextRunNo();
    var id = UUID.randomUUID();
    var now = dbNow();
    var snapshot =
        new DataMigrationMappingSnapshot(
            mapping.code(), mapping.name(), mapping.sourceSystem(), mapping.targetModel(), mapping.fields());
    sql.execute(
        "insert into platform_migration_run(id,tenant_id,run_no,mode,target_model,mapping_id,"
            + " mapping_code,mapping_snapshot,source_filename,source_sha256,status,rollback_status,"
            + " actor_id,requested_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,?::jsonb,?,?,'PENDING','NONE',?,?::timestamptz,"
            + " ?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        runNo,
        mode.name(),
        model.code(),
        mapping.id(),
        mapping.code(),
        toJson(snapshot),
        safeFilename(input.filename()),
        sha256(content),
        actor.userId(),
        now,
        now,
        now);
    try {
      writeFile(id, "SOURCE", safeFilename(input.filename()), content, null);
    } catch (RuntimeException e) {
      // 源文件没落成 ⇒ 这个任务永远跑不起来 ⇒ 如实标失败，不留一个「待执行」的僵尸。
      sql.execute(
          "update platform_migration_run set status = 'FAILED', error = ?, finished_at = ?::timestamptz,"
              + " updated_at = ?::timestamptz where id = ?",
          "源文件写入存储失败：" + e.getMessage(),
          dbNow(),
          dbNow(),
          id);
      throw e;
    }
    return run(id);
  }

  /**
   * 请求回滚（**已受理**：真正的删除在进程内执行器上跑）。
   *
   * <p>🔴 <b>回滚的前提在这里判一次，数据库触发器再判一次</b> ——
   * 应用层判了是给人一句人话，数据库判了是「绕过应用层也做不了」。
   */
  public DataMigrationRunRow requestRollback(UUID id, AccessService.Access actor) {
    var run = run(id);
    if (run.mode().equals(Mode.DRY_RUN.name())) {
      conflict("试运行没有写任何业务数据，没有可回滚的东西");
    }
    if (!run.status().equals(RunStatus.SUCCEEDED.name())
        && !run.status().equals(RunStatus.FAILED.name())) {
      conflict("迁移还没跑完（当前「" + label(run.status()) + "」），不能回滚");
    }
    if (!run.rollbackStatus().equals(RollbackStatus.NONE.name())) {
      conflict("这个任务已经回滚过了（当前「" + rollbackLabel(run.rollbackStatus()) + "」）");
    }
    var created =
        count(
            "select count(*) from platform_migration_row where run_id = ? and outcome = 'SUCCEEDED'"
                + " and upstream_id is not null",
            id);
    if (created == 0) {
      conflict("这个任务没有迁成任何记录，没有可回滚的东西");
    }
    return run;
  }

  /** 逐行结论（真分页 + 真筛选）。 */
  public DataMigrationRowPage rows(UUID runId, String outcome, int page, int size) {
    run(runId);
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where run_id = ?");
    var params = new ArrayList<Object>();
    params.add(runId);
    if (outcome != null && !outcome.isBlank()) {
      where.append(" and outcome = ?");
      params.add(outcome.trim().toUpperCase(Locale.ROOT));
    }
    var total = count("select count(*) from platform_migration_row" + where, params.toArray());
    var succeeded =
        count(
            "select count(*) from platform_migration_row where run_id = ? and outcome = 'SUCCEEDED'",
            runId);
    var failed =
        count("select count(*) from platform_migration_row where run_id = ? and outcome = 'FAILED'", runId);
    var skipped =
        count("select count(*) from platform_migration_row where run_id = ? and outcome = 'SKIPPED'", runId);
    var rows =
        sql.fetch(
            "select id,run_id,row_no,outcome,target_kind,target_code,upstream_id,upstream_parent_id,"
                + " reason_code,message,raw_value,rollback_status,rollback_message,created_at"
                + " from platform_migration_row"
                + where
                + " order by row_no limit ? offset ?",
            concat(params, size2, (long) page2 * size2));
    return new DataMigrationRowPage(
        rows.map(this::toRowRow), page2, size2, total, (int) succeeded, (int) failed, (int) skipped);
  }

  /** 核对报告（CSV）。 */
  public DataMigrationFileContent report(UUID runId) {
    var run = run(runId);
    if (run.reportFileId() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这个任务还没有核对报告");
    }
    return downloadFile(run.reportFileId());
  }

  /** 回滚报告（CSV）。 */
  public DataMigrationFileContent rollbackReport(UUID runId) {
    var run = run(runId);
    if (run.rollbackReportFileId() == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "这个任务还没有回滚报告");
    }
    return downloadFile(run.rollbackReportFileId());
  }

  private DataMigrationFileContent downloadFile(UUID fileId) {
    var row =
        sql.fetchOne(
            "select filename,storage_key,content_type,expires_at from platform_migration_file"
                + " where id = ?",
            fileId);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
    var expiresAt = row.get("expires_at", OffsetDateTime.class);
    if (expiresAt != null && expiresAt.isBefore(dbNow())) {
      throw new ResponseStatusException(
          HttpStatus.GONE,
          "文件已过下载有效期（" + expiresAt + "）。迁移任务记录仍然保留，可重新发起一次。");
    }
    try {
      return new DataMigrationFileContent(
          row.get("filename", String.class),
          row.get("content_type", String.class),
          storage.read(row.get("storage_key", String.class)));
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.GONE, "文件已不在存储里（可能已被过期清理）：" + e.getMessage());
    }
  }

  // ================================================================ 执行（异步）

  /**
   * 这个任务行**在库里看得见吗**（存在即可，不看状态）。
   *
   * <p>🔴 <b>给执行器用</b>：发起请求的那笔事务**还没提交**时，执行线程
   * 在库里看不到这一行（RLS + 事务可见性）。执行器据此判断「还没提交，等一下」，
   * 而不是把一次正常的提交时序差当成「任务不存在」。
   *
   * <p>⚠️ 必须在**带租户上下文的事务里**调用（RLS 挡住了没绑租户的读）。
   */
  public boolean exists(UUID runId) {
    var row = sql.fetchOne("select status from platform_migration_run where id = ?", runId);
    return row != null;
  }

  /**
   * 执行一次迁移（**由执行器在独立线程上调用**）。
   *
   * <p>🔴 <b>进度是事实，不是承诺</b>：每处理一行就更新一次计数。
   */
  public void executeRun(UUID runId, String bearer) {
    var run = run(runId);
    if (!run.status().equals(RunStatus.PENDING.name())) return;
    var now = dbNow();
    var claimed =
        sql.execute(
            "update platform_migration_run set status = 'RUNNING', started_at = ?::timestamptz,"
                + " updated_at = ?::timestamptz where id = ? and status = 'PENDING'",
            now,
            now,
            runId);
    if (claimed == 0) return;
    try {
      migrate(runId, bearer);
    } catch (RuntimeException e) {
      fail(runId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /** 迁移主体：逐行解析 → 逐行结论 → 写结论 → 出核对报告。 */
  private void migrate(UUID runId, String bearer) {
    var run = run(runId);
    var mode = Mode.valueOf(run.mode());
    var model = DataMigrationTargets.require(run.targetModel());
    var snapshot = readSnapshot(run);
    var source = sourceFile(runId);
    var rows = ImportExportCsv.read(source.content());
    var header = rows.get(0);
    var body = rows.subList(1, rows.size());

    // 🔴 **必填列缺了 ⇒ 整个任务失败**（不是「每一行都失败」）：
    //   根因在配置 / 文件结构，不在数据里；逐行报错会让人去查数据，其实该查表头。
    var index = new LinkedHashMap<String, Integer>();
    for (var i = 0; i < header.length; i++) index.putIfAbsent(header[i].trim(), i);
    for (var f : snapshot.fields()) {
      if (f.required()) {
        check(
            index.containsKey(f.source()),
            "源文件里没有「" + f.source() + "」这一列（表头："
                + String.join(" / ", header)
                + "）—— 这是**文件结构**的问题，不是某一行数据的问题");
      }
    }
    var unmapped = new ArrayList<String>();
    for (var h : header) {
      var name = h.trim();
      if (name.isEmpty()) continue;
      var mapped = snapshot.fields().stream().anyMatch(f -> f.source().equals(name));
      if (!mapped) unmapped.add(name);
    }

    var succeeded = 0;
    var failed = 0;
    var skipped = 0;
    var seenCodes = new LinkedHashSet<String>();
    for (var i = 0; i < body.size(); i++) {
      var rowNo = i + 2; // 表头是第 1 行
      var cells = body.get(i);
      var decision = decideRow(model, snapshot, header, cells, rowNo, seenCodes, mode, bearer);
      switch (decision.outcome()) {
        case SUCCEEDED -> succeeded++;
        case FAILED -> failed++;
        case SKIPPED -> skipped++;
      }
      insertRow(runId, rowNo, decision);
      sql.execute(
          "update platform_migration_run set processed_rows = ?, succeeded_rows = ?, failed_rows = ?,"
              + " skipped_rows = ?, updated_at = ?::timestamptz where id = ?",
          succeeded + failed + skipped,
          succeeded,
          failed,
          skipped,
          dbNow(),
          runId);
    }

    var report =
        buildReport(
            run,
            snapshot,
            unmapped,
            succeeded,
            failed,
            skipped,
            mode == Mode.DRY_RUN ? "试运行：未写入目标库" : "正式迁移：已写入目标库");
    var file = writeFile(runId, "REPORT", "migration-report-" + run.runNo() + ".csv", report, dbNow().plusHours(DEFAULT_EXPIRY_HOURS));
    sql.execute(
        "update platform_migration_run set status = 'SUCCEEDED', total_rows = ?, processed_rows = ?,"
            + " succeeded_rows = ?, failed_rows = ?, skipped_rows = ?, report_file_id = ?,"
            + " finished_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
        succeeded + failed + skipped,
        succeeded + failed + skipped,
        succeeded,
        failed,
        skipped,
        file.id(),
        dbNow(),
        dbNow(),
        runId);
  }

  /**
   * 一行数据的结论。
   *
   * @param outcome 结论
   * @param targetKind 目标类型（SPU / SKU）
   * @param targetCode 目标编码（失败时可能为空）
   * @param upstreamId 建出来的目标库记录 id（**回滚的依据**；试运行为空）
   * @param upstreamParentId SKU 的父 SPU id（SKU 才有）
   * @param reasonCode 原因码
   * @param message 人话说明
   * @param rawValue 出问题的原始值
   */
  private record Decision(
      RowOutcome outcome,
      String targetKind,
      String targetCode,
      UUID upstreamId,
      UUID upstreamParentId,
      String reasonCode,
      String message,
      String rawValue) {}

  /** 逐行决策：映射 → 校验 → 判重 → （正式迁移时）真的写。 */
  private Decision decideRow(
      DataMigrationTargets.Model model,
      DataMigrationMappingSnapshot snapshot,
      String[] header,
      String[] cells,
      int rowNo,
      Set<String> seenCodes,
      Mode mode,
      String bearer) {
    // ---------- 1. 按映射取值
    var index = new LinkedHashMap<String, Integer>();
    for (var i = 0; i < header.length; i++) index.putIfAbsent(header[i].trim(), i);
    var values = new LinkedHashMap<String, String>();
    for (var f : snapshot.fields()) {
      var column = index.get(f.source());
      var raw = column == null || column >= cells.length ? "" : cells[column];
      var text = transform(raw, f.transform());
      if (text.isEmpty() && !f.required()) {
        text = f.defaultValue();
      }
      values.put(f.target(), text);
    }

    // ---------- 2. 必填校验
    for (var field : model.fields()) {
      if (!field.required()) continue;
      var value = values.getOrDefault(field.key(), "");
      if (value.isBlank()) {
        return new Decision(
            RowOutcome.FAILED,
            model.code(),
            values.getOrDefault("code", ""),
            null,
            null,
            "MISSING_REQUIRED_FIELD",
            "必填字段「" + field.label() + "」为空",
            value);
      }
    }

    // ---------- 3. 编码归一
    var rawCode = values.getOrDefault("code", "");
    String code;
    try {
      code = normalizeCode(rawCode);
    } catch (IllegalArgumentException e) {
      return new Decision(
          RowOutcome.FAILED, model.code(), rawCode, null, null, "INVALID_CODE", e.getMessage(), rawCode);
    }

    // ---------- 4. 目标库判重
    var existence =
        model.code().equals("SPU")
            ? catalog.spuLookup(code, bearer).existence()
            : catalog.skuExists(code, bearer);
    if (existence == DataMigrationCatalogPort.Existence.EXISTS) {
      return new Decision(
          RowOutcome.SKIPPED,
          model.code(),
          code,
          null,
          null,
          "ALREADY_EXISTS",
          "目标库里已经有编码「" + code + "」了，迁移历史数据不制造重复主数据",
          rawCode);
    }
    if (existence == DataMigrationCatalogPort.Existence.UNKNOWN) {
      // 🔴 **查不清就跳过**（保守）：把一条可能已存在的记录再迁一遍，
      //   会在目标库里造出重复主数据 —— 那比漏迁一条更难收拾。
      return new Decision(
          RowOutcome.SKIPPED,
          model.code(),
          code,
          null,
          null,
          "TARGET_CHECK_INCONCLUSIVE",
          "目标库的查询结果被截断，无法确认编码「" + code + "」是不是已经存在 —— "
              + "这一行先跳过，避免在目标库里造出重复主数据",
          rawCode);
    }

    // ---------- 5. SKU 的父商品
    UUID parentId = null;
    if (model.code().equals("SKU")) {
      var parentCode = values.getOrDefault("spuCode", "").trim();
      var parent = catalog.spuLookup(parentCode, bearer);
      if (parent.existence() == DataMigrationCatalogPort.Existence.EXISTS) {
        parentId = parent.id();
      } else {
        var reason =
            parent.existence() == DataMigrationCatalogPort.Existence.UNKNOWN
                ? "TARGET_CHECK_INCONCLUSIVE"
                : "PARENT_SPU_NOT_FOUND";
        var message =
            parent.existence() == DataMigrationCatalogPort.Existence.UNKNOWN
                ? "无法确认所属商品「" + parentCode + "」是不是存在（目标库查询被截断）"
                : "所属商品「" + parentCode + "」在目标库里不存在 —— 本模块不自动建父商品";
        return new Decision(RowOutcome.FAILED, model.code(), code, null, null, reason, message, parentCode);
      }
    }

    // ---------- 6. 文件内重复
    //
    // 🔴 **校验过了才记重复**：一个失败行不该把它的重复行也变成「跳过」——
    //   那样报告里会出现「跳过」掩盖了真实的失败原因。
    if (!seenCodes.add(code)) {
      return new Decision(
          RowOutcome.SKIPPED,
          model.code(),
          code,
          null,
          null,
          "DUPLICATE_IN_FILE",
          "同一个文件里已经迁过编码「" + code + "」了，重复的那一行不迁",
          rawCode);
    }

    // ---------- 7. 试运行到此为止（**一个写端点都不调**）
    if (mode == Mode.DRY_RUN) {
      return new Decision(
          RowOutcome.SUCCEEDED,
          model.code(),
          code,
          null,
          parentId,
          "",
          "试运行：这一行可以迁成（未写入目标库）",
          "");
    }

    // ---------- 8. 正式迁移：真的写目标库
    try {
      if (model.code().equals("SPU")) {
        var id =
            catalog.createSpu(
                new DataMigrationCatalogPort.SpuPayload(
                    code,
                    values.getOrDefault("name", ""),
                    values.getOrDefault("description", ""),
                    values.getOrDefault("brand", ""),
                    values.getOrDefault("category", ""),
                    "DRAFT",
                    0L),
                bearer);
        return new Decision(
            RowOutcome.SUCCEEDED, model.code(), code, id, null, "", "已写入目标库（草稿）", "");
      }
      var skuVersion = catalog.spuVersion(parentId, bearer);
      var skuId =
          catalog.createSku(
              parentId,
              new DataMigrationCatalogPort.SkuPayload(
                  code,
                  values.getOrDefault("name", ""),
                  values.getOrDefault("barcode", ""),
                  values.getOrDefault("baseUnit", ""),
                  "DRAFT",
                  List.of(),
                  skuVersion),
              bearer);
      return new Decision(
          RowOutcome.SUCCEEDED, model.code(), code, skuId, parentId, "", "已写入目标库（草稿）", "");
    } catch (DataMigrationCatalogPort.RowFailure e) {
      return new Decision(
          RowOutcome.FAILED, model.code(), code, null, parentId, "UPSTREAM_REJECTED", e.detail, "");
    }
  }

  /** 值转换。 */
  private static String transform(String raw, String mode) {
    var text = raw == null ? "" : raw;
    var t = Transform.parse(mode);
    if (t == null) return text;
    return switch (t) {
      case TRIM -> text.trim();
      case UPPER -> text.trim().toUpperCase(Locale.ROOT);
      case LOWER -> text.trim().toLowerCase(Locale.ROOT);
      case NONE -> text;
    };
  }

  private void insertRow(UUID runId, int rowNo, Decision d) {
    var now = dbNow();
    sql.execute(
        "insert into platform_migration_row(id,tenant_id,run_id,row_no,outcome,target_kind,target_code,"
            + " upstream_id,upstream_parent_id,reason_code,message,raw_value,rollback_status,"
            + " rollback_message,created_at,updated_at)"
            + " values(?,?,?,?::int,?,?,?,?,?,?,?,?,'NONE','',?::timestamptz,?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        runId,
        rowNo,
        d.outcome().name(),
        d.targetKind() == null ? "" : d.targetKind(),
        d.targetCode() == null ? "" : d.targetCode(),
        d.upstreamId(),
        d.upstreamParentId(),
        d.reasonCode() == null ? "" : d.reasonCode(),
        d.message() == null ? "" : d.message(),
        d.rawValue() == null ? "" : d.rawValue(),
        now,
        now);
  }

  /** 任务失败（写明原因）。 */
  private void fail(UUID runId, String reason) {
    sql.execute(
        "update platform_migration_run set status = 'FAILED', error = ?, finished_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        reason == null ? "执行失败" : reason,
        dbNow(),
        dbNow(),
        runId);
  }

  /**
   * 把**卡住**的任务标成失败（执行中断）。
   *
   * <p>🔴 **不能自动重试**：重试要调用方的 bearer，而任务表里不存凭据。
   */
  public int failStuck(OffsetDateTime stuckBefore) {
    var rows =
        sql.fetch(
            "select id from platform_migration_run where status in ('PENDING','RUNNING')"
                + " and created_at <= ?::timestamptz",
            stuckBefore);
    var failed = 0;
    for (var r : rows) {
      sql.execute(
          "update platform_migration_run set status = 'FAILED',"
              + " error = '执行中断（进程重启或执行器异常）。本模块不把会话凭据落库，"
              + "因此不会自动重试 —— 请重新发起一次。',"
              + " finished_at = ?::timestamptz, updated_at = ?::timestamptz"
              + " where id = ? and status in ('PENDING','RUNNING')",
          dbNow(),
          dbNow(),
          r.get("id", UUID.class));
      failed++;
    }
    return failed;
  }

  // ================================================================ 回滚

  /**
   * 执行回滚（**由执行器在独立线程上调用**）。
   *
   * <p>🔴 <b>按 upstream_id 真删</b>；删不掉的行如实记「未删除」并写明原因 ——
   * 不把「提交了删除请求」当成「删掉了」。
   */
  public void executeRollback(UUID runId, String bearer) {
    var run = run(runId);
    if (!run.rollbackStatus().equals(RollbackStatus.NONE.name())) return;
    var claimed =
        sql.execute(
            "update platform_migration_run set rollback_status = 'RUNNING', updated_at = ?::timestamptz"
                + " where id = ? and rollback_status = 'NONE'",
            dbNow(),
            runId);
    if (claimed == 0) return;
    var deleted = 0;
    var failures = 0;
    try {
      var rows =
          sql.fetch(
              "select id,row_no,target_kind,target_code,upstream_id,upstream_parent_id"
                  + " from platform_migration_row where run_id = ? and outcome = 'SUCCEEDED'"
                  + " and upstream_id is not null order by row_no",
              runId);
      // 🔴 **先删 SKU 再删 SPU**：catalog 要求「商品名下没有 SKU」才能删商品。
      //   一次迁移任务只迁一种目标模型，所以这里按目标类型分两批处理，
      //   顺序保证「删到最后一条时父商品已经空了」。
      for (var kind : List.of("SKU", "SPU")) {
        for (var r : rows) {
          if (!kind.equals(r.get("target_kind", String.class))) continue;
          var rowId = r.get("id", UUID.class);
          var rowNo = r.get("row_no", Integer.class);
          var targetId = r.get("upstream_id", UUID.class);
          var parentId = r.get("upstream_parent_id", UUID.class);
          try {
            if (kind.equals("SKU")) {
              deleteSku(parentId, targetId, bearer);
            } else {
              var version = catalog.spuVersion(targetId, bearer);
              catalog.deleteSpu(targetId, version, bearer);
            }
            sql.execute(
                "update platform_migration_row set rollback_status = 'DELETED',"
                    + " rollback_message = '已从目标库删除', updated_at = ?::timestamptz where id = ?",
                dbNow(),
                rowId);
            deleted++;
          } catch (DataMigrationCatalogPort.RowFailure e) {
            failures++;
            sql.execute(
                "update platform_migration_row set rollback_status = 'FAILED', rollback_message = ?,"
                    + " updated_at = ?::timestamptz where id = ?",
                e.detail.length() > 480 ? e.detail.substring(0, 480) : e.detail,
                dbNow(),
                rowId);
          }
        }
      }
    } catch (RuntimeException e) {
      // 🔴 **系统性失败**（catalog 不可达）：标回滚失败，已删掉的算数，没删的如实留账。
      failures++;
    }
    sql.execute(
        "update platform_migration_run set rollback_status = ?, rolled_back_rows = ?,"
            + " rolled_back_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
        failures == 0 ? RollbackStatus.SUCCEEDED.name() : RollbackStatus.FAILED.name(),
        deleted,
        dbNow(),
        dbNow(),
        runId);
    writeRollbackReport(runId, deleted, failures);
  }

  /** 删一个 SKU（版本冲突时重读一次父商品版本再试）。 */
  private void deleteSku(UUID parentId, UUID skuId, String bearer) {
    var version = catalog.spuVersion(parentId, bearer);
    try {
      catalog.deleteSku(parentId, skuId, version, bearer);
    } catch (DataMigrationCatalogPort.RowFailure e) {
      if (e.status != 409) throw e;
      // 🔴 **409 = 乐观锁冲突**（父商品被别人改过）⇒ 重读版本再试一次。
      //   只重试一次：连着两次冲突说明真有人在并发改，重试第三次也没意义。
      catalog.deleteSku(parentId, skuId, catalog.spuVersion(parentId, bearer), bearer);
    }
  }

  private UUID rowIdOf(UUID runId, int rowNo) {
    var row =
        sql.fetchOne("select id from platform_migration_row where run_id = ? and row_no = ?", runId, rowNo);
    return row == null ? null : row.get("id", UUID.class);
  }

  // ================================================================ 文件与报告

  /** 写一份文件到存储并落元信息。 */
  private DataMigrationFileRow writeFile(
      UUID runId, String role, String filename, byte[] content, OffsetDateTime expiresAt) {
    var id = UUID.randomUUID();
    var owner = run(runId);
    // 🔴 存储键带**租户段** + **迁移段**：一个租户算不出另一个租户的键
    //   （实际隔离仍由 RLS 保证，这里是第二道）；迁移段与 18.1.3.1 的导入导出段**不同**，
    //   两边的文件不会互相覆盖。
    var key =
        currentTenant().toString().replace("-", "")
            + "/migration/"
            + owner.runNo()
            + "/"
            + role.toLowerCase(Locale.ROOT)
            + "-"
            + id
            + ".csv";
    long bytes;
    try {
      bytes = storage.put(key, content);
    } catch (IOException e) {
      throw new IllegalStateException("文件写入失败：" + e.getMessage(), e);
    }
    sql.execute(
        "insert into platform_migration_file(id,tenant_id,run_id,role,filename,storage_key,content_type,"
            + " content_sha256,byte_size,row_count,expires_at,created_at)"
            + " values(?,?,?,?,?,?,'text/csv; charset=UTF-8',?,?::numeric,0,?::timestamptz,"
            + " ?::timestamptz)",
        id,
        currentTenant(),
        runId,
        role,
        filename,
        key,
        sha256(content),
        BigDecimal.valueOf(bytes),
        expiresAt,
        dbNow());
    return file(id);
  }

  private DataMigrationFileRow file(UUID id) {
    var row =
        sql.fetchOne(
            "select id,run_id,role,filename,content_type,content_sha256,byte_size,row_count,"
                + " expires_at,created_at from platform_migration_file where id = ?",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
    return new DataMigrationFileRow(
        row.get("id", UUID.class),
        row.get("run_id", UUID.class),
        row.get("role", String.class),
        row.get("filename", String.class),
        row.get("content_type", String.class),
        row.get("content_sha256", String.class),
        row.get("byte_size", BigDecimal.class),
        intValue(row.get("row_count", Integer.class)),
        row.get("expires_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  /** 源文件（迁移用）。 */
  private DataMigrationFileContent sourceFile(UUID runId) {
    var row =
        sql.fetchOne(
            "select filename,storage_key from platform_migration_file where run_id = ? and role = 'SOURCE'",
            runId);
    if (row == null) throw new IllegalStateException("找不到上传的源文件");
    try {
      return new DataMigrationFileContent(
          row.get("filename", String.class),
          "text/csv; charset=UTF-8",
          storage.read(row.get("storage_key", String.class)));
    } catch (IOException e) {
      throw new IllegalStateException("源文件读不出来：" + e.getMessage());
    }
  }

  /** 核对报告：摘要块 + 逐行明细。 */
  private byte[] buildReport(
      DataMigrationRunRow run,
      DataMigrationMappingSnapshot snapshot,
      List<String> unmappedColumns,
      int succeeded,
      int failed,
      int skipped,
      String note) {
    var header =
        List.of(
            "行号",
            "结论",
            "目标类型",
            "目标编码",
            "原因码",
            "原因说明",
            "上游记录ID",
            "父记录ID",
            "回滚结论",
            "回滚说明");
    var body = new ArrayList<String[]>();
    body.add(summary("# 迁移核对报告", ""));
    body.add(summary("# 迁移任务", run.runNo()));
    body.add(summary("# 模式", modeLabel(run.mode())));
    body.add(summary("# 目标模型", run.targetModel()));
    body.add(summary("# 映射配置", snapshot.code() + "（" + snapshot.name() + "）"));
    body.add(summary("# 外部系统", snapshot.sourceSystem()));
    body.add(summary("# 源文件", run.sourceFilename()));
    body.add(summary("# 源文件指纹", run.sourceSha256()));
    body.add(summary("# 总行数", String.valueOf(succeeded + failed + skipped)));
    body.add(summary("# 成功", String.valueOf(succeeded)));
    body.add(summary("# 失败", String.valueOf(failed)));
    body.add(summary("# 跳过", String.valueOf(skipped)));
    body.add(
        summary(
            "# 未映射的列（已忽略）",
            unmappedColumns.isEmpty() ? "（无）" : String.join(" / ", unmappedColumns)));
    body.add(summary("# 说明", note));
    body.add(summary("# 生成时间", dbNow().toString()));
    body.add(header.toArray(new String[0]));
    var detail =
        sql.fetch(
            "select row_no,outcome,target_kind,target_code,reason_code,message,upstream_id,"
                + " upstream_parent_id,rollback_status,rollback_message"
                + " from platform_migration_row where run_id = ? order by row_no",
            run.id());
    for (var r : detail) {
      body.add(
          new String[] {
            String.valueOf(r.get("row_no", Integer.class)),
            outcomeLabel(r.get("outcome", String.class)),
            r.get("target_kind", String.class),
            r.get("target_code", String.class),
            r.get("reason_code", String.class),
            r.get("message", String.class),
            r.get("upstream_id", UUID.class) == null ? "" : r.get("upstream_id", UUID.class).toString(),
            r.get("upstream_parent_id", UUID.class) == null
                ? ""
                : r.get("upstream_parent_id", UUID.class).toString(),
            rollbackLabel(r.get("rollback_status", String.class)),
            r.get("rollback_message", String.class)
          });
    }
    return ImportExportCsv.write(List.of(), body);
  }

  /** 回滚报告：删了多少、哪些没删掉、为什么。 */
  private void writeRollbackReport(UUID runId, int deleted, int failures) {
    var run = run(runId);
    var body = new ArrayList<String[]>();
    body.add(summary("# 迁移回滚报告", ""));
    body.add(summary("# 迁移任务", run.runNo()));
    body.add(summary("# 模式", modeLabel(run.mode())));
    body.add(summary("# 回滚结论", rollbackLabel(run.rollbackStatus())));
    body.add(summary("# 实际删除", String.valueOf(deleted)));
    body.add(summary("# 删除失败", String.valueOf(failures)));
    body.add(summary("# 生成时间", dbNow().toString()));
    body.add(List.of("行号", "目标类型", "目标编码", "上游记录ID", "回滚结论", "说明").toArray(new String[0]));
    var detail =
        sql.fetch(
            "select row_no,target_kind,target_code,upstream_id,rollback_status,rollback_message"
                + " from platform_migration_row where run_id = ? and upstream_id is not null"
                + " order by row_no",
            runId);
    for (var r : detail) {
      body.add(
          new String[] {
            String.valueOf(r.get("row_no", Integer.class)),
            r.get("target_kind", String.class),
            r.get("target_code", String.class),
            r.get("upstream_id", UUID.class) == null ? "" : r.get("upstream_id", UUID.class).toString(),
            rollbackLabel(r.get("rollback_status", String.class)),
            r.get("rollback_message", String.class)
          });
    }
    var content = ImportExportCsv.write(List.of(), body);
    var file =
        writeFile(
            runId,
            "REPORT",
            "migration-rollback-" + run.runNo() + ".csv",
            content,
            dbNow().plusHours(DEFAULT_EXPIRY_HOURS));
    sql.execute(
        "update platform_migration_run set rollback_report_file_id = ? where id = ?", file.id(), runId);
  }

  /** 摘要行（两列，补齐成与明细同宽，避免 CSV 里列错位）。 */
  private static String[] summary(String label, String value) {
    return new String[] {label, value, "", "", "", "", "", "", "", ""};
  }

  // ================================================================ 过期清理

  /**
   * 清理过期文件（**任务记录保留** —— 它是审计事实）。
   *
   * @return 被清掉的文件数
   */
  public int expireDue() {
    var now = dbNow();
    var due =
        sql.fetch(
            "select id,storage_key from platform_migration_file where expires_at is not null"
                + " and expires_at <= ?::timestamptz",
            now);
    var expired = 0;
    for (var r : due) {
      try {
        storage.delete(r.get("storage_key", String.class));
      } catch (IOException e) {
        // 删不掉就留着（下次再删）；**不能因此中断整批清理**。
      }
      sql.execute("delete from platform_migration_file where id = ?", r.get("id", UUID.class));
      expired++;
    }
    return expired;
  }

  // ================================================================ 小工具

  private static int intValue(Integer value) {
    return value == null ? 0 : value;
  }

  private DataMigrationRunRow toRunRow(org.jooq.Record r) {
    return new DataMigrationRunRow(
        r.get("id", UUID.class),
        r.get("run_no", String.class),
        r.get("mode", String.class),
        r.get("target_model", String.class),
        r.get("mapping_id", UUID.class),
        r.get("mapping_code", String.class),
        r.get("source_filename", String.class),
        r.get("source_sha256", String.class),
        r.get("status", String.class),
        r.get("rollback_status", String.class),
        intValue(r.get("total_rows", Integer.class)),
        intValue(r.get("processed_rows", Integer.class)),
        intValue(r.get("succeeded_rows", Integer.class)),
        intValue(r.get("failed_rows", Integer.class)),
        intValue(r.get("skipped_rows", Integer.class)),
        intValue(r.get("rolled_back_rows", Integer.class)),
        r.get("report_file_id", UUID.class),
        r.get("rollback_report_file_id", UUID.class),
        r.get("error", String.class),
        r.get("actor_id", UUID.class),
        r.get("requested_at", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("rolled_back_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private DataMigrationRowRow toRowRow(org.jooq.Record r) {
    return new DataMigrationRowRow(
        r.get("id", UUID.class),
        r.get("run_id", UUID.class),
        intValue(r.get("row_no", Integer.class)),
        r.get("outcome", String.class),
        r.get("target_kind", String.class),
        r.get("target_code", String.class),
        r.get("upstream_id", UUID.class),
        r.get("upstream_parent_id", UUID.class),
        r.get("reason_code", String.class),
        r.get("message", String.class),
        r.get("raw_value", String.class),
        r.get("rollback_status", String.class),
        r.get("rollback_message", String.class),
        r.get("created_at", OffsetDateTime.class));
  }

  private DataMigrationMappingSnapshot readSnapshot(DataMigrationRunRow run) {
    var row =
        sql.fetchOne("select mapping_snapshot from platform_migration_run where id = ?", run.id());
    if (row == null) throw new IllegalStateException("找不到迁移任务的映射快照");
    var raw = row.get("mapping_snapshot", String.class);
    if (raw == null || raw.isBlank()) throw new IllegalStateException("迁移任务没有映射快照");
    try {
      return PlatformUpstreamClient.JSON.readValue(raw, DataMigrationMappingSnapshot.class);
    } catch (Exception e) {
      throw new IllegalStateException("迁移任务的映射快照读不出来：" + e.getMessage());
    }
  }

  private static String label(String status) {
    try {
      return RunStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String rollbackLabel(String status) {
    try {
      return RollbackStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String modeLabel(String mode) {
    try {
      return Mode.valueOf(mode).label();
    } catch (IllegalArgumentException e) {
      return mode;
    }
  }

  private static String outcomeLabel(String outcome) {
    try {
      return RowOutcome.valueOf(outcome).label();
    } catch (IllegalArgumentException e) {
      return outcome;
    }
  }

  /** 人可读单号（租户短码 + 序列号）。 */
  private String nextRunNo() {
    var seq = sql.fetchOne("select nextval('platform_migration_run_no_seq')").get(0, Long.class);
    var tenantShort = currentTenant().toString().replace("-", "").substring(0, 8);
    return "MIG-" + tenantShort + "-" + String.format("%08d", seq);
  }

  /** 安全的文件名（去掉路径分隔符与控制字符）。 */
  private static String safeFilename(String raw) {
    var name = raw == null ? "" : raw.trim();
    name = name.replace('\\', '_').replace('/', '_');
    name = name.replaceAll("[\\r\\n\\t]", "_");
    if (name.isEmpty()) name = "source.csv";
    if (name.length() > 200) name = name.substring(name.length() - 200);
    return name;
  }

  /** 把参数数组拼成可变参数数组（jOOQ 的 {@code fetch(String, Object...)}）。 */
  private static Object[] concat(List<Object> params, Object... extra) {
    var out = new ArrayList<Object>(params);
    out.addAll(List.of(extra));
    return out.toArray();
  }
}
