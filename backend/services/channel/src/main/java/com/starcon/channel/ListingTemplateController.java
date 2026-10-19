package com.starcon.channel;

import static com.starcon.channel.ListingTemplateModels.*;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.springframework.web.bind.annotation.*;

/**
 * 4.2.2.1 刊登模板。
 *
 * <p>蓝图：「沉淀可复用的刊登模板（类目级、店铺级），保存字段填充规则（取自商品属性、引用文案库、套用图片组），一键套用到目标商品。输入：模板
 * 定义（字段规则 + 图片组 + 必填校验）；套用目标。输出：模板记录；套用后的内容草稿。关系：上游 = 03 文案资产、03 媒体库；下游 = 4.2.1.1
 * 编辑器。<b>模板引用的是"取值规则"而非一次性拷贝</b>，商品改价或换图后套用结果跟着变。」端点按蓝图模块号标注，方便对照
 * 04-listing-and-content.md。
 *
 * <p>🔴 端点清单里**没有**任何一个「保存到线上 / 发布」的入口，这是刻意的：
 *
 * <ul>
 *   <li>{@code POST /apply} —— 「一键套用到目标商品」。产出是**草稿**（{@code listing_content_draft}），
 *       写事务打开 {@code app.draft_only}，线上 listing 的内容列在此期间被 DB 拒写。
 *   <li>{@code GET /applies} —— 套用记录（谁在什么时候把哪个模板套到了哪个商品上）。
 *   <li>{@code GET|POST /} 与 {@code GET /{id}} —— 「输出：模板记录」+「输入：模板定义」。
 *   <li>{@code GET /{id}/matches} —— 真的落在模板适用范围里的那批 listing（真分页）。
 * </ul>
 *
 * <p>权限码<b>复用</b> identity 已有的 {@code channel.operation.*}（与 4.1.1.1 listing 列表、
 * 4.2.1.2 批量编辑、4.4.2.1 商品认领同族），identity 服务零改动。读操作用 read，写操作用 create / update。
 */
@RestController
@RequestMapping("/channel/listing-templates")
public class ListingTemplateController {

  private final AccessService access;
  private final ListingTemplateService service;

  public ListingTemplateController(AccessService access, ListingTemplateService service) {
    this.access = access;
    this.service = service;
  }

  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  /**
   * 需要把调用方 bearer 透传给 catalog 端口的入口。
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
   * 能力声明：编辑器侧套用入口 available=false + ownerModule=4.2.1.1；
   * 发布维度 available=false + ownerModule=4.3.1.2。
   *
   * <p>前端据此禁用任何「直接上线」按钮，并对未接入的段显示说明文字，<b>不编造值</b>。
   */
  @GetMapping("/capabilities")
  public ListingTemplateCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.capabilities());
  }

  /** 筛选项词表（作用域 / 平台 / 店铺 / 站点），不硬编码在前端。 */
  @GetMapping("/filter-options")
  public ListingTemplateFilterOptions filterOptions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "channel.operation.read", a -> service.filterOptions());
  }

  // ---- 模板记录 ----

  /** 模板列表（真分页 + 真筛选：作用域 / 平台 / 店铺 / 类目 / 状态 / 关键字）。 */
  @GetMapping
  public ListingTemplatePage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String scope,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String shopCode,
      @RequestParam(required = false) String categoryPath,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "channel.operation.read",
        a -> service.list(page, size, scope, platformCode, shopCode, categoryPath, status, keyword));
  }

  /** 模板详情（本体 + 字段规则 + 图片组）。跨租户与不存在返回逐字相同的 404。 */
  @GetMapping("/{id}")
  public ListingTemplateDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    return call(auth, "channel.operation.read", a -> service.load(id));
  }

  /**
   * 新建 / 更新模板（「输入：模板定义 = 字段规则 + 图片组 + 必填校验」）。
   *
   * <p>载荷里**没有**任何字段值 —— 只有适用范围、来源引用与取值表达式。
   */
  @PostMapping
  public ListingTemplateDetail save(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody SaveListingTemplateRequest request) {
    return call(auth, "channel.operation.create", a -> service.save(request));
  }

  /** 启停模板。 */
  @PutMapping("/{id}/status")
  public ListingTemplateDetail setStatus(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam String status) {
    return call(auth, "channel.operation.update", a -> service.setStatus(id, status));
  }

  /** 删除模板：被套用过的模板不可删（服务层先拒，DB 触发器兜底）。 */
  @DeleteMapping("/{id}")
  public void remove(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id) {
    call(
        auth,
        "channel.operation.delete",
        a -> {
          service.remove(id);
          return null;
        });
  }

  /**
   * 🔴 适用范围：真的落在模板适用范围里的那批 listing（真分页）。
   *
   * <p>这条端点是「适用范围判定有真实数据支撑」的证据 —— 界面看到的是从库里查出来的命中行，
   * 不是一句描述。
   */
  @GetMapping("/{id}/matches")
  public ListingTemplateMatchPage matches(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String id,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(auth, "channel.operation.read", a -> service.matches(id, page, size));
  }

  // ---- 套用 ----

  /**
   * 🔴 一键套用到目标商品（「输出：套用后的内容草稿」）。
   *
   * <p>产出的是**草稿**（{@code listing_content_draft}），线上 listing 一列都不碰 ——
   * 写事务打开 {@code app.draft_only}，DB 触发器 {@code guard_listing_live_content} 在此期间
   * 拒绝对线上内容列的任何改动。发布是 4.3.1.2 的另一条路径。
   *
   * <p>幂等语义：带同一个 {@code idempotencyKey} 重复套用返回第一次的那一份结果
   * （{@code idempotentReplay=true}），不重新求值、不再写草稿；不带幂等键重复套用则是
   * **覆盖草稿**（重新求值后覆盖同一份 DRAFT），每次都留一条套用记录。
   */
  @PostMapping("/apply")
  public ListingTemplateApplyResult apply(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody ApplyListingTemplateRequest request) {
    return callWithBearer(
        auth, "channel.operation.create", (a, bearer) -> service.apply(request, bearer));
  }

  /** 套用记录列表（真分页 + 真筛选）。 */
  @GetMapping("/applies")
  public ListingTemplateApplyPage applies(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String templateId,
      @RequestParam(required = false) String listingId,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth, "channel.operation.read", a -> service.applies(page, size, templateId, listingId));
  }

  /** 单次套用的详情（含逐字段求值明细与必填违规清单）。 */
  @GetMapping("/applies/{applyId}")
  public ListingTemplateApplyResult applyDetail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String applyId) {
    return call(auth, "channel.operation.read", a -> service.applyDetail(applyId));
  }
}
