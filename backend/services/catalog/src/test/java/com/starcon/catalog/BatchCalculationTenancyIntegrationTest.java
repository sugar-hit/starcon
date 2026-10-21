package com.starcon.catalog;

import static com.starcon.catalog.BatchCalculationModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.starcon.catalog.BatchCalculationModels.BatchCalculationRequest;
import com.starcon.catalog.BatchCalculationModels.BatchRunDetail;
import com.starcon.catalog.BatchCalculationModels.ResultQuery;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.server.ResponseStatusException;

/**
 * 5.3.1.2 批量测算的**多租户三断言**。
 *
 * <p>把三条铁律**单独拉出来实跑**，因为本模块的隔离面比一般列表模块更大：
 * 批量测算的结果表里同时躺着**售价、成本口径、毛利额与毛利率** ——
 * A 读到 B 的一行，不只是「看到了别人的商品」，而是**拿到了别人店铺的定价依据**，
 * 而下游 5.4.1.2 批量调价会拿这些数字直接改价。
 *
 * <ol>
 *   <li><b>两租户互不可见</b>：批次与结果都按成员关系过滤 —— 越权访问一律 404；
 *       并且**按主键直查对方的行、断言读到零行**（只在 HTTP 层断言「列表里没有对方数据」
 *       是不够的 —— 那个结论可能来自过滤条件写对了，而底下的行照样在库里可见）。</li>
 *   <li><b>伪造 tenant header 无效</b>：租户只来自已认证成员关系，
 *       {@code X-Tenant-Id} 头改变不了任何可见范围。</li>
 *   <li><b>连接池归还后不串租户</b>：连接池压到 1 条连接，
 *       只有复用同一条物理连接，「归还后不串」才是真的被验证过 ——
 *       池大于 1 时每次可能拿到另一条干净连接，「没串」可能只是碰巧。</li>
 * </ol>
 */
class BatchCalculationTenancyIntegrationTest extends BatchCalcFixture {

  @Autowired BatchCalculationService calculations;

  private BatchCalculationRequest request(String category, String brand) {
    return new BatchCalculationRequest(
        "AMAZON",
        "US",
        "USD",
        "USD",
        category,
        brand,
        BigDecimal.ONE,
        null,
        new BigDecimal("0.15"),
        null,
        new BigDecimal("5"),
        null,
        new BigDecimal("0.15"),
        DAY_OLD.minusDays(1),
        TODAY,
        "隔离测试");
  }

  private BatchRunDetail create(AccessService.Access who, String category, String brand) {
    return write(who, () -> calculations.createRun(who, request(category, brand), bearer(who)));
  }

  // ================================================================ 断言一

  /**
   * 两租户的批次与结果互不可见，且**按主键直查对方的行读到零行**。
   *
   * <p>每个「读到零行」都配一条**同样写法的正对照**读到 1 行 —— 防假绿：
   * 一条永远返回零行的查询也能通过「读到零行」的断言。
   */
  @Test
  void 两租户的批次与结果互不可见且按主键直查对方行读到零行() {
    String category = unique("CAT");
    UUID mySku = pricedSku(actor, "TEN", category, "BrandT");
    UUID theirSku = pricedSku(actorB, "TEN", category, "BrandT");
    priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());

    var myRun = create(actor, category, "BrandT");
    var theirRun = create(actorB, category, "BrandT");

    // 正对照：两边都真的有数据 —— 否则下面的断言就是空的。
    assertThat(tx(actor, () -> calculations.getRun(myRun.id()))).isNotNull();
    assertThat(tx(actorB, () -> calculations.getRun(theirRun.id()))).isNotNull();

