package com.starcon.channel;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 10.1.1.1 承运商列表 的传输模型。
 *
 * <p>载荷一律用具名 {@code record} 而不是 {@code Map<String,Object>}：Map 的值类型会被
 * springdoc 推成 object，OpenAPI codegen 出来是空对象 {@code {}}（已踩过）。
 *
 * <p>🔴 字段归属是这个模块最要紧的一件事，蓝图加粗句是
 * **「承运商是往来单位的一种角色，不在此处重复建档」**。所以下面每一个
 * 带「上游」字样的字段都是 catalog 3.6.1.1/3.6.1.2 的<b>只读快照</b>：
 * 它们由同步写回，<b>任何写请求都不接受它们</b>。
 * 本模块的主权字段只有 {@code carrierCategory} / {@code serviceTier} /
 * {@code secretRef} / {@code coverageCountries} / {@code businessTypes} /
 * {@code note} —— 也就是「物流侧的身份没有、但物流侧需要的东西」。
 */
public final class CarrierModels {
  private CarrierModels() {}

  // ================================================================ 能力声明

  /**
   * 一个能力维度。
   *
   * <p>{@code available=false} 时必须同时给出 {@code ownerModule}（缺口归属模块）与
   * {@code impact}（缺了会怎样）。**不用空列表或 0 冒充「这一项没有数据」**。
   */
  public record CarrierCapabilityDimension(
      String dimension,
      String description,
      boolean available,
      String source,
      String ownerModule,
      String impact) {}

  public record CarrierCapabilities(
      List<CarrierCapabilityDimension> dimensions,
      List<String> carrierCategories,
      List<String> serviceTiers,
      List<String> businessTypes,
      List<String> coverageCountryCodes,
      List<String> readinessCodes,
      List<String> availabilityCodes,
      String upstreamNote,
      String boundaryNote) {}

  // ================================================================ 承运商档案

  /**
   * 承运商列表的一行。
   *
   * <p>「上游快照」段的字段来自 catalog，读时展示、**不可经本模块编辑**
   * （改它们要回 /partners）。「物流侧」段是本模块的主权。
   *
   * <p>{@code availability} 是<b>算出来的</b>，不是存下来的一个下拉框：
   * 见 {@link CarrierAvailability}。
   */
  public record CarrierRow(
      UUID id,
      // === 上游 catalog 只读快照 ===
      UUID roleId,
      UUID partyId,
      String partyCode,
      String partyName,
      String partyStatus,
      boolean partyUsable,
      String roleStatus,
      String interfaceType,
      @Schema(nullable = true) String accountRef,
      List<String> serviceCapabilities,
      // === 物流侧（本模块主权）===
      @Schema(nullable = true) String carrierCategory,
      @Schema(nullable = true) String serviceTier,
      /** 凭据的**密钥管理器引用**；绝不返回凭据本身（那里根本没有凭据）。 */
      @Schema(nullable = true) String secretRef,
      /** 掩码，形如 {@code sf_live_****3f2a}。界面显示的是它，不是引用。 */
      @Schema(nullable = true) String secretMasked,
      List<String> coverageCountries,
      int coverageCount,
      List<String> businessTypes,
      int businessCount,
      // === 判定 ===
      String readiness,
      String availability,
      String availabilityMessage,
      String note,
      long version,
      @Schema(nullable = true) OffsetDateTime syncedAt,
      OffsetDateTime updatedAt) {}

  /**
   * 列表分页信封。
   *
   * <p>{@code summary} 是<b>不受分页影响</b>的统计（覆盖当前筛选条件下的全集，
   * 与 {@code limit/offset} 无关）：界面上把它放在分页旁边，运营才知道
   * 「这一页 20 条里 3 条可用」之外还有多少。
   */
  public record CarrierPage(
      List<CarrierRow> items, int page, int size, long total, int totalPages, CarrierSummary summary) {}

  /** 筛选结果上的真聚合（独立查询，不随 limit/offset 变）。 */
  public record CarrierSummary(
      long total,
      long available,
      long manualOnly,
      long partyBlocked,
      long notRegistered,
      long noCredential,
      long noCoverage,
      long noBusinessScope) {}

  /** 概览：全量口径的分类 / 时效分布，同样不受任何筛选影响。 */
  public record CarrierCategoryCount(String category, String label, long count) {}

  public record CarrierOverview(
      CarrierSummary summary,
      List<CarrierCategoryCount> byCategory,
      List<CarrierCategoryCount> byTier,
      String note) {}

  // ================================================================ 写入口

