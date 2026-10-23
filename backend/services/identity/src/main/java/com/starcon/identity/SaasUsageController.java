package com.starcon.identity;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.impl.DSL;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 17.7.1.2 用量统计。
 *
 * <p>蓝图对本模块的职责原文：
 *
 * <blockquote>
 *   统计各租户资源用量（店铺、SKU、月订单、存储、API、AI）用于计费与容量规划，
 *   口径须与套餐定义严格一致。输入：各系统用量埋点；统计周期。输出：用量报表 + 趋势 +
 *   计费依据。关系：上游 = 全部系统埋点；下游 = 17.7.1.1 计费。
 * </blockquote>
 *
 * <p><b>本模块是 17.7.1.1 之上的统计层，不重造上游已有的东西</b>：
 *
 * <ol>
 *   <li>17.7.1.1 已经给了 {@code POST /identity/saas-plans/usage}（登记用量）与
 *       {@code GET /identity/saas-plans/entitlement-check}（超限判定），本类<b>没有</b>
 *       同义端点。
 *   <li>{@code saas_entitlement_usage} 只存「当前用量」（每个维度一行 upsert），
 *       <b>没有历史</b> —— 所以它撑不起趋势，也撑不起「这个周期用了多少」这种
 *       按周期切分的报表。本类补的就是这一层：<b>归集</b>把当前用量物化成
 *       <b>按周期分桶的统计事实</b>（{@code saas_usage_statistic}），
 *       报表 / 趋势 / 计费依据 / 容量规划全部只读这一份事实。
 *   <li>{@code saas_entitlement_usage} 的写入所有者仍然是 17.7.1.1，本类<b>只读</b>它
 *       （每张事实表只有一个写入所有者）。
 * </ol>
 *
 * <p><b>口径与套餐定义严格一致</b>，具体是三条：
 *
 * <ol>
 *   <li>六个维度码与四个周期码只能用 17.7.1.1 已定义的那一套，本类的 CHECK 约束与
 *       词表都写死同一份，不另起名字。
 *   <li><b>流量口径</b>维度（套餐定义 period ∈ DAY/MONTH/YEAR）的桶粒度<b>固定</b>为
 *       套餐定义的周期：来源系统只提供该粒度的值，把月值切进日桶是计费争议的起点。
 *       <b>存量口径</b>维度（period = TOTAL）的值是采集时刻的快照，桶粒度跟随
 *       <b>统计周期</b>（归集请求里的 periodType），于是存量维度也有趋势。
 *   <li><b>上限一律实时读套餐定义</b>，统计事实表里不存上限快照——上限改了，
 *       历史报表必须按新上限重算。
 * </ol>
 *
 * <p><b>没接通的不假装接通</b>：来源系统没登记的维度，统计事实表里<b>根本没有这一行</b>，
 * 计费依据里它以 {@code UNDETERMINED} 出现（判定不了），而不是「用量 0」。
 * 「量到了正好是 0」与「没量到」在报表上是两件完全不同的事。
 *
 * <p><b>不落任何金额</b>：本类没有单价、没有金额、没有发票 / 应收 / 付款 / 结算 /
 * 收入确认。落的是数量（{@code NUMERIC(19,6)}）。
 *
 * <p><b>平台运营边界</b>：与 17.7.1.1 一致，「仅平台管理员可见，租户不可访问」。
 * 放行条件是三道同时成立（{@link #requirePlatformOps}）：会话租户里存在平台运营角色
 * 登记、被登记的 roleId 正好是当前会话持有的角色、该角色分配带有所需权限码。
 * 跨租户读一律走 SECURITY DEFINER 函数（函数内部自己再判一次闸门），
 * 不用「不带租户上下文也能查」的裸查询。
 *
 * <p><b>时间字段</b>：{@code timestamptz} 一律绑 {@link OffsetDateTime} 并在 values 位置写
 * {@code ?::timestamptz}；周期边界按 <b>UTC</b> 切分（{@code asOf} 先折算到 UTC 再截断）。
 */
@RestController
@RequestMapping("/identity")
public class SaasUsageController {
  /** 沿用 17.7.1.1 的权限码：页面资源「订阅与权益用量」本来就覆盖权益用量。 */
  private static final String USAGE_READ = "identity.subscription.read";

  private static final String USAGE_WRITE = "identity.subscription.update";

  /** 六个维度码：只能用 17.7.1.1 已定义的那一套。 */
  private static final List<String> KINDS =
      List.of("SHOP_COUNT", "SKU_COUNT", "ORDER_VOLUME", "USER_COUNT", "API_CALL", "AI_USAGE");

  /** 四个周期码。 */
  private static final List<String> PERIODS = List.of("DAY", "MONTH", "YEAR", "TOTAL");

  /** 覆盖状态。 */
  private static final List<String> COVERAGE_STATES =
      List.of("WIRED", "PARTIAL", "NOT_WIRED", "NO_SUBSCRIPTION");

  /** 计费依据的状态。 */
  private static final List<String> BILLING_STATES =
      List.of("WITHIN_LIMIT", "OVER_LIMIT", "UNLIMITED", "UNDETERMINED");

  private final DSLContext sql;
  private final IdentityController identity;
  private final TenantTransactions tenants;
  private final AuditLog audit;

  public SaasUsageController(
      DSLContext sql,
      IdentityController identity,
      PlatformTransactionManager manager) {
    this.sql = sql;
    this.identity = identity;
    this.audit = new AuditLog(sql, identity);
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
  }

  // ------------------------------------------------------------------
  // DTO
  // ------------------------------------------------------------------

  /** 一个能力维度。{@code available=false} 时必须同时给出 ownerModule 与 impact。 */
  public record UsageCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /** 本模块的能力声明。 */
  public record UsageCapabilitiesView(
      List<UsageCapabilityDimension> dimensions,
      List<String> entitlementKinds,
      List<String> periods,
      List<String> coverageStates,
      List<String> billingStates,
      String boundaryNote,
      String caliberNote,
      String moneyNote) {}

  /** 词表：前端不硬编码维度码与周期码。 */
  public record UsageOptionsView(
      List<String> entitlementKinds,
      List<String> periods,
      List<String> coverageStates,
      List<String> billingStates) {}

  /** 用量报表一行：租户 × 权益维度 × 统计周期。 */
  public record UsageReportRow(
      UUID tenantId,
      String tenantCode,
      String tenantName,
      UUID subscriptionId,
      String planCode,
      String planName,
      String subscriptionStatus,
      String entitlementCode,
      String entitlementKind,
      @Schema(nullable = true) BigDecimal entitlementLimit,
      boolean entitlementUnlimited,
      String overLimitAction,
      String entitlementUnit,
      String entitlementPeriod,
      String periodType,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      BigDecimal quantity,
      String sourceSystem,
      @Schema(nullable = true) OffsetDateTime measuredAt,
      OffsetDateTime collectedAt,
      @Schema(nullable = true) BigDecimal utilization,
      boolean overLimit) {}

  /** 趋势上的一个点：同一租户、同一维度、同一周期。 */
  public record UsageTrendPoint(
      UUID tenantId,
      String tenantCode,
      String tenantName,
      String entitlementKind,
      String periodType,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      BigDecimal quantity,
      String sourceSystems,
      OffsetDateTime collectedAt) {}

  /** 计费依据一行：数量 + 对应权益上限 + 结论（没有金额）。 */
  public record UsageBillingRow(
      UUID tenantId,
      String tenantCode,
      String tenantName,
      String planCode,
      String planName,
      String subscriptionStatus,
      String entitlementCode,
      String entitlementKind,
      BigDecimal entitlementLimit,
      boolean entitlementUnlimited,
      String overLimitAction,
      String entitlementUnit,
      String entitlementPeriod,
      @Schema(nullable = true) String periodType,
      @Schema(nullable = true) OffsetDateTime periodStart,
      @Schema(nullable = true) OffsetDateTime periodEnd,
      @Schema(nullable = true) BigDecimal quantity,
      String sourceSystem,
      @Schema(nullable = true) OffsetDateTime measuredAt,
      @Schema(nullable = true) OffsetDateTime collectedAt,
      @Schema(nullable = true) BigDecimal utilization,
      boolean overLimit,
      String state,
      String stateNote) {}

  /** 容量规划一行：每个维度一行。 */
  public record UsageCapacityRow(
      String entitlementKind,
      long subscribedTenantCount,
      long measuredTenantCount,
      long unmeasuredTenantCount,
      long unlimitedTenantCount,
      BigDecimal usedTotal,
      BigDecimal licensedTotal,
      BigDecimal headroom,
      @Schema(nullable = true) OffsetDateTime latestPeriodStart) {}

  /** 维度覆盖一行：谁真的接通了。 */
  public record UsageCoverageRow(
      String entitlementKind,
      long subscribedTenantCount,
      long registeredTenantCount,
      long measuredTenantCount,
      String wiredSourceSystems,
      String state,
      String stateNote) {}

  /** 归集批次一行。 */
  public record UsageCollectionRow(
      UUID collectionId,
      UUID tenantId,
      String tenantCode,
      String tenantName,
      UUID subscriptionId,
      String periodType,
      OffsetDateTime periodStart,
      OffsetDateTime periodEnd,
      int dimensionCount,
      int measuredCount,
      int unmeasuredCount,
      String status,
      String sourceSystems,
      OffsetDateTime startedAt,
      OffsetDateTime finishedAt,
      String note) {}

  /** 归集请求。{@code tenantId} 为空 = 全部有当前订阅的租户。 */
  public record UsageCollectionRequest(
      @Schema(nullable = true) UUID tenantId,
      @NotBlank String periodType,
      @Schema(nullable = true) OffsetDateTime asOf) {}

  /** 归集结果：一个租户一次归集的结果。 */
  public record UsageCollectionTenantResult(
      UUID tenantId,
      UUID subscriptionId,
      String planCode,
      UUID collectionId,
      int dimensionCount,
      int measuredCount,
      int unmeasuredCount,
      String status,
      List<String> measuredKinds,
      List<String> unmeasuredKinds,
      List<String> sourceSystems) {}

  /** 归集结果：整次归集。 */
  public record UsageCollectionRun(
      String periodType,
      OffsetDateTime asOf,
      int tenantCount,
      int measuredDimensionCount,
      int unmeasuredDimensionCount,
      List<UsageCollectionTenantResult> tenants) {}

  // ------------------------------------------------------------------
  // 平台运营闸门
  // ------------------------------------------------------------------

  /**
   * 三道条件同时成立才放行（与 17.7.1.1 同一套）：
   *
   * <ol>
   *   <li>会话租户里存在一条 {@code saas_platform_role} 登记；
   *   <li>被登记的 {@code roleId} 正好是当前会话持有的角色，且其 {@code scope_mode='ALL'}；
   *   <li>该角色分配带有所需权限码。
   * </ol>
   *
   * <p>为什么不能只看权限码：任何租户的所有者都能自建角色并把任意已登记的权限码授给自己。
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
    require(allowed, "用量统计是平台运营域，当前角色没有此操作权限");
  }

  /** 过闸门（读与写的权限码不同）。 */
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

  /** 目标租户必须真实存在；不存在直接 400（403 是「你不许看」，404/400 是「问错了」）。 */
  private void requireTenant(String auth, UUID tenantId) {
    // ⚠️ 必须写成 `select * from func(...)`：jOOQ 解析裸函数调用 `select func(...)` 时
    // 不知道这个函数的返回列，于是把行类型当成「只有一个叫函数名的字段」。
    var row =
        sql.fetchOne(
            "select * from saas_platform_tenant_info(?,?,?)", hash(auth), tenantId, USAGE_READ);
    check(row != null, "目标租户不存在");
  }

  private static void require(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.FORBIDDEN, message);
  }

  private static void check(boolean value, String message) {
    if (!value) throw IdentityController.error(HttpStatus.BAD_REQUEST, message);
  }

  /** timestamptz 一律绑 {@link OffsetDateTime}。 */
  private static OffsetDateTime utcNow() {
    return OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
  }

  private String hash(String auth) {
    return IdentityController.hash(identity.token(auth));
  }

  private static String note(String value) {
    return value == null ? "" : value.trim();
  }

  /** 归集目标：一个租户的一条当前订阅。 */
  private record Target(UUID tenantId, UUID subscriptionId, String planCode) {}

  /** 一个统计桶：粒度 + 起止。 */
  private record Bucket(String type, OffsetDateTime start, OffsetDateTime end) {}

  // ------------------------------------------------------------------
  // 读出口
  // ------------------------------------------------------------------

  /**
   * 能力声明：把「实做了什么」与「<b>没做</b>什么」都摆在明面上。
   *
   * <p>三条 {@code available=false} 各自说清缺口归属与影响面——空列表与 0 冒充「没数据」
   * 是把缺口说成了事实。
   */
  @GetMapping("/saas-usage/capabilities")
  public UsageCapabilitiesView capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, USAGE_READ);
    var dimensions = new ArrayList<UsageCapabilityDimension>();
    dimensions.add(
        new UsageCapabilityDimension(
            "周期归集与统计事实",
            "把来源系统登记的「当前用量」物化成按周期分桶的统计事实，于是有了历史与趋势",
            true,
            "saas_usage_statistic / saas_usage_collection",
            "",
            ""));
    dimensions.add(
        new UsageCapabilityDimension(
            "用量报表与趋势",
            "跨租户用量报表（租户 × 维度 × 周期）与按维度聚合的趋势序列",
            true,
            "GET /identity/saas-usage/report、GET /identity/saas-usage/trend",
            "",
            ""));
    dimensions.add(
        new UsageCapabilityDimension(
            "计费依据（数量口径）",
            "每个租户的每个维度：周期用量 + 对应权益上限 + 超限结论，作为 17.7.1.1 计费的依据",
            true,
            "GET /identity/saas-usage/billing-basis",
            "",
            "本模块只落数量：没有单价、没有金额、没有发票。量不到的维度结论是 UNDETERMINED，不是「没超限」"));
    dimensions.add(
        new UsageCapabilityDimension(
            "容量规划",
            "每个维度的已授权容量、实际用量与余量，以及不设上限的租户数",
            true,
            "GET /identity/saas-usage/capacity",
            "",
            ""));
    dimensions.add(
        new UsageCapabilityDimension(
            "来源系统埋点接入",
            "店铺数 / SKU 数 / 订单量 / API 调用量 / AI 用量分散在 catalog / channel / trade 等服务，"
                + "需要各来源系统经 POST /identity/saas-plans/usage 登记，本模块才量得到",
            false,
            "各来源系统经 POST /identity/saas-plans/usage 登记",
            "catalog / channel / trade 等来源服务",
            "未登记的维度在统计事实里没有这一行，计费依据里结论是 UNDETERMINED（判定不了）；"
                + "本模块不用 0 冒充「量过了正好是 0」"));
    dimensions.add(
        new UsageCapabilityDimension(
            "存储维度计量",
            "蓝图 17.7.1.2 的职责原文列了「存储」，而 17.7.1.1 的套餐定义给的是用户数——"
                + "两份清单对不上，套餐里没有存储维度",
            false,
            "saas_plan_entitlement（需补一个存储维度）",
            "17.7.1.1",
            "存储用量没有可比对的权益上限，本模块给不出存储维度的用量报表与超限结论；"
                + "本模块不另起 STORAGE 维度码——另起会让统计口径与套餐定义分叉"));
    dimensions.add(
        new UsageCapabilityDimension(
            "计费与收入确认",
            "发票、应收、付款、结算、收入确认一律不实现",
            false,
            "实体目录 §8：SaaS 商业化独立于 ERP 客户的销售收入",
            "实体目录 §8",
            "本模块不落任何金额列；统计与计费依据都是数量"));
    return new UsageCapabilitiesView(
        dimensions,
        KINDS,
        PERIODS,
        COVERAGE_STATES,
        BILLING_STATES,
        "用量统计与套餐、权益、订阅同属 starcon 自身的 SaaS 商业化域，仅平台管理员可见，租户不可访问。",
        "口径与套餐定义严格一致：六个维度码与四个周期码只用 17.7.1.1 已定义的那一套；"
            + "流量维度的桶粒度固定为套餐定义的周期，存量维度的桶粒度跟随统计周期；上限实时读套餐定义。",
        "本模块不落任何金额：没有发票、应收、付款、结算与收入确认。");
  }

  /** 词表：维度码、周期码与状态码，前端不硬编码。 */
  @GetMapping("/saas-usage/options")
  public UsageOptionsView options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, USAGE_READ);
    return new UsageOptionsView(KINDS, PERIODS, COVERAGE_STATES, BILLING_STATES);
  }

  /** 用量报表：租户 × 权益维度 × 统计周期。 */
  @GetMapping("/saas-usage/report")
  public List<UsageReportRow> report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String periodType) {
    authorize(auth, USAGE_READ);
    return sql.fetch(
            "select * from saas_platform_usage_report(?,?,?,?)",
            hash(auth),
            tenantId == null ? DSL.val((UUID) null, UUID.class) : DSL.val(tenantId, UUID.class),
            kind == null || kind.isBlank() ? DSL.val("", String.class) : DSL.val(kind.trim(), String.class),
            periodType == null || periodType.isBlank()
                ? DSL.val("", String.class)
                : DSL.val(periodType.trim().toUpperCase(Locale.ROOT), String.class))
        .stream()
        .map(SaasUsageController::toReportRow)
        .toList();
  }

  /**
   * 趋势：同一租户、同一维度、同一周期一个点，跨周期连成序列。
   *
   * <p>同一个维度下可能有多个权益码（不同套餐的同一维度），这里按维度聚合求和——
   * 流量维度是各权益码用量之和，存量维度是各权益码快照之和（都是「这个租户这个维度总共多少」）。
   */
  @GetMapping("/saas-usage/trend")
  public List<UsageTrendPoint> trend(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String periodType) {
    authorize(auth, USAGE_READ);
    var rows =
        sql.fetch(
            "select * from saas_platform_usage_report(?,?,?,?)",
            hash(auth),
            tenantId == null ? DSL.val((UUID) null, UUID.class) : DSL.val(tenantId, UUID.class),
            kind == null || kind.isBlank() ? DSL.val("", String.class) : DSL.val(kind.trim(), String.class),
            periodType == null || periodType.isBlank()
                ? DSL.val("", String.class)
                : DSL.val(periodType.trim().toUpperCase(Locale.ROOT), String.class));
    // 聚合键：租户 + 维度 + 周期。权益码不参与分组——趋势看的是「这个维度」。
    var grouped = new LinkedHashMap<String, List<Record>>();
    for (var row : rows) {
      String key =
          row.get("tenant_id", UUID.class)
              + "|"
              + row.get("entitlement_kind", String.class)
              + "|"
              + row.get("period_type", String.class)
              + "|"
              + row.get("period_start", OffsetDateTime.class);
      grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
    }
    var out = new ArrayList<UsageTrendPoint>();
    for (var entry : grouped.entrySet()) {
      var first = entry.getValue().get(0);
      BigDecimal quantity = BigDecimal.ZERO;
      var sources = new LinkedHashSet<String>();
      OffsetDateTime collectedAt = null;
      for (var row : entry.getValue()) {
        BigDecimal value = row.get("quantity", BigDecimal.class);
        if (value != null) quantity = quantity.add(value);
        String source = note(row.get("source_system", String.class));
        if (!source.isBlank()) sources.add(source);
        var collected = row.get("collected_at", OffsetDateTime.class);
        if (collected != null && (collectedAt == null || collected.isAfter(collectedAt))) {
          collectedAt = collected;
        }
      }
      out.add(
          new UsageTrendPoint(
              first.get("tenant_id", UUID.class),
              first.get("tenant_code", String.class),
              first.get("tenant_name", String.class),
              first.get("entitlement_kind", String.class),
              first.get("period_type", String.class),
              first.get("period_start", OffsetDateTime.class),
              first.get("period_end", OffsetDateTime.class),
              quantity,
              String.join(",", sources),
              collectedAt));
    }
    out.sort(
        java.util.Comparator.comparing(UsageTrendPoint::tenantCode)
            .thenComparing(UsageTrendPoint::entitlementKind)
            .thenComparing(UsageTrendPoint::periodStart));
    return out;
  }

  /** 计费依据：数量 + 上限 + 结论。没量到的维度也在里面（结论是 UNDETERMINED）。 */
  @GetMapping("/saas-usage/billing-basis")
  public List<UsageBillingRow> billingBasis(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tenantId,
      @RequestParam(required = false) String kind,
      @RequestParam(required = false) String periodType) {
    authorize(auth, USAGE_READ);
    return sql.fetch(
            "select * from saas_platform_usage_billing(?,?,?,?)",
            hash(auth),
            tenantId == null ? DSL.val((UUID) null, UUID.class) : DSL.val(tenantId, UUID.class),
            kind == null || kind.isBlank() ? DSL.val("", String.class) : DSL.val(kind.trim(), String.class),
            periodType == null || periodType.isBlank()
                ? DSL.val("", String.class)
                : DSL.val(periodType.trim().toUpperCase(Locale.ROOT), String.class))
        .stream()
        .map(SaasUsageController::toBillingRow)
        .toList();
  }

  /** 容量规划：每个维度一行。 */
  @GetMapping("/saas-usage/capacity")
  public List<UsageCapacityRow> capacity(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String periodType) {
    authorize(auth, USAGE_READ);
    return sql.fetch(
            "select * from saas_platform_usage_capacity(?,?)",
            hash(auth),
            periodType == null || periodType.isBlank()
                ? DSL.val("", String.class)
                : DSL.val(periodType.trim().toUpperCase(Locale.ROOT), String.class))
        .stream()
        .map(
            r ->
                new UsageCapacityRow(
                    r.get("entitlement_kind", String.class),
                    r.get("subscribed_tenant_count", Long.class),
                    r.get("measured_tenant_count", Long.class),
                    r.get("unmeasured_tenant_count", Long.class),
                    r.get("unlimited_tenant_count", Long.class),
                    r.get("used_total", BigDecimal.class),
                    r.get("licensed_total", BigDecimal.class),
                    r.get("headroom", BigDecimal.class),
                    r.get("latest_period_start", OffsetDateTime.class)))
        .toList();
  }

  /** 维度覆盖：谁真的接通了、谁没接通。 */
  @GetMapping("/saas-usage/coverage")
  public List<UsageCoverageRow> coverage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    authorize(auth, USAGE_READ);
    return sql.fetch("select * from saas_platform_usage_coverage(?)", hash(auth)).stream()
        .map(
            r -> {
              String state = r.get("state", String.class);
              return new UsageCoverageRow(
                  r.get("entitlement_kind", String.class),
                  r.get("subscribed_tenant_count", Long.class),
                  r.get("registered_tenant_count", Long.class),
                  r.get("measured_tenant_count", Long.class),
                  r.get("wired_source_systems", String.class),
                  state,
                  coverageNote(state));
            })
        .toList();
  }

  /** 归集批次历史。 */
  @GetMapping("/saas-usage/collections")
  public List<UsageCollectionRow> collections(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID tenantId) {
    authorize(auth, USAGE_READ);
    return sql.fetch(
            "select * from saas_platform_usage_collections(?,?)",
            hash(auth),
            tenantId == null ? DSL.val((UUID) null, UUID.class) : DSL.val(tenantId, UUID.class))
        .stream()
        .map(
            r ->
                new UsageCollectionRow(
                    r.get("collection_id", UUID.class),
                    r.get("tenant_id", UUID.class),
                    r.get("tenant_code", String.class),
                    r.get("tenant_name", String.class),
                    r.get("subscription_id", UUID.class),
                    r.get("period_type", String.class),
                    r.get("period_start", OffsetDateTime.class),
                    r.get("period_end", OffsetDateTime.class),
                    r.get("dimension_count", Integer.class),
                    r.get("measured_count", Integer.class),
                    r.get("unmeasured_count", Integer.class),
                    r.get("status", String.class),
                    r.get("source_systems", String.class),
                    r.get("started_at", OffsetDateTime.class),
                    r.get("finished_at", OffsetDateTime.class),
                    r.get("note", String.class)))
        .toList();
  }

  // ------------------------------------------------------------------
  // 写出口：归集
  // ------------------------------------------------------------------

  /**
   * 归集：把来源系统登记的「当前用量」物化成按周期分桶的统计事实。
   *
   * <p>归集是<b>写</b>动作，所以逐个租户单独成事务（ADR-0005：每次业务事务选定一个明确
   * 目标租户）。{@code tenantId} 为空时按归集范围逐个租户跑，每个租户一个事务。
   *
   * <p>同一个周期重复归集是<b>覆盖</b>不是累加 —— 累加会让重复跑一次的租户用量翻倍。
   */
  @PostMapping("/saas-usage/collections")
  public UsageCollectionRun collect(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody UsageCollectionRequest input) {
    var a = authorize(auth, USAGE_WRITE);
    String periodType = note(input.periodType()).toUpperCase(Locale.ROOT);
    check(PERIODS.contains(periodType), "统计周期只能是 DAY / MONTH / YEAR / TOTAL");
    // 周期边界按 UTC 切分：先把 asOf 折算到 UTC 再截断。
    OffsetDateTime asOf =
        (input.asOf() == null ? utcNow() : input.asOf()).withOffsetSameInstant(ZoneOffset.UTC);

    List<Target> targets;
    if (input.tenantId() != null) {
      requireTenant(auth, input.tenantId());
      var sub = tenants.run(input.tenantId(), () -> currentSubscription(input.tenantId()));
      if (sub == null)
        throw IdentityController.error(
            HttpStatus.BAD_REQUEST, "该租户没有生效的订阅，无从归集用量");
      targets =
          List.of(
              new Target(
                  input.tenantId(),
                  sub.get("id", UUID.class),
                  sub.get("plan_code", String.class)));
    } else {
      targets =
          sql.fetch("select * from saas_platform_usage_scope(?)", hash(auth)).stream()
              .map(
                  r ->
                      new Target(
                          r.get("tenant_id", UUID.class),
                          r.get("subscription_id", UUID.class),
                          r.get("plan_code", String.class)))
              .toList();
    }

    var results = new ArrayList<UsageCollectionTenantResult>();
    int measured = 0;
    int unmeasured = 0;
    for (var target : targets) {
      var result =
          tenants.run(target.tenantId(), () -> collectOne(a, auth, target, periodType, asOf));
      results.add(result);
      measured += result.measuredCount();
      unmeasured += result.unmeasuredCount();
    }
    return new UsageCollectionRun(
        periodType, asOf, results.size(), measured, unmeasured, results);
  }

  /** 一个租户的一次归集（在该租户的 RLS 上下文里跑）。 */
  private UsageCollectionTenantResult collectOne(
      IdentityController.Access a,
      String auth,
      Target target,
      String periodType,
      OffsetDateTime asOf) {
    UUID tenantId = target.tenantId();
    UUID subscriptionId = target.subscriptionId();
    var entitlements =
        sql.fetch(
            "select entitlement_code,entitlement_kind,period from saas_plan_entitlement"
                + " where plan_code=? order by sort_order,entitlement_code",
            target.planCode());
    var registrations = new LinkedHashMap<String, Record>();
    for (var row :
        sql.fetch(
            "select entitlement_code,usage_value,available,source_system,measured_at,note"
                + " from saas_entitlement_usage where tenant_id=? and subscription_id=?",
            tenantId,
            subscriptionId)) {
      registrations.put(row.get("entitlement_code", String.class), row);
    }

    var sub = currentSubscription(tenantId);
    OffsetDateTime subscriptionStart =
        sub == null
            ? null
            : firstNonNull(
                sub.get("started_at", OffsetDateTime.class),
                sub.get("trial_start", OffsetDateTime.class),
                sub.get("created_at", OffsetDateTime.class));

    var measuredKinds = new ArrayList<String>();
    var unmeasuredKinds = new ArrayList<String>();
    var sources = new LinkedHashSet<String>();
    for (var entitlement : entitlements) {
      String code = entitlement.get("entitlement_code", String.class);
      String kind = entitlement.get("entitlement_kind", String.class);
      // 维度口径取自套餐定义：流量维度的桶粒度固定为它，存量维度的桶粒度跟随统计周期。
      String caliber = entitlement.get("period", String.class);
      var registration = registrations.get(code);
      if (registration == null
          || !Boolean.TRUE.equals(registration.get("available", Boolean.class))
          || registration.get("usage_value", BigDecimal.class) == null) {
        // 没接通：统计事实里**没有这一行**，计费依据里它是 UNDETERMINED。
        unmeasuredKinds.add(kind);
        continue;
      }
      var bucket = bucketFor(caliber, periodType, asOf, subscriptionStart);
      String source = note(registration.get("source_system", String.class));
      sql.execute(
          "insert into saas_usage_statistic(id,tenant_id,subscription_id,entitlement_code,"
              + " entitlement_kind,period_type,period_start,period_end,quantity,source_system,"
              + " measured_at,collected_at,collected_by,note)"
              + " values(?,?,?,?,?,?,?::timestamptz,?::timestamptz,?,?,?::timestamptz,"
              + "?::timestamptz,?,?)"
              + " on conflict (tenant_id,subscription_id,entitlement_code,period_type,period_start)"
              + " do update set entitlement_kind=excluded.entitlement_kind,"
              + " period_end=excluded.period_end,quantity=excluded.quantity,"
              + " source_system=excluded.source_system,measured_at=excluded.measured_at,"
              + " collected_at=excluded.collected_at,collected_by=excluded.collected_by,"
              + " note=excluded.note",
          Ids.next(),
          tenantId,
          subscriptionId,
          code,
          kind,
          bucket.type(),
          bucket.start(),
          bucket.end(),
          registration.get("usage_value", BigDecimal.class),
          source,
          registration.get("measured_at", OffsetDateTime.class),
          utcNow(),
          a.membershipId(),
          note(registration.get("note", String.class)));
      measuredKinds.add(kind);
      if (!source.isBlank()) sources.add(source);
    }

    int dimensionCount = entitlements.size();
    int measuredCount = measuredKinds.size();
    int unmeasuredCount = unmeasuredKinds.size();
    var cycle = bucketFor(periodType, periodType, asOf, subscriptionStart);
    UUID collectionId = Ids.next();
    OffsetDateTime startedAt = utcNow();
    sql.execute(
        "insert into saas_usage_collection(id,tenant_id,subscription_id,period_type,period_start,"
            + " period_end,dimension_count,measured_count,unmeasured_count,status,source_systems,"
            + " started_at,finished_at,actor_membership_id,note)"
            + " values(?,?,?,?,?::timestamptz,?::timestamptz,?,?,?,?,?,?::timestamptz,"
            + "?::timestamptz,?,?)",
        collectionId,
        tenantId,
        subscriptionId,
        periodType,
        cycle.start(),
        cycle.end(),
        dimensionCount,
        measuredCount,
        unmeasuredCount,
        measuredCount == dimensionCount ? "COMPLETED" : "PARTIAL",
        String.join(",", sources),
        startedAt,
        utcNow(),
        a.membershipId(),
        "");
    auditCollection(
        a,
        auth,
        collectionId,
        tenantId,
        periodType,
        dimensionCount,
        measuredCount,
        unmeasuredCount,
        String.join(",", sources));
    return new UsageCollectionTenantResult(
        tenantId,
        subscriptionId,
        target.planCode(),
        collectionId,
        dimensionCount,
        measuredCount,
        unmeasuredCount,
        measuredCount == dimensionCount ? "COMPLETED" : "PARTIAL",
        measuredKinds,
        unmeasuredKinds,
        new ArrayList<>(sources));
  }

  /** 该租户的当前订阅（最新创建的一条，排除已取消）。 */
  private Record currentSubscription(UUID tenantId) {
    return sql.fetchOne(
        "select id,plan_code,status,started_at,trial_start,created_at from saas_subscription"
            + " where tenant_id=? and status<>'CANCELLED' order by created_at desc,id desc limit 1",
        tenantId);
  }

  /**
   * 统计桶。
   *
   * <p>流量口径（套餐定义 period ∈ DAY/MONTH/YEAR）：桶粒度<b>固定</b>为套餐定义的周期。
   * 存量口径（period = TOTAL）：值是采集时刻的快照，桶粒度跟随统计周期。
   */
  private static Bucket bucketFor(
      String caliber, String requestedPeriod, OffsetDateTime asOf, OffsetDateTime subscriptionStart) {
    String type = "TOTAL".equals(caliber) ? requestedPeriod : caliber;
    return switch (type) {
      case "DAY" -> {
        var start = asOf.truncatedTo(ChronoUnit.DAYS);
        yield new Bucket("DAY", start, start.plusDays(1));
      }
      case "MONTH" -> {
        var start = asOf.truncatedTo(ChronoUnit.DAYS).withDayOfMonth(1);
        yield new Bucket("MONTH", start, start.plusMonths(1));
      }
      case "YEAR" -> {
        var start = asOf.truncatedTo(ChronoUnit.DAYS).withDayOfYear(1);
        yield new Bucket("YEAR", start, start.plusYears(1));
      }
      default -> {
        // 总量桶：从订阅生效到采集时刻。采集时刻不晚于生效时刻时（刚开通就归集），
        // 区间长度必须为正，否则会被 CHECK 拒掉。
        var start = subscriptionStart == null ? asOf.truncatedTo(ChronoUnit.DAYS) : subscriptionStart;
        var end = asOf.isAfter(start) ? asOf : start.plusSeconds(1);
        yield new Bucket("TOTAL", start, end);
      }
    };
  }

  private static OffsetDateTime firstNonNull(OffsetDateTime... values) {
    for (var value : values) if (value != null) return value;
    return null;
  }

  /**
   * 审计：归集是平台运营动作，审计落在<b>平台运营租户</b>的审计流水里
   * （目标租户在变更详情里）。{@code identity_audit} 受 RLS 保护，所以必须在该租户的
   * 上下文里写。
   */
  private void auditCollection(
      IdentityController.Access a,
      String auth,
      UUID resourceId,
      UUID tenantId,
      String periodType,
      int dimensionCount,
      int measuredCount,
      int unmeasuredCount,
      String sourceSystems) {
    var after = new LinkedHashMap<String, Object>();
    after.put("tenantId", tenantId.toString());
    after.put("periodType", periodType);
    after.put("dimensionCount", dimensionCount);
    after.put("measuredCount", measuredCount);
    after.put("unmeasuredCount", unmeasuredCount);
    after.put("sourceSystems", sourceSystems);
    tenants.run(
        a.tenantId(),
        () -> {
          audit.record(
              a,
              auth,
              "identity.saas_usage_statistic",
              resourceId,
              "用量统计 · " + tenantId,
              "SaasUsageCollected",
              null,
              after);
          return null;
        });
  }

  // ------------------------------------------------------------------
  // 映射
  // ------------------------------------------------------------------

  private static UsageReportRow toReportRow(Record r) {
    return new UsageReportRow(
        r.get("tenant_id", UUID.class),
        r.get("tenant_code", String.class),
        r.get("tenant_name", String.class),
        r.get("subscription_id", UUID.class),
        r.get("plan_code", String.class),
        r.get("plan_name", String.class),
        r.get("subscription_status", String.class),
        r.get("entitlement_code", String.class),
        r.get("entitlement_kind", String.class),
        r.get("entitlement_limit", BigDecimal.class),
        Boolean.TRUE.equals(r.get("entitlement_unlimited", Boolean.class)),
        r.get("over_limit_action", String.class),
        r.get("entitlement_unit", String.class),
        r.get("entitlement_period", String.class),
        r.get("period_type", String.class),
        r.get("period_start", OffsetDateTime.class),
        r.get("period_end", OffsetDateTime.class),
        r.get("quantity", BigDecimal.class),
        r.get("source_system", String.class),
        r.get("measured_at", OffsetDateTime.class),
        r.get("collected_at", OffsetDateTime.class),
        r.get("utilization", BigDecimal.class),
        Boolean.TRUE.equals(r.get("over_limit", Boolean.class)));
  }

  private static UsageBillingRow toBillingRow(Record r) {
    BigDecimal quantity = r.get("quantity", BigDecimal.class);
    boolean unlimited = Boolean.TRUE.equals(r.get("entitlement_unlimited", Boolean.class));
    boolean overLimit = Boolean.TRUE.equals(r.get("over_limit", Boolean.class));
    String state =
        quantity == null
            ? "UNDETERMINED"
            : unlimited ? "UNLIMITED" : overLimit ? "OVER_LIMIT" : "WITHIN_LIMIT";
    return new UsageBillingRow(
        r.get("tenant_id", UUID.class),
        r.get("tenant_code", String.class),
        r.get("tenant_name", String.class),
        r.get("plan_code", String.class),
        r.get("plan_name", String.class),
        r.get("subscription_status", String.class),
        r.get("entitlement_code", String.class),
        r.get("entitlement_kind", String.class),
        r.get("entitlement_limit", BigDecimal.class),
        unlimited,
        r.get("over_limit_action", String.class),
        r.get("entitlement_unit", String.class),
        r.get("entitlement_period", String.class),
        r.get("period_type", String.class),
        r.get("period_start", OffsetDateTime.class),
        r.get("period_end", OffsetDateTime.class),
        quantity,
        r.get("source_system", String.class),
        r.get("measured_at", OffsetDateTime.class),
        r.get("collected_at", OffsetDateTime.class),
        r.get("utilization", BigDecimal.class),
        overLimit,
        state,
        billingNote(state, r.get("over_limit_action", String.class)));
  }

  private static String billingNote(String state, String overLimitAction) {
    return switch (state) {
      case "UNDETERMINED" -> "该维度尚未接通计量：判定不了，不是「没超限」";
      case "OVER_LIMIT" ->
          "已超出权益上限，超限策略为 " + (overLimitAction == null ? "未配置" : overLimitAction);
      case "UNLIMITED" -> "该权益不设上限";
      default -> "用量在权益上限内";
    };
  }

  private static String coverageNote(String state) {
    return switch (state) {
      case "WIRED" -> "全部有订阅的租户都已由来源系统登记";
      case "PARTIAL" -> "部分租户已由来源系统登记，其余还没接通";
      case "NOT_WIRED" -> "没有任何来源系统登记过这个维度";
      default -> "当前没有租户订阅含这个维度的套餐";
    };
  }
}
