package com.starcon.catalog;

import static com.starcon.catalog.PhysicalSafetyModels.*;
import static com.starcon.catalog.PhysicalSafetyRules.*;

import com.starcon.tenancy.Ids;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 3.7.2.1 物理与安全属性：物理参数 + 安全属性的读写、派生计算、按属性筛选与渠道判定。
 *
 * <p>蓝图原文：「职责：维护裸品物理参数（长宽高、净重、毛重、包装方式）与安全属性
 * （是否带电、是否液体、是否危险品、是否磁性），决定可走哪些物流渠道与是否需要特殊申报。」
 * 「输出：SKU 物理档案；供 10 物流渠道筛选、8 拣货装箱体积核算。」
 * 「下游 = 10 物流（渠道适配、体积重）、16 合规（危险品与认证）。」
 *
 * <p><b>与 3.1.2.2 的分工</b>（V28 第一节）：物理参数列在 {@code sku_package} 上，
 * 本类**读写同一张表**，不另建表把它们抄一遍。安全属性在 {@code sku_safety_attribute}。
 * 两张表都是 catalog 自有，单租户内一次事务写入。
 *
 * <p><b>本类不落任何派生值</b>：体积、体积重、计费重、渠道判定、装箱件数全部现算
 * （{@link PhysicalSafetyRules}）。体积重的除数是<b>渠道口径</b>，落库等于替 10 物流选除数。
 *
 * <p><b>权限口径</b>：读 {@code catalog.compliance.read}、写 {@code catalog.compliance.update}。
 * 选 compliance 而不是 sku 的理由：3.7 在蓝图里是「商品合规属性」一级（页面资源
 * {@code catalog.compliance}「合规与资质」），而本页回答的是「能不能发、要不要申报」——
 * 这是合规判定而不是商品资料编辑。物理参数与安全属性在<b>同一个端点</b>里写，
 * 所以两者用同一套权限，不出现「能改尺寸却改不了安全属性」的半截状态。
 */
@Service
public class PhysicalSafetyService {
  /** 单页最大行数（筛选列表页）。 */
  static final int MAX_PAGE_SIZE = 100;

  /** 装箱核算的行数上限：一次算几十万行既慢又没人核对，宁可报错。 */
  static final int MAX_CARTON_LINES = 500;

  private final DSLContext sql;

  public PhysicalSafetyService(DSLContext sql) {
    this.sql = sql;
  }

  // ============================================================ 读取

  /** 读单个 SKU 的物理档案（物理参数 + 安全属性 + 派生值）。 */
  public PhysicalSafetyProfile profile(UUID skuId, BigDecimal divisor) {
    Record row = skuRow(skuId);
    return assemble(row, safetyRow(skuId), divisor);
  }

  /**
   * 一条 SQL 取出 SKU 身份与 {@code sku_package} 物理参数。
   *
   * <p>取不到就 404：RLS 已过滤跨租户的行，不区分「不存在」与「没权限」，
   * 用 403 区分等于给探测的人一个存在性预言机。
   */
  private Record skuRow(UUID skuId) {
    Record row =
        sql.fetchOne(
            "select s.tenant_id,s.id,s.code,s.name,p.code as spu_code,"
                + "k.length_mm,k.width_mm,k.height_mm,k.net_weight_g,k.gross_weight_g,"
                + "k.packaging_type,k.version as packaging_version"
                + " from sku s"
                + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
                + " left join sku_package k on k.tenant_id=s.tenant_id and k.sku_id=s.id"
                + " where s.id=?",
            skuId);
    if (row == null) throw CatalogService.missing();
    return row;
  }

  /** 安全属性行；{@code null} = 未采集（V28 规则 B2）。 */
  private Record safetyRow(UUID skuId) {
    return sql.fetchOne(
        "select contains_battery,contains_liquid,dangerous_goods,is_magnetic,note,version"
            + " from sku_safety_attribute where sku_id=?",
        skuId);
  }

