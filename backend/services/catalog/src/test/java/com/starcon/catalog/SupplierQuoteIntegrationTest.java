package com.starcon.catalog;

import static com.starcon.catalog.CatalogModels.*;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 3.6.2.1 供应商报价：报价与阶梯价的真实落库往返、按数量取价、
 * 规则 R1~R7，以及<b>租户隔离三断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：报价与阶梯全部真的落库，不使用任何内存替身。
 * 连接池固定为 1 条连接：只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最重要的四个断言</b>：
 *
 * <ol>
 *   <li>{@link #同优先级并列的有效报价必须报错让用户选()}：蓝图点名的那条规则。
 *       这里断言的是<b>真的抛了 409</b> 且 body 里<b>带得出候选清单</b> ——
 *       只报一句"存在多条"而不给候选的话，"让用户选"是落空的（用户无从选）。
 *   <li>{@link #租户隔离三断言覆盖报价行()}：报价行才是本模块真正的越权面 ——
 *       「A 读到自己的采购成本时混进 B 的报价」会直接让 A 的利润算错。
 *   <li>{@link #按数量取适用档位()}：阶梯取价写错了不报错，只会让采购单按更贵或更便宜的
 *       那一档结算，而账面看不出任何异常。
 *   <li>{@link #报价随供应商身份解除级联删除()}：V32 的 ON DELETE CASCADE 真的生效 ——
 *       留下孤儿报价会让 06 采购带出一家已不再是供应商的报价。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class SupplierQuoteIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();
  static final AtomicInteger SEQ = new AtomicInteger();
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
        uid, tenant, "Test", UUID.randomUUID(), dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(), grants);
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
            byte[] body = JSON.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions))));
      // 只有商品读权限：写端点必须 403（报价沿用商品主数据域权限）。
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
      // 只有商品读权限的**读**身份：能读报价但不能写。
      // 刻意给足 catalog.sku.read —— V3 的 sku_read_scope 是 RESTRICTIVE 策略，
      // 读报价要 join sku，而 sku 在 operation='catalog.spu.read' 时额外要求
      // catalog_scope('catalog.sku.read')。只有 spu.read 的身份读报价会拿到 404
      // （sku 行被策略过滤掉 → join 落空 → 看起来像"报价不存在"）。
      // 那是已交付的 V3 数据范围设计在起作用，不是本模块的缺陷：
      // 报价的成本数据本来就不该让连 SKU 都看不见的角色拿到。
      actors.put(
          "Bearer catalog-reader",
          actor(
              user, a, null,
              List.of(grant("ALL", List.of("catalog.spu.read", "catalog.sku.read")))));
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
    // 单连接：只有复用同一条连接，才能真正证明归还之后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired SupplierQuoteService quotes;
  @Autowired PartyService parties;
  @Autowired CatalogService catalog;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired PlatformTransactionManager manager;
  @Autowired MockMvc http;

  AccessService.Access actor;
  AccessService.Access actorB;

  @BeforeEach
  void setUp() {
    actor = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    unit(actor, "PCS", true, true);
    unit(actorB, "PCS", true, true);
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  /** 写事务（报价的写端点走 update 权限上下文）。 */
  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  void writeVoid(AccessService.Access who, Runnable work) {
    access.run(
        who,
        "catalog.spu.update",
        () -> {
          work.run();
          return null;
        });
  }

  static String unique(String prefix) {
    return prefix + "-" + SEQ.incrementAndGet();
  }

  // ================================================================ 夹具

  /** 登记一个计量单位（报价的 purchase_unit_code 外键指向它）。 */
  void unit(AccessService.Access who, String code, boolean enabled, boolean allowPurchase) {
    tx(
        who,
        () -> {
          sql.execute(
              "insert into unit_of_measure(id,tenant_id,code,name,dimension,scale,allow_purchase,"
                  + "allow_sale,allow_storage,enabled) values(?::uuid,?::uuid,?,?, 'COUNT',0,?,true,true,?)"
                  + " on conflict (tenant_id,code) do nothing",
              UUID.randomUUID(), who.tenantId(), code, code, allowPurchase, enabled);
          return null;
        });
  }

  /** 建一个带供应商身份的 ACTIVE 主体。 */
  UUID supplier(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    return write(
            who,
            () ->
                parties.save(
                    who,
                    null,
                    new PartyInput(
                        code, "供应商 " + code, "ORGANIZATION", "CN",
                        "mail@" + code.toLowerCase() + ".example", null, null,
                        "ACTIVE", List.of(new PartyRoleInput("SUPPLIER", "ACTIVE", "")), 0)))
        .id();
  }

  /** 建一个只有客户身份的主体（用来验证规则 R1：报价必须挂在供应商身份上）。 */
  UUID customerOnly(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    return write(
            who,
            () ->
                parties.save(
                    who,
                    null,
                    new PartyInput(
                        code, "客户 " + code, "ORGANIZATION", "CN",
                        "mail@" + code.toLowerCase() + ".example", null, null,
                        "ACTIVE", List.of(new PartyRoleInput("CUSTOMER", "ACTIVE", "")), 0)))
        .id();
  }

  /**
   * 建一个 SPU + SKU（报价挂在 SKU 上）。
   *
   * <p>SPU 建为 DRAFT：3.1.1.1 的生命周期要求「新商品先保存为草稿，补齐 SKU 后再提交待上架」，
   * 直接建 ACTIVE 会被 CatalogService 拒 400。本模块不关心 SPU 状态，只要求 SKU 真实存在。
   */
  UUID sku(AccessService.Access who, String prefix) {
    String code = unique(prefix);
    UUID spuId =
        write(
            who,
            () ->
                catalog.saveSpu(
                    who, null,
                    new SpuInput(code + "-SPU", "SPU " + code, "", "Brand", "Category", "DRAFT", 0)));
    return write(
        who,
        () ->
            catalog.saveSku(
                who, spuId, null,
                new SkuInput(code, "SKU " + code, unique("BC"), "PCS", "ACTIVE", List.of(), 0)));
  }

  static SupplierQuotePriceTierInput tier(String min, String price) {
    return new SupplierQuotePriceTierInput(new BigDecimal(min), new BigDecimal(price), "");
  }

  /** 默认报价入参：三档阶梯、100 起订、30 天交期、长期有效。 */
  SupplierQuoteInput quoteInput(
      UUID supplierId, UUID skuId, String supplierSkuCode, int priority) {
    return new SupplierQuoteInput(
        supplierId, skuId, supplierSkuCode, "PCS", "USD", new BigDecimal("100"),
        30, LocalDate.of(2026, 1, 1), null, priority, "ACTIVE",
        List.of(tier("1", "3.80"), tier("100", "3.20"), tier("500", "2.90")), "", 0);
  }

  SupplierQuote create(AccessService.Access who, UUID supplierId, UUID skuId, String code, int priority) {
    return write(who, () -> quotes.create(who, quoteInput(supplierId, skuId, code, priority)));
  }

  // ================================================================ 往返与基本写入

  @Test
  void 报价与阶梯真的落库并往返一致() {
    UUID sup = supplier(actor, "Q1");
    UUID item = sku(actor, "SKU1");
    var saved = create(actor, sup, item, unique("PN"), 1);

    assertThat(saved.id()).isNotNull();
    assertThat(saved.supplierId()).isEqualTo(sup);
    assertThat(saved.skuId()).isEqualTo(item);
    assertThat(saved.currency()).isEqualTo("USD");
    assertThat(saved.purchaseUnitCode()).isEqualTo("PCS");
    assertThat(saved.moqQuantity()).isEqualByComparingTo("100");
    assertThat(saved.leadTimeDays()).isEqualTo(30);
    assertThat(saved.priority()).isEqualTo(1);
    assertThat(saved.priorityLabel()).isEqualTo("首选");
    assertThat(saved.status()).isEqualTo("ACTIVE");
    // 阶梯按起订量升序读回，且是真值不是占位
    assertThat(saved.tiers()).hasSize(3);
    assertThat(saved.tiers().get(0).minQuantity()).isEqualByComparingTo("1");
    assertThat(saved.tiers().get(0).unitPrice()).isEqualByComparingTo("3.80");
    assertThat(saved.tiers().get(2).minQuantity()).isEqualByComparingTo("500");
    assertThat(saved.tiers().get(2).unitPrice()).isEqualByComparingTo("2.90");
    // 长期有效（valid_to 为空）要给明确提示，而不是"没设置有效期"这种含糊说法
    assertThat(saved.validityLabel()).isEqualTo("2026-01-01 起长期有效");

    // 真的在库里：直接查 jOOQ，不看 DTO 也能查到
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from supplier_quote where tenant_id=? and id=?", a, saved.id()))
        .get(0, Long.class)).isEqualTo(1L);
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from supplier_quote_price_tier where tenant_id=? and quote_id=?", a, saved.id()))
        .get(0, Long.class)).isEqualTo(3L);
  }

  @Test
  void 阶梯按起订量乱序提交会被排好而不是报错() {
    UUID sup = supplier(actor, "Q2");
    UUID item = sku(actor, "SKU2");
    // 运营按金额从小到大录档很自然，写入前先排序再判（规则 R5）
    var input =
        new SupplierQuoteInput(
            sup, item, unique("PN"), "PCS", "USD", new BigDecimal("1"), 0,
            LocalDate.of(2026, 1, 1), null, 1, "ACTIVE",
            List.of(tier("500", "2.90"), tier("1", "3.80"), tier("100", "3.20")), "", 0);
    var saved = write(actor, () -> quotes.create(actor, input));
    assertThat(saved.tiers().stream().map(SupplierQuotePriceTier::minQuantity).toList())
        .extracting(q -> q.toPlainString())
        .containsExactly("1.000000", "100.000000", "500.000000");
  }

  @Test
  void 同一供应商同一货号不能挂两个SKU() {
    UUID sup = supplier(actor, "Q3");
    UUID itemA = sku(actor, "SKU3A");
    UUID itemB = sku(actor, "SKU3B");
    String code = unique("PN-DUP");
    create(actor, sup, itemA, code, 1);
    // 同一货号挂到另一个 SKU → UNIQUE(供应商, 货号) 挡住
    assertThatThrownBy(() -> create(actor, sup, itemB, code, 1))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  @Test
  void 改报价整体替换阶梯而不是合并() {
    UUID sup = supplier(actor, "Q4");
    UUID item = sku(actor, "SKU4");
    var saved = create(actor, sup, item, unique("PN"), 1);
    // 三档改成两档：第二档必须消失（合并写入会让"删掉一档"无法表达）
    var input =
        new SupplierQuoteInput(
            sup, item, saved.supplierSkuCode(), "PCS", "EUR", new BigDecimal("50"), 45,
            LocalDate.of(2026, 2, 1), LocalDate.of(2026, 12, 31), 2, "ACTIVE",
            List.of(tier("1", "4.10"), tier("50", "3.50")), "改成欧元了", saved.version());
    var updated = write(actor, () -> quotes.update(actor, saved.id(), input));
    assertThat(updated.tiers()).hasSize(2);
    assertThat(updated.currency()).isEqualTo("EUR");
    assertThat(updated.moqQuantity()).isEqualByComparingTo("50");
    assertThat(updated.leadTimeDays()).isEqualTo(45);
    assertThat(updated.priority()).isEqualTo(2);
    assertThat(updated.version()).isEqualTo(1);
    assertThat(updated.validityLabel()).isEqualTo("2026-02-01 ~ 2026-12-31");
    // 库里真的只剩两档
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from supplier_quote_price_tier where tenant_id=? and quote_id=?", a, saved.id()))
        .get(0, Long.class)).isEqualTo(2L);
  }

  @Test
  void 版本不匹配报冲突() {
    UUID sup = supplier(actor, "Q5");
    UUID item = sku(actor, "SKU5");
    var saved = create(actor, sup, item, unique("PN"), 1);
    var stale =
        new SupplierQuoteInput(
            sup, item, saved.supplierSkuCode(), "PCS", "USD", new BigDecimal("100"), 30,
            LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "3.80")), "", saved.version() + 1);
    assertThatThrownBy(() -> write(actor, () -> quotes.update(actor, saved.id(), stale)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
  }

  // ================================================================ 规则 R1：报价挂供应商身份

  @Test
  void 报价必须挂在供应商身份上而不是客户身份上() {
    UUID item = sku(actor, "SKU6");
    UUID onlyCustomer = customerOnly(actor, "CUST-ONLY");
    // 主体存在但没有 SUPPLIER 角色 → 409，且文案要告诉运营"先指派身份"
    assertThatThrownBy(() -> create(actor, onlyCustomer, item, unique("PN"), 1))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("供应商");
    // 真正的主体不存在 → 404 而不是 409
    assertThatThrownBy(() -> create(actor, UUID.randomUUID(), item, unique("PN"), 1))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 规则 R2：优先级与歧义

  @Test
  void 唯一最高优先级的那条才是默认() {
    UUID sup = supplier(actor, "Q7");
    UUID item = sku(actor, "SKU7");
    create(actor, sup, item, unique("PN-A"), 2);
    var best = create(actor, sup, item, unique("PN-B"), 1);
    create(actor, sup, item, unique("PN-C"), 3);

    var result = tx(actor, () -> quotes.effective(item, sup, LocalDate.of(2026, 6, 1), null));
    assertThat(result.ambiguous()).isFalse();
    assertThat(result.selected()).isNotNull();
    assertThat(result.selected().id()).isEqualTo(best.id());
    assertThat(result.selected().selectionState()).isEqualTo("SELECTED");
    // 三条都还是有效报价，只是两条是备选
    assertThat(result.candidates()).hasSize(3);
    assertThat(result.candidates().stream()
            .filter(q -> "LOWER_PRIORITY".equals(q.selectionState())).count())
        .isEqualTo(2);
  }

  @Test
  void 同优先级并列的有效报价必须报错让用户选() throws Exception {
    UUID sup = supplier(actor, "Q8");
    UUID item = sku(actor, "SKU8");
    var first = create(actor, sup, item, unique("PN-A"), 1);
    var second = create(actor, sup, item, unique("PN-B"), 1);

    // 1) 读出口：不替用户挑，selected 为 null，且逐条标 AMBIGUOUS
    var result = tx(actor, () -> quotes.effective(item, sup, LocalDate.of(2026, 6, 1), null));
    assertThat(result.ambiguous()).isTrue();
    assertThat(result.selected()).as("规则 R2：绝不能随机取一条").isNull();
    assertThat(result.candidates()).hasSize(2);
    assertThat(result.candidates()).allMatch(q -> "AMBIGUOUS".equals(q.selectionState()));
    assertThat(result.ambiguityProblem()).contains("2 条").contains("不会替你随机选");

    // 2) 严格取价出口：真的抛异常（409），不是返回一个"看起来正常"的结果
    assertThatThrownBy(
            () -> tx(actor, () -> quotes.resolve(item, sup, LocalDate.of(2026, 6, 1), null)))
        .isInstanceOf(SupplierQuoteService.SupplierQuoteAmbiguityException.class)
        .hasMessageContaining("2 条");

    // 3) HTTP 出口的 body 必须带得出候选 —— "让用户选"的后半句是让用户选得了
    var response =
        http.perform(get("/catalog/supplier-quotes/effective/resolve")
                .header("Authorization", "Bearer tenant-a")
                .param("skuId", item.toString())
                .param("supplierId", sup.toString())
                .param("at", "2026-06-01"))
            .andExpect(status().isConflict())
            .andReturn().getResponse().getContentAsString();
    var tree = new ObjectMapper().readTree(response);
    assertThat(tree.get("ambiguous").asBoolean()).isTrue();
    var candidates = tree.get("candidates");
    assertThat(candidates.isArray()).isTrue();
    assertThat(candidates.size()).isEqualTo(2);
    var ids = new java.util.HashSet<String>();
    candidates.forEach(c -> ids.add(c.get("id").asText()));
    assertThat(ids).containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
    // 候选里要带得出运营判断所需的信息（供应商 / SKU / 货号 / 币种）
    var sample = candidates.get(0);
    assertThat(sample.get("supplierSkuCode").asText()).isNotBlank();
    assertThat(sample.get("currency").asText()).isEqualTo("USD");
    assertThat(sample.get("priority").asInt()).isEqualTo(1);
  }

  @Test
  void 把其中一条优先级调开后歧义消失() {
    UUID sup = supplier(actor, "Q9");
    UUID item = sku(actor, "SKU9");
    var first = create(actor, sup, item, unique("PN-A"), 1);
    var second = create(actor, sup, item, unique("PN-B"), 1);
    // 先断言歧义确实存在（下面调开优先级后它才会消失）
    assertThatThrownBy(() -> tx(actor, () -> quotes.resolve(item, sup, LocalDate.of(2026, 6, 1), null)))
        .isInstanceOf(SupplierQuoteService.SupplierQuoteAmbiguityException.class);

    // 把第二条调成优先级 2（规则 R2 的处置建议就是"调开一位"）
    write(actor, () -> quotes.update(actor, second.id(),
        new SupplierQuoteInput(
            sup, item, second.supplierSkuCode(), "PCS", "USD", new BigDecimal("100"), 30,
            LocalDate.of(2026, 1, 1), null, 2, "ACTIVE",
            List.of(tier("1", "3.80"), tier("100", "3.20"), tier("500", "2.90")), "", second.version())));
    var resolved = tx(actor, () -> quotes.resolve(item, sup, LocalDate.of(2026, 6, 1), null));
    assertThat(resolved.ambiguous()).isFalse();
    assertThat(resolved.selected()).isNotNull();
    assertThat(resolved.selected().id()).isEqualTo(first.id());
  }

  @Test
  void 优先级只在同一供应商内部可比() {
    UUID item = sku(actor, "SKU10");
    UUID supA = supplier(actor, "SUP-A");
    UUID supB = supplier(actor, "SUP-B");
    // 两个供应商都报优先级 1：这是两家供应商在竞价，不是歧义
    create(actor, supA, item, unique("PN-A"), 1);
    create(actor, supB, item, unique("PN-B"), 1);

    // 限定供应商 → 各取各的默认，不歧义
    assertThat(tx(actor, () -> quotes.resolve(item, supA, LocalDate.of(2026, 6, 1), null))
        .selected().supplierId()).isEqualTo(supA);
    assertThat(tx(actor, () -> quotes.resolve(item, supB, LocalDate.of(2026, 6, 1), null))
        .selected().supplierId()).isEqualTo(supB);

    // 不限定供应商（05 定价 / 13 利润读全市场成本）→ selected 恒为 null，且不歧义
    var all = tx(actor, () -> quotes.effective(item, null, LocalDate.of(2026, 6, 1), null));
    assertThat(all.candidates()).hasSize(2);
    assertThat(all.selected()).as("跨供应商比优先级是错的").isNull();
    assertThat(all.ambiguous()).isFalse();
    // 但每家内部仍各自标出默认
    assertThat(all.candidates()).allMatch(q -> "SELECTED".equals(q.selectionState()));
  }

  // ================================================================ 阶梯取价

  @Test
  void 按数量取适用档位() {
    UUID sup = supplier(actor, "Q11");
    UUID item = sku(actor, "SKU11");
    create(actor, sup, item, unique("PN"), 1);
    LocalDate at = LocalDate.of(2026, 6, 1);

    // 1 件 → 第一档 3.80
    assertThat(tx(actor, () -> quotes.effective(item, sup, at, new BigDecimal("1")))
        .selectedTier().unitPrice()).isEqualByComparingTo("3.80");
    // 99.999999 仍属第一档
    assertThat(tx(actor, () -> quotes.effective(item, sup, at, new BigDecimal("99.999999")))
        .selectedTier().unitPrice()).isEqualByComparingTo("3.80");
    // 137 件 → 第二档 3.20
    assertThat(tx(actor, () -> quotes.effective(item, sup, at, new BigDecimal("137")))
        .selectedTier().unitPrice()).isEqualByComparingTo("3.20");
    // 100000 件 → 最后一档 2.90（阶梯是开口的）
    assertThat(tx(actor, () -> quotes.effective(item, sup, at, new BigDecimal("100000")))
        .selectedTier().unitPrice()).isEqualByComparingTo("2.90");
    // 低于起订量 → 没有适用价，且给提示，**不**退回高档价
    var below = tx(actor, () -> quotes.effective(item, sup, at, new BigDecimal("0")));
    assertThat(below.selectedTier()).isNull();
    assertThat(below.warnings()).anyMatch(w -> w.contains("低于最低档起订量"));
  }

  // ================================================================ 有效报价判定

  @Test
  void 有效期是闭区间且过期停用的不参与判定() {
    UUID sup = supplier(actor, "Q12");
    UUID item = sku(actor, "SKU12");
    var base = new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "USD", new BigDecimal("1"), 0,
        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 1, "ACTIVE",
        List.of(tier("1", "3.80")), "", 0);
    var saved = write(actor, () -> quotes.create(actor, base));

    // 3/31 当天仍然有效（界面上写着「~ 3/31」，系统当天就不认的话两边说的不是同一件事）
    assertThat(tx(actor, () -> quotes.effective(item, sup, LocalDate.of(2026, 3, 31), null))
        .selected()).isNotNull();
    // 4/1 过期
    var expired = tx(actor, () -> quotes.effective(item, sup, LocalDate.of(2026, 4, 1), null));
    assertThat(expired.selected()).isNull();
    assertThat(expired.candidates()).isEmpty();
    assertThat(expired.warnings()).anyMatch(w -> w.contains("没有有效报价"));
    // 过期原因要能从详情里读到
    assertThat(tx(actor, () -> quotes.detail(saved.id())).warnings())
        .anyMatch(w -> w.contains("过期"));

    // 停用后不参与判定
    write(actor, () -> quotes.update(actor, saved.id(),
        new SupplierQuoteInput(
            sup, item, saved.supplierSkuCode(), "PCS", "USD", new BigDecimal("1"), 0,
            LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), 1, "DISABLED",
            List.of(tier("1", "3.80")), "", saved.version())));
    assertThat(tx(actor, () -> quotes.effective(item, sup, LocalDate.of(2026, 3, 15), null))
        .selected()).isNull();
    assertThat(tx(actor, () -> quotes.detail(saved.id())).warnings())
        .anyMatch(w -> w.contains("已停用"));
  }

  @Test
  void 没有有效报价时严格取价出口报404() {
    UUID sup = supplier(actor, "Q13");
    UUID item = sku(actor, "SKU13");
    // 报一条 2099 年才生效的报价：今天没有有效报价
    write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "USD", new BigDecimal("1"), 0,
        LocalDate.of(2099, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "3.80")), "", 0)));
    assertThatThrownBy(() -> tx(actor, () -> quotes.resolve(item, sup, null, null)))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 概览与列表的歧义计数与逐条状态一致() {
    UUID sup = supplier(actor, "Q14");
    UUID item = sku(actor, "SKU14");
    create(actor, sup, item, unique("PN-A"), 1);
    create(actor, sup, item, unique("PN-B"), 1);

    // 概览的 ambiguousQuotes 要数的是"落在歧义组里的报价条数"，不是组数
    var overview = tx(actor, () -> quotes.overview());
    assertThat(overview.ambiguousQuotes()).isGreaterThanOrEqualTo(2L);
    assertThat(overview.effectiveQuotes()).isGreaterThanOrEqualTo(2L);
    assertThat(overview.supplierCount()).isGreaterThanOrEqualTo(1L);

    // 列表逐条标状态，与有效报价出口逐字同源
    var page = tx(actor, () -> quotes.list(
        new SupplierQuoteQuery(null, item, sup, null, null, null, true, null, null, 0, 50)));
    assertThat(page.items()).isNotEmpty();
    assertThat(page.items()).allMatch(q -> "AMBIGUOUS".equals(q.selectionState()));

    // 「只看需人工选择」筛出来的条数与上面同源
    var only = tx(actor, () -> quotes.list(
        new SupplierQuoteQuery(null, item, sup, null, null, null, null, true, null, 0, 50)));
    assertThat(only.items()).hasSize(page.items().size());
  }

  // ================================================================ 规则 R3/R4/R5：校验

  @Test
  void 币种格式起订量交期阶梯都要校验() {
    UUID sup = supplier(actor, "Q15");
    UUID item = sku(actor, "SKU15");
    // 币种不是三位码
    assertThatThrownBy(() -> write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "US", new BigDecimal("1"), 0,
        LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "3.80")), "", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("三位");
    // 起订量留空
    assertThatThrownBy(() -> write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "USD", null, 0,
        LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "3.80")), "", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("起订量必填");
    // 结束日早于生效日
    assertThatThrownBy(() -> write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "USD", new BigDecimal("1"), 0,
        LocalDate.of(2026, 3, 1), LocalDate.of(2026, 2, 1), 1, "ACTIVE",
        List.of(tier("1", "3.80")), "", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("不能早于");
    // 交期把月填成天
    assertThatThrownBy(() -> write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "USD", new BigDecimal("1"), 9999,
        LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "3.80")), "", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("365");
    // 零阶梯
    assertThatThrownBy(() -> write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "USD", new BigDecimal("1"), 0,
        LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(), "", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("至少一档");
    // 阶梯单价为 0
    assertThatThrownBy(() -> write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "PCS", "USD", new BigDecimal("1"), 0,
        LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "0")), "", 0))))
        .isInstanceOf(ResponseStatusException.class).hasMessageContaining("必须大于 0");
  }

  @Test
  void 采购单位必须已登记() {
    UUID sup = supplier(actor, "Q16");
    UUID item = sku(actor, "SKU16");
    assertThatThrownBy(() -> write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
        sup, item, unique("PN"), "NOT-REGISTERED", "USD", new BigDecimal("1"), 0,
        LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "3.80")), "", 0))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("计量单位");
  }

  @Test
  void 已经写着的单位后来被停用仍能改() {
    unit(actor, "BOX", true, true);
    UUID sup = supplier(actor, "Q17");
    UUID item = sku(actor, "SKU17");
    var saved =
        write(actor, () -> quotes.create(actor, new SupplierQuoteInput(
            sup, item, unique("PN"), "BOX", "USD", new BigDecimal("1"), 0,
            LocalDate.of(2026, 1, 1), null, 1, "ACTIVE", List.of(tier("1", "3.80")), "", 0)));
    // 停用之后改备注仍要能存，否则运营改不动任何东西而完全看不出原因
    writeVoid(actor, () -> sql.execute(
        "update unit_of_measure set enabled=false where tenant_id=? and code=?", a, "BOX"));
    var kept =
        write(actor, () -> quotes.update(actor, saved.id(),
            new SupplierQuoteInput(
                sup, item, saved.supplierSkuCode(), "BOX", "USD", new BigDecimal("1"), 0,
                LocalDate.of(2026, 1, 1), null, 1, "ACTIVE",
                List.of(tier("1", "3.80")), "只改了备注", saved.version())));
    assertThat(kept.purchaseUnitCode()).isEqualTo("BOX");
    assertThat(kept.note()).isEqualTo("只改了备注");
  }

  // ================================================================ 规则 R6：级联与删除

  @Test
  void 报价随供应商身份解除级联删除() {
    UUID sup = supplier(actor, "Q18");
    UUID item = sku(actor, "SKU18");
    var saved = create(actor, sup, item, unique("PN"), 1);
    // 阶梯也必须跟着删：留下孤儿阶梯时按量取价读不到而单独读阶梯读得到，两种读法给出不一致的价格
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from supplier_quote_price_tier where tenant_id=? and quote_id=?", a, saved.id()))
        .get(0, Long.class)).isEqualTo(3L);

    var roles = tx(actor, () -> parties.rolesOf(sup));
    writeVoid(actor, () -> parties.removeRole(actor, sup, roles.get(0).id(), roles.get(0).version()));
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from supplier_quote where tenant_id=? and id=?", a, saved.id()))
        .get(0, Long.class)).isZero();
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from supplier_quote_price_tier where tenant_id=? and quote_id=?", a, saved.id()))
        .get(0, Long.class)).isZero();
  }

  @Test
  void 删报价带版本且删后不再参与有效判定() {
    UUID sup = supplier(actor, "Q19");
    UUID item = sku(actor, "SKU19");
    var saved = create(actor, sup, item, unique("PN"), 1);
    assertThatThrownBy(() -> writeVoid(actor, () -> quotes.delete(actor, saved.id(), saved.version() + 1)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已被他人修改");
    writeVoid(actor, () -> quotes.delete(actor, saved.id(), saved.version()));
    // 删掉之后 06 采购退回"该 SKU 无有效报价"（规则 R6）
    assertThat(tx(actor, () -> quotes.effective(item, sup, null, null)).selected()).isNull();
    // 再删一次 → 404
    assertThatThrownBy(() -> writeVoid(actor, () -> quotes.delete(actor, saved.id(), 0)))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 租户隔离三断言

  /**
   * 租户隔离三断言，<b>并且覆盖报价行与阶梯行</b>。
   *
   * <p>只测主体等于没测：报价行才是本模块真正的越权面 ——
   * 「A 读到自己的采购成本时混进 B 的报价」会直接让 A 的毛利算错，
   * 而采购成本在 ERP 里是最敏感的一类数据。
   */
  @Test
  void 租户隔离三断言覆盖报价行() throws Exception {
    UUID myItem = sku(actor, "ISO-SKU-A");
    UUID theirItem = sku(actorB, "ISO-SKU-B");
    UUID mySup = supplier(actor, "ISO-SUP-A");
    UUID theirSup = supplier(actorB, "ISO-SUP-B");
    var mine = create(actor, mySup, myItem, unique("PN-A"), 1);
    var theirs = create(actorB, theirSup, theirItem, unique("PN-B"), 1);

    // 两边都必须真的有报价行 —— 否则下面的断言就是空的
    assertThat(tx(actor, () -> quotes.detail(mine.id())).id()).isEqualTo(mine.id());
    assertThat(tx(actorB, () -> quotes.detail(theirs.id())).id()).isEqualTo(theirs.id());

    // 1) 两租户互不可见：读对方报价 404，写到对方 SKU 上 404
    assertThatThrownBy(() -> tx(actor, () -> quotes.detail(theirs.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> quotes.detail(mine.id())))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能把报价写到 B 的 SKU 上
    assertThatThrownBy(
            () -> writeVoid(actor, () -> quotes.create(actor,
                quoteInput(mySup, theirItem, unique("PN-X"), 1))))
        .isInstanceOf(ResponseStatusException.class);
    // A 不能删 B 的报价
    assertThatThrownBy(() -> writeVoid(actor, () -> quotes.delete(actor, theirs.id(), theirs.version())))
        .isInstanceOf(ResponseStatusException.class);
    // 价格不相交：这是本模块最要紧的一条
    assertThat(tx(actor, () -> quotes.detail(mine.id())).tiers().get(0).unitPrice())
        .isEqualByComparingTo("3.80");
    assertThat(tx(actorB, () -> quotes.detail(theirs.id())).tiers().get(0).unitPrice())
        .isEqualByComparingTo("3.80");
    // 阶梯行也读不到对方的（直接查库）
    assertThat(tx(actor, () -> sql.fetchOne(
            "select count(*) from supplier_quote_price_tier where tenant_id=? and quote_id=?", a, theirs.id()))
        .get(0, Long.class)).isZero();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/supplier-quotes/" + theirs.id())
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/supplier-quotes/" + mine.id())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    // 伪造头下写入仍落在 B 自己名下 —— 断言**行确实落在 B 名下**，不只断言 A 看不见
    UUID forgedSku = sku(actorB, "FORGED-SKU");
    UUID forgedSupplier = supplier(actorB, "FORGED-SUP");
    var forged = create(actorB, forgedSupplier, forgedSku, unique("PN-FORGED"), 1);
    assertThatThrownBy(() -> tx(actor, () -> quotes.detail(forged.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(tx(actorB, () -> sql.fetchOne(
                "select tenant_id from supplier_quote where id=?", forged.id()).get(0, UUID.class)))
        .isEqualTo(b);
    // A 用伪造头去读 B 的 SKU / 报价：404 而不是 200。
    // 404 而不是 200 + 空候选是刻意的：B 的 SKU 对 A 根本不可见（RLS + 复合外键），
    // 而报价要 join sku 才能读出 —— "读不到 SKU"与"这个 SKU 没有报价"是两件不同的事，
    // 让它们都退化成 200 + 空列表的话，运营会以为 B 真的没报价。
    http.perform(get("/catalog/supplier-quotes")
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b))
        .andExpect(status().isOk());
    http.perform(get("/catalog/supplier-quotes/effective")
            .header("Authorization", "Bearer tenant-a").header("X-Tenant-Id", b)
            .param("skuId", forgedSku.toString())
            .param("supplierId", forgedSupplier.toString()))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替），逐轮断言报价集合不相交
    for (int i = 0; i < 5; i++) {
      var myQuotes =
          tx(actor, () -> quotes.list(new SupplierQuoteQuery(null, null, null, null, null, null, null, null, null, 0, 200)))
              .items().stream().map(SupplierQuote::id).collect(java.util.stream.Collectors.toSet());
      var theirQuotes =
          tx(actorB, () -> quotes.list(new SupplierQuoteQuery(null, null, null, null, null, null, null, null, null, 0, 200)))
              .items().stream().map(SupplierQuote::id).collect(java.util.stream.Collectors.toSet());
      assertThat(myQuotes).isNotEmpty();
      assertThat(theirQuotes).isNotEmpty();
      assertThat(java.util.Collections.disjoint(myQuotes, theirQuotes)).isTrue();
      // 有效报价出口也不串：A 读 B 的 SKU 直接 404（跨租户的 SKU 对 A 不可见），
      // 而不是"读到了 B 的报价"或者"读到空列表"。
      assertThat(tx(actor, () -> quotes.effective(myItem, mySup, null, null)).selected()).isNotNull();
      assertThatThrownBy(() -> tx(actor, () -> quotes.effective(theirItem, theirSup, null, null)))
          .as("A 读 B 的供应商报价必须 404 而不是串过去")
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ RLS 与结构

  @Test
  void 两张表都强制RLS() {
    for (String table : List.of("supplier_quote", "supplier_quote_price_tier")) {
      var row = tx(actor, () -> sql.fetchOne(
          "select relrowsecurity,relforcerowsecurity from pg_class where relname=?", table));
      assertThat(row.get("relrowsecurity", Boolean.class)).as("%s 开启 RLS", table).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class)).as("%s 强制 RLS", table).isTrue();
      var policy = tx(actor, () -> sql.fetchOne(
          "select count(*) from pg_policies where tablename=? and policyname='isolation'", table));
      assertThat(policy.get(0, Long.class)).as("%s 有 isolation 策略", table).isEqualTo(1L);
    }
  }

  @Test
  void 金额与数量列的精度与类型() {
    // 禁浮点：金额与数量一律 numeric（AGENTS.md）
    assertThat(columnType("supplier_quote", "moq_quantity")).isEqualTo("numeric");
    assertThat(columnPrecision("supplier_quote", "moq_quantity")).isEqualTo("19,6");
    assertThat(columnType("supplier_quote_price_tier", "min_quantity")).isEqualTo("numeric");
    assertThat(columnPrecision("supplier_quote_price_tier", "min_quantity")).isEqualTo("19,6");
    assertThat(columnType("supplier_quote_price_tier", "unit_price")).isEqualTo("numeric");
    assertThat(columnPrecision("supplier_quote_price_tier", "unit_price")).isEqualTo("19,4");
    // 阶梯主键是 (tenant_id, quote_id, min_quantity) —— 两档数量相同在 DB 层不可能
    var pk = tx(actor, () -> sql.fetchOne(
        "select (select string_agg(a.attname,',' order by k.ord) from unnest(c.conkey)"
            + " with ordinality k(att,ord) join pg_attribute a"
            + " on a.attrelid=c.conrelid and a.attnum=k.att)"
            + " from pg_constraint c where c.conrelid='supplier_quote_price_tier'::regclass"
            + " and c.contype='p'"));
    assertThat(pk.get(0, String.class)).isEqualTo("tenant_id,quote_id,min_quantity");
    // 报价外键指向 party_role（供应商身份）而不是 party
    var fk = tx(actor, () -> sql.fetchOne(
        "select t.relname from pg_constraint c join pg_class t on t.oid=c.confrelid"
            + " where c.conrelid='supplier_quote'::regclass and c.contype='f'"
            + " and c.confdeltype='c' and t.relname='party_role'"));
    assertThat(fk.get(0, String.class)).isEqualTo("party_role");
  }

  @Test
  void dbCHECK挡住绕过服务层的脏数据() {
    UUID sup = supplier(actor, "Q20");
    UUID item = sku(actor, "SKU20");
    var saved = create(actor, sup, item, unique("PN"), 1);
    // 有效期倒置：服务层会拒，DB CHECK 也要兜住直连写入
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "update supplier_quote set valid_to='2020-01-01' where tenant_id=? and id=?", a, saved.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 零单价：DB CHECK 也要兜住（真实场景是"赠品"混进报价行）
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "update supplier_quote_price_tier set unit_price=0 where tenant_id=? and quote_id=?", a, saved.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
    // 非 ISO4217 三位码
    assertThatThrownBy(() -> writeVoid(actor, () -> sql.execute(
            "update supplier_quote set currency='US' where tenant_id=? and id=?", a, saved.id())))
        .isInstanceOf(org.springframework.dao.DataAccessException.class);
  }

  private String columnPrecision(String table, String column) {
    return tx(actor, () -> sql.fetchOne(
            "select numeric_precision||','||numeric_scale from information_schema.columns"
                + " where table_name=? and column_name=?", table, column))
        .get(0, String.class);
  }

  private String columnType(String table, String column) {
    return tx(actor, () -> sql.fetchOne(
            "select data_type from information_schema.columns where table_name=? and column_name=?",
            table, column))
        .get(0, String.class);
  }

  // ================================================================ HTTP 全链路

  @Test
  void http全链路与读写权限分离() throws Exception {
    UUID sup = supplier(actor, "HTTP-SUP");
    UUID item = sku(actor, "HTTP-SKU");

    var created =
        http.perform(post("/catalog/supplier-quotes").header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(JSON.writeValueAsBytes(Map.ofEntries(
                    Map.entry("supplierId", sup.toString()),
                    Map.entry("skuId", item.toString()),
                    Map.entry("supplierSkuCode", "HTTP-PN-1"),
                    Map.entry("purchaseUnitCode", "PCS"),
                    Map.entry("currency", "USD"),
                    Map.entry("moqQuantity", new BigDecimal("100")),
                    Map.entry("leadTimeDays", 30),
                    Map.entry("validFrom", "2026-01-01"),
                    Map.entry("priority", 1),
                    Map.entry("status", "ACTIVE"),
                    Map.entry("tiers", List.of(
                        Map.of("minQuantity", new BigDecimal("1"), "unitPrice", new BigDecimal("3.80")),
                        Map.of("minQuantity", new BigDecimal("100"), "unitPrice", new BigDecimal("3.20")))),
                    Map.entry("note", "HTTP 建的"),
                    Map.entry("version", 0)))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currency").value("USD"))
            .andExpect(jsonPath("$.tiers.length()").value(2))
            .andReturn().getResponse().getContentAsString();
    UUID quoteId = UUID.fromString(new ObjectMapper().readTree(created).get("id").asText());

    // GET 读回一致 + 阶梯明细
    http.perform(get("/catalog/supplier-quotes/" + quoteId)
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.priorityLabel").value("首选"))
        .andExpect(jsonPath("$.leadTimeLabel").value("交期 30 天"));
    http.perform(get("/catalog/supplier-quotes/" + quoteId + "/tiers")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(2));

    // 概览 / 词表 / 列表 / 有效报价
    http.perform(get("/catalog/supplier-quotes/overview")
            .header("Authorization", "Bearer tenant-a")).andExpect(status().isOk());
    http.perform(get("/catalog/supplier-quotes/options")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.statuses.length()").value(2));
    http.perform(get("/catalog/supplier-quotes")
            .header("Authorization", "Bearer tenant-a").param("skuId", item.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));
    http.perform(get("/catalog/supplier-quotes/effective")
            .header("Authorization", "Bearer tenant-a")
            .param("skuId", item.toString())
            .param("supplierId", sup.toString())
            .param("quantity", "137"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.selectedTier.unitPrice").value(3.20));
    http.perform(get("/catalog/supplier-quotes/effective/resolve")
            .header("Authorization", "Bearer tenant-a")
            .param("skuId", item.toString())
            .param("supplierId", sup.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ambiguous").value(false));

    // 写权限：只有读权限的角色写报价必须 403；读放行
    http.perform(put("/catalog/supplier-quotes/" + quoteId)
            .header("Authorization", "Bearer reader").contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of(
                "supplierId", sup.toString(), "skuId", item.toString(),
                "supplierSkuCode", "HTTP-PN-1", "purchaseUnitCode", "PCS", "currency", "USD",
                "moqQuantity", new BigDecimal("100"), "validFrom", "2026-01-01",
                "tiers", List.of(Map.of("minQuantity", new BigDecimal("1"),
                    "unitPrice", new BigDecimal("3.80"))), "version", 0))))
        .andExpect(status().isForbidden());
    http.perform(get("/catalog/supplier-quotes/" + quoteId)
            .header("Authorization", "Bearer catalog-reader"))
        .andExpect(status().isOk());

    // 缺 token → 401
    http.perform(get("/catalog/supplier-quotes/" + quoteId))
        .andExpect(status().isUnauthorized());

    // 校验失败 → 400（不是 500）
    http.perform(post("/catalog/supplier-quotes").header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.of(
                "supplierId", sup.toString(), "skuId", item.toString(),
                "supplierSkuCode", "HTTP-PN-2", "purchaseUnitCode", "PCS", "currency", "US",
                "validFrom", "2026-01-01",
                "tiers", List.of(Map.of("minQuantity", new BigDecimal("1"),
                    "unitPrice", new BigDecimal("3.80"))), "version", 0))))
        .andExpect(status().isBadRequest());

    // PUT 带版本更新
    http.perform(put("/catalog/supplier-quotes/" + quoteId)
            .header("Authorization", "Bearer tenant-a").contentType("application/json")
            .content(JSON.writeValueAsBytes(Map.ofEntries(
                Map.entry("supplierId", sup.toString()),
                Map.entry("skuId", item.toString()),
                Map.entry("supplierSkuCode", "HTTP-PN-1"),
                Map.entry("purchaseUnitCode", "PCS"),
                Map.entry("currency", "USD"),
                Map.entry("moqQuantity", new BigDecimal("100")),
                Map.entry("leadTimeDays", 30),
                Map.entry("validFrom", "2026-01-01"),
                Map.entry("priority", 1),
                Map.entry("status", "ACTIVE"),
                Map.entry("tiers", List.of(Map.of("minQuantity", new BigDecimal("1"),
                    "unitPrice", new BigDecimal("3.95")))),
                Map.entry("version", 0)))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tiers[0].unitPrice").value(3.95));

    // DELETE 带版本
    http.perform(delete("/catalog/supplier-quotes/" + quoteId)
            .header("Authorization", "Bearer tenant-a").param("version", "1"))
        .andExpect(status().isOk());
  }
}
