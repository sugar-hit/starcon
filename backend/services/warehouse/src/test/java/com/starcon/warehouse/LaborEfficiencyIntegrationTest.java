package com.starcon.warehouse;

import static com.starcon.warehouse.LaborEfficiencyModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
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
 * 8.5.1.1 作业效率：作业记录写入入口、五个指标的精确口径、人效报表/排名/趋势， 以及租户隔离三断言。
 *
 * <p>真实 PostgreSQL 16 + 强制 RLS，不使用任何内存替身。连接池固定为 1 条连接： 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>这个模块的交付物是「口径」，所以测试主要在钉口径而不是钉行数</b>：
 *
 * <ul>
 *   <li>打包时效是<b>墙钟时长</b>、分母是<b>任务张数</b>、异常终止与无开工时刻的任务不计入
 *   <li>复核准确率分母是<b>复核件数</b>，分母为 0 时是 null 而不是 0%
 *   <li>差错率按件数与按单量两个口径，分母各自写明
 *   <li>人均日的分母是「人数 × <b>作业天数</b>」，停工日不进分母
 *   <li>「日」按 Asia/Shanghai 归属完成时刻
 * </ul>
 *
 * <p>另外必须真的去数据库里硬写，证明 V74 的约束是<b>可执行</b>的而不是注释里的一句承诺： 终态计量冻结、已开工不许换人、事件 append-only（{@link
 * #数据库层钉死人效凭据()}）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class LaborEfficiencyIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
  static final UUID user = UUID.randomUUID();

  /** 第二个操作人：验证「按人」分组与排名真的按人分开，而不是全员一行。 */
  static final UUID worker = UUID.randomUUID();

  static HttpServer identity;
  static final Map<String, AccessService.Access> actors = new ConcurrentHashMap<>();
  static final List<String> allPermissions =
      Stream.of("inventory")
          .flatMap(
              p ->
                  Stream.of("read", "create", "update", "delete")
                      .map(op -> "warehouse." + p + "." + op))
          .toList();

  /** 口径时区：所有「日」断言都以它为准，与 V74 的聚合表达式同一个字符串。 */
  static final ZoneId ZONE = ZoneId.of(LaborEfficiencyModels.TIMEZONE);

  /** 夹具里的时刻一律按口径时区构造：否则「昨天 10 点」会随机器时区漂。 */
  static final ZoneOffset SHANGHAI = ZoneOffset.ofHours(8);

  static final UUID whOne = idFor(a, 4);
  static final UUID whTwo = idFor(a, 5);

  /** 确定性夹具 id：同一个 (租户, 序号) 永远同一个 uuid，否则测试里的仓库 id 会指不到行。 */
  static UUID idFor(UUID tenant, int ordinal) {
    return UUID.nameUUIDFromBytes(("starcon-warehouse-labor-" + tenant + "-" + ordinal).getBytes());
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
            byte[] body =
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsBytes(access);
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

  @Autowired LaborEfficiencyService service;
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
  void seed() throws Exception {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    // TRUNCATE 不触发行级触发器，以超级用户连接执行可绕过 FORCE RLS ——
    // 只用于测试夹具重建，不给应用角色开这个口子。
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = c.createStatement()) {
      s.execute(
          "truncate warehouse_work_event, warehouse_work_task, warehouse, legal_entity_ref"
              + " restart identity cascade");
    }
    for (var tenant : List.of(a, b)) {
      access.run(
          actorFor(tenant),
          "warehouse.inventory.read",
          () -> {
            sql.execute(
                "insert into legal_entity_ref(id,tenant_id,code,name) values(?,?,?,?)",
                idFor(tenant, 1),
                tenant,
                "LE-1",
                "主体一");
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 4),
                tenant,
                "WH-1",
                "主仓",
                "CN",
                LaborEfficiencyModels.TIMEZONE);
            sql.execute(
                "insert into warehouse(id,tenant_id,code,name,country_code,timezone)"
                    + " values(?,?,?,?,?,?)",
                idFor(tenant, 5),
                tenant,
                "WH-2",
                "次仓",
                "CN",
                LaborEfficiencyModels.TIMEZONE);
            return null;
          });
    }
  }

  AccessService.Access actorFor(UUID tenant) {
    return tenant.equals(a) ? actorA : actorB;
  }

  /** 在某租户的上下文里直接跑 SQL：用来验证**数据库层**真的挡住了某些写入。 */
  private void raw(UUID tenant, String statement, Object... binds) {
    access.run(
        actorFor(tenant),
        "warehouse.inventory.read",
        () -> {
          sql.execute(statement, binds);
          return null;
        });
  }

  /** 以迁移角色（超级用户）直连：用来证明触发器对**绕过权限**的连接同样生效。 */
  private void superuser(String statement, Object... binds) throws Exception {
    try (var c = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword())) {
      var ps = c.prepareStatement(statement);
      for (int i = 0; i < binds.length; i++) {
        ps.setObject(i + 1, binds[i]);
      }
      ps.execute();
    }
  }

  private <T> T query(UUID tenant, java.util.function.Supplier<T> work) {
    return access.run(actorFor(tenant), "warehouse.inventory.read", work);
  }

  private UUID whOneOf(UUID tenant) {
    return query(
        tenant,
        () -> sql.fetchOne("select id from warehouse where code = 'WH-1'").get("id", UUID.class));
  }

  // ================================================================ 写入入口

  @Test
  void 作业记录有真实写入入口且时间写回正确() {
    var task = create(LaborWorkType.PICKING, whOne, user, "OUT-001", 3, 5, "120");
    assertThat(task.status()).isEqualTo(WorkTaskStatus.PENDING);
    assertThat(task.code())
        .startsWith("WT-")
        .contains(task.id().toString().replace("-", "").substring(0, 8));
    assertThat(task.sourceModule()).isEqualTo("8.5.1.1");
    assertThat(task.operatorUserId()).isEqualTo(user);
    assertThat(task.warehouseCode()).isEqualTo("WH-1");

    var started = start(task.id());
    assertThat(started.status()).isEqualTo(WorkTaskStatus.RUNNING);
    assertThat(started.startedAt()).isNotNull();

    var done = complete(task.id(), 3, 5, "120", "45.500", "2", 1);
    assertThat(done.status()).isEqualTo(WorkTaskStatus.COMPLETED);
    assertThat(done.completedAt()).isNotNull();
    assertThat(done.pieceCount()).isEqualByComparingTo("120.000000");
    assertThat(done.travelDistanceM()).isEqualByComparingTo("45.500");
    assertThat(done.errorPieceCount()).isEqualByComparingTo("2.000000");
    assertThat(done.errorOrderCount()).isEqualTo(1);
    // 「日」= 完成时刻在 Asia/Shanghai 下的自然日，写完立刻回查必须是今天。
    assertThat(done.workDay()).isEqualTo(LocalDate.now(ZONE));
    assertThat(done.durationSeconds()).isNotNull().isGreaterThanOrEqualTo(BigDecimal.ZERO);

    // 时间戳写回的是**带偏移的时刻**：拿库里的原值与服务端 now() 比。
    // 差值超过 120 秒就说明绑定偏了（Timestamp.valueOf 会静默偏 −28800s）。
    var now = OffsetDateTime.now(ZONE);
    var startedAt =
        query(
            a,
            () ->
                sql.fetchOne("select started_at from warehouse_work_task where id = ?", task.id())
                    .get("started_at", OffsetDateTime.class));
    var completedAt =
        query(
            a,
            () ->
                sql.fetchOne("select completed_at from warehouse_work_task where id = ?", task.id())
                    .get("completed_at", OffsetDateTime.class));
    assertThat(Math.abs(Duration.between(startedAt, now).toSeconds())).isLessThan(120);
    assertThat(Math.abs(Duration.between(completedAt, now).toSeconds())).isLessThan(120);
  }

  @Test
  void 事件时间线按顺序留痕() {
    var task = create(LaborWorkType.PACKING, whOne, user, null, 0, 0, null);
    start(task.id());
    complete(task.id(), 1, 1, "5", null, "0", 0);
    var detail = read(task.id());
    assertThat(detail.timeline())
        .extracting(LaborWorkEvent::eventType)
        .containsExactly("CREATED", "ASSIGNED", "STARTED", "COMPLETED");
    assertThat(detail.timeline()).allSatisfy(e -> assertThat(e.detail()).isNotBlank());
    assertThat(detail.task().status()).isEqualTo(WorkTaskStatus.COMPLETED);
  }

  @Test
  void 未派工不能开工未开工不能完成() {
    var unassigned =
        access.run(
            actorA,
            "warehouse.inventory.update",
            () ->
                service.create(
                    new LaborWorkTaskCreateInput(
                        "PICKING", whOne, "8.5.1.1", "OUT-9", 1, 1, null, null, null),
                    user));
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.start(unassigned.id(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没派工");

    // 改派给另一个人，然后由他开工。
    assign(unassigned.id(), worker);
    start(unassigned.id());
    assertThat(read(unassigned.id()).task().operatorUserId()).isEqualTo(worker);

    // 重复开工会被 409 挡下：重复记一次开始会让时效被算短一半。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () -> service.start(unassigned.id(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409");

    complete(unassigned.id(), 1, 1, "3", null, "0", 0);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.complete(
                            unassigned.id(),
                            new LaborWorkTaskCompleteInput(
                                1, 1, new BigDecimal("9"), null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有「作业中」");
  }

  @Test
  void 行走距离只属于拣货且差错不得超过件数() {
    var packing = create(LaborWorkType.PACKING, whOne, user, null, 1, 1, null);
    start(packing.id());
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.complete(
                            packing.id(),
                            new LaborWorkTaskCompleteInput(
                                1, 1, new BigDecimal("5"), new BigDecimal("12"), null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只属于拣货作业");

    var picking = create(LaborWorkType.PICKING, whOne, user, null, 1, 1, null);
    start(picking.id());
    // 差错件数超过作业件数：这是口径错了，不是「这个人错得离谱」。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.complete(
                            picking.id(),
                            new LaborWorkTaskCompleteInput(
                                1, 1, new BigDecimal("5"), null, new BigDecimal("9"), null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能超过作业件数");
    // 差错单量超过涉及单量同理。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.complete(
                            picking.id(),
                            new LaborWorkTaskCompleteInput(
                                1, 1, new BigDecimal("5"), null, null, 3, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能超过涉及单量");
  }

  @Test
  void 异常必须写清原因且终态不可重复上报() {
    var task = create(LaborWorkType.PICKING, whOne, user, "OUT-77", 2, 2, null);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.reportException(
                            task.id(),
                            new LaborWorkTaskExceptionInput(
                                "MISSING_STOCK", "缺", null, null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("至少 4 个字");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.reportException(
                            task.id(),
                            new LaborWorkTaskExceptionInput(
                                "NOPE", "货不在货位停工", null, null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的异常类型");

    var done = exception(task.id(), "MISSING_STOCK", "货不在货位，整单停工等待补货", 0, 0, null);
    assertThat(done.status()).isEqualTo(WorkTaskStatus.EXCEPTION);
    assertThat(done.exceptionCode()).isEqualTo("MISSING_STOCK");
    assertThat(done.completedAt()).isNotNull();
    // 没有开工时刻 → 没有可算的时效（不是 0 秒）。
    assertThat(done.durationSeconds()).isNull();

    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.reportException(
                            task.id(),
                            new LaborWorkTaskExceptionInput(
                                "OTHER", "又停了需要复盘", null, null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已定稿");
    assertThat(read(task.id()).timeline())
        .last()
        .extracting(LaborWorkEvent::exceptionCode)
        .isEqualTo("MISSING_STOCK");
  }

  // ================================================================ 五个指标的口径

  @Test
  void 五个指标按人按日聚合() {
    var today = LocalDate.now(ZONE);
    var yesterday = today.minusDays(1);
    // 甲：今天拣货 3 单 120 件走 45.5 米；昨天拣货 2 单 40 件走 20 米。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        yesterday.atTime(10, 0).atOffset(SHANGHAI),
        2,
        2,
        "40",
        "20.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        3,
        3,
        "120",
        "45.500",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    // 乙：今天复核 50 件，错 2 件。差错记在发现它的那张任务上。
    seedFinished(
        a,
        whOne,
        worker,
        LaborWorkType.REVIEW,
        today.atTime(15, 0).atOffset(SHANGHAI),
        1,
        1,
        "50",
        null,
        "2",
        1,
        "COMPLETED",
        null,
        null);

    var report = readReport(yesterday, today, null, null);
    assertThat(report.timezone()).isEqualTo(LaborEfficiencyModels.TIMEZONE);
    assertThat(report.dayBasis()).contains("Asia/Shanghai").contains("已定稿");
    assertThat(report.rows()).hasSize(3);

    var meToday = row(report, user, today);
    assertThat(meToday.taskCount()).isEqualTo(1);
    assertThat(meToday.pickedPieces()).isEqualByComparingTo("120");
    assertThat(meToday.pickedOrders()).isEqualTo(3);
    assertThat(meToday.travelDistanceM()).isEqualByComparingTo("45.500");
    // 甲只做了拣货：打包与复核口径对他全是「不可计算」而不是 0。
    assertThat(meToday.avgPackingSeconds()).isNull();
    assertThat(meToday.reviewAccuracy()).isNull();
    assertThat(meToday.reviewedPieces()).isEqualByComparingTo("0");

    var meYesterday = row(report, user, yesterday);
    assertThat(meYesterday.pickedPieces()).isEqualByComparingTo("40");
    assertThat(meYesterday.pickedOrders()).isEqualTo(2);

    var partner = row(report, worker, today);
    assertThat(partner.pickedPieces()).isEqualByComparingTo("0");
    assertThat(partner.travelDistanceM()).isEqualByComparingTo("0.000");
    assertThat(partner.reviewedPieces()).isEqualByComparingTo("50");
    assertThat(partner.reviewErrorPieces()).isEqualByComparingTo("2");
    // 复核准确率 = (50 − 2) ÷ 50 = 96%
    assertThat(partner.reviewAccuracy()).isEqualByComparingTo("96.0000");

    assertThat(report.summary().headcount()).isEqualTo(2);
    assertThat(report.summary().workDays()).isEqualTo(2);
    assertThat(report.summary().pickedPieces()).isEqualByComparingTo("160");
    assertThat(report.summary().pickedOrders()).isEqualTo(5);
    assertThat(report.summary().travelDistanceM()).isEqualByComparingTo("65.500");
  }

  @Test
  void 打包时效是墙钟时长且分母是任务张数() {
    var today = LocalDate.now(ZONE);
    var base = OffsetDateTime.of(today.atStartOfDay(), ZoneOffset.ofHours(8));
    // 两张打包任务：耗时 600s 与 1200s。分母是张数 → 平均 900s。
    // 若按件数当分母会得到 400s（(600+1200)/4.5 件），与真实口径完全不同。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PACKING,
        base,
        4,
        4,
        "40",
        null,
        "0",
        0,
        "COMPLETED",
        null,
        base.minusSeconds(600));
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PACKING,
        base.plusSeconds(3600),
        4,
        4,
        "40",
        null,
        "0",
        0,
        "COMPLETED",
        null,
        base.plusSeconds(2400));
    // 一张异常终止的打包任务（中途放弃）：不计入时效。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PACKING,
        base.plusSeconds(7200),
        4,
        4,
        "40",
        null,
        "0",
        0,
        "EXCEPTION",
        "MISSING_STOCK",
        base.plusSeconds(0));
    // 一张补录任务（没有开工时刻）：时效不可计算，不是 0 秒。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PACKING,
        base.plusSeconds(10800),
        1,
        1,
        "3",
        null,
        "0",
        0,
        "COMPLETED",
        null,
        null);

    var report = readReport(today, today, null, null);
    var row = row(report, user, today);
    assertThat(row.packingTaskCount()).isEqualTo(4);
    // 分母 = 4 张 − 1 异常 − 1 无开工时刻 = 2 张；分子 600 + 1200 = 1800 秒
    assertThat(row.packingSecondsTotal()).isEqualByComparingTo("1800.000");
    assertThat(row.avgPackingSeconds()).isEqualByComparingTo("900.000");
    assertThat(report.summary().avgPackingSeconds()).isEqualByComparingTo("900.000");

    assertThat(service.capabilities().packingEfficiencyBasisNote())
        .contains("墙钟时长")
        .contains("不是**净作业时长")
        .contains("任务张数");
  }

  @Test
  void 复核准确率分母为零时是null而不是零() {
    var today = LocalDate.now(ZONE);
    // 只做拣货、零差错：复核件数分母为 0 → 准确率必须是 null（不可计算）。
    // 0% 会被读成「复核全对」，真实含义是「今天没人复核」。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "10",
        "5.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    var report = readReport(today, today, null, null);
    var row = row(report, user, today);
    assertThat(row.reviewedPieces()).isEqualByComparingTo("0");
    assertThat(row.reviewAccuracy()).isNull();
    assertThat(report.summary().reviewAccuracy()).isNull();
    assertThat(service.capabilities().reviewAccuracyBasisNote())
        .contains("而不是 0%")
        .contains("今天没人复核");
  }

  @Test
  void 差错率两个口径的分母各自写明() {
    var today = LocalDate.now(ZONE);
    // 甲：作业 100 件、5 单，错 10 件、2 单 → 按件数 10%，按单量 40%
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        5,
        5,
        "100",
        "60.000",
        "10",
        2,
        "COMPLETED",
        null,
        null);
    var report = readReport(today, today, null, null);
    var row = row(report, user, today);
    assertThat(row.errorPieces()).isEqualByComparingTo("10");
    assertThat(row.errorOrders()).isEqualTo(2);
    assertThat(row.errorRateByPiece()).isEqualByComparingTo("10.0000");
    assertThat(row.errorRateByOrder()).isEqualByComparingTo("40.0000");

    // 差错率跨类型统计：复核任务上的差错也进同一个分母（合计 200 件错 20 件）。
    seedFinished(
        a,
        whOne,
        worker,
        LaborWorkType.REVIEW,
        today.atTime(14, 0).atOffset(SHANGHAI),
        5,
        5,
        "100",
        null,
        "10",
        0,
        "COMPLETED",
        null,
        null);
    var both = readReport(today, today, null, null);
    assertThat(both.summary().errorPieces()).isEqualByComparingTo("20");
    assertThat(both.summary().errorRateByPiece()).isEqualByComparingTo("10.0000");
    // 单量合计 5 + 5 = 10，差错单量 2 + 0 = 2
    assertThat(both.summary().errorRateByOrder()).isEqualByComparingTo("20.0000");
    assertThat(service.capabilities().errorBasisNote()).contains("按件数").contains("按单量");
  }

  @Test
  void 人均日分母是人数乘作业天数() {
    var today = LocalDate.now(ZONE);
    var d1 = today.minusDays(1);
    // 甲在两天各拣 1 单 100 件，乙只在今天拣 2 单 200 件。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        d1.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "100",
        "10.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "100",
        "10.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        worker,
        LaborWorkType.PICKING,
        today.atTime(10, 0).atOffset(SHANGHAI),
        2,
        2,
        "200",
        "20.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);

    // 区间正好三天但只有两天有作业：停工日不进分母，
    // 否则会把人均拉低，而停工不是任何一个人的效率问题。
    var report = readReport(d1, today, null, null);
    assertThat(report.summary().headcount()).isEqualTo(2);
    assertThat(report.summary().workDays()).isEqualTo(2);
    assertThat(report.summary().pickedPieces()).isEqualByComparingTo("400");
    // 400 ÷ (2 人 × 2 作业日) = 100 件/人·日。若用「人数 × 区间自然日数(3)」会得到 66.67。
    assertThat(report.summary().avgPickedPiecesPerPersonDay()).isEqualByComparingTo("100.00");
    // 单量合计 1 + 1 + 2 = 4，4 ÷ 4 = 1.00 单/人·日
    assertThat(report.summary().avgPickedOrdersPerPersonDay()).isEqualByComparingTo("1.00");
    assertThat(service.capabilities().perCapitaBasisNote())
        .contains("作业天数")
        .contains("停工日")
        .contains("区间首尾的不完整日");
  }

  @Test
  void 按AsiaShanghai归属日且补录任务不计时效() {
    var today = LocalDate.now(ZONE);
    // 上海时间今天 00:30 = UTC 前一天 16:30。若按 UTC 或服务器本地时区归属，
    // 这一天的键会错一天。按 Asia/Shanghai 归属必须是今天。
    var shanghaiEarly = today.atTime(0, 30).atOffset(SHANGHAI);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PACKING,
        shanghaiEarly,
        1,
        1,
        "10",
        null,
        "0",
        0,
        "COMPLETED",
        null,
        shanghaiEarly.minusSeconds(300));

    var report = readReport(today, today, null, null);
    assertThat(report.rows()).hasSize(1);
    assertThat(report.rows().get(0).workDay()).isEqualTo(today);
    assertThat(report.summary().avgPackingSeconds()).isEqualByComparingTo("300.000");

    // 补录：没有开工时刻 → 时效不可计算（不是 0 秒），任务仍进报表。
    var evening = today.atTime(20, 0).atOffset(SHANGHAI);
    seedFinished(
        a,
        whOne,
        worker,
        LaborWorkType.PACKING,
        evening,
        1,
        1,
        "10",
        null,
        "0",
        0,
        "COMPLETED",
        null,
        null);
    var two = readReport(today, today, null, null);
    var noClock =
        two.rows().stream()
            .filter(r -> r.operatorUserId().equals(worker))
            .findFirst()
            .orElseThrow();
    assertThat(noClock.avgPackingSeconds()).isNull();
    assertThat(noClock.pickedPieces()).isEqualByComparingTo("0");
    // 分母不受补录影响：只有一张有开工时刻的任务参与时效。
    assertThat(two.summary().avgPackingSeconds()).isEqualByComparingTo("300.000");
  }

  @Test
  void 未开工与作业中的任务不进报表() {
    var today = LocalDate.now(ZONE);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "10",
        "5.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    // 领了任务不干活的人不该出现在人效里。
    var pending = create(LaborWorkType.PICKING, whOne, worker, null, 1, 1, null);
    start(pending.id());

    var report = readReport(today, today, null, null);
    assertThat(report.rows()).hasSize(1);
    assertThat(report.summary().headcount()).isEqualTo(1);
    assertThat(report.summary().pickedPieces()).isEqualByComparingTo("10");
  }

  // ================================================================ 排名与趋势

  @Test
  void 排名方向由指标本身决定() {
    var today = LocalDate.now(ZONE);
    // 甲：复核 100 件 0 差错、拣货 100 件走 40 米。
    // 乙：复核 100 件错 20 件、拣货 200 件走 400 米。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.REVIEW,
        today.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "100",
        null,
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        worker,
        LaborWorkType.REVIEW,
        today.atTime(9, 30).atOffset(SHANGHAI),
        1,
        1,
        "100",
        null,
        "20",
        1,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(14, 0).atOffset(SHANGHAI),
        1,
        1,
        "100",
        "40.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        worker,
        LaborWorkType.PICKING,
        today.atTime(15, 0).atOffset(SHANGHAI),
        2,
        2,
        "200",
        "400.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);

    // 差错率越低越好（ascending）：甲 0% 第一、乙 20% 第二。
    var byError = rank(today, today, "ERROR_RATE_BY_PIECE");
    assertThat(byError.higherIsBetter()).isFalse();
    assertThat(byError.metricLabel()).isEqualTo("差错率（按件数）");
    assertThat(byError.rows())
        .extracting(LaborRankingRow::operatorUserId)
        .containsExactly(user, worker);
    assertThat(byError.rows().get(0).metricValue()).isEqualByComparingTo("0.0000");
    // 差错率按件数是**跨类型**口径：乙还拣了 200 件且零差错，那 200 件同样进分母，
    // 所以是 20 ÷ 300 = 6.6667% 而不是 20%。复核准确率才只看复核件（80%）。
    assertThat(byError.rows().get(1).metricValue()).isEqualByComparingTo("6.6667");

    // 复核准确率越高越好：甲 100% 第一。
    var byAccuracy = rank(today, today, "REVIEW_ACCURACY");
    assertThat(byAccuracy.higherIsBetter()).isTrue();
    assertThat(byAccuracy.rows())
        .extracting(LaborRankingRow::operatorUserId)
        .containsExactly(user, worker);
    assertThat(byAccuracy.rows().get(0).metricValue()).isEqualByComparingTo("100.0000");
    assertThat(byAccuracy.rows().get(1).metricValue()).isEqualByComparingTo("80.0000");
    assertThat(byAccuracy.metricBasis()).contains("分母").contains("复核件数");

    // 方向相反的两个指标给出相反的序：乙拣得多所以件数榜第一，但他走得远所以距离榜垫底。
    // 这正是必须一次只排一个指标、并在响应里带出方向的原因。
    var byPieces = rank(today, today, "PICKED_PIECES");
    assertThat(byPieces.higherIsBetter()).isTrue();
    assertThat(byPieces.rows())
        .extracting(LaborRankingRow::operatorUserId)
        .containsExactly(worker, user);
    assertThat(byPieces.rows().get(0).metricValue()).isEqualByComparingTo("200");
    var byDistance = rank(today, today, "TRAVEL_DISTANCE");
    assertThat(byDistance.higherIsBetter()).isFalse();
    assertThat(byDistance.rows())
        .extracting(LaborRankingRow::operatorUserId)
        .containsExactly(user, worker);
    assertThat(byDistance.rows().get(0).metricValue()).isEqualByComparingTo("40");
    assertThat(byDistance.rows().get(1).metricValue()).isEqualByComparingTo("400");
    assertThat(byPieces.rows().get(0).operatorUserId())
        .isNotEqualTo(byDistance.rows().get(0).operatorUserId());
  }

  @Test
  void 趋势补齐无作业的日子() {
    var today = LocalDate.now(ZONE);
    var d3 = today.minusDays(3);
    var d1 = today.minusDays(1);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        d3.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "50",
        "30.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        d1.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "70",
        "40.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);

    var trend = readTrend(d3, today, null, null, null);
    // 4 天全部出现：中间两天停工。折线图跳过空白日会让「停了两天」看起来像「产量没变」。
    assertThat(trend.points()).hasSize(4);
    assertThat(trend.points())
        .extracting(LaborTrendPoint::workDay)
        .containsExactly(d3, d3.plusDays(1), d1, today);
    assertThat(trend.points().get(0).pickedPieces()).isEqualByComparingTo("50");
    assertThat(trend.points().get(0).activeOperators()).isEqualTo(1);
    assertThat(trend.points().get(1).taskCount()).isZero();
    assertThat(trend.points().get(1).pickedPieces()).isEqualByComparingTo("0");
    assertThat(trend.points().get(1).reviewAccuracy()).isNull();
    assertThat(trend.points().get(2).pickedPieces()).isEqualByComparingTo("70");
    assertThat(trend.points().get(3).taskCount()).isZero();
    assertThat(trend.scopeNote()).contains("全员口径");

    var personal = readTrend(d3, today, user, null, null);
    assertThat(personal.scopeNote()).contains("个人口径").contains(user.toString());
  }

  @Test
  void 能力端点带齐五个指标口径() {
    var caps = service.capabilities();
    assertThat(caps.workTypes())
        .extracting(LaborWorkTypeOption::value)
        .containsExactly("PICKING", "PACKING", "REVIEW");
    assertThat(caps.rankingMetrics()).hasSize(LaborRankingMetric.values().length);
    assertThat(caps.metricBasis())
        .extracting(LaborMetricBasis::metric)
        .containsExactly(
            "PICKED_PIECES",
            "PICKED_ORDERS",
            "TRAVEL_DISTANCE",
            "PACKING_SECONDS",
            "REVIEW_ACCURACY",
            "ERROR_RATE_BY_PIECE",
            "ERROR_RATE_BY_ORDER");
    assertThat(caps.metricBasis())
        .allSatisfy(
            b -> {
              assertThat(b.definition()).isNotBlank();
              assertThat(b.denominator()).isNotBlank();
              assertThat(b.unit()).isNotBlank();
            });
    assertThat(caps.exceptionCodes())
        .extracting(LaborExceptionOption::value)
        .containsExactly(
            "MISSING_STOCK", "EQUIPMENT_FAILURE", "ORDER_CANCELLED", "SAFETY_HOLD", "OTHER");
    assertThat(caps.errorVsExceptionNote()).contains("差错 ≠ 异常").contains("排班时最不公平");
    assertThat(caps.upstreamNote()).contains("8.3").contains("未交付");
    assertThat(caps.ownershipNote()).contains("唯一写入所有者");
  }

  @Test
  void 报表分页筛选与关键字搜索是真实的() {
    var done = create(LaborWorkType.PICKING, whTwo, user, "OUT-7788", 1, 1, "12");
    start(done.id());
    complete(done.id(), 1, 1, "12", "8.000", "0", 0);
    var pending = create(LaborWorkType.PACKING, whOne, worker, "OUT-0001", 0, 0, null);

    var all =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, null, null, null, null, 0, 20));
    assertThat(all.total()).isEqualTo(2);

    var byStatus =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks("PENDING", null, null, null, null, 0, 20));
    assertThat(byStatus.tasks()).extracting(LaborWorkTask::id).containsExactly(pending.id());

    var byType =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, "PACKING", null, null, null, 0, 20));
    assertThat(byType.tasks()).extracting(LaborWorkTask::id).containsExactly(pending.id());

    var byWarehouse =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, null, whTwo, null, null, 0, 20));
    assertThat(byWarehouse.tasks()).extracting(LaborWorkTask::id).containsExactly(done.id());

    var byOperator =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, null, null, worker, null, 0, 20));
    assertThat(byOperator.tasks()).extracting(LaborWorkTask::id).containsExactly(pending.id());

    // 关键字搜索真的按上游单据号过滤，不是假搜索。
    var hit =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, null, null, null, "7788", 0, 20));
    assertThat(hit.tasks()).extracting(LaborWorkTask::id).containsExactly(done.id());
    var byCode =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, null, null, null, done.code(), 0, 20));
    assertThat(byCode.tasks()).extracting(LaborWorkTask::id).containsExactly(done.id());
    var miss =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, null, null, null, "没有这个单号", 0, 20));
    assertThat(miss.total()).isZero();
    assertThat(miss.tasks()).isEmpty();

    var paged =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.tasks(null, null, null, null, null, 0, 1));
    assertThat(paged.tasks()).hasSize(1);
    assertThat(paged.total()).isEqualTo(2);
    assertThat(paged.size()).isEqualTo(1);
  }

  @Test
  void 区间与格式校验拒绝而不是猜() {
    var today = LocalDate.now(ZONE);
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.report(today, today.minusDays(3), null, null, 0, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("不能晚于");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.trend(today.minusDays(400), today, null, null, null)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("跨度不能超过");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.ranking(today, today, null, null, null, 20)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("必须选择排名指标");
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.read",
                    () -> service.ranking(today, today, null, null, "ERROR", 20)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("未知的排名指标");
    // 缺省区间是最近 14 天，落在「日」口径内。
    var byDefault =
        access.run(
            actorA,
            "warehouse.inventory.read",
            () -> service.report(null, null, null, null, 0, 20));
    assertThat(byDefault.to()).isEqualTo(today);
    assertThat(byDefault.from()).isEqualTo(today.minusDays(13));
  }

  @Test
  void 端点返回真实数据且校验参数() throws Exception {
    var task = create(LaborWorkType.PICKING, whOne, user, "OUT-3131", 2, 2, "30");
    http.perform(get("/warehouse/labor/capabilities").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timezone").value(LaborEfficiencyModels.TIMEZONE))
        .andExpect(jsonPath("$.metricBasis.length()").value(LaborRankingMetric.values().length));
    http.perform(get("/warehouse/labor/work-tasks").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1))
        .andExpect(jsonPath("$.tasks[0].code").value(task.code()))
        .andExpect(jsonPath("$.tasks[0].workType").value("PICKING"));
    http.perform(
            get("/warehouse/labor/work-tasks/{id}", task.id())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.timeline.length()").value(2))
        .andExpect(jsonPath("$.task.status").value("PENDING"));
    http.perform(
            get("/warehouse/labor/report")
                .param("from", LocalDate.now(ZONE).toString())
                .param("to", LocalDate.now(ZONE).toString())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.dayBasis").isNotEmpty())
        .andExpect(jsonPath("$.rows.length()").value(0));
    http.perform(
            get("/warehouse/labor/ranking")
                .param("metric", "PICKED_PIECES")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.metricLabel").isNotEmpty());
    http.perform(get("/warehouse/labor/trend").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.points.length()").value(14));
    // 日期格式不对直接 400，不按服务器本地时区猜。
    http.perform(
            get("/warehouse/labor/report")
                .param("from", "2026/10/01")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    // ranking 的 metric 是必填：方向不同的指标不能混在一个榜里。
    http.perform(get("/warehouse/labor/ranking").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isBadRequest());
    // 写入类端点需要 update 权限。
    http.perform(
            post("/warehouse/labor/work-tasks/{id}/start", task.id())
                .header("Authorization", "Bearer read-only")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/warehouse/labor/work-tasks/{id}/start", task.id())
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("RUNNING"));
  }

  // ================================================================ 数据库层

  @Test
  void 数据库层钉死人效凭据() throws Exception {
    var task = create(LaborWorkType.PICKING, whOne, user, "OUT-999", 2, 2, "50");
    start(task.id());
    var done = complete(task.id(), 2, 2, "50", "30.000", "1", 1);
    var completedAt = done.completedAt();

    // 1. 终态计量冻结：绕过服务直接改已完成任务的件数，DB 必须拒绝。
    //    只写在服务层注释里的红线，等于没有红线。
    assertThatThrownBy(
            () ->
                raw(
                    a,
                    "update warehouse_work_task set piece_count = 999 where tenant_id = ? and id = ?",
                    a,
                    task.id()))
        .hasMessageContaining("计量与时间不可改");
    // 2. 完成时刻也不许改：改了就能把人效任意挪到别的日子。
    assertThatThrownBy(
            () ->
                raw(
                    a,
                    "update warehouse_work_task set completed_at = ?::timestamptz"
                        + " where tenant_id = ? and id = ?",
                    OffsetDateTime.now(ZONE).minusDays(3),
                    a,
                    task.id()))
        .hasMessageContaining("计量与时间不可改");
    // 3. 终态不可回退。
    assertThatThrownBy(
            () ->
                raw(
                    a,
                    "update warehouse_work_task set status = 'RUNNING', started_at = ?::timestamptz"
                        + " where tenant_id = ? and id = ?",
                    OffsetDateTime.now(ZONE),
                    a,
                    task.id()))
        .hasMessageContaining("终态不可回退");
    // 4. 事件 append-only：改与删都不行。
    var eventId =
        query(
            a,
            () ->
                sql.fetchOne(
                        "select id from warehouse_work_event where task_id = ? order by occurred_at limit 1",
                        task.id())
                    .get("id", UUID.class));
    assertThatThrownBy(
            () ->
                raw(
                    a,
                    "update warehouse_work_event set detail = '改过的经过' where tenant_id = ? and id = ?",
                    a,
                    eventId))
        .hasMessageContaining("append-only");
    // DELETE 上有两层独立防护，都得验：应用角色没有 DELETE 权限，
    // 而绕过权限的连接仍然会被 append-only 触发器挡住。
    // 断言「删不掉」这件事本身（驱动把 42501 包装成 BadSqlGrammarException，
    // 消息不一定带 PG 原文），而不是断言某句错误文案。
    assertThatThrownBy(
        () ->
            raw(a, "delete from warehouse_work_event where tenant_id = ? and id = ?", a, eventId));
    assertThatThrownBy(() -> superuser("delete from warehouse_work_event where id = ?", eventId))
        .hasMessageContaining("append-only");
    // 两层都挡住了：事件还在。
    assertThat(
            query(
                a,
                () ->
                    sql.fetchOne("select count(*) from warehouse_work_event where id = ?", eventId)
                        .get(0, Long.class)))
        .isEqualTo(1L);
    // 5. 差错件数超过作业件数在 DB 层也被挡住。
    var other = create(LaborWorkType.PICKING, whOne, user, null, 1, 1, "5");
    start(other.id());
    assertThatThrownBy(
            () ->
                raw(
                    a,
                    "update warehouse_work_task set status = 'COMPLETED', completed_at = ?::timestamptz,"
                        + " piece_count = 5, error_piece_count = 9 where tenant_id = ? and id = ?",
                    OffsetDateTime.now(ZONE),
                    a,
                    other.id()))
        .hasMessageContaining("violates check constraint");
    // 6. 备注可以改 —— 不是所有列都冻结，否则补一句现场说明都要走审批。
    raw(
        a,
        "update warehouse_work_task set note = '现场补充说明' where tenant_id = ? and id = ?",
        a,
        task.id());
    assertThat(
            query(
                a,
                () ->
                    sql.fetchOne("select note from warehouse_work_task where id = ?", task.id())
                        .get("note", String.class)))
        .isEqualTo("现场补充说明");

    // 原始计量一个数都没被动过。
    var after =
        query(
            a,
            () ->
                sql.fetchOne(
                    "select piece_count, completed_at from warehouse_work_task where id = ?",
                    task.id()));
    assertThat(after.get("piece_count", BigDecimal.class)).isEqualByComparingTo("50");
    assertThat(after.get("completed_at", OffsetDateTime.class)).isEqualTo(completedAt);
  }

  @Test
  void 已开工不许换人() {
    var task = create(LaborWorkType.PICKING, whOne, user, null, 1, 1, null);
    start(task.id());
    // 服务层先挡一道。
    assertThatThrownBy(
            () ->
                access.run(
                    actorA,
                    "warehouse.inventory.update",
                    () ->
                        service.assign(
                            task.id(), new LaborWorkTaskAssignInput(worker, null), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("只有「待开工」");
    // DB 层再挡一道：拿连接硬改也换不了人。
    assertThatThrownBy(
            () ->
                raw(
                    a,
                    "update warehouse_work_task set operator_user_id = ?, assigned_by_user_id = ?"
                        + " where tenant_id = ? and id = ?",
                    worker,
                    user,
                    a,
                    task.id()))
        .hasMessageContaining("不许更换操作人");
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 断言一_两租户互不可见() {
    var today = LocalDate.now(ZONE);
    // A 的人效数据
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        3,
        3,
        "120",
        "45.500",
        "2",
        1,
        "COMPLETED",
        null,
        null);
    var taskA = create(LaborWorkType.PACKING, whOne, user, null, 1, 1, null);
    start(taskA.id());

    // B 造自己的数据：人效必须完全分开，不能互相污染排名与汇总。
    seedFinished(
        b,
        whOneOf(b),
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "10",
        "5.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);

    // B 看不到 A 的任务：连详情都是 404（比返回空集更强 —— 不泄露存在性）。
    assertThatThrownBy(
            () -> access.run(actorB, "warehouse.inventory.read", () -> service.detail(taskA.id())))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.start(taskA.id(), null, user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.read",
                    () ->
                        service.assign(
                            taskA.id(), new LaborWorkTaskAssignInput(worker, null), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.update",
                    () ->
                        service.complete(
                            taskA.id(),
                            new LaborWorkTaskCompleteInput(
                                1, 1, new BigDecimal("1"), null, null, null, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
    // B 也读不到 A 的任务列表。
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.tasks(null, null, null, null, null, 0, 50))
                .total())
        .isEqualTo(1);

    // 跨租户不能看到对方的人效排名：A 的 120 件不能进 B 的榜。
    var rankingB =
        access.run(
            actorB,
            "warehouse.inventory.read",
            () -> service.ranking(today, today, null, null, "PICKED_PIECES", 20));
    assertThat(rankingB.rows()).hasSize(1);
    assertThat(rankingB.rows().get(0).pickedPieces()).isEqualByComparingTo("10");
    assertThat(rankingB.rows().get(0).errorPieces()).isEqualByComparingTo("0");
    var reportB =
        access.run(
            actorB,
            "warehouse.inventory.read",
            () -> service.report(today, today, null, null, 0, 20));
    assertThat(reportB.summary().pickedPieces()).isEqualByComparingTo("10");
    assertThat(reportB.summary().headcount()).isEqualTo(1);
    // A 的仓库 id 在 B 的租户上下文里查不到：不能拿 A 的仓库切片口径。
    assertThat(
            access
                .run(
                    actorB,
                    "warehouse.inventory.read",
                    () -> service.report(today, today, whOne, null, 0, 20))
                .rows())
        .isEmpty();
    // B 也给 A 的仓库派不了工。
    assertThatThrownBy(
            () ->
                access.run(
                    actorB,
                    "warehouse.inventory.update",
                    () ->
                        service.create(
                            new LaborWorkTaskCreateInput(
                                "PICKING", whOne, "8.5.1.1", null, null, null, null, user, null),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("404");
  }

  @Test
  void 断言二_伪造租户头无效() throws Exception {
    var today = LocalDate.now(ZONE);
    // A 有**两个人**的人效数据；B 只有一个人。伪造头若生效，B 会看到 2 行。
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        3,
        3,
        "120",
        "45.500",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        a,
        whOne,
        worker,
        LaborWorkType.PICKING,
        today.atTime(10, 0).atOffset(SHANGHAI),
        1,
        1,
        "60",
        "20.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    var taskA = create(LaborWorkType.PICKING, whOne, user, "OUT-FORGED", 1, 1, null);

    seedFinished(
        b,
        whOneOf(b),
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "10",
        "5.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);

    // 身份是 B，伪造 X-Tenant-Id 指向 A：B 看到的仍然是 B 的（1 行，不是 A 的 2 行）。
    http.perform(
            get("/warehouse/labor/ranking")
                .param("metric", "PICKED_PIECES")
                .param("from", today.toString())
                .param("to", today.toString())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.rows.length()").value(1))
        .andExpect(jsonPath("$.rows[0].pickedPieces").value(10));
    http.perform(
            get("/warehouse/labor/report")
                .param("from", today.toString())
                .param("to", today.toString())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.summary.headcount").value(1))
        .andExpect(jsonPath("$.rows.length()").value(1));
    http.perform(
            get("/warehouse/labor/work-tasks/{id}", taskA.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString()))
        .andExpect(status().isNotFound());
    // 伪造头也不能让 B 开工 A 的任务。
    http.perform(
            post("/warehouse/labor/work-tasks/{id}/start", taskA.id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a.toString())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isNotFound());
    // A 的任务仍是待开工：这串伪造请求没有在 A 的租户里改动任何东西。
    assertThat(
            query(
                a,
                () ->
                    sql.fetchOne("select status from warehouse_work_task where id = ?", taskA.id())
                        .get("status", String.class)))
        .isEqualTo("PENDING");
  }

  @Test
  void 断言三_连接池归还后不串租户() {
    var today = LocalDate.now(ZONE);
    seedFinished(
        a,
        whOne,
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        3,
        3,
        "120",
        "45.500",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    seedFinished(
        b,
        whOneOf(b),
        user,
        LaborWorkType.PICKING,
        today.atTime(9, 0).atOffset(SHANGHAI),
        1,
        1,
        "10",
        "5.000",
        "0",
        0,
        "COMPLETED",
        null,
        null);
    var taskA = create(LaborWorkType.PACKING, whOne, user, null, 1, 1, null);

    // 交替复用同一条物理连接（池容量=1）：A → B → A → B
    for (int i = 0; i < 3; i++) {
      var a1 =
          access.run(
              actorA,
              "warehouse.inventory.read",
              () -> service.report(today, today, null, null, 0, 50));
      assertThat(a1.summary().pickedPieces()).isEqualByComparingTo("120");
      assertThat(a1.summary().headcount()).isEqualTo(1);

      var b1 =
          access.run(
              actorB,
              "warehouse.inventory.read",
              () -> service.report(today, today, null, null, 0, 50));
      assertThat(b1.summary().pickedPieces()).isEqualByComparingTo("10");
      assertThat(b1.summary().headcount()).isEqualTo(1);

      var a2 = access.run(actorA, "warehouse.inventory.read", () -> service.detail(taskA.id()));
      assertThat(a2.task().warehouseCode()).isEqualTo("WH-1");

      // B 读同一个 id 必须拿不到，而不是拿到 A 的行。
      assertThatThrownBy(
              () ->
                  access.run(actorB, "warehouse.inventory.read", () -> service.detail(taskA.id())))
          .isInstanceOf(ResponseStatusException.class)
          .hasMessageContaining("404");
    }
    // 两张表逐张确认 FORCE RLS
    for (var table : List.of("warehouse_work_task", "warehouse_work_event")) {
      assertThat(
              query(
                  a,
                  () ->
                      sql.fetchOne(
                              "select relforcerowsecurity from pg_class where relname = ?", table)
                          .get("relforcerowsecurity", Boolean.class)))
          .isTrue();
      assertThat(
              query(
                  a,
                  () ->
                      sql.fetchOne("select relrowsecurity from pg_class where relname = ?", table)
                          .get("relrowsecurity", Boolean.class)))
          .isTrue();
    }
  }

  // ================================================================ 夹具

  private LaborWorkTask create(
      LaborWorkType type,
      UUID warehouseId,
      UUID operator,
      String reference,
      int orders,
      int lines,
      String pieces) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.create(
                new LaborWorkTaskCreateInput(
                    type.name(),
                    warehouseId,
                    "8.5.1.1",
                    reference,
                    orders,
                    lines,
                    pieces == null ? null : new BigDecimal(pieces),
                    operator,
                    "夹具"),
                user));
  }

  private LaborWorkTask assign(UUID taskId, UUID operator) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () -> service.assign(taskId, new LaborWorkTaskAssignInput(operator, "改派"), user));
  }

  private LaborWorkTask start(UUID taskId) {
    return access.run(
        actorA, "warehouse.inventory.update", () -> service.start(taskId, null, user));
  }

  private LaborWorkTask complete(
      UUID taskId,
      int orders,
      int lines,
      String pieces,
      String distance,
      String errorPieces,
      int errorOrders) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.complete(
                taskId,
                new LaborWorkTaskCompleteInput(
                    orders,
                    lines,
                    new BigDecimal(pieces),
                    distance == null ? null : new BigDecimal(distance),
                    errorPieces == null ? null : new BigDecimal(errorPieces),
                    errorOrders,
                    null),
                user));
  }

  private LaborWorkTask exception(
      UUID taskId, String code, String note, int orders, int lines, String pieces) {
    return access.run(
        actorA,
        "warehouse.inventory.update",
        () ->
            service.reportException(
                taskId,
                new LaborWorkTaskExceptionInput(
                    code,
                    note,
                    orders,
                    lines,
                    pieces == null ? null : new BigDecimal(pieces),
                    null,
                    null),
                user));
  }

  private LaborWorkTaskDetail read(UUID taskId) {
    return access.run(actorA, "warehouse.inventory.read", () -> service.detail(taskId));
  }

  /**
   * 直接写一条**已定稿**的作业任务，用来造跨天的历史。
   *
   * <p>只有 INSERT 能造历史：V74 的触发器刻意让 completed_at 不可改（改了就能把人效 任意挪到别的日子），所以「三天前做的活」只能作为夹具写进去，不能事后改出来。
   * 写入端点本身的正确性由上面的测试用真实端点覆盖。
   */
  private void seedFinished(
      UUID tenant,
      UUID warehouseId,
      UUID operator,
      LaborWorkType type,
      OffsetDateTime completedAt,
      int orders,
      int lines,
      String pieces,
      String distance,
      String errorPieces,
      int errorOrders,
      String status,
      String exceptionCode,
      OffsetDateTime startedAt) {
    query(
        tenant,
        () -> {
          sql.execute(
              "insert into warehouse_work_task(id,tenant_id,code,work_type,source_module,reference_no,"
                  + " warehouse_id,operator_user_id,assigned_at,assigned_by_user_id,status,started_at,"
                  + " completed_at,order_count,line_count,piece_count,travel_distance_m,"
                  + " error_piece_count,error_order_count,exception_code,exception_note,note)"
                  + " values(?,?,?,?,?,?,?,?,?::timestamptz,?,?,?::timestamptz,?::timestamptz,"
                  + " ?,?,?,?,?,?,?,?,?)",
              UUID.randomUUID(),
              tenant,
              "WT-SEED-" + UUID.randomUUID().toString().substring(0, 8),
              type.name(),
              "8.5.1.1",
              "OUT-" + UUID.randomUUID().toString().substring(0, 6),
              warehouseId,
              operator,
              completedAt.minusHours(4),
              operator,
              status,
              startedAt,
              completedAt,
              orders,
              lines,
              new BigDecimal(pieces),
              distance == null ? null : new BigDecimal(distance),
              new BigDecimal(errorPieces == null ? "0" : errorPieces),
              errorOrders,
              exceptionCode,
              exceptionCode == null ? "" : "夹具异常",
              "夹具");
          return null;
        });
  }

  private LaborReport readReport(LocalDate from, LocalDate to, UUID warehouseId, String workType) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> service.report(from, to, warehouseId, workType, 0, 200));
  }

  private LaborRanking rank(LocalDate from, LocalDate to, String metric) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> service.ranking(from, to, null, null, metric, 50));
  }

  private LaborTrend readTrend(
      LocalDate from, LocalDate to, UUID operatorUserId, UUID warehouseId, String workType) {
    return access.run(
        actorA,
        "warehouse.inventory.read",
        () -> service.trend(from, to, operatorUserId, warehouseId, workType));
  }

  private static LaborEfficiencyRow row(LaborReport report, UUID operator, LocalDate day) {
    return report.rows().stream()
        .filter(r -> r.operatorUserId().equals(operator) && r.workDay().equals(day))
        .findFirst()
        .orElseThrow(() -> new AssertionError("报表里没有 " + operator + " 在 " + day + " 的行"));
  }
}
