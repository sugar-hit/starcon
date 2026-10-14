package com.starcon.channel;

import static com.starcon.channel.WarehouseConnectionModels.*;

import com.starcon.channel.warehouse.OverseasWarehouseAdapter;
import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 10.4.2.1 海外仓连接。
 *
 * <p>🔴 本类唯一不能妥协的约束（蓝图 §10.4.2.1 加粗句）： 「外部仓库存是**观测值不是自有账本**，必须与自营库存**分开存储与对账，不能覆盖**」。
 *
 * <p>本类里所有与库存相关的写操作只做一件事：往 {@code external_stock_observation} **插一行新快照**。类里不存在、也不允许存在对观测表的
 * UPDATE/DELETE —— 不是「记得别写」，而是数据库层触发器 + 权限面各挡一层（迁移 V93）， 绕过服务层直接写 SQL 同样改不动已观测到的值。
 *
 * <p>自营库存账本在 warehouse 服务（独立库，V51 warehouse_inventory_ledger）。
 * 本服务没有它的数据源，本类也不引入——分开存储由**分库**保证，比靠约定可靠。
 */
@Service
public class WarehouseConnectionService {
  /** 数量统一 HALF_UP 到 6 位小数，与 DB 的 numeric(19,6) 对齐（禁浮点）。 */
  private static final int QUANTITY_SCALE = 6;

  private final DSLContext sql;
  private final Map<String, OverseasWarehouseAdapter> adapters;

