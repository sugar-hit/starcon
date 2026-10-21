package com.starcon.catalog;

import static com.starcon.catalog.PlatformPriceSyncModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceSyncRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.2.1 平台价格同步的**多租户三断言**。
 *
 * <p>把三条铁律**单独拉出来实跑**。本模块的隔离面比一般列表模块更大：
 * 一条价格回写记录里同时躺着**期望价、平台承接价、平台实际价、差额** ——
 * A 读到 B 的一行，不只是「看到了别人的商品」，而是
 * **拿到了别人店铺的定价依据、平台实际收了多少、差了多少**。
 * 定价与利润结构正是跨境 ERP 最敏感的东西。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：规则、回写记录、差异台账都按成员关系过滤 —— 越权访问一律 404；
 *       并且<b>按主键直查对方的行、断言读到零行</b>（只在 HTTP 层断言「列表里没有对方数据」
 *       是不够的 —— 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见）。
 *       🔴 直查用 {@code catalog_app} 应用账号（不是超级用户）：超级用户会绕过 RLS，断言本身失效。</li>
 *   <li><b>伪造 tenant header 无效</b>：租户只来自已认证成员关系，
 *       {@code X-Tenant-Id} 头改变不了任何可见范围；伪造头下的写入仍落在调用者名下。</li>
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，
 *       只有复用同一条物理连接，「归还后不串」才是真的被验证过 ——
 *       池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。</li>
 * </ol>
 */
class PlatformPriceSyncTenancyIntegrationTest extends PlatformPriceSyncFixture {

  @BeforeEach
  void reset() {
    resetQueue();
    EVALUATION.set(Evaluation.EXEMPT);
  }

  /** 造一条「租户 → 自己的 SKU + 事实 + 规则 + 回写记录」。 */
  private record Side(AccessService.Access who, UUID skuId, UUID ruleId, UUID recordId, UUID diffId) {}

