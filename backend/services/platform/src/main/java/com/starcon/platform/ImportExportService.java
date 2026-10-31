package com.starcon.platform;

import static com.starcon.platform.ImportExportModels.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.1.3.1「导入导出任务」的服务层。
 *
 * <p>蓝图原文：「<b>职责</b>：统一的数据导入导出通道：任务创建（选择数据域与范围）、异步执行、
 * 进度跟踪、结果下载、失败行报告、模板下载。<b>输入</b>：导出/导入请求；文件上传。
 * <b>输出</b>：任务记录 + 文件（带有效期）+ 错误报告。<b>关系</b>：上游 = 全部业务域；
 * 下游 = 用户本地文件。<b>大文件一律异步</b>，并在下载链接上设置有效期，避免存储无限增长。」
 *
 * <p><b>七条纪律：</b>
 *
 * <ol>
 *   <li><b>「上游 = 全部业务域」按三条通道落地</b>，不是一条跨库大 SQL：
 *       ① platform 自有表（jOOQ 显式 SQL 读自己的表）；② 属主服务的**授权只读端点**
 *       （跨服务 HTTP，只转发调用方自己的 bearer）；③ 属主服务的**文件型导入作业**。
 *       逐域的接线状态见 {@link ImportExportDomains}，没接通的<b>如实标 available=false</b>。
 *   <li><b>导出与导入共用一张任务表</b>：两条链完全同构（任务 → 异步 → 进度 → 下载 → 失败行）。
 *   <li><b>不抄一份到本库</b>：属主服务的数据只经它自己的授权 API 读。抄一份就会漂移。
 *   <li><b>超限就报错，不偷偷截断</b>：超过单次导出上限直接让任务失败并说明该缩小区间 ——
 *       偷偷只导一部分，会让导出的文件**看起来是完整的，而它不是**。
 *   <li><b>失败行逐行逐列</b>：不写「一个错误字符串」——运营要看到**哪一行、哪一列、为什么**。
 *   <li><b>时间一律 DB 时钟</b>：落库时间戳取 {@code clock_timestamp()}，
 *       用 {@link OffsetDateTime} 绑定并在 values 位置写 {@code ?::timestamptz}
 *       （用 {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s）。
 *   <li><b>写入归属会话租户</b>：所有 INSERT 的 {@code tenant_id} 取自已认证会话，
 *       不接受请求参数传入；RLS 的 WITH CHECK 再兜一层。
 * </ol>
 */
@Service
public class ImportExportService {

  /** 列表分页上限。 */
  static final int MAX_PAGE_SIZE = 100;

  /**
   * 单次导出最多多少行。
   *
   * <p>🔴 上限是**必须**的：没有上限时一个「全量」请求会把上游翻到天荒地老，
   * 而调用方在等一个几分钟的响应。超限**直接让任务失败并说明该缩小区间**，
   * 而不是**偷偷只导一部分**（那会让导出的文件看起来是完整的，而它不是）。
   */
  static final int MAX_EXPORT_ROWS = 20_000;

  /** 跨服务翻页时每页拉多少行。 */
  static final int UPSTREAM_PAGE_SIZE = 200;

  /** 跨服务最多翻多少页（分页翻页有上限 —— 不能把它当无限流接口用）。 */
  static final int MAX_UPSTREAM_PAGES = 100;

  /** 导入文件大小上限（10 MB）：再大的文件应该走对象存储直传，不是通道的活。 */
  static final int MAX_IMPORT_BYTES = 10 * 1024 * 1024;

  /** 导入文件最多多少行。 */
  static final int MAX_IMPORT_ROWS = 20_000;

  /** 结果文件的默认有效期（小时）。 */
  static final long DEFAULT_EXPIRY_HOURS = 24;

  private static final SecureRandom RANDOM = new SecureRandom();

  private final DSLContext sql;
  private final ImportExportStorage storage;
  private final ObjectMapper json;

  private final CatalogProductPort catalog;
  private final TradeOrderPort trade;
  private final WarehouseInventoryPort warehouse;
  private final ChannelListingCatalogPort channel;
  private final FinanceVoucherPort finance;

  public ImportExportService(
      DSLContext sql,
      ImportExportStorage storage,
      ObjectMapper json,
      CatalogProductPort catalog,
      TradeOrderPort trade,
      WarehouseInventoryPort warehouse,
      ChannelListingCatalogPort channel,
      FinanceVoucherPort finance) {
    this.sql = sql;
    this.storage = storage;
    this.json = json;
    this.catalog = catalog;
    this.trade = trade;
    this.warehouse = warehouse;
    this.channel = channel;
    this.finance = finance;
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

  /** 整数取值（null → 0）。 */
  private static int intValue(Integer value) {
    return value == null ? 0 : value;
  }

  /** 内容指纹（sha256，十六进制 64 位）。 */
  static String sha256(byte[] content) {
    try {
      return java.util.HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用", e);
    }
  }

  /** 把一个值转成 CSV 单元格的文本（null → 空串，BigDecimal 不丢精度、不引入浮点）。 */
  static String cell(Object value) {
    if (value == null) return "";
    if (value instanceof BigDecimal d) return d.stripTrailingZeros().toPlainString();
    if (value instanceof Boolean b) return b ? "true" : "false";
    if (value instanceof OffsetDateTime t) return t.toString();
    if (value instanceof LocalDate d) return d.toString();
    return String.valueOf(value);
  }

  // ================================================================ 能力声明

  /**
   * 18.1.3.1 的能力声明：逐维说清「有没有、依据是什么、缺了会怎样、归谁」。
   *
   * <p>🔴 **没接通的格子如实写 {@code available=false}**，
   * 并写明缺什么、归谁 —— 绝不为让测试绿而写成假的 {@code true}。
   */
  public ImportExportCapabilities capabilities() {
    var capabilities =
        List.of(
            new ImportExportCapability(
                "18.1.3.1",
                "任务创建（选择数据域与范围）",
                true,
                "platform_io_task 表 + 创建端点；范围（scope）在创建那一刻**冻结**成 jsonb 快照，"
                    + "之后不可改（数据库触发器钉死）—— 审计要回答的是「**当时**导的是什么」",
                "",
                "同一个租户同一数据域同时只允许一个**导入**任务在跑（部分唯一索引裁决）；"
                    + "两个并行的导出是合法的"),
            new ImportExportCapability(
                "18.1.3.1",
                "异步执行",
                true,
                "任务创建后立即返回「已受理」；真正的导出 / 导入在**进程内执行器**上跑，"
                    + "任务行上的计数是已推进的进度，不是这次响应的承诺",
                "",
                "执行期间调用方的会话失效不影响已受理的任务（执行器只转发调用方自己的 bearer，"
                    + "上游仍按成员关系解析租户）"),
            new ImportExportCapability(
                "18.1.3.1",
                "进度跟踪",
                true,
                "任务行上的 total_rows / processed_rows / succeeded_rows / failed_rows 四个计数，"
                    + "由执行器每推进一批就更新；**状态机由数据库触发器钉死**"
                    + "（PENDING → RUNNING → SUCCEEDED → EXPIRED，任意非终态可 CANCELLED / FAILED）",
                "",
                "计数是**进度**，最终结论看 status"),
            new ImportExportCapability(
                "18.1.3.1",
                "结果下载",
                true,
                "文件真落磁盘（platform_io_file 记 storage_key / sha256 / 字节数），"
                    + "下载端点按 storage_key 读回；**带有效期**（expires_at），过期回 410 Gone",
                "",
                "有效期内可以反复下载；过期后文件被清掉，但**任务记录保留**（它是审计事实）"),
            new ImportExportCapability(
                "18.1.3.1",
                "失败行报告",
                true,
                "platform_io_row_error 表**逐行逐列**记录（哪一行、哪一列、原始值、原因码），"
                    + "并另出一份 CSV 报告文件可下载",
                "",
                "行号是**文件里的物理行号**（表头 = 1）—— 运营在 Excel 里数到的就是它"),
            new ImportExportCapability(
                "18.1.3.1",
                "模板下载",
                true,
                "platform_io_template 表存**列定义 + 示例行**（模板与解析器共用一份代码，"
                    + "两处各写一份列名必然对不上）；商品域的模板**代理 catalog 的模板文件**，"
                    + "不在本库复制一份",
                "",
                "只给 platform 自己拥有导入格式的域建内置模板"),
            new ImportExportCapability(
                "18.1.3.1",
                "**导出：platform 自有表的数据域**（应用与凭证 / 事件订阅 / 投递记录 / 调用埋点）",
                true,
                "jOOQ **显式 SQL** 读 platform 自己的表（AGENTS.md：不用 JPA 懒加载掩盖关键逻辑）",
                "",
                "投递记录不导出请求体（可能带买家 PII）；调用埋点不导出调用方 IP 与密钥摘要"),
            new ImportExportCapability(
                "18.1.3.1",
                "**导出：属主服务的授权只读端点**（商品 / 订单 / 库存 / 平台商品 / 凭证）",
                true,
                "经属主服务**自己的授权 API** 逐页读（catalog / trade / warehouse / channel / finance），"
                    + "**只转发调用方自己的 bearer**，不传租户号；取不到一律 503（是「读不到」，不是「没有数据」）",
                "",
                "**不在 platform 落一份副本** —— 副本会漂移（属主改了这边还留着旧的）"),
            new ImportExportCapability(
                "18.1.3.1",
                "**导入：platform 自有表的数据域**（事件订阅）",
                true,
                "逐行校验后**由本模块自己落库**（platform_webhook_subscription + "
                    + "platform_webhook_subscription_event）；校验失败的行不落库，逐行进失败行报告",
                "",
                "导入的订阅**不带签名密钥** —— 密钥明文只能显示一次，批量导入无法交付给对接方；"
                    + "请在订阅列表里逐个生成并交给对方"),
            new ImportExportCapability(
                "18.1.3.1",
                "**导入：属主服务的文件型作业**（商品域）",
                true,
                "把上传的文件**委托**给 catalog 的导入作业（multipart），轮询作业状态，"
                    + "再把上游的逐行错误回灌成 platform 自己的失败行报告",
                "",
                "商品域的导入格式（fieldKey / matchKey）归 catalog 所有，通道只代理，"
                    + "不复制一份模板到本库"),
            new ImportExportCapability(
                "18.1.3.1",
                "**导入：订单 / 库存 / 平台商品 / 凭证 / 客户**",
                false,
                "这些域的属主服务**没有面向通道的文件型导入作业端点**："
                    + "trade 的订单要走自己的下单流程（占库存 / 信用校验 / 平台回传），"
                    + "warehouse 的库存是由单据推导的余量，channel 的刊登要过平台侧校验与限流，"
                    + "finance 的凭证是会计事实，crm 根本没有面向平台的客户档案端点",
                "9.1 / 7.1 / 4.1 / 12.5.2.1 / 15.1",
                "这些域**只能导出，不能导入**。强行导入会绕过属主服务的校验链，"
                    + "把账实关系与对账关系写坏"),
            new ImportExportCapability(
                "18.1.3.1",
                "**导入：投递记录 / 调用埋点**",
                false,
                "这两个域是**只追加的执行事实**（投递记录每次投递一行；调用埋点的表连 UPDATE/DELETE "
                    + "都被触发器拒绝）。导入等于伪造「已经投递过 / 已经调用过」",
                "18.1.2.2 / 18.1.1.3",
                "只能导出核对，不可导入"));
    var gaps =
        List.of(
            new ImportExportGap(
                "18.1.3.2",
                "数据迁移",
                "platform",
                false,
                "同卷兄弟",
                "蓝图写明「上游 = 18.1.3.1」—— 数据迁移要建在导入导出通道之上；"
                    + "本车道**不依赖**它，也**没有**顺手实现它（历史迁移要试运行与回滚核对，是另一套语义）"),
            new ImportExportGap(
                "18.2.1.1",
                "财务软件",
                "platform",
                true,
                "同卷兄弟",
                "财务软件的**凭证文件导出**与本模块的通道并存：那边导的是法定凭证文件"
                    + "（Excel/XML + 科目映射），这边的凭证域导出的是凭证清单（CSV）——"
                    + "两个不同形状的文件，不是同一个功能的两份实现"),
            new ImportExportGap(
                "18.1.2.1",
                "事件订阅",
                "platform",
                true,
                "上游 + 数据域",
                "事件订阅既是本模块的一个**数据域**（可导出可导入），也是导入的**目标域**之一"));
    return new ImportExportCapabilities(capabilities, gaps);
  }

  /** 概览：几个数。 */
  public ImportExportOverview overview() {
    return new ImportExportOverview(
        count("select count(*) from platform_io_task"),
        count("select count(*) from platform_io_task where direction = 'EXPORT'"),
        count("select count(*) from platform_io_task where direction = 'IMPORT'"),
        count("select count(*) from platform_io_task where status = 'PENDING'"),
        count("select count(*) from platform_io_task where status = 'RUNNING'"),
        count("select count(*) from platform_io_task where status = 'SUCCEEDED'"),
        count("select count(*) from platform_io_task where status = 'FAILED'"),
        count("select count(*) from platform_io_row_error"),
        count("select count(*) from platform_io_file"),
        dbNow());
  }

  /**
   * 数据域目录（**两个方向各一行**）。
   *
   * <p>🔴 没接通的那几行**照样出现在目录里**并标 {@code available=false} ——
   * 混进空态等于告诉运营「这个域已经能导了」。
   */
  public List<ImportExportDomainRow> domains() {
    var out = new ArrayList<ImportExportDomainRow>();
    for (var d : ImportExportDomains.ALL) {
      out.add(
          new ImportExportDomainRow(
              d.code(),
              d.label(),
              Direction.EXPORT.name(),
              d.exportSource() != ImportExportDomains.ExportSource.NONE,
              d.exportSource().label(),
              d.ownerService(),
              d.ownerModule(),
              d.exportColumns(),
              d.exportReason(),
              d.exportLimitation()));
      out.add(
          new ImportExportDomainRow(
              d.code(),
              d.label(),
              Direction.IMPORT.name(),
              d.importSource() != ImportExportDomains.ImportSource.NONE,
              d.importSource().label(),
              d.ownerService(),
              d.ownerModule(),
              d.importColumns(),
              d.importReason(),
              ""));
    }
    return out;
  }

  // ================================================================ 任务

  /** 任务列表（真分页 + 真筛选）。 */
  public ImportExportTaskPage tasks(
      String direction, String domainCode, String status, int page, int size) {
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (direction != null && !direction.isBlank()) {
      var dir = Direction.parse(direction);
      check(dir != null, "方向只能是 EXPORT 或 IMPORT");
      where.append(" and direction = ?");
      params.add(dir.name());
    }
    if (domainCode != null && !domainCode.isBlank()) {
      where.append(" and domain_code = ?");
      params.add(domainCode.trim().toUpperCase(Locale.ROOT));
    }
    if (status != null && !status.isBlank()) {
      where.append(" and status = ?");
      params.add(status.trim().toUpperCase(Locale.ROOT));
    }
    var total =
        count(
            "select count(*) from platform_io_task" + where,
            params.toArray());
    var rows =
        sql.fetch(
            "select id,task_no,direction,domain_code,domain_label,format,status,scope::text as scope,"
                + " total_rows,processed_rows,succeeded_rows,failed_rows,result_file_id,error_file_id,"
                + " upstream_service,upstream_job_id,upstream_job_ref,error,actor_id,requested_at,"
                + " started_at,finished_at,expires_at,created_at"
                + " from platform_io_task"
                + where
                + " order by created_at desc, id limit ? offset ?",
            concat(params, size2, (long) page2 * size2));
    return new ImportExportTaskPage(rows.map(this::toTaskRow), page2, size2, total);
  }

  /** 任务详情。 */
  public ImportExportTaskRow task(UUID id) {
    var row =
        sql.fetchOne(
            "select id,task_no,direction,domain_code,domain_label,format,status,scope::text as scope,"
                + " total_rows,processed_rows,succeeded_rows,failed_rows,result_file_id,error_file_id,"
                + " upstream_service,upstream_job_id,upstream_job_ref,error,actor_id,requested_at,"
                + " started_at,finished_at,expires_at,created_at"
                + " from platform_io_task where id = ?",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "导入导出任务不存在");
    return toTaskRow(row);
  }

  private ImportExportTaskRow toTaskRow(org.jooq.Record r) {
    var scope = new LinkedHashMap<String, Object>();
    var raw = r.get("scope", String.class);
    if (raw != null && !raw.isBlank()) {
      try {
        Map<?, ?> parsed = json.readValue(raw, Map.class);
        if (parsed != null) {
          for (var entry : parsed.entrySet()) {
            scope.put(
                String.valueOf(entry.getKey()),
                entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
          }
        }
      } catch (IOException e) {
        // 快照解析不了就当空对象 —— 不因为一条读不出范围的任务把整个列表打挂。
        scope.clear();
      }
    }
    var upstreamJobId = r.get("upstream_job_id", UUID.class);
    return new ImportExportTaskRow(
        r.get("id", UUID.class),
        r.get("task_no", String.class),
        r.get("direction", String.class),
        r.get("domain_code", String.class),
        r.get("domain_label", String.class),
        r.get("format", String.class),
        r.get("status", String.class),
        scope,
        intValue(r.get("total_rows", Integer.class)),
        intValue(r.get("processed_rows", Integer.class)),
        intValue(r.get("succeeded_rows", Integer.class)),
        intValue(r.get("failed_rows", Integer.class)),
        r.get("result_file_id", UUID.class),
        r.get("error_file_id", UUID.class),
        r.get("upstream_service", String.class),
        upstreamJobId == null ? "" : upstreamJobId.toString(),
        r.get("upstream_job_ref", String.class),
        r.get("error", String.class),
        r.get("actor_id", UUID.class),
        r.get("requested_at", OffsetDateTime.class),
        r.get("started_at", OffsetDateTime.class),
        r.get("finished_at", OffsetDateTime.class),
        r.get("expires_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 发起一个**导出**任务（已受理，异步执行）。
   *
   * <p>🔴 **不因为端口没接通就假装能导**：该域的导出未接通 ⇒ 直接 409 并写明原因与归属。
   */
  public ImportExportTaskRow createExport(
      String domainCode, Map<String, Object> scope, AccessService.Access actor) {
    var domain = ImportExportDomains.require(domainCode);
    if (domain.exportSource() == ImportExportDomains.ExportSource.NONE) {
      conflict(
          "「"
              + domain.label()
              + "」的导出未接通："
              + domain.exportReason()
              + "（归属模块 "
              + domain.ownerModule()
              + "）");
    }
    var normalized = validateScope(domain, scope);
    var taskNo = nextTaskNo();
    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_io_task(id,tenant_id,task_no,direction,domain_code,domain_label,"
            + " format,status,scope,actor_id,requested_at,created_at,updated_at)"
            + " values(?,?,?,'EXPORT',?,?,'CSV','PENDING',?::jsonb,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        taskNo,
        domain.code(),
        domain.label(),
        toJson(normalized),
        actor.userId(),
        now,
        now,
        now);
    return task(id);
  }

  /**
   * 发起一个**导入**任务：上传文件 → 建任务（已受理，异步执行）。
   *
   * <p>源文件**立刻落存储**：这样即使执行器还没跑到，文件也已经在（失败行报告要引用它）。
   */
  public ImportExportTaskRow createImport(
      String domainCode,
      String filename,
      byte[] content,
      Map<String, Object> scope,
      AccessService.Access actor) {
    var domain = ImportExportDomains.require(domainCode);
    if (domain.importSource() == ImportExportDomains.ImportSource.NONE) {
      conflict(
          "「"
              + domain.label()
              + "」的导入未接通："
              + domain.importReason()
              + "（归属模块 "
              + domain.ownerModule()
              + "）");
    }
    check(content != null && content.length > 0, "上传的文件是空的");
    check(
        content.length <= MAX_IMPORT_BYTES,
        "上传文件超过单次上限（" + (MAX_IMPORT_BYTES / 1024 / 1024) + " MB），请拆成多个文件");
    var normalized = validateScope(domain, scope);
    var taskNo = nextTaskNo();
    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_io_task(id,tenant_id,task_no,direction,domain_code,domain_label,"
            + " format,status,scope,actor_id,requested_at,created_at,updated_at)"
            + " values(?,?,?,'IMPORT',?,?,'CSV','PENDING',?::jsonb,?,?::timestamptz,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        taskNo,
        domain.code(),
        domain.label(),
        toJson(normalized),
        actor.userId(),
        now,
        now,
        now);
    writeFile(id, FileRole.SOURCE, safeFilename(filename), content, null);
    return task(id);
  }

  /** 作废一个任务（导错范围时第一件事就是让它不能再下载）。 */
  public ImportExportTaskRow cancel(UUID id, AccessService.Access actor) {
    var task = task(id);
    if (TaskStatus.valueOf(task.status()).name().equals("PENDING")
        || task.status().equals("RUNNING")) {
      sql.execute(
          "update platform_io_task set status = 'CANCELLED', updated_at = ?::timestamptz where id = ?",
          dbNow(),
          id);
    } else {
      conflict("当前状态「" + label(task.status()) + "」不能作废");
    }
    return task(id);
  }

  // ================================================================ 范围

  /**
   * 校验并归一化范围。
   *
   * <p>🔴 **未知字段直接 400**：默默忽略一个拼错的筛选条件，会让用户以为导的是「筛过的」，
   * 而实际导的是全量 —— 这是最危险的一种错。
   */
  private Map<String, Object> validateScope(
      ImportExportDomains.Domain domain, Map<String, Object> scope) {
    var allowed = new LinkedHashSet<String>();
    for (var f : domain.exportScope()) allowed.add(f.fieldKey());
    for (var f : ImportExportDomains.importScope(domain.code())) allowed.add(f.fieldKey());
    var out = new LinkedHashMap<String, Object>();
    if (scope == null) return out;
    for (var entry : scope.entrySet()) {
      var key = entry.getKey();
      check(allowed.contains(key), "未知的范围字段「" + key + "」（该数据域支持：" + allowed + "）");
      var value = entry.getValue();
      if (value == null) continue;
      var text = String.valueOf(value).trim();
      if (text.isEmpty()) continue;
      out.put(key, text);
    }
    return out;
  }

  private String toJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (IOException e) {
      throw new IllegalStateException("范围快照序列化失败", e);
    }
  }

  // ================================================================ 文件

  /** 写一份文件到存储并落元信息。 */
  private ImportExportFileRow writeFile(
      UUID taskId, FileRole role, String filename, byte[] content, OffsetDateTime expiresAt) {
    var id = UUID.randomUUID();
    var owner = this.task(taskId);
    // 存储键带**租户段**：一个租户算不出另一个租户的键（实际隔离仍由 RLS 保证，这里是第二道）。
    var key =
        currentTenant().toString().replace("-", "")
            + "/"
            + owner.taskNo()
            + "/"
            + role.name().toLowerCase(Locale.ROOT)
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
        "insert into platform_io_file(id,tenant_id,task_id,role,filename,storage_key,content_type,"
            + " content_sha256,byte_size,row_count,expires_at,created_at)"
            + " values(?,?,?,?,?,?,'text/csv; charset=UTF-8',?,?::numeric,?,?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        taskId,
        role.name(),
        filename,
        key,
        sha256(content),
        BigDecimal.valueOf(bytes),
        0,
        expiresAt,
        dbNow());
    return file(id);
  }

  /** 文件元信息。 */
  public ImportExportFileRow file(UUID id) {
    var row =
        sql.fetchOne(
            "select id,task_id,role,filename,content_type,content_sha256,byte_size,row_count,"
                + " expires_at,created_at from platform_io_file where id = ?",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "文件不存在");
    return toFileRow(row);
  }

  private static ImportExportFileRow toFileRow(org.jooq.Record r) {
    return new ImportExportFileRow(
        r.get("id", UUID.class),
        r.get("task_id", UUID.class),
        r.get("role", String.class),
        r.get("filename", String.class),
        r.get("content_type", String.class),
        r.get("content_sha256", String.class),
        r.get("byte_size", BigDecimal.class),
        intValue(r.get("row_count", Integer.class)),
        r.get("expires_at", OffsetDateTime.class),
        r.get("created_at", OffsetDateTime.class));
  }

  /**
   * 下载一份任务的文件。
   *
   * <p>🔴 **过期回 410 Gone**，不靠前端禁用按钮。文件过期后从存储里清掉，
   * 但**任务记录保留**（它是审计事实）。
   */
  public ImportExportFileContent download(UUID taskId, FileRole role) {
    var task = task(taskId);
    var fileId = role == FileRole.RESULT ? task.resultFileId() : task.errorFileId();
    if (fileId == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND,
          role == FileRole.RESULT ? "这个任务还没有结果文件" : "这个任务还没有失败行报告");
    }
    var file = file(fileId);
    // 🔴 **任务级与文件级的有效期都要看**：任务级是「这次导出的下载窗口」，
    // 文件级是「这份文件本身的截止时刻」—— 只看一个就可能出现「任务还在、文件早过期了」
    // 或「任务过期了、文件还在」的缝。
    var now = dbNow();
    var deadline = file.expiresAt();
    if (task.expiresAt() != null && (deadline == null || task.expiresAt().isBefore(deadline))) {
      deadline = task.expiresAt();
    }
    if (deadline != null && deadline.isBefore(now)) {
      throw new ResponseStatusException(
          HttpStatus.GONE,
          "文件已过下载有效期（"
              + deadline
              + "）。任务记录仍然保留，可重新发起一次导出。");
    }
    var key =
        sql.fetchOne("select storage_key from platform_io_file where id = ?", fileId)
            .get(0, String.class);
    try {
      var content = storage.read(key);
      return new ImportExportFileContent(file.filename(), file.contentType(), content);
    } catch (IOException e) {
      throw new ResponseStatusException(
          HttpStatus.GONE, "文件已不在存储里（可能已被过期清理）：" + e.getMessage());
    }
  }

  // ================================================================ 失败行

  /** 失败行明细（真分页 + 真筛选）。 */
  public ImportExportRowErrorPage rowErrors(UUID taskId, String severity, int page, int size) {
    task(taskId);
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where task_id = ?");
    var params = new ArrayList<Object>();
    params.add(taskId);
    if (severity != null && !severity.isBlank()) {
      where.append(" and severity = ?");
      params.add(severity.trim().toUpperCase(Locale.ROOT));
    }
    var total = count("select count(*) from platform_io_row_error" + where, params.toArray());
    var errorCount =
        count(
            "select count(*) from platform_io_row_error where task_id = ? and severity = 'ERROR'",
            taskId);
    var warningCount =
        count(
            "select count(*) from platform_io_row_error where task_id = ? and severity = 'WARNING'",
            taskId);
    var rows =
        sql.fetch(
            "select id,task_id,row_no,column_name,raw_value,error_code,message,severity,created_at"
                + " from platform_io_row_error"
                + where
                + " order by row_no, column_name, error_code limit ? offset ?",
            concat(params, size2, (long) page2 * size2));
    return new ImportExportRowErrorPage(
        rows.map(
            r ->
                new ImportExportRowErrorRow(
                    r.get("id", UUID.class),
                    r.get("task_id", UUID.class),
                    intValue(r.get("row_no", Integer.class)),
                    r.get("column_name", String.class),
                    r.get("raw_value", String.class),
                    r.get("error_code", String.class),
                    r.get("message", String.class),
                    r.get("severity", String.class),
                    r.get("created_at", OffsetDateTime.class))),
        page2,
        size2,
        total,
        (int) errorCount,
        (int) warningCount);
  }

  // ================================================================ 模板

  /** 模板列表（真分页 + 真筛选）。 */
  public ImportExportTemplatePage templates(String domainCode, int page, int size) {
    // 内置模板**按需落地**（每个租户各一份）：迁移里不预置行 ——
    // 「不存在的租户」没有模板可给，而模板一旦被引用就该在手边。
    ensureBuiltinTemplates();
    var size2 = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var page2 = Math.max(page, 0);
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    if (domainCode != null && !domainCode.isBlank()) {
      where.append(" and domain_code = ?");
      params.add(domainCode.trim().toUpperCase(Locale.ROOT));
    }
    var total = count("select count(*) from platform_io_template" + where, params.toArray());
    var rows =
        sql.fetch(
            "select id,domain_code,code,name,builtin,enabled,remark,created_at"
                + " from platform_io_template"
                + where
                + " order by builtin desc, domain_code, code limit ? offset ?",
            concat(params, size2, (long) page2 * size2));
    return new ImportExportTemplatePage(
        rows.map(
            r ->
                new ImportExportTemplateRow(
                    r.get("id", UUID.class),
                    r.get("domain_code", String.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("builtin", Boolean.class),
                    r.get("enabled", Boolean.class),
                    r.get("remark", String.class),
                    r.get("created_at", OffsetDateTime.class))),
        page2,
        size2,
        total);
  }

  /** 模板详情（列定义 + 示例行）。 */
  public ImportExportTemplateDetail template(UUID id) {
    var row =
        sql.fetchOne(
            "select id,domain_code,code,name,builtin,enabled,remark,created_at,columns::text as columns,"
                + " sample_row::text as sample_row"
                + " from platform_io_template where id = ?",
            id);
    if (row == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在");
    var templateRow =
        new ImportExportTemplateRow(
            row.get("id", UUID.class),
            row.get("domain_code", String.class),
            row.get("code", String.class),
            row.get("name", String.class),
            row.get("builtin", Boolean.class),
            row.get("enabled", Boolean.class),
            row.get("remark", String.class),
            row.get("created_at", OffsetDateTime.class));
    var columns = new ArrayList<ImportExportColumn>();
    try {
      var parsed =
          json.readValue(row.get("columns", String.class), ImportExportColumn[].class);
      if (parsed != null) {
        for (var c : parsed) columns.add(c);
      }
    } catch (IOException e) {
      throw new IllegalStateException("模板列定义读不出来", e);
    }
    var sample = new LinkedHashMap<String, String>();
    try {
      Map<?, ?> parsed = json.readValue(row.get("sample_row", String.class), Map.class);
      if (parsed != null) {
        for (var entry : parsed.entrySet()) {
          sample.put(
              String.valueOf(entry.getKey()),
              entry.getValue() == null ? "" : String.valueOf(entry.getValue()));
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException("模板示例行读不出来", e);
    }
    return new ImportExportTemplateDetail(templateRow, columns, sample);
  }

  /**
   * 下载模板 CSV（列头一行 + 示例行）。
   *
   * <p>🔴 **模板文件是即时生成的**，不是落库的文件：它是**契约**（列定义），
   * 不是一次性的导出产物 —— 没有有效期。
   */
  public ImportExportFileContent templateFile(UUID id) {
    var detail = template(id);
    var header = new ArrayList<String>();
    for (var c : detail.columns()) header.add(c.header());
    var sample = new ArrayList<String[]>();
    if (!detail.columns().isEmpty()) {
      var values = new String[detail.columns().size()];
      for (var i = 0; i < values.length; i++) {
        var key = detail.columns().get(i).fieldKey();
        values[i] = detail.sampleRow().getOrDefault(key, detail.columns().get(i).sampleValue());
      }
      sample.add(values);
    }
    var content = ImportExportCsv.write(header, sample);
    return new ImportExportFileContent(
        detail.template().code() + ".csv", "text/csv; charset=UTF-8", content);
  }

  /** 落地**全部**内置模板（每个租户各一份）。 */
  public void ensureBuiltinTemplates() {
    for (var domain : ImportExportDomains.ALL) {
      if (builtinTemplate(domain) == null) continue;
      ensureBuiltinTemplate(domain.code());
    }
  }

  /**
   * 落地内置模板（**按租户各一份**，首次用到该数据域时）。
   *
   * <p>⚠️ 模板的列定义与解析器**共用一份代码**（{@link #builtinTemplate}）——
   * 两处各写一份列名，模板与解析必然对不上。
   */
  public ImportExportTemplateRow ensureBuiltinTemplate(String domainCode) {
    var domain = ImportExportDomains.require(domainCode);
    var builtin = builtinTemplate(domain);
    if (builtin == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "「" + domain.label() + "」没有内置模板");
    }
    var existing =
        sql.fetchOne(
            "select id from platform_io_template where domain_code = ? and code = ?",
            domain.code(),
            builtin.code());
    if (existing != null) return template(existing.get("id", UUID.class)).template();
    var id = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_io_template(id,tenant_id,domain_code,code,name,columns,sample_row,"
            + " builtin,enabled,remark,created_at,updated_at)"
            + " values(?,?,?,?,?,?::jsonb,?::jsonb,true,true,'',?::timestamptz,?::timestamptz)",
        id,
        currentTenant(),
        domain.code(),
        builtin.code(),
        builtin.name(),
        toJson(List.of(builtin.columns().toArray(new ImportExportColumn[0]))),
        toJson(builtin.sample()),
        now,
        now);
    return template(id).template();
  }

  /** 内置模板定义（**解析器与模板共用这一份**）。 */
  private BuiltinTemplate builtinTemplate(ImportExportDomains.Domain domain) {
    if (!"PLATFORM_SUBSCRIPTION".equals(domain.code())) return null;
    var columns =
        List.of(
            new ImportExportColumn(
                "name", "订阅名称", "STRING", true, "订单事件订阅", "同一个应用下不可重名"),
            new ImportExportColumn(
                "target_url",
                "订阅端点地址",
                "STRING",
                true,
                "https://example.com/hook",
                "必须以 http:// 或 https:// 开头"),
            new ImportExportColumn(
                "app_code",
                "所属应用",
                "STRING",
                true,
                "erp-connector",
                "必须是本租户已注册的应用编码"),
            new ImportExportColumn(
                "event_types",
                "订阅事件类型",
                "STRING",
                true,
                "order.created|order.paid",
                "多个用 | 分隔；每个都必须在本租户已登记的事件类型里"),
            new ImportExportColumn(
                "description", "说明", "STRING", false, "对接方 ERP 接收订单事件", "选填"));
    var sample = new LinkedHashMap<String, String>();
    sample.put("name", "订单事件订阅");
    sample.put("target_url", "https://example.com/hook");
    sample.put("app_code", "erp-connector");
    sample.put("event_types", "order.created|order.paid");
    sample.put("description", "对接方 ERP 接收订单事件");
    return new BuiltinTemplate("SUBSCRIPTION_STANDARD", "事件订阅标准模板", columns, sample);
  }

  /** 内置模板（列 + 示例行）。 */
  private record BuiltinTemplate(
      String code, String name, List<ImportExportColumn> columns, Map<String, String> sample) {}

  // ================================================================ 执行（异步）

  /**
   * 执行一个任务（**由执行器在独立线程上调用**）。
   *
   * <p>🔴 <b>状态推进是「事实」，不是承诺</b>：每推进一批就更新计数；
   * 失败了就把失败原因写进任务行（成功 / 作废 / 过期时也保留 —— 它是「当时发生了什么」）。
   */
  public void execute(UUID taskId, String bearer, UUID actorUserId) {
    var task = task(taskId);
    if (!task.status().equals("PENDING")) {
      // 已被取消或已被别的执行器领走：什么都不做。
      return;
    }
    var now = dbNow();
    var claimed =
        sql.execute(
            "update platform_io_task set status = 'RUNNING', started_at = ?::timestamptz,"
                + " updated_at = ?::timestamptz"
                + " where id = ? and status = 'PENDING'",
            now,
            now,
            taskId);
    if (claimed == 0) return;
    try {
      if (task.direction().equals(Direction.EXPORT.name())) {
        runExport(taskId, bearer);
      } else {
        runImport(taskId, bearer, actorUserId);
      }
    } catch (RuntimeException e) {
      fail(taskId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /** 导出：把数据域的行拉出来 → 生成 CSV → 落存储 → 落文件记录。 */
  private void runExport(UUID taskId, String bearer) {
    var task = task(taskId);
    var domain = ImportExportDomains.require(task.domainCode());
    var scope = task.scope();
    List<String[]> rows;
    switch (domain.exportSource()) {
      case LOCAL_TABLE -> rows = exportLocal(domain.code(), scope);
      case AUTHORIZED_PORT -> rows = exportPort(domain.code(), scope, bearer);
      case NONE -> throw new IllegalStateException("导出未接通：" + domain.exportReason());
      default -> throw new IllegalStateException("未知的导出来源");
    }
    if (rows.size() > MAX_EXPORT_ROWS) {
      throw new IllegalStateException(
          "导出行数超过单次上限（"
              + MAX_EXPORT_ROWS
              + " 行，本次 "
              + rows.size()
              + " 行）。请缩小范围后重试 —— 本通道不会只导一部分，"
              + "那样导出的文件看起来是完整的而它不是。");
    }
    var header = new ArrayList<String>();
    for (var c : domain.exportColumns()) header.add(c.header());
    var content = ImportExportCsv.write(header, rows);
    var expiresAt = dbNow().plusHours(DEFAULT_EXPIRY_HOURS);
    var file =
        writeFile(
            taskId,
            FileRole.RESULT,
            domain.code().toLowerCase(Locale.ROOT) + "-" + task.taskNo() + ".csv",
            content,
            expiresAt);
    sql.execute(
        "update platform_io_task set status = 'SUCCEEDED', total_rows = ?, processed_rows = ?,"
            + " succeeded_rows = ?, failed_rows = 0, result_file_id = ?, expires_at = ?::timestamptz,"
            + " finished_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
        rows.size(),
        rows.size(),
        rows.size(),
        file.id(),
        expiresAt,
        dbNow(),
        dbNow(),
        taskId);
  }

  /** 本地表导出（jOOQ 显式 SQL；**只读 platform 自己的表**）。 */
  private List<String[]> exportLocal(String domainCode, Map<String, Object> scope) {
    var where = new StringBuilder(" where 1 = 1");
    var params = new ArrayList<Object>();
    var from = scope.get("from");
    var to = scope.get("to");
    switch (domainCode) {
      case "PLATFORM_APP" -> {
        var status = scope.get("status");
        if (status != null) {
          where.append(" and status = ?");
          params.add(status.toString().toUpperCase(Locale.ROOT));
        }
        var q = scope.get("q");
        if (q != null) {
          where.append(" and (app_code ilike ? or app_name ilike ?)");
          params.add("%" + q + "%");
          params.add("%" + q + "%");
        }
        var rows =
            sql.fetch(
                "select app_code,app_name,app_type,status,description,contact_name,contact_email,"
                    + " created_at from platform_app"
                    + where
                    + " order by app_code limit ?",
                concat(params, MAX_EXPORT_ROWS + 1));
        return rows.map(r -> new String[] {
          cell(r.get("app_code", String.class)),
          cell(r.get("app_name", String.class)),
          cell(r.get("app_type", String.class)),
          cell(r.get("status", String.class)),
          cell(r.get("description", String.class)),
          cell(r.get("contact_name", String.class)),
          cell(r.get("contact_email", String.class)),
          cell(r.get("created_at", OffsetDateTime.class))
        });
      }
      case "PLATFORM_SUBSCRIPTION" -> {
        var status = scope.get("status");
        if (status != null) {
          where.append(" and s.status = ?");
          params.add(status.toString().toUpperCase(Locale.ROOT));
        }
        var q = scope.get("q");
        if (q != null) {
          where.append(" and (s.name ilike ? or s.target_url ilike ?)");
          params.add("%" + q + "%");
          params.add("%" + q + "%");
        }
        var rows =
            sql.fetch(
                "select s.name,s.target_url,s.status,a.app_code,"
                    + " (select string_agg(e.event_type,'|' order by e.event_type)"
                    + "    from platform_webhook_subscription_event e"
                    + "   where e.subscription_id = s.id and e.enabled) as event_types,"
                    + " s.description,s.created_at"
                    + " from platform_webhook_subscription s"
                    + " join platform_app a on a.tenant_id = s.tenant_id and a.id = s.app_id"
                    + where
                    + " order by a.app_code, s.name limit ?",
                concat(params, MAX_EXPORT_ROWS + 1));
        return rows.map(r -> new String[] {
          cell(r.get("name", String.class)),
          cell(r.get("target_url", String.class)),
          cell(r.get("status", String.class)),
          cell(r.get("app_code", String.class)),
          cell(r.get("event_types", String.class)),
          cell(r.get("description", String.class)),
          cell(r.get("created_at", OffsetDateTime.class))
        });
      }
      case "PLATFORM_DELIVERY" -> {
        var status = scope.get("status");
        if (status != null) {
          where.append(" and status = ?");
          params.add(status.toString().toUpperCase(Locale.ROOT));
        }
        var eventType = scope.get("event_type");
        if (eventType != null) {
          where.append(" and event_type = ?");
          params.add(eventType.toString());
        }
        appendWindow(where, params, "created_at", from, to);
        var rows =
            sql.fetch(
                "select event_type,schema_version,status,status_code,attempt_count,replay_count,"
                    + " target_url,last_error,created_at,delivered_at"
                    + " from platform_delivery_record"
                    + where
                    + " order by created_at desc limit ?",
                concat(params, MAX_EXPORT_ROWS + 1));
        return rows.map(r -> new String[] {
          cell(r.get("event_type", String.class)),
          cell(r.get("schema_version", Integer.class)),
          cell(r.get("status", String.class)),
          cell(r.get("status_code", Integer.class)),
          cell(r.get("attempt_count", Integer.class)),
          cell(r.get("replay_count", Integer.class)),
          cell(r.get("target_url", String.class)),
          cell(r.get("last_error", String.class)),
          cell(r.get("created_at", OffsetDateTime.class)),
          cell(r.get("delivered_at", OffsetDateTime.class))
        });
      }
      case "PLATFORM_CALL_LOG" -> {
        var outcome = scope.get("outcome");
        if (outcome != null) {
          where.append(" and outcome = ?");
          params.add(outcome.toString().toUpperCase(Locale.ROOT));
        }
        var endpoint = scope.get("endpoint_code");
        if (endpoint != null) {
          where.append(" and endpoint_code = ?");
          params.add(endpoint.toString());
        }
        appendWindow(where, params, "called_at", from, to);
        var rows =
            sql.fetch(
                "select endpoint_code,http_method,request_path,status_code,outcome,error_code,"
                    + " rate_limited,duration_ms,request_id,called_at"
                    + " from platform_api_call_log"
                    + where
                    + " order by called_at desc limit ?",
                concat(params, MAX_EXPORT_ROWS + 1));
        return rows.map(r -> new String[] {
          cell(r.get("endpoint_code", String.class)),
          cell(r.get("http_method", String.class)),
          cell(r.get("request_path", String.class)),
          cell(r.get("status_code", Integer.class)),
          cell(r.get("outcome", String.class)),
          cell(r.get("error_code", String.class)),
          cell(r.get("rate_limited", Boolean.class)),
          cell(r.get("duration_ms", BigDecimal.class)),
          cell(r.get("request_id", String.class)),
          cell(r.get("called_at", OffsetDateTime.class))
        });
      }
      default -> throw new IllegalStateException("没有本地导出的实现：" + domainCode);
    }
  }

  /** 跨服务导出（授权只读端口；**只转发调用方自己的 bearer**）。 */
  private List<String[]> exportPort(
      String domainCode, Map<String, Object> scope, String bearer) {
    var out = new ArrayList<String[]>();
    switch (domainCode) {
      case "PRODUCT" -> {
        var page = 0;
        while (page < MAX_UPSTREAM_PAGES) {
          var result =
              catalog.skus(
                  page,
                  UPSTREAM_PAGE_SIZE,
                  str(scope.get("q")),
                  str(scope.get("category")),
                  str(scope.get("status")),
                  bearer);
          if (result.items() == null || result.items().isEmpty()) {
            break;
          }
          for (var row : result.items()) {
            out.add(
                new String[] {
                  cell(row.code()),
                  cell(row.name()),
                  cell(row.spuCode()),
                  cell(row.spuName()),
                  cell(row.category()),
                  cell(row.barcode()),
                  cell(row.baseUnit()),
                  cell(row.status()),
                  cell(row.variantSignature()),
                  cell(row.updatedAt())
                });
          }
          if (out.size() > MAX_EXPORT_ROWS) break;
          page++;
          if (result.items().size() < UPSTREAM_PAGE_SIZE) break;
        }
      }
      case "ORDER" -> {
        var page = 0;
        var from = date(scope.get("ordered_from"));
        var to = date(scope.get("ordered_to"));
        while (page < MAX_UPSTREAM_PAGES) {
          var result = trade.orders(from, to, page, UPSTREAM_PAGE_SIZE, bearer);
          if (result.rows() == null || result.rows().isEmpty()) break;
          for (var row : result.rows()) {
            out.add(
                new String[] {
                  cell(row.orderNo()),
                  cell(row.orderSource()),
                  cell(row.currency()),
                  cell(row.grandTotalAmount()),
                  cell(row.lineCount()),
                  cell(row.submittedAt())
                });
          }
          if (out.size() > MAX_EXPORT_ROWS) break;
          page++;
          if (result.rows().size() < UPSTREAM_PAGE_SIZE) break;
        }
      }
      case "INVENTORY" -> {
        var page = 0;
        while (page < MAX_UPSTREAM_PAGES) {
          var result = warehouse.balances(page, UPSTREAM_PAGE_SIZE, bearer);
          if (result.rows() == null || result.rows().isEmpty()) break;
          for (var row : result.rows()) {
            out.add(
                new String[] {
                  cell(row.skuCode()),
                  cell(row.skuName()),
                  cell(row.warehouseCode()),
                  cell(row.warehouseName()),
                  cell(row.legalEntityCode()),
                  cell(row.onHand()),
                  cell(row.reserved()),
                  cell(row.available()),
                  cell(row.zeroStock()),
                  cell(row.overstock())
                });
          }
          if (out.size() > MAX_EXPORT_ROWS) break;
          page++;
          if (result.rows().size() < UPSTREAM_PAGE_SIZE) break;
        }
      }
      case "LISTING" -> {
        var page = 0;
        while (page < MAX_UPSTREAM_PAGES) {
          var result = channel.listings(page, UPSTREAM_PAGE_SIZE, bearer);
          if (result.items() == null || result.items().isEmpty()) break;
          for (var row : result.items()) {
            out.add(
                new String[] {
                  cell(row.shopCode()),
                  cell(row.shopName()),
                  cell(row.platformCode()),
                  cell(row.marketplaceCode()),
                  cell(row.externalItemId()),
                  cell(row.title()),
                  cell(row.status()),
                  cell(row.mappingStatus())
                });
          }
          if (out.size() > MAX_EXPORT_ROWS) break;
          page++;
          if (result.items().size() < UPSTREAM_PAGE_SIZE) break;
        }
      }
      case "VOUCHER" -> {
        var page = 0;
        var status = str(scope.get("status"));
        var currency = str(scope.get("currency"));
        var from = date(scope.get("date_from"));
        var to = date(scope.get("date_to"));
        while (page < MAX_UPSTREAM_PAGES) {
          var result =
              finance.vouchers(status, from, to, currency, page, UPSTREAM_PAGE_SIZE, bearer);
          if (result.items() == null || result.items().isEmpty()) break;
          for (var row : result.items()) {
            out.add(
                new String[] {
                  cell(row.voucherNo()),
                  cell(row.periodCode()),
                  cell(row.voucherDate()),
                  cell(row.currency()),
                  cell(row.status()),
                  cell(row.description()),
                  cell(row.totalDebit()),
                  cell(row.totalCredit()),
                  cell(row.lineCount())
                });
          }
          if (out.size() > MAX_EXPORT_ROWS) break;
          page++;
          if (result.items().size() < UPSTREAM_PAGE_SIZE) break;
        }
      }
      default -> throw new IllegalStateException("没有跨服务导出的实现：" + domainCode);
    }
    return out;
  }

  /** 导入：按数据域分派。 */
  private void runImport(UUID taskId, String bearer, UUID actorUserId) {
    var task = task(taskId);
    var domain = ImportExportDomains.require(task.domainCode());
    switch (domain.importSource()) {
      case LOCAL_APPLY -> runLocalImport(taskId, domain, actorUserId);
      case DELEGATED_JOB -> runDelegatedImport(taskId, domain, bearer);
      case NONE -> throw new IllegalStateException("导入未接通：" + domain.importReason());
      default -> throw new IllegalStateException("未知的导入来源");
    }
  }

  /**
   * 本地导入（platform 自有表）：逐行校验 → 合法行落库 → 失败行进失败行报告。
   *
   * <p>🔴 <b>校验失败的行不落库</b>，也不「尽力导入」产生半截数据。
   */
  private void runLocalImport(
      UUID taskId, ImportExportDomains.Domain domain, UUID actorUserId) {
    var source = sourceFile(taskId);
    var rows = ImportExportCsv.read(source.content());
    if (rows.isEmpty()) {
      throw new IllegalStateException("上传的文件里没有任何内容（连表头都没有）");
    }
    var header = rows.get(0);
    var columns = resolveColumns(domain, header);
    var body = rows.subList(1, rows.size());
    if (body.size() > MAX_IMPORT_ROWS) {
      throw new IllegalStateException(
          "文件行数超过单次上限（" + MAX_IMPORT_ROWS + " 行）。请拆成多个文件。");
    }
    var errors = new ArrayList<Object[]>();
    var receipts = new ArrayList<String[]>();
    var applied = 0;
    var seen = new LinkedHashSet<String>();
    for (var i = 0; i < body.size(); i++) {
      var rowNo = i + 2; // 表头是第 1 行
      var values = mapRow(columns, header, body.get(i));
      var rowErrors = validateSubscriptionRow(values, seen, rowNo);
      if (!rowErrors.isEmpty()) {
        errors.addAll(rowErrors);
        continue;
      }
      applySubscriptionRow(values, actorUserId);
      applied++;
      receipts.add(new String[] {cell(rowNo), "APPLIED", values.getOrDefault("name", "")});
      sql.execute(
          "update platform_io_task set processed_rows = ?, succeeded_rows = ?, failed_rows = ?,"
              + " updated_at = ?::timestamptz where id = ?",
          i + 1,
          applied,
          errors.size(),
          dbNow(),
          taskId);
    }
    writeRowErrors(taskId, errors);
    writeReceipt(taskId, receipts);
    finishImport(taskId, applied, errors.size());
  }

  /** 委托导入（属主服务的文件型作业）。 */
  private void runDelegatedImport(
      UUID taskId, ImportExportDomains.Domain domain, String bearer) {
    var owner = this.task(taskId);
    var targetEntity = owner.scope().getOrDefault("target_entity", "SPU").toString();
    var importMode = owner.scope().getOrDefault("import_mode", "CREATE").toString();
    var templates = catalog.templates(targetEntity, bearer);
    if (templates.items() == null || templates.items().isEmpty()) {
      throw new IllegalStateException(
          "catalog 服务里没有目标对象「"
              + targetEntity
              + "」的启用导入模板 —— 请先在商品域的导入向导里建一个模板，再来通道导入。");
    }
    var template =
        templates.items().stream()
            .filter(t -> t.enabled())
            .findFirst()
            .orElse(templates.items().get(0));
    var source = sourceFile(taskId);
    var uploaded = catalog.uploadImport(template.id(), importMode, source.filename(), source.content(), bearer);
    sql.execute(
        "update platform_io_task set upstream_service = 'catalog', upstream_job_id = ?,"
            + " upstream_job_ref = ?, total_rows = ?, updated_at = ?::timestamptz where id = ?",
        uploaded.jobId(),
        "catalog:" + uploaded.jobId(),
        uploaded.rowCount(),
        dbNow(),
        taskId);
    // catalog 的导入作业要「先预校验、再执行」两步；这里**替属主跑完这两步**，
    // 再把上游的逐行错误回灌成自己的失败行报告。
    var job = catalog.prevalidate(uploaded.jobId(), bearer);
    job = catalog.executeImport(uploaded.jobId(), bearer);
    var errors = new ArrayList<Object[]>();
    var page = 0;
    long total = 0;
    while (page < MAX_UPSTREAM_PAGES) {
      var errorPage = catalog.importErrors(uploaded.jobId(), null, page, UPSTREAM_PAGE_SIZE, bearer);
      if (errorPage.items() == null || errorPage.items().isEmpty()) break;
      for (var e : errorPage.items()) {
        errors.add(
            new Object[] {
              e.rowNo(),
              e.fieldKey() == null ? "" : e.fieldKey(),
              "",
              e.code() == null ? "UPSTREAM_ERROR" : e.code(),
              e.message() == null ? "上游导入作业报告了失败" : e.message(),
              e.severity() == null ? "ERROR" : e.severity()
            });
      }
      total += errorPage.items().size();
      if (errorPage.items().size() < UPSTREAM_PAGE_SIZE) break;
      page++;
    }
    writeRowErrors(taskId, errors);
    writeReceipt(taskId, delegatedReceipts(uploaded.jobId(), bearer));
    var applied = Math.max(job.appliedCount(), 0);
    var failed = Math.max(job.failedCount(), 0);
    if (failed == 0 && !errors.isEmpty()) failed = (int) total;
    finishImport(taskId, applied, failed);
  }

  /** 一行订阅数据的校验（返回失败项；空列表表示这一行合法）。 */
  private List<Object[]> validateSubscriptionRow(
      Map<String, String> values, Set<String> seen, int rowNo) {
    var errors = new ArrayList<Object[]>();
    var name = values.getOrDefault("name", "").trim();
    var targetUrl = values.getOrDefault("target_url", "").trim();
    var appCode = values.getOrDefault("app_code", "").trim();
    var eventTypes = values.getOrDefault("event_types", "").trim();
    if (name.isEmpty()) {
      errors.add(new Object[] {rowNo, "", "", "MISSING_NAME", "订阅名称不能为空", "ERROR"});
    }
    if (targetUrl.isEmpty()) {
      errors.add(new Object[] {rowNo, "", "", "MISSING_TARGET_URL", "订阅端点地址不能为空", "ERROR"});
    } else if (!targetUrl.startsWith("http://") && !targetUrl.startsWith("https://")) {
      errors.add(
          new Object[] {
            rowNo, "", "", "INVALID_TARGET_URL", "订阅端点地址必须以 http:// 或 https:// 开头", "ERROR"
          });
    }
    UUID appId = null;
    if (appCode.isEmpty()) {
      errors.add(new Object[] {rowNo, "", "", "MISSING_APP_CODE", "所属应用不能为空", "ERROR"});
    } else {
      var app =
          sql.fetchOne("select id from platform_app where app_code = ?", appCode);
      if (app == null) {
        errors.add(
            new Object[] {
              rowNo, "", "", "APP_NOT_FOUND", "本租户没有编码为「" + appCode + "」的应用", "ERROR"
            });
      } else {
        appId = app.get("id", UUID.class);
      }
    }
    var types = new ArrayList<String>();
    if (eventTypes.isEmpty()) {
      errors.add(new Object[] {rowNo, "", "", "MISSING_EVENT_TYPES", "订阅事件类型不能为空", "ERROR"});
    } else {
      for (var raw : eventTypes.split("\\|")) {
        var type = raw.trim();
        if (type.isEmpty()) continue;
        types.add(type);
        var known =
            sql.fetchOne(
                "select max(schema_version) as v from platform_webhook_event_type where event_type = ?",
                type);
        if (known == null || known.get("v", Integer.class) == null) {
          errors.add(
              new Object[] {
                rowNo, "", "", "EVENT_TYPE_UNKNOWN", "本租户没有登记事件类型「" + type + "」", "ERROR"
              });
        }
      }
      if (types.isEmpty()) {
        errors.add(
            new Object[] {rowNo, "", "", "MISSING_EVENT_TYPES", "订阅事件类型不能为空", "ERROR"});
      }
    }
    if (appId != null && !name.isEmpty()) {
      var key = appId + "|" + name;
      if (!seen.add(key)) {
        errors.add(
            new Object[] {
              rowNo, "", "", "DUPLICATE_NAME", "同一个应用下已经有叫「" + name + "」的订阅了", "ERROR"
            });
      }
      var exists =
          sql.fetchOne(
              "select count(*) from platform_webhook_subscription where app_id = ? and name = ?",
              appId,
              name);
      if (exists != null && exists.get(0, Long.class) > 0) {
        errors.add(
            new Object[] {
              rowNo, "", "", "SUBSCRIPTION_EXISTS", "本租户已经有叫「" + name + "」的订阅了", "ERROR"
            });
      }
    }
    values.put("__app_id", appId == null ? "" : appId.toString());
    values.put("__event_types", String.join("|", types));
    return errors;
  }

  /** 落一行订阅（真写库）。 */
  private void applySubscriptionRow(Map<String, String> values, UUID actorUserId) {
    var appId = UUID.fromString(values.get("__app_id"));
    var name = values.get("name").trim();
    var targetUrl = values.get("target_url").trim();
    var description = values.getOrDefault("description", "").trim();
    var subscriptionId = UUID.randomUUID();
    var now = dbNow();
    sql.execute(
        "insert into platform_webhook_subscription(id,tenant_id,app_id,name,target_url,status,"
            + " description,created_by,created_at,updated_at)"
            + " values(?,?,?,?,?,'ACTIVE',?,?,?::timestamptz,?::timestamptz)",
        subscriptionId,
        currentTenant(),
        appId,
        name,
        targetUrl,
        description,
        actorUserId,
        now,
        now);
    for (var type : values.getOrDefault("__event_types", "").split("\\|")) {
      if (type.isBlank()) continue;
      var version =
          sql.fetchOne(
                  "select max(schema_version) as v from platform_webhook_event_type where event_type = ?",
                  type)
              .get("v", Integer.class);
      sql.execute(
          "insert into platform_webhook_subscription_event(id,tenant_id,subscription_id,event_type,"
              + " schema_version,filter,enabled,created_at)"
              + " values(?,?,?,?,?::int,'{}'::jsonb,true,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          subscriptionId,
          type,
          version,
          now);
    }
  }

  /** 把列头映射到字段键（中文列头或字段键都认）。 */
  private List<ImportExportColumn> resolveColumns(
      ImportExportDomains.Domain domain, String[] header) {
    var byHeader = new LinkedHashMap<String, String>();
    for (var i = 0; i < header.length; i++) {
      byHeader.put(header[i].trim(), header[i].trim());
    }
    var columns = new ArrayList<ImportExportColumn>();
    var missing = new ArrayList<String>();
    for (var c : domain.importColumns()) {
      if (byHeader.containsKey(c.header()) || byHeader.containsKey(c.fieldKey())) {
        columns.add(c);
      } else if (c.required()) {
        missing.add(c.header());
      }
    }
    check(missing.isEmpty(), "文件缺少必填列：" + String.join("、", missing));
    return columns;
  }

  /** 按列定义把一行映射成字段键 → 值。 */
  private Map<String, String> mapRow(
      List<ImportExportColumn> columns, String[] header, String[] row) {
    var values = new LinkedHashMap<String, String>();
    for (var i = 0; i < header.length; i++) {
      var key = header[i].trim();
      for (var c : columns) {
        if (c.header().equals(key) || c.fieldKey().equals(key)) {
          values.put(c.fieldKey(), i < row.length ? row[i].trim() : "");
        }
      }
    }
    for (var c : columns) {
      values.putIfAbsent(c.fieldKey(), "");
    }
    return values;
  }

  /**
   * 落**导入回执**（结果文件）。
   *
   * <p>🔴 **导入也必须有结果文件**：蓝图输出是「任务记录 + 文件 + 错误报告」三件套，
   * 而「成功却没有文件」的任务在数据上是在骗人（V415 的 CHECK 直接挡住）。
   * ⇒ 导入成功时落一份回执 CSV：**哪一行变成了哪条记录**。
   */
  private void writeReceipt(UUID taskId, List<String[]> receipts) {
    var header = List.of("行号", "结果", "说明");
    var content = ImportExportCsv.write(header, receipts);
    var file =
        writeFile(
            taskId,
            FileRole.RESULT,
            "import-receipt-" + taskId + ".csv",
            content,
            dbNow().plusHours(DEFAULT_EXPIRY_HOURS));
    sql.execute(
        "update platform_io_task set result_file_id = ? where id = ?", file.id(), taskId);
  }

  /** 委托导入的回执（取上游的逐行结果：哪一行变成了哪条记录）。 */
  private List<String[]> delegatedReceipts(UUID upstreamJobId, String bearer) {
    var out = new ArrayList<String[]>();
    var page = 0;
    while (page < MAX_UPSTREAM_PAGES) {
      var rows = catalog.importRows(upstreamJobId, page, UPSTREAM_PAGE_SIZE, bearer);
      if (rows.items() == null || rows.items().isEmpty()) break;
      for (var row : rows.items()) {
        var values = row.values();
        var key =
            values == null
                ? ""
                : values.getOrDefault("spu_code", values.getOrDefault("sku_code", ""));
        out.add(new String[] {cell(row.rowNo()), cell(row.outcome()), cell(key)});
      }
      if (rows.items().size() < UPSTREAM_PAGE_SIZE) break;
      page++;
    }
    return out;
  }

  /** 落失败行 + 生成失败行报告文件。 */
  private void writeRowErrors(UUID taskId, List<Object[]> errors) {
    sql.execute("delete from platform_io_row_error where task_id = ?", taskId);
    for (var e : errors) {
      sql.execute(
          "insert into platform_io_row_error(id,tenant_id,task_id,row_no,column_name,raw_value,"
              + " error_code,message,severity,created_at)"
              + " values(?,?,?,?::int,?,?,?,?,?,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          taskId,
          (Integer) e[0],
          (String) e[1],
          (String) e[2],
          (String) e[3],
          (String) e[4],
          (String) e[5],
          dbNow());
    }
    if (errors.isEmpty()) return;
    var header =
        List.of("行号", "列名", "原始值", "原因码", "原因说明", "级别");
    var rows = new ArrayList<String[]>();
    for (var e : errors) {
      rows.add(
          new String[] {
            cell(e[0]), cell(e[1]), cell(e[2]), cell(e[3]), cell(e[4]), cell(e[5])
          });
    }
    var content = ImportExportCsv.write(header, rows);
    var file =
        writeFile(
            taskId,
            FileRole.ERROR_REPORT,
            "import-errors-" + taskId + ".csv",
            content,
            dbNow().plusHours(DEFAULT_EXPIRY_HOURS));
    sql.execute(
        "update platform_io_task set error_file_id = ? where id = ?", file.id(), taskId);
  }

  /** 收尾一个导入任务。 */
  private void finishImport(UUID taskId, int applied, int failed) {
    var now = dbNow();
    var expiresAt = now.plusHours(DEFAULT_EXPIRY_HOURS);
    sql.execute(
        "update platform_io_task set status = 'SUCCEEDED', total_rows = ?, processed_rows = ?,"
            + " succeeded_rows = ?, failed_rows = ?, expires_at = ?::timestamptz,"
            + " finished_at = ?::timestamptz, updated_at = ?::timestamptz where id = ?",
        applied + failed,
        applied + failed,
        applied,
        failed,
        expiresAt,
        now,
        now,
        taskId);
  }

  /** 任务失败（写明原因；**一次数据都不写**是这里的原则）。 */
  private void fail(UUID taskId, String reason) {
    sql.execute(
        "update platform_io_task set status = 'FAILED', error = ?, finished_at = ?::timestamptz,"
            + " updated_at = ?::timestamptz where id = ?",
        reason == null ? "执行失败" : reason,
        dbNow(),
        dbNow(),
        taskId);
  }

  /** 源文件（导入用）。 */
  private ImportExportFileContent sourceFile(UUID taskId) {
    var row =
        sql.fetchOne(
            "select filename,storage_key from platform_io_file where task_id = ? and role = 'SOURCE'",
            taskId);
    if (row == null) throw new IllegalStateException("找不到上传的源文件");
    var key = row.get("storage_key", String.class);
    try {
      return new ImportExportFileContent(
          row.get("filename", String.class), "text/csv; charset=UTF-8", storage.read(key));
    } catch (IOException e) {
      throw new IllegalStateException("源文件读不出来：" + e.getMessage());
    }
  }

  // ================================================================ 过期清理

  /**
   * 清理过期任务：把到期任务标 EXPIRED，并删掉它们的文件本体。
   *
   * <p>🔴 **任务记录保留**（它是审计事实：谁在什么时候导出了什么），
   * 只让**文件**不再可下载 —— 蓝图要的是「避免存储无限增长」，不是「抹掉历史」。
   */
  public int expireDue() {
    var now = dbNow();
    var due =
        sql.fetch(
            "select id from platform_io_task where status = 'SUCCEEDED' and expires_at is not null"
                + " and expires_at <= ?::timestamptz",
            now);
    var expired = 0;
    for (var r : due) {
      var id = r.get("id", UUID.class);
      var files =
          sql.fetch("select storage_key from platform_io_file where task_id = ?", id);
      for (var f : files) {
        try {
          storage.delete(f.get("storage_key", String.class));
        } catch (IOException e) {
          // 删不掉就留着（下次再删）；**不能因此中断整批清理**。
        }
      }
      sql.execute(
          "update platform_io_task set status = 'EXPIRED', updated_at = ?::timestamptz"
              + " where id = ? and status = 'SUCCEEDED'",
          now,
          id);
      expired++;
    }
    return expired;
  }

  /**
   * 把**卡住**的任务标成失败（执行中断）。
   *
   * <p>🔴 **不能自动重试**：重试要调用方的 bearer，而任务表里**不存凭据**
   * （存凭据 = 把 secret 落库）。⇒ 卡住的任务只能如实标「执行中断」，由人重新发起。
   *
   * @param stuckBefore 这个时刻之前创建、却还停在 PENDING / RUNNING 的任务算卡住
   * @return 被标失败的任务数
   */
  public int failStuck(OffsetDateTime stuckBefore) {
    var rows =
        sql.fetch(
            "select id from platform_io_task where status in ('PENDING','RUNNING')"
                + " and created_at <= ?::timestamptz",
            stuckBefore);
    var failed = 0;
    for (var r : rows) {
      sql.execute(
          "update platform_io_task set status = 'FAILED',"
              + " error = '执行中断（进程重启或执行器异常）。本通道不把会话凭据落库，"
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

  // ================================================================ 小工具

  private static String label(String status) {
    try {
      return TaskStatus.valueOf(status).label();
    } catch (IllegalArgumentException e) {
      return status;
    }
  }

  private static String str(Object value) {
    if (value == null) return null;
    var text = value.toString().trim();
    return text.isEmpty() ? null : text;
  }

  private static LocalDate date(Object value) {
    var text = str(value);
    if (text == null) return null;
    try {
      return LocalDate.parse(text);
    } catch (java.time.format.DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "日期格式必须是 yyyy-MM-dd：" + text);
    }
  }

  /** 拼时间窗（{@code from} / {@code to} 都是 yyyy-MM-dd，含当天）。 */
  private static void appendWindow(
      StringBuilder where, List<Object> params, String column, Object from, Object to) {
    var fromDate = date(from);
    var toDate = date(to);
    if (fromDate != null) {
      where.append(" and ").append(column).append(" >= ?::timestamptz");
      params.add(fromDate.atStartOfDay().atOffset(ZoneOffset.UTC));
    }
    if (toDate != null) {
      where.append(" and ").append(column).append(" < ?::timestamptz");
      params.add(toDate.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC));
    }
  }

  /** 人可读单号（租户短码 + 序列号）。 */
  private String nextTaskNo() {
    var seq = sql.fetchOne("select nextval('platform_io_task_no_seq')").get(0, Long.class);
    var tenantShort = currentTenant().toString().replace("-", "").substring(0, 8);
    return "IO-" + tenantShort + "-" + String.format("%08d", seq);
  }

  /** 安全的文件名（去掉路径分隔符与控制字符）。 */
  private static String safeFilename(String raw) {
    var name = raw == null ? "" : raw.trim();
    name = name.replace('\\', '_').replace('/', '_');
    name = name.replaceAll("[\\r\\n\\t]", "_");
    if (name.isEmpty()) name = "upload.csv";
    if (name.length() > 200) name = name.substring(name.length() - 200);
    return name;
  }

  /** 把参数数组拼成可变参数数组（jOOQ 的 {@code fetch(String, Object...)}）。 */
  private static Object[] concat(List<Object> params, Object... extra) {
    var out = new ArrayList<Object>(params);
    out.addAll(List.of(extra));
    return out.toArray();
  }

  /** 随机串（导入时不需要，但与订阅模块保持同一套取值习惯）。 */
  static String randomToken() {
    var buffer = new byte[16];
    RANDOM.nextBytes(buffer);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
  }

  /** 文本转字节（UTF-8）。 */
  static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
