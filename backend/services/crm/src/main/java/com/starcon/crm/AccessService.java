package com.starcon.crm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.TenantTransactions;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 跨服务访问守卫（15.4.2.1 知识库随 crm 服务第一条业务端点引入）。
 *
 * <p>租户**绝不**来自客户端头：identity 依据已认证用户的成员关系解析出租户与权限， 本服务再开一个事务，绑定 {@code app.current_tenant} 供 RLS
 * 强制隔离， 然后才执行查询。伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>与 identity / catalog / channel / warehouse / trade **完全同构**（逐字照抄同一实现）， 差别只有权限码前缀：{@code
 * crm.}。刻意不在 crm 重写一份「简化版」守卫 —— 租户解析一旦出现第二个实现，「哪些端点信任头」就会变成需要逐个审计的问题。
 */
@Service
public class AccessService {
  public record Grant(UUID roleId, String roleCode, String scopeMode, List<String> permissions) {}

  public record Access(
      UUID userId,
      UUID tenantId,
      String tenantName,
      UUID membershipId,
      UUID departmentId,
      List<String> permissions,
      List<Grant> grants) {}

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final RestClient client;
  private final String key;
  private final ObjectMapper json;

  public AccessService(
      DSLContext sql,
      PlatformTransactionManager manager,
      ObjectMapper json,
      @Value("${IDENTITY_URL}") String url,
      @Value("${INTERNAL_KEY}") String key) {
    this.sql = sql;
    this.tenants = new TenantTransactions(sql, new TransactionTemplate(manager));
    this.json = json;
    this.key = key;
    var factory =
        new JdkClientHttpRequestFactory(
            java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
  }

  /** 在可信租户事务里执行一段工作，并绑定数据范围所需的 actor / grants 上下文。 */
  public <T> T run(Access actor, String permission, Supplier<T> work) {
    return tenants.run(
        actor.tenantId(),
        () -> {
          try {
            sql.fetch(
                "select set_config('app.actor',?,true),set_config('app.department',?,true),"
                    + "set_config('app.permission',?,true),set_config('app.grants',?,true)",
                actor.userId().toString(),
                actor.departmentId() == null ? "" : actor.departmentId().toString(),
                permission,
                json.writeValueAsString(actor.grants() == null ? List.of() : actor.grants()));
          } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
          }
          return work.get();
        });
  }

  /**
   * 校验会话并解析出可信租户。
   *
   * <p>沿用其它五个服务的判据：权限码必须在某个 grant 里命中， 且同一 grant 里必须同时有该动作与对应资源的 {@code .read}，
   * 否则「能写不能读」这种组合会被静默放行。
   */
  public Access require(String bearer, String permission) {
    try {
      Access access =
          client
              .get()
              .uri("/internal/access")
              .header("X-Internal-Key", key)
              .header("Authorization", bearer)
              .retrieve()
              .body(Access.class);
      if (access == null
          || access.grants() == null
          || access.grants().stream()
              .noneMatch(
                  g ->
                      g.permissions().contains(permission)
                          && g.permissions()
                              .contains(
                                  permission.substring(0, permission.lastIndexOf('.')) + ".read")
                          && (g.scopeMode().equals("ALL")
                              || g.scopeMode().equals("SELF")
                              || (g.scopeMode().equals("DEPARTMENT")
                                  && access.departmentId() != null))))
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
      return access;
    } catch (RestClientResponseException e) {
      throw new ResponseStatusException(
          e.getStatusCode().value() == 401
              ? HttpStatus.UNAUTHORIZED
              : HttpStatus.SERVICE_UNAVAILABLE,
          "登录已失效或身份服务不可用");
    } catch (org.springframework.web.client.RestClientException e) {
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "身份服务暂时不可用，请稍后重试");
    }
  }
}
