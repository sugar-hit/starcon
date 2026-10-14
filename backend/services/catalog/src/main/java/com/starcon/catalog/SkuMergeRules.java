package com.starcon.catalog;

import java.util.List;
import java.util.Map;

/**
 * 3.9.1.2 SKU 合并的<b>纯规则</b>：哪些引用搬、怎么搬、哪些刻意不搬，以及版本比对与执行范围的判定。
 *
 * <p>与本模块其他类的关系：{@link SkuMergeService} 负责取数与落库， 一切<b>判断</b>都在这里，且这里不碰数据库 —— 这样每条规则都能被单元测试直接钉住，
 * 而集成测试只需要证明「规则被真实数据正确地应用了」。
 *
 * <h2>为什么引用策略是一张表而不是 if 分支</h2>
 *
 * <p>蓝图点名的迁移面是「listing 映射、库存 bucket、采购历史、价格、订单快照保留指向」， 落在 catalog 自己的库里有 <b>29 张表带 {@code
 * sku_id}</b>。逐张 if 写下去， 「这张表到底搬不搬」这个最重要的决定会淹没在代码流里，而它恰恰是审计时最需要一眼看清的东西。 所以搬不搬、怎么搬写在这张表里，{@code
 * table} 与 {@code extra} 放在一起而不是写成两条成品 SQL： 全量计数与执行迁移两条查询必须永远同源，分开写两份字符串迟早会只改到一处。
 *
 * <h2>三种动作与五种计数</h2>
 *
 * <ul>
 *   <li><b>MOVE</b> —— 改指主记录。撞上唯一约束的行<b>留在被合并记录上</b>（{@code conflictCount}）， 除非这一维度是去重语义（{@link
 *       #dropConflicts}），那种冲突行是纯冗余、删掉不丢信息。
 *   <li><b>SUM</b> —— 组件类引用（BOM 组件 / 套装组件）的常态：同一个版本里同时挂着两条重复 SKU 时， 「每 1
 *       个成品需要这个组件多少」的答案应当是<b>两者之和</b>，而不是二选一。 所以冲突行不删也不留，而是把用量加进主记录的同行，再删掉被合并记录上的那行。
 *   <li><b>FROZEN</b> —— 历史事实，刻意不动（见 {@link #FROZEN_POLICIES} 的逐条理由）。
 * </ul>
 *
 * <p>五种计数不折叠成一个数：搬走 / 相加 / 去重 / 搬不动 / 刻意不动是五种不同的处理动作， 运营要判断的正是「哪几类引用在挡路」，一个合计数答不了这个问题。
 */
final class SkuMergeRules {
  private SkuMergeRules() {}

  /** 一个维度的动作。 */
  static final String MOVE = "MOVE";

  static final String SUM = "SUM";
  static final String FROZEN = "FROZEN";

  /** 版本比对结果。 */
  static final String CHECK_MATCHED = "MATCHED";

  static final String CHECK_STALE = "STALE";
  static final String CHECK_ALREADY_EXECUTED = "ALREADY_EXECUTED";
  static final String CHECK_NOT_DECIDED = "NOT_DECIDED";
  static final String CHECK_ALREADY_MERGED = "ALREADY_MERGED";
  static final String CHECK_KEEP = "KEEP";

  /** 一次被计数/被冻结的表 + 附加条件。 */
  /**
   * 一个被计数/被冻结的表。
   *
   * @param skuCol 该表指向 SKU 的列名，{@code null} 时用默认的 {@code sku_id}。 有一个例外必须点名：{@code
   *     bom_explosion_run} 记的是「这次展开算的是哪个成品的 BOM」， 所以它的列叫 {@code output_sku_id}（与 {@code bom}
   *     同名同义）而**不是** {@code sku_id} —— 套默认列名会直接报「列不存在」。
   * @param sideCols 该表指向 SKU 的<b>两侧</b>列名（3.9.1.1 的候选是 {@code left_sku_id / right_sku_id}、决定是
   *     {@code primary_sku_id / secondary_sku_id}）， 而不是单列 {@code sku_id}。为 {@code null} 时按单列 {@code
   *     sku_id} 计。 <b>这两张表没有 {@code sku_id}</b>：给它们套一个「按 sku_id 统计」的通用分支， 报出来的会是「这一类没有引用」——
   *     而实际上有（本模块刚执行的那次合并就在里面）， 那比直接报错危险得多。
   */
  record TableRef(String table, String extra, String skuCol, String[] sideCols) {
    TableRef(String table) {
      this(table, "1=1", null, null);
    }

