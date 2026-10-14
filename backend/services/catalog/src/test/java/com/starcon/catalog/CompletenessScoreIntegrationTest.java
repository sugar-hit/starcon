package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static com.starcon.catalog.CompletenessScoreModels.*;
import static com.starcon.catalog.CompletenessScoreRules.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jooq.DSLContext;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.9.2.1 完整度评分：评分现算与分档、六个维度的缺失判定、认证四态、 报关缺失码、两条阻断链的独立结论、缺失项清单、批量评估快照， 以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：{@code sku_customs_declaration} / {@code sku_certification} /
 * {@code completeness_evaluation} 全部真的落库， Flyway 真的跑 V37。连接池固定为 1 条连接：只有复用同一条连接， 才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的断言</b>：
 *
 * <ol>
 *   <li>{@link #满分商品拿到满分且刊登与清关都就绪()}：六个维度全齐时分数必须是 精确的 1.0000 —— 这条同时证明「无必填属性定义算齐全」与「无需认证算齐全」
 *       两个不会误伤新商品的口径。
 *   <li>{@link #缺失项逐条可解释且分数随之下降()}：断言缺失<b>码</b>而不只是计数 —— 「缺 3 项」没法让人去补，「缺 HS 编码 / 申报品名」可以。
 *   <li>{@link #认证四态各有各的缺失码()}：规则 R5 —— 未登记 / 待取得 / 已过期 三者的下一步动作完全不同，合成一条会让运营反复填一个填不了的字段。
 *   <li>{@link #刊登就绪与清关就绪是两个独立结论()}：规则 R3。
 *   <li>{@link #租户隔离三断言()}：两租户互不可见、伪造 tenant header 无效、 连接池归还后不串租户。
 *   <li>{@link #评分快照落库并按jsonb原样读回()}：含 {@code evaluated_at} 的 <b>真实落库值</b>（timestamptz
 *       断言写回的值，不只断言没抛异常）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CompletenessScoreIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

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
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
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
      // 只读角色：只有 spu.read，没有 update —— 用来证明写操作按权限收敛。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute", "compliance")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
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

  @Autowired CompletenessScoreService scores;
  @Autowired CatalogService catalog;
  @Autowired CategoryTreeService categoryTrees;
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
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  String unique(String hint) {
    return hint
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  static BigDecimal decimal(String value) {
    return value == null ? null : new BigDecimal(value);
  }

  /** 建一个 SPU + 一个 SKU（沿用 3.1 已交付的写入路径，不用裸 SQL 造商品）。 */
  UUID spuOf(AccessService.Access who, String code) {
    return write(
        who,
        () ->
            catalog.saveSpu(
                who, null, new SpuInput(code, "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
  }

  UUID skuOf(AccessService.Access who, String code) {
    UUID spuId = spuOf(who, code);
    return write(
        who,
        () ->
            catalog.saveSku(
                who,
                spuId,
                null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  /**
   * 给 SPU 挂一个主分类（类目维度要求它；没有它就一定报 PRIMARY_CATEGORY_UNASSIGNED）。
   *
   * <p>走 3.3.1.1 已交付的 CategoryTreeService 而不是裸 SQL：分类树要求 tree_id + path_ids + 防环，一棵一棵手搓的树会在触发器上撞墙，
   * 而撞出来的报错与本模块要验的东西毫无关系。
   */
  void assignCategory(AccessService.Access who, UUID spuId) {
    var tree =
        write(
            who,
            () ->
                categoryTrees.saveTree(
                    who,
                    null,
                    new CategoryTreeInput(unique("TREE"), "测试树", "INTERNAL", null, true, 0)));
    var node =
        write(
            who,
            () ->
                categoryTrees.save(
                    who,
                    tree.id(),
                    null,
                    new CategoryInput(unique("CAT"), "测试分类", null, 0, true, List.of(), 0)));
    write(
        who,
        () ->
            categoryTrees.saveSpuCategories(
                who, spuId, new SpuCategoryInput(node.id(), List.of())));
  }

  /** 造 n 张图并挂到主体上（主图尺寸可配，用来触发分辨率缺失项）。 */
  void attachImages(
      AccessService.Access who, String ownerType, UUID ownerId, int count, String edgePx) {
    for (int i = 0; i < count; i++) {
      final UUID assetId = UUID.randomUUID();
      final int index = i;
      final String sha =
          String.format("%064x", Math.abs(UUID.randomUUID().getMostSignificantBits() * 31 + i));
      write(
          who,
          () ->
              sql.execute(
                  "insert into media_asset(id,tenant_id,name,media_type,purpose,file_format,mime_type,byte_size,"
                      + "width_px,height_px,content_sha256,storage_key,original_filename,enabled,version)"
                      + " values(?,?,?,'IMAGE',?,'png','image/png',1024,?,?,?,?,?,true,0)",
                  assetId,
                  who.tenantId(),
                  "img-" + index,
                  index == 0 ? "PRIMARY" : "GALLERY",
                  decimal(edgePx),
                  decimal(edgePx),
                  sha,
                  "key/" + assetId,
                  "f" + index + ".png"));
      UUID finalId = assetId;
      write(
          who,
          () ->
              sql.execute(
                  "insert into media_reference(id,tenant_id,asset_id,owner_type,spu_id,sku_id)"
                      + " values(?,?,?,?,?,?)",
                  UUID.randomUUID(),
                  who.tenantId(),
                  finalId,
                  ownerType,
                  "SPU".equals(ownerType) ? ownerId : null,
                  "SKU".equals(ownerType) ? ownerId : null));
    }
  }

  void fillPackage(AccessService.Access who, UUID skuId) {
    write(
        who,
        () ->
            sql.execute(
                "insert into sku_package(id,tenant_id,sku_id,length_mm,width_mm,height_mm,net_weight_g,gross_weight_g,"
                    + "packaging_type,version) values(?,?,?,?,?,?,?,?,?,0)"
                    + " on conflict (tenant_id,sku_id) do nothing",
                UUID.randomUUID(),
                who.tenantId(),
                skuId,
                decimal("200"),
                decimal("150"),
                decimal("100"),
                decimal("500"),
                decimal("800"),
                "纸箱"));
  }

  CustomsDeclaration saveCustoms(
      AccessService.Access who, UUID skuId, String hs, String name, String origin) {
    return write(
        who,
        () ->
            scores.saveCustoms(
                who,
                skuId,
                new CustomsDeclarationInput(hs, name, origin, "台", List.of("材质：棉"), true, 0L)));
  }

  /** 全空的报关属性（连法定计量单位与申报要素都不给）—— 用来验"空行等于未登记"那条拒写。 */
  CustomsDeclaration saveCustomsEmpty(AccessService.Access who, UUID skuId) {
    return write(
        who,
        () ->
            scores.saveCustoms(
                who,
                skuId,
                new CustomsDeclarationInput(null, null, null, null, List.of(), true, 0L)));
  }

  /** 把一个 SKU 补成"六维度全齐"。 */
  UUID completeSku(AccessService.Access who, String prefix) {
    UUID skuId = skuOf(who, unique(prefix));
    UUID spuId =
        tx(
            who,
            () ->
                sql.fetchOne(
                        "select spu_id from sku where tenant_id=? and id=?", who.tenantId(), skuId)
                    .get(0, UUID.class));
    assignCategory(who, spuId);
    attachImages(who, "SKU", skuId, MIN_IMAGE_COUNT, "1200");
    fillPackage(who, skuId);
    saveCustoms(who, skuId, "847130", "便携式电脑", "CN");
    write(
        who,
        () ->
            scores.saveCertification(
                who,
                skuId,
                null,
                new CertificationInput(
                    "CE", "欧盟 CE", "NOT_REQUIRED", "", null, null, true, "", 0L)));
    return skuId;
  }

  UUID spuIdOf(AccessService.Access who, UUID skuId) {
    return tx(
        who,
        () ->
            sql.fetchOne("select spu_id from sku where tenant_id=? and id=?", who.tenantId(), skuId)
                .get(0, UUID.class));
  }

  List<String> missingCodes(CompletenessScorecard card) {
    return card.missing().stream().map(CompletenessCheck::code).toList();
  }

  // ============================================================ 评分：满分与确定性

  @Test
  void 满分商品拿到满分且刊登与清关都就绪() {
    UUID sku = completeSku(actor, "FULL");
    var card = tx(actor, () -> scores.card("SKU", sku));

    assertThat(card.score()).isEqualByComparingTo("1.0000");
    assertThat(card.grade()).isEqualTo("EXCELLENT");
    assertThat(card.missing()).isEmpty();
    assertThat(card.listingReady()).isTrue();
    assertThat(card.customsReady()).isTrue();
    assertThat(card.dimensions()).hasSize(6);
    assertThat(card.dimensions()).allMatch(d -> d.total() >= 1);
    // 六项权重合计 1.0000（V37 第一节）：这是「改了权重忘了配平」的唯一兜底断言。
    assertThat(
            card.dimensions().stream()
                .map(d -> d.weight())
                .reduce(BigDecimal.ZERO, BigDecimal::add))
        .isEqualByComparingTo("1.0000");

    // 同��数据读两次分数必须完全一致（评分是确定性的现算，不含随机或时间依赖）。
    var again = tx(actor, () -> scores.card("SKU", sku));
    assertThat(again.score()).isEqualByComparingTo(card.score());
  }

  @Test
  void 评分是现算的且随主数据变化() {
    UUID sku = completeSku(actor, "LIVE");
    assertThat(tx(actor, () -> scores.card("SKU", sku)).score()).isEqualByComparingTo("1.0000");

    // 删掉报关行 → 分数必须立刻掉下来（不读任何缓存/快照）
    write(
        actor,
        () -> {
          scores.deleteCustoms(actor, sku, 0L);
          return Boolean.TRUE;
        });
    var afterDelete = tx(actor, () -> scores.card("SKU", sku));
    assertThat(afterDelete.score()).isLessThan(BigDecimal.ONE);
    assertThat(missingCodes(afterDelete)).contains("CUSTOMS_DECLARATION_MISSING");
    assertThat(afterDelete.customsReady()).isFalse();
    // 报关缺失不影响刊登就绪 —— 这正是规则 R3 的两个结论要分开的理由。
    assertThat(afterDelete.listingReady()).isTrue();
  }

  @Test
  void 新建商品没有变体时记一条无变体缺失项而不是把权重摊开() {
    UUID spu = spuOf(actor, unique("EMPTY"));
    var card = tx(actor, () -> scores.card("SPU", spu));
    // 规则 R4：无变体的 SPU 在三条 SKU 侧维度上各记一条 SPU_HAS_NO_SKU，
    // 于是 customs_ratio / packaging_ratio / certification_ratio 都是 0 而不是"不算"。
    long noSku = card.missing().stream().filter(c -> c.code().equals("SPU_HAS_NO_SKU")).count();
    assertThat(noSku).isEqualTo(3);
    for (CompletenessScoreModels.CompletenessDimension dim : card.dimensions()) {
      if (List.of("CUSTOMS", "PACKAGING", "CERTIFICATION").contains(dim.dimension())) {
        assertThat(dim.satisfied()).isZero();
        assertThat(dim.total()).isEqualTo(1);
      }
    }
  }

  // ============================================================ 缺失项

  @Test
  void 缺失项逐条可解释且分数随之下降() {
    UUID sku = skuOf(actor, unique("BARE"));
    UUID spuId =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select spu_id from sku where tenant_id=? and id=?", actor.tenantId(), sku)
                    .get(0, UUID.class));
    // 只给一张小图：数量不足 + 主图分辨率不足；没有报关、没有包装、没有认证。
    attachImages(actor, "SKU", sku, 1, "800");
    var card = tx(actor, () -> scores.card("SKU", sku));

    List<String> codes = missingCodes(card);
    assertThat(codes)
        .contains(
            "CUSTOMS_DECLARATION_MISSING",
            "PACKAGE_RECORD_MISSING",
            "IMAGE_COUNT_BELOW_MIN",
            "PRIMARY_IMAGE_EDGE_TOO_SMALL",
            "CERTIFICATION_NOT_REGISTERED",
            "PRIMARY_CATEGORY_UNASSIGNED");
    assertThat(card.score()).isLessThan(BigDecimal.ONE);
    assertThat(card.grade()).isIn("POOR", "FAIR");

    // 每条缺失项都必须带「主体名 + 下一步」，否则清单没法驱动补全。
    for (CompletenessCheck check : card.missing()) {
      assertThat(check.subjectLabel()).as(check.code()).isNotBlank();
      assertThat(check.remedy()).as(check.code()).isNotBlank();
      assertThat(check.detail()).as(check.code()).isNotBlank();
    }
    assertThat(spuId).isNotNull();
  }

  @Test
  void 报关缺失按字段逐条报告而不是一条打包() {
    UUID sku = skuOf(actor, unique("HS"));
    // 只填品名：HS 编码与原产国仍缺 —— 三条缺失码要分别出现，因为
    // 「建档」与「补字段」是两种不同的下一步动作。
    saveCustoms(actor, sku, "", "便携式电脑", "");
    var card = tx(actor, () -> scores.card("SKU", sku));
    List<String> codes = missingCodes(card);
    // 填了申报品名、没填 HS 编码与原产国：两个"补字段"缺失码要分别出现
    assertThat(codes).contains("HS_CODE_MISSING", "ORIGIN_COUNTRY_MISSING");
    // 报关行已建（只是没填全）→ 不能再报"尚未登记"，否则运营会去建档而发现行已经在了
    assertThat(codes).doesNotContain("CUSTOMS_DECLARATION_MISSING", "DECLARED_NAME_MISSING");
  }

  @Test
  void 报关为空行被拒因为它与未登记在评分里完全一样() {
    UUID sku = skuOf(actor, unique("EMPTYHS"));
    assertThatThrownBy(() -> saveCustomsEmpty(actor, sku))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少要填一项");
  }

  @Test
  void hs编码会去掉小数点与短横线所以同码只有一种写法() {
    UUID sku = skuOf(actor, unique("HSFMT"));
    var saved = saveCustoms(actor, sku, "8471.30-01", "便携式电脑", "cn");
    assertThat(saved.hsCode()).isEqualTo("84713001");
    assertThat(saved.originCountry()).isEqualTo("CN");
    // 经 HTTP 读回的是真实落库的值
    assertThat(
            tx(
                actor,
                () ->
                    sql.fetchOne(
                            "select hs_code, origin_country from sku_customs_declaration where tenant_id=? and sku_id=?",
                            a,
                            sku)
                        .get("hs_code", String.class)))
        .isEqualTo("84713001");
  }

  @Test
  void 原产国格式非法被拒() {
    UUID sku = skuOf(actor, unique("BADCC"));
    assertThatThrownBy(() -> saveCustoms(actor, sku, "847130", "电脑", "CHN"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("两位码");
  }

  // ============================================================ 认证四态（规则 R5）

  @Test
  void 认证四态各有各的缺失码() {
    // (1) 一行都没有 → CERTIFICATION_NOT_REGISTERED
    UUID none = skuOf(actor, unique("CERT-NONE"));
    assertThat(missingCodes(tx(actor, () -> scores.card("SKU", none))))
        .contains("CERTIFICATION_NOT_REGISTERED");
    assertThat(tx(actor, () -> scores.certifications(none)).completeness()).isEqualTo("NONE");

    // (2) 全部已申请 → CERTIFICATION_PENDING_ONLY（要等审批，补录编号救不了）
    UUID pending = skuOf(actor, unique("CERT-PEND"));
    write(
        actor,
        () ->
            scores.saveCertification(
                actor,
                pending,
                null,
                new CertificationInput("FCC", "美国 FCC", "PENDING", "", null, null, true, "", 0L)));
    assertThat(missingCodes(tx(actor, () -> scores.card("SKU", pending))))
        .contains("CERTIFICATION_PENDING_ONLY");
    assertThat(tx(actor, () -> scores.certifications(pending)).completeness()).isEqualTo("PENDING");

    // (3) 全部已过期 → CERTIFICATION_EXPIRED_ONLY（要发起续期）
    UUID expired = skuOf(actor, unique("CERT-EXP"));
    write(
        actor,
        () ->
            scores.saveCertification(
                actor,
                expired,
                null,
                new CertificationInput(
                    "UL",
                    "美国 UL",
                    "HELD",
                    "UL-1",
                    LocalDate.now().minusYears(3),
                    LocalDate.now().minusYears(1),
                    true,
                    "",
                    0L)));
    assertThat(missingCodes(tx(actor, () -> scores.card("SKU", expired))))
        .contains("CERTIFICATION_EXPIRED_ONLY");
    assertThat(tx(actor, () -> scores.certifications(expired)).completeness()).isEqualTo("EXPIRED");

    // (4) 无需认证 → 算齐全（这条最关键：没有它，不需要认证的商品永远红着）
    UUID noneRequired = skuOf(actor, unique("CERT-NR"));
    write(
        actor,
        () ->
            scores.saveCertification(
                actor,
                noneRequired,
                null,
                new CertificationInput("PSE", "", "NOT_REQUIRED", "", null, null, true, "", 0L)));
    var ok = tx(actor, () -> scores.card("SKU", noneRequired));
    assertThat(missingCodes(ok))
        .doesNotContain(
            "CERTIFICATION_NOT_REGISTERED",
            "CERTIFICATION_PENDING_ONLY",
            "CERTIFICATION_EXPIRED_ONLY");
    var dim =
        ok.dimensions().stream()
            .filter(d -> d.dimension().equals("CERTIFICATION"))
            .findFirst()
            .orElseThrow();
    assertThat(dim.complete()).isTrue();

    // (5) 标了「不计入评分」的行不进评分，但仍留在档案里可查
    UUID unscored = skuOf(actor, unique("CERT-UN"));
    write(
        actor,
        () ->
            scores.saveCertification(
                actor,
                unscored,
                null,
                new CertificationInput("REACH", "", "PENDING", "", null, null, false, "", 0L)));
    assertThat(missingCodes(tx(actor, () -> scores.card("SKU", unscored))))
        .contains("CERTIFICATION_NOT_REGISTERED");
    assertThat(tx(actor, () -> scores.certifications(unscored)).items()).hasSize(1);
  }

  @Test
  void 已取得但没有证书编号在服务层与库层都被拒() {
    UUID sku = skuOf(actor, unique("CERT-NONO"));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        scores.saveCertification(
                            actor,
                            sku,
                            null,
                            new CertificationInput(
                                "CE", "", "HELD", "", null, null, true, "", 0L))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("证书编号");
    // 绕过服务层直连 SQL 也写不进去（触发器兜底，CHECK 可被 DROP 绕过而触发器不能）
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        sql.execute(
                            "insert into sku_certification(id,tenant_id,sku_id,certification_code,status,scored,version)"
                                + " values(?,?,?,'CE','HELD',true,0)",
                            UUID.randomUUID(),
                            a,
                            sku)))
        .hasMessageContaining("证书编号");
  }

  @Test
  void 认证到期日当天仍然有效() {
    UUID sku = skuOf(actor, unique("CERT-EDGE"));
    LocalDate today = LocalDate.now();
    write(
        actor,
        () ->
            scores.saveCertification(
                actor,
                sku,
                null,
                new CertificationInput(
                    "CE", "", "HELD", "CE-1", today.minusYears(1), today, true, "", 0L)));
    assertThat(tx(actor, () -> scores.certifications(sku)).completeness()).isEqualTo("COMPLETE");
    assertThat(missingCodes(tx(actor, () -> scores.card("SKU", sku))))
        .doesNotContain("CERTIFICATION_EXPIRED_ONLY");
  }

  @Test
  void 同一sku的同一认证种类只能有一条() {
    UUID sku = skuOf(actor, unique("CERT-DUP"));
    write(
        actor,
        () ->
            scores.saveCertification(
                actor,
                sku,
                null,
                new CertificationInput("CE", "", "HELD", "CE-1", null, null, true, "", 0L)));
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        scores.saveCertification(
                            actor,
                            sku,
                            null,
                            new CertificationInput(
                                "CE", "", "HELD", "CE-2", null, null, true, "", 0L))))
        // 唯一索引在 DB 层裁决：同租户同 SKU 同认证种类只能有一条。
        // 断言 stack trace 而不是 message —— Spring 把它包成
        // DataIntegrityViolationException，message 是 jOOQ 的 SQL 串，
        // 约束名在 root cause 里。
        .hasStackTraceContaining("sku_certification_unique_code");
  }

  // ============================================================ 分档（规则 R2）

  @Test
  void 分档阈值作用在比值上且边界值归上一档() {
    // 0.7500 / 0.7499 / 0.6000 / 0.5999 四个边界都要钉死 ——
    // 用 compareTo 而不是 equals 是为了让恰好 0.75 的商品不掉档。
    assertThat(CompletenessScoreRules.gradeOf(decimal("0.9000"))).isEqualTo("EXCELLENT");
    assertThat(CompletenessScoreRules.gradeOf(decimal("0.8999"))).isEqualTo("GOOD");
    assertThat(CompletenessScoreRules.gradeOf(decimal("0.7500"))).isEqualTo("GOOD");
    assertThat(CompletenessScoreRules.gradeOf(decimal("0.7499"))).isEqualTo("FAIR");
    assertThat(CompletenessScoreRules.gradeOf(decimal("0.6000"))).isEqualTo("FAIR");
    assertThat(CompletenessScoreRules.gradeOf(decimal("0.5999"))).isEqualTo("POOR");
    assertThat(CompletenessScoreRules.gradeOf(decimal("0.0000"))).isEqualTo("POOR");
  }

  @Test
  void 评分标度固定四位且落在01之间() {
    // 一个"少一样"的商品：分数必须仍是 4 位小数（numeric(5,4) 的落库精度）。
    UUID sku = completeSku(actor, "SCALE");
    write(
        actor,
        () -> {
          scores.deleteCustoms(actor, sku, 0L);
          return Boolean.TRUE;
        });
    var card = tx(actor, () -> scores.card("SKU", sku));
    assertThat(card.score().scale()).isEqualTo(4);
    assertThat(card.score()).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
  }

  // ============================================================ 缺失项清单（批量补全）

  @Test
  void 缺失项清单按缺失码聚合并按影响面降序() {
    String prefix = unique("GAP");
    for (int i = 0; i < 3; i++) skuOf(actor, prefix + i);
    var page =
        tx(
            actor,
            () ->
                scores.gaps(
                    new CompletenessQuery(
                        null, "SKU", null, null, null, null, null, null, null, null, null, 0, 50)));
    var customsGap =
        page.items().stream()
            .filter(g -> g.code().equals("CUSTOMS_DECLARATION_MISSING"))
            .findFirst()
            .orElseThrow();
    assertThat(customsGap.affectedCount()).isGreaterThanOrEqualTo(3);
    assertThat(customsGap.dimension()).isEqualTo("CUSTOMS");
    assertThat(customsGap.sampleOwners()).isNotEmpty();
    assertThat(customsGap.nextEntry()).isNotBlank();
    // 降序：影响面最大的在第一条
    long first = page.items().get(0).affectedCount();
    for (int i = 1; i < page.items().size(); i++)
      assertThat(page.items().get(i).affectedCount()).isLessThanOrEqualTo(first);
    assertThat(page.byDimension()).hasSize(6);
  }

  @Test
  void 清单的byDimension与按维度筛选口径一致() {
    var summary =
        tx(
            actor,
            () ->
                scores.gapSummary(
                    new CompletenessQuery(
                        null, "SKU", null, null, null, null, null, null, null, null, null, 0, 20)));
    assertThat(summary.byDimension()).hasSize(6);
    assertThat(summary.byGrade()).hasSize(4);
    assertThat(summary.ownerCount()).isPositive();
    assertThat(summary.missingCheckCount()).isPositive();
    long dimensionSum =
        summary.byDimension().stream()
            .mapToLong(CompletenessScoreRules.DimensionGap::missingCount)
            .sum();
    assertThat(dimensionSum).isEqualTo(summary.missingCheckCount());
  }

  // ============================================================ 批量评估快照

  @Test
  void 评分快照落库并按jsonb原样读回() throws Exception {
    UUID sku = skuOf(actor, unique("EVAL"));
    var result =
        write(
            actor,
            () ->
                scores.evaluate(
                    actor,
                    new CompletenessEvaluationInput(
                        "SKU", List.of(sku, UUID.randomUUID()), "批量补全第一轮")));

    assertThat(result.requested()).isEqualTo(2);
    assertThat(result.succeeded()).isEqualTo(1);
    assertThat(result.failed()).isEqualTo(1); // 不存在的主体按条失败，不中断整批
    assertThat(result.failures()).hasSize(1);
    var snapshot = result.evaluations().get(0);
    assertThat(snapshot.missing()).isNotEmpty();
    assertThat(snapshot.ownerId()).isEqualTo(sku);

    // jsonb 两列都要能原样读回（缺失项清单不是只写不算的死数据）
    var raw =
        tx(
            actor,
            () ->
                sql.fetchOne(
                    "select dimension_scores, missing_checks, missing_count from completeness_evaluation"
                        + " where tenant_id=? and id=?",
                    a,
                    snapshot.id()));
    assertThat(raw.get("dimension_scores", String.class)).contains("CUSTOMS");
    assertThat(raw.get("missing_checks", String.class)).contains("CUSTOMS_DECLARATION_MISSING");
    assertThat(raw.get("missing_count", Integer.class)).isEqualTo(snapshot.missing().size());

    // evaluated_at 是 timestamptz：断言**写回的真实值**，不只断言没抛异常。
    var readBack = tx(actor, () -> scores.evaluation(snapshot.id()));
    assertThat(readBack.evaluatedAt()).isNotNull();
    var stored =
        tx(
            actor,
            () ->
                sql.fetchOne(
                        "select evaluated_at from completeness_evaluation where tenant_id=? and id=?",
                        a,
                        snapshot.id())
                    .get("evaluated_at", java.time.OffsetDateTime.class));
    assertThat(stored).isNotNull();
    // 服务层给的是 UTC ISO 串，读回来要能解析成同一个时刻
    assertThat(java.time.OffsetDateTime.parse(readBack.evaluatedAt()).toInstant())
        .isEqualTo(stored.toInstant());

    // 评估人来自已认证成员关系，不接受客户端指定
    assertThat(readBack.evaluatedBy()).isEqualTo(user);
  }

  @Test
  void 快照不参与读出口删掉它评分功能完全不受影响() {
    UUID sku = completeSku(actor, "NOSNAP");
    var before = tx(actor, () -> scores.card("SKU", sku)).score();
    write(
        actor,
        () -> scores.evaluate(actor, new CompletenessEvaluationInput("SKU", List.of(sku), "留个快照")));
    tx(actor, () -> sql.execute("delete from completeness_evaluation where tenant_id=?", a));
    var after = tx(actor, () -> scores.card("SKU", sku)).score();
    assertThat(after).isEqualByComparingTo(before);
    assertThat(after).isEqualByComparingTo("1.0000");
  }

  // ============================================================ 预检出口

  @Test
  void 刊登预检与清关预检分别按两条链拦() {
    UUID blocked = skuOf(actor, unique("PRE-BAD"));
    UUID blockedSpu = spuIdOf(actor, blocked);
    UUID ok = completeSku(actor, "PRE-OK");
    UUID okSpu = spuIdOf(actor, ok);
    // 刊登预检按 SPU 寻址（一个 SPU 刊登时是一个整体，缺的是它每个变体的并集）
    var listing = tx(actor, () -> scores.listingPrecheck(List.of(blockedSpu, okSpu)));
    assertThat(listing.checkedCount()).isEqualTo(2);
    assertThat(listing.blockedCount()).isEqualTo(1);
    assertThat(listing.blockers()).hasSize(1);
    assertThat(listing.blockers().get(0).missingCodes()).isNotEmpty();

    // 只缺报关的 SKU：刊登过、清关不过
    UUID customsOnly = skuOf(actor, unique("PRE-CUS"));
    UUID spuId =
        tx(
            actor,
            () ->
                sql.fetchOne("select spu_id from sku where tenant_id=? and id=?", a, customsOnly)
                    .get(0, UUID.class));
    assignCategory(actor, spuId);
    attachImages(actor, "SKU", customsOnly, MIN_IMAGE_COUNT, "1200");
    fillPackage(actor, customsOnly);
    write(
        actor,
        () ->
            scores.saveCertification(
                actor,
                customsOnly,
                null,
                new CertificationInput("CE", "", "NOT_REQUIRED", "", null, null, true, "", 0L)));
    var customs = tx(actor, () -> scores.customsPrecheck(List.of(customsOnly)));
    assertThat(customs.allowed()).isFalse();
    assertThat(customs.blockers().get(0).missingCodes()).contains("CUSTOMS_DECLARATION_MISSING");
    var customListing =
        tx(actor, () -> scores.listingPrecheck(List.of(spuIdOf(actor, customsOnly))));
    assertThat(customListing.allowed()).isTrue();
  }

  // ============================================================ 列表与概览

  @Test
  void 列表真分页且总数与筛选口径一致() {
    String prefix = unique("PAGE");
    for (int i = 0; i < 3; i++) skuOf(actor, prefix + i);
    var all =
        tx(
            actor,
            () ->
                scores.list(
                    new CompletenessQuery(
                        null, "SKU", null, null, null, null, null, null, null, null, null, 0,
                        100)));
    var page =
        tx(
            actor,
            () ->
                scores.list(
                    new CompletenessQuery(
                        null, "SKU", null, null, null, null, null, null, null, null, null, 0, 2)));
    assertThat(page.total()).isEqualTo(all.total());
    assertThat(page.items()).hasSizeLessThanOrEqualTo(2);
    assertThat(page.gradeCounts()).hasSize(4);
    // 默认按分数升序：治理页先看要补的
    assertThat(page.note()).contains("现算");

    var overview = tx(actor, () -> scores.overview());
    assertThat(overview.skuCount()).isPositive();
    assertThat(overview.gradeCounts()).hasSize(4);
    assertThat(overview.dimensionGaps()).hasSize(6);
  }

  @Test
  void 关键词筛选与like转义() {
    String prefix = unique("KW");
    skuOf(actor, prefix + "-A");
    var hit =
        tx(
            actor,
            () ->
                scores.list(
                    new CompletenessQuery(
                        prefix, "SKU", null, null, null, null, null, null, null, null, null, 0,
                        50)));
    assertThat(hit.total()).isPositive();
    // 搜 % 不能变成「匹配所有」——LIKE 通配符必须被转义
    var wildcard =
        tx(
            actor,
            () ->
                scores.list(
                    new CompletenessQuery(
                        "%", "SKU", null, null, null, null, null, null, null, null, null, 0, 50)));
    assertThat(wildcard.total()).isZero();
  }

  // ============================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言() throws Exception {
    String myPrefix = unique("MINE");
    UUID mySku = completeSku(actor, myPrefix);
    UUID mySpu =
        tx(
            actor,
            () ->
                sql.fetchOne("select spu_id from sku where tenant_id=? and id=?", a, mySku)
                    .get(0, UUID.class));

    String theirPrefix = unique("THEIRS");
    UUID theirSku = completeSku(actorB, theirPrefix);

    // 1) 两租户互不可见：读对方的评分卡 404。
    assertThatThrownBy(() -> tx(actor, () -> scores.card("SKU", theirSku)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> scores.card("SKU", mySku)))
        .isInstanceOf(ResponseStatusException.class);
    // 读对方的报关属性拿不到（且不能靠写对方的报关属性来改出可见性）。
    assertThat(tx(actor, () -> scores.customsOf(theirSku))).isNull();
    assertThat(tx(actorB, () -> scores.customsOf(mySku))).isNull();
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        scores.saveCustoms(
                            actor,
                            theirSku,
                            new CustomsDeclarationInput(
                                "999999", "别人的货", "US", "台", List.of(), true, 0L))))
        .isInstanceOf(ResponseStatusException.class);
    // 对方的报关属性未被本租户改掉
    assertThat(
            tx(
                actorB,
                () ->
                    sql.fetchOne(
                            "select declared_name from sku_customs_declaration where tenant_id=? and sku_id=?",
                            b,
                            theirSku)
                        .get("declared_name", String.class)))
        .isEqualTo("便携式电脑");
    // 对方变体的认证集合读不到
    assertThatThrownBy(() -> tx(actor, () -> scores.certifications(theirSku)))
        .isInstanceOf(ResponseStatusException.class);
    // 评估快照也隔离
    var mine =
        write(
            actor,
            () ->
                scores.evaluate(
                    actor, new CompletenessEvaluationInput("SKU", List.of(mySku), "我的")));
    assertThatThrownBy(() -> tx(actorB, () -> scores.evaluation(mine.evaluations().get(0).id())))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系。
    http.perform(
            get("/catalog/completeness/SKU/" + mySku)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 清单接口同样不认伪造的头。
    http.perform(
            get("/catalog/completeness/gaps")
                .param("keyword", myPrefix)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              var body = JSON.readTree(result.getResponse().getContentAsString());
              assertThat(body.get("total").asLong()).isZero();
            });
    // 报关属性读取同样不认：返回 200 但**没有 body**（对方租户的行读不到，
    // 而"没登记"与"读不到"对客户端是同一个形状 —— 这正是租户隔离该有的样子）。
    // 断言"拿不到对方的数据"而不是断言 body 等于某个具体字符串：
    // 空 body 是 Spring 对 null 返回值的正常渲染，写死 "null" 会把断言绑死在
    // 一个与本模块语义无关的序列化细节上。
    http.perform(
            get("/catalog/completeness/customs/skus/" + mySku)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("847130")
                    .doesNotContain("便携式电脑"));

    // 3) 连接池归还后不串租户（单连接反复交替 5 轮），逐轮断言评分卡与列表都不串。
    for (int i = 0; i < 5; i++) {
      var myCard = tx(actor, () -> scores.card("SKU", mySku));
      var theirCard = tx(actorB, () -> scores.card("SKU", theirSku));
      assertThat(myCard.ownerId()).isEqualTo(mySku);
      assertThat(theirCard.ownerId()).isEqualTo(theirSku);
      assertThat(myCard.ownerCode()).startsWith(myPrefix);
      assertThat(theirCard.ownerCode()).startsWith(theirPrefix);

      var myRows =
          tx(
                  actor,
                  () ->
                      scores.list(
                          new CompletenessQuery(
                              null, "SKU", null, null, null, null, null, null, null, null, null, 0,
                              100)))
              .items();
      var theirRows =
          tx(
                  actorB,
                  () ->
                      scores.list(
                          new CompletenessQuery(
                              null, "SKU", null, null, null, null, null, null, null, null, null, 0,
                              100)))
              .items();
      var myIds = myRows.stream().map(CompletenessRow::ownerId).toList();
      var theirIds = theirRows.stream().map(CompletenessRow::ownerId).toList();
      assertThat(myIds).contains(mySku).doesNotContain(theirSku);
      assertThat(theirIds).contains(theirSku).doesNotContain(mySku);
      assertThat(java.util.Collections.disjoint(myIds, theirIds)).isTrue();

      var mySpuCard = tx(actor, () -> scores.card("SPU", mySpu));
      assertThat(mySpuCard.ownerId()).isEqualTo(mySpu);
      assertThat(mySpuCard.skuCount()).isEqualTo(1);
    }
  }

  // ============================================================ 库层约束

  @Test
  void 三张新表都强制rls() {
    for (String table :
        List.of("sku_customs_declaration", "sku_certification", "completeness_evaluation")) {
      var row =
          tx(
              actor,
              () ->
                  sql.fetchOne(
                      "select relrowsecurity, relforcerowsecurity from pg_class where relname=? and relkind='r'",
                      table));
      assertThat(row).as(table).isNotNull();
      assertThat(Boolean.TRUE.equals(row.get("relrowsecurity", Boolean.class))).as(table).isTrue();
      assertThat(Boolean.TRUE.equals(row.get("relforcerowsecurity", Boolean.class)))
          .as(table)
          .isTrue();
    }
  }

  @Test
  void 评分列是numeric54而不是百分数() {
    var columns =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select column_name, numeric_precision p, numeric_scale s from information_schema.columns"
                        + " where table_name='completeness_evaluation' and column_name='score'"));
    assertThat(columns).hasSize(1);
    // NUMERIC(5,4) 装不下 100.0000 —— 这是刻意的，逼所有读出口用 0..1 比值
    assertThat(columns.get(0).get("p", Integer.class)).isEqualTo(5);
    assertThat(columns.get(0).get("s", Integer.class)).isEqualTo(4);
  }

  @Test
  void 报关与认证列的数值与文本宽度与迁移一致() {
    var textColumns =
        tx(
            actor,
            () ->
                sql.fetch(
                    "select column_name, character_maximum_length len from information_schema.columns"
                        + " where table_name='sku_customs_declaration' and column_name in"
                        + " ('hs_code','declared_name','origin_country','legal_unit')"));
    assertThat(textColumns).hasSize(4);
    assertThat(textColumns)
        .allMatch(
            r ->
                r.get("column_name", String.class).equals("hs_code")
                    ? r.get("len", Integer.class) == 32
                    : r.get("column_name", String.class).equals("declared_name")
                        ? r.get("len", Integer.class) == 200
                        : r.get("column_name", String.class).equals("origin_country")
                            ? r.get("len", Integer.class) == 2
                            : r.get("len", Integer.class) == 32);
  }

  @Test
  void 一个sku只能有一份报关属性() {
    UUID sku = skuOf(actor, unique("ONECUS"));
    saveCustoms(actor, sku, "847130", "电脑", "CN");
    assertThatThrownBy(
            () ->
                sql.execute(
                    "insert into sku_customs_declaration(id,tenant_id,sku_id,hs_code,declared_name,version)"
                        + " values(?,?,?,'847131','另一个',0)",
                    UUID.randomUUID(),
                    a,
                    sku))
        .hasMessageContaining("sku_customs_declaration");
  }

  @Test
  void 经http读回的是真实落库的值() throws Exception {
    UUID sku = completeSku(actor, "HTTP");
    UUID spuId =
        tx(
            actor,
            () ->
                sql.fetchOne("select spu_id from sku where tenant_id=? and id=?", a, sku)
                    .get(0, UUID.class));
    http.perform(get("/catalog/completeness/SKU/" + sku).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              var body = JSON.readTree(result.getResponse().getContentAsString());
              assertThat(body.get("score").decimalValue()).isEqualByComparingTo("1.0000");
              assertThat(body.get("grade").asText()).isEqualTo("EXCELLENT");
              assertThat(body.get("scorePercent").asText()).isEqualTo("100.00");
              assertThat(body.get("listingReady").asBoolean()).isTrue();
              assertThat(body.get("customsReady").asBoolean()).isTrue();
              assertThat(body.get("dimensions")).hasSize(6);
            });
    // SPU 侧：变体数与类目都要读出来
    http.perform(
            get("/catalog/completeness/SPU/" + spuId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              var body = JSON.readTree(result.getResponse().getContentAsString());
              assertThat(body.get("ownerType").asText()).isEqualTo("SPU");
              assertThat(body.get("skuCount").asLong()).isEqualTo(1);
            });
    // 报关属性读回
    http.perform(
            get("/catalog/completeness/customs/skus/" + sku)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(
            result -> {
              var body = JSON.readTree(result.getResponse().getContentAsString());
              assertThat(body.get("hsCode").asText()).isEqualTo("847130");
              assertThat(body.get("declaredName").asText()).isEqualTo("便携式电脑");
              assertThat(body.get("originCountry").asText()).isEqualTo("CN");
              assertThat(body.get("declarationElements")).hasSize(1);
            });
  }

  // ============================================================ 权限

  @Test
  void 写操作按权限收敛且未认证被拒() throws Exception {
    UUID sku = skuOf(actor, unique("PERM"));
    // 只读角色不能写报关属性
    http.perform(
            put("/catalog/completeness/customs/skus/" + sku)
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content(
                    "{\"hsCode\":\"847130\",\"declaredName\":\"电脑\",\"originCountry\":\"CN\"}"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/catalog/completeness/evaluations")
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"ownerType\":\"SKU\",\"ownerIds\":[\"" + sku + "\"]}"))
        .andExpect(status().isForbidden());
    // 未认证 401
    http.perform(get("/catalog/completeness/overview")).andExpect(status().isUnauthorized());
    http.perform(get("/catalog/completeness/SKU/" + sku)).andExpect(status().isUnauthorized());
  }

  @Test
  void 非法主体类型与非法分档被拒() {
    UUID sku = skuOf(actor, unique("BADQ"));
    assertThatThrownBy(() -> tx(actor, () -> scores.card("PRODUCT", sku)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只支持 SPU 或 SKU");
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        scores.list(
                            new CompletenessQuery(
                                null, "SKU", "PERFECT", null, null, null, null, null, null, null,
                                null, 0, 20))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("分档不在词表");
    assertThatThrownBy(() -> tx(actor, () -> scores.card("SKU", UUID.randomUUID())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 批量评估超过上限被拒且空列表被拒() {
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        scores.evaluate(
                            actor, new CompletenessEvaluationInput("SKU", List.of(), ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少选择一个");
    var tooMany = new java.util.ArrayList<UUID>();
    for (int i = 0; i < 501; i++) tooMany.add(UUID.randomUUID());
    assertThatThrownBy(
            () ->
                write(
                    actor,
                    () ->
                        scores.evaluate(
                            actor, new CompletenessEvaluationInput("SKU", tooMany, ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("一次最多评估 500");
  }

  @Test
  void 词表与阈值与后端同源() {
    var options = tx(actor, () -> scores.options());
    assertThat(options.dimensions()).hasSize(6);
    assertThat(options.minImageCount()).isEqualTo(MIN_IMAGE_COUNT);
    assertThat(options.minPrimaryImageEdgePx()).isEqualTo(MIN_PRIMARY_IMAGE_EDGE_PX);
    assertThat(options.certificationStatuses()).contains("NOT_REQUIRED");
    assertThat(options.note()).contains("无需认证");
  }
}
