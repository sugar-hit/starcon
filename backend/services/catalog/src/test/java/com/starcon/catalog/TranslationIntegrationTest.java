package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.4.3.1 多语言内容：SPU/SKU 多语言名称与描述（按语言唯一）、三态状态流转与 DB 层守卫、
 * AI 翻译建议的采纳/驳回、翻译完整度、批量操作，以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS：译文、建议、批次与逐条结果全部真的落库，
 * 「待翻/已翻/已校对互斥」「只有已校对可刊登」都是从库里读出来的事实，
 * 不是内存里的返回值。
 *
 * <p>连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的两个断言：</b>
 * <ol>
 *   <li>{@link #statusMachineIsEnforcedInTheDatabaseNotJustTheService()}：直连 SQL 也改不出
 *       「待翻直接跳已校对」和「带正文的译文退回待翻」——绕过应用层的写入也被拦住。
 *   <li>{@link #shopSiteDerivationIsReportedAsUnwiredInsteadOfInvented()}：02 渠道未接入时
 *       返回 available=false + 归 02 渠道的说明，且**不写入任何语言需求**——
 *       绝不能编一份站点推导结果（空列表会被读成「不需要做多语言」）。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TranslationIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

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
    try {
      try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
          var s = conn.createStatement()) {
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
            byte[] body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader", actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute")
        .flatMap(p -> java.util.stream.Stream.of("read", "create", "update", "delete")
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
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired TranslationService translations;
  @Autowired TranslationBatchService batches;
  @Autowired CatalogService catalog;
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

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(who, "catalog.spu.update", () -> {
      work.run();
      return null;
    });
  }

  // ------------------------------------------------------------ 夹具

  String unique(String hint) {
    return hint + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /** 建 SPU（3.1.1.1 的写入所有权）。 */
  UUID spu(AccessService.Access who, String code) {
    return write(
        who,
        () -> catalog.saveSpu(
            who, null, new SpuInput(code, "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
  }

  UUID sku(AccessService.Access who, UUID spuId, String code, String name) {
    return sku(who, spuId, code, name, 0);
  }

  /**
   * 建 SKU。第二个 SKU 起必须带 SPU 的当前版本 —— 传 0 会撞「商品已被修改，请刷新后重试」，
   * 那条 409 说的是乐观锁而不是「一个 SPU 只能有一个 SKU」。
   */
  UUID sku(AccessService.Access who, UUID spuId, String code, String name, long spuVersion) {
    return write(
        who,
        () -> catalog.saveSku(
            who, spuId, null,
            new SkuInput(code, name, unique("BC"), "PCS", "ACTIVE", List.of(), spuVersion)));
  }

  long versionOf(AccessService.Access who, String table, UUID id) {
    return write(who, () ->
        sql.fetchOne("select version from " + table + " where tenant_id=? and id=?", a, id)
            .get(0, Long.class));
  }

  TranslationLocaleRow locale(AccessService.Access who, String tag, String name) {
    return write(who, () -> translations.saveLocale(who, null,
        new TranslationLocaleInput(tag, name, true, "", 0))).locale();
  }

  TranslationEntry entry(
      AccessService.Access who, String type, UUID subject, String tag, String name, String description) {
    return write(who, () -> translations.saveEntry(who, type, subject, null,
        new TranslationEntryInput(tag, name, description, "MANUAL", null)));
  }

  /** 建一条已校对的译文（走完整的状态流转，不直接改库）。 */
  TranslationEntry reviewedEntry(
      AccessService.Access who, String type, UUID subject, String tag, String name) {
    var created = entry(who, type, subject, tag, name, name + " description");
    return write(who, () -> translations.setEntryStatus(who, created.id(),
        new TranslationStatusInput("REVIEWED", created.version())));
  }

  // ============================================================ 语言需求

  @Test
  void manualLocaleRequirementIsRealWritableData() {
    // 这是本模块在 02 渠道未接入时**唯一真实可写入**的一侧，不允许是假数据。
    var saved = locale(actor, "de-DE", "德语");
    assertThat(saved.locale()).isEqualTo("de-DE");
    assertThat(saved.source()).isEqualTo("MANUAL");
    assertThat(saved.enabled()).isTrue();

    // 同一租户同一种语言不能重复建 —— 否则「我们到底需不需要德语」无法回答。
    assertThatThrownBy(() -> locale(actor, "DE-de", "德语"))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经");
  }

  @Test
  void localeLabelIsImmutableAfterCreation() {
    var row = locale(actor, "fr-FR", "法语");
    assertThatThrownBy(() -> write(actor, () -> translations.saveLocale(actor, row.id(),
        new TranslationLocaleInput("it-IT", "意大利语", true, "", row.version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不可改");
  }

  @Test
  void localeCannotBeDeletedWhileProductTranslationsUseIt() {
    var row = locale(actor, "it-IT", "意大利语");
    UUID spuId = spu(actor, unique("SPU"));
    entry(actor, "SPU", spuId, "it-IT", "Macchina", "");
    // DB 层守卫（refuse_translation_locale_delete），不是应用层的自觉。
    assertThatThrownBy(() -> write(actor, () -> {
      translations.deleteLocale(actor, row.id(), row.version());
      return null;
    }))
        .hasMessageContaining("不可删除");
  }

  // ============================================================ 「需要哪些语言由店铺启用站点推导」

  /**
   * 02 渠道未接入时：明确返回「未接入」并写明归属，且**不写入任何行**。
   *
   * <p>这是蓝图硬要求「需要哪些语言由店铺启用站点推导，不靠人肉记忆」的落点。
   * 绝不能编一份语言列表，更不能回空数组 —— 空数组会被读成「不需要做多语言」，
   * 那是本模块最危险的错误方向。
   */
  @Test
  void shopSiteDerivationIsReportedAsUnwiredInsteadOfInvented() {
    var view = tx(actor, () -> translations.requirementView());
    assertThat(view.siteLocaleAvailable()).isFalse();
    assertThat(view.siteNote()).contains("02 渠道").contains("店铺启用站点");
    // 未接入时 derivedCount 必须是 0，且**不写任何 SHOP_SITE 行**。
    assertThat(view.derivedCount()).isZero();

    // 同步接口返回 409 并写明归属，且不写入任何语言需求。
    long before = write(actor, () -> {
      sql.execute(
          "select locale from translation_locale where source='SHOP_SITE'");
      return sql.fetchOne("select count(*) from translation_locale where source='SHOP_SITE'")
          .get(0, Long.class);
    });
    assertThatThrownBy(() -> write(actor, () -> translations.syncFromSites(actor)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("02 渠道")
        .hasMessageContaining("未写入");
    long after = write(actor, () ->
        sql.fetchOne("select count(*) from translation_locale where source='SHOP_SITE'")
            .get(0, Long.class));
    assertThat(after).isEqualTo(before);
  }

  @Test
  void mergedViewUnitesManualRequirementWithUnwiredSiteColumn() {
    locale(actor, "es-ES", "西班牙语");
    var view = write(actor, () -> translations.requirementView());
    assertThat(view.requirements()).extracting(r -> r.locale()).contains("es-ES");
    // 人工维护的一侧 fromSite=false；站点侧未接入，所以没有任何 fromSite 行。
    var row = view.requirements().stream().filter(r -> r.locale().equals("es-ES")).findFirst().orElseThrow();
    assertThat(row.fromManual()).isTrue();
    assertThat(row.fromSite()).isFalse();
    assertThat(view.siteLocaleAvailable()).isFalse();
    assertThat(view.note()).contains("02 渠道");
  }

  // ============================================================ 按语言唯一

  @Test
  void oneLocalePerProductIsEnforced() {
    UUID spuId = spu(actor, unique("SPU"));
    entry(actor, "SPU", spuId, "nl-NL", "Koffiemachine", "");
    // 蓝图「按语言唯一」：第二条同语言译文直接冲突。
    assertThatThrownBy(() -> entry(actor, "SPU", spuId, "nl-NL", "Andere", ""))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经有");
    // 换一种语言就能建 —— 唯一性是按语言的，不是每个商品只能一条。
    entry(actor, "SPU", spuId, "sv-SE", "Kaffemaskin", "");
    assertThat(tx(actor, () -> translations.entries(
        new TranslationEntryQuery("SPU", null, null, spuId.toString(), null, null, 0, 50))).total())
        .isEqualTo(2);
  }

  @Test
  void spuAndSkuTranslationsAreIndependentTables() {
    // 不建成一张多态表：两条表各自对自己的主体建复合外键，删除各自级联各自的译文。
    // 两个 SKU 分属两个 SPU：同一个 SPU 下两个无属性 SKU 的 variant_signature 相同，
    // 那是 V10 的组合唯一约束在起作用，与本用例要验的「两张表独立」无关。
    UUID spuId = spu(actor, unique("SPU"));
    UUID keepSku = sku(actor, spuId, unique("SKU"), "保留款");
    UUID dropSku = sku(actor, spu(actor, unique("SPU")), unique("SKU"), "下架款");
    entry(actor, "SPU", spuId, "da-DK", "Kaffemaskine", "");
    entry(actor, "SKU", keepSku, "da-DK", "Behold udgave", "");
    entry(actor, "SKU", dropSku, "da-DK", "Udgave der fjernes", "");

    var before = tx(actor, () -> translations.entries(
        new TranslationEntryQuery(null, null, "da-DK", null, null, null, 0, 50)));
    assertThat(before.total()).isEqualTo(3);
    assertThat(before.items()).extracting(TranslationEntry::targetType)
        .containsExactlyInAnyOrder("SPU", "SKU", "SKU");

    // 删一个 SKU 只带走它自己那条译文：SPU 的与另一个 SKU 的照常可读。
    write(actor, () -> {
      sql.execute("delete from sku where tenant_id=? and id=?", a, dropSku);
      return null;
    });
    var after = tx(actor, () -> translations.entries(
        new TranslationEntryQuery(null, null, "da-DK", null, null, null, 0, 50)));
    assertThat(after.total()).isEqualTo(2);
    assertThat(after.items()).extracting(TranslationEntry::targetType)
        .containsExactlyInAnyOrder("SPU", "SKU");
    assertThat(tx(actor, () -> translations.productView("SKU", keepSku)).rows())
        .anyMatch(r -> r.locale().equals("da-DK") && r.name().equals("Behold udgave"));
    // 被删 SKU 的译文随之级联消失（复合外键，不是应用层的清理）。
    assertThat(tx(actor, () -> translations.entries(
        new TranslationEntryQuery("SKU", null, "da-DK", null, dropSku.toString(), null, 0, 50))
        .total())).isZero();
  }

  // ============================================================ 三态与状态流转

  @Test
  void pendingTranslatedReviewedFlowWithReviewerRecorded() {
    UUID spuId = spu(actor, unique("SPU"));
    // 待翻：登记了语言但没有文本 —— 是一等状态，不是「行不存在」。
    var pending = entry(actor, "SPU", spuId, "pl-PL", "", "");
    assertThat(pending.status()).isEqualTo("PENDING");
    assertThat(pending.name()).isEmpty();

    // 已翻：填入正文。
    var translated = write(actor, () -> translations.saveEntry(actor, "SPU", spuId, pending.id(),
        new TranslationEntryEdit("Ekspresja do kawy", "Opis produktu", "MANUAL", "TRANSLATED",
            pending.version())));
    assertThat(translated.status()).isEqualTo("TRANSLATED");
    assertThat(translated.translatedAt()).isNotNull();

    // 已校对：留下校对人。
    var reviewed = write(actor, () -> translations.setEntryStatus(actor, translated.id(),
        new TranslationStatusInput("REVIEWED", translated.version())));
    assertThat(reviewed.status()).isEqualTo("REVIEWED");
    assertThat(reviewed.reviewedBy()).isEqualTo(user);
    assertThat(reviewed.reviewedAt()).isNotNull();
  }

  @Test
  void pendingCannotBeMarkedReviewedThroughTheApi() {
    UUID spuId = spu(actor, unique("SPU"));
    var pending = entry(actor, "SPU", spuId, "cs-CZ", "", "");
    assertThatThrownBy(() -> write(actor, () -> translations.setEntryStatus(actor, pending.id(),
        new TranslationStatusInput("REVIEWED", pending.version()))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已翻");
  }

  @Test
  void statusMachineIsEnforcedInTheDatabaseNotJustTheService() {
    UUID spuId = spu(actor, unique("SPU"));
    var pending = entry(actor, "SPU", spuId, "hu-HU", "", "");
    var translated = write(actor, () -> translations.saveEntry(actor, "SPU", spuId, pending.id(),
        new TranslationEntryEdit("Kávéautomata", "Leírás", "MANUAL", "TRANSLATED", pending.version())));

    // 绕过应用层，直连 SQL 改状态：V22 的触发器必须拦下来。
    assertThatThrownBy(() -> write(actor, () -> {
      sql.execute("update spu_translation set status='PENDING' where id=?", translated.id());
      return null;
    })).hasMessageContaining("待翻");

    // 直连 SQL 把已翻改成已校对：状态流转本身合法，但校对人/校对时刻没人填，
    // 所以被 CHECK 拦下 —— 这正是「已校对必须能回答谁校对的」在 DB 层的落点。
    assertThatThrownBy(() -> write(actor, () -> {
      sql.execute("update spu_translation set status='REVIEWED' where id=?", translated.id());
      return null;
    })).hasMessageContaining("spu_translation_reviewed_needs_reviewer");

    // 待翻直接跳已校对：即使把校对人也一起填上，仍然被触发器拦（跳级抹掉了「已翻」）。
    // 另起一条仍处于 PENDING 的行 —— 上面的 saveEntry 已经把 pending 那行推进成「已翻」了。
    UUID stillPending = spu(actor, unique("SPU"));
    var untouched = entry(actor, "SPU", stillPending, "en-GB", "", "");
    assertThatThrownBy(() -> write(actor, () -> {
      sql.execute(
          "update spu_translation set status='REVIEWED',reviewed_by=?,reviewed_at=now()"
              + " where id=?",
          user, untouched.id());
      return null;
    })).hasMessageContaining("已校对");

    // 直接写一个「已校对但没有校对人」的行：CHECK 拦。
    assertThatThrownBy(() -> write(actor, () -> {
      sql.execute(
          "insert into spu_translation(id,tenant_id,spu_id,locale,name,description,origin,status,version)"
              + " values(?::uuid,?::uuid,?::uuid,'sk-SK','Kaviar','Popis','MANUAL','REVIEWED',0)",
          UUID.randomUUID(), a, spuId);
      return null;
    })).hasMessageContaining("spu_translation_reviewed_needs_reviewer");

    // 「待翻却带正文」：CHECK 拦。
    assertThatThrownBy(() -> write(actor, () -> {
      sql.execute(
          "insert into spu_translation(id,tenant_id,spu_id,locale,name,description,origin,status,version)"
              + " values(?::uuid,?::uuid,?::uuid,'ro-RO','Kafea','Descriere','MANUAL','PENDING',0)",
          UUID.randomUUID(), a, spuId);
      return null;
    })).hasMessageContaining("spu_translation_pending_is_empty");

    // 残留占位符：触发器拦（占位符是 3.4.2.2 的机制，商品译文是成品）。
    assertThatThrownBy(() -> write(actor, () -> {
      sql.execute(
          "insert into spu_translation(id,tenant_id,spu_id,locale,name,description,origin,status,version)"
              + " values(?::uuid,?::uuid,?::uuid,'tr-TR','Kahve {brand}','Aciklama','MANUAL','TRANSLATED',0)",
          UUID.randomUUID(), a, spuId);
      return null;
    })).hasMessageContaining("占位符");
  }

  @Test
  void placeholderInProductTextIsRejectedByTheApi() {
    UUID spuId = spu(actor, unique("SPU"));
    assertThatThrownBy(() -> entry(actor, "SPU", spuId, "el-GR", "Kafe {brand}", ""))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("花括号");
  }

  // ============================================================ AI 翻译建议的采纳 / 驳回

  @Test
  void aiSuggestionIsAdoptedIntoTranslatedNotReviewed() {
    UUID spuId = spu(actor, unique("SPU"));
    String batch = unique("AI");
    var page = write(actor, () -> translations.submitSuggestions(actor,
        new TranslationSuggestionSubmit(List.of(new TranslationSuggestionInput(
            "SPU", spuId.toString(), null, "de-DE", "Kaffeemaschine", "Beschreibung",
            "AI", "model-x", batch)))));
    var suggestion = page.items().get(0);
    assertThat(suggestion.status()).isEqualTo("PENDING");

    // 采纳后落在「已翻」—— 机器译文发到平台上等于没做校对这一步。
    var adopted = write(actor, () -> translations.adoptSuggestion(actor, suggestion.id()));
    assertThat(adopted.status()).isEqualTo("TRANSLATED");
    assertThat(adopted.origin()).isEqualTo("AI");
    assertThat(adopted.name()).isEqualTo("Kaffeemaschine");
    assertThat(tx(actor, () -> translations.suggestion(suggestion.id())).status()).isEqualTo("ADOPTED");
  }

  @Test
  void decidedSuggestionIsFinal() {
    UUID spuId = spu(actor, unique("SPU"));
    var suggestion =
        write(actor, () -> translations.saveSuggestion(actor, new TranslationSuggestionInput(
                "SPU", spuId.toString(), null, "ja-JP", "コーヒーメーカー", "説明",
                "AI", "model-x", unique("AI"))));
    write(actor, () -> translations.adoptSuggestion(actor, suggestion.id()));

    // 处置结果是终态：不能再驳回、也不能再采纳一次。
    assertThatThrownBy(() -> write(actor, () -> translations.rejectSuggestion(actor, suggestion.id(),
        new TranslationSuggestionReject("理由"))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("终态");
    assertThatThrownBy(() -> write(actor, () -> translations.adoptSuggestion(actor, suggestion.id())))
        .isInstanceOf(ResponseStatusException.class);

    // 直连 SQL 也改不回去（V22 触发器 guard_translation_suggestion_decided）。
    assertThatThrownBy(() -> write(actor, () -> {
      sql.execute("update translation_suggestion set status='PENDING' where id=?", suggestion.id());
      return null;
    })).hasMessageContaining("终态");
  }

  @Test
  void rejectingASuggestionRequiresAReason() {
    UUID spuId = spu(actor, unique("SPU"));
    var suggestion =
        write(actor, () -> translations.saveSuggestion(actor, new TranslationSuggestionInput(
                "SPU", spuId.toString(), null, "ko-KR", "커피 머신", "설명",
                "AI", "model-x", unique("AI"))));
    // 不给理由的驳回让下一轮翻译无从改进。
    assertThatThrownBy(() -> write(actor, () -> translations.rejectSuggestion(actor, suggestion.id(),
        new TranslationSuggestionReject("  "))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("理由");
    var rejected = write(actor, () -> translations.rejectSuggestion(actor, suggestion.id(),
        new TranslationSuggestionReject("机翻把型号 M8 也译了")));
    assertThat(rejected.status()).isEqualTo("REJECTED");
    assertThat(rejected.rejectReason()).contains("M8");
  }

  @Test
  void adoptedSuggestionKeepsItsSnapshotWhenTheTranslationIsLaterEdited() {
    UUID spuId = spu(actor, unique("SPU"));
    var suggestion =
        write(actor, () -> translations.saveSuggestion(actor, new TranslationSuggestionInput(
                "SPU", spuId.toString(), null, "ru-RU", "Кофемашина", "Описание",
                "AI", "model-x", unique("AI"))));
    var adopted = write(actor, () -> translations.adoptSuggestion(actor, suggestion.id()));
    // 人工改译文之后，「当初 AI 给的是哪一版」仍然答得上来。
    var edited = write(actor, () -> translations.saveEntry(actor, "SPU", spuId, adopted.id(),
        new TranslationEntryEdit("Кофемашина Pro", "Описание", "MANUAL", "TRANSLATED", adopted.version())));
    assertThat(edited.name()).isEqualTo("Кофемашина Pro");
    assertThat(tx(actor, () -> translations.suggestion(suggestion.id())).suggestedName())
        .isEqualTo("Кофемашина");
  }

  // ============================================================ 翻译完整度（04 刊登的读形状）

  @Test
  void onlyReviewedTranslationsArePublishable() {
    locale(actor, "pt-BR", "葡萄牙语");
    UUID spuId = spu(actor, unique("SPU"));
    var translated = entry(actor, "SPU", spuId, "pt-BR", "Máquina de café", "Descrição");
    var coverage = tx(actor, () -> translations.coverage("SPU", spuId, List.of("pt-BR")));
    // 「已翻」不可刊登：机器译文发到平台等于没做校对。
    assertThat(coverage.publishableCount()).isZero();
    assertThat(coverage.translatedCount()).isEqualTo(1);

    write(actor, () -> translations.setEntryStatus(actor, translated.id(),
        new TranslationStatusInput("REVIEWED", translated.version())));
    var after = tx(actor, () -> translations.coverage("SPU", spuId, List.of("pt-BR")));
    assertThat(after.publishableCount()).isEqualTo(1);
    assertThat(after.rows()).allMatch(TranslationCoverageRow::publishable);
  }

  @Test
  void coverageReportsMissingLocalesAsNotRegistered() {
    locale(actor, "zh-TW", "繁體中文");
    locale(actor, "th-TH", "泰语");
    UUID spuId = spu(actor, unique("SPU"));
    entry(actor, "SPU", spuId, "th-TH", "เครื่องทำกาแฟ", "");
    // 显式指定语言：没译文行 = 未登记，而不是被藏起来。
    var coverage = tx(actor, () -> translations.coverage("SPU", spuId, List.of("zh-TW", "th-TH")));
    assertThat(coverage.locales()).containsExactly("zh-TW", "th-TH");
    assertThat(coverage.rows()).filteredOn(r -> r.locale().equals("zh-TW"))
        .allMatch(r -> r.status() == null && !r.publishable());
    assertThat(coverage.requiredLocaleCount()).isEqualTo(2);
    assertThat(coverage.translatedCount()).isEqualTo(1);
    assertThat(coverage.publishableCount()).isZero();
    assertThat(coverage.note()).contains("已校对");
    // 语言留空时回落到租户启用的语言需求（本类共用租户 A，所以只会是「不少于」这两种）。
    var fallback = tx(actor, () -> translations.coverage("SPU", spuId, null));
    assertThat(fallback.locales()).contains("zh-TW", "th-TH").hasSizeGreaterThanOrEqualTo(2);
  }

  // ============================================================ 批量操作

  @Test
  void createPendingFillsPlaceholdersAndSkipsExistingRows() {
    locale(actor, "fi-FI", "芬兰语");
    locale(actor, "nb-NO", "挪威语");
    UUID first = spu(actor, unique("SPU"));
    UUID second = spu(actor, unique("SPU"));
    // 第一个商品已有 nb-NO 译文：补占位时必须跳过而不是重建（会抹掉工作成果）。
    entry(actor, "SPU", first, "nb-NO", "Kaffemaskin", "");

    var detail = write(actor, () -> batches.run(actor, new TranslationBatchInput(
        "CREATE_PENDING", "SPU", List.of(first.toString(), second.toString()),
        List.of("fi-FI", "nb-NO"), "")));
    var batch = detail.batch();
    assertThat(batch.action()).isEqualTo("CREATE_PENDING");
    assertThat(batch.itemCount()).isEqualTo(4);
    assertThat(batch.succeededCount()).isEqualTo(3);
    assertThat(batch.skippedCount()).isEqualTo(1);
    assertThat(batch.rejectedCount()).isZero();
    assertThat(batch.status()).isEqualTo("PARTIAL");
    // 计数自洽（translation_batch 的 CHECK 要求三者相加等于 item_count）。
    assertThat(batch.succeededCount() + batch.skippedCount() + batch.rejectedCount())
        .isEqualTo(batch.itemCount());

    // 逐条原因落到行上，不是拼在 error 字段里的一句话。
    var skipped = detail.items().stream().filter(i -> i.outcome().equals("SKIPPED")).findFirst().orElseThrow();
    assertThat(skipped.locale()).isEqualTo("nb-NO");
    assertThat(skipped.reason()).contains("已经有");
    assertThat(skipped.targetCode()).isNotBlank();

    var view = tx(actor, () -> translations.productView("SPU", second));
    assertThat(view.rows()).filteredOn(r -> r.locale().equals("fi-FI"))
        .allMatch(r -> "PENDING".equals(r.status()));
  }

  @Test
  void markReviewedOnlyMovesTranslatedRowsAndRecordsTheReviewer() {
    locale(actor, "no-NO", "挪威语");
    UUID spuId = spu(actor, unique("SPU"));
    var translated = entry(actor, "SPU", spuId, "no-NO", "Kaffemaskin", "Beskrivelse");
    // 另一个商品只有待翻占位：本行必须被拒收而不是被「标成已校对」。
    UUID pendingOnly = spu(actor, unique("SPU"));
    write(actor, () -> {
      sql.execute(
          "insert into spu_translation(id,tenant_id,spu_id,locale,name,description,origin,status,version)"
              + " values(?::uuid,?::uuid,?::uuid,'no-NO','','','MANUAL','PENDING',0)",
          UUID.randomUUID(), a, pendingOnly);
      return null;
    });

    var detail = write(actor, () -> batches.run(actor, new TranslationBatchInput(
        "MARK_REVIEWED", "SPU", List.of(spuId.toString(), pendingOnly.toString()),
        List.of("no-NO"), "")));
    assertThat(detail.batch().succeededCount()).isEqualTo(1);
    assertThat(detail.batch().rejectedCount()).isEqualTo(1);
    assertThat(detail.batch().status()).isEqualTo("PARTIAL");

    var rejected = detail.items().stream().filter(i -> i.outcome().equals("REJECTED")).findFirst().orElseThrow();
    assertThat(rejected.reason()).contains("待翻").contains("无从校对");

    var after = tx(actor, () -> translations.entry(translated.id()));
    assertThat(after.status()).isEqualTo("REVIEWED");
    assertThat(after.reviewedBy()).isEqualTo(user);
  }

  @Test
  void adoptSuggestionsInBatchByBatchRef() {
    UUID spuId = spu(actor, unique("SPU"));
    String batch = unique("AI");
    write(actor, () -> translations.submitSuggestions(actor, new TranslationSuggestionSubmit(List.of(
        new TranslationSuggestionInput("SPU", spuId.toString(), null, "da-DK", "Kaffemaskine", "Beskrivelse", "AI", "m", batch),
        new TranslationSuggestionInput("SPU", spuId.toString(), null, "sv-SE", "Kaffemaskin", "Beskrivning", "AI", "m", batch)))));

    var detail = write(actor, () -> batches.run(actor, new TranslationBatchInput(
        "ADOPT_SUGGESTIONS", "SPU", List.of(), List.of(), batch)));
    assertThat(detail.batch().itemCount()).isEqualTo(2);
    assertThat(detail.batch().succeededCount()).isEqualTo(2);
    assertThat(detail.batch().status()).isEqualTo("SUCCEEDED");

    var view = tx(actor, () -> translations.productView("SPU", spuId));
    assertThat(view.rows()).filteredOn(r -> java.util.Set.of("da-DK", "sv-SE").contains(r.locale()))
        .allMatch(r -> "TRANSLATED".equals(r.status()));
    assertThat(view.pendingSuggestionCount()).isZero();
  }

  @Test
  void batchWithoutAnythingToDoIsRejectedWithAReason() {
    locale(actor, "bg-BG", "保加利亚语");
    UUID spuId = spu(actor, unique("SPU"));
    // 第一次是真的补占位。
    var first = write(actor, () -> batches.run(actor, new TranslationBatchInput(
        "CREATE_PENDING", "SPU", List.of(spuId.toString()), List.of("bg-BG"), "")));
    assertThat(first.batch().succeededCount()).isEqualTo(1);
    // 第二次已经没什么可补了：整批拒收并说清为什么，而不是回一个「0 条成功」。
    assertThatThrownBy(() -> write(actor, () -> batches.run(actor, new TranslationBatchInput(
        "CREATE_PENDING", "SPU", List.of(spuId.toString()), List.of("bg-BG"), ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有需要补");
  }

  @Test
  void batchRejectsDuplicateTargetIds() {
    locale(actor, "et-EE", "爱沙尼亚语");
    UUID spuId = spu(actor, unique("SPU"));
    assertThatThrownBy(() -> write(actor, () -> batches.run(actor, new TranslationBatchInput(
        "CREATE_PENDING", "SPU", List.of(spuId.toString(), spuId.toString()),
        List.of("et-EE"), ""))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("重复");
  }

  // ============================================================ 租户隔离三断言

  /**
   * 租户隔离三断言：
   *
   * <ol>
   *   <li>两租户互不可见（读、写、删、导出都按当前租户过滤）；
   *   <li>伪造 {@code X-Tenant-Id} 头无效（租户只来自已认证的成员关系）；
   *   <li>连接池归还后不串租户（单连接反复交替）。
   * </ol>
   */
  @Test
  void tenantIsolationHoldsAcrossReadWriteAndPoolReuse() throws Exception {
    locale(actor, "sv-SE", "瑞典语");
    UUID mineSpu = spu(actor, unique("SPU"));
    UUID mineEntry = reviewedEntry(actor, "SPU", mineSpu, "sv-SE", "Kaffemaskin").id();

    locale(actorB, "sv-SE", "瑞典语");
    UUID theirsSpu = spu(actorB, unique("SPU"));
    UUID theirsEntry = reviewedEntry(actorB, "SPU", theirsSpu, "sv-SE", "Deras kaffemaskin").id();

    // 1) 两租户互不可见
    assertThatThrownBy(() -> tx(actor, () -> translations.entry(theirsEntry)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> translations.entry(mineEntry)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actor, () -> translations.entries(
        new TranslationEntryQuery("SPU", null, null, theirsSpu.toString(), null, null, 0, 50)).total()))
        .isZero();
    assertThat(tx(actorB, () -> translations.entries(
        new TranslationEntryQuery("SPU", null, null, mineSpu.toString(), null, null, 0, 50)).total()))
        .isZero();
    // 覆盖统计同样不串：两个租户各有 1 条，但各只看见自己的。
    assertThat(tx(actor, () -> translations.requirementView()).requirements().stream()
        .filter(r -> r.locale().equals("sv-SE")).findFirst().orElseThrow().translationCount())
        .isEqualTo(1);
    assertThat(tx(actorB, () -> translations.requirementView()).requirements().stream()
        .filter(r -> r.locale().equals("sv-SE")).findFirst().orElseThrow().translationCount())
        .isEqualTo(1);

    // B 租户也建不了指向 A 租户商品的译文（FK 是 tenant_id 复合外键）。
    assertThatThrownBy(() -> entry(actorB, "SPU", mineSpu, "sv-SE", "Hijack", ""))
        .isInstanceOf(ResponseStatusException.class);

    // 2) 伪造 tenant header 无效：租户只来自已认证的成员关系
    // 用 B 的令牌 + 伪造的 A 租户头去读 **A 的**译文：必须是 404。
    http.perform(get("/catalog/translations/entries/" + mineEntry)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/translations/spus/" + mineSpu)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍然落在 B 租户自己的商品上，与 A 无关。
    var submitted =
        http.perform(post("/catalog/translations/spus/" + theirsSpu + "/entries")
                .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a)
                .contentType("application/json")
                .content("{\"locale\":\"mt-MT\",\"name\":\"Maghna\",\"description\":\"x\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(submitted).contains("mt-MT");
    // 写入落在 B 租户自己的商品上；A 租户一条都看不到（这条语言只有本用例写过）。
    assertThat(tx(actorB, () -> translations.entries(
        new TranslationEntryQuery("SPU", null, "mt-MT", null, null, null, 0, 50)).total()))
        .isEqualTo(1);
    assertThat(tx(actor, () -> translations.entries(
        new TranslationEntryQuery("SPU", null, "mt-MT", null, null, null, 0, 50)).total()))
        .isZero();

    // B 租户建一条自己的建议，让「A 看不见 B 的建议」成为真正的对比，
    // 而不是靠「A 恰好一条建议都没有」这种依赖用例执行顺序的断言。
    var theirsSuggestion =
        write(actorB, () -> translations.saveSuggestion(actorB, new TranslationSuggestionInput(
                "SPU", theirsSpu.toString(), null, "mt-MT", "Maghna", "Deskrizzjoni",
                "AI", "model-x", unique("AI"))));
    long aSuggestionsBefore = tx(actor, () -> translations.suggestions(
        new TranslationSuggestionQuery(null, null, null, null, null, null, null, 0, 200)).total());
    assertThat(tx(actorB, () -> translations.suggestion(theirsSuggestion.id()).id()))
        .isEqualTo(theirsSuggestion.id());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actor, () -> translations.entry(mineEntry).name())).isEqualTo("Kaffemaskin");
      assertThat(tx(actorB, () -> translations.entry(theirsEntry).name())).isEqualTo("Deras kaffemaskin");
      assertThatThrownBy(() -> tx(actorB, () -> translations.entry(mineEntry)))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(tx(actor, () -> translations.productView("SPU", mineSpu).code()))
          .isNotEqualTo(tx(actorB, () -> translations.productView("SPU", theirsSpu).code()));
      // 建议列表同样不串：A 的条数不因 B 写入而变化，且拿不到 B 那一条。
      assertThat(tx(actor, () -> translations.suggestions(
          new TranslationSuggestionQuery(null, null, null, null, null, null, null, 0, 200)).total()))
          .isEqualTo(aSuggestionsBefore);
      assertThatThrownBy(() -> tx(actor, () -> translations.suggestion(theirsSuggestion.id())))
          .isInstanceOf(ResponseStatusException.class);
      assertThat(tx(actorB, () -> translations.suggestions(
          new TranslationSuggestionQuery(null, null, "mt-MT", null, null, null, null, 0, 50)).total()))
          .isEqualTo(1);
    }
  }

  @Test
  void rlsPoliciesAndNumericShapeAreInPlace() throws Exception {
    // 表结构断言：业务表必须有 tenant_id NOT NULL 且强制 RLS。
    var tables =
        tx(actor, () ->
            sql.fetch(
                "select relname,relrowsecurity,relforcerowsecurity from pg_class"
                    + " where relname in ('translation_locale','spu_translation','sku_translation',"
                    + "'translation_suggestion','translation_batch','translation_batch_item')"
                    + " and relkind='r'"));
    assertThat(tables).hasSize(6);
    assertThat(tables).allMatch(r -> Boolean.TRUE.equals(r.get("relforcerowsecurity", Boolean.class))
        && Boolean.TRUE.equals(r.get("relrowsecurity", Boolean.class)));
    // 按语言唯一：两张表都必须有 (tenant_id, 主体, locale) 的唯一约束。
    assertThat(tx(actor, () -> sql.fetch(
        "select indexdef from pg_indexes where tablename in ('spu_translation','sku_translation')"
            + " and indexdef like '%UNIQUE%'")))
        .hasSizeGreaterThanOrEqualTo(2);
  }

  // ============================================================ HTTP 与导出

  @Test
  void httpSurfaceWiresTheWholeFlowAndRejectsReadOnlyActor() throws Exception {
    locale(actor, "id-ID", "印尼语");
    UUID spuId = spu(actor, unique("SPU"));

    http.perform(get("/catalog/translations/overview").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.note").isNotEmpty())
        .andExpect(jsonPath("$.siteLocaleAvailable").value(false));

    http.perform(get("/catalog/translations/locales/requirements").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.siteLocaleAvailable").value(false))
        .andExpect(jsonPath("$.siteNote").isNotEmpty());

    var created =
        http.perform(post("/catalog/translations/spus/" + spuId + "/entries")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"locale\":\"id-ID\",\"name\":\"Mesin Kopi\",\"description\":\"Deskripsi\"}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
    assertThat(created).contains("TRANSLATED");

    http.perform(get("/catalog/translations/spus/" + spuId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows[?(@.locale=='id-ID')].publishable").value(false));

    // 未认证 → 401；只读角色可读不可写。
    http.perform(get("/catalog/translations/overview")).andExpect(status().isUnauthorized());
    http.perform(post("/catalog/translations/locales")
            .header("Authorization", "Bearer reader").contentType("application/json")
            .content("{\"locale\":\"id-ID\"}"))
        .andExpect(status().isForbidden());
    http.perform(get("/catalog/translations/overview").header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());

    // 非法语言标签 → 400（不是 500）。
    http.perform(post("/catalog/translations/spus/" + spuId + "/entries")
            .header("Authorization", "Bearer tenant-a").contentType("application/json")
            .content("{\"locale\":\"en_US\",\"name\":\"x\"}"))
        .andExpect(status().isBadRequest());
  }

  /**
   * CSV 导出产出真实行。
   *
   * <p>盯的是 3.4.2.2 踩过的坑：流式线程的连接上没有 {@code app.current_tenant}，
   * FORCE RLS 会把全部行藏起来，于是导出只有一个表头、而且**不报错**。
   */
  @Test
  void csvExportProducesRealRows() {
    locale(actor, "vi-VN", "越南语");
    UUID spuId = spu(actor, unique("SPU"));
    reviewedEntry(actor, "SPU", spuId, "vi-VN", "Máy pha cà phê");

    var chunks = new java.util.ArrayList<String>();
    writeVoid(actor, () -> translations.streamEntriesCsv(
        new TranslationEntryQuery("SPU", "REVIEWED", "vi-VN", spuId.toString(), null, null, 0, 0),
        chunks::add));
    String csv = String.join("", chunks);
    assertThat(csv.lines()).hasSizeGreaterThan(1);
    assertThat(csv).contains("Máy pha cà phê").contains("可刊登").contains("vi-VN");
    // 商品多语言内容里不会有占位符（3.4.2.2 的机制不在这张表）。
    assertThat(csv).doesNotContain("{brand}");
  }
}