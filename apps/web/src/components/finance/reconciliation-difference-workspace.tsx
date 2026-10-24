'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
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
  ReconciliationDiffCapabilities,
  ReconciliationDiffDetail,
  ReconciliationDiffOptions,
  ReconciliationDiffQueuePage,
  ReconciliationDiffRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  adjustReconciliationDiff,
  assignReconciliationDiff,
  claimReconciliationDiff,
  classifyReconciliationDiff,
  fetchReconciliationDiff,
  fetchReconciliationDiffCapabilities,
  fetchReconciliationDiffOptions,
  fetchReconciliationDiffs,
  formatReconciliationDiffDifference,
  formatReconciliationDiffMoney,
  reconciliationDiffActionHints,
  reconciliationDiffAdjustmentBasisHint,
  reconciliationDiffAssigneeLabel,
  reconciliationDiffCategoryLabel,
  reconciliationDiffClassifyHint,
  reconciliationDiffCurrencySummaryLabel,
  reconciliationDiffEventLabel,
  reconciliationDiffHint,
  reconciliationDiffStatusLabel,
  reconciliationDiffStatusTone,
  reconciliationDiffTypeLabel,
  recordReconciliationAdjustment,
  writeOffReconciliationDiff,
} from './reconciliation-difference-model';

/**
 * 12.3.2.2 差异处理。
 *
 * <p>蓝图 12-finance.md：「处理对账差异：分类（我方多记/对方多记/时间差/汇率差/手续费遗漏）、
 * 指定责任人、人工判定与调整、闭环跟踪。」输入：「差异明细；处理结论；调整凭证或说明。」
 * 输出：「差异处理记录 + 调整分录 + 闭环状态。」加粗句：「<b>差异分类是改进闭环的输入</b>：
 * 某类差异反复出现说明上游流程有系统性问题。」
 *
 * <p><b>页面围绕三块内容组织</b>：
 *
 * <ol>
 *   <li><b>差异处理队列</b>：上游 12.3.2.1 产出的差异清单（真分页 + 真筛选 + 真搜索），
 *       每一行都写着「差在哪儿」（差异类型）、「差多少」（同币种差额）、
 *       「归到哪一类」（归因分类）、「谁在管」（责任人）；</li>
 *   <li><b>按币种分组 + 分类分布</b>：🔴 汇总**绝不跨币种合计**；「分类分布」是
 *       **改进闭环的输入** —— 某一类反复出现，就是上游流程有系统性问题；</li>
 *   <li><b>处理动作</b>：认领（并指定责任人）→ 分类归因 → 登记调整分录（凭证或说明）
 *       → 判定「已调整」/「已核销」→ 看闭环时间线。每一道闸的**阻断理由**都写在
 *       按钮旁边（理由来自服务端，页面不自己推断规则）。</li>
 * </ol>
 *
 * <p><b>页面不做业务算术</b>：上游差额、调整金额、分类分布全由服务端算好，
 * 页面只原样展示；「算不出」（服务端给 null）显示「—」而**不是 0**。
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「还没有差异」与「筛选无结果」。
 */

/** 归因分类（受控词表，与服务端一致）。 */
const CATEGORIES = [
  'BOOK_OVER_RECORDED',
  'COUNTERPARTY_OVER_RECORDED',
  'TIMING_DIFFERENCE',
  'FX_DIFFERENCE',
  'FEE_OMISSION',
] as const;

const PAGE_SIZE = 20;

