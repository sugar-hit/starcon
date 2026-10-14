package com.starcon.warehouse;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 7.3.2.1 序列号的传输模型。
 *
 * <p>蓝图原话：「序列号级库存管理：录入/扫码、状态（在库/已售/在途/维修/报废）、当前位置、 绑定 SKU 与批次，出入库时强校验数量与序列号条数一致」「输出：序列号档案 + 位置流转历史
 * + 校验错误」 「<b>序列号校验是一致性硬约束</b>：数量为 1 时必须有且仅有 1 个序列号，不允许『大概齐』」。
 *
 * <p><b>三件输出对应三组类型</b>，读的时候别混：
 *
 * <ul>
 *   <li>{@link SerialProfile} 序列号档案 —— <b>现在</b>这一件是什么状态、在哪个库位、绑哪个批次。 它是投影，可变（货会挪、状态会变）。
 *   <li>{@link SerialMovementRow} 位置流转历史 —— append-only 的事实，每行一次真实的挪动/换态。 档案改一次这里就多一行，反过来删不掉。
 *   <li>{@link SerialIssue} 校验错误 —— 出入库强校验失败时**逐条**告诉你是哪个号、哪一条不对，
 *       而不是只回一个「校验不通过」。只回一句失败等于把定位的活儿全推给操作员， 而他手里正拿着上百个号。
 * </ul>
 *
 * <p><b>数量口径（全模块唯一一套）</b>：{@code declaredQuantity} 是业务声明的数量， {@code serialCount}
 * 是实际序列号条数。两者必须**完全相等**，不相等时整个操作不落库 —— 见 V58 的 {@code CHECK(serial_count = declared_quantity)}
 * 与延迟约束触发器。 「大概齐」在这里不是错误提示，是一个连提交都提交不了的状态。
 *
 * <p>枚举类名一律带 {@code Serial} 前缀：四份 OpenAPI spec 的 schema 会被 {@code
 * tools/codegen/catalog-contracts.py} 拍平进同一个 TS 命名空间，不加前缀必然与 catalog / channel / identity 的类型撞名。
 */
public final class InventorySerialModels {

  private InventorySerialModels() {}

  // ------------------------------------------------------------ 枚举

  /**
   * 序列号状态。
   *
   * <p>取值集合与 V58 的 CHECK 完全一致（DB 是最终裁决方，这里是给界面用的中文标签）。
   *
   * <p><b>报废是终态</b>，由 {@link #canMoveTo} 显式表达，并由 V58 的 {@code guard_inventory_serial_identity}
   * 触发器在数据库层兜住 —— 应用层和数据库层 各有一份，但它们表达的是同一条不可逆的业务决定，不是两套可各自漂移的规则。
   */
  public enum SerialStatus {
    IN_STOCK("在库"),
    SOLD("已售"),
    IN_TRANSIT("在途"),
    REPAIR("维修"),
    SCRAPPED("报废");

    private final String label;

    SerialStatus(String label) {
      this.label = label;
    }

    /** 中文标签。界面上用；契约与库里仍然是 ASCII 码值。 */
    public String label() {
      return label;
    }

    /**
     * 状态机（唯一一处，别处再写一遍就会算错）。
     *
     * <p>允许的流转刻意是**闭世界**：没列出来的组合一律拒绝。写「没列出来就拒绝」而不是 「没列出来就允许」，是因为前者漏一条只是少一种用法，后者漏一条就是货账两岔。
     *
     * <p>各条的理由：
     *
     * <ul>
     *   <li>在库 → 已售/在途/维修/报废：这是现场真会发生的四种去向。
     *   <li>在途 → 在库：到货入库。→ 报废：发运途中丢件或损毁。
     *   <li>已售 → 在库：售后退货/换新把货收回来。→ 报废：客户退回的损坏件。
     *   <li>维修 → 在库：修好了放回原库位。→ 报废：修不好的判废。
     *   <li>报废 → <b>无</b>：终态。见类注释。
     * </ul>
     */
    public boolean canMoveTo(SerialStatus next) {
      if (next == null || this == next) {
        return false;
      }
      return switch (this) {
        case IN_STOCK -> next == SOLD || next == IN_TRANSIT || next == REPAIR || next == SCRAPPED;
        case IN_TRANSIT, SOLD, REPAIR -> next == IN_STOCK || next == SCRAPPED;
        case SCRAPPED -> false;
      };
    }

