'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  RefundApplicationFilterOptions,
  RefundCapabilities,
  RefundDetail,
  RefundPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionButton,
  amount,
  availableText,
  balanceRows,
  boundaryRows,
  cancelRefundApplication,
  createRefundApplication,
  decideRefundApplication,
  dispatchRefundApplication,
  eventText,
  executionText,
  executionTone,
  fetchRefundCapabilities,
  fetchRefundDetail,
  fetchRefundFilters,
  fetchRefundList,
  fetchRefundableBalance,
  methodText,
  policyText,
  reasonText,
  recordRefundOutcome,
  saveRefundPolicy,
  stateTone,
  submitRefundApplication,
  updateRefundApplication,
} from './refund-application-model';

/**
 * 11.3.1.1 退款申请。
 *
 * <p>蓝图：「发起退款：选择退款方式（原路/平台/余额）、金额（可部分）、原因，
 * 校验累计退款不超过可退余额，提交审批后交财务执行。」
 * 加粗句：「<b>可退余额校验必须在服务端做</b>——重复提交退款是资损事故的高发点。」
 *
 * <p><b>页面围绕那条加粗句组织</b>：
 *
 * <ol>
 *   <li><b>第一屏是「待审 / 执行中」的金额</b>（{@link StatCard}），不是「一共多少张单」——
 *       这两档才是决定「有多少钱已经许出去 / 已经派出去了」的数。</li>
 *   <li><b>「校验可退余额」是真去后端算的</b>：页面把 {@code caseId} 送上去，
 *       拿回五个占用桶逐条显示。页面**不自己算**（{@link balanceRows}）。</li>
 *   <li><b>审批规则默认 fail-closed 要写在脸上</b>：没配规则时横幅直接说
 *       「任何金额都要两人签字」，而不是显示一个会被读成「0 元以下免签」的阈值 0。</li>
 *   <li><b>没有「付款」按钮</b>：12 财务整卷未交付，capabilities 里
 *       {@code handoffToFinance.available=false} 如实报出，页面照着显示，不渲染一个注定失败的入口。</li>
 * </ol>
 */
