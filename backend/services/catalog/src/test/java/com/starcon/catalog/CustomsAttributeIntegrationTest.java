package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.CustomsAttributeModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
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
 * 3.7.1.1 HS 编码与申报要素：报关属性读写、申报要素维护、审核状态机、
 * <b>供 10 报关引用的只读闸门</b>，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：{@code customs_attribute} 与
 * {@code customs_declaration_element} 全部真的落库，Flyway 真的跑 V94。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的断言</b>：
 *
 * <ol>
 *   <li>{@link #未审核通过时申报资料一律报错()}：本模块存在的理由。V94 第一节 ——
 *       只有 APPROVED 可被 10 报关引用。
 *   <li>{@link #审核通过后申报资料带上回填下游所需的全部字段()}：与 V85
 *       {@code customs_declaration_line} 的逐列对应关系（回填口）。
 *   <li>{@link #审核通过前必须补齐必填申报要素()}：规则 A4 的服务层防线。
 *   <li>{@link #裸sql写非法hs编码被库层拒绝()}：证明约束真的在 DB 上而不是只在 Java。
 *   <li>{@link #租户隔离三断言()}：两租户互不可见、伪造 tenant header 无效、
 *       连接池归还后不串租户。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomsAttributeIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
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
      // 能编辑但**不能审核**：证明「填」与「批」是两个权限
      // （蓝图：必须允许审核流程而非任意填写）。
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

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute", "compliance")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete", "review")
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

  /** 一条合法的申报要素（位次 1、品名、已填值）。 */
  static DeclarationElementInput element(int ordinal, String code, String name, String value, boolean required) {
    return new DeclarationElementInput(ordinal, code, name, value, required);
  }

  /** 默认的两条申报要素：01 品名 / 02 品牌类型，都已填。 */
  static List<DeclarationElementInput> completeElements() {
    return List.of(
        element(1, "01", "品名", "移动电话机", true),
        element(2, "02", "品牌类型", "自有品牌", true));
  }

  /** 合法的报关属性入参。 */
  CustomsInput input(String hsCode, String origin, String name, String value, String unit,
      boolean eligible, String agreement, List<DeclarationElementInput> elements, long version) {
    return new CustomsInput(hsCode, origin, name, decimal(value), unit, eligible, agreement, "", elements, version);
  }

  /** 便捷入参：851712 / CN / 手机 / 无享惠 / 两条齐备要素。 */
  CustomsInput defaultInput(long version) {
    return input("851712", "CN", "移动电话机", "12.5", "PCE", false, "", completeElements(), version);
  }

  /** 保存 + 提交 + 审核通过，返回档案 id（供 declaration-ready 用）。 */
  UUID approved(AccessService.Access who, UUID skuId) {
    CustomsSaved saved = write(who, () -> customs.save(who, skuId, defaultInput(0)));
    CustomsProfile submitted =
        write(who, () -> customs.submit(who, skuId, new SubmitInput(saved.profile().version())));
    assertThat(submitted.status()).isEqualTo("DRAFT");
    assertThat(submitted.submittedAt()).isNotNull();
    CustomsProfile approvedProfile =
        write(who, () -> customs.review(who, skuId, new ReviewInput(true, ""), true));
    assertThat(approvedProfile.status()).isEqualTo("APPROVED");
    return approvedProfile.attributeId();
  }

  // ============================================================ 读写

  @Test
  void 报关属性与申报要素一次提交写入两张表() {
    UUID sku = skuOf(actor, unique("CU"));
    var saved = write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "移动电话机", "12.5", "PCE", false, "", completeElements(), 0)));

    var attr = tx(actor, () -> sql.fetchOne(
        "select hs_code,origin_country_code,declared_name,declared_value_reference,"
            + "statutory_unit_code,preferential_origin_eligible,preference_agreement,status,version"
            + " from customs_attribute where tenant_id=? and sku_id=?", a, sku));
    assertThat(attr.get("hs_code", String.class)).isEqualTo("851712");
    // 原产地按库约定是 varchar(2)，读回来**没有尾随空格**（char(2) 会有）。
    assertThat(attr.get("origin_country_code", String.class)).isEqualTo("CN");
    assertThat(attr.get("declared_name", String.class)).isEqualTo("移动电话机");
    assertThat(attr.get("declared_value_reference", BigDecimal.class)).isEqualByComparingTo(decimal("12.5"));
    assertThat(attr.get("statutory_unit_code", String.class)).isEqualTo("PCE");
    assertThat(attr.get("status", String.class)).isEqualTo("DRAFT");
    assertThat(attr.get("version", Long.class)).isEqualTo(0L);

    var elements = tx(actor, () -> sql.fetch(
        "select ordinal,element_code,element_name,element_value,required"
            + " from customs_declaration_element where tenant_id=? and customs_attribute_id=?"
            + " order by ordinal", a, saved.profile().attributeId()));
    assertThat(elements).hasSize(2);
    assertThat(elements.get(0).get("element_code", String.class)).isEqualTo("01");
    assertThat(elements.get(0).get("element_value", String.class)).isEqualTo("移动电话机");
    assertThat(elements.get(1).get("ordinal", Integer.class)).isEqualTo(2);
    assertThat(elements.get(1).get("required", Boolean.class)).isTrue();
  }

  @Test
  void 原产地在页面上原样回读没有尾随空格() {
    UUID sku = skuOf(actor, unique("CU"));
    // 用户从任何来源粘贴都可能带来小写与空格；归一化在应用层做完。
    write(actor, () -> customs.save(actor, sku,
        input("851712", " cn ", "移动电话机", "1", "PCE", false, "", completeElements(), 0)));
    var view = tx(actor, () -> customs.profile(sku));
    assertThat(view.originCountryCode()).isEqualTo("CN");
    // 长度必须真的是 2：char(2) 会在某些比较里带出填充空格。
    assertThat(view.originCountryCode()).hasSize(2);
    var stored = tx(actor, () -> sql.fetchOne(
        "select length(origin_country_code) len from customs_attribute where sku_id=?", sku));
    assertThat(stored.get("len", Integer.class)).isEqualTo(2);
  }

  @Test
  void 未维护报关属性时档案显示为未维护而不是报错() {
    UUID sku = skuOf(actor, unique("CU"));
    var view = tx(actor, () -> customs.profile(sku));
    assertThat(view.attributeId()).isNull();
    assertThat(view.status()).isNull();
    assertThat(view.elements()).isEmpty();
    // 但**不可报关** —— 这是真实状态，不是「查不到」。
    assertThat(view.declarationReady()).isFalse();
    assertThat(view.blockers()).containsExactly("NOT_RECORDED");
    assertThat(tx(actor, () -> sql.fetch("select * from customs_attribute where sku_id=?", sku)))
        .isEmpty();
  }

  @Test
  void 非法hs编码被服务层拒绝且不写任何一行() {
    UUID sku = skuOf(actor, unique("CU"));
    assertThatThrownBy(() -> write(actor, () -> customs.save(actor, sku,
        input("8517A2", "CN", "手机", "1", "PCE", false, "", completeElements(), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("6–10 位纯数字");
    // 整次提交是原子的：属性与要素都不该留下半截。
    assertThat(tx(actor, () -> sql.fetch("select * from customs_attribute where sku_id=?", sku)))
        .isEmpty();
  }

  @Test
  void 享惠与协定双向一致都被服务层拒绝() {
    UUID sku = skuOf(actor, unique("CU"));
    // 勾了可享惠没填协定
    assertThatThrownBy(() -> write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", true, "", completeElements(), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("协定代码");
    // 没勾可享惠却填了协定
    assertThatThrownBy(() -> write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", false, "RCEP", completeElements(), 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("两个互相矛盾的答案");
  }

  @Test
  void 申报要素位次重复被拒() {
    UUID sku = skuOf(actor, unique("CU"));
    var duplicated = List.of(
        element(1, "01", "品名", "手机", true),
        element(1, "02", "品牌类型", "自有品牌", true));
    assertThatThrownBy(() -> write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", false, "", duplicated, 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("位次不能重复");
  }

  @Test
  void 乐观锁版本不符撞409() {
    UUID sku = skuOf(actor, unique("CU"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    // 用过期的 version 再写一次。
    assertThatThrownBy(() -> write(actor, () -> customs.save(actor, sku, defaultInput(99))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
  }

  @Test
  void 版本号随保存递增并回传给前端做cas() {
    UUID sku = skuOf(actor, unique("CU"));
    var first = write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    assertThat(first.profile().version()).isEqualTo(0L);
    var second = write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    // 版本已递增，回传值必须跟着变，否则前端 CAS 永远打不中。
    assertThat(second.profile().version()).isEqualTo(1L);
  }

  // ============================================================ 审核闸（V94 第一节，本模块存在的理由）

  @Test
  void 未审核通过时申报资料一律报错() {
    UUID sku = skuOf(actor, unique("CU"));

    // 1) 还没维护报关属性。
    assertThatThrownBy(() -> tx(actor, () -> customs.declarationReady(sku)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("尚未审核通过")
        .hasMessageContaining("还没有报关属性");

    // 2) 刚保存的草稿。
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    var draftError =
        org.assertj.core.api.Assertions.catchThrowable(
            () -> tx(actor, () -> customs.declarationReady(sku)));
    assertThat(draftError).isInstanceOf(ResponseStatusException.class);
    assertThat(((ResponseStatusException) draftError).getStatusCode())
        .as("未审核通过时是 409，而不是返回一份看起来能用的数据")
        .isEqualTo(HttpStatus.CONFLICT);
    assertThat(draftError).hasMessageContaining("还没提交审核");

    // 3) 已提交待审。
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    assertThatThrownBy(() -> tx(actor, () -> customs.declarationReady(sku)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("等待审核");
  }

  @Test
  void 被驳回的申报资料同样报错() {
    UUID sku = skuOf(actor, unique("CU"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    write(actor, () -> customs.review(actor, sku, new ReviewInput(false, "HS 码与报关行确认的不一致"), true));
    assertThatThrownBy(() -> tx(actor, () -> customs.declarationReady(sku)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被驳回");
  }

  @Test
  void 审核通过后申报资料带上回填下游所需的全部字段() {
    UUID sku = skuOf(actor, unique("CU"));
    // 走一条带享惠的完整路径，确保返回体的每格都有值。
    write(actor, () -> customs.save(actor, sku,
        input("8517123090", "VN", "移动电话机", "99.99", "PCE", true, "RCEP", completeElements(), 0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    write(actor, () -> customs.review(actor, sku, new ReviewInput(true, ""), true));

    var ready = tx(actor, () -> customs.declarationReady(sku));
    // 与 V85 customs_declaration_line 的逐列对应：
    assertThat(ready.hsCode()).isEqualTo("8517123090");
    assertThat(ready.hsCodeSource()).isEqualTo("MODULE_3_7_1_1");
    // APPROVED 就是「这个码被人审过」的可信来源 → hs_code_verified = true。
    assertThat(ready.hsCodeVerified()).isTrue();
    assertThat(ready.declaredName()).isEqualTo("移动电话机");
    assertThat(ready.originCountryCode()).isEqualTo("VN");
    assertThat(ready.statutoryUnitCode()).isEqualTo("PCE");
    // 定位键必须能填进 varchar(64)：UUID 是 36 字符。
    assertThat(ready.declarationElementsRef()).isEqualTo(ready.declarationElementsRef());
    assertThat(ready.declarationElementsRef()).hasSizeLessThanOrEqualTo(64);
    assertThat(ready.declarationElementsRef())
        .isEqualTo(tx(actor, () -> customs.profile(sku)).attributeId().toString());
    assertThat(ready.declarationElementsSource()).isEqualTo("MODULE_3_7_1_1");
    assertThat(ready.declarationElementStatus()).isEqualTo("MODULE_3_7_1_1_COMPLETE");
    // jsonb 对象形态：键 = elementCode、值 = elementValue（V85 CHECK 要求 object）。
    assertThat(ready.declarationElements().byCode())
        .containsEntry("01", "移动电话机")
        .containsEntry("02", "自有品牌");
    // 同时给有序清单供人工核对。
    assertThat(ready.declarationElements().entries())
        .extracting(DeclarationElement::ordinal).containsExactly(1, 2);
    assertThat(ready.preferentialOriginEligible()).isTrue();
    assertThat(ready.preferenceAgreement()).isEqualTo("RCEP");
    assertThat(ready.reviewedBy()).isNotNull();
    assertThat(ready.reviewedAt()).isNotNull();
    // 申报价值参考只是参考值，不越界替 10 报关算申报货值。
    assertThat(ready.declaredValueReference()).isEqualByComparingTo(decimal("99.99"));
  }

  @Test
  void 审核通过前必须补齐必填申报要素() {
    UUID sku = skuOf(actor, unique("CU"));
    // 规则 A4：一条 required 的要素还空着。
    var incomplete = List.of(
        element(1, "01", "品名", "移动电话机", true),
        element(2, "02", "品牌类型", "   ", true));
    write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", false, "", incomplete, 0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    // 报错必须**逐项点名**，让运营一次改完而不是反复提交试错。
    assertThatThrownBy(() -> write(actor, () -> customs.review(actor, sku, new ReviewInput(true, ""), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("02 品牌类型");

    // 补齐后同一次提交就能通过。
    write(actor, () -> customs.save(actor, sku, defaultInput(2)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(3)));
    var approved = write(actor, () -> customs.review(actor, sku, new ReviewInput(true, ""), true));
    assertThat(approved.status()).isEqualTo("APPROVED");
    assertThat(approved.declarationReady()).isTrue();
  }

  @Test
  void 审核通过前必须至少有一条申报要素() {
    UUID sku = skuOf(actor, unique("CU"));
    write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", false, "", List.of(), 0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    assertThatThrownBy(() -> write(actor, () -> customs.review(actor, sku, new ReviewInput(true, ""), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少填写一条申报要素");
  }

  @Test
  void 驳回必须写明原因() {
    UUID sku = skuOf(actor, unique("CU"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    assertThatThrownBy(() -> write(actor, () -> customs.review(actor, sku, new ReviewInput(false, "  "), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("驳回必须写明原因");
    var rejected = write(actor, () -> customs.review(actor, sku, new ReviewInput(false, "用途填错"), true));
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.rejectReason()).isEqualTo("用途填错");
    assertThat(rejected.reviewedBy()).isNotNull();
  }

  @Test
  void 未提交不能直接审核() {
    UUID sku = skuOf(actor, unique("CU"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    assertThatThrownBy(() -> write(actor, () -> customs.review(actor, sku, new ReviewInput(true, ""), true)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("先提交审核");
  }

  @Test
  void 已审核通过的内容不可直接改要先驳回() {
    UUID sku = skuOf(actor, unique("CU"));
    approved(actor, sku);
    var view = tx(actor, () -> customs.profile(sku));
    // 直接改内容会被拒：改动它等于让 10 报关拿着与审核结论不一致的资料去申报。
    assertThatThrownBy(() -> write(actor, () -> customs.save(actor, sku, defaultInput(view.version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已审核通过");

    // 正确路径：审核里驳回 → 改 → 重新提交 → 再审。
    write(actor, () -> customs.review(actor, sku, new ReviewInput(false, "税号要改"), true));
    var afterReject = tx(actor, () -> customs.profile(sku));
    assertThat(afterReject.status()).isEqualTo("REJECTED");
    var saved = write(actor, () -> customs.save(actor, sku,
        input("851713", "CN", "智能手机", "1", "PCE", false, "", completeElements(), afterReject.version())));
    assertThat(saved.profile().hsCode()).isEqualTo("851713");
    // 保存草稿后回到 DRAFT 且清空上一轮审核痕迹。
    assertThat(saved.profile().status()).isEqualTo("DRAFT");
    assertThat(saved.profile().rejectReason()).isEmpty();
    assertThat(saved.profile().reviewedBy()).isNull();
  }

  @Test
  void 没有审核权限的人不能审核() {
    UUID sku = skuOf(actor, unique("CU"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    // editor 角色有 read+update 但没有 review —— 「能填」不等于「能批」。
    var editor = actors.get("Bearer editor");
    assertThatThrownBy(() -> write(editor, () -> customs.review(editor, sku, new ReviewInput(true, ""), false)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有审核权限");
    // 行没有被改成 APPROVED。
    assertThat(tx(actor, () -> customs.profile(sku)).status()).isEqualTo("DRAFT");
  }

  @Test
  void 审核时间戳写入后读回不偏时区() {
    UUID sku = skuOf(actor, unique("CU"));
    approved(actor, sku);
    var row = tx(actor, () -> sql.fetchOne(
        "select reviewed_at from customs_attribute where sku_id=?", sku));
    var reviewedAt = row.get("reviewed_at", OffsetDateTime.class);
    assertThat(reviewedAt).isNotNull();
    // 与墙钟同一天：偏 -28800s 的双偏移会让审核时刻落到前一天。
    assertThat(reviewedAt.toLocalDate()).isEqualTo(OffsetDateTime.now().toLocalDate());
    // 读回响应层是 ISO-8601 字符串，时区信息不丢。
    var view = tx(actor, () -> customs.profile(sku));
    assertThat(view.reviewedAt()).contains("T");
    assertThat(OffsetDateTime.parse(view.reviewedAt()).toInstant()).isEqualTo(reviewedAt.toInstant());
  }

  // ============================================================ 筛选

  @Test
  void 按hs编码与原产地筛选() {
    String prefix = unique("FILTER");
    UUID phone = skuOf(actor, prefix + "PHONE");
    UUID laptop = skuOf(actor, prefix + "LAPTOP");
    write(actor, () -> customs.save(actor, phone,
        input("851712", "CN", "移动电话机", "1", "PCE", false, "", completeElements(), 0)));
    write(actor, () -> customs.save(actor, laptop,
        input("847130", "US", "便携式电脑", "1", "PCE", false, "", completeElements(), 0)));

    var byHs = tx(actor, () -> customs.search(new CustomsQuery(null, "851712", null, null, null, null, 0, 50)));
    assertThat(byHs.items()).extracting(CustomsRow::skuCode)
        .contains(codeOf(phone)).doesNotContain(codeOf(laptop));

    var byOrigin = tx(actor, () -> customs.search(new CustomsQuery(null, null, "US", null, null, null, 0, 50)));
    assertThat(byOrigin.items()).extracting(CustomsRow::skuCode)
        .contains(codeOf(laptop)).doesNotContain(codeOf(phone));
  }

  @Test
  void 按状态与享惠筛选() {
    String prefix = unique("STATUS");
    UUID draft = skuOf(actor, prefix + "DRAFT");
    UUID approvedSku = skuOf(actor, prefix + "APPROVED");
    write(actor, () -> customs.save(actor, draft, defaultInput(0)));
    approved(actor, approvedSku);

    var drafts = tx(actor, () -> customs.search(new CustomsQuery(null, null, null, "DRAFT", null, null, 0, 50)));
    assertThat(drafts.items()).extracting(CustomsRow::skuCode).contains(codeOf(draft));
    var approvedRows = tx(actor, () -> customs.search(new CustomsQuery(null, null, null, "APPROVED", null, null, 0, 50)));
    assertThat(approvedRows.items()).extracting(CustomsRow::skuCode).contains(codeOf(approvedSku));

    // 「筛不适用任何协定」是一个**有意义的查询**（空串 ≠ 不筛）。
    var noAgreement = tx(actor, () -> customs.search(new CustomsQuery(null, null, null, null, "", null, 0, 50)));
    assertThat(noAgreement.items()).extracting(CustomsRow::skuCode)
        .contains(codeOf(draft), codeOf(approvedSku));
  }

  @Test
  void 列表行给出申报要素计数供审核队列使用() {
    UUID sku = skuOf(actor, unique("COUNT"));
    write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", false, "",
            List.of(element(1, "01", "品名", "手机", true),
                element(2, "02", "品牌类型", "", true)), 0)));
    var rows = tx(actor, () -> customs.search(new CustomsQuery(null, null, null, null, null, null, 0, 50)));
    var row = rows.items().stream().filter(r -> r.skuCode().equals(codeOf(sku))).findFirst().orElseThrow();
    assertThat(row.elementCount()).isEqualTo(2);
    // 必填却还空着的条数：审核人在队列里一眼看出哪些不能通过。
    assertThat(row.requiredMissingCount()).isEqualTo(1);
  }

  @Test
  void 列表分页与总数一致() {
    String prefix = unique("PAGE");
    for (int i = 0; i < 3; i++) {
      UUID sku = skuOf(actor, prefix + i);
      write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    }
    var page0 = tx(actor, () -> customs.search(new CustomsQuery(prefix, null, null, null, null, null, 0, 2)));
    var page1 = tx(actor, () -> customs.search(new CustomsQuery(prefix, null, null, null, null, null, 1, 2)));
    assertThat(page0.items()).hasSize(2);
    assertThat(page0.total()).isEqualTo(3);
    assertThat(page0.totalPages()).isEqualTo(2);
    assertThat(page1.items()).hasSize(1);
    assertThat(page0.items()).doesNotContainAnyElementsOf(page1.items());
  }

  String codeOf(UUID sku) {
    return tx(actor, () -> sql.fetchOne("select code from sku where id=?", sku).get("code", String.class));
  }

  // ============================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言() throws Exception {
    String myPrefix = unique("MINE");
    UUID mySku = skuOf(actor, myPrefix);
    write(actor, () -> customs.save(actor, mySku,
        input("851712", "CN", "移动电话机", "1", "PCE", true, "RCEP", completeElements(), 0)));

    String theirPrefix = unique("THEIRS");
    UUID theirSku = skuOf(actorB, theirPrefix);
    write(actorB, () -> customs.save(actorB, theirSku,
        input("847130", "US", "便携式电脑", "10", "PCE", false, "", completeElements(), 0)));

    // 1) 两租户互不可见：读对方的档案直接 404。
    assertThatThrownBy(() -> tx(actor, () -> customs.profile(theirSku)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> customs.profile(mySku)))
        .isInstanceOf(ResponseStatusException.class);
    // 写对方的档案同样拿不到目标。
    assertThatThrownBy(() -> write(actor, () -> customs.save(actor, theirSku, defaultInput(0))))
        .isInstanceOf(ResponseStatusException.class);
    // 对方的 HS 码不能被本租户改掉。
    assertThat(tx(actorB, () -> customs.profile(theirSku)).hsCode()).isEqualTo("847130");
    // **审核闸也必须跨租户隔离**：拿对方的 SKU 调申报资料出口拿不到任何东西。
    assertThatThrownBy(() -> tx(actor, () -> customs.declarationReady(theirSku)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> write(actor, () -> customs.submit(actor, theirSku, new SubmitInput(0))))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(get("/catalog/customs-attributes/skus/" + mySku)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 申报资料出口同样不认伪造的头。
    http.perform(get("/catalog/customs-attributes/declaration-ready").param("skuId", mySku.toString())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 筛选接口同样不认伪造的头。
    http.perform(get("/catalog/customs-attributes/profiles")
            .param("keyword", myPrefix)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = JSON.readTree(result.getResponse().getContentAsString());
          assertThat(body.get("total").asLong()).isZero();
        });

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言档案与列表都不串。
    for (int i = 0; i < 5; i++) {
      var mine = tx(actor, () -> customs.profile(mySku));
      var theirs = tx(actorB, () -> customs.profile(theirSku));
      assertThat(mine.skuId()).isEqualTo(mySku);
      assertThat(theirs.skuId()).isEqualTo(theirSku);
      assertThat(mine.hsCode()).isEqualTo("851712");
      assertThat(theirs.hsCode()).isEqualTo("847130");
      assertThat(mine.originCountryCode()).isEqualTo("CN");
      assertThat(theirs.originCountryCode()).isEqualTo("US");
      assertThat(mine.preferenceAgreement()).isEqualTo("RCEP");
      assertThat(theirs.preferenceAgreement()).isEmpty();
      assertThat(mine.declaredValueReference()).isEqualByComparingTo(decimal("1"));
      assertThat(theirs.declaredValueReference()).isEqualByComparingTo(decimal("10"));

      var myRows = tx(actor, () -> customs.search(new CustomsQuery(null, null, null, null, null, null, 0, 100)))
          .items();
      var theirRows = tx(actorB, () -> customs.search(new CustomsQuery(null, null, null, null, null, null, 0, 100)))
          .items();
      var myIds = myRows.stream().map(CustomsRow::skuId).toList();
      var theirIds = theirRows.stream().map(CustomsRow::skuId).toList();
      assertThat(myIds).contains(mySku).doesNotContain(theirSku);
      assertThat(theirIds).contains(theirSku).doesNotContain(mySku);
      assertThat(java.util.Collections.disjoint(myIds, theirIds)).isTrue();
    }
  }

  // ============================================================ 库层约束（绕开 Java 直接写 SQL）

  @Test
  void 裸sql写非法hs编码被库层拒绝() {
    UUID sku = skuOf(actor, unique("DBCHK"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    // 绕过服务层：CHECK 必须自己咬住。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set hs_code='8517A2' where sku_id=?", sku)))
        .hasMessageContaining("HS 编码");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set hs_code='851' where sku_id=?", sku)))
        .isNotNull();
    // 值没被改脏。
    assertThat(tx(actor, () -> customs.profile(sku)).hsCode()).isEqualTo("851712");
  }

  @Test
  void 裸sql写非法原产地被库层拒绝() {
    UUID sku = skuOf(actor, unique("DBORIGIN"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    // 入参用 **两位但非大写** 的 'c1'，而不是超长的 'CHN'：
    // origin_country_code 是 varchar(2)（见 新表强制rls且数量列精度正确 与 V94 第一节），
    // 超长值在 PostgreSQL 构造新元组时就被输入函数挡掉，报的是
    // 「value too long for type character varying(2)」——
    // 那条路径根本到不了 BEFORE 触发器与 CHECK，也就拿不到本断言要的可读中文。
    // 'c1' 装得下 varchar(2)，于是它真正走到库层的规则 A1 CHECK 与触发器，
    // 这才是本测试要证明的那一层。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set origin_country_code='c1' where sku_id=?", sku)))
        .hasMessageContaining("原产地");
    assertThat(tx(actor, () -> customs.profile(sku)).originCountryCode()).isEqualTo("CN");
  }

  @Test
  void 裸sql写享惠与协定不一致被库层拒绝() {
    UUID sku = skuOf(actor, unique("DBPREF"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    // 两个方向都要钉：CHECK 与触发器至少有一层咬住即可（两层都在）。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set preferential_origin_eligible=true where sku_id=?", sku)))
        .hasMessageContaining("协定代码");
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set preference_agreement='RCEP' where sku_id=?", sku)))
        .hasMessageContaining("两个互相矛盾的答案");
  }

  @Test
  void 裸sql绕过审核痕迹被库层拒绝() {
    UUID sku = skuOf(actor, unique("DBREVIEW"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    // 「没有审核人的已通过」等于「有人按过通过」这种无法证伪的说法。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set status='APPROVED' where sku_id=?", sku)))
        .hasMessageContaining("审核");
    // 驳回不写理由。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set status='REJECTED',reviewed_by=?,reviewed_at=now()"
            + " where sku_id=?", user, sku)))
        .hasMessageContaining("驳回必须写明原因");
  }

  @Test
  void 裸sql审核通过但申报要素为空被触发器拒绝() {
    UUID sku = skuOf(actor, unique("DBA4"));
    // **要素必须是空的**：规则 A4 之一要求「审核通过前至少填写一条申报要素」，
    // 而这条跨表约束只有触发器能钉（CHECK 看不到子表）。
    // 这里必须用空清单而不是 defaultInput —— defaultInput 带的是两条已填齐的要素，
    // 那样裸 SQL 审核通过本来就合法，触发器也就不会拒（与同文件的
    // 审核通过前必须至少有一条申报要素 同一形状）。
    write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", false, "", List.of(), 0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    // 规则 A4 是跨表约束，只能靠触发器。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set status='APPROVED',reviewed_by=?,reviewed_at=now()"
            + " where sku_id=?", user, sku)))
        .hasMessageContaining("至少填写一条申报要素");
    assertThat(tx(actor, () -> customs.profile(sku)).status()).isEqualTo("DRAFT");
  }

  @Test
  void 裸sql审核通过但必填要素为空被触发器逐项点名() {
    UUID sku = skuOf(actor, unique("DBA4B"));
    write(actor, () -> customs.save(actor, sku,
        input("851712", "CN", "手机", "1", "PCE", false, "",
            List.of(element(1, "01", "品名", "手机", true),
                element(2, "02", "品牌类型", "", true)), 0)));
    write(actor, () -> customs.submit(actor, sku, new SubmitInput(1)));
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_attribute set status='APPROVED',reviewed_by=?,reviewed_at=now()"
            + " where sku_id=?", user, sku)))
        .hasMessageContaining("02 品牌类型");
  }

  @Test
  void 已审核通过后裸sql也删不掉申报要素() {
    UUID sku = skuOf(actor, unique("DBFREEZE"));
    approved(actor, sku);
    // 只拦增改会让「把全部要素删掉」这条路径敞开 —— DB 层必须一起拦。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "delete from customs_declaration_element where customs_attribute_id="
            + "(select id from customs_attribute where sku_id=?)", sku)))
        .hasMessageContaining("已审核通过");
    // 改值同样被拦。
    assertThatThrownBy(() -> tx(actor, () -> sql.execute(
        "update customs_declaration_element set element_value='改掉'"
            + " where customs_attribute_id=(select id from customs_attribute where sku_id=?)", sku)))
        .hasMessageContaining("已审核通过");
    // 要素还在。
    assertThat(tx(actor, () -> customs.profile(sku)).elements()).hasSize(2);
  }

  @Test
  void 运行时角色没有delete权限所以本模块没有物理删除面() {
    // V94 第四节：跨库查不到 10 报关是否已引用，所以刻意不给删除。
    var privs = tx(actor, () -> sql.fetch(
        "select privilege_type from information_schema.role_table_grants"
            + " where grantee='catalog_app' and table_name='customs_attribute'"));
    assertThat(privs).extracting(r -> r.get("privilege_type", String.class))
        .contains("SELECT", "INSERT", "UPDATE");
    assertThat(privs).extracting(r -> r.get("privilege_type", String.class))
        .doesNotContain("DELETE");
  }

  @Test
  void 删sku会级联带走报关属性与申报要素() {
    // ON DELETE CASCADE 仍然照常工作：引用完整性动作由系统执行，
    // 不受 catalog_app 对子表权限的影响（刻意不给 DELETE 权限后的回归点）。
    UUID sku = skuOf(actor, unique("CASCADE"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    assertThat(tx(actor, () -> sql.fetch("select * from customs_attribute where sku_id=?", sku)))
        .hasSize(1);
    // 直接删 sku 行（绕过服务层的状态校验）以验证级联路径本身。
    tx(actor, () -> sql.execute("delete from sku where id=?", sku));
    assertThat(tx(actor, () -> sql.fetch("select * from customs_attribute where sku_id=?", sku)))
        .isEmpty();
    assertThat(tx(actor, () -> sql.fetch(
        "select * from customs_declaration_element e where not exists"
            + " (select 1 from customs_attribute a where a.id=e.customs_attribute_id)")))
        .isEmpty();
  }

  @Test
  void 新表强制rls且数量列精度正确() {
    for (String table : List.of("customs_attribute", "customs_declaration_element")) {
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
    // 申报价值参考保持 NUMERIC(19,6)：禁浮点，且多给的小数位不该被静默四舍五入。
    var columns = tx(actor, () -> sql.fetch(
        "select column_name,numeric_precision p,numeric_scale s from information_schema.columns"
            + " where table_name='customs_attribute' and column_name='declared_value_reference'"));
    assertThat(columns).hasSize(1);
    assertThat(columns.get(0).get("p", Integer.class)).isEqualTo(19);
    assertThat(columns.get(0).get("s", Integer.class)).isEqualTo(6);
    // 原产地是 varchar(2) 而不是 char(2)（char 会空白填充）。
    var originType = tx(actor, () -> sql.fetchOne(
        "select data_type,character_maximum_length len from information_schema.columns"
            + " where table_name='customs_attribute' and column_name='origin_country_code'"));
    assertThat(originType.get("data_type", String.class)).isEqualTo("character varying");
    assertThat(originType.get("len", Integer.class)).isEqualTo(2);
  }

  // ============================================================ HTTP 层

  @Test
  void 写入后经http读回的是真实落库的值() throws Exception {
    UUID sku = skuOf(actor, unique("HTTP"));
    approved(actor, sku);
    http.perform(get("/catalog/customs-attributes/skus/" + sku)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = JSON.readTree(result.getResponse().getContentAsString());
          assertThat(body.get("skuId").asText()).isEqualTo(sku.toString());
          assertThat(body.get("hsCode").asText()).isEqualTo("851712");
          assertThat(body.get("originCountryCode").asText()).isEqualTo("CN");
          assertThat(body.get("declaredValueReference").decimalValue()).isEqualByComparingTo(decimal("12.5"));
          assertThat(body.get("status").asText()).isEqualTo("APPROVED");
          assertThat(body.get("declarationReady").asBoolean()).isTrue();
          assertThat(body.get("elements").size()).isEqualTo(2);
        });
    // 申报资料出口经 HTTP 也能取到，且字段与 V85 的回填口对得上。
    http.perform(get("/catalog/customs-attributes/declaration-ready")
            .param("skuId", sku.toString())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = JSON.readTree(result.getResponse().getContentAsString());
          assertThat(body.get("hsCodeSource").asText()).isEqualTo("MODULE_3_7_1_1");
          assertThat(body.get("hsCodeVerified").asBoolean()).isTrue();
          assertThat(body.get("declarationElementsRef").asText()).hasSizeLessThanOrEqualTo(64);
          assertThat(body.get("declarationElements").get("byCode").get("01").asText())
              .isEqualTo("移动电话机");
        });
  }

  @Test
  void 未审核通过时申报资料端点返回409() throws Exception {
    UUID sku = skuOf(actor, unique("HTTP409"));
    write(actor, () -> customs.save(actor, sku, defaultInput(0)));
    // 不是 404 也不是 200：**409** 明确告诉下游「这份资料存在但不可用」。
    http.perform(get("/catalog/customs-attributes/declaration-ready")
            .param("skuId", sku.toString())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isConflict());
  }

  @Test
  void 写与审核按权限收敛() throws Exception {
    UUID sku = skuOf(actor, unique("PERM"));
    // reader 只有 read，没有 update。
    http.perform(put("/catalog/customs-attributes/skus/" + sku)
            .header("Authorization", "Bearer reader")
            .contentType("application/json")
            .content(JSON.writeValueAsString(defaultInput(0))))
        .andExpect(status().isForbidden());
    // editor 能写但不能审核。
    http.perform(post("/catalog/customs-attributes/skus/" + sku + "/review")
            .header("Authorization", "Bearer editor")
            .contentType("application/json")
            .content(JSON.writeValueAsString(new ReviewInput(true, ""))))
        .andExpect(status().isForbidden());
  }

  @Test
  void 未认证返回未授权() throws Exception {
    UUID sku = skuOf(actor, unique("AUTH"));
    http.perform(get("/catalog/customs-attributes/skus/" + sku).header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void 审核留痕落进审计事件() {
    UUID sku = skuOf(actor, unique("AUDIT"));
    approved(actor, sku);
    var actions = tx(actor, () -> sql.fetch(
        "select action from audit_event where tenant_id=? and resource_id=? order by action",
        a, sku)).stream().map(r -> r.get("action", String.class)).toList();
    assertThat(actions).contains("SkuCustomsAttributeSaved", "SkuCustomsAttributeSubmitted",
        "SkuCustomsAttributeApproved");
  }
}