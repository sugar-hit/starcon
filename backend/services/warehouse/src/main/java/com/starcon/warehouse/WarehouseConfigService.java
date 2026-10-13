package com.starcon.warehouse;

import static com.starcon.warehouse.WarehouseConfigModels.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 8.1.1.3 仓库配置。
 *
 * <p>蓝图原文：「配置仓库行为参数：是否支持批次/序列号、拣货策略（按订单/按波次/按库位）、
 * 默认复核比例、是否允许拆零发货、工作时段与班次。」以及关系段的红线：
 * 「**配置影响作业方式**，不是纯展示项，修改需记录变更。」
 *
 * <p>本类是 8.1.1 三个兄弟里**第一个写数据**的（8.1.1.1 与 8.1.1.2 都是纯聚合视图），
 * 所以三条纪律在这里成立得最重：
 *
 * <ul>
 *   <li><b>档案与行为是两组字段。</b>本端点只改行为参数（批次/序列号、拣货策略、
 *       复核比例、拆零、班次），碰不到名称、地址、时区与状态 —— 那属 8.1.1.2。
 *       「改配置」这个动作不应该有能力顺手改到地址去。
 *   <li><b>改了就必须留痕。</b>蓝图那句「修改需记录变更」是硬要求，所以每次写入都在
 *       同一事务里逐字段写 {@code warehouse_config_change}。留痕与写入原子：
 *       事务回滚时两边一起消失，不会出现「配置没改成但留痕说改了」。
 *   <li><b>归档是终态。</b>归档的仓不允许再改配置（与 8.1.1.2 同一纪律），
 *       归档前留下的配置与班次**原样保留** —— 归档的是仓库不是它的作业参数。
 * </ul>
 *
 * <p>租户谓词在每条 SQL 上显式出现两次防线：第一道是 RLS 策略
 * （{@code app.current_tenant}，V1 与本迁移 §4 建立），第二道是服务层的
 * {@code tenant_id = current_setting(...)}。忘了绑定上下文时第二道让查询当场返回空
 * 而不是全表（与 8.1.1.1 同一理由）。
 */
@Service
public class WarehouseConfigService {

  /** 单页最大行数。变更历史会长，但它不是明细流水 —— 给一个明确的上限而不是无界。 */
  static final int MAX_PAGE_SIZE = 200;

  /** 未配置过时对外呈现的默认复核比例。与 V104 的 DEFAULT 逐字一致。 */
  static final BigDecimal DEFAULT_REVIEW_RATIO = new BigDecimal("0");

  /** 留痕里时间的固定格式。值本身是规范化文本，这样排序与比对有确定语义。 */
  private static final DateTimeFormatter VALUE_TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

  private static final String TENANT = "tenant_id = current_setting('app.current_tenant', true)::uuid";

  private final DSLContext sql;

  public WarehouseConfigService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 配置全貌

