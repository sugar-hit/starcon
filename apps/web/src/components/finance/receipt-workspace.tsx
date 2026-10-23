'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Download, RefreshCw, TriangleAlert } from 'lucide-react';
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
  FinanceReceiptAllocationRow,
  FinanceReceiptBalance,
  FinanceReceiptCapabilities,
  FinanceReceiptDetail,
  FinanceReceiptOptions,
  FinanceReceiptPage,
  FinanceReceiptRow,
} from '../../../../../packages/api-contracts/src/index';
import { fetchReceivables } from './receivable-model';
import {
  allocationStatusLabel,
  createReceipt,
  fetchReceipt,
  fetchReceiptBalance,
  fetchReceiptCapabilities,
  fetchReceiptOptions,
  fetchReceipts,
  formatReceivedAt,
  formatReceiptAmount,
  formatReceiptMoney,
  fundAccountLabelHint,
  importReceipts,
  payerTypeLabel,
  receiptEventLabel,
  receiptSourceLabel,
  receiptSourceSystemLabel,
  receiptStatusLabel,
  receiptStatusTone,
  releaseReceiptAllocation,
  reserveReceiptAllocation,
  voidReceipt,
} from './receipt-model';

/**
 * 12.2.1.1 收款单。
 *
 * <p>蓝图 12-finance.md：「登记与跟踪资金收入：平台回款、银行到账、PayPal/Stripe/PingPong 等第三方支付到账、
 * 其他收入；记录资金账户、金额币种、到账时间与来源。」输入：「到账流水导入或手工登记；结算数据。」
 * 输出：「收款单 + 未核销余额。」加粗句：「<b>一笔收款可核销多笔应收</b>（合并回款），也可能是预收。」
 *
 * <p><b>页面围绕那条加粗句组织</b>：详情里有「预留核销」区块 —— 一笔收款可以挂多笔应收
 * （合并回款），也可以一笔都不挂（**预收**：钱先到了，对应的应收还没到）。
 *
 * <p><b>页面刻意没有的三个东西</b>：
 *
 * <ol>
 *   <li><b>「用平台结算建收款」的入口</b>：上游 12.3.1 结算**未交付**，
 *       放一个能点的入口就是骗人 —— 能力声明里如实报出归属模块 12.3.1.1。</li>
 *   <li><b>资金账户选择器</b>：12.7.1 账户管理**未交付**，没有受管账户可选；
 *       页面只能填**自由文本**标识，并在字段旁写明它**不是**受管资金账户。</li>
 *   <li><b>「标记已核销」按钮</b>：核销（12.2.1.2）**未交付** ——
 *       页面只显示预留关系与未核销余额，**不提供**任何假装核销的写入口。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有收款单」。
 */
