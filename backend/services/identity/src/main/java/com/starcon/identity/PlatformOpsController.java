package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.7.2.2 平台运维视图。
 *
 * <p>蓝图对本模块点名的硬要求是那句「<b>跨租户视图只看技术指标不看业务数据</b>」。
 * 它在本模块里落成<b>三条独立成立的事实</b>，而不是一句「我们不查那些表」的自觉：
 *
 * <ol>
 *   <li><b>表结构层面</b>：指标表 {@code platform_ops_metric} 的每一个字符型列都被
 *       CHECK 钉死在技术枚举上（service / metric / unit / severity / source），
 *       量纲与指标码绑定，比率必须落在 0~1。库里**没有任何自由文本列**能装下
 *       客户名 / 订单号 / 金额。告警表同理：告警是「技术量与阈值的比较结果」，
 *       人话由接口层按枚举码拼出来，不入库。
 *   <li><b>端点层面</b>：跨租户的读出口只有 {@code platform_ops_*} 四个
 *       SECURITY DEFINER 函数，函数内部**自己再判一次平台运营三道闸门** ——
 *       应用层漏判不会让数据漏出去。租户级表本身是 {@code tenant_id NOT NULL} +
 *       RLS FORCE isolation，普通事务里根本看不见别人的行。
 *   <li><b>测试层面</b>：集成测试逐列断言「每个字符型列都挂着取值域 CHECK、且列名
 *       不是自由文本列名」，并断言本模块四张表**没有任何金额语义列**。
 * </ol>
 *
 * <p>🔴 <b>「成本」是数量不是金额</b>：成本估算 = Σ(最近一次采样值 × 口径权重)，
 * 单位是「估算单位数量」，来源是 {@code platform_ops_cost_model}（平台级、只读）。
 * 本模块不落发票 / 应收 / 付款 / 结算 —— 那是 12 财务域的事。
 *
 * <p>🔴 <b>平台运营域不对租户开放</b>：权限码 {@code identity.platform_ops.*}
 * 刻意**不授 OWNER**（与 17.7.1.1 同一先例，与 V53 相反），只能通过
 * {@code platform_ops_register_role()} 授给**被登记的平台运营角色**。
 * 判定走三道闸门：① 会话租户有 {@code saas_platform_role} 登记；② 登记的 roleId
 * 正是会话持有的角色且 {@code scope_mode='ALL'}；③ 该角色带有所需权限码。
 *
 * <p>指标接入（{@code POST /identity/platform-ops/ingest/metrics}）与平台运营角色
 * 登记（{@code POST /identity/platform-ops/roles}）都是**服务端到服务端**的路径，
 * 要内部密钥 {@code X-Internal-Key}，且**刻意不进浏览器的 api-proxy 白名单**。
 */
@RestController
@RequestMapping("/identity")
public class PlatformOpsController {
  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final String internalKey;

  private static final String OPS_READ = "identity.platform_ops.read";
  private static final String OPS_ALERT = "identity.platform_ops.alert";
  private static final String OPS_UPDATE = "identity.platform_ops.update";

  /** 指标码 → 单位。与迁移里的 CHECK 同口径（这里是为了给出可读的 400，不是第二份数据源）。 */
  private static final Map<String, String> METRIC_UNITS =
      Map.ofEntries(
          Map.entry("ONLINE_STATUS", "STATE"),
          Map.entry("ERROR_RATE", "RATIO"),
          Map.entry("ERROR_COUNT", "COUNT"),
          Map.entry("REQUEST_COUNT", "COUNT"),
          Map.entry("REQUEST_LATENCY_MS", "MILLISECOND"),
          Map.entry("AVAILABILITY", "RATIO"),
          Map.entry("TASK_BACKLOG", "COUNT"),
          Map.entry("QUEUE_DEPTH", "COUNT"),
          Map.entry("CPU_UTILIZATION", "RATIO"),
          Map.entry("MEMORY_UTILIZATION", "RATIO"),
          Map.entry("STORAGE_UTILIZATION", "RATIO"),
          Map.entry("STORAGE_USED_BYTES", "BYTE"),
          Map.entry("API_CALL_COUNT", "COUNT"),
          Map.entry("AI_CALL_COUNT", "COUNT"));

  /** 指标码 → 告警码。与迁移里告警表的 CHECK 同口径。 */
  private static final Map<String, String> METRIC_ALERT_CODES =
      Map.ofEntries(
          Map.entry("ERROR_RATE", "ERROR_RATE_HIGH"),
          Map.entry("TASK_BACKLOG", "TASK_BACKLOG_HIGH"),
          Map.entry("QUEUE_DEPTH", "QUEUE_DEPTH_HIGH"),
          Map.entry("CPU_UTILIZATION", "CPU_PRESSURE"),
          Map.entry("MEMORY_UTILIZATION", "MEMORY_PRESSURE"),
          Map.entry("STORAGE_UTILIZATION", "STORAGE_PRESSURE"),
          Map.entry("REQUEST_LATENCY_MS", "LATENCY_HIGH"),
          Map.entry("AVAILABILITY", "AVAILABILITY_LOW"),
          Map.entry("ONLINE_STATUS", "TENANT_OFFLINE"));

