package com.starcon.crm;

import static com.starcon.crm.MessageSendModels.*;

import com.starcon.crm.MessageSendModels.CrmMessageSendBatchInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendBatchResult;
import com.starcon.crm.MessageSendModels.CrmMessageSendCapabilities;
import com.starcon.crm.MessageSendModels.CrmMessageSendDetail;
import com.starcon.crm.MessageSendModels.CrmMessageSendInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendKnowledgeTemplate;
import com.starcon.crm.MessageSendModels.CrmMessageSendOutcome;
import com.starcon.crm.MessageSendModels.CrmMessageSendPage;
import com.starcon.crm.MessageSendModels.CrmMessageSendResultInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendRetryInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendTargetPage;
import com.starcon.crm.MessageSendModels.CrmMessageSendTemplateInput;
import com.starcon.crm.MessageSendModels.CrmMessageSendTemplatePage;
import com.starcon.crm.MessageSendModels.CrmMessageSendTemplateRow;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
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

/**
 * 15.1.1.2 消息发送。
 *
 * <p>命名空间 {@code /crm/message-sends}：前端入口同名。
 * 发送记录挂在 {@code /crm/message-sends/sends}（列表 / 单条详情 / 登记 / 回登记 / 重试），
 * 模板挂在 {@code /templates}，目录与动作挂在发送命名空间下（{@code /capabilities}
 * {@code /knowledge-templates} {@code /targets} {@code /batch}）——
 * <b>裸路径 {@code /crm/message-sends} 刻意不是一个端点</b>，所以白名单里不放行它。
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/message-inbox}（15.1.1.1 收件箱）<b>刻意不共用命名空间</b>：
 * 本模块管<b>发出去的那一条</b>，收件箱管<b>收到的消息</b>。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code crm.message.send.read} / {@code crm.message.send.update}：
 * 与同服务兄弟模块（{@code crm.message.*} / {@code crm.review.*} / {@code crm.knowledge.*}）
 * 同一命名法，identity 服务<b>零改动</b>。
 */
@RestController
@RequestMapping("/crm/message-sends")
public class MessageSendController {

  private final AccessService access;
  private final MessageSendService sends;

  public MessageSendController(AccessService access, MessageSendService sends) {
    this.access = access;
    this.sends = sends;
  }

