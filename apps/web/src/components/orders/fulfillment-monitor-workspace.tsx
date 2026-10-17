'use client';

import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { AlertTriangle, ClipboardCheck, RefreshCw, Search, ShieldAlert, Timer } from 'lucide-react';
import { Button, EmptyState, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type {
  FulfillmentMonitorCapabilities,
  FulfillmentMonitorCaseDetail,
  FulfillmentMonitorCasePage,
  FulfillmentMonitorCaseRow,
  FulfillmentMonitorEffectiveThresholds,
  FulfillmentMonitorScanResult,
  FulfillmentMonitorTodoPage,
  FulfillmentMonitorTodoRow,
} from '../../../../../packages/api-contracts/src/index';
import {
  ANOMALY_HINT,
  VERIFIED_SCOPE_HINT,
  type FulfillmentMonitorCaseListParams,
  actionTodo,
  advanceDisposition,
  anomalyLabel,
  closeCase,
  confirmedVerification,
  createTodo,
  dispose,
  errorText,
  eventSummary,
  fetchCapabilities,
  fetchCase,
  fetchCases,
  fetchThresholds,
  fetchTodos,
  overdueText,
  putThreshold,
  releaseSummary,
  scan,
  severityTone,
  thresholdSummary,
  thresholdText,
  todoSummary,
  verify,
} from './fulfillment-monitor-model';

const PAGE_SIZE = 20;

/**
 * 表单字段外壳。
 *
 * <p>刻意不用 `FormField`：它要求 children 是 `Input`，而本页大量字段是 `<select>`
 * （异常类型、处置状态、查证范围、处置动作）。照 9.6.1.3 / 9.6.1.2 工作区的同一处理，
 * 下拉与输入共用一个外壳，视觉一致。
 */
function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="space-y-2">
      <span className="text-label-13 text-foreground block">{label}</span>
      {children}
    </div>
  );
}

interface Option {
  value: string;
  label: string;
}

/** 下拉外壳：用原生 `<select>`（本仓没有 Select 组件，9.6.1.3 工作区同一处理）。 */
function SelectField({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: Option[];
}) {
  return (
    <Field label={label}>
      <select
        className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </Field>
  );
}

const ANOMALY_KINDS = [
  { value: '', label: '全部异常' },
  { value: 'RESERVATION_FAILED', label: '预占失败' },
  { value: 'INSUFFICIENT_STOCK', label: '库存不足' },
  { value: 'STEP_STUCK', label: '流程卡在某步' },
  { value: 'SHIPMENT_TIMEOUT', label: '超时未发货' },
];

const CASE_STATUSES = [
  { value: '', label: '全部状态' },
  { value: 'OPEN', label: '待查证' },
  { value: 'INVESTIGATING', label: '查证中' },
  { value: 'VERIFIED', label: '已查证' },
  { value: 'DISPOSED', label: '已处置' },
  { value: 'DISMISSED', label: '已证伪' },
];

const ACTION_KINDS = [
  { value: 'RETRY', label: '重试' },
  { value: 'SWITCH_PATH', label: '切换履约路径' },
  { value: 'MANUAL_INTERVENTION', label: '人工介入' },
  { value: 'RELEASE_RESERVATION', label: '释放预占' },
];

const VERIFIED_SCOPES = [
  { value: 'DOWNSTREAM_ACK', label: '已向下游查证回执' },
  { value: 'WAREHOUSE_STOCK', label: '已查 warehouse 库存' },
  { value: 'CARRIER_TRACE', label: '已查承运轨迹' },
  { value: 'LOCAL_STATE_ONLY', label: '只看本地状态（不足以支持重试）' },
];

const STEP_KINDS = [
  { value: 'RESERVE', label: '锁定预占' },
  { value: 'OUTBOUND', label: '生成出库单' },
  { value: 'WORK', label: '编排作业' },
  { value: 'TRACK', label: '跟踪完成' },
];

