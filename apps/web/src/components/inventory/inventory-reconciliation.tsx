'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, RefreshCw, Scale, ShieldAlert, ShieldCheck, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  LegalEntityRef,
  ReconciliationFindingPage,
  ReconciliationResult,
  ReconciliationRun,
  ReconciliationRunPage,
  ReconciliationSuspectWindow,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  diffText,
  errorText,
  fetchLegalEntities,
  fetchReconciliationFindings,
  fetchReconciliationRun,
  fetchReconciliationRuns,
  fetchWarehouses,
  qty,
  runReconciliation,
  scopeTypeLabels,
  severityLabels,
  statusLabels,
  suspectCategoryLabels,
  windowLabel,
  withOffset,
} from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.1.2.2 账实核对工作区。
 *
 * <p>蓝图：「定期核对『按流水累计的余额』与『当前余额快照』是否一致，差异时列出可疑区间
 * （漏记、重复记、并发丢失）」，输出「核对结果 + 差异明细 + 可疑流水定位」。
 *
 * <p>六条界面纪律：
 *
 * <ul>
 *   <li>**差异为零也是一个结果，而且要占版面**。把"账实一致"压成一行灰字，
 *       等于告诉运营"没事就别来"—— 而"核过了、一致"本身就是需要留痕的结论。
 *   <li>**口径公式摆在页面上**：期初 + 区间内 + 区间后 = 应等于快照。
 *       数字对不上时，第一件要让人看懂的是"这几个数是怎么加起来的"。
 *   <li>**可疑流水必须能点进台账**。定位的终点是一笔具体的业务，
 *       点一下就跳到那笔流水的分录明细，页面自己闭环不跳转的定位等于半成品。
 *   <li>**漏记允许"指不出哪一笔"**。它的结论就是"没有任何流水能解释这个余额"，
 *       界面必须如实显示"无对应流水"，不能随便指一笔充数。
 *   <li>**区间后的流水单独提示**。它不为 0 就说明有错期录入或事后补记，值得单独说一句。
 *   <li>**核对记录不可改**：历史批次只读。要作废就重跑一次，新旧并存。
 * </ul>
 */
