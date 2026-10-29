package com.starcon.channel;

import static com.starcon.channel.ListingEditorModels.*;
import static com.starcon.channel.ListingPublishJobModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 4.3.1.1 一键刊登 的多租户三断言（AGENTS.md 硬性要求，缺一不可）。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>（作业 / 目标 / 步骤都不可见）
 *   <li><b>伪造 tenant header 无效</b>（租户只来自已认证成员关系）
 *   <li><b>连接池归还后不串租户</b>（池压到 1，A→B→A 三轮）
 * </ol>
 *
 * <p>🔴 <b>按主键直查那条必须用 {@code channel_app} 连，不能用 Testcontainers 默认的超级用户。</b>
 * 超级用户<b>绕过 RLS</b> —— 用它查「读不到对方数据」会永远得到「读得到」，
 * 那不是隔离被破坏，而是这个断言本身失效了。
 */
@SpringBootTest
@AutoConfigureMockMvc
class ListingPublishJobTenancyIntegrationTest {

  static final PostgreSQLContainer<?> pg =
      new PostgreSQLContainer<>("postgres:16-alpine").withUrlParam("sslmode", "disable");
  static final UUID user = UUID.randomUUID();
  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final ObjectMapper JSON = new ObjectMapper();

  UUID a, b;
  AccessService.Access actorA, actorB;
  String bearerA, bearerB;

  static final UUID AMAZON = UUID.fromString("11111111-1111-1111-1111-111111111101");
  UUID siteUs;

  static final String ALL_SCOPE_GRANTS =
      "[{\"permissions\":[\"channel.operation.read\",\"channel.operation.create\","
          + "\"channel.operation.update\",\"channel.operation.delete\"],\"scopeMode\":\"ALL\"}]";

  static final List<String> allPermissions =
      java.util.stream.Stream.of("registry", "connection", "shop", "sync", "operation")
          .flatMap(
              p ->
                  java.util.stream.Stream.concat(
                      java.util.stream.Stream.of("read", "create", "update", "delete")
                          .map(op -> "channel." + p + "." + op),
                      p.equals("sync")
                          ? java.util.stream.Stream.of("channel.sync.trigger", "channel.sync.complete")
                          : java.util.stream.Stream.empty()))
          .distinct()
          .toList();