    static TableRef col(String table, String column) {
      return new TableRef(table, "1=1", column, null);
    }

    static TableRef either(String table, String left, String right) {
      return new TableRef(table, "1=1", null, new String[] {left, right});
    }
  }

  /**
   * 一个引用维度的完整策略。
   *
   * @param skuCol 该表指向 SKU 的列名。绝大多数是 {@code sku_id}，唯一的例外是 {@code bom.output_sku_id}（BOM 说的是「这个 BOM
   *     产出哪个 SKU」，方向与其它表相反、 列名也不同）。写死成 {@code sku_id} 会让这一维度<b>静默统计成 0</b> ——
   *     报告上看起来「这一类没有引用」，而实际上有，那比报错危险得多。
   * @param keyCols 冲突判定键。判「冲突」用的是 {@code is not distinct from}（NULL 安全）。
   *     <b>空数组</b>表示「主记录上只要已经有该表任何一行就算冲突」—— 对应 {@code UNIQUE(tenant_id,sku_id)} 那几张表（包装参数 / 安全属性 /
   *     套装代表）。
   * @param qtyCol {@link #SUM} 时要相加的列（NUMERIC(19,6)，CHECK>0，所以和仍是正的）
   * @param dropConflicts 冲突行是否<b>删除</b>而不是留在原处。只有去重语义为真： 素材引用里「同一张图在两条重复 SKU 上各挂一条」是重复的产物，
   *     主记录上已经有一条完全相同的引用，被合并记录上那一条纯属冗余。
   * @param sameSpuOnly 只在两条 SKU 同一个 SPU 时可迁移。 {@code attribute_value} 是唯一一张：它的外键是 {@code
   *     (tenant_id,spu_id,sku_id) → sku(tenant_id,spu_id,id)}， 跨 SPU 改指会让 {@code spu_id} 与 {@code
   *     sku_id} 指向一个不存在的组合。 就算硬把 spu_id 也改成主记录的，{@code attribute_id} 指向的仍是源 SPU 的属性定义 （V1 的 {@code
   *     (tenant_id,spu_id,attribute_id) → attribute_definition}）， 那条改指在数据上没有意义。所以跨 SPU
   *     时整维度被拒并明确报出来。
   * @param frozenSources {@link #FROZEN} 维度要计数的表（可多张，合成一个报告行）
   */
  record RefPolicy(
      String dimension,
      String label,
      String action,
      String table,
      String skuCol,
      String extra,
      List<String> keyCols,
      String qtyCol,
      boolean dropConflicts,
      boolean sameSpuOnly,
      List<TableRef> frozenSources,
      String note) {

    /** 冲突判定表达式（{@code s} = 被合并记录侧行，{@code t} = 主记录侧行）。 */
    String conflictJoin() {
      if (keyCols.isEmpty()) return "true";
      StringBuilder sb = new StringBuilder();
      for (String col : keyCols) {
        if (sb.length() > 0) sb.append(" and ");
        sb.append("t.").append(col).append(" is not distinct from s.").append(col);
      }
      return sb.toString();
    }
  }

  // ================================================================ 搬得动的当前引用

