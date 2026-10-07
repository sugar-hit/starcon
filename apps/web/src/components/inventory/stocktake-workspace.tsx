'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle,
  ClipboardCheck,
  ClipboardList,
  RefreshCw,
  ScanLine,
  ShieldAlert,
} from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  LocationRef,
  StocktakeDetail,
  StocktakeLinePage,
  StocktakeScopeCapability,
  StocktakeSheetPage,
  StocktakeTask,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import { fetchLocations, fetchWarehouses } from './inventory-model';
import {
  assignStocktakeTask,
  availableScopes,
  bookText,
  countStocktakeLine,
  countedQtyProblem,
  createStocktake,
  errorText,
  fetchStocktake,
  fetchStocktakeCapabilities,
  fetchStocktakeLines,
  fetchStocktakes,
  fetchStocktakeTasks,
  formatMoment,
  lineFilterOptions,
  progressPercent,
  progressText,
  qty,
  sheetStatusLabels,
  taskStatusLabels,
  taskUnitOptions,
  toCountedQty,
  unavailableNote,
  varianceText,
  varianceTone,
} from './stocktake-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.4.2.1 盘点单工作区。
 *
 * <p>蓝图：「发起盘点：选择范围（按仓库/类目/库位/ABC 分类）、冻结时点快照、生成盘点任务
 * （按库位或按 SKU）、录入实盘数」，输出「盘点单 + 账面/实盘/差异三列数据」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li>**冻结时刻要摆在最显眼处，而不是一行元数据**。这一页存在的理由是「账冻结在那一刻」，
 *       所以冻结时刻、冻结行数与「账面合计」必须一眼可见 —— 否则用户会把账面数误当成当前余额。
 *   <li>**账面列是冻结值，不与当前余额做任何对比**。冻结之后仓库照常收发，账面合计与今天的
 *       库存余额本来就不一样，界面不制造「异常」提示来暗示它错了。
 *   <li>**未录入与差异 0 必须视觉上分开**。破折号表示还没盘，0 表示盘过且相符 ——
 *       把两者混成同一个显示，是盘点录入最危险的误导。
 *   <li>**不可用的范围保留并说明原因**。ABC 分类库里没有字段、类目投影未接入，
 *       这两件事要能对用户讲清楚，而不是让选项凭空消失。
 *   <li>**差异不能手工填**。录入框只收实盘数，差异列只读 —— 它由账面与实盘算出。
 * </ul>
 */
