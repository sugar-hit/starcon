'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, RefreshCw, Send, ShieldQuestion } from 'lucide-react';
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

import type {
  ShipmentWritebackBoard,
  ShipmentWritebackCapabilities,
  ShipmentWritebackTaskDetail,
  ShipmentWritebackTaskPage,
} from '../../../../../packages/api-contracts/src/index';
import {
  batchRetryShipmentWriteback,
  correctShipmentWriteback,
  errorText,
  fetchShipmentWritebackBoard,
  fetchShipmentWritebackCapabilities,
  fetchShipmentWritebackTask,
  fetchShipmentWritebackTasks,
  registerShipmentWritebackOutcome,
  retryShipmentWriteback,
  shipmentWritebackActions,
  shipmentWritebackBatchSummary,
  shipmentWritebackBlockedHint,
  shipmentWritebackGroupByOptions,
  shipmentWritebackStatusLabel,
  shipmentWritebackSummaryView,
  shipmentWritebackTone,
  shipmentWritebackVerificationLabel,
  submitShipmentWriteback,
  verifyShipmentWriteback,
  type ShipmentWritebackTone,
} from './shipment-writeback-model';

const PAGE_SIZE = 20;

const STATUS_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'PENDING', label: '待回传' },
  { value: 'SUBMITTED', label: '已提交平台' },
  { value: 'CONFIRMED', label: '平台已受理' },
  { value: 'REJECTED', label: '平台拒绝' },
  { value: 'PENDING_VERIFICATION', label: '结果未知·待查证' },
  { value: 'SKIPPED', label: '决定不回传' },
  { value: 'SUPERSEDED', label: '已被修正版取代' },
];

const VERIFICATION_OPTIONS = [
  { value: '', label: '全部' },
  { value: 'NOT_REQUIRED', label: '无需查证' },
  { value: 'CONFIRMED_APPLIED', label: '查证确认已应用' },
  { value: 'CONFIRMED_NOT_APPLIED', label: '查证确认未应用' },
  { value: 'STILL_UNKNOWN', label: '查证后仍不确定' },
];

const CONCLUSION_OPTIONS = [
  { value: 'CONFIRMED_APPLIED', label: '查证确认平台已应用（不得重发）' },
  { value: 'CONFIRMED_NOT_APPLIED', label: '查证确认平台未应用（允许重发）' },
  { value: 'STILL_UNKNOWN', label: '仍不确定（继续禁止重发）' },
];

/**
 * 三态各自的视觉分档。
 *
 * <p>`unknown` 刻意不用红也不用绿：它是「不知道」，不是「不行」也不是「行了」。
 * 把它染成红色会让运营以为平台已经拒绝、可以放心重发 —— 那正是重复发货的起点。
 */
const TONE_CLASS: Record<ShipmentWritebackTone, string> = {
  pending: 'bg-slate-100 text-slate-700',
  inflight: 'bg-blue-100 text-blue-800',
  ok: 'bg-emerald-100 text-emerald-800',
  bad: 'bg-red-100 text-red-800',
  unknown: 'bg-amber-100 text-amber-900',
  muted: 'bg-slate-100 text-slate-500',
};

function Badge({ tone, children }: { tone: ShipmentWritebackTone; children: React.ReactNode }) {
  return (
    <span className={`inline-flex shrink-0 rounded px-1.5 py-0.5 text-xs ${TONE_CLASS[tone]}`}>
      {children}
    </span>
  );
}

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
        {props.options.map((o) => (
          <option key={o.value} value={o.value}>
            {o.label}
          </option>
        ))}
      </select>
    </div>
  );
}

function moment(value: string | undefined): string {
  if (!value) return '—';
  return value.replace('T', ' ').replace(/\+.*$/, '').replace('Z', '');
}

/**
 * 9.4.2.1 平台回传工作区。
 *
 * <p>四个区块按「会不会让平台判我们迟发」排：**监控汇总 → 分组看板 → 回传任务清单 →
 * 任务明细与补偿动作**。
 *
 * <p>这一页存在的理由就是蓝图关系里那句加粗：「回传状态必须<b>可监控可补偿</b>」。
 * 所以汇总条把「该发的 / 到点的 / 结果未知 / 已达上限」四个数并排放在最上面 ——
 * 结果未知单列一格且染琥珀色，因为它是唯一「需要人去看」的一类。
 *
 * <p>明细区的动作按钮按 `shipmentWritebackActions` 禁用：未知态<b>只</b>留「登记查证结论」，
 * 重试按钮不出现。前端这道只为省一次往返，后端仍会独立拒一次。
 */
