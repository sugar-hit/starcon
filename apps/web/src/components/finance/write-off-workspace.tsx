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
  FinanceWriteOffAdvanceBalance,
  FinanceWriteOffAdvanceRow,
  FinanceWriteOffAllocationInput,
  FinanceWriteOffCapabilities,
  FinanceWriteOffDetail,
  FinanceWriteOffMatchCandidate,
  FinanceWriteOffOptions,
  FinanceWriteOffPage,
  FinanceWriteOffPending,
} from '../../../../../packages/api-contracts/src/index';
import {
  advanceBalanceHint,
  advanceStatusLabel,
  allocationTargetLabel,
  allocationTypeLabel,
  autoMatchWriteOff,
  createWriteOff,
  fetchWriteOff,
  fetchWriteOffAdvanceBalance,
  fetchWriteOffAdvanceRows,
  fetchWriteOffCapabilities,
  fetchWriteOffMatchCandidates,
  fetchWriteOffOptions,
  fetchWriteOffPending,
  fetchWriteOffs,
  formatOccurredAt,
  formatWriteOffAmount,
  formatWriteOffMoney,
  matchConditionHint,
  releaseWriteOffAdvance,
  reverseWriteOff,
  writeOffEventLabel,
  writeOffModeLabel,
  writeOffStatusLabel,
  writeOffStatusTone,
} from './write-off-model';

/**
 * 12.2.1.2 核销。
 *
 * <p>蓝图 12-finance.md：「把收款核销到应收：自动匹配（金额+订单号+日期）或人工指定，
 * 支持部分核销、合并核销、预收挂账、核销撤销。」输出：「核销记录 + 应收余额更新 + 预收余额。」
 * 加粗句：「<b>核销必须精确到明细行</b>，只核销到订单头无法处理部分回款与折让。」
 *
 * <p><b>页面围绕那条加粗句组织</b>：待核销视图里能选的是**应收明细行**，
 * 每一行都显示行号、订单号与该行的未核销余额；核销记录详情里同样显示行号与订单号。
 *
 * <p><b>页面刻意没有的东西</b>：
 *
 * <ol>
 *   <li><b>「回款口径 / 利润报表」入口</b>：下游 13 利润**未交付**，
 *       能力声明里如实报出归属模块 13 —— 放一个能点的入口就是骗人。</li>
 *   <li><b>「坏账核销」入口</b>：12.1.1.3 **未交付**，而且它走「作废 + 新单」那条路，
 *       与本模块的行级核销**不是同一件事**。</li>
 * </ol>
 *
 * <p>加载 / 空 / 错误三态齐全；空态区分「筛选无结果」与「还没有核销记录」。
 */
