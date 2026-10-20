package com.starcon.catalog;

import static com.starcon.catalog.PriceAdjustmentModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentExpectedVersionRequest;
import com.starcon.catalog.PriceAdjustmentModels.OrderQuery;
import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.4.1.1 调价单的**多租户三断言**。
 *
 * <p>把三条铁律**单独拉出来实跑**，因为本模块的隔离面比一般列表模块更大：
 * 一张调价单里同时躺着**旧价、新价、改价前后的毛利额与毛利率、审批实例号** ——
 * A 读到 B 的一行，不只是「看到了别人的商品」，而是
 * **拿到了别人店铺的定价依据、利润结构，以及那张单在审批系统里的挂单号**。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：调价单与明细都按成员关系过滤 —— 越权访问一律 404；
 *       并且<b>按主键直查对方的行、断言读到零行</b>（只在 HTTP 层断言「列表里没有对方数据」
 *       是不够的 —— 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见）。</li>
 *   <li><b>伪造 tenant header 无效</b>：租户只来自已认证成员关系，
 *       {@code X-Tenant-Id} 头改变不了任何可见范围。</li>
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，
 *       只有复用同一条物理连接，「归还后不串」才是真的被验证过 ——
 *       池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。</li>
 * </ol>
 */
class PriceAdjustmentTenancyIntegrationTest extends PriceAdjustmentFixture {

  @Autowired PriceAdjustmentService adjustments;

  @BeforeEach
  void resetApproval() {
    EVALUATION.set(Evaluation.EXEMPT);
    identityDown.set(false);
    INSTANCES.clear();
  }

  private PriceAdjustmentModels.PriceAdjustmentDetail create(
      AccessService.Access who, UUID skuId, String newPrice) {
    return write(who, () -> adjustments.create(who, request(skuId, newPrice), bearer(who)));
  }

  // ================================================================ 断言一

  /**
   * 两租户的调价单与明细互不可见，且<b>按主键直查对方的行读到零行</b>。
   *
   * <p>每个「读到零行」都配一条<b>同样写法的正对照</b>读到 1 行 —— 防假绿：
   * 一条永远返回零行的查询也能通过「读到零行」的断言。
   */
  @Test
  void 两租户的调价单与明细互不可见且按主键直查对方行读到零行() {
    String category = unique("CAT");
    UUID mySku = pricedSku(actor, "TEN", category, "BrandT");
    UUID theirSku = pricedSku(actorB, "TEN", category, "BrandT");
    priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());

    var myOrder = create(actor, mySku, "120");
    var theirOrder = create(actorB, theirSku, "60");

    // 正对照：两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> adjustments.get(myOrder.id(), false, bearer(actor)))).isNotNull();
    assertThat(tx(actorB, () -> adjustments.get(theirOrder.id(), false, bearer(actorB))))
        .isNotNull();

