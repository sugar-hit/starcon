package com.starcon.crm;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 15.3.2.2 接触记录的传输模型。
 *
 * <p>蓝图 15 卷 15.3.2.2 原话：
 *
 * <blockquote>
 * 职责：记录与客户的所有接触（客服通话、消息、邮件、营销触达、售后沟通），可由各模块自动写入，支持时间线检索。
 * 输入：各模块接触事件；手工补录。
 * 输出：接触时间线。
 * 关系：上游 = 15.1、11 售后、15.5.2；下游 = 15.3.1.2。
 * <b>接触记录是自动化的豁免依据</b>——曾明确表示"不要再联系我"的客户，营销触达需排除。
 * </blockquote>
 *
 * <p><b>本文件最重要的三件事</b>：
 *
 * <ol>
 *   <li><b>接触类型照蓝图点名的五类归位</b>（{@link CustomerContactType}）：
 *       客服通话 / 消息 / 邮件 / 营销触达 / 售后沟通 —— 加上 {@code OPT_OUT}
 *       （退订动作本身的记录）与 {@code OTHER}。</li>
 *   <li><b>来源可分</b>（{@link CustomerContactOrigin} = MANUAL / AUTO）：
 *       手工补录与上游模块自动写入是**两类记录**，自动写入必须带
 *       {@code sourceModule} + {@code sourceRef}（幂等键与追溯依据）。</li>
 *   <li><b>退订是独立的一等状态</b>，不是接触记录上的一个布尔：
 *       {@link CrmCustomerOptOutRow}（当前状态）+ {@link CrmCustomerOptOutEventRow}
 *       （只追加的审计流水）。退订**只能**被显式动作改掉。</li>
 * </ol>
 *
 * <p><b>命名</b>：统一用 {@code CrmCustomerContact*} / {@code CrmCustomerOptOut*} 前缀。
 * 全仓 grep 确认与既有类型零重叠 —— 契约被 codegen 合并进一个扁平文件、
 * 同名 key 后者覆盖前者且不报冲突，故不按前缀扫而是逐个名字精确比对。
 */
public final class CustomerContactModels {

  private CustomerContactModels() {}

  // ================================================================ 枚举

  /**
   * 接触类型 —— 照蓝图点名的五类归位。
   *
   * <p>🔴 {@link #MARKETING}（营销触达）当前**只可能**由人工补录或「明知违规仍要留痕」
   * 产生：真正的营销触达由 **15.5.2.1 关怀与催办**发出，而它**未交付**。
   */
  public enum CustomerContactType {
    CALL("客服通话"),
    MESSAGE("消息"),
    EMAIL("邮件"),
    MARKETING("营销触达"),
    AFTERSALES("售后沟通"),
    OPT_OUT("退订 / 免打扰"),
    OTHER("其它接触");

    private final String label;

    CustomerContactType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 接触方向：客户来的 / 我们发的。 */
  public enum CustomerContactDirection {
    INBOUND("客户发起"),
    OUTBOUND("我们发起");

    private final String label;

    CustomerContactDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 记录来源：手工补录 / 上游模块自动写入。 */
  public enum CustomerContactOrigin {
    MANUAL("手工补录"),
    AUTO("自动写入");

    private final String label;

