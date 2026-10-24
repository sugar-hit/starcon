'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import type {
  Access,
  ApprovalTodoItem,
  ApprovalTodoPage,
  CrmMessagePage,
  CrmMessageRow,
  FinanceSupplierReconciliationPage,
  FinanceSupplierReconciliationRow,
  PendingReviewPage,
  PendingReviewRow,
  SafetyStockAlertPage,
  SafetyStockAlertRow,
  StockoutAlertPage,
  StockoutAlertRow,
  SyncFailureView,
  TimelinessOverduePage,
  TimelinessOverdueRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import {
  EMPTY_FILTERS,
  SCOPE_MODE_LABELS,
  SEVERITY_FILTER_OPTIONS,
  SEVERITY_LABELS,
  TODO_TYPE_LABELS,
  TODO_TYPE_OWNER,
  TODO_TYPE_SOURCE,
  TODO_TYPES,
  filterTodos,
  formatDwell,
  mergeTodos,
  sourceAvailabilities,
  summarizeTodos,
  unreadBadge,
  type MyTodo,
  type SourceAvailability,
  type SourceResult,
  type TodoFilters,
  type TodoType,
} from './my-todo-model';

/**
 * 1.1.1.1 我的待办（卷 01 · L3 1.1.1 待办与待决策的第一格）。
 *
 * <p>蓝图原文：「把散落在 12 个系统的『需要人处理』的动作收敛成一条**按紧急度排序**的队列，
 * 每项都有明确的下一步入口。覆盖：超期未发货、待审订单、库存缺口、待处理同步失败、
 * 待核对账差异、待回复站内信、待审批单据、库存低于安全线。」
 *
 * <p>🔴 **本页面不写任何业务数据**。卷 01 的边界写死：「工作台只读 + 跳转；
 * 一切状态变更回到归属系统完成」。所以这里没有认领、没有完成、没有转交 ——
 * 每张卡片只有一条**出口**：跳到归属系统的处理页并带上定位参数。
 *
 * <p>🔴 **租户与数据范围不是前端说了算**：
 * 本页**不发送** {@code X-Tenant-Id}（浏览器也发不了 —— 代理只转发会话 cookie，
 * 不转发任何租户头），租户与法人/店铺/仓库/部门范围**全部由归属服务按
 * 已认证的成员关系在服务端判定**（每个服务的 {@code AccessService.require}
 * 从会话解析出可信租户，再开事务绑定 RLS 租户上下文）。
 * 前端**不把范围当成可信输入往下传** —— 它只读各服务已经按范围过滤好的投影。
 *
 * <p>页面分五块：
 *
 * <ul>
 *   <li><b>数据源可达性</b>—— 八个来源各自读到了没有；读不到的**逐条如实报出**，
 *       不把「读不到」渲染成「0 条」。</li>
 *   <li><b>待办概览</b>—— 总数 / 未读角标 / 最久停留 / 判不了严重度的条数。</li>
 *   <li><b>按类型筛</b>—— 八类各自的条数，点一下就按这一类筛。</li>
 *   <li><b>筛选</b>—— 严重度 / 停留时长 / 关键字。</li>
 *   <li><b>待办队列</b>—— 按紧急度排序的卡片：类型、单据引用、严重度、停留时长、
 *       下一步入口。</li>
 * </ul>
 *
 * <p>「读不到」「没有待办」「待办都判不出严重度」是三件不同的事：
 * 混成一句话，运营就会以为「平台还没配规则」而去找错方向。
 */

/** 清单每页行数。 */
const PAGE_SIZE = 50;
/** 每个来源最多翻几页（超出部分如实报出「只取回前 N 条」）。 */
const MAX_PAGES = 5;

interface Loaded<T> {
  data: T[];
  truncated: boolean;
  total: number;
  error: string;
}

/**
 * 读一个来源的**全部**行（真分页翻到底，上限 {@link MAX_PAGES} 页）。
 *
 * <p>翻页超限时**如实报出**被截断 —— 少读了几条与「本来就只有这些条」是两件事。
 */
async function loadAll<T>(
  fetchPage: (page: number) => Promise<{ items: T[]; total: number }>,
  maxPages: number,
): Promise<Loaded<T>> {
  const items: T[] = [];
  let total = 0;
  try {
    for (let page = 0; page < maxPages; page += 1) {
      const result = await fetchPage(page);
      total = result.total;
      items.push(...result.items);
      if (items.length >= total || result.items.length === 0) break;
    }
  } catch (e) {
    return {
      data: items,
      truncated: total > items.length,
      total,
      error: e instanceof Error ? e.message : '读取失败',
    };
  }
  return { data: items, truncated: total > items.length, total, error: '' };
}

