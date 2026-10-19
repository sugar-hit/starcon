'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  SupplierClaimCandidatePage,
  SupplierClaimCapabilities,
  SupplierClaimDetail,
  SupplierClaimPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionBlockedReason,
  addSupplierClaimItem,
  agreeSupplierClaim,
  CLAIM_REASON_OPTIONS,
  CLAIM_STATUS_OPTIONS,
  createSupplierClaim,
  fetchSupplierClaim,
  fetchSupplierClaimCandidates,
  fetchSupplierClaimCapabilities,
  fetchSupplierClaims,
  formatAmount,
  formatTime,
  isSettlementPending,
  NEGOTIATION_ROLE_OPTIONS,
  NEGOTIATION_TYPE_OPTIONS,
  OFFSET_STATUS_OPTIONS,
  recordSupplierClaimNegotiation,
  registerSupplierClaimSettlement,
  rejectSupplierClaim,
  rejectSupplierClaimSettlement,
  removeSupplierClaimItem,
  SETTLEMENT_METHOD_OPTIONS,
  settlementEffectiveHint,
  stageBlockedReason,
  startSupplierClaimNegotiation,
  submitSupplierClaim,
  withdrawSupplierClaim,
  confirmSupplierClaimSettlement,
} from './supplier-claim-model';

/**
 * 11.2.3.3 供应商索赔。
 *
 * <p>蓝图：「对质量原因退货向供应商发起索赔：关联批次与责任判定、索赔金额、协商记录、
 * 赔付与冲减应付处理。」
 * 输出：「索赔单 + 冲减应付或退款记录。」
 * 加粗句：「<b>索赔不是自动到账</b>，系统只跟踪流程与金额，冲减应付需财务确认。」
 *
 * <p><b>页面围绕两条硬规则组织</b>：
 *
 * <ol>
 *   <li><b>索赔基数不由页面算</b>：候选行里的「索赔基数」与「本行上限」是**后端算好的**
 *       推导值（= 质检原值 × 数量 ÷ 实检数量），页面只读不重算。
 *       页面也**不提供**「原值」输入框 —— 一个可填的金额等于把财务口径交给每个操作员自己编。</li>
 *   <li><b>登记赔付 ≠ 冲减生效</b>：登记那一栏显示「已登记 8.00 USD / 尚未生效（生效 0）」，
 *       「财务确认冲减」是**另一个按钮、另一种语气**，且必须回填财务单据号。
 *       页面**刻意没有**「标记已结算」的入口 —— 蓝图那句话说的恰恰相反。</li>
 * </ol>
 *
 * <p>这是**真实工作区**：数据全部来自 {@code GET /trade/supplier-claims} 等真实端点，
 * 加载 / 空 / 错误三态齐全，不是只有字段名的占位页。
 */
