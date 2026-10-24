'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { BellRing, CheckCheck, RefreshCw } from 'lucide-react';
import type {
  Access,
  CrmMessagePage,
  CrmMessageRow,
  FinanceSupplierReconciliationPage,
  FinanceSupplierReconciliationRow,
  NotificationInboxCapabilities,
  NotificationReadEntry,
  NotificationReadResult,
  NotificationReadState,
  NotificationSubscriptions,
  StockoutAlertPage,
  StockoutAlertRow,
  SyncFailureView,
  TimelinessOverduePage,
  TimelinessOverdueRow,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import {
  EMPTY_FILTERS,
  NOTIFICATION_TYPES,
  NOTIFICATION_TYPE_LABELS,
  NOTIFICATION_TYPE_OWNER,
  SEVERITY_FILTER_OPTIONS,
  SEVERITY_LABELS,
  applyReadState,
  filterBySubscription,
  filterNotifications,
  formatOccurredAt,
  mergeNotifications,
  summarizeNotifications,
  unreadBadge,
  type NotificationFilters,
  type NotificationItem,
  type NotificationType,
  type Severity,
  type SourceResult,
} from './notification-inbox-model';

/**
 * 1.3.1.1 通知列表（卷 01 · L3 1.3.1 消息与预警 → {@code /notifications}）。
 *
 * <p>蓝图原文：「系统级与业务级通知的统一收件箱，区分严重级别，支持已读/未读、批量处理、
 * 按类型订阅。……**通知是投影，允许丢弃后重算**，不作为业务事实来源。」
 *
 * <p>🔴 **通知内容来自各域的只读投影，本页不复制业务数据**：
 * 缺货预警（07 库存）、履约超时（09 订单）、同步失败（02 渠道）、
 * 对账差异（12 财务）、站内信（15 客户）各自住在自己的库里，
 * 本页按它们的**授权只读端点**聚合成一条通知流。通知里只有**引用 + 跳转链接**，
 * 没有复制过来的业务事实 —— 处置动作一律回到归属系统完成。
 *
 * <p>🔴 **个人状态（已读 / 订阅）落在 identity**：按**成员**存，不按租户共享一份
 * （同事读过什么与我无关）。本页不信任任何客户端租户头，租户与数据范围
 * **全部由归属服务按已认证的成员关系在服务端判定**。
 *
 * <p>🔴 **「读不到」「没有权限」「没有数据」是三件不同的事**：
 * 混成一句话，运营就会以为「平台还没配规则」而去找错方向。
 *
 * <p>页面分五块：
 *
 * <ul>
 *   <li><b>数据源可达性</b> —— 五个来源各自读到了没有；读不到的**逐条如实报出**，
 *       不把「读不到」渲染成「0 条」。</li>
 *   <li><b>收件箱概览</b> —— 计数与**清单同源**（由清单渲染的那一批行算出，
 *       不另查一个 count 端点）。</li>
 *   <li><b>按类型订阅</b> —— 勾选哪些类型的通知进收件箱（词表由服务端下发）。</li>
 *   <li><b>筛选</b> —— 类型 / 严重度 / 已读状态 / 关键字。</li>
 *   <li><b>通知流</b> —— 逐条：类型、严重度、时间、关联单据跳转链接、已读切换，
 *       以及**限定在当前筛选集合内**的批量已读。</li>
 * </ul>
 */

/** 清单每页行数。 */
const PAGE_SIZE = 50;
/** 每个来源最多翻几页（超出部分如实报出「只取回前 N 条」）。 */
const MAX_PAGES = 5;

/**
 * 每类通知读上游投影端点所需的**读权限码**。
 *
 * <p>取自各服务控制器里 {@code access.require(bearer, "<码>")} 的**逐字**权限码。
 * 这些码**只用于判断「这个来源能不能读」**，前端**不把它当成授权凭据** ——
 * 真正的授权判定永远发生在服务端。
 */
const NOTIFICATION_TYPE_PERMISSION: Record<NotificationType, string> = {
  STOCKOUT: 'warehouse.inventory.read',
  TIMELINESS_OVERDUE: 'trade.fulfillment.read',
  SYNC_FAILURE: 'channel.sync.read',
  RECONCILIATION_DIFF: 'finance.suppliereconciliation.read',
  MESSAGE: 'crm.message.read',
};

/** 会话里的一条角色授权（形状来自 {@code auth/session} 的响应）。 */
type SessionGrant = {
  roleId: string;
  roleCode: string;
  scopeMode: string;
  permissions: string[];
};

/**
 * 会话在某权限码上**有没有读权限**。
 *
 * <p>判据与后端 {@code AccessService.require} **逐字同构**：
 * 命中某条授权时，同一条授权里必须**同时**有「动作权限」与「该动作所属页面的 {@code .read} 权限」，
 * 否则「能写不能读」这种组合会被静默放行。
 *
 * <p>🔴 这个判定只用来决定「**要不要去读这个来源**」——
 * 读不到时的提示文案才说得准（是「你没这个权限」还是「服务挂了」）。
 * 它**不是**授权：服务端会独立地再判一次。
 */
function canRead(
  grants: readonly SessionGrant[] | null | undefined,
  permission: string,
  hasDepartment: boolean,
): boolean {
  if (!grants || grants.length === 0) return false;
  const dot = permission.lastIndexOf('.');
  const siblingRead = (dot < 0 ? permission : permission.substring(0, dot)) + '.read';
  return grants.some((grant) => {
    if (!grant) return false;
    const permissions = grant.permissions ?? [];
    if (!permissions.includes(permission) || !permissions.includes(siblingRead)) return false;
    if (grant.scopeMode === 'ALL' || grant.scopeMode === 'SELF') return true;
    return grant.scopeMode === 'DEPARTMENT' && hasDepartment;
  });
}

interface Loaded<T> {
  data: T[];
  truncated: boolean;
  total: number;
  error: string;
}

/**
 * 读一个来源的**全部**行（真分页翻到底，上限 {@link MAX_PAGES} 页）。
 *
 * <p>翻页超限时**如实报出被截断** —— 少读了几条与「本来就只有这些条」是两件事。
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

function severityTone(severity: Severity): 'danger' | 'warning' | 'info' | 'neutral' {
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

export function NotificationInboxWorkspace() {
  const [ready, setReady] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notices, setNotices] = useState<string[]>([]);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [unreadable, setUnreadable] = useState<{ type: NotificationType; reason: string }[]>([]);
  const [sourceTotals, setSourceTotals] = useState<Record<string, number>>({});
  const [permitted, setPermitted] = useState<Record<string, boolean>>({});
  const [capabilities, setCapabilities] = useState<NotificationInboxCapabilities | null>(null);
  const [subscribed, setSubscribed] = useState<string[]>([]);
  const [session, setSession] = useState<Access | null>(null);
  const [dataAsOf, setDataAsOf] = useState('');

  const [type, setType] = useState('');
  const [severity, setSeverity] = useState('');
  const [readState, setReadState] = useState<'ALL' | 'UNREAD' | 'READ'>('ALL');
  const [keyword, setKeyword] = useState('');
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
      const grants = (access?.grants ?? null) as SessionGrant[] | null;
      const hasDepartment = Boolean(access?.departmentId);
      const allowed: Record<string, boolean> = {};
      for (const code of NOTIFICATION_TYPES) {
        allowed[code] = canRead(grants, NOTIFICATION_TYPE_PERMISSION[code], hasDepartment);
      }
      setPermitted(allowed);

      // 受控类型词表与批量上限由**服务端**下发（前端不自己写一份词表）。
      const caps = await api<NotificationInboxCapabilities>(
        'identity/notification-inbox/capabilities',
      );
      setCapabilities(caps);

      /** 没有读权限的来源**不去读** —— 读了必然 403，且会把「无权限」说成「服务挂了」。 */
      const read = <T,>(code: NotificationType, work: () => Promise<Loaded<T>>): Promise<Loaded<T>> =>
        allowed[code]
          ? work()
          : Promise.resolve({ data: [], total: 0, truncated: false, error: '' });

      const [stockout, overdue, syncFailed, recon, messages] = await Promise.all([
        // 07 库存：缺货预警
        read<StockoutAlertRow>('STOCKOUT', () =>
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
        // 09 订单：履约超时
        read<TimelinessOverdueRow>('TIMELINESS_OVERDUE', () =>
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
        // 02 渠道：同步失败
        read<SyncFailureView>('SYNC_FAILURE', () =>
          loadAll<SyncFailureView>(
            async () => {
              const result = await api<SyncFailureView[]>('channel/sync-failures?limit=200');
              return { items: result, total: result.length };
            },
            1,
          ),
        ),
        // 12 财务：对账差异
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
        // 15 客户：站内信
        read<CrmMessageRow>('MESSAGE', () =>
          loadAll<CrmMessageRow>(
            async (pageNo) => {
              const result = await api<CrmMessagePage>(
                `crm/message-inbox/messages?page=${pageNo}&size=${PAGE_SIZE}`,
              );
              return { items: result.items, total: result.total };
            },
            MAX_PAGES,
          ),
        ),
      ]);

      const merged = mergeNotifications({
        stockout: toSource(stockout),
        timelinessOverdue: toSource(overdue),
        syncFailure: toSource(syncFailed),
        reconciliationDiff: toSource(recon),
        message: toSource(messages),
      });

      // 上游截断**如实报出** —— 「只取回前 N 条」不是「就这么多条」。
      const truncated: [string, Loaded<unknown>][] = [
        ['库存缺货', stockout],
        ['履约超时', overdue],
        ['同步失败', syncFailed],
        ['对账差异', recon],
        ['站内信', messages],
      ];
      for (const [label, loaded] of truncated) {
        if (loaded.truncated) {
          notes.push(
            `${label}只取回前 ${loaded.data.length} 条（区间内共 ${loaded.total} 条），后面的没取回来。`,
          );
        }
      }

      // 我的订阅类型（**生效值**：没自定义过时服务端返回全部类型）。
      const subs = await api<NotificationSubscriptions>(
        'identity/notification-inbox/subscriptions',
      );
      setSubscribed(subs.types ?? []);

      // 已读状态：按**这批通知的键**去查（不是把「我读过所有」全量拉回来）。
      const keys = merged.items.map((item) => item.key);
      const readKeys = new Set<string>();
      if (keys.length > 0) {
        const state = await api<NotificationReadState>(
          'identity/notification-inbox/read-state',
          'POST',
          { keys },
        );
        for (const entry of state.read ?? []) readKeys.add(entry.key);
      }

      setItems(applyReadState(merged.items, readKeys));
      setUnreadable(merged.unreadable);
      setSourceTotals({
        STOCKOUT: stockout.total,
        TIMELINESS_OVERDUE: overdue.total,
        SYNC_FAILURE: syncFailed.total,
        RECONCILIATION_DIFF: recon.total,
        MESSAGE: messages.total,
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

  /** 标记一批通知为已读 / 未读（**只对点名的这几条生效**）。 */
  const mark = useCallback(async (keys: string[], action: 'read' | 'unread') => {
    if (keys.length === 0) return;
    try {
      // 两条路径写成**字面量**（不是模板拼出来的）：白名单的反向抽取测试
      // 要能从源码里原样抽出这两个调用点，模板变量会让它抽不出真形状。
      if (action === 'read') {
        const result = await api<NotificationReadResult>(
          'identity/notification-inbox/read',
          'POST',
          { keys },
        );
        const readKeys = new Set((result.read ?? []).map((entry: NotificationReadEntry) => entry.key));
        // 🔴 只把**服务端回报已读**的那些改成已读，其余条目**原样保留** ——
        //    把没点名的条目一并改成未读就是在乱改别人的状态。
        setItems((current) =>
          current.map((item) => (readKeys.has(item.key) ? { ...item, read: true } : item)),
        );
      } else {
        await api<NotificationReadResult>('identity/notification-inbox/unread', 'POST', { keys });
        const targets = new Set(keys);
        setItems((current) =>
          current.map((item) => (targets.has(item.key) ? { ...item, read: false } : item)),
        );
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    }
  }, []);

  /** 改订阅类型（**整体替换**，与服务端语义一致）。 */
  const changeSubscriptions = useCallback(async (types: string[]) => {
    try {
      const result = await api<NotificationSubscriptions>(
        'identity/notification-inbox/subscriptions',
        'PUT',
        { types },
      );
      setSubscribed(result.types ?? []);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败，请重试');
    }
  }, []);

  const filters: NotificationFilters = useMemo(
    () => ({ ...EMPTY_FILTERS, type, severity, readState, keyword }),
    [keyword, readState, severity, type],
  );

  /** 订阅过滤后的收件箱（= 收件箱本身）。 */
  const inbox = useMemo(() => filterBySubscription(items, subscribed), [items, subscribed]);
  /** 清单：收件箱再按界面筛选条件过滤 —— 概览与角标都从**这一份**算出。 */
  const visible = useMemo(() => filterNotifications(inbox, filters), [inbox, filters]);
  const summary = useMemo(() => summarizeNotifications(visible), [visible]);
  const inboxSummary = useMemo(() => summarizeNotifications(inbox), [inbox]);
  const pageCount = Math.max(1, Math.ceil(visible.length / PAGE_SIZE));
  const current = Math.min(page, pageCount - 1);
  const pageRows = visible.slice(current * PAGE_SIZE, current * PAGE_SIZE + PAGE_SIZE);
  const notPermittedCount = NOTIFICATION_TYPES.filter((code) => !permitted[code]).length;
  const unknownType = capabilities
    ? NOTIFICATION_TYPES.filter((code) => !capabilities.types.some((t) => t.code === code))
    : [];

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载通知收件箱…
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          通知收件箱是卷 01 的<b>只读聚合层</b>：它把 07 库存、09 订单、02 渠道、12 财务、
          15 客户五处的通知投影并成<b>一条通知流</b>。通知是<b>投影</b> ——
          允许丢弃后重算，<b>不作为业务事实来源</b>；本页不复制任何业务数据，
          每条只给一条出口：<b>跳到归属系统的处理页</b>。
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
          {NOTIFICATION_TYPES.map((code) => {
            const failure = unreadable.find((item) => item.type === code);
            const can = permitted[code] ?? true;
            return (
              <li key={code} className="text-copy-12 text-muted-foreground rounded-md border p-2">
                <Badge tone={failure ? 'warning' : can ? 'success' : 'neutral'}>
                  {failure ? '读不到' : can ? '已接通' : '无权限'}
                </Badge>
                <span className="ml-2">
                  {NOTIFICATION_TYPE_LABELS[code]} · 归属 {NOTIFICATION_TYPE_OWNER[code]} · 上游共{' '}
                  {can ? `${sourceTotals[code] ?? 0} 条` : '未读取（无权限）'}
                </span>
                {!can && (
                  <span className="ml-2">
                    （你的角色没有这一类通知的读权限 —— 这一类**对你不开放**，不等于「没有通知」）
                  </span>
                )}
                {failure && <span className="ml-2">（{failure.reason}）</span>}
              </li>
            );
          })}
        </ul>
        <p className="text-copy-12 text-muted-foreground">
          当前工作空间 <b>{session?.tenantName ?? '—'}</b>（租户来自已认证的成员关系，
          <b>不信任任何客户端租户头</b>）。数据范围<b>由归属服务按该会话在服务端判定</b>，
          本页<b>不把范围当成可信输入往下传</b>。数据截至{' '}
          {dataAsOf ? dataAsOf.replace('T', ' ').slice(0, 19) : '—'}。
        </p>
      </section>

      {/* ---------------- 收件箱概览 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">收件箱概览</h2>
        <div className="flex flex-wrap gap-2">
          <span className="rounded-md border px-3 py-2 text-copy-12">
            当前清单 <b className="ml-1">{summary.total}</b> 条
            {unreadBadge(summary) && (
              <Badge tone="info" className="ml-2">
                未读 {unreadBadge(summary)}
              </Badge>
            )}
            <span className="text-muted-foreground ml-2">
              （订阅内共 {inboxSummary.total} 条 · 已读 {summary.readCount} 条）
            </span>
          </span>
          <span className="rounded-md border px-3 py-2 text-copy-12">
            严重 <b className="ml-1">{summary.bySeverity.CRITICAL ?? 0}</b> · 高{' '}
            <b className="ml-1">{summary.bySeverity.HIGH ?? 0}</b> · 中{' '}
            <b className="ml-1">{summary.bySeverity.MEDIUM ?? 0}</b> · 低{' '}
            <b className="ml-1">{summary.bySeverity.LOW ?? 0}</b>
          </span>
          <span className="border-warning/40 bg-warning/5 rounded-md border px-3 py-2 text-copy-12">
            判不了严重度 <b className="ml-1">{summary.unknownSeverityCount}</b> 条 · 时刻判不了{' '}
            <b className="ml-1">{summary.unknownTimeCount}</b> 条
            <span className="text-muted-foreground ml-2">（上游没给判定依据，不等于低优先级）</span>
          </span>
          {unreadable.length > 0 && (
            <span className="border-destructive/30 bg-destructive/5 rounded-md border px-3 py-2 text-copy-12">
              读不到来源 <b className="ml-1">{unreadable.length}</b> 个
              <span className="text-muted-foreground ml-2">
                （这几类的通知本次**判不了**，不等于「没有通知」）
              </span>
            </span>
          )}
          {notPermittedCount > 0 && (
            <span className="rounded-md border px-3 py-2 text-copy-12">
              无权限来源 <b className="ml-1">{notPermittedCount}</b> 个
              <span className="text-muted-foreground ml-2">
                （这几类通知对你的角色不开放，不计入总数）
              </span>
            </span>
          )}
          {unknownType.length > 0 && (
            <span className="border-destructive/30 bg-destructive/5 rounded-md border px-3 py-2 text-copy-12">
              服务端词表缺 <b className="ml-1">{unknownType.length}</b> 类
              <span className="text-muted-foreground ml-2">
                （前端这一类不会被订阅，也不会出现在收件箱里）
              </span>
            </span>
          )}
        </div>
        {capabilities && (
          <p className="text-copy-12 text-muted-foreground">
            单次批量标记上限 <b>{capabilities.maxKeysPerRequest}</b> 条。
            {capabilities.boundaries.length > 0 && (
              <>
                未交付：{capabilities.boundaries.join('；')}。
              </>
            )}
          </p>
        )}
      </section>

      {/* ---------------- 按类型订阅 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-16 font-semibold">按类型订阅</h2>
        <div className="flex flex-wrap gap-3">
          {NOTIFICATION_TYPES.map((code) => {
            const checked = subscribed.includes(code);
            const isLast = checked && subscribed.length === 1;
            return (
              <label key={code} className="flex items-center gap-1 text-copy-12">
                <input
                  type="checkbox"
                  checked={checked}
                  disabled={isLast}
                  onChange={() =>
                    void changeSubscriptions(
                      checked
                        ? subscribed.filter((item) => item !== code)
                        : [...subscribed, code],
                    )
                  }
                />
                <span>{NOTIFICATION_TYPE_LABELS[code]}</span>
                <span className="text-muted-foreground">（{inboxSummary.byType[code] ?? 0} 条）</span>
              </label>
            );
          })}
        </div>
        <p className="text-copy-12 text-muted-foreground">
          订阅是<b>个人设置</b>，只影响你自己的收件箱（它不改变任何业务数据）。
          至少要订阅一类 —— 最后一类不能取消，取消后收件箱就空了。
        </p>
      </section>

      {/* ---------------- 筛选 ---------------- */}
      <section className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-1 text-copy-12">
          <span className="text-muted-foreground">类型</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={type}
            onChange={(e) => {
              setType(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部类型</option>
            {NOTIFICATION_TYPES.map((code) => (
              <option key={code} value={code}>
                {NOTIFICATION_TYPE_LABELS[code]}
              </option>
            ))}
          </select>
        </label>
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
          <span className="text-muted-foreground">已读状态</span>
          <select
            className="border rounded px-2 py-1 text-copy-12"
            value={readState}
            onChange={(e) => {
              setReadState(e.target.value as 'ALL' | 'UNREAD' | 'READ');
              setPage(0);
            }}
          >
            <option value="ALL">全部</option>
            <option value="UNREAD">未读</option>
            <option value="READ">已读</option>
          </select>
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
            placeholder="SKU / SO-… / MSG-…"
          />
        </label>
        <Button
          variant="outline"
          size="sm"
          onClick={() => {
            setType('');
            setSeverity('');
            setReadState('ALL');
            setKeyword('');
            setPage(0);
          }}
        >
          重置筛选
        </Button>
      </section>

      {/* ---------------- 通知流 ---------------- */}
      <section className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-copy-16 font-semibold">通知流</h2>
          <Button
            variant="outline"
            size="sm"
            disabled={visible.length === 0}
            onClick={() =>
              void mark(
                visible.map((item) => item.key),
                'read',
              )
            }
          >
            <CheckCheck className="h-4 w-4" />
            把当前清单标为已读（{visible.length} 条）
          </Button>
        </div>
        {visible.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              {inbox.length === 0
                ? '当前收件箱**没有**通知。'
                : '当前筛选下**没有**通知 —— 这只说明这一组筛选条件没命中，' +
                  '不代表整个收件箱是空的（把筛选重置掉看看）。'}
              {unreadable.length > 0 && (
                <>
                  <br />
                  另有 <b>{unreadable.length}</b> 个来源读不到，那几类通知本次**判不了**，
                  不能算作「没有通知」。
                </>
              )}
            </p>
          </div>
        ) : (
          <>
            <ul className="space-y-2">
              {pageRows.map((item) => (
                <li
                  key={item.key}
                  className={`bg-card rounded-lg border p-3 ${item.read ? 'opacity-70' : ''}`}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="primary">{NOTIFICATION_TYPE_LABELS[item.type]}</Badge>
                    <Badge tone={severityTone(item.severity)}>
                      {SEVERITY_LABELS[item.severity]}
                    </Badge>
                    {item.read ? <Badge tone="neutral">已读</Badge> : <Badge tone="brand">未读</Badge>}
                    <span className="font-mono text-copy-13">{item.reference}</span>
                    <span className="text-copy-12 text-muted-foreground">{item.referenceLabel}</span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    {formatOccurredAt(item.occurredAt)} · {item.detail}
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    数据范围 {item.scopeLabel} · 归属 {NOTIFICATION_TYPE_OWNER[item.type]}
                    （处置动作在归属系统完成）
                  </div>
                  <div className="mt-2 flex flex-wrap items-center gap-3">
                    <a
                      href={item.href}
                      className="text-brand text-copy-12 underline-offset-4 hover:underline"
                    >
                      {item.actionLabel} →
                    </a>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() =>
                        void mark([item.key], item.read ? 'unread' : 'read')
                      }
                    >
                      <BellRing className="h-4 w-4" />
                      {item.read ? '标为未读' : '标为已读'}
                    </Button>
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
