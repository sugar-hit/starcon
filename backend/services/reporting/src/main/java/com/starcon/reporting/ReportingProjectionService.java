package com.starcon.reporting;

import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

/**
 * 13.1.1.1 的**投影器**（事件 → 投影事实）。
 *
 * <p>它是本服务**唯一**写投影表的地方。上游事件（推）与上游拉取结果（拉）都被归一化成
 * {@link ReportingProjectionModels.ProjectionEvent}，然后交给这里 —— 所以幂等、口径、
 * 重建这三条纪律**只有一处实现**。
 *
 * <p>⚠️ **本类不写业务事实**：它只往 {@code reporting_metric_fact} /
 * {@code reporting_drilldown_fact}（投影）{@code reporting_projection_event}（账本）/
 * {@code reporting_projection_cursor}（游标）里写。这四张表随时可以清空重建 ——
 * 业务事实仍然在 trade / finance / warehouse / channel / catalog 那边。
 *
 * <p><b>幂等的三层</b>：
 *
 * <ol>
 *   <li><b>事件层</b>：{@code reporting_projection_event} 的
 *       {@code UNIQUE(tenant_id, source_service, source_event_id)} ⇒ 同一事件重投只投影一次。
 *   <li><b>事实层</b>：事实行按「维度 + 指标 + 日 + 币种 + 来源服务 + 上游行」唯一 ⇒
 *       同一上游行改了内容，重新投影是**覆盖**而不是**追加**。
 *   <li><b>口径层</b>：事实行的指标码必须已在 {@code reporting_metric_definition} 登记
 *       （DB 触发器钉死）⇒ 事实不能自己发明指标。
 * </ol>
 */
@Service
public class ReportingProjectionService {

  /** 零 uuid：唯一索引里把 NULL 的维度归一成它（「公司级」行的维度为空）。 */
  private static final String ZERO_UUID = "00000000-0000-0000-0000-000000000000";

  /**
   * 事实 upsert：同一「维度 + 指标 + 日 + 币种 + 来源服务 + 上游行」只留一条，改内容即覆盖。
   *
   * <p>⚠️ **零 uuid 用 {@code %s} 填而不是拼文本块**：文本块的起始 {@code """} 后面必须
   * 换行，把 {@code """ + ZERO_UUID + """} 写成一行是**编译不过**的（文本块起始分隔符
   * 序列非法，缺少行终止符）。这里用 {@code .formatted(...)} 一次填两处。
   */
  private static final String FACT_UPSERT =
      ("""
      insert into reporting_metric_fact
        (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,
         value,timezone,source_service,source_reference,updated_at)
      values (?,?,?,?,?,?,?,?,?,?,?,?,now())
      on conflict (tenant_id, scope_type,
          coalesce(legal_entity_id, '%s'::uuid),
          coalesce(shop_id, '%s'::uuid),
          metric_code, bucket_date, currency, source_service, source_reference)
      do update set value = excluded.value,
                    timezone = excluded.timezone,
                    updated_at = now()
      """)
          .formatted(ZERO_UUID, ZERO_UUID);

  /** 构成明细 upsert：同一条构成明细只留一条（重投不会把金额加两遍）。 */
  private static final String DRILLDOWN_UPSERT =
      ("""
      insert into reporting_drilldown_fact
        (id,tenant_id,scope_type,legal_entity_id,shop_id,metric_code,bucket_date,currency,
         source_kind,reference_id,reference_label,quantity,value,source_service,source_reference,updated_at)
      values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,now())
      on conflict (tenant_id, scope_type,
          coalesce(legal_entity_id, '%s'::uuid),
          coalesce(shop_id, '%s'::uuid),
          metric_code, bucket_date, currency, source_kind, reference_id, source_service, source_reference)
      do update set value = excluded.value,
                    quantity = excluded.quantity,
                    reference_label = excluded.reference_label,
                    updated_at = now()
      """)
          .formatted(ZERO_UUID, ZERO_UUID);

  /**
   * 事件账本写入：幂等键冲突 ⇒ 什么都不做（调用方据此判定「重复事件」）。
   *
   * <p>⚠️ {@code fact_count} 在**插入时**就写好（事件体里已经知道有几条事实）——
   * 账本是 append-only（DB 层 REVOKE 了 UPDATE/DELETE），事后 UPDATE 会直接 403。
   */
  private static final String EVENT_INSERT =
      """
      insert into reporting_projection_event
        (id,tenant_id,source_service,source_event_id,event_type,aggregate_id,aggregate_version,
         occurred_at,applied_at,fact_count,note,epoch)
      values (?,?,?,?,?,?,?,?::timestamptz,now(),?,'',?)
      on conflict (tenant_id, source_service, source_event_id, epoch) do nothing
      returning id
      """;

