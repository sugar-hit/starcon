'use client';

import { useCallback, useEffect, useState } from 'react';
import { RefreshCw, ShieldAlert, ShieldCheck, Ban, Scale } from 'lucide-react';
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
  InterceptCapabilities,
  InterceptCheck,
  InterceptCheckInput,
  InterceptDispositionInput,
  InterceptPolicyInput,
  InterceptRestrictionInput,
  InterceptSkuCostInput,
  InterceptDetail,
  InterceptPolicy,
  InterceptRestriction,
  InterceptSkuCost,
  InterceptSummary,
} from '../../../../../packages/api-contracts/src/index';
import {
  CHECK_CODES,
  FINAL_DISPOSITIONS,
  MISSING_REFERENCE_POLICIES,
  RECOMMENDED_DISPOSITIONS,
  REASON_CODES,
  RESTRICTION_EFFECTS,
  RESTRICTION_KINDS,
  RESTRICTION_SOURCES,
  checkCodeText,
  createCheck,
  errorText,
  evidenceText,
  fetchCapabilities,
  fetchCheck,
  fetchChecks,
  fetchOverview,
  fetchPolicy,
  fetchRestrictions,
  fetchSkuCosts,
  finalText,
  isPending,
  isoText,
  missingPolicyText,
  money,
  observedText,
  pageText,
  rate,
  recommendedText,
  reasonCodeText,
  restrictionEffectText,
  restrictionKindText,
  restrictionPageText,
  restrictionSourceText,
  savePolicy,
  saveRestriction,
  saveSkuCost,
  severityText,
  skuCostPageText,
  statusText,
  submitDisposition,
} from './order-intercept-model';

const PAGE_SIZE = 20;

/**
 * 9.2.1.3 异常拦截工作区。
 *
 * 蓝图：「识别并拦截异常订单：地址不可达/信息不全、黑名单买家、重复下单、异常折扣（低于成本）、
 * 疑似欺诈、超出收货国家限制、平台合规风险。输出：拦截清单 + 拦截原因 + 处置建议（取消/转人工/放行）。」
 *
 * 蓝图加粗句：「<b>拦截不自动取消</b>，默认转人工——误拦真实订单的代价通常高于人工看一眼。」
 *
 * <p>本工作区是**真实数据工作区**而不是占位页：拦截策略、限制清单、成本基线、
 * 拦截清单与八项明细、人工处置全部落在 PostgreSQL 16 上。
 * 加载态 / 空态 / 错误态三态分开渲染。
 *
 * <p>布局顺序刻意按客服的判读顺序：
 * <b>能力与缺口 → 策略 → 限制清单与成本基线 → 触发检查 → 拦截清单 → 详情与人工处置</b>。
 */
