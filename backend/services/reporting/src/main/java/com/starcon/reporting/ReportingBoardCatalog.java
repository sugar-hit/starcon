package com.starcon.reporting;

import java.util.List;
import java.util.Locale;

/**
 * 13.6.1.2 看板搭建的**受控目录与常量**。
 *
 * <p>🔴 **本类就是「看板不能一个通吃」的物理边界**：能摆到看板上的指标码、维度码、
 * 图表类型、布局形状、权限码形状，**全部**是这个类（或
 * {@link ReportingCustomReportCatalog}）里的常量与白名单。
 * 服务层拼装查询时，SQL 片段**只从这些常量里取**，用户输入**只作为绑定参数**（{@code ?}）
 * 传进去 —— 任何时候都不把用户输入拼进 SQL 文本。
 *
 * <p>蓝图原文（13.6.1.2 关系句）：
 *
 * <blockquote>
 * 「**看板需权限过滤**，不同角色看到的组件不同，不能一个看板通吃。」
 * </blockquote>
 *
 * <p>⇒ 三道防线：
 *
 * <ol>
 *   <li>DB 层：组件的 {@code metric_code} 必须**已登记**（触发器），维度码只能命中白名单（CHECK）；</li>
 *   <li>DB 层：视图 {@code reporting_board_component_visible} 按 {@code required_permission} 过滤；</li>
 *   <li>服务层：再按当前用户的权限 / 角色过滤一遍（保险，不是唯一防线）。</li>
 * </ol>
 *
 * <p>⚠️ **维度白名单与 13.6.1.1 完全一致**（同一张表 {@code reporting_metric_fact} 上的真实列），
 * **不另起一套**：看板用 6 个维度而报表用 5 个，就会出现「同一个数字两个拆法」。
 */
public final class ReportingBoardCatalog {

  private ReportingBoardCatalog() {}

  // ==========================================================================
  // 栅格常量
  // ==========================================================================

  /** 看板宽度（栅格列数）。 */
  public static final int GRID_COLUMNS = 12;

  /** 一屏最多多少行。 */
  public static final int MAX_GRID_ROWS = 64;

  /** 一张看板最多摆多少个组件（防止一个看板把服务拖死）。 */
  public static final int MAX_COMPONENTS_PER_BOARD = 50;

  /** 一份模板最多带多少个组件。 */
  public static final int MAX_COMPONENTS_PER_TEMPLATE = 50;

  /** 组件默认尺寸（宽 3 格 × 高 2 行）。 */
  public static final int DEFAULT_COMPONENT_WIDTH = 3;

  /** 组件默认高度。 */
  public static final int DEFAULT_COMPONENT_HEIGHT = 2;

  // ==========================================================================
  // 权限码形状
  // ==========================================================================

  /** 权限码的形状（小写 + 点分段，如 {@code reporting.board.read}）。 */
  private static final java.util.regex.Pattern PERMISSION_CODE =
      java.util.regex.Pattern.compile("^[a-z][a-z0-9]*(\\.[a-z0-9-]+)+$");

  /**
   * 校验权限码的形状；空串合法（= 谁都能看）。
   *
   * <p>⚠️ 这里只校验**形状**，不校验「这个权限码真的存在」——
   * 权限码的登记册在 identity 服务里，reporting 不跨服务读它
   * （跨服务只走授权 API / event，见 AGENTS.md 的模块边界纪律）。
   * 一个不存在的权限码只会让**没有人**看得见这个组件（fail-closed，不 fail-open）。
   */
  public static boolean validPermissionCode(String code) {
    if (code == null || code.isEmpty()) return true;
    return PERMISSION_CODE.matcher(code).matches();
  }

  // ==========================================================================
  // 可见范围
  // ==========================================================================

  /**
   * 看板的可见范围（**白名单**）。
   *
   * <p>PRIVATE = 只有创建者（要给别人看得显式开共享）；TEAM = 团队内成员。
   */
  public enum BoardVisibility {
    /** 仅创建者（除非开了共享）。 */
    PRIVATE,
    /** 团队内成员（组件仍按权限过滤）。 */
    TEAM;

    /** 中文标签。 */
    public String label() {
      return this == TEAM ? "团队可见" : "仅自己可见";
    }

    /** 按码取；取不到抛异常（不静默回落成 PRIVATE）。 */
    public static BoardVisibility of(String code) {
      if (code == null || code.isBlank()) return PRIVATE;
      var normalized = code.trim().toUpperCase(Locale.ROOT);
      for (var visibility : values()) {
        if (visibility.name().equals(normalized)) return visibility;
      }
      throw new IllegalArgumentException(
          "看板可见范围只能是 PRIVATE / TEAM（收到的是「" + code + "」）。");
    }
  }

  /**
   * 共享的可见范围（**白名单**）。
   *
   * <p>ROLE = 只有命中 role_id / permission_code 的成员能打开这张看板；
   * TENANT = 租户内成员都能打开（组件**仍然**按 required_permission 过滤）。
   */
  public enum ShareScope {
    /** 仅命中角色 / 权限码的人。 */
    ROLE,
    /** 租户内成员。 */
    TENANT;

    /** 中文标签。 */
    public String label() {
      return this == TENANT ? "租户内成员" : "仅命中角色的人";
    }

    /** 按码取；取不到抛异常（不静默回落成 ROLE）。 */
    public static ShareScope of(String code) {
      if (code == null || code.isBlank()) return ROLE;
      var normalized = code.trim().toUpperCase(Locale.ROOT);
      for (var scope : values()) {
        if (scope.name().equals(normalized)) return scope;
      }
      throw new IllegalArgumentException(
          "共享可见范围只能是 ROLE / TENANT（收到的是「" + code + "」）。");
    }
  }

