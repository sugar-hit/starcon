package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 18.5.1.1「模板管理」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：管理通知与消息模板：模板内容、变量、适用场景、语言版本、启用状态；修改后新产生的通知使用新版本，
 * 历史通知保留当时内容。 输入：模板编辑；变量定义。 输出：模板记录 + 渲染预览。 关系：上游 = 3.4.2 文案资产；
 * 下游 = 1.3.1.1、15.1.1.2、18.5.2。 <b>模板必须版本化</b>，已发出的通知不能被新模板"篡改"。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code NotifyTemplate*} 前缀 —— 刻意不用 {@code Text*}（那是 18.4.2.1「文本与翻译」
 * 的命名空间：AI 翻译链路的产物，**另一套东西**）、不用 {@code Notification*}（那是 identity 服务
 * 1.3.1.1「通知列表」的命名空间，跨服务同名会让读代码的人以为是一件事），
 * 也**不用裸 {@code Template*}** —— catalog 服务里 {@code Template*} 已经是**价格模板**的词汇
 * （{@code TemplateInput} / {@code TemplatePage} / {@code TemplateDetail}），
 * 契约是九个服务合并进同一个扁平命名空间的，同名会让消费端拿到**错的那一份**字段
 * （codegen 会直接报「跨服务同名 DTO：字段级覆盖」并中止）。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：模板里出现的金额是**变量取值**（渲染时填进去的文本），
 * 本模块不记账。版本号与计数都是 {@code int} / {@code long}。
 *
 * <p><b>🔴 请求体里没有 tenantId / userId / membershipId</b>：租户与操作人只从已认证会话解析
 * （{@link AccessService}），不接受请求参数传入 —— platform 服务开着
 * {@code fail-on-unknown-properties}，多传一个字段直接 400。
 */
public final class NotificationTemplateModels {

  private NotificationTemplateModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（表 / 端点 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   */
  public record NotifyTemplateCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟 / 上游能力。 */
  public record NotifyTemplateGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record NotifyTemplateCapabilities(
      List<NotifyTemplateCapability> capabilities, List<NotifyTemplateGap> gaps, NotifyTemplateConventions conventions) {}

  /** 一个场景码。 */
  public record NotifyTemplateSceneOption(String code, String label) {}

  /** 一个变量类型码。 */
  public record NotifyTemplateVariableTypeOption(String code, String label) {}

  /** 一个渲染错误码（写清「什么时候会报这个」）。 */
  public record NotifyTemplateRenderErrorCode(String code, String description) {}

  /**
   * 三条约定（**口径**，不是数据）：场景词表、语言写法、变量写法。
   *
   * <p>🔴 场景词表与迁移里的 CHECK 约束**逐字同表**（集成测试里有一条断言专门守着这件事），
   * 语言写法是 IANA 形状，变量写法是 {@code {{变量名}}}。
   */
  public record NotifyTemplateConventions(
      List<NotifyTemplateSceneOption> scenes,
      String languageRule,
      List<String> languageExamples,
      List<NotifyTemplateVariableTypeOption> variableTypes,
      List<NotifyTemplateRenderErrorCode> renderErrorCodes) {}

  /** 概览：几个数。 */
  public record NotifyTemplateOverview(
      long templates,
      long activeTemplates,
      long archivedTemplates,
      long versions,
      long languages,
      long scenes,
      OffsetDateTime generatedAt) {}

  // ================================================================ 词表

  /**
   * 适用场景（**受控词表**，与迁移里的 CHECK 逐字同表）。
   *
   * <p>下游按场景取模板；自由文本会让「按场景取模板」退化成模糊匹配。
   */
  public enum NotifyTemplateScene {
    ORDER_STATUS("订单状态变更"),
    STOCK("库存与缺货"),
    PRICE("价格与促销"),
    LOGISTICS("物流与履约"),
    CUSTOMER_SERVICE("客服消息"),
    MARKETING("营销触达"),
    SYSTEM("系统公告");

    private final String label;

