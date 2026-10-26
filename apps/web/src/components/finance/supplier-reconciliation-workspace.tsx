'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  FinanceSupplierReconciliationCapabilities,
  FinanceSupplierReconciliationDetail,
  FinanceSupplierReconciliationDifferencePage,
  FinanceSupplierReconciliationLinePage,
  FinanceSupplierReconciliationOptions,
  FinanceSupplierReconciliationPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  actorRoleLabel,
  addSupplierReconciliationExplanation,
  confirmBlockReason,
  confirmSupplierReconciliation,
  differenceHint,
  differenceStatusLabel,
  differenceStatusTone,
  differenceTypeLabel,
  fetchSupplierReconciliationCapabilities,
  fetchSupplierReconciliationDifferences,
  fetchSupplierReconciliationLines,
  fetchSupplierReconciliationOptions,
  fetchSupplierReconciliation,
  fetchSupplierReconciliations,
  formatSupplierMoney,
  formatSupplierQuantity,
  importSupplierReconciliations,
  lineVarianceHint,
  matchSupplierReconciliation,
  reconciliationEventLabel,
  reconciliationStatusLabel,
  reconciliationStatusTone,
  reconciliationSummaryLabel,
  rejectSupplierReconciliation,
  resolutionLabel,
  resolveSupplierReconciliationDifference,
  upstreamStatusLabel,
} from './supplier-reconciliation-model';

/**
 * 12.1.2.3 供应商对账（财务侧）。
 *
 * <p>蓝图 12-finance.md：「财务侧供应商对账：核对采购侧对账单（6.5.1）与收货/质检数据，
 * 差异处理与确认，最终生成应付依据。」输出：「对账确认结果 + 差异清单 + 应付生成依据。」
 * 加粗句：「<b>财务是最终确认方</b>，采购不能自行确认应付——这是职责分离。」
 *
 * <p><b>页面围绕那两句加粗句组织</b>：
 *
 * <ol>
 *   <li>「核对采购侧对账单（6.5.1）与收货/质检数据」⇒ 页面第一块是**导入 + 核对**：
 *       采购侧对账单（上游 6.5.1.1 已由供应商确认的那些）导入后，逐行核「实到数量」与
 *       「质检扣量」，两侧口径（采购侧 / 财务侧）**并排显示**，差额由差异清单解释。</li>
 *   <li>「差异处理与确认」⇒ 页面有**差异清单**这一段：每条差异能登记说明（采购侧 / 供应商给出），
 *       也能被裁决（财务侧动作）。<b>未裁决的差异存在时，确认按钮是禁用的并写明原因</b>。</li>
 *   <li>「最终生成应付依据」⇒ 确认后页面显示**应付依据**（采购侧口径 + 调整额 = 应付净额）
 *       与应付单号。<b>页面不提供任何「标记已付」入口</b> —— 付款属 12.2.2.1（未交付）。</li>
 * </ol>
 *
 * <p><b>页面刻意没有的东西</b>：
 *
 * <ul>
 *   <li><b>「采购侧自己确认应付」的入口</b>：页面上的「财务确认」按钮对应后端的
 *       {@code finance.suppliereconciliation.confirm} 权限，采购侧角色拿不到。</li>
 *   <li><b>任何「标记已付」「执行付款」按钮</b>：付款属 12.2.2.1（未交付）。</li>
 * </ul>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有对账单」。
 */
