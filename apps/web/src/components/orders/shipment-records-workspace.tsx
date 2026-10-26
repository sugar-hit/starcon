'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AlertTriangle, Filter, PackageCheck, RefreshCw, Truck } from 'lucide-react';
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

/**
 * 朴素下拉框。
 *
 * <p>刻意不套 {@code FormField}：那个组件的 children 被约束成 input，
 * 而分组维度与状态都是<b>枚举</b> —— 用 input 画下拉会得到一个只能手打、
 * 还能打错枚举值的控件。
 */
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

import type {
  ShipmentCallbackRow,
  ShipmentPerformanceReport,
  ShipmentRecordBoard,
  ShipmentRecordCapabilities,
  ShipmentRecordDetail,
  ShipmentRecordPage,
  ShipmentRecordRow,
  ShipmentTrackingObservationRow,
  ShipmentVisibilityRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  buyerVisibleLabel,
  callbackGapNote,
  callbackSeverity,
  changeShipmentRecordStatus,
  errorText,
  fetchShipmentPerformance,
  fetchShipmentRecord,
  fetchShipmentRecordBoard,
  fetchShipmentRecordCapabilities,
  fetchShipmentRecords,
  isTerminal,
  nextStatuses,
  percentText,
  platformCallbackLabel,
  registerBuyerVisibility,
  registerPlatformCallback,
  registerTrackingObservation,
  shipmentRecordStatusLabel,
  slaVerdict,
  summaryTiles,
  syncShipmentRecords,
  trackingRateSeverity,
  trackingSeverity,
  trackingStatusLabel,
} from './shipment-records-model';
import { ListPreferenceBar } from '../common/list-preference-bar';
import { useListPreferences } from '../../lib/use-list-preferences';
import type { ListColumn } from '../../lib/list-preferences-model';

const PAGE_SIZE = 50;

/**
 * 1.4.1.2 列表与表格偏好 —— 发货记录表的**列定义**。
 *
 * <p>最后一列是「明细」按钮（每行一个出口），不进偏好：它是这张表的入口，不该被关掉。
 */
const RECORD_COLUMNS: ListColumn[] = [
  { key: 'recordNo', label: '记录号' },
  { key: 'carrier', label: '承运商' },
  { key: 'waybill', label: '运单号' },
  { key: 'shippedAt', label: '发货时间' },
  { key: 'sla', label: '时限判定' },
  { key: 'tracking', label: '轨迹' },
  { key: 'buyerVisible', label: '买家可见' },
  { key: 'callback', label: '平台回传' },
  { key: 'status', label: '状态' },
];

/** 排序键就是**后端排序白名单里的 token**（见 trade 的 ShipmentRecordService.orderBy）。 */
const RECORD_SORTS: ListColumn[] = [
  { key: 'SHIPPED_AT_DESC', label: '发货时间（新→旧）' },
  { key: 'SHIPPED_AT_ASC', label: '发货时间（旧→新）' },
  { key: 'RECORD_NO_ASC', label: '记录号（小→大）' },
  { key: 'CARRIER_CODE_ASC', label: '承运商代码（小→大）' },
  { key: 'PARCEL_COUNT_DESC', label: '包裹数（多→少）' },
  { key: 'WEIGHT_DESC', label: '毛重（重→轻）' },
];

/**
 * 可以被记住的筛选条件：**只有枚举与编号**。
 *
 * <p>「关键字」是自由文本（可能含 PII），刻意**不**进白名单。
 */
const PERSISTABLE_FILTERS = [
  'groupBy',
  'carrierCode',
  'trackingStatus',
  'platformCallbackStatus',
] as const;

const GROUP_BY_OPTIONS = [
  { value: 'CARRIER', label: '按承运商' },
  { value: 'TRACKING', label: '按轨迹状态' },
  { value: 'PLATFORM_CALLBACK', label: '按平台回传状态' },
];

