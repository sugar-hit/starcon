package com.starcon.reporting;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.starcon.tenancy.Ids;
import com.starcon.tenancy.TenantTransactions;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 13.6.1.2 看板搭建的**服务层**（蓝图 13.6.1.2）。
 *
 * <p>蓝图原文：
 *
 * <blockquote>
 * **职责**：搭建自定义经营看板：拖拽组件、选择指标与筛选、布局保存、按角色共享、设置默认值。<br>
 * **输入**：组件配置；看板模板；可见范围设置。<br>
 * **输出**：看板视图 + 共享配置。<br>
 * **关系**：上游 = 13.6.1.1；下游 = 1 工作台（可置为个人看板）。
 * <b>看板需权限过滤</b>，不同角色看到的组件不同，不能一个看板通吃。
 * </blockquote>
 *
 * <p>🔴 **本类不重算口径**：组件上的数字由 13.6.1.1 的
 * {@link ReportingCustomReportService#execute} 按登记口径算出来
 * （口径、单位、聚合方式、分子分母全部来自 {@code reporting_metric_definition}）。
 * 看板**不另起一套指标计算逻辑** —— 那会让同一个指标在报表与看板上给出两个数。
 *
 * <p>🔴 **用户输入只作为参数值**：SQL 片段只来自
 * {@link ReportingCustomReportCatalog}（维度 / 运算符白名单）与
 * {@link ReportingBoardCatalog}（图表类型 / 权限码形状），筛选值一律走
 * **jOOQ 绑定参数**，**绝不拼进 SQL 文本**。
 *
 * <p>🔴 **不同角色看到的组件不同**：本类按当前用户的权限 / 角色过滤可见组件，
 * **库层的视图 {@code reporting_board_component_visible} 用同一条规则再过滤一遍**
 * —— 两边同一条规则，服务层这道是保险，不是唯一防线。
 *
 * <p>🔴 **「算不出来」与「等于 0」必须分开**：分母为 0、事实不存在、指标未登记，
 * 三种情况一律返回 {@code null} / {@code NO_DATA} / {@code NOT_COMPARABLE}，
 * **不用 0 冒充**。
 *
 * <p>⚠️ **本模块不负责导出与定时推送**：那属 13.6.2.1（未交付）⇒ capabilities 里
 * 显式写清「未交付」，界面上不提供假按钮。
 */
@Service
public class ReportingBoardService {

  /** 不可用时的**逐字相同**的 404 文案（不泄露「看板存在但你没权限」）。 */
  private static final String NOT_FOUND_MESSAGE = "看板不存在或无权访问";

  /** 看板清单的单页最大条数。 */
  private static final int MAX_PAGE_SIZE = 200;

  /** 一个组件最多取多少个数据点（看板是概览，不是明细表）。 */
  private static final int COMPONENT_ROW_LIMIT = 100;

  /** 默认时间窗口的天数（含今天）。 */
  private static final int DEFAULT_WINDOW_DAYS = 30;

  // ==========================================================================
  // 内部形状
  // ==========================================================================

  /** 一行看板（从库里读出来的形状）。 */
  private record BoardRow(
      UUID tenantId,
      UUID id,
      String code,
      String name,
      String description,
      String visibility,
      UUID ownerUserId,
      List<ReportingBoardModels.BoardFilterView> defaultFilters,
      LocalDate defaultWindowFrom,
      LocalDate defaultWindowTo,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一行组件。 */
  private record ComponentRow(
      UUID tenantId,
      UUID id,
      UUID boardId,
      String metricCode,
      String dimensionCode,
      String chartType,
      String layoutJson,
      String filterJson,
      String requiredPermission,
      int sortOrder,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /** 一行共享。 */
  private record ShareRow(
      UUID id,
      UUID boardId,
      UUID roleId,
      String permissionCode,
      String scope,
      UUID createdBy,
      OffsetDateTime createdAt,
      OffsetDateTime revokedAt) {}

  /** 当前会话的「我能看什么」（从**已认证会话**解析出来的，不信任任何客户端头）。 */
  private record ActorContext(UUID userId, Set<String> permissions, Set<UUID> roleIds) {}

  /** 访问条件（SQL 片段 + 绑定参数）。 */
  private record AccessClause(String sql, List<Object> params) {}

  private final DSLContext sql;
  private final TenantTransactions tenants;
  private final ReportingCustomReportService reports;
  private final ObjectMapper json;
  private final Clock clock;

  public ReportingBoardService(
      DSLContext sql,
      TenantTransactions tenants,
      ReportingCustomReportService reports,
      ObjectMapper json,
      Clock clock) {
    this.sql = sql;
    this.tenants = tenants;
    this.reports = reports;
    this.json = json;
    this.clock = clock;
  }

  // ==========================================================================
  // 能力声明与目录
  // ==========================================================================

  /** 能力声明：**导出与定时推送属 13.6.2.1（未交付）** ⇒ 如实写清。 */
  public ReportingBoardModels.BoardCapabilities capabilities() {
    return new ReportingBoardModels.BoardCapabilities(
        true,
        true,
        true,
        ReportingBoardCatalog.UPSTREAM_MODULES,
        ReportingBoardCatalog.NOT_DELIVERED,
        ReportingBoardCatalog.RULES);
  }

  /** 可用的语义层指标清单（**按当前租户过滤**，口径唯一的展示形态）。 */
  public List<ReportingBoardModels.BoardMetricView> metricCatalog(UUID tenantId) {
    var out = new ArrayList<ReportingBoardModels.BoardMetricView>();
    for (var metric : reports.metricCatalog(tenantId)) {
      out.add(
          new ReportingBoardModels.BoardMetricView(
              metric.metricCode(),
              metric.displayName(),
              metric.unit(),
              metric.aggregation(),
              metric.numeratorMetricCode(),
              metric.denominatorMetricCode(),
              metric.currencyAware(),
              metric.description(),
              metric.ownerModule()));
    }
    return out;
  }

  /** 可用维度白名单（**与 13.6.1.1 完全一致的 5 个**）。 */
  public List<ReportingBoardModels.BoardDimensionView> dimensionCatalog() {
    var out = new ArrayList<ReportingBoardModels.BoardDimensionView>();
    for (var dimension : reports.dimensionCatalog()) {
      out.add(
          new ReportingBoardModels.BoardDimensionView(
              dimension.dimensionCode(),
              dimension.label(),
              dimension.valueKind(),
              dimension.note()));
    }
    return out;
  }

  // ==========================================================================
  // 看板清单
  // ==========================================================================

  /** 看板清单（真分页 + 真搜索 + 真筛选；**只列当前用户有权打开的**）。 */
  public ReportingBoardModels.BoardPage list(
      AccessService.Access actor, String keyword, String visibility, int page, int size) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var effectiveSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
          var effectivePage = Math.max(page, 0);
          var where = new ArrayList<String>();
          var params = new ArrayList<Object>();
          params.add(actor.tenantId());
          var access = accessClause(context);
          where.add(access.sql());
          params.addAll(access.params());
          if (keyword != null && !keyword.isBlank()) {
            where.add("(b.display_name ilike ? or b.board_code ilike ?)");
            var like = "%" + keyword.trim() + "%";
            params.add(like);
            params.add(like);
          }
          if (visibility != null && !visibility.isBlank()) {
            where.add("b.visibility = ?");
            params.add(normalizeVisibility(visibility));
          }
          var clause = " and " + String.join(" and ", where);
          var total =
              sql.fetchOne(
                  "select count(*) from reporting_board b where b.tenant_id = ?" + clause,
                  params.toArray());
          var rows =
              sql.fetch(
                  "select b.id, b.board_code, b.display_name, b.description, b.visibility,"
                      + " b.owner_user_id, b.updated_at,"
                      + " (select count(*) from reporting_board_component c"
                      + "   where c.tenant_id = b.tenant_id and c.board_id = b.id),"
                      + " (select count(*) from reporting_board_component_visible c"
                      + "   where c.tenant_id = b.tenant_id and c.board_id = b.id),"
                      + " (select count(*) from reporting_board_share s"
                      + "   where s.tenant_id = b.tenant_id and s.board_id = b.id and s.revoked_at is null)"
                      + " from reporting_board b"
                      + " where b.tenant_id = ?"
                      + clause
                      + " order by b.updated_at desc, b.board_code"
                      + " limit ? offset ?",
                  append(params, effectiveSize, effectivePage * effectiveSize).toArray());
          var items = new ArrayList<ReportingBoardModels.BoardSummary>();
          for (var row : rows) {
            items.add(
                new ReportingBoardModels.BoardSummary(
                    row.get(0, UUID.class).toString(),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, UUID.class).toString(),
                    row.get(5, UUID.class).equals(context.userId()),
                    row.get(7, Integer.class),
                    row.get(8, Integer.class),
                    row.get(9, Integer.class),
                    row.get(6, OffsetDateTime.class) == null
                        ? ""
                        : row.get(6, OffsetDateTime.class).toString()));
          }
          return new ReportingBoardModels.BoardPage(
              items,
              total == null ? 0L : total.get(0, Long.class),
              effectivePage,
              effectiveSize,
              keyword == null ? "" : keyword.trim(),
              visibility == null ? "" : visibility.trim());
        });
  }

  /** 我的看板（工作台视图）：**我能打开**的看板 + 各自看得见的组件数。 */
  public ReportingBoardModels.BoardWorkbenchResponse workbench(AccessService.Access actor) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var access = accessClause(context);
          var rows =
              sql.fetch(
                  "select b.id, b.board_code, b.display_name, b.description, b.visibility,"
                      + " b.owner_user_id, b.updated_at,"
                      + " (select count(*) from reporting_board_component c"
                      + "   where c.tenant_id = b.tenant_id and c.board_id = b.id),"
                      + " (select count(*) from reporting_board_component_visible c"
                      + "   where c.tenant_id = b.tenant_id and c.board_id = b.id)"
                      + " from reporting_board b"
                      + " where b.tenant_id = ? and "
                      + access.sql()
                      + " order by b.updated_at desc, b.board_code",
                  append(new ArrayList<>(List.of(actor.tenantId())), access.params().toArray()).toArray());
          var items = new ArrayList<ReportingBoardModels.BoardWorkbenchItem>();
          for (var row : rows) {
            items.add(
                new ReportingBoardModels.BoardWorkbenchItem(
                    row.get(0, UUID.class).toString(),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    row.get(4, String.class),
                    row.get(5, UUID.class).equals(context.userId()),
                    row.get(8, Integer.class),
                    row.get(7, Integer.class),
                    row.get(6, OffsetDateTime.class) == null
                        ? ""
                        : row.get(6, OffsetDateTime.class).toString()));
          }
          return new ReportingBoardModels.BoardWorkbenchResponse(
              items, dataUpdatedAt(actor.tenantId()), DATA_UPDATED_NOTE);
        });
  }

  // ==========================================================================
  // 读取 / 保存 / 删除
  // ==========================================================================

  /** 读一张看板（配置 + **已按权限过滤**的组件）。 */
  public ReportingBoardModels.BoardView get(AccessService.Access actor, UUID boardId) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireAccess(board, context);
          return toView(actor.tenantId(), board, context);
        });
  }

  /** 保存一张看板（{@code boardId} 为空 ⇒ 新建）。 */
  public ReportingBoardModels.BoardView save(
      AccessService.Access actor, UUID boardId, ReportingBoardModels.BoardSaveRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var code = requireText(request.boardCode(), "看板编码");
          var name = requireText(request.displayName(), "看板名称");
          var description = request.description() == null ? "" : request.description().trim();
          if (description.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "看板说明最长 500 字");
          }
          var visibility = normalizeVisibility(request.visibility());
          var defaultFilters = parseFilters(request.defaultFilters());
          if (request.defaultWindowFrom() != null && request.defaultWindowTo() != null
              && request.defaultWindowFrom().isAfter(request.defaultWindowTo())) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "默认窗口的起始日期不能晚于结束日期");
          }
          var defaultFiltersJson = writeJson(filterViews(defaultFilters));

          if (boardId == null) {
            var id = Ids.next();
            try {
              sql.execute(
                  "insert into reporting_board"
                      + " (id,tenant_id,board_code,display_name,description,owner_user_id,visibility,"
                      + " default_filters,default_window_from,default_window_to,created_at,updated_at)"
                      + " values (?,?,?,?,?,?,?,?::jsonb,?,?,now(),now())",
                  id,
                  actor.tenantId(),
                  code,
                  name,
                  description,
                  context.userId(),
                  visibility,
                  defaultFiltersJson,
                  request.defaultWindowFrom(),
                  request.defaultWindowTo());
            } catch (DataAccessException e) {
              throw duplicate("看板编码 " + code + " 在本租户里已经存在了");
            }
            return toView(actor.tenantId(), loadBoard(actor.tenantId(), id), context);
          }

          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          if (!board.code().equals(code)) {
            var clash =
                sql.fetchOne(
                    "select id from reporting_board where tenant_id = ? and board_code = ? and id <> ?",
                    actor.tenantId(),
                    code,
                    boardId);
            if (clash != null) {
              throw duplicate("看板编码 " + code + " 在本租户里已经存在了");
            }
          }
          try {
            sql.execute(
                "update reporting_board set board_code = ?, display_name = ?, description = ?,"
                    + " visibility = ?, default_filters = ?::jsonb, default_window_from = ?,"
                    + " default_window_to = ?, updated_at = now()"
                    + " where tenant_id = ? and id = ?",
                code,
                name,
                description,
                visibility,
                defaultFiltersJson,
                request.defaultWindowFrom(),
                request.defaultWindowTo(),
                actor.tenantId(),
                boardId);
          } catch (DataAccessException e) {
            throw duplicate("看板编码 " + code + " 在本租户里已经存在了");
          }
          return toView(actor.tenantId(), loadBoard(actor.tenantId(), boardId), context);
        });
  }

  /** 删除一张看板（连同它的组件 / 共享）。 */
  public void delete(AccessService.Access actor, UUID boardId) {
    var context = actorContext(actor);
    tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          sql.execute(
              "delete from reporting_board_component where tenant_id = ? and board_id = ?",
              actor.tenantId(),
              boardId);
          sql.execute(
              "delete from reporting_board_share where tenant_id = ? and board_id = ?",
              actor.tenantId(),
              boardId);
          sql.execute(
              "delete from reporting_board where tenant_id = ? and id = ?", actor.tenantId(), boardId);
          return null;
        });
  }

  // ==========================================================================
  // 组件
  // ==========================================================================

  /** 加一个组件（**指标码必须已登记**，DB 层有触发器钉死）。 */
  public ReportingBoardModels.BoardComponentView addComponent(
      AccessService.Access actor,
      UUID boardId,
      ReportingBoardModels.BoardComponentSaveRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var metricCode = normalizeMetricCode(request.metricCode());
          var dimensionCode = normalizeDimensionCode(request.dimensionCode());
          var chartType = normalizeChartType(request.chartType());
          var layout = normalizeLayout(request.layout());
          var filters = parseFilters(request.filter());
          var requiredPermission = normalizePermission(request.requiredPermission());

          // 🔴 口径唯一：指标码必须**已在本租户登记**（DB 层还有一道触发器）。
          requireRegisteredMetric(actor.tenantId(), metricCode);

          var count =
              sql.fetchOne(
                      "select count(*) from reporting_board_component where tenant_id = ? and board_id = ?",
                      actor.tenantId(),
                      boardId)
                  .get(0, Integer.class);
          if (count >= ReportingBoardCatalog.MAX_COMPONENTS_PER_BOARD) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "一张看板最多摆 " + ReportingBoardCatalog.MAX_COMPONENTS_PER_BOARD + " 个组件。");
          }
          var sortOrder =
              request.sortOrder() != null
                  ? request.sortOrder()
                  : nextSortOrder(actor.tenantId(), boardId);
          var id = Ids.next();
          try {
            sql.execute(
                "insert into reporting_board_component"
                    + " (id,tenant_id,board_id,metric_code,dimension_code,chart_type,layout,filter,"
                    + " required_permission,sort_order,created_at,updated_at)"
                    + " values (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,now(),now())",
                id,
                actor.tenantId(),
                boardId,
                metricCode,
                dimensionCode,
                chartType,
                writeJson(layout),
                writeJson(filterViews(filters)),
                requiredPermission,
                sortOrder);
          } catch (DataAccessException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "这个看板里已经有「" + metricCode + " × " + dimensionCode + "」这个组件了。");
          }
          return toComponentView(loadComponent(actor.tenantId(), boardId, id));
        });
  }

  /** 改一个组件（**含拖拽后的位置与尺寸**）。 */
  public ReportingBoardModels.BoardComponentView updateComponent(
      AccessService.Access actor,
      UUID boardId,
      UUID componentId,
      ReportingBoardModels.BoardComponentSaveRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var component = loadComponent(actor.tenantId(), boardId, componentId);
          var metricCode = normalizeMetricCode(request.metricCode());
          var dimensionCode = normalizeDimensionCode(request.dimensionCode());
          var chartType = normalizeChartType(request.chartType());
          var layout = normalizeLayout(request.layout());
          var filters = parseFilters(request.filter());
          var requiredPermission = normalizePermission(request.requiredPermission());
          requireRegisteredMetric(actor.tenantId(), metricCode);
          var sortOrder = request.sortOrder() != null ? request.sortOrder() : component.sortOrder();
          try {
            sql.execute(
                "update reporting_board_component set metric_code = ?, dimension_code = ?,"
                    + " chart_type = ?, layout = ?::jsonb, filter = ?::jsonb, required_permission = ?,"
                    + " sort_order = ?, updated_at = now()"
                    + " where tenant_id = ? and id = ?",
                metricCode,
                dimensionCode,
                chartType,
                writeJson(layout),
                writeJson(filterViews(filters)),
                requiredPermission,
                sortOrder,
                actor.tenantId(),
                componentId);
          } catch (DataAccessException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "这个看板里已经有「" + metricCode + " × " + dimensionCode + "」这个组件了。");
          }
          sql.execute(
              "update reporting_board set updated_at = now() where tenant_id = ? and id = ?",
              actor.tenantId(),
              boardId);
          return toComponentView(loadComponent(actor.tenantId(), boardId, componentId));
        });
  }

  /** 删一个组件。 */
  public void deleteComponent(AccessService.Access actor, UUID boardId, UUID componentId) {
    var context = actorContext(actor);
    tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          loadComponent(actor.tenantId(), boardId, componentId);
          sql.execute(
              "delete from reporting_board_component where tenant_id = ? and id = ?",
              actor.tenantId(),
              componentId);
          sql.execute(
              "update reporting_board set updated_at = now() where tenant_id = ? and id = ?",
              actor.tenantId(),
              boardId);
          return null;
        });
  }

  /**
   * **批量保存布局**（拖拽一次 ⇒ 一次提交）。
   *
   * <p>🔴 **非法布局直接报错**（400），**不静默丢弃**：悄悄挪回默认位置会让人以为存上了。
   */
  public List<ReportingBoardModels.BoardComponentView> saveLayout(
      AccessService.Access actor, UUID boardId, ReportingBoardModels.BoardLayoutSaveRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          if (request == null || request.components() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          if (request.components().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "布局里一个组件都没有");
          }
          // 先把**全部**布局校验过，再动数据库（要么全存，要么全不存）。
          var items = new ArrayList<ReportingBoardModels.BoardLayoutItem>();
          for (var item : request.components()) {
            if (item == null || item.componentId() == null || item.componentId().isBlank()) {
              throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "布局里有组件没有 id");
            }
            var componentId = uuid(item.componentId(), "组件 id");
            // 组件必须属于这张看板（否则就是拿别人的看板 id 改布局）。
            loadComponent(actor.tenantId(), boardId, componentId);
            var layout = normalizeLayout(new ReportingBoardModels.BoardLayoutView(item.x(), item.y(), item.w(), item.h()));
            items.add(
                new ReportingBoardModels.BoardLayoutItem(
                    componentId.toString(),
                    layout.x(),
                    layout.y(),
                    layout.w(),
                    layout.h(),
                    item.sortOrder()));
          }
          var order = 0;
          for (var item : items) {
            sql.execute(
                "update reporting_board_component set layout = ?::jsonb, sort_order = ?,"
                    + " updated_at = now() where tenant_id = ? and id = ?",
                writeJson(
                    new ReportingBoardModels.BoardLayoutView(item.x(), item.y(), item.w(), item.h())),
                item.sortOrder() != null ? item.sortOrder() : order++,
                actor.tenantId(),
                uuid(item.componentId(), "组件 id"));
          }
          sql.execute(
              "update reporting_board set updated_at = now() where tenant_id = ? and id = ?",
              actor.tenantId(),
              boardId);
          return componentViews(actor.tenantId(), boardId, context);
        });
  }

  // ==========================================================================
  // 共享（按角色）
  // ==========================================================================

  /** 一张看板的共享清单（**只有创建者能看**）。 */
  public List<ReportingBoardModels.BoardShareView> listShares(
      AccessService.Access actor, UUID boardId) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          var out = new ArrayList<ReportingBoardModels.BoardShareView>();
          for (var row :
              sql.fetch(
                  "select id, board_id, role_id, permission_code, scope, created_by, created_at,"
                      + " revoked_at from reporting_board_share"
                      + " where tenant_id = ? and board_id = ? order by created_at desc, id",
                  actor.tenantId(),
                  boardId)) {
            out.add(toShareView(row));
          }
          return out;
        });
  }

  /** 加一条共享（按角色 / 权限码，**二选一**）。 */
  public ReportingBoardModels.BoardShareView addShare(
      AccessService.Access actor, UUID boardId, ReportingBoardModels.BoardShareRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var roleId =
              request.roleId() == null || request.roleId().isBlank()
                  ? null
                  : uuid(request.roleId(), "角色 id");
          var permissionCode =
              request.permissionCode() == null ? "" : request.permissionCode().trim();
          if (roleId == null && permissionCode.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "共享要么圈一个角色（roleId），要么圈一个权限码（permissionCode）");
          }
          if (roleId != null && !permissionCode.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "角色（roleId）与权限码（permissionCode）**二选一**，不能同时给");
          }
          if (!permissionCode.isEmpty() && !ReportingBoardCatalog.validPermissionCode(permissionCode)) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "权限码形状不对（收到的是「" + permissionCode + "」）");
          }
          var scope = normalizeScope(request.scope());
          var id = Ids.next();
          try {
            sql.execute(
                "insert into reporting_board_share"
                    + " (id,tenant_id,board_id,role_id,permission_code,scope,created_by,created_at)"
                    + " values (?,?,?,?,?,?,?,now())",
                id,
                actor.tenantId(),
                boardId,
                roleId,
                permissionCode,
                scope,
                context.userId());
          } catch (DataAccessException e) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "这张看板上已经有同一组角色 / 权限码的共享了");
          }
          var row =
              sql.fetchOne(
                  "select id, board_id, role_id, permission_code, scope, created_by, created_at,"
                      + " revoked_at from reporting_board_share where tenant_id = ? and id = ?",
                  actor.tenantId(),
                  id);
          return toShareView(row);
        });
  }

  /** 撤掉一条共享（**行留着当凭据**，只是标了撤销时刻）。 */
  public void removeShare(AccessService.Access actor, UUID boardId, UUID shareId) {
    var context = actorContext(actor);
    tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          var row =
              sql.fetchOne(
                  "select id from reporting_board_share where tenant_id = ? and board_id = ? and id = ?",
                  actor.tenantId(),
                  boardId,
                  shareId);
          if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "共享不存在");
          }
          sql.execute(
              "update reporting_board_share set revoked_at = now() where tenant_id = ? and id = ?",
              actor.tenantId(),
              shareId);
          return null;
        });
  }

  // ==========================================================================
  // 默认值
  // ==========================================================================

  /** 读默认值（默认筛选 + 默认时间窗口）。 */
  public ReportingBoardModels.BoardDefaultsView getDefaults(
      AccessService.Access actor, UUID boardId) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireAccess(board, context);
          return new ReportingBoardModels.BoardDefaultsView(
              board.id().toString(),
              board.defaultFilters(),
              board.defaultWindowFrom(),
              board.defaultWindowTo(),
              board.ownerUserId().equals(context.userId()));
        });
  }

  /** 保存默认值（**只有创建者能改**）。 */
  public ReportingBoardModels.BoardDefaultsView saveDefaults(
      AccessService.Access actor, UUID boardId, ReportingBoardModels.BoardDefaultsRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireOwner(board, context);
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var filters = parseFilters(request.defaultFilters());
          if (request.defaultWindowFrom() != null && request.defaultWindowTo() != null
              && request.defaultWindowFrom().isAfter(request.defaultWindowTo())) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "默认窗口的起始日期不能晚于结束日期");
          }
          sql.execute(
              "update reporting_board set default_filters = ?::jsonb, default_window_from = ?,"
                  + " default_window_to = ?, updated_at = now() where tenant_id = ? and id = ?",
              writeJson(filterViews(filters)),
              request.defaultWindowFrom(),
              request.defaultWindowTo(),
              actor.tenantId(),
              boardId);
          var updated = loadBoard(actor.tenantId(), boardId);
          return new ReportingBoardModels.BoardDefaultsView(
              updated.id().toString(),
              updated.defaultFilters(),
              updated.defaultWindowFrom(),
              updated.defaultWindowTo(),
              true);
        });
  }

  // ==========================================================================
  // 模板
  // ==========================================================================

  /** 模板清单（**按当前租户过滤**）。 */
  public List<ReportingBoardModels.BoardTemplateView> listTemplates(UUID tenantId) {
    return tenants.run(
        tenantId,
        () -> {
          var out = new ArrayList<ReportingBoardModels.BoardTemplateView>();
          for (var row :
              sql.fetch(
                  "select id, template_code, display_name, description, created_by, created_at, updated_at"
                      + " from reporting_board_template where tenant_id = ?"
                      + " order by updated_at desc, template_code",
                  tenantId)) {
            out.add(
                new ReportingBoardModels.BoardTemplateView(
                    row.get(0, UUID.class).toString(),
                    row.get(1, String.class),
                    row.get(2, String.class),
                    row.get(3, String.class),
                    templateComponentViews(tenantId, row.get(0, UUID.class)),
                    row.get(4, UUID.class).toString(),
                    offset(row.get(5, OffsetDateTime.class)),
                    offset(row.get(6, OffsetDateTime.class))));
          }
          return out;
        });
  }

  /** 保存一份模板（含模板里的组件）。 */
  public ReportingBoardModels.BoardTemplateView createTemplate(
      AccessService.Access actor, ReportingBoardModels.BoardTemplateSaveRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var code = requireText(request.templateCode(), "模板编码");
          var name = requireText(request.displayName(), "模板名称");
          var description = request.description() == null ? "" : request.description().trim();
          if (description.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板说明最长 500 字");
          }
          List<ReportingBoardModels.BoardComponentSaveRequest> components =
              request.components() == null
                  ? List.<ReportingBoardModels.BoardComponentSaveRequest>of()
                  : request.components();
          if (components.size() > ReportingBoardCatalog.MAX_COMPONENTS_PER_TEMPLATE) {
            throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "一份模板最多带 " + ReportingBoardCatalog.MAX_COMPONENTS_PER_TEMPLATE + " 个组件。");
          }
          var id = Ids.next();
          try {
            sql.execute(
                "insert into reporting_board_template"
                    + " (id,tenant_id,template_code,display_name,description,created_by,created_at,updated_at)"
                    + " values (?,?,?,?,?,?,now(),now())",
                id,
                actor.tenantId(),
                code,
                name,
                description,
                context.userId());
          } catch (DataAccessException e) {
            throw duplicate("模板编码 " + code + " 在本租户里已经存在了");
          }
          var order = 0;
          for (var component : components) {
            insertTemplateComponent(actor.tenantId(), id, component, order++);
          }
          return loadTemplate(actor.tenantId(), id);
        });
  }

  /** 用模板实例化一张看板（**模板里的组件整份复制过来**）。 */
  public ReportingBoardModels.BoardView instantiateTemplate(
      AccessService.Access actor, UUID templateId, ReportingBoardModels.BoardTemplateInstantiateRequest request) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
          }
          var code = requireText(request.boardCode(), "看板编码");
          var name = requireText(request.displayName(), "看板名称");
          var description = request.description() == null ? "" : request.description().trim();
          var visibility = normalizeVisibility(request.visibility());
          var template =
              sql.fetchOne(
                      "select id from reporting_board_template where tenant_id = ? and id = ?",
                      actor.tenantId(),
                      templateId)
                  .get(0, UUID.class);
          var boardId = Ids.next();
          try {
            sql.execute(
                "insert into reporting_board"
                    + " (id,tenant_id,board_code,display_name,description,owner_user_id,visibility,"
                    + " default_filters,default_window_from,default_window_to,created_at,updated_at)"
                    + " values (?,?,?,?,?,?,?,'[]'::jsonb,null,null,now(),now())",
                boardId,
                actor.tenantId(),
                code,
                name,
                description,
                context.userId(),
                visibility);
          } catch (DataAccessException e) {
            throw duplicate("看板编码 " + code + " 在本租户里已经存在了");
          }
          var templateComponents =
              sql.fetch(
                  "select metric_code, dimension_code, chart_type, layout::text, filter::text,"
                      + " required_permission, sort_order from reporting_board_template_component"
                      + " where tenant_id = ? and template_id = ? order by sort_order, id",
                  actor.tenantId(),
                  template);
          for (var row : templateComponents) {
            sql.execute(
                "insert into reporting_board_component"
                    + " (id,tenant_id,board_id,metric_code,dimension_code,chart_type,layout,filter,"
                    + " required_permission,sort_order,created_at,updated_at)"
                    + " values (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,now(),now())",
                Ids.next(),
                actor.tenantId(),
                boardId,
                row.get(0, String.class),
                row.get(1, String.class),
                row.get(2, String.class),
                row.get(3, String.class),
                row.get(4, String.class),
                row.get(5, String.class),
                row.get(6, Integer.class));
          }
          return toView(actor.tenantId(), loadBoard(actor.tenantId(), boardId), context);
        });
  }

  // ==========================================================================
  // 看板视图（输出）
  // ==========================================================================

  /**
   * 渲染一张看板：**已按权限过滤**的组件 + 每个组件的实时数据。
   *
   * <p>🔴 组件上的数字**不落库**：由 13.6.1.1 的查询引擎按登记口径现算。
   */
  public ReportingBoardModels.BoardViewResponse view(
      AccessService.Access actor, UUID boardId, LocalDate from, LocalDate to) {
    var context = actorContext(actor);
    return tenants.run(
        actor.tenantId(),
        () -> {
          var board = loadBoard(actor.tenantId(), boardId);
          requireAccess(board, context);
          var window = resolveWindow(board, from, to);
          var visible = visibleComponents(actor.tenantId(), boardId);
          var total = totalComponents(actor.tenantId(), boardId);
          var catalog = metricCatalogMap(actor.tenantId());
          var defaultFilters = parseFilterSpecs(writeJson(board.defaultFilters()));

          var data = new ArrayList<ReportingBoardModels.BoardComponentData>();
          for (var component : visible) {
            data.add(renderComponent(actor.tenantId(), board, component, window, catalog, defaultFilters));
          }
          var caliberNotes = new ArrayList<String>();
          caliberNotes.add(
              "组件上的数字由 13.6.1.1 的报表引擎按登记口径**实时算出来**（不落库）—— "
                  + "口径改一处，看板与报表自动跟着改。");
          caliberNotes.add(
              "🔴 **涉币指标按币种分别报**（绝不跨币种相加）；与货币无关的量（笔数 / 件数）"
                  + "给一个跨币种合计，它的币种显示为空串。");
          caliberNotes.add(
              "🔴 **组件按角色权限过滤**：你没有权限的组件不会出现在这里（"
                  + "这是库层的规则，不是前端藏起来的）。");
          var anyData = data.stream().anyMatch(item -> "AVAILABLE".equals(item.status()));
          return new ReportingBoardModels.BoardViewResponse(
              board.id().toString(),
              board.code(),
              board.name(),
              board.description(),
              board.visibility(),
              window.from(),
              window.to(),
              data,
              visible.size(),
              total,
              dataUpdatedAt(actor.tenantId()),
              DATA_UPDATED_NOTE,
              caliberNotes,
              anyData ? "AVAILABLE" : "NO_DATA",
              anyData
                  ? ""
                  : "这张看板上没有任何组件取到数据（不是 0）：上游可能还没有投影，或窗口不对。");
        });
  }

  // ==========================================================================
  // 私有：加载与判定
  // ==========================================================================

  /** 读一行看板（RLS 挡住别的租户）。 */
  private BoardRow loadBoard(UUID tenantId, UUID boardId) {
    var row =
        sql.fetchOne(
            "select id, tenant_id, board_code, display_name, description, visibility, owner_user_id,"
                + " default_filters::text, default_window_from, default_window_to, created_at, updated_at"
                + " from reporting_board where tenant_id = ? and id = ?",
            tenantId,
            boardId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
    return new BoardRow(
        row.get(1, UUID.class),
        row.get(0, UUID.class),
        row.get(2, String.class),
        row.get(3, String.class),
        row.get(4, String.class),
        row.get(5, String.class),
        row.get(6, UUID.class),
        parseFilterViews(row.get(7, String.class)),
        row.get(8, LocalDate.class),
        row.get(9, LocalDate.class),
        row.get(10, OffsetDateTime.class),
        row.get(11, OffsetDateTime.class));
  }

  /** 读一行组件（**必须属于这张看板**）。 */
  private ComponentRow loadComponent(UUID tenantId, UUID boardId, UUID componentId) {
    var row =
        sql.fetchOne(
            "select id, tenant_id, board_id, metric_code, dimension_code, chart_type, layout::text,"
                + " filter::text, required_permission, sort_order, created_at, updated_at"
                + " from reporting_board_component where tenant_id = ? and board_id = ? and id = ?",
            tenantId,
            boardId,
            componentId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "组件不存在");
    }
    return toComponentRow(row);
  }

  /** 🔴 **可见的组件**（库层视图已经按权限过滤过了；这里再按同一条规则收一道）。 */
  private List<ComponentRow> visibleComponents(UUID tenantId, UUID boardId) {
    var rows =
        sql.fetch(
            "select id, tenant_id, board_id, metric_code, dimension_code, chart_type, layout::text,"
                + " filter::text, required_permission, sort_order, created_at, updated_at"
                + " from reporting_board_component_visible"
                + " where tenant_id = ? and board_id = ? order by sort_order, id",
            tenantId,
            boardId);
    var out = new ArrayList<ComponentRow>();
    for (var row : rows) {
      out.add(toComponentRow(row));
    }
    return out;
  }

  /** 一行组件 → 内部形状。 */
  private ComponentRow toComponentRow(org.jooq.Record row) {
    return new ComponentRow(
        row.get(1, UUID.class),
        row.get(0, UUID.class),
        row.get(2, UUID.class),
        row.get(3, String.class),
        row.get(4, String.class),
        row.get(5, String.class),
        row.get(6, String.class),
        row.get(7, String.class),
        row.get(8, String.class),
        row.get(9, Integer.class),
        row.get(10, OffsetDateTime.class),
        row.get(11, OffsetDateTime.class));
  }

  /** 看板上的组件总数（租户内的真实总数 —— 分母不因为权限过滤而变小）。 */
  private int totalComponents(UUID tenantId, UUID boardId) {
    return sql.fetchOne(
            "select count(*) from reporting_board_component where tenant_id = ? and board_id = ?",
            tenantId,
            boardId)
        .get(0, Integer.class);
  }

  /** 访问条件：创建者 / TEAM 可见 / 命中共享。 */
  private AccessClause accessClause(ActorContext actor) {
    var params = new ArrayList<Object>();
    params.add(actor.userId());
    var match = shareMatchClause(actor);
    params.addAll(match.params());
    var sqlText =
        "(b.owner_user_id = ? or b.visibility = 'TEAM' or exists ("
            + "select 1 from reporting_board_share s"
            + " where s.tenant_id = b.tenant_id and s.board_id = b.id and s.revoked_at is null"
            + " and ("
            + match.sql()
            + ")))";
    return new AccessClause(sqlText, params);
  }

  /** 共享命中的 SQL 片段 + 绑定参数（**参数顺序与片段里的 ? 一一对应**）。 */
  private AccessClause shareMatchClause(ActorContext actor) {
    var params = new ArrayList<Object>();
    var clauses = new ArrayList<String>();
    clauses.add("s.scope = 'TENANT'");
    if (!actor.roleIds().isEmpty()) {
      clauses.add("s.role_id in (" + placeholders(actor.roleIds().size()) + ")");
      params.addAll(actor.roleIds());
    }
    if (!actor.permissions().isEmpty()) {
      clauses.add(
          "(s.permission_code <> '' and s.permission_code in ("
              + placeholders(actor.permissions().size())
              + "))");
      params.addAll(actor.permissions());
    }
    return new AccessClause(String.join(" or ", clauses), params);
  }

  /** 🔴 访问闸门：打不开 ⇒ 与「不存在」**逐字相同**的 404。 */
  private void requireAccess(BoardRow board, ActorContext actor) {
    if (board.ownerUserId().equals(actor.userId())) return;
    if ("TEAM".equals(board.visibility())) return;
    var match = shareMatchClause(actor);
    var hits =
        sql.fetchOne(
                "select count(*) from reporting_board_share s"
                    + " where s.tenant_id = ? and s.board_id = ? and s.revoked_at is null and ("
                    + match.sql()
                    + ")",
                append(new ArrayList<>(List.of(board.tenantId(), board.id())), match.params().toArray())
                    .toArray())
            .get(0, Integer.class);
    if (hits <= 0) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
  }

  /** 只有创建者能改（分享 / 布局 / 默认值这类**管理动作**）。 */
  private void requireOwner(BoardRow board, ActorContext actor) {
    if (!board.ownerUserId().equals(actor.userId())) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, NOT_FOUND_MESSAGE);
    }
  }

  /** 看板 → 出参。 */
  private ReportingBoardModels.BoardView toView(
      UUID tenantId, BoardRow board, ActorContext actor) {
    var components = componentViews(tenantId, board.id(), actor);
    return new ReportingBoardModels.BoardView(
        board.id().toString(),
        board.code(),
        board.name(),
        board.description(),
        board.visibility(),
        board.ownerUserId().toString(),
        board.ownerUserId().equals(actor.userId()),
        board.defaultFilters(),
        board.defaultWindowFrom(),
        board.defaultWindowTo(),
        components,
        sql.fetchOne(
                "select count(*) from reporting_board_share where tenant_id = ? and board_id = ?"
                    + " and revoked_at is null",
                tenantId,
                board.id())
            .get(0, Integer.class),
        offset(board.createdAt()),
        offset(board.updatedAt()));
  }

  /** 一张看板的可见组件 → 出参。 */
  private List<ReportingBoardModels.BoardComponentView> componentViews(
      UUID tenantId, UUID boardId, ActorContext actor) {
    var out = new ArrayList<ReportingBoardModels.BoardComponentView>();
    for (var component : visibleComponents(tenantId, boardId)) {
      out.add(toComponentView(component));
    }
    return out;
  }

  /** 组件 → 出参（指标名与单位来自口径表）。 */
  private ReportingBoardModels.BoardComponentView toComponentView(ComponentRow component) {
    var metric =
        sql.fetchOne(
            "select display_name, unit, currency_aware from reporting_metric_definition"
                + " where tenant_id = ? and metric_code = ?",
            component.tenantId(),
            component.metricCode());
    return new ReportingBoardModels.BoardComponentView(
        component.id().toString(),
        component.boardId().toString(),
        component.metricCode(),
        metric == null ? component.metricCode() : metric.get(0, String.class),
        metric == null ? "" : metric.get(1, String.class),
        metric != null && metric.get(2, Boolean.class),
        component.dimensionCode(),
        component.chartType(),
        readLayout(component.layoutJson()),
        parseFilterViews(component.filterJson()),
        component.requiredPermission(),
        component.sortOrder(),
        offset(component.createdAt()),
        offset(component.updatedAt()));
  }

  /** 模板 → 出参。 */
  private ReportingBoardModels.BoardTemplateView loadTemplate(UUID tenantId, UUID templateId) {
    var row =
        sql.fetchOne(
            "select id, template_code, display_name, description, created_by, created_at, updated_at"
                + " from reporting_board_template where tenant_id = ? and id = ?",
            tenantId,
            templateId);
    if (row == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在");
    }
    return new ReportingBoardModels.BoardTemplateView(
        row.get(0, UUID.class).toString(),
        row.get(1, String.class),
        row.get(2, String.class),
        row.get(3, String.class),
        templateComponentViews(tenantId, templateId),
        row.get(4, UUID.class).toString(),
        offset(row.get(5, OffsetDateTime.class)),
        offset(row.get(6, OffsetDateTime.class)));
  }

  /** 模板里的组件 → 出参。 */
  private List<ReportingBoardModels.BoardComponentView> templateComponentViews(
      UUID tenantId, UUID templateId) {
    var out = new ArrayList<ReportingBoardModels.BoardComponentView>();
    for (var row :
        sql.fetch(
            "select id, metric_code, dimension_code, chart_type, layout::text, filter::text,"
                + " required_permission, sort_order, created_at, updated_at"
                + " from reporting_board_template_component where tenant_id = ? and template_id = ?"
                + " order by sort_order, id",
            tenantId,
            templateId)) {
      out.add(
          new ReportingBoardModels.BoardComponentView(
              row.get(0, UUID.class).toString(),
              templateId.toString(),
              row.get(1, String.class),
              row.get(1, String.class),
              "",
              false,
              row.get(2, String.class),
              row.get(3, String.class),
              readLayout(row.get(4, String.class)),
              parseFilterViews(row.get(5, String.class)),
              row.get(6, String.class),
              row.get(7, Integer.class),
              offset(row.get(8, OffsetDateTime.class)),
              offset(row.get(9, OffsetDateTime.class))));
    }
    return out;
  }

  /** 组件 → 出参（带口径表里的指标名 / 单位）。 */
  private void insertTemplateComponent(
      UUID tenantId, UUID templateId, ReportingBoardModels.BoardComponentSaveRequest request, int order) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板里有组件是空的");
    }
    var metricCode = normalizeMetricCode(request.metricCode());
    var dimensionCode = normalizeDimensionCode(request.dimensionCode());
    var chartType = normalizeChartType(request.chartType());
    var layout = normalizeLayout(request.layout());
    var filters = parseFilters(request.filter());
    var requiredPermission = normalizePermission(request.requiredPermission());
    requireRegisteredMetric(tenantId, metricCode);
    try {
      sql.execute(
          "insert into reporting_board_template_component"
              + " (id,tenant_id,template_id,metric_code,dimension_code,chart_type,layout,filter,"
              + " required_permission,sort_order,created_at,updated_at)"
              + " values (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,now(),now())",
          Ids.next(),
          tenantId,
          templateId,
          metricCode,
          dimensionCode,
          chartType,
          writeJson(layout),
          writeJson(filterViews(filters)),
          requiredPermission,
          order);
    } catch (DataAccessException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "模板里已经有「" + metricCode + " × " + dimensionCode + "」这个组件了。");
    }
  }

  /** 渲染一个组件的数据（**复用 13.6.1.1 的查询引擎**）。 */
  private ReportingBoardModels.BoardComponentData renderComponent(
      UUID tenantId,
      BoardRow board,
      ComponentRow component,
      Window window,
      Map<String, ReportingCustomReportModels.CustomReportMetricView> catalog,
      List<ReportingCustomReportService.FilterSpec> defaultFilters) {
    var dimensionLabel = dimensionLabel(component.dimensionCode());
    var metric = catalog.get(component.metricCode());
    if (metric == null) {
      return new ReportingBoardModels.BoardComponentData(
          component.id().toString(),
          component.metricCode(),
          component.metricCode(),
          "",
          false,
          component.dimensionCode(),
          dimensionLabel,
          component.chartType(),
          List.of(),
          "NO_DATA",
          "这个组件的指标码已经不在口径表里了",
          "METRIC_NOT_REGISTERED",
          "指标码 " + component.metricCode() + " 未在口径表登记 —— 口径唯一，没登记就没有这个指标。",
          null,
          DATA_UPDATED_NOTE,
          List.of("🔴 **口径唯一**：指标码必须已登记，没有登记就没有这个指标。"));
    }
    var filters = mergeFilters(defaultFilters, parseFilterSpecs(component.filterJson()));
    var config =
        new ReportingCustomReportService.ReportConfig(
            board.id(),
            board.code(),
            board.name(),
            board.description(),
            board.visibility(),
            component.chartType(),
            window.from(),
            window.to(),
            board.ownerUserId(),
            List.of(component.metricCode()),
            List.of(component.dimensionCode()),
            filters);
    var response = reports.execute(tenantId, config, window.from(), window.to(), 0, COMPONENT_ROW_LIMIT);

    var points = new ArrayList<ReportingBoardModels.BoardComponentPoint>();
    for (var row : response.rows()) {
      var dimensionValue =
          row.dimensions().isEmpty() ? "" : row.dimensions().get(0).value();
      var values = new ArrayList<ReportingBoardModels.BoardComponentValue>();
      if (!row.measures().isEmpty()) {
        for (var value : row.measures().get(0).values()) {
          values.add(
              new ReportingBoardModels.BoardComponentValue(
                  value.currency(), value.value(), value.availability(), value.availabilityNote()));
        }
      }
      points.add(new ReportingBoardModels.BoardComponentPoint(dimensionValue, values));
    }
    return new ReportingBoardModels.BoardComponentData(
        component.id().toString(),
        component.metricCode(),
        metric.displayName(),
        metric.unit(),
        metric.currencyAware(),
        component.dimensionCode(),
        dimensionLabel,
        component.chartType(),
        points,
        response.status(),
        response.statusNote(),
        response.emptyReason(),
        response.emptyNote(),
        response.dataUpdatedAt(),
        response.dataUpdatedNote(),
        response.caliberNotes());
  }

  /**
   * 引擎用的筛选 → 落库用的形状。
   *
   * <p>⚠️ 必须落成 {@link ReportingBoardModels.BoardFilterView} 的字段名
   * （dimensionCode / operator / valueText / valueText2）：引擎用的 {@code FilterSpec}
   * 的字段名是 dimension / operator / value / value2 —— 两套字段名会让
   * 「存进去再读出来」对不上（筛选会静默变成空的）。
   */
  private List<ReportingBoardModels.BoardFilterView> filterViews(
      List<ReportingCustomReportService.FilterSpec> specs) {
    var out = new ArrayList<ReportingBoardModels.BoardFilterView>();
    for (var spec : specs) {
      out.add(
          new ReportingBoardModels.BoardFilterView(
              spec.dimension().name(), spec.operator().name(), spec.value(), spec.value2()));
    }
    return out;
  }

  /** 合并筛选：看板默认值打底，组件自己的筛选**优先**（同一维度 + 同一运算符）。 */
  private List<ReportingCustomReportService.FilterSpec> mergeFilters(
      List<ReportingCustomReportService.FilterSpec> defaults,
      List<ReportingCustomReportService.FilterSpec> componentFilters) {
    var merged = new LinkedHashMap<String, ReportingCustomReportService.FilterSpec>();
    for (var filter : defaults) {
      merged.put(filterKey(filter), filter);
    }
    for (var filter : componentFilters) {
      merged.put(filterKey(filter), filter);
    }
    return new ArrayList<>(merged.values());
  }

  private String filterKey(ReportingCustomReportService.FilterSpec filter) {
    return filter.dimension().name() + "|" + filter.operator().name();
  }

  /** 窗口：请求 > 看板默认值 > 最近 30 天。 */
  private Window resolveWindow(BoardRow board, LocalDate from, LocalDate to) {
    var end = to != null ? to : (board.defaultWindowTo() != null ? board.defaultWindowTo() : LocalDate.now(clock));
    var start =
        from != null
            ? from
            : (board.defaultWindowFrom() != null
                ? board.defaultWindowFrom()
                : end.minusDays(DEFAULT_WINDOW_DAYS - 1));
    if (start.isAfter(end)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "起始日期不能晚于结束日期（from=" + start + "、to=" + end + "）");
    }
    return new Window(start, end);
  }

  /** 数据更新时间（最近一次投影落库时刻）。 */
  private OffsetDateTime dataUpdatedAt(UUID tenantId) {
    var row = sql.fetchOne("select max(updated_at) from reporting_metric_fact where tenant_id = ?", tenantId);
    return row == null ? null : row.get(0, OffsetDateTime.class);
  }

  private Map<String, ReportingCustomReportModels.CustomReportMetricView> metricCatalogMap(UUID tenantId) {
    var out = new LinkedHashMap<String, ReportingCustomReportModels.CustomReportMetricView>();
    for (var metric : reports.metricCatalog(tenantId)) {
      out.put(metric.metricCode(), metric);
    }
    return out;
  }

  /** 🔴 口径唯一：指标码必须**已登记**（DB 层还有一道触发器，这里先挡一道）。 */
  private void requireRegisteredMetric(UUID tenantId, String metricCode) {
    var row =
        sql.fetchOne(
            "select metric_code from reporting_metric_definition where tenant_id = ? and metric_code = ?",
            tenantId,
            metricCode);
    if (row == null) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "指标码 " + metricCode + " 未在口径表（reporting_metric_definition）登记 —— "
              + "口径唯一，没有登记就没有这个指标。");
    }
  }

  private int nextSortOrder(UUID tenantId, UUID boardId) {
    var row =
        sql.fetchOne(
            "select coalesce(max(sort_order), -1) + 1 from reporting_board_component"
                + " where tenant_id = ? and board_id = ?",
            tenantId,
            boardId);
    return row == null ? 0 : row.get(0, Integer.class);
  }

  // ==========================================================================
  // 私有：归一化与校验
  // ==========================================================================

  /** 当前会话的上下文（**不信任任何客户端头**）。 */
  private ActorContext actorContext(AccessService.Access access) {
    var permissions = new LinkedHashSet<String>();
    var roleIds = new LinkedHashSet<UUID>();
    if (access.permissions() != null) {
      for (var permission : access.permissions()) {
        if (permission != null && !permission.isBlank()) permissions.add(permission.trim());
      }
    }
    if (access.grants() != null) {
      for (var grant : access.grants()) {
        if (grant.roleId() != null) roleIds.add(grant.roleId());
        if (grant.permissions() != null) {
          for (var permission : grant.permissions()) {
            if (permission != null && !permission.isBlank()) permissions.add(permission.trim());
          }
        }
      }
    }
    return new ActorContext(access.userId(), permissions, roleIds);
  }

  /** 指标码归一化（**形状**由 DB 的 CHECK 兜底，这里先给出可读的错误）。 */
  private String normalizeMetricCode(String metricCode) {
    var code = requireText(metricCode, "指标码").toUpperCase(Locale.ROOT);
    if (!code.matches("^[A-Z][A-Z0-9_]{0,47}$")) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "指标码形状不对（只允许大写字母 / 数字 / 下划线，收到的是「" + code + "」）");
    }
    return code;
  }

  /** 维度码归一化（**白名单**，与 13.6.1.1 完全一致）。 */
  private String normalizeDimensionCode(String dimensionCode) {
    try {
      return ReportingCustomReportCatalog.Dimension.of(dimensionCode).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** 图表类型归一化（**白名单**）。 */
  private String normalizeChartType(String chartType) {
    try {
      return ReportingCustomReportCatalog.ChartType.of(chartType).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** 维度码 → 中文标签。 */
  private String dimensionLabel(String dimensionCode) {
    try {
      return ReportingCustomReportCatalog.Dimension.of(dimensionCode).label();
    } catch (IllegalArgumentException e) {
      return dimensionCode;
    }
  }

  /** 可见范围归一化（PRIVATE / TEAM）。 */
  private String normalizeVisibility(String visibility) {
    try {
      return ReportingBoardCatalog.BoardVisibility.of(visibility).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** 共享范围归一化（ROLE / TENANT）。 */
  private String normalizeScope(String scope) {
    try {
      return ReportingBoardCatalog.ShareScope.of(scope).name();
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
  }

  /** 权限码归一化（空串 = 谁都能看）。 */
  private String normalizePermission(String requiredPermission) {
    var code = requiredPermission == null ? "" : requiredPermission.trim();
    if (!ReportingBoardCatalog.validPermissionCode(code)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "权限码形状不对（收到的是「" + code + "」）");
    }
    return code;
  }

  /**
   * 布局归一化（**非法布局直接报错**，不静默丢弃）。
   *
   * <p>⚠️ 与 DB 层 {@code reporting_board_layout_ok} 是同一条规则。
   */
  private ReportingBoardModels.BoardLayoutView normalizeLayout(
      ReportingBoardModels.BoardLayoutView layout) {
    if (layout == null) {
      return new ReportingBoardModels.BoardLayoutView(
          0, 0, ReportingBoardCatalog.DEFAULT_COMPONENT_WIDTH, ReportingBoardCatalog.DEFAULT_COMPONENT_HEIGHT);
    }
    if (layout.x() < 0 || layout.y() < 0) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "布局的位置不能是负数（收到的是 x=" + layout.x() + "、y=" + layout.y() + "）");
    }
    if (layout.w() < 1 || layout.h() < 1) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "布局的尺寸至少是 1×1（收到的是 w=" + layout.w() + "、h=" + layout.h() + "）");
    }
    if (layout.x() + layout.w() > ReportingBoardCatalog.GRID_COLUMNS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "布局超出看板宽度（看板宽 " + ReportingBoardCatalog.GRID_COLUMNS + " 栅格，收到的是 x=" + layout.x()
              + "、w=" + layout.w() + "）");
    }
    if (layout.y() + layout.h() > ReportingBoardCatalog.MAX_GRID_ROWS) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "布局超出看板高度（最多 " + ReportingBoardCatalog.MAX_GRID_ROWS + " 行，收到的是 y=" + layout.y()
              + "、h=" + layout.h() + "）");
    }
    return layout;
  }

  /** 筛选条件解析（**白名单 + 值的形状校验**；值只作为绑定参数）。 */
  private List<ReportingCustomReportService.FilterSpec> parseFilters(
      List<ReportingBoardModels.BoardFilterRequest> requests) {
    var out = new ArrayList<ReportingCustomReportService.FilterSpec>();
    if (requests == null) return out;
    for (var request : requests) {
      out.add(parseFilter(request));
    }
    return out;
  }

  private ReportingCustomReportService.FilterSpec parseFilter(
      ReportingBoardModels.BoardFilterRequest request) {
    if (request == null) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选条件不能为空");
    }
    ReportingCustomReportCatalog.Dimension dimension;
    ReportingCustomReportCatalog.FilterOperator operator;
    try {
      dimension = ReportingCustomReportCatalog.Dimension.of(request.dimensionCode());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    try {
      operator = ReportingCustomReportCatalog.FilterOperator.of(request.operator());
    } catch (IllegalArgumentException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
    }
    var value = request.valueText() == null ? "" : request.valueText().trim();
    var value2 = request.valueText2() == null ? "" : request.valueText2().trim();
    switch (operator) {
      case EQ, GTE, LTE -> {
        if (value.isEmpty()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "筛选值不能为空（维度：" + dimension.label() + "）");
        }
        validateFilterValue(dimension, value);
      }
      case IN -> {
        var values = splitIn(value);
        if (values.isEmpty()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "「属于」筛选至少要一个值（维度：" + dimension.label() + "）");
        }
        for (var item : values) {
          validateFilterValue(dimension, item);
        }
      }
      case BETWEEN -> {
        if (value.isEmpty() || value2.isEmpty()) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "「介于」筛选需要上下两个值（维度：" + dimension.label() + "）");
        }
        validateFilterValue(dimension, value);
        validateFilterValue(dimension, value2);
      }
    }
    return new ReportingCustomReportService.FilterSpec(dimension, operator, value, value2);
  }

  /** 筛选值的形状校验（与 13.6.1.1 同一条规则）。 */
  private void validateFilterValue(ReportingCustomReportCatalog.Dimension dimension, String value) {
    switch (dimension) {
      case BUCKET_DATE -> {
        try {
          LocalDate.parse(value);
        } catch (RuntimeException e) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "「日历日」维度的筛选值必须是 YYYY-MM-DD（收到的是「" + value + "」）");
        }
      }
      case LEGAL_ENTITY, SHOP -> {
        try {
          UUID.fromString(value);
        } catch (RuntimeException e) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "「" + dimension.label() + "」维度的筛选值必须是 uuid（收到的是「" + value + "」）");
        }
      }
      case CURRENCY -> {
        if (!value.matches("^[A-Za-z]{3}$")) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST, "「币种」维度的筛选值必须是 3 位字母（收到的是「" + value + "」）");
        }
      }
      case SCOPE_TYPE -> {
        var normalized = value.toUpperCase(Locale.ROOT);
        if (!normalized.equals("COMPANY")
            && !normalized.equals("LEGAL_ENTITY")
            && !normalized.equals("SHOP")) {
          throw new ResponseStatusException(
              HttpStatus.BAD_REQUEST,
              "「公司 / 法人 / 店铺」维度的筛选值只能是 COMPANY / LEGAL_ENTITY / SHOP（收到的是「"
                  + value
                  + "」）");
        }
      }
    }
  }

  private List<String> splitIn(String value) {
    var out = new ArrayList<String>();
    if (value == null) return out;
    for (var part : value.split(",")) {
      var item = part.trim();
      if (!item.isEmpty()) out.add(item);
    }
    return out;
  }

  // ==========================================================================
  // 私有：jsonb 与小工具
  // ==========================================================================

  /** 写 jsonb（**值只作为绑定参数**，用显式 cast 落库）。 */
  private String writeJson(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /** 读 jsonb 里的筛选数组。 */
  private List<ReportingBoardModels.BoardFilterView> parseFilterViews(String jsonText) {
    if (jsonText == null || jsonText.isBlank()) return List.of();
    List<ReportingBoardModels.BoardFilterRequest> parsed;
    try {
      parsed = json.readValue(jsonText, new TypeReference<List<ReportingBoardModels.BoardFilterRequest>>() {});
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选条件存的不是合法 JSON");
    }
    var out = new ArrayList<ReportingBoardModels.BoardFilterView>();
    for (var filter : parsed) {
      out.add(
          new ReportingBoardModels.BoardFilterView(
              filter.dimensionCode(),
              filter.operator(),
              filter.valueText() == null ? "" : filter.valueText(),
              filter.valueText2() == null ? "" : filter.valueText2()));
    }
    return out;
  }

  /** 读 jsonb 里的筛选数组 → 引擎用的形状。 */
  private List<ReportingCustomReportService.FilterSpec> parseFilterSpecs(String jsonText) {
    var out = new ArrayList<ReportingCustomReportService.FilterSpec>();
    if (jsonText == null || jsonText.isBlank()) return out;
    List<ReportingBoardModels.BoardFilterRequest> parsed;
    try {
      parsed = json.readValue(jsonText, new TypeReference<List<ReportingBoardModels.BoardFilterRequest>>() {});
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "筛选条件存的不是合法 JSON");
    }
    for (var filter : parsed) {
      out.add(parseFilter(filter));
    }
    return out;
  }

  /** 读 jsonb 里的布局。 */
  private ReportingBoardModels.BoardLayoutView readLayout(String jsonText) {
    if (jsonText == null || jsonText.isBlank()) {
      return new ReportingBoardModels.BoardLayoutView(
          0, 0, ReportingBoardCatalog.DEFAULT_COMPONENT_WIDTH, ReportingBoardCatalog.DEFAULT_COMPONENT_HEIGHT);
    }
    try {
      return json.readValue(
          jsonText, ReportingBoardModels.BoardLayoutView.class);
    } catch (Exception e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "布局存的不是合法 JSON");
    }
  }

  private ReportingBoardModels.BoardShareView toShareView(org.jooq.Record row) {
    var roleId = row.get(2, UUID.class);
    return new ReportingBoardModels.BoardShareView(
        row.get(0, UUID.class).toString(),
        row.get(1, UUID.class).toString(),
        roleId == null ? null : roleId.toString(),
        row.get(3, String.class),
        row.get(4, String.class),
        row.get(5, UUID.class).toString(),
        offset(row.get(6, OffsetDateTime.class)),
        offset(row.get(7, OffsetDateTime.class)));
  }

  /** 时间戳 → 文本（空 = 空串，不是「null」字符串）。 */
  private String offset(OffsetDateTime value) {
    return value == null ? "" : value.toString();
  }

  private UUID uuid(String raw, String label) {
    try {
      return UUID.fromString(raw);
    } catch (RuntimeException e) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, label + "格式不正确（收到的是「" + raw + "」）");
    }
  }

  private String requireText(String value, String label) {
    if (value == null || value.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "不能为空");
    }
    return value.trim();
  }

  private ResponseStatusException duplicate(String message) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
  }

  private String placeholders(int count) {
    return String.join(",", java.util.Collections.nCopies(Math.max(count, 1), "?"));
  }

  private List<Object> append(List<Object> params, Object... extra) {
    var out = new ArrayList<>(params);
    out.addAll(Arrays.asList(extra));
    return out;
  }

  /** 时间窗口。 */
  private record Window(LocalDate from, LocalDate to) {}

  /** 数据更新时间的口径说明。 */
  private static final String DATA_UPDATED_NOTE =
      "最近一次投影落库时刻（reporting_metric_fact.updated_at 的最大值）—— 看板是投影，不是实时的。";
}