    NotifyTemplateScene(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static NotifyTemplateScene parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /**
   * 变量类型（受控词表）。
   *
   * <p>类型不是装饰：{@code NUMBER} 的取值必须**能解析成数字**、{@code DATE} 必须**能解析成时间**，
   * 解析不了就报 {@code INVALID_VALUE} 并点名变量 —— 一条 {@code {{amount}}} 里塞了「十二块」，
   * 到下游算账时才炸，比在这里炸贵得多。
   */
  public enum NotifyTemplateVariableType {
    TEXT("文本"),
    NUMBER("数字"),
    DATE("日期时间"),
    CURRENCY("金额");

    private final String label;

    NotifyTemplateVariableType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static NotifyTemplateVariableType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  // ================================================================ 模板

  /** 模板主记录一行。 */
  public record NotifyTemplateRow(
      UUID id,
      String templateKey,
      String scene,
      String sceneLabel,
      String language,
      String name,
      String description,
      boolean enabled,
      @Schema(nullable = true) UUID enabledVersionId,
      @Schema(nullable = true) Integer enabledVersionNo,
      long versionCount,
      int latestVersionNo,
      boolean archived,
      @Schema(nullable = true) OffsetDateTime archivedAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 模板列表（真分页）。 */
  public record NotifyTemplatePage(List<NotifyTemplateRow> items, int page, int size, long total, int totalPages) {}

  /** 版本列表（真分页）。 */
  public record NotifyTemplateVersionPage(
      List<NotifyTemplateVersionRow> items, int page, int size, long total, int totalPages) {}

  /**
   * 一个变量定义。
   *
   * @param required 是否**必须**由调用方给值（true 时不允许有默认值）
   * @param defaultValue 取值缺省时的兜底（{@code required=false} 时才允许）
   */
  public record NotifyTemplateVariableDefinition(
      String name,
      String type,
      boolean required,
      @Schema(nullable = true) String defaultValue,
      String description) {}

  /** 一个版本行（**不可变**）。 */
  public record NotifyTemplateVersionRow(
      UUID id,
      UUID templateId,
      int versionNo,
      String subject,
      String body,
      List<NotifyTemplateVariableDefinition> variables,
      String changeNote,
      UUID createdBy,
      OffsetDateTime createdAt,
      boolean active,
      @Schema(nullable = true) OffsetDateTime activatedAt,
      @Schema(nullable = true) OffsetDateTime deactivatedAt) {}

  /** 模板详情（主记录 + 版本列表 + 当前生效版本）。 */
  public record NotifyTemplateDetail(
      NotifyTemplateRow template,
      List<NotifyTemplateVersionRow> versions,
      @Schema(nullable = true) NotifyTemplateVersionRow enabledVersion) {}

  /** 版本详情（版本本身 + 它所属模板的 key / 场景 / 语言）。 */
  public record NotifyTemplateVersionDetail(
      NotifyTemplateVersionRow version, String templateKey, String scene, String language, String templateName) {}

  /** 新建模板（**同时**落下第 1 版内容）。 */
  public record NotifyTemplateInput(
      String templateKey,
      String scene,
      String language,
      String name,
      String description,
      String subject,
      String body,
      List<NotifyTemplateVariableDefinition> variables,
      String changeNote) {}

  /**
   * 新增版本（= 编辑）。
   *
   * <p>🔴 **编辑语义**：这个接口**只追加**一个版本行，老版本原样不动。
   */
  public record NotifyTemplateVersionInput(
      String subject, String body, List<NotifyTemplateVariableDefinition> variables, String changeNote) {}

  /** 改模板的名称与说明（**内容不许在这里改** —— 内容只能新增版本）。 */
  public record NotifyTemplateMetadataInput(String name, String description) {}

  // ================================================================ 渲染

  /**
   * 一条渲染错误。
   *
   * @param code 错误码：{@code SYNTAX_ERROR} / {@code MISSING_VARIABLE} / {@code UNKNOWN_VARIABLE} /
   *     {@code EXTRA_VARIABLE} / {@code INVALID_VALUE}
   * @param variables 出问题的变量名（**点名**，不是「有个变量不对」）
   */
  public record NotifyTemplateRenderError(String code, String message, List<String> variables) {}

  /**
   * 渲染结果。
   *
   * @param ok 是否渲染成功（false 时 {@code renderedSubject} / {@code renderedBody} 一定为 null ——
   *     **绝不**渲染成空串，也**绝不**留下 {@code {{xxx}}} 残渣）
   * @param message 人话小结（成功时说渲染了什么，失败时说缺什么）
   */
  public record NotifyTemplateRenderResult(
      boolean ok,
      String message,
      @Schema(nullable = true) String renderedSubject,
      @Schema(nullable = true) String renderedBody,
      List<NotifyTemplateVariableDefinition> variables,
      List<NotifyTemplateRenderError> errors,
      @Schema(nullable = true) UUID templateId,
      @Schema(nullable = true) UUID versionId,
      @Schema(nullable = true) String templateKey,
      @Schema(nullable = true) String language,
      @Schema(nullable = true) Integer versionNo) {}

  /**
   * 渲染预览的入参。
   *
   * <p>两种用法：
   *
   * <ul>
   *   <li>{@code templateId + versionId} ⇒ 预览**已保存**的那一版（真实内容）；
   *   <li>{@code subject + body + variables} ⇒ 预览**还没保存**的草稿内容（不落库）。
   * </ul>
   */
  public record NotifyTemplateRenderInput(
      String templateId,
      String versionId,
      String subject,
      String body,
      List<NotifyTemplateVariableDefinition> variables,
      Map<String, String> values) {}

  // ================================================================ 按时刻解析

  /**
   * 「某个时刻生效的那一版」的解析结果。
   *
   * <p>🔴 下游（18.5.2.1 投递记录）要引用的就是这里的 {@code versionId} + 内容快照 ——
   * 引用「版本 id」而不是「当前版本」，后来改模板才不会篡改已经发出的通知。
   */
  public record NotifyTemplateResolved(
      UUID templateId,
      UUID versionId,
      int versionNo,
      String templateKey,
      String scene,
      String language,
      String subject,
      String body,
      List<NotifyTemplateVariableDefinition> variables,
      OffsetDateTime resolvedAt,
      OffsetDateTime activatedAt,
      @Schema(nullable = true) OffsetDateTime deactivatedAt) {}

  // ================================================================ 上游（3.4.2 文案资产）

  /**
   * 上游（catalog 服务 3.4.2 文案资产）的一条内容模板。
   *
   * <p>⚠️ 这是**上游的事实**，本模块**只读不抄**：抄一份就会漂移。
   */
  public record NotifyTemplateContentAssetRow(
      String source,
      UUID id,
      String code,
      String name,
      String note,
      boolean enabled,
      String pattern,
      long version) {}

  /** 上游文案资产清单（标题库 + 描述与卖点库）。 */
  public record NotifyTemplateContentAssetView(
      List<NotifyTemplateContentAssetRow> titles,
      List<NotifyTemplateContentAssetRow> descriptions,
      boolean connected,
      String note) {}
}