  /**
   * 组装档案：物理参数 + 安全属性 + 派生值。
   *
   * <p>{@code safetyCollected} 取「有没有那一行」，<b>不是</b>「四个标记是否都为 false」——
   * 后者会把「没填」渲染成「无特殊属性」，而渠道判定据此放行（规则 R2 的漏洞）。
   */
  private PhysicalSafetyProfile assemble(Record row, Record safety, BigDecimal divisor) {
    BigDecimal volume = volumeCm3(
        row.get("length_mm", BigDecimal.class),
        row.get("width_mm", BigDecimal.class),
        row.get("height_mm", BigDecimal.class));
    BigDecimal volumetric = volumetricWeightKg(volume, divisor);
    BigDecimal chargeable =
        chargeableWeightKg(row.get("gross_weight_g", BigDecimal.class), volumetric);

    boolean collected = safety != null;
    PhysicalSafetyProfile draft =
        new PhysicalSafetyProfile(
            row.get("id", UUID.class),
            row.get("code", String.class),
            row.get("name", String.class),
            row.get("spu_code", String.class),
            row.get("length_mm", BigDecimal.class),
            row.get("width_mm", BigDecimal.class),
            row.get("height_mm", BigDecimal.class),
            row.get("net_weight_g", BigDecimal.class),
            row.get("gross_weight_g", BigDecimal.class),
            row.get("packaging_type", String.class),
            collected,
            collected && Boolean.TRUE.equals(safety.get("contains_battery", Boolean.class)),
            collected && Boolean.TRUE.equals(safety.get("contains_liquid", Boolean.class)),
            collected && Boolean.TRUE.equals(safety.get("dangerous_goods", Boolean.class)),
            collected && Boolean.TRUE.equals(safety.get("is_magnetic", Boolean.class)),
            collected ? safety.get("note", String.class) : "",
            collected ? safety.get("version", Long.class) : null,
            volume,
            divisor,
            volumetric,
            chargeable,
            List.of(),
            List.of(),
            volume != null);
    // 派生列表统一从 Rules 走，保证档案里的 activeFlags 与渠道判定用的是同一套。
    return new PhysicalSafetyProfile(
        draft.skuId(),
        draft.skuCode(),
        draft.skuName(),
        draft.spuCode(),
        draft.lengthMm(),
        draft.widthMm(),
        draft.heightMm(),
        draft.netWeightG(),
        draft.grossWeightG(),
        draft.packagingType(),
        draft.safetyCollected(),
        draft.containsBattery(),
        draft.containsLiquid(),
        draft.dangerousGoods(),
        draft.isMagnetic(),
        draft.safetyNote(),
        draft.safetyVersion(),
        draft.volumeCm3(),
        draft.divisorCm3PerKg(),
        draft.volumetricWeightKg(),
        draft.chargeableWeightKg(),
        activeFlags(draft),
        requiredDeclarations(draft),
        draft.hasVolume());
  }

  // ============================================================ 写入

  /**
   * 保存物理参数与安全属性（一次提交写两张表）。
   *
   * <p><b>安全属性未勾选任何一项时删除该行</b>，回到「未采集」：这与「确认什么都不带」
   * 是两件事（V28 规则 B2）。要让渠道放行，运营得显式勾一次「已采集、都不带」——
   * 界面上那是一个独立的确认动作（{@code safetyCollected=true} + 四个 false）。
   */
  public PhysicalSafetySaved save(
      AccessService.Access actor, UUID skuId, PhysicalSafetyInput input) {
    Record row = skuRow(skuId);
    UUID tenantId = row.get("tenant_id", UUID.class);
    validate(input);
    boolean packagingChanged = writePackaging(actor, skuId, input);
    writeSafety(actor, skuId, tenantId, input);
    audit(actor, skuId, "SkuPhysicalSafetySaved");
    return new PhysicalSafetySaved(profile(skuId, null), packagingChanged);
  }

  /**
   * 校验入参：毛重不小于净重、精度上限、安全标记与「已采集」的组合。
   *
   * <p><b>「已勾选某个标记但没勾已采集」直接拒</b>：那是一个自相矛盾的提交，
   * 放行就会出现「渠道看到带电、运营以为自己没填」的判定分裂。
   */
  private static void validate(PhysicalSafetyInput input) {
    BigDecimal net = input.netWeightG();
    BigDecimal gross = input.grossWeightG();
    CatalogService.check(
        net == null || gross == null || gross.compareTo(net) >= 0, "毛重不能小于净重");
    // 逐个判空而不是 List.of(...)：List.of 不接受 null，而这些列「没填」正是 null。
    for (BigDecimal value :
        java.util.Arrays.asList(
            input.lengthMm(),
            input.widthMm(),
            input.heightMm(),
            input.netWeightG(),
            input.grossWeightG())) {
      if (value != null) {
        CatalogService.check(value.signum() >= 0, "尺寸与重量不能为负数");
        CatalogService.check(value.scale() <= 6, "尺寸与重量最多 6 位小数");
      }
    }
    boolean anyFlag =
        input.containsBattery() || input.containsLiquid() || input.dangerousGoods() || input.isMagnetic();
    CatalogService.check(
        !anyFlag || input.safetyCollected(), "勾选了安全标记就必须同时确认「已采集安全属性」");
    String note = input.safetyNote() == null ? "" : input.safetyNote().trim();
    CatalogService.check(note.length() <= 1000, "安全属性备注最多 1000 字");
    String type = input.packagingType() == null ? "" : input.packagingType().trim();
    CatalogService.check(type.length() <= 64, "包装方式最多 64 字");
  }

