'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, ClipboardList, Package, RefreshCw, ShieldCheck } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  DestinationAccount,
  ForecastAvailabilityPage,
  ForecastCapabilities,
  ForecastDetail,
  ForecastLineInput,
  ForecastListQuery,
  ForecastPage,
  ForecastReceiptRow,
  ForecastSpecRow,
  ShipmentReportRow,
  ShipmentTaskRow,
  SpecViolation,
} from './stock-forecast-model';
import {
  AvailabilityState,
  availabilityState,
  availabilityText,
  cancelForecast,
  createForecast,
  createReceipt,
  createShipmentReport,
  createSpec,
  dispatchShipmentTask,
  fetchAvailability,
  fetchCapabilities,
  fetchDestinationAccounts,
  fetchForecast,
  fetchForecasts,
  fetchReceipts,
  fetchShipmentReports,
  fetchSpecs,
  forecastSourceLabel,
  forecastStatusLabel,
  quantityText,
  replaceLines,
  retireSpec,
  retryShipmentTask,
  shipmentTaskStatusLabel,
  submitForecast,
  validateForecast,
  violationText,
  violationsFromError,
} from './stock-forecast-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 10.4.2.2 备货预报。
 *
 * <p>蓝图：「向海外仓/平台仓提交备货计划：SKU 与数量、目的仓、期望到货时间、平台货件编号，
 * 接收仓端接收与拒收反馈。」
 *
 * <p>🔴 这个页面的重心是**规格闸**。蓝图加粗句说「平台仓备货有严格规格（箱型、贴标、
 * 托盘、混装限制），规格不符会被拒收造成额外成本与延误」——所以：
 *
 * <ul>
 *   <li>「备货规格」是独立可维护的一页：箱型、贴标、托盘、混装四组限制各有自己的入口</li>
 *   <li>「规格校验」逐条点名违反哪一条，并给出期望值与实际值</li>
 *   <li>没过闸的单**不给提交入口** —— 界面上不存在「先提交再看」的路径</li>
 * </ul>
 *
 * <p>另一条纪律：库存与在途口径读不到时显示「不可用」，不用 0 顶。
 */