export function SupplierClaimWorkspace() {
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('');
  const [offsetStatus, setOffsetStatus] = useState('');
  const [liabilityStage, setLiabilityStage] = useState('');
  const [supplierRefId, setSupplierRefId] = useState('');
  const [minAmount, setMinAmount] = useState('');
  const [maxAmount, setMaxAmount] = useState('');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [keyword, setKeyword] = useState('');

  const [data, setData] = useState<SupplierClaimPage | null>(null);
  const [candidates, setCandidates] = useState<SupplierClaimCandidatePage | null>(null);
  const [caps, setCaps] = useState<SupplierClaimCapabilities | null>(null);
  const [detail, setDetail] = useState<SupplierClaimDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);

  // ---- 发起索赔 ----
  const [newReason, setNewReason] = useState('QUALITY_DEFECT');
  const [newNote, setNewNote] = useState('');
  const [newCurrency, setNewCurrency] = useState('USD');
  const [newSupplier, setNewSupplier] = useState('');

  // ---- 加明细 ----
  const [pickInspection, setPickInspection] = useState('');
  const [pickAttribution, setPickAttribution] = useState('');
  const [pickQty, setPickQty] = useState('');
  const [pickAmount, setPickAmount] = useState('');

  // ---- 协商 ----
  const [negType, setNegType] = useState('SUPPLIER_RESPONSE');
  const [negRole, setNegRole] = useState('SUPPLIER');
  const [negProposed, setNegProposed] = useState('');
  const [negAgreed, setNegAgreed] = useState('');
  const [negNote, setNegNote] = useState('');

  // ---- 赔付 / 冲减 ----
  const [settleMethod, setSettleMethod] = useState('OFFSET_PAYABLE');
  const [settleAmount, setSettleAmount] = useState('');
  const [financeReference, setFinanceReference] = useState('');
  const [rejectReason, setRejectReason] = useState('');
  const [withdrawReason, setWithdrawReason] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      // ⚠️ exactOptionalPropertyTypes：筛选条件只能「要么有要么没有」，
      //   不能把 undefined 当成一个已给的键塞进去。
      const filters: Parameters<typeof fetchSupplierClaims>[0] = { page, size: 20 };
      if (status) filters.status = status;
      if (offsetStatus) filters.offsetStatus = offsetStatus;
      if (liabilityStage) filters.liabilityStage = liabilityStage;
      if (supplierRefId) filters.supplierRefId = supplierRefId;
      if (minAmount) filters.minAmount = Number(minAmount);
      if (maxAmount) filters.maxAmount = Number(maxAmount);
      if (keyword) filters.keyword = keyword;
      if (fromDate) filters.from = new Date(`${fromDate}T00:00:00Z`).toISOString();
      if (toDate) filters.to = new Date(`${toDate}T23:59:59Z`).toISOString();
      setData(await fetchSupplierClaims(filters));
      setCandidates(await fetchSupplierClaimCandidates({ page: 0, size: 20 }));
      setCaps(await fetchSupplierClaimCapabilities());
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }, [
    page,
    status,
    offsetStatus,
    liabilityStage,
    supplierRefId,
    minAmount,
    maxAmount,
    keyword,
    fromDate,
    toDate,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const open = useCallback(async (id: string) => {
    setError(null);
    try {
      setDetail(await fetchSupplierClaim(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  const run = useCallback(
    async (work: () => Promise<SupplierClaimDetail>) => {
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
      const d = await createSupplierClaim({
        supplierRefId: newSupplier,
        claimReasonCode: newReason,
        claimReasonNote: newNote,
        claimCurrency: newCurrency,
      });
      setNewNote('');
      setNewSupplier('');
      return d;
    });
  };

  const addItem = () => {
    if (!detail) return;
    void run(async () =>
      addSupplierClaimItem(detail.head.id, {
        inspectionId: pickInspection,
        attributionId: pickAttribution,
        claimQty: pickQty ? Number(pickQty) : null,
        claimAmount: Number(pickAmount),
      }),
    );
  };

  const recordNegotiation = () => {
    if (!detail) return;
    void run(async () =>
      recordSupplierClaimNegotiation(detail.head.id, {
        negotiationType: negType,
        participantRole: negRole,
        proposedAmount: negProposed ? Number(negProposed) : null,
        agreedAmount: negAgreed ? Number(negAgreed) : null,
        negotiationNote: negNote || null,
        occurredAt: new Date().toISOString(),
      }),
    );
  };

  const registerSettlement = () => {
    if (!detail) return;
    void run(async () =>
      registerSupplierClaimSettlement(detail.head.id, {
        settlementMethod: settleMethod,
        requestedAmount: Number(settleAmount),
      }),
    );
  };

  const summary = data?.summary;
  const pendingSettlement = detail?.settlements.find((s) => isSettlementPending(s));

  return (
    <div className="space-y-4">
      <PageHeader
        title="供应商索赔"
        description="对质量原因退货向供应商发起索赔：关联批次与责任判定、协商与赔付，冲减应付需财务确认"
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

      {/* ---- 第一屏：账面上有多少钱在途 ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="索赔总额"
          value={formatAmount(summary?.totalClaimedAmount ?? 0)}
          polarity="neutral"
        />
        <StatCard
          label="已达成"
          value={formatAmount(summary?.totalAgreedAmount ?? 0)}
          polarity="positive"
        />
        {/* ⚠️ 这一格只累加**已被财务确认**的生效金额 —— 待确认的赔付不算数。 */}
        <StatCard
          label="冲减已生效"
          value={formatAmount(summary?.totalAppliedOffsetAmount ?? 0)}
          polarity="positive"
        />
        <StatCard
          label="待财务确认"
          value={summary?.pendingFinanceConfirmCount ?? 0}
          polarity="negative"
        />
      </div>

      {/* ---- 边界声明：哪些下游还没落地 ---- */}
      {caps ? (
        <SectionCard title="模块边界（下游未交付的逐条说明）">
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
        </SectionCard>
      ) : null}

      {/* ---- 发起索赔 ---- */}
      <SectionCard title="发起索赔">
        <div className="grid gap-2 md:grid-cols-4">
          <Input
            placeholder="供应商引用 id（quality_supplier_ref）"
            value={newSupplier}
            onChange={(e) => setNewSupplier(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={newReason}
            onChange={(e) => setNewReason(e.target.value)}
          >
            {CLAIM_REASON_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <Input
            placeholder="币种（如 USD）"
            value={newCurrency}
            onChange={(e) => setNewCurrency(e.target.value.toUpperCase())}
          />
          <Input
            placeholder="索赔理由（必填）"
            value={newNote}
            onChange={(e) => setNewNote(e.target.value)}
          />
        </div>
        <div className="mt-2 flex items-center gap-2">
          <Button
            disabled={busy || !newSupplier || !newNote || newCurrency.length !== 3}
            onClick={create}
          >
            建索赔单（草稿）
          </Button>
          <span className="text-muted-foreground text-xs">
            建单后恒为草稿：明细录完才能提交。供应商必须先在供应商引用镜像里登记。
          </span>
        </div>
      </SectionCard>

      {/* ---- 可索赔候选（发起索赔的唯一入口素材）---- */}
      <SectionCard title="可索赔候选（已判定 + 已归因 + 环节可索赔）">
        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : candidates && candidates.rows.length === 0 ? (
          <EmptyState
            title="没有可索赔的批次"
            description="候选 = 已判定的质检结论 × 已归因的责任判定，且责任环节落在生产 / 描述不符。先去 11.2.3.1 判质检、11.1.2.2 出责任判定。"
          />
        ) : candidates ? (
          <div className="space-y-2">
            {candidates.rows.map((r) => {
              const blocked = stageBlockedReason(r.attributedStage);
              return (
                <div
                  key={r.inspectionId}
                  className="hover:bg-muted/50 flex flex-wrap items-center gap-2 rounded border p-2"
                >
                  <div className="min-w-0 flex-1">
                    <div className="text-sm font-medium">
                      {r.skuName || r.skuCode} · {r.skuCode}
                    </div>
                    <div className="text-muted-foreground text-xs">
                      质检单 {r.inspectionNo}（{r.verdictLabel ?? '—'}）· 归因 {r.attributionNo}
                      <span className="mx-1">|</span>
                      责任环节 {r.attributedStageLabel} · 批次 {r.lotCode || '—'}
                    </div>
                    <div className="text-muted-foreground mt-0.5 text-xs">
                      实检 {String(r.inspectedQty)} · 索赔基数{' '}
                      <b>{formatAmount(r.claimableBaseAmount, r.claimCurrency)}</b> · 本行上限{' '}
                      <b>{formatAmount(r.maxClaimAmount, r.claimCurrency)}</b>
                    </div>
                    {blocked ? (
                      <div className="mt-0.5 text-xs text-amber-600">{blocked}</div>
                    ) : null}
                  </div>
                  <Button
                    disabled={busy || Boolean(blocked)}
                    onClick={() => {
                      setPickInspection(r.inspectionId);
                      setPickAttribution(r.attributionId);
                      setPickQty(String(r.inspectedQty));
                      setPickAmount(String(r.maxClaimAmount));
                    }}
                  >
                    选它
                  </Button>
                </div>
              );
            })}
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 索赔单列表 ---- */}
      <SectionCard title="索赔单">
        <div className="mb-3 grid gap-2 md:grid-cols-4">
          <Input
            placeholder="关键字（单号 / 供应商编码 / 名称）"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="rounded border px-2 py-1 text-sm"
            value={status}
            onChange={(e) => setStatus(e.target.value)}
          >
            <option value="">全部状态</option>
            {CLAIM_STATUS_OPTIONS.map((o) => (
              <option key={o.code} value={o.code}>
                {o.label}
              </option>
            ))}
          </select>
          <select
            className="rounded border px-2 py-1 text-sm"
            value={offsetStatus}
            onChange={(e) => setOffsetStatus(e.target.value)}
          >
            <option value="">全部冲减状态</option>
            {OFFSET_STATUS_OPTIONS.map((o) => (
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
            <option value="">全部责任环节</option>
            <option value="PRODUCTION">生产</option>
            <option value="DESCRIPTION">描述不符</option>
          </select>
          <Input
            placeholder="供应商引用 id"
            value={supplierRefId}
            onChange={(e) => setSupplierRefId(e.target.value)}
          />
          <Input
            placeholder="金额下限"
            value={minAmount}
            onChange={(e) => setMinAmount(e.target.value)}
          />
          <Input
            placeholder="金额上限"
            value={maxAmount}
            onChange={(e) => setMaxAmount(e.target.value)}
          />
          <Input placeholder="起始日期" type="date" value={fromDate} onChange={(e) => setFromDate(e.target.value)} />
          <Input placeholder="截止日期" type="date" value={toDate} onChange={(e) => setToDate(e.target.value)} />
        </div>

        {loading ? (
          <div className="text-muted-foreground py-6 text-center text-sm">加载中…</div>
        ) : data && data.rows.length === 0 ? (
          <EmptyState
            title="没有索赔单"
            description="从上面的候选里挑一批已判定的货发起索赔，或者调整筛选条件。"
          />
        ) : data ? (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">索赔单</th>
                <th className="py-2">供应商</th>
                <th className="py-2">状态</th>
                <th className="py-2">索赔 / 达成</th>
                <th className="py-2">冲减</th>
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
                    <div>{r.claimNo}</div>
                    <div className="text-muted-foreground text-xs">
                      {r.claimReasonLabel} · {r.itemCount} 条明细 · {formatTime(r.createdAt)}
                    </div>
                  </td>
                  <td className="py-2">
                    <div>{r.supplierName || r.supplierCode || '—'}</div>
                    <div className="text-muted-foreground text-xs">{r.supplierCode}</div>
                  </td>
                  <td className="py-2">
                    <Badge tone={r.claimStatus === 'SETTLED' ? 'success' : 'neutral'}>
                      {r.claimStatusLabel}
                    </Badge>
                  </td>
                  <td className="py-2 text-xs">
                    {formatAmount(r.claimedAmount, r.claimCurrency)}
                    <span className="text-muted-foreground">
                      {' '}
                      / 达成 {r.agreedAmount == null ? '—' : formatAmount(r.agreedAmount, r.claimCurrency)}
                    </span>
                  </td>
                  <td className="py-2 text-xs">
                    <Badge
                      tone={
                        r.offsetStatus === 'APPLIED'
                          ? 'success'
                          : r.offsetStatus === 'PENDING_FINANCE_CONFIRM'
                            ? 'warning'
                            : 'neutral'
                      }
                    >
                      {r.offsetStatusLabel}
                    </Badge>
                    <div className="text-muted-foreground">
                      生效 {formatAmount(r.appliedOffsetAmount, r.claimCurrency)}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : null}

        {data && data.total > data.size ? (
          <div className="mt-3 flex gap-2">
            <Button disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
              上一页
            </Button>
            <span className="text-muted-foreground self-center text-sm">
              第 {data.page + 1} 页 / 共 {data.total} 条
            </span>
            <Button
              disabled={(page + 1) * data.size >= data.total}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        ) : null}
      </SectionCard>

      {/* ---- 单张索赔单 ---- */}
      {detail ? (
        <SectionCard title={`索赔单 ${detail.head.claimNo}`}>
          <div className="space-y-4">
            <div className="grid gap-2 text-sm md:grid-cols-4">
              <div>
                <div className="text-muted-foreground text-xs">供应商</div>
                <div>{detail.head.supplierName || detail.head.supplierCode}</div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">状态</div>
                <div>
                  <Badge tone={detail.head.claimStatus === 'SETTLED' ? 'success' : 'neutral'}>
                    {detail.head.claimStatusLabel}
                  </Badge>
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">索赔 / 基数 / 达成</div>
                <div className="text-xs">
                  {formatAmount(detail.head.claimedAmount, detail.head.claimCurrency)} /{' '}
                  {formatAmount(detail.head.claimableBaseAmount, detail.head.claimCurrency)} /{' '}
                  {detail.head.agreedAmount == null
                    ? '—'
                    : formatAmount(detail.head.agreedAmount, detail.head.claimCurrency)}
                </div>
              </div>
              <div>
                <div className="text-muted-foreground text-xs">冲减</div>
                <div className="text-xs">
                  {detail.head.offsetStatusLabel} · 生效{' '}
                  {formatAmount(detail.head.appliedOffsetAmount, detail.head.claimCurrency)}
                </div>
              </div>
            </div>

            {/* ---- 明细 ---- */}
            <div>
              <div className="mb-1 text-sm font-medium">索赔明细</div>
              {detail.items.length === 0 ? (
                <div className="text-muted-foreground text-xs">
                  还没有明细 —— 提交前至少要一条（发出去的索赔单要能让供应商看懂赔的是哪几批货）。
                </div>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="py-1">SKU / 批次</th>
                      <th className="py-1">责任环节</th>
                      <th className="py-1">数量</th>
                      <th className="py-1">索赔 / 基数 / 上限</th>
                      <th className="py-1" />
                    </tr>
                  </thead>
                  <tbody>
                    {detail.items.map((i) => (
                      <tr key={i.id} className="border-b">
                        <td className="py-1 text-xs">
                          {i.skuName || i.skuCode} · 批次 {i.lotCode || '—'}
                        </td>
                        <td className="py-1 text-xs">{i.attributedStageLabel || i.attributedStage}</td>
                        <td className="py-1 text-xs">{String(i.claimQty)}</td>
                        <td className="py-1 text-xs">
                          {formatAmount(i.claimAmount, i.claimCurrency)} /{' '}
                          {formatAmount(i.claimableBaseAmount, i.claimCurrency)} /{' '}
                          {formatAmount(i.maxClaimAmount, i.claimCurrency)}
                        </td>
                        <td className="py-1 text-right">
                          <Button
                            variant="ghost"
                            disabled={busy || detail.head.claimStatus !== 'DRAFT'}
                            onClick={() =>
                              void run(async () => removeSupplierClaimItem(detail.head.id, i.id))
                            }
                          >
                            移除
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              {/* ---- 加明细（基数与上限由后端算，页面不重算）---- */}
              <div className="mt-2 grid gap-2 md:grid-cols-4">
                <Input
                  placeholder="质检结论 id"
                  value={pickInspection}
                  onChange={(e) => setPickInspection(e.target.value)}
                />
                <Input
                  placeholder="责任判定 id"
                  value={pickAttribution}
                  onChange={(e) => setPickAttribution(e.target.value)}
                />
                <Input
                  placeholder="索赔数量"
                  value={pickQty}
                  onChange={(e) => setPickQty(e.target.value)}
                />
                <Input
                  placeholder="索赔金额（不得超过本行上限）"
                  value={pickAmount}
                  onChange={(e) => setPickAmount(e.target.value)}
                />
              </div>
              <div className="mt-2 flex items-center gap-2">
                <Button
                  disabled={busy || !pickInspection || !pickAttribution || !pickAmount}
                  onClick={addItem}
                >
                  加入明细
                </Button>
                <span className="text-muted-foreground text-xs">
                  索赔基数 = 质检原值；本行上限 = 原值 × 数量 ÷ 实检数量，由后端算，页面不重算。
                </span>
              </div>
            </div>

            {/* ---- 状态流转 ---- */}
            <div className="flex flex-wrap gap-2">
              <Button
                disabled={busy || Boolean(actionBlockedReason(detail.actions, 'SUBMIT'))}
                onClick={() =>
                  void run(async () =>
                    submitSupplierClaim(detail.head.id, {
                      submitNote: '',
                      expectedVersion: detail.head.version,
                    }),
                  )
                }
              >
                提交索赔
              </Button>
              <Button
                disabled={busy || Boolean(actionBlockedReason(detail.actions, 'RECORD_NEGOTIATION'))}
                onClick={recordNegotiation}
              >
                记录协商
              </Button>
              <Button
                disabled={busy || Boolean(actionBlockedReason(detail.actions, 'AGREE'))}
                onClick={() =>
                  void run(async () =>
                    agreeSupplierClaim(detail.head.id, { expectedVersion: detail.head.version }),
                  )
                }
              >
                置为已达成
              </Button>
              <Button
                disabled={busy || Boolean(actionBlockedReason(detail.actions, 'REGISTER_SETTLEMENT'))}
                onClick={registerSettlement}
              >
                登记赔付
              </Button>
              <Button
                disabled={busy || Boolean(actionBlockedReason(detail.actions, 'WITHDRAW_CLAIM'))}
                onClick={() =>
                  void run(async () =>
                    withdrawSupplierClaim(detail.head.id, {
                      withdrawReason,
                      expectedVersion: detail.head.version,
                    }),
                  )
                }
              >
                撤回索赔
              </Button>
              <Button
                disabled={busy || Boolean(actionBlockedReason(detail.actions, 'REJECT_CLAIM'))}
                onClick={() =>
                  void run(async () =>
                    rejectSupplierClaim(detail.head.id, {
                      rejectReason,
                      expectedVersion: detail.head.version,
                    }),
                  )
                }
              >
                驳回索赔
              </Button>
            </div>
            {/* 不可执行的动作要**说清楚为什么**，而不是把按钮灰掉让人猜。 */}
            <div className="text-muted-foreground space-y-0.5 text-xs">
              {detail.actions
                .filter((a) => !a.allowed && a.blockedReason)
                .map((a) => (
                  <div key={a.code}>
                    {a.label}：{a.blockedReason}
                  </div>
                ))}
            </div>

            {/* ---- 协商记录 ---- */}
            <div>
              <div className="mb-1 text-sm font-medium">协商记录</div>
              {detail.negotiations.length === 0 ? (
                <div className="text-muted-foreground text-xs">
                  还没有协商记录 —— 协商是必经环节：提交后先记一条协商才能进协商中。
                </div>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="py-1">#</th>
                      <th className="py-1">类型 / 参与方</th>
                      <th className="py-1">提议 / 达成</th>
                      <th className="py-1">说明</th>
                      <th className="py-1">发生时间</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.negotiations.map((n) => (
                      <tr key={n.id} className="border-b">
                        <td className="py-1 text-xs">{n.negotiationSeq}</td>
                        <td className="py-1 text-xs">
                          {n.negotiationTypeLabel} · {n.participantRoleLabel}
                        </td>
                        <td className="py-1 text-xs">
                          {n.proposedAmount == null ? '—' : formatAmount(n.proposedAmount)} /{' '}
                          {n.agreedAmount == null ? '—' : formatAmount(n.agreedAmount)}
                        </td>
                        <td className="py-1 text-xs">{n.negotiationNote || '—'}</td>
                        <td className="py-1 text-xs">{formatTime(n.occurredAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              <div className="mt-2 grid gap-2 md:grid-cols-5">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={negType}
                  onChange={(e) => setNegType(e.target.value)}
                >
                  {NEGOTIATION_TYPE_OPTIONS.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={negRole}
                  onChange={(e) => setNegRole(e.target.value)}
                >
                  {NEGOTIATION_ROLE_OPTIONS.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <Input
                  placeholder="提议金额"
                  value={negProposed}
                  onChange={(e) => setNegProposed(e.target.value)}
                />
                <Input
                  placeholder="达成金额（仅「达成一致」）"
                  value={negAgreed}
                  onChange={(e) => setNegAgreed(e.target.value)}
                />
                <Input
                  placeholder="协商说明"
                  value={negNote}
                  onChange={(e) => setNegNote(e.target.value)}
                />
              </div>
              <div className="mt-2 flex gap-2">
                <Button
                  disabled={busy || !negProposed}
                  onClick={() => {
                    recordNegotiation();
                    setNegProposed('');
                    setNegAgreed('');
                    setNegNote('');
                  }}
                >
                  记一条协商
                </Button>
                <Button
                  disabled={busy || detail.head.claimStatus !== 'SUBMITTED'}
                  onClick={() =>
                    void run(async () =>
                      startSupplierClaimNegotiation(detail.head.id, detail.head.version),
                    )
                  }
                >
                  进入协商
                </Button>
              </div>
            </div>

            {/* ---- 赔付 / 冲减应付 ---- */}
            <div>
              <div className="mb-1 text-sm font-medium">赔付 / 冲减应付</div>
              {detail.settlements.length === 0 ? (
                <div className="text-muted-foreground text-xs">
                  还没有赔付记录 —— 只有已达成一致的索赔单才能登记赔付。
                </div>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="py-1">方式</th>
                      <th className="py-1">登记 / 生效</th>
                      <th className="py-1">状态</th>
                      <th className="py-1">财务单据号</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.settlements.map((s) => (
                      <tr key={s.id} className="border-b">
                        <td className="py-1 text-xs">{s.settlementMethodLabel}</td>
                        <td className="py-1 text-xs">
                          {settlementEffectiveHint({
                            requestedAmount: s.requestedAmount,
                            effectiveAmount: s.effectiveAmount,
                            settlementStatus: s.settlementStatus,
                          })}
                        </td>
                        <td className="py-1 text-xs">
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
                        </td>
                        <td className="py-1 text-xs">{s.financeReference || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              <div className="mt-2 grid gap-2 md:grid-cols-4">
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={settleMethod}
                  onChange={(e) => setSettleMethod(e.target.value)}
                >
                  {SETTLEMENT_METHOD_OPTIONS.map((o) => (
                    <option key={o.code} value={o.code}>
                      {o.label}
                    </option>
                  ))}
                </select>
                <Input
                  placeholder="登记赔付金额"
                  value={settleAmount}
                  onChange={(e) => setSettleAmount(e.target.value)}
                />
                <Input
                  placeholder="财务单据号（财务确认时必填）"
                  value={financeReference}
                  onChange={(e) => setFinanceReference(e.target.value)}
                />
                <Input
                  placeholder="驳回 / 撤回理由"
                  value={rejectReason}
                  onChange={(e) => setRejectReason(e.target.value)}
                />
              </div>
              <div className="mt-2 flex flex-wrap items-center gap-2">
                <Button
                  disabled={busy || !settleAmount}
                  onClick={() => {
                    registerSettlement();
                    setSettleAmount('');
                  }}
                >
                  登记赔付
                </Button>
                {/* ⚠️ 财务确认是**另一个**按钮：登记 ≠ 生效。 */}
                <Button
                  disabled={busy || !pendingSettlement || !financeReference}
                  onClick={() => {
                    if (!pendingSettlement) return;
                    void run(async () =>
                      confirmSupplierClaimSettlement(detail.head.id, pendingSettlement.id, {
                        financeReference,
                      }),
                    );
                    setFinanceReference('');
                  }}
                >
                  财务确认冲减
                </Button>
                <Button
                  disabled={busy || !pendingSettlement || !rejectReason}
                  onClick={() => {
                    if (!pendingSettlement) return;
                    void run(async () =>
                      rejectSupplierClaimSettlement(detail.head.id, pendingSettlement.id, {
                        rejectReason,
                      }),
                    );
                    setRejectReason('');
                  }}
                >
                  财务驳回赔付
                </Button>
                <span className="text-muted-foreground text-xs">
                  登记赔付**不生效**（生效金额恒为 0）；只有财务确认冲减才生效。
                </span>
              </div>
            </div>

            {/* ---- 留痕 ---- */}
            <div>
              <div className="mb-1 text-sm font-medium">留痕</div>
              {detail.events.length === 0 ? (
                <div className="text-muted-foreground text-xs">暂无留痕</div>
              ) : (
                <ul className="space-y-1 text-xs">
                  {detail.events.map((e) => (
                    <li key={e.id} className="text-muted-foreground">
                      <span className="text-foreground">{e.eventTypeLabel}</span> ·{' '}
                      {formatTime(e.occurredAt)} · {e.reasonNote}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
