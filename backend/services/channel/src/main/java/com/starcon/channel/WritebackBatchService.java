package com.starcon.channel;

import static com.starcon.channel.WritebackBatchModels.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 2.5.2.1 常用回写操作。
 *
 * <p>本模块只做一件事：把「用户勾选的一批目标 + 一组参数」翻译成 N 条独立的平台回写任务，然后**如实**回报每一条的成败。
 *
 * <p>四条不可让步的纪律：
 *
 * <ol>
 *   <li><b>批量 ≠ 事务</b>：逐条独立处理，一条被拦下不影响其余。代码里没有任何一处把 N 条包进同一个 try —— 跨服务没有全局事务，写出来也只是自欺。
 *   <li><b>每条任务带业务幂等键</b>：键由「批次键 + 目标 + 参数摘要」导出，不接受调用方自拟，所以「同一个目标的同一次改价」重发多少次，平台上都只有一次动作。
 *   <li><b>没发出去的��不进任务清单</b>：被拦下的那条不生成 platform_operation（它压根没发往平台），但它**照样**出现在批次结果里并带原因 —— 漏报比失败更糟。
 *   <li><b>批次状态现查不落库</b>：outcome 每次从 platform_operation 的真实状态聚合。落一个 status 字段就等于多一个会与真实任务撒谎的地方。
 * </ol>
 */
@Service
public class WritebackBatchService {
  /** 一次批量最多处理多少条。上限保护：一次误操作点了「全选」不该生成十万条任务。 */
  private static final int MAX_BATCH_TARGETS = 500;

  /**
   * 序列化用的 mapper。
   *
   * <p>两处配置都不是可有可无的：
   *
   * <ul>
   *   <li>{@code JavaTimeModule} —— 参数里有 OffsetDateTime（发货时刻），不注册就抛「请求内容无法序列化」。
   *   <li>{@code ORDER_MAP_ENTRIES_BY_KEYS} —— {@code perTarget} 是 Map，遍历顺序不保证稳定；键序一变，摘要就变，同一个请求两次提交就会算出两个幂等键，幂等直接失效。
   * </ul>
   */
  private static final ObjectMapper JSON =
      JsonMapper.builder()
          .addModule(new JavaTimeModule())
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
          .build();

  /**
   * 逐条结果的聚合口径 —— 六个表达式是**唯一**的一份，下面 {@link #ITEM_AGGREGATE} 与 {@link #outcomeCase()} 都由它们拼出来。
   *
   * <p>outcome 的判定规则在 SQL 侧（{@link #outcomeCase()}）与 Java 侧（{@link #outcomeOf}）各有一份，形状相同、**改一处必须同时改另一处**，集成测试里有一条断言专门钉住两者不跑偏（按 outcome 过滤列表必须与详情读出的 outcome 一致）。
   */
  private static final String AGG_TOTAL = "count(i.id)";
  private static final String AGG_REJECTED = "count(*) filter (where i.item_result='REJECTED')";
  private static final String AGG_SUCCEEDED =
      "count(*) filter (where i.item_result='QUEUED' and o.status='SUCCEEDED')";
  private static final String AGG_FAILED =
      "count(*) filter (where i.item_result='QUEUED' and o.status='FAILED')";
  private static final String AGG_UNKNOWN =
      "count(*) filter (where i.item_result='QUEUED' and o.status='UNKNOWN')";
  private static final String AGG_PENDING =
      "count(*) filter (where i.item_result='QUEUED'"
          + " and coalesce(o.status,'PENDING') in ('PENDING','SENDING'))";

  private static final String ITEM_AGGREGATE =
      AGG_TOTAL
          + "::bigint item_total,"
          + AGG_REJECTED
          + "::bigint item_rejected,"
          + AGG_SUCCEEDED
          + "::bigint item_succeeded,"
          + AGG_FAILED
          + "::bigint item_failed,"
          + AGG_UNKNOWN
          + "::bigint item_unknown,"
          + AGG_PENDING
          + "::bigint item_pending";

  /**
   * 批次结局的 SQL 判定。与 {@link #outcomeOf} 同一套规则。
   *
   * <p>UNKNOWN **不算已定局**：结果未知时平台可能已经执行，把它并进「成功」会报出一句谎，把它并进「失败」会诱导运营去重试、造成重复扣减（2.5.1.2 整节讲的就是这件事）。所以只要还有 UNKNOWN 没核验，批次就停在进行中。
   */
  private static String outcomeCase() {
    return " case when ("
        + AGG_TOTAL
        + ")=0 or ("
        + AGG_REJECTED
        + "+"
        + AGG_SUCCEEDED
        + "+"
        + AGG_FAILED
        + ")<"
        + AGG_TOTAL
        + " then 'IN_PROGRESS' when ("
        + AGG_FAILED
        + ")=0 and ("
        + AGG_REJECTED
        + ")=0 then 'SUCCEEDED' when ("
        + AGG_SUCCEEDED
        + ")=0 then 'FAILED' else 'PARTIAL' end";
  }

  private static final String BATCH_JOINS =
      " from writeback_batch b"
          + " left join writeback_batch_item i on i.tenant_id=b.tenant_id and i.batch_id=b.id"
          + " left join platform_operation o on o.tenant_id=i.tenant_id and o.id=i.operation_id";

