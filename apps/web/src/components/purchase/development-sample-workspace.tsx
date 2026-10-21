'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { PackageSearch, Plus, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  DevelopmentSampleCapabilities,
  DevelopmentSampleCostView,
  DevelopmentSampleDetail,
  DevelopmentSampleMediaCandidateRow,
  DevelopmentSampleOptions,
  DevelopmentSampleOrderDetail,
  DevelopmentSampleOrderPage,
  DevelopmentSampleOrderRow,
  DevelopmentSamplePage,
  DevelopmentSampleRow,
  DevelopmentSampleSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  addDevelopmentSampleCostLine,
  allowedOrderTargetOptions,
  allowedSampleTargetOptions,
  capabilityGaps,
  confirmDevelopmentSample,
  confirmationNeedsIssueNote,
  confirmationResultForTarget,
  confirmationSummary,
  costByCurrencyText,
  costLineSummary,
  createDevelopmentSample,
  createDevelopmentSampleMedia,
  createDevelopmentSampleOrder,
  createDevelopmentSampleShipment,
  deleteDevelopmentSampleCostLine,
  expensePostedText,
  fetchDevelopmentSample,
  fetchDevelopmentSampleCapabilities,
  fetchDevelopmentSampleCost,
  fetchDevelopmentSampleLedger,
  fetchDevelopmentSampleMediaCandidates,
  fetchDevelopmentSampleOptions,
  fetchDevelopmentSampleOrder,
  fetchDevelopmentSampleOrders,
  fetchDevelopmentSampleProjectOptions,
  fetchDevelopmentSampleStallOptions,
  fetchDevelopmentSampleSummary,
  fetchDevelopmentSampleVendorOptions,
  fmt,
  isConfirmationTarget,
  money,
  orderStatusTone,
  orderTransitionSummary,
  sampleStatusTone,
  timeText,
  trackingText,
  transitionDevelopmentSample,
  transitionDevelopmentSampleOrder,
  transitionSummary,
  type DevelopmentSampleConfirmationResult,
  type DevelopmentSampleCostType,
  type DevelopmentSampleLogisticsStatus,
  type DevelopmentSampleOrderStatus,
  type DevelopmentSampleSourceType,
  type DevelopmentSampleStatus,
} from './development-sample-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.6.1.2 打样与样品管理工作区。
 *
 * <p>蓝图：「管理打样与样品：样品申请、样品来源（供应商/自有产）、样品物流、确认结果（通过/修改/放弃）、
 * 样品成本；与达人寄样（14.5.1.2）区分打样与营销样品。」输出「打样单 + 样品台账 + 确认记录；样品成本」。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>打样单与样品台账分开列</b>：打样单是**一次打样活动**，样品台账是**一件样品**。
 *       两个页签分别看，详情里再从单子进到样品。</li>
 *   <li><b>打样与达人寄样分得开</b>：来源只有供应商 / 自有产 / 档口，用途只有「开发打样」。
 *       页面**不提供「其它」**——留一个「其它」桶，营销寄样就会被塞进来。</li>
 *   <li><b>没做到的必须写在页面上</b>：样品成本未进费用归集（归 12 费用）、
 *       真实物流轨迹不可查（归 10 物流）、试产衔接未交付（6.6.2.1）—— 常驻横幅。</li>
 *   <li><b>成本不跨币种相加</b>：概览逐币种列，不给一个假的「总成本」。</li>
 *   <li><b>状态只给合法的去向</b>：下拉来自后端的 allowedTargets，页面不维护第二份状态机。</li>
 *   <li><b>确认结果与制作进度分两个入口</b>：确认（通过 / 修改 / 放弃）必须带一条不可改写的确认记录；
 *       「修改」还必须写明修改意见。</li>
 *   <li>加载 / 空 / 错误三态齐全。</li>
 * </ul>
 */

const PAGE_SIZE = 20;

/** 建单时的来源下拉（**没有「其它」**）。 */
const SOURCE_TYPES: { value: DevelopmentSampleSourceType; label: string }[] = [
  { value: 'SUPPLIER', label: '供应商打样' },
  { value: 'OWN_FACTORY', label: '自有产打样' },
  { value: 'STALL', label: '档口打样' },
];

const CONFIRMATION_RESULTS: { value: DevelopmentSampleConfirmationResult; label: string }[] = [
  { value: 'PASSED', label: '通过' },
  { value: 'MODIFY', label: '修改' },
  { value: 'ABANDONED', label: '放弃' },
];

const COST_TYPES: { value: DevelopmentSampleCostType; label: string }[] = [
  { value: 'SAMPLE_FEE', label: '打样费' },
  { value: 'MATERIAL', label: '物料费' },
  { value: 'TOOLING', label: '模具费' },
  { value: 'FREIGHT', label: '物流费' },
  { value: 'INSPECTION', label: '检品费' },
];

const LOGISTICS_STATUSES: { value: DevelopmentSampleLogisticsStatus; label: string }[] = [
  { value: 'SHIPPED', label: '已发出' },
  { value: 'IN_TRANSIT', label: '运输中' },
  { value: 'DELIVERED', label: '已签收' },
  { value: 'EXCEPTION', label: '异常' },
  { value: 'RETURNED', label: '已退回' },
];

