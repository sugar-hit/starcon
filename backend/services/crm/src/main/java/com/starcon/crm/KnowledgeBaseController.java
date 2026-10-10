package com.starcon.crm;

import static com.starcon.crm.KnowledgeBaseModels.*;

import com.starcon.crm.KnowledgeBaseModels.CrmCloseVersionInput;
import com.starcon.crm.KnowledgeBaseModels.CrmCreateEntryInput;
import com.starcon.crm.KnowledgeBaseModels.CrmCreateVersionInput;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeCapabilities;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeEntry;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeEntryDetail;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeReference;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeScope;
import com.starcon.crm.KnowledgeBaseModels.CrmKnowledgeVersion;
import com.starcon.crm.KnowledgeBaseModels.CrmPublishVersionInput;
import com.starcon.crm.KnowledgeBaseModels.CrmRecordReferenceInput;
import com.starcon.crm.KnowledgeBaseModels.CrmReviewDueReport;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 15.4.2.1 客服知识库。
 *
 * <p>命名空间 {@code /crm/knowledge}（蓝图 15 卷给本模块指定的路由是 {@code /crm/knowledge}）：
 * 前端入口同名，浏览器只经 /api/crm 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p><b>刻意不与 11.4.2.2 的 {@code /catalog/kb} 共用命名空间</b>：那是另一个 bounded context
 * （catalog 服务、售后域、按平台规则版本组织）已经通过集成测试的既有契约。
 * 两个模块都叫「知识库」，但一个回答「这个平台政策这一版变了没有」，
 * 一个回答「这家店这个类目这个语言此刻该答哪一条」。边界理由见 V99 文件头第零节。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/crm/knowledge")
public class KnowledgeBaseController {

  private final AccessService access;
  private final KnowledgeBaseService knowledge;

  public KnowledgeBaseController(AccessService access, KnowledgeBaseService knowledge) {
    this.access = access;
    this.knowledge = knowledge;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm." + permission);
    return access.run(actor, "crm." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmKnowledgeCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "knowledge.read", a -> knowledge.capabilities());
  }

