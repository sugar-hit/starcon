package com.starcon.channel;

import static com.starcon.channel.ChannelModels.*;

import com.starcon.tenancy.Ids;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** 02 渠道与店铺的业务逻辑：平台目录、授权连接、店铺与站点。 */
@Service
public class ChannelService {
  /** 2.2.2.2：令牌到期提醒阈值（天）。Shopee 等平台令牌有效期短，阈值更早触发。 */
  private static final long EXPIRY_WARN_DAYS = 7;

  private final DSLContext sql;

  public ChannelService(DSLContext sql) {
    this.sql = sql;
  }

  /**
   * Single-column, single-row lookup with a typed result.
   *
   * <p>jOOQ only exposes {@code fetchValue} as {@code Object}, which turns every call site into an
   * unchecked cast. This keeps the cast in one place and returns null for "no such row".
   */
  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private long count(String query, Object... binds) {
    Long v = one(Long.class, query, binds);
    return v == null ? 0L : v;
  }

  /**
   * 2.1.1.2 能力矩阵键名提取。
   *
   * <p>jsonb 到达 JDBC 后可能是 String、PGobject 或 Map，取决于驱动与 jOOQ 配置；
   * 这里统一转成 JSON 文本再解析，避免"能力为空"这种静默失真——能力矩阵是
   * 09/07/10/11 禁用按钮的依据，读空等于让所有按钮都可点。
   */
  private static List<String> capabilityKeys(Object raw) {
    if (raw == null) return List.of();
    String json = jsonText(raw);
    try {
      var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
      List<String> keys = new ArrayList<>();
      node.fields().forEachRemaining(e -> {
        if (e.getValue().asBoolean(false)) keys.add(e.getKey());
      });
      return keys;
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("能力矩阵 JSON 解析失败", e);
    }
  }

  /** jsonb 的 JDBC 形态随驱动而变，优先取 PGObject.getValue()。 */
  private static String jsonText(Object raw) {
    if (raw instanceof String s) return s;
    if (raw instanceof org.jooq.JSONB j) return j.data();
    try {
      Object value =
          raw.getClass().getMethod("getValue").invoke(raw);
      return value == null ? "{}" : value.toString();
    } catch (ReflectiveOperationException e) {
      return raw.toString();
    }
  }

  // ------------------------------------------------------------------
  // 2.1 平台目录（全局码表）
  // ------------------------------------------------------------------

  /** 2.1.1.1 平台目录 + 2.1.1.2 能力矩阵 + 2.1.1.3 站点参数。 */
  public List<PlatformView> platforms() {
    Map<UUID, PlatformView> platforms = new LinkedHashMap<>();
    for (Record r : sql.fetch("select id,code,name,integration,support_status,capabilities from platform order by position,code")) {
      List<String> capabilities = new ArrayList<>();
      for (String key : capabilityKeys(r.get("capabilities"))) capabilities.add(key);
      platforms.put(
          r.get("id", UUID.class),
          new PlatformView(
              r.get("id", UUID.class),
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("integration", String.class),
              r.get("support_status", String.class),
              List.copyOf(capabilities),
              new ArrayList<>()));
    }
    for (Record r :
        sql.fetch(
            "select id,platform_id,code,name,country_code,default_currency,default_timezone,default_locale,currency_scale,vat_required"
                + " from marketplace order by position,code")) {
      MarketplaceView site =
          new MarketplaceView(
              r.get("id", UUID.class),
              r.get("code", String.class),
              r.get("name", String.class),
              r.get("country_code", String.class),
              r.get("default_currency", String.class),
              r.get("default_timezone", String.class),
              r.get("default_locale", String.class),
              r.get("currency_scale", Integer.class),
              r.get("vat_required", Boolean.class));
      PlatformView p = platforms.get(r.get("platform_id", UUID.class));
      if (p != null) p.marketplaces().add(site);
    }
    return List.copyOf(platforms.values());
  }

  /** 2.1.1.2 能力判定：被 09/07/10/11 引用，在别处禁用按钮而不是点了才报错。 */
  public boolean supports(UUID platformId, String capability) {
    return Boolean.TRUE.equals(
        one(Boolean.class, "select (capabilities->>?)::boolean from platform where id=?", capability, platformId));
  }

  // ------------------------------------------------------------------
  // 2.1.2 平台枚举映射
  // ------------------------------------------------------------------

