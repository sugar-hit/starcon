'use client';

import { Fragment, useCallback, useEffect, useState } from 'react';
import { AlertTriangle, RefreshCw, ScrollText, TriangleAlert } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  InventoryEntryLine,
  InventoryLedgerDrill,
  InventoryLedgerPage,
  InventoryRefType,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  balanceNowNote,
  errorText,
  fetchLedger,
  fetchLedgerDrill,
  fetchLedgerRefTypes,
  fetchSkus,
  fetchWarehouses,
  grainNote,
  ledgerKindLabels,
  ledgerKindOptions,
  qty,
  refLabel,
  signed,
  signedTone,
  type LedgerDrill,
  type LedgerParams,
} from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.1.2.1 库存流水台账工作区。
 *
 * <p>蓝图：「按时间顺序展示所有库存业务，每笔含来源单据、数量符号、操作人、发生时间，
 * 可下钻到分录行」，输出是「流水列表 + 分录明细 + 期间净变动汇总」。
 *
 * <p>六条界面纪律：
 *
 * <ul>
 *   <li>**数量符号必须显式画出 +/−**，颜色只是辅助。台账的正负就是入库/出库的方向，
 *       只靠颜色区分在灰度打印和色觉障碍下不可读。
 *   <li>**在手与预占分两列**。预占/释放只动预占列、不动在手列，合成一列会让人以为
 *       「预占 8 件」把在手也动了。
 *   <li>**行上的量是整笔的净变动**，不是筛选维度的量。按 SKU 筛选后这个差异最容易被误读成
 *       算错了，所以口径说明直接写在表头上（{@link grainNote}）。
 *   <li>**空分录的业务单独报数**，不静默吞掉 —— 它是写入方中途失败的痕迹。
 *   <li>**期间净变动的两个口径都摆出来**：行口径与分录口径。无维度筛选时两者恒等，
 *       给了 SKU/仓库筛选后不相等是正常的，差值就是被筛掉的那部分（7.1.2.2 核对的基线）。
 *   <li>**时间区间默认给最近 30 天**。台账是全量历史，不设默认区间的话第一次进来就是
 *       「从有数据那天到现在」，几千行里根本找不到要看的那笔。
 * </ul>
 */