  static AccessService.Access actor(UUID uid, UUID tenantId, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenantId, "Test", UUID.randomUUID(), null,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='starcon_app')"
            + " then create role starcon_app nologin; end if; end $$");
        s.execute("do $$ begin if not exists(select 1 from pg_roles where rolname='channel_app')"
            + " then create role channel_app login password 'test-only' nosuperuser nobypassrls; end if; end $$");
        s.execute("alter role channel_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            AccessService.Access who = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (who == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body = new ObjectMapper().writeValueAsBytes(who);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @BeforeEach
  void freshTenants() {
    a = UUID.randomUUID();
    b = UUID.randomUUID();
    bearerA = "Bearer t-a-" + a;
    bearerB = "Bearer t-b-" + b;
    List<AccessService.Grant> grants = List.of(grant("ALL", allPermissions));
    actors.put(bearerA, actor(user, a, grants));
    actors.put(bearerB, actor(user, b, grants));
    actorA = actors.get(bearerA);
    actorB = actors.get(bearerB);
    siteUs = marketplaceId(AMAZON, "US");
  }

  static UUID marketplaceId(UUID platformId, String code) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(
            "select id from marketplace where platform_id='" + platformId + "' and code='" + code + "'")) {
      return rs.next() ? UUID.fromString(rs.getString(1)) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "channel_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://127.0.0.1:1");
    r.add("WAREHOUSE_URL", () -> "http://127.0.0.1:1");
    r.add("INTERNAL_KEY", () -> "test-key");
    // 连接池压到 1：多租户串租户在单连接下最容易暴露。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @Autowired ListingPublishJobService publishes;
  @Autowired ListingEditorService editor;
  @Autowired AccessService access;
  @Autowired MockMvc http;

  // ==================================================================
  // 辅助
  // ==================================================================

  <T> T as(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  static void execAsTenant(UUID tenantId, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      s.execute(sql);
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  /** 以<b>应用角色 channel_app</b>（非超级用户）直查一行计数 —— 超级用户绕过 RLS 会让断言永远「通过」。 */
  static long countAsTenant(UUID tenantId, String sql) {
    try (Connection c = DriverManager.getConnection(pg.getJdbcUrl(), "channel_app", "test-only");
        var s = c.createStatement()) {
      s.execute("select set_config('app.current_tenant','" + tenantId + "',false)");
      s.execute("select set_config('app.actor','" + user + "',false)");
      s.execute("select set_config('app.department','',false)");
      s.execute("select set_config('app.permission','channel.operation.create',false)");
      s.execute("select set_config('app.grants','" + ALL_SCOPE_GRANTS + "',false)");
      try (var rs = s.executeQuery(sql)) {
        return rs.next() ? rs.getLong(1) : 0L;
      }
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  static String scalar(String sql) {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement();
        var rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    } catch (SQLException e) {
      throw new RuntimeException(e);
    }
  }

  UUID seedShop(UUID tenantId, String code, UUID platformId, UUID marketplaceId) {
    UUID id = UUID.randomUUID();
    execAsTenant(tenantId,
        "insert into shop (tenant_id,id,platform_id,code,name) values ('" + tenantId + "','" + id
            + "','" + platformId + "','" + code + "','测试店铺 " + code + "')");
    execAsTenant(tenantId,
        "insert into shop_marketplace (tenant_id,shop_id,marketplace_id,status)"
            + " values ('" + tenantId + "','" + id + "','" + marketplaceId + "','ACTIVE')");
    return id;
  }

  UUID editorSubmission(AccessService.Access who, UUID shopId, UUID marketplaceId, String title) {
    EditorDraftRow draft =
        as(
            who,
            "channel.operation.create",
            () ->
                editor.save(
                    new EditorDraftRequest(
                        shopId,
                        marketplaceId,
                        "en-US",
                        UUID.randomUUID(),
                        null,
                        "Electronics > Audio",
                        List.of(
                            new EditorFieldValue("TITLE", title, null, null, null),
                            new EditorFieldValue("DESCRIPTION", "描述正文", null, null, null),
                            new EditorFieldValue("IMAGES", null, List.of("https://img/1.jpg"), null, null)),
                        "")));
    return as(
            who,
            "channel.operation.update",
            () -> editor.submit(draft.draftId().toString(), "idem-" + draft.draftId()))
        .submissionId();
  }

  ListingPublishJobRow create(
      AccessService.Access who, UUID shopId, UUID marketplaceId, UUID submissionId, String key) {
    return as(
        who,
        "channel.operation.create",
        () ->
            publishes.create(
                new CreateListingPublishJobRequest(
                    UUID.randomUUID(),
                    List.of(new ListingPublishTargetInput(shopId, marketplaceId, "en-US", "")),
                    "EDITOR_SUBMISSION",
                    List.of(submissionId),
                    "PRICING_SERVICE",
                    null,
                    null,
                    "KEEP_UNSET",
                    null,
                    key,
                    ""),
                "Bearer ignored-by-this-path",
                user));
  }

  // ==================================================================
  // 断言一：两租户互不可见
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 断言一：两租户互不可见（作业 / 目标 / 步骤都不可见）")
  void tenantsAreIsolated() {
    UUID shopA = seedShop(a, "TEN-A-1", AMAZON, siteUs);
    UUID shopB = seedShop(b, "TEN-B-1", AMAZON, siteUs);
    UUID submissionA = editorSubmission(actorA, shopA, siteUs, "A 的内容");
    UUID submissionB = editorSubmission(actorB, shopB, siteUs, "B 的内容");

    var jobA = create(actorA, shopA, siteUs, submissionA, "tenant-a");
    var jobB = create(actorB, shopB, siteUs, submissionB, "tenant-b");

    // B 读不到 A 的作业
    assertThat(as(actorB, "channel.operation.read", () -> publishes.jobs(null, 0, 50)))
        .as("B 的租户上下文里读不到 A 的作业")
        .extracting(ListingPublishJobRow::jobId)
        .containsExactly(jobB.jobId());
    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> as(actorB, "channel.operation.read", () -> publishes.job(jobA.jobId().toString()))))
        .as("B 按 id 直取 A 的作业必须失败")
        .isNotNull();

    // 🔴 按主键直查（用 channel_app 连，超级用户绕过 RLS 会让这个断言永远「通过」）
    assertThat(countAsTenant(b, "select count(*) from listing_publish_job where id='" + jobA.jobId() + "'"))
        .as("B 的上下文里直查 A 的作业必须读到零行").isZero();
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_publish_job_target where job_id='" + jobA.jobId() + "'"))
        .as("B 的上下文里直查 A 的目标必须读到零行").isZero();
    assertThat(
            countAsTenant(
                b, "select count(*) from listing_publish_job_step where job_id='" + jobA.jobId() + "'"))
        .as("B 的上下文里直查 A 的步骤必须读到零行").isZero();

    // 三张表都是 FORCE RLS
    for (String table :
        List.of("listing_publish_job", "listing_publish_job_target", "listing_publish_job_step")) {
      assertThat(scalar("select relforcerowsecurity::text from pg_class where relname='" + table + "'"))
          .as(table + " 必须 FORCE RLS").isEqualTo("true");
    }

    // 同一机制在本租户自己的数据上必须看得见行 —— 否则上面那些「零行」可能只是查询一直返回 0
    assertThat(countAsTenant(a, "select count(*) from listing_publish_job where id='" + jobA.jobId() + "'"))
        .as("A 的上下文必须能直查 A 自己的作业（证明上面那个 0 是隔离，不是查询失效）")
        .isEqualTo(1);

    // B 在自己的店铺下建一份，两边互不影响
    assertThat(as(actorA, "channel.operation.read", () -> publishes.jobs(null, 0, 50)))
        .extracting(ListingPublishJobRow::jobId)
        .containsExactly(jobA.jobId());
    assertThat(as(actorB, "channel.operation.read", () -> publishes.jobs(null, 0, 50)))
        .extracting(ListingPublishJobRow::jobId)
        .containsExactly(jobB.jobId());
  }

  // ==================================================================
  // 断言二：伪造 tenant header 无效
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 断言二：伪造 tenant header 无效（租户只来自已认证成员关系）")
  void forgedTenantHeaderIsIgnored() throws Exception {
    UUID shopA = seedShop(a, "FORGE-A", AMAZON, siteUs);
    UUID shopB = seedShop(b, "FORGE-B", AMAZON, siteUs);
    UUID submissionB = editorSubmission(actorB, shopB, siteUs, "B 的内容");
    var jobB = create(actorB, shopB, siteUs, submissionB, "forge-b");

    // A 的会话 + B 的头 ⇒ 仍然读不到 B 的作业
    http.perform(get("/channel/one-click-publish/jobs/" + jobB.jobId())
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isNotFound());
    http.perform(get("/channel/one-click-publish/jobs")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // A 的会话 + A 的头 ⇒ 只能看到 A 自己的（这里 A 还没建作业 ⇒ 空）
    http.perform(get("/channel/one-click-publish/jobs")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // 无会话一律 401（伪造的头救不了）
    http.perform(get("/channel/one-click-publish/capabilities")
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isUnauthorized());

    // A 建一份之后，A 的会话 + B 的头仍然只能看到 A 自己的
    UUID submissionA = editorSubmission(actorA, shopA, siteUs, "A 的内容");
    create(actorA, shopA, siteUs, submissionA, "forge-a");
    http.perform(get("/channel/one-click-publish/jobs")
            .header("Authorization", bearerA)
            .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  // ==================================================================
  // 断言三：连接池归还后不串租户
  // ==================================================================

  @Test
  @DisplayName("4.3.1.1 断言三：连接池归还后不串租户（池压到 1，A→B→A 三轮）")
  void pooledConnectionsDoNotBleedTenants() {
    UUID shopA = seedShop(a, "POOL-A", AMAZON, siteUs);
    UUID shopB = seedShop(b, "POOL-B", AMAZON, siteUs);
    UUID submissionA = editorSubmission(actorA, shopA, siteUs, "A 的内容");
    UUID submissionB = editorSubmission(actorB, shopB, siteUs, "B 的内容");
    var jobA = create(actorA, shopA, siteUs, submissionA, "pool-a");
    var jobB = create(actorB, shopB, siteUs, submissionB, "pool-b");

    for (int i = 0; i < 3; i++) {
      assertThat(as(actorA, "channel.operation.read", () -> publishes.jobs(null, 0, 50)))
          .as("第 " + (i + 1) + " 轮：A 只看到 A 的作业")
          .extracting(ListingPublishJobRow::jobId)
          .containsExactly(jobA.jobId());
      assertThat(as(actorB, "channel.operation.read", () -> publishes.jobs(null, 0, 50)))
          .as("第 " + (i + 1) + " 轮：B 只看到 B 的作业")
          .extracting(ListingPublishJobRow::jobId)
          .containsExactly(jobB.jobId());
      // 目标列表也跟着租户走
      assertThat(as(actorA, "channel.operation.read", () -> publishes.targets(jobA.jobId().toString(), null)))
          .hasSize(1);
      assertThat(as(actorB, "channel.operation.read", () -> publishes.targets(jobB.jobId().toString(), null)))
          .hasSize(1);
    }

    // 池里那条连接归还后，租户上下文必须被清掉：直查计数时不能带上前一个租户的行
    assertThat(countAsTenant(b, "select count(*) from listing_publish_job"))
        .as("B 的上下文里只能看到 B 自己的作业").isEqualTo(1);
    assertThat(countAsTenant(a, "select count(*) from listing_publish_job"))
        .as("A 的上下文里只能看到 A 自己的作业").isEqualTo(1);
  }

  /**
   * 🔴 收尾：停掉本地桩与 Testcontainers 容器。
   *
   * <p>不回收容器的话，Ryuk 只在 JVM 退出时按 session 标签统一回收 —— 整个模块跑完之前容器一直活着
   * （实测 channel 一个服务就能堆到 110+ 个 postgres 容器），容器内存 + Docker VM 把机器压进 swap，
   * JVM 被卡住十几分钟 ⇒ 全量跑时断言假红、SSL 握手失败。
   *
   * <p>顺序：先停外部服务，再停容器。反了会出现「容器已停但还有 in-flight 请求打进来」。
   */
  @AfterAll
  static void stopTestResources() {
    if (identity != null) identity.stop(0);
    pg.stop();
  }
}