/** 把 {@link Loaded} 折成模型的 {@link SourceResult}。 */
function toSource<T>(loaded: Loaded<T>): SourceResult<T> {
  return { rows: loaded.data, total: loaded.total, error: loaded.error };
}

function severityTone(severity: string): 'danger' | 'warning' | 'info' | 'neutral' {
  switch (severity) {
    case 'CRITICAL':
      return 'danger';
    case 'HIGH':
      return 'warning';
    case 'MEDIUM':
      return 'info';
    default:
      return 'neutral';
  }
}

export function MyTodoWorkspace() {
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notices, setNotices] = useState<string[]>([]);
  const [todos, setTodos] = useState<MyTodo[]>([]);
  const [unreadable, setUnreadable] = useState<{ type: TodoType; reason: string }[]>([]);
  const [sourceTotals, setSourceTotals] = useState<Record<string, number>>({});
  const [availability, setAvailability] = useState<Record<TodoType, SourceAvailability> | null>(
    null,
  );
  const [session, setSession] = useState<Access | null>(null);
  const [dataAsOf, setDataAsOf] = useState('');

  const [type, setType] = useState('');
  const [severity, setSeverity] = useState('');
  const [keyword, setKeyword] = useState('');
  const [minDwellHours, setMinDwellHours] = useState('0');
  const [page, setPage] = useState(0);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    const notes: string[] = [];
    try {
      // 会话是**唯一**的租户与授权来源：它由身份服务按已认证的成员关系下发，
      // 浏览器**不能**自己声明租户或数据范围（代理也不转发任何租户头）。
      const access = await api<Access>('auth/session');
      setSession(access);
      const avail = sourceAvailabilities(access);
      setAvailability(avail);

      /** 没有读权限的来源**不去读** —— 读了必然 403，且会把「无权限」说成「服务挂了」。 */
      const read = <T,>(type: TodoType, work: () => Promise<Loaded<T>>): Promise<Loaded<T>> =>
        avail[type].permitted
          ? work()
          : Promise.resolve({ data: [], total: 0, truncated: false, error: '' });

      const [overdue, review, shortage, syncFailed, recon, messages, approval, safety] =
        await Promise.all([
          // 09 订单：超期未发货
          read<TimelinessOverdueRow>('ORDER_OVERDUE', () =>
            loadAll<TimelinessOverdueRow>(
              async (pageNo) => {
                const result = await api<TimelinessOverduePage>(
                  `trade/fulfillment/timeliness/overdue?page=${pageNo}&size=${PAGE_SIZE}`,
                );
                return { items: result.items, total: result.total };
              },
              MAX_PAGES,
            ),
          ),
          // 09 订单：待审订单
          read<PendingReviewRow>('ORDER_PENDING_REVIEW', () =>
            loadAll<PendingReviewRow>(
              async (pageNo) => {
                const result = await api<PendingReviewPage>(
                  `trade/order-pending-review/queue?page=${pageNo}&size=${PAGE_SIZE}`,
                );
                return { items: result.items, total: result.total };
              },
              MAX_PAGES,
            ),
          ),
          // 07 库存：库存缺口
          read<StockoutAlertRow>('STOCK_SHORTAGE', () =>
            loadAll<StockoutAlertRow>(
              async (pageNo) => {
                const result = await api<StockoutAlertPage>(
                  `warehouse/inventory/stockout-alerts/stockouts?page=${pageNo}&size=${PAGE_SIZE}`,
                );
                return { items: result.rows, total: result.total };
              },
              MAX_PAGES,
            ),
          ),
          // 02 渠道：待处理同步失败
          read<SyncFailureView>('SYNC_FAILED', () =>
            loadAll<SyncFailureView>(
              async () => {
                const result = await api<SyncFailureView[]>('channel/sync-failures?limit=200');
                return { items: result, total: result.length };
              },
              1,
            ),
          ),
          // 12 财务：待核对账差异
          read<FinanceSupplierReconciliationRow>('RECONCILIATION_DIFF', () =>
            loadAll<FinanceSupplierReconciliationRow>(
              async (pageNo) => {
                const result = await api<FinanceSupplierReconciliationPage>(
                  `finance/supplier-reconciliation?page=${pageNo}&size=${PAGE_SIZE}`,
                );
                return { items: result.rows, total: result.total };
              },
              MAX_PAGES,
            ),
          ),
          // 15 客户：待回复站内信
          read<CrmMessageRow>('MESSAGE_UNREAD', () =>
            loadAll<CrmMessageRow>(
              async (pageNo) => {
                const result = await api<CrmMessagePage>(
                  `crm/message-inbox/messages?readStatus=UNREAD&page=${pageNo}&size=${PAGE_SIZE}`,
                );
                return { items: result.items, total: result.total };
              },
              MAX_PAGES,
            ),
          ),
          // 17 审批：待审批单据
          read<ApprovalTodoItem>('APPROVAL_PENDING', () =>
            loadAll<ApprovalTodoItem>(
              async (pageNo) => {
                const result = await api<ApprovalTodoPage>(
                  `identity/approval-todos?page=${pageNo}&size=${PAGE_SIZE}`,
                );
                return { items: result.items, total: result.total };
              },
              MAX_PAGES,
            ),
          ),
          // 07 库存：库存低于安全线
          read<SafetyStockAlertRow>('SAFETY_STOCK_BELOW', () =>
            loadAll<SafetyStockAlertRow>(
              async (pageNo) => {
                const result = await api<SafetyStockAlertPage>(
                  `warehouse/inventory/safety-stock/alerts?page=${pageNo}&size=${PAGE_SIZE}`,
                );
                return { items: result.alerts, total: result.total };
              },
              MAX_PAGES,
            ),
          ),
        ]);

      const merged = mergeTodos(
        {
          orderOverdue: toSource(overdue),
          pendingReview: toSource(review),
          stockShortage: toSource(shortage),
          syncFailed: toSource(syncFailed),
          reconciliationDiff: toSource(recon),
          messageUnread: toSource(messages),
          approvalPending: toSource(approval),
          safetyStockBelow: toSource(safety),
        },
        Date.now(),
      );

      // 上游截断**如实报出** —— 「只取回前 N 条」不是「就这么多条」。
      const truncated: [string, Loaded<unknown>][] = [
        ['超期未发货', overdue],
        ['待审订单', review],
        ['库存缺口', shortage],
        ['待核对账差异', recon],
        ['待回复站内信', messages],
        ['待审批单据', approval],
        ['库存低于安全线', safety],
      ];
      for (const [label, loaded] of truncated) {
        if (loaded.truncated) {
          notes.push(
            `${label}只取回前 ${loaded.data.length} 条（区间内共 ${loaded.total} 条），后面的没取回来。`,
          );
        }
      }

      setTodos(merged.todos);
      setUnreadable(merged.unreadable);
      setSourceTotals({
        ORDER_OVERDUE: overdue.total,
        ORDER_PENDING_REVIEW: review.total,
        STOCK_SHORTAGE: shortage.total,
        SYNC_FAILED: syncFailed.total,
        RECONCILIATION_DIFF: recon.total,
        MESSAGE_UNREAD: messages.total,
        APPROVAL_PENDING: approval.total,
        SAFETY_STOCK_BELOW: safety.total,
      });
      setDataAsOf(new Date().toISOString());
      setNotices(notes);
      setPage(0);
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setBusy(false);
      setReady(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const filters: TodoFilters = useMemo(
    () => ({
      ...EMPTY_FILTERS,
      type,
      severity,
      keyword,
      minDwellHours: Number(minDwellHours) || 0,
    }),
    [keyword, minDwellHours, severity, type],
  );

  const visible = useMemo(() => filterTodos(todos, filters), [todos, filters]);
  const summary = useMemo(() => summarizeTodos(todos, unreadable.length), [todos, unreadable.length]);
  const pageCount = Math.max(1, Math.ceil(visible.length / PAGE_SIZE));
  const current = Math.min(page, pageCount - 1);
  const pageRows = visible.slice(current * PAGE_SIZE, current * PAGE_SIZE + PAGE_SIZE);
  const notPermittedCount = availability
    ? TODO_TYPES.filter((code) => !availability[code].permitted).length
    : 0;

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载我的待办…
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          我的待办是卷 01 的<b>只读聚合层</b>：它把 09 订单、07 库存、02 渠道、12 财务、
          15 客户、17 审批六处的待办投影并成<b>一条按紧急度排序</b>的队列。
          本页<b>不产生任何业务写入</b> —— 每张卡片只给一条出口：
          <b>跳到归属系统的处理页并带上定位参数</b>，处置动作一律回到归属系统完成。
        </p>
        <Button variant="ghost" size="sm" onClick={() => void load()} disabled={busy}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notices.map((notice) => (
        <p
          key={notice}
          role="status"
          className="border-warning/40 bg-warning/5 rounded-md border p-3 text-copy-12"
        >
          {notice}
        </p>
      ))}

      {/* ---------------- 数据源可达性 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">数据源可达性</h2>
        <ul className="space-y-1">
          {TODO_TYPES.map((code) => {
            const failure = unreadable.find((item) => item.type === code);
            const avail = availability?.[code];
            const permitted = avail?.permitted ?? true;
            return (
              <li key={code} className="text-copy-12 text-muted-foreground rounded-md border p-2">
                <Badge tone={failure ? 'warning' : permitted ? 'success' : 'neutral'}>
                  {failure ? '读不到' : permitted ? '已接通' : '无权限'}
                </Badge>
                <span className="ml-2">
                  {TODO_TYPE_LABELS[code]} · 归属 {TODO_TYPE_OWNER[code]} · 上游共{' '}
                  {permitted ? `${sourceTotals[code] ?? 0} 条` : '未读取（无权限）'}
                </span>
                <span className="text-muted-foreground ml-2 font-mono text-copy-12">
                  {TODO_TYPE_SOURCE[code]}
                </span>
                {permitted && avail && (
                  <span className="ml-2">· 数据范围 {SCOPE_MODE_LABELS[avail.scopeMode]}</span>
                )}
                {!permitted && (
                  <span className="ml-2">
                    （你的角色没有这一类待办的读权限 —— 这一类**对你不开放**，不等于「没有待办」）
                  </span>
                )}
                {failure && <span className="ml-2">（{failure.reason}）</span>}
              </li>
            );
          })}
        </ul>
        <p className="text-copy-12 text-muted-foreground">
          当前工作空间 <b>{session?.tenantName ?? '—'}</b>（租户来自已认证的成员关系，
          <b>不信任任何客户端租户头</b>）。法人/店铺/仓库/部门的数据范围
          <b>由归属服务按该会话在服务端判定</b>（每个服务的 AccessService 先解析可信租户、
          再开事务绑定 RLS 租户上下文），本页<b>不把范围当成可信输入往下传</b> ——
          它只读各服务**已经按范围过滤好**的投影。
          数据截至 {dataAsOf ? dataAsOf.replace('T', ' ').slice(0, 19) : '—'}。
        </p>
      </section>

      {/* ---------------- 概览 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">待办概览</h2>
        <div className="flex flex-wrap gap-2">
          <span className="rounded-md border px-3 py-2 text-copy-12">
            待办总数 <b className="ml-1">{summary.total}</b> 条
            {unreadBadge(summary) && (
              <Badge tone="info" className="ml-2">
                未读 {unreadBadge(summary)}
              </Badge>
            )}
          </span>
          <span className="rounded-md border px-3 py-2 text-copy-12">
            最久停留{' '}
            <b className="ml-1">
              {summary.longestDwellSeconds === null
                ? '判不了'
                : formatDwell(summary.longestDwellSeconds)}
            </b>
          </span>
          <span className="rounded-md border px-3 py-2 text-copy-12">
            严重 <b className="ml-1">{summary.bySeverity.CRITICAL ?? 0}</b> · 高{' '}
            <b className="ml-1">{summary.bySeverity.HIGH ?? 0}</b> · 中{' '}
            <b className="ml-1">{summary.bySeverity.MEDIUM ?? 0}</b> · 低{' '}
            <b className="ml-1">{summary.bySeverity.LOW ?? 0}</b>
          </span>
          <span className="border-warning/40 bg-warning/5 rounded-md border px-3 py-2 text-copy-12">
            判不了严重度 <b className="ml-1">{summary.unknownSeverityCount}</b> 条
            <span className="text-muted-foreground ml-2">（上游没给判定依据，不等于低优先级）</span>
          </span>
          {unreadable.length > 0 && (
            <span className="border-destructive/30 bg-destructive/5 rounded-md border px-3 py-2 text-copy-12">
              读不到来源 <b className="ml-1">{unreadable.length}</b> 个
              <span className="text-muted-foreground ml-2">
                （这几类的待办本次**判不了**，不等于「没有待办」）
              </span>
            </span>
          )}
          {notPermittedCount > 0 && (
            <span className="rounded-md border px-3 py-2 text-copy-12">
              无权限来源 <b className="ml-1">{notPermittedCount}</b> 个
              <span className="text-muted-foreground ml-2">
                （这几类待办对你的角色不开放，不计入总数）
              </span>
            </span>
          )}
        </div>
      </section>

      {/* ---------------- 按类型筛 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">按类型筛</h2>
        <div className="flex flex-wrap gap-2">
          {TODO_TYPES.map((code) => (
            <button
              key={code}
              type="button"
              onClick={() => {
                setType((currentType) => (currentType === code ? '' : code));
                setPage(0);
              }}
              className={`rounded-md border px-3 py-2 text-left text-copy-12 ${
                type === code ? 'border-primary bg-primary/5' : ''
              }`}
            >
              <span className="text-muted-foreground">{TODO_TYPE_LABELS[code]}</span>
              <span className="ml-2 font-medium">{summary.byType[code] ?? 0} 条</span>
            </button>
          ))}
        </div>
      </section>

      {/* ---------------- 筛选 ---------------- */}
      <section className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">严重度</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={severity}
            onChange={(e) => {
              setSeverity(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部严重度</option>
            {SEVERITY_FILTER_OPTIONS.map((code) => (
              <option key={code} value={code}>
                {SEVERITY_LABELS[code]}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">停留时长下限（小时）</span>
          <input
            className="border rounded px-2 py-1 text-copy-12"
            value={minDwellHours}
            inputMode="numeric"
            onChange={(e) => {
              setMinDwellHours(e.target.value);
              setPage(0);
            }}
            placeholder="不限"
          />
        </label>
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">单据引用 / 说明</span>
          <input
            className="border rounded px-2 py-1 text-copy-12"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
            placeholder="SO-… / MSG-…"
          />
        </label>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setType('');
            setSeverity('');
            setKeyword('');
            setMinDwellHours('0');
            setPage(0);
          }}
        >
          重置筛选
        </Button>
      </section>

      {/* ---------------- 待办队列 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">待办队列</h2>
        {visible.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {todos.length === 0
                ? '当前**没有**待办。'
                : '当前筛选下**没有**待办 —— 这只说明这一组筛选条件没命中，' +
                  '不代表整个队列是空的（把筛选重置掉看看）。'}
              {unreadable.length > 0 && (
                <>
                  <br />
                  另有 <b>{unreadable.length}</b> 个来源读不到，那几类待办本次**判不了**，
                  不能算作「没有待办」。
                </>
              )}
            </p>
          </div>
        ) : (
          <>
            <ul className="space-y-2">
              {pageRows.map((todo) => (
                <li key={todo.key} className="bg-card rounded-lg border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="primary">{TODO_TYPE_LABELS[todo.type]}</Badge>
                    <Badge tone={severityTone(todo.severity)}>
                      {SEVERITY_LABELS[todo.severity]}
                    </Badge>
                    <span className="font-mono text-copy-13">{todo.reference}</span>
                    <span className="text-copy-12 text-muted-foreground">{todo.referenceLabel}</span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">{todo.detail}</div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    停留 {formatDwell(todo.dwellSeconds)} · 数据范围 {todo.scopeLabel}
                  </div>
                  <div className="mt-2">
                    <a
                      href={todo.href}
                      className="text-brand text-copy-12 underline-offset-4 hover:underline"
                    >
                      {todo.actionLabel} →
                    </a>
                    <span className="text-copy-12 text-muted-foreground ml-2">
                      归属 {TODO_TYPE_OWNER[todo.type]}（处置动作在归属系统完成）
                    </span>
                  </div>
                </li>
              ))}
            </ul>
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={current <= 0}
                onClick={() => setPage((value) => Math.max(0, value - 1))}
              >
                上一页
              </Button>
              <span className="text-copy-12 text-muted-foreground">
                第 {current + 1} / {pageCount} 页 · 命中 {visible.length} 条
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={current >= pageCount - 1}
                onClick={() => setPage((value) => Math.min(pageCount - 1, value + 1))}
              >
                下一页
              </Button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