export function InventoryLedger({ drill }: { drill: LedgerDrill | null }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');

  const [page, setPage] = useState<InventoryLedgerPage | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [refTypes, setRefTypes] = useState<InventoryRefType[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);
  const [drillDown, setDrillDown] = useState<{ id: string; data: InventoryLedgerDrill } | null>(
    null,
  );
  const [drillError, setDrillError] = useState('');
  const [drillLoading, setDrillLoading] = useState(false);

  const [from, setFrom] = useState(() => daysAgo(30));
  const [to, setTo] = useState(() => daysAgo(0));
  const [warehouseId, setWarehouseId] = useState(drill?.warehouseId ?? '');
  const [skuId, setSkuId] = useState(drill?.skuId ?? '');
  const [kinds, setKinds] = useState<string[]>([]);
  const [refType, setRefType] = useState('');
  const [keyword, setKeyword] = useState('');
  const [sort, setSort] = useState('OCCURRED_AT');
  const [pageNumber, setPageNumber] = useState(0);

  // 从 7.1.1.2 点「查看流水」进来时带上那两行的定位维度，之后可自由改
  useEffect(() => {
    setWarehouseId(drill?.warehouseId ?? '');
    setSkuId(drill?.skuId ?? '');
    setPageNumber(0);
  }, [drill]);

  const hasDimensionFilter = Boolean(warehouseId || skuId);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: LedgerParams = { sort, page: pageNumber, size: 50 };
    if (from) params.from = from;
    if (to) params.to = to;
    if (warehouseId) params.warehouseId = warehouseId;
    if (skuId) params.skuId = skuId;
    if (kinds.length) params.kinds = kinds;
    if (refType) params.refTypes = [refType];
    if (keyword.trim()) params.keyword = keyword.trim();
    try {
      setPage(await fetchLedger(params));
    } catch (e) {
      setError(errorText(e));
      setPage(null);
    } finally {
      setLoading(false);
    }
  }, [readable, from, to, warehouseId, skuId, kinds, refType, keyword, sort, pageNumber]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        const [wh, sk, rt] = await Promise.all([
          fetchWarehouses(),
          fetchSkus(),
          fetchLedgerRefTypes(),
        ]);
        setWarehouses(wh);
        setSkus(sk);
        setRefTypes(rt);
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [readable]);

  async function toggleEntry(transactionId: string) {
    setDrillError('');
    if (expanded === transactionId) {
      setExpanded(null);
      setDrillDown(null);
      return;
    }
    setExpanded(transactionId);
    setDrillLoading(true);
    try {
      setDrillDown({ id: transactionId, data: await fetchLedgerDrill(transactionId) });
    } catch (e) {
      setDrillError(errorText(e));
      setDrillDown(null);
    } finally {
      setDrillLoading(false);
    }
  }

  if (!readable) {
    return (
      <EmptyState
        icon={<ScrollText className="h-6 w-6" />}
        title="没有库存流水的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const summary = page?.summary ?? null;
  const totalPages = page ? Math.max(1, Math.ceil(page.total / page.size)) : 1;

  return (
    <div className="space-y-6">
      <PageHeader
        title="库存流水台账"
        description="按时间顺序展示所有库存业务：入库、出库、预占、释放、调整与调拨，每笔含来源单据、数量符号、操作人与发生时间，可下钻到分录行。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}

      {page && page.dataScope === 'SELF' && (
        <div className="border-warning/40 bg-warning-soft flex items-start gap-3 rounded-md border p-4 text-sm">
          <TriangleAlert className="text-warning mt-0.5 h-4 w-4 shrink-0" />
          <p>{page.dataScopeNote}</p>
        </div>
      )}

      {/* 期间净变动：行口径与分录口径并排，不相等时差值就是被维度筛掉的部分 */}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="期间净在手变动"
          value={signed(summary?.transactionOnHandDelta)}
          polarity="neutral"
          comparisonLabel="入库为正、出库为负"
        />
        <StatCard
          label="期间净预占变动"
          value={signed(summary?.transactionReservedDelta)}
          polarity="neutral"
          comparisonLabel="预占为正、释放为负"
        />
        <StatCard
          label="业务笔数"
          value={summary?.transactionCount ?? 0}
          polarity="neutral"
          comparisonLabel={
            summary && summary.entryCount !== summary.transactionCount
              ? `共 ${summary.entryCount} 条分录`
              : '每笔一条分录'
          }
        />
        <StatCard
          label="涉及 SKU / 仓库"
          value={`${summary?.skuCount ?? 0} / ${summary?.warehouseCount ?? 0}`}
          polarity="neutral"
          comparisonLabel={`${summary?.actorCount ?? 0} 名操作人`}
        />
      </div>

      {summary && hasDimensionFilter && summary.onHandConserved && summary.reservedConserved && (
        <div className="border-warning/40 bg-warning-soft rounded-md border p-3 text-sm">
          已按 SKU / 仓库筛选：汇总里的两个口径仍然相等，说明每笔命中业务都只涉及这一个维度。
        </div>
      )}
      {summary && summary.emptyTransactionCount > 0 && (
        <div className="border-warning/40 bg-warning-soft rounded-md border p-3 text-sm">
          期间内有 {summary.emptyTransactionCount} 笔业务没有分录。流水只追加、不修改，
          这些记录不会被悄悄抹掉 —— 它们通常是库存业务写入中断的痕迹，需要人工确认。
        </div>
      )}

      <SectionCard title="筛选" description="期间净变动与分组统计始终是全量口径，不随分页变化。">
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <label className="space-y-1 text-sm">
            <span className="block font-medium">开始时间（含）</span>
            <input
              type="datetime-local"
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={from}
              onChange={(e) => {
                setFrom(toISOStringOffset(e.target.value));
                setPageNumber(0);
              }}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">结束时间（不含）</span>
            <input
              type="datetime-local"
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={to}
              onChange={(e) => {
                setTo(toISOStringOffset(e.target.value));
                setPageNumber(0);
              }}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">仓库</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={warehouseId}
              onChange={(e) => {
                setWarehouseId(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部仓库</option>
              {warehouses.map((w) => (
                <option key={w.id} value={w.id}>
                  {w.code} {w.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">SKU</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={skuId}
              onChange={(e) => {
                setSkuId(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部 SKU</option>
              {skus.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">来源单据类型</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={refType}
              onChange={(e) => {
                setRefType(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="">全部来源单据</option>
              {refTypes.map((r) => (
                <option key={r.refType} value={r.refType}>
                  {r.refType}（{r.transactionCount} 笔）
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">关键字</span>
            <input
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={keyword}
              placeholder="SKU / 仓库 编码名称、业务备注"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageNumber(0);
              }}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">排序</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={sort}
              onChange={(e) => {
                setSort(e.target.value);
                setPageNumber(0);
              }}
            >
              <option value="OCCURRED_AT">发生时间（新到旧）</option>
              <option value="OCCURRED_AT_ASC">发生时间（旧到新）</option>
              <option value="KIND">按业务类型</option>
              <option value="REF_TYPE">按来源单据类型</option>
              <option value="ON_HAND_DELTA">按净在手变动</option>
              <option value="ENTRY_COUNT">按分录条数</option>
            </select>
          </label>
          <div className="space-y-1 text-sm">
            <span className="block font-medium">业务类型</span>
            <div className="flex flex-wrap gap-1.5">
              {ledgerKindOptions.map((k) => {
                const on = kinds.includes(k.value);
                return (
                  <button
                    key={k.value}
                    type="button"
                    aria-pressed={on}
                    onClick={() => {
                      setKinds(on ? kinds.filter((v) => v !== k.value) : [...kinds, k.value]);
                      setPageNumber(0);
                    }}
                    className={`rounded-full border px-2.5 py-1 text-xs transition-colors ${
                      on
                        ? 'border-primary bg-primary text-primary-foreground'
                        : 'border-control-border text-muted-foreground hover:bg-muted'
                    }`}
                  >
                    {k.label}
                  </button>
                );
              })}
            </div>
          </div>
        </div>
      </SectionCard>

      {summary && page && page.breakdown.length > 0 && (
        <SectionCard
          title="按业务类型"
          description="只列出期间真实发生过的类型：没有发生的类型报 0 笔会让人误以为已经查过。"
        >
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            {page.breakdown.map((b) => (
              <div key={b.kind} className="border-control-border rounded-md border p-3 text-sm">
                <p className="font-medium">{ledgerKindLabels[b.kind] ?? b.kind}</p>
                <p className="text-muted-foreground mt-1">
                  {b.transactionCount} 笔 · {b.entryCount} 条分录
                </p>
                <p className={signedTone(b.onHandDelta)}>在手 {signed(b.onHandDelta)}</p>
                <p className={signedTone(b.reservedDelta)}>预占 {signed(b.reservedDelta)}</p>
              </div>
            ))}
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="流水列表"
        description={
          page
            ? `${grainNote(hasDimensionFilter)} 共 ${page.total} 笔，当前第 ${page.page + 1} / ${totalPages} 页。`
            : undefined
        }
        extra={
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={loading || pageNumber === 0}
              onClick={() => setPageNumber((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={loading || !page || pageNumber + 1 >= totalPages}
              onClick={() => setPageNumber((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground py-8 text-center text-sm">正在加载库存流水…</p>
        ) : page && page.rows.length > 0 ? (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left text-xs">
                  <th className="py-2 pr-3">发生时间</th>
                  <th className="py-2 pr-3">业务类型</th>
                  <th className="py-2 pr-3">来源单据</th>
                  <th className="py-2 pr-3 text-right">在手变动</th>
                  <th className="py-2 pr-3 text-right">预占变动</th>
                  <th className="py-2 pr-3 text-right">分录</th>
                  <th className="py-2 pr-3">备注</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {page.rows.map((row) => (
                  <Fragment key={row.transactionId}>
                    <tr className="border-b last:border-0">
                      <td className="whitespace-nowrap py-2 pr-3">
                        {formatMoment(row.occurredAt)}
                      </td>
                      <td className="py-2 pr-3">{ledgerKindLabels[row.kind] ?? row.kind}</td>
                      <td className="whitespace-nowrap py-2 pr-3 font-mono text-xs">
                        {refLabel(row)}
                      </td>
                      <td
                        className={`whitespace-nowrap py-2 pr-3 text-right font-medium ${signedTone(
                          row.onHandDelta,
                        )}`}
                      >
                        {signed(row.onHandDelta)}
                      </td>
                      <td
                        className={`whitespace-nowrap py-2 pr-3 text-right ${signedTone(
                          row.reservedDelta,
                        )}`}
                      >
                        {signed(row.reservedDelta)}
                      </td>
                      <td className="text-muted-foreground whitespace-nowrap py-2 pr-3 text-right">
                        {row.entryCount}
                        {row.warehouseCount > 1 && (
                          <span className="ml-1 text-xs">· 跨 {row.warehouseCount} 仓</span>
                        )}
                      </td>
                      <td className="text-muted-foreground py-2 pr-3">{row.notes || '—'}</td>
                      <td className="py-2 text-right">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => void toggleEntry(row.transactionId)}
                        >
                          {expanded === row.transactionId ? '收起分录' : '下钻分录'}
                        </Button>
                      </td>
                    </tr>
                    {expanded === row.transactionId && (
                      <tr className="bg-muted/40 border-b">
                        <td colSpan={8} className="py-3">
                          {drillError && <p className="text-destructive text-sm">{drillError}</p>}
                          {drillLoading && (
                            <p className="text-muted-foreground text-sm">正在加载分录…</p>
                          )}
                          {drillDown && drillDown.id === row.transactionId && (
                            <EntryTable entries={drillDown.data.entries} />
                          )}
                        </td>
                      </tr>
                    )}
                  </Fragment>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <EmptyState
            icon={<ScrollText className="h-5 w-5" />}
            title="当前条件下没有库存流水"
            description="调整时间范围或筛选条件再试一次。流水只追加不修改，这里没有记录通常意味着这段时间内确实没有发生库存业务。"
          />
        )}
      </SectionCard>

      {summary && (
        <p className="text-muted-foreground text-xs">
          期间 {summary.firstOccurredAt ? formatMoment(summary.firstOccurredAt) : '—'} 至{' '}
          {summary.lastOccurredAt ? formatMoment(summary.lastOccurredAt) : '—'}
          ；分录口径在手 {signed(summary.entryOnHandDelta)}、预占{' '}
          {signed(summary.entryReservedDelta)}。{grainNote(hasDimensionFilter)}
        </p>
      )}
    </div>
  );
}

/** 分录明细表。分录挂在 balance 行上，所以定位维度是 SKU × 仓库 × 法人 × 质量状态。 */
function EntryTable({ entries }: { entries: InventoryEntryLine[] }) {
  if (entries.length === 0) {
    return (
      <p className="text-muted-foreground text-sm">
        这笔业务没有分录。流水只追加不修改，这条记录会被永久保留 ——
        它通常是库存业务写入中断的痕迹，需要人工确认。
      </p>
    );
  }
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-xs">
        <thead>
          <tr className="text-muted-foreground border-b text-left">
            <th className="py-1.5 pr-3">SKU</th>
            <th className="py-1.5 pr-3">仓库</th>
            <th className="py-1.5 pr-3">货权法人</th>
            <th className="py-1.5 pr-3">质量状态</th>
            <th className="py-1.5 pr-3 text-right">在手变动</th>
            <th className="py-1.5 pr-3 text-right">预占变动</th>
            <th className="py-1.5 pr-3 text-right">当前在手 / 预占</th>
          </tr>
        </thead>
        <tbody>
          {entries.map((e) => (
            <tr key={e.entryId} className="border-b last:border-0">
              <td className="py-1.5 pr-3">
                {e.skuCode} <span className="text-muted-foreground">{e.skuName}</span>
              </td>
              <td className="py-1.5 pr-3">{e.warehouseCode}</td>
              <td className="py-1.5 pr-3">{e.legalEntityCode}</td>
              <td className="py-1.5 pr-3">{e.qualityStatusName}</td>
              <td className={`py-1.5 pr-3 text-right ${signedTone(e.onHandDelta)}`}>
                {signed(e.onHandDelta)}
              </td>
              <td className={`py-1.5 pr-3 text-right ${signedTone(e.reservedDelta)}`}>
                {signed(e.reservedDelta)}
              </td>
              <td className="text-muted-foreground py-1.5 pr-3 text-right">
                {qty(e.balanceOnHandNow)} / {qty(e.balanceReservedNow)}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-muted-foreground mt-2 text-xs">{balanceNowNote}</p>
    </div>
  );
}

/**
 * `datetime-local` 给的是不带时区的墙钟串，必须补上偏移量再发出去。
 *
 * <p>后端按绝对时刻筛选 timestamptz；发裸时间串会让服务端猜时区，同一个区间在开发机与
 * K8s 里会筛出不同的结果，期间净变动就没法复现。这里用浏览器本地时区显式换算，
 * 用户看到什么时间就按什么时间筛，不做隐式转换。
 */
function toISOStringOffset(local: string): string {
  if (!local) return '';
  const date = new Date(local);
  if (Number.isNaN(date.getTime())) return local;
  const offset = date.getTimezoneOffset() * 60000;
  const sign = offset <= 0 ? '+' : '-';
  const abs = Math.abs(offset);
  const hh = String(Math.floor(abs / 3600000)).padStart(2, '0');
  const mm = String(Math.floor((abs % 3600000) / 60000)).padStart(2, '0');
  return `${local}${sign}${hh}:${mm}`;
}

/** 默认时间区间：最近 30 天。返回 `datetime-local` 用的墙钟串（本地时区）。 */
function daysAgo(days: number): string {
  const date = new Date(Date.now() - days * 86400000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}

/** 台账时间是带偏移的 ISO 串，按用户本地时区展示成可读时刻。 */
function formatMoment(value: string | null | undefined): string {
  if (!value) return '—';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`;
}
