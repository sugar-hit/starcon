package com.starcon.trade;

import static com.starcon.trade.QualityInspectionModels.*;
import static com.starcon.trade.QualityInspectionRules.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 6.4.2.1 质检单。
 *
 * <p>蓝图原话：「来料/成品质检：抽样比例、检验项（外观、功能、规格、包装、认证标识）、检验结果记录、
 * 不合格判定与扣量。」输入「收货事件；质检模板（按类目）；抽样规则；质检结果录入」。
 * 输出「质检单（合格量/不合格量/让步接收量）；触发 7 库存的质量状态分流」。
 *
 * <p><b>本模块只做一件事：把「这批货到底行不行、有多少行」变成一份有凭据的三量结论。</b>
 *
 * <p><b>十条实现纪律</b>：
 *
 * <ol>
 *   <li><b>到货量来自上游，不来自客户端</b>：读 8.2.1.1 收货单（授权只读端口），
 *       实收量写进 {@code arrived_quantity}；客户端只能指定「本次检哪一批」（{@code lotSize}）。</li>
 *   <li><b>抽样是服务端算的</b>：比例 × 批量 + 夹逼 + 舍入口径，算式原文随单落库，
 *       前端传的抽样数一律不认。</li>
 *   <li><b>三量互斥且完备</b>：合格 + 不合格 + 让步接收 = 本次质检覆盖的批量，DB 层 CHECK 强制。</li>
 *   <li><b>逐项结论驱动扣量</b>：没有一个凭空的「总分」；不合格判定与扣量都落在逐项结论上。</li>
 *   <li><b>让步接收要权限 + 原因</b>：两者缺一拒绝；且记录人**必须真的持有那个权限码**。</li>
 *   <li><b>危险品必须验认证标识</b>：3.7.2.1 读到「需要认证」而模板里没有认证标识检验项 ⇒ 完结被拒。</li>
 *   <li><b>分流只记意图，不假装已分流</b>：warehouse 当前没有可用的授权写端点 ⇒
 *       {@code disposition_status} 只能是 {@code NOT_DISPATCHED} / {@code UNAVAILABLE}。</li>
 *   <li><b>跨服务只经授权 HTTP</b>：收货（warehouse）与安全属性（catalog）都只经只读端口读，
 *       零跨库 FK / 视图 / JOIN。</li>
 *   <li><b>终态不可改写</b>：已完成的质检单不再接受结论录入，三量也不再可改（DB 触发器 + 服务层）。</li>
 *   <li><b>时间一律 UTC 口径</b>：{@code timestamptz} 列用 {@code OffsetDateTime} 绑定，
 *       values 位置写 {@code ?::timestamptz}；业务日历用 {@code date}。</li>
 * </ol>
 */
@Service
public class QualityInspectionService {

  /** 数量刻度：与 DB 的 numeric(19,6) 对齐。 */
  private static final int QUANTITY_SCALE = 6;

  /** 让步接收需要的权限码 —— 记录人必须真的持有它。 */
  public static final String CONCESSION_PERMISSION = "trade.quality-inspection.concession";

  /**
   * 质检单的查询列。
   *
   * <p>结论条数、让步条数、关键项是否失败都用**子查询**取，而不是在 Java 里逐单再查一遍 ——
   * 列表页 50 行就是 50 次额外往返，而且「列表显示的关键项失败 = false、详情显示 true」
   * 这种不一致最难查。
   */
  private static final String ORDER_SELECT =
      "select o.*,"
          + " (select count(*) from quality_inspection_order_item i"
          + "  where i.tenant_id=o.tenant_id and i.order_id=o.id) as item_count,"
          + " (select count(*) from quality_inspection_concession c"
          + "  where c.tenant_id=o.tenant_id and c.order_id=o.id) as concession_count,"
          + " (select count(*) from quality_inspection_order_item i"
          + "  where i.tenant_id=o.tenant_id and i.order_id=o.id"
          + "    and i.critical and i.result='FAIL') as critical_failure_count"
          + " from quality_inspection_order o";

  private final DSLContext sql;
  private final QualityInspectionWarehousePort warehouse;
  private final QualityInspectionCatalogPort catalog;

  public QualityInspectionService(
      DSLContext sql, QualityInspectionWarehousePort warehouse, QualityInspectionCatalogPort catalog) {
    this.sql = sql;
    this.warehouse = warehouse;
    this.catalog = catalog;
  }

  // ================================================================ 能力声明

  /**
   * 本模块的能力与上下游可用性。
   *
   * <p>存在的理由：收货在 warehouse、安全属性在 catalog、库存分流在 warehouse ——
   * 哪一个接通了必须由接口自己报出。特别是<b>分流</b>：质检单判完之后，
   * 「这批不合格的货到底有没有进待检 bucket」是本模块最要紧的一个事实，
   * 而 warehouse 当前<b>没有</b>可用的授权写端点 ⇒ 如实标 {@code available=false}。
   */
  public IncomingQualityInspectionCapabilities capabilities() {
    List<IncomingCapabilityItem> upstream =
        List.of(
            new IncomingCapabilityItem(
                "GOODS_RECEIPT",
                "8.2.1.1 收货单（warehouse 服务）",
                true,
                null,
                null,
                "收货事件读 warehouse 的授权只读接口"
                    + "（GET /warehouse/goods-receipts + GET /warehouse/goods-receipts/{id}）。"
                    + "到货量由服务端读上游后写入，不信客户端。"
                    + "⚠️ 已知事实：warehouse 当前**不接受 PURCHASE 来源的收货单**"
                    + "（8.2.1.1 自己的能力声明里写明「6 采购与供应尚未交付」），"
                    + "所以来料质检目前只能挂在已有的收货单上；6.3 采购单交付后这条限制才会解除。"),
            new IncomingCapabilityItem(
                "PHYSICAL_SAFETY",
                "3.7.2.1 物理安全属性（catalog 服务）",
                true,
                null,
                null,
                "读 catalog 的授权只读接口（GET /catalog/physical-safety/skus/{skuId}）。"
                    + "带电池 / 液体 / 危险品 / 磁性的 SKU 必须验认证标识 —— "
                    + "模板里没有认证标识检验项时，完结质检单会被拒。"),
            new IncomingCapabilityItem(
                "TEMPLATE",
                "质检模板（按类目）",
                true,
                null,
                null,
                "模板由本模块自带（quality_inspection_template / _item 两张表，租户可维护）。"
                    + "类目是 catalog 的主数据，本库只存逻辑 ID + 名称快照，不建跨库外键。"));

    List<IncomingCapabilityItem> downstream =
        List.of(
            new IncomingCapabilityItem(
                "INVENTORY_DISPOSITION",
                "7 库存的质量状态分流（待检 / 残次 bucket）",
                false,
                "7.1.1.2 库存明细（bucket） / 8.2.1.2 收货差异处理",
                "质检判完之后，不合格量**没有**真的被分流到待检 / 残次 bucket —— "
                    + "warehouse 侧当前没有可用的授权写端点（唯一的质量状态写入口是 "
                    + "8.2.1.2 的**差异处置决定**，它必须挂在一条真实的收货差异上，"
                    + "不能拿来给一整批正常货做分流）。"
                    + "因此本模块只把分流**意图**（disposition）落库并把 disposition_status 标成"
                    + "UNAVAILABLE；**绝不出现 DISPATCHED 却其实没分流**的假记录。"
                    + "影响：可售量口径（7.2.2.1）在本模块交付后仍看不到本模块的扣量 —— "
                    + "要等 warehouse 提供批量质量状态写端点后，7 库存才会把待检 / 残次量扣出可售量。",
                "warehouse 服务未提供可用的授权写端点。"),
            new IncomingCapabilityItem(
                "DEFECTIVE_DISPOSITION",
                "6.4.2.2 不良品处理",
                false,
                "6.4.2.2",
                "不合格品的处置（退换货补货 / 返修 / 报废 / 转促销品）不在本模块范围内。"
                    + "本模块只登记「让步接收」这一条（蓝图 6.4.2.1 输出句里点名的三量之一），"
                    + "并强制要求原因与权限码 —— 处置流程本身由 6.4.2.2 交付。",
                "6.4.2.2 尚未交付。"),
            new IncomingCapabilityItem(
                "SUPPLIER_RATING",
                "6.1.1.3 供应商评级",
                false,
                "6.1.1.3",
                "本模块产出的合格率与扣量是评级的**输入之一**，但评级模块未交付 ⇒ "
                    + "本模块不产出任何评级结论，也不代它算分。"
                    + "影响：6.1.1.3 交付后，需要由它订阅本模块的质检事实；"
                    + "在此之前质检结论只在本模块与 6.4.2.2 内可见。",
                "6.1.1.3 尚未交付。"),
            new IncomingCapabilityItem(
                "PURCHASE_RECONCILIATION",
                "6.5.1 采购对账",
                false,
                "6.5.1",
                "质检扣量会改变应付金额，但**对账由采购发起、12 财务执行付款** —— "
                    + "本模块只如实记录扣量金额（币种 / 汇率快照 / 单位换算），"
                    + "不代 6.5.1 发起对账，也不代 12 财务确认应付。",
                "6.5.1 尚未交付。"));

    List<IncomingCapabilityItem> actions =
        List.of(
            new IncomingCapabilityItem(
                "CREATE_ORDER",
                "建质检单（按模板算抽样）",
                true,
                null,
                null,
                "到货量读 8 收货；应抽数量按模板规则在服务端算。"),
            new IncomingCapabilityItem(
                "RECORD_ITEM",
                "录入逐项检验结论",
                true,
                null,
                null,
                "外观 / 功能 / 规格 / 包装 / 认证标识，逐项记录并带扣量。"),
            new IncomingCapabilityItem(
                "CONCESSION",
                "让步接收",
                true,
                null,
                null,
                "必须给原因与权限码，且记录人必须真的持有该权限码（"
                    + CONCESSION_PERMISSION
                    + "）。缺一拒绝。"),
            new IncomingCapabilityItem(
                "DISPATCH_DISPOSITION",
                "把质检结论分流到库存待检 / 残次 bucket",
                false,
                "7.1.1.2 / 8.2.1.2",
                "分流意图已落库（disposition），但**没有真的分流**："
                    + "warehouse 没有可用的授权写端点。",
                "warehouse 服务未提供可用的授权写端点。"));

    return new IncomingQualityInspectionCapabilities(
        upstream,
        downstream,
        actions,
        itemCodes(),
        roundingModes(),
        "应抽数量 = 批量 × 抽样比例（0~1 的比率，不是百分数），"
            + "再按最小 / 最大抽样数夹逼、再按批量本身封顶，最后按舍入口径取整。"
            + "整条算式的原文写在每一张质检单的 sampling_basis 里，不靠复算。",
        "合格量 + 不合格量 + 让步接收量 = 本次质检覆盖的批量（lot_size），恒成立。"
            + "让步接收量从扣量合计**里面**出 —— 让步接收是「不合格里被批准放行的那部分」，"
            + "不是把不合格改成合格，所以让步接收量 ≤ 扣量合计（DB 层 CHECK 强制）。",
        "让步接收必须给**原因**与**权限码**（蓝图 6.4.2.2 加粗句的纪律："
            + "让步接收需要权限与原因，不能由质检员随手放行）。"
            + "本模块还会核对记录人是否真的持有该权限码 —— 只写一个码不算放行。",
        "不合格的货**不能直接进可售库存**：质检单把不合格量单列出来，"
            + "并按模板指定去向（待检 / 残次）记下分流意图。"
            + "分流状态 disposition_status 如实报出上游是否真的登记了 —— "
            + "当前 warehouse 无可用写端点，所以只会是 NOT_DISPATCHED / UNAVAILABLE。",
        "本模块的职责边界：建模板 → 建质检单（按模板算抽样）→ 逐项录结论 → "
            + "（必要时）登记让步接收 → 完结出三量 → 记下分流意图 → 导出 CSV 给质检员。"
            + "**处置流程（退换货 / 返修 / 报废 / 转促销品）不在本模块范围内**。");
  }

