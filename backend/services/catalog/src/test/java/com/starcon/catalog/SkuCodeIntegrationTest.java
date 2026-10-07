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
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.1.2.3 编码与条码：编码规则、三种编码方式（手工 / 规则生成 / 批量补齐）、
 * 外部条码 scheme+value 唯一性、**冲突拒绝清单**，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SkuCodeIntegrationTest {
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
            var access = actors.get(e.getRequestHeaders().getFirst("Authorization"));
            if (access == null) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      // 只有 sku 读权限：所有写端点必须 403。
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
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CatalogService catalog;
  @Autowired SkuCodeService codes;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T call(AccessService.Access who, String permission, java.util.function.Supplier<T> work) {
    return access.run(who, permission, work);
  }

  /** deleteIdentifier 返回 void，用这个包装版把它接进租户事务。 */
  void run(AccessService.Access who, String permission, Runnable work) {
    access.run(who, permission, () -> {
      work.run();
      return null;
    });
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.sku.read", work);
  }

  // ------------------------------------------------------------ 夹具

  /** 真实建 SPU + SKU，走 CatalogService 的正常入口而不是裸 SQL。 */
  record Fixture(UUID spuId, UUID skuId) {}

  Fixture fixture(String prefix) {
    UUID spu =
        call(
            actor,
            "catalog.spu.create",
            () ->
                catalog.saveSpu(
                    actor,
                    null,
                    new SpuInput(
                        prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "编码条码测试商品",
                        "描述",
                        "品牌",
                        "3C 配件",
                        "DRAFT",
                        0)));
    UUID sku =
        call(
            actor,
            "catalog.sku.create",
            () ->
                catalog.saveSku(
                    actor,
                    spu,
                    null,
                    new SkuInput(
                        prefix + "-" + UUID.randomUUID().toString().substring(0, 8),
                        "编码条码 SKU",
                        "0" + Math.abs(prefix.hashCode() % 1000000000),
                        "件",
                        "ACTIVE",
                        List.of(),
                        spuVersion(spu))));
    return new Fixture(spu, sku);
  }

  /** SPU 当前的乐观锁 version：创建 SKU 时必须带上，saveSku 走 CAS。 */
  long spuVersion(UUID spu) {
    return tx(actor, () -> catalog.detail(spu).product().version());
  }

  /**
   * 造一个「尚无编码」的 SKU。
   *
   * <p>应用入口不允许空编码（{@code CatalogService.code} 要求 [A-Z0-9] 开头），所以这里直接
   * 写库模拟**导入/历史遗留**数据。
   *
   * <p>注意 V1 的 {@code UNIQUE(tenant_id,code)}：一个租户里最多只能有一行空编码，
   * 因此每个测试最多调用一次。
   */
  UUID uncodedSku(UUID spuId) {
    return tx(
        actor,
        () -> {
          // V1 的 UNIQUE(tenant_id,code) 决定了整个租户最多只有一行空编码，而所有用例共享
          // 同一个租户与同一个库，所以复用已存在的那一行而不是再插一条。
          // 复用的前提是把它**复位**：前面的用例可能已经给它编过码了。
          var existing = sql.fetchOne("select id,spu_id from sku where btrim(code)=''");
          if (existing != null) {
            sql.execute(
                "update sku set spu_id=?,version=version+1,updated_at=now() where id=?",
                spuId,
                existing.get("id", UUID.class));
            return existing.get("id", UUID.class);
          }
          UUID tenantId =
              sql.fetchOne("select tenant_id from spu where id=?", spuId).get("tenant_id", UUID.class);
          UUID id = UUID.randomUUID();
          sql.execute(
              "insert into sku(id,tenant_id,spu_id,code,name,variant_signature)"
                  + " values(?,?,?,'','待编码 SKU',?)",
              id,
              tenantId,
              spuId,
              "SIG-" + id);
          return id;
        });
  }

  /** 合法 GTIN-14（含校验位），用于构造 14 位 scheme 的导入用例。 */
  static String gtin14(String seed) {
    long n = Math.abs(seed.hashCode() % 10000000000000L);
    String body = String.format(Locale.ROOT, "%013d", n);
    return body + BarcodeScheme.expectedCheckDigit(body);
  }

  /** 读 SKU 编码。必须在租户事务里读：RLS 在事务级 GUC 绑定，事务外读一律看不到行。 */
  String codeOf(UUID skuId) {
    return tx(actor, () -> sql.fetchOne("select code from sku where id=?", skuId).get("code", String.class));
  }

  long versionOf(UUID skuId) {
    return tx(actor, () -> sql.fetchOne("select version from sku where id=?", skuId).get("version", Long.class));
  }

  String barcodeOf(UUID skuId) {
    return tx(
        actor,
        () -> sql.fetchOne("select barcode from sku where id=?", skuId).get("barcode", String.class));
  }

  /**
   * 每个测试用**互不相同**的条码值。
   *
   * <p>sku_identifier 的唯一性是租户级的，而同一批测试共享同一个租户与同一个 PostgreSQL
   * 实例，复用固定条码会让「唯一性拒绝」用例会互相污染。真实 GTIN-13 由此算法生成：
   * 取一个 12 位前缀再补上 mod-10 校验位。
   */
  static String gtin13(String seed) {
    long n = Math.abs(seed.hashCode() % 100000000000L);
    String body = String.format(Locale.ROOT, "%012d", n);
    return body + BarcodeScheme.expectedCheckDigit(body);
  }

  String uniqueEan13() {
    return gtin13(UUID.randomUUID().toString());
  }

  /** 合法 UPC-A（12 位，含校验位）。 */
  String uniqueUpca() {
    long n = Math.abs(UUID.randomUUID().hashCode() % 10000000000L);
    String body = String.format(Locale.ROOT, "%011d", n);
    return body + BarcodeScheme.expectedCheckDigit(body);
  }

  SkuCodeRuleInput rule(String code, String prefix, int width) {
    return new SkuCodeRuleInput(code, "规则 " + code, "测试规则", "ALL", null, prefix, "", width, 1, true, 0);
  }

  UUID saveRule(String code, String prefix, int width) {
    return call(actor, "catalog.sku.update", () -> codes.saveRule(actor, null, rule(code, prefix, width)).id());
  }

  /** 限定到某个商品族的规则：批量类用例必须用它，否则 scope=ALL 会扫到别的用例建的 SKU。 */
  UUID spuRule(String code, String prefix, int width, UUID spuId) {
    return call(
        actor,
        "catalog.sku.update",
        () ->
            codes.saveRule(
                    actor,
                    null,
                    new SkuCodeRuleInput(code, "规则 " + code, "", "SPU", spuId, prefix, "", width, 1, true, 0))
                .id());
  }

  // ------------------------------------------------------------ 编码规则

  @Test
  void ruleExposesNextSequenceAndSampleCodeBeforeAnyGeneration() {
    var saved = call(actor, "catalog.sku.update", () -> codes.saveRule(actor, null, rule("R1", "SKU-", 6)));
    assertThat(saved.sampleCode()).isEqualTo("SKU-000001");
    var listed = tx(actor, codes::rules).stream().filter(r -> r.id().equals(saved.id())).findFirst().orElseThrow();
    assertThat(listed.nextValue()).isEqualTo(1);
    assertThat(listed.sampleCode()).isEqualTo("SKU-000001");
    assertThat(listed.scopeLabel()).isEqualTo("全部 SKU");
  }

  @Test
  void ruleRejectsExpandedCodeThatCannotFitTheColumn() {
    var bad =
        new SkuCodeRuleInput(
            "TOOLONG", "超长", "", "ALL", null, "A".repeat(32), "B".repeat(32), 12, 1, true, 0);
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.saveRule(actor, null, bad)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("76")
        .hasMessageContaining("64");
  }

  @Test
  void ruleRejectsIllegalPrefixAndScopeWithoutSpu() {
    var badChars =
        new SkuCodeRuleInput("BAD1", "非法", "", "ALL", null, "SKU 1", "", 4, 1, true, 0);
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.saveRule(actor, null, badChars)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("编码前缀");

    var badScope =
        new SkuCodeRuleInput("BAD2", "缺 SPU", "", "SPU", null, "S-", "", 4, 1, true, 0);
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.saveRule(actor, null, badScope)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须指定 SPU");
  }

  @Test
  void ruleCodeIsUniquePerTenantAndScopeIsOnePerSpu() {
    saveRule("DUP", "A-", 4);
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.saveRule(actor, null, rule("DUP", "B-", 4))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已存在");

    var f = fixture("SCOPE");
    call(
        actor,
        "catalog.sku.update",
        () -> codes.saveRule(actor, null, new SkuCodeRuleInput("SPU1", "族规则", "", "SPU", f.spuId(), "F-", "", 4, 1, true, 0)));
    // 同一商品族只能有一条规则，否则生成的编码无法预期。
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        codes.saveRule(
                            actor, null, new SkuCodeRuleInput("SPU2", "族规则 2", "", "SPU", f.spuId(), "G-", "", 4, 1, true, 0))))
        .hasMessageContaining("一个商品族只能有一条规则");
  }

  @Test
  void ruleUpdateUsesOptimisticLock() {
    UUID id = saveRule("LOCK", "L-", 4);
    var current = tx(actor, codes::rules).stream().filter(r -> r.id().equals(id)).findFirst().orElseThrow();
    var input =
        new SkuCodeRuleInput("LOCK", "改名", "描述", "ALL", null, "L-", "", 4, 1, true, current.version());
    var saved = call(actor, "catalog.sku.update", () -> codes.saveRule(actor, id, input));
    assertThat(saved.version()).isEqualTo(current.version() + 1);
    // 用旧 version 再提交一次 → 409
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.saveRule(actor, id, input)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
  }

  // ------------------------------------------------------------ 手工输入

  @Test
  void manualCodeInputPersistsAndKeepsAssignmentHistory() {
    var f = fixture("MANUAL");
    String codeBefore = codeOf(f.skuId());
    long before = versionOf(f.skuId());
    var saved = call(actor, "catalog.sku.update", () -> codes.setCode(actor, f.skuId(), new SkuCodeInput("MANUAL-A", before)));
    assertThat(saved.fromCode()).isEqualTo(codeBefore);
    assertThat(saved.toCode()).isEqualTo("MANUAL-A");
    assertThat(codeOf(f.skuId())).isEqualTo("MANUAL-A");
    assertThat(saved.version()).isEqualTo(before + 1);

    var panel = tx(actor, () -> codes.panel(f.skuId()));
    assertThat(panel.internalCode()).isEqualTo("MANUAL-A");
    assertThat(panel.internalSource()).isEqualTo("MANUAL");
    assertThat(panel.assignments()).hasSize(1);
    // 覆盖必须留 from_code → to_code 的痕，否则事后查不出编码被谁改成什么
    assertThat(panel.assignments().get(0).fromCode()).isEqualTo(codeBefore);
    assertThat(panel.assignments().get(0).toCode()).isEqualTo("MANUAL-A");
    assertThat(panel.assignments().get(0).sourceLabel()).isEqualTo("手工输入");
  }

  @Test
  void manualCodeInputRejectsDuplicateAndStaleVersion() {
    var f1 = fixture("DUP1");
    var f2 = fixture("DUP2");
    call(actor, "catalog.sku.update", () -> codes.setCode(actor, f1.skuId(), new SkuCodeInput("TAKEN", versionOf(f1.skuId()))));
    // 租户内唯一：另一 SKU 抢同一个编码必须被拒
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.setCode(actor, f2.skuId(), new SkuCodeInput("TAKEN", versionOf(f2.skuId())))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被其他 SKU 使用");
    // 旧 version → 409（用 current+1 保证一定过期，而不是赌夹具的 version 恰好不是 0）
    long current = versionOf(f2.skuId());
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.setCode(actor, f2.skuId(), new SkuCodeInput("OTHER", current + 1))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    assertThat(codeOf(f2.skuId())).isNotEqualTo("OTHER");
  }

  // ------------------------------------------------------------ 规则生成 / 批量补齐

  @Test
  void generateAssignsRuleCodesAndSkipsAlreadyCodedSku() {
    var coded = fixture("GEN-CODED");
    String codedBefore = codeOf(coded.skuId());
    UUID blank = uncodedSku(coded.spuId());
    UUID ruleId =
        call(
            actor,
            "catalog.sku.update",
            () ->
                codes.saveRule(
                        actor,
                        null,
                        new SkuCodeRuleInput(
                            "GEN", "生成规则", "", "SPU", coded.spuId(), "SKU-", "", 6, 1, true, 0))
                    .id());

    var result =
        call(
            actor,
            "catalog.sku.update",
            () ->
                codes.batch(
                    actor,
                    new SkuCodeBatchInput("GENERATE", ruleId, List.of(coded.skuId(), blank), null, false)));
    // 已有编码 → SKIPPED（不是错误，只是不属于它该动的范围）；空编码 → APPLIED
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(result.skippedCount()).isEqualTo(1);
    assertThat(result.rejectedCount()).isZero();
    assertThat(codeOf(blank)).isEqualTo("SKU-000001");
    assertThat(codeOf(coded.skuId())).isEqualTo(codedBefore);

    var skipped = result.items().stream().filter(i -> i.outcome().equals("SKIPPED")).findFirst().orElseThrow();
    assertThat(skipped.reason()).contains("规则不覆盖手工编码").contains("批量补齐");
    assertThat(skipped.codeAfter()).isNull();

    // 已赋码后再跑一次：现在是 SKIPPED，不再重复分配序号
    var again =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", ruleId, List.of(blank), null, false)));
    assertThat(again.skippedCount()).isEqualTo(1);
    assertThat(again.appliedCount()).isZero();
  }

  @Test
  void backfillNormalizesSkusNotYetOnTheRuleAndIsIdempotent() {
    // 一个尚未赋码的（导入遗留）+ 一个手工编码的 = 两条都要被规范化
    var manual = fixture("BF-MANUAL");
    var spu = manual.spuId();
    String manualBefore = codeOf(manual.skuId());
    UUID blank = uncodedSku(spu);
    // 限定到本商品族的规则：否则 scope=ALL 会把同租户下**其它用例**建的 SKU 也扫进来
    // （所有用例共享同一个租户与同一个库）。
    UUID ruleId =
        call(
            actor,
            "catalog.sku.update",
            () ->
                codes.saveRule(
                        actor,
                        null,
                        new SkuCodeRuleInput("BF", "补齐规则", "", "SPU", spu, "BF-", "", 4, 1, true, 0))
                    .id());

    var preview =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.batch(actor, new SkuCodeBatchInput("BACKFILL", ruleId, null, null, true)));
    assertThat(preview.dryRun()).isTrue();
    // 预览不写：空编码仍是空，手工编码保持原样
    assertThat(codeOf(blank)).isEmpty();
    assertThat(codeOf(manual.skuId())).isEqualTo(manualBefore);

    var result =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.batch(actor, new SkuCodeBatchInput("BACKFILL", ruleId, null, null, false)));
    assertThat(result.appliedCount()).isEqualTo(2);
    assertThat(result.rejectedCount()).isZero();
    assertThat(codeOf(blank)).startsWith("BF-");
    assertThat(codeOf(manual.skuId())).startsWith("BF-").isNotEqualTo(manualBefore);
    // 覆盖既有编码必须留 from_code → to_code 的痕
    var history = tx(actor, () -> codes.panel(manual.skuId())).assignments();
    assertThat(history).isNotEmpty();
    assertThat(history.get(0).fromCode()).isEqualTo(manualBefore);
    assertThat(history.get(0).sourceLabel()).isEqualTo("批量补齐");

    // 幂等：再跑一次，已经按本规则编过码的 SKU 全部跳过，不重复改
    var again =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.batch(actor, new SkuCodeBatchInput("BACKFILL", ruleId, null, null, false)));
    assertThat(again.appliedCount()).isZero();
  }

  @Test
  void generatedCodeCollisionIsRejectedWithReasonNotCrashed() {
    var f = fixture("COLL");
    UUID blank = uncodedSku(f.spuId());
    UUID ruleId =
        call(
            actor,
            "catalog.sku.update",
            () ->
                codes.saveRule(
                        actor,
                        null,
                        new SkuCodeRuleInput(
                            "COL", "冲突规则", "", "SPU", f.spuId(), "COL-", "", 3, 1, true, 0))
                    .id());
    // 手工占用下一个将要生成的编码 COL-001 → 该行必须进拒绝清单并说明原因
    call(
        actor,
        "catalog.sku.update",
        () -> codes.setCode(actor, f.skuId(), new SkuCodeInput("COL-001", versionOf(f.skuId()))));
    var result =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", ruleId, List.of(blank), null, false)));
    assertThat(result.rejectedCount()).isEqualTo(1);
    var rejected = result.items().stream().filter(i -> i.outcome().equals("REJECTED")).findFirst().orElseThrow();
    assertThat(rejected.reason()).contains("COL-001").contains("已被其他 SKU 使用");
    assertThat(codeOf(blank)).isEmpty();
  }

  @Test
  void dryRunPreviewDoesNotWriteOrConsumeSequence() {
    var f = fixture("PREVIEW");
    UUID blank = uncodedSku(f.spuId());
    UUID ruleId =
        call(
            actor,
            "catalog.sku.update",
            () ->
                codes.saveRule(
                        actor,
                        null,
                        new SkuCodeRuleInput(
                            "PRE", "预览规则", "", "SPU", f.spuId(), "PRE-", "", 4, 1, true, 0))
                    .id());
    var preview =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", ruleId, List.of(blank), null, true)));
    assertThat(preview.dryRun()).isTrue();
    assertThat(preview.appliedCount()).isEqualTo(1);
    // 预览不写 sku.code
    assertThat(codeOf(blank)).isEmpty();
    // 预览不消耗号段：真正执行时从同一个序号开始
    var real =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", ruleId, List.of(blank), null, false)));
    assertThat(codeOf(blank)).isEqualTo("PRE-0001");
    assertThat(real.appliedCount()).isEqualTo(1);
  }

  @Test
  void generateRejectsDuplicatedSkuIdsAndEmptySelection() {
    UUID ruleId = saveRule("DUPID", "D-", 4);
    var f = fixture("DUPID");
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", ruleId, List.of(), null, false))))
        .hasMessageContaining("请先选择");
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () ->
                        codes.batch(
                            actor,
                            new SkuCodeBatchInput(
                                "GENERATE", ruleId, List.of(f.skuId(), f.skuId()), null, false))))
        .hasMessageContaining("不能重复提交");
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () -> codes.batch(actor, new SkuCodeBatchInput("NOPE", ruleId, List.of(f.skuId()), null, false))))
        .hasMessageContaining("只能是 GENERATE 或 BACKFILL");
  }

  @Test
  void disabledRuleCannotGenerate() {
    var f = fixture("DIS");
    UUID ruleId = saveRule("DIS", "DIS-", 4);
    call(
        actor,
        "catalog.sku.update",
        () ->
            codes.saveRule(
                actor,
                ruleId,
                new SkuCodeRuleInput(
                    "DIS",
                    "停用的规则",
                    "",
                    "ALL",
                    null,
                    "DIS-",
                    "",
                    4,
                    1,
                    false,
                    tx(actor, () -> {
                          for (var r : codes.rules())
                            if (r.id().equals(ruleId)) return r.version();
                          return 0L;
                        }))));
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", ruleId, List.of(f.skuId()), null, false))))
        .hasMessageContaining("已停用");
  }

  @Test
  void sequenceAllocationNeverRepeatsAcrossBatches() {
    var seen = new HashSet<String>();
    for (int i = 0; i < 3; i++) {
      // 每个商品族一条规则：同一 SPU 只允许一条限定规则，否则生成的编码无法预期
      var f = fixture("SEQ" + i);
      UUID blank = uncodedSku(f.spuId());
      UUID ruleId = spuRule("SEQ" + i, "S" + i + "-", 4, f.spuId());
      call(
          actor,
          "catalog.sku.update",
          () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", ruleId, List.of(blank), null, false)));
      assertThat(seen.add(codeOf(blank))).as("第 %d 批编码不重复", i).isTrue();
    }
    // 同一规则的两批也不能重号
    var sharedSpu = fixture("SEQSHARED");
    UUID shared = spuRule("SHARED", "SH-", 4, sharedSpu.spuId());
    for (int i = 0; i < 2; i++) {
      UUID blank = uncodedSku(sharedSpu.spuId());
      call(
          actor,
          "catalog.sku.update",
          () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", shared, List.of(blank), null, false)));
      assertThat(seen.add(codeOf(blank))).as("共享规则第 %d 批不重号", i).isTrue();
    }
  }

  // ------------------------------------------------------------ 外部条码

  @Test
  void externalIdentifierIsUniquePerSchemeAndValueAndSyncsPrimaryBarcode() {
    var f1 = fixture("ID1");
    var f2 = fixture("ID2");
    String f2Barcode = barcodeOf(f2.skuId());
    String ean = uniqueEan13();
    var saved =
        call(
            actor,
            "catalog.sku.update",
            () -> codes.addIdentifier(actor, f1.skuId(), new SkuIdentifierInput("EAN13", ean, true, null, "SCAN")));
    assertThat(saved.isPrimary()).isTrue();
    assertThat(saved.scheme()).isEqualTo("EAN13");
    // sku.barcode 由触发器同步（条码不一致在拣货现场会真的打错货）
    assertThat(barcodeOf(f1.skuId())).isEqualTo(ean);
    var panel = tx(actor, () -> codes.panel(f1.skuId()));
    assertThat(panel.identifiers()).hasSize(1);
    assertThat(panel.identifiers().get(0).schemeLabel()).isEqualTo("EAN-13");
    assertThat(panel.identifiers().get(0).source()).isEqualTo("SCAN");

    // 同 scheme+value 被另一个 SKU 占用 → 409
    assertThatThrownBy(
            () ->
                call(
                    actor,
                    "catalog.sku.update",
                    () -> codes.addIdentifier(actor, f2.skuId(), new SkuIdentifierInput("EAN13", ean, false, null, null))))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(409));
    // 非 primary 的条码不写 sku.barcode：f2 保留夹具自带的条码，不被这次录入影响
    assertThat(barcodeOf(f2.skuId())).isEqualTo(f2Barcode);
  }

  @Test
  void sameValueUnderDifferentSchemeIsAllowed() {
    var f1 = fixture("SC1");
    var f2 = fixture("SC2");
    // 唯一性范围是 scheme+value，不是裸 value
    String ean = uniqueEan13();
    call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f1.skuId(), new SkuIdentifierInput("EAN13", ean, false, null, null)));
    call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f2.skuId(), new SkuIdentifierInput("CUSTOM", ean, false, null, null)));
    assertThat(tx(actor, () -> codes.panel(f1.skuId())).identifiers()).hasSize(1);
    assertThat(tx(actor, () -> codes.panel(f2.skuId())).identifiers()).hasSize(1);
  }

  @Test
  void deletingPrimaryBarcodeClearsSkuBarcode() {
    var f = fixture("DEL");
    String ean = uniqueEan13();
    var saved = call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f.skuId(), new SkuIdentifierInput("EAN13", ean, true, null, null)));
    assertThat(barcodeOf(f.skuId())).isEqualTo(ean);
    run(actor, "catalog.sku.update", () -> codes.deleteIdentifier(actor, f.skuId(), saved.id()));
    assertThat(barcodeOf(f.skuId())).isEmpty();
    assertThat(tx(actor, () -> codes.panel(f.skuId())).identifiers()).isEmpty();
  }

  @Test
  void identifierRejectsBadSchemeAndBadCheckDigit() {
    var f = fixture("BADBAR");
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f.skuId(), new SkuIdentifierInput("GS1-128", "ABC", false, null, null))))
        .hasMessageContaining("不支持的条码 scheme");
    String good = uniqueEan13();
    String wrongCheck = good.substring(0, 12) + (good.charAt(12) == '7' ? '8' : '7');
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f.skuId(), new SkuIdentifierInput("EAN13", wrongCheck, false, null, null))))
        .hasMessageContaining("校验位不正确");
    assertThatThrownBy(() -> call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f.skuId(), new SkuIdentifierInput("SELLER_SKU", "PLATFORM-1", false, null, null))))
        .hasMessageContaining("不支持的条码 scheme");
  }

  // ------------------------------------------------------------ 条码批量导入 + 拒绝清单

  @Test
  void identifierImportReturnsRowLevelRejectionList() {
    var owner = fixture("OWNER");
    var other = fixture("OTHER");
    String taken = uniqueEan13();
    String fresh = uniqueEan13();
    String upca = uniqueUpca();
    // 先占用一个条码，后续导入必须把它拒掉
    call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, owner.skuId(), new SkuIdentifierInput("EAN13", taken, false, null, null)));
    String wrongGtin14 = gtin14(UUID.randomUUID().toString());
    // 末位改掉一位 → 校验位错
    wrongGtin14 =
        wrongGtin14.substring(0, 13) + (wrongGtin14.charAt(13) == '5' ? '6' : '5');

    var rows =
        List.of(
            // 1 正常导入
            new SkuIdentifierRow(other.skuId(), "EAN13", fresh, false, null),
            // 2 与已有条码冲突（owner 已占用）
            new SkuIdentifierRow(other.skuId(), "EAN13", taken, false, null),
            // 3 本批内重复
            new SkuIdentifierRow(other.skuId(), "UPCA", upca, false, null),
            new SkuIdentifierRow(other.skuId(), "UPCA", upca, false, null),
            // 4 校验位错
            new SkuIdentifierRow(other.skuId(), "GTIN14", wrongGtin14, false, null),
            // 5 scheme 不支持
            new SkuIdentifierRow(other.skuId(), "SELLER_SKU", "PLATFORM-1", false, null),
            // 6 SKU 不存在
            new SkuIdentifierRow(UUID.randomUUID(), "CUSTOM", "X-1", false, null));

    var result = call(actor, "catalog.sku.update", () -> codes.importIdentifiers(actor, new SkuIdentifierImportInput(rows, null, false)));
    assertThat(result.requestedCount()).isEqualTo(7);
    // 第 1 行（新 EAN）与第 3 行（本批首个 UPC-A）本就应该成功，其余 5 行各有各的拒绝理由
    assertThat(result.appliedCount()).isEqualTo(2);
    assertThat(result.rejectedCount()).isEqualTo(5);

    List<SkuIdentifierImportItem> rejected =
        result.items().stream().filter(i -> i.outcome().equals("REJECTED")).toList();
    assertThat(rejected).hasSize(5);
    // 每条拒绝都带**可执行**的原因，而不是笼统的"失败"
    assertThat(rejected.get(0).reason()).contains(taken).contains("已被其他 SKU 使用");
    assertThat(rejected.get(1).reason()).contains("本批次内该 scheme+条码值重复");
    assertThat(rejected.get(2).reason()).contains("校验位不正确");
    assertThat(rejected.get(3).reason()).contains("不支持的条码 scheme");
    assertThat(rejected.get(4).reason()).contains("记录不存在或没有访问权限");

    // 逐行留痕：事后用 batchResult 读回，拒绝清单仍完整
    var reread = tx(actor, () -> codes.batchResult(result.batchId()));
    assertThat(reread.rejectedCount()).isEqualTo(5);
    assertThat(reread.items()).hasSize(7);
  }

  @Test
  void identifierImportRejectsSecondPrimaryForSameScheme() {
    var f = fixture("PRIM");
    String first = uniqueEan13();
    String second = uniqueEan13();
    call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f.skuId(), new SkuIdentifierInput("EAN13", first, true, null, null)));
    var result =
        call(
            actor,
            "catalog.sku.update",
            () ->
                codes.importIdentifiers(
                    actor,
                    new SkuIdentifierImportInput(
                        List.of(new SkuIdentifierRow(f.skuId(), "EAN13", second, true, null)), null, false)));
    assertThat(result.rejectedCount()).isEqualTo(1);
    assertThat(result.items().get(0).reason()).contains("已有主条码").contains(first);
    // 原主条码没被动过
    assertThat(barcodeOf(f.skuId())).isEqualTo(first);
  }

  @Test
  void identifierImportDryRunWritesNothing() {
    var f = fixture("IMP");
    var result =
        call(
            actor,
            "catalog.sku.update",
            () ->
                codes.importIdentifiers(
                    actor,
                    new SkuIdentifierImportInput(
                        List.of(new SkuIdentifierRow(f.skuId(), "EAN13", uniqueEan13(), false, null)), null, true)));
    assertThat(result.dryRun()).isTrue();
    assertThat(result.appliedCount()).isEqualTo(1);
    assertThat(tx(actor, () -> codes.panel(f.skuId())).identifiers()).isEmpty();
  }

  @Test
  void oneBadRowDoesNotRollBackTheWholeBatch() {
    var f = fixture("PARTIAL");
    var rows =
        List.of(
            new SkuIdentifierRow(f.skuId(), "EAN13", uniqueEan13(), false, null),
            new SkuIdentifierRow(f.skuId(), "EAN13", "BAD", false, null),
            new SkuIdentifierRow(f.skuId(), "UPCA", uniqueUpca(), false, null));
    var result = call(actor, "catalog.sku.update", () -> codes.importIdentifiers(actor, new SkuIdentifierImportInput(rows, null, false)));
    assertThat(result.appliedCount()).isEqualTo(2);
    assertThat(result.rejectedCount()).isEqualTo(1);
    assertThat(tx(actor, () -> codes.panel(f.skuId())).identifiers()).hasSize(2);
  }

  // ------------------------------------------------------------ 概览

  @Test
  void overviewCountsUncodedSkusAndSchemeDistribution() {
    var f = fixture("OV");
    UUID blank = uncodedSku(f.spuId());
    call(actor, "catalog.sku.update", () -> codes.addIdentifier(actor, f.skuId(), new SkuIdentifierInput("EAN13", uniqueEan13(), false, null, null)));
    var overview = tx(actor, codes::overview);
    assertThat(overview.uncodedSkus()).isGreaterThanOrEqualTo(1);
    assertThat(overview.identifierCount()).isGreaterThanOrEqualTo(1);
    assertThat(overview.schemeCounts()).anyMatch(s -> s.scheme().equals("EAN13"));
    // 显式写明 seller_sku 的归属，避免有人把它当内部编码塞进来
    assertThat(overview.sellerSkuNote()).contains("seller_sku").contains("04");
    assertThat(codeOf(blank)).isEmpty();
  }

  // ------------------------------------------------------------ 租户隔离三断言

  @Test
  void skuCodeIsTenantIsolatedAndHeaderForgeryIsIgnored() throws Exception {
    var mine = fixture("ISO-MINE");
    UUID mineRule = saveRule("ISO-MINE-R", "MI-", 4);
    var theirs =
        tx(
            actorB,
            () ->
                catalog.saveSpu(
                    actorB,
                    null,
                    new SpuInput(
                        "ISO-THEIRS-" + UUID.randomUUID().toString().substring(0, 8),
                        "B 租户商品",
                        "",
                        "品牌",
                        "家居",
                        "DRAFT",
                        0)));
    UUID mineBlank = uncodedSku(mine.spuId());

    // 1) 两租户互不可见
    assertThat(codeOf(mineBlank)).isEmpty();
    // B 租户看不到 A 租户的 SKU 面板
    assertThatThrownBy(() -> tx(actorB, () -> codes.panel(mine.skuId())))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // B 租户规则列表里没有 A 的规则
    assertThat(tx(actorB, codes::rules).stream().map(SkuCodeRule::id)).doesNotContain(mineRule);
    // B 租户拿 A 的 SKU id 去写条码 → 404，且 A 的条码数量不变
    long beforeCount = tx(actor, () -> codes.panel(mine.skuId()).identifiers().size());
    assertThatThrownBy(
            () ->
                tx(
                    actorB,
                    () ->
                        codes.addIdentifier(
                            actorB, mine.skuId(), new SkuIdentifierInput("EAN13", uniqueEan13(), false, null, null))))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> codes.panel(mine.skuId()).identifiers())).hasSize((int) beforeCount);
    // B 租户看不到 A 的批次明细
    UUID mineBatch =
        call(actor, "catalog.sku.update", () -> codes.batch(actor, new SkuCodeBatchInput("GENERATE", mineRule, List.of(mineBlank), null, false))).batchId();
    assertThatThrownBy(() -> tx(actorB, () -> codes.batchResult(mineBatch)))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    http.perform(
            get("/catalog/sku-code/skus/" + mine.skuId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/sku-code/skus/" + mine.skuId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    // 伪造头也不能借道写接口
    http.perform(
            post("/catalog/sku-code/skus/" + mine.skuId() + "/identifiers")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"scheme\":\"EAN13\",\"value\":\"96385074\",\"isPrimary\":false}"))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> codes.panel(mine.skuId()).skuId())).isEqualTo(mine.skuId());
      assertThatThrownBy(() -> tx(actorB, () -> codes.panel(mine.skuId())))
          .isInstanceOf(ResponseStatusException.class);
    }
    assertThat(theirs).isNotNull();
  }

  // ------------------------------------------------------------ 表结构

  @Test
  void everyNewTableHasForcedRlsAndTenantNotNull() {
    var tables = List.of("sku_code_rule", "sku_code_rule_seq", "sku_identifier", "sku_code_assignment", "sku_code_batch", "sku_code_batch_item");
    var weak =
        sql.fetch(
            "select relname from pg_class join pg_namespace n on n.oid=relnamespace"
                + " where n.nspname='public' and relkind='r' and relname in"
                + " ('sku_code_rule','sku_code_rule_seq','sku_identifier','sku_code_assignment','sku_code_batch','sku_code_batch_item')"
                + " and (not relrowsecurity or not relforcerowsecurity)");
    assertThat(weak).isEmpty();
    var role = sql.fetchOne("select rolsuper,rolbypassrls from pg_roles where rolname=current_user");
    assertThat(role.get("rolsuper", Boolean.class)).isFalse();
    assertThat(role.get("rolbypassrls", Boolean.class)).isFalse();
    for (String table : tables) {
      var column =
          sql.fetchOne(
              "select is_nullable from information_schema.columns where table_name=? and column_name='tenant_id'",
              table);
      assertThat(column).as(table).isNotNull();
      assertThat(column.get("is_nullable", String.class)).as(table).isEqualTo("NO");
    }
    // 序号必须是整数大类型，绝不能是浮点（浮点会漏号）。
    var seqType = sql.fetchOne("select data_type from information_schema.columns where table_name='sku_code_rule_seq' and column_name='next_value'");
    assertThat(seqType.get("data_type", String.class)).isEqualTo("bigint");
  }

  @Test
  void skuIdentifierHasSchemeValueUniquenessInDatabase() {
    var indexes = sql.fetch(
        "select indexdef from pg_indexes where tablename='sku_identifier'");
    assertThat(indexes).anyMatch(i -> i.get("indexdef", String.class).contains("UNIQUE") && i.get("indexdef", String.class).contains("scheme, value"));
  }

  // ------------------------------------------------------------ HTTP 契约

  @Test
  void httpEndpointsExposeRulesPanelAndRejectionList() throws Exception {
    var f = fixture("HTTP");
    UUID blank = uncodedSku(f.spuId());

    http.perform(get("/catalog/sku-code/rules").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$").isArray());

    http.perform(get("/catalog/sku-code/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.sellerSkuNote").exists())
        .andExpect(jsonPath("$.totalSkus").isNumber());

    String ruleBody =
        "{\"code\":\"HTTPR\",\"name\":\"HTTP 规则\",\"description\":\"\",\"scope\":\"ALL\",\"spuId\":null,"
            + "\"prefix\":\"H-\",\"suffix\":\"\",\"seqWidth\":4,\"startFrom\":1,\"enabled\":true,\"version\":0}";
    String ruleId =
        http.perform(
                post("/catalog/sku-code/rules")
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content(ruleBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.sampleCode").value("H-0001"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String id = ruleId.replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");

    http.perform(
            post("/catalog/sku-code/batch")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"action\":\"GENERATE\",\"ruleId\":\"" + id + "\",\"skuIds\":[\"" + blank + "\"],\"dryRun\":true}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dryRun").value(true))
        .andExpect(jsonPath("$.appliedCount").value(1));

    http.perform(get("/catalog/sku-code/skus/" + f.skuId()).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.identifiers").isArray());

    // 导入里放一行非法条码 → 拒绝清单随行返回
    http.perform(
            post("/catalog/sku-code/identifiers/import")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(
                    "{\"rows\":[{\"skuId\":\"" + f.skuId() + "\",\"scheme\":\"EAN13\",\"value\":\"123\",\"isPrimary\":false}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rejectedCount").value(1))
        .andExpect(jsonPath("$.items[0].outcome").value("REJECTED"))
        .andExpect(jsonPath("$.items[0].reason").exists());
  }

  @Test
  void writeEndpointsRequireUpdatePermission() throws Exception {
    var f = fixture("PERM");
    http.perform(get("/catalog/sku-code/skus/" + f.skuId()).header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());
    http.perform(
            put("/catalog/sku-code/skus/" + f.skuId() + "/code")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"code\":\"NOPE\",\"version\":0}"))
        .andExpect(status().isForbidden());
    http.perform(post("/catalog/sku-code/rules").header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content("{\"code\":\"N\",\"name\":\"n\",\"description\":\"\",\"scope\":\"ALL\",\"spuId\":null,\"prefix\":\"N\",\"suffix\":\"\",\"seqWidth\":4,\"startFrom\":1,\"enabled\":true,\"version\":0}"))
        .andExpect(status().isForbidden());
  }
}