  /**
   * 配置页一次取全：仓库标识（给班次标时区用）+ 当前配置 + 班次清单 + 最近一页变更历史
   * + 下游影响声明。
   *
   * <p>「一次返回」而不是四个端点：这是一个**编辑页**，四个请求会让页面在
   * 数据版本不一致时把「班次是改过的、配置是没改过的」拼在一起显示。
   */
  public ConfigOverview overview(UUID warehouseId, ChangeQuery query) {
    WarehouseIdentity identity = requireWarehouse(warehouseId);
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 20 : query.size()));
    int page = Math.max(0, query.page() == null ? 0 : query.page());
    return new ConfigOverview(
        identity.code(),
        identity.name(),
        identity.timezone(),
        identity.status(),
        identity.archived(),
        // 归档是终态：归档后不允许再改任何配置，所以「能不能改」直接由状态决定。
        !identity.archived(),
        config(warehouseId),
        shifts(warehouseId),
        changes(warehouseId, new ChangeQuery(query.scope(), query.shiftId(), page, size)),
        impacts(),
        "配置影响下游作业方式而不是界面显示；每次修改都会逐字段写入变更历史"
            + "（append-only，运行角色与表 owner 都改不掉）。班次存的是当地墙上时刻，"
            + "本仓时区 " + identity.timezone() + "。");
  }

  /** 仓库本身的档案字段。只读三条，与 8.1.1.2 的修改逻辑无关。 */
  private record WarehouseIdentity(
      String code, String name, String timezone, String status) {
    boolean archived() {
      return "ARCHIVED".equals(status);
    }
  }

  private WarehouseIdentity requireWarehouse(UUID warehouseId) {
    var r =
        sql.fetchOne(
            "select code,name,timezone,status from warehouse where " + TENANT + " and id=?",
            warehouseId);
    // 不存在与不属于本租户都 404：两者可观测结果必须相同，
    // 否则「猜 id」成了探测别的租户有没有仓库的侧信道（与 8.1.1.2 同一理由）。
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "仓库不存在");
    return new WarehouseIdentity(
        r.get("code", String.class),
        r.get("name", String.class),
        r.get("timezone", String.class),
        r.get("status", String.class));
  }

  // ================================================================ 当前配置

  /**
   * 当前生效配置。
   *
   * <p>**没有配置行时返回默认值，并如实说 {@code unconfigured=true}** ——
   * 不自动建行。自动建行会让「这个仓的复核比例是 0」与「这个仓还没配过复核比例」
   * 在界面上分不开，而这两件事对运营是完全不同的：后者意味着作业可能正按
   * 拣货策略的默认值在跑。
   */
  public WarehouseConfig config(UUID warehouseId) {
    // 仓库不存在与不属于本租户都 404（与 8.1.1.2 同一纪律）：
    // 不这么做的话，猜一个别的租户的仓库 id 会拿到一份「未配置」的默认配置，
    // 而那个仓库可能配着完全不同的拣货策略。
    requireWarehouse(warehouseId);
    var r =
        sql.fetchOne(
            """
            select lot_tracking_enabled, serial_tracking_enabled, picking_strategy,
                   default_review_ratio, allow_split_shipment, note,
                   updated_by_membership_id, created_at, updated_at
              from warehouse_config
             where %s and warehouse_id=?
            """
                .formatted(TENANT),
            warehouseId);
    if (r == null) {
      return new WarehouseConfig(
          warehouseId,
          false,
          false,
          PickingStrategy.ORDER,
          DEFAULT_REVIEW_RATIO,
          false,
          true,
          "",
          null,
          null,
          null);
    }
    return new WarehouseConfig(
        warehouseId,
        Boolean.TRUE.equals(r.get("lot_tracking_enabled", Boolean.class)),
        Boolean.TRUE.equals(r.get("serial_tracking_enabled", Boolean.class)),
        PickingStrategy.valueOf(r.get("picking_strategy", String.class)),
        r.get("default_review_ratio", BigDecimal.class),
        Boolean.TRUE.equals(r.get("allow_split_shipment", Boolean.class)),
        false,
        r.get("note", String.class),
        r.get("updated_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  // ================================================================ 修改配置

  /**
   * 修改仓库行为参数。
   *
   * <p>应用层校验给出可读错误，DB 层仍有 CHECK 与外键兜底 —— 两层都要在，各挡各的：
   * 前者挡用户，后者和别的车道一样挡 SQL。
   *
   * <p>留痕与写入在**同一个事务**里（{@code AccessService.run} 已开事务），
   * 所以「配置改了但留痕没写」与「留痕写了但配置没改」都不会发生。
   *
   * @param actorId 已认证成员 id —— 取自 {@code AccessService.Access.userId()}，
   *     **不接受请求参数传入**：调用方传 id 等于允许把变更记到别人头上
   */
  public WarehouseConfigSaved update(UUID warehouseId, WarehouseConfigInput input, UUID actorId) {
    WarehouseIdentity identity = requireWarehouse(warehouseId);
    if (identity.archived())
      throw new IllegalArgumentException("仓库已归档，不能再修改配置：归档是终态，没有回退动作");

    PickingStrategy strategy = strategy(input.pickingStrategy());
    BigDecimal ratio = reviewRatio(input.defaultReviewRatio());
    boolean lot = Boolean.TRUE.equals(input.lotTrackingEnabled());
    boolean serial = Boolean.TRUE.equals(input.serialTrackingEnabled());
    boolean split = Boolean.TRUE.equals(input.allowSplitShipment());
    String note = input.note() == null ? "" : input.note().trim();

    WarehouseConfig before = config(warehouseId);

    sql.execute(
        """
        insert into warehouse_config(tenant_id,warehouse_id,lot_tracking_enabled,
            serial_tracking_enabled,picking_strategy,default_review_ratio,allow_split_shipment,
            note,updated_by_membership_id)
        values(current_setting('app.current_tenant', true)::uuid,?,?,?,?,?,?,?,?)
        on conflict (tenant_id,warehouse_id) do update
           set lot_tracking_enabled=excluded.lot_tracking_enabled,
               serial_tracking_enabled=excluded.serial_tracking_enabled,
               picking_strategy=excluded.picking_strategy,
               default_review_ratio=excluded.default_review_ratio,
               allow_split_shipment=excluded.allow_split_shipment,
               note=excluded.note,
               updated_by_membership_id=excluded.updated_by_membership_id,
               updated_at=now()
        """,
        warehouseId, lot, serial, strategy.name(), ratio, split, note, actorId);

    // 逐字段留痕。真的是「变了」才写 —— new == old 的一行不是变更，是噪声，
    // 它会让「这个仓改过几次」变成一个被无意义提交灌水的数字。
    List<ConfigChange> written =
        new ArrayList<>(
            recordChanges(
                warehouseId, "CONFIG", null, null, before, lot, serial, strategy, ratio, split,
                note, actorId));

    WarehouseConfig after = config(warehouseId);
    return new WarehouseConfigSaved(after, written.size(), written);
  }

  /**
   * 逐字段比较并写留痕。
   *
   * <p>值统一**规范化文本**：布尔 {@code "true"/"false"}、比例固定 6 位小数、
   * 策略用枚举名、备注原样（空白归一成空串，否则「备注是空」与「备注是空格」
   * 会在留痕里永远不相等）。
   */
  private List<ConfigChange> recordChanges(
      UUID warehouseId,
      String scope,
      UUID shiftId,
      String shiftCode,
      WarehouseConfig before,
      boolean lot,
      boolean serial,
      PickingStrategy strategy,
      BigDecimal ratio,
      boolean split,
      String note,
      UUID actorId) {
    List<ConfigChange> written = new ArrayList<>();
    String changeNote = "";
    // unconfigured=true 时「旧值」是 null（之前没有这个值），不是默认值 ——
    // 首次配置与「配成和默认一样」在审计上必须能分开。
    boolean existed = !before.unconfigured();

    written.addAll(
        field(warehouseId, scope, shiftId, shiftCode, "lotTrackingEnabled",
            existed ? Boolean.toString(before.lotTrackingEnabled()) : null,
            Boolean.toString(lot), changeNote, actorId));
    written.addAll(
        field(warehouseId, scope, shiftId, shiftCode, "serialTrackingEnabled",
            existed ? Boolean.toString(before.serialTrackingEnabled()) : null,
            Boolean.toString(serial), changeNote, actorId));
    written.addAll(
        field(warehouseId, scope, shiftId, shiftCode, "pickingStrategy",
            existed ? before.pickingStrategy().name() : null,
            strategy.name(), changeNote, actorId));
    written.addAll(
        field(warehouseId, scope, shiftId, shiftCode, "defaultReviewRatio",
            existed ? ratioText(before.defaultReviewRatio()) : null,
            ratioText(ratio), changeNote, actorId));
    written.addAll(
        field(warehouseId, scope, shiftId, shiftCode, "allowSplitShipment",
            existed ? Boolean.toString(before.allowSplitShipment()) : null,
            Boolean.toString(split), changeNote, actorId));
    written.addAll(
        field(warehouseId, scope, shiftId, shiftCode, "note",
            existed ? before.note() : null, note, changeNote, actorId));
    return written;
  }

  // ================================================================ 班次

  /**
   * 班次清单。
   *
   * <p>按 {@code enabled} 倒序 + 编码排序：启用中的排前面（那是要排的班），
   * 停用的沉底但**仍然列出来** —— 「停用的班次」与「这个仓从没有夜班」
   * 在界面上必须能分开，否则排班的人会以为夜班从来没排过。
   */
  public List<ShiftRow> shifts(UUID warehouseId) {
    return sql.fetch(
            """
            select id, code, name, start_local, end_local, crosses_midnight, enabled, note,
                   created_by_membership_id, created_at, updated_at
              from warehouse_shift
             where %s and warehouse_id=?
             order by enabled desc, code
            """
                .formatted(TENANT),
            warehouseId)
        .map(WarehouseConfigService::shiftRow);
  }

  /**
   * 新建班次。
   *
   * <p>跨夜由服务端按墙上时刻算出，**不接受调用方传入** —— 调用方自己判断会出现
   * 「22:00-06:00 被算成不跨夜」，而那会让时长算成负数。
   */
  public ShiftSaved createShift(UUID warehouseId, ShiftInput input, UUID actorId) {
    WarehouseIdentity identity = requireWarehouse(warehouseId);
    if (identity.archived())
      throw new IllegalArgumentException("仓库已归档，不能再修改班次：归档是终态，没有回退动作");

    String code = input.code().trim().toUpperCase(Locale.ROOT);
    String name = input.name().trim();
    if (code.isEmpty()) throw new IllegalArgumentException("班次编码不能为空");
    if (name.isEmpty()) throw new IllegalArgumentException("班次名称不能为空");

    LocalTime start = localTime(input.startLocal(), "上班时刻");
    LocalTime end = localTime(input.endLocal(), "下班时刻");
    if (start.equals(end))
      throw new IllegalArgumentException(
          "上班与下班时刻不能相同：相同会被当成一个跨夜 24 小时的班，那几乎肯定是录错了");
    boolean crosses = start.isAfter(end);
    boolean enabled = input.enabled() == null || input.enabled();
    String note = input.note() == null ? "" : input.note().trim();

    UUID id = Ids.next();
    try {
      sql.execute(
          """
          insert into warehouse_shift(tenant_id,warehouse_id,id,code,name,start_local,end_local,
              crosses_midnight,enabled,note,created_by_membership_id)
          values(current_setting('app.current_tenant', true)::uuid,?,?,?,?,?,?,?,?,?,?)
          """,
          warehouseId, id, code, name, start, end, crosses, enabled, note, actorId);
    } catch (org.springframework.dao.DuplicateKeyException e) {
      // Spring 的 DefaultExceptionTranslatorExecuteListener 把 jOOQ 异常翻成
      // org.springframework.dao.*（与 8.1.1.1 同一手法，接错类型的表现是 catch 永不执行）。
      throw new IllegalArgumentException("班次编码 " + code + " 在本仓已存在");
    }

    List<ConfigChange> written =
        new ArrayList<>(
            field(warehouseId, "SHIFT", id, code, "code", null, code, note, actorId));
    written.addAll(field(warehouseId, "SHIFT", id, code, "name", null, name, note, actorId));
    written.addAll(
        field(warehouseId, "SHIFT", id, code, "startLocal", null, VALUE_TIME.format(start), note, actorId));
    written.addAll(
        field(warehouseId, "SHIFT", id, code, "endLocal", null, VALUE_TIME.format(end), note, actorId));
    written.addAll(
        field(warehouseId, "SHIFT_SCHEDULE", id, code, "enabled",
            null, Boolean.toString(enabled), note, actorId));

    return new ShiftSaved(shift(id, warehouseId), written.size(), written);
  }

  /** 改班次（整份替换，含启用状态）。 */
  public ShiftSaved updateShift(UUID warehouseId, UUID shiftId, ShiftUpdateInput input, UUID actorId) {
    WarehouseIdentity identity = requireWarehouse(warehouseId);
    if (identity.archived())
      throw new IllegalArgumentException("仓库已归档，不能再修改班次：归档是终态，没有回退动作");

    var current = requireShift(warehouseId, shiftId);
    String name = input.name().trim();
    if (name.isEmpty()) throw new IllegalArgumentException("班次名称不能为空");
    LocalTime start = localTime(input.startLocal(), "上班时刻");
    LocalTime end = localTime(input.endLocal(), "下班时刻");
    if (start.equals(end))
      throw new IllegalArgumentException(
          "上班与下班时刻不能相同：相同会被当成一个跨夜 24 小时的班，那几乎肯定是录错了");
    boolean crosses = start.isAfter(end);
    boolean enabled = Boolean.TRUE.equals(input.enabled());
    String note = input.note() == null ? "" : input.note().trim();

    sql.execute(
        """
        update warehouse_shift
           set name=?, start_local=?, end_local=?, crosses_midnight=?, enabled=?, note=?,
               updated_at=now()
         where %s and warehouse_id=? and id=?
        """
            .formatted(TENANT),
        name, start, end, crosses, enabled, note, warehouseId, shiftId);

    String code = current.get("code", String.class);
    String fieldNote = note;
    List<ConfigChange> written =
        new ArrayList<>(
            field(warehouseId, "SHIFT", shiftId, code, "name",
                current.get("name", String.class), name, fieldNote, actorId));
    written.addAll(
        field(warehouseId, "SHIFT", shiftId, code, "startLocal",
            VALUE_TIME.format(current.get("start_local", LocalTime.class)),
            VALUE_TIME.format(start), fieldNote, actorId));
    written.addAll(
        field(warehouseId, "SHIFT", shiftId, code, "endLocal",
            VALUE_TIME.format(current.get("end_local", LocalTime.class)),
            VALUE_TIME.format(end), fieldNote, actorId));
    // 启用状态单列一条（scope=SHIFT_SCHEDULE）：停用一个班次与改动它的上下班时刻
    // 是两种不同的作业影响，混在一条里会让复盘分不清。
    written.addAll(
        field(warehouseId, "SHIFT_SCHEDULE", shiftId, code, "enabled",
            Boolean.toString(Boolean.TRUE.equals(current.get("enabled", Boolean.class))),
            Boolean.toString(enabled), fieldNote, actorId));
    written.addAll(
        field(warehouseId, "SHIFT", shiftId, code, "note",
            current.get("note", String.class), note, fieldNote, actorId));

    return new ShiftSaved(shift(shiftId, warehouseId), written.size(), written);
  }

  /**
   * 班次行映射。时长由起止时刻算出，跨夜班会算出大于 1440 的值 ——
   * 界面上不该让用户自己算减法。
   */
  private static ShiftRow shiftRow(org.jooq.Record r) {
    LocalTime start = r.get("start_local", LocalTime.class);
    LocalTime end = r.get("end_local", LocalTime.class);
    long minutes = ChronoUnit.MINUTES.between(start, end);
    if (minutes <= 0) minutes += 24 * 60;   // 跨夜班：加上一天
    return new ShiftRow(
        r.get("id", UUID.class),
        r.get("code", String.class),
        r.get("name", String.class),
        start,
        end,
        Boolean.TRUE.equals(r.get("crosses_midnight", Boolean.class)),
        minutes,
        Boolean.TRUE.equals(r.get("enabled", Boolean.class)),
        r.get("note", String.class),
        r.get("created_by_membership_id", UUID.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("updated_at", OffsetDateTime.class));
  }

  private org.jooq.Record requireShift(UUID warehouseId, UUID shiftId) {
    var r =
        sql.fetchOne(
            """
            select id, code, name, start_local, end_local, enabled, note
              from warehouse_shift
             where %s and warehouse_id=? and id=?
            """
                .formatted(TENANT),
            warehouseId,
            shiftId);
    if (r == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "班次不存在");
    return r;
  }

  private ShiftRow shift(UUID shiftId, UUID warehouseId) {
    return shifts(warehouseId).stream()
        .filter(s -> s.id().equals(shiftId))
        .findFirst()
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "班次不存在"));
  }

  // ================================================================ 变更留痕

  /**
   * 变更历史。真分页 + 筛选（范围 / 班次）+ 确定次序。
   *
   * <p>排序是 {@code changed_at desc, id}：只按时间排会在同毫秒内不确定，
   * 而没有确定次序的分页会漏行或重复。
   */
  public ChangePage changes(UUID warehouseId, ChangeQuery query) {
    requireWarehouse(warehouseId);
    int size = Math.min(MAX_PAGE_SIZE, Math.max(1, query.size() == null ? 20 : query.size()));
    int page = Math.max(0, query.page() == null ? 0 : query.page());

    var where = new ArrayList<String>();
    var binds = new ArrayList<Object>();
    // 列名带表别名前缀：行查询 left join 了 warehouse_shift，两张表都有 tenant_id，
    // 不限定就会出现 "column reference tenant_id is ambiguous"（而不是安静地查错表）。
    where.add("c.tenant_id = current_setting('app.current_tenant', true)::uuid");
    where.add("c.warehouse_id = ?");
    binds.add(warehouseId);
    if (query.scope() != null && !query.scope().isBlank()) {
      String scope = query.scope().trim().toUpperCase(Locale.ROOT);
      if (!SCOPES.contains(scope)) throw new IllegalArgumentException("不支持的留痕范围：" + query.scope());
      where.add("c.scope = ?");
      binds.add(scope);
    }
    if (query.shiftId() != null) {
      where.add("c.shift_id = ?");
      binds.add(query.shiftId());
    }
    String clause = " where " + String.join(" and ", where);

    long total =
        sql.fetchOne(
                "select count(*) from warehouse_config_change c" + clause, binds.toArray())
            .get(0, Long.class);

    var rows =
        sql.fetch(
                """
                select c.id, c.scope, c.shift_id, s.code as shift_code, c.field_name,
                       c.old_value, c.new_value, c.note, c.changed_by_membership_id, c.changed_at
                  from warehouse_config_change c
                  left join warehouse_shift s
                    on s.tenant_id=c.tenant_id and s.id=c.shift_id
                """
                    + clause
                    + " order by c.changed_at desc, c.id limit ? offset ?",
                withTail(binds, size, page * size))
            .map(r -> change(r));

    return new ChangePage(rows, total, page, size);
  }

  private static ConfigChange change(org.jooq.Record r) {
    return new ConfigChange(
        r.get("id", UUID.class),
        r.get("scope", String.class),
        r.get("shift_id", UUID.class),
        r.get("shift_code", String.class),
        r.get("field_name", String.class),
        r.get("old_value", String.class),
        r.get("new_value", String.class),
        r.get("note", String.class),
        r.get("changed_by_membership_id", UUID.class),
        r.get("changed_at", OffsetDateTime.class));
  }

  /** 留痕范围的受控词表，与 V104 的 CHECK 逐字一致。 */
  private static final List<String> SCOPES = List.of("CONFIG", "SHIFT", "SHIFT_SCHEDULE");

  /**
   * 写一条留痕。
   *
   * <p>old == new 时**不写**：那种行不是变更，是噪声。返回空列表让调用方
   * 自然地少一条 —— 「本次变更 N 条」这个数才有意义。
   */
  private List<ConfigChange> field(
      UUID warehouseId,
      String scope,
      UUID shiftId,
      String shiftCode,
      String fieldName,
      String oldValue,
      String newValue,
      String note,
      UUID actorId) {
    if (java.util.Objects.equals(oldValue, newValue)) return List.of();
    UUID id = Ids.next();
    sql.execute(
        """
        insert into warehouse_config_change(tenant_id,warehouse_id,id,scope,shift_id,
            field_name,old_value,new_value,note,changed_by_membership_id)
        values(current_setting('app.current_tenant', true)::uuid,?,?,?,?,?,?,?,?,?)
        """,
        warehouseId, id, scope, shiftId, fieldName, oldValue, newValue, note, actorId);
    return List.of(
        new ConfigChange(
            id,
            scope,
            shiftId,
            shiftCode,
            fieldName,
            oldValue,
            newValue,
            note,
            actorId,
            // 写完立刻读回真实时刻：留痕的 changed_at 是 DB 的 now()，
            // 服务端时钟与 DB 时钟不同时，读回比让调用方猜更可靠。
            sql.fetchOne(
                    "select changed_at from warehouse_config_change where %s and id=?"
                        .formatted(TENANT),
                    id)
                .get(0, OffsetDateTime.class)));
  }

  // ================================================================ 能力声明与下拉项

  /**
   * 配置对下游作业的影响。
   *
   * <p>蓝图说「影响 8.3 作业行为」，但 8.3.2 拣货与 8.3.3 复核**尚未交付**
   * （本服务还没有拣货任务表与复核任务表）。这里逐条说清哪一项现在真的会生效、
   * 哪一项要等下游接上 —— 而不是笼统写一句「影响 8.3」，
   * 那会让人以为拣货已经按这个策略在跑。
   */
  public List<ConfigImpact> impacts() {
    return List.of(
        new ConfigImpact(
            "BATCH_TRACKING", "批次追溯", false, "8.2.1.1",
            "收货单（8.2.1.1，V70）已交付但**没有批次字段**：它不读本配置。"
                + "批次追溯要等收货录入侧接上开关后才会生效。"),
        new ConfigImpact(
            "SERIAL_TRACKING", "序列号追溯", false, "7.1.1.2",
            "inventory_serial（V58）已建表但**没有 warehouse_id**："
                + "序列号在数据层就还没有归属到仓，本配置给不了它归属。"),
        new ConfigImpact(
            "PICKING_STRATEGY", "拣货策略", false, "8.3.2",
            "拣货任务表不存在，本服务还没有按策略分派任务的地方。"
                + "配置已落库并留痕，8.3.2 落地后直接读当前态。"),
        new ConfigImpact(
            "REVIEW_RATIO", "默认复核比例", false, "8.3.3",
            "复核任务表不存在。比例已落库并钉在 [0,1]，8.3.3 落地后读当前态。"),
        new ConfigImpact(
            "SPLIT_SHIPMENT", "拆零发货", false, "8.3.4",
            "发货交接（8.3.4）尚未交付，拆零在下游没有落点。"
                + "开关已落库，允许拆零前请确认下游已支持。"),
        new ConfigImpact(
            "SHIFT_SCHEDULE", "工作时段与班次", true, "8.1.1.3",
            "本模块自身已交付：班次可增改、时长与跨夜由服务端算、可停用，"
                + "每次改动逐字段留痕。"));
  }

  /** 筛选与录入下拉项。 */
  public ConfigOptions options(UUID warehouseId) {
    requireWarehouse(warehouseId);
    return new ConfigOptions(
        Arrays.stream(PickingStrategy.values())
            .map(s -> new PickingStrategyOption(s.name(), s.label(), s.note()))
            .toList(),
        shifts(warehouseId).stream()
            .map(s -> new ShiftOption(s.id(), s.code(), s.name(), s.enabled()))
            .toList(),
        List.of(
            new ScopeOption("CONFIG", "作业参数"),
            new ScopeOption("SHIFT", "班次字段"),
            new ScopeOption("SHIFT_SCHEDULE", "班次启用状态")),
        "默认复核比例是 0~1 的比例值：0 = 不复核，1 = 全量复核。"
            + "它是**默认**——单次作业可以覆盖它，但「这个仓默认复核几成」是仓自己的属性。",
        "拆零发货默认关闭。拆零会破掉「一单一包裹」这个下游默认假设"
            + "（面单、装箱、运费都按整单算），所以要拆零必须显式打开。",
        "班次存的是**当地墙上时刻**，具体某天的实际时刻按本仓时区推算。"
            + "上班时刻晚于下班时刻表示跨夜班（如 22:00 → 06:00），时长会跨到次日。");
  }

  // ================================================================ 校验辅助

  /** 拣货策略词表。三选一，非法值给可读错误（400），不静默取默认值。 */
  static PickingStrategy strategy(String raw) {
    try {
      return PickingStrategy.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "不支持的拣货策略：" + raw + "（应为 ORDER / WAVE / LOCATION 三选一）");
    }
  }

  /**
   * 默认复核比例。
   *
   * <p>区间在这里**再**校验一遍而不是只靠 V104 的 CHECK：CHECK 报出来的是
   * SQLSTATE 23514，被 ApiErrorHandler 翻成一句「数据与既有记录冲突」，
   * 用户看不出是自己填了 1.5。
   */
  static BigDecimal reviewRatio(BigDecimal raw) {
    if (raw == null) throw new IllegalArgumentException("默认复核比例不能为空");
    if (raw.signum() < 0 || raw.compareTo(BigDecimal.ONE) > 0) {
      throw new IllegalArgumentException("默认复核比例必须在 0 到 1 之间：" + raw.toPlainString());
    }
    // 归一到 6 位小数（NUMERIC(9,6) 的精度）。不归一会让留痕里
    // 「0.05」与「0.050000」变成两个不同的串，而它们其实是同一个比例。
    return raw.setScale(6, java.math.RoundingMode.HALF_UP);
  }

  /**
   * 解析班次时刻（{@code HH:mm} 或 {@code HH:mm:ss}）。
   *
   * <p>解析不了给可读错误。刻意**不**兜底成「现在几点」或某个默认值 ——
   * 悄悄改掉用户填的时刻等于让排班表上出现一个谁都没填过的班次。
   */
  static LocalTime localTime(String raw, String field) {
    String text = raw == null ? "" : raw.trim();
    if (text.isEmpty()) throw new IllegalArgumentException(field + "不能为空");
    try {
      return LocalTime.parse(text.length() == 5 ? text + ":00" : text);
    } catch (DateTimeParseException e) {
      throw new IllegalArgumentException(field + "格式不正确：" + raw + "（应为 HH:mm 或 HH:mm:ss）");
    }
  }

  /** 比例的规范化文本：固定 6 位小数，让「相同比例」在留痕里只有一个写法。 */
  static String ratioText(BigDecimal value) {
    return value.setScale(6, java.math.RoundingMode.HALF_UP).toPlainString();
  }

  private static Object[] withTail(List<Object> binds, Object... tail) {
    Object[] all = new Object[binds.size() + tail.length];
    for (int i = 0; i < binds.size(); i++) all[i] = binds.get(i);
    for (int i = 0; i < tail.length; i++) all[binds.size() + i] = tail[i];
    return all;
  }
}