  // ================================================================ 下拉

  /** 建质检单需要的下拉与词表。 */
  public IncomingQualityInspectionOptions options() {
    List<IncomingTemplateOption> templates = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select t.id,t.code,t.name,t.inspection_type,"
                + " (select count(*) from quality_inspection_template_item i"
                + "  where i.tenant_id=t.tenant_id and i.template_id=t.id) as item_count"
                + " from quality_inspection_template t"
                + " where t.tenant_id = ?::uuid and t.status='ACTIVE'"
                + " order by t.code limit 200",
            currentTenant())) {
      IncomingInspectionType type = IncomingInspectionType.valueOf(row.get("inspection_type", String.class));
      templates.add(
          new IncomingTemplateOption(
              row.get("id", UUID.class),
              row.get("code", String.class),
              row.get("name", String.class),
              type.name(),
              type.label(),
              row.get("item_count", Long.class)));
    }
    return new IncomingQualityInspectionOptions(
        templates,
        itemCodes(),
        itemCodeLabels(),
        orderStatuses(),
        orderStatusLabels(),
        resultCodes(),
        resultLabels(),
        dispositionCodes(),
        dispositionLabels(),
        inspectionTypes(),
        inspectionTypeLabels(),
        roundingModes(),
        roundingModeLabels(),
        "模板只列启用中的；停用的模板不出现在建单下拉里（已开出的质检单仍然带模板快照）。");
  }

  // ================================================================ 模板

  /** 模板列表（真分页 + 真筛选）。 */
  public IncomingTemplatePage pageTemplates(
      String keyword, String inspectionType, String status, int page, int size) {
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    // 条件与绑定值**一起**拼：值空就整段跳过，绑定值也一起跳过 ——
    // 「SQL 少一段、绑定值还留着」会让 jOOQ 把参数绑到别的 ? 上，而且它不报错。
    StringBuilder where = new StringBuilder(" where t.tenant_id = ?::uuid");
    List<Object> args = new ArrayList<>();
    args.add(currentTenant());
    if (keyword != null && !keyword.isBlank()) {
      where.append(" and (t.code ilike ? or t.name ilike ?)");
      args.add("%" + keyword.trim() + "%");
      args.add("%" + keyword.trim() + "%");
    }
    if (inspectionType != null && !inspectionType.isBlank()) {
      where.append(" and t.inspection_type = ?");
      args.add(inspectionType);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and t.status = ?");
      args.add(status);
    }
    List<Record> rows =
        sql.fetch(
            "select t.*,"
                + " (select count(*) from quality_inspection_template_item i"
                + "  where i.tenant_id=t.tenant_id and i.template_id=t.id) as item_count,"
                + " (select count(*) from quality_inspection_template_item i"
                + "  where i.tenant_id=t.tenant_id and i.template_id=t.id and i.critical) as critical_count"
                + " from quality_inspection_template t" + where
                + " order by t.code, t.id limit ? offset ?",
            argsOf(args, limit, offset));
    long total =
        sql.fetchOne(
                "select count(*) from quality_inspection_template t" + where, argsOf(args))
            .get(0, Long.class);
    List<IncomingTemplateRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(templateRow(row));
    }
    return new IncomingTemplatePage(
        items, Math.max(page, 0), limit, total, (int) Math.ceil(total / (double) limit), "");
  }

  /** 模板详情：头 + 检验项。 */
  public IncomingTemplateDetail templateDetail(UUID id) {
    Record row = templateRow(id);
    return new IncomingTemplateDetail(templateRow(row), templateItems(row.get("id", UUID.class)));
  }

  /** 建模板（含检验项）。 */
  @Transactional
  public IncomingTemplateRow createTemplate(IncomingTemplateInput input, UUID actorId) {
    if (input.items().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "质检模板至少要有一个检验项 —— 没有检验项的模板检不出任何东西");
    }
    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    insert(
        "quality_inspection_template",
        List.of(
            "id", "tenant_id", "code", "name", "category_id", "category_name", "inspection_type",
            "sampling_ratio", "min_sample_quantity", "max_sample_quantity", "rounding_mode",
            "critical_failure_action", "defective_target", "status", "note", "created_by",
            "created_at", "updated_at"),
        List.of(
            "?::uuid", "?::uuid", "?::varchar", "?::varchar", "?::uuid", "?::varchar", "?::varchar",
            "?", "?", "?", "?::varchar", "?::varchar", "?::varchar", "?::varchar", "?::varchar",
            "?::uuid", "?::timestamptz", "?::timestamptz"),
        Arrays.asList(
            (Object) id,
            currentTenant(),
            input.code().trim(),
            input.name().trim(),
            input.categoryId(),
            input.categoryName(),
            input.inspectionType().name(),
            scale(input.samplingRatio()),
            scale(input.minSampleQuantity() == null ? BigDecimal.ONE : input.minSampleQuantity()),
            scale(input.maxSampleQuantity()),
            (input.roundingMode() == null ? IncomingRoundingMode.CEILING : input.roundingMode()).name(),
            (input.criticalFailureAction() == null
                    ? IncomingCriticalFailureAction.REJECT_LOT
                    : input.criticalFailureAction())
                .name(),
            (input.defectiveTarget() == null
                    ? IncomingDefectiveTarget.PENDING_INSPECTION
                    : input.defectiveTarget())
                .name(),
            (input.status() == null ? IncomingTemplateStatus.ACTIVE : input.status()).name(),
            input.note() == null ? "" : input.note(),
            actorId,
            now,
            now));
    insertTemplateItems(id, input.items());
    return templateRow(templateRow(id));
  }

  /** 改模板（检验项整组替换；抽样规则改了就只影响**之后**新开的质检单）。 */
  @Transactional
  public IncomingTemplateRow updateTemplate(UUID id, IncomingTemplateInput input, UUID actorId) {
    templateRow(id);
    if (input.items().isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "质检模板至少要有一个检验项 —— 没有检验项的模板检不出任何东西");
    }
    long used =
        sql.fetchOne(
                "select count(*) from quality_inspection_order"
                    + " where tenant_id = ?::uuid and template_id = ?::uuid",
                currentTenant(),
                id)
            .get(0, Long.class);
    sql.execute(
        "update quality_inspection_template set code=?,name=?,category_id=?,category_name=?,"
            + " inspection_type=?,sampling_ratio=?,min_sample_quantity=?,max_sample_quantity=?,"
            + " rounding_mode=?,critical_failure_action=?,defective_target=?,status=?,note=?,updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        input.code().trim(),
        input.name().trim(),
        input.categoryId(),
        input.categoryName(),
        input.inspectionType().name(),
        scale(input.samplingRatio()),
        scale(input.minSampleQuantity() == null ? BigDecimal.ONE : input.minSampleQuantity()),
        scale(input.maxSampleQuantity()),
        (input.roundingMode() == null ? IncomingRoundingMode.CEILING : input.roundingMode()).name(),
        (input.criticalFailureAction() == null
                ? IncomingCriticalFailureAction.REJECT_LOT
                : input.criticalFailureAction())
            .name(),
        (input.defectiveTarget() == null
                ? IncomingDefectiveTarget.PENDING_INSPECTION
                : input.defectiveTarget())
            .name(),
        (input.status() == null ? IncomingTemplateStatus.ACTIVE : input.status()).name(),
        input.note() == null ? "" : input.note(),
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        id);
    // 已被质检单引用时**不允许改检验项** —— 那些单子的结论指向的就是这些检验项，
    // 删掉一个会让「当时查的是哪一项」永远没有答案。
    if (used == 0) {
      sql.execute(
          "delete from quality_inspection_template_item where tenant_id=?::uuid and template_id=?::uuid",
          currentTenant(),
          id);
      insertTemplateItems(id, input.items());
    } else {
      for (IncomingTemplateItemInput item : input.items()) {
        sql.execute(
            "update quality_inspection_template_item set name=?,critical=?,acceptance_criteria=?,"
                + " sample_coverage=?,sort_order=?,active=true,updated_at=?::timestamptz"
                + " where tenant_id=?::uuid and template_id=?::uuid and item_code=?::varchar",
            item.name().trim(),
            Boolean.TRUE.equals(item.critical()),
            item.acceptanceCriteria() == null ? "" : item.acceptanceCriteria(),
            scale(item.sampleCoverage() == null ? BigDecimal.ONE : item.sampleCoverage()),
            item.sortOrder() == null ? 0 : item.sortOrder(),
            OffsetDateTime.now(ZoneOffset.UTC),
            currentTenant(),
            id,
            item.itemCode().name());
      }
    }
    return templateRow(templateRow(id));
  }

  /** 启用 / 停用模板。 */
  public IncomingTemplateRow setTemplateStatus(UUID id, IncomingTemplateStatus status) {
    templateRow(id);
    sql.execute(
        "update quality_inspection_template set status=?,updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        status.name(),
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        id);
    return templateRow(templateRow(id));
  }

  // ================================================================ 质检单

  /**
   * 建质检单。
   *
   * <p><b>到货量来自上游</b>：读 8.2.1.1 收货单的明细行，实收量写进 {@code arrived_quantity}；
   * 客户端只能指定「本次检哪一批」（{@code lotSize}，≤ 到货量）。
   *
   * <p><b>应抽数量在服务端算</b>：按模板的抽样规则算，算式原文随单落库。
   */
  @Transactional
  public IncomingOrderRow createOrder(IncomingOrderInput input, UUID actorId, String bearer) {
    Record template = templateRow(input.templateId());
    if (!"ACTIVE".equals(template.get("status", String.class))) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "质检模板已停用，不能用它开新的质检单");
    }
    BigDecimal arrived = resolveArrivedQuantity(input, bearer);
    BigDecimal lot = input.lotSize() == null ? arrived : scale(input.lotSize());
    if (lot.compareTo(arrived) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "本次质检的批量 " + lot.toPlainString() + " 超过了收货单实收量 " + arrived.toPlainString()
              + "：分批质检时批量只能小于等于到货量");
    }
    IncomingRoundingMode rounding = IncomingRoundingMode.valueOf(template.get("rounding_mode", String.class));
    SamplePlan plan =
        samplePlan(
            lot,
            template.get("sampling_ratio", BigDecimal.class),
            template.get("min_sample_quantity", BigDecimal.class),
            template.get("max_sample_quantity", BigDecimal.class),
            rounding);

    UUID id = UUID.randomUUID();
    String inspectionNo =
        "QI-"
            + LocalDate.now(ZoneOffset.UTC).toString().replace("-", "")
            + "-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    // 3.7.2.1 物理安全属性：只读一次快照，后续不随 catalog 变化。
    String safetyState = IncomingSafetyState.UNKNOWN.name();
    String safetyJson = "{}";
    boolean requiresCertification = false;
    if (input.skuId() != null) {
      QualityInspectionCatalogPort.SafetyProfileView profile = catalog.findProfile(input.skuId(), bearer);
      if (profile == null) {
        safetyState = IncomingSafetyState.NOT_COLLECTED.name();
      } else {
        safetyState = IncomingSafetyState.COLLECTED.name();
        safetyJson = safetyJson(profile);
        requiresCertification = requiresCertification(profile);
      }
    } else {
      safetyState = IncomingSafetyState.UNKNOWN.name();
    }

    insert(
        "quality_inspection_order",
        List.of(
            "id", "tenant_id", "inspection_no", "template_id", "template_code", "template_name",
            "inspection_type", "category_id", "category_name", "sku_id", "sku_code", "sku_name",
            "supplier_id", "supplier_name", "receipt_code", "receipt_line_id", "source_ref_code",
            "batch_no", "arrived_quantity", "lot_size", "sampling_ratio", "min_sample_quantity",
            "max_sample_quantity", "rounding_mode", "sample_quantity", "sampling_basis",
            "sampled_quantity", "qualified_quantity", "defective_quantity", "concession_quantity",
            "gross_deducted_quantity", "status", "result", "disposition", "disposition_status",
            "disposition_note", "currency_code", "exchange_rate", "unit_conversion_factor",
            "unit_price", "deducted_amount", "safety_profile", "safety_requires_certification",
            "safety_profile_state", "note", "conclusion_note", "created_by", "created_at",
            "updated_at"),
        List.of(
            "?::uuid", "?::uuid", "?::varchar", "?::uuid", "?::varchar", "?::varchar", "?::varchar",
            "?::uuid", "?::varchar", "?::uuid", "?::varchar", "?::varchar", "?::uuid", "?::varchar",
            "?::varchar", "?::uuid", "?::varchar", "?::varchar", "?", "?", "?", "?", "?", "?::varchar",
            "?", "?::varchar", "?", "?", "?", "?", "?", "?::varchar", "?::varchar", "?::varchar",
            "?::varchar", "?::varchar", "?::varchar", "?", "?", "?", "?", "?::jsonb", "?::boolean",
            "?::varchar", "?::varchar", "?::varchar", "?::uuid", "?::timestamptz", "?::timestamptz"),
        Arrays.asList(
            (Object) id,
            currentTenant(),
            inspectionNo,
            input.templateId(),
            template.get("code", String.class),
            template.get("name", String.class),
            template.get("inspection_type", String.class),
            template.get("category_id", UUID.class),
            template.get("category_name", String.class),
            input.skuId(),
            input.skuCode().trim(),
            input.skuName(),
            input.supplierId(),
            input.supplierName(),
            input.receiptCode(),
            input.receiptLineId(),
            input.sourceRefCode(),
            input.batchNo(),
            arrived,
            lot,
            plan.ratio(),
            plan.minSample(),
            plan.maxSample(),
            rounding.name(),
            plan.sampleQuantity(),
            plan.basis(),
            plan.sampleQuantity(),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            IncomingOrderStatus.DRAFT.name(),
            // **结论此时还没有** —— 未完结的单子不许带结论（填 0 或占位都是编造）。
            null,
            IncomingDisposition.PENDING_INSPECTION.name(),
            IncomingDispositionStatus.NOT_DISPATCHED.name(),
            "",
            input.currencyCode(),
            input.exchangeRate(),
            input.unitConversionFactor(),
            input.unitPrice(),
            BigDecimal.ZERO.setScale(4, java.math.RoundingMode.HALF_UP),
            safetyJson,
            requiresCertification,
            safetyState,
            input.note() == null ? "" : input.note(),
            "",
            actorId,
            now,
            now));
    return orderRow(orderRow(id));
  }

  /** 质检单列表（真分页 + 真筛选 + 真排序）。 */
  public IncomingOrderPage pageOrders(
      String keyword,
      String status,
      String result,
      String inspectionType,
      String receiptCode,
      String skuCode,
      String sort,
      int page,
      int size) {
    int limit = pageSize(size);
    int offset = Math.max(page, 0) * limit;
    // 条件与绑定值**一起**拼（理由同 pageTemplates）。
    StringBuilder where = new StringBuilder(" where o.tenant_id = ?::uuid");
    List<Object> args = new ArrayList<>();
    args.add(currentTenant());
    if (keyword != null && !keyword.isBlank()) {
      where.append(
          " and (o.inspection_no ilike ? or o.sku_code ilike ? or o.sku_name ilike ?"
              + " or o.template_name ilike ? or o.receipt_code ilike ?)");
      String like = "%" + keyword.trim() + "%";
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
      args.add(like);
    }
    if (status != null && !status.isBlank()) {
      where.append(" and o.status = ?");
      args.add(status);
    }
    if (result != null && !result.isBlank()) {
      where.append(" and o.result = ?");
      args.add(result);
    }
    if (inspectionType != null && !inspectionType.isBlank()) {
      where.append(" and o.inspection_type = ?");
      args.add(inspectionType);
    }
    if (receiptCode != null && !receiptCode.isBlank()) {
      where.append(" and o.receipt_code = ?");
      args.add(receiptCode);
    }
    if (skuCode != null && !skuCode.isBlank()) {
      where.append(" and o.sku_code = ?");
      args.add(skuCode);
    }
    String order =
        switch (sort == null ? "created" : sort) {
          case "quantity" -> " order by o.lot_size desc, o.id";
          case "result" -> " order by o.result nulls last, o.created_at desc, o.id";
          case "completed" -> " order by o.completed_at desc nulls last, o.id";
          default -> " order by o.created_at desc, o.id";
        };
    List<Record> rows =
        sql.fetch(
            ORDER_SELECT + where + order + " limit ? offset ?", argsOf(args, limit, offset));
    long total =
        sql.fetchOne("select count(*) from quality_inspection_order o" + where, argsOf(args))
            .get(0, Long.class);
    List<IncomingOrderRow> items = new ArrayList<>();
    for (Record row : rows) {
      items.add(orderRow(row));
    }
    return new IncomingOrderPage(items, Math.max(page, 0), limit, total, (int) Math.ceil(total / (double) limit), "");
  }

  /** 质检单详情：头 + 逐项结论 + 让步记录 + 缺项 + 分流。 */
  public IncomingOrderDetail orderDetail(UUID id) {
    Record row = orderRow(id);
    UUID templateId = row.get("template_id", UUID.class);
    List<IncomingTemplateItemRow> templateItems = templateItems(templateId);
    List<IncomingOrderItemRow> items = orderItems(id);
    List<String> concluded = new ArrayList<>();
    for (IncomingOrderItemRow item : items) {
      concluded.add(item.itemCode());
    }
    List<String> missingCodes = new ArrayList<>();
    List<String> missingLabels = new ArrayList<>();
    List<IncomingTemplateItemRow> pending = new ArrayList<>();
    for (IncomingTemplateItemRow item : templateItems) {
      if (!concluded.contains(item.itemCode())) {
        missingCodes.add(item.itemCode());
        missingLabels.add(item.itemCodeLabel());
        pending.add(item);
      }
    }
    boolean criticalFailure = false;
    for (IncomingOrderItemRow item : items) {
      if (item.critical() && "FAIL".equals(item.result())) {
        criticalFailure = true;
      }
    }
    return new IncomingOrderDetail(
        orderRow(row),
        items,
        concessions(id),
        missingCodes,
        missingLabels,
        pending,
        row.get("sampling_basis", String.class),
        "合格量 + 不合格量 + 让步接收量 = 本次质检覆盖的批量 "
            + row.get("lot_size", BigDecimal.class).toPlainString()
            + "（本单到货 " + row.get("arrived_quantity", BigDecimal.class).toPlainString() + "）。",
        row.get("disposition_note", String.class),
        safetyNote(row),
        row.get("conclusion_note", String.class),
        actions(row),
        "");
  }

  /**
   * 录入逐项检验结论。
   *
   * <p>结论是<b>可改</b>的（检验过程中会复核），但已完成的质检单不再接受（终态不可改写）。
   */
  @Transactional
  public IncomingOrderItemRow recordItem(UUID id, IncomingOrderItemInput input, UUID actorId) {
    Record order = orderRow(id);
    IncomingOrderStatus status = IncomingOrderStatus.valueOf(order.get("status", String.class));
    if (status.terminal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "质检单已处于终态（" + status.label() + "），不能再录检验结论");
    }
    Record templateItem = templateItemRow(order.get("template_id", UUID.class), input.templateItemId());
    BigDecimal sampled = order.get("sampled_quantity", BigDecimal.class);
    BigDecimal lotSize = order.get("lot_size", BigDecimal.class);
    if (input.sampledQuantity() != null) {
      BigDecimal requested = scale(input.sampledQuantity());
      if (requested.compareTo(sampled) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "实抽数量 " + requested.toPlainString() + " 超过了应抽数量 " + sampled.toPlainString()
                + "：应抽数量是按模板的抽样规则算出来的，不能往上加");
      }
      if (requested.compareTo(lotSize) > 0) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST, "实抽数量不能超过本次质检的批量 " + lotSize.toPlainString());
      }
      sampled = requested;
    }
    BigDecimal failed = scale(input.failedSampleQuantity());
    if (failed.compareTo(sampled) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "判不合格的件数 " + failed.toPlainString() + " 超过了实抽数量 " + sampled.toPlainString());
    }
    BigDecimal deducted = scale(input.deductedQuantity());
    if (deducted.compareTo(lotSize) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "扣量 " + deducted.toPlainString() + " 超过了本次质检的批量 " + lotSize.toPlainString());
    }
    OffsetDateTime inspectedAt =
        input.inspectedAt() == null ? OffsetDateTime.now(ZoneOffset.UTC) : input.inspectedAt();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID tenant = currentTenant();
    UUID itemId = UUID.randomUUID();
    sql.execute(
        "insert into quality_inspection_order_item(id,tenant_id,order_id,template_item_id,item_code,"
            + " item_name,critical,result,failed_sample_quantity,deducted_quantity,defect_type,"
            + " defect_description,inspected_by,inspected_at,created_at,updated_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::uuid,?::varchar,?::varchar,?::boolean,?::varchar,?,?,"
            + "?::varchar,?::varchar,?::uuid,?::timestamptz,?::timestamptz,?::timestamptz)"
            + " on conflict (tenant_id,order_id,template_item_id) do update set"
            + " result=excluded.result,failed_sample_quantity=excluded.failed_sample_quantity,"
            + " deducted_quantity=excluded.deducted_quantity,defect_type=excluded.defect_type,"
            + " defect_description=excluded.defect_description,inspected_by=excluded.inspected_by,"
            + " inspected_at=excluded.inspected_at,updated_at=excluded.updated_at",
        itemId,
        tenant,
        id,
        input.templateItemId(),
        templateItem.get("item_code", String.class),
        templateItem.get("name", String.class),
        Boolean.TRUE.equals(templateItem.get("critical", Boolean.class)),
        input.result().name(),
        failed,
        deducted,
        input.defectType(),
        input.defectDescription() == null ? "" : input.defectDescription(),
        actorId,
        inspectedAt,
        now,
        now);
    // 实抽数量只能往下调；这里把最新值写回单头。
    sql.execute(
        "update quality_inspection_order set sampled_quantity=?::numeric, status=?, updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        sampled,
        IncomingOrderStatus.INSPECTING.name(),
        now,
        tenant,
        id);
    return orderItem(orderItemRow(id, input.templateItemId()));
  }

  /**
   * 登记让步接收。
   *
   * <p><b>原因与权限码缺一拒绝</b>（蓝图 6.4.2.2 加粗句的纪律）；
   * 并且**记录人必须真的持有那个权限码** —— 只写一个码不算放行。
   */
  @Transactional
  public IncomingConcessionRow addConcession(
      UUID id, IncomingConcessionInput input, UUID actorId, List<String> actorPermissions) {
    Record order = orderRow(id);
    IncomingOrderStatus status = IncomingOrderStatus.valueOf(order.get("status", String.class));
    if (status.terminal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "质检单已处于终态（" + status.label() + "），不能再登记让步接收");
    }
    String permissionCode = input.permissionCode().trim();
    if (!actorPermissions.contains(permissionCode)) {
      throw new ResponseStatusException(
          HttpStatus.FORBIDDEN,
          "当前身份没有权限码 " + permissionCode + "，不能放行让步接收"
              + "（蓝图 6.4.2.2 加粗句：让步接收需要权限与原因，不能由质检员随手放行）");
    }
    BigDecimal lotSize = order.get("lot_size", BigDecimal.class);
    BigDecimal quantity = scale(input.quantity());
    BigDecimal already =
        scale(
            sql.fetchOne(
                    "select coalesce(sum(quantity),0) from quality_inspection_concession"
                        + " where tenant_id=?::uuid and order_id=?::uuid",
                    currentTenant(),
                    id)
                .get(0, BigDecimal.class));
    BigDecimal gross = grossDeducted(id);
    if (already.add(quantity).compareTo(gross) > 0) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "让步接收量 " + already.add(quantity).toPlainString() + " 超过了本单的扣量合计 "
              + gross.toPlainString()
              + "：让步接收只能放行「已经判不合格」的那部分，不能凭空放行合格品");
    }
    if (already.add(quantity).compareTo(lotSize) > 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "让步接收量超过了本次质检的批量 " + lotSize.toPlainString());
    }
    UUID concessionId = UUID.randomUUID();
    sql.execute(
        "insert into quality_inspection_concession(id,tenant_id,order_id,item_code,quantity,reason,"
            + " permission_code,approved_by,created_at)"
            + " values (?::uuid,?::uuid,?::uuid,?::varchar,?,?::varchar,?::varchar,?::uuid,?::timestamptz)",
        concessionId,
        currentTenant(),
        id,
        input.itemCode() == null ? null : input.itemCode().name(),
        quantity,
        input.reason().trim(),
        permissionCode,
        actorId,
        OffsetDateTime.now(ZoneOffset.UTC));
    return concession(concessionRow(concessionId));
  }

  /**
   * 完结质检单 —— 判出三量、结论与分流意图。
   *
   * <p>完结前的四道关口：
   *
   * <ol>
   *   <li>每个启用的检验项都必须有结论（不然「检了什么」没记录）；</li>
   *   <li>实抽数量 ≥ 1（否则等于没抽）；</li>
   *   <li>危险品 / 带电池的 SKU 必须有认证标识检验项（3.7.2.1）；</li>
   *   <li>让步接收量 ≤ 扣量合计（DB 层再兜一次）。</li>
   * </ol>
   */
  @Transactional
  public IncomingOrderRow complete(UUID id, IncomingCompleteInput input, UUID actorId) {
    Record order = orderRow(id);
    IncomingOrderStatus status = IncomingOrderStatus.valueOf(order.get("status", String.class));
    if (status.terminal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "质检单已处于终态（" + status.label() + "），不能重复完结");
    }
    List<IncomingTemplateItemRow> templateItems = templateItems(order.get("template_id", UUID.class));
    List<IncomingOrderItemRow> items = orderItems(id);
    List<String> missing = new ArrayList<>();
    for (IncomingTemplateItemRow item : templateItems) {
      boolean found = false;
      for (IncomingOrderItemRow concluded : items) {
        if (concluded.templateItemId().equals(item.id())) {
          found = true;
          break;
        }
      }
      if (!found) {
        missing.add(item.itemCodeLabel());
      }
    }
    if (!missing.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "还有检验项没有结论，不能完结：" + String.join("、", missing));
    }
    BigDecimal sampled = order.get("sampled_quantity", BigDecimal.class);
    if (sampled.signum() <= 0) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "实抽数量为 0，等于没抽，不能完结");
    }
    if (Boolean.TRUE.equals(order.get("safety_requires_certification", Boolean.class))
        && !hasCertificationItem(templateItems)) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT,
          "该 SKU 带电池 / 液体 / 危险品（3.7.2.1 物理安全属性），必须验认证标识，"
              + "但这张质检单的模板里没有认证标识检验项 —— 先给模板加上认证标识再完结");
    }

    BigDecimal lotSize = order.get("lot_size", BigDecimal.class);
    BigDecimal gross = grossDeducted(id);
    String criticalAction = templateValue(order.get("template_id", UUID.class), "critical_failure_action");
    boolean criticalFailure = false;
    for (IncomingOrderItemRow item : items) {
      if (item.critical() && "FAIL".equals(item.result())) {
        criticalFailure = true;
      }
    }
    if (criticalFailure && "REJECT_LOT".equals(criticalAction)) {
      gross = lotSize;
    }
    BigDecimal concession =
        scale(
            sql.fetchOne(
                    "select coalesce(sum(quantity),0) from quality_inspection_concession"
                        + " where tenant_id=?::uuid and order_id=?::uuid",
                    currentTenant(),
                    id)
                .get(0, BigDecimal.class));
    QuantityBalance balance = balance(lotSize, gross, concession);
    if (!balance.balanced()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "三量对不上：合格 + 不合格 + 让步接收必须等于本次质检的批量");
    }
    IncomingInspectionResult result = resultOf(balance);
    IncomingDefectiveTarget target =
        IncomingDefectiveTarget.valueOf(
            templateValue(order.get("template_id", UUID.class), "defective_target"));
    IncomingDisposition disposition = dispositionOf(balance, target);
    BigDecimal amount =
        deductedAmount(
            balance.grossDeducted(),
            order.get("unit_price", BigDecimal.class),
            order.get("exchange_rate", BigDecimal.class),
            order.get("unit_conversion_factor", BigDecimal.class));
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update quality_inspection_order set status=?,result=?,qualified_quantity=?,"
            + " defective_quantity=?,concession_quantity=?,gross_deducted_quantity=?,disposition=?,"
            + " disposition_status=?,disposition_note=?,deducted_amount=?,conclusion_note=?,"
            + " completed_at=?::timestamptz,updated_at=?::timestamptz where tenant_id=?::uuid and id=?::uuid",
        IncomingOrderStatus.COMPLETED.name(),
        result.name(),
        balance.qualified(),
        balance.defective(),
        balance.concession(),
        balance.grossDeducted(),
        disposition.name(),
        IncomingDispositionStatus.UNAVAILABLE.name(),
        "不合格量 " + balance.nonSellable().toPlainString() + " 应当进入「"
            + disposition.label() + "」bucket；warehouse 当前没有可用的授权写端点，"
            + "所以**只记了分流意图，没有真的分流**（绝不假装已分流）。",
        amount,
        input.conclusionNote() == null ? "" : input.conclusionNote(),
        now,
        now,
        currentTenant(),
        id);
    return orderRow(orderRow(id));
  }

  /** 作废质检单。理由必填。 */
  @Transactional
  public IncomingOrderRow cancel(UUID id, IncomingCancelInput input) {
    Record order = orderRow(id);
    IncomingOrderStatus status = IncomingOrderStatus.valueOf(order.get("status", String.class));
    if (status.terminal()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "质检单已处于终态（" + status.label() + "），不能重复作废");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    sql.execute(
        "update quality_inspection_order set status=?,conclusion_note=?,completed_at=?::timestamptz,updated_at=?::timestamptz"
            + " where tenant_id=?::uuid and id=?::uuid",
        IncomingOrderStatus.CANCELLED.name(),
        "作废：" + input.cancelReason().trim(),
        now,
        now,
        currentTenant(),
        id);
    return orderRow(orderRow(id));
  }

  /** 汇总视图：可售量与不可售量分列。 */
  public IncomingInspectionSummary summary(String currencyCode) {
    UUID tenant = currentTenant();
    Record row =
        sql.fetchOne(
            "select count(*) as order_count,"
                + " count(*) filter (where status='DRAFT') as draft_count,"
                + " count(*) filter (where status='INSPECTING') as inspecting_count,"
                + " count(*) filter (where status='COMPLETED') as completed_count,"
                + " count(*) filter (where status='CANCELLED') as cancelled_count,"
                + " count(*) filter (where result='PASSED') as passed_count,"
                + " count(*) filter (where result='FAILED') as failed_count,"
                + " count(*) filter (where result='PARTIAL') as partial_count,"
                + " count(*) filter (where result='CONCESSION_ACCEPTED') as concession_count,"
                + " coalesce(sum(arrived_quantity),0) as arrived,"
                + " coalesce(sum(lot_size) filter (where status='COMPLETED'),0) as inspected,"
                + " coalesce(sum(qualified_quantity),0) as qualified,"
                + " coalesce(sum(defective_quantity),0) as defective,"
                + " coalesce(sum(concession_quantity),0) as concession,"
                + " coalesce(sum(deducted_amount) filter (where currency_code=?),0) as deducted"
                + " from quality_inspection_order where tenant_id=?::uuid",
            currencyCode,
            tenant);
    BigDecimal arrived = scale(row.get("arrived", BigDecimal.class));
    BigDecimal inspected = scale(row.get("inspected", BigDecimal.class));
    BigDecimal qualified = scale(row.get("qualified", BigDecimal.class));
    BigDecimal defective = scale(row.get("defective", BigDecimal.class));
    BigDecimal concession = scale(row.get("concession", BigDecimal.class));
    return new IncomingInspectionSummary(
        row.get("order_count", Long.class),
        row.get("draft_count", Long.class),
        row.get("inspecting_count", Long.class),
        row.get("completed_count", Long.class),
        row.get("cancelled_count", Long.class),
        row.get("passed_count", Long.class),
        row.get("failed_count", Long.class),
        row.get("partial_count", Long.class),
        row.get("concession_count", Long.class),
        arrived,
        inspected,
        qualified,
        defective,
        concession,
        qualified.add(concession),
        defective,
        row.get("deducted", BigDecimal.class),
        currencyCode,
        "「到货量」是所有单子的到货量合计（含未完结的）；「已检量」只算已完结的单子。"
            + "扣量金额按币种筛选统计 —— 不跨币种相加。");
  }

  /** 导出 CSV 给质检员：一张质检单 + 逐项结论 + 让步记录。 */
  public byte[] exportCsv(UUID id) {
    Record order = orderRow(id);
    List<IncomingOrderItemRow> items = orderItems(id);
    List<IncomingConcessionRow> concessions = concessions(id);
    StringBuilder csv = new StringBuilder();
    csv.append("质检单号,状态,结论,SKU,收货单号,批次,到货量,本次批量,应抽数量,实抽数量,")
        .append("合格量,不合格量,让步接收量,扣量合计,可售量,不可售量,分流目标,分流状态\n");
    IncomingOrderRow row = orderRow(order);
    csv.append(csvCell(row.inspectionNo()))
        .append(',')
        .append(csvCell(row.statusLabel()))
        .append(',')
        .append(csvCell(row.resultLabel() == null ? "" : row.resultLabel()))
        .append(',')
        .append(csvCell(row.skuCode()))
        .append(',')
        .append(csvCell(row.receiptCode() == null ? "" : row.receiptCode()))
        .append(',')
        .append(csvCell(row.batchNo() == null ? "" : row.batchNo()))
        .append(',')
        .append(row.arrivedQuantity().toPlainString())
        .append(',')
        .append(row.lotSize().toPlainString())
        .append(',')
        .append(row.sampleQuantity().toPlainString())
        .append(',')
        .append(row.sampledQuantity().toPlainString())
        .append(',')
        .append(row.qualifiedQuantity().toPlainString())
        .append(',')
        .append(row.defectiveQuantity().toPlainString())
        .append(',')
        .append(row.concessionQuantity().toPlainString())
        .append(',')
        .append(row.grossDeductedQuantity().toPlainString())
        .append(',')
        .append(row.sellableQuantity().toPlainString())
        .append(',')
        .append(row.nonSellableQuantity().toPlainString())
        .append(',')
        .append(csvCell(row.dispositionLabel()))
        .append(',')
        .append(csvCell(row.dispositionStatusLabel()))
        .append('\n');
    csv.append("检验项,结论,关键项,抽检不合格件数,批次扣量,缺陷类型,缺陷描述\n");
    for (IncomingOrderItemRow item : items) {
      csv.append(csvCell(item.itemCodeLabel()))
          .append(',')
          .append(csvCell(item.resultLabel()))
          .append(',')
          .append(item.critical() ? "是" : "否")
          .append(',')
          .append(item.failedSampleQuantity().toPlainString())
          .append(',')
          .append(item.deductedQuantity().toPlainString())
          .append(',')
          .append(csvCell(item.defectType() == null ? "" : item.defectType()))
          .append(',')
          .append(csvCell(item.defectDescription()))
          .append('\n');
    }
    csv.append("让步接收量,原因,权限码\n");
    for (IncomingConcessionRow concession : concessions) {
      csv.append(concession.quantity().toPlainString())
          .append(',')
          .append(csvCell(concession.reason()))
          .append(',')
          .append(csvCell(concession.permissionCode()))
          .append('\n');
    }
    return csv.toString().getBytes(StandardCharsets.UTF_8);
  }

  // ================================================================ 内部

  /**
   * 拼一条 INSERT：<b>列、占位符、参数三者逐个对应</b>。
   *
   * <p>为什么不用「一长串 {@code ?::varchar}」：那种写法里参数与占位符**没有任何机制**把它们
   * 绑在一起 —— 多一个少一个，jOOQ 要么报「参数个数不匹配」（409），要么更糟：
   * 数量正好但顺序错了，值就绑到了别的列上，而 SQL 还不报错。
   * 这里把三份清单写成**平行数组**，并在一行代码里断言长度一致 ——
   * 对不上当场炸（而不是落库落歪）。
   */
  private void insert(String table, List<String> columns, List<String> casts, List<Object> values) {
    if (columns.size() != casts.size() || columns.size() != values.size()) {
      throw new IllegalStateException(
          "INSERT "
              + table
              + " 的列/占位符/参数数量对不上："
              + columns.size()
              + "/"
              + casts.size()
              + "/"
              + values.size());
    }
    String statement =
        "insert into "
            + table
            + "("
            + String.join(",", columns)
            + ") values ("
            + String.join(",", casts)
            + ")";
    sql.execute(statement, values.toArray());
  }

  /** 读上游收货单，拿本次到货量。 */
  private BigDecimal resolveArrivedQuantity(IncomingOrderInput input, String bearer) {
    if (input.receiptCode() == null || input.receiptCode().isBlank()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "必须给来源收货单号：到货量来自 8 收货，本服务不认客户端报的数量");
    }
    QualityInspectionWarehousePort.ReceiptDetailView receipt =
        warehouse.findByCode(input.receiptCode().trim(), bearer);
    if (receipt == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "仓库里没有收货单 " + input.receiptCode().trim() + "（或它不属于当前租户）");
    }
    List<QualityInspectionWarehousePort.ReceiptLineView> lines = receipt.lines();
    if (lines == null || lines.isEmpty()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "收货单 " + receipt.receiptCode() + " 没有明细行，没有可质检的货");
    }
    for (QualityInspectionWarehousePort.ReceiptLineView line : lines) {
      if (input.receiptLineId() != null && !input.receiptLineId().equals(line.id())) {
        continue;
      }
      if (input.receiptLineId() == null
          && (line.skuCode() == null || !line.skuCode().equals(input.skuCode().trim()))) {
        continue;
      }
      BigDecimal received = line.receivedQuantity();
      if (received == null || received.signum() <= 0) {
        throw new ResponseStatusException(
            HttpStatus.CONFLICT,
            "收货单 " + receipt.receiptCode() + " 的 " + line.skuCode() + " 实收量为 0，没有可质检的货");
      }
      return scale(received);
    }
    throw new ResponseStatusException(
        HttpStatus.NOT_FOUND,
        "收货单 " + receipt.receiptCode() + " 里没有 SKU " + input.skuCode().trim() + " 的明细行");
  }

  /** 本单的扣量合计（Σ 各检验项的批次扣量）。 */
  private BigDecimal grossDeducted(UUID orderId) {
    return scale(
        sql.fetchOne(
                "select coalesce(sum(deducted_quantity),0) from quality_inspection_order_item"
                    + " where tenant_id=?::uuid and order_id=?::uuid",
                currentTenant(),
                orderId)
            .get(0, BigDecimal.class));
  }

  private boolean hasCertificationItem(List<IncomingTemplateItemRow> items) {
    for (IncomingTemplateItemRow item : items) {
      if ("CERTIFICATION".equals(item.itemCode())) {
        return true;
      }
    }
    return false;
  }

  private String templateValue(UUID templateId, String column) {
    return sql.fetchOne(
            "select " + column + " from quality_inspection_template"
                + " where tenant_id=?::uuid and id=?::uuid",
            currentTenant(),
            templateId)
        .get(column, String.class);
  }

  private String safetyNote(Record order) {
    String state = order.get("safety_profile_state", String.class);
    if (IncomingSafetyState.COLLECTED.name().equals(state)) {
      return Boolean.TRUE.equals(order.get("safety_requires_certification", Boolean.class))
          ? "3.7.2.1：该 SKU 带电池 / 液体 / 危险品 / 磁性，必须验认证标识。"
          : "3.7.2.1：已采集该 SKU 的物理安全属性，未发现需要认证标识的危险属性。";
    }
    if (IncomingSafetyState.NOT_COLLECTED.name().equals(state)) {
      return "3.7.2.1：该 SKU **没有安全属性档案**（catalog 返回未采集）—— "
          + "「没采过」不等于「不需要认证」，本单不据此放行认证标识要求。";
    }
    return "3.7.2.1：本单未带 SKU 标识，无法核对物理安全属性。";
  }

  private List<String> actions(Record order) {
    List<String> actions = new ArrayList<>();
    IncomingOrderStatus status = IncomingOrderStatus.valueOf(order.get("status", String.class));
    if (!status.terminal()) {
      actions.add("RECORD_ITEM");
      actions.add("CONCESSION");
      actions.add("COMPLETE");
      actions.add("CANCEL");
    }
    actions.add("EXPORT");
    return actions;
  }

  private void insertTemplateItems(UUID templateId, List<IncomingTemplateItemInput> items) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    int order = 0;
    for (IncomingTemplateItemInput item : items) {
      insert(
          "quality_inspection_template_item",
          List.of(
              "id", "tenant_id", "template_id", "item_code", "name", "critical",
              "acceptance_criteria", "sample_coverage", "sort_order", "active", "created_at",
              "updated_at"),
          List.of(
              "?::uuid", "?::uuid", "?::uuid", "?::varchar", "?::varchar", "?::boolean",
              "?::varchar", "?", "?", "?::boolean", "?::timestamptz", "?::timestamptz"),
          Arrays.asList(
              (Object) UUID.randomUUID(),
              currentTenant(),
              templateId,
              item.itemCode().name(),
              item.name().trim(),
              Boolean.TRUE.equals(item.critical()),
              item.acceptanceCriteria() == null ? "" : item.acceptanceCriteria(),
              scale(item.sampleCoverage() == null ? BigDecimal.ONE : item.sampleCoverage()),
              item.sortOrder() == null ? order : item.sortOrder(),
              true,
              now,
              now));
      order++;
    }
  }

  private Record templateRow(UUID id) {
    Record row =
        sql.fetchOne(
            "select t.*,"
                + " (select count(*) from quality_inspection_template_item i"
                + "  where i.tenant_id=t.tenant_id and i.template_id=t.id) as item_count,"
                + " (select count(*) from quality_inspection_template_item i"
                + "  where i.tenant_id=t.tenant_id and i.template_id=t.id and i.critical) as critical_count"
                + " from quality_inspection_template t"
                + " where t.tenant_id=?::uuid and t.id=?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "质检模板不存在或无权访问");
    }
    return row;
  }

  private Record templateItemRow(UUID templateId, UUID templateItemId) {
    Record row =
        sql.fetchOne(
            "select * from quality_inspection_template_item"
                + " where tenant_id=?::uuid and template_id=?::uuid and id=?::uuid",
            currentTenant(),
            templateId,
            templateItemId);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.NOT_FOUND, "检验项不存在或不属于这张质检单的模板");
    }
    return row;
  }

  private List<IncomingTemplateItemRow> templateItems(UUID templateId) {
    List<IncomingTemplateItemRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from quality_inspection_template_item"
                + " where tenant_id=?::uuid and template_id=?::uuid and active"
                + " order by sort_order, item_code",
            currentTenant(),
            templateId)) {
      IncomingItemCode code = IncomingItemCode.valueOf(row.get("item_code", String.class));
      items.add(
          new IncomingTemplateItemRow(
              row.get("id", UUID.class),
              code.name(),
              code.label(),
              row.get("name", String.class),
              Boolean.TRUE.equals(row.get("critical", Boolean.class)),
              row.get("acceptance_criteria", String.class),
              row.get("sample_coverage", BigDecimal.class),
              row.get("sort_order", Integer.class),
              Boolean.TRUE.equals(row.get("active", Boolean.class))));
    }
    return items;
  }

  private Record orderRow(UUID id) {
    Record row =
        sql.fetchOne(
            ORDER_SELECT + " where o.tenant_id=?::uuid and o.id=?::uuid", currentTenant(), id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "质检单不存在或无权访问");
    }
    return row;
  }

  private Record orderItemRow(UUID orderId, UUID templateItemId) {
    Record row =
        sql.fetchOne(
            "select * from quality_inspection_order_item"
                + " where tenant_id=?::uuid and order_id=?::uuid and template_item_id=?::uuid",
            currentTenant(),
            orderId,
            templateItemId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "检验结论不存在");
    }
    return row;
  }

  private Record concessionRow(UUID id) {
    Record row =
        sql.fetchOne(
            "select * from quality_inspection_concession where tenant_id=?::uuid and id=?::uuid",
            currentTenant(),
            id);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "让步接收记录不存在");
    }
    return row;
  }

  private List<IncomingOrderItemRow> orderItems(UUID orderId) {
    List<IncomingOrderItemRow> items = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from quality_inspection_order_item"
                + " where tenant_id=?::uuid and order_id=?::uuid order by item_code",
            currentTenant(),
            orderId)) {
      items.add(orderItem(row));
    }
    return items;
  }

  /** 逐项检验结论 → 传输模型。 */
  private IncomingOrderItemRow orderItem(Record row) {
    IncomingItemCode code = IncomingItemCode.valueOf(row.get("item_code", String.class));
    IncomingItemResult result = IncomingItemResult.valueOf(row.get("result", String.class));
    return new IncomingOrderItemRow(
        row.get("id", UUID.class),
        row.get("template_item_id", UUID.class),
        code.name(),
        code.label(),
        row.get("item_name", String.class),
        Boolean.TRUE.equals(row.get("critical", Boolean.class)),
        result.name(),
        result.label(),
        row.get("failed_sample_quantity", BigDecimal.class),
        row.get("deducted_quantity", BigDecimal.class),
        row.get("defect_type", String.class),
        row.get("defect_description", String.class),
        row.get("inspected_at", OffsetDateTime.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private List<IncomingConcessionRow> concessions(UUID orderId) {
    List<IncomingConcessionRow> rows = new ArrayList<>();
    for (Record row :
        sql.fetch(
            "select * from quality_inspection_concession"
                + " where tenant_id=?::uuid and order_id=?::uuid order by created_at, id",
            currentTenant(),
            orderId)) {
      rows.add(concession(row));
    }
    return rows;
  }

  /** 让步接收记录 → 传输模型。 */
  private IncomingConcessionRow concession(Record row) {
    String itemCode = row.get("item_code", String.class);
    return new IncomingConcessionRow(
        row.get("id", UUID.class),
        itemCode,
        itemCode == null ? null : IncomingItemCode.valueOf(itemCode).label(),
        row.get("quantity", BigDecimal.class),
        row.get("reason", String.class),
        row.get("permission_code", String.class),
        row.get("approved_by", UUID.class),
        row.get("created_at", OffsetDateTime.class));
  }

  private IncomingTemplateRow templateRow(Record row) {
    IncomingInspectionType type = IncomingInspectionType.valueOf(row.get("inspection_type", String.class));
    IncomingRoundingMode rounding = IncomingRoundingMode.valueOf(row.get("rounding_mode", String.class));
    IncomingCriticalFailureAction critical =
        IncomingCriticalFailureAction.valueOf(row.get("critical_failure_action", String.class));
    IncomingDefectiveTarget target = IncomingDefectiveTarget.valueOf(row.get("defective_target", String.class));
    IncomingTemplateStatus status = IncomingTemplateStatus.valueOf(row.get("status", String.class));
    return new IncomingTemplateRow(
        row.get("id", UUID.class),
        row.get("code", String.class),
        row.get("name", String.class),
        row.get("category_id", UUID.class),
        row.get("category_name", String.class),
        type.name(),
        type.label(),
        row.get("sampling_ratio", BigDecimal.class),
        row.get("min_sample_quantity", BigDecimal.class),
        row.get("max_sample_quantity", BigDecimal.class),
        rounding.name(),
        rounding.label(),
        critical.name(),
        critical.label(),
        target.name(),
        target.label(),
        row.get("item_count", Long.class) == null ? 0L : row.get("item_count", Long.class),
        row.get("critical_count", Long.class) == null ? 0L : row.get("critical_count", Long.class),
        status.name(),
        status.label(),
        row.get("note", String.class),
        row.get("created_at", OffsetDateTime.class),
        row.get("updated_at", OffsetDateTime.class));
  }

  private IncomingOrderRow orderRow(Record row) {
    IncomingInspectionType type = IncomingInspectionType.valueOf(row.get("inspection_type", String.class));
    IncomingOrderStatus status = IncomingOrderStatus.valueOf(row.get("status", String.class));
    String result = row.get("result", String.class);
    IncomingInspectionResult resultEnum =
        result == null ? null : IncomingInspectionResult.valueOf(result);
    IncomingDisposition disposition = IncomingDisposition.valueOf(row.get("disposition", String.class));
    IncomingDispositionStatus dispositionStatus =
        IncomingDispositionStatus.valueOf(row.get("disposition_status", String.class));
    BigDecimal lotSize = row.get("lot_size", BigDecimal.class);
    BigDecimal qualified = row.get("qualified_quantity", BigDecimal.class);
    BigDecimal defective = row.get("defective_quantity", BigDecimal.class);
    BigDecimal concession = row.get("concession_quantity", BigDecimal.class);
    long itemCount = row.get("item_count", Long.class) == null ? 0L : row.get("item_count", Long.class);
    long concessionCount =
        row.get("concession_count", Long.class) == null ? 0L : row.get("concession_count", Long.class);
    long criticalFailureCount =
        row.get("critical_failure_count", Long.class) == null
            ? 0L
            : row.get("critical_failure_count", Long.class);
    boolean criticalFailure = criticalFailureCount > 0;
    return new IncomingOrderRow(
        row.get("id", UUID.class),
        row.get("inspection_no", String.class),
        type.name(),
        type.label(),
        status.name(),
        status.label(),
        result,
        resultEnum == null ? null : resultEnum.label(),
        row.get("template_id", UUID.class),
        row.get("template_code", String.class),
        row.get("template_name", String.class),
        row.get("category_id", UUID.class),
        row.get("category_name", String.class),
        row.get("sku_id", UUID.class),
        row.get("sku_code", String.class),
        row.get("sku_name", String.class),
        row.get("supplier_id", UUID.class),
        row.get("supplier_name", String.class),
        row.get("receipt_code", String.class),
        row.get("source_ref_code", String.class),
        row.get("batch_no", String.class),
        row.get("arrived_quantity", BigDecimal.class),
        lotSize,
        row.get("sample_quantity", BigDecimal.class),
        row.get("sampled_quantity", BigDecimal.class),
        qualified,
        defective,
        concession,
        row.get("gross_deducted_quantity", BigDecimal.class),
        qualified.add(concession),
        defective,
        disposition.name(),
        disposition.label(),
        dispositionStatus.name(),
        dispositionStatus.label(),
        row.get("currency_code", String.class),
        row.get("unit_price", BigDecimal.class),
        row.get("deducted_amount", BigDecimal.class),
        itemCount,
        itemCount,
        concessionCount,
        criticalFailure,
        row.get("created_at", OffsetDateTime.class),
        row.get("completed_at", OffsetDateTime.class));
  }

  /** 物理安全属性快照（jsonb 文本）。 */
  private String safetyJson(QualityInspectionCatalogPort.SafetyProfileView profile) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("skuCode", profile.skuCode());
    map.put("safetyCollected", profile.safetyCollected());
    map.put("containsBattery", profile.containsBattery());
    map.put("containsLiquid", profile.containsLiquid());
    map.put("dangerousGoods", profile.dangerousGoods());
    map.put("isMagnetic", profile.isMagnetic());
    map.put("dangerousSubstanceClass", profile.dangerousSubstanceClass());
    map.put("activeFlags", profile.activeFlags());
    map.put("requiredDeclarations", profile.requiredDeclarations());
    try {
      return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 需要验认证标识吗（3.7.2.1 的危险属性）。 */
  private boolean requiresCertification(QualityInspectionCatalogPort.SafetyProfileView profile) {
    return profile.containsBattery()
        || profile.containsLiquid()
        || profile.dangerousGoods()
        || profile.isMagnetic();
  }

  // ================================================================ 小工具

  /** 当前租户（由 RLS 事务绑定，绝不来自客户端头）。 */
  private UUID currentTenant() {
    var row =
        sql.fetchOne("select nullif(current_setting('app.current_tenant', true), '')::uuid as t");
    UUID tenant = row == null ? null : row.get("t", UUID.class);
    if (tenant == null) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "登录已失效，请重新登录");
    }
    return tenant;
  }

  private static int pageSize(int size) {
    return Math.min(Math.max(size, 1), 200);
  }

  /** 条件片段：值为空时不加条件（返回空串，避免拼出 {@code and ()} 这种坏 SQL）。 */
  private static String filter(String sql, String value) {
    return value == null || value.isBlank() ? "" : sql;
  }

  /**
   * 绑定值数组。
   *
   * <p>用法：条件片段与绑定值**必须一起**加 —— 少加一段条件却留着它的绑定值，
   * jOOQ 会把值绑到别的 {@code ?} 上，**而且不报错**（SQL 参数数量对不上时它才报错，
   * 而这里数量正好对得上、绑错了位置）。所以每个 {@code if} 里条件与值同进同出。
   */
  private static Object[] argsOf(List<Object> args, Object... extra) {
    List<Object> all = new ArrayList<>(args);
    all.addAll(List.of(extra));
    return all.toArray();
  }

  private static String csvCell(String value) {
    if (value == null) {
      return "";
    }
    String text = value.replace("\r", " ").replace("\n", " ");
    if (text.contains(",") || text.contains("\"")) {
      return "\"" + text.replace("\"", "\"\"") + "\"";
    }
    return text;
  }

  private static List<String> itemCodes() {
    List<String> values = new ArrayList<>();
    for (IncomingItemCode code : IncomingItemCode.values()) {
      values.add(code.name());
    }
    return values;
  }

  private static List<String> itemCodeLabels() {
    List<String> values = new ArrayList<>();
    for (IncomingItemCode code : IncomingItemCode.values()) {
      values.add(code.label());
    }
    return values;
  }

  private static List<String> roundingModes() {
    List<String> values = new ArrayList<>();
    for (IncomingRoundingMode mode : IncomingRoundingMode.values()) {
      values.add(mode.name());
    }
    return values;
  }

  private static List<String> roundingModeLabels() {
    List<String> values = new ArrayList<>();
    for (IncomingRoundingMode mode : IncomingRoundingMode.values()) {
      values.add(mode.label());
    }
    return values;
  }

  private static List<String> orderStatuses() {
    List<String> values = new ArrayList<>();
    for (IncomingOrderStatus status : IncomingOrderStatus.values()) {
      values.add(status.name());
    }
    return values;
  }

  private static List<String> orderStatusLabels() {
    List<String> values = new ArrayList<>();
    for (IncomingOrderStatus status : IncomingOrderStatus.values()) {
      values.add(status.label());
    }
    return values;
  }

  private static List<String> resultCodes() {
    List<String> values = new ArrayList<>();
    for (IncomingInspectionResult result : IncomingInspectionResult.values()) {
      values.add(result.name());
    }
    return values;
  }

  private static List<String> resultLabels() {
    List<String> values = new ArrayList<>();
    for (IncomingInspectionResult result : IncomingInspectionResult.values()) {
      values.add(result.label());
    }
    return values;
  }

  private static List<String> dispositionCodes() {
    List<String> values = new ArrayList<>();
    for (IncomingDisposition disposition : IncomingDisposition.values()) {
      values.add(disposition.name());
    }
    return values;
  }

  private static List<String> dispositionLabels() {
    List<String> values = new ArrayList<>();
    for (IncomingDisposition disposition : IncomingDisposition.values()) {
      values.add(disposition.label());
    }
    return values;
  }

  private static List<String> inspectionTypes() {
    List<String> values = new ArrayList<>();
    for (IncomingInspectionType type : IncomingInspectionType.values()) {
      values.add(type.name());
    }
    return values;
  }

  private static List<String> inspectionTypeLabels() {
    List<String> values = new ArrayList<>();
    for (IncomingInspectionType type : IncomingInspectionType.values()) {
      values.add(type.label());
    }
    return values;
  }
}
