package com.starcon.crm;

import static com.starcon.crm.MessageClassificationModels.*;

import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationBatchInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationBatchResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationCapabilities;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationClassifyInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationClassifyResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationDetail;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationFeedbackInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationFeedbackPage;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationFeedbackResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationOptions;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationPage;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleInput;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRulePage;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleResult;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationRuleRow;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationStatistics;
import com.starcon.crm.MessageClassificationModels.CrmMessageClassificationTrainingSamplePage;
import jakarta.validation.Valid;
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
 * 15.1.1.3 消息分类。
 *
 * <p>命名空间 {@code /crm/message-classifications}：前端入口同名（页面 {@code /crm/classifications}）。
 *
 * <p>⚠️ 与同服务的 {@code /crm/message-inbox}（15.1.1.1 消息收件箱）、
 * {@code /crm/message-sends}（15.1.1.2 消息发送）**刻意不共用命名空间**：
 * 那两个分别管**收到的消息**与**发出去的消息**，本模块管**怎么给消息分类**。
 * 分类能力走**自己的**前缀，所以收件箱那条里
 * {@code message-inbox/messages/{uuid}/classify} 依旧**不放行**（那条白名单本车道不动）。
 *
 * <p>集合与动作的挂法：
 *
 * <ul>
 *   <li>{@code /rules} —— 分类规则（GET 真分页真筛选 / POST 新建 / PUT 修改 / DELETE 删除）
 *       与 {@code /rules/{uuid}}；
 *   <li>{@code /classifications} 与 {@code /classifications/{uuid}} —— 分类结论；
 *   <li>{@code /messages/{uuid}} —— **某条消息**的当前结论 + 命中依据 + 纠正历史；
 *   <li>{@code /classify} / {@code /classify-batch} —— 发起分类；
 *   <li>{@code /feedback} —— 人工纠正（只追加）与纠正记录列表；
 *   <li>{@code /training-samples} —— 🔴 人工纠正回流成的**训练样本**；
 *   <li>{@code /statistics} —— 规则命中统计 + 标签分布 + 冲突与纠正；
 *   <li>{@code /capabilities} / {@code /options} —— 能力声明与筛选词表。
 * </ul>
 *
 * <p>租户<b>不</b>来自任何请求头：先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码 {@code crm.classification.read} / {@code crm.classification.update}：
 * 与同服务兄弟模块（{@code crm.message.*} / {@code crm.contact.*} / {@code crm.rule.*}）
 * 同一命名法，identity 服务<b>零改动</b>。
 */
@RestController
@RequestMapping("/crm/message-classifications")
public class MessageClassificationController {

  private final AccessService access;
  private final MessageClassificationService classifications;

