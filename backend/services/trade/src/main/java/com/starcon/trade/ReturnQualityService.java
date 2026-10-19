package com.starcon.trade;

import static com.starcon.trade.ReturnQualityModels.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.springframework.stereotype.Service;

/**
 * 11.2.3.1 质检与判定。
 *
 * <p><b>本类是 V179 八张新表的写入所有者</b>（return_quality_category / return_quality_category_sku /
 * return_quality_template / return_quality_template_item / return_quality_inspection /
 * return_quality_check_result / return_quality_disposition_instruction / return_quality_event），
 * 对 V172 的 return_* 簇与 V99 的 sales_order 簇<b>只读</b>。
 * 刻意不加外键、不 ALTER 既有表 ⇒ 跨车道 DDL 回归面 = 0（同批另有三条车道也在往 trade 落迁移）。
 *
 * <p><b>六条硬纪律</b>：
 *
 * <ol>
 *   <li><b>每一条查询都显式带 {@code tenant_id} 谓词</b>，不依赖 RLS 当唯一防线。
 *       质检结论决定这批货以什么身份重新进入销售，而逐项结果与判定理由是要拿去索赔的举证材料，
 *       端点又大多是<b>按 id 直达</b>的 URL —— 漏掉租户谓词就是跨租户泄露。</li>
 *   <li><b>判定是三值枚举状态机，不是布尔开关</b>（硬规则 1）。判定只走
 *       {@link #conclude} 一个入口，并且<b>服务层与 DB 触发器各判一次</b> ——
 *       只判一次的话，绕过服务层直连 SQL 就能把「缺配件」判成可再销售。
 *       两条路径的分野在 {@link QualityVerdict#allowed} 里写死，不在 if 里临场决定。</li>
 *   <li><b>残值真算，调用方传不进来</b>（硬规则 2）：{@link #salvage} 是唯一的算法实现，
 *       原值实时读订单行单价 × 实收数量，系数来自模板与失败检项；
 *       算出来的金额写库后由 DB 触发器<b>重算比对</b>，不等就拒。</li>
 *   <li><b>不写质量状态指令</b>：{@code return_quality_disposition_instruction} 由 V179 触发器
 *       在判定那一刻生成，本服务<b>没有任何</b>写它的代码路径 ⇒ 「凭空造一张『这批货可再销售』的指令」
 *       在本模块没有代码路径。</li>
 *   <li><b>时间绑定</b>：时间一律 {@code ?::timestamptz} + {@link OffsetDateTime}。
 *       {@code Timestamp.valueOf(LocalDateTime)} 会静默偏 −28800s，
 *       而判定时刻偏 8 小时会让「这批货在待检区压了多久」整体算错。</li>
 *   <li><b>状态流转只有一个入口</b>（{@link #conclude} / {@link #cancel}）：每次改状态都过版本校验，都写留痕。</li>
 * </ol>
 */
@Service
public class ReturnQualityService {

  /** 列表单页上限。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 金额与成色系数的刻度：金额 4 位（与 sales_order_line.unit_price 同刻度），系数 6 位。 */
  private static final int MONEY_SCALE = 4;

  private static final int RATE_SCALE = 6;

  private static final BigDecimal ONE = BigDecimal.ONE.setScale(RATE_SCALE);

  private final DSLContext sql;

  public ReturnQualityService(DSLContext sql) {
    this.sql = sql;
  }

  // ================================================================ 能力声明

  /**
   * 能力声明：职责句的四个动词逐块报可用性，下游逐个报 delivered=false 与归属模块号。
   *
   * <p>让界面能说清「为什么这批货不能判可再销售」「为什么处置还没做」，
   * 而不是让它们显示成空白让人以为「这些能力没做」。
   */
  public ReturnQualityCapabilities capabilities() {
    return new ReturnQualityCapabilities(
        List.of(
            "待检入库分录 return_inventory_posting + 实收行 return_receipt_line（V172，同库只读）—— 待检队列与两个判定维度的真值来源",
            "订单行 sales_order_line / 订单头 sales_order（V99 实时读）—— **原值 = 单价 × 实收数量**，残值不允许调用方传入，所以原值只能从这里取",
            "质检类目 return_quality_category + 类目 SKU 映射 return_quality_category_sku（V179，**租户自维护**）—— 类目真值在 catalog 服务，跨库读不到",
            "质检模板 return_quality_template + 检项 return_quality_template_item（V179，按类目取用，带成色系数与三档上限）",
            "质检单 return_quality_inspection + 逐项结果 return_quality_check_result（V179）",
            "质量状态指令 return_quality_disposition_instruction（V179，**由 DB 触发器在判定那一刻生成**，运行角色 UPDATE/DELETE 已收回）",
            "质检留痕 return_quality_event（V179，追加式，由 DB 触发器按状态变更自动追加）"),
        List.of(
            section("appearance", "外观", true, null,
                "由模板检项表达：APPEARANCE 维度的检项失败**不禁止**判可再销售，"
                    + "它扣的是成色系数（salvage_rate），不是可售资格 —— 外观瑕疵品折价再售是行业常态。",
                "return_quality_template_item（V179）"),
            section("function", "功能", true, null,
                "FUNCTION 维度的检项失败**禁止**判可再销售（只能残次或报废）："
                    + "功能坏了的机器当新品卖出去，收货的人会退货，赔的是我们的运费和口碑。",
                "return_quality_check_result + V179 守卫⑤ 路径②"),
            section("accessory", "配件完整性", true, null,
                "ACCESSORY 维度失败（或入库维度 goods_condition=MISSING_PARTS）"
                    + "⇒ **只能判残次**：既不许判可再销售，也不许判报废。"
                    + "蓝图加粗句「缺少配件则应判残次」的落点。"
                    + "配件缺常常是仓库漏装，商品损坏常常是运输或买家，索赔方向不一样，所以单列成一个维度。",
                "return_quality_check_result + V179 守卫⑤ 路径①"),
            section("resalability", "是否可再销售（判定）", true, null,
                "判定是**三值枚举**（RESALABLE / DEFECTIVE / SCRAPPED），不是布尔开关。"
                    + "三条路径：①配件缺失⇒只能残次；②商品损坏或功能失效⇒可再销售被禁；"
                    + "③**外包装破损 / 损毁 / 无包装一律不禁止可再销售** —— "
                    + "蓝图加粗句的左半，这里刻意什么都不判。",
                "return_quality_inspection.verdict + V179 守卫⑤"),
            section("salvageEstimate", "残值估算", true, null,
                "真算，不是让调用方填一个数：原值 = 订单行单价 × 实收数量（实时读，订单行改价残值跟着改）；"
                    + "扣减 = Σ(FAIL 检项的 salvage_rate)；系数 = clamp(基准 − 扣减, 下限, 1)；"
                    + "残值 = round(原值 × min(系数, 档位上限), 4)。"
                    + "⚠️ **传输层没有残值字段**，且算出的金额写库后由 DB 触发器**重算比对**，不等就拒。"
                    + "全程 NUMERIC(19,4) + BigDecimal，**禁浮点**。",
                "return_quality_inspection（V179 守卫⑤）+ 三档上限列"),
            section("templateByCategory", "质检模板（按类目）", true, null,
                "模板与类目的对应关系真落库：类目租户自维护（类目真值在 catalog 服务，跨库读不到），"
                    + "SKU→类目的映射也是租户维护的真数据。"
                    + "⚠️ **拿不到类目就没有模板**：开质检单时若该 SKU 尚未归类，直接报错并说明去哪儿维护，"
                    + "而不是悄悄退回到一个「默认模板」。",
                "return_quality_template + return_quality_category_sku（V179）"),
            section("dispositionInstruction", "质量状态指令（输出）", true, null,
                "判定那一刻由 **DB 触发器**生成一张指令：指令类型与目标库存状态由结论**派生**，"
                    + "数量与残值直取质检单（残值本身已被按可审阅规则校验过）。"
                    + "⚠️ 本模块**只出不消费**：`instruction_status` 恒 PENDING、消费方恒 11.2.3.2，"
                    + "运行角色对该表的 UPDATE/DELETE 已收回。",
                "return_quality_disposition_instruction（V179 触发器⑤）"),
            section("dispositionApply", "转可售 / 残次 / 报废（库存流转）", false, "11.2.3.2",
                "**未交付**。本模块因此**没有**任何改库存状态的端点，传输层也不提供对应字段 —— "
                    + "不是没做按钮，是接口层面就不接受这个动作。",
                null),
            section("salvageAccounting", "残值财务处理（损耗 / 冲减应付）", false, "12 财务",
                "**未交付**。本模块输出残值金额与币种（NUMERIC(19,4) + 币种码），"
                    + "但**不写任何财务分录** —— 跨服务写账是模块边界纪律明令禁止的。",
                null),
            section("supplierClaim", "供应商索赔", false, "11.2.3.3",
                "**未交付**。本模块的逐项结果、判定理由与照片引用是它将来的举证材料，"
                    + "但**不产出任何索赔结论**。",
                null),
            section("batchAttribution", "批次质量归因", false, "11.1.2.2",
                "**未交付**。本模块已按 SKU 与批次留存质检结论，是它将来归因的输入。",
                null),
            section("defectResale", "残次品清仓 / 调价", false, "5 调价",
                "**未交付**。残次品的处置（打折清仓、拆件卖）属 5 调价与 11.2.3.2。",
                null)),
        List.of(
            related("MODULE_11_2_2_1", "11.2.2.1 退货入库", true, null,
                "待检入库分录与两个判定维度（V172，已交付）。本模块的**唯一**入口是它的待检队列。",
                "/trade/return-receipts"),
            related("MODULE_11_2_1_1", "11.2.1.1 退货授权单", true, null,
                "授权量与「批没批」（V161，已交付）。本模块只读引用它挂出来的实收行。",
                "/aftersales/returns"),
            related("MODULE_11_2_3_2", "11.2.3.2 转可售/残次/报废", false, "11.2.3.2",
                "消费质量状态指令、把待检真正流转到可售 / 残次 / 报废**未交付**。"
                    + "⇒ 指令当前恒为 PENDING，本模块**没有**流转端点。",
                null),
            related("MODULE_12_FINANCE", "12 财务（残值处理）", false, "12 财务",
                "残值损耗入账、冲减应付**未交付**，且是**独立数据库** ⇒ 本服务按模块边界纪律不写财务分录。",
                null),
            related("MODULE_1_CATALOG", "1 商品中心（类目）", false, "1 商品中心 / 10 接口",
                "商品类目真值在 catalog 服务的**独立数据库**里，按 AGENTS.md 模块边界纪律"
                    + "**禁止跨服务 SQL / 视图 / FK**，本服务读不到也写不进去。"
                    + "⇒ 质检类目是租户自维护的作业口径，不是商品主数据的复制品。",
                null)),
        List.of(
            action("CREATE_INSPECTION", "开质检单", true, "POST", "/trade/return-quality/inspections",
                false, "该待检分录还没有在途质检单，且其 SKU 已归类", null, null, null),
            action("RECORD_RESULTS", "录逐项结果", true, "PUT",
                "/trade/return-quality/inspections/{id}", false, "仅 DRAFT", null, null, null),
            action("CONCLUDE", "判定（结论 + 残值）", true, "POST",
                "/trade/return-quality/inspections/{id}/conclude", false,
                "仅 DRAFT，强制检项全部有结果，结论在允许集合内", null, null, null),
            action("CANCEL", "撤销", true, "POST",
                "/trade/return-quality/inspections/{id}/cancel", false, "仅 DRAFT", null, null, null),
            action("ACTIVATE_TEMPLATE", "启用模板", true, "POST",
                "/trade/return-quality/templates/{id}/activate", false,
                "仅 DRAFT，且至少有一个检项", null, null, null),
            action("APPLY_DISPOSITION", "执行处置（转可售 / 残次 / 报废）", false, null, null, false,
                "仅已判定", null, "11.2.3.2",
                "**未交付**。本模块**刻意没有**这个端点：报废是不可逆的、需审批与原因，"
                    + "而质检结论刚出来的那一刻正是最需要人看一眼的时候。"),
            action("POST_SALVAGE", "残值入账", false, null, null, false, "仅已判定", null, "12 财务",
                "**未交付**，且跨服务写账被模块边界纪律禁止。"),
            action("CLAIM_SUPPLIER", "发起供应商索赔", false, null, null, false, "仅已判定为残次",
                null, "11.2.3.3", "**未交付**。本模块只提供举证材料，不产出索赔结论。")),
        List.of(
            boundary("不提供「直接转可售」的端点，传输层也不提供能改库存状态的字段", "11.2.3.2",
                "质量状态指令只出不消费。放行一个流转入口等于让界面显示成「判完就能上架」，"
                    + "而报废是不可逆的、需审批与原因。"),
            boundary("判定结论与残值是终态，不可改", "11.2.3.2",
                "判错了只能作废重判。结论是要拿去和买家、供应商、平台对质的，"
                    + "「这批货到底判成什么」出现两个答案比判错更难收拾。"),
            boundary("传输层没有残值字段，残值由公式算出并被 DB 重算校验", "12 财务",
                "残值是财务数据。一个可填的金额等于把损耗口径交给每个调用方自己编，"
                    + "而禁浮点与可审阅是它能进账的前提。"),
            boundary("不写任何财务分录、不写库存表", "12 财务 / 7 库存 / 8 仓库",
                "跨服务禁止 SQL。本服务输出残值与指令，消费侧在别的服务的独立库里。"),
            boundary("不产出索赔与质量归因结论", "11.2.3.3 / 11.1.2.2",
                "本模块的逐项结果、判定理由与照片引用是它们的输入，不是它们的结论。")),
        "DRAFT → CONCLUDED（终态）/ CANCELLED（终态）。"
            + "⚠️ 已判定**整行冻结**（V179 守卫⑥）：结论、SKU 快照、模板口径一起冻结 —— "
            + "允许事后改 SKU 或模板，等于让同一个结论可以配上一份不同的证据。",
        "⚠️ **判定要保守，且两条路径不同**。"
            + "判定是三值枚举而不是布尔开关；三条显式路径："
            + "①配件缺失⇒**只能判残次**（不许判可再销售，也不许判报废）；"
            + "②商品本体损坏 / 功能失效⇒**可再销售被禁**（残次或报废）；"
            + "③**外包装破损 / 损毁 / 无包装一律不禁止可再销售**。"
            + "服务层与 DB 触发器各判一次，判定口径由入库时记的两个维度**与**本次失败检项的维度共同决定 —— "
            + "只看其中一边的话，另一边就成了可以绕过的后门。",
        "残值 = round(原值 × min(系数, 档位上限), 4)；原值 = 订单行单价 × 实收数量（实时读订单行）；"
            + "系数 = clamp(模板基准系数 − Σ(FAIL 检项扣减), 模板下限, 1)；"
            + "档位上限按判定档位取（可再销售 / 残次 / 报废三档各自的 cap）。"
            + "⚠️ NUMERIC(19,4) + BigDecimal，**禁浮点**；传输层没有残值字段；"
            + "DB 触发器重算比对，不一致当场拒。",
        "质量状态指令由 **DB 触发器**在判定那一刻生成，指令类型与目标库存状态由结论**派生**，"
            + "不接受调用方传入。⚠️ `instruction_status` **恒为 PENDING**、消费方**恒为 11.2.3.2** —— "
            + "处置属 11.2.3.2 未交付，运行角色对该表的 UPDATE/DELETE 已收回，"
            + "所以「在本模块里把它改成已消费」既没有代码路径也没有 SQL 权限。",
        "模板按类目取用：类目与 SKU→类目映射都是租户自维护的真数据"
            + "（类目真值在 catalog 服务的独立库里，跨服务禁止 SQL）。"
            + "⚠️ 模板 ACTIVE 之后**不许就地改检项**（改版会改变判定口径，而历史质检单要能复现当时的口径）—— "
            + "要改就新建一个版本号。");
  }

