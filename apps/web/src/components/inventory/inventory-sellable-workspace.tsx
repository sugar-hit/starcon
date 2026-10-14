'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  AvailableQuantityCapabilities,
  AvailableQuantityPage,
  AvailableQuantityReport,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  bufferScopeHint,
  bufferScopeLabel,
  capabilityGaps,
  channelPolicyText,
  defaultSellableParams,
  errorText,
  fetchSellableCapabilities,
  fetchSellableReport,
  fetchSellableSkus,
  headlineWarning,
  isEmptyScope,
  orderedSlices,
  qty,
  safetyBufferConfigured,
  safetyBufferText,
  sellableSortLabels,
  sliceTone,
  type SellableParams,
} from './inventory-sellable-model';

/**
 * 7.1.1.3 可用量工作区。
 *
 * <p>蓝图：「统一计算并展示可售量（available），明确其口径……支持查看构成拆解。」
 * 「输出：可售量数值 + 构成拆解（每一项扣了多少）。」
 *
 * <p>这页要回答的是一个很具体的问题：**这批货现在到底还能卖多少，少的那些去哪了。**
 * 所以布局就是「一个可售量 + 一张构成拆解 + 一张按 SKU 的明细」，再加一块如实说明
 * 哪些口径还没接入的折叠区。
 *
 * <p>加载 / 空 / 错误 / 无权限四态分开渲染。空态要区分两种：
 * 「这个范围没有库存」（正常）与「口径坏了」（不正常）—— 后者由
 * {@link headlineWarning} 顶到第一屏，不会被空态盖住。
 */