export function OrderInterceptWorkspace() {
  const [caps, setCaps] = useState<InterceptCapabilities | null>(null);
  const [summary, setSummary] = useState<InterceptSummary | null>(null);
  const [policy, setPolicy] = useState<InterceptPolicy | null>(null);
  const [checks, setChecks] = useState<InterceptCheck[]>([]);
  const [checkPage, setCheckPage] = useState({ total: 0, page: 0, totalPages: 0 });
  const [restrictions, setRestrictions] = useState<InterceptRestriction[]>([]);
  const [restrictionPage, setRestrictionPage] = useState({ total: 0, page: 0, totalPages: 0 });
  const [costs, setCosts] = useState<InterceptSkuCost[]>([]);
  const [costPage, setCostPage] = useState({ total: 0, page: 0, totalPages: 0 });
  const [detail, setDetail] = useState<InterceptDetail | null>(null);

  const [keyword, setKeyword] = useState('');
  const [filterDisposition, setFilterDisposition] = useState('');
  const [filterCode, setFilterCode] = useState('');
  const [onlyPending, setOnlyPending] = useState(false);
  const [page, setPage] = useState(0);

  const [orderId, setOrderId] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');

  // 策略表单
  const [policyName, setPolicyName] = useState('默认拦截策略');
  const [duplicateWindowHours, setDuplicateWindowHours] = useState('24');
  const [duplicateMaxOrders, setDuplicateMaxOrders] = useState('3');
  const [belowCostTolerance, setBelowCostTolerance] = useState('0.0000');
  const [fraudThreshold, setFraudThreshold] = useState('60.0000');
  const [missingPolicy, setMissingPolicy] = useState('ASSESS_KNOWN_ONLY');

  // 限制清单表单
  const [rKind, setRKind] = useState('SHIPPING_COUNTRY');
  const [rTarget, setRTarget] = useState('US');
  const [rEffect, setREffect] = useState('BLOCK');
  const [rSource, setRSource] = useState('TENANT_MAINTAINED');
  const [rReason, setRReason] = useState('');

  // 成本基线表单
  const [cSkuId, setCSkuId] = useState('');
  const [cUnitCost, setCUnitCost] = useState('0.0000');
  const [cCurrency, setCCurrency] = useState('USD');

  // 人工处置表单
  const [dDecision, setDDecision] = useState('RELEASE');
  const [dReasonCode, setDReasonCode] = useState('FALSE_POSITIVE');
  const [dNote, setDNote] = useState('');

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [capabilities, overview, policyResult, checkResult, restrictionResult, costResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchOverview(),
          // 策略没登记就是 404，那是「未配置」而不是错误：用 catch 降级成 null，
          // 这样页面能显示「尚未登记拦截策略」并给出登记表单。
          fetchPolicy().catch(() => null),
          fetchChecks({
            keyword: keyword || undefined,
            recommendedDisposition: filterDisposition || undefined,
            checkCode: filterCode || undefined,
            pendingDispositionOnly: onlyPending || undefined,
            page,
            size: PAGE_SIZE,
          }),
          fetchRestrictions({ page: 0, size: PAGE_SIZE }),
          fetchSkuCosts({ page: 0, size: PAGE_SIZE }),
        ]);
      setCaps(capabilities);
      setSummary(overview);
      setPolicy(policyResult);
      if (policyResult) {
        setPolicyName(policyResult.policyName);
        setDuplicateWindowHours(String(policyResult.duplicateWindowHours));
        setDuplicateMaxOrders(String(policyResult.duplicateMaxOrders));
        setBelowCostTolerance(policyResult.belowCostToleranceRate.toFixed(4));
        setFraudThreshold(policyResult.fraudScoreThreshold.toFixed(4));
        setMissingPolicy(policyResult.missingReferencePolicy);
      }
      setChecks(checkResult.items);
      setCheckPage({ total: checkResult.total, page: checkResult.page, totalPages: checkResult.totalPages });
      setRestrictions(restrictionResult.items);
      setRestrictionPage({
        total: restrictionResult.total,
        page: restrictionResult.page,
        totalPages: restrictionResult.totalPages,
      });
      setCosts(costResult.items);
      setCostPage({ total: costResult.total, page: costResult.page, totalPages: costResult.totalPages });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, filterDisposition, filterCode, onlyPending, page]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  async function openDetail(checkId: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchCheck(checkId));
      setDDecision('RELEASE');
      setDReasonCode('FALSE_POSITIVE');
      setDNote('');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function runCheck() {
    if (!orderId.trim()) {
      setError('必须填写要检查的订单 ID');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await createCheck({ orderId: orderId.trim() } as InterceptCheckInput);
      setNotice(saved.summary);
      await loadAll();
      await openDetail(saved.check.id);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitDispositionFor(checkId: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await submitDisposition(checkId, {
        decision: dDecision as InterceptDispositionInput['decision'],
        reasonCode: dReasonCode as InterceptDispositionInput['reasonCode'],
        note: dNote,
      });
      setNotice(saved.summary);
      await loadAll();
      await openDetail(checkId);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitPolicy() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await savePolicy({
        policyName: policyName.trim(),
        duplicateWindowHours: Number(duplicateWindowHours),
        duplicateMaxOrders: Number(duplicateMaxOrders),
        belowCostToleranceRate: Number(belowCostTolerance),
        fraudScoreThreshold: Number(fraudThreshold),
        missingReferencePolicy: missingPolicy as InterceptPolicyInput['missingReferencePolicy'],
      });
      setNotice(
        `拦截策略「${saved.policyName}」已保存为第 ${saved.version} 版：` +
          `同一张单的历史检查记录引用 (策略, 版本)，所以阈值改动可复算。`,
      );
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitRestriction() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveRestriction({
        restrictionKind: rKind as InterceptRestrictionInput['restrictionKind'],
        restrictionTarget: rTarget.trim().toUpperCase(),
        restrictionEffect: rEffect as InterceptRestrictionInput['restrictionEffect'],
        restrictionSource: rSource as InterceptRestrictionInput['restrictionSource'],
        sourceReference: '',
        reason: rReason,
        // ⚠️ codegen 把 @Schema(nullable) 的字段生成成**必填的 nullable**，
        // 所以「不限生效期」必须显式写 null，不能靠不传这个 key。
        effectiveFrom: null,
        effectiveTo: null,
      });
      setNotice(
        `已登记${restrictionKindText(saved.restrictionKind)} ${saved.restrictionTarget} → ` +
          `${restrictionEffectText(saved.restrictionEffect)}。` +
          `注意这只是**建议**档位：即使 BLOCK 档也只产生「建议取消」，不会自动取消订单。`,
      );
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitSkuCost() {
    if (!cSkuId.trim()) {
      setError('必须填写 SKU ID');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveSkuCost({
        skuId: cSkuId.trim(),
        skuCode: '',
        skuName: '',
        unitCost: Number(cUnitCost),
        currency: cCurrency.trim().toUpperCase(),
        capturedAt: new Date().toISOString(),
        note: '界面登记',
      } as InterceptSkuCostInput);
      setNotice(
        `已登记 ${saved.skuId} 的单位成本 ${money(saved.unitCost)} ${saved.currency}，` +
          `采集时刻 ${isoText(saved.capturedAt)}。` +
          `低于成本判定只使用**该时刻之前**采集到的基线：订单提交之后才采到的成本对它没有贡献。`,
      );
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-6">
      <PageHeader
        title="异常拦截"
        description="识别并拦截异常订单，输出拦截清单、拦截原因与处置建议。"
        actions={
          <Button onClick={() => void loadAll()} disabled={loading || busy}>
            <RefreshCw className="mr-2 h-4 w-4" /> 刷新
          </Button>
        }
      />

      {error ? <Notice tone="danger" title="操作失败">{error}</Notice> : null}
      {notice ? <Notice tone="success" title="已完成">{notice}</Notice> : null}

      {/* ------------------------------------------------ 能力与缺口 */}
      <SectionCard title="能力与上下游缺口" description="哪几项真接通、哪几项靠租户维护、哪几项现在判不了。">
        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : !caps ? (
          <EmptyState title="能力声明加载失败" description="请确认 trade 服务可用后刷新。" />
        ) : (
          <div className="space-y-4">
            <Notice tone="info" title="蓝图加粗句与本模块的落点">
              {caps.boldRule}
            </Notice>
            <div className="flex flex-wrap gap-2">
              <span className="rounded border px-2 py-1 text-xs">
                跨服务读表：{caps.crossServiceReadAvailable ? '可用' : '不可用（模块边界纪律）'}
              </span>
              <span className="rounded border px-2 py-1 text-xs">
                地址校验（9.2.2.1）：{caps.addressValidationAvailable ? '可用' : '未交付'}
              </span>
              <span className="rounded border px-2 py-1 text-xs">
                店铺维度：{caps.shopScopeSupported ? '支持' : '不支持（sales_order 无 shop_code 列）'}
              </span>
              <span className="rounded border px-2 py-1 text-xs">
                拦截策略：{caps.policyConfigured ? '已登记' : '尚未登记'}
              </span>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">命中项</th>
                    <th className="py-2 pr-3">输入来源</th>
                    <th className="py-2 pr-3">上游</th>
                    <th className="py-2">缺口说明</th>
                  </tr>
                </thead>
                <tbody>
                  {caps.checks.map((c) => (
                    <tr key={c.code} className="border-b align-top">
                      <td className="py-2 pr-3 font-medium">{c.label}</td>
                      <td className="py-2 pr-3 text-muted-foreground">{c.source}</td>
                      <td className="py-2 pr-3">
                        {c.upstreamDelivered ? (
                          <span className="text-xs text-emerald-600">真接通</span>
                        ) : (
                          <span className="text-xs text-amber-600">未接通</span>
                        )}
                      </td>
                      <td className="py-2 text-muted-foreground">{c.gap ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Notice tone="info" title="判定顺序">{caps.determinationOrder}</Notice>
            <Notice tone="info" title="上游与下游缺口">{caps.gaps}</Notice>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 汇总 */}
      {summary ? (
        <div className="grid gap-3 md:grid-cols-4 lg:grid-cols-8">
          <StatCard label="检查总数" value={summary.total} />
          <StatCard label="待人工处置" value={summary.pendingDisposition} icon={<ShieldAlert />} />
          <StatCard label="建议放行" value={summary.releaseCount} />
          <StatCard label="建议转人工" value={summary.manualReviewCount} />
          <StatCard label="建议取消" value={summary.cancelSuggestedCount} icon={<Ban />} />
          <StatCard label="已人工取消" value={summary.cancelledCount} />
          <StatCard label="已人工挂起" value={summary.heldCount} />
          <StatCard label="有缺参照项" value={summary.incompleteCheckCount} />
        </div>
      ) : null}

      {/* ------------------------------------------------ 拦截策略 */}
      <SectionCard
        title="拦截策略"
        description="每租户一条。阈值是运营口径，改这里而不是改代码。"
      >
        <div className="grid gap-3 md:grid-cols-3">
          <FormField id="keyword" label="策略名称">
            <Input value={policyName} onChange={(e) => setPolicyName(e.target.value)} />
          </FormField>
          <FormField id="filter-disposition" label="重复下单时间窗（小时）">
            <Input
              value={duplicateWindowHours}
              onChange={(e) => setDuplicateWindowHours(e.target.value)}
            />
          </FormField>
          <FormField id="filter-code" label="重复下单笔数阈值">
            <Input
              value={duplicateMaxOrders}
              onChange={(e) => setDuplicateMaxOrders(e.target.value)}
            />
          </FormField>
          <FormField id="policy-name" label="低于成本容忍度（0.0500 = 允许亏 5%）">
            <Input
              value={belowCostTolerance}
              onChange={(e) => setBelowCostTolerance(e.target.value)}
            />
          </FormField>
          <FormField id="dup-window" label="疑似欺诈风控分阈值（0..100，与 9.2.2.2 同刻度）">
            <Input value={fraudThreshold} onChange={(e) => setFraudThreshold(e.target.value)} />
          </FormField>
          <FormField id="dup-max" label="缺参照口径">
            <select
              className="h-9 w-full rounded border px-2 text-sm"
              value={missingPolicy}
              onChange={(e) => setMissingPolicy(e.target.value)}
            >
              {MISSING_REFERENCE_POLICIES.map((p) => (
                <option key={p.value} value={p.value}>
                  {p.label}
                </option>
              ))}
            </select>
          </FormField>
        </div>
        <div className="mt-3 flex items-center gap-3">
          <Button onClick={() => void submitPolicy()} disabled={busy}>
            保存策略
          </Button>
          {policy ? (
            <span className="text-xs text-muted-foreground">
              当前第 {policy.version} 版 · 缺参照口径：{missingPolicyText(policy.missingReferencePolicy)}{' '}
              · 容忍度 {rate(policy.belowCostToleranceRate)}
            </span>
          ) : (
            <span className="text-xs text-amber-600">
              尚未登记拦截策略 —— 本模块不提供隐含默认值（「没配 = 默认全放行」与加粗句相反）。
            </span>
          )}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 限制清单 + 成本基线 */}
      <div className="grid gap-4 lg:grid-cols-2">
        <SectionCard
          title="限制清单"
          description="禁运收货国与平台来源合规风险。9.2.2.1 与 16 合规未交付期间，这是租户自己维护的真实数据。"
        >
          <div className="grid gap-3 md:grid-cols-2">
            <FormField id="cost-tolerance" label="限制类型">
              <select
                className="h-9 w-full rounded border px-2 text-sm"
                value={rKind}
                onChange={(e) => setRKind(e.target.value)}
              >
                {RESTRICTION_KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="r-target" label={rKind === 'SHIPPING_COUNTRY' ? '收货国码（ISO alpha-2）' : '订单来源'}>
              <Input value={rTarget} onChange={(e) => setRTarget(e.target.value)} />
            </FormField>
            <FormField id="fraud-threshold" label="强度">
              <select
                className="h-9 w-full rounded border px-2 text-sm"
                value={rEffect}
                onChange={(e) => setREffect(e.target.value)}
              >
                {RESTRICTION_EFFECTS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="missing-policy" label="来源">
              <select
                className="h-9 w-full rounded border px-2 text-sm"
                value={rSource}
                onChange={(e) => setRSource(e.target.value)}
              >
                {RESTRICTION_SOURCES.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="r-kind" label="理由（客服要能向买家解释）">
              <Input value={rReason} onChange={(e) => setRReason(e.target.value)} />
            </FormField>
          </div>
          <Button className="mt-3" onClick={() => void submitRestriction()} disabled={busy}>
            登记限制
          </Button>
          {loading ? (
            <p className="mt-3 text-sm text-muted-foreground">加载中…</p>
          ) : restrictions.length === 0 ? (
            <EmptyState
              title="限制清单为空"
              description="没有登记任何限制时，收货国与平台来源两项一律判「已核对，未命中」——那表示清单里没有，不是平台判定通过。"
            />
          ) : (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">类型</th>
                    <th className="py-2 pr-3">目标</th>
                    <th className="py-2 pr-3">强度</th>
                    <th className="py-2">理由</th>
                  </tr>
                </thead>
                <tbody>
                  {restrictions.map((r) => (
                    <tr key={r.id} className="border-b">
                      <td className="py-2 pr-3">{restrictionKindText(r.restrictionKind)}</td>
                      <td className="py-2 pr-3 font-mono text-xs">{r.restrictionTarget}</td>
                      <td className="py-2 pr-3">
                        {restrictionEffectText(r.restrictionEffect)}
                        <div className="text-xs text-muted-foreground">
                          {restrictionSourceText(r.restrictionSource)}
                        </div>
                      </td>
                      <td className="py-2 text-muted-foreground">{r.reason || '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="mt-2 text-xs text-muted-foreground">
                {restrictionPageText({
                  items: restrictions,
                  total: restrictionPage.total,
                  page: restrictionPage.page,
                  size: PAGE_SIZE,
                  totalPages: restrictionPage.totalPages,
                })}
              </p>
            </div>
          )}
        </SectionCard>

        <SectionCard
          title="SKU 成本基线"
          description="卷 05 定价未交付，「低于成本线」只能靠租户维护的成本基线。币种必须与订单一致才参与比较。"
        >
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="r-target" label="SKU ID">
              <Input value={cSkuId} onChange={(e) => setCSkuId(e.target.value)} />
            </FormField>
            <FormField id="r-effect" label="单位成本">
              <Input value={cUnitCost} onChange={(e) => setCUnitCost(e.target.value)} />
            </FormField>
            <FormField id="r-source" label="币种">
              <Input value={cCurrency} onChange={(e) => setCCurrency(e.target.value)} />
            </FormField>
          </div>
          <Button className="mt-3" onClick={() => void submitSkuCost()} disabled={busy}>
            登记成本基线
          </Button>
          {loading ? (
            <p className="mt-3 text-sm text-muted-foreground">加载中…</p>
          ) : costs.length === 0 ? (
            <EmptyState
              title="没有成本基线"
              description="没有基线时「异常折扣」判缺参照而不是判「无异常」——没量到成本不等于成本正常。"
            />
          ) : (
            <div className="mt-3 overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">SKU</th>
                    <th className="py-2 pr-3">单位成本</th>
                    <th className="py-2">采集时刻</th>
                  </tr>
                </thead>
                <tbody>
                  {costs.map((c) => (
                    <tr key={c.id} className="border-b">
                      <td className="py-2 pr-3 font-mono text-xs">{c.skuId}</td>
                      <td className="py-2 pr-3">
                        {money(c.unitCost)} {c.currency}
                      </td>
                      <td className="py-2 text-xs text-muted-foreground">{isoText(c.capturedAt)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="mt-2 text-xs text-muted-foreground">
                {skuCostPageText({
                  items: costs,
                  total: costPage.total,
                  page: costPage.page,
                  size: PAGE_SIZE,
                  totalPages: costPage.totalPages,
                })}
              </p>
            </div>
          )}
        </SectionCard>
      </div>

      {/* ------------------------------------------------ 触发检查 */}
      <SectionCard
        title="触发检查"
        description="判定基准时刻恒等于订单的提交时刻：同一张单重复检查结果恒定。请求不接收「要不要自动取消」这类参数。"
      >
        <div className="flex flex-wrap items-end gap-3">
          <FormField id="r-reason" label="订单 ID">
            <Input
              value={orderId}
              onChange={(e) => setOrderId(e.target.value)}
              placeholder="UUID"
              className="w-80"
            />
          </FormField>
          <Button onClick={() => void runCheck()} disabled={busy || !caps?.policyConfigured}>
            <ShieldCheck className="mr-2 h-4 w-4" /> 运行检查
          </Button>
          {!caps?.policyConfigured ? (
            <span className="text-xs text-amber-600">先登记拦截策略才能检查。</span>
          ) : null}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 拦截清单 */}
      <SectionCard title="拦截清单" description="建议处置与最终处置分行显示；「建议取消」永远带「尚无人工结论」。">
        <div className="mb-3 flex flex-wrap items-end gap-3">
          <FormField id="c-sku" label="关键字（订单号 / 策略名）">
            <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} className="w-56" />
          </FormField>
          <FormField id="c-cost" label="建议处置">
            <select
              className="h-9 w-full rounded border px-2 text-sm"
              value={filterDisposition}
              onChange={(e) => {
                setFilterDisposition(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {RECOMMENDED_DISPOSITIONS.map((d) => (
                <option key={d.value} value={d.value}>
                  {d.label}
                </option>
              ))}
            </select>
          </FormField>
          <FormField id="c-currency" label="命中项">
            <select
              className="h-9 w-full rounded border px-2 text-sm"
              value={filterCode}
              onChange={(e) => {
                setFilterCode(e.target.value);
                setPage(0);
              }}
            >
              <option value="">全部</option>
              {CHECK_CODES.map((c) => (
                <option key={c.value} value={c.value}>
                  {c.label}
                </option>
              ))}
            </select>
          </FormField>
          <label className="flex items-center gap-2 pb-2 text-sm">
            <input
              type="checkbox"
              checked={onlyPending}
              onChange={(e) => {
                setOnlyPending(e.target.checked);
                setPage(0);
              }}
            />
            只看待人工处置
          </label>
        </div>

        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : checks.length === 0 ? (
          <EmptyState
            title="还没有拦截记录"
            description="填一个已提交订单的 ID 运行一次检查，或放宽筛选条件。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left">
                  <th className="py-2 pr-3">订单号</th>
                  <th className="py-2 pr-3">来源</th>
                  <th className="py-2 pr-3">命中 / 缺参照</th>
                  <th className="py-2 pr-3">建议处置</th>
                  <th className="py-2 pr-3">最终处置</th>
                  <th className="py-2">操作</th>
                </tr>
              </thead>
              <tbody>
                {checks.map((c) => (
                  <tr key={c.id} className="border-b align-top">
                    <td className="py-2 pr-3 font-mono text-xs">{c.orderNo}</td>
                    <td className="py-2 pr-3 text-xs">{c.orderSource}</td>
                    <td className="py-2 pr-3 text-xs">
                      命中 {c.flaggedCount}（BLOCK {c.blockedCount}）/ 缺参照 {c.missingCount}
                    </td>
                    <td className="py-2 pr-3">
                      {recommendedText(c.recommendedDisposition)}
                      {isPending(c) ? (
                        <div className="text-xs text-amber-600">
                          {c.recommendedDisposition === 'CANCEL'
                            ? '建议取消 · 尚无人工结论（不会自动取消）'
                            : '尚无人工结论'}
                        </div>
                      ) : null}
                    </td>
                    <td className="py-2 pr-3">
                      {c.finalDisposition ? (
                        <span className="text-emerald-700">{finalText(c.finalDisposition)}</span>
                      ) : (
                        <span className="text-muted-foreground">尚无人工结论</span>
                      )}
                    </td>
                    <td className="py-2">
                      <Button variant="outline" onClick={() => void openDetail(c.id)}>
                        详情
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="mt-2 flex items-center gap-3 text-xs text-muted-foreground">
              <span>{pageText({ items: checks, ...checkPage, size: PAGE_SIZE, totalPages: checkPage.totalPages } as never)}</span>
              <Button
                variant="outline"
                disabled={page <= 0}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="outline"
                disabled={page + 1 >= checkPage.totalPages}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 详情与人工处置 */}
      {detail ? (
        <SectionCard
          title={`检查详情 · ${detail.check.orderNo}`}
          description="八项命中恒定各占一行；缺参照与上游未接通也在，按判读顺序呈现。"
        >
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-4">
              <StatCard label="建议处置" value={recommendedText(detail.check.recommendedDisposition)} />
              <StatCard label="最终处置" value={finalText(detail.check.finalDisposition)} />
              <StatCard label="命中 / BLOCK / 缺参照"
                value={`${detail.check.flaggedCount} / ${detail.check.blockedCount} / ${detail.check.missingCount}`} />
              <StatCard label="判定基准时刻" value={isoText(detail.check.assessedReferenceAt)} />
            </div>

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">命中项</th>
                    <th className="py-2 pr-3">状态</th>
                    <th className="py-2 pr-3">严重度</th>
                    <th className="py-2 pr-3">实际 / 阈值</th>
                    <th className="py-2 pr-3">来源</th>
                    <th className="py-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.findings.map((f) => (
                    <tr key={f.id} className="border-b align-top">
                      <td className="py-2 pr-3 font-medium">{checkCodeText(f.checkCode)}</td>
                      <td className="py-2 pr-3">
                        <span
                          className={
                            f.status === 'FLAGGED'
                              ? 'text-red-600'
                              : f.status === 'CLEARED'
                                ? 'text-emerald-600'
                                : 'text-amber-600'
                          }
                        >
                          {statusText(f.status)}
                        </span>
                      </td>
                      <td className="py-2 pr-3 text-xs">{severityText(f.severity)}</td>
                      <td className="py-2 pr-3 text-xs">
                        {/* 没测到就显示「未测到」，不显示 0 */}
                        {observedText(f.observedValue) ?? '未测到'}
                        {f.thresholdValue !== null ? (
                          <span className="text-muted-foreground">
                            {' '}
                            / 阈值 {observedText(f.thresholdValue)}
                          </span>
                        ) : null}
                        {f.matchCount > 1 ? (
                          <div className="text-muted-foreground">命中 {f.matchCount} 处</div>
                        ) : null}
                        {f.evidenceReference ? (
                          <div className="font-mono text-muted-foreground">{f.evidenceReference}</div>
                        ) : null}
                      </td>
                      <td className="py-2 pr-3 text-xs text-muted-foreground">
                        {evidenceText(f.evidenceSource)}
                      </td>
                      <td className="py-2 text-xs text-muted-foreground">{f.explanation}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {detail.disposition ? (
              <Notice tone="info" title="人工处置留痕">
                人工处置留痕：{finalText(detail.disposition.decision)} ·{' '}
                {reasonCodeText(detail.disposition.reasonCode)} · 处置人 {detail.disposition.disposedBy}{' '}
                · {isoText(detail.disposition.disposedAt)}
                {detail.disposition.note ? ` · ${detail.disposition.note}` : ''}
              </Notice>
            ) : null}

            {detail.check.manualReviewRequired && !detail.check.finalDisposition ? (
              <div className="rounded border p-3">
                <div className="mb-2 flex items-center gap-2 text-sm font-medium">
                  <Scale className="h-4 w-4" /> 人工处置 —— 拦截本身不自动取消，取消只能从这里产生
                </div>
                <div className="grid gap-3 md:grid-cols-3">
                  <FormField id="order-id" label="处置结论">
                    <select
                      className="h-9 w-full rounded border px-2 text-sm"
                      value={dDecision}
                      onChange={(e) => setDDecision(e.target.value)}
                    >
                      {FINAL_DISPOSITIONS.map((d) => (
                        <option key={d.value} value={d.value}>
                          {d.label}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id="d-decision" label="原因码">
                    <select
                      className="h-9 w-full rounded border px-2 text-sm"
                      value={dReasonCode}
                      onChange={(e) => setDReasonCode(e.target.value)}
                    >
                      {REASON_CODES.map((r) => (
                        <option key={r.value} value={r.value}>
                          {r.label}
                        </option>
                      ))}
                    </select>
                  </FormField>
                  <FormField id="d-reason" label="备注">
                    <Input value={dNote} onChange={(e) => setDNote(e.target.value)} />
                  </FormField>
                </div>
                <Button
                  className="mt-3"
                  onClick={() => void submitDispositionFor(detail.check.id)}
                  disabled={busy}
                >
                  提交处置留痕
                </Button>
                <p className="mt-2 text-xs text-muted-foreground">
                  刻意**不提供**批量处置与「一键放行」：批量处置正是加粗句点名要避免的那种动作。
                </p>
              </div>
            ) : null}

            {!detail.check.manualReviewRequired ? (
              <Notice tone="success" title="无需人工处置">
                这张单的建议处置是「放行」，不需要人工处置 —— 给它补一条处置记录没有意义。
              </Notice>
            ) : null}
          </div>
        </SectionCard>
      ) : null}
    </div>
  );
}
