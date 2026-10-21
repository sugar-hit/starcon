package com.starcon.catalog;

import static com.starcon.catalog.PriceAdjustmentModels.*;
import static org.assertj.core.api.Assertions.*;

import com.starcon.catalog.PriceAdjustmentModels.PriceAdjustmentExpectedVersionRequest;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleInput;
import com.starcon.catalog.PlatformPriceSyncModels.PlatformPriceRuleType;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 5.4.2.1 平台价格同步的**共享底座**。
 *
 * <p>🔴 本底座**继承** 5.4.1.1 的 {@link PriceAdjustmentFixture}（真实 PG16 容器 + identity 权限替身 +
 * warehouse / channel 替身 + 单连接连接池 + SKU / 价格观测夹具 + 调价单夹具），**不改它一个字符** ——
 * 底座是共享资产，改它会让兄弟模块的测试一起红。本类只加本模块自己需要的东西：
 *
 * <ol>
 *   <li><b>02 队列替身</b>（channel 的 {@code platform_operation}）：本模块把回写任务投递过去，
 *       替身要能回答「已登记的店铺与站点在哪」「这次任务登记成什么 id」「回执收没收到」。</li>
 *   <li><b>造一条真的上游待回写事实</b>：本模块消费的是 5.4.1.1 的
 *       {@code price_adjustment_writeback}，那张表有触发器要求事实必须挂在真实存在的调价单明细上 ——
 *       所以这里**真的走一遍调价单的建单 → 提交 → 执行**，让事实由上游自己产出，
 *       而不是绕过触发器插一行假的。</li>
 * </ol>
 *
 * <p><b>替身只存在于测试代码里</b>：生产代码路径永远是真实的 HTTP 调用。
 */
abstract class PlatformPriceSyncFixture extends PriceAdjustmentFixture {

  @org.springframework.beans.factory.annotation.Autowired PlatformPriceSyncService sync;

  /** 上游 5.4.1.1 调价单服务（本模块消费它产出的待回写事实）。 */
  @org.springframework.beans.factory.annotation.Autowired PriceAdjustmentService adjustments;

  /** 已登记的店铺（登记式边界）。 */
  static final UUID SHOP_ID = UUID.randomUUID();

  /** 已登记的站点。 */
  static final UUID MARKETPLACE_ID = UUID.randomUUID();

  /** 02 队列替身收到的 Authorization 头（断言租户不是靠自定义头传过去的）。 */
  static final AtomicReference<String> lastQueueBearer = new AtomicReference<>();

  /** 置位后 02 队列替身一律回 503（模拟「渠道服务不可达」）。 */
  static final AtomicBoolean queueDown = new AtomicBoolean();

  /** 置位后 02 队列替身**不返回**任何已登记店铺（模拟「这个平台 / 站点没有登记落点」）。 */
  static final AtomicBoolean shopsEmpty = new AtomicBoolean();

  /** 02 队列替身收到的投递次数（断言「同一把幂等键不产生第二次回写」）。 */
  static final AtomicInteger enqueueCount = new AtomicInteger();

  /** 02 队列替身收到的投递载荷（逐条留存，断言 payload 形状）。 */
  static final List<Map<String, Object>> enqueued = new CopyOnWriteArrayList<>();

  /** 02 队列替身收到的回执（成功 / 失败）。 */
  static final List<String> completed = new CopyOnWriteArrayList<>();

  /** 02 队列任务 id（按投递序号）。 */
  static final Map<Integer, UUID> operationIds = new ConcurrentHashMap<>();

  static {
    // 🔴 用**更具体**的 context 挂到共享的渠道替身上：
    // com.sun.net.httpserver 按**最长前缀**匹配，所以 "/channel/shops" 会盖过 "/channel"，
    // 而兄弟模块用的 "/channel" 那一支**一字未动**（共享底座不被污染）。
    channel.createContext("/channel/shops", PlatformPriceSyncFixture::handleShops);
    channel.createContext("/channel/platform-operations", PlatformPriceSyncFixture::handleOperations);
  }