  // ================================================================ 类目

  /** 类目列表：真分页 + 状态筛选 + 关键字。 */
  public QualityCategoryPage listCategories(String status, String keyword, Integer page, Integer size) {
    int p = page == null || page < 0 ? 0 : page;
    int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);

    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("c.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (isSet(status)) {
      where.add("c.category_status = ?");
      binds.add(parseEnum(QualityCategoryStatus.class, status, "类目状态").name());
    }
    if (isSet(keyword)) {
      where.add("(c.category_code ilike ? or c.category_name ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
    }
    String clause = " where " + String.join(" and ", where);

    // ⚠️ count 查询必须带 FROM：这里的 where 子句是以 `c.` 开头的别名谓词。
    long total =
        sql.fetchOne("select count(*) from return_quality_category c" + clause, binds.toArray())
            .get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<QualityCategoryRow> rows =
        sql.fetch(
                "select c.*,"
                    + " (select count(*) from return_quality_category_sku m"
                    + "   where m.tenant_id = c.tenant_id and m.category_id = c.id) as sku_count,"
                    + " (select count(*) from return_quality_template t"
                    + "   where t.tenant_id = c.tenant_id and t.category_id = c.id) as template_count,"
                    + " (select count(*) from return_quality_template t"
                    + "   where t.tenant_id = c.tenant_id and t.category_id = c.id"
                    + "     and t.template_status = 'ACTIVE') as active_template_count"
                    + " from return_quality_category c"
                    + clause
                    + " order by c.category_code, c.id"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(
                r -> {
                  QualityCategoryStatus st =
                      QualityCategoryStatus.valueOf(r.get("category_status", String.class));
                  return new QualityCategoryRow(
                      r.get("id", UUID.class),
                      r.get("category_code", String.class),
                      r.get("category_name", String.class),
                      st.name(),
                      st.label(),
                      r.get("sku_count", Long.class),
                      r.get("template_count", Long.class),
                      r.get("active_template_count", Long.class));
                });

    return new QualityCategoryPage(
        rows,
        total,
        OffsetDateTime.now(ZoneOffset.UTC),
        "类目是**租户自维护的作业口径**，不是商品主数据的复制品：商品类目真值在 catalog 服务的独立数据库里，"
            + "按 AGENTS.md 模块边界纪律**禁止跨服务 SQL / 视图 / FK**，本服务读不到。"
            + "SKU→类目的映射没建的话，开质检单会直接报错（而不是悄悄用一个「默认模板」）—— "
            + "拿家电模板去检衣服，代价比多录一条映射大得多。");
  }

  /** 建类目。 */
  public QualityCategoryRow createCategory(QualityCategoryCreate request, UUID userId) {
    if (!isSet(request.categoryCode())) {
      throw new IllegalArgumentException("必须给出类目编码 category_code（报表与模板口径的稳定锚点）");
    }
    if (!isSet(request.categoryName())) {
      throw new IllegalArgumentException("必须给出类目名称");
    }
    UUID id = UUID.randomUUID();
    // ⚠️ 编码是租户内唯一的**稳定锚点**：重复编码会静默地把模板挂到「另一个」类目上，
    //   而报表口径仍然是旧编码 —— 所以重复要报错，不是 do nothing。
    long sameCode =
        sql.fetchOne(
                "select count(*) from return_quality_category"
                    + " where tenant_id = ?::uuid and category_code = ?",
                currentTenant(),
                request.categoryCode().trim())
            .get(0, Long.class);
    if (sameCode > 0) {
      throw new IllegalArgumentException("质检类目编码已存在：" + request.categoryCode().trim());
    }
    sql.execute(
        "insert into return_quality_category(id,tenant_id,category_code,category_name,note,created_by)"
            + " values(?::uuid,?::uuid,?,?,?,?::uuid)",
        id,
        currentTenant(),
        request.categoryCode().trim(),
        request.categoryName().trim(),
        trimOrEmpty(request.note()),
        userId);
    return category(id);
  }

  /** 改类目名称 / 启停。刻意不给改编码的入口。 */
  public QualityCategoryRow updateCategory(UUID id, QualityCategoryUpdate request) {
    Record head = requireCategory(id);
    QualityCategoryStatus status =
        parseEnum(QualityCategoryStatus.class, request.categoryStatus(), "类目状态");
    sql.execute(
        "update return_quality_category"
            + " set category_name = ?, category_status = ?, note = ?, updated_at = ?::timestamptz"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        request.categoryName() == null || request.categoryName().isBlank()
            ? head.get("category_name", String.class)
            : request.categoryName().trim(),
        status.name(),
        trimOrEmpty(request.note()),
        OffsetDateTime.now(ZoneOffset.UTC),
        currentTenant(),
        id);
    return category(id);
  }

  /** 把一个 SKU 归到质检类目（蓝图「模板按类目取用」的解析依据）。 */
  public void mapSku(UUID categoryId, QualitySkuMappingCreate request, UUID userId) {
    if (request.skuId() == null) {
      throw new IllegalArgumentException("必须给出 sku_id（按类目取模板靠的是它）");
    }
    requireCategory(categoryId);
    // 一个 SKU 只能归一个类目（表级 UNIQUE）：归两个会让「该用哪个模板」没有唯一答案。
    sql.execute(
        "insert into return_quality_category_sku(id,tenant_id,category_id,sku_id,sku_code,mapped_by)"
            + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?::uuid)"
            + " on conflict (tenant_id,sku_id) do update"
            + "   set category_id = excluded.category_id,"
            + "       sku_code = excluded.sku_code,"
            + "       mapped_by = excluded.mapped_by,"
            + "       mapped_at = ?::timestamptz",
        UUID.randomUUID(),
        currentTenant(),
        categoryId,
        request.skuId(),
        trimOrEmpty(request.skuCode()),
        userId,
        OffsetDateTime.now(ZoneOffset.UTC));
  }

  // ================================================================ 模板