  private static final List<String> SERVICE_CODES =
      List.of("identity", "catalog", "channel", "trade", "warehouse", "finance", "web");

  public PlatformOpsController(
      DSLContext sql,
      IdentityController identity,
      PlatformTransactionManager manager,
      @org.springframework.beans.factory.annotation.Value("${INTERNAL_KEY}") String internalKey) {
    this.sql = sql;
    this.identity = identity;
    this.internalKey = internalKey;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // 视图
  // ------------------------------------------------------------------

  /** 一条技术指标采样。列全是技术量，没有任何业务字段。 */
  public record PlatformOpsMetricView(
      UUID id,
      UUID tenantId,
      String serviceCode,
      String metricCode,
      BigDecimal sampleValue,
      String unit,
      String severity,
      OffsetDateTime windowStart,
      OffsetDateTime windowEnd,
      String source,
      OffsetDateTime createdAt) {}

  /** 真分页的一页指标（total 是同条件下的真实总条数）。 */
  public record PlatformOpsMetricPage(
      List<PlatformOpsMetricView> items, long total, int limit, int offset) {}

  /** 一条告警。 */
  public record PlatformOpsAlertView(
      UUID id,
      UUID tenantId,
      String alertCode,
      String metricCode,
      String serviceCode,
      String severity,
      BigDecimal observedValue,
      BigDecimal thresholdValue,
      String status,
      OffsetDateTime raisedAt,
      @Schema(nullable = true) OffsetDateTime acknowledgedAt,
      @Schema(nullable = true) OffsetDateTime resolvedAt) {}

  /** 真分页的一页告警。 */
  public record PlatformOpsAlertPage(
      List<PlatformOpsAlertView> items, long total, int limit, int offset) {}

  /**
   * 每个租户一行的技术健康摘要。
   *
   * <p>{@code online} 是**可空**的：没有在线状态采样时是 {@code null}（= 不知道），
   * 不是 {@code false}（= 离线）。把「没数据」说成「离线」是把缺口说成了事实。
   */
  public record PlatformOpsTenantHealthView(
      UUID tenantId,
      String tenantCode,
      String tenantName,
      String tenantStatus,
      @Schema(nullable = true) Boolean online,
      @Schema(nullable = true) BigDecimal errorRate,
      @Schema(nullable = true) BigDecimal taskBacklog,
      @Schema(nullable = true) BigDecimal queueDepth,
      @Schema(nullable = true) BigDecimal storageUtilization,
      @Schema(nullable = true) BigDecimal availability,
      @Schema(nullable = true) BigDecimal latencyMs,
      @Schema(nullable = true) BigDecimal costEstimate,
      long openAlertCount,
      long criticalAlertCount,
      @Schema(nullable = true) OffsetDateTime lastSampleAt) {}

  /**
   * 平台健康看板汇总。
   *
   * <p>口径与逐租户行**同源**（汇总就是逐租户行算出来的），不存在两套口径漂移。
   * 比率类取「有值的租户」的平均，没有采样的租户不进分母 —— 补 0 会把「没数据」
   * 说成「指标是 0」。
   */
  public record PlatformOpsOverviewView(
      long tenantCount,
      long onlineTenantCount,
      long offlineTenantCount,
      long unknownTenantCount,
      @Schema(nullable = true) BigDecimal avgErrorRate,
      @Schema(nullable = true) BigDecimal maxErrorRate,
      @Schema(nullable = true) BigDecimal totalTaskBacklog,
      @Schema(nullable = true) BigDecimal totalQueueDepth,
      @Schema(nullable = true) BigDecimal avgAvailability,
      @Schema(nullable = true) BigDecimal avgLatencyMs,
      @Schema(nullable = true) BigDecimal avgStorageUtilization,
      @Schema(nullable = true) BigDecimal totalCostEstimate,
      long openAlertCount,
      long criticalAlertCount,
      long tenantWithSamples,
      OffsetDateTime generatedAt) {}

  /** 一条告警阈值。 */
  public record PlatformOpsThresholdView(
      String metricCode,
      BigDecimal warnThreshold,
      BigDecimal criticalThreshold,
      String comparison,
      boolean enabled,
      OffsetDateTime updatedAt) {}

  /** 能力声明的一维。 */
  public record PlatformOpsCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 能力声明。 */
  public record PlatformOpsCapabilities(List<PlatformOpsCapabilityDimension> dimensions) {}

  /** 指标接入输入（服务端到服务端）。 */
  public record PlatformOpsMetricInput(
      @NotNull UUID tenantId,
      @NotBlank @Size(max = 32) String serviceCode,
      @NotBlank @Size(max = 40) String metricCode,
      @NotNull @DecimalMin("0") BigDecimal sampleValue,
      @NotBlank @Size(max = 16) String unit,
      @NotNull OffsetDateTime windowStart,
      @NotNull OffsetDateTime windowEnd,
      @Schema(nullable = true) @Size(max = 16) String source) {}

  /** 指标接入结果。 */
  public record PlatformOpsIngestResult(
      UUID metricId,
      String severity,
      @Schema(nullable = true) UUID alertId,
      @Schema(nullable = true) String alertStatus,
      @Schema(nullable = true) String alertCode) {}

  /** 阈值写入输入。 */
  public record PlatformOpsThresholdInput(
      @NotNull @DecimalMin("0") BigDecimal warnThreshold,
      @NotNull @DecimalMin("0") BigDecimal criticalThreshold,
      @NotBlank @Pattern(regexp = "GT|LT") String comparison,
      @Schema(nullable = true) Boolean enabled) {}

  // ------------------------------------------------------------------
  // 闸门与工具
  // ------------------------------------------------------------------

  /**
   * 平台运营三道闸门（与 17.7.1.1 的 {@code SaasPlanController.requirePlatformOps} 同构）。
   *
   * <p>为什么不能只看权限码：{@code create_child} 会把 permission 表里的**全部**权限码
   * 授给子租户的 OWNER，只看权限码的话，任何租户的所有者都能看跨租户运维看板。
   */
  private void requirePlatformOps(IdentityController.Access a, String permission) {
    var registered =
        sql.fetch("select role_id from saas_platform_role where tenant_id=?", a.tenantId())
            .getValues("role_id", UUID.class);
    boolean allowed =
        a.grants()
            .stream()
            .anyMatch(
                g ->
                    g.scopeMode().equals("ALL")
                        && registered.contains(g.roleId())
                        && g.permissions().contains(permission));
    require(allowed, "平台运维视图是平台运营域，当前角色没有此操作权限");
  }

  /** 过闸门并返回会话。读取登记表的判定必须在**会话租户的上下文**里做（表受 RLS 保护）。 */
  private IdentityController.Access authorize(String auth, String permission) {
    var a = identity.resolve(auth);
    tenants.run(
        a.tenantId(),
        () -> {
          requirePlatformOps(a, permission);
          return null;
        });
    return a;
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  private static void reject(String message) {
    throw IdentityController.error(HttpStatus.CONFLICT, message);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  /** timestamptz 一律绑 {@link OffsetDateTime}；用 Timestamp.valueOf 会静默偏 −28800s。 */
  private static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  /** 内部密钥比对：常量时间比较，避免逐字节提前返回。 */
  private void requireInternalKey(String key) {
    if (!MessageDigest.isEqual(
        key.getBytes(StandardCharsets.UTF_8), internalKey.getBytes(StandardCharsets.UTF_8)))
      throw IdentityController.error(HttpStatus.FORBIDDEN, "拒绝内部调用");
  }

  // ------------------------------------------------------------------
  // 能力声明
  // ------------------------------------------------------------------

  /**
   * 能力声明：逐维说明哪些已交付、哪些还没有。
   *
   * <p>未交付的维度如实标 {@code available=false} 并写明归属模块与影响 ——
   * 界面据此隐藏入口，而不是让用户点进去撞 404。
   */
  @GetMapping("/platform-ops/capabilities")
  public PlatformOpsCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, OPS_READ);
    return new PlatformOpsCapabilities(
        List.of(
            new PlatformOpsCapabilityDimension(
                "CROSS_TENANT_METRICS",
                "跨租户技术指标读取（在线状态 / 错误率 / 任务积压 / 队列深度 / 容量占用 / 响应延迟 / 可用性）",
                true,
                "platform_ops_metric + platform_ops_metric_list()",
                "",
                "指标行只含技术量与租户 id，不含任何业务数据"),
            new PlatformOpsCapabilityDimension(
                "PLATFORM_HEALTH_DASHBOARD",
                "平台健康看板：逐租户技术健康摘要 + 全局汇总",
                true,
                "platform_ops_tenant_health()",
                "",
                "没有采样的指标返回空值而不是 0"),
            new PlatformOpsCapabilityDimension(
                "ALERTING",
                "告警：阈值比较产生告警，确认 / 闭环流转",
                true,
                "platform_ops_threshold + platform_ops_alert",
                "",
                "阈值是平台级配置，改阈值要平台运营权限"),
            new PlatformOpsCapabilityDimension(
                "COST_ESTIMATE",
                "容量 / 调用量口径的成本估算（数量）",
                true,
                "platform_ops_cost_model",
                "",
                "🔴 是估算单位数量，不是金额；不落发票 / 应收 / 付款 / 结算"),
            new PlatformOpsCapabilityDimension(
                "TENANT_SELF_METRICS",
                "本租户自检视角：只看得到自己租户的技术指标（RLS 保护）",
                true,
                "GET /identity/platform-ops/self/metrics",
                "",
                "只返回调用方自己租户的行；租户之间互不可见"),
            new PlatformOpsCapabilityDimension(
                "METRIC_INGEST",
                "指标接入面（服务端到服务端，内部密钥路径）",
                true,
                "POST /identity/platform-ops/ingest/metrics",
                "",
                "浏览器打不到这条路径"),
            new PlatformOpsCapabilityDimension(
                "UPSTREAM_COLLECTION",
                "各服务运维指标的**采集器**接入（自动上报）",
                false,
                "无（backend/libraries 下没有 observability / 事件信封库）",
                "平台可观测性采集（后续待办）",
                "本模块只提供接入面与消费面；采集器尚未接入，指标靠内部密钥路径推送"),
            new PlatformOpsCapabilityDimension(
                "BILLING_ALERTING",
                "用量 / 计费口径的告警（与 17.7.1.2 用量统计联动）",
                false,
                "无（17.7.1.2 用量统计与本模块同批并行车道）",
                "17.7.1.2",
                "本模块的告警基于技术阈值，不基于用量计费口径"),
            new PlatformOpsCapabilityDimension(
                "TENANT_ALERT_SUBSCRIPTION",
                "平台告警的下游分发（通知 / 值班排班 / 外部告警通道）",
                false,
                "无（仓内没有通知与告警通道服务）",
                "平台告警分发（后续待办）",
                "本模块只产出告警记录与看板，不负责把告警推到外部通道")));
  }

  // ------------------------------------------------------------------
  // 看板
  // ------------------------------------------------------------------

  /** 逐租户的技术健康摘要（看板主表）。 */
  @GetMapping("/platform-ops/tenants")
  public List<PlatformOpsTenantHealthView> tenants(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, OPS_READ);
    return sql.fetch("select * from platform_ops_tenant_health(?::text)", hash(auth)).stream()
        .map(PlatformOpsController::toHealth)
        .toList();
  }