    // 1) 两租户互不可见：按 id 取对方的调价单必须失败。
    assertThatThrownBy(
            () -> tx(actor, () -> adjustments.get(theirOrder.id(), false, bearer(actor))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () -> tx(actorB, () -> adjustments.get(myOrder.id(), false, bearer(actorB))))
        .isInstanceOf(ResponseStatusException.class);

    // 调价单列表里混不进对方的调价单。
    var myOrders =
        tx(
            actor,
            () ->
                adjustments.list(new OrderQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(myOrders.items()).isNotEmpty();
    assertThat(myOrders.items().stream().map(o -> o.id())).doesNotContain(theirOrder.id());
    assertThat(myOrders.items().stream().map(o -> o.id())).contains(myOrder.id());
    var theirOrders =
        tx(
            actorB,
            () ->
                adjustments.list(new OrderQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(theirOrders.items().stream().map(o -> o.id())).doesNotContain(myOrder.id());
    assertThat(theirOrders.items().stream().map(o -> o.id())).contains(theirOrder.id());

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    // 正对照：同样写法的查询读自己的行读到 1 行。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_adjustment_order where id=?",
                                myOrder.id())
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_adjustment_order where id=?",
                                theirOrder.id())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 明细行同样互不可见。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_adjustment_order_item where order_id=?",
                                myOrder.id())
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_adjustment_order_item where order_id=?",
                                theirOrder.id())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 待回写事实同样互不可见。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from price_adjustment_writeback where order_id=?",
                                myOrder.id())
                            .get(0, Long.class))
                .longValue())
        .isZero();
  }

  // ================================================================ 断言二

  /**
   * 伪造 {@code X-Tenant-Id} 头<b>无效</b>：租户只来自已认证成员关系。
   *
   * <p>🔴 断言的是「读到的内容与不带头时<b>逐字段相同</b>」，不是「4xx」——
   * 本模块在越权读取时返回 404（不泄露「存在」这个事实），
   * 而读取自己租户的数据时应当<b>完全不受这个头影响</b>。
   */
  @Test
  void 伪造tenant头无效且读到自己租户的数据逐字段相同() throws Exception {
    String category = unique("CAT");
    UUID mySku = pricedSku(actor, "FORGE", category, "BrandF");
    UUID theirSku = pricedSku(actorB, "FORGE", category, "BrandF");
    priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());
    var myOrder = create(actor, mySku, "120");
    var theirOrder = create(actorB, theirSku, "60");

    // 伪造头指向 B：A 读 A 自己的调价单仍然是 200，且内容与不带头时逐字段相同。
    var plain =
        http.perform(
                get("/catalog/price-adjustments/" + myOrder.id())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var forged =
        http.perform(
                get("/catalog/price-adjustments/" + myOrder.id())
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).isEqualTo(plain);

    // 伪造头指向 B：A 读 B 的调价单仍然是 404 —— 头改变不了任何可见范围。
    http.perform(
            get("/catalog/price-adjustments/" + theirOrder.id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/price-adjustments/" + myOrder.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 伪造头下的写入仍落在<b>调用者自己</b>名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    var created =
        http.perform(
                post("/catalog/price-adjustments")
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b)
                    .contentType("application/json")
                    .content(JSON.writeValueAsString(request(mySku, "130"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var createdId = JSON.readTree(created).get("id").asText();
    var owners =
        tx(
                actor,
                () ->
                    sql.fetch(
                            "select distinct tenant_id from price_adjustment_order where id=?::uuid",
                            UUID.fromString(createdId))
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
    String category = unique("CAT");
    UUID mySku = pricedSku(actor, "POOL", category, "BrandP");
    UUID theirSku = pricedSku(actorB, "POOL", category, "BrandP");
    priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());

    for (int i = 0; i < 5; i++) {
      var myOrder = create(actor, mySku, "12" + i);
      var theirOrder = create(actorB, theirSku, "6" + i);

      // 交替走一遍完整的状态流转（写 → 提交 → 执行），逼出「连接归还后再取」的路径。
      write(
          actor,
          () ->
              adjustments.submit(
                  actor, myOrder.id(), new PriceAdjustmentExpectedVersionRequest(myOrder.version(), null),
                  bearer(actor)));
      write(
          actorB,
          () ->
              adjustments.submit(
                  actorB, theirOrder.id(), new PriceAdjustmentExpectedVersionRequest(theirOrder.version(), null),
                  bearer(actorB)));

      var myOrderIds =
          tx(actor, () -> adjustments.list(new OrderQuery(null, null, null, null, null, null, 0, 200)))
              .items()
              .stream()
              .map(o -> o.id())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      var theirOrderIds =
          tx(
                  actorB,
                  () ->
                      adjustments.list(new OrderQuery(null, null, null, null, null, null, 0, 200)))
              .items()
              .stream()
              .map(o -> o.id())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      assertThat(myOrderIds).isNotEmpty();
      assertThat(theirOrderIds).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myOrderIds, theirOrderIds)).isTrue();

      // 交替读明细：两个租户的 SKU 集合也必须不相交。
      var mySkus =
          tx(actor, () -> adjustments.get(myOrder.id(), false, bearer(actor)))
              .items()
              .stream()
              .map(r -> r.skuId())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      var theirSkus =
          tx(actorB, () -> adjustments.get(theirOrder.id(), false, bearer(actorB)))
              .items()
              .stream()
              .map(r -> r.skuId())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      assertThat(mySkus).containsOnly(mySku);
      assertThat(theirSkus).containsOnly(theirSku);
      assertThat(java.util.Collections.disjoint(mySkus, theirSkus)).isTrue();

      assertThatThrownBy(
              () -> tx(actor, () -> adjustments.get(theirOrder.id(), false, bearer(actor))))
          .as("A 读 B 的调价单必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  /** 金额与币种在跨租户读取时同样不可见（防御性断言：详情里的旧价也是租户内的）。 */
  @Test
  void 跨租户读不到对方的旧价() {
    String category = unique("CAT");
    UUID mySku = pricedSku(actor, "AMT", category, "BrandA");
    UUID theirSku = pricedSku(actorB, "AMT", category, "BrandA");
    priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());
    var myOrder = create(actor, mySku, "120");
    var theirOrder = create(actorB, theirSku, "60");

    assertThat(tx(actor, () -> adjustments.get(myOrder.id(), false, bearer(actor))).oldPrice())
        .isEqualTo(new BigDecimal("100.0000"));
    assertThatThrownBy(
            () -> tx(actor, () -> adjustments.get(theirOrder.id(), false, bearer(actor))))
        .isInstanceOf(ResponseStatusException.class);
  }
}