  /** 模板列表：真分页 + 状态 / 类目筛选 + 关键字 + 排序白名单 + 跨全表汇总。 */
  public QualityTemplatePage listTemplates(
      UUID categoryId, String status, String keyword, Integer page, Integer size, String sort) {
    int p = page == null || page < 0 ? 0 : page;
    int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);
    QualityTemplateSort order = QualityTemplateSort.parse(sort);

    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("t.tenant_id = ?::uuid");
    binds.add(currentTenant());
    if (categoryId != null) {
      where.add("t.category_id = ?::uuid");
      binds.add(categoryId);
    }
    if (isSet(status)) {
      where.add("t.template_status = ?");
      binds.add(parseEnum(QualityTemplateStatus.class, status, "模板状态").name());
    }
    if (isSet(keyword)) {
      where.add("(t.template_no ilike ? or t.template_name ilike ? or c.category_name ilike ?)");
      String like = "%" + keyword.trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    String clause = " where " + String.join(" and ", where);
    String from =
        " from return_quality_template t"
            + " join return_quality_category c on c.tenant_id = t.tenant_id and c.id = t.category_id"
            + " left join lateral ("
            + "   select count(*) as item_count,"
            + "          count(*) filter (where i.item_required) as mandatory_count"
            + "     from return_quality_template_item i"
            + "    where i.tenant_id = t.tenant_id and i.template_id = t.id) it on true";

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<QualityTemplateRow> rows =
        sql.fetch(
                "select t.*, c.category_code, c.category_name, it.item_count, it.mandatory_count"
                    + from
                    + clause
                    + " order by "
                    + order.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(ReturnQualityService::templateRow);

    return new QualityTemplatePage(
        rows,
        p,
        s,
        total,
        templateSummary(clause, binds),
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "排序白名单四个键（created_desc / activated_desc / version_desc / category_asc）；其余落回 created_desc。");
  }

  /** 模板详情：检项全量 + 「能判成什么」的说明。 */
  public QualityTemplateDetail templateDetail(UUID id) {
    Record head =
        sql.fetchOne(
            "select t.*, c.category_code, c.category_name,"
                + " (select count(*) from return_quality_template_item i"
                + "   where i.tenant_id = t.tenant_id and i.template_id = t.id) as item_count,"
                + " (select count(*) from return_quality_template_item i"
                + "   where i.tenant_id = t.tenant_id and i.template_id = t.id"
                + "     and i.item_required) as mandatory_count"
                + " from return_quality_template t"
                + " join return_quality_category c on c.tenant_id = t.tenant_id and c.id = t.category_id"
                + " where t.tenant_id = ?::uuid and t.id = ?::uuid",
            currentTenant(),
            id);
    if (head == null) {
      throw new IllegalArgumentException("质检模板不存在或不属于当前租户：" + id);
    }
    List<QualityTemplateItemRow> items = templateItems(id);
    return new QualityTemplateDetail(
        templateRow(head),
        items,
        "模板决定判定口径：APPEARANCE / PACKAGING 类检项失败**不禁止**判可再销售，"
            + "FUNCTION / ACCESSORY 类失败则**禁止**（ACCESSORY 失败进一步只能判残次）。"
            + "清单页把这一句写在页面上，质检员就不必凭记忆去猜哪个检项能压掉可售资格。",
        List.of(
            verdictOption(QualityVerdict.RESALABLE, "仅当外观 / 包装类检项失败时可判；配件缺失或功能失效时被规则拒绝"),
            verdictOption(QualityVerdict.DEFECTIVE, "任何情况下都允许 —— 它是「有问题但还能当残次处理」这一档"),
            verdictOption(QualityVerdict.SCRAPPED, "配件缺失时被规则拒绝；商品损坏或功能失效时允许（报废不可逆）")),
        capabilities().relatedModules(),
        OffsetDateTime.now(ZoneOffset.UTC),
        "⚠️ 模板 ACTIVE 之后**不许就地改检项**：改版会改变判定口径，而历史质检单要能复现当时的口径。"
            + "要改请新建一个版本号（旧模板可 RETIRED，但历史质检单仍指向它）。");
  }

  /** 建模板草稿（带检项）。 */
  public QualityTemplateDetail createTemplate(QualityTemplateCreate request, UUID userId) {
    if (request.categoryId() == null) {
      throw new IllegalArgumentException("必须给出 category_id：蓝图输入句「质检模板（按类目）」");
    }
    Record category = requireCategory(request.categoryId());
    if (!QualityCategoryStatus.valueOf(category.get("category_status", String.class)).active()) {
      throw new IllegalArgumentException("不能在已停用的质检类目下建模板");
    }
    if (request.templateVersion() <= 0) {
      throw new IllegalArgumentException("template_version 必须大于 0（它是复现历史口径的锚点）");
    }
    List<QualityTemplateItemDraft> items = request.items();
    if (items == null || items.isEmpty()) {
      throw new IllegalArgumentException("质检模板必须至少有一个检项：没有检项的模板不是质检模板");
    }
    long next = sql.fetchOne("select nextval('return_quality_template_no_seq')").get(0, Long.class);

    UUID id = UUID.randomUUID();
    // ⚠️ 系数先校验再落库：五个系数互相之间也有大小关系，
    //   让 DB 的 CHECK 去报错的话，运营看到的是「violates check constraint」而不是「报废上限高于残次上限」。
    BigDecimal base = coefficient(request.baseCoefficient(), "基准系数");
    BigDecimal min = coefficient(request.minCoefficient(), "系数下限");
    BigDecimal resale = coefficient(request.resaleCap(), "可再销售档上限");
    BigDecimal defective = coefficient(request.defectiveCap(), "残次档上限");
    BigDecimal scrap = coefficient(request.scrapCap(), "报废档上限");
    if (min.compareTo(base) > 0) {
      throw new IllegalArgumentException("系数下限不能高于基准系数");
    }
    if (scrap.compareTo(defective) > 0 || defective.compareTo(resale) > 0) {
      throw new IllegalArgumentException(
          "三档上限必须满足 报废上限 ≤ 残次上限 ≤ 可再销售上限：否则判成更严重的档位反而残值更高");
    }

    sql.execute(
        "insert into return_quality_template(id,tenant_id,template_no,template_name,category_id,"
            + "template_version,template_status,base_coefficient,min_coefficient,resale_cap,"
            + "defective_cap,scrap_cap,note,created_by)"
            + " values(?::uuid,?::uuid,?,?,?::uuid,?,'DRAFT',?,?,?,?,?,?,?::uuid)",
        id,
        currentTenant(),
        "RQT" + next,
        requireName(request.templateName()),
        request.categoryId(),
        request.templateVersion(),
        base,
        min,
        resale,
        defective,
        scrap,
        trimOrEmpty(request.note()),
        userId);
    insertItems(id, items);
    return templateDetail(id);
  }

  /** 改模板草稿（整单替换检项）。只有 DRAFT 可改。 */
  public QualityTemplateDetail updateTemplate(UUID id, QualityTemplateUpdate request) {
    Record head = requireTemplate(id);
    QualityTemplateStatus status =
        QualityTemplateStatus.valueOf(head.get("template_status", String.class));
    if (!status.editable()) {
      throw new IllegalArgumentException(
          "质检模板已"
              + status.label()
              + "，检项不能再改：改版会改变判定口径，而历史质检单要能复现当时的口径。"
              + "请新建一个版本号（旧模板可置为已停用）。");
    }
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "质检模板");

    BigDecimal base = coefficient(request.baseCoefficient(), "基准系数");
    BigDecimal min = coefficient(request.minCoefficient(), "系数下限");
    BigDecimal resale = coefficient(request.resaleCap(), "可再销售档上限");
    BigDecimal defective = coefficient(request.defectiveCap(), "残次档上限");
    BigDecimal scrap = coefficient(request.scrapCap(), "报废档上限");
    if (min.compareTo(base) > 0) {
      throw new IllegalArgumentException("系数下限不能高于基准系数");
    }
    if (scrap.compareTo(defective) > 0 || defective.compareTo(resale) > 0) {
      throw new IllegalArgumentException("三档上限必须满足 报废上限 ≤ 残次上限 ≤ 可再销售上限");
    }

    sql.execute(
        "update return_quality_template set template_name = ?, base_coefficient = ?,"
            + " min_coefficient = ?, resale_cap = ?, defective_cap = ?, scrap_cap = ?, note = ?,"
            + " version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        requireName(request.templateName()),
        base,
        min,
        resale,
        defective,
        scrap,
        trimOrEmpty(request.note()),
        currentTenant(),
        id);

    // 整单替换：草稿期「删掉写错的那一条检项」在界面上必须有地方安放。
    if (request.items() != null && !request.items().isEmpty()) {
      sql.execute(
          "delete from return_quality_template_item where tenant_id = ?::uuid and template_id = ?::uuid",
          currentTenant(),
          id);
      insertItems(id, request.items());
    }
    return templateDetail(id);
  }