  // ================================================================ 02 队列替身

  /** 已登记的店铺与站点。 */
  private static void handleShops(com.sun.net.httpserver.HttpExchange e) throws IOException {
    lastQueueBearer.set(e.getRequestHeaders().getFirst("Authorization"));
    if (queueDown.get()) {
      respond(e, 503, Map.of("message", "渠道服务暂时不可达"));
      return;
    }
    if (shopsEmpty.get()) {
      respond(e, 200, List.of());
      return;
    }
    var marketplace = new java.util.LinkedHashMap<String, Object>();
    marketplace.put("marketplaceId", MARKETPLACE_ID);
    marketplace.put("marketplaceCode", "US");
    marketplace.put("marketplaceName", "美国站");
    var shop = new java.util.LinkedHashMap<String, Object>();
    shop.put("id", SHOP_ID);
    shop.put("code", "SHOP-AMZ-US");
    shop.put("name", "Amazon 美国店");
    shop.put("platformCode", "AMAZON");
    shop.put("marketplaces", List.of(marketplace));
    respond(e, 200, List.of(shop));
  }

  /** 02 队列任务登记与回执。 */
  private static void handleOperations(com.sun.net.httpserver.HttpExchange e) throws IOException {
    lastQueueBearer.set(e.getRequestHeaders().getFirst("Authorization"));
    if (queueDown.get()) {
      respond(e, 503, Map.of("message", "渠道服务暂时不可达"));
      return;
    }
    String path = e.getRequestURI().getPath();
    String method = e.getRequestMethod();
    if ("POST".equals(method) && path.equals("/channel/platform-operations")) {
      var body = readJson(e);
      int seq = enqueueCount.incrementAndGet();
      enqueued.add(body);
      UUID id = UUID.randomUUID();
      operationIds.put(seq, id);
      // 与 channel 的 enqueueOperation 同形：返回任务 id（裸 UUID）。
      respond(e, 200, id.toString());
      return;
    }
    if ("POST".equals(method) && path.startsWith("/channel/platform-operations/")) {
      completed.add(path);
      respond(e, 200, Map.of("ok", true));
      return;
    }
    respond(e, 404, Map.of("message", "没有这个端点"));
  }

  // ================================================================ 夹具

  /** 复位 02 队列替身的状态。 */
  void resetQueue() {
    queueDown.set(false);
    shopsEmpty.set(false);
    enqueueCount.set(0);
    enqueued.clear();
    completed.clear();
    operationIds.clear();
    lastQueueBearer.set(null);
  }

  /**
   * 把本租户**生效**的规则全部停用。
   *
   * <p>🔴 测试容器在同一个 JVM 里被所有测试类共享，规则表不会自动清空 ——
   * 留着上一条用例建出来的生效规则，后一条用例建同作用域的规则就会撞唯一约束，
   * 测出来的失败原因与被测行为无关。所以每条用例开始前先把生效规则停用
   * （停用不占唯一索引的坑，见 V245 的部分索引说明）。
   */
  @org.junit.jupiter.api.BeforeEach
  void disableActiveRules() {
    // 两个租户都要复位：唯一索引是 (tenant_id, …)，A 的规则不占 B 的坑，
    // 但 B 自己上一条用例留下的生效规则会挡住下一条。
    disableActiveRules(actor);
    disableActiveRules(actorB);
  }

  /** 把某个租户生效的规则全部停用。 */
  void disableActiveRules(AccessService.Access who) {
    writeVoid(
        who,
        () ->
            sql.execute(
                "update platform_price_rule set status='DISABLED'"
                    + " where tenant_id=nullif(current_setting('app.current_tenant',true),'')::uuid"
                    + " and status='ACTIVE'"));
  }

