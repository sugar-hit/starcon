package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 8.1.1.3 仓库配置的传输模型。
 *
 * <p>蓝图原文：「配置仓库行为参数：是否支持批次/序列号、拣货策略（按订单/按波次/按库位）、 默认复核比例、是否允许拆零发货、工作时段与班次。」以及关系段的红线：
 * 「**配置影响作业方式**，不是纯展示项，修改需记录变更。」
 *
 * <p>三组形状，各自对应职责里的三段：
 *
 * <ul>
 *   <li>{@link WarehouseConfig} —— 作业行为参数（批次/序列号、拣货策略、复核比例、拆零）。 这些是**作业契约**：8.3.2 拣货与 8.3.3 复核按它执行。
 *   <li>{@link ShiftRow} —— 工作时段与班次。8.1.1.2 的证据栏点名「人员花名册与排班无表 （属 8.1.1.3）」，缺口在这里补上。
 *   <li>{@link ConfigChange} —— 变更留痕，逐字段一条。蓝图那句「修改需记录变更」是硬要求。
 * </ul>
 *
 * <p>**不复用** {@link WarehouseFacilityModels} 的 {@code WarehouseKind} 之外的东西： 本模块与 8.1.1.1 / 8.1.1.2
 * 共享的只有仓库本身这个引用键。 档案字段（名称/地址/时区）不在这里 —— 那属 8.1.1.2， 「改配置」这个动作不应该有能力顺手改到地址去。
 */
public final class WarehouseConfigModels {

  private WarehouseConfigModels() {}

  // ------------------------------------------------------------ 拣货策略

  /**
   * 拣货策略（受控词表，三选一）。
   *
   * <p>蓝图点名「按订单/按波次/按库位」。这三个值是**作业契约**而不是显示用的标签： 8.3.2 按它决定任务怎么分派，所以它必须是枚举而不能是自由文本 ——
   * 「按波次」「BY_WAVE」「WAVE」三套写法在界面上看不出是同一个意思， 而下游解析器只认一种。
   *
   * <p>三者的差别说清楚，界面上才不用猜：
   *
   * <ul>
   *   <li>{@link #ORDER}：一单一任务。订单数多时任务碎片化，但责任到单。
   *   <li>{@link #WAVE}：一批一任务。同波次内的订单一起拣，路径短， 代价是「这一单谁拣的」要靠波次里的行来追。
   *   <li>{@link #LOCATION}：一库位一任务。适合货位集中度高的仓， 但订单被拆到多个任务上。
   * </ul>
   */
  public enum PickingStrategy {
    ORDER("按订单", "一单一任务：责任到单，但订单数多时任务碎片化"),
    WAVE("按波次", "一批一任务：同波次订单一起拣，路径短，但责任要靠波次内的行来追"),
    LOCATION("按库位", "一库位一任务：适合货位集中的仓，但订单会被拆到多个任务");

    private final String label;
    private final String note;

    PickingStrategy(String label, String note) {
      this.label = label;
      this.note = note;
    }

    public String label() {
      return label;
    }

    public String note() {
      return note;
    }
  }

  /** 拣货策略下拉项：代码、中文名与那句「为什么这么选」的说明。 */
  public record PickingStrategyOption(String code, String label, String note) {}

  // ------------------------------------------------------------ 配置

  /**
   * 仓库的生效配置。
   *
   * @param lotTrackingEnabled 是否支持批次追溯。关掉时收货（8.2.1.1）不录批号
   * @param serialTrackingEnabled 是否支持序列号追溯。与批次**独立** —— 批次要录批号、序列号要逐件扫，作业成本不同，压成一个布尔会让
   *     「录批号但不逐件扫」无法表达
   * @param pickingStrategy 拣货策略（作业契约，8.3.2 按它分派任务）
   * @param defaultReviewRatio 默认复核比例 0~1。0 = 不复核，1 = 全量复核。 复核是 8.3.3 的事，这里只给**默认**比例 —— 单次作业可以覆盖它，
   *     但「默认是几」必须是仓自己的属性而不是每次作业现问
   * @param allowSplitShipment 是否允许拆零发货。默认 false：拆零会破掉 「一单一包裹」这个下游默认假设（面单、装箱、运费都按整单算）
   * @param unconfigured 是否尚未配置过（warehouse_config 里没有这一行）。 为 true 时其余字段是**默认值**而不是用户填的值 —— 界面上必须能区分
   *     「这个仓的复核比例是 0」与「这个仓还没配过复核比例」
   * @param sourceConfig 读的是哪一份：当前生效值。刻意**不**给「历史档」： 作业读的是当前态，读历史只会让每个作业单多做一次解析
   * @param note 备注
   * @param updatedByMembershipId 最后一次修改者（已认证成员 id）
   * @param createdAt / updatedAt 建行与最后改动时刻
   */
  public record WarehouseConfig(
      UUID warehouseId,
      boolean lotTrackingEnabled,
      boolean serialTrackingEnabled,
      PickingStrategy pickingStrategy,
      BigDecimal defaultReviewRatio,
      boolean allowSplitShipment,
      boolean unconfigured,
      String note,
      @Schema(nullable = true) UUID updatedByMembershipId,
      @Schema(nullable = true) OffsetDateTime createdAt,
      @Schema(nullable = true) OffsetDateTime updatedAt) {}

