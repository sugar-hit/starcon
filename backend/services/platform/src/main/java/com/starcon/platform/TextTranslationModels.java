package com.starcon.platform;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 18.4.2.1「文本与翻译」的传输模型。
 *
 * <p>蓝图 18-integration-and-automation.md 原文：
 *
 * <blockquote>
 *
 * 职责：AI 文本能力：多语言翻译（标题/描述/客服话术，支持保留品牌词与专业术语）、标题优化与生成、
 * 卖点提炼、违禁词规避建议、客服回复草稿。 输入：源文本；目标语言；风格要求；术语表。
 * 输出：翻译/生成结果 + 置信度提示 + 可人工编辑。 关系：上游 = 3.4.3 多语言、4.2.1.1 编辑器、
 * 15.1.1.2 发送；下游 = 无（结果需人工确认）。 <b>翻译结果必须人工确认后发布</b>，
 * 直接发机器翻译是品牌风险。
 *
 * </blockquote>
 *
 * <p><b>命名。</b>统一用 {@code Text*} 前缀 —— 刻意不用 {@code Integration*}（那是 18.1.1.1
 * 「应用与凭证」的命名空间），也不用 {@code Platform*}（channel 服务那一族说的是
 * 「Amazon / eBay 这些**电商平台**」，与本模块是两回事）。契约被 codegen 合并进同一个扁平
 * 命名空间，前缀撞车会让读代码的人以为是一件事。
 *
 * <p><b>🔴 本模块一个 {@code double} 都没有</b>：置信度是 {@link BigDecimal}
 * （库里 {@code NUMERIC(5,4)}），长度是 {@code int}（字符数）。本模块不落任何金额。
 *
 * <p><b>🔴 「翻译结果」在本仓当前拿不到</b>（AI 供应商未接入，见
 * {@link AiTextGenerationPort}）：{@link TextGenerationOutcome} 因此带
 * {@code connected=false / generated=false} 与一条写明「缺什么、归谁」的 {@code reason}，
 * **它不是一个「翻译好了」的结果**。
 */
public final class TextTranslationModels {

  private TextTranslationModels() {}

  // ================================================================ 能力声明

  /**
   * 一项能力（或一项「还没接通」的能力）。
   *
   * @param available 该能力**现在**能不能用（不是「将来会有」）
   * @param evidence 凭什么这么判（端点 / 表 / 规则的具体名字）
   * @param ownerModule 还没接通的能力，**缺在哪个模块**
   */
  public record TextCapability(
      String moduleId,
      String name,
      boolean available,
      String evidence,
      String ownerModule,
      String note) {}

  /** 一个「还没交付」的下游 / 兄弟能力。 */
  public record TextGap(
      String moduleId, String name, String service, boolean available, String relation, String note) {}

  /** 能力声明总览。 */
  public record TextCapabilities(List<TextCapability> capabilities, List<TextGap> gaps) {}

  /** 概览：几个数。 */
  public record TextOverview(
      long total,
      long pending,
      long drafted,
      long confirmed,
      long published,
      long rejected,
      long glossary,
      long bannedTerms,
      long rules,
      long templates,
      long drafts,
      OffsetDateTime generatedAt) {}

  // ================================================================ 术语表

  /** 术语类型。 */
  public enum TermType {
    /** 品牌词 / 商标 —— 译文里缺失即**拦住发布**。 */
    BRAND("品牌词"),
    /** 专业术语 —— 缺失给警告，由运营判断。 */
    PROFESSIONAL("专业术语"),
    /** 商品专名 / 型号 —— 缺失给警告。 */
    PRODUCT("商品专名");

    private final String label;

