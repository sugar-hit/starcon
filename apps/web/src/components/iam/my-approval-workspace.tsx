'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState } from '@starcon/ui';
import { AlertTriangle, Check, RefreshCw, X } from 'lucide-react';
import type {
  ApprovalBatchItemResult,
  ApprovalBatchResult,
  ApprovalInboxCapabilities,
  ApprovalInboxDetail,
  ApprovalInboxItem,
  ApprovalInboxPage,
  ApprovalUnclaimedNodeView,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '@/lib/api';
import { useIdentity } from './identity-shell';
import {
  ACTION_LABELS,
  APPROVER_TYPE_LABELS,
  DECISION_LABELS,
  DWELL_FILTERS,
  SORTS,
  SORT_LABELS,
  STATUS_LABELS,
  amountLabel,
  batchSummary,
  buildQuery,
  dwellLabel,
  dwellTone,
  emptyFilters,
  isFiltered,
  resultLabel,
  resultTone,
  retryableItems,
  summaryLabel,
  validateFilters,
  type BatchAction,
  type InboxFilters,
} from './my-approval-model';

/**
 * 17.4.2.1 待我审批。
 *
 * <p>页面展示蓝图点名的五样东西：**单据类型、金额、申请人、停留时长、当前节点**，
 * 并提供**批量审批与转交**。筛选与排序全部交给后端（DB 侧），前端不拉全量回来排。
 *
 * <p>三条边界在界面上也要看得见，不能只写在注释里：
 *
 * <ol>
 *   <li><b>权限判定在服务端。</b>列表按当前用户的成员关系与节点审批人过滤，页面不传用户 id、
 *       也不传租户；筛选里的「申请人」只是一个可选的收窄条件，冒充不了「看别人的待办」。
 *   <li><b>审批动作在 17 执行。</b>1 工作台待办只跳到这里，不持有第二套审批逻辑。
 *   <li><b>批量逐条回报。</b>结果与请求同序同长，成功与失败都要逐条列出来 ——
 *       一条驳回不该把同批已经通过的 19 条打回待办。
 * </ol>
 *
 * <p>「无人可审」也是页面的一部分：停在岗位节点上的待办解析不出具体的人，
 * 这里如实列出来，而不是让它们在列表里安静地消失。
 */
export function MyApprovalWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState<ApprovalInboxPage | null>(null);
  const [filters, setFilters] = useState<InboxFilters>(emptyFilters());
  const [applied, setApplied] = useState<string>('');
  const [selected, setSelected] = useState<string[]>([]);
  const [comment, setComment] = useState('');
  const [transferTarget, setTransferTarget] = useState('');
  const [results, setResults] = useState<ApprovalBatchResult | null>(null);
  const [capabilities, setCapabilities] = useState<ApprovalInboxCapabilities | null>(null);
  const [detail, setDetail] = useState<ApprovalInboxDetail | null>(null);

  const readable = session.permissions.includes('identity.approval_inbox.read');
  const approvable = session.permissions.includes('identity.approval_inbox.approve');
  const transferable = session.permissions.includes('identity.approval_inbox.transfer');

  const load = useCallback(async () => {
    if (!readable) {
      // 没有读权限就不请求：空数组会被渲染成「你没有任何待办」，那是骗人。
      setReady(true);
      return;
    }
    setError('');
    try {
      const query = buildQuery(filters);
      setApplied(query);
      const data = await api<ApprovalInboxPage>(`identity/my-approvals${query}`);
      setPage(data);
      // 筛选变化后保留的选择可能已不在结果里，留着会让「批量通过 5 条」变成 3 条却不说。
      setSelected((prev) => prev.filter((id) => data.items.some((i) => i.id === id)));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '待审批加载失败，请稍后重试');
      setPage(null);
    } finally {
      setReady(true);
    }
  }, [readable, filters]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    api<ApprovalInboxCapabilities>('identity/my-approvals/capabilities')
      .then(setCapabilities)
      .catch(() => setCapabilities(null));
  }, [readable]);

  const applyFilters = useCallback(() => {
    const problems = validateFilters(filters);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    setPage(null);
    setFilters((f) => ({ ...f, page: 0 }));
  }, [filters]);

  const resetFilters = useCallback(() => {
    setError('');
    setPage(null);
    setFilters(emptyFilters());
  }, []);

  const patch = useCallback((changes: Partial<InboxFilters>) => {
    setFilters((f) => ({ ...f, ...changes }));
  }, []);

  const toggle = useCallback((id: string) => {
    setSelected((prev) => (prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]));
  }, []);

  const toggleAll = useCallback(() => {
    const ids = (page?.items ?? []).map((i) => i.id);
    setSelected((prev) => (ids.every((id) => prev.includes(id)) ? [] : ids));
  }, [page]);

  const openDetail = useCallback(async (id: string) => {
    setError('');
    try {
      setDetail(await api<ApprovalInboxDetail>(`identity/my-approvals/${id}`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '详情加载失败');
    }
  }, []);

  /** 单条动作。失败时保留列表而不是清空 —— 用户不该因为一次失败就丢掉筛选上下文。 */
  const decideOne = useCallback(
    async (id: string, decision: 'APPROVE' | 'REJECT') => {
      setBusy(true);
      setError('');
      setNotice('');
      try {
        await api(`identity/my-approvals/${id}/decide`, 'POST', { decision, comment });
        setNotice(decision === 'APPROVE' ? '已通过' : '已驳回');
        setComment('');
        setDetail(null);
        await load();
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '审批失败，请稍后重试');
      } finally {
        setBusy(false);
      }
    },
    [comment, load],
  );

  const transferOne = useCallback(
    async (id: string) => {
      setBusy(true);
      setError('');
      setNotice('');
      try {
        await api(`identity/my-approvals/${id}/transfer`, 'POST', {
          toMembershipId: transferTarget.trim() || null,
          comment,
        });
        setNotice('已转交');
        setComment('');
        setTransferTarget('');
        setDetail(null);
        await load();
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '转交失败');
      } finally {
        setBusy(false);
      }
    },
    [comment, load, transferTarget],
  );

  /**
   * 批量：逐条回报，不用「全成或全败」的提示。
   *
   * <p>失败条目留在 `results` 里而不是只弹一句话：用户要能逐条看到哪条没成、为什么。
   */
  const runBatch = useCallback(
    async (action: BatchAction) => {
      const ids = [...selected];
      if (ids.length === 0) {
        setError('请先选择要处理的待审批');
        return;
      }
      if (action === 'REJECT' && comment.trim() === '') {
        setError('批量驳回需要填写理由：申请人需要知道被打回的原因');
        return;
      }
      if (action === 'TRANSFER' && transferTarget.trim() === '') {
        setError('批量转交需要填写转交目标的成员 ID');
        return;
      }
      setBusy(true);
      setError('');
      setNotice('');
      try {
        const result = await api<ApprovalBatchResult>('identity/my-approvals/batch', 'POST', {
          action,
          items: ids.map((instanceId) => ({ instanceId })),
          comment: comment.trim() || null,
          toMembershipId: action === 'TRANSFER' ? transferTarget.trim() : null,
        });
        setResults(result);
        setNotice(batchSummary(result.succeeded, result.failed));
        setSelected([]);
        setComment('');
        setTransferTarget('');
        await load();
      } catch (e) {
        setError(e instanceof ApiError ? e.message : '批量处理失败');
      } finally {
        setBusy(false);
      }
    },
    [comment, load, selected, transferTarget],
  );

  const items = page?.items ?? [];
  const total = page?.total ?? 0;
  const summary = page?.summary;
  const unclaimed = page?.unclaimedNodes ?? [];
  const allSelected = items.length > 0 && items.every((i) => selected.includes(i.id));
  const needsApprove = approvable && selected.length > 0;
  const needsTransfer = transferable && selected.length > 0;
  const pendingBys = useMemo(
    () => capabilities?.sourceSystems.filter((s) => !s.delivered) ?? [],
    [capabilities],
  );

  if (!ready) return <p className="text-muted-foreground text-sm">正在加载待审批…</p>;
  if (!readable)
    return <p className="text-muted-foreground text-sm">当前角色没有待我审批的查看权限。</p>;

  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <h1 className="text-xl font-semibold">待我审批</h1>
        <p className="text-muted-foreground text-sm">
          按当前节点停在谁头上过滤。停留时长与排序都在数据库侧完成，页面不做二次排序。
        </p>
      </header>

      {error && (
        <p role="alert" className="text-destructive flex items-center gap-2 text-sm">
          <AlertTriangle className="h-4 w-4 shrink-0" />
          {error}
        </p>
      )}
      {notice && <p className="text-sm">{notice}</p>}

      {/* 表头汇总：把「筛选出 N 条」与「共 M 条」分开讲 */}
      <section className="grid gap-3 sm:grid-cols-3">
        <Stat label="待我审批" value={summary ? String(summary.pendingTotal) : '—'} />
        <Stat
          label="当前筛选"
          value={summaryLabel(items.length, summary?.pendingTotal ?? 0)}
          wide
        />
        <Stat label="最长停留" value={summary ? dwellLabel(summary.longestDwellSeconds) : '—'} />
      </section>

      {/* 筛选 */}
      <section className="space-y-3">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Input
            label="单据类型"
            placeholder="PURCHASE_ORDER"
            value={filters.documentTypeCode}
            onChange={(v) => patch({ documentTypeCode: v })}
          />
          <Input
            label="币种"
            placeholder="USD"
            value={filters.currencyCode}
            onChange={(v) => patch({ currencyCode: v })}
          />
          <Input
            label="金额下限"
            placeholder="1000"
            value={filters.minAmount}
            onChange={(v) => patch({ minAmount: v })}
          />
          <Input
            label="金额上限"
            placeholder="50000"
            value={filters.maxAmount}
            onChange={(v) => patch({ maxAmount: v })}
          />
          <Input
            label="来源系统"
            placeholder="trade"
            value={filters.sourceSystem}
            onChange={(v) => patch({ sourceSystem: v })}
          />
          <Input
            label="停留时长"
            value={
              filters.minDwellHours === null
                ? ''
                : String(DWELL_FILTERS.find((d) => d.hours === filters.minDwellHours)?.label ?? '')
            }
            placeholder="不限"
            onChange={(v) =>
              patch({
                minDwellHours: DWELL_FILTERS.find((d) => d.label === v.trim())?.hours ?? null,
              })
            }
          />
          <label className="grid gap-1 text-sm">
            <span className="text-muted-foreground">排序</span>
            <select
              className="bg-background rounded-md border px-3 py-2"
              value={filters.sort}
              onChange={(e) => patch({ sort: e.target.value as InboxFilters['sort'] })}
            >
              {SORTS.map((s) => (
                <option key={s} value={s}>
                  {SORT_LABELS[s]}
                </option>
              ))}
            </select>
          </label>
          <div className="flex items-end gap-2">
            <Button onClick={applyFilters} disabled={busy}>
              筛选
            </Button>
            <Button variant="ghost" onClick={resetFilters} disabled={busy}>
              重置
            </Button>
            <Button variant="ghost" onClick={() => void load()} disabled={busy} title="刷新">
              <RefreshCw className="h-4 w-4" />
            </Button>
          </div>
        </div>
      </section>

      {/* 批量操作条：没有权限的动作不出现，而不是出现一个点了就报 403 的按钮 */}
      {(approvable || transferable) && (
        <section className="space-y-3 rounded-lg border p-4">
          <div className="flex flex-wrap items-end gap-2">
            <p className="text-sm font-medium">已选 {selected.length} 条</p>
            <Input
              label="意见"
              placeholder="驳回必填；通过可留空"
              value={comment}
              onChange={setComment}
            />
            {transferable && (
              <Input
                label="转交目标成员 ID"
                placeholder="UUID，留空表示交回节点审批人"
                value={transferTarget}
                onChange={setTransferTarget}
              />
            )}
          </div>
          <div className="flex flex-wrap gap-2">
            {needsApprove && (
              <>
                <Button disabled={busy} onClick={() => void runBatch('APPROVE')}>
                  <Check className="h-4 w-4" />
                  {ACTION_LABELS.APPROVE}
                </Button>
                <Button
                  variant="destructive"
                  disabled={busy || comment.trim() === ''}
                  onClick={() => void runBatch('REJECT')}
                >
                  <X className="h-4 w-4" />
                  {ACTION_LABELS.REJECT}
                </Button>
              </>
            )}
            {needsTransfer && (
              <Button
                variant="secondary"
                disabled={busy || transferTarget.trim() === ''}
                onClick={() => void runBatch('TRANSFER')}
              >
                {ACTION_LABELS.TRANSFER}
              </Button>
            )}
            {selected.length > 0 && (
              <Button variant="ghost" onClick={() => setSelected([])} disabled={busy}>
                取消选择
              </Button>
            )}
          </div>
          {!approvable && (
            <p className="text-muted-foreground text-sm">
              当前角色只有查看权限，审批与转交动作不会出现。
            </p>
          )}
        </section>
      )}

      {/* 批量逐条结果：成功与失败都要逐条列出 */}
      {results && (
        <section className="space-y-2 rounded-lg border p-4">
          <div className="flex items-center justify-between">
            <h2 className="font-semibold">
              批量{ACTION_LABELS[results.action as BatchAction] ?? results.action}结果
            </h2>
            <span className="text-muted-foreground text-sm">
              {batchSummary(results.succeeded, results.failed)}
            </span>
          </div>
          <p className="text-muted-foreground text-sm">
            逐条回报，与提交顺序一一对应；一条失败不影响其余。
          </p>
          <ul className="space-y-1">
            {results.results.map((r: ApprovalBatchItemResult) => (
              <li key={r.instanceId} className="flex items-center gap-2 text-sm">
                <Badge tone={resultTone(r.code)}>{resultLabel(r.code)}</Badge>
                <span className="text-muted-foreground font-mono text-xs">{r.instanceId}</span>
                <span className="text-muted-foreground flex-1">{r.message}</span>
                {r.status && (
                  <span className="text-muted-foreground text-xs">
                    {STATUS_LABELS[r.status] ?? r.status}
                  </span>
                )}
              </li>
            ))}
          </ul>
          {retryableItems(results.results).length > 0 && (
            <p className="text-muted-foreground text-sm">
              有 {retryableItems(results.results).length} 条未处理成功，可在列表里重新选中再试。
            </p>
          )}
        </section>
      )}

      {/* 无人可审：如实报出，不让这些单子静默消失 */}
      {unclaimed.length > 0 && <Unclaimed nodes={unclaimed} />}

      {/* 列表 */}
      {items.length === 0 ? (
        isFiltered(filters) ? (
          <EmptyState
            title="没有符合条件的待审批"
            description="当前筛选条件下没有匹配项，放宽条件再看看。"
            action={<Button onClick={resetFilters}>清空筛选</Button>}
          />
        ) : (
          <EmptyState
            title="当前没有需要你审批的单据"
            description="分派到你名下的待办会出现在这里。"
          />
        )
      ) : (
        <section className="space-y-2">
          <label className="text-muted-foreground flex items-center gap-2 text-sm">
            <input type="checkbox" checked={allSelected} onChange={toggleAll} />
            全选本页（{items.length} 条）
          </label>
          <ul className="space-y-2">
            {items.map((item) => (
              <Row
                key={item.id}
                item={item}
                selected={selected.includes(item.id)}
                onToggle={() => toggle(item.id)}
                onOpen={() => void openDetail(item.id)}
                approvable={approvable}
                transferable={transferable}
                busy={busy}
                onDecide={(decision) => void decideOne(item.id, decision)}
                onTransfer={() => void transferOne(item.id)}
              />
            ))}
          </ul>
          <Pager
            page={filters.page}
            size={filters.size}
            total={total}
            onChange={(p) => {
              setPage(null);
              patch({ page: p });
            }}
          />
        </section>
      )}

      {/* 对接状态：没接上的业务系统如实列出来 */}
      {capabilities && pendingBys.length > 0 && (
        <section className="space-y-2 rounded-lg border p-4">
          <h2 className="font-semibold">提交入口对接状态</h2>
          <p className="text-muted-foreground text-sm">
            {capabilities.intakeContract} —— {capabilities.approvedBy}
          </p>
          <ul className="space-y-1">
            {pendingBys.map((s) => (
              <li key={s.sourceSystem} className="flex flex-wrap items-center gap-2 text-sm">
                <Badge tone="warning">未交付</Badge>
                <span className="font-medium">{s.sourceSystem}</span>
                <span className="text-muted-foreground">{s.owningModule}</span>
                <span className="text-muted-foreground flex-1">{s.note}</span>
              </li>
            ))}
          </ul>
          {capabilities.observed.length > 0 && (
            <p className="text-muted-foreground text-sm">
              已登记实例：
              {capabilities.observed
                .map((o) => `${o.sourceSystem} ${o.pendingCount}/${o.instanceCount} 在办`)
                .join('，')}
            </p>
          )}
        </section>
      )}

      {detail && (
        <Detail
          detail={detail}
          busy={busy}
          comment={comment}
          transferTarget={transferTarget}
          approvable={approvable}
          transferable={transferable}
          onComment={setComment}
          onTransferTarget={setTransferTarget}
          onDecide={(decision) => void decideOne(detail.instance.id, decision)}
          onTransfer={() => void transferOne(detail.instance.id)}
          onClose={() => setDetail(null)}
        />
      )}

      {applied && <p className="text-muted-foreground text-xs">当前查询：{applied}</p>}
    </div>
  );
}

