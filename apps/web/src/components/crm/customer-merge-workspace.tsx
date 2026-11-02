'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { AlertTriangle, GitMerge, RefreshCw, Scissors, ShieldAlert } from 'lucide-react';
import type {
  CrmCustomerMergeCandidatePage,
  CrmCustomerMergeCapabilities,
  CrmCustomerMergeLogicalDetail,
  CrmCustomerMergeLogicalPage,
  CrmCustomerMergeOperationPage,
  CrmCustomerMergeRunPage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  confirmCustomerMerge,
  customerMergeAmountText,
  customerMergeCandidateNote,
  customerMergeConfidenceText,
  customerMergeEmptyStateText,
  customerMergeExclusionFlagLabel,
  customerMergeLogicalNote,
  customerMergeMappingStateLabel,
  customerMergeMatchTypeLabel,
  customerMergeOptOutLabel,
  customerMergeRunText,
  customerMergeSignalText,
  customerMergeStatusLabel,
  fetchCustomerMergeCandidates,
  fetchCustomerMergeCapabilities,
  fetchCustomerMergeLogical,
  fetchCustomerMergeLogicalDetail,
  fetchCustomerMergeOperations,
  fetchCustomerMergeRuns,
  rejectCustomerMerge,
  scanCustomerMerge,
  splitCustomerMerge,
} from './customer-merge-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.3.1.3 跨店合并工作区。
 *
 * <p>页面按「先看清边界、再看候选、最后才动手」的顺序排。
 * 蓝图加粗的两句是「**只给建议不自动执行**」与「**必须可拆分**」——
 * 界面上第一件要让人看到的就是这两句，而不是一张能直接点「一键合并」的表。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」；
 *       「自动合并」这一维**刻意标成未交付**，并写清为什么。</li>
 *   <li><b>候选队列</b> —— 一行 = 一对「可能是同一个人」的来源客户。
 *       🔴 每一行都还**只是建议**：状态列永远把「待确认」摆在最前面。</li>
 *   <li><b>置信度要能解释</b> —— 逐条列出命中了哪个信号、强度多少、
 *       比对的是什么（脱敏形态）。</li>
 *   <li><b>人工确认 / 拒绝都要写理由</b> —— 理由是必填项；
 *       事后要能回答「凭什么合并」或「为什么不是同一个人」。</li>
 *   <li><b>逻辑客户</b> —— 合并后的那个人：成员、**按币种分行**的金额、操作记录。
 *       成员可以<b>拆分</b>，拆分后来源行原样还在。</li>
 * </ul>
 */