  private static final String BATCH_COLUMNS =
      " b.id,b.batch_no,b.kind,b.shop_id,b.idempotency_key,b.selected_count,b.rejected_count,"
          + " b.reason,b.filter_snapshot,b.param_snapshot,b.created_at";

  private final DSLContext sql;

  public WritebackBatchService(DSLContext sql) {
    this.sql = sql;
  }

  // ==================================================================
  // 可回写目标登记簿
  // ==================================================================

  /**
   * 登记 / 更新可回写目标。
   *
   * <p>同一（店铺, 类型, 平台键）重复登记是**更新**而不是造第二条：上游重推同一条事件、运营重复提交，都不该让候选池凭空多出一个目标。
   */
  public UUID upsertTarget(AccessService.Access actor, WritebackTargetInput input) {
    String currency = input.currency() == null ? null : input.currency().toUpperCase(Locale.ROOT);
    if (input.currentPrice() != null && currency == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "登记价格必须同时给出三位大写币种");
    scaleCheck("库存数量", input.currentStock(), 3);
    scaleCheck("价格", input.currentPrice(), 4);

    Record row =
        sql.fetchOne(
            "insert into writeback_target(id,tenant_id,shop_id,target_type,external_ref,listing_ref,"
                + " sku_code,current_stock,current_price,currency,source_ref)"
                + " values (?,?,?,?,?,?,?,?,?,?,?)"
                + " on conflict (tenant_id,shop_id,target_type,external_ref) do update set"
                + " listing_ref=excluded.listing_ref,sku_code=excluded.sku_code,"
                + " current_stock=excluded.current_stock,current_price=excluded.current_price,"
                + " currency=excluded.currency,source_ref=excluded.source_ref,"
                + " version=writeback_target.version+1,updated_at=now()"
                + " returning id",
            Ids.next(),
            actor.tenantId(),
            input.shopId(),
            input.targetType().name(),
            input.externalRef().trim(),
            trim(input.listingRef()),
            trim(input.skuCode()),
            input.currentStock(),
            input.currentPrice(),
            currency,
            trim(input.sourceRef()));
    return row == null ? null : row.get("id", UUID.class);
  }