  /** 启用模板（此后检项不可改，且可被质检单引用）。 */
  public QualityTemplateDetail activateTemplate(UUID id, UUID userId) {
    Record head = requireTemplate(id);
    QualityTemplateStatus status =
        QualityTemplateStatus.valueOf(head.get("template_status", String.class));
    if (status == QualityTemplateStatus.ACTIVE) {
      throw new IllegalArgumentException("质检模板已启用：" + id);
    }
    if (status == QualityTemplateStatus.RETIRED) {
      throw new IllegalArgumentException("已停用的模板不能重新启用，请新建一个版本（历史质检单要能复现当时的口径）");
    }
    long itemCount =
        sql.fetchOne(
                "select count(*) from return_quality_template_item where tenant_id = ?::uuid and template_id = ?::uuid",
                currentTenant(),
                id)
            .get(0, Long.class);
    if (itemCount == 0) {
      throw new IllegalArgumentException("质检模板没有任何检项，不能启用");
    }
    sql.execute(
        "update return_quality_template set template_status = 'ACTIVE', activated_by = ?::uuid,"
            + " activated_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        userId, OffsetDateTime.now(ZoneOffset.UTC), currentTenant(), id);
    return templateDetail(id);
  }

  /** 停用模板（不再被新质检单选中；历史质检单仍指向它）。 */
  public QualityTemplateDetail retireTemplate(UUID id) {
    requireTemplate(id);
    sql.execute(
        "update return_quality_template set template_status = 'RETIRED', version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        id);
    return templateDetail(id);
  }

  // ================================================================ 待检队列

  /**
   * 待检队列：已入库分录里<b>还没有</b>在途质检单的那些。
   *
   * <p>质检员上班第一屏要看的不是「一共入了多少」，而是
   * <b>「现在有多少件货等着我判」</b>。判定完成的那条分录自然不再命中 —— 队列自己往前推。
   */
  public QualityPendingPage pendingQueue(String skuCode, Integer page, Integer size) {
    int p = page == null || page < 0 ? 0 : page;
    int s = size == null || size <= 0 ? 20 : Math.min(size, MAX_PAGE_SIZE);

    // 已判定 / 已撤销的质检单不再占用队列（部分条件，与「一张分录同时只有一张在途质检单」同一口径）。
    // ⚠️ 队列行上的类目与模板**来自 SKU→类目映射**，不是来自「已经开过的那张质检单」：
    //   队列的第一屏恰恰是**还没开质检单**的那些货，等它们开完单才显示模板，这张表就白做了 ——
    //   质检员看到的必须是「按这个模板判」而不是「按那张单判」。
    String from =
        " from return_inventory_posting g"
            + " join return_receipt r on r.tenant_id = g.tenant_id and r.id = g.receipt_id"
            + " left join lateral ("
            + "   select i.id from return_quality_inspection i"
            + "    where i.tenant_id = g.tenant_id and i.posting_id = g.id"
            + "      and i.inspection_status <> 'CANCELLED'"
            + "    order by i.created_at desc limit 1) q on true"
            + " left join return_quality_category_sku m"
            + "   on m.tenant_id = g.tenant_id and m.sku_id = g.sku_id"
            + " left join return_quality_category c"
            + "   on c.tenant_id = m.tenant_id and m.category_id = c.id"
            + " left join lateral ("
            + "   select t.id, t.template_name from return_quality_template t"
            + "    where t.tenant_id = g.tenant_id and t.category_id = m.category_id"
            + "      and t.template_status = 'ACTIVE'"
            + "    order by t.template_version desc limit 1) tpl on true";

    List<Object> binds = new ArrayList<>();
    List<String> where = new ArrayList<>();
    where.add("g.tenant_id = ?::uuid");
    binds.add(currentTenant());
    where.add("g.inventory_state = 'PENDING_INSPECTION'");
    where.add("q.id is null");
    if (isSet(skuCode)) {
      where.add("g.sku_code ilike ?");
      binds.add("%" + skuCode.trim() + "%");
    }
    String clause = " where " + String.join(" and ", where);

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<QualityPendingRow> rows =
        sql.fetch(
                "select g.*, r.receipt_no, m.category_id, c.category_code,"
                    + " tpl.id as template_id, tpl.template_name"
                    + from
                    + clause
                    + " order by g.posted_at, g.id"
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(
                r -> {
                  ReturnReceiptModels.ReturnPackagingCondition pkg =
                      ReturnReceiptModels.ReturnPackagingCondition.valueOf(
                          r.get("packaging_condition", String.class));
                  ReturnReceiptModels.ReturnGoodsCondition goods =
                      ReturnReceiptModels.ReturnGoodsCondition.valueOf(
                          r.get("goods_condition", String.class));
                  return new QualityPendingRow(
                      r.get("id", UUID.class),
                      r.get("receipt_id", UUID.class),
                      r.get("receipt_no", String.class),
                      r.get("receipt_line_id", UUID.class),
                      r.get("sku_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("batch_no", String.class),
                      r.get("pending_inspection_qty", BigDecimal.class),
                      pkg.name(),
                      pkg.label(),
                      goods.name(),
                      goods.label(),
                      r.get("category_id", UUID.class),
                      r.get("category_code", String.class),
                      r.get("template_id", UUID.class),
                      r.get("template_name", String.class),
                      r.get("template_id", UUID.class) != null,
                      r.get("posted_at", OffsetDateTime.class));
                });

    return new QualityPendingPage(
        rows,
        p,
        s,
        total,
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），按入库时刻从早到晚排检。",
        "每一行都带**入库时记的两个判定维度**与它所属的质检类目 / 模板。"
            + "hasTemplate=false 表示该 SKU 还没配模板 —— 开质检单会直接报错，"
            + "去哪儿配（类目与 SKU 映射）就写在错误消息里，而不是悄悄套一个默认模板。");
  }

  // ================================================================ 判定预览

  /**
   * 判定预览：提交之前就告诉质检员「这批货**能**判成什么、**不能**判成什么、残值大概多少」。
   *
   * <p>⚠️ 残值是<b>试算</b>（按当前已录入的逐项结果），最终值以判定那一刻 DB 触发器的重算为准。
   */
  public QualityVerdictPreview verdictPreview(UUID postingId) {
    Record posting = requirePosting(postingId);
    UUID inspectionId = draftInspectionOf(postingId);
    return preview(posting, inspectionId);
  }

  private QualityVerdictPreview preview(Record posting, UUID inspectionId) {
    UUID postingId = posting.get("id", UUID.class);
    String packaging = posting.get("packaging_condition", String.class);
    String goods = posting.get("goods_condition", String.class);

    List<String> failedDimensions =
        inspectionId == null
            ? List.of()
            : sql.fetch(
                    "select distinct cr.item_dimension from return_quality_check_result cr"
                        + " where cr.tenant_id = ?::uuid and cr.inspection_id = ?::uuid"
                        + "   and cr.check_outcome = 'FAIL'",
                    currentTenant(), inspectionId)
                .map(r -> r.get("item_dimension", String.class));

    QualityFailureProfile profile = new QualityFailureProfile(goods, packaging, failedDimensions);
    QualityVerdictPath path = profile.requiredPath();

    // 模板参数：草稿质检单用它，没有草稿就按分录的 SKU 类目取启用中的模板。
    Record template =
        inspectionId != null
            ? templateOfInspection(inspectionId)
            : activeTemplateOfSku(posting.get("sku_id", UUID.class));

    BigDecimal original = originalValue(posting.get("receipt_line_id", UUID.class));
    String currency = currencyOf(posting.get("receipt_line_id", UUID.class));
    Salvage salvage =
        template == null
            ? Salvage.unavailable(original, currency)
            : salvage(inspectionId, original, template, QualityVerdict.RESALABLE);

    ReturnReceiptModels.ReturnPackagingCondition pkg =
        ReturnReceiptModels.ReturnPackagingCondition.valueOf(packaging);
    ReturnReceiptModels.ReturnGoodsCondition g = ReturnReceiptModels.ReturnGoodsCondition.valueOf(goods);

    return new QualityVerdictPreview(
        postingId,
        posting.get("receipt_line_id", UUID.class),
        posting.get("sku_code", String.class),
        posting.get("sku_name", String.class),
        posting.get("pending_inspection_qty", BigDecimal.class),
        pkg.name(),
        pkg.label(),
        g.name(),
        g.label(),
        failedDimensions,
        path,
        path.label(),
        verdictOptions(profile),
        original.setScale(MONEY_SCALE, RoundingMode.HALF_UP),
        currency,
        salvage.deduction(),
        salvage.coefficient(),
        salvage.cap(),
        salvage.amount(),
        OffsetDateTime.now(ZoneOffset.UTC),
        "⚠️ 残值是**试算**（按当前已录入的逐项结果与「可再销售」档上限），"
            + "最终值以判定那一刻 DB 触发器按同一个式子重算为准 —— 不一致会当场拒绝入库。"
            + "换句话说这里的数是给人看的，落库的那个才是账。");
  }

  // ================================================================ 质检单：列表 / 详情

  /** 质检单列表：真分页 + 多维筛选 + 排序白名单 + 跨全表汇总。 */
  public QualityInspectionPage listInspections(QualityInspectionListQuery query) {
    int p = query.page() == null || query.page() < 0 ? 0 : query.page();
    int s = query.size() == null || query.size() <= 0 ? 20 : Math.min(query.size(), MAX_PAGE_SIZE);
    QualityInspectionSort sort = QualityInspectionSort.parse(query.sort());

    List<Object> binds = new ArrayList<>();
    List<String> where = conditions(query, binds);
    String clause = " where " + String.join(" and ", where);

    String from =
        " from return_quality_inspection i"
            + " join return_quality_template t on t.tenant_id = i.tenant_id and t.id = i.template_id"
            + " join return_quality_category c on c.tenant_id = i.tenant_id and c.id = i.category_id"
            + " left join return_receipt r on r.tenant_id = i.tenant_id and r.id = i.receipt_id"
            + " left join lateral ("
            + "   select d.instruction_type, d.instruction_status from return_quality_disposition_instruction d"
            + "    where d.tenant_id = i.tenant_id and d.inspection_id = i.id) d on true"
            + " left join lateral ("
            + "   select count(*) as result_count from return_quality_check_result cr"
            + "    where cr.tenant_id = i.tenant_id and cr.inspection_id = i.id) rc on true";

    long total = sql.fetchOne("select count(*)" + from + clause, binds.toArray()).get(0, Long.class);

    List<Object> pageBinds = new ArrayList<>(binds);
    pageBinds.add(s);
    pageBinds.add((long) p * s);
    List<QualityInspectionRow> rows =
        sql.fetch(
                "select i.*, t.template_name, c.category_code, c.category_name, r.receipt_no,"
                    + " d.instruction_type, coalesce(d.instruction_status, '') as instruction_status,"
                    + " coalesce(rc.result_count, 0) as result_count"
                    + from
                    + clause
                    + " order by "
                    + sort.sql()
                    + " limit ? offset ?",
                pageBinds.toArray())
            .map(
                r -> {
                  String verdict = r.get("verdict", String.class);
                  QualityInspectionStatus status =
                      QualityInspectionStatus.valueOf(r.get("inspection_status", String.class));
                  return new QualityInspectionRow(
                      r.get("id", UUID.class),
                      r.get("inspection_no", String.class),
                      r.get("posting_id", UUID.class),
                      r.get("receipt_id", UUID.class),
                      r.get("receipt_no", String.class),
                      r.get("receipt_line_id", UUID.class),
                      r.get("template_id", UUID.class),
                      r.get("template_name", String.class),
                      r.get("template_version", Integer.class),
                      r.get("category_id", UUID.class),
                      r.get("category_code", String.class),
                      r.get("category_name", String.class),
                      r.get("sku_id", UUID.class),
                      r.get("sku_code", String.class),
                      r.get("sku_name", String.class),
                      r.get("batch_no", String.class),
                      r.get("inspected_qty", BigDecimal.class),
                      status.name(),
                      status.label(),
                      verdict,
                      verdict == null ? null : QualityVerdict.valueOf(verdict).label(),
                      r.get("original_value", BigDecimal.class),
                      r.get("salvage_amount", BigDecimal.class),
                      r.get("salvage_currency", String.class),
                      r.get("instruction_type", String.class),
                      r.get("instruction_status", String.class),
                      r.get("result_count", Integer.class),
                      r.get("version", Long.class));
                });

    return new QualityInspectionPage(
        rows,
        p,
        s,
        total,
        summary(clause, binds),
        OffsetDateTime.now(ZoneOffset.UTC),
        "真分页（LIMIT/OFFSET + 独立 COUNT），汇总与排序不受分页影响。",
        "每一行都带 originalValue / salvageAmount / salvageCurrency —— 残值是**算出来的**："
            + "round(原值 × min(成色系数, 档位上限), 4)，原值 = 订单行单价 × 实收数量。"
            + "NUMERIC(19,4) + BigDecimal，**禁浮点**；传输层没有残值字段，DB 触发器重算比对。",
        "verdict 是**三值枚举**不是布尔开关。verdict 恒为空 ⟺ 还没判定；"
            + "「入库维度 goods_condition=MISSING_PARTS 或配件类检项失败」的行**只能**判 DEFECTIVE —— "
            + "而「外包装破损但商品完好」的行判 RESALABLE 是允许的（蓝图加粗句的两半，两条不同的路径）。",
        "排序白名单四个键（created_desc / concluded_asc / verdict_asc / salvage_desc）；"
            + "其余取值落回 created_desc，不接受任意表达式。");
  }

  private List<String> conditions(QualityInspectionListQuery query, List<Object> binds) {
    List<String> where = new ArrayList<>();
    where.add("i.tenant_id = ?::uuid");
    binds.add(currentTenant());
    // ⚠️ 筛选值**先过枚举**再进 SQL：直接绑一个拼错的判定值，表里恰好没有匹配的行时
    //   表级 CHECK 根本不会被求值（CHECK 只在 INSERT/UPDATE 时检查），结果是静默返回零行。
    if (isSet(query.status())) {
      where.add("i.inspection_status = ?");
      binds.add(parseEnum(QualityInspectionStatus.class, query.status(), "质检状态").name());
    }
    if (isSet(query.verdict())) {
      where.add("i.verdict = ?");
      binds.add(parseEnum(QualityVerdict.class, query.verdict(), "判定结论").name());
    }
    if (query.categoryId() != null) {
      where.add("i.category_id = ?::uuid");
      binds.add(query.categoryId());
    }
    if (query.templateId() != null) {
      where.add("i.template_id = ?::uuid");
      binds.add(query.templateId());
    }
    if (query.receiptId() != null) {
      where.add("i.receipt_id = ?::uuid");
      binds.add(query.receiptId());
    }
    if (isSet(query.skuCode())) {
      where.add("i.sku_code ilike ?");
      binds.add("%" + query.skuCode().trim() + "%");
    }
    if (isSet(query.keyword())) {
      where.add(
          "(i.inspection_no ilike ? or i.sku_code ilike ? or i.sku_name ilike ?"
              + " or i.batch_no ilike ? or r.receipt_no ilike ?)");
      String like = "%" + query.keyword().trim() + "%";
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
      binds.add(like);
    }
    return where;
  }

  /** 单张质检单全景。**不存在的 id 与不属于本租户的 id 返回同一条错误**。 */
  public QualityInspectionDetail inspectionDetail(UUID id) {
    Record head =
        sql.fetchOne(
            "select i.*, t.template_name, c.category_code, c.category_name, r.receipt_no,"
                + " d.instruction_type, coalesce(d.instruction_status, '') as instruction_status,"
                + " (select count(*) from return_quality_check_result cr"
                + "   where cr.tenant_id = i.tenant_id and cr.inspection_id = i.id) as result_count"
                + " from return_quality_inspection i"
                + " join return_quality_template t on t.tenant_id = i.tenant_id and t.id = i.template_id"
                + " join return_quality_category c on c.tenant_id = i.tenant_id and c.id = i.category_id"
                + " left join return_receipt r on r.tenant_id = i.tenant_id and r.id = i.receipt_id"
                + " left join return_quality_disposition_instruction d"
                + "   on d.tenant_id = i.tenant_id and d.inspection_id = i.id"
                + " where i.tenant_id = ?::uuid and i.id = ?::uuid",
            currentTenant(),
            id);
    if (head == null) {
      throw new IllegalArgumentException("质检单不存在或不属于当前租户：" + id);
    }

    List<QualityCheckResultRow> results =
        sql.fetch(
                "select cr.*,"
                    + " (cr.item_dimension in ('FUNCTION','ACCESSORY')) as blocks_resale_when_failed"
                    + " from return_quality_check_result cr"
                    + " left join return_quality_template_item ti"
                    + "   on ti.tenant_id = cr.tenant_id and ti.id = cr.template_item_id"
                    + " where cr.tenant_id = ?::uuid and cr.inspection_id = ?::uuid"
                    // 按模板里的**检项顺序**渲染：质检员录的顺序与模板顺序一致时，
                    // 结果页才能和纸面模板对得上（否则「第三项到底是哪个」要靠猜）。
                    + " order by ti.item_no nulls last, cr.item_code, cr.id",
                currentTenant(),
                id)
            .map(this::checkResult);

    List<ReturnQualityEventRow> events =
        sql.fetch(
                "select * from return_quality_event"
                    + " where tenant_id = ?::uuid and inspection_id = ?::uuid"
                    + " order by occurred_at, id",
                currentTenant(),
                id)
            .map(
                e ->
                    new ReturnQualityEventRow(
                        e.get("id", UUID.class),
                        e.get("event_type", String.class),
                        eventLabel(e.get("event_type", String.class)),
                        e.get("from_status", String.class),
                        e.get("to_status", String.class),
                        e.get("verdict", String.class),
                        e.get("salvage_amount", BigDecimal.class),
                        e.get("reason_note", String.class),
                        e.get("occurred_by", UUID.class),
                        e.get("occurred_at", OffsetDateTime.class)));

    Record instruction =
        sql.fetchOne(
            "select * from return_quality_disposition_instruction"
                + " where tenant_id = ?::uuid and inspection_id = ?::uuid",
            currentTenant(),
            id);

    UUID templateId = head.get("template_id", UUID.class);
    List<QualityTemplateItemRow> pendingItems = templateItems(templateId);
    int mandatoryMissing =
        (int)
            pendingItems.stream()
                .filter(QualityTemplateItemRow::itemRequired)
                .filter(item -> results.stream().noneMatch(r -> r.templateItemId().equals(item.id())))
                .count();

    List<String> failedDimensions =
        results.stream()
            .filter(r -> QualityCheckOutcome.valueOf(r.checkOutcome()).failed())
            .map(QualityCheckResultRow::itemDimension)
            .distinct()
            .toList();
    // 判定路径的两个输入都取**入库时记的维度**（V172 的真值），不是本次自己填的 ——
    // 「当时收件时看到的是什么」才是判定的事实基础。
    UUID receiptLineId = head.get("receipt_line_id", UUID.class);
    QualityFailureProfile profile =
        new QualityFailureProfile(
            goodsConditionOf(receiptLineId), packagingConditionOf(receiptLineId), failedDimensions);
    QualityVerdictPath path = profile.requiredPath();

    QualityInspectionStatus status =
        QualityInspectionStatus.valueOf(head.get("inspection_status", String.class));

    return new QualityInspectionDetail(
        row(head),
        results,
        instruction == null ? null : instruction(instruction),
        events,
        pendingItems,
        mandatoryMissing,
        verdictOptions(profile),
        path,
        path.label(),
        actions(head, status, mandatoryMissing, path),
        OffsetDateTime.now(ZoneOffset.UTC),
        status.draft()
            ? "⚠️ 判定之前：verdict 与残值恒为空（残值是判定的产物）。"
                + "当前允许的结论由「入库时记的两个维度」与「已录入的失败检项」共同决定 —— "
                + "requiredPath 就是那条路径的名字，它不是建议而是规则。"
            : "已判定：**整行终态**，结论、SKU 快照、模板口径一起冻结（判错了只能作废重判）。"
                + "质量状态指令恒为 PENDING，消费方恒为 11.2.3.2 —— 处置端点属 11.2.3.2 未交付，"
                + "本模块**刻意没有**「转可售」入口，运行角色对该表的 UPDATE/DELETE 也已收回。");
  }

  // ================================================================ 质检单：写

  /** 开一张质检单（对一条待检入库分录）。标的物与数量由 DB 触发器取，不接受传入。 */
  public QualityInspectionDetail createInspection(QualityInspectionCreate request, UUID userId) {
    if (request.postingId() == null) {
      throw new IllegalArgumentException("必须给出 posting_id（要检的是哪一条待检入库分录）");
    }
    Record posting = requirePosting(request.postingId());

    // 类目：SKU → 类目映射（租户维护的真数据）。拿不到就报错，不退化成「默认模板」。
    UUID skuId = posting.get("sku_id", UUID.class);
    Record mapping = null;
    if (skuId != null) {
      mapping =
          sql.fetchOne(
              "select m.category_id, c.category_status from return_quality_category_sku m"
                  + " join return_quality_category c on c.tenant_id = m.tenant_id and c.id = m.category_id"
                  + " where m.tenant_id = ?::uuid and m.sku_id = ?::uuid",
              currentTenant(),
              skuId);
    }
    if (mapping == null) {
      throw new IllegalArgumentException(
          "SKU "
              + (skuId == null ? "<空>" : skuId)
              + " 还没有归到任何质检类目，无法按类目取用质检模板。"
              + "请先在「类目与 SKU 映射」里维护：类目真值在 catalog 服务（独立数据库），"
              + "本服务按模块边界纪律不跨库读，质检类目是租户自维护的作业口径。");
    }
    if (!QualityCategoryStatus.valueOf(mapping.get("category_status", String.class)).active()) {
      throw new IllegalArgumentException(
          "该 SKU 所属的质检类目已停用（停用是口径变更，不再接受新质检单）："
              + mapping.get("category_id", UUID.class));
    }
    UUID categoryId = mapping.get("category_id", UUID.class);

    // 模板：显式指定时必须属于该类目；不给时取该类目唯一启用中的模板。
    Record template;
    if (request.templateId() != null) {
      template = requireTemplate(request.templateId());
      if (!template.get("category_id", UUID.class).equals(categoryId)) {
        throw new IllegalArgumentException(
            "指定的质检模板不属于该 SKU 所属的质检类目：模板类目 "
                + template.get("category_id", UUID.class)
                + "，SKU 类目 " + categoryId);
      }
      if (!QualityTemplateStatus.valueOf(template.get("template_status", String.class))
          .equals(QualityTemplateStatus.ACTIVE)) {
        throw new IllegalArgumentException("只能使用已启用的质检模板（草稿模板改了口径，历史质检单无法复现）");
      }
    } else {
      template = activeTemplateOfCategory(categoryId);
    }

    long existing =
        sql.fetchOne(
                "select count(*) from return_quality_inspection where tenant_id = ?::uuid"
                    + " and posting_id = ?::uuid and inspection_status <> 'CANCELLED'",
                currentTenant(),
                request.postingId())
            .get(0, Long.class);
    if (existing > 0) {
      throw new IllegalArgumentException("该待检分录已有一张在途质检单（一张分录同时只能有一张在途质检单）");
    }

    long next = sql.fetchOne("select nextval('return_quality_inspection_no_seq')").get(0, Long.class);
    UUID id = UUID.randomUUID();
    sql.execute(
        "insert into return_quality_inspection(id,tenant_id,inspection_no,posting_id,template_id,"
            + "template_version,category_id,inspection_status,created_by)"
            + " values(?::uuid,?::uuid,?,?::uuid,?::uuid,?,?::uuid,'DRAFT',?::uuid)",
        id,
        currentTenant(),
        "RQI" + next,
        request.postingId(),
        template.get("id", UUID.class),
        template.get("template_version", Integer.class),
        categoryId,
        userId);
    return inspectionDetail(id);
  }

  /** 录逐项检查结果（整单替换）。只有 DRAFT 可录。 */
  public QualityInspectionDetail recordResults(UUID id, QualityInspectionUpdate request, UUID userId) {
    Record head = requireInspection(id);
    requireDraft(head);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "质检单");

    UUID templateId = head.get("template_id", UUID.class);
    // 逐条校验收到的检项确实属于本单模板：不属于的直接拒，而不是静默丢弃
    // （静默丢弃会让质检员以为「已经录了」，实际判定时被触发器拦下，错误消息还指向别处）。
    for (QualityCheckResultDraft result : safeResults(request.results())) {
      UUID itemId = result.templateItemId();
      if (itemId == null) {
        throw new IllegalArgumentException("每条检查结果都必须给出 template_item_id");
      }
      boolean belongs =
          sql.fetchOne(
                  "select count(*) from return_quality_template_item"
                      + " where tenant_id = ?::uuid and id = ?::uuid and template_id = ?::uuid",
                  currentTenant(), itemId, templateId)
              .get(0, Long.class)
              > 0;
      if (!belongs) {
        throw new IllegalArgumentException(
            "检项不属于本质检单使用的模板（template_id=" + templateId + "）：" + itemId);
      }
      QualityCheckOutcome outcome =
          parseEnum(QualityCheckOutcome.class, result.checkOutcome(), "检查结果");
      if (outcome.failed() && !isSet(result.checkNote())) {
        throw new IllegalArgumentException(
            "检查项不合格必须写说明：判残次要拿去索赔，没有理由的 FAIL 在纠纷里等于没有 FAIL");
      }
    }

    sql.execute(
        "delete from return_quality_check_result where tenant_id = ?::uuid and inspection_id = ?::uuid",
        currentTenant(),
        id);
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (QualityCheckResultDraft result : safeResults(request.results())) {
      UUID itemId = result.templateItemId();
      Record item =
          sql.fetchOne(
              "select item_code, item_name, item_dimension from return_quality_template_item"
                  + " where tenant_id = ?::uuid and id = ?::uuid",
              currentTenant(), itemId);
      // ⚠️ 维度取**模板定义**而不是请求体：请求体里那个字段只是给人看的回显，
      //   让它可写就等于允许调用方把「功能」说成「外观」，从而绕过路径②。
      QualityDimension dimension = QualityDimension.valueOf(item.get("item_dimension", String.class));
      QualityCheckOutcome outcome =
          parseEnum(QualityCheckOutcome.class, result.checkOutcome(), "检查结果");
      sql.execute(
          "insert into return_quality_check_result(id,tenant_id,inspection_id,template_item_id,"
              + "item_code,item_name,item_dimension,check_outcome,check_note,photo_id,checked_by,checked_at)"
              + " values(?::uuid,?::uuid,?::uuid,?::uuid,?,?,?,?,?,?::uuid,?::uuid,?::timestamptz)",
          UUID.randomUUID(),
          currentTenant(),
          id,
          itemId,
          item.get("item_code", String.class),
          item.get("item_name", String.class),
          dimension.name(),
          outcome.name(),
          trimOrEmpty(result.checkNote()),
          result.photoId(),
          userId,
          now);
    }
    sql.execute(
        "update return_quality_inspection set version = version + 1 where tenant_id = ?::uuid and id = ?::uuid",
        currentTenant(),
        id);
    return inspectionDetail(id);
  }

  /**
   * 判定：给结论 + 理由，服务层算残值，<b>DB 触发器重算比对</b>并生成质量状态指令。
   *
   * <p>这是本模块<b>唯一</b>的判定入口，也是硬规则 1 与硬规则 2 的服务层落点。
   */
  public QualityInspectionDetail conclude(UUID id, QualityInspectionConclude request, UUID userId) {
    Record head = requireInspection(id);
    requireDraft(head);
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "质检单");
    QualityVerdict verdict = parseEnum(QualityVerdict.class, request.verdict(), "判定结论");
    if (!isSet(request.conclusionNote())) {
      throw new IllegalArgumentException(
          "判定必须写理由：判残次要拿去向供应商与平台索赔，判报废要经审批，"
              + "「点一下下拉框」出来的结论在纠纷里等于没有结论");
    }

    UUID templateId = head.get("template_id", UUID.class);
    UUID receiptLineId = head.get("receipt_line_id", UUID.class);

    // ---- 硬规则 1（服务层这一道）：先算失败画像，再问规则允不允许 ----
    List<String> failedDimensions =
        sql.fetch(
                "select distinct item_dimension from return_quality_check_result"
                    + " where tenant_id = ?::uuid and inspection_id = ?::uuid and check_outcome = 'FAIL'",
                currentTenant(),
                id)
            .map(r -> r.get("item_dimension", String.class));
    QualityFailureProfile profile =
        new QualityFailureProfile(
            goodsConditionOf(receiptLineId),
            packagingConditionOf(receiptLineId),
            failedDimensions);
    if (!verdict.allowed(profile)) {
      throw new IllegalArgumentException(verdictBlockedReason(verdict, profile));
    }

    // ---- 强制检项覆盖 ----
    long mandatory =
        sql.fetchOne(
                "select count(*) from return_quality_template_item"
                    + " where tenant_id = ?::uuid and template_id = ?::uuid and item_required",
                currentTenant(),
                templateId)
            .get(0, Long.class);
    long recorded =
        sql.fetchOne(
                "select count(*) from return_quality_check_result"
                    + " where tenant_id = ?::uuid and inspection_id = ?::uuid",
                currentTenant(),
                id)
            .get(0, Long.class);
    if (recorded < mandatory) {
      throw new IllegalArgumentException(
          "还有 " + (mandatory - recorded) + " 个强制检项没有结果，不能判定"
              + "（强制检项漏检是质检事故最常见的来源，库级用「结果行必须覆盖强制检项」在库层钉死）");
    }

    // ---- 硬规则 2（服务层这一道）：残值真算 ----
    Record template = requireTemplate(templateId);
    BigDecimal original = originalValue(receiptLineId);
    String currency = currencyOf(receiptLineId);
    Salvage computed = salvage(id, original, template, verdict);
    if (computed.amount().compareTo(BigDecimal.ZERO) < 0) {
      throw new IllegalArgumentException("残值不能为负");
    }

    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    // ⚠️ **只写 verdict / conclusion_note / salvage_amount / 人与时刻**；
    //   原值、扣减、系数、档位上限、币种、SKU 快照、数量全部由 V179 触发器填，
    //   指令也由触发器生成 —— 本服务**没有**写 return_quality_disposition_instruction 的代码路径。
    sql.execute(
        "update return_quality_inspection set inspection_status = 'CONCLUDED', verdict = ?,"
            + " verdict_label = ?, conclusion_note = ?, salvage_amount = ?,"
            + " concluded_by = ?::uuid, concluded_at = ?::timestamptz, version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid and inspection_status = 'DRAFT'",
        verdict.name(),
        verdict.label(),
        request.conclusionNote().trim(),
        computed.amount(),
        userId,
        now,
        currentTenant(),
        id);
    return inspectionDetail(id);
  }

  /** 撤销（录错了 / 判错了还没生效）。只有 DRAFT 可撤销 —— 已判定已是终态。 */
  public QualityInspectionDetail cancel(UUID id, QualityInspectionCancel request, UUID userId) {
    Record head = requireInspection(id);
    QualityInspectionStatus status =
        QualityInspectionStatus.valueOf(head.get("inspection_status", String.class));
    if (status == QualityInspectionStatus.CONCLUDED) {
      throw new IllegalArgumentException(
          "质检单已判定，是终态不可撤销：结论与质量状态指令已经生成，"
              + "「这批货到底判成什么」出现两个答案比判错更难收拾");
    }
    if (status == QualityInspectionStatus.CANCELLED) {
      throw new IllegalArgumentException("质检单已撤销：" + id);
    }
    requireVersion(head.get("version", Long.class), request.expectedVersion(), "质检单");
    if (!isSet(request.cancelReason())) {
      throw new IllegalArgumentException("撤销必须说明理由（录错了 / 判错了 / 买家又把货拿回去了）");
    }
    sql.execute(
        "update return_quality_inspection set inspection_status = 'CANCELLED',"
            + " cancelled_by = ?::uuid, cancelled_at = ?::timestamptz, cancel_reason = ?,"
            + " version = version + 1"
            + " where tenant_id = ?::uuid and id = ?::uuid and inspection_status = 'DRAFT'",
        userId,
        OffsetDateTime.now(ZoneOffset.UTC),
        request.cancelReason().trim(),
        currentTenant(),
        id);
    return inspectionDetail(id);
  }

  // ================================================================ 残值算法（硬规则 2）

  /**
   * 残值：<b>本模块唯一的算法实现</b>，判定与判定预览共用同一份代码。
   *
   * <p>式子（人可手算复核，与 V179 守卫⑤逐项对应）：
   *
   * <pre>
   *   原值 base      = 订单行单价 × 本行实收数量
   *   扣减 deduction = Σ(FAIL 检项的 salvage_rate)
   *   系数 coeff     = clamp(baseCoefficient − deduction, minCoefficient, 1)
   *   上限 cap       = 判定档位对应的三档上限
   *   残值 salvage   = round(base × min(coeff, cap), 4)
   * </pre>
   *
   * <p>⚠️ 全程 {@link BigDecimal}：{@code 0.1 + 0.2 ≠ 0.3} 这种事不能出现在残值里。
   * 系数一律 6 位、金额一律 4 位，与库里的 NUMERIC(9,6) / NUMERIC(19,4) 同刻度 ——
   * 两边算出不同的数，DB 触发器会在写入那一刻拒掉。
   */
  private Salvage salvage(
      UUID inspectionId, BigDecimal original, Record template, QualityVerdict verdict) {

    // 扣减按**检项**算而不是按维度算：同一维度里可能有三项检项，
    // 按维度合并会让「三项外观全挂」与「一项外观挂」扣一样的分。
    // ⚠️ 按检项取时每项取**模板定义**的 salvage_rate，而不是结果行上的任何回显值。
    BigDecimal deduction =
        sql.fetchOne(
                "select coalesce(sum(i.salvage_rate), 0) as d"
                    + " from return_quality_check_result cr"
                    + " join return_quality_template_item i"
                    + "   on i.tenant_id = cr.tenant_id and i.id = cr.template_item_id"
                    + " where cr.tenant_id = ?::uuid and cr.inspection_id = ?::uuid"
                    + "   and cr.check_outcome = 'FAIL'",
                currentTenant(),
                inspectionId)
            .get("d", BigDecimal.class);
    if (deduction == null) {
      deduction = BigDecimal.ZERO;
    }
    deduction = deduction.setScale(RATE_SCALE, RoundingMode.HALF_UP);

    BigDecimal base = template.get("base_coefficient", BigDecimal.class);
    BigDecimal min = template.get("min_coefficient", BigDecimal.class);
    BigDecimal coeff = base.subtract(deduction);
    if (coeff.compareTo(min) < 0) {
      coeff = min;
    }
    if (coeff.compareTo(ONE) > 0) {
      coeff = ONE;
    }
    coeff = coeff.setScale(RATE_SCALE, RoundingMode.HALF_UP);

    BigDecimal cap =
        switch (verdict) {
          case RESALABLE -> template.get("resale_cap", BigDecimal.class);
          case DEFECTIVE -> template.get("defective_cap", BigDecimal.class);
          case SCRAPPED -> template.get("scrap_cap", BigDecimal.class);
        };
    cap = cap.setScale(RATE_SCALE, RoundingMode.HALF_UP);

    BigDecimal effective = coeff.min(cap);
    // ⚠️ 这里**不能**先把原值四舍五入到 4 位再乘：库里触发器用的是未舍入的原值
    //   （单价 4 位 × 数量 6 位 = 10 位小数），两边差一点点就会在写入时被拒。
    BigDecimal amount = original.multiply(effective).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    BigDecimal roundedOriginal = original.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    if (amount.compareTo(roundedOriginal) > 0) {
      amount = roundedOriginal;
    }
    return new Salvage(deduction, coeff, cap, amount);
  }

  /** 预览用：没有模板时残值给不出（而不是给 0 —— 0 会被读成「这批货一文不值」）。 */
  private static final class Salvage {
    private final BigDecimal deduction;
    private final BigDecimal coefficient;
    private final BigDecimal cap;
    private final BigDecimal amount;

    private Salvage(BigDecimal deduction, BigDecimal coefficient, BigDecimal cap, BigDecimal amount) {
      this.deduction = deduction;
      this.coefficient = coefficient;
      this.cap = cap;
      this.amount = amount;
    }

    static Salvage unavailable(BigDecimal original, String currency) {
      return new Salvage(
          BigDecimal.ZERO.setScale(RATE_SCALE),
          BigDecimal.ZERO.setScale(RATE_SCALE),
          BigDecimal.ZERO.setScale(RATE_SCALE),
          null);
    }

    BigDecimal deduction() {
      return deduction;
    }

    BigDecimal coefficient() {
      return coefficient;
    }

    BigDecimal cap() {
      return cap;
    }

    BigDecimal amount() {
      return amount;
    }
  }

  // ================================================================ 工具

  private void insertItems(UUID templateId, List<QualityTemplateItemDraft> items) {
    int no = 0;
    for (QualityTemplateItemDraft item : items) {
      no++;
      if (!isSet(item.itemCode()) || !isSet(item.itemName())) {
        throw new IllegalArgumentException("每个检项都必须有编码与名称（编码是模板内唯一标识）");
      }
      QualityDimension dimension = parseEnum(QualityDimension.class, item.itemDimension(), "检项维度");
      BigDecimal rate =
          item.salvageRate() == null
              ? BigDecimal.ZERO.setScale(RATE_SCALE)
              : item.salvageRate().setScale(RATE_SCALE, RoundingMode.HALF_UP);
      if (rate.signum() < 0 || rate.compareTo(ONE) > 0) {
        throw new IllegalArgumentException("检项扣减系数必须在 0 与 1 之间：" + item.itemCode());
      }
      sql.execute(
          "insert into return_quality_template_item(id,tenant_id,template_id,item_code,item_name,"
              + "item_dimension,salvage_rate,item_required,expected_value,fail_hint,item_no)"
              + " values(?::uuid,?::uuid,?::uuid,?,?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          currentTenant(),
          templateId,
          item.itemCode().trim(),
          item.itemName().trim(),
          dimension.name(),
          rate,
          item.itemRequired(),
          trimOrEmpty(item.expectedValue()),
          trimOrEmpty(item.failHint()),
          no);
    }
  }

  private List<QualityTemplateItemRow> templateItems(UUID templateId) {
    return sql.fetch(
            "select * from return_quality_template_item"
                + " where tenant_id = ?::uuid and template_id = ?::uuid"
                + " order by item_no, id",
            currentTenant(),
            templateId)
        .map(
            i -> {
              QualityDimension d = QualityDimension.valueOf(i.get("item_dimension", String.class));
              return new QualityTemplateItemRow(
                  i.get("id", UUID.class),
                  i.get("item_code", String.class),
                  i.get("item_name", String.class),
                  d.name(),
                  d.label(),
                  d.blocksResale(),
                  i.get("salvage_rate", BigDecimal.class),
                  i.get("item_required", Boolean.class),
                  i.get("expected_value", String.class),
                  i.get("fail_hint", String.class),
                  i.get("item_no", Integer.class));
            });
  }

  private QualityCheckResultRow checkResult(Record r) {
    QualityDimension d = QualityDimension.valueOf(r.get("item_dimension", String.class));
    QualityCheckOutcome outcome = QualityCheckOutcome.valueOf(r.get("check_outcome", String.class));
    return new QualityCheckResultRow(
        r.get("id", UUID.class),
        r.get("template_item_id", UUID.class),
        r.get("item_code", String.class),
        r.get("item_name", String.class),
        d.name(),
        d.label(),
        r.get("blocks_resale_when_failed", Boolean.class),
        outcome.name(),
        outcome.label(),
        r.get("check_note", String.class),
        r.get("photo_id", UUID.class),
        r.get("checked_by", UUID.class),
        r.get("checked_at", OffsetDateTime.class));
  }

  private QualityDispositionInstruction instruction(Record r) {
    String type = r.get("instruction_type", String.class);
    return new QualityDispositionInstruction(
        r.get("id", UUID.class),
        r.get("inspection_id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_line_id", UUID.class),
        type,
        instructionLabel(type),
        r.get("target_inventory_state", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("qty", BigDecimal.class),
        r.get("salvage_amount", BigDecimal.class),
        r.get("salvage_currency", String.class),
        r.get("original_value", BigDecimal.class),
        r.get("grade_coefficient", BigDecimal.class),
        r.get("instruction_status", String.class),
        r.get("consumer_module", String.class),
        r.get("issued_at", OffsetDateTime.class),
        r.get("issued_by", UUID.class));
  }

  private static QualityInspectionRow row(Record r) {
    String verdict = r.get("verdict", String.class);
    QualityInspectionStatus status =
        QualityInspectionStatus.valueOf(r.get("inspection_status", String.class));
    return new QualityInspectionRow(
        r.get("id", UUID.class),
        r.get("inspection_no", String.class),
        r.get("posting_id", UUID.class),
        r.get("receipt_id", UUID.class),
        r.get("receipt_no", String.class),
        r.get("receipt_line_id", UUID.class),
        r.get("template_id", UUID.class),
        r.get("template_name", String.class),
        r.get("template_version", Integer.class),
        r.get("category_id", UUID.class),
        r.get("category_code", String.class),
        r.get("category_name", String.class),
        r.get("sku_id", UUID.class),
        r.get("sku_code", String.class),
        r.get("sku_name", String.class),
        r.get("batch_no", String.class),
        r.get("inspected_qty", BigDecimal.class),
        status.name(),
        status.label(),
        verdict,
        verdict == null ? null : QualityVerdict.valueOf(verdict).label(),
        r.get("original_value", BigDecimal.class),
        r.get("salvage_amount", BigDecimal.class),
        r.get("salvage_currency", String.class),
        r.get("instruction_type", String.class),
        r.get("instruction_status", String.class),
        r.get("result_count", Integer.class),
        r.get("version", Long.class));
  }

  private static QualityTemplateRow templateRow(Record r) {
    QualityTemplateStatus status =
        QualityTemplateStatus.valueOf(r.get("template_status", String.class));
    return new QualityTemplateRow(
        r.get("id", UUID.class),
        r.get("template_no", String.class),
        r.get("category_id", UUID.class),
        r.get("category_code", String.class),
        r.get("category_name", String.class),
        r.get("template_name", String.class),
        r.get("template_version", Integer.class),
        status.name(),
        status.label(),
        r.get("base_coefficient", BigDecimal.class),
        r.get("min_coefficient", BigDecimal.class),
        r.get("resale_cap", BigDecimal.class),
        r.get("defective_cap", BigDecimal.class),
        r.get("scrap_cap", BigDecimal.class),
        r.get("item_count", Integer.class),
        r.get("mandatory_count", Long.class),
        r.get("note", String.class),
        r.get("version", Long.class));
  }

  private QualityTemplateSummary templateSummary(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where t.template_status = 'DRAFT') as draft,"
                + " count(*) filter (where t.template_status = 'ACTIVE') as active,"
                + " count(*) filter (where t.template_status = 'RETIRED') as retired,"
                + " coalesce(sum((select count(*) from return_quality_template_item i"
                + "   where i.tenant_id = t.tenant_id and i.template_id = t.id)), 0) as item_count,"
                + " coalesce(sum((select count(*) from return_quality_template_item i"
                + "   where i.tenant_id = t.tenant_id and i.template_id = t.id and i.item_required)), 0)"
                + "   as mandatory_count"
                + " from return_quality_template t"
                + " join return_quality_category c on c.tenant_id = t.tenant_id and c.id = t.category_id"
                + clause,
            binds.toArray());
    return new QualityTemplateSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("active", Long.class),
        r.get("retired", Long.class),
        r.get("item_count", Long.class),
        r.get("mandatory_count", Long.class));
  }

