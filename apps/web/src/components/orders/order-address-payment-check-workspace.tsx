'use client';

import { useCallback, useEffect, useState } from 'react';
import { MapPin, RefreshCw, ShieldAlert, ShieldCheck, Truck } from 'lucide-react';
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
  AddressPaymentCapabilities,
  AddressPaymentCheck,
  AddressPaymentCheckDetail,
  AddressPaymentCheckInput,
  AddressPaymentCountryRule,
  AddressPaymentCountryRuleInput,
  AddressPaymentEmbargoRule,
  AddressPaymentEmbargoRuleInput,
  AddressPaymentOverview,
  AddressPaymentPaymentRule,
  AddressPaymentPaymentRuleInput,
  AddressPaymentPolicy,
  AddressPaymentPolicyInput,
} from '../../../../../packages/api-contracts/src/index';
import {
  DELIVERY_STATUSES,
  EMBARGO_LEVELS,
  MISSING_REFERENCE_POLICIES,
  PAYMENT_METHODS,
  PAYMENT_STATUSES,
  PAYMENT_STATUS_SOURCES,
  RISK_LEVELS,
  RULE_SEVERITIES,
  RULE_SOURCES,
  createCheck,
  deliveryText,
  deliveryTone,
  embargoLevelText,
  embargoListNote,
  embargoPageText,
  errorText,
  evidenceText,
  fetchCapabilities,
  fetchCheck,
  fetchChecks,
  fetchCountryRules,
  fetchEmbargoRules,
  fetchOverview,
  fetchPaymentRules,
  fetchPolicy,
  insuranceGapNote,
  isoText,
  itemTone,
  missingNote,
  missingPolicyText,
  money,
  observedText,
  pageText,
  countryRulePageText,
  paymentMethodText,
  paymentRulePageText,
  paymentStatusSourceText,
  paymentStatusText,
  riskText,
  riskTone,
  ruleSeverityText,
  ruleSourceText,
  saveCountryRule,
  saveEmbargoRule,
  savePaymentRule,
  savePolicy,
  severityText,
  sortItems,
  statusText,
} from './order-address-payment-check-model';

const PAGE_SIZE = 20;

const TONE_CLASS: Record<string, string> = {
  ok: 'border-emerald-300 bg-emerald-50 text-emerald-800',
  warn: 'border-amber-300 bg-amber-50 text-amber-800',
  block: 'border-red-300 bg-red-50 text-red-800',
  critical: 'border-purple-400 bg-purple-50 text-purple-900',
  unknown: 'border-slate-400 bg-slate-100 text-slate-700',
  muted: 'border-slate-200 bg-slate-50 text-slate-600',
};

/**
 * 9.2.2.1 地址与支付校验工作区。
 *
 * 蓝图：「校验买家地址可投递性（国家/邮编/街道格式）、禁运国家清单、支付状态一致性
 * （平台已付/货到付款/未付款）。输出：校验结果 + 风险等级。」
 *
 * 蓝图加粗句：「<b>货到付款订单的履约风险最高</b>，需额外风控与保险策略。」
 *
 * <p>本工作区是**真实数据工作区**而不是占位页：禁运国家清单、国别地址格式规则、
 * 支付状态一致性规则、校验结果与七项明细全部落在 PostgreSQL 16 上。
 * 加载态 / 空态 / 错误态三态分开渲染。
 *
 * <p>布局顺序刻意按客服的判读顺序：
 * <b>能力与缺口 → 策略 → 禁运清单 → 国别规则与支付规则 → 触发校验 → 校验清单 → 详情</b>。
 *
 * <p>⚠️ 界面上**刻意**不提供的东西：
 * <ul>
 *   <li>不改写已落库的校验结论 —— 校验是凭据，改一格就再也复算不出当时为什么判成这个风险等级。</li>
 *   <li>不算保费、不推荐投保方案 —— 货到付款保险策略在本系统里未交付，
 *       界面上显式标成「未交付」并点名归属模块，而不是给一个假入口。</li>
 * </ul>
 */