  public WarehouseConnectionService(DSLContext sql, List<OverseasWarehouseAdapter> adapterList) {
    this.sql = sql;
    this.adapters =
        adapterList.stream()
            .collect(Collectors.toMap(OverseasWarehouseAdapter::providerCode, Function.identity()));
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  // ==================================================================
  // 10.4.2.1 服务商目录
  // ==================================================================

  public List<WarehouseProviderView> providers() {
    return sql
        .fetch(
            "select id,code,name,kind,supports_inventory_sync,supports_forecast,"
                + " supports_status_callback,supports_billing from warehouse_provider order by position,code")
        .stream()
        .map(
            r ->
                new WarehouseProviderView(
                    r.get("id", UUID.class),
                    r.get("code", String.class),
                    r.get("name", String.class),
                    r.get("kind", String.class),
                    r.get("supports_inventory_sync", Boolean.class),
                    r.get("supports_forecast", Boolean.class),
                    r.get("supports_status_callback", Boolean.class),
                    r.get("supports_billing", Boolean.class)))
        .toList();
  }

  // ==================================================================
  // 10.4.2.1 连接
  // ==================================================================

  private WarehouseConnectionView mapConnection(Record r) {
    return new WarehouseConnectionView(
        r.get("id", UUID.class),
        r.get("provider_id", UUID.class),
        r.get("provider_code", String.class),
        r.get("provider_name", String.class),
        r.get("authorized_account", String.class),
        r.get("display_name", String.class),
        r.get("api_endpoint_ref", String.class),
        r.get("status", String.class),
        r.get("sync_paused", Boolean.class),
        r.get("last_sync_at", Instant.class),
        r.get("last_error", String.class),
        r.get("version", Long.class),
        r.get("cred_ref", String.class) == null
            ? null
            : new CredentialReferenceView(
                r.get("cred_provider", String.class),
                r.get("cred_ref", String.class),
                r.get("cred_token_type", String.class),
                r.get("cred_status", String.class),
                r.get("cred_expires_at", Instant.class)),
        r.get("account_count", Integer.class));
  }

  private static final String CONNECTION_SELECT =
      "select c.id,c.provider_id,p.code provider_code,p.name provider_name,"
          + " c.authorized_account,c.display_name,c.api_endpoint_ref,c.status,c.sync_paused,"
          + " c.last_sync_at,c.last_error,c.version,"
          + " cr.provider cred_provider,cr.external_ref cred_ref,cr.token_type cred_token_type,"
          + " cr.status cred_status,cr.expires_at cred_expires_at,"
          + " (select count(*) from warehouse_account a where a.tenant_id=c.tenant_id and a.connection_id=c.id) account_count"
          + " from warehouse_connection c join warehouse_provider p on p.id=c.provider_id"
          + " left join credential_ref cr on cr.tenant_id=c.tenant_id and cr.id=c.credential_id";

  public List<WarehouseConnectionView> connections() {
    return sql.fetch(CONNECTION_SELECT + " order by c.status,c.display_name,c.id").stream()
        .map(this::mapConnection)
        .toList();
  }

  public WarehouseConnectionView connection(UUID id) {
    Record r = sql.fetchOne(CONNECTION_SELECT + " where c.id=?", id);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");
    return mapConnection(r);
  }

  /** 新建连接：凭据先落 credential_ref（只存 secret manager 引用），连接只拿外键。 */
  public UUID createConnection(WarehouseConnectionInput input) {
    if (one(Long.class, "select count(*) from warehouse_provider where id=?", input.providerId())
        == 0L) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "服务商不存在");

    UUID credentialId = Ids.next();
    sql.execute(
        "insert into credential_ref(id,tenant_id,provider,external_ref,token_type,authorized_at,status)"
            + " values (?,current_setting('app.current_tenant',true)::uuid,'SECRET_MANAGER',?,'API_KEY',now(),'ACTIVE')",
        credentialId,
        input.credentialExternalRef());

    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into warehouse_connection(id,tenant_id,provider_id,credential_id,authorized_account,"
              + " display_name,api_endpoint_ref) values (?,current_setting('app.current_tenant',true)::uuid,"
              + " ?,?,?,?,?)",
          id,
          input.providerId(),
          credentialId,
          input.authorizedAccount(),
          input.displayName() == null ? "" : input.displayName(),
          input.apiEndpointRef() == null ? "" : input.apiEndpointRef());
    } catch (RuntimeException dup) {
      // 同一服务商同一账号已连接：明确报错，不要留一个指向同一份凭据的僵尸连接
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该服务商账号已存在连接");
    }
    return id;
  }

  /** 连接启用/停用同步。 */
  public void setPaused(UUID id, boolean paused) {
    int n =
        sql.execute(
            "update warehouse_connection set sync_paused=?,updated_at=now(),version=version+1"
                + " where id=? and status<>'REVOKED'",
            paused,
            id);
    if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在或已吊销");
  }

  // ==================================================================
  // 10.4.2.1 仓账户
  // ==================================================================

  private WarehouseAccountView mapAccount(Record r) {
    return new WarehouseAccountView(
        r.get("id", UUID.class),
        r.get("connection_id", UUID.class),
        r.get("connection_name", String.class),
        r.get("provider_code", String.class),
        r.get("external_account_code", String.class),
        r.get("name", String.class),
        r.get("country_code", String.class),
        r.get("timezone", String.class),
        r.get("warehouse_kind", String.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        r.get("last_sync_at", Instant.class),
        r.get("latest_observed_at", Instant.class),
        r.get("observed_sku_count", BigDecimal.class));
  }

  private static final String ACCOUNT_SELECT =
      "select a.id,a.connection_id,coalesce(nullif(c.display_name,''),c.authorized_account) connection_name,"
          + " p.code provider_code,a.external_account_code,a.name,a.country_code,a.timezone,"
          + " a.warehouse_kind,a.status,a.version,c.last_sync_at,"
          + " (select max(o.observed_at) from external_stock_observation o"
          + "  where o.tenant_id=a.tenant_id and o.account_id=a.id) latest_observed_at,"
          + " (select count(distinct o.external_sku) from external_stock_observation o"
          + "  where o.tenant_id=a.tenant_id and o.account_id=a.id) observed_sku_count"
          + " from warehouse_account a join warehouse_connection c"
          + " on c.tenant_id=a.tenant_id and c.id=a.connection_id"
          + " join warehouse_provider p on p.id=c.provider_id";

  public List<WarehouseAccountView> accounts(UUID connectionId) {
    boolean filtered = connectionId != null;
    Result<?> rows =
        sql.fetch(
            ACCOUNT_SELECT
                + " where true"
                + (filtered ? " and a.connection_id=?" : "")
                + " order by a.status,a.name,a.external_account_code",
            filtered ? new Object[] {connectionId} : new Object[0]);
    return rows.stream().map(this::mapAccount).toList();
  }

  public WarehouseAccountView account(UUID id) {
    Record r = sql.fetchOne(ACCOUNT_SELECT + " where a.id=?", id);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓账户不存在");
    return mapAccount(r);
  }

  public UUID createAccount(UUID connectionId, WarehouseAccountInput input) {
    if (one(Long.class, "select count(*) from warehouse_connection where id=?", connectionId) == 0L)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");
    UUID id = Ids.next();
    try {
      sql.execute(
          "insert into warehouse_account(id,tenant_id,connection_id,external_account_code,name,"
              + " country_code,timezone,warehouse_kind) values (?,current_setting('app.current_tenant',true)::uuid,"
              + " ?,?,?,?,?,?)",
          id,
          connectionId,
          input.externalAccountCode(),
          input.name() == null ? "" : input.name(),
          input.countryCode() == null ? "" : input.countryCode().toUpperCase(),
          input.timezone() == null ? "UTC" : input.timezone(),
          input.warehouseKind().name());
    } catch (RuntimeException dup) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该服务商下此仓账号已存在");
    }
    return id;
  }

  // ==================================================================
  // 10.4.2.1 服务商报送行（适配器的真实输入，租户可维护）
  // ==================================================================

  /**
   * 登记一条服务商库存报送。
   *
   * <p>这是适配器的**输入**，不是观测：服务商报错时它要能改（所以有 UPDATE）， 观测快照不能改（所以没有 UPDATE）。两者分表正是为了保住这个区别。
   */
  public UUID recordInboundStockLine(
      UUID accountId,
      String externalSku,
      BigDecimal quantity,
      String unit,
      String source,
      java.time.OffsetDateTime reportedAt) {
    if (one(Long.class, "select count(*) from warehouse_account where id=?", accountId) == 0L)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓账户不存在");
    UUID id = Ids.next();
    sql.execute(
        "insert into warehouse_inbound_stock_line(id,tenant_id,account_id,external_sku,quantity,unit,"
            + " source,reported_at) values (?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?,?::timestamptz)",
        id,
        accountId,
        externalSku,
        normalizeQuantity(quantity),
        unit == null || unit.isBlank() ? "PCS" : unit,
        source,
        reportedAt.toString());
    return id;
  }

  // ==================================================================
  // 10.4.2.1 库存同步（适配器 → 观测快照 + 同步记录）
  // ==================================================================

  /**
   * 触发一次库存同步。
   *
   * <p>同步粒度是**仓账户**（蓝图：多仓服务商下每个仓是一个账户，账单与同步都按账户走）。
   *
   * <p>写路径只有一条：适配器取数 → 插观测快照 → 落同步记录。 全程没有任何一步碰自营库存表——不只是没写，是服务层连数据源都没有。
   */
  public UUID triggerSync(UUID accountId, String triggerSource) {
    AccountContext ctx = accountContext(accountId);
    if (ctx.syncPaused()) throw new ResponseStatusException(HttpStatus.CONFLICT, "该连接已暂停同步");

    OverseasWarehouseAdapter adapter = adapters.get(ctx.providerCode());
    if (adapter == null)
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "该服务商尚未接入适配器");
    if (!ctx.supportsInventorySync())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该服务商未开通库存同步能力");

    // 幂等键按**批次**而不是按账户：键里带上本批报送的最新时点。
    // 只用 accountId 会让同一账户第二次同步永远撞 409 —— 那是把「防重复运行」
    // 错做成「一个账户一辈子只能同步一次」。按批次去重才是原意：
    // 同一批报送不重复跑，新一批报送可以再跑。
    String batch =
        one(
            String.class,
            "select coalesce(max(reported_at)::text,'EMPTY') from warehouse_inbound_stock_line"
                + " where account_id=?",
            accountId);
    String idempotencyKey = "wst:" + accountId + ":" + batch;
    if (one(
            Long.class,
            "select count(*) from warehouse_sync_run where idempotency_key=?",
            idempotencyKey)
        != 0L) throw new ResponseStatusException(HttpStatus.CONFLICT, "该批报送已同步过，无需重复触发");

    UUID runId = Ids.next();
    sql.execute(
        "insert into warehouse_sync_run(id,tenant_id,connection_id,account_id,idempotency_key,"
            + " trigger_source,status) values (?,current_setting('app.current_tenant',true)::uuid,"
            + " ?,?,?,?,'RUNNING')",
        runId,
        ctx.connectionId(),
        accountId,
        idempotencyKey,
        triggerSource);

    OverseasWarehouseAdapter.StockSnapshot snapshot;
    try {
      snapshot =
          adapter.fetchStock(
              new OverseasWarehouseAdapter.ConnectionSettings(
                  ctx.connectionId(),
                  accountId,
                  ctx.authorizedAccount(),
                  ctx.externalAccountCode(),
                  ctx.credentialExternalRef(),
                  ctx.apiEndpointRef()));
    } catch (OverseasWarehouseAdapter.WarehouseProviderException e) {
      finishRun(runId, "FAILED", 0, 0, 1, e.getMessage());
      if (e.authorizationFailure()) markConnectionExpired(ctx.connectionId());
      throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "服务商拉取库存失败：" + e.getMessage());
    }

    int fetched = snapshot.lines().size();
    int observed = writeObservations(runId, ctx, accountId, snapshot);
    finishRun(runId, observed == fetched ? "SUCCEEDED" : "PARTIAL", fetched, observed, 0, "");

    sql.execute(
        "update warehouse_connection set last_sync_at=now(),last_error='',updated_at=now(),"
            + " version=version+1 where id=?",
        ctx.connectionId());
    return runId;
  }

  /**
   * 把适配器取回的观测落成**快照行**。
   *
   * <p>这里是整个模块唯一写库存数字的地方，也是「不能覆盖」的落点： 全部是 INSERT，同账户同 SKU 同时点靠唯一约束去重（重放不翻倍）， 没有任何 UPDATE ——
   * 已观测到的值改不了，只能再落一行新时点的快照。
   */
  private int writeObservations(
      UUID runId,
      AccountContext ctx,
      UUID accountId,
      OverseasWarehouseAdapter.StockSnapshot snapshot) {
    Instant observedAt = snapshot.observedAt();
    int written = 0;
    for (OverseasWarehouseAdapter.StockLine line : snapshot.lines()) {
      UUID id = Ids.next();
      // on conflict do nothing：同一观测时点重放不产生第二行，也不算失败
      int n =
          sql.execute(
              "insert into external_stock_observation(id,tenant_id,connection_id,account_id,run_id,"
                  + " external_sku,quantity,unit,source,observed_at) values "
                  + "(?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?,?,?,?::timestamptz)"
                  + " on conflict (tenant_id,account_id,external_sku,observed_at) do nothing",
              id,
              ctx.connectionId(),
              accountId,
              runId,
              line.externalSku(),
              normalizeQuantity(line.quantity()),
              line.unit() == null || line.unit().isBlank() ? "PCS" : line.unit(),
              snapshot.source(),
              observedAt.toString());
      written += n;
    }
    return written;
  }

  private void finishRun(
      UUID runId, String status, int fetched, int observed, int failed, String error) {
    sql.execute(
        "update warehouse_sync_run set status=?,finished_at=now(),fetched_count=?,observed_count=?,"
            + " failed_count=?,error_detail=? where id=? and status='RUNNING'",
        status,
        fetched,
        observed,
        failed,
        error == null ? "" : error,
        runId);
  }

  /** 授权失效要落到连接状态上，否则每次同步都重复失败而没人知道该去换凭据。 */
  private void markConnectionExpired(UUID connectionId) {
    sql.execute(
        "update warehouse_connection set status='EXPIRED',updated_at=now(),version=version+1"
            + " where id=? and status in ('ACTIVE','EXPIRING')",
        connectionId);
  }

  public List<WarehouseSyncRunView> syncRuns(UUID connectionId, int limit) {
    boolean filtered = connectionId != null;
    Result<?> rows =
        sql.fetch(
            "select r.id,r.connection_id,r.account_id,a.name account_name,r.trigger_source,r.status,"
                + " r.started_at,r.finished_at,r.fetched_count,r.observed_count,r.failed_count,"
                + " r.error_detail from warehouse_sync_run r"
                + " left join warehouse_account a on a.tenant_id=r.tenant_id and a.id=r.account_id"
                + " where true"
                + (filtered ? " and r.connection_id=?" : "")
                + " order by r.started_at desc limit "
                + Math.max(1, Math.min(limit, 200)),
            filtered ? new Object[] {connectionId} : new Object[0]);
    return rows.stream()
        .map(
            r ->
                new WarehouseSyncRunView(
                    r.get("id", UUID.class),
                    r.get("connection_id", UUID.class),
                    r.get("account_id", UUID.class),
                    r.get("account_name", String.class),
                    r.get("trigger_source", String.class),
                    r.get("status", String.class),
                    r.get("started_at", Instant.class),
                    r.get("finished_at", Instant.class),
                    r.get("fetched_count", Integer.class),
                    r.get("observed_count", Integer.class),
                    r.get("failed_count", Integer.class),
                    r.get("error_detail", String.class)))
        .toList();
  }

  // ==================================================================
  // 10.4.2.1 外部库存观测（只读出口）
  // ==================================================================

  /** 某个仓账户的观测流水，按观测时点倒序——观测值的历史本身就是要留的证据。 */
  public List<ExternalStockObservationView> observations(UUID accountId, int limit) {
    return sql
        .fetch(
            "select o.id,o.account_id,a.name account_name,o.external_sku,o.quantity,o.unit,o.source,"
                + " o.observed_at from external_stock_observation o"
                + " join warehouse_account a on a.tenant_id=o.tenant_id and a.id=o.account_id"
                + " where o.account_id=? order by o.observed_at desc,o.external_sku limit "
                + Math.max(1, Math.min(limit, 500)),
            accountId)
        .stream()
        .map(
            r ->
                new ExternalStockObservationView(
                    r.get("id", UUID.class),
                    r.get("account_id", UUID.class),
                    r.get("account_name", String.class),
                    r.get("external_sku", String.class),
                    r.get("quantity", BigDecimal.class),
                    r.get("unit", String.class),
                    r.get("source", String.class),
                    r.get("observed_at", Instant.class)))
        .toList();
  }

  /**
   * 各 SKU 的最新观测。
   *
   * <p>用 DISTINCT ON 按 observed_at 取最新——「当前看到的数」是**查出来的**， 不是存在一行里等着被 UPDATE 覆盖的。这是快照语义最直接的体现。
   */
  public List<ExternalStockLatestView> latestObservations(UUID accountId) {
    return sql
        .fetch(
            "select distinct on (o.external_sku) o.account_id,a.name account_name,o.external_sku,"
                + " o.quantity,o.unit,o.source,o.observed_at"
                + " from external_stock_observation o"
                + " join warehouse_account a on a.tenant_id=o.tenant_id and a.id=o.account_id"
                + " where o.account_id=? order by o.external_sku,o.observed_at desc",
            accountId)
        .stream()
        .map(
            r ->
                new ExternalStockLatestView(
                    r.get("account_id", UUID.class),
                    r.get("account_name", String.class),
                    r.get("external_sku", String.class),
                    r.get("quantity", BigDecimal.class),
                    r.get("unit", String.class),
                    r.get("source", String.class),
                    r.get("observed_at", Instant.class)))
        .toList();
  }

  // ==================================================================
  // 内部
  // ==================================================================

  private record AccountContext(
      UUID connectionId,
      String providerCode,
      boolean syncPaused,
      boolean supportsInventorySync,
      String authorizedAccount,
      String externalAccountCode,
      String credentialExternalRef,
      String apiEndpointRef) {}

  private AccountContext accountContext(UUID accountId) {
    Record r =
        sql.fetchOne(
            "select a.connection_id,a.external_account_code,p.code provider_code,p.supports_inventory_sync,"
                + " c.sync_paused,c.authorized_account,c.api_endpoint_ref,cr.external_ref cred_ref"
                + " from warehouse_account a join warehouse_connection c"
                + " on c.tenant_id=a.tenant_id and c.id=a.connection_id"
                + " join warehouse_provider p on p.id=c.provider_id"
                + " left join credential_ref cr on cr.tenant_id=c.tenant_id and cr.id=c.credential_id"
                + " where a.id=?",
            accountId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓账户不存在");
    return new AccountContext(
        r.get("connection_id", UUID.class),
        r.get("provider_code", String.class),
        r.get("sync_paused", Boolean.class),
        r.get("supports_inventory_sync", Boolean.class),
        r.get("authorized_account", String.class),
        r.get("external_account_code", String.class),
        r.get("cred_ref", String.class),
        r.get("api_endpoint_ref", String.class));
  }

  /** 数量 HALF_UP 到 6 位小数，禁浮点——与 DB 的 numeric(19,6) 一致。 */
  private static BigDecimal normalizeQuantity(BigDecimal q) {
    return q == null
        ? BigDecimal.ZERO.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP)
        : q.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP);
  }
}
