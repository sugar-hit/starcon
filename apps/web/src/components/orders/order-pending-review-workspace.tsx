'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, CheckCheck, ClipboardList, Clock, RefreshCw, ShieldCheck, X } from 'lucide-react';
import {
  Badge,
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
  BatchDecisionInput,
  BatchReceipt,
  PendingReviewCapabilities,
  PendingReviewDetail,
  PendingReviewRow,
  PendingReviewSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  approveDisabledReason,
  canSelect,
  decideBatch,
  errorText,
  evaluateOrder,
  fetchBatches,
  fetchCapabilities,
  fetchQueue,
  fetchQueueItem,
  fetchSummary,
  hitRuleText,
  isoText,
  money,
  outcomeText,
  pageText,
  queueStatusText,
  reviewDecisionText,
  reviewModeText,
  riskScoreHint,
  riskScoreText,
  timelinessText,
  waitingText,
} from './order-pending-review-model';

const PAGE_SIZE = 20;

/** 队列状态筛选项。默认是「未终结」= 待审 + 已挂起（已挂起的单仍在等人）。 */
const STATUS_OPTIONS = [
  { value: '', label: '未终结（待审 + 已挂起）' },
  { value: 'PENDING', label: '待审' },
  { value: 'SUSPENDED', label: '已挂起' },
  { value: 'APPROVED', label: '已通过' },
  { value: 'REJECTED', label: '已驳回' },
  { value: 'ALL', label: '全部' },
];

const DECISION_OPTIONS = [
  { value: '', label: '全部判定' },
  { value: 'AUTO_APPROVE', label: '规则判定通过' },
  { value: 'MANUAL_REVIEW', label: '规则判定转人工' },
];

/** FormField 的 children 必须是 Input，所以下拉框自己配 label。 */
function SelectField({
  id,
  label,
  value,
  onChange,
  options,
  hint,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
  hint?: string;
}) {
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="text-label-13 text-foreground block">
        {label}
      </label>
      <select
        id={id}
        className="border-border bg-surface w-full rounded border px-2 py-1.5"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
      {hint && <p className="text-copy-12 text-muted-foreground">{hint}</p>}
    </div>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-copy-13 text-muted-foreground">{label}</div>
      <div className="text-copy-14 text-foreground break-words">{value}</div>
    </div>
  );
}

/**
 * 9.2.1.1 待审队列工作区。
 *
 * <p>蓝图：「展示待审订单，按风险与时效排序，支持批量通过/驳回/挂起，
 * 显示每单命中的规则与风险信号。」
 *
 * <p><b>区块顺序对应「先看我手上有什么，再决定怎么审」</b>：
 *
 * <ol>
 *   <li><b>能力与依据</b>：能作为批量通过依据的规则清单摆在这里 ——
 *       蓝图加粗句「批量通过必须基于明确的规则，而不是默认全过」的界面落点：
 *       规则清单为空时「通过」按钮直接禁用，并说明为什么。</li>
 *   <li><b>概览</b>：待审 / 已挂起 / 已通过 / 已驳回 / 已超时 / 未评估 / 高风险。</li>
 *   <li><b>队列</b>：真分页 + 真筛选，顺序就是后端的「风险分 → 时效分 → 提交时刻」。</li>
 *   <li><b>批量操作</b>：通过要挑规则，驳回与挂起要写理由；
 *       提交后回执逐条列出「哪一单、什么结论、为什么」。</li>
 *   <li><b>单条详情</b>：订单事实 + 风险五维明细 + 完整命中链 + 最近几次批量结果。</li>
 * </ol>
 */