export function OrderAddressPaymentCheckWorkspace() {
  const [caps, setCaps] = useState<AddressPaymentCapabilities | null>(null);
  const [summary, setSummary] = useState<AddressPaymentOverview | null>(null);
  const [policy, setPolicy] = useState<AddressPaymentPolicy | null>(null);
  const [checks, setChecks] = useState<AddressPaymentCheck[]>([]);
  const [checkPage, setCheckPage] = useState({ total: 0, page: 0, totalPages: 0 });
  const [embargoes, setEmbargoes] = useState<AddressPaymentEmbargoRule[]>([]);
  const [embargoPage, setEmbargoPage] = useState({ total: 0, page: 0, totalPages: 0 });
  const [countryRules, setCountryRules] = useState<AddressPaymentCountryRule[]>([]);
  const [countryPage, setCountryPage] = useState({ total: 0, page: 0, totalPages: 0 });
  const [paymentRules, setPaymentRules] = useState<AddressPaymentPaymentRule[]>([]);
  const [paymentPage, setPaymentPage] = useState({ total: 0, page: 0, totalPages: 0 });
  const [detail, setDetail] = useState<AddressPaymentCheckDetail | null>(null);

  const [keyword, setKeyword] = useState('');
  const [filterRisk, setFilterRisk] = useState('');
  const [filterDelivery, setFilterDelivery] = useState('');
  const [page, setPage] = useState(0);

  const [orderId, setOrderId] = useState('');
  const [paymentStatus, setPaymentStatus] = useState('');
  const [paymentStatusSource, setPaymentStatusSource] = useState('PLATFORM_CALLBACK');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');

  // 策略表单
  const [policyName, setPolicyName] = useState('默认校验策略');
  const [missingPolicy, setMissingPolicy] = useState('ASSESS_KNOWN_ONLY');

  // 禁运清单表单
  const [embargoCountry, setEmbargoCountry] = useState('US');
  const [embargoLevel, setEmbargoLevel] = useState('BLOCK');
  const [embargoSource, setEmbargoSource] = useState('TENANT_MAINTAINED');
  const [embargoReason, setEmbargoReason] = useState('');

  // 国别规则表单
  const [countryCode, setCountryCode] = useState('US');
  const [postcodeRequired, setPostcodeRequired] = useState(true);
  const [postcodePattern, setPostcodePattern] = useState('^[0-9]{5}(-[0-9]{4})?$');
  const [postcodeNote, setPostcodeNote] = useState('ZIP 或 ZIP+4');
  const [streetRequired, setStreetRequired] = useState(true);
  const [streetMinLength, setStreetMinLength] = useState('5');
  const [cityRequired, setCityRequired] = useState(true);
  const [regionRequired, setRegionRequired] = useState(false);
  const [phoneRequired, setPhoneRequired] = useState(true);

  // 支付规则表单
  const [ruleMethod, setRuleMethod] = useState('PREPAID');
  const [ruleStatus, setRuleStatus] = useState('UNPAID');
  const [ruleSeverity, setRuleSeverity] = useState('BLOCK');
  const [ruleReason, setRuleReason] = useState('');

  const loadAll = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [capabilities, overview, policyResult, checkResult, embargoResult, countryResult, paymentResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchOverview(),
          // 策略没登记就是 404，那是「未配置」而不是错误：用 catch 降级成 null，
          // 这样页面能显示「尚未登记校验策略」并给出登记表单。
          fetchPolicy().catch(() => null),
          fetchChecks({
            keyword: keyword || undefined,
            riskLevel: filterRisk || undefined,
            deliveryStatus: filterDelivery || undefined,
            page,
                  }),
          fetchEmbargoRules({ page: 0, size: PAGE_SIZE }),
          fetchCountryRules({ page: 0, size: PAGE_SIZE }),
          fetchPaymentRules({ page: 0, size: PAGE_SIZE }),
        ]);
      setCaps(capabilities);
      setSummary(overview);
      setPolicy(policyResult);
      if (policyResult) {
        setPolicyName(policyResult.policyName);
        setMissingPolicy(policyResult.missingReferencePolicy);
      }
      setChecks(checkResult.items);
      setCheckPage({ total: checkResult.total, page: checkResult.page, totalPages: checkResult.totalPages });
      setEmbargoes(embargoResult.items);
      setEmbargoPage({
        total: embargoResult.total,
        page: embargoResult.page,
        totalPages: embargoResult.totalPages,
      });
      setCountryRules(countryResult.items);
      setCountryPage({
        total: countryResult.total,
        page: countryResult.page,
        totalPages: countryResult.totalPages,
      });
      setPaymentRules(paymentResult.items);
      setPaymentPage({
        total: paymentResult.total,
        page: paymentResult.page,
        totalPages: paymentResult.totalPages,
      });
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [keyword, filterRisk, filterDelivery, page]);

  useEffect(() => {
    void loadAll();
  }, [loadAll]);

  async function openDetail(checkId: string) {
    setBusy(true);
    setError('');
    try {
      setDetail(await fetchCheck(checkId));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function runCheck() {
    if (!orderId.trim()) {
      setError('必须填写要校验的订单 ID');
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      // ⚠️ codegen 把 @Schema(nullable) 的字段生成成**必填的 nullable**，
      // 所以「不提供支付状态」必须显式写 null，不能靠不传这个 key。
      const input: AddressPaymentCheckInput = {
        orderId: orderId.trim(),
        paymentStatus: paymentStatus
          ? (paymentStatus as AddressPaymentCheckInput['paymentStatus'])
          : null,
        paymentStatusSource: paymentStatus
          ? (paymentStatusSource as AddressPaymentCheckInput['paymentStatusSource'])
          : null,
        paymentStatusObservedAt: paymentStatus ? new Date().toISOString() : null,
      };
      const saved = await createCheck(input);
      setNotice(saved.message);
      await loadAll();
      await openDetail(saved.detail.check.id);
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
        missingReferencePolicy: missingPolicy as AddressPaymentPolicyInput['missingReferencePolicy'],
      });
      setNotice(
        `校验策略「${saved.policyName}」已保存为第 ${saved.version} 版。` +
          `登记时会顺带幂等装一份基线码表（国别地址格式规则 + 支付一致性规则），` +
          `租户已改过的行不会被覆盖。`,
      );
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitEmbargo() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveEmbargoRule({
        countryCode: embargoCountry.trim().toUpperCase(),
        embargoLevel: embargoLevel as AddressPaymentEmbargoRuleInput['embargoLevel'],
        source: embargoSource as AddressPaymentEmbargoRuleInput['source'],
        sourceReference: '',
        reason: embargoReason,
        effectiveFrom: null,
        effectiveTo: null,
        enabled: true,
      });
      setNotice(
        `已登记禁运国家 ${saved.countryCode}（${embargoLevelText(saved.embargoLevel)}）。` +
          `生效期相对**订单提交时刻**判定，所以订单提交之后才生效的规则对它没有贡献。`,
      );
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitCountryRule() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await saveCountryRule({
        countryCode: countryCode.trim().toUpperCase(),
        postcodeRequired,
        postcodePattern: postcodeRequired ? postcodePattern.trim() : '',
        postcodeNote: postcodeNote,
        streetRequired,
        streetMinLength: Number(streetMinLength),
        cityRequired,
        regionRequired,
        phoneRequired,
        enabled: true,
      } as AddressPaymentCountryRuleInput);
      setNotice(
        `已保存 ${saved.countryCode} 的地址格式规则：邮编 ${saved.postcodeRequired ? saved.postcodePattern : '不要求'}，` +
          `街道最小 ${saved.streetMinLength} 个字符。` +
          `邮编正则在服务端先编译一遍 —— 编译不过的正则会让该国的邮编项永远判不了，而界面上它显示为「已配置」。`,
      );
      await loadAll();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  }

  async function submitPaymentRule() {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await savePaymentRule({
        paymentMethod: ruleMethod,
        observedStatus: ruleStatus,
        expectedSeverity: ruleSeverity as AddressPaymentPaymentRuleInput['expectedSeverity'],
        reason: ruleReason,
        enabled: true,
      });
      setNotice(
        `已保存支付一致性规则（${saved.paymentMethod} × ${saved.observedStatus}）→ ` +
          `${ruleSeverityText(saved.expectedSeverity)}。` +
          `自洽组合也占一行：查不到规则只能是「缺参照」，不会是「自洽」。`,
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
        title="地址与支付校验"
        description="校验买家地址可投递性、禁运国家清单与支付状态一致性，输出校验结果与风险等级。"
        actions={
          <Button onClick={() => void loadAll()} disabled={loading || busy}>
            <RefreshCw className="mr-2 h-4 w-4" /> 刷新
          </Button>
        }
      />

      {error ? <Notice tone="danger" title="操作失败">{error}</Notice> : null}
      {notice ? <Notice tone="success" title="已完成">{notice}</Notice> : null}

      {/* ------------------------------------------------ 能力与缺口 */}
      <SectionCard
        title="能力与上下游缺口"
        description="哪几项真接通、哪几项靠租户维护、哪几项现在判不了。"
      >
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
                校验策略：{caps.policyConfigured ? '已登记' : '尚未登记'}
              </span>
              <span className="rounded border px-2 py-1 text-xs">
                禁运清单：{caps.embargoListConfigured ? '非空' : '为空（没有禁运任何国家）'}
              </span>
            </div>
            <div className="rounded border p-3 text-sm">
              <p className="font-medium">风险等级判定顺序</p>
              <p className="text-muted-foreground">{caps.riskOrder}</p>
              <p className="mt-2 font-medium">校验结果判定顺序</p>
              <p className="text-muted-foreground">{caps.deliveryOrder}</p>
            </div>
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">检查项</th>
                    <th className="py-2 pr-3">输入来源</th>
                    <th className="py-2 pr-3">是否接通</th>
                    <th className="py-2 pr-3">归属</th>
                    <th className="py-2">缺口与影响</th>
                  </tr>
                </thead>
                <tbody>
                  {caps.items.map((item) => (
                    <tr key={item.code} className="border-b align-top">
                      <td className="py-2 pr-3">{item.label}</td>
                      <td className="py-2 pr-3 text-muted-foreground">{item.source}</td>
                      <td className="py-2 pr-3">
                        <span
                          className={`rounded border px-2 py-1 text-xs ${
                            item.available ? TONE_CLASS.ok : TONE_CLASS.unknown
                          }`}
                        >
                          {item.available ? '已接通' : '未接通'}
                        </span>
                      </td>
                      <td className="py-2 pr-3 text-muted-foreground">
                        {item.available ? '—' : item.ownerModule}
                      </td>
                      <td className="py-2 text-muted-foreground">
                        {item.available ? item.thresholdSource : item.impact}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Notice tone="warning" title="上下游缺口">
              {caps.gaps}
            </Notice>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 概览 */}
      {summary ? (
        <div className="grid gap-3 md:grid-cols-4">
          <StatCard label="累计校验" value={String(summary.totalChecks)} comparisonLabel="张订单次校验" />
          <StatCard
            label="货到付款"
            value={String(summary.codChecks)}
            comparisonLabel={`最高风险 ${summary.criticalChecks} 单`}
          />
          <StatCard
            label="不可投递"
            value={String(summary.notDeliverableChecks)}
            comparisonLabel={`未定 ${summary.undeterminedChecks} 单`}
          />
          <StatCard
            label="码表行数"
            value={`${summary.embargoRuleCount}/${summary.countryRuleCount}/${summary.paymentRuleCount}`}
            comparisonLabel="禁运 / 国别 / 支付规则"
          />
        </div>
      ) : null}

      {/* ------------------------------------------------ 校验策略 */}
      <SectionCard
        title="校验策略"
        description="每租户一条。没登记就报「未配置」，不给隐含默认值。"
      >
        {loading ? (
          <p className="text-sm text-muted-foreground">加载中…</p>
        ) : (
          <div className="space-y-4">
            {policy ? (
              <Notice tone="success" title={`已登记（第 ${policy.version} 版）`}>
                {policy.policyName} · 缺参照口径：{missingPolicyText(policy.missingReferencePolicy)} ·
                更新时间 {isoText(policy.updatedAt)}
              </Notice>
            ) : (
              <Notice tone="warning" title="尚未登记校验策略">
                先登记策略才能跑校验：禁运清单为空与「清单位国家真的不在禁运清单里」长得一模一样。
              </Notice>
            )}
            <div className="grid gap-3 md:grid-cols-2">
              <FormField id="ap-policy-name" label="策略名称" required>
                <Input value={policyName} onChange={(e) => setPolicyName(e.target.value)} />
              </FormField>
              <FormField
                id="ap-missing-policy"
                label="缺参照怎么办"
                hint="缺参照即不出结论 = 有任何一项没量到就不给「可投递」"
              >
                <select
                  className="h-9 w-full rounded border bg-background px-2 text-sm"
                  value={missingPolicy}
                  onChange={(e) => setMissingPolicy(e.target.value)}
                >
                  {MISSING_REFERENCE_POLICIES.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
            <Button onClick={() => void submitPolicy()} disabled={busy}>
              保存策略（同时幂等安装基线码表）
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------ 禁运清单 */}
      <SectionCard
        title="禁运国家清单"
        description="租户可维护的码表，不是写死在代码里的国家数组。"
      >
        <div className="space-y-4">
          <Notice tone="info" title="清单状态">
            {embargoListNote(summary?.embargoRuleCount ?? embargoPage.total)}
          </Notice>
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="ap-embargo-country" label="国家码" required hint="ISO alpha-2 大写">
              <Input
                value={embargoCountry}
                onChange={(e) => setEmbargoCountry(e.target.value)}
                maxLength={2}
              />
            </FormField>
            <FormField id="ap-embargo-level" label="禁运强度">
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={embargoLevel}
                onChange={(e) => setEmbargoLevel(e.target.value)}
              >
                {EMBARGO_LEVELS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="ap-embargo-source" label="来源">
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={embargoSource}
                onChange={(e) => setEmbargoSource(e.target.value)}
              >
                {RULE_SOURCES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="ap-embargo-reason" label="理由">
              <Input value={embargoReason} onChange={(e) => setEmbargoReason(e.target.value)} />
            </FormField>
          </div>
          <Button onClick={() => void submitEmbargo()} disabled={busy}>
            登记禁运国家
          </Button>
          {embargoes.length === 0 ? (
            <EmptyState
              title="禁运清单为空"
              description="本租户当前没有禁运任何国家。这是租户自己的选择，不是「没有禁运风险」。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">国家</th>
                    <th className="py-2 pr-3">强度</th>
                    <th className="py-2 pr-3">来源</th>
                    <th className="py-2 pr-3">理由</th>
                    <th className="py-2">生效期</th>
                  </tr>
                </thead>
                <tbody>
                  {embargoes.map((rule) => (
                    <tr key={rule.id} className="border-b">
                      <td className="py-2 pr-3 font-mono">{rule.countryCode}</td>
                      <td className="py-2 pr-3">{embargoLevelText(rule.embargoLevel)}</td>
                      <td className="py-2 pr-3 text-muted-foreground">{ruleSourceText(rule.source)}</td>
                      <td className="py-2 pr-3">{rule.reason || '—'}</td>
                      <td className="py-2 text-muted-foreground">
                        {rule.effectiveFrom || '不限'} ~ {rule.effectiveTo || '不限'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="mt-2 text-xs text-muted-foreground">{embargoPageText({
                total: embargoPage.total,
                page: embargoPage.page,
                            totalPages: embargoPage.totalPages,
              })}</p>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 国别规则与支付规则 */}
      <div className="grid gap-6 lg:grid-cols-2">
        <SectionCard
          title="国别地址格式规则"
          description="一个国家一套规则：邮编正则、街道最小长度、必填项。"
        >
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-2">
              <FormField id="ap-country" label="国家码" required>
                <Input
                  value={countryCode}
                  onChange={(e) => setCountryCode(e.target.value)}
                  maxLength={2}
                />
              </FormField>
              <FormField id="ap-postcode-note" label="邮编说明">
                <Input value={postcodeNote} onChange={(e) => setPostcodeNote(e.target.value)} />
              </FormField>
            </div>
            <FormField
              id="ap-postcode-pattern"
              label="邮编正则"
              hint="必填时必填：编译不过的正则会让该国的邮编项永远判不了"
            >
              <Input
                value={postcodePattern}
                onChange={(e) => setPostcodePattern(e.target.value)}
                disabled={!postcodeRequired}
              />
            </FormField>
            <div className="grid gap-3 md:grid-cols-3 text-sm">
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={postcodeRequired}
                  onChange={(e) => setPostcodeRequired(e.target.checked)}
                />
                邮编必填
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={streetRequired}
                  onChange={(e) => setStreetRequired(e.target.checked)}
                />
                街道必填
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={cityRequired}
                  onChange={(e) => setCityRequired(e.target.checked)}
                />
                城市必填
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={regionRequired}
                  onChange={(e) => setRegionRequired(e.target.checked)}
                />
                省份/州必填
              </label>
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={phoneRequired}
                  onChange={(e) => setPhoneRequired(e.target.checked)}
                />
                电话必填
              </label>
              <FormField id="ap-street-min" label="街道最小长度">
                <Input
                  value={streetMinLength}
                  onChange={(e) => setStreetMinLength(e.target.value)}
                />
              </FormField>
            </div>
            <Button onClick={() => void submitCountryRule()} disabled={busy}>
              保存国别规则
            </Button>
            {countryRules.length === 0 ? (
              <EmptyState
                title="尚未登记国别规则"
                description="没有规则的国，邮编 / 街道 / 必填项三项会落「缺参照」，而不是「通过」。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="py-2 pr-3">国家</th>
                      <th className="py-2 pr-3">邮编规则</th>
                      <th className="py-2 pr-3">街道</th>
                      <th className="py-2">必填</th>
                    </tr>
                  </thead>
                  <tbody>
                    {countryRules.map((rule) => (
                      <tr key={rule.id} className="border-b">
                        <td className="py-2 pr-3 font-mono">{rule.countryCode}</td>
                        <td className="py-2 pr-3 font-mono text-xs">
                          {rule.postcodeRequired ? rule.postcodePattern : '不要求'}
                        </td>
                        <td className="py-2 pr-3">≥ {rule.streetMinLength} 字</td>
                        <td className="py-2 text-muted-foreground">
                          {[
                            rule.cityRequired ? '城市' : null,
                            rule.regionRequired ? '省份' : null,
                            rule.phoneRequired ? '电话' : null,
                          ]
                            .filter(Boolean)
                            .join('、') || '无'}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="mt-2 text-xs text-muted-foreground">{countryRulePageText({
                  total: countryPage.total,
                  page: countryPage.page,
                                totalPages: countryPage.totalPages,
                })}</p>
              </div>
            )}
          </div>
        </SectionCard>

        <SectionCard
          title="支付状态一致性规则"
          description="付款方式 × 观察到的支付状态 → 自洽还是矛盾。自洽组合也占一行。"
        >
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="ap-rule-method" label="付款方式">
                <select
                  className="h-9 w-full rounded border bg-background px-2 text-sm"
                  value={ruleMethod}
                  onChange={(e) => setRuleMethod(e.target.value)}
                >
                  {PAYMENT_METHODS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="ap-rule-status" label="观察到的支付状态">
                <select
                  className="h-9 w-full rounded border bg-background px-2 text-sm"
                  value={ruleStatus}
                  onChange={(e) => setRuleStatus(e.target.value)}
                >
                  {PAYMENT_STATUSES.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="ap-rule-severity" label="结论">
                <select
                  className="h-9 w-full rounded border bg-background px-2 text-sm"
                  value={ruleSeverity}
                  onChange={(e) => setRuleSeverity(e.target.value)}
                >
                  {RULE_SEVERITIES.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
            <FormField id="ap-rule-reason" label="理由">
              <Input value={ruleReason} onChange={(e) => setRuleReason(e.target.value)} />
            </FormField>
            <Button onClick={() => void submitPaymentRule()} disabled={busy}>
              保存支付规则
            </Button>
            {paymentRules.length === 0 ? (
              <EmptyState
                title="尚未登记支付规则"
                description="没有规则时支付一致性那一项落「缺参照」，而不是「一致」。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left">
                      <th className="py-2 pr-3">付款方式</th>
                      <th className="py-2 pr-3">支付状态</th>
                      <th className="py-2 pr-3">结论</th>
                      <th className="py-2">理由</th>
                    </tr>
                  </thead>
                  <tbody>
                    {paymentRules.map((rule) => (
                      <tr key={rule.id} className="border-b">
                        <td className="py-2 pr-3">{paymentMethodText(rule.paymentMethod)}</td>
                        <td className="py-2 pr-3">{paymentStatusText(rule.observedStatus)}</td>
                        <td className="py-2 pr-3">{ruleSeverityText(rule.expectedSeverity)}</td>
                        <td className="py-2 text-muted-foreground">{rule.reason || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="mt-2 text-xs text-muted-foreground">{paymentRulePageText({
                  total: paymentPage.total,
                  page: paymentPage.page,
                                totalPages: paymentPage.totalPages,
                })}</p>
              </div>
            )}
          </div>
        </SectionCard>
      </div>

      {/* ------------------------------------------------ 触发校验 */}
      <SectionCard
        title="跑一次校验"
        description="判定基准时刻取订单的提交时刻，同一张单重复校验结果恒定。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="ap-order-id" label="订单 ID" required>
              <Input value={orderId} onChange={(e) => setOrderId(e.target.value)} />
            </FormField>
            <FormField
              id="ap-payment-status"
              label="支付状态观察值"
              hint="本服务没有支付状态事实（12 财务未交付），由调用方提供"
            >
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={paymentStatus}
                onChange={(e) => setPaymentStatus(e.target.value)}
              >
                <option value="">不提供（该项落「缺参照」）</option>
                {PAYMENT_STATUSES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="ap-payment-source" label="观察值来源">
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={paymentStatusSource}
                onChange={(e) => setPaymentStatusSource(e.target.value)}
                disabled={!paymentStatus}
              >
                {PAYMENT_STATUS_SOURCES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
          </div>
          <Button onClick={() => void runCheck()} disabled={busy}>
            <MapPin className="mr-2 h-4 w-4" /> 跑一次校验
          </Button>
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 校验结果清单 */}
      <SectionCard title="校验结果清单" description="真分页 + 真筛选。">
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="ap-filter-keyword" label="订单号">
              <Input value={keyword} onChange={(e) => setKeyword(e.target.value)} />
            </FormField>
            <FormField id="ap-filter-risk" label="风险等级">
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={filterRisk}
                onChange={(e) => setFilterRisk(e.target.value)}
              >
                <option value="">全部</option>
                {RISK_LEVELS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="ap-filter-delivery" label="校验结果">
              <select
                className="h-9 w-full rounded border bg-background px-2 text-sm"
                value={filterDelivery}
                onChange={(e) => setFilterDelivery(e.target.value)}
              >
                <option value="">全部</option>
                {DELIVERY_STATUSES.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
          </div>
          {loading ? (
            <p className="text-sm text-muted-foreground">加载中…</p>
          ) : checks.length === 0 ? (
            <EmptyState
              title="还没有校验记录"
              description="跑一次校验之后，这里会按判定基准时刻倒序列出每一单的结果。"
            />
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">订单号</th>
                    <th className="py-2 pr-3">校验结果</th>
                    <th className="py-2 pr-3">风险等级</th>
                    <th className="py-2 pr-3">付款方式 / 状态</th>
                    <th className="py-2 pr-3">命中</th>
                    <th className="py-2 pr-3">判定基准时刻</th>
                    <th className="py-2">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {checks.map((check) => (
                    <tr key={check.id} className="border-b">
                      <td className="py-2 pr-3 font-mono">{check.orderNo}</td>
                      <td className="py-2 pr-3">
                        <span
                          className={`rounded border px-2 py-1 text-xs ${
                            TONE_CLASS[deliveryTone(check.deliveryStatus)]
                          }`}
                        >
                          {deliveryText(check.deliveryStatus)}
                        </span>
                      </td>
                      <td className="py-2 pr-3">
                        <span
                          className={`rounded border px-2 py-1 text-xs ${
                            TONE_CLASS[riskTone(check.riskLevel)]
                          }`}
                        >
                          {riskText(check.riskLevel)}
                        </span>
                      </td>
                      <td className="py-2 pr-3">
                        {paymentMethodText(check.paymentMethod)} ·{' '}
                        {paymentStatusText(check.paymentStatus)}
                      </td>
                      <td className="py-2 pr-3">
                        命中 {check.flaggedCount} / 缺参照 {check.noReferenceCount} / 未交付{' '}
                        {check.upstreamUnavailableCount}
                      </td>
                      <td className="py-2 pr-3 text-muted-foreground">
                        {isoText(check.assessedReferenceAt)}
                      </td>
                      <td className="py-2">
                        <Button size="sm" variant="ghost" onClick={() => void openDetail(check.id)}>
                          详情
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="mt-3 flex items-center justify-between text-sm">
                <span className="text-muted-foreground">{pageText({
                  total: checkPage.total,
                  page: checkPage.page,
                                totalPages: checkPage.totalPages,
                })}</span>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={checkPage.page <= 0}
                    onClick={() => setPage(Math.max(0, checkPage.page - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={checkPage.page + 1 >= checkPage.totalPages}
                    onClick={() => setPage(checkPage.page + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ------------------------------------------------ 校验详情 */}
      {detail ? (
        <SectionCard
          title={`校验详情 · ${detail.check.orderNo}`}
          description="输出两件：校验结果（可投递性）与风险等级。七项恒定各占一行。"
        >
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-4">
              <StatCard
                label="校验结果"
                value={deliveryText(detail.check.deliveryStatus)}
                comparisonLabel="地址类四项判定"
              />
              <StatCard
                label="风险等级"
                value={riskText(detail.check.riskLevel)}
                comparisonLabel={detail.check.codFlagged ? '货到付款专属最高档' : '按命中严重度定档'}
              />
              <StatCard
                label="订单金额"
                value={`${money(detail.check.orderAmount)} ${detail.check.orderCurrency}`}
                comparisonLabel={`付款方式 ${paymentMethodText(detail.check.paymentMethod)}`}
              />
              <StatCard
                label="支付状态观察值"
                value={paymentStatusText(detail.check.paymentStatus)}
                comparisonLabel={paymentStatusSourceText(detail.check.paymentStatusSource)}
              />
            </div>

            {detail.check.codFlagged ? (
              <Notice tone="warning" title="货到付款：履约风险最高">
                这一单的付款方式是货到付款，风险等级恒为最高档（CRITICAL），无论其它项结论如何。
                额外风控走 9.2.2.2 的风控评估；保险策略本系统未交付。
              </Notice>
            ) : null}
            {insuranceGapNote(detail.check) ? (
              <Notice tone="warning" title="保险策略未交付">
                {insuranceGapNote(detail.check)}
              </Notice>
            ) : null}
            {missingNote(detail.check) ? (
              <Notice tone="info" title="有项没量到">
                {missingNote(detail.check)}
              </Notice>
            ) : null}

            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left">
                    <th className="py-2 pr-3">检查项</th>
                    <th className="py-2 pr-3">结论</th>
                    <th className="py-2 pr-3">严重度</th>
                    <th className="py-2 pr-3">取值来源</th>
                    <th className="py-2 pr-3">观测值</th>
                    <th className="py-2">说明</th>
                  </tr>
                </thead>
                <tbody>
                  {sortItems(detail.items).map((item) => {
                    const observed = observedText(item.observedValue);
                    return (
                      <tr key={item.itemCode} className="border-b align-top">
                        <td className="py-2 pr-3">{item.itemLabel}</td>
                        <td className="py-2 pr-3">
                          <span
                            className={`rounded border px-2 py-1 text-xs ${
                              TONE_CLASS[itemTone(item)]
                            }`}
                          >
                            {statusText(item.status)}
                          </span>
                        </td>
                        <td className="py-2 pr-3">{severityText(item.severity)}</td>
                        <td className="py-2 pr-3 text-muted-foreground">
                          {evidenceText(item.evidenceSource)}
                        </td>
                        <td className="py-2 pr-3">
                          {/* ⚠️ 没量到显示「未测到」，不显示 0 */}
                          {observed === null ? (
                            <span className="text-muted-foreground">未测到</span>
                          ) : (
                            observed
                          )}
                        </td>
                        <td className="py-2 text-muted-foreground">
                          {item.explanation}
                          {item.evidenceReference ? `（${item.evidenceReference}）` : ''}
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
            <p className="text-xs text-muted-foreground">
              判定基准时刻 {isoText(detail.check.assessedReferenceAt)} · 策略「
              {detail.check.policyName}」第 {detail.check.policyVersion} 版 · 命中{' '}
              {detail.check.flaggedCount} 条（BLOCK {detail.check.blockedCount} / WARN{' '}
              {detail.check.warnCount} / CRITICAL {detail.check.criticalCount}）· 校验结论是
              <b>凭据</b>，不提供改写入口。
            </p>
          </div>
        </SectionCard>
      ) : null}

      {!detail && !loading ? (
        <SectionCard title="校验详情" description="从上面的清单里点「详情」。">
          <EmptyState
            title="尚未打开任何一次校验"
            description="点「跑一次校验」或从清单里选一行，查看七项明细与两个输出。"
          />
        </SectionCard>
      ) : null}

      <div className="flex flex-wrap gap-3 text-xs text-muted-foreground">
        <span className="inline-flex items-center gap-1">
          <ShieldCheck className="h-3 w-3" /> 可投递
        </span>
        <span className="inline-flex items-center gap-1">
          <ShieldAlert className="h-3 w-3" /> 不可投递 / 高风险
        </span>
        <span className="inline-flex items-center gap-1">
          <Truck className="h-3 w-3" /> 货到付款（最高风险档）
        </span>
      </div>
    </div>
  );
}