export function InventorySellableWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');

  const [params, setParams] = useState<SellableParams>(defaultSellableParams);
  const [report, setReport] = useState<AvailableQuantityReport | null>(null);
  const [page, setPage] = useState<AvailableQuantityPage | null>(null);
  const [caps, setCaps] = useState<AvailableQuantityCapabilities | null>(null);
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
      const { sort: _sort, page: _page, size: _size, ...reportParams } = params;
      const [data, rows, capability] = await Promise.all([
        fetchSellableReport(reportParams),
        fetchSellableSkus(params),
        fetchSellableCapabilities(),
      ]);
      setReport(data);
      setPage(rows);
      setCaps(capability);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [params, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const totals = report?.totals ?? null;
  const warning = headlineWarning(totals);
  const slices = useMemo(() => orderedSlices(totals?.slices), [totals]);
  const gaps = useMemo(() => capabilityGaps(caps), [caps]);
  const unavailableGaps = gaps.filter((g) => !g.available);

  if (!readable) {
    return (
      <SectionCard>
        <EmptyState
          title="没有查看可售量的权限"
          description="本功能需要库存读取权限（warehouse.inventory.read）。"
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="可用量计算"
        description="可售量 = 合格且允许销售的 bucket 可用之和，再按策略扣一次安全缓冲"
        actions={
          <Button onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" /> 刷新
          </Button>
        }
      />

      {error !== '' && (
        <Notice tone="danger" title="可售量读取失败">
          {error}
        </Notice>
      )}

      {/* 口径不自洽或缓冲过大时，这不是一行小字，是这一页最重要的信息。 */}
      {warning !== '' && (
        <Notice tone="warning" title="可售量口径异常">
          {warning}
        </Notice>
      )}

      <SectionCard title="筛选条件">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <label className="text-sm text-slate-300">
            SKU 关键字
            <Input
              className="mt-1"
              value={params.keyword ?? ''}
              placeholder="编码或名称"
              onChange={(e) =>
                setParams({ ...params, keyword: e.target.value, page: 0 })
              }
            />
          </label>
          <label className="text-sm text-slate-300">
            仓库 ID
            <Input
              className="mt-1"
              value={params.warehouseId ?? ''}
              placeholder="留空 = 全部仓库"
              onChange={(e) =>
                setParams({ ...params, warehouseId: e.target.value, page: 0 })
              }
            />
          </label>
          <label className="text-sm text-slate-300">
            库位 ID
            <Input
              className="mt-1"
              value={params.locationId ?? ''}
              placeholder="留空 = 全部库位"
              onChange={(e) =>
                setParams({ ...params, locationId: e.target.value, page: 0 })
              }
            />
          </label>
          <label className="text-sm text-slate-300">
            排序
            <select
              className="mt-1 w-full rounded border border-slate-600 bg-slate-900 px-2 py-1 text-sm"
              value={params.sort ?? 'SELLABLE'}
              onChange={(e) => setParams({ ...params, sort: e.target.value, page: 0 })}
            >
              {Object.entries(sellableSortLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
        </div>
      </SectionCard>

      {/*
        第一屏给结论：可售量是多少，以及它是怎么来的。
        StatCard 没有 hint 位（它的 hint 类位置是 unit / comparisonLabel），
        所以口径说明统一放在下面的「口径与算式」卡里 —— 卡片保持单一职责。
      */}
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="可售量" value={loading ? '…' : qty(totals?.sellable)} unit="件" />
        <StatCard
          label="可售基数"
          value={loading ? '…' : qty(totals?.sellableBeforeBuffer)}
          unit="件"
        />
        <StatCard label="安全缓冲" value={loading ? '…' : safetyBufferText(totals)} unit="件" />
        <StatCard
          label="可用量（在手 − 预占）"
          value={loading ? '…' : qty(totals?.available)}
          unit="件"
        />
      </div>

      <SectionCard title="合计口径">
        <dl className="grid gap-3 text-sm sm:grid-cols-2 lg:grid-cols-4">
          <div>
            <dt className="text-slate-400">在手</dt>
            <dd className="tabular-nums text-slate-200">{qty(totals?.onHand)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">预占</dt>
            <dd className="tabular-nums text-slate-200">{qty(totals?.reserved)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">安全缓冲</dt>
            <dd className="text-slate-200">{bufferScopeHint(totals)}</dd>
          </div>
          <div>
            <dt className="text-slate-400">范围</dt>
            <dd className="text-slate-200">
              {totals?.skuCount ?? 0} 个 SKU · {totals?.bucketCount ?? 0} 条明细 ·{' '}
              {totals?.warehouseCount ?? 0} 个仓
            </dd>
          </div>
        </dl>
      </SectionCard>

      {/*
        构成拆解：并列的切分，不是瀑布。
        每一行给「量 + 行数 + 这一片是计入还是被排除 + 判定依据」，
        被排除的四片用另一种视觉 —— 画成逐级相减会让人以为它们被扣了两道。
      */}
      <SectionCard title="构成拆解" description="五片互斥且穷尽；被排除的片不进可售基数，也不重复扣减">
        <table className="w-full min-w-[720px] text-sm">
          <thead>
            <tr className="border-b border-slate-700 text-left text-slate-400">
              <th className="px-3 py-2">口径</th>
              <th className="px-3 py-2 text-right">可用量</th>
              <th className="px-3 py-2 text-right">明细行</th>
              <th className="px-3 py-2">处理</th>
              <th className="px-3 py-2">判定依据</th>
            </tr>
          </thead>
          <tbody>
            {slices.map((slice) => (
              <tr key={slice.code} className="border-b border-slate-800">
                <td className="px-3 py-2">{slice.label}</td>
                <td className="px-3 py-2 text-right tabular-nums">{qty(slice.amount)}</td>
                <td className="px-3 py-2 text-right tabular-nums">{slice.bucketCount}</td>
                <td className="px-3 py-2">
                  {sliceTone(slice) === 'counted' ? (
                    <span className="text-emerald-400">计入可售</span>
                  ) : (
                    <span className="text-amber-400">被排除</span>
                  )}
                </td>
                <td className="px-3 py-2 text-slate-400">{slice.basis}</td>
              </tr>
            ))}
            {slices.length === 0 && (
              <tr>
                <td colSpan={5} className="px-3 py-6 text-center text-slate-500">
                  暂无构成数据
                </td>
              </tr>
            )}
          </tbody>
          <tfoot>
            <tr className="border-t border-slate-700">
              <td className="px-3 py-2 font-medium">合计</td>
              <td className="px-3 py-2 text-right font-medium tabular-nums">
                {qty(totals?.available)}
              </td>
              <td className="px-3 py-2 text-right tabular-nums">{totals?.bucketCount ?? 0}</td>
              <td className="px-3 py-2 text-slate-400">
                {totals && !totals.sliceBalanced ? '与可用量对不上' : '与可用量一致'}
              </td>
              <td className="px-3 py-2 text-slate-400">{caps?.sliceIdentity ?? ''}</td>
            </tr>
          </tfoot>
        </table>
      </SectionCard>

      <SectionCard title="口径与算式">
        <dl className="space-y-3 text-sm">
          <div>
            <dt className="text-slate-400">算式</dt>
            <dd className="font-mono text-xs text-slate-200">{caps?.availableFormula ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-slate-400">恒等式</dt>
            <dd className="font-mono text-xs text-slate-200">{caps?.sliceIdentity ?? '—'}</dd>
          </div>
          <div>
            <dt className="text-slate-400">渠道可售量口径（7.2.2.1）</dt>
            <dd className="text-slate-200">{channelPolicyText(report?.channelPolicy)}</dd>
          </div>
          {report?.channelPolicy?.note ? (
            <div>
              <dt className="text-slate-400">说明</dt>
              <dd className="text-slate-400">{report.channelPolicy.note}</dd>
            </div>
          ) : null}
          <div>
            <dt className="text-slate-400">预占口径</dt>
            <dd className="text-slate-400">{caps?.releaseNote ?? '—'}</dd>
          </div>
        </dl>
      </SectionCard>

      <SectionCard title="按 SKU 的可售量" description="合计不受分页与排序影响">
        {!loading && isEmptyScope(totals) ? (
          <EmptyState
            title="这个范围内没有库存"
            description="换一个仓库、库位或关键字再试。没有任何 bucket 时不显示可售量数字。"
          />
        ) : (
          <>
            <table className="w-full min-w-[900px] text-sm">
              <thead>
                <tr className="border-b border-slate-700 text-left text-slate-400">
                  <th className="px-3 py-2">SKU</th>
                  <th className="px-3 py-2 text-right">在手</th>
                  <th className="px-3 py-2 text-right">预占</th>
                  <th className="px-3 py-2 text-right">可用</th>
                  <th className="px-3 py-2 text-right">可售基数</th>
                  <th className="px-3 py-2 text-right">安全缓冲</th>
                  <th className="px-3 py-2 text-right">可售量</th>
                  <th className="px-3 py-2 text-right">明细行</th>
                </tr>
              </thead>
              <tbody>
                {(page?.rows ?? []).map((row) => (
                  <tr key={row.skuId} className="border-b border-slate-800">
                    <td className="px-3 py-2">
                      <div>{row.skuCode}</div>
                      <div className="text-xs text-slate-500">{row.skuName}</div>
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{qty(row.onHand)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{qty(row.reserved)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">{qty(row.available)}</td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      {qty(row.sellableBeforeBuffer)}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">
                      <div>{qty(row.safetyBuffer)}</div>
                      <div className="text-xs text-slate-500">
                        {bufferScopeLabel(row.bufferScopeType)}
                      </div>
                    </td>
                    <td
                      className={
                        row.bufferExceedsBase
                          ? 'px-3 py-2 text-right font-medium tabular-nums text-amber-400'
                          : 'px-3 py-2 text-right font-medium tabular-nums'
                      }
                    >
                      {qty(row.sellable)}
                    </td>
                    <td className="px-3 py-2 text-right tabular-nums">{row.bucketCount}</td>
                  </tr>
                ))}
                {(page?.rows ?? []).length === 0 && !isEmptyScope(totals) && (
                  <tr>
                    <td colSpan={8} className="px-3 py-6 text-center text-slate-500">
                      这个范围有库存，但当前页没有行 —— 换个关键字或排序试试
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
            <div className="mt-4 flex items-center justify-between text-sm text-slate-400">
              <span>
                共 {page?.total ?? 0} 个 SKU
                {!safetyBufferConfigured(totals) && totals ? ' · 未配置安全缓冲' : ''}
              </span>
              <div className="flex gap-2">
                <Button
                  disabled={(page?.page ?? 0) === 0}
                  onClick={() =>
                    setParams({ ...params, page: Math.max(0, (page?.page ?? 0) - 1) })
                  }
                >
                  上一页
                </Button>
                <Button
                  disabled={
                    (page?.page ?? 0) + 1 >= Math.ceil((page?.total ?? 0) / (params.size ?? 50))
                  }
                  onClick={() => setParams({ ...params, page: (page?.page ?? 0) + 1 })}
                >
                  下一页
                </Button>
              </div>
            </div>
          </>
        )}
      </SectionCard>

      {unavailableGaps.length > 0 && (
        <SectionCard
          title="尚未接入的口径"
          description="这些能力不属于本模块。未交付的维度如实列出并指认归属，而不是静默按一条直觉处理。"
        >
          <ul className="space-y-3 text-sm">
            {unavailableGaps.map((gap) => (
              <li key={gap.key} className="border-l-2 border-amber-500/60 pl-3">
                <div className="text-slate-200">
                  {gap.label}
                  <span className="ml-2 text-xs text-amber-400">归属 {gap.ownerModule}</span>
                </div>
                <div className="text-slate-400">{gap.note}</div>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}