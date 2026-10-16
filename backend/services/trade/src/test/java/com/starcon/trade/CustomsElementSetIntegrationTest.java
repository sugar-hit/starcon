package com.starcon.trade;

import static com.starcon.trade.CustomsElementSetModels.*;
import static org.assertj.core.api.Assertions.*;
// 显式单条静态导入压过 AssertJ 的通配导入：assertThrows 在 JUnit 与 AssertJ 里各有一份
// 签名不同的重载，通配导入 + 值兼容 lambda 会二义。仓库既有测试也走这条路
// （SalesOrderOverviewIntegrationTest 用的就是 JUnit 那份）。
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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
 * 10.5.1.2 申报要素。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接：
 * 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>catalog 侧用进程内 HTTP 假服务</b>（不是内存替身数据库）：本模块对 catalog 的
 * 唯一依赖是那个<b>授权只读 HTTP 端点</b>，而跨服务边界本来就只能在 HTTP 上发生
 * （AGENTS.md 禁止跨服务 SQL / FK / 视图）。所以用假服务是<b>忠实还原</b>边界形状，
 * 而不是绕过它 —— 真去连 catalog 的库才是违规。
 *
 * <p><b>本测试要钉住的九条</b>：
 *
 * <ol>
 *   <li><b>申报品名不能照搬销售标题</b>（蓝图加粗句）：服务层拒绝、DB CHECK 与触发器
 *       再各挡一道，且<b>大小写与首尾空白不同也算照搬</b>；</li>
 *   <li><b>缺失项提示逐项点名</b>，且与发布闸<b>同一口径</b>（清单与详情给同一个数）；</li>
 *   <li><b>发布闸三道</b>：缺要素不可发布、上游产地不一致不可发布、齐备才可发布；</li>
 *   <li><b>已发布不可原地改正文</b>（绕过服务层直改 SQL 也被 DB 挡下），只能新建版本；</li>
 *   <li><b>生效区间用固定 OffsetDateTime 钉死</b>：断言写在具体时刻上，
 *       <b>不依赖 {@code now()}</b> —— 否则用例只在某个时间段通过，是个按批次引爆的定时炸弹；</li>
 *   <li><b>timestamptz 往返零漂移</b>（写回的值逐项断言，不是只断言没抛异常）；</li>
 *   <li><b>币种枚举与 DB CHECK 逐项相等</b>（防「前端下拉能选、DB 拒收」这类静默漂移）；</li>
 *   <li><b>真分页 + 汇总不受分页影响</b>，筛选与排序白名单真实生效；</li>
 *   <li><b>多租户三断言</b>：两租户互不可见 / 伪造 tenant header 无效 / 连接池归还后不串租户。</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomsElementSetIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static final UUID membershipA = UUID.randomUUID(), membershipB = UUID.randomUUID();
  static HttpServer identity;
  static HttpServer catalog;
  // ⚠️ 必须 registerModule(JavaTimeModule)：ElementSetInput 里有 OffsetDateTime，
  //    裸 ObjectMapper 序列化 java.time 会抛 InvalidDefinitionException，
  //    那与被测代码无关，是测试夹具自己的问题。
  static final ObjectMapper json = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();

  /** catalog 假服务的可变状态：销售标题 / 归类口径产地 / 该目的地有没有版本。 */
  static volatile String catalogSalesTitle = "高颜值超薄移动电源 10000mAh 快充";
  static volatile String catalogOrigin = "CN";
  static volatile boolean catalogHasVersion = true;

  /** 固定时刻（+08:00）。生效区间断言全部钉在具体时刻上，不依赖 now()。 */
  static final OffsetDateTime T0 = OffsetDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
  static final OffsetDateTime T1 = OffsetDateTime.of(2026, 7, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
  static final OffsetDateTime T2 = OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));

  static final List<String> allPermissions =
      java.util.stream.Stream.of("fulfillment")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "trade." + p + "." + op))
          .toList();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID membership, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        membership,
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
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
        s.execute("create role trade_app login password 'test-only' nosuperuser nobypassrls");
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
            byte[] body = json.writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put(
          "Bearer tenant-a", actor(user, a, membershipA, null, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer tenant-b", actor(user, b, membershipB, null, List.of(grant("ALL", allPermissions))));
      // 一个没有任何 trade 权限的身份：用来钉 403。
      actors.put("Bearer tenant-a-noperm", actor(user, a, membershipA, null, List.of()));
      identity.start();

      // catalog 假服务：只实现本模块唯一用到的那一个授权只读端点。
      catalog = HttpServer.create(new InetSocketAddress(0), 0);
      catalog.createContext(
          "/catalog/customs-declaration/resolve",
          e -> {
            var auth = e.getRequestHeaders().getFirst("Authorization");
            if (!"Bearer tenant-a".equals(auth) && !"Bearer tenant-b".equals(auth)) {
              e.sendResponseHeaders(401, -1);
              e.close();
              return;
            }
            if (!catalogHasVersion) {
              e.sendResponseHeaders(404, -1);
              e.close();
              return;
            }
            var query = e.getRequestURI().getQuery();
            var sku = param(query, "skuId");
            var destination = param(query, "destination");
            // 用 LinkedHashMap 而不是 Map.of：Map.of 最多 10 对键值，
            // 而合成响应有 20 个字段（超限会在编译期报错，不会运行时才发现）。
            var payload = new java.util.LinkedHashMap<String, Object>();
            payload.put("skuId", sku);
            payload.put("skuCode", "SKU-" + sku.substring(0, 8));
            payload.put("skuName", catalogSalesTitle);
            payload.put("destinationCountryCode", destination);
            payload.put("destinationVersionRef", UUID.randomUUID().toString());
            payload.put("destinationVersionNo", 1);
            payload.put("declaredName", "移动电源 锂离子电池 10000mAh");
            payload.put("declaredNameSource", "MODULE_3_7_1_2");
            payload.put("hsCode", "8507600090");
            payload.put("originCountryCode", catalogOrigin);
            payload.put("statutoryUnitCode", "PCE");
            payload.put("preferentialOriginEligible", true);
            payload.put("preferentialApplicable", true);
            payload.put("preferentialGranted", true);
            payload.put("preferenceAgreement", "RCEP");
            payload.put("declarationElementStatus", "COMPLETE");
            payload.put(
                "declarationElements",
                Map.of(
                    "entries",
                    List.of(
                        Map.of(
                            "ordinal", 1,
                            "elementCode", "01",
                            "elementName", "品名",
                            "elementValue", "移动电源",
                            "required", true)),
                    "byCode", Map.of("01", "移动电源")));
            byte[] body = json.writeValueAsBytes(payload);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      catalog.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static String param(String query, String key) {
    if (query == null) return "";
    for (var part : query.split("&")) {
      var kv = part.split("=", 2);
      if (kv.length == 2 && kv[0].equals(key)) return kv[1];
    }
    return "";
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "trade_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("CATALOG_URL", () -> "http://localhost:" + catalog.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明归还后不会串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired CustomsElementSetService service;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    catalog.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void bindActors() throws Exception {
    // 先清空本模块两张表（照 BomBindingIntegrationTest 的同一约定：走迁移超级账号 truncate，
    // 业务角色 trade_app 在 FORCE RLS 下连自己都看不见，更别说 truncate）。
    // **必须清**：JUnit 同一 JVM 内方法顺序不定，而汇总/分页那几条断言计的是**行数** ——
    // 不清的话「发往 DE 的元素此刻生效几版」会数进别的用例建的行，
    // 断言时绿时红，且红的时候指不出是哪一条用例的数据。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute("truncate customs_element_set_item, customs_element_set cascade");
    }
    catalogSalesTitle = "高颜值超薄移动电源 10000mAh 快充";
    catalogOrigin = "CN";
    catalogHasVersion = true;
    actorA = access.require("Bearer tenant-a", "trade.fulfillment.read");
    actorB = access.require("Bearer tenant-b", "trade.fulfillment.read");
  }

  // ==========================================================================
  // 1. 申报品名不能照搬销售标题（蓝图加粗句）
  // ==========================================================================

  @Test
  void 申报品名照搬销售标题被服务层拒绝() {
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () ->
                    inA(
                        () ->
                            service.create(
                                baseInput("高颜值超薄移动电源 10000mAh 快充").build(),
                                "Bearer tenant-a")))
            .getReason();
    assertThat(reason).as("错误文案要说清是清关口径与转化文案的用途之别")
        .contains("与销售标题完全相同")
        .contains("混用是常见被退单原因");
  }

  @Test
  void 申报品名只差首尾空白或大小写仍然算照搬() {
    // 「照搬」在现实里经常是复制后顺手改了首字母大小写，那仍然是照搬。
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () ->
                    inA(
                        () ->
                            service.create(
                                baseInput("  高颜值超薄移动电源 10000mAh 快充  ").build(),
                                "Bearer tenant-a")))
            .getReason();
    assertThat(reason).contains("与销售标题完全相同");
  }

  @Test
  void 绕过服务层直改sql照抄销售标题被db挡下() {
    var created =
        inA(() -> service.create(baseInput("移动电源 锂离子电池 10000mAh").build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    inA(
        () -> {
          var e =
              assertThrows(
                  RuntimeException.class,
                  () ->
                      sql.execute(
                          "update customs_element_set set declared_name=? where id=?",
                          catalogSalesTitle, id));
          assertThat(message(e)).as("DB CHECK 也要挡住照抄，不能只靠服务层")
              .contains("与销售标题完全相同");
          return null;
        });
  }

  @Test
  void 申报品名是独立字段而不是销售标题换个名字() {
    // 蓝图要求「申报品名有独立字段与独立校验」：这里钉住两者取值不同且各自可独立维护。
    var created =
        inA(() -> service.create(baseInput("移动电源 锂离子电池 10000mAh").build(), "Bearer tenant-a"));
    var d = created.detail();
    assertThat(d.row().declaredName()).isEqualTo("移动电源 锂离子电池 10000mAh");
    assertThat(d.salesTitleSnapshot())
        .isEqualTo(catalogSalesTitle)
        .isNotEqualTo(d.row().declaredName());
  }

  // ==========================================================================
  // 2. 缺失项提示逐项点名，且与发布闸同一口径
  // ==========================================================================

  @Test
  void 空集逐项点名九件缺失且不是一句泛化提示() {
    var d = inA(() -> service.create(emptyInput().build(), "Bearer tenant-a")).detail();
    var blockers =
        d.missing().stream()
            .filter(m -> m.severity() == ElementSetMissingSeverity.BLOCKER)
            .toList();
    assertThat(blockers).hasSize(9);
    assertThat(blockers).extracting(ElementSetMissingItem::label)
        .containsExactlyInAnyOrder(
            "申报品名", "规格材质", "用途", "品牌类型", "品牌", "数量", "单价", "币种", "产地");
    // 每一条都要带「为什么」，否则运营看到字段名也不知道该填什么。
    assertThat(blockers).allMatch(m -> m.reason() != null && m.reason().length() > 10);
    assertThat(d.complete()).isFalse();
    assertThat(d.publishable()).isFalse();
    assertThat(d.actions()).contains("EDIT").doesNotContain("PUBLISH");
  }

  @Test
  void required补充项没填值也算缺失() {
    var input =
        baseInput("移动电源 锂离子电池 10000mAh")
            .items(
                List.of(
                    new ElementSetItemInput(1, "A1", "用途细分", "", true),
                    new ElementSetItemInput(2, "A2", "材质细分", "锂离子电芯", false)));
    var d = inA(() -> service.create(input.build(), "Bearer tenant-a")).detail();
    assertThat(d.missing()).extracting(ElementSetMissingItem::label).contains("用途细分");
    assertThat(d.missing()).extracting(ElementSetMissingItem::field).contains("item:A1");
    // 非必填但空着不算缺失 —— 「没这项」与「这项还没填」是两件事。
    assertThat(d.missing()).extracting(ElementSetMissingItem::label).doesNotContain("材质细分");
  }

  @Test
  void 清单与详情的缺失条数一致() {
    // 同一个事实在两个页面上给两个答案是列表页最常见的假活之一。
    var created = inA(() -> service.create(emptyInput().build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    var detail = inA(() -> service.detail(id.toString()));
    var page = inA(() -> service.list("DE", null, null, null, null, null, 0, 50, T0));
    var row =
        page.items().stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    assertThat(row.missingCount()).isEqualTo(detail.missing().size());
    assertThat(row.blockingMissingCount()).isEqualTo(detail.missing().size());
  }

  // ==========================================================================
  // 3. 发布闸三道
  // ==========================================================================

  @Test
  void 缺要素不可发布且错误文案逐项列出() {
    var created = inA(() -> service.create(emptyInput().build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () ->
                    inA(
                        () ->
                            service.publish(
                                id.toString(),
                                new ElementSetPublishInput(0L),
                                "Bearer tenant-a",
                                membershipA)))
            .getReason();
    assertThat(reason).contains("不能发布").contains("申报品名").contains("规格材质").contains("产地");
  }

  @Test
  void 绕过服务层直接发布缺要素的集被db挡下() {
    var created = inA(() -> service.create(emptyInput().build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    inA(
        () -> {
          var e =
              assertThrows(
                  RuntimeException.class,
                  () ->
                      sql.execute(
                          "update customs_element_set set status='PUBLISHED', published_by=?,"
                              + " published_at=?::timestamptz where id=?",
                          membershipA, OffsetDateTime.now(), id));
          assertThat(message(e)).as("DB 触发器 C4 必须独立于服务层生效")
              .contains("不能发布")
              .contains("申报要素缺失");
          return null;
        });
  }

  @Test
  void 齐备后可发布且发布人留痕() {
    var created =
        inA(() -> service.create(baseInput("移动电源 锂离子电池 10000mAh").build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    var published =
        inA(
            () ->
                service.publish(
                    id.toString(), new ElementSetPublishInput(0L), "Bearer tenant-a", membershipA));
    assertThat(published.detail().row().status()).isEqualTo("PUBLISHED");
    assertThat(published.detail().actions()).contains("CLOSE").doesNotContain("EDIT");
    // 裸查也要在租户事务里：RLS 在 FORCE 下对**没有绑定租户上下文**的连接一样收口，
    // 不包 inA 的话这一句返回 null，而断言会报成一个与「发布人留痕」毫不相干的 NPE。
    var row = inA(() -> sql.fetchOne("select published_by, published_at from customs_element_set where id=?", id));
    assertThat(row.get("published_by", UUID.class))
        .as("没有发布人的「已发布」无法证伪")
        .isEqualTo(membershipA);
    assertThat(row.get("published_at", OffsetDateTime.class)).isNotNull();
  }

  @Test
  void 上游没有该目的地的版本时发布被拒且不退回基础属性() {
    var created =
        inA(() -> service.create(baseInput("移动电源 锂离子电池 10000mAh").build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    catalogHasVersion = false;
    try {
      var reason =
          assertThrows(
                  ResponseStatusException.class,
                  () ->
                      inA(
                          () ->
                              service.publish(
                                  id.toString(),
                                  new ElementSetPublishInput(0L),
                                  "Bearer tenant-a",
                                  membershipA)))
              .getReason();
      assertThat(reason).as("文案要指向「去维护」而不是「查不到」")
          .contains("没有生效的报关版本")
          .contains("不会退回基础属性");
    } finally {
      catalogHasVersion = true;
    }
  }

  @Test
  void 产地与上游归类口径不一致时发布被拒() {
    // 先用一致产地建集（建集那一刻本身也核一次，不靠建集放行），
    // 再把上游归类口径改成 VN，制造「库里这一版 CN / 上游已 VN」的错位。
    var created =
        inA(() -> service.create(baseInput("移动电源 锂离子电池 10000mAh").build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    catalogOrigin = "VN";
    try {
      var reason =
          assertThrows(
                  ResponseStatusException.class,
                  () ->
                      inA(
                          () ->
                              service.publish(
                                  id.toString(),
                                  new ElementSetPublishInput(0L),
                                  "Bearer tenant-a",
                                  membershipA)))
              .getReason();
      assertThat(reason).contains("不一致").contains("CN");
    } finally {
      catalogOrigin = "CN";
    }
  }

  @Test
  void 建集时就拦下产地与上游归类口径不一致() {
    // 产地错位要在**最早**那一刻拦住：等它活到发布才拦，中间这段时间里
    // 清单、详情、导出都可能把这一版当成可用的申报口径。
    catalogOrigin = "VN";
    try {
      var reason =
          assertThrows(
                  ResponseStatusException.class,
                  () ->
                      inA(
                          () ->
                              service.create(
                                  baseInput("移动电源 锂离子电池 10000mAh").build(),
                                  "Bearer tenant-a")))
              .getReason();
      assertThat(reason).contains("不一致").contains("VN");
    } finally {
      catalogOrigin = "CN";
    }
  }

  // ==========================================================================
  // 4. 已发布不可原地改正文
  // ==========================================================================

  @Test
  void 已发布不能经服务层改正文() {
    var id = publishedSet("DE", 1);
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () ->
                    inA(
                        () ->
                            service.update(
                                id.toString(), baseInput("换个说法").build(), "Bearer tenant-a")))
            .getReason();
    assertThat(reason).contains("已发布的申报要素集不能改").contains("新建一个版本");
  }

  @Test
  void 已发布绕过服务层直改sql同样被挡() {
    var id = publishedSet("US", 1);
    inA(
        () -> {
          var e =
              assertThrows(
                  RuntimeException.class,
                  () -> sql.execute("update customs_element_set set brand='OTHER' where id=?", id));
          assertThat(message(e)).contains("不能原地改申报要素本体");
          return null;
        });
    // 补充项也一起冻住（头表一条不动就能改子表的话，C4 的发布闸就只在发布那刻跑过一次）。
    inA(
        () -> {
          var e =
              assertThrows(
                  RuntimeException.class,
                  () ->
                      sql.execute(
                          "insert into customs_element_set_item (id,tenant_id,element_set_id,ordinal,"
                              + "element_code,element_name,element_value,required)"
                              + " values (?,?,?,?,?,?,?,true)",
                          UUID.randomUUID(), a, id, 99, "Z9", "偷加的一项", "x"));
          assertThat(message(e)).contains("补充申报要素不可改删");
          return null;
        });
  }

  @Test
  void 已发布只能向前关闭区间不能放宽或收紧到过去() {
    // ⚠️ 这一版必须是**开口**区间（effective_to = null）。
    //   若沿用 T1（2026-07-01）作终点，而「现在」是 2026-10-15，那么
    //   「收紧到 30 天后」实际上是**放宽**（2026-11-14 晚于 2026-07-01），
    //   触发器会正确地拒掉它 —— 用例挂在与它声称相反的语义上。
    //   开口区间 + 收到一个仍在未来的时刻，才是真正的「收紧」。
    var id = inA(() -> service.create(completeInput("GB", 1, T0, null).build(), "Bearer tenant-a"))
        .detail().row().id();
    inA(
        () ->
            service.publish(
                id.toString(), new ElementSetPublishInput(0L), "Bearer tenant-a", membershipA));
    // 乐观锁凭据必须取**当前真实值**：发布那一步已经把 version 从 0 推到 1，
    // 写死 0 会先被乐观锁挡成 409，于是这条用例在测「乐观锁」而不是「区间收紧」。
    long current = currentVersion(id);
    // 放宽（终点置空 = +∞）被拒。不能只说「不晚于起点」：null 与起点的先后关系
    // 根本无从比较，真实原因是「这一版已被引用，放宽会让它继续覆盖未来申报」。
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () -> inA(() -> service.close(id.toString(), new ElementSetCloseInput(current, null))))
            .getReason();
    assertThat(reason).contains("不能把生效终点放空").contains("只允许**收紧**区间");

    // 收紧到已经过去的时刻被拒。⚠️ 这个「过去」必须**晚于生效起点**（本版 from = 2026-01-01）：
    //   取一个比起点还早的时刻（2020）会先被「终点必须晚于起点」那条拦下，
    //   根本没走到「只能设在未来」—— 那样这条用例测的就不是它声称在测的东西了。
    var past = OffsetDateTime.of(2026, 6, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8));
    assertThat(past).as("这个时刻必须晚于生效起点 T0，否则走不到「只能设在未来」那一闸")
        .isAfter(T0);
    var reason2 =
        assertThrows(
                ResponseStatusException.class,
                () -> inA(() -> service.close(id.toString(), new ElementSetCloseInput(current, past))))
            .getReason();
    assertThat(reason2).contains("生效终点只能设在未来");

    // 收紧到未来的某一刻合法
    var future = OffsetDateTime.now().plusDays(30);
    var closed = inA(() -> service.close(id.toString(), new ElementSetCloseInput(current, future)));
    assertThat(closed.detail().row().effectiveTo()).isNotNull();
  }

  /** 读当前的乐观锁凭据（写死数字会在发布把它 +1 之后悄悄变成测乐观锁）。 */
  long currentVersion(UUID id) {
    return inA(() -> sql.fetchOne("select version from customs_element_set where id=?", id)
            .get("version", Long.class));
  }

  @Test
  void 已发布区间重叠被拒且点名与哪一版撞了() {
    // 必须同一个 SKU：重叠的判定维度是 (sku, 目的地, 区间)，
    // 两个随机 SKU 压根不构成冲突，那样的用例是「因为没撞上而通过」的假活。
    var sku = UUID.randomUUID();
    var first = inA(() -> service.create(completeInput("FR", 1, T0, T1).skuId(sku).build(), "Bearer tenant-a"));
    inA(
        () ->
            service.publish(
                first.detail().row().id().toString(),
                new ElementSetPublishInput(0L),
                "Bearer tenant-a",
                membershipA));
    // 第二个 FR 版本区间与第一个重叠。建集不撞闸（草稿区间随便重叠），
    // 撞闸发生在**发布那一刻** —— 与 V138 guard_element_set_interval 的时机一致。
    var second =
        inA(
            () ->
                service.create(
                    completeInput("FR", 2, T0, T1).skuId(sku).build(),
                    "Bearer tenant-a"));
    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () ->
                inA(
                    () ->
                        service.publish(
                            second.detail().row().id().toString(),
                            new ElementSetPublishInput(0L),
                            "Bearer tenant-a",
                            membershipA)));
    var reason = thrown.getReason();
    assertThat(reason).as("只说「区间重叠」的话运营要自己回去找是哪一版")
        .contains("与 v1 撞了")
        .contains("FR");
    // 冲突是 409 而不是 500：业务冲突要给客户端可读的文案去改区间。
    assertThat(thrown.getStatusCode().value()).isEqualTo(409);
  }

  // ==========================================================================
  // 5. 生效区间用固定时刻钉死（不依赖 now()）
  // ==========================================================================

  @Test
  void 生效区间按固定时刻判定而不是依赖当前时间() {
    // v1: [T0, T1)   v2: [T1, T2) —— 半开区间，在 T1 处恰好命中 v2
    var created1 =
        inA(() -> service.create(completeInput("DE", 1, T0, T1).build(), "Bearer tenant-a"));
    var created2 =
        inA(() -> service.create(completeInput("DE", 2, T1, T2).build(), "Bearer tenant-a"));
    for (var c : List.of(created1, created2)) {
      inA(
          () ->
              service.publish(
                  c.detail().row().id().toString(),
                  new ElementSetPublishInput(0L),
                  "Bearer tenant-a",
                  membershipA));
    }
    var atT0 = inA(() -> service.list("DE", null, null, null, null, null, 0, 50, T0));
    assertThat(atT0.summary().effectiveNow()).isEqualTo(1);
    assertThat(atT0.items()).extracting(ElementSetRow::versionNo).contains(1);

    var atT1 = inA(() -> service.list("DE", null, null, null, null, null, 0, 50, T1));
    assertThat(atT1.summary().effectiveNow()).as("左闭右开：T1 属于 v2 不属于 v1").isEqualTo(1);
    // 清单是**维护视图**：两个版本都要看得见（未来的、草稿的都在其中），
    // 「这一刻哪一版生效」由 effectiveNow 标注 + summary 的生效计数回答，
    // 而不是把行过滤掉 —— 过滤掉的话运营就看不到自己还没发的那一版了。
    assertThat(atT1.items())
        .filteredOn(ElementSetRow::effectiveNow)
        .extracting(ElementSetRow::versionNo)
        .as("左闭右开：T1 这一刻生效的是 v2")
        .containsExactly(2);

    var between =
        inA(
            () ->
                service.list(
                    "DE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    50,
                    OffsetDateTime.of(2026, 4, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8))));
    assertThat(between.summary().effectiveNow()).isEqualTo(1);

    var afterAll =
        inA(
            () ->
                service.list(
                    "DE",
                    null,
                    null,
                    null,
                    null,
                    null,
                    0,
                    50,
                    OffsetDateTime.of(2030, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(8))));
    assertThat(afterAll.summary().effectiveNow()).as("两段区间都结束后没有生效版本").isZero();
  }

  @Test
  void 终点不晚于起点被拒() {
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () -> inA(() -> service.create(completeInput("IT", 1, T1, T0).build(), "Bearer tenant-a")))
            .getReason();
    assertThat(reason).contains("生效终点必须严格晚于生效起点");
  }

  @Test
  void 生效区间往返保持固定时刻() {
    // 这条断言同时是防回归提醒：区间相关断言必须用固定时刻，
    // 否则「只在本批次时间窗内通过」的用例会在下一批悄悄变红。
    var created =
        inA(() -> service.create(completeInput("ES", 1, T0, T1).build(), "Bearer tenant-a"));
    var row = created.detail().row();
    assertThat(row.effectiveFrom()).isEqualTo(T0);
    assertThat(row.effectiveTo()).isEqualTo(T1);
  }

  // ==========================================================================
  // 6. timestamptz 往返零漂移
  // ==========================================================================

  @Test
  void timestamptz写入带时区后读回零漂移() {
    var created =
        inA(() -> service.create(completeInput("NL", 1, T0, T1).build(), "Bearer tenant-a"));
    var row = created.detail().row();
    assertThat(row.effectiveFrom().toInstant()).isEqualTo(T0.toInstant());
    assertThat(row.effectiveFrom().getOffset()).isEqualTo(ZoneOffset.ofHours(8));
    var raw =
        inA(
            () ->
                sql.fetchOne("select effective_from from customs_element_set where id=?", row.id())
                    .get("effective_from", OffsetDateTime.class));
    assertThat(raw.toInstant())
        .as("用 Timestamp.valueOf(LocalDateTime) 写会静默偏 −28800 秒 —— 必须逐项断言写回的值")
        .isEqualTo(T0.toInstant());
  }

  // ==========================================================================
  // 7. 币种枚举与 DB CHECK 逐项相等（防静默漂移）
  // ==========================================================================

  @Test
  void 币种枚举与db的check逐项相等() {
    var def =
        sql.fetchOne(
                "select pg_get_constraintdef(oid) as d from pg_constraint"
                    + " where conname='customs_element_set_currency_iso4217'")
            .get("d", String.class);
    assertThat(def).as("V138 必须真的建出这条 CHECK").isNotNull();
    var dbCodes = new ArrayList<String>();
    var matcher = java.util.regex.Pattern.compile("'([A-Z]{3})'").matcher(def);
    while (matcher.find()) {
      dbCodes.add(matcher.group(1));
    }
    java.util.Collections.sort(dbCodes);
    assertThat(dbCodes)
        .as("Java 枚举与 DB CHECK 不一致时会出现「前端下拉能选、DB 拒收」或反过来")
        .isEqualTo(ElementSetCurrency.codes());
  }

  @Test
  void 非两位国家代码被服务层拒成400而不是撑爆varchar2() {
    // ⚠️ 这条钉的是「服务层自己校验形状」：DTO 上的 @Pattern 只在 HTTP 的 @Valid 上生效，
    // 不经控制器的调用（批处理 / 内部编排 / 另一个模块直调 Service）会绕过它，
    // 然后 4 个字母去撞 varchar(2) 以 DataIntegrityViolation（500）收场 —— 那是一个「你填错了」。
    // 小写 "de" 不在这一列里：服务层会把目的地统一大写，"de" 是**合法输入**且落库为 DE，
    // 规范化比报错对运营友好（界面上不该因为手滑的大小写把人挡在门外）。
    for (var bad : List.of("DEU", "*", "")) {
      var thrown =
          assertThrows(
              ResponseStatusException.class,
              () ->
                  inA(
                      () ->
                          service.create(
                              baseInput("移动电源 锂离子电池 10000mAh")
                                  .destinationCountryCode(bad)
                                  .build(),
                              "Bearer tenant-a")));
      assertThat(thrown.getStatusCode().value()).as("目的地「%s」必须是 400", bad).isEqualTo(400);
      assertThat(thrown.getReason()).as("文案要说清要什么，不要只报格式错").contains("两位大写字母");
    }
  }

  @Test
  void 非iso4217的币种被拒() {
    for (var bad : List.of("ZZZ", "XXX", "ABC")) {
      var reason =
          assertThrows(
                  ResponseStatusException.class,
                  () ->
                      inA(
                          () ->
                              service.create(
                                  baseInput("移动电源 锂离子电池 10000mAh").currency(bad).build(),
                                  "Bearer tenant-a")))
              .getReason();
      assertThat(reason)
          .as("%s 形状对但不是货币，放行它会让整票货值算错一个数量级", bad)
          .contains("不是本模块支持的 ISO 4217 申报币种");
    }
  }

  @Test
  void 优惠适用性与原产地规则双向一致() {
    var noRule =
        assertThrows(
                ResponseStatusException.class,
                () ->
                    inA(
                        () ->
                            service.create(
                                baseInput("移动电源 锂离子电池 10000mAh")
                                    .preferentialRateApplicable(true)
                                    .originRuleCode("")
                                    .build(),
                                "Bearer tenant-a")))
            .getReason();
    assertThat(noRule).contains("必须填原产地规则代码").contains("可能被追缴");

    var ruleOnly =
        assertThrows(
                ResponseStatusException.class,
                () ->
                    inA(
                        () ->
                            service.create(
                                baseInput("移动电源 锂离子电池 10000mAh")
                                    .preferentialRateApplicable(false)
                                    .originRuleCode("RCEP")
                                    .build(),
                                "Bearer tenant-a")))
            .getReason();
    assertThat(ruleOnly).contains("就不该填原产地规则代码").contains("互相矛盾");
  }

  @Test
  void 主张优惠会给出不等于会免的口径提示() {
    var input =
        baseInput("移动电源 锂离子电池 10000mAh")
            .preferentialRateApplicable(true)
            .originRuleCode("RCEP");
    var d = inA(() -> service.create(input.build(), "Bearer tenant-a")).detail();
    var notice =
        d.missing().stream()
            .filter(m -> m.severity() == ElementSetMissingSeverity.NOTICE)
            .toList();
    assertThat(notice).hasSize(1);
    assertThat(notice.get(0).reason()).contains("主张优惠不等于会免");
    assertThat(d.complete()).as("NOTICE 不是阻断项").isTrue();
  }

  // ==========================================================================
  // 8. 列表：真分页 + 汇总不受分页 + 筛选 + 排序白名单
  // ==========================================================================

  @Test
  void 真分页两页不重叠且汇总覆盖全量() {
    for (int i = 1; i <= 7; i++) {
      // 复制成 final 再捕获：循环变量本身不是 effectively final，直接捕获编译不过。
      final int v = i;
      inA(() -> service.create(completeInput("SE", v, T0.plusDays(v), T1).build(), "Bearer tenant-a"));
    }
    var first = inA(() -> service.list("SE", null, null, null, null, null, 0, 3, T0));
    var second = inA(() -> service.list("SE", null, null, null, null, null, 1, 3, T0));
    var all = inA(() -> service.list("SE", null, null, null, null, null, 0, 50, T0));
    assertThat(first.items()).hasSize(3);
    assertThat(second.items()).hasSize(3);
    assertThat(first.items()).extracting(ElementSetRow::id)
        .doesNotContainAnyElementsOf(second.items().stream().map(ElementSetRow::id).toList());
    assertThat(all.totalElements()).isGreaterThanOrEqualTo(7);
    assertThat(first.summary().total()).isEqualTo(all.summary().total());
    // 7 行 / 每页 3 行 = 3 页。**看的是 first 的页数**：all 用的 size=50，
    // 拿它的总页数去断言「超过一页」是在断言一个必然为 1 的数 —— 那是条不会红的假断言。
    assertThat(first.totalPages()).isEqualTo(3);
    assertThat(second.totalPages()).isEqualTo(3);
  }

  @Test
  void 筛选真筛选且排序白名单外的键静默回落() {
    inA(() -> service.create(completeInput("AT", 1, T0, T1).build(), "Bearer tenant-a"));
    inA(() -> service.create(completeInput("PL", 1, T0, T1).build(), "Bearer tenant-a"));
    assertThat(inA(() -> service.list("AT", null, null, null, null, null, 0, 50, T0)).items())
        .extracting(ElementSetRow::destinationCountryCode)
        .containsOnly("AT");
    assertThat(inA(() -> service.list("PL", "DRAFT", null, null, null, null, 0, 50, T0)).items())
        .extracting(ElementSetRow::destinationCountryCode)
        .containsOnly("PL");
    assertThat(inA(() -> service.list("PL", "PUBLISHED", null, null, null, null, 0, 50, T0)).items())
        .as("状态筛选真筛选")
        .isEmpty();
    // 注入排序键：静默回落默认键但表还在（与 10.2.1.1 同一纪律）
    var injected = inA(() -> service.list("AT", null, null, null, null, "; drop table x", 0, 50, T0));
    assertThat(injected.items()).hasSize(1);
    assertThat(injected.sortNote()).contains("不认识");
  }

  @Test
  void 只列有缺失项的筛选真筛选() {
    inA(() -> service.create(completeInput("BE", 1, T0, T1).build(), "Bearer tenant-a"));
    inA(
        () ->
            service.create(
                emptyInput().destinationCountryCode("BE").versionNo(2).build(), "Bearer tenant-a"));
    var missingOnly = inA(() -> service.list("BE", null, null, true, null, null, 0, 50, T0));
    assertThat(missingOnly.items()).hasSize(1);
    assertThat(missingOnly.items().get(0).versionNo()).isEqualTo(2);
  }

  @Test
  void 筛选下拉来自真实数据() {
    inA(() -> service.create(completeInput("CZ", 1, T0, T1).build(), "Bearer tenant-a"));
    var opts = inA(() -> service.filterOptions());
    assertThat(opts.destinations()).extracting(ElementSetVocabularyItem::code).contains("CZ");
    assertThat(opts.statuses()).extracting(ElementSetVocabularyItem::code).contains("DRAFT");
    assertThat(opts.sorts()).isNotEmpty();
    assertThat(opts.total()).isGreaterThanOrEqualTo(1);
  }

  @Test
  void 乐观锁让并发编辑撞409() {
    var created =
        inA(() -> service.create(baseInput("移动电源 锂离子电池 10000mAh").build(), "Bearer tenant-a"));
    var id = created.detail().row().id().toString();
    inA(() -> service.update(id, baseInput("第一版说法").build(), "Bearer tenant-a"));
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () -> inA(() -> service.update(id, baseInput("第二版说法").build(), "Bearer tenant-a")))
            .getReason();
    assertThat(reason).as("后到的写入必须撞 409 而不是静默覆盖先到的那份申报事实")
        .contains("乐观锁")
        .contains("没有落库");
  }

  @Test
  void 补充要素项位次重复被拒() {
    var input =
        baseInput("移动电源 锂离子电池 10000mAh")
            .items(
                List.of(
                    new ElementSetItemInput(1, "A1", "第一项", "x", true),
                    new ElementSetItemInput(1, "A2", "第二项", "y", true)));
    var reason =
        assertThrows(
                ResponseStatusException.class,
                () -> inA(() -> service.create(input.build(), "Bearer tenant-a")))
            .getReason();
    assertThat(reason).contains("位次 1 重复了");
  }

  // ==========================================================================
  // 9. capabilities 逐条说清边界
  // ==========================================================================

  @Test
  void capabilities逐条声明能力与边界() {
    var caps = inA(() -> service.capabilities());
    var byDim = new java.util.HashMap<String, ElementSetCapabilityDimension>();
    caps.dimensions().forEach(d -> byDim.put(d.dimension(), d));
    assertThat(byDim.get("ELEMENT_SET").available()).isTrue();
    assertThat(byDim.get("DESTINATION_VARIANT").note()).contains("catalog 3.7.1.2 明文不做这件事");
    assertThat(byDim.get("BATCH_ACTUALS").note()).contains("参考值不是申报值");
    assertThat(byDim.get("HS_CLASSIFICATION").available()).isFalse();
    assertThat(byDim.get("HS_CLASSIFICATION").ownerModule()).contains("3.7.1.1");
    assertThat(byDim.get("STATUTORY_UOM").available()).isFalse();
    assertThat(byDim.get("TARIFF_RATE").note()).contains("主张不等于会免");
    assertThat(byDim.get("CUSTOMS_SUBMISSION").ownerModule()).contains("10.5.1.1");
    // 三张词表都要给，界面从 capabilities 读而不是自己抄一份
    assertThat(caps.currencies()).extracting(ElementSetVocabularyItem::code).contains("USD", "EUR", "CNY");
    assertThat(caps.brandTypes()).extracting(ElementSetVocabularyItem::code)
        .contains("OWN_BRAND", "OEM", "NO_BRAND", "NOT_APPLICABLE");
    assertThat(caps.originRules()).extracting(ElementSetVocabularyItem::code).contains("RCEP", "CPTPP");
    assertThat(caps.elementFields()).extracting(ElementSetVocabularyItem::label)
        .contains("申报品名", "规格材质", "用途", "品牌", "数量", "单价", "币种", "产地");
    assertThat(caps.declaredNameNote()).contains("不能照搬销售标题");
    assertThat(caps.effectiveIntervalNote()).contains("半开区间");
    assertThat(caps.catalogBoundaryNote()).contains("本模块拥有的是");
  }

  @Test
  void 上游预览真的跨服务取到销售标题与合成资料() {
    var preview = inA(() -> service.preview(UUID.randomUUID().toString(), "DE", T0, "Bearer tenant-a"));
    assertThat(preview.salesTitle()).isEqualTo(catalogSalesTitle);
    assertThat(preview.hsCode()).isEqualTo("8507600090");
    assertThat(preview.originCountryCode()).isEqualTo("CN");
    assertThat(preview.statutoryUnitCode()).isEqualTo("PCE");
    assertThat(preview.preferenceAgreement()).isEqualTo("RCEP");
    assertThat(preview.upstreamElements()).extracting(ElementSetItemRow::elementCode).contains("01");
    assertThat(preview.declaredNameSource()).isEqualTo("MODULE_3_7_1_2");
  }

  // ==========================================================================
  // 10. HTTP 边界
  // ==========================================================================

  @Test
  void 端点返回400_401_403_404_409() throws Exception {
    http.perform(get("/trade/logistics/customs-elements/list").header("Authorization", "Bearer nope"))
        .andExpect(status().isUnauthorized());
    http.perform(
            get("/trade/logistics/customs-elements/list").header("Authorization", "Bearer tenant-a-noperm"))
        .andExpect(status().isForbidden());
    http.perform(
            get("/trade/logistics/customs-elements/" + UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
    http.perform(get("/trade/logistics/customs-elements/not-a-uuid").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    http.perform(get("/trade/logistics/customs-elements/list?status=NOPE")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    // 409：已发布再改
    var id = publishedSet("PT", 1);
    http.perform(put("/trade/logistics/customs-elements/" + id)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content(json.writeValueAsString(baseInput("换个说法").build())))
        .andExpect(status().isConflict());
    // 200：capabilities / filter-options / list
    http.perform(get("/trade/logistics/customs-elements/capabilities")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/logistics/customs-elements/filter-options")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/trade/logistics/customs-elements/list").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
  }

  @Test
  void 建集端点经http走的是不带尾斜杠的路径() throws Exception {
    // 钉住 @PostMapping 的**无值**写法：Boot 3 不把尾斜杠当等价路径，
    // 浏览器经 /api/trade 代理转发过来的路径没有尾斜杠 —— 写成 @PostMapping("/") 的话
    // 这一条在浏览器里是 404，而 service 层与除本条外的所有用例都照样绿。
    http.perform(
            post("/trade/logistics/customs-elements")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json.writeValueAsString(baseInput("移动电源 锂离子电池 10000mAh").build())))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = result.getResponse().getContentAsString();
          assertThat(body)
              .as("建集响应要带回完整的申报要素集（清单行 + 缺失项提示）")
              .contains("\"status\":\"DRAFT\"")
              .contains("\"destinationCountryCode\":\"DE\"")
              .contains("移动电源 锂离子电池 10000mAh")
              .contains("\"missing\":[]");
        });
    // 尾斜杠那一支刻意**不放行**：后端没有这个映射，
    // 放行它会让界面以为有一条等价的建集路径，而它其实 404。
    http.perform(
            post("/trade/logistics/customs-elements/")
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content(json.writeValueAsString(baseInput("移动电源 锂离子电池 10000mAh").build())))
        .andExpect(status().isNotFound());
  }

  // ==========================================================================
  // 11. 多租户三断言
  // ==========================================================================

  @Test
  void 断言1_两租户互不可见() {
    var mine = inA(() -> service.create(completeInput("MY", 1, T0, T1).build(), "Bearer tenant-a"));
    var theirs = inB(() -> service.create(completeInput("TH", 1, T0, T1).build(), "Bearer tenant-b"));
    var mineList = inA(() -> service.list("MY", null, null, null, null, null, 0, 50, T0));
    var theirsList = inB(() -> service.list("TH", null, null, null, null, null, 0, 50, T0));
    assertThat(mineList.items()).extracting(ElementSetRow::id).contains(mine.detail().row().id());
    assertThat(mineList.items()).extracting(ElementSetRow::id).doesNotContain(theirs.detail().row().id());
    assertThat(theirsList.items()).extracting(ElementSetRow::id).contains(theirs.detail().row().id());
    assertThat(theirsList.items()).extracting(ElementSetRow::id).doesNotContain(mine.detail().row().id());
    // 按主键直查 B 的行也必须拿不到（不只是列表过滤）
    var thrown =
        assertThrows(
            ResponseStatusException.class,
            () -> inA(() -> service.detail(theirs.detail().row().id().toString())));
    assertThat(thrown.getStatusCode().value()).isEqualTo(404);
    // 筛选下拉也互不可见
    assertThat(inA(() -> service.filterOptions()).destinations())
        .extracting(ElementSetVocabularyItem::code)
        .doesNotContain("TH");
  }

  @Test
  void 断言2_伪造租户上下文无效() throws Exception {
    var mine = inA(() -> service.create(completeInput("FI", 1, T0, T1).build(), "Bearer tenant-a"));
    var theirs = inB(() -> service.create(completeInput("SG", 1, T0, T1).build(), "Bearer tenant-b"));
    // 伪造 X-Tenant-Id 头：租户仍由已认证成员关系解析，伪造头无处可去。
    http.perform(get("/trade/logistics/customs-elements/list")
            .header("Authorization", "Bearer tenant-a")
            .header("X-Tenant-Id", b))
        .andExpect(status().isOk())
        .andExpect(result -> {
          var body = result.getResponse().getContentAsString();
          assertThat(body).contains(mine.detail().row().id().toString());
          assertThat(body)
              .as("B 的集合绝不能出现在 A 的响应里")
              .doesNotContain(theirs.detail().row().id().toString());
        });
  }

  @Test
  void 断言3_连接池归还后不串租户() {
    var mine = inA(() -> service.create(completeInput("NO", 1, T0, T1).build(), "Bearer tenant-a"));
    inB(() -> service.create(completeInput("NO", 1, T0, T1).build(), "Bearer tenant-b"));
    // 池大小为 1：下面每次读写都复用同一条连接，只有事务局部绑定 + 提交即清空
    // 才能保证第 5 次读到的仍然是 B 而不是上一轮残留的 A。
    for (int i = 0; i < 5; i++) {
      var aRows = inA(() -> service.list("NO", null, null, null, null, null, 0, 50, T0));
      var bRows = inB(() -> service.list("NO", null, null, null, null, null, 0, 50, T0));
      assertThat(aRows.items()).as("第 %d 轮租户 A", i).hasSize(1);
      assertThat(aRows.items()).extracting(ElementSetRow::id).containsExactly(mine.detail().row().id());
      assertThat(bRows.items()).as("第 %d 轮租户 B", i).hasSize(1);
      assertThat(bRows.items()).extracting(ElementSetRow::id).doesNotContain(mine.detail().row().id());
    }
    // 归还后再单独借一次，仍然干净。
    assertThat(inA(() -> service.list("NO", null, null, null, null, null, 0, 50, T0)).items())
        .hasSize(1);
    assertThat(inB(() -> service.list("NO", null, null, null, null, null, 0, 50, T0)).items())
        .hasSize(1);
  }

  @Test
  void 每张表都force_rls而不是只启用() {
    for (var table : List.of("customs_element_set", "customs_element_set_item")) {
      var row =
          sql.fetchOne(
              "select relrowsecurity, relforcerowsecurity from pg_class where relname = ?", table);
      assertThat(row).as("V138 必须真的建出 %s", table).isNotNull();
      assertThat(row.get(0, Boolean.class)).as("%s RLS 未启用", table).isTrue();
      assertThat(row.get(1, Boolean.class)).as("%s 只有 FORCE 才挡得住误授 owner", table).isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('trade_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("trade_app 必须真的能读 %s", table)
          .isTrue();
      assertThat(
              sql.fetchOne("select has_table_privilege('starcon_app',?,'SELECT')", table)
                  .get(0, Boolean.class))
          .as("starcon_app 跨服务只读角色拿不到本模块的表")
          .isFalse();
    }
  }

  // ==========================================================================
  // 工具
  // ==========================================================================

  <T> T inA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "trade.fulfillment.update", work);
  }

  <T> T inB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "trade.fulfillment.update", work);
  }

  static String message(RuntimeException e) {
    if (e instanceof ResponseStatusException rse) {
      return rse.getReason() == null ? rse.getStatusCode().toString() : rse.getReason();
    }
    return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
  }

  /**
   * 输入构造器。
   *
   * <p>Java record 不可变，所以「在齐备集合上改一个字段」这种测试写法必须靠构造器而不是
   * 链式 setter。这里保留默认值，让每个用例只写它关心的那一个字段 ——
   * 满屏传 15 个 null 的构造函数调用会让人看不出这条用例到底在测什么。
   */
  static final class InputBuilder {
    UUID skuId = UUID.randomUUID();
    String destination = "DE";
    int versionNo = 1;
    String declaredName;
    String specMaterial;
    String usage;
    String brandType;
    String brand;
    BigDecimal quantity;
    BigDecimal unitPrice;
    String currency;
    String originCountryCode;
    String originRuleCode;
    Boolean preferential = Boolean.FALSE;
    OffsetDateTime from = T0;
    OffsetDateTime to = T1;
    String note = "";
    Long version = 0L;
    List<ElementSetItemInput> items = List.of();

    InputBuilder destinationCountryCode(String v) {
      this.destination = v;
      return this;
    }

    InputBuilder versionNo(int v) {
      this.versionNo = v;
      return this;
    }

    InputBuilder declaredName(String v) {
      this.declaredName = v;
      return this;
    }

    /**
     * 指定 SKU。
     *
     * <p>默认每个构造器都是**随机 SKU**：绝大多数用例互不相干，随机更好读。
     * 但「区间重叠」这类断言必须让两版落在**同一个 SKU** 上 ——
     * 不重叠的判定维度是 (sku, 目的地)，SKU 不同就压根不构成冲突。
     */
    InputBuilder skuId(UUID v) {
      this.skuId = v;
      return this;
    }

    InputBuilder currency(String v) {
      this.currency = v;
      return this;
    }

    InputBuilder preferentialRateApplicable(Boolean v) {
      this.preferential = v;
      return this;
    }

    InputBuilder originRuleCode(String v) {
      this.originRuleCode = v;
      return this;
    }

    InputBuilder items(List<ElementSetItemInput> v) {
      this.items = v;
      return this;
    }

    InputBuilder note(String v) {
      this.note = v;
      return this;
    }

    ElementSetInput build() {
      return new ElementSetInput(
          skuId, destination, versionNo, declaredName, specMaterial, usage, brandType, brand,
          quantity, unitPrice, currency, originCountryCode, originRuleCode, preferential, from, to,
          note, version, items);
    }
  }

  /** 一个空白集合（九件全空），用于测缺失项提示。 */
  static InputBuilder emptyInput() {
    return new InputBuilder().note("空集");
  }

  /** 一个九件齐备的集合（不含补充项）。 */
  static InputBuilder completeInput(
      String destination, int versionNo, OffsetDateTime from, OffsetDateTime to) {
    var b = new InputBuilder().destinationCountryCode(destination).versionNo(versionNo).note("齐备集合");
    b.from = from;
    b.to = to;
    b.declaredName = "移动电源 锂离子电池 10000mAh";
    b.specMaterial = "铝合金+ABS外壳";
    b.usage = "手机充电";
    b.brandType = "OWN_BRAND";
    b.brand = "STARCON";
    b.quantity = new BigDecimal("120.000000");
    b.unitPrice = new BigDecimal("8.5000");
    b.currency = "USD";
    b.originCountryCode = "CN";
    return b;
  }

  /** 带自定义申报品名的齐备输入（version=0 表示刚建）。 */
  static InputBuilder baseInput(String declaredName) {
    return completeInput("DE", 1, T0, T1).declaredName(declaredName);
  }

  /** 建一个已发布的集合并返回它的 id。 */
  UUID publishedSet(String destination, int versionNo) {
    var created =
        inA(() -> service.create(completeInput(destination, versionNo, T0, T1).build(), "Bearer tenant-a"));
    var id = created.detail().row().id();
    inA(
        () ->
            service.publish(
                id.toString(), new ElementSetPublishInput(0L), "Bearer tenant-a", membershipA));
    return id;
  }
}