export function ShipmentWritebackWorkspace() {
  const [capabilities, setCapabilities] = useState<ShipmentWritebackCapabilities | null>(null);
  const [board, setBoard] = useState<ShipmentWritebackBoard | null>(null);
  const [page, setPage] = useState<ShipmentWritebackTaskPage | null>(null);
  const [detail, setDetail] = useState<ShipmentWritebackTaskDetail | null>(null);

  const [groupBy, setGroupBy] = useState('STATUS');
  const [status, setStatus] = useState('');
  const [platformCode, setPlatformCode] = useState('');
  const [carrierCode, setCarrierCode] = useState('');
  const [verification, setVerification] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [outcomeDraft, setOutcomeDraft] = useState('CONFIRMED');
  const [requestId, setRequestId] = useState('');
  const [errorMessage, setErrorMessage] = useState('');
  const [conclusion, setConclusion] = useState('CONFIRMED_NOT_APPLIED');
  const [evidence, setEvidence] = useState('');
  const [correctionReason, setCorrectionReason] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, boardData, pageData] = await Promise.all([
        fetchShipmentWritebackCapabilities(),
        fetchShipmentWritebackBoard({ groupBy, status, platformCode, carrierCode }),
        fetchShipmentWritebackTasks({
          status,
          platformCode,
          carrierCode,
          verification,
          keyword,
          page: pageIndex,
          size: PAGE_SIZE,
        }),
      ]);
      setCapabilities(caps);
      setBoard(boardData);
      setPage(pageData);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [groupBy, status, platformCode, carrierCode, verification, keyword, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  const summary = page ? shipmentWritebackSummaryView(page.summary) : null;

  const run = async (work: () => Promise<ShipmentWritebackTaskDetail>, done: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const updated = await work();
      setDetail(updated);
      setNotice(done);
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const openDetail = async (taskId: string) => {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchShipmentWritebackTask(taskId));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const batchRetry = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const r = await batchRetryShipmentWriteback({
        platformCode: platformCode || null,
        carrierCode: carrierCode || null,
        limit: 50,
        note: '批量重试',
      });
      setNotice(shipmentWritebackBatchSummary(r).join('；'));
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const task = detail?.task;
  const actions = shipmentWritebackActions(task?.status, task?.verification);
  const unknownHint = shipmentWritebackBlockedHint(task?.status, task?.verification);

  return (
    <div className="flex flex-col gap-6 p-6">
      <PageHeader
        title="平台回传"
        description="把发货信息回传平台并跟踪结果：成功 / 失败 / 结果未知。回传失败会让平台显示未发货并可能判定迟发。"
        actions={
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} /> 刷新
            </Button>
            <Button onClick={() => void batchRetry()} disabled={busy}>
              <Send className="h-4 w-4" /> 批量重试
            </Button>
          </div>
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

      {/* 监控：可监控的第一眼就在这里 */}
      <SectionCard title="回传状态监控">
        {loading && !summary ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : summary ? (
          <>
            <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
              <StatCard label="回传任务总数" value={String(summary.total)} />
              <StatCard label="平台已受理" value={String(summary.confirmed)} polarity="positive" />
              <StatCard label="平台拒绝" value={String(summary.rejected)} polarity="negative" />
              <StatCard
                label="结果未知·待查证"
                value={String(summary.unknown)}
                polarity="negative"
              />
              <StatCard label="当前可重发" value={String(summary.resendAllowed)} />
            </div>
            <ul className="text-muted-foreground mt-3 list-disc pl-5 text-xs">
              <li>
                「结果未知」单列一格：平台可能已收到也可能没收到，直接重发会造成平台侧重复发货，
                必须先查证。
              </li>
              <li>
                到点待发 {summary.due} 条 · 已达重试上限 {summary.atCap} 条。
              </li>
            </ul>
          </>
        ) : null}
      </SectionCard>

      {/* 看板：分组是服务端 GROUP BY，不是页内统计 */}
      <SectionCard title="分组看板">
        <div className="mb-3 max-w-xs">
          <SelectField
            id="wb-group"
            label="分组维度"
            value={groupBy}
            onChange={setGroupBy}
            options={shipmentWritebackGroupByOptions}
          />
        </div>
        {board && board.groups.length ? (
          <ul className="flex flex-col gap-1 text-sm">
            {board.groups.map((g) => (
              <li key={g.groupKey} className="flex items-center gap-3">
                <span className="w-40 shrink-0 font-medium">{g.groupLabel}</span>
                <span className="text-muted-foreground">{g.taskCount} 条</span>
                {g.unknownCount > 0 ? <Badge tone="unknown">未知 {g.unknownCount}</Badge> : null}
                {g.rejectedCount > 0 ? <Badge tone="bad">拒绝 {g.rejectedCount}</Badge> : null}
              </li>
            ))}
          </ul>
        ) : (
          <p className="text-muted-foreground text-sm">还没有回传任务。</p>
        )}
        {board?.boundaryNotes?.length ? (
          <ul className="text-muted-foreground mt-3 list-disc pl-5 text-xs">
            {board.boundaryNotes.map((n) => (
              <li key={n}>{n}</li>
            ))}
          </ul>
        ) : null}
      </SectionCard>

      {/* 清单 */}
      <SectionCard title="回传任务">
        <div className="mb-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-5">
          <SelectField
            id="wb-status"
            label="回传状态"
            value={status}
            onChange={setStatus}
            options={STATUS_OPTIONS}
          />
          <SelectField
            id="wb-verification"
            label="查证结论"
            value={verification}
            onChange={setVerification}
            options={VERIFICATION_OPTIONS}
          />
          <FormField id="wb-platform" label="平台代码">
            <Input
              value={platformCode}
              onChange={(e) => setPlatformCode(e.target.value)}
              placeholder="AMAZON"
            />
          </FormField>
          <FormField id="wb-carrier" label="承运商代码">
            <Input
              value={carrierCode}
              onChange={(e) => setCarrierCode(e.target.value)}
              placeholder="SF"
            />
          </FormField>
          <FormField id="wb-keyword" label="关键词">
            <Input
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              placeholder="运单号 / 任务号 / 平台订单号"
            />
          </FormField>
        </div>

        {loading && !page ? (
          <p className="text-muted-foreground text-sm">加载中…</p>
        ) : page && page.items.length ? (
          <div className="flex flex-col gap-2">
            {page.items.map((row) => {
              const tone = shipmentWritebackTone(row.status);
              const hint = shipmentWritebackBlockedHint(row.status, row.verification);
              return (
                <button
                  key={row.id}
                  type="button"
                  onClick={() => void openDetail(row.id)}
                  className="border-input hover:bg-accent flex flex-col gap-1 rounded-md border p-3 text-left"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone={tone}>{shipmentWritebackStatusLabel(row.status)}</Badge>
                    <span className="font-medium">{row.masterWaybillNo}</span>
                    <span className="text-muted-foreground text-xs">
                      {row.carrierCode} · {row.platformCode}
                    </span>
                    {row.revision > 1 ? (
                      <span className="text-muted-foreground text-xs">r{row.revision}</span>
                    ) : null}
                    <span className="text-muted-foreground ml-auto text-xs">
                      尝试 {row.attemptCount}/{row.maxAttempts}
                    </span>
                  </div>
                  {hint ? (
                    <span className="flex items-start gap-1 text-xs text-amber-800">
                      <AlertTriangle className="mt-0.5 h-3 w-3 shrink-0" />
                      {hint}
                    </span>
                  ) : row.blockedReason ? (
                    <span className="text-muted-foreground text-xs">{row.blockedReason}</span>
                  ) : null}
                  {row.lastError ? (
                    <span className="text-muted-foreground text-xs">最近错误：{row.lastError}</span>
                  ) : null}
                </button>
              );
            })}
            <div className="flex items-center gap-2 pt-2">
              <Button
                variant="secondary"
                disabled={page.page <= 0}
                onClick={() => setPageIndex((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <span className="text-muted-foreground text-sm">
                第 {page.page + 1} / {Math.max(1, page.totalPages)} 页 · 共 {page.totalElements} 条
              </span>
              <Button
                variant="secondary"
                disabled={page.page + 1 >= page.totalPages}
                onClick={() => setPageIndex((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        ) : (
          <EmptyState
            title="没有匹配的回传任务"
            description="回传任务是从 9.4.1.2 的发货记录发起的；还没有记录时这里不会出现任何任务。"
          />
        )}
      </SectionCard>

      {/* 明细与补偿 */}
      {detail && task ? (
        <SectionCard title={`任务明细 ${task.taskNo}`}>
          <div className="mb-3 flex flex-wrap items-center gap-2">
            <Badge tone={shipmentWritebackTone(task.status)}>
              {shipmentWritebackStatusLabel(task.status)}
            </Badge>
            <span className="text-muted-foreground text-xs">
              查证：{shipmentWritebackVerificationLabel(task.verification)}
            </span>
            <span className="text-muted-foreground text-xs">幂等键：{task.idempotencyKey}</span>
          </div>

          {unknownHint ? (
            <Notice tone="warning" title="结果未知：先查证再谈重发">
              <span className="flex items-start gap-2">
                <ShieldQuestion className="mt-0.5 h-4 w-4 shrink-0" />
                {unknownHint}
              </span>
            </Notice>
          ) : null}

          <dl className="mt-3 grid gap-2 text-sm sm:grid-cols-2 lg:grid-cols-4">
            {[
              ['平台', task.platformCode],
              ['店铺', task.shopCode],
              ['平台订单号', task.platformOrderRef || '—'],
              ['承运商', task.carrierCode],
              ['主运单号', task.masterWaybillNo],
              ['发货时间', moment(task.shippedAt)],
              ['发货记录', task.sourceRecordNo],
              ['销售订单', task.salesOrderNo || '—'],
            ].map(([k, v]) => (
              <div key={k as string}>
                <dt className="text-muted-foreground text-xs">{k}</dt>
                <dd>{v as string}</dd>
              </div>
            ))}
          </dl>

          {/* 投递 */}
          <div className="mt-4 flex flex-wrap gap-2">
            <Button
              variant="secondary"
              disabled={busy || !actions.canSubmit}
              onClick={() =>
                void run(() => submitShipmentWriteback(task.id), '已投递到平台对接队列')
              }
            >
              <Send className="h-4 w-4" /> 提交投递
            </Button>
            <Button
              variant="secondary"
              disabled={busy || !actions.canRetry}
              onClick={() => void run(() => retryShipmentWriteback(task.id), '已重投一次')}
            >
              重试一次
            </Button>
          </div>

          {/* 登记三态 */}
          {actions.canRegisterOutcome ? (
            <div className="mt-4 flex flex-col gap-3 border-t pt-4">
              <h3 className="font-medium">登记平台结果（三态）</h3>
              <div className="grid gap-3 sm:grid-cols-3">
                <SelectField
                  id="wb-outcome"
                  label="平台结果"
                  value={outcomeDraft}
                  onChange={setOutcomeDraft}
                  options={[
                    { value: 'CONFIRMED', label: '平台已受理' },
                    { value: 'REJECTED', label: '平台拒绝' },
                    { value: 'PENDING_VERIFICATION', label: '结果未知（须写明为什么）' },
                  ]}
                />
                <FormField id="wb-req" label="平台请求号">
                  <Input
                    value={requestId}
                    onChange={(e) => setRequestId(e.target.value)}
                    placeholder="REQ-…"
                  />
                </FormField>
                <FormField id="wb-errmsg" label="错误 / 未知说明">
                  <Input
                    value={errorMessage}
                    onChange={(e) => setErrorMessage(e.target.value)}
                    placeholder="未知必填：超时？连接断了？"
                  />
                </FormField>
              </div>
              <div>
                <Button
                  disabled={busy}
                  onClick={() =>
                    void run(
                      () =>
                        registerShipmentWritebackOutcome(task.id, {
                          status: outcomeDraft as 'CONFIRMED',
                          platformRequestId: requestId,
                          errorMessage,
                          note: '',
                        }),
                      '已登记平台结果',
                    )
                  }
                >
                  登记结果
                </Button>
              </div>
            </div>
          ) : null}

          {/* 查证：未知态唯一的出路 */}
          {actions.canVerify ? (
            <div className="mt-4 flex flex-col gap-3 border-t pt-4">
              <h3 className="font-medium">登记查证结论（重发之前必须先有结论）</h3>
              <div className="grid gap-3 sm:grid-cols-2">
                <SelectField
                  id="wb-conclusion"
                  label="查证结论"
                  value={conclusion}
                  onChange={setConclusion}
                  options={CONCLUSION_OPTIONS}
                />
                <FormField id="wb-evidence" label="查证依据">
                  <Input
                    value={evidence}
                    onChange={(e) => setEvidence(e.target.value)}
                    placeholder="查到的请求号 / 平台后台截图说明"
                  />
                </FormField>
              </div>
              <div>
                <Button
                  disabled={busy}
                  onClick={() =>
                    void run(
                      () =>
                        verifyShipmentWriteback(task.id, {
                          conclusion: conclusion as 'CONFIRMED_NOT_APPLIED',
                          source: 'CHANNEL_VERIFY',
                          evidence,
                        }),
                      '已登记查证结论',
                    )
                  }
                >
                  登记查证结论
                </Button>
              </div>
            </div>
          ) : null}

          {/* 人工修正 */}
          {actions.canCorrect ? (
            <div className="mt-4 flex flex-col gap-3 border-t pt-4">
              <h3 className="font-medium">人工修正（新建一版，原版留作证据）</h3>
              <FormField id="wb-reason" label="修正原因（至少 4 个字符）">
                <Input
                  value={correctionReason}
                  onChange={(e) => setCorrectionReason(e.target.value)}
                  placeholder="例：承运商给错了运单号"
                />
              </FormField>
              <div>
                <Button
                  variant="secondary"
                  disabled={busy || correctionReason.trim().length < 4}
                  onClick={() =>
                    void run(
                      () =>
                        correctShipmentWriteback(task.id, {
                          carrierCode: null,
                          masterWaybillNo: null,
                          shippedAt: null,
                          platformOrderRef: null,
                          parcels: null,
                          reason: correctionReason,
                        }),
                      '已新建修正版',
                    )
                  }
                >
                  提交修正
                </Button>
              </div>
            </div>
          ) : null}

          {/* 尝试凭据与时间线 */}
          <div className="mt-4 border-t pt-4">
            <h3 className="mb-2 font-medium">投递尝试（append-only 凭据）</h3>
            {detail.attempts.length ? (
              <ul className="flex flex-col gap-1 text-sm">
                {detail.attempts.map((a) => (
                  <li key={a.id} className="flex flex-wrap items-center gap-2">
                    <span className="text-muted-foreground">#{a.attemptNo}</span>
                    <span>{a.outcomeLabel}</span>
                    {a.platformRequestId ? (
                      <span className="text-muted-foreground text-xs">{a.platformRequestId}</span>
                    ) : null}
                    {a.nextAttemptAt ? (
                      <span className="text-muted-foreground text-xs">
                        下一次 {moment(a.nextAttemptAt)}
                      </span>
                    ) : null}
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-muted-foreground text-sm">还没有投递尝试。</p>
            )}
          </div>

          {detail.verifications.length ? (
            <div className="mt-4 border-t pt-4">
              <h3 className="mb-2 font-medium">查证记录</h3>
              <ul className="flex flex-col gap-1 text-sm">
                {detail.verifications.map((v) => (
                  <li key={v.id}>
                    {v.conclusionLabel} · {v.sourceLabel}
                    {v.evidence ? (
                      <span className="text-muted-foreground"> — {v.evidence}</span>
                    ) : null}
                  </li>
                ))}
              </ul>
            </div>
          ) : null}
        </SectionCard>
      ) : null}

      {capabilities ? (
        <SectionCard title="能力与边界">
          <ul className="flex flex-col gap-1 text-sm">
            {capabilities.dimensions.map((d) => (
              <li key={d.dimension} className="flex flex-wrap items-center gap-2">
                <Badge tone={d.available ? 'ok' : 'muted'}>
                  {d.available ? '可用' : `不可用（归 ${d.ownerModule}）`}
                </Badge>
                <span className="font-medium">{d.description}</span>
                <span className="text-muted-foreground text-xs">{d.impact}</span>
              </li>
            ))}
          </ul>
          <p className="text-muted-foreground mt-3 text-xs">
            三态策略：{capabilities.threeStatePolicy}
          </p>
          <p className="text-muted-foreground text-xs">
            幂等键：{capabilities.idempotencyKeyBasis}
          </p>
          <p className="text-muted-foreground text-xs">店铺：{capabilities.shopIdPolicy}</p>
        </SectionCard>
      ) : null}
    </div>
  );
}