function Stat({ label, value, wide }: { label: string; value: string; wide?: boolean }) {
  return (
    <div className={`bg-card rounded-lg border p-4 ${wide ? 'sm:col-span-1' : ''}`}>
      <p className="text-muted-foreground text-xs">{label}</p>
      <p className="mt-1 text-lg font-semibold">{value}</p>
    </div>
  );
}

function Input({
  label,
  value,
  onChange,
  placeholder,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
}) {
  return (
    <label className="grid gap-1 text-sm">
      <span className="text-muted-foreground">{label}</span>
      <input
        className="bg-background rounded-md border px-3 py-2"
        value={value}
        placeholder={placeholder}
        onChange={(e) => onChange(e.target.value)}
      />
    </label>
  );
}

function Row({
  item,
  selected,
  onToggle,
  onOpen,
  approvable,
  transferable,
  busy,
  onDecide,
  onTransfer,
}: {
  item: ApprovalInboxItem;
  selected: boolean;
  onToggle: () => void;
  onOpen: () => void;
  approvable: boolean;
  transferable: boolean;
  busy: boolean;
  onDecide: (decision: 'APPROVE' | 'REJECT') => void;
  onTransfer: () => void;
}) {
  return (
    <li className="bg-card space-y-2 rounded-lg border p-4">
      <div className="flex flex-wrap items-start gap-3">
        <input
          type="checkbox"
          checked={selected}
          onChange={onToggle}
          className="mt-1"
          aria-label={`选择 ${item.subjectTitle}`}
        />
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" className="font-medium" onClick={onOpen}>
              {item.subjectTitle}
            </button>
            <Badge tone="neutral">{item.documentTypeName || item.documentTypeCode}</Badge>
            <Badge tone={dwellTone(item.dwellSeconds)}>停留 {dwellLabel(item.dwellSeconds)}</Badge>
            {item.assignedToId && <Badge tone="primary">已转交给我</Badge>}
          </div>
          <dl className="text-muted-foreground mt-2 grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2 lg:grid-cols-4">
            <Cell label="单据类型" value={item.documentTypeCode} />
            <Cell label="金额" value={amountLabel(item.amount, item.currencyCode)} />
            <Cell label="申请人" value={item.applicantName ?? item.applicantId} />
            <Cell
              label="当前节点"
              value={`${item.currentNodeName}（${APPROVER_TYPE_LABELS[item.approverType] ?? item.approverType}）`}
            />
            <Cell label="审批单号" value={item.instanceNo} />
            <Cell label="来源" value={`${item.sourceSystem} · ${item.sourceReferenceId}`} />
            <Cell label="流程" value={item.flowName} />
            <Cell label="申请人已等" value={dwellLabel(item.totalSeconds)} />
          </dl>
        </div>
      </div>
      {(approvable || transferable) && (
        <div className="flex flex-wrap gap-2">
          {approvable && (
            <>
              <Button size="sm" disabled={busy} onClick={() => onDecide('APPROVE')}>
                {DECISION_LABELS.APPROVE}
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={busy}
                onClick={() => onDecide('REJECT')}
              >
                {DECISION_LABELS.REJECT}
              </Button>
            </>
          )}
          {transferable && (
            <Button size="sm" variant="secondary" disabled={busy} onClick={onTransfer}>
              转交
            </Button>
          )}
        </div>
      )}
    </li>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex gap-1">
      <dt>{label}</dt>
      <dd className="truncate font-medium">{value}</dd>
    </div>
  );
}

