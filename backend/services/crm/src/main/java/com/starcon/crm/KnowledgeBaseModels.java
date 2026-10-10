package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/**
 * 15.4.2.1 客服知识库的传输模型。
 *
 * <p>蓝图 15 卷 15.4.2.1 原文：
 *
 * <blockquote>
 * 职责：维护客服知识库：常见问题标准答法、平台政策说明、产品使用指导、退换货规则，
 * 按店铺/类目/语言组织，支持版本与生效日期。
 * 输入：知识条目编辑；版本管理。输出：知识库内容 + 引用记录。
 * 关系：上游 = 3.4.2.2 文案资产；下游 = 15.1.1.2 发送、15.5.1 自动回复。
 * <b>知识条目必须标注适用平台与生效时间</b>，平台政策变更时才能快速定位过期内容。
 * </blockquote>
 *
 * <p><b>建模纪律</b>：
 *
 * <ul>
 *   <li><b>三个组织维度逐个必填</b>：{@code shopId} / {@code categoryId} / {@code locale}
 *       在 V99 里都是 NOT NULL，模型层同样不给「全部适用」的通配写法 ——
 *       理由见 V99 文件头第二节（允许通配会让「这个格子此刻该答哪一条」出现两个答案）。
 *   <li><b>生效区间用半开区间 {@code [from, to)}</b>，{@code effectiveTo} 为 null 表示 +∞，
 *       与 catalog V23 / V25 / V95 的 R1–R5 同一口径（规则 R1、规则 R4）。
 *   <li><b>版本是不可变的已发布事实</b>：{@link KnowledgeVersion#status()} 为 PUBLISHED 之后，
 *       正文与生效起点都不能改，只能新建一版接替（规则 R5 只允许向前关闭终点）。
 *   <li><b>引用是只追加的事实行</b>：{@link KnowledgeReference} 不提供任何修改 / 删除入口，
 *       因为「这条知识被用过没有」正是要回答的问题（V99 第五节）。
 * </ul>
 */
public final class KnowledgeBaseModels {

  private KnowledgeBaseModels() {}

  // ------------------------------------------------------------ 输入

  /**
   * 新建知识档案的输入。
   *
   * <p>{@code shopId} / {@code categoryId} 是**逻辑 ID**：店铺属 channel、类目属 catalog，
   * 本服务不建跨库外键，只存 ID + 名称快照（trade V1 同一纪律）。名称快照的作用是
   * 列表页不必回源就能显示，<b>不构成引用完整性</b>。
   *
   * <p>{@code topicKey} 是格子内的稳定业务键，建后不可改；两行并存时
   * 「这个格子这条问题的标准答法是什么」会有两个都自称标准的答案。
   */
  public record CreateEntryInput(
      @NotBlank @Size(max = 64) String topicKey,
      @NotBlank @Size(max = 200) String title,
      @NotNull UUID shopId,
      @Size(max = 200) String shopName,
      @NotNull UUID categoryId,
      @Size(max = 200) String categoryName,
      @NotBlank
          @Pattern(
              regexp = "^[a-z]{2,3}(-[A-Za-z0-9]{2,8})*$",
              message = "语言要用 BCP-47 简写，例如 zh-CN / en-US")
          String locale,
      @NotBlank
          @Pattern(
              regexp = "^[A-Z][A-Z0-9_]*$",
              message = "平台码要用大写字母开头，例如 AMAZON / SHOPIFY")
          String platformCode,
      @NotBlank String topicType,
      @Size(max = 500) String note) {}

  /**
   * 新建一个草稿版本的输入。
   *
   * <p>蓝图「输入：知识条目编辑；版本管理」里的**版本管理**：草稿可以随便改区间
   * （规则 R3：只有已发布版本参与重叠判定），发布那一刻才检查。
   *
   * <p>{@code effectiveFrom} / {@code effectiveTo} 传字符串时**必须带时区偏移量**：
   * 服务层用 {@code OffsetDateTime.parse} 解析，用 {@code Timestamp.valueOf(LocalDateTime)}
   * 会在东八区静默偏 −28800 秒 —— 这是本仓库反复踩到的坑。
   */
  public record CreateVersionInput(
      @NotNull @Positive Integer versionNo,
      @NotBlank String question,
      @NotBlank String answer,
      @NotBlank String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Size(max = 500) String note) {}

  /**
   * 发布一个草稿版本。
   *
   * <p>发布是**单向**的：之后不能撤回（V99 第四节 guard 触发器），
   * 因为 15.1.1.2 已发出的消息与 15.5.1 自动回复都按那一版答复过买家。
   */
  public record PublishVersionInput(@Size(max = 500) String note) {}

