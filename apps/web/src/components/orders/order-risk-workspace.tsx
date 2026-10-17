'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Gauge, RefreshCw, ShieldAlert, Signal, UserCheck } from 'lucide-react';
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
  RiskAssessment,
  RiskCapabilities,
  RiskDetail,
  RiskRule,
  RiskSignal,
  RiskSignalInput,
  Summary,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionText,
  canReview,
  componentStatusText,
  createAssessment,
  createSignal,
  dimensionText,
  errorText,
  evaluationStatusText,
  fetchAssessments,
  fetchAssessment,
  fetchCapabilities,
  fetchOverview,
  fetchRule,
  fetchSignals,
  finalActionText,
  isoText,
  money,
  pageText,
  rawValueText,
  reasonText,
  saveRule,
  score,
  scoreText,
  signalCodeText,
  sourceText,
  submitReview,
  verdictText,
  weight,
} from './order-risk-model';

const PAGE_SIZE = 20;

/** 登记信号时主体键默认按客户档案 ID 填，与后端的主口径一致。 */
const SUBJECT_KIND = 'CUSTOMER_PARTY' as const;

const SIGNAL_CODES = [
  'REFUND_RATE',
  'DISPUTE_RATE',
  'DEVICE_IP_LINK_COUNT',
  'SHARED_DEVICE_COUNT',
  'SHARED_IP_COUNT',
];

