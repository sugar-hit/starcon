'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { Activity, AlertTriangle, RefreshCw, Sliders } from 'lucide-react';
import type {
  CrmLifecycleAlertRow,
  CrmLifecycleCapabilities,
  CrmLifecyclePage,
  CrmLifecyclePolicyPage,
  CrmLifecycleRunPage,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  acknowledgeLifecycleAlert,
  computeLifecycle,
  createLifecyclePolicy,
  fetchLifecycleAlerts,
  fetchLifecycleCapabilities,
  fetchLifecyclePolicies,
  fetchLifecycleRuns,
  fetchLifecycleSegments,
  lifecycleAlertEmptyStateText,
  lifecycleAlertStatusLabel,
  lifecycleBaselineText,
  lifecycleEmptyStateText,
  lifecycleMonetaryText,
  lifecyclePolicyNote,
  lifecyclePolicyScopeLabel,
  lifecycleRfmText,
  lifecycleRiskFactorText,
  lifecycleRiskLevelLabel,
  lifecycleRiskText,
  lifecycleSegmentLabel,
  lifecycleStatsNote,
  lifecycleSuggestionOwnerLabel,
  lifecycleThresholdSourceLabel,
  lifecycleThresholdText,
  resolveLifecycleAlert,
} from './customer-lifecycle-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 15.6.2.1 客户留存看板（生命周期与流失预警）。
 *
 * <p>页面按「先看清口径、再看数据、最后才动手」的顺序排。蓝图加粗句是
 * 「**复购周期因品类差异极大**（快消周级、家具月级），预警阈值需可按类目配置，不能一刀切」——
 * 所以界面上第一件要让人看到的就是**阈值从哪来**，而不是一张可以直接点「批量召回」的表。
 *
 * <ul>
 *   <li><b>能力边界</b> —— 逐维显示「有没有、依据是什么、缺了会怎样」。
 *       触达执行（15.5.2）与独立站弃单挽回（15.6.2.2）两块 available=false，
 *       页面把它们**显式灰掉**并写清归属模块。</li>
 *   <li><b>阈值口径常驻</b> —— 每一条分层与预警都写着「阈值从哪来」，
 *       并且在阈值来自订单历史基线时**显著标出**（那不是按类目生效）。</li>
 *   <li><b>风险分能拆开看</b> —— 四个因子各带权重、原始值、得分与说明，
 *       而不是只甩一个「56 分」。</li>
 *   <li><b>召回只是建议</b> —— 页面给「已知悉 / 已处理」两个动作，
 *       **没有**「立即发送」：触达归 15.5.2，本模块没那个能力。</li>
 *   <li><b>重算</b> —— 分层与预警都来自上游 trade 的真实订单事实（授权 HTTP API 只读）；
 *       上游读不到会明确失败，页面如实显示失败原因。</li>
 * </ul>
 */
