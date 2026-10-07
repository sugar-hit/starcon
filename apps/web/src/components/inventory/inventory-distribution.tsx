'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Boxes, MapPin, RefreshCw, Warehouse as WarehouseIcon } from 'lucide-react';
import { Button, EmptyState, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  DistributionAction,
  DistributionRow,
  DistributionSpuRef,
  LegalEntityRef,
  MultiWarehouseDistribution,
  SkuRef,
} from '../../../../../packages/api-contracts/src/catalog';
import {
  errorText,
  fetchDistribution,
  fetchDistributionSpus,
  fetchLegalEntities,
  fetchSkus,
  isPlatformRow,
  observationFreshness,
  qty,
  rowSourceNote,
  savePlatformObservation,
  turnoverText,
  warehouseKindLabels,
  type DistributionParams,
} from './inventory-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.1.3.1 多仓分布视图。
 *
 * <p>蓝图：「把同一 SKU（或 SPU）在所有仓库的库存并列展示，含本地仓/海外仓/FBA/三方仓/
 * 在途，用于调拨决策与缺货判断」，输出「多仓对照矩阵（行=仓库，列=在手/预占/可用/在途/
 * 周转天数）」。
 *
 * <p>六条界面纪律：
 *
 * <ul>
 *   <li>**自有账本量与平台观测量分区展示，永远不相加**。FBA 库存是平台仓观测值、
 *       不是自有账本（蓝图原话）。所以页面上不存在「总库存」这一列：账本 100 与平台观测 80
 *       加出来的 180 没有任何一个来源背书。平台行另起一个区块，标注来源与采集时间。
 *   <li>**在途单列，且不并进在手**。在途是"已发货未入本仓"，与本仓在手天然不重叠；
 *       未到货不能发货，所以也不并进可用。
 *   <li>**周转天数算不出就显示「—」并把原因挂在 title 上**，不显示 0。
 *       0 会被读成「瞬间卖完」，假数会被读成「算过了」。两个口径（自有发货速度 /
 *       平台观测差分）在单元格里分开标注，因为它们的数不能直接比大小。
 *   <li>**平台量的新鲜度与数量一样重要**。超过一天直接标「数据较旧」——
 *       把调拨决策建在一个几天前的快照上是这个模块最典型的事故。
 *   <li>**零库存仓要留在表里**。缺货判断是本模块的用途之一，把 available=0 的仓过滤掉
 *       就把"要补货的仓"从视野里删了。
 *   <li>**未登记 SPU 的覆盖缺口如实报出**。按 SPU 看时覆盖不到它们，这是 warehouse
 *       引用登记的客观边界，页面上说明而不是假装筛选是完整的。
 * </ul>
 */
