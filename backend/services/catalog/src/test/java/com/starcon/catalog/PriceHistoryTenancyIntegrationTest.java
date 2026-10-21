package com.starcon.catalog;

import static com.starcon.catalog.PriceHistoryModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeInput;
import com.starcon.catalog.PriceHistoryModels.PriceHistoryChangeQuery;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.5.1.1 价格历史的**多租户三断言**。
 *
 * <p>把三条铁律**单独拉出来实跑**，因为本模块的隔离面比一般列表模块更大：
 * 价格变更事实里同时躺着**旧价、新价、改价前后的毛利额与毛利率** ——
 * A 读到 B 的一行，不只是「看到了别人的商品」，而是**拿到了别人店铺的定价依据与利润结构**。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：变更事实按成员关系过滤 —— 越权访问一律 404；
 *       并且**按主键直查对方的行、断言读到零行**（只在 HTTP 层断言「列表里没有对方数据」
 *       是不够的 —— 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见）。</li>
 *   <li><b>伪造 tenant header 无效</b>：租户只来自已认证成员关系，
 *       {@code X-Tenant-Id} 头改变不了任何可见范围。</li>
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，
 *       只有复用同一条物理连接，「归还后不串」才是真的被验证过 ——
 *       池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。</li>
 * </ol>
 */
class PriceHistoryTenancyIntegrationTest extends BatchCalcFixture {

  @Autowired PriceHistoryService history;

  private static final String PLATFORM = "AMAZON";
  private static final String SITE = "US";

  /** 登记一次价格变更（走真实写入口）。 */
  private PriceHistoryChangeRow register(
      AccessService.Access who, UUID skuId, String oldPrice, String newPrice, String reason) {
    var input =
        new PriceHistoryChangeInput(
            skuId,
            PLATFORM,
            SITE,
            "USD",
            oldPrice == null ? null : new BigDecimal(oldPrice),
            new BigDecimal(newPrice),
            "MANUAL_ADJUSTMENT",
            "",
            reason,
            OffsetDateTime.now(),
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            "");
    var saved = write(who, () -> history.register(who, bearer(who), input));
    return tx(who, () -> history.getChange(saved.id())).change();
  }

  // ================================================================ 断言一

