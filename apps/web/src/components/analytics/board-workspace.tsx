'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard } from '@starcon/ui';
import { LayoutGrid, RefreshCw, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  chartTypeLabel,
  componentRows,
  componentSubtitle,
  componentTitle,
  componentValueNote,
  componentValueText,
  dataUpdatedNote,
  dataUpdatedText,
  dimensionLabel,
  GRID_COLUMNS,
  gridColumnStart,
  gridRowStart,
  layoutChanged,
  layoutError,
  layoutText,
  listEmptyText,
  metricLabel,
  metricRegistryNote,
  moveDown,
  moveLeft,
  moveRight,
  moveUp,
  notDeliveredNotes,
  pageState,
  permissionFilterNote,
  requiredPermissionLabel,
  ruleNotes,
  shareScopeLabel,
  shareTargetLabel,
  sortByLayout,
  summaryText,
  swapLayouts,
  toBoardSaveRequest,
  toComponentSaveRequest,
  toInstantiateRequest,
  toLayoutRequest,
  toShareSaveRequest,
  toTemplateSaveRequest,
  visibilityLabel,
  workbenchDataUpdatedText,
  workbenchEmptyText,
} from './board-model';
import type {
  BoardCapabilities,
  BoardComponentSaveRequest,
  BoardComponentView,
  BoardDefaultsView,
  BoardDimensionView,
  BoardLayoutView,
  BoardMetricView,
  BoardPage,
  BoardShareView,
  BoardTemplateView,
  BoardView,
  BoardViewResponse,
  BoardWorkbenchResponse,
} from './board-model';

/**
 * 13.6.1.2 看板搭建工作区（卷 13「利润与经营分析」L3 13.6.1 下的第二格）。
 *
 * <p>蓝图输出：「看板视图 + 共享配置。」
 *
 * <p><b>数据来自 reporting 服务（13 卷的唯一归属服务）</b>：
 *
 * <ul>
 *   <li>{@code GET reporting/board/metrics} —— 可用的语义层指标清单（口径唯一）；</li>
 *   <li>{@code GET reporting/board/dimensions} —— 维度白名单（与 13.6.1.1 完全一致）；</li>
 *   <li>{@code GET/POST reporting/board/boards} —— 看板清单（真分页 + 真搜索）；</li>
 *   <li>{@code GET reporting/board/board} —— 我的看板（工作台视图）；</li>
 *   <li>{@code GET reporting/board/boards/{id}/view} —— 看板视图（组件 + 实时数据）；</li>
 *   <li>{@code POST/PUT/DELETE .../components} —— 组件（指标 + 维度 + 布局 + 筛选 + 所需权限）；</li>
 *   <li>{@code PUT .../layout} —— 拖拽后的布局批量保存；</li>
 *   <li>{@code .../shares} —— 按角色共享；{@code .../defaults} —— 默认值；</li>
 *   <li>{@code /board-templates} —— 看板模板与实例化。</li>
 * </ul>
 *
 * <p><b>🔴 页面必须做到的五件事</b>：
 *
 * <ol>
 *   <li><b>权限过滤是服务端的事</b>：组件集合是服务端按当前用户的权限 / 角色给的，
 *       前端<strong>不</strong>自己藏；但必须把「你看得见几个 / 一共几个」如实说出来。</li>
 *   <li><b>「算不出来」≠「数据是 0」</b>：不可比的取值显示「—」并把原因说清楚，绝不用 0 顶替。</li>
 *   <li><b>多币种不相加</b>：一个维度取值下的多个币种分行显示。</li>
 *   <li><b>前端不做业务算术</b>：派生值与比率全部来自服务端。</li>
 *   <li><b>数据更新时间常驻</b>（卷 13 卷首「数据延迟要显示」）。</li>
 * </ol>
 */

/** 组件草稿。 */
interface ComponentDraft {
  metricCode: string;
  dimensionCode: string;
  chartType: string;
  layout: BoardLayoutView;
  filter: { dimensionCode: string; operator: string; valueText: string; valueText2: string }[];
  requiredPermission: string;
}

const EMPTY_COMPONENT_DRAFT: ComponentDraft = {
  metricCode: '',
  dimensionCode: '',
  chartType: 'BAR',
  layout: { x: 0, y: 0, w: 3, h: 2 },
  filter: [],
  requiredPermission: '',
};

const OPERATORS = [
  { value: 'EQ', label: '等于' },
  { value: 'IN', label: '属于（逗号分隔）' },
  { value: 'GTE', label: '不早于' },
  { value: 'LTE', label: '不晚于' },
  { value: 'BETWEEN', label: '介于（上下两个值）' },
];

const CHART_TYPES = [
  { value: 'TABLE', label: '表格' },
  { value: 'BAR', label: '柱状图' },
  { value: 'LINE', label: '折线图' },
  { value: 'PIE', label: '饼图' },
];