    /** 解析：库值/请求值 → 枚举。非法值给出可读原因，而不是抛一句 NumberFormatException。 */
    public static SerialStatus parse(String raw) {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("序列号状态不能为空");
      }
      String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
      for (SerialStatus s : values()) {
        if (s.name().equals(value)) {
          return s;
        }
      }
      throw new IllegalArgumentException("不支持的序列号状态：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 流转种类。
   *
   * <p>五个取值各自对应现场的一种真实动作，没有一个是「其它」兜底： 有兜底项就等于允许把说不清的动作塞进去，而说不清的历史在追溯时等于没有历史。
   *
   * <p>{@link #REBIND}（批次改绑）单列而不是并进状态流转：改批号时状态通常没变， 并进去就会攒出一堆「状态没变的状态流转」，读历史的人得逐行分辨哪行到底改了什么。
   */
  public enum SerialMovementKind {
    INBOUND("入库"),
    OUTBOUND("出库"),
    RELOCATION("库位转移"),
    STATUS_CHANGE("状态流转"),
    REBIND("批次改绑");

    private final String label;

    SerialMovementKind(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static SerialMovementKind parse(String raw) {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("流转种类不能为空");
      }
      String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
      for (SerialMovementKind k : values()) {
        if (k.name().equals(value)) {
          return k;
        }
      }
      throw new IllegalArgumentException("不支持的流转种类：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
  }

  /**
   * 操作方向。
   *
   * <p>与 {@link SerialMovementKind} 形似但不是一回事：方向是「这次业务要干什么」 （收 / 发 / 挪 / 改态 / 改绑），流转种类是「每个号实际发生了什么」。
   * 本模块里两者一一对应，但它们回答的问题不同：方向决定校验规则（出库必须可售、在库）， 流转种类决定历史读起来是什么样。合成一个字段会让「一次操作里有的号换了状态、
   * 有的号只挪了位」这种混合场景没法表达。
   */
  public enum SerialMovementDirection {
    INBOUND("入库"),
    OUTBOUND("出库"),
    RELOCATION("库位转移"),
    STATUS_CHANGE("状态流转"),
    REBIND("批次改绑");

    private final String label;

    SerialMovementDirection(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }

    public static SerialMovementDirection parse(String raw) {
      if (raw == null || raw.isBlank()) {
        throw new IllegalArgumentException("操作方向不能为空");
      }
      String value = raw.trim().toUpperCase(java.util.Locale.ROOT);
      for (SerialMovementDirection d : values()) {
        if (d.name().equals(value)) {
          return d;
        }
      }
      throw new IllegalArgumentException("不支持的操作方向：" + raw + "，可选：" + String.join("、", names()));
    }

    public static List<String> names() {
      return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }

    /** 该方向对应的流转种类。 */
    public SerialMovementKind kind() {
      return switch (this) {
        case INBOUND -> SerialMovementKind.INBOUND;
        case OUTBOUND -> SerialMovementKind.OUTBOUND;
        case RELOCATION -> SerialMovementKind.RELOCATION;
        case STATUS_CHANGE -> SerialMovementKind.STATUS_CHANGE;
        case REBIND -> SerialMovementKind.REBIND;
      };
    }
  }

  /**
   * 校验错误码。
   *
   * <p>做成枚举而不是自由文本，是为了让前端能**按错误分流**： {@link #SERIAL_COUNT_MISMATCH} 要整单退回并重新扫码， {@link
   * #STATUS_NOT_ALLOWED} 只需要把那几个号挑出来单独处理。 只回一句中文的话，前端只能把所有错误画成同一个红框。
   *
   * <p>每条错误都带 {@code serialNumber}：出错的是**哪一个号**必须当场说清， 让人拿着上百个号去猜是哪一个检查错了。
   */
  public enum SerialIssueCode {
    /** 声明数量带小数。序列号是离散件，「1.5 台带序列号的机器」物理上不存在。 */
    QUANTITY_NOT_INTEGER("数量必须是不带小数的整数"),
    /** 声明数量为负。 */
    QUANTITY_NEGATIVE("数量不能为负"),
    /** 序列号条数与数量不一致 —— 蓝图点名的硬约束，不允许「大概齐」。 */
    SERIAL_COUNT_MISMATCH("序列号条数必须与数量完全一致"),
    /** 同一次操作里同一个号出现了两次。 */
    SERIAL_DUPLICATED("同一个序列号在本次操作里出现了多次"),
    /** 号不存在（出库时）。 */
    SERIAL_NOT_FOUND("序列号不存在"),
    /** 号已存在（入库建档时撞号）。 */
    SERIAL_EXISTS("序列号已存在"),
    /** 号绑的 SKU 与本次操作的 SKU 不一致。 */
    SKU_MISMATCH("序列号绑定的 SKU 与本次操作不一致"),
    /** 号已绑在另一个批次上，本次要绑的不是它。 */
    LOT_MISMATCH("序列号已绑定其它批次"),
    /** 当前状态不允许这个方向（在库才能出库、报废的号不能再动）。 */
    STATUS_NOT_ALLOWED("当前状态不允许此操作"),
    /** 非法状态流转。 */
    TRANSITION_NOT_ALLOWED("不允许的状态流转"),
    /** 号不在指定库位（出库/挪位时的位置校验）。 */
    LOCATION_MISMATCH("序列号不在指定库位"),
    /** 该方向要求带库位但没带。 */
    LOCATION_REQUIRED("该操作必须指定库位"),
    /** 该方向不允许带库位。 */
    LOCATION_NOT_ALLOWED("该操作不允许指定库位"),
    /** 序列号列表里有空串。 */
    SERIAL_REQUIRED("序列号不能为空"),
    /**
     * 本次操作不会改变这个号的任何东西（已在同一库位、同一状态、同一批次）。
     *
     * <p>单列一个错误码而不是复用 LOCATION_MISMATCH：前者是「操作本身没意义」， 后者是「操作有意义但号不在你以为的位置」。混成一条，操作员会去库位里找问题，
     * 而真正的原因是他自己把同一个号扫了两遍。
     */
    NO_CHANGE("本次操作不会改变任何东西");

    private final String label;

    SerialIssueCode(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  // ------------------------------------------------------------ 输入

  /**
   * 位置流转历史查询。
   *
   * @param serialId 按序列号查（「这个号的时间线」）
   * @param operationId 按操作头查（「这一单动了哪些号」）
   * @param skuId 按 SKU 查
   * @param kind 按流转种类查
   * @param refType / refId 按来源单据回查
   * @param keyword 序列号 / 来源库位 / 目标库位 / 批次号 模糊匹配
   */
  public record SerialMovementQuery(
      @Schema(nullable = true) UUID serialId,
      @Schema(nullable = true) UUID operationId,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String kind,
      @Schema(nullable = true) String refType,
      @Schema(nullable = true) UUID refId,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true, defaultValue = "0") Integer page,
      @Schema(nullable = true, defaultValue = "50") Integer size) {}

  /** 出入库操作头查询。 */
  public record SerialOperationQuery(
      @Schema(nullable = true) String direction,
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) String refType,
      @Schema(nullable = true) UUID refId,
      @Schema(nullable = true, defaultValue = "0") Integer page,
      @Schema(nullable = true, defaultValue = "50") Integer size) {}

  /**
   * 序列号档案列表查询。
   *
   * @param skuId 按 SKU 筛
   * @param warehouseId 按仓库筛 —— 序列号本身不带 warehouse（位置才有）， 所以这一项是「当前所在仓库」，实现为对 {@code
   *     warehouse_location} 的半连接。 已售/在途/报废的号没有库位，也就**不属于任何仓库**：传了仓库筛选它们就不出现。 这是有意的 ——
   *     「上海仓现在有哪些号」不该列出一堆已经卖掉的号。
   * @param locationId 按库位筛
   * @param lotId 按批次筛
   * @param status 按状态筛
   * @param keyword 序列号 / SKU 编码 / 批次号 模糊匹配
   */
  public record SerialQuery(
      @Schema(nullable = true) UUID skuId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID locationId,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String status,
      @Schema(nullable = true) String keyword,
      @Schema(nullable = true) String sort,
      @Schema(nullable = true, defaultValue = "0") Integer page,
      @Schema(nullable = true, defaultValue = "50") Integer size) {}

  /**
   * 扫码 / 单条录入。
   *
   * <p>录入本身就是一次入库（货扫到哪儿就在哪儿），因此服务端会同时写下 {@link SerialOperationRecord} 与一条 {@link
   * SerialMovementRow}， 而不是「先建档、以后再补一条入库记录」—— 后者会让号在它已经在库的那段时间里 没有任何流转历史，正是事后查不到「这号什么时候到的」的原因。
   *
   * @param serialNumber 序列号字面量（设备铭牌上的原文，不做大小写归一）
   * @param skuId 绑定的 SKU，永久不可改
   * @param lotId 批次，可空
   * @param locationId 落位库位 —— 录入必须有位置，否则「在库」就没有库位可指
   * @param refType 来源单据类型（PO 等），可空
   * @param refId 来源单据 id，可空
   * @param note 备注，可空
   * @param occurredAt 业务时间，不传则取当前时刻
   */
  public record SerialRegisterRequest(
      String serialNumber,
      UUID skuId,
      @Schema(nullable = true) UUID lotId,
      UUID locationId,
      @Schema(nullable = true) String refType,
      @Schema(nullable = true) UUID refId,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  /**
   * 批量导入（一个 SKU 的若干台设备，通常来自收货扫描枪的一次上传）。
   *
   * <p>整批**同 SKU、同库位**：批量导入的价值在于「一次扫完一箱」，而混着 SKU 的批量 在现场是用不上的，强行支持只会让校验规则分叉。需要多 SKU 时调多次。
   *
   * @param serials 序列号列表；服务端去重并逐号校验，重复的号会被记成错误而不是被静默合并
   * @param dryRun 为 true 时只做校验不落库：收货现场先看一遍「哪几个号有问题」再决定是否提交
   */
  public record SerialImportRequest(
      UUID skuId,
      @Schema(nullable = true) UUID lotId,
      UUID locationId,
      List<String> serials,
      @Schema(nullable = true) String refType,
      @Schema(nullable = true) UUID refId,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true, defaultValue = "false") Boolean dryRun) {}

  /**
   * 编辑档案。
   *
   * <p>只能改批次绑定与备注。<b>不能改 SKU</b>（序列号与 SKU 的绑定永久不可变， V58 触发器会挡），也**不能改状态与位置** —— 那两样必须走流转端点，
   * 否则状态变更就绕开了流转历史，档案和历史会对不上。
   *
   * @param lotId 新批次；{@code clearLot=true} 时表示解绑批次
   * @param clearLot 显式解绑批次。之所以要一个显式开关而不是「传 null 表示解绑」： 请求体里省略 lotId 与传 null 在 JSON
   *     里分不开，没有这个开关就没法表达「解绑」， 只能另开一个端点。
   */
  public record SerialUpdateRequest(
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true, defaultValue = "false") Boolean clearLot,
      @Schema(nullable = true) String note) {}

  /**
   * 出入库 / 挪位 / 改态 / 改绑。
   *
   * <p>{@code declaredQuantity} 与 {@code serialNumbers} 的关系就是蓝图那条硬约束：
   * 两者必须完全相等。服务端在校验阶段就逐条比对，<b>一条都不写</b>； 通过后写入的操作头由 V58 的 {@code CHECK(serial_count =
   * declared_quantity)} 与延迟约束触发器再兜一遍。
   *
   * @param direction 操作方向
   * @param skuId 目标 SKU：一次操作里的所有序列号必须属于它。入库建档时它是必填 （号还不存在，得告诉系统这是哪个型号的机器）；其余方向它用于校验「这批号是不是同一个型号」， 传
   *     null 则跳过该校验（现场本来就只有一个 SKU 时不必先查一次再传上来）。
   * @param declaredQuantity 业务声明的数量（NUMERIC(19,6) + BigDecimal，禁浮点）
   * @param serialNumbers 本次要动的序列号列表
   * @param toLocationId 目标库位（INBOUND / RELOCATION 必填，OUTBOUND 不填）
   * @param fromLocationId 来源库位（OUTBOUND / RELOCATION 可选；填了就必须是号当前所在库位）
   * @param lotId 批次（INBOUND 建档时可带；REBIND 时为目标批次）
   * @param toStatus 目标状态（STATUS_CHANGE 必填）
   * @param refType 来源单据类型，可空
   * @param refId 来源单据 id，可空
   * @param note 备注，可空
   * @param occurredAt 业务时间，不传则取当前时刻
   * @param dryRun 为 true 时只校验不落库（下单前先验一遍货够不够、状态对不对）
   */
  public record SerialMoveRequest(
      String direction,
      @Schema(nullable = true) UUID skuId,
      BigDecimal declaredQuantity,
      List<String> serialNumbers,
      @Schema(nullable = true) UUID toLocationId,
      @Schema(nullable = true) UUID fromLocationId,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String toStatus,
      @Schema(nullable = true) String refType,
      @Schema(nullable = true) UUID refId,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime occurredAt,
      @Schema(nullable = true, defaultValue = "false") Boolean dryRun) {}

  /**
   * 出入库校验（只算不写）。
   *
   * <p>形状与 {@link SerialMoveRequest} 完全相同：前端「先验后提」用的是同一个请求体， 少一套 DTO 就少一处「校验用的规则」和「提交用的规则」悄悄分叉的可能。
   */
  public record SerialValidateRequest(
      String direction,
      @Schema(nullable = true) UUID skuId,
      BigDecimal declaredQuantity,
      List<String> serialNumbers,
      @Schema(nullable = true) UUID toLocationId,
      @Schema(nullable = true) UUID fromLocationId,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String toStatus,
      @Schema(nullable = true) String refType,
      @Schema(nullable = true) UUID refId,
      @Schema(nullable = true) String note,
      @Schema(nullable = true) OffsetDateTime occurredAt) {}

  // ------------------------------------------------------------ 输出

  /**
   * 序列号档案。
   *
   * <p>只描述「现在」：状态、当前位置、批次。历史不在这里 —— 要看轨迹去 {@link SerialMovementRow}，档案里塞历史会让一行变成一坨。
   *
   * @param locationWarehouseId 当前库位所属仓库（已无库位时为 null）
   * @param locationCode / locationName 当前库位编码与名称（已无库位时为空串）
   * @param skuCode SKU 编码固化下来：catalog 是别的服务，档案要独立可读
   * @param lotCode 批次号固化下来（同上）
   * @param movementCount 累计流转次数（从历史实时聚合，不落库 —— 它是历史条数，不是档案的属性）
   */
  public record SerialProfile(
      UUID id,
      String serialNumber,
      UUID skuId,
      String skuCode,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) String lotCode,
      SerialStatus status,
      String statusLabel,
      @Schema(nullable = true) UUID currentLocationId,
      @Schema(nullable = true) UUID locationWarehouseId,
      @Schema(nullable = true) String locationCode,
      @Schema(nullable = true) String locationName,
      String note,
      long movementCount,
      @Schema(nullable = true) OffsetDateTime lastMovementAt,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 序列号档案分页（真分页，total 不受分页影响）。 */
  public record SerialPage(List<SerialProfile> serials, long total, int page, int size) {}

  /**
   * 位置流转历史的一行。
   *
   * <p>append-only：这行一旦写下就代表「那件货当时确实在那儿」。 {@code from*} / {@code to*} 成对出现，「从哪来、到哪去」一眼可读，
   * 不必靠顺序推断（顺序会骗人：补记的流水 occurred_at 可能与写入顺序相反）。
   *
   * @param fromLocationCode / toLocationCode 库位编码固化，null 表示不在任何库位
   * @param fromLotCode / toLotCode 批次号固化，null 表示无批次
   */
  public record SerialMovementRow(
      UUID id,
      UUID serialId,
      String serialNumber,
      UUID operationId,
      SerialMovementKind movementKind,
      String movementLabel,
      SerialStatus fromStatus,
      SerialStatus toStatus,
      @Schema(nullable = true) UUID fromLocationId,
      @Schema(nullable = true) String fromLocationCode,
      @Schema(nullable = true) UUID toLocationId,
      @Schema(nullable = true) String toLocationCode,
      @Schema(nullable = true) UUID fromLotId,
      @Schema(nullable = true) String fromLotCode,
      @Schema(nullable = true) UUID toLotId,
      @Schema(nullable = true) String toLotCode,
      String refType,
      @Schema(nullable = true) UUID refId,
      UUID actorId,
      String note,
      OffsetDateTime occurredAt,
      OffsetDateTime createdAt) {}

  /** 流转历史分页（真分页）。 */
  public record SerialMovementPage(
      List<SerialMovementRow> movements, long total, int page, int size) {}

  /**
   * 出入库操作头。
   *
   * <p>{@code declaredQuantity} 与 {@code serialCount} 并列放在响应里，是为了让 「这一单声明了几件、实际动了几个号」在界面上直接可比 ——
   * 这两个数字相等是本模块的 硬约束，把它们分开放到两个响应里就没人比了。
   */
  public record SerialOperationRecord(
      UUID id,
      SerialMovementDirection direction,
      String directionLabel,
      UUID skuId,
      @Schema(nullable = true) UUID lotId,
      @Schema(nullable = true) UUID warehouseId,
      @Schema(nullable = true) UUID locationId,
      String refType,
      @Schema(nullable = true) UUID refId,
      BigDecimal declaredQuantity,
      long serialCount,
      UUID actorId,
      String note,
      OffsetDateTime occurredAt,
      OffsetDateTime createdAt) {}

  /** 操作头分页（真分页）。 */
  public record SerialOperationPage(
      List<SerialOperationRecord> operations, long total, int page, int size) {}

  /**
   * 一条校验错误。
   *
   * @param serialNumber 出错的号；与号无关的全局错误（如数量与条数不符）为空串
   * @param expected 期望值（期望状态 / 期望库位编码），没有期望时为空串
   * @param actual 实际值（实际状态 / 实际库位编码），没有实际值时为空串
   */
  public record SerialIssue(
      SerialIssueCode code,
      String codeLabel,
      String message,
      String serialNumber,
      String expected,
      String actual) {}

  /**
   * 出入库强校验结果。
   *
   * @param passed 是否通过。**通过不等于没写库** —— 还要看 {@code dryRun}： 校验端点永远只算不写，运动端点在 {@code dryRun=true}
   *     时也只算不写。
   * @param declaredQuantity 业务声明的数量
   * @param serialCount 实际拿到的序列号条数
   * @param issues 逐条错误；有错误时 {@code passed=false}，且一条数据都不会写入
   * @param applied 真正落了库时为 true（dryRun 时恒为 false）
   * @param operation 本次落下的操作头（dryRun 或校验失败时为 null）
   */
  public record SerialValidationResult(
      boolean passed,
      boolean dryRun,
      BigDecimal declaredQuantity,
      int serialCount,
      List<SerialIssue> issues,
      boolean applied,
      @Schema(nullable = true) SerialOperationRecord operation,
      @Schema(nullable = true) List<SerialProfile> serials) {}

  /**
   * 词表：状态、流转种类、操作方向、校验错误码。
   *
   * <p>刻意由后端下发而不是前端硬编码：这四组取值与 V58 的 CHECK 一一对应， 前端写死一份就多一处「迁移加了第 6 个状态而界面还停在 5 个」的漂移点。 界面拿到 {@code
   * label} 直接显示中文，拿到 {@code name} 提交码值。
   *
   * @param issues 全部校验错误码 —— 前端可按码分流（整单退回 vs 挑出个别号单独处理）
   */
  public record SerialVocabularies(
      List<SerialStatus> statuses,
      List<SerialMovementKind> movementKinds,
      List<SerialMovementDirection> directions,
      List<SerialIssueCode> issues) {}

  /**
   * 状态分布汇总。
   *
   * <p>{@code total} 与五档之和**恒等**（{@code sumMatches} 显式给出）， 界面必须把它画出来：五档数字加起来不等于总数，说明聚合口径漂了，
   * 那种情况下每个单独的数字都不可信。
   *
   * @param unboundCount 未绑定批次的号数（非批次管理品，按设计就该有）
   * @param lotMismatchCount 批次已耗尽/已过期但号仍挂在该批次上的数量 —— 提醒用户换绑
   */
  public record SerialStats(
      long total,
      long inStock,
      long sold,
      long inTransit,
      long repair,
      long scrapped,
      boolean sumMatches,
      long unboundCount,
      long lotMismatchCount) {}
}