export function InventoryReconciliation() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 核对要落一份不可变的结论，所以发起核对是写权限；只看不需要
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [history, setHistory] = useState<ReconciliationRunPage | null>(null);
  const [result, setResult] = useState<ReconciliationResult | null>(null);
  const [discrepancies, setDiscrepancies] = useState<ReconciliationFindingPage | null>(null);
  const [suspects, setSuspects] = useState<ReconciliationFindingPage | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [entities, setEntities] = useState<LegalEntityRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [from, setFrom] = useState(() => daysAgo(30));
  const [to, setTo] = useState(() => daysAgo(0));
  const [scope, setScope] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [historyPage, setHistoryPage] = useState(0);
  const [discPage, setDiscPage] = useState(0);
  const [suspectPage, setSuspectPage] = useState(0);
  const [selectedRun, setSelectedRun] = useState<string | null>(null);

  const loadHistory = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const params: { page: number; size: number; status?: string } = {
        page: historyPage,
        size: 20,
      };
      if (statusFilter) params.status = statusFilter;
      setHistory(await fetchReconciliationRuns(params));
    } catch (e) {
      setError(errorText(e));
      setHistory(null);
    } finally {
      setLoading(false);
    }
  }, [readable, historyPage, statusFilter]);

  useEffect(() => {
    void loadHistory();
  }, [loadHistory]);

  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        const [wh, le] = await Promise.all([fetchWarehouses(), fetchLegalEntities()]);
        setWarehouses(wh);
        setEntities(le);
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [readable]);

  /** 打开某次核对：结论 + 两份发现各自真分页。 */
  const openRun = useCallback(
    async (runId: string) => {
      setError('');
      setNotice('');
      setSelectedRun(runId);
      setDiscPage(0);
      setSuspectPage(0);
      try {
        setResult(await fetchReconciliationRun(runId));
        const [disc, sus] = await Promise.all([
          fetchReconciliationFindings(runId, { kind: 'DISCREPANCY', page: 0, size: 50 }),
          fetchReconciliationFindings(runId, { kind: 'SUSPECT', page: 0, size: 50 }),
        ]);
        setDiscrepancies(disc);
        setSuspects(sus);
      } catch (e) {
        setError(errorText(e));
      }
    },
    [],
  );

  async function execute() {
    setError('');
    setNotice('');
    if (!from || !to) {
      setError('核对区间必须给出起止时间，否则这份记录事后无法复现');
      return;
    }
    setRunning(true);
    try {
      const params: { from: string; to: string; warehouseId?: string; legalEntityId?: string } = {
        from: withOffset(from),
        to: withOffset(to),
      };
      // 范围用 `维度:id` 编码：下拉里三个维度混在一个 select 里，靠前缀区分是哪一维。
      // 切分结果可能是 undefined（noUncheckedIndexedAccess），存在性检查后再赋给可选属性。
      const [scopeType, scopeId] = scope.split(':');
      if (scopeType === 'WAREHOUSE' && scopeId) params.warehouseId = scopeId;
      if (scopeType === 'LEGAL_ENTITY' && scopeId) params.legalEntityId = scopeId;
      const res = await runReconciliation(params);
      setResult(res);
      setSelectedRun(res.run.id);
      setDiscrepancies({ findings: res.discrepancies, total: res.discrepancies.length, page: 0, size: 50 });
      setSuspects({ findings: res.suspects, total: res.suspects.length, page: 0, size: 50 });
      setNotice(
        res.run.status === 'CONSISTENT'
          ? `核对完成：${res.run.scopeLabel} 账实一致，已留档本次结论`
          : `核对完成：${res.run.scopeLabel} 有 ${res.run.discrepancyCount} 条余额行对不上，已留档本次结论`,
      );
      await loadHistory();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setRunning(false);
    }
  }

  /** 翻某一类发现的页。合计恒为全量，翻页时数字不会变小。 */
  async function turnFindings(
    kind: 'DISCREPANCY' | 'SUSPECT',
    page: number,
  ): Promise<void> {
    if (!selectedRun) return;
    setError('');
    try {
      const next = await fetchReconciliationFindings(selectedRun, { kind, page, size: 50 });
      if (kind === 'DISCREPANCY') {
        setDiscrepancies(next);
        setDiscPage(page);
      } else {
        setSuspects(next);
        setSuspectPage(page);
      }
    } catch (e) {
      setError(errorText(e));
    }
  }

  if (!readable) {
    return (
      <EmptyState
        icon={<Scale className="h-6 w-6" />}
        title="没有库存核对的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const run = result?.run ?? null;

  return (
    <div className="space-y-6">
      <SectionCard
        title="发起核对"
        description="核对 = 按流水累计的余额 vs 当前余额快照。两个数都现算，差异不为零时会定位到具体的可疑流水。"
      >
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-5">
          <label className="space-y-1 text-sm">
            <span className="block font-medium">区间起（含）</span>
            <input
              type="datetime-local"
              className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={from}
              onChange={(e) => setFrom(e.target.value)}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">区间止（不含）</span>
            <input
              type="datetime-local"
              className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={to}
              onChange={(e) => setTo(e.target.value)}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">范围</span>
            <select
              className="w-full rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={scope}
              onChange={(e) => setScope(e.target.value)}
            >
              <option value="">全部余额行</option>
              <optgroup label="按仓库">
                {warehouses.map((w) => (
                  <option key={w.id} value={`WAREHOUSE:${w.id}`}>
                    {w.code} {w.name}
                  </option>
                ))}
              </optgroup>
              <optgroup label="按货权法人">
                {entities.map((l) => (
                  <option key={l.id} value={`LEGAL_ENTITY:${l.id}`}>
                    {l.code} {l.name}
                  </option>
                ))}
              </optgroup>
            </select>
          </label>
          <div className="flex items-end">
            <Button onClick={() => void execute()} disabled={running || !writable}>
              {running ? '核对中…' : '执行核对'}
            </Button>
          </div>
          <div className="flex items-end text-xs text-muted-foreground">
            {!writable ? '发起核对需要 warehouse.inventory.update 权限' : '结论会留档且不可修改'}
          </div>
        </div>
        {error && (
          <div className="mt-3 flex items-start gap-3 rounded-md border border-destructive/40 bg-destructive-soft p-4 text-sm text-destructive">
            <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
            <div className="space-y-2">
              <p>{error}</p>
              <Button variant="outline" size="sm" onClick={() => void loadHistory()}>
                重新加载
              </Button>
            </div>
          </div>
        )}
        {notice && (
          <div className="mt-3 rounded-md border border-success/40 bg-success-soft p-4 text-sm text-success">
            {notice}
          </div>
        )}
      </SectionCard>

      {run ? <Verdict run={run} /> : null}

      {run && result ? (
        <>
          <SuspectWindows windows={result.suspectWindows} />
          <DiscrepancyTable
            page={discrepancies}
            pageNumber={discPage}
            onTurn={(p) => void turnFindings('DISCREPANCY', p)}
          />
          <SuspectTable
            page={suspects}
            pageNumber={suspectPage}
            onTurn={(p) => void turnFindings('SUSPECT', p)}
          />
        </>
      ) : !loading ? (
        <EmptyState
          icon={<Scale className="h-5 w-5" />}
          title="还没有核对结果"
          description="设定区间后点「执行核对」。差异为零同样会留下一条可回查的记录。"
        />
      ) : null}

      <SectionCard
        title="历史核对记录"
        description="结论不可修改：查出来的差异要作废，只能重跑一次核对，新旧两批并存。"
        extra={
          <div className="flex items-center gap-2">
            <select
              className="rounded-md border border-control-border bg-card px-3 py-2 text-sm"
              value={statusFilter}
              onChange={(e) => {
                setStatusFilter(e.target.value);
                setHistoryPage(0);
              }}
            >
              <option value="">全部结论</option>
              <option value="CONSISTENT">账实一致</option>
              <option value="DISCREPANT">存在差异</option>
            </select>
            <Button
              variant="outline"
              size="sm"
              onClick={() => void loadHistory()}
              disabled={loading}
            >
              <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
              刷新
            </Button>
          </div>
        }
      >
        {history === null || history.runs.length === 0 ? (
          <EmptyState
            icon={<Scale className="h-5 w-5" />}
            title={loading ? '加载中' : '暂无核对记录'}
            description={loading ? '正在读取历史核对结论。' : '还没有发起过任何一次账实核对。'}
          />
        ) : (
          <>
            <table className="w-full text-sm">
              <thead className="text-left text-xs text-muted-foreground">
                <tr>
                  <th className="py-2">核对时间</th>
                  <th className="py-2">范围</th>
                  <th className="py-2">区间</th>
                  <th className="py-2">结论</th>
                  <th className="py-2">在手差</th>
                  <th className="py-2">差异行</th>
                  <th className="py-2">可疑线索</th>
                </tr>
              </thead>
              <tbody>
                {history.runs.map((r) => (
                  <tr
                    key={r.id}
                    className={`border-t border-border ${selectedRun === r.id ? 'bg-muted/50' : ''}`}
                  >
                    <td className="py-2">{formatMoment(r.createdAt)}</td>
                    <td className="py-2">
                      {r.scopeLabel}
                      <span className="ml-1 text-xs text-muted-foreground">
                        {scopeTypeLabels[r.scopeType] ?? r.scopeType}
                      </span>
                    </td>
                    <td className="py-2 text-xs text-muted-foreground">
                      {formatMoment(r.windowFrom)} ~ {formatMoment(r.windowTo)}
                    </td>
                    <td className="py-2">
                      <StatusPill status={r.status} />
                    </td>
                    <td className="py-2 font-mono">{diffText(r.onHandDiff)}</td>
                    <td className="py-2">{r.discrepancyCount}</td>
                    <td className="py-2">{r.suspectCount}</td>
                    <td className="py-2 text-right">
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => void openRun(r.id)}
                        aria-pressed={selectedRun === r.id}
                      >
                        查看
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <Pager
              total={history.total}
              page={history.page}
              size={history.size}
              onTurn={setHistoryPage}
            />
          </>
        )}
      </SectionCard>
    </div>
  );
}

/** 结论条。差异为零也占满一整块版面，并写清"核了什么"。 */
function Verdict({ run }: { run: ReconciliationRun }) {
  const consistent = run.status === 'CONSISTENT';
  return (
    <div
      className={`rounded-md border p-4 ${consistent ? 'border-success/40 bg-success-soft' : 'border-destructive/40 bg-destructive-soft'}`}
    >
      <div className="flex items-start gap-3">
        {consistent ? (
          <ShieldCheck className="mt-0.5 h-5 w-5 shrink-0 text-success" />
        ) : (
          <ShieldAlert className="mt-0.5 h-5 w-5 shrink-0 text-destructive" />
        )}
        <div className="min-w-0 space-y-2">
          <p className={`text-sm font-medium ${consistent ? 'text-success' : 'text-destructive'}`}>
            {statusLabels[run.status] ?? run.status} · {run.scopeLabel}
          </p>
          <p className="text-sm text-muted-foreground">{run.verdictNote}</p>
        </div>
      </div>
      <div className="mt-4 grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="核对余额行" value={run.checkedBalanceCount} polarity="neutral" />
        <StatCard
          label="区间内分录"
          value={run.checkedEntryCount}
          polarity="neutral"
          comparisonLabel={`${run.checkedTransactionCount} 笔业务`}
        />
        <StatCard
          label="在手差异（累计 − 快照）"
          value={diffText(run.onHandDiff)}
          polarity={run.onHandDiff === 0 ? 'neutral' : 'negative'}
        />
        <StatCard
          label="预占差异"
          value={diffText(run.reservedDiff)}
          polarity={run.reservedDiff === 0 ? 'neutral' : 'negative'}
        />
      </div>
      {/* 口径公式摆在页面上：数字对不上时先让人看懂这几个数是怎么加起来的 */}
      <div className="mt-3 grid gap-2 text-xs text-muted-foreground sm:grid-cols-2">
        <p>
          在手：期初 <span className="font-mono">{diffText(run.openingOnHand)}</span> + 区间内{' '}
          <span className="font-mono">{diffText(run.periodOnHand)}</span> + 区间后{' '}
          <span className="font-mono">{diffText(run.afterWindowOnHand)}</span> = 应当等于{' '}
          <span className="font-mono">{diffText(run.expectedOnHand)}</span>；快照{' '}
          <span className="font-mono">{diffText(run.snapshotOnHand)}</span>。
        </p>
        <p>
          预占：期初 <span className="font-mono">{diffText(run.openingReserved)}</span> + 区间内{' '}
          <span className="font-mono">{diffText(run.periodReserved)}</span> + 区间后{' '}
          <span className="font-mono">{diffText(run.afterWindowReserved)}</span> = 应当等于{' '}
          <span className="font-mono">{diffText(run.expectedReserved)}</span>；快照{' '}
          <span className="font-mono">{diffText(run.snapshotReserved)}</span>。
        </p>
      </div>
      {(run.afterWindowOnHand !== 0 || run.afterWindowReserved !== 0) && (
        <div className="mt-3 flex items-start gap-2 rounded-md border border-warning/40 bg-warning-soft p-3 text-xs text-warning">
          <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>核对区间结束后仍有流水，通常意味着错期录入或事后补记，先查这一段。</span>
        </div>
      )}
    </div>
  );
}

/** 可疑区间：回答"是哪一段流水可疑"，而不是只给一个差额数字。 */
function SuspectWindows({ windows }: { windows: ReconciliationSuspectWindow[] }) {
  if (windows.length === 0) return null;
  return (
    <SectionCard
      title="可疑区间"
      description="按天分桶，按严重度与线索条数排序。先去查排在最前面的那一段。"
    >
      <ul className="space-y-2 text-sm">
        {windows.map((w) => (
          <li
            key={w.windowStart}
            className="flex flex-wrap items-center gap-3 rounded-md border border-border px-3 py-2"
          >
            <SeverityBadge severity={w.severity} />
            <span className="font-mono text-xs">{windowLabel(w.windowStart, w.windowEnd)}</span>
            <span className="text-muted-foreground">{w.suspectCount} 条可疑线索</span>
            <span className="text-muted-foreground">区间净在手 {signed(w.onHandDelta)}</span>
          </li>
        ))}
      </ul>
    </SectionCard>
  );
}

function DiscrepancyTable({
  page,
  pageNumber,
  onTurn,
}: {
  page: ReconciliationFindingPage | null;
  pageNumber: number;
  onTurn: (page: number) => void;
}) {
  if (!page) return null;
  return (
    <SectionCard
      title={`差异明细（${page.total} 条余额行对不上）`}
      description="一行 = 一条余额行。差异 = 该行全部历史的分录累计 − 当前快照，与核对区间无关。"
    >
      {page.findings.length === 0 ? (
        <EmptyState
          icon={<ShieldCheck className="h-5 w-5" />}
          title="没有差异明细"
          description="范围内每条余额行的流水累计都等于当前余额快照。"
        />
      ) : (
        <>
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground">
              <tr>
                <th className="py-2">SKU / 仓库</th>
                <th className="py-2">流水累计（在手）</th>
                <th className="py-2">快照（在手）</th>
                <th className="py-2">差异</th>
                <th className="py-2">预占差异</th>
                <th className="py-2">分录 / 业务</th>
                <th className="py-2">最后动过</th>
              </tr>
            </thead>
            <tbody>
              {page.findings.map((f) => (
                <tr key={f.id} className="border-t border-border align-top">
                  <td className="py-2">
                    <div className="font-medium">{f.skuCode}</div>
                    <div className="text-xs text-muted-foreground">
                      {f.warehouseCode} · {f.legalEntityCode} · {f.qualityStatusCode}
                    </div>
                  </td>
                  <td className="py-2 font-mono">{diffText(f.onHandSum)}</td>
                  <td className="py-2 font-mono">{diffText(f.snapshotOnHand)}</td>
                  <td className={`py-2 font-mono ${diffTone(f.onHandDiff)}`}>{diffText(f.onHandDiff)}</td>
                  <td className={`py-2 font-mono ${diffTone(f.reservedDiff)}`}>
                    {diffText(f.reservedDiff)}
                  </td>
                  <td className="py-2 text-xs">
                    {f.entryCount} / {f.transactionCount}
                  </td>
                  <td className="py-2 text-xs text-muted-foreground">
                    {f.transactionId ? (
                      <LedgerHref transactionId={f.transactionId} at={f.occurredAt} />
                    ) : (
                      '—'
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="mt-2 text-xs text-muted-foreground">{page.findings[0]?.reason}</p>
          <Pager total={page.total} page={pageNumber} size={page.size} onTurn={onTurn} />
        </>
      )}
    </SectionCard>
  );
}

function SuspectTable({
  page,
  pageNumber,
  onTurn,
}: {
  page: ReconciliationFindingPage | null;
  pageNumber: number;
  onTurn: (page: number) => void;
}) {
  if (!page) return null;
  return (
    <SectionCard
      title={`可疑流水定位（${page.total} 条线索）`}
      description="漏记 / 迟到补记 / 重复记 / 并发丢失四类，都落到具体的余额行或具体的业务单上。"
    >
      {page.findings.length === 0 ? (
        <EmptyState
          icon={<ShieldCheck className="h-5 w-5" />}
          title="没有可疑流水"
          description="范围内没有差异，也就没有需要解释的流水。"
        />
      ) : (
        <>
          <table className="w-full text-sm">
            <thead className="text-left text-xs text-muted-foreground">
              <tr>
                <th className="py-2">类别</th>
                <th className="py-2">定位</th>
                <th className="py-2">数量</th>
                <th className="py-2">时间桶</th>
                <th className="py-2">说明</th>
              </tr>
            </thead>
            <tbody>
              {page.findings.map((f) => (
                <tr key={f.id} className="border-t border-border align-top">
                  <td className="py-2">
                    <SeverityBadge severity={f.severity} />
                    <div className="mt-1 text-xs">
                      {f.category ? (suspectCategoryLabels[f.category] ?? f.category) : '—'}
                    </div>
                  </td>
                  <td className="py-2 text-xs">
                    {f.transactionId ? (
                      <>
                        <LedgerHref transactionId={f.transactionId} at={f.occurredAt} />
                        <div className="mt-1 text-muted-foreground">
                          {f.refType || '无来源单据'} · {f.skuCode} / {f.warehouseCode}
                        </div>
                        {f.relatedTransactionId ? (
                          <div className="mt-1 text-muted-foreground">
                            与{' '}
                            <LedgerHref transactionId={f.relatedTransactionId} at={null} />
                            {f.gapSeconds !== null ? ` 相差 ${f.gapSeconds} 秒` : ''}
                          </div>
                        ) : null}
                        {f.latenessSeconds !== null ? (
                          <div className="mt-1 text-muted-foreground">
                            补录迟了 {f.latenessSeconds} 秒
                          </div>
                        ) : null}
                      </>
                    ) : (
                      // 漏记的结论就是"指不出哪一笔"，如实显示而不是随便指一笔充数
                      <span className="text-muted-foreground">
                        无对应流水（{f.skuCode} / {f.warehouseCode}）
                      </span>
                    )}
                  </td>
                  <td className="py-2 font-mono text-xs">{signed(f.onHandSum)}</td>
                  <td className="py-2 font-mono text-xs">
                    {f.windowStart ? windowLabel(f.windowStart, f.windowEnd) : '—'}
                  </td>
                  <td className="max-w-md py-2 text-xs text-muted-foreground">{f.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <Pager total={page.total} page={pageNumber} size={page.size} onTurn={onTurn} />
        </>
      )}
    </SectionCard>
  );
}

function StatusPill({ status }: { status: string }) {
  const ok = status === 'CONSISTENT';
  return (
    <span
      className={`rounded px-2 py-0.5 text-xs ${ok ? 'bg-success-soft text-success' : 'bg-destructive-soft text-destructive'}`}
    >
      {statusLabels[status] ?? status}
    </span>
  );
}

function SeverityBadge({ severity }: { severity: string }) {
  const tone =
    severity === 'HIGH'
      ? 'bg-destructive-soft text-destructive'
      : severity === 'WARN'
        ? 'bg-warning-soft text-warning'
        : 'bg-muted text-muted-foreground';
  return <span className={`rounded px-2 py-0.5 text-xs ${tone}`}>{severityLabels[severity] ?? severity}</span>;
}

/** 定位的终点是一笔具体的业务：点一下跳到台账里那笔流水的分录明细。 */
function LedgerHref({ transactionId, at }: { transactionId: string; at: string | null }) {
  const href = `/inventory/ledger?transactionId=${encodeURIComponent(transactionId)}`;
  return (
    <a href={href} className="text-primary underline underline-offset-2">
      {at ? formatMoment(at) : '查看这笔流水'}
    </a>
  );
}

function Pager({
  total,
  page,
  size,
  onTurn,
}: {
  total: number;
  page: number;
  size: number;
  onTurn: (page: number) => void;
}) {
  const pages = Math.max(1, Math.ceil(total / Math.max(1, size)));
  return (
    <div className="mt-3 flex items-center gap-2 text-xs text-muted-foreground">
      <Button variant="outline" size="sm" disabled={page <= 0} onClick={() => onTurn(page - 1)}>
        上一页
      </Button>
      <span>
        第 {page + 1} / {pages} 页（合计 {total}，合计不受分页影响）
      </span>
      <Button
        variant="outline"
        size="sm"
        disabled={page + 1 >= pages}
        onClick={() => onTurn(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}

function diffTone(value: number): string {
  return value === 0 ? '' : 'text-destructive';
}

function signed(value: number | null | undefined): string {
  const n = Number(value ?? 0);
  return n > 0 ? `+${qty(n)}` : qty(n);
}

/** 默认区间：最近 30 天。返回 `datetime-local` 用的墙钟串（本地时区）。 */
function daysAgo(days: number): string {
  const date = new Date(Date.now() - days * 86400000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** 核对时间是带偏移的 ISO 串，按用户本地时区展示成可读时刻。 */
function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}
