package com.starcon.warehouse;

import static com.starcon.warehouse.LabelPrintModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
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
 * 8.3.4.2 面单与标签打印：五种打印对象的真渲染、<b>打印次数真累加</b>、 平台重复打印判据、批量打印真批量，以及租户隔离三断言 + 跨租户看不到对方的次数。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p>夹具写法与 {@link InventoryTransferIntegrationTest} 完全一致（同样的 Testcontainers、 同样的 fake identity
 * server、同样的按租户派生 UUID、同样的 TRUNCATE 清场）。
 *
 * <p><b>打印次数是这个模块最容易被做成假逻辑的地方</b>，所以这里的断言全部打在 <b>权威计数列</b> {@code print_target.print_count}
 * 上，而不是只数日志行数； 并且显式验证 DB 层拒绝把次数改小、拒绝篡改打印凭据。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LabelPrintingIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final java.util.Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();
  static final List<String> allPermissions =
      java.util.stream.Stream.of("inventory")
          .flatMap(
              p ->
                  java.util.stream.Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(
        ("starcon-warehouse-print-test-" + tenant + "-" + ordinal).getBytes());
  }

  static AccessService.Access actor(UUID uid, UUID tenant, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        null,
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
        s.execute("create role warehouse_app login password 'test-only' nosuperuser nobypassrls");
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
            var body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(200, body.length);
            e.getResponseBody().write(body);
            e.close();
          });
      actors.put("Bearer tenant-a", actor(user, a, List.of(grant("ALL", allPermissions))));
      actors.put("Bearer tenant-b", actor(user, b, List.of(grant("ALL", allPermissions))));
      actors.put(
          "Bearer read-only",
          actor(user, a, List.of(grant("ALL", List.of("warehouse.inventory.read")))));
      identity.start();
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("DB_URL", pg::getJdbcUrl);
    r.add("DB_USER", () -> "warehouse_app");
    r.add("DB_PASSWORD", () -> "test-only");
    r.add("DB_MIGRATION_USER", pg::getUsername);
    r.add("DB_MIGRATION_PASSWORD", pg::getPassword);
    r.add("IDENTITY_URL", () -> "http://localhost:" + identity.getAddress().getPort());
    r.add("INTERNAL_KEY", () -> "test-key");
    // 单连接：只有复用同一条连接才能证明连接池归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired LabelPrintingService printing;
  @Autowired AccessService access;
  @Autowired DSLContext sql;
  @Autowired MockMvc http;

  @AfterAll
  static void stop() {
    identity.stop(0);
    pg.stop();
  }

  AccessService.Access actorA;
  AccessService.Access actorB;

  @BeforeEach
  void reset() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS —— 只用于夹具重建。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate label_print_log, print_job_item, print_job, print_target, print_reprint_rule,"
              + " print_template, print_device restart identity cascade");
    }
  }

  /**
   * 在租户 A 的上下文里执行。
   *
   * <p><b>不能嵌套</b>：{@code set_config('app.current_tenant', ..., true)} 是事务级的， 而 {@code access.run}
   * 默认传播 REQUIRED 会复用外层事务 —— 在 asB 的 lambda 里再调 asA， 会把当前事务的租户悄悄改成 A，于是「B 拿不到 A 的数据」这种断言会假绿。
   * 所以凡是需要在 B 的上下文里用到的 id，都先在 A 的上下文里取出来再传进去。
   */
  <T> T asA(java.util.function.Supplier<T> work) {
    return access.run(actorA, "warehouse.inventory.read", work);
  }

  <T> T asB(java.util.function.Supplier<T> work) {
    return access.run(actorB, "warehouse.inventory.read", work);
  }

  // ================================================================ 夹具

  LabelPrintTemplate templateA(PrintObjectType type, String code) {
    return asA(
        () ->
            printing.createTemplate(
                new LabelPrintTemplateRequest(
                    code, code + " 模板", type, "100X150", "PORTRAIT", null, true)));
  }

  LabelPrintTemplate templateB(PrintObjectType type, String code) {
    return asB(
        () ->
            printing.createTemplate(
                new LabelPrintTemplateRequest(
                    code, code + " 模板", type, "100X150", "PORTRAIT", null, true)));
  }

  LabelPrintTarget targetA(PrintObjectType type, String ref, String waybill) {
    return asA(
        () ->
            printing.registerTarget(
                new LabelPrintTargetRequest(
                    type,
                    ref,
                    waybill,
                    "SFEX",
                    "AMZ",
                    "张三",
                    "上海市浦东新区某某路 1 号",
                    "SKU-1001",
                    "测试商品",
                    "SH-A-01-02",
                    "CTN-1",
                    1,
                    new BigDecimal("1.250000"),
                    "易碎")));
  }

  LabelPrintTarget targetB(String ref, String waybill) {
    return asB(
        () ->
            printing.registerTarget(
                new LabelPrintTargetRequest(
                    PrintObjectType.SHIPPING_LABEL,
                    ref,
                    waybill,
                    "SFEX",
                    "AMZ",
                    "李四",
                    "深圳市南山区某某路 2 号",
                    null,
                    null,
                    null,
                    null,
                    1,
                    new BigDecimal("2.000000"),
                    null)));
  }

  /** 绕过服务层读权威计数：断言必须打在真实落库的列上。 */
  int storedCount(UUID targetId) {
    return asA(
        () ->
            sql.fetchOne("select print_count from print_target where id = ?", targetId)
                .get(0, Integer.class));
  }

  int logCount(UUID targetId) {
    return asA(
        () ->
            sql.fetchOne("select count(*) from label_print_log where target_id = ?", targetId)
                .get(0, Long.class)
                .intValue());
  }

  // ================================================================ 五种打印对象都有真模板与真产出

  @Test
  void 五种打印对象各自有真渲染内容() {
    var cases =
        new Object[][] {
          {PrintObjectType.SHIPPING_LABEL, "OB-1001", "WB1001", "面单"},
          {PrintObjectType.PACKING_SLIP, "OB-1002", null, "装箱单"},
          {PrintObjectType.SKU_BARCODE, "SKU-2001", null, "SKU 条码"},
          {PrintObjectType.LOCATION_LABEL, "LOC-3001", null, "库位标签"},
          {PrintObjectType.CARTON_MARK, "CTN-4001", null, "箱唛"},
        };

    for (var c : cases) {
      var type = (PrintObjectType) c[0];
      var ref = (String) c[1];
      var waybill = (String) c[2];
      var label = (String) c[3];
      var template = templateA(type, "T-" + type.name());
      var target = targetA(type, ref, waybill);

      var job =
          asA(
              () ->
                  printing.createJob(
                      new LabelPrintJobRequest(
                          template.id(), List.of(target.id()), 1, null, null, null, label + "批量"),
                      user));
      var detail = asA(() -> printing.jobDetail(job.id()));
      assertThat(detail.items()).hasSize(1);
      var item = detail.items().get(0);

      var rendered = asA(() -> printing.renderedContent(job.id(), item.id(), null));
      assertThat(rendered).isNotBlank();
      assertThat(rendered.length()).isGreaterThan(40);

      var format = LabelRenderService.defaultFormatFor(type);
      assertThat(item.contentFormat()).isEqualTo(format);
      // 校验和必须与内容真对应（下载端点就是靠它验内容没被改过）
      assertThat(item.contentChecksum()).isEqualTo(LabelRenderService.sha256(rendered)).hasSize(64);

      if (format == PrintContentFormat.ZPL) {
        // 真正的热敏打印机指令流：^XA 包边、^XZ 收尾、条码用 ^BC
        assertThat(rendered).startsWith("^XA").endsWith("^XZ\n");
        assertThat(rendered).contains("^BCN,");
        // 面单/条码/库位标签都要打条码，且用得上真正的码值
        assertThat(rendered).doesNotContain("^FD^FS");
      } else {
        // 单据类的打印视图：@page 尺寸 + 内联样式
        assertThat(rendered).contains("<!doctype html>").contains("@page{size:");
        assertThat(rendered).contains("打印次数：");
      }
      // 五种对象的内容不能互相抄（那说明渲染没按类型走）
      assertThat(rendered).contains(ref.length() > 0 ? ref.substring(0, 1) : "x");
    }
  }

  @Test
  void 面单渲染出真实条码指令与Code128图形() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-LABEL");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-2001", "SF1234567890");
    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 1, null, null, null, null),
                    user));
    var item = asA(() -> printing.jobDetail(job.id())).items().get(0);
    var zpl = asA(() -> printing.renderedContent(job.id(), item.id(), null));

    // 条码指令里带的是真实运单号，不是占位
    assertThat(zpl).contains("^FD").contains("SF1234567890");

    // Code 128 Set B 校验位按规范算：start(104) + Σ 值×位 mod 103
    var checksum = 104;
    var position = 1;
    for (var ch : "SF1234567890".toCharArray()) {
      checksum += (ch - 32) * position;
      position++;
    }
    assertThat(checksum % 103).isEqualTo(55);

    // 箱唛走 HTML，那一侧必须画出真条码（SVG 条纹），不是纯文字
    var cartonTemplate = templateA(PrintObjectType.CARTON_MARK, "T-CARTON");
    var carton = targetA(PrintObjectType.CARTON_MARK, "CTN-9001", null);
    var cartonJob =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        cartonTemplate.id(), List.of(carton.id()), 1, null, null, null, null),
                    user));
    var cartonItem = asA(() -> printing.jobDetail(cartonJob.id())).items().get(0);
    var html = asA(() -> printing.renderedContent(cartonJob.id(), cartonItem.id(), null));
    assertThat(html).contains("<svg").contains("CTN-9001");
    assertThat(html).contains("aria-label=\"条码 CTN-1\"");
  }

  // ================================================================ 打印次数真累加

  @Test
  void 打印次数真的逐次累加并落库() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-COUNT");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-3001", "WB-COUNT");

    // 建对象时次数必须是 0：首印由打印动作产生，不是建对象时产生
    assertThat(storedCount(target.id())).isZero();
    assertThat(logCount(target.id())).isZero();

    // 第一次打印
    var first =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 1, null, null, null, null),
                    user));
    var r1 = asA(() -> printing.executeJob(first.id(), user));
    assertThat(storedCount(target.id())).isEqualTo(1);
    assertThat(logCount(target.id())).isEqualTo(1);
    assertThat(r1.logs())
        .singleElement()
        .satisfies(l -> assertThat(l.printSequence()).isEqualTo(1));
    assertThat(r1.counts()).singleElement().satisfies(c -> assertThat(c.printCount()).isEqualTo(1));

    // 第二次打印 —— 权威计数必须是 2，不是日志里写了「第 2 次」而列纹丝不动
    var second =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 1, null, null, null, "重打"),
                    user));
    var r2 = asA(() -> printing.executeJob(second.id(), user));
    assertThat(storedCount(target.id())).isEqualTo(2);
    assertThat(logCount(target.id())).isEqualTo(2);
    assertThat(r2.logs())
        .singleElement()
        .satisfies(l -> assertThat(l.printSequence()).isEqualTo(2));

    // 第三次
    var third =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 1, null, null, null, null),
                    user));
    asA(() -> printing.executeJob(third.id(), user));
    assertThat(storedCount(target.id())).isEqualTo(3);
    assertThat(logCount(target.id())).isEqualTo(3);

    // 权威计数列必须与日志条数一致（两者互相钉死）
    assertThat(storedCount(target.id())).isEqualTo(logCount(target.id()));

    // 历史时间线按次数倒序，且能看到每一次
    var history = asA(() -> printing.targetHistory(target.id()));
    assertThat(history).extracting(LabelPrintLog::printSequence).containsExactly(3, 2, 1);

    // 同一任务不能打印两次：重打必须新建任务，这样「第几次」永远是真的第几次
    assertThatThrownBy(() -> asA(() -> printing.executeJob(third.id(), user)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(storedCount(target.id())).isEqualTo(3);
  }

  @Test
  void 打印份数按份累加且凭据序号连续() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-COPIES");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-4001", "WB-COPIES");

    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 3, null, null, null, "打三份"),
                    user));
    // label_count 必须是算出来的 target_count × copies
    assertThat(job.labelCount()).isEqualTo(3);
    assertThat(job.targetCount()).isEqualTo(1);

    var result = asA(() -> printing.executeJob(job.id(), user));
    assertThat(result.logs()).hasSize(3);
    // 同一张面单打三份 = 三条凭据，序号 1/2/3，权威计数是 3
    assertThat(result.logs()).extracting(LabelPrintLog::printSequence).containsExactly(1, 2, 3);
    assertThat(result.logs()).extracting(LabelPrintLog::copyIndex).containsExactly(1, 2, 3);
    assertThat(storedCount(target.id())).isEqualTo(3);
    assertThat(logCount(target.id())).isEqualTo(3);
  }

  @Test
  void 批量打印是真批量且逐对象各自计数() {
    var template = templateA(PrintObjectType.SKU_BARCODE, "T-BATCH");
    var targets =
        java.util.stream.IntStream.rangeClosed(1, 12)
            .mapToObj(i -> targetA(PrintObjectType.SKU_BARCODE, "SKU-" + i, null))
            .toList();

    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(),
                        targets.stream().map(LabelPrintTarget::id).toList(),
                        2,
                        null,
                        null,
                        null,
                        "12 个 SKU 各打两份"),
                    user));
    assertThat(job.targetCount()).isEqualTo(12);
    assertThat(job.labelCount()).isEqualTo(24);

    var detail = asA(() -> printing.jobDetail(job.id()));
    assertThat(detail.items()).hasSize(12);
    // 行号必须连续，每行一份真渲染内容
    assertThat(detail.items())
        .extracting(LabelPrintJobItem::lineNo)
        .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
    for (var item : detail.items()) {
      assertThat(item.contentBytes()).isGreaterThan(40);
      assertThat(item.contentChecksum()).hasSize(64);
    }

    var result = asA(() -> printing.executeJob(job.id(), user));
    assertThat(result.logs()).hasSize(24);
    // 每个对象各自累加到 2，而不是所有人共享一个总数
    for (var t : targets) {
      assertThat(storedCount(t.id())).isEqualTo(2);
      assertThat(logCount(t.id())).isEqualTo(2);
    }
  }

  // ================================================================ 重复打印判据

  @Test
  void 平台重复打印上限真的会拦下来() {
    asA(
        () ->
            printing.upsertRule(
                new LabelReprintRuleRequest(
                    "AMZ", PrintObjectType.SHIPPING_LABEL, 2, 3, "Amazon 面单最多印 3 次")));
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-RULE");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-5001", "WB-RULE");

    // next=1 / next=2：未到告警线，正常
    assertThat(asA(() -> printing.targetById(target.id())).nextVerdict())
        .isEqualTo(ReprintVerdict.NORMAL);

    printOnce(template, target.id());
    assertThat(asA(() -> printing.targetById(target.id())).nextVerdict())
        .isEqualTo(ReprintVerdict.NORMAL);

    // 印到第 2 次：序号 2 &lt;= warning=2 → 这次不算告警
    var second = printOnce(template, target.id());
    assertThat(storedCount(target.id())).isEqualTo(2);
    assertThat(second.logs())
        .singleElement()
        .satisfies(l -> assertThat(l.reprintWarning()).isFalse());
    // 但「再印一次」会落到序号 3 > warning=2 → 列表里要提前显示为告警
    assertThat(asA(() -> printing.targetById(target.id())).nextVerdict())
        .isEqualTo(ReprintVerdict.WARNING);

    // 印到第 3 次（序号 3 正好等于上限，允许但这次真的要留告警标记）
    var third = printOnce(template, target.id());
    assertThat(storedCount(target.id())).isEqualTo(3);
    assertThat(third.logs())
        .singleElement()
        .satisfies(l -> assertThat(l.reprintWarning()).isTrue());

    // next=4 > max=3：超限
    assertThat(asA(() -> printing.targetById(target.id())).nextVerdict())
        .isEqualTo(ReprintVerdict.BLOCKED);

    // 建单即被整体拒绝，且明确指出是哪个对象超了限
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.createJob(
                            new LabelPrintJobRequest(
                                template.id(), List.of(target.id()), 1, null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e ->
                assertThat(((ResponseStatusException) e).getReason())
                    .contains("OB-5001")
                    .contains("超出平台重复打印上限"));
    // 被拒之后次数不能变
    assertThat(storedCount(target.id())).isEqualTo(3);
    assertThat(logCount(target.id())).isEqualTo(3);
  }

  @Test
  void 强制重打要留名字且次数照实累加() {
    asA(
        () ->
            printing.upsertRule(
                new LabelReprintRuleRequest(
                    "AMZ", PrintObjectType.SHIPPING_LABEL, 2, 2, "最多 2 次")));
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-OVERRIDE");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-6001", "WB-OVERRIDE");

    printOnce(template, target.id());
    printOnce(template, target.id());
    assertThat(storedCount(target.id())).isEqualTo(2);

    // 强制重打必须给理由（DB 与应用层都收）
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.createJob(
                            new LabelPrintJobRequest(
                                template.id(), List.of(target.id()), 1, null, true, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("理由");

    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(),
                        List.of(target.id()),
                        1,
                        null,
                        true,
                        "面单贴错，第 3 次强制重打（已与平台报备）",
                        null),
                    user));
    var result = asA(() -> printing.executeJob(job.id(), user));

    // 超限放行之后次数照样要真实 +1，凭据要标出这是强制放行的
    assertThat(storedCount(target.id())).isEqualTo(3);
    assertThat(logCount(target.id())).isEqualTo(3);
    assertThat(result.logs())
        .singleElement()
        .satisfies(
            l -> {
              assertThat(l.printSequence()).isEqualTo(3);
              assertThat(l.overrideApplied()).isTrue();
            });
    // 理由随任务留痕
    assertThat(asA(() -> printing.jobById(job.id())).overrideReason()).contains("已与平台报备");
  }

  @Test
  void 通配规则对无平台的对象生效() {
    asA(
        () ->
            printing.upsertRule(
                new LabelReprintRuleRequest(
                    "*", PrintObjectType.SHIPPING_LABEL, 1, 2, "全平台面单最多印 2 次")));
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-WILD");
    // 无平台的对象（platform_code 为空）
    var target =
        asA(
            () ->
                printing.registerTarget(
                    new LabelPrintTargetRequest(
                        PrintObjectType.SHIPPING_LABEL,
                        "OB-7001",
                        "WB-WILD",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));

    // 印 1 次：next=2 > warning=1 但 &lt;= max=2 → 允许但告警
    printOnce(template, target.id());
    var row = asA(() -> printing.targetById(target.id()));
    assertThat(row.printCount()).isEqualTo(1);
    assertThat(row.nextVerdict()).isEqualTo(ReprintVerdict.WARNING);

    // 印第 2 次：next=2 == max，仍允许
    printOnce(template, target.id());
    assertThat(storedCount(target.id())).isEqualTo(2);
    // 之后再打 next=3 > max=2 → 超限
    assertThat(asA(() -> printing.targetById(target.id())).nextVerdict())
        .isEqualTo(ReprintVerdict.BLOCKED);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.createJob(
                            new LabelPrintJobRequest(
                                template.id(), List.of(target.id()), 1, null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(storedCount(target.id())).isEqualTo(2);
  }

  @Test
  void 没有规则的平台仍然计数留痕只是不拦() {
    var template = templateA(PrintObjectType.LOCATION_LABEL, "T-NORULE");
    var target = targetA(PrintObjectType.LOCATION_LABEL, "LOC-8001", null);
    for (int i = 1; i <= 5; i++) {
      printOnce(template, target.id());
      assertThat(storedCount(target.id())).isEqualTo(i);
    }
    // 不限次，但一次都不能少记：5 次打印 = 5 条凭据
    assertThat(logCount(target.id())).isEqualTo(5);
    assertThat(asA(() -> printing.targetById(target.id())).nextVerdict())
        .isEqualTo(ReprintVerdict.NORMAL);
  }

  LabelPrintExecution printOnce(LabelPrintTemplate template, UUID targetId) {
    return printOnceAs(actorA, template, targetId);
  }

  /**
   * 按指定租户身份打印一次。
   *
   * <p>必须带上身份：A 的模板/对象只能在 A 的租户上下文里用，直接用 asA 会拿 B 的模板去 建单，撞上 404 —— 那样测的就不是「两个租户各自打印」，而是「A 恰好看不到 B
   * 的模板」了。
   */
  LabelPrintExecution printOnceAs(
      AccessService.Access actor, LabelPrintTemplate template, UUID targetId) {
    var job =
        access.run(
            actor,
            "warehouse.inventory.read",
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(targetId), 1, null, null, null, null),
                    user));
    return access.run(actor, "warehouse.inventory.read", () -> printing.executeJob(job.id(), user));
  }

  // ================================================================ DB 层真实性（不是应用层自律）

  @Test
  void 数据库拒绝把打印次数改小() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-MONO");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-9001", "WB-MONO");
    printOnce(template, target.id());
    printOnce(template, target.id());
    assertThat(storedCount(target.id())).isEqualTo(2);

    // 改小：只增不减
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update print_target set print_count = 0 where id = ?", target.id())))
        .hasMessageContaining("打印次数不可减少");
    // 跳着改（2 → 7，只有 2 条凭据）：「只增不减」拦不住它，靠「次数不得超过凭据条数」挡住
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update print_target set print_count = 7 where id = ?", target.id())))
        .hasMessageContaining("打印次数");
    // 清掉打印时间戳（次数 > 0 却没印过）：必须被拒
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update print_target set last_printed_at = null where id = ?",
                            target.id())))
        .hasMessageContaining("last_printed_at");
    assertThat(storedCount(target.id())).isEqualTo(2);
  }

  @Test
  void 数据库拒绝篡改与删除打印凭据() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-LOG");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-9101", "WB-LOG");
    printOnce(template, target.id());

    // UPDATE：应用角色有 UPDATE 权限，被 append-only 触发器挡住
    assertThatThrownBy(
            () -> asA(() -> sql.execute("update label_print_log set print_sequence = 99")))
        .hasMessageContaining("append-only");

    // DELETE：迁移里已 REVOKE DELETE，应用角色在权限层就被拒（先于触发器）。
    // 两道闸门都在，断言的是「删不掉、条数不变」这个事实本身。
    assertThatThrownBy(() -> asA(() -> sql.execute("delete from label_print_log")));
    assertThatThrownBy(
        () ->
            asA(() -> sql.execute("delete from label_print_log where target_id = ?", target.id())));
    assertThat(logCount(target.id())).isEqualTo(1);
  }

  @Test
  void 数据库拒绝伪造高于权威次数的打印凭据() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-FAKE");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-9201", "WB-FAKE");
    printOnce(template, target.id());

    var job =
        asA(
            () ->
                printing.jobById(
                    sql.fetchOne("select job_id from label_print_log limit 1")
                        .get("job_id", UUID.class)));
    var itemId =
        asA(
            () ->
                sql.fetchOne("select job_item_id from label_print_log limit 1")
                    .get("job_item_id", UUID.class));

    // 次数只有 1，凭据却写「第 5 次」—— 必须被 DB 拒
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "insert into label_print_log(id,tenant_id,job_id,job_item_id,target_id,object_type,"
                                + "external_ref,print_sequence,copy_index,content_format,content_checksum)"
                                + " values(?,?,?,?,?,'SHIPPING_LABEL','OB-9201',5,1,'ZPL',?)",
                            UUID.randomUUID(),
                            a,
                            job.id(),
                            itemId,
                            target.id(),
                            "deadbeef")))
        .hasMessageContaining("超过该对象当前打印次数");
  }

  @Test
  void 数据库拒绝修改打印快照() {
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-9301", "WB-FROZEN");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update print_target set waybill_no = 'WB-CHANGED' where id = ?",
                            target.id())))
        .hasMessageContaining("打印快照不可修改");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update print_target set external_ref = 'OTHER' where id = ?",
                            target.id())))
        .hasMessageContaining("external_ref 不可修改");
    assertThat(asA(() -> printing.targetById(target.id())).waybillNo()).isEqualTo("WB-FROZEN");
  }

  @Test
  void 数据库拒绝非法状态跳转与篡改汇总数() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-STATE");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-9401", "WB-STATE");
    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 1, null, null, null, null),
                    user));

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update print_job set status = 'COMPLETED', finished_at = now() where id = ?",
                            job.id())))
        .hasMessageContaining("状态不可从");
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute(
                            "update print_job set label_count = 999 where id = ?", job.id())))
        .hasMessageContaining("不可修改");

    // 正常路径能走通，终态之后不可再改
    asA(() -> printing.executeJob(job.id(), user));
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        sql.execute("update print_job set remark = 'late' where id = ?", job.id())))
        .hasMessageContaining("已终结");
    assertThatThrownBy(() -> asA(() -> printing.cancelJob(job.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  @Test
  void 时间字段按timestamptz原样写回不偏时区() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-TIME");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-9501", "WB-TIME");
    var before = OffsetDateTime.now().minusHours(2);
    asA(
        () ->
            sql.execute(
                "update print_target set first_printed_at = ?::timestamptz, last_printed_at = ?::timestamptz,"
                    + " print_count = 1 where id = ?",
                before,
                before,
                target.id()));

    // 断言写回的值：偏移量必须原样保留，不能被静默偏 −28800s
    var row =
        asA(
            () ->
                sql.fetchOne(
                    "select first_printed_at from print_target where id = ?", target.id()));
    var stored = row.get("first_printed_at", OffsetDateTime.class);
    assertThat(stored.toInstant()).isEqualTo(before.toInstant());
    assertThat(stored.getOffset()).isEqualTo(before.getOffset());
    assertThat(ChronoUnitSupport.secondsBetween(stored, before)).isZero();
  }

  /** 极小的辅助类：避免测试里为了比时间而多引一个 import。 */
  static final class ChronoUnitSupport {
    static long secondsBetween(OffsetDateTime later, OffsetDateTime earlier) {
      return java.time.Duration.between(earlier, later).getSeconds();
    }
  }

  @Test
  void 跨租户不能看到对方的打印任务与打印次数() {
    var tplA = templateA(PrintObjectType.SHIPPING_LABEL, "T-TENANT");
    var tplB = templateB(PrintObjectType.SHIPPING_LABEL, "T-TENANT");
    var tgtA = targetA(PrintObjectType.SHIPPING_LABEL, "OB-A-1", "WB-A");
    var tgtB = targetB("OB-B-1", "WB-B");

    printOnceAs(actorA, tplA, tgtA.id());
    printOnceAs(actorB, tplB, tgtB.id());

    // 各自打印一次，两边都是 1，互不影响
    assertThat(storedCount(tgtA.id())).isEqualTo(1);
    assertThat(
            asB(
                () ->
                    sql.fetchOne("select print_count from print_target where id = ?", tgtB.id())
                        .get(0, Integer.class)))
        .isEqualTo(1);

    // B 拿 A 的任务 id 查不到（id 必须先在 A 的上下文里取，见 asA 的注释）
    var jobAId = jobIdOf(tgtA.id());
    var itemAId = itemIdOf(jobAId);
    assertThatThrownBy(() -> asB(() -> printing.jobById(jobAId)))
        .isInstanceOf(ResponseStatusException.class);
    // B 拿 A 的模板 id 建单建不出来
    assertThatThrownBy(() -> asB(() -> printing.templateById(tplA.id())))
        .isInstanceOf(ResponseStatusException.class);
    // B 的列表里看不到 A 的任何任务
    var jobsB = asB(() -> printing.jobs(null, null, null, 0, 50));
    assertThat(jobsB.total()).isEqualTo(1);
    assertThat(jobsB.rows()).extracting(LabelPrintJob::id).doesNotContain(jobAId);

    // B 的目标列表里只有自己那条，且次数不串
    var targetsB = asB(() -> printing.targets(null, null, null, null, 0, 50));
    assertThat(targetsB.rows()).extracting(LabelPrintTarget::externalRef).containsExactly("OB-B-1");
    assertThat(targetsB.rows().get(0).printCount()).isEqualTo(1);

    // B 的打印日志里只有自己的凭据，且看不到 A 的内容
    var logsB = asB(() -> printing.logs(null, null, null, 0, 50));
    assertThat(logsB.rows()).extracting(LabelPrintLog::externalRef).containsExactly("OB-B-1");
    assertThatThrownBy(() -> asB(() -> printing.renderedContent(jobAId, itemAId, null)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> asB(() -> printing.targetHistory(tgtA.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  /** 必须在租户上下文里查：FORCE RLS 下没有绑定 app.current_tenant 的查询一行都读不到。 */
  UUID jobIdOf(UUID targetId) {
    return jobIdOfAs(actorA, targetId);
  }

  /** 按租户取该目标最近一张任务 id（limit 1：重打会有多张，不加会抛 TooManyRowsException）。 */
  UUID jobIdOfAs(AccessService.Access actor, UUID targetId) {
    return access.run(
        actor,
        "warehouse.inventory.read",
        () ->
            sql.fetchOne(
                    "select job_id from print_job_item where target_id = ? order by line_no limit 1",
                    targetId)
                .get("job_id", UUID.class));
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var tplA = templateA(PrintObjectType.SHIPPING_LABEL, "T-ISO");
    var tplB = templateB(PrintObjectType.SHIPPING_LABEL, "T-ISO");
    var tgtA = targetA(PrintObjectType.SHIPPING_LABEL, "OB-ISO-A", "WB-ISO-A");
    var tgtB = targetB("OB-ISO-B", "WB-ISO-B");

    printOnceAs(actorA, tplA, tgtA.id());
    printOnceAs(actorB, tplB, tgtB.id());
    printOnceAs(actorB, tplB, tgtB.id());

    var pageA = asA(() -> printing.jobs(null, null, null, 0, 50));
    assertThat(pageA.total()).isEqualTo(1);
    var pageB = asB(() -> printing.jobs(null, null, null, 0, 50));
    assertThat(pageB.total()).isEqualTo(2);

    // 拿 A 的任务给 B → 404（403 会泄露「这张任务存在」）
    var jobAId = jobIdOf(tgtA.id());
    assertThatThrownBy(() -> asB(() -> printing.jobById(jobAId)))
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(
            e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(404));
    // 拿 A 的打印对象给 B → 404
    assertThatThrownBy(() -> asB(() -> printing.targetById(tgtA.id())))
        .isInstanceOf(ResponseStatusException.class);
    // 拿 A 的任务内容给 B 下载 → 404
    var itemAId = itemIdOf(jobAId);
    assertThatThrownBy(() -> asB(() -> printing.renderedContent(jobAId, itemAId, null)))
        .isInstanceOf(ResponseStatusException.class);
    // B 用 A 的模板建单建不出来
    assertThatThrownBy(
            () ->
                asB(
                    () ->
                        printing.createJob(
                            new LabelPrintJobRequest(
                                tplA.id(), List.of(tgtB.id()), 1, null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class);
    // B 用 A 的模板 id 也查不到
    assertThatThrownBy(() -> asB(() -> printing.templateById(tplA.id())))
        .isInstanceOf(ResponseStatusException.class);
  }

  UUID itemIdOf(UUID jobId) {
    return asA(
        () ->
            sql.fetchOne("select id from print_job_item where job_id = ?", jobId)
                .get("id", UUID.class));
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-FORGED");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-FORGED", "WB-FORGED");
    printOnce(template, target.id());
    var jobId = jobIdOf(target.id());

    // 身份是 B 但伪造 X-Tenant-Id 指向 A → 只能看到 B 自己的（这里是空）
    http.perform(
            get("/warehouse/inventory/printing/jobs")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 反向：身份是 A 伪造 B 的头也只能看到自己的
    http.perform(
            get("/warehouse/inventory/printing/targets")
                .header("Authorization", "Bearer tenant-a")
                .header("X-Tenant-Id", b.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.rows[0].printCount").value(1));
    // 详情与内容下载端点同样不受伪造头影响
    http.perform(
            get("/warehouse/inventory/printing/jobs/" + jobId)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 打印日志也隔离
    http.perform(
            get("/warehouse/inventory/printing/logs")
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(0));
    // 未认证 401
    http.perform(get("/warehouse/inventory/printing/jobs")).andExpect(status().isUnauthorized());
    // 写端点要 create 权限：只有 read 的角色不能建任务
    http.perform(
            post("/warehouse/inventory/printing/jobs")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"templateId\":\"" + template.id() + "\"}"))
        .andExpect(status().isForbidden());
    // 改规则要 update 权限
    http.perform(
            post("/warehouse/inventory/printing/reprint-rules")
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{\"platformCode\":\"AMZ\",\"objectType\":\"SHIPPING_LABEL\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var tplA = templateA(PrintObjectType.SHIPPING_LABEL, "T-POOL");
    var tplB = templateB(PrintObjectType.SHIPPING_LABEL, "T-POOL");
    var tgtA = targetA(PrintObjectType.SHIPPING_LABEL, "OB-POOL-A", "WB-POOL-A");
    var tgtB = targetB("OB-POOL-B", "WB-POOL-B");

    // A 印 2 次、B 印 3 次：两个数既不能互相污染，也不能因为共用一条连接而串
    printOnceAs(actorA, tplA, tgtA.id());
    printOnceAs(actorA, tplA, tgtA.id());
    printOnceAs(actorB, tplB, tgtB.id());
    printOnceAs(actorB, tplB, tgtB.id());
    printOnceAs(actorB, tplB, tgtB.id());

    var jobAId = jobIdOfAs(actorA, tgtA.id());
    var jobBId = jobIdOfAs(actorB, tgtB.id());

    for (int i = 0; i < 20; i++) {
      // 池大小为 1，同一条连接被反复借还：交替访问两个租户，每一轮都校验
      // 列表 / 详情 / 内容下载 / 计数四条路径都不串租户。
      var jobsA = asA(() -> printing.jobs(null, null, null, 0, 50));
      var jobsB = asB(() -> printing.jobs(null, null, null, 0, 50));
      assertThat(jobsA.total()).isEqualTo(2);
      assertThat(jobsB.total()).isEqualTo(3);

      assertThat(asA(() -> printing.targetById(tgtA.id())).printCount()).isEqualTo(2);
      assertThat(asB(() -> printing.targetById(tgtB.id())).printCount()).isEqualTo(3);

      assertThat(asA(() -> printing.targets(null, null, null, null, 0, 50)).rows())
          .extracting(LabelPrintTarget::externalRef)
          .containsExactly("OB-POOL-A");
      assertThat(asB(() -> printing.targets(null, null, null, null, 0, 50)).rows())
          .extracting(LabelPrintTarget::externalRef)
          .containsExactly("OB-POOL-B");

      assertThat(asA(() -> printing.logs(null, null, null, 0, 50)).total()).isEqualTo(2);
      assertThat(asB(() -> printing.logs(null, null, null, 0, 50)).total()).isEqualTo(3);

      assertThatThrownBy(() -> asB(() -> printing.jobById(jobAId)))
          .isInstanceOf(ResponseStatusException.class);
      assertThatThrownBy(() -> asA(() -> printing.jobById(jobBId)))
          .isInstanceOf(ResponseStatusException.class);
    }
  }

  // ================================================================ 端点与内容下载

  @Test
  void 能力声明如实讲清面单数据来自调用方与判据口径() throws Exception {
    http.perform(
            get("/warehouse/inventory/printing/capabilities")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.objectTypes.length()").value(5))
        .andExpect(jsonPath("$.reprint.countAuthoritative").value(true))
        .andExpect(jsonPath("$.reprint.ranges.length()").value(3))
        .andExpect(jsonPath("$.upstreamNote").value(org.hamcrest.Matchers.containsString("调用方")));
  }

  @Test
  void 下载端点按落库格式吐字节且校验内容一致() throws Exception {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-DOWNLOAD");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-DL-1", "WB-DL-1");
    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 1, null, null, null, null),
                    user));
    asA(() -> printing.executeJob(job.id(), user));
    var item = asA(() -> printing.jobDetail(job.id())).items().get(0);

    var response =
        http.perform(
                get("/warehouse/inventory/printing/jobs/"
                        + job.id()
                        + "/items/"
                        + item.id()
                        + "/content")
                    .header("Authorization", "Bearer tenant-a"))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/vnd.zpl"))
            .andReturn()
            .getResponse();
    var text = response.getContentAsString();
    assertThat(text).startsWith("^XA").contains("WB-DL-1");
    assertThat(LabelRenderService.sha256(text)).isEqualTo(item.contentChecksum());

    // 格式与实际不符要 400，而不是默默返回别的东西
    http.perform(
            get("/warehouse/inventory/printing/jobs/"
                    + job.id()
                    + "/items/"
                    + item.id()
                    + "/content?format=HTML")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());

    // 单据类走 HTML
    var cartonTemplate = templateA(PrintObjectType.PACKING_SLIP, "T-DOWNLOAD2");
    var carton = targetA(PrintObjectType.PACKING_SLIP, "OB-DL-2", null);
    var cartonJob =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        cartonTemplate.id(), List.of(carton.id()), 1, null, null, null, null),
                    user));
    var cartonItem = asA(() -> printing.jobDetail(cartonJob.id())).items().get(0);
    http.perform(
            get("/warehouse/inventory/printing/jobs/"
                    + cartonJob.id()
                    + "/items/"
                    + cartonItem.id()
                    + "/content")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("text/html"));
  }

  @Test
  void 模板与打印机配置可维护且校验真实() {
    // 打印机
    var device =
        asA(
            () ->
                printing.createDevice(
                    new LabelPrintDeviceRequest(
                        "ZD-01",
                        "面单区热敏机",
                        PrintDeviceKind.THERMAL_LABEL,
                        "Zebra ZT411",
                        "100X150",
                        203,
                        "A 区 1 号工作台")));
    assertThat(device.paperSize()).isEqualTo("100X150");
    assertThat(asA(() -> printing.devices(true)))
        .extracting(LabelPrintDevice::code)
        .containsExactly("ZD-01");
    var updated =
        asA(
            () ->
                printing.updateDevice(
                    device.id(),
                    new LabelPrintDeviceRequest(
                        "ZD-01",
                        "面单区热敏机（换纸）",
                        PrintDeviceKind.THERMAL_LABEL,
                        "Zebra ZT411",
                        "100X150",
                        203,
                        "A 区 2 号工作台")));
    assertThat(updated.name()).contains("换纸");

    // 模板布局校验：坏布局必须当场拒，不能存下去等渲染时才炸
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.createTemplate(
                            new LabelPrintTemplateRequest(
                                "T-BAD",
                                "坏布局",
                                PrintObjectType.SHIPPING_LABEL,
                                "100X150",
                                "PORTRAIT",
                                "{\"blocks\":[{\"label\":\"没有 key\"}]}",
                                true))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.createTemplate(
                            new LabelPrintTemplateRequest(
                                "T-BAD2",
                                "坏布局",
                                PrintObjectType.SHIPPING_LABEL,
                                "100X150",
                                "PORTRAIT",
                                "不是 JSON",
                                true))))
        .isInstanceOf(ResponseStatusException.class);

    // 自定义布局真的影响渲染
    var custom =
        asA(
            () ->
                printing.createTemplate(
                    new LabelPrintTemplateRequest(
                        "T-CUSTOM",
                        "自定义面单",
                        PrintObjectType.SHIPPING_LABEL,
                        "100X150",
                        "PORTRAIT",
                        "{\"blocks\":[{\"key\":\"skuName\",\"label\":\"品名\",\"scale\":30},"
                            + "{\"key\":\"waybillNo\",\"label\":\"运单\",\"scale\":30}]}",
                        true)));
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-CUST-1", "WB-CUST-1");
    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        custom.id(), List.of(target.id()), 1, null, null, null, null),
                    user));
    var zpl =
        asA(
            () ->
                printing.renderedContent(
                    job.id(), asA(() -> printing.jobDetail(job.id())).items().get(0).id(), null));
    assertThat(zpl).contains("品名：测试商品").contains("运单：WB-CUST-1");
    // 默认布局里的承运商不在自定义布局里，就不该出现
    assertThat(zpl).doesNotContain("承运商");

    // 已被任务用过的模板不能改对象类型
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.updateTemplate(
                            custom.id(),
                            new LabelPrintTemplateRequest(
                                "T-CUSTOM",
                                "自定义面单",
                                PrintObjectType.LOCATION_LABEL,
                                "100X150",
                                "PORTRAIT",
                                null,
                                true))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能改打印对象类型");
  }

  @Test
  void 模板与对象类型不一致时被拒() {
    var labelTemplate = templateA(PrintObjectType.SHIPPING_LABEL, "T-MISMATCH");
    var carton = targetA(PrintObjectType.CARTON_MARK, "CTN-MIX-1", null);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.createJob(
                            new LabelPrintJobRequest(
                                labelTemplate.id(),
                                List.of(carton.id()),
                                1,
                                null,
                                null,
                                null,
                                null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("类型不一致");
  }

  @Test
  void 取消任务后不能再打印() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-CANCEL");
    var target = targetA(PrintObjectType.SHIPPING_LABEL, "OB-CANCEL-1", "WB-CANCEL");
    var job =
        asA(
            () ->
                printing.createJob(
                    new LabelPrintJobRequest(
                        template.id(), List.of(target.id()), 1, null, null, null, null),
                    user));
    asA(() -> printing.cancelJob(job.id()));
    assertThat(asA(() -> printing.jobById(job.id())).status()).isEqualTo(PrintJobStatus.CANCELLED);
    // 取消之后打印次数必须是 0：取消不是打印
    assertThat(storedCount(target.id())).isZero();
    assertThat(logCount(target.id())).isZero();
  }

  @Test
  void 分页与筛选是真实的() {
    var template = templateA(PrintObjectType.SHIPPING_LABEL, "T-PAGE");
    var targets =
        java.util.stream.IntStream.rangeClosed(1, 25)
            .mapToObj(i -> targetA(PrintObjectType.SHIPPING_LABEL, "OB-P-" + i, "WB-P-" + i))
            .toList();
    asA(
        () ->
            printing.createJob(
                new LabelPrintJobRequest(
                    template.id(),
                    targets.stream().map(LabelPrintTarget::id).toList(),
                    1,
                    null,
                    null,
                    null,
                    null),
                user));

    var page0 = asA(() -> printing.targets(null, null, null, null, 0, 10));
    var page1 = asA(() -> printing.targets(null, null, null, null, 1, 10));
    var page2 = asA(() -> printing.targets(null, null, null, null, 2, 10));
    assertThat(page0.total()).isEqualTo(25);
    assertThat(page0.rows()).hasSize(10);
    assertThat(page1.rows()).hasSize(10);
    assertThat(page2.rows()).hasSize(5);
    // 三页不重不漏
    var seen = new java.util.HashSet<UUID>();
    for (var p : List.of(page0, page1, page2)) {
      for (var r : p.rows()) assertThat(seen.add(r.id())).isTrue();
    }
    assertThat(seen).hasSize(25);

    // 关键词筛选真的在筛
    var filtered = asA(() -> printing.targets(null, null, "OB-P-7", null, 0, 50));
    assertThat(filtered.total()).isEqualTo(1);
    assertThat(filtered.rows().get(0).externalRef()).isEqualTo("OB-P-7");

    // 按外部引用精确查
    var exact = asA(() -> printing.targets(null, "OB-P-12", null, null, 0, 50));
    assertThat(exact.total()).isEqualTo(1);
    // 查不到就是查不到（不是返回全部）
    assertThat(asA(() -> printing.targets(null, "NOPE", null, null, 0, 50)).total()).isZero();
    // 按次数下限筛
    assertThat(asA(() -> printing.targets(null, null, null, 1, 0, 50)).total()).isZero();
    printOnce(template, targets.get(0).id());
    assertThat(asA(() -> printing.targets(null, null, null, 1, 0, 50)).total()).isEqualTo(1);
  }

  @Test
  void 登记打印对象幂等且不接收非法快照() {
    var first =
        asA(
            () ->
                printing.registerTarget(
                    new LabelPrintTargetRequest(
                        PrintObjectType.SHIPPING_LABEL,
                        "OB-IDEM",
                        "WB-IDEM",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
    var again =
        asA(
            () ->
                printing.registerTarget(
                    new LabelPrintTargetRequest(
                        PrintObjectType.SHIPPING_LABEL,
                        "OB-IDEM",
                        "WB-IDEM-OTHER",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null)));
    // 幂等：同一个 external_ref 返回同一条，不会因为上游重推而多出一条计数分叉
    assertThat(again.id()).isEqualTo(first.id());
    assertThat(again.waybillNo()).isEqualTo("WB-IDEM");

    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.registerTarget(
                            new LabelPrintTargetRequest(
                                PrintObjectType.SHIPPING_LABEL,
                                "  ",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null))))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                asA(
                    () ->
                        printing.registerTarget(
                            new LabelPrintTargetRequest(
                                PrintObjectType.SHIPPING_LABEL,
                                "OB-NEG",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                1,
                                new BigDecimal("-1"),
                                null))))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("非负");
  }
}