  private QualityInspectionSummary summary(String clause, List<Object> binds) {
    Record r =
        sql.fetchOne(
            "select count(*) as total,"
                + " count(*) filter (where i.inspection_status = 'DRAFT') as draft,"
                + " count(*) filter (where i.inspection_status = 'CONCLUDED') as concluded,"
                + " count(*) filter (where i.inspection_status = 'CANCELLED') as cancelled,"
                + " count(*) filter (where i.verdict = 'RESALABLE') as resalable,"
                + " count(*) filter (where i.verdict = 'DEFECTIVE') as defective,"
                + " count(*) filter (where i.verdict = 'SCRAPPED') as scrapped,"
                + " coalesce(sum(i.original_value), 0) as original_value,"
                + " coalesce(sum(i.salvage_amount), 0) as salvage_amount"
                + " from return_quality_inspection i"
                + " join return_quality_template t on t.tenant_id = i.tenant_id and t.id = i.template_id"
                + " join return_quality_category c on c.tenant_id = i.tenant_id and c.id = i.category_id"
                + " left join return_receipt r on r.tenant_id = i.tenant_id and r.id = i.receipt_id"
                + " left join lateral ("
                + "   select d.instruction_type, d.instruction_status from return_quality_disposition_instruction d"
                + "    where d.tenant_id = i.tenant_id and d.inspection_id = i.id) d on true"
                + " left join lateral ("
                + "   select count(*) as result_count from return_quality_check_result cr"
                + "    where cr.tenant_id = i.tenant_id and cr.inspection_id = i.id) rc on true"
                + clause,
            binds.toArray());
    long awaiting =
        sql.fetchOne(
                "select count(*) from return_quality_disposition_instruction"
                    + " where tenant_id = ?::uuid and instruction_status = 'PENDING'",
                currentTenant())
            .get(0, Long.class);
    long pendingQueue =
        sql.fetchOne(
                "select count(*) from return_inventory_posting g"
                    + " where g.tenant_id = ?::uuid and g.inventory_state = 'PENDING_INSPECTION'"
                    + "   and not exists (select 1 from return_quality_inspection i"
                    + "                    where i.tenant_id = g.tenant_id and i.posting_id = g.id"
                    + "                      and i.inspection_status <> 'CANCELLED')",
                currentTenant())
            .get(0, Long.class);
    return new QualityInspectionSummary(
        r.get("total", Long.class),
        r.get("draft", Long.class),
        r.get("concluded", Long.class),
        r.get("cancelled", Long.class),
        r.get("resalable", Long.class),
        r.get("defective", Long.class),
        r.get("scrapped", Long.class),
        r.get("original_value", BigDecimal.class),
        r.get("salvage_amount", BigDecimal.class),
        awaiting,
        pendingQueue);
  }