    CustomerContactOrigin(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 合规状态。
   *
   * <p>🔴 客户处于生效退订时，**退订之后**发生的营销触达必须显式标成
   * {@link #VIOLATION} 才能落库（DB 触发器强制）—— 豁免依据要真的拦得住，
   * 而不是一条可以绕过的备注。
   */
  public enum CustomerContactCompliance {
    COMPLIANT("合规"),
    VIOLATION("违反退订豁免");

    private final String label;

    CustomerContactCompliance(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /**
   * 退订范围。
   *
   * <p>{@link #ALL} = 不要再联系我（**全部**触达都排除）；
   * {@link #MARKETING} = 只排除营销触达（其它客服沟通照常）。
   */
  public enum CustomerOptOutScope {
    ALL("全部触达"),
    MARKETING("仅营销触达");

    private final String label;

    CustomerOptOutScope(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 退订状态：生效中 / 已被显式撤销。 */
  public enum CustomerOptOutState {
    ACTIVE("生效中"),
    REVOKED("已撤销");

    private final String label;

    CustomerOptOutState(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  /** 退订审计事件类型：登记退订 / 显式撤销。 */
  public enum CustomerOptOutEventType {
    GRANTED("登记退订"),
    REVOKED("撤销退订");

    private final String label;

    CustomerOptOutEventType(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ================================================================ 入参

  /**
   * 手工补录一条接触记录。
   *
   * <p>⚠️ {@code contactType} **不含** {@code OPT_OUT}：退订不是「一条接触」，
   * 它是一个**合规状态**，只能由退订端点（显式动作）改。
   * 这样「退订不可被后续的普通接触记录悄悄覆盖或撤销」在模型层就已经成立。
   */
  public record CrmCustomerContactInput(
      @NotBlank @Size(max = 128) String customerRef,
      @Schema(nullable = true) @Size(max = 200) String customerName,
      @NotBlank String contactType,
      @NotBlank String direction,
      @NotBlank String occurredAt,
      @Schema(nullable = true) @Size(max = 400) String subject,
      @Schema(nullable = true) String summary,
      @Schema(nullable = true) BigDecimal durationSeconds,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) @Size(max = 200) String shopName,
      @Schema(nullable = true) @Size(max = 32) String platformCode,
      @Schema(nullable = true) @Size(max = 64) String orderReference,
      @Schema(nullable = true) @Size(max = 32) String sourceModule,
      @Schema(nullable = true) @Size(max = 128) String sourceRef,
      @Schema(nullable = true) @Size(max = 64) String sourceNo,
      @Schema(nullable = true) Map<String, Object> detail,
      @Schema(nullable = true) String complianceState) {}

  /**
   * 上游模块自动写入一条接触记录（幂等）。
   *
   * <p>🔴 {@code sourceModule} + {@code sourceRef} 构成**幂等键**：
   * 同一条上游记录重复写入只会有**一条**接触记录（重试与平台重复推送不重复记账）。
   */
  public record CrmCustomerContactIngestInput(
      @NotBlank @Size(max = 128) String customerRef,
      @Schema(nullable = true) @Size(max = 200) String customerName,
      @NotBlank String contactType,
      @NotBlank String direction,
      @NotBlank String occurredAt,
      @Schema(nullable = true) @Size(max = 400) String subject,
      @Schema(nullable = true) String summary,
      @Schema(nullable = true) BigDecimal durationSeconds,
      @NotBlank @Size(max = 32) String sourceModule,
      @NotBlank @Size(max = 128) String sourceRef,
      @Schema(nullable = true) @Size(max = 64) String sourceNo,
      @Schema(nullable = true) @Size(max = 64) String sourceLabel,
      @Schema(nullable = true) UUID shopId,
      @Schema(nullable = true) @Size(max = 200) String shopName,
      @Schema(nullable = true) @Size(max = 32) String platformCode,
      @Schema(nullable = true) @Size(max = 64) String orderReference,
      @Schema(nullable = true) Map<String, Object> detail,
      @Schema(nullable = true) String complianceState) {}

  /**
   * 登记退订 / 免打扰。
   *
   * <p>🔴 {@code reason} **必填**：没有依据的退订没法复核，也没法纠正。
   */
  public record CrmCustomerOptOutInput(
      @NotBlank @Size(max = 128) String customerRef,
      @Schema(nullable = true) @Size(max = 200) String customerName,
      @NotBlank String scope,
      @NotBlank @Size(max = 600) String reason,
      @Schema(nullable = true) Map<String, Object> evidence) {}

  /**
   * 撤销退订。
   *
   * <p>🔴 {@code reason} **必填**：撤销也要留痕 —— 没有理由的撤销，
   * 下一次就没人说得清「为什么又给他发营销邮件了」。
   */
  public record CrmCustomerOptOutRevokeInput(@NotBlank @Size(max = 600) String reason) {}

  // ================================================================ 出参

  /**
   * 能力维度：每一维都要说清「有没有、依据是什么、缺了会怎样」。
   *
   * <p>字段名与兄弟模块一致：{@code dimension / description / available / source /
   * ownerModule / impact}。
   */
  public record CrmCustomerContactCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  /**
   * 能力声明：前端在渲染前就知道哪些动作存在，而不是靠试。
   *
   * @param exemptionNote 🔴 豁免依据的说明（退订怎么判、谁能改、怎么留痕）
   * @param actionPolicy 🔴 明确声明「本模块不发送任何触达」
   */
  public record CrmCustomerContactCapabilities(
      List<CrmCustomerContactCapabilityDimension> dimensions,
      List<String> contactTypes,
      List<String> directions,
      List<String> origins,
      List<String> optOutScopes,
      List<String> optOutStates,
      String exemptionNote,
      String actionPolicy,
      String boundaryNote,
      String highlight) {}

  /**
   * 一条接触记录。
   *
   * @param durationSeconds 通话时长（秒）—— 服务端给什么前端显示什么，前端不做算术
   * @param sourceModule 上游模块号（自动写入时必填）：这条接触是哪来的
   */
  public record CrmCustomerContactRow(
      UUID id,
      String contactNo,
      String customerRef,
      String customerName,
      String contactType,
      String contactTypeLabel,
      String direction,
      String directionLabel,
      String origin,
      String originLabel,
      String sourceModule,
      String sourceRef,
      String sourceNo,
      String sourceLabel,
      @Schema(nullable = true) UUID shopId,
      String shopName,
      String platformCode,
      String orderReference,
      String subject,
      String summary,
      @Schema(nullable = true) BigDecimal durationSeconds,
      String occurredAt,
      @Schema(nullable = true) UUID operatorId,
      Map<String, Object> detail,
      String complianceState,
      String complianceStateLabel,
      UUID createdBy,
      String createdAt,
      String updatedAt) {}

  /** 接触记录分页（真分页：总数与页数都从真实查询来）。 */
  public record CrmCustomerContactPage(
      List<CrmCustomerContactRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 接触时间线（蓝图输出句的第一项）。
   *
   * @param optOut 该客户当前的退订判定（**豁免依据**：营销触达前先看它）
   */
  public record CrmCustomerTimelinePage(
      String customerRef,
      String customerName,
      CrmCustomerOptOutCheck optOut,
      List<CrmCustomerContactRow> items,
      int page,
      int size,
      long totalElements,
      int totalPages) {}

  /**
   * 退订记录（一段退订期）。
   *
   * @param reason 🔴 登记依据（必填）：他凭什么说不要再联系
   * @param evidence 依据快照（在哪条消息里说的、原文摘要…）
   */
  public record CrmCustomerOptOutRow(
      UUID id,
      String customerRef,
      String customerName,
      String scope,
      String scopeLabel,
      String state,
      String stateLabel,
      String reason,
      Map<String, Object> evidence,
      UUID recordedBy,
      String recordedAt,
      @Schema(nullable = true) UUID revokedBy,
      @Schema(nullable = true) String revokedAt,
      String revokeReason,
      String createdAt,
      String updatedAt) {}

  /** 退订记录分页（真分页）。 */
  public record CrmCustomerOptOutPage(
      List<CrmCustomerOptOutRow> items, int page, int size, long totalElements, int totalPages) {}

  /** 退订审计流水的一行（只追加）。 */
  public record CrmCustomerOptOutEventRow(
      UUID id,
      UUID optOutId,
      String customerRef,
      String eventType,
      String eventTypeLabel,
      UUID actorId,
      String occurredAt,
      String reason,
      Map<String, Object> detail,
      String createdAt) {}

  /** 退订审计流水分页（真分页）。 */
  public record CrmCustomerOptOutEventPage(
      List<CrmCustomerOptOutEventRow> items, int page, int size, long totalElements, int totalPages) {}

  /**
   * 退订判定 —— <b>可查询的豁免依据</b>。
   *
   * <p>🔴 这是 15.5.2 将来要消费的东西：「这个客户现在是不是还能营销」。
   * {@code suppressed} = 存在生效中的退订（全部触达都被排除）；
   * {@code marketingSuppressed} = 生效中的退订覆盖营销触达（ALL 或 MARKETING 范围）。
   */
  public record CrmCustomerOptOutCheck(
      String customerRef,
      String customerName,
      boolean suppressed,
      boolean marketingSuppressed,
      String scope,
      String scopeLabel,
      @Schema(nullable = true) UUID optOutId,
      String reason,
      @Schema(nullable = true) String recordedAt,
      @Schema(nullable = true) UUID recordedBy,
      String note) {}

  /**
   * 批量退订判定（营销触达前对一批人做豁免检查）。
   *
   * @param checkedCount 本次判定的人数
   * @param suppressedCount 其中被豁免（不可营销）的人数
   */
  public record CrmCustomerOptOutCheckResult(
      int checkedCount, int suppressedCount, List<CrmCustomerOptOutCheck> items, String note) {}

  /**
   * 自动写入的结果。
   *
   * @param created true = 新写入；false = **幂等命中**（这条上游记录已有接触记录）
   * @param note 幂等命中时说清「没有重复记账」，别让人以为写失败了
   */
  public record CrmCustomerContactIngestResult(
      CrmCustomerContactRow contact, boolean created, String note) {}
}
