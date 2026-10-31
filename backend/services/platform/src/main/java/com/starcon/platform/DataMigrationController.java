package com.starcon.platform;

import static com.starcon.platform.DataMigrationModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 18.1.3.2「数据迁移」的 HTTP 端点。
 *
 * <p>命名空间是 {@code /platform/data-migration}：与 18.1.3.1 的 {@code /platform/io}、
 * 18.1.2.1 的 {@code /platform/webhooks}、18.1.2.2 的 {@code /platform/deliveries} 刻意不共用前缀 ——
 * 那是**别的模块的端点**。
 *
 * <p><b>五项能力各一组端点，顺序即依赖</b>：
 *
 * <pre>
 *   能力声明   GET  /platform/data-migration/capabilities
 *   概览       GET  /platform/data-migration/overview
 *   目标模型   GET  /platform/data-migration/targets
 *   映射配置   GET  /platform/data-migration/mappings
 *   映射配置   POST /platform/data-migration/mappings
 *   映射配置   GET  /platform/data-migration/mappings/{id}
 *   映射配置   PUT  /platform/data-migration/mappings/{id}
 *   映射配置   DELETE /platform/data-migration/mappings/{id}
 *   迁移任务   GET  /platform/data-migration/runs
 *   发起迁移   POST /platform/data-migration/runs            （已受理，异步执行）
 *   任务详情   GET  /platform/data-migration/runs/{id}        （进度跟踪）
 *   失败回滚   POST /platform/data-migration/runs/{id}/rollback（已受理，异步删除）
 *   逐行结论   GET  /platform/data-migration/runs/{id}/rows   （核对报告的明细）
 *   核对报告   GET  /platform/data-migration/runs/{id}/report （CSV 下载）
 *   回滚报告   GET  /platform/data-migration/runs/{id}/rollback-report （CSV 下载）
 *   过期清理   POST /platform/data-migration/expire
 * </pre>
 *
 * <p>🔴 <b>POST 返回「已受理」而不是「已完成」</b>：迁移要逐行判重、逐行写目标库，
 * 那是分钟级的事。前端<b>绝不能</b>在 POST 返回后显示「迁移完成」——
 * 必须轮询任务详情拿真实的行数与状态。
 *
 * <p>🔴 <b>租户只来自已认证成员关系</b>：本控制器没有任何一个参数是 tenantId，
 * 一律取 {@code AccessService.Access.tenantId()}。客户端传 {@code X-Tenant-Id} 不会被读。
 *
 * <p>权限码 {@code platform.migration.read / .create / .execute / .download}：
 * 「看」「配」「跑」「拿文件」是四件事 —— 文件要**离开本系统**（进用户本地文件），
 * 所以下载是独立授权的。
 */
@RestController
@RequestMapping("/platform")
public class DataMigrationController {

  private final AccessService access;
  private final DataMigrationService service;
  private final DataMigrationExecutor executor;

  public DataMigrationController(
      AccessService access, DataMigrationService service, DataMigrationExecutor executor) {
    this.access = access;
    this.service = service;
    this.executor = executor;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再把工作交给服务层。 */
  private <T> T call(
      String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "platform.migration." + permission);
    return access.run(actor, "platform.migration." + permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与概览

  /** 能力声明：哪些真接通、缺什么、归谁。 */
  @GetMapping("/data-migration/capabilities")
  public DataMigrationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> service.capabilities());
  }