export function OrderPendingReviewWorkspace() {
  const [capabilities, setCapabilities] = useState<PendingReviewCapabilities | null>(null);
  const [summary, setSummary] = useState<PendingReviewSummary | null>(null);
  const [rows, setRows] = useState<PendingReviewRow[]>([]);
  const [meta, setMeta] = useState({ total: 0, page: 0, size: PAGE_SIZE });
  const [detail, setDetail] = useState<PendingReviewDetail | null>(null);
  const [receipt, setReceipt] = useState<BatchReceipt | null>(null);

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [decision, setDecision] = useState('');
  const [currentPage, setCurrentPage] = useState(0);
  const [selected, setSelected] = useState<string[]>([]);

  const [ruleCode, setRuleCode] = useState('');
  const [reason, setReason] = useState('');
  const [batchAction, setBatchAction] = useState<'APPROVE' | 'REJECT' | 'SUSPEND'>('APPROVE');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, sum, pg] = await Promise.all([
        fetchCapabilities(),
        fetchSummary(),
        fetchQueue({
          keyword: keyword || undefined,
          queueStatus: status || undefined,
          reviewDecision: decision || undefined,
          page: currentPage,
          size: PAGE_SIZE,
        }),
      ]);
      setCapabilities(caps);
      setSummary(sum);
      setRows(pg.items);
      setMeta({ total: pg.total, page: pg.page, size: pg.size });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, status, decision, currentPage]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const openDetail = useCallback(async (orderId: string) => {
    setBusy(true);
    setError(null);
    try {
      setDetail(await fetchQueueItem(orderId));
      setNotice(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const toggle = useCallback((orderId: string) => {
    setSelected((prev) =>
      prev.includes(orderId) ? prev.filter((id) => id !== orderId) : [...prev, orderId],
    );
  }, []);

  const submitBatch = useCallback(async () => {
    if (selected.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      const input: BatchDecisionInput = {
        // 幂等键：同一次操作重试要带同一个键。窗口内生成一次，重试时复用。
        idempotencyKey: `pending-review-${Date.now()}-${selected.join(',').slice(0, 64)}`,
        action: batchAction,
        orderIds: selected,
        ruleCode: batchAction === 'APPROVE' ? ruleCode : null,
        ruleVersion:
          batchAction === 'APPROVE'
            ? (capabilities?.approvableRules.find((r) => r.ruleCode === ruleCode)?.version ?? null)
            : null,
        reason: batchAction === 'APPROVE' ? reason : reason,
      };
      const result = await decideBatch(input);
      setReceipt(result);
      setNotice(
        result.replayed
          ? '这次是幂等回放：上一次用同一个幂等键的请求已经处理过，没有再次改任何状态。'
          : `本批实际通过 ${result.approvedCount} 单、驳回 ${result.rejectedCount} 单、` +
            `挂起 ${result.suspendedCount} 单、跳过 ${result.skippedCount} 单。`,
      );
      setSelected([]);
      await reload();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [selected, batchAction, ruleCode, reason, capabilities, reload]);

  const approvableRules = capabilities?.approvableRules ?? [];
  const approveBlocker = approveDisabledReason(selected.length, ruleCode, approvableRules.length);
  const reasonBlocker =
    batchAction !== 'APPROVE' && !reason.trim() ? '驳回与挂起必须写明理由' : null;

  const statusOptions = useMemo(() => STATUS_OPTIONS, []);
  const decisionOptions = useMemo(() => DECISION_OPTIONS, []);

  return (
    <div className="space-y-6">
      <PageHeader
        title="待审队列"
        description="展示待审订单，按风险与时效排序，支持批量通过/驳回/挂起，显示每单命中的规则与风险信号。"
        actions={
          <Button onClick={() => void reload()} disabled={loading || busy}>
            <RefreshCw className={loading ? 'animate-spin' : ''} aria-hidden />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {/* ---------------------------------------------------- 1. 能力与依据 */}
      <SectionCard title="能力与可用的审单规则">
        {capabilities ? (
          <div className="space-y-3">
            <Notice tone="warning" title="批量通过必须基于明确的规则">
              {capabilities.notes}
            </Notice>
            <div className="grid gap-2 sm:grid-cols-3">
              <Cell label="生效中的规则" value={`${capabilities.activeRuleCount} 条`} />
              <Cell
                label="其中可作为通过依据"
                value={`${capabilities.autoApproveRuleCount} 条`}
              />
              <Cell label="强制转人工" value={`${capabilities.forcedManualRuleCount} 条`} />
            </div>
            {approvableRules.length === 0 ? (
              <Notice tone="warning" title="本租户没有可用的「自动通过」规则">
                批量通过需要一条此刻生效中、动作是「自动通过」的规则。当前一条都没有，
                所以「通过」是禁用的 —— 这不是故障：没有规则就没有「明确的规则」，
                本模块不提供「默认全过」这条路。请先到审单规则里登记并启用一条。
              </Notice>
            ) : (
              <ul className="space-y-2">
                {approvableRules.map((rule) => (
                  <li
                    key={rule.ruleCode}
                    className="border-border flex flex-wrap items-start justify-between gap-2 border-b pb-2 last:border-0"
                  >
                    <div className="min-w-0">
                      <div className="text-copy-14 text-foreground font-medium">
                        {rule.ruleName}（{rule.ruleCode}）
                      </div>
                      <div className="text-copy-13 text-muted-foreground">
                        第 {rule.version} 版 · 生效自 {isoText(rule.effectiveFrom)}
                        {rule.effectiveTo ? ` 至 ${isoText(rule.effectiveTo)}` : ' · 长期有效'}
                      </div>
                    </div>
                    <Badge tone="success">可作为通过依据</Badge>
                  </li>
                ))}
              </ul>
            )}
            <ul className="space-y-1">
              {capabilities.dimensions.map((dimension) => (
                <li key={dimension.code} className="text-copy-13 text-muted-foreground">
                  <span className="text-foreground">{dimension.label}</span>：{dimension.source}
                </li>
              ))}
            </ul>
          </div>
        ) : (
          <EmptyState title="能力声明加载中" description="正在读取本模块的上下游可用性。" />
        )}
      </SectionCard>

      {/* ---------------------------------------------------- 2. 概览 */}
      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="待审" value={summary.pending} icon={<ClipboardList aria-hidden />} />
          <StatCard label="已挂起" value={summary.suspended} icon={<Clock aria-hidden />} />
          <StatCard label="已超时" value={summary.overdue} icon={<AlertTriangle aria-hidden />} />
          <StatCard label="未评估风险分" value={summary.unevaluated} icon={<ShieldCheck aria-hidden />} />
        </div>
      )}

      {/* ---------------------------------------------------- 3. 队列 */}
      <SectionCard
        title="待审队列"
        description="顺序由后端按「风险分 → 时效分 → 提交时刻」给出；界面不再二次排序。"
        extra={<span className="text-copy-13 text-muted">{pageText(meta.page, meta.size, meta.total)}</span>}
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <FormField id="pending-keyword" label="订单号 / 平台单号">
              <Input
                id="pending-keyword"
                value={keyword}
                placeholder="模糊匹配"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
              />
            </FormField>
            <SelectField
              id="pending-status"
              label="队列状态"
              value={status}
              onChange={(v) => {
                setStatus(v);
                setCurrentPage(0);
              }}
              options={statusOptions}
            />
            <SelectField
              id="pending-decision"
              label="审单判定"
              value={decision}
              onChange={(v) => {
                setDecision(v);
                setCurrentPage(0);
              }}
              options={decisionOptions}
            />
            <div className="flex items-end">
              <Button
                variant="secondary"
                onClick={() => {
                  setKeyword('');
                  setStatus('');
                  setDecision('');
                  setCurrentPage(0);
                }}
              >
                清空筛选
              </Button>
            </div>
          </div>

          {loading ? (
            <EmptyState title="加载中" description="正在读取待审队列。" />
          ) : rows.length === 0 ? (
            <EmptyState
              title="队列是空的"
              description={
                status
                  ? '当前筛选条件下没有订单。清空筛选可以看到全部。'
                  : '没有待审订单：已提交进审单队列的订单会出现在这里。'
              }
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full border-collapse text-left">
                <thead>
                  <tr className="text-copy-12 text-muted-foreground">
                    <th className="w-8 py-2">
                      <input
                        type="checkbox"
                        aria-label="全选本页可审的订单"
                        checked={
                          rows.filter(canSelect).length > 0 &&
                          rows.filter(canSelect).every((r) => selected.includes(r.orderId))
                        }
                        onChange={(e) => {
                          const selectable = rows.filter(canSelect).map((r) => r.orderId);
                          setSelected((prev) =>
                            e.target.checked
                              ? [...new Set([...prev, ...selectable])]
                              : prev.filter((id) => !selectable.includes(id)),
                          );
                        }}
                      />
                    </th>
                    <th className="py-2">订单</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">风险分</th>
                    <th className="py-2">时效</th>
                    <th className="py-2">命中的规则</th>
                    <th className="py-2">队列状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.orderId} className="border-border border-t">
                      <td className="py-2 align-top">
                        <input
                          type="checkbox"
                          aria-label={`选择订单 ${row.orderNo}`}
                          disabled={!canSelect(row)}
                          checked={selected.includes(row.orderId)}
                          onChange={() => toggle(row.orderId)}
                        />
                      </td>
                      <td className="py-2 align-top">
                        <div className="text-foreground font-medium">{row.orderNo}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          {row.orderSource} · 提交 {isoText(row.submittedAt)}
                          {row.recipientName ? ` · ${row.recipientName}` : ''}
                        </div>
                        {row.decisionReason && (
                          <div className="text-copy-12 text-muted-foreground">
                            结论：{row.decisionReason}
                          </div>
                        )}
                      </td>
                      <td className="py-2 align-top">
                        {money(row.grandTotal)} {row.currencyCode}
                      </td>
                      <td className="py-2 align-top" title={riskScoreHint(row)}>
                        <div>{riskScoreText(row.riskScore)}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          {row.riskRecommendedAction
                            ? `建议：${row.riskRecommendedAction}`
                            : '未评估'}
                        </div>
                      </td>
                      <td className="py-2 align-top">
                        <div>{timelinessText(row.timelinessState)}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          已等 {waitingText(row.waitingHours)}
                        </div>
                      </td>
                      <td className="py-2 align-top">
                        <div>{hitRuleText(row)}</div>
                        <div className="text-copy-12 text-muted-foreground">
                          {row.reviewDecision
                            ? `${reviewDecisionText(row.reviewDecision)}（${reviewModeText(row.reviewMode)}）`
                            : '未判定'}
                        </div>
                      </td>
                      <td className="py-2 align-top">
                        <Badge
                          tone={
                            row.queueStatus === 'APPROVED'
                              ? 'success'
                              : row.queueStatus === 'REJECTED'
                                ? 'danger'
                                : row.queueStatus === 'SUSPENDED'
                                  ? 'warning'
                                  : 'neutral'
                          }
                        >
                          {queueStatusText(row.queueStatus)}
                        </Badge>
                      </td>
                      <td className="py-2 align-top">
                        <Button size="sm" variant="secondary" onClick={() => void openDetail(row.orderId)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}

          {meta.total > meta.size && (
            <div className="flex items-center justify-end gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={currentPage === 0}
                onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <span className="text-copy-13 text-muted">第 {meta.page + 1} 页</span>
              <Button
                size="sm"
                variant="secondary"
                disabled={(currentPage + 1) * meta.size >= meta.total}
                onClick={() => setCurrentPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------- 4. 批量操作 */}
      <SectionCard
        title="批量审单"
        description="审单是发货前的最后人工闸门：通过必须指名规则，驳回与挂起必须写明理由。"
        extra={<span className="text-copy-13 text-muted">已选 {selected.length} 单</span>}
      >
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            {(
              [
                { value: 'APPROVE', label: '批量通过' },
                { value: 'REJECT', label: '批量驳回' },
                { value: 'SUSPEND', label: '批量挂起' },
              ] as const
            ).map((option) => (
              <Button
                key={option.value}
                size="sm"
                variant={batchAction === option.value ? 'default' : 'secondary'}
                onClick={() => setBatchAction(option.value)}
              >
                {option.label}
              </Button>
            ))}
          </div>

          {batchAction === 'APPROVE' ? (
            <SelectField
              id="pending-rule"
              label="依据的审单规则（必填）"
              value={ruleCode}
              onChange={setRuleCode}
              options={[
                { value: '', label: '请选择一条生效中的「自动通过」规则' },
                ...approvableRules.map((rule) => ({
                  value: rule.ruleCode,
                  label: `${rule.ruleName}（${rule.ruleCode} · 第 ${rule.version} 版）`,
                })),
              ]}
              hint="批量通过必须基于明确的规则：后端会逐单按这条规则判定，只有真的判成通过的才放行，其余跳过并写清原因。"
            />
          ) : null}

          <FormField
            id="pending-reason"
            label={batchAction === 'APPROVE' ? '备注（可选）' : '理由（必填）'}
            hint="没有理由的结论，客服没法向买家解释。"
            error={reasonBlocker ?? undefined}
          >
            <Input
              id="pending-reason"
              value={reason}
              placeholder={batchAction === 'APPROVE' ? '例如：本批小额单，按自动通过规则放行' : '例如：地址不可达，等买家补充'}
              onChange={(e) => setReason(e.target.value)}
            />
          </FormField>

          <div className="flex flex-wrap items-center gap-3">
            <Button
              onClick={() => void submitBatch()}
              disabled={busy || selected.length === 0 || Boolean(approveBlocker) || Boolean(reasonBlocker)}
            >
              <CheckCheck aria-hidden />
              提交批量审单
            </Button>
            {approveBlocker && batchAction === 'APPROVE' && (
              <span className="text-copy-13 text-muted-foreground">{approveBlocker}</span>
            )}
            {reasonBlocker && batchAction !== 'APPROVE' && (
              <span className="text-copy-13 text-muted-foreground">{reasonBlocker}</span>
            )}
          </div>

          {receipt && (
            <div className="border-border space-y-2 rounded border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-copy-14 text-foreground font-medium">本批回执</span>
                {receipt.replayed && <Badge tone="info">幂等回放</Badge>}
                <Badge tone="neutral">{receipt.actionLabel}</Badge>
                {receipt.ruleCode && (
                  <Badge tone="primary">
                    依据规则 {receipt.ruleCode} · 第 {receipt.ruleVersion} 版
                  </Badge>
                )}
              </div>
              <div className="text-copy-13 text-muted-foreground">
                请求 {receipt.requestedCount} 单 —— 实际通过 {receipt.approvedCount}、驳回{' '}
                {receipt.rejectedCount}、挂起 {receipt.suspendedCount}、跳过 {receipt.skippedCount}。
              </div>
              <table className="text-copy-13 w-full border-collapse text-left">
                <thead>
                  <tr className="text-copy-12 text-muted-foreground">
                    <th className="py-1">#</th>
                    <th className="py-1">订单</th>
                    <th className="py-1">结论</th>
                    <th className="py-1">原因</th>
                  </tr>
                </thead>
                <tbody>
                  {receipt.items.map((item) => (
                    <tr key={item.seqNo} className="border-border border-t">
                      <td className="py-1 align-top">{item.seqNo}</td>
                      <td className="py-1 align-top">{item.orderNo ?? item.orderId}</td>
                      <td className="py-1 align-top">
                        <Badge
                          tone={
                            item.outcome === 'APPROVED'
                              ? 'success'
                              : item.outcome === 'REJECTED'
                                ? 'danger'
                                : item.outcome === 'SUSPENDED'
                                  ? 'warning'
                                  : 'neutral'
                          }
                        >
                          {outcomeText(item.outcome)}
                        </Badge>
                      </td>
                      <td className="py-1 align-top">{item.reason}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="text-copy-12 text-muted-foreground">{receipt.notes}</div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------- 5. 单条详情 */}
      {detail && (
        <SectionCard
          title={`订单详情 · ${detail.order.orderNo}`}
          extra={
            <Button size="sm" variant="ghost" onClick={() => setDetail(null)}>
              <X aria-hidden />
              关闭
            </Button>
          }
        >
          <div className="space-y-4">
            <div className="grid gap-2 sm:grid-cols-3 lg:grid-cols-4">
              <Cell label="订单来源" value={detail.order.orderSource} />
              <Cell
                label="订单金额"
                value={`${money(detail.order.grandTotal)} ${detail.order.currencyCode}`}
              />
              <Cell label="提交时刻" value={isoText(detail.order.submittedAt)} />
              <Cell label="队列状态" value={queueStatusText(detail.row.queueStatus)} />
              <Cell label="收件人" value={detail.order.recipientName ?? '—'} />
              <Cell label="收货国家" value={detail.order.shippingCountry ?? '—'} />
              <Cell label="收货城市" value={detail.order.shippingCity ?? '—'} />
              <Cell label="订单行数" value={`${detail.order.lineCount} 行`} />
            </div>

            {detail.queueCase ? (
              <div className="border-border space-y-1 rounded border p-3">
                <div className="text-copy-14 text-foreground font-medium">
                  审单结论：{detail.queueCase.statusLabel}
                </div>
                <div className="text-copy-13 text-muted-foreground">
                  {isoText(detail.queueCase.decidedAt)} · {detail.queueCase.decisionReason || '—'}
                </div>
                {detail.queueCase.ruleCode && (
                  <div className="text-copy-13 text-muted-foreground">
                    依据规则 {detail.queueCase.ruleCode} · 第 {detail.queueCase.ruleVersion} 版
                  </div>
                )}
              </div>
            ) : (
              <Notice tone="info" title="这一单还没有审单结论">
                还没有跑过规则判定。可以点下面的「按当前生效规则试算」先看命中链 ——
                试跑只留痕，不推进任何状态。
              </Notice>
            )}

            <div className="flex flex-wrap gap-2">
              <Button
                size="sm"
                variant="secondary"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setError(null);
                  try {
                    await evaluateOrder(detail.order.orderId);
                    setDetail(await fetchQueueItem(detail.order.orderId));
                    setNotice('已按此刻生效中的规则试算：命中链在下面，试跑不推进任何状态。');
                  } catch (e) {
                    setError(errorText(e));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                按当前生效规则试算
              </Button>
              <Button
                size="sm"
                variant="ghost"
                disabled={busy}
                onClick={async () => {
                  setBusy(true);
                  setError(null);
                  try {
                    setDetail(await fetchQueueItem(detail.order.orderId));
                  } catch (e) {
                    setError(errorText(e));
                  } finally {
                    setBusy(false);
                  }
                }}
              >
                刷新详情
              </Button>
            </div>

            <div>
              <div className="text-copy-14 text-foreground font-medium">命中的规则与逐条件明细</div>
              {detail.hits.length === 0 ? (
                <div className="text-copy-13 text-muted-foreground">
                  还没有判定记录。批量通过或试跑之后，这里会列出「哪个条件、拿订单的哪个字段、实际值多少」。
                </div>
              ) : (
                <div className="space-y-2">
                  {detail.hits.map((hit) => (
                    <div key={hit.id} className="border-border rounded border p-3">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-copy-14 text-foreground font-medium">
                          {hit.ruleName}（{hit.ruleCode}）
                        </span>
                        <Badge tone={hit.matched ? 'warning' : 'neutral'}>
                          {hit.matched ? '命中' : '未命中'}
                        </Badge>
                        {hit.forcedManual && <Badge tone="danger">强制转人工</Badge>}
                        <span className="text-copy-12 text-muted-foreground">次序 {hit.evalRank}</span>
                      </div>
                      <div className="text-copy-13 text-muted-foreground">{hit.reason}</div>
                      {hit.conditions.length > 0 && (
                        <table className="text-copy-12 mt-2 w-full border-collapse text-left">
                          <thead>
                            <tr className="text-muted-foreground">
                              <th className="py-1">维度</th>
                              <th className="py-1">期望</th>
                              <th className="py-1">订单字段</th>
                              <th className="py-1">实际值</th>
                              <th className="py-1">解释</th>
                            </tr>
                          </thead>
                          <tbody>
                            {hit.conditions.map((condition) => (
                              <tr key={condition.seqNo} className="border-border border-t">
                                <td className="py-1 align-top">{condition.dimensionLabel}</td>
                                <td className="py-1 align-top">{condition.expected || '—'}</td>
                                <td className="py-1 align-top font-mono">{condition.actualField || '—'}</td>
                                <td className="py-1 align-top font-mono">
                                  {condition.actualValue || '—'}
                                </td>
                                <td className="py-1 align-top">{condition.explanation}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                    </div>
                  ))}
                </div>
              )}
            </div>

            {detail.riskComponents.length > 0 && (
              <div>
                <div className="text-copy-14 text-foreground font-medium">风险信号（五个维度）</div>
                <table className="text-copy-13 mt-1 w-full border-collapse text-left">
                  <thead>
                    <tr className="text-copy-12 text-muted-foreground">
                      <th className="py-1">维度</th>
                      <th className="py-1">状态</th>
                      <th className="py-1">原始值</th>
                      <th className="py-1">归一化分</th>
                      <th className="py-1">贡献</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.riskComponents.map((component) => (
                      <tr key={component.id} className="border-border border-t">
                        <td className="py-1 align-top">{component.dimension}</td>
                        <td className="py-1 align-top">{component.status}</td>
                        <td className="py-1 align-top">
                          {component.rawValue === null ? '未测到' : component.rawValue}
                        </td>
                        <td className="py-1 align-top">{component.normalizedScore}</td>
                        <td className="py-1 align-top">{component.contribution}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="text-copy-12 text-muted-foreground">
                  风险分只是**建议**；最终动作只能由人给出。
                </div>
              </div>
            )}

            {detail.recentBatches.length > 0 && (
              <div>
                <div className="text-copy-14 text-foreground font-medium">最近几次批量审单</div>
                <ul className="space-y-1">
                  {detail.recentBatches.map((ref) => (
                    <li key={`${ref.batchId}-${ref.outcome}`} className="text-copy-13">
                      {isoText(ref.createdAt)} · {outcomeText(ref.outcome)} · {ref.reason}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            <div className="text-copy-12 text-muted-foreground">{detail.notes}</div>
          </div>
        </SectionCard>
      )}
    </div>
  );
}