export function RefundApplicationWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pendingOnly, setPendingOnly] = useState(false);
  const [data, setData] = useState<RefundPage | null>(null);
  const [caps, setCaps] = useState<RefundCapabilities | null>(null);
  const [filters, setFilters] = useState<RefundApplicationFilterOptions | null>(null);
  const [detail, setDetail] = useState<RefundDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // 发起退款草稿用的表单。⚠️ **没有「可退余额」输入框** —— 余额只读，从服务端来。
  const [caseId, setCaseId] = useState('');
  const [amountInput, setAmountInput] = useState('');
  const [method, setMethod] = useState('ORIGINAL');
  const [reason, setReason] = useState('RETURN_REFUND');
  const [paymentRef, setPaymentRef] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」。
      const query: Parameters<typeof fetchRefundList>[0] = { page, size: 20 };
      if (status) query.status = status;
      if (keyword) query.keyword = keyword;
      if (pendingOnly) query.pendingReviewOnly = true;
      const [list, capabilities, options] = await Promise.all([
        fetchRefundList(query),
        fetchRefundCapabilities(),
        fetchRefundFilters(),
      ]);
      setData(list);
      setCaps(capabilities);
      setFilters(options);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [page, status, keyword, pendingOnly]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = useCallback(async (id: string) => {
    setError(null);
    try {
      setDetail(await fetchRefundDetail(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载详情失败');
    }
  }, []);

  const act = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        const next = (await work()) as RefundDetail | undefined;
        if (next) setDetail(next);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : '操作失败');
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const createDraft = useCallback(async () => {
    if (!caseId.trim()) {
      setError('发起退款申请需要先填售后单 ID（可退余额是在这张售后单上算的）');
      return;
    }
    await act(() =>
      createRefundApplication({
        caseId: caseId.trim(),
        rmaId: null,
        refundMethod: method,
        refundAmount: amountInput,
        currency: 'USD',
        refundReason: reason,
        refundReasonNote: reason === 'OTHER' ? '（请补充说明）' : null,
        originalPaymentRef: method === 'ORIGINAL' ? paymentRef || null : null,
        platformRefundRef: method === 'PLATFORM' ? paymentRef || null : null,
      }),
    );
  }, [act, caseId, amountInput, method, reason, paymentRef]);

  const summary = data?.summary;
  // ⚠️ StatCard 的 polarity 只有 neutral / positive / negative 三档 ——
  //   「待审」与「执行中」都不是坏事，用 negative 会让运营以为有异常；
  //   真正需要标红的是「已失败」（钱退不出去，得有人管）。
  const failed = summary?.failed ?? 0;
  const stats = [
    {
      label: '待审笔数',
      value: (summary?.pendingApproval ?? 0) + (summary?.secondApprovalPending ?? 0),
      polarity: 'neutral' as const,
    },
    {
      label: '待审占用金额',
      value: amount(summary?.pendingAmount ?? 0),
      polarity: 'neutral' as const,
    },
    {
      label: '执行中笔数 / 金额',
      value: `${summary?.executing ?? 0} / ${amount(summary?.executingAmount ?? 0)}`,
      polarity: 'neutral' as const,
    },
    {
      label: '已成功 / 已失败',
      value: `${summary?.succeeded ?? 0} / ${failed}`,
      polarity: (failed > 0 ? 'negative' : 'positive') as 'negative' | 'positive',
    },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="退款申请"
        description="发起退款：选方式、定金额、写原因，可退余额由服务端实时校验，提交审批后交财务执行。"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
          </Button>
        }
      />

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        {stats.map((s) => (
          <StatCard key={s.label} label={s.label} value={s.value} polarity={s.polarity} />
        ))}
      </div>

      {/* 加粗句的页面级说明：横幅而不是脚注，因为它决定了这个页面**不提供**什么。 */}
      <div className="flex items-start gap-2 rounded-md border border-amber-500/40 bg-amber-500/10 p-3 text-sm">
        <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" aria-hidden />
        <p>
          可退余额**必须在服务端算**。本页没有「可退余额」输入框，也不会替你算 ——
          填了金额提交后由后端按「基数 − 已退 − 在途」复核，重复提交退款是资损事故的高发点。
        </p>
      </div>

      <SectionCard title="发起退款申请（建草稿）">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-sm">
            <span className="mb-1 block">售后单 ID</span>
            <Input
              value={caseId}
              placeholder="uuid"
              onChange={(e) => setCaseId(e.target.value)}
            />
          </label>
          <label className="text-sm">
            <span className="mb-1 block">退款方式</span>
            <select
              className="rounded border px-2 py-1"
              value={method}
              onChange={(e) => setMethod(e.target.value)}
            >
              <option value="ORIGINAL">原路退回</option>
              <option value="PLATFORM">平台侧退款</option>
              <option value="BALANCE">退回余额</option>
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">金额（可部分）</span>
            <Input
              value={amountInput}
              placeholder="30.00"
              onChange={(e) => setAmountInput(e.target.value)}
            />
          </label>
          <label className="text-sm">
            <span className="mb-1 block">原因</span>
            <select
              className="rounded border px-2 py-1"
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            >
              <option value="RETURN_REFUND">退货退款</option>
              <option value="CANCELLATION">取消订单退款</option>
              <option value="WRONG_AMOUNT">退款金额有误</option>
              <option value="GOODWILL">赔付</option>
              <option value="OTHER">其他（必须写说明）</option>
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">
              {method === 'PLATFORM' ? '平台退款单号' : '原收款流水号'}
            </span>
            <Input
              value={paymentRef}
              placeholder={method === 'BALANCE' ? '退回余额无需填写' : '必填'}
              disabled={method === 'BALANCE'}
              onChange={(e) => setPaymentRef(e.target.value)}
            />
          </label>
          <Button disabled={busy} onClick={() => void createDraft()}>
            建草稿
          </Button>
        </div>
        <p className="mt-2 text-xs text-muted">
          建草稿 ≠ 提交审批。提交在详情页做，且**提交那一刻会再校验一次**可退余额 ——
          页面打开到提交之间，额度可能已经被别的单占走。
        </p>
      </SectionCard>

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <label className="text-sm">
            <span className="mb-1 block">状态</span>
            <select
              className="rounded border px-2 py-1"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {(filters?.statuses ?? []).map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="mb-1 block">关键字</span>
            <Input
              value={keyword}
              placeholder="申请单号 / 售后单号 / 订单号 / 原收款流水号"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={pendingOnly}
              onChange={(e) => {
                setPendingOnly(e.target.checked);
                setPage(0);
              }}
            />
            只看待审
          </label>
        </div>
      </SectionCard>

      {error && (
        <SectionCard>
          <p className="text-sm text-danger" role="alert">
            {error}
          </p>
        </SectionCard>
      )}

      {loading && !data ? (
        <SectionCard>
          <p className="text-sm text-muted">加载中…</p>
        </SectionCard>
      ) : !data || data.rows.length === 0 ? (
        <EmptyState
          title="还没有退款申请单"
          description="退款申请挂在售后单上：先在售后单发起，再对本单发起退款。"
        />
      ) : (
        <SectionCard title={`退款申请单（${data.total} 张）`}>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="p-2">申请单号</th>
                <th className="p-2">售后单 / 订单</th>
                <th className="p-2">状态</th>
                <th className="p-2">金额</th>
                <th className="p-2">方式</th>
                <th className="p-2">执行状态</th>
                <th className="p-2">操作</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((row) => (
                <tr key={row.head.id} className="border-b">
                  <td className="p-2 font-mono">{row.head.applicationNo}</td>
                  <td className="p-2">
                    {row.head.caseNo ?? '—'} / {row.head.orderNo ?? '—'}
                  </td>
                  <td className="p-2">
                    <Badge tone={stateTone(row.head.applicationStatus)}>
                      {row.head.applicationStatusLabel}
                    </Badge>
                  </td>
                  <td className="p-2">
                    {amount(row.head.refundAmount)} {row.head.currency}
                  </td>
                  <td className="p-2">{methodText(row.head.refundMethod)}</td>
                  <td className="p-2">
                    {row.head.executionId ? (
                      <Badge tone={executionTone(row.timelinessState === 'NOT_APPLICABLE' ? 'IN_FLIGHT' : row.timelinessState)}>
                        已派发
                      </Badge>
                    ) : (
                      <span className="text-muted">未派发</span>
                    )}
                  </td>
                  <td className="p-2">
                    <Button variant="secondary" onClick={() => void openDetail(row.head.id)}>
                      详情
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mt-3 flex items-center gap-2">
            <Button
              variant="secondary"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <span className="text-xs text-muted">
              第 {data.page + 1} / {Math.max(1, Math.ceil(data.total / data.size))} 页
            </span>
            <Button
              variant="secondary"
              disabled={(page + 1) * data.size >= data.total}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
          <p className="mt-2 text-xs text-muted">{data.balanceNote}</p>
        </SectionCard>
      )}

      {detail && (
        <SectionCard title={`退款申请 ${detail.head.applicationNo}`}>
          <div className="space-y-4 text-sm">
            <div className="flex flex-wrap gap-2">
              <Badge tone={stateTone(detail.head.applicationStatus)}>
                {detail.head.applicationStatusLabel}
              </Badge>
              <Badge>{methodText(detail.head.refundMethod)}</Badge>
              <Badge>{reasonText(detail.head.refundReason)}</Badge>
              <Badge tone={executionTone(detail.executionState)}>
                {detail.executionState ?? '未派发'}
              </Badge>
            </div>

            <p className="text-muted">
              金额 {amount(detail.head.refundAmount)} {detail.head.currency} ·
              售后单 {detail.head.caseNo ?? '—'} · 订单 {detail.head.orderNo ?? '—'}
              {detail.head.rmaNo ? ` · 退货授权 ${detail.head.rmaNo}` : ''}
            </p>

            {/* 可退余额：五个占用桶逐条 —— 加粗句在页面上的落点 */}
            <div>
              <h4 className="mb-1 font-medium">可退余额（服务端实时算）</h4>
              <table className="w-full text-left text-xs">
                <tbody>
                  {balanceRows(detail.balance).map((r) => (
                    <tr key={r.label} className="border-b">
                      <td className="p-1">{r.label}</td>
                      <td className="p-1 text-right font-mono">{r.value}</td>
                      <td className="p-1 text-muted">{r.hint ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="mt-1 text-xs">
                当前可退：
                <Badge tone={availableText(detail.balance).tone}>
                  {availableText(detail.balance).value}
                </Badge>
              </p>
              {detail.balance.blockingReasons.length > 0 && (
                <ul className="mt-1 list-disc text-xs text-danger pl-4">
                  {detail.balance.blockingReasons.map((r) => (
                    <li key={r}>{r}</li>
                  ))}
                </ul>
              )}
            </div>

            {/* 审批规则：默认 fail-closed 必须写在脸上 */}
            <div>
              <h4 className="mb-1 font-medium">审批规则</h4>
              <Badge tone={policyText(detail.policy).tone}>{policyText(detail.policy).label}</Badge>
              <p className="mt-1 text-xs text-muted">{detail.balance.balanceSource}</p>
              {detail.policy.note && (
                <p className="text-xs text-muted">{detail.policy.note}</p>
              )}
            </div>

            {/* 执行状态：UNKNOWN 这一格是最重要的提示 */}
            <div>
              <h4 className="mb-1 font-medium">执行状态</h4>
              <p className="text-xs">{executionText(detail)}</p>
              {detail.financeExecution.available === false && (
                <p className="mt-1 text-xs text-muted">
                  真实付款执行：未交付（归属 {detail.financeExecution.ownerModule}）。
                  这里的「执行中」只说明**已派发**，不代表钱已经退了。
                </p>
              )}
            </div>

            {/* 操作区：按钮的可用性与理由都来自后端，前端不自己推断 */}
            <div className="flex flex-wrap gap-2">
              {detail.actions
                .filter((a) => a.method !== null)
                .map((a) => {
                  const b = actionButton(a);
                  return (
                    <Button
                      key={a.action}
                      variant={a.action === 'PAY_OUT' ? 'secondary' : 'default'}
                      disabled={b.disabled || busy}
                      title={b.reason ?? undefined}
                      onClick={() => {
                        const version = detail.head.version;
                        const id = detail.head.id;
                        if (a.action === 'SUBMIT') {
                          void act(() => submitRefundApplication(id, { expectedVersion: version }));
                        } else if (a.action === 'FIRST_APPROVE' || a.action === 'SECOND_APPROVE') {
                          const note = window.prompt('审批说明（驳回必填）', '');
                          if (note === null) return;
                          const approve = window.confirm(`同意？（说明：${note}）`);
                          void act(() =>
                            decideRefundApplication(id, { approve, note, expectedVersion: version }),
                          );
                        } else if (a.action === 'CANCEL') {
                          const reason2 = window.prompt('撤销理由（必填）', '');
                          if (!reason2) return;
                          void act(() =>
                            cancelRefundApplication(id, { reason: reason2, expectedVersion: version }),
                          );
                        } else if (a.action === 'DISPATCH') {
                          const executionId = window.prompt(
                            '挂哪条退款执行记录？（executionId，来自 11.3.1.2）',
                            '',
                          );
                          if (!executionId) return;
                          void act(() =>
                            dispatchRefundApplication(id, {
                              executionId: executionId.trim(),
                              expectedVersion: version,
                            }),
                          );
                        } else if (a.action === 'RECORD_OUTCOME') {
                          const succeeded = window.confirm('结果：成功？（取消 = 失败）');
                          const failureReason = succeeded ? '' : window.prompt('失败原因（必填）', '') ?? '';
                          const ref = succeeded ? window.prompt('外部交易号（成功必填）', '') ?? '' : '';
                          void act(() =>
                            recordRefundOutcome(id, {
                              result: succeeded ? 'SUCCEEDED' : 'FAILED',
                              source: 'MANUAL_OPERATOR',
                              externalTransactionRef: ref || null,
                              failureReason: failureReason || null,
                              expectedVersion: version,
                            }),
                          );
                        } else if (a.action === 'UPDATE_DRAFT') {
                          const next = window.prompt('新的退款金额', String(detail.head.refundAmount));
                          if (!next) return;
                          void act(() =>
                            updateRefundApplication(id, {
                              refundMethod: detail.head.refundMethod,
                              refundAmount: next,
                              currency: detail.head.currency,
                              refundReason: detail.head.refundReason,
                              refundReasonNote: detail.head.refundReasonNote,
                              originalPaymentRef: detail.head.originalPaymentRef,
                              platformRefundRef: detail.head.platformRefundRef,
                              expectedVersion: version,
                            }),
                          );
                        }
                      }}
                    >
                      {b.label}
                    </Button>
                  );
                })}
            </div>

            {/* 未交付的动作照实列出，不渲染成按钮 */}
            <div>
              <h4 className="mb-1 font-medium">本模块刻意不做的事</h4>
              <ul className="list-disc text-xs text-muted pl-4">
                {detail.actions
                  .filter((a) => a.method === null)
                  .map((a) => (
                    <li key={a.action}>
                      {a.label}：{a.unavailableReason}
                    </li>
                  ))}
                {(caps ? boundaryRows(caps) : []).map((b) => (
                  <li key={b.scope}>
                    {b.scope}（{b.owner}）：{b.reason}
                  </li>
                ))}
              </ul>
            </div>

            {/* 留痕：审批轨迹是审计材料，金额逐条冻结 */}
            <div>
              <h4 className="mb-1 font-medium">审批与状态留痕</h4>
              <ul className="space-y-1 text-xs">
                {detail.events.map((e) => (
                  <li key={e.id}>
                    {e.occurredAt} · {eventText(e)}
                    {e.reasonNote ? ` · ${e.reasonNote}` : ''}
                  </li>
                ))}
              </ul>
            </div>

            <Button variant="secondary" onClick={() => setDetail(null)}>
              关闭详情
            </Button>
          </div>
        </SectionCard>
      )}

      <SectionCard title="审批规则维护（租户可维护）">
        <PolicyEditor busy={busy} onSave={(body) => act(() => saveRefundPolicy(body))} />
      </SectionCard>
    </div>
  );
}

/** 审批规则维护：阈值是租户可维护的真数据，不是代码里的 if。 */
function PolicyEditor({
  busy,
  onSave,
}: {
  busy: boolean;
  onSave: (body: unknown) => Promise<void>;
}) {
  const [currency, setCurrency] = useState('USD');
  const [threshold, setThreshold] = useState('100.00');
  return (
    <div className="flex flex-wrap items-end gap-3">
      <label className="text-sm">
        <span className="mb-1 block">币种</span>
        <Input value={currency} onChange={(e) => setCurrency(e.target.value.toUpperCase())} />
      </label>
      <label className="text-sm">
        <span className="mb-1 block">双人签阈值</span>
        <Input value={threshold} onChange={(e) => setThreshold(e.target.value)} />
      </label>
      <Button
        disabled={busy}
        onClick={() =>
          void onSave({
            currency,
            dualApprovalThreshold: threshold,
            note: '退款审批规则（租户可维护）',
            effectiveFrom: null,
          })
        }
      >
        保存规则
      </Button>
      <p className="w-full text-xs text-muted">
        金额 **≥** 阈值必须两人签字（复审人 ≠ 初审人、初审人 ≠ 提交人）。
        阈值取**提交那一刻**的口径，之后改规则不会让已待审的单凭空多出或少掉一次审批。
        不配置时一律两人签（fail-closed）。
      </p>
    </div>
  );
}