export function ReceiptWorkspace() {
  const [caps, setCaps] = useState<FinanceReceiptCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceReceiptOptions | null>(null);
  const [page, setPage] = useState<FinanceReceiptPage | null>(null);
  const [balance, setBalance] = useState<FinanceReceiptBalance | null>(null);
  const [detail, setDetail] = useState<FinanceReceiptDetail | null>(null);
  const [openReceivables, setOpenReceivables] = useState<
    { id: string; receivableNo: string; currency: string; openAmount: number }[]
  >([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [source, setSource] = useState('');
  const [currency, setCurrency] = useState('');
  const [sourceSystem, setSourceSystem] = useState('');
  const [advanceOnly, setAdvanceOnly] = useState(false);
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 手工登记
  const [entrySource, setEntrySource] = useState('MANUAL');
  const [entryCurrency, setEntryCurrency] = useState('USD');
  const [entryAmount, setEntryAmount] = useState('');
  const [entryReceivedAt, setEntryReceivedAt] = useState('');
  const [entryPayerType, setEntryPayerType] = useState('CUSTOMER');
  const [entryPayerName, setEntryPayerName] = useState('');
  const [entryAccountLabel, setEntryAccountLabel] = useState('');
  const [entryAdvance, setEntryAdvance] = useState(false);
  const [entryNote, setEntryNote] = useState('');

  // 到账流水导入
  const [importSource, setImportSource] = useState('BANK_TRANSFER');
  const [importSourceSystem, setImportSourceSystem] = useState('');
  const [importFormat, setImportFormat] = useState('CSV');
  const [importContent, setImportContent] = useState('');

  // 预留核销
  const [reserveReceivableId, setReserveReceivableId] = useState('');
  const [reserveAmount, setReserveAmount] = useState('');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = {
    ...(status ? { status } : {}),
    ...(source ? { source } : {}),
    ...(currency ? { currency } : {}),
    ...(sourceSystem ? { sourceSystem } : {}),
    ...(advanceOnly ? { advanceOnly } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, b] = await Promise.all([
        fetchReceiptCapabilities(),
        fetchReceiptOptions(),
        fetchReceipts(params),
        fetchReceiptBalance(),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
      setBalance(b);
    } catch (e) {
      setError(e instanceof Error ? e.message : '收款单加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, source, currency, sourceSystem, advanceOnly, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 打开详情时顺带拉「待核销的应收」，好让「合并回款」可以**选**，
   * 而不是让人去别处抄一个 UUID。
   */
  const openDetail = useCallback(async (id: string) => {
    setActionError('');
    try {
      const [d, receivables] = await Promise.all([
        fetchReceipt(id),
        fetchReceivables({ status: 'OPEN', page: 0, size: 100 }),
      ]);
      setDetail(d);
      setOpenReceivables(
        receivables.items.map((r) => ({
          id: r.id,
          receivableNo: r.receivableNo,
          currency: r.currency,
          openAmount: r.amount - r.settledAmount,
        })),
      );
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '收款单详情加载失败');
    }
  }, []);

  /** 可选的应收：只列与这笔收款**同币种**的（跨币种核销是硬纪律）。 */
  const selectableReceivables = useMemo(() => {
    if (!detail) return [];
    return openReceivables.filter((r) => r.currency === detail.receipt.currency);
  }, [detail, openReceivables]);

  async function submitManual() {
    setActionError('');
    setNotice('');
    if (!entryPayerName.trim()) {
      setActionError('请写明付款方名称');
      return;
    }
    const amount = Number(entryAmount);
    if (!entryAmount || Number.isNaN(amount) || amount <= 0) {
      setActionError('金额必须大于 0');
      return;
    }
    if (!entryReceivedAt) {
      setActionError('请写明到账时间（默认填当前时刻会把「昨天到账的钱」记成今天到账）');
      return;
    }
    try {
      await createReceipt({
        source: entrySource as 'MANUAL' | 'BANK_TRANSFER' | 'PAYMENT_GATEWAY' | 'OTHER',
        sourceSystem: null,
        sourceReference: null,
        currency: entryCurrency.trim().toUpperCase(),
        amount,
        // ⚠️ datetime-local 给的是**浏览器本地时间**（不带偏移），
        //    toISOString() 把它换成带 Z 的 UTC 时刻 —— 这一步就是「不偏 8 小时」的关键。
        receivedAt: new Date(entryReceivedAt).toISOString(),
        payerType: entryPayerType as 'PLATFORM' | 'CUSTOMER' | 'PARTNER' | 'OTHER',
        payerName: entryPayerName.trim(),
        payerRef: null,
        fundAccountLabel: entryAccountLabel.trim() || null,
        advanceFlag: entryAdvance,
        note: entryNote.trim() || null,
      });
      setNotice('已登记一张收款单');
      setEntryAmount('');
      setEntryPayerName('');
      setEntryAccountLabel('');
      setEntryNote('');
      setEntryReceivedAt('');
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记收款单失败');
    }
  }

  async function runImport() {
    setActionError('');
    setNotice('');
    if (!importContent.trim()) {
      setActionError('请粘贴到账流水内容');
      return;
    }
    if (!importSourceSystem.trim()) {
      setActionError('请写明来源系统（它与来源流水号组成幂等键）');
      return;
    }
    try {
      const result = await importReceipts({
        source: importSource as 'MANUAL' | 'BANK_TRANSFER' | 'PAYMENT_GATEWAY' | 'OTHER',
        sourceSystem: importSourceSystem.trim(),
        format: importFormat as 'CSV' | 'JSON',
        content: importContent,
      });
      const lines = [`读到 ${result.scanned} 条，新建 ${result.imported} 张，跳过 ${result.skipped} 条`];
      for (const warning of result.warnings) lines.push(`⚠️ ${warning}`);
      setNotice(lines.join('；'));
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '到账流水导入失败');
    }
  }

  async function doReserve() {
    if (!detail) return;
    setActionError('');
    setNotice('');
    if (!reserveReceivableId) {
      setActionError('请选择要预留核销的应收单');
      return;
    }
    const amount = Number(reserveAmount);
    if (!reserveAmount || Number.isNaN(amount) || amount <= 0) {
      setActionError('预留金额必须大于 0');
      return;
    }
    try {
      await reserveReceiptAllocation(detail.receipt.id, {
        receivableId: reserveReceivableId,
        reservedAmount: amount,
        note: '页面发起',
      });
      setNotice('已登记一笔预留核销（⚠️ 预留不是核销：未核销余额不会因此减少）');
      setReserveReceivableId('');
      setReserveAmount('');
      await openDetail(detail.receipt.id);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '登记预留核销失败');
    }
  }

  async function doRelease(allocation: FinanceReceiptAllocationRow) {
    if (!detail) return;
    setActionError('');
    setNotice('');
    try {
      await releaseReceiptAllocation(detail.receipt.id, allocation.id);
      setNotice('已释放这笔预留核销');
      await openDetail(detail.receipt.id);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '释放预留核销失败');
    }
  }

  async function doVoid(row: FinanceReceiptRow) {
    setActionError('');
    setNotice('');
    const reason = window.prompt('作废理由（必填）：', '');
    if (!reason || !reason.trim()) return;
    try {
      await voidReceipt(row.id, reason.trim());
      setNotice(`已作废 ${row.receiptNo} —— 作废是终态，整单冻结`);
      setDetail(null);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '作废失败');
    }
  }

  const hasFilters = Boolean(status || source || currency || sourceSystem || keyword) || advanceOnly;

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="收款单"
        description="来源 / 金额与币种 / 到账时间 / 资金账户标识 · 未核销余额（按币种分开）"
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
          {/* ---------- 汇总 ---------- */}
          <div className="grid gap-4 md:grid-cols-4">
            <StatCard label="在册收款单" value={balance?.receiptCount ?? 0} unit="单" />
            <StatCard label="预收单" value={balance?.advanceCount ?? 0} unit="单" />
            <StatCard
              label="收款单总数"
              value={page?.total ?? 0}
              unit="单"
              comparisonLabel="按当前筛选条件"
            />
            <StatCard
              label="币种数"
              value={balance?.byCurrency.length ?? 0}
              unit="种"
              comparisonLabel="不同币种不合并"
            />
          </div>

          {/* ---------- 未核销余额（12.2.1.1 的输出之二） ---------- */}
          <SectionCard
            title="未核销余额"
            description="⚠️ 核销（12.2.1.2）未交付 ⇒ 未核销余额**恒等于收款金额**（还没有任何核销能减它）。不同币种**不**合并。"
          >
            {!balance || balance.byCurrency.length === 0 ? (
              <EmptyState
                title="还没有未核销的收款"
                description="登记一笔收款或导入一份到账流水后，这里会按币种显示未核销余额。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">币种</th>
                    <th className="py-2">收款金额</th>
                    <th className="py-2">已核销</th>
                    <th className="py-2">未核销余额</th>
                    <th className="py-2">其中预收</th>
                    <th className="py-2">单数</th>
                  </tr>
                </thead>
                <tbody>
                  {balance.byCurrency.map((s) => (
                    <tr key={s.currency} className="border-border border-t">
                      <td className="py-2 font-medium">{s.currency}</td>
                      <td className="py-2">{formatReceiptAmount(s.amount)}</td>
                      <td className="py-2">{formatReceiptAmount(s.settledAmount)}</td>
                      <td className="py-2 font-medium">{formatReceiptAmount(s.unsettledAmount)}</td>
                      <td className="py-2">
                        {s.advanceAmount > 0 ? (
                          <Badge tone="warning">{formatReceiptAmount(s.advanceAmount)}</Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="py-2">{s.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 收款单列表 ---------- */}
          <SectionCard title="收款单" description="点一行看预留核销关系与事件流。">
            <div className="mb-4 grid gap-3 md:grid-cols-4">
              <Input
                placeholder="关键字：单号 / 付款方 / 来源流水号 / 账户标识"
                value={keyword}
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setCurrentPage(0);
                }}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={status}
                onChange={(e) => {
                  setStatus(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部状态</option>
                {(options?.statuses ?? []).map((s) => (
                  <option key={s} value={s}>
                    {receiptStatusLabel(s)}
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
                    {receiptSourceLabel(s)}
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
                value={sourceSystem}
                onChange={(e) => {
                  setSourceSystem(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部来源系统</option>
                {(options?.sourceSystems ?? []).map((s) => (
                  <option key={s} value={s}>
                    {receiptSourceSystemLabel(s)}
                  </option>
                ))}
              </select>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={advanceOnly}
                  onChange={(e) => {
                    setAdvanceOnly(e.target.checked);
                    setCurrentPage(0);
                  }}
                />
                只看预收
              </label>
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                title={hasFilters ? '没有符合筛选条件的收款单' : '还没有收款单'}
                description={
                  hasFilters
                    ? '把筛选条件放宽一点再试。'
                    : '先在下面「手工登记」或「到账流水导入」里登记一笔到账。'
                }
              />
            ) : (
              <>
                <table className="text-copy-13 w-full text-left">
                  <thead className="text-muted-foreground">
                    <tr>
                      <th className="py-2">单号</th>
                      <th className="py-2">来源</th>
                      <th className="py-2">付款方</th>
                      <th className="py-2">金额</th>
                      <th className="py-2">未核销</th>
                      <th className="py-2">到账时间</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">操作</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.items.map((row) => (
                      <tr key={row.id} className="border-border border-t">
                        <td className="py-2">
                          <button
                            type="button"
                            className="underline-offset-2 hover:underline"
                            onClick={() => void openDetail(row.id)}
                          >
                            {row.receiptNo}
                          </button>
                          {row.advanceFlag && (
                            <Badge tone="warning" className="ml-2">
                              预收
                            </Badge>
                          )}
                          {row.sourceReference && (
                            <div className="text-muted-foreground text-copy-12">
                              来源流水号 {row.sourceReference}
                            </div>
                          )}
                        </td>
                        <td className="py-2">
                          {receiptSourceLabel(row.source)}
                          <div className="text-muted-foreground text-copy-12">
                            {receiptSourceSystemLabel(row.sourceSystem)}
                          </div>
                        </td>
                        <td className="py-2">
                          {row.payerName}
                          <div className="text-muted-foreground text-copy-12">
                            {payerTypeLabel(row.payerType)}
                          </div>
                        </td>
                        <td className="py-2">{formatReceiptMoney(row.amount, row.currency)}</td>
                        <td className="py-2">{formatReceiptAmount(row.unsettledAmount)}</td>
                        <td className="py-2">{formatReceivedAt(row.receivedAt)}</td>
                        <td className="py-2">
                          <Badge tone={receiptStatusTone(row.status)}>
                            {receiptStatusLabel(row.status)}
                          </Badge>
                        </td>
                        <td className="py-2">
                          <div className="flex flex-wrap gap-2">
                            <Button
                              size="sm"
                              variant="ghost"
                              onClick={() => void openDetail(row.id)}
                            >
                              详情
                            </Button>
                            {row.status !== 'VOID' && (
                              <Button size="sm" variant="ghost" onClick={() => void doVoid(row)}>
                                作废
                              </Button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="text-muted-foreground mt-3 flex items-center justify-between text-sm">
                  <span>
                    共 {page.total} 单，第 {page.page + 1} 页
                  </span>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={page.page <= 0}
                      onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
                    >
                      上一页
                    </Button>
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={(page.page + 1) * page.size >= page.total}
                      onClick={() => setCurrentPage((p) => p + 1)}
                    >
                      下一页
                    </Button>
                  </div>
                </div>
              </>
            )}
          </SectionCard>

          {/* ---------- 详情 ---------- */}
          {detail && (
            <SectionCard
              title={`收款单详情 · ${detail.receipt.receiptNo}`}
              description={`${receiptSourceLabel(detail.receipt.source)} · ${detail.receipt.payerName}`}
              extra={
                <Button size="sm" variant="secondary" onClick={() => setDetail(null)}>
                  关闭
                </Button>
              }
            >
              <div className="grid gap-4 md:grid-cols-2">
                <div className="space-y-1 text-sm">
                  <p>
                    收款金额：
                    {formatReceiptMoney(detail.receipt.amount, detail.receipt.currency)}
                  </p>
                  <p>已核销：{formatReceiptAmount(detail.receipt.settledAmount)}</p>
                  <p>
                    未核销余额：
                    <Badge tone="warning">
                      {formatReceiptAmount(detail.receipt.unsettledAmount)}
                    </Badge>
                  </p>
                  <p>
                    预收：
                    {detail.receipt.advanceFlag ? (
                      <Badge tone="warning">是（还没有对应应收的资金）</Badge>
                    ) : (
                      '否'
                    )}
                  </p>
                  <p>到账时间：{formatReceivedAt(detail.receipt.receivedAt)}</p>
                  <p>资金账户：{fundAccountLabelHint(detail.receipt)}</p>
                  <p>
                    受管资金账户引用：
                    {detail.receipt.fundAccountRef ?? '未登记（12.7.1.1 未交付）'}
                  </p>
                  <p>结算单号：{detail.receipt.settlementNo ?? '未登记（12.3.1.1 未交付）'}</p>
                  <p>
                    状态：
                    <Badge tone={receiptStatusTone(detail.receipt.status)}>
                      {receiptStatusLabel(detail.receipt.status)}
                    </Badge>
                  </p>
                  {detail.receipt.note && <p>备注：{detail.receipt.note}</p>}
                </div>

                <div>
                  <h4 className="text-label-13 mb-2 font-semibold">预留核销关系</h4>
                  <p className="text-muted-foreground mb-2 text-copy-12">
                    ⚠️ **预留不是核销**：它只登记「这笔钱打算核销到哪几笔应收」，
                    **不减少**未核销余额；实际核销由 12.2.1.2 承担。
                  </p>
                  {detail.allocations.length === 0 ? (
                    <EmptyState
                      title="还没有预留核销关系"
                      description={
                        detail.receipt.advanceFlag
                          ? '这是一笔预收（还没有对应应收），所以不挂预留关系。'
                          : '登记一笔预留核销，这笔钱就可以被核销到多笔应收上（合并回款）。'
                      }
                    />
                  ) : (
                    <table className="text-copy-13 w-full text-left">
                      <thead className="text-muted-foreground">
                        <tr>
                          <th className="py-1">应收单</th>
                          <th className="py-1">预留金额</th>
                          <th className="py-1">状态</th>
                          <th className="py-1">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {detail.allocations.map((a) => (
                          <tr key={a.id} className="border-border border-t">
                            <td className="py-1">{a.receivableNo}</td>
                            <td className="py-1">{formatReceiptAmount(a.reservedAmount)}</td>
                            <td className="py-1">
                              <Badge tone={a.status === 'RESERVED' ? 'warning' : 'neutral'}>
                                {allocationStatusLabel(a.status)}
                              </Badge>
                            </td>
                            <td className="py-1">
                              {a.status === 'RESERVED' && (
                                <Button
                                  size="sm"
                                  variant="ghost"
                                  onClick={() => void doRelease(a)}
                                >
                                  释放
                                </Button>
                              )}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  )}

                  {!detail.receipt.advanceFlag && detail.receipt.status !== 'VOID' && (
                    <div className="mt-3">
                      <div className="grid gap-2 md:grid-cols-2">
                        <select
                          className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                          value={reserveReceivableId}
                          onChange={(e) => setReserveReceivableId(e.target.value)}
                        >
                          <option value="">选择要预留核销的应收单…</option>
                          {selectableReceivables.map((r) => (
                            <option key={r.id} value={r.id}>
                              {r.receivableNo} · {r.currency} · 未核销 {r.openAmount.toFixed(4)}
                            </option>
                          ))}
                        </select>
                        <Input
                          placeholder="预留金额"
                          value={reserveAmount}
                          onChange={(e) => setReserveAmount(e.target.value)}
                        />
                      </div>
                      {selectableReceivables.length === 0 && (
                        <p className="text-muted-foreground mt-1 text-copy-12">
                          没有与本笔收款**同币种**的待核销应收（跨币种核销是硬纪律）。
                        </p>
                      )}
                      <div className="mt-2">
                        <Button size="sm" onClick={() => void doReserve()}>
                          登记预留核销
                        </Button>
                      </div>
                    </div>
                  )}
                </div>
              </div>

              <h4 className="text-label-13 mb-2 mt-5 font-semibold">事件流</h4>
              {detail.events.length === 0 ? (
                <EmptyState title="没有事件" />
              ) : (
                <ul className="space-y-1 text-sm">
                  {detail.events.map((e) => (
                    <li key={e.id}>
                      <span className="text-muted-foreground">
                        {formatReceivedAt(e.occurredAt)}
                      </span>{' '}
                      · {receiptEventLabel(e.eventType)}
                      {e.reason ? ` · ${e.reason}` : ''}
                    </li>
                  ))}
                </ul>
              )}
            </SectionCard>
          )}

          {/* ---------- 手工登记 ---------- */}
          <SectionCard
            title="手工登记收款单"
            description="⚠️ 没有「平台回款」入口：上游 12.3.1 结算未交付（归属模块 12.3.1.1）。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={entrySource}
                onChange={(e) => setEntrySource(e.target.value)}
              >
                <option value="MANUAL">人工登记</option>
                <option value="BANK_TRANSFER">银行到账</option>
                <option value="PAYMENT_GATEWAY">第三方支付到账</option>
                <option value="OTHER">其他收入</option>
              </select>
              <Input
                placeholder="币种（USD / EUR / CNY …）"
                value={entryCurrency}
                onChange={(e) => setEntryCurrency(e.target.value)}
              />
              <Input
                placeholder="金额"
                value={entryAmount}
                onChange={(e) => setEntryAmount(e.target.value)}
              />
              <Input
                type="datetime-local"
                value={entryReceivedAt}
                onChange={(e) => setEntryReceivedAt(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={entryPayerType}
                onChange={(e) => setEntryPayerType(e.target.value)}
              >
                <option value="CUSTOMER">付款方：客户</option>
                <option value="PLATFORM">付款方：平台</option>
                <option value="PARTNER">付款方：合作方</option>
                <option value="OTHER">付款方：其他</option>
              </select>
              <Input
                placeholder="付款方名称"
                value={entryPayerName}
                onChange={(e) => setEntryPayerName(e.target.value)}
              />
              <Input
                placeholder="资金账户标识（自由文本，不是受管资金账户）"
                value={entryAccountLabel}
                onChange={(e) => setEntryAccountLabel(e.target.value)}
              />
              <Input
                placeholder="备注"
                value={entryNote}
                onChange={(e) => setEntryNote(e.target.value)}
              />
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={entryAdvance}
                  onChange={(e) => setEntryAdvance(e.target.checked)}
                />
                预收（还没有对应应收的资金）
              </label>
            </div>
            <div className="mt-3">
              <Button onClick={() => void submitManual()}>登记收款单</Button>
            </div>
          </SectionCard>

          {/* ---------- 到账流水导入 ---------- */}
          <SectionCard
            title="到账流水导入"
            description="CSV 表头 / JSON 键：source_reference、amount、currency、received_at（必需），payer_name、payer_type、fund_account_label、note（可选）。幂等键 = 来源系统 + 来源流水号。"
          >
            <div className="grid gap-3 md:grid-cols-3">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={importSource}
                onChange={(e) => setImportSource(e.target.value)}
              >
                <option value="BANK_TRANSFER">来源：银行到账</option>
                <option value="PAYMENT_GATEWAY">来源：第三方支付到账</option>
                <option value="MANUAL">来源：人工登记</option>
                <option value="OTHER">来源：其他收入</option>
              </select>
              <Input
                placeholder="来源系统（bank / paypal / stripe / pingpong）"
                value={importSourceSystem}
                onChange={(e) => setImportSourceSystem(e.target.value)}
              />
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={importFormat}
                onChange={(e) => setImportFormat(e.target.value)}
              >
                <option value="CSV">格式：CSV</option>
                <option value="JSON">格式：JSON</option>
              </select>
            </div>
            <textarea
              className="border-border bg-background mt-3 min-h-32 w-full rounded-md border p-3 font-mono text-sm"
              placeholder={
                importFormat === 'CSV'
                  ? 'source_reference,amount,currency,received_at,payer_name\nBANK-001,1500.00,USD,2026-10-01T08:30:00+08:00,ACME Ltd'
                  : '{"rows":[{"source_reference":"PP-9","amount":"99.99","currency":"USD","received_at":"2026-10-01T08:30:00+08:00"}]}'
              }
              value={importContent}
              onChange={(e) => setImportContent(e.target.value)}
            />
            <p className="text-muted-foreground mt-1 text-copy-12">
              ⚠️ 到账时间必须带时区偏移（如 2026-10-01T08:30:00+08:00 或 ...Z）：没有偏移的行会被跳过并逐条说明 ——
              默默按本地时区补齐会让到账时间偏 8 小时。
            </p>
            <div className="mt-3">
              <Button onClick={() => void runImport()}>
                <Download className="mr-2 h-4 w-4" />
                导入到账流水
              </Button>
            </div>
          </SectionCard>

          {/* ---------- 能力边界 ---------- */}
          {caps && (
            <SectionCard title="能力边界" description="哪些上游真接通、哪些还没交付、归谁。">
              <ul className="space-y-2 text-sm">
                {caps.capabilities.map((c) => (
                  <li key={`${c.module}-${c.name}`} className="flex items-start gap-2">
                    <Badge tone={c.available ? 'success' : 'warning'}>
                      {c.available ? '可用' : '未接入'}
                    </Badge>
                    <span>
                      <span className="font-medium">
                        {c.module} {c.name}
                      </span>
                      {c.ownerModule && (
                        <span className="text-muted-foreground"> （归属 {c.ownerModule}）</span>
                      )}
                      <div className="text-muted-foreground">{c.detail}</div>
                    </span>
                  </li>
                ))}
              </ul>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}