  /**
   * 两租户的变更事实互不可见，且**按主键直查对方的行读到零行**。
   *
   * <p>每个「读到零行」都配一条**同样写法的正对照**读到 1 行 —— 防假绿：
   * 一条永远返回零行的查询也能通过「读到零行」的断言。
   */
  @Test
  void 两租户的变更事实互不可见且按主键直查对方行读到零行() {
    UUID mySku = pricedSku(actor, "TEN", unique("CAT"), "BrandT");
    UUID theirSku = pricedSku(actorB, "TEN", unique("CAT"), "BrandT");
    var mine = register(actor, mySku, "100", "90", "我的降价");
    var theirs = register(actorB, theirSku, "50", "40", "他们的降价");

    // 正对照：两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> history.getChange(mine.id()))).isNotNull();
    assertThat(tx(actorB, () -> history.getChange(theirs.id()))).isNotNull();

    // 1) 两租户互不可见：按 id 取对方的变更事实必须失败。
    assertThatThrownBy(() -> tx(actor, () -> history.getChange(theirs.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> history.getChange(mine.id())))
        .isInstanceOf(ResponseStatusException.class);

    // 列表里混不进对方的行。
    var myRows =
        tx(actor, () -> history.listChanges(new PriceHistoryChangeQuery(null, mySku, null, null, null, null, null, null, null, null, 0, 200))).items();
    assertThat(myRows).isNotEmpty();
    assertThat(myRows).allSatisfy(r -> assertThat(r.skuId()).isEqualTo(mySku));
    var theirRows =
        tx(actorB, () -> history.listChanges(new PriceHistoryChangeQuery(null, theirSku, null, null, null, null, null, null, null, null, 0, 200))).items();
    assertThat(theirRows).allSatisfy(r -> assertThat(r.skuId()).isEqualTo(theirSku));

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    // 正对照：同样写法的查询读自己的行读到 1 行。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_change_history where id=?", mine.id())
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_change_history where id=?", theirs.id())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 时间轴也互不可见。
    var theirTimeline = tx(actor, () -> history.timeline(theirSku, PLATFORM, SITE, null, null));
    assertThat(theirTimeline.changeCount()).isZero();
    var myTimeline = tx(actor, () -> history.timeline(mySku, PLATFORM, SITE, null, null));
    assertThat(myTimeline.changeCount()).isEqualTo(1);
  }

  // ================================================================ 断言二

  /**
   * 伪造 {@code X-Tenant-Id} 头**无效**：租户只来自已认证成员关系。
   *
   * <p>🔴 断言的是「读到的内容与不带头时**逐字段相同**」，不是「4xx」——
   * 本模块在越权读取时返回 404（不泄露「存在」这个事实），
   * 而读取自己租户的数据时应当**完全不受这个头影响**。
   */
  @Test
  void 伪造tenant头无效且读到自己租户的数据逐字段相同() throws Exception {
    UUID mySku = pricedSku(actor, "FORGE", unique("CAT"), "BrandF");
    UUID theirSku = pricedSku(actorB, "FORGE", unique("CAT"), "BrandF");
    var mine = register(actor, mySku, "100", "90", "我的降价");
    var theirs = register(actorB, theirSku, "50", "40", "他们的降价");

    // 伪造头指向 B：A 读 A 自己的变更事实仍然是 200，且内容与不带头时逐字段相同。
    var plain =
        http.perform(
                get("/catalog/price-history/changes/" + mine.id())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var forged =
        http.perform(
                get("/catalog/price-history/changes/" + mine.id())
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).isEqualTo(plain);

    // 伪造头指向 B：A 读 B 的变更事实仍然是 404 —— 头改变不了任何可见范围。
    http.perform(
            get("/catalog/price-history/changes/" + theirs.id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/price-history/changes/" + mine.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    var body =
        """
        {"skuId":"%s","platformCode":"AMAZON","siteCode":"US","currency":"USD",
         "oldPrice":90.00,"newPrice":85.00,"changeSource":"MANUAL_ADJUSTMENT",
         "reasonText":"伪造头下的写入","effectiveAt":"2026-03-01T10:15:30+08:00"}
        """
            .formatted(mySku);
    http.perform(
            post("/catalog/price-history/changes")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk());
    var owners =
        tx(
                actor,
                () ->
                    sql.fetch(
                            "select distinct tenant_id from price_change_history where sku_id=?",
                            mySku)
                        .stream()
                        .map(row -> row.get(0, UUID.class))
                        .toList())
            .stream()
            .map(u -> u.toString())
            .toList();
    assertThat(owners).isNotEmpty();
    assertThat(owners).containsOnly(a.toString());
  }

  // ================================================================ 断言三

  /**
   * 连接池归还后**不串租户**（单连接反复交替），逐轮断言两个集合不相交。
   *
   * <p>连接池压到 1 条连接（见底座）：每个请求必然复用同一条物理连接，
   * 租户设置如果泄漏到连接上，第二个租户就会看到第一个租户的数据。
   */
  @Test
  void 连接池归还后不串租户() {
    UUID mySku = pricedSku(actor, "POOL", unique("CAT"), "BrandP");
    UUID theirSku = pricedSku(actorB, "POOL", unique("CAT"), "BrandP");

    for (int i = 0; i < 5; i++) {
      var mine = register(actor, mySku, "100", "90", "第 " + i + " 次");
      var theirs = register(actorB, theirSku, "50", "40", "第 " + i + " 次");

      var myIds =
          tx(actor, () -> history.listChanges(new PriceHistoryChangeQuery(null, mySku, null, null, null, null, null, null, null, null, 0, 200))).items()
              .stream()
              .map(r -> r.id())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      var theirIds =
          tx(actorB, () -> history.listChanges(new PriceHistoryChangeQuery(null, theirSku, null, null, null, null, null, null, null, null, 0, 200))).items()
              .stream()
              .map(r -> r.id())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      assertThat(myIds).isNotEmpty();
      assertThat(theirIds).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myIds, theirIds)).isTrue();

      // 交替读单条：A 读不到 B 的，B 读不到 A 的。
      assertThatThrownBy(() -> tx(actor, () -> history.getChange(theirs.id())))
          .as("A 读 B 的价格变更必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> tx(actorB, () -> history.getChange(mine.id())))
          .as("B 读 A 的价格变更必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 原因字典

  /** 原因字典是**租户级**的：A 建的字典，B 看不见。 */
  @Test
  void 原因字典是租户级的() {
    write(
        actor,
        () ->
            history.saveReason(
                actor, new PriceHistoryReasonInput("ONLY-A", "只有 A 有", "COMPETITION", true, 1, "")));

    var myReasons = tx(actor, history::reasons);
    var theirReasons = tx(actorB, history::reasons);
    assertThat(myReasons.stream().map(r -> r.code())).contains("ONLY-A");
    assertThat(theirReasons.stream().map(r -> r.code())).doesNotContain("ONLY-A");

    // 直接查库也读不到（RLS 兜底）。
    assertThat(
            tx(
                    actorB,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_change_reason where code=?", "ONLY-A")
                            .get(0, Long.class))
                .longValue())
        .isZero();
  }

  @Test
  void 读取变更事实的列表不会带出别的租户的SKU() {
    UUID mySku = pricedSku(actor, "MIX", unique("CAT"), "BrandM");
    UUID theirSku = pricedSku(actorB, "MIX", unique("CAT"), "BrandM");
    register(actor, mySku, "100", "90", "我的");
    register(actorB, theirSku, "50", "40", "他们的");

    var rows =
        tx(actor, () -> history.listChanges(new PriceHistoryChangeQuery(null, null, PLATFORM, SITE, null, null, null, null, null, null, 0, 200))).items();
    assertThat(rows).isNotEmpty();
    assertThat(rows.stream().map(r -> r.skuId()).toList()).doesNotContain(theirSku);
    assertThat(rows.stream().map(r -> r.skuId()).toList()).contains(mySku);
    // 列表里也不该出现对方的 SKU 编码。
    assertThat(rows.stream().map(r -> r.skuCode()).toList())
        .allSatisfy(code -> assertThat(code).doesNotContain("MIX-SKU-" + theirSku.toString()));
  }

  @Test
  void 原因字典保存只影响自己的租户() {
    write(
        actorB,
        () ->
            history.saveReason(
                actorB, new PriceHistoryReasonInput("ONLY-B", "只有 B 有", "COST", true, 1, "")));
    var myReasons = tx(actor, history::reasons);
    assertThat(myReasons.stream().map(r -> r.code())).doesNotContain("ONLY-B");
    var theirReasons = tx(actorB, history::reasons);
    assertThat(theirReasons.stream().map(r -> r.code())).contains("ONLY-B");
    assertThat(List.of(theirReasons)).isNotEmpty();
  }
}