  private List<QualityVerdictOption> verdictOptions(QualityFailureProfile profile) {
    return List.of(
        new QualityVerdictOption(
            QualityVerdict.RESALABLE.name(),
            QualityVerdict.RESALABLE.label(),
            QualityVerdict.RESALABLE.allowed(profile),
            QualityVerdict.RESALABLE.allowed(profile) ? null : verdictBlockedReason(QualityVerdict.RESALABLE, profile)),
        new QualityVerdictOption(
            QualityVerdict.DEFECTIVE.name(),
            QualityVerdict.DEFECTIVE.label(),
            QualityVerdict.DEFECTIVE.allowed(profile),
            null),
        new QualityVerdictOption(
            QualityVerdict.SCRAPPED.name(),
            QualityVerdict.SCRAPPED.label(),
            QualityVerdict.SCRAPPED.allowed(profile),
            QualityVerdict.SCRAPPED.allowed(profile) ? null : verdictBlockedReason(QualityVerdict.SCRAPPED, profile)));
  }

  /**
   * 「这个结论为什么不允许」的具体理由。
   *
   * <p>按钮变灰而不给理由，是最消耗信任的一种 UI：质检员会以为系统坏了，
   * 于是去找人手工绕过 —— 而绕过的正是这条硬规则要防的那件事。
   */
  private static String verdictBlockedReason(QualityVerdict verdict, QualityFailureProfile profile) {
    return switch (profile.requiredPath()) {
      case ACCESSORY_MISSING -> verdict == QualityVerdict.RESALABLE
          ? "配件缺失只能判残次（蓝图加粗句「缺少配件则应判残次」）：缺配件的机器当整机卖出去，"
              + "收货的人一定会退，赔的是我们的运费与口碑"
          : "配件缺失只能判残次：判报废等于把一台能作残次处理的货当废铁丢掉（报废不可逆）";
      case GOODS_OR_FUNCTION_DAMAGED -> verdict == QualityVerdict.RESALABLE
          ? "商品本体损坏或功能失效不能判可再销售：只能判残次或报废"
          : "商品本体损坏或功能失效时请判残次 —— 报废是不可逆的、需审批与原因（11.2.3.2）";
      case PACKAGING_OR_APPEARANCE_ONLY -> "当前不适用";
    };
  }