  /**
   * 修改仓库配置的入参。
   *
   * <p>六个字段全部**必填**：本端点是「整份配置替换」而不是「逐字段补丁」。 逐字段补丁（只传改了的字段）会让「把这个字段清掉」无法表达 —— 而「允许拆零发货」从 true 改回
   * false 正是一个必须能表达的动作。 前端表单本来就是整份提交，服务层再逐条校验并给出可读错误。
   */
  public record WarehouseConfigInput(
      @jakarta.validation.constraints.NotNull Boolean lotTrackingEnabled,
      @jakarta.validation.constraints.NotNull Boolean serialTrackingEnabled,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 16)
          String pickingStrategy,
      @jakarta.validation.constraints.NotNull BigDecimal defaultReviewRatio,
      @jakarta.validation.constraints.NotNull Boolean allowSplitShipment,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 500) String note) {}

  /**
   * 配置保存结果。
   *
   * @param changeCount 本次写了多少条变更留痕。**逐字段**一条， 所以「改了 2 个字段」就是 2 —— 这个数让界面能立刻告诉用户 「本次变更已留痕 N
   *     条」，而不必让用户自己去翻历史页确认
   * @param changes 本次实际写下的留痕条目（改动过的字段才在列里）
   */
  public record WarehouseConfigSaved(
      WarehouseConfig config, int changeCount, List<ConfigChange> changes) {}

  // ------------------------------------------------------------ 班次

  /**
   * 一个班次（工作时段）。
   *
   * <p>8.1.1.2 的 {@code rosterAvailable=false} 理由写的是「人员花名册与排班没有数据表 （工作时段与班次属 8.1.1.3 配置）」——
   * 这个缺口在这里补上。
   *
   * @param startLocal / endLocal **当地墙上时刻**（{@code time}，不是 timestamptz）。 「早班
   *     09:00-18:00」是一条每天重复的规则，它本身不是某个瞬间； 具体某天的实际时刻由 {@code warehouse.timezone}（IANA ID）推出来。 刻意不用
   *     timestamptz：那样就必须回答「09:00 是哪天的 09:00」， 而班次按定义跨日期重复，这个日期没有来源
   * @param crossesMidnight 跨夜班（{@code start > end}，如 22:00 → 06:00）。 不禁止跨夜：夜班是真实存在的作业形态，拆成两条会让
   *     「这个班从 22 点到次日 6 点」在数据层就没有答案
   * @param durationMinutes 班次时长（分钟），由起止时刻算出。 跨夜班会算出大于 1440 的值（22:00→06:00 是 480 分钟）—— 界面上不该让用户自己算减法
   * @param enabled 是否排班。**停用是排班调整的出口，不是删除** —— 删掉班次会让「上周三夜班是谁上的」这个问题失去载体
   */
  public record ShiftRow(
      UUID id,
      String code,
      String name,
      LocalTime startLocal,
      LocalTime endLocal,
      boolean crossesMidnight,
      long durationMinutes,
      boolean enabled,
      String note,
      @Schema(nullable = true) UUID createdByMembershipId,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 新建班次入参。跨夜由服务端按起止时刻算出，不接受调用方传入。 */
  public record ShiftInput(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 32)
          String code,
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String name,
      @jakarta.validation.constraints.NotNull String startLocal,
      @jakarta.validation.constraints.NotNull String endLocal,
      @Schema(nullable = true) Boolean enabled,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 500) String note) {}

  /** 改班次入参。同一组字段，整份替换。 */
  public record ShiftUpdateInput(
      @jakarta.validation.constraints.NotBlank @jakarta.validation.constraints.Size(max = 64)
          String name,
      @jakarta.validation.constraints.NotNull String startLocal,
      @jakarta.validation.constraints.NotNull String endLocal,
      @jakarta.validation.constraints.NotNull Boolean enabled,
      @Schema(nullable = true) @jakarta.validation.constraints.Size(max = 500) String note) {}

  /**
   * 班次保存结果。
   *
   * @param shift 保存后的班次
   * @param changes 本次写下的留痕条目（逐字段）
   */
  public record ShiftSaved(ShiftRow shift, int changeCount, List<ConfigChange> changes) {}

  // ------------------------------------------------------------ 变更留痕

  /**
   * 一条配置变更留痕。
   *
   * <p>蓝图「修改需记录变更」。粒度是**逐字段**：一次改 3 个字段写 3 行， 而不是把整份配置快照写一行 —— 审计的真实问题是 「谁把复核比例从 0 调到 1 了」，逐行才能答；
   * 整份快照会让「改了哪几个字段」变成一个需要人肉比对的问题。
   *
   * @param scope 改的是哪一组参数：CONFIG 当前生效的行为参数 / SHIFT 某个班次的字段 / SHIFT_SCHEDULE 该班次的启用状态。
   *     「把早班停用」与「把早班改到 10 点上班」是两种不同的作业影响， 混进同一条会让复盘分不清
   * @param fieldName 字段名（规范化标识符，不是展示名）
   * @param oldValue 改前的值。**null = 之前没有这个值**（新建）； 「之前是空」与「之前没有」必须能分开，所以用 null 而不是空串
   * @param newValue 改后的值。null = 之后没有了
   * @param changedByMembershipId 操作人：已认证成员 id。 **不接受请求参数传入** —— 调用方传 id 等于允许把变更记到别人头上
   * @param changedAt 变更时刻
   */
  public record ConfigChange(
      UUID id,
      String scope,
      @Schema(nullable = true) UUID shiftId,
      @Schema(nullable = true) String shiftCode,
      String fieldName,
      @Schema(nullable = true) String oldValue,
      @Schema(nullable = true) String newValue,
      String note,
      UUID changedByMembershipId,
      OffsetDateTime changedAt) {}

  /** 变更历史查询条件。真分页，服务端有上限。 */
  public record ChangeQuery(
      @Schema(nullable = true) String scope,
      @Schema(nullable = true) UUID shiftId,
      @Schema(nullable = true) Integer page,
      @Schema(nullable = true) Integer size) {}

  /**
   * 变更历史一页。
   *
   * @param rows 当前页（按 {@code changedAt DESC, id} 定序 —— 只按时间排会在同毫秒内 不确定，而没有确定次序的分页会漏行或重复）
   * @param total 满足筛选的总条数
   */
  public record ChangePage(List<ConfigChange> rows, long total, int page, int size) {}

  // ------------------------------------------------------------ 配置全貌

  /**
   * 配置页一次返回的形状。
   *
   * @param warehouseCode / warehouseName / timezone 仓库标识与时区。 时区在这里是**必需的**：班次存的是当地墙上时刻，
   *     界面上要把「09:00」标成「09:00 Asia/Shanghai」而不是让前端猜
   * @param archived 仓库是否已归档。归档是终态 —— 归档的仓不允许再改配置 （与 8.1.1.2 同一纪律），所以界面上整个编辑入口要关掉而不是提交了再报错
   * @param editable 是否允许改。归档仓恒为 false
   */
  public record ConfigOverview(
      String warehouseCode,
      String warehouseName,
      String timezone,
      String warehouseStatus,
      boolean archived,
      boolean editable,
      WarehouseConfig config,
      List<ShiftRow> shifts,
      ChangePage changes,
      List<ConfigImpact> impacts,
      String note) {}

  /**
   * 配置对下游作业的影响声明。
   *
   * <p>蓝图说「输出：生效配置；影响 8.3 作业行为」，但 8.3.2 拣货与 8.3.3 复核 **尚未交付**（本服务还没有 picking_task / review_task 表）。
   * 这里逐条报出「哪一项现在真的会生效、哪一项要等下游接上」—— 而不是笼统写一句「影响 8.3」。写「影响 8.3」会让人以为拣货已经按这个策略在跑。
   *
   * @param available 当前真的会生效
   * @param ownerModule 未生效时归哪个模块
   * @param note 说明
   */
  public record ConfigImpact(
      String item, String label, boolean available, String ownerModule, String note) {}

  /** 下拉项与说明。 */
  public record ConfigOptions(
      List<PickingStrategyOption> strategies,
      List<ShiftOption> shifts,
      List<WarehouseConfigScopeOption> scopes,
      String reviewRatioNote,
      String splitShipmentNote,
      String shiftNote) {}

  /** 班次下拉项（用于变更历史的筛选）。 */
  public record ShiftOption(UUID id, String code, String name, boolean enabled) {}

  /**
   * 留痕范围下拉项。
   *
   * <p>名字带模块前缀不是啰嗦：springdoc 用 record 的**简单类名**做 {@code components.schemas} 的键， 同一个服务里两个同名 record
   * 会**后者覆盖前者**。本仓库已经因为 {@code ScopeOption}（税则）与 {@code
   * StatusOption}（预占/仓库设施）各出现过一次，已改名的这批前缀就是为了不再撞。
   */
  public record WarehouseConfigScopeOption(String code, String label) {}
}
