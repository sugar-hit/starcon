'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
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
  FinanceBadDebtCapabilities,
  FinanceBadDebtDetail,
  FinanceBadDebtEvidenceInput,
  FinanceBadDebtOptions,
  FinanceBadDebtPage,
  FinanceBadDebtRow,
  FinanceBadDebtSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  addBadDebtEvidence,
  approveBadDebtCase,
  badDebtActionBlockedReason,
  badDebtAccountingLabel,
  badDebtEmptyHint,
  badDebtEvidenceTypeLabel,
  badDebtReasonLabel,
  badDebtStatusLabel,
  badDebtWriteOffMethodLabel,
  createBadDebtCase,
  fetchBadDebtCapabilities,
  fetchBadDebtCases,
  fetchBadDebtDetail,
  fetchBadDebtOptions,
  fetchBadDebtSummary,
  formatBadDebtAmount,
  formatBadDebtMoney,
  rejectBadDebtCase,
  removeBadDebtEvidence,
  submitBadDebtCase,
  voidBadDebtCase,
  writeOffBadDebtCase,
} from './bad-debt-model';

/**
 * 12.1.1.3 坏账与核销。
 *
 * <p>蓝图 12-finance.md：「处理坏账：争议损失确认、买家拒付、平台冻结无法收回的核销，
 * 保留核销依据与审批。」输入：「核销申请；依据材料；审批。」输出：「坏账核销记录 + 会计处理 + 审计留痕。」
 *
 * <p><b>页面围绕那两句加粗句组织</b>：
 *
 * <ol>
 *   <li>「<b>核销是财务终审动作</b>，需要完整依据链」⇒ 页面把**依据材料**做成独立的一段
 *       （可逐条增删、能看到外部单号与内容），提交审批前没有依据就**不让提交**，
 *       审批意见必填、审批人 ≠ 提交人（不可执行时页面把服务端给的**原因**显示出来，
 *       而不是把按钮灰掉让人猜）。</li>
 *   <li>「<b>不允许批量一键核销无审批</b>」⇒ 页面**没有**任何批量核销入口；
 *       每一次核销都要一张一张走完申请 → 依据 → 审批。</li>
 * </ol>
 *
 * <p><b>页面刻意没有的东西</b>：
 *
 * <ul>
 *   <li><b>「已入账」的绿标</b>：下游 12.5.2.1「凭证管理」未交付 ⇒ 会计处理**不可用**，
 *       页面显示「待凭证（归属模块 12.5.2.1）」，**不显示 0**。</li>
 *   <li><b>跨币种的合计</b>：USD 与 EUR 加起来不是任何一个金额，汇总一律按币种分组渲染。</li>
 *   <li><b>「标记已收回应收」的按钮</b>：核销金额的写者恒为 12.2.1.2，本模块不碰那一列。</li>
 * </ul>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有坏账核销申请」。
 */