  /** 概览：几个数。 */
  @GetMapping("/data-migration/overview")
  public DataMigrationOverview overview(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> service.overview());
  }

  /**
   * 目标模型目录（**没接通的那几个照样出现**并标未接通）。
   *
   * <p>🔴 没接通的那几行**照样出现在目录里** —— 混进空态等于告诉运营
   * 「订单也已经能迁历史数据了」。
   */
  @GetMapping("/data-migration/targets")
  public List<DataMigrationTargetRow> targets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", actor -> service.targets());
  }

  // ================================================================ 映射配置

  /** 映射配置列表（真分页 + 真筛选）。 */
  @GetMapping("/data-migration/mappings")
  public DataMigrationMappingPage mappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String targetModel,
      @RequestParam(required = false) String q,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> service.mappings(targetModel, q, page, size));
  }

  /** 映射配置详情。 */
  @GetMapping("/data-migration/mappings/{id}")
  public DataMigrationMappingRow mapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> service.mapping(id));
  }

  /**
   * 新建映射配置（外部字段 → 内部模型）。
   *
   * <p>🔴 **目标模型的必填字段必须全被映射**，否则建配置时就拒 ——
   * 缺了「商品编码」映射，运行时会让**每一行**都失败，而根因在配置里。
   */
  @PostMapping("/data-migration/mappings")
  public DataMigrationMappingRow createMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DataMigrationMappingInput input) {
    return call(auth, "create", actor -> service.createMapping(input, actor));
  }

  /** 修改映射配置（已发起的迁移任务用的是它冻结的快照，不受影响）。 */
  @PutMapping("/data-migration/mappings/{id}")
  public DataMigrationMappingRow updateMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @Valid @RequestBody DataMigrationMappingInput input) {
    return call(auth, "create", actor -> service.updateMapping(id, input));
  }

  /** 删除映射配置（已被迁移任务引用的不能删）。 */
  @DeleteMapping("/data-migration/mappings/{id}")
  public Map<String, Object> deleteMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "create",
        actor -> {
          service.deleteMapping(id);
          return Map.of("deleted", true);
        });
  }

  // ================================================================ 迁移任务

  /** 迁移任务列表（真分页 + 真筛选）。 */
  @GetMapping("/data-migration/runs")
  public DataMigrationRunPage runs(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String mode,
      @RequestParam(required = false) String status,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return call(auth, "read", actor -> service.runs(mode, status, page, size));
  }

  /** 迁移任务详情（**进度跟踪 + 核对结论**）。 */
  @GetMapping("/data-migration/runs/{id}")
  public DataMigrationRunRow run(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(auth, "read", actor -> service.run(id));
  }

  /**
   * 发起一次迁移（**已受理**）。
   *
   * <p>🔴 <b>浏览器走 base64 JSON 这一支</b>：同源代理只转发 JSON，multipart 过不去。
   * 文件名与内容都在请求里 —— 那两样是「迁进去的到底是哪份文件」的凭据。
   *
   * <p>🔴 <b>mode=DRY_RUN 就是「试运行」</b>：服务端只读不写。
   * 前端**不能**只靠「我选了试运行」来保证不写 —— 保证在服务端与数据库触发器里。
   */
  @PostMapping("/data-migration/runs")
  public DataMigrationRunRow createRun(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody DataMigrationRunInput input) {
    return call(
        auth,
        "create",
        actor -> {
          var run = service.createRun(input, actor);
          executor.submit(run.id(), auth, actor);
          return run;
        });
  }

  /**
   * 发起一次回滚（**已受理**：真正的删除在后台跑）。
   *
   * <p>🔴 回滚**不是**「把状态改成已回滚」：它要去目标库真删。
   * 删不掉的那几行会如实记「未删除」并写明原因。
   */
  @PostMapping("/data-migration/runs/{id}/rollback")
  public DataMigrationRunRow rollback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    return call(
        auth,
        "execute",
        actor -> {
          var run = service.requestRollback(id, actor);
          executor.submitRollback(run.id(), auth, actor);
          return run;
        });
  }

  /** 逐行结论（**核对报告的明细**：哪一行、什么结论、为什么）。 */
  @GetMapping("/data-migration/runs/{id}/rows")
  public DataMigrationRowPage rows(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestParam(required = false) String outcome,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "50") int size) {
    return call(auth, "read", actor -> service.rows(id, outcome, page, size));
  }

  /**
   * 下载核对报告（CSV）。
   *
   * <p>有效期由服务端判：过期回 <b>410 Gone</b>，不靠前端禁用按钮。
   */
  @GetMapping(value = "/data-migration/runs/{id}/report", produces = "text/csv")
  public ResponseEntity<byte[]> report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var content = call(auth, "download", actor -> service.report(id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, content.contentType())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + content.filename() + "\"")
        .body(content.content());
  }

  /** 下载回滚报告（CSV）。 */
  @GetMapping(value = "/data-migration/runs/{id}/rollback-report", produces = "text/csv")
  public ResponseEntity<byte[]> rollbackReport(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    var content = call(auth, "download", actor -> service.rollbackReport(id));
    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, content.contentType())
        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + content.filename() + "\"")
        .body(content.content());
  }

  // ================================================================ 维护

  /**
   * 清理过期文件（**外部调度**触发的一次入口）。
   *
   * <p>🔴 **迁移任务记录保留**（它是审计事实：谁在什么时候按什么映射迁了什么），
   * 只让**文件**不再可下载。
   */
  @PostMapping("/data-migration/expire")
  public Map<String, Object> expire(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(
        auth,
        "execute",
        actor -> Map.of("expiredFiles", service.expireDue(), "failedTasks", 0));
  }
}