  /**
   * 向前关闭生效区间（规则 R5）。
   *
   * <p>这是已发布版本上**唯一**合法的写操作：把 {@code effectiveTo} 收紧到一个仍在未来的时刻，
   * 让接替它的版本从那一刻起独占。允许收回到 null 或往后延都会被触发器拒绝。
   */
  public record CloseVersionInput(@NotBlank String effectiveTo) {}

  /**
   * 记录一次引用。
   *
   * <p>{@code referencedBy} <b>不</b>由客户端提供：服务层用 {@code AccessService.Access.userId()}，
   * 伪造一个别人的 id 不该让引用记录冒名。{@code consumer} 说明是人工发送还是自动回复。
   */
  public record RecordReferenceInput(
      @NotBlank String consumer, @Size(max = 200) String sourceRef, @Size(max = 500) String note) {}

  // ------------------------------------------------------------ 输出

  /** 知识档案在列表与详情里的形状（不含版本正文，正文在 {@link KnowledgeVersion}）。 */
  public record KnowledgeEntry(
      UUID id,
      String topicKey,
      String title,
      UUID shopId,
      String shopName,
      UUID categoryId,
      String categoryName,
      String locale,
      String platformCode,
      String topicType,
      String status,
      /** 此刻生效的版本（规则 R4）。没有生效版本时为 null —— 这是一个**要显形**的状态。 */
      @Schema(nullable = true) KnowledgeVersion effectiveVersion,
      int versionCount,
      long referenceCount,
      String note,
      String createdAt,
      String updatedAt) {}

  /**
   * 知识版本。
   *
   * <p>{@code effectiveTo} 为 null = 这一版还没有接替者（+∞），与规则 R1 的半开区间一致。
   */
  public record KnowledgeVersion(
      UUID id,
      UUID entryId,
      int versionNo,
      String question,
      String answer,
      String status,
      String effectiveFrom,
      @Schema(nullable = true) String effectiveTo,
      @Schema(nullable = true) UUID publishedBy,
      @Schema(nullable = true) String publishedAt,
      long referenceCount,
      String note,
      String createdAt,
      String updatedAt) {}

  /** 档案详情 = 档案本体 + 全部版本（按版本号倒序）。 */
  public record KnowledgeEntryDetail(KnowledgeEntry entry, List<KnowledgeVersion> versions) {}

  /**
   * 一次引用。
   *
   * @param referencedBy 谁引用的（已认证成员 id，由服务端写入）
   * @param referencedAt 什么时候引用的（服务端 now()，不接受客户端传值）
   * @param versionNo 冗余出来的版本号：引用面板要显示「第几版」，
   *     否则每行都要回查版本表才能读懂
   */
  public record KnowledgeReference(
      UUID id,
      UUID versionId,
      int versionNo,
      UUID entryId,
      String entryTitle,
      UUID referencedBy,
      String referencedAt,
      String consumer,
      @Schema(nullable = true) String sourceRef,
      String note) {}

  /**
   * 「平台政策改版后待复核」的判定结果（蓝图粗体句的落地出口）。
   *
   * @param platformCode 改版的平台
   * @param policyChangedAt 该平台新政策的生效时刻
   * @param staleDays 当前生效那一版已经**多少天没被动过**（按它的生效起点算）
   * @param reason 判定理由（DB 词表）：{@code WRITTEN_BEFORE_CHANGE} =
   *     「当前生效版写于改版之前，且至今仍是开放区间（没有接替版本）」，
   *     即内容仍停留在旧政策口径下
   */
  public record ReviewDueEntry(
      UUID entryId,
      String topicKey,
      String title,
      UUID shopId,
      String shopName,
      UUID categoryId,
      String categoryName,
      String locale,
      String platformCode,
      String topicType,
      UUID versionId,
      int versionNo,
      String question,
      String effectiveFrom,
      long staleDays,
      String reason,
      long referenceCount) {}

  /** {@code review-due} 的整体回答：判定口径与命中条目一并返回，页面能自证它按什么查的。 */
  public record ReviewDueReport(
      String platformCode,
      String policyChangedAt,
      String criterion,
      int count,
      List<ReviewDueEntry> entries) {}

  /**
   * 组织范围（店铺 × 类目 × 语言）—— 页面上三个下拉框的**真实数据源**。
   *
   * <p>刻意做成查询而不是常量：这三个维度在 V99 里是 NOT NULL 的真实列，
   * 下拉框列出的是**本租户库里真实存在过的格子**，不是一份写死在前端的列表。
   */
  public record KnowledgeScope(
      UUID shopId, String shopName, UUID categoryId, String categoryName, String locale, long entryCount) {}

  /** 端点能力声明：让前端在渲染前就知道哪些动作存在，而不是靠试。 */
  public record KnowledgeCapabilities(
      boolean effectiveLookup,
      boolean versionManagement,
      boolean referenceRecords,
      boolean platformReview,
      List<String> topicTypes,
      List<String> consumers) {}
}