export function SupplierReconciliationWorkspace() {
  const [caps, setCaps] = useState<FinanceSupplierReconciliationCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceSupplierReconciliationOptions | null>(null);
  const [page, setPage] = useState<FinanceSupplierReconciliationPage | null>(null);
  const [detail, setDetail] = useState<FinanceSupplierReconciliationDetail | null>(null);
  const [lines, setLines] = useState<FinanceSupplierReconciliationLinePage | null>(null);
  const [differences, setDifferences] =
    useState<FinanceSupplierReconciliationDifferencePage | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [selectedId, setSelectedId] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [upstreamStatus, setUpstreamStatus] = useState('');
  const [supplierRefId, setSupplierRefId] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);
  const [differenceStatus, setDifferenceStatus] = useState('');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = useMemo(
    () => ({
      ...(status ? { status } : {}),
      ...(upstreamStatus ? { upstreamStatus } : {}),
      ...(supplierRefId ? { supplierRefId } : {}),
      ...(currency ? { currency } : {}),
      ...(keyword ? { keyword } : {}),
      page: currentPage,
      size: 50,
    }),
    [status, upstreamStatus, supplierRefId, currency, keyword, currentPage],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p] = await Promise.all([
        fetchSupplierReconciliationCapabilities(),
        fetchSupplierReconciliationOptions(),
        fetchSupplierReconciliations(params),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载供应商对账失败');
    } finally {
      setLoading(false);
    }
  }, [params]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadDetail = useCallback(
    async (id: string) => {
      setActionError('');
      try {
        const [d, l, diff] = await Promise.all([
          fetchSupplierReconciliation(id),
          fetchSupplierReconciliationLines(id, { page: 0, size: 200 }),
          fetchSupplierReconciliationDifferences(id, {
            ...(differenceStatus ? { status: differenceStatus } : {}),
            page: 0,
            size: 200,
          }),
        ]);
        setDetail(d);
        setLines(l);
        setDifferences(diff);
      } catch (e) {
        setActionError(e instanceof Error ? e.message : '加载对账单详情失败');
      }
    },
    [differenceStatus],
  );

  useEffect(() => {
    if (selectedId) void loadDetail(selectedId);
  }, [selectedId, loadDetail]);

  async function runImport() {
    setActionError('');
    try {
      const result = await importSupplierReconciliations();
      setNotice(
        `导入完成：扫描 ${result.scanned} 张，新增 ${result.imported} 张，刷新 ${result.refreshed} 张，跳过 ${result.skipped} 张` +
          (result.warnings.length > 0 ? `；提示：${result.warnings.join('；')}` : ''),
      );
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '导入采购侧对账单失败');
    }
  }

  async function runMatch(id: string) {
    setActionError('');
    try {
      const result = await matchSupplierReconciliation(id);
      setNotice(
        `核对完成：匹配 ${result.matchedLines} 行 / 未匹配 ${result.unmatchedLines} 行，产生差异 ${result.raisedDifferences} 条`,
      );
      await load();
      await loadDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '核对失败');
    }
  }

  async function runAddExplanation(id: string, differenceId: string) {
    const text = window.prompt('差异说明（必填）：这批为什么这么处理？');
    if (text === null || text.trim() === '') return;
    const role = window.prompt('说明方（PURCHASE / FINANCE / SUPPLIER）', 'SUPPLIER');
    if (role === null || role.trim() === '') return;
    setActionError('');
    try {
      await addSupplierReconciliationExplanation(id, differenceId, {
        explanation: text.trim(),
        providedRole: role.trim().toUpperCase(),
        evidenceRef: '',
      });
      setNotice('差异说明已登记（说明是举证，只能追加）');
      await loadDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记差异说明失败');
    }
  }

  async function runResolve(id: string, differenceId: string) {
    const note = window.prompt('裁决说明（必填）：凭什么这么判？');
    if (note === null || note.trim() === '') return;
    const resolution = window.prompt(
      '裁决方式：APPLIED = 按财务口径调整应付；KEPT = 沿用采购侧口径',
      'APPLIED',
    );
    if (resolution === null || resolution.trim() === '') return;
    setActionError('');
    try {
      await resolveSupplierReconciliationDifference(id, differenceId, {
        resolution: resolution.trim().toUpperCase(),
        note: note.trim(),
      });
      setNotice('差异已裁决');
      await load();
      await loadDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '裁决差异失败');
    }
  }

  async function runConfirm(id: string) {
    setActionError('');
    try {
      const result = await confirmSupplierReconciliation(id);
      setNotice(
        result.payableBasis
          ? `财务已确认：应付依据 ${result.payableBasis.payableNo}（${formatSupplierMoney(result.payableBasis.amount, result.payableBasis.currency)}）`
          : '财务已确认',
      );
      await load();
      await loadDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '财务确认失败');
    }
  }

  async function runReject(id: string) {
    const reason = window.prompt('驳回理由（必填）：驳回掉的是一份对账单，痕迹要留着');
    if (reason === null || reason.trim() === '') return;
    setActionError('');
    try {
      await rejectSupplierReconciliation(id, reason.trim());
      setNotice('已驳回（终态，整单冻结）');
      await load();
      await loadDetail(id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '驳回失败');
    }
  }

  const unavailable = (caps?.capabilities ?? []).filter((c) => !c.available);
  const gaps = caps?.gaps ?? [];
  const hasFilter = Boolean(status || upstreamStatus || supplierRefId || currency || keyword);
  const head = detail?.head;
  const blockReason = head ? confirmBlockReason(head) : '';

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="供应商对账"
        description="财务侧核对采购侧对账单与收货 / 质检数据，差异处理与确认后生成应付依据 —— 财务是最终确认方，采购不能自行确认应付"
        actions={
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            <Button onClick={() => void runImport()} disabled={loading}>
              导入采购侧对账单
            </Button>
          </div>
        }
      />

      {error && (
        <div className="flex items-center gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm">
          <TriangleAlert className="h-4 w-4" />
          {error}
        </div>
      )}
      {actionError && (
        <Notice tone="danger" title="操作未完成">
          {actionError}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {loading && <p className="text-muted-foreground text-sm">加载中…</p>}

      {!loading && !error && (
        <>
          {/* ---------- 职责分离与未交付的模块 ---------- */}
          <Notice tone="info" title="职责分离：财务是最终确认方">
            采购侧能导入对账单、能跑核对、能登记差异说明；
            <b> 裁决差异与最终确认走独立权限（finance.suppliereconciliation.confirm）</b>
            ，只有财务侧能走 —— 采购侧的数据在财务确认前不会变成已确认应付。
          </Notice>
          {unavailable.length > 0 && (
            <Notice tone="warning" title="当前不可用的能力">
              {unavailable.map((c) => `${c.moduleId} ${c.title}（${c.detail}）`).join('；')}
            </Notice>
          )}
          {gaps.length > 0 && (
            <Notice tone="warning" title="未交付的下游">
              {gaps.map((gap) => `${gap.moduleId} ${gap.title}（${gap.detail}）`).join('；')}
            </Notice>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="在册对账单" value={page?.total ?? 0} unit="张" />
            <StatCard
              label="待核对"
              value={(page?.rows ?? []).filter((r) => r.status === 'IMPORTED').length}
              unit="张"
              comparisonLabel="当前页内计数"
            />
            <StatCard
              label="未裁决差异"
              value={page?.openDifferenceCount ?? 0}
              unit="条"
              comparisonLabel="覆盖整个筛选结果集"
            />
            <StatCard
              label="已确认应付"
              value={(page?.rows ?? []).filter((r) => r.status === 'CONFIRMED').length}
              unit="张"
              comparisonLabel="当前页内计数"
            />
          </div>

          {/* ---------- 按币种分开的汇总 ---------- */}
          <SectionCard
            title="按币种分开的汇总"
            description="采购侧口径 → 财务侧口径（采购侧口径 + 已裁决的调整额）。不同币种绝不相加 —— USD 与 EUR 加起来不是任何一个金额。"
          >
            {(page?.summaries ?? []).length === 0 ? (
              <p className="text-muted-foreground text-copy-13">还没有对账单。</p>
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">币种</th>
                    <th className="py-2">采购侧口径 → 财务侧口径</th>
                  </tr>
                </thead>
                <tbody>
                  {(page?.summaries ?? []).map((summary) => (
                    <tr key={summary.currency} className="border-border border-t">
                      <td className="py-2 font-medium">{summary.currency}</td>
                      <td className="py-2">{reconciliationSummaryLabel(summary)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 对账单列表 ---------- */}
          <SectionCard
            title="对账单"
            description="真分页 + 真筛选；汇总覆盖整个筛选结果集，不是当前页。"
          >
            <div className="mb-3 grid gap-2 md:grid-cols-5">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                aria-label="对账状态"
                onChange={(e) => {
                  setStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部状态</option>
                {(options?.statuses ?? []).map((value) => (
                  <option key={value} value={value}>
                    {reconciliationStatusLabel(value)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={upstreamStatus}
                aria-label="上游状态"
                onChange={(e) => {
                  setUpstreamStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部上游状态</option>
                {(options?.upstreamStatuses ?? []).map((value) => (
                  <option key={value} value={value}>
                    {upstreamStatusLabel(value)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={supplierRefId}
                aria-label="供应商"
                onChange={(e) => {
                  setSupplierRefId(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部供应商</option>
                {(options?.suppliers ?? []).map((supplier) => (
                  <option key={supplier.refId} value={supplier.refId}>
                    {supplier.name}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                aria-label="币种"
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((value) => (
                  <option key={value} value={value}>
                    {value}
                  </option>
                ))}
              </select>
              <Input
                value={keyword}
                aria-label="关键字"
                placeholder="对账单号 / 供应商 / 应付单号"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
              />
            </div>

            {(page?.rows ?? []).length === 0 ? (
              <EmptyState
                title={hasFilter ? '没有匹配的对账单' : '还没有导入对账单'}
                description={
                  hasFilter
                    ? '换个筛选条件再试 —— 汇总覆盖的是整个筛选结果集。'
                    : '先点右上角「导入采购侧对账单」：上游 6.5.1.1 里供应商已确认的对账单会落到这里。'
                }
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">对账单号</th>
                    <th className="py-2">供应商</th>
                    <th className="py-2">期间</th>
                    <th className="py-2">币种</th>
                    <th className="py-2">采购侧口径</th>
                    <th className="py-2">财务侧口径</th>
                    <th className="py-2">差异</th>
                    <th className="py-2">状态</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {(page?.rows ?? []).map((row) => (
                    <tr key={row.id} className="border-border border-t">
                      <td className="py-2 font-medium">
                        {row.reconNo}
                        <span className="text-muted-foreground text-copy-12 block">
                          上游 {row.upstreamReconNo || '—'}
                        </span>
                      </td>
                      <td className="py-2">{row.supplierName}</td>
                      <td className="py-2">
                        {row.periodStart} ~ {row.periodEnd}
                      </td>
                      <td className="py-2">{row.statementCurrency}</td>
                      <td className="py-2">
                        {formatSupplierMoney(row.statementSuggestedAmount, row.statementCurrency)}
                      </td>
                      <td className="py-2">
                        {row.financeConfirmedAmount === null
                          ? '尚未确认'
                          : formatSupplierMoney(row.financeConfirmedAmount, row.statementCurrency)}
                      </td>
                      <td className="py-2">
                        {row.openDifferenceCount > 0 ? (
                          <Badge tone="warning">{row.openDifferenceCount} 条待裁决</Badge>
                        ) : (
                          <span className="text-muted-foreground">无</span>
                        )}
                      </td>
                      <td className="py-2">
                        <Badge tone={reconciliationStatusTone(row.status)}>
                          {reconciliationStatusLabel(row.status)}
                        </Badge>
                      </td>
                      <td className="py-2 text-right">
                        <Button
                          variant="secondary"
                          onClick={() => setSelectedId(row.id === selectedId ? '' : row.id)}
                        >
                          {row.id === selectedId ? '收起' : '查看'}
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

            <div className="mt-3 flex items-center gap-2">
              <Button
                variant="secondary"
                onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                disabled={currentPage === 0}
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-copy-13">
                第 {currentPage + 1} 页 · 共 {page?.total ?? 0} 张
              </span>
              <Button
                variant="secondary"
                onClick={() => setCurrentPage((p) => p + 1)}
                disabled={(page?.rows ?? []).length === 0}
              >
                下一页
              </Button>
            </div>
          </SectionCard>

          {/* ---------- 选中一张对账单的详情 ---------- */}
          {detail && head && (
            <>
              <SectionCard
                title={`${head.reconNo} · ${head.supplierName}`}
                description={`上游对账单 ${head.upstreamReconNo || '—'}（${upstreamStatusLabel(head.upstreamStatus)}）· 期间 ${head.periodStart} ~ ${head.periodEnd} · 对账单币种 ${head.statementCurrency}`}
                extra={
                  <div className="flex flex-wrap gap-2">
                    <Button
                      variant="secondary"
                      onClick={() => void runMatch(head.id)}
                      disabled={head.status === 'CONFIRMED' || head.status === 'REJECTED'}
                    >
                      核对收货与质检
                    </Button>
                    <Button
                      onClick={() => void runConfirm(head.id)}
                      disabled={Boolean(blockReason)}
                    >
                      财务确认并生成应付
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => void runReject(head.id)}
                      disabled={head.status === 'CONFIRMED' || head.status === 'REJECTED'}
                    >
                      财务驳回
                    </Button>
                  </div>
                }
              >
                <div className="space-y-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={reconciliationStatusTone(head.status)}>
                      {reconciliationStatusLabel(head.status)}
                    </Badge>
                    {blockReason && (
                      <span className="text-muted-foreground text-copy-13">{blockReason}</span>
                    )}
                  </div>
                  <div className="grid gap-3 md:grid-cols-3">
                    <StatCard
                      label="采购侧口径"
                      value={formatSupplierMoney(
                        head.statementSuggestedAmount,
                        head.statementCurrency,
                      )}
                      comparisonLabel="上游建议数（不是应付事实）"
                    />
                    <StatCard
                      label="裁决调整"
                      value={formatSupplierMoney(
                        head.financeAdjustedAmount,
                        head.statementCurrency,
                      )}
                      comparisonLabel="按财务口径调整的差额"
                    />
                    <StatCard
                      label="财务确认金额"
                      value={
                        head.financeConfirmedAmount === null
                          ? '尚未确认'
                          : formatSupplierMoney(head.financeConfirmedAmount, head.statementCurrency)
                      }
                      comparisonLabel="确认后才有值"
                    />
                  </div>
                  {detail.payableBasis && (
                    <Notice tone="success" title="应付依据">
                      应付单 {detail.payableBasis.payableNo} ·{' '}
                      {formatSupplierMoney(
                        detail.payableBasis.amount,
                        detail.payableBasis.currency,
                      )}{' '}
                      = 采购侧{' '}
                      {formatSupplierMoney(
                        detail.payableBasis.statementAmount,
                        detail.payableBasis.currency,
                      )}{' '}
                      + 调整{' '}
                      {formatSupplierMoney(
                        detail.payableBasis.adjustmentAmount,
                        detail.payableBasis.currency,
                      )}
                      。<b>付款属 12.2.2.1（未交付）</b>，本页不提供任何「标记已付」入口。
                    </Notice>
                  )}
                  {head.status === 'REJECTED' && head.rejectReason && (
                    <Notice tone="danger" title="已驳回">
                      {head.rejectReason}
                    </Notice>
                  )}
                </div>
              </SectionCard>

              {/* ---------- 明细行：采购侧口径 vs 财务侧口径 ---------- */}
              <SectionCard
                title="对账明细行"
                description="两侧口径并排显示：采购侧是多少、财务侧认定是多少、差在哪。"
              >
                {(lines?.rows ?? []).length === 0 ? (
                  <p className="text-muted-foreground text-copy-13">这张对账单还没有明细行。</p>
                ) : (
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">行</th>
                        <th className="py-2">类型</th>
                        <th className="py-2">来源</th>
                        <th className="py-2">采购侧金额</th>
                        <th className="py-2">财务侧金额</th>
                        <th className="py-2">核对结论</th>
                      </tr>
                    </thead>
                    <tbody>
                      {(lines?.rows ?? []).map((line) => (
                        <tr key={line.id} className="border-border border-t">
                          <td className="py-2">{line.lineNo}</td>
                          <td className="py-2">{line.lineTypeLabel}</td>
                          <td className="py-2">
                            {line.sourceRefCode || '—'}
                            <span className="text-muted-foreground text-copy-12 block">
                              {line.sourceLineRefCode || '—'}
                            </span>
                          </td>
                          <td className="py-2">
                            {formatSupplierMoney(line.statementAmount, line.currency)}
                            <span className="text-muted-foreground text-copy-12 block">
                              数量 {formatSupplierQuantity(line.quantity)}
                            </span>
                          </td>
                          <td className="py-2">
                            {line.financeAmount === null
                              ? '—'
                              : formatSupplierMoney(line.financeAmount, line.currency)}
                            <span className="text-muted-foreground text-copy-12 block">
                              数量 {formatSupplierQuantity(line.financeQuantity)}
                            </span>
                          </td>
                          <td className="py-2">{lineVarianceHint(line)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </SectionCard>

              {/* ---------- 质检证据 ---------- */}
              <SectionCard
                title="质检证据"
                description="6.4.2.1 质检单的快照（扣量合计 = 不合格量 + 让步接收量）。"
              >
                {detail.inspections.length === 0 ? (
                  <p className="text-muted-foreground text-copy-13">
                    这张对账单还没有命中质检单 —— <b>没有质检记录不等于没有扣量</b>。
                  </p>
                ) : (
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">质检单</th>
                        <th className="py-2">收货单</th>
                        <th className="py-2">SKU</th>
                        <th className="py-2">合格量</th>
                        <th className="py-2">不合格量</th>
                        <th className="py-2">让步接收量</th>
                        <th className="py-2">扣量合计</th>
                      </tr>
                    </thead>
                    <tbody>
                      {detail.inspections.map((inspection) => (
                        <tr key={inspection.id} className="border-border border-t">
                          <td className="py-2">{inspection.inspectionNo}</td>
                          <td className="py-2">{inspection.receiptCode || '—'}</td>
                          <td className="py-2">{inspection.skuCode || '—'}</td>
                          <td className="py-2">
                            {formatSupplierQuantity(inspection.qualifiedQuantity)}
                          </td>
                          <td className="py-2">
                            {formatSupplierQuantity(inspection.defectiveQuantity)}
                          </td>
                          <td className="py-2">
                            {formatSupplierQuantity(inspection.concessionQuantity)}
                          </td>
                          <td className="py-2 font-medium">
                            {formatSupplierQuantity(inspection.deductedQuantity)}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </SectionCard>

              {/* ---------- 差异清单 ---------- */}
              <SectionCard
                title="差异清单"
                description="每条差异都要逐条处理完才能确认对账单：登记说明（采购侧 / 供应商给出）或裁决（财务侧动作）。"
                extra={
                  <select
                    className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                    value={differenceStatus}
                    aria-label="差异状态"
                    onChange={(e) => setDifferenceStatus(e.target.value)}
                  >
                    <option value="">全部差异</option>
                    <option value="OPEN">未处理</option>
                    <option value="EXPLAINED">已说明待裁决</option>
                    <option value="RESOLVED">已裁决</option>
                  </select>
                }
              >
                {(differences?.rows ?? []).length === 0 ? (
                  <p className="text-muted-foreground text-copy-13">没有差异。</p>
                ) : (
                  <table className="text-copy-13 w-full text-left">
                    <thead className="text-muted-foreground">
                      <tr>
                        <th className="py-2">行</th>
                        <th className="py-2">类型</th>
                        <th className="py-2">差异</th>
                        <th className="py-2">状态</th>
                        <th className="py-2">裁决</th>
                        <th className="py-2" />
                      </tr>
                    </thead>
                    <tbody>
                      {(differences?.rows ?? []).map((difference) => (
                        <tr key={difference.id} className="border-border border-t">
                          <td className="py-2">{difference.lineNo || '—'}</td>
                          <td className="py-2">{differenceTypeLabel(difference.differenceType)}</td>
                          <td className="py-2">
                            {differenceHint(difference)}
                            {difference.explanation && (
                              <span className="text-muted-foreground text-copy-12 block">
                                说明：{difference.explanation}
                              </span>
                            )}
                          </td>
                          <td className="py-2">
                            <Badge tone={differenceStatusTone(difference.status)}>
                              {differenceStatusLabel(difference.status)}
                            </Badge>
                          </td>
                          <td className="py-2">
                            {difference.resolution
                              ? `${resolutionLabel(difference.resolution)} · ${formatSupplierMoney(difference.adjustmentAmount, difference.currency)}`
                              : '—'}
                          </td>
                          <td className="py-2 text-right">
                            {difference.status !== 'RESOLVED' && (
                              <div className="flex justify-end gap-2">
                                <Button
                                  variant="secondary"
                                  onClick={() => void runAddExplanation(head.id, difference.id)}
                                >
                                  登记说明
                                </Button>
                                <Button
                                  variant="secondary"
                                  onClick={() => void runResolve(head.id, difference.id)}
                                >
                                  裁决
                                </Button>
                              </div>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                )}
              </SectionCard>

              {/* ---------- 事件流 ---------- */}
              <SectionCard
                title="事件流"
                description="append-only 举证：谁在什么时候做了什么（采购侧动作与财务侧动作分开显示）。"
              >
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">时刻</th>
                      <th className="py-2">动作</th>
                      <th className="py-2">动作方</th>
                      <th className="py-2">说明</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.events.map((event) => (
                      <tr key={event.id} className="border-border border-t">
                        <td className="py-2">{event.occurredAt}</td>
                        <td className="py-2">{reconciliationEventLabel(event.eventType)}</td>
                        <td className="py-2">
                          <Badge tone={event.actorRole === 'FINANCE' ? 'success' : 'neutral'}>
                            {actorRoleLabel(event.actorRole)}
                          </Badge>
                        </td>
                        <td className="py-2">{event.reason}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </SectionCard>
            </>
          )}
        </>
      )}
    </div>
  );
}
