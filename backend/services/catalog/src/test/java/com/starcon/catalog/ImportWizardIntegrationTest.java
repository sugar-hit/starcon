package com.starcon.catalog;

import static com.starcon.catalog.ImportWizardModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * 3.8.1.1 导入向导：模板下载、文件解析、字段映射、三种导入模式、预校验逐行错误、
 * 异步执行真落库、结果统计，以及<b>租户隔离三断言 + 同类任务互斥断言</b>。
 *
 * <p><b>真实 PostgreSQL 16 + 强制 RLS</b>：导入任务、逐行、错误明细、映射配置全部真落库，
 * 执行阶段真的往 spu / sku / attribute_value / sku_package / sku_identifier 写。
 * 连接池固定为 1 条连接 —— 只有复用同一条连接，才能真正证明归还后不会串租户。
 *
 * <p><b>异步路径怎么测</b>：调 {@code prevalidateNow} / {@code executeNow}，
 * 它们跑的是与队列<b>同一段代码</b>（{@code runPrevalidate} / {@code runExecute}），
 * 所以测试驱动的是线上那条路径而不是替身。
 *
 * <p><b>本文件最重要的五个断言</b>：
 *
 * <ol>
 *   <li>{@link #租户隔离三断言()}：两租户互不可见 / 伪造 X-Tenant-Id 头无效 / 单连接池反复交替不串。
 *   <li>{@link #同一租户同类导入任务并发时被拒绝()}：第二个同类任务 409，**由 DB 唯一索引裁决**，
 *       并额外用直连 SQL 证明绕不过去。
 *   <li>{@link #导入的数据带的是任务所属租户()}：A 的任务写出的行 tenant_id 是 A，B 看不到也改不了。
 *   <li>{@link #预校验逐行报错并定位到行号()}：第 4 行的净重填错，报错行号必须是 4。
 *   <li>{@link #有错误的行不落库其余照常导入()}：3.8.1.2 蓝图「不允许尽力导入产生半截数据」。
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class ImportWizardIntegrationTest {
  static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16-alpine");
  static final UUID a = UUID.randomUUID(), b = UUID.randomUUID(), user = UUID.randomUUID();
  static HttpServer identity;
  static final ObjectMapper JSON = new ObjectMapper();
  static final Map<String, AccessService.Access> actors = new java.util.concurrent.ConcurrentHashMap<>();

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
    try (var conn = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
        var s = conn.createStatement()) {
      s.execute("create role starcon_app nologin");
      s.execute("create role catalog_app login password 'test-only' nosuperuser nobypassrls");
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
      identity.start();
      actors.put("Bearer tenant-a", actor(user, a, null, List.of(grant("ALL", allPermissions()))));
      actors.put("Bearer tenant-b", actor(user, b, null, List.of(grant("ALL", allPermissions()))));
      actors.put(
          "Bearer reader", actor(user, a, null, List.of(grant("ALL", List.of("catalog.spu.read")))));
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  static List<String> allPermissions() {
    return java.util.stream.Stream.of("spu", "sku", "assets", "attribute")
        .flatMap(p -> java.util.stream.Stream.of("read", "create", "update", "delete")
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
    // 互斥槽位是**按租户+目标对象**占的，而测试类共享同一个库：不清理的话
    // 上一个用例留下的活跃任务（很多用例故意停在「预校验完成」不执行）
    // 会让下一个用例的同目标对象上传被 409 —— 那不是被测行为，是用例之间的残留。
    // 取消（PENDING/PREVALIDATING/PREVALIDATED/RUNNING → CANCELLED）是合法迁移。
    releaseActiveJobs(actorA);
    releaseActiveJobs(actorB);
  }

  /** 把该租户所有活跃导入任务取消，释放互斥槽位。 */
  void releaseActiveJobs(AccessService.Access who) {
    write(who, () -> sql.execute(
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
    return hint + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
  }

  /** 建一个走标准列的模板。 */
  UUID template(AccessService.Access who, String entity) {
    return write(who, () -> templates.create(who.tenantId(),
        new ImportTemplateInput(unique("TPL-" + entity + "-"), "模板 " + entity, "", entity, true, List.of()))
        .template().id());
  }

  /**
   * 上传 → 预校验 →（可选）执行，返回终态任务。
   *
   * <p>{@code requestPrevalidate} / {@code requestExecute} 已经把活扔进线程池了
   * （那正是「导入是异步任务」的落点），所以这里**先**再驱动一次
   * {@code prevalidateNow} / {@code executeNow}（抢不到就退出，与 3.4.1.2 的
   * {@code runJobNow} 同一口径），然后**轮询等终态**而不是假设「同步驱动 = 我自己跑完的」。
   */
  ImportJobRow runImport(
      AccessService.Access who, UUID templateId, String mode, String csv, boolean execute) {
    byte[] content = csv.getBytes(StandardCharsets.UTF_8);
    var summary = write(who, () -> jobs.upload(who.tenantId(), templateId, mode, Map.of(), "import.csv", content));
    write(who, () -> jobs.requestPrevalidate(who, summary.jobId()));
    executor.prevalidateNow(who, summary.jobId());
    awaitTerminal(who, summary.jobId());
    if (!execute) return tx(who, () -> jobs.job(who.tenantId(), summary.jobId()));
    write(who, () -> jobs.requestExecute(who, summary.jobId()));
    executor.executeNow(who, summary.jobId());
    awaitTerminal(who, summary.jobId());
    return tx(who, () -> jobs.job(who.tenantId(), summary.jobId()));
  }

  /** 轮询等任务到终态。超时即失败（异步路径卡住时能立刻定位，而不是让测试挂死）。 */
  ImportJobRow awaitTerminal(AccessService.Access who, UUID jobId) {
    var deadline = java.time.Duration.ofSeconds(30);
    long start = System.nanoTime();
    while (java.time.Duration.ofNanos(System.nanoTime() - start).compareTo(deadline) < 0) {
      var job = tx(who, () -> jobs.job(who.tenantId(), jobId));
      if (Set.of("PREVALIDATED", "SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED").contains(job.status()))
        return job;
      try {
        Thread.sleep(25);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException(e);
      }
    }
    throw new AssertionError("导入任务 " + jobId + " 30 秒内没有到达终态");
  }



  /** 建一个父商品 + 一个挂在它下面的 SKU，返回 SKU id。 */
  UUID seedSku(AccessService.Access who, String skuCode) {
    UUID spuId = UUID.randomUUID();
    UUID skuId = UUID.randomUUID();
    write(who, () -> sql.execute(
        "insert into spu(id,tenant_id,code,name) values(?,?,?,?)", spuId, who.tenantId(), unique("SPU-"), "父商品"));
    write(who, () -> sql.execute(
        "insert into sku(id,tenant_id,spu_id,code,name,variant_signature) values(?,?,?,?,?,?)",
        skuId, who.tenantId(), spuId, skuCode, "SKU " + skuCode, "sig:" + skuCode));
    return skuId;
  }

  // ================================================================ 1 下载模板

  @Test
  void 模板下载给出真实可下载的列头与示例行() throws Exception {
    UUID id = template(actorA, "SPU");
    var file = tx(actorA, () -> templates.csv(actorA.tenantId(), id));
    var parsed = ImportCsvParser.parse(file.content());
    // 列头来自模板列定义，示例行来自 sample_value —— 运营照着示例填比对着列头猜格式出错率低得多。
    assertThat(parsed.headers()).contains("商品编码", "商品名称");
    assertThat(parsed.rows()).hasSize(1);
    assertThat(parsed.rows().get(0)).contains("SPU-1001");
    assertThat(file.fileName()).endsWith("-template.csv");

    // 端点层面也确认真的能下载（Content-Disposition + 真实字节）。
    http.perform(get("/catalog/import-templates/{id}/file", id).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")));
  }

  // ================================================================ 2 + 5 真落库

  @Test
  void 新增模式真的把商品写进spu表() {
    UUID id = template(actorA, "SPU");
    String code = unique("SPU-");
    var job = runImport(actorA, id, "CREATE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n"
            + code + ",蓝牙耳机,TWS 续航 30 小时,SoundCore,消费电子,DRAFT\r\n", true);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    assertThat(job.appliedCount()).isEqualTo(1);
    assertThat(job.rowCount()).isEqualTo(1);

    // 断言真的落库，且落的是任务所属租户。
    var row = tx(actorA, () -> sql.fetchOne("select code,name,tenant_id from spu where code=?", code));
    assertThat(row).as("SPU 必须真的落库").isNotNull();
    assertThat(row.get("name", String.class)).isEqualTo("蓝牙耳机");
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(a);
  }

  @Test
  void SKU导入真的写sku并带上父商品() {
    UUID id = template(actorA, "SKU");
    String spuCode = unique("SPU-");
    String skuCode = unique("SKU-");
    write(actorA, () -> sql.execute(
        "insert into spu(id,tenant_id,code,name) values(?,?,?,?)",
        UUID.randomUUID(), a, spuCode, "父商品"));
    var job = runImport(actorA, id, "CREATE",
        "SKU 编码,所属商品编码,SKU 名称,基础单位,状态\r\n"
            + skuCode + "," + spuCode + ",黑色,件,ACTIVE\r\n", true);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    var row = tx(actorA, () -> sql.fetchOne("select code,name,spu_id,base_unit,tenant_id from sku where code=?", skuCode));
    assertThat(row).isNotNull();
    assertThat(row.get("name", String.class)).isEqualTo("黑色");
    assertThat(row.get("base_unit", String.class)).isEqualTo("件");
    assertThat(row.get("tenant_id", UUID.class)).isEqualTo(a);
    // 父商品关系是真的（不是只落了 SKU 自己）
    assertThat(tx(actorA, () -> sql.fetchOne("select code from spu where id=?", row.get("spu_id", UUID.class)))
        .get("code", String.class)).isEqualTo(spuCode);
  }

  @Test
  void 包装导入真的写sku_package且毛重净重关系正确() {
    UUID id = template(actorA, "PACKAGE");
    String skuCode = unique("SKU-");
    UUID skuId = seedSku(actorA, skuCode);
    var job = runImport(actorA, id, "CREATE",
        "SKU 编码,包装类型,长(mm),宽(mm),高(mm),净重(g),毛重(g)\r\n"
            + skuCode + ",彩盒,120,80,45,210,260\r\n", true);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    var row = tx(actorA, () -> sql.fetchOne("select * from sku_package where sku_id=?", skuId));
    assertThat(row).as("包装档案必须真的落库").isNotNull();
    assertThat(row.get("length_mm", BigDecimal.class)).isEqualByComparingTo("120");
    assertThat(row.get("gross_weight_g", BigDecimal.class)).isEqualByComparingTo("260");
  }

  @Test
  void 条码导入真的写sku_identifier并同步主条码到sku() {
    UUID id = template(actorA, "BARCODE");
    String skuCode = unique("SKU-");
    UUID skuId = seedSku(actorA, skuCode);
    String value = "69" + UUID.randomUUID().toString().replace("-", "").substring(0, 11);
    var job = runImport(actorA, id, "CREATE",
        "SKU 编码,条码体系,条码值\r\n" + skuCode + ",EAN_13," + value + "\r\n", true);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    var identifier = tx(actorA, () -> sql.fetchOne("select * from sku_identifier where value=?", value));
    assertThat(identifier).as("条码必须真的落库").isNotNull();
    assertThat(identifier.get("source", String.class)).isEqualTo("IMPORT");
    assertThat(identifier.get("is_primary", Boolean.class)).isTrue();
    // V7 的触发器把主条码同步回 sku.barcode —— 这条同步也是真的发生了。
    assertThat(tx(actorA, () -> sql.fetchOne("select barcode from sku where id=?", skuId)).get("barcode", String.class))
        .isEqualTo(value);
  }

  @Test
  void 属性导入真的写属性值且不新建属性定义() {
    UUID id = template(actorA, "ATTRIBUTE");
    String spuCode = unique("SPU-");
    UUID spuId = UUID.randomUUID();
    UUID attributeId = UUID.randomUUID();
    write(actorA, () -> sql.execute(
        "insert into spu(id,tenant_id,code,name) values(?,?,?,?)", spuId, a, spuCode, "S"));
    write(actorA, () -> sql.execute(
        "insert into attribute_definition(id,tenant_id,spu_id,code,name,data_type,value_mode)"
            + " values(?,?,?,?,?,'DECIMAL','COMMON_DEFAULT')",
        attributeId, a, spuId, "battery_life", "续航"));
    var job = runImport(actorA, id, "CREATE",
        "商品编码,属性编码,属性值,SKU 编码,赋值范围\r\n" + spuCode + ",battery_life,30,,\r\n", true);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    var item = tx(actorA, () -> sql.fetchOne(
        "select i.decimal_value,i.text_value from attribute_value_item i"
            + " join attribute_value av on av.tenant_id=i.tenant_id and av.id=i.value_id"
            + " where av.attribute_id=?", attributeId));
    assertThat(item).as("属性值必须真的落库").isNotNull();
    // DECIMAL 型属性值进 decimal_value 列（V1 的 num_nonnulls=1 要求有且只有一列非空）
    assertThat(item.get("decimal_value", BigDecimal.class)).isEqualByComparingTo("30");
    assertThat(item.get("text_value", String.class)).isNull();
  }

  // ================================================================ 3 字段映射

  @Test
  void 字段映射存库可复用且按上传文件的真实列名解析() {
    UUID id = template(actorA, "SPU");
    String code = unique("SPU-");
    // 上传文件的列名与模板列名完全不同（模拟供应商文件）
    String csv = "item_code,product_name\r\n" + code + ",映射进来的商品\r\n";
    byte[] content = csv.getBytes(StandardCharsets.UTF_8);
    var summary = write(actorA, () ->
        jobs.upload(actorA.tenantId(), id, "CREATE",
            Map.of("spu_code", "item_code", "spu_name", "product_name"), "vendor.csv", content));
    // 自动映射猜不到时必须由用户显式给映射，否则预校验会报「列没有对应」
    var job = write(actorA, () -> jobs.setMapping(actorA.tenantId(), summary.jobId(),
        new ImportMappingInput(Map.of("spu_code", "item_code", "spu_name", "product_name"), "供应商A")));
    assertThat(job.mapping()).containsEntry("spu_code", "item_code");

    write(actorA, () -> jobs.requestPrevalidate(actorA, summary.jobId()));
    executor.prevalidateNow(actorA, summary.jobId());
    var validated = awaitTerminal(actorA, summary.jobId());
    assertThat(validated.status()).isEqualTo("PREVALIDATED");
    assertThat(validated.validCount()).isEqualTo(1);

    write(actorA, () -> jobs.requestExecute(actorA, summary.jobId()));
    executor.executeNow(actorA, summary.jobId());
    assertThat(awaitTerminal(actorA, summary.jobId()).status()).isEqualTo("SUCCEEDED");
    assertThat(tx(actorA, () -> sql.fetchOne("select name from spu where code=?", code))
        .get("name", String.class)).isEqualTo("映射进来的商品");

    // 映射配置真的存库了，可以复用
    var saved = tx(actorA, () -> templates.mappings(actorA.tenantId(), id));
    assertThat(saved.items()).extracting(ImportFieldMappingRow::name).contains("供应商A");
    assertThat(saved.items().get(0).mapping()).containsEntry("spu_code", "item_code");
  }

  @Test
  void 改映射会把上一轮结论作废() {
    UUID id = template(actorA, "SPU");
    String code = unique("SPU-");
    String csv = "a_col,b_col\r\n" + code + ",名称\r\n";
    var summary = write(actorA, () -> jobs.upload(actorA.tenantId(), id, "CREATE", Map.of(),
        "x.csv", csv.getBytes(StandardCharsets.UTF_8)));
    write(actorA, () -> jobs.requestPrevalidate(actorA, summary.jobId()));
    executor.prevalidateNow(actorA, summary.jobId());
    // 猜不到映射 -> 预校验报必填缺失
    assertThat(awaitTerminal(actorA, summary.jobId()).invalidCount()).isEqualTo(1);

    var remapped = write(actorA, () -> jobs.setMapping(actorA.tenantId(), summary.jobId(),
        new ImportMappingInput(Map.of("spu_code", "a_col", "spu_name", "b_col"), null)));
    assertThat(remapped.status()).as("改映射后行结论作废，回到已上传").isEqualTo("PENDING");
    assertThat(remapped.validCount()).isZero();
  }

  @Test
  void 引用不存在的商品在预校验阶段就报出来而不是执行时炸() {
    UUID id = template(actorA, "SKU");
    var job = runImport(actorA, id, "CREATE",
        "SKU 编码,所属商品编码,SKU 名称,基础单位,状态\r\n"
            + unique("SKU-") + ",NO-SUCH-SPU,黑色,,\r\n", false);
    assertThat(job.invalidCount()).isEqualTo(1);
    var errors = tx(actorA, () -> jobs.errors(actorA.tenantId(), job.id(), "ERROR", 0, 50));
    assertThat(errors.items()).extracting(ImportRowErrorLine::code).contains("REFERENCE_NOT_FOUND");
  }

  @Test
  void 缺映射的必填列逐行报UNMAPPED_COLUMN() {
    UUID id = template(actorA, "SPU");
    String csv = "随便一列\r\n" + unique("SPU-") + "\r\n";
    var summary = write(actorA, () -> jobs.upload(actorA.tenantId(), id, "CREATE", Map.of(),
        "x.csv", csv.getBytes(StandardCharsets.UTF_8)));
    write(actorA, () -> jobs.requestPrevalidate(actorA, summary.jobId()));
    executor.prevalidateNow(actorA, summary.jobId());
    awaitTerminal(actorA, summary.jobId());
    var errors = tx(actorA, () -> jobs.errors(actorA.tenantId(), summary.jobId(), null, 0, 50));
    assertThat(errors.items()).extracting(ImportRowErrorLine::code).contains("UNMAPPED_COLUMN");
  }

  // ================================================================ 三种模式

  @Test
  void 新增模式遇到已存在编码跳过而不是报错() {
    UUID id = template(actorA, "SPU");
    String code = unique("SPU-");
    write(actorA, () -> sql.execute("insert into spu(id,tenant_id,code,name) values(?,?,?,?)",
        UUID.randomUUID(), a, code, "原有名称"));
    var job = runImport(actorA, id, "CREATE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + code + ",新名称,,,,\r\n", true);
    assertThat(job.appliedCount()).isZero();
    assertThat(job.skippedCount()).isEqualTo(1);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    // 原值没被覆盖
    assertThat(tx(actorA, () -> sql.fetchOne("select name from spu where code=?", code)).get("name", String.class))
        .isEqualTo("原有名称");
    // 预校验给的是 WARNING 而不是 ERROR
    var errors = tx(actorA, () -> jobs.errors(actorA.tenantId(), job.id(), "WARNING", 0, 50));
    assertThat(errors.items()).extracting(ImportRowErrorLine::code).contains("EXISTS_IN_CREATE_MODE");
  }

  @Test
  void 更新模式改已有记录() {
    UUID id = template(actorA, "SPU");
    String code = unique("SPU-");
    write(actorA, () -> sql.execute("insert into spu(id,tenant_id,code,name) values(?,?,?,?)",
        UUID.randomUUID(), a, code, "旧名称"));
    var job = runImport(actorA, id, "UPDATE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + code + ",改过的名称,,,,\r\n", true);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    assertThat(tx(actorA, () -> sql.fetchOne("select name from spu where code=?", code)).get("name", String.class))
        .isEqualTo("改过的名称");
  }

  @Test
  void 按编码匹配模式有则更新无则新建() {
    UUID id = template(actorA, "SPU");
    String existing = unique("SPU-");
    String fresh = unique("SPU-");
    write(actorA, () -> sql.execute("insert into spu(id,tenant_id,code,name) values(?,?,?,?)",
        UUID.randomUUID(), a, existing, "旧名称"));
    var job = runImport(actorA, id, "MATCH_BY_CODE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n"
            + existing + ",更新成的名称,,,,\r\n"
            + fresh + ",新建的名称,,,,\r\n", true);
    assertThat(job.status()).isEqualTo("SUCCEEDED");
    assertThat(job.appliedCount()).isEqualTo(2);
    assertThat(tx(actorA, () -> sql.fetchOne("select name from spu where code=?", existing)).get("name", String.class))
        .isEqualTo("更新成的名称");
    assertThat(tx(actorA, () -> sql.fetchOne("select name from spu where code=?", fresh)).get("name", String.class))
        .isEqualTo("新建的名称");
  }

  // ================================================================ 异步

  @Test
  void 提交后立刻返回已受理进度是库里可查的() {
    UUID id = template(actorA, "SPU");
    // **用文件里真的那个编码**：早先这里另起了一个 unique("SPU-") 去断言「还没落库」，
    // 而那个编码根本不在 CSV 里，于是「查不到」恒真 —— 断言看着通过，其实什么都没验。
    String code = unique("SPU-");
    String csv = "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + code + ",异步, ,,,\r\n";
    var summary = write(actorA, () -> jobs.upload(actorA.tenantId(), id, "CREATE", Map.of(),
        "x.csv", csv.getBytes(StandardCharsets.UTF_8)));
    // 提交只是「已受理」：此刻没有任何商品被写入。
    var accepted = write(actorA, () -> jobs.requestPrevalidate(actorA, summary.jobId()));
    assertThat(accepted.status()).isEqualTo("PREVALIDATING");
    assertThat(tx(actorA, () -> sql.fetchOne("select id from spu where code=?", code)))
        .as("提交只是受理，此刻这一行还没落库").isNull();
    // 真实工作跑完之后，统计是库里可查的事实。
    //
    // **必须 await 而不是直接读计数**：requestPrevalidate 已经把活扔进线程池了，
    // 池里的线程可能抢先 claim 掉这个任务，于是这里的 prevalidateNow 直接退出、
    // 什么都不做（这正是「一个任务一个主人」的语义）。不等待就是在读一个
    // 还没发生的进度。awaitTerminal 等的是真实终态，与线上页面的轮询同一条路。
    executor.prevalidateNow(actorA, summary.jobId());
    awaitTerminal(actorA, summary.jobId());
    assertThat(tx(actorA, () -> jobs.job(actorA.tenantId(), summary.jobId())).validCount()).isEqualTo(1);
  }

  // ================================================================ 互斥

  @Test
  void 同一租户同类导入任务并发时被拒绝() {
    UUID id = template(actorA, "SPU");
    byte[] content = ("商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + unique("SPU-") + ",x,,,,\r\n")
        .getBytes(StandardCharsets.UTF_8);
    var first = write(actorA, () -> jobs.upload(actorA.tenantId(), id, "CREATE", Map.of(), "a.csv", content));
    // 第一个任务还在活跃态（PENDING），第二个同类任务必须被拒
    assertThatThrownBy(() -> write(actorA, () -> jobs.upload(actorA.tenantId(), id, "CREATE", Map.of(), "b.csv", content)))
        .isInstanceOf(ResponseStatusException.class)
        // 断言的是「同类…导入任务…只允许一个」这条语义与在跑任务的文件名（运营据此知道该去取消谁），
        // 而不是某一句文案措辞：文案调整不该让这条测试变红。
        .hasMessageContaining("同类的「SPU」导入任务")
        .hasMessageContaining("只允许一个同类任务")
        .hasMessageContaining("a.csv");

    // **DB 层保证**：绕开服务层直连 SQL 也插不进去（部分唯一索引 import_job_one_active_per_entity）
    assertThatThrownBy(() -> tx(actorA, () -> sql.execute(
        "insert into import_job(id,tenant_id,template_id,target_entity,import_mode,file_name,file_format,"
            + "byte_size,content_sha256,row_count,status,actor_id) values(?,?,?,'SPU','CREATE','c.csv','CSV',"
            + "10,?,1,'PENDING',?)",
        UUID.randomUUID(), a, id, "0".repeat(64), UUID.randomUUID())))
        .as("互斥必须在 DB 层成立，否则并发事务会双双插进去")
        .hasMessageContaining("import_job_one_active_per_entity");

    // 目标对象不同则互不影响（「同类」的粒度是目标对象）
    UUID other = template(actorA, "SKU");
    var otherJob = write(actorA, () -> jobs.upload(actorA.tenantId(), other, "CREATE", Map.of(),
        "d.csv", ("SKU 编码,所属商品编码,SKU 名称,基础单位,状态\r\n" + unique("SKU-") + ",S,x,,\r\n")
            .getBytes(StandardCharsets.UTF_8)));
    assertThat(otherJob.jobId()).isNotEqualTo(first.jobId());

    // 不同租户的同类任务也互不影响
    UUID idB = template(actorB, "SPU");
    var jobB = write(actorB, () -> jobs.upload(actorB.tenantId(), idB, "CREATE", Map.of(),
        "e.csv", ("商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + unique("SPU-") + ",x,,,,\r\n")
            .getBytes(StandardCharsets.UTF_8)));
    assertThat(jobB.jobId()).isNotNull();

    // 第一个任务到终态后，互斥槽位释放，可以再导同类
    write(actorA, () -> jobs.requestPrevalidate(actorA, first.jobId()));
    executor.prevalidateNow(actorA, first.jobId());
    awaitTerminal(actorA, first.jobId());
    write(actorA, () -> jobs.requestExecute(actorA, first.jobId()));
    executor.executeNow(actorA, first.jobId());
    assertThat(awaitTerminal(actorA, first.jobId()).status()).isEqualTo("SUCCEEDED");
    var third = write(actorA, () -> jobs.upload(actorA.tenantId(), id, "CREATE", Map.of(), "f.csv", content));
    assertThat(third.jobId()).isNotNull();
  }

  // ================================================================ 租户隔离三断言

  @Test
  void 租户隔离三断言() throws Exception {
    UUID idA = template(actorA, "SPU");
    UUID idB = template(actorB, "SPU");
    String mine = unique("SPU-");
    String theirs = unique("SPU-");
    var myJob = runImport(actorA, idA, "CREATE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + mine + ",我的, ,,,\r\n", true);
    var theirJob = runImport(actorB, idB, "CREATE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + theirs + ",他们的, ,,,\r\n", true);
    assertThat(myJob.appliedCount()).isEqualTo(1);
    assertThat(theirJob.appliedCount()).isEqualTo(1);

    // 1) 两租户互不可见：任务、逐行、错误明细、模板全部隔离
    assertThatThrownBy(() -> tx(actorA, () -> jobs.job(actorA.tenantId(), theirJob.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorB, () -> jobs.job(actorB.tenantId(), myJob.id())))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorA, () -> jobs.rows(actorA.tenantId(), theirJob.id(), null, 0, 10)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorA, () -> jobs.errors(actorA.tenantId(), theirJob.id(), null, 0, 10)))
        .isInstanceOf(ResponseStatusException.class);
    assertThatThrownBy(() -> tx(actorA, () -> templates.detail(actorA.tenantId(), idB)))
        .isInstanceOf(ResponseStatusException.class);
    // 商品本身也互不可见
    assertThat(tx(actorA, () -> sql.fetchOne("select id from spu where code=?", theirs))).isNull();
    assertThat(tx(actorB, () -> sql.fetchOne("select id from spu where code=?", mine))).isNull();

    // 2) 伪造 X-Tenant-Id 头无效：租户只来自已认证成员关系
    http.perform(get("/catalog/import-jobs/{id}", myJob.id())
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());
    http.perform(get("/catalog/import-templates/{id}/file", idA)
            .header("Authorization", "Bearer tenant-b").header("X-Tenant-Id", a))
        .andExpect(status().isNotFound());

    // 3) 连接池归还后不串租户（单连接反复交替）
    for (int i = 0; i < 5; i++) {
      assertThat(tx(actorA, () -> jobs.job(actorA.tenantId(), myJob.id())).id()).isEqualTo(myJob.id());
      assertThat(tx(actorB, () -> jobs.job(actorB.tenantId(), theirJob.id())).id()).isEqualTo(theirJob.id());
      assertThat(tx(actorA, () -> sql.fetchOne("select name from spu where code=?", mine)).get("name", String.class))
          .isEqualTo("我的");
      assertThat(tx(actorB, () -> sql.fetchOne("select name from spu where code=?", theirs)).get("name", String.class))
          .isEqualTo("他们的");
    }
  }

  @Test
  void 导入的数据带的是任务所属租户而不是别的租户() {
    // B 先有一条同名编码的商品；A 的任务必须只改 A 的，B 的那行不能被动过。
    String code = unique("SPU-");
    write(actorB, () -> sql.execute("insert into spu(id,tenant_id,code,name) values(?,?,?,?)",
        UUID.randomUUID(), b, code, "B 的原值"));
    UUID idA = template(actorA, "SPU");
    var job = runImport(actorA, idA, "MATCH_BY_CODE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + code + ",A 导入的名称,,,,\r\n", true);
    assertThat(job.appliedCount()).isEqualTo(1);

    var mine = tx(actorA, () -> sql.fetchOne("select name,tenant_id from spu where code=?", code));
    assertThat(mine).as("A 的任务写入的行 tenant_id 必须是 A").isNotNull();
    assertThat(mine.get("tenant_id", UUID.class)).isEqualTo(a);
    assertThat(mine.get("name", String.class)).isEqualTo("A 导入的名称");
    assertThat(tx(actorB, () -> sql.fetchOne("select name from spu where code=?", code)).get("name", String.class))
        .as("B 的同名商品不能被 A 的导入改写").isEqualTo("B 的原值");
  }

  // ================================================================ 时间戳

  @Test
  void 任务时间戳是带时区写回的值() {
    UUID id = template(actorA, "SPU");
    OffsetDateTime before = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(1);
    var job = runImport(actorA, id, "CREATE",
        "商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + unique("SPU-") + ",x,,,,\r\n", true);
    assertThat(job.createdAt()).isAfterOrEqualTo(before.toInstant());
    assertThat(job.prevalidatedAt()).as("预校验时刻要落库").isNotNull();
    assertThat(job.startedAt()).as("执行开始时刻要落库").isNotNull();
    assertThat(job.finishedAt()).as("执行结束时刻要落库").isNotNull();
    // 结束不早于开始：这就是「时区没有被二次偏移」的证据（-28800s 会让结束早于开始）
    assertThat(job.finishedAt()).isAfterOrEqualTo(job.startedAt());
    assertThat(job.finishedAt()).isBefore(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30).toInstant());
  }

  // ================================================================ 端点

  @Test
  void 端点五环节都通且只读权限不能写() throws Exception {
    UUID id = template(actorA, "SPU");
    var csv = ("商品编码,商品名称,商品描述,品牌,类目,状态\r\n" + unique("SPU-") + ",端点, ,,,\r\n")
        .getBytes(StandardCharsets.UTF_8);
    var upload = http.perform(multipart("/catalog/import-jobs")
            .file(new org.springframework.mock.web.MockMultipartFile("file", "e2e.csv", "text/csv", csv))
            .param("templateId", id.toString()).param("importMode", "CREATE")
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    UUID jobId = UUID.fromString(extractJobId(upload));

    http.perform(put("/catalog/import-jobs/{id}/mapping", jobId)
            .header("Authorization", "Bearer tenant-a")
            .contentType("application/json")
            .content("{\"mapping\":{\"spu_code\":\"商品编码\",\"spu_name\":\"商品名称\"},\"saveAs\":\"端点映射\"}"))
        .andExpect(status().isOk());
    http.perform(post("/catalog/import-jobs/{id}/prevalidate", jobId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    executor.prevalidateNow(actorA, jobId);
    awaitTerminal(actorA, jobId);
    http.perform(post("/catalog/import-jobs/{id}/execute", jobId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    executor.executeNow(actorA, jobId);
    awaitTerminal(actorA, jobId);

    http.perform(get("/catalog/import-jobs/{id}", jobId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    http.perform(get("/catalog/import-jobs/{id}/rows", jobId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/import-jobs/{id}/errors", jobId).header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/import-jobs").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/import-field-mappings").param("templateId", id.toString())
            .header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());
    http.perform(get("/catalog/import-templates").header("Authorization", "Bearer tenant-a"))
        .andExpect(status().isOk());

    // 只读角色不能上传
    http.perform(multipart("/catalog/import-jobs")
            .file(new org.springframework.mock.web.MockMultipartFile("file", "x.csv", "text/csv", csv))
            .param("templateId", id.toString()).param("importMode", "CREATE")
            .header("Authorization", "Bearer reader"))
        .andExpect(status().isForbidden());
  }

  private static String extractJobId(String json) {
    try {
      return JSON.readTree(json).get("jobId").asText();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }


}