/**
 * 9.3.3.2 履约流程监控工作区。
 *
 * <p>蓝图：「监控履约流程的异常与超时：预占失败、库存不足、流程卡在某步、超时未发货，
 * 提供重试、切换路径、人工介入入口。」
 * **「超时不是失败**：流程卡住时先查证再决定重试或释放预占，否则会出现"重试造成重复发货"。**
 *
 * <p><b>区块顺序就是那条加粗句的操作顺序</b>：
 *
 * <ol>
 *   <li><b>下游边界</b>：释放预占（7.2.1.3）与工作台待办（1）<b>都没接通</b>。
 *       这条必须排在最前面 —— 界面若把「已登记释放指令」显示成「货已释放」，
 *       就会有人以为货回池了、于是又发一次，正是蓝图要防的那件事。</li>
 *   <li><b>异常流程清单</b>：四类异常 + 严重度 + 「卡了多久 / 阈值多久 / 谁定的」。</li>
 *   <li><b>详情与处置</b>：查证在前、处置在后。<b>重试按钮的可用性由后端说了算</b>，
 *       不允许重试时把 {@code retryBlockedReason} 原样显示出来，
 *       而不是让操作员点一下撞 409 再猜差什么。</li>
 *   <li><b>阈值与待办</b>：阈值带来源；待办是人工介入入口（本服务内是权威待办）。</li>
 * </ol>
 *
 * <p>加载态 / 空态 / 错误态三态齐全；版本冲突（409）单独处理，不混进通用错误提示。
 */