export function WriteOffWorkspace() {
  const [caps, setCaps] = useState<FinanceWriteOffCapabilities | null>(null);
  const [options, setOptions] = useState<FinanceWriteOffOptions | null>(null);
  const [page, setPage] = useState<FinanceWriteOffPage | null>(null);
  const [pending, setPending] = useState<FinanceWriteOffPending | null>(null);
  const [balance, setBalance] = useState<FinanceWriteOffAdvanceBalance | null>(null);
  const [advanceRows, setAdvanceRows] = useState<FinanceWriteOffAdvanceRow[]>([]);
  const [detail, setDetail] = useState<FinanceWriteOffDetail | null>(null);
  const [candidates, setCandidates] = useState<FinanceWriteOffMatchCandidate[]>([]);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');

  // 筛选
  const [status, setStatus] = useState('');
  const [mode, setMode] = useState('');
  const [currency, setCurrency] = useState('');
  const [keyword, setKeyword] = useState('');
  const [currentPage, setCurrentPage] = useState(0);

  // 人工指定核销
  const [manualReceiptId, setManualReceiptId] = useState('');
  const [manualLineId, setManualLineId] = useState('');
  const [manualAmount, setManualAmount] = useState('');
  const [manualNote, setManualNote] = useState('');

  // 自动匹配
  const [autoReceiptId, setAutoReceiptId] = useState('');
  const [autoOrderNo, setAutoOrderNo] = useState('');
  const [autoAmount, setAutoAmount] = useState('');
  const [autoTolerance, setAutoTolerance] = useState('0');

  /**
   * 筛选参数。
   *
   * ⚠️ `exactOptionalPropertyTypes` 开着（AGENTS.md）：**没填的筛选项不能显式给 undefined** ——
   *   所以这里按「填了才建键」处理，而不是一律塞 undefined。
   */
  const params = {
    ...(status ? { status } : {}),
    ...(mode ? { mode } : {}),
    ...(currency ? { currency } : {}),
    ...(keyword ? { keyword } : {}),
    page: currentPage,
    size: 50,
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [c, o, p, pendingView, b, advances] = await Promise.all([
        fetchWriteOffCapabilities(),
        fetchWriteOffOptions(),
        fetchWriteOffs(params),
        fetchWriteOffPending(),
        fetchWriteOffAdvanceBalance(),
        fetchWriteOffAdvanceRows(),
      ]);
      setCaps(c);
      setOptions(o);
      setPage(p);
      setPending(pendingView);
      setBalance(b);
      setAdvanceRows(advances);
    } catch (e) {
      setError(e instanceof Error ? e.message : '核销数据加载失败');
    } finally {
      setLoading(false);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status, mode, currency, keyword, currentPage]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 选中的收款单（人工核销与自动匹配都用它）。 */
  const selectedReceipt = useMemo(
    () => pending?.receipts.find((r) => r.id === manualReceiptId) ?? null,
    [pending, manualReceiptId],
  );

  /** 选中的应收明细行（**核销精确到明细行**：选的是行，不是单头）。 */
  const selectedLine = useMemo(
    () => pending?.lines.find((l) => l.id === manualLineId) ?? null,
    [pending, manualLineId],
  );

  /** 可选的应收明细行：只列与所选收款**同币种**的（跨币种核销是硬纪律）。 */
  const selectableLines = useMemo(() => {
    if (!pending || !selectedReceipt) return [];
    return pending.lines.filter((l) => l.currency === selectedReceipt.currency);
  }, [pending, selectedReceipt]);

  async function openDetail(id: string) {
    setActionError('');
    try {
      setDetail(await fetchWriteOff(id));
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '核销记录详情加载失败');
    }
  }

  async function submitManual() {
    setActionError('');
    setNotice('');
    if (!manualReceiptId) {
      setActionError('请选择要核销的收款单');
      return;
    }
    if (!manualLineId) {
      setActionError('请选择要核销的应收明细行（核销精确到明细行，不是单头）');
      return;
    }
    const amount = Number(manualAmount);
    if (!manualAmount || Number.isNaN(amount) || amount <= 0) {
      setActionError('核销金额必须大于 0');
      return;
    }
    const allocation: FinanceWriteOffAllocationInput = {
      type: 'SETTLEMENT',
      receivableLineId: manualLineId,
      amount,
      note: manualNote.trim() || null,
    };
    try {
      const created = await createWriteOff({
        receiptId: manualReceiptId,
        allocations: [allocation],
        note: manualNote.trim() || null,
        occurredAt: null,
      });
      setNotice(
        `已核销 ${formatWriteOffMoney(created.writeOff.totalAmount, created.writeOff.currency)} —— ` +
          `金额落在**应收明细行**上（部分核销）`,
      );
      setManualAmount('');
      setManualNote('');
      setManualLineId('');
      await load();
      await openDetail(created.writeOff.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '核销失败');
    }
  }

  async function previewMatch() {
    setActionError('');
    setNotice('');
    if (!autoReceiptId) {
      setActionError('请选择要核销的收款单');
      return;
    }
    if (!autoOrderNo.trim()) {
      setActionError('请写明订单号：自动匹配是「金额 + 订单号 + 日期」三者对上');
      return;
    }
    try {
      const found = await fetchWriteOffMatchCandidates({
        receiptId: autoReceiptId,
        orderNo: autoOrderNo.trim(),
        ...(autoAmount ? { amount: Number(autoAmount) } : {}),
        ...(autoTolerance ? { dateToleranceDays: Number(autoTolerance) } : {}),
      });
      setCandidates(found);
      setNotice(
        found.length === 0
          ? '没有命中任何明细行 —— 金额相同**不构成**匹配，三个条件要同时成立'
          : `命中 ${found.length} 条明细行`,
      );
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '自动匹配候选查询失败');
    }
  }

  async function runAutoMatch() {
    setActionError('');
    setNotice('');
    if (!autoReceiptId) {
      setActionError('请选择要核销的收款单');
      return;
    }
    if (!autoOrderNo.trim()) {
      setActionError('请写明订单号');
      return;
    }
    try {
      const result = await autoMatchWriteOff({
        receiptId: autoReceiptId,
        orderNo: autoOrderNo.trim(),
        amount: autoAmount ? Number(autoAmount) : null,
        matchDate: null,
        dateToleranceDays: autoTolerance ? Number(autoTolerance) : null,
        note: '自动匹配',
      });
      setNotice(
        `已自动匹配核销 ${formatWriteOffMoney(result.writeOff.totalAmount, result.writeOff.currency)}` +
          `（${result.candidates.length} 条明细行）`,
      );
      setCandidates([]);
      await load();
      await openDetail(result.writeOff.id);
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '自动匹配核销失败');
    }
  }

  async function doReverse(row: { id: string; writeOffNo: string }) {
    setActionError('');
    setNotice('');
    const reason = window.prompt('撤销理由（必填）：', '');
    if (!reason || !reason.trim()) return;
    try {
      await reverseWriteOff(row.id, reason.trim());
      setNotice(`已撤销 ${row.writeOffNo} —— 明细行的已核销金额已退回`);
      setDetail(null);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '核销撤销失败');
    }
  }

  async function doReleaseAdvance(row: FinanceWriteOffAdvanceRow) {
    setActionError('');
    setNotice('');
    try {
      await releaseWriteOffAdvance(row.id);
      setNotice(`已释放 ${row.receiptNo} 上的预收挂账 ${formatWriteOffMoney(row.amount, row.currency)}`);
      await load();
    } catch (e) {
      setActionError(e instanceof Error ? e.message : '释放预收挂账失败');
    }
  }

  const hasFilters = Boolean(status || mode || currency || keyword);

  return (
    <div className="space-y-6 p-6">
      <PageHeader
        title="核销"
        description="把收款核销到应收明细行 · 部分核销 / 合并核销 / 预收挂账 / 核销撤销"
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
            <StatCard
              label="核销记录"
              value={page?.total ?? 0}
              unit="笔"
              comparisonLabel="按当前筛选条件"
            />
            <StatCard
              label="预收余额（挂账）"
              value={balance?.byCurrency.reduce((sum, c) => sum + c.parkedAmount, 0) ?? 0}
              unit="笔"
              comparisonLabel="按币种分开，不跨币种相加"
            />
            <StatCard
              label="挂账笔数"
              value={balance?.ledgerCount ?? 0}
              unit="笔"
              comparisonLabel="还挂在预收账上的"
            />
            <StatCard
              label="待核销应收行"
              value={pending?.lines.length ?? 0}
              unit="行"
              comparisonLabel="核销精确到明细行"
            />
          </div>

          {/* ---------- 上游缺口如实标注 ---------- */}
          {caps && caps.capabilities.some((c) => !c.available) && (
            <SectionCard
              title="能力声明里的缺口"
              description="没交付的模块**如实标出来**，不放能点的假入口。"
            >
              <ul className="space-y-2 text-sm">
                {caps.capabilities
                  .filter((c) => !c.available)
                  .map((c) => (
                    <li key={`${c.module}-${c.name}`} className="border-border border-t pt-2">
                      <Badge tone="warning">{c.module}</Badge>{' '}
                      <span className="font-medium">{c.name}</span>
                      <span className="text-muted-foreground">
                        （归属模块 {c.ownerModule || '—'}）
                      </span>
                      <div className="text-muted-foreground mt-1 text-xs">{c.detail}</div>
                    </li>
                  ))}
              </ul>
            </SectionCard>
          )}

          {/* ---------- 预收余额（12.2.1.2 的输出之一） ---------- */}
          <SectionCard
            title="预收余额"
            description="⚠️ 口径：预收 ⊂ 未核销余额（挂账**不**减未核销余额）。两段口径互不重复计数，不同币种**不**合并。"
          >
            {!balance || balance.byCurrency.length === 0 ? (
              <EmptyState
                title="还没有预收余额"
                description="把「钱到了但还没有对应应收」的部分做一次预收挂账，这里就会按币种显示预收余额。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">币种</th>
                    <th className="py-2">挂账预收</th>
                    <th className="py-2">预收标记</th>
                    <th className="py-2">合计</th>
                    <th className="py-2">笔数</th>
                  </tr>
                </thead>
                <tbody>
                  {balance.byCurrency.map((c) => (
                    <tr key={c.currency} className="border-border border-t">
                      <td className="py-2 font-medium">{c.currency}</td>
                      <td className="py-2">{formatWriteOffAmount(c.parkedAmount)}</td>
                      <td className="py-2">{formatWriteOffAmount(c.flaggedAmount)}</td>
                      <td className="py-2 font-medium">{formatWriteOffAmount(c.totalAmount)}</td>
                      <td className="py-2">{c.count}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 待核销：收款单 ---------- */}
          <SectionCard
            title="待核销的收款"
            description="一笔收款可以核销多笔应收（**合并核销**）。⚠️ 挂着预收的钱是**专用**的，要动用先释放挂账。"
          >
            {!pending || pending.receipts.length === 0 ? (
              <EmptyState
                title="没有待核销的收款"
                description="先在收款单模块登记一笔到账（并留出未核销余额），这里才会有可核销的收款。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">收款单号</th>
                    <th className="py-2">付款方</th>
                    <th className="py-2">币种</th>
                    <th className="py-2">未核销余额</th>
                    <th className="py-2">其中挂预收</th>
                    <th className="py-2">可核销</th>
                    <th className="py-2">到账时间</th>
                  </tr>
                </thead>
                <tbody>
                  {pending.receipts.map((r) => (
                    <tr key={r.id} className="border-border border-t">
                      <td className="py-2 font-medium">{r.receiptNo}</td>
                      <td className="py-2">{r.payerName || '—'}</td>
                      <td className="py-2">{r.currency}</td>
                      <td className="py-2">{formatWriteOffAmount(r.unsettledAmount)}</td>
                      <td className="py-2">
                        {r.openAdvanceAmount > 0 ? (
                          <Badge tone="warning">{formatWriteOffAmount(r.openAdvanceAmount)}</Badge>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                      <td className="py-2 font-medium">{formatWriteOffAmount(r.availableAmount)}</td>
                      <td className="py-2">{formatOccurredAt(r.receivedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 待核销：应收明细行 ---------- */}
          <SectionCard
            title="待核销的应收明细行"
            description="⚠️ **核销必须精确到明细行**：只核销到订单头无法处理部分回款与折让，所以选的是行。"
          >
            {!pending || pending.lines.length === 0 ? (
              <EmptyState
                title="没有待核销的应收明细行"
                description="在应收单模块确认一张应收（并留有未核销的行），这里才会有可核销的明细行。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">应收单</th>
                    <th className="py-2">行</th>
                    <th className="py-2">订单号</th>
                    <th className="py-2">币种</th>
                    <th className="py-2">行金额</th>
                    <th className="py-2">未核销</th>
                    <th className="py-2">到期日</th>
                  </tr>
                </thead>
                <tbody>
                  {pending.lines.map((l) => (
                    <tr key={l.id} className="border-border border-t">
                      <td className="py-2">{l.receivableNo}</td>
                      <td className="py-2">第 {l.lineNo} 行</td>
                      <td className="py-2">{l.orderNo || '—'}</td>
                      <td className="py-2">{l.currency}</td>
                      <td className="py-2">{formatWriteOffAmount(l.amount)}</td>
                      <td className="py-2 font-medium">{formatWriteOffAmount(l.openAmount)}</td>
                      <td className="py-2">{l.dueDate}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 人工指定核销 ---------- */}
          <SectionCard
            title="人工指定核销"
            description="选一笔收款 + 一条应收明细行 + 金额。金额小于该行未核销余额 ⇒ **部分核销**；一次选多行 ⇒ **合并核销**。"
          >
            <div className="grid gap-3 md:grid-cols-4">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={manualReceiptId}
                onChange={(e) => {
                  setManualReceiptId(e.target.value);
                  setManualLineId('');
                }}
              >
                <option value="">选择收款单</option>
                {(pending?.receipts ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.receiptNo} · {r.currency} · 可核销 {formatWriteOffAmount(r.availableAmount)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={manualLineId}
                onChange={(e) => setManualLineId(e.target.value)}
                disabled={!selectedReceipt}
              >
                <option value="">选择应收明细行</option>
                {selectableLines.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.receivableNo} 第 {l.lineNo} 行 · {l.orderNo || '无订单'} · 未核销{' '}
                    {formatWriteOffAmount(l.openAmount)}
                  </option>
                ))}
              </select>
              <Input
                placeholder="核销金额"
                value={manualAmount}
                onChange={(e) => setManualAmount(e.target.value)}
              />
              <Button onClick={() => void submitManual()}>核销</Button>
            </div>
            <Input
              className="mt-3"
              placeholder="备注（可选）"
              value={manualNote}
              onChange={(e) => setManualNote(e.target.value)}
            />
            {selectedLine && (
              <p className="text-muted-foreground mt-2 text-xs">
                目标：{selectedLine.receivableNo} 第 {selectedLine.lineNo} 行 · 订单{' '}
                {selectedLine.orderNo || '无'} · 该行未核销余额{' '}
                {formatWriteOffMoney(selectedLine.openAmount, selectedLine.currency)}
              </p>
            )}
          </SectionCard>

          {/* ---------- 自动匹配 ---------- */}
          <SectionCard
            title="自动匹配核销"
            description="⚠️ 三个条件**缺一不可**：订单号完全相等 + 金额对上 + 日期在窗口内。**金额相同不构成匹配**。"
          >
            <div className="grid gap-3 md:grid-cols-5">
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={autoReceiptId}
                onChange={(e) => setAutoReceiptId(e.target.value)}
              >
                <option value="">选择收款单</option>
                {(pending?.receipts ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.receiptNo} · {r.currency}
                  </option>
                ))}
              </select>
              <Input
                placeholder="订单号（必填）"
                value={autoOrderNo}
                onChange={(e) => setAutoOrderNo(e.target.value)}
              />
              <Input
                placeholder="目标金额（留空＝收款未核销余额）"
                value={autoAmount}
                onChange={(e) => setAutoAmount(e.target.value)}
              />
              <Input
                placeholder="日期容差天数（0＝必须同日）"
                value={autoTolerance}
                onChange={(e) => setAutoTolerance(e.target.value)}
              />
              <div className="flex gap-2">
                <Button variant="secondary" onClick={() => void previewMatch()}>
                  试匹配
                </Button>
                <Button onClick={() => void runAutoMatch()}>按匹配核销</Button>
              </div>
            </div>
            {candidates.length > 0 && (
              <ul className="mt-3 space-y-2 text-sm">
                {candidates.map((c) => (
                  <li key={c.receivableLineId} className="border-border border-t pt-2">
                    <span className="font-medium">
                      {c.receivableNo} 第 {c.lineNo} 行 · {c.orderNo || '无订单'}
                    </span>
                    <span className="text-muted-foreground">
                      （{formatWriteOffMoney(c.matchedAmount, c.currency)}）
                    </span>
                    <div className="text-muted-foreground mt-1 text-xs">
                      {matchConditionHint(c)}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          {/* ---------- 预收挂账行 ---------- */}
          <SectionCard
            title="预收挂账"
            description="挂着的钱是**专用**的：核销时按「未核销余额 − 未释放挂账额」封顶，要动用请先释放。"
          >
            {advanceRows.length === 0 ? (
              <EmptyState
                title="还没有预收挂账"
                description="在收款单上做一次预收挂账（钱到了但还没有对应应收），这里会列出挂账行。"
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">收款单</th>
                    <th className="py-2">核销记录</th>
                    <th className="py-2">币种</th>
                    <th className="py-2">挂账金额</th>
                    <th className="py-2">状态</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {advanceRows.map((r) => (
                    <tr key={r.id} className="border-border border-t">
                      <td className="py-2">{r.receiptNo}</td>
                      <td className="py-2">{r.writeOffNo}</td>
                      <td className="py-2">{r.currency}</td>
                      <td className="py-2 font-medium">{formatWriteOffAmount(r.amount)}</td>
                      <td className="py-2">
                        <Badge tone={r.status === 'OPEN' ? 'warning' : 'neutral'}>
                          {advanceStatusLabel(r.status)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {r.status === 'OPEN' ? (
                          <Button variant="secondary" onClick={() => void doReleaseAdvance(r)}>
                            释放
                          </Button>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 核销记录列表 ---------- */}
          <SectionCard title="核销记录" description="点一行看分配明细（精确到明细行）与事件流。">
            <div className="mb-4 grid gap-3 md:grid-cols-4">
              <Input
                placeholder="关键字：核销单号 / 备注 / 收款单号 / 付款方"
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
                    {writeOffStatusLabel(s)}
                  </option>
                ))}
              </select>
              <select
                className="border-border bg-background h-9 rounded-md border px-3 text-sm"
                value={mode}
                onChange={(e) => {
                  setMode(e.target.value);
                  setCurrentPage(0);
                }}
              >
                <option value="">全部方式</option>
                {(options?.modes ?? []).map((m) => (
                  <option key={m} value={m}>
                    {writeOffModeLabel(m)}
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
            </div>

            {!page || page.items.length === 0 ? (
              <EmptyState
                title={hasFilters ? '没有符合筛选条件的核销记录' : '还没有核销记录'}
                description={
                  hasFilters
                    ? '把筛选条件放宽一点再试。'
                    : '在上面选一笔收款和一条应收明细行，做第一次核销。'
                }
              />
            ) : (
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">核销单号</th>
                    <th className="py-2">收款单</th>
                    <th className="py-2">方式</th>
                    <th className="py-2">核销</th>
                    <th className="py-2">预收挂账</th>
                    <th className="py-2">合计</th>
                    <th className="py-2">核销时刻</th>
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
                          {row.writeOffNo}
                        </button>
                      </td>
                      <td className="py-2">{row.receiptNo}</td>
                      <td className="py-2">{writeOffModeLabel(row.mode)}</td>
                      <td className="py-2">{formatWriteOffAmount(row.settlementAmount)}</td>
                      <td className="py-2">{formatWriteOffAmount(row.advanceAmount)}</td>
                      <td className="py-2 font-medium">
                        {formatWriteOffMoney(row.totalAmount, row.currency)}
                      </td>
                      <td className="py-2">{formatOccurredAt(row.occurredAt)}</td>
                      <td className="py-2">
                        <Badge tone={writeOffStatusTone(row.status)}>
                          {writeOffStatusLabel(row.status)}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {row.status === 'POSTED' ? (
                          <Button variant="secondary" onClick={() => void doReverse(row)}>
                            撤销
                          </Button>
                        ) : (
                          <span className="text-muted-foreground">—</span>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </SectionCard>

          {/* ---------- 核销记录详情 ---------- */}
          {detail && (
            <SectionCard
              title={`核销记录 ${detail.writeOff.writeOffNo}`}
              description="分配明细**精确到应收明细行** —— 核销到了哪一行、金额多少，这里逐条列出。"
            >
              <table className="text-copy-13 w-full text-left">
                <thead className="text-muted-foreground">
                  <tr>
                    <th className="py-2">类型</th>
                    <th className="py-2">目标</th>
                    <th className="py-2">金额</th>
                    <th className="py-2">状态</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.allocations.map((a) => (
                    <tr key={a.id} className="border-border border-t">
                      <td className="py-2">{allocationTypeLabel(a.allocationType)}</td>
                      <td className="py-2">{allocationTargetLabel(a)}</td>
                      <td className="py-2 font-medium">{formatWriteOffAmount(a.amount)}</td>
                      <td className="py-2">
                        <Badge tone={a.status === 'POSTED' ? 'success' : 'neutral'}>
                          {a.status === 'POSTED' ? '已核销' : '已撤销'}
                        </Badge>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <h3 className="mt-4 mb-2 text-sm font-medium">事件流</h3>
              <ul className="space-y-1 text-sm">
                {detail.events.map((e) => (
                  <li key={e.id} className="text-muted-foreground">
                    {formatOccurredAt(e.occurredAt)} · {writeOffEventLabel(e.eventType)}
                    {e.reason ? ` · ${e.reason}` : ''}
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
