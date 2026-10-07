package com.starcon.catalog;

import static com.starcon.catalog.ImportWizardModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * 3.8.1.1 的异步执行器：把「预校验」与「执行」两个阶段真的做完。
 *
 * <p>蓝图：「<b>导入是异步任务</b>，大文件不阻塞页面」。本类与
 * {@link ImportJobService#requestPrevalidate} / {@code requestExecute} 合起来才是这句话的落点：
 * 那两个方法只推进任务状态就返回，本类在请求线程之外把活干完。
 *
 * <h2>租户上下文必须显式绑定</h2>
 * FORCE RLS 下每个事务都要有 {@code app.current_tenant}，而工作线程没有 HTTP 请求带来的上下文。
 * 所以每次访问库都用提交瞬间记下的 <b>tenant_id</b> 开事务 —— 那个 tenant_id 来自
 * {@link AccessService.Access}（已认证成员关系解析的结果），不是客户端能指定的字段。
 * 这一条同时保证了「导入的数据带的是导入任务所属租户」：工作线程里没有任何路径能拿到别的租户。
 *
 * <h2>分批推进，进度落在库里</h2>
 * 预校验与执行都按 {@value #BATCH} 行一批处理，每批一个事务并立刻回写计数。
 * 于是：进度是<b>库里可查的事实</b>（换进程、重启都不丢），单个事务的时长与文件大小无关，
 * 页面轮询到的永远是真实进度而不是内存里的计数器。
 *
 * <h2>单个失败不影响整批</h2>
 * 200 行里 3 行不合法时，用户要的是「哪 3 行、为什么」，而不是全部白跑。
 * 每一行独立提交，失败行记 FAILED + 原因，其余照常落库。
 */
@Component
public class ImportJobExecutor {
  private static final Logger log = LoggerFactory.getLogger(ImportJobExecutor.class);

  /**
   * 每批行数。
   *
   * <p>200 行一批：一次事务里要写的行数够少，锁持有时间短（避免与 3.1.2.1 的批量操作互相卡），
   * 又不至于把往返次数拉到几千次。批大小直接决定「大文件不阻塞」的体感 ——
   * 20 万行按 200 行一批是 1000 批，每批一次往返。
   */
  private static final int BATCH = 200;

  /** in (...) 占位符一次最多放多少个值（PG 参数上限 65535，留足余量）。 */
  private static final int IN_CHUNK = 500;

  private final DSLContext sql;
  private final AccessService access;
  private final ExecutorService pool;

  public ImportJobExecutor(DSLContext sql, AccessService access) {
    this.sql = sql;
    this.access = access;
    this.pool = Executors.newFixedThreadPool(2, new ThreadFactory() {
      private final AtomicInteger seq = new AtomicInteger();

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r, "import-wizard-" + seq.incrementAndGet());
        t.setDaemon(false);
        return t;
      }
    });
  }

  // ================================================================ 入口

  /**
 * 排一个预校验任务。入队失败不抛：任务行已在库里，可以重跑。
 *
   * <p><b>带的是 {@link AccessService.Access} 而不是裸 tenantId</b>：写入目标表
   * （spu / sku / ...）被 V3 的 {@code stamp_record_owner} 触发器与 {@code data_scope}
   * 策略要求 {@code app.actor} / {@code app.permission} / {@code app.grants}，
   * 只绑 {@code app.current_tenant} 的话每一行写入都会被拒
   * （"Authenticated actor required"）。而这份 Access 来自请求线程里
   * {@link AccessService#require} 的解析结果 —— 即已认证成员关系，不是客户端指定的字段。
   */
  public boolean enqueuePrevalidate(AccessService.Access actor, UUID jobId) {
    return enqueue(actor, jobId, "预校验", "catalog.spu.read");
  }

  /** 排一个执行任务。写权限用 spu.update —— 执行阶段真的要落库。 */
  public boolean enqueueExecute(AccessService.Access actor, UUID jobId) {
    return enqueue(actor, jobId, "执行", "catalog.spu.update");
  }

  private boolean enqueue(
      AccessService.Access actor, UUID jobId, String what, String permission) {
    UUID tenantId = actor.tenantId();
    try {
      pool.execute(() -> {
        try {
          if ("预校验".equals(what)) prevalidateNow(actor, permission, jobId);
          else executeNow(actor, permission, jobId);
        } catch (RuntimeException e) {
          log.error("导入任务 {} {}失败", jobId, what, e);
          failJob(tenantId, jobId, what + "中断：" + readable(e));
        }
      });
      return true;
    } catch (RejectedExecutionException e) {
      log.warn("导入任务 {} 未能入队（线程池已满），任务仍留在库里可重新提交", jobId);
      return false;
    }
  }

  /** 同步跑完预校验（集成测试与「立刻要结果」的场景用；与异步路径同一段代码）。 */
  public void prevalidateNow(AccessService.Access actor, UUID jobId) {
    prevalidateNow(actor, "catalog.spu.read", jobId);
  }

  public void prevalidateNow(AccessService.Access actor, String permission, UUID jobId) {
    runPrevalidate(actor, permission, jobId);
  }

  /** 同步跑完执行。 */
  public void executeNow(AccessService.Access actor, UUID jobId) {
    executeNow(actor, "catalog.spu.update", jobId);
  }

  public void executeNow(AccessService.Access actor, String permission, UUID jobId) {
    runExecute(actor, permission, jobId);
  }

  @jakarta.annotation.PreDestroy
  void shutdown() {
    pool.shutdown();
  }

  // ================================================================ 预校验

  /**
   * 预校验：逐行按模板规则与库内事实判定，产出逐行结论 + 逐行错误。
   *
   * <p>判定完全复用 {@link ImportRules#validateRow}，与执行阶段「这行写不写」用同一个
   * {@link ImportRules#blocking} 判据 —— 预校验说能导的，执行不会跳过；
   * 预校验说有 ERROR 的，执行一定不落库。
   */
  private void runPrevalidate(AccessService.Access actor, String permission, UUID jobId) {
    UUID tenantId = actor.tenantId();
    if (!claim(actor, permission, jobId, "PREVALIDATING")) return;
    ImportJobRow job = access.run(actor, permission, () -> jobs().job(tenantId, jobId));
    ImportRules.Entity entity = ImportRules.entityOf(job.targetEntity());
    ImportRules.Mode mode = ImportRules.modeOf(job.importMode());
    if (entity == null || mode == null) {
      failJob(tenantId, jobId, "任务的目标对象或导入模式不合法");
      return;
    }
    List<ImportRules.Column> columns = access.run(actor, permission, () -> jobs().ruleColumns(tenantId, job.templateId()));
    Map<String, String> mapping = job.mapping();

    // 1) 清掉上一轮结论（重跑时页面上不能同时挂着两轮互相矛盾的结论）
    access.run(actor, permission, () -> {
      sql.execute("delete from import_row_error where job_id=?", jobId);
      sql.execute("update import_job_row set outcome='PENDING',error_count=0 where job_id=?", jobId);
      sql.execute("update import_job set valid_count=0,invalid_count=0 where id=?", jobId);
      return null;
    });

    // 2) 一次性把本文件用到的库内事实查出来（只查这个文件里出现的编码，不全表扫）
    Facts facts = access.run(actor, permission, () -> loadFacts(tenantId, entity, columns, mapping, jobId));

    // 3) 逐批判定。游标是**最后一个处理过的 row_no**，不是 offset ——
    // 判定完的行会从 'PENDING' 变成 'VALID'/'INVALID'，也就是从下一次查询的
    // 结果集里消失。用 offset 分页会在每一批之后跳过 BATCH 行（第一批处理 1..200，
    // 第二批 offset=200 于是从 401 开始），大批量导入会静默漏掉整段数据。
    // 「WHERE row_no > ? ORDER BY row_no LIMIT ?」不受结果集收缩影响。
    int cursor = 0;
    int valid = 0, invalid = 0;
    while (true) {
      final int after = cursor;
      var slice = access.run(actor, permission, () -> rowSlice(tenantId, jobId, after));
      if (slice.isEmpty()) break;
      List<Object[]> rowUpdates = new ArrayList<>(slice.size());
      List<Object[]> errorRows = new ArrayList<>();
      for (var row : slice) {
        int rowNo = row.get("row_no", Integer.class);
        Map<String, String> cells = ImportTemplateService.readJson(row.get("cells"));
        Map<String, String> values = ImportJobService.resolveValues(columns, mapping, cells);

        List<ImportRules.Problem> problems = new ArrayList<>();
        problems.addAll(unmappedProblems(columns, mapping));
        problems.addAll(
            ImportRules.validateRow(entity, mode, columns, values, facts.forRow(values, tenantId, sql)));

        boolean blocking = ImportRules.blocking(problems);
        if (blocking) invalid++;
        else valid++;
        rowUpdates.add(new Object[] {
            blocking ? "INVALID" : "VALID", problems.size(), row.get("id", UUID.class), jobId
        });
        for (var p : problems)
          errorRows.add(new Object[] {
              Ids.next(), tenantId, jobId, rowNo, p.fieldKey(), p.code(), p.message(), p.severity().name()
          });
      }
      final int v = valid, inv = invalid;
      access.run(actor, permission, () -> {
        for (Object[] bindings : rowUpdates)
          sql.execute("update import_job_row set outcome=?,error_count=? where id=? and job_id=?", bindings);
        // on conflict do nothing：唯一键是 (job,row,field,code)，它的语义就是
        // 「同一行的同一处问题只记一条」。重复插入（重跑与并发分批交错）应当被忽略，
        // 而不是让整批预校验失败。
        for (Object[] bindings : errorRows)
          sql.execute(
              "insert into import_row_error(id,tenant_id,job_id,row_no,field_key,code,message,severity)"
                  + " values(?,?,?,?,?,?,?,?)"
                  + " on conflict (tenant_id,job_id,row_no,field_key,code) do nothing",
              bindings);
        sql.execute("update import_job set valid_count=?,invalid_count=? where id=?", v, inv, jobId);
        return null;
      });
      cursor = rowNoOf(slice.get(slice.size() - 1));
    }

    // 4) 落 PREVALIDATED（触发器补 prevalidated_at）
    final int finalValid = valid, finalInvalid = invalid;
    access.run(actor, permission, () -> {
      sql.execute(
          "update import_job set status='PREVALIDATED',valid_count=?,invalid_count=? where id=? and status='PREVALIDATING'",
          finalValid, finalInvalid, jobId);
      return null;
    });
  }

  /** 模板里声明了、但这次映射没有对应到文件列的必填列 —— 逐行报错而不是整批失败。 */
  private static List<ImportRules.Problem> unmappedProblems(
      List<ImportRules.Column> columns, Map<String, String> mapping) {
    List<ImportRules.Problem> out = new ArrayList<>();
    for (var c : columns) {
      if (c.required() && !mapping.containsKey(c.fieldKey()))
        out.add(ImportRules.Problem.error(
            c.fieldKey(), "UNMAPPED_COLUMN",
            "模板列「" + c.fieldKey() + "」是必填列，但这次映射没有对应到文件里的任何一列。"
                + "请在「字段映射」里选中它对应的那一列"));
    }
    return out;
  }

  // ================================================================ 执行

  /**
   * 执行：把预校验判定为 VALID 的行真的写进商品表。
   *
   * <p>有 ERROR 的行**不落库**（3.8.1.2 蓝图：「校验失败的行不落库，不允许『尽力导入』产生半截数据」）。
   * CREATE 模式下编码已存在的行是 WARNING，落成 SKIPPED 而不是报错。
   */
  private void runExecute(AccessService.Access actor, String permission, UUID jobId) {
    UUID tenantId = actor.tenantId();
    if (!claim(actor, permission, jobId, "RUNNING")) return;
    ImportJobRow job = access.run(actor, permission, () -> jobs().job(tenantId, jobId));
    ImportRules.Entity entity = ImportRules.entityOf(job.targetEntity());
    ImportRules.Mode mode = ImportRules.modeOf(job.importMode());
    if (entity == null || mode == null) {
      failJob(tenantId, jobId, "任务的目标对象或导入模式不合法");
      return;
    }
    List<ImportRules.Column> columns = access.run(actor, permission, () -> jobs().ruleColumns(tenantId, job.templateId()));

    int cursor = 0, applied = 0, failed = 0, skipped = 0;
    while (true) {
      final int after = cursor;
      var slice = access.run(actor, permission, () -> validSlice(tenantId, jobId, after));
      if (slice.isEmpty()) break;
      List<Object[]> updates = new ArrayList<>(slice.size());
      for (var row : slice) {
        UUID rowId = row.get("id", UUID.class);
        int rowNo = row.get("row_no", Integer.class);
        Map<String, String> values =
            ImportJobService.resolveValues(columns, job.mapping(), ImportTemplateService.readJson(row.get("cells")));
        try {
          // 每行独立事务：这一行失败不影响同一批里其它行落库。
          Applied applied1 = access.run(actor, permission, () -> apply(tenantId, entity, mode, values));
          if (applied1.skipped()) skipped++;
          else applied++;
          updates.add(new Object[] {
              applied1.skipped() ? "SKIPPED" : "IMPORTED", 0, applied1.spuId(), applied1.skuId(), rowId, jobId});
        } catch (RuntimeException e) {
          failed++;
          String reason = readable(e);
          log.warn("导入任务 {} 第 {} 行写入失败：{}", jobId, rowNo, reason);
          updates.add(new Object[] {"FAILED", 1, null, null, rowId, jobId});
          // 失败原因同样进 import_row_error，页面上「这一行为什么没进来」是同一份数据。
          final int rowNoFinal = rowNo;
          final String message = reason;
          access.run(actor, permission, () -> {
            sql.execute(
                "insert into import_row_error(id,tenant_id,job_id,row_no,field_key,code,message,severity)"
                    + " values(?,?,?,?,'','WRITE_FAILED',?,'ERROR')"
                    + " on conflict (tenant_id,job_id,row_no,field_key,code) do update set message=excluded.message",
                Ids.next(), tenantId, jobId, rowNoFinal, message);
            return null;
          });
        }
      }
      final int a = applied, f = failed, s = skipped;
      access.run(actor, permission, () -> {
        for (Object[] bindings : updates)
          sql.execute(
              "update import_job_row set outcome=?,error_count=?,spu_id=?,sku_id=? where id=? and job_id=?",
              bindings);
        sql.execute(
            "update import_job set applied_count=?,failed_count=?,skipped_count=? where id=?", a, f, s, jobId);
        return null;
      });
      cursor = rowNoOf(slice.get(slice.size() - 1));
    }

    final int finalApplied = applied, finalFailed = failed, finalSkipped = skipped;
    access.run(actor, permission, () -> {
      String status = finalFailed == 0 ? "SUCCEEDED" : (finalApplied > 0 || finalSkipped > 0 ? "PARTIAL" : "FAILED");
      sql.execute(
          "update import_job set status=?,applied_count=?,failed_count=?,skipped_count=?"
              + " where id=? and status='RUNNING'",
          status, finalApplied, finalFailed, finalSkipped, jobId);
      return null;
    });
  }

  /** 一行的写入结果。 */
  private record Applied(boolean skipped, UUID spuId, UUID skuId) {}

  /**
   * 写一行。
   *
   * <p>三个模式的语义在这里落地：
   * CREATE 只新建、已存在即跳过；UPDATE 只更新、不存在即报错（预校验已拦，这里是兜底）；
   * MATCH_BY_CODE 有则更新无则新建。
   */
  private Applied apply(UUID tenantId, ImportRules.Entity entity, ImportRules.Mode mode, Map<String, String> v) {
    return switch (entity) {
      case SPU -> applySpu(tenantId, mode, v);
      case SKU -> applySku(tenantId, mode, v);
      case ATTRIBUTE -> applyAttribute(tenantId, mode, v);
      case PACKAGE -> applyPackage(tenantId, mode, v);
      case BARCODE -> applyBarcode(tenantId, mode, v);
    };
  }

  // ---------------------------------------------------------------- SPU

  private Applied applySpu(UUID tenantId, ImportRules.Mode mode, Map<String, String> v) {
    String code = v.get("spu_code");
    var existing = sql.fetchOne("select id,status from spu where code=?", code);
    if (mode == ImportRules.Mode.CREATE && existing != null) return new Applied(true, existing.get("id", UUID.class), null);
    if (mode == ImportRules.Mode.UPDATE && existing == null)
      throw new IllegalStateException("更新模式要求商品已存在，但找不到编码 " + code);

    String status = v.getOrDefault("status", "").isBlank() ? "DRAFT" : v.get("status").trim().toUpperCase(Locale.ROOT);
    if (existing == null) {
      UUID id = Ids.next();
      sql.execute(
          "insert into spu(id,tenant_id,code,name,description,brand,category,status)"
              + " values(?,?,?,?,?,?,?,?)",
          id, tenantId, code, v.getOrDefault("spu_name", ""), v.getOrDefault("description", ""),
          v.getOrDefault("brand", ""), v.getOrDefault("category", ""), status);
      return new Applied(false, id, null);
    }
    UUID id = existing.get("id", UUID.class);
    // 状态只能沿生命周期合法转换，所以导入不得改已非草稿商品的状态 ——
    // 「批量导入」不是「批量改状态」，后者是 3.1.1.3 的职责且要求填变更原因。
    if (existing.get("status", String.class).equals("DRAFT") && !status.equals("DRAFT"))
      sql.execute("update spu set status=? where id=? and status='DRAFT'", status, id);
    sql.execute(
        "update spu set name=?,description=?,brand=?,category=?,version=version+1,updated_at=now() where id=?",
        v.getOrDefault("spu_name", ""), v.getOrDefault("description", ""), v.getOrDefault("brand", ""),
        v.getOrDefault("category", ""), id);
    return new Applied(false, id, null);
  }

  // ---------------------------------------------------------------- SKU

  private Applied applySku(UUID tenantId, ImportRules.Mode mode, Map<String, String> v) {
    String code = v.get("sku_code");
    String spuCode = v.get("spu_code");
    var spu = sql.fetchOne("select id from spu where code=?", spuCode);
    if (spu == null) throw new IllegalStateException("所属商品 " + spuCode + " 在本租户不存在");
    UUID spuId = spu.get("id", UUID.class);

    var existing = sql.fetchOne("select id,status from sku where code=?", code);
    // CREATE 模式遇到已存在编码 → **跳过**，与 applySpu 同一口径。
    // 早先这里返回 skipped=false，结果是这一行被计成 applied：页面上写着
    // 「成功导入 1 条」而实际一条都没写进去（SPU 那边的同种情况返回 true，SKU 这边返回 false，
    // 同一个 CREATE 语义在两类对象上给出不同的统计，是最难查的那类不一致）。
    if (mode == ImportRules.Mode.CREATE && existing != null) return new Applied(true, null, existing.get("id", UUID.class));
    if (mode == ImportRules.Mode.UPDATE && existing == null)
      throw new IllegalStateException("更新模式要求 SKU 已存在，但找不到编码 " + code);

    String status = v.getOrDefault("status", "").isBlank() ? "DRAFT" : v.get("status").trim().toUpperCase(Locale.ROOT);
    String name = v.getOrDefault("sku_name", "");
    String unit = v.getOrDefault("base_unit", "").isBlank() ? "件" : v.get("base_unit").trim();
    if (existing == null) {
      UUID id = Ids.next();
      // variant_signature 是同 SPU 下规格组合的唯一键。导入的 SKU 没有规格轴，
      // 用自身编码当签名：它在本租户内唯一，因此满足 UNIQUE(tenant_id,spu_id,variant_signature)，
      // 且「两行同编码」在预校验阶段就已经被 DUPLICATE_IN_FILE 拦掉了。
      sql.execute(
          "insert into sku(id,tenant_id,spu_id,code,name,base_unit,status,variant_signature)"
              + " values(?,?,?,?,?,?,?,?)",
          id, tenantId, spuId, code, name, unit, status, "IMPORT:" + code);
      return new Applied(false, spuId, id);
    }
    UUID id = existing.get("id", UUID.class);
    sql.execute(
        "update sku set name=?,base_unit=?,version=version+1,updated_at=now() where id=?",
        name, unit, id);
    return new Applied(false, spuId, id);
  }

  // ---------------------------------------------------------------- 属性

  /**
   * 写一条属性值。
   *
   * <p>属性是<b>依附于已有属性定义</b>的值：{@code attribute_definition} 由 3.2.1.1 管，
   * 导入只写值（{@code attribute_value} + {@code attribute_value_item}）。
   * 引用不存在的属性定义会在这里报错，不新建定义 —— 否则一次导入会静默造出一堆
   * 没有类目归属、没有规格轴绑定的属性字典项。
   *
   * <p>公共锁定 / 仅 SKU 两种赋值范围由 {@code validate_value_owner} 触发器兜底，
   * 这里按定义里的 value_mode 决定写公共值还是 SKU 值。
   */
  private Applied applyAttribute(UUID tenantId, ImportRules.Mode mode, Map<String, String> v) {
    String spuCode = v.get("spu_code");
    String attributeCode = v.get("attribute_code");
    var definition = sql.fetchOne(
        "select d.id,d.data_type,d.value_mode,s.id as spu_id from attribute_definition d"
            + " join spu s on s.tenant_id=d.tenant_id and s.id=d.spu_id"
            + " where s.code=? and d.code=?",
        spuCode, attributeCode);
    if (definition == null)
      throw new IllegalStateException("属性 " + attributeCode + " 在商品 " + spuCode + " 下不存在（请先在属性定义里建好）");
    UUID attributeId = definition.get("id", UUID.class);
    UUID spuId = definition.get("spu_id", UUID.class);
    String valueMode = definition.get("value_mode", String.class);

    UUID skuId = null;
    if ("SKU_ONLY".equals(valueMode)) {
      String skuCode = v.getOrDefault("sku_code", "");
      if (skuCode.isBlank())
        throw new IllegalStateException("属性 " + attributeCode + " 是「仅 SKU」属性，必须填 SKU 编码");
      var sku = sql.fetchOne("select id from sku where code=?", skuCode);
      if (sku == null) throw new IllegalStateException("SKU " + skuCode + " 在本租户不存在");
      skuId = sku.get("id", UUID.class);
    } else if (!v.getOrDefault("sku_code", "").isBlank()) {
      // 公共属性却填了 SKU 编码：不是「聪明地猜」，是明确报错。
      throw new IllegalStateException(
          "属性 " + attributeCode + " 是公共属性（" + valueMode + "），不能指定 SKU；指定 SKU 的值请用 SKU 属性覆盖");
    }

    // 先取旧值：UNIQUE NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id) 决定了
    // 「一个属性在一个 SKU 上至多一行」，所以更新就是替换而不是追加。
    var previous = sql.fetchOne(
        "select id from attribute_value where attribute_id=? and sku_id is not distinct from ?",
        attributeId, skuId);
    if (previous == null && mode == ImportRules.Mode.UPDATE)
      throw new IllegalStateException("更新模式要求该属性已有值，但商品 " + spuCode + " 的 " + attributeCode + " 还没有值");

    UUID valueId = previous == null ? Ids.next() : previous.get("id", UUID.class);
    if (previous == null)
      sql.execute(
          "insert into attribute_value(id,tenant_id,spu_id,attribute_id,sku_id,state) values(?,?,?,?,?,'SET')",
          valueId, tenantId, spuId, attributeId, skuId);
    else
      sql.execute("delete from attribute_value_item where value_id=?", valueId);

    String text = v.getOrDefault("attribute_value", "");
    writeTypedValue(tenantId, valueId, attributeId, definition.get("data_type", String.class), text);
    sql.execute("update spu set version=version+1,updated_at=now() where id=?", spuId);
    return new Applied(false, spuId, skuId);
  }

  /**
   * 按属性定义的数据类型写值列。
   *
   * <p>五个值列里<b>有且只有一个</b>非空（V1 的 {@code num_nonnulls(...)=1} CHECK 要求），
   * 所以这里按类型挑一列、其余留 null。预校验已经按列声明的 data_type 判过一遍，
   * 这一层是兜底：预校验判的是「文本能否转成该类型」，这里判的是「转完能不能落进该列」。
   */
  private void writeTypedValue(UUID tenantId, UUID valueId, UUID attributeId, String dataType, String text) {
    String textValue = null;
    BigDecimal decimalValue = null;
    Boolean booleanValue = null;
    java.sql.Date dateValue = null;
    String optionValue = null;
    switch (dataType) {
      case "DECIMAL" -> decimalValue = new BigDecimal(text.trim());
      case "BOOLEAN" -> booleanValue = switch (text.trim().toLowerCase(Locale.ROOT)) {
        case "true", "是", "1", "y" -> Boolean.TRUE;
        case "false", "否", "0", "n" -> Boolean.FALSE;
        default -> throw new IllegalStateException("布尔值只能是 true/false、是/否、1/0，当前是「" + text + "」");
      };
      case "DATE" -> dateValue = java.sql.Date.valueOf(LocalDate.parse(text.trim()));
      case "OPTION" -> {
        optionValue = text.trim();
        long exists =
            sql.fetchOne(
                    "select count(*) from attribute_option where attribute_id=? and value=?",
                    attributeId, optionValue)
                .get(0, Long.class);
        if (exists == 0) throw new IllegalStateException("选项「" + optionValue + "」不在该属性的选项字典里");
      }
      default -> textValue = text;
    }
    sql.execute(
        "insert into attribute_value_item(tenant_id,value_id,attribute_id,ordinal,text_value,"
            + "decimal_value,boolean_value,date_value,option_value) values(?,?,?,0,?,?,?,?,?)",
        tenantId, valueId, attributeId, textValue, decimalValue, booleanValue, dateValue, optionValue);
  }

  // ---------------------------------------------------------------- 包装

  /** 写包装档案。sku_package 是「一个 SKU 至多一行」（UNIQUE(tenant_id,sku_id)），所以是 upsert。 */
  private Applied applyPackage(UUID tenantId, ImportRules.Mode mode, Map<String, String> v) {
    String skuCode = v.get("sku_code");
    var sku = sql.fetchOne("select id from sku where code=?", skuCode);
    if (sku == null) throw new IllegalStateException("SKU " + skuCode + " 在本租户不存在");
    UUID skuId = sku.get("id", UUID.class);
    var existing = sql.fetchOne("select id from sku_package where sku_id=?", skuId);
    if (mode == ImportRules.Mode.UPDATE && existing == null)
      throw new IllegalStateException("更新模式要求包装档案已存在，但 SKU " + skuCode + " 还没有包装档案");

    BigDecimal len = decimalOrNull(v.get("length_mm"));
    BigDecimal wid = decimalOrNull(v.get("width_mm"));
    BigDecimal hei = decimalOrNull(v.get("height_mm"));
    BigDecimal net = decimalOrNull(v.get("net_weight_g"));
    BigDecimal gross = decimalOrNull(v.get("gross_weight_g"));
    String type = v.getOrDefault("packaging_type", "");
    if (existing == null) {
      sql.execute(
          "insert into sku_package(id,tenant_id,sku_id,length_mm,width_mm,height_mm,net_weight_g,"
              + "gross_weight_g,packaging_type) values(?,?,?,?,?,?,?,?,?)",
          Ids.next(), tenantId, skuId, len, wid, hei, net, gross, type);
    } else {
      sql.execute(
          "update sku_package set length_mm=?,width_mm=?,height_mm=?,net_weight_g=?,gross_weight_g=?,"
              + "packaging_type=?,version=version+1,updated_at=now() where id=?",
          len, wid, hei, net, gross, type, existing.get("id", UUID.class));
    }
    return new Applied(false, null, skuId);
  }

  // ---------------------------------------------------------------- 条码

  /**
   * 写条码。
   *
   * <p>条码值在「同一体系下全租户唯一」（UNIQUE(tenant_id,scheme,value)），所以：
   * 同一个值已经挂在别的 SKU 上 → 这一行失败并说明条码已占用；
   * 同一个值已经挂在同一 SKU 上 → 视为重新指定主条码。
   *
   * <p>主条码只有一个（每个 scheme 至多一个，见 V7 的部分唯一索引），
   * 所以先把该 SKU 该 scheme 下别的主条码撤下来，再把本行设为主条码。
   * sku.barcode 由 V7 的 sync_primary_barcode 触发器同步，本模块不双写。
   */
  private Applied applyBarcode(UUID tenantId, ImportRules.Mode mode, Map<String, String> v) {
    String skuCode = v.get("sku_code");
    var sku = sql.fetchOne("select id from sku where code=?", skuCode);
    if (sku == null) throw new IllegalStateException("SKU " + skuCode + " 在本租户不存在");
    UUID skuId = sku.get("id", UUID.class);
    String scheme = v.getOrDefault("scheme", "").trim().toUpperCase(Locale.ROOT);
    String value = v.getOrDefault("barcode_value", "").trim();

    var owner = sql.fetchOne("select sku_id from sku_identifier where scheme=? and value=?", scheme, value);
    if (owner != null && !owner.get("sku_id", UUID.class).equals(skuId))
      throw new IllegalStateException("条码 " + scheme + " " + value + " 已被另一个 SKU 占用，一个条码只能属于一个 SKU");

    // 撤下该 SKU 该 scheme 下别的主条码（一个 scheme 至多一个主条码）
    sql.execute(
        "update sku_identifier set is_primary=false where sku_id=? and scheme=? and is_primary and value<>?",
        skuId, scheme, value);
    if (owner == null)
      sql.execute(
          "insert into sku_identifier(id,tenant_id,sku_id,scheme,value,is_primary,source,occurred_at)"
              + " values(?,?,?,?,?,true,'IMPORT',now())",
          Ids.next(), tenantId, skuId, scheme, value);
    else
      sql.execute(
          "update sku_identifier set is_primary=true,source='IMPORT',updated_at=now() where tenant_id=? and scheme=? and value=?",
          tenantId, scheme, value);
    return new Applied(false, null, skuId);
  }

  // ================================================================ 库内事实

  /**
   * 预校验要用到的库内事实，<b>只查本文件里出现过的编码</b>。
   *
   * <p>不做全表扫：一个 50 万 SKU 的租户，导一批 20 行却把全表读进内存是不可接受的。
   * 逐行查又会把预校验变成 N 次往返（20 万行 = 20 万次）。所以先把文件里的编码
   * 收集出来，用 {@code in (...)} 分块查一次。
   */
  private static class Facts {
    final Set<String> spuCodes = new HashSet<>();
    final Map<String, UUID> spuIds = new LinkedHashMap<>();
    final Set<String> skuCodes = new HashSet<>();
    final Map<String, UUID> skuIds = new LinkedHashMap<>();
    final Set<String> skuBySpu = new HashSet<>();
    final Set<String> attributeKeys = new HashSet<>();
    /** (spu_code␟attribute_code) → value_mode。 */
    final Map<String, String> attributeModes = new LinkedHashMap<>();
    final Set<String> existingAttributeValues = new HashSet<>();
    final Set<String> skusWithPackage = new HashSet<>();
    final Set<String> identifierKeys = new HashSet<>();
    /** 值 → 归属 SKU 编码（用于「条码已被别的 SKU 占用」）。 */
    final Map<String, String> identifierOwner = new LinkedHashMap<>();

    /**
     * 某一行的事实。
     *
     * @param duplicateInFile 同一自然键在文件里出现第二次（第一行已被登记）
     */
    ImportRules.Facts forRow(Map<String, String> values, UUID tenantId, DSLContext sql) {
      String key = ImportRules.naturalKey(entity, values);
      boolean duplicate = !seen.add(key);
      String matchValue = values.get(ImportRules.matchKeyOf(entity));
      boolean matchExists = exists(entity, values);
      ImportRules.Problem reference = referenceProblem(entity, values);
      List<ImportRules.Problem> extra = new ArrayList<>();
      if (entity == ImportRules.Entity.BARCODE) {
        String valueKey = values.getOrDefault("scheme", "").trim().toUpperCase(Locale.ROOT)
            + "|" + values.getOrDefault("barcode_value", "").trim();
        String owner = identifierOwner.get(valueKey);
        if (owner != null && !owner.equals(values.get("sku_code")))
          extra.add(ImportRules.Problem.error("barcode_value", "BARCODE_TAKEN",
              "条码 " + valueKey + " 已经挂在 SKU " + owner + " 上，一个条码只能属于一个 SKU"));
      }
      return new ImportRules.Facts(matchValue, matchExists, duplicate, reference, extra);
    }

    ImportRules.Entity entity;

    /** 文件内已出现过的自然键。 */
    final Set<String> seen = new HashSet<>();

    private boolean exists(ImportRules.Entity entity, Map<String, String> values) {
      return switch (entity) {
        case SPU -> spuCodes.contains(trim(values.get("spu_code")));
        case SKU -> skuCodes.contains(trim(values.get("sku_code")));
        case ATTRIBUTE -> existingAttributeValues.contains(ImportRules.naturalKey(entity, values));
        case PACKAGE -> skusWithPackage.contains(trim(values.get("sku_code")));
        case BARCODE -> identifierKeys.contains(
            values.getOrDefault("scheme", "").trim().toUpperCase(Locale.ROOT) + "|"
                + values.getOrDefault("barcode_value", "").trim());
      };
    }

    /** 引用完整性：引用的商品 / SKU / 属性定义必须在本租户存在。 */
    private ImportRules.Problem referenceProblem(ImportRules.Entity entity, Map<String, String> values) {
      return switch (entity) {
        case SPU, BARCODE, PACKAGE -> null;
        case SKU -> spuCodes.contains(trim(values.get("spu_code")))
            ? null
            : ImportRules.Problem.error("spu_code", "REFERENCE_NOT_FOUND",
                "所属商品 " + trim(values.get("spu_code")) + " 在本租户不存在，请先导入商品或改对编码");
        case ATTRIBUTE -> {
          String key = ImportRules.naturalKey(entity, values);
          if (attributeKeys.contains(key)) yield null;
          boolean spuMissing = !spuCodes.contains(trim(values.get("spu_code")));
          yield spuMissing
              ? ImportRules.Problem.error("spu_code", "REFERENCE_NOT_FOUND",
                  "商品 " + trim(values.get("spu_code")) + " 在本租户不存在，请先导入商品或改对编码")
              : ImportRules.Problem.error("attribute_code", "REFERENCE_NOT_FOUND",
                  "商品 " + trim(values.get("spu_code")) + " 下没有属性 " + trim(values.get("attribute_code"))
                      + "，请先在属性定义里建好（导入只写值，不新建属性定义）");
        }
      };
    }

    private static String trim(String v) {
      return v == null ? "" : v.trim();
    }
  }

  /** 收集文件里出现的编码并分块查库。 */
  private Facts loadFacts(
      UUID tenantId, ImportRules.Entity entity, List<ImportRules.Column> columns,
      Map<String, String> mapping, UUID jobId) {
    Facts facts = new Facts();
    facts.entity = entity;
    Set<String> spuCodes = new LinkedHashSet<>();
    Set<String> skuCodes = new LinkedHashSet<>();
    Set<String> attributeCodes = new LinkedHashSet<>();
    /** 本文件里出现的条码值（scheme|value），用来按值反查占用情况。 */
    Set<String> barcodeValues = new LinkedHashSet<>();

    for (var row : sql.fetch("select cells from import_job_row where job_id=?", jobId)) {
      Map<String, String> values =
          ImportJobService.resolveValues(columns, mapping, ImportTemplateService.readJson(row.get("cells")));
      switch (entity) {
        case SPU -> spuCodes.add(trim(values.get("spu_code")));
        case SKU -> {
          spuCodes.add(trim(values.get("spu_code")));
          skuCodes.add(trim(values.get("sku_code")));
        }
        case ATTRIBUTE -> {
          spuCodes.add(trim(values.get("spu_code")));
          attributeCodes.add(trim(values.get("spu_code")) + "␟" + trim(values.get("attribute_code")));
          skuCodes.add(trim(values.get("sku_code")));
        }
        case PACKAGE -> skuCodes.add(trim(values.get("sku_code")));
        case BARCODE -> {
          skuCodes.add(trim(values.get("sku_code")));
          String value = trim(values.get("barcode_value"));
          if (!value.isEmpty())
            barcodeValues.add(trim(values.get("scheme")).toUpperCase(Locale.ROOT) + "|" + value);
        }
      }
    }
    spuCodes.removeIf(String::isEmpty);
    skuCodes.removeIf(String::isEmpty);
    barcodeValues.removeIf(v -> v.endsWith("|"));

    for (var chunk : chunked(spuCodes))
      for (var r : sql.fetch("select id,code from spu where code in (" + placeholders(chunk.size()) + ")", chunk.toArray())) {
        facts.spuCodes.add(r.get("code", String.class));
        facts.spuIds.put(r.get("code", String.class), r.get("id", UUID.class));
      }
    for (var chunk : chunked(skuCodes))
      for (var r : sql.fetch("select id,code,spu_id from sku where code in (" + placeholders(chunk.size()) + ")", chunk.toArray())) {
        facts.skuCodes.add(r.get("code", String.class));
        facts.skuIds.put(r.get("code", String.class), r.get("id", UUID.class));
        UUID spuId = r.get("spu_id", UUID.class);
        facts.skuBySpu.add(spuId + "␟" + r.get("code", String.class));
      }
    if (entity == ImportRules.Entity.ATTRIBUTE) {
      for (var chunk : chunked(attributeCodes)) {
        String[] codes = chunk.stream().map(k -> k.split("␟", -1)[1]).distinct().toArray(String[]::new);
        for (var r : sql.fetch(
            "select d.id,d.code,d.value_mode,s.code as spu_code from attribute_definition d"
                + " join spu s on s.tenant_id=d.tenant_id and s.id=d.spu_id"
                + " where d.code in (" + placeholders(codes.length) + ")", (Object[]) codes)) {
          facts.attributeKeys.add(r.get("spu_code", String.class) + "␟" + r.get("code", String.class));
          facts.attributeModes.put(
              r.get("spu_code", String.class) + "␟" + r.get("code", String.class),
              r.get("value_mode", String.class));
        }
      }
      // 已有属性值（判断 UPDATE 模式的目标是否存在）。同样只查本文件涉及的属性编码 ——
      // 全表扫 attribute_value 在一个属性值几万条的租户上是不可接受的。
      Set<String> distinctAttributeCodes =
          attributeCodes.stream().map(k -> k.split("␟", -1)[1]).collect(java.util.stream.Collectors.toSet());
      for (var chunk : chunked(distinctAttributeCodes))
        for (var r : sql.fetch(
            "select s.code as spu_code,d.code as attribute_code from attribute_value av"
                + " join attribute_definition d on d.tenant_id=av.tenant_id and d.id=av.attribute_id"
                + " join spu s on s.tenant_id=av.tenant_id and s.id=av.spu_id"
                + " where d.code in (" + placeholders(chunk.size()) + ")", chunk.toArray()))
          facts.existingAttributeValues.add(
              r.get("spu_code", String.class) + "␟" + r.get("attribute_code", String.class));
    }
    if (entity == ImportRules.Entity.PACKAGE)
      for (var chunk : chunked(skuCodes))
        for (var r : sql.fetch(
            "select s.code from sku_package p join sku s on s.tenant_id=p.tenant_id and s.id=p.sku_id"
                + " where s.code in (" + placeholders(chunk.size()) + ")", chunk.toArray()))
          facts.skusWithPackage.add(r.get("code", String.class));
    if (entity == ImportRules.Entity.BARCODE) {
      // 只查**本文件里出现过的条码值**，不是整张 sku_identifier ——
      // 一个条码值跨全租户唯一，所以问「这批值有没有被占用」只需要问这几个。
      // 全表扫在这个租户有几十万条码时会把整张表读进内存，而文件里可能只有 20 行。
      for (var value : chunked(barcodeValues)) {
        String[] vals = value.stream().map(k -> k.split("\\|", 2)[1]).distinct().toArray(String[]::new);
        for (var r : sql.fetch(
            "select i.scheme,i.value,s.code as sku_code from sku_identifier i"
                + " join sku s on s.tenant_id=i.tenant_id and s.id=i.sku_id"
                + " where i.value in (" + placeholders(vals.length) + ")", (Object[]) vals)) {
          String key = r.get("scheme", String.class).toUpperCase(Locale.ROOT) + "|" + r.get("value", String.class);
          facts.identifierKeys.add(key);
          facts.identifierOwner.put(key, r.get("sku_code", String.class));
        }
      }
    }
    return facts;
  }

  // ================================================================ 小工具

  /**
   * 下一批预校验的行：{@code row_no > after}，**不用 offset**。
   *
   * <p>原因见 {@code runPrevalidate} 里的说明：判定过的行会离开 'PENDING' 结果集，
   * offset 分页会在每批之后跳过整段数据。keyset（游标）分页不受结果集收缩影响。
   */
  private List<org.jooq.Record> rowSlice(UUID tenantId, UUID jobId, int afterRowNo) {
    return sql.fetch(
        "select id,row_no,cells from import_job_row where job_id=? and outcome='PENDING'"
            + " and row_no>? order by row_no limit ?",
        jobId, afterRowNo, BATCH);
  }

  /** 下一批待执行的行。同样是 keyset 分页，理由同上。 */
  private List<org.jooq.Record> validSlice(UUID tenantId, UUID jobId, int afterRowNo) {
    return sql.fetch(
        "select id,row_no,cells from import_job_row where job_id=? and outcome='VALID'"
            + " and row_no>? order by row_no limit ?",
        jobId, afterRowNo, BATCH);
  }

  /** 一批里最后一行的行号，用作下一批的游标。 */
  private static int rowNoOf(org.jooq.Record row) {
    return row.get("row_no", Integer.class);
  }

  /**
   * 领取任务所有权。命中 0 行说明另一个工作线程已经在跑，本次直接退出。
   *
   * <p>「直接退出」而不是「等对方跑完」：两个执行者互相等待对方交还连接，
   * 在单连接池下会直接死锁。而计数覆盖比死锁更难查 ——
   * 两个执行者各自把 applied_count 写成自己的局部计数，后写的那次抹掉前一次的进度。
   */
  private boolean claim(AccessService.Access actor, String permission, UUID jobId, String expectStatus) {
    return access.run(actor, permission, () -> sql.execute(
        "update import_job set worker_claimed_at=now()"
            + " where id=? and worker_claimed_at is null and status=?",
        jobId, expectStatus)) == 1;
  }

  private void failJob(UUID tenantId, UUID jobId, String reason) {
    try {
      access.tenants.run(tenantId, () -> {
        // 只在「还在跑」的两个态上落 FAILED：已经到终态的任务不能被这次异常改写
        // （触发器也会拒，但显式写清楚意图，免得以后有人放宽触发器时改出静默覆盖）。
        sql.execute(
            "update import_job set status='FAILED',error=? where id=? and status in ('PREVALIDATING','RUNNING')",
            reason, jobId);
        return null;
      });
    } catch (RuntimeException e) {
      log.error("导入任务 {} 标记失败时出错", jobId, e);
    }
  }

  private ImportJobService jobs() {
    return new ImportJobService(sql, this);
  }

  static String readable(Throwable e) {
    var root = e;
    while (root.getCause() != null) root = root.getCause();
    String message = root.getMessage();
    if (message == null) return root.getClass().getSimpleName();
    if (root instanceof DataAccessException) {
      // 把最常见的约束名翻译成人话：直接抛 PG 的英文报错对运营没有意义。
      if (message.contains("sku_identifier_scheme_value_key") || message.contains("sku_identifier_pkey"))
        return "条码值已被占用（同一体系下条码必须唯一）";
      if (message.contains("spu_tenant_id_code_key")) return "商品编码已存在（编码在租户内唯一）";
      if (message.contains("sku_tenant_id_code_key")) return "SKU 编码已存在（编码在租户内唯一）";
      if (message.contains("sku_package_tenant_id_sku_id_key")) return "该 SKU 已有包装档案，请改用更新模式";
      if (message.contains("attribute_value_item_attribute_id_fkey"))
        return "属性选项不在字典里（选项型属性必须先在选项管理里登记）";
      if (message.contains("violates check constraint")) return "字段取值违反数据库约束（请检查数值范围与必填项）";
    }
    return message.length() > 280 ? message.substring(0, 280) : message;
  }

  private static BigDecimal decimalOrNull(String v) {
    if (v == null || v.isBlank()) return null;
    return new BigDecimal(v.trim());
  }

  private static String placeholders(int n) {
    return String.join(",", java.util.Collections.nCopies(n, "?"));
  }

  private static <T> List<List<T>> chunked(Set<T> values) {
    List<List<T>> out = new ArrayList<>();
    List<T> current = new ArrayList<>();
    for (T v : values) {
      current.add(v);
      if (current.size() == IN_CHUNK) {
        out.add(current);
        current = new ArrayList<>();
      }
    }
    if (!current.isEmpty()) out.add(current);
    return out;
  }

  private static String trim(String v) {
    return v == null ? "" : v.trim();
  }
}
