package com.starcon.trade;

import static com.starcon.trade.AftersalesReasonModels.*;

import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 11.1.2.1 售后原因分类。
 *
 * <p><b>命名空间是 {@code /trade/aftersales/reasons}**，与 11.1.1.1 的
 * {@code /trade/aftersales} 并列在同一前缀下但<b>不同一段</b>：
 * 它挂在 reasons 这道门下而不是直接挂在 aftersales 下，
 * 将来 11.1.1.1 若产出 {@code reasons} 之外的形状也不会与本模块撞车。
 *
 * <p><b>刻意不占用的路径</b>：
 *
 * <ul>
 *   <li>{@code /trade/aftersales/cases/{id}}（单条详情）—— 属 11.1.1.2，未交付。
 *       本模块<b>只用 caseId 做归因</b>，不给它造详情端点：
 *       一个点进去 404 的入口比没有这个入口更危险。
 *   <li>跨模块的组合端点（如「按原因反查售后单列表」）—— 那是 11.1.1.1 的列表筛选，
 *       它自己带 {@code reasonCode} 筛选参数。本模块提供的是<b>归因写入与读取</b>，
 *       把列表能力复制一遍会造出两个各自漂移的口径。
 * </ul>
 *
 * <p><b>权限码</b>：读 {@code trade.aftersales.reason.read}，写 {@code trade.aftersales.reason.write}。
 * <b>刻意与 11.1.1.1 的 {@code trade.aftersales.read/write} 分开</b>：
 * 归因是质量归因与供应商索赔的共同依据，给一份只读权限就等于
 * 让「谁都能改所有人的退货原因」，而这恰恰是加粗句要防的失真来源。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} / {@link #write} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 FORCE RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/trade/aftersales/reasons")
public class AftersalesReasonController {

  private static final String READ = "aftersales.reason.read";
  private static final String WRITE = "aftersales.reason.write";

  private final AccessService access;
  private final AftersalesReasonService reasons;

  public AftersalesReasonController(AccessService access, AftersalesReasonService reasons) {
    this.access = access;
    this.reasons = reasons;
  }

  /** 统一读入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + READ);
    return access.run(actor, "trade." + READ, () -> work.apply(actor));
  }

  /** 写入口：需要 {@code trade.aftersales.reason.write}。租户来源与读入口完全一致。 */
  private <T> T write(String bearer, Function<AccessService.Access, T> work) {
    AccessService.Access actor = access.require(bearer, "trade." + WRITE);
    return access.run(actor, "trade." + WRITE, () -> work.apply(actor));
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：哪些维度真有依据、上游通没通、下游交付没交付、平台原因码映射归谁。
   *
   * <p>让界面能说清「为什么平台原因码是租户自己维护的、为什么没有店铺维度、
   * 为什么质量分析那一栏是空的」，而不是让它们显示成空白让人误以为链路已通。
   */
  @GetMapping("/capabilities")
  public AftersalesReasonCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> reasons.capabilities());
  }

  // ================================================================ 分类树

  /**
   * 分类树（整树或某支）。
   *
   * <p><b>路径、层级与「是否可被归因」都由数据库的递归 CTE 算出来</b>，
   * 前端照 {@code depth} / {@code parentId} / {@code codePath} 建树即可。
   * 本次读取会**按租户幂等落库**蓝图点名的七类基线词表
   * （{@code baselineSeededOnThisRead=true} 表示这是该租户第一次打开本模块）。
   */
  @GetMapping("/tree")
  public AftersalesReasonTree tree(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String categoryStatus,
      @RequestParam(required = false) String rootKind,
      @RequestParam(required = false) Boolean assignableOnly) {
    return call(
        auth,
        actor ->
            reasons.tree(new AftersalesReasonTreeQuery(categoryStatus, rootKind, assignableOnly)));
  }

  /**
   * 新建一个分类。
   *
   * <p>⚠️ <b>建根必须给 {@code rootKind}</b>（七类之一），<b>建子节点必须不给</b> ——
   * 根节点不归类的话报表无法确定它该汇总到哪一类，而给子节点编一个 root_kind
   * 会让同一批数据在两个口径里被各计一次。
   */
  @PostMapping("/categories")
  @ResponseStatus(HttpStatus.CREATED)
  public AftersalesReasonTreeNode createCategory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesReasonCategoryCreate request) {
    return write(auth, actor -> reasons.createCategory(request, actor.userId()));
  }

  /**
   * 更新一个分类。
   *
   * <p>⚠️ <b>只改得了名称、状态、排序位、说明、父分类五项。</b>
   * {@code rootKind} 与 {@code categoryCode} 是**不可改列**（V158 的列级白名单也没授它们）：
   * root_kind 是报表的汇总锚点，改它会把全部历史归因重新归到另一个口径；
   * 编码是 11.5.2 与 6 采购的对齐键，改它会让历史数据对不上账。
   * 要「换口径」请新建一个分类再迁归因。
   */
  @PatchMapping("/categories/{id}")
  public AftersalesReasonTreeNode updateCategory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AftersalesReasonCategoryUpdate request) {
    return write(auth, actor -> reasons.updateCategory(id, request, actor.userId()));
  }

  /**
   * 删除一个分类（叶子且从未被归因 / 映射引用）。
   *
   * <p>⚠️ <b>「删不掉」是口径完整性要求，不是缺陷。</b>
   * 被引用过的分类只能改状态停用：停用后不再可被新归因选中，
   * 而历史归因仍然指向它，两个口径都还在。
   */
  @DeleteMapping("/categories/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteCategory(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    write(
        auth,
        actor -> {
          reasons.deleteCategory(id);
          return null;
        });
  }

  // ================================================================ 归因

  /**
   * 读一张售后单的当前归因。
   *
   * <p>⚠️ {@code attributed=false} 表示「这张单还没做过归因」（11.1.1.1 登记时的合法状态），
   * 与「归因为空」是**两件不同的事** —— 后者在 DB 层不可能存在
   * （零成员的归因集合提交不了）。
   */
  @GetMapping("/attributions")
  public AftersalesReasonAttributionView attribution(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam UUID caseId) {
    return call(auth, actor -> reasons.attribution(caseId));
  }

  /**
   * 登记一次归因（<b>替换语义</b>：新写一版，旧的自动标记为失效并留痕）。
   *
   * <p><b>加粗句「必须强制选择分类而不是留自由文本」的兑现点。</b>
   * 至少一个原因、恰好一个主因、权重必填且落在 (0, 1]、分类必须启用 ——
   * 服务层与 DB 提交期约束触发器**双层**强制，绕过后端直接写 SQL 也存不进去。
   *
   * <p>{@code note} 是**补充说明**，不是归因本身：只给 note 不给 items 会直接被拒。
   */
  @PostMapping("/attributions")
  @ResponseStatus(HttpStatus.CREATED)
  public AftersalesReasonAttributionView attribute(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesReasonAttributionCreate request) {
    return write(auth, actor -> reasons.attribute(request, actor.userId()));
  }

  // ================================================================ 平台原因码映射

  /**
   * 平台原因码映射列表（真筛选 + 真排序）。
   *
   * <p>⚠️ 这张表在 <b>trade 库</b>，不是 channel 的 {@code platform_enum_mapping}
   * （后者在 channel 服务独立库 starcon_channel，禁止跨服务 SQL / FK / 视图）。
   * 本表形状与它一致，2.1.2.1 的同步器按同一形状写入时表结构与查询都不用改。
   * 响应里的 {@code ownershipNote} 把这件事原样报给界面。
   */
  @GetMapping("/mappings")
  public AftersalesReasonMappingList mappings(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String platformSource,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String mappingStatus) {
    return call(
        auth, actor -> reasons.mappings(platformSource, categoryId, mappingStatus));
  }

  /**
   * 新建一条平台原因码映射。
   *
   * <p>⚠️ {@code capturedAt} 是**采集时刻**不是登记时刻，缺省才取 now()；
   * 同步器写入时必须给真实采集时刻，否则同步历史不可回溯。
   */
  @PostMapping("/mappings")
  @ResponseStatus(HttpStatus.CREATED)
  public AftersalesReasonMappingRow createMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody AftersalesReasonMappingCreate request) {
    return write(auth, actor -> reasons.createMapping(request, actor.userId()));
  }

  /**
   * 更新一条映射（内部分类 / 平台原因名 / 状态）。
   *
   * <p>⚠️ {@code platformSource} 与 {@code platformReasonCode} 是<b>匹配键</b>，不可改 ——
   * 改它们等于把一条映射换成另一条；要换请删了重建。
   */
  @PatchMapping("/mappings/{id}")
  public AftersalesReasonMappingRow updateMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id,
      @RequestBody AftersalesReasonMappingCreate request) {
    return write(auth, actor -> reasons.updateMapping(id, request, actor.userId()));
  }

  /**
   * 删除一条平台原因码映射。
   *
   * <p>⚠️ <b>同步器写的行不许删，只许停用。</b>同步器下次运行会写回来，
   * 删了会变成「我明明停用了却又生效了」；而且删掉之后平台侧那个原因码
   * 在本租户就没有任何口径了，归因会退化成「无法归类的平台原因」。
   * 租户自维护的行才允许删。
   */
  @DeleteMapping("/mappings/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteMapping(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID id) {
    write(
        auth,
        actor -> {
          reasons.deleteMapping(id);
          return null;
        });
  }

  // ================================================================ 报表

  /**
   * 报表（统一口径）。
   *
   * <p>蓝图输出句：「原因分类记录；<b>报表用统一口径</b>」。
   * 只统计**当前生效**的归因集合，主因与次因分开计数，
   * 七类根口径零也出现，占比的分母由响应里的 {@code semantics} 逐条写明 ——
   * 下游 11.5.2 与 6 采购（**均未交付**）交付后直接取用，不必自己再定一套。
   */
  @GetMapping("/reports")
  public AftersalesReasonReport report(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, actor -> reasons.report());
  }
}