  /**
   * 当前引用：合并后应该跟着主记录走的那一批。
   *
   * <p>顺序即页面展示顺序：<b>先搬得动的、再搬不动的、最后刻意不动的历史</b>。 词表与 V36 的九类引用刻意对齐（候选详情页上给运营看的就是那九个数字， 报告换一套词表会让「引用 3
   * 条」与「冲突 3 条」在页面上对不上）， 另加三类 catalog 侧当前引用：TRANSLATION 多语言标题、BUNDLE_SKU 套装代表、BOM_OUTPUT BOM 产出物。
   */
  static final List<RefPolicy> MOVE_POLICIES =
      List.of(
          new RefPolicy(
              "BARCODE",
              "条码与物料标识",
              MOVE,
              "sku_identifier",
              "sku_id",
              "1=1",
              List.of("scheme", "value"),
              null,
              false,
              false,
              List.of(),
              "UNIQUE(tenant_id,scheme,value)：同一个条码值不能挂两条 SKU，"
                  + "主记录已有同值条码时被合并记录的这一行留在原处（该条码仍可查到，"
                  + "而删掉它等于让「这两条曾经撞过码」这件事查无对证）"),
          new RefPolicy(
              "ATTRIBUTE",
              "属性值",
              MOVE,
              "attribute_value",
              "sku_id",
              "sku_id is not null",
              List.of("attribute_id"),
              null,
              false,
              true,
              List.of(),
              "UNIQUE NULLS NOT DISTINCT(tenant_id,attribute_id,sku_id)：主记录已设过同一属性时，"
                  + "被合并记录的那一行留在原处（主记录的值不被覆盖 —— 覆盖掉等于用另一条商品的值"
                  + "悄悄改掉主记录的规格）；跨 SPU 合并时整维度不搬，理由见 sameSpuOnly"),
          new RefPolicy(
              "MEDIA",
              "素材引用",
              MOVE,
              "media_reference",
              "sku_id",
              "owner_type='SKU'",
              List.of("asset_id"),
              null,
              true,
              false,
              List.of(),
              "同一张图在两条重复 SKU 上各挂一条是重复建档的产物，主记录上已有完全相同的引用，"
                  + "所以冲突行按去重删除（这里与其它维度相反：留下的那条与主记录的那条信息完全相同，"
                  + "删掉不丢任何东西）"),
          new RefPolicy(
              "TRANSLATION",
              "多语言标题",
              MOVE,
              "sku_translation",
              "sku_id",
              "1=1",
              List.of("locale"),
              null,
              false,
              false,
              List.of(),
              "UNIQUE(tenant_id,sku_id,locale)：同一语种主记录已有译文时不覆盖" + "（译文是人工审过的内容，不该被另一条商品的机器译文顶掉）"),
          new RefPolicy(
              "QUOTE",
              "供应商报价",
              MOVE,
              "supplier_quote",
              "sku_id",
              "1=1",
              List.of("supplier_role_id", "supplier_sku_code"),
              null,
              false,
              false,
              List.of(),
              "蓝图的「价格」：报价是当前主数据，必须跟着主记录走，否则合并后主记录会少掉一个供应商的价。"
                  + "UNIQUE(tenant_id,supplier_role_id,supplier_sku_code) 挡住「同供应商同货号两条」时"
                  + "留在原处 —— 这恰恰是货号撞车的现场，留着比删掉更有排查价值"),
          new RefPolicy(
              "BUNDLE",
              "套装组件",
              SUM,
              "bundle_component",
              "sku_id",
              "1=1",
              List.of("version_id"),
              "quantity",
              false,
              false,
              List.of(),
              "同一套装版本里同时挂着两条重复 SKU 时，答案是「每套需要两条之和」而不是二选一，"
                  + "所以用量相加后删掉被合并记录的那行；quantity 是 NUMERIC(19,6) CHECK>0，和仍是正的"),
          new RefPolicy(
              "BUNDLE_SKU",
              "套装代表",
              MOVE,
              "bundle_sku",
              "sku_id",
              "1=1",
              List.of(),
              null,
              false,
              false,
              List.of(),
              "UNIQUE(tenant_id,sku_id)（一个 SKU 至多代表一个套装）：主记录已经是某个套装的代表时"
                  + "留在原处，不覆盖 —— 一个 SKU 只能代表一个套装，这条约束没有「二选一」的余地"),
          new RefPolicy(
              "BOM_OUTPUT",
              "BOM 产出物",
              MOVE,
              "bom",
              "output_sku_id",
              "1=1",
              List.of(),
              null,
              false,
              false,
              List.of(),
              "一个 SKU 有自己的 BOM（bom.output_sku_id）时，主记录已有 BOM 就不覆盖，"
                  + "留在原处：BOM 是要人工审核的工艺文件，两份不能自动合成一份"),
          new RefPolicy(
              "BOM",
              "BOM 组件",
              SUM,
              "bom_component",
              "sku_id",
              "1=1",
              List.of("version_id"),
              "unit_quantity",
              false,
              false,
              List.of(),
              "同一 BOM 版本里同时挂着两条重复 SKU 时，单位用量相加后删掉被合并记录的那行；"
                  + "理由与套装组件相同 ——「每 1 个成品需要这个组件多少」只能有一个答案"),
          new RefPolicy(
              "PACKAGE",
              "包装参数",
              MOVE,
              "sku_package",
              "sku_id",
              "1=1",
              List.of(),
              null,
              false,
              false,
              List.of(),
              "UNIQUE(tenant_id,sku_id)：主记录已有包装参数时留在原处，不覆盖" + "（重量尺寸是报关与运费的事实，两个值不能自动取其一）"),
          new RefPolicy(
              "SAFETY",
              "安全属性",
              MOVE,
              "sku_safety_attribute",
              "sku_id",
              "1=1",
              List.of(),
              null,
              false,
              false,
              List.of(),
              "UNIQUE(tenant_id,sku_id)：主记录已采集过安全属性时留在原处。" + "电池/液体/危险品这四项是合规申报的输入，合并时不覆盖主记录已采集的值"),
          new RefPolicy(
              "UNIT_RULE",
              "单位换算规则",
              MOVE,
              "sku_unit_conversion",
              "sku_id",
              "1=1",
              List.of("unit_code", "role", "effective_from"),
              null,
              false,
              false,
              List.of(),
              "单位换算规则是当前主数据（采购/销售/包装单位的换比），合并后必须跟着主记录走，"
                  + "否则主记录会少掉一个换算档，06 建采购单时按错比例算数量。"
                  + "UNIQUE(tenant_id,sku_id,unit_code,role,effective_from) 冲突时留在原处。"
                  + "**与下面 UNIT_CONVERSION_SNAPSHOT 的区别**：这里是活的换算表，"
                  + "那里是单据行冻结快照 —— 一个要搬，一个不能搬"));

