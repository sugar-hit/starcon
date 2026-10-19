package com.starcon.channel;

import static com.starcon.channel.ListingComplianceModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.2.3.2 合规预检（违禁词/侵权）。
 *
 * <p>蓝图：「发布前扫描内容的违规风险：违禁词/敏感词命中、极限词、品牌侵权（外观/商标）、平台类目必填缺失、
 * 图片不合规（尺寸/白底/水印）、认证缺失。输入：内容草稿；租户违禁词库；平台规则库；16 合规的侵权与认证状态；
 * 03 品牌授权状态。输出：风险清单（风险等级、命中位置、规则来源、建议替换）；阻断项（未修复不允许发布）与提醒项。
 * 关系：上游 = 16 合规与风控、03 品牌、02 平台规则；下游 = 4.3.1.1（阻断发布）。
 * <b>阻断项在服务端强制校验</b>，前端提示不能作为唯一防线。」
 * 端点按蓝图模块号标注，方便对照 04-listing-and-content.md。
 *
 * <p>🔴 端点清单里**没有**任何一个「直接发布」的入口，这是刻意的：
 *
 * <ul>
 *   <li>{@code POST /scans} —— 触发扫描。产出是**扫描记录 + 风险清单**，不碰线上 listing。
 *   <li>{@code GET /drafts/{id}/publish-gate} —— 🔴 **发布闸门**。这是「阻断项在服务端强制校验」
 *       的对外形状：服务端真算，{@code allowed=false} 时不允许发布。**页面上把它显示成红色不是拦截，
 *       拦截在服务端。**
 *   <li>{@code GET /words} 及其写操作 —— 租户级违禁词库（运营自己维护）。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.2.1.2 批量编辑、4.2.2.1 刊登模板、4.4.2.1 商品认领同族），identity 服务零改动。
 * 读操作用 read，写操作用 create / update / delete。
 */
@RestController
@RequestMapping("/channel/listing-compliance-precheck")
public class ListingCompliancePrecheckController {

  private final AccessService access;
  private final ListingCompliancePrecheckService service;

  public ListingCompliancePrecheckController(AccessService access, ListingCompliancePrecheckService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 需要把调用方 bearer 透传给 catalog 端口的入口（扫描要读 03 品牌 / 媒体库 / 类目映射）。
   *
   * <p>🔴 租户<b>不由本服务传给 catalog</b>：转发的是调用方自己的 bearer，catalog 侧仍按
   * 已认证成员关系解析租户并强制 RLS。
   */
  private <T> T callWithBearer(
      String bearer, String permission, BiFunction<AccessService.Access, String, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor, bearer));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：16.2.1.1 合规规则库、16.2.2.2 认证与资质、16.2.2.1 合规预检（体检报告）、
   * 4.3.1.1 一键刊登**均 available=false** + ownerModule + 影响说明。
   *
   * <p>前端据此禁用「直接发布」并对未接入的段显示说明文字，<b>不编造值</b>。
   */
  @GetMapping("/capabilities")
  public ListingComplianceCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项词表（平台 / 店铺 / 各类取值域），不硬编码在前端。 */
  @GetMapping("/filter-options")
  public ListingComplianceFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 扫描 ----

  /** 可扫描的编辑器草稿（真分页 + 真搜索）：扫描的内容来源。 */
  @GetMapping("/drafts")
  public ListingComplianceDraftPage drafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.drafts(page, size, shopId, keyword));
  }

  /**
   * 🔴 触发一次合规预检。
   *
   * <p>产出：一条扫描记录 + 一份风险清单（每条带风险等级 / 命中位置 / 规则来源 / 建议替换）。
   * **不碰线上 listing**：线上内容列在编辑事务期间由 DB 触发器拒写，本模块连草稿都不改。
   */
  @PostMapping("/scans")
  public ListingComplianceScanResult scan(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ListingComplianceScanRequest request) {
    return callWithBearer(
        auth,
        "channel.operation.create",
        (a, bearer) -> service.scan(request.draftId(), request.triggerSource(), bearer));
  }

  /** 扫描历史（真分页 + 真筛选）。 */
  @GetMapping("/scans")
  public ListingComplianceScanPage scans(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID draftId,
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String conclusion,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.scans(page, size, draftId, state, conclusion));
  }

  /** 单次扫描的详情（含风险清单）。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/scans/{id}")
  public ListingComplianceScanResult scanDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.scanDetail(parseId(id)));
  }

  /**
   * 🔴 发布闸门：**服务端强制校验**的那一半。
   *
   * <p>返回 {@code allowed=false} 时，这份内容**未修复不允许发布**。
   * 另有 DB 触发器在 4.2.1.1 的入队动作上直接拒写（见 V198 第三节 (c)）——
   * 前端提示不是唯一防线。
   */
  @GetMapping("/drafts/{draftId}/publish-gate")
  public ListingCompliancePublishGate publishGate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String draftId) {
    return call(auth, "channel.operation.read", a -> service.publishGate(parseId(draftId)));
  }

  // ---- 风险处置 ----

  /** 人工处置风险（标记已修复 / 已忽略 / 重新打开）。处置会立刻改写扫描的阻断项计数。 */
  @PostMapping("/risks/decisions")
  public java.util.Map<String, Object> decideRisks(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ListingComplianceRiskDecisionBatchRequest request) {
    return call(
        auth,
        "channel.operation.update",
        a -> {
          int changed = service.decideRisks(request.riskIds(), request.status(), request.note());
          return java.util.Map.<String, Object>of("changed", changed);
        });
  }

  // ---- 租户违禁词库 ----

  /** 词库列表（真分页 + 真筛选）。 */
  @GetMapping("/words")
  public ListingComplianceWordPage words(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String category,
      @RequestParam(required = false) String severity,
      @RequestParam(required = false) String riskLevel,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a ->
            service.words(
                page, size, category, severity, riskLevel, platformCode, keyword, enabled));
  }

  /** 新建一个词。 */
  @PostMapping("/words")
  public ListingComplianceWordRow createWord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ListingComplianceWordInput input) {
    return call(auth, "channel.operation.create", a -> service.saveWord(null, input));
  }

  /** 改一个词。 */
  @PutMapping("/words/{id}")
  public ListingComplianceWordRow updateWord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @Valid @RequestBody ListingComplianceWordInput input) {
    return call(auth, "channel.operation.update", a -> service.saveWord(parseId(id), input));
  }

  /** 启停一个词。 */
  @PutMapping("/words/{id}/enabled")
  public ListingComplianceWordRow setWordEnabled(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam boolean enabled) {
    return call(auth, "channel.operation.update", a -> service.setWordEnabled(parseId(id), enabled));
  }

  /** 删除一个词。 */
  @DeleteMapping("/words/{id}")
  public void deleteWord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    call(
        auth,
        "channel.operation.delete",
        a -> {
          service.deleteWord(parseId(id));
          return null;
        });
  }

  // ---- 辅助 ----

  /** 路径上的 id 必须是合法 UUID；不是就 404（不泄漏「格式对但不存在」与「格式错」的区别）。 */
  private static UUID parseId(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND, "不存在");
    }
  }

  /** 供 OpenAPI 文档用的类型引用（避免 IDE 报未使用的 import）。 */
  @SuppressWarnings("unused")
  private static final List<String> DOCUMENTED = List.of();
}
