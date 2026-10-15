'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { AlertTriangle, BarChart3, Clock, RefreshCw, Timer, Truck } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import type { TimelinessFilter } from './order-timeliness-model';
import type {
  OrderTimelinessCapabilities,
  OrderTimelinessDetail,
  OrderTimelinessEventPage,
  OrderTimelinessFactPage,
  OrderTimelinessReport,
  TimelinessDimensionPage,
  TimelinessOverduePage,
} from '../../../../../packages/api-contracts/src/index';
import {
  ApiError,
  capabilityTone,
  fetchTimelinessCapabilities,
  fetchTimelinessDimensions,
  fetchTimelinessEvents,
  fetchTimelinessFact,
  fetchTimelinessFacts,
  fetchTimelinessOverdue,
  fetchTimelinessReport,
  formatDuration,
  formatRate,
  overdueKindLabel,
  promiseLabel,
  promiseTone,
  registerDelivery,
  registerPromise,
  slaLabel,
  slaTone,
  syncTimeliness,
} from './order-timeliness-model';

/** 朴素下拉框：枚举值用 input 画会得到一个只能手打、还能打错值的控件。 */
function SelectField(props: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={props.id} className="text-sm font-medium">
        {props.label}
      </label>
      <select
        id={props.id}
        className="border-input h-9 rounded-md border bg-transparent px-2 text-sm"
        value={props.value}
        onChange={(e) => props.onChange(e.target.value)}
      >
        {props.options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

const TONE_CLASS: Record<string, string> = {
  ok: 'text-emerald-600',
  warn: 'text-amber-600',
  bad: 'text-red-600',
  neutral: 'text-muted-foreground',
};

const TONE_BADGE: Record<string, string> = {
  ok: 'bg-emerald-500/10 text-emerald-600',
  bad: 'bg-red-500/10 text-red-600',
  neutral: 'bg-muted text-muted-foreground',
  warn: 'bg-amber-500/10 text-amber-600',
};

function Badge({ tone, children }: { tone: string; children: React.ReactNode }) {
  return (
    <span className={`rounded px-2 py-0.5 text-xs font-medium ${TONE_BADGE[tone] ?? ''}`}>
      {children}
    </span>
  );
}

export function OrderTimelinessWorkspace() {
  const [caps, setCaps] = useState<OrderTimelinessCapabilities | null>(null);
  const [report, setReport] = useState<OrderTimelinessReport | null>(null);
  const [dimensions, setDimensions] = useState<TimelinessDimensionPage | null>(null);
  const [overdue, setOverdue] = useState<TimelinessOverduePage | null>(null);
  const [facts, setFacts] = useState<OrderTimelinessFactPage | null>(null);
  const [events, setEvents] = useState<OrderTimelinessEventPage | null>(null);
  const [detail, setDetail] = useState<OrderTimelinessDetail | null>(null);

  const [dimensionBy, setDimensionBy] = useState('SHOP');
  const [shipSlaState, setShipSlaState] = useState('');
  const [promiseState, setPromiseState] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);

  const [loading, setLoading] = useState(true);
  const [syncing, setSyncing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [promiseAt, setPromiseAt] = useState('');
  const [promiseNote, setPromiseNote] = useState('');
  const [deliveredAt, setDeliveredAt] = useState('');
  const [deliveryNote, setDeliveryNote] = useState('');

  // 刻意是**空对象**而不是 `{ shopRef: undefined }`：
  // exactOptionalPropertyTypes 下「显式 undefined」不等于「不传」，
  // 写成后者会一路报错，而 query() 本来就把 undefined 与缺失同等对待。
  const filter: TimelinessFilter = useMemo(() => ({}), []);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [c, r, d, o, f, e] = await Promise.all([
        fetchTimelinessCapabilities(),
        fetchTimelinessReport(filter),
        fetchTimelinessDimensions({ ...filter, dimensionBy }),
        fetchTimelinessOverdue(filter),
        fetchTimelinessFacts({ ...filter, shipSlaState, promiseState, keyword, page, size: 20 }),
        fetchTimelinessEvents({ page: 0, size: 20 }),
      ]);
      setCaps(c);
      setReport(r);
      setDimensions(d);
      setOverdue(o);
      setFacts(f);
      setEvents(e);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '加载时效数据失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [filter, dimensionBy, shipSlaState, promiseState, keyword, page]);

  useEffect(() => {
    void load();
  }, [load]);

  const runSync = useCallback(async () => {
    setSyncing(true);
    setNotice(null);
    setError(null);
    try {
      const result = await syncTimeliness({ pageSize: 500 });
      setNotice(
        `扫描 ${result.scanned} 票，新增 ${result.inserted}，更新 ${result.updated}，` +
          `未变 ${result.unchanged}；其中 ${result.withoutOrderedAt} 票缺下单时刻、` +
          `${result.withoutPackedAt} 票缺出库时刻，已落 UNSET 桶`,
      );
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '同步失败，请稍后重试');
    } finally {
      setSyncing(false);
    }
  }, [load]);

  const openDetail = useCallback(async (factId: string) => {
    setError(null);
    setPromiseAt('');
    setDeliveredAt('');
    setPromiseNote('');
    setDeliveryNote('');
    try {
      setDetail(await fetchTimelinessFact(factId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '加载明细失败，请稍后重试');
    }
  }, []);

  const submitPromise = useCallback(async () => {
    if (!detail || !promiseAt) return;
    try {
      setDetail(
        await registerPromise(detail.fact.id, {
          promisedDeliveryAt: promiseAt,
          source: 'MANUAL_ATTESTATION',
          note: promiseNote,
        }),
      );
      setNotice('承诺到货已登记（凭据 append-only，再改请追加一条）');
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '登记承诺到货失败');
    }
  }, [detail, promiseAt, promiseNote, load]);

  const submitDelivery = useCallback(async () => {
    if (!detail || !deliveredAt) return;
    try {
      setDetail(
        await registerDelivery(detail.fact.id, {
          deliveredAt,
          state: 'DELIVERED',
          source: 'MANUAL_ATTESTATION',
          note: deliveryNote,
        }),
      );
      setNotice('实际到货已登记');
      await load();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : '登记实际到货失败');
    }
  }, [detail, deliveredAt, deliveryNote, load]);

  const unavailable = (caps?.dimensions ?? []).filter((x) => !x.available);

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        title="时效分析"
        description="下单到出库、出库到交接、承诺到货 vs 实际到货，按店铺 / 仓库 / 商品 / 承运商拆解"
      />

      {/* 1. 能力声明与缺口 */}
      <SectionCard title="能力声明与上游缺口" extra={<span className="text-muted-foreground"><BarChart3 className="size-4" /></span>}>
        {loading && !caps ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : (
          <>
            <div className="flex flex-wrap gap-2">
              {(caps?.dimensions ?? []).map((d) => (
                <Badge key={d.key} tone={capabilityTone(d.available)}>
                  {d.label}：{d.available ? '可算' : `缺上游 · ${d.ownerModule}`}
                </Badge>
              ))}
            </div>
            {unavailable.length > 0 && (
              <Notice tone="info" title="以下维度没有可读上游">
                本模块只能<b>登记</b>而不是读取 —— 界面上显示为「未登记」
                <b>不等于</b>系统抓到了数据：
                <ul className="mt-2 list-disc pl-5">
                  {unavailable.map((d) => (
                    <li key={d.key}>
                      <b>{d.label}</b>：{d.reason}
                      <br />
                      <span className="text-muted-foreground">本模块处理：{d.handling}</span>
                    </li>
                  ))}
                </ul>
              </Notice>
            )}
            <p className="text-muted-foreground mt-3 text-xs">{caps?.definition}</p>
          </>
        )}
      </SectionCard>

      {/* 2. 同步入口 */}
      <SectionCard title="从 9.4.1.2 发货记录刷新时效事实" extra={<span className="text-muted-foreground"><BarChart3 className="size-4" /></span>}>
        <p className="text-muted-foreground text-sm">
          时效事实只来自 9.4.1.2 的发货记录、9.4.1.1 的待发货投影与销售订单的提交时刻，
          三者都在本服务库内（跨服务 SQL 被 AGENTS.md 禁止）。缺一个端点就如实落 UNSET 桶，
          不会按 0 算。
        </p>
        <Button className="mt-3" onClick={() => void runSync()} disabled={syncing}>
          {syncing ? '同步中…' : '同步时效事实'}
        </Button>
        {notice && (
          <Notice tone="success" title="同步完成">
            {notice}
          </Notice>
        )}
      </SectionCard>

      {error && (
        <Notice tone="danger" title="操作失败">
          <span className="flex items-center gap-2">
            <AlertTriangle className="size-4" />
            {error}
          </span>
        </Notice>
      )}

      {/* 3. 时效报表 */}
      <SectionCard title="时效报表" extra={<span className="text-muted-foreground"><BarChart3 className="size-4" /></span>}>
        {loading && !report ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : report && report.totalFacts === 0 ? (
          <EmptyState
            title="还没有时效事实"
            description="先点上面的「同步时效事实」，从 9.4.1.2 的发货记录拉取。"
          />
        ) : (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              {(report?.segments ?? []).map((s) => (
                <StatCard
                  key={s.segment}
                  label={s.segmentLabel}
                  value={`${s.measuredCount} 票可测`}
                  comparisonLabel={`${s.unsetCount} 票未测出（缺端点）`}
                />
              ))}
            </div>
            <div className="mt-4 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="p-2">时段</th>
                    <th className="p-2">均值</th>
                    <th className="p-2">中位数</th>
                    <th className="p-2">P90</th>
                    <th className="p-2">最慢</th>
                    <th className="p-2">最快</th>
                  </tr>
                </thead>
                <tbody>
                  {(report?.segments ?? []).map((s) => (
                    <tr key={s.segment} className="border-b">
                      <td className="p-2">{s.segmentLabel}</td>
                      <td className="p-2">{formatDuration(Number(s.avgSeconds ?? 0))}</td>
                      <td className="p-2">{formatDuration(s.p50Seconds)}</td>
                      <td className="p-2">{formatDuration(s.p90Seconds)}</td>
                      <td className="p-2">{formatDuration(s.maxSeconds)}</td>
                      <td className="p-2">{formatDuration(s.minSeconds)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-4 grid gap-3 sm:grid-cols-2">
              <div className="rounded-md border p-3">
                <p className="text-sm font-medium">发货时限三桶</p>
                <p className="mt-2 text-sm">
                  <Badge tone={slaTone('ON_TIME')}>按时 {report?.sla.onTime ?? 0}</Badge>{' '}
                  <Badge tone={slaTone('OVERDUE')}>超时 {report?.sla.overdue ?? 0}</Badge>{' '}
                  <Badge tone={slaTone('UNSET')}>未登记时限 {report?.sla.unset ?? 0}</Badge>
                </p>
                <p className="text-muted-foreground mt-2 text-xs">
                  超时率 {formatRate(report?.sla.overdueRate)} —— {report?.sla.rateBasis}
                </p>
              </div>
              <div className="rounded-md border p-3">
                <p className="text-sm font-medium">承诺到货 vs 实际到货 四桶</p>
                <p className="mt-2 text-sm">
                  <Badge tone={promiseTone('MET')}>达成 {report?.promise.met ?? 0}</Badge>{' '}
                  <Badge tone={promiseTone('LATE')}>超出 {report?.promise.late ?? 0}</Badge>{' '}
                  <Badge tone={promiseTone('NO_PROMISE')}>
                    未登记承诺 {report?.promise.noPromise ?? 0}
                  </Badge>{' '}
                  <Badge tone={promiseTone('NO_ACTUAL')}>
                    未登记到货 {report?.promise.noActual ?? 0}
                  </Badge>
                </p>
                <p className="text-muted-foreground mt-2 text-xs">
                  达成率 {formatRate(report?.promise.metRate)} —— {report?.promise.rateBasis}
                </p>
              </div>
            </div>
          </>
        )}
      </SectionCard>

      {/* 4. 分维度对比 */}
      <SectionCard title="分维度对比" extra={<span className="text-muted-foreground"><BarChart3 className="size-4" /></span>}>
        <SelectField
          id="dimensionBy"
          label="拆解维度"
          value={dimensionBy}
          onChange={setDimensionBy}
          options={[
            { value: 'SHOP', label: '店铺' },
            { value: 'WAREHOUSE', label: '仓库' },
            { value: 'CARRIER', label: '承运商' },
            { value: 'SKU', label: '商品' },
          ]}
        />
        {dimensions && dimensions.items.length === 0 ? (
          <EmptyState title="该维度下还没有数据" description="换一组筛选条件再试。" />
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="p-2">{dimensions?.dimensionLabel}</th>
                  <th className="p-2">票数</th>
                  <th className="p-2">可测 / 未测出</th>
                  <th className="p-2">下单到出库 均值</th>
                  <th className="p-2">出库到交接 均值</th>
                  <th className="p-2">下单到交接 均值</th>
                  <th className="p-2">超时限数</th>
                </tr>
              </thead>
              <tbody>
                {(dimensions?.items ?? []).map((row) => (
                  <tr key={`${row.dimension}-${row.dimensionKey}`} className="border-b">
                    <td className="p-2">{row.dimensionLabel || '（未归属）'}</td>
                    <td className="p-2">{row.factCount}</td>
                    <td className="p-2">
                      {row.measuredCount} / {row.unsetCount}
                    </td>
                    <td className="p-2">
                      {row.avgOrderToPackSeconds === null
                        ? '未测出'
                        : formatDuration(Number(row.avgOrderToPackSeconds))}
                    </td>
                    <td className="p-2">
                      {row.avgPackToHandoverSeconds === null
                        ? '未测出'
                        : formatDuration(Number(row.avgPackToHandoverSeconds))}
                    </td>
                    <td className="p-2">
                      {row.avgOrderToHandoverSeconds === null
                        ? '未测出'
                        : formatDuration(Number(row.avgOrderToHandoverSeconds))}
                    </td>
                    <td className="p-2">{row.overdueCount}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
        <p className="text-muted-foreground mt-2 text-xs">{dimensions?.caveat}</p>
      </SectionCard>

      {/* 5. 超时明细 */}
      <SectionCard title="超时明细" extra={<span className="text-muted-foreground"><BarChart3 className="size-4" /></span>}>
        <p className="text-muted-foreground text-sm">
          发货时限超期 <b>{overdue?.shipOverdueCount ?? 0}</b> 票 ·
          到货承诺超期 <b>{overdue?.promiseLateCount ?? 0}</b> 票 ·
          两端有缺口无法判定 <b>{overdue?.unmeasuredPromiseCount ?? 0}</b> 票。
          两个来源刻意分列：一个是平台要求「几天内发出」，另一个是「哪天到货」。
        </p>
        {overdue && overdue.items.length === 0 ? (
          <EmptyState title="没有超时明细" description="当前筛选范围内没有超时限的货。" />
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="p-2">事实号</th>
                  <th className="p-2">销售订单</th>
                  <th className="p-2">承运商</th>
                  <th className="p-2">超时类型</th>
                  <th className="p-2">超时</th>
                  <th className="p-2">时限时刻</th>
                </tr>
              </thead>
              <tbody>
                {(overdue?.items ?? []).map((row) => (
                  <tr key={`${row.factId}-${row.overdueKind}`} className="border-b">
                    <td className="p-2 font-mono text-xs">{row.factNo}</td>
                    <td className="p-2">{row.salesOrderNo || '（无订单快照）'}</td>
                    <td className="p-2">{row.carrierName || row.carrierCode || '—'}</td>
                    <td className="p-2">
                      <Badge tone="bad">{overdueKindLabel(row.overdueKind)}</Badge>
                    </td>
                    <td className="p-2">{formatDuration(row.overdueSeconds)}</td>
                    <td className="p-2 text-xs">{row.deadlineAt ?? '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </SectionCard>

      {/* 6. 事实清单 */}
      <SectionCard title="时效事实清单">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <FormField id="timeliness-keyword" label="关键字">
            <Input
              value={keyword}
              placeholder="单号 / 店铺 / 仓库 / 承运商"
              onChange={(e: { target: { value: string } }) => {
                setKeyword(e.target.value);
                setPage(0);
              }}
            />
          </FormField>
          <SelectField
            id="shipSlaState"
            label="发货时限"
            value={shipSlaState}
            onChange={(v) => {
              setShipSlaState(v);
              setPage(0);
            }}
            options={[
              { value: '', label: '全部' },
              { value: 'ON_TIME', label: '按时发货' },
              { value: 'OVERDUE', label: '超出发货时限' },
              { value: 'UNSET', label: '未登记发货时限' },
            ]}
          />
          <SelectField
            id="promiseState"
            label="承诺 vs 实际"
            value={promiseState}
            onChange={(v) => {
              setPromiseState(v);
              setPage(0);
            }}
            options={[
              { value: '', label: '全部' },
              { value: 'MET', label: '达成承诺' },
              { value: 'LATE', label: '超出承诺' },
              { value: 'NO_PROMISE', label: '未登记承诺到货' },
              { value: 'NO_ACTUAL', label: '未登记实际到货' },
            ]}
          />
        </div>

        <div className="text-muted-foreground mt-3 text-xs">
          共 {facts?.total ?? 0} 票，其中 {facts?.summary.withOrderedAt ?? 0} 票有下单时刻、
          {facts?.summary.withPackedAt ?? 0} 票有出库时刻、
          {facts?.summary.shipSlaUnset ?? 0} 票未登记发货时限、
          {facts?.summary.withoutOrderSnapshot ?? 0} 票没有订单快照（进不了商品维度）。
        </div>

        {facts && facts.items.length === 0 ? (
          <EmptyState title="没有匹配的事实" description="放宽筛选条件再试。" />
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="p-2">事实号</th>
                  <th className="p-2">销售订单</th>
                  <th className="p-2">下单→出库</th>
                  <th className="p-2">出库→交接</th>
                  <th className="p-2">发货时限</th>
                  <th className="p-2">承诺 vs 实际</th>
                </tr>
              </thead>
              <tbody>
                {(facts?.items ?? []).map((row) => (
                  <tr
                    key={row.id}
                    className="cursor-pointer border-b hover:bg-muted/50"
                    onClick={() => void openDetail(row.id)}
                  >
                    <td className="p-2 font-mono text-xs">{row.factNo}</td>
                    <td className="p-2">{row.salesOrderNo || '（无订单快照）'}</td>
                    <td className="p-2">{formatDuration(row.orderToPackSeconds)}</td>
                    <td className="p-2">{formatDuration(row.packToHandoverSeconds)}</td>
                    <td className="p-2">
                      <Badge tone={slaTone(row.shipSlaState)}>{slaLabel(row.shipSlaState)}</Badge>
                      {row.shipOverdueSeconds !== null && (
                        <span className="ml-1 text-xs">
                          超 {formatDuration(row.shipOverdueSeconds)}
                        </span>
                      )}
                    </td>
                    <td className="p-2">
                      <Badge tone={promiseTone(row.promiseVsDeliveryState)}>
                        {promiseLabel(row.promiseVsDeliveryState)}
                      </Badge>
                      {row.promiseVsDeliverySeconds !== null && (
                        <span className="ml-1 text-xs">
                          {formatDuration(row.promiseVsDeliverySeconds)}
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="mt-3 flex items-center gap-3">
          <Button
            variant="outline"
            size="sm"
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
          >
            上一页
          </Button>
          <span className="text-muted-foreground text-xs">
            第 {(facts?.page ?? 0) + 1} / {facts?.totalPages ?? 1} 页
          </span>
          <Button
            variant="outline"
            size="sm"
            disabled={page + 1 >= (facts?.totalPages ?? 1)}
            onClick={() => setPage((p) => p + 1)}
          >
            下一页
          </Button>
        </div>
      </SectionCard>

      {/* 7. 明细与登记 */}
      {detail && (
        <SectionCard
        title={`时效明细 ${detail.fact.factNo}`}
        extra={<span className="text-muted-foreground"><Clock className="size-4" /></span>}
      >
          <div className="grid gap-3 sm:grid-cols-3">
            <div>
              <p className="text-muted-foreground text-xs">下单时刻</p>
              <p className="text-sm">{detail.fact.orderedAt ?? '未登记（草稿单）'}</p>
            </div>
            <div>
              <p className="text-muted-foreground text-xs">出库时刻</p>
              <p className="text-sm">{detail.fact.packedAt ?? '未测出（9.4.1.1 未同步）'}</p>
            </div>
            <div>
              <p className="text-muted-foreground text-xs">交接时刻</p>
              <p className="text-sm">{detail.fact.handedOverAt}</p>
            </div>
          </div>

          <h4 className="mt-4 text-sm font-medium">承诺到货登记</h4>
          <p className="text-muted-foreground text-xs">
            真实来源是平台 SLA 面（2.5.1.1 未交付），本模块<b>登记</b>而不是读取。
          </p>
          <div className="mt-2 flex flex-wrap items-end gap-2">
            <FormField id="promise-at" label="承诺到货时刻">
              <Input
                type="datetime-local"
                value={promiseAt}
                onChange={(e: { target: { value: string } }) => setPromiseAt(e.target.value)}
              />
            </FormField>
            <FormField id="promise-note" label="依据">
              <Input
                value={promiseNote}
                onChange={(e: { target: { value: string } }) => setPromiseNote(e.target.value)}
              />
            </FormField>
            <Button onClick={() => void submitPromise()} disabled={!promiseAt}>
              登记承诺
            </Button>
          </div>

          <h4 className="mt-4 text-sm font-medium">实际到货登记</h4>
          <p className="text-muted-foreground text-xs">
            真实来源是承运商轨迹的签收事件（10.3 未交付），本模块<b>登记</b>人工上报，
            不抓取轨迹。
          </p>
          <div className="mt-2 flex flex-wrap items-end gap-2">
            <FormField id="delivered-at" label="签收时刻">
              <Input
                type="datetime-local"
                value={deliveredAt}
                onChange={(e: { target: { value: string } }) => setDeliveredAt(e.target.value)}
              />
            </FormField>
            <FormField id="delivery-note" label="备注">
              <Input
                value={deliveryNote}
                onChange={(e: { target: { value: string } }) => setDeliveryNote(e.target.value)}
              />
            </FormField>
            <Button onClick={() => void submitDelivery()} disabled={!deliveredAt}>
              登记到货
            </Button>
          </div>

          {detail.orderLines.length > 0 && (
            <>
              <h4 className="mt-4 text-sm font-medium">订单行（商品维度来源）</h4>
              <ul className="mt-2 list-disc pl-5 text-sm">
                {detail.orderLines.map((l) => (
                  <li key={l.skuId}>
                    {l.skuCode} · {l.skuName} × {l.quantity}
                  </li>
                ))}
              </ul>
            </>
          )}

          <h4 className="mt-4 text-sm font-medium">口径边界</h4>
          <ul className="text-muted-foreground mt-2 list-disc pl-5 text-xs">
            {detail.boundaries.map((b) => (
              <li key={b}>{b}</li>
            ))}
          </ul>
        </SectionCard>
      )}

      {/* 8. 事件流 */}
      <SectionCard title="事件流">
        {events && events.items.length === 0 ? (
          <EmptyState title="还没有事件" description="同步或登记一次就会出现记录。" />
        ) : (
          <ul className="flex flex-col gap-1 text-sm">
            {(events?.items ?? []).map((e) => (
              <li key={e.seq} className="border-b py-1">
                <span className="text-muted-foreground text-xs">{e.occurredAt}</span>{' '}
                <span className={`text-xs ${TONE_CLASS.ok}`}>{e.kind}</span> {e.summary}
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
}
