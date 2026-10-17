'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, ShieldAlert, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  RefundDiscrepancyPage,
  RefundExecutionDetail,
  RefundExecutionPage,
  RefundFilterOptions,
  RefundStatusCapabilities,
} from '../../../../../packages/api-contracts/src/index';
import { ApiError } from '../../lib/api';
import {
  actionButton,
  attemptKindLabels,
  blockingText,
  discrepancyKindLabels,
  discrepancyPair,
  failureText,
  fetchRefundCapabilities,
  fetchRefundDetail,
  fetchRefundDiscrepancies,
  fetchRefundFilters,
  fetchRefundList,
  formatAmount,
  formatTime,
  platformMethodLabel,
  platformSyncLabel,
  reportOutcomeLabels,
  reportSourceLabels,
  resolutionLabels,
  stateBadgeClass,
  stateTone,
  transactionText,
  unavailableCell,
  unknownWarning,
  verdictLabels,
  verdictTone,
} from './refund-status-model';

/**
 * 11.3.1.2 退款状态。
 *
 * <p>蓝图：「跟踪退款执行状态与平台同步状态，处理退款失败、外部超时（先查证交易再重试，
 * 禁止重复支付）、平台退款与系统退款不一致的差异。」
 * 输出：「退款状态 + 失败原因 + 重试/人工处理建议 + 差异清单。」
 * 加粗句：「<b>外部退款超时不等于失败</b>，重复发起退款会造成实质资损。」
 *
 * <p><b>本页面对那句加粗句負责的四件事</b>：
 *
 * <ol>
 *   <li><b>「结果未知数」是页面顶部的第一张卡</b>，且下面固定跟一条橙色风险条
 *       （{@link unknownWarning}）。运营打开页面先看到的不是「一共多少笔」，
 *       而是「有几笔的钱可能已经付出去了、但我们还不知道」。</li>
 *   <li><b>UNKNOWN 有一整套独立于「失败」的视觉与文案</b>：徽标、语气、按钮全部不同。
 *       它<b>永远拿不到</b>「重试」按钮，只能拿到「先查证交易」——
 *       可重试性由后端状态机下发（{@code row.action}），<b>前端不自己推断</b>。</li>
 *   <li><b>不能做的动作也说出来</b>：每行把后端给的 {@code actionBlocking} 原样显示。
 *       按钮变灰而不给理由，会让运营以为系统坏了。</li>
 *   <li><b>差异清单把两边原值并排显示</b>（系统 vs 平台），不是只报「不一致」——
 *       只报结论运营没法判断该改哪边。</li>
 * </ol>
 *
 * <p><b>诚实显示不可得的维度</b>：12 财务 / 15 客服 / 9 订单回写全部未交付
 * （见 {@code capabilities}），页面上如实写明归属模块，而不是显示成空白。
 */