  // ==========================================================================
  // 能力声明里的静态事实
  // ==========================================================================

  /** 本服务**真的读过**的上游模块。 */
  public static final List<String> UPSTREAM_MODULES =
      List.of(
          "13.6.1.1 自定义报表 · 受控语义层目录（指标清单 / 维度白名单 / 筛选运算符）—— 本模块**只引用**它",
          "13.6.1.1 自定义报表 · 报表查询引擎（口径不重算）—— 本模块的组件数据**由它算出来**",
          "13.1.1.1 经营总览 · 指标口径唯一表（reporting_metric_definition）—— 组件指标码的登记册");

  /**
   * **确实拿不到**的部分（每条都写清原因与影响）。
   *
   * <p>🔴 **导出不属本模块**：看板的导出 / 定时推送属 13.6.2.1（未交付）。
   */
  public static final List<String> NOT_DELIVERED =
      List.of(
          "🔴 **看板的导出与定时推送未交付** —— 归属 13.6.2.1 导出与订阅。"
              + "蓝图写死「大导出走异步任务并给出有效期」⇒ 那需要**异步任务表 + 文件有效期**，"
              + "本模块既没有导出端点，也没有导出任务表。界面上「导出」明确写「未交付」，不提供假按钮。",
          "🔴 **看板的数字不落库** —— 组件上的数字是**按登记口径实时算出来**的读模型。"
              + "落一份结果就是第二个真相：口径改了、事实刷新了，落库的那份不会跟着变。",
          "🔴 **不开放任意 SQL** —— 蓝图关系句的硬约束。组件只能引用**已登记的 metric_code**"
              + "（口径唯一，DB 层有触发器钉死），维度只能取 13.6.1.1 那张 5 个码的白名单，"
              + "筛选值一律走**绑定参数**，用户输入**永远不会**被拼进 SQL 文本。",
          "**法人 / 店铺维度给的是 uuid 文本，不是名称** —— 语义层不带法人 / 店铺的名称档案"
              + "⇒ 看板里给 uuid，**不编一个名字**（编名字 = 第二个真相）。",
          "**1 工作台的「个人看板」置位属下游模块** —— 蓝图关系句写死「下游 = 1 工作台」，"
              + "那属卷 01 的工作台与门户系统；本模块只提供看板与看板视图，"
              + "**不**在本服务里另起一套工作台。",
          "**角色 id 来自 identity，本服务不校验它是否存在** —— 跨服务只走授权 API / event"
              + "（AGENTS.md 的模块边界纪律）⇒ 一个失效的角色 id 会让这条共享**谁也命中不了**"
              + "（fail-closed，不 fail-open）。");

  /** 判定规则与口径说明（页面上照着显示）。 */
  public static final List<BoardRuleNote> RULES =
      List.of(
          new BoardRuleNote(
              "METRIC_REGISTRY",
              "🔴 **口径唯一**：每个组件的指标都必须能追到一个**已登记的 metric_code**"
                  + "（13.1.1.1 的 reporting_metric_definition 是唯一口径表）。"
                  + "DB 层有触发器钉死 —— **写不进未登记的指标码**，"
                  + "看板不能绕过登记表自己发明一个指标。"),
          new BoardRuleNote(
              "PERMISSION_FILTER",
              "🔴 **不同角色看到的组件不同**：组件上写明「谁才看得懂」（required_permission）。"
                  + "看得见的组件 = 没有权限要求的 + 你持有那个权限的 + "
                  + "（看板创建者看得到自己摆的全部）。这条规则同时写在**库层的视图**与**服务层**里，"
                  + "两边同一条 —— 不能一个看板通吃。"),
          new BoardRuleNote(
              "ROLE_SHARE",
              "**按角色共享**：共享要么圈一个角色（role_id），要么圈一个权限码（permission_code），"
                  + "**二选一**（DB 层 CHECK 钉死）。共享的可见范围两档："
                  + "ROLE（仅命中的人）/ TENANT（租户内成员，但组件仍按权限过滤）。"),
          new BoardRuleNote(
              "LAYOUT_SHAPE",
              "**拖拽布局有形状约束**：位置是非负整数、尺寸是正整数、"
                  + "x + w ≤ " + GRID_COLUMNS + "（看板宽 " + GRID_COLUMNS + " 栅格）。"
                  + "非法布局**直接报错**（400），**不静默丢弃** —— "
                  + "悄悄挪回默认位置会让人以为存上了。"),
          new BoardRuleNote(
              "CURRENCY",
              "🔴 **涉币指标不跨币种相加**：按币种分别报（USD 一行、EUR 一行）。"
                  + "与货币无关的量（笔数 / 件数）给一个跨币种合计，它的币种显示为空串 —— "
                  + "空串 = 与币种无关，不是「币种未知」。"),
          new BoardRuleNote(
              "NO_FAKE_ZERO",
              "🔴 **「算不出来」≠「数据是 0」**：分母为 0 / 事实缺失的度量显示「—」"
                  + "并写明原因，**不用 0 顶替**。"),
          new BoardRuleNote(
              "DATA_LATENCY",
              "**数据延迟**：看板是投影，不是实时的。页面上常驻显示**数据更新时间**"
                  + "（最近一次投影落库时刻）；空表时是 null（不是一个长得像时间的假值）。"
                  + "看板数字与业务系统冲突时，**以业务系统为准**。"));

  /** 一条规则 / 口径说明。 */
  public record BoardRuleNote(String ruleCode, String description) {}
}
