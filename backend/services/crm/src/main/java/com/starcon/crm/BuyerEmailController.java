package com.starcon.crm;

import static com.starcon.crm.BuyerEmailModels.*;

import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailAttachment;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailAttachmentInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailCapabilities;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailConversation;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailCreateInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailDetail;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailPage;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailRow;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSendInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSendRecord;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSendResult;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailShopView;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSyncInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailSyncResult;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailTemplateInput;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailTemplatePage;
import com.starcon.crm.BuyerEmailModels.CrmBuyerEmailTemplateRow;
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
 * 15.1.2.1 邮件收发（买家邮件）。
 *
 * <p>命名空间 {@code /crm/buyer-emails}：前端入口同名（页面 {@code /crm/emails}），
 * 浏览器只经 {@code /api/crm} 代理访问；后端路径按<b>服务</b>命名空间走，
 * 与 catalog（/catalog/...）、trade（/trade/...）同一纪律。
 *
 * <p>⚠️ 与同服务的 {@code /crm/review-requests}（15.2.2.1 索评）、
 * {@code /crm/review-rules}（15.2.2.2 索评规则）、
 * {@code /crm/knowledge}（15.4.2.1 客服知识库）**刻意不共用命名空间**：
 * 索评管「要不要向这位买家索评」、知识库管「这个格子该答哪一条」，
 * 本模块管「这位买家发来的这封邮件是什么，我们回过没有」。
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 */
@RestController
@RequestMapping("/crm/buyer-emails")
public class BuyerEmailController {

  private final AccessService access;
  private final BuyerEmailService emails;

  public BuyerEmailController(AccessService access, BuyerEmailService emails) {
    this.access = access;
    this.emails = emails;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm." + permission);
    return access.run(actor, "crm." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmBuyerEmailCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "email.read", a -> emails.capabilities());
  }

  /**
   * 店铺目录（来自上游 channel 的真实清单）+ 每个平台的买家邮件接入状态。
   *
   * <p>上游读不到一律 503 —— **绝不返回空集合冒充「没有店铺」**。
   */
  @GetMapping("/shops")
  public List<CrmBuyerEmailShopView> shops(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "email.read", a -> emails.shops(auth));
  }

  /** 邮件列表（真分页 + 真筛选）。 */
  @GetMapping
  public CrmBuyerEmailPage list(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String platformCode,
      @RequestParam(required = false) String direction,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String buyerRef,
      @RequestParam(required = false) String salesOrderNo,
      @RequestParam(required = false) String conversationKey,
      @RequestParam(required = false) String subject,
      @RequestParam(required = false) String receivedFrom,
      @RequestParam(required = false) String receivedTo,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "email.read",
        a ->
            emails.emailPage(
                shopId,
                platformCode,
                direction,
                status,
                buyerRef,
                salesOrderNo,
                conversationKey,
                subject,
                receivedFrom,
                receivedTo,
                page,
                size));
  }

  /**
   * 平台买家邮件同步。
   *
   * <p>🔴 当前**恒为 409** 且**不写任何数据**：平台买家邮件适配器未交付
   * （channel/vendor 只有 OAuth 适配器）。未接入是业务状态，不是故障 ——
   * 返回空列表会让运营以为这个店铺没有买家来信。
   */
  @PostMapping("/sync")
  public CrmBuyerEmailSyncResult sync(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmBuyerEmailSyncInput input) {
    return call(auth, "email.update", a -> emails.sync(input, auth));
  }

  /** 登记一封**收到的**买家邮件（人工登记入口）。 */
  @PostMapping
  public CrmBuyerEmailRow create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmBuyerEmailCreateInput input) {
    return call(auth, "email.update", a -> emails.create(input, a.userId(), auth));
  }

  /** 单封邮件详情 = 邮件本体 + 附件 + 发送记录 + 同会话邮件。 */
  @GetMapping("/{emailId}")
  public CrmBuyerEmailDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId) {
    return call(auth, "email.read", a -> emails.detail(emailId));
  }

  /**
   * 会话视图（加粗句「邮件与站内信在同一会话处理」的落点）。
   *
   * <p>🔴 站内信那一半**不可见**（15.1.1.1 未交付）：本端点**不假装**能看到站内信原文，
   * 同源靠 {@code conversationKey} 相等来标记。
   */
  @GetMapping("/{emailId}/conversation")
  public CrmBuyerEmailConversation conversation(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId) {
    return call(auth, "email.read", a -> emails.conversation(emailId));
  }

  /** 登记一个附件的**元数据**（本服务不持有文件字节）。 */
  @PostMapping("/{emailId}/attachments")
  public CrmBuyerEmailAttachment attachment(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId,
      @Valid @RequestBody CrmBuyerEmailAttachmentInput input) {
    return call(auth, "email.update", a -> emails.addAttachment(emailId, input));
  }

  /** 一封邮件的发送记录（时间线）。 */
  @GetMapping("/{emailId}/send-records")
  public List<CrmBuyerEmailSendRecord> sendRecords(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId) {
    return call(auth, "email.read", a -> emails.detail(emailId).sendRecords());
  }

  /**
   * 登记一次发送（= 蓝图输出句里的「发送记录」）。
   *
   * <p>🔴 {@code transport=PLATFORM_API} 会被服务端拒绝：平台侧邮件发送接口**未接入**。
   * 🔴 发送失败**保留原邮件状态**（收到的那封仍是「已收到」），失败原因留在发送记录里。
   */
  @PostMapping("/{emailId}/send-records")
  public CrmBuyerEmailSendResult sendRecord(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID emailId,
      @Valid @RequestBody CrmBuyerEmailSendInput input) {
    return call(auth, "email.update", a -> emails.addSendRecord(emailId, input, a.userId()));
  }

  /** 邮件模板列表（真分页 + 真筛选）。 */
  @GetMapping("/templates")
  public CrmBuyerEmailTemplatePage templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID shopId,
      @RequestParam(required = false) String locale,
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth, "email.read", a -> emails.templatePage(shopId, locale, status, keyword, page, size));
  }

  /** 新建邮件模板。 */
  @PostMapping("/templates")
  public CrmBuyerEmailTemplateRow createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmBuyerEmailTemplateInput input) {
    return call(auth, "email.update", a -> emails.createTemplate(input, a.userId()));
  }

  /** 单条邮件模板详情。 */
  @GetMapping("/templates/{templateId}")
  public CrmBuyerEmailTemplateRow template(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    return call(auth, "email.read", a -> emails.template(templateId));
  }

  /** 改邮件模板（模板编码建后不可改）。 */
  @PutMapping("/templates/{templateId}")
  public CrmBuyerEmailTemplateRow updateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId,
      @Valid @RequestBody CrmBuyerEmailTemplateInput input) {
    return call(auth, "email.update", a -> emails.updateTemplate(templateId, input, a.userId()));
  }

  /**
   * 删邮件模板。
   *
   * <p>⚠️ **已被发送记录引用过的模板删不掉**（DB 触发器）：删掉它会让
   * 「这次回复用的哪套话术」在历史里只剩一个编码快照、没有正文可查。要停用请改状态。
   */
  @DeleteMapping("/templates/{templateId}")
  public void deleteTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID templateId) {
    call(
        auth,
        "email.update",
        a -> {
          emails.deleteTemplate(templateId);
          return null;
        });
  }
}
