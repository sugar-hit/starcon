package com.starcon.trade;

import static com.starcon.trade.ProductIssueAttributionModels.*;

import com.starcon.trade.ProductLotTracePort.LotSnapshot;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.1.2.2 商品问题归因。
 *
 * <p><b>本类是 V180 五张新表的写入所有者</b>（product_issue_attribution / product_issue_photo /
 * product_issue_photo_comparison / product_issue_lot_flag / product_issue_event），
 * 对 V156 的 aftersales_case、V158 的原因归因三表、V179 的 return_quality_* 簇
 * 与 V172 的 return_receipt_* 簇<b>只读</b>。刻意不加外键、不 ALTER 既有表 ⇒
 * 跨车道 DDL 回归面 = 0（同批另有三条车道也在往 trade 落迁移）。
 *
 * <p><b>批次真值在 warehouse 独立库</b>（7.3.1.1 / V57 inventory_lot），
 * 本服务<b>只经 {@link ProductLotTracePort} 的授权 HTTP 端点读</b>，
 * 既不直连对方库、也不建跨库视图或外键（AGENTS.md 模块边界纪律）。
 * 本服务写进自己库的是**归因事实**（product_issue_attribution.lot_id + 快照列），
 * <b>绝不改 warehouse 的 inventory_lot</b>。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       归因结论是要拿去和供应商索赔的<b>责任判定</b>，端点大多是<b>按 id 直达</b>的 URL ——
 *       漏掉租户谓词就是跨租户泄露谈判底牌。</li>
 *   <li><b>归因环节只走 {@link #attribute} 一个入口</b>，且<b>服务层与 DB 触发器各判一次</b>
 *       （V180 守卫①-④ + 环节相容性守卫）：只判一次的话，
 *       绕过服务层直连 SQL 就能把「描述不符」归到「生产」去。</li>
 *   <li><b>照片比对结果传输层不接受</b>（硬规则 2）：由服务层按两张照片的 sha256
 *       <b>真</b>比对得出，再由 DB 触发器独立比对一次 ——
 *       口径是<b>内容指纹</b>，不是图像相似度（本仓无图像库、无模型、无视觉服务接入点）。</li>
 *   <li><b>批次标记统计列由 DB 重算</b>（硬规则 3）：服务层按当前生效归因聚合后写入，
 *       触发器<b>独立重算并比对</b>，不等就拒。批次标记就是蓝图加粗句
 *       「同一个批次退回来 10 件」的计数来源，统计基数算虚了整个模块就没有意义。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而归因时刻偏 8 小时会让「这批货退回来多久才定的责」整体算错。</li>
 *   <li><b>状态流转只有一个入口</b>（{@link #attribute} / {@link #supersede}）：
 *       每次改状态都过版本校验，都写留痕。</li>
 * </ol>
 */
@Service
public class ProductIssueAttributionService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 归因数量的刻度（与 V172 / V179 的数量同刻度）。 */
  private static final int QTY_SCALE = 6;

  /**
   * 批次标记的默认最低**件数**口径（V180 的 {@code min_flag_qty} 默认值）。
   *
   * <p>⚠️ 为什么是 3 而不是 1：蓝图加粗句说的是「同一个批次退回来 <b>10 件</b>」与
   * 「10 个批次各退 <b>1 件</b>」的区别 —— 后者<b>不该</b>触发供应商责任。
   * 单件退货在跨境电商里是纯噪声（买家改变主意、一次运输磕碰），
   * 把它算进批次统计会让每个批次都被标「可疑」，
   * 而一个天天误报的标记，运营第三次看到就不会再当真了 —— 那才是真正把这条加粗句毁掉的方式。
   */
  private static final BigDecimal DEFAULT_MIN_FLAG_QTY = new BigDecimal("3");

  private final DSLContext sql;
  private final ProductLotTracePort lots;
  private final com.fasterxml.jackson.databind.ObjectMapper json;

  public ProductIssueAttributionService(
      DSLContext sql,
      ProductLotTracePort lots,
      com.fasterxml.jackson.databind.ObjectMapper json) {
    this.sql = sql;
    this.lots = lots;
    this.json = json;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号。
   *
   * <p>让界面能说清「为什么这张单还归不了因」「为什么索赔发不出去」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public ProductAttributionCapabilities capabilities() {
    return new ProductAttributionCapabilities(
        List.of(
            "售后单 aftersales_case（V156，同库只读）—— 归因单的挂靠对象与租户归属校验来源",
            "原因归因 aftersales_reason_attribution_set / _item / _category（V158，11.1.2.1 已交付，同库只读）—— 输入句「售后原因」的**唯一口径来源**，本模块只取**当前生效**那一版",
            "质检结论 return_quality_inspection / _check_result（V179，11.2.3.1 已交付，同库只读）—— 输入句「质检结论」，只接受**已判定**的质检单",
            "退回实物照片 return_receipt_photo（V172，11.2.2.1 已交付，同库只读）—— 输入句「退回实物照片」，本模块**引用**不复制",
            "实收数量 return_receipt_line ← return_receipt ← rma_authorization ← aftersales_case（V161/V172，同库只读）—— 归因数量的**上限来源**，调用方传一个数不算数",
            "批次档案 inventory_lot（V57，**warehouse 独立库**）—— **只经授权 HTTP 端点读**（7.3.1.1 GET /warehouse/inventory/lots/{lotId}），跨服务 SQL / 外键 / 视图均被 AGENTS.md 禁止"),
        List.of(
            section(
                "attribution",
                "归因到具体环节（生产 / 运输 / 仓储 / 描述）",
                true,
                null,
                "四个环节是枚举六值中的四个取值，不是自由文本",
                null),
            section(
                "lot-trace",
                "批次追溯（读 7.3.1.1 批次档案）",
                true,
                null,
                "经 warehouse 授权 HTTP 端点读，转发调用方自己的 Authorization bearer",
                null),
            section(
                "photo-compare",
                "照片比对",
                true,
                null,
                "**口径是内容指纹（sha256）一致 / 不一致，不是图像相似度** —— 本仓无图像库、无模型、无视觉服务接入点",
                null),
            section(
                "improvement",
                "改进建议（对供应商或流程）",
                true,
                null,
                "归因时**必须**给出改进建议（V180 表级 CHECK 拒掉空建议）；它是人写的文字，不是 AI 生成",
                null),
            section(
                "lot-flag",
                "批次质量标记",
                true,
                null,
                "统计列由 DB 触发器按当前生效归因**重算比对**，传输层不接受任何取值",
                null),
            section(
                "claim",
                "供应商索赔",
                false,
                "6.4.2.2",
                "**未交付** —— 归因结论是索赔的举证材料，但索赔单、协商记录、赔付与冲减应付都不在本模块",
                "6 采购 / 6.4.2.2"),
            section(
                "rating",
                "供应商评级",
                false,
                "6.1.1.3",
                "**未交付** —— 批次标记是评级的输入之一，评级本身的打分与升降档不在本模块",
                "6 采购 / 6.1.1.3"),
            section(
                "quality-analysis",
                "质量分析报表",
                false,
                "11.5.2.1",
                "**未交付** —— 本模块只出归因结论与批次标记，缺陷类型分布 / 批次质量趋势 / 成本估算属 11.5.2.1",
                "11 售后 / 11.5.2.1")),
        List.of(
            related(
                "7.3.1.1",
                "批次管理",
                true,
                null,
                "本模块的批次追溯上游；批次事实的写入所有者是 warehouse，本模块只读",
                "http://localhost:8084/warehouse/inventory/lots"),
            related(
                "11.1.2.1",
                "原因分类",
                true,
                null,
                "输入句「售后原因」的唯一口径来源（V158，同库只读）",
                "/aftersales/reasons"),
            related(
                "11.2.3.1",
                "质检与判定",
                true,
                null,
                "输入句「质检结论」的来源（V179，同库只读）；归因要求质检单**已判定**",
                "/aftersales/return-quality"),
            related(
                "6.4.2.2",
                "供应商索赔",
                false,
                "6 采购",
                "**未交付** —— 本模块提供举证材料（归因结论 + 批次标记），索赔单与赔付不在这里",
                null),
            related(
                "6.1.1.3",
                "供应商评级",
                false,
                "6 采购",
                "**未交付** —— 批次标记是评级输入，评级打分不在这里",
                null),
            related(
                "11.5.2.1",
                "质量分析",
                false,
                "11 售后",
                "**未交付** —— 质量报表与成本估算属 11.5.2.1",
                null)),
        List.of(
            action(
                "list",
                "归因单列表",
                true,
                "GET",
                "/trade/product-attribution/attributions",
                false,
                null,
                null,
                null,
                null),
            action(
                "create",
                "建归因单",
                true,
                "POST",
                "/trade/product-attribution/attributions",
                false,
                "该售后单要有实收记录（归因数量不得超过实收量）",
                null,
                null,
                null),
            action(
                "photos",
                "登记 / 替换照片",
                true,
                "PUT",
                "/trade/product-attribution/attributions/{id}/photos",
                false,
                "归因单处于 DRAFT",
                null,
                null,
                null),
            action(
                "compare",
                "照片比对（内容指纹）",
                true,
                "POST",
                "/trade/product-attribution/attributions/{id}/photos/compare",
                false,
                "两张照片都已登记",
                null,
                null,
                null),
            action(
                "attribute",
                "出归因结论",
                true,
                "POST",
                "/trade/product-attribution/attributions/{id}/attribute",
                false,
                "环节 + 理由 + 改进建议三者齐全；归因到「生产」必须挂批次",
                null,
                null,
                null),
            action(
                "supersede",
                "改判（留痕另起一版）",
                true,
                "POST",
                "/trade/product-attribution/attributions/{id}/supersede",
                false,
                "原归因单必须已归因",
                null,
                null,
                null),
            action(
                "lot-flags",
                "批次质量标记",
                true,
                "GET",
                "/trade/product-attribution/lot-flags",
                false,
                null,
                null,
                null,
                null),
            action(
                "claim",
                "发起供应商索赔",
                false,
                "POST",
                null,
                false,
                null,
                "6.4.2.2 未交付",
                "6 采购",
                "归因结论与批次标记是索赔的**举证材料**，但索赔单的创建、协商记录与赔付处理都不在本模块"),
            action(
                "rating",
                "写入供应商评级",
                false,
                "POST",
                null,
                false,
                null,
                "6.1.1.3 未交付",
                "6 采购",
                "评级打分与升降档不在本模块"),
            action(
                "image-similarity",
                "图像相似度比对",
                false,
                "POST",
                null,
                false,
                null,
                "本仓无图像库、无模型、无第三方视觉服务接入点",
                null,
                "本模块的照片比对口径是**内容指纹（sha256）一致 / 不一致**。"
                    + "同一件商品在不同光线角度下拍出的两张照片指纹必然不同，"
                    + "编一个「相似度」数字出来会让人以为系统看懂了照片，而它其实只比了字节。"
                    + "语义级的「是不是同一处缺陷」由人工在归因说明里写")),
        List.of(
            boundary("不改 warehouse 的 inventory_lot", "7.3.1.1（warehouse）",
                "批次事实的写入所有者是 warehouse；本模块的批次质量标记是 trade 侧**基于归因事实的统计结论**，"
                    + "两者是不同的东西：inventory_lot 记「这个批次有多少货」，本模块记「这个批次退回来多少、归到了哪些环节」"),
            boundary("不写跨服务 SQL / 视图 / 外键", "AGENTS.md 模块边界纪律",
                "批次只经 warehouse 授权 HTTP 端点读，转发调用方自己的 Authorization bearer，绝不传递或信任 X-Tenant-Id"),
            boundary("不做图像相似度", null,
                "见 image-similarity 动作的 unavailableReason"),
            boundary("不生成 AI 改进建议", null,
                "改进建议是**人写的必填文字**。本仓没有模型接入点，"
                    + "标成「AI 建议」就是在冒充智能能力"),
            boundary("不替下游消费结论", "6.4.2.2 / 6.1.1.3 / 11.5.2.1",
                "归因结论与批次标记**只出不消费**：本模块不发起索赔、不写评级、不出质量报表")),
        "DRAFT → ATTRIBUTED → SUPERSEDED：归因结论**终态不可逆**。"
            + "归因是要拿去和供应商 / 平台对质的结论，判错了只能**改判留痕另起一版**，"
            + "不能就地改数 —— 那会让「这批货到底归给谁」出现两个答案。"
            + "DB 层有部分唯一索引保证一单同时只有一版生效。",
        "六个环节取值：PRODUCTION 生产 / TRANSPORT 运输 / WAREHOUSE 仓储 / DESCRIPTION 描述 / "
            + "BUYER_FACTOR 买家因素 / UNDETERMINED 待定。"
            + "前四个逐字对应职责句点名的环节；后两个是**诚实出口** —— "
            + "「责任待定」是一个必须能被如实记下来的结论，"
            + "把它逼成四选一等于逼运营编一个环节出来，而编出来的环节会直接流向下游索赔。"
            + "两条 DB 规则：**生产责任只对质量问题成立**（描述不符归到生产在物理上讲不通），"
            + "且**归因到生产必须挂批次**（没有批次就指不到某一次投料）。",
        "照片比对落的是**内容指纹（sha256）**比对，不是图像相似度。"
            + "落库的是对象键 + 指纹 + 字节数 + 类型，**不存二进制大对象**；"
            + "比对结果是 IDENTITY_SAME / IDENTITY_DIFFER 两个取值，"
            + "刻意没有 0.87 这种相似度数字 —— 本仓没有图像库、没有训练好的模型、"
            + "也没有第三方视觉服务的接入点，编一个相似度等于冒充图像识别能力。"
            + "语义级的「是不是同一处缺陷」由人工在归因说明里写。",
        "批次质量标记是**统计结论**，不是「有人标了一下」："
            + "suspect_return_count / affected_return_qty / stage_breakdown 三列"
            + "**全部由 DB 触发器按当前生效归因重算**，插入语句里的任何取值都不被接受。"
            + "且 SUSPECT 要求 **≥ " + DEFAULT_MIN_FLAG_QTY.toPlainString() + " 件**当前生效归因 —— "
            + "1 件也标「批次有质量问题」会让整个标记体系变成噪声，"
            + "而天天误报的标记，运营第三次看到就不会再当真了。"
            + "这正是蓝图加粗句「同一个批次退回来 10 件 ≠ 10 个批次各退 1 件」的计数落点。",
        "批次真值在 **warehouse 独立库**（7.3.1.1 / V57 inventory_lot）。"
            + "本模块只经 warehouse 授权 HTTP 端点读（GET /warehouse/inventory/lots/{lotId}），"
            + "转发**调用方自己的 Authorization bearer**，warehouse 侧仍按已认证成员关系解析租户并强制 RLS。"
            + "本服务**绝不传递或信任 X-Tenant-Id 头**，也**绝不改 warehouse 的 inventory_lot**。");
  }

  private static AttributionSectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason, String landsIn) {
    return new AttributionSectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static AttributionRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new AttributionRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static AttributionAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new AttributionAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static AttributionBoundary boundary(String boundary, String ownerModule, String reason) {
    return new AttributionBoundary(boundary, ownerModule, reason);
  }

  // ================================================================ 列表

  /** 归因单列表查询参数。 */
  public record AttributionListQuery(
      int page, int size, String status, String stage, String skuCode, String lotId, String keyword,
      String sort) {}

  /** 归因单列表（真分页 + 多维筛选 + 排序白名单 + 跨全表汇总）。 */
  public AttributionPage listAttributions(AttributionListQuery query) {
    int p = Math.max(0, query.page());
    int s = query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);

    String from =
        " from product_issue_attribution a"
            + " left join lateral ("
            + "   select count(*) as photo_count, count(c.id) as compare_count"
            + "     from product_issue_photo p"
            + "     left join product_issue_photo_comparison c"
            + "       on c.tenant_id = p.tenant_id and c.left_photo_id = p.id"
            + "    where p.tenant_id = a.tenant_id and p.attribution_id = a.id"
            + " ) pc on true";

    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("a.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(query.status())) {
      if (!query.status().equals("ATTRIBUTED") && !query.status().equals("DRAFT")
          && !query.status().equals("SUPERSEDED")) {
        throw new IllegalArgumentException("状态只能是 DRAFT / ATTRIBUTED / SUPERSEDED");
      }
      where.add("a.attribution_status = ?");
      binds.add(query.status().trim());
    }
    if (isSet(query.stage())) {
      where.add("a.attributed_stage = ?");
      binds.add(query.stage().trim().toUpperCase(java.util.Locale.ROOT));
    }
    if (isSet(query.skuCode())) {
      where.add("a.sku_code ilike ?");
      binds.add("%" + query.skuCode().trim() + "%");
    }
    if (isSet(query.lotId())) {
      where.add("a.lot_id = ?::uuid");
      binds.add(query.lotId().trim());
    }
    if (isSet(query.keyword())) {
      where.add(
          "(a.attribution_no ilike ? or a.lot_code ilike ? or a.sku_code ilike ?"
              + " or a.attribution_note ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<AttributionRow> rows =
        sql.fetch(
                "select a.*, coalesce(pc.photo_count,0) as photo_count,"
                    + " coalesce(pc.compare_count,0) as compare_count"
                    + from
                    + clause
                    + " order by "
                    + sortClause(query.sort())
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(r -> toRow(r));

    AttributionSummary summary = summary();
    return new AttributionPage(
        rows,
        p,
        s,
        total,
        summary,
        OffsetDateTime.now(ZoneOffset.ofHours(8)),
        "合计与汇总**跨全表**计算，不随当前页变小 —— 翻页时总量跟着变会让运营以为数据在丢。",
        "排序键 " + sortClause(query.sort())
            + " 由 (attributed_at, id) 唯一确定，两次导出的行序不会逐行不同。",
        "环节六值：生产 / 运输 / 仓储 / 描述 / 买家因素 / 待定。"
            + "「待定」是合法结论，不是失败态。");
  }

  /** 排序白名单。⚠️ 每条都带 id 兜底：`id` 是唯一键，没有它两次导出可能逐行不同。 */
  private static String sortClause(String sort) {
    if (sort == null) return "a.created_at desc, a.id desc";
    return switch (sort.trim().toLowerCase(java.util.Locale.ROOT)) {
      case "created_asc" -> "a.created_at, a.id";
      case "attributed_desc" -> "a.attributed_at desc nulls last, a.id desc";
      case "stage" -> "a.attributed_stage nulls last, a.attributed_at desc nulls last, a.id desc";
      case "lot" -> "a.lot_code, a.attributed_at desc nulls last, a.id desc";
      case "qty_desc" -> "a.attributed_qty desc, a.id desc";
      default -> "a.created_at desc, a.id desc";
    };
  }

  /** 跨全表汇总。⚠️ 只数**当前生效**的归因（SUPERSEDED 的那几版是改判留痕，不重复计数）。 */
  private AttributionSummary summary() {
    var r =
        sql.fetchOne(
                "select count(*) as total,"
                    + " count(*) filter (where attribution_status='DRAFT') as draft,"
                    + " count(*) filter (where attribution_status='ATTRIBUTED') as attributed,"
                    + " count(*) filter (where attribution_status='SUPERSEDED') as superseded,"
                    + " count(*) filter (where attributed_stage='PRODUCTION') as production,"
                    + " count(*) filter (where attributed_stage='TRANSPORT') as transport,"
                    + " count(*) filter (where attributed_stage='WAREHOUSE') as warehouse,"
                    + " count(*) filter (where attributed_stage='DESCRIPTION') as description,"
                    + " count(*) filter (where attributed_stage='BUYER_FACTOR') as buyer_factor,"
                    + " count(*) filter (where attributed_stage='UNDETERMINED') as undetermined,"
                    + " count(*) filter (where lot_id is not null) as with_lot,"
                    + " count(*) filter (where lot_id is null) as without_lot,"
                    + " coalesce(sum(attributed_qty) filter (where attribution_status='ATTRIBUTED'),0) as attributed_qty"
                    + " from product_issue_attribution where tenant_id = ?::uuid",
                currentTenant());
    return new AttributionSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("attributed", Long.class),
        r.get("superseded", Long.class),
        r.get("production", Long.class),
        r.get("transport", Long.class),
        r.get("warehouse", Long.class),
        r.get("description", Long.class),
        r.get("buyer_factor", Long.class),
        r.get("undetermined", Long.class),
        r.get("attributed_qty", BigDecimal.class),
        r.get("with_lot", Long.class),
        r.get("without_lot", Long.class),
        sql.fetchOne(
                "select count(*) from product_issue_lot_flag"
                    + " where tenant_id = ?::uuid and flag_status <> 'NONE'",
                currentTenant())
            .get(0, Long.class));
  }

  private AttributionRow toRow(org.jooq.Record r) {
    return new AttributionRow(
        r.get("id", UUID.class),
        r.get("attribution_no", String.class),
        r.get("case_id", UUID.class),
        r.get("inspection_id", UUID.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("lot_produced_on", java.time.LocalDate.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("attributed_qty", BigDecimal.class),
        r.get("attribution_status", String.class),
        AttributionStatus.valueOf(r.get("attribution_status", String.class)).label(),
        r.get("attributed_stage", String.class),
        r.get("stage_label", String.class),
        r.get("attribution_note", String.class),
        r.get("improvement_suggestion", String.class),
        r.get("photo_count", Integer.class) == null ? 0 : r.get("photo_count", Integer.class),
        r.get("compare_count", Integer.class) == null ? 0 : r.get("compare_count", Integer.class),
        r.get("created_at", OffsetDateTime.class),
        r.get("attributed_at", OffsetDateTime.class));
  }

  // ================================================================ 详情

  /** 归因单全景：头 + 照片 + 比对 + 批次标记 + 留痕 + 当前可执行动作。 */
  public AttributionDetail detail(UUID id) {
    Record head =
        sql.fetchOne(
            "select a.*, coalesce(pc.photo_count,0) as photo_count,"
                + " coalesce(pc.compare_count,0) as compare_count"
                + " from product_issue_attribution a"
                + " left join lateral ("
                + "   select count(*) as photo_count, count(c.id) as compare_count"
                + "     from product_issue_photo p"
                + "     left join product_issue_photo_comparison c"
                + "       on c.tenant_id = p.tenant_id and c.left_photo_id = p.id"
                + "    where p.tenant_id = a.tenant_id and p.attribution_id = a.id"
                + " ) pc on true"
                + " where a.tenant_id = ?::uuid and a.id = ?::uuid",
            currentTenant(),
            id);
    // 跨租户在 RLS 下查不到 → 与「不存在」完全相同的错误（不泄露 id 是否存在于别的租户）。
    if (head == null) {
      throw new IllegalArgumentException("归因单不存在或不属于当前租户：" + id);
    }
    AttributionRow row = toRow(head);

    List<PhotoRow> photos =
        sql.fetch(
                "select * from product_issue_photo where tenant_id = ?::uuid and attribution_id = ?::uuid"
                    + " order by registered_at, id",
                currentTenant(),
                id)
            .map(
                r ->
                    new PhotoRow(
                        r.get("id", UUID.class),
                        r.get("attribution_id", UUID.class),
                        r.get("source_photo_id", UUID.class),
                        r.get("photo_role", String.class),
                        PhotoRole.valueOf(r.get("photo_role", String.class)).label(),
                        r.get("object_uri", String.class),
                        r.get("content_sha256", String.class),
                        r.get("content_bytes", Long.class),
                        r.get("content_type", String.class),
                        r.get("captured_at", OffsetDateTime.class),
                        r.get("registered_at", OffsetDateTime.class),
                        r.get("caption", String.class),
                        String.valueOf(r.get("registered_by", UUID.class))));

    List<PhotoComparisonRow> comparisons =
        sql.fetch(
                "select c.*, l.content_sha256 as left_sha, r.content_sha256 as right_sha"
                    + " from product_issue_photo_comparison c"
                    + " join product_issue_photo l"
                    + "   on l.tenant_id = c.tenant_id and l.id = c.left_photo_id"
                    + " join product_issue_photo r"
                    + "   on r.tenant_id = c.tenant_id and r.id = c.right_photo_id"
                    + " where c.tenant_id = ?::uuid and c.attribution_id = ?::uuid"
                    + " order by c.compared_at, c.id",
                currentTenant(),
                id)
            .map(
                r ->
                    new PhotoComparisonRow(
                        r.get("id", UUID.class),
                        r.get("attribution_id", UUID.class),
                        r.get("left_photo_id", UUID.class),
                        r.get("right_photo_id", UUID.class),
                        r.get("left_sha", String.class),
                        r.get("right_sha", String.class),
                        r.get("compare_result", String.class),
                        PhotoCompareResult.valueOf(r.get("compare_result", String.class)).label(),
                        r.get("compare_basis", String.class),
                        r.get("comparison_note", String.class),
                        r.get("compared_by", UUID.class),
                        r.get("compared_at", OffsetDateTime.class)));

    List<LotFlagRow> lotFlags = listFlagsForAttribution(id);
    List<AttributionEventRow> events = events(id);

    return new AttributionDetail(
        row,
        photos,
        comparisons,
        lotFlags,
        events,
        actions(row.status()),
        "照片比对口径：**内容指纹（sha256）一致 / 不一致**，不是图像相似度。"
            + "本仓没有图像库、没有训练好的模型、也没有第三方视觉服务的接入点 —— "
            + "同一件商品在不同光线与角度下拍出的两张照片指纹必然不同，"
            + "编一个相似度数字会让人以为系统看懂了照片，而它其实只比了字节。"
            + "「是不是同一处缺陷」由人工在归因说明里写。",
        "批次真值在 warehouse 独立库（7.3.1.1 / V57 inventory_lot）。"
            + "本模块只经 warehouse 授权 HTTP 端点读，转发调用方自己的 Authorization bearer，"
            + "**绝不传递或信任 X-Tenant-Id 头**，也**绝不改 warehouse 的 inventory_lot**。"
            + "本单上的 lot_code / lot_produced_on 是**登记当时的快照** —— "
            + "批次档案日后被改名或删除，这张归因单仍要能说出「当时是哪个批次」。");
  }

  /** 当前可执行动作（按状态推导，不让界面自己猜）。 */
  private List<AttributionAction> actions(String status) {
    AttributionStatus state = AttributionStatus.valueOf(status);
    boolean draft = state.editable();
    return List.of(
        action(
            "photos",
            "登记 / 替换照片",
            draft,
            "PUT",
            "/trade/product-attribution/attributions/{id}/photos",
            false,
            draft ? null : "归因单已" + state.label() + "，照片不可再改（举证材料要能复现当时的形态）",
            null,
            null,
            null),
        action(
            "compare",
            "照片比对",
            draft,
            "POST",
            "/trade/product-attribution/attributions/{id}/photos/compare",
            false,
            draft ? null : "归因单已" + state.label() + "，比对照准已定",
            null,
            null,
            null),
        action(
            "attribute",
            "出归因结论",
            draft,
            "POST",
            "/trade/product-attribution/attributions/{id}/attribute",
            false,
            draft
                ? null
                : "归因结论**终态不可逆**：判错了只能改判留痕另起一版，不能就地改数",
            null,
            null,
            null),
        action(
            "supersede",
            "改判",
            state == AttributionStatus.ATTRIBUTED,
            "POST",
            "/trade/product-attribution/attributions/{id}/supersede",
            false,
            state == AttributionStatus.ATTRIBUTED
                ? null
                : "只有已归因的单能改判；草稿直接改，归因过的才需要留痕",
            null,
            null,
            null));
  }

  // ================================================================ 建单

  /**
   * 建归因单（恒为 DRAFT）。
   *
   * <p>⚠️ <b>lot_id 会在建单那一刻经 warehouse 的授权端点真查一次</b>，
   * 把 lotCode / producedOn / supplierRefId 写成<b>本地快照</b> ——
   * 「交接凭据自带快照」（与 V172 / V179 同纪律）。
   * 调用方传来的 lotCode 一律<b>不采信</b>：那是可以随便编的字符串。
   */
  public AttributionDetail create(AttributionCreate request, UUID actor, String bearer) {
    if (request == null || request.caseId() == null) {
      throw new IllegalArgumentException("必须给出 case_id：归因单挂在哪张售后单上");
    }
    BigDecimal qty = quantity(request.attributedQty());

    // ---- 批次：只经 warehouse 授权端点读真值，快照写进本单 ----
    String lotCode = "";
    java.time.LocalDate producedOn = null;
    UUID supplierRefId = null;
    if (request.lotId() != null) {
      LotSnapshot snapshot = lots.snapshot(request.lotId(), bearer);
      lotCode = snapshot.lotCode() == null ? "" : snapshot.lotCode();
      producedOn = snapshot.producedOn();
      supplierRefId = snapshot.supplierRefId();
    }

    String skuCode = request.skuCode() == null ? "" : request.skuCode().trim();
    String skuName = request.skuName() == null ? "" : request.skuName().trim();
    UUID skuId = request.skuId();
    // SKU 快照优先取**已判定质检单**上的（那是实物身份），其次取批次档案上的。
    if (request.inspectionId() != null) {
      var snap =
          sql.fetchOne(
              "select i.sku_id, i.sku_code, i.sku_name"
                  + " from return_quality_inspection i"
                  + " where i.tenant_id = ?::uuid and i.id = ?::uuid",
              currentTenant(),
              request.inspectionId());
      if (snap != null) {
        if (skuId == null) skuId = snap.get("sku_id", UUID.class);
        if (skuCode.isEmpty()) skuCode = nullToEmpty(snap.get("sku_code", String.class));
        if (skuName.isEmpty()) skuName = nullToEmpty(snap.get("sku_name", String.class));
      }
    }
    if (skuId == null && request.lotId() != null) {
      var lotSku =
          sql.fetchOne(
              "select sku_id from product_issue_attribution"
                  + " where tenant_id = ?::uuid and lot_id = ?::uuid and sku_id is not null limit 1",
              currentTenant(),
              request.lotId());
      if (lotSku != null) skuId = lotSku.get(0, UUID.class);
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));

    sql.execute(
        "insert into product_issue_attribution ("
            + " id, tenant_id, attribution_no, case_id, inspection_id, reason_set_id,"
            + " lot_id, lot_code, lot_produced_on, lot_supplier_ref_id,"
            + " sku_id, sku_code, sku_name, attributed_qty, attribution_status,"
            + " stage_label, attribution_note, improvement_suggestion,"
            + " created_by, created_at, version)"
            + " values (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid, ?::uuid,"
            + " ?::uuid, ?, ?::date, ?::uuid,"
            + " ?::uuid, ?, ?, ?::numeric, 'DRAFT',"
            + " '', '', '',"
            + " ?::uuid, ?::timestamptz, 0)",
        id,
        currentTenant(),
        sql.fetchValue("select nextval('product_issue_attribution_no_seq')", String.class),
        request.caseId(),
        request.inspectionId(),
        request.reasonSetId(),
        request.lotId(),
        lotCode,
        producedOn,
        supplierRefId,
        skuId,
        skuCode,
        skuName,
        qty.toPlainString(),
        actor,
        now.toString());

    // 原因集合缺省取**当前生效**的那一版：让「售后原因」这个输入句有唯一口径，
    // 而不是每次让操作员现填一次（11.1.2.1 的部分唯一索引保证一单只有一版生效）。
    UUID reasonSetId = request.reasonSetId();
    if (reasonSetId == null) {
      var current =
          sql.fetchOne(
              "select id from aftersales_reason_attribution_set"
                  + " where tenant_id = ?::uuid and case_id = ?::uuid and superseded_at is null",
              currentTenant(),
              request.caseId());
      if (current != null) {
        reasonSetId = current.get(0, UUID.class);
        sql.execute(
            "update product_issue_attribution set reason_set_id = ?::uuid"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            reasonSetId,
            currentTenant(),
            id);
      }
    }

    event(id, AttributionEventType.CREATED, "创建归因单草稿：" + skuCode, actor, now);
    return detail(id);
  }

  // ================================================================ 照片

  /**
   * 登记 / 替换照片（整单替换）。
   *
   * <p>⚠️ <b>只接受对象键 + 内容指纹 + 字节数</b>，<b>不接二进制</b> ——
   * 举证材料进对象存储，本库只存定位键与指纹（与 V172 的 return_receipt_photo 同一口径）。
   */
  public AttributionDetail registerPhotos(UUID id, PhotoRegisterRequest request, UUID actor) {
    AttributionStatus status = requireDraft(id);
    List<PhotoRegister> photos = request == null || request.photos() == null ? List.of() : request.photos();
    if (photos.isEmpty()) {
      throw new IllegalArgumentException("至少要登记一张照片：归因结论要靠实物证据支撑");
    }
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));

    sql.execute(
        "delete from product_issue_photo_comparison where tenant_id = ?::uuid and attribution_id = ?::uuid",
        currentTenant(),
        id);
    sql.execute(
        "delete from product_issue_photo where tenant_id = ?::uuid and attribution_id = ?::uuid",
        currentTenant(),
        id);

    for (PhotoRegister photo : photos) {
      if (photo == null || !isSet(photo.objectUri())) {
        throw new IllegalArgumentException("每张照片都必须给出 object_uri（对象键）");
      }
      String sha = photo.contentSha256() == null ? "" : photo.contentSha256().trim().toLowerCase(java.util.Locale.ROOT);
      if (!sha.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException(
            "content_sha256 必须是 64 位十六进制内容指纹：照片比对比的就是它（不是图像相似度）");
      }
      if (photo.contentBytes() <= 0) {
        throw new IllegalArgumentException("content_bytes 必须大于 0（照片不可能是空文件）");
      }
      OffsetDateTime capturedAt =
          photo.capturedAt() == null ? now : photo.capturedAt();
      sql.execute(
          "insert into product_issue_photo ("
              + " id, tenant_id, attribution_id, source_photo_id, photo_role,"
              + " object_uri, content_sha256, content_bytes, content_type,"
              + " captured_at, registered_at, caption, registered_by)"
              + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?,"
              + " ?, ?, ?::bigint, ?,"
              + " ?::timestamptz, ?::timestamptz, ?, ?::uuid)",
          UUID.randomUUID(),
          currentTenant(),
          id,
          photo.sourcePhotoId(),
          role(photo.photoRole()),
          photo.objectUri().trim(),
          sha,
          photo.contentBytes(),
          photo.contentType() == null ? "" : photo.contentType().trim(),
          capturedAt.toString(),
          now.toString(),
          photo.caption() == null ? "" : photo.caption().trim(),
          actor);
    }
    event(id, AttributionEventType.PHOTO_REGISTERED, "登记照片 " + photos.size() + " 张", actor, now);
    if (status != AttributionStatus.DRAFT) {
      throw new IllegalArgumentException("归因单已不可编辑");
    }
    return detail(id);
  }

  /**
   * 照片比对：<b>结果由服务层按 sha256 真比对得出</b>，传输层不接受调用方指定（硬规则 2）。
   *
   * <p>DB 触发器再独立比一次（V180 比对守卫）—— 两处口径一致才写得进去。
   */
  public AttributionDetail comparePhotos(UUID id, PhotoCompareRequest request, UUID actor) {
    requireDraft(id);
    if (request == null || request.leftPhotoId() == null || request.rightPhotoId() == null) {
      throw new IllegalArgumentException("必须给出 left_photo_id 与 right_photo_id 两张照片");
    }
    if (request.leftPhotoId().equals(request.rightPhotoId())) {
      throw new IllegalArgumentException("左右必须是不同两张照片（自己跟自己比对会让「比对了几对」虚高）");
    }

    String leftSha = photoSha(id, request.leftPhotoId());
    String rightSha = photoSha(id, request.rightPhotoId());
    String result = leftSha.equals(rightSha) ? "IDENTITY_SAME" : "IDENTITY_DIFFER";

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
    sql.execute(
        "insert into product_issue_photo_comparison ("
            + " id, tenant_id, attribution_id, left_photo_id, right_photo_id,"
            + " compare_result, compare_basis, comparison_note, compared_by, compared_at)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?::uuid, ?::uuid, ?, ?, ?, ?::uuid, ?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        id,
        request.leftPhotoId(),
        request.rightPhotoId(),
        result,
        "CONTENT_FINGERPRINT",
        request.comparisonNote() == null ? "" : request.comparisonNote().trim(),
        actor,
        now.toString());

    event(
        id,
        AttributionEventType.PHOTO_COMPARED,
        "照片比对：" + PhotoCompareResult.valueOf(result).label() + "（内容指纹口径）",
        actor,
        now);
    return detail(id);
  }

  private String photoSha(UUID attributionId, UUID photoId) {
    String sha =
        sql.fetchOne(
                "select content_sha256 from product_issue_photo"
                    + " where tenant_id = ?::uuid and attribution_id = ?::uuid and id = ?::uuid",
                currentTenant(),
                attributionId,
                photoId)
            .get(0, String.class);
    if (sha == null) {
      throw new IllegalArgumentException("照片不存在或不属于这张归因单：" + photoId);
    }
    return sha;
  }

  // ================================================================ 归因（唯一状态流转入口）

  /**
   * 出归因结论（硬规则 1）。
   *
   * <p>⚠️ <b>环节 + 理由 + 改进建议三者缺一不可</b>（职责句明写「形成对供应商或流程的改进建议」，
   * DB 层 CHECK 会再拒一次）。归因到「生产」必须挂批次。
   */
  public AttributionDetail attribute(UUID id, AttributionConclude request, UUID actor) {
    requireDraft(id);
    if (request == null || !isSet(request.attributedStage())) {
      throw new IllegalArgumentException("必须给出 attributed_stage（归因环节）");
    }
    AttributionStage stage = stage(request.attributedStage());
    // 生产责任必须挂批次 —— 判的是**这张单实际存的** lot_id，而不是无条件拒 PRODUCTION。
    // （无条件拒会让「挂了批次的生产归因」也写不进去，那不是这条规则要防的事。）
    UUID lotId = lotIdOf(id);
    if (stage.requiresLot() && lotId == null) {
      throw new IllegalArgumentException(
          "归因到「生产」必须挂批次：生产责任的最小单位是批次，"
              + "没有批次就指不到某一次投料，向供应商索赔时说不出「哪一批」");
    }
    String note = text(request.attributionNote(), "归因理由");
    String suggestion = text(request.improvementSuggestion(), "改进建议");

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
    sql.execute(
        "update product_issue_attribution"
            + " set attributed_stage = ?, stage_label = ?, attribution_note = ?,"
            + "   improvement_suggestion = ?, attribution_status = 'ATTRIBUTED',"
            + "   attributed_by = ?::uuid, attributed_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid and attribution_status = 'DRAFT'",
        stage.name(),
        stage.label(),
        note,
        suggestion,
        actor,
        now.toString(),
        currentTenant(),
        id);

    event(id, AttributionEventType.ATTRIBUTED, "归因：" + stage.label(), actor, now);
    // ⚠️ 没有批次就没有「批次质量标记」这回事（不是数据缺失，是这件事本身不成立）。
    if (lotId != null) {
      refreshLotFlag(id, null, null, actor);
    }
    return detail(id);
  }

  /**
   * 改判：<b>终态不可逆</b> —— 原单标记 SUPERSEDED，另起一版 DRAFT 并直接归因。
   *
   * <p>DB 层有部分唯一索引保证一单同时只有一版生效，
   * 所以「把原单就地改掉」在物理上做不到。
   */
  public AttributionDetail supersede(UUID id, AttributionSupersede request, UUID actor) {
    AttributionStatus status = statusOf(id);
    if (status != AttributionStatus.ATTRIBUTED) {
      throw new IllegalArgumentException(
          "只有已归因的单能改判（当前：" + status.label() + "）；草稿直接改，不需要留痕");
    }
    if (request == null || !isSet(request.reason())) {
      throw new IllegalArgumentException("改判必须说明理由（改判是要拿去对质的）");
    }
    AttributionStage stage = stage(request.attributedStage());
    String note = text(request.attributionNote(), "归因理由");
    String suggestion = text(request.improvementSuggestion(), "改进建议");
    UUID caseId = caseIdOf(id);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));

    sql.execute(
        "update product_issue_attribution"
            + " set attribution_status = 'SUPERSEDED', superseded_by = ?::uuid,"
            + "   superseded_at = ?::timestamptz, supersede_reason = ?, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        actor,
        now.toString(),
        request.reason().trim(),
        currentTenant(),
        id);
    event(id, AttributionEventType.SUPERSEDED, "改判：" + request.reason().trim(), actor, now);

    UUID nextId = UUID.randomUUID();
    sql.execute(
        "insert into product_issue_attribution ("
            + " id, tenant_id, attribution_no, case_id, inspection_id, reason_set_id,"
            + " lot_id, lot_code, lot_produced_on, lot_supplier_ref_id,"
            + " sku_id, sku_code, sku_name, attributed_qty, attribution_status,"
            + " stage_label, attribution_note, improvement_suggestion, created_by, created_at, version)"
            + " select ?::uuid, tenant_id,"
            + " (select 'A'||lpad(nextval('product_issue_attribution_no_seq')::text,10,'0')),"
            + " case_id, inspection_id, reason_set_id, lot_id, lot_code, lot_produced_on,"
            + " lot_supplier_ref_id, sku_id, sku_code, sku_name, attributed_qty,"
            + " 'DRAFT', '', '', '', ?::uuid, ?::timestamptz, 0"
            + " from product_issue_attribution where tenant_id = ?::uuid and id = ?::uuid",
        nextId,
        actor,
        now.toString(),
        currentTenant(),
        id);

    return attribute(nextId, new AttributionConclude(stage.name(), note, suggestion), actor);
  }

  // ================================================================ 批次质量标记

  /** 批次标记列表页。 */
  public LotFlagPage listLotFlags(
      String flagStatus, String skuCode, String keyword, int page, int size) {
    int p = Math.max(0, page);
    int s = size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);

    String from = " from product_issue_lot_flag f";
    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("f.tenant_id = ?::uuid");
    binds.add(currentTenant());

    if (isSet(flagStatus)) {
      if (!flagStatus.equals("NONE") && !flagStatus.equals("SUSPECT")
          && !flagStatus.equals("CONFIRMED")) {
        throw new IllegalArgumentException("批次标记状态只能是 NONE / SUSPECT / CONFIRMED");
      }
      where.add("f.flag_status = ?");
      binds.add(flagStatus.trim());
    }
    if (isSet(skuCode)) {
      where.add("f.sku_code ilike ?");
      binds.add("%" + skuCode.trim() + "%");
    }
    if (isSet(keyword)) {
      where.add("(f.lot_code ilike ? or f.flag_note ilike ?)");
      binds.add("%" + keyword.trim() + "%");
      binds.add("%" + keyword.trim() + "%");
    }
    String clause = " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);
    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);

    List<LotFlagRow> rows =
        sql.fetch(
                "select f.*" + from + clause
                    + " order by f.flag_status desc, f.recomputed_at desc, f.id limit ? offset ?",
                pageBinds.toArray())
            .map(this::toFlagRow)
            ;

    Record sum =
        sql.fetchOne(
                "select count(*) filter (where flag_status='NONE') as none,"
                    + " count(*) filter (where flag_status='SUSPECT') as suspect,"
                    + " count(*) filter (where flag_status='CONFIRMED') as confirmed,"
                    + " count(distinct lot_supplier_ref_id) filter (where lot_supplier_ref_id is not null)"
                    + "   as suppliers,"
                    + " coalesce(max(min_flag_qty),0) as min_flag_qty"
                    + " from product_issue_lot_flag where tenant_id = ?::uuid",
                currentTenant());
    LotFlagSummary summary =
        new LotFlagSummary(
            sql.fetchOne(
                    "select count(*) from product_issue_lot_flag where tenant_id = ?::uuid",
                    currentTenant())
                .get(0, Long.class),
            sum.get("none", Long.class),
            sum.get("suspect", Long.class),
            sum.get("confirmed", Long.class),
            sum.get("min_flag_qty", BigDecimal.class) == null
                ? DEFAULT_MIN_FLAG_QTY
                : sum.get("min_flag_qty", BigDecimal.class),
            sum.get("suppliers", Long.class));

    return new LotFlagPage(
        rows,
        p,
        s,
        total,
        summary,
        OffsetDateTime.now(ZoneOffset.ofHours(8)),
        "SUSPECT 要求 **≥ " + DEFAULT_MIN_FLAG_QTY.toPlainString() + " 件**当前生效归因。"
            + "1 件也标「批次有质量问题」会让整个标记体系变成噪声 —— "
            + "这正是蓝图加粗句「同一个批次退回来 10 件」与「10 个批次各退 1 件」要区分的地方："
            + "前者是供应商责任，后者更可能是运输或买家因素。",
        "本表的批次质量标记是 **trade 侧基于归因事实的统计结论**，"
            + "**不是** warehouse 的 inventory_lot（7.3.1.1，后者记的是「这个批次有多少货」）。"
            + "本模块只经 warehouse 授权 HTTP 端点读批次档案，绝不写对方的表 —— "
            + "每张事实表只有一个写入所有者（AGENTS.md 模块边界纪律）。");
  }

  private List<LotFlagRow> listFlagsForAttribution(UUID attributionId) {
    return sql.fetch(
            "select f.* from product_issue_lot_flag f"
                + " where f.tenant_id = ?::uuid and f.lot_id in"
                + "   (select lot_id from product_issue_attribution"
                + "     where tenant_id = ?::uuid and id = ?::uuid and lot_id is not null)"
                + " order by f.recomputed_at desc, f.id",
            currentTenant(),
            currentTenant(),
            attributionId)
        .map(this::toFlagRow);
  }

  /**
   * 按批次读单个质量标记。
   *
   * <p>⚠️ 跨租户在 RLS 下查不到 → 与「不存在」**完全相同**的错误
   * （403 会泄露「这个批次存在」）。
   */
  public LotFlagRow lotFlag(UUID lotId) {
    Record r =
        sql.fetchOne(
            "select * from product_issue_lot_flag where tenant_id = ?::uuid and lot_id = ?::uuid",
            currentTenant(),
            lotId);
    if (r == null) {
      throw new IllegalArgumentException(
          "该批次还没有质量标记：归因单要挂上批次并出归因结论，标记才会出现");
    }
    return toFlagRow(r);
  }

  private LotFlagRow toFlagRow(org.jooq.Record r) {
    return new LotFlagRow(
        r.get("id", UUID.class),
        r.get("lot_id", UUID.class),
        r.get("lot_code", String.class),
        r.get("lot_produced_on", java.time.LocalDate.class),
        r.get("lot_supplier_ref_id", UUID.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("suspect_return_count", Integer.class),
        r.get("affected_return_qty", BigDecimal.class),
        breakdown(r.get("stage_breakdown", String.class)),
        r.get("flag_status", String.class),
        LotFlagStatus.valueOf(r.get("flag_status", String.class)).label(),
        r.get("min_flag_qty", BigDecimal.class),
        r.get("flag_note", String.class),
        r.get("recomputed_at", OffsetDateTime.class),
        String.valueOf(r.get("flagged_by", UUID.class)));
  }

  /**
   * 从 jsonb 解析环节分布。
   *
   * <p>⚠️ jOOQ 读 jsonb 回来是 PGobject，**不会**自动变成 Map
   * （照抄 DropshipTrackingService 的同一纪律）—— 所以先按字符串取出再用 Jackson 解析。
   *
   * <p>⚠️ 六个环节**全部出现**，缺键补 0 —— 少列一个环节，界面就显示成空白，
   * 而空白会被读成「没有这个数据」而不是「真的是 0」。
   */
  private StageBreakdown breakdown(String raw) {
    if (raw == null || raw.isBlank()) {
      return new StageBreakdown(0, 0, 0, 0, 0, 0);
    }
    try {
      var map = json.readValue(raw, Map.class);
      return new StageBreakdown(
          intAt(map, "PRODUCTION"),
          intAt(map, "TRANSPORT"),
          intAt(map, "WAREHOUSE"),
          intAt(map, "DESCRIPTION"),
          intAt(map, "BUYER_FACTOR"),
          intAt(map, "UNDETERMINED"));
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException("环节分布不是合法的 jsonb 对象：" + raw, e);
    }
  }

  private static int intAt(Map<?, ?> map, String key) {
    var raw = map.get(key);
    if (raw == null) return 0;
    return raw instanceof Number n ? n.intValue() : Integer.parseInt(String.valueOf(raw));
  }

  /**
   * 刷新批次质量标记（硬规则 3）。
   *
   * <p>服务层按**当前生效**归因聚合 → 写入 → <b>DB 触发器独立重算并比对</b>，不等就拒。
   * 调用方<b>无法</b>传入统计值：{@link LotFlagRefresh} 里根本没有这三个字段。
   */
  public LotFlagRow refreshLotFlag(UUID attributionId, String flagNote, String flagStatus, UUID actor) {
    UUID lotId = lotIdOf(attributionId);
    if (lotId == null) {
      throw new IllegalArgumentException("这张归因单没有挂批次，无从做批次质量标记");
    }
    var snap =
        sql.fetchOne(
            "select lot_code, lot_produced_on, lot_supplier_ref_id, sku_id, sku_code"
                + " from product_issue_attribution where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            attributionId);
    if (snap == null) {
      throw new IllegalArgumentException("归因单不存在或不属于当前租户：" + attributionId);
    }

    // ---- 真聚合：只数当前生效归因（SUPERSEDED 是改判留痕，不重复计数）----
    var agg =
        sql.fetchOne(
                "select count(*) as cnt, coalesce(sum(attributed_qty),0) as qty"
                    + " from product_issue_attribution"
                    + " where tenant_id = ?::uuid and lot_id = ?::uuid"
                    + "   and attribution_status = 'ATTRIBUTED'",
                currentTenant(),
                lotId);
    int count = agg.get("cnt", Integer.class);
    BigDecimal qty = agg.get("qty", BigDecimal.class);

    String breakdownJson =
        sql.fetchOne(
            "select coalesce(jsonb_object_agg(stage, cnt), '{}'::jsonb)::text from ("
                + " select attributed_stage as stage, count(*)::bigint as cnt"
                + "   from product_issue_attribution"
                + "  where tenant_id = ?::uuid and lot_id = ?::uuid"
                + "    and attribution_status = 'ATTRIBUTED'"
                + "  group by attributed_stage) s",
            currentTenant(),
            lotId).get(0, String.class);

    // 口径：⚠️ 判据落在**件数**而不是归因单张数 ——
    // 蓝图加粗句「同一个批次退回来 10 件」vs「10 个批次各退 1 件」：
    // 一张单退 10 件和十张单各退 1 件，在这个批次上都是「10 件」，都该标；
    // 而后者每个批次都只有 1 件，一个都不该被标。按张数判会把这两者判反。
    String status = "NONE";
    if (qty.compareTo(DEFAULT_MIN_FLAG_QTY) >= 0) {
      status = isSet(flagStatus) ? flagStatus.trim().toUpperCase(java.util.Locale.ROOT) : "SUSPECT";
      if (!status.equals("SUSPECT") && !status.equals("CONFIRMED") && !status.equals("NONE")) {
        throw new IllegalArgumentException("批次标记状态只能是 NONE / SUSPECT / CONFIRMED");
      }
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.ofHours(8));
    // ⚠️ fetchOne 无行时返回 **null**（不是空记录），直接 .get(0,…) 会抛 NPE。
    boolean exists =
        sql.fetchOne(
                "select 1 from product_issue_lot_flag where tenant_id = ?::uuid and lot_id = ?::uuid",
                currentTenant(),
                lotId)
            != null;

    if (exists) {
      sql.execute(
          "update product_issue_lot_flag set suspect_return_count = ?, affected_return_qty = ?::numeric,"
              + " stage_breakdown = ?::jsonb, flag_status = ?, flag_note = ?, min_flag_qty = ?::numeric,"
              + " recomputed_at = ?::timestamptz, flagged_by = ?::uuid"
              + " where tenant_id = ?::uuid and lot_id = ?::uuid",
          count,
          qty.toPlainString(),
          breakdownJson,
          status,
          flagNote == null ? "" : flagNote.trim(),
          DEFAULT_MIN_FLAG_QTY.toPlainString(),
          now.toString(),
          actor,
          currentTenant(),
          lotId);
    } else {
      sql.execute(
          "insert into product_issue_lot_flag ("
              + " id, tenant_id, lot_id, lot_code, lot_produced_on, lot_supplier_ref_id,"
              + " sku_id, sku_code, suspect_return_count, affected_return_qty, stage_breakdown,"
              + " flag_status, min_flag_qty, flag_note, recomputed_at, flagged_by)"
              + " values (?::uuid, ?::uuid, ?::uuid, ?, ?::date, ?::uuid, ?::uuid, ?, ?, ?::numeric,"
              + " ?::jsonb, ?, ?::numeric, ?, ?::timestamptz, ?::uuid)",
          UUID.randomUUID(),
          currentTenant(),
          lotId,
          nullToEmpty(snap.get("lot_code", String.class)),
          snap.get("lot_produced_on", java.time.LocalDate.class),
          snap.get("lot_supplier_ref_id", UUID.class),
          snap.get("sku_id", UUID.class),
          nullToEmpty(snap.get("sku_code", String.class)),
          count,
          qty.toPlainString(),
          breakdownJson,
          status,
          DEFAULT_MIN_FLAG_QTY.toPlainString(),
          flagNote == null ? "" : flagNote.trim(),
          now.toString(),
          actor);
    }

    event(attributionId, AttributionEventType.LOT_FLAGGED,
        "刷新批次标记 " + lotId + "：" + count + " 件 / " + LotFlagStatus.valueOf(status).label(),
        actor, now);
    return lotFlag(lotId);
  }

  // ================================================================ 留痕

  private List<AttributionEventRow> events(UUID attributionId) {
    return sql.fetch(
            "select * from product_issue_event where tenant_id = ?::uuid and attribution_id = ?::uuid"
                + " order by occurred_at, id",
            currentTenant(),
            attributionId)
        .map(
            r ->
                new AttributionEventRow(
                    r.get("id", UUID.class),
                    r.get("attribution_id", UUID.class),
                    r.get("event_type", String.class),
                    AttributionEventType.valueOf(r.get("event_type", String.class)).label(),
                    r.get("event_note", String.class),
                    r.get("occurred_by", UUID.class),
                    r.get("occurred_at", OffsetDateTime.class)))
        ;
  }

  private void event(UUID attributionId, AttributionEventType type, String note, UUID actor, OffsetDateTime at) {
    sql.execute(
        "insert into product_issue_event ("
            + " id, tenant_id, attribution_id, event_type, event_note, occurred_by, occurred_at)"
            + " values (?::uuid, ?::uuid, ?::uuid, ?, ?, ?::uuid, ?::timestamptz)",
        UUID.randomUUID(),
        currentTenant(),
        attributionId,
        type.name(),
        note == null ? "" : (note.length() > 1000 ? note.substring(0, 1000) : note),
        actor,
        at.toString());
  }

  // ================================================================ 校验助手

  /** 取归因单状态；跨租户在 RLS 下查不到 → 与「不存在」同一个错误（不泄露 id 是否存在）。 */
  AttributionStatus statusOf(UUID id) {
    Record r =
        sql.fetchOne(
            "select attribution_status from product_issue_attribution"
                + " where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    // ⚠️ 查不到时 fetchOne 返回 **null** 而不是空记录 —— 直接 .get(0,…) 会抛 NPE，
    //   而 NPE 会被当成 500 返回，把「跨租户访问」伪装成「服务端故障」。
    String status = r == null ? null : r.get(0, String.class);
    if (status == null) {
      throw new IllegalArgumentException("归因单不存在或不属于当前租户：" + id);
    }
    return AttributionStatus.valueOf(status);
  }

  /** 取归因单挂的批次；没挂返回 null（"没挂批次"与"挂了个空批次"不是同一件事）。 */
  UUID lotIdOf(UUID id) {
    Record r =
        sql.fetchOne(
            "select lot_id from product_issue_attribution where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    return r == null ? null : r.get(0, UUID.class);
  }

  private UUID caseIdOf(UUID id) {
    return sql.fetchOne(
            "select case_id from product_issue_attribution where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id)
        .get(0, UUID.class);
  }

  private AttributionStatus requireDraft(UUID id) {
    AttributionStatus status = statusOf(id);
    if (status != AttributionStatus.DRAFT) {
      throw new IllegalArgumentException(
          "归因单当前是「"
              + status.label()
              + "」，只有草稿可编辑 —— 归因结论是要拿去对质的，"
              + "判错了只能改判留痕另起一版，不能就地改数");
    }
    return status;
  }

  private static AttributionStage stage(String raw) {
    AttributionStage stage;
    try {
      stage = AttributionStage.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "归因环节只能是 PRODUCTION / TRANSPORT / WAREHOUSE / DESCRIPTION / BUYER_FACTOR / UNDETERMINED"
              + "（生产 / 运输 / 仓储 / 描述 / 买家因素 / 待定）");
    }
    return stage;
  }

  private static String role(String raw) {
    try {
      return PhotoRole.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT)).name();
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          "照片角色只能是 PRODUCT_OVERVIEW / DEFECT_CLOSEUP / PACKAGING / LABEL_BATCH / OTHER");
    }
  }

  private static BigDecimal quantity(BigDecimal raw) {
    if (raw == null) {
      throw new IllegalArgumentException("必须给出 attributed_qty（归因数量，数量一律 NUMERIC，禁浮点）");
    }
    BigDecimal qty = raw.setScale(QTY_SCALE, java.math.RoundingMode.HALF_UP);
    if (qty.signum() <= 0) {
      throw new IllegalArgumentException("归因数量必须大于 0");
    }
    return qty;
  }

  private static String text(String raw, String label) {
    if (!isSet(raw)) {
      throw new IllegalArgumentException(
          "必须给出" + label + "（归因结论是要拿去和供应商 / 平台对质的，"
              + "「点一下下拉框」出来的结论在纠纷里等于没有结论）");
    }
    return raw.trim();
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String nullToEmpty(String value) {
    return value == null ? "" : value;
  }

  /** 当前租户：由 backend/libraries/tenancy 在事务内绑定，客户端头改不动它。 */
  private UUID currentTenant() {
    return UUID.fromString(
        sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }
}