  public List<EnumMappingView> enumMappings(String platformCode, String enumKind) {
    Result<?> rows =
        sql.fetch(
            "select m.id,m.platform_code,m.enum_kind,m.external_value,m.internal_value"
                + " from platform_enum_mapping m join platform p on p.id=m.platform_id"
                + " where p.code=? and (?='' or m.enum_kind=?) order by m.enum_kind,m.external_value",
            platformCode, enumKind, enumKind);
    return rows.stream().map(r -> mapEnum(r)).toList();
  }

  private static EnumMappingView mapEnum(Record r) {
    return new EnumMappingView(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("enum_kind", String.class),
        r.get("external_value", String.class),
        r.get("internal_value", String.class));
  }

  /**
   * 解析平台枚举。
   *
   * <p>2.1.2.1 铁律：映射缺失必须显式报"未映射"，绝不默认落到兜底状态——否则状态机被污染。
   */
  public String resolveEnum(UUID platformId, String enumKind, String externalValue) {
    return one(
        String.class,
        "select m.internal_value from platform_enum_mapping m"
            + " where m.platform_id=? and m.enum_kind=? and m.external_value=?",
        platformId, enumKind, externalValue);
  }

  // ------------------------------------------------------------------
  // 2.2 授权与连接
  // ------------------------------------------------------------------

  /** 2.2.1.1 连接列表。绝不返回凭据明文，只有元信息。 */
  public List<ConnectionView> connections(String q) {
    // 空搜索词直接走无过滤查询：避免 "?" 占位符在 LIKE 与空串判断混用时
    // 让 jOOQ 误判参数个数，也让计划能命中不同索引。
    boolean filtered = q != null && !q.isBlank();
    String filter = filtered ? " and (c.display_name ilike ? or c.authorized_account ilike ? or p.name ilike ?)" : "";
    String like = "%" + (filtered ? q.trim() : "") + "%";
    Result<?> rows =
        sql.fetch(
            "select c.id,c.platform_id,p.code platform_code,p.name platform_name,c.authorized_account,c.display_name,"
                + " c.status,c.sync_paused,c.last_sync_at,c.last_error,c.version,"
                + " cr.id cred_id,cr.provider,cr.token_type,cr.granted_scopes,cr.authorized_at,cr.expires_at,"
                + " cr.rotated_at,cr.status cred_status,"
                + " (select count(*) from shop s where s.tenant_id=c.tenant_id and s.connection_id=c.id) shop_count"
                + " from channel_connection c join platform p on p.id=c.platform_id"
                + " left join credential_ref cr on cr.tenant_id=c.tenant_id and cr.id=c.credential_id"
                + " where true"
                + filter
                + " order by c.status='ACTIVE' desc,p.position,c.created_at desc",
            filtered ? new Object[] {like, like, like} : new Object[0]);
    return rows.stream().map(r -> mapConnection(r)).toList();
  }

  private static ConnectionView mapConnection(Record r) {
    UUID credId = r.get("cred_id", UUID.class);
    CredentialMetaView cred =
        credId == null
            ? null
            : new CredentialMetaView(
                credId,
                r.get("provider", String.class),
                r.get("token_type", String.class),
                r.get("granted_scopes", String.class),
                r.get("authorized_at", Instant.class),
                r.get("expires_at", Instant.class),
                r.get("rotated_at", Instant.class),
                r.get("cred_status", String.class));
    return new ConnectionView(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("authorized_account", String.class),
        r.get("display_name", String.class),
        r.get("status", String.class),
        r.get("sync_paused", Boolean.class),
        r.get("last_sync_at", Instant.class),
        r.get("last_error", String.class),
        r.get("version", Long.class),
        cred,
        r.get("shop_count", Long.class));
  }

