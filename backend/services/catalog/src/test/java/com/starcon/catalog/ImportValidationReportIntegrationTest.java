package com.starcon.catalog;

import static com.starcon.catalog.ImportValidationModels.*;
import static com.starcon.catalog.ImportWizardModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * 3.8.1.2 校验报告：报告固化与分类、「必须修正 / 可跳过」的分段、带标注文件与修正版模板的导出， 以及<b>租户隔离三断言 + RLS 强隔离断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：报告快照、错误明细、导出登记全部真落库， 导出文件是真字节（用上游同一个 {@code ImportCsvParser}
 * 反解回来逐格断言）。 连接池固定 1 条连接 —— 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>本文件最关键的四组断言</b>：
 *
 * <ol>
 *   <li>{@link #报告把预校验结果分成必须修正与可跳过两段()}：蓝图那句 「区分可跳过与必须修正」「校验失败的行不落库」在计数上可查。
 *   <li>{@link #导出的带标注文件可以原样重新上传()}：带标注的错误文件不只是给人看的， 它是**修正后可以直接重传**的那一份。
 *   <li>{@link #重跑预校验不会静默改写已有报告且会被标为过期()}：快照语义的成败在此。
 *   <li>{@link #租户隔离三断言()} + {@link #伪造租户上下文的直接写入被RLS拒绝()}。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportValidationReportIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors =
      new java.util.concurrent.ConcurrentHashMap<>();

  static AccessService.Access actor(
      UUID uid, UUID tenant, UUID dept, List<AccessService.Grant> grants) {
    return new AccessService.Access(
        uid,
        tenant,
        "Test",
        UUID.randomUUID(),
        dept,
        grants.stream().flatMap(g -> g.permissions().stream()).distinct().toList(),
        grants);
  }

  static AccessService.Grant grant(String scope, List<String> permissions) {
    return new AccessService.Grant(UUID.randomUUID(), "TEST", scope, permissions);
  }

  static {
    pg.start();
    try (var conn =
            DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
      identity = HttpServer.create(new java.net.InetSocketAddress(0), 0);
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
      identity.start();
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader",
          actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute")
        .flatMap(
            p ->
                java.util.stream.Stream.of("read", "create", "update", "delete")
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
    // 固定 1 条连接：只有复用同一条连接才能证明归还后不串租户。
    r.add("spring.datasource.hikari.maximum-pool-size", () -> 1);
  }

  @Autowired ImportTemplateService templates;
  @Autowired ImportJobService jobs;
  @Autowired ImportJobExecutor executor;
  @Autowired ImportValidationService reports;
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
  void setup() {
    actorA = actors.get("Bearer tenant-a");
    actorB = actors.get("Bearer tenant-b");
    releaseActiveJobs(actorA);
    releaseActiveJobs(actorB);
  }

  void releaseActiveJobs(AccessService.Access who) {
    write(
        who,
        () ->
            sql.execute(
                "update import_job set status='CANCELLED'"
                    + " where status in ('PENDING','PREVALIDATING','PREVALIDATED','RUNNING')"));
  }

  <T> T tx(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.read", work);
  }

  <T> T write(AccessService.Access who, java.util.function.Supplier<T> work) {
    return access.run(who, "catalog.spu.update", work);
  }

  String unique(String hint) {
    return hint
        + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  UUID template(AccessService.Access who, String entity) {
    return write(
        who,
        () ->
            templates
                .create(
                    who.tenantId(),
                    new ImportTemplateInput(
                        unique("TPL-" + entity + "-"), "模板 " + entity, "", entity, true, List.of()))
                .template()
                .id());
  }

  /** 上传 → 预校验 → 等终态（不执行）。返回任务 id。 */
  UUID prevalidatedJob(AccessService.Access who, UUID templateId, String mode, String csv) {
    byte[] content = csv.getBytes(StandardCharsets.UTF_8);
    var summary =
        write(
            who,
            () -> jobs.upload(who.tenantId(), templateId, mode, Map.of(), "import.csv", content));
    write(who, () -> jobs.requestPrevalidate(who, summary.jobId()));
    executor.prevalidateNow(who, summary.jobId());
    awaitPrevalidated(who, summary.jobId());
    return summary.jobId();
  }

  /**
   * 轮询等预校验到终态（超时即失败：异步路径卡住时立刻定位，而不是让测试挂死）。
   *
   * <p>断言的是**终态**而不是「我驱动的那一次同步跑完了」——因为 {@code requestPrevalidate} 已经把活扔进线程池了（那正是「导入是异步任务」的落点），
   * 这里再驱动一次只是抢跑，谁真正跑完要以库里的状态为准。
   */
  void awaitPrevalidated(AccessService.Access who, UUID jobId) {
    var deadline = java.time.Duration.ofSeconds(30);
    long start = System.nanoTime();
    while (java.time.Duration.ofNanos(System.nanoTime() - start).compareTo(deadline) < 0) {
      var job = tx(who, () -> jobs.job(who.tenantId(), jobId));
      if (Set.of("PREVALIDATED", "FAILED", "CANCELLED").contains(job.status())) return;
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("导入任务 " + jobId + " 30 秒内没有到达预校验完成");
  }

  /**
   * 一个固定的四行样本：干净行 / 缺必填（必须修正）/ 编码已存在（可跳过）/ 状态非法（必须修正）。
   *
   * <p>四行覆盖了报告必须能分清的三种结论，以及蓝图的「不允许尽力导入产生半截数据」。
   */
  String sampleCsv(String cleanCode, String existingCode) {
    return "商品编码,商品名称,商品描述,品牌,类目,状态\r\n"
        + cleanCode
        + ",干净行,,,,DRAFT\r\n"
        + ",缺编码,名称,,,DRAFT\r\n"
        + existingCode
        + ",已存在,,,,\r\n"
        + unique("SPU-")
        + ",非法状态,,,,NOT_A_STATUS\r\n";
  }

  /** 预置一个已存在的 SPU，让 CREATE 模式对它报「已存在」（可跳过而不是错误）。 */
  void seedSpu(AccessService.Access who, String code) {
    write(
        who,
        () ->
            sql.execute(
                "insert into spu(id,tenant_id,code,name) values(?,?,?,?)",
                UUID.randomUUID(),
                who.tenantId(),
                code,
                "已存在的商品"));
  }

  // ================================================================ 分段与分类

  @Test
  void 报告把预校验结果分成必须修正与可跳过两段() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));

    var summary = tx(actorA, () -> reports.summary(actorA.tenantId(), jobId, user));
    var r = summary.report();
    assertThat(r.totalRowCount()).isEqualTo(4);
    // 第 2 行干净；第 3 行缺必填编码、第 6 行状态非法 → 必须修正
    assertThat(r.cleanRowCount()).isEqualTo(1);
    assertThat(r.blockingRowCount()).as("有 ERROR 的行不落库").isEqualTo(2);
    // 第 4 行编码已存在（CREATE 模式下是 WARNING）→ 可跳过，照常落库
    assertThat(r.skippableRowCount()).as("只有 WARNING 的行照常落库").isEqualTo(1);
    assertThat(r.issueRowCount()).isEqualTo(3);
    // 缺编码那一行报两条（必填缺失 + 新增模式必须有匹配键），两条都是 ERROR
    assertThat(r.errorCount()).isEqualTo(3);
    assertThat(r.warningCount()).isEqualTo(1);
    // 三段之和等于总行数：报告页顶部的数字不能互相矛盾
    assertThat(r.blockingRowCount() + r.skippableRowCount() + r.cleanRowCount())
        .isEqualTo(r.totalRowCount());
    assertThat(r.stale()).as("刚生成的报告不是过期的").isFalse();
    assertThat(r.sourceStatus()).isEqualTo("PREVALIDATED");

    // 分类统计：蓝图点名的五类，含 0 的类也要给（否则「没踩」与「没检查」看起来一样）
    assertThat(summary.categories()).hasSize(6);
    // 必填缺失是 2 条但只落在 1 行（同一行两个码）—— 条数与行数必须分开给
    assertThat(summary.categories())
        .filteredOn(c -> c.label().equals("必填缺失"))
        .singleElement()
        .satisfies(
            c -> {
              assertThat(c.errorCount()).isEqualTo(2);
              assertThat(c.rowCount()).isEqualTo(1);
            });
    assertThat(summary.categories())
        .filteredOn(c -> c.label().equals("格式错误"))
        .singleElement()
        .satisfies(c -> assertThat(c.errorCount()).isEqualTo(1));
    assertThat(summary.categories())
        .filteredOn(c -> c.label().equals("唯一冲突"))
        .singleElement()
        .satisfies(c -> assertThat(c.warningCount()).isEqualTo(1));
    assertThat(summary.categories())
        .filteredOn(c -> c.label().equals("重复编码"))
        .singleElement()
        .satisfies(c -> assertThat(c.rowCount()).isZero());

    // 按列统计：错最多的列排前面
    assertThat(summary.columns()).isNotEmpty();
    assertThat(summary.columns()).allSatisfy(c -> assertThat(c.fieldLabel()).isNotBlank());
    assertThat(summary.columns().stream().mapToInt(c -> c.errorCount() + c.warningCount()).sum())
        .isEqualTo(r.errorCount() + r.warningCount());
  }

  @Test
  void 错误明细按行定位并带原始值() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));

    var page =
        tx(actorA, () -> reports.issues(actorA.tenantId(), jobId, user, null, null, null, 0, 50));
    assertThat(page.total()).isEqualTo(4);
    assertThat(page.errorCount()).isEqualTo(3);
    assertThat(page.warningCount()).isEqualTo(1);
    // 行号是文件里的物理行号：表头第 1 行，第一条数据第 2 行（干净那行没有错误，所以看不到）
    assertThat(page.items()).extracting(i -> i.rowNo()).containsExactly(3, 3, 4, 5);

    // 缺必填的那一行：字段标签用**文件里的列头**，且带原始值
    var required = page.items().get(1);
    assertThat(required.fieldKey()).isEqualTo("spu_code");
    assertThat(required.fieldLabel()).isEqualTo("商品编码");
    assertThat(required.code()).isEqualTo("REQUIRED");
    assertThat(required.category()).isEqualTo("REQUIRED_MISSING");
    assertThat(required.categoryLabel()).isEqualTo("必填缺失");
    assertThat(required.severity()).isEqualTo("ERROR");
    assertThat(required.rawValue()).as("空格子也要能定位到").isEmpty();
    assertThat(required.rowBlocking()).as("有 ERROR → 整行不落库").isTrue();
    assertThat(required.rowOutcome()).isEqualTo("INVALID");
    assertThat(required.rowOutcomeLabel()).isEqualTo("校验不通过");

    // 可跳过的那一行：WARNING 不阻断落库
    var skippable = page.items().get(2);
    assertThat(skippable.severity()).isEqualTo("WARNING");
    assertThat(skippable.code()).isEqualTo("EXISTS_IN_CREATE_MODE");
    assertThat(skippable.category()).isEqualTo("UNIQUE_CONFLICT");
    assertThat(skippable.rowBlocking()).as("只有 WARNING → 行照常落库").isFalse();
    assertThat(skippable.rawValue()).isEqualTo(existing);

    // 三种筛选分别对应三种用户意图
    var onlyErrors =
        tx(
            actorA,
            () -> reports.issues(actorA.tenantId(), jobId, user, "ERROR", null, null, 0, 50));
    assertThat(onlyErrors.items()).extracting(i -> i.rowNo()).containsExactly(3, 3, 5);
    var onlyRequired =
        tx(
            actorA,
            () ->
                reports.issues(
                    actorA.tenantId(), jobId, user, null, "REQUIRED_MISSING", null, 0, 50));
    assertThat(onlyRequired.items()).extracting(i -> i.rowNo()).containsExactly(3, 3);
    var oneRow =
        tx(actorA, () -> reports.issues(actorA.tenantId(), jobId, user, null, null, 4, 0, 50));
    assertThat(oneRow.items()).extracting(i -> i.rowNo()).containsExactly(4);
    // 分页是真的分页（不是把全量塞进一页）
    var firstPage =
        tx(actorA, () -> reports.issues(actorA.tenantId(), jobId, user, null, null, null, 0, 2));
    assertThat(firstPage.items()).hasSize(2);
    assertThat(firstPage.total()).isEqualTo(4);
    var secondPage =
        tx(actorA, () -> reports.issues(actorA.tenantId(), jobId, user, null, null, null, 1, 2));
    assertThat(secondPage.items()).extracting(i -> i.rowNo()).containsExactly(4, 5);

    // 未知类别必须 400（而不是静默返回空列表让人以为「没有这类问题」）
    assertThatThrownBy(
            () ->
                tx(
                    actorA,
                    () ->
                        reports.issues(
                            actorA.tenantId(), jobId, user, null, "NOT_A_CATEGORY", null, 0, 50)))
        .isInstanceOf(ResponseStatusException.class);
  }

  // ================================================================ 导出

  @Test
  void 导出的带标注文件给出全部行并标注是否落库() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));

    var export =
        write(
            actorA,
            () ->
                reports.createExport(
                    actorA.tenantId(),
                    jobId,
                    new ImportValidationExportInput("ANNOTATED_ERRORS", "ALL"),
                    user));
    assertThat(export.exportKind()).isEqualTo("ANNOTATED_ERRORS");
    assertThat(export.exportKindLabel()).isEqualTo("带标注的错误文件");
    assertThat(export.rowCount()).as("带标注的错误文件给出全部数据行（含校验通过的）").isEqualTo(4);
    assertThat(export.issueCount()).isEqualTo(4);
    assertThat(export.byteSize()).isPositive();
    assertThat(export.contentSha256()).hasSize(64);
    assertThat(export.fileName()).endsWith("-annotated-errors.csv");

    var file = tx(actorA, () -> reports.exportFile(actorA.tenantId(), jobId, export.id(), user));
    var parsed = ImportCsvParser.parse(file.content());
    // 列头 = 行号 + 模板六列 + 七个标注列
    assertThat(parsed.headers())
        .containsExactly(
            "行号", "商品编码", "商品名称", "商品描述", "品牌", "类目", "状态", "问题字段", "必须修正", "问题类别", "问题代码", "问题说明",
            "本行结论", "是否落库");
    assertThat(parsed.rows()).hasSize(4);

    int conclusion = parsed.headers().indexOf("本行结论");
    int willImport = parsed.headers().indexOf("是否落库");
    int mustFix = parsed.headers().indexOf("必须修正");
    assertThat(parsed.rows().get(0).get(conclusion)).isEqualTo("校验通过");
    assertThat(parsed.rows().get(0).get(willImport)).isEqualTo("是");
    assertThat(parsed.rows().get(0).get(mustFix)).isEqualTo("否");
    // 第 2 条数据（缺必填）：必须修正 + 永不落库
    assertThat(parsed.rows().get(1).get(conclusion)).isEqualTo("必须修正");
    assertThat(parsed.rows().get(1).get(willImport)).isEqualTo("否");
    // 第 3 条数据（已存在）：可跳过 + 照常落库 —— 这正是「区分可跳过与必须修正」的落点
    assertThat(parsed.rows().get(2).get(conclusion)).isEqualTo("可跳过");
    assertThat(parsed.rows().get(2).get(willImport)).as("可跳过的行照常落库").isEqualTo("是");
    assertThat(parsed.rows().get(2).get(mustFix)).isEqualTo("否");
    // 第 4 条数据（状态非法）
    assertThat(parsed.rows().get(3).get(conclusion)).isEqualTo("必须修正");
  }

  @Test
  void 修正版模板只给有问题的行() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));

    var export =
        write(
            actorA,
            () ->
                reports.createExport(
                    actorA.tenantId(),
                    jobId,
                    new ImportValidationExportInput("FIXED_TEMPLATE", "ALL"),
                    user));
    assertThat(export.exportKindLabel()).isEqualTo("修正版模板");
    assertThat(export.rowCount()).as("只含有问题的行").isEqualTo(3);

    var file = tx(actorA, () -> reports.exportFile(actorA.tenantId(), jobId, export.id(), user));
    var parsed = ImportCsvParser.parse(file.content());
    assertThat(parsed.rows()).hasSize(3);
    int rowNo = parsed.headers().indexOf("行号");
    assertThat(parsed.rows().stream().map(r -> r.get(rowNo)).toList())
        .as("导出的行号必须还是文件里的物理行号")
        .containsExactly("3", "4", "5");

    // 按 ERROR 筛选：只给必须修正的行，可跳过的行不给
    var errorsOnly =
        write(
            actorA,
            () ->
                reports.createExport(
                    actorA.tenantId(),
                    jobId,
                    new ImportValidationExportInput("FIXED_TEMPLATE", "ERROR"),
                    user));
    var errorsFile =
        tx(actorA, () -> reports.exportFile(actorA.tenantId(), jobId, errorsOnly.id(), user));
    var errorsParsed = ImportCsvParser.parse(errorsFile.content());
    assertThat(errorsParsed.rows().stream().map(r -> r.get(rowNo)).toList())
        .as("导出一行数据一行，所以 ERROR 筛选给出的是行不是条")
        .containsExactly("3", "5");

    // 没有任何问题时报错，而不是给一个只有表头的空文件让人以为「修好了」
    // 先释放互斥槽位：上面那个任务停在「预校验完成」，同租户同类任务会 409。
    releaseActiveJobs(actorA);
    UUID cleanTpl = template(actorA, "SPU");
    UUID cleanJob =
        prevalidatedJob(
            actorA,
            cleanTpl,
            "CREATE",
            "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + unique("SPU-") + ",干净,,,,\r\n");
    assertThatThrownBy(
            () ->
                write(
                    actorA,
                    () ->
                        reports.createExport(
                            actorA.tenantId(),
                            cleanJob,
                            new ImportValidationExportInput("FIXED_TEMPLATE", "ALL"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("没有需要修正的行");
  }

  /**
   * 带标注的错误文件改完可以**原样重新上传**。
   *
   * <p>这条是导出能不能落地的判据：导入只按映射读取模板声明的列，多出来的标注列会被忽略， 所以运营不必手工删列。而这份文件确实来自 3.8.1.1 的导入通道（真的走一遍 upload）。
   */
  @Test
  void 导出的带标注文件可以原样重新上传() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));
    var export =
        write(
            actorA,
            () ->
                reports.createExport(
                    actorA.tenantId(),
                    jobId,
                    new ImportValidationExportInput("ANNOTATED_ERRORS", "ALL"),
                    user));
    var file = tx(actorA, () -> reports.exportFile(actorA.tenantId(), jobId, export.id(), user));

    releaseActiveJobs(actorA);
    var again =
        write(
            actorA,
            () ->
                jobs.upload(
                    actorA.tenantId(), tpl, "CREATE", Map.of(), file.fileName(), file.content()));
    assertThat(again.rowCount()).as("标注列不能被当成数据列，也不能把行数带偏").isEqualTo(4);
    assertThat(again.columnCount()).isEqualTo(14);
    // 重新预校验后仍然只有原来那三行有问题（标注列没有引入新错误）
    write(actorA, () -> jobs.requestPrevalidate(actorA, again.jobId()));
    executor.prevalidateNow(actorA, again.jobId());
    awaitPrevalidated(actorA, again.jobId());
    var recheck = tx(actorA, () -> reports.summary(actorA.tenantId(), again.jobId(), user));
    assertThat(recheck.report().blockingRowCount()).isEqualTo(2);
    assertThat(recheck.report().skippableRowCount()).isEqualTo(1);
    assertThat(recheck.report().cleanRowCount()).isEqualTo(1);
    assertThat(recheck.report().totalRowCount()).isEqualTo(4);
  }

  /** 同一份报告导两次字节一致：导出内容只由报告决定，不含时间戳等易变项。 */
  @Test
  void 同一份报告导出两次字节一致() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));

    var first =
        write(
            actorA,
            () ->
                reports.createExport(
                    actorA.tenantId(),
                    jobId,
                    new ImportValidationExportInput("ANNOTATED_ERRORS", "ALL"),
                    user));
    var second =
        write(
            actorA,
            () ->
                reports.createExport(
                    actorA.tenantId(),
                    jobId,
                    new ImportValidationExportInput("ANNOTATED_ERRORS", "ALL"),
                    user));
    assertThat(second.contentSha256()).isEqualTo(first.contentSha256());
    assertThat(second.byteSize()).isEqualTo(first.byteSize());
    // 下载时现算的字节必须与登记的哈希一致（否则「下载到的」就不是登记的那一份）
    var file = tx(actorA, () -> reports.exportFile(actorA.tenantId(), jobId, first.id(), user));
    assertThat(ImportCsvParser.sha256(file.content())).isEqualTo(first.contentSha256());

    // 导出登记可查：导过什么、基于哪一轮、谁导的
    var list = tx(actorA, () -> reports.exports(actorA.tenantId(), jobId, user));
    assertThat(list.items()).hasSize(2);
    assertThat(list.items())
        .allSatisfy(
            e -> {
              assertThat(e.jobId()).isEqualTo(jobId);
              assertThat(e.stale()).isFalse();
              assertThat(e.contentSha256()).hasSize(64);
            });
  }

  // ================================================================ 快照与过期

  /**
   * 快照语义的成败在此：重跑预校验<b>不会</b>静默改写已有报告，报告会被标为过期， 要显式重新生成才换成新结论。
   *
   * <p>这条直接对应 V34 文件头的理由：{@code import_row_error} 的重跑是 delete + 重写， 而报告页是运营逐条核对的现场 ——
   * 中途换掉它，比让它显示「已过期」危险得多。
   */
  @Test
  void 重跑预校验不会静默改写已有报告且会被标为过期() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));

    var before = tx(actorA, () -> reports.summary(actorA.tenantId(), jobId, user));
    assertThat(before.report().stale()).isFalse();
    long issuesBefore = countIssues(actorA, before.report().id());

    // 重跑一次预校验（映射没变也算重跑：上游允许，因为补规则后要重算）
    write(actorA, () -> jobs.requestPrevalidate(actorA, jobId));
    executor.prevalidateNow(actorA, jobId);
    awaitPrevalidated(actorA, jobId);

    var after = tx(actorA, () -> reports.summary(actorA.tenantId(), jobId, user));
    assertThat(after.report().id()).as("同一个任务只有一份当前报告").isEqualTo(before.report().id());
    assertThat(after.report().stale()).as("任务在报告生成之后又被写过 → 报告已过期").isTrue();
    assertThat(countIssues(actorA, after.report().id())).as("过期期间明细不被静默改写").isEqualTo(issuesBefore);

    // 显式重新生成后才换成当前结论
    var refreshed = write(actorA, () -> reports.regenerate(actorA.tenantId(), jobId, user));
    assertThat(refreshed.stale()).isFalse();
    var summary = tx(actorA, () -> reports.summary(actorA.tenantId(), jobId, user));
    assertThat(summary.report().stale()).isFalse();
    assertThat(summary.report().blockingRowCount()).isEqualTo(2);

    // 过期报告不允许导出：导出的标注会对应上一轮结论
    write(actorA, () -> jobs.requestPrevalidate(actorA, jobId));
    assertThat(tx(actorA, () -> reports.summary(actorA.tenantId(), jobId, user)).report().stale())
        .isTrue();
    assertThatThrownBy(
            () ->
                write(
                    actorA,
                    () ->
                        reports.createExport(
                            actorA.tenantId(),
                            jobId,
                            new ImportValidationExportInput("ANNOTATED_ERRORS", "ALL"),
                            user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("已经过期");
  }

  long countIssues(AccessService.Access who, UUID reportId) {
    return tx(
        who,
        () ->
            sql.fetchOne("select count(*) from import_validation_issue where report_id=?", reportId)
                .get(0, Long.class));
  }

  @Test
  void 没有预校验结论的任务拿不到报告() {
    UUID tpl = template(actorA, "SPU");
    var summary =
        write(
            actorA,
            () ->
                jobs.upload(
                    actorA.tenantId(),
                    tpl,
                    "CREATE",
                    Map.of(),
                    "import.csv",
                    ("商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + unique("SPU-") + ",x,,,,\r\n")
                        .getBytes(StandardCharsets.UTF_8)));
    assertThatThrownBy(
            () -> tx(actorA, () -> reports.summary(actorA.tenantId(), summary.jobId(), user)))
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("还没有预校验结论");
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言() throws Exception {
    UUID idA = template(actorA, "SPU");
    UUID idB = template(actorB, "SPU");
    String existingA = unique("SPU-");
    seedSpu(actorA, existingA);
    String existingB = unique("SPU-");
    seedSpu(actorB, existingB);
    UUID jobA = prevalidatedJob(actorA, idA, "CREATE", sampleCsv(unique("SPU-"), existingA));
    UUID jobB = prevalidatedJob(actorB, idB, "CREATE", sampleCsv(unique("SPU-"), existingB));
    var reportA = tx(actorA, () -> reports.summary(actorA.tenantId(), jobA, user));
    var reportB = tx(actorB, () -> reports.summary(actorB.tenantId(), jobB, user));
    assertThat(reportA.report().jobId()).isEqualTo(jobA);
    assertThat(reportB.report().jobId()).isEqualTo(jobB);

    // 1) 两租户互不可见：报告、明细、导出登记、导出字节全部隔离
    assertThatThrownBy(() -> tx(actorA, () -> reports.summary(actorA.tenantId(), jobB, user)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> reports.summary(actorB.tenantId(), jobA, user)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                tx(
                    actorA,
                    () -> reports.issues(actorA.tenantId(), jobB, user, null, null, null, 0, 50)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorA, () -> reports.exports(actorA.tenantId(), jobB, user)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(
            () ->
                tx(
                    actorA,
                    () -> reports.exportFile(actorA.tenantId(), jobB, reportB.report().id(), user)))
        .isInstanceOf(ResponseStatusException.class);
    assertThat(
            tx(
                actorA,
                () -> sql.fetchOne("select id from import_validation_report where job_id=?", jobB)))
        .isNull();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(
            get("/catalog/import-jobs/{id}/report", jobA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/import-jobs/{id}/report/issues", jobA)
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(
            get("/catalog/import-jobs/{id}/report/exports/{eid}/file", jobA, reportA.report().id())
                .header("Authorization", "Bearer tenant-b")
                .header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actorA, () -> reports.summary(actorA.tenantId(), jobA, user)).report().jobId())
          .isEqualTo(jobA);
      assertThat(tx(actorB, () -> reports.summary(actorB.tenantId(), jobB, user)).report().jobId())
          .isEqualTo(jobB);
      assertThat(
              tx(
                      actorA,
                      () ->
                          sql.fetchOne(
                              "select count(*) from import_validation_report where job_id=?", jobA))
                  .get(0, Long.class))
          .isEqualTo(1L);
      assertThat(
              tx(
                      actorB,
                      () ->
                          sql.fetchOne(
                              "select count(*) from import_validation_report where job_id=?", jobB))
                  .get(0, Long.class))
          .isEqualTo(1L);
    }
  }

  /**
   * DB 层的强隔离：带着 A 的租户上下文直接写 B 的行会被 RLS 拒绝。
   *
   * <p>这是「FORCE RLS 不是摆设」的证据 —— 服务层永远带正确的 tenant_id， 真正要防的是有人绕过服务层直连 SQL。用直连 SQL 才测得出这一层。
   */
  @Test
  void 伪造租户上下文的直接写入被RLS拒绝() {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));
    var report = tx(actorA, () -> reports.summary(actorA.tenantId(), jobId, user));

    // 用 B 的租户上下文写「属于 A 的任务」的报告行 → RLS 的 WITH CHECK 挡住
    assertThatThrownBy(
            () ->
                tx(
                    actorB,
                    () ->
                        sql.execute(
                            "insert into import_validation_report(id,tenant_id,job_id,source_prevalidated_at,source_status,"
                                + "total_row_count,blocking_row_count,skippable_row_count,clean_row_count,issue_row_count,"
                                + "error_count,warning_count,actor_id) values(?,?,?,now(),'PREVALIDATED',1,0,0,1,0,0,0,?)",
                            UUID.randomUUID(),
                            a,
                            jobId,
                            user)))
        .as("RLS 必须让伪造租户的直接写入失败，否则 RLS 只是摆设")
        .hasMessageContaining("import_validation_report");

    // 用 A 的租户上下文读 B 的报告 → 读不到（RLS 的 USING 挡住）
    assertThat(
            tx(
                actorA,
                () -> sql.fetchOne("select id from import_validation_report where tenant_id=?", b)))
        .isNull();
    assertThat(
            tx(
                    actorA,
                    () ->
                        sql.fetchOne(
                            "select count(*) from import_validation_issue where report_id=?",
                            report.report().id()))
                .get(0, Integer.class))
        .as("明细确实落在本租户的上下文里")
        .isPositive();

    // 报告的三张表都必须是 FORCE RLS（表 owner 也绕不过去）
    for (String table :
        List.of(
            "import_validation_report", "import_validation_issue", "import_validation_export")) {
      var row =
          tx(
              actorA,
              () ->
                  sql.fetchOne(
                      "select relrowsecurity,relforcerowsecurity from pg_class"
                          + " where relname=? and relnamespace='public'::regnamespace",
                      table));
      assertThat(row).as("%s 必须存在", table).isNotNull();
      assertThat(row.get("relrowsecurity", Boolean.class)).as("%s 要 ENABLE RLS", table).isTrue();
      assertThat(row.get("relforcerowsecurity", Boolean.class))
          .as("%s 要 FORCE RLS", table)
          .isTrue();
    }
  }

  // ================================================================ 端点

  @Test
  void 端点六个都通且只读权限不能生成或导出() throws Exception {
    UUID tpl = template(actorA, "SPU");
    String existing = unique("SPU-");
    seedSpu(actorA, existing);
    UUID jobId = prevalidatedJob(actorA, tpl, "CREATE", sampleCsv(unique("SPU-"), existing));

    http.perform(
            get("/catalog/import-jobs/{id}/report", jobId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.report.jobId").value(jobId.toString()))
        .andExpect(jsonPath("$.report.blockingRowCount").value(2))
        .andExpect(jsonPath("$.report.skippableRowCount").value(1))
        .andExpect(jsonPath("$.report.stale").value(false))
        .andExpect(jsonPath("$.categories.length()").value(6));

    http.perform(
            post("/catalog/import-jobs/{id}/report", jobId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stale").value(false));

    http.perform(
            get("/catalog/import-jobs/{id}/report/issues", jobId)
                .param("severity", "ERROR")
                .param("category", "REQUIRED_MISSING")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(2))
        .andExpect(jsonPath("$.items[0].fieldKey").value("spu_code"));

    // 定位到一行
    http.perform(
            get("/catalog/import-jobs/{id}/report/issues", jobId)
                .param("rowNo", "4")
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].rowNo").value(4));

    // 生成导出（登记）→ 下载
    var created =
        http.perform(
                post("/catalog/import-jobs/{id}/report/exports", jobId)
                    .header("Authorization", "Bearer tenant-a")
                    .contentType("application/json")
                    .content("{\"exportKind\":\"FIXED_TEMPLATE\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.rowCount").value(3))
            .andReturn()
            .getResponse()
            .getContentAsString();
    UUID exportId = UUID.fromString(JSON.readTree(created).get("id").asText());

    http.perform(
            get("/catalog/import-jobs/{id}/report/exports", jobId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items.length()").value(1));

    http.perform(
            get("/catalog/import-jobs/{id}/report/exports/{eid}/file", jobId, exportId)
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
        .andExpect(
            header().string("Content-Type", org.hamcrest.Matchers.containsString("text/csv")));

    // 未知的导出种类 / 未知类别都是 400
    http.perform(
            post("/catalog/import-jobs/{id}/report/exports", jobId)
                .header("Authorization", "Bearer tenant-a")
                .contentType("application/json")
                .content("{\"exportKind\":\"NOPE\"}"))
        .andExpect(status().isBadRequest());

    // 只读权限不能重新生成、不能导出（这两个是真写库的动作）
    http.perform(
            post("/catalog/import-jobs/{id}/report", jobId)
                .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
    http.perform(
            post("/catalog/import-jobs/{id}/report/exports", jobId)
                .header("Authorization", "Bearer reader")
                .contentType("application/json")
                .content("{\"exportKind\":\"ANNOTATED_ERRORS\"}"))
        .andExpect(status().isForbidden());
    // 只读权限可以读报告
    http.perform(
            get("/catalog/import-jobs/{id}/report", jobId).header("Authorization", "Bearer reader"))
        .andExpect(status().isOk());

    // 不存在的导出记录 404
    http.perform(
            get("/catalog/import-jobs/{id}/report/exports/{eid}/file", jobId, UUID.randomUUID())
                .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isNotFound());
  }
}
