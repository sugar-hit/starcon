'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import { RefreshCw } from 'lucide-react';
import { api, ApiError } from '@/lib/api';
import {
  AVAILABILITY_LABELS,
  CONSISTENCY_CHECK_KEYS,
  CONSISTENCY_RULES,
  CONSISTENCY_SEMANTICS,
  CONSISTENCY_SOURCE_SPECS,
  GREEN,
  MAPPING_GAP_STALE_DAYS,
  RED,
  UNDETERMINED,
  VERDICT_LABELS,
  VERDICT_TONES,
  YELLOW,
  available,
  buildAccuracyCheck,
  buildAmountParityCheck,
  buildAnomalyList,
  buildConclusion,
  buildDiffRateCheck,
  buildMappingGapCheck,
  buildReconciliationCheck,
  formatPercent,
  formatQuantity,
  formatTimestamp,
  latestRun,
  sourceAvailabilities,
  unavailable,
  type Access,
  type CheckAvailability,
  type ConsistencyCheck,
  type ConsistencyCheckKey,
  type InventoryHealthGate,
  type MappingGapSummary,
  type ReconciliationFinding,
  type ReconciliationFindingPage,
  type ReconciliationResult,
  type ReconciliationRunPage,
  type SourceAvailability,
} from './consistency-check-model';

/**
 * 1.5.1.2 数据一致性体检工作区（卷 01 工作台与门户）。
 *
 * <p>蓝图输出：「一致性体检报告（分项健康 + 异常清单 + 处置入口）。」
 *
 * <p><b>数据来自三处，跨服务聚合在本页完成</b>：
 *
 * <ul>
 *   <li>{@code GET warehouse/inventory/reconciliation} + {@code /{runId}} +
 *       {@code /{runId}/findings} —— 账实核对结论与差异明细（7.1.2.2）</li>
 *   <li>{@code GET warehouse/inventory/health/gate} —— 库存准确率红线判定（7.6.2.1）</li>
 *   <li>{@code GET channel/mapping-gaps/summary} —— 待处理映射缺失数（2.4.2.3）</li>
 * </ul>
 *
 * <p>另有两项**上游不可读**，本页如实标出、不用 0 或绿色顶替：
 * 12.3.2 对账差异（未交付）与订单/履约/财务三方金额差异（履约侧无金额事实）。
 *
 * <p><b>三种「读不到」必须分开</b>：<b>无权限</b>（你的角色没有这个来源的读权限，
 * 连请求都不发）、<b>读不到</b>（服务不可达 / 报错）、<b>没有数据</b>（读到了但这一档是空的）。
 * 三者都<b>不是 0</b>，也<b>不是健康</b>。
 *
 * <p><b>只读 + 跳转</b>：页面上<b>没有</b>触发核对 / 评估 / 映射 / 差异处理任何写动作，
 * 每个数字的出口是<b>跳到归属系统</b>去处理。
 */

/** 差异明细每页条数（服务端上限 200，这里取 50）。 */
const FINDING_PAGE_SIZE = 50;

/** 差异明细的严重度 → Badge 色调。 */
const FINDING_TONES: Record<string, 'danger' | 'warning' | 'neutral'> = {
  HIGH: 'danger',
  WARN: 'warning',
  INFO: 'neutral',
};

/** 差异明细的严重度人话。 */
const FINDING_LABELS: Record<string, string> = {
  HIGH: '高',
  WARN: '中',
  INFO: '提示',
};

/** 可疑类别人话（07 库存的四个类别）。 */
const CATEGORY_LABELS: Record<string, string> = {
  MISSING: '漏记',
  BACKFILL: '迟到补记',
  DUPLICATE: '重复记',
  LOST_UPDATE: '并发丢失',
};

/** 把异常写成一句话。 */
function describeError(failure: unknown): string {
  if (failure instanceof ApiError) return failure.message;
  if (failure instanceof Error) return failure.message;
  return '服务暂时读不到';
}