  /**
   * 登记 / 改一个承运商的**物流侧档案**。
   *
   * <p>刻意<b>没有</b>主体身份字段（名称 / 编码 / 公司类型 / 国家 / 联系方式），
   * 也没有接口类型与账号 —— 那些是 catalog 的主权，出现在这里就等于开了
   * 「同一个承运商在两处各改一次」的口子。
   *
   * <p>{@code secretRef} 只接受密钥管理器引用（DB 层 CHECK 钉死
   * {@code secret://} / {@code kv://} / {@code vault://} / {@code external-secrets://}），
   * 真实 token 会在写库那一刻被拒。
   */
  public record SaveCarrierRequest(
      @NotBlank @Size(max = 32) String carrierCategory,
      @NotBlank @Size(max = 32) String serviceTier,
      @Schema(nullable = true) @Size(max = 200) String secretRef,
      @Schema(nullable = true) @Size(max = 64) String secretMasked,
      @NotNull @Size(min = 1, max = 200) List<@Size(min = 2, max = 2) String> coverageCountries,
      @NotNull @Size(min = 1, max = 10) List<@Size(max = 32) String> businessTypes,
      @Size(max = 2000) String note,
      @PositiveOrZero long version) {}

  /** 同步结果：如实报告看到了多少、建了多少、刷新了多少、跳过了多少。 */
  public record SyncCarriersResult(
      int seen,
      int created,
      int refreshed,
      int skippedNoCarrierRole,
      OffsetDateTime syncedAt,
      String note) {}

  // ================================================================ 可用性判定

  /**
   * 可用性判定的<b>单一实现</b>（列表行 / 详情 / 概览 / 写后回读都调它）。
   *
   * <p>蓝图输出句要「承运商档案 + 可用性状态」。这条状态必须有**可判定的依据**，
   * 所以它是三项真实输入的函数，而不是管理员手填的一个下拉框：
   *
   * <ol>
   *   <li>上游主体是否可用（catalog 的判定：停用 / 拉黑 / 已删除）；
   *   <li>物流侧档案是否齐备（DB 层 {@code readiness}，由凭据引用 / 覆盖国家 /
   *       业务类型三项算出，且由触发器重算比对，改不动）；
   *   <li>接口类型是不是只能人工处理（{@code WEB_PORTAL} / {@code EMAIL}）。
   * </ol>
   *
   * <p>顺序有讲究：**主体不可用排第一**（上游说这个对手方已经不能做生意了，
   * 配置得再齐也没用）；配置缺口第二（那才是本模块自己能解决的）；
   * {@code MANUAL_ONLY} 最后（它不是缺陷，是一种真实存在的对接方式）。
   */
  public final class CarrierAvailability {
    private CarrierAvailability() {}

    /** 可用于系统自动对接。 */
    public static final String AVAILABLE = "AVAILABLE";
    /** 配置齐备，但接口类型只能人工处理（平台下单 / 邮件往来）。 */
    public static final String MANUAL_ONLY = "MANUAL_ONLY";
    /** 上游主体不可用。 */
    public static final String PARTY_BLOCKED = "PARTY_BLOCKED";

    /** 返回对外的可用性状态。输入全部来自行上的真实读数。 */
    public static String of(boolean partyUsable, String readiness, String interfaceType) {
      if (!partyUsable) {
        return PARTY_BLOCKED;
      }
      String r = readiness == null ? "NOT_REGISTERED" : readiness;
      if (!"READY".equals(r)) {
        return r;
      }
      if ("WEB_PORTAL".equals(interfaceType) || "EMAIL".equals(interfaceType)) {
        return MANUAL_ONLY;
      }
      return AVAILABLE;
    }

    /** 给界面看的一句话理由；与状态一一对应，不猜。 */
    public static String message(String availability, boolean partyUsable) {
      if (!partyUsable) {
        return "上游往来单位已停用 / 拉黑 / 删除，不能作为承运商使用（改状态请回 /partners）";
      }
      return switch (availability) {
        case AVAILABLE -> "可用于系统自动对接";
        case MANUAL_ONLY -> "配置齐备，但接口类型只能人工处理（平台下单 / 邮件往来），不会自动取轨迹";
        case "NOT_REGISTERED" -> "还没有登记物流侧档案（承运商类别与时效等级为空）";
        case "NO_CREDENTIAL" -> "没有登记凭据引用，系统对接拿不到鉴权材料";
        case "NO_COVERAGE" -> "没有登记覆盖国家，一个国家都送不到";
        case "NO_BUSINESS_SCOPE" -> "没有声明支持的业务类型（自发货 / 退货 / FBA 入仓）";
        default -> availability;
      };
    }
  }
}
