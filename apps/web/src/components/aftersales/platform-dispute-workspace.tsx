'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  PlatformDisputeCapabilities,
  PlatformDisputeDetail,
  PlatformDisputePage,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionBlockedReason,
  addPlatformEvidenceItem,
  confirmPlatformDisputeSettlement,
  countdownText,
  createPlatformDispute,
  deadlineText,
  deadlineTone,
  DISPUTE_STATUS_OPTIONS,
  DISPUTE_TYPE_OPTIONS,
  EVIDENCE_TYPE_OPTIONS,
  fetchPlatformDispute,
  fetchPlatformDisputeCapabilities,
  fetchPlatformDisputes,
  formatAmount,
  formatTime,
  isSettlementPending,
  LIABILITY_STAGE_OPTIONS,
  markPlatformEvidence,
  PLATFORM_OPTIONS,
  platformTypeMismatchReason,
  POSITION_OPTIONS,
  registerPlatformDisputeSettlement,
  rejectPlatformDisputeSettlement,
  removePlatformEvidenceItem,
  RESOLUTION_OPTIONS,
  resolvePlatformDispute,
  SETTLEMENT_STATE_OPTIONS,
  settlementEffectiveHint,
  startPlatformEvidence,
  submitPlatformEvidence,
  updatePlatformDisputePosition,
  withdrawPlatformDispute,
} from './platform-dispute-model';