export function RefundStatusWorkspace() {
  const [params, setParams] = useState({
    executionState: '',
    platformSyncState: '',
    platformRefundMethod: '',
    currency: '',
    orderNo: '',
    refundNo: '',
    failureCode: '',
    minAmount: '',
    maxAmount: '',
    onlyNeedsAttention: '',
    sort: 'ATTENTION_FIRST',
    page: 0,
    size: 20,
  });
  const [page, setPage] = useState<RefundExecutionPage | null>(null);
  const [caps, setCaps] = useState<RefundStatusCapabilities | null>(null);
  const [options, setOptions] = useState<RefundFilterOptions | null>(null);
  const [discrepancies, setDiscrepancies] = useState<RefundDiscrepancyPage | null>(null);
  const [detail, setDetail] = useState<RefundExecutionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [p, c, f, d] = await Promise.all([
        fetchRefundList(params),
        fetchRefundCapabilities(),
        fetchRefundFilters(),
        fetchRefundDiscrepancies({ size: 20 }),
      ]);
      setPage(p);
      setCaps(c);
      setOptions(f);
      setDiscrepancies(d);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : '退款状态加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = async (id: string) => {
    setNotice(null);
    try {
      setDetail(await fetchRefundDetail(id));
    } catch (e) {
      setNotice(e instanceof ApiError ? e.message : '退款详情加载失败');
    }
  };

  const warning = unknownWarning(page?.unknownCount ?? 0);

  const totals = useMemo(() => {
    const rows = page?.rows ?? [];
    return {
      attention: rows.filter((r) => r.needsAttention).length,
      unknown: rows.filter((r) => r.executionState === 'UNKNOWN').length,
    };
  }, [page]);

  return (
    <div className="space-y-6">
      <PageHeader
        title="退款状态"
        description="跟踪退款执行与平台同步；外部超时不等于失败，重试前必须先查证交易。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-1 h-3.5 w-3.5 animate-spin' : 'mr-1 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      />

      {/* 顶部汇总：**未知态排第一**，因为它是最可能造成资损的一类 */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
        <StatCard label="退款总数" value={page?.total ?? 0} />
        <StatCard
          label="结果未知（超时不等于失败）"
          value={page?.unknownCount ?? 0}
          polarity={page?.unknownCount ? 'negative' : 'neutral'}
        />
        <StatCard label="在途待结果" value={page?.inFlightCount ?? 0} />
        <StatCard label="已明确失败" value={page?.failedCount ?? 0} />
        <StatCard label="对账差异" value={page?.discrepancyCount ?? 0} />
      </div>

      {warning && (
        <div className="flex items-start gap-2 rounded-md border border-warning/40 bg-warning-soft px-3 py-2 text-sm text-warning">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{warning}</span>
        </div>
      )}

      {notice && (
        <div className="rounded-md border border-destructive/40 bg-destructive-soft px-3 py-2 text-sm text-destructive">
          {notice}
        </div>
      )}

      {error && (
        <div className="rounded-md border border-destructive/40 bg-destructive-soft px-3 py-2 text-sm text-destructive">
          {error}
        </div>
      )}

      {/* 筛选 */}
      <SectionCard title="筛选">
        <div className="grid gap-3 md:grid-cols-4 lg:grid-cols-6">
          <label className="text-xs text-muted-foreground">
            执行状态
            <select
              className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
              value={params.executionState}
              onChange={(e) => setParams({ ...params, executionState: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              {(options?.executionStates ?? []).map((s) => (
                <option key={s} value={s}>
                  {stateTone(s).label}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-muted-foreground">
            平台同步状态
            <select
              className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
              value={params.platformSyncState}
              onChange={(e) => setParams({ ...params, platformSyncState: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              {(options?.platformSyncStates ?? []).map((s) => (
                <option key={s} value={s}>
                  {platformSyncLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-muted-foreground">
            退款方式
            <select
              className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
              value={params.platformRefundMethod}
              onChange={(e) => setParams({ ...params, platformRefundMethod: e.target.value, page: 0 })}
            >
              <option value="">全部</option>
              {(options?.platformRefundMethods ?? []).map((s) => (
                <option key={s} value={s}>
                  {platformMethodLabel(s)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-xs text-muted-foreground">
            订单号
            <Input
              className="mt-1"
              value={params.orderNo}
              onChange={(e) => setParams({ ...params, orderNo: e.target.value, page: 0 })}
            />
          </label>
          <label className="text-xs text-muted-foreground">
            退款号
            <Input
              className="mt-1"
              value={params.refundNo}
              onChange={(e) => setParams({ ...params, refundNo: e.target.value, page: 0 })}
            />
          </label>
          <label className="text-xs text-muted-foreground">
            金额下限（闭区间）
            <Input
              className="mt-1"
              value={params.minAmount}
              onChange={(e) => setParams({ ...params, minAmount: e.target.value, page: 0 })}
            />
          </label>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-3 text-xs">
          <label className="flex items-center gap-1">
            <input
              type="checkbox"
              checked={params.onlyNeedsAttention === 'true'}
              onChange={(e) =>
                setParams({
                  ...params,
                  onlyNeedsAttention: e.target.checked ? 'true' : '',
                  page: 0,
                })
              }
            />
            只看待处置（未知 / 失败 / 差异）
          </label>
          <span className="text-muted-foreground">
            金额区间口径：{page?.amountRangeSemantics ?? '闭区间'}
          </span>
          {page && Object.keys(page.amountByCurrency).length > 0 && (
            <span className="text-muted-foreground">
              金额按币种分开：
              {Object.entries(page.amountByCurrency)
                .map(([c, v]) => formatAmount(v, c))
                .join(' · ')}
            </span>
          )}
        </div>
      </SectionCard>

      {/* 列表 */}
      <SectionCard title="退款列表（默认：待处置优先）">
        {loading && !page ? (
          <p className="py-8 text-center text-sm text-muted-foreground">加载中…</p>
        ) : !page || page.rows.length === 0 ? (
          <EmptyState
            title="没有符合条件的退款"
            description="调整筛选条件，或等待上游 11.3.1.1 退款申请与 12 财务交付后产生真实退款记录。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="p-2">退款号 / 订单</th>
                  <th className="p-2">状态</th>
                  <th className="p-2">金额</th>
                  <th className="p-2">失败原因</th>
                  <th className="p-2">建议动作</th>
                  <th className="p-2">外部交易号</th>
                  <th className="p-2">最近查证</th>
                  <th className="p-2" />
                </tr>
              </thead>
              <tbody>
                {page.rows.map((r) => {
                  const btn = actionButton(r.action);
                  return (
                    <tr
                      key={r.id}
                      className={
                        r.executionState === 'UNKNOWN'
                          ? 'border-b bg-warning-soft/40'
                          : 'border-b'
                      }
                    >
                      <td className="p-2">
                        <div className="font-medium">{r.refundNo}</div>
                        <div className="text-xs text-muted-foreground">
                          {r.orderNo} · {platformMethodLabel(r.platformRefundMethod)} ·{' '}
                          {platformSyncLabel(r.platformSyncState)}
                        </div>
                      </td>
                      <td className="p-2">
                        <Badge tone={stateTone(r.executionState).tone}>
                          {stateTone(r.executionState).label}
                        </Badge>
                        {r.openDiscrepancyCount > 0 && (
                          <Badge tone="warning" className="ml-1">
                            {r.openDiscrepancyCount} 项差异待裁定
                          </Badge>
                        )}
                        {r.attemptCount > 1 && (
                          <div className="mt-1 text-xs text-muted-foreground">
                            已派发 {r.attemptCount} 次
                          </div>
                        )}
                      </td>
                      <td className="p-2 tabular-nums">{formatAmount(r.refundAmount, r.currency)}</td>
                      <td className="max-w-xs p-2 text-xs">
                        {r.executionState === 'SUCCEEDED' ? (
                          <span className="text-muted-foreground">—</span>
                        ) : (
                          failureText(r)
                        )}
                      </td>
                      <td className="max-w-xs p-2 text-xs">
                        <div>{r.actionLabel}</div>
                        {blockingText(r) && (
                          <div className="text-muted-foreground">{blockingText(r)}</div>
                        )}
                        {r.requiredVerdict && (
                          <div className="text-muted-foreground">
                            要求查证结论：{verdictLabels[r.requiredVerdict] ?? r.requiredVerdict}
                          </div>
                        )}
                      </td>
                      <td className="p-2 text-xs">{transactionText(r.externalTransactionRef)}</td>
                      <td className="p-2 text-xs">{formatTime(r.lastVerifiedAt)}</td>
                      <td className="p-2 text-right">
                        <Button
                          variant={btn.variant === 'disabled' ? 'ghost' : btn.variant}
                          size="sm"
                          disabled={btn.variant === 'disabled' || !btn.show}
                          onClick={() => void openDetail(r.id)}
                        >
                          {btn.show ? btn.label : '详情'}
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
        {page && page.rows.length > 0 && (
          <div className="mt-3 flex items-center justify-between text-xs text-muted-foreground">
            <span>
              第 {page.page + 1} 页 · 本页 {totals.attention} 项待处置 · 共 {page.total} 笔
            </span>
            <div className="flex gap-2">
              <Button
                variant="outline"
                size="sm"
                disabled={page.page <= 0}
                onClick={() => setParams({ ...params, page: page.page - 1 })}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={(page.page + 1) * page.size >= page.total}
                onClick={() => setParams({ ...params, page: page.page + 1 })}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* 差异清单 */}
      <SectionCard title="对账差异清单（未裁定）">
        {!discrepancies || discrepancies.rows.length === 0 ? (
          <EmptyState
            title="没有待裁定的差异"
            description="平台退款与系统退款目前一致。差异只登记事实、不自动抹平，需人工裁定。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs text-muted-foreground">
                  <th className="p-2">退款号</th>
                  <th className="p-2">类型</th>
                  <th className="p-2">系统 vs 平台</th>
                  <th className="p-2">说明</th>
                  <th className="p-2">裁定状态</th>
                  <th className="p-2">检出时间</th>
                </tr>
              </thead>
              <tbody>
                {discrepancies.rows.map((d) => (
                  <tr key={d.id} className="border-b">
                    <td className="p-2 font-medium">{d.refundNo}</td>
                    <td className="p-2">
                      <Badge tone="warning">
                        {d.discrepancyKindLabel || discrepancyKindLabels[d.discrepancyKind]}
                      </Badge>
                    </td>
                    <td className="p-2 text-xs tabular-nums">{discrepancyPair(d)}</td>
                    <td className="max-w-xs p-2 text-xs">{d.detail}</td>
                    <td className="p-2 text-xs">
                      {resolutionLabels[d.resolutionState] ?? d.resolutionState}
                      {d.resolutionNote && (
                        <div className="text-muted-foreground">{d.resolutionNote}</div>
                      )}
                    </td>
                    <td className="p-2 text-xs">{formatTime(d.detectedAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        {discrepancies && (
          <p className="mt-3 text-xs text-muted-foreground">
            待裁定 {discrepancies.openCount} · 已确认待处理 {discrepancies.acknowledgedCount} · 已裁定{' '}
            {discrepancies.resolvedCount}
          </p>
        )}
      </SectionCard>

      {/* 详情 */}
      {detail && (
        <SectionCard title={`退款详情 ${detail.header.refundNo}`}>
          <div className="space-y-4 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={stateTone(detail.header.executionState).tone}>
                {stateTone(detail.header.executionState).label}
              </Badge>
              <span className="text-xs">{detail.advice.label}</span>
              {detail.advice.blocking && (
                <span className="text-xs text-destructive">禁止：{detail.advice.blocking}</span>
              )}
            </div>
            <p className="text-xs text-muted-foreground">{detail.idempotencyNote}</p>
            <p className="text-xs text-muted-foreground">{detail.platformNote}</p>

            <div>
              <h4 className="mb-1 text-xs font-medium">派发历史（每次对外派发一条）</h4>
              {detail.attempts.length === 0 ? (
                <p className="text-xs text-muted-foreground">尚未发起任何派发。</p>
              ) : (
                <ul className="space-y-1 text-xs">
                  {detail.attempts.map((a) => (
                    <li key={a.id} className="rounded border px-2 py-1">
                      第 {a.attemptNo} 次 · {attemptKindLabels[a.attemptKind] ?? a.attemptKind} ·
                      派发时状态 {a.stateAtDispatch} · 幂等键 {a.idempotencyKey} ·{' '}
                      {formatTime(a.dispatchedAt)}
                      {a.verificationId && <span> · 依据查证 {a.verificationId.slice(0, 8)}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h4 className="mb-1 text-xs font-medium">查证历史（先查证交易）</h4>
              {detail.verifications.length === 0 ? (
                <p className="text-xs text-muted-foreground">尚未做过查证。</p>
              ) : (
                <ul className="space-y-1 text-xs">
                  {detail.verifications.map((v) => (
                    <li key={v.id} className="rounded border px-2 py-1">
                      <Badge tone={verdictTone(v.verdict)}>
                        {v.verdictLabel || verdictLabels[v.verdict]}
                      </Badge>{' '}
                      交易号 {v.transactionRef} · {formatTime(v.verifiedAt)}
                      {v.note && <span className="text-muted-foreground"> · {v.note}</span>}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h4 className="mb-1 text-xs font-medium">外部执行结果回报</h4>
              {detail.reports.length === 0 ? (
                <p className="text-xs text-muted-foreground">
                  还没有回报。12 财务未交付，当前只有平台侧查询与人工登记能写入。
                </p>
              ) : (
                <ul className="space-y-1 text-xs">
                  {detail.reports.map((r) => (
                    <li key={r.id} className="rounded border px-2 py-1">
                      {reportSourceLabels[r.reportSource] ?? r.reportSource} ·{' '}
                      {reportOutcomeLabels[r.reportOutcome] ?? r.reportOutcome}
                      {r.failureReason && <span> · {r.failureReason}</span>} ·{' '}
                      {formatTime(r.reportedAt)}
                    </li>
                  ))}
                </ul>
              )}
            </div>

            <div>
              <h4 className="mb-1 text-xs font-medium">状态事件流</h4>
              <ul className="space-y-1 text-xs">
                {detail.events.map((e) => (
                  <li key={e.id} className="rounded border px-2 py-1">
                    {formatTime(e.occurredAt)} · {e.eventType}
                    {e.fromState && (
                      <span>
                        {' '}
                        {e.fromState} → {e.toState}
                      </span>
                    )}
                    <span className="text-muted-foreground"> · {e.detail}</span>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </SectionCard>
      )}

      {/* 能力与缺口 */}
      {caps && (
        <SectionCard title="能力与缺口（未交付的上下游如实列出）">
          <div className="space-y-3 text-xs">
            <div className="flex items-start gap-2">
              <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0 text-muted-foreground" />
              <div>
                <p className="font-medium">上游</p>
                <p className="text-muted-foreground">
                  12 财务：{caps.upstream.financeDelivered ? '已交付' : '未交付'}（归属{' '}
                  {caps.upstream.financeOwner}）· {caps.upstream.financeNote}
                </p>
                <p className="text-muted-foreground">
                  11.3.1.1 退款申请：
                  {caps.upstream.refundRequestDelivered ? '已交付' : '未交付'}（归属{' '}
                  {caps.upstream.refundRequestOwner}）· {caps.upstream.refundRequestNote}
                </p>
                <p className="text-muted-foreground">回报落库方式：{caps.upstream.reportLandingNote}</p>
              </div>
            </div>
            <div>
              <p className="font-medium">下游</p>
              <p className="text-muted-foreground">
                通知买家（15 客服）：
                {caps.downstream.customerNotifyDelivered ? '已交付' : '未交付'}（归属{' '}
                {caps.downstream.customerNotifyOwner}）
              </p>
              <p className="text-muted-foreground">
                订单退款状态回写（9 订单）：
                {caps.downstream.orderStatusWritebackDelivered ? '已交付' : '未交付'}（归属{' '}
                {caps.downstream.orderStatusWritebackOwner}）
              </p>
            </div>
            <div>
              <p className="font-medium">幂等保证</p>
              <ul className="list-disc pl-5 text-muted-foreground">
                {caps.idempotencyGuarantees.map((g) => (
                  <li key={g}>{g}</li>
                ))}
              </ul>
            </div>
            <div>
              <p className="font-medium">重试护栏</p>
              <p className="text-muted-foreground">{caps.retryGuardNote}</p>
            </div>
            <div>
              <p className="font-medium">状态机（后端唯一真身）</p>
              <ul className="space-y-0.5 text-muted-foreground">
                {caps.stateMachine.map((t) => (
                  <li key={`${t.from}>${t.to}`}>
                    {t.fromLabel} → {t.toLabel}
                    {t.requiresVerification && (
                      <span className="text-destructive">
                        {' '}
                        （必须先查证，结论 {t.allowedVerdict}）
                      </span>
                    )}
                  </li>
                ))}
              </ul>
            </div>
            <div>
              <p className="font-medium">不可用的动作</p>
              <ul className="space-y-0.5 text-muted-foreground">
                {caps.actions
                  .filter((a) => !a.available)
                  .map((a) => (
                    <li key={a.action}>
                      {a.label}（{a.ownerModule ?? '—'}）：{a.reason}
                    </li>
                  ))}
              </ul>
            </div>
            <p className="text-muted-foreground">{caps.shopGranularityNote}</p>
            <p className="text-muted-foreground">
              平台店铺维度：{unavailableCell('', { label: '平台店铺', ownerModule: '2 平台与店铺', reason: caps.shopGranularityNote }).text}
            </p>
          </div>
        </SectionCard>
      )}
    </div>
  );
}