function Unclaimed({ nodes }: { nodes: ApprovalUnclaimedNodeView[] }) {
  return (
    <section className="space-y-2 rounded-lg border border-dashed p-4">
      <h2 className="font-semibold">无人可审的节点（{nodes.length}）</h2>
      <p className="text-muted-foreground text-sm">
        这些待办停在「按岗位」节点上，而岗位字典不带成员绑定，因此解析不出具体审批人。
        它们不会出现在任何人的列表里 —— 需要在审批流里把该节点改成角色或指定人。
      </p>
      <ul className="space-y-1">
        {nodes.map((n) => (
          <li key={`${n.flowName}:${n.nodeKey}`} className="flex items-center gap-2 text-sm">
            <Badge tone="warning">{n.pendingCount} 条</Badge>
            <span className="font-medium">{n.nodeName}</span>
            <span className="text-muted-foreground">{n.flowName}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Detail({
  detail,
  busy,
  comment,
  transferTarget,
  approvable,
  transferable,
  onComment,
  onTransferTarget,
  onDecide,
  onTransfer,
  onClose,
}: {
  detail: ApprovalInboxDetail;
  busy: boolean;
  comment: string;
  transferTarget: string;
  approvable: boolean;
  transferable: boolean;
  onComment: (value: string) => void;
  onTransferTarget: (value: string) => void;
  onDecide: (decision: 'APPROVE' | 'REJECT') => void;
  onTransfer: () => void;
  onClose: () => void;
}) {
  const item = detail.instance;
  return (
    <section className="space-y-3 rounded-lg border p-4">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">{item.subjectTitle}</h2>
          <p className="text-muted-foreground text-sm">
            {item.instanceNo} · {item.documentTypeName || item.documentTypeCode}
          </p>
        </div>
        <Button variant="ghost" size="sm" onClick={onClose}>
          关闭
        </Button>
      </div>
      <dl className="grid gap-x-6 gap-y-1 text-sm sm:grid-cols-2">
        <Cell label="金额" value={amountLabel(item.amount, item.currencyCode)} />
        <Cell label="申请人" value={item.applicantName ?? item.applicantId} />
        <Cell
          label="当前节点"
          value={`${item.currentNodeName}（第 ${item.currentNodeOrder} 级）`}
        />
        <Cell
          label="审批人判定"
          value={APPROVER_TYPE_LABELS[item.approverType] ?? item.approverType}
        />
        <Cell label="在本节点停留" value={dwellLabel(item.dwellSeconds)} />
        <Cell label="提交至今" value={dwellLabel(item.totalSeconds)} />
        <Cell label="审批流" value={item.flowName} />
        <Cell label="转交给" value={detail.assignedToName ?? item.assignedToId ?? '未转交'} />
      </dl>
      {(approvable || transferable) && (
        <div className="flex flex-wrap items-end gap-2">
          <Input label="意见" value={comment} onChange={onComment} placeholder="驳回必填" />
          {transferable && (
            <Input
              label="转交目标成员 ID"
              value={transferTarget}
              onChange={onTransferTarget}
              placeholder="留空表示交回节点审批人"
            />
          )}
          {approvable && (
            <>
              <Button size="sm" disabled={busy} onClick={() => onDecide('APPROVE')}>
                {DECISION_LABELS.APPROVE}
              </Button>
              <Button
                size="sm"
                variant="destructive"
                disabled={busy || comment.trim() === ''}
                onClick={() => onDecide('REJECT')}
              >
                {DECISION_LABELS.REJECT}
              </Button>
            </>
          )}
          {transferable && (
            <Button
              size="sm"
              variant="secondary"
              disabled={busy || transferTarget.trim() === ''}
              onClick={onTransfer}
            >
              转交
            </Button>
          )}
        </div>
      )}
    </section>
  );
}

function Pager({
  page,
  size,
  total,
  onChange,
}: {
  page: number;
  size: number;
  total: number;
  onChange: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / size));
  if (pages <= 1) return null;
  return (
    <div className="flex items-center gap-2 text-sm">
      <Button variant="ghost" size="sm" disabled={page <= 0} onClick={() => onChange(page - 1)}>
        上一页
      </Button>
      <span className="text-muted-foreground">
        第 {page + 1} / {pages} 页
      </span>
      <Button
        variant="ghost"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}