  public MessageClassificationController(
      AccessService access, MessageClassificationService classifications) {
    this.access = access;
    this.classifications = classifications;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, "crm.classification." + permission);
    return access.run(actor, "crm.classification." + permission, () -> work.apply(actor));
  }

  /** 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。 */
  @GetMapping("/capabilities")
  public CrmMessageClassificationCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> classifications.capabilities());
  }

  /** 筛选词表（标签与优先级是 DB 允许的取值域；平台与消息类型来自本租户真实数据）。 */
  @GetMapping("/options")
  public CrmMessageClassificationOptions options(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> classifications.options());
  }

  // ================================================================ 规则

  /** 规则列表（真分页 + 真筛选）。 */
  @GetMapping("/rules")
  public CrmMessageClassificationRulePage rules(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String targetLabel,
      @RequestParam(required = false) Boolean enabled,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(auth, "read", a -> classifications.rules(keyword, targetLabel, enabled, page, size));
  }

  /** 单条规则。 */
  @GetMapping("/rules/{ruleId}")
  public CrmMessageClassificationRuleRow rule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "read", a -> classifications.requireRule(ruleId));
  }

  /** 新建一条规则（关键词必填非空）。 */
  @PostMapping("/rules")
  public CrmMessageClassificationRuleResult createRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageClassificationRuleInput input) {
    return call(auth, "update", a -> classifications.createRule(input, a.userId()));
  }

  /** 修改一条规则（规则编码建后不可改）。 */
  @PutMapping("/rules/{ruleId}")
  public CrmMessageClassificationRuleResult updateRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId,
      @Valid @RequestBody CrmMessageClassificationRuleInput input) {
    return call(auth, "update", a -> classifications.updateRule(ruleId, input, a.userId()));
  }

  /** 删除一条规则（已产生的结论与纠正不受影响）。 */
  @DeleteMapping("/rules/{ruleId}")
  public CrmMessageClassificationRuleResult deleteRule(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID ruleId) {
    return call(auth, "update", a -> classifications.deleteRule(ruleId));
  }

  // ================================================================ 分类

  /** 分类结论列表（真分页 + 真筛选 + 与分页无关的全量汇总）。 */
  @GetMapping("/classifications")
  public CrmMessageClassificationPage classifications(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID messageId,
      @RequestParam(required = false) String label,
      @RequestParam(required = false) String suggestedPriority,
      @RequestParam(required = false) Boolean priorityConflict,
      @RequestParam(required = false) Boolean autoReplyEligible,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            classifications.classifications(
                messageId,
                label,
                suggestedPriority,
                priorityConflict,
                autoReplyEligible,
                keyword,
                page,
                size));
  }

  /** 单条分类详情 = 结论 + 命中依据 + 消息正文 + 纠正历史。 */
  @GetMapping("/classifications/{classificationId}")
  public CrmMessageClassificationDetail detail(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID classificationId) {
    return call(auth, "read", a -> classifications.detail(classificationId));
  }

  /** 某条消息的当前分类结论 + 命中依据 + 纠正历史。 */
  @GetMapping("/messages/{messageId}")
  public CrmMessageClassificationDetail ofMessage(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable UUID messageId) {
    return call(auth, "read", a -> classifications.ofMessage(messageId));
  }

  /** 分类一条消息。 */
  @PostMapping("/classify")
  public CrmMessageClassificationClassifyResult classify(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageClassificationClassifyInput input) {
    return call(auth, "update", a -> classifications.classify(input, a.userId()));
  }

  /** 批量分类：逐条给出结论与原因，失败的条目不会被悄悄跳过。 */
  @PostMapping("/classify-batch")
  public CrmMessageClassificationBatchResult classifyBatch(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageClassificationBatchInput input) {
    return call(auth, "update", a -> classifications.classifyBatch(input, a.userId()));
  }

  // ================================================================ 人工纠正

  /**
   * 人工纠正（= 训练样本的来源）。
   *
   * <p>🔴 纠正**只追加**：不回写分类结论里的标签与优先级。
   */
  @PostMapping("/feedback")
  public CrmMessageClassificationFeedbackResult correct(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @Valid @RequestBody CrmMessageClassificationFeedbackInput input) {
    return call(auth, "update", a -> classifications.correct(input, a.userId()));
  }

  /** 纠正记录列表（真分页 + 真筛选）。 */
  @GetMapping("/feedback")
  public CrmMessageClassificationFeedbackPage feedback(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) UUID messageId,
      @RequestParam(required = false) String correctedLabel,
      @RequestParam(required = false) String predictedLabel,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a ->
            classifications.feedback(
                messageId, correctedLabel, predictedLabel, ruleCode, page, size));
  }

  // ================================================================ 训练样本与统计

  /**
   * 🔴 训练样本：机器预测 + 人工纠正 + 消息正文。
   *
   * <p>蓝图加粗句「人工纠正要回流为训练样本」的落点。
   */
  @GetMapping("/training-samples")
  public CrmMessageClassificationTrainingSamplePage trainingSamples(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String correctedLabel,
      @RequestParam(required = false) String predictedLabel,
      @RequestParam(required = false) String ruleCode,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "50") int size) {
    return call(
        auth,
        "read",
        a -> classifications.trainingSamples(correctedLabel, predictedLabel, ruleCode, keyword, page, size));
  }

  /** 分类统计：规则命中 + 标签分布 + 冲突 + 纠正一致率。 */
  @GetMapping("/statistics")
  public CrmMessageClassificationStatistics statistics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "read", a -> classifications.statistics());
  }
}