  private Side seed(AccessService.Access who, String prefix, String price) {
    UUID sku = pricedSku(who, prefix, unique("CAT"), "BrandT");
    priceObservation(who, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    UUID ruleId = minPriceRule(who, "AMAZON", "US", "10", "USD");
    var fact = upstreamFact(who, sku, price);
    var run = sync.sync(who, new PlatformPriceSyncRequest(null, null, sku, null), bearer(who));
    assertThat(run.items()).hasSize(1);
    UUID recordId = run.items().get(0).id();
    var diffs = tx(who, () -> sync.listDiffs(null, null, null, sku, null, 0, 20));
    assertThat(diffs.totalElements()).isEqualTo(1L);
    return new Side(who, sku, ruleId, recordId, diffs.items().get(0).id());
  }

  // ================================================================ 断言一

  /**
   * 两租户的规则、回写记录与差异台账互不可见，且<b>按主键直查对方的行读到零行</b>。
   *
   * <p>每个「读到零行」都配一条<b>同样写法的正对照</b>读到 1 行 —— 防假绿：
   * 一条永远返回零行的查询也能通过「读到零行」的断言。
   */
  @Test
  void 两租户的规则回写记录与差异互不可见且按主键直查对方行读到零行() {
    var mine = seed(actor, "TEN", "120");
    var theirs = seed(actorB, "TEN", "60");

    // 正对照：两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> sync.getRule(mine.ruleId()))).isNotNull();
    assertThat(tx(actorB, () -> sync.getRule(theirs.ruleId()))).isNotNull();
    assertThat(tx(actor, () -> sync.getRecord(mine.recordId()))).isNotNull();
    assertThat(tx(actorB, () -> sync.getRecord(theirs.recordId()))).isNotNull();

    // 1) 两租户互不可见：按 id 取对方的规则 / 记录必须失败。
    assertThatThrownBy(() -> tx(actor, () -> sync.getRule(theirs.ruleId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> sync.getRule(mine.ruleId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actor, () -> sync.getRecord(theirs.recordId())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> sync.getRecord(mine.recordId())))
        .isInstanceOf(ResponseStatusException.class);

    // 规则列表里混不进对方的规则。
    var myRules = tx(actor, () -> sync.listRules(null, null, null, "ACTIVE", null, 0, 200));
    assertThat(myRules.items()).extracting(r -> r.id()).doesNotContain(theirs.ruleId());
    assertThat(myRules.items()).extracting(r -> r.id()).contains(mine.ruleId());
    var theirRules = tx(actorB, () -> sync.listRules(null, null, null, "ACTIVE", null, 0, 200));
    assertThat(theirRules.items()).extracting(r -> r.id()).doesNotContain(mine.ruleId());

    // 回写结果列表里混不进对方的记录。
    var myRecords = tx(actor, () -> sync.listRecords(null, null, null, null, 0, 200));
    assertThat(myRecords.items()).extracting(r -> r.id()).doesNotContain(theirs.recordId());
    assertThat(myRecords.items()).extracting(r -> r.id()).contains(mine.recordId());
    var theirRecords = tx(actorB, () -> sync.listRecords(null, null, null, null, 0, 200));
    assertThat(theirRecords.items()).extracting(r -> r.id()).doesNotContain(mine.recordId());

    // 差异台账里混不进对方的差异。
    var myDiffs = tx(actor, () -> sync.listDiffs(null, null, null, null, null, 0, 200));
    assertThat(myDiffs.items()).extracting(d -> d.id()).doesNotContain(theirs.diffId());
    assertThat(myDiffs.items()).extracting(d -> d.id()).contains(mine.diffId());

    // 🔴 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    // 正对照：同样写法的查询读自己的行读到 1 行。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_price_sync where id=?::uuid",
                                mine.recordId())
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_price_sync where id=?::uuid",
                                theirs.recordId())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 差异与规则同样按主键直查。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_price_sync_diff where id=?::uuid",
                                mine.diffId())
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_price_sync_diff where id=?::uuid",
                                theirs.diffId())
                            .get(0, Long.class))
                .longValue())
        .isZero();
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from platform_price_rule where id=?::uuid", theirs.ruleId())
                            .get(0, Long.class))
                .longValue())
        .isZero();
  }

  // ================================================================ 断言二

  /**
   * 伪造 {@code X-Tenant-Id} 头<b>无效</b>：租户只来自已认证成员关系。
   *
   * <p>🔴 断言的是「读到的内容与不带头时<b>逐字段相同</b>」，不是「4xx」——
   * 读自己租户的数据时应当<b>完全不受这个头影响</b>。
   */
  @Test
  void 伪造tenant头无效且读到自己租户的数据逐字段相同() throws Exception {
    var mine = seed(actor, "FORGE", "120");
    var theirs = seed(actorB, "FORGE", "60");

    // 伪造头指向 B：A 读 A 自己的记录仍然是 200，且内容与不带头时逐字段相同。
    var plain =
        http.perform(
                get("/catalog/price-sync/records/" + mine.recordId())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var forged =
        http.perform(
                get("/catalog/price-sync/records/" + mine.recordId())
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).isEqualTo(plain);

    // 伪造头指向 B：A 读 B 的记录仍然是 404 —— 头改变不了任何可见范围。
    http.perform(
            get("/catalog/price-sync/records/" + theirs.recordId())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/price-sync/records/" + mine.recordId())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 伪造头下的写入仍落在<b>调用者自己</b>名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    var created =
        http.perform(
                post("/catalog/price-sync/rules")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b)
                    .contentType("application/json")
                    .content(
                        JSON.writeValueAsString(
                            new PlatformPriceRuleInput(
                                com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleType.MIN_PRICE,
                                "SHOPIFY",
                                "US",
                                null,
                                null,
                                null,
                                new BigDecimal("50"),
                                "USD",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "伪造租户头下的写入"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var createdId = UUID.fromString(JSON.readTree(created).get("id").asText());
    var owners =
        tx(
                actor,
                () ->
                    sql.fetch(
                            "select distinct tenant_id from platform_price_rule where id=?::uuid",
                            createdId)
                        .stream()
                        .map(row -> row.get(0, UUID.class))
                        .toList())
            .stream()
            .map(u -> u.toString())
            .toList();
    assertThat(owners).containsOnly(a.toString());
  }

  // ================================================================ 断言三

  /**
   * 连接池归还后<b>不串租户</b>（单连接反复交替），逐轮断言两个集合不相交。
   *
   * <p>连接池压到 1 条连接（见底座）：每个请求必然复用同一条物理连接，
   * 租户设置如果泄漏到连接上，第二个租户就会看到第一个租户的数据。
   */
  @Test
  void 连接池归还后不串租户() {
    for (int i = 0; i < 5; i++) {
      UUID mySku = pricedSku(actor, "POOL", unique("CAT"), "BrandP");
      UUID theirSku = pricedSku(actorB, "POOL", unique("CAT"), "BrandP");
      priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
      priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());

      var myFact = upstreamFact(actor, mySku, "12" + i);
      var theirFact = upstreamFact(actorB, theirSku, "6" + i);

      // 交替走一遍完整的回写路径（写入 → 投递 → 读回），逼出「连接归还后再取」的路径。
      var myRun = sync.sync(actor, new PlatformPriceSyncRequest(null, null, mySku, null), bearer(actor));
      var theirRun =
          sync.sync(actorB, new PlatformPriceSyncRequest(null, null, theirSku, null), bearer(actorB));
      assertThat(myRun.items()).hasSize(1);
      assertThat(theirRun.items()).hasSize(1);

      var myRecords =
          tx(actor, () -> sync.listRecords(null, null, null, null, 0, 200))
              .items()
              .stream()
              .map(r -> r.id())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      var theirRecords =
          tx(actorB, () -> sync.listRecords(null, null, null, null, 0, 200))
              .items()
              .stream()
              .map(r -> r.id())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      assertThat(myRecords).isNotEmpty();
      assertThat(theirRecords).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myRecords, theirRecords)).isTrue();

      // 交替读明细：两个租户的 SKU 集合也必须不相交。
      var mySkus =
          tx(actor, () -> sync.getRecord(myRun.items().get(0).id()))
              .record()
              .skuId();
      var theirSkus =
          tx(actorB, () -> sync.getRecord(theirRun.items().get(0).id()))
              .record()
              .skuId();
      assertThat(mySkus).isEqualTo(mySku);
      assertThat(theirSkus).isEqualTo(theirSku);

      // 🔴 先在 B 的上下文里取到 B 的记录 id，再回到 A 的上下文去读 ——
      //   不能把两个租户的上下文**嵌套**起来（set_config 是事务级的，
      //   内层改掉租户后，外层读到的就已经是另一个租户的数据了）。
      var theirRecordId =
          tx(actorB, () -> sync.listRecords(null, null, null, theirSku, 0, 20))
              .items()
              .get(0)
              .id();
      assertThatThrownBy(() -> tx(actor, () -> sync.getRecord(theirRecordId)))
          .as("A 读 B 的回写记录必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);

      // 直接按主键查也读不到（RLS 兜底）。
      assertThat(
              tx(
                      actor,
                      () ->
                          sql.fetchOne(
                                  "select count(*) from platform_price_sync where id=?::uuid",
                                  theirRecordId)
                              .get(0, Long.class))
                  .longValue())
          .isZero();
    }
  }
}