  /** 封禁 / 解封。封禁后该目标仍留在候选池里（要看得见「它被封了」），但批量时会被逐条拦下。 */
  public void setTargetStatus(
      AccessService.Access actor, UUID id, TargetStatus status, String reason) {
    // 先校验再写：DB 的 CHECK 会以 500 顶回来，那种报错对运营毫无指导意义。
    if (status == TargetStatus.BLOCKED && trim(reason).isEmpty())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "封禁必须写明原因");
    int updated =
        sql.execute(
            "update writeback_target set status=?,blocked_reason=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and id=?",
            status.name(),
            status == TargetStatus.BLOCKED ? trim(reason) : "",
            actor.tenantId(),
            id);
    if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "可回写目标不存在");
  }

  /** 候选池读：真分页 + 真筛选 + 真聚合。 */
  public WritebackTargetPage targets(
      UUID tenantId, UUID shopId, String targetType, String keyword, String status, int page,
      int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 50 : Math.min(size, 200);

    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    StringBuilder where = new StringBuilder(" where t.tenant_id=?");
    if (shopId != null) {
      where.append(" and t.shop_id=?");
      binds.add(shopId);
    }
    if (!blank(targetType)) {
      where.append(" and t.target_type=?");
      binds.add(targetType.trim());
    }
    if (!blank(status)) {
      where.append(" and t.status=?");
      binds.add(status.trim());
    }
    if (!blank(keyword)) {
      where.append(" and (lower(t.external_ref) like lower(?)||'%'"
          + " or lower(t.sku_code) like '%'||lower(?)||'%')");
      binds.add(keyword.trim());
      binds.add(keyword.trim());
    }

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add(p * s);
    var rows =
        sql.fetch(
            "select t.id,t.shop_id,s.name shop_name,p.code platform_code,t.target_type,t.external_ref,"
                + " t.listing_ref,t.sku_code,t.current_stock,t.current_price,t.currency,t.status,"
                + " t.blocked_reason,t.created_at,t.updated_at"
                + " from writeback_target t"
                + " join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id"
                + " join platform p on p.id=s.platform_id"
                + where
                + " order by t.shop_id,t.external_ref limit ? offset ?",
            pageBinds.toArray());

    long total =
        sql.fetchOne(
                "select count(*) from writeback_target t"
                    + " join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id"
                    + where,
                binds.toArray())
            .get(0, Long.class);

    return new WritebackTargetPage(
        rows.stream().map(WritebackBatchService::mapTarget).toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        targetSummary(tenantId, where.toString(), binds));
  }

  /** 汇总独立于分页：拉全量在 Java 里数会让「这一屏」与「实际总量」不一致。 */
  private WritebackTargetSummary targetSummary(UUID tenantId, String where, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*)::bigint total,"
                + " count(*) filter (where t.status='ACTIVE')::bigint active_count,"
                + " count(*) filter (where t.status='BLOCKED')::bigint blocked_count,"
                + " count(*) filter (where t.target_type='LISTING')::bigint listing_count,"
                + " count(*) filter (where t.target_type='SHIPMENT')::bigint shipment_count,"
                + " count(distinct t.shop_id)::bigint shops"
                + " from writeback_target t"
                + " join shop s on s.tenant_id=t.tenant_id and s.id=t.shop_id"
                + where,
            binds.toArray());
    return new WritebackTargetSummary(
        r.get("total", Long.class),
        r.get("active_count", Long.class),
        r.get("blocked_count", Long.class),
        r.get("listing_count", Long.class),
        r.get("shipment_count", Long.class),
        r.get("shops", Long.class));
  }

  private static WritebackTargetView mapTarget(Record r) {
    return new WritebackTargetView(
        r.get("id", UUID.class),
        r.get("shop_id", UUID.class),
        r.get("shop_name", String.class),
        r.get("platform_code", String.class),
        r.get("target_type", String.class),
        r.get("external_ref", String.class),
        r.get("listing_ref", String.class),
        r.get("sku_code", String.class),
        r.get("current_stock", BigDecimal.class),
        r.get("current_price", BigDecimal.class),
        r.get("currency", String.class),
        r.get("status", String.class),
        r.get("blocked_reason", String.class),
        r.get("created_at", Instant.class),
        r.get("updated_at", Instant.class));
  }

  // ==================================================================
  // 批量执行
  // ==================================================================

  /**
   * 执行一次批量回写。
   *
   * <p>幂等键命中时直接返回既有批次 —— 重发不会产生第二批平台动作。
   */
  public WritebackBatchView execute(AccessService.Access actor, WritebackBatchInput input) {
    String idempotencyKey = blank(input.idempotencyKey()) ? derivedKey(input) : input.idempotencyKey().trim();
    BatchParameters parameters = orEmpty(input.parameters());

    Record existing =
        sql.fetchOne(
            "select id from writeback_batch where tenant_id=? and idempotency_key=?",
            actor.tenantId(),
            idempotencyKey);
    if (existing != null) return detail(actor, existing.get("id", UUID.class));

    List<Resolved> targets = resolve(actor, input);
    if (targets.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "筛选条件没有命中任何可回写目标，请先确认店铺与目标是否已登记");
    if (targets.size() > MAX_BATCH_TARGETS)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "一次最多处理 " + MAX_BATCH_TARGETS + " 条，请缩小筛选范围");

    UUID batchId = Ids.next();
    sql.execute(
        "insert into writeback_batch(id,tenant_id,batch_no,kind,shop_id,filter_snapshot,"
            + " param_snapshot,idempotency_key,selected_count,rejected_count,"
            + " created_by_membership_id,reason)"
            + " values (?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,0,0,?,?)",
        batchId,
        actor.tenantId(),
        nextBatchNo(),
        input.kind().name(),
        input.shopId(),
        writeJson(orEmpty(input.filter())),
        writeJson(parameters),
        idempotencyKey,
        actor.membershipId(),
        trim(input.reason()));

    int position = 0;
    int rejected = 0;
    for (Resolved target : targets) {
      position++;
      // 纪律一：逐条独立。一条出问题只让那一条被拒，其余照常 —— 没有任何回滚。
      String problem = processOne(actor, batchId, position, input.kind(), target, parameters, idempotencyKey);
      if (problem != null) rejected++;
    }

    sql.execute(
        "update writeback_batch set selected_count=?,rejected_count=? where tenant_id=? and id=?",
        position,
        rejected,
        actor.tenantId(),
        batchId);

    return detail(actor, batchId);
  }

  /**
   * 逐条处理。返回 null = 已排队；返回字符串 = 被拒的原因。
   *
   * <p>刻意**不抛异常**：抛了就得在调用处 try，而 try 里再包一层「整体回滚」的错觉就来了。这里用返回值表达单条成败，形状上就不给整体回滚留位置。
   */
  private String processOne(
      AccessService.Access actor,
      UUID batchId,
      int position,
      BatchKind kind,
      Resolved target,
      BatchParameters parameters,
      String batchKey) {
    // 目标不存在（勾选了一个已删的目标）：照样要有一行，否则「选了 5 条只回报 4 条」。
    if (target.id() == null)
      return rejectItem(
          actor,
          batchId,
          position,
          target,
          "目标不存在或不属于当前租户（勾选的 " + target.requestedId() + "）");

    List<String> problems = validate(kind, target, parameters);
    if (!problems.isEmpty()) return rejectItem(actor, batchId, position, target, String.join("；", problems));

    Map<String, Object> payload = merge(kind, target, parameters);
    // 业务幂等键由服务端导出：调用方自拟就能两次「不同键、同一个目标」，恰好绕开幂等。
    String taskKey = taskKey(batchKey, kind, target, payload);

    Record existing =
        sql.fetchOne(
            "select id from platform_operation where tenant_id=? and idempotency_key=?",
            actor.tenantId(),
            taskKey);
    UUID operationId;
    if (existing != null) {
      // 键命中 = 这次平台动作已经登记过：复用既有任务，不产生第二次。
      operationId = existing.get("id", UUID.class);
    } else {
      operationId = Ids.next();
      sql.execute(
          "insert into platform_operation(id,tenant_id,shop_id,marketplace_id,operation,"
              + " idempotency_key,payload) values (?,?,?,null,?,?,cast(? as jsonb))",
          operationId,
          actor.tenantId(),
          target.shopId(),
          operationOf(kind),
          taskKey,
          writeJson(payload));
    }

    OffsetDateTime shippedAt =
        payload.get("shippedAt") == null ? null : OffsetDateTime.parse(String.valueOf(payload.get("shippedAt")));
    // ⚠️ timestamptz 必须显式 ?::timestamptz 并绑 OffsetDateTime：
    // 用 Timestamp.valueOf(LocalDateTime) 会静默偏 −28800s，页面上看不出异常、平台侧却收货时间对不上。
    sql.execute(
        "insert into writeback_batch_item(id,tenant_id,batch_id,position,target_id,external_ref,"
            + " shop_id,target_type,item_result,operation_id,reason,shipped_at)"
            + " values (?,?,?,?,?,?,?,?,'QUEUED',?,'',?::timestamptz)",
        Ids.next(),
        actor.tenantId(),
        batchId,
        position,
        target.id(),
        target.externalRef(),
        target.shopId(),
        target.targetType(),
        operationId,
        shippedAt);
    return null;
  }

  /** 被拦下的那条：没发往平台 ⇒ 不生成 platform_operation，但**照样**回报原因。 */
  private String rejectItem(
      AccessService.Access actor, UUID batchId, int position, Resolved target, String reason) {
    // 目标不存在时 target_id 落 NULL：挂一个查无此行的 uuid 会被外键顶回来，
    // 而「这一条的目标没了」本身就是必须被回报的事实。
    sql.execute(
        "insert into writeback_batch_item(id,tenant_id,batch_id,position,target_id,external_ref,"
            + " shop_id,target_type,item_result,reason) values (?,?,?,?,?,?,?,?,'REJECTED',?)",
        Ids.next(),
        actor.tenantId(),
        batchId,
        position,
        target.id(),
        target.externalRef(),
        target.shopId(),
        target.targetType(),
        reason);
    return reason;
  }

  /** 试算：与 {@link #execute} 走**同一套**筛选与校验，只是不落任何一行。 */
  public WritebackBatchPreview preview(AccessService.Access actor, WritebackBatchInput input) {
    BatchParameters parameters = orEmpty(input.parameters());
    List<Resolved> targets = resolve(actor, input);
    List<WritebackPreviewIssue> issues = new ArrayList<>();
    int applicable = 0;
    for (Resolved target : targets) {
      // 目标不存在就只报「不存在」：再叠一串「只适用于 SHIPMENT 的目标」是噪声，
      // 而噪声会让人以为真正的原因被埋在中间。
      if (target.id() == null) {
        issues.add(
            new WritebackPreviewIssue(
                target.requestedId(),
                null,
                "TARGET_MISSING",
                "目标不存在或不属于当前租户（勾选的 " + target.requestedId() + "）"));
        continue;
      }
      List<String> problems = validate(input.kind(), target, parameters);
      if (problems.isEmpty()) applicable++;
      for (String problem : problems)
        issues.add(
            new WritebackPreviewIssue(
                target.id(), target.externalRef(), problemCode(problem), problem));
    }
    int blocked =
        (int) targets.stream().filter(t -> TargetStatus.BLOCKED.name().equals(t.status())).count();
    return new WritebackBatchPreview(
        input.kind(),
        targets.size(),
        applicable,
        blocked,
        issues.size(),
        issues,
        effectiveParameters(parameters));
  }

  // ==================================================================
  // 批次读
  // ==================================================================

  public WritebackBatchPage batches(
      UUID tenantId, String kind, UUID shopId, String outcome, int page, int size) {
    int p = Math.max(page, 0);
    int s = size <= 0 ? 20 : Math.min(size, 100);

    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    StringBuilder where = new StringBuilder(" where b.tenant_id=?");
    if (!blank(kind)) {
      where.append(" and b.kind=?");
      binds.add(kind.trim());
    }
    if (shopId != null) {
      // 跨店铺批次的 shop_id 可能为空，所以店铺条件同时看逐条归属：
      // 只看批次头会让「按店铺筛 2.5.2.1 的历史」漏掉跨店铺批次里的那一店铺。
      where.append(" and (b.shop_id=? or exists (select 1 from writeback_batch_item i"
          + " where i.tenant_id=b.tenant_id and i.batch_id=b.id and i.shop_id=?))");
      binds.add(shopId);
      binds.add(shopId);
    }

    // outcome 是**算出来的**，所以过滤只能在聚合之后做：先在派生表里数出条目并判定
    // outcome，再拿同一个 outcome 去筛 —— 分页才与筛选自洽（把判定挪到 WHERE 里就得
    // 把六个聚合各写两遍，改一处忘一处就会筛出与详情不一致的列表）。
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(outcome == null ? "" : outcome.trim());
    pageBinds.add(outcome == null ? "" : outcome.trim());
    pageBinds.add(s);
    pageBinds.add(p * s);

    var rows =
        sql.fetch(
            "select t.id,t.batch_no,t.kind,t.shop_id,t.idempotency_key,t.selected_count,"
                + " t.rejected_count,t.reason,t.filter_snapshot,t.param_snapshot,t.created_at,"
                + " t.item_total,t.item_rejected,t.item_succeeded,t.item_failed,t.item_unknown,"
                + " t.item_pending"
                + " from (select " + BATCH_COLUMNS + "," + ITEM_AGGREGATE + ","
                + outcomeCase()
                + " as outcome"
                + BATCH_JOINS
                + where
                + " group by b.id,b.batch_no,b.kind,b.shop_id,b.idempotency_key,b.selected_count,"
                + " b.rejected_count,b.reason,b.filter_snapshot,b.param_snapshot,b.created_at) t"
                + " where (? = '' or t.outcome = ?)"
                + " order by t.created_at desc,t.batch_no desc limit ? offset ?",
            pageBinds.toArray());
    long total = totalBatches(where.toString(), binds);
    return new WritebackBatchPage(
        rows.stream().map(r -> mapBatch(r, List.of())).toList(),
        p,
        s,
        total,
        (int) Math.ceil(total / (double) s),
        overallSummary(tenantId));
  }

  /** 批次详情：逐条结果 + 现查的真实平台任务状态。 */
  public WritebackBatchView detail(AccessService.Access actor, UUID batchId) {
    Record r =
        sql.fetchOne(
            "select " + BATCH_COLUMNS + "," + ITEM_AGGREGATE + BATCH_JOINS
                + " where b.tenant_id=? and b.id=? group by b.id,b.batch_no,b.kind,b.shop_id,"
                + " b.idempotency_key,b.selected_count,b.rejected_count,b.reason,"
                + " b.filter_snapshot,b.param_snapshot,b.created_at",
            actor.tenantId(),
            batchId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "批量回写批次不存在");
    return mapBatch(r, itemsOf(actor.tenantId(), batchId));
  }

  private long totalBatches(String where, List<Object> binds) {
    return sql.fetchOne("select count(*) from writeback_batch b" + where, binds.toArray())
        .get(0, Long.class);
  }

  /** 跨批次汇总：真聚合（不受筛选与分页影响），否则「这一屏」会与「实际总量」不一致。 */
  private WritebackBatchSummary overallSummary(UUID tenantId) {
    Record r =
        sql.fetchOne(
            "select count(distinct b.id)::bigint batches," + ITEM_AGGREGATE + BATCH_JOINS
                + " where b.tenant_id=?",
            tenantId);
    return summarize(
        r.get("item_total", Long.class),
        r.get("item_rejected", Long.class),
        r.get("item_succeeded", Long.class),
        r.get("item_failed", Long.class),
        r.get("item_unknown", Long.class),
        r.get("item_pending", Long.class));
  }

  /** 逐条结果。平台任务状态一律**现查**，不读快照 —— 快照会与真实任务撒谎。 */
  private List<WritebackItemView> itemsOf(UUID tenantId, UUID batchId) {
    return sql.fetch(
            "select i.position,i.target_id,i.shop_id,i.target_type,i.external_ref,i.reason,"
                + " i.operation_id,i.shipped_at,i.created_at,t.listing_ref,t.sku_code,"
                + " o.status operation_status,o.idempotency_key,o.payload"
                + " from writeback_batch_item i"
                + " left join writeback_target t on t.tenant_id=i.tenant_id and t.id=i.target_id"
                + " left join platform_operation o on o.tenant_id=i.tenant_id and o.id=i.operation_id"
                + " where i.tenant_id=? and i.batch_id=? order by i.position",
            tenantId,
            batchId)
        .stream()
        .map(WritebackBatchService::mapItem)
        .toList();
  }

  private static WritebackItemView mapItem(Record r) {
    JsonNode payload = parseQuietly(r.get("payload", String.class));
    return new WritebackItemView(
        r.get("position", Integer.class),
        r.get("target_id", UUID.class),
        r.get("shop_id", UUID.class),
        r.get("target_type", String.class),
        r.get("external_ref", String.class),
        r.get("listing_ref", String.class),
        r.get("sku_code", String.class),
        r.get("operation_id", UUID.class) == null ? "REJECTED" : "QUEUED",
        r.get("operation_id", UUID.class),
        r.get("operation_status", String.class),
        r.get("idempotency_key", String.class),
        r.get("reason", String.class),
        r.get("shipped_at", Instant.class),
        text(payload, "stock", BigDecimal.class),
        text(payload, "price", BigDecimal.class),
        text(payload, "currency", String.class),
        text(payload, "trackingNumber", String.class),
        text(payload, "carrierCode", String.class),
        r.get("created_at", Instant.class));
  }

  private static WritebackBatchView mapBatch(Record r, List<WritebackItemView> items) {
    return new WritebackBatchView(
        r.get("id", UUID.class),
        r.get("batch_no", String.class),
        BatchKind.valueOf(r.get("kind", String.class)),
        r.get("shop_id", UUID.class),
        r.get("idempotency_key", String.class),
        r.get("selected_count", Integer.class),
        r.get("rejected_count", Integer.class),
        r.get("reason", String.class),
        r.get("filter_snapshot", String.class),
        r.get("param_snapshot", String.class),
        summarize(
            r.get("item_total", Long.class),
            r.get("item_rejected", Long.class),
            r.get("item_succeeded", Long.class),
            r.get("item_failed", Long.class),
            r.get("item_unknown", Long.class),
            r.get("item_pending", Long.class)),
        items,
        r.get("created_at", Instant.class));
  }

  // ==================================================================
  // 聚合：部分成功是真的一个状态
  // ==================================================================

  private static WritebackBatchSummary summarize(
      long total, long rejected, long succeeded, long failed, long unknown, long pending) {
    return new WritebackBatchSummary(
        outcomeOf(total, rejected, succeeded, failed),
        (int) total,
        (int) (total - rejected),
        (int) rejected,
        (int) succeeded,
        (int) failed,
        (int) unknown,
        (int) pending);
  }

  /**
   * 批次结局的唯一判定口径（Java 侧）。
   *
   * <p>UNKNOWN **不算已定局**：结果未知时平台可能已经执行，把它并进「成功」会报出一句谎，把它并进「失败」会诱导运营去重试、造成重复扣减。所以只要还有 UNKNOWN 没核验，批次就停在进行中。
   *
   * <p>与 {@link #outcomeCase()} 是同一套规则的两份实现，改一处必须同时改另一处。
   */
  static String outcomeOf(long total, long rejected, long succeeded, long failed) {
    long settled = rejected + succeeded + failed;
    if (total == 0 || settled < total) return "IN_PROGRESS";
    if (failed == 0 && rejected == 0) return "SUCCEEDED";
    if (succeeded == 0) return "FAILED";
    return "PARTIAL";
  }

  // ==================================================================
  // 候选集解析
  // ==================================================================

  /**
   * 候选集里的一条。
   *
   * <p>{@code id} 为 null 表示「调用方勾了它，但它压根不存在（或不属于当前租户）」：这种情况照样要有一条逐条结果，只是没有平台任务、没有目标属性。
   * {@code requestedId} 保留调用方勾的那个 id，只是为了报错时说清「你勾的是哪一个」。
   */
  private record Resolved(
      UUID requestedId,
      UUID id,
      UUID shopId,
      String targetType,
      String externalRef,
      String skuCode,
      String currency,
      String status,
      BigDecimal currentStock,
      BigDecimal currentPrice) {}

  private static final String RESOLVED_COLUMNS =
      " id,shop_id,target_type,external_ref,sku_code,currency,status,current_stock,current_price";

  /** 勾选优先，筛选兜底。勾选里混进的越权 / 已删目标必须**报出来**，不能悄悄少一条。 */
  private List<Resolved> resolve(AccessService.Access actor, WritebackBatchInput input) {
    if (input.targetIds() != null && !input.targetIds().isEmpty()) {
      List<UUID> asked = input.targetIds().stream().distinct().toList();
      if (asked.size() > MAX_BATCH_TARGETS)
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "一次最多处理 " + MAX_BATCH_TARGETS + " 条，请分批执行");
      List<Object> binds = new ArrayList<>();
      binds.add(actor.tenantId());
      StringBuilder in = new StringBuilder(" in (");
      for (int i = 0; i < asked.size(); i++) {
        in.append(i == 0 ? "?" : ",?");
        binds.add(asked.get(i));
      }
      in.append(")");
      Map<UUID, Resolved> found = new HashMap<>();
      sql.fetch(
              "select" + RESOLVED_COLUMNS + " from writeback_target where tenant_id=? and id" + in,
              binds.toArray())
          .forEach(r -> found.put(r.get("id", UUID.class), mapResolved(r)));
      List<Resolved> ordered = new ArrayList<>();
      for (UUID id : asked) {
        // 顺序按调用方的勾选顺序：逐条回报要能对上用户勾的第几条。
        Resolved hit = found.get(id);
        ordered.add(
            hit != null
                ? hit
                : new Resolved(id, null, null, null, null, null, null, null, null, null));
      }
      return ordered;
    }

    TargetFilter filter = orEmpty(input.filter());
    UUID shopId = input.shopId() != null ? input.shopId() : filter.shopId();
    // 范围必须显式：既没勾选也没给任何筛选条件时放行等于「把整个租户的目标批量改一遍」——
    // 一个调用方的漏传就会变成真实事故，而这种事故事后无法分辨是失误还是本意。
    if (shopId == null
        && filter.targetType() == null
        && filter.status() == null
        && blank(filter.keyword()))
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "请勾选记录范围，或至少给出一个筛选条件（店铺 / 类型 / 状态 / 关键词）");
    List<Object> binds = new ArrayList<>();
    binds.add(actor.tenantId());
    StringBuilder where = new StringBuilder(" where tenant_id=?");
    if (shopId != null) {
      where.append(" and shop_id=?");
      binds.add(shopId);
    }
    if (filter.targetType() != null) {
      where.append(" and target_type=?");
      binds.add(filter.targetType().name());
    }
    if (filter.status() != null) {
      where.append(" and status=?");
      binds.add(filter.status().name());
    }
    if (!blank(filter.keyword())) {
      where.append(" and (lower(external_ref) like lower(?)||'%'"
          + " or lower(sku_code) like '%'||lower(?)||'%')");
      binds.add(filter.keyword().trim());
      binds.add(filter.keyword().trim());
    }
    // 多取一条用于识别「超上限」，真正的上限判定在 execute/preview 里给出行号。
    where.append(" order by shop_id,external_ref limit ").append(MAX_BATCH_TARGETS + 1);
    return sql.fetch(
            "select" + RESOLVED_COLUMNS + " from writeback_target" + where, binds.toArray())
        .stream()
        .map(WritebackBatchService::mapResolved)
        .toList();
  }

  private static Resolved mapResolved(Record r) {
    UUID id = r.get("id", UUID.class);
    return new Resolved(
        id,
        id,
        r.get("shop_id", UUID.class),
        r.get("target_type", String.class),
        r.get("external_ref", String.class),
        r.get("sku_code", String.class),
        r.get("currency", String.class),
        r.get("status", String.class),
        r.get("current_stock", BigDecimal.class),
        r.get("current_price", BigDecimal.class));
  }

  // ==================================================================
  // 逐条校验与 payload
  // ==================================================================

  /** 返回空列表 = 这条可以发出去。命中的每一条都会**如实**写进该条目的 reason。 */
  private List<String> validate(BatchKind kind, Resolved target, BatchParameters parameters) {
    List<String> problems = new ArrayList<>();
    if (TargetStatus.BLOCKED.name().equals(target.status()))
      problems.add("目标已被封禁，不允许发起回写");
    if (kind == BatchKind.SHIP_UPDATE && !"SHIPMENT".equals(target.targetType()))
      problems.add("回传物流只适用于 SHIPMENT 类型的目标");
    if (kind != BatchKind.SHIP_UPDATE && !"LISTING".equals(target.targetType()))
      problems.add(kind + " 只适用于 LISTING 类型的目标");

    PerTargetParameters override = perTarget(parameters, target);
    switch (kind) {
      case UPDATE_STOCK -> {
        BigDecimal stock =
            override != null && override.stock() != null ? override.stock() : parameters.stock();
        if (stock == null) problems.add("缺少库存数量");
        else if (stock.signum() < 0) problems.add("库存数量不能为负");
        else if (stock.scale() > 3) problems.add("库存数量最多 3 位小数");
      }
      case UPDATE_PRICE -> {
        BigDecimal price =
            override != null && override.price() != null ? override.price() : parameters.price();
        if (price == null) problems.add("缺少价格");
        else if (price.signum() <= 0) problems.add("价格必须大于 0");
        else if (price.scale() > 4) problems.add("价格最多 4 位小数");
        if (blank(parameters.currency())) problems.add("缺少三位大写币种");
        else if (blank(target.currency()))
          problems.add("目标没有登记币种，拒绝按无单位金额回写");
        else if (!target.currency().equalsIgnoreCase(parameters.currency()))
          problems.add(
              "币种 " + parameters.currency() + " 与目标登记的 " + target.currency() + " 不一致");
      }
      case SHIP_UPDATE -> {
        String tracking =
            override != null && override.trackingNumber() != null
                ? override.trackingNumber()
                : parameters.trackingNumber();
        String carrier =
            override != null && override.carrierCode() != null
                ? override.carrierCode()
                : parameters.carrierCode();
        if (blank(tracking)) problems.add("缺少物流单号");
        else if (tracking.trim().length() < 5) problems.add("物流单号至少 5 个字符");
        if (blank(carrier)) problems.add("缺少承运商编码");
      }
      case DELIST -> {
        // 下架不需要数值参数；目标被封禁的情况上面已报。
      }
    }
    return problems;
  }

  /** 公共值 → 逐目标覆盖。payload 是**冻结的**：平台收到的是发出那一刻的内容。 */
  private Map<String, Object> merge(BatchKind kind, Resolved target, BatchParameters parameters) {
    PerTargetParameters override = perTarget(parameters, target);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("batchKind", kind.name());
    payload.put("targetType", target.targetType());
    payload.put("externalRef", target.externalRef());
    payload.put("skuCode", target.skuCode() == null ? "" : target.skuCode());
    switch (kind) {
      case UPDATE_STOCK -> {
        BigDecimal stock =
            override != null && override.stock() != null ? override.stock() : parameters.stock();
        payload.put("stock", stock.setScale(3, RoundingMode.UNNECESSARY));
        // 旧值随行冻结：事后追问「这条库存是从多少改成多少」时不至于回头去猜。
        payload.put("previousStock", target.currentStock());
      }
      case UPDATE_PRICE -> {
        BigDecimal price =
            override != null && override.price() != null ? override.price() : parameters.price();
        payload.put("price", price.setScale(4, RoundingMode.UNNECESSARY));
        payload.put("currency", parameters.currency());
        payload.put("previousPrice", target.currentPrice());
      }
      case DELIST -> {
        // 下架 = 改刊登的在线状态（V169 第四节）：payload 带 delist 让派发方分辨方向。
        payload.put("delist", true);
        payload.put("active", false);
      }
      case SHIP_UPDATE -> {
        String tracking =
            override != null && override.trackingNumber() != null
                ? override.trackingNumber()
                : parameters.trackingNumber();
        String carrier =
            override != null && override.carrierCode() != null
                ? override.carrierCode()
                : parameters.carrierCode();
        OffsetDateTime shippedAt =
            override != null && override.shippedAt() != null
                ? override.shippedAt()
                : parameters.shippedAt();
        payload.put("trackingNumber", tracking.trim());
        payload.put("carrierCode", carrier.trim());
        payload.put("shippedAt", shippedAt == null ? null : shippedAt.toInstant().toString());
      }
    }
    return payload;
  }

  private WritebackBatchParameters effectiveParameters(BatchParameters parameters) {
    return new WritebackBatchParameters(
        parameters.stock(),
        parameters.price(),
        parameters.currency(),
        parameters.trackingNumber(),
        parameters.carrierCode(),
        parameters.shippedAt(),
        parameters.perTarget() == null ? 0 : parameters.perTarget().size());
  }

  /**
   * 该目标自己的覆盖项，没有就 null（沿用公共值）。
   *
   * <p>覆盖项是**具名 record 的列表**而不是 Map：Map 的值类型会被 springdoc 推成 object、
   * codegen 出空对象 {@code {}}，前端既拿不到运单号也过不了类型检查（9.6.1.3 已踩过）。
   */
  private PerTargetParameters perTarget(BatchParameters parameters, Resolved target) {
    if (parameters.perTarget() == null || target.id() == null) return null;
    for (PerTargetParameters override : parameters.perTarget()) {
      if (override != null && target.id().toString().equals(override.targetId())) return override;
    }
    return null;
  }

  /** DELIST 走 PUBLISH 通道 + payload.delist，理由见 V169 文件头第四节。 */
  private static String operationOf(BatchKind kind) {
    return kind == BatchKind.DELIST ? "PUBLISH" : kind.name();
  }

  // ==================================================================
  // 幂等键
  // ==================================================================

  /** 调用方没给键时按请求内容算摘要：同样的请求提交两次只生成一批。 */
  private String derivedKey(WritebackBatchInput input) {
    return "wbb-"
        + sha256(
            input.kind().name()
                + "|"
                + (input.shopId() == null ? "" : input.shopId())
                + "|"
                + writeJson(orEmpty(input.filter()))
                + "|"
                + writeJson(input.targetIds() == null ? List.of() : input.targetIds())
                + "|"
                + writeJson(orEmpty(input.parameters())));
  }

  private String taskKey(String batchKey, BatchKind kind, Resolved target, Map<String, Object> payload) {
    return "wbo-"
        + sha256(
            batchKey + "|" + operationOf(kind) + "|" + target.id() + "|" + writeJson(payload));
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 不可用，幂等键无法生成", e);
    }
  }

  // ==================================================================
  // 小工具
  // ==================================================================

  private String nextBatchNo() {
    long next =
        sql.fetchOne("select nextval('writeback_batch_no_seq')").get(0, Long.class);
    return "WB-"
        + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
        + "-"
        + String.format("%06d", next);
  }

  private static BatchParameters orEmpty(BatchParameters parameters) {
    return parameters == null
        ? new BatchParameters(null, null, null, null, null, null, null)
        : parameters;
  }

  private static TargetFilter orEmpty(TargetFilter filter) {
    return filter == null ? new TargetFilter(null, null, null, null) : filter;
  }

  private static void scaleCheck(String label, BigDecimal value, int scale) {
    if (value != null && value.stripTrailingZeros().scale() > scale)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "最多 " + scale + " 位小数");
  }

  private static String writeJson(Object value) {
    try {
      return JSON.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalArgumentException("请求内容无法序列化", e);
    }
  }

  private static JsonNode parseQuietly(String json) {
    if (json == null || json.isBlank()) return null;
    try {
      return JSON.readTree(json);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static <T> T text(JsonNode node, String key, Class<T> type) {
    if (node == null || node.get(key) == null || node.get(key).isNull()) return null;
    try {
      return JSON.treeToValue(node.get(key), type);
    } catch (JsonProcessingException e) {
      return null;
    }
  }

  private static String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String problemCode(String problem) {
    if (problem.startsWith("目标已被封禁")) return "TARGET_BLOCKED";
    if (problem.startsWith("目标不存在")) return "TARGET_MISSING";
    if (problem.startsWith("缺少库存") || problem.startsWith("库存数量")) return "INVALID_STOCK";
    if (problem.startsWith("缺少价格") || problem.startsWith("价格")) return "INVALID_PRICE";
    if (problem.startsWith("缺少三位大写币种")) return "MISSING_CURRENCY";
    if (problem.startsWith("目标没有登记币种")) return "CURRENCY_UNKNOWN";
    if (problem.startsWith("币种")) return "CURRENCY_MISMATCH";
    if (problem.startsWith("缺少物流单号") || problem.startsWith("物流单号")) return "INVALID_TRACKING";
    if (problem.startsWith("缺少承运商")) return "MISSING_CARRIER";
    return "NOT_APPLICABLE";
  }
}
