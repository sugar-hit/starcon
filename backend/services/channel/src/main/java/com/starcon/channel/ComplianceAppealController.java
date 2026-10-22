package com.starcon.channel;

import static com.starcon.channel.ComplianceAppealModels.*;

import java.util.List;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.1.2.2 申诉管理。
 *
 * <p>蓝图：「管理平台申诉：申诉类型、举证材料收集、申诉文本、提交跟踪、结果记录与申诉次数统计。
 * 输入：违规记录；申诉材料；提交动作。输出：申诉单 + 提交记录 + 结果（通过/驳回）。
 * 关系：上游 = 16.1.2.1；下游 = 平台申诉接口。<b>申诉配额有限</b>，需记录已用次数并优先申诉高影响违规。」
 *
 * <p>🔴 端点清单：
 *
 * <ul>
 *   <li>{@code GET /capabilities} —— 能力声明：值域、状态机、未交付下游逐条列出。
 *   <li>{@code GET /shops} —— 店铺范围清单（**真分页**，来自本服务自己的店铺目录）。
 *   <li>{@code GET /appeals} —— 申诉单清单（**真分页 + 真筛选**，默认按影响档位优先排序）。
 *   <li>{@code POST /appeals} —— 新建申诉单（**草稿态**，店铺由违规派生）。
 *   <li>{@code GET /appeals/{id}} —— 申诉单详情（举证材料 + 提交记录 + 结果记录 + 配额）。
 *   <li>{@code PUT /appeals/{id}/text} —— 改申诉文本（**只有草稿态能改**）。
 *   <li>{@code POST /appeals/{id}/evidence} —— 追加举证材料（**append-only**，只有草稿态能收集）。
 *   <li>{@code POST /appeals/{id}/submit} —— 提交申诉（**消耗一次申诉配额**）。
 *   <li>{@code POST /appeals/{id}/decision} —— 记录平台裁定（通过 / 驳回，**append-only**）。
 *   <li>{@code POST /appeals/{id}/withdraw} —— 撤回申诉（**终态**，不消耗配额）。
 *   <li>{@code POST /appeals/{id}/reopen} —— 驳回后回到草稿（补充材料再提，**再消耗一次配额**）。
 *   <li>{@code GET /quotas} —— 申诉配额清单（**真分页**，没配配额的店铺也在列）。
 *   <li>{@code PUT /quotas} —— 配置申诉配额（上限 + 周期）。
 *   <li>{@code GET /stats} —— 申诉次数统计（**申诉次数** = 提交总次数）。
 *   <li>{@code GET /priority-queue} —— **优先申诉队列**（按影响档位从高到低排）。
 * </ul>
 *
 * <p>🔴 <b>刻意没有的形状</b>：没有「真的把申诉送到平台」的端点 —— 平台申诉接口
 * <b>本仓不存在</b>，放行一个不存在的入口比 404 更糟（界面会以为功能已交付）。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（读 / 录 / 改），
 * identity 服务零改动。
 */
@RestController
@RequestMapping("/channel/platform-appeals")
public class ComplianceAppealController {

  private final AccessService access;
  private final ComplianceAppealService service;

  public ComplianceAppealController(AccessService access, ComplianceAppealService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /** 能力声明：值域、状态机，以及**未交付的下游逐条列出**（available=false + ownerModule + 影响）。 */
  @GetMapping("/capabilities")
  public ComplianceAppealCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 店铺范围 ----

  /** 店铺范围清单（真分页 + 真统计；数据来自本服务自己的店铺目录）。 */
  @GetMapping("/shops")
  public ComplianceAppealShopPage shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.shops(platformCode, keyword, shopId, page, size));
  }

  // ---- 申诉单 ----

  /** 申诉单清单（真分页 + 真筛选；默认按**影响档位**优先排序）。 */
  @GetMapping("/appeals")
  public ComplianceAppealPage appeals(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String appealType,
      @RequestParam(required = false) String violationId,
      @RequestParam(required = false) String impactLevel,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "priority") String sort,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.appeals(
                shopId,
                status,
                appealType,
                violationId,
                impactLevel,
                keyword,
                sort,
                page,
                size));
  }

  /** 新建申诉单（草稿态；店铺由违规派生，不接受客户端自报）。 */
  @PostMapping("/appeals")
  public ComplianceAppeal create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ComplianceAppealCreateInput input) {
    return call(auth, "channel.operation.create", a -> service.create(input));
  }

  /** 申诉单详情（举证材料 + 提交记录 + 结果记录 + 配额）。 */
  @GetMapping("/appeals/{id}")
  public ComplianceAppealDetail appeal(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.appeal(id));
  }

  /** 改申诉文本（只有草稿态能改）。 */
  @PutMapping("/appeals/{id}/text")
  public ComplianceAppeal updateText(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ComplianceAppealTextInput input) {
    return call(auth, "channel.operation.update", a -> service.updateText(id, input));
  }

  /** 追加举证材料（append-only：不可改写、不可删；只有草稿态能收集）。 */
  @PostMapping("/appeals/{id}/evidence")
  public ComplianceAppealEvidence addEvidence(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ComplianceAppealEvidenceInput input) {
    return call(auth, "channel.operation.create", a -> service.addEvidence(id, input));
  }

  /** 提交申诉（**消耗一次申诉配额**）。 */
  @PostMapping("/appeals/{id}/submit")
  public ComplianceAppealSubmissionResult submit(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) ComplianceAppealSubmitInput input) {
    return call(auth, "channel.operation.update", a -> service.submit(id, input));
  }

  /** 记录平台裁定（通过 / 驳回；结果记录 append-only）。 */
  @PostMapping("/appeals/{id}/decision")
  public ComplianceAppealDecisionResult decide(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody ComplianceAppealDecisionInput input) {
    return call(auth, "channel.operation.update", a -> service.decide(id, input));
  }

  /** 撤回申诉（终态；不消耗配额）。 */
  @PostMapping("/appeals/{id}/withdraw")
  public ComplianceAppeal withdraw(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestBody(required = false) ComplianceAppealWithdrawInput input) {
    return call(auth, "channel.operation.update", a -> service.withdraw(id, input));
  }

  /** 驳回后回到草稿（补充材料后再次申诉；再提交会再消耗一次配额）。 */
  @PostMapping("/appeals/{id}/reopen")
  public ComplianceAppeal reopen(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.update", a -> service.reopen(id));
  }

  // ---- 配额 ----

  /** 申诉配额清单（真分页；**没配配额的店铺也在列**）。 */
  @GetMapping("/quotas")
  public ComplianceAppealQuotaPage quotas(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.quotas(shopId, page, size));
  }

  /** 配置申诉配额（上限 + 周期；周期不填 ⇒ 当前自然季度 UTC）。 */
  @PutMapping("/quotas")
  public ComplianceAppealQuotaUpdate setQuota(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ComplianceAppealQuotaInput input) {
    return call(auth, "channel.operation.update", a -> service.setQuota(input));
  }

  // ---- 统计与优先队列 ----

  /** 申诉次数统计（**申诉次数** = 提交总次数）。 */
  @GetMapping("/stats")
  public ComplianceAppealStats stats(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String from,
      @RequestParam(required = false) String to) {
    return call(auth, "channel.operation.read", a -> service.stats(shopId, from, to));
  }

  /** 🔴 优先申诉队列（按影响档位从高到低排）。 */
  @GetMapping("/priority-queue")
  public ComplianceAppealPriorityPage priorityQueue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String shopId,
      @RequestParam(required = false) String appealableOnly,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.priorityQueue(shopId, appealableOnly, page, size));
  }
}
