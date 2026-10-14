'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  LocationUtilizationCapabilities,
  LocationUtilizationDetail,
  LocationUtilizationReport,
  LocationUtilizationRow,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  defaultFilters,
  dwellText,
  errorText,
  fetchUtilization,
  fetchUtilizationCapabilities,
  fetchUtilizationDetail,
  gradeLabel,
  heatColor,
  heatRows,
  heatTextColor,
  isEmptyUtilization,
  metricLabel,
  outboundText,
  severityTone,
  totalUtilizationText,
  turnoverText,
  utilizationText,
  type UtilizationFilters,
} from './warehouse-location-utilization-model';

/**
 * 8.5.2.1 库位利用率工作区。
 *
 * <p>蓝图：「分析库位占用：容量利用率、周转率、空置库位、爆满库位、低效库位，给出调整建议。
 * 输入：库位与库存占用；出库频次。输出：利用率报表 + 热力图 + 建议。」以及
 * 「库位热力数据同时优化拣货路径（高频商品放近出口），一举两得。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「未量」与 0.0% 分开渲染。</b>0% 是一句关于事实的断言（「量过了，是空的」），
 *       「未量」是一句关于知识的断言（「没人量过」）。混成同一个 0，运营会拿它排库位。
 *   <li><b>热力图是真数据驱动。</b>每个格子的颜色来自后端算的 {@code heat}（0..100），
 *       并标出强度来自哪个指标（容量利用率 / 在手量相对值 / 空置）——
 *       换指标时必须换说法，不能画成同一种色还叫同一个名字。
 *   <li><b>合计只覆盖已量容量的库位时显示「N/M」。</b>不显示一个没人测过的百分比。
 *   <li><b>建议逐条带归属模块</b>：分流上架归 8.1.2.2，拣货路径归 8.3.2.1。
 *       本页只<b>指出问题并指认归属</b>，不越界去执行。
 *   <li><b>出库频次的归集粒度直接显示出来</b>，不只写在文档里。
 *   <li>加载 / 空 / 错误三态分开渲染。
 * </ul>
 */