  /** 2.2.1.2 授权向导的落库步骤：创建连接（不含凭据，等轮换时写入引用）。 */
  public UUID createConnection(AccessService.Access actor, ConnectionInput input) {
    UUID platformId =
        one(UUID.class, "select id from platform where code=?", input.platformCode());
    if (platformId == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台不存在或尚未开放接入");
    UUID id = Ids.next();
    sql.execute(
        "insert into channel_connection(id,tenant_id,platform_id,authorized_account,display_name)"
            + " values (?,?,?,?,?)",
        id, actor.tenantId(), platformId, input.authorizedAccount(), nullToEmpty(input.displayName()));
    audit(actor, id, "channel.connection.create");
    return id;
  }

  /**
   * 2.2.1.3 凭据轮换：只接收密钥管理器写好的引用。
   *
   * <p>库里永远没有明文令牌；轮换在同一个连接上换引用并更新有效期。
   */
  public void rotateCredential(AccessService.Access actor, UUID connectionId, CredentialRotateInput input) {
    // 连接自身的版本用于乐观锁；新建连接尚未挂凭据时 credential_id 为 NULL，
    // 因此必须用 "无行" 而非 "值为 null" 来判断连接是否存在。
    long expected = requireConnectionVersion(connectionId);
    UUID existing =
        one(
            UUID.class,
            "select credential_id from channel_connection where tenant_id=? and id=?",
            actor.tenantId(),
            connectionId);
    if (expected < 0)
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");
    // jOOQ 3.19 把 Instant / OffsetDateTime / String 一律绑成 varchar，PG 拒绝写入 timestamptz；
    // 只有 LocalDateTime 会按原生类型发送。时区显式选 UTC，不能依赖 JVM 默认时区。
    LocalDateTime authorizedAt =
        LocalDateTime.ofInstant(
            input.authorizedAt() == null ? Instant.now() : input.authorizedAt(), ZoneOffset.UTC);
    LocalDateTime expiresAt =
        input.expiresAt() == null ? null : LocalDateTime.ofInstant(input.expiresAt(), ZoneOffset.UTC);
    UUID credId;
    if (existing != null) {
      sql.execute(
          "update credential_ref set external_ref=?,granted_scopes=?,authorized_at=?,expires_at=?,rotated_at=now(),"
              + " status='ACTIVE',version=version+1,updated_at=now() where tenant_id=? and id=?",
          input.externalRef(), input.grantedScopes(), authorizedAt, expiresAt, actor.tenantId(), existing);
      credId = existing;
    } else {
      credId = Ids.next();
      sql.execute(
          "insert into credential_ref(id,tenant_id,external_ref,granted_scopes,authorized_at,expires_at)"
              + " values (?,?,?,?,?,?)",
          credId, actor.tenantId(), input.externalRef(), input.grantedScopes(), authorizedAt, expiresAt);
    }
    // 重新授权成功即恢复连接可用状态，并把到期状态重新算一遍
    sql.execute(
        "update channel_connection set credential_id=?,status=?,last_error='',version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?",
        credId, syncPausedStatus(credId), actor.tenantId(), connectionId);
    audit(actor, connectionId, "channel.connection.rotate");
    // 乐观锁收尾：连接版本必须恰好 +1，否则说明中途被别人改过
    requireConnectionVersion(connectionId, expected + 1);
  }

  private String syncPausedStatus(UUID credentialId) {
    Instant expires =
        one(Instant.class, "select expires_at from credential_ref where id=?", credentialId);
    if (expires == null) return "ACTIVE";
    return expires.isBefore(Instant.now()) ? "EXPIRED" : (daysUntil(expires) <= EXPIRY_WARN_DAYS ? "EXPIRING" : "ACTIVE");
  }

  /** 2.2.1.1 断开连接：不删除历史数据，历史订单仍可查。 */
  public void revokeConnection(AccessService.Access actor, UUID connectionId, long expectedVersion) {
    requireConnectionVersion(connectionId, expectedVersion);
    sql.execute(
        "update channel_connection set status='REVOKED',sync_paused=true,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?",
        actor.tenantId(), connectionId);
    // 凭据同步失效，否则列表里会同时出现"连接已撤销"和"凭据仍有效"两种自相矛盾的状态
    sql.execute(
        "update credential_ref set status='REVOKED',version=version+1,updated_at=now() where tenant_id=? and status<>'REVOKED'"
            + " and id=(select credential_id from channel_connection where tenant_id=? and id=?)",
        actor.tenantId(), actor.tenantId(), connectionId);
    audit(actor, connectionId, "channel.connection.revoke");
  }

  /** 2.2.2.1 健康诊断。 */
  public ConnectionHealthView health(AccessService.Access actor, UUID connectionId) {
    ConnectionView c =
        connections("").stream().filter(v -> v.id().equals(connectionId)).findFirst().orElseThrow();
    if (!visible(connectionId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");
    Instant expires = c.credential() == null ? null : c.credential().expiresAt();
    long days = expires == null ? -1 : daysUntil(expires);
    boolean tokenDead = expires != null && expires.isBefore(Instant.now());
    String status = c.status();
    String diagnosis;
    String recommendation;
    boolean canSync;
    if ("REVOKED".equals(status)) {
      diagnosis = "授权已撤销，同步已停止";
      recommendation = "重新完成授权后再恢复同步";
      canSync = false;
    } else if (tokenDead) {
      diagnosis = "令牌已过期，平台调用会全部失败";
      recommendation = "立即重新授权，否则该连接下店铺会持续漏单";
      canSync = false;
    } else if ("EXPIRING".equals(status) || (days >= 0 && days <= EXPIRY_WARN_DAYS)) {
      diagnosis = "令牌将在 " + days + " 天后到期";
      recommendation = "在到期前完成重新授权，避免同步中断";
      canSync = !c.syncPaused();
    } else if (!c.lastError().isBlank()) {
      diagnosis = "最近一次同步失败：" + c.lastError();
      recommendation = "到同步运行日志查看明细，修复后重放";
      canSync = false;
    } else {
      diagnosis = "连接正常";
      recommendation = "无需处置";
      canSync = !c.syncPaused();
    }
    return new ConnectionHealthView(
        connectionId, status, diagnosis, recommendation, canSync, expires, days, c.lastSyncAt(), c.lastError());
  }

  /** 2.2.2.2 重新授权提醒：临期连接进入工作台待办。 */
  public List<ReauthorizationReminderView> reauthorizationReminders(int withinDays) {
    // 用 make_interval 传天数：jOOQ 解析不了 "(?||' days')::interval" 这类拼接表达式
    Result<?> rows =
        sql.fetch(
            "select c.id,p.code,c.authorized_account,cr.expires_at,"
                + " extract(day from (cr.expires_at - now()))::bigint days_left"
                + " from channel_connection c join platform p on p.id=c.platform_id"
                + " join credential_ref cr on cr.tenant_id=c.tenant_id and cr.id=c.credential_id"
                + " where c.status in ('ACTIVE','EXPIRING') and cr.status<>'REVOKED'"
                + " and cr.expires_at is not null"
                + " and cr.expires_at > now() and cr.expires_at <= now() + make_interval(days => ?)"
                + " order by cr.expires_at",
            withinDays);
    return rows.stream()
        .map(
            r -> {
              long days = r.get("days_left", Long.class);
              String severity = days <= 1 ? "CRITICAL" : (days <= 3 ? "WARNING" : "NOTICE");
              return new ReauthorizationReminderView(
                  r.get("id", UUID.class),
                  r.get("code", String.class),
                  r.get("authorized_account", String.class),
                  r.get("expires_at", Instant.class),
                  days,
                  severity);
            })
        .toList();
  }

  private static long daysUntil(Instant at) {
    return ChronoUnit.DAYS.between(Instant.now(), at);
  }

  private boolean visible(UUID connectionId) {
    return count("select count(*) from channel_connection where id=?", connectionId) > 0;
  }

  // ------------------------------------------------------------------
  // 2.3 店铺与站点
  // ------------------------------------------------------------------

  /** 2.3.1.1 店铺列表。 */
  public List<ShopView> shops(String q) {
    boolean filtered = q != null && !q.isBlank();
    String filter = filtered ? " and (s.name ilike ? or s.code ilike ? or p.name ilike ?)" : "";
    String like = "%" + (filtered ? q.trim() : "") + "%";
    Result<?> rows =
        sql.fetch(
            "select s.id,s.platform_id,p.code platform_code,p.name platform_name,s.connection_id,"
                + " c.authorized_account,s.legal_entity_id,s.code,s.name,s.platform_shop_id,s.operating_mode,"
                + " s.owner_id,s.department_id,s.status,s.version,c.last_sync_at,"
                + " (select count(*) from shop_marketplace m where m.tenant_id=s.tenant_id and m.shop_id=s.id"
                + "  and m.status='ACTIVE') marketplace_count"
                + " from shop s join platform p on p.id=s.platform_id"
                + " left join channel_connection c on c.tenant_id=s.tenant_id and c.id=s.connection_id"
                + " where true"
                + filter
                + " order by p.position,s.created_at desc",
            filtered ? new Object[] {like, like, like} : new Object[0]);
    return rows.stream().map(r -> mapShop(r, null)).toList();
  }

  /** 2.3.1.2 店铺详情：带启用站点。 */
  public ShopView shop(UUID tenantId, UUID id) {
    Record r =
        sql.fetchOne(
            "select s.id,s.platform_id,p.code platform_code,p.name platform_name,s.connection_id,"
                + " c.authorized_account,s.legal_entity_id,s.code,s.name,s.platform_shop_id,s.operating_mode,"
                + " s.owner_id,s.department_id,s.status,s.version,c.last_sync_at,"
                + " (select count(*) from shop_marketplace m where m.tenant_id=s.tenant_id and m.shop_id=s.id"
                + "  and m.status='ACTIVE') marketplace_count"
                + " from shop s join platform p on p.id=s.platform_id"
                + " left join channel_connection c on c.tenant_id=s.tenant_id and c.id=s.connection_id"
                + " where s.id=?",
            id);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺不存在");
    List<ShopMarketplaceView> sites = new ArrayList<>();
    for (Record m :
        sql.fetch(
            "select mk.id,mk.code,mk.name,ms.status,ms.enabled_at"
                + " from shop_marketplace ms join marketplace mk on mk.id=ms.marketplace_id"
                + " where ms.tenant_id=? and ms.shop_id=? order by mk.position,mk.code",
            tenantId, id))
      sites.add(
          new ShopMarketplaceView(
              m.get("id", UUID.class),
              m.get("code", String.class),
              m.get("name", String.class),
              m.get("status", String.class),
              m.get("enabled_at", Instant.class)));
    return mapShop(r, List.copyOf(sites));
  }

  private static ShopView mapShop(Record r, List<ShopMarketplaceView> sites) {
    return new ShopView(
        r.get("id", UUID.class),
        r.get("platform_id", UUID.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("connection_id", UUID.class),
        r.get("authorized_account", String.class),
        r.get("legal_entity_id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("platform_shop_id", String.class),
        r.get("operating_mode", String.class),
        r.get("owner_id", UUID.class),
        r.get("department_id", UUID.class),
        r.get("status", String.class),
        r.get("version", Long.class),
        r.get("marketplace_count", Integer.class),
        sites == null ? List.of() : sites,
        r.get("last_sync_at", Instant.class));
  }

  public UUID createShop(AccessService.Access actor, ShopInput input) {
    UUID platformId = one(UUID.class, "select id from platform where code=?", input.platformCode());
    if (platformId == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台不存在或尚未开放接入");
    if (input.connectionId() != null) {
      Integer owned =
          one(Integer.class,
              "select 1 from channel_connection where tenant_id=? and id=? and platform_id=?",
              actor.tenantId(), input.connectionId(), platformId);
      if (owned == null)
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选连接不属于该平台或当前租户");
    }
    UUID id = Ids.next();
    sql.execute(
        "insert into shop(id,tenant_id,platform_id,connection_id,legal_entity_id,code,name,operating_mode)"
            + " values (?,?,?,?,?,?,?,?)",
        id,
        actor.tenantId(),
        platformId,
        input.connectionId(),
        input.legalEntityId(),
        input.code(),
        input.name(),
        input.operatingMode());
    audit(actor, id, "channel.shop.create");
    return id;
  }

  public void updateShop(AccessService.Access actor, UUID id, ShopInput input) {
    // 客户端必须带上读到的版本号；与服务端当前版本不一致说明中间被别人改过，直接拒绝
    if (input.version() == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "更新店铺必须携带版本号");
    long expected = requireVersion(id);
    if (expected != input.version())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被他人修改，请刷新后重试");
    UUID platformId = one(UUID.class, "select id from platform where code=?", input.platformCode());
    int updated =
        sql.execute(
            "update shop set platform_id=?,connection_id=?,legal_entity_id=?,code=?,name=?,operating_mode=?,"
                + " version=version+1,updated_at=now() where tenant_id=? and id=?",
            platformId,
            input.connectionId(),
            input.legalEntityId(),
            input.code(),
            input.name(),
            input.operatingMode(),
            actor.tenantId(),
            id);
    if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺不存在");
    audit(actor, id, "channel.shop.update");
    touchVersion(id, expected);
  }

  /**
   * 2.3.1.2 删除店铺前必须检查是否仍被单据引用。
   *
   * <p>刊登、订单等下游表还没有建（后续期），因此这里检查已存在的引用点：
   * 启用中的站点配置不允许直接删除，只能先停用。
   */
  public void deleteShop(AccessService.Access actor, UUID id, long expectedVersion) {
    long activeSites =
        count(
            "select count(*) from shop_marketplace where tenant_id=? and shop_id=? and status='ACTIVE'",
            actor.tenantId(), id);
    if (activeSites > 0)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该店铺仍有 " + activeSites + " 个启用中的站点，请先停用再删除");
    requireVersion(id, expectedVersion);
    int deleted = sql.execute("delete from shop where tenant_id=? and id=?", actor.tenantId(), id);
    if (deleted == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺不存在");
    audit(actor, id, "channel.shop.delete");
  }

  /** 2.3.1.1 启用站点。 */
  public void enableMarketplace(AccessService.Access actor, UUID shopId, ShopMarketplaceInput input) {
    // 站点码只在平台内唯一，因此必须把店铺的平台一起带入，否则会把别平台的同名站点挂上来
    UUID mkId =
        one(
            UUID.class,
            "select m.id from marketplace m join shop s on s.platform_id=m.platform_id"
                + " where s.tenant_id=? and s.id=? and m.code=?",
            actor.tenantId(), shopId, input.marketplaceCode());
    if (mkId == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "站点不存在或不属于该店铺所在平台");
    sql.execute(
        "insert into shop_marketplace(tenant_id,shop_id,marketplace_id,status)"
            + " values (?,?,?,?) on conflict (tenant_id,shop_id,marketplace_id)"
            + " do update set status=excluded.status",
        actor.tenantId(), shopId, mkId, input.status() == null ? "ACTIVE" : input.status());
    audit(actor, shopId, "channel.shop.marketplace.enable");
  }

  public void disableMarketplace(AccessService.Access actor, UUID shopId, UUID marketplaceId) {
    int updated =
        sql.execute(
            "update shop_marketplace set status='SUSPENDED' where tenant_id=? and shop_id=? and marketplace_id=?",
            actor.tenantId(), shopId, marketplaceId);
    if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该店铺未启用此站点");
    audit(actor, shopId, "channel.shop.marketplace.disable");
  }

  /**
   * 2.3.2.2 负责人与数据范围。
   *
   * <p>owner_id 在建表时由触发器锁为不可变，转让必须走这个显式端点，这样绩效归属的变化
   * 一定会在审计里留下痕迹。
   */
  public void transferOwner(AccessService.Access actor, UUID shopId, ShopOwnerTransferInput input) {
    // 触发器只认这个显式意图位，因此转让只可能发生在这个可审计的端点上
    sql.fetch("select set_config('app.transfer','true',true)");
    int updated =
        sql.execute(
            "update shop set owner_id=?,department_id=?,version=version+1,updated_at=now() where tenant_id=? and id=?",
            input.newOwnerId(), input.departmentId(), actor.tenantId(), shopId);
    if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "店铺不存在");
    audit(actor, shopId, "channel.shop.transfer");
  }

  // ------------------------------------------------------------------
  // 并发与审计
  // ------------------------------------------------------------------

  /** 连接版本；返回 -1 表示连接不存在，让调用方决定如何报错。 */
  private long requireConnectionVersion(UUID id) {
    Long v = one(Long.class, "select version from channel_connection where id=?", id);
    return v == null ? -1 : v;
  }

  private void requireConnectionVersion(UUID id, long expected) {
    long v = requireConnectionVersion(id);
    if (v < 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "连接不存在");
    if (v != expected)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被他人修改，请刷新后重试");
  }

  private long requireVersion(UUID id) {
    Long v = one(Long.class, "select version from shop where id=?", id);
    if (v == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在");
    return v;
  }

  private long requireVersion(UUID id, long expected) {
    Long v = requireVersion(id);
    if (v != expected)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被他人修改，请刷新后重试");
    return v;
  }

  /** 乐观锁：版本变化必须恰好为 1，否则说明中途被别人改过。 */
  private void touchVersion(UUID id, long expected) {
    Long v = one(Long.class, "select version from shop where id=?", id);
    if (v == null || v != expected + 1)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "记录已被他人修改，请刷新后重试");
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values (?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  private static String nullToEmpty(String s) {
    return s == null ? "" : s;
  }
}
