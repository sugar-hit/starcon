'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';

import {
  EMPTY_ACTUAL_FILTERS,
  EMPTY_RULE_FILTERS,
  PLATFORM_FEE_SECTIONS,
  actualSourceLabel,
  allocationBasisLabel,
  chargeBasisLabel,
  createFeeRule,
  fetchFeeActuals,
  fetchFeeEstimate,
  fetchFeeRule,
  fetchFeeRuleList,
  fetchFeeVariance,
  fetchPlatformDirectory,
  fetchPlatformFeeCapabilities,
  feeTypeLabel,
  moneyText,
  moneyWithCurrency,
  rateText,
  recordFeeActual,
  replaceFeeTiers,
  ruleScopeText,
  timeText,
  updateFeeRule,
  validateActual,
  validateRule,
  validateVoidReason,
  varianceHeadline,
  varianceText,
  varianceTone,
  voidFeeActual,
} from './platform-fee-model';
import type {
  FeeActualFilters,
  FeeActualInput,
  FeeActualListPage,
  FeeEstimate,
  FeeRuleDetail,
  FeeRuleFilters,
  FeeRuleInput,
  FeeRuleListPage,
  FeeTierInput,
  FeeVarianceReport,
  PlatformDirectory,
  PlatformFeeCapabilities,
} from './platform-fee-model';
import { LogisticsFreightWorkspace } from './logistics-freight-workspace';

/**
 * 5.2.3.1 平台费用工作区。
 *
 * <p>🔴 页面最上面那一块**不是提示语，是这一页的设计前提**：
 * 蓝图加粗句「**规则参数用于事前测算，实际费用来自 12 的结算对账**——两者差额要在利润报表中
 * 显性展示为『费用差异』，否则测算永远不准却不知道为什么」。
 *
 * <p>所以本页把三件事摆在最显眼的位置：
 *
 * <ol>
 *   <li><b>两个口径并排</b>：「费用规则（事前测算口径）」与「实际费用（事后对账口径）」
 *       是两块独立的区域，各自带自己的来源标注。实际费用当前**只有人工登记**一种来源
 *       （12 财务的结算对账尚未交付），页面上如实写明，**不冒充对账结果**。
 *   <li><b>差额必须能算出来并被展示</b>：「费用差异」是独立的一块，按
 *       （费用类型 × 币种）列出「实际 / 按规则测算 / 差额」。
 *   <li><b>🔴 算不出来就说算不出来</b>：没有实际费用时显示「无法计算差异」+ 原因；
 *       规则没配、阶梯落不进档时显示「算不出来」——**任何情况下都不显示「差异 0.00」**。
 * </ol>
 *
 * <p>🔴 **null 不渲染成 0**：规则没配 ≠ 这笔费用是 0；平台没收钱 ≠ 算不出来。
 * 两者的区别在页面上必须看得出来。
 *
 * <p>🔴 **分区块结构**：5.2.3.2 物流与头程费用与本模块共用 L3 路由 {@code /pricing/fees}。
 * 本模块只占「平台费用」这一块；未交付的兄弟分区**显式标成未交付**（不画空白框冒充已交付），
 * 后续兄弟模块在 {@link PLATFORM_FEE_SECTIONS} 里新增一条分区即可，不需要重写本页骨架。
 */
