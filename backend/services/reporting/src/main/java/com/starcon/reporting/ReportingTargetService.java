package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.1.2.1 目标设定的**读写模型**（蓝图「输入」句：目标定义 + 历史基线参考；「输出」句：目标记录 + 分解计划）。
 *
 * <p>🔴 **本类不定义指标，只引用指标**：目标存的是「**已登记的口径码 + 目标值 + 周期 + 归属对象**」，
 * 口径（单位 / 聚合方式 / 分子分母）全部来自口径表
 * （{@code reporting_metric_definition} / {@code reporting_store_metric_definition}）。
 * SQL 与 Java 里**没有一处硬编码的指标公式**。
 *
 * <p>🔴 **目标必须挂在组织对象上**（蓝图关系句的命门）：归属对象是部门 / 人员 / 店铺 / 产品，
 * 本服务**不造一张组织表顶替** —— 组织本体在 identity / catalog / channel，
 * 这里走**授权只读 API** 取清单，并把**快照**（编码 + 显示名）抄到目标行上，
 * 组织改名后历史目标仍认得出是谁。
 *
 * <p>🔴 **多币种绝不相加**：目标值带币种，分解项带币种，汇总按币种分列。
 * 一个 USD 目标和一个 EUR 目标的大小本来就不可比。
 *
 * <p>🔴 **「算不出来」与「等于 0」分开说**：基线取不到时是 {@code null} + 原因，**不是 0**。
 */
@Service
public class ReportingTargetService {

  /** 分页上限（防呆）。 */
  private static final int MAX_PAGE_SIZE = 200;

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingOverviewService overview;
  private final ReportingStoreComparisonService comparison;
  private final ReportingTargetOrgPort org;
  private final Clock clock;

  public ReportingTargetService(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingOverviewService overview,
      ReportingStoreComparisonService comparison,
      ReportingTargetOrgPort org,
      Clock clock) {
    this.sql = sql;
    this.tenants = tenants;
    this.overview = overview;
    this.comparison = comparison;
    this.org = org;
    this.clock = clock;
  }

  // ==========================================================================
  // 能力声明
  // ==========================================================================

  /** 逐条报出：归属对象的来源、历史基线的边界、蓝图点名的指标里哪些口径没登记。 */
  public ReportingTargetModels.TargetCapabilities capabilities(UUID tenantId) {
    return tenants.run(
        tenantId,
        () ->
            new ReportingTargetModels.TargetCapabilities(
                true, true, UPSTREAM_MODULES, NOT_DELIVERED, NOTES));
  }

  // ==========================================================================
  // 目标指标清单
  // ==========================================================================

  /**
   * 蓝图点名的目标指标清单（按归属维度）。
   *
   * <p>⚠️ **显示名 / 单位 / 口径说明全部取自口径表**（运行时唯一真相），
   * 本模块只提供「蓝图的这个目标名 ↔ 哪些口径码」的映射。
   */
  public List<ReportingTargetModels.TargetMetricView> metrics(UUID tenantId, String scopeType) {
    var scope = requireScope(scopeType);
    return tenants.run(
        tenantId,
        () -> {
          var orgCalibers = orgCalibers(tenantId);
          var shopCalibers = shopCalibers(tenantId);
          var out = new ArrayList<ReportingTargetModels.TargetMetricView>();
          for (var metric : ReportingTargetMetricCatalog.STANDARD) {
            var availability = ReportingTargetMetricCatalog.availability(metric.key(), scope);
            if (availability == null) continue;
            var calipers = new ArrayList<ReportingTargetModels.TargetCaliberView>();
            for (var caliber : availability.calipers()) {
              var definition =
                  ReportingTargetMetricCatalog.SCOPE_SHOP.equals(scope)
                      ? shopCalibers.get(caliber.metricCode())
                      : orgCalibers.get(caliber.metricCode());
              if (definition == null) continue;
              calipers.add(
                  new ReportingTargetModels.TargetCaliberView(
                      definition.metricCode(),
                      definition.displayName(),
                      definition.unit(),
                      definition.aggregation(),
                      definition.description(),
                      definition.ownerModule()));
            }            out.add(
                new ReportingTargetModels.TargetMetricView(
                    metric.key(),
                    metric.displayName(),
                    metric.unit(),
                    metric.ownerModule(),
                    scope,
                    availability.available() && !calipers.isEmpty(),
                    calipers,
                    availability.available() && calipers.isEmpty()
                        ? "该口径码还没有在口径表里登记 —— 口径唯一，没有登记就没有这个指标。"
                        : availability.reason(),
                    availability.available() && !calipers.isEmpty()
                        ? "目标**引用**这个已登记的口径码，不重算口径（口径真相在口径表里）。"
                        : ""));
          }
          return out;
        });
  }

  // ==========================================================================
  // 归属对象清单
  // ==========================================================================