export function StocktakeWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 发起盘点（冻结账面）与录入实盘是写动作：把「冻结账面」挂在读权限上，那道闸门等于没有。
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [page, setPage] = useState<StocktakeSheetPage | null>(null);
  const [capabilities, setCapabilities] = useState<StocktakeScopeCapability[]>([]);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [locations, setLocations] = useState<LocationRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageNo, setPageNo] = useState(0);

  const [detail, setDetail] = useState<StocktakeDetail | null>(null);
  const [lines, setLines] = useState<StocktakeLinePage | null>(null);
  const [tasks, setTasks] = useState<StocktakeTask[]>([]);
  const [lineFilter, setLineFilter] = useState('ALL');
  const [lineKeyword, setLineKeyword] = useState('');
  const [linePage, setLinePage] = useState(0);
  const [drillLoading, setDrillLoading] = useState(false);

  const [draftQty, setDraftQty] = useState('');
  const [draftBarcode, setDraftBarcode] = useState('');
  const [draftLineId, setDraftLineId] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // 发起盘点表单
  const [scopeType, setScopeType] = useState('WAREHOUSE');
  const [scopeKey, setScopeKey] = useState('');
  const [taskUnit, setTaskUnit] = useState('BY_LOCATION');
  const [note, setNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [sheets, caps] = await Promise.all([
        fetchStocktakes({ status, keyword, page: pageNo, size: 20 }),
        fetchStocktakeCapabilities(),
      ]);
      setPage(sheets);
      setCapabilities(caps);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [status, keyword, pageNo]);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  useEffect(() => {
    if (!readable) return;
    fetchWarehouses()
      .then(setWarehouses)
      .catch(() => setWarehouses([]));
  }, [readable]);

  // 库位下拉按所选仓库过滤：让「先选仓、再选库位」这条路径在界面上成立。
  useEffect(() => {
    if (!readable) return;
    fetchLocations()
      .then(setLocations)
      .catch(() => setLocations([]));
  }, [readable]);

  const openSheet = useCallback(
    async (id: string) => {
      setError('');
      setDrillLoading(true);
      setDetail(null);
      setLines(null);
      setTasks([]);
      setDraftLineId(null);
      setDraftQty('');
      setDraftBarcode('');
      try {
        const [d, l, t] = await Promise.all([
          fetchStocktake(id),
          fetchStocktakeLines(id, {
            filter: lineFilter,
            keyword: lineKeyword,
            page: linePage,
            size: 50,
          }),
          fetchStocktakeTasks(id, true),
        ]);
        setDetail(d);
        setLines(l);
        setTasks(t);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setDrillLoading(false);
      }
    },
    [lineFilter, lineKeyword, linePage],
  );

  // 筛选条件变了就把当前单重新拉一遍（而不是让表格停在旧筛选结果上）。
  useEffect(() => {
    if (!detail) return;
    void openSheet(detail.sheet.id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [lineFilter, lineKeyword, linePage]);

  const submitCount = useCallback(
    async (lineId: string, raw: string) => {
      if (!detail) return;
      const problem = countedQtyProblem(raw);
      if (problem) {
        setError(problem);
        return;
      }
      setSaving(true);
      setError('');
      try {
        const barcode = draftBarcode.trim();
        await countStocktakeLine(detail.sheet.id, lineId, {
          countedQty: toCountedQty(raw),
          ...(barcode ? { barcode } : {}),
        });
        setNotice(
          `已录入实盘数 ${raw}。差异由「实盘 − 账面」自动算出，不能手工填；账面数仍是冻结那一刻的值。`,
        );
        setDraftLineId(null);
        setDraftQty('');
        setDraftBarcode('');
        await openSheet(detail.sheet.id);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [detail, draftBarcode, openSheet],
  );

  const undoCount = useCallback(
    async (lineId: string) => {
      if (!detail) return;
      setSaving(true);
      setError('');
      try {
        await countStocktakeLine(detail.sheet.id, lineId, { countedQty: null });
        setNotice('已撤销这一行的录入，它回到「未录入」，差异随之消失。复盘时用这个动作。');
        await openSheet(detail.sheet.id);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [detail, openSheet],
  );

  const submitCreate = useCallback(async () => {
    if (!scopeKey) {
      setError('请先选择盘点范围');
      return;
    }
    setSaving(true);
    setError('');
    setNotice('');
    try {
      const saved = await createStocktake({ scopeType, scopeKey, taskUnit, note: note.trim() });
      setNotice(saved.note);
      setNote('');
      setScopeKey('');
      await load();
      await openSheet(saved.sheet.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [scopeType, scopeKey, taskUnit, note, load, openSheet]);

  const submitAssign = useCallback(
    async (task: StocktakeTask) => {
      if (!detail) return;
      setSaving(true);
      setError('');
      try {
        await assignStocktakeTask(detail.sheet.id, task.id, null);
        setNotice(`已取消「${task.taskCode}」的派工。`);
        await openSheet(detail.sheet.id);
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [detail, openSheet],
  );

  const usableScopes = useMemo(() => availableScopes(capabilities), [capabilities]);
  const scopeOptions = useMemo(() => {
    if (scopeType === 'LOCATION') {
      return locations.map((l) => ({
        value: l.id,
        label: `${l.warehouseCode}/${l.code} ${l.name}`,
      }));
    }
    return warehouses.map((w) => ({ value: w.id, label: `${w.code} ${w.name}` }));
  }, [scopeType, locations, warehouses]);

  if (!readable) {
    return (
      <EmptyState
        icon={<ShieldAlert className="h-6 w-6" />}
        title="没有盘点单的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const blockedNote = unavailableNote(capabilities, scopeType);

  return (
    <div className="space-y-6">
      <PageHeader
        title="盘点单"
        description="发起盘点并在发起那一刻冻结账面数；之后录入实盘数，差异由实盘减账面自动算出。冻结之后仓库照常收发，但账面列不再被改写。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}
      {notice && (
        <Notice tone="success" title="操作已完成">
          {notice}
        </Notice>
      )}

      {page && detail && (
        <div className="grid gap-4 md:grid-cols-4">
          <StatCard
            label="冻结行数"
            value={detail.sheet.lineCount}
            polarity="neutral"
            comparisonLabel={`冻结于 ${formatMoment(detail.sheet.frozenAt)}`}
          />
          <StatCard
            label="已录入"
            value={progressText(detail.sheet.countedLineCount, detail.sheet.lineCount)}
            polarity="neutral"
            comparisonLabel={`进度 ${progressPercent(detail.sheet.countedLineCount, detail.sheet.lineCount)}%`}
          />
          <StatCard
            label="账面合计（冻结值）"
            value={bookText(detail.summary.bookTotal)}
            polarity="neutral"
            comparisonLabel="冻结那一刻的数，不随库存变动而改写"
          />
          <StatCard
            label="差异合计"
            value={bookText(detail.summary.varianceTotal)}
            polarity={detail.summary.varianceLineCount > 0 ? 'negative' : 'positive'}
            comparisonLabel={`${detail.summary.varianceLineCount} 行有差异`}
          />
        </div>
      )}

      {/* ---------------- 发起盘点 ---------------- */}
      <SectionCard
        title="发起盘点"
        description="发起那一刻会冻结范围内每一行 bucket 的账面数并生成盘点任务。范围与任务单元是两个独立维度：可以按整仓盘、按库位发任务。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="scope-type" label="盘点范围">
            <select
              id="scope-type"
              className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={scopeType}
              onChange={(e) => {
                setScopeType(e.target.value);
                setScopeKey('');
              }}
              disabled={!writable}
            >
              {usableScopes.map((c) => (
                <option key={c.scopeType} value={c.scopeType}>
                  {c.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="scope-key" label={scopeType === 'LOCATION' ? '库位' : '仓库'} required>
            <select
              id="scope-key"
              className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={scopeKey}
              onChange={(e) => setScopeKey(e.target.value)}
              disabled={!writable || scopeOptions.length === 0}
            >
              <option value="">请选择</option>
              {scopeOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="task-unit" label="任务单元">
            <select
              id="task-unit"
              className="border-control-border bg-background h-9 w-full rounded-md border px-2 text-sm"
              value={taskUnit}
              onChange={(e) => setTaskUnit(e.target.value)}
              disabled={!writable}
            >
              {taskUnitOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="stocktake-note" label="备注">
            <Input
              id="stocktake-note"
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="例如：月末 A 仓全盘"
              disabled={!writable}
            />
          </FormField>
        </div>
        {blockedNote && (
          <p className="text-muted-foreground mt-2 text-xs">该范围当前不可用：{blockedNote}</p>
        )}
        <div className="mt-3">
          <Button onClick={() => void submitCreate()} disabled={!writable || saving || !scopeKey}>
            {saving ? '正在冻结…' : '发起盘点并冻结账面数'}
          </Button>
          {!writable && (
            <p className="text-muted-foreground mt-2 text-xs">
              当前角色只有读权限：发起盘点会冻结账面数并生成任务，是写操作，需要
              warehouse.inventory.update。
            </p>
          )}
        </div>
        <details className="mt-3">
          <summary className="text-muted-foreground cursor-pointer text-xs">
            盘点范围为什么没有 ABC 分类？
          </summary>
          <ul className="text-muted-foreground mt-2 list-disc space-y-1 pl-4 text-xs">
            {capabilities
              .filter((c) => !c.available)
              .map((c) => (
                <li key={c.scopeType}>
                  <strong className="text-foreground">{c.label}</strong>：{c.note}
                </li>
              ))}
          </ul>
        </details>
      </SectionCard>

      {/* ---------------- 盘点单列表 ---------------- */}
      <SectionCard
        title="盘点单"
        description="进度与差异行数由明细实时算出，不另存一份进度列。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      >
        <div className="mb-3 flex flex-wrap gap-2">
          <Input
            className="max-w-xs"
            placeholder="搜单号或范围名"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPageNo(0);
            }}
          />
          <select
            className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPageNo(0);
            }}
          >
            <option value="">全部状态</option>
            {Object.entries(sheetStatusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>

        {page === null || page.sheets.length === 0 ? (
          <EmptyState
            icon={<ClipboardList className="h-5 w-5" />}
            title={loading ? '加载中' : keyword ? '没有匹配的盘点单' : '还没有盘点单'}
            description={
              loading
                ? '正在读取盘点单列表。'
                : keyword
                  ? '换个关键词，或清空搜索看全部。'
                  : '在上面选一个范围发起盘点。'
            }
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-2">单号</th>
                <th className="py-2">范围</th>
                <th className="py-2">冻结时刻</th>
                <th className="py-2 text-right">行数</th>
                <th className="py-2 text-right">已录入</th>
                <th className="py-2 text-right">账面合计</th>
                <th className="py-2 text-right">差异行</th>
                <th className="py-2">状态</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {page.sheets.map((s) => (
                <tr key={s.id} className="border-control-border border-t">
                  <td className="py-2 font-mono text-xs">{s.code}</td>
                  <td className="py-2">{s.scopeLabel}</td>
                  <td className="text-muted-foreground py-2 text-xs">{formatMoment(s.frozenAt)}</td>
                  <td className="py-2 text-right">{s.lineCount}</td>
                  <td className="py-2 text-right">
                    {progressText(s.countedLineCount, s.lineCount)}
                  </td>
                  <td className="py-2 text-right">{bookText(s.bookTotal)}</td>
                  <td className="py-2 text-right">{s.varianceLineCount}</td>
                  <td className="py-2">{sheetStatusLabels[s.status] ?? s.status}</td>
                  <td className="py-2 text-right">
                    <Button variant="outline" size="sm" onClick={() => void openSheet(s.id)}>
                      打开
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        {page && page.total > page.size && (
          <div className="mt-3 flex items-center gap-2 text-xs">
            <Button
              variant="outline"
              size="sm"
              disabled={pageNo === 0}
              onClick={() => setPageNo((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground">
              第 {pageNo + 1} 页 / 共 {page.total} 张
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={pageNo * page.size + page.size >= page.total}
              onClick={() => setPageNo((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 盘点任务 ---------------- */}
      {detail && (
        <SectionCard
          title="盘点任务"
          description={`按${
            detail.sheet.taskUnit === 'BY_SKU' ? ' SKU' : '库位'
          }下发；状态与进度由明细行派生，不会出现「任务显示完成但还有行没盘」。`}
        >
          {tasks.length === 0 ? (
            <EmptyState
              icon={<ClipboardCheck className="h-5 w-5" />}
              title="没有未盘完的任务"
              description="这张单的全部任务都已录入。"
            />
          ) : (
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">任务</th>
                  <th className="py-2 text-right">行数</th>
                  <th className="py-2 text-right">已录入</th>
                  <th className="py-2 text-right">账面</th>
                  <th className="py-2 text-right">实盘</th>
                  <th className="py-2 text-right">差异</th>
                  <th className="py-2">状态</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {tasks.map((t) => (
                  <tr key={t.id} className="border-control-border border-t">
                    <td className="py-2">
                      <span className="font-mono text-xs">{t.taskCode}</span>
                      <span className="text-muted-foreground ml-2 text-xs">{t.taskName}</span>
                    </td>
                    <td className="py-2 text-right">{t.lineCount}</td>
                    <td className="py-2 text-right">
                      {progressText(t.countedLineCount, t.lineCount)}
                    </td>
                    <td className="py-2 text-right">{bookText(t.bookTotal)}</td>
                    <td className="py-2 text-right">{qty(t.countedTotal)}</td>
                    <td className="py-2 text-right">{qty(t.varianceTotal)}</td>
                    <td className="py-2">{taskStatusLabels[t.status] ?? t.status}</td>
                    <td className="py-2 text-right">
                      <Button variant="outline" size="sm" onClick={() => void submitAssign(t)}>
                        取消派工
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </SectionCard>
      )}

      {/* ---------------- 三列明细 ---------------- */}
      {detail && (
        <SectionCard
          title={`账面 / 实盘 / 差异 · ${detail.sheet.scopeLabel}`}
          description={`冻结于 ${formatMoment(detail.sheet.frozenAt)}，共 ${detail.sheet.lineCount} 行。账面列是冻结值，之后的出入库不会改写它；差异列只读，由实盘减账面算出。`}
        >
          <div className="mb-3 flex flex-wrap gap-2">
            <select
              className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
              value={lineFilter}
              onChange={(e) => {
                setLineFilter(e.target.value);
                setLinePage(0);
              }}
            >
              {lineFilterOptions.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
            <Input
              className="max-w-xs"
              placeholder="搜 SKU / 库位 / 批次 / 条码"
              value={lineKeyword}
              onChange={(e) => {
                setLineKeyword(e.target.value);
                setLinePage(0);
              }}
            />
          </div>

          {lines === null || lines.lines.length === 0 ? (
            <EmptyState
              icon={<ScanLine className="h-5 w-5" />}
              title={drillLoading ? '加载中' : '没有匹配的行'}
              description={
                drillLoading
                  ? '正在读取盘点明细。'
                  : lineFilter === 'PENDING'
                    ? '这张单已经全部录完，没有待录入的行。'
                    : '换个筛选条件或关键词试试。'
              }
            />
          ) : (
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left text-xs">
                <tr>
                  <th className="py-2">仓库 / 库位</th>
                  <th className="py-2">SKU</th>
                  <th className="py-2">批次</th>
                  <th className="py-2">质量</th>
                  <th className="py-2 text-right">账面</th>
                  <th className="py-2 text-right">实盘</th>
                  <th className="py-2 text-right">差异</th>
                  <th className="py-2">录入</th>
                </tr>
              </thead>
              <tbody>
                {lines.lines.map((l) => (
                  <tr key={l.id} className="border-control-border border-t">
                    <td className="py-2 text-xs">
                      <span className="font-mono">{l.warehouseCode}</span> /{' '}
                      <span className="font-mono">{l.locationCode}</span>
                    </td>
                    <td className="py-2 text-xs">
                      <span className="font-mono">{l.skuCode}</span>
                      <span className="text-muted-foreground ml-2">{l.skuName}</span>
                    </td>
                    <td className="text-muted-foreground py-2 font-mono text-xs">
                      {l.lotCode || '—'}
                    </td>
                    <td className="text-muted-foreground py-2 text-xs">{l.qualityStatusCode}</td>
                    <td className="py-2 text-right font-mono">{bookText(l.bookOnHand)}</td>
                    <td className="py-2 text-right font-mono">{qty(l.countedQty)}</td>
                    <td
                      className={`py-2 text-right font-mono ${
                        varianceTone(l.variance) === 'bad'
                          ? 'text-destructive'
                          : varianceTone(l.variance) === 'warn'
                            ? 'text-warning'
                            : ''
                      }`}
                    >
                      {varianceText(l.variance)}
                    </td>
                    <td className="py-2">
                      {l.countedQty === null ? (
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={!writable}
                          onClick={() => {
                            setDraftLineId(l.id);
                            setDraftQty('');
                            setDraftBarcode('');
                          }}
                        >
                          录入实盘
                        </Button>
                      ) : (
                        <div className="flex items-center gap-2">
                          <span className="text-muted-foreground text-xs">
                            {l.countedBarcode || '手工录入'} · {formatMoment(l.countedAt)}
                          </span>
                          <Button
                            variant="outline"
                            size="sm"
                            disabled={!writable || saving}
                            onClick={() => void undoCount(l.id)}
                          >
                            撤销
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}

          {draftLineId && (
            <div className="border-control-border mt-4 rounded-md border p-3">
              <p className="text-muted-foreground mb-2 text-xs">
                录入实盘数。差异会自动算出来，不需要（也不允许）手填。条码对应蓝图里的「扫码」：
                没有扫码硬件对接，所以做成手工录入 + 条码字段，而不是伪造一个扫码流程。
              </p>
              <div className="flex flex-wrap items-end gap-2">
                <FormField id="counted-qty" label="实盘数" required>
                  <Input
                    id="counted-qty"
                    value={draftQty}
                    onChange={(e) => setDraftQty(e.target.value)}
                    className="w-32"
                    inputMode="decimal"
                  />
                </FormField>
                <FormField id="counted-barcode" label="条码（可选）">
                  <Input
                    id="counted-barcode"
                    value={draftBarcode}
                    onChange={(e) => setDraftBarcode(e.target.value)}
                    className="w-56"
                    placeholder="扫码枪扫到的条码"
                  />
                </FormField>
                <Button
                  disabled={saving}
                  onClick={() => {
                    if (draftLineId) void submitCount(draftLineId, draftQty);
                  }}
                >
                  提交
                </Button>
                <Button variant="outline" onClick={() => setDraftLineId(null)}>
                  取消
                </Button>
              </div>
            </div>
          )}

          {lines && lines.total > lines.size && (
            <div className="mt-3 flex items-center gap-2 text-xs">
              <Button
                variant="outline"
                size="sm"
                disabled={linePage === 0}
                onClick={() => setLinePage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <span className="text-muted-foreground">
                第 {linePage + 1} 页 / 共 {lines.total} 行
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={linePage * lines.size + lines.size >= lines.total}
                onClick={() => setLinePage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          )}
        </SectionCard>
      )}
    </div>
  );
}