export function WarehouseLocationUtilizationWorkspace({ warehouseId }: { warehouseId: string }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.facilities.read');

  const [filters, setFilters] = useState<UtilizationFilters>(defaultFilters);
  const [report, setReport] = useState<LocationUtilizationReport | null>(null);
  const [caps, setCaps] = useState<LocationUtilizationCapabilities | null>(null);
  const [detail, setDetail] = useState<LocationUtilizationDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, capability] = await Promise.all([
        fetchUtilization(warehouseId, filters),
        fetchUtilizationCapabilities(),
      ]);
      setReport(data);
      setCaps(capability);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [warehouseId, filters, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const openDetail = async (locationId: string) => {
    try {
      setDetail(await fetchUtilizationDetail(warehouseId, locationId, filters));
    } catch (e) {
      setError(errorText(e));
    }
  };

  const rows = useMemo(() => report?.locations ?? [], [report]);
  const heat = useMemo(() => heatRows(report?.heatmap ?? []), [report]);
  const totals = report?.totals;

  if (!readable) {
    return (
      <SectionCard>
        <EmptyState
          title="没有查看库位利用率的权限"
          description="本功能需要仓库读取权限（warehouse.facilities.read）。"
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="库位利用率"
        description="容量利用率、周转率、空置与爆满库位、低效库位与调整建议"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
        }
      />

      {error !== '' && (
        <Notice tone="danger" title="库位利用率读取失败">
          {error}
        </Notice>
      )}

      <SectionCard title="筛选条件">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <label className="text-sm text-slate-300">
            统计窗口（天）
            <Input
              type="number"
              value={String(filters.windowDays)}
              min={1}
              max={caps?.maxWindowDays ?? 365}
              onChange={(e) =>
                setFilters({
                  ...filters,
                  windowDays: Number(e.target.value) || defaultFilters.windowDays,
                })
              }
            />
          </label>
          <label className="text-sm text-slate-300">
            利用率档位
            <select
              className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
              value={filters.grade}
              onChange={(e) => setFilters({ ...filters, grade: e.target.value })}
            >
              <option value="">全部</option>
              {(caps?.grades ?? []).map((g) => (
                <option key={g} value={g}>
                  {gradeLabel(g)}
                </option>
              ))}
            </select>
          </label>
          <label className="text-sm text-slate-300">
            库位类型
            <select
              className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
              value={filters.type}
              onChange={(e) => setFilters({ ...filters, type: e.target.value })}
            >
              <option value="">全部</option>
              {['STORAGE', 'RECEIVING', 'SHIPPING', 'QUARANTINE', 'DEFECTIVE', 'FROZEN'].map(
                (t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ),
              )}
            </select>
          </label>
          <label className="text-sm text-slate-300">
            利用率下限（0..1）
            <Input
              value={filters.minUtilization}
              placeholder="如 0.85"
              onChange={(e) => setFilters({ ...filters, minUtilization: e.target.value })}
            />
          </label>
          <label className="text-sm text-slate-300">
            关键字
            <Input
              value={filters.keyword}
              placeholder="编码或名称"
              onChange={(e) => setFilters({ ...filters, keyword: e.target.value })}
            />
          </label>
        </div>
        <label className="mt-3 flex items-center gap-2 text-sm text-slate-300">
          <input
            type="checkbox"
            checked={filters.pickableOnly}
            onChange={(e) => setFilters({ ...filters, pickableOnly: e.target.checked })}
          />
          只看可拣库位
        </label>
        <p className="mt-2 text-xs text-slate-500">
          利用率下限会天然排除「未量容量」与「容量为 0」两类 ——
          它们的利用率没有定义。要看这两类请按档位筛。
        </p>
      </SectionCard>

      {loading && <p className="text-sm text-slate-400">正在读取库位利用率…</p>}

      {!loading && totals !== undefined && totals !== null && (
        <>
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="库位总数" value={totals.locationCount} />
            <StatCard label="合计容量利用率" value={totalUtilizationText(totals)} />
            <StatCard label="爆满库位" value={totals.overfullCount} />
            <StatCard label="空置库位" value={totals.idleCount} />
            <StatCard label="高位库位" value={totals.highCount} />
            <StatCard label="低位 / 适中" value={`${totals.lowCount} / ${totals.balancedCount}`} />
            <StatCard label="容量未量" value={totals.unmeasuredCount} />
            <StatCard label="窗口出库量" value={totals.outboundQuantity.toFixed(2)} unit="件" />
            <StatCard
              label="整体周转率"
              value={
                totals.turnoverComputable && totals.turnover !== null
                  ? totals.turnover.toFixed(2)
                  : '无在手量'
              }
            />
          </div>
          <p className="mt-2 text-xs text-slate-500">
            爆满 = 在手已超量得容量；高位 = 利用率 ≥ 85% 但未超；容量未量 =
            从没量过容量，利用率算不出来。 合计利用率只覆盖已量容量的库位，
            {totalUtilizationText(totals).includes('/')
              ? '本次未给出百分比，因为不是每个库位都量过容量。'
              : '本次所有库位都量过容量。'}
          </p>
        </>
      )}

      {!loading && isEmptyUtilization(report) && error === '' && (
        <SectionCard>
          <EmptyState
            title="这个仓库还没有库位"
            description="库位利用率按真实库位与真实库存计算，没有库位就没有可分析的占用。"
          />
        </SectionCard>
      )}

      {!loading && !isEmptyUtilization(report) && (
        <>
          <SectionCard title="库位热力图">
            <p className="mb-3 text-xs text-slate-500">
              色阶由每个库位的真实利用率（或容量量不出时的在手量相对值）驱动，括号里是强度来源。
            </p>
            <div className="flex flex-wrap gap-2">
              {heat.map(({ cell, indent }) => (
                <div
                  key={cell.locationId}
                  className="flex min-w-[112px] flex-col gap-1 rounded border border-slate-700 px-2 py-1.5 text-xs"
                  style={{ marginLeft: `${indent * 12}px`, backgroundColor: heatColor(cell.heat) }}
                >
                  <button
                    type="button"
                    className="text-left font-medium hover:underline"
                    style={{ color: heatTextColor(cell.heat) }}
                    onClick={() => void openDetail(cell.locationId)}
                  >
                    {cell.code}
                  </button>
                  <span style={{ color: heatTextColor(cell.heat) }}>
                    {utilizationTextFromCell(cell.capacityUtilization)} · 强度 {cell.heat}
                  </span>
                  <span style={{ color: heatTextColor(cell.heat) }}>
                    {metricLabel(cell.metric)}
                  </span>
                </div>
              ))}
            </div>
          </SectionCard>

          <SectionCard title={`库位利用率报表（${rows.length} 条）`}>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-700 text-xs text-slate-400">
                    <th className="py-2 pr-3">库位</th>
                    <th className="py-2 pr-3">类型</th>
                    <th className="py-2 pr-3">档位</th>
                    <th className="py-2 pr-3">容量</th>
                    <th className="py-2 pr-3">在手 / 预占</th>
                    <th className="py-2 pr-3">容量利用率</th>
                    <th className="py-2 pr-3">窗口出库</th>
                    <th className="py-2 pr-3">周转率</th>
                    <th className="py-2 pr-3">SKU / 离散</th>
                    <th className="py-2 pr-3">在手天数</th>
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <UtilizationRow key={row.id} row={row} onOpen={() => void openDetail(row.id)} />
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>

          <SectionCard title={`调整建议（${report?.advice.length ?? 0} 条）`}>
            {report !== null && report.advice.length > 0 ? (
              <ul className="space-y-2">
                {report.advice.map((a, i) => (
                  <li
                    key={`${a.kind}-${a.locationId}-${a.skuId}-${i}`}
                    className={`rounded border px-3 py-2 text-sm ${severityTone(a.severity)}`}
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{a.kindLabel ?? a.kind}</span>
                      <span className="border-current/40 rounded border px-1.5 py-0.5 text-xs">
                        {a.severityLabel ?? a.severity}
                      </span>
                      {a.locationCode !== null && (
                        <span className="text-xs">库位 {a.locationCode}</span>
                      )}
                      {a.ownerModule !== null && (
                        <span className="text-xs">落地归属：{a.ownerModule}</span>
                      )}
                    </div>
                    <p className="mt-1 text-slate-200">{a.message}</p>
                    <p className="mt-0.5 text-xs text-slate-400">{a.action}</p>
                  </li>
                ))}
              </ul>
            ) : (
              <EmptyState
                title="没有需要处理的库位"
                description="爆满、空置、低效与分散四类问题都没有出现。建议由真实数据触发，不会为了凑数出现。"
              />
            )}
          </SectionCard>

          {caps !== null && (
            <SectionCard title="口径说明">
              <dl className="space-y-2 text-sm text-slate-300">
                <div>
                  <dt className="text-slate-400">出库频次归集粒度</dt>
                  <dd>{caps.outboundFrequencyGrain}</dd>
                </div>
                <div>
                  <dt className="text-slate-400">周转率口径</dt>
                  <dd>{caps.turnoverBasis}</dd>
                </div>
                <div>
                  <dt className="text-slate-400">下游模块</dt>
                  <dd>
                    自动分配（{caps.autoAllocationOwnerModule ?? '未指认'}）
                    {caps.autoAllocationAvailable ? '：已就绪' : '：未交付'}； 拣货路径（
                    {caps.pickPathOwnerModule ?? '未指认'}）
                    {caps.pickPathOptimizationAvailable ? '：已就绪' : '：未交付'}
                  </dd>
                </div>
              </dl>
            </SectionCard>
          )}

          {detail !== null && (
            <SectionCard title={`库位 ${detail.location.code} 的 SKU 明细`}>
              <div className="mb-3 text-xs text-slate-500">
                「高频商品放近出口」这条建议的依据：出库量与跨库位离散度都是真数。
              </div>
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-slate-700 text-xs text-slate-400">
                    <th className="py-2 pr-3">SKU</th>
                    <th className="py-2 pr-3">在手 / 预占</th>
                    <th className="py-2 pr-3">窗口出库</th>
                    <th className="py-2 pr-3">散在几个库位</th>
                    <th className="py-2 pr-3">占该 SKU 全仓在手</th>
                    <th className="py-2 pr-3">在手天数</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.skus.map((s) => (
                    <tr key={s.skuId} className="border-b border-slate-800">
                      <td className="py-1.5 pr-3 font-mono text-xs">{s.skuId}</td>
                      <td className="py-1.5 pr-3">
                        {s.onHand} / {s.reserved}
                      </td>
                      <td className="py-1.5 pr-3">{s.outboundQuantity}</td>
                      <td className="py-1.5 pr-3">{s.locationCount}</td>
                      <td className="py-1.5 pr-3">
                        {s.shareOfSkuOnHand === null
                          ? '无全仓在手'
                          : `${(s.shareOfSkuOnHand * 100).toFixed(1)}%`}
                      </td>
                      <td className="py-1.5 pr-3">
                        {s.dwellDays === null ? '无库存记录' : `${s.dwellDays.toFixed(1)} 天`}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <button
                type="button"
                className="mt-3 text-sm text-slate-400 hover:text-slate-200"
                onClick={() => setDetail(null)}
              >
                收起明细
              </button>
            </SectionCard>
          )}
        </>
      )}
    </div>
  );
}

function UtilizationRow({ row, onOpen }: { row: LocationUtilizationRow; onOpen: () => void }) {
  return (
    <tr className="border-b border-slate-800">
      <td className="py-1.5 pr-3">
        <button type="button" className="text-left font-medium hover:underline" onClick={onOpen}>
          {row.code}
        </button>
        <div className="text-xs text-slate-500">{row.name}</div>
      </td>
      <td className="py-1.5 pr-3 text-xs">{row.typeLabel ?? row.type}</td>
      <td className="py-1.5 pr-3">
        <span className="rounded border border-slate-600 px-1.5 py-0.5 text-xs">
          {row.gradeLabel ?? gradeLabel(row.grade)}
        </span>
        {row.capacityExceeded && <span className="ml-1 text-xs text-rose-300">已超容</span>}
      </td>
      <td className="py-1.5 pr-3">{row.capacity === null ? '未量' : row.capacity}</td>
      <td className="py-1.5 pr-3">
        {row.onHand} / {row.reserved}
      </td>
      <td className="py-1.5 pr-3">{utilizationText(row)}</td>
      <td className="py-1.5 pr-3">{outboundText(row)}</td>
      <td className="py-1.5 pr-3">{turnoverText(row)}</td>
      <td className="py-1.5 pr-3 text-xs">
        {row.skuCount} / {row.skuDispersion}
      </td>
      <td className="py-1.5 pr-3 text-xs">{dwellText(row)}</td>
    </tr>
  );
}

/** 热力格子里没有「档位」概念，直接按容量利用率三态渲染。 */
function utilizationTextFromCell(value: number | null): string {
  if (value === null || value === undefined) return '未量';
  return `${(value * 100).toFixed(0)}%`;
}