export function CustomerMergeWorkspace() {
  const { session } = useIdentity();
  const canUpdate = session.permissions.includes('crm.customers.update');

  const [capabilities, setCapabilities] = useState<CrmCustomerMergeCapabilities | null>(null);
  const [candidates, setCandidates] = useState<CrmCustomerMergeCandidatePage | null>(null);
  const [logical, setLogical] = useState<CrmCustomerMergeLogicalPage | null>(null);
  const [detail, setDetail] = useState<CrmCustomerMergeLogicalDetail | null>(null);
  const [operations, setOperations] = useState<CrmCustomerMergeOperationPage | null>(null);
  const [runs, setRuns] = useState<CrmCustomerMergeRunPage | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [scanning, setScanning] = useState(false);

  // 筛选
  const [status, setStatus] = useState('');
  const [matchType, setMatchType] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // 人工复核：每条候选一个理由输入框
  const [reasons, setReasons] = useState<Record<string, string>>({});
  const [busyId, setBusyId] = useState<string | null>(null);
  // 拆分：选中的逻辑客户 + 成员 + 理由
  const [splitTarget, setSplitTarget] = useState<string | null>(null);
  const [splitMemberId, setSplitMemberId] = useState('');
  const [splitReason, setSplitReason] = useState('');

  const filtered = useMemo(
    () => status !== '' || matchType !== '' || keyword !== '',
    [status, matchType, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [candidatePage, logicalPage, operationPage, runPage] = await Promise.all([
        fetchCustomerMergeCandidates({
          status: status || undefined,
          matchType: matchType || undefined,
          keyword: keyword || undefined,
          page: pageIndex,
          size: pageSize,
        }),
        fetchCustomerMergeLogical({ page: 0, size: 20 }),
        fetchCustomerMergeOperations({ page: 0, size: 20 }),
        fetchCustomerMergeRuns({ page: 0, size: 10 }).catch(() => null),
      ]);
      setCandidates(candidatePage);
      setLogical(logicalPage);
      setOperations(operationPage);
      if (runPage) setRuns(runPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '跨店合并数据读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [status, matchType, keyword, pageIndex, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void fetchCustomerMergeCapabilities()
      .then(setCapabilities)
      .catch(() => setCapabilities(null));
  }, []);

  async function runScan() {
    setScanning(true);
    setNotice(null);
    setError(null);
    try {
      const result = await scanCustomerMerge();
      setNotice(
        `候选生成完成（${result.runNo}）：扫了 ${result.scannedCustomerCount} 位来源客户，` +
          `产出候选 ${result.candidateCount} 条。` +
          `🔴 本轮**没有合并任何客户**，每一条都还要人工确认。` +
          (result.belowThresholdCount > 0
            ? `同名不同人（置信度不足）${result.belowThresholdCount} 对没有进队列。`
            : '') +
          (result.truncated ? ' 🔴 本轮名单被截断，不是全量。' : ''),
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成候选失败，请稍后重试');
    } finally {
      setScanning(false);
    }
  }

  async function confirm(candidateId: string) {
    const reason = (reasons[candidateId] ?? '').trim();
    if (reason === '') {
      setError('合并必须写理由：事后要能回答「凭什么合并」。');
      return;
    }
    setBusyId(candidateId);
    setError(null);
    try {
      const merged = await confirmCustomerMerge(candidateId, { reason });
      setNotice(
        `已合并到逻辑客户 ${merged.logical.logicalRef}：合并后的逻辑客户有 ` +
          `${merged.logical.memberCount} 位成员，跨 ${merged.logical.sourceShopCount} 个来源店铺。` +
          `🔴 合并**没有改动来源客户行**，随时可以拆开。`,
      );
      setReasons({ ...reasons, [candidateId]: '' });
      await load();
      await openDetail(merged.logical.id);
    } catch (e) {
      setError(e instanceof Error ? e.message : '合并失败，请稍后重试');
    } finally {
      setBusyId(null);
    }
  }

  async function reject(candidateId: string) {
    const reason = (reasons[candidateId] ?? '').trim();
    if (reason === '') {
      setError('拒绝也必须写理由：事后要能回答「为什么不是同一个人」。');
      return;
    }
    setBusyId(candidateId);
    setError(null);
    try {
      await rejectCustomerMerge(candidateId, { reason });
      setNotice('已拒绝该候选，理由已记入操作流水。');
      setReasons({ ...reasons, [candidateId]: '' });
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '拒绝失败，请稍后重试');
    } finally {
      setBusyId(null);
    }
  }

  async function openDetail(logicalId: string) {
    setError(null);
    try {
      const loaded = await fetchCustomerMergeLogicalDetail(logicalId);
      setDetail(loaded);
      setSplitTarget(logicalId);
      setSplitMemberId('');
      setSplitReason('');
    } catch (e) {
      setError(e instanceof Error ? e.message : '逻辑客户详情读取失败');
    }
  }

  async function doSplit() {
    if (!splitTarget || splitMemberId === '' || splitReason.trim() === '') {
      setError('拆分必须选定成员并写明理由。');
      return;
    }
    setBusyId(splitTarget);
    setError(null);
    try {
      const after = await splitCustomerMerge(splitTarget, {
        customerProfileId: splitMemberId,
        reason: splitReason.trim(),
      });
      setNotice(
        `已拆分：逻辑客户 ${after.logical.logicalRef} 现在有 ${after.logical.memberCount} 位成员。` +
          `🔴 被拆出去的那条来源客户行**原样还在**客户列表里（合并不曾改动它）。`,
      );
      setDetail(after);
      setSplitMemberId('');
      setSplitReason('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '拆分失败，请稍后重试');
    } finally {
      setBusyId(null);
    }
  }

  const empty = customerMergeEmptyStateText(filtered, (runs?.total ?? 0) > 0);

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {error}
        </div>
      )}
      {notice && (
        <div className="border-primary/40 bg-primary/10 text-copy-13 rounded-md px-3 py-2">
          {notice}
        </div>
      )}

      {/* ---------------- 能力边界 ---------------- */}
      <SectionCard
        title="能力边界"
        description="这个页面回答什么、不回答什么 —— 先看清边界再动数据。"
      >
        {capabilities ? (
          <div className="space-y-3">
            <p className="text-muted-foreground text-copy-13">{capabilities.boundaryNote}</p>
            <ul className="space-y-2">
              {capabilities.dimensions.map((dimension) => (
                <li key={dimension.dimension} className="flex items-start gap-2">
                  <Badge tone={dimension.available ? 'success' : 'warning'}>
                    {dimension.available ? '可用' : '未交付'}
                  </Badge>
                  <div className="min-w-0">
                    <div className="text-copy-13 font-medium">{dimension.description}</div>
                    <div className="text-muted-foreground text-copy-12">
                      依据：{dimension.source}
                      {dimension.ownerModule ? ` · 归属模块：${dimension.ownerModule}` : ''}
                    </div>
                    {dimension.impact && (
                      <div className="text-muted-foreground text-copy-12">{dimension.impact}</div>
                    )}
                  </div>
                </li>
              ))}
            </ul>
            <div className="bg-muted/60 space-y-1 rounded-md px-3 py-2">
              <div className="text-copy-13 flex items-center gap-2 font-medium">
                <ShieldAlert className="h-4 w-4" />
                两条硬规矩
              </div>
              <p className="text-muted-foreground text-copy-12">
                {capabilities.confirmationPolicy}
              </p>
              <p className="text-muted-foreground text-copy-12">{capabilities.splitPolicy}</p>
            </div>
            <div className="bg-muted/60 rounded-md px-3 py-2">
              <div className="text-copy-13 flex items-center gap-2 font-medium">
                <ShieldAlert className="h-4 w-4" />
                联系方式口径
              </div>
              <p className="text-muted-foreground text-copy-12 mt-1">
                {capabilities.maskingPolicy}
              </p>
            </div>
            <p className="text-muted-foreground text-copy-12">{capabilities.highlight}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 生成候选 ---------------- */}
      <SectionCard
        title="生成候选建议"
        description="🔴 只给建议，不自动执行：这一格**不会**合并任何客户，合并只能由下面的「人工确认」触发。"
      >
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <Button onClick={() => void runScan()} disabled={!canUpdate || scanning}>
              <RefreshCw className="h-4 w-4" />
              {scanning ? '生成中…' : '生成候选建议'}
            </Button>
            {!canUpdate && (
              <span className="text-muted-foreground text-copy-12">
                当前角色没有 crm.customers.update，不能生成候选。
              </span>
            )}
          </div>
          {runs && runs.rows.length > 0 && (
            <div className="space-y-1">
              {runs.rows.map((run) => (
                <div key={run.id} className="text-copy-12 text-muted-foreground">
                  {customerMergeRunText(run)}
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 候选队列 ---------------- */}
      <SectionCard
        title="候选队列"
        description="一行 = 一对「可能是同一个人」的来源客户。🔴 每一行都还只是建议 —— 状态列里的「待确认」是终态之前的那一格。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="状态">
            <select
              className={control}
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              <option value="SUGGESTED">待确认</option>
              <option value="CONFIRMED">已合并</option>
              <option value="REJECTED">已拒绝</option>
              <option value="STALE">已失效</option>
            </select>
          </Field>
          <Field label="匹配依据">
            <select
              className={control}
              value={matchType}
              onChange={(e) => {
                setMatchType(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.matchTypes ?? []).map((type) => (
                <option key={type} value={type}>
                  {customerMergeMatchTypeLabel(type)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="客户标识 / 姓名 / 信号">
            <input
              className={control}
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageIndex(0);
              }}
            />
          </Field>
          <div className="flex items-end gap-2">
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              重新加载
            </Button>
            <Button
              onClick={() => {
                setStatus('');
                setMatchType('');
                setKeyword('');
                setPageIndex(0);
              }}
            >
              清空筛选
            </Button>
          </div>
        </div>

        {loading && !candidates ? (
          <p className="text-muted-foreground text-copy-13 mt-3">候选队列读取中…</p>
        ) : candidates && candidates.rows.length === 0 ? (
          <div className="mt-3">
            <EmptyState title={empty.title} description={empty.description} />
          </div>
        ) : (
          <div className="mt-3 space-y-3">
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">状态</th>
                    <th className="px-2 py-1 text-left">左侧来源</th>
                    <th className="px-2 py-1 text-left">右侧来源</th>
                    <th className="px-2 py-1 text-left">匹配依据</th>
                    <th className="px-2 py-1 text-right">置信度</th>
                    <th className="px-2 py-1 text-left">信号明细</th>
                    <th className="px-2 py-1 text-left">排除信号</th>
                    <th className="px-2 py-1 text-left">人工复核</th>
                  </tr>
                </thead>
                <tbody>
                  {candidates?.rows.map((row) => (
                    <tr key={row.id} className="border-control-border border-t align-top">
                      <td className="px-2 py-2">
                        <Badge tone={row.status === 'SUGGESTED' ? 'warning' : 'success'}>
                          {row.statusLabel || customerMergeStatusLabel(row.status)}
                        </Badge>
                      </td>
                      <td className="px-2 py-2">
                        <div>{row.leftCustomerRef}</div>
                        <div className="text-muted-foreground text-copy-12">
                          {row.leftSourceShop} · {row.leftCustomerName || '—'}
                        </div>
                      </td>
                      <td className="px-2 py-2">
                        <div>{row.rightCustomerRef}</div>
                        <div className="text-muted-foreground text-copy-12">
                          {row.rightSourceShop} · {row.rightCustomerName || '—'}
                        </div>
                      </td>
                      <td className="px-2 py-2">
                        {row.matchTypeLabel || customerMergeMatchTypeLabel(row.matchType)}
                      </td>
                      <td className="px-2 py-2 text-right">
                        {customerMergeConfidenceText(row.confidence)}
                      </td>
                      <td className="px-2 py-2">
                        <div>{customerMergeSignalText(row.signals)}</div>
                        {row.signalSummary && (
                          <div className="text-muted-foreground text-copy-12">
                            {row.signalSummary}
                          </div>
                        )}
                      </td>
                      <td className="px-2 py-2">
                        {row.exclusionFlags.length === 0 ? (
                          <span className="text-muted-foreground text-copy-12">—</span>
                        ) : (
                          row.exclusionFlags.map((flag) => (
                            <div key={flag} className="text-copy-12">
                              {customerMergeExclusionFlagLabel(flag)}
                            </div>
                          ))
                        )}
                      </td>
                      <td className="px-2 py-2">
                        {row.status === 'SUGGESTED' ? (
                          <div className="space-y-1">
                            <input
                              className={control}
                              placeholder="理由（必填）"
                              value={reasons[row.id] ?? ''}
                              onChange={(e) => setReasons({ ...reasons, [row.id]: e.target.value })}
                            />
                            <div className="flex gap-2">
                              <Button
                                onClick={() => void confirm(row.id)}
                                disabled={!canUpdate || busyId === row.id}
                              >
                                <GitMerge className="h-4 w-4" />
                                确认合并
                              </Button>
                              <Button
                                variant="outline"
                                onClick={() => void reject(row.id)}
                                disabled={!canUpdate || busyId === row.id}
                              >
                                拒绝
                              </Button>
                            </div>
                          </div>
                        ) : (
                          <div className="text-muted-foreground text-copy-12">
                            {row.logicalRef ? `逻辑客户 ${row.logicalRef}` : '—'}
                            {row.reviewNote ? ` · ${row.reviewNote}` : ''}
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center justify-between gap-2">
              <div className="text-muted-foreground text-copy-12">
                共 {candidates?.total ?? 0} 条候选 · 第 {(candidates?.page ?? 0) + 1} /{' '}
                {Math.max(candidates?.totalPages ?? 1, 1)} 页
              </div>
              <div className="flex items-center gap-2">
                <select
                  className={control}
                  value={String(pageSize)}
                  onChange={(e) => {
                    setPageSize(Number(e.target.value));
                    setPageIndex(0);
                  }}
                >
                  {[10, 20, 50, 100].map((size) => (
                    <option key={size} value={size}>
                      每页 {size}
                    </option>
                  ))}
                </select>
                <Button
                  variant="outline"
                  disabled={(candidates?.page ?? 0) <= 0}
                  onClick={() => setPageIndex(Math.max((candidates?.page ?? 0) - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  disabled={(candidates?.page ?? 0) + 1 >= (candidates?.totalPages ?? 1)}
                  onClick={() => setPageIndex((candidates?.page ?? 0) + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
            <p className="text-muted-foreground text-copy-12">
              {customerMergeCandidateNote(candidates)}
            </p>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 逻辑客户 ---------------- */}
      <SectionCard
        title="逻辑客户"
        description="合并后的那个「人」：成员是各来源店铺的客户行（来源行本身没被改过），金额按币种分行。"
      >
        {logical && logical.rows.length === 0 ? (
          <EmptyState
            title="还没有逻辑客户"
            description="确认一条候选之后，这里会出现合并后的逻辑客户 —— 以及可以随时拆开的成员。"
          />
        ) : (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-3">
              <StatCard
                label="逻辑客户"
                value={logical?.total ?? 0}
                icon={<GitMerge className="h-4 w-4" />}
              />
              <StatCard label="生效中" value={logical?.mergedCustomerCount ?? 0} />
              <StatCard
                label="已解散（拆干净了）"
                value={logical?.splitCustomerCount ?? 0}
                polarity="negative"
                icon={<AlertTriangle className="h-4 w-4" />}
              />
            </div>
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">逻辑客户</th>
                    <th className="px-2 py-1 text-left">展示名</th>
                    <th className="px-2 py-1 text-right">成员</th>
                    <th className="px-2 py-1 text-right">来源店铺</th>
                    <th className="px-2 py-1 text-right">订单数</th>
                    <th className="px-2 py-1 text-left">退订</th>
                    <th className="px-2 py-1 text-left">状态</th>
                    <th className="px-2 py-1 text-left">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {logical?.rows.map((row) => (
                    <tr key={row.id} className="border-control-border border-t">
                      <td className="px-2 py-2">{row.logicalRef}</td>
                      <td className="px-2 py-2">{row.displayName || '—'}</td>
                      <td className="px-2 py-2 text-right">{row.memberCount}</td>
                      <td className="px-2 py-2 text-right">{row.sourceShopCount}</td>
                      <td className="px-2 py-2 text-right">{row.totalOrderCount}</td>
                      <td className="px-2 py-2">
                        {row.optOutStateLabel || customerMergeOptOutLabel(row.optOutState)}
                      </td>
                      <td className="px-2 py-2">{row.statusLabel}</td>
                      <td className="px-2 py-2">
                        <Button variant="outline" onClick={() => void openDetail(row.id)}>
                          查看成员
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="text-muted-foreground text-copy-12">
              {customerMergeLogicalNote(logical)}
            </p>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 逻辑客户详情 / 拆分 ---------------- */}
      {detail && (
        <SectionCard
          title={`逻辑客户 ${detail.logical.logicalRef} 的成员`}
          description="🔴 拆分 = 把成员移出去并留痕；映射行不删，被拆出去的来源客户行原样还在。"
        >
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">来源店铺</th>
                    <th className="px-2 py-1 text-left">客户标识</th>
                    <th className="px-2 py-1 text-left">买家名</th>
                    <th className="px-2 py-1 text-left">角色</th>
                    <th className="px-2 py-1 text-left">状态</th>
                    <th className="px-2 py-1 text-left">合并 / 拆分留痕</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.members.map((member) => (
                    <tr key={member.mappingId} className="border-control-border border-t">
                      <td className="px-2 py-2">{member.sourceShop}</td>
                      <td className="px-2 py-2">{member.customerRef}</td>
                      <td className="px-2 py-2">{member.customerName || '—'}</td>
                      <td className="px-2 py-2">{member.role === 'PRIMARY' ? '主成员' : '成员'}</td>
                      <td className="px-2 py-2">
                        {member.stateLabel || customerMergeMappingStateLabel(member.state)}
                      </td>
                      <td className="text-muted-foreground text-copy-12 px-2 py-2">
                        {member.mergedAt ? `合并于 ${member.mergedAt.slice(0, 19)}` : '—'}
                        {member.splitAt ? ` · 拆出于 ${member.splitAt.slice(0, 19)}` : ''}
                        {member.splitReason ? ` · ${member.splitReason}` : ''}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="text-muted-foreground text-copy-12">
              累计消费（按币种分行，跨币种不相加）：{customerMergeAmountText(detail.amounts)}
            </div>

            <div className="border-control-border space-y-2 rounded-md px-3 py-2">
              <div className="text-copy-13 flex items-center gap-2 font-medium">
                <Scissors className="h-4 w-4" />
                拆分一个成员
              </div>
              <div className="grid gap-2 md:grid-cols-3">
                <Field label="要拆出的成员">
                  <select
                    className={control}
                    value={splitMemberId}
                    onChange={(e) => setSplitMemberId(e.target.value)}
                  >
                    <option value="">请选择</option>
                    {detail.members
                      .filter((member) => member.state === 'ACTIVE')
                      .map((member) => (
                        <option key={member.mappingId} value={member.customerProfileId}>
                          {member.sourceShop} · {member.customerRef} · {member.customerName || '—'}
                        </option>
                      ))}
                  </select>
                </Field>
                <Field label="拆分理由（必填）">
                  <input
                    className={control}
                    value={splitReason}
                    onChange={(e) => setSplitReason(e.target.value)}
                  />
                </Field>
                <div className="flex items-end">
                  <Button
                    onClick={() => void doSplit()}
                    disabled={!canUpdate || busyId === splitTarget}
                  >
                    <Scissors className="h-4 w-4" />
                    拆分
                  </Button>
                </div>
              </div>
              <p className="text-muted-foreground text-copy-12">{detail.note}</p>
            </div>

            <div className="space-y-1">
              <div className="text-copy-13 font-medium">操作记录</div>
              {detail.operations.length === 0 ? (
                <p className="text-muted-foreground text-copy-12">还没有操作记录。</p>
              ) : (
                detail.operations.map((operation) => (
                  <div key={operation.id} className="text-muted-foreground text-copy-12">
                    {operation.occurredAt.slice(0, 19)} · {operation.operationLabel} ·{' '}
                    {operation.reason} · 成员 {operation.memberCount} 位
                  </div>
                ))
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 操作记录 ---------------- */}
      <SectionCard
        title="操作记录"
        description="合并 / 拆分 / 拒绝候选的只追加流水 —— 事后要能回答「谁在什么时候把谁合并了」。"
      >
        {operations && operations.rows.length === 0 ? (
          <EmptyState
            title="还没有操作记录"
            description="每一次合并、拆分与拒绝都会在这里留一行。"
          />
        ) : (
          <div className="space-y-1">
            {operations?.rows.map((operation) => (
              <div key={operation.id} className="text-copy-12 text-muted-foreground">
                {operation.occurredAt.slice(0, 19)} · {operation.operationLabel} ·{' '}
                {operation.logicalRef ? `逻辑客户 ${operation.logicalRef}` : '候选'} ·{' '}
                {operation.reason} · 成员 {operation.memberCount} 位
              </div>
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  );
}
