package com.starcon.channel;

import static com.starcon.channel.CompliancePrecheckModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 16.2.2.1 合规预检。
 *
 * <p>蓝图：「发布前的合规体检：内容违规、认证缺失、尺寸规格不符、材质与描述不符、危险品与运输限制、标签与说明书要求，
 * 多维度检查并给出整改建议。输入：listing 内容草稿；商品属性；认证状态；平台要求。输出：体检报告（分维度得分 +
 * 问题清单 + 整改建议）。关系：上游 = 4.2.1.1 编辑器；下游 = 16.2.1.2 拦截。<b>预检在保存草稿时就跑一次</b>（即时反馈），
 * <b>发布时再跑一次</b>（强制校验）。」
 *
 * <p>🔴🔴 端点清单里有两个**触发入口**，对应蓝图加粗句里的两次体检：
 *
 * <ul>
 *   <li>{@code POST /drafts/{id}/check} —— 🔴 <b>保存草稿时的即时反馈</b>（trigger=DRAFT_SAVE）。
 *       运营改完内容立刻看到体检结果，不必等到发布。
 *   <li>{@code POST /drafts/{id}/precheck} —— 🔴 <b>发布前的强制校验</b>（trigger=PUBLISH_GATE）。
 *       供 16.2.1.2 拦截执行调用（未交付）。⚠️ <b>本模块不执行拦截</b>：拦截、豁免、审批归 16.2.1.2，
 *       这里只把结论算出来。
 *   <li>{@code GET /reports} / {@code GET /reports/{id}} —— 报告历史（真分页）与单份报告。
 *   <li>{@code GET /drafts} —— 可供体检的内容草稿（4.2.1.1 的草稿，本模块只读）。
 *   <li>{@code GET /capabilities} —— 能力声明：哪些维度量得到、量不到归谁。
 * </ul>
 *
 * <p>🔴 <b>两次体检产出两份报告</b>：报告头带 {@code triggerKind}，历史里分得清
 * 「这是保存时的那次」还是「这是发布前的那次」。
 *
 * <p>🔴 与 4.2.3.2「合规预检（违禁词 / 侵权）」是<b>两个模块</b>：本控制器是
 * {@code /channel/compliance-precheck}，那边是 {@code /channel/listing-compliance-precheck}，
 * 两边不共用表、不共用端点、不共用页面。
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.2.1.1 刊登编辑器同族），identity 服务零改动。读操作用 read，写操作用 create。
 */
@RestController
@RequestMapping("/channel/compliance-precheck")
public class CompliancePrecheckController {

  private final AccessService access;
  private final CompliancePrecheckService service;

  public CompliancePrecheckController(AccessService access, CompliancePrecheckService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ---- 能力声明 ----

  /**
   * 能力声明：六个维度各自量不量得到、量不到归谁。
   *
   * <p>前端据此把「未知」显示成未知而不是 0 分，并原样显示未接入说明，<b>不编造已交付</b>。
   */
  @GetMapping("/capabilities")
  public CompliancePrecheckCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  // ---- 输入侧：可供体检的内容草稿（4.2.1.1，只读） ----

  /** 可供体检的草稿列表（真分页）。 */
  @GetMapping("/drafts")
  public CompliancePrecheckDraftPage drafts(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "channel.operation.read", a -> service.drafts(shopId, page, size));
  }

  // ---- 两个触发入口 ----

  /**
   * 🔴 保存草稿时的**即时反馈**（trigger=DRAFT_SAVE）。
   *
   * <p>产出是一份体检报告。草稿本身一个字节都不动（本模块对 {@code listing_editor_draft} 只读）。
   */
  @PostMapping("/drafts/{id}/check")
  public CompliancePrecheckReport check(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.create", a -> service.check(id, "DRAFT_SAVE", auth));
  }

  /**
   * 🔴 发布前的**强制校验**（trigger=PUBLISH_GATE）。
   *
   * <p>⚠️ <b>本模块不执行拦截</b>：这里只把结论（PASS / ATTENTION / UNKNOWN）算出来，
   * 阻断、豁免与审批归 16.2.1.2 拦截执行（未交付）。
   */
  @PostMapping("/drafts/{id}/precheck")
  public CompliancePrecheckReport precheck(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.create", a -> service.check(id, "PUBLISH_GATE", auth));
  }

  // ---- 报告 ----

  /** 报告历史（真分页；可按草稿与触发语义筛）。 */
  @GetMapping("/reports")
  public CompliancePrecheckReportPage reports(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID draftId,
      @RequestParam(required = false) String triggerKind,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.reports(draftId, triggerKind, page, size));
  }

  /** 单份报告（分维度得分 + 问题清单 + 整改建议）。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/reports/{id}")
  public CompliancePrecheckReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.report(id));
  }
}