  /**
   * 归属对象清单（**权威来源**）：部门 / 人员来自 identity，店铺来自本服务的店铺档案，产品来自 catalog。
   *
   * @param bearer 调用方自己的会话（转发给上游，上游按已认证成员关系解析租户）
   */
  public ReportingTargetModels.TargetOwnerList owners(
      UUID tenantId, String scopeType, String bearer) {
    var scope = requireScope(scopeType);
    if (ReportingTargetMetricCatalog.SCOPE_SHOP.equals(scope)) {
      return tenants.run(tenantId, () -> shopOwners(tenantId));
    }
    // ⚠️ 上游清单在**事务外**取：HTTP 调用不该占着数据库连接。
    switch (scope) {
      case ReportingTargetMetricCatalog.SCOPE_DEPARTMENT -> {
        var list = org.departments(bearer);
        var owners = new ArrayList<ReportingTargetModels.TargetOwnerView>();
        for (var department : list) {
          var detail = new StringBuilder();
          if (department.leaderName() != null && !department.leaderName().isBlank()) {
            detail.append("负责人 ").append(department.leaderName());
          }
          if (department.memberCount() > 0) {
            if (detail.length() > 0) detail.append(" · ");
            detail.append(department.memberCount()).append(" 人");
          }
          owners.add(
              new ReportingTargetModels.TargetOwnerView(
                  scope,
                  department.id().toString(),
                  department.code() == null ? "" : department.code(),
                  department.name() == null ? "" : department.name(),
                  detail.toString()));
        }
        return new ReportingTargetModels.TargetOwnerList(
            scope, owners, "identity（GET /identity/departments）", "");
      }
      case ReportingTargetMetricCatalog.SCOPE_MEMBER -> {
        // 人员带部门名 ⇒ 顺带取一次部门清单（同一个上游，不额外增加失败面）。
        var departments = new LinkedHashMap<String, String>();
        for (var department : org.departments(bearer)) {
          departments.put(department.id().toString(), department.name());
        }
        var list = org.members(bearer);
        var owners = new ArrayList<ReportingTargetModels.TargetOwnerView>();
        for (var member : list) {
          var detail = new StringBuilder();
          if (member.departmentId() != null) {
            var name = departments.get(member.departmentId().toString());
            detail.append(name == null || name.isBlank() ? "未分配部门" : name);
          } else {
            detail.append("未分配部门");
          }
          if (member.owner()) {
            detail.append(" · 企业所有者");
          }
          owners.add(
              new ReportingTargetModels.TargetOwnerView(
                  scope,
                  member.id().toString(),
                  "",
                  member.username() == null ? "" : member.username(),
                  detail.toString()));
        }
        return new ReportingTargetModels.TargetOwnerList(
            scope, owners, "identity（GET /identity/members）", "");
      }
      case ReportingTargetMetricCatalog.SCOPE_PRODUCT -> {
        var list = org.products(bearer);
        var owners = new ArrayList<ReportingTargetModels.TargetOwnerView>();
        for (var product : list) {
          var detail = new StringBuilder();
          if (product.brand() != null && !product.brand().isBlank()) {
            detail.append(product.brand());
          }
          if (product.skuCount() > 0) {
            if (detail.length() > 0) detail.append(" · ");
            detail.append(product.skuCount()).append(" 个 SKU");
          }
          owners.add(
              new ReportingTargetModels.TargetOwnerView(
                  scope,
                  product.id().toString(),
                  product.code() == null ? "" : product.code(),
                  product.name() == null ? "" : product.name(),
                  detail.toString()));
        }
        return new ReportingTargetModels.TargetOwnerList(
            scope, owners, "catalog（GET /catalog/products）", "");
      }
      default -> throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "归属对象类型无法解析：" + scopeType);
    }
  }

  /** 店铺清单（来自本服务自己的店铺档案投影 —— 13.1.1.2 多店对比落的那张表）。 */
  private ReportingTargetModels.TargetOwnerList shopOwners(UUID tenantId) {
    var rows =
        sql.fetch(
            "select shop_code, shop_name, platform_name from reporting_store_profile "
                + "where tenant_id = ? order by platform_code, shop_code",
            tenantId);
    var owners = new ArrayList<ReportingTargetModels.TargetOwnerView>();
    for (var row : rows) {
      var platform = row.get(2, String.class);
      owners.add(
          new ReportingTargetModels.TargetOwnerView(
              ReportingTargetMetricCatalog.SCOPE_SHOP,
              row.get(0, String.class),
              row.get(0, String.class),
              row.get(1, String.class) == null ? "" : row.get(1, String.class),
              platform == null || platform.isBlank() ? "未标注平台" : platform));
    }
    return new ReportingTargetModels.TargetOwnerList(
        ReportingTargetMetricCatalog.SCOPE_SHOP,
        owners,
        "reporting 店铺档案（reporting_store_profile，13.1.1.2 多店对比的投影）",
        owners.isEmpty()
            ? "这个租户还没有店铺档案 —— 13.1.1.2 多店对比的店铺维度投影跑过之后才有店铺可挂目标。"
            : "");
  }

  // ==========================================================================
  // 分解预览
  // ==========================================================================

  /**
   * 分解预览（**不落库**）：给定周期与目标值，算出分解计划。
   *
   * <p>⚠️ 预览与落库走**同一套计算**（{@link ReportingTargetDecomposition}），
   * 所以页面上看到的分解项与真正存进去的**逐分一致**。
   */
  public ReportingTargetModels.TargetDecompositionPreview decompositionPreview(
      LocalDate periodStart,
      LocalDate periodEnd,
      String granularity,
      String method,
      String currency,
      BigDecimal targetValue,
      List<BigDecimal> weights) {
    var total = normalize(targetValue);
    var buckets = decompose(periodStart, periodEnd, granularity, method, total, weights);
    var sum = BigDecimal.ZERO;
    var views = new ArrayList<ReportingTargetModels.TargetBucketView>();
    for (var bucket : buckets) {
      sum = sum.add(bucket.value());
      views.add(
          new ReportingTargetModels.TargetBucketView(
              bucket.start(),
              bucket.end(),
              granularity,
              currency,
              bucket.value(),
              bucket.weight()));
    }
    return new ReportingTargetModels.TargetDecompositionPreview(
        granularity,
        method,
        currency,
        total,
        views,
        sum,
        sum.compareTo(total) == 0,
        ReportingTargetDecomposition.remainderNote(method, buckets, total));
  }

  // ==========================================================================
  // 周期边界（本租户时区）
  // ==========================================================================

  /**
   * 当前周期边界（**按本租户时区算**，不写死 UTC）。
   *
   * <p>⚠️ 时区取自本租户投影事实上的分桶时区（{@code reporting_metric_fact.timezone}），
   * 与 1.1.2.1 核心指标卡 / 13.1.1.1 经营总览**同一条纪律**
   * （13.1.1.2 栽过一次：行里声称自己按 UTC 分桶，实际按的是别的时区）。
   *
   * <p>⚠️ 本租户还没有任何投影事实 ⇒ 暂按 UTC，并在响应里说清楚（{@code timezoneSource}），
   * 一旦投影进来就会改用该租户自己的时区。
   *
   * @param granularity MONTHLY / WEEKLY
   */
  public ReportingTargetModels.TargetCurrentPeriod currentPeriod(UUID tenantId, String granularity) {
    var scope = granularity == null ? "" : granularity.trim().toUpperCase(java.util.Locale.ROOT);
    if (!"MONTHLY".equals(scope) && !"WEEKLY".equals(scope)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "granularity 只能是 MONTHLY 或 WEEKLY，收到：" + granularity);
    }
    return tenants.run(tenantId, () -> currentPeriodInTransaction(tenantId, scope));
  }

  private ReportingTargetModels.TargetCurrentPeriod currentPeriodInTransaction(
      UUID tenantId, String granularity) {
    var zone = tenantZone(tenantId);
    var asOf = LocalDate.now(clock.withZone(ZoneId.of(zone.zone())));
    LocalDate from;
    LocalDate to;
    if ("MONTHLY".equals(granularity)) {
      from = asOf.withDayOfMonth(1);
      to = asOf.withDayOfMonth(asOf.lengthOfMonth());
    } else {
      from =
          asOf.with(
              java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
      to = from.plusDays(6);
    }
    return new ReportingTargetModels.TargetCurrentPeriod(
        granularity, from, to, asOf, zone.zone(), zone.source(), zone.note());
  }

  /** 本租户的分桶时区（**服务端判定**，不来自请求参数）。 */
  private record TenantZone(String zone, String source, String note) {}

  private TenantZone tenantZone(UUID tenantId) {
    var row =
        sql.fetchOne(
            "select timezone from reporting_metric_fact "
                + "where tenant_id = ? and timezone <> '' order by updated_at desc limit 1",
            tenantId);
    if (row != null) {
      var zone = row.get(0, String.class);
      if (zone != null && !zone.isBlank()) {
        try {
          ZoneId.of(zone);
          return new TenantZone(zone, "PROJECTION", "");
        } catch (RuntimeException ignored) {
          // 落库的是非法时区 id ⇒ 落回 UTC，并在响应里说清楚（不静默）。
        }
      }
    }
    return new TenantZone(
        "UTC",
        "FALLBACK_UTC",
        "本租户还没有任何投影事实，取不到自己的分桶时区，当前周期暂按 UTC 计算 —— "
            + "一旦投影进来就会改用该租户自己的时区。");
  }

  // ==========================================================================
  // 目标清单
  // ==========================================================================

  /**
   * 目标清单（**真分页 + 真计数**）。
   *
   * @param scopeType 归属对象类型（可空 ⇒ 全部）
   * @param metricCode 口径码（可空 ⇒ 全部）
   * @param status ACTIVE / ARCHIVED（可空 ⇒ 全部）
   * @param ownerId 归属对象标识（可空 ⇒ 全部）
   */
  public ReportingTargetModels.TargetListResponse list(
      UUID tenantId,
      String scopeType,
      String metricCode,
      String status,
      String ownerId,
      int page,
      int size) {
    var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    var effectivePage = Math.max(page, 0);
    return tenants.run(
        tenantId,
        () -> {
          var where = new ArrayList<Object>();
          where.add(tenantId);
          var filter = new StringBuilder(" where t.tenant_id = ?");
          if (scopeType != null && !scopeType.isBlank()) {
            requireScope(scopeType);
            filter.append(" and t.scope_type = ?");
            where.add(scopeType.trim());
          }
          if (metricCode != null && !metricCode.isBlank()) {
            filter.append(" and t.metric_code = ?");
            where.add(metricCode.trim());
          }
          if (status != null && !status.isBlank()) {
            var effective = status.trim();
            if (!"ACTIVE".equals(effective) && !"ARCHIVED".equals(effective)) {
              throw new ResponseStatusException(
                  HttpStatus.BAD_REQUEST, "status 只能是 ACTIVE 或 ARCHIVED，收到：" + effective);
            }
            filter.append(" and t.status = ?");
            where.add(effective);
          }
          if (ownerId != null && !ownerId.isBlank()) {
            filter.append(" and t.owner_id = ?");
            where.add(ownerId.trim());
          }

          var total =
              sql.fetchOne(
                  "select count(*) from reporting_target t" + filter, where.toArray());
          var rows =
              sql.fetch(
                  "select t.id, t.metric_code, t.scope_type, t.owner_id, t.owner_code, t.owner_name, "
                      + "t.currency, t.target_value, t.baseline_value, t.baseline_from, t.baseline_to, "
                      + "t.baseline_status, t.baseline_note, t.period_start, t.period_end, "
                      + "t.granularity, t.decomposition_method, t.status, t.note, "
                      + "t.created_at, t.updated_at, "
                      + "(select count(*) from reporting_target_bucket b "
                      + "  where b.tenant_id = t.tenant_id and b.target_id = t.id), "
                      + "(select coalesce(sum(b.target_value), 0) from reporting_target_bucket b "
                      + "  where b.tenant_id = t.tenant_id and b.target_id = t.id) "
                      + "from reporting_target t"
                      + filter
                      + " order by t.period_start desc, t.scope_type, t.owner_name, t.id "
                      + "limit ? offset ?",
                  append(where, effectiveSize, effectivePage * effectiveSize));

          var orgCalibers = orgCalibers(tenantId);
          var shopCalibers = shopCalibers(tenantId);
          var targets = new ArrayList<ReportingTargetModels.TargetView>();
          for (var row : rows) {
            targets.add(target(row, orgCalibers, shopCalibers, List.of(), 0, BigDecimal.ZERO));
          }

          // 合计**按币种分列**（绝不跨币种相加）。
          var totals =
              sql.fetch(
                  "select t.currency, sum(t.target_value), count(*) from reporting_target t"
                      + filter
                      + " group by t.currency order by t.currency",
                  where.toArray());
          var currencyTotals = new ArrayList<ReportingTargetModels.TargetCurrencyTotal>();
          for (var row : totals) {
            currencyTotals.add(
                new ReportingTargetModels.TargetCurrencyTotal(
                    row.get(0, String.class),
                    row.get(1, BigDecimal.class),
                    row.get(2, Integer.class)));
          }

          return new ReportingTargetModels.TargetListResponse(
              effectivePage,
              effectiveSize,
              total == null ? 0L : total.get(0, Long.class),
              targets,
              currencyTotals,
              "合计按币种分列：不同币种的目标**绝不相加**（一个 USD 目标和一个 EUR 目标的大小本来就不可比）。");
        });
  }

  // ==========================================================================
  // 目标详情
  // ==========================================================================

  /** 目标详情（含完整分解计划）。 */
  public ReportingTargetModels.TargetView detail(UUID tenantId, UUID id) {
    return tenants.run(tenantId, () -> detailInTransaction(tenantId, id));
  }

  private ReportingTargetModels.TargetView detailInTransaction(UUID tenantId, UUID id) {
    var row =
        sql.fetchOne(
            "select id, metric_code, scope_type, owner_id, owner_code, owner_name, currency, "
                + "target_value, baseline_value, baseline_from, baseline_to, baseline_status, "
                + "baseline_note, period_start, period_end, granularity, decomposition_method, "
                + "status, note, created_at, updated_at from reporting_target "
                + "where tenant_id = ? and id = ?",
            tenantId,
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标不存在或不在你的可见范围：" + id);
    }
    var bucketRows =
        sql.fetch(
            "select bucket_start, bucket_end, granularity, currency, target_value, weight "
                + "from reporting_target_bucket where tenant_id = ? and target_id = ? "
                + "order by bucket_start",
            tenantId,
            id);
    var buckets = new ArrayList<ReportingTargetModels.TargetBucketView>();
    var sum = BigDecimal.ZERO;
    for (var bucket : bucketRows) {
      var value = bucket.get(4, BigDecimal.class);
      sum = sum.add(value);
      buckets.add(
          new ReportingTargetModels.TargetBucketView(
              bucket.get(0, LocalDate.class),
              bucket.get(1, LocalDate.class),
              bucket.get(2, String.class),
              bucket.get(3, String.class),
              value,
              bucket.get(5, BigDecimal.class)));
    }
    var orgCalibers = orgCalibers(tenantId);
    var shopCalibers = shopCalibers(tenantId);
    return target(row, orgCalibers, shopCalibers, buckets, buckets.size(), sum);
  }

  // ==========================================================================
  // 写入
  // ==========================================================================

  /** 设定一个目标（同时写下分解计划）。 */
  public ReportingTargetModels.TargetView create(
      UUID tenantId, ReportingTargetModels.TargetInput input, String bearer) {
    return tenants.run(
        tenantId,
        () -> {
          seedCalibers(tenantId);
          var scope = requireScope(input.scopeType());
          var owner = requireOwner(tenantId, scope, input.ownerId(), bearer);
          var total = normalize(input.targetValue());
          var buckets =
              decompose(
                  input.periodStart(),
                  input.periodEnd(),
                  input.granularity(),
                  input.decompositionMethod(),
                  total,
                  input.weights());
          var baseline =
              baseline(
                  tenantId,
                  scope,
                  input.ownerId(),
                  input.metricCode(),
                  input.currency(),
                  input.periodStart(),
                  input.periodEnd());
          var id = Ids.next();
          assertUnique(tenantId, scope, input.ownerId(), input.metricCode(), input.currency(),
              input.periodStart(), input.periodEnd(), null);
          sql.execute(
              "insert into reporting_target (id, tenant_id, metric_code, scope_type, owner_id, "
                  + "owner_code, owner_name, currency, target_value, baseline_value, baseline_from, "
                  + "baseline_to, baseline_status, baseline_note, period_start, period_end, "
                  + "granularity, decomposition_method, status, note, created_at, updated_at) "
                  + "values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now(),now())",
              id,
              tenantId,
              input.metricCode().trim(),
              scope,
              input.ownerId().trim(),
              owner.ownerCode(),
              owner.ownerName(),
              input.currency().trim(),
              total,
              baseline.value(),
              baseline.from(),
              baseline.to(),
              baseline.status(),
              baseline.note(),
              input.periodStart(),
              input.periodEnd(),
              input.granularity(),
              input.decompositionMethod(),
              input.status() == null || input.status().isBlank() ? "ACTIVE" : input.status(),
              input.note() == null ? "" : input.note());
          insertBuckets(tenantId, id, input.granularity(), input.currency(), buckets);
          return detailInTransaction(tenantId, id);
        });
  }

  /** 修改一个目标（目标值 / 周期 / 归属对象 / 分解方式都可改；分解计划随之重写）。 */
  public ReportingTargetModels.TargetView update(
      UUID tenantId, UUID id, ReportingTargetModels.TargetInput input, String bearer) {
    return tenants.run(
        tenantId,
        () -> {
          seedCalibers(tenantId);
          var existing = loadTarget(tenantId, id);
          var scope = requireScope(input.scopeType());
          // 归属对象没变 ⇒ 沿用原快照（不去打扰上游）；变了 ⇒ 重新取快照。
          var owner =
              scope.equals(existing.scopeType()) && input.ownerId().trim().equals(existing.ownerId())
                  ? new ReportingTargetModels.TargetOwnerView(
                      existing.scopeType(), existing.ownerId(), existing.ownerCode(),
                      existing.ownerName(), "")
                  : requireOwner(tenantId, scope, input.ownerId(), bearer);
          var total = normalize(input.targetValue());
          var buckets =
              decompose(
                  input.periodStart(),
                  input.periodEnd(),
                  input.granularity(),
                  input.decompositionMethod(),
                  total,
                  input.weights());
          assertUnique(tenantId, scope, input.ownerId(), input.metricCode(), input.currency(),
              input.periodStart(), input.periodEnd(), id);
          var baseline =
              baseline(
                  tenantId,
                  scope,
                  input.ownerId(),
                  input.metricCode(),
                  input.currency(),
                  input.periodStart(),
                  input.periodEnd());
          sql.execute("delete from reporting_target_bucket where tenant_id = ? and target_id = ?",
              tenantId, id);
          sql.execute(
              "update reporting_target set metric_code = ?, scope_type = ?, owner_id = ?, "
                  + "owner_code = ?, owner_name = ?, currency = ?, target_value = ?, "
                  + "baseline_value = ?, baseline_from = ?, baseline_to = ?, baseline_status = ?, "
                  + "baseline_note = ?, period_start = ?, period_end = ?, granularity = ?, "
                  + "decomposition_method = ?, status = ?, note = ?, updated_at = now() "
                  + "where tenant_id = ? and id = ?",
              input.metricCode().trim(),
              scope,
              input.ownerId().trim(),
              owner.ownerCode(),
              owner.ownerName(),
              input.currency().trim(),
              total,
              baseline.value(),
              baseline.from(),
              baseline.to(),
              baseline.status(),
              baseline.note(),
              input.periodStart(),
              input.periodEnd(),
              input.granularity(),
              input.decompositionMethod(),
              input.status() == null || input.status().isBlank() ? existing.status() : input.status(),
              input.note() == null ? "" : input.note(),
              tenantId,
              id);
          insertBuckets(tenantId, id, input.granularity(), input.currency(), buckets);
          return detailInTransaction(tenantId, id);
        });
  }

  /** 删除一个目标（分解计划随外键级联删除）。 */
  public void delete(UUID tenantId, UUID id) {
    tenants.run(
        tenantId,
        () -> {
          loadTarget(tenantId, id);
          sql.execute("delete from reporting_target where tenant_id = ? and id = ?", tenantId, id);
          return null;
        });
  }

  /** 重新读一次历史基线（事实投影进来之后，基线会变 —— 所以它可以刷新）。 */
  public ReportingTargetModels.TargetView refreshBaseline(UUID tenantId, UUID id) {
    return tenants.run(
        tenantId,
        () -> {
          var existing = loadTarget(tenantId, id);
          var baseline =
              baseline(
                  tenantId,
                  existing.scopeType(),
                  existing.ownerId(),
                  existing.metricCode(),
                  existing.currency(),
                  existing.periodStart(),
                  existing.periodEnd());
          sql.execute(
              "update reporting_target set baseline_value = ?, baseline_from = ?, baseline_to = ?, "
                  + "baseline_status = ?, baseline_note = ?, updated_at = now() "
                  + "where tenant_id = ? and id = ?",
              baseline.value(),
              baseline.from(),
              baseline.to(),
              baseline.status(),
              baseline.note(),
              tenantId,
              id);
          return detailInTransaction(tenantId, id);
        });
  }

  // ==========================================================================
  // 内部：组装
  // ==========================================================================

  /** 一行目标记录 → 出参视图。 */
  private ReportingTargetModels.TargetView target(
      org.jooq.Record row,
      Map<String, CaliberInfo> orgCalibers,
      Map<String, CaliberInfo> shopCalibers,
      List<ReportingTargetModels.TargetBucketView> buckets,
      int bucketCount,
      BigDecimal bucketSum) {
    var metricCode = row.get(1, String.class);
    var scopeType = row.get(2, String.class);
    var definition =
        ReportingTargetMetricCatalog.SCOPE_SHOP.equals(scopeType)
            ? shopCalibers.get(metricCode)
            : orgCalibers.get(metricCode);
    var targetValue = row.get(7, BigDecimal.class);
    var baselineValue = row.get(8, BigDecimal.class);
    var baselineStatus = row.get(11, String.class);
    var baseline =
        new ReportingTargetModels.TargetBaselineView(
            baselineStatus,
            baselineValue,
            baselineValue == null ? null : row.get(6, String.class),
            row.get(9, LocalDate.class),
            row.get(10, LocalDate.class),
            ReportingTargetMetricCatalog.SCOPE_SHOP.equals(scopeType) ? "OWNER" : "TENANT",
            row.get(12, String.class));
    return new ReportingTargetModels.TargetView(
        row.get(0, UUID.class),
        metricCode,
        definition == null ? metricCode : definition.displayName(),
        definition == null ? "" : definition.unit(),
        scopeType,
        row.get(3, String.class),
        row.get(4, String.class),
        row.get(5, String.class),
        row.get(6, String.class),
        targetValue,
        baseline,
        row.get(13, LocalDate.class),
        row.get(14, LocalDate.class),
        row.get(15, String.class),
        row.get(16, String.class),
        row.get(17, String.class),
        row.get(18, String.class),
        buckets,
        bucketCount,
        bucketSum,
        targetValue != null && bucketSum != null && targetValue.compareTo(bucketSum) == 0,
        row.get(19, OffsetDateTime.class),
        row.get(20, OffsetDateTime.class));
  }

  /** 写分解项。 */
  private void insertBuckets(
      UUID tenantId, UUID targetId, String granularity, String currency,
      List<ReportingTargetDecomposition.Bucket> buckets) {
    for (var bucket : buckets) {
      sql.execute(
          "insert into reporting_target_bucket (id, tenant_id, target_id, bucket_start, bucket_end, "
              + "granularity, currency, target_value, weight, created_at, updated_at) "
              + "values (?,?,?,?,?,?,?,?,?,now(),now())",
          Ids.next(),
          tenantId,
          targetId,
          bucket.start(),
          bucket.end(),
          granularity,
          currency,
          bucket.value(),
          bucket.weight());
    }
  }

  // ==========================================================================
  // 内部：口径目录
  // ==========================================================================

  /**
   * 🔴 **先播种口径表再写目标**：目标引用的指标码必须在口径表里登记
   * （DB 触发器钉死），而口径表是**懒播种**的（第一次用到这个租户时按标准份播种一份）。
   */
  private void seedCalibers(UUID tenantId) {
    overview.metricDefinitions(tenantId);
    comparison.metricDefinitions(tenantId);
  }

  private Map<String, CaliberInfo> orgCalibers(UUID tenantId) {
    var map = new LinkedHashMap<String, CaliberInfo>();
    for (var definition : overview.metricDefinitions(tenantId)) {
      map.put(
          definition.metricCode(),
          new CaliberInfo(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.description(),
              definition.ownerModule()));
    }
    return map;
  }

  private Map<String, CaliberInfo> shopCalibers(UUID tenantId) {
    var map = new LinkedHashMap<String, CaliberInfo>();
    for (var definition : comparison.metricDefinitions(tenantId)) {
      map.put(
          definition.metricCode(),
          new CaliberInfo(
              definition.metricCode(),
              definition.displayName(),
              definition.unit(),
              definition.aggregation(),
              definition.description(),
              definition.ownerModule()));
    }
    return map;
  }

  /** 口径信息的内部统一形状（两张口径表的行结构不同，但对外是同一件事）。 */
  private record CaliberInfo(
      String metricCode,
      String displayName,
      String unit,
      String aggregation,
      String description,
      String ownerModule) {}

  // ==========================================================================
  // 内部：历史基线
  // ==========================================================================

  /**
   * 历史基线（**取自已投影的事实，不是新算一遍**）。
   *
   * <p>⚠️ **基线窗口 = 目标周期的去年同期**（period_start − 1 年 ~ period_end − 1 年）。
   *
   * <p>⚠️ **维度是明说的**：
   *
   * <ul>
   *   <li><b>店铺维度</b> ⇒ 取该**店铺自己**的历史事实（{@code reporting_store_metric_fact}），
   *       scope = OWNER；</li>
   *   <li><b>部门 / 人员 / 产品维度</b> ⇒ 这三个维度的历史事实**还没有投影到 reporting**
   *       （上游只投影到公司 / 法人 / 店铺维度）⇒ 取**租户级（公司维度）**的同一口径值作为
   *       <b>参考值</b>，scope = TENANT，<b>并在说明里写清它不是该归属对象自己的历史</b>。</li>
 * </ul>
   *
   * <p>🔴 **取不到就说取不到**：没有事实时是 {@code NO_DATA} + {@code null}，**不是 0**。
   */
  private ReportingTargetModels.TargetBaselineView baseline(
      UUID tenantId,
      String scopeType,
      String ownerId,
      String metricCode,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd) {
    var from = periodStart.minusYears(1);
    var to = periodEnd.minusYears(1);
    if (ReportingTargetMetricCatalog.SCOPE_SHOP.equals(scopeType)) {
      var response = comparison.comparison(tenantId, from, to, null, null);
      for (var row : response.rows()) {
        if (!ownerId.equals(row.shopCode())) continue;
        for (var metric : row.metrics()) {
          if (!metricCode.equals(metric.metricCode())) continue;
          if (!currency.equals(metric.currency())) continue;
          if (metric.value() == null) break;
          return new ReportingTargetModels.TargetBaselineView(
              "AVAILABLE",
              metric.value(),
              currency,
              from,
              to,
              "OWNER",
              "基线 = 该店铺自己在 " + from + " ~ " + to + " 的实际值（按已登记口径求值，本模块不重算）。");
        }
        return new ReportingTargetModels.TargetBaselineView(
            "NO_DATA",
            null,
            null,
            from,
            to,
            "OWNER",
            "该店铺在这个基线窗口（去年同期）里没有这个指标的事实 —— **不是基线为 0**。");
      }
      return new ReportingTargetModels.TargetBaselineView(
          "NO_DATA",
          null,
          null,
          from,
          to,
          "OWNER",
          "该店铺还没有投影任何事实 —— 13.1.1.2 多店对比的店铺维度投影跑过之后才有基线。");
    }
    var response = overview.overview(tenantId, from, to, "COMPANY", null, null, null);
    for (var card : response.cards()) {
      if (!metricCode.equals(card.metricCode())) continue;
      for (var value : card.values()) {
        if (!currency.equals(value.currency())) continue;
        if (value.value() == null) continue;
        return new ReportingTargetModels.TargetBaselineView(
            "AVAILABLE",
            value.value(),
            currency,
            from,
            to,
            "TENANT",
            "基线 = 本租户在 " + from + " ~ " + to + " 的实际值（**公司维度**）—— "
                + "部门 / 人员 / 产品维度的历史事实**尚未投影到 reporting**"
                + "（上游只投影到公司 / 法人 / 店铺维度），"
                + "所以这里给的是**租户级参考值**，不是该归属对象自己的历史。");
      }
    }
    return new ReportingTargetModels.TargetBaselineView(
        "NO_DATA",
        null,
        null,
        from,
        to,
        "TENANT",
        "本租户在这个基线窗口（去年同期）里还没有这个指标的事实 —— **不是基线为 0**。");
  }

  // ==========================================================================
  // 内部：校验与工具
  // ==========================================================================

  /** 归属对象类型（受控词表）。 */
  private String requireScope(String scopeType) {
    var scope = scopeType == null ? "" : scopeType.trim().toUpperCase(java.util.Locale.ROOT);
    if (!ReportingTargetMetricCatalog.SCOPE_TYPES.contains(scope)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "归属对象类型只能是 "
              + String.join(" / ", ReportingTargetMetricCatalog.SCOPE_TYPES)
              + "，收到："
              + scopeType);
    }
    return scope;
  }

  /** 金额刻度归一（与服务端 numeric(24,4) 对齐；分解合计与目标值因此必然一致）。 */
  private BigDecimal normalize(BigDecimal value) {
    if (value == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标值不能为空。");
    }
    return value.setScale(ReportingTargetDecomposition.VALUE_SCALE, RoundingMode.HALF_UP);
  }

  /** 分解（计算口径不成立时翻成 400）。 */
  private List<ReportingTargetDecomposition.Bucket> decompose(
      LocalDate periodStart,
      LocalDate periodEnd,
      String granularity,
      String method,
      BigDecimal total,
      List<BigDecimal> weights) {
    try {
      return ReportingTargetDecomposition.decompose(
          periodStart, periodEnd, granularity, method, total, weights);
    } catch (ReportingTargetDecomposition.InvalidDecomposition e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** 归属对象必须真实存在（**不在清单里就是不存在**，不凭客户端自称）。 */
  private ReportingTargetModels.TargetOwnerView requireOwner(
      UUID tenantId, String scopeType, String ownerId, String bearer) {
    var id = ownerId == null ? "" : ownerId.trim();
    if (id.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "归属对象不能为空。");
    }
    for (var owner : owners(tenantId, scopeType, bearer).owners()) {
      if (owner.ownerId().equals(id)) return owner;
    }
    throw new ResponseStatusException(
        HttpStatus.BAD_REQUEST,
        "归属对象不存在或不在你的可见范围（" + scopeType + " / " + id + "）—— "
            + "目标必须挂在真实存在的组织对象上。");
  }

  /** 同一归属对象 + 指标 + 币种 + 周期只能有一个目标。 */
  private void assertUnique(
      UUID tenantId,
      String scopeType,
      String ownerId,
      String metricCode,
      String currency,
      LocalDate periodStart,
      LocalDate periodEnd,
      UUID exclude) {
    var text =
        "select count(*) from reporting_target where tenant_id = ? and scope_type = ? "
            + "and owner_id = ? and metric_code = ? and currency = ? "
            + "and period_start = ? and period_end = ?";
    var params =
        new ArrayList<Object>(
            List.of(
                tenantId, scopeType, ownerId, metricCode, currency, periodStart, periodEnd));
    if (exclude != null) {
      text += " and id <> ?";
      params.add(exclude);
    }
    var count = sql.fetchOne(text, params.toArray());
    if (count != null && count.get(0, Long.class) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "这个归属对象在这个指标、这个币种、这个周期上已经有一个目标了 —— "
              + "请改那条目标，不要再立一条（否则达成追踪会分不清哪条才是真目标）。");
    }
  }

  /** 目标行（取不到就 404）。 */
  private TargetRow loadTarget(UUID tenantId, UUID id) {
    var row =
        sql.fetchOne(
            "select id, metric_code, scope_type, owner_id, owner_code, owner_name, currency, "
                + "target_value, period_start, period_end, granularity, decomposition_method, status "
                + "from reporting_target where tenant_id = ? and id = ?",
            tenantId,
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "目标不存在或不在你的可见范围：" + id);
    }
    return new TargetRow(
        row.get(0, UUID.class),
        row.get(1, String.class),
        row.get(2, String.class),
        row.get(3, String.class),
        row.get(4, String.class),
        row.get(5, String.class),
        row.get(6, String.class),
        row.get(7, BigDecimal.class),
        row.get(8, LocalDate.class),
        row.get(9, LocalDate.class),
        row.get(10, String.class),
        row.get(11, String.class),
        row.get(12, String.class));
  }

  /** 目标行的内部形状。 */
  private record TargetRow(
      UUID id,
      String metricCode,
      String scopeType,
      String ownerId,
      String ownerCode,
      String ownerName,
      String currency,
      BigDecimal targetValue,
      LocalDate periodStart,
      LocalDate periodEnd,
      String granularity,
      String decompositionMethod,
      String status) {}

  /** 拼 limit / offset 参数。 */
  private static Object[] append(List<Object> base, Object... extra) {
    var out = new ArrayList<Object>(base.size() + extra.length);
    out.addAll(base);
    for (var item : extra) {
      out.add(item);
    }
    return out.toArray();
  }

  // ==========================================================================
  // 能力声明的静态事实
  // ==========================================================================

  /** 本服务**真的读过**的上游模块。 */
  private static final List<String> UPSTREAM_MODULES =
      List.of(
          "17 组织（identity：GET /identity/departments 部门清单、GET /identity/members 人员清单）",
          "05 商品主数据（catalog：GET /catalog/products 产品（SPU）清单）",
          "02 渠道与店铺（店铺清单取自本服务自己的 reporting_store_profile 投影，13.1.1.2 多店对比落的那张表）",
          "13.1.1.1 经营总览（租户级口径表与历史事实：口径唯一，派生型按分子分母递归求值）",
          "13.1.1.2 多店对比（店铺级口径表与店铺维度历史事实）");

  /** **确实拿不到**的部分（每条都写清原因，不写「未接入」这种空话）。 */
  private static final List<String> NOT_DELIVERED =
      List.of(
          "**广告 ROI 在公司 / 部门 / 人员 / 产品维度没有登记口径码**：只有店铺维度的 "
              + "STORE_AD_ROAS（广告销售额 ÷ 广告花费）。本模块**不拿 AD_SPEND（广告花费）冒充 ROI** —— "
              + "那是换一个指标冒充另一个指标。归属模块：14.3.1.1 活动报表（channel）。",
          "**库存周转任何维度都没有登记口径码**：口径表里只有 INVENTORY_VALUE（库存总额，存量口径）。"
              + "库存周转需要「销量 ÷ 平均库存成本」这类分母关系，**没登记就没有**。"
              + "本模块**不拿库存总额冒充周转率**。归属模块：7.1.1.1 库存总览（warehouse）/ "
              + "13.5.1.1 库存分析（reporting）。",
          "**店铺维度的毛利额 / 毛利率拿不到**：上游的成本分录（12.4.1.2）与利润表（12.5.2.2）"
              + "都**没有店铺维度**，只有法人 / 公司维度 ⇒ 店铺目标只能挂成交额 / 订单量 / 广告类指标。",
          "**部门 / 人员 / 产品维度的历史基线取不到「该归属对象自己」的数**：上游事实目前只投影到"
              + "公司 / 法人 / 店铺维度 ⇒ 基线给的是**租户级参考值**（响应里 scope = TENANT），"
              + "并写明它不是该归属对象自己的历史。归属模块：13.1.1.1 经营总览（需补齐这三个维度的投影）。",
          "**绩效核算未对接**：17 卷整卷未开发（包含绩效核算），本模块只产出「目标 + 分解计划」，"
              + "不计算绩效、不改人事 —— 下游接上时读 reporting_target 的 scope_type / owner_id / currency 即可。",
          "**产品维度的实际值尚未投影**：产品目标可以设定，但 13.1.2.2 达成追踪要拿产品维度的实际值时，"
              + "当前投影里没有这个维度的事实 —— 那一格会如实报「不可得」。");

  /** 口径纪律说明。 */
  private static final List<String> NOTES =
      List.of(
          "口径**单点定义**在 13（reporting_metric_definition / reporting_store_metric_definition）："
              + "本模块只**引用**已登记的口径码，**不重算口径** —— SQL 与 Java 里没有一处硬编码公式。",
          "**销售额在店铺维度是三个并列口径**（平台侧 / 还原口径 / 口径差异额）："
              + "取其一改名叫「销售额」就是抹掉口径差异 ⇒ 三个都留着，设定时自己选。",
          "**分解项合计精确等于目标值**：均分时差额归最后一个分解项；按权重时用最大余额法补齐。"
              + "数据层还有一道**延迟约束触发器**兜底（提交时合计对不上 ⇒ 整个事务失败）。",
          "**余数归属写在响应里**（remainderNote）：宁可让最后一项承担差额，也不让合计对不上。",
          "**多币种绝不相加**：目标值带币种，分解项带币种，合计按币种分列。",
          "**归属对象是快照**：组织改名后历史目标仍认得出是谁 —— 快照是「当时的写法」，不是外键。",
          "**「没有基线」与「基线为 0」是两件事**：基线取不到时给 null + 原因，绝不显示成 0。");
}