    TermType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}（服务层给一句能直接展示的话）。 */
    public static TermType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var t : values()) {
        if (t.name().equalsIgnoreCase(raw.trim())) return t;
      }
      return null;
    }
  }

  /** 术语表一行。 */
  public record TextGlossaryRow(
      UUID id,
      String sourceTerm,
      String targetTerm,
      String termType,
      String termTypeLabel,
      String targetLocale,
      boolean caseSensitive,
      boolean enabled,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 术语表写入（新建或改；id 为空即新建）。 */
  public record TextGlossaryInput(
      String sourceTerm,
      String targetTerm,
      String termType,
      String targetLocale,
      Boolean caseSensitive,
      Boolean enabled,
      String note) {}

  // ================================================================ 违禁词

  /** 违禁词分类。 */
  public enum BannedCategory {
    ABSOLUTE("绝对化用语"),
    CLAIM("功效或效果宣称"),
    GUARANTEE("承诺与担保"),
    COMPETITOR("竞品指向"),
    CONTACT("导流联系方式"),
    INCENTIVE("诱导好评");

    private final String label;

    BannedCategory(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static BannedCategory parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  /** 违禁词一行。 */
  public record TextBannedTermRow(
      UUID id,
      String term,
      String category,
      String categoryLabel,
      String severity,
      String targetLocale,
      boolean enabled,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 违禁词写入。 */
  public record TextBannedTermInput(
      String term,
      String category,
      String severity,
      String targetLocale,
      Boolean enabled,
      String note) {}

  // ================================================================ 长度与字符集规则

  /** 字符集要求。 */
  public enum CharsetRule {
    ANY("不限制"),
    LATIN("不得含中日韩字符"),
    CJK("需含中日韩字符");

    private final String label;

    CharsetRule(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static CharsetRule parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  /** 规则一行。 */
  public record TextRuleRow(
      UUID id,
      String contentType,
      String targetLocale,
      Integer minLength,
      Integer maxLength,
      String requireCharset,
      String requireCharsetLabel,
      boolean terminologyStrict,
      boolean enabled,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 规则写入。 */
  public record TextRuleInput(
      String contentType,
      String targetLocale,
      Integer minLength,
      Integer maxLength,
      String requireCharset,
      Boolean terminologyStrict,
      Boolean enabled,
      String note) {}

  // ================================================================ 客服回复模板

  /** 模板一行。 */
  public record TextTemplateRow(
      UUID id,
      String templateCode,
      String templateName,
      String body,
      List<String> variables,
      String contentType,
      String targetLocale,
      boolean enabled,
      String note,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 模板写入。 */
  public record TextTemplateInput(
      String templateCode,
      String templateName,
      String body,
      String contentType,
      String targetLocale,
      Boolean enabled,
      String note) {}

  // ================================================================ 任务

  /** 任务状态。 */
  public enum TaskStatus {
    /** 已建任务，等待生成（AI 供应商未接入时停在这一步）。 */
    PENDING("待生成"),
    /** 有译文草稿，等待人工复核。 */
    DRAFTED("待复核"),
    /** 人工已确认 —— **发布的硬门槛**。 */
    CONFIRMED("已确认"),
    /** 已发布，下游可取。 */
    PUBLISHED("已发布"),
    /** 被驳回（终态）。 */
    REJECTED("已驳回");

    private final String label;

    TaskStatus(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static TaskStatus parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var s : values()) {
        if (s.name().equalsIgnoreCase(raw.trim())) return s;
      }
      return null;
    }
  }

  /** 内容类型。 */
  public enum ContentType {
    TITLE("标题"),
    DESCRIPTION("描述"),
    HIGHLIGHT("卖点"),
    CUSTOMER_REPLY("客服话术");

    private final String label;

    ContentType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    /** 解析；非法取值返回 {@code null}。 */
    public static ContentType parse(String raw) {
      if (raw == null || raw.isBlank()) return null;
      for (var c : values()) {
        if (c.name().equalsIgnoreCase(raw.trim())) return c;
      }
      return null;
    }
  }

  /** 任务一行（列表与详情共用）。 */
  public record TextTaskRow(
      UUID id,
      String taskNo,
      String contentType,
      String contentTypeLabel,
      String sourceText,
      String sourceLocale,
      String targetLocale,
      String styleRequirement,
      String upstreamRefType,
      UUID upstreamRefId,
      String status,
      String statusLabel,
      UUID currentDraftId,
      String currentDraftText,
      BigDecimal confidence,
      BigDecimal confidenceTermCoverage,
      BigDecimal confidencePlaceholderIntegrity,
      BigDecimal confidenceCheckPassRate,
      BigDecimal confidenceLengthCompliance,
      int checkTotal,
      int checkFailed,
      int checkWarned,
      UUID confirmedBy,
      OffsetDateTime confirmedAt,
      OffsetDateTime publishedAt,
      String rejectReason,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 任务写入。 */
  public record TextTaskInput(
      String contentType,
      String sourceText,
      String sourceLocale,
      String targetLocale,
      String styleRequirement,
      String upstreamRefType,
      UUID upstreamRefId,
      String templateId) {}

  /** 任务列表（真分页）。 */
  public record TextTaskPage(List<TextTaskRow> items, int page, int size, long total, long totalPages) {}

  // ================================================================ 草稿与检查

  /** 译文草稿一行。 */
  public record TextDraftRow(
      UUID id,
      UUID taskId,
      int version,
      String targetText,
      String origin,
      BigDecimal confidence,
      BigDecimal confidenceTermCoverage,
      BigDecimal confidencePlaceholderIntegrity,
      BigDecimal confidenceCheckPassRate,
      BigDecimal confidenceLengthCompliance,
      int checkTotal,
      int checkFailed,
      int checkWarned,
      UUID editedBy,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 译文写入（人工可编辑的那一版）。 */
  public record TextDraftInput(String targetText) {}

  /** 一条检查结论。 */
  public record TextCheckRow(
      UUID id,
      UUID taskId,
      UUID draftId,
      String ruleCode,
      String ruleLabel,
      String verdict,
      String matchedItem,
      String detail,
      OffsetDateTime createdAt) {}

  /**
   * 一条**规避建议**。
   *
   * @param suggestion 人可执行的建议（改哪个词、为什么）
   */
  public record TextViolation(
      String ruleCode,
      String severity,
      String matchedItem,
      String suggestion,
      String detail) {}

  /**
   * 置信度提示（**必须能解释**）。
   *
   * @param explanation 逐维的人话说明（前端直接显示，不必自己编）
   */
  public record TextConfidence(
      BigDecimal total,
      BigDecimal termCoverage,
      BigDecimal placeholderIntegrity,
      BigDecimal checkPassRate,
      BigDecimal lengthCompliance,
      List<String> explanation) {}

  /** 任务详情（任务 + 当前草稿 + 历史版本 + 检查结论 + 规避建议）。 */
  public record TextTaskDetail(
      TextTaskRow task,
      TextDraftRow draft,
      List<TextDraftRow> history,
      List<TextCheckRow> checks,
      List<TextViolation> violations,
      TextConfidence confidence) {}

  // ================================================================ 生成（AI 供应商未接入）

  /**
   * 一次「生成 / 翻译」的结论。
   *
   * 🔴 **本仓当前 AI 供应商未接入** ⇒ {@code connected=false}、{@code generated=false}、
   *   {@code text} 为空串，{@code reason} 写明缺什么、归哪个模块。
   *   **它不是一个「翻译好了」的结果** —— 一条假的成功回执会让「文案已经翻译好了」
   *   在数据上看起来发生过，而现场一个字都没翻译出来。
   */
  public record TextGenerationOutcome(
      boolean connected,
      boolean generated,
      String text,
      String model,
      String reason,
      String ownerModule) {}

  // ================================================================ 上游候选源文本

  /** 一条可拿来翻译的上游文案。 */
  public record TextSourceCandidate(
      String refType,
      UUID refId,
      String refLabel,
      String locale,
      String name,
      String description) {}

  /**
   * 上游候选源文本（**跨服务只读**）。
   *
   * @param source 取自哪个上游服务
   * @param note 取不到 / 部分可用时的说明
   */
  public record TextSourceCandidateView(
      List<TextSourceCandidate> items, String source, String note) {}

  /**
   * 可选目标语言（来自 **3.4.3 多语言**的租户语言需求）。
   *
   * @param source 上游服务名
   * @param note 上游不可用或部分可用时的说明
   */
  public record TextLocaleView(
      List<TextLocaleOption> items, String source, String note) {}

  /** 一个目标语言。 */
  public record TextLocaleOption(
      String locale, String localeName, boolean enabled, long translationCount, long pendingCount) {}

  /** 客服回复草稿的变量绑定结果。 */
  public record TextTemplateBinding(
      UUID templateId,
      String templateCode,
      String body,
      List<String> variables,
      List<String> missing,
      List<String> unknown) {}
}