export function FulfillmentMonitorWorkspace() {
  const [capabilities, setCapabilities] = useState<FulfillmentMonitorCapabilities | null>(null);
  const [page, setPage] = useState<FulfillmentMonitorCasePage | null>(null);
  const [todos, setTodos] = useState<FulfillmentMonitorTodoPage | null>(null);
  const [thresholds, setThresholds] = useState<FulfillmentMonitorEffectiveThresholds | null>(null);
  const [detail, setDetail] = useState<FulfillmentMonitorCaseDetail | null>(null);
  const [scanResult, setScanResult] = useState<FulfillmentMonitorScanResult | null>(null);

  const [filters, setFilters] = useState<FulfillmentMonitorCaseListParams>({
    page: 0,
    size: PAGE_SIZE,
  });
  const [keyword, setKeyword] = useState('');
  const [warehouseFilter, setWarehouseFilter] = useState('');
  const [thresholdWarehouse, setThresholdWarehouse] = useState('');

  const [loading, setLoading] = useState(true);
  const [working, setWorking] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 查证表单
  const [conclusion, setConclusion] = useState('CONFIRMED');
  const [verifiedScope, setVerifiedScope] = useState('DOWNSTREAM_ACK');
  const [evidenceRef, setEvidenceRef] = useState('');
  const [finding, setFinding] = useState('');

  // 处置表单
  const [actionKind, setActionKind] = useState('RETRY');
  const [targetRouteRef, setTargetRouteRef] = useState('');
  const [retryGrantSeconds, setRetryGrantSeconds] = useState('7200');
  const [dispositionNote, setDispositionNote] = useState('');
  const [todoTitle, setTodoTitle] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, cases, todoPage, thresholdData] = await Promise.all([
        fetchCapabilities(),
        fetchCases(filters),
        fetchTodos({ page: 0, size: PAGE_SIZE }),
        fetchThresholds(thresholdWarehouse || undefined),
      ]);
      setCapabilities(caps);
      setPage(cases);
      setTodos(todoPage);
      setThresholds(thresholdData);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [filters, thresholdWarehouse]);

  useEffect(() => {
    void load();
  }, [load]);

  const run = useCallback(
    async (work: () => Promise<unknown>) => {
      setWorking(true);
      setError(null);
      try {
        await work();
        await load();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setWorking(false);
      }
    },
    [load],
  );

  const openDetail = useCallback(async (caseId: string) => {
    setWorking(true);
    setError(null);
    try {
      setDetail(await fetchCase(caseId));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setWorking(false);
    }
  }, []);

  const stats = useMemo(() => {
    const items = page?.items ?? [];
    return {
      total: page?.total ?? 0,
      critical: items.filter((r) => r.severity === 'CRITICAL').length,
      pending: items.filter((r) => r.status === 'OPEN' || r.status === 'INVESTIGATING').length,
      verified: items.filter((r) => r.status === 'VERIFIED').length,
    };
  }, [page]);

  const selected = detail?.monitorCase ?? null;
  const verifiedRecord = detail ? confirmedVerification(detail.verifications) : null;

  // ------------------------------------------------------------- 操作

  const submitScan = () =>
    run(async () => {
      const result = await scan({
        warehouseRef: '',
        anomalyKinds: [],
        maxCases: 100,
        asOfSecondsAgo: 0,
      });
      setScanResult(result);
    });

  const submitVerify = () => {
    if (!selected) return;
    return run(async () => {
      const saved = await verify(selected.id, {
        expectedMonitorVersion: selected.monitorVersion,
        conclusion: conclusion as 'CONFIRMED' | 'NOT_CONFIRMED',
        verifiedScope: verifiedScope as
          'DOWNSTREAM_ACK' | 'LOCAL_STATE_ONLY' | 'WAREHOUSE_STOCK' | 'CARRIER_TRACE',
        evidenceRef,
        finding,
        verifiedAt: '',
        // 幂等键带上时间戳：同一次提交重发不会造出两条「我查过了」，
        // 而两次**不同的**查证不会因为键相同被合并掉。
        idempotencyKey: `verify-${selected.id}-${Date.now()}`,
      });
      setFinding('');
      setEvidenceRef('');
      setDetail(await fetchCase(saved.caseId));
    });
  };

  const submitDisposition = () => {
    if (!selected) return;
    return run(async () => {
      await dispose(selected.id, {
        expectedMonitorVersion: selected.monitorVersion,
        actionKind: actionKind as
          'RETRY' | 'SWITCH_PATH' | 'MANUAL_INTERVENTION' | 'RELEASE_RESERVATION' | 'DISMISS',
        // 重试必须引用一条「已查证（CONFIRMED）」的查证 —— 没有它，
        // 后端的 DB 触发器会直接拒，界面上按钮也应当是禁用的。
        verificationId: actionKind === 'RETRY' ? (verifiedRecord?.id ?? '') : '',
        retryGrantSeconds: actionKind === 'RETRY' ? Number(retryGrantSeconds) : 0,
        targetRouteRef: actionKind === 'SWITCH_PATH' ? targetRouteRef : '',
        targetWarehouseRef: '',
        estimatedCostDelta: 0,
        estimatedCostCurrencyCode: '',
        note: dispositionNote,
        idempotencyKey: `disp-${selected.id}-${Date.now()}`,
      });
      setDispositionNote('');
      setTargetRouteRef('');
      setDetail(await fetchCase(selected.id));
    });
  };

  const submitClose = () => {
    if (!selected) return;
    return run(async () => {
      await closeCase(selected.id, {
        expectedMonitorVersion: selected.monitorVersion,
        note: '处置完成，案例结案',
      });
      setDetail(await fetchCase(selected.id));
    });
  };

  const submitTodo = () => {
    if (!selected || !todoTitle) return;
    return run(async () => {
      await createTodo(selected.id, {
        actionKind: 'REVIEW_CASE',
        title: todoTitle,
        detail: '',
        capturedAt: '',
        sourceKind: 'TENANT_MAINTAINED',
      });
      setTodoTitle('');
      setDetail(await fetchCase(selected.id));
    });
  };

  const completeFirstDisposition = () => {
    if (!selected || !detail) return;
    const pending = detail.dispositions.find((d) => d.status === 'PENDING' || d.status === 'SENT');
    if (!pending) return;
    return run(async () => {
      await advanceDisposition(selected.id, pending.id, {
        fromStatus: pending.status,
        toStatus: 'COMPLETED',
        outcome: '下游已受理并完成',
        lastError: '',
      });
      setDetail(await fetchCase(selected.id));
    });
  };

  const saveThreshold = (stepKind: string, seconds: number) =>
    run(async () => {
      await putThreshold({
        warehouseRef: thresholdWarehouse,
        stepKind: stepKind as 'RESERVE' | 'OUTBOUND' | 'WORK' | 'TRACK',
        thresholdSeconds: seconds,
        notes: '工作区调整',
      });
    });

  const actOnTodo = (todo: FulfillmentMonitorTodoRow, action: string) =>
    run(async () => {
      await actionTodo(todo.id, { action, note: action === 'CLAIM' ? '我来跟' : '已处理' });
    });

  // ------------------------------------------------------------- 渲染

  return (
    <div className="space-y-6">
      <PageHeader
        title="履约流程监控"
        description="监控履约流程的异常与超时：预占失败、库存不足、流程卡在某步、超时未发货；提供重试、切换路径、人工介入入口。"
        actions={
          <>
            <Button onClick={submitScan} disabled={working}>
              <RefreshCw className="mr-2 h-4 w-4" />
              扫描异常
            </Button>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              刷新
            </Button>
          </>
        }
      />

      {error ? (
        <Notice tone="danger" title="请求失败">
          {error}
        </Notice>
      ) : null}

      {/* 1. 下游边界 —— 加粗句的前提，两个下游都没接通 */}
      <SectionCard title="下游边界" description="这两个下游都未交付，界面上不得把它们显示成已完成">
        {capabilities ? (
          <div className="space-y-3">
            {!capabilities.reservationReleaseDispatched ? (
              <Notice tone="warning" title="释放预占只登记指令，不真的释放">
                7.2.1.3 在 warehouse 服务的独立库，跨服务读写被模块边界纪律禁止。
                释放登记缺省是「待回执」—— 只有拿到 7.2 的回执才算已释放， 否则货其实还在锁着。
              </Notice>
            ) : null}
            {!capabilities.workbenchTodoPushed ? (
              <Notice tone="warning" title="人工介入待办没有推到 1 工作台">
                本页的待办就是当前权威待办（租户可维护，带来源与采集时刻）。
              </Notice>
            ) : null}
            {!capabilities.processStateMutated ? (
              <Notice tone="info" title="本模块不代 9.3.3.1 改流程状态">
                ：检出只落一条异常案例，
                要不要重试由处置动作决定，流程怎么动由履约请求的推进接口发起。
              </Notice>
            ) : null}
            <ul className="space-y-1 text-sm">
              {capabilities.dimensions.map((d) => (
                <li key={d.key} className="flex gap-2">
                  <span className={d.available ? 'text-muted-foreground' : 'text-amber-600'}>
                    {d.available ? '已接通' : '未接通'}
                  </span>
                  <span className="font-medium">{d.label}</span>
                  <span className="text-muted-foreground">{d.ownerModule}</span>
                  {d.reason ? <span className="text-muted-foreground">— {d.reason}</span> : null}
                </li>
              ))}
            </ul>
            <Notice tone="info" title="本模块的核心口径">
              {capabilities.coreStanceNote}
            </Notice>
          </div>
        ) : (
          <p className="text-muted-foreground text-sm">加载中…</p>
        )}
      </SectionCard>

      {/* 2. 异常流程清单 */}
      <div className="grid gap-4 md:grid-cols-4">
        <StatCard label="异常案例总数" value={String(stats.total)} />
        <StatCard label="本页严重" value={String(stats.critical)} />
        <StatCard label="本页待查证" value={String(stats.pending)} />
        <StatCard label="本页已查证" value={String(stats.verified)} />
      </div>

      <SectionCard
        title="异常流程清单"
        description="输出第一项：异常流程清单。每行都带「卡了多久 / 阈值多久 / 谁定的」"
      >
        <div className="mb-4 flex flex-wrap items-end gap-3">
          <Field label="关键词">
            <Input
              value={keyword}
              placeholder="单号 / 案例号 / 订单号 / SKU"
              onChange={(e) => setKeyword(e.target.value)}
            />
          </Field>
          <Field label="异常类型">
            <select
              className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
              value={filters.anomalyKind ?? ''}
              onChange={(e) => setFilters({ ...filters, anomalyKind: e.target.value, page: 0 })}
            >
              {ANOMALY_KINDS.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="处置状态">
            <select
              className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
              value={filters.status ?? ''}
              onChange={(e) => setFilters({ ...filters, status: e.target.value, page: 0 })}
            >
              {CASE_STATUSES.map((o) => (
                <option key={o.value} value={o.value}>
                  {o.label}
                </option>
              ))}
            </select>
          </Field>
          <Field label="仓库">
            <Input
              value={warehouseFilter}
              placeholder="发货仓号"
              onChange={(e) => setFilters({ ...filters, warehouseRef: e.target.value, page: 0 })}
            />
          </Field>
          <Button onClick={() => setFilters({ ...filters, keyword, page: 0 })} variant="secondary">
            <Search className="mr-2 h-4 w-4" />
            筛选
          </Button>
        </div>

        {loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : !page || page.items.length === 0 ? (
          <EmptyState
            title="暂无异常流程"
            description="履约流程都在正常推进。点「扫描异常」可以立刻按当前阈值复查一遍。"
          />
        ) : (
          <>
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2">案例</th>
                  <th className="py-2">履约请求</th>
                  <th className="py-2">异常</th>
                  <th className="py-2">严重度</th>
                  <th className="py-2">卡了多久 / 阈值 / 来源</th>
                  <th className="py-2">处置状态</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {page.items.map((row: FulfillmentMonitorCaseRow) => (
                  <tr key={row.id} className="border-b">
                    <td className="py-2">
                      <div className="font-mono text-xs">{row.caseNo}</div>
                      <div className="text-muted-foreground text-xs">第 {row.attempt} 次尝试</div>
                    </td>
                    <td className="py-2">
                      <div className="font-mono text-xs">{row.requestCode}</div>
                      <div className="text-muted-foreground text-xs">
                        {row.orderRef} · {row.skuRef} · {row.warehouseRef}
                      </div>
                    </td>
                    <td className="py-2">
                      <div className="flex items-center gap-1">
                        <ShieldAlert className="h-4 w-4" />
                        {anomalyLabel(row)}
                      </div>
                      <div className="text-muted-foreground text-xs">
                        {ANOMALY_HINT[row.anomalyKind]}
                      </div>
                    </td>
                    <td className="py-2">
                      <span
                        className={
                          severityTone(row.severity) === 'danger'
                            ? 'font-semibold text-red-600'
                            : severityTone(row.severity) === 'warning'
                              ? 'text-amber-600'
                              : 'text-muted-foreground'
                        }
                      >
                        {row.severityLabel}
                      </span>
                    </td>
                    <td className="py-2">
                      <div className="flex items-center gap-1">
                        <Timer className="h-4 w-4" />
                        {thresholdSummary(row)}
                      </div>
                      <div className="text-muted-foreground text-xs">{row.ruleEvidence}</div>
                    </td>
                    <td className="py-2">{row.statusLabel}</td>
                    <td className="py-2">
                      <Button
                        variant="secondary"
                        onClick={() => void openDetail(row.id)}
                        disabled={working}
                      >
                        处置
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground mt-3 text-xs">
              共 {page.total} 条，本页 {page.page + 1} /{' '}
              {Math.max(1, Math.ceil(page.total / page.size))}
            </p>
          </>
        )}

        {scanResult ? (
          <Notice tone="info" title="扫描结果">
            扫描了 {scanResult.scannedRequests} 条未完成的履约流程，新检出 {scanResult.createdCases}{' '}
            条异常案例。未结案的同类异常不会重复检出，所以反复扫描不刷屏。
          </Notice>
        ) : null}
      </SectionCard>

      {/* 3. 详情与处置：查证在前，处置在后 */}
      {selected ? (
        <SectionCard
          title={`处置：${selected.caseNo}`}
          description="超时不是失败。先查证，再决定重试还是释放预占"
        >
          <div className="space-y-5">
            <div className="rounded border p-3 text-sm">
              <div className="mb-1 flex items-center gap-2">
                <AlertTriangle className="h-4 w-4" />
                {anomalyLabel(selected)} · {selected.severityLabel} · 履约请求{' '}
                <span className="font-mono">{selected.requestCode}</span>
              </div>
              <div>{thresholdSummary(selected)}</div>
              <div className="text-muted-foreground text-xs">{selected.ruleEvidence}</div>
              <div className="text-muted-foreground mt-1 text-xs">
                处置状态 {selected.statusLabel} · monitorVersion {selected.monitorVersion}
              </div>
            </div>

            {/* 3a. 查证记录 —— 加粗句的凭据 */}
            <div>
              <h4 className="mb-2 flex items-center gap-1 text-sm font-semibold">
                <ClipboardCheck className="h-4 w-4" />
                查证记录
              </h4>
              {detail && detail.verifications.length > 0 ? (
                <ul className="space-y-2 text-sm">
                  {detail.verifications.map((v) => (
                    <li key={v.id} className="rounded border p-2">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{v.conclusionLabel}</span>
                        <span className="text-muted-foreground text-xs">
                          {v.verifiedScopeLabel}
                        </span>
                        <span className="text-muted-foreground text-xs">
                          凭据 {v.evidenceRef || '（未给）'}
                        </span>
                      </div>
                      <div className="text-muted-foreground text-xs">{v.finding}</div>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-muted-foreground text-sm">
                  还没有查证记录。<strong>没有「已查证」的案例不允许发起任何处置</strong> ——
                  这是后端数据库层的硬约束，不是界面的建议。
                </p>
              )}
            </div>

            {/* 3b. 查证表单 */}
            {selected.status !== 'DISPOSED' && selected.status !== 'DISMISSED' ? (
              <div className="space-y-3 rounded border p-3">
                <h4 className="text-sm font-semibold">发起查证</h4>
                <div className="grid gap-3 md:grid-cols-2">
                  <Field label="查证结论">
                    <select
                      className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
                      value={conclusion}
                      onChange={(e) => setConclusion(e.target.value)}
                    >
                      <option value="CONFIRMED">坐实了异常</option>
                      <option value="NOT_CONFIRMED">证伪，不是异常</option>
                    </select>
                  </Field>
                  <Field label="查证范围">
                    <select
                      className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
                      value={verifiedScope}
                      onChange={(e) => setVerifiedScope(e.target.value)}
                    >
                      {VERIFIED_SCOPES.map((s) => (
                        <option key={s.value} value={s.value}>
                          {s.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="本次查证看到的下游凭据">
                    <Input
                      value={evidenceRef}
                      placeholder="回执号 / 库存快照号 / 轨迹号"
                      onChange={(e) => setEvidenceRef(e.target.value)}
                    />
                  </Field>
                  <Field label="查证结论（看到了什么）">
                    <Input
                      value={finding}
                      placeholder="例：已向 7.2 查证，预占确实没有落地"
                      onChange={(e) => setFinding(e.target.value)}
                    />
                  </Field>
                </div>
                <p className="text-muted-foreground text-xs">
                  {VERIFIED_SCOPE_HINT[verifiedScope]}
                </p>
                <Button onClick={submitVerify} disabled={working || !finding}>
                  提交查证
                </Button>
              </div>
            ) : null}

            {/* 3c. 处置表单 */}
            {selected.status !== 'DISPOSED' && selected.status !== 'DISMISSED' ? (
              <div className="space-y-3 rounded border p-3">
                <h4 className="text-sm font-semibold">发起处置</h4>
                {detail && !detail.retryAllowed ? (
                  <Notice tone="warning" title="现在还不能重试">
                    {detail.retryBlockedReason}
                  </Notice>
                ) : null}
                <div className="grid gap-3 md:grid-cols-2">
                  <SelectField
                    label="处置动作"
                    value={actionKind}
                    onChange={(v) => setActionKind(v)}
                    options={ACTION_KINDS}
                  />
                  {actionKind === 'RETRY' ? (
                    <Field label="重试窗口（秒）">
                      <Input
                        value={retryGrantSeconds}
                        onChange={(e) => setRetryGrantSeconds(e.target.value)}
                      />
                    </Field>
                  ) : null}
                  {actionKind === 'SWITCH_PATH' ? (
                    <Field label="目标 routing_decision_ref">
                      <Input
                        value={targetRouteRef}
                        placeholder="9.3.2.2 的决策引用"
                        onChange={(e) => setTargetRouteRef(e.target.value)}
                      />
                    </Field>
                  ) : null}
                  <Field label="处置理由（必填）">
                    <Input
                      value={dispositionNote}
                      placeholder="例：查证坐实下游没动，重试这一步"
                      onChange={(e) => setDispositionNote(e.target.value)}
                    />
                  </Field>
                </div>
                <Button
                  onClick={submitDisposition}
                  disabled={
                    working ||
                    !dispositionNote ||
                    (actionKind === 'RETRY' && !verifiedRecord) ||
                    (actionKind === 'SWITCH_PATH' && !targetRouteRef)
                  }
                >
                  发起处置
                </Button>
                {actionKind === 'RETRY' && !verifiedRecord ? (
                  <p className="text-muted-foreground text-xs">
                    重试必须引用一条「已查证」的查证记录 —— 没有它按钮是禁用的。
                  </p>
                ) : null}
              </div>
            ) : null}

            {/* 3d. 处置记录 */}
            <div>
              <h4 className="mb-2 text-sm font-semibold">处置操作与处置记录</h4>
              {detail && detail.dispositions.length > 0 ? (
                <ul className="space-y-2 text-sm">
                  {detail.dispositions.map((d) => (
                    <li key={d.id} className="rounded border p-2">
                      <div className="flex items-center gap-2">
                        <span className="font-medium">{d.actionKindLabel}</span>
                        <span className="text-muted-foreground text-xs">{d.statusLabel}</span>
                        <span className="font-mono text-xs">{d.dispositionNo}</span>
                      </div>
                      <div className="text-muted-foreground text-xs">{d.note}</div>
                      {d.targetRouteRef ? (
                        <div className="text-muted-foreground text-xs">
                          目标路径 {d.targetRouteRef}
                        </div>
                      ) : null}
                      {d.outcome ? (
                        <div className="text-muted-foreground text-xs">回执：{d.outcome}</div>
                      ) : null}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-muted-foreground text-sm">还没有处置记录。</p>
              )}
              {detail &&
              detail.dispositions.some((d) => d.status === 'PENDING' || d.status === 'SENT') ? (
                <Button
                  variant="secondary"
                  className="mt-2"
                  onClick={completeFirstDisposition}
                  disabled={working}
                >
                  登记处置完成（下游回执）
                </Button>
              ) : null}
              {selected.status === 'VERIFIED' ? (
                <Button
                  variant="secondary"
                  className="mt-2"
                  onClick={submitClose}
                  disabled={working}
                >
                  结案
                </Button>
              ) : null}
            </div>

            {/* 3e. 释放登记 */}
            {detail && detail.releases.length > 0 ? (
              <div>
                <h4 className="mb-2 text-sm font-semibold">释放预占登记</h4>
                <ul className="space-y-2 text-sm">
                  {detail.releases.map((r) => (
                    <li key={r.id} className="rounded border p-2">
                      <div className="font-mono text-xs">{r.releaseNo}</div>
                      <div className="text-xs">{releaseSummary(r)}</div>
                      <div className="text-muted-foreground text-xs">来源 {r.sourceKindLabel}</div>
                    </li>
                  ))}
                </ul>
              </div>
            ) : null}

            {/* 3f. 事件流 */}
            {detail ? (
              <div>
                <h4 className="mb-2 text-sm font-semibold">事件流</h4>
                <ul className="text-muted-foreground space-y-1 text-xs">
                  {detail.events.map((e) => (
                    <li key={e.id}>{eventSummary(e)}</li>
                  ))}
                </ul>
              </div>
            ) : null}
          </div>
        </SectionCard>
      ) : null}

      {/* 4. 阈值 */}
      <SectionCard
        title="超时阈值配置"
        description="输入项：超时阈值配置。解析顺序 仓库覆盖 > 租户默认 > 建单快照 > 内置兜底"
      >
        <div className="mb-3 flex items-end gap-3">
          <Field label="按仓库查看（留空看租户默认档）">
            <Input
              value={thresholdWarehouse}
              placeholder="仓号，留空为租户默认"
              onChange={(e) => setThresholdWarehouse(e.target.value)}
            />
          </Field>
        </div>
        {!thresholds ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b text-left">
                <th className="py-2">步骤</th>
                <th className="py-2">生效阈值</th>
                <th className="py-2">来源</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {thresholds.thresholds.map((t) => (
                <tr key={t.stepKind} className="border-b">
                  <td className="py-2">{t.stepKindLabel}</td>
                  <td className="py-2">{thresholdText(t.thresholdSeconds)}</td>
                  <td className="py-2">
                    {t.sourceLabel}
                    {t.notes ? (
                      <span className="text-muted-foreground ml-2 text-xs">{t.notes}</span>
                    ) : null}
                  </td>
                  <td className="py-2">
                    <Button
                      variant="secondary"
                      disabled={working}
                      onClick={() => saveThreshold(t.stepKind, t.thresholdSeconds)}
                    >
                      按当前值保存
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        <p className="text-muted-foreground mt-2 text-xs">
          内置兜底阈值：RESERVE 1 小时 / OUTBOUND 2 小时 / WORK 4 小时 / TRACK 24 小时。
          未配置的步骤会明确标成「内置兜底」，不会让人误以为有人配过。
        </p>
      </SectionCard>

      {/* 5. 人工介入待办 */}
      <SectionCard title="人工介入待办" description="下游 1 工作台未交付：本页待办就是当前权威待办">
        {selected ? (
          <div className="mb-3 flex items-end gap-3">
            <Field label="为当前案例登记待办">
              <Input
                value={todoTitle}
                placeholder="例：升级给仓储主管"
                onChange={(e) => setTodoTitle(e.target.value)}
              />
            </Field>
            <Button onClick={submitTodo} disabled={working || !todoTitle}>
              登记
            </Button>
          </div>
        ) : null}
        {loading ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : !todos || todos.items.length === 0 ? (
          <EmptyState title="暂无待办" description="当前没有需要人工介入的履约异常。" />
        ) : (
          <ul className="space-y-2 text-sm">
            {todos.items.map((t) => (
              <li key={t.id} className="rounded border p-2">
                <div className="flex items-center gap-2">
                  <span className="font-medium">{t.title}</span>
                  <span className="text-muted-foreground text-xs">{todoSummary(t)}</span>
                  {t.severity === 'CRITICAL' ? (
                    <span className="text-xs font-semibold text-red-600">严重</span>
                  ) : null}
                </div>
                <div className="text-muted-foreground text-xs">
                  采集于 {t.capturedAt || '—'} · 来源 {t.sourceKindLabel}
                </div>
                {t.status === 'OPEN' ? (
                  <Button
                    variant="secondary"
                    className="mt-1"
                    disabled={working}
                    onClick={() => actOnTodo(t, 'CLAIM')}
                  >
                    认领
                  </Button>
                ) : null}
                {t.status === 'CLAIMED' ? (
                  <Button
                    variant="secondary"
                    className="mt-1"
                    disabled={working}
                    onClick={() => actOnTodo(t, 'DONE')}
                  >
                    完成
                  </Button>
                ) : null}
              </li>
            ))}
          </ul>
        )}
        <p className="text-muted-foreground mt-2 text-xs">
          采集时刻与登记时刻刻意分开：登记时刻会把「十分钟前就该看的单」说成刚刚出现， SLA
          排序会跟着算错。
        </p>
      </SectionCard>
    </div>
  );
}