    // 1) 两租户互不可见：按 id 取对方的批次必须失败。
    assertThatThrownBy(() -> tx(actor, () -> calculations.getRun(theirRun.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> calculations.getRun(myRun.id())))
        .isInstanceOf(ResponseStatusException.class);

    // 批次列表里混不进对方的批次。
    var myRuns = tx(actor, () -> calculations.listRuns(0, 200)).items();
    assertThat(myRuns).isNotEmpty();
    assertThat(myRuns.stream().map(BatchRunDetail::id)).doesNotContain(theirRun.id());
    assertThat(myRuns.stream().map(BatchRunDetail::id)).contains(myRun.id());
    var theirRuns = tx(actorB, () -> calculations.listRuns(0, 200)).items();
    assertThat(theirRuns.stream().map(BatchRunDetail::id)).doesNotContain(myRun.id());
    assertThat(theirRuns.stream().map(BatchRunDetail::id)).contains(theirRun.id());

    // 直接查库也读不到对方的那一行（RLS 兜底，不只是服务层过滤）。
    // 正对照：同样写法的查询读自己的行读到 1 行。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from batch_calculation_run where id=?", myRun.id())
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from batch_calculation_run where id=?",
                                theirRun.id())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 结果行同样互不可见。
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from batch_calculation_result where run_id=?",
                                myRun.id())
                            .get(0, Long.class))
                .longValue())
        .isEqualTo(1L);
    assertThat(
            tx(
                    actor,
                    () ->
                        sql.fetchOne(
                                "select count(*) from batch_calculation_result where run_id=?",
                                theirRun.id())
                            .get(0, Long.class))
                .longValue())
        .isZero();

    // 结果列表接口也不给出对方的行。
    var myResults =
        tx(actor, () -> calculations.listResults(myRun.id(), new ResultQuery(null, null, null, null, null, null, 0, 200)));
    assertThat(myResults.items()).isNotEmpty();
    assertThat(myResults.items()).allSatisfy(row -> assertThat(row.skuId()).isEqualTo(mySku));
    assertThatThrownBy(
            () ->
                tx(
                    actor,
                    () ->
                        calculations.listResults(
                            theirRun.id(), new ResultQuery(null, null, null, null, null, null, 0, 200))))
        .isInstanceOf(ResponseStatusException.class);
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
    String category = unique("CAT");
    UUID mySku = pricedSku(actor, "FORGE", category, "BrandF");
    UUID theirSku = pricedSku(actorB, "FORGE", category, "BrandF");
    priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());
    var myRun = create(actor, category, "BrandF");
    var theirRun = create(actorB, category, "BrandF");

    // 伪造头指向 B：A 读 A 自己的批次仍然是 200，且内容与不带头时逐字段相同。
    var plain =
        http.perform(
                get("/catalog/batch-calculations/runs/" + myRun.id())
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var forged =
        http.perform(
                get("/catalog/batch-calculations/runs/" + myRun.id())
                    .header("Authorization", "Bearer tenant-a")
                    .header("X-Tenant-Id", b))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(forged).isEqualTo(plain);

    // 伪造头指向 B：A 读 B 的批次仍然是 404 —— 头改变不了任何可见范围。
    http.perform(
            get("/catalog/batch-calculations/runs/" + theirRun.id())
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/batch-calculations/runs/" + myRun.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 伪造头下的写入仍落在**调用者自己**名下 —— 断言行确实落在 A 名下，不只断言 B 看不见。
    http.perform(
            post("/catalog/batch-calculations/runs")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b)
                .contentType("application/json")
                .content(JSON.writeValueAsString(request(category, "BrandF"))))
        .andExpect(status().isOk());
    var owners =
        tx(
                actor,
                () ->
                    sql.fetch("select distinct tenant_id from batch_calculation_run where scope_category=?", category)
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
    String category = unique("CAT");
    UUID mySku = pricedSku(actor, "POOL", category, "BrandP");
    UUID theirSku = pricedSku(actorB, "POOL", category, "BrandP");
    priceObservation(actor, mySku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    priceObservation(actorB, theirSku, "AMAZON", "US", "USD", "50", null, OffsetDateTime.now());
    var myRun = create(actor, category, "BrandP");
    var theirRun = create(actorB, category, "BrandP");

    for (int i = 0; i < 5; i++) {
      var myRunIds =
          tx(actor, () -> calculations.listRuns(0, 200)).items().stream()
              .map(BatchRunDetail::id)
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      var theirRunIds =
          tx(actorB, () -> calculations.listRuns(0, 200)).items().stream()
              .map(BatchRunDetail::id)
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      assertThat(myRunIds).isNotEmpty();
      assertThat(theirRunIds).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myRunIds, theirRunIds)).isTrue();

      // 交替读结果行：两个租户的结果集合也必须不相交。
      var mySkus =
          tx(actor, () -> calculations.listResults(myRun.id(), new ResultQuery(null, null, null, null, null, null, 0, 200)))
              .items()
              .stream()
              .map(r -> r.skuId())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      var theirSkus =
          tx(actorB, () -> calculations.listResults(theirRun.id(), new ResultQuery(null, null, null, null, null, null, 0, 200)))
              .items()
              .stream()
              .map(r -> r.skuId())
              .collect(java.util.stream.Collectors.toCollection(HashSet::new));
      assertThat(mySkus).containsOnly(mySku);
      assertThat(theirSkus).containsOnly(theirSku);
      assertThat(java.util.Collections.disjoint(mySkus, theirSkus)).isTrue();

      assertThatThrownBy(() -> tx(actor, () -> calculations.getRun(theirRun.id())))
          .as("A 读 B 的批量测算批次必须失败而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }
}
