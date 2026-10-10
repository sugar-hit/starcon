package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.CustomsAttributeModels.*;
import static com.starcon.catalog.CustomsDestinationVersionModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.7.1.2 目的地报关版本：读写、发布状态机、<b>覆盖 vs 继承的合成</b>、
 * 按目的地查生效版本，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：{@code customs_destination_version} 与
 * {@code customs_destination_requirement} 全部真的落库，Flyway 真的跑 V95。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的五组断言</b>：
 *
 * <ol>
 *   <li>{@link #同一sku两个目的地合成出两份不同资料()}：本模块存在的理由。
 *       「不同目的地必须用不同版本，不能用一套申报资料打通全球」。
 *   <li>{@link #该目的地无生效版本时明确报错而不是回退到基础()}：静默回退等于
 *       把蓝图要杜绝的做法从一个更隐蔽的入口请回来。
 *   <li>{@link #相邻区间在前一版终点处不重叠()} + {@link #区间重叠的已发布版本被库层拒绝()}：
 *       规则 R1/R2/B1，且<b>写进去的时间要断言写回来的值</b>。
 *   <li>{@link #租户隔离三断言()}：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户。
 *   <li>裸 SQL 直接打库：B2–B5 各自证明约束真的在 DB 上而不只在 Java。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomsDestinationVersionIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  // 本模块的入参含 OffsetDateTime（生效区间），必须注册 JavaTimeModule：
  // 默认的 ObjectMapper 不认识 java.time 类型，序列化请求体时会直接抛
  // InvalidDefinitionException —— 那是测试夹具的问题，不是被测代码的问题。
  static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      // 只读角色：只有 read，没有 update —— 证明写操作按权限收敛。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.compliance.read")))));
      // 能编辑但**不能发布**：证明「填」与「发」是两个权限
      // （蓝图：不同目的地必须用不同版本，而发布才是下游要用的那一份）。
      actors.put(
          "Bearer editor",
          actor(
              user, a, null,
              List.of(grant("ALL", List.of("catalog.compliance.read", "catalog.compliance.update")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  /** 全权限清单：<b>比 3.7.1.1 多一个 publish</b> —— 那是本模块的第三个权限。 */
  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute", "compliance")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete", "review", "publish")
                    .map(op -> "catalog." + p + "." + op))
        .toList();
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
    // 单连接：只有复用同一条连接，「归还后不串租户」才是真的被证明过。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  @Autowired CustomsDestinationVersionService destination;
  @Autowired CustomsAttributeService customs;
  @Autowired CatalogService catalog;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setup() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.compliance.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.compliance.update", work);
  }

  String unique(String hint) {
    return hint + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID skuOf(AccessService.Access who, String code) {
    UUID spuId =
        write(who, () -> catalog.saveSpu(
            who, null, new SpuInput(code, "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(who, () -> catalog.saveSku(
        who, spuId, null, new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  static List<DeclarationElementInput> completeElements() {
    return List.of(
        new DeclarationElementInput(1, "01", "品名", "移动电话机", true),
        new DeclarationElementInput(2, "02", "品牌类型", "自有品牌", true));
  }

  /** 维护一份<b>已审核通过</b>的基础报关属性（3.7.1.1），返回基础属性 id。 */
  UUID approvedBase(AccessService.Access who, UUID skuId, boolean preferential) {
    CustomsInput input =
        new CustomsInput(
            "851712", "CN", "移动电话机", decimal("12.5"), "PCE",
            preferential, preferential ? "RCEP" : "", "", completeElements(), 0);
    CustomsProfileSaved saved = write(who, () -> customs.save(who, skuId, input));
    write(who, () -> customs.submit(who, skuId, new SubmitInput(saved.profile().version())));
    CustomsProfile approved =
        write(who, () -> customs.review(who, skuId, new ReviewInput(true, ""), true));
    assertThat(approved.status()).isEqualTo("APPROVED");
    return approved.attributeId();
  }

  /** 建一个 SKU 并维护好已审核通过的基础报关属性。 */
  UUID skuWithApprovedBase(AccessService.Access who, String code, boolean preferential) {
    UUID sku = skuOf(who, code);
    approvedBase(who, sku, preferential);
    return sku;
  }

  static OffsetDateTime at(String iso) {
    return OffsetDateTime.parse(iso).withOffsetSameInstant(ZoneOffset.UTC);
  }

  static DestinationRequirementInput requirement(String code, String name, boolean required) {
    return new DestinationRequirementInput(code, name, required, "");
  }

  /** 便捷入参：DE 目的地、覆盖税号与品名、当前时刻起一直有效。 */
  DestinationVersionInput draft(String destination, int versionNo, String hsCode, String name, long version) {
    return new DestinationVersionInput(
        destination, versionNo, hsCode, name, false,
        at("2020-01-01T00:00:00Z"), null, "", List.of(), version);
  }

  /** 保存 + 发布，返回发布后的版本行。 */
  DestinationVersionRow published(
      AccessService.Access who, UUID skuId, DestinationVersionInput input) {
    DestinationVersionSaved saved = write(who, () -> destination.save(who, skuId, input));
    return write(who, () -> destination.publish(who, saved.row().id(),
        new DestinationPublishInput(saved.row().version()), true));
  }

  // ============================================================ 覆盖 vs 继承（本模块最要紧的一条）

  /**
   * **同一 SKU 两个目的地合成出两份不同资料** —— 本模块存在的理由。
   *
   * <p>蓝图原文：「不同目的地必须用不同版本，不能用一套申报资料打通全球」。
   * 一份基础资料 + DE / US 两套差异声明，合成结果必须逐格不同；
   * 而<b>法定计量单位与申报要素必须完全一致</b>（它们只来自基础属性，
   * 本模块不复制 —— 复制即第二个事实来源）。
   */
  @Test
  void 同一sku两个目的地合成出两份不同资料() {
    // 基础属性**有享惠资格**（RCEP 原产地规则满足）—— 下面要证明的是
    // 「有资格」与「该目的地承认与否」是两个独立维度，而不是把基础设成无资格
    // 让 granted 恒为 false 那样糊过去。
    UUID sku = skuWithApprovedBase(actor, unique("TWO"), true);

    published(actor, sku, new DestinationVersionInput(
        "DE", 1, "85171300", "Mobiltelefon", false,
        at("2020-01-01T00:00:00Z"), null, "DE 税则 8 位", List.of(), 0));
    published(actor, sku, new DestinationVersionInput(
        "US", 1, "85171230", "Cellular telephone", true,
        at("2020-01-01T00:00:00Z"), null, "FCC + RCEP 不适用", List.of(), 0));

    var de = tx(actor, () -> destination.resolve(sku, "DE", null));
    var us = tx(actor, () -> destination.resolve(sku, "US", null));

    // **两份资料的税号与品名逐格不同** —— 这就是「不能用一套打通全球」的可测形态。
    assertThat(de.hsCode()).isEqualTo("85171300");
    assertThat(us.hsCode()).isEqualTo("85171230");
    assertThat(de.declaredName()).isEqualTo("Mobiltelefon");
    assertThat(us.declaredName()).isEqualTo("Cellular telephone");
    assertThat(de.hsCode()).isNotEqualTo(us.hsCode());
    assertThat(de.declaredName()).isNotEqualTo(us.declaredName());

    // 来源标记要说清这一格来自哪一层（10 报关要把它落进 hs_code_source）。
    assertThat(de.hsCodeSource()).isEqualTo("MODULE_3_7_1_2");
    assertThat(de.hsCodeOverridden()).isTrue();
    assertThat(de.declaredNameSource()).isEqualTo("MODULE_3_7_1_2");

    // **法定计量单位与申报要素完全来自基础属性，两个目的地逐字相同**。
    assertThat(de.statutoryUnitCode()).isEqualTo("PCE");
    assertThat(us.statutoryUnitCode()).isEqualTo("PCE");
    assertThat(de.declarationElements().byCode()).containsEntry("01", "移动电话机");
    assertThat(us.declarationElements().byCode()).isEqualTo(de.declarationElements().byCode());
    // 申报要素的来源恒为 3.7.1.1：目的地版本**不复制**要素（V95 第二节）。
    assertThat(de.declarationElementsSource()).isEqualTo("MODULE_3_7_1_1");
    assertThat(us.declarationElementsSource()).isEqualTo("MODULE_3_7_1_1");
    // 定位键与 3.7.1.1 一致（回填 declaration_elements_ref 用它）。
    assertThat(de.declarationElementsRef())
        .isEqualTo(tx(actor, () -> customs.profile(sku)).attributeId().toString());

    // **享惠：基础有资格 + US 目的地不承认 = US 这一份不享惠**。
    assertThat(de.preferentialOriginEligible()).isTrue();
    assertThat(de.preferentialApplicable()).isFalse();
    assertThat(de.preferentialGranted()).isFalse();
    assertThat(us.preferentialApplicable()).isTrue();
    assertThat(us.preferentialGranted()).isTrue();
  }

  @Test
  void 目的地没填覆盖时合成结果就是基础资料并标出是继承() {
    UUID sku = skuWithApprovedBase(actor, unique("INH"), false);
    published(actor, sku, new DestinationVersionInput(
        "GB", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "", List.of(), 0));

    var resolved = tx(actor, () -> destination.resolve(sku, "GB", null));
    // 税号与品名沿用基础 —— 但**明确标出是继承**，界面上才看得出哪里被改过。
    assertThat(resolved.hsCode()).isEqualTo("851712");
    assertThat(resolved.hsCodeSource()).isEqualTo("MODULE_3_7_1_1");
    assertThat(resolved.hsCodeOverridden()).isFalse();
    assertThat(resolved.declaredName()).isEqualTo("移动电话机");
    assertThat(resolved.declaredNameSource()).isEqualTo("MODULE_3_7_1_1");
    assertThat(resolved.declaredNameOverridden()).isFalse();
    // 但目的地版本本身是真的存在且生效的 —— 「有差异声明」与「差异声明里有覆盖」是两件事。
    assertThat(resolved.destinationVersionNo()).isEqualTo(1);
    assertThat(resolved.destinationVersionRef()).hasSizeLessThanOrEqualTo(64);
  }

  @Test
  void 税号与品名各自独立继承而不是一起() {
    UUID sku = skuWithApprovedBase(actor, unique("HALF"), false);
    // 只覆盖税号，品名留空沿用基础。
    published(actor, sku, new DestinationVersionInput(
        "FR", 1, "85171400", "", false, at("2020-01-01T00:00:00Z"), null, "", List.of(), 0));

    var resolved = tx(actor, () -> destination.resolve(sku, "FR", null));
    assertThat(resolved.hsCode()).isEqualTo("85171400");
    assertThat(resolved.hsCodeOverridden()).isTrue();
    assertThat(resolved.declaredName()).isEqualTo("移动电话机");
    assertThat(resolved.declaredNameOverridden()).isFalse();
    assertThat(resolved.declaredNameSource()).isEqualTo("MODULE_3_7_1_1");
  }

  @Test
  void 认证要求随目的地不同() {
    UUID sku = skuWithApprovedBase(actor, unique("REQ"), false);
    published(actor, sku, new DestinationVersionInput(
        "US", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "FCC",
        List.of(requirement("FCC", "美国 FCC 认证", true),
            requirement("UL", "UL 认���", false)),
        0));
    published(actor, sku, new DestinationVersionInput(
        "DE", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "CE",
        List.of(requirement("CE", "欧盟 CE 认证", true),
            requirement("WEEE", "德国 WEEE 注册", true)),
        0));

    var us = tx(actor, () -> destination.requirements(sku, "US", null));
    var de = tx(actor, () -> destination.requirements(sku, "DE", null));
    // **两个目的地的认证要求清单完全不同** —— 这是 16 合规按目的地校验的输入。
    assertThat(us.items()).extracting(DestinationRequirement::requirementCode)
        .containsExactly("FCC", "UL");
    assertThat(de.items()).extracting(DestinationRequirement::requirementCode)
        .containsExactly("CE", "WEEE");
    // required=false 是「可选」不是「不需要」，所以它不计入强制项数。
    assertThat(us.requiredOnlyCount()).isEqualTo(1);
    assertThat(de.requiredOnlyCount()).isEqualTo(2);
    // 合成出口把该目的地的要求带出来，10 报关据此判断能不能放行。
    assertThat(tx(actor, () -> destination.resolve(sku, "US", null)).requirements())
        .extracting(DestinationRequirement::requirementCode).containsExactly("FCC", "UL");
  }

  // ============================================================ 无生效版本不降级（第零节第 2 条）

  @Test
  void 该目的地无生效版本时明确报错而不是回退到基础() {
    UUID sku = skuWithApprovedBase(actor, unique("NOEFF"), false);
    // 1) 完全没有版本。
    var noVersion = org.assertj.core.api.Assertions.catchThrowable(
        () -> tx(actor, () -> destination.resolve(sku, "DE", null)));
    assertThat(noVersion).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) noVersion).getStatusCode())
        .as("没有生效版本是 409：这份资料存在但不可用")
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(noVersion).hasMessageContaining("没有生效的报关版本");
    assertThat(noVersion).hasMessageContaining("不能用一套申报资料打通全球");

    // 2) 只有草稿：草稿对下游不可见（与 3.7.1.1 的「未审核通过不可引用」同一条纪律）。
    write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "Mobiltelefon", 0)));
    assertThatThrownBy(() -> tx(actor, () -> destination.resolve(sku, "DE", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有生效的报关版本");

    // effective 与 requirements 同样不降级。
    assertThatThrownBy(() -> tx(actor, () -> destination.effective(sku, "DE", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有生效的报关版本");
    assertThatThrownBy(() -> tx(actor, () -> destination.requirements(sku, "DE", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有生效的报关版本");
  }

  @Test
  void 基础属性未审核通过时合成出口同样拒绝() {
    UUID sku = skuOf(actor, unique("NOAPPROVE"));
    // 只保存草稿，不提交不审核。
    write(actor, () -> customs.save(actor, sku,
        new CustomsInput("851712", "CN", "移动电话机", decimal("1"), "PCE", false, "", "",
            completeElements(), 0)));
    published(actor, sku, draft("DE", 1, "85171300", "Mobiltelefon", 0));
    // 目的地版本齐备也不够：基础没过审就是不能用（与 3.7.1.1 的 409 同一条纪律）。
    assertThatThrownBy(() -> tx(actor, () -> destination.resolve(sku, "DE", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("尚未审核通过");
  }

  @Test
  void 没有基础属性时不能建目的地版本() {
    UUID sku = skuOf(actor, unique("NOBASE"));
    // 规则 B6：服务层给可读中文，而不是外键约束那种「23503 / key not present」。
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "X", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没有基础报关属性")
        .hasMessageContaining("说不上「差异相对什么」");
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_version where sku_id=?", sku))).isEmpty();
  }

  // ============================================================ 规则 R1 / R2 / B1：生效区间

  @Test
  void 相邻区间在前一版终点处不重叠() {
    UUID sku = skuWithApprovedBase(actor, unique("ADJ"), false);
    // [10:00,12:00) 与 [12:00,14:00)：在 12:00 处**不**重叠（规则 R1，半开区间）。
    DestinationVersionRow first = published(actor, sku, new DestinationVersionInput(
        "DE", 1, "85171300", "", false,
        at("2026-03-01T10:00:00Z"), at("2026-03-01T12:00:00Z"), "", List.of(), 0));
    // **第二版能发布成功**就是这条规则的证明：闭区间实现会让这里被拒。
    DestinationVersionRow second = published(actor, sku, new DestinationVersionInput(
        "DE", 2, "85171400", "", false,
        at("2026-03-01T12:00:00Z"), at("2026-03-01T14:00:00Z"), "", List.of(), 0));
    assertThat(second.versionNo()).isEqualTo(2);

    // **写进去的时间要断言写回来的值**：timestamptz 往返不偏时区、不丢精度。
    var stored = tx(actor, () -> sql.fetchOne(
        "select effective_from,effective_to from customs_destination_version where id=?", first.id()));
    assertThat(stored.get("effective_from", OffsetDateTime.class).toInstant())
        .isEqualTo(at("2026-03-01T10:00:00Z").toInstant());
    assertThat(stored.get("effective_to", OffsetDateTime.class).toInstant())
        .isEqualTo(at("2026-03-01T12:00:00Z").toInstant());

    // 时刻查询逐格断言边界：12:00 属于第二版，不属于第一版。
    assertThat(tx(actor, () -> destination.effective(sku, "DE", "2026-03-01T10:00:00Z"))
        .destinationVersionId()).isEqualTo(first.id());
    assertThat(tx(actor, () -> destination.effective(sku, "DE", "2026-03-01T11:59:59Z"))
        .destinationVersionId()).isEqualTo(first.id());
    assertThat(tx(actor, () -> destination.effective(sku, "DE", "2026-03-01T12:00:00Z"))
        .destinationVersionId()).isEqualTo(second.id());
    assertThat(tx(actor, () -> destination.effective(sku, "DE", "2026-03-01T13:59:59Z"))
        .destinationVersionId()).isEqualTo(second.id());
    // 14:00 之后两版都不生效。
    assertThatThrownBy(() -> tx(actor, () -> destination.effective(sku, "DE", "2026-03-01T14:00:00Z")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有生效的报关版本");
  }

  @Test
  void 区间重叠的已发布版本被库层拒绝() {
    UUID sku = skuWithApprovedBase(actor, unique("OVER"), false);
    published(actor, sku, new DestinationVersionInput(
        "DE", 1, "85171300", "", false,
        at("2026-03-01T10:00:00Z"), at("2026-03-01T12:00:00Z"), "", List.of(), 0));
    // 与第一版真重叠：[11:00,13:00) 跨过了 12:00。
    var error = org.assertj.core.api.Assertions.catchThrowable(() -> published(actor, sku,
        new DestinationVersionInput("DE", 2, "85171400", "", false,
            at("2026-03-01T11:00:00Z"), at("2026-03-01T13:00:00Z"), "", List.of(), 0)));
    assertThat(error).isInstanceOf(ResponseStatusException.class);
    // 报错要**点名与哪一版撞了**，而不是只说「冲突」。
    assertThat(error).hasMessageContaining("已发布版本 1");
    assertThat(error).hasMessageContaining("目的 DE");
    // 冲突的那一版没有被发布（整次发布是原子的）。
    assertThat(tx(actor, () -> destination.effective(sku, "DE", "2026-03-01T11:30:00Z")).versionNo())
        .isEqualTo(1);
  }

  @Test
  void 草稿区间随便重叠只在发布那一刻才检查() {
    UUID sku = skuWithApprovedBase(actor, unique("DRAFTOVL"), false);
    // 两条草稿区间完全重叠 —— 规则 R3：草稿不参与判定，所以两次保存都必须成功。
    var first = write(actor, () -> destination.save(actor, sku, new DestinationVersionInput(
        "DE", 1, "85171300", "", false,
        at("2026-03-01T10:00:00Z"), at("2026-03-01T12:00:00Z"), "", List.of(), 0)));
    var second = write(actor, () -> destination.save(actor, sku, new DestinationVersionInput(
        "DE", 2, "85171400", "", false,
        at("2026-03-01T10:00:00Z"), at("2026-03-01T12:00:00Z"), "", List.of(), 0)));
    assertThat(first.row().status()).isEqualTo("DRAFT");
    assertThat(second.row().status()).isEqualTo("DRAFT");

    // 发布第一版成功。
    write(actor, () -> destination.publish(actor, first.row().id(),
        new DestinationPublishInput(first.row().version()), true));
    // **再发布与之重叠的第二版时冲突** —— 规则 R2 的判定点就在这一刻。
    assertThatThrownBy(() -> write(actor, () -> destination.publish(actor, second.row().id(),
        new DestinationPublishInput(second.row().version()), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发布版本 1");
    // 第二版仍然是草稿（整次发布是原子的）。
    assertThat(tx(actor, () -> destination.profile(sku, "DE")).versions())
        .filteredOn(v -> v.versionNo() == 2)
        .singleElement()
        .extracting(DestinationVersionRow::status)
        .isEqualTo("DRAFT");
  }

  @Test
  void 永久有效的已发布版本会挡住后来的重叠版本() {
    UUID sku = skuWithApprovedBase(actor, unique("OPEN"), false);
    published(actor, sku, new DestinationVersionInput(
        "DE", 1, "85171300", "", false,
        at("2020-01-01T00:00:00Z"), null, "一直有效", List.of(), 0));
    // to = NULL = +∞，所以任何后面的区间都与它重叠。
    assertThatThrownBy(() -> published(actor, sku, new DestinationVersionInput(
        "DE", 2, "85171400", "", false,
        at("2026-01-01T00:00:00Z"), at("2027-01-01T00:00:00Z"), "", List.of(), 0)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发布版本 1");
  }

  @Test
  void 已发布版本只能向前关闭不能改起点() {
    UUID sku = skuWithApprovedBase(actor, unique("CLOSE"), false);
    DestinationVersionRow published =
        published(actor, sku, new DestinationVersionInput(
            "DE", 1, "85171300", "", false,
            at("2026-03-01T10:00:00Z"), at("2030-01-01T00:00:00Z"), "", List.of(), 0));
    var fresh = tx(actor, () -> destination.profile(sku, "DE")).versions().get(0);

    // 规则 R5：允许把终点收窄（且必须仍在未来）。
    var narrowed = write(actor, () -> destination.save(actor, sku,
        new DestinationVersionInput("DE", 1, "85171300", "", false,
            at("2026-03-01T10:00:00Z"), at("2028-01-01T00:00:00Z"), "",
            List.of(), fresh.version())));
    assertThat(narrowed.row().effectiveTo()).isEqualTo("2028-01-01T00:00:00Z");

    // 改生效起点：服务层与库层都拒。
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, sku,
        new DestinationVersionInput("DE", 1, "85171300", "", false,
            at("2026-03-02T10:00:00Z"), at("2028-01-01T00:00:00Z"), "",
            List.of(), narrowed.row().version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已发布");
    // 改覆盖内容：同样拒（下游正在引用它）。
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, sku,
        new DestinationVersionInput("DE", 1, "85179999", "", false,
            at("2026-03-01T10:00:00Z"), at("2028-01-01T00:00:00Z"), "",
            List.of(), narrowed.row().version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能直接改");
    // 裸 SQL 改起点也拒（B3 的触发器那一半）。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set effective_from=?::timestamptz where id=?",
        at("2026-03-02T10:00:00Z"), published.id())))
        .hasMessageContaining("不能改生效起点");
    // 裸 SQL 改终点往后延也拒。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set effective_to=?::timestamptz where id=?",
        at("2029-01-01T00:00:00Z"), published.id())))
        .hasMessageContaining("不能把生效终点往后延");
    // 裸 SQL 改覆盖内容也拒。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set destination_hs_code='85179999' where id=?", published.id())))
        .hasMessageContaining("不能改目的地税号");
  }

  // ============================================================ 版本面板与写入

  @Test
  void 版本面板列出草稿与已发布并标出此刻生效的那个() {
    UUID sku = skuWithApprovedBase(actor, unique("PANEL"), false);
    published(actor, sku, new DestinationVersionInput(
        "DE", 1, "85171300", "", false, at("2020-01-01T00:00:00Z"), null, "", List.of(), 0));
    write(actor, () -> destination.save(actor, sku,
        new DestinationVersionInput("DE", 2, "85171400", "", false,
            at("2027-01-01T00:00:00Z"), null, "未来的税则换版", List.of(), 0)));

    var panel = tx(actor, () -> destination.profile(sku, "DE"));
    assertThat(panel.destinationCountryCode()).isEqualTo("DE");
    assertThat(panel.versions()).hasSize(2);
    // 版本号倒序：运营最关心最新的一版。
    assertThat(panel.versions()).extracting(DestinationVersionRow::versionNo).containsExactly(2, 1);
    // 只有「当前生效」的那一版被标 effective —— 未来的草稿不算。
    var first = panel.versions().stream().filter(v -> v.versionNo() == 1).findFirst().orElseThrow();
    var second = panel.versions().stream().filter(v -> v.versionNo() == 2).findFirst().orElseThrow();
    assertThat(first.effective()).isTrue();
    assertThat(second.effective()).isFalse();
    assertThat(panel.effectiveVersionId()).isEqualTo(first.id());
    assertThat(panel.draftCount()).isEqualTo(1);
    // 基础属性摘要内联返回：只列差异列而不列基础值，运营无法判断「空着」是沿用还是漏填。
    assertThat(panel.base().hsCode()).isEqualTo("851712");
    assertThat(panel.base().declaredName()).isEqualTo("移动电话机");
    assertThat(panel.base().baseApproved()).isTrue();
    assertThat(panel.base().statutoryUnitCode()).isEqualTo("PCE");
  }

  @Test
  void 未维护基础属性时面板显示未维护而不是报错() {
    UUID sku = skuOf(actor, unique("NOBASEPANEL"));
    var panel = tx(actor, () -> destination.profile(sku, "DE"));
    assertThat(panel.base().attributeId()).isNull();
    assertThat(panel.base().baseApproved()).isFalse();
    assertThat(panel.versions()).isEmpty();
    assertThat(panel.effectiveVersionId()).isNull();
  }

  @Test
  void 保存后认证要求是全量替换而不是叠加() {
    UUID sku = skuWithApprovedBase(actor, unique("REQREPL"), false);
    write(actor, () -> destination.save(actor, sku, new DestinationVersionInput(
        "US", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "",
        List.of(requirement("FCC", "FCC", true), requirement("UL", "UL", false)), 0)));
    var first = tx(actor, () -> destination.profile(sku, "US")).versions().get(0);
    assertThat(first.requirements()).hasSize(2);

    // 第二次保存只带一条：完整清单替换 → 最终集合是一行，不是三行。
    var replaced = write(actor, () -> destination.save(actor, sku, new DestinationVersionInput(
        "US", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "",
        List.of(requirement("FCC", "FCC", true)), first.version())));
    assertThat(replaced.row().requirements()).hasSize(1);
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_requirement where destination_version_id=?", first.id())))
        .hasSize(1);
  }

  @Test
  void 同一版本里认证码重复被拒() {
    UUID sku = skuWithApprovedBase(actor, unique("REQDUP"), false);
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, sku,
        new DestinationVersionInput("US", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "",
            List.of(requirement("fcc", "FCC 认证", true), requirement("FCC", "另一个 FCC", false)), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("认证代码不能重复")
        .hasMessageContaining("两个互相矛盾的答案");
  }

  @Test
  void 目的国通配被服务层拒绝() {
    UUID sku = skuWithApprovedBase(actor, unique("WILD"), false);
    // 规则 B5 的第一道：应用层给可读中文。
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, sku, draft("*", 1, "", "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能用一套申报资料打通全球");
    assertThatThrownBy(() -> tx(actor, () -> destination.profile(sku, "*")))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能用一套申报资料打通全球");
    assertThatThrownBy(() -> tx(actor, () -> destination.resolve(sku, " * ", null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能用一套申报资料打通全球");
    // 一行都没写。
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_version where sku_id=?", sku))).isEmpty();
  }

  @Test
  void 目的地税号格式非法被服务层拒绝() {
    UUID sku = skuWithApprovedBase(actor, unique("HSFMT"), false);
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, sku,
        draft("DE", 1, "8517A2", "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("6–10 位纯数字")
        .hasMessageContaining("或者留空表示沿用基础 HS 编码");
  }

  @Test
  void 乐观锁版本不符撞409() {
    UUID sku = skuWithApprovedBase(actor, unique("CAS"), false);
    write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, sku,
        draft("DE", 1, "85171300", "", 99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
  }

  @Test
  void 版本号随保存递增并回传给前端做cas() {
    UUID sku = skuWithApprovedBase(actor, unique("VER"), false);
    var first = write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    assertThat(first.row().version()).isEqualTo(0L);
    var second = write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    // 版本已递增，回传值必须跟着变，否则前端 CAS 永远打不中。
    assertThat(second.row().version()).isEqualTo(1L);
  }

  // ============================================================ 发布闸与权限

  @Test
  void 没有发布权限的人不能发布() {
    UUID sku = skuWithApprovedBase(actor, unique("NOPUB"), false);
    var saved = write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    // editor 角色有 read+update 但没有 publish —— 「能填」不等于「能发」。
    var editor = actors.get("Bearer editor");
    assertThatThrownBy(() -> write(editor, () -> destination.publish(editor, saved.row().id(),
        new DestinationPublishInput(0), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有发布权限");
    // 行仍然是草稿，对下游不可见。
    assertThat(tx(actor, () -> destination.profile(sku, "DE")).versions().get(0).status())
        .isEqualTo("DRAFT");
    assertThatThrownBy(() -> tx(actor, () -> destination.resolve(sku, "DE", null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 已发布的版本不能重复发布也不能撤回() {
    UUID sku = skuWithApprovedBase(actor, unique("REPUB"), false);
    DestinationVersionRow published =
        published(actor, sku, draft("DE", 1, "85171300", "", 0));
    assertThat(published.status()).isEqualTo("PUBLISHED");
    assertThat(published.publishedAt()).isNotNull();
    // 重复发布被拒。
    assertThatThrownBy(() -> write(actor, () -> destination.publish(actor, published.id(),
        new DestinationPublishInput(published.version()), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有草稿可以发布");
    // 撤回发布（裸 SQL 绕过服务层）被库层拒绝。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set status='DRAFT',published_by=null,published_at=null"
            + " where id=?", published.id())))
        .hasMessageContaining("不能撤回发布");
  }

  @Test
  void 发布留痕落进审计事件() {
    UUID sku = skuWithApprovedBase(actor, unique("AUDIT"), false);
    published(actor, sku, draft("DE", 1, "85171300", "", 0));
    var actions = tx(actor, () -> sql.fetch(
        "select action from audit_event where tenant_id=? and resource_id=? order by action",
        a, sku)).stream().map(r -> r.get("action", String.class)).toList();
    assertThat(actions).contains(
        "SkuCustomsAttributeSaved", "SkuCustomsAttributeSubmitted", "SkuCustomsAttributeApproved",
        "SkuCustomsDestinationVersionSaved", "SkuCustomsDestinationVersionPublished");
  }

  @Test
  void 发布时刻写入后读回不偏时区() {
    UUID sku = skuWithApprovedBase(actor, unique("TZ"), false);
    var row = published(actor, sku, draft("DE", 1, "85171300", "", 0));
    var raw = tx(actor, () -> sql.fetchOne(
        "select published_at from customs_destination_version where id=?", row.id()));
    var publishedAt = raw.get("published_at", OffsetDateTime.class);
    assertThat(publishedAt).isNotNull();
    // 与墙钟同一天：偏 -28800s 的双偏移会让发布时刻落到前一天。
    assertThat(publishedAt.toLocalDate()).isEqualTo(OffsetDateTime.now().toLocalDate());
    // 读回响应层是 ISO-8601 字符串，时区信息不丢。
    assertThat(row.publishedAt()).contains("T");
    assertThat(OffsetDateTime.parse(row.publishedAt()).toInstant()).isEqualTo(publishedAt.toInstant());
  }

  // ============================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言() throws Exception {
    String myPrefix = unique("MINE");
    UUID mySku = skuWithApprovedBase(actor, myPrefix, true);
    published(actor, mySku, draft("DE", 1, "85171300", "Mobiltelefon", 0));

    String theirPrefix = unique("THEIRS");
    UUID theirSku = skuWithApprovedBase(actorB, theirPrefix, false);
    published(actorB, theirSku, draft("DE", 1, "85179999", "Their Phone", 0));

    // 1) 两租户互不可见：读对方的版本面板直接 404。
    assertThatThrownBy(() -> tx(actor, () -> destination.profile(theirSku, "DE")))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> destination.profile(mySku, "DE")))
        .isInstanceOf(ResponseStatusException.class);
    // 写对方的版本同样拿不到目标（对方 SKU 在本租户不可见）。
    assertThatThrownBy(() -> write(actor, () -> destination.save(actor, theirSku,
        draft("DE", 2, "85171111", "Hijack", 0))))
        .isInstanceOf(ResponseStatusException.class);
    // 对方的覆盖值不能被本租户改掉。
    assertThat(tx(actorB, () -> destination.resolve(theirSku, "DE", null)).hsCode())
        .isEqualTo("85179999");
    // **合成出口也必须跨租户隔离**：拿对方的 SKU 去合成拿不到任何东西。
    assertThatThrownBy(() -> tx(actor, () -> destination.resolve(theirSku, "DE", null)))
        .isInstanceOf(ResponseStatusException.class);
    // 按版本 id 发布对方的版本同样不行。
    var theirRow = tx(actorB, () -> destination.profile(theirSku, "DE")).versions().get(0);
    assertThatThrownBy(() -> write(actor, () -> destination.publish(actor, theirRow.id(),
        new DestinationPublishInput(theirRow.version()), true)))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(get("/catalog/customs-destination-versions/skus/" + mySku)
            .param("destination", "DE")
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 合成出口同样不认伪造的头。
    http.perform(get("/catalog/customs-declaration/resolve")
            .param("skuId", mySku.toString()).param("destination", "DE")
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言面板与合成都不串。
    for (int i = 0; i < 5; i++) {
      var mine = tx(actor, () -> destination.profile(mySku, "DE"));
      var theirs = tx(actorB, () -> destination.profile(theirSku, "DE"));
      assertThat(mine.versions()).hasSize(1);
      assertThat(theirs.versions()).hasSize(1);
      assertThat(mine.versions().get(0).destinationHsCode()).isEqualTo("85171300");
      assertThat(theirs.versions().get(0).destinationHsCode()).isEqualTo("85179999");
      // 基础属性也不串：本租户是 RCEP 且已过审，对方不享惠。
      assertThat(mine.base().baseApproved()).isTrue();
      assertThat(mine.base().hsCode()).isEqualTo("851712");
      assertThat(theirs.base().hsCode()).isEqualTo("851712");

      var myResolved = tx(actor, () -> destination.resolve(mySku, "DE", null));
      var theirResolved = tx(actorB, () -> destination.resolve(theirSku, "DE", null));
      assertThat(myResolved.hsCode()).isEqualTo("85171300");
      assertThat(theirResolved.hsCode()).isEqualTo("85179999");
      assertThat(myResolved.declaredName()).isEqualTo("Mobiltelefon");
      assertThat(theirResolved.declaredName()).isEqualTo("Their Phone");
      // 享惠判定不串：本租户基础有资格 + DE 不承认 = false。
      assertThat(myResolved.preferentialOriginEligible()).isTrue();
      assertThat(theirResolved.preferentialOriginEligible()).isFalse();
      // 两个租户的版本 id 集合互不相交。
      assertThat(java.util.Collections.disjoint(
          List.of(mine.versions().get(0).id()), List.of(theirs.versions().get(0).id())))
          .as("两租户的版本行不得互相可见（轮次 %s）", i)
          .isTrue();
    }
  }

  // ============================================================ 库层约束（绕开 Java 直接写 SQL）

  @Test
  void 裸sql写通配目的国被库层拒绝() {
    UUID sku = skuWithApprovedBase(actor, unique("DBWILD"), false);
    var saved = write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    // 规则 B5 的第二道：绕过服务层，CHECK 与触发器都要咬住。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set destination_country_code='*' where id=?", saved.row().id())))
        .hasMessageContaining("不能用一套申报资料打通全球");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "insert into customs_destination_version(id,tenant_id,sku_id,customs_attribute_id,"
            + "destination_country_code,version_no,effective_from) "
            + "select ?,?,?,?,'*',9,?::timestamptz from customs_destination_version where id=?",
        UUID.randomUUID(), a, sku,
        tx(actor, () -> sql.fetchOne("select customs_attribute_id from customs_destination_version where id=?",
            saved.row().id())).get(0, UUID.class),
        at("2026-01-01T00:00:00Z"), saved.row().id())))
        .hasMessageContaining("不能用一套申报资料打通全球");
    // 值没被改脏。
    assertThat(tx(actor, () -> destination.profile(sku, "DE")).versions().get(0).destinationCountryCode())
        .isEqualTo("DE");
  }

  @Test
  void 裸sql写非法目的地税号被库层拒绝() {
    UUID sku = skuWithApprovedBase(actor, unique("DBHS"), false);
    var saved = write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set destination_hs_code='8517A2' where id=?", saved.row().id())))
        .hasMessageContaining("目的地税号");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set destination_hs_code='851' where id=?", saved.row().id())))
        .isNotNull();
    // 空串（沿用基础）必须仍然合法 —— 否则 B4 会把继承语义一起拦掉。
    write(actor, () -> sql.execute(
        "update customs_destination_version set destination_hs_code='' where id=?", saved.row().id()));
    assertThat(tx(actor, () -> destination.profile(sku, "DE")).versions().get(0).destinationHsCode())
        .isEmpty();
  }

  @Test
  void 裸sql绕过发布痕迹被库层拒绝() {
    UUID sku = skuWithApprovedBase(actor, unique("DBPUB"), false);
    var saved = write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    // 规则 B2：没有发布人的「已发布」等于「有人按过发布」这种无法证伪的说法。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set status='PUBLISHED' where id=?", saved.row().id())))
        .hasMessageContaining("发布必须同时记录发布人与发布时刻");
    // 反向：草稿不得带发布痕迹（否则「哪些还没发布」这个查询失真）。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set published_by=? where id=?", user, saved.row().id())))
        .hasMessageContaining("草稿不能带发布痕迹");
  }

  @Test
  void 裸sql发布态版本的认证要求也不可增删改() {
    UUID sku = skuWithApprovedBase(actor, unique("DBREQ"), false);
    var published = published(actor, sku, new DestinationVersionInput(
        "US", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "",
        List.of(requirement("FCC", "FCC", true)), 0));
    // 只拦增改的话「把全部要求删掉」这条路径是敞开的 —— DB 层必须一起拦。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "delete from customs_destination_requirement where destination_version_id=?", published.id())))
        .hasMessageContaining("已发布版本的认证要求不可修改");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "insert into customs_destination_requirement(id,tenant_id,destination_version_id,requirement_code)"
            + " values(?,?,?,'UL')", UUID.randomUUID(), a, published.id())))
        .hasMessageContaining("已发布版本的认证要求不可修改");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_requirement set required=false where destination_version_id=?",
        published.id())))
        .hasMessageContaining("已发布版本的认证要求不可修改");
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_requirement where destination_version_id=?", published.id())))
        .hasSize(1);
  }

  @Test
  void 裸sql挂错基础属性被触发器拒绝() {
    UUID skuA = skuWithApprovedBase(actor, unique("FK_A"), false);
    UUID skuB = skuWithApprovedBase(actor, unique("FK_B"), false);
    var saved = write(actor, () -> destination.save(actor, skuA, draft("DE", 1, "85171300", "", 0)));
    UUID attributeB = tx(actor, () -> customs.profile(skuB)).attributeId();
    // 复合外键只能保证「属性存在且同租户」，「属性属于这个 SKU」是跨行不变量，
    // CHECK 做不到 —— 由 guard_destination_version 的 B6 分支钉。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_destination_version set customs_attribute_id=? where id=?",
        attributeB, saved.row().id())))
        .hasMessageContaining("挂错了基础报关属性");
  }

  @Test
  void 新表强制rls且国家码是varchar2() {
    for (String table : List.of("customs_destination_version", "customs_destination_requirement")) {
      var info = tx(actor, () -> sql.fetchOne(
          "select relrowsecurity,relforcerowsecurity from pg_class"
              + " where relname=? and relkind='r'", table));
      // 注意：AssertJ 没有 assertThat(boolean, String) 这个重载，描述要用 .as() 挂。
      assertThat(Boolean.TRUE.equals(info.get("relrowsecurity", Boolean.class)))
          .as("表 %s 必须 ENABLE ROW LEVEL SECURITY", table)
          .isTrue();
      assertThat(Boolean.TRUE.equals(info.get("relforcerowsecurity", Boolean.class)))
          .as("表 %s 必须 FORCE ROW LEVEL SECURITY —— 否则表 owner 可绕过策略", table)
          .isTrue();
    }
    // 目的地国家码是 varchar(2) 而不是 char(2)（char 会空白填充）。
    var destinationType = tx(actor, () -> sql.fetchOne(
        "select data_type,character_maximum_length len from information_schema.columns"
            + " where table_name='customs_destination_version'"
            + " and column_name='destination_country_code'"));
    assertThat(destinationType.get("data_type", String.class)).isEqualTo("character varying");
    assertThat(destinationType.get("len", Integer.class)).isEqualTo(2);
    // **本表没有金额列**：完税价格取决于成交价与汇率，是 10 报关与 12 财务的事实，
    // 落一列等于替它们选口径（V95 第二节）。所以一张 numeric 列都不该有。
    var money = tx(actor, () -> sql.fetch(
        "select column_name from information_schema.columns"
            + " where table_name in ('customs_destination_version','customs_destination_requirement')"
            + " and data_type like '%numeric%' or"
            + " (table_name in ('customs_destination_version','customs_destination_requirement')"
            + " and data_type in ('double precision','real'))"));
    assertThat(money).isEmpty();
  }

  @Test
  void 运行时角色没有版本表的delete权限但要求表有() {
    // V95 第七节：版本与要求**刻意**授权不同（保护来自触发器，不来自权限）。
    var versionGrants = tx(actor, () -> sql.fetch(
        "select privilege_type from information_schema.role_table_grants"
            + " where grantee='catalog_app' and table_name='customs_destination_version'"));
    assertThat(versionGrants).extracting(r -> r.get("privilege_type", String.class))
        .contains("SELECT", "INSERT", "UPDATE")
        .doesNotContain("DELETE");
    var requirementGrants = tx(actor, () -> sql.fetch(
        "select privilege_type from information_schema.role_table_grants"
            + " where grantee='catalog_app' and table_name='customs_destination_requirement'"));
    assertThat(requirementGrants).extracting(r -> r.get("privilege_type", String.class))
        .contains("SELECT", "INSERT", "UPDATE", "DELETE");
  }

  @Test
  void 已发布版本不能删而草稿会随sku级联带走() {
    // 删 SKU 会级联 sku → customs_attribute → customs_destination_version → 要求表。
    // 这条级联对**已发布**版本是关着的（refuse_destination_version_published_delete），
    // 对**草稿**才放行 —— 与 V25 的 refuse_bom_version_published_delete 同一形状：
    // 「已发布的版本不可删，但草稿要能随着主体一起清掉，否则库里会攒下一批
    // 永远删不掉的孤儿草稿」。

    // 1) 已发布版本：删 SKU 被库层拒绝，且点名是哪个目的地哪一版。
    UUID publishedSku = skuWithApprovedBase(actor, unique("CASCADE_P"), false);
    var publishedRow = published(actor, publishedSku, new DestinationVersionInput(
        "US", 1, "", "", false, at("2020-01-01T00:00:00Z"), null, "",
        List.of(requirement("FCC", "FCC", true)), 0));
    assertThatThrownBy(() -> tx(actor, () -> sql.execute("delete from sku where id=?", publishedSku)))
        .hasMessageContaining("已发布的目的地报关版本不能删除")
        .hasMessageContaining("US");
    // 行还在，要求也在。
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_version where id=?", publishedRow.id()))).hasSize(1);
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_requirement where destination_version_id=?", publishedRow.id())))
        .hasSize(1);

    // 2) 草稿版本：整条链一路级联清干净。
    UUID draftSku = skuWithApprovedBase(actor, unique("CASCADE_D"), false);
    var draftRow = write(actor, () -> destination.save(actor, draftSku, new DestinationVersionInput(
        "DE", 1, "85171300", "", false, at("2020-01-01T00:00:00Z"), null, "",
        List.of(requirement("CE", "CE", true)), 0)));
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_version where id=?", draftRow.row().id()))).hasSize(1);
    tx(actor, () -> sql.execute("delete from sku where id=?", draftSku));
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_version where id=?", draftRow.row().id()))).isEmpty();
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_requirement where destination_version_id=?", draftRow.row().id())))
        .isEmpty();
    // 全库不留孤儿要求行。
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_destination_requirement r where not exists"
            + " (select 1 from customs_destination_version v where v.id=r.destination_version_id)")))
        .isEmpty();
  }

  // ============================================================ HTTP 层

  @Test
  void 写入后经http读回的是真实落库的值() throws Exception {
    UUID sku = skuWithApprovedBase(actor, unique("HTTP"), true);
    published(actor, sku, new DestinationVersionInput(
        "DE", 1, "85171300", "Mobiltelefon", false, at("2020-01-01T00:00:00Z"), null, "德区",
        List.of(requirement("CE", "欧盟 CE 认证", true)), 0));

    http.perform(get("/catalog/customs-destination-versions/skus/" + sku)
            .param("destination", "DE")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = JSON.readTree(result.getResponse().getContentAsString());
          assertThat(body.get("destinationCountryCode").asText()).isEqualTo("DE");
          assertThat(body.get("base").get("hsCode").asText()).isEqualTo("851712");
          assertThat(body.get("versions").size()).isEqualTo(1);
          var row = body.get("versions").get(0);
          assertThat(row.get("versionNo").asInt()).isEqualTo(1);
          assertThat(row.get("status").asText()).isEqualTo("PUBLISHED");
          assertThat(row.get("destinationHsCode").asText()).isEqualTo("85171300");
          assertThat(row.get("effective").asBoolean()).isTrue();
          assertThat(row.get("requirements").size()).isEqualTo(1);
        });

    // 合成出口经 HTTP 也能取到，且与 V85 的回填口对得上。
    http.perform(get("/catalog/customs-declaration/resolve")
            .param("skuId", sku.toString()).param("destination", "DE")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = JSON.readTree(result.getResponse().getContentAsString());
          assertThat(body.get("hsCode").asText()).isEqualTo("85171300");
          assertThat(body.get("hsCodeSource").asText()).isEqualTo("MODULE_3_7_1_2");
          assertThat(body.get("hsCodeVerified").asBoolean()).isTrue();
          assertThat(body.get("declaredName").asText()).isEqualTo("Mobiltelefon");
          assertThat(body.get("statutoryUnitCode").asText()).isEqualTo("PCE");
          assertThat(body.get("declarationElementsSource").asText()).isEqualTo("MODULE_3_7_1_1");
          assertThat(body.get("declarationElementsRef").asText()).hasSizeLessThanOrEqualTo(64);
          assertThat(body.get("declarationElements").get("byCode").get("01").asText())
              .isEqualTo("移动电话机");
          assertThat(body.get("preferentialGranted").asBoolean()).isFalse();
        });
  }

  @Test
  void 无生效版本时端点返回409而不是200或404() throws Exception {
    UUID sku = skuWithApprovedBase(actor, unique("HTTP409"), false);
    write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    // 不是 404（资料存在）也不是 200（**明确告诉下游「存在但不可用」**）。
    http.perform(get("/catalog/customs-declaration/resolve")
            .param("skuId", sku.toString()).param("destination", "DE")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isConflict());
    http.perform(get("/catalog/customs-destination-versions/effective")
            .param("skuId", sku.toString()).param("destination", "DE")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isConflict());
  }

  @Test
  void 写与发布按权限收敛() throws Exception {
    UUID sku = skuWithApprovedBase(actor, unique("PERM"), false);
    var saved = write(actor, () -> destination.save(actor, sku, draft("DE", 1, "85171300", "", 0)));
    // reader 只有 read，没有 update。
    http.perform(put("/catalog/customs-destination-versions/skus/" + sku)
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(JSON.writeValueAsString(draft("DE", 2, "85171400", "", 0))))
        .andExpect(status().isForbidden());
    // editor 能写但不能发布。
    http.perform(post("/catalog/customs-destination-versions/" + saved.row().id() + "/publish")
            .header("Authorization", "Bearer editor")
            .contentType("application/json")
            .content(JSON.writeValueAsString(new DestinationPublishInput(0))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 未认证返回未授权() throws Exception {
    UUID sku = skuOf(actor, unique("AUTH"));
    http.perform(get("/catalog/customs-destination-versions/skus/" + sku)
            .param("destination", "DE").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }
}