  /**
   * 造一条**真的**上游待回写事实：建调价单 → 提交 → 执行。
   *
   * <p>🔴 走的是 5.4.1.1 的真服务（不是直接插表）：事实由上游自己产出，
   * 它的触发器、幂等键与状态机都被真正跑到 —— 本模块消费的就是那个形状。
   *
   * @param price 新价（= 期望回写上去的价）
   * @return 待回写事实的 id 与幂等键
   */
  record UpstreamFact(UUID writebackId, String idempotencyKey, BigDecimal expectedPrice) {}

  UpstreamFact upstreamFact(UUID skuId, String price) {
    return upstreamFact(actor, skuId, price);
  }

  /** 指定租户造一条真的上游待回写事实（多租户用例用）。 */
  UpstreamFact upstreamFact(AccessService.Access who, UUID skuId, String price) {
    EVALUATION.set(Evaluation.EXEMPT);
    var draft = write(who, () -> adjustments.create(who, request(skuId, price), bearer(who)));
    var approved =
        write(
            who,
            () ->
                adjustments.submit(
                    who, draft.id(), new PriceAdjustmentExpectedVersionRequest(draft.version(), null),
                    bearer(who)));
    write(
        who,
        () ->
            adjustments.execute(
                who, draft.id(), new PriceAdjustmentExpectedVersionRequest(approved.version(), null),
                bearer(who)));
    var writebacks =
        tx(who, () -> adjustments.listWritebacks("PENDING", skuId, "AMAZON", "US", 0, 20));
    assertThat(writebacks.total()).isEqualTo(1L);
    var fact = writebacks.items().get(0);
    return new UpstreamFact(fact.id(), fact.idempotencyKey(), fact.expectedPrice());
  }

  /** 建一条平台最低价规则。 */
  UUID minPriceRule(String platformCode, String siteCode, String minPrice, String currency) {
    return minPriceRule(actor, platformCode, siteCode, minPrice, currency);
  }

  /** 建一条平台最低价规则（指定租户）。 */
  UUID minPriceRule(
      AccessService.Access who, String platformCode, String siteCode, String minPrice, String currency) {
    var rule =
        write(
            who,
            () ->
                sync.createRule(
                    new PlatformPriceRuleInput(
                        PlatformPriceRuleType.MIN_PRICE,
                        platformCode,
                        siteCode,
                        null,
                        null,
                        null,
                        new BigDecimal(minPrice),
                        currency,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        "测试用最低价规则")));
    return rule.id();
  }

  /** 建一条自动比价规则（对比平台 / 对比站点 / 允许的最大价差比例）。 */
  UUID autoCompareRule(
      String platformCode, String siteCode, String comparePlatform, String compareSite, String gapRatio) {
    var rule =
        write(
            actor,
            () ->
                sync.createRule(
                    new PlatformPriceRuleInput(
                        PlatformPriceRuleType.AUTO_COMPARE,
                        platformCode,
                        siteCode,
                        null,
                        null,
                        null,
                        null,
                        null,
                        comparePlatform,
                        compareSite,
                        new BigDecimal(gapRatio),
                        null,
                        null,
                        null,
                        "测试用自动比价规则")));
    return rule.id();
  }

  /** 建一条多站点联动规则（参照站点 / 比例带）。 */
  UUID multiSiteLinkRule(
      String platformCode, String siteCode, String referenceSite, String minRatio, String maxRatio) {
    var rule =
        write(
            actor,
            () ->
                sync.createRule(
                    new PlatformPriceRuleInput(
                        PlatformPriceRuleType.MULTI_SITE_LINK,
                        platformCode,
                        siteCode,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        referenceSite,
                        new BigDecimal(minRatio),
                        new BigDecimal(maxRatio),
                        "测试用多站点联动规则")));
    return rule.id();
  }

  /** 造一个带价格观测的 SKU（内部价 100 / 平台价可空）。 */
  UUID observedSku(String prefix) {
    UUID sku = pricedSku(actor, prefix, unique("CAT"), "BrandPPS");
    priceObservation(actor, sku, "AMAZON", "US", "USD", "100", null, OffsetDateTime.now());
    return sku;
  }
}