/**
 * 11.4.1.1 平台纠纷列表。
 *
 * <p>蓝图：「管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、
 * 举证要求、我方立场、结果记录。」
 * 输出：「纠纷单 + 时效倒计时 + 举证清单。」
 * 加粗句：「<b>平台纠纷有严格举证时限</b>，超期自动判败，是硬性时效要求。」
 *
 * <p><b>页面围绕两条硬规则组织</b>：
 *
 * <ol>
 *   <li><b>倒计时由绝对截止时刻现算</b>：页面每秒重算一次剩余时间，
 *       拿的是 {@code evidenceDeadline} 这一个事实 —— 页面停着不动也不会变成假数据。</li>
 *   <li><b>已超期的单据显示成「已超期」而不是一个负数</b>：超期自动判负是系统做的，
 *       页面**不提供**「手动标记超期判负」的入口 —— 提前结案不该是一个按钮。</li>
 * </ol>
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/platform-disputes} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 */
export function PlatformDisputeWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [disputeType, setDisputeType] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [settlementState, setSettlementState] = useState('');
  const [overdueOnly, setOverdueOnly] = useState(false);
  const [keyword, setKeyword] = useState('');

  const [data, setData] = useState<PlatformDisputePage | null>(null);
  const [caps, setCaps] = useState<PlatformDisputeCapabilities | null>(null);
  const [detail, setDetail] = useState<PlatformDisputeDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // ---- 倒计时的「现在」：每秒走一格，倒计时由**绝对截止时刻**现算 ----
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  // ---- 登记平台纠纷 ----
  const [newPlatform, setNewPlatform] = useState('AMAZON');
  const [newPlatformName, setNewPlatformName] = useState('Amazon');
  const [newDisputeNo, setNewDisputeNo] = useState('');
  const [newType, setNewType] = useState('A_TO_Z');
  const [newReason, setNewReason] = useState('');
  const [newOrderNo, setNewOrderNo] = useState('');
  const [newCurrency, setNewCurrency] = useState('USD');
  const [newAmount, setNewAmount] = useState('');
  const [newDeadline, setNewDeadline] = useState('');

  // ---- 举证清单 ----
  const [evidenceType, setEvidenceType] = useState('ORDER_FULFILLMENT_PROOF');
  const [evidenceLabel, setEvidenceLabel] = useState('');
  const [evidenceRequired, setEvidenceRequired] = useState(true);
  const [missingReason, setMissingReason] = useState('');

  // ---- 立场 / 结果 / 结算 ----
  const [position, setPosition] = useState('UNDETERMINED');
  const [liabilityStage, setLiabilityStage] = useState('UNDETERMINED');
  const [positionNote, setPositionNote] = useState('');
  const [liabilityNote, setLiabilityNote] = useState('');
  const [resolutionCode, setResolutionCode] = useState('PLATFORM_RULING_AGAINST');
  const [resolutionNote, setResolutionNote] = useState('');
  const [withdrawReason, setWithdrawReason] = useState('');
  const [settleAmount, setSettleAmount] = useState('');
  const [financeReference, setFinanceReference] = useState('');
  const [rejectReason, setRejectReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」。
      const filters: Parameters<typeof fetchPlatformDisputes>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (disputeType) filters.disputeType = disputeType;
      if (platformCode) filters.platformCode = platformCode;
      if (settlementState) filters.settlementStatus = settlementState;
      if (overdueOnly) filters.overdueOnly = true;
      if (keyword) filters.keyword = keyword;
      setData(await fetchPlatformDisputes(filters));
      setCaps(await fetchPlatformDisputeCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [page, status, disputeType, platformCode, settlementState, overdueOnly, keyword]);

  useEffect(() => {
    void load();
  }, [load]);

  const open = useCallback(async (id: string) => {
    setError(null);
    try {
      setDetail(await fetchPlatformDispute(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<PlatformDisputeDetail>) => {
      setBusy(true);
      setError(null);
      try {
        const d = await work();
        setDetail(d);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const create = () => {
    void run(async () => {
      const d = await createPlatformDispute({
        platformCode: newPlatform,
        platformName: newPlatformName,
        platformDisputeNo: newDisputeNo,
        disputeType: newType,
        disputeReason: newReason || null,
        platformOrderNo: newOrderNo || null,
        disputeCurrency: newCurrency,
        disputeAmount: Number(newAmount),
        evidenceDeadline: new Date(newDeadline).toISOString(),
        ourPosition: position,
        liabilityStage: liabilityStage,
        positionNote: positionNote || null,
        liabilityNote: liabilityNote || null,
      });
      setNewDisputeNo('');
      setNewReason('');
      setNewOrderNo('');
      setNewAmount('');
      return d;
    });
  };

  const typeMismatch = platformTypeMismatchReason(newType, newPlatform);
  const summary = data?.summary;
  const head = detail?.head;
  const pendingSettlement = detail?.settlements.find((s) => isSettlementPending(s));

  /** 按币种分组的金额口径（**不折算**）。 */
  const currencyRows = useMemo(() => data?.currencyBreakdown ?? [], [data]);

  return (
    <div className="space-y-4">
      <PageHeader
        title="平台纠纷"
        description="管理平台纠纷/争议（Amazon A-to-Z、eBay 争议、速卖通纠纷）：类型、金额、平台时限、举证要求、我方立场、结果记录"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-1 h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <div className="border-destructive/40 bg-destructive/10 text-destructive flex items-start gap-2 rounded-lg border p-3 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <div>
            <div className="font-medium">操作失败</div>
            <div className="mt-1 break-words">{error}</div>
          </div>
        </div>
      ) : null}

      {/* ---- 第一屏：有多少纠纷在时限里 ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="纠纷总数" value={summary?.total ?? 0} polarity="neutral" />
        {/* ⚠️ 这一格是**超期未提交**的件数 —— 超期即自动判负，这一格就是「已经丢掉的」。 */}
        <StatCard label="已超期（自动判负）" value={summary?.overdue ?? 0} polarity="negative" />
        <StatCard
          label="举证准备中"
          value={summary?.evidencePreparing ?? 0}
          polarity="neutral"
        />
        <StatCard
          label="待财务确认"
          value={summary?.awaitingFinanceConfirm ?? 0}
          polarity="negative"
        />
      </div>

      {/* ---- 金额口径：按币种分组，不做固定汇率折算 ---- */}
      <SectionCard title="争议金额（按币种分组，不做汇率折算）">
        {currencyRows.length === 0 ? (
          <div className="text-muted-foreground py-4 text-center text-sm">暂无数据</div>
        ) : (
          <div className="flex flex-wrap gap-2">
            {currencyRows.map((c) => (
              <div
                key={c.currency}
                className="hover:bg-muted/50 flex items-center gap-2 rounded border px-3 py-2"
              >
                <Badge tone="info">{c.currency}</Badge>
                <span className="text-sm font-medium">{formatAmount(c.totalAmount)}</span>
                <span className="text-muted-foreground text-xs">
                  {c.disputeCount} 件 · 结算已生效 {formatAmount(c.effectiveAmount)}
                </span>
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 边界声明：哪些上下游还没落地 ---- */}
      {caps ? (
        <SectionCard title="模块边界（上下游未交付的逐条说明）">
          <div className="space-y-2 text-sm">
            {caps.related
              .filter((r) => !r.delivered)
              .map((r) => (
                <div key={r.id} className="flex flex-wrap items-center gap-2">
                  <Badge tone="warning">{r.ownerModule ?? '未交付'}</Badge>
                  <span className="font-medium">{r.label}</span>
                  <span className="text-muted-foreground text-xs">{r.reason}</span>
                </div>
              ))}
          </div>
          <div className="text-muted-foreground mt-3 text-xs">{caps.platformSyncGap}</div>
        </SectionCard>
      ) : null}

      {/* ---- 登记平台纠纷 ---- */}
      <SectionCard title="登记平台纠纷">
        <div className="grid gap-2 md:grid-cols-4">
          <select
            className="rounded border px-2 py-1 text-sm"
            value={newPlatform}
            onChange={(e) => {
              const code = e.target.value;
              setNewPlatform(code);
              setNewPlatformName(
                PLATFORM_OPTIONS.find((p) => p.code === code)?.label ?? code,
              );
            }}
          >
            {PLATFORM_OPTIONS.map((p) => (
              <option key={p.code} value={p.code}>
                {p.label}（{p.code}）
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={newType}
            onChange={(e) => setNewType(e.target.value)}
          >
            {DISPUTE_TYPE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <Input
            placeholder="平台纠纷单号（必填，如 AZ-2026-001）"
            value={newDisputeNo}
            onChange={(e) => setNewDisputeNo(e.target.value)}
          />
          <Input
            placeholder="平台订单号"
            value={newOrderNo}
            onChange={(e) => setNewOrderNo(e.target.value)}
          />
          <Input
            placeholder="币种（如 USD）"
            value={newCurrency}
            onChange={(e) => setNewCurrency(e.target.value.toUpperCase())}
          />
          <Input
            placeholder="争议金额"
            value={newAmount}
            onChange={(e) => setNewAmount(e.target.value)}
          />
          <Input
            placeholder="举证截止时刻（平台的时限）"
            type="datetime-local"
            value={newDeadline}
            onChange={(e) => setNewDeadline(e.target.value)}
          />
          <Input
            placeholder="纠纷事由"
            value={newReason}
            onChange={(e) => setNewReason(e.target.value)}
          />
        </div>
        {typeMismatch ? (
          <div className="mt-2 text-xs text-amber-600">{typeMismatch}</div>
        ) : null}
        <div className="mt-2 flex items-center gap-2">
          <Button
            disabled={
              busy ||
              Boolean(typeMismatch) ||
              !newDisputeNo ||
              !newAmount ||
              !newDeadline ||
              newCurrency.length !== 3
            }
            onClick={create}
          >
            登记纠纷
          </Button>
          <span className="text-muted-foreground text-xs">
            平台名与平台码按**登记那一刻的快照**落库（平台主数据在 channel 独立库，跨库读不到）。
            举证截止时刻是**真实时刻**，不是「7 天」这种相对描述。
          </span>
        </div>
      </SectionCard>

      {/* ---- 纠纷列表 ---- */}
      <SectionCard title="纠纷列表">
        <div className="mb-3 grid gap-2 md:grid-cols-4">
          <Input
            placeholder="关键字（单号 / 平台纠纷号 / 平台订单号）"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            {DISPUTE_STATUS_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={disputeType}
            onChange={(e) => setDisputeType(e.target.value)}
          >
            <option value="">全部类型</option>
            {DISPUTE_TYPE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={platformCode}
            onChange={(e) => setPlatformCode(e.target.value)}
          >
            <option value="">全部平台</option>
            {PLATFORM_OPTIONS.map((p) => (
              <option key={p.code} value={p.code}>
                {p.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={settlementState}
            onChange={(e) => setSettlementState(e.target.value)}
          >
            <option value="">全部结算口径</option>
            {SETTLEMENT_STATE_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={overdueOnly}
              onChange={(e) => setOverdueOnly(e.target.checked)}
            />
            只看已超期
          </label>
        </div>

        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : data && data.rows.length === 0 ? (
          <EmptyState
            title="没有平台纠纷"
            description="从上面的表单登记一件平台纠纷，或者调整筛选条件。"
          />
        ) : data ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">纠纷</th>
                <th className="py-2">平台 / 类型</th>
                <th className="py-2">金额</th>
                <th className="py-2">举证时限</th>
                <th className="py-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {data.rows.map((r) => (
                <tr
                  key={r.id}
                  className="hover:bg-muted/50 cursor-pointer border-b"
                  onClick={() => void open(r.id)}
                >
                  <td className="py-2">
                    <div>{r.disputeNo}</div>
                    <div className="text-muted-foreground text-xs">
                      平台纠纷号 {r.platformDisputeNo || '—'} · 订单 {r.platformOrderNo || '—'} ·{' '}
                      {formatTime(r.createdAt)}
                    </div>
                  </td>
                  <td className="py-2">
                    <div>{r.platformName}</div>
                    <div className="text-muted-foreground text-xs">{r.disputeTypeLabel}</div>
                  </td>
                  <td className="py-2">
                    {formatAmount(r.disputeAmount, r.disputeCurrency)}
                    <div className="text-muted-foreground text-xs">
                      结算已生效 {formatAmount(r.effectiveAmount)}
                    </div>
                  </td>
                  <td className="py-2">
                    {/* ⚠️ 倒计时由绝对截止时刻现算，不看接口给的快照。 */}
                    <Badge tone={deadlineTone(r.deadlineState)}>
                      {deadlineText(r.evidenceDeadline, r.deadlineState, now)}
                    </Badge>
                    <div className="text-muted-foreground text-xs">
                      截止 {formatTime(r.evidenceDeadline)}
                    </div>
                  </td>
                  <td className="py-2">
                    <div className="flex flex-wrap items-center gap-1">
                      <Badge
                        tone={
                          r.disputeStatus === 'RESOLVED_LOST'
                            ? 'danger'
                            : r.disputeStatus === 'RESOLVED_WON'
                              ? 'success'
                              : 'neutral'
                        }
                      >
                        {r.disputeStatusLabel}
                      </Badge>
                      {r.evidenceTotal > 0 ? (
                        <span className="text-muted-foreground text-xs">
                          举证 {r.evidenceProvided}/{r.evidenceTotal}
                          {r.evidenceMissing > 0 ? `（缺 ${r.evidenceMissing}）` : ''}
                        </span>
                      ) : null}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}
      </SectionCard>

      {/* ---- 详情 ---- */}
      {detail && head ? (
        <SectionCard title={`纠纷详情 · ${head.disputeNo}`}>
          <div className="space-y-3">
            {/* 头 */}
            <div className="grid gap-2 text-sm md:grid-cols-3">
              <div>
                <div className="text-muted-foreground text-xs">平台</div>
                <div>
                  {head.platformName}（{head.platformCode}）· {head.disputeTypeLabel}
                </div>
                <div className="text-muted-foreground text-xs">
                  平台纠纷号 {head.platformDisputeNo || '—'}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">争议金额</div>
                <div className="font-medium">
                  {formatAmount(head.disputeAmount, head.disputeCurrency)}
                </div>
                <div className="text-muted-foreground text-xs">
                  结算口径 {head.settlementStateLabel} · 已生效{' '}
                  {formatAmount(head.effectiveAmount)}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">举证时限</div>
                <div className="flex items-center gap-2">
                  <Badge tone={deadlineTone(head.deadlineState)}>
                    {deadlineText(head.evidenceDeadline, head.deadlineState, now)}
                  </Badge>
                </div>
                <div className="text-muted-foreground text-xs">
                  截止 {formatTime(head.evidenceDeadline)}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">我方立场</div>
                <div>{head.ourPositionLabel}</div>
                <div className="text-muted-foreground text-xs">{head.positionNote || '—'}</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">责任判定</div>
                <div>{head.liabilityStageLabel}</div>
                <div className="text-muted-foreground text-xs">{head.liabilityNote || '—'}</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">结果</div>
                <div>{head.resolutionCodeLabel ?? '—'}</div>
                <div className="text-muted-foreground text-xs">
                  {head.resolvedAt ? formatTime(head.resolvedAt) : '—'}
                </div>
              </div>
            </div>

            {/* 举证清单 */}
            <div>
              <div className="mb-1 flex items-center justify-between">
                <div className="text-sm font-medium">
                  举证清单（已具备 {head.evidenceProvided}/{head.evidenceTotal}
                  {head.evidenceMissing > 0 ? `，缺 ${head.evidenceMissing} 项` : ''}）
                </div>
                <Badge tone="info">
                  {head.evidenceMissing > 0 ? '必举证项不齐，提交不了' : '清单齐'}
                </Badge>
              </div>
              {detail.evidenceItems.length === 0 ? (
                <div className="text-muted-foreground text-xs">
                  还没有举证清单。举证要求是平台给的，先把该交的材料列出来。
                </div>
              ) : (
                <div className="space-y-1">
                  {detail.evidenceItems.map((item) => (
                    <div
                      key={item.id}
                      className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2 text-sm"
                    >
                      <span className="text-muted-foreground text-xs">#{item.evidenceSeq}</span>
                      <span className="font-medium">{item.evidenceTypeLabel}</span>
                      {item.evidenceLabel ? (
                        <span className="text-muted-foreground text-xs">{item.evidenceLabel}</span>
                      ) : null}
                      {item.required ? <Badge tone="neutral">必举</Badge> : null}
                      <Badge
                        tone={
                          item.evidenceState === 'PROVIDED'
                            ? 'success'
                            : item.evidenceState === 'MISSING'
                              ? 'danger'
                              : 'neutral'
                        }
                      >
                        {item.evidenceStateLabel}
                      </Badge>
                      {item.missingReason ? (
                        <span className="text-xs text-destructive">缺：{item.missingReason}</span>
                      ) : null}
                      {item.evidenceNote ? (
                        <span className="text-muted-foreground text-xs">{item.evidenceNote}</span>
                      ) : null}
                      <div className="ml-auto flex gap-1">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={
                            busy ||
                            actionBlockedReason(detail.actions, 'MARK_EVIDENCE') !== null
                          }
                          onClick={() =>
                            void run(async () =>
                              markPlatformEvidence(head.id, item.id, {
                                evidenceState: 'PROVIDED',
                                evidenceNote: '已上传',
                                missingReason: null,
                              }),
                            )
                          }
                        >
                          标已具备
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={
                            busy ||
                            actionBlockedReason(detail.actions, 'MARK_EVIDENCE') !== null
                          }
                          onClick={() =>
                            void run(async () =>
                              markPlatformEvidence(head.id, item.id, {
                                evidenceState: 'MISSING',
                                evidenceNote: null,
                                missingReason: missingReason || '缺（未填写原因）',
                              }),
                            )
                          }
                        >
                          标缺失
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={
                            busy ||
                            actionBlockedReason(detail.actions, 'MARK_EVIDENCE') !== null
                          }
                          onClick={() =>
                            void run(async () => removePlatformEvidenceItem(head.id, item.id))
                          }
                        >
                          移除
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
              )}

              {/* 加一项举证材料 */}
              <div className="mt-2 grid gap-2 md:grid-cols-4">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={evidenceType}
                  onChange={(e) => setEvidenceType(e.target.value)}
                >
                  {EVIDENCE_TYPE_OPTIONS.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <Input
                  placeholder="材料说明（如：3 月 5 日物流签收记录）"
                  value={evidenceLabel}
                  onChange={(e) => setEvidenceLabel(e.target.value)}
                />
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={evidenceRequired}
                    onChange={(e) => setEvidenceRequired(e.target.checked)}
                  />
                  必举证项
                </label>
                <Button
                  disabled={busy || actionBlockedReason(detail.actions, 'ADD_EVIDENCE_ITEM') !== null}
                  onClick={() =>
                    void run(async () =>
                      addPlatformEvidenceItem(head.id, {
                        evidenceType,
                        evidenceLabel: evidenceLabel || null,
                        required: evidenceRequired,
                        evidenceNote: null,
                      }),
                    )
                  }
                >
                  加举证材料项
                </Button>
              </div>
              <Input
                className="mt-2"
                placeholder="缺失原因（标「缺失」时必填）"
                value={missingReason}
                onChange={(e) => setMissingReason(e.target.value)}
              />
            </div>

            {/* 结算影响分录 */}
            {detail.settlements.length > 0 ? (
              <div>
                <div className="mb-1 text-sm font-medium">结算影响分录</div>
                <div className="space-y-1">
                  {detail.settlements.map((s) => (
                    <div
                      key={s.id}
                      className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2 text-sm"
                    >
                      <Badge
                        tone={
                          s.settlementStatus === 'CONFIRMED'
                            ? 'success'
                            : s.settlementStatus === 'REJECTED'
                              ? 'danger'
                              : 'warning'
                        }
                      >
                        {s.settlementStatusLabel}
                      </Badge>
                      {/* ⚠️ 登记 ≠ 生效：这一行必须让人一眼看出「还没生效」。 */}
                      <span className="text-xs">{settlementEffectiveHint(s)}</span>
                      {s.financeReference ? (
                        <span className="text-muted-foreground text-xs">
                          财务单据号 {s.financeReference}
                        </span>
                      ) : null}
                      <div className="ml-auto flex gap-1">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={
                            busy || actionBlockedReason(detail.actions, 'CONFIRM_SETTLEMENT') !== null
                          }
                          onClick={() =>
                            void run(async () =>
                              confirmPlatformDisputeSettlement(head.id, s.id, {
                                financeReference: financeReference || 'FIN-PENDING',
                              }),
                            )
                          }
                        >
                          财务确认生效
                        </Button>
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={
                            busy || actionBlockedReason(detail.actions, 'REJECT_SETTLEMENT') !== null
                          }
                          onClick={() =>
                            void run(async () =>
                              rejectPlatformDisputeSettlement(head.id, s.id, {
                                rejectReason: rejectReason || '财务驳回',
                              }),
                            )
                          }
                        >
                          财务驳回
                        </Button>
                      </div>
                    </div>
                  ))}
                </div>
                <div className="mt-1 flex gap-2">
                  <Input
                    placeholder="财务单据号"
                    value={financeReference}
                    onChange={(e) => setFinanceReference(e.target.value)}
                  />
                  <Input
                    placeholder="驳回理由"
                    value={rejectReason}
                    onChange={(e) => setRejectReason(e.target.value)}
                  />
                </div>
              </div>
            ) : null}

            {/* 动作区 */}
            <div className="flex flex-wrap items-end gap-2 border-t pt-3">
              <Button
                variant="outline"
                disabled={busy || actionBlockedReason(detail.actions, 'START_EVIDENCE') !== null}
                onClick={() =>
                  void run(async () => startPlatformEvidence(head.id, head.version))
                }
              >
                进入举证准备
              </Button>
              <Button
                variant="outline"
                disabled={busy || actionBlockedReason(detail.actions, 'UPDATE_POSITION') !== null}
                onClick={() =>
                  void run(async () =>
                    updatePlatformDisputePosition(head.id, {
                      ourPosition: position,
                      liabilityStage: liabilityStage,
                      positionNote: positionNote || null,
                      liabilityNote: liabilityNote || null,
                      expectedVersion: head.version,
                    }),
                  )
                }
              >
                更新立场与责任判定
              </Button>
              <Button
                disabled={busy || actionBlockedReason(detail.actions, 'SUBMIT_EVIDENCE') !== null}
                onClick={() =>
                  void run(async () =>
                    submitPlatformEvidence(head.id, {
                      submitNote: null,
                      expectedVersion: head.version,
                    }),
                  )
                }
              >
                提交举证
              </Button>
              <Button
                variant="outline"
                disabled={busy || actionBlockedReason(detail.actions, 'REGISTER_SETTLEMENT') !== null}
                onClick={() =>
                  void run(async () =>
                    registerPlatformDisputeSettlement(head.id, {
                      requestedAmount: Number(settleAmount),
                    }),
                  )
                }
              >
                登记结算影响
              </Button>
              <Button
                variant="ghost"
                disabled={busy || actionBlockedReason(detail.actions, 'WITHDRAW') !== null}
                onClick={() =>
                  void run(async () =>
                    withdrawPlatformDispute(head.id, {
                      withdrawReason: withdrawReason || null,
                      expectedVersion: head.version,
                    }),
                  )
                }
              >
                我方撤诉
              </Button>
            </div>

            <div className="grid gap-2 md:grid-cols-4">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={position}
                onChange={(e) => setPosition(e.target.value)}
              >
                {POSITION_OPTIONS.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
              <select
                className="rounded border px-2 py-1 text-sm"
                value={liabilityStage}
                onChange={(e) => setLiabilityStage(e.target.value)}
              >
                {LIABILITY_STAGE_OPTIONS.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
              <Input
                placeholder="立场说明"
                value={positionNote}
                onChange={(e) => setPositionNote(e.target.value)}
              />
              <Input
                placeholder="责任判定说明"
                value={liabilityNote}
                onChange={(e) => setLiabilityNote(e.target.value)}
              />
            </div>

            <div className="grid gap-2 md:grid-cols-4">
              <select
                className="rounded border px-2 py-1 text-sm"
                value={resolutionCode}
                onChange={(e) => setResolutionCode(e.target.value)}
              >
                {RESOLUTION_OPTIONS.map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
              <Input
                placeholder="裁决说明"
                value={resolutionNote}
                onChange={(e) => setResolutionNote(e.target.value)}
              />
              <Input
                placeholder="登记的结算影响金额"
                value={settleAmount}
                onChange={(e) => setSettleAmount(e.target.value)}
              />
              <Input
                placeholder="撤诉理由"
                value={withdrawReason}
                onChange={(e) => setWithdrawReason(e.target.value)}
              />
            </div>
            <Button
              variant="outline"
              disabled={busy || actionBlockedReason(detail.actions, 'RESOLVE') !== null}
              onClick={() =>
                void run(async () =>
                  resolvePlatformDispute(head.id, {
                    resolutionCode,
                    resolutionNote: resolutionNote || null,
                    expectedVersion: head.version,
                  }),
                )
              }
            >
              记录平台裁决结果
            </Button>

            {/* 留痕 */}
            <div>
              <div className="mb-1 text-sm font-medium">留痕</div>
              <div className="space-y-1">
                {detail.events.map((e) => (
                  <div key={e.id} className="text-muted-foreground text-xs">
                    {formatTime(e.occurredAt)} · {e.eventTypeLabel}
                    {e.reasonNote ? ` — ${e.reasonNote}` : ''}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