const TRACKING_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'UNSET', label: '未登记轨迹' },
  { value: 'NO_TRACE', label: '承运商无轨迹' },
  { value: 'PICKED_UP', label: '已揽收' },
  { value: 'IN_TRANSIT', label: '运输中' },
  { value: 'CUSTOMS', label: '清关中' },
  { value: 'OUT_FOR_DELIVERY', label: '派送中' },
  { value: 'DELIVERED', label: '已送达' },
  { value: 'EXCEPTION', label: '轨迹异常' },
  { value: 'RETURNED', label: '已退回' },
];

const CALLBACK_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'UNSET', label: '未登记（漏了）' },
  { value: 'PENDING', label: '待回传' },
  { value: 'SUBMITTED', label: '已提交平台' },
  { value: 'ACKNOWLEDGED', label: '平台已受理' },
  { value: 'REJECTED', label: '平台拒绝' },
  { value: 'SKIPPED', label: '决定不回传' },
];

const VISIBILITY_OPTIONS = [
  { value: 'VISIBLE', label: '买家可见' },
  { value: 'HIDDEN', label: '对买家隐藏' },
  { value: 'PUBLISHED', label: '已向买家发布' },
];

const TONE_CLASS: Record<string, string> = {
  danger: 'bg-red-100 text-red-800',
  warn: 'bg-amber-100 text-amber-800',
  muted: 'bg-slate-100 text-slate-700',
  normal: 'bg-emerald-100 text-emerald-800',
};

function Badge({ severity, children }: { severity: string; children: React.ReactNode }) {
  return (
    <span
      className={`inline-flex shrink-0 rounded px-1.5 py-0.5 text-xs ${
        TONE_CLASS[severity] ?? TONE_CLASS.normal
      }`}
    >
      {children}
    </span>
  );
}

function moment(value: string | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').replace(/\+.*$/, '').replace('Z', '');
}

/**
 * 9.4.1.2 发货记录工作区。
 *
 * <p>四个区块按「会不会掉绩效」排：**绩效口径 → 分组看板 → 发货记录清单 → 记录明细**，
 * 外加能力声明与同步入口。
 *
 * <p>三列状态（轨迹 / 买家可见 / 平台回传）在当前集成头里都没有可读上游，
 * 所以界面上它们**全部显示为带出处的「未登记」**，而不是留空或显示成「正常」：
 * 留空会被读成「没问题」，而真实情况是这一列还没有数据源。
 *
 * <p>加载态 / 空态 / 错误态三态齐全：空清单要区分「一条发货记录都没有」与
 * 「还没从 8.3.4.1 同步过」—— 后者给的是同步按钮，不是空白页。
 */
