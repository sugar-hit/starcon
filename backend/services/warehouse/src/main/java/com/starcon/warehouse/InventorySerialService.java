package com.starcon.warehouse;

import static com.starcon.warehouse.InventorySerialModels.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 7.3.2.1 序列号：档案、位置流转历史与出入库强校验。
 *
 * <p>蓝图原话：「序列号级库存管理：录入/扫码、状态（在库/已售/在途/维修/报废）、当前位置、 绑定 SKU 与批次，出入库时强校验数量与序列号条数一致」「输出：序列号档案 + 位置流转历史
 * + 校验错误」 「<b>序列号校验是一致性硬约束</b>：数量为 1 时必须有且仅有 1 个序列号，不允许『大概齐』」。
 *
 * <p><b>三张表分工</b>（V58）：{@code inventory_serial} 是**现在**这一件是什么状态、在哪个库位； {@code
 * inventory_serial_movement} 是 append-only 的流转事实； {@code inventory_serial_operation}
 * 是一次业务操作的操作头，承载「声明数量」与「序列号条数」 这两个可比数字 —— 没有这一行，蓝图那条硬约束就只能是一段可以被绕过的应用层 if。
 *
 * <p><b>「不允许大概齐」不是提示，是提交不了的状态</b>。四层一起保证：
 *
 * <ol>
 *   <li>本服务在校验阶段逐条比对 {@code declaredQuantity} 与 {@code serialNumbers.size()}，
 *       不等就<b>一条都不写</b>，并把差多少、哪个号有问题逐条回给调用方。
 *   <li>写操作头前再校验一次「可操作条数 == 声明数量」，防止逐号校验因条件变化少了一个号。
 *   <li>操作头的 {@code CHECK(serial_count = declared_quantity)} 让「声明自相矛盾」无法落库。
 *   <li>V58 的延迟约束触发器在 COMMIT 时数一遍真正写入的流转行，对不上就整笔回滚 —— 挡住「数量对得上、实际少写两件」这种半成品。
 * </ol>
 *
 * <p><b>位置与状态是两条正交的轴</b>，这是本模块最容易写错的地方：
 *
 * <pre>
 *   方向            current_location_id 变成      toStatus
 *   INBOUND         toLocationId（必填）          缺省 IN_STOCK，允许 IN_STOCK / REPAIR
 *   OUTBOUND        null                          必填，允许 SOLD / IN_TRANSIT / SCRAPPED
 *   RELOCATION      toLocationId（必填）          可空=保持，允许 IN_STOCK / REPAIR
 *   STATUS_CHANGE   保持不变                      必填，只能 IN_STOCK / REPAIR
 *   REBIND          保持不变                      必须为空（改批次不改状态）
 * </pre>
 *
 * <p>为什么报废只能走 OUTBOUND：V58 的 CHECK 规定「报废的号不能挂在库位上」， 而货报废时它确实已经从货架上被拿走了。出库 + SCRAPPED
 * 表达的正是这件事，语义与物理一致； 硬造一个「状态流转」把它留在库位上，只会让约束与现实两头不讨好。
 *
 * <p>四条实现纪律：
 *
 * <ul>
 *   <li><b>租户不来自请求</b>：由 {@link AccessService} 从已认证成员关系解析并绑定 {@code app.current_tenant}，写入时用 {@code
 *       current_setting('app.current_tenant')} 取值 （与 {@code WarehouseReferenceService} 同一写法），全程靠
 *       RLS 隔离，不接受 X-Tenant-Id。
 *   <li><b>写入逐行 execute</b>，不用 {@code DSLContext.batch(String,...)}：纯文本语句上 带 {@code ?::cast} 的
 *       batch 绑定不可靠（与 7.1.2.1 / 7.1.2.2 同一条理由）。
 *   <li><b>时间绑定</b>：{@code ?::timestamptz} 后面一律跟一个字面量（{@code now()}）， 让 cast 不落在 values 末位 —— 末位
 *       cast 会让 jOOQ 整体绑定错位一格。
 *   <li><b>数量一律 numeric(19,6) + BigDecimal</b>，比较一律 {@code compareTo}，不用 {@code equals} （后者把 0 与
 *       0.0000 判成不等）。
 * </ul>
 */
@Service
public class InventorySerialService {

  /** 单页最大行数：档案列表是运营日常操作，200 行已远超一屏。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 单次批量导入/流转的最大序列号条数。500 是一箱高端设备的上限量级，再大就该分批了。 */
  static final int MAX_BATCH_SIZE = 500;

  /** 批量导入的最小批量：1 条走扫码端点。两条路径写出的历史形态不同，混用只会让人困惑。 */
  static final int MIN_IMPORT_BATCH = 2;

  /** 数量列的零：避免同一列里混着 scale 不一样的 0。 */
  private static final BigDecimal ZERO = new BigDecimal("0.000000");

  /** 排序白名单的可选值。 */
  private static final String SORTS = "RECENT、SERIAL_NUMBER、STATUS、LOCATION、MOVED";

  /**
   * 不动库位的两个方向：状态流转与批次改绑。
   *
   * <p>它们的号必须在架上 —— 一个不在任何库位上的号要改状态或改批次，只能走出入库 （那才会真的改变它的位置）。把这个判断显式列出来，免得每加一个方向就忘了
   * 给「库位不变」这条分支补一条检查。
   */
  private static final List<SerialMovementDirection> KEEP_LOCATION =
      List.of(SerialMovementDirection.STATUS_CHANGE, SerialMovementDirection.REBIND);

  private final DSLContext sql;

  public InventorySerialService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 列表与档案

  /** 序列号档案列表（真分页，total 不受分页影响）。 */
  public SerialPage page(SerialQuery query) {
    var q = query == null ? EMPTY_QUERY : query;
    int pageNo = Math.max(0, q.page() == null ? 0 : q.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 50 : q.size()));

    var where = new ArrayList<Object>();
    var conditions = profileConditions(q, where);

    var binds = new ArrayList<>(where);
    binds.add(size);
    binds.add((long) pageNo * size);
    var rows =
        sql.fetch(
            "select "
                + PROFILE_COLUMNS
                + " "
                + PROFILE_FROM
                + " where "
                + conditions
                + " order by "
                + orderBy(q.sort())
                + " limit ? offset ?",
            binds.toArray());

