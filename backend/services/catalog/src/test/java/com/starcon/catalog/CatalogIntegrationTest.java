package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.*;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
class CatalogIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("spu", "sku", "attribute", "research", "compliance", "assets")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "catalog." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try {
      try (var c =
              DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = c.createStatement()) {
        s.execute("create role starcon_app nologin");
        s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
      }
      identity = HttpServer.create(new InetSocketAddress(0), 0);
      identity.createContext(
          "/internal/access",
          e -> {
            String auth = e.getRequestHeaders().getFirst("Authorization");
            var actor = actors.get(auth);
            if (actor == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(actor);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer reader",
          actor(
              user,
              a,
              null,
              List.of(
                  grant(
                      "ALL",
                      List.of("catalog.spu.read", "catalog.sku.read", "catalog.attribute.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "catalog_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CatalogService service;
  @Autowired DSLContext sql;
  @Autowired PlatformTransactionManager manager;
  @Autowired MockMvc http;
  TestTransactions tx;
  @Autowired AccessService access;

  class TestTransactions {
    <T> T run(UUID tenant, java.util.function.Supplier<T> work) {
      return access.run(
          actor(user, tenant, null, List.of(grant("ALL", allPermissions))),
          "catalog.spu.update",
          work);
    }
  }

  AccessService.Access actor;

  @BeforeEach
  void setup() {
    tx = new TestTransactions();
    actor = actors.get("Bearer tenant-a");
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  UUID product() {
    return tx.run(
        a,
        () ->
            service.saveSpu(
                actor,
                null,
                new SpuInput("P-" + UUID.randomUUID(), "Test phone", "", "", "", "DRAFT", 0)));
  }

  Detail detail(UUID id) {
    return tx.run(a, () -> service.detail(id));
  }

  Attribute attribute(
      UUID id,
      String code,
      String type,
      String mode,
      boolean required,
      boolean axis,
      List<String> options,
      List<String> values) {
    tx.run(
        a,
        () -> {
          service.saveAttribute(
              actor,
              id,
              null,
              new AttributeInput(
                  code,
                  code,
                  type,
                  mode,
                  false,
                  required,
                  axis,
                  "",
                  "Parameters",
                  options,
                  values,
                  detail(id).product().version()));
          return null;
        });
    return detail(id).attributes().stream()
        .filter(x -> x.code().equals(code))
        .findFirst()
        .orElseThrow();
  }

  UUID sku(UUID id, String code, List<ValueInput> values) {
    return tx.run(
        a,
        () ->
            service.saveSku(
                actor,
                id,
                null,
                new SkuInput(
                    code, code, "", "件", "ACTIVE", values, detail(id).product().version())));
  }

  @Test
  void rlsIsolationHeaderForgeryAndConnectionReuse() throws Exception {
    UUID id = product();
    assertThat(tx.run(b, () -> service.list(""))).isEmpty();
    assertThat(sql.fetchCount(sql.selectFrom(org.jooq.impl.DSL.table("spu")))).isZero();
    assertThat(tx.run(a, () -> service.detail(id).product().id())).isEqualTo(id);
    http.perform(
            get("/catalog/products/" + id)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/products/" + id)
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    http.perform(get("/catalog/products")).andExpect(status().isUnauthorized());
    http.perform(
            post("/catalog/products")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"code\":\"DENIED\",\"name\":\"Denied\",\"description\":\"\",\"brand\":\"\",\"category\":\"\",\"status\":\"DRAFT\",\"version\":0}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void inheritedOverrideClearAndRestorePreserveFalseAndZero() {
    UUID p = product();
    var fixed =
        attribute(p, "VIDEO", "TEXT", "COMMON_LOCKED", true, false, List.of(), List.of("4K"));
    var color =
        attribute(p, "COLOR", "OPTION", "SKU_ONLY", true, true, List.of("Black", "Red"), List.of());
    var option =
        attribute(
            p, "ACCESSORY", "TEXT", "COMMON_DEFAULT", false, false, List.of(), List.of("Standard"));
    var flag =
        attribute(p, "FLAG", "BOOLEAN", "COMMON_LOCKED", false, false, List.of(), List.of("false"));
    var number =
        attribute(p, "ZERO", "DECIMAL", "COMMON_LOCKED", false, false, List.of(), List.of("0"));
    UUID s =
        sku(
            p,
            "S-" + UUID.randomUUID(),
            List.of(new ValueInput(color.id(), "SET", List.of("Black"))));
    Sku saved = detail(p).skus().getFirst();
    assertThat(
            saved.values().stream()
                .filter(v -> v.attributeId().equals(fixed.id()))
                .findFirst()
                .orElseThrow()
                .effectiveValues())
        .containsExactly("4K");
    assertThat(
            saved.values().stream()
                .filter(v -> v.attributeId().equals(flag.id()))
                .findFirst()
                .orElseThrow()
                .effectiveValues())
        .containsExactly("false");
    assertThat(
            new java.math.BigDecimal(
                saved.values().stream()
                    .filter(v -> v.attributeId().equals(number.id()))
                    .findFirst()
                    .orElseThrow()
                    .effectiveValues()
                    .getFirst()))
        .isEqualByComparingTo("0");
    for (String state : List.of("SET", "CLEAR", "INHERIT")) {
      tx.run(
          a,
          () -> {
            service.saveSku(
                actor,
                p,
                s,
                new SkuInput(
                    saved.code(),
                    saved.name(),
                    "",
                    "件",
                    "ACTIVE",
                    List.of(
                        new ValueInput(color.id(), "SET", List.of("Black")),
                        new ValueInput(
                            option.id(),
                            state,
                            state.equals("SET") ? List.of("Special") : List.of())),
                    detail(p).product().version()));
            return null;
          });
      assertThat(
              detail(p).skus().getFirst().values().stream()
                  .filter(v -> v.attributeId().equals(option.id()))
                  .findFirst()
                  .orElseThrow()
                  .effectiveValues())
          .isEqualTo(
              state.equals("SET")
                  ? List.of("Special")
                  : state.equals("CLEAR") ? List.of() : List.of("Standard"));
    }
  }

  @Test
  void duplicateVariantRollsBackAndStaleVersionFails() {
    UUID p = product();
    var color =
        attribute(p, "COLOR", "OPTION", "SKU_ONLY", true, true, List.of("Black", "Red"), List.of());
    sku(p, "S-" + UUID.randomUUID(), List.of(new ValueInput(color.id(), "SET", List.of("Black"))));
    long version = detail(p).product().version();
    assertThatThrownBy(
            () ->
                sku(
                    p,
                    "S-" + UUID.randomUUID(),
                    List.of(new ValueInput(color.id(), "SET", List.of("Black")))))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThat(detail(p).product().version()).isEqualTo(version);
    assertThatThrownBy(
            () ->
                tx.run(
                    a,
                    () ->
                        service.saveSpu(
                            actor, p, new SpuInput("STALE", "Stale", "", "", "", "DRAFT", 0))))
        .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
  }

  @Test
  void activationAndLockedValuesAreValidated() {
    UUID p = product();
    var fixed = attribute(p, "VIDEO", "TEXT", "COMMON_LOCKED", true, false, List.of(), List.of());
    assertThatThrownBy(() -> sku(p, "S-" + UUID.randomUUID(), List.of()))
        .hasMessageContaining("必填");
    assertThatThrownBy(
            () ->
                sku(
                    p,
                    "S-" + UUID.randomUUID(),
                    List.of(new ValueInput(fixed.id(), "SET", List.of("4K")))))
        .hasMessageContaining("不能由 SKU 覆写");
    assertThatThrownBy(
            () ->
                tx.run(
                    a,
                    () ->
                        service.saveSpu(
                            actor,
                            p,
                            new SpuInput(
                                "ACTIVE",
                                "Phone",
                                "",
                                "",
                                "",
                                "ACTIVE",
                                detail(p).product().version()))))
        .hasMessageContaining("至少启用一个");
  }

  @Test
  void typedValuesCrossTenantReferencesAndModeConstraintsAreEnforced() {
    UUID p = product();
    var color =
        attribute(p, "COLOR", "OPTION", "SKU_ONLY", true, true, List.of("Black"), List.of());
    assertThatThrownBy(
            () ->
                sku(
                    p,
                    "S-" + UUID.randomUUID(),
                    List.of(new ValueInput(color.id(), "SET", List.of("Other")))))
        .hasMessageContaining("选项");
    assertThatThrownBy(
            () ->
                tx.run(
                    b,
                    () ->
                        sql.execute(
                            "insert into sku(id,tenant_id,spu_id,code,name,variant_signature) values(?,?,?,?,?,?)",
                            UUID.randomUUID(),
                            b,
                            p,
                            "CROSS",
                            "Cross",
                            "")))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    assertThatThrownBy(
            () ->
                tx.run(
                    a,
                    () ->
                        sql.execute(
                            "insert into attribute_value values(?,?,?,?,null,'SET')",
                            UUID.randomUUID(),
                            a,
                            p,
                            color.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void ancillaryRecordsPersistAndRespectLifecycle() {
    for (String kind : List.of("research", "compliance", "assets")) {
      UUID id =
          access.run(
              actor,
              "catalog." + kind + ".create",
              () ->
                  service.saveRecord(
                      actor,
                      kind,
                      null,
                      new RecordInput(
                          null,
                          "Example",
                          "Notes",
                          "https://example.com",
                          "DRAFT",
                          "en-US",
                          "US",
                          "2027-01-01",
                          0)));
      assertThat(
              access.run(actor, "catalog." + kind + ".read", () -> service.records(kind)).stream()
                  .anyMatch(x -> x.id().equals(id)))
          .isTrue();
      assertThat(
              access.run(
                  actors.get("Bearer tenant-b"),
                  "catalog." + kind + ".read",
                  () -> service.records(kind)))
          .isEmpty();
      access.run(
          actor,
          "catalog." + kind + ".delete",
          () -> {
            service.deleteRecord(actor, kind, id, 0);
            return null;
          });
      assertThat(
              access.run(actor, "catalog." + kind + ".read", () -> service.records(kind)).stream()
                  .noneMatch(x -> x.id().equals(id)))
          .isTrue();
    }
  }

  @Test
  void everyBusinessTableHasForcedRlsAndRuntimeRoleCannotBypass() {
    assertThat(
            sql.fetch(
                "select relname from pg_class join pg_namespace n on n.oid=relnamespace where n.nspname='public' and relkind='r' and relname<>'flyway_schema_history' and (not relrowsecurity or not relforcerowsecurity)"))
        .isEmpty();
    var role =
        sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
  }

  UUID ownedProduct(AccessService.Access actor) {
    return access.run(
        actor,
        "catalog.spu.create",
        () ->
            service.saveSpu(
                actor,
                null,
                new SpuInput("P-" + UUID.randomUUID(), "Scoped", "", "", "", "DRAFT", 0)));
  }

  @Test
  void scopesFilterRowsAndCannotCombineAllReadWithSelfWrite() throws Exception {
    UUID dept = UUID.randomUUID(), otherDept = UUID.randomUUID(), uid = UUID.randomUUID();
    var creator = actor(uid, a, dept, List.of(grant("ALL", allPermissions)));
    var peer = actor(UUID.randomUUID(), a, dept, List.of(grant("ALL", allPermissions)));
    var outsider = actor(UUID.randomUUID(), a, otherDept, List.of(grant("ALL", allPermissions)));
    UUID own = ownedProduct(creator), same = ownedProduct(peer), other = ownedProduct(outsider);
    var self = actor(uid, a, dept, List.of(grant("SELF", allPermissions)));
    var department = actor(uid, a, dept, List.of(grant("DEPARTMENT", allPermissions)));
    assertThat(
            access.run(self, "catalog.spu.read", () -> service.list("")).stream().map(Product::id))
        .containsExactly(own);
    assertThat(
            access.run(department, "catalog.spu.read", () -> service.list("")).stream()
                .map(Product::id))
        .containsExactlyInAnyOrder(own, same);
    assertThat(
            access.run(creator, "catalog.spu.read", () -> service.list("")).stream()
                .map(Product::id))
        .contains(own, same, other);
    var mixed =
        actor(
            uid,
            a,
            dept,
            List.of(
                grant(
                    "ALL",
                    List.of("catalog.spu.read", "catalog.sku.read", "catalog.attribute.read")),
                grant("SELF", allPermissions)));
    actors.put("Bearer mixed", mixed);
    http.perform(get("/catalog/products/" + same).header("Authorization", "Bearer mixed"))
        .andExpect(status().isOk());
    String update =
        "{\"code\":\"UNCHANGED\",\"name\":\"Denied\",\"description\":\"\",\"brand\":\"\",\"category\":\"\",\"status\":\"DRAFT\",\"version\":0}";
    http.perform(
            put("/catalog/products/" + same)
                .header("Authorization", "Bearer mixed")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(update))
        .andExpect(status().isNotFound());
    http.perform(
            delete("/catalog/products/" + same + "?version=0")
                .header("Authorization", "Bearer mixed"))
        .andExpect(status().isNotFound());
    http.perform(
            put("/catalog/products/" + own)
                .header("Authorization", "Bearer mixed")
                .contentType("application/json")
                .content(update))
        .andExpect(status().isOk());
    assertThat(
            access.run(
                mixed,
                "catalog.spu.update",
                () -> sql.execute("update spu set name='Forbidden' where id=?", same)))
        .isZero();
    assertThat(
            access.run(
                department,
                "catalog.spu.delete",
                () -> sql.execute("delete from spu where id=?", other)))
        .isZero();
    assertThat(sql.fetch("select * from spu")).isEmpty();
    assertThatThrownBy(
            () ->
                access.run(
                    self,
                    "catalog.spu.read",
                    () -> {
                      throw new IllegalStateException("rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(sql.fetch("select * from spu")).isEmpty();
    assertThat(
            access.run(outsider, "catalog.spu.read", () -> service.list("")).stream()
                .map(Product::id))
        .contains(other);
  }

  @Test
  void childEntitiesAndRecordPagesEnforceScopesForEveryWrite() throws Exception {
    UUID uid = UUID.randomUUID(), dept = UUID.randomUUID();
    var owner = actor(uid, a, dept, List.of(grant("ALL", allPermissions)));
    var restricted =
        actor(UUID.randomUUID(), a, UUID.randomUUID(), List.of(grant("SELF", allPermissions)));
    UUID p = ownedProduct(owner);
    actors.put("Bearer scoped-denied", restricted);
    String sku =
        "{\"code\":\"SKU\",\"name\":\"SKU\",\"barcode\":\"\",\"baseUnit\":\"件\",\"status\":\"DRAFT\",\"values\":[],\"version\":0}";
    http.perform(
            post("/catalog/products/" + p + "/skus")
                .header("Authorization", "Bearer scoped-denied")
                .contentType("application/json")
                .content(sku))
        .andExpect(status().isNotFound());
    assertThat(
            access.run(
                restricted,
                "catalog.attribute.update",
                () -> sql.fetch("select * from attribute_definition where spu_id=?", p)))
        .isEmpty();
    assertThatThrownBy(
            () ->
                access.run(
                    restricted,
                    "catalog.sku.create",
                    () ->
                        service.saveSku(
                            restricted,
                            p,
                            null,
                            new SkuInput("X", "X", "", "件", "DRAFT", List.of(), 0))))
        .hasMessageContaining("记录不存在");
    assertThatThrownBy(
            () ->
                access.run(
                    restricted,
                    "catalog.attribute.create",
                    () -> {
                      service.saveAttribute(
                          restricted,
                          p,
                          null,
                          new AttributeInput(
                              "X",
                              "X",
                              "TEXT",
                              "COMMON_DEFAULT",
                              false,
                              false,
                              false,
                              "",
                              "",
                              List.of(),
                              List.of(),
                              0));
                      return null;
                    }))
        .hasMessageContaining("记录不存在");
    for (String kind : List.of("research", "compliance", "assets")) {
      var input = new RecordInput(null, "Scoped", "", "", "DRAFT", "", "", null, 0);
      UUID id =
          access.run(
              owner,
              "catalog." + kind + ".create",
              () -> service.saveRecord(owner, kind, null, input));
      assertThat(access.run(restricted, "catalog." + kind + ".read", () -> service.records(kind)))
          .isEmpty();
      assertThatThrownBy(
              () ->
                  access.run(
                      restricted,
                      "catalog." + kind + ".update",
                      () -> service.saveRecord(restricted, kind, id, input)))
          .hasMessageContaining("记录已更新或不存在");
      assertThatThrownBy(
              () ->
                  access.run(
                      restricted,
                      "catalog." + kind + ".delete",
                      () -> {
                        service.deleteRecord(restricted, kind, id, 0);
                        return null;
                      }))
          .hasMessageContaining("只能删除");
      UUID own =
          access.run(
              restricted,
              "catalog." + kind + ".create",
              () -> service.saveRecord(restricted, kind, null, input));
      assertThat(
              access
                  .run(restricted, "catalog." + kind + ".read", () -> service.records(kind))
                  .stream()
                  .map(ProductRecord::id))
          .containsExactly(own);
    }
    // An ALL permission on another page cannot widen the requested page scope.
    var crossed =
        actor(
            restricted.userId(),
            a,
            restricted.departmentId(),
            List.of(
                grant("SELF", List.of("catalog.spu.read")),
                grant("ALL", List.of("catalog.sku.read"))));
    assertThat(
            access.run(crossed, "catalog.spu.read", () -> service.list("")).stream()
                .map(Product::id))
        .doesNotContain(p);
    var missingDept = actor(uid, a, null, List.of(grant("DEPARTMENT", allPermissions)));
    actors.put("Bearer no-department", missingDept);
    http.perform(get("/catalog/products").header("Authorization", "Bearer no-department"))
        .andExpect(status().isForbidden());
  }

  @Test
  void productDetailRedactsPagesWithoutTheirOwnReadScope() throws Exception {
    var owner =
        actor(UUID.randomUUID(), a, UUID.randomUUID(), List.of(grant("ALL", allPermissions)));
    UUID p = ownedProduct(owner);
    access.run(
        owner,
        "catalog.attribute.create",
        () -> {
          service.saveAttribute(
              owner,
              p,
              null,
              new AttributeInput(
                  "SECRET",
                  "Private",
                  "TEXT",
                  "COMMON_DEFAULT",
                  false,
                  false,
                  false,
                  "",
                  "",
                  List.of(),
                  List.of("value"),
                  0));
          return null;
        });
    access.run(
        owner,
        "catalog.sku.create",
        () ->
            service.saveSku(
                owner,
                p,
                null,
                new SkuInput("S-" + UUID.randomUUID(), "SKU", "", "件", "DRAFT", List.of(), 1)));
    var reader =
        actor(
            owner.userId(),
            a,
            owner.departmentId(),
            List.of(grant("ALL", List.of("catalog.spu.read"))));
    actors.put("Bearer spu-only", reader);
    http.perform(get("/catalog/products/" + p).header("Authorization", "Bearer spu-only"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.skus").isEmpty())
        .andExpect(jsonPath("$.attributes").isEmpty())
        .andExpect(jsonPath("$.product.skuCount").value(0));
    var skuReader =
        actor(
            owner.userId(),
            a,
            owner.departmentId(),
            List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read"))));
    var result = access.run(skuReader, "catalog.spu.read", () -> service.readDetail(p));
    var skuWriter =
        actor(
            owner.userId(),
            a,
            owner.departmentId(),
            List.of(grant("ALL", List.of("catalog.sku.read", "catalog.sku.update"))));
    assertThatThrownBy(
            () ->
                access.run(
                    skuWriter,
                    "catalog.sku.update",
                    () ->
                        service.saveSku(
                            skuWriter,
                            p,
                            result.skus().getFirst().id(),
                            new SkuInput("HIDDEN", "Hidden", "", "件", "DRAFT", List.of(), 2))))
        .hasMessageContaining("属性查看权限");
    assertThat(result.skus()).hasSize(1);
    assertThat(result.skus().getFirst().values()).isEmpty();
    assertThat(result.attributes()).isEmpty();
  }
}