  /** 统一入口：先确认身份与权限（租户<b>不</b>来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.message.send." + permission);
    return access.run(actor, "crm.message.send." + permission, () -> work.apply(actor));
  }

  /** 无返回值的统一入口（与上面同一个守卫，只是没有结果）。 */
  private void callVoid(String bearer, String permission, java.util.function.Consumer<AccessService.Access> work) {
    var actor = access.require(bearer, "crm.message.send." + permission);
    access.run(
        actor,
        "crm.message.send." + permission,
        () -> {
          work.accept(actor);
          return null;
        });
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmMessageSendCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> sends.capabilities());
  }

  // ------------------------------------------------------------ 回复模板

  /** 模板列表（真分页 + 真筛选）。 */
  @GetMapping("/templates")
  public CrmMessageSendTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> sends.templates(shopId, locale, status, keyword, page, size));
  }

  /** 单条模板详情。 */
  @GetMapping("/templates/{templateId}")
  public CrmMessageSendTemplateRow template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "read", a -> sends.template(templateId));
  }

  /** 新建模板。 */
  @PostMapping("/templates")
  public CrmMessageSendTemplateRow createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageSendTemplateInput input) {
    return call(auth, "update", a -> sends.createTemplate(input, a.userId()));
  }

  /** 改模板（历史发送记录不受影响 —— 它们快照了当时的话术）。 */
  @PutMapping("/templates/{templateId}")
  public CrmMessageSendTemplateRow updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody CrmMessageSendTemplateInput input) {
    return call(auth, "update", a -> sends.updateTemplate(templateId, input, a.userId()));
  }

  /**
   * 删除模板。
   *
   * <p>已被发送记录引用时改成<b>停用</b>（不真删）—— 删掉之后历史发送记录上的
   * template_id 会指向一个不存在的模板，「当时用的哪套话术」就再也读不出来了。
   */
  @DeleteMapping("/templates/{templateId}")
  public void deleteTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    callVoid(auth, "update", a -> sends.deleteTemplate(templateId));
  }

  /** 知识库话术来源（15.4.2 上游，只列此刻生效的版本）。 */
  @GetMapping("/knowledge-templates")
  public List<CrmMessageSendKnowledgeTemplate> knowledgeTemplates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String locale) {
    return call(auth, "read", a -> sends.knowledgeTemplates(shopId, locale));
  }

  // ------------------------------------------------------------ 可回复目标

  /**
   * 可回复的目标消息（来自 15.1.1.1 收件箱）。
   *
   * <p>🔴 「已回复」是<b>推导</b>出来的（存在一条 SENT 的发送记录），本模块只读源消息。
   */
  @GetMapping("/targets")
  public CrmMessageSendTargetPage targets(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String buyerReference,
      @RequestParam(required = false) String orderReference,
      @RequestParam(required = false) Boolean onlyUnreplied,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            sends.targets(
                shopId, keyword, buyerReference, orderReference, onlyUnreplied, page, size));
  }

  // ------------------------------------------------------------ 发送记录

  /** 发送记录列表（真分页 + 真筛选 + 与分页无关的全量汇总）。 */
  @GetMapping("/sends")
  public CrmMessageSendPage sends(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) UUID targetMessageId,
      @RequestParam(required = false) UUID batchId,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            sends.sends(
                shopId, platformCode, status, targetMessageId, batchId, keyword, page, size));
  }

  /** 发送记录详情 = 发送本体 + 附件 + 尝试留痕 + 目标消息状态。 */
  @GetMapping("/sends/{sendId}")
  public CrmMessageSendDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sendId) {
    return call(auth, "read", a -> sends.detail(sendId));
  }

  /**
   * 登记一次发送（调用模板 / 手工编辑 / 带附件）。
   *
   * <p>🔴 {@code idempotencyKey} 必填：重放同一条键**不会**产生第二条发送记录。
   *
   * <p>🔴 {@code transport=PLATFORM_API} 会被服务端<b>拒绝</b>（平台发送接口未接入），
   * 且一次数据都不写。
   */
  @PostMapping("/sends")
  public CrmMessageSendOutcome send(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageSendInput input) {
    return call(auth, "update", a -> sends.send(input, a.userId(), auth));
  }

  /**
   * 批量相同回复（同类问题）：同一个正文发给多条消息。
   *
   * <p>逐条给出结论与原因；失败的条目不会被悄悄跳过。
   */
  @PostMapping("/batch")
  public CrmMessageSendBatchResult batch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageSendBatchInput input) {
    return call(auth, "update", a -> sends.batch(input, a.userId(), auth));
  }

  /**
   * 登记一次投递的结果（人工交接：运营在平台侧实际发出或失败之后回登记）。
   *
   * <p>🔴 登记 SENT **不改源消息** —— 已回复状态由「存在一条 SENT 的发送记录」推导。
   */
  @PostMapping("/sends/{sendId}/result")
  public CrmMessageSendOutcome result(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sendId,
      @Valid @RequestBody CrmMessageSendResultInput input) {
    return call(auth, "update", a -> sends.registerResult(sendId, input, a.userId()));
  }

  /** 失败重试：开一次新的尝试（不改正文）。 */
  @PostMapping("/sends/{sendId}/retry")
  public CrmMessageSendOutcome retry(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID sendId,
      @RequestBody(required = false) CrmMessageSendRetryInput input) {
    return call(auth, "update", a -> sends.retry(sendId, input, a.userId()));
  }
}