export function DevelopmentSampleWorkspace() {
  const [tab, setTab] = useState<'orders' | 'ledger'>('orders');
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [sourceType, setSourceType] = useState('');
  const [page, setPage] = useState(0);

  const [orders, setOrders] = useState<DevelopmentSampleOrderPage | null>(null);
  const [ledger, setLedger] = useState<DevelopmentSamplePage | null>(null);
  const [caps, setCaps] = useState<DevelopmentSampleCapabilities | null>(null);
  const [options, setOptions] = useState<DevelopmentSampleOptions | null>(null);
  const [summary, setSummary] = useState<DevelopmentSampleSummary | null>(null);

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [creating, setCreating] = useState(false);
  const [orderDetail, setOrderDetail] = useState<DevelopmentSampleOrderDetail | null>(null);
  const [sampleDetail, setSampleDetail] = useState<DevelopmentSampleDetail | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (tab === 'orders') {
        setOrders(
          await fetchDevelopmentSampleOrders({
            page,
            size: PAGE_SIZE,
            ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
            ...(status ? { status } : {}),
            ...(sourceType ? { sourceType } : {}),
          }),
        );
      } else {
        setLedger(
          await fetchDevelopmentSampleLedger({
            page,
            size: PAGE_SIZE,
            ...(keyword.trim() ? { keyword: keyword.trim() } : {}),
            ...(status ? { status } : {}),
            ...(sourceType ? { sourceType } : {}),
          }),
        );
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [tab, page, keyword, status, sourceType]);

  const loadSummary = useCallback(async () => {
    try {
      setSummary(await fetchDevelopmentSampleSummary());
    } catch {
      // 概览拿不到不该让整页空掉：清单仍然可用。
      setSummary(null);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchDevelopmentSampleCapabilities());
      } catch {
        // capabilities 拿不到不该让整页空掉：页面不假装自己知道上下游。
        setCaps(null);
      }
      try {
        setOptions(await fetchDevelopmentSampleOptions());
      } catch {
        setOptions(null);
      }
    })();
    void loadSummary();
  }, [loadSummary]);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setBusy(true);
      setError(null);
      try {
        await work();
        await load();
        await loadSummary();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [load, loadSummary],
  );

  const openOrder = useCallback(async (id: string) => {
    try {
      setOrderDetail(await fetchDevelopmentSampleOrder(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const openSample = useCallback(async (orderId: string, sampleId: string) => {
    try {
      setSampleDetail(await fetchDevelopmentSample(orderId, sampleId));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const gaps = useMemo(() => capabilityGaps(caps), [caps]);

  const stats = useMemo(() => {
    const orderCounts = summary?.orderStatuses ?? [];
    const sampleCounts = summary?.sampleStatuses ?? [];
    const orderBy = (value: string) =>
      orderCounts.find((item) => item.status === value)?.count ?? 0;
    const sampleBy = (value: string) =>
      sampleCounts.find((item) => item.status === value)?.count ?? 0;
    return {
      totalOrders: summary?.totalOrders ?? 0,
      totalSamples: summary?.totalSamples ?? 0,
      inProgress: orderBy('IN_PROGRESS'),
      confirmed: sampleBy('CONFIRMED'),
      revising: sampleBy('REVISING'),
    };
  }, [summary]);

  const orderRows = orders?.rows ?? [];
  const ledgerRows = ledger?.rows ?? [];
  const total = tab === 'orders' ? (orders?.total ?? 0) : (ledger?.total ?? 0);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="flex flex-col gap-4">
      <PageHeader
        title="打样与样品管理"
        description="管理打样与样品：样品申请、样品来源（供应商 / 自有产 / 档口）、样品物流、确认结果（通过 / 修改 / 放弃）、样品成本。打样单是一次打样活动，样品台账是一件样品。"
        actions={
          <div className="flex gap-2">
            <Button variant="ghost" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
            <Button onClick={() => setCreating(true)} disabled={busy}>
              <Plus />
              新建打样单
            </Button>
          </div>
        }
      />

      {/* 口径常驻：这一页的数从哪来、缺什么，必须一直摆在眼前 */}
      <div className="flex items-start gap-2 rounded-md border border-sky-500/40 bg-sky-500/10 p-3 text-sm">
        <PackageSearch className="mt-0.5 size-4 shrink-0" />
        <div className="flex flex-col gap-1">
          <p className="font-medium">打样与营销样品分开：本页只管开发打样</p>
          <p className="text-muted-foreground">
            达人寄样（14.5.1.2）属 14 卷，是营销样品，**不在本模块** —— 混进来会让营销费用污染开发成本，
            新品利润就被高估了。来源只有供应商 / 自有产 / 档口，用途只有「开发打样」。
          </p>
          {caps && <p className="text-muted-foreground">{caps.sourceUsageNote}</p>}
          {gaps.map((gap) => (
            <p key={gap} className="text-muted-foreground">
              🔴 {gap}
            </p>
          ))}
        </div>
      </div>

      {summary && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <StatCard label="打样单总数" value={stats.totalOrders} />
          <StatCard label="进行中" value={stats.inProgress} />
          <StatCard label="样品总数" value={stats.totalSamples} />
          <StatCard label="已确认样品" value={stats.confirmed} />
          <StatCard label="修改中样品" value={stats.revising} />
        </div>
      )}

      <SectionCard title="筛选">
        <div className="flex flex-wrap items-end gap-3">
          <div className="flex gap-1">
            <Button
              variant={tab === 'orders' ? 'default' : 'ghost'}
              onClick={() => {
                setTab('orders');
                setPage(0);
              }}
            >
              打样单
            </Button>
            <Button
              variant={tab === 'ledger' ? 'default' : 'ghost'}
              onClick={() => {
                setTab('ledger');
                setPage(0);
              }}
            >
              样品台账
            </Button>
          </div>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">关键字</span>
            <Input
              value={keyword}
              placeholder={tab === 'orders' ? '单号 / 立项编号 / 供应商' : '样品编号 / 规格 / 立项'}
              onChange={(e) => setKeyword(e.target.value)}
              className="w-64"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">状态</span>
            <select
              className="h-9 rounded-md border px-2"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {(tab === 'orders' ? (options?.orderStatuses ?? []) : (options?.sampleStatuses ?? [])).map(
                (item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ),
              )}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">来源</span>
            <select
              className="h-9 rounded-md border px-2"
              value={sourceType}
              onChange={(e) => {
                setSourceType(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {(options?.sourceTypes ?? []).map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <Button
            onClick={() => {
              setPage(0);
              void load();
            }}
          >
            查询
          </Button>
        </div>
        {summary && summary.costByCurrency.length > 0 && (
          <p className="text-muted-foreground mt-2 text-sm">
            样品成本合计（**按币种分开，不跨币种相加**）：{costByCurrencyText(summary.costByCurrency)}
          </p>
        )}
      </SectionCard>

      {error && (
        <div className="flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && (
        <p className="text-muted-foreground text-sm">
          正在加载{tab === 'orders' ? '打样单' : '样品台账'}…
        </p>
      )}

      {!loading && tab === 'orders' && orderRows.length === 0 && !error && (
        <EmptyState
          title="还没有打样单"
          description="当前筛选条件下没有打样单。先点右上角「新建打样单」开一张，或放宽筛选条件。"
        />
      )}

      {!loading && tab === 'ledger' && ledgerRows.length === 0 && !error && (
        <EmptyState
          title="还没有样品"
          description="样品台账是一件样品一行。打样单详情里登记第一个样品，或放宽筛选条件。"
        />
      )}

      {!loading && tab === 'orders' && orderRows.length > 0 && (
        <SectionCard title={`打样单清单（${orders?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">打样单号</th>
                  <th className="px-2 py-1">立项</th>
                  <th className="px-2 py-1">来源 / 用途</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">样品</th>
                  <th className="px-2 py-1">币种</th>
                  <th className="px-2 py-1">样品成本</th>
                  <th className="px-2 py-1">申请时刻</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {orderRows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-medium">{row.orderNo}</td>
                    <td className="px-2 py-1">
                      {row.projectName}
                      <span className="text-muted-foreground"> / {row.projectNo}</span>
                    </td>
                    <td className="px-2 py-1">
                      {row.sourceTypeLabel}
                      <span className="text-muted-foreground"> / {row.usageTypeLabel}</span>
                    </td>
                    <td className="px-2 py-1">
                      <Badge tone={orderStatusTone(row.status)}>{row.statusLabel}</Badge>
                    </td>
                    <td className="px-2 py-1">
                      {row.confirmedSampleCount}/{row.sampleCount} 已确认
                    </td>
                    <td className="px-2 py-1">{row.currency}</td>
                    <td className="px-2 py-1">{money(row.costTotal, row.currency)}</td>
                    <td className="px-2 py-1">{timeText(row.requestedAt)}</td>
                    <td className="px-2 py-1">
                      <Button variant="ghost" onClick={() => void openOrder(row.id)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {!loading && tab === 'ledger' && ledgerRows.length > 0 && (
        <SectionCard title={`样品台账（${ledger?.total ?? 0}）`}>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">样品编号</th>
                  <th className="px-2 py-1">打样单 / 立项</th>
                  <th className="px-2 py-1">规格</th>
                  <th className="px-2 py-1">数量</th>
                  <th className="px-2 py-1">状态</th>
                  <th className="px-2 py-1">轮次</th>
                  <th className="px-2 py-1">确认 / 物流 / 图片</th>
                  <th className="px-2 py-1">最新单号</th>
                  <th className="px-2 py-1" />
                </tr>
              </thead>
              <tbody>
                {ledgerRows.map((row) => (
                  <tr key={row.id} className="border-t">
                    <td className="px-2 py-1 font-medium">{row.sampleNo}</td>
                    <td className="px-2 py-1">
                      {row.projectName}
                      <span className="text-muted-foreground"> / {row.projectNo}</span>
                    </td>
                    <td className="px-2 py-1">
                      {row.specName ? `${row.specName} ${row.specValue}` : '未填规格'}
                    </td>
                    <td className="px-2 py-1">{fmt(row.quantity)}</td>
                    <td className="px-2 py-1">
                      <Badge tone={sampleStatusTone(row.status)}>{row.statusLabel}</Badge>
                    </td>
                    <td className="px-2 py-1">第 {row.revisionRound} 轮</td>
                    <td className="px-2 py-1">
                      {row.confirmationCount} / {row.shipmentCount} / {row.mediaCount}
                    </td>
                    <td className="px-2 py-1">{row.latestTrackingNo || '未发货'}</td>
                    <td className="px-2 py-1">
                      <Button
                        variant="ghost"
                        onClick={() => void openSample(row.orderId, row.id)}
                      >
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </SectionCard>
      )}

      {!loading && total > 0 && (
        <div className="flex items-center justify-end gap-2 text-sm">
          <span className="text-muted-foreground">
            第 {page + 1} / {pageCount} 页，共 {total} 条
          </span>
          <Button
            variant="ghost"
            disabled={page === 0}
            onClick={() => setPage((value) => Math.max(0, value - 1))}
          >
            上一页
          </Button>
          <Button
            variant="ghost"
            disabled={page + 1 >= pageCount}
            onClick={() => setPage((value) => value + 1)}
          >
            下一页
          </Button>
        </div>
      )}

      {creating && (
        <NewOrderModal
          onClose={() => setCreating(false)}
          busy={busy}
          onSubmit={async (input) => {
            await run(() => createDevelopmentSampleOrder(input));
            setCreating(false);
          }}
        />
      )}

      {orderDetail && (
        <OrderDetailModal
          detail={orderDetail}
          options={options}
          busy={busy}
          onClose={() => setOrderDetail(null)}
          onOpenSample={(sampleId) => {
            setOrderDetail(null);
            void openSample(orderDetail.order.id, sampleId);
          }}
          onTransition={async (toStatus, reason) => {
            await run(() =>
              transitionDevelopmentSampleOrder(orderDetail.order.id, { toStatus, reason, occurredAt: null }),
            );
            setOrderDetail(await fetchDevelopmentSampleOrder(orderDetail.order.id));
          }}
          onAddSample={async (input) => {
            await run(() => createDevelopmentSample(orderDetail.order.id, input));
            setOrderDetail(await fetchDevelopmentSampleOrder(orderDetail.order.id));
          }}
          onAddCostLine={async (input) => {
            await run(() => addDevelopmentSampleCostLine(orderDetail.order.id, input));
            setOrderDetail(await fetchDevelopmentSampleOrder(orderDetail.order.id));
          }}
          onDeleteCostLine={async (lineId) => {
            await run(() => deleteDevelopmentSampleCostLine(orderDetail.order.id, lineId));
            setOrderDetail(await fetchDevelopmentSampleOrder(orderDetail.order.id));
          }}
        />
      )}

      {sampleDetail && orderDetail && (
        <SampleDetailModal
          detail={sampleDetail}
          orderId={orderDetail.order.id}
          options={options}
          busy={busy}
          onClose={() => setSampleDetail(null)}
          onRefresh={async () => {
            setSampleDetail(await fetchDevelopmentSample(orderDetail.order.id, sampleDetail.sample.id));
          }}
          onAdvance={async (toStatus, reason) => {
            await run(() =>
              transitionDevelopmentSample(orderDetail.order.id, sampleDetail.sample.id, {
                toStatus,
                reason,
                occurredAt: null,
              }),
            );
            setSampleDetail(
              await fetchDevelopmentSample(orderDetail.order.id, sampleDetail.sample.id),
            );
          }}
          onConfirm={async (result, reason, issueNote) => {
            await run(() =>
              confirmDevelopmentSample(orderDetail.order.id, sampleDetail.sample.id, {
                result,
                reason,
                issueNote: issueNote || null,
                confirmedAt: null,
              }),
            );
            setSampleDetail(
              await fetchDevelopmentSample(orderDetail.order.id, sampleDetail.sample.id),
            );
          }}
          onAddShipment={async (input) => {
            await run(() =>
              createDevelopmentSampleShipment(orderDetail.order.id, sampleDetail.sample.id, input),
            );
            setSampleDetail(
              await fetchDevelopmentSample(orderDetail.order.id, sampleDetail.sample.id),
            );
          }}
          onAttachMedia={async (mediaRefId) => {
            await run(() =>
              createDevelopmentSampleMedia(orderDetail.order.id, sampleDetail.sample.id, {
                mediaRefId,
                note: null,
              }),
            );
            setSampleDetail(
              await fetchDevelopmentSample(orderDetail.order.id, sampleDetail.sample.id),
            );
          }}
        />
      )}
    </div>
  );
}

// ================================================================ 新建打样单

function NewOrderModal({
  onClose,
  onSubmit,
  busy,
}: {
  onClose: () => void;
  onSubmit: (input: {
    orderNo: string;
    developmentProjectId: string;
    sourceType: DevelopmentSampleSourceType;
    usageType: 'DEVELOPMENT';
    supplierRefId: string | null;
    supplierCode: string | null;
    supplierName: string | null;
    stallId: string | null;
    stallCode: string | null;
    stallName: string | null;
    currency: string;
    requesterId: string;
    requesterName: string;
    requestedAt: string;
    expectedDeliveryAt: string | null;
    purposeNote: string | null;
    note: string | null;
  }) => Promise<void>;
  busy: boolean;
}) {
  const [orderNo, setOrderNo] = useState('');
  const [projectId, setProjectId] = useState('');
  const [source, setSource] = useState<DevelopmentSampleSourceType>('SUPPLIER');
  const [supplierId, setSupplierId] = useState('');
  const [stallId, setStallId] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [requesterName, setRequesterName] = useState('');
  const [requestedAt, setRequestedAt] = useState(() => new Date().toISOString().slice(0, 16));
  const [expected, setExpected] = useState('');
  const [purpose, setPurpose] = useState('');
  const [error, setError] = useState<string | null>(null);

  const [projects, setProjects] = useState<
    { id: string; projectNo: string; name: string; status: string }[]
  >([]);
  const [vendors, setVendors] = useState<
    { roleId: string; partyCode: string; partyName: string }[]
  >([]);
  const [stalls, setStalls] = useState<{ id: string; code: string; name: string }[]>([]);
  const [loadingRefs, setLoadingRefs] = useState(true);

  useEffect(() => {
    void (async () => {
      setLoadingRefs(true);
      try {
        const [projectPage, vendorPage, stallPage] = await Promise.all([
          fetchDevelopmentSampleProjectOptions(),
          fetchDevelopmentSampleVendorOptions(),
          fetchDevelopmentSampleStallOptions(),
        ]);
        setProjects(
          projectPage.rows.map((row) => ({
            id: row.id,
            projectNo: row.projectNo,
            name: row.name,
            status: row.status,
          })),
        );
        setVendors(
          vendorPage.items.map((row) => ({
            roleId: row.roleId,
            partyCode: row.partyCode,
            partyName: row.partyName,
          })),
        );
        setStalls(
          stallPage.items.map((row) => ({ id: row.id, code: row.code, name: row.name })),
        );
      } catch (e) {
        setError(errorText(e));
      } finally {
        setLoadingRefs(false);
      }
    })();
  }, []);

  const submit = async () => {
    setError(null);
    if (!orderNo.trim()) {
      setError('打样单号必填（租户内唯一）');
      return;
    }
    if (!projectId) {
      setError('必须选择一个开发立项 —— 打样单挂在立项上');
      return;
    }
    if (source === 'SUPPLIER' && !supplierId) {
      setError('来源是「供应商打样」时必须选一个供应商');
      return;
    }
    if (source === 'STALL' && !stallId) {
      setError('来源是「档口打样」时必须选一个档口');
      return;
    }
    if (!requestedAt) {
      setError('申请时刻必填');
      return;
    }
    const supplier = vendors.find((row) => row.roleId === supplierId);
    const stall = stalls.find((row) => row.id === stallId);
    try {
      await onSubmit({
        orderNo: orderNo.trim(),
        developmentProjectId: projectId,
        sourceType: source,
        usageType: 'DEVELOPMENT',
        supplierRefId: source === 'SUPPLIER' ? supplierId : null,
        supplierCode: source === 'SUPPLIER' ? (supplier?.partyCode ?? null) : null,
        supplierName: source === 'SUPPLIER' ? (supplier?.partyName ?? null) : null,
        stallId: source === 'STALL' ? stallId : null,
        stallCode: source === 'STALL' ? (stall?.code ?? null) : null,
        stallName: source === 'STALL' ? (stall?.name ?? null) : null,
        currency: currency.trim().toUpperCase(),
        requesterId: 'current-user',
        requesterName: requesterName.trim(),
        requestedAt: new Date(requestedAt).toISOString(),
        expectedDeliveryAt: expected ? new Date(expected).toISOString() : null,
        purposeNote: purpose.trim() || null,
        note: null,
      });
    } catch (e) {
      setError(errorText(e));
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-3xl">
        <ModalHeader>
          <ModalTitle>新建打样单</ModalTitle>
          <ModalDescription>
            一次打样活动一张单，单下可以挂多件样品（样品台账一件一行）。
            来源只有供应商 / 自有产 / 档口，**用途固定为「开发打样」** —— 达人寄样不归本模块。
          </ModalDescription>
        </ModalHeader>
        <div className="grid gap-3 md:grid-cols-2">
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">打样单号 *</span>
            <Input value={orderNo} onChange={(e) => setOrderNo(e.target.value)} />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">开发立项 *</span>
            <select
              className="h-9 rounded-md border px-2"
              value={projectId}
              onChange={(e) => setProjectId(e.target.value)}
              disabled={loadingRefs}
            >
              <option value="">{loadingRefs ? '加载中…' : '请选择立项'}</option>
              {projects.map((row) => (
                <option key={row.id} value={row.id}>
                  {row.projectNo} · {row.name}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">样品来源 *</span>
            <select
              className="h-9 rounded-md border px-2"
              value={source}
              onChange={(e) => setSource(e.target.value as DevelopmentSampleSourceType)}
            >
              {SOURCE_TYPES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">样品用途</span>
            <Input value="开发打样" disabled />
          </label>
          {source === 'SUPPLIER' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">供应商 *</span>
              <select
                className="h-9 rounded-md border px-2"
                value={supplierId}
                onChange={(e) => setSupplierId(e.target.value)}
                disabled={loadingRefs}
              >
                <option value="">{loadingRefs ? '加载中…' : '请选择供应商'}</option>
                {vendors.map((row) => (
                  <option key={row.roleId} value={row.roleId}>
                    {row.partyCode} · {row.partyName}
                  </option>
                ))}
              </select>
            </label>
          )}
          {source === 'STALL' && (
            <label className="flex flex-col gap-1 text-sm">
              <span className="text-muted-foreground">档口 *</span>
              <select
                className="h-9 rounded-md border px-2"
                value={stallId}
                onChange={(e) => setStallId(e.target.value)}
                disabled={loadingRefs}
              >
                <option value="">{loadingRefs ? '加载中…' : '请选择档口'}</option>
                {stalls.map((row) => (
                  <option key={row.id} value={row.id}>
                    {row.code} · {row.name}
                  </option>
                ))}
              </select>
            </label>
          )}
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">币种 *</span>
            <Input
              value={currency}
              onChange={(e) => setCurrency(e.target.value)}
              maxLength={3}
              placeholder="USD"
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">申请人</span>
            <Input
              value={requesterName}
              onChange={(e) => setRequesterName(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">申请时刻 *</span>
            <Input
              type="datetime-local"
              value={requestedAt}
              onChange={(e) => setRequestedAt(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm">
            <span className="text-muted-foreground">期望到样</span>
            <Input
              type="datetime-local"
              value={expected}
              onChange={(e) => setExpected(e.target.value)}
            />
          </label>
          <label className="flex flex-col gap-1 text-sm md:col-span-2">
            <span className="text-muted-foreground">打样用途说明</span>
            <Input value={purpose} onChange={(e) => setPurpose(e.target.value)} />
          </label>
        </div>
        {error && <p className="text-sm text-red-600">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            取消
          </Button>
          <Button onClick={() => void submit()} disabled={busy}>
            建单
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 打样单详情

function OrderDetailModal({
  detail,
  options,
  busy,
  onClose,
  onOpenSample,
  onTransition,
  onAddSample,
  onAddCostLine,
  onDeleteCostLine,
}: {
  detail: DevelopmentSampleOrderDetail;
  options: DevelopmentSampleOptions | null;
  busy: boolean;
  onClose: () => void;
  onOpenSample: (sampleId: string) => void;
  onTransition: (toStatus: DevelopmentSampleOrderStatus, reason: string) => Promise<void>;
  onAddSample: (input: {
    sampleNo: string;
    specName: string;
    specValue: string;
    quantity: number;
    remark: string;
  }) => Promise<void>;
  onAddCostLine: (input: {
    costType: DevelopmentSampleCostType;
    amount: number;
    description: string;
    incurredAt: string;
  }) => Promise<void>;
  onDeleteCostLine: (lineId: string) => Promise<void>;
}) {
  const order = detail.order;
  const targets = allowedOrderTargetOptions(options, order.status);
  const [target, setTarget] = useState('');
  const [reason, setReason] = useState('');

  const [sampleNo, setSampleNo] = useState('');
  const [specName, setSpecName] = useState('');
  const [specValue, setSpecValue] = useState('');
  const [quantity, setQuantity] = useState('1');

  const [costType, setCostType] = useState<DevelopmentSampleCostType>('SAMPLE_FEE');
  const [amount, setAmount] = useState('');
  const [costDescription, setCostDescription] = useState('');

  const [error, setError] = useState<string | null>(null);

  const submitTransition = async () => {
    setError(null);
    if (!target) {
      setError('请选择目标状态');
      return;
    }
    if (!reason.trim()) {
      setError('必须写明原因 —— 一句「推进一下」不是留痕');
      return;
    }
    try {
      await onTransition(target as DevelopmentSampleOrderStatus, reason.trim());
      setTarget('');
      setReason('');
    } catch (e) {
      setError(errorText(e));
    }
  };

  const submitSample = async () => {
    setError(null);
    if (!sampleNo.trim()) {
      setError('样品编号必填（租户内唯一）');
      return;
    }
    const parsed = Number(quantity);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setError('数量必须大于 0');
      return;
    }
    try {
      await onAddSample({
        sampleNo: sampleNo.trim(),
        specName: specName.trim(),
        specValue: specValue.trim(),
        quantity: parsed,
        remark: '',
      });
      setSampleNo('');
      setSpecName('');
      setSpecValue('');
      setQuantity('1');
    } catch (e) {
      setError(errorText(e));
    }
  };

  const submitCost = async () => {
    setError(null);
    const parsed = Number(amount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      setError('成本金额必须大于 0');
      return;
    }
    try {
      await onAddCostLine({
        costType,
        amount: parsed,
        description: costDescription.trim(),
        incurredAt: new Date().toISOString(),
      });
      setAmount('');
      setCostDescription('');
    } catch (e) {
      setError(errorText(e));
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-5xl">
        <ModalHeader>
          <ModalTitle>
            {order.orderNo} · {order.projectName}
          </ModalTitle>
          <ModalDescription>
            立项 {order.projectNo} · 来源 {order.sourceTypeLabel} / 用途 {order.usageTypeLabel} ·
            币种 {order.currency} · 当前状态 {order.statusLabel} · 申请人{' '}
            {order.requesterName || '未指定'} · 申请于 {timeText(order.requestedAt)}
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-4">
          <SectionCard title="打样单记录">
            <dl className="grid gap-2 text-sm sm:grid-cols-3">
              <div>
                <dt className="text-muted-foreground">来源实体</dt>
                <dd>
                  {order.sourceType === 'SUPPLIER'
                    ? `${order.supplierName}（${order.supplierCode}）`
                    : order.sourceType === 'STALL'
                      ? `${order.stallName}（${order.stallCode}）`
                      : '自有产（无外部来源实体）'}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">期望到样</dt>
                <dd>{timeText(order.expectedDeliveryAt)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">打样用途</dt>
                <dd>{order.purposeNote || '未填'}</dd>
              </div>
            </dl>
          </SectionCard>

          <SectionCard title="推进状态">
            {targets.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                当前状态是终态，没有可去的下一状态（不提供「恢复」）。
              </p>
            ) : (
              <div className="flex flex-wrap items-end gap-3">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">目标状态</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={target}
                    onChange={(e) => setTarget(e.target.value)}
                  >
                    <option value="">请选择</option>
                    {targets.map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">原因 *</span>
                  <Input
                    value={reason}
                    onChange={(e) => setReason(e.target.value)}
                    className="w-72"
                    placeholder="推进原因（必填）"
                  />
                </label>
                <Button onClick={() => void submitTransition()} disabled={busy}>
                  推进
                </Button>
              </div>
            )}
          </SectionCard>

          <SectionCard title={`样品台账（${detail.samples.length} 件）`}>
            {detail.samples.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                这张单还没有样品。样品是**一件一行**：在下面登记第一个样品。
              </p>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground text-left">
                    <tr>
                      <th className="px-2 py-1">样品编号</th>
                      <th className="px-2 py-1">规格</th>
                      <th className="px-2 py-1">数量</th>
                      <th className="px-2 py-1">状态</th>
                      <th className="px-2 py-1">轮次</th>
                      <th className="px-2 py-1">确认 / 物流 / 图片</th>
                      <th className="px-2 py-1" />
                    </tr>
                  </thead>
                  <tbody>
                    {detail.samples.map((sample) => (
                      <tr key={sample.id} className="border-t">
                        <td className="px-2 py-1 font-medium">{sample.sampleNo}</td>
                        <td className="px-2 py-1">
                          {sample.specName ? `${sample.specName} ${sample.specValue}` : '未填'}
                        </td>
                        <td className="px-2 py-1">{fmt(sample.quantity)}</td>
                        <td className="px-2 py-1">
                          <Badge tone={sampleStatusTone(sample.status)}>{sample.statusLabel}</Badge>
                        </td>
                        <td className="px-2 py-1">第 {sample.revisionRound} 轮</td>
                        <td className="px-2 py-1">
                          {sample.confirmationCount} / {sample.shipmentCount} / {sample.mediaCount}
                        </td>
                        <td className="px-2 py-1">
                          <Button variant="ghost" onClick={() => onOpenSample(sample.id)}>
                            详情
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">样品编号 *</span>
                <Input value={sampleNo} onChange={(e) => setSampleNo(e.target.value)} />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">规格名</span>
                <Input value={specName} onChange={(e) => setSpecName(e.target.value)} />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">规格值</span>
                <Input value={specValue} onChange={(e) => setSpecValue(e.target.value)} />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">数量 *</span>
                <Input
                  value={quantity}
                  onChange={(e) => setQuantity(e.target.value)}
                  className="w-24"
                />
              </label>
              <Button onClick={() => void submitSample()} disabled={busy}>
                登记样品
              </Button>
            </div>
          </SectionCard>

          <SectionCard title={`样品成本（${money(detail.cost.totalAmount, detail.cost.currency)}）`}>
            <p className="text-muted-foreground text-sm">
              🔴 {expensePostedText(detail.cost.expensePosted, detail.cost.expenseOwnerModule)}：
              {detail.cost.expenseNote}
            </p>
            {detail.cost.lines.length === 0 ? (
              <p className="text-muted-foreground mt-2 text-sm">还没有成本明细行。</p>
            ) : (
              <ul className="mt-2 flex flex-col gap-1 text-sm">
                {detail.cost.lines.map((line) => (
                  <li key={line.id} className="flex items-center justify-between gap-2">
                    <span>{costLineSummary(line)}</span>
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void onDeleteCostLine(line.id)}
                    >
                      删除
                    </Button>
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">成本类型</span>
                <select
                  className="h-9 rounded-md border px-2"
                  value={costType}
                  onChange={(e) => setCostType(e.target.value as DevelopmentSampleCostType)}
                >
                  {COST_TYPES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">金额 *（{detail.cost.currency}）</span>
                <Input
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                  className="w-32"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">说明</span>
                <Input
                  value={costDescription}
                  onChange={(e) => setCostDescription(e.target.value)}
                  className="w-56"
                />
              </label>
              <Button onClick={() => void submitCost()} disabled={busy}>
                追加成本
              </Button>
            </div>
          </SectionCard>

          <SectionCard title="状态迁移历史">
            {detail.transitions.length === 0 ? (
              <p className="text-muted-foreground text-sm">还没有状态迁移记录。</p>
            ) : (
              <ul className="flex flex-col gap-1 text-sm">
                {detail.transitions.map((row) => (
                  <li key={row.id}>{orderTransitionSummary(row)}</li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>

        {error && <p className="text-sm text-red-600">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}

// ================================================================ 样品详情

function SampleDetailModal({
  detail,
  options,
  busy,
  onClose,
  onAdvance,
  onConfirm,
  onAddShipment,
  onAttachMedia,
}: {
  detail: DevelopmentSampleDetail;
  orderId: string;
  options: DevelopmentSampleOptions | null;
  busy: boolean;
  onClose: () => void;
  onRefresh: () => Promise<void>;
  onAdvance: (toStatus: DevelopmentSampleStatus, reason: string) => Promise<void>;
  onConfirm: (
    result: DevelopmentSampleConfirmationResult,
    reason: string,
    issueNote: string,
  ) => Promise<void>;
  onAddShipment: (input: {
    trackingNo: string;
    carrierCode: string;
    carrierName: string;
    logisticsStatus: DevelopmentSampleLogisticsStatus;
    shippedAt: string;
    expectedArrivalAt: string | null;
    deliveredAt: string | null;
    note: string | null;
  }) => Promise<void>;
  onAttachMedia: (mediaRefId: string) => Promise<void>;
}) {
  const sample = detail.sample;
  const targets = allowedSampleTargetOptions(options, sample.status);
  const progressTargets = targets.filter((item) => !isConfirmationTarget(item.value));
  const confirmTargets = targets.filter((item) => isConfirmationTarget(item.value));

  const [progressTo, setProgressTo] = useState('');
  const [progressReason, setProgressReason] = useState('');
  const [result, setResult] = useState<DevelopmentSampleConfirmationResult>('PASSED');
  const [confirmReason, setConfirmReason] = useState('');
  const [issueNote, setIssueNote] = useState('');

  const [trackingNo, setTrackingNo] = useState('');
  const [carrierName, setCarrierName] = useState('');
  const [logisticsStatus, setLogisticsStatus] =
    useState<DevelopmentSampleLogisticsStatus>('SHIPPED');
  const [shippedAt, setShippedAt] = useState(() => new Date().toISOString().slice(0, 16));
  const [deliveredAt, setDeliveredAt] = useState('');

  const [mediaKeyword, setMediaKeyword] = useState('');
  const [mediaCandidates, setMediaCandidates] = useState<DevelopmentSampleMediaCandidateRow[]>([]);
  const [mediaError, setMediaError] = useState<string | null>(null);

  const [error, setError] = useState<string | null>(null);

  const searchMedia = async () => {
    setMediaError(null);
    try {
      setMediaCandidates(await fetchDevelopmentSampleMediaCandidates(mediaKeyword));
    } catch (e) {
      setMediaError(errorText(e));
    }
  };

  const submitProgress = async () => {
    setError(null);
    if (!progressTo) {
      setError('请选择目标状态');
      return;
    }
    if (!progressReason.trim()) {
      setError('必须写明原因 —— 一句「推进一下」不是留痕');
      return;
    }
    try {
      await onAdvance(progressTo as DevelopmentSampleStatus, progressReason.trim());
      setProgressTo('');
      setProgressReason('');
    } catch (e) {
      setError(errorText(e));
    }
  };

  const submitConfirm = async () => {
    setError(null);
    if (!confirmReason.trim()) {
      setError('必须写明确认原因');
      return;
    }
    if (confirmationNeedsIssueNote(result) && !issueNote.trim()) {
      setError('确认结果是「修改」时必须写明修改意见 —— 只说「要改」不说改什么，下一轮还是得猜');
      return;
    }
    try {
      await onConfirm(result, confirmReason.trim(), issueNote.trim());
      setConfirmReason('');
      setIssueNote('');
    } catch (e) {
      setError(errorText(e));
    }
  };

  const submitShipment = async () => {
    setError(null);
    if (!trackingNo.trim()) {
      setError('发货单号必填 —— 没有单号，「发出去了」就没有凭据');
      return;
    }
    if (logisticsStatus === 'DELIVERED' && !deliveredAt) {
      setError('物流状态是「已签收」时必须填签收时刻');
      return;
    }
    try {
      await onAddShipment({
        trackingNo: trackingNo.trim(),
        carrierCode: '',
        carrierName: carrierName.trim(),
        logisticsStatus,
        shippedAt: new Date(shippedAt).toISOString(),
        expectedArrivalAt: null,
        deliveredAt: deliveredAt ? new Date(deliveredAt).toISOString() : null,
        note: null,
      });
      setTrackingNo('');
      setCarrierName('');
    } catch (e) {
      setError(errorText(e));
    }
  };

  return (
    <Modal open onOpenChange={(open) => !open && onClose()}>
      <ModalContent className="max-w-5xl">
        <ModalHeader>
          <ModalTitle>
            {sample.sampleNo} · {sample.projectName}
          </ModalTitle>
          <ModalDescription>
            来源 {sample.sourceTypeLabel} / 用途 {sample.usageTypeLabel} · 状态 {sample.statusLabel} ·
            第 {sample.revisionRound} 轮打样 · 数量 {fmt(sample.quantity)} {sample.currency}
          </ModalDescription>
        </ModalHeader>

        <div className="flex flex-col gap-4">
          <SectionCard title="推进制作进度">
            {progressTargets.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                当前状态没有可用的制作进度去向（终态或只能走确认）。
              </p>
            ) : (
              <div className="flex flex-wrap items-end gap-3">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">目标状态</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={progressTo}
                    onChange={(e) => setProgressTo(e.target.value)}
                  >
                    <option value="">请选择</option>
                    {progressTargets.map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">原因 *</span>
                  <Input
                    value={progressReason}
                    onChange={(e) => setProgressReason(e.target.value)}
                    className="w-72"
                  />
                </label>
                <Button onClick={() => void submitProgress()} disabled={busy}>
                  推进
                </Button>
              </div>
            )}
          </SectionCard>

          <SectionCard title="确认结果（通过 / 修改 / 放弃）">
            <p className="text-muted-foreground text-sm">
              确认会落一条**不可改写**的确认记录（谁 / 何时 / 什么结论 / 为什么），删不掉也改不了。
              「修改」必须写明修改意见。
            </p>
            {confirmTargets.length === 0 ? (
              <p className="text-muted-foreground mt-2 text-sm">
                当前状态不允许确认（已确认 / 已放弃是终态）。
              </p>
            ) : (
              <div className="mt-2 flex flex-wrap items-end gap-3">
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">结论</span>
                  <select
                    className="h-9 rounded-md border px-2"
                    value={result}
                    onChange={(e) =>
                      setResult(e.target.value as DevelopmentSampleConfirmationResult)
                    }
                  >
                    {CONFIRMATION_RESULTS.map((item) => (
                      <option
                        key={item.value}
                        value={item.value}
                        disabled={!confirmTargets.some((t) => t.value === item.value)}
                      >
                        {item.label}
                      </option>
                    ))}
                  </select>
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">原因 *</span>
                  <Input
                    value={confirmReason}
                    onChange={(e) => setConfirmReason(e.target.value)}
                    className="w-64"
                  />
                </label>
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">
                    修改意见{confirmationNeedsIssueNote(result) ? ' *' : ''}
                  </span>
                  <Input
                    value={issueNote}
                    onChange={(e) => setIssueNote(e.target.value)}
                    className="w-64"
                  />
                </label>
                <Button onClick={() => void submitConfirm()} disabled={busy}>
                  确认
                </Button>
              </div>
            )}
          </SectionCard>

          <SectionCard title="样品物流快照">
            <p className="text-muted-foreground text-sm">
              🔴 本模块**只记物流快照**（发货单号 / 承运商 / 物流状态 / 发出时刻）——
              真实物流轨迹归 10 物流管，轨迹取不到时显示「轨迹不可查」。
            </p>
            {detail.shipments.length === 0 ? (
              <p className="text-muted-foreground mt-2 text-sm">还没有登记物流快照。</p>
            ) : (
              <ul className="mt-2 flex flex-col gap-1 text-sm">
                {detail.shipments.map((row) => (
                  <li key={row.id}>
                    {row.trackingNo} · {row.carrierName || '未填承运商'} · {row.logisticsStatusLabel} ·{' '}
                    {timeText(row.shippedAt)} ·{' '}
                    {trackingText(row.trackingAvailable, row.trackingOwnerModule)}
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">发货单号 *</span>
                <Input value={trackingNo} onChange={(e) => setTrackingNo(e.target.value)} />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">承运商</span>
                <Input
                  value={carrierName}
                  onChange={(e) => setCarrierName(e.target.value)}
                  className="w-40"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">物流状态</span>
                <select
                  className="h-9 rounded-md border px-2"
                  value={logisticsStatus}
                  onChange={(e) =>
                    setLogisticsStatus(e.target.value as DevelopmentSampleLogisticsStatus)
                  }
                >
                  {LOGISTICS_STATUSES.map((item) => (
                    <option key={item.value} value={item.value}>
                      {item.label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">发出时刻</span>
                <Input
                  type="datetime-local"
                  value={shippedAt}
                  onChange={(e) => setShippedAt(e.target.value)}
                />
              </label>
              {logisticsStatus === 'DELIVERED' && (
                <label className="flex flex-col gap-1 text-sm">
                  <span className="text-muted-foreground">签收时刻 *</span>
                  <Input
                    type="datetime-local"
                    value={deliveredAt}
                    onChange={(e) => setDeliveredAt(e.target.value)}
                  />
                </label>
              )}
              <Button onClick={() => void submitShipment()} disabled={busy}>
                登记物流
              </Button>
            </div>
          </SectionCard>

          <SectionCard title="样品图片">
            <p className="text-muted-foreground text-sm">
              图片引用 catalog 的媒体库（3.4.1.1）：本模块只存**引用 + 元数据快照**，
              不跨库写媒体表。
            </p>
            {detail.media.length === 0 ? (
              <p className="text-muted-foreground mt-2 text-sm">还没有挂图片。</p>
            ) : (
              <ul className="mt-2 flex flex-col gap-1 text-sm">
                {detail.media.map((row) => (
                  <li key={row.id}>
                    {row.mediaName || row.mediaRefId} · {row.fileFormat || row.mediaType} ·{' '}
                    {row.contentSha256 ? `sha256 ${row.contentSha256.slice(0, 12)}…` : '无哈希'}
                  </li>
                ))}
              </ul>
            )}
            <div className="mt-3 flex flex-wrap items-end gap-3">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">媒体库搜索</span>
                <Input
                  value={mediaKeyword}
                  onChange={(e) => setMediaKeyword(e.target.value)}
                  className="w-56"
                />
              </label>
              <Button variant="ghost" onClick={() => void searchMedia()}>
                查媒体库
              </Button>
            </div>
            {mediaError && <p className="mt-1 text-sm text-red-600">{mediaError}</p>}
            {mediaCandidates.length > 0 && (
              <ul className="mt-2 flex flex-col gap-1 text-sm">
                {mediaCandidates.map((row) => (
                  <li key={row.id} className="flex items-center justify-between gap-2">
                    <span>
                      {row.name || row.id} · {row.fileFormat || row.mediaType} ·{' '}
                      {row.byteSize ?? '无大小'}
                    </span>
                    <Button
                      variant="ghost"
                      disabled={busy}
                      onClick={() => void onAttachMedia(row.id)}
                    >
                      挂到样品
                    </Button>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="确认记录">
            {detail.confirmations.length === 0 ? (
              <p className="text-muted-foreground text-sm">还没有确认记录。</p>
            ) : (
              <ul className="flex flex-col gap-1 text-sm">
                {detail.confirmations.map((row) => (
                  <li key={row.id} className="flex items-center gap-2">
                    <Badge
                      tone={
                        row.result === 'PASSED'
                          ? 'success'
                          : row.result === 'MODIFY'
                            ? 'warning'
                            : 'danger'
                      }
                    >
                      {row.resultLabel}
                    </Badge>
                    <span>{confirmationSummary(row)}</span>
                    <span className="text-muted-foreground">{row.reason}</span>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>

          <SectionCard title="制作进度事件">
            {detail.transitions.length === 0 ? (
              <p className="text-muted-foreground text-sm">还没有进度事件。</p>
            ) : (
              <ul className="flex flex-col gap-1 text-sm">
                {detail.transitions.map((row) => (
                  <li key={row.id}>{transitionSummary(row)}</li>
                ))}
              </ul>
            )}
          </SectionCard>
        </div>

        {error && <p className="text-sm text-red-600">{error}</p>}
        <ModalFooter>
          <Button variant="ghost" onClick={onClose}>
            关闭
          </Button>
        </ModalFooter>
      </ModalContent>
    </Modal>
  );
}