  /**
   * 组织范围：真实存在过的「店铺 × 类目 × 语言」格子。
   *
   * <p>三个下拉框的数据源。刻意做成查询而不是常量 —— 否则「按店铺/类目/语言组织」
   * 就退化成一个摆设。
   */
  @GetMapping("/scopes")
  public List<CrmKnowledgeScope> scopes(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "knowledge.read", a -> knowledge.scopes());
  }

  /**
   * 按格子取条目，每条带「此刻生效的那一版」（规则 R4）。
   *
   * <p>三个维度都可以省，但省掉任何一个都要**看得见地**退化：
   * 三个都传 = 「这家店这个类目中文版当前有效的条目有哪些」的精确答案。
   *
   * @param at 判定时刻，省略即此刻。带偏移量（例如 {@code 2026-10-10T08:00:00+08:00}）。
   */
  @GetMapping("/entries")
  public List<CrmKnowledgeEntry> entries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String topicType,
      @RequestParam(required = false) String at,
      @RequestParam(required = false, defaultValue = "false") boolean includeArchived) {
    return call(
        auth,
        "knowledge.read",
        a ->
            knowledge.list(
                shopId, categoryId, locale, platformCode, topicType, at, includeArchived));
  }

  /**
   * 「这个格子此刻生效的条目」—— 只含有生效版本的。
   *
   * <p>与 {@code GET /entries} 并列而不合并：后者是**管理视角**（这一格子里都有什么，
   * 包括还没发布的、已经过期的），本接口是**应答视角**（客服此刻能拿去用的有哪些）。
   * 下游 15.1.1.2 发送与 15.5.1 自动回复走的是本接口 —— 它们要的是「能用的」，
   * 不是「存在的」。
   */
  @GetMapping("/entries/effective")
  public List<CrmKnowledgeEntry> effectiveEntries(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) UUID categoryId,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String at) {
    return call(
        auth,
        "knowledge.read",
        a -> knowledge.effectiveEntries(shopId, categoryId, locale, at));
  }

  /** 档案详情 = 档案本体 + 全部版本（按版本号倒序）。 */
  @GetMapping("/entries/{entryId}")
  public CrmKnowledgeEntryDetail entry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId) {
    return call(auth, "knowledge.read", a -> knowledge.detail(entryId));
  }

  /** 新建知识档案（蓝图「输入：知识条目编辑」）。 */
  @PostMapping("/entries")
  public CrmKnowledgeEntry createEntry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmCreateEntryInput input) {
    return call(auth, "knowledge.update", a -> knowledge.createEntry(input, a.userId()));
  }

  /** 某条知识的全部版本（按版本号倒序）。 */
  @GetMapping("/entries/{entryId}/versions")
  public List<CrmKnowledgeVersion> versions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId) {
    return call(auth, "knowledge.read", a -> knowledge.versions(entryId));
  }

  /**
   * 新建一个草稿版本。
   *
   * <p>与上面的 GET <b>同一路径</b>、不同方法：列表与新建挂在同一条知识下，
   * 是既有服务（如 catalog 的 {@code bundles/{id}/versions}）的同一形状。
   * 建草稿是「知识条目编辑 + 版本管理」两个输入里的后者。
   */
  @PostMapping("/entries/{entryId}/versions")
  public CrmKnowledgeVersion createVersion(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId,
      @Valid @RequestBody CrmCreateVersionInput input) {
    return call(auth, "knowledge.update", a -> knowledge.createVersion(entryId, input));
  }

  /**
   * 发布一个草稿版本（不可逆）。
   *
   * <p><b>POST 而不是 PUT</b>：发布是一次不可逆的**状态流转**，不是「把这份资源改成这样」。
   */
  @PostMapping("/versions/{versionId}/publish")
  public CrmKnowledgeVersion publish(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID versionId,
      @Valid @RequestBody(required = false) CrmPublishVersionInput input) {
    return call(auth, "knowledge.update", a -> knowledge.publish(versionId, input, a.userId()));
  }

  /**
   * 向前关闭生效区间（规则 R5）—— 已发布版本上唯一合法的写操作。
   *
   * <p>关闭也是一次不可逆的动作（终点只能往前收），所以同样走 POST 而不是 PUT。
   */
  @PostMapping("/versions/{versionId}/close")
  public CrmKnowledgeVersion close(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID versionId,
      @Valid @RequestBody CrmCloseVersionInput input) {
    return call(auth, "knowledge.update", a -> knowledge.close(versionId, input));
  }

  /** 某版本的引用记录：谁在什么时候引用了它。 */
  @GetMapping("/versions/{versionId}/references")
  public List<CrmKnowledgeReference> references(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID versionId) {
    return call(auth, "knowledge.read", a -> knowledge.references(versionId));
  }

  /**
   * 记录一次引用（蓝图「输出：知识库内容 + 引用记录」）。
   *
   * <p>引用是**只追加**的：这里刻意没有 PUT / DELETE 形状 —— 允许改删等于可以把
   * 「没人用过」这个答案做出来（V99 第五节）。
   *
   * <p>下游 15.1.1.2 发送与 15.5.1 自动回复（**两者当前都未开发**）调用的就是这个端点。
   */
  @PostMapping("/versions/{versionId}/references")
  public CrmKnowledgeReference recordReference(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID versionId,
      @Valid @RequestBody CrmRecordReferenceInput input) {
    return call(auth, "knowledge.read", a -> knowledge.recordReference(versionId, input, a.userId()));
  }

  /** 某条知识全部版本的引用记录。 */
  @GetMapping("/entries/{entryId}/references")
  public List<CrmKnowledgeReference> entryReferences(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID entryId) {
    return call(auth, "knowledge.read", a -> knowledge.entryReferences(entryId));
  }

  /**
   * 「某平台改版后哪些条目需要复核」—— 蓝图粗体句的正面回答。
   *
   * <p>给定平台码与该平台新政策的生效时刻，返回当前生效版写于改版之前、
   * 且至今仍无接替版本的条目。判据原文一并返回，页面能自证它按什么查的。
   */
  @GetMapping("/review-due")
  public CrmReviewDueReport reviewDue(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam String platformCode,
      @RequestParam String policyChangedAt) {
    return call(auth, "knowledge.read", a -> knowledge.reviewDue(platformCode, policyChangedAt));
  }
}