export function StockForecastWorkspace() {
  const [capabilities, setCapabilities] = useState<ForecastCapabilities | null>(null);
  const [accounts, setAccounts] = useState<DestinationAccount[]>([]);
  const [specs, setSpecs] = useState<ForecastSpecRow[]>([]);
  const [page, setPage] = useState<ForecastPage | null>(null);
  const [detail, setDetail] = useState<ForecastDetail | null>(null);
  const [reports, setReports] = useState<ShipmentReportRow[]>([]);
  const [receipts, setReceipts] = useState<ForecastReceiptRow[]>([]);
  const [availability, setAvailability] = useState<ForecastAvailabilityPage | null>(null);
  const [violations, setViolations] = useState<SpecViolation[]>([]);
  const [statusFilter, setStatusFilter] = useState('');
  const [accountFilter, setAccountFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageNo, setPageNo] = useState(0);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      // exactOptionalPropertyTypes：空筛选条件**不传**，不能传 undefined。
      const listQuery: ForecastListQuery = { page: pageNo, size: 20 };
      if (statusFilter) listQuery.status = statusFilter;
      if (accountFilter) listQuery.accountId = accountFilter;
      if (keyword) listQuery.keyword = keyword;
      const [caps, accs, specRows, list] = await Promise.all([
        fetchCapabilities(),
        fetchDestinationAccounts(),
        fetchSpecs(),
        fetchForecasts(listQuery),
      ]);
      setCapabilities(caps);
      setAccounts(accs);
      setSpecs(specRows);
      setPage(list);
      setLoaded(true);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [statusFilter, accountFilter, keyword, pageNo]);

  const loadDetail = useCallback(async (id: string) => {
    setBusy(true);
    setError('');
    setViolations([]);
    try {
      const [d, r, rec] = await Promise.all([
        fetchForecast(id),
        fetchShipmentReports(id),
        fetchReceipts(id),
      ]);
      setDetail(d);
      setReports(r);
      setReceipts(rec);
      setViolations(d.specCheck?.violations ?? []);
      try {
        setAvailability(await fetchAvailability(id));
      } catch {
        // 可用性口径读不到就如实说「不可用」，不用 0 顶：
        // 0 会被读成「没有库存需求」，那正是会真的少备一批货的路径。
        setAvailability(null);
      }
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  async function run(action: () => Promise<unknown>, after?: () => Promise<void>) {
    setBusy(true);
    setError('');
    setViolations([]);
    try {
      await action();
      if (after) await after();
    } catch (e) {
      const list = violationsFromError(e);
      if (list.length > 0) setViolations(list);
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  // ---- 建预报单 ----

  function buildForecast() {
    const accountId = accountFilter || accounts[0]?.id;
    if (!accountId) {
      setError('先在「备货规格」里为某个目的仓配置规格，才能建预报单');
      return;
    }
    const sku = window.prompt('SKU 编码');
    if (!sku) return;
    const raw = window.prompt('数量');
    if (raw === null) return;
    const quantity = Number(raw);
    if (!Number.isFinite(quantity) || quantity <= 0) {
      setError('数量必须大于 0');
      return;
    }
    const boxType = window.prompt('箱型（留空则不声明）') ?? '';
    const arrival = window.prompt('期望到货时间（例如 2026-12-20T09:30:00+08:00）');
    if (!arrival) return;
    const lines: ForecastLineInput[] = [
      {
        skuCode: sku.trim(),
        quantity,
        unit: 'PCS',
        boxType: boxType.trim(),
        cartonCount: 1,
        labelFormat: 'FNSKU',
        cartonLabelRef: `CARTON-${sku.trim()}`,
      },
    ];
    void run(async () => {
      const id = await createForecast({
        accountId,
        source: 'MANUAL',
        expectedArrivalAt: arrival,
        note: '手工登记',
        lines,
      });
      await loadDetail(id);
    });
  }

  function editLines() {
    if (!detail) return;
    const sku = window.prompt('SKU 编码', detail.lines[0]?.skuCode ?? '');
    if (!sku) return;
    const raw = window.prompt('数量', String(detail.lines[0]?.quantity ?? 1));
    if (raw === null) return;
    const quantity = Number(raw);
    if (!Number.isFinite(quantity) || quantity <= 0) {
      setError('数量必须大于 0');
      return;
    }
    const lines: ForecastLineInput[] = [
      {
        skuCode: sku.trim(),
        quantity,
        unit: detail.lines[0]?.unit ?? 'PCS',
        boxType: detail.lines[0]?.boxType ?? '',
        cartonCount: detail.lines[0]?.cartonCount ?? 1,
        palletCount: detail.lines[0]?.palletCount ?? null,
        labelFormat: detail.lines[0]?.labelFormat ?? '',
        cartonLabelRef: detail.lines[0]?.cartonLabelRef ?? '',
        unitWeightKg: detail.lines[0]?.unitWeightKg ?? null,
      },
    ];
    void run(() => replaceLines(detail.forecast.id, lines), () => loadDetail(detail.forecast.id));
  }

  // ---- 规格维护 ----

  function buildSpec() {
    const accountId = accountFilter || accounts[0]?.id;
    if (!accountId) {
      setError('先选一个目的仓');
      return;
    }
    const boxTypes = window.prompt('允许的箱型（逗号分隔，如 CARTON_STANDARD,CARTON_LARGE）');
    if (!boxTypes) return;
    const mixed = window.confirm('是否允许同一箱型混装多个 SKU？（取消 = 禁止混装）');
    const labelRequired = window.confirm('是否要求逐 SKU 贴标？（取消 = 不要求）');
    const palletRequired = window.confirm('是否要求整托发运？（取消 = 不要求）');
    const leadRaw = window.prompt('期望到货的最短提前期（天，0 = 不限制）') ?? '0';
    const lead = Number(leadRaw);
    if (!Number.isFinite(lead) || lead < 0) {
      setError('提前期必须是不小于 0 的整数');
      return;
    }
    void run(
      () =>
        createSpec({
          accountId,
          allowedBoxTypes: boxTypes
            .split(',')
            .map((t) => t.trim())
            .filter(Boolean),
          allowedLabelFormats: labelRequired ? ['FNSKU'] : [],
          requiresFnskuLabel: labelRequired,
          requiresCartonLabel: false,
          palletRequired,
          mixedLoadingAllowed: mixed,
          minArrivalLeadDays: lead,
          note: '界面登记',
        }),
      load,
    );
  }

  // ---- 货件任务与回执 ----

  function registerShipmentRef() {
    if (!detail) return;
    const ref = window.prompt('平台货件编号（平台回调或人工登记带回的真实编号）');
    if (!ref) return;
    void run(
      () =>
        createShipmentReport(detail.forecast.id, {
          externalShipmentRef: ref.trim(),
          source: 'MANUAL_REGISTRATION',
          reportedAt: new Date().toISOString(),
        }),
      () => loadDetail(detail.forecast.id),
    );
  }

  function registerReceipt(receiptType: 'ACCEPTED' | 'REJECTED') {
    if (!detail) return;
    const ref = window.prompt('仓端回执编号') ?? '';
    const reason =
      receiptType === 'REJECTED'
        ? window.prompt('拒收理由（拒收必须写理由）') ?? ''
        : '';
    if (receiptType === 'REJECTED' && !reason.trim()) {
      setError('拒收必须写理由');
      return;
    }
    void run(
      () =>
        createReceipt(detail.forecast.id, {
          receiptType,
          receiptRef: ref,
          detail: receiptType === 'ACCEPTED' ? '仓端已接收' : '仓端拒收',
          rejectedReason: reason,
          receivedAt: new Date().toISOString(),
          source: 'MANUAL_REGISTRATION',
        }),
      () => loadDetail(detail.forecast.id),
    );
  }

  const summary = page?.summary;
  const task: ShipmentTaskRow | undefined = detail?.shipmentTask ?? undefined;
  const canSubmit = detail?.forecast.status === 'VALIDATED';
  const canDispatch = task?.status === 'PENDING';
  const canRetry = task?.status === 'FAILED';
  const canReceive = detail?.forecast.status === 'SHIPMENT_CREATED';
  const canCancel = ['DRAFT', 'VALIDATED', 'SUBMITTED'].includes(detail?.forecast.status ?? '');
  const activeSpec = specs.find((s) => s.accountId === detail?.forecast.accountId && s.status === 'ACTIVE');

  return (
    <>
      <PageHeader
        title="备货预报"
        description="向海外仓/平台仓提交备货计划：SKU 与数量、目的仓、期望到货时间、平台货件编号，并接收仓端接收/拒收回执。"
      />

      {error ? <p className="text-destructive text-sm">{error}</p> : null}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="预报单"
          value={String(summary?.total ?? 0)}
          comparisonLabel="本租户的全部预报单"
        />
        <StatCard
          label="待提交"
          value={String((summary?.draft ?? 0) + (summary?.validated ?? 0))}
          comparisonLabel="草稿与已过规格校验"
        />
        <StatCard
          label="已提交"
          value={String(summary?.submitted ?? 0)}
          comparisonLabel="等仓端回执"
        />
        <StatCard
          label="被拒收"
          value={String(summary?.rejected ?? 0)}
          comparisonLabel={summary?.rejected ? '规格不符被拒收，需改规格或改单' : '暂无拒收'}
        />
      </div>

      <Tabs defaultValue="forecasts" className="mt-6">
        <TabsList>
          <TabsTrigger value="forecasts">预报单</TabsTrigger>
          <TabsTrigger value="specs">备货规格</TabsTrigger>
          <TabsTrigger value="capabilities">能力与边界</TabsTrigger>
        </TabsList>

        <TabsContent value="forecasts">
          <SectionCard
            title="预报单"
            description="真分页 + 真筛选 + 真排序（筛选与排序都在服务端）。规格不过闸的单不给提交入口。"
            extra={
              <div className="flex flex-wrap items-center gap-2">
                <input
                  className="rounded border px-2 py-1 text-sm"
                  placeholder="单号 / SKU 关键字"
                  value={keyword}
                  onChange={(e) => {
                    setKeyword(e.target.value);
                    setPageNo(0);
                  }}
                />
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={statusFilter}
                  onChange={(e) => {
                    setStatusFilter(e.target.value);
                    setPageNo(0);
                  }}
                >
                  <option value="">全部状态</option>
                  {['DRAFT', 'VALIDATED', 'SUBMITTED', 'SHIPMENT_CREATED', 'ACCEPTED', 'REJECTED', 'CANCELLED'].map(
                    (s) => (
                      <option key={s} value={s}>
                        {forecastStatusLabel(s)}
                      </option>
                    ),
                  )}
                </select>
                <select
                  className="rounded border px-2 py-1 text-sm"
                  value={accountFilter}
                  onChange={(e) => {
                    setAccountFilter(e.target.value);
                    setPageNo(0);
                  }}
                >
                  <option value="">全部目的仓</option>
                  {accounts.map((a) => (
                    <option key={a.id} value={a.id}>
                      {a.name || a.externalAccountCode}
                    </option>
                  ))}
                </select>
                <Button size="sm" variant="ghost" onClick={() => void load()} disabled={busy}>
                  <RefreshCw className="mr-1.5 h-4 w-4" />
                  刷新
                </Button>
                <Button size="sm" onClick={buildForecast} disabled={busy}>
                  新建预报单
                </Button>
              </div>
            }
          >
            {!loaded ? (
              <p className="text-muted-foreground text-sm">正在加载预报单…</p>
            ) : !page || page.rows.length === 0 ? (
              <EmptyState
                icon={<ClipboardList className="h-6 w-6" />}
                title="还没有预报单"
                description="先在「备货规格」里为目的仓配置箱型、贴标、托盘与混装限制，再新建预报单。"
              />
            ) : (
              <>
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">单号</th>
                      <th className="py-2">目的仓</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">来源</th>
                      <th className="py-2">行数 / 数量</th>
                      <th className="py-2">期望到货</th>
                      <th className="py-2">平台货件编号</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.rows.map((f) => (
                      <tr
                        key={f.id}
                        className="cursor-pointer border-b last:border-0"
                        onClick={() => void loadDetail(f.id)}
                      >
                        <td className="py-2 font-mono text-xs">{f.forecastNo}</td>
                        <td className="py-2">{f.accountName || '—'}</td>
                        <td className="py-2">
                          <Badge
                            tone={
                              f.status === 'ACCEPTED'
                                ? 'success'
                                : f.status === 'REJECTED'
                                  ? 'danger'
                                  : f.status === 'CANCELLED'
                                    ? 'neutral'
                                    : 'info'
                            }
                          >
                            {forecastStatusLabel(f.status)}
                          </Badge>
                        </td>
                        <td className="py-2">{forecastSourceLabel(f.source)}</td>
                        <td className="py-2">
                          {f.lineCount} 行 / {quantityText(f.totalQuantity, 'PCS')}
                        </td>
                        <td className="py-2">{new Date(f.expectedArrivalAt).toLocaleString()}</td>
                        <td className="py-2 font-mono text-xs">
                          {f.platformShipmentId || '—'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <div className="mt-3 flex items-center gap-2 text-sm">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || page.page <= 0}
                    onClick={() => setPageNo((p) => Math.max(0, p - 1))}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground">
                    第 {page.page + 1} / {Math.max(1, page.pageCount)} 页 · 共 {page.total} 单
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || page.page + 1 >= page.pageCount}
                    onClick={() => setPageNo((p) => p + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </>
            )}
          </SectionCard>

          {detail ? (
            <SectionCard
              className="mt-4"
              title={`${detail.forecast.forecastNo} · ${forecastStatusLabel(detail.forecast.status)}`}
              description={
                activeSpec
                  ? `按第 ${activeSpec.versionNo} 版规格校验（规格版本在建单时冻结，改规格不影响已建单）`
                  : '该预报单冻结的规格版本'
              }
              extra={
                <div className="flex flex-wrap gap-2">
                  {detail.forecast.status === 'DRAFT' ? (
                    <Button size="sm" variant="outline" onClick={editLines} disabled={busy}>
                      改明细
                    </Button>
                  ) : null}
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={busy || !['DRAFT', 'VALIDATED'].includes(detail.forecast.status)}
                    onClick={() =>
                      void run(
                        () => validateForecast(detail.forecast.id),
                        () => loadDetail(detail.forecast.id),
                      )
                    }
                  >
                    规格校验
                  </Button>
                  {canSubmit ? (
                    <Button
                      size="sm"
                      disabled={busy}
                      onClick={() =>
                        void run(
                          () => submitForecast(detail.forecast.id),
                          () => loadDetail(detail.forecast.id),
                        )
                      }
                    >
                      提交并生成货件任务
                    </Button>
                  ) : null}
                  {canDispatch ? (
                    <Button
                      size="sm"
                      disabled={busy}
                      onClick={() =>
                        void run(
                          () => dispatchShipmentTask(detail.forecast.id),
                          () => loadDetail(detail.forecast.id),
                        )
                      }
                    >
                      推进货件任务
                    </Button>
                  ) : null}
                  {canRetry ? (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={busy}
                      onClick={() =>
                        void run(
                          () => retryShipmentTask(detail.forecast.id),
                          () => loadDetail(detail.forecast.id),
                        )
                      }
                    >
                      重试货件任务
                    </Button>
                  ) : null}
                  <Button size="sm" variant="outline" onClick={registerShipmentRef} disabled={busy}>
                    登记平台货件编号
                  </Button>
                  {canReceive ? (
                    <>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => registerReceipt('ACCEPTED')}
                      >
                        登记仓端接收
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => registerReceipt('REJECTED')}
                      >
                        登记仓端拒收
                      </Button>
                    </>
                  ) : null}
                  {canCancel ? (
                    <Button
                      size="sm"
                      variant="ghost"
                      disabled={busy}
                      onClick={() =>
                        void run(
                          () => cancelForecast(detail.forecast.id, '界面作废'),
                          () => loadDetail(detail.forecast.id),
                        )
                      }
                    >
                      作废
                    </Button>
                  ) : null}
                </div>
              }
            >
              {violations.length > 0 ? (
                <div className="mb-4 rounded border border-destructive p-3">
                  <p className="text-destructive flex items-center gap-1.5 text-sm font-medium">
                    <AlertTriangle className="h-4 w-4" />
                    规格校验未通过（{violations.length} 条）—— 提交前必须改掉，否则会被仓端拒收
                  </p>
                  <ul className="mt-2 list-disc space-y-1 pl-5 text-sm">
                    {violations.map((v, i) => (
                      <li key={`${v.rule}-${i}`}>
                        <span className="font-mono text-xs">{v.rule}</span> · {violationText(v)}
                      </li>
                    ))}
                  </ul>
                </div>
              ) : detail.specCheck?.passed ? (
                <p className="text-muted-foreground mb-4 flex items-center gap-1.5 text-sm">
                  <ShieldCheck className="h-4 w-4" />
                  规格校验通过（按第 {detail.specCheck.specVersion} 版规格）
                </p>
              ) : null}

              <h3 className="mb-2 text-sm font-medium">明细</h3>
              <table className="mb-6 w-full text-sm">
                <thead>
                  <tr className="text-muted-foreground border-b text-left">
                    <th className="py-2">SKU</th>
                    <th className="py-2">数量</th>
                    <th className="py-2">箱型 / 箱数</th>
                    <th className="py-2">贴标 / 箱唛</th>
                    <th className="py-2">托盘</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.lines.map((l) => (
                    <tr key={l.id} className="border-b last:border-0">
                      <td className="py-2 font-mono text-xs">{l.skuCode}</td>
                      <td className="py-2">{quantityText(l.quantity, l.unit)}</td>
                      <td className="py-2">
                        {l.boxType || '—'} / {l.cartonCount ?? '—'}
                      </td>
                      <td className="py-2">
                        {l.labelFormat || '—'} / {l.cartonLabelRef || '—'}
                      </td>
                      <td className="py-2">{l.palletCount ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              <h3 className="mb-2 text-sm font-medium">平台货件创建任务</h3>
              {!task ? (
                <p className="text-muted-foreground mb-6 text-sm">
                  还没生成货件任务：预报单过规格闸并提交后自动生成。
                </p>
              ) : (
                <div className="mb-6 text-sm">
                  <p className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-xs">{task.taskNo}</span>
                    <Badge tone={task.status === 'SUCCEEDED' ? 'success' : task.status === 'FAILED' ? 'danger' : 'info'}>
                      {shipmentTaskStatusLabel(task.status)}
                    </Badge>
                    <span className="text-muted-foreground">尝试 {task.attempts} 次</span>
                  </p>
                  {task.platformShipmentId ? (
                    <p className="mt-1">
                      平台货件编号 <span className="font-mono text-xs">{task.platformShipmentId}</span>
                    </p>
                  ) : null}
                  {task.lastError ? (
                    <p className="text-destructive mt-1">{task.lastError}</p>
                  ) : null}
                  {reports.length > 0 ? (
                    <ul className="text-muted-foreground mt-1 space-y-0.5 text-xs">
                      {reports.map((r) => (
                        <li key={r.id}>
                          报送 {r.externalShipmentRef} · {r.source} ·{' '}
                          {new Date(r.reportedAt).toLocaleString()}
                        </li>
                      ))}
                    </ul>
                  ) : (
                    <p className="text-muted-foreground mt-1 text-xs">
                      平台侧还没有登记货件编号：登记后推进任务才能成功。
                    </p>
                  )}
                </div>
              )}

              <h3 className="mb-2 text-sm font-medium">备货可用性</h3>
              {!availability ? (
                <p className="text-muted-foreground mb-6 text-sm">
                  可用性口径不可用：自营可售量与头程在途都读不到（上游不可达）。
                  这里**不用 0 冒充** —— 0 会被读成「没有库存需求」。
                </p>
              ) : availability.rows.length === 0 ? (
                <p className="text-muted-foreground mb-6 text-sm">该预报单没有可核的 SKU。</p>
              ) : (
                <table className="mb-6 w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">SKU</th>
                      <th className="py-2">预报量</th>
                      <th className="py-2">可售 / 在途</th>
                      <th className="py-2">结论</th>
                    </tr>
                  </thead>
                  <tbody>
                    {availability.rows.map((r) => (
                      <tr key={r.skuCode} className="border-b last:border-0">
                        <td className="py-2 font-mono text-xs">{r.skuCode}</td>
                        <td className="py-2">{quantityText(r.requestedQuantity, r.unit)}</td>
                        <td className="py-2">{availabilityText(r)}</td>
                        <td className="py-2">
                          <Badge
                            tone={
                              availabilityState(r) === AvailabilityState.ENOUGH
                                ? 'success'
                                : availabilityState(r) === AvailabilityState.SHORT
                                  ? 'warning'
                                  : 'neutral'
                            }
                          >
                            {availabilityState(r) === AvailabilityState.ENOUGH
                              ? '可售量足够'
                              : availabilityState(r) === AvailabilityState.SHORT
                                ? '可售量不足'
                                : '不可用'}
                          </Badge>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}

              <h3 className="mb-2 text-sm font-medium">仓端回执</h3>
              {receipts.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  还没有回执。仓端接收与拒收都会落库并留在这里（不可改、不可删）。
                </p>
              ) : (
                <table className="w-full text-sm">
                  <thead>
                    <tr className="text-muted-foreground border-b text-left">
                      <th className="py-2">类型</th>
                      <th className="py-2">回执号</th>
                      <th className="py-2">说明 / 拒收理由</th>
                      <th className="py-2">回报时刻</th>
                    </tr>
                  </thead>
                  <tbody>
                    {receipts.map((r) => (
                      <tr key={r.id} className="border-b last:border-0">
                        <td className="py-2">
                          <Badge tone={r.receiptType === 'ACCEPTED' ? 'success' : 'danger'}>
                            {r.receiptType === 'ACCEPTED' ? '接收' : '拒收'}
                          </Badge>
                        </td>
                        <td className="py-2 font-mono text-xs">{r.receiptRef || '—'}</td>
                        <td className="py-2">{r.rejectedReason || r.detail || '—'}</td>
                        <td className="py-2">{new Date(r.receivedAt).toLocaleString()}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </SectionCard>
          ) : null}
        </TabsContent>

        <TabsContent value="specs">
          <SectionCard
            title="备货规格（箱型 / 贴标 / 托盘 / 混装）"
            description="平台仓备货有严格规格：规格不符会被拒收造成额外成本与延误。同一目的仓同时只有一版生效规格，换规格 = 建新版。"
            extra={
              <Button size="sm" onClick={buildSpec} disabled={busy}>
                新建规格版本
              </Button>
            }
          >
            {specs.length === 0 ? (
              <EmptyState
                icon={<Package className="h-6 w-6" />}
                title="还没有备货规格"
                description="规格是预报单提交前的硬闸：没有生效规格，目的仓就没法建预报单。"
              />
            ) : (
              <ul className="divide-y">
                {specs.map((s) => (
                  <li key={s.id} className="flex items-start justify-between gap-4 py-3">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{s.accountName || s.accountId}</span>
                        <Badge tone={s.status === 'ACTIVE' ? 'success' : 'neutral'}>
                          第 {s.versionNo} 版 · {s.status === 'ACTIVE' ? '生效中' : '已停用'}
                        </Badge>
                      </div>
                      <p className="text-muted-foreground mt-1 text-sm">
                        箱型 {s.allowedBoxTypes.join(' / ') || '不限'} · 单箱≤
                        {s.maxUnitsPerCarton ?? '—'} 件 / {s.maxWeightPerCartonKg ?? '—'} kg · 同箱型≤
                        {s.maxSkusPerCarton ?? '—'} SKU
                      </p>
                      <p className="text-muted-foreground mt-1 text-sm">
                        贴标 {s.requiresFnskuLabel ? `必填（${s.allowedLabelFormats.join('/') || '任意'}）` : '不要求'}
                        {s.requiresCartonLabel ? ' · 箱唛必填' : ''} · 托盘{' '}
                        {s.palletRequired ? `必填（单托盘≤${s.maxCartonsPerPallet ?? '—'} 箱 / ${s.maxPalletWeightKg ?? '—'} kg）` : '不要求'}
                        · 混装 {s.mixedLoadingAllowed ? '允许' : '禁止'} · 提前期 {s.minArrivalLeadDays} 天
                      </p>
                    </div>
                    {s.status === 'ACTIVE' ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        onClick={() => void run(() => retireSpec(s.id), load)}
                      >
                        停用
                      </Button>
                    ) : null}
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </TabsContent>

        <TabsContent value="capabilities">
          <SectionCard
            title="能力与边界"
            description="未接通的能力如实标成不可用，不用空列表或 0 冒充「这一项没有数据」。"
          >
            {!capabilities ? (
              <p className="text-muted-foreground text-sm">正在加载能力声明…</p>
            ) : (
              <ul className="divide-y">
                {capabilities.dimensions.map((d) => (
                  <li key={d.key} className="flex items-start justify-between gap-4 py-3">
                    <div className="min-w-0">
                      <span className="font-medium">{d.label}</span>
                      <p className="text-muted-foreground mt-1 text-sm">{d.impact}</p>
                      <p className="text-muted-foreground mt-0.5 text-xs">归属模块 {d.ownerModule}</p>
                    </div>
                    <Badge tone={d.available ? 'success' : 'warning'}>
                      {d.available ? '已接通' : '未接通'}
                    </Badge>
                  </li>
                ))}
              </ul>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}