  private static QualityVerdictOption verdictOption(QualityVerdict verdict, String reason) {
    return new QualityVerdictOption(verdict.name(), verdict.label(), true, reason);
  }

  private List<QualityAction> actions(
      Record head, QualityInspectionStatus status, int mandatoryMissing, QualityVerdictPath path) {
    String path_ = "/trade/return-quality/inspections/" + head.get("id", UUID.class);
    return List.of(
        action("RECORD_RESULTS", "录逐项结果", status.draft(), "PUT", path_, false, "仅 DRAFT",
            status.draft() ? null : "质检单已" + status.label() + "，结论与证据是终态", null, null),
        action("CONCLUDE", "判定（结论 + 残值）",
            status.draft() && mandatoryMissing == 0, "POST", path_ + "/conclude", false,
            "仅 DRAFT，强制检项全部有结果，结论在允许集合内",
            concludeBlocked(status, mandatoryMissing, path), null, null),
        action("CANCEL", "撤销", status.draft(), "POST", path_ + "/cancel", false, "仅 DRAFT",
            status.draft() ? null : "质检单已" + status.label() + "，撤销入口只在草稿期开放", null, null),
        action("APPLY_DISPOSITION", "执行处置（转可售 / 残次 / 报废）", false, null, null, false,
            "仅已判定", null, "11.2.3.2",
            "**未交付**。质量状态指令只出不消费：`instruction_status` 恒 PENDING、消费方恒 11.2.3.2，"
                + "运行角色对该表的 UPDATE/DELETE 已收回 —— 本模块没有「转可售」入口是接口层面的决定，"
                + "不是没做按钮。"),
        action("POST_SALVAGE", "残值入账", false, null, null, false, "仅已判定", null, "12 财务",
            "**未交付**，且跨服务写账被模块边界纪律禁止（12 财务是独立数据库）。"),
        action("CLAIM_SUPPLIER", "发起供应商索赔", false, null, null, false, "仅已判定为残次", null,
            "11.2.3.3", "**未交付**。本模块的逐项结果、判定理由与照片引用是它的举证材料，不是它的结论。"));
  }