export function InventoryDistribution({ initialSkuId }: { initialSkuId?: string | null }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  // 录入平台观测会写库，所以是写权限；看矩阵不需要
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [scope, setScope] = useState<'SKU' | 'SPU'>('SKU');
  const [skuId, setSkuId] = useState(initialSkuId ?? '');
  const [spuId, setSpuId] = useState('');
  const [legalEntityId, setLegalEntityId] = useState('');
  const [lookbackDays, setLookbackDays] = useState('30');

  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [spus, setSpus] = useState<DistributionSpuRef[]>([]);
  const [entities, setEntities] = useState<LegalEntityRef[]>([]);
  const [matrix, setMatrix] = useState<MultiWarehouseDistribution | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 平台观测录入：来源与采集时间必填，缺一个后端就会显式拒绝，所以表单里不让留空。
  const [obsSource, setObsSource] = useState('AMAZON_FBA');
  const [obsSku, setObsSku] = useState('');
  const [obsWarehouse, setObsWarehouse] = useState('');
  const [obsOnHand, setObsOnHand] = useState('');
  const [obsInbound, setObsInbound] = useState('');
  const [obsCollectedAt, setObsCollectedAt] = useState(() => nowLocal());
  const [obsBusy, setObsBusy] = useState(false);
  const [obsError, setObsError] = useState('');

  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        const [s, le] = await Promise.all([fetchSkus(), fetchLegalEntities()]);
        setSkus(s);
        setEntities(le);
      } catch (e) {
        setError(errorText(e));
      }
      // SPU 下拉单独容错：它拿不到只该让「按 SPU 看」不可选，不该阻断矩阵主体。
      try {
        setSpus(await fetchDistributionSpus());
      } catch {
        setSpus([]);
      }
    })();
  }, [readable]);

  const params = useMemo<DistributionParams | null>(() => {
    if (scope === 'SKU') return skuId ? { skuId } : null;
    return spuId ? { spuId } : null;
  }, [scope, skuId, spuId]);

  const load = useCallback(async () => {
    if (!readable || !params) {
      setMatrix(null);
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const p: DistributionParams = { ...params };
      if (legalEntityId) p.legalEntityId = legalEntityId;
      const days = Number(lookbackDays);
      if (Number.isFinite(days) && days > 0) p.lookbackDays = days;
      setMatrix(await fetchDistribution(p));
    } catch (e) {
      setError(errorText(e));
      setMatrix(null);
    } finally {
      setLoading(false);
    }
  }, [readable, params, legalEntityId, lookbackDays]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitObservation() {
    setObsError('');
    setNotice('');
    if (!obsSku || !obsWarehouse) {
      setObsError('请选择 SKU 与平台仓：观测必须落到具体的货与具体的仓');
      return;
    }
    if (!obsSource.trim()) {
      setObsError('来源不能为空：平台仓数量必须能标注来源');
      return;
    }
    if (!obsCollectedAt) {
      setObsError('采集时间不能为空：平台仓数量必须能标注采集时间');
      return;
    }
    const entityId = legalEntityId || entities[0]?.id;
    if (!entityId) {
      setObsError('缺少货权法人：观测必须记录货权归属');
      return;
    }
    setObsBusy(true);
    try {
      const saved = await savePlatformObservation({
        warehouseId: obsWarehouse,
        skuId: obsSku,
        legalEntityId: entityId,
        source: obsSource.trim(),
        // datetime-local 给的是「本地墙上时间」，没有偏移量。显式补上浏览器偏移再发，
        // 否则后端按服务器时区解析，采集时间会平白偏掉几个小时。
        collectedAt: `${obsCollectedAt}${localOffsetSuffix()}`,
        observedOnHand: Number(obsOnHand || 0),
        observedReserved: 0,
        observedAvailable: Number(obsOnHand || 0),
        observedInbound: Number(obsInbound || 0),
        rawReference: null,
      });
      setNotice(
        `已记录 ${saved.source} 观测：采集时间 ${saved.collectedAt}（来源与采集时间已随该数量一同落库）`,
      );
      await load();
    } catch (e) {
      setObsError(errorText(e));
    } finally {
      setObsBusy(false);
    }
  }

  if (!readable) {
    return (
      <EmptyState
        icon={<Boxes className="h-6 w-6" />}
        title="没有多仓分布的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  const ownRows = matrix?.rows.filter((r) => !isPlatformRow(r)) ?? [];
  const platformRows = matrix?.rows.filter(isPlatformRow) ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="多仓分布"
        description="同一 SKU（或 SPU）在所有仓库的库存并列：自有账本量与平台观测量分区展示，两者不相加。"
        actions={
          <Button
            variant="outline"
            onClick={() => void load()}
            disabled={loading || !params}
            className="gap-2"
          >
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </Button>
        }
      />

      <SectionCard title="查询条件">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <label className="text-sm">
            <span className="text-muted-foreground">查看口径</span>
            <select
              className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
              value={scope}
              onChange={(e) => setScope(e.target.value as 'SKU' | 'SPU')}
            >
              <option value="SKU">按 SKU</option>
              <option value="SPU">按 SPU</option>
            </select>
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground">{scope === 'SKU' ? 'SKU' : 'SPU'}</span>
            <select
              className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
              value={scope === 'SKU' ? skuId : spuId}
              onChange={(e) =>
                scope === 'SKU' ? setSkuId(e.target.value) : setSpuId(e.target.value)
              }
            >
              <option value="">{scope === 'SKU' ? '请选择 SKU' : '请选择 SPU'}</option>
              {scope === 'SKU'
                ? skus.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.code} · {s.name}
                    </option>
                  ))
                : spus.map((s) => (
                    <option key={s.spuId} value={s.spuId}>
                      SPU {s.spuId.slice(0, 8)}…（{s.skuCount} 个 SKU）
                    </option>
                  ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground">货权法人</span>
            <select
              className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
              value={legalEntityId}
              onChange={(e) => setLegalEntityId(e.target.value)}
            >
              <option value="">全部法人</option>
              {entities.map((e) => (
                <option key={e.id} value={e.id}>
                  {e.code} · {e.name}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground">出库回看天数</span>
            <input
              className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
              value={lookbackDays}
              onChange={(e) => setLookbackDays(e.target.value)}
              inputMode="numeric"
            />
          </label>
        </div>
        <p className="text-muted-foreground mt-2 text-xs">
          周转天数只计入发货（SHIPMENT）出库，调拨出库不计入 ——
          调拨不是消耗，把它算进去会让周转天数假性变差。
        </p>
      </SectionCard>

      {error && (
        <p className="border-danger-border bg-danger-soft text-danger rounded-md border px-3 py-2 text-sm">
          {error}
        </p>
      )}

      {!params && (
        <EmptyState
          icon={<MapPin className="h-6 w-6" />}
          title="请选择要查看的 SKU 或 SPU"
          description="多仓分布是一次对照一个 SKU 或一个 SPU —— 不提供全租户视图，因为那样既不是矩阵也无法下钻。"
        />
      )}

      {params && loading && <p className="text-muted-foreground text-sm">正在聚合各仓库存…</p>}

      {params && !loading && matrix && matrix.rows.length === 0 && (
        <EmptyState
          icon={<WarehouseIcon className="h-6 w-6" />}
          title="该 SKU 在本租户没有任何仓有货"
          description="没有任何仓库持有它的在手/预占，也没有在途或平台观测记录。若预期应该有货，请确认是否录入了平台仓观测。"
        />
      )}

      {params && !loading && matrix && matrix.rows.length > 0 && (
        <>
          <SectionCard title="合计（自有账本与平台观测分区，不可相加）">
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <StatCard
                label="自有账本在手"
                value={qty(matrix.totals.ownOnHand)}
                polarity="neutral"
                comparisonLabel={`${matrix.totals.ownWarehouseCount} 个自有仓`}
              />
              <StatCard
                label="自有账本可用"
                value={qty(matrix.totals.ownAvailable)}
                polarity="neutral"
                comparisonLabel={`预占 ${qty(matrix.totals.ownReserved)}`}
              />
              <StatCard
                label="自有仓在途"
                value={qty(matrix.totals.ownInTransit)}
                polarity="neutral"
                comparisonLabel="已发货未入本仓"
              />
              <StatCard
                label="平台观测在手"
                value={qty(matrix.totals.platformOnHand)}
                polarity="neutral"
                comparisonLabel={`${matrix.totals.platformWarehouseCount} 个平台仓`}
              />
            </div>
            <p className="text-muted-foreground mt-3 text-xs">
              自有仓 {matrix.totals.ownWarehouseCount} 个，其中可用为 0 的{' '}
              {matrix.totals.ownZeroWarehouseCount}{' '}
              个（缺货判断要看的就是这个数）。平台观测最早采集时间{' '}
              {matrix.totals.earliestCollectedAt ?? '—'}。<b>本页不提供「总库存」</b>
              ：自有账本量与平台观测量权威性不同，相加得到的数没有任何来源背书。
            </p>
          </SectionCard>

          <SectionCard title={`自有账本仓（${ownRows.length}）`}>
            <p className="text-muted-foreground mb-3 text-xs">
              在手/预占/可用来自库存账本，可直接用于调拨决策；在途按收货仓归属，与在手不重叠、也不并入可用。
            </p>
            <div className="overflow-x-auto">
              <table className="w-full min-w-[1080px] text-sm">
                <thead>
                  <tr className="border-control-border text-muted-foreground border-b text-left">
                    <th className="px-3 py-2 font-medium">仓库</th>
                    <th className="px-3 py-2 font-medium">性质</th>
                    <th className="px-3 py-2 text-right font-medium">在手</th>
                    <th className="px-3 py-2 text-right font-medium">预占</th>
                    <th className="px-3 py-2 text-right font-medium">可用</th>
                    <th className="px-3 py-2 text-right font-medium">在途</th>
                    <th className="px-3 py-2 text-right font-medium">周转天数</th>
                    <th className="px-3 py-2 font-medium">分解度</th>
                  </tr>
                </thead>
                <tbody>
                  {ownRows.map((row) => (
                    <OwnRow key={row.warehouseId} row={row} />
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          {platformRows.length > 0 && (
            <SectionCard title={`平台仓观测量（${platformRows.length}）`}>
              <p className="text-muted-foreground mb-3 text-xs">
                以下数字是<b>平台仓观测值，不是自有账本</b>
                ：来源与采集时间一并显示，因为它们只在采集时刻成立。
                同一仓若来自多个观测来源会分行显示而不是相加 —— 两个来源冲突时相加得到的数谁也不认。
              </p>
              <div className="overflow-x-auto">
                <table className="w-full min-w-[1080px] text-sm">
                  <thead>
                    <tr className="border-control-border text-muted-foreground border-b text-left">
                      <th className="px-3 py-2 font-medium">仓库</th>
                      <th className="px-3 py-2 font-medium">来源</th>
                      <th className="px-3 py-2 font-medium">采集时间</th>
                      <th className="px-3 py-2 text-right font-medium">平台在手</th>
                      <th className="px-3 py-2 text-right font-medium">平台预留</th>
                      <th className="px-3 py-2 text-right font-medium">平台可售</th>
                      <th className="px-3 py-2 text-right font-medium">入库在途</th>
                      <th className="px-3 py-2 text-right font-medium">周转天数</th>
                    </tr>
                  </thead>
                  <tbody>
                    {platformRows.map((row) => (
                      <PlatformRow key={`${row.warehouseId}-${row.source}`} row={row} />
                    ))}
                  </tbody>
                </table>
              </div>
            </SectionCard>
          )}

          <ScopeNote matrix={matrix} />

          {matrix.actions.length > 0 && (
            <SectionCard title="下游操作">
              <ul className="space-y-1 text-sm">
                {matrix.actions.map((a) => (
                  <ActionLine key={a.action} action={a} />
                ))}
              </ul>
            </SectionCard>
          )}
        </>
      )}

      {writable && (
        <SectionCard title="录入平台仓观测">
          <p className="text-muted-foreground mb-3 text-xs">
            平台仓（FBA）数量必须来自采集，不能凭印象填。来源与采集时间为必填 ——
            缺任何一个后端会显式拒绝，
            因为一行「不知道哪来的、什么时候的」平台数量比不显示更危险：它会被当成和自有账本一样确定。
          </p>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-6">
            <label className="text-sm">
              <span className="text-muted-foreground">平台仓</span>
              <select
                className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
                value={obsWarehouse}
                onChange={(e) => setObsWarehouse(e.target.value)}
              >
                <option value="">请选择</option>
                {platformWarehouseIds(matrix).map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} · {w.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-sm">
              <span className="text-muted-foreground">SKU</span>
              <select
                className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
                value={obsSku}
                onChange={(e) => setObsSku(e.target.value)}
              >
                <option value="">请选择</option>
                {skus.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.code}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-sm">
              <span className="text-muted-foreground">来源</span>
              <input
                className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
                value={obsSource}
                onChange={(e) => setObsSource(e.target.value)}
              />
            </label>
            <label className="text-sm">
              <span className="text-muted-foreground">采集时间</span>
              <input
                type="datetime-local"
                className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
                value={obsCollectedAt}
                onChange={(e) => setObsCollectedAt(e.target.value)}
              />
            </label>
            <label className="text-sm">
              <span className="text-muted-foreground">平台在手</span>
              <input
                className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
                value={obsOnHand}
                onChange={(e) => setObsOnHand(e.target.value)}
                inputMode="decimal"
              />
            </label>
            <label className="text-sm">
              <span className="text-muted-foreground">入库在途</span>
              <input
                className="border-control-border bg-surface mt-1 w-full rounded-md border px-2 py-1.5"
                value={obsInbound}
                onChange={(e) => setObsInbound(e.target.value)}
                inputMode="decimal"
              />
            </label>
          </div>
          {obsError && <p className="text-danger mt-2 text-sm">{obsError}</p>}
          {notice && <p className="text-success mt-2 text-sm">{notice}</p>}
          <div className="mt-3">
            <Button onClick={() => void submitObservation()} disabled={obsBusy}>
              {obsBusy ? '记录中…' : '记录观测'}
            </Button>
          </div>
        </SectionCard>
      )}
    </div>
  );
}

function OwnRow({ row }: { row: DistributionRow }) {
  const turnover = turnoverText(row);
  return (
    <tr className="border-control-border border-b last:border-0">
      <td className="px-3 py-2">
        <div>{row.warehouseName}</div>
        <div className="text-muted-foreground text-xs">
          {row.warehouseCode} · {row.countryCode} · {row.timezone}
        </div>
      </td>
      <td className="text-muted-foreground px-3 py-2 text-xs">
        {warehouseKindLabels[row.warehouseKind] ?? row.warehouseKind}
      </td>
      <td className="px-3 py-2 text-right tabular-nums">{qty(row.onHand)}</td>
      <td className="px-3 py-2 text-right tabular-nums">{qty(row.reserved)}</td>
      <td
        className={`px-3 py-2 text-right font-medium tabular-nums ${
          (row.available ?? 0) <= 0 ? 'text-danger' : ''
        }`}
        title={(row.available ?? 0) <= 0 ? '可用为 0：缺货判断要看的就是这一格' : undefined}
      >
        {qty(row.available)}
      </td>
      <td className="px-3 py-2 text-right tabular-nums" title="已从发货仓出库、尚未入本仓">
        {qty(row.inTransit)}
      </td>
      <td className="px-3 py-2 text-right tabular-nums" title={turnover.title}>
        <div>{turnover.value}</div>
        <div className="text-muted-foreground text-xs">{turnover.basis}</div>
      </td>
      <td className="text-muted-foreground px-3 py-2 text-xs">
        {row.bucketCount === 0 ? (
          <span title="该仓库存尚未拆到库位：数量仍然计入矩阵，但点不进明细">尚未拆库位</span>
        ) : (
          `${row.bucketCount} 个货位条目 · ${row.locationCount} 库位 · ${row.lotCount} 批次`
        )}
      </td>
    </tr>
  );
}

function PlatformRow({ row }: { row: DistributionRow }) {
  const turnover = turnoverText(row);
  return (
    <tr className="border-control-border border-b last:border-0">
      <td className="px-3 py-2">
        <div>{row.warehouseName}</div>
        <div className="text-muted-foreground text-xs">
          {row.warehouseCode} · {row.countryCode}
        </div>
      </td>
      <td className="px-3 py-2 text-xs">{row.source ?? '—'}</td>
      <td className="text-muted-foreground px-3 py-2 text-xs">{observationFreshness(row)}</td>
      <td className="px-3 py-2 text-right tabular-nums">{qty(row.platformOnHand)}</td>
      <td className="px-3 py-2 text-right tabular-nums">{qty(row.platformReserved)}</td>
      <td className="px-3 py-2 text-right font-medium tabular-nums">
        {qty(row.platformAvailable)}
      </td>
      <td
        className="px-3 py-2 text-right tabular-nums"
        title="平台报出的入库在途，与自有仓在途不是同一批货"
      >
        {qty(row.platformInbound)}
      </td>
      <td className="px-3 py-2 text-right tabular-nums" title={turnover.title}>
        <div>{turnover.value}</div>
        <div className="text-muted-foreground text-xs">{turnover.basis}</div>
      </td>
    </tr>
  );
}

function ScopeNote({ matrix }: { matrix: MultiWarehouseDistribution }) {
  const unregistered = matrix.skuScope.unregisteredSpuCount;
  if (unregistered <= 0) return null;
  return (
    <p className="border-control-border bg-surface-muted text-muted-foreground rounded-md border px-3 py-2 text-xs">
      本租户有 {unregistered} 个「有库存但未登记 SPU」的 SKU，按 SPU 查看时它们不会出现在矩阵里 ——
      SPU
      归属的权威在商品主数据服务，本服务只做引用登记，覆盖不到是客观边界，这里如实报出而不是假装筛选完整。
    </p>
  );
}

function ActionLine({ action }: { action: DistributionAction }) {
  return (
    <li className="flex items-center gap-2">
      <span className={action.available ? 'text-foreground' : 'text-muted-foreground'}>
        {action.label}
      </span>
      <span className="text-muted-foreground text-xs">
        （目标模块 {action.ownerModule}）{action.note}
      </span>
      <span className="text-muted-foreground text-xs">路由契约 {action.href}</span>
    </li>
  );
}

/** 平台仓下拉只列出矩阵里出现过的平台仓：观测只允许落在平台仓上。 */
function platformWarehouseIds(matrix: MultiWarehouseDistribution | null) {
  const seen = new Map<string, { id: string; code: string; name: string }>();
  for (const row of matrix?.rows ?? []) {
    if (isPlatformRow(row))
      seen.set(row.warehouseId, {
        id: row.warehouseId,
        code: row.warehouseCode,
        name: row.warehouseName,
      });
  }
  return [...seen.values()];
}

function nowLocal(): string {
  const d = new Date();
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(
    d.getMinutes(),
  )}`;
}

/** datetime-local 的值没有偏移量，补上浏览器当前偏移才能表达一个真实时刻。 */
function localOffsetSuffix(): string {
  const offset = -new Date().getTimezoneOffset();
  const sign = offset >= 0 ? '+' : '-';
  const abs = Math.abs(offset);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}