  /** 读当前重建纪元（没有记录 ⇒ 0 = 首轮）。 */
  private static final String EPOCH_SELECT =
      """
      select epoch from reporting_projection_epoch where tenant_id = ? order by epoch desc limit 1
      """;

  /** 纪元推进（清空重建时 +1）。 */
  private static final String EPOCH_UPSERT =
      """
      insert into reporting_projection_epoch (id,tenant_id,epoch,updated_at)
      values (?,?,?,now())
      on conflict (tenant_id, epoch) do update set updated_at = now()
      """;

  /** 游标推进。 */
  private static final String CURSOR_UPSERT =
      """
      insert into reporting_projection_cursor
        (id,tenant_id,source_service,stream,last_source_event_id,last_occurred_at,
         processed_count,skipped_count,updated_at)
      values (?,?,?,?,?,?::timestamptz,?,?,now())
      on conflict (tenant_id, source_service, stream) do update
        set last_source_event_id = excluded.last_source_event_id,
            last_occurred_at = excluded.last_occurred_at,
            processed_count = reporting_projection_cursor.processed_count + excluded.processed_count,
            skipped_count = reporting_projection_cursor.skipped_count + excluded.skipped_count,
            updated_at = now()
      """;

  private final DSLContext sql;
  private final TenantTransactions tenants;

  public ReportingProjectionService(DSLContext sql, TenantTransactions tenants) {
    this.sql = sql;
    this.tenants = tenants;
  }

  // ==========================================================================
  // 口径播种
  // ==========================================================================

  /**
   * 按标准口径播种该租户的指标定义（**幂等**：已存在的不动）。
   *
   * <p>⚠️ 播种只写**默认口径**，不写任何数据。租户改了口径（改了单位、改了分子分母），
   * 这里**不会**覆盖它 —— 口径是租户自己的。
   */
  public void ensureMetricDefinitions(UUID tenantId) {
    tenants.run(
        tenantId,
        () -> {
          for (var definition : ReportingMetricCatalog.STANDARD) {
            sql.execute(
                """
                insert into reporting_metric_definition
                  (id,tenant_id,metric_code,display_name,unit,aggregation,numerator_metric_code,
                   denominator_metric_code,currency_aware,description,owner_module,enabled,created_at,updated_at)
                values (?,?,?,?,?,?,?,?,?,?,?,true,now(),now())
                on conflict (tenant_id, metric_code) do nothing
                """,
                Ids.next(),
                tenantId,
                definition.metricCode(),
                definition.displayName(),
                definition.unit().name(),
                definition.aggregation().name(),
                definition.numeratorMetricCode(),
                definition.denominatorMetricCode(),
                definition.currencyAware(),
                definition.description(),
                definition.ownerModule());
          }
          return null;
        });
  }

  // ==========================================================================
  // 投影
  // ==========================================================================

  /**
   * 投影一批事件。
   *
   * <p>⚠️ **整批在一个事务里**：一批里第 3 条失败 ⇒ 前面两条也不落库。半批投影出来的报表
   * 比不投影更危险（用户以为看到的是全量）。
   */
  public ReportingProjectionModels.ProjectionBatchResult applyBatch(
      UUID tenantId, List<ReportingProjectionModels.ProjectionEvent> events) {
    ensureMetricDefinitions(tenantId);
    return tenants.run(
        tenantId,
        () -> {
          var epoch = currentEpoch();
          var results = new java.util.ArrayList<ReportingProjectionModels.ProjectionResult>();
          int applied = 0;
          int skipped = 0;
          int factCount = 0;
          int drilldownCount = 0;
          for (var event : events) {
            var result = applyOne(event, epoch);
            results.add(result);
            if ("APPLIED".equals(result.outcome())) {
              applied++;
              factCount += result.factCount();
              drilldownCount += result.drilldownCount();
            } else {
              skipped++;
            }
          }
          return new ReportingProjectionModels.ProjectionBatchResult(
              results,
              applied,
              skipped,
              factCount,
              drilldownCount,
              "同一事件重复投递会被幂等跳过（账本唯一键），投影不会翻倍。");
        });
  }

  /** 当前重建纪元（没有记录 ⇒ 0 = 首轮）。 */
  private int currentEpoch() {
    var row = sql.fetchOne(EPOCH_SELECT, currentTenant());
    return row == null ? 0 : row.get(0, Integer.class);
  }

