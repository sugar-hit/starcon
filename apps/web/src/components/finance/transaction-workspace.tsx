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
  FinanceTransactionCapabilities,
  FinanceTransactionImportResult,
  FinanceTransactionOptions,
  FinanceTransactionPage,
  FinanceTransactionReconciliation,
} from '../../../../../packages/api-contracts/src/index';
import {
  TRANSACTION_TYPES,
  fetchTransactionCapabilities,
  fetchTransactionOptions,
  fetchTransactionReconciliation,
  fetchTransactions,
  formatOrderReference,
  formatTransactionContribution,
  formatTransactionDate,
  formatTransactionMoney,
  importTransactionReport,
  reconciliationHint,
  registerTransaction,
  transactionCategoryLabel,
  transactionSourceLabel,
  transactionSettlementStatusLabel,
  transactionTypeLabel,
} from './transaction-model';

/**
 * 12.3.1.2 交易明细。
 *
 * <p>蓝图 12-finance.md：「管理结算单下的逐笔明细：订单销售、退款、费用（佣金/仓储/配送/广告）、赔付、调整、代扣税，
 * 保留外部流水号与币种；<b>允许无订单行的费用类明细</b>。」输出：「明细行 + <b>汇总校验（明细合计 = 结算单汇总）</b>。」
 *
 * <p><b>页面围绕三条纪律组织</b>：
 *
 * <ol>
 *   <li><b>订单行可以没有，而且这是正常状态</b>：佣金、仓储费、月度调整天然无订单行
 *       （蓝图加粗句「不强制每行都有订单」）。页面把「没有订单号」显示成「—」，
 *       <b>不</b>渲染成异常、<b>不</b>提供「必须填订单号」的强制提示。</li>
 *   <li><b>汇总校验是实时算出来的</b>：页面显示服务端算好的「明细合计 / 汇总合计 / 结算金额」三者，
 *       并把**不恒成立**的那些口径（分类、笔数、币种、符号、周期）单列出来 ——
 *       「金额平了」不等于「账对了」。</li>
 *   <li><b>上游没交付的事就说没交付</b>：平台交易报告自动同步（上游「卷 02 同步」未交付）
 *       没有入口；自动对账任务（12.3.2.1）与利润（13）未交付 ⇒ 页面只显示
 *       <b>汇总校验</b>，不提供「跑对账 / 看利润」的按钮。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有交易明细」。
 */