export function ShipmentRecordsWorkspace() {
  const [capabilities, setCapabilities] = useState<ShipmentRecordCapabilities | null>(null);
  const [board, setBoard] = useState<ShipmentRecordBoard | null>(null);
  const [page, setPage] = useState<ShipmentRecordPage | null>(null);
  const [performance, setPerformance] = useState<ShipmentPerformanceReport | null>(null);
  const [detail, setDetail] = useState<ShipmentRecordDetail | null>(null);

  const [groupBy, setGroupBy] = useState('CARRIER');
  const [carrierCode, setCarrierCode] = useState('');
  const [trackingStatus, setTrackingStatus] = useState('');
  const [platformCallbackStatus, setPlatformCallbackStatus] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [trackingDraft, setTrackingDraft] = useState('IN_TRANSIT');
  const [trackingRef, setTrackingRef] = useState('');
  const [trackingObservedAt, setTrackingObservedAt] = useState('');
  const [visibilityDraft, setVisibilityDraft] = useState('VISIBLE');
  const [visibilityNote, setVisibilityNote] = useState('');
  const [callbackDraft, setCallbackDraft] = useState('PENDING');
  const [callbackNote, setCallbackNote] = useState('');
  const [statusDraft, setStatusDraft] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  // 1.4.1.2 列表与表格偏好：这一页记「列显示 + 排序 + 每页条数 + 分组维度/承运商/轨迹/回传状态」。
  const prefs = useListPreferences({
    pageKey: 'orders.shipment-records',
    columns: RECORD_COLUMNS,
    sortOptions: RECORD_SORTS,
    pageSize: PAGE_SIZE,
    pageSizeOptions: [10, 20, 50, 100],
    filters: { groupBy: 'CARRIER' },
    persistableFilterKeys: PERSISTABLE_FILTERS,
  });
  const columnVisible = (key: string) => prefs.state.columns.includes(key);
  /** 恢复出来的视图状态只在「恢复完成」那一刻应用一次，之后由用户自己的操作驱动。 */
  const restored = useRef(false);
  useEffect(() => {
    if (!prefs.ready || restored.current) return;
    restored.current = true;
    const restoredFilters = prefs.state.filters;
    setGroupBy(restoredFilters.groupBy ?? 'CARRIER');
    setCarrierCode(restoredFilters.carrierCode ?? '');
    setTrackingStatus(restoredFilters.trackingStatus ?? '');
    setPlatformCallbackStatus(restoredFilters.platformCallbackStatus ?? '');
  }, [prefs.ready, prefs.state.filters]);
  // 每页条数变了以后，原来的页码可能已经越界 —— 回到第一页，而不是停在空白页上。
  useEffect(() => {
    setPageIndex(0);
  }, [prefs.state.pageSize]);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, boardData, pageData, perf] = await Promise.all([
        fetchShipmentRecordCapabilities(),
        fetchShipmentRecordBoard({ groupBy, carrierCode, trackingStatus, platformCallbackStatus }),
        fetchShipmentRecords({
          carrierCode,
          trackingStatus,
          platformCallbackStatus,
          keyword,
          page: pageIndex,
          size: prefs.state.pageSize,
          sort: prefs.state.sortKey,
        }),
        fetchShipmentPerformance({ carrierCode, trackingStatus, platformCallbackStatus, keyword }),
      ]);
      setCapabilities(caps);
      setBoard(boardData);
      setPage(pageData);
      setPerformance(perf);
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setLoading(false);
    }
  }, [
    groupBy,
    carrierCode,
    trackingStatus,
    platformCallbackStatus,
    keyword,
    pageIndex,
    prefs.state.pageSize,
    prefs.state.sortKey,
  ]);

  useEffect(() => {
    void load();
  }, [load]);

  const records: ShipmentRecordRow[] = page?.items ?? [];
  const tiles = useMemo(
    () => (page ? summaryTiles(page.summary) : []),
    [page],
  );

  async function run(action: () => Promise<string>) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      setNotice(await action());
      await load();
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  async function openDetail(recordId: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchShipmentRecord(recordId));
    } catch (cause) {
      setError(errorText(cause));
    } finally {
      setBusy(false);
    }
  }

  function selectedRecordId(): string | null {
    return detail?.record.id ?? null;
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="发货记录"
        description="9.4.1.2 · 包裹 / 运单号 / 承运商 / 发货时间 / 轨迹状态 / 买家可见状态 / 平台回传状态"
        actions={
          <Button onClick={() => void load()} disabled={loading || busy}>
            <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
          </Button>
        }
      />

      {error ? (
        <Notice tone="danger" title="操作失败">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力与上游可用性">
          <ul className="space-y-2 text-sm">
            {capabilities.dimensions.map((dimension) => (
              <li key={dimension.dimension} className="flex items-start gap-2">
                <span
                  className={
                    dimension.available
                      ? 'mt-0.5 inline-flex shrink-0 rounded px-1.5 py-0.5 text-xs bg-emerald-100 text-emerald-800'
                      : 'mt-0.5 inline-flex shrink-0 rounded px-1.5 py-0.5 text-xs bg-amber-100 text-amber-800'
                  }
                >
                  {dimension.available ? '可用' : '未交付'}
                </span>
                <span>
                  <strong>{dimension.dimension}</strong> · {dimension.description}
                  <br />
                  <span className="text-muted-foreground">
                    来源：{dimension.source}
                    {dimension.ownerModule ? ` · 归属模块：${dimension.ownerModule}` : ''}
                  </span>
                  {dimension.impact ? (
                    <div className="text-muted-foreground">{dimension.impact}</div>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-3 text-xs text-muted-foreground">{capabilities.downstreamNote}</p>
        </SectionCard>
      ) : null}

      <SectionCard
        title="从 8.3.4.1 同步已确认交接的交接单"
        extra={<PackageCheck className="h-4 w-4" />}
      >
        <p className="text-sm text-muted-foreground">
          已发货的唯一来源是 warehouse 服务 8.3.4.1 已确认交接（status=HANDED_OVER）的交接单
          （跨库视图与跨服务 SQL 都被禁止）。发货时刻取上游 handover_at 快照，
          界面上<strong>不允许</strong>手填 —— 迟发率不能靠改时间凑。
        </p>
        <div className="mt-3">
          <Button
            disabled={busy}
            onClick={() =>
              void run(async () => {
                const result = await syncShipmentRecords({ pageSize: 100 });
                return `同步完成：读到 ${result.fetchedHandovers} 张交接单，新增 ${result.insertedRecords}，更新 ${result.updatedRecords}，跳过 ${result.skippedHandovers}，包裹 ${result.parcelRowsUpserted} 行；有订单快照 ${result.recordsWithOrderSnapshot}，无快照 ${result.recordsWithoutOrderSnapshot}，无时限 ${result.recordsWithoutSla}。`;
              })
            }
          >
            同步
          </Button>
        </div>
      </SectionCard>

      {performance ? (
        <SectionCard title="绩效口径" extra={<AlertTriangle className="h-4 w-4" />}>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="迟发率" value={percentText(performance.lateRate)} />
            <StatCard label="准时率" value={percentText(performance.onTimeRate)} />
            <StatCard
              label="有效追踪率"
              value={percentText(performance.validTrackingRate)}
            />
            <StatCard label="未登记时限（UNSET）" value={performance.unsetSlaRecords} />
          </div>
          <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
            <li>迟发率口径：{performance.lateRateBasis}</li>
            <li>有效追踪率口径：{performance.validTrackingRateBasis}</li>
            {performance.boundaryNotes.map((note) => (
              <li key={note}>· {note}</li>
            ))}
            <li className={TONE_CLASS[trackingRateSeverity(performance.validTrackingRate)]}>
              有效追踪率当前为 {percentText(performance.validTrackingRate)}
              （{performance.trackableRecords} 可追踪 / {performance.untrackableRecords} 不可追踪）
            </li>
          </ul>
        </SectionCard>
      ) : null}

      {tiles.length ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {tiles.map((tile) => (
            <div key={tile.label} title={tile.hint}>
              <StatCard label={tile.label} value={tile.value} />
              <p className="mt-1 text-xs text-muted-foreground">{tile.hint}</p>
            </div>
          ))}
        </div>
      ) : null}

      <SectionCard title="分组看板" extra={<Filter className="h-4 w-4" />}>
        <div className="flex flex-wrap items-end gap-3">
          <SelectField
            id="shipment-records-group-by"
            label="分组维度"
            value={groupBy}
            onChange={(value) => {
              setGroupBy(value);
              setPageIndex(0);
              prefs.setFilter('groupBy', value);
            }}
            options={GROUP_BY_OPTIONS}
          />
          <FormField id="shipment-records-carrier" label="承运商代码">
            <Input
              value={carrierCode}
              placeholder="留空为全部"
              onChange={(e) => {
                setCarrierCode(e.target.value);
                prefs.setFilter('carrierCode', e.target.value);
              }}
            />
          </FormField>
          <SelectField
            id="shipment-records-tracking"
            label="轨迹状态"
            value={trackingStatus}
            onChange={(value) => {
              setTrackingStatus(value);
              setPageIndex(0);
              prefs.setFilter('trackingStatus', value);
            }}
            options={TRACKING_OPTIONS}
          />
          <SelectField
            id="shipment-records-callback"
            label="平台回传状态"
            value={platformCallbackStatus}
            onChange={(value) => {
              setPlatformCallbackStatus(value);
              setPageIndex(0);
              prefs.setFilter('platformCallbackStatus', value);
            }}
            options={CALLBACK_OPTIONS}
          />
        </div>
        {board ? (
          board.groups.length ? (
            <table className="mt-4 w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2">分组</th>
                  <th className="py-2">记录数</th>
                  <th className="py-2">包裹数</th>
                  <th className="py-2">轨迹异常</th>
                </tr>
              </thead>
              <tbody>
                {board.groups.map((group) => (
                  <tr key={group.groupKey} className="border-b">
                    <td className="py-2">
                      <Badge
                        severity={
                          group.groupKey === 'UNSET' || group.groupKey === ''
                            ? 'muted'
                            : 'normal'
                        }
                      >
                        {group.groupLabel}
                      </Badge>
                    </td>
                    <td className="py-2">{group.recordCount}</td>
                    <td className="py-2">{group.parcelCount}</td>
                    <td className="py-2">
                      {group.exceptionCount > 0 ? (
                        <Badge severity="danger">{group.exceptionCount}</Badge>
                      ) : (
                        '0'
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="mt-4 text-sm text-muted-foreground">当前筛选下没有分组。</p>
          )
        ) : null}
      </SectionCard>

      <SectionCard title="发货记录清单" extra={<Truck className="h-4 w-4" />}>
        <ListPreferenceBar
          columns={RECORD_COLUMNS}
          sortOptions={RECORD_SORTS}
          pageSizeOptions={[10, 20, 50, 100]}
          prefs={prefs}
        />
        <div className="flex flex-wrap items-end gap-3">
          <FormField id="shipment-records-keyword" label="关键字（记录号 / 运单号 / 订单号 / 交接单号）">
            <Input
              value={keyword}
              placeholder="留空为全部"
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageIndex(0);
              }}
            />
          </FormField>
        </div>

        {loading ? (
          <p className="mt-4 text-sm text-muted-foreground">加载中…</p>
        ) : records.length === 0 ? (
          <EmptyState
            title="没有匹配的发货记录"
            description="若从未同步过，请先用上方按钮从 8.3.4.1 同步已确认交接的交接单 —— 已发货的唯一来源是那一个接口。"
          />
        ) : (
          <>
            <table className="mt-4 w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  {columnVisible('recordNo') && <th className="py-2">记录号</th>}
                  {columnVisible('carrier') && <th className="py-2">承运商</th>}
                  {columnVisible('waybill') && <th className="py-2">运单号</th>}
                  {columnVisible('shippedAt') && <th className="py-2">发货时间</th>}
                  {columnVisible('sla') && <th className="py-2">时限判定</th>}
                  {columnVisible('tracking') && <th className="py-2">轨迹</th>}
                  {columnVisible('buyerVisible') && <th className="py-2">买家可见</th>}
                  {columnVisible('callback') && <th className="py-2">平台回传</th>}
                  {columnVisible('status') && <th className="py-2">状态</th>}
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {records.map((row) => {
                  const verdict = slaVerdict(row.slaDeadlineAt, row.lateAgainstSla);
                  return (
                    <tr key={row.id} className="border-b">
                      {columnVisible('recordNo') && (
                        <td className="py-2">
                          <div>{row.recordNo}</div>
                          <div className="text-xs text-muted-foreground">
                            {row.salesOrderNo || '（无订单快照）'}
                          </div>
                        </td>
                      )}
                      {columnVisible('carrier') && (
                        <td className="py-2">{row.carrierName || row.carrierCode}</td>
                      )}
                      {columnVisible('waybill') && (
                        <td className="py-2">{row.masterWaybillNo}</td>
                      )}
                      {columnVisible('shippedAt') && (
                        <td className="py-2">{moment(row.shippedAt)}</td>
                      )}
                      {columnVisible('sla') && (
                        <td className="py-2">
                          <Badge severity={verdict.severity}>{verdict.label}</Badge>
                        </td>
                      )}
                      {columnVisible('tracking') && (
                        <td className="py-2">
                          <Badge severity={trackingSeverity(row.trackingStatus)}>
                            {trackingStatusLabel(row.trackingStatus)}
                          </Badge>
                        </td>
                      )}
                      {columnVisible('buyerVisible') && (
                        <td className="py-2">{buyerVisibleLabel(row.buyerVisibleStatus)}</td>
                      )}
                      {columnVisible('callback') && (
                        <td className="py-2">
                          <Badge severity={callbackSeverity(row.platformCallbackStatus)}>
                            {platformCallbackLabel(row.platformCallbackStatus)}
                          </Badge>
                        </td>
                      )}
                      {columnVisible('status') && (
                        <td className="py-2">{shipmentRecordStatusLabel(row.status)}</td>
                      )}
                      <td className="py-2">
                        <Button size="sm" onClick={() => void openDetail(row.id)} disabled={busy}>
                          明细
                        </Button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            <div className="mt-3 flex items-center gap-2 text-sm">
              <Button disabled={pageIndex <= 0} onClick={() => setPageIndex((p) => Math.max(0, p - 1))}>
                上一页
              </Button>
              <span>
                第 {pageIndex + 1} / {Math.max(1, page?.totalPages ?? 1)} 页 · 共{' '}
                {page?.totalElements ?? 0} 条
              </span>
              <Button
                disabled={pageIndex + 1 >= (page?.totalPages ?? 1)}
                onClick={() => setPageIndex((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </>
        )}
      </SectionCard>

      {detail ? (
        <SectionCard title={`记录明细 · ${detail.record.recordNo}`}>
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard label="包裹" value={detail.record.parcelCount} />
            <StatCard label="承运商" value={detail.record.carrierName || detail.record.carrierCode} />
            <StatCard label="运单号" value={detail.record.masterWaybillNo} />
            <StatCard label="发货时间" value={moment(detail.record.shippedAt)} />
          </div>

          <ul className="mt-3 space-y-1 text-xs text-muted-foreground">
            {detail.boundaryNotes.map((note) => (
              <li key={note}>· {note}</li>
            ))}
          </ul>

          <h4 className="mt-4 text-sm font-medium">包裹</h4>
          <table className="mt-2 w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">箱号</th>
                <th className="py-2">逐箱运单号</th>
                <th className="py-2">重量（克）</th>
                <th className="py-2">件量</th>
                <th className="py-2">轨迹</th>
                <th className="py-2">观测次数</th>
              </tr>
            </thead>
            <tbody>
              {detail.parcels.map((parcel) => (
                <tr key={parcel.id} className="border-b">
                  <td className="py-2">#{parcel.parcelNo}</td>
                  <td className="py-2">{parcel.waybillNo || '—'}</td>
                  <td className="py-2">{parcel.grossWeightG}</td>
                  <td className="py-2">{parcel.totalQuantity}</td>
                  <td className="py-2">
                    <Badge severity={trackingSeverity(parcel.currentTrackingStatus)}>
                      {trackingStatusLabel(parcel.currentTrackingStatus)}
                    </Badge>
                  </td>
                  <td className="py-2">{parcel.observationCount}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <h4 className="mt-4 text-sm font-medium">轨迹观测（append-only）</h4>
          {detail.tracking.length ? (
            <table className="mt-2 w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2">观测时刻</th>
                  <th className="py-2">箱号</th>
                  <th className="py-2">状态</th>
                  <th className="py-2">来源</th>
                  <th className="py-2">凭据</th>
                </tr>
              </thead>
              <tbody>
                {detail.tracking.map((observation: ShipmentTrackingObservationRow) => (
                  <tr key={observation.id} className="border-b">
                    <td className="py-2">{moment(observation.observedAt)}</td>
                    <td className="py-2">{observation.parcelId ? `#${observation.parcelNo}` : '整票'}</td>
                    <td className="py-2">{trackingStatusLabel(observation.status)}</td>
                    <td className="py-2">{observation.source}</td>
                    <td className="py-2">{observation.observationRef || observation.detail}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : (
            <p className="mt-2 text-sm text-muted-foreground">
              尚未登记任何轨迹观测（10.3 未交付）——「未登记」不等于「轨迹正常」。
            </p>
          )}

          <h4 className="mt-4 text-sm font-medium">买家可见状态</h4>
          {detail.visibility.length ? (
            <ul className="mt-2 space-y-1 text-sm">
              {detail.visibility.map((row: ShipmentVisibilityRow) => (
                <li key={row.id}>
                  {moment(row.registeredAt)} · {buyerVisibleLabel(row.status)} · {row.source} ·{' '}
                  {row.note}
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-2 text-sm text-muted-foreground">尚未登记（无可读上游）。</p>
          )}

          <h4 className="mt-4 text-sm font-medium">平台回传</h4>
          {detail.callbacks.length ? (
            <ul className="mt-2 space-y-1 text-sm">
              {detail.callbacks.map((row: ShipmentCallbackRow) => (
                <li key={row.id}>
                  {moment(row.registeredAt)} · {platformCallbackLabel(row.status)} · {row.source}
                  {row.requestRef ? ` · 请求 ${row.requestRef}` : ''}
                  {row.responseRef ? ` · 回执 ${row.responseRef}` : ''}
                  {row.note ? ` · ${row.note}` : ''}
                </li>
              ))}
            </ul>
          ) : (
            <p className="mt-2 text-sm text-muted-foreground">
              尚未登记（9.4.2.1 / 2.5.1.1 未交付）——这是「漏了」，与「决定不回传」不同。
            </p>
          )}

          <h4 className="mt-4 text-sm font-medium">时间线</h4>
          <ul className="mt-2 space-y-1 text-sm">
            {detail.timeline.map((event) => (
              <li key={event.id}>
                {moment(event.occurredAt)} · {event.eventKind}
                {event.toStatus ? ` → ${event.toStatus}` : ''} · {event.detail}
              </li>
            ))}
          </ul>

          {callbackGapNote(detail.record.platformCallbackStatus, undefined) ? (
            <p className="mt-3 text-sm text-muted-foreground">
              {callbackGapNote(detail.record.platformCallbackStatus, undefined)}
            </p>
          ) : null}

          <h4 className="mt-4 text-sm font-medium">登记</h4>
          {isTerminal(detail.record.status) ? (
            <p className="mt-2 text-sm text-muted-foreground">
              该记录已处于终态（{shipmentRecordStatusLabel(detail.record.status)}），
              不再接受状态登记 —— 它已是发生过的发货事实。
            </p>
          ) : (
            <div className="mt-2 flex flex-wrap items-end gap-3">
              <SelectField
                id="shipment-records-tracking-draft"
                label="轨迹观测"
                value={trackingDraft}
                onChange={setTrackingDraft}
                options={TRACKING_OPTIONS.filter((o) => o.value !== '' && o.value !== 'UNSET')}
              />
              <FormField id="shipment-records-tracking-ref" label="观测凭据（必填）">
                <Input value={trackingRef} onChange={(e) => setTrackingRef(e.target.value)} />
              </FormField>
              <FormField id="shipment-records-tracking-at" label="观测时刻（留空取现在）">
                <Input
                  value={trackingObservedAt}
                  placeholder="2026-03-03T08:00:00+08:00"
                  onChange={(e) => setTrackingObservedAt(e.target.value)}
                />
              </FormField>
              <Button
                disabled={busy || trackingRef.trim().length < 4 || !selectedRecordId()}
                onClick={() =>
                  void run(async () => {
                    const result = await registerTrackingObservation(selectedRecordId() as string, {
                      status: trackingDraft as never,
                      source: 'MANUAL_ATTESTATION',
                      // null = 整票级观测。逐箱观测要把 parcelId 指到某一箱 ——
                      // 真实轨迹是按运单号走的，一票多箱里往往只有一箱在途。
                      parcelId: null,
                      observationRef: trackingRef.trim(),
                      detail: '',
                      observedAt: trackingObservedAt
                        ? new Date(trackingObservedAt).toISOString()
                        : new Date().toISOString(),
                    });
                    setTrackingRef('');
                    return `已登记轨迹观测，当前轨迹状态：${trackingStatusLabel(
                      result.record.trackingStatus,
                    )}。`;
                  })
                }
              >
                登记轨迹
              </Button>

              <SelectField
                id="shipment-records-visibility-draft"
                label="买家可见状态"
                value={visibilityDraft}
                onChange={setVisibilityDraft}
                options={VISIBILITY_OPTIONS}
              />
              <FormField id="shipment-records-visibility-note" label="依据（人工登记必填）">
                <Input value={visibilityNote} onChange={(e) => setVisibilityNote(e.target.value)} />
              </FormField>
              <Button
                disabled={busy || visibilityNote.trim().length === 0 || !selectedRecordId()}
                onClick={() =>
                  void run(async () => {
                    const result = await registerBuyerVisibility(selectedRecordId() as string, {
                      status: visibilityDraft as never,
                      source: 'MANUAL_ATTESTATION',
                      note: visibilityNote.trim(),
                      registeredAt: new Date().toISOString(),
                    });
                    setVisibilityNote('');
                    return `已登记买家可见状态：${buyerVisibleLabel(
                      result.record.buyerVisibleStatus,
                    )}。`;
                  })
                }
              >
                登记买家可见
              </Button>

              <SelectField
                id="shipment-records-callback-draft"
                label="平台回传状态"
                value={callbackDraft}
                onChange={setCallbackDraft}
                options={CALLBACK_OPTIONS.filter((o) => o.value !== '' && o.value !== 'UNSET')}
              />
              <FormField id="shipment-records-callback-note" label="说明（SKIPPED 必填原因）">
                <Input value={callbackNote} onChange={(e) => setCallbackNote(e.target.value)} />
              </FormField>
              <Button
                disabled={
                  busy ||
                  !selectedRecordId() ||
                  (callbackDraft === 'SKIPPED' && callbackNote.trim().length === 0)
                }
                onClick={() =>
                  void run(async () => {
                    const result = await registerPlatformCallback(selectedRecordId() as string, {
                      status: callbackDraft as never,
                      source: 'MANUAL_ATTESTATION',
                      requestRef: '',
                      responseRef: '',
                      note: callbackNote.trim(),
                      registeredAt: new Date().toISOString(),
                    });
                    setCallbackNote('');
                    return `已登记平台回传状态：${platformCallbackLabel(
                      result.record.platformCallbackStatus,
                    )}。`;
                  })
                }
              >
                登记回传
              </Button>

              <SelectField
                id="shipment-records-status-draft"
                label="推进状态"
                value={statusDraft}
                onChange={setStatusDraft}
                options={[
                  { value: '', label: '选择目标状态' },
                  ...nextStatuses(detail.record.status).map((status) => ({
                    value: status,
                    label: shipmentRecordStatusLabel(status),
                  })),
                ]}
              />
              <FormField id="shipment-records-status-reason" label="变更依据（必填）">
                <Input value={callbackNote} onChange={(e) => setCallbackNote(e.target.value)} />
              </FormField>
              <Button
                disabled={busy || !statusDraft || callbackNote.trim().length < 4 || !selectedRecordId()}
                onClick={() =>
                  void run(async () => {
                    const result = await changeShipmentRecordStatus(selectedRecordId() as string, {
                      status: statusDraft as never,
                      reason: callbackNote.trim(),
                    });
                    setStatusDraft('');
                    setCallbackNote('');
                    return `状态已推进为：${shipmentRecordStatusLabel(result.record.status)}。`;
                  })
                }
              >
                推进状态
              </Button>
            </div>
          )}
        </SectionCard>
      ) : null}
    </div>
  );
}
