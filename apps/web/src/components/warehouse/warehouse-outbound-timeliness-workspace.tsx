'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { RefreshCw } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  OutboundTimelinessCapabilities,
  OutboundTimelinessReport,
  OutboundTimelinessRow,
} from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import { ListPreferenceBar } from '../common/list-preference-bar';
import { useListPreferences } from '../../lib/use-list-preferences';
import type { ListColumn } from '../../lib/list-preferences-model';
import {
  bucketText,
  defaultFilters,
  errorText,
  fetchTimeliness,
  fetchTimelinessCapabilities,
  isEmptyTimeliness,
  minutesText,
  missingCount,
  rateText,
  rowMinutes,
  stageDistribution,
  thresholdFor,
  thresholdText,
  type TimelinessFilters,
} from './warehouse-outbound-timeliness-model';

/**
 * 1.4.1.2 列表与表格偏好 —— 超时明细表的**列定义**。
 */
const OVERDUE_COLUMNS: ListColumn[] = [
  { key: 'orderCode', label: '出库单' },
  { key: 'warehouse', label: '仓库' },
  { key: 'stage', label: '环节' },
  { key: 'actual', label: '实际' },
  { key: 'threshold', label: '阈值' },
  { key: 'excess', label: '超出' },
  { key: 'occurredAt', label: '发生时刻' },
];

/**
 * 可以被记住的筛选条件：**只有日期与编号**。
 *
 * <p>统计区间与仓库 id 都是安全 token；这一页没有自由文本筛选。
 */
const PERSISTABLE_FILTERS = ['from', 'to', 'warehouseId'] as const;

/**
 * 8.5.3.1 出库时效工作区。
 *
 * <p>蓝图：「统计出库各环节时效：订单到出库时长、拣货时长、打包时长、交接时长，识别超时环节与瓶颈。
 * 输入：订单时间戳；各作业环节时间戳。输出：时效分布 + 瓶颈环节 + 超时明细。」
 * 以及红线：「时效数据直接对应平台迟发率指标，超时会导致绩效违规与搜索降权。」
 *
 * <p>界面纪律（三件与「直接进绩效申诉」直接相关的事）：
 *
 * <ul>
 *   <li><b>「不可计算」绝不显示成 0 分钟。</b>缺下单时刻投影、还没走到终点、时间戳倒挂，
 *       后端一律给 null 并报出缺失样本数。折成 0 会把「这单还没发」显示成「这单超快」。
 *   <li><b>阈值永远带来源。</b>「40 分钟（仓库覆盖）」而不是光秃秃的 40 ——
 *       看到数字就要能回答「这是谁定的」。
 *   <li><b>瓶颈显示判定依据。</b>瓶颈只在内部三环节里选（总时长恒最大，参与排名结论恒为零），
 *       所以界面把 basis 原文显示出来，而不是只给一个环节名让人猜。
 * </ul>
 *
 * <p>加载 / 空 / 错误三态分开渲染。
 */
export function WarehouseOutboundTimelinessWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');

  const [filters, setFilters] = useState<TimelinessFilters>(defaultFilters);
  const [report, setReport] = useState<OutboundTimelinessReport | null>(null);
  const [caps, setCaps] = useState<OutboundTimelinessCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  // 1.4.1.2 列表与表格偏好：这一页记「列显示 + 统计区间 + 仓库」。
  // 这一页的每页条数由后端固定（20），所以没有「每页条数」这一项。
  const prefs = useListPreferences({
    pageKey: 'warehouse.outbound-timeliness',
    columns: OVERDUE_COLUMNS,
    pageSize: 20,
    pageSizeOptions: [],
    persistableFilterKeys: PERSISTABLE_FILTERS,
  });
  const columnVisible = (key: string) => prefs.state.columns.includes(key);
  /** 恢复出来的视图状态只在「恢复完成」那一刻应用一次，之后由用户自己的操作驱动。 */
  const restored = useRef(false);
  useEffect(() => {
    if (!prefs.ready || restored.current) return;
    restored.current = true;
    const restoredFilters = prefs.state.filters;
    setFilters((current) => ({
      ...current,
      from: restoredFilters.from ?? '',
      to: restoredFilters.to ?? '',
      warehouseId: restoredFilters.warehouseId ?? '',
      page: 0,
    }));
  }, [prefs.ready, prefs.state.filters]);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, capability] = await Promise.all([
        fetchTimeliness(filters),
        fetchTimelinessCapabilities(filters.warehouseId || null),
      ]);
      setReport(data);
      setCaps(capability);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filters, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const distribution = report?.distribution ?? [];
  const thresholds = report?.thresholds ?? [];
  const overdueItems = report?.overdue.items ?? [];
  const overdueTotal = report?.overdue.total ?? 0;
  const page = report?.overdue.page ?? 0;
  const pageSize = report?.overdue.size ?? 20;

  const bottleneck = report?.bottleneck;
  // 缺失样本摘要依赖 stages（来自 caps），所以 stages 也放进 memo —— 否则
  // `caps?.stages ?? []` 每次渲染都是新数组，useMemo 会跟着每帧重算。
  const missingSummary = useMemo(
    () =>
      (caps?.stages ?? []).map((s) => ({
        stage: s,
        missing: report ? missingCount(report, s.value) : 0,
      })),
    [caps, report],
  );

  if (!readable) {
    return (
      <SectionCard>
        <EmptyState
          title="没有查看出库时效的权限"
          description="本功能需要仓库读取权限（warehouse.inventory.read）。"
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="出库时效"
        description="订单到出库 / 拣货 / 打包 / 交接四环节时效、瓶颈环节与超时明细"
        actions={
          <Button variant="secondary" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="mr-2 h-4 w-4" aria-hidden />
            刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="查询失败">
          {error}
        </Notice>
      ) : null}

      <SectionCard title="统计区间">
        <div className="grid gap-3 sm:grid-cols-4">
          <label className="text-sm">
            <span className="text-muted-foreground mb-1 block">起始日（含）</span>
            <Input
              type="date"
              value={filters.from}
              onChange={(e) => {
                setFilters((f) => ({ ...f, from: e.target.value, page: 0 }));
                prefs.setFilter('from', e.target.value);
              }}
            />
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground mb-1 block">结束日（含）</span>
            <Input
              type="date"
              value={filters.to}
              onChange={(e) => {
                setFilters((f) => ({ ...f, to: e.target.value, page: 0 }));
                prefs.setFilter('to', e.target.value);
              }}
            />
          </label>
          <label className="text-sm">
            <span className="text-muted-foreground mb-1 block">仓库 id（留空 = 全租户）</span>
            <Input
              value={filters.warehouseId}
              placeholder="留空看全租户"
              onChange={(e) => {
                setFilters((f) => ({ ...f, warehouseId: e.target.value, page: 0 }));
                prefs.setFilter('warehouseId', e.target.value);
              }}
            />
          </label>
          <div className="text-muted-foreground flex items-end text-sm">
            {report ? (
              <span>
                {report.from} ~ {report.to} · {report.timezone}
              </span>
            ) : null}
          </div>
        </div>
      </SectionCard>

      {loading && !report ? <p className="text-muted-foreground text-sm">加载中…</p> : null}

      {report && isEmptyTimeliness(report) ? (
        <SectionCard>
          <EmptyState
            title="该区间没有已发货的出库单"
            description="时效只统计已走到各环节终点的样本。没有已发货单据时不存在分布与瓶颈。"
          />
        </SectionCard>
      ) : null}

      {report && !isEmptyTimeliness(report) ? (
        <>
          <div className="grid gap-4 sm:grid-cols-3">
            <StatCard label="已发货出库单" value={String(report.shippedOrderCount)} unit="单" />
            <StatCard
              label="超时单数"
              value={String(report.overdueOrderCount)}
              unit="单"
              comparisonLabel={`超时单率 ${rateText(report.overdueOrderRate)}`}
            />
            <StatCard
              label="瓶颈环节"
              value={bottleneck?.stageLabel ?? '无法判定'}
              comparisonLabel={
                bottleneck?.stageCode
                  ? `${bottleneck.overdueCount}/${bottleneck.sampledCount} 单超时`
                  : '区间内没有任何内部环节有已完成样本'
              }
            />
          </div>

          <SectionCard title="时效分布（四环节）">
            <p className="text-muted-foreground mb-3 text-sm">{report.dayBasis}</p>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2 pr-3">环节</th>
                    <th className="py-2 pr-3">样本</th>
                    <th className="py-2 pr-3">缺失</th>
                    <th className="py-2 pr-3">覆盖率</th>
                    <th className="py-2 pr-3">平均</th>
                    <th className="py-2 pr-3">P50</th>
                    <th className="py-2 pr-3">P90</th>
                    <th className="py-2 pr-3">最大</th>
                    <th className="py-2 pr-3">阈值</th>
                    <th className="py-2 pr-3">超时率</th>
                  </tr>
                </thead>
                <tbody>
                  {distribution.map((d) => {
                    const threshold = thresholdFor(thresholds, d.stageCode);
                    return (
                      <tr key={d.stageCode} className="border-border border-t">
                        <td className="py-2 pr-3">
                          {d.stageLabel}
                          {d.stageCode === 'ORDER_TO_OUTBOUND' ? (
                            <span className="text-muted-foreground ml-1 text-xs">（总时长）</span>
                          ) : null}
                        </td>
                        <td className="py-2 pr-3">{d.sampledCount}</td>
                        <td className="py-2 pr-3">{d.missingOrderCount}</td>
                        <td className="py-2 pr-3">{rateText(d.coverageRate)}</td>
                        <td className="py-2 pr-3">{minutesText(d.avgMinutes)}</td>
                        <td className="py-2 pr-3">{minutesText(d.p50Minutes)}</td>
                        <td className="py-2 pr-3">{minutesText(d.p90Minutes)}</td>
                        <td className="py-2 pr-3">{minutesText(d.maxMinutes)}</td>
                        <td className="py-2 pr-3">
                          {threshold ? thresholdText(threshold) : minutesText(d.thresholdMinutes)}
                        </td>
                        <td className="py-2 pr-3">
                          {rateText(d.overdueRate)}
                          <span className="text-muted-foreground ml-1 text-xs">
                            ({d.overdueCount})
                          </span>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="text-muted-foreground mt-3 text-xs">
              「样本」= 走到该环节终点的单数（分母）。「缺失」= 终点尚未发生的单数，单独报出、
              不混进分母 —— 「今天时效很好」有可能只是「货还没发出去」。
            </p>
          </SectionCard>

          {distribution.length > 0 ? (
            <SectionCard title="分桶分布">
              <div className="grid gap-4 md:grid-cols-2">
                {distribution.map((d) => (
                  <div key={d.stageCode}>
                    <div className="mb-1 text-sm font-medium">
                      {d.stageLabel}
                      {d.sampledCount === 0 ? (
                        <span className="text-muted-foreground ml-2 text-xs">
                          本区间无样本，不给分位数
                        </span>
                      ) : null}
                    </div>
                    <ul className="text-muted-foreground space-y-1 text-xs">
                      {d.buckets.map((b) => (
                        <li key={b.bucket} className="flex justify-between">
                          <span>{b.bucket}</span>
                          <span>{bucketText(b.orderCount, b.share)}</span>
                        </li>
                      ))}
                    </ul>
                  </div>
                ))}
              </div>
            </SectionCard>
          ) : null}

          <SectionCard title="瓶颈判定依据">
            <p className="text-sm">{bottleneck?.basis ?? '区间内没有可判定的样本。'}</p>
          </SectionCard>

          <SectionCard title="样本完整度">
            <ul className="space-y-1 text-sm">
              {missingSummary.map(({ stage, missing }) => (
                <li key={stage.value} className="flex justify-between">
                  <span>{stage.label}</span>
                  <span className="text-muted-foreground">
                    {missing} 单未走到终点
                    {missing > 0 ? '（时长不可计算，未计入分布）' : ''}
                  </span>
                </li>
              ))}
            </ul>
          </SectionCard>

          <SectionCard title="超时明细">
            <ListPreferenceBar columns={OVERDUE_COLUMNS} pageSizeOptions={[]} prefs={prefs} />
            <div className="text-muted-foreground mb-3 flex items-center justify-between text-sm">
              <span>
                共 {overdueTotal} 条 · 第 {page + 1} 页
              </span>
              <span className="flex gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={page === 0}
                  onClick={() => setFilters((f) => ({ ...f, page: f.page - 1 }))}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={(page + 1) * pageSize >= overdueTotal}
                  onClick={() => setFilters((f) => ({ ...f, page: f.page + 1 }))}
                >
                  下一页
                </Button>
              </span>
            </div>
            {overdueItems.length === 0 ? (
              <EmptyState title="该区间没有超时明细" description="四个环节都在生效阈值之内。" />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      {columnVisible('orderCode') && <th className="py-2 pr-3">出库单</th>}
                      {columnVisible('warehouse') && <th className="py-2 pr-3">仓库</th>}
                      {columnVisible('stage') && <th className="py-2 pr-3">环节</th>}
                      {columnVisible('actual') && <th className="py-2 pr-3">实际</th>}
                      {columnVisible('threshold') && <th className="py-2 pr-3">阈值</th>}
                      {columnVisible('excess') && <th className="py-2 pr-3">超出</th>}
                      {columnVisible('occurredAt') && <th className="py-2 pr-3">发生时刻</th>}
                    </tr>
                  </thead>
                  <tbody>
                    {overdueItems.map((d) => (
                      <tr
                        key={`${d.outboundOrderId}-${d.stageCode}`}
                        className="border-border border-t"
                      >
                        {columnVisible('orderCode') && (
                          <td className="py-2 pr-3">{d.orderCode}</td>
                        )}
                        {columnVisible('warehouse') && (
                          <td className="py-2 pr-3">{d.warehouseCode}</td>
                        )}
                        {columnVisible('stage') && <td className="py-2 pr-3">{d.stageLabel}</td>}
                        {columnVisible('actual') && (
                          <td className="py-2 pr-3">{minutesText(d.actualMinutes)}</td>
                        )}
                        {columnVisible('threshold') && (
                          <td className="py-2 pr-3">
                            {thresholdText({
                              stageCode: d.stageCode,
                              stageLabel: d.stageLabel,
                              thresholdMinutes: d.thresholdMinutes,
                              source: d.thresholdSource,
                              sourceLabel: d.thresholdSource,
                              enabled: null,
                              note: null,
                            })}
                          </td>
                        )}
                        {columnVisible('excess') && (
                          <td className="py-2 pr-3">{minutesText(d.excessMinutes)}</td>
                        )}
                        {columnVisible('occurredAt') && (
                          <td className="py-2 pr-3">{d.occurredAt ?? '—'}</td>
                        )}
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="text-muted-foreground mt-3 text-xs">
              明细定位到「具体出库单 + 具体环节 + 超出多少分钟」：绩效申诉要的是这三个数，
              不是一句「平均超时」。
            </p>
          </SectionCard>

          <SectionCard title="逐单时长">
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2 pr-3">出库单</th>
                    <th className="py-2 pr-3">仓库</th>
                    <th className="py-2 pr-3">订单到出库</th>
                    <th className="py-2 pr-3">拣货</th>
                    <th className="py-2 pr-3">打包</th>
                    <th className="py-2 pr-3">交接</th>
                    <th className="py-2 pr-3">超时环节</th>
                  </tr>
                </thead>
                <tbody>
                  {report.rows.map((row: OutboundTimelinessRow) => (
                    <tr key={row.outboundOrderId} className="border-border border-t">
                      <td className="py-2 pr-3">{row.orderCode}</td>
                      <td className="py-2 pr-3">{row.warehouseCode}</td>
                      <td className="py-2 pr-3">
                        {minutesText(rowMinutes(row, 'ORDER_TO_OUTBOUND'))}
                      </td>
                      <td className="py-2 pr-3">{minutesText(rowMinutes(row, 'PICKING'))}</td>
                      <td className="py-2 pr-3">{minutesText(rowMinutes(row, 'PACKING'))}</td>
                      <td className="py-2 pr-3">{minutesText(rowMinutes(row, 'HANDOVER'))}</td>
                      <td className="py-2 pr-3">
                        {row.overdueStages.length === 0 ? (
                          <span className="text-muted-foreground">—</span>
                        ) : (
                          row.overdueStages.join('、')
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </SectionCard>
        </>
      ) : null}

      {caps ? (
        <SectionCard title="口径声明">
          <dl className="space-y-3 text-sm">
            <div>
              <dt className="font-medium">四个环节怎么取</dt>
              <dd className="text-muted-foreground">{caps.stageBasisNote}</dd>
            </div>
            <div>
              <dt className="font-medium">订单时间戳从哪来</dt>
              <dd className="text-muted-foreground">{caps.orderTimestampSourceNote}</dd>
            </div>
            <div>
              <dt className="font-medium">缺失样本怎么算</dt>
              <dd className="text-muted-foreground">{caps.missingSampleBasisNote}</dd>
            </div>
            <div>
              <dt className="font-medium">瓶颈怎么选</dt>
              <dd className="text-muted-foreground">{caps.bottleneckBasisNote}</dd>
            </div>
            <div>
              <dt className="font-medium">内置兜底阈值</dt>
              <dd className="text-muted-foreground">
                {caps.builtinThresholds.map((t) => thresholdText(t)).join('；')}
              </dd>
            </div>
          </dl>
        </SectionCard>
      ) : null}
    </div>
  );
}