  /**
   * 平台健康看板汇总。
   *
   * <p>口径与逐租户行**同源**：汇总就是把那批行算出来的，不另起一套 SQL，
   * 所以「总览说 3 个租户不在线」与「表里正好 3 行不在线」永远一致。
   */
  @GetMapping("/platform-ops/overview")
  public PlatformOpsOverviewView overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var rows = tenants(auth);
    long online = 0;
    long offline = 0;
    long unknown = 0;
    BigDecimal maxErrorRate = null;
    BigDecimal sumErrorRate = BigDecimal.ZERO;
    int errorRateCount = 0;
    BigDecimal sumBacklog = BigDecimal.ZERO;
    int backlogCount = 0;
    BigDecimal sumQueue = BigDecimal.ZERO;
    int queueCount = 0;
    BigDecimal sumAvailability = BigDecimal.ZERO;
    int availabilityCount = 0;
    BigDecimal sumLatency = BigDecimal.ZERO;
    int latencyCount = 0;
    BigDecimal sumStorage = BigDecimal.ZERO;
    int storageCount = 0;
    BigDecimal sumCost = BigDecimal.ZERO;
    int costCount = 0;
    long openAlerts = 0;
    long criticalAlerts = 0;
    long withSamples = 0;
    for (var row : rows) {
      if (row.online() == null) unknown++;
      else if (row.online()) online++;
      else offline++;
      if (row.lastSampleAt() != null) withSamples++;
      if (row.errorRate() != null) {
        sumErrorRate = sumErrorRate.add(row.errorRate());
        errorRateCount++;
        if (maxErrorRate == null || row.errorRate().compareTo(maxErrorRate) > 0)
          maxErrorRate = row.errorRate();
      }
      if (row.taskBacklog() != null) {
        sumBacklog = sumBacklog.add(row.taskBacklog());
        backlogCount++;
      }
      if (row.queueDepth() != null) {
        sumQueue = sumQueue.add(row.queueDepth());
        queueCount++;
      }
      if (row.availability() != null) {
        sumAvailability = sumAvailability.add(row.availability());
        availabilityCount++;
      }
      if (row.latencyMs() != null) {
        sumLatency = sumLatency.add(row.latencyMs());
        latencyCount++;
      }
      if (row.storageUtilization() != null) {
        sumStorage = sumStorage.add(row.storageUtilization());
        storageCount++;
      }
      if (row.costEstimate() != null) {
        sumCost = sumCost.add(row.costEstimate());
        costCount++;
      }
      openAlerts += row.openAlertCount();
      criticalAlerts += row.criticalAlertCount();
    }
    return new PlatformOpsOverviewView(
        rows.size(),
        online,
        offline,
        unknown,
        average(sumErrorRate, errorRateCount),
        maxErrorRate,
        backlogCount == 0 ? null : sumBacklog,
        queueCount == 0 ? null : sumQueue,
        average(sumAvailability, availabilityCount),
        average(sumLatency, latencyCount),
        average(sumStorage, storageCount),
        costCount == 0 ? null : sumCost,
        openAlerts,
        criticalAlerts,
        withSamples,
        utcNow());
  }

  /** 平均值。分母为 0 时返回 null —— 没有数据就说没有数据，不补 0。 */
  private static BigDecimal average(BigDecimal sum, int count) {
    if (count == 0) return null;
    return sum.divide(BigDecimal.valueOf(count), 6, RoundingMode.HALF_UP);
  }

  // ------------------------------------------------------------------
  // 指标与告警
  // ------------------------------------------------------------------

  /** 跨租户技术指标列表（真分页：limit / offset / total 都是真的）。 */
  @GetMapping("/platform-ops/metrics")
  public PlatformOpsMetricPage metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) String serviceCode,
      @RequestParam(required = false) String metricCode,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    authorize(auth, OPS_READ);
    int safeLimit = Math.min(Math.max(limit, 1), 200);
    int safeOffset = Math.max(offset, 0);
    // 🔴 每个占位符都带显式 cast：jOOQ 把 null 绑成 unknown、把 OffsetDateTime 绑成
    //   character varying，PG 这样就**解析不出这个函数**（报 "function ... does not exist"）。
    var items =
        sql.fetch(
                "select * from platform_ops_metric_list(?::text,?::uuid,?::text,?::text,?::int,?::int)",
                hash(auth),
                tenantId,
                blankToNull(serviceCode),
                blankToNull(metricCode),
                safeLimit,
                safeOffset)
            .stream()
            .map(PlatformOpsController::toMetric)
            .toList();
    long total =
        sql.fetchOne(
                "select platform_ops_metric_total(?::text,?::uuid,?::text,?::text)",
                hash(auth),
                tenantId,
                blankToNull(serviceCode),
                blankToNull(metricCode))
            .get(0, Long.class);
    return new PlatformOpsMetricPage(items, total, safeLimit, safeOffset);
  }

  /** 跨租户告警列表（真分页）。 */
  @GetMapping("/platform-ops/alerts")
  public PlatformOpsAlertPage alerts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(defaultValue = "0") int offset) {
    authorize(auth, OPS_READ);
    int safeLimit = Math.min(Math.max(limit, 1), 200);
    int safeOffset = Math.max(offset, 0);
    var items =
        sql.fetch(
                "select * from platform_ops_alert_list(?::text,?::text,?::text,?::uuid,?::int,?::int)",
                hash(auth),
                blankToNull(status),
                blankToNull(severity),
                tenantId,
                safeLimit,
                safeOffset)
            .stream()
            .map(PlatformOpsController::toAlert)
            .toList();
    long total =
        sql.fetchOne(
                "select platform_ops_alert_total(?::text,?::text,?::text,?::uuid)",
                hash(auth),
                blankToNull(status),
                blankToNull(severity),
                tenantId)
            .get(0, Long.class);
    return new PlatformOpsAlertPage(items, total, safeLimit, safeOffset);
  }

  /** 确认告警：已确认的告警保留最初的确认时刻。 */
  @PostMapping("/platform-ops/alerts/{alertId}/acknowledge")
  public PlatformOpsAlertView acknowledge(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId) {
    return transition(auth, alertId, "ACKNOWLEDGED");
  }

  /** 闭环告警：已闭环的告警保留最初的闭环时刻。 */
  @PostMapping("/platform-ops/alerts/{alertId}/resolve")
  public PlatformOpsAlertView resolveAlert(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID alertId) {
    return transition(auth, alertId, "RESOLVED");
  }

  /**
   * 告警状态流转。
   *
   * <p>跨租户写必须先确定「本次事务的目标租户」（ADR-0005），所以这里是两个动作：
   * 先用授权函数问出告警属于哪个租户，再切到那个租户的上下文里改 ——
   * RLS 保证这一步**只能**改到那一个租户的行。
   */
  private PlatformOpsAlertView transition(String auth, UUID alertId, String target) {
    authorize(auth, OPS_ALERT);
    UUID owner =
        sql.fetchOne(
                "select platform_ops_alert_tenant(?::text,?::uuid,?::text)", hash(auth), alertId, OPS_ALERT)
            .get(0, UUID.class);
    if (owner == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "告警不存在");
    return tenants.run(
        owner,
        () -> {
          var row = sql.fetchOne("select * from platform_ops_alert where id=?", alertId);
          if (row == null) throw IdentityController.error(HttpStatus.NOT_FOUND, "告警不存在");
          String current = row.get("status", String.class);
          if ("RESOLVED".equals(current))
            reject("告警已闭环，不能再变更状态");
          if ("ACKNOWLEDGED".equals(target) && "ACKNOWLEDGED".equals(current))
            return toAlert(row);
          if ("RESOLVED".equals(target)) {
            sql.execute(
                "update platform_ops_alert set status='RESOLVED',"
                    + " resolved_at=coalesce(resolved_at,?::timestamptz) where id=?",
                utcNow(),
                alertId);
          } else {
            sql.execute(
                "update platform_ops_alert set status='ACKNOWLEDGED',"
                    + " acknowledged_at=coalesce(acknowledged_at,?::timestamptz) where id=?",
                utcNow(),
                alertId);
          }
          return toAlert(sql.fetchOne("select * from platform_ops_alert where id=?", alertId));
        });
  }

  // ------------------------------------------------------------------
  // 阈值
  // ------------------------------------------------------------------

  /** 告警阈值（平台级，全局一份）。 */
  @GetMapping("/platform-ops/thresholds")
  public List<PlatformOpsThresholdView> thresholds(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, OPS_READ);
    return sql.fetch(
            "select metric_code,warn_threshold,critical_threshold,comparison,enabled,updated_at"
                + " from platform_ops_threshold order by metric_code")
        .stream()
        .map(PlatformOpsController::toThreshold)
        .toList();
  }

  /** 改一个指标的告警阈值。平台级配置，写入走 SECURITY DEFINER 函数。 */
  @PutMapping("/platform-ops/thresholds/{metricCode}")
  public PlatformOpsThresholdView updateThreshold(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String metricCode,
      @Valid @RequestBody PlatformOpsThresholdInput input) {
    authorize(auth, OPS_UPDATE);
    String code = metricCode.trim();
    check(METRIC_ALERT_CODES.containsKey(code) || METRIC_UNITS.containsKey(code), "未知的技术指标码");
    // 两级阈值必须与比较方向自洽：GT 时 warning < critical；LT 时 critical < warning。
    // 这里先判一次（给人看的是 400），数据库函数里还有同一道校验（那是兜底）。
    if ("GT".equals(input.comparison()))
      check(
          input.warnThreshold().compareTo(input.criticalThreshold()) < 0,
          "GT 方向下告警阈值必须小于严重阈值");
    else
      check(
          input.criticalThreshold().compareTo(input.warnThreshold()) < 0,
          "LT 方向下严重阈值必须小于告警阈值");
    sql.fetch(
        "select platform_ops_apply_threshold(?::text,?::text,?::numeric,?::numeric,?::text,?::boolean)",
        hash(auth),
        code,
        input.warnThreshold(),
        input.criticalThreshold(),
        input.comparison(),
        input.enabled() == null || input.enabled());
    return thresholds(auth).stream()
        .filter(row -> row.metricCode().equals(code))
        .findFirst()
        .orElseThrow(
            () -> IdentityController.error(HttpStatus.NOT_FOUND, "指标 " + code + " 没有阈值配置"));
  }

  // ------------------------------------------------------------------
  // 本租户自检（RLS 保护的读路径）
  // ------------------------------------------------------------------

  /**
   * 本租户的技术指标（自检视角）。
   *
   * <p>这条路径是<b>租户自己的</b>，不是平台运营域：它在**调用方租户的上下文**里
   * 读，RLS FORCE isolation 保证只能看见自己租户的行。它存在的意义是让多租户
   * 三断言有真实的 HTTP 落点（两租户互不可见 / 伪造租户头无效 / 连接池归还后不串租户）。
   *
   * <p>租户**看不到**别人的在线状态 / 错误率 / 成本：那些只在平台运营域的
   * {@code /platform-ops/tenants} 里，而那条路径要过三道闸门。
   */
  @GetMapping("/platform-ops/self/metrics")
  public List<PlatformOpsMetricView> selfMetrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    var a = identity.resolve(auth);
    return tenants.run(
        a.tenantId(),
        () ->
            sql.fetch(
                    "select id,tenant_id,service_code,metric_code,sample_value,unit,severity,"
                        + " window_start,window_end,source,created_at from platform_ops_metric"
                        + " where tenant_id=? order by window_end desc, id desc limit 200",
                    a.tenantId())
                .stream()
                .map(PlatformOpsController::toMetric)
                .toList());
  }

  // ------------------------------------------------------------------
  // 接入面（服务端到服务端）
  // ------------------------------------------------------------------

  /**
   * 登记平台运营角色（平台运营侧专用路径，浏览器打不到）。
   *
   * <p>🔴 刻意**不放进浏览器的 api-proxy 白名单**：它是平台运营侧的专用路径，
   * 浏览器打不到它；它还要求内部密钥 {@code X-Internal-Key}。
   * 登记的是「本会话所在租户」—— 不接收任何租户 id 参数，租户一律来自已认证成员关系。
   */
  @PostMapping("/platform-ops/roles")
  public Map<String, Object> registerPlatformRole(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestHeader(value = "X-Internal-Key", defaultValue = "") String key,
      @RequestParam(required = false) String roleCode) {
    requireInternalKey(key);
    var a = identity.resolve(auth);
    String code = roleCode == null || roleCode.isBlank() ? "PLATFORM_OPS" : roleCode.trim();
    sql.fetch("select platform_ops_register_role(?::text,?::text)", hash(auth), code);
    return Map.of(
        "tenantId", a.tenantId().toString(),
        "roleCode", code,
        "registered", true);
  }

  /**
   * 指标接入（服务端到服务端）。
   *
   * <p>🔴 同样**刻意不进浏览器的 api-proxy 白名单**：这条路径要内部密钥。
   * 采集方一次可以推**一批**采样。
   *
   * <p>写入在**目标租户**的上下文里做：目标租户由调用方显式给出（这是运维事件的
   * 归属对象，不是「浏览器的租户上下文」），并先确认它真实存在。租户来自调用方
   * 显式指定 + 存在性校验，**不信任任何 {@code X-Tenant-Id} 头**。
   */
  @PostMapping("/platform-ops/ingest/metrics")
  public List<PlatformOpsIngestResult> ingest(
      @RequestHeader(value = "X-Internal-Key", defaultValue = "") String key,
      @Valid @RequestBody List<@Valid PlatformOpsMetricInput> batch) {
    requireInternalKey(key);
    check(batch != null && !batch.isEmpty(), "至少要有一条指标采样");
    check(batch.size() <= 500, "一次最多上报 500 条指标采样");
    var results = new ArrayList<PlatformOpsIngestResult>();
    for (var input : batch) results.add(ingestOne(input));
    return results;
  }

  /** 单条采样：校验 → 落库 → 阈值比较 → 告警。 */
  private PlatformOpsIngestResult ingestOne(PlatformOpsMetricInput input) {
    String service = input.serviceCode().trim();
    String metric = input.metricCode().trim();
    String unit = input.unit().trim();
    String source = input.source() == null || input.source().isBlank() ? "PUSH" : input.source().trim();
    check(SERVICE_CODES.contains(service), "未知的服务码 " + service);
    check(METRIC_UNITS.containsKey(metric), "未知的技术指标码 " + metric);
    check(METRIC_UNITS.get(metric).equals(unit), "指标 " + metric + " 的单位必须是 " + METRIC_UNITS.get(metric));
    check("PUSH".equals(source) || "SCHEDULER".equals(source), "采集来源只能是 PUSH 或 SCHEDULER");
    check(input.sampleValue().signum() >= 0, "采样值不能是负数");
    check(input.sampleValue().scale() <= 6, "采样值最多 6 位小数");
    check(
        input.windowEnd().isAfter(input.windowStart()),
        "采集窗口的结束时刻必须晚于开始时刻");
    check(
        !"RATIO".equals(unit) || input.sampleValue().compareTo(BigDecimal.ONE) <= 0,
        "比率类指标的取值必须落在 0 ~ 1");
    check(
        !"ONLINE_STATUS".equals(metric)
            || input.sampleValue().compareTo(BigDecimal.ZERO) == 0
            || input.sampleValue().compareTo(BigDecimal.ONE) == 0,
        "在线状态只能取 0 或 1");
    UUID tenant = input.tenantId();
    Boolean exists =
        sql.fetchOne("select platform_ops_tenant_exists(?::uuid) as exists_flag", tenant)
            .get("exists_flag", Boolean.class);
    check(Boolean.TRUE.equals(exists), "租户不存在");

    return tenants.run(
        tenant,
        () -> {
          var threshold =
              sql.fetchOne(
                  "select warn_threshold,critical_threshold,comparison from platform_ops_threshold"
                      + " where metric_code=? and enabled",
                  metric);
          String severity = "OK";
          boolean breached = false;
          boolean critical = false;
          BigDecimal warn = null;
          BigDecimal criticalThreshold = null;
          if (threshold != null) {
            warn = threshold.get("warn_threshold", BigDecimal.class);
            criticalThreshold = threshold.get("critical_threshold", BigDecimal.class);
            String comparison = threshold.get("comparison", String.class);
            boolean gt = "GT".equals(comparison);
            breached = gt
                ? input.sampleValue().compareTo(warn) >= 0
                : input.sampleValue().compareTo(warn) <= 0;
            critical =
                gt
                    ? input.sampleValue().compareTo(criticalThreshold) >= 0
                    : input.sampleValue().compareTo(criticalThreshold) <= 0;
            severity = breached ? (critical ? "CRITICAL" : "WARNING") : "OK";
          }
          UUID metricId = Ids.next();
          sql.execute(
              "insert into platform_ops_metric"
                  + " (id,tenant_id,service_code,metric_code,sample_value,unit,severity,"
                  + " window_start,window_end,source)"
                  + " values (?,?,?,?,?,?,?,?::timestamptz,?::timestamptz,?)"
                  + " on conflict (tenant_id,service_code,metric_code,window_start,window_end)"
                  + " do update set sample_value=excluded.sample_value, unit=excluded.unit,"
                  + " severity=excluded.severity, source=excluded.source, created_at=now()",
              metricId,
              tenant,
              service,
              metric,
              input.sampleValue(),
              unit,
              severity,
              input.windowStart(),
              input.windowEnd(),
              source);
          if (!breached) {
            // 回到阈值以内 ⇒ 未闭环的告警自动闭环（保留历史，不物理删除）。
            sql.execute(
                "update platform_ops_alert set status='RESOLVED',"
                    + " resolved_at=coalesce(resolved_at,?::timestamptz)"
                    + " where tenant_id=? and service_code=? and metric_code=? and status<>'RESOLVED'",
                utcNow(),
                tenant,
                service,
                metric);
            return new PlatformOpsIngestResult(metricId, severity, null, null, null);
          }
          String alertCode = METRIC_ALERT_CODES.get(metric);
          UUID alertId = Ids.next();
          sql.execute(
              "insert into platform_ops_alert"
                  + " (id,tenant_id,alert_code,metric_code,service_code,severity,"
                  + " observed_value,threshold_value,status,raised_at)"
                  + " values (?,?,?,?,?,?,?,?,'OPEN',?::timestamptz)"
                  + " on conflict (tenant_id,service_code,metric_code) where status <> 'RESOLVED'"
                  + " do update set observed_value=excluded.observed_value,"
                  + " threshold_value=excluded.threshold_value, severity=excluded.severity",
              alertId,
              tenant,
              alertCode,
              metric,
              service,
              severity,
              input.sampleValue(),
              warn,
              utcNow());
          var open =
              sql.fetchOne(
                  "select id,status from platform_ops_alert where tenant_id=? and service_code=?"
                      + " and metric_code=? and status<>'RESOLVED'",
                  tenant,
                  service,
                  metric);
          UUID actualId = open == null ? alertId : open.get("id", UUID.class);
          String actualStatus = open == null ? "OPEN" : open.get("status", String.class);
          return new PlatformOpsIngestResult(
              metricId, severity, actualId, actualStatus, alertCode);
        });
  }

  // ------------------------------------------------------------------
  // 映射
  // ------------------------------------------------------------------

  private static PlatformOpsMetricView toMetric(Record row) {
    return new PlatformOpsMetricView(
        row.get("id", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("service_code", String.class),
        row.get("metric_code", String.class),
        row.get("sample_value", BigDecimal.class),
        row.get("unit", String.class),
        row.get("severity", String.class),
        row.get("window_start", OffsetDateTime.class),
        row.get("window_end", OffsetDateTime.class),
        row.get("source", String.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private static PlatformOpsAlertView toAlert(Record row) {
    return new PlatformOpsAlertView(
        row.get("id", UUID.class),
        row.get("tenant_id", UUID.class),
        row.get("alert_code", String.class),
        row.get("metric_code", String.class),
        row.get("service_code", String.class),
        row.get("severity", String.class),
        row.get("observed_value", BigDecimal.class),
        row.get("threshold_value", BigDecimal.class),
        row.get("status", String.class),
        row.get("raised_at", OffsetDateTime.class),
        row.get("acknowledged_at", OffsetDateTime.class),
        row.get("resolved_at", OffsetDateTime.class));
  }

  private static PlatformOpsTenantHealthView toHealth(Record row) {
    return new PlatformOpsTenantHealthView(
        row.get("tenant_id", UUID.class),
        row.get("tenant_code", String.class),
        row.get("tenant_name", String.class),
        row.get("tenant_status", String.class),
        row.get("online", Boolean.class),
        row.get("error_rate", BigDecimal.class),
        row.get("task_backlog", BigDecimal.class),
        row.get("queue_depth", BigDecimal.class),
        row.get("storage_utilization", BigDecimal.class),
        row.get("availability", BigDecimal.class),
        row.get("latency_ms", BigDecimal.class),
        row.get("cost_estimate", BigDecimal.class),
        row.get("open_alert_count", Long.class),
        row.get("critical_alert_count", Long.class),
        row.get("last_sample_at", OffsetDateTime.class));
  }

  private static PlatformOpsThresholdView toThreshold(Record row) {
    return new PlatformOpsThresholdView(
        row.get("metric_code", String.class),
        row.get("warn_threshold", BigDecimal.class),
        row.get("critical_threshold", BigDecimal.class),
        row.get("comparison", String.class),
        Boolean.TRUE.equals(row.get("enabled", Boolean.class)),
        row.get("updated_at", OffsetDateTime.class));
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