/** FormField 的 children 必须是 Input，所以下拉框自己配 label。 */
function SelectField({
  id,
  label,
  value,
  onChange,
  options,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: { value: string; label: string }[];
}) {
  return (
    <div className="space-y-2">
      <label htmlFor={id} className="text-label-13 text-foreground block">
        {label}
      </label>
      <select
        id={id}
        className="border-border bg-surface w-full rounded border px-2 py-1.5"
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}

/**
 * 9.2.2.2 风控校验工作区。
 *
 * <p>蓝图：「多信号风险打分：买家历史（退款率、纠纷率）、设备与 IP 关联（16）、下单频次、
 * 金额异常、地址集中度；输出风险分与建议动作（放行 / 人工 / 拒发）。」
 *
 * <p><b>四个区块的顺序对应「先问数据够不够，再问打了多少分，最后问谁来定」</b>：
 *
 * <ol>
 *   <li><b>能力与缺口</b>：15 客户与 16 合规在集成头都没接通（前者只交付了 crm 服务里的
 *       15.4.1.1，后者一个都没交付），所以「买家历史」与「设备 IP」两维靠**租户登记的信号**
 *       撑住。这一点必须在第一屏说清楚，否则运营会以为风险分是从系统里读来的。</li>
 *   <li><b>规则</b>：五维权重、两个阈值、缺信号口径全部可维护。没配规则时下面都不可写 ——
 *       不给隐含默认值。</li>
 *   <li><b>信号登记</b>：把租户自己量到的退款率 / 纠纷率 / 设备 IP 关联登记进来。
 *       「采集时刻」必填且不能填成登记时刻。</li>
 *   <li><b>评估与人工复核</b>：列表 + 详情（五个维度逐行明细）。
 *       「建议动作」与「最终动作」分行显示：建议拒发的单永远显示「尚无人工结论」。</li>
 * </ol>
 */
export function OrderRiskWorkspace() {
  const [capabilities, setCapabilities] = useState<RiskCapabilities | null>(null);
  const [overview, setOverview] = useState<Summary | null>(null);
  const [rule, setRule] = useState<RiskRule | null>(null);
  const [ruleMissing, setRuleMissing] = useState(false);
  const [signals, setSignals] = useState<RiskSignal[]>([]);
  const [signalTotal, setSignalTotal] = useState(0);
  const [page, setPage] = useState<RiskAssessment[]>([]);
  const [pageMeta, setPageMeta] = useState({ total: 0, page: 0, size: PAGE_SIZE });
  const [detail, setDetail] = useState<RiskDetail | null>(null);

  const [keyword, setKeyword] = useState('');
  const [actionFilter, setActionFilter] = useState('');
  const [pendingOnly, setPendingOnly] = useState(false);
  const [currentPage, setCurrentPage] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [signalCode, setSignalCode] = useState('REFUND_RATE');
  const [subjectKey, setSubjectKey] = useState('');
  const [measuredValue, setMeasuredValue] = useState('0.10');
  const [capturedAt, setCapturedAt] = useState('');

  const [reviewDecision, setReviewDecision] = useState('SHIP');
  const [reviewReason, setReviewReason] = useState('FALSE_POSITIVE');
  const [reviewNote, setReviewNote] = useState('');

  const loadRule = useCallback(async () => {
    try {
      setRule(await fetchRule());
      setRuleMissing(false);
    } catch (e) {
      const status = (e as { status?: number }).status;
      if (status === 404) {
        setRule(null);
        setRuleMissing(true);
        return;
      }
      setError(errorText(e));
    }
  }, []);

  const reload = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [caps, ov, sig, pg] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchSignals({ page: 0, size: PAGE_SIZE }),
        fetchAssessments({
          keyword: keyword || undefined,
          recommendedAction: actionFilter || undefined,
          pendingReviewOnly: pendingOnly || undefined,
          page: currentPage,
          size: PAGE_SIZE,
        }),
      ]);
      setCapabilities(caps);
      setOverview(ov);
      setSignals(sig.items);
      setSignalTotal(sig.total);
      setPage(pg.items);
      setPageMeta({ total: pg.total, page: pg.page, size: pg.size });
      await loadRule();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, actionFilter, pendingOnly, currentPage, loadRule]);

  useEffect(() => {
    void reload();
  }, [reload]);

  const openDetail = useCallback(async (id: string) => {
    setBusy(true);
    setError(null);
    try {
      setDetail(await fetchAssessment(id));
      setNotice(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, []);

  const submitSignal = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const input: RiskSignalInput = {
        signalCode: signalCode as RiskSignalInput['signalCode'],
        subjectKind: SUBJECT_KIND,
        subjectKey: subjectKey.trim(),
        measuredValue: Number(measuredValue),
        signalSource: 'TENANT_MAINTAINED',
        sourceReference: '',
        capturedAt: capturedAt ? new Date(capturedAt).toISOString() : new Date().toISOString(),
        note: '',
      };
      await createSignal(input);
      setNotice(`已登记「${signalCodeText(signalCode)}」= ${measuredValue}`);
      setSubjectKey('');
      await reload();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [signalCode, subjectKey, measuredValue, capturedAt, reload]);

  const runAssessment = useCallback(
    async (orderId: string) => {
      setBusy(true);
      setError(null);
      try {
        const saved = await createAssessment({ orderId: orderId.trim(), note: '' });
        setNotice(saved.summary);
        await openDetail(saved.assessment.id);
        await reload();
      } catch (e) {
        setError(errorText(e));
      } finally {
        setBusy(false);
      }
    },
    [openDetail, reload],
  );

  const doReview = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await submitReview(detail.assessment.id, {
        decision: reviewDecision as 'SHIP',
        reasonCode: reviewReason as 'FALSE_POSITIVE',
        note: reviewNote,
      });
      setNotice(saved.summary);
      setReviewNote('');
      await openDetail(detail.assessment.id);
      await reload();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }, [detail, reviewDecision, reviewReason, reviewNote, openDetail, reload]);

  const signalOptions = useMemo(
    () => SIGNAL_CODES.map((code) => ({ value: code, label: signalCodeText(code) })),
    [],
  );

  return (
    <div className="space-y-6">
      <PageHeader
        title="风控校验"
        description="多信号风险打分：买家历史、设备与 IP 关联、下单频次、金额异常、地址集中度；输出风险分与建议动作。"
        actions={
          <Button onClick={() => void reload()} disabled={loading || busy}>
            <RefreshCw className={loading ? 'animate-spin' : ''} aria-hidden />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="info" title="已完成">
          {notice}
        </Notice>
      )}

      {/* ---------------------------------------------------- 1. 能力与上游缺口 */}
      <SectionCard
        title="能力与上游缺口"
        extra={capabilities && <span className="text-copy-13 text-muted">{capabilities.weightCheck}</span>}
      >
        {capabilities ? (
          <div className="space-y-3">
            <Notice tone={capabilities.crossServiceReadAvailable ? 'info' : 'warning'} title="上游接线情况">
              {capabilities.gaps}
            </Notice>
            <ul className="space-y-2">
              {capabilities.dimensions.map((dimension) => (
                <li
                  key={dimension.code}
                  className="border-border flex flex-wrap items-start justify-between gap-2 border-b pb-2 last:border-0"
                >
                  <div className="min-w-0">
                    <div className="text-copy-14 text-foreground font-medium">{dimension.label}</div>
                    <div className="text-copy-13 text-muted-foreground">{dimension.source}</div>
                    {!dimension.upstreamDelivered && dimension.gap && (
                      <div className="text-copy-13 text-warning">{dimension.gap}</div>
                    )}
                  </div>
                  <span
                    className={
                      dimension.upstreamDelivered
                        ? 'bg-success-soft text-success rounded px-2 py-0.5 text-xs'
                        : 'bg-warning-soft text-warning rounded px-2 py-0.5 text-xs'
                    }
                  >
                    {dimension.upstreamDelivered ? '已接通' : '未接通'}
                  </span>
                </li>
              ))}
            </ul>
            <div className="text-copy-13 text-muted-foreground">{capabilities.scoringOrder}</div>
            <Notice tone="warning" title={`下游 ${capabilities.downstream.moduleId}「${capabilities.downstream.name}」尚未交付`}>
              {capabilities.downstream.handoff}
            </Notice>
          </div>
        ) : (
          <EmptyState title="能力声明加载中" description="正在读取本模块的上下游可用性。" />
        )}
      </SectionCard>

      {/* ---------------------------------------------------- 2. 概览 */}
      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="评估总数" value={overview.total} icon={<Gauge aria-hidden />} />
          <StatCard
            label="待人工复核"
            value={overview.pendingReviewCount}
            icon={<UserCheck aria-hidden />}
          />
          <StatCard
            label="建议拒发"
            value={overview.holdShipmentCount}
            icon={<ShieldAlert aria-hidden />}
          />
          <StatCard
            label="信号不足未出分"
            value={overview.insufficientSignalCount}
            icon={<Signal aria-hidden />}
          />
        </div>
      )}

      {/* ---------------------------------------------------- 3. 规则 */}
      <SectionCard title="风控规则">
        {ruleMissing ? (
          <Notice tone="warning" title="本租户尚未登记风控规则">
            风控分会影响一整批订单的发货节奏，本模块不提供隐含默认值 —— 请先 PUT
            /trade/orders/risk-validation/rule 登记规则（五维权重合计必须恰好 1.0000），
            登记之后才能触发评估。
          </Notice>
        ) : rule ? (
          <div className="space-y-3">
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <Cell label="规则名" value={rule.ruleName} />
              <Cell label="版本" value={`第 ${rule.version} 版`} />
              <Cell label="建议人工阈值" value={score(rule.manualReviewThreshold)} />
              <Cell label="建议拒发阈值" value={score(rule.holdShipmentThreshold)} />
            </div>
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
              <div>
                <div className="text-copy-13 text-muted-foreground">五维权重</div>
                <div className="text-copy-13 font-mono">
                  买家 {weight(rule.weightBuyerHistory)} / 设备IP {weight(rule.weightDeviceIpLink)} /
                  频次 {weight(rule.weightOrderFrequency)} / 金额 {weight(rule.weightAmountAnomaly)} /
                  地址 {weight(rule.weightAddressConcentration)}
                </div>
              </div>
              <Cell
                label="缺信号口径"
                value={
                  rule.missingSignalPolicy === 'BLOCK_EVALUATION' ? '缺任一维度即不出分' : '按已知维度打分'
                }
              />
              <Cell label="信号新鲜度" value={`${rule.signalMaxAgeHours} 小时`} />
              <Cell label="下单频次窗" value={`${rule.frequencyWindowHours} 小时 / 打满 ${rule.frequencyMaxOrders} 笔`} />
              <Cell
                label="金额异常打满档"
                value={`${score(rule.amountAnomalyRatio)} 倍（至少 ${rule.amountAnomalyMinOrders} 笔同币种历史）`}
              />
              <Cell
                label="地址集中度窗"
                value={`${rule.addressConcentrationWindowHours} 小时 / 打满 ${rule.addressConcentrationMaxOrders} 笔`}
              />
            </div>
          </div>
        ) : (
          <EmptyState title="规则加载中" description="正在读取风控规则。" />
        )}
      </SectionCard>

      {/* ---------------------------------------------------- 4. 信号登记 */}
      <SectionCard title="风险信号登记">
        <Notice tone="warning" title="这两维的上游都还没接通">
          买家历史与设备 IP 关联的上游（15 客户 / 16 合规）在集成头都未交付，下面登记的
          是它们**真实参与打分**的数据来源，不是占位。正式接口到位后由同步器按同一形状写入，
          这里不用改结构。
        </Notice>
        <div className="mt-3 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <SelectField
            id="risk-signal-code"
            label="信号"
            value={signalCode}
            onChange={setSignalCode}
            options={signalOptions}
          />
          <FormField id="risk-subject-key" label="主体键（客户档案 ID）">
            <Input value={subjectKey} onChange={(e) => setSubjectKey(e.target.value)} />
          </FormField>
          <FormField id="risk-measured" label="测量值（比率 0..1 / 计数 >0）">
            <Input value={measuredValue} onChange={(e) => setMeasuredValue(e.target.value)} />
          </FormField>
          <FormField id="risk-captured" label="采集时刻（留空取现在）">
            <Input
              type="datetime-local"
              value={capturedAt}
              onChange={(e) => setCapturedAt(e.target.value)}
            />
          </FormField>
        </div>
        <div className="mt-3">
          <Button onClick={() => void submitSignal()} disabled={busy || !subjectKey.trim()}>
            登记信号
          </Button>
        </div>
        <div className="text-copy-13 text-muted-foreground mt-3">已登记 {signalTotal} 条</div>
        <ul className="mt-2 space-y-1">
          {signals.map((signal) => (
            <li key={signal.id} className="text-copy-13 flex flex-wrap justify-between gap-2">
              <span>
                {signalCodeText(signal.signalCode)} = {signal.measuredValue}
              </span>
              <span className="text-muted-foreground">
                {sourceText(signal.signalSource)} · 采集于 {isoText(signal.capturedAt)}
              </span>
            </li>
          ))}
          {signals.length === 0 && (
            <li className="text-copy-13 text-muted-foreground">
              还没有登记任何信号 —— 五码未齐时对应的维度会判「缺信号」。
            </li>
          )}
        </ul>
      </SectionCard>

      {/* ---------------------------------------------------- 5. 触发评估 */}
      <SectionCard title="触发一次评估">
        <Notice tone="info" title="评估基准时刻取订单的提交时刻">
          同一张单重复评估结果恒定。信号的新鲜度也是相对这个时刻判的，
          且**排除该时刻之后采集的信号** —— 一条在订单提交之后才量到的画像，
          对这一单的决策没有贡献。
        </Notice>
        <AssessTrigger busy={busy} onRun={runAssessment} />
      </SectionCard>

      {/* ---------------------------------------------------- 6. 评估列表 */}
      <SectionCard title="风控评估">
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <FormField id="risk-keyword" label="订单号 / 规则名">
            <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} />
          </FormField>
          <SelectField
            id="risk-action-filter"
            label="建议动作"
            value={actionFilter}
            onChange={setActionFilter}
            options={[
              { value: '', label: '全部' },
              { value: 'RELEASE', label: '放行' },
              { value: 'MANUAL_REVIEW', label: '人工复核' },
              { value: 'HOLD_SHIPMENT', label: '建议拒发' },
            ]}
          />
          <div className="space-y-2">
            <span className="text-label-13 text-foreground block">只看待人工复核</span>
            <label className="text-copy-13 flex items-center gap-2 py-1.5">
              <input
                id="risk-pending-only"
                type="checkbox"
                checked={pendingOnly}
                onChange={(e) => setPendingOnly(e.target.checked)}
              />
              是
            </label>
          </div>
          <div className="flex items-end">
            <Button onClick={() => setCurrentPage(0)} disabled={loading || busy}>
              应用筛选
            </Button>
          </div>
        </div>

        {loading ? (
          <EmptyState title="加载中" description="正在读取风控评估。" />
        ) : page.length === 0 ? (
          <EmptyState
            title="还没有风控评估"
            description="在上方触发一次评估，或先登记风控规则与风险信号。"
          />
        ) : (
          <div className="mt-3 overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-border border-b text-left">
                  <th className="py-2">订单</th>
                  <th className="py-2">风险分</th>
                  <th className="py-2">建议动作</th>
                  <th className="py-2">最终动作</th>
                  <th className="py-2">维度</th>
                  <th className="py-2">基准时刻</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {page.map((row) => (
                  <tr key={row.id} className="border-border border-b last:border-0">
                    <td className="py-2">
                      <div className="font-mono">{row.orderNo}</div>
                      <div className="text-muted-foreground text-xs">
                        {money(row.orderAmount)} {row.orderCurrency}
                      </div>
                    </td>
                    <td className="py-2">
                      <div className="font-mono">{scoreText(row)}</div>
                      <div className="text-muted-foreground text-xs">
                        {evaluationStatusText(row.evaluationStatus)}
                      </div>
                    </td>
                    <td className="py-2">
                      <div className={row.recommendedAction === 'HOLD_SHIPMENT' ? 'text-destructive' : ''}>
                        {actionText(row.recommendedAction)}
                      </div>
                      {row.manualReviewRequired && (
                        <div className="text-warning text-xs">需人工复核</div>
                      )}
                    </td>
                    <td className="py-2">
                      <div>{finalActionText(row.finalAction)}</div>
                      {row.finalAction === null && (
                        <div className="text-muted-foreground text-xs">分数不能单独作为拒绝依据</div>
                      )}
                    </td>
                    <td className="py-2">
                      已计 {row.scoredDimensionCount} / 缺 {row.missingDimensionCount}
                    </td>
                    <td className="text-muted-foreground py-2 text-xs">
                      {isoText(row.evaluatedReferenceAt)}
                    </td>
                    <td className="py-2">
                      <Button onClick={() => void openDetail(row.id)} disabled={busy}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        <div className="text-copy-13 text-muted-foreground mt-3 flex items-center justify-between">
          <span>{pageText(pageMeta.page, pageMeta.size, pageMeta.total)}</span>
          <span className="flex gap-2">
            <Button
              onClick={() => setCurrentPage((p) => Math.max(0, p - 1))}
              disabled={currentPage === 0 || loading}
            >
              上一页
            </Button>
            <Button
              onClick={() => setCurrentPage((p) => p + 1)}
              disabled={(currentPage + 1) * PAGE_SIZE >= pageMeta.total || loading}
            >
              下一页
            </Button>
          </span>
        </div>
      </SectionCard>

      {/* ---------------------------------------------------- 7. 详情 + 人工复核 */}
      {detail && (
        <SectionCard title="评估详情">
          <div className="space-y-4">
            <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <Cell label="订单" value={detail.assessment.orderNo} />
              <Cell label="风险分" value={scoreText(detail.assessment)} />
              <Cell label="建议动作" value={actionText(detail.assessment.recommendedAction)} />
              <Cell label="最终动作" value={finalActionText(detail.assessment.finalAction)} />
            </div>

            <Notice
              tone={detail.assessment.finalAction === null ? 'warning' : 'info'}
              title="建议与结论分开"
            >
              {verdictText(detail.assessment)}
            </Notice>

            <div>
              <div className="mb-2 text-copy-14 text-foreground font-medium">五个维度</div>
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-border border-b text-left">
                      <th className="py-2">维度</th>
                      <th className="py-2">状态</th>
                      <th className="py-2">原始值</th>
                      <th className="py-2">归一化</th>
                      <th className="py-2">权重</th>
                      <th className="py-2">贡献</th>
                      <th className="py-2">来源与依据</th>
                    </tr>
                  </thead>
                  <tbody>
                    {detail.components.map((component) => (
                      <tr key={component.id} className="border-border border-b align-top last:border-0">
                        <td className="py-2">{dimensionText(component.dimension)}</td>
                        <td className="py-2">
                          <span className={component.status === 'SCORED' ? 'text-success' : 'text-warning'}>
                            {componentStatusText(component.status)}
                          </span>
                        </td>
                        <td className="py-2 font-mono">{rawValueText(component)}</td>
                        <td className="py-2 font-mono">{score(component.normalizedScore)}</td>
                        <td className="py-2 font-mono">{weight(component.weight)}</td>
                        <td className="py-2 font-mono">{score(component.contribution)}</td>
                        <td className="py-2">
                          <div>{sourceText(component.signalSource)}</div>
                          {component.signalCapturedAt && (
                            <div className="text-muted-foreground text-xs">
                              采集于 {isoText(component.signalCapturedAt)}
                            </div>
                          )}
                          <div className="text-muted-foreground text-xs">{component.explanation}</div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </div>

            {detail.review && (
              <div className="border-border rounded border p-3 text-sm">
                <div className="text-copy-14 text-foreground font-medium">人工复核留痕</div>
                <div>结论：{finalActionText(detail.review.decision)}</div>
                <div>原因：{reasonText(detail.review.reasonCode)}</div>
                <div className="text-muted-foreground">复核于 {isoText(detail.review.reviewedAt)}</div>
                {detail.review.note && <div>备注：{detail.review.note}</div>}
              </div>
            )}

            {canReview(detail.assessment) && (
              <div className="space-y-3">
                <Notice tone="warning" title="分数不能单独作为拒绝依据">
                  蓝图加粗句的落点：分数再高也只是建议，真正的放行 / 拒发由下面这一笔人工结论决定。
                </Notice>
                <div className="grid gap-3 sm:grid-cols-3">
                  <SelectField
                    id="risk-review-decision"
                    label="复核结论"
                    value={reviewDecision}
                    onChange={setReviewDecision}
                    options={[
                      { value: 'SHIP', label: '复核后发货' },
                      { value: 'REJECT', label: '复核后拒发' },
                      { value: 'DEFERRED', label: '转延期，等材料' },
                      { value: 'ESCALATED', label: '升级合规人工' },
                    ]}
                  />
                  <SelectField
                    id="risk-review-reason"
                    label="原因"
                    value={reviewReason}
                    onChange={setReviewReason}
                    options={[
                      { value: 'FALSE_POSITIVE', label: '误判，疑似正常复购客户' },
                      { value: 'CONFIRMED_RISK', label: '复核确认有风险' },
                      { value: 'PENDING_EVIDENCE', label: '仍需材料' },
                      { value: 'ESCALATED_COMPLIANCE', label: '升级合规处理' },
                    ]}
                  />
                  <FormField id="risk-review-note" label="备注">
                    <Input value={reviewNote} onChange={(e) => setReviewNote(e.target.value)} />
                  </FormField>
                </div>
                <Button onClick={() => void doReview()} disabled={busy}>
                  提交复核
                </Button>
              </div>
            )}
          </div>
        </SectionCard>
      )}
    </div>
  );
}

/** 标签 + 值的小格子。 */
function Cell({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-copy-13 text-muted-foreground">{label}</div>
      <div className="text-copy-14 text-foreground font-medium">{value}</div>
    </div>
  );
}

/** 触发评估的输入区（订单 ID）。刻意独立成小组件，便于测试替换。 */
function AssessTrigger({
  busy,
  onRun,
}: {
  busy: boolean;
  onRun: (orderId: string) => void | Promise<void>;
}) {
  const [orderId, setOrderId] = useState('');
  return (
    <div className="mt-3 flex flex-wrap items-end gap-3">
      <div className="min-w-64 flex-1">
        <FormField id="risk-order-id" label="订单 ID">
          <Input value={orderId} onChange={(e) => setOrderId(e.target.value)} />
        </FormField>
      </div>
      <Button onClick={() => void onRun(orderId)} disabled={busy || !orderId.trim()}>
        触发评估
      </Button>
    </div>
  );
}
