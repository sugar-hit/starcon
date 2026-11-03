package com.starcon.platform;

import com.starcon.platform.ApiCallMonitorModels.Outcome;
import com.starcon.platform.AppCredentialModels.QuotaWindow;
import com.starcon.tenancy.TenantTransactions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.jooq.DSLContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 18.1.1.3 的<b>上游</b>：API 网关入口（对外系统的真实调用入口，命名空间 {@code /open/v1/**}）。
 *
 * <p>蓝图写死「<b>上游 = API 网关</b>」，而本仓今天**没有网关** —— 于是本车道把网关补上，
 * 因为不补就没有调用埋点，没有埋点，QPS / 错误率 / 限流命中 / 慢调用四维就只能是编出来的。
 *
 * <p><b>网关做的事，五步，顺序固定：</b>
 *
 * <ol>
 *   <li><b>定位凭证</b>：拿调用方给的 API Key 算 SHA-256 → 走
 *       {@code platform_api_credential_lookup}（<b>唯一的跨租户出口</b>）拿到候选行 →
 *       再用 BCrypt 比 Secret。定位不到租户 ⇒ <b>不落埋点</b>（没有租户可归属，
 *       硬塞一个租户等于把 A 的调用量算到 B 头上）。
 *   <li><b>判凭证状态</b>：凭证被轮换 / 吊销 ⇒ 401。
 *   <li><b>判应用状态</b>：应用被暂停 / 吊销 ⇒ 403。
 *   <li><b>判授权范围与 IP 白名单</b>：按 18.1.1.1 的口径（<b>没配 = 不限制</b>）。
 *   <li><b>判配额</b>：按 18.1.1.1 的 {@code platform_app_quota} 逐窗口判 ⇒ 超了 429。
 * </ol>
 *
 * <p>🔴 <b>五种失败全部落埋点</b>（凭证错 / 应用被吊销 / 范围不足 / IP 不在白名单 / 超出配额）——
 * 它们恰恰是对接方最需要被定位的失败。蓝图原话「外部系统调用错误要能被快速定位，
 * 否则对接方只会说"你们接口有问题"」—— 把失败记下来是这句话的**前提**。
 *
 * <p>🔴 <b>限流命中与错误分开记</b>：429 既是错误也是限流命中，但「有多少次调用被配额挡下来」
 * 与「有多少个 4xx」是两个问题，埋点里分成 {@code outcome} 与 {@code rate_limited} 两列。
 *
 * <p>⚠️ <b>被限流挡下的调用不计入用量</b>：它没有消耗服务。限流命中次数由埋点的
 * {@code rate_limited} 单独统计，不靠用量表反推。
 */
@Service
public class ApiGatewayService {

  private static final BCryptPasswordEncoder PASSWORDS = new BCryptPasswordEncoder();

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final Clock clock;

  public ApiGatewayService(DSLContext sql, TenantTransactions tenants, Clock clock) {
    this.sql = sql;
    this.tenants = tenants;
    this.clock = clock;
  }

  // ================================================================ 模型

  /** 一次网关调用的结果：真实状态码 + 响应体。 */
  public record GatewayResult(int statusCode, Object body) {}

  /** 已定位到租户与应用的调用方身份（**不含任何明文**）。 */
  public record GatewayIdentity(
      UUID tenantId,
      UUID appId,
      UUID credentialId,
      String appCode,
      String appName,
      String appStatus,
      String credentialStatus,
      String apiKeyPrefix,
      String secretHint,
      int credentialVersion) {}

  /** 处理请求体时能看到的会话（身份 + 请求号 + 端点码）。 */
  public record GatewaySession(GatewayIdentity identity, String requestId, String endpointCode) {}

  /** 凭证候选行（跨租户函数的返回）。 */
  private record Candidate(
      UUID tenantId,
      UUID appId,
      UUID credentialId,
      String secretDigest,
      String apiKeyPrefix,
      String secretHint,
      String credentialStatus,
      int credentialVersion,
      String appStatus,
      String appCode,
      String appName) {}

  /** 配额判定结论。 */
  private record QuotaDecision(
      boolean exceeded, String windowType, BigDecimal limitCount, BigDecimal usedCount) {}

  /**
   * 业务动作里的「没有这条」。
   *
   * <p>🔴 它与「内部错误」必须分开：前者是 404 / NOT_FOUND（调用方要的东西不存在），
   * 后者是 500 / ERROR（我们出问题了）。两者都落埋点，但结论码不同 ——
   * 「错误率」这一维要能区分这两种。
   */
  public static final class NotFound extends RuntimeException {
    public NotFound(String message) {
      super(message);
    }
  }

  // ================================================================ 主流程

  /**
   * 处理一次网关调用。
   *
   * @param endpoint 命中的端点（{@code null} = 路径不在放行清单里，直接 404）
   * @param work 真正的业务动作（在**租户事务内**执行，因此它看到的是本租户的数据）
   */
  public GatewayResult invoke(
      ApiGatewayEndpoints.Endpoint endpoint,
      String method,
      String path,
      String apiKey,
      String secret,
      String clientIp,
      String requestId,
      Function<GatewaySession, Object> work) {
    var startedAt = System.nanoTime();

    // 1) 定位凭证：唯一的跨租户出口。
    var candidates = lookupCandidates(apiKey);
    if (candidates.isEmpty()) {
      // Key 根本不存在 ⇒ **没有租户可归属** ⇒ 不落埋点。
      return failure(401, "API_KEY_UNKNOWN", "API Key 不存在或已被吊销", requestId);
    }
    var matched = new ArrayList<Candidate>();
    for (var candidate : candidates) {
      if (PASSWORDS.matches(secret == null ? "" : secret, candidate.secretDigest())) {
        matched.add(candidate);
      }
    }
    if (matched.size() > 1) {
      // 同一个 Key 值在两个租户下都登记过，且 Secret 都对得上 ⇒ 无法确定归属。
      // 此时**不落行**：落给谁都是猜的。
      return failure(
          409, "API_KEY_AMBIGUOUS", "同一个 API Key 在多个租户下都能匹配成功，无法确定归属", requestId);
    }
    if (matched.isEmpty()) {
      // Key 对得上、Secret 不对 ⇒ 租户**已知** ⇒ 落埋点（这正是对接方要查的那一类）。
      var identity = identityOf(candidates.get(0));
      return tenants.run(
          identity.tenantId(),
          () -> {
            record(
                identity,
                endpoint,
                method,
                path,
                401,
                Outcome.AUTH_FAILED,
                "SECRET_MISMATCH",
                false,
                elapsedMs(startedAt),
                clientIp,
                requestId);
            return failure(401, "SECRET_MISMATCH", "API Secret 不匹配", requestId);
          });
    }

    var identity = identityOf(matched.get(0));
    return tenants.run(identity.tenantId(), () -> authorize(identity, endpoint, method, path, clientIp, requestId, startedAt, work));
  }

  /** 租户内的四道判定 + 真正的业务动作 + 埋点落库。 */
  private GatewayResult authorize(
      GatewayIdentity identity,
      ApiGatewayEndpoints.Endpoint endpoint,
      String method,
      String path,
      String clientIp,
      String requestId,
      long startedAt,
      Function<GatewaySession, Object> work) {

    // 凭证状态：被轮换 / 被吊销的凭证不能再用。
    if (!"ACTIVE".equals(identity.credentialStatus())) {
      record(
          identity,
          endpoint,
          method,
          path,
          401,
          Outcome.AUTH_FAILED,
          "CREDENTIAL_" + identity.credentialStatus(),
          false,
          elapsedMs(startedAt),
          clientIp,
          requestId);
      return failure(401, "CREDENTIAL_NOT_ACTIVE", "该凭证已失效（状态：" + identity.credentialStatus() + "）", requestId);
    }

    // 应用状态：被暂停 / 被吊销的应用不能继续调用。
    if (!"ACTIVE".equals(identity.appStatus())) {
      record(
          identity,
          endpoint,
          method,
          path,
          403,
          Outcome.FORBIDDEN,
          "APP_" + identity.appStatus(),
          false,
          elapsedMs(startedAt),
          clientIp,
          requestId);
      return failure(403, "APP_NOT_ACTIVE", "应用已不可用（状态：" + identity.appStatus() + "）", requestId);
    }

    // 授权范围：18.1.1.1 的口径是「没配 = 不限制」。
    var scopes = scopesOf(identity.appId());
    if (!scopes.isEmpty() && !scopes.contains(endpoint.resourceCode() + ":" + endpoint.actionCode())) {
      record(
          identity,
          endpoint,
          method,
          path,
          403,
          Outcome.FORBIDDEN,
          "SCOPE_DENIED",
          false,
          elapsedMs(startedAt),
          clientIp,
          requestId);
      return failure(
          403,
          "SCOPE_DENIED",
          "该应用没有被授予 " + endpoint.resourceCode() + " 的 " + endpoint.actionCode() + " 权限",
          requestId);
    }

    // IP 白名单：同样是「没配 = 不限制」。
    var allowlist = ipAllowlistOf(identity.appId());
    if (!allowlist.isEmpty()
        && allowlist.stream().noneMatch(cidr -> AppCredentialService.Cidrs.contains(cidr, clientIp))) {
      record(
          identity,
          endpoint,
          method,
          path,
          403,
          Outcome.FORBIDDEN,
          "IP_NOT_ALLOWED",
          false,
          elapsedMs(startedAt),
          clientIp,
          requestId);
      return failure(403, "IP_NOT_ALLOWED", "来源 IP 不在该应用的白名单内", requestId);
    }

    // 配额：按 18.1.1.1 的配置逐窗口判，超了就是 429。
    // 🔴 「现在」取注入的时钟（生产 = 系统时钟）：配额窗口起点是按它截断出来的，
    //    截断有边界 —— 边界落在两次调用之间会让用量看起来被清零（见 ApiCallMonitorClockConfiguration）。
    var now = OffsetDateTime.now(clock);
    var quota = quotaDecision(identity.appId(), now);
    if (quota.exceeded()) {
      record(
          identity,
          endpoint,
          method,
          path,
          429,
          Outcome.RATE_LIMITED,
          "QUOTA_EXCEEDED",
          true,
          elapsedMs(startedAt),
          clientIp,
          requestId);
      return failure(
          429,
          "QUOTA_EXCEEDED",
          "超出 "
              + quota.windowType()
              + " 窗口的配额上限（"
              + quota.limitCount()
              + "，已用 "
              + quota.usedCount()
              + "）",
          requestId);
    }

    // 真正的业务动作（在租户事务内执行）。
    Object data;
    try {
      data = work.apply(new GatewaySession(identity, requestId, endpoint.code()));
    } catch (NotFound e) {
      // 「调用方要的东西不存在」：404 / NOT_FOUND —— 与内部错误分开记。
      record(
          identity,
          endpoint,
          method,
          path,
          404,
          Outcome.NOT_FOUND,
          "NOT_FOUND",
          false,
          elapsedMs(startedAt),
          clientIp,
          requestId);
      return failure(404, "NOT_FOUND", e.getMessage(), requestId);
    } catch (Exception e) {
      // 🔴 内部错误**也要落埋点**（500 / ERROR）：对端报障时凭 requestId 就能查到这一次。
      //    异常在这里被吃掉、不再抛出去 —— 抛出去会让事务回滚，埋点行也跟着没了。
      record(
          identity,
          endpoint,
          method,
          path,
          500,
          Outcome.ERROR,
          "INTERNAL_ERROR",
          false,
          elapsedMs(startedAt),
          clientIp,
          requestId);
      return failure(
          500, "INTERNAL_ERROR", "网关内部错误，请凭请求号 " + requestId + " 联系对接支持", requestId);
    }

    // 记用量（只记**通过**了配额判定的调用 —— 被限流挡下的没有消耗服务）。
    incrementUsage(identity.appId(), now);
    touchCredential(identity.credentialId());

    record(
        identity,
        endpoint,
        method,
        path,
        200,
        Outcome.SUCCESS,
        "",
        false,
        elapsedMs(startedAt),
        clientIp,
        requestId);
    return new GatewayResult(
        200,
        new ApiCallMonitorModels.ApiGatewayResponse(
            endpoint.code(), requestId, identity.appCode(), now, data));
  }

  // ================================================================ 凭证定位

  /** 跨租户凭证定位（**唯一的跨租户出口**，见 V414 的函数说明）。 */
  private List<Candidate> lookupCandidates(String apiKey) {
    var lookup = AppCredentialService.sha256Hex(apiKey == null ? "" : apiKey);
    var rows = sql.fetch("select * from platform_api_credential_lookup(?)", lookup);
    var result = new ArrayList<Candidate>();
    for (var row : rows) {
      result.add(
          new Candidate(
              row.get("tenant_id", UUID.class),
              row.get("app_id", UUID.class),
              row.get("credential_id", UUID.class),
              row.get("secret_digest", String.class),
              row.get("api_key_prefix", String.class),
              row.get("secret_hint", String.class),
              row.get("credential_status", String.class),
              row.get("credential_version", Integer.class),
              row.get("app_status", String.class),
              row.get("app_code", String.class),
              row.get("app_name", String.class)));
    }
    return result;
  }

  private static GatewayIdentity identityOf(Candidate candidate) {
    return new GatewayIdentity(
        candidate.tenantId(),
        candidate.appId(),
        candidate.credentialId(),
        candidate.appCode(),
        candidate.appName(),
        candidate.appStatus(),
        candidate.credentialStatus(),
        candidate.apiKeyPrefix(),
        candidate.secretHint(),
        candidate.credentialVersion());
  }

  // ================================================================ 范围 / IP / 配额

  /** 本应用被授予的「资源:操作」集合。 */
  private Set<String> scopesOf(UUID appId) {
    var rows = sql.fetch("select resource_code, action_code from platform_app_scope where app_id = ?", appId);
    var result = new java.util.HashSet<String>();
    for (var row : rows) {
      result.add(row.get("resource_code", String.class) + ":" + row.get("action_code", String.class));
    }
    return result;
  }

  /** 本应用的 IP 白名单（CIDR 列表）。 */
  private List<String> ipAllowlistOf(UUID appId) {
    return sql.fetch("select cidr from platform_app_ip_allowlist where app_id = ?", appId)
        .map(row -> row.get("cidr", String.class));
  }

  /**
   * 配额判定：逐窗口看（SECOND → MINUTE → HOUR → DAY → MONTH），返回**最紧**的那个越线窗口。
   *
   * <p>「没配 = 不限」（不是「限 0 次」）；被停用的窗口不参与判定 —— 与 18.1.1.1 同一口径。
   */
  private QuotaDecision quotaDecision(UUID appId, OffsetDateTime at) {
    var configured =
        sql.fetch("select window_type, limit_count, enabled from platform_app_quota where app_id = ?", appId);
    for (var window : AppCredentialService.WINDOWS) {
      var row =
          configured.stream()
              .filter(r -> window.name().equals(r.get("window_type", String.class)))
              .findFirst()
              .orElse(null);
      if (row == null) continue;
      if (!row.get("enabled", Boolean.class)) continue;
      var limit = row.get("limit_count", BigDecimal.class);
      var start = AppCredentialService.windowStart(window, at);
      var usage =
          sql.fetchOne(
              "select used_count from platform_app_quota_usage where app_id = ? and window_type = ?"
                  + " and window_started_at = ?::timestamptz",
              appId,
              window.name(),
              start);
      var used = usage == null ? BigDecimal.ZERO : usage.get("used_count", BigDecimal.class);
      if (used.compareTo(limit) >= 0) {
        return new QuotaDecision(true, window.name(), limit, used);
      }
    }
    return new QuotaDecision(false, "", null, null);
  }

  /** 记一次用量（只对**已配置且启用**的窗口记）。 */
  private void incrementUsage(UUID appId, OffsetDateTime at) {
    var configured =
        sql.fetch("select window_type, enabled from platform_app_quota where app_id = ?", appId);
    var now = OffsetDateTime.now(clock);
    for (var window : AppCredentialService.WINDOWS) {
      var row =
          configured.stream()
              .filter(r -> window.name().equals(r.get("window_type", String.class)))
              .findFirst()
              .orElse(null);
      if (row == null) continue;
      if (!row.get("enabled", Boolean.class)) continue;
      var start = AppCredentialService.windowStart(window, at);
      sql.execute(
          "insert into platform_app_quota_usage(id,tenant_id,app_id,window_type,window_started_at,"
              + " used_count,updated_at) values(?,?,?,?,?::timestamptz,1,?::timestamptz)"
              + " on conflict (tenant_id,app_id,window_type,window_started_at)"
              + " do update set used_count = platform_app_quota_usage.used_count + excluded.used_count,"
              + " updated_at = excluded.updated_at",
          UUID.randomUUID(),
          currentTenant(),
          appId,
          window.name(),
          start,
          now);
    }
  }

  /** 凭证最后使用时刻（18.1.1.1 的口径：真的用到了才更新）。 */
  private void touchCredential(UUID credentialId) {
    sql.execute(
        "update platform_app_credential set last_used_at = ?::timestamptz where id = ?",
        OffsetDateTime.now(clock),
        credentialId);
  }

  /**
   * 网关的「现在」（与配额窗口、凭证最后使用时刻**同一个**时钟）。
   *
   * <p>🔴 配额视图必须按**同一个**「现在」算窗口起点，否则视图显示的用量与网关实际
   * 判限流的那个窗口不是同一个 —— 对接方看到「还剩 10 次」而实际已经被挡下。
   */
  public OffsetDateTime now() {
    return OffsetDateTime.now(clock);
  }

  // ================================================================ 埋点落库

  /** 落一行调用埋点（**只追加**：迁移里的触发器钉死了改与删）。 */
  private void record(
      GatewayIdentity identity,
      ApiGatewayEndpoints.Endpoint endpoint,
      String method,
      String path,
      int statusCode,
      Outcome outcome,
      String errorCode,
      boolean rateLimited,
      BigDecimal durationMs,
      String clientIp,
      String requestId) {
    var now = sql.fetchOne("select clock_timestamp() as t").get(0, OffsetDateTime.class);
    sql.execute(
        "insert into platform_api_call_log(id,tenant_id,app_id,credential_id,endpoint_code,"
            + " http_method,request_path,status_code,outcome,error_code,rate_limited,duration_ms,"
            + " client_ip,request_id,called_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)",
        UUID.randomUUID(),
        identity.tenantId(),
        identity.appId(),
        identity.credentialId(),
        endpoint == null ? "UNKNOWN" : endpoint.code(),
        method,
        path.length() > 200 ? path.substring(0, 200) : path,
        statusCode,
        outcome.name(),
        errorCode == null ? "" : errorCode,
        rateLimited,
        durationMs,
        clientIp == null ? "" : clientIp,
        requestId == null ? "" : requestId,
        now);
  }

  // ================================================================ 网关回显视图

  /** ping：应用身份回显（**不含任何密钥**）。 */
  public Object pingView(GatewayIdentity identity) {
    return new ApiCallMonitorModels.ApiGatewayPingView(
        identity.appCode(), identity.appName(), identity.credentialVersion(), identity.apiKeyPrefix());
  }

  /**
   * quota：各窗口的配额状态。
   *
   * <p>🔴 真读 18.1.1.1 的 {@code platform_app_quota}（配置）与 {@code platform_app_quota_usage}
   * （用量）—— 没配过的窗口显示「未配置 = 不限」，**不是**「限 0 次」。
   *
   * <p>🔴 窗口起点按**注入的时钟**算（与判限流时同一个）—— 视图与执行必须是同一个窗口。
   */
  public Object quotaView(UUID appId) {
    var at = OffsetDateTime.now(clock);
    var configured =
        sql.fetch("select window_type, limit_count, enabled from platform_app_quota where app_id = ?", appId);
    var windows = new ArrayList<ApiCallMonitorModels.ApiGatewayQuotaWindow>();
    for (var window : AppCredentialService.WINDOWS) {
      var row =
          configured.stream()
              .filter(r -> window.name().equals(r.get("window_type", String.class)))
              .findFirst()
              .orElse(null);
      var start = AppCredentialService.windowStart(window, at);
      var end = AppCredentialService.windowEnd(window, start);
      if (row == null) {
        windows.add(
            new ApiCallMonitorModels.ApiGatewayQuotaWindow(
                window.name(),
                window.label(),
                false,
                false,
                null,
                BigDecimal.ZERO,
                null,
                false,
                start,
                end));
        continue;
      }
      var limit = row.get("limit_count", BigDecimal.class);
      var enabled = row.get("enabled", Boolean.class);
      var usage =
          sql.fetchOne(
              "select used_count from platform_app_quota_usage where app_id = ? and window_type = ?"
                  + " and window_started_at = ?::timestamptz",
              appId,
              window.name(),
              start);
      var used = usage == null ? BigDecimal.ZERO : usage.get("used_count", BigDecimal.class);
      var remaining = limit.subtract(used).max(BigDecimal.ZERO).setScale(6, RoundingMode.HALF_UP);
      windows.add(
          new ApiCallMonitorModels.ApiGatewayQuotaWindow(
              window.name(),
              window.label(),
              true,
              enabled,
              limit,
              used,
              remaining,
              enabled && used.compareTo(limit) >= 0,
              start,
              end));
    }
    return new ApiCallMonitorModels.ApiGatewayQuotaView(windows);
  }

  /** scopes：被授予的资源 × 操作（真读 18.1.1.1 的授权范围）。 */
  public Object scopeView(UUID appId) {
    var rows =
        sql.fetch(
            "select resource_code, action_code from platform_app_scope where app_id = ?"
                + " order by resource_code, action_code",
            appId);
    var scopes = new ArrayList<String>();
    for (var row : rows) {
      scopes.add(row.get("resource_code", String.class) + ":" + row.get("action_code", String.class));
    }
    return new ApiCallMonitorModels.ApiGatewayScopeView(scopes);
  }

  /** credential：当前这一版凭证的元信息（**没有明文**，库里也没有）。 */
  public Object credentialView(UUID credentialId) {
    var row =
        sql.fetchOne(
            "select version, status, api_key_prefix, secret_hint, issued_at, expires_at, last_used_at,"
                + " revoked_at from platform_app_credential where id = ?",
            credentialId);
    if (row == null) return null;
    var status = row.get("status", String.class);
    return new ApiCallMonitorModels.ApiGatewayCredentialView(
        row.get("version", Integer.class),
        status,
        AppCredentialService.credentialStatusLabel(status),
        row.get("api_key_prefix", String.class),
        row.get("secret_hint", String.class),
        row.get("issued_at", OffsetDateTime.class),
        row.get("expires_at", OffsetDateTime.class),
        row.get("last_used_at", OffsetDateTime.class),
        row.get("revoked_at", OffsetDateTime.class));
  }

  /** calls：本应用最近的调用（**只看得到本应用的**，租户内的别的应用看不到）。 */
  public List<ApiCallMonitorModels.ApiGatewayCallView> recentCalls(UUID appId, int limit) {
    var rows =
        sql.fetch(
            "select endpoint_code, http_method, request_path, status_code, outcome, error_code,"
                + " rate_limited, duration_ms, request_id, called_at from platform_api_call_log"
                + " where app_id = ? order by called_at desc limit ?",
            appId,
            limit);
    return rows.map(ApiGatewayService::toCallView);
  }

  /** call：按请求号取一次调用（**只限本应用**；取不到返回 null）。 */
  public ApiCallMonitorModels.ApiGatewayCallView callByRequestId(UUID appId, String requestId) {
    var row =
        sql.fetchOne(
            "select endpoint_code, http_method, request_path, status_code, outcome, error_code,"
                + " rate_limited, duration_ms, request_id, called_at from platform_api_call_log"
                + " where app_id = ? and request_id = ? order by called_at desc limit 1",
            appId,
            requestId);
    return row == null ? null : toCallView(row);
  }

  private static ApiCallMonitorModels.ApiGatewayCallView toCallView(org.jooq.Record row) {
    var outcome = Outcome.parse(row.get("outcome", String.class));
    return new ApiCallMonitorModels.ApiGatewayCallView(
        row.get("request_id", String.class),
        row.get("endpoint_code", String.class),
        row.get("http_method", String.class),
        row.get("request_path", String.class),
        row.get("status_code", Integer.class),
        outcome == null ? row.get("outcome", String.class) : outcome.name(),
        outcome == null ? "" : outcome.label(),
        row.get("error_code", String.class),
        row.get("rate_limited", Boolean.class),
        row.get("duration_ms", BigDecimal.class),
        row.get("called_at", OffsetDateTime.class));
  }

  // ================================================================ 工具

  /** 失败响应体（真实状态码 + 机器可读的失败码 + 请求号）。 */
  private static GatewayResult failure(int statusCode, String error, String message, String requestId) {
    return new GatewayResult(
        statusCode, new ApiCallMonitorModels.ApiGatewayError(error, message, requestId));
  }

  /** 已耗毫秒（数量，6 位小数，禁浮点）。 */
  static BigDecimal elapsedMs(long startedAtNanos) {
    return BigDecimal.valueOf(System.nanoTime() - startedAtNanos)
        .divide(BigDecimal.valueOf(1_000_000L), 6, RoundingMode.HALF_UP);
  }

  /** 当前事务里的租户（由 {@link TenantTransactions} 绑定）。 */
  private UUID currentTenant() {
    var raw = sql.fetchOne("select current_setting('app.current_tenant', true) as t").get(0, String.class);
    if (raw == null || raw.isBlank()) throw new IllegalStateException("缺少可信租户上下文");
    return UUID.fromString(raw);
  }
}