    long total = count("select count(*) " + PROFILE_FROM + " where " + conditions, where);
    return new SerialPage(
        rows.stream().map(InventorySerialService::profile).toList(), total, pageNo, size);
  }

  /** 单个序列号档案。跨租户在 RLS 下查不到 → 404，不泄露「这个号存在」。 */
  public SerialProfile profile(UUID id) {
    if (id == null) {
      throw new IllegalArgumentException("序列号 id 不能为空");
    }
    var rows = sql.fetch("select " + PROFILE_COLUMNS + " " + PROFILE_FROM + " where s.id = ?", id);
    if (rows.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "序列号不存在或无权查看");
    }
    return profile(rows.get(0));
  }

  /**
   * 状态分布汇总。
   *
   * <p>{@code sumMatches} 显式给出「五档之和 == 总数」。界面必须把它画出来： 五档加起来不等于总数，说明聚合口径漂了，那种情况下每个单独的数字都不可信。
   */
  public SerialStats stats(SerialQuery query) {
    var q = query == null ? EMPTY_QUERY : query;
    var where = new ArrayList<Object>();
    var conditions = profileConditions(q, where);

    var rows =
        sql.fetch(
            "select s.status, count(*) as cnt "
                + PROFILE_FROM
                + " where "
                + conditions
                + " group by s.status",
            where.toArray());
    long total = 0;
    long inStock = 0;
    long sold = 0;
    long inTransit = 0;
    long repair = 0;
    long scrapped = 0;
    for (Record r : rows) {
      long cnt = r.get("cnt", Long.class);
      total += cnt;
      switch (SerialStatus.valueOf(r.get("status", String.class))) {
        case IN_STOCK -> inStock += cnt;
        case SOLD -> sold += cnt;
        case IN_TRANSIT -> inTransit += cnt;
        case REPAIR -> repair += cnt;
        case SCRAPPED -> scrapped += cnt;
      }
    }

    var detail =
        sql.fetch(
            "select count(*) filter (where s.lot_id is null) as unbound,"
                + " count(*) filter (where s.lot_id is not null and lo.status <> 'ACTIVE') as stale_lot "
                + PROFILE_FROM
                + " where "
                + conditions,
            where.toArray());
    long unbound = detail.get(0).get("unbound", Long.class);
    long staleLot = detail.get(0).get("stale_lot", Long.class);

    long sum = inStock + sold + inTransit + repair + scrapped;
    return new SerialStats(
        total, inStock, sold, inTransit, repair, scrapped, sum == total, unbound, staleLot);
  }

  // ================================================================ 扫码录入 / 批量导入

  /**
   * 扫码 / 单条录入。
   *
   * <p>录入本身就是一次入库：号扫到哪个库位，它当下就在那个库位上。 因此这里直接写「档案 + 操作头 + 一条流转」，而不是先建档、以后再补一条入库记录 ——
   * 后者会让号在它已经在库的那段时间里没有任何流转历史，正是事后查不到「这号什么时候到的」的原因。
   *
   * <p>号已存在时按**退货入库**处理（已售/在途/维修 → 在库）；已在库的号会被拒绝并提示改用库位转移。
   */
  @Transactional
  public SerialValidationResult register(SerialRegisterRequest request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("录入请求不能为空");
    }
    var move =
        new SerialMoveRequest(
            SerialMovementDirection.INBOUND.name(),
            request.skuId(),
            BigDecimal.ONE,
            request.serialNumber() == null ? List.of() : List.of(request.serialNumber()),
            request.locationId(),
            null,
            request.lotId(),
            null,
            request.refType(),
            request.refId(),
            request.note(),
            request.occurredAt(),
            false);
    return apply(move, actorId);
  }

  /**
   * 批量导入（一个 SKU 的若干台设备，通常是收货扫描枪一次上传的整箱）。
   *
   * <p>整批同 SKU、同库位、同批次：批量导入的价值在于「一次扫完一箱」， 混着 SKU 的批量在现场用不上，强行支持只会让校验规则分叉。要多 SKU 就调多次。
   *
   * <p>{@code dryRun=true} 时只校验不落库 —— 收货现场先看一遍「哪几个号有问题」再决定是否提交， 比先落库再逐条撤销现实得多。
   */
  @Transactional
  public SerialValidationResult importBatch(SerialImportRequest request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("批量导入请求不能为空");
    }
    var serials = request.serials() == null ? List.<String>of() : request.serials();
    if (serials.size() < MIN_IMPORT_BATCH) {
      throw new IllegalArgumentException("批量导入至少 " + MIN_IMPORT_BATCH + " 条；单条录入请用扫码端点");
    }
    if (serials.size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "单次最多导入 " + MAX_BATCH_SIZE + " 条序列号，本次 " + serials.size() + " 条，请分批");
    }
    var move =
        new SerialMoveRequest(
            SerialMovementDirection.INBOUND.name(),
            request.skuId(),
            BigDecimal.valueOf(serials.size()),
            serials,
            request.locationId(),
            null,
            request.lotId(),
            null,
            request.refType(),
            request.refId(),
            request.note(),
            request.occurredAt(),
            request.dryRun());
    return apply(move, actorId);
  }

  // ================================================================ 档案编辑与删除

  /**
   * 编辑档案：只能改批次绑定与备注。
   *
   * <p><b>不能改 SKU</b>（序列号与 SKU 的绑定永久不可变，V58 触发器会挡）， 也<b>不能改状态与位置</b> —— 那两样必须走流转端点，否则状态变更就绕开了流转历史，
   * 档案与历史会对不上。
   */
  @Transactional
  public SerialProfile update(UUID id, SerialUpdateRequest request) {
    var current = profile(id);
    boolean clearLot = request != null && Boolean.TRUE.equals(request.clearLot());
    UUID lotId = clearLot || request == null ? null : request.lotId();
    if (lotId != null && !Objects.equals(lotId, current.lotId())) {
      // 改绑前先确认这个批次确实是**同一个 SKU** 的批次，否则档案会指向一个不相干的批次，
      // 按批次追溯时直接断链 —— 那比没绑批次更难查。
      var lot =
          sql.fetch(
              "select id from inventory_lot where id = ? and sku_id = ?", lotId, current.skuId());
      if (lot.isEmpty()) {
        throw new IllegalArgumentException("批次不存在，或不属于该序列号绑定的 SKU");
      }
    }
    String note = request == null || request.note() == null ? current.note() : request.note();
    sql.execute(
        "update inventory_serial set lot_id = ?, note = ?, updated_at = now() where id = ?",
        lotId,
        note,
        id);
    return profile(id);
  }

  /**
   * 作废一次录错的录入 —— 只删「录错了」，不删「没发生过」。
   *
   * <p>已被任何单据引用过的号删掉，等于让那张单据凭空少了一件货，那种情况只能走报废。 因此只允许作废**仅有建档那一次入库**、且仍在库的号，这正是扫码录错的标准修法。
   *
   * <p>走 {@code discard_unused_serial} 而不是直接 {@code delete}：流转历史是 append-only， 应用角色连它的 DELETE
   * 权限都没有。这个函数是数据库里唯一被允许删除序列号历史的入口， 它会把守卫条件重新校验一遍（见 V58）。直接删档案会撞流转行的外键 —— 那正是它的作用：
   * 让「删档案」与「删掉它那次入库的凭据」这两件事不可能各走各的。
   */
  @Transactional
  public void delete(UUID id) {
    profile(id);
    sql.execute("select discard_unused_serial(?)", id);
  }

  // ================================================================ 出入库强校验

  /** 只校验不落库。请求体形状与 {@link #move} 完全一致，避免两套规则悄悄分叉。 */
  public SerialValidationResult validate(SerialValidateRequest request) {
    if (request == null) {
      throw new IllegalArgumentException("校验请求不能为空");
    }
    var move =
        new SerialMoveRequest(
            request.direction(),
            request.skuId(),
            request.declaredQuantity(),
            request.serialNumbers(),
            request.toLocationId(),
            request.fromLocationId(),
            request.lotId(),
            request.toStatus(),
            request.refType(),
            request.refId(),
            request.note(),
            request.occurredAt(),
            true);
    return apply(move, null);
  }

  /**
   * 出入库 / 库位转移 / 状态流转 / 批次改绑。
   *
   * <p>流程固定三步：<b>先校验、再落库、返回逐条错误</b>。 校验没过时**一条都不写** —— 半套操作落库比不落库更糟，因为档案与历史已经对不上了。
   */
  @Transactional
  public SerialValidationResult move(SerialMoveRequest request, UUID actorId) {
    if (request == null) {
      throw new IllegalArgumentException("操作请求不能为空");
    }
    return apply(request, actorId);
  }

  /** 校验 + 落库的共同实现。{@code dryRun} 为真时到校验为止就返回。 */
  private SerialValidationResult apply(SerialMoveRequest request, UUID actorId) {
    var direction = SerialMovementDirection.parse(request.direction());
    boolean dryRun = Boolean.TRUE.equals(request.dryRun());
    var rawSerials = request.serialNumbers() == null ? List.<String>of() : request.serialNumbers();

    if (rawSerials.size() > MAX_BATCH_SIZE) {
      throw new IllegalArgumentException(
          "单次最多操作 " + MAX_BATCH_SIZE + " 个序列号，本次 " + rawSerials.size() + " 个，请分批");
    }

    var issues = new ArrayList<SerialIssue>();
    var quantity = request.declaredQuantity();

    // ---- 第 1 层：数量本身合不合法。
    // 不合法时**不再**往下算条数，否则会同时报「数量不对」和「条数不对」两条，
    // 把真正的问题（数量填错了）淹掉。
    boolean quantityOk = false;
    if (quantity == null) {
      issues.add(issue(SerialIssueCode.QUANTITY_NOT_INTEGER, "", "序列号数量必填", "", ""));
    } else if (quantity.compareTo(BigDecimal.ZERO) < 0) {
      issues.add(
          issue(SerialIssueCode.QUANTITY_NEGATIVE, "", "序列号数量不能为负", "", quantity.toPlainString()));
    } else if (quantity.stripTrailingZeros().scale() > 0) {
      // 序列号是离散件：带小数的数量在物理上不存在。
      issues.add(
          issue(
              SerialIssueCode.QUANTITY_NOT_INTEGER,
              "",
              "序列号是离散件，数量不允许带小数",
              "整数",
              quantity.toPlainString()));
    } else {
      quantityOk = true;
    }

    // ---- 第 2 层：蓝图点名的硬约束 —— 条数与数量必须**完全**相等。
    var trimmed = new ArrayList<String>(rawSerials.size());
    for (var raw : rawSerials) {
      var value = raw == null ? "" : raw.trim();
      if (value.isEmpty()) {
        issues.add(issue(SerialIssueCode.SERIAL_REQUIRED, "", "序列号不能为空", "", ""));
        continue;
      }
      trimmed.add(value);
    }
    if (quantityOk && trimmed.size() != quantity.toBigIntegerExact().intValue()) {
      issues.add(
          issue(
              SerialIssueCode.SERIAL_COUNT_MISMATCH,
              "",
              "声明数量 "
                  + quantity.toBigIntegerExact()
                  + "，实际提供 "
                  + trimmed.size()
                  + " 个序列号：出入库数量必须与序列号条数完全一致，不允许大概齐",
              String.valueOf(quantity.toBigIntegerExact()),
              String.valueOf(trimmed.size())));
    }

    // ---- 第 3 层：同一次操作里同一个号只能出现一次。
    var seen = new LinkedHashSet<String>();
    var unique = new ArrayList<String>(trimmed.size());
    for (var number : trimmed) {
      if (!seen.add(number)) {
        issues.add(
            issue(SerialIssueCode.SERIAL_DUPLICATED, number, "同一个序列号在本次操作里出现了多次", number, number));
      } else {
        unique.add(number);
      }
    }

    // ---- 第 4 层：方向与参数自身的组合是否成立。
    // 只管「调用本身填对没有」，不管「库存够不够、状态对不对」——
    // 混在一起会让报错指向错误的对象（没填库位却报「3 个号状态不对」）。
    var plan = resolvePlan(direction, request, issues);

    // ---- 第 5 层：逐号校验。只在第 4 层成立时继续。
    List<SerialPlan> plans = List.of();
    if (!unique.isEmpty() && plan != null) {
      plans = planSerials(unique, request.skuId(), plan, issues);
    }

    if (!issues.isEmpty()) {
      return new SerialValidationResult(
          false,
          dryRun,
          quantity == null ? ZERO : quantity,
          trimmed.size(),
          List.copyOf(issues),
          false,
          null,
          null);
    }
    if (dryRun || plans.isEmpty()) {
      return new SerialValidationResult(
          true,
          dryRun,
          quantity == null ? ZERO : quantity,
          trimmed.size(),
          List.of(),
          false,
          null,
          null);
    }

    var operation = persist(plan, plans, direction, request, actorId);
    var profiles = plans.stream().map(SerialPlan::id).map(this::profile).toList();
    return new SerialValidationResult(
        true,
        false,
        quantity == null ? ZERO : quantity,
        trimmed.size(),
        List.of(),
        true,
        operation,
        profiles);
  }

  // ---------------------------------------------------------------- 计划：把方向翻译成一次具体操作

  /**
   * 一次方向落地后的「目标形态」。
   *
   * @param targetStatus 目标状态；{@code null} 表示「各自保持原状态」（库位转移未指定状态时）
   * @param toLocationId 目标库位；{@code null} 表示「保持原库位」或「不在任何库位」
   * @param fromLocationId 可选的来源库位校验；填了就必须是号当前所在库位
   * @param toLotId 目标批次；{@code null} 表示「保持原批次」或「解绑批次」
   */
  private record Plan(
      SerialMovementDirection direction,
      SerialStatus targetStatus,
      UUID toLocationId,
      UUID fromLocationId,
      UUID toWarehouseId,
      UUID toLotId) {}

  /** 一个序列号在本次操作里的落点。 */
  private record SerialPlan(
      UUID id,
      String serialNumber,
      SerialStatus fromStatus,
      SerialStatus toStatus,
      UUID fromLocationId,
      UUID toLocationId,
      UUID fromLotId,
      UUID toLotId) {}

  /** 校验方向自身的参数组合，并算出目标形态。 */
  private Plan resolvePlan(
      SerialMovementDirection direction, SerialMoveRequest request, List<SerialIssue> issues) {
    var toStatus =
        request.toStatus() == null || request.toStatus().isBlank()
            ? null
            : SerialStatus.parse(request.toStatus());
    var lotId = request.lotId();

    switch (direction) {
      case INBOUND -> {
        if (request.toLocationId() == null) {
          issues.add(issue(SerialIssueCode.LOCATION_REQUIRED, "", "入库必须指定落位库位", "", ""));
          return null;
        }
        var target = toStatus == null ? SerialStatus.IN_STOCK : toStatus;
        if (target != SerialStatus.IN_STOCK && target != SerialStatus.REPAIR) {
          issues.add(
              issue(
                  SerialIssueCode.STATUS_NOT_ALLOWED,
                  "",
                  "入库后的状态只能是 IN_STOCK 或 REPAIR；把一个已出库的号收回来正是入库的用途",
                  "IN_STOCK / REPAIR",
                  target.name()));
          return null;
        }
        return new Plan(
            direction,
            target,
            request.toLocationId(),
            null,
            warehouseOf(request.toLocationId()),
            lotId);
      }
      case OUTBOUND -> {
        if (request.toLocationId() != null) {
          issues.add(
              issue(
                  SerialIssueCode.LOCATION_NOT_ALLOWED,
                  "",
                  "出库后序列号不再挂在任何库位上，不能指定目标库位",
                  "null",
                  String.valueOf(request.toLocationId())));
          return null;
        }
        if (toStatus == null) {
          issues.add(
              issue(
                  SerialIssueCode.STATUS_NOT_ALLOWED,
                  "",
                  "出库必须说明去向：SOLD 已售 / IN_TRANSIT 在途 / SCRAPPED 报废",
                  "SOLD / IN_TRANSIT / SCRAPPED",
                  ""));
          return null;
        }
        if (toStatus != SerialStatus.SOLD
            && toStatus != SerialStatus.IN_TRANSIT
            && toStatus != SerialStatus.SCRAPPED) {
          issues.add(
              issue(
                  SerialIssueCode.STATUS_NOT_ALLOWED,
                  "",
                  "出库后的状态只能是 SOLD、IN_TRANSIT 或 SCRAPPED",
                  "SOLD / IN_TRANSIT / SCRAPPED",
                  toStatus.name()));
          return null;
        }
        return new Plan(direction, toStatus, null, request.fromLocationId(), null, lotId);
      }
      case RELOCATION -> {
        if (request.toLocationId() == null) {
          issues.add(issue(SerialIssueCode.LOCATION_REQUIRED, "", "库位转移必须指定目标库位", "", ""));
          return null;
        }
        if (toStatus != null
            && toStatus != SerialStatus.IN_STOCK
            && toStatus != SerialStatus.REPAIR) {
          issues.add(
              issue(
                  SerialIssueCode.STATUS_NOT_ALLOWED,
                  "",
                  "库位转移不改变售卖属性：目标状态只能是 IN_STOCK 或 REPAIR",
                  "IN_STOCK / REPAIR",
                  toStatus.name()));
          return null;
        }
        // toStatus 为 null = 保持各号原状态，逐号校验时各自按自己的原状态落。
        return new Plan(
            direction,
            toStatus,
            request.toLocationId(),
            request.fromLocationId(),
            warehouseOf(request.toLocationId()),
            null);
      }
      case STATUS_CHANGE -> {
        if (request.toLocationId() != null) {
          issues.add(
              issue(
                  SerialIssueCode.LOCATION_NOT_ALLOWED,
                  "",
                  "状态流转不改变库位；换位置请用库位转移或出库方向",
                  "null",
                  String.valueOf(request.toLocationId())));
          return null;
        }
        if (toStatus == null) {
          issues.add(issue(SerialIssueCode.STATUS_NOT_ALLOWED, "", "状态流转必须指定目标状态", "", ""));
          return null;
        }
        // 状态流转保持库位不变，所以目标状态必须是「有库位」的那两档。
        // 已售/在途/报废在 V58 的 CHECK 里要求库位为空，只能走 OUTBOUND。
        if (toStatus != SerialStatus.IN_STOCK && toStatus != SerialStatus.REPAIR) {
          issues.add(
              issue(
                  SerialIssueCode.STATUS_NOT_ALLOWED,
                  "",
                  toStatus.name() + " 的序列号不能挂在库位上：这类状态请用出库方向，它会把序列号从库位上摘掉",
                  "IN_STOCK / REPAIR",
                  toStatus.name()));
          return null;
        }
        return new Plan(
            direction,
            toStatus,
            null,
            request.fromLocationId(),
            request.fromLocationId() == null ? null : warehouseOf(request.fromLocationId()),
            null);
      }
      case REBIND -> {
        if (toStatus != null) {
          issues.add(
              issue(
                  SerialIssueCode.STATUS_NOT_ALLOWED,
                  "",
                  "批次改绑不改变状态，请去掉目标状态",
                  "null",
                  toStatus.name()));
          return null;
        }
        if (request.toLocationId() != null) {
          issues.add(
              issue(
                  SerialIssueCode.LOCATION_NOT_ALLOWED,
                  "",
                  "批次改绑不改变库位",
                  "null",
                  String.valueOf(request.toLocationId())));
          return null;
        }
        return new Plan(
            direction,
            null,
            null,
            request.fromLocationId(),
            request.fromLocationId() == null ? null : warehouseOf(request.fromLocationId()),
            lotId);
      }
    }
    return null;
  }

  /**
   * count(*) 归一化。
   *
   * <p>jOOQ 在这条语句上返回的是 {@code Object}（纯文本 SQL 没有列类型信息）， 窄化只在这一处做；三个列表的 total
   * 走同一个函数，分页口径才不会各写一遍各错一遍。
   */
  private long count(String text, List<Object> binds) {
    var value = sql.fetchValue(text, binds.toArray());
    return value == null ? 0L : ((Number) value).longValue();
  }

  /** 库位 → 所属仓库。查不到返回 null，由外键在写入时报错，不在这里提前 404。 */
  private UUID warehouseOf(UUID locationId) {
    if (locationId == null) {
      return null;
    }
    var rows = sql.fetch("select warehouse_id from warehouse_location where id = ?", locationId);
    return rows.isEmpty() ? null : rows.get(0).get(0, UUID.class);
  }

  /**
   * 逐号校验并算出每个号的落点。
   *
   * <p>一次 {@code where serial_number in (?,?,...)} 批量取回档案，而不是逐号查询： 扫 200 个号发 200 条 SQL
   * 在收货现场是可感知的卡顿，而号天然是列表参数， 一次 IN 查询既快又只走一次索引。
   *
   * <p>占位符是**按个数生成**的而不是 {@code = any(?)}：纯文本语句里 jOOQ 会把一个 {@code String[]} 当成多个独立绑定发出去，PG
   * 收到的是「一个标量 ANY 一个数组」， 直接报 {@code op ANY/ALL (array) requires array on right side}。
   * 占位符个数由入参个数决定，不存在被填错的位置。
   *
   * <p>取档案用左连接 {@code warehouse_location}：号本身一定有行（查不到就是「号不存在 →
   * 建档」的分支），但它可能没有库位（已售/在途/报废），那时要读出空字符串而不是丢行。
   */
  private List<SerialPlan> planSerials(
      List<String> numbers, UUID skuId, Plan plan, List<SerialIssue> issues) {
    var rows =
        sql.fetch(
            """
            select s.id, s.serial_number, s.sku_id, s.status, s.current_location_id,
                   s.lot_id, coalesce(loc.code, '') as location_code
            from inventory_serial s
            left join warehouse_location loc
              on loc.tenant_id = s.tenant_id and loc.id = s.current_location_id
            where s.serial_number in (%s)
            """
                .formatted(String.join(",", java.util.Collections.nCopies(numbers.size(), "?"))),
            numbers.toArray(new String[0]));

    var byNumber = new HashMap<String, Record>(rows.size());
    for (Record r : rows) {
      byNumber.put(r.get("serial_number", String.class), r);
    }

    var out = new ArrayList<SerialPlan>(numbers.size());
    for (String number : numbers) {
      var existing = byNumber.get(number);

      // --- 号不存在：只有入库建档才允许。
      if (existing == null) {
        if (plan.direction() != SerialMovementDirection.INBOUND) {
          issues.add(
              issue(SerialIssueCode.SERIAL_NOT_FOUND, number, "序列号不存在：" + number, "", number));
          continue;
        }
        if (skuId == null) {
          issues.add(issue(SerialIssueCode.SKU_MISMATCH, number, "新建序列号必须指定 SKU", "", ""));
          continue;
        }
        // 新号必然是从「不在任何库位、不在任何批次」走进 INBOUND 的落点。
        out.add(
            new SerialPlan(
                UUID.randomUUID(),
                number,
                null,
                plan.targetStatus(),
                null,
                plan.toLocationId(),
                null,
                plan.toLotId()));
        continue;
      }

      UUID id = existing.get("id", UUID.class);
      UUID serialSku = existing.get("sku_id", UUID.class);
      SerialStatus current = SerialStatus.valueOf(existing.get("status", String.class));
      UUID currentLocation = existing.get("current_location_id", UUID.class);
      UUID currentLot = existing.get("lot_id", UUID.class);

      if (skuId != null && !skuId.equals(serialSku)) {
        issues.add(
            issue(
                SerialIssueCode.SKU_MISMATCH,
                number,
                "序列号 " + number + " 绑定的 SKU 与本次操作不是同一个",
                String.valueOf(serialSku),
                String.valueOf(skuId)));
        continue;
      }

      // --- 位置校验
      if (plan.fromLocationId() != null
          && !Objects.equals(currentLocation, plan.fromLocationId())) {
        issues.add(
            issue(
                SerialIssueCode.LOCATION_MISMATCH,
                number,
                "序列号 " + number + " 不在指定库位上",
                String.valueOf(plan.fromLocationId()),
                existing.get("location_code", String.class)));
        continue;
      }
      if (plan.direction() == SerialMovementDirection.OUTBOUND && currentLocation == null) {
        issues.add(
            issue(
                SerialIssueCode.STATUS_NOT_ALLOWED,
                number,
                "序列号 " + number + " 当前不在任何库位上，无法出库",
                "在库",
                current.name()));
        continue;
      }
      if (KEEP_LOCATION.contains(plan.direction()) && currentLocation == null) {
        issues.add(
            issue(
                SerialIssueCode.STATUS_NOT_ALLOWED,
                number,
                "序列号 " + number + " 不在库位上，只能通过出库方向改变状态",
                "在库",
                current.name()));
        continue;
      }

      // --- 目标形态
      SerialStatus target =
          switch (plan.direction()) {
            case REBIND -> current;
            case RELOCATION -> plan.targetStatus() == null ? current : plan.targetStatus();
            default -> plan.targetStatus();
          };
      UUID toLocation =
          switch (plan.direction()) {
            case INBOUND, RELOCATION -> plan.toLocationId();
            case OUTBOUND -> null;
            case STATUS_CHANGE, REBIND -> currentLocation;
          };
      UUID toLot =
          switch (plan.direction()) {
            case REBIND -> plan.toLotId();
            case INBOUND -> plan.toLotId() != null ? plan.toLotId() : currentLot;
            default -> currentLot;
          };

      // --- 状态机
      if (target != current && !current.canMoveTo(target)) {
        issues.add(
            issue(
                SerialIssueCode.TRANSITION_NOT_ALLOWED,
                number,
                "序列号 " + number + " 当前为「" + current.label() + "」，不能变更为「" + target.label() + "」",
                current.name(),
                target.name()));
        continue;
      }
      // 改批次只能走 REBIND：否则一次普通的入库会顺手把别的号改绑到别的批次上去。
      if (plan.direction() != SerialMovementDirection.REBIND
          && plan.direction() != SerialMovementDirection.INBOUND
          && toLot != null
          && !Objects.equals(currentLot, toLot)) {
        issues.add(
            issue(
                SerialIssueCode.LOT_MISMATCH,
                number,
                "序列号 " + number + " 已绑定其它批次，改批次请用批次改绑方向",
                String.valueOf(currentLot),
                String.valueOf(toLot)));
        continue;
      }
      // 什么都不改变的「操作」是无效操作，写进历史只会让人以为真发生过一次变动。
      if (Objects.equals(currentLocation, toLocation)
          && current == target
          && Objects.equals(currentLot, toLot)) {
        issues.add(
            issue(
                SerialIssueCode.NO_CHANGE,
                number,
                "序列号 " + number + " 已经在这个库位、这个状态、这个批次上，本次操作不会改变任何东西",
                "",
                ""));
        continue;
      }

      out.add(
          new SerialPlan(
              id, number, current, target, currentLocation, toLocation, currentLot, toLot));
    }
    return out;
  }

  // ---------------------------------------------------------------- 落库

  /**
   * 写入一次操作：操作头 + N 条流转 + 档案推进，全部在同一个事务里。
   *
   * <p>操作头**先写**：流转行有指向它的外键，先有头才有行。 V58 的延迟约束触发器在 COMMIT 时回头数流转行 —— 少写一条，整笔回滚。
   *
   * <p>租户用 {@code current_setting('app.current_tenant')} 在 SQL 里取， 不从参数传（与 {@code
   * WarehouseReferenceService} 同一写法）：传进来就多一个能被填错的入口。
   */
  private SerialOperationRecord persist(
      Plan plan,
      List<SerialPlan> serials,
      SerialMovementDirection direction,
      SerialMoveRequest request,
      UUID actorId) {
    var occurredAt = request.occurredAt() == null ? OffsetDateTime.now() : request.occurredAt();
    var operationId = UUID.randomUUID();
    var quantity = request.declaredQuantity();

    // 逐号校验可能因为条件（号已存在、SKU 不符……）把某些号排除掉，
    // 此时「可操作条数」与「声明数量」不再相等 —— 必须在**写之前**就停下来。
    // 否则它要到 COMMIT 时才被延迟触发器发现，那次失败会伪装成一次随机的提交失败。
    if (serials.size() != quantity.toBigIntegerExact().intValue()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "声明 " + quantity.toBigIntegerExact() + " 件，但只有 " + serials.size() + " 件通过校验，本次操作已整体撤销");
    }
    if (request.skuId() == null) {
      throw new IllegalArgumentException("序列号操作必须指定 SKU");
    }

    sql.execute(
        """
        insert into inventory_serial_operation(id,tenant_id,direction,sku_id,lot_id,warehouse_id,
          location_id,ref_type,ref_id,declared_quantity,serial_count,actor_id,note,occurred_at)
        values (?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)
        """,
        operationId,
        direction.name(),
        request.skuId(),
        request.lotId(),
        plan.toWarehouseId(),
        plan.toLocationId(),
        request.refType() == null ? "" : request.refType(),
        request.refId(),
        quantity,
        serials.size(),
        actorId,
        request.note() == null ? "" : request.note(),
        occurredAt);

    for (var s : serials) {
      // 顺序：**档案先落、流转后落**。流转行有指向档案的外键，反过来写会让建档那一次
      // 直接撞外键 —— 而且这个顺序不能靠「先写完再一起提交」绕过去，外键是即时校验的。
      if (s.fromStatus() == null) {
        sql.execute(
            """
            insert into inventory_serial(id,tenant_id,serial_number,sku_id,lot_id,status,
              current_location_id,note,last_movement_at,created_at,updated_at)
            values (?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?,?,?::timestamptz, now(), now())
            """,
            s.id(),
            s.serialNumber(),
            request.skuId(),
            s.toLotId(),
            s.toStatus().name(),
            s.toLocationId(),
            request.note() == null ? "" : request.note(),
            occurredAt);
      } else {
        // 乐观推进：把「号现在长什么样」写进 WHERE，并发改动时这里会更新 0 行。
        // 宁可整笔失败也不要覆盖掉别人的写入 —— 并发地把同一个号改两次，
        // 其中一次必然是错的，而错的那次会写进不可修改的历史里。
        int updated =
            sql.execute(
                """
                update inventory_serial set status = ?, current_location_id = ?, lot_id = ?,
                       last_movement_at = ?::timestamptz, updated_at = now()
                where id = ? and status = ?
                  and current_location_id is not distinct from ?::uuid
                  and lot_id is not distinct from ?::uuid
                """,
                s.toStatus().name(),
                s.toLocationId(),
                s.toLotId(),
                occurredAt,
                s.id(),
                s.fromStatus().name(),
                s.fromLocationId(),
                s.fromLotId());
        if (updated == 0) {
          throw new ResponseStatusException(
              HttpStatus.CONFLICT, "序列号 " + s.serialNumber() + " 在本次操作期间被改动过，请刷新后重试");
        }
      }

      sql.execute(
          """
          insert into inventory_serial_movement(id,tenant_id,serial_id,operation_id,movement_kind,
            serial_number,from_status,to_status,from_location_id,to_location_id,
            from_lot_id,to_lot_id,ref_type,ref_id,actor_id,note,occurred_at)
          values (?,current_setting('app.current_tenant',true)::uuid,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::timestamptz)
          """,
          UUID.randomUUID(),
          s.id(),
          operationId,
          direction.kind().name(),
          s.serialNumber(),
          s.fromStatus() == null ? SerialStatus.IN_STOCK.name() : s.fromStatus().name(),
          s.toStatus().name(),
          s.fromLocationId(),
          s.toLocationId(),
          s.fromLotId(),
          s.toLotId(),
          request.refType() == null ? "" : request.refType(),
          request.refId(),
          actorId,
          request.note() == null ? "" : request.note(),
          occurredAt);
    }

    var headers =
        sql.fetch(
            """
            select o.id, o.direction, o.sku_id, o.lot_id, o.warehouse_id, o.location_id,
                   o.ref_type, o.ref_id, o.declared_quantity, o.serial_count, o.actor_id,
                   o.note, o.occurred_at, o.created_at
            from inventory_serial_operation o where o.id = ?
            """,
            operationId);
    if (headers.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "序列号操作头写入后读不回来");
    }
    return operation(headers.get(0));
  }

  // ================================================================ 历史与操作头查询

  /** 位置流转历史分页（真分页）。按号 / 按操作 / 按 SKU / 按来源单据筛。 */
  public SerialMovementPage movements(SerialMovementQuery query) {
    var q = query == null ? EMPTY_MOVEMENT_QUERY : query;
    int pageNo = Math.max(0, q.page() == null ? 0 : q.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size() == null ? 50 : q.size()));

    var where = new ArrayList<Object>();
    var conditions = movementConditions(q, where);

    var binds = new ArrayList<>(where);
    binds.add(size);
    binds.add((long) pageNo * size);
    var rows =
        sql.fetch(
            "select "
                + MOVEMENT_COLUMNS
                + " from inventory_serial_movement m "
                + MOVEMENT_FROM
                + " where "
                + conditions
                + " order by m.occurred_at desc, m.id desc limit ? offset ?",
            binds.toArray());
    long total =
        count(
            "select count(*) from inventory_serial_movement m "
                + MOVEMENT_FROM
                + " where "
                + conditions,
            where);
    return new SerialMovementPage(
        rows.stream().map(InventorySerialService::movement).toList(), total, pageNo, size);
  }

  /** 单个序列号的时间线（倒序）。档案页右侧的「流转历史」面板走这里。 */
  public SerialMovementPage timeline(UUID serialId, int page, int size) {
    return movements(
        new SerialMovementQuery(serialId, null, null, null, null, null, null, page, size));
  }

  /** 出入库操作头分页（真分页）。 */
  public SerialOperationPage operations(SerialOperationQuery query) {
    var q = query == null ? EMPTY_OPERATION_QUERY : query;
    int pageNo = Math.max(0, q.page());
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, q.size()));

    var where = new ArrayList<Object>();
    var sb = new StringBuilder("1=1");
    if (q.direction() != null && !q.direction().isBlank()) {
      sb.append(" and o.direction = ?");
      where.add(SerialMovementDirection.parse(q.direction()).name());
    }
    if (q.skuId() != null) {
      sb.append(" and o.sku_id = ?");
      where.add(q.skuId());
    }
    if (q.refType() != null && !q.refType().isBlank()) {
      sb.append(" and o.ref_type = ?");
      where.add(q.refType());
    }
    if (q.refId() != null) {
      sb.append(" and o.ref_id = ?");
      where.add(q.refId());
    }
    var conditions = sb.toString();

    var binds = new ArrayList<>(where);
    binds.add(size);
    binds.add((long) pageNo * size);
    var rows =
        sql.fetch(
            """
            select o.id, o.direction, o.sku_id, o.lot_id, o.warehouse_id, o.location_id,
                   o.ref_type, o.ref_id, o.declared_quantity, o.serial_count, o.actor_id,
                   o.note, o.occurred_at, o.created_at,
                   (select count(*) from inventory_serial_movement m
                     where m.tenant_id = o.tenant_id and m.operation_id = o.id) as movement_count
            from inventory_serial_operation o where
            """
                + conditions
                + " order by o.occurred_at desc, o.id desc limit ? offset ?",
            binds.toArray());
    long total =
        count("select count(*) from inventory_serial_operation o where " + conditions, where);
    return new SerialOperationPage(
        rows.stream().map(InventorySerialService::operation).toList(), total, pageNo, size);
  }

  // ---------------------------------------------------------------- SQL 片段

  /**
   * 档案查询的 FROM。SKU 编码与批次号固化进档案（跨服务，档案要独立可读）， 库位编码/名称同理 —— 库里那几列改名删掉都不该让历史页面读不出「当时在哪」。
   *
   * <p>{@code left join lateral} 现算流转次数：它就是历史条数， 落一份计数进档案就多了一个能漂移的真相来源。走 {@code (tenant_id,
   * serial_id)} 索引， 每行一次索引查找，比全表 group by 便宜得多。
   */
  private static final String PROFILE_FROM =
      """
      from inventory_serial s
      left join sku_ref k on k.tenant_id = s.tenant_id and k.id = s.sku_id
      left join inventory_lot lo on lo.tenant_id = s.tenant_id and lo.id = s.lot_id
      left join warehouse_location loc
        on loc.tenant_id = s.tenant_id and loc.id = s.current_location_id
      left join lateral (
        select count(*) as cnt from inventory_serial_movement m
         where m.tenant_id = s.tenant_id and m.serial_id = s.id
      ) mc on true
      """;

  private static final String PROFILE_COLUMNS =
      """
      s.id, s.serial_number, s.sku_id, coalesce(k.code, '') as sku_code,
      s.lot_id, coalesce(lo.lot_code, '') as lot_code, s.status,
      s.current_location_id, loc.warehouse_id as location_warehouse_id,
      coalesce(loc.code, '') as location_code, coalesce(loc.name, '') as location_name,
      s.note, coalesce(mc.cnt, 0) as movement_count,
      s.last_movement_at, s.created_at, s.updated_at
      """;

  private static final String MOVEMENT_COLUMNS =
      """
      m.id, m.serial_id, m.serial_number, m.operation_id, m.movement_kind,
      m.from_status, m.to_status, m.from_location_id, m.to_location_id,
      m.from_lot_id, m.to_lot_id, m.ref_type, m.ref_id, m.actor_id, m.note,
      m.occurred_at, m.created_at,
      coalesce(fl.code, '') as from_location_code, coalesce(tl.code, '') as to_location_code,
      coalesce(flo.lot_code, '') as from_lot_code, coalesce(tlo.lot_code, '') as to_lot_code
      """;

  private static final String MOVEMENT_FROM =
      """
      left join warehouse_location fl on fl.tenant_id = m.tenant_id and fl.id = m.from_location_id
      left join warehouse_location tl on tl.tenant_id = m.tenant_id and tl.id = m.to_location_id
      left join inventory_lot flo on flo.tenant_id = m.tenant_id and flo.id = m.from_lot_id
      left join inventory_lot tlo on tlo.tenant_id = m.tenant_id and tlo.id = m.to_lot_id
      """;

  private static String profileConditions(SerialQuery q, List<Object> where) {
    var sb = new StringBuilder("1=1");
    if (q.skuId() != null) {
      sb.append(" and s.sku_id = ?");
      where.add(q.skuId());
    }
    if (q.warehouseId() != null) {
      // 只匹配**当前有库位**的号：已售/在途/报废的号不属于任何仓库，
      // 「上海仓现在有哪些号」不该列出一堆已经卖掉的号。
      sb.append(" and loc.warehouse_id = ?");
      where.add(q.warehouseId());
    }
    if (q.locationId() != null) {
      sb.append(" and s.current_location_id = ?");
      where.add(q.locationId());
    }
    if (q.lotId() != null) {
      sb.append(" and s.lot_id = ?");
      where.add(q.lotId());
    }
    if (q.status() != null && !q.status().isBlank()) {
      sb.append(" and s.status = ?");
      where.add(SerialStatus.parse(q.status()).name());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      sb.append(
          " and (s.serial_number ilike ? or coalesce(k.code, '') ilike ?"
              + " or coalesce(lo.lot_code, '') ilike ?)");
      var pattern = "%" + q.keyword().trim() + "%";
      where.add(pattern);
      where.add(pattern);
      where.add(pattern);
    }
    return sb.toString();
  }

  private static String movementConditions(SerialMovementQuery q, List<Object> where) {
    var sb = new StringBuilder("1=1");
    if (q.serialId() != null) {
      sb.append(" and m.serial_id = ?");
      where.add(q.serialId());
    }
    if (q.operationId() != null) {
      sb.append(" and m.operation_id = ?");
      where.add(q.operationId());
    }
    if (q.skuId() != null) {
      sb.append(" and s.sku_id = ?");
      where.add(q.skuId());
    }
    if (q.kind() != null && !q.kind().isBlank()) {
      sb.append(" and m.movement_kind = ?");
      where.add(SerialMovementKind.parse(q.kind()).name());
    }
    if (q.refType() != null && !q.refType().isBlank()) {
      sb.append(" and m.ref_type = ?");
      where.add(q.refType());
    }
    if (q.refId() != null) {
      sb.append(" and m.ref_id = ?");
      where.add(q.refId());
    }
    if (q.keyword() != null && !q.keyword().isBlank()) {
      sb.append(
          " and (m.serial_number ilike ? or coalesce(tl.code, '') ilike ?"
              + " or coalesce(fl.code, '') ilike ? or coalesce(tlo.lot_code, '') ilike ?)");
      var pattern = "%" + q.keyword().trim() + "%";
      where.add(pattern);
      where.add(pattern);
      where.add(pattern);
      where.add(pattern);
    }
    return sb.toString();
  }

  /** 排序白名单。未知值 400 —— 把用户输入拼进 ORDER BY 是一条真实的注入面。 */
  private static String orderBy(String sort) {
    var value = sort == null || sort.isBlank() ? "RECENT" : sort.trim().toUpperCase(Locale.ROOT);
    return switch (value) {
      case "SERIAL_NUMBER" -> " s.serial_number asc, s.id";
      case "STATUS" -> " s.status asc, s.serial_number asc, s.id";
      case "LOCATION" -> " loc.code asc nulls last, s.serial_number asc, s.id";
      case "MOVED" -> " s.last_movement_at desc nulls last, s.id";
      case "RECENT" -> " s.updated_at desc, s.id";
      default -> throw new IllegalArgumentException("不支持的排序键：" + sort + "，可选：" + SORTS);
    };
  }

  // ---------------------------------------------------------------- 取值与映射

  private static SerialProfile profile(Record r) {
    var status = SerialStatus.valueOf(r.get("status", String.class));
    return new SerialProfile(
        r.get("id", UUID.class),
        r.get("serial_number", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        status,
        status.label(),
        r.get("current_location_id", UUID.class),
        r.get("location_warehouse_id", UUID.class),
        r.get("location_code", String.class),
        r.get("location_name", String.class),
        r.get("note", String.class),
        r.get("movement_count", Long.class),
        moment(r, "last_movement_at"),
        moment(r, "created_at"),
        moment(r, "updated_at"));
  }

  private static SerialMovementRow movement(Record r) {
    var kind = SerialMovementKind.valueOf(r.get("movement_kind", String.class));
    var from = SerialStatus.valueOf(r.get("from_status", String.class));
    var to = SerialStatus.valueOf(r.get("to_status", String.class));
    return new SerialMovementRow(
        r.get("id", UUID.class),
        r.get("serial_id", UUID.class),
        r.get("serial_number", String.class),
        r.get("operation_id", UUID.class),
        kind,
        kind.label(),
        from,
        to,
        r.get("from_location_id", UUID.class),
        r.get("from_location_code", String.class),
        r.get("to_location_id", UUID.class),
        r.get("to_location_code", String.class),
        r.get("from_lot_id", UUID.class),
        r.get("from_lot_code", String.class),
        r.get("to_lot_id", UUID.class),
        r.get("to_lot_code", String.class),
        r.get("ref_type", String.class),
        r.get("ref_id", UUID.class),
        r.get("actor_id", UUID.class),
        r.get("note", String.class),
        moment(r, "occurred_at"),
        moment(r, "created_at"));
  }

  private static SerialOperationRecord operation(Record r) {
    var direction = SerialMovementDirection.valueOf(r.get("direction", String.class));
    return new SerialOperationRecord(
        r.get("id", UUID.class),
        direction,
        direction.label(),
        r.get("sku_id", UUID.class),
        r.get("lot_id", UUID.class),
        r.get("warehouse_id", UUID.class),
        r.get("location_id", UUID.class),
        r.get("ref_type", String.class),
        r.get("ref_id", UUID.class),
        r.get("declared_quantity", BigDecimal.class),
        r.get("serial_count", Long.class),
        r.get("actor_id", UUID.class),
        r.get("note", String.class),
        moment(r, "occurred_at"),
        moment(r, "created_at"));
  }

  private static SerialIssue issue(
      SerialIssueCode code, String number, String message, String expected, String actual) {
    return new SerialIssue(code, code.label(), message, number, expected, actual);
  }

  /**
   * 取时间值。
   *
   * <p>纯文本语句里 jOOQ 拿不到列类型，返回的可能是 {@link OffsetDateTime}、 {@link java.time.Instant} 或 {@link
   * java.sql.Timestamp}，直接 {@code get(field, OffsetDateTime.class)} 会在拿到 Instant 时抛
   * ClassCastException （与 7.1.2.1 / 7.1.2.2 同一处理）。
   */
  private static OffsetDateTime moment(Record r, String field) {
    Object value = r.get(field);
    if (value == null) {
      return null;
    }
    if (value instanceof OffsetDateTime offset) {
      return offset;
    }
    if (value instanceof java.time.Instant instant) {
      return instant.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    if (value instanceof java.sql.Timestamp timestamp) {
      return timestamp.toInstant().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
    }
    return OffsetDateTime.parse(value.toString());
  }

  private static final SerialQuery EMPTY_QUERY =
      new SerialQuery(null, null, null, null, null, null, null, 0, 50);
  private static final SerialMovementQuery EMPTY_MOVEMENT_QUERY =
      new SerialMovementQuery(null, null, null, null, null, null, null, 0, 50);
  private static final SerialOperationQuery EMPTY_OPERATION_QUERY =
      new SerialOperationQuery(null, null, null, null, 0, 50);
}