  /** 投影一个事件（**必须在** {@code tenants.run} 的租户事务里）。 */
  private ReportingProjectionModels.ProjectionResult applyOne(
      ReportingProjectionModels.ProjectionEvent event, int epoch) {
    validate(event);
    var facts = event.facts() == null ? List.<ReportingProjectionModels.ProjectionFact>of() : event.facts();
    var drilldowns =
        event.drilldowns() == null
            ? List.<ReportingProjectionModels.ProjectionDrilldown>of()
            : event.drilldowns();
    var ledger =
        sql.fetch(
            EVENT_INSERT,
            Ids.next(),
            currentTenant(),
            event.sourceService(),
            event.sourceEventId(),
            event.eventType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.occurredAt(),
            facts.size(),
            epoch);
    if (ledger.isEmpty()) {
      // **重复事件**：唯一键冲突，什么都没写。
      bumpCursor(event.sourceService(), streamOf(event), event, 0, 1);
      return new ReportingProjectionModels.ProjectionResult(
          event.sourceService(),
          event.sourceEventId(),
          event.eventType(),
          "SKIPPED_DUPLICATE",
          0,
          0,
          OffsetDateTime.now(),
          "同一上游事件已投影过，本次跳过（事实不会被重复累加）。");
    }

    int written = 0;
    for (var fact : facts) {
      sql.execute(
          FACT_UPSERT,
          Ids.next(),
          currentTenant(),
          fact.scopeType(),
          fact.legalEntityId(),
          fact.shopId(),
          fact.metricCode(),
          fact.bucketDate(),
          fact.currency(),
          fact.value() == null ? BigDecimal.ZERO : fact.value(),
          event.timezone(),
          event.sourceService(),
          fact.sourceReference() == null ? "" : fact.sourceReference());
      written++;
    }
    int drilldownCount = 0;
    for (var drill : drilldowns) {
      sql.execute(
          DRILLDOWN_UPSERT,
          Ids.next(),
          currentTenant(),
          drill.scopeType(),
          drill.legalEntityId(),
          drill.shopId(),
          drill.metricCode(),
          drill.bucketDate(),
          drill.currency(),
          drill.sourceKind(),
          drill.referenceId(),
          drill.referenceLabel() == null ? "" : drill.referenceLabel(),
          drill.quantity() == null ? BigDecimal.ZERO : drill.quantity(),
          drill.value() == null ? BigDecimal.ZERO : drill.value(),
          event.sourceService(),
          drill.sourceReference() == null ? "" : drill.sourceReference());
      drilldownCount++;
    }
    bumpCursor(event.sourceService(), streamOf(event), event, 1, 0);
    return new ReportingProjectionModels.ProjectionResult(
        event.sourceService(),
        event.sourceEventId(),
        event.eventType(),
        "APPLIED",
        written,
        drilldownCount,
        OffsetDateTime.now(),
        "");
  }

  /**
   * 当前事务的租户。
   *
   * <p>⚠️ 取的是 **DB 里绑定的那个**租户（{@code app.current_tenant}），不是参数里传的 ——
   * 这样「行落在谁的租户名下」只有一个出处。
   */
  private UUID currentTenant() {
    var row = sql.fetchOne("select nullif(current_setting('app.current_tenant', true),'')::uuid");
    return row == null ? null : row.get(0, UUID.class);
  }

  /** 游标推进。 */
  private void bumpCursor(
      String sourceService,
      String stream,
      ReportingProjectionModels.ProjectionEvent event,
      int processed,
      int skipped) {
    sql.execute(
        CURSOR_UPSERT,
        Ids.next(),
        currentTenant(),
        sourceService,
        stream,
        event.sourceEventId(),
        event.occurredAt(),
        processed,
        skipped);
  }

  /** 流名：同一个上游服务可以有多条流（如 trade 的订单流 / 售后流）。 */
  private String streamOf(ReportingProjectionModels.ProjectionEvent event) {
    return event.sourceService() + ":" + event.eventType();
  }