export function PlatformFeeWorkspace() {
  const [capabilities, setCapabilities] = useState<PlatformFeeCapabilities | null>(null);
  const [directory, setDirectory] = useState<PlatformDirectory | null>(null);
  const [rulePage, setRulePage] = useState<FeeRuleListPage | null>(null);
  const [ruleFilters, setRuleFilters] = useState<FeeRuleFilters>(EMPTY_RULE_FILTERS);
  const [ruleDetail, setRuleDetail] = useState<FeeRuleDetail | null>(null);
  const [actualPage, setActualPage] = useState<FeeActualListPage | null>(null);
  const [actualFilters, setActualFilters] = useState<FeeActualFilters>(EMPTY_ACTUAL_FILTERS);
  const [variance, setVariance] = useState<FeeVarianceReport | null>(null);
  const [estimate, setEstimate] = useState<FeeEstimate | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const [ruleForm, setRuleForm] = useState<FeeRuleInput>({
    platformCode: '',
    siteCode: null,
    internalCategoryId: null,
    platformCategoryId: null,
    feeType: 'COMMISSION',
    chargeBasis: 'PERCENTAGE_OF_SALE',
    rate: null,
    fixedAmount: null,
    currency: null,
    periodTotalAmount: null,
    periodCurrency: null,
    allocationBasis: null,
    allocationDivisor: null,
    effectiveFrom: '',
    effectiveTo: null,
    enabled: true,
    note: '',
    version: null,
  });
  const [ruleProblems, setRuleProblems] = useState<string[]>([]);
  const [editingRuleId, setEditingRuleId] = useState<string | null>(null);

  const [tierRows, setTierRows] = useState<FeeTierInput[]>([]);
  const [tierProblems, setTierProblems] = useState<string[]>([]);

  const [actualForm, setActualForm] = useState<FeeActualInput>({
    platformCode: '',
    siteCode: null,
    internalCategoryId: null,
    platformCategoryId: null,
    feeType: 'COMMISSION',
    amount: '',
    currency: 'USD',
    saleAmount: '',
    quantity: '1',
    occurredOn: '',
    sourceRef: '',
    note: '',
  });
  const [actualProblems, setActualProblems] = useState<string[]>([]);

  const [estimateForm, setEstimateForm] = useState({
    platformCode: '',
    siteCode: '',
    saleAmount: '100',
    quantity: '1',
    currency: 'USD',
  });

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [caps, nextRules, nextActuals] = await Promise.all([
        fetchPlatformFeeCapabilities(),
        fetchFeeRuleList(ruleFilters),
        fetchFeeActuals(actualFilters),
      ]);
      setCapabilities(caps);
      setRulePage(nextRules);
      setActualPage(nextActuals);
      setLoaded(true);
    } catch (e) {
      setError(e instanceof Error ? e.message : '平台费用台账读不到');
    } finally {
      setBusy(false);
    }
  }, [ruleFilters, actualFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  // 平台目录只用来选维度；读不到时不阻断页面（规则台账仍然可读）。
  useEffect(() => {
    let cancelled = false;
    fetchPlatformDirectory()
      .then((next) => {
        if (!cancelled) setDirectory(next);
      })
      .catch(() => {
        if (!cancelled) setDirectory(null);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const loadVariance = useCallback(async () => {
    if (!actualFilters.platformCode) {
      setVariance(null);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const from = actualFilters.from || defaultFrom();
      const to = actualFilters.to || defaultTo();
      setVariance(
        await fetchFeeVariance({
          platformCode: actualFilters.platformCode,
          siteCode: actualFilters.siteCode || null,
          feeType: actualFilters.feeType || null,
          from,
          to,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '费用差异读不到');
    } finally {
      setBusy(false);
    }
  }, [actualFilters]);

  useEffect(() => {
    void loadVariance();
  }, [loadVariance]);

  const openRule = useCallback(async (ruleId: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const next = await fetchFeeRule(ruleId);
      setRuleDetail(next);
      setEditingRuleId(next.rule.id);
      setTierRows(
        next.tiers.map((t) => ({
          seq: t.seq,
          minAmount: t.minAmount ?? '0',
          maxAmount: t.maxAmount ?? '',
          rate: t.rate ?? '0',
        })),
      );
      setRuleForm({
        platformCode: next.rule.platformCode,
        siteCode: next.rule.siteCode,
        internalCategoryId: next.rule.internalCategoryId,
        platformCategoryId: next.rule.platformCategoryId,
        feeType: next.rule.feeType,
        chargeBasis: next.rule.chargeBasis,
        rate: next.rule.rate,
        fixedAmount: next.rule.fixedAmount,
        currency: next.rule.currency,
        periodTotalAmount: next.rule.periodTotalAmount,
        periodCurrency: next.rule.periodCurrency,
        allocationBasis: next.rule.allocationBasis,
        allocationDivisor: next.rule.allocationDivisor,
        effectiveFrom: next.rule.effectiveFrom ?? '',
        effectiveTo: next.rule.effectiveTo,
        enabled: next.rule.enabled,
        note: next.rule.note,
        version: next.rule.version,
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : '费用规则明细读不到');
    } finally {
      setBusy(false);
    }
  }, []);

  function resetRuleForm() {
    setEditingRuleId(null);
    setRuleDetail(null);
    setTierRows([]);
    setRuleProblems([]);
    setRuleForm({
      ...EMPTY_RULE_FILTERS,
      platformCode: '',
      siteCode: null,
      internalCategoryId: null,
      platformCategoryId: null,
      feeType: 'COMMISSION',
      chargeBasis: 'PERCENTAGE_OF_SALE',
      rate: null,
      fixedAmount: null,
      currency: null,
      periodTotalAmount: null,
      periodCurrency: null,
      allocationBasis: null,
      allocationDivisor: null,
      effectiveFrom: nowLocal(),
      effectiveTo: null,
      enabled: true,
      note: '',
      version: null,
    } as FeeRuleInput);
  }

  async function submitRule() {
    const problems = validateRule(ruleForm);
    setRuleProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      const saved = editingRuleId
        ? await updateFeeRule(editingRuleId, ruleForm)
        : await createFeeRule(ruleForm);
      setNotice(
        editingRuleId
          ? `已保存规则（第 ${saved.version} 版）—— 同一作用域下生效区间重叠的规则会被拒绝。`
          : '已新建规则。',
      );
      resetRuleForm();
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '费用规则保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitTiers() {
    if (!editingRuleId) return;
    const rows = tierRows
      .filter((t) => t.minAmount !== '' || t.rate !== '')
      .map((t, index) => ({
        seq: index + 1,
        minAmount: t.minAmount,
        maxAmount: t.maxAmount === '' ? null : t.maxAmount,
        rate: t.rate,
      }));
    setTierProblems([]);
    setBusy(true);
    setError('');
    try {
      const next = await replaceFeeTiers(editingRuleId, rows);
      setRuleDetail(next);
      setTierProblems([]);
      setNotice('已整体替换阶梯 —— 要么全换要么全不换，不会留下半成品规则。');
      await load();
    } catch (e) {
      setTierProblems([e instanceof Error ? e.message : '阶梯保存失败']);
    } finally {
      setBusy(false);
    }
  }

  async function submitActual() {
    const problems = validateActual(actualForm);
    setActualProblems(problems);
    if (problems.length) return;
    setBusy(true);
    setError('');
    try {
      await recordFeeActual(actualForm);
      setNotice('已登记实际费用 —— 它与规则是两个独立的事实，差额在下面的「费用差异」里现算。');
      setActualForm({ ...actualForm, amount: '', saleAmount: '', sourceRef: '', note: '' });
      await load();
      await loadVariance();
    } catch (e) {
      setError(e instanceof Error ? e.message : '实际费用登记失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitVoid(actualId: string, reason: string) {
    const problems = validateVoidReason(reason);
    if (problems.length) {
      setError(problems[0] ?? '作废必须填理由');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await voidFeeActual(actualId, reason);
      setNotice('已作废该笔实际费用 —— 旧行仍留在库里，历史可追溯。');
      await load();
      await loadVariance();
    } catch (e) {
      setError(e instanceof Error ? e.message : '作废失败');
    } finally {
      setBusy(false);
    }
  }

  async function runEstimate() {
    if (!estimateForm.platformCode) {
      setError('测算要选平台：平台费用规则挂在平台维度上');
      return;
    }
    setBusy(true);
    setError('');
    try {
      setEstimate(
        await fetchFeeEstimate({
          platformCode: estimateForm.platformCode,
          siteCode: estimateForm.siteCode || null,
          saleAmount: estimateForm.saleAmount || '0',
          quantity: estimateForm.quantity || '0',
          currency: estimateForm.currency || 'USD',
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '费用测算失败');
    } finally {
      setBusy(false);
    }
  }

  const varianceSummary = useMemo(() => varianceHeadline(variance), [variance]);
  const deliveredSections = PLATFORM_FEE_SECTIONS.filter((s) => s.delivered);
  const pendingSections = PLATFORM_FEE_SECTIONS.filter((s) => !s.delivered);
  const siteOptions = useMemo(() => {
    const platform = (directory?.platforms ?? []).find((p) => p.code === ruleForm.platformCode);
    return platform?.marketplaces ?? [];
  }, [directory, ruleForm.platformCode]);

  return (
    <div className="space-y-4">
      <PageHeader
        title="平台费用"
        description="平台侧费用的计算规则：佣金比例或阶梯、订阅费分摊、Referral Fee、交易费、罚款类型。规则按「平台 / 站点 / 类目」维护，用于利润测算的扣项。"
      />

      {error ? (
        <Notice tone="danger" title="出错">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {/* ============================================================ 口径与能力声明 */}
      <SectionCard
        title="口径声明（必看）"
        description="蓝图加粗句：规则参数用于事前测算，实际费用来自 12 的结算对账 —— 两者差额要显性展示为「费用差异」，否则测算永远不准却不知道为什么。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <Notice tone="warning" title="两个口径，两份独立的事实">
              <div className="space-y-1">
                <p className="font-semibold">规则（事前测算口径）＝ 这笔单按规则应该扣多少</p>
                <p>实际费用（事后对账口径）＝ 平台实际扣了多少</p>
                <p>费用差异 ＝ 实际 − 按规则测算。两者永不合并成一个字段。</p>
              </div>
            </Notice>

            <div>
              <p className="text-label-13 mb-2 font-semibold">
                逐维度能力（取不到 / 未交付的写明归谁管）
              </p>
              <div className="space-y-2">
                {capabilities.dimensions.map((d) => (
                  <div key={d.code} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 font-semibold">{d.label}</span>
                      <Badge tone={d.available ? 'success' : 'warning'}>
                        {d.available ? '已交付' : '未交付'}
                      </Badge>
                      <span className="text-copy-12 text-muted-foreground">{d.ownerModule}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">{d.impact}</p>
                    {d.note ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">{d.note}</p>
                    ) : null}
                  </div>
                ))}
              </div>
            </div>

            <div className="grid gap-2 md:grid-cols-2">
              <Notice tone="info" title="规则口径">
                {capabilities.ruleBasis}
              </Notice>
              <Notice tone="info" title="实际费用口径">
                {capabilities.actualBasis}
              </Notice>
              <Notice tone="info" title="费用差异口径">
                {capabilities.varianceBasis}
              </Notice>
              <Notice tone="info" title="上游">
                {capabilities.upstreamNote}
              </Notice>
              <Notice tone="info" title="下游">
                {capabilities.downstreamNote}
              </Notice>
              <Notice tone="info" title="可做的动作">
                {capabilities.actionsNote}
              </Notice>
            </div>
          </div>
        ) : busy && !loaded ? (
          <p className="text-muted-foreground text-copy-13">正在读能力声明……</p>
        ) : (
          <p className="text-muted-foreground text-copy-13">能力声明读不到。</p>
        )}
      </SectionCard>

      {/* ============================================================ 规则台账（事前测算口径） */}
      <SectionCard
        title="费用规则（事前测算口径）"
        description="一条规则 = 一个（平台 / 站点 / 类目 × 费用类型）的费率口径，按生效区间取用。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="fee-kw" label="关键词" hint="平台码 / 站点码 / 备注">
              <Input
                id="fee-kw"
                value={ruleFilters.keyword}
                placeholder="全部"
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, keyword: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="fee-platform" label="平台">
              <Input
                id="fee-platform"
                value={ruleFilters.platformCode}
                placeholder="全部"
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, platformCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="fee-site" label="站点">
              <Input
                id="fee-site"
                value={ruleFilters.siteCode}
                placeholder="全部"
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, siteCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="fee-type" label="费用类型">
              <Input
                id="fee-type"
                value={ruleFilters.feeType}
                placeholder="全部"
                onChange={(e) =>
                  setRuleFilters({ ...ruleFilters, feeType: e.target.value, page: 0 })
                }
              />
            </FormField>
          </div>

          {busy && !rulePage ? (
            <p className="text-muted-foreground text-copy-13">正在读规则台账……</p>
          ) : rulePage && rulePage.items.length ? (
            <div className="space-y-2">
              {rulePage.items.map((rule) => (
                <div key={rule.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="brand">{rule.feeTypeLabel}</Badge>
                    <Badge tone="neutral">{rule.chargeBasisLabel}</Badge>
                    {!rule.enabled ? <Badge tone="warning">已停用</Badge> : null}
                    {rule.tierCount > 0 ? <Badge tone="info">{rule.tierCount} 档阶梯</Badge> : null}
                    <span className="text-copy-12 text-muted-foreground">
                      {ruleScopeText(rule)}
                    </span>
                  </div>
                  <p className="text-copy-13 mt-2">
                    {rule.rate ? `费率 ${rateText(rule.rate)}` : null}
                    {rule.fixedAmount ? `固定额 ${rule.fixedAmount} ${rule.currency ?? ''}` : null}
                    {rule.periodTotalAmount
                      ? `订阅费周期总额 ${rule.periodTotalAmount} ${rule.periodCurrency ?? ''} ÷ ${rule.allocationDivisor ?? '?'}（${allocationBasisLabel(rule.allocationBasis)}）`
                      : null}
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    生效 {timeText(rule.effectiveFrom)} ~{' '}
                    {rule.effectiveTo ? timeText(rule.effectiveTo) : '长期有效'}
                  </p>
                  <div className="mt-2">
                    <Button size="sm" variant="outline" onClick={() => void openRule(rule.id)}>
                      查看 / 修改
                    </Button>
                  </div>
                </div>
              ))}
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={rulePage.page <= 0}
                  onClick={() => setRuleFilters({ ...ruleFilters, page: rulePage.page - 1 })}
                >
                  上一页
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  第 {rulePage.page + 1} 页 / 共 {rulePage.total} 条
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={(rulePage.page + 1) * rulePage.size >= rulePage.total}
                  onClick={() => setRuleFilters({ ...ruleFilters, page: rulePage.page + 1 })}
                >
                  下一页
                </Button>
              </div>
            </div>
          ) : (
            <EmptyState
              title="还没有费用规则"
              description="平台费用规则挂在「平台 / 站点 / 类目」三个维度上。规则没配 ⇒ 测算时会少算扣项，而页面上看不出任何异常。"
            />
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 规则表单 */}
      <SectionCard
        title={editingRuleId ? '修改费用规则' : '新建费用规则'}
        description="同一作用域下生效区间重叠的规则会被拒绝 —— 两条规则同时生效时「用哪一条」没有答案。"
      >
        <div className="space-y-3">
          {ruleProblems.length ? (
            <Notice tone="danger" title="没通过校验">
              <ul className="list-disc pl-4">
                {ruleProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </Notice>
          ) : null}

          <div className="grid gap-3 md:grid-cols-4">
            <FormField id="rule-platform" label="平台" required hint="平台码，必填">
              <Input
                id="rule-platform"
                value={ruleForm.platformCode}
                onChange={(e) => setRuleForm({ ...ruleForm, platformCode: e.target.value })}
              />
            </FormField>
            <FormField id="rule-site" label="站点" hint="留空 = 该平台全部站点">
              <Input
                id="rule-site"
                value={ruleForm.siteCode ?? ''}
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, siteCode: e.target.value.trim() || null })
                }
              />
            </FormField>
            <FormField id="rule-fee-type" label="费用类型" required>
              <select
                id="rule-fee-type"
                className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                value={ruleForm.feeType}
                onChange={(e) => setRuleForm({ ...ruleForm, feeType: e.target.value })}
              >
                {(capabilities?.feeTypes ?? []).map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="rule-basis" label="计价形态" required>
              <select
                id="rule-basis"
                className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                value={ruleForm.chargeBasis}
                onChange={(e) => setRuleForm({ ...ruleForm, chargeBasis: e.target.value })}
              >
                {(capabilities?.chargeBases ?? []).map((b) => (
                  <option key={b.value} value={b.value}>
                    {b.label}
                  </option>
                ))}
              </select>
            </FormField>
          </div>

          <div className="grid gap-3 md:grid-cols-4">
            {ruleForm.chargeBasis === 'PERCENTAGE_OF_SALE' ? (
              <FormField id="rule-rate" label="费率（小数口径）" required hint="0.15 = 15%">
                <Input
                  id="rule-rate"
                  value={ruleForm.rate ?? ''}
                  placeholder="0.15"
                  onChange={(e) =>
                    setRuleForm({ ...ruleForm, rate: e.target.value.trim() || null })
                  }
                />
              </FormField>
            ) : null}
            {ruleForm.chargeBasis === 'FIXED_PER_ORDER' ||
            ruleForm.chargeBasis === 'FIXED_PER_UNIT' ? (
              <>
                <FormField id="rule-fixed" label="固定额" required>
                  <Input
                    id="rule-fixed"
                    value={ruleForm.fixedAmount ?? ''}
                    onChange={(e) =>
                      setRuleForm({ ...ruleForm, fixedAmount: e.target.value.trim() || null })
                    }
                  />
                </FormField>
                <FormField id="rule-currency" label="币种" required>
                  <Input
                    id="rule-currency"
                    value={ruleForm.currency ?? ''}
                    placeholder="USD"
                    onChange={(e) =>
                      setRuleForm({
                        ...ruleForm,
                        currency: e.target.value.trim().toUpperCase() || null,
                      })
                    }
                  />
                </FormField>
              </>
            ) : null}
            {ruleForm.feeType === 'SUBSCRIPTION' ? (
              <>
                <FormField id="rule-period-total" label="订阅费周期总额" required>
                  <Input
                    id="rule-period-total"
                    value={ruleForm.periodTotalAmount ?? ''}
                    onChange={(e) =>
                      setRuleForm({ ...ruleForm, periodTotalAmount: e.target.value.trim() || null })
                    }
                  />
                </FormField>
                <FormField id="rule-period-currency" label="周期总额币种" required>
                  <Input
                    id="rule-period-currency"
                    value={ruleForm.periodCurrency ?? ''}
                    placeholder="USD"
                    onChange={(e) =>
                      setRuleForm({
                        ...ruleForm,
                        periodCurrency: e.target.value.trim().toUpperCase() || null,
                      })
                    }
                  />
                </FormField>
                <FormField id="rule-allocation" label="分摊口径" required>
                  <select
                    id="rule-allocation"
                    className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                    value={ruleForm.allocationBasis ?? ''}
                    onChange={(e) =>
                      setRuleForm({
                        ...ruleForm,
                        allocationBasis: e.target.value || null,
                      })
                    }
                  >
                    <option value="">请选择</option>
                    {(capabilities?.allocationBases ?? []).map((b) => (
                      <option key={b.value} value={b.value}>
                        {b.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField
                  id="rule-divisor"
                  label="分摊基数"
                  required
                  hint="期间单量 / 件量，必须显式登记"
                >
                  <Input
                    id="rule-divisor"
                    value={ruleForm.allocationDivisor ?? ''}
                    onChange={(e) =>
                      setRuleForm({
                        ...ruleForm,
                        allocationDivisor: e.target.value.trim() || null,
                      })
                    }
                  />
                </FormField>
              </>
            ) : null}
            <FormField id="rule-from" label="生效起点" required>
              <Input
                id="rule-from"
                value={ruleForm.effectiveFrom}
                placeholder="2026-03-01T00:00:00Z"
                onChange={(e) => setRuleForm({ ...ruleForm, effectiveFrom: e.target.value })}
              />
            </FormField>
            <FormField id="rule-to" label="生效终点" hint="留空 = 长期有效">
              <Input
                id="rule-to"
                value={ruleForm.effectiveTo ?? ''}
                onChange={(e) =>
                  setRuleForm({ ...ruleForm, effectiveTo: e.target.value.trim() || null })
                }
              />
            </FormField>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <Button onClick={() => void submitRule()} disabled={busy}>
              {editingRuleId ? '保存修改' : '新建规则'}
            </Button>
            {editingRuleId ? (
              <Button variant="outline" onClick={resetRuleForm}>
                取消修改
              </Button>
            ) : null}
          </div>

          {ruleDetail ? (
            <div className="border-border space-y-2 rounded-md border p-3">
              <p className="text-label-13 font-semibold">当前规则的作用域</p>
              <p className="text-copy-13">{ruleDetail.scopeBasis}</p>
              <p className="text-copy-12 text-muted-foreground">{ruleDetail.note}</p>
            </div>
          ) : null}
        </div>
      </SectionCard>

      {/* ============================================================ 阶梯 */}
      {editingRuleId ? (
        <SectionCard
          title="阶梯（整体替换）"
          description="阶梯必须首尾相接：下界含、上界开，最后一档无上界。销售额低于最低档下界时该规则不适用（不是按 0 扣）。"
        >
          <div className="space-y-3">
            {tierProblems.length ? (
              <Notice tone="danger" title="阶梯没通过校验">
                <ul className="list-disc pl-4">
                  {tierProblems.map((p) => (
                    <li key={p}>{p}</li>
                  ))}
                </ul>
              </Notice>
            ) : null}
            {tierRows.map((tier, index) => (
              <div key={index} className="grid gap-3 md:grid-cols-4">
                <FormField id={`tier-min-${index}`} label={`第 ${index + 1} 档 下界（含）`}>
                  <Input
                    id={`tier-min-${index}`}
                    value={tier.minAmount}
                    onChange={(e) => {
                      const next = [...tierRows];
                      next[index] = { ...tier, minAmount: e.target.value };
                      setTierRows(next);
                    }}
                  />
                </FormField>
                <FormField
                  id={`tier-max-${index}`}
                  label={`第 ${index + 1} 档 上界（不含，留空 = 无上限）`}
                >
                  <Input
                    id={`tier-max-${index}`}
                    value={tier.maxAmount ?? ''}
                    onChange={(e) => {
                      const next = [...tierRows];
                      next[index] = { ...tier, maxAmount: e.target.value.trim() || null };
                      setTierRows(next);
                    }}
                  />
                </FormField>
                <FormField id={`tier-rate-${index}`} label={`第 ${index + 1} 档 费率（小数）`}>
                  <Input
                    id={`tier-rate-${index}`}
                    value={tier.rate}
                    onChange={(e) => {
                      const next = [...tierRows];
                      next[index] = { ...tier, rate: e.target.value };
                      setTierRows(next);
                    }}
                  />
                </FormField>
              </div>
            ))}
            <div className="flex flex-wrap items-center gap-2">
              <Button
                variant="outline"
                onClick={() =>
                  setTierRows([
                    ...tierRows,
                    { seq: tierRows.length + 1, minAmount: '0', maxAmount: null, rate: '0' },
                  ])
                }
              >
                加一档
              </Button>
              <Button
                variant="outline"
                onClick={() => setTierRows(tierRows.slice(0, Math.max(0, tierRows.length - 1)))}
              >
                删最后一档
              </Button>
              <Button onClick={() => void submitTiers()} disabled={busy}>
                整体替换阶梯
              </Button>
            </div>
          </div>
        </SectionCard>
      ) : null}

      {/* ============================================================ 测算 */}
      <SectionCard
        title="费用测算（事前口径）"
        description="输入一次销售，产出逐项费用 —— 这是 5.3.1.1 利润测算器要消费的费用项。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-5">
            <FormField id="est-platform" label="平台" required>
              <Input
                id="est-platform"
                value={estimateForm.platformCode}
                onChange={(e) => setEstimateForm({ ...estimateForm, platformCode: e.target.value })}
              />
            </FormField>
            <FormField id="est-site" label="站点" hint="留空 = 平台级">
              <Input
                id="est-site"
                value={estimateForm.siteCode}
                onChange={(e) => setEstimateForm({ ...estimateForm, siteCode: e.target.value })}
              />
            </FormField>
            <FormField id="est-amount" label="销售额" required>
              <Input
                id="est-amount"
                value={estimateForm.saleAmount}
                onChange={(e) => setEstimateForm({ ...estimateForm, saleAmount: e.target.value })}
              />
            </FormField>
            <FormField id="est-quantity" label="数量" required>
              <Input
                id="est-quantity"
                value={estimateForm.quantity}
                onChange={(e) => setEstimateForm({ ...estimateForm, quantity: e.target.value })}
              />
            </FormField>
            <FormField id="est-currency" label="币种" required>
              <Input
                id="est-currency"
                value={estimateForm.currency}
                onChange={(e) => setEstimateForm({ ...estimateForm, currency: e.target.value })}
              />
            </FormField>
          </div>
          <Button onClick={() => void runEstimate()} disabled={busy}>
            测算费用项
          </Button>

          {estimate ? (
            <div className="space-y-2">
              <Notice tone={estimate.complete ? 'success' : 'warning'} title="测算结果">
                {estimate.note}
              </Notice>
              <div className="grid gap-2 md:grid-cols-2">
                {estimate.items.map((item) => (
                  <div key={item.feeType} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-label-13 font-semibold">{item.feeTypeLabel}</span>
                      {item.chargeBasisLabel ? (
                        <Badge tone="neutral">{item.chargeBasisLabel}</Badge>
                      ) : null}
                      <Badge tone={item.applicable ? 'success' : 'warning'}>
                        {item.applicable ? '已测算' : '算不出来'}
                      </Badge>
                    </div>
                    <p className="text-copy-13 mt-2 font-semibold">
                      {moneyWithCurrency(item.amount, item.currency)}
                    </p>
                    <p className="text-copy-12 text-muted-foreground mt-1">{item.basis}</p>
                    {item.note ? (
                      <p className="text-copy-12 text-muted-foreground mt-1">{item.note}</p>
                    ) : null}
                  </div>
                ))}
              </div>
              <StatCard
                label="费用合计"
                value={moneyWithCurrency(estimate.totalAmount, estimate.currency)}
                comparisonLabel={
                  estimate.complete ? '全部费用项都算得出来' : '合计不完整，别直接拿去算利润'
                }
              />
            </div>
          ) : (
            <p className="text-muted-foreground text-copy-13">还没有测算结果。</p>
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 实际费用（事后对账口径） */}
      <SectionCard
        title="实际费用（事后对账口径）"
        description="独立于规则的另一个事实：平台实际扣了多少。🔴 12 财务的结算对账尚未交付，当前唯一来源是人工登记的平台账单观察值。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 md:grid-cols-5">
            <FormField id="act-platform" label="平台" required>
              <Input
                id="act-platform"
                value={actualForm.platformCode}
                onChange={(e) => setActualForm({ ...actualForm, platformCode: e.target.value })}
              />
            </FormField>
            <FormField id="act-site" label="站点" hint="留空 = 平台级">
              <Input
                id="act-site"
                value={actualForm.siteCode ?? ''}
                onChange={(e) =>
                  setActualForm({ ...actualForm, siteCode: e.target.value.trim() || null })
                }
              />
            </FormField>
            <FormField id="act-type" label="费用类型" required>
              <select
                id="act-type"
                className="border-border text-copy-13 h-9 w-full rounded-md border px-2"
                value={actualForm.feeType}
                onChange={(e) => setActualForm({ ...actualForm, feeType: e.target.value })}
              >
                {(capabilities?.feeTypes ?? []).map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </FormField>
            <FormField id="act-amount" label="实际发生额" required hint="可负 = 冲回">
              <Input
                id="act-amount"
                value={actualForm.amount}
                onChange={(e) => setActualForm({ ...actualForm, amount: e.target.value })}
              />
            </FormField>
            <FormField id="act-currency" label="币种" required>
              <Input
                id="act-currency"
                value={actualForm.currency}
                onChange={(e) =>
                  setActualForm({ ...actualForm, currency: e.target.value.trim().toUpperCase() })
                }
              />
            </FormField>
            <FormField id="act-sale" label="销售额基数" required hint="差异计算要用">
              <Input
                id="act-sale"
                value={actualForm.saleAmount}
                onChange={(e) => setActualForm({ ...actualForm, saleAmount: e.target.value })}
              />
            </FormField>
            <FormField id="act-quantity" label="数量基数" required hint="差异计算要用">
              <Input
                id="act-quantity"
                value={actualForm.quantity}
                onChange={(e) => setActualForm({ ...actualForm, quantity: e.target.value })}
              />
            </FormField>
            <FormField id="act-date" label="费用发生日" required>
              <Input
                id="act-date"
                value={actualForm.occurredOn}
                placeholder="2026-03-01"
                onChange={(e) => setActualForm({ ...actualForm, occurredOn: e.target.value })}
              />
            </FormField>
            <FormField id="act-ref" label="平台账单号" hint="只作追溯用">
              <Input
                id="act-ref"
                value={actualForm.sourceRef}
                onChange={(e) => setActualForm({ ...actualForm, sourceRef: e.target.value })}
              />
            </FormField>
          </div>

          {actualProblems.length ? (
            <Notice tone="danger" title="没通过校验">
              <ul className="list-disc pl-4">
                {actualProblems.map((p) => (
                  <li key={p}>{p}</li>
                ))}
              </ul>
            </Notice>
          ) : null}

          <Button onClick={() => void submitActual()} disabled={busy}>
            登记实际费用
          </Button>

          <div className="grid gap-3 md:grid-cols-5">
            <FormField id="act-filter-platform" label="按平台筛">
              <Input
                id="act-filter-platform"
                value={actualFilters.platformCode}
                onChange={(e) =>
                  setActualFilters({ ...actualFilters, platformCode: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="act-filter-from" label="区间起">
              <Input
                id="act-filter-from"
                value={actualFilters.from}
                placeholder={defaultFrom()}
                onChange={(e) =>
                  setActualFilters({ ...actualFilters, from: e.target.value, page: 0 })
                }
              />
            </FormField>
            <FormField id="act-filter-to" label="区间止">
              <Input
                id="act-filter-to"
                value={actualFilters.to}
                placeholder={defaultTo()}
                onChange={(e) =>
                  setActualFilters({ ...actualFilters, to: e.target.value, page: 0 })
                }
              />
            </FormField>
          </div>

          {busy && !actualPage ? (
            <p className="text-muted-foreground text-copy-13">正在读实际费用台账……</p>
          ) : actualPage && actualPage.items.length ? (
            <div className="space-y-2">
              {actualPage.items.map((row) => (
                <div key={row.id} className="border-border rounded-md border p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <Badge tone="brand">{row.feeTypeLabel}</Badge>
                    <Badge tone="neutral">{actualSourceLabel(row.source)}</Badge>
                    {row.isVoid ? <Badge tone="warning">已作废</Badge> : null}
                    <span className="text-copy-12 text-muted-foreground">
                      {row.platformCode}
                      {row.siteCode ? ` / ${row.siteCode}` : ''} · {row.occurredOn}
                    </span>
                  </div>
                  <p className="text-copy-13 mt-2 font-semibold">
                    {moneyWithCurrency(row.amount, row.currency)}
                    <span className="text-muted-foreground text-copy-12 ml-2">
                      销售额基数 {moneyText(row.saleAmount)} · 数量 {moneyText(row.quantity)}
                    </span>
                  </p>
                  {row.sourceRef ? (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      账单号 {row.sourceRef}
                    </p>
                  ) : null}
                  {row.isVoid ? (
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      作废理由：{row.voidReason}
                    </p>
                  ) : (
                    <VoidActualControl onVoid={(reason) => void submitVoid(row.id, reason)} />
                  )}
                </div>
              ))}
              <div className="flex items-center gap-2">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={actualPage.page <= 0}
                  onClick={() => setActualFilters({ ...actualFilters, page: actualPage.page - 1 })}
                >
                  上一页
                </Button>
                <span className="text-copy-12 text-muted-foreground">
                  第 {actualPage.page + 1} 页 / 共 {actualPage.total} 条
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={(actualPage.page + 1) * actualPage.size >= actualPage.total}
                  onClick={() => setActualFilters({ ...actualFilters, page: actualPage.page + 1 })}
                >
                  下一页
                </Button>
              </div>
            </div>
          ) : (
            <EmptyState
              title="还没有实际费用"
              description="没有实际费用时，下面的「费用差异」会显示「无法计算差异」—— 而不是「差异 0.00」。"
            />
          )}
        </div>
      </SectionCard>

      {/* ============================================================ 费用差异 */}
      <SectionCard
        title="费用差异（规则测算 vs 实际）"
        description="蓝图加粗句的落点：差额必须能算出来并被展示。按（费用类型 × 币种）分组，跨币种绝不相加。"
      >
        <div className="space-y-3">
          <Notice tone={varianceSummary.tone} title="差异结论">
            {varianceSummary.text}
          </Notice>

          {variance && variance.available && variance.groups.length ? (
            <div className="space-y-2">
              {variance.groups.map((group) => (
                <div
                  key={`${group.feeType}-${group.currency}`}
                  className="border-border rounded-md border p-3"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-label-13 font-semibold">{group.feeTypeLabel}</span>
                    <Badge tone={varianceTone(group)}>{varianceText(group)}</Badge>
                    <span className="text-copy-12 text-muted-foreground">{group.currency}</span>
                  </div>
                  <div className="mt-2 grid gap-2 md:grid-cols-3">
                    <StatCard
                      label="实际费用（事后对账口径）"
                      value={moneyWithCurrency(group.actualAmount, group.currency)}
                      comparisonLabel={`${group.actualCount} 笔`}
                    />
                    <StatCard
                      label="按规则测算（事前测算口径）"
                      value={moneyWithCurrency(group.estimatedAmount, group.currency)}
                      comparisonLabel={`${group.comparableCount} 笔可对照`}
                    />
                    <StatCard
                      label="费用差异"
                      value={moneyWithCurrency(group.varianceAmount, group.currency)}
                      comparisonLabel={group.note}
                    />
                  </div>
                </div>
              ))}
            </div>
          ) : variance && variance.available ? (
            <p className="text-muted-foreground text-copy-13">这个区间没有可比的费用类型。</p>
          ) : (
            <p className="text-muted-foreground text-copy-13">
              先在上面的实际费用台账里登记一笔，再回来看差额。
            </p>
          )}
          <p className="text-copy-12 text-muted-foreground">{variance?.note ?? ''}</p>
        </div>
      </SectionCard>

      {/* 5.2.3.2 物流与头程费用：与本模块共用 L3 路由，各占一个独立分区 */}
      <LogisticsFreightWorkspace />

      {/* ============================================================ 分区 */}
      <SectionCard title="同一 L3 下的兄弟模块">
        <div className="space-y-2">
          {deliveredSections.map((s) => (
            <div key={s.id} className="border-border rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-label-13 font-semibold">
                  {s.moduleId} {s.title}
                </span>
                <Badge tone="success">已交付</Badge>
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">{s.summary}</p>
            </div>
          ))}
          {pendingSections.map((s) => (
            <div key={s.id} className="border-border rounded-md border p-3">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-label-13 font-semibold">
                  {s.moduleId} {s.title}
                </span>
                <Badge tone="warning">未交付</Badge>
              </div>
              <p className="text-copy-12 text-muted-foreground mt-1">{s.summary}</p>
            </div>
          ))}
        </div>
      </SectionCard>
    </div>
  );
}

/** 作废一条实际费用的小控件（理由必填）。 */
function VoidActualControl({ onVoid }: { onVoid: (reason: string) => void }) {
  const [reason, setReason] = useState('');
  const [open, setOpen] = useState(false);
  if (!open) {
    return (
      <div className="mt-2">
        <Button size="sm" variant="outline" onClick={() => setOpen(true)}>
          作废（要写理由）
        </Button>
      </div>
    );
  }
  return (
    <div className="mt-2 space-y-2">
      <FormField id={`void-reason-${reason.length}`} label="作废理由" required>
        <Input
          id={`void-reason-${reason.length}`}
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="录错了，重复登记"
        />
      </FormField>
      <div className="flex items-center gap-2">
        <Button
          size="sm"
          onClick={() => {
            onVoid(reason);
            setOpen(false);
            setReason('');
          }}
        >
          确认作废
        </Button>
        <Button size="sm" variant="outline" onClick={() => setOpen(false)}>
          取消
        </Button>
      </div>
    </div>
  );
}

function nowLocal(): string {
  return new Date().toISOString();
}

function defaultFrom(): string {
  const date = new Date();
  date.setDate(date.getDate() - 30);
  return date.toISOString().slice(0, 10);
}

function defaultTo(): string {
  return new Date().toISOString().slice(0, 10);
}