  /**
   * 写物理参数：{@code sku_package} 的 {@code ON CONFLICT (tenant_id,sku_id)} 原地更新。
   *
   * <p>与 3.1.2.2 详情页写的是<b>同一张表同一组列</b>，所以两处的约束也必须一致：
   * 正负与「毛重不小于净重」由 V6 的 CHECK 兜底，这里先给可读的中文错误。
   * 全空则删除该行，让「没有维护包装」在数据上是真的没有。
   */
  private boolean writePackaging(AccessService.Access actor, UUID skuId, PhysicalSafetyInput input) {
    String type = input.packagingType() == null ? "" : input.packagingType().trim();
    boolean empty =
        type.isEmpty()
            && input.lengthMm() == null
            && input.widthMm() == null
            && input.heightMm() == null
            && input.netWeightG() == null
            && input.grossWeightG() == null;
    if (empty) return sql.execute("delete from sku_package where sku_id=?", skuId) > 0;

    // 乐观锁：V6 的 sku_package 有 version 列，两个页面同时改包装时后到的撞 409。
    int updated =
        sql.execute(
            "update sku_package set length_mm=?,width_mm=?,height_mm=?,net_weight_g=?,"
                + "gross_weight_g=?,packaging_type=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and sku_id=? and version=?",
            input.lengthMm(),
            input.widthMm(),
            input.heightMm(),
            input.netWeightG(),
            input.grossWeightG(),
            type,
            actor.tenantId(),
            skuId,
            input.packagingVersion());
    if (updated == 1) return true;
    // 没有这一行时 version 记为 0，插入路径不带 CAS（此时并发插入由唯一约束挡住）。
    int inserted =
        sql.execute(
            "insert into sku_package(id,tenant_id,sku_id,length_mm,width_mm,height_mm,"
                + "net_weight_g,gross_weight_g,packaging_type,version)"
                + " values(?,?,?,?,?,?,?,?,?,0)"
                + " on conflict (tenant_id,sku_id) do nothing",
            Ids.next(),
            actor.tenantId(),
            skuId,
            input.lengthMm(),
            input.widthMm(),
            input.heightMm(),
            input.netWeightG(),
            input.grossWeightG(),
            type);
    if (inserted == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "包装尺寸重量已被他人修改，请刷新后重试");
    return true;
  }

