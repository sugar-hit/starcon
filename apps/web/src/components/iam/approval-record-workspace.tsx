'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button, EmptyState, Input } from '@starcon/ui';
import { Download, FileSearch, RefreshCw } from 'lucide-react';
import type {
  ApprovalRecordCapabilities,
  ApprovalRecordDetail,
  ApprovalRecordPage,
  ApprovalRecordStatistics,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError, api } from '@/lib/api';
import { useIdentity } from './identity-shell';
import {
  RECORD_ACTIONS,
  RECORD_STATUSES,
  SORT_LABELS,
  SORTS,
  actionLabel,
  actionTone,
  amountLabel,
  buildExportQuery,
  buildQuery,
  durationLabel,
  durationTone,
  emptyFilters,
  isFiltered,
  rateLabel,
  statusLabel,
  statusTone,
  summaryLabel,
  timelineHeadline,
  topKey,
  validateFilters,
  type RecordFilters,
  type Sort,
  type Tone,
} from './approval-record-model';

/**
 * 17.4.2.2 审批记录。
 *
 * <p>页面呈现蓝图点名的全部内容：**发起、流转节点、各节点意见与时间、转交、驳回、最终结果**，
 * 外加**统计报表**与**审计导出**。
 *
 * <p>三条边界在界面上也要看得见，不能只写在注释里：
 *
 * <ol>
 *   <li><b>只读。</b>这里没有任何审批动作按钮。「审批记录不可修改」是权限级硬保证
 *       （上游只给 {@code approval_instance_action} 授 SELECT,INSERT），
 *       所以页面连一个看起来能点的入口都不给 —— 给一个点了必然 405 的按钮是骗人。
 *   <li><b>不重复待我审批。</b>本页看的是<b>全租户</b>的审批链路，不是「归我的待办」；
 *       要批东西请去「待我审批」。两件事的权限码也是分开的。
 *   <li><b>撤回如实报未交付。</b>能力声明说 {@code delivered=false} 时，这里显示那句话与归属模块，
 *       而不是画一个永远转不动的撤回按钮。
 * </ol>
 */
