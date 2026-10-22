package com.starcon.channel;

import static com.starcon.channel.IpRiskLibraryModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.4.1.1 侵权库。
 *
 * <p>蓝图：「维护知识产权风险库：品牌商标、专利、版权、肖像权，登记风险等级与适用平台。
 * 输入：侵权条目登记；批量导入。输出：侵权库记录。关系：上游 = 3.3.2 品牌；下游 = 16.4.1.2 检测。
 * <b>侵权检测是刊登前拦截而非事后申诉</b>，成本差一个数量级。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：值域、各条口径、未交付下游逐条列出。
 *   <li>{@code GET /platforms} —— 适用平台目录（读本服务自己的 {@code platform} 目录）。
 *   <li>{@code GET /brands} —— 3.3.2 品牌候选（**跨服务只读**，读不到就说读不到）。
 *   <li>{@code GET /entries} —— 侵权库清单（**真分页 + 真筛选**；可按平台反查）。
 *   <li>{@code POST /entries} —— 登记条目（**按条目编码幂等**）。
 *   <li>{@code GET /entries/{id}} —— 条目详情（匹配特征 + 适用平台）。
 *   <li>{@code PUT /entries/{id}} —— 改条目（**整体替换**；条目编码不可改）。
 *   <li>{@code PUT /entries/{id}/status} —— 启用 / 停用（**停用必写理由**）。
 *   <li>{@code POST /imports} —— 🔴 <b>批量导入</b>（行级结果 + 幂等 + 部分失败不回滚）。
 *   <li>{@code GET /imports} —— 导入批次清单（**真分页**）。
 *   <li>{@code GET /imports/{id}} —— 导入批次详情（逐行结果）。
 *   <li>{@code GET /platform-summary} —— 平台上的风险汇总（**反查**入口）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有删除端点（条目**只停用**，历史要保留）、
 * 没有检测端点（16.4.1.2 检测与预警<b>未交付</b>，放行一个不存在的入口比 404 更糟）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/ip-risk-library")
public class IpRiskLibraryController {

  private final AccessService access;
  private final IpRiskLibraryService service;

  public IpRiskLibraryController(AccessService access, IpRiskLibraryService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：值域、各条口径，以及**未交付的下游逐条列出**（available=false + ownerModule + 影响）。 */
  @GetMapping("/capabilities")
  public IpRiskLibraryCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 平台 ----

  /** 适用平台目录（真目录，**不写死平台常量**）。 */
  @GetMapping("/platforms")
  public List<IpRiskLibraryPlatform> platforms(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.platforms());
  }

  // ---- 上游品牌 ----

  /** 3.3.2 品牌候选（catalog 服务，只读）；**读不到就显式说读不到**。 */
  @GetMapping("/brands")
  public IpRiskLibraryBrandSet brands(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword) {
    return call(auth, "channel.operation.read", a -> service.brands(keyword, auth));
  }

  // ---- 侵权库 ----

  /** 侵权库清单（真分页 + 真筛选；{@code platformCode} 是多对多的反查入口）。 */
  @GetMapping("/entries")
  public IpRiskLibraryEntryPage entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String rightType,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.entries(rightType, riskLevel, status, platformCode, keyword, page, size));
  }

  /** 登记侵权条目（按条目编码**幂等**）。 */
  @PostMapping("/entries")
  public IpRiskLibraryEntryCreation createEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody IpRiskLibraryEntryInput input) {
    return call(auth, "channel.operation.create", a -> service.createEntry(input));
  }

  /** 条目详情（匹配特征 + 适用平台）。 */
  @GetMapping("/entries/{id}")
  public IpRiskLibraryEntryDetail entry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.entry(id));
  }

  /** 改条目（整体替换；条目编码是身份，不可改）。 */
  @PutMapping("/entries/{id}")
  public IpRiskLibraryEntryDetail updateEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody IpRiskLibraryEntryInput input) {
    return call(auth, "channel.operation.update", a -> service.updateEntry(id, input));
  }

  /** 启用 / 停用（**停用必写理由**；重新启用不抹掉历史）。 */
  @PutMapping("/entries/{id}/status")
  public IpRiskLibraryEntryDetail setStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody IpRiskLibraryStatusInput input) {
    return call(auth, "channel.operation.update", a -> service.setStatus(id, input));
  }

  // ---- 批量导入 ----

  /** 🔴 批量导入（行级结果 + 幂等 + 部分失败不回滚）。 */
  @PostMapping("/imports")
  public IpRiskLibraryImportResult importEntries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody IpRiskLibraryImportInput input) {
    return call(auth, "channel.operation.create", a -> service.importEntries(input));
  }

  /** 导入批次清单（真分页）。 */
  @GetMapping("/imports")
  public IpRiskLibraryImportPage imports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.imports(page, size));
  }

  /** 导入批次详情（逐行结果）。 */
  @GetMapping("/imports/{id}")
  public IpRiskLibraryImportDetail importBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.importBatch(id));
  }

  // ---- 平台汇总 ----

  /** 平台上的侵权风险汇总（**「这个平台上有哪些高风险条目」**的反查入口）。 */
  @GetMapping("/platform-summary")
  public IpRiskLibraryPlatformSummaryPage platformSummary(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.platformSummary());
  }
}
