package com.starcon.channel.oauth;

import static com.starcon.channel.ChannelModels.*;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.channel.AccessService;
import com.starcon.channel.secrets.SecretStore;
import com.starcon.channel.vendor.OAuthProtocol;
import com.starcon.channel.vendor.OAuthProtocol.Authorization;
import com.starcon.channel.vendor.OAuthProtocol.AuthorizeRequest;
import com.starcon.channel.vendor.OAuthProtocol.OAuthProfile;
import com.starcon.channel.vendor.OAuthProtocol.TokenRequest;
import com.starcon.channel.vendor.PlatformException;
import com.starcon.channel.vendor.PlatformOAuthAdapter;
import com.starcon.channel.vendor.VendorRegistry;
import com.starcon.tenancy.Ids;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
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
 * 2.2.1.2 授权向导的后端实现。
 *
 * <p>六步各自落在哪：
 * <ol>
 *   <li>选平台 + 2.选账号 + 3.授权跳转 → {@link #start}（生成会话与 state，返回可直接跳转的 URL）
 *   <li>4.回调校验 → {@link #handleCallback}（换令牌、判租户、判绑定冲突、落 credential_ref）
 *   <li>5.确认店铺范围 → {@link #candidates} / {@link #selectShops}
 *   <li>6.完成 → {@link #confirm}（建 channel_connection 与 shop）
 * </ol>
 *
 * <p><b>租户归属</b>：只有 {@link #handleCallback} 会脱离请求身份，其余端点都走正常的
 * {@code access.require} + RLS。回调的租户来自 {@code oauth_callback_bootstrap(state)}——
 * 即"授权发起时记录的 state → 会话"绑定关系，请求头（含伪造的 X-Tenant-Id）在这里完全不参与。
 */
@Service
public class OAuthAuthorizationService {
  /** 2.2.1.2：授权发起会话的有效期。短到能挡住"发起后隔天回来点确认"，长到够用户走完平台授权页。 */
  private static final Duration DEFAULT_TTL = Duration.ofMinutes(15);

  private final DSLContext sql;
  private final AccessService access;
  private final VendorRegistry vendors;
  private final PlatformOAuthProperties config;
  private final SecretStore secrets;
  private final ObjectMapper json;
  private final SecureRandom random = new SecureRandom();

  public OAuthAuthorizationService(
      DSLContext sql,
      AccessService access,
      VendorRegistry vendors,
      PlatformOAuthProperties config,
      SecretStore secrets,
      ObjectMapper json) {
    this.sql = sql;
    this.access = access;
    this.vendors = vendors;
    this.config = config;
    this.secrets = secrets;
    this.json = json;
  }

  // ------------------------------------------------------------------
  // 第 1–3 步：发起授权
  // ------------------------------------------------------------------

  /**
   * 2.2.1.2 发起授权：登记会话并返回跳转地址。
   *
   * <p>幂等由 {@code idempotency_key} 保证：同租户重复提交同一个键返回同一个会话，
   * 用户在页面上连点两次"去授权"不会产生两条待授权会话，也不会让平台侧多弹一次授权页。
   */
  public OAuthStartView start(AccessService.Access actor, OAuthStartInput input) {
    String platformCode = input.platformCode().trim().toUpperCase(Locale.ROOT);
    UUID platformId = requirePlatform(platformCode);
    OAuthProfile profile = requireProfile(platformCode);
    PlatformOAuthAdapter adapter = vendors.find(platformCode);
    if (adapter == null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "平台 " + platformCode + " 尚未接入授权向导，请先在平台注册表中登记授权端点");
    PlatformOAuthProperties.PlatformClient client = config.clientFor(platformCode);
    if (client == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, config.missingCredentialHint(platformCode));

    String idempotencyKey = input.idempotencyKey().trim();
    Record existing =
        sql.fetchOne(
            "select id,state_token,redirect_path,requested_scopes,expires_at,status"
                + " from oauth_authorization_session where tenant_id=? and idempotency_key=?",
            actor.tenantId(),
            idempotencyKey);
    if (existing != null) {
      // 幂等命中：已经完成就直接告诉调用方结果，不重新跳转
      if ("COMPLETED".equals(existing.get("status", String.class)))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "该授权已完成，请从连接列表查看结果");
      if (existing.get("expires_at", Instant.class).isBefore(Instant.now()))
        throw new ResponseStatusException(HttpStatus.CONFLICT, "上一次授权会话已过期，请换一个幂等键重新发起");
      return new OAuthStartView(
          existing.get("id", UUID.class),
          platformCode,
          platformName(platformId),
          authorizeUrlFor(platformCode, adapter, profile, client, existing, input.loginHint()),
          existing.get("requested_scopes", String.class),
          existing.get("expires_at", Instant.class),
          existing.get("status", String.class));

    }

    String loginHint = input.loginHint() == null ? "" : input.loginHint().trim();
    List<String> scopes = requestedScopes(input.scopes(), profile);
    String state = randomToken(32);
    String verifier = profile.codeChallengeMethod() == OAuthProtocol.CodeChallengeMethod.S256 ? randomToken(48) : null;
    String challenge =
        verifier == null ? null : base64Url(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
    String redirectUri = config.publicBaseUrl() + "/channel/oauth/callback";
    String requestScopes = adapter.joinScopes(scopes);

    String reject = adapter.rejectAuthorize(profile, new AuthorizeRequest(
        client.clientId(), redirectUri, state, loginHint, scopes, challenge, profile.codeChallengeMethod()));
    if (reject != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, reject);

    Instant expiresAt = Instant.now().plus(config.stateTtl().isZero() || config.stateTtl().isNegative()
        ? DEFAULT_TTL : config.stateTtl());
    UUID id = Ids.next();
    // 发起人快照：回调没有 bearer，租户与权限都要从这里恢复。
    // 存 grants 而不是"用户 id"是必要的——数据范围策略要靠 app.grants 判定，
    // 只存用户 id 会让回调创建的店铺绕过 2.3.2.2 的可见性约束。
    sql.execute(
        "insert into oauth_authorization_session(id,tenant_id,platform_id,state_token,code_verifier,"
            + "initiated_by,actor_department_id,actor_grants,login_hint,redirect_path,requested_scopes,"
            + "idempotency_key,expires_at)"
            + " values (?,?,?,?,?,?,?,cast(? as jsonb),?,?,?,?,?)",
        id,
        actor.tenantId(),
        platformId,
        state,
        verifier,
        actor.userId(),
        actor.departmentId(),
        toJson(actor.grants()),
        loginHint,
        "/channel/oauth/callback",
        requestScopes,
        idempotencyKey,
        Timestamps.at(expiresAt));

    audit(actor, id, "channel.oauth.start");
    String authorizeUrl = adapter.authorizeUri(
            profile,
            new AuthorizeRequest(client.clientId(), redirectUri, state, loginHint, scopes, challenge, profile.codeChallengeMethod()))
        .toString();
    return new OAuthStartView(id, platformCode, platformName(platformId), authorizeUrl, requestScopes, expiresAt, "PENDING");
  }

  /**
   * 用已存在的会话重建跳转地址。
   *
   * <p>幂等重放时不重新签发 state：复用原 state 才能让平台跳回来时命中同一个会话，
   * 换一个新 state 会让第一次授权作废、第二次回调落空。
   */
  private String authorizeUrlFor(
      String platformCode,
      PlatformOAuthAdapter adapter,
      OAuthProfile profile,
      PlatformOAuthProperties.PlatformClient client,
      Record session,
      String loginHint) {
    UUID sessionId = session.get("id", UUID.class);
    String state = session.get("state_token", String.class);
    Record stored =
        sql.fetchOne("select code_verifier,login_hint from oauth_authorization_session where id=?", sessionId);
    String verifier = stored.get("code_verifier", String.class);
    String challenge = verifier == null ? null : base64Url(sha256(verifier.getBytes(StandardCharsets.UTF_8)));
    String hint = loginHint == null || loginHint.isBlank() ? stored.get("login_hint", String.class) : loginHint.trim();
    String redirectUri = config.publicBaseUrl() + session.get("redirect_path", String.class);
    return adapter.authorizeUri(
            profile,
            new AuthorizeRequest(
                client.clientId(),
                redirectUri,
                state,
                hint,
                splitScopes(session.get("requested_scopes", String.class)),
                challenge,
                profile.codeChallengeMethod()))
        .toString();
  }

  // ------------------------------------------------------------------
  // 第 4 步：回调校验
  // ------------------------------------------------------------------

  /**
   * 2.2.1.2 平台回调。
   *
   * <p>这是整个模块里唯一不接受客户端身份的地方。租户与权限都从 {@code state} 反查：
   * {@code oauth_callback_bootstrap} 只对 PENDING 且未过期的 state 返回发起人快照，
   * 拿不到就是无效请求——伪造的 {@code X-Tenant-Id} 在这条路径上根本没有读取它的代码。
   *
   * <p>state 一次性：进入本方法后立刻把状态从 PENDING 改掉，重放同一个回调只会得到
   * "授权链接无效或已过期"，这就是 OAuth state 的 CSRF 防护。
   */
  public OAuthSessionView handleCallback(String code, String state, String error, String errorDescription) {
    if (state == null || state.isBlank())
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少 state，无法确定这次授权属于哪个租户");
    Bootstrap boot = bootstrap(state);
    if (boot == null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "授权链接无效或已过期（state 不存在、已被使用或已过期），请重新发起授权");
    // 回到发起人的权限上下文：这是"授权由谁发起，就只能以谁的身份写入"的保证
    AccessService.Access initiator =
        new AccessService.Access(
            boot.initiatedBy(), boot.tenantId(), "OAuth", null, boot.actorDepartmentId(), boot.permissions(), boot.grants());
    return access.run(initiator, "channel.connection.create", () -> processCallback(boot, code, error, errorDescription));
  }

  private OAuthSessionView processCallback(Bootstrap boot, String code, String error, String errorDescription) {
    UUID sessionId = one(UUID.class, "select id from oauth_authorization_session where state_token=?", boot.state());
    Record session = sql.fetchOne(
        "select id,platform_id,code_verifier,login_hint,redirect_path,requested_scopes"
            + " from oauth_authorization_session where id=?",
        sessionId);
    UUID platformId = session.get("platform_id", UUID.class);
    String platformCode = platformCode(platformId);
    OAuthProfile profile = requireProfile(platformCode);
    PlatformOAuthAdapter adapter = vendors.find(platformCode);
    PlatformOAuthProperties.PlatformClient client = config.clientFor(platformCode);

    // 先消费掉 state：并发的重放请求即使同时进来，也只有一个能通过下面的乐观更新
    int claimed =
        sql.execute(
            "update oauth_authorization_session set status='AUTHORIZED',updated_at=now()"
                + " where id=? and status='PENDING'",
            sessionId);
    if (claimed == 0)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该授权回调已被处理过，请重新发起授权");

    if (error != null && !error.isBlank())
      return fail(sessionId, adapter.translatePlatformError(error, errorDescription));
    if (code == null || code.isBlank())
      return fail(
          sessionId,
          new OAuthProtocol.OAuthFailure("PLATFORM_REJECTED", "平台没有返回授权码，请重新发起授权"));
    if (client == null)
      return fail(
          sessionId,
          new OAuthProtocol.OAuthFailure("CREDENTIAL_REJECTED", config.missingCredentialHint(platformCode)));

    Authorization authorization;
    try {
      authorization =
          adapter.authorize(
              profile,
              new TokenRequest(
                  profile.tokenUrl(),
                  profile.tokenRequestStyle(),
                  profile.tokenAuthMethod(),
                  client.clientId(),
                  client.clientSecret(),
                  code,
                  config.publicBaseUrl() + session.get("redirect_path", String.class),
                  session.get("code_verifier", String.class),
                  session.get("login_hint", String.class),
                  Map.of()));
    } catch (PlatformException e) {
      return fail(sessionId, e.failure());
    } catch (RuntimeException e) {
      return fail(
          sessionId,
          new OAuthProtocol.OAuthFailure("TOKEN_EXCHANGE_FAILED", "授权失败，请重新发起授权"));
    }

    List<String> granted = normalizeScopes(authorization.grantedScopes(), session.get("requested_scopes", String.class));
    String missing = missingScopes(adapter.minimumScopes(), granted);
    if (missing != null)
      return fail(
          sessionId,
          new OAuthProtocol.OAuthFailure(
              "INSUFFICIENT_SCOPE",
              "平台授予的权限范围缺少 " + missing + "，无法完成订单与商品同步。请在平台后台补齐权限后重新授权。"));

    String accountId = authorization.accountId().trim();
    // 同一平台账号只能绑定一个租户：这是蓝图 2.2.1.2「关系」段的硬要求。
    // 判定放在写库之前，因为唯一索引只会在插入时才炸，那时用户已经白跳了一次授权页。
    UUID owner = one(UUID.class, "select oauth_account_owner(?,?)", platformId, accountId);
    if (owner != null && !owner.equals(boot.tenantId()))
      return fail(
          sessionId,
          new OAuthProtocol.OAuthFailure(
              "ACCOUNT_BOUND_BY_OTHER_TENANT",
              "该平台商家账号已被其他租户绑定，无法重复绑定。请联系管理员解绑，或改用未被占用的商家账号。"));
    if (accountId.equalsIgnoreCase(session.get("login_hint", String.class)) == false
        && adapter.requiresShopDomain())
      return fail(
          sessionId,
          new OAuthProtocol.OAuthFailure(
              "PLATFORM_REJECTED",
              "平台返回的店铺（" + accountId + "）与指定的店铺域名（" + session.get("login_hint", String.class) + "）不一致，请确认没有授权错店铺。"));

    // 令牌明文到此为止：先落密钥管理器，再把引用写进 credential_ref
    String secretPath =
        "channel/" + boot.tenantId() + "/" + platformCode.toLowerCase(Locale.ROOT) + "/" + accountId;
    String reference = secrets.write(secretPath, authorization.accessToken());
    UUID credentialId = upsertCredential(boot.tenantId(), reference, granted, authorization.expiresAt());

    java.sql.Timestamp authorizedAt = Timestamps.at(Instant.now());
    java.sql.Timestamp expiresAt = Timestamps.at(authorization.expiresAt());
    sql.execute(
        "update oauth_authorization_session set granted_scopes=?,authorized_account=?,authorized_account_name=?,"
            + " credential_id=?,updated_at=now() where id=?",
        String.join(" ", granted),
        accountId,
        authorization.accountName() == null ? "" : authorization.accountName(),
        credentialId,
        sessionId);
    // 凭据表的时间列由 2.2.1.3 的 credential_window 约束校验，这里必须显式给合法窗口
    sql.execute(
        "update credential_ref set granted_scopes=?,authorized_at=?,expires_at=?,status=?,version=version+1,updated_at=now()"
            + " where tenant_id=? and id=?",
        String.join(" ", granted),
        authorizedAt,
        expiresAt,
        authorization.expiresAt() != null && authorization.expiresAt().isBefore(Instant.now()) ? "EXPIRED" : "ACTIVE",
        boot.tenantId(),
        credentialId);

    // 店铺候选：确认前不算业务事实
    int deleted =
        sql.execute("delete from oauth_shop_candidate where tenant_id=? and session_id=?", boot.tenantId(), sessionId);
    if (deleted < 0) throw new IllegalStateException("候选清理异常");
    for (var shop : authorization.shops()) {
      sql.execute(
          "insert into oauth_shop_candidate(id,tenant_id,session_id,platform_id,external_shop_id,shop_name,"
              + "default_marketplace_code) values (?,?,?,?,?,?,?)"
              + " on conflict (session_id,external_shop_id) do update set shop_name=excluded.shop_name,"
              + " default_marketplace_code=excluded.default_marketplace_code",
          Ids.next(),
          boot.tenantId(),
          sessionId,
          platformId,
          shop.externalShopId(),
          shop.name() == null ? "" : shop.name(),
          shop.marketplaceCode() == null ? "" : shop.marketplaceCode());
    }
    audit(boot.initiatedBy(), boot.tenantId(), sessionId, "channel.oauth.callback");
    return sessionView(sessionId);
  }

  // ------------------------------------------------------------------
  // 第 5 步：确认店铺范围
  // ------------------------------------------------------------------

  public List<OAuthShopCandidateView> candidates(AccessService.Access actor, UUID sessionId) {
    requireSession(actor, sessionId);
    return sql.fetch(
            "select id,external_shop_id,shop_name,default_marketplace_code from oauth_shop_candidate"
                + " where tenant_id=? and session_id=? order by shop_name,external_shop_id",
            actor.tenantId(), sessionId)
        .stream()
        .map(
            r ->
                new OAuthShopCandidateView(
                    r.get("id", UUID.class),
                    r.get("external_shop_id", String.class),
                    r.get("shop_name", String.class),
                    r.get("default_marketplace_code", String.class),
                    true))
        .toList();
  }

  // ------------------------------------------------------------------
  // 第 6 步：确认并完成
  // ------------------------------------------------------------------

  /**
   * 2.2.1.2 确认店铺范围并完成授权。
   *
   * <p>建 {@code channel_connection} + {@code shop}（+ 站点），把向导产物交给下游的 2.3.1 店铺档案。
   * 幂等：重复提交同一个 {@code idempotency_key} 返回第一次的结果，不会建出第二组店铺。
   */
  public OAuthCompletedView confirm(AccessService.Access actor, UUID sessionId, OAuthConfirmInput input) {
    Record session = requireSession(actor, sessionId);
    String status = session.get("status", String.class);
    if ("COMPLETED".equals(status)) return completedView(actor.tenantId(), sessionId);
    if (!"AUTHORIZED".equals(status))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该授权尚未完成平台回调（或已失败），请先完成授权再确认店铺范围");
    String account = session.get("authorized_account", String.class);
    if (account == null || account.isBlank())
      throw new ResponseStatusException(HttpStatus.CONFLICT, "授权回调没有带回平台账号，无法完成连接");
    UUID credentialId = session.get("credential_id", UUID.class);
    if (credentialId == null)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "授权回调没有写入凭据引用，无法完成连接");

    UUID platformId = session.get("platform_id", UUID.class);
    String platformCode = platformCode(platformId);
    // 判定归属要重新做一次：从授权发起到确认之间，账号可能刚被另一个租户绑走
    UUID owner = one(UUID.class, "select oauth_account_owner(?,?)", platformId, account);
    if (owner != null && !owner.equals(actor.tenantId()))
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "该平台商家账号已被其他租户绑定，无法重复绑定。请联系管理员解绑。");

    List<Record> chosen = selectedCandidates(actor.tenantId(), sessionId, input.shopIds());
    if (chosen.isEmpty())
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "请至少确认一个店铺：授权成功但不纳管任何店铺会留下一条永不参与同步的空连接");

    UUID connectionId = Ids.next();
    String displayName = session.get("authorized_account_name", String.class);
    sql.execute(
        "insert into channel_connection(id,tenant_id,platform_id,credential_id,authorized_account,display_name,status)"
            + " values (?,?,?,?,?,?,?)",
        connectionId,
        actor.tenantId(),
        platformId,
        credentialId,
        account,
        displayName == null || displayName.isBlank() ? account : displayName,
        credentialStatus(session.get("credential_expires_at", Instant.class)));

    String prefix = input.shopCodePrefix() == null || input.shopCodePrefix().isBlank()
        ? platformCode.substring(0, Math.min(4, platformCode.length()))
        : input.shopCodePrefix().trim();
    List<UUID> shopIds = new ArrayList<>();
    for (Record candidate : chosen) {
      String externalId = candidate.get("external_shop_id", String.class);
      UUID shopOwner = one(UUID.class, "select oauth_shop_owner(?,?)", platformId, externalId);
      if (shopOwner != null && !shopOwner.equals(actor.tenantId()))
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "平台店铺 " + externalId + " 已被其他租户纳管，无法重复纳管。请取消该店铺或联系管理员解绑。");
      UUID shopId = Ids.next();
      String code = uniqueShopCode(actor.tenantId(), prefix, externalId);
      sql.execute(
          "insert into shop(id,tenant_id,platform_id,connection_id,code,name,platform_shop_id,operating_mode)"
              + " values (?,?,?,?,?,?,?,?)",
          shopId,
          actor.tenantId(),
          platformId,
          connectionId,
          code,
          candidate.get("shop_name", String.class),
          externalId,
          // 2.3.2.1：全托管平台的经营模式是平台决定的硬约束，不让用户在这里选成自发货
          "TEMU".equals(platformCode) ? "FULL_CONSIGNMENT" : "SELF_FULFILLED");
      linkMarketplace(actor.tenantId(), platformId, shopId, candidate.get("default_marketplace_code", String.class));
      shopIds.add(shopId);
    }
    sql.execute(
        "update oauth_authorization_session set status='COMPLETED',connection_id=?,completed_at=now(),updated_at=now()"
            + " where id=?",
        connectionId,
        sessionId);
    audit(actor, sessionId, "channel.oauth.complete");
    return new OAuthCompletedView(
        sessionId, connectionId, credentialId, List.copyOf(shopIds), shopIds.size(), "/channels?section=shops");
  }

  /** 2.2.1.2 放弃本次授权：state 立即作废，之后平台再回调也换不到租户。 */
  public void cancel(AccessService.Access actor, UUID sessionId) {
    // 先确认会话是自己的：别人的会话与"不在待授权状态"必须给出不同答案，
    // 否则跨租户探测者能靠 404/409 的差别看出某个 id 是否存在。
    requireSession(actor, sessionId);
    int cancelled =
        sql.execute(
            "update oauth_authorization_session set status='CANCELLED',updated_at=now()"
                + " where tenant_id=? and id=? and status='PENDING'",
            actor.tenantId(), sessionId);
    if (cancelled == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "该授权已不在待授权状态，无需取消");
    audit(actor, sessionId, "channel.oauth.cancel");
  }

  /**
   * 平台回调完成后回到前端的地址。
   *
   * <p>只带 sessionId，不带任何凭据或令牌：前端拿到后自己去轮询会话状态，
   * 那些请求仍然带着正常的登录态，走标准权限判定。
   */
  public String uiReturnUrl(UUID sessionId, String status) {
    char sep = config.uiReturnUrl().contains("?") ? '&' : '?';
    return config.uiReturnUrl() + sep + "oauthSession=" + sessionId + "&oauthStatus=" + status;
  }

  public OAuthSessionView session(AccessService.Access actor, UUID sessionId) {
    requireSession(actor, sessionId);
    return sessionView(sessionId);
  }

  // ------------------------------------------------------------------
  // 视图
  // ------------------------------------------------------------------

  private OAuthSessionView sessionView(UUID sessionId) {
    Record r =
        sql.fetchOne(
            "select s.id,p.code platform_code,p.name platform_name,s.status,s.failure_code,s.failure_message,"
                + " s.authorized_account,s.authorized_account_name,s.requested_scopes,s.granted_scopes,"
                + " s.connection_id,s.credential_id,s.expires_at,s.completed_at,"
                + " (select count(*) from oauth_shop_candidate c where c.tenant_id=s.tenant_id and c.session_id=s.id) candidates,"
                + " (select count(*) from shop sh where sh.tenant_id=s.tenant_id and sh.connection_id=s.connection_id) selected"
                + " from oauth_authorization_session s join platform p on p.id=s.platform_id where s.id=?",
            sessionId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权会话不存在");
    return new OAuthSessionView(
        r.get("id", UUID.class),
        r.get("platform_code", String.class),
        r.get("platform_name", String.class),
        r.get("status", String.class),
        r.get("failure_code", String.class),
        r.get("failure_message", String.class),
        r.get("authorized_account", String.class),
        r.get("authorized_account_name", String.class),
        r.get("requested_scopes", String.class),
        r.get("granted_scopes", String.class),
        r.get("connection_id", UUID.class),
        r.get("credential_id", UUID.class),
        r.get("candidates", Integer.class),
        r.get("selected", Integer.class),
        r.get("expires_at", Instant.class),
        r.get("completed_at", Instant.class));
  }

  private OAuthCompletedView completedView(UUID tenantId, UUID sessionId) {
    Record r =
        sql.fetchOne(
            "select connection_id,credential_id from oauth_authorization_session where tenant_id=? and id=?",
            tenantId, sessionId);
    List<UUID> shopIds =
        sql.fetch("select id from shop where tenant_id=? and connection_id=? order by created_at", tenantId, r.get("connection_id", UUID.class))
            .stream()
            .map(x -> x.get("id", UUID.class))
            .toList();
    return new OAuthCompletedView(
        sessionId,
        r.get("connection_id", UUID.class),
        r.get("credential_id", UUID.class),
        shopIds,
        shopIds.size(),
        "/channels?section=shops");
  }

  // ------------------------------------------------------------------
  // 内部
  // ------------------------------------------------------------------

  /** state → 发起人快照。SECURITY DEFINER 只对 PENDING 且未过期的 state 放行。 */
  private Bootstrap bootstrap(String state) {
    // 用 one() 而不是 fetchValue(String, Class, ...)：后者会匹配到 (String, Object...) 重载，
    // 把 Class 本身当成一个绑定值，占位符个数与参数个数对不上，报的却是 bad SQL grammar。
    // 取 String 而不是 Object：jsonb → Object 的 jOOQ 转换在 PGobject 上不可靠，取文本最稳。
    String raw = one(String.class, "select oauth_callback_bootstrap(?)", state);
    if (raw == null) return null;
    try {
      Map<String, Object> row = json.readValue(raw, new TypeReference<Map<String, Object>>() {});
      return new Bootstrap(
          state, uuid(row.get("tenantId")), uuid(row.get("initiatedBy")),
          uuid(row.get("actorDepartmentId")), fromJson(row.get("actorGrants")));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("授权引导快照解析失败", e);
    }
  }

  /**
   * jsonb 里的 UUID 是 JSON 字符串，Jackson 反序列化成 String 而不是 UUID。
   *
   * <p>直接强转会在回调最前面抛 ClassCastException，用户看到的是"服务出错了"而不是
   * "授权成功"——所以这里显式转一次，也顺手兼容已经是 UUID 的情况。
   */
  private static UUID uuid(Object value) {
    return value == null ? null : (value instanceof UUID id ? id : UUID.fromString(value.toString()));
  }

  private record Bootstrap(
      String state, UUID tenantId, UUID initiatedBy, UUID actorDepartmentId, List<AccessService.Grant> grants) {
    List<String> permissions() {
      return grants == null
          ? List.of()
          : grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList();
    }
  }

  private OAuthSessionView fail(UUID sessionId, OAuthProtocol.OAuthFailure failure) {
    sql.execute(
        "update oauth_authorization_session set status='FAILED',failure_code=?,failure_message=?,updated_at=now()"
            + " where id=?",
        failure.code(), failure.message(), sessionId);
    return sessionView(sessionId);
  }

  private OAuthProfile requireProfile(String platformCode) {
    Record r =
        sql.fetchOne(
            "select authorize_url,token_url,account_info_url,scope_param,login_hint_param,account_prompt_param,"
                + "account_prompt_value,token_auth_method,token_request_style,code_challenge_method,default_scopes,"
                + "token_access_field,token_refresh_field,token_expires_field,token_scope_field,"
                + "account_id_field,account_name_field,shops_field,shop_id_field,shop_name_field,shop_marketplace_field,"
                + "extra_authorize_params from platform_oauth_profile"
                + " join platform p on p.id=platform_oauth_profile.platform_id where p.code=?",
            platformCode);
    if (r == null)
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "平台 " + platformCode + " 尚未登记授权端点，请先在平台注册表中补齐 OAuth 配置");
    Map<String, String> extra = new LinkedHashMap<>();
    Object raw = r.get("extra_authorize_params");
    if (raw != null) {
      try {
        Map<String, Object> parsed = json.readValue(raw.toString(), new TypeReference<Map<String, Object>>() {});
        parsed.forEach((k, v) -> extra.put(k, v == null ? "" : v.toString()));
      } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
        throw new IllegalStateException("授权静态参数 JSON 解析失败", e);
      }
    }
    return new OAuthProfile(
        platformCode,
        r.get("authorize_url", String.class),
        r.get("token_url", String.class),
        r.get("account_info_url", String.class),
        r.get("scope_param", String.class),
        r.get("login_hint_param", String.class),
        r.get("account_prompt_param", String.class),
        r.get("account_prompt_value", String.class),
        OAuthProtocol.TokenAuthMethod.valueOf(r.get("token_auth_method", String.class)),
        OAuthProtocol.TokenRequestStyle.valueOf(r.get("token_request_style", String.class)),
        OAuthProtocol.CodeChallengeMethod.valueOf(r.get("code_challenge_method", String.class)),
        r.get("default_scopes", String.class),
        r.get("token_access_field", String.class),
        r.get("token_refresh_field", String.class),
        r.get("token_expires_field", String.class),
        r.get("token_scope_field", String.class),
        r.get("account_id_field", String.class),
        r.get("account_name_field", String.class),
        r.get("shops_field", String.class),
        r.get("shop_id_field", String.class),
        r.get("shop_name_field", String.class),
        r.get("shop_marketplace_field", String.class),
        extra);
  }

  private UUID requirePlatform(String platformCode) {
    UUID id = one(UUID.class, "select id from platform where code=?", platformCode);
    if (id == null)
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "平台不存在或尚未开放接入：" + platformCode);
    return id;
  }

  private String platformCode(UUID platformId) {
    String code = one(String.class, "select code from platform where id=?", platformId);
    if (code == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "平台不存在");
    return code;
  }

  private String platformName(UUID platformId) {
    String name = one(String.class, "select name from platform where id=?", platformId);
    return name == null ? "" : name;
  }

  private Record requireSession(AccessService.Access actor, UUID sessionId) {
    Record r =
        sql.fetchOne(
            "select id,platform_id,status,authorized_account,authorized_account_name,credential_id,"
                + "(select expires_at from credential_ref cr where cr.tenant_id=s.tenant_id and cr.id=s.credential_id)"
                + " credential_expires_at"
                + " from oauth_authorization_session s where tenant_id=? and id=?",
            actor.tenantId(), sessionId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "授权会话不存在");
    return r;
  }

  /**
   * 选中的店铺候选。
   *
   * <p>{@code null}（请求里没带 shopIds）表示"全部候选"，对应用户没动过勾选；
   * <b>显式传空数组</b>表示"一个都不选"，必须报错——授权成功却不纳管任何店铺会留下一条
   * 永不参与同步的空连接，比直接失败难查得多。
   */
  private List<Record> selectedCandidates(UUID tenantId, UUID sessionId, List<UUID> shopIds) {
    if (shopIds == null)
      return sql.fetch(
          "select id,external_shop_id,shop_name,default_marketplace_code from oauth_shop_candidate"
              + " where tenant_id=? and session_id=? order by shop_name,external_shop_id",
          tenantId, sessionId);
    if (shopIds.isEmpty()) return List.of();
    // 逐个占位符而不是 "= any(?::uuid[])"：数组参数要走 jOOQ 的 SQLArray 解析，
    // 一旦解析器猜错类型就会整条语句报 bad grammar。ID 数量有限，拼占位符更稳也更好读。
    // 空列表必须在这里短路：拼出来会是 "id in ()"，那是语法错误而不是"没选中"。
    String placeholders = shopIds.stream().map(id -> "?").collect(java.util.stream.Collectors.joining(","));
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);
    binds.add(sessionId);
    binds.addAll(shopIds);
    return sql.fetch(
        "select id,external_shop_id,shop_name,default_marketplace_code from oauth_shop_candidate"
            + " where tenant_id=? and session_id=? and id in (" + placeholders + ")"
            + " order by shop_name,external_shop_id",
        binds.toArray());
  }

  private void linkMarketplace(UUID tenantId, UUID platformId, UUID shopId, String marketplaceCode) {
    if (marketplaceCode == null || marketplaceCode.isBlank()) return;
    // 平台返回的站点标识未必等于注册表里的站点码（有的平台给的是站点 ID），
    // 对不上就留空：站点可以在 2.3.1.1 店铺详情里补，不该让整个授权流程失败。
    UUID mk =
        one(UUID.class,
            "select id from marketplace where platform_id=? and (code=? or name=?)",
            platformId, marketplaceCode, marketplaceCode);
    if (mk == null) return;
    sql.execute(
        "insert into shop_marketplace(tenant_id,shop_id,marketplace_id,status) values (?,?,?,'ACTIVE')"
            + " on conflict (tenant_id,shop_id,marketplace_id) do update set status='ACTIVE'",
        tenantId, shopId, mk);
  }

  private String uniqueShopCode(UUID tenantId, String prefix, String externalId) {
    String base = (prefix + "-" + sanitize(externalId)).toUpperCase(Locale.ROOT);
    if (base.length() > 56) base = base.substring(0, 56);
    String code = base;
    for (int i = 2; one(UUID.class, "select id from shop where tenant_id=? and code=?", tenantId, code) != null; i++) {
      code = base + "-" + i;
    }
    return code;
  }

  private static String sanitize(String raw) {
    String cleaned = raw.replaceAll("[^A-Za-z0-9_-]", "-");
    while (cleaned.contains("--")) cleaned = cleaned.replace("--", "-");
    return cleaned.replaceAll("^-|-$", "");
  }

  private UUID upsertCredential(UUID tenantId, String reference, List<String> scopes, Instant expiresAt) {
    java.sql.Timestamp authorizedAt = Timestamps.at(Instant.now());
    java.sql.Timestamp expires = Timestamps.at(expiresAt);
    UUID existing =
        one(UUID.class, "select id from credential_ref where tenant_id=? and external_ref=?", tenantId, reference);
    if (existing != null) return existing;
    UUID id = Ids.next();
    sql.execute(
        "insert into credential_ref(id,tenant_id,provider,external_ref,granted_scopes,authorized_at,expires_at)"
            + " values (?,?,?,?,?,?,?)",
        id, tenantId, secrets.provider(), reference, String.join(" ", scopes), authorizedAt, expires);
    return id;
  }

  private String credentialStatus(Instant expiresAt) {
    if (expiresAt == null) return "ACTIVE";
    if (expiresAt.isBefore(Instant.now())) return "EXPIRED";
    return Duration.between(Instant.now(), expiresAt).toDays() <= 7 ? "EXPIRING" : "ACTIVE";
  }

  private List<String> requestedScopes(String requested, OAuthProfile profile) {
    if (requested != null && !requested.isBlank()) return splitScopes(requested);
    return splitScopes(profile.defaultScopes());
  }

  private static List<String> splitScopes(String raw) {
    if (raw == null || raw.isBlank()) return List.of();
    return Arrays.stream(raw.split("[ ,]+")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
  }

  /**
   * 归一化授予的 scope：平台不回传 scope 时以申请的为准。
   *
   * <p>这一步不能省——大部分平台确实不回传，省掉会让"权限不足"永远判不出来。
   */
  private static List<String> normalizeScopes(List<String> granted, String requested) {
    return granted == null || granted.isEmpty() ? splitScopes(requested) : granted;
  }

  private static String missingScopes(List<String> required, List<String> granted) {
    if (required == null || required.isEmpty()) return null;
    List<String> missing = new ArrayList<>();
    for (String need : required) {
      boolean ok =
          granted.stream()
              .anyMatch(
                  g ->
                      g.equalsIgnoreCase(need)
                          || g.endsWith(need)
                          || need.endsWith(g)
                          || g.startsWith(need + ":"));
      if (!ok) missing.add(need);
    }
    return missing.isEmpty() ? null : String.join("、", missing);
  }

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    audit(actor.userId(), actor.tenantId(), resourceId, action);
  }

  private void audit(UUID userId, UUID tenantId, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values (?,?,?,?,?)",
        Ids.next(), tenantId, userId, resourceId, action);
  }

  private <T> T one(Class<T> type, String query, Object... binds) {
    Record row = sql.fetchOne(query, binds);
    return row == null ? null : row.get(0, type);
  }

  private String toJson(Object value) {
    try {
      return json.writeValueAsString(value == null ? List.of() : value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  private List<AccessService.Grant> fromJson(Object raw) {
    if (raw == null) return List.of();
    // raw 有两种形态：启动时写进去的是 JSON 文本；回调快照里 actorGrants 是嵌在 jsonb 里的
    // jsonb，Jackson 读外层时已经把它解析成了对象数组。convertValue 两种都能吃。
    try {
      return json.convertValue(raw, new TypeReference<List<AccessService.Grant>>() {});
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException("发起人权限快照解析失败", e);
    }
  }

  private static String randomToken(int bytes) {
    byte[] buffer = new byte[bytes];
    new SecureRandom().nextBytes(buffer);
    return base64Url(buffer);
  }

  private static String base64Url(byte[] raw) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
  }

  private static byte[] sha256(byte[] raw) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(raw);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