export function ReconciliationDifferenceWorkspace() {
  const [capabilities, setCapabilities] = useState<ReconciliationDiffCapabilities | null>(null);
  const [options, setOptions] = useState<ReconciliationDiffOptions | null>(null);
  const [queue, setQueue] = useState<ReconciliationDiffQueuePage | null>(null);
  const [selected, setSelected] = useState<ReconciliationDiffRow | null>(null);
  const [detail, setDetail] = useState<ReconciliationDiffDetail | null>(null);

  const [loading, setLoading] = useState(true);
  const [detailLoading, setDetailLoading] = useState(false);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 队列筛选
  const [statusFilter, setStatusFilter] = useState('');
  const [categoryFilter, setCategoryFilter] = useState('');
  const [currencyFilter, setCurrencyFilter] = useState('');
  const [differenceTypeFilter, setDifferenceTypeFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageNo, setPageNo] = useState(0);

  // 处理草稿
  const [assigneeDraft, setAssigneeDraft] = useState('');
  const [classifyCategory, setClassifyCategory] = useState<string>('FEE_OMISSION');
  const [classifyReason, setClassifyReason] = useState('');
  const [voucherNo, setVoucherNo] = useState('');
  const [adjustmentAmount, setAdjustmentAmount] = useState('');
  const [adjustmentCurrency, setAdjustmentCurrency] = useState('');
  const [adjustmentDirection, setAdjustmentDirection] = useState('OUT');
  const [adjustmentExplanation, setAdjustmentExplanation] = useState('');
  const [conclusion, setConclusion] = useState('');

  const params = useMemo(
    () => ({
      ...(statusFilter ? { status: statusFilter } : {}),
      ...(categoryFilter ? { category: categoryFilter } : {}),
      ...(currencyFilter ? { currency: currencyFilter } : {}),
      ...(differenceTypeFilter ? { differenceType: differenceTypeFilter } : {}),
      ...(keyword ? { keyword } : {}),
    }),
    [statusFilter, categoryFilter, currencyFilter, differenceTypeFilter, keyword],
  );

  const loadQueue = useCallback(async () => {
    const page = await fetchReconciliationDiffs({ ...params, page: pageNo, size: PAGE_SIZE });
    setQueue(page);
  }, [params, pageNo]);

  const loadDetail = useCallback(async (differenceId: string) => {
    setDetailLoading(true);
    try {
      setDetail(await fetchReconciliationDiff(differenceId));
    } finally {
      setDetailLoading(false);
    }
  }, []);

  useEffect(() => {
    let alive = true;
    (async () => {
      setLoading(true);
      setError('');
      try {
        const [caps, opts] = await Promise.all([
          fetchReconciliationDiffCapabilities(),
          fetchReconciliationDiffOptions(),
        ]);
        if (!alive) return;
        setCapabilities(caps);
        setOptions(opts);
        await loadQueue();
      } catch (e) {
        if (!alive) return;
        setError(e instanceof Error ? e.message : '加载失败');
      } finally {
        if (alive) setLoading(false);
      }
    })();
    return () => {
      alive = false;
    };
  }, [loadQueue]);

  useEffect(() => {
    if (!selected) return;
    void loadDetail(selected.differenceId);
  }, [selected, loadDetail]);

  // ---------------------------------------------------------------- 动作

  async function guard(action: () => Promise<void>) {
    setActionError('');
    setNotice('');
    try {
      await action();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '操作失败');
    }
  }

  function selectDiff(row: ReconciliationDiffRow) {
    setSelected(row);
    setDetail(null);
    setNotice('');
    setActionError('');
    setConclusion('');
    setAssigneeDraft('');
    setClassifyReason('');
    setVoucherNo('');
    setAdjustmentAmount('');
    setAdjustmentCurrency('');
    setAdjustmentExplanation('');
  }

  async function refresh(differenceId: string) {
    await loadQueue();
    await loadDetail(differenceId);
  }

  async function doClaim() {
    if (!selected) return;
    const id = selected.differenceId;
    return guard(async () => {
      await claimReconciliationDiff(id, {
        assigneeId: assigneeDraft.trim() === '' ? null : assigneeDraft.trim(),
        note: null,
      });
      setNotice('已认领这条差异，并指定了责任人。');
      setAssigneeDraft('');
      await refresh(id);
    });
  }

  async function doAssign() {
    if (!selected) return;
    const id = selected.differenceId;
    return guard(async () => {
      await assignReconciliationDiff(id, { assigneeId: assigneeDraft.trim(), reason: null });
      setNotice('已改派责任人。');
      setAssigneeDraft('');
      await refresh(id);
    });
  }

  async function doClassify() {
    if (!selected) return;
    const id = selected.differenceId;
    return guard(async () => {
      await classifyReconciliationDiff(id, {
        category: classifyCategory,
        reason: classifyReason.trim(),
      });
      setNotice(`已分类归因：${reconciliationDiffCategoryLabel(classifyCategory)}。`);
      setClassifyReason('');
      await refresh(id);
    });
  }

  async function doRecordAdjustment() {
    if (!selected) return;
    const id = selected.differenceId;
    return guard(async () => {
      await recordReconciliationAdjustment(id, {
        voucherNo: voucherNo.trim() === '' ? null : voucherNo.trim(),
        adjustmentAmount: Number(adjustmentAmount),
        currency: adjustmentCurrency.trim() === '' ? null : adjustmentCurrency.trim(),
        direction: adjustmentDirection,
        explanation: adjustmentExplanation.trim() === '' ? null : adjustmentExplanation.trim(),
        occurredOn: null,
      });
      setNotice('已登记调整分录。');
      setVoucherNo('');
      setAdjustmentAmount('');
      setAdjustmentExplanation('');
      await refresh(id);
    });
  }

  async function doAdjust() {
    if (!selected) return;
    const id = selected.differenceId;
    return guard(async () => {
      await adjustReconciliationDiff(id, { conclusion: conclusion.trim() });
      setNotice('已判定「已调整」，这条差异闭环。');
      setConclusion('');
      await refresh(id);
    });
  }

  async function doWriteOff() {
    if (!selected) return;
    const id = selected.differenceId;
    return guard(async () => {
      await writeOffReconciliationDiff(id, { conclusion: conclusion.trim() });
      setNotice('已判定「已核销」，这条差异闭环。');
      setConclusion('');
      await refresh(id);
    });
  }

  // ---------------------------------------------------------------- 渲染

  const actions = detail?.actions;
  const hints = actions ? reconciliationDiffActionHints(actions) : [];
  const basisHint = reconciliationDiffAdjustmentBasisHint({
    voucherNo,
    explanation: adjustmentExplanation,
  });
  const classifyHint = reconciliationDiffClassifyHint({ reason: classifyReason });
  const currencies = options?.currencies ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="差异处理"
        description="分类归因、指定责任人、人工判定与调整、闭环跟踪。上游是 12.3.2.1 对账任务产出的差异清单。"
        actions={
          <Button
            variant="ghost"
            onClick={() => {
              void loadQueue();
            }}
            aria-label="刷新差异处理队列"
          >
            <RefreshCw className="size-4" />
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="加载失败">
          {error}
        </Notice>
      ) : null}
      {actionError ? (
        <Notice tone="danger" title="操作失败">
          {actionError}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力声明" description="哪些真接通、哪些没交付、归谁">
          <ul className="space-y-2">
            {capabilities.capabilities.map((c) => (
              <li key={`${c.moduleId}-${c.name}`} className="text-copy-13">
                <Badge tone={c.delivered ? 'success' : 'warning'}>
                  {c.moduleId} {c.name}
                </Badge>
                <span className="text-muted-foreground ml-2">{c.source}</span>
                <p className="text-muted-foreground mt-1">{c.note}</p>
              </li>
            ))}
          </ul>
          {capabilities.gaps.length > 0 ? (
            <div className="mt-4 space-y-2">
              <p className="text-label-13">缺口</p>
              <ul className="space-y-2">
                {capabilities.gaps.map((g) => (
                  <li key={`${g.moduleId}-${g.name}`} className="text-copy-13">
                    <Badge tone={g.delivered ? 'success' : 'warning'}>
                      {g.moduleId} {g.name}
                    </Badge>
                    <span className="text-muted-foreground ml-2">{g.owner}</span>
                    <p className="text-muted-foreground mt-1">{g.note}</p>
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </SectionCard>
      ) : null}

      <SectionCard
        title="差异处理队列"
        description="上游 12.3.2.1 产出的差异清单 · 真分页 + 真筛选 + 真搜索"
      >
        {loading ? (
          <p className="text-muted-foreground text-copy-13">加载中…</p>
        ) : queue && queue.items.length === 0 ? (
          <EmptyState
            title={Object.keys(params).length > 0 ? '没有符合条件的差异' : '还没有待处理的差异'}
            description={
              Object.keys(params).length > 0
                ? '换一个筛选条件试试。'
                : '上游 12.3.2.1 的对账任务跑完匹配后，没配平的条目会出现在这里。'
            }
          />
        ) : (
          <div className="space-y-3">
            <div className="flex flex-wrap items-end gap-2">
              <FormField id="rd-status" label="状态">
                <Input
                  value={statusFilter}
                  onChange={(e) => {
                    setStatusFilter(e.target.value);
                    setPageNo(0);
                  }}
                  placeholder="OPEN / CLAIMED / ADJUSTED / WRITTEN_OFF"
                />
              </FormField>
              <FormField id="rd-category" label="归因分类">
                <Input
                  value={categoryFilter}
                  onChange={(e) => {
                    setCategoryFilter(e.target.value);
                    setPageNo(0);
                  }}
                  placeholder="FEE_OMISSION …"
                />
              </FormField>
              <FormField id="rd-currency" label="币种">
                <Input
                  value={currencyFilter}
                  onChange={(e) => {
                    setCurrencyFilter(e.target.value);
                    setPageNo(0);
                  }}
                  placeholder="USD / EUR …"
                />
              </FormField>
              <FormField id="rd-type" label="差异类型">
                <Input
                  value={differenceTypeFilter}
                  onChange={(e) => {
                    setDifferenceTypeFilter(e.target.value);
                    setPageNo(0);
                  }}
                  placeholder="BOOK_ONLY …"
                />
              </FormField>
              <FormField id="rd-keyword" label="关键字">
                <Input
                  value={keyword}
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setPageNo(0);
                  }}
                  placeholder="单号 / 归因 / 结论"
                />
              </FormField>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-copy-13">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2 pr-3">单号</th>
                    <th className="py-2 pr-3">差异类型</th>
                    <th className="py-2 pr-3">差额</th>
                    <th className="py-2 pr-3">归因分类</th>
                    <th className="py-2 pr-3">责任人</th>
                    <th className="py-2 pr-3">分录</th>
                    <th className="py-2 pr-3">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {queue?.items.map((row) => (
                    <tr
                      key={row.differenceId}
                      className="cursor-pointer border-b last:border-0"
                      onClick={() => selectDiff(row)}
                    >
                      <td className="py-2 pr-3">{row.reference || '—'}</td>
                      <td className="py-2 pr-3">{reconciliationDiffTypeLabel(row.differenceType)}</td>
                      <td className="py-2 pr-3">
                        {formatReconciliationDiffDifference(
                          row.differenceAmount,
                          row.currency,
                          row.exchangeRateAvailable,
                        )}
                      </td>
                      <td className="py-2 pr-3">
                        {row.category ? reconciliationDiffCategoryLabel(row.category) : '未分类'}
                      </td>
                      <td className="py-2 pr-3">
                        {reconciliationDiffAssigneeLabel(row.assigneeId)}
                      </td>
                      <td className="py-2 pr-3">{row.adjustmentCount}</td>
                      <td className="py-2 pr-3">
                        <Badge tone={reconciliationDiffStatusTone(row.status)}>
                          {reconciliationDiffStatusLabel(row.status)}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            {queue && queue.total > PAGE_SIZE ? (
              <div className="flex items-center gap-2">
                <Button
                  variant="ghost"
                  disabled={pageNo === 0}
                  onClick={() => setPageNo((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  第 {pageNo + 1} 页 · 共 {queue.total} 条
                </span>
                <Button
                  variant="ghost"
                  disabled={(pageNo + 1) * PAGE_SIZE >= queue.total}
                  onClick={() => setPageNo((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </div>
        )}
      </SectionCard>

      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard
          title="按币种汇总"
          description="🔴 跨币种绝不相加：每个币种一行，差额算不出来就写「汇率不可得」"
        >
          {queue && queue.currencies.length === 0 ? (
            <EmptyState title="还没有汇总" description="先跑一次上游对账任务，产生差异后这里才有数。" />
          ) : (
            <div className="space-y-3">
              {queue?.currencies.map((c) => (
                <div key={c.currency} className="border-b pb-3 last:border-0 last:pb-0">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="primary">{c.currency}</Badge>
                    {!c.exchangeRateAvailable ? <Badge tone="warning">汇率不可得</Badge> : null}
                  </div>
                  <p className="text-copy-13 mt-1">
                    {reconciliationDiffCurrencySummaryLabel(c)}
                  </p>
                </div>
              ))}
            </div>
          )}
        </SectionCard>

        <SectionCard
          title="分类分布（改进闭环的输入）"
          description="某一类反复出现 ⇒ 上游流程有系统性问题"
        >
          {queue && queue.categories.length === 0 ? (
            <EmptyState
              title="还没有分类"
              description="处理过的差异会按归因分类统计在这里 —— 那是改进闭环的输入。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-copy-13">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2 pr-3">归因分类</th>
                    <th className="py-2 pr-3">币种</th>
                    <th className="py-2 pr-3">条数</th>
                    <th className="py-2 pr-3">上游差额</th>
                    <th className="py-2 pr-3">调整额</th>
                  </tr>
                </thead>
                <tbody>
                  {queue?.categories.map((c) => (
                    <tr key={`${c.category}-${c.currency}`} className="border-b last:border-0">
                      <td className="py-2 pr-3">{c.categoryLabel}</td>
                      <td className="py-2 pr-3">{c.currency}</td>
                      <td className="py-2 pr-3">{c.count}</td>
                      <td className="py-2 pr-3">
                        {formatReconciliationDiffMoney(c.differenceAmount, c.currency)}
                      </td>
                      <td className="py-2 pr-3">
                        {formatReconciliationDiffMoney(c.adjustedAmount, c.currency)}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </SectionCard>
      </div>

      {selected ? (
        <>
          <SectionCard
            title={`差异 ${selected.reference || selected.differenceId}`}
            description={reconciliationDiffHint(selected)}
            extra={
              <Badge tone={reconciliationDiffStatusTone(selected.status)}>
                {reconciliationDiffStatusLabel(selected.status)}
              </Badge>
            }
          >
            {detailLoading ? (
              <p className="text-muted-foreground text-copy-13">加载中…</p>
            ) : !detail ? (
              <EmptyState title="读不到这条差异的详情" description="刷新一下再试。" />
            ) : (
              <div className="space-y-3">
                <div className="grid gap-3 sm:grid-cols-4">
                  <StatCard label="归因分类" value={selected.category ? (selected.categoryLabel ?? '—') : '未分类'} />
                  <StatCard label="责任人" value={reconciliationDiffAssigneeLabel(selected.assigneeId)} />
                  <StatCard label="调整分录" value={`${selected.adjustmentCount} 条`} />
                  <StatCard label="平台侧" value={formatReconciliationDiffMoney(selected.externalAmount, selected.currency)} />
                </div>
                <p className="text-muted-foreground text-copy-12">
                  归因（上游）：{selected.attribution || '—'}；上游原因：{selected.reason || '—'}
                </p>
                {selected.categoryReason ? (
                  <p className="text-copy-13">分类理由：{selected.categoryReason}</p>
                ) : null}
                {selected.conclusion ? (
                  <p className="text-copy-13">闭环结论：{selected.conclusion}</p>
                ) : null}
                {hints.length > 0 ? (
                  <Notice tone="info" title="当前不能做的事">
                    <ul className="list-disc space-y-1 pl-4">
                      {hints.map((hint) => (
                        <li key={hint}>{hint}</li>
                      ))}
                    </ul>
                  </Notice>
                ) : null}
              </div>
            )}
          </SectionCard>

          <div className="grid gap-4 lg:grid-cols-2">
            <SectionCard title="处理动作" description="认领 → 分类归因 → 登记调整分录 → 判定闭环">
              <div className="space-y-4">
                <div className="space-y-2">
                  <FormField
                    id="rd-assignee"
                    label="责任人 id"
                    hint="留空 = 认领人就是操作者自己（本仓没有用户目录服务，只存 uuid）"
                  >
                    <Input
                      value={assigneeDraft}
                      onChange={(e) => setAssigneeDraft(e.target.value)}
                      placeholder="用户 uuid"
                    />
                  </FormField>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="ghost"
                      disabled={!actions?.canClaim}
                      onClick={() => void doClaim()}
                    >
                      认领
                    </Button>
                    <Button
                      variant="ghost"
                      disabled={!actions?.canAssign || assigneeDraft.trim() === ''}
                      onClick={() => void doAssign()}
                    >
                      改派责任人
                    </Button>
                  </div>
                </div>

                <div className="space-y-2">
                  <FormField id="rd-category-pick" label="归因分类" required>
                    <select
                      id="rd-category-pick"
                      className="border-control-border bg-card text-copy-13 w-full rounded-md border px-2 py-1.5"
                      value={classifyCategory}
                      onChange={(e) => setClassifyCategory(e.target.value)}
                    >
                      {CATEGORIES.map((c) => (
                        <option key={c} value={c}>
                          {reconciliationDiffCategoryLabel(c)}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <div className="space-y-1">
                    <label htmlFor="rd-category-reason" className="text-label-13 text-foreground block">
                      分类理由<span className="text-destructive ml-1">*</span>
                    </label>
                    <textarea
                      id="rd-category-reason"
                      className="border-control-border bg-card text-copy-13 min-h-20 w-full rounded-md border px-2 py-1.5"
                      value={classifyReason}
                      onChange={(e) => setClassifyReason(e.target.value)}
                      placeholder="例：平台结算单里有 3.5 USD 佣金，我方账没有这一笔。"
                    />
                    {classifyHint ? (
                      <p className="text-copy-12 text-destructive">{classifyHint}</p>
                    ) : null}
                  </div>
                  <Button
                    variant="ghost"
                    disabled={!actions?.canClassify || classifyHint !== ''}
                    onClick={() => void doClassify()}
                  >
                    保存分类归因
                  </Button>
                </div>

                <div className="space-y-2">
                  <div className="flex flex-wrap items-end gap-2">
                    <FormField id="rd-voucher" label="调整凭证号">
                      <Input
                        value={voucherNo}
                        onChange={(e) => setVoucherNo(e.target.value)}
                        placeholder="凭证或说明至少一个"
                      />
                    </FormField>
                    <FormField id="rd-amount" label="调整金额" required>
                      <Input
                        type="number"
                        min={0}
                        step="0.0001"
                        value={adjustmentAmount}
                        onChange={(e) => setAdjustmentAmount(e.target.value)}
                      />
                    </FormField>
                    <FormField id="rd-currency-pick" label="币种" hint="留空 = 用差异本身的币种">
                      <Input
                        value={adjustmentCurrency}
                        onChange={(e) => setAdjustmentCurrency(e.target.value)}
                        placeholder={selected.currency}
                        list="rd-currency-options"
                      />
                    </FormField>
                    <FormField id="rd-direction" label="方向" required>
                      <select
                        id="rd-direction"
                        className="border-control-border bg-card text-copy-13 w-full rounded-md border px-2 py-1.5"
                        value={adjustmentDirection}
                        onChange={(e) => setAdjustmentDirection(e.target.value)}
                      >
                        <option value="OUT">支出</option>
                        <option value="IN">收入</option>
                      </select>
                    </FormField>
                  </div>
                  <datalist id="rd-currency-options">
                    {currencies.map((c) => (
                      <option key={c} value={c} />
                    ))}
                  </datalist>
                  <div className="space-y-1">
                    <label htmlFor="rd-explanation" className="text-label-13 text-foreground block">
                      调整说明
                    </label>
                    <textarea
                      id="rd-explanation"
                      className="border-control-border bg-card text-copy-13 min-h-20 w-full rounded-md border px-2 py-1.5"
                      value={adjustmentExplanation}
                      onChange={(e) => setAdjustmentExplanation(e.target.value)}
                      placeholder="例：补记平台扣的 3.5 USD 佣金（凭证 VCH-2026-0001）。"
                    />
                    {basisHint ? (
                      <p className="text-copy-12 text-destructive">{basisHint}</p>
                    ) : null}
                  </div>
                  <Button
                    variant="ghost"
                    disabled={
                      !actions?.canRecordAdjustment ||
                      basisHint !== '' ||
                      adjustmentAmount.trim() === ''
                    }
                    onClick={() => void doRecordAdjustment()}
                  >
                    登记调整分录
                  </Button>
                </div>

                <div className="space-y-2">
                  <div className="space-y-1">
                    <label htmlFor="rd-conclusion" className="text-label-13 text-foreground block">
                      闭环结论<span className="text-destructive ml-1">*</span>
                    </label>
                    <textarea
                      id="rd-conclusion"
                      className="border-control-border bg-card text-copy-13 min-h-16 w-full rounded-md border px-2 py-1.5"
                      value={conclusion}
                      onChange={(e) => setConclusion(e.target.value)}
                      placeholder="例：按平台账单调整我方账（补记手续费 3.5 USD）。"
                    />
                  </div>
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="ghost"
                      disabled={!actions?.canAdjust || conclusion.trim() === ''}
                      onClick={() => void doAdjust()}
                    >
                      判定「已调整」
                    </Button>
                    <Button
                      variant="ghost"
                      disabled={!actions?.canWriteOff || conclusion.trim() === ''}
                      onClick={() => void doWriteOff()}
                    >
                      判定「已核销」
                    </Button>
                  </div>
                </div>
              </div>
            </SectionCard>

            <div className="space-y-4">
              <SectionCard title="调整分录" description="凭证或说明，至少有一个 · 一次性事实，不可改写">
                {!detail || detail.adjustments.length === 0 ? (
                  <EmptyState
                    title="还没有调整分录"
                    description="判定「已调整」必须至少有一条调整分录；「已核销」= 判定不需要调整。"
                  />
                ) : (
                  <div className="overflow-x-auto">
                    <table className="w-full text-copy-13">
                      <thead>
                        <tr className="text-muted-foreground border-b text-left">
                          <th className="py-2 pr-3">#</th>
                          <th className="py-2 pr-3">金额</th>
                          <th className="py-2 pr-3">方向</th>
                          <th className="py-2 pr-3">凭证</th>
                          <th className="py-2 pr-3">说明</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.adjustments.map((a) => (
                          <tr key={a.id} className="border-b last:border-0">
                            <td className="py-2 pr-3">{a.entryNo}</td>
                            <td className="py-2 pr-3">
                              {formatReconciliationDiffMoney(a.adjustmentAmount, a.currency)}
                            </td>
                            <td className="py-2 pr-3">{a.directionLabel}</td>
                            <td className="py-2 pr-3">{a.voucherNo ?? '—'}</td>
                            <td className="py-2 pr-3">{a.explanation || '—'}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </SectionCard>

              <SectionCard title="闭环时间线" description="append-only 举证 · 每一步都留痕">
                {!detail || detail.events.length === 0 ? (
                  <EmptyState title="还没有处理记录" description="认领或分类之后，这里会记下每一步。" />
                ) : (
                  <ul className="space-y-2">
                    {detail.events.map((e) => (
                      <li key={e.id} className="text-copy-13 border-b pb-2 last:border-0 last:pb-0">
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone="primary">{reconciliationDiffEventLabel(e.eventType)}</Badge>
                          <span className="text-muted-foreground text-copy-12">{e.occurredAt}</span>
                        </div>
                        <p className="text-muted-foreground mt-1">{e.summary}</p>
                      </li>
                    ))}
                  </ul>
                )}
              </SectionCard>
            </div>
          </div>
        </>
      ) : null}

      <p className="text-muted-foreground text-copy-12">{options?.note ?? ''}</p>
    </div>
  );
}