export function BoardWorkspace() {
  const [capabilities, setCapabilities] = useState<BoardCapabilities | null>(null);
  const [metrics, setMetrics] = useState<BoardMetricView[]>([]);
  const [dimensions, setDimensions] = useState<BoardDimensionView[]>([]);
  const [templates, setTemplates] = useState<BoardTemplateView[]>([]);
  const [page, setPage] = useState<BoardPage | null>(null);
  const [workbench, setWorkbench] = useState<BoardWorkbenchResponse | null>(null);
  const [keyword, setKeyword] = useState('');
  const [visibility, setVisibility] = useState('');
  const [listError, setListError] = useState('');
  const [loading, setLoading] = useState(true);

  const [board, setBoard] = useState<BoardView | null>(null);
  const [view, setView] = useState<BoardViewResponse | null>(null);
  const [viewError, setViewError] = useState('');
  const [layoutDraft, setLayoutDraft] = useState<BoardComponentView[]>([]);
  const [dragId, setDragId] = useState<string | null>(null);
  const [savingLayout, setSavingLayout] = useState(false);

  const [componentDraft, setComponentDraft] = useState<ComponentDraft | null>(null);
  const [componentError, setComponentError] = useState('');

  const [shares, setShares] = useState<BoardShareView[]>([]);
  const [shareRoleId, setShareRoleId] = useState('');
  const [sharePermissionCode, setSharePermissionCode] = useState('');
  const [shareScope, setShareScope] = useState('ROLE');
  const [shareError, setShareError] = useState('');

  const [defaults, setDefaults] = useState<BoardDefaultsView | null>(null);
  const [defaultFilters, setDefaultFilters] = useState<
    { dimensionCode: string; operator: string; valueText: string; valueText2: string }[]
  >([]);
  const [defaultWindowFrom, setDefaultWindowFrom] = useState('');
  const [defaultWindowTo, setDefaultWindowTo] = useState('');
  const [defaultsError, setDefaultsError] = useState('');

  const [templateCode, setTemplateCode] = useState('');
  const [templateName, setTemplateName] = useState('');
  const [templateError, setTemplateError] = useState('');

  const [actionError, setActionError] = useState('');

  // ── 目录与清单 ────────────────────────────────────────────────────────
  const loadLists = useCallback(async () => {
    setLoading(true);
    setListError('');
    try {
      const query = new URLSearchParams();
      if (keyword.trim()) query.set('keyword', keyword.trim());
      if (visibility) query.set('visibility', visibility);
      query.set('page', '0');
      query.set('size', '20');
      const [caps, metricDefs, dimensionDefs, templateList, boardPage, myBoards] = await Promise.all([
        api<BoardCapabilities>('reporting/board/capabilities'),
        api<BoardMetricView[]>('reporting/board/metrics'),
        api<BoardDimensionView[]>('reporting/board/dimensions'),
        api<BoardTemplateView[]>('reporting/board/board-templates'),
        api<BoardPage>(`reporting/board/boards?${query.toString()}`),
        api<BoardWorkbenchResponse>('reporting/board/board'),
      ]);
      setCapabilities(caps);
      setMetrics(metricDefs);
      setDimensions(dimensionDefs);
      setTemplates(templateList);
      setPage(boardPage);
      setWorkbench(myBoards);
    } catch (e) {
      setListError(e instanceof Error ? e.message : '读取失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [keyword, visibility]);

  useEffect(() => {
    void loadLists();
  }, [loadLists]);

  // ── 打开一张看板 ──────────────────────────────────────────────────────
  const openBoard = useCallback(async (boardId: string) => {
    setActionError('');
    setViewError('');
    setView(null);
    setComponentDraft(null);
    try {
      const loaded = await api<BoardView>(`reporting/board/boards/${boardId}`);
      setBoard(loaded);
      setLayoutDraft(sortByLayout(loaded.components));
      setDefaults(await api<BoardDefaultsView>(`reporting/board/boards/${boardId}/defaults`));
      setDefaultFilters(
        defaultsToDraft(loaded.defaultFilters),
      );
      setDefaultWindowFrom(loaded.defaultWindowFrom ?? '');
      setDefaultWindowTo(loaded.defaultWindowTo ?? '');
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '读取看板失败，请重试');
      return;
    }
    // 🔴 共享只有创建者能管 ⇒ 非创建者打开时如实说明，不整个打不开。
    try {
      setShares(await api<BoardShareView[]>(`reporting/board/boards/${boardId}/shares`));
    } catch {
      setShares([]);
    }
    try {
      const rendered = await api<BoardViewResponse>(`reporting/board/boards/${boardId}/view`);
      setView(rendered);
    } catch (e) {
      setView(null);
      setViewError(e instanceof Error ? e.message : '看板视图读取失败，请重试');
    }
  }, []);

  // ── 看板视图刷新 ──────────────────────────────────────────────────────
  const refreshView = useCallback(async () => {
    if (!board) return;
    setViewError('');
    try {
      setView(await api<BoardViewResponse>(`reporting/board/boards/${board.boardId}/view`));
    } catch (e) {
      setView(null);
      setViewError(e instanceof Error ? e.message : '看板视图读取失败，请重试');
    }
  }, [board]);

  // ── 布局（拖拽） ──────────────────────────────────────────────────────
  const patchLayout = useCallback((componentId: string, next: BoardLayoutView) => {
    setLayoutDraft((current) =>
      current.map((component) =>
        component.componentId === componentId ? { ...component, layout: next } : component,
      ),
    );
  }, []);

  const saveLayout = useCallback(async () => {
    if (!board) return;
    setSavingLayout(true);
    setActionError('');
    try {
      await api<BoardComponentView[]>(
        `reporting/board/boards/${board.boardId}/layout`,
        'PUT',
        toLayoutRequest(layoutDraft),
      );
      await openBoard(board.boardId);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '保存布局失败，请重试');
    } finally {
      setSavingLayout(false);
    }
  }, [board, layoutDraft, openBoard]);

  // ── 组件 ──────────────────────────────────────────────────────────────
  const addComponent = useCallback(async () => {
    if (!board || !componentDraft) return;
    setComponentError('');
    const layoutProblem = layoutError(componentDraft.layout);
    if (layoutProblem) {
      setComponentError(layoutProblem);
      return;
    }
    try {
      const body: BoardComponentSaveRequest = toComponentSaveRequest(componentDraft);
      await api<BoardComponentView>(
        `reporting/board/boards/${board.boardId}/components`,
        'POST',
        body,
      );
      setComponentDraft(null);
      await openBoard(board.boardId);
    } catch (e) {
      setComponentError(e instanceof Error ? e.message : '保存组件失败，请检查输入后重试');
    }
  }, [board, componentDraft, openBoard]);

  const deleteComponent = useCallback(
    async (componentId: string) => {
      if (!board) return;
      setActionError('');
      try {
        await api<void>(
          `reporting/board/boards/${board.boardId}/components/${componentId}`,
          'DELETE',
        );
        await openBoard(board.boardId);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '删除组件失败，请重试');
      }
    },
    [board, openBoard],
  );

  // ── 共享 ──────────────────────────────────────────────────────────────
  const addShare = useCallback(async () => {
    if (!board) return;
    setShareError('');
    try {
      await api<BoardShareView>(`reporting/board/boards/${board.boardId}/shares`, 'POST', {
        ...toShareSaveRequest({ roleId: shareRoleId, permissionCode: sharePermissionCode, scope: shareScope }),
      });
      setShareRoleId('');
      setSharePermissionCode('');
      setShares(await api<BoardShareView[]>(`reporting/board/boards/${board.boardId}/shares`));
    } catch (e) {
      setShareError(e instanceof Error ? e.message : '保存共享失败，请检查输入后重试');
    }
  }, [board, shareRoleId, sharePermissionCode, shareScope]);

  const revokeShare = useCallback(
    async (shareId: string) => {
      if (!board) return;
      setShareError('');
      try {
        await api<void>(`reporting/board/boards/${board.boardId}/shares/${shareId}`, 'DELETE');
        setShares(await api<BoardShareView[]>(`reporting/board/boards/${board.boardId}/shares`));
      } catch (e) {
        setShareError(e instanceof Error ? e.message : '撤销共享失败，请重试');
      }
    },
    [board],
  );

  // ── 默认值 ────────────────────────────────────────────────────────────
  const saveDefaults = useCallback(async () => {
    if (!board) return;
    setDefaultsError('');
    try {
      await api<BoardDefaultsView>(`reporting/board/boards/${board.boardId}/defaults`, 'PUT', {
        defaultFilters: defaultFilters.map((filter) => ({
          dimensionCode: filter.dimensionCode,
          operator: filter.operator,
          valueText: filter.valueText,
          valueText2: filter.valueText2 || null,
        })),
        defaultWindowFrom: defaultWindowFrom || null,
        defaultWindowTo: defaultWindowTo || null,
      });
      await openBoard(board.boardId);
    } catch (e) {
      setDefaultsError(e instanceof Error ? e.message : '保存默认值失败，请重试');
    }
  }, [board, defaultFilters, defaultWindowFrom, defaultWindowTo, openBoard]);

  // ── 模板 ──────────────────────────────────────────────────────────────
  const createTemplate = useCallback(async () => {
    setTemplateError('');
    try {
      await api<BoardTemplateView>('reporting/board/board-templates', 'POST', {
        ...toTemplateSaveRequest({
          templateCode,
          displayName: templateName,
          description: '',
          components: board
            ? board.components.map((component) => ({
                metricCode: component.metricCode,
                dimensionCode: component.dimensionCode,
                chartType: component.chartType,
                layout: component.layout,
                filter: component.filter.map((filter) => ({
                  dimensionCode: filter.dimensionCode,
                  operator: filter.operator,
                  valueText: filter.valueText,
                  valueText2: filter.valueText2 || null,
                })),
                requiredPermission: component.requiredPermission,
                sortOrder: component.sortOrder,
              }))
            : [],
        }),
      });
      setTemplateCode('');
      setTemplateName('');
      const list = await api<BoardTemplateView[]>('reporting/board/board-templates');
      setTemplates(list);
    } catch (e) {
      setTemplateError(e instanceof Error ? e.message : '保存模板失败，请检查输入后重试');
    }
  }, [board, templateCode, templateName]);

  const instantiateTemplate = useCallback(
    async (templateId: string) => {
      setTemplateError('');
      const code = `B-${templateId.slice(0, 8)}`;
      try {
        const created = await api<BoardView>(
          `reporting/board/board-templates/${templateId}/instantiate`,
          'POST',
          {
            ...toInstantiateRequest({
              boardCode: code,
              displayName: `从模板 ${code} 建的看板`,
              description: '',
              visibility: 'TEAM',
            }),
          },
        );
        await loadLists();
        await openBoard(created.boardId);
      } catch (e) {
        setTemplateError(e instanceof Error ? e.message : '用模板建看板失败，请重试');
      }
    },
    [loadLists, openBoard],
  );

  // ── 渲染辅助 ──────────────────────────────────────────────────────────
  const metricByCode = useMemo(() => {
    const map = new Map<string, BoardMetricView>();
    for (const metric of metrics) map.set(metric.metricCode, metric);
    return map;
  }, [metrics]);

  const dimensionByCode = useMemo(() => {
    const map = new Map<string, BoardDimensionView>();
    for (const dimension of dimensions) map.set(dimension.dimensionCode, dimension);
    return map;
  }, [dimensions]);

  const dataByComponent = useMemo(() => {
    const map = new Map<string, BoardViewResponse['components'][number]>();
    for (const component of view?.components ?? []) map.set(component.componentId, component);
    return map;
  }, [view]);

  const viewState = pageState(view, viewError);
  const dirty = board ? layoutChanged(board.components, layoutDraft) : false;

  return (
    <div className="space-y-4">
      {/* ── 能力声明横幅 ──────────────────────────────────────────────── */}
      <div className="border-border bg-muted/40 flex flex-wrap items-center justify-between gap-3 rounded-lg border px-4 py-3">
        <div className="text-copy-13 text-foreground">
          <span className="font-medium">看板搭建：按已登记的语义层指标摆组件</span>
          <span className="text-muted-foreground ml-2">
            口径唯一，组件按角色权限过滤 —— 不同角色看到的组件不同。
          </span>
        </div>
        <div className="text-copy-13 flex flex-wrap items-center gap-2">
          <Badge tone="warning">导出与定时推送：未交付（13.6.2.1）</Badge>
          <Button variant="outline" size="sm" disabled={loading} onClick={() => void loadLists()}>
            <RefreshCw className="mr-1.5 h-3.5 w-3.5" />
            刷新
          </Button>
        </div>
      </div>

      {capabilities && notDeliveredNotes(capabilities).length > 0 && (
        <SectionCard title="这一版还不能做什么" description="如实写清楚，比做一个假按钮好。">
          <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
            {notDeliveredNotes(capabilities).map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        </SectionCard>
      )}

      {capabilities && (
        <SectionCard title="判定规则与口径纪律" description="页面上照着显示，不在代码注释里。">
          <ul className="text-copy-13 text-muted-foreground list-disc space-y-1 pl-5">
            {ruleNotes(capabilities).map((rule) => (
              <li key={rule.ruleCode}>{rule.description}</li>
            ))}
          </ul>
        </SectionCard>
      )}

      {/* ── 我的看板 ──────────────────────────────────────────────────── */}
      <SectionCard
        title="我的看板"
        description="你**能打开**的看板（按共享配置过滤）。别人共享给你的看板也会出现在这里。"
      >
        {workbench && workbench.boards.length === 0 && (
          <EmptyState title="还没有能打开的看板" description={workbenchEmptyText(workbench)} />
        )}
        {workbench && workbench.boards.length > 0 && (
          <>
            <div className="flex flex-wrap gap-2">
              {workbench.boards.map((item) => (
                <button
                  key={item.boardId}
                  type="button"
                  onClick={() => void openBoard(item.boardId)}
                  className="border-border bg-background hover:border-foreground/40 rounded-md border px-3 py-2 text-left"
                >
                  <div className="text-copy-13 font-medium">{item.displayName}</div>
                  <div className="text-muted-foreground text-copy-13">
                    {item.visibleComponentCount}/{item.totalComponentCount} 个组件可见 ·{' '}
                    {visibilityLabel(item.visibility)}
                    {item.ownedByMe ? ' · 我创建的' : ''}
                  </div>
                </button>
              ))}
            </div>
            <p className="text-copy-13 text-muted-foreground mt-2">{workbenchDataUpdatedText(workbench)}</p>
          </>
        )}
      </SectionCard>

      {/* ── 看板清单 ──────────────────────────────────────────────────── */}
      <SectionCard title="看板清单" description="真分页 + 真搜索 + 真筛选。">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            搜索
            <input
              type="search"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="看板名称或编码"
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            可见范围
            <select
              value={visibility}
              onChange={(e) => setVisibility(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            >
              <option value="">全部</option>
              <option value="PRIVATE">仅自己可见</option>
              <option value="TEAM">团队可见</option>
            </select>
          </label>
        </div>

        {listError && <p className="text-copy-13 text-destructive mt-3">读取失败：{listError}</p>}

        {!listError && page && page.rows.length === 0 && (
          <EmptyState title="没有看板" description={listEmptyText(page)} />
        )}

        {!listError && page && page.rows.length > 0 && (
          <div className="mt-4 overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead className="text-muted-foreground border-border border-b">
                <tr>
                  <th className="py-2 pr-3 font-medium">看板</th>
                  <th className="py-2 pr-3 font-medium">构成</th>
                  <th className="py-2 pr-3 font-medium">可见范围</th>
                  <th className="py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {page.rows.map((row) => (
                  <tr key={row.boardId} className="border-border/60 border-b">
                    <td className="py-2 pr-3">
                      <div className="font-medium">{row.displayName}</div>
                      <div className="text-muted-foreground text-copy-13">{row.boardCode}</div>
                    </td>
                    <td className="text-muted-foreground py-2 pr-3">{summaryText(row)}</td>
                    <td className="py-2 pr-3">
                      <Badge tone="neutral">{visibilityLabel(row.visibility)}</Badge>
                    </td>
                    <td className="py-2">
                      <Button size="sm" variant="outline" onClick={() => void openBoard(row.boardId)}>
                        打开
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-copy-13 text-muted-foreground mt-2">共 {page.total} 张看板。</p>
          </div>
        )}
      </SectionCard>

      {/* ── 看板工作区 ────────────────────────────────────────────────── */}
      {board && (
        <>
          <SectionCard
            title={`看板：${board.displayName}`}
            description={`${board.boardCode} · ${visibilityLabel(board.visibility)} · ${
              board.ownedByMe ? '我创建的（可编辑）' : '别人创建的（只读配置）'
            }`}
          >
            <div className="flex flex-wrap items-center gap-2">
              <Button variant="outline" size="sm" onClick={() => void refreshView()}>
                刷新数据
              </Button>
              {board.ownedByMe && (
                <Button
                  size="sm"
                  disabled={!dirty || savingLayout}
                  onClick={() => void saveLayout()}
                >
                  {savingLayout ? '保存中…' : '保存布局'}
                </Button>
              )}
              {dirty && (
                <span className="text-copy-13 text-muted-foreground">
                  布局改了还没保存。
                </span>
              )}
            </div>
            {actionError && <p className="text-copy-13 text-destructive mt-2">{actionError}</p>}
          </SectionCard>

          {/* 画布：拖拽组件 */}
          <SectionCard
            title="看板画布"
            description={`${GRID_COLUMNS} 栅格宽。拖动卡片换位置（或用方向键），改完点「保存布局」。`}
          >
            {layoutDraft.length === 0 && (
              <EmptyState
                title="还没有组件"
                description="在下面「加组件」里挑一个已登记的指标开始搭。"
              />
            )}
            {layoutDraft.length > 0 && (
              <div
                className="grid gap-3"
                style={{ gridTemplateColumns: `repeat(${GRID_COLUMNS}, minmax(0, 1fr))` }}
              >
                {sortByLayout(layoutDraft).map((component) => {
                  const data = dataByComponent.get(component.componentId);
                  const rows = data ? componentRows(data) : [];
                  return (
                    <div
                      key={component.componentId}
                      draggable={board.ownedByMe}
                      onDragStart={() => setDragId(component.componentId)}
                      onDragEnd={() => setDragId(null)}
                      onDragOver={(event) => event.preventDefault()}
                      onDrop={(event) => {
                        event.preventDefault();
                        if (!dragId || !board.ownedByMe) return;
                        setLayoutDraft((current) => swapLayouts(current, dragId, component.componentId));
                        setDragId(null);
                      }}
                      className="border-border bg-background rounded-lg border p-3"
                      style={{
                        gridColumn: `${gridColumnStart(component.layout)} / span ${component.layout.w}`,
                        gridRow: `${gridRowStart(component.layout)} / span ${component.layout.h}`,
                        opacity: dragId === component.componentId ? 0.5 : 1,
                      }}
                    >
                      <div className="flex items-start justify-between gap-2">
                        <div>
                          <div className="text-copy-13 font-medium">
                            {componentTitle(component)}
                          </div>
                          <div className="text-muted-foreground text-copy-13">
                            {componentSubtitle(component)} · {chartTypeLabel(component.chartType)} ·{' '}
                            {layoutText(component.layout)}
                          </div>
                          <div className="text-muted-foreground text-copy-13">
                            {requiredPermissionLabel(component.requiredPermission)}
                          </div>
                        </div>
                        {board.ownedByMe && (
                          <button
                            type="button"
                            aria-label="删除组件"
                            className="text-muted-foreground hover:text-destructive"
                            onClick={() => void deleteComponent(component.componentId)}
                          >
                            <TriangleAlert className="h-4 w-4" />
                          </button>
                        )}
                      </div>

                      {data && data.points.length > 0 && (
                        <table className="mt-2 w-full text-left text-copy-13">
                          <tbody>
                            {rows.map((row) => (
                              <tr key={row.dimensionValue}>
                                <td className="text-muted-foreground pr-2 align-top">
                                  {row.dimensionValue}
                                </td>
                                <td className="align-top">
                                  {row.lines.length > 0 ? row.lines.join(' / ') : '—'}
                                  {row.notes.length > 0 && (
                                    <div className="text-muted-foreground">
                                      {row.notes.join('；')}
                                    </div>
                                  )}
                                </td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                      {data && data.points.length === 0 && (
                        <p className="text-muted-foreground mt-2 text-copy-13">
                          {data.emptyNote || '这个窗口里没有数据（不是 0）。'}
                        </p>
                      )}
                      {!data && (
                        <p className="text-muted-foreground mt-2 text-copy-13">
                          这个组件这次没有取到数据。
                        </p>
                      )}

                      {board.ownedByMe && (
                        <div className="mt-2 flex flex-wrap gap-1">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => patchLayout(component.componentId, moveLeft(component.layout))}
                          >
                            ←
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => patchLayout(component.componentId, moveRight(component.layout))}
                          >
                            →
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => patchLayout(component.componentId, moveUp(component.layout))}
                          >
                            ↑
                          </Button>
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => patchLayout(component.componentId, moveDown(component.layout))}
                          >
                            ↓
                          </Button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
            {viewError && <p className="text-copy-13 text-destructive mt-3">{viewError}</p>}
            {viewState === 'empty' && (
              <p className="text-copy-13 text-muted-foreground mt-3">
                这个时间窗口里没有任何组件取到数据（不是 0）。
              </p>
            )}
            {view && (
              <>
                <p className="text-copy-13 text-muted-foreground mt-3">
                  {permissionFilterNote(view.visibleComponentCount, view.totalComponentCount)}
                </p>
                <p className="text-copy-13 text-muted-foreground mt-1">
                  {dataUpdatedText(view)} · {dataUpdatedNote(view)}
                </p>
              </>
            )}
          </SectionCard>

          {/* 加组件 */}
          {board.ownedByMe && (
            <SectionCard
              title="加组件"
              description={metricRegistryNote()}
            >
              {!componentDraft ? (
                <Button onClick={() => setComponentDraft({ ...EMPTY_COMPONENT_DRAFT })}>
                  <LayoutGrid className="mr-1.5 h-4 w-4" />
                  新增组件
                </Button>
              ) : (
                <div className="space-y-3">
                  <div className="flex flex-wrap gap-3">
                    <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                      指标（已登记）
                      <select
                        value={componentDraft.metricCode}
                        onChange={(e) =>
                          setComponentDraft({ ...componentDraft, metricCode: e.target.value })
                        }
                        className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                      >
                        <option value="">请选择</option>
                        {metrics.map((metric) => (
                          <option key={metric.metricCode} value={metric.metricCode}>
                            {metricLabel(metric, metric.metricCode)}（{metric.metricCode}）
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                      维度（白名单）
                      <select
                        value={componentDraft.dimensionCode}
                        onChange={(e) =>
                          setComponentDraft({ ...componentDraft, dimensionCode: e.target.value })
                        }
                        className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                      >
                        <option value="">请选择</option>
                        {dimensions.map((dimension) => (
                          <option key={dimension.dimensionCode} value={dimension.dimensionCode}>
                            {dimensionLabel(dimension, dimension.dimensionCode)}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                      图表类型
                      <select
                        value={componentDraft.chartType}
                        onChange={(e) =>
                          setComponentDraft({ ...componentDraft, chartType: e.target.value })
                        }
                        className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                      >
                        {CHART_TYPES.map((type) => (
                          <option key={type.value} value={type.value}>
                            {type.label}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                      所需权限码（留空 = 所有人可见）
                      <input
                        value={componentDraft.requiredPermission}
                        onChange={(e) =>
                          setComponentDraft({
                            ...componentDraft,
                            requiredPermission: e.target.value,
                          })
                        }
                        placeholder="reporting.profit.read"
                        className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                      />
                    </label>
                  </div>

                  <div className="flex flex-wrap gap-3">
                    {(['x', 'y', 'w', 'h'] as const).map((key) => (
                      <label key={key} className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                        {key}
                        <input
                          type="number"
                          min={key === 'w' || key === 'h' ? 1 : 0}
                          value={componentDraft.layout[key]}
                          onChange={(e) =>
                            setComponentDraft({
                              ...componentDraft,
                              layout: {
                                ...componentDraft.layout,
                                [key]: Number(e.target.value),
                              },
                            })
                          }
                          className="border-border bg-background w-20 rounded-md border px-2 py-1.5 text-sm"
                        />
                      </label>
                    ))}
                  </div>
                  {layoutError(componentDraft.layout) && (
                    <p className="text-copy-13 text-destructive">
                      {layoutError(componentDraft.layout)}
                    </p>
                  )}

                  <div className="space-y-2">
                    <div className="text-copy-13 text-muted-foreground">组件筛选（值只作为参数传给服务端）</div>
                    {componentDraft.filter.map((filter, index) => (
                      <div key={index} className="flex flex-wrap items-center gap-2">
                        <select
                          value={filter.dimensionCode}
                          onChange={(e) => {
                            const next = [...componentDraft.filter];
                            next[index] = { ...filter, dimensionCode: e.target.value };
                            setComponentDraft({ ...componentDraft, filter: next });
                          }}
                          className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                        >
                          <option value="">维度</option>
                          {dimensions.map((dimension) => (
                            <option key={dimension.dimensionCode} value={dimension.dimensionCode}>
                              {dimensionLabel(dimension, dimension.dimensionCode)}
                            </option>
                          ))}
                        </select>
                        <select
                          value={filter.operator}
                          onChange={(e) => {
                            const next = [...componentDraft.filter];
                            next[index] = { ...filter, operator: e.target.value };
                            setComponentDraft({ ...componentDraft, filter: next });
                          }}
                          className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                        >
                          {OPERATORS.map((operator) => (
                            <option key={operator.value} value={operator.value}>
                              {operator.label}
                            </option>
                          ))}
                        </select>
                        <input
                          value={filter.valueText}
                          placeholder="值"
                          onChange={(e) => {
                            const next = [...componentDraft.filter];
                            next[index] = { ...filter, valueText: e.target.value };
                            setComponentDraft({ ...componentDraft, filter: next });
                          }}
                          className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                        />
                        <input
                          value={filter.valueText2}
                          placeholder="上界（仅「介于」）"
                          onChange={(e) => {
                            const next = [...componentDraft.filter];
                            next[index] = { ...filter, valueText2: e.target.value };
                            setComponentDraft({ ...componentDraft, filter: next });
                          }}
                          className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                        />
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() =>
                            setComponentDraft({
                              ...componentDraft,
                              filter: componentDraft.filter.filter((_, i) => i !== index),
                            })
                          }
                        >
                          去掉
                        </Button>
                      </div>
                    ))}
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        setComponentDraft({
                          ...componentDraft,
                          filter: [
                            ...componentDraft.filter,
                            {
                              dimensionCode: '',
                              operator: 'EQ',
                              valueText: '',
                              valueText2: '',
                            },
                          ],
                        })
                      }
                    >
                      加一条筛选
                    </Button>
                  </div>

                  {componentError && <p className="text-copy-13 text-destructive">{componentError}</p>}
                  <div className="flex gap-2">
                    <Button onClick={() => void addComponent()}>保存组件</Button>
                    <Button variant="outline" onClick={() => setComponentDraft(null)}>
                      取消
                    </Button>
                  </div>
                </div>
              )}
            </SectionCard>
          )}

          {/* 共享 */}
          <SectionCard
            title="按角色共享"
            description="角色与权限码**二选一**。共享决定「谁能打开这张看板」，组件**仍然**按所需权限过滤。"
          >
            {shares.length === 0 && (
              <p className="text-muted-foreground text-copy-13">还没有共享配置。</p>
            )}
            {shares.length > 0 && (
              <ul className="space-y-1">
                {shares.map((share) => (
                  <li key={share.shareId} className="text-copy-13 flex items-center gap-2">
                    <span>{shareTargetLabel(share)}</span>
                    <Badge tone="neutral">{shareScopeLabel(share.scope)}</Badge>
                    {board.ownedByMe && (
                      <Button size="sm" variant="outline" onClick={() => void revokeShare(share.shareId)}>
                        撤销
                      </Button>
                    )}
                  </li>
                ))}
              </ul>
            )}
            {board.ownedByMe && (
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                  角色 id
                  <input
                    value={shareRoleId}
                    onChange={(e) => setShareRoleId(e.target.value)}
                    placeholder="与权限码二选一"
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  />
                </label>
                <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                  权限码
                  <input
                    value={sharePermissionCode}
                    onChange={(e) => setSharePermissionCode(e.target.value)}
                    placeholder="reporting.profit.read"
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  />
                </label>
                <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                  范围
                  <select
                    value={shareScope}
                    onChange={(e) => setShareScope(e.target.value)}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  >
                    <option value="ROLE">仅命中角色的人</option>
                    <option value="TENANT">租户内成员</option>
                  </select>
                </label>
                <Button onClick={() => void addShare()}>加一条共享</Button>
              </div>
            )}
            {shareError && <p className="text-copy-13 text-destructive mt-2">{shareError}</p>}
          </SectionCard>

          {/* 默认值 */}
          <SectionCard
            title="默认值"
            description="默认筛选（组件自己的筛选优先）与默认时间窗口。只有创建者能改。"
          >
            <div className="flex flex-wrap gap-3">
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                默认起始日
                <input
                  type="date"
                  value={defaultWindowFrom}
                  disabled={!board.ownedByMe}
                  onChange={(e) => setDefaultWindowFrom(e.target.value)}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                />
              </label>
              <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
                默认结束日
                <input
                  type="date"
                  value={defaultWindowTo}
                  disabled={!board.ownedByMe}
                  onChange={(e) => setDefaultWindowTo(e.target.value)}
                  className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                />
              </label>
            </div>
            <div className="mt-3 space-y-2">
              {defaultFilters.map((filter, index) => (
                <div key={index} className="flex flex-wrap items-center gap-2">
                  <select
                    value={filter.dimensionCode}
                    disabled={!board.ownedByMe}
                    onChange={(e) => {
                      const next = [...defaultFilters];
                      next[index] = { ...filter, dimensionCode: e.target.value };
                      setDefaultFilters(next);
                    }}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  >
                    <option value="">维度</option>
                    {dimensions.map((dimension) => (
                      <option key={dimension.dimensionCode} value={dimension.dimensionCode}>
                        {dimensionLabel(dimension, dimension.dimensionCode)}
                      </option>
                    ))}
                  </select>
                  <select
                    value={filter.operator}
                    disabled={!board.ownedByMe}
                    onChange={(e) => {
                      const next = [...defaultFilters];
                      next[index] = { ...filter, operator: e.target.value };
                      setDefaultFilters(next);
                    }}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  >
                    {OPERATORS.map((operator) => (
                      <option key={operator.value} value={operator.value}>
                        {operator.label}
                      </option>
                    ))}
                  </select>
                  <input
                    value={filter.valueText}
                    disabled={!board.ownedByMe}
                    placeholder="值"
                    onChange={(e) => {
                      const next = [...defaultFilters];
                      next[index] = { ...filter, valueText: e.target.value };
                      setDefaultFilters(next);
                    }}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  />
                  <input
                    value={filter.valueText2}
                    disabled={!board.ownedByMe}
                    placeholder="上界"
                    onChange={(e) => {
                      const next = [...defaultFilters];
                      next[index] = { ...filter, valueText2: e.target.value };
                      setDefaultFilters(next);
                    }}
                    className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
                  />
                  {board.ownedByMe && (
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        setDefaultFilters(defaultFilters.filter((_, i) => i !== index))
                      }
                    >
                      去掉
                    </Button>
                  )}
                </div>
              ))}
              {board.ownedByMe && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setDefaultFilters([
                      ...defaultFilters,
                      { dimensionCode: '', operator: 'EQ', valueText: '', valueText2: '' },
                    ])
                  }
                >
                  加一条默认筛选
                </Button>
              )}
            </div>
            {board.ownedByMe && (
              <Button className="mt-3" onClick={() => void saveDefaults()}>
                保存默认值
              </Button>
            )}
            {defaultsError && <p className="text-copy-13 text-destructive mt-2">{defaultsError}</p>}
            {defaults && !defaults.ownedByMe && (
              <p className="text-muted-foreground text-copy-13 mt-2">
                默认值由看板的创建者维护。
              </p>
            )}
          </SectionCard>
        </>
      )}

      {/* ── 看板模板 ──────────────────────────────────────────────────── */}
      <SectionCard
        title="看板模板"
        description="模板是一份可复用的看板骨架；实例化之后才变成一张真看板。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            模板编码
            <input
              value={templateCode}
              onChange={(e) => setTemplateCode(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <label className="text-copy-13 text-muted-foreground flex flex-col gap-1">
            模板名称
            <input
              value={templateName}
              onChange={(e) => setTemplateName(e.target.value)}
              className="border-border bg-background rounded-md border px-2 py-1.5 text-sm"
            />
          </label>
          <Button
            onClick={() => void createTemplate()}
            disabled={!templateCode.trim() || !templateName.trim()}
          >
            把当前看板存成模板
          </Button>
        </div>
        {templates.length === 0 && (
          <p className="text-muted-foreground text-copy-13 mt-3">还没有模板。</p>
        )}
        {templates.length > 0 && (
          <ul className="mt-3 space-y-1">
            {templates.map((template) => (
              <li key={template.templateId} className="text-copy-13 flex items-center gap-2">
                <span className="font-medium">{template.displayName}</span>
                <span className="text-muted-foreground">{template.templateCode}</span>
                <span className="text-muted-foreground">
                  {template.components.length} 个组件
                </span>
                <Button size="sm" variant="outline" onClick={() => void instantiateTemplate(template.templateId)}>
                  用它建看板
                </Button>
              </li>
            ))}
          </ul>
        )}
        {templateError && <p className="text-copy-13 text-destructive mt-2">{templateError}</p>}
      </SectionCard>
    </div>
  );
}

/** 看板的默认筛选 → 编辑草稿。 */
function defaultsToDraft(
  filters: { dimensionCode: string; operator: string; valueText: string; valueText2: string }[],
): { dimensionCode: string; operator: string; valueText: string; valueText2: string }[] {
  return filters.map((filter) => ({
    dimensionCode: filter.dimensionCode,
    operator: filter.operator,
    valueText: filter.valueText,
    valueText2: filter.valueText2,
  }));
}
