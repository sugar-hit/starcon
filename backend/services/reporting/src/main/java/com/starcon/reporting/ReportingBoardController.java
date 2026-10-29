package com.starcon.reporting;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.1.2 看板搭建的 HTTP 端点。
 *
 * <p>命名空间是 {@code /reporting/board}：13.6.1.1 用的是 {@code /reporting/custom-report}，
 * 13.1.1.1 用的是 {@code /reporting/overview}，每个兄弟模块各有各的前缀，
 * 挤在同一个前缀下会让「哪个模块的哪个端点」变得说不清。
 *
 * <p>租户<b>不</b>来自任何请求头：{@link #call} 先向 identity 确认已认证成员关系与权限，
 * 再在一个事务里绑定 {@code app.current_tenant}，由 RLS 在数据库层强制隔离。
 * 伪造的 {@code X-Tenant-Id} 头改不动这个设置。
 *
 * <p>权限码：{@code reporting.board.read}（看板 / 组件 / 视图）与
 * {@code reporting.board.write}（搭建 / 布局 / 共享 / 默认值）。
 *
 * <p>🔴 **看板需权限过滤**：不同角色打开同一个看板，看到的组件集合**必须不同**。
 * 这条规则同时写在**库层的视图**（{@code reporting_board_component_visible}）
 * 与**服务层**里 —— 不是前端藏起来的。
 *
 * <p>🔴 **本模块没有导出 / 定时推送端点**：那属 13.6.2.1（未交付）⇒
 * 能力声明里显式标「未交付」，界面上写清，不提供假按钮。
 */
@RestController
@RequestMapping("/reporting/board")
@Tag(name = "13.6.1.2 看板搭建")
public class ReportingBoardController {

  private final AccessService access;
  private final ReportingBoardService boards;

  public ReportingBoardController(AccessService access, ReportingBoardService boards) {
    this.access = access;
    this.boards = boards;
  }

  /** 统一入口：先确认身份与权限（租户**不**来自客户端头），再在事务里绑定 RLS 租户上下文。 */
  private <T> T call(String bearer, String permission, Function<AccessService.Access, T> work) {
    var actor = access.require(bearer, permission);
    return access.run(actor, permission, () -> work.apply(actor));
  }

  // ================================================================ 能力声明与目录

  @GetMapping("/capabilities")
  @Operation(
      summary = "能力声明：哪些真能跑、哪些未交付、判定规则是什么",
      description =
          "🔴 **看板的导出与定时推送属 13.6.2.1，未交付** —— 界面上写清「未交付」，"
              + "**不提供假按钮**。")
  public ReportingBoardModels.BoardCapabilities capabilities(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.board.read", actor -> boards.capabilities());
  }

  @GetMapping("/metrics")
  @Operation(
      summary = "可用的语义层指标清单（口径唯一的展示形态）",
      description =
          "指标定义是**租户级**的 ⇒ 这里只给**当前租户**登记的指标，"
              + "别的租户登记的指标不会出现在清单里。")
  public List<ReportingBoardModels.BoardMetricView> metrics(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.board.read", actor -> boards.metricCatalog(actor.tenantId()));
  }

  @GetMapping("/dimensions")
  @Operation(
      summary = "可用维度白名单（只有 5 个，与 13.6.1.1 完全一致）",
      description =
          "🔴 看板**不开放任意列名**：维度只能是这张白名单里的码，"
              + "它们对应投影事实上真实存在的列。")
  public List<ReportingBoardModels.BoardDimensionView> dimensions(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.board.read", actor -> boards.dimensionCatalog());
  }

  // ================================================================ 我的看板（工作台视图）

  @GetMapping("/board")
  @Operation(
      summary = "我的看板（工作台视图）：我能打开的看板 + 各自看得见的组件数",
      description =
          "🔴 **看得见的组件数是过滤后的真实值** —— 没有权限的组件算不进这个数，"
              + "分母是看板上的组件总数。")
  public ReportingBoardModels.BoardWorkbenchResponse workbench(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.board.read", actor -> boards.workbench(actor));
  }

  // ================================================================ 看板清单

  @GetMapping("/boards")
  @Operation(summary = "看板清单（真分页 + 真搜索 + 真筛选）")
  public ReportingBoardModels.BoardPage boards(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) String visibility,
      @RequestParam(required = false, defaultValue = "0") int page,
      @RequestParam(required = false, defaultValue = "20") int size) {
    return call(
        auth,
        "reporting.board.read",
        actor -> boards.list(actor, keyword, visibility, page, size));
  }

  @PostMapping("/boards")
  @Operation(
      summary = "新建一张看板",
      description = "🔴 创建者来自**已认证会话**（不在入参里）⇒ 客户端不能把看板「送给」别人当自己的。")
  public ReportingBoardModels.BoardView create(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReportingBoardModels.BoardSaveRequest request) {
    return call(
        auth, "reporting.board.write", actor -> boards.save(actor, null, request));
  }

  @GetMapping("/boards/{boardId}")
  @Operation(summary = "读一张看板（配置 + 已按权限过滤的组件）")
  public ReportingBoardModels.BoardView board(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId) {
    return call(
        auth, "reporting.board.read", actor -> boards.get(actor, uuid(boardId, "看板 id")));
  }

  @PutMapping("/boards/{boardId}")
  @Operation(summary = "更新一张看板（只有创建者能改）")
  public ReportingBoardModels.BoardView update(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @RequestBody ReportingBoardModels.BoardSaveRequest request) {
    return call(
        auth,
        "reporting.board.write",
        actor -> boards.save(actor, uuid(boardId, "看板 id"), request));
  }

  @DeleteMapping("/boards/{boardId}")
  @Operation(summary = "删除一张看板（连同它的组件与共享）")
  public void delete(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId) {
    call(
        auth,
        "reporting.board.write",
        actor -> {
          boards.delete(actor, uuid(boardId, "看板 id"));
          return null;
        });
  }

  // ================================================================ 组件

  @PostMapping("/boards/{boardId}/components")
  @Operation(
      summary = "加一个组件（指标 + 维度 + 图表类型 + 布局 + 筛选 + 所需权限）",
      description =
          "🔴 指标码必须**已登记**（口径唯一，DB 层有触发器）；维度只能命中白名单；"
              + "**非法布局直接报错**，不静默丢弃。")
  public ReportingBoardModels.BoardComponentView addComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @RequestBody ReportingBoardModels.BoardComponentSaveRequest request) {
    return call(
        auth,
        "reporting.board.write",
        actor -> boards.addComponent(actor, uuid(boardId, "看板 id"), request));
  }

  @PutMapping("/boards/{boardId}/components/{componentId}")
  @Operation(summary = "改一个组件（含拖拽后的位置与尺寸）")
  public ReportingBoardModels.BoardComponentView updateComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @PathVariable String componentId,
      @RequestBody ReportingBoardModels.BoardComponentSaveRequest request) {
    return call(
        auth,
        "reporting.board.write",
        actor ->
            boards.updateComponent(
                actor, uuid(boardId, "看板 id"), uuid(componentId, "组件 id"), request));
  }

  @DeleteMapping("/boards/{boardId}/components/{componentId}")
  @Operation(summary = "删一个组件")
  public void deleteComponent(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @PathVariable String componentId) {
    call(
        auth,
        "reporting.board.write",
        actor -> {
          boards.deleteComponent(actor, uuid(boardId, "看板 id"), uuid(componentId, "组件 id"));
          return null;
        });
  }

  @PutMapping("/boards/{boardId}/layout")
  @Operation(
      summary = "批量保存布局（拖拽一次 ⇒ 一次提交）",
      description =
          "🔴 **非法布局直接报错**（位置非负、尺寸为正、不超出 12 栅格宽），"
              + "**不静默丢弃** —— 悄悄挪回默认位置会让人以为存上了。")
  public List<ReportingBoardModels.BoardComponentView> saveLayout(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @RequestBody ReportingBoardModels.BoardLayoutSaveRequest request) {
    return call(
        auth,
        "reporting.board.write",
        actor -> boards.saveLayout(actor, uuid(boardId, "看板 id"), request));
  }

  // ================================================================ 共享（按角色）

  @GetMapping("/boards/{boardId}/shares")
  @Operation(summary = "一张看板的共享清单（只有创建者能看）")
  public List<ReportingBoardModels.BoardShareView> shares(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId) {
    return call(
        auth, "reporting.board.read", actor -> boards.listShares(actor, uuid(boardId, "看板 id")));
  }

  @PostMapping("/boards/{boardId}/shares")
  @Operation(
      summary = "加一条共享（按角色或按权限码）",
      description =
          "🔴 角色与权限码**二选一**（DB 层 CHECK 钉死）；"
              + "共享只决定「谁能打开这张看板」，**组件仍然按权限过滤**。")
  public ReportingBoardModels.BoardShareView addShare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @RequestBody ReportingBoardModels.BoardShareRequest request) {
    return call(
        auth,
        "reporting.board.write",
        actor -> boards.addShare(actor, uuid(boardId, "看板 id"), request));
  }

  @DeleteMapping("/boards/{boardId}/shares/{shareId}")
  @Operation(summary = "撤掉一条共享")
  public void removeShare(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @PathVariable String shareId) {
    call(
        auth,
        "reporting.board.write",
        actor -> {
          boards.removeShare(actor, uuid(boardId, "看板 id"), uuid(shareId, "共享 id"));
          return null;
        });
  }

  // ================================================================ 默认值

  @GetMapping("/boards/{boardId}/defaults")
  @Operation(summary = "读默认值（默认筛选 + 默认时间窗口）")
  public ReportingBoardModels.BoardDefaultsView defaults(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId) {
    return call(
        auth, "reporting.board.read", actor -> boards.getDefaults(actor, uuid(boardId, "看板 id")));
  }

  @PutMapping("/boards/{boardId}/defaults")
  @Operation(summary = "保存默认值（只有创建者能改）")
  public ReportingBoardModels.BoardDefaultsView saveDefaults(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @RequestBody ReportingBoardModels.BoardDefaultsRequest request) {
    return call(
        auth,
        "reporting.board.write",
        actor -> boards.saveDefaults(actor, uuid(boardId, "看板 id"), request));
  }

  // ================================================================ 看板视图

  @GetMapping("/boards/{boardId}/view")
  @Operation(
      summary = "看板视图：已按权限过滤的组件 + 每个组件的实时数据",
      description =
          "🔴 组件上的数字**不落库** —— 由 13.6.1.1 的报表引擎按登记口径现算。"
              + "🔴 **没有权限的组件不会出现在这里**（库层的规则，不是前端藏起来的）。"
              + "🔴 **涉币指标按币种分别报**；**算不出来**显示「—」并写明原因，不是 0。")
  public ReportingBoardModels.BoardViewResponse view(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String boardId,
      @RequestParam(required = false) java.time.LocalDate from,
      @RequestParam(required = false) java.time.LocalDate to) {
    return call(
        auth,
        "reporting.board.read",
        actor -> boards.view(actor, uuid(boardId, "看板 id"), from, to));
  }

  // ================================================================ 模板

  @GetMapping("/board-templates")
  @Operation(summary = "看板模板清单（按当前租户过滤）")
  public List<ReportingBoardModels.BoardTemplateView> templates(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth) {
    return call(auth, "reporting.board.read", actor -> boards.listTemplates(actor.tenantId()));
  }

  @PostMapping("/board-templates")
  @Operation(
      summary = "保存一份看板模板（含模板里的组件）",
      description = "🔴 模板里的组件**同样**只能引用已登记的指标码与白名单维度码。")
  public ReportingBoardModels.BoardTemplateView createTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @RequestBody ReportingBoardModels.BoardTemplateSaveRequest request) {
    return call(auth, "reporting.board.write", actor -> boards.createTemplate(actor, request));
  }

  @PostMapping("/board-templates/{templateId}/instantiate")
  @Operation(summary = "用模板实例化一张看板（模板里的组件整份复制过来）")
  public ReportingBoardModels.BoardView instantiateTemplate(
      @RequestHeader(value = "Authorization", defaultValue = "") String auth,
      @PathVariable String templateId,
      @RequestBody ReportingBoardModels.BoardTemplateInstantiateRequest request) {
    return call(
        auth,
        "reporting.board.write",
        actor ->
            boards.instantiateTemplate(actor, uuid(templateId, "模板 id"), request));
  }

  // ================================================================ 参数解析

  /** uuid 路径参数（格式不对 ⇒ 400，不是 404 —— 那是**请求**坏了，不是资源不存在）。 */
  private UUID uuid(String raw, String label) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "格式不正确（收到的是「" + raw + "」）");
    }
  }
}