  /** 入参校验：错的东西要**当场说清楚**，不能落库之后才发现。 */
  private void validate(ReportingProjectionModels.ProjectionEvent event) {
    if (event.sourceService() == null || event.sourceService().isBlank())
      throw new IllegalArgumentException("sourceService 不能为空");
    if (event.sourceEventId() == null || event.sourceEventId().isBlank())
      throw new IllegalArgumentException("sourceEventId 不能为空（它是幂等键的另一半）");
    if (event.eventType() == null || event.eventType().isBlank())
      throw new IllegalArgumentException("eventType 不能为空");
    if (!ReportingProjectionModels.ReportingEventTypes.ALL.contains(event.eventType()))
      throw new IllegalArgumentException(
          "未知的事件类型 "
              + event.eventType()
              + "，可用："
              + String.join("、", ReportingProjectionModels.ReportingEventTypes.ALL));
    if (event.occurredAt() == null) throw new IllegalArgumentException("occurredAt 不能为空");
    if (event.timezone() == null || event.timezone().isBlank())
      throw new IllegalArgumentException("timezone 不能为空（跨时区汇总必须显示所用时区）");
    for (var fact : event.facts() == null ? List.<ReportingProjectionModels.ProjectionFact>of() : event.facts()) {
      if (fact.metricCode() == null || fact.metricCode().isBlank())
        throw new IllegalArgumentException("metricCode 不能为空");
      if (fact.bucketDate() == null) throw new IllegalArgumentException("bucketDate 不能为空");
      if (fact.currency() == null || !fact.currency().matches("^[A-Z]{3}$"))
        throw new IllegalArgumentException("currency 必须是 ISO 4217 alpha-3 大写字母");
      if (!List.of("COMPANY", "LEGAL_ENTITY", "SHOP").contains(fact.scopeType()))
        throw new IllegalArgumentException("scopeType 必须是 COMPANY / LEGAL_ENTITY / SHOP");
    }
  }

  // ==========================================================================
  // 汇率快照
  // ==========================================================================

  /**
   * 记一条汇率快照（**append-only**）。
   *
   * <p>⚠️ **本服务不拥有汇率**：汇率是外部事实（catalog 的汇率档案），抄一份就会漂移。
   * 这里留的只是「某次测算用的那一档 + 它的生效日」，事后可复盘。
   *
   * <p>⚠️ 快照表在 DB 层被 {@code REVOKE UPDATE,DELETE} ⇒ 只能追加。同一币种对
   * 反复刷新会追加多条，查询侧按 {@code as_of} 取最近的那条。
   *
   * @param currency 原币
   * @param baseCurrency 报价币（目标币）
   * @param rate 汇率（1 currency = rate baseCurrency）
   * @param asOf 生效时点（**必须带**：今天的汇率变了不能让上月的报表变）
   */
  public void recordCurrencyRate(
      UUID tenantId,
      String currency,
      String baseCurrency,
      BigDecimal rate,
      OffsetDateTime asOf,
      String sourceService) {
    if (currency == null || baseCurrency == null || rate == null || asOf == null) return;
    if (currency.equals(baseCurrency)) return;
    if (rate.signum() <= 0) return;
    tenants.run(
        tenantId,
        () -> {
          sql.execute(
              """
              insert into reporting_currency_rate_snapshot
                (id,tenant_id,currency,base_currency,rate,as_of,source_service,created_at)
              values (?,?,?,?,?,?::timestamptz,?,now())
              on conflict (tenant_id, currency, base_currency, as_of) do nothing
              """,
              Ids.next(),
              tenantId,
              currency,
              baseCurrency,
              rate,
              asOf,
              sourceService == null ? "" : sourceService);
          return null;
        });
  }

  // ==========================================================================
  // 重建
  // ==========================================================================

  /**
   * 清空投影（事实 + 构成明细 + 游标），并把**重建纪元 +1**。
   *
   * <p>⚠️ **只清投影，不碰业务事实**：业务事实在上游服务那边，本服务清空的是自己的读模型。
   * 清空后重投同一批 envelope 即可回到同一状态 —— 这就是「报表是投影，可从事件重建」的可操作形态。
   *
   * <p>⚠️ **账本行不删**：事件账本是举证凭据（DB 层 REVOKE 了 UPDATE/DELETE，删不动）。
   * 所以「这一批事件已经投过」这件事靠**换纪元**抹掉 —— 同一个 {@code source_event_id}
   * 在新纪元里是一条全新的账本行，于是重投不会被幂等键挡掉。
   *
   * @return 被清掉的事实条数
   */
  public int clearProjection(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          // ⚠️ **必须用 execute 而不是 fetch**：DELETE 没有结果集，
          // fetch(...).size() 恒为 0 —— 「清掉了多少条」会永远报 0。
          var facts =
              sql.execute("delete from reporting_metric_fact where tenant_id = ?", tenantId);
          sql.execute("delete from reporting_drilldown_fact where tenant_id = ?", tenantId);
          sql.execute("delete from reporting_projection_cursor where tenant_id = ?", tenantId);
          // 纪元 +1：旧账本留着（举证凭据），但不再挡住新一轮的重投。
          var next = currentEpoch() + 1;
          sql.execute(EPOCH_UPSERT, Ids.next(), currentTenant(), next);
          return facts;
        });
  }
}