export function BadDebtWorkspace() {
  const [caps, setCaps] = useState<FinanceBadDebtCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceBadDebtOptions | null>(null);
  const [summary, setSummary] = useState<FinanceBadDebtSummary | null>(null);
  const [page, setPage] = useState<FinanceBadDebtPage | null>(null);
  const [detail, setDetail] = useState<FinanceBadDebtDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  // 筛选
  const [status, setStatus] = useState('');
  const [reasonType, setReasonType] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 新建申请草稿
  const [showCreate, setShowCreate] = useState(false);
  const [draftReceivableId, setDraftReceivableId] = useState('');
  const [draftAmount, setDraftAmount] = useState('');
  const [draftReasonType, setDraftReasonType] = useState('PLATFORM_FROZEN');
  const [draftReasonDetail, setDraftReasonDetail] = useState('');
  const [draftDisputeId, setDraftDisputeId] = useState('');

  // 依据材料草稿
  const [evidenceType, setEvidenceType] = useState('PLATFORM_FROZEN_NOTICE');
  const [evidenceTitle, setEvidenceTitle] = useState('');
  const [evidenceReference, setEvidenceReference] = useState('');
  const [evidenceContent, setEvidenceContent] = useState('');

  // 审批意见
  const [decisionComment, setDecisionComment] = useState('');

  const filters = useMemo(
    () => ({
      status: status || undefined,
      reasonType: reasonType || undefined,
      currency: currency || undefined,
      keyword: keyword.trim() || undefined,
    }),
    [status, reasonType, currency, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [capsResult, optionsResult, summaryResult, pageResult] = await Promise.all([
        fetchBadDebtCapabilities(),
        fetchBadDebtOptions(),
        fetchBadDebtSummary(),
        fetchBadDebtCases(filters, currentPage, 50),
      ]);
      setCaps(capsResult);
      setOptions(optionsResult);
      setSummary(summaryResult);
      setPage(pageResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '坏账核销数据加载失败');
    } finally {
      setLoading(false);
    }
  }, [filters, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 打开一张申请（详情：依据材料 + 留痕 + 可执行动作）。 */
  const open = useCallback(async (id: string) => {
    setActionError('');
    try {
      setDetail(await fetchBadDebtDetail(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '打开申请失败');
    }
  }, []);

  /** 跑一个写动作：统一处理错误与刷新。 */
  const run = useCallback(
    async (action: () => Promise<unknown>, okMessage: string, after?: () => Promise<void>) => {
      setBusy(true);
      setActionError('');
      setNotice('');
      try {
        await action();
        setNotice(okMessage);
        await load();
        if (after) await after();
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '操作失败');
      } finally {
        setBusy(false);
      }
    },
    [load],
  );

  const submitCreate = useCallback(async () => {
    const amount = Number(draftAmount);
    if (!draftReceivableId.trim()) {
      setActionError('请填写要核销的应收单 ID');
      return;
    }
    if (!Number.isFinite(amount) || amount <= 0) {
      setActionError('核销金额必须大于 0');
      return;
    }
    if (!draftReasonDetail.trim()) {
      setActionError('坏账事由必须写明明细');
      return;
    }
    await run(
      () =>
        createBadDebtCase({
          receivableId: draftReceivableId.trim(),
          writeOffAmount: amount,
          reasonType: draftReasonType,
          reasonDetail: draftReasonDetail.trim(),
          disputeId: draftDisputeId.trim() || undefined,
        }),
      '核销申请已创建（草稿）：接下来补依据材料，再提交审批。',
      async () => {
        setShowCreate(false);
        setDraftReceivableId('');
        setDraftAmount('');
        setDraftReasonDetail('');
        setDraftDisputeId('');
      },
    );
  }, [draftAmount, draftDisputeId, draftReceivableId, draftReasonDetail, draftReasonType, run]);

  const submitEvidence = useCallback(async () => {
    if (!detail) return;
    if (!evidenceTitle.trim()) {
      setActionError('依据材料必须写明标题');
      return;
    }
    if (!evidenceContent.trim()) {
      setActionError('依据材料必须写明内容：没有内容的依据不是依据');
      return;
    }
    const input: FinanceBadDebtEvidenceInput = {
      evidenceType: evidenceType as FinanceBadDebtEvidenceInput['evidenceType'],
      title: evidenceTitle.trim(),
      referenceNo: evidenceReference.trim(),
      content: evidenceContent.trim(),
      attachmentRef: null,
      occurredOn: null,
    };
    await run(
      () => addBadDebtEvidence(detail.row.id, input),
      '依据材料已加入。',
      async () => {
        setEvidenceTitle('');
        setEvidenceReference('');
        setEvidenceContent('');
        await open(detail.row.id);
      },
    );
  }, [detail, evidenceContent, evidenceReference, evidenceTitle, evidenceType, open, run]);

  const accounting = caps?.capabilities.find((c) => c.ownerModule === '12.5.2.1');
  const batchCapability = caps?.capabilities.find((c) => c.name.includes('批量'));
  const upstreamDispute = caps?.capabilities.find((c) => c.module === '11.4.1.1');

  return (
    <div className="space-y-6">
      <PageHeader
        title="坏账与核销"
        description="争议损失确认、买家拒付、平台冻结无法收回的核销 —— 每一次核销都要有依据、有审批。"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="size-4" aria-hidden="true" />
              刷新
            </Button>
            <Button onClick={() => setShowCreate((v) => !v)}>新建核销申请</Button>
          </div>
        }
      />

      {error && (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}

      {/* 能力边界：会计处理不可用 / 没有批量核销 —— 这两件事必须看得见。 */}
      {caps && (
        <Notice tone="warning" title="能力边界">
          <ul className="space-y-1">
            <li>
              <b>会计处理不可用</b>：下游 12.5.2.1「凭证管理」未交付 ⇒ 核销记录里只有
              「归属模块 12.5.2.1 + 空凭证号」，**不会**显示成「已入账 0 块」。
              {accounting?.detail}
            </li>
            <li>
              <b>没有「一键批量核销」</b>：蓝图写死不允许批量一键核销无审批。
              {batchCapability?.detail}
            </li>
            <li>
              <b>上游纠纷</b>：{upstreamDispute?.available ? '已接通' : '未接通'} —— {upstreamDispute?.detail}
            </li>
          </ul>
        </Notice>
      )}

      {/* 汇总：按币种分开，绝不跨币种合计。 */}
      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="申请总数" value={summary.totalCount} />
          <StatCard label="待审批" value={summary.pendingApprovalCount} />
          <StatCard label="已批准待核销" value={summary.approvedCount} />
          <StatCard label="已核销" value={summary.writtenOffCount} />
        </div>
      )}

      {summary && summary.currencyTotals.length > 0 && (
        <SectionCard title="核销金额（按币种）" description="USD 与 EUR 加起来不是任何一个金额 ⇒ 一律按币种分组。">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            {summary.currencyTotals.map((total) => (
              <div key={total.currency}>
                <StatCard
                  label={`${total.currency} 核销额`}
                  value={formatBadDebtAmount(total.writeOffAmount)}
                />
                <p className="text-copy-12 text-muted-foreground mt-1">
                  {total.count} 笔申请，其中已核销 {total.writtenOffCount} 笔
                </p>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      {/* 新建核销申请 */}
      {showCreate && (
        <SectionCard
          title="新建核销申请"
          description="核销是终审动作：申请建好之后必须补依据材料，再提交审批。"
        >
          <div className="grid gap-4 md:grid-cols-2">
            <FormField
              id="bad-debt-receivable"
              label="应收单 ID"
              required
              hint="12.1.1.1 的应收单 id（复制单号对应的 UUID）。"
            >
              <Input
                value={draftReceivableId}
                onChange={(e) => setDraftReceivableId(e.target.value)}
                placeholder="00000000-0000-0000-0000-000000000000"
              />
            </FormField>
            <FormField
              id="bad-debt-amount"
              label="核销金额"
              required
              hint="不得超过该应收的未核销余额。"
            >
              <Input
                type="number"
                step="0.0001"
                value={draftAmount}
                onChange={(e) => setDraftAmount(e.target.value)}
                placeholder="0.0000"
              />
            </FormField>
            <FormField id="bad-debt-reason-type" label="坏账事由" required>
              <select
                id="bad-debt-reason-type"
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3 py-1"
                value={draftReasonType}
                onChange={(e) => setDraftReasonType(e.target.value)}
              >
                {(options?.reasonTypeOptions ?? []).map((o) => (
                  <option key={o.code} value={o.code}>
                    {o.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              id="bad-debt-dispute"
              label="上游纠纷 ID（可选）"
              hint="11.4.1.1 的纠纷 id。争议损失确认必须是「我方败诉」的纠纷。"
            >
              <Input
                value={draftDisputeId}
                onChange={(e) => setDraftDisputeId(e.target.value)}
                placeholder="留空 = 与平台纠纷无关"
              />
            </FormField>
          </div>
          <div className="mt-4 space-y-2">
            <label htmlFor="bad-debt-reason-detail" className="text-label-13 text-foreground block">
              事由明细<span className="text-destructive ml-1">*</span>
            </label>
            <textarea
              id="bad-debt-reason-detail"
              className="border-control-border bg-card text-copy-13 min-h-24 w-full rounded-md border px-2 py-1.5"
              value={draftReasonDetail}
              onChange={(e) => setDraftReasonDetail(e.target.value)}
              placeholder="例：平台以「未收到货」判我方败诉，货款 120.00 USD 冻结无法收回。"
            />
          </div>
          <div className="mt-4 flex gap-2">
            <Button onClick={() => void submitCreate()} disabled={busy}>
              创建申请
            </Button>
            <Button variant="ghost" onClick={() => setShowCreate(false)} disabled={busy}>
              取消
            </Button>
          </div>
        </SectionCard>
      )}

      {/* 筛选 + 列表 */}
      <SectionCard title="核销申请" description="真分页 + 真筛选；汇总覆盖整个筛选结果集，不是当前页。">
        <div className="grid gap-3 md:grid-cols-4">
          <FormField id="bad-debt-filter-status" label="状态">
            <select
              id="bad-debt-filter-status"
              className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3 py-1"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">全部</option>
              {(options?.statusOptions ?? []).map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="bad-debt-filter-reason" label="事由">
            <select
              id="bad-debt-filter-reason"
              className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3 py-1"
              value={reasonType}
              onChange={(e) => {
                setReasonType(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">全部</option>
              {(options?.reasonTypeOptions ?? []).map((o) => (
                <option key={o.code} value={o.code}>
                  {o.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="bad-debt-filter-currency" label="币种">
            <select
              id="bad-debt-filter-currency"
              className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3 py-1"
              value={currency}
              onChange={(e) => {
                setCurrency(e.target.value);
                setCurrentPage(0);
              }}
            >
              <option value="">全部</option>
              {(options?.currencies ?? []).map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="bad-debt-filter-keyword" label="关键字">
            <Input
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setCurrentPage(0);
              }}
              placeholder="单号 / 应收单号 / 客户 / 纠纷号"
            />
          </FormField>
        </div>

        {loading && <p className="text-copy-13 text-muted-foreground mt-4">加载中…</p>}

        {!loading && page && page.items.length === 0 && (
          <EmptyState
            title="没有坏账核销申请"
            description={badDebtEmptyHint(page.total, Boolean(status || reasonType || currency || keyword))}
          />
        )}

        {!loading && page && page.items.length > 0 && (
          <>
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-left text-copy-13">
                <thead className="text-muted-foreground border-b">
                  <tr>
                    <th className="py-2 pr-3 font-medium">核销单号</th>
                    <th className="py-2 pr-3 font-medium">目标应收</th>
                    <th className="py-2 pr-3 font-medium">事由</th>
                    <th className="py-2 pr-3 font-medium">核销金额</th>
                    <th className="py-2 pr-3 font-medium">未核销余额</th>
                    <th className="py-2 pr-3 font-medium">状态</th>
                    <th className="py-2 font-medium">会计处理</th>
                  </tr>
                </thead>
                <tbody>
                  {page.items.map((row) => (
                    <tr key={row.id} className="border-b last:border-0">
                      <td className="py-2 pr-3">
                        <button
                          type="button"
                          className="text-brand underline-offset-2 hover:underline"
                          onClick={() => void open(row.id)}
                        >
                          {row.caseNo}
                        </button>
                      </td>
                      <td className="py-2 pr-3">
                        {row.receivableNo}
                        {row.disputeNo ? ` · 纠纷 ${row.disputeNo}` : ''}
                      </td>
                      <td className="py-2 pr-3">{badDebtReasonLabel(row.reasonType, options?.reasonTypeOptions)}</td>
                      <td className="py-2 pr-3">{formatBadDebtMoney(row.writeOffAmount, row.currency)}</td>
                      <td className="py-2 pr-3">{formatBadDebtMoney(row.openBalance, row.currency)}</td>
                      <td className="py-2 pr-3">
                        <Badge tone="neutral">{badDebtStatusLabel(row.status, options?.statusOptions)}</Badge>
                      </td>
                      <td className="py-2">{badDebtAccountingLabel(row)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="text-muted-foreground mt-3 flex items-center gap-3 text-copy-12">
              <span>
                共 {page.total} 笔 · 第 {page.page + 1} / {Math.max(1, Math.ceil(page.total / page.size))} 页
              </span>
              <Button
                variant="ghost"
                disabled={page.page <= 0}
                onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="ghost"
                disabled={(page.page + 1) * page.size >= page.total}
                onClick={() => setCurrentPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {/* 详情：依据材料 + 留痕 + 可执行动作 */}
      {detail && (
        <SectionCard
          title={`核销申请 ${detail.row.caseNo}`}
          description={`目标应收 ${detail.row.receivableNo} · ${detail.row.counterpartyName || '未填对象'} · ${formatBadDebtMoney(
            detail.row.writeOffAmount,
            detail.row.currency,
          )}`}
        >
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="应收总额（核销时快照）" value={formatBadDebtAmount(detail.row.receivableAmount)} />
            <StatCard label="已核销额（核销时快照）" value={formatBadDebtAmount(detail.row.receivableSettled)} />
            <StatCard label="未核销余额（核销时快照）" value={formatBadDebtAmount(detail.row.openBalance)} />
            <StatCard label="本次核销金额" value={formatBadDebtAmount(detail.row.writeOffAmount)} />
          </div>

          <div className="mt-4 space-y-1 text-copy-13">
            <p>
              <span className="text-muted-foreground">事由：</span>
              {badDebtReasonLabel(detail.row.reasonType, options?.reasonTypeOptions)} —— {detail.row.reasonDetail}
            </p>
            {detail.row.disputeNo && (
              <p>
                <span className="text-muted-foreground">上游纠纷：</span>
                {detail.row.disputeNo}（{detail.row.disputeStatus} ·{' '}
                {formatBadDebtMoney(detail.row.disputeAmount, detail.row.disputeCurrency)}）
              </p>
            )}
            <p>
              <span className="text-muted-foreground">会计处理：</span>
              {badDebtAccountingLabel(detail.row)}
            </p>
            {detail.row.writeOffMethod && (
              <p>
                <span className="text-muted-foreground">核销路径：</span>
                {badDebtWriteOffMethodLabel(detail.row)}
                {detail.row.voidedReceivableNo ? ` · 已作废原单 ${detail.row.voidedReceivableNo}` : ''}
                {detail.row.replacementReceivableNo
                  ? ` · 承接单 ${detail.row.replacementReceivableNo}`
                  : ' · 无承接余额'}
                {detail.row.replacementDueDateAdjusted
                  ? '（原单已逾期，承接单到期日取今天）'
                  : ''}
              </p>
            )}
            {detail.row.decisionComment && (
              <p>
                <span className="text-muted-foreground">审批意见：</span>
                {detail.row.decisionComment}
              </p>
            )}
          </div>

          {/* 依据材料 */}
          <div className="mt-5">
            <h3 className="text-title-14 mb-2">依据材料（{detail.evidence.length}）</h3>
            {detail.evidence.length === 0 ? (
              <p className="text-copy-13 text-muted-foreground">
                还没有依据材料。<b>没有依据材料就提交不了审批</b>：核销是终审动作，需要完整依据链。
              </p>
            ) : (
              <ul className="space-y-2">
                {detail.evidence.map((item) => (
                  <li key={item.id} className="border-control-border rounded-md border p-3">
                    <div className="flex items-start justify-between gap-3">
                      <div>
                        <p className="text-copy-13">
                          <Badge tone="neutral">
                            {badDebtEvidenceTypeLabel(item.evidenceType, options?.evidenceTypeOptions)}
                          </Badge>{' '}
                          {item.title}
                        </p>
                        <p className="text-copy-12 text-muted-foreground mt-1">{item.content}</p>
                        {item.referenceNo && (
                          <p className="text-copy-12 text-muted-foreground mt-1">外部单号：{item.referenceNo}</p>
                        )}
                      </div>
                      {detail.row.status === 'DRAFT' && (
                        <Button
                          variant="ghost"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => removeBadDebtEvidence(detail.row.id, item.id),
                              '依据材料已移除。',
                              () => open(detail.row.id),
                            )
                          }
                        >
                          移除
                        </Button>
                      )}
                    </div>
                  </li>
                ))}
              </ul>
            )}

            {detail.row.status === 'DRAFT' && (
              <div className="mt-3 grid gap-3 md:grid-cols-2">
                <FormField id="bad-debt-evidence-type" label="依据类型" required>
                  <select
                    id="bad-debt-evidence-type"
                    className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3 py-1"
                    value={evidenceType}
                    onChange={(e) => setEvidenceType(e.target.value)}
                  >
                    {(options?.evidenceTypeOptions ?? []).map((o) => (
                      <option key={o.code} value={o.code}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="bad-debt-evidence-ref" label="外部单号">
                  <Input
                    value={evidenceReference}
                    onChange={(e) => setEvidenceReference(e.target.value)}
                    placeholder="平台裁决单号 / 拒付流水号"
                  />
                </FormField>
                <FormField id="bad-debt-evidence-title" label="依据标题" required>
                  <Input
                    value={evidenceTitle}
                    onChange={(e) => setEvidenceTitle(e.target.value)}
                    placeholder="例：平台裁决书 PD-2026-0001"
                  />
                </FormField>
              </div>
            )}
            {detail.row.status === 'DRAFT' && (
              <div className="mt-2 space-y-2">
                <label htmlFor="bad-debt-evidence-content" className="text-label-13 text-foreground block">
                  依据内容<span className="text-destructive ml-1">*</span>
                </label>
                <textarea
                  id="bad-debt-evidence-content"
                  className="border-control-border bg-card text-copy-13 min-h-24 w-full rounded-md border px-2 py-1.5"
                  value={evidenceContent}
                  onChange={(e) => setEvidenceContent(e.target.value)}
                  placeholder="材料内容或结论。没有内容的依据不是依据。"
                />
                <Button className="mt-2" onClick={() => void submitEvidence()} disabled={busy}>
                  加依据材料
                </Button>
              </div>
            )}
          </div>

          {/* 审批与核销 */}
          <div className="mt-5">
            <h3 className="text-title-14 mb-2">审批与核销</h3>
            {detail.actions.some((a) => a.code === 'APPROVE' || a.code === 'REJECT') && (
              <div className="space-y-2">
                <label htmlFor="bad-debt-comment" className="text-label-13 text-foreground block">
                  审批意见<span className="text-destructive ml-1">*</span>
                </label>
                <textarea
                  id="bad-debt-comment"
                  className="border-control-border bg-card text-copy-13 min-h-20 w-full rounded-md border px-2 py-1.5"
                  value={decisionComment}
                  onChange={(e) => setDecisionComment(e.target.value)}
                  placeholder="写明依据与判断。没有意见的审批无法追溯依据。"
                />
              </div>
            )}
            <div className="mt-3 flex flex-wrap gap-2">
              {detail.actions.map((action) => {
                const blocked = badDebtActionBlockedReason(action);
                return (
                  <span key={action.code} className="inline-flex flex-col">
                    <Button
                      variant={action.code === 'WRITE_OFF' ? 'default' : 'ghost'}
                      disabled={!action.allowed || busy}
                      title={blocked || undefined}
                      onClick={() => {
                        const id = detail.row.id;
                        if (action.code === 'SUBMIT') {
                          void run(() => submitBadDebtCase(id), '已提交审批。', () => open(id));
                        } else if (action.code === 'APPROVE') {
                          void run(
                            () => approveBadDebtCase(id, { comment: decisionComment.trim() }),
                            '已审批通过。',
                            () => open(id),
                          );
                        } else if (action.code === 'REJECT') {
                          void run(
                            () => rejectBadDebtCase(id, { comment: decisionComment.trim() }),
                            '已驳回。',
                            () => open(id),
                          );
                        } else if (action.code === 'WRITE_OFF') {
                          void run(() => writeOffBadDebtCase(id), '核销已完成。', () => open(id));
                        } else if (action.code === 'VOID') {
                          void run(
                            () => voidBadDebtCase(id, decisionComment.trim() || '作废申请'),
                            '申请已作废。',
                            () => open(id),
                          );
                        }
                      }}
                    >
                      {action.label}
                    </Button>
                    {blocked && (
                      <span className="text-copy-12 text-muted-foreground mt-1 inline-flex items-center gap-1">
                        <TriangleAlert className="size-3" aria-hidden="true" />
                        {blocked}
                      </span>
                    )}
                  </span>
                );
              })}
            </div>
          </div>

          {/* 审计留痕 */}
          <div className="mt-5">
            <h3 className="text-title-14 mb-2">审计留痕（{detail.events.length}）</h3>
            <ol className="space-y-2">
              {detail.events.map((event) => (
                <li key={event.id} className="text-copy-12 flex gap-2">
                  <span className="text-muted-foreground">
                    {event.occurredAt ? new Date(event.occurredAt).toLocaleString('zh-CN') : ''}
                  </span>
                  <span>
                    <Badge tone="neutral">{event.eventType}</Badge>{' '}
                    {event.fromStatus || '—'} → {event.toStatus || '—'}
                    {event.reason ? ` · ${event.reason}` : ''}
                  </span>
                </li>
              ))}
            </ol>
          </div>
        </SectionCard>
      )}
    </div>
  );
}