  private static String concludeBlocked(
      QualityInspectionStatus status, int mandatoryMissing, QualityVerdictPath path) {
    if (!status.draft()) {
      return "质检单已" + status.label() + "，结论与证据是终态（判错了只能作废重判）";
    }
    if (mandatoryMissing > 0) {
      return "还有 " + mandatoryMissing + " 个强制检项没有结果";
    }
    return "当前路径：" + path.label();
  }

  private Record requireCategory(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from return_quality_category where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new IllegalArgumentException("质检类目不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private QualityCategoryRow category(UUID id) {
    Record r =
        sql.fetchOne(
            "select c.*,"
                + " (select count(*) from return_quality_category_sku m"
                + "   where m.tenant_id = c.tenant_id and m.category_id = c.id) as sku_count,"
                + " (select count(*) from return_quality_template t"
                + "   where t.tenant_id = c.tenant_id and t.category_id = c.id) as template_count,"
                + " (select count(*) from return_quality_template t"
                + "   where t.tenant_id = c.tenant_id and t.category_id = c.id"
                + "     and t.template_status = 'ACTIVE') as active_template_count"
                + " from return_quality_category c where c.tenant_id = ?::uuid and c.id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new IllegalArgumentException("质检类目不存在或不属于当前租户：" + id);
    }
    QualityCategoryStatus st = QualityCategoryStatus.valueOf(r.get("category_status", String.class));
    return new QualityCategoryRow(
        r.get("id", UUID.class),
        r.get("category_code", String.class),
        r.get("category_name", String.class),
        st.name(),
        st.label(),
        r.get("sku_count", Long.class),
        r.get("template_count", Long.class),
        r.get("active_template_count", Long.class));
  }

  private Record requireTemplate(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from return_quality_template where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new IllegalArgumentException("质检模板不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private Record activeTemplateOfCategory(UUID categoryId) {
    Record r =
        sql.fetchOne(
            "select * from return_quality_template where tenant_id = ?::uuid and category_id = ?::uuid"
                + " and template_status = 'ACTIVE' order by template_version desc limit 1",
            currentTenant(),
            categoryId);
    if (r == null) {
      throw new IllegalArgumentException(
          "该质检类目还没有已启用的质检模板：模板必须先启用才能用于判定"
              + "（草稿模板改了口径，历史质检单将无法复现当时的口径）");
    }
    return r;
  }

  private Record activeTemplateOfSku(UUID skuId) {
    if (skuId == null) {
      return null;
    }
    return sql.fetchOne(
        "select t.* from return_quality_template t"
            + " join return_quality_category_sku m on m.tenant_id = t.tenant_id and m.category_id = t.category_id"
            + " where t.tenant_id = ?::uuid and m.sku_id = ?::uuid and t.template_status = 'ACTIVE'"
            + " order by t.template_version desc limit 1",
        currentTenant(),
        skuId);
  }

  private Record templateOfInspection(UUID inspectionId) {
    return sql.fetchOne(
        "select t.* from return_quality_template t"
            + " join return_quality_inspection i on i.tenant_id = t.tenant_id and i.template_id = t.id"
            + " where i.tenant_id = ?::uuid and i.id = ?::uuid",
        currentTenant(),
        inspectionId);
  }

  private Record requirePosting(UUID postingId) {
    Record r =
        sql.fetchOne(
            "select * from return_inventory_posting where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            postingId);
    if (r == null) {
      throw new IllegalArgumentException("待检入库分录不存在或不属于当前租户：" + postingId);
    }
    return r;
  }

  private UUID draftInspectionOf(UUID postingId) {
    Record r =
        sql.fetchOne(
            "select id from return_quality_inspection where tenant_id = ?::uuid and posting_id = ?::uuid"
                + " and inspection_status = 'DRAFT' order by created_at desc limit 1",
            currentTenant(),
            postingId);
    return r == null ? null : r.get("id", UUID.class);
  }

  private Record requireInspection(UUID id) {
    Record r =
        sql.fetchOne(
            "select * from return_quality_inspection where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            id);
    if (r == null) {
      throw new IllegalArgumentException("质检单不存在或不属于当前租户：" + id);
    }
    return r;
  }

  private void requireDraft(Record head) {
    QualityInspectionStatus status =
        QualityInspectionStatus.valueOf(head.get("inspection_status", String.class));
    if (!status.draft()) {
      throw new IllegalArgumentException(
          "质检单「" + status.label() + "」是终态，不可修改：" + head.get("id", UUID.class)
              + "（判错了只能作废后重判：结论与质量状态指令已经生成，"
              + "「这批货到底判成什么」出现两个答案比判错更难收拾）");
    }
  }

  private void requireVersion(long current, Long expected, String what) {
    if (expected == null) {
      throw new IllegalArgumentException(
          "必须给出 expectedVersion（乐观锁，避免两个人同时判同一批货互相覆盖）");
    }
    if (expected != current) {
      throw new IllegalArgumentException(
          what + "版本不符（当前 " + current + "，请求 " + expected + "），请刷新后重试");
    }
  }

  /**
   * 原值 = 订单行单价 × 本行实收数量（<b>实时读订单行</b>，订单行改价残值跟着改）。
   *
   * <p>⚠️ 返回<b>未舍入</b>的精确值：单价 4 位 × 数量 6 位 = 10 位小数，
   * 库里触发器用的就是这个未舍入值。服务层若先舍入到 4 位再乘，两边会在第 5 位上分家，
   * 写入时被触发器判成「残值金额与公式算出来的不一致」—— 一个查起来极难看的错。
   */
  private BigDecimal originalValue(UUID receiptLineId) {
    Record r =
        sql.fetchOne(
            "select coalesce(ol.unit_price, 0) * coalesce(rl.received_qty, 0) as base_value"
                + " from return_receipt_line rl"
                + " join rma_authorization_line al"
                + "   on al.tenant_id = rl.tenant_id and al.id = rl.rma_authorization_line_id"
                + " join aftersales_case_line cl"
                + "   on cl.tenant_id = al.tenant_id and cl.id = al.case_line_id"
                + " join sales_order_line ol"
                + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                + " where rl.tenant_id = ?::uuid and rl.id = ?::uuid",
            currentTenant(),
            receiptLineId);
    if (r == null) {
      throw new IllegalArgumentException("实收行不存在或不属于当前租户，无法估算原值：" + receiptLineId);
    }
    return r.get("base_value", BigDecimal.class);
  }

  private String currencyOf(UUID receiptLineId) {
    Record r =
        sql.fetchOne(
            "select o.currency from return_receipt_line rl"
                + " join rma_authorization_line al"
                + "   on al.tenant_id = rl.tenant_id and al.id = rl.rma_authorization_line_id"
                + " join aftersales_case_line cl"
                + "   on cl.tenant_id = al.tenant_id and cl.id = al.case_line_id"
                + " join sales_order_line ol"
                + "   on ol.tenant_id = cl.tenant_id and ol.id = cl.order_line_id"
                + " join sales_order o on o.tenant_id = ol.tenant_id and o.id = ol.order_id"
                + " where rl.tenant_id = ?::uuid and rl.id = ?::uuid",
            currentTenant(),
            receiptLineId);
    return r == null ? "USD" : r.get("currency", String.class);
  }

  private String goodsConditionOf(UUID receiptLineId) {
    Record r =
        sql.fetchOne(
            "select goods_condition from return_receipt_line where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            receiptLineId);
    return r == null ? ReturnReceiptModels.ReturnGoodsCondition.NOT_INSPECTED.name()
        : r.get("goods_condition", String.class);
  }

  private String packagingConditionOf(UUID receiptLineId) {
    Record r =
        sql.fetchOne(
            "select packaging_condition from return_receipt_line where tenant_id = ?::uuid and id = ?::uuid",
            currentTenant(),
            receiptLineId);
    return r == null ? ReturnReceiptModels.ReturnPackagingCondition.NOT_INSPECTED.name()
        : r.get("packaging_condition", String.class);
  }

  private UUID currentTenant() {
    return UUID.fromString(sql.fetchOne("select current_setting('app.current_tenant')").get(0, String.class));
  }

  private static BigDecimal coefficient(BigDecimal value, String label) {
    if (value == null) {
      throw new IllegalArgumentException("必须给出" + label + "（成色系数是残值的输入，禁浮点也禁缺省）");
    }
    BigDecimal scaled = value.setScale(RATE_SCALE, RoundingMode.HALF_UP);
    if (scaled.signum() < 0 || scaled.compareTo(ONE) > 0) {
      throw new IllegalArgumentException(label + "必须在 0 与 1 之间：" + value);
    }
    return scaled;
  }

  private static String requireName(String value) {
    if (!isSet(value)) {
      throw new IllegalArgumentException("必须给出名称");
    }
    return value.trim();
  }

  private static List<QualityCheckResultDraft> safeResults(List<QualityCheckResultDraft> results) {
    return results == null ? List.of() : results;
  }

  private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("必须给出" + label);
    }
    try {
      return Enum.valueOf(type, raw.trim());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          label + "取值非法：" + raw + "（可选：" + java.util.Arrays.toString(type.getEnumConstants()) + "）");
    }
  }

  private static boolean isSet(String value) {
    return value != null && !value.isBlank();
  }

  private static String trimOrEmpty(String value) {
    return isSet(value) ? value.trim() : "";
  }

  private static String eventLabel(String code) {
    return switch (code) {
      case "CREATED" -> "登记质检草稿";
      case "RESULT_RECORDED" -> "录入逐项结果";
      case "CONCLUDED" -> "判定";
      case "CANCELLED" -> "撤销";
      default -> code;
    };
  }

  private static String instructionLabel(String type) {
    return switch (type) {
      case "MOVE_TO_SELLABLE" -> "转入可售";
      case "MOVE_TO_DEFECTIVE" -> "转入残次";
      case "WRITE_OFF_SCRAP" -> "报废核销";
      default -> type;
    };
  }

  private static QualitySectionCapability section(
      String section, String label, boolean available, String ownerModule, String reason,
      String landsIn) {
    return new QualitySectionCapability(section, label, available, ownerModule, reason, landsIn);
  }

  private static QualityRelatedModule related(
      String id, String label, boolean delivered, String ownerModule, String reason, String href) {
    return new QualityRelatedModule(id, label, delivered, ownerModule, reason, href);
  }

  private static QualityAction action(
      String code, String label, boolean allowed, String method, String path, boolean required,
      String precondition, String blockingReason, String ownerModule, String unavailableReason) {
    return new QualityAction(
        code, label, allowed, method, path, required, precondition, blockingReason, ownerModule,
        unavailableReason);
  }

  private static QualityBoundary boundary(String boundary, String ownerModule, String reason) {
    return new QualityBoundary(boundary, ownerModule, reason);
  }

  /** 模板排序白名单。 */
  private enum QualityTemplateSort {
    CREATED_DESC("t.created_at desc, t.id desc"),
    ACTIVATED_DESC("t.activated_at desc nulls last, t.id desc"),
    VERSION_DESC("t.category_id, t.template_version desc, t.id desc"),
    CATEGORY_ASC("c.category_code, t.template_version desc, t.id desc");

    private final String sql;

    QualityTemplateSort(String sql) {
      this.sql = sql;
    }

    String sql() {
      return this.sql;
    }

    static QualityTemplateSort parse(String raw) {
      if (raw == null) {
        return CREATED_DESC;
      }
      return switch (raw) {
        case "created_desc" -> CREATED_DESC;
        case "activated_desc" -> ACTIVATED_DESC;
        case "version_desc" -> VERSION_DESC;
        case "category_asc" -> CATEGORY_ASC;
        default -> CREATED_DESC;
      };
    }
  }
}