  /**
   * 冻结的历史事实：<b>刻意不搬</b>，继续指向被合并记录。
   *
   * <p>蓝图关系行：「<b>历史订单行冻结当时 SKU 快照，不随合并回溯改写</b>（实体目录 §3.6） ——只迁移『当前引用』（映射/库存/价格），历史事实保持冻结。」
   *
   * <p>不搬的理由对每一组都成立：这些行回答的是「<b>当时</b>长什么样 / 当时依据哪一版」， 改写它们等于让证据随结论一起变。逐组：
   *
   * <ul>
   *   <li>{@code sku_unit_conversion_snapshot} —— 采购单/入库单/出库单的<b>行冻结快照</b>， {@code
   *       UNIQUE(tenant_id,document_type,document_ref,line_no)} 钉在真实单据行上。 搬走它等于说「当初那单用的换算比例是按合并后的
   *       SKU 算的」—— 而它当初不是。 蓝图的「采购历史」正落在这里。
   *   <li>{@code sku_status_event} —— SKU 生命周期事件流。它是「这条商品经历过什么」的时间线，
   *       改指会让合并这件事本身在时间线上凭空消失。合并只会<b>追加</b>一条 INACTIVE 事件，不动已有的。
   *   <li>{@code import_job_row} —— 批量导入的行级历史。导入报告要能回答「这行数据是哪次导入进来的」。
   *   <li>{@code title_record} / {@code title_generation_sku} —— 标题生成的历史记录与快照。
   *   <li>{@code content_render_*} —— 详情页渲染产物（含已发现的渲染问题），是那一版的渲染结论。
   *   <li>{@code translation_batch_item} / {@code translation_suggestion} —— 翻译批次与建议的处理痕迹。
   *   <li>{@code bom_explosion_run} / {@code bom_explosion_line} —— 一次爆炸展开的运行与结果。
   *   <li>{@code sku_code_assignment} / {@code sku_code_batch_item} / {@code
   *       sku_batch_operation_item} —— 编码分配与批量操作的执行历史。
   *   <li>{@code sku_duplicate_candidate} / {@code sku_duplicate_decision} —— 3.9.1.1 自己的候选与决定。
   *       <b>这一组是合并来源本身</b>：它们记着「当初是哪一次扫描、依据哪几维证据、 谁在什么时候判的合并」——改了它们，这次合并就再也回溯不了来源了。
   * </ul>
   */
  static final List<RefPolicy> FROZEN_POLICIES =
      List.of(
          frozen(
              "UNIT_CONVERSION_SNAPSHOT",
              "单位换算快照",
              "采购单/入库单/出库单的行冻结快照，钉在 document_ref+line_no 上。"
                  + "蓝图的「采购历史」落在这里：搬走它等于让单据行指向一个合并后才存在的身份",
              "sku_unit_conversion_snapshot"),
          frozen(
              "STATUS_EVENT",
              "SKU 状态事件",
              "生命周期事件流。合并只追加一条「被合并而停用」的事件，不改已有的任何一条",
              "sku_status_event"),
          frozen(
              "IMPORT_HISTORY",
              "批量导入行",
              "导入报告要能回答「这行是哪次导入进来的」，改指之后那次导入的记录就对不上原始行了",
              "import_job_row"),
          frozen(
              "TITLE_HISTORY", "标题生成记录", "标题生成那一轮的历史与快照", "title_generation_sku", "title_record"),
          frozen(
              "RENDER_ARTIFACT",
              "详情渲染产物",
              "渲染产物是那一版的结论，含当时发现的渲染问题",
              "content_render_sku",
              "content_render_section",
              "content_render_issue"),
          frozen(
              "TRANSLATION_HISTORY",
              "翻译批次与建议",
              "翻译处理痕迹：某条建议被采纳或拒绝过，是可回溯的处理历史",
              "translation_batch_item",
              "translation_suggestion"),
          new RefPolicy(
              "BOM_EXPLOSION_HISTORY",
              "BOM 爆炸历史",
              FROZEN,
              null,
              "sku_id",
              null,
              List.of(),
              null,
              false,
              false,
              List.of(
                  TableRef.col("bom_explosion_run", "output_sku_id"),
                  new TableRef("bom_explosion_line")),
              "一次展开的运行与结果；重跑会另起一次运行，历史不需要跟着合并走"),
          frozen(
              "CODE_HISTORY",
              "编码与批量操作历史",
              "编码分配（from_code→to_code）与批量操作的执行历史",
              "sku_code_assignment",
              "sku_code_batch_item",
              "sku_batch_operation_item"),
          new RefPolicy(
              "DUPLICATE_HISTORY",
              "重复识别候选与决定",
              FROZEN,
              null,
              "sku_id",
              null,
              List.of(),
              null,
              false,
              false,
              List.of(
                  TableRef.either("sku_duplicate_candidate", "left_sku_id", "right_sku_id"),
                  TableRef.either("sku_duplicate_decision", "primary_sku_id", "secondary_sku_id")),
              "3.9.1.1 自己的记录，也就是本次合并的来源。" + "改了它就再也答不出「当初是哪次扫描、依据哪几维证据、谁判的合并」"));