export function ApprovalRecordWorkspace() {
  const { session } = useIdentity();
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState<ApprovalRecordPage | null>(null);
  const [stats, setStats] = useState<ApprovalRecordStatistics | null>(null);
  const [caps, setCaps] = useState<ApprovalRecordCapabilities | null>(null);
  const [detail, setDetail] = useState<ApprovalRecordDetail | null>(null);
  const [filters, setFilters] = useState<RecordFilters>(emptyFilters());

  const readable = session.permissions.includes('identity.approval_record.read');
  const exportable = session.permissions.includes('identity.approval_record.export');

  const load = useCallback(async () => {
    if (!readable) return;
    setError('');
    try {
      const query = buildQuery(filters);
      setPage(await api<ApprovalRecordPage>(`identity/approval-records${query}`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '审批记录加载失败，请稍后重试');
      setPage(null);
    }
  }, [readable, filters]);

  const loadStats = useCallback(async () => {
    if (!readable) return;
    try {
      const params = new URLSearchParams();
      if (filters.documentTypeCode.trim())
        params.set('documentTypeCode', filters.documentTypeCode.trim());
      if (filters.sourceSystem.trim()) params.set('sourceSystem', filters.sourceSystem.trim());
      const submittedFrom = localToIso(filters.submittedFrom);
      const submittedTo = localToIso(filters.submittedTo);
      if (submittedFrom) params.set('submittedFrom', submittedFrom);
      if (submittedTo) params.set('submittedTo', submittedTo);
      const query = params.toString();
      setStats(
        await api<ApprovalRecordStatistics>(
          `identity/approval-records/statistics${query ? `?${query}` : ''}`,
        ),
      );
    } catch {
      // 统计失败不该让记录列表跟着空掉：两者是不同的东西，统计只是加分的读出口
      setStats(null);
    }
  }, [readable, filters]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void loadStats();
  }, [loadStats]);

  useEffect(() => {
    if (!readable) return;
    api<ApprovalRecordCapabilities>('identity/approval-records/capabilities')
      .then(setCaps)
      .catch(() => setCaps(null));
  }, [readable]);

  const apply = useCallback(() => {
    const problems = validateFilters(filters);
    if (problems.length) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    setNotice('');
    // 改筛选回到第一页：停在第 5 页看一个只有 2 页的结果集，只会让人以为筛选没生效
    setFilters((prev) => ({ ...prev, page: 0 }));
  }, [filters]);

  const openDetail = useCallback(async (id: string) => {
    setError('');
    try {
      setDetail(await api<ApprovalRecordDetail>(`identity/approval-records/${id}`));
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '链路详情加载失败');
    }
  }, []);

  const doExport = useCallback(async () => {
    const problems = validateFilters(filters);
    if (problems.length) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const query = buildExportQuery(filters);
      const response = await fetch(`/api/iam/identity/approval-records/export${query}`, {
        headers: { 'Content-Type': 'application/json' },
        cache: 'no-store',
      });
      if (!response.ok) throw new ApiError(response.status, '导出失败，请收窄时间范围后重试');
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.download = `approval-records-${Date.now()}.csv`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
      setNotice('导出已开始，请到浏览器下载目录查看');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '导出失败，请稍后重试');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  if (!readable)
    return (
      <EmptyState
        title="没有审批记录查看权限"
        description="审批记录需要 identity.approval_record.read，与「待我审批」的审批权是两回事。"
      />
    );

  const filtered = isFiltered(filters);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">审批记录</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            全链路记录：发起、流转节点、各节点意见与时间、转交、驳回与最终结果。记录只读，不可修改。
          </p>
        </div>
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => void load()} disabled={busy}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          {exportable && (
            <Button onClick={() => void doExport()} disabled={busy}>
              <Download className="mr-2 h-4 w-4" />
              导出审计 CSV
            </Button>
          )}
        </div>
      </header>

      {error && (
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
      )}
      {notice && <p className="text-muted-foreground text-sm">{notice}</p>}

      {!exportable && (
        <p className="text-muted-foreground border-muted-foreground/30 rounded border px-3 py-2 text-sm">
          你有审批记录的查看权限，但没有导出权限（identity.approval_record.export）。
          把全量审批链路导成文件与在系统里看两下是两种不同的风险，所以是两个权限码。
        </p>
      )}

      {caps && !caps.withdrawal.delivered && (
        <p className="text-muted-foreground border-muted-foreground/30 rounded border px-3 py-2 text-sm">
          <b>撤回未交付</b>：{caps.withdrawal.note}（归属 {caps.withdrawal.owningModule}）。
          本页不会显示撤回记录，因为库里确实还没有撤回这一事实。
        </p>
      )}

      {/* 筛选 */}
      <section className="bg-card space-y-3 rounded-lg border p-4">
        <div className="grid gap-3 md:grid-cols-3 lg:grid-cols-4">
          <Field label="实例号 / 标题片段">
            <Input
              value={filters.instanceNo}
              onChange={(e) => setFilters({ ...filters, instanceNo: e.target.value })}
              placeholder="AP-…"
            />
          </Field>
          <Field label="单据类型">
            <Input
              value={filters.documentTypeCode}
              onChange={(e) => setFilters({ ...filters, documentTypeCode: e.target.value })}
              placeholder="PURCHASE_ORDER"
            />
          </Field>
          <Field label="来源系统">
            <Input
              value={filters.sourceSystem}
              onChange={(e) => setFilters({ ...filters, sourceSystem: e.target.value })}
              placeholder="trade"
            />
          </Field>
          <Field label="结果状态">
            <select
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.status}
              onChange={(e) => setFilters({ ...filters, status: e.target.value })}
            >
              <option value="">全部</option>
              {RECORD_STATUSES.map((s) => (
                <option key={s} value={s}>
                  {statusLabel(s)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="申请人（成员 ID）">
            <Input
              value={filters.applicantId}
              onChange={(e) => setFilters({ ...filters, applicantId: e.target.value })}
              placeholder="发起这条审批的人"
            />
          </Field>
          <Field label="审批人（成员 ID）">
            <Input
              value={filters.actorId}
              onChange={(e) => setFilters({ ...filters, actorId: e.target.value })}
              placeholder="在这条审批上动过手的人"
            />
          </Field>
          <Field label="当前节点 Key">
            <Input
              value={filters.nodeKey}
              onChange={(e) => setFilters({ ...filters, nodeKey: e.target.value })}
              placeholder="N1"
            />
          </Field>
          <Field label="发生过动作">
            <select
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.action}
              onChange={(e) => setFilters({ ...filters, action: e.target.value })}
            >
              <option value="">全部</option>
              {RECORD_ACTIONS.map((a) => (
                <option key={a} value={a}>
                  {actionLabel(a)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="币种">
            <Input
              value={filters.currencyCode}
              onChange={(e) => setFilters({ ...filters, currencyCode: e.target.value })}
              placeholder="USD"
            />
          </Field>
          <Field label="金额下限">
            <Input
              value={filters.minAmount}
              onChange={(e) => setFilters({ ...filters, minAmount: e.target.value })}
              placeholder="0"
            />
          </Field>
          <Field label="金额上限">
            <Input
              value={filters.maxAmount}
              onChange={(e) => setFilters({ ...filters, maxAmount: e.target.value })}
              placeholder="不限"
            />
          </Field>
          <Field label="提交时间从">
            <Input
              type="datetime-local"
              value={filters.submittedFrom}
              onChange={(e) => setFilters({ ...filters, submittedFrom: e.target.value })}
            />
          </Field>
          <Field label="提交时间到">
            <Input
              type="datetime-local"
              value={filters.submittedTo}
              onChange={(e) => setFilters({ ...filters, submittedTo: e.target.value })}
            />
          </Field>
          <Field label="动作时间从">
            <Input
              type="datetime-local"
              value={filters.decidedFrom}
              onChange={(e) => setFilters({ ...filters, decidedFrom: e.target.value })}
            />
          </Field>
          <Field label="动作时间到">
            <Input
              type="datetime-local"
              value={filters.decidedTo}
              onChange={(e) => setFilters({ ...filters, decidedTo: e.target.value })}
            />
          </Field>
          <Field label="排序">
            <select
              className="border-input h-9 w-full rounded-md border px-2 text-sm"
              value={filters.sort}
              onChange={(e) => setFilters({ ...filters, sort: e.target.value as Sort })}
            >
              {SORTS.map((s) => (
                <option key={s} value={s}>
                  {SORT_LABELS[s]}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <div className="flex gap-2">
          <Button onClick={apply}>应用筛选</Button>
          <Button variant="outline" onClick={() => setFilters(emptyFilters())}>
            清空
          </Button>
        </div>
      </section>

      {/* 统计报表 */}
      {stats && (
        <section className="bg-card space-y-3 rounded-lg border p-4">
          <h3 className="font-medium">统计报表</h3>
          <div className="grid gap-3 md:grid-cols-4 lg:grid-cols-6">
            <Metric label="实例总数" value={String(stats.instanceTotal)} />
            <Metric label="待审批" value={String(stats.pendingTotal)} />
            <Metric label="已结清" value={String(stats.finishedTotal)} />
            <Metric label="驳回" value={String(stats.rejectedTotal)} />
            <Metric label="通过率" value={rateLabel(stats.approveRate)} />
            <Metric
              label="最长停留"
              value={durationLabel(stats.longestDwellSeconds)}
              tone={durationTone(stats.longestDwellSeconds)}
            />
            <Metric label="平均周期" value={durationLabel(stats.avgCycleSeconds)} />
            <Metric label="平均单步" value={durationLabel(stats.avgActionSeconds)} />
            <Metric
              label="统计区间"
              value={
                stats.periodFrom || stats.periodTo
                  ? `${stats.periodFrom ?? '最早'} ~ ${stats.periodTo ?? '现在'}`
                  : '全部时间'
              }
            />
          </div>

          <BreakdownTable
            title="按结果状态"
            rows={stats.byStatus}
            highlight={topKey(stats.byStatus)}
          />
          <BreakdownTable
            title="按单据类型"
            rows={stats.byDocumentType}
            highlight={topKey(stats.byDocumentType)}
          />
          <BreakdownTable
            title="按节点（动作发生时的节点快照）"
            rows={stats.byNode}
            highlight={topKey(stats.byNode)}
          />
          <BreakdownTable
            title="按来源系统"
            rows={stats.bySourceSystem}
            highlight={topKey(stats.bySourceSystem)}
          />

          <div className="grid gap-4 lg:grid-cols-2">
            <div>
              <h4 className="text-muted-foreground mb-1 text-sm font-medium">按审批动作</h4>
              <ul className="space-y-1 text-sm">
                {stats.byAction.map((row) => (
                  <li key={row.action} className="flex items-center gap-2">
                    <Badge tone={actionTone(row.action)}>{actionLabel(row.action)}</Badge>
                    <span>{row.count} 次</span>
                  </li>
                ))}
                {stats.byAction.length === 0 && (
                  <li className="text-muted-foreground">还没有任何审批动作</li>
                )}
              </ul>
            </div>
            <div>
              <h4 className="text-muted-foreground mb-1 text-sm font-medium">按审批人</h4>
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-1">审批人</th>
                    <th className="py-1">处理</th>
                    <th className="py-1">通过</th>
                    <th className="py-1">驳回</th>
                    <th className="py-1">转出/转入</th>
                    <th className="py-1">平均处理</th>
                  </tr>
                </thead>
                <tbody>
                  {stats.byActor.map((row) => (
                    <tr key={row.actorId}>
                      <td className="py-1">{row.actorName ?? '未知审批人'}</td>
                      <td className="py-1">{row.actionCount}</td>
                      <td className="py-1">{row.approveCount}</td>
                      <td className="py-1">{row.rejectCount}</td>
                      <td className="py-1">
                        {row.transferOutCount}/{row.transferInCount}
                      </td>
                      <td className="py-1">{durationLabel(row.avgHandleSeconds)}</td>
                    </tr>
                  ))}
                  {stats.byActor.length === 0 && (
                    <tr>
                      <td colSpan={6} className="text-muted-foreground py-2">
                        还没有任何审批人处理过单据
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          </div>
        </section>
      )}

      {/* 列表 */}
      <section className="bg-card space-y-3 rounded-lg border p-4">
        <div className="text-muted-foreground flex items-center justify-between text-sm">
          <span>{page ? summaryLabel(page.items.length, page.total) : '加载中…'}</span>
          {page && (
            <span>
              未结 {page.summary.pendingTotal} 条 · 最长停留{' '}
              {durationLabel(page.summary.longestDwellSeconds)}
            </span>
          )}
        </div>

        {page && page.items.length === 0 ? (
          <EmptyState
            title={filtered ? '当前筛选没有命中记录' : '还没有审批记录'}
            description={
              filtered
                ? '试着放宽时间范围或清掉几个筛选条件。'
                : '各业务系统提交待审批单据后，这里会出现完整链路。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="py-2">实例号</th>
                  <th className="py-2">单据类型 / 标题</th>
                  <th className="py-2">金额</th>
                  <th className="py-2">申请人</th>
                  <th className="py-2">当前节点</th>
                  <th className="py-2">结果</th>
                  <th className="py-2">动作</th>
                  <th className="py-2">提交时间</th>
                  <th className="py-2">停留</th>
                  <th className="py-2">重发</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {page?.items.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="py-2 font-mono text-xs">{row.instanceNo}</td>
                    <td className="py-2">
                      <div>{row.subjectTitle}</div>
                      <div className="text-muted-foreground text-xs">
                        {row.documentTypeName} · {row.flowName}
                      </div>
                    </td>
                    <td className="py-2">{amountLabel(row.amount, row.currencyCode)}</td>
                    <td className="py-2">{row.applicantName ?? '未知申请人'}</td>
                    <td className="py-2">{row.currentNodeName ?? row.currentNodeKey}</td>
                    <td className="py-2">
                      <Badge tone={statusTone(row.status)}>{statusLabel(row.status)}</Badge>
                    </td>
                    <td className="py-2 text-xs">
                      共 {row.actionCount} 次
                      {row.transferCount > 0 && ` · 转交 ${row.transferCount}`}
                      {row.rejectCount > 0 && ` · 驳回 ${row.rejectCount}`}
                    </td>
                    <td className="py-2 text-xs">{new Date(row.submittedAt).toLocaleString()}</td>
                    <td className="py-2 text-xs">
                      <Badge tone={durationTone(row.dwellSeconds)}>
                        {durationLabel(row.dwellSeconds)}
                      </Badge>
                    </td>
                    <td className="py-2 text-xs">
                      {row.originalInstanceNo ? `源自 ${row.originalInstanceNo}` : '—'}
                      {row.resubmittedCount > 0 && ` · 重发 ${row.resubmittedCount}`}
                    </td>
                    <td className="py-2">
                      <Button size="sm" variant="outline" onClick={() => void openDetail(row.id)}>
                        <FileSearch className="mr-1 h-4 w-4" />
                        链路
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {page && page.total > page.size && (
          <div className="flex items-center justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={filters.page <= 0}
              onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-xs">
              第 {filters.page + 1} / {Math.max(1, Math.ceil(page.total / page.size))} 页
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={filters.page + 1 >= Math.ceil(page.total / page.size)}
              onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
            >
              下一页
            </Button>
          </div>
        )}
      </section>

      {/* 链路详情 */}
      {detail && (
        <section className="bg-card space-y-3 rounded-lg border p-4">
          <div className="flex items-start justify-between">
            <div>
              <h3 className="font-medium">
                {detail.instance.subjectTitle} · {detail.instance.instanceNo}
              </h3>
              <p className="text-muted-foreground mt-1 text-sm">
                {detail.instance.documentTypeName} · {detail.instance.flowName} ·{' '}
                {amountLabel(detail.instance.amount, detail.instance.currencyCode)}
              </p>
            </div>
            <Button variant="outline" size="sm" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </div>

          <dl className="text-muted-foreground grid gap-2 text-sm md:grid-cols-4">
            <div>
              <dt>申请人</dt>
              <dd className="text-foreground">{detail.instance.applicantName ?? '未知申请人'}</dd>
            </div>
            <div>
              <dt>最终结果</dt>
              <dd className="text-foreground">
                <Badge tone={statusTone(detail.instance.status)}>
                  {statusLabel(detail.instance.status)}
                </Badge>
              </dd>
            </div>
            <div>
              <dt>提交时间</dt>
              <dd className="text-foreground">
                {new Date(detail.instance.submittedAt).toLocaleString()}
              </dd>
            </div>
            <div>
              <dt>结清时间</dt>
              <dd className="text-foreground">
                {detail.instance.finishedAt
                  ? new Date(detail.instance.finishedAt).toLocaleString()
                  : '尚未结清'}
              </dd>
            </div>
          </dl>

          <h4 className="text-muted-foreground text-sm font-medium">
            流转链路（{detail.timeline.length} 个节点动作）
          </h4>
          <ol className="space-y-2">
            {detail.timeline.map((node) => (
              <li key={node.actionId} className="border-t pt-2 first:border-t-0">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={actionTone(node.action)}>{actionLabel(node.action)}</Badge>
                  <span className="text-sm">
                    {timelineHeadline(node.action, node.actorName, node.toName)}
                  </span>
                  <span className="text-muted-foreground text-xs">
                    节点 {node.nodeName ?? node.nodeKey}
                  </span>
                  <span className="text-muted-foreground ml-auto text-xs">
                    {new Date(node.decidedAt).toLocaleString()} · 在该节点压了{' '}
                    {durationLabel(node.dwellSeconds)}
                  </span>
                </div>
                {node.comment && <p className="mt-1 text-sm">{node.comment}</p>}
                {!node.comment && node.action !== 'TRANSFER' && (
                  <p className="text-muted-foreground mt-1 text-xs">这一步没有留意见</p>
                )}
              </li>
            ))}
            {detail.timeline.length === 0 && (
              <li className="text-muted-foreground text-sm">
                这条审批还没有任何人处理过，链路尚未开始。
              </li>
            )}
          </ol>

          {detail.instance.originalInstanceNo && (
            <p className="text-sm">
              本单由原实例{' '}
              <button
                type="button"
                className="underline"
                onClick={() => void openDetail(detail.instance.originalInstanceId as string)}
              >
                {detail.instance.originalInstanceNo}
              </button>{' '}
              驳回后重新发起。
            </p>
          )}

          {detail.resubmissions.length > 0 && (
            <>
              <h4 className="text-muted-foreground text-sm font-medium">
                由本单驳回后重新发起的实例（{detail.resubmissions.length}）
              </h4>
              <ul className="space-y-1 text-sm">
                {detail.resubmissions.map((r) => (
                  <li key={r.id}>
                    <button
                      type="button"
                      className="underline"
                      onClick={() => void openDetail(r.id)}
                    >
                      {r.instanceNo}
                    </button>{' '}
                    <Badge tone={statusTone(r.status)}>{statusLabel(r.status)}</Badge>{' '}
                    <span className="text-muted-foreground text-xs">
                      {new Date(r.submittedAt).toLocaleString()}
                    </span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </section>
      )}
    </div>
  );
}

function localToIso(value: string): string | null {
  if (!value.trim()) return null;
  const parsed = Date.parse(value);
  return Number.isNaN(parsed) ? null : new Date(parsed).toISOString();
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="space-y-1 text-sm">
      <span className="text-muted-foreground text-xs">{label}</span>
      {children}
    </label>
  );
}

function Metric({ label, value, tone }: { label: string; value: string; tone?: Tone }) {
  return (
    <div className="rounded border p-2">
      <div className="text-muted-foreground text-xs">{label}</div>
      <div className="mt-1 text-sm font-medium">
        {tone ? <Badge tone={tone}>{value}</Badge> : value}
      </div>
    </div>
  );
}

function BreakdownTable({
  title,
  rows,
  highlight,
}: {
  title: string;
  rows: {
    key: string;
    label: string;
    instanceCount: number;
    pendingCount: number;
    avgDwellSeconds: number;
  }[];
  highlight: string | null;
}) {
  return (
    <div>
      <h4 className="text-muted-foreground mb-1 text-sm font-medium">{title}</h4>
      <table className="w-full text-sm">
        <thead className="text-muted-foreground text-left">
          <tr>
            <th className="py-1">分组</th>
            <th className="py-1">实例数</th>
            <th className="py-1">未结</th>
            <th className="py-1">平均停留</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((row) => (
            <tr key={row.key}>
              <td className="py-1">
                {row.label}
                {row.key === highlight && <Badge tone="primary">最多</Badge>}
              </td>
              <td className="py-1">{row.instanceCount}</td>
              <td className="py-1">{row.pendingCount}</td>
              <td className="py-1">{durationLabel(row.avgDwellSeconds)}</td>
            </tr>
          ))}
          {rows.length === 0 && (
            <tr>
              <td colSpan={4} className="text-muted-foreground py-2">
                该维度暂无数据
              </td>
            </tr>
          )}
        </tbody>
      </table>
    </div>
  );
}