export function ConsistencyCheckWorkspace() {
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [ready, setReady] = useState(false);

  const [availability, setAvailability] = useState<Record<
    ConsistencyCheckKey,
    SourceAvailability
  > | null>(null);
  /** 逐来源的读不到原因（无权限也归到这里，措辞不同）。 */
  const [sourceErrors, setSourceErrors] = useState<Record<string, string>>({});

  const [reconciliation, setReconciliation] = useState<ReconciliationRunPage | null>(null);
  const [accuracy, setAccuracy] = useState<InventoryHealthGate | null>(null);
  const [mapping, setMapping] = useState<MappingGapSummary | null>(null);
  const [reconDetail, setReconDetail] = useState<ReconciliationResult | null>(null);
  const [findings, setFindings] = useState<ReconciliationFindingPage | null>(null);
  const [findingPage, setFindingPage] = useState(0);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    setSourceErrors({});
    try {
      // 1. 先向身份服务确认身份与权限：租户**不**来自客户端头。
      const access = await api<Access>('auth/session');
      const avail = sourceAvailabilities(access);
      setAvailability(avail);

      const errors: Record<string, string> = {};
      const permitted = (key: ConsistencyCheckKey) => avail[key]?.permitted === true;

      // 2. 账实核对：先取最近一次批次，再按批次取可疑区间与差异明细。
      let latestRunId: string | null = null;
      if (!permitted('RECONCILIATION')) {
        errors.RECONCILIATION = '无权限 · 对你不开放';
        setReconciliation(null);
        setReconDetail(null);
        setFindings(null);
      } else {
        try {
          const page = await api<ReconciliationRunPage>(
            'warehouse/inventory/reconciliation?page=0&size=1',
          );
          setReconciliation(page);
          const run = latestRun(page.runs);
          latestRunId = run?.id ?? null;
          if (latestRunId) {
            try {
              const detail = await api<ReconciliationResult>(
                `warehouse/inventory/reconciliation/${encodeURIComponent(latestRunId)}`,
              );
              setReconDetail(detail);
            } catch (failure) {
              errors.RECONCILIATION = `结论读到了，但可疑区间读不到（${describeError(failure)}）`;
              setReconDetail(null);
            }
            try {
              const found = await api<ReconciliationFindingPage>(
                `warehouse/inventory/reconciliation/${encodeURIComponent(latestRunId)}` +
                  `/findings?kind=DISCREPANCY&page=${findingPage}&size=${FINDING_PAGE_SIZE}`,
              );
              setFindings(found);
            } catch (failure) {
              errors.RECONCILIATION = `差异明细读不到（${describeError(failure)}）`;
              setFindings(null);
            }
          } else {
            setReconDetail(null);
            setFindings(null);
          }
        } catch (failure) {
          errors.RECONCILIATION = describeError(failure);
          setReconciliation(null);
          setReconDetail(null);
          setFindings(null);
        }
      }

      // 3. 库存准确率红线判定。
      if (!permitted('ACCURACY')) {
        errors.ACCURACY = '无权限 · 对你不开放';
        setAccuracy(null);
      } else {
        try {
          const gate = await api<InventoryHealthGate>('warehouse/inventory/health/gate');
          setAccuracy(gate);
        } catch (failure) {
          errors.ACCURACY = describeError(failure);
          setAccuracy(null);
        }
      }

      // 4. 待处理映射缺失。
      if (!permitted('MAPPING_GAPS')) {
        errors.MAPPING_GAPS = '无权限 · 对你不开放';
        setMapping(null);
      } else {
        try {
          const summary = await api<MappingGapSummary>('channel/mapping-gaps/summary');
          setMapping(summary);
        } catch (failure) {
          errors.MAPPING_GAPS = describeError(failure);
          setMapping(null);
        }
      }

      setSourceErrors(errors);
    } catch (failure) {
      // 🔴 读不到就是读不到：不清空已有结论、不渲染绿色空态，直接把原因摆出来。
      setError(describeError(failure));
    } finally {
      setLoading(false);
      setReady(true);
    }
  }, [findingPage]);

  useEffect(() => {
    void load();
  }, [load]);

  const checks = useMemo<ConsistencyCheck[]>(() => {
    const now = new Date();
    return [
      buildReconciliationCheck(
        reconciliation
          ? available(reconciliation)
          : unavailable<ReconciliationRunPage>(
              sourceErrors.RECONCILIATION?.startsWith('无权限') ? 'NOT_PERMITTED' : 'UNREACHABLE',
              sourceErrors.RECONCILIATION ?? null,
            ),
      ),
      buildAccuracyCheck(
        accuracy
          ? available(accuracy)
          : unavailable<InventoryHealthGate>(
              sourceErrors.ACCURACY?.startsWith('无权限') ? 'NOT_PERMITTED' : 'UNREACHABLE',
              sourceErrors.ACCURACY ?? null,
            ),
      ),
      buildDiffRateCheck(),
      buildAmountParityCheck(),
      buildMappingGapCheck(
        mapping
          ? available(mapping)
          : unavailable<MappingGapSummary>(
              sourceErrors.MAPPING_GAPS?.startsWith('无权限') ? 'NOT_PERMITTED' : 'UNREACHABLE',
              sourceErrors.MAPPING_GAPS ?? null,
            ),
        now,
      ),
    ];
  }, [accuracy, mapping, reconciliation, sourceErrors]);

  const conclusion = useMemo(() => buildConclusion(checks, accuracy), [checks, accuracy]);
  const anomalies = useMemo(() => buildAnomalyList(checks), [checks]);
  const findingTotal = findings?.total ?? 0;
  const findingPageCount = Math.max(1, Math.ceil(findingTotal / FINDING_PAGE_SIZE));

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载数据一致性体检…
      </p>
    );

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          数据一致性体检回答的是<b>「账能不能信」</b>：库存账实核对、库存准确率、
          待处理映射缺失各差在哪，以及<b>哪些分项现在根本判不了</b>。 本页只读 ——{' '}
          <b>处置动作一律回到归属系统完成</b>。
        </p>
        <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
          <RefreshCw className="h-4 w-4" />
          刷新
        </Button>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          <b>读不到</b>：{error}
          <div className="text-copy-12 text-muted-foreground mt-1">
            这一页**没有任何结论** —— 「没读到」不等于「一切正常」，请重试或联系管理员。
          </div>
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}

      {/* ---------------- 整体结论 ---------------- */}
      <section
        className={`rounded-lg border p-4 ${
          conclusion.verdict === RED
            ? 'border-destructive/40 bg-destructive/5'
            : conclusion.verdict === YELLOW
              ? 'border-warning/40 bg-warning/5'
              : conclusion.verdict === UNDETERMINED
                ? 'border-border bg-muted/30'
                : 'border-success/40 bg-success/5'
        }`}
      >
        <div className="flex flex-wrap items-center gap-2">
          <Badge tone={VERDICT_TONES[conclusion.verdict]}>
            {VERDICT_LABELS[conclusion.verdict]}
          </Badge>
          <h2 className="text-copy-15 font-medium">{conclusion.title}</h2>
        </div>
        {conclusion.detail && (
          <p className="text-copy-13 mt-2 whitespace-pre-line">{conclusion.detail}</p>
        )}
        <dl className="text-copy-12 mt-3 grid gap-2 sm:grid-cols-4">
          <div className="rounded-md border p-2">
            <dt className="text-muted-foreground">红 · 必须处理</dt>
            <dd className="text-destructive font-medium">{conclusion.counts.red} 项</dd>
          </div>
          <div className="rounded-md border p-2">
            <dt className="text-muted-foreground">黄 · 预警</dt>
            <dd className="font-medium">{conclusion.counts.yellow} 项</dd>
          </div>
          <div className="rounded-md border p-2">
            <dt className="text-muted-foreground">判不了 · 无结论</dt>
            <dd className="font-medium">{conclusion.counts.undetermined} 项</dd>
          </div>
          <div className="rounded-md border p-2">
            <dt className="text-muted-foreground">绿 · 正常</dt>
            <dd className="text-success-foreground font-medium">{conclusion.counts.green} 项</dd>
          </div>
        </dl>
        {conclusion.counts.undetermined > 0 && (
          <p className="text-copy-12 text-muted-foreground mt-2">
            ⚠️ {conclusion.counts.undetermined} 项判不了 —— 判不了**不是**「没问题」，
            整体结论因此不能报「通过」。
          </p>
        )}
      </section>

      {/* ---------------- 结算与对账闸门 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">结算与对账闸门</h2>
        <p className="text-copy-12 text-muted-foreground">
          蓝图那条「一致性指标是账可信的底线，低于阈值应阻断结算与对账出结论」， 归属服务（07 库存
          7.6.2.1）已经把它做成一个<b>可查询、带证据</b>的判定。 本页只把判定原样暴露出来 ——{' '}
          <b>不代 12 财务结算与 7.1.2.2 对账执行阻断</b>
          （工作台只读 + 跳转）。
        </p>
        {accuracy ? (
          <div className="rounded-lg border p-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={accuracy.blocked ? 'danger' : 'success'}>
                {accuracy.blocked ? '已阻断 · 不可出结论' : '未触发阻断'}
              </Badge>
              <span className="text-copy-13">
                库存准确率 {formatPercent(accuracy.accuracyRate)} / 阈值{' '}
                {formatPercent(accuracy.accuracyThreshold)}
              </span>
            </div>
            {accuracy.blockReason && <p className="text-copy-13 mt-2">{accuracy.blockReason}</p>}
            <dl className="text-copy-12 mt-3 grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
              <div>
                <dt className="text-muted-foreground">判定口径</dt>
                <dd>{accuracy.basis}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">准确率状态</dt>
                <dd>{accuracy.accuracyState}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">最近一次账实核对</dt>
                <dd>{formatTimestamp(accuracy.lastReconciledAt)}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">最近一次盘点</dt>
                <dd>{formatTimestamp(accuracy.lastStocktakenAt)}</dd>
              </div>
            </dl>
            <div className="mt-3">
              <Button asChild variant="outline" size="sm">
                <Link href="/inventory/health">去 07 库存看库存健康</Link>
              </Button>
            </div>
          </div>
        ) : (
          <p className="text-copy-13 text-muted-foreground rounded-md border p-3">
            闸门判定
            {availability?.ACCURACY?.permitted === false ? '**没有去读**（无权限）' : '**读不到**'}
            —— 这一页**不能**给出「可否结算」的结论。
          </p>
        )}
      </section>

      {/* ---------------- 分项体检 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">分项健康</h2>
        <p className="text-copy-12 text-muted-foreground">
          五个分项对应蓝图点名的五类输入。每一项的结论都能追到一条规则与一处事实来源。
        </p>
        <div className="space-y-3">
          {checks.map((check) => (
            <article key={check.key} className="rounded-lg border p-4">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={VERDICT_TONES[check.verdict]}>{VERDICT_LABELS[check.verdict]}</Badge>
                  <span className="text-copy-13 font-medium">{check.label}</span>
                  <span className="text-copy-12 text-muted-foreground">
                    {check.owner} · {check.ownerModule}
                  </span>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="info">{AVAILABILITY_LABELS[check.availability]}</Badge>
                  {check.action ? (
                    <Button asChild variant="outline" size="sm">
                      <Link href={check.action.href}>{check.action.label}</Link>
                    </Button>
                  ) : (
                    <Badge tone="neutral">没有可跳转的页面</Badge>
                  )}
                </div>
              </div>
              <p className="text-copy-13 mt-2 whitespace-pre-line">{check.reason}</p>
              {check.missing && (
                <p className="text-copy-12 text-muted-foreground mt-1">差什么：{check.missing}</p>
              )}
              {check.evidence.length > 0 && (
                <ul className="text-copy-12 text-muted-foreground mt-2 space-y-1">
                  {check.evidence.map((line) => (
                    <li key={line}>· {line}</li>
                  ))}
                </ul>
              )}
            </article>
          ))}
        </div>
      </section>

      {/* ---------------- 异常清单 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">异常清单</h2>
        {anomalies.length === 0 ? (
          <p className="text-copy-13 text-muted-foreground">
            五个分项全部在阈值内。⚠️ 这不等于「读不到」—— 读不到时上面会直接写明原因。
          </p>
        ) : (
          <ul className="space-y-2">
            {anomalies.map((check) => (
              <li key={check.key} className="rounded-md border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={VERDICT_TONES[check.verdict]}>{VERDICT_LABELS[check.verdict]}</Badge>
                  <span className="text-copy-13 font-medium">{check.label}</span>
                </div>
                <p className="text-copy-12 text-muted-foreground mt-1">{check.reason}</p>
                <p className="text-copy-12 text-muted-foreground">
                  处置归属 {check.ownerModule}
                  {check.action ? ` · 入口：${check.action.label}` : ' · 暂无处理页面'}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ---------------- 差异明细 ---------------- */}
      {reconDetail && (
        <section className="space-y-2">
          <h2 className="text-copy-15 font-medium">核对差异明细</h2>
          <p className="text-copy-12 text-muted-foreground">
            最近一次账实核对（{formatTimestamp(reconDetail.run.windowFrom)} ~{' '}
            {formatTimestamp(reconDetail.run.windowTo)}）定位到的差异行与可疑流水定位。
          </p>
          {reconDetail.suspectWindows.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="text-copy-12 w-full">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="p-2">可疑区间</th>
                    <th className="p-2">可疑流水</th>
                    <th className="p-2">对不上的余额行</th>
                    <th className="p-2">净在手变动</th>
                    <th className="p-2">严重度</th>
                  </tr>
                </thead>
                <tbody>
                  {reconDetail.suspectWindows.map((window) => (
                    <tr key={`${window.windowStart}-${window.windowEnd}`} className="border-t">
                      <td className="p-2">
                        {formatTimestamp(window.windowStart)} ~ {formatTimestamp(window.windowEnd)}
                      </td>
                      <td className="p-2">{window.suspectCount} 条</td>
                      <td className="p-2">{window.discrepancyCount} 条</td>
                      <td className="p-2">{formatQuantity(window.onHandDelta)}</td>
                      <td className="p-2">{window.severity}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-copy-13 text-muted-foreground">这一批没有定位到可疑区间。</p>
          )}

          <div className="overflow-x-auto">
            <table className="text-copy-12 w-full">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="p-2">严重度</th>
                  <th className="p-2">类别</th>
                  <th className="p-2">原因</th>
                  <th className="p-2">SKU</th>
                  <th className="p-2">仓库 / 法人</th>
                  <th className="p-2">在手累计</th>
                  <th className="p-2">快照</th>
                  <th className="p-2">差额</th>
                </tr>
              </thead>
              <tbody>
                {(findings?.findings ?? []).map((finding) => (
                  <tr key={finding.id} className="border-t align-top">
                    <td className="p-2">
                      <Badge tone={FINDING_TONES[finding.severity] ?? 'neutral'}>
                        {FINDING_LABELS[finding.severity] ?? finding.severity}
                      </Badge>
                    </td>
                    <td className="p-2">
                      {finding.category
                        ? (CATEGORY_LABELS[finding.category] ?? finding.category)
                        : '—'}
                    </td>
                    <td className="p-2">{finding.reason}</td>
                    <td className="p-2">{finding.skuCode}</td>
                    <td className="p-2">
                      {finding.warehouseCode} / {finding.legalEntityCode}
                    </td>
                    <td className="p-2">{formatQuantity(finding.onHandSum)}</td>
                    <td className="p-2">{formatQuantity(finding.snapshotOnHand)}</td>
                    <td className="p-2">{formatQuantity(finding.onHandDiff)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {findingTotal === 0 ? (
            <p className="text-copy-13 text-muted-foreground">这一批没有差异明细。</p>
          ) : (
            <div className="text-copy-12 text-muted-foreground flex flex-wrap items-center gap-3">
              <span>
                共 {findingTotal} 条差异明细，第 {findingPage + 1} / {findingPageCount} 页 （本页{' '}
                {findings?.findings.length ?? 0} 条）。
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={findingPage <= 0}
                onClick={() => setFindingPage((current) => Math.max(0, current - 1))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={findingPage + 1 >= findingPageCount}
                onClick={() => setFindingPage((current) => current + 1)}
              >
                下一页
              </Button>
            </div>
          )}
        </section>
      )}

      {/* ---------------- 能力声明 ---------------- */}
      <section className="space-y-2">
        <h2 className="text-copy-15 font-medium">能力声明</h2>
        <p className="text-copy-12 text-muted-foreground">
          规则表逐条公布 —— 页面上每个结论都能追到一条「条件 → 结论 → 事实来源」。
        </p>
        <div className="overflow-x-auto">
          <table className="text-copy-12 w-full">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="p-2">规则</th>
                <th className="p-2">条件</th>
                <th className="p-2">结论</th>
                <th className="p-2">事实来源</th>
              </tr>
            </thead>
            <tbody>
              {CONSISTENCY_RULES.map((rule) => (
                <tr key={rule.code} className="border-t align-top">
                  <td className="p-2 font-mono">{rule.code}</td>
                  <td className="p-2">{rule.condition}</td>
                  <td className="p-2">{rule.reason}</td>
                  <td className="text-muted-foreground p-2 font-mono">{rule.source}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <h3 className="text-copy-13 font-medium">上游来源</h3>
        <div className="overflow-x-auto">
          <table className="text-copy-12 w-full">
            <thead className="text-muted-foreground text-left">
              <tr>
                <th className="p-2">分项</th>
                <th className="p-2">归属</th>
                <th className="p-2">只读端点</th>
                <th className="p-2">权限码</th>
                <th className="p-2">状态</th>
              </tr>
            </thead>
            <tbody>
              {CONSISTENCY_CHECK_KEYS.map((key) => {
                const spec = CONSISTENCY_SOURCE_SPECS[key];
                return (
                  <tr key={key} className="border-t align-top">
                    <td className="p-2">{spec.label}</td>
                    <td className="p-2">
                      {spec.owner} · {spec.ownerModule}
                    </td>
                    <td className="text-muted-foreground p-2 font-mono">
                      {spec.endpoint ?? '（无可读端点）'}
                    </td>
                    <td className="text-muted-foreground p-2 font-mono">
                      {spec.permission ?? '—'}
                    </td>
                    <td className="p-2">
                      {spec.state === 'READABLE'
                        ? '已交付'
                        : spec.state === 'NOT_DELIVERED'
                          ? '上游未交付'
                          : '本仓不可比'}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <ul className="space-y-1">
          {CONSISTENCY_SEMANTICS.map((item) => (
            <li key={item.label} className="text-copy-12 text-muted-foreground">
              <Badge tone="info">{item.label}</Badge>
              <span className="ml-2">{item.text}</span>
            </li>
          ))}
          <li className="text-copy-12 text-muted-foreground">
            <Badge tone="info">阈值</Badge>
            <span className="ml-2">
              映射缺口陈旧阈值 {MAPPING_GAP_STALE_DAYS} 天（本模块默认政策）；库存准确率阈值由 07
              库存持有，本模块原样引用。
            </span>
          </li>
        </ul>
      </section>
    </div>
  );
}

/** 供测试引用：本页用到的分项键顺序。 */
export const CONSISTENCY_PAGE_KEYS = CONSISTENCY_CHECK_KEYS;

/** 供测试引用：差异明细的严重度词表。 */
export const CONSISTENCY_FINDING_SEVERITIES: readonly string[] = ['HIGH', 'WARN', 'INFO'];

/** 供测试引用：复核差异行时用到的字段。 */
export function describeFinding(finding: ReconciliationFinding): string {
  return [
    finding.severity,
    finding.category ?? '',
    finding.skuCode,
    finding.warehouseCode,
    formatQuantity(finding.onHandDiff),
  ]
    .filter((part) => part !== '')
    .join(' · ');
}
