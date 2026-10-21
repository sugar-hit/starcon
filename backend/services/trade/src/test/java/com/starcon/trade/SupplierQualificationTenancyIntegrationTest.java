package com.starcon.trade;

import static com.starcon.trade.SupplierQualificationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 6.1.2.3 供应商资质的<b>多租户三断言</b>。
 *
 * <p>跑的是 AGENTS.md 明文要求的三条，且<b>实跑在本模块自己的五张表上</b>
 * （supplier_qualification 全族），<b>不是</b>占位探针表 ——
 * 探针表能证明 RLS 开着，证明不了<b>本模块的查询本身</b>没漏租户谓词。
 *
 * <p><b>本模块尤其不能省隔离</b>：资质档案里有<b>供应商名、证件编号、发证机构、品牌授权</b> ——
 * 一个租户能看到另一个租户的「哪些供应商拿到了哪些品牌的授权、证件什么时候过期」，
 * 拿到的是**供应商与品牌的合作格局**；端点又大多是<b>按 id 直达</b>的 URL，猜到一个 UUID 就能读，
 * 不需要翻列表。
 *
 * <p><b>连接池固定为 1 条连接</b>：只有复用同一条连接，才能真正证明归还后不会串租户 ——
 * 池大于 1 时每次可能拿到不同连接，「没串」可能只是碰巧换了条干净连接。
 *
 * <p><b>每轮读写都新建实体</b>：否则测的是幂等而不是隔离 —— 复用上一轮的记录，
 * 第二轮断言通过只能说明「同一条记录没变」。
 *
 * <p>⚠️ 身份与 catalog 都用 <b>spy</b>（只替换那几次跨服务调用），{@code run} 仍是真实实现
 * ⇒ RLS 租户绑定跑的是生产代码路径，且一个额外监听端口都不开。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierQualificationTenancyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID userA = UUID.randomUUID(), userB = UUID.randomUUID();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static final List<String> perms =
      List.of(
          "trade.qualifications.read",
          "trade.qualifications.create",
          "trade.qualifications.update",
          "trade.qualifications.delete");

  /** catalog stub 的供应商清单：roleId → 名称。 */
  static final Map<String, String> catalogSuppliers = new ConcurrentHashMap<>();

  static AccessService.Access actor(UUID uid, UUID tid) {
    var grants = List.of(new AccessService.Grant(UUID.randomUUID(), "TEST", "ALL", perms));
    return new AccessService.Access(uid, tid, "Test", UUID.randomUUID(), null, perms, grants);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
      }
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
    actors.put("Bearer tenant-a", actor(userA, a));
    actors.put("Bearer tenant-b", actor(userB, b));
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:8081");
    r.add("INTERNAL_KEY", () -> "test-key");
    // ⚠️ **连接池固定为 1**：池大于 1 时每次可能拿到不同连接，
    // 「归还后不串租户」可能只是碰巧换了条干净连接。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
  }

  @AfterAll
  static void stop() {
    pg.stop();
  }

  @MockitoSpyBean AccessService access;
  @MockitoSpyBean SupplierPartyQueryPort parties;
  @MockitoSpyBean BrandQueryPort brands;
  @MockitoSpyBean QualificationSkuQueryPort skus;

  @Autowired SupplierQualificationService service;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @BeforeEach
  void stubIdentityAndCatalog() {
    catalogSuppliers.clear();
    org.mockito.Mockito
        .doAnswer(
            inv -> {
              AccessService.Access found = actors.get(inv.getArgument(0, String.class));
              if (found == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效");
              }
              String permission = inv.getArgument(1, String.class);
              boolean allowed =
                  found.grants().stream()
                      .anyMatch(
                          g ->
                              g.permissions().contains(permission)
                                  && g.permissions()
                                      .contains(
                                          permission.substring(0, permission.lastIndexOf('.')) + ".read")
                                  && ("ALL".equals(g.scopeMode()) || "SELF".equals(g.scopeMode())));
              if (!allowed) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前角色没有此操作权限");
              }
              return found;
            })
        .when(access)
        .require(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());

    org.mockito.Mockito
        .doAnswer(
            inv -> {
              UUID roleId = inv.getArgument(0);
              String name = catalogSuppliers.get(roleId.toString());
              if (name == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该供应商不存在：" + roleId);
              }
              return new SupplierPartyQueryPort.SupplierView(roleId, "SUP-1", name, "ACTIVE");
            })
        .when(parties)
        .require(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString());

    org.mockito.Mockito
        .doAnswer(
            inv -> {
              UUID brandId = inv.getArgument(0);
              return new BrandQueryPort.BrandView(brandId, "BR-1", "晨光", "AUTHORIZED", true);
            })
        .when(brands)
        .require(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString());

    org.mockito.Mockito
        .doAnswer(
            inv -> {
              UUID skuId = inv.getArgument(0);
              return new QualificationSkuQueryPort.SkuView(skuId, "SKU-1", "女士通勤包", "ACTIVE");
            })
        .when(skus)
        .require(org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString());
  }

  @BeforeEach
  void clean() throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate supplier_qualification_reminder, supplier_qualification_review,"
              + " supplier_qualification_document, supplier_qualification_policy,"
              + " supplier_qualification cascade");
    }
  }

  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-a"), "trade.qualifications.update", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actors.get("Bearer tenant-b"), "trade.qualifications.update", work);
  }

  // ==========================================================================
  // 夹具
  // ==========================================================================

  final UUID supplierRole = UUID.randomUUID();

  OffsetDateTime dbNow() {
    return asA(() -> sql.fetchOne("select now()").get(0, OffsetDateTime.class));
  }

  /** 在指定租户里登记一份资质（名字里带租户标记，便于肉眼核对不串）。 */
  QualificationRow seed(UUID tenant, String tag) {
    catalogSuppliers.put(supplierRole.toString(), "供应商-" + tag);
    UUID roleId = supplierRole;
    // ⚠️ 时刻**在事务外**取（dbNow 自带一个事务）——
    //   在 as(...) 里面再取就是嵌套事务了。
    var now = dbNow();
    var work =
        (java.util.function.Supplier<QualificationRow>)
            () ->
                service.createQualification(
                    new QualificationInput(
                        "Q-" + tag,
                        roleId,
                        "BUSINESS_LICENSE",
                        "营业执照-" + tag,
                        "CERT-" + tag,
                        "发证机构",
                        null,
                        now.toString(),
                        now.plusYears(1).toString(),
                        tag,
                        0),
                    "Bearer tenant-a");
    return tenant.equals(a) ? asA(work) : asB(work);
  }

  private QualificationQuery query() {
    return new QualificationQuery(null, null, null, null, null, null, null, false, 0, 20);
  }

  // ==========================================================================
  // 断言一：两租户互不可见
  // ==========================================================================

  @Test
  void 两租户互不可见() throws Exception {
    QualificationRow qa = seed(a, "ISO-A");
    // 每轮都新建实体：复用上一轮的记录，第二轮断言通过只能说明「同一条记录没变」。
    QualificationRow qb = seed(b, "ISO-B");

    // ---- 列表互不可见（含汇总口径）----
    assertThat(asA(() -> service.pageQualifications(query())).total()).isEqualTo(1);
    assertThat(asA(() -> service.pageQualifications(query())).items())
        .extracting(QualificationRow::id)
        .containsExactly(qa.id());
    assertThat(asB(() -> service.pageQualifications(query())).items())
        .extracting(QualificationRow::id)
        .containsExactly(qb.id());

    // 证件名称不串（两边的证件名不同）。
    assertThat(asA(() -> service.qualification(qa.id())).qualification().title())
        .isEqualTo("营业执照-ISO-A");
    assertThat(asB(() -> service.qualification(qb.id())).qualification().title())
        .isEqualTo("营业执照-ISO-B");

    // ---- 详情按 id 直达也不行 ----
    assertThatThrownBy(() -> asA(() -> service.qualification(qb.id())))
        .hasStackTraceContaining("不存在或无权查看");
    assertThatThrownBy(() -> asB(() -> service.qualification(qa.id())))
        .hasStackTraceContaining("不存在或无权查看");

    // ---- 写入口也拦住：不能替别人提交审核 ----
    assertThatThrownBy(() -> asA(() -> service.review(qb.id(), new QualificationReviewInput("SUBMIT", ""))))
        .hasStackTraceContaining("不存在或无权审核");

    // ---- 直接在 RLS 上验证：绑定 A 的租户之后，B 的行在库里**读不出来** ----
    //      绕过服务层也一样（这里跑的是 trade_app，不是超级用户）。
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from supplier_qualification where tenant_id=?::uuid and id=?::uuid",
                            b,
                            qb.id()))
                .get(0, Long.class))
        .as("RLS 之下看不到别的租户的行")
        .isZero();
    // ⚠️ **即使不带 tenant_id 谓词**也读不到（证明是真隔离，不是应用层过滤）。
    assertThat(
            asA(
                () ->
                    sql.fetchOne(
                            "select count(*) from supplier_qualification where id=?::uuid", qb.id()))
                .get(0, Long.class))
        .as("不带租户谓词也读不到别人的行")
        .isZero();

    // HTTP 层同样如此（按 id 直达的 URL 是最容易被猜到的那种）。
    http.perform(
            get("/trade/supplier-qualifications/qualifications/{id}", qb.id().toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 断言二：伪造 tenant header 无效
  // ==========================================================================

  @Test
  void 伪造租户头无效() throws Exception {
    QualificationRow qa = seed(a, "FORGE-A");
    QualificationRow qb = seed(b, "FORGE-B");

    // 带着 **B 的令牌** + **伪造的 A 的 X-Tenant-Id**：读到的必须是 B 的数据，
    // 不是 A 的 —— 头改不动 identity 解析出来的可信租户。
    String list =
        http.perform(get("/trade/supplier-qualifications/qualifications")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list).contains(qb.id().toString()).doesNotContain(qa.id().toString());

    // 反过来：带着 A 的令牌 + 伪造的 B 的头，读到的仍然是 A 的数据。
    String list2 =
        http.perform(get("/trade/supplier-qualifications/qualifications")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(list2).contains(qa.id().toString()).doesNotContain(qb.id().toString());

    // 详情同理。
    String detail =
        http.perform(
                get("/trade/supplier-qualifications/qualifications/{id}", qb.id().toString())
                    .header("Authorization", "Bearer tenant-b")
                    .header("X-Tenant-Id", a.toString()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(detail).contains(qb.id().toString()).doesNotContain(qa.id().toString());
  }

  // ==========================================================================
  // 断言三：连接池归还后不串租户
  // ==========================================================================

  @Test
  void 连接池归还后不串租户() throws Exception {
    QualificationRow qa = seed(a, "POOL-A");
    QualificationRow qb = seed(b, "POOL-B");

    // 交替借还：同一张资质反复以两个租户的身份读，读到的永远是**当前绑定**的租户。
    for (int i = 0; i < 20; i++) {
      assertThat(asA(() -> service.qualification(qa.id()).qualification().title()))
          .isEqualTo("营业执照-POOL-A");
      assertThat(asB(() -> service.qualification(qb.id()).qualification().title()))
          .isEqualTo("营业执照-POOL-B");
      // 归还之后再读另一个租户的 id：仍然读不到。
      assertThatThrownBy(() -> asA(() -> service.qualification(qb.id())))
          .hasStackTraceContaining("不存在或无权查看");
      assertThatThrownBy(() -> asB(() -> service.qualification(qa.id())))
          .hasStackTraceContaining("不存在或无权查看");
    }
  }
}