  /**
   * 写安全属性。
   *
   * <p>{@code safetyCollected=false} 时删除该行：回到「未采集」状态。
   * {@code safetyCollected=true} 但四项全 false 时<b>保留行</b> —— 那正是
   * 「已采集、确认什么都不带」，是让渠道能放行的那个状态。
   */
  private void writeSafety(
      AccessService.Access actor, UUID skuId, UUID tenantId, PhysicalSafetyInput input) {
    if (!input.safetyCollected()) {
      sql.execute("delete from sku_safety_attribute where tenant_id=? and sku_id=?", tenantId, skuId);
      return;
    }
    String note = input.safetyNote() == null ? "" : input.safetyNote().trim();
    int updated =
        sql.execute(
            "update sku_safety_attribute set contains_battery=?,contains_liquid=?,"
                + "dangerous_goods=?,is_magnetic=?,note=?,version=version+1,updated_at=now()"
                + " where tenant_id=? and sku_id=? and version=?",
            input.containsBattery(),
            input.containsLiquid(),
            input.dangerousGoods(),
            input.isMagnetic(),
            note,
            tenantId,
            skuId,
            input.safetyVersion());
    if (updated == 1) return;
    if (input.safetyVersion() != 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "安全属性已被他人修改，请刷新后重试");
    int inserted =
        sql.execute(
            "insert into sku_safety_attribute(id,tenant_id,sku_id,contains_battery,contains_liquid,"
                + "dangerous_goods,is_magnetic,note,version)"
                + " values(?,?,?,?,?,?,?,?,0)"
                + " on conflict (tenant_id,sku_id) do nothing",
            Ids.next(),
            tenantId,
            skuId,
            input.containsBattery(),
            input.containsLiquid(),
            input.dangerousGoods(),
            input.isMagnetic(),
            note);
    if (inserted == 0)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "安全属性已被他人修改，请刷新后重试");
  }

  // ============================================================ 筛选

  /** 按安全属性筛选 SKU 列表（供 10 物流渠道筛选）。 */
  public PhysicalSafetyPage search(PhysicalSafetyQuery query) {
    UUID tenantId = currentTenantId();
    int page = Math.max(0, query.page());
    int size = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);

    StringBuilder where = new StringBuilder(" where s.tenant_id=?");
    List<Object> binds = new ArrayList<>();
    binds.add(tenantId);

    String keyword = normalizeKeyword(query.keyword());
    if (keyword != null) {
      where.append(" and (lower(s.code) like ? or lower(s.name) like ?)");
      String like = "%" + keyword + "%";
      binds.add(like);
      binds.add(like);
    }
    // 四个标记都支持筛 false：运营会问「哪些货不是危险品」。
    if (query.containsBattery() != null) {
      where.append(flagFilter("a.contains_battery", query.containsBattery()));
      binds.add(query.containsBattery());
    }
    if (query.containsLiquid() != null) {
      where.append(flagFilter("a.contains_liquid", query.containsLiquid()));
      binds.add(query.containsLiquid());
    }
    if (query.dangerousGoods() != null) {
      where.append(flagFilter("a.dangerous_goods", query.dangerousGoods()));
      binds.add(query.dangerousGoods());
    }
    if (query.isMagnetic() != null) {
      where.append(flagFilter("a.is_magnetic", query.isMagnetic()));
      binds.add(query.isMagnetic());
    }
    if (query.safetyCollected() != null) {
      where.append(query.safetyCollected() ? " and a.id is not null" : " and a.id is null");
    }
    if (query.requiresDeclaration() != null) {
      where.append(
          query.requiresDeclaration()
              ? " and (a.contains_battery or a.contains_liquid or a.dangerous_goods or a.is_magnetic)"
              : " and a.id is not null and not (a.contains_battery or a.contains_liquid"
                  + " or a.dangerous_goods or a.is_magnetic)");
    }
    String packagingType = normalizePackagingType(query.packagingType());
    if (packagingType != null) {
      where.append(" and k.packaging_type=?");
      binds.add(packagingType);
    }

    // LEFT JOIN 安全属性与包装：筛选「未采集」的行必须能出现在结果里。
    String from =
        " from sku s"
            + " join spu p on p.tenant_id=s.tenant_id and p.id=s.spu_id"
            + " left join sku_safety_attribute a on a.tenant_id=s.tenant_id and a.sku_id=s.id"
            + " left join sku_package k on k.tenant_id=s.tenant_id and k.sku_id=s.id";
    long total =
        sql.fetchOne("select count(*)" + from + where, binds.toArray()).get(0, Long.class);
    if (total == 0) return new PhysicalSafetyPage(List.of(), page, size, 0, 0);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(size);
    pageBinds.add((long) page * size);
    var rows =
        sql.fetch(
            "select s.id,s.code,s.name,p.code as spu_code,"
                + "a.contains_battery,a.contains_liquid,a.dangerous_goods,a.is_magnetic,"
                + "a.id as safety_id,k.length_mm,k.width_mm,k.height_mm,k.gross_weight_g,"
                + "k.packaging_type,greatest(s.updated_at,k.updated_at,a.updated_at) as profile_updated_at"
                + from
                + where
                + " order by profile_updated_at desc nulls last,s.id desc limit ? offset ?",
            pageBinds.toArray());

    List<PhysicalSafetyRow> items = new ArrayList<>(rows.size());
    for (Record r : rows) {
      boolean collected = r.get("safety_id", UUID.class) != null;
      PhysicalSafetyProfile draft =
          new PhysicalSafetyProfile(
              r.get("id", UUID.class), r.get("code", String.class), r.get("name", String.class),
              r.get("spu_code", String.class), null, null, null, null,
              r.get("gross_weight_g", BigDecimal.class), r.get("packaging_type", String.class),
              collected,
              collected && Boolean.TRUE.equals(r.get("contains_battery", Boolean.class)),
              collected && Boolean.TRUE.equals(r.get("contains_liquid", Boolean.class)),
              collected && Boolean.TRUE.equals(r.get("dangerous_goods", Boolean.class)),
              collected && Boolean.TRUE.equals(r.get("is_magnetic", Boolean.class)),
              "", null, null, null, null, null, List.of(), List.of(), false);
      items.add(
          new PhysicalSafetyRow(
              draft.skuId(),
              draft.skuCode(),
              draft.skuName(),
              draft.spuCode(),
              collected,
              draft.containsBattery(),
              draft.containsLiquid(),
              draft.dangerousGoods(),
              draft.isMagnetic(),
              flagsText(draft),
              volumeCm3(
                  r.get("length_mm", BigDecimal.class),
                  r.get("width_mm", BigDecimal.class),
                  r.get("height_mm", BigDecimal.class)),
              draft.grossWeightG(),
              draft.packagingType(),
              requiredDeclarations(draft),
              r.get("profile_updated_at", OffsetDateTime.class) == null
                  ? null
                  : r.get("profile_updated_at", OffsetDateTime.class).toInstant().toString()));
    }
    return new PhysicalSafetyPage(
        List.copyOf(items), page, size, total, (int) ((total + size - 1) / size));
  }

  /** 三个标记共用同一句 WHERE 形状；筛 false 时必须同时要求「已采集」。 */
  private static String flagFilter(String column, boolean value) {
    return value ? " and " + column + "=?" : " and " + column + " is not null and " + column + "=?";
  }

  // ============================================================ 渠道判定

  /**
   * 按调用方给的渠道限制判定这个 SKU 能不能走（规则 R2 + R3）。
   *
   * <p>本方法<b>不查渠道表</b>：渠道与限制是 10 物流的事实。本模块提供的是判定本身。
   */
  public ChannelVerdictView verdict(UUID skuId, ChannelVerdictInput input) {
    CatalogService.check(
        input.divisorCm3PerKg() == null || input.divisorCm3PerKg().signum() > 0,
        "体积重除数必须大于 0");
    String channel = input.channelCode() == null ? "" : input.channelCode().trim();
    CatalogService.check(!channel.isEmpty() && channel.length() <= 64, "请填写渠道标识（最多 64 字）");

    PhysicalSafetyProfile view = profile(skuId, input.divisorCm3PerKg());
    // 显式限定：本类的 verdict(UUID, ...) 方法会遮蔽静态导入的同名纯函数。
    var result =
        PhysicalSafetyRules.verdict(
            view,
            new ChannelPolicy(
                channel,
                Boolean.TRUE.equals(input.allowBattery()),
                Boolean.TRUE.equals(input.allowLiquid()),
                Boolean.TRUE.equals(input.allowDangerousGoods()),
                Boolean.TRUE.equals(input.allowMagnetic()),
                Boolean.TRUE.equals(input.requirePhysicalParameters())));
    return new ChannelVerdictView(
        view.skuId(),
        view.skuCode(),
        result.channelCode(),
        result.shippable(),
        result.blockers(),
        result.blockers().stream().map(PhysicalSafetyRules::blockerLabel).toList(),
        result.declarations(),
        result.declarations().stream().map(PhysicalSafetyRules::declarationLabel).toList(),
        view.volumeCm3(),
        input.divisorCm3PerKg(),
        view.volumetricWeightKg(),
        view.chargeableWeightKg(),
        verdictNote(view, input.divisorCm3PerKg()));
  }

  /** 判定附注：把「为什么算不出来」讲清楚，页面不靠猜。 */
  private static String verdictNote(PhysicalSafetyProfile profile, BigDecimal divisor) {
    if (!profile.safetyCollected())
      return "安全属性还没采集：渠道判定按「不知道」处理并拒发，确认后即可放行。";
    if (divisor == null)
      return "未提供体积重除数，本次不给体积重（除数是渠道口径，由调用方决定）。";
    if (!profile.hasVolume()) return "缺长宽高，算不出体积与体积重。";
    return "体积重按调用方给的除数 "
        + divisor.toPlainString()
        + " cm³/kg 计算；计费重取实重与体积重的较大者。";
  }

  // ============================================================ 装箱体积核算

  /**
   * 装箱体积核算（供 8 拣货）：给一组 SKU 行与箱规，算每箱可装几件、共需几箱。
   *
   * <p>件数取<b>体积与重量两条约束里更紧的那个</b>（{@link PhysicalSafetyRules#carton}）。
   * 同一 SKU 出现多行时<b>合并</b>再算：分两行各算一次会得到两个各自成立的箱数，
   * 而真实的拣货是把这几行装进同一批箱子。
   */
  public CartonCalcView cartonCalc(CartonCalcInput input) {
    CatalogService.check(
        input.lines() != null && !input.lines().isEmpty(), "至少要有一行 SKU");
    CatalogService.check(
        input.lines().size() <= MAX_CARTON_LINES, "一次最多核算 " + MAX_CARTON_LINES + " 行");
    CatalogService.check(
        input.cartonVolumeCm3().signum() > 0, "每箱可用容积必须大于 0");
    CatalogService.check(input.cartonMaxGrossWeightG().signum() > 0, "每箱限重必须大于 0");

    Map<UUID, BigDecimal> quantities = new LinkedHashMap<>();
    for (CartonLineInput line : input.lines()) {
      CatalogService.check(
          line.quantity() != null && line.quantity().signum() > 0, "每行数量必须大于 0");
      quantities.merge(line.skuId(), line.quantity(), BigDecimal::add);
    }

    List<CartonLineView> lines = new ArrayList<>(quantities.size());
    int totalCartons = 0;
    BigDecimal totalVolume = BigDecimal.ZERO;
    for (Map.Entry<UUID, BigDecimal> entry : quantities.entrySet()) {
      Record row = skuRow(entry.getKey());
      BigDecimal unitVolume =
          volumeCm3(
              row.get("length_mm", BigDecimal.class),
              row.get("width_mm", BigDecimal.class),
              row.get("height_mm", BigDecimal.class));
      var result =
          carton(
              row.get("code", String.class),
              unitVolume,
              row.get("net_weight_g", BigDecimal.class),
              row.get("gross_weight_g", BigDecimal.class),
              input.cartonVolumeCm3(),
              input.cartonMaxGrossWeightG());
      BigDecimal quantity = entry.getValue();
      BigDecimal lineVolume =
          unitVolume == null ? null : unitVolume.multiply(quantity);
      int cartons = result.unitsPerCarton() <= 0 ? 0 : ceilDivide(quantity, result.unitsPerCarton());
      lines.add(
          new CartonLineView(
              row.get("id", UUID.class),
              result.skuCode(),
              quantity,
              unitVolume,
              result.netWeightG(),
              result.grossWeightG(),
              result.unitsPerCartonByVolume(),
              result.unitsPerCartonByWeight(),
              result.unitsPerCarton(),
              result.limitedByWeight(),
              cartons,
              lineVolume,
              result.note()));
      totalCartons += cartons;
      if (lineVolume != null) totalVolume = totalVolume.add(lineVolume);
    }

    BigDecimal utilization =
        input.cartonVolumeCm3().signum() > 0
            ? totalVolume
                .multiply(new BigDecimal("100"))
                .divide(input.cartonVolumeCm3().multiply(new BigDecimal(Math.max(1, totalCartons))), 2, RoundingMode.HALF_UP)
            : null;
    return new CartonCalcView(
        List.copyOf(lines),
        input.cartonVolumeCm3(),
        input.cartonMaxGrossWeightG(),
        totalCartons,
        totalVolume,
        utilization,
        "每箱件数取体积与限重两条约束里更紧的那个；"
            + "体积利用率 = 总体积 ÷（箱数 × 每箱容积），是所有行的合计口径。");
  }

  /** 向上取整的整数除法：数量 5 件、每箱 2 件 → 3 箱（不能是 2 箱装不下第 5 件）。 */
  private static int ceilDivide(BigDecimal quantity, int per) {
    return quantity.divide(new BigDecimal(per), 0, RoundingMode.CEILING).intValueExact();
  }

  // ============================================================ 公共

  private void audit(AccessService.Access actor, UUID resourceId, String action) {
    sql.execute(
        "insert into audit_event(id,tenant_id,actor_id,resource_id,action) values(?,?,?,?,?)",
        Ids.next(), actor.tenantId(), actor.userId(), resourceId, action);
  }

  UUID currentTenantId() {
    return sql.fetchOne("select nullif(current_setting('app.current_tenant',true),'')::uuid")
        .get(0, UUID.class);
  }
}