  private static RefPolicy frozen(String dimension, String label, String note, String... tables) {
    List<TableRef> sources = java.util.Arrays.stream(tables).map(t -> new TableRef(t)).toList();
    return new RefPolicy(
        dimension, label, FROZEN, null, "sku_id", null, List.of(), null, false, false, sources,
        note);
  }

  /** 全部维度（搬得动的在前，冻结的历史在后）。 */
  static List<RefPolicy> allPolicies() {
    List<RefPolicy> all = new java.util.ArrayList<>(MOVE_POLICIES);
    all.addAll(FROZEN_POLICIES);
    return List.copyOf(all);
  }

  static RefPolicy policy(String dimension) {
    for (RefPolicy p : allPolicies()) if (p.dimension().equals(dimension)) return p;
    throw new IllegalArgumentException("未知的引用维度：" + dimension);
  }

  // ================================================================ 展示词表

  private static final Map<String, String> ACTION_LABELS =
      Map.of(MOVE, "已改指主记录", SUM, "已并入主记录（用量相加）", FROZEN, "历史事实·保持冻结", "NONE", "无引用");

  static String actionLabel(String action) {
    return ACTION_LABELS.getOrDefault(action, action);
  }

  static String scopeLabel(String scope) {
    return "SPU".equals(scope) ? "跨 SPU 合并（源商品连带停产）" : "同 SPU 合并（只并掉一条 SKU）";
  }

