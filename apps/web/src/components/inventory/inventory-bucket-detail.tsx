'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  ArrowRightLeft,
  Boxes,
  MapPin,
  PackageSearch,
  RefreshCw,
  TriangleAlert,
} from 'lucide-react';
import { Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import type {
  BucketAction,
  BucketRow,
  InventoryBucketDetail,
  LegalEntityRef,
  LocationRef,
  LotRef,
  QualityStatusRef,
  SkuRef,
  WarehouseRef,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionNote,
  bucketPlacement,
  bucketQuery,
  errorText,
  fetchBuckets,
  fetchLegalEntities,
  fetchLocations,
  fetchLots,
  fetchQualityStatuses,
  fetchSkus,
  fetchWarehouses,
  lotLabel,
  lotStatusLabels,
  locationTypeLabels,
  pickableNote,
  qty,
  roleBadge,
  roleLabels,
  type BucketDrill,
  type BucketParams,
} from './inventory-model';

/**
 * 7.1.1.2 库存明细工作区：bucket 级的最细粒度视图。
 *
 * <p>蓝图的硬要求是「界面上必须能看到『在哪个货位、什么批次、什么质量状态』，否则无法解释差异」。
 * 所以这张表的列顺序刻意是 **SKU → 货位 · 批次 · 质量状态 → 数量**，而不是像总览那样先摆数量。
 *
 * <p>与总览（7.1.1.1）的关系是上下钻：总览行带 `detailHref`，点了带着四个维度跳到本页；
 * 本页的合计与总览合计**必须对得上**，对不上时页面上直接报，而不是让用户自己比。
 *
 * <p>四条界面纪律：
 *
 * <ul>
 *   <li>**未分解 ≠ 不平**。还没拆到库位的 balance 行是合法状态，单独计数展示；
 *       只有 Σbucket 与 balance 真对不上才报警。
 *   <li>**无批次是真实取值**，显示成「无批次」而不是空白格。
 *   <li>**最近变动时间与系统时间分开展示**：前者是库存业务的发生时间，后者是行写入时刻。
 *   <li>**行内操作入口如实标注可用性**：目标模块（7.4.1.1 / 7.5.1.1 / 7.1.2.1）未交付时禁用并
 *       说明原因，不给点下去 404 的死链。
 * </ul>
 */
export function InventoryBucketDetail({
  drill,
  readable,
}: {
  /** 总览（7.1.1.1）行下钻带过来的四个维度；null 表示不带筛选。 */
  drill: BucketDrill | null;
  readable: boolean;
}) {
  const [detail, setDetail] = useState<InventoryBucketDetail | null>(null);
  const [warehouses, setWarehouses] = useState<WarehouseRef[]>([]);
  const [entities, setEntities] = useState<LegalEntityRef[]>([]);
  const [statuses, setStatuses] = useState<QualityStatusRef[]>([]);
  const [skus, setSkus] = useState<SkuRef[]>([]);
  const [locations, setLocations] = useState<LocationRef[]>([]);
  const [lots, setLots] = useState<LotRef[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [expanded, setExpanded] = useState<string | null>(null);

  // 下钻条件：总览行点进来时带上四个维度，之后可自由改
  const [warehouseId, setWarehouseId] = useState(drill?.warehouseId ?? '');
  const [legalEntityId, setLegalEntityId] = useState(drill?.legalEntityId ?? '');
  const [locationId, setLocationId] = useState('');
  const [skuId, setSkuId] = useState(drill?.skuId ?? '');
  const [qualityStatusId, setQualityStatusId] = useState(drill?.qualityStatusId ?? '');
  const [lotId, setLotId] = useState('');
  const [keyword, setKeyword] = useState('');
  const [onlyPickable, setOnlyPickable] = useState(false);
  const [sort, setSort] = useState('ON_HAND');
  const [page, setPage] = useState(0);

  // 总览行下钻：drill 变了就把四个维度同步过来并回到第一页。
  // 不这么做的话，从总览切过来时会看到上一轮筛选残留的行，
  // 用户会以为"下了钻但数字没变"。
  useEffect(() => {
    setWarehouseId(drill?.warehouseId ?? '');
    setLegalEntityId(drill?.legalEntityId ?? '');
    setSkuId(drill?.skuId ?? '');
    setQualityStatusId(drill?.qualityStatusId ?? '');
    setLocationId('');
    setLotId('');
    setPage(0);
  }, [drill]);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    const params: BucketParams = { sort, page, size: 50 };
    if (warehouseId) params.warehouseId = warehouseId;
    if (legalEntityId) params.legalEntityId = legalEntityId;
    if (locationId) params.locationId = locationId;
    if (skuId) params.skuId = skuId;
    if (lotId) params.lotId = lotId;
    if (qualityStatusId) params.qualityStatusId = qualityStatusId;
    if (keyword.trim()) params.keyword = keyword.trim();
    if (onlyPickable) params.onlyPickable = true;
    try {
      setDetail(await fetchBuckets(params));
    } catch (e) {
      setError(errorText(e));
      setDetail(null);
    } finally {
      setLoading(false);
    }
  }, [
    readable,
    warehouseId,
    legalEntityId,
    locationId,
    skuId,
    lotId,
    qualityStatusId,
    keyword,
    onlyPickable,
    sort,
    page,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  // 参照数据：仓库 / 法人 / 质量状态 / SKU 只取一次
  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        const [wh, le, qs, sk] = await Promise.all([
          fetchWarehouses(),
          fetchLegalEntities(),
          fetchQualityStatuses(),
          fetchSkus(),
        ]);
        setWarehouses(wh);
        setEntities(le);
        setStatuses(qs);
        setSkus(sk);
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [readable]);

  // 逐级下钻的下一级跟着上级走：选了仓才拉库位，选了 SKU 才拉批次。
  // 不这么做的话下拉里会混着别的仓的库位，选中的结果可能是空的而用户不知道为什么。
  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        setLocations(await fetchLocations(warehouseId || undefined));
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [readable, warehouseId]);

  useEffect(() => {
    if (!readable) return;
    void (async () => {
      try {
        setLots(await fetchLots(skuId || undefined));
      } catch (e) {
        setError(errorText(e));
      }
    })();
  }, [readable, skuId]);

  // 上级变了，下级若已不在候选集里就清掉，避免带着一个永远筛不出行的残值
  useEffect(() => {
    if (locationId && locations.length > 0 && !locations.some((l) => l.id === locationId)) {
      setLocationId('');
    }
  }, [locations, locationId]);

  useEffect(() => {
    if (lotId && lots.length > 0 && !lots.some((l) => l.id === lotId)) setLotId('');
  }, [lots, lotId]);

  const summary = detail?.summary ?? null;
  const recon = detail?.reconciliation ?? null;
  const actions = useMemo<BucketAction[]>(() => detail?.actions ?? [], [detail]);

  if (!readable) {
    return (
      <EmptyState
        icon={<PackageSearch className="h-6 w-6" />}
        title="没有库存明细的查看权限"
        description="当前角色未被授予 warehouse.inventory.read，请联系管理员在角色中开启该页面。"
      />
    );
  }

  return (
    <div className="space-y-4">
      <SectionCard
        title="库存明细（库位 × 批次）"
        description="一行 = 法人货权 × SKU × 仓库 × 库位 × 批次 × 质量状态，与 7.1.1.1 总览逐级下钻。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={loading ? 'mr-2 h-3.5 w-3.5 animate-spin' : 'mr-2 h-3.5 w-3.5'} />
            刷新
          </Button>
        }
      >
        <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-4">
          <label className="space-y-1 text-sm">
            <span className="block font-medium">货权法人</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={legalEntityId}
              onChange={(e) => {
                setLegalEntityId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部法人</option>
              {entities.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code} {l.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">仓库</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={warehouseId}
              onChange={(e) => {
                setWarehouseId(e.target.value);
                setPage(0);
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
            <span className="block font-medium">库位</span>
            <select
              className="border-control-border bg-card disabled:bg-muted w-full rounded-md border px-3 py-2 text-sm"
              value={locationId}
              disabled={!warehouseId && locations.length > 1}
              onChange={(e) => {
                setLocationId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部库位</option>
              {locations.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.code} {l.name}（{locationTypeLabels[l.type] ?? l.type}）
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
                setPage(0);
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
            <span className="block font-medium">批次</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={lotId}
              onChange={(e) => {
                setLotId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部批次（含无批次）</option>
              {lots.map((l) => (
                <option key={l.id} value={l.id}>
                  {l.skuCode} / {l.lotCode}
                  {l.expiresOn ? `（效期 ${l.expiresOn}）` : ''}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">质量状态</span>
            <select
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={qualityStatusId}
              onChange={(e) => {
                setQualityStatusId(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部质量状态</option>
              {statuses.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.name}
                </option>
              ))}
            </select>
          </label>
          <label className="space-y-1 text-sm">
            <span className="block font-medium">关键字</span>
            <input
              className="border-control-border bg-card w-full rounded-md border px-3 py-2 text-sm"
              value={keyword}
              placeholder="SKU / 仓库 / 库位 / 批次 编码或名称"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPage(0);
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
                setPage(0);
              }}
            >
              <option value="ON_HAND">在手从高到低</option>
              <option value="AVAILABLE">可用从高到低</option>
              <option value="SKU_CODE">按 SKU 编码</option>
              <option value="LOCATION_CODE">按库位编码</option>
              <option value="LAST_MOVEMENT">按最近变动时间</option>
            </select>
          </label>
          <label className="flex items-end gap-2 text-sm">
            <input
              type="checkbox"
              className="h-4 w-4"
              checked={onlyPickable}
              onChange={(e) => {
                setOnlyPickable(e.target.checked);
                setPage(0);
              }}
            />
            只看可拣库位
          </label>
        </div>
      </SectionCard>

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive flex items-start gap-3 rounded-md border p-4 text-sm">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-2">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              重新加载
            </Button>
          </div>
        </div>
      )}

      {summary && (
        <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
          <StatCard label="明细在手" value={qty(summary.onHand)} polarity="neutral" />
          <StatCard label="明细可用" value={qty(summary.available)} polarity="neutral" />
          <StatCard label="明细预占" value={qty(summary.reserved)} polarity="neutral" />
          <StatCard
            label="库位 / 批次"
            value={`${summary.locationCount} / ${summary.lotCount}`}
            polarity="neutral"
          />
        </div>
      )}

      {/* 守恒核对：不成立就当场报，而不是让用户自己把两个数对起来 */}
      {recon && !recon.onHandConserved && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border p-4 text-sm">
          账实不平：涉及 {recon.touchedBalanceCount} 条余额的在手是 {qty(recon.balanceOnHand)}，
          其库位明细汇总是 {qty(recon.bucketOnHand)}。请检查库存写入方，或重新加载。
        </div>
      )}
      {recon && recon.onHandConserved && recon.undecomposedBalanceCount > 0 && (
        <div className="border-warning/40 bg-warning-soft flex items-start gap-3 rounded-md border p-4 text-sm">
          <MapPin className="text-warning mt-0.5 h-4 w-4 shrink-0" />
          <div className="space-y-1">
            <p className="font-medium">还有 {recon.undecomposedBalanceCount} 条余额未拆到库位</p>
            <p className="text-muted-foreground">
              这些库存只有仓库级余额，还没有库位 / 批次明细，所以不在上表内 ——
              这是合法状态而不是差异。拆库位由收货、上架与调拨写入（属 08 仓库作业）。
            </p>
          </div>
        </div>
      )}

      <SectionCard
        title="库位明细"
        description={
          detail
            ? `共 ${detail.total} 行，当前第 ${detail.page + 1} / ${Math.max(
                1,
                Math.ceil(detail.total / detail.size),
              )} 页；合计为全量 ${summary?.rowCount ?? 0} 行的和。`
            : undefined
        }
        extra={
          <div className="flex gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={loading || page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <Button
              variant="outline"
              size="sm"
              disabled={loading || !detail || (page + 1) * detail.size >= detail.total}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        }
      >
        {loading ? (
          <div className="space-y-2" aria-busy="true" aria-live="polite">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className="bg-muted h-9 animate-pulse rounded" />
            ))}
            <p className="text-muted-foreground text-sm">正在读取库位明细…</p>
          </div>
        ) : !detail || detail.rows.length === 0 ? (
          <EmptyState
            icon={<Boxes className="h-6 w-6" />}
            title={detail ? '没有符合条件的库位明细' : '尚未加载'}
            description={
              detail
                ? '当前筛选条件下没有库位明细。可放宽筛选；若整库为空，说明库存尚未拆解到库位（见上方提示）。'
                : '请稍候，或点击刷新重新加载。'
            }
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1180px] text-sm">
              <thead>
                <tr className="text-muted-foreground border-b text-left text-xs">
                  <th className="px-3 py-2 font-medium">SKU</th>
                  <th className="px-3 py-2 font-medium">仓库</th>
                  <th className="px-3 py-2 font-medium">货权法人</th>
                  <th className="px-3 py-2 font-medium">库位 · 批次 · 质量状态</th>
                  <th className="px-3 py-2 text-right font-medium">在手</th>
                  <th className="px-3 py-2 text-right font-medium">预占</th>
                  <th className="px-3 py-2 text-right font-medium">可用</th>
                  <th className="px-3 py-2 font-medium">最近变动</th>
                  <th className="px-3 py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {detail.rows.map((row) => (
                  <BucketRowView
                    key={row.bucketId}
                    row={row}
                    actions={actions}
                    open={expanded === row.bucketId}
                    onToggle={() =>
                      setExpanded((current) => (current === row.bucketId ? null : row.bucketId))
                    }
                  />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 一行 bucket。点击展开版本号、业务/系统时间、批次效期与库位可拣性等构成。 */
function BucketRowView({
  row,
  actions,
  open,
  onToggle,
}: {
  row: BucketRow;
  actions: BucketAction[];
  open: boolean;
  onToggle: () => void;
}) {
  return (
    <>
      <tr className="hover:bg-muted/50 cursor-pointer border-b last:border-0" onClick={onToggle}>
        <td className="px-3 py-2">
          <div className="font-medium">{row.skuCode}</div>
          <div className="text-muted-foreground text-xs">{row.skuName}</div>
        </td>
        <td className="px-3 py-2">
          {row.warehouseCode}
          <div className="text-muted-foreground text-xs">{row.warehouseName}</div>
        </td>
        <td className="px-3 py-2">
          {row.legalEntityCode}
          <div className="text-muted-foreground text-xs">{row.legalEntityName}</div>
        </td>
        <td className="px-3 py-2">
          <div className="flex flex-wrap items-center gap-1">
            <span className="bg-muted rounded px-2 py-0.5 text-xs">
              {row.locationCode} · {lotLabel(row.lotCode)}
            </span>
            <span className={`rounded px-2 py-0.5 text-xs ${roleBadge[row.qualityRole] ?? ''}`}>
              {roleLabels[row.qualityRole] ?? row.qualityStatusName}
            </span>
            {row.expired && (
              <span className="bg-destructive-soft text-destructive rounded px-2 py-0.5 text-xs">
                已过效期
              </span>
            )}
            {!row.pickable && (
              <span className="bg-warning-soft text-warning rounded px-2 py-0.5 text-xs">
                不可拣
              </span>
            )}
            {row.zeroStock && (
              <span className="bg-destructive-soft text-destructive rounded px-2 py-0.5 text-xs">
                零库存
              </span>
            )}
          </div>
        </td>
        <td className="px-3 py-2 text-right tabular-nums">{qty(row.onHand)}</td>
        <td className="px-3 py-2 text-right tabular-nums">{qty(row.reserved)}</td>
        <td className="px-3 py-2 text-right font-medium tabular-nums">{qty(row.available)}</td>
        <td className="whitespace-nowrap px-3 py-2 text-xs">
          <div className="tabular-nums">{new Date(row.lastMovementAt).toLocaleString('zh-CN')}</div>
          <div className="text-muted-foreground">v{row.version}</div>
        </td>
        <td className="px-3 py-2" onClick={(e) => e.stopPropagation()}>
          <div className="flex flex-wrap gap-1">
            {actions.map((a) => (
              <Button
                key={a.action}
                variant="outline"
                size="sm"
                disabled={!a.available}
                title={actionNote(a)}
                aria-label={actionNote(a)}
              >
                {a.action === 'TRANSFER' ? <ArrowRightLeft className="h-3.5 w-3.5" /> : a.label}
              </Button>
            ))}
          </div>
        </td>
      </tr>
      {open && (
        <tr className="bg-muted/30 border-b last:border-0">
          <td colSpan={9} className="px-3 py-3">
            <div className="grid gap-4 text-sm sm:grid-cols-2 lg:grid-cols-4">
              <Cell
                label="库位"
                value={`${row.locationCode} ${row.locationName}（${pickableNote(row)}）`}
              />
              <Cell
                label="批次"
                value={
                  row.lotCode
                    ? `${row.lotCode}${row.expiresOn ? ` · 效期 ${row.expiresOn}` : ''}${
                        row.lotStatus ? ` · ${lotStatusLabels[row.lotStatus] ?? row.lotStatus}` : ''
                      }`
                    : '无批次（非批次管理品）'
                }
              />
              <Cell
                label="质量状态"
                value={`${row.qualityStatusName}（${
                  row.qualityRole === 'SELLABLE' ? '可售，可被预占' : '不可预占'
                }）`}
              />
              <Cell
                label="可用构成"
                value={`在手 ${qty(row.onHand)} − 预占 ${qty(row.reserved)} = ${qty(row.available)}`}
              />
              <Cell label="版本号" value={String(row.version)} />
              <Cell
                label="最近变动（业务时间）"
                value={new Date(row.lastMovementAt).toLocaleString('zh-CN')}
              />
              <Cell
                label="行更新时间（系统时间）"
                value={new Date(row.updatedAt).toLocaleString('zh-CN')}
              />
              <Cell
                label="归属余额"
                value={`balance ${row.balanceId}（7.1.1.1 总览的同一行口径）`}
              />
            </div>
          </td>
        </tr>
      )}
    </>
  );
}

function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-muted-foreground text-xs">{label}</div>
      <div className="mt-0.5 tabular-nums">{value}</div>
    </div>
  );
}