export function TransactionWorkspace() {
  const [caps, setCaps] = useState<FinanceTransactionCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceTransactionOptions | null>(null);
  const [page, setPage] = useState<FinanceTransactionPage | null>(null);
  const [check, setCheck] = useState<FinanceTransactionReconciliation | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [settlementId, setSettlementId] = useState('');
  const [transactionType, setTransactionType] = useState('');
  const [currency, setCurrency] = useState('');
  const [source, setSource] = useState('');
  const [hasOrder, setHasOrder] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 登记一条明细
  const [regSettlement, setRegSettlement] = useState('');
  const [regType, setRegType] = useState<string>('ORDER_SALES');
  const [regAmount, setRegAmount] = useState('');
  const [regKey, setRegKey] = useState('');
  const [regExternal, setRegExternal] = useState('');
  const [regOrder, setRegOrder] = useState('');
  const [regOrderLine, setRegOrderLine] = useState('');
  const [regOccurredOn, setRegOccurredOn] = useState('');
  const [regDescription, setRegDescription] = useState('');

  // 上传交易报告
  const [impSettlement, setImpSettlement] = useState('');
  const [impFileKey, setImpFileKey] = useState('');
  const [impFormat, setImpFormat] = useState<'CSV' | 'JSON'>('CSV');
  const [impContent, setImpContent] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [capsData, optionsData, pageData] = await Promise.all([
        fetchTransactionCapabilities(),
        fetchTransactionOptions(),
        fetchTransactions({
          settlementId: settlementId || undefined,
          transactionType: transactionType || undefined,
          currency: currency || undefined,
          source: source || undefined,
          hasOrder: hasOrder === '' ? undefined : hasOrder === 'yes',
          keyword: keyword || undefined,
          page: currentPage,
          size: 50,
        }),
      ]);
      setCaps(capsData);
      setOptions(optionsData);
      setPage(pageData);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败');
    } finally {
      setLoading(false);
    }
  }, [settlementId, transactionType, currency, source, hasOrder, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  // 选中结算单时，顺手把汇总校验拉出来（明细与汇总必须能对上）。
  useEffect(() => {
    if (!settlementId) {
      setCheck(null);
      return;
    }
    let cancelled = false;
    fetchTransactionReconciliation(settlementId)
      .then((data) => {
        if (!cancelled) setCheck(data);
      })
      .catch(() => {
        if (!cancelled) setCheck(null);
      });
    return () => {
      cancelled = true;
    };
  }, [settlementId, page]);

  async function runRegister() {
    setActionError('');
    setNotice('');
    if (!regSettlement) {
      setActionError('请选择结算单：交易明细挂在结算单下');
      return;
    }
    if (!regAmount.trim() || Number(regAmount) === 0) {
      setActionError('请写明金额：金额为 0 的明细只会污染笔数口径');
      return;
    }
    if (!regKey.trim()) {
      setActionError('请写明幂等键：没有幂等键就没法保证「同一笔平台交易不记两次」');
      return;
    }
    try {
      const created = await registerTransaction({
        source: 'MANUAL',
        settlementId: regSettlement,
        transactionType: regType as never,
        amount: Number(regAmount),
        currency: null,
        quantity: null,
        occurredOn: regOccurredOn || null,
        externalReference: regExternal.trim() || null,
        // ⚠️ 订单号与订单行**可空**：费用类明细天然没有订单行。
        orderReference: regOrder.trim() || null,
        orderLineReference: regOrderLine.trim() || null,
        idempotencyKey: regKey.trim(),
        description: regDescription.trim() || null,
      });
      setNotice(
        `已登记 ${transactionTypeLabel(created.transactionType)} ${formatTransactionMoney(
          created.amount,
          created.currency,
        )}（结算单 ${created.settlementNo}）`,
      );
      setRegAmount('');
      setRegKey('');
      setRegExternal('');
      setRegOrder('');
      setRegOrderLine('');
      setRegDescription('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记交易明细失败');
    }
  }

  async function runImport() {
    setActionError('');
    setNotice('');
    if (!impSettlement) {
      setActionError('请选择要追加到哪张结算单');
      return;
    }
    if (!impFileKey.trim()) {
      setActionError('请写明交易报告身份（平台交易报告号 / 上传文件名）');
      return;
    }
    try {
      const result: FinanceTransactionImportResult = await importTransactionReport({
        source: 'MANUAL_UPLOAD',
        settlementId: impSettlement,
        fileKey: impFileKey.trim(),
        format: impFormat,
        content: impContent,
        note: null,
      });
      const lines = [
        `读到 ${result.scanned} 条，写入 ${result.imported} 行，重复 ${result.duplicated} 条，跳过 ${result.skipped} 条（第 ${result.parseVersion} 版解析）`,
      ];
      for (const warning of result.warnings) lines.push(`⚠️ ${warning}`);
      setNotice(lines.join('；'));
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '交易报告导入失败');
    }
  }

  const settlements = options?.settlements ?? [];
  const orderlessCount = useMemo(
    () => (page?.summaryByType ?? []).reduce((sum, t) => sum + t.orderlessCount, 0),
    [page],
  );
  const hasFilters = Boolean(settlementId || transactionType || currency || source || keyword);

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="交易明细"
        description="结算单下的逐笔明细：订单销售 / 退款 / 佣金 / 仓储费 / 配送费 / 广告费 / 赔付 / 其他调整 / 代扣税 · 外部流水号与币种 · 允许无订单行的费用类明细"
        actions={
          <div className="flex items-center gap-2">
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
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
          {/* ---------- 能力声明：上游没交付的事必须说在界面上 ---------- */}
          {caps && caps.capabilities.some((c) => !c.available) && (
            <SectionCard
              title="能力声明"
              description="🔴 标红的这几项**当前不可用** —— 界面不提供它们的入口，也不拿假数据顶替。"
            >
              <ul className="space-y-2 text-sm">
                {caps.capabilities
                  .filter((c) => !c.available)
                  .map((c) => (
                    <li key={`${c.module}-${c.name}`} className="flex items-start gap-2">
                      <Badge tone="danger">不可用</Badge>
                      <span>
                        <b>{c.name}</b>
                        {c.ownerModule ? `（归属模块 ${c.ownerModule}）` : ''}：{c.detail}
                      </span>
                    </li>
                  ))}
              </ul>
            </SectionCard>
          )}

          {/* ---------- 概览 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard
              label="明细笔数"
              value={page?.total ?? 0}
              unit="行"
              comparisonLabel="按当前筛选条件"
            />
            <StatCard
              label="币种数"
              value={page?.summaryByCurrency.length ?? 0}
              unit="种"
              comparisonLabel="不同币种不合并"
            />
            <StatCard
              label="无订单行"
              value={orderlessCount}
              unit="行"
              comparisonLabel="费用类天然没有订单行，不是异常"
            />
            <StatCard
              label="交易类型"
              value={page?.summaryByType.length ?? 0}
              unit="类"
              comparisonLabel="按当前筛选条件"
            />
          </div>

          {/* ---------- 汇总校验 ---------- */}
          <SectionCard
            title="汇总校验"
            description="明细合计 = 结算单汇总 —— **实时算出来的**，不是存下来的快照。金额平了不等于账对了：分类口径 / 笔数 / 币种 / 符号 / 周期分列在下面。"
          >
            {!settlementId ? (
              <EmptyState
                title="先选一张结算单"
                description="选中结算单后，这里会显示明细合计、汇总合计与结算金额三者的核对结果。"
              />
            ) : !check ? (
              <p className="text-muted-foreground text-sm">正在读取汇总校验…</p>
            ) : (
              <div className="space-y-3">
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <Badge tone={check.balanced ? 'success' : 'danger'}>
                    {check.balanced ? '金额对平' : '金额对不平'}
                  </Badge>
                  <span>{reconciliationHint(check)}</span>
                </div>
                <p className="text-muted-foreground text-xs">
                  结算单 {check.settlementNo} · 周期 {formatTransactionDate(check.periodStart)} ~
                  {formatTransactionDate(check.periodEnd)} · 币种 {check.currency} · 共 {check.lineCount} 行 ·
                  其中 {check.orderlessLineCount} 行没有订单行（费用类天然如此，**不是异常**）
                </p>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">类别</th>
                      <th className="py-2">明细合计</th>
                      <th className="py-2">汇总项</th>
                      <th className="py-2">笔数</th>
                      <th className="py-2">汇总笔数</th>
                      <th className="py-2">核对</th>
                    </tr>
                  </thead>
                  <tbody>
                    {check.categories.map((c) => (
                      <tr key={c.category} className="border-border border-t">
                        <td className="py-2 font-medium">{c.categoryLabel}</td>
                        <td className="py-2">{formatTransactionMoney(c.lineAmount, check.currency)}</td>
                        <td className="py-2">{formatTransactionMoney(c.summaryAmount, check.currency)}</td>
                        <td className="py-2">{c.lineCount}</td>
                        <td className="py-2">{c.summaryLineCount}</td>
                        <td className="py-2">
                          <Badge tone={c.matched ? 'success' : 'danger'}>
                            {c.matched ? '一致' : '对不上'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {check.issues.length > 0 ? (
                  <Notice tone="warning" title="口径上有这些地方要看">
                    <ul className="space-y-1">
                      {check.issues.map((issue) => (
                        <li key={issue}>· {issue}</li>
                      ))}
                    </ul>
                  </Notice>
                ) : (
                  <p className="text-muted-foreground text-xs">
                    没有口径问题。「销售额明细没有订单号」**不算问题** —— 蓝图明令不强制每行都有订单。
                  </p>
                )}
              </div>
            )}
          </SectionCard>

          {/* ---------- 按交易类型的汇总 ---------- */}
          <SectionCard
            title="按交易类型汇总"
            description="金额是**对结算金额的贡献**（正=增加、负=减少）。⚠️ 作废与被新版本取代的结算单下的明细不计入。"
          >
            {!page || page.summaryByType.length === 0 ? (
              <EmptyState
                title="还没有在册的交易明细"
                description="登记一条明细，或上传一份平台交易报告后，这里会按交易类型显示金额与笔数。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">交易类型</th>
                    <th className="py-2">结算类别</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">笔数</th>
                    <th className="py-2">其中无订单行</th>
                  </tr>
                </thead>
                <tbody>
                  {page.summaryByType.map((t) => (
                    <tr key={t.transactionType} className="border-border border-t">
                      <td className="py-2 font-medium">{t.transactionTypeLabel}</td>
                      <td className="py-2">{t.categoryLabel}</td>
                      <td className="py-2 font-medium">
                        {formatTransactionContribution(t.amount)}
                      </td>
                      <td className="py-2">{t.count}</td>
                      <td className="py-2">{t.orderlessCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 筛选 ---------- */}
          <SectionCard title="筛选" description="筛选项全部来自真实数据，不是写死的词表。">
            <div className="grid gap-3 md:grid-cols-4">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={settlementId}
                onChange={(e) => {
                  setSettlementId(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部结算单</option>
                {settlements.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.settlementNo}（{s.shopCode} · {formatTransactionDate(s.periodStart)}~
                    {formatTransactionDate(s.periodEnd)}）
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={transactionType}
                onChange={(e) => {
                  setTransactionType(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部交易类型</option>
                {TRANSACTION_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {transactionTypeLabel(t)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={currency}
                onChange={(e) => {
                  setCurrency(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部币种</option>
                {(options?.currencies ?? []).map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={source}
                onChange={(e) => {
                  setSource(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部来源</option>
                {(options?.sources ?? []).map((s) => (
                  <option key={s} value={s}>
                    {transactionSourceLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={hasOrder}
                onChange={(e) => {
                  setHasOrder(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">有无订单行都行</option>
                <option value="yes">只看有订单行的</option>
                <option value="no">只看没有订单行的（费用类）</option>
              </select>
              <Input
                placeholder="关键字（订单号 / 外部流水号 / 幂等键 / 备注）"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
              />
            </div>
          </SectionCard>

          {/* ---------- 明细列表 ---------- */}
          <SectionCard
            title="交易明细"
            description="⚠️ 明细行是**外部事实**（append-only）：只能追加，不能改也不能删 —— 账单是外部凭据，改账单等于改写凭据，正确做法是重新解析建新单。"
          >
            {!page || page.items.length === 0 ? (
              <EmptyState
                title={hasFilters ? '当前筛选条件下没有明细' : '还没有交易明细'}
                description={
                  hasFilters
                    ? '换个筛选条件看看，或者清空筛选条件。'
                    : '登记一条明细，或上传一份平台交易报告。'
                }
              />
            ) : (
              <>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">结算单</th>
                      <th className="py-2">行号</th>
                      <th className="py-2">交易类型</th>
                      <th className="py-2">贡献</th>
                      <th className="py-2">账单原值</th>
                      <th className="py-2">币种</th>
                      <th className="py-2">账单日期</th>
                      <th className="py-2">外部流水号</th>
                      <th className="py-2">订单号</th>
                      <th className="py-2">幂等键</th>
                      <th className="py-2">来源</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((row) => (
                      <tr key={row.id} className="border-border border-t">
                        <td className="py-2">
                          {row.settlementNo}
                          <span className="text-muted-foreground block text-xs">
                            {row.shopCode} · {formatTransactionDate(row.periodStart)}~
                            {formatTransactionDate(row.periodEnd)}
                          </span>
                        </td>
                        <td className="py-2">{row.lineNo}</td>
                        <td className="py-2">
                          {row.transactionTypeLabel}
                          <span className="text-muted-foreground block text-xs">
                            {transactionCategoryLabel(row.category)}
                          </span>
                        </td>
                        <td className="py-2 font-medium">
                          {formatTransactionContribution(row.amount)}
                        </td>
                        <td className="py-2">{formatTransactionMoney(row.rawAmount, row.currency)}</td>
                        <td className="py-2">{row.currency}</td>
                        <td className="py-2">{formatTransactionDate(row.occurredOn)}</td>
                        <td className="py-2">{row.externalReference ?? '—'}</td>
                        {/* ⚠️ 没有订单号是**正常状态**（费用类明细天然无订单行），显示「—」而不是异常。 */}
                        <td className="py-2">{formatOrderReference(row.orderReference)}</td>
                        <td className="py-2 text-xs">{row.idempotencyKey ?? '—'}</td>
                        <td className="py-2 text-xs">{transactionSourceLabel(row.source)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="mt-3 flex items-center justify-between text-sm">
                  <span className="text-muted-foreground text-xs">
                    第 {page.page + 1} 页 · 共 {page.total} 行
                  </span>
                  <div className="flex items-center gap-2">
                    <Button
                      variant="secondary"
                      disabled={currentPage === 0}
                      onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                    >
                      上一页
                    </Button>
                    <Button
                      variant="secondary"
                      disabled={(currentPage + 1) * page.size >= page.total}
                      onClick={() => setCurrentPage((p) => p + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </>
            )}
          </SectionCard>

          {/* ---------- 登记一条明细 ---------- */}
          <SectionCard
            title="登记一条明细"
            description="⚠️ 订单号与订单行**可以留空** —— 佣金、仓储费、月度调整天然没有订单行（蓝图加粗句「不强制每行都有订单」）。幂等键**必填**：没有它就没法保证「同一笔平台交易不记两次」。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={regSettlement}
                onChange={(e) => setRegSettlement(e.target.value)}
              >
                <option value="">选择结算单</option>
                {settlements.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.settlementNo}（{s.shopCode}）
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={regType}
                onChange={(e) => setRegType(e.target.value)}
              >
                {TRANSACTION_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {transactionTypeLabel(t)}
                  </option>
                ))}
              </select>
              <Input
                placeholder="金额（如 100.00，账单里写的那个数）"
                value={regAmount}
                onChange={(e) => setRegAmount(e.target.value)}
              />
              <Input
                placeholder="幂等键（必填，如 TX-2026-0001）"
                value={regKey}
                onChange={(e) => setRegKey(e.target.value)}
              />
              <Input
                placeholder="外部流水号（平台交易号，可空）"
                value={regExternal}
                onChange={(e) => setRegExternal(e.target.value)}
              />
              <Input
                placeholder="订单号（可空：费用类没有订单行）"
                value={regOrder}
                onChange={(e) => setRegOrder(e.target.value)}
              />
              <Input
                placeholder="订单行引用（可空）"
                value={regOrderLine}
                onChange={(e) => setRegOrderLine(e.target.value)}
              />
              <Input
                type="date"
                placeholder="账单日期（可空）"
                value={regOccurredOn}
                onChange={(e) => setRegOccurredOn(e.target.value)}
              />
              <Input
                placeholder="备注（可空）"
                value={regDescription}
                onChange={(e) => setRegDescription(e.target.value)}
              />
            </div>
            <div className="mt-3 flex items-center gap-2">
              <Button onClick={() => void runRegister()}>登记明细</Button>
              <span className="text-muted-foreground text-xs">
                金额会按交易类型的符号约定归一（销售额/赔付为正，退款/佣金/费用/广告/代扣税为负），
                账单原值同时保留在库里，方便逐行核对。
              </span>
            </div>
          </SectionCard>

          {/* ---------- 上传平台交易报告 ---------- */}
          <SectionCard
            title="上传平台交易报告"
            description="CSV / JSON 真解析、真入库。⚠️ 文件级错误整体失败；行级错误只跳过那一行并逐条说明 —— 告警会原样显示在这里。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={impSettlement}
                onChange={(e) => setImpSettlement(e.target.value)}
              >
                <option value="">追加到哪张结算单</option>
                {settlements.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.settlementNo}（{s.shopCode}）
                  </option>
                ))}
              </select>
              <Input
                placeholder="交易报告身份：平台交易报告号 / 上传文件名"
                value={impFileKey}
                onChange={(e) => setImpFileKey(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={impFormat}
                onChange={(e) => setImpFormat(e.target.value as 'CSV' | 'JSON')}
              >
                <option value="CSV">CSV</option>
                <option value="JSON">JSON</option>
              </select>
            </div>
            <textarea
              className="border-border bg-background mt-3 h-40 w-full rounded-md border p-2 font-mono text-xs"
              placeholder={
                impFormat === 'CSV'
                  ? 'transaction_type,amount,currency,quantity,occurred_on,external_reference,order_reference,idempotency_key,description\nORDER_SALES,1000.00,USD,10,2026-10-01,TX-1,ORDER-1,IDEM-1,销售额\nCOMMISSION,150.00,USD,,2026-10-05,TX-2,,IDEM-2,平台佣金'
                  : '{"rows":[{"transaction_type":"ORDER_SALES","amount":"1000.00","external_reference":"TX-1","idempotency_key":"IDEM-1"}]}'
              }
              value={impContent}
              onChange={(e) => setImpContent(e.target.value)}
            />
            <div className="mt-3 flex items-center gap-2">
              <Button onClick={() => void runImport()}>导入交易报告</Button>
              <span className="text-muted-foreground text-xs">
                交易类型认得 {TRANSACTION_TYPES.length} 个词表值及其常见别名；不认识的类型**按行拒收**
                （猜错类型等于把退款记成销售额）。没有幂等键也没有外部流水号的行同样拒收。
              </span>
            </div>
          </SectionCard>

          {/* ---------- 所属结算单状态 ---------- */}
          {check && (
            <SectionCard title="所属结算单" description="明细行挂在结算单下；结算单是终态后不可再追加明细。">
              <p className="text-sm">
                {check.settlementNo} · 状态 {transactionSettlementStatusLabel(check.settlementStatus ?? '')}
                {' · '}
                结算金额 {formatTransactionMoney(check.settlementAmount, check.currency)}
              </p>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