  /**
   * 版本比对结论。
   *
   * @param decision 3.9.1.1 登记的决定（null = 还没人判）
   * @param executed 是否已有执行记录
   * @param primaryMerged / secondaryMerged 两端是否已被合并过
   * @param expectedPrimary / expectedSecondary 决定里登记的版本（MERGE 决定一定有值）
   * @param actualPrimary / actualActual 此刻 sku 表里的真实版本
   * @return CHECK_MATCHED 时才允许执行
   */
  static String versionCheck(
      String decision,
      boolean executed,
      boolean primaryMerged,
      boolean secondaryMerged,
      Long expectedPrimary,
      Long expectedSecondary,
      long actualPrimary,
      long actualSecondary) {
    if (decision == null) return CHECK_NOT_DECIDED;
    if ("KEEP".equals(decision)) return CHECK_KEEP;
    if (executed) return CHECK_ALREADY_EXECUTED;
    // 合并链保持一层：两端任一已被合并过就不再允许参与下一次归并。
    if (primaryMerged || secondaryMerged) return CHECK_ALREADY_MERGED;
    if (expectedPrimary == null || expectedSecondary == null) return CHECK_NOT_DECIDED;
    if (expectedPrimary != actualPrimary || expectedSecondary != actualSecondary)
      return CHECK_STALE;
    return CHECK_MATCHED;
  }

  /** 版本比对结论的一句话，给页面与报错共用。 */
  static String versionCheckLabel(String check) {
    return switch (check) {
      case CHECK_MATCHED -> "决定仍然成立：登记之后两条商品都没有被改动过，可以执行合并。";
      case CHECK_STALE -> "决定已过期：登记之后主记录或被合并记录被人改过（版本对不上），" + "当初「像不像」的结论可能已经不成立，请重新扫描并确认后再合并。";
      case CHECK_ALREADY_EXECUTED -> "这份决定已经执行过了，不能重复合并。";
      case CHECK_NOT_DECIDED -> "还没有登记合并决定，或登记的不是「合并」——本模块只执行已登记的合并决定。";
      case CHECK_KEEP -> "这一对被判为「保留两条」，不应对它们做任何归并。";
      case CHECK_ALREADY_MERGED ->
          "两条记录里至少有一条已经被合并过。合并链保持一层：把「已并进别人的记录」再拿来做主记录，" + "或把「被别人并进来的记录」再并进第三条，都会让「当前有效归属」不再唯一。";
      default -> check;
    };
  }

  /** 跨 SPU 时的额外说明（源 SPU 上剩下的 SKU 不会被搬）。 */
  static String crossSpuNote(int remainingSkuCount) {
    return "两条 SKU 分属不同商品（3.9.1.1 的 CROSS_SPU 维度：同商品不同 SPU 重复建档）。"
        + "本次合并把源商品停产并标记为已合并；"
        + (remainingSkuCount == 0
            ? "源商品上没有其它 SKU，合并后它就是一条空壳。"
            : "源商品上还有 "
                + remainingSkuCount
                + " 条 SKU 不在这次登记的决定范围内，"
                + "它们的引用一条都没动 —— 把它们搬到主商品需要逐条判断是不是同一个商品，"
                + "而登记的决定只覆盖了「这一对 SKU」。")
        + "历史订单行冻结当时快照，不随合并回溯改写。";
  }
}