export function CustomerLifecycleWorkspace() {
  const { session } = useIdentity();
  const canUpdate = session.permissions.includes('crm.lifecycle.update');

  const [capabilities, setCapabilities] = useState<CrmLifecycleCapabilities | null>(null);
  const [page, setPage] = useState<CrmLifecyclePage | null>(null);
  const [alerts, setAlerts] = useState<Awaited<ReturnType<typeof fetchLifecycleAlerts>> | null>(
    null,
  );
  const [policies, setPolicies] = useState<CrmLifecyclePolicyPage | null>(null);
  const [runs, setRuns] = useState<CrmLifecycleRunPage | null>(null);

  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [computing, setComputing] = useState(false);

  // 筛选
  const [segment, setSegment] = useState('');
  const [alertStatus, setAlertStatus] = useState('OPEN');
  const [riskLevel, setRiskLevel] = useState('');
  const [categoryCode, setCategoryCode] = useState('');
  const [keyword, setKeyword] = useState('');
  const [pageIndex, setPageIndex] = useState(0);
  const [pageSize, setPageSize] = useState(20);

  // 阈值策略新建
  const [policyScope, setPolicyScope] = useState('GLOBAL');
  const [policyCategory, setPolicyCategory] = useState('');
  const [policyExpectedDays, setPolicyExpectedDays] = useState('');
  const [policyToleranceDays, setPolicyToleranceDays] = useState('0');
  const [policyEffectiveFrom, setPolicyEffectiveFrom] = useState('');
  const [policyNote, setPolicyNote] = useState('');

  const filtered = useMemo(
    () =>
      segment !== '' ||
      alertStatus !== '' ||
      riskLevel !== '' ||
      categoryCode !== '' ||
      keyword !== '',
    [segment, alertStatus, riskLevel, categoryCode, keyword],
  );

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [segments, alertPage, policyPage, runPage] = await Promise.all([
        fetchLifecycleSegments({
          segment: segment || undefined,
          categoryCode: categoryCode || undefined,
          keyword: keyword || undefined,
          page: pageIndex,
          size: pageSize,
        }),
        fetchLifecycleAlerts({
          status: alertStatus || undefined,
          riskLevel: riskLevel || undefined,
          categoryCode: categoryCode || undefined,
          keyword: keyword || undefined,
          page: 0,
          size: pageSize,
        }),
        fetchLifecyclePolicies({ size: 50 }).catch(() => null),
        fetchLifecycleRuns().catch(() => null),
      ]);
      setPage(segments);
      setAlerts(alertPage);
      if (policyPage) setPolicies(policyPage);
      if (runPage) setRuns(runPage);
    } catch (e) {
      setError(e instanceof Error ? e.message : '留存看板读取失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [segment, alertStatus, riskLevel, categoryCode, keyword, pageIndex, pageSize]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void fetchLifecycleCapabilities()
      .then(setCapabilities)
      .catch(() => setCapabilities(null));
  }, []);

  async function runCompute() {
    setComputing(true);
    setNotice(null);
    setError(null);
    try {
      const result = await computeLifecycle();
      setNotice(
        `重算完成：客户 ${result.customerCount} 位 · 预警 ${result.alertCount} 条 · ` +
          `命中类目策略 ${result.categoryPolicyHitCount} 位` +
          (result.categoryUnresolvedCount > 0
            ? ` · 类目没解析出来 ${result.categoryUnresolvedCount} 位`
            : '') +
          (result.thresholdUnavailableCount > 0
            ? ` · 阈值不可判定 ${result.thresholdUnavailableCount} 位`
            : '') +
          (result.truncated ? ' · 🔴 本次扫描被截断，名单不是全量' : '') +
          (result.categoryLookupTruncated ? ' · 🔴 类目解析达到上限，部分客户类目未解析' : ''),
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '重算失败，请稍后重试');
    } finally {
      setComputing(false);
    }
  }

  async function markAlert(alertId: string, action: 'acknowledge' | 'resolve') {
    setError(null);
    try {
      if (action === 'acknowledge') {
        await acknowledgeLifecycleAlert(alertId, '运营已知悉');
      } else {
        await resolveLifecycleAlert(alertId, '运营已处理');
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '预警状态推进失败');
    }
  }

  async function savePolicy() {
    setError(null);
    try {
      await createLifecyclePolicy({
        scope: policyScope,
        categoryCode: policyScope === 'CATEGORY' ? policyCategory.trim() : undefined,
        expectedRepurchaseDays: Number(policyExpectedDays),
        toleranceDays: policyToleranceDays === '' ? 0 : Number(policyToleranceDays),
        effectiveFrom: policyEffectiveFrom
          ? new Date(policyEffectiveFrom).toISOString()
          : new Date().toISOString(),
        note: policyNote.trim() || undefined,
      });
      setNotice('阈值策略已新建。生效区间从现在开始 —— 之前的判定仍按当时的策略。');
      setPolicyExpectedDays('');
      setPolicyNote('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '阈值策略保存失败');
    }
  }

  const stats = page?.stats;
  const empty = lifecycleEmptyStateText(filtered, (runs?.total ?? 0) > 0);
  const alertEmpty = lifecycleAlertEmptyStateText(filtered, (runs?.total ?? 0) > 0);

  return (
    <div className="space-y-4">
      {error && (
        <div className="border-destructive/40 bg-destructive/10 text-destructive text-copy-13 rounded-md px-3 py-2">
          {error}
        </div>
      )}
      {notice && (
        <div className="border-primary/40 bg-primary/10 text-copy-13 rounded-md px-3 py-2">
          {notice}
        </div>
      )}

      {/* ---------------- 能力边界 ---------------- */}
      <SectionCard
        title="能力边界"
        description="这个看板回答什么、不回答什么 —— 先看清口径再动数据。"
      >
        {capabilities ? (
          <div className="space-y-3">
            <p className="text-muted-foreground text-copy-13">{capabilities.boundaryNote}</p>
            <ul className="space-y-2">
              {capabilities.dimensions.map((dimension) => (
                <li key={dimension.dimension} className="flex items-start gap-2">
                  <Badge tone={dimension.available ? 'success' : 'warning'}>
                    {dimension.available ? '可用' : '未交付'}
                  </Badge>
                  <div className="min-w-0">
                    <div className="text-copy-13 font-medium">{dimension.description}</div>
                    <div className="text-muted-foreground text-copy-12">
                      依据：{dimension.source}
                      {dimension.ownerModule ? ` · 归属模块：${dimension.ownerModule}` : ''}
                    </div>
                    {dimension.impact && (
                      <div className="text-muted-foreground text-copy-12">{dimension.impact}</div>
                    )}
                  </div>
                </li>
              ))}
            </ul>
            <div className="bg-muted/60 rounded-md px-3 py-2">
              <div className="text-copy-13 flex items-center gap-2 font-medium">
                <Sliders className="h-4 w-4" />
                阈值口径
              </div>
              <p className="text-muted-foreground text-copy-12 mt-1">
                {capabilities.thresholdPolicy}
              </p>
            </div>
            <p className="text-muted-foreground text-copy-12">{capabilities.highlight}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 留存概览 ---------------- */}
      <SectionCard
        title="留存概览"
        description="🔴 这些数字来自独立查询，不受分页影响：翻到第 3 页看到的分布与第 1 页完全一样。"
      >
        {stats ? (
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-3">
              <StatCard
                label="客户数"
                value={stats.totalCustomers}
                icon={<Activity className="h-4 w-4" />}
              />
              <StatCard
                label="超期客户"
                value={stats.overdueCustomers}
                polarity="negative"
                icon={<AlertTriangle className="h-4 w-4" />}
              />
              <StatCard label="预警条数" value={stats.totalAlerts} />
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">生命周期分层</div>
                {stats.bySegment.length === 0 ? (
                  <div className="text-copy-13">无</div>
                ) : (
                  stats.bySegment.map((bucket) => (
                    <div key={bucket.segment} className="text-copy-13">
                      {bucket.segmentLabel}：{bucket.customerCount} 位
                    </div>
                  ))
                )}
              </div>
              <div className="border-control-border rounded-md px-3 py-2">
                <div className="text-copy-12 text-muted-foreground">类目（catalog 只读）</div>
                {stats.byCategory.length === 0 ? (
                  <div className="text-copy-13">无</div>
                ) : (
                  stats.byCategory.map((bucket) => (
                    <div key={bucket.categoryCode ?? '__none__'} className="text-copy-13">
                      {bucket.categoryCode ?? '类目未解析'}：{bucket.customerCount} 位 / 预警{' '}
                      {bucket.alertCount} 条
                    </div>
                  ))
                )}
              </div>
            </div>
            <p className="text-muted-foreground text-copy-12">{lifecycleStatsNote(stats)}</p>
          </div>
        ) : (
          <p className="text-muted-foreground text-copy-13">统计读取中…</p>
        )}
      </SectionCard>

      {/* ---------------- 筛选 ---------------- */}
      <SectionCard
        title="筛选"
        description="按生命周期分层、预警状态、风险等级、类目筛选。分层的关键词与预警的关键词是同一个框。"
      >
        <div className="grid gap-3 md:grid-cols-5">
          <Field label="生命周期分层">
            <select
              className={control}
              value={segment}
              onChange={(e) => {
                setSegment(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.segments ?? []).map((code) => (
                <option key={code} value={code}>
                  {lifecycleSegmentLabel(code)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="预警状态">
            <select
              className={control}
              value={alertStatus}
              onChange={(e) => setAlertStatus(e.target.value)}
            >
              <option value="">全部</option>
              {(capabilities?.alertStatuses ?? []).map((code) => (
                <option key={code} value={code}>
                  {lifecycleAlertStatusLabel(code)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="风险等级">
            <select
              className={control}
              value={riskLevel}
              onChange={(e) => {
                setRiskLevel(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部</option>
              {(capabilities?.riskLevels ?? []).map((code) => (
                <option key={code} value={code}>
                  {lifecycleRiskLevelLabel(code)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="类目编码">
            <input
              className={control}
              value={categoryCode}
              onChange={(e) => {
                setCategoryCode(e.target.value);
                setPageIndex(0);
              }}
              placeholder="catalog 的类目编码"
            />
          </Field>
          <Field label="关键词">
            <input
              className={control}
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageIndex(0);
              }}
              placeholder="客户标识 / 买家名"
            />
          </Field>
          <div className="flex items-end gap-2">
            <Button variant="outline" onClick={() => void load()} disabled={loading}>
              重新加载
            </Button>
            <Button
              onClick={() => {
                setSegment('');
                setAlertStatus('OPEN');
                setRiskLevel('');
                setCategoryCode('');
                setKeyword('');
                setPageIndex(0);
              }}
            >
              清空筛选
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 流失预警清单 ---------------- */}
      <SectionCard
        title="流失预警清单（本应复购未复购）"
        description="🔴 召回只是**建议**：触达归 15.5.2、独立站弃单挽回归 15.6.2.2 —— 本模块不发送任何消息。"
      >
        {loading && !alerts ? (
          <p className="text-muted-foreground text-copy-13">预警清单读取中…</p>
        ) : alerts && alerts.rows.length === 0 ? (
          <EmptyState title={alertEmpty.title} description={alertEmpty.description} />
        ) : (
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">客户</th>
                    <th className="px-2 py-1 text-left">来源店铺</th>
                    <th className="px-2 py-1 text-right">距上次购买</th>
                    <th className="px-2 py-1 text-left">阈值（从哪来）</th>
                    <th className="px-2 py-1 text-right">逾期</th>
                    <th className="px-2 py-1 text-left">风险分（因子）</th>
                    <th className="px-2 py-1 text-left">召回建议 / 归属</th>
                    <th className="px-2 py-1 text-left">状态</th>
                    <th className="px-2 py-1 text-left">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {alerts?.rows.map((alert) => (
                    <AlertRow
                      key={alert.id}
                      alert={alert}
                      canUpdate={canUpdate}
                      onMark={markAlert}
                    />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="text-muted-foreground text-copy-12">
              共 {alerts?.total ?? 0} 条 · 第 {(alerts?.page ?? 0) + 1} /{' '}
              {Math.max(alerts?.totalPages ?? 1, 1)} 页
            </div>
            <p className="text-muted-foreground text-copy-12">{alerts?.note}</p>
          </div>
        )}
      </SectionCard>

      {/* ---------------- 生命周期分层结果 ---------------- */}
      <SectionCard
        title="生命周期分层结果"
        description="一行 = 一个来源店铺下的一个客户（与 15.3.1.1 同一口径：同一个人在两个来源下是两行）。"
      >
        {loading && !page ? (
          <p className="text-muted-foreground text-copy-13">分层结果读取中…</p>
        ) : page && page.rows.length === 0 ? (
          <EmptyState title={empty.title} description={empty.description} />
        ) : (
          <div className="space-y-3">
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">客户</th>
                    <th className="px-2 py-1 text-left">来源店铺</th>
                    <th className="px-2 py-1 text-left">分层（凭什么）</th>
                    <th className="px-2 py-1 text-left">RFM</th>
                    <th className="px-2 py-1 text-left">消费</th>
                    <th className="px-2 py-1 text-left">类目</th>
                    <th className="px-2 py-1 text-left">阈值（从哪来）</th>
                    <th className="px-2 py-1 text-left">本人复购基线</th>
                  </tr>
                </thead>
                <tbody>
                  {page?.rows.map((row) => (
                    <tr key={row.id} className="border-control-border border-t align-top">
                      <td className="px-2 py-1">
                        <div>{row.customerName || '—'}</div>
                        <div className="text-muted-foreground text-copy-12">{row.customerRef}</div>
                      </td>
                      <td className="px-2 py-1">{row.sourceShop}</td>
                      <td className="px-2 py-1">
                        <Badge
                          tone={
                            row.segment === 'CHURNED'
                              ? 'danger'
                              : row.segment === 'RECALL'
                                ? 'warning'
                                : row.segment === 'ACTIVE'
                                  ? 'success'
                                  : 'neutral'
                          }
                        >
                          {row.segmentLabel || lifecycleSegmentLabel(row.segment)}
                        </Badge>
                        <div className="text-muted-foreground text-copy-12 mt-1 max-w-md">
                          {row.segmentReason}
                        </div>
                      </td>
                      <td className="px-2 py-1">{lifecycleRfmText(row)}</td>
                      <td className="px-2 py-1">
                        {lifecycleMonetaryText(row.monetary, row.currency)}
                      </td>
                      <td className="px-2 py-1">
                        {row.categoryCode ?? (
                          <span className="text-muted-foreground">类目未解析</span>
                        )}
                      </td>
                      <td className="max-w-xs px-2 py-1">
                        <div>{lifecycleThresholdText(row)}</div>
                        {row.thresholdSource === 'ORDER_HISTORY_BASELINE' && (
                          <Badge tone="warning" className="mt-1">
                            不是按类目生效
                          </Badge>
                        )}
                        {row.thresholdSource === 'NONE' && (
                          <Badge tone="warning" className="mt-1">
                            不可判定
                          </Badge>
                        )}
                      </td>
                      <td className="max-w-xs px-2 py-1">{lifecycleBaselineText(row)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center justify-between gap-2">
              <div className="text-muted-foreground text-copy-12">
                共 {page?.total ?? 0} 位客户 · 第 {(page?.page ?? 0) + 1} /{' '}
                {Math.max(page?.totalPages ?? 1, 1)} 页
                {page?.dataScope ? ` · 数据范围 ${page.dataScope}` : ''}
              </div>
              <div className="flex items-center gap-2">
                <select
                  className={control}
                  value={String(pageSize)}
                  onChange={(e) => {
                    setPageSize(Number(e.target.value));
                    setPageIndex(0);
                  }}
                >
                  {[10, 20, 50, 100].map((size) => (
                    <option key={size} value={size}>
                      每页 {size}
                    </option>
                  ))}
                </select>
                <Button
                  variant="outline"
                  disabled={(page?.page ?? 0) <= 0}
                  onClick={() => setPageIndex(Math.max((page?.page ?? 0) - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  disabled={(page?.page ?? 0) + 1 >= (page?.totalPages ?? 1)}
                  onClick={() => setPageIndex((page?.page ?? 0) + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
            {page?.dataScopeNote && (
              <p className="text-muted-foreground text-copy-12">{page.dataScopeNote}</p>
            )}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 阈值策略 ---------------- */}
      <SectionCard
        title="复购周期阈值（按类目可配）"
        description="🔴 阈值是**租户数据**、带生效区间。改阈值不改历史：要改请新建一条（新的生效起点）。"
      >
        <div className="space-y-3">
          {policies && policies.rows.length > 0 ? (
            <div className="overflow-x-auto">
              <table className="text-copy-13 w-full">
                <thead className="text-muted-foreground text-copy-12">
                  <tr>
                    <th className="px-2 py-1 text-left">作用域</th>
                    <th className="px-2 py-1 text-left">类目</th>
                    <th className="px-2 py-1 text-left">阈值</th>
                    <th className="px-2 py-1 text-left">生效区间</th>
                    <th className="px-2 py-1 text-left">状态</th>
                    <th className="px-2 py-1 text-left">备注</th>
                  </tr>
                </thead>
                <tbody>
                  {policies.rows.map((policy) => (
                    <tr key={policy.id} className="border-control-border border-t">
                      <td className="px-2 py-1">{lifecyclePolicyScopeLabel(policy.scope)}</td>
                      <td className="px-2 py-1">{policy.categoryCode || '—'}</td>
                      <td className="px-2 py-1">{lifecyclePolicyNote(policy)}</td>
                      <td className="px-2 py-1">
                        {policy.effectiveFrom.slice(0, 10)} ~{' '}
                        {policy.effectiveTo ? policy.effectiveTo.slice(0, 10) : '至今'}
                      </td>
                      <td className="px-2 py-1">
                        <Badge tone={policy.effectiveNow ? 'success' : 'neutral'}>
                          {policy.effectiveNow ? '生效中' : '未生效'}
                        </Badge>
                      </td>
                      <td className="px-2 py-1">{policy.note || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          ) : (
            <p className="text-muted-foreground text-copy-13">
              还没有阈值策略。此时判定会用**租户真实订单历史算出来的中位复购间隔**，
              并在每一行标出「不是按类目生效」。
            </p>
          )}
          <p className="text-muted-foreground text-copy-12">{policies?.note}</p>

          <div className="grid gap-3 md:grid-cols-6">
            <Field label="作用域">
              <select
                className={control}
                value={policyScope}
                onChange={(e) => setPolicyScope(e.target.value)}
              >
                <option value="GLOBAL">全局兜底</option>
                <option value="CATEGORY">按类目</option>
              </select>
            </Field>
            <Field label="类目编码">
              <input
                className={control}
                value={policyCategory}
                onChange={(e) => setPolicyCategory(e.target.value)}
                disabled={policyScope !== 'CATEGORY'}
                placeholder="catalog 的类目编码"
              />
            </Field>
            <Field label="预期复购间隔（天）">
              <input
                className={control}
                value={policyExpectedDays}
                onChange={(e) => setPolicyExpectedDays(e.target.value)}
                inputMode="numeric"
                placeholder="例：14"
              />
            </Field>
            <Field label="容差（天）">
              <input
                className={control}
                value={policyToleranceDays}
                onChange={(e) => setPolicyToleranceDays(e.target.value)}
                inputMode="numeric"
              />
            </Field>
            <Field label="生效起点" hint="留空 = 从现在开始">
              <input
                className={control}
                type="datetime-local"
                value={policyEffectiveFrom}
                onChange={(e) => setPolicyEffectiveFrom(e.target.value)}
              />
            </Field>
            <Field label="备注">
              <input
                className={control}
                value={policyNote}
                onChange={(e) => setPolicyNote(e.target.value)}
                placeholder="例：快消按周补货"
              />
            </Field>
          </div>
          <div className="flex items-center gap-2">
            <Button
              onClick={() => void savePolicy()}
              disabled={!canUpdate || policyExpectedDays.trim() === ''}
            >
              新建阈值策略
            </Button>
            {!canUpdate && (
              <span className="text-muted-foreground text-copy-12">
                当前角色没有 crm.lifecycle.update，不能改阈值。
              </span>
            )}
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 重算 ---------------- */}
      <SectionCard
        title="重算分层与预警"
        description="分层与预警都来自上游 trade 的真实订单事实（授权 HTTP API 只读）。上游读不到会明确失败，不会伪装成「没有客户」。"
      >
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <Button onClick={() => void runCompute()} disabled={!canUpdate || computing}>
              <RefreshCw className="h-4 w-4" />
              {computing ? '重算中…' : '重算分层与预警'}
            </Button>
            {!canUpdate && (
              <span className="text-muted-foreground text-copy-12">
                当前角色没有 crm.lifecycle.update，不能重算。
              </span>
            )}
          </div>
          {runs && runs.rows.length > 0 && (
            <div className="space-y-1">
              {runs.rows.map((run) => (
                <div key={run.id} className="text-copy-12 text-muted-foreground">
                  {run.runNo} · {run.status} · 客户 {run.customerCount} 位 · 预警 {run.alertCount}{' '}
                  条 · 命中类目策略 {run.categoryPolicyHitCount} 位
                  {run.categoryUnresolvedCount > 0
                    ? ` · 类目未解析 ${run.categoryUnresolvedCount} 位`
                    : ''}
                  {run.thresholdUnavailableCount > 0
                    ? ` · 阈值不可判定 ${run.thresholdUnavailableCount} 位`
                    : ''}
                  {run.truncated ? ' · 🔴 扫描被截断' : ''}
                  {run.failureReason ? ` · ${run.failureReason}` : ''}
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

/** 一行预警（含风险因子明细与召回建议）。 */
function AlertRow({
  alert,
  canUpdate,
  onMark,
}: {
  alert: CrmLifecycleAlertRow;
  canUpdate: boolean;
  onMark: (alertId: string, action: 'acknowledge' | 'resolve') => Promise<void>;
}) {
  return (
    <tr className="border-control-border border-t align-top">
      <td className="px-2 py-1">
        <div>{alert.customerName || '—'}</div>
        <div className="text-muted-foreground text-copy-12">{alert.customerRef}</div>
      </td>
      <td className="px-2 py-1">{alert.sourceShop}</td>
      <td className="px-2 py-1 text-right">{alert.daysSinceLastOrder} 天</td>
      <td className="max-w-xs px-2 py-1">
        <div>
          预期复购 {alert.expectedRepurchaseDays} 天 + 容差 {alert.toleranceDays} 天
        </div>
        <div className="text-muted-foreground text-copy-12">
          {lifecycleThresholdSourceLabel(alert.thresholdSource)}
        </div>
        {alert.thresholdSource === 'ORDER_HISTORY_BASELINE' && (
          <Badge tone="warning" className="mt-1">
            不是按类目生效
          </Badge>
        )}
      </td>
      <td className="px-2 py-1 text-right">{alert.overdueDays} 天</td>
      <td className="max-w-md px-2 py-1">
        <div className="font-medium">{lifecycleRiskText(alert)}</div>
        <ul className="text-muted-foreground text-copy-12 mt-1 space-y-0.5">
          {alert.riskFactors.map((factor) => (
            <li key={factor.code}>{lifecycleRiskFactorText(factor)}</li>
          ))}
        </ul>
      </td>
      <td className="max-w-md px-2 py-1">
        <div>{alert.recallSuggestion}</div>
        <div className="text-muted-foreground text-copy-12 mt-1">
          归属：{alert.suggestionOwnerLabel || lifecycleSuggestionOwnerLabel(alert.suggestionOwner)}
        </div>
      </td>
      <td className="px-2 py-1">
        <Badge
          tone={
            alert.status === 'OPEN'
              ? 'warning'
              : alert.status === 'ACKNOWLEDGED'
                ? 'info'
                : 'success'
          }
        >
          {alert.statusLabel || lifecycleAlertStatusLabel(alert.status)}
        </Badge>
        {alert.statusNote && (
          <div className="text-muted-foreground text-copy-12 mt-1">{alert.statusNote}</div>
        )}
      </td>
      <td className="px-2 py-1">
        <div className="flex flex-col gap-1">
          <Button
            variant="outline"
            disabled={!canUpdate || alert.status !== 'OPEN'}
            onClick={() => void onMark(alert.id, 'acknowledge')}
          >
            已知悉
          </Button>
          <Button
            variant="outline"
            disabled={!canUpdate || alert.status === 'RESOLVED'}
            onClick={() => void onMark(alert.id, 'resolve')}
          >
            已处理
          </Button>
        </div>
      </td>
    </tr>
  );
}
