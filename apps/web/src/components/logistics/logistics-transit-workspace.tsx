'use client';

import { useCallback, useEffect, useState } from 'react';
import { Clock, PackageSearch, Ruler, ShieldAlert } from 'lucide-react';
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
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';
import type {
  LimitPrecheckInput,
  LimitPrecheckResult,
  LogisticsTransitCapabilities,
  LogisticsTransitOverview,
  MarkingRequirementPage,
  SaveMarkingRequirementInput,
  SaveSizeLimitInput,
  SaveTransitRuleInput,
  SizeLimitPage,
  TransitEstimateInput,
  TransitEstimateResult,
  TransitRulePage,
} from './logistics-transit-model';
import {
  EMPTY_ESTIMATE,
  EMPTY_MARKING_FORM,
  EMPTY_PRECHECK,
  EMPTY_RULE_FORM,
  EMPTY_SIZE_FORM,
  EMPTY_TRANSIT_FILTERS,
  bandText,
  checkLimits,
  estimateTransit,
  fetchMarkings,
  fetchSizeLimits,
  fetchTransitCapabilities,
  fetchTransitOverview,
  fetchTransitRules,
  flagKindLabel,
  hitKindLabel,
  optionalNumber,
  parseList,
  removeMarking,
  removeSizeLimit,
  removeTransitRule,
  requirementModeLabel,
  ruleReadinessMeta,
  saveMarkingRequirement,
  saveSizeLimit,
  saveTransitRule,
  serviceLevelLabel,
  severityMeta,
  toMarkingForm,
  toRuleForm,
  toSizeForm,
  transitDaysText,
  transitStatusLabel,
  validateMarkingForm,
  validateRuleForm,
  validateSizeForm,
  verdictMeta,
} from './logistics-transit-model';

/**
 * 10.1.1.3 时效与限制。
 *
 * <p>蓝图：「维护渠道时效（分国家分重量段）与限制（尺寸上限、重量上限、禁限品类、
 * 电池/液体/危险品标记要求），并在选品与刊登阶段提示」，输出「时效估算；限制命中提示」。
 *
 * <p>🔴 这一页最重要的设计决定在最后两个面板：**判定是服务端做的**。
 * 页面把 SKU 安全属性（品类 / 带电 / 带液 / 带磁 / 危险品）与包裹尺寸重量发给
 * <code>POST /channel/logistics-transit-limits/precheck</code>，拿回来的才是
 * PASS / REVIEW / BLOCK 与逐条理由；时效估算同理走 <code>POST .../estimate</code>。
 *
 * <p>本页**刻意没有**任何一处拿 sizeLimits / markings 数组自己比对出「可以发 / 不能发」，
 * 也没有自己按重量段算预计到达 —— 那两处的规则会散落到每个调用方，而总有一个调用方
 * 会漏掉「危险品」这一条，或把半开区间算成闭区间。
 *
 * <p>另一条边界：渠道身份（代码 / 名称 / 承运商 / 计费）是 10.1.1.2 的只读快照，
 * 这一页只在**新建时**选一个渠道，建完不可改。
 */
export function LogisticsTransitWorkspace() {
  const [capabilities, setCapabilities] = useState<LogisticsTransitCapabilities | null>(null);
  const [overview, setOverview] = useState<LogisticsTransitOverview | null>(null);
  const [rules, setRules] = useState<TransitRulePage | null>(null);
  const [limits, setLimits] = useState<SizeLimitPage | null>(null);
  const [markings, setMarkings] = useState<MarkingRequirementPage | null>(null);
  const [filters, setFilters] = useState(EMPTY_TRANSIT_FILTERS);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);

  const [ruleForm, setRuleForm] = useState<SaveTransitRuleInput>(EMPTY_RULE_FORM);
  const [ruleEditing, setRuleEditing] = useState(false);
  const [sizeForm, setSizeForm] = useState<SaveSizeLimitInput>(EMPTY_SIZE_FORM);
  const [sizeEditing, setSizeEditing] = useState(false);
  const [markingForm, setMarkingForm] = useState<SaveMarkingRequirementInput>(EMPTY_MARKING_FORM);
  const [markingEditing, setMarkingEditing] = useState(false);

  const [probe, setProbe] = useState<LimitPrecheckInput>(EMPTY_PRECHECK);
  const [probeCategories, setProbeCategories] = useState('HOME_GOODS');
  const [probeResult, setProbeResult] = useState<LimitPrecheckResult | null>(null);
  const [probeError, setProbeError] = useState('');

  const [estimate, setEstimate] = useState<TransitEstimateInput>(EMPTY_ESTIMATE);
  const [estimateResult, setEstimateResult] = useState<TransitEstimateResult | null>(null);
  const [estimateError, setEstimateError] = useState('');

  const load = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const [r, l, m, caps, ov] = await Promise.all([
        fetchTransitRules(filters),
        fetchSizeLimits(filters),
        fetchMarkings(filters),
        fetchTransitCapabilities(),
        fetchTransitOverview(),
      ]);
      setRules(r);
      setLimits(l);
      setMarkings(m);
      setCapabilities(caps);
      setOverview(ov);
    } catch (e) {
      setError(e instanceof Error ? e.message : '时效与限制读不到');
    } finally {
      setBusy(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  async function submitRule(next: SaveTransitRuleInput) {
    const problems = validateRuleForm(next);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await saveTransitRule(next);
      setRuleForm(EMPTY_RULE_FORM);
      setRuleEditing(false);
      setNotice(`已保存 ${next.countryCode} 的时效承诺`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitSizeLimit(next: SaveSizeLimitInput) {
    const problems = validateSizeForm(next);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await saveSizeLimit(next);
      setSizeForm(EMPTY_SIZE_FORM);
      setSizeEditing(false);
      setNotice('已保存尺寸 / 重量上限');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function submitMarking(next: SaveMarkingRequirementInput) {
    const problems = validateMarkingForm(next);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await saveMarkingRequirement(next);
      setMarkingForm(EMPTY_MARKING_FORM);
      setMarkingEditing(false);
      setNotice('已保存标记要求');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function runPrecheck() {
    setProbeError('');
    try {
      setProbeResult(
        await checkLimits({
          ...probe,
          categoryCodes: parseList(probeCategories),
        }),
      );
    } catch (e) {
      setProbeResult(null);
      setProbeError(e instanceof Error ? e.message : '限制预检失败');
    }
  }

  async function runEstimate() {
    setEstimateError('');
    try {
      setEstimateResult(await estimateTransit(estimate));
    } catch (e) {
      setEstimateResult(null);
      setEstimateError(e instanceof Error ? e.message : '时效估算失败');
    }
  }

  const ruleProblems = validateRuleForm(ruleForm);
  const sizeProblems = validateSizeForm(sizeForm);
  const markingProblems = validateMarkingForm(markingForm);

  return (
    <div className="space-y-4">
      <PageHeader
        title="时效与限制"
        description="维护渠道时效（分国家分重量段）与限制（尺寸上限、重量上限、电池/液体/危险品标记要求），并在选品与刊登阶段提示。"
      />

      {error && (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      )}
      {notice && !error && (
        <Notice tone="success" title="已保存">
          {notice}
        </Notice>
      )}
      {busy && !rules && <p className="text-muted-foreground text-sm">正在加载时效与限制…</p>}
      {error && !busy && (
        <Button size="sm" variant="outline" onClick={() => void load()}>
          重试
        </Button>
      )}

      {/* 🔴 这一句是这一页的设计前提，不是提示语 */}
      <Notice tone="info" title="限制命中由服务端判定">
        {capabilities?.limitationNote ??
          '页面只提交属性、不自己比对：限制命中走 POST precheck，时效估算走 POST estimate。'}
      </Notice>

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="时效规则" value={overview?.transitSummary.total ?? 0} />
        <StatCard label="当前生效" value={overview?.transitSummary.effectiveNow ?? 0} />
        <StatCard label="覆盖国家" value={overview?.transitSummary.countries ?? 0} />
        <StatCard label="尺寸 / 重量上限" value={overview?.sizeLimitTotal ?? 0} />
      </div>

      <Tabs defaultValue="transit">
        <TabsList>
          <TabsTrigger value="transit">时效规则</TabsTrigger>
          <TabsTrigger value="limits">尺寸 / 重量上限</TabsTrigger>
          <TabsTrigger value="markings">标记要求</TabsTrigger>
          <TabsTrigger value="precheck">限制预检</TabsTrigger>
          <TabsTrigger value="estimate">时效估算</TabsTrigger>
        </TabsList>

        {/* ============================ 时效规则 ============================ */}
        <TabsContent value="transit">
          <SectionCard title="筛选" className="mt-4">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="trKeyword" label="关键词">
                <Input
                  id="trKeyword"
                  value={filters.keyword}
                  placeholder="渠道代码 / 名称 / 国家"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="trCountry" label="国家">
                <Input
                  id="trCountry"
                  value={filters.countryCode}
                  placeholder="DE / US"
                  onChange={(e) =>
                    setFilters({ ...filters, countryCode: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="trLevel" label="服务等级">
                <Input
                  id="trLevel"
                  value={filters.serviceLevel}
                  placeholder="ECONOMY / STANDARD / EXPRESS"
                  onChange={(e) =>
                    setFilters({ ...filters, serviceLevel: e.target.value, page: 0 })
                  }
                />
              </FormField>
              <FormField id="trReadiness" label="可用性">
                <Input
                  id="trReadiness"
                  value={filters.readiness}
                  placeholder="READY / NO_TRANSIT / DISABLED"
                  onChange={(e) => setFilters({ ...filters, readiness: e.target.value, page: 0 })}
                />
              </FormField>
            </div>
            <p className="text-muted-foreground mt-2 text-xs">
              统计覆盖当前筛选结果且不受分页影响。按渠道过滤用渠道 ID（每行的渠道名已显示在卡片上）。
            </p>
          </SectionCard>

          <SectionCard title={`时效规则（${rules?.total ?? 0}）`} className="mt-4">
            {rules && rules.items.length > 0 ? (
              <div className="space-y-3">
                {rules.items.map((row) => (
                  <RuleCard
                    key={row.id}
                    row={row}
                    busy={busy}
                    onEdit={() => {
                      setRuleEditing(true);
                      setRuleForm(toRuleForm(row));
                      setError('');
                    }}
                    onDelete={() =>
                      void removeTransitRule(row.id)
                        .then(() => {
                          setNotice('已撤下该时效承诺');
                          return load();
                        })
                        .catch((e: unknown) =>
                          setError(e instanceof Error ? e.message : '撤下失败'),
                        )
                    }
                  />
                ))}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={rules.page <= 0 || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {rules.page + 1} / {Math.max(rules.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={rules.page + 1 >= rules.totalPages || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<Clock className="h-6 w-6" />}
                title="还没有时效承诺"
                description="渠道时效要按国家与重量段登记：没有它，下游测算算不出送达天数，刊登阶段也无法承诺时效。"
                action={
                  <Button
                    size="sm"
                    onClick={() => {
                      setRuleEditing(true);
                      setRuleForm(EMPTY_RULE_FORM);
                    }}
                  >
                    登记第一条
                  </Button>
                }
              />
            )}
          </SectionCard>

          <SectionCard title="登记 / 修改时效承诺" className="mt-4">
            <RuleForm
              value={ruleForm}
              editing={ruleEditing}
              problems={ruleProblems}
              busy={busy}
              onChange={setRuleForm}
              onSubmit={() => void submitRule(ruleForm)}
              onCancel={() => {
                setRuleEditing(false);
                setRuleForm(EMPTY_RULE_FORM);
              }}
            />
          </SectionCard>
        </TabsContent>

        {/* ==================== 尺寸 / 重量上限 ==================== */}
        <TabsContent value="limits">
          <SectionCard title={`尺寸 / 重量上限（${limits?.total ?? 0}）`} className="mt-4">
            {limits && limits.items.length > 0 ? (
              <div className="space-y-3">
                {limits.items.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-13 font-medium">
                        {row.channelCode} · {row.scopeLabel}
                      </span>
                      <Badge tone={row.countryCode ? 'info' : 'neutral'}>
                        {row.countryCode ? `国家 ${row.countryCode}` : '全部国家'}
                      </Badge>
                      <Button
                        size="sm"
                        variant="outline"
                        className="ml-auto"
                        disabled={busy}
                        onClick={() => {
                          setSizeEditing(true);
                          setSizeForm(toSizeForm(row));
                          setError('');
                        }}
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() =>
                          void removeSizeLimit(row.id)
                            .then(() => {
                              setNotice('已撤下该上限');
                              return load();
                            })
                            .catch((e: unknown) =>
                              setError(e instanceof Error ? e.message : '撤下失败'),
                            )
                        }
                      >
                        删除
                      </Button>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {sizeText(row.maxLengthCm, row.maxWidthCm, row.maxHeightCm, row.maxGirthCm, row.maxWeightKg)}
                    </p>
                  </div>
                ))}
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={limits.page <= 0 || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
                  >
                    上一页
                  </Button>
                  <span className="text-muted-foreground text-xs">
                    第 {limits.page + 1} / {Math.max(limits.totalPages, 1)} 页
                  </span>
                  <Button
                    size="sm"
                    variant="ghost"
                    disabled={limits.page + 1 >= limits.totalPages || busy}
                    onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            ) : (
              <EmptyState
                icon={<Ruler className="h-6 w-6" />}
                title="还没有尺寸 / 重量上限"
                description="上限是「超过就拒收」的硬边界：缺了它，超限件只能等承运商拒收通知才发现，而那时已经产生了额外费用。"
                action={
                  <Button
                    size="sm"
                    onClick={() => {
                      setSizeEditing(true);
                      setSizeForm(EMPTY_SIZE_FORM);
                    }}
                  >
                    登记第一条
                  </Button>
                }
              />
            )}
          </SectionCard>

          <SectionCard title="登记 / 修改上限" className="mt-4">
            <SizeForm
              value={sizeForm}
              editing={sizeEditing}
              problems={sizeProblems}
              busy={busy}
              onChange={setSizeForm}
              onSubmit={() => void submitSizeLimit(sizeForm)}
              onCancel={() => {
                setSizeEditing(false);
                setSizeForm(EMPTY_SIZE_FORM);
              }}
            />
          </SectionCard>
        </TabsContent>

        {/* ====================== 标记要求 ====================== */}
        <TabsContent value="markings">
          <SectionCard title={`标记要求（${markings?.total ?? 0}）`} className="mt-4">
            {markings && markings.items.length > 0 ? (
              <div className="space-y-3">
                {markings.items.map((row) => (
                  <div key={row.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-13 font-medium">
                        {row.channelCode} · {flagKindLabel(row.flagKind)}
                      </span>
                      <Badge tone={row.requirementMode === 'BLOCK' ? 'danger' : 'info'}>
                        {requirementModeLabel(row.requirementMode)}
                      </Badge>
                      {!row.consistentWithUpstream && (
                        <Badge tone="danger">与上游判定不一致</Badge>
                      )}
                      <Button
                        size="sm"
                        variant="outline"
                        className="ml-auto"
                        disabled={busy}
                        onClick={() => {
                          setMarkingEditing(true);
                          setMarkingForm(toMarkingForm(row));
                          setError('');
                        }}
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        disabled={busy}
                        onClick={() =>
                          void removeMarking(row.id)
                            .then(() => {
                              setNotice('已删除该标记要求');
                              return load();
                            })
                            .catch((e: unknown) =>
                              setError(e instanceof Error ? e.message : '删除失败'),
                            )
                        }
                      >
                        删除
                      </Button>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {row.requirementMode === 'LABEL_REQUIRED'
                        ? `必须贴「${row.markingCode}」标识`
                        : requirementModeLabel(row.requirementMode)}
                      {row.upstreamRestrictionMode
                        ? ` · 上游 10.1.1.2 判定：${row.upstreamRestrictionMode}`
                        : ' · 上游未登记该项限制'}
                    </p>
                  </div>
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<ShieldAlert className="h-6 w-6" />}
                title="还没有标记要求"
                description="禁限品类与电池/液体/磁力的拦截在上游 10.1.1.2；这一层登记的是「可以走但必须贴什么标识」以及危险品要求。"
                action={
                  <Button
                    size="sm"
                    onClick={() => {
                      setMarkingEditing(true);
                      setMarkingForm(EMPTY_MARKING_FORM);
                    }}
                  >
                    登记第一条
                  </Button>
                }
              />
            )}
          </SectionCard>

          <SectionCard title="登记 / 修改标记要求" className="mt-4">
            <MarkingForm
              value={markingForm}
              editing={markingEditing}
              problems={markingProblems}
              busy={busy}
              onChange={setMarkingForm}
              onSubmit={() => void submitMarking(markingForm)}
              onCancel={() => {
                setMarkingEditing(false);
                setMarkingForm(EMPTY_MARKING_FORM);
              }}
            />
          </SectionCard>
        </TabsContent>

        {/* ====================== 🔴 限制预检 ====================== */}
        <TabsContent value="precheck">
          <SectionCard title="刊登前限制预检（服务端判定）" className="mt-4">
            <p className="text-muted-foreground mb-3 text-xs">
              把 SKU 安全属性与包裹尺寸重量发给服务端，拿回 PASS / REVIEW / BLOCK 与逐条理由。
              这里填的是<b>属性</b>，不是结论 —— 页面不自己比对。
            </p>
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="pcCountry" label="目的国">
                <Input
                  id="pcCountry"
                  value={probe.countryCode}
                  placeholder="DE"
                  onChange={(e) => setProbe({ ...probe, countryCode: e.target.value })}
                />
              </FormField>
              <FormField id="pcCategories" label="品类编码（逗号分隔）">
                <Input
                  id="pcCategories"
                  value={probeCategories}
                  placeholder="HOME_GOODS, COSMETICS"
                  onChange={(e) => setProbeCategories(e.target.value)}
                />
              </FormField>
              <FormField id="pcWeight" label="重量 kg">
                <Input
                  id="pcWeight"
                  value={probe.weightKg === null ? '' : String(probe.weightKg)}
                  onChange={(e) =>
                    setProbe({ ...probe, weightKg: optionalNumber(e.target.value) })
                  }
                />
              </FormField>
              <FormField id="pcLength" label="长 cm">
                <Input
                  id="pcLength"
                  value={probe.lengthCm === null ? '' : String(probe.lengthCm)}
                  onChange={(e) => setProbe({ ...probe, lengthCm: optionalNumber(e.target.value) })}
                />
              </FormField>
              <FormField id="pcWidth" label="宽 cm">
                <Input
                  id="pcWidth"
                  value={probe.widthCm === null ? '' : String(probe.widthCm)}
                  onChange={(e) => setProbe({ ...probe, widthCm: optionalNumber(e.target.value) })}
                />
              </FormField>
              <FormField id="pcHeight" label="高 cm">
                <Input
                  id="pcHeight"
                  value={probe.heightCm === null ? '' : String(probe.heightCm)}
                  onChange={(e) => setProbe({ ...probe, heightCm: optionalNumber(e.target.value) })}
                />
              </FormField>
            </div>
            <div className="mt-3 flex flex-wrap gap-4">
              {(
                [
                  ['containsBattery', '带电池'],
                  ['containsLiquid', '带液体'],
                  ['containsMagnet', '带磁力'],
                  ['containsDangerousGoods', '含危险品'],
                ] as const
              ).map(([key, label]) => (
                <label key={key} className="flex items-center gap-2 text-copy-13">
                  <input
                    type="checkbox"
                    checked={probe[key]}
                    onChange={(e) => setProbe({ ...probe, [key]: e.target.checked })}
                  />
                  {label}
                </label>
              ))}
            </div>
            <Button size="sm" className="mt-4" onClick={() => void runPrecheck()}>
              跑一次预检
            </Button>
            {probeError && (
              <Notice tone="danger" title="预检失败">
                {probeError}
              </Notice>
            )}
          </SectionCard>

          <SectionCard title="判定结果" className="mt-4">
            {probeResult === null ? (
              <p className="text-muted-foreground text-sm">
                还没有结果。上面点「跑一次预检」，结论由服务端给出。
              </p>
            ) : probeResult.verdicts.length === 0 ? (
              <EmptyState
                icon={<PackageSearch className="h-6 w-6" />}
                title="没有候选渠道"
                description="这些条件下没有匹配的渠道 —— 这不代表「可以随便选一条」，它代表「没有候选渠道」。"
              />
            ) : (
              <div className="space-y-3">
                <div className="text-muted-foreground flex gap-4 text-xs">
                  <span>可承运 {probeResult.pass}</span>
                  <span>需人工确认 {probeResult.review}</span>
                  <span>硬拦截 {probeResult.block}</span>
                </div>
                {probeResult.verdicts.map((v) => (
                  <VerdictCard key={v.channelId} verdict={v} />
                ))}
                <p className="text-muted-foreground text-xs">{probeResult.note}</p>
              </div>
            )}
          </SectionCard>
        </TabsContent>

        {/* ====================== 🔴 时效估算 ====================== */}
        <TabsContent value="estimate">
          <SectionCard title="时效估算（服务端判定命中哪一段）" className="mt-4">
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="esCountry" label="目的国">
                <Input
                  id="esCountry"
                  value={estimate.countryCode}
                  placeholder="DE"
                  onChange={(e) => setEstimate({ ...estimate, countryCode: e.target.value })}
                />
              </FormField>
              <FormField id="esWeight" label="重量 kg">
                <Input
                  id="esWeight"
                  value={String(estimate.weightKg)}
                  onChange={(e) =>
                    setEstimate({ ...estimate, weightKg: Number(e.target.value) || 0 })
                  }
                />
              </FormField>
              <FormField id="esLevel" label="服务等级（留空 = 全部）">
                <Input
                  id="esLevel"
                  value={estimate.serviceLevel}
                  placeholder="STANDARD"
                  onChange={(e) => setEstimate({ ...estimate, serviceLevel: e.target.value })}
                />
              </FormField>
            </div>
            <Button size="sm" className="mt-4" onClick={() => void runEstimate()}>
              估算时效
            </Button>
            {estimateError && (
              <Notice tone="danger" title="估算失败">
                {estimateError}
              </Notice>
            )}
          </SectionCard>

          <SectionCard title="估算结果" className="mt-4">
            {estimateResult === null ? (
              <p className="text-muted-foreground text-sm">还没有结果。上面填重量后点「估算时效」。</p>
            ) : estimateResult.items.length === 0 ? (
              <Notice tone="warning" title="这个重量段没有时效承诺">
                {estimateResult.note}
              </Notice>
            ) : (
              <div className="space-y-3">
                {estimateResult.items.map((item) => (
                  <div key={`${item.channelId}-${item.serviceLevel}`} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-13 font-medium">{item.channelCode}</span>
                      <Badge tone="info">{serviceLevelLabel(item.serviceLevel)}</Badge>
                      <Badge tone="neutral">{item.matchedBand}</Badge>
                    </div>
                    <p className="text-copy-12 text-muted-foreground mt-1">
                      {transitDaysText(item.transitDaysMin, item.transitDaysMax)} · 截单{' '}
                      {item.cutoffTime} · 预计到达 {new Date(item.earliestArrival).toISOString().slice(0, 10)} ~
                      {new Date(item.latestArrival).toISOString().slice(0, 10)}
                    </p>
                  </div>
                ))}
                <p className="text-muted-foreground text-xs">{estimateResult.note}</p>
              </div>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </div>
  );
}

// ================================================================== 子组件

function RuleCard({
  row,
  busy,
  onEdit,
  onDelete,
}: {
  row: TransitRulePage['items'][number];
  busy: boolean;
  onEdit: () => void;
  onDelete: () => void;
}) {
  const meta = ruleReadinessMeta(row.readiness);
  return (
    <div className="border-border rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-copy-13 font-medium">{row.channelCode}</span>
        <Badge tone="neutral">{row.countryCode}</Badge>
        <Badge tone="info">{serviceLevelLabel(row.serviceLevel)}</Badge>
        <Badge tone={meta.tone === 'success' ? 'success' : 'danger'}>{meta.label}</Badge>
        <Badge tone={row.effectiveNow ? 'success' : 'neutral'}>
          {row.effectiveNow ? '当前生效' : '不在生效区间'}
        </Badge>
        <Badge tone="neutral">{transitStatusLabel(row.status)}</Badge>
        <Button size="sm" variant="outline" className="ml-auto" disabled={busy} onClick={onEdit}>
          编辑
        </Button>
        <Button size="sm" variant="ghost" disabled={busy} onClick={onDelete}>
          删除
        </Button>
      </div>
      <p className="text-copy-12 text-muted-foreground mt-1">
        重量段 {bandText(row.weightMinKg, row.weightMaxKg)} ·{' '}
        {transitDaysText(row.transitDaysMin, row.transitDaysMax)} · 截单 {row.cutoffTime} ·{' '}
        {row.readinessMessage}
      </p>
      {row.note && <p className="text-copy-12 text-muted-foreground mt-1">{row.note}</p>}
    </div>
  );
}

function VerdictCard({ verdict }: { verdict: LimitPrecheckResult['verdicts'][number] }) {
  const meta = verdictMeta(verdict.verdict);
  return (
    <div className="border-border rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <span className="text-copy-13 font-medium">{verdict.channelCode}</span>
        <Badge tone={meta.tone === 'danger' ? 'danger' : 'info'}>{meta.label}</Badge>
        {verdict.transitDaysMin !== null && (
          <Badge tone="neutral">
            {transitDaysText(verdict.transitDaysMin, verdict.transitDaysMax ?? verdict.transitDaysMin)}
          </Badge>
        )}
      </div>
      {verdict.hits.length === 0 ? (
        <p className="text-copy-12 text-muted-foreground mt-1">{verdict.note}</p>
      ) : (
        <ul className="mt-2 space-y-1">
          {verdict.hits.map((h, i) => (
            <li key={`${h.hitKind}-${i}`} className="text-copy-12 flex flex-wrap items-center gap-2">
              <Badge tone={severityMeta(h.severity).tone === 'danger' ? 'danger' : 'warning'}>
                {severityMeta(h.severity).label}
              </Badge>
              <span className="font-medium">{hitKindLabel(h.hitKind)}</span>
              <span className="text-muted-foreground">{h.message}</span>
              {h.carrierReason && (
                <span className="text-muted-foreground">承运商侧：{h.carrierReason}</span>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function sizeText(
  length: number | null,
  width: number | null,
  height: number | null,
  girth: number | null,
  weight: number | null,
): string {
  const parts: string[] = [];
  if (length !== null && width !== null && height !== null) {
    parts.push(`尺寸 ${length} × ${width} × ${height} cm`);
  }
  if (girth !== null) parts.push(`周长和 ≤ ${girth} cm`);
  if (weight !== null) parts.push(`重量 ≤ ${weight} kg`);
  return parts.length > 0 ? parts.join(' · ') : '未登记任何上限';
}

function RuleForm({
  value,
  editing,
  problems,
  busy,
  onChange,
  onSubmit,
  onCancel,
}: {
  value: SaveTransitRuleInput;
  editing: boolean;
  problems: string[];
  busy: boolean;
  onChange: (v: SaveTransitRuleInput) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  const channelId = value.channelId ?? '';
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-4">
        <FormField id="rfChannel" label="所属渠道" required hint="渠道身份归 10.1.1.2，建后不可改">
          <Input
            id="rfChannel"
            value={channelId}
            placeholder="渠道 ID"
            disabled={value.transitRuleId !== null}
            onChange={(e) => onChange({ ...value, channelId: e.target.value })}
          />
        </FormField>
        <FormField id="rfCountry" label="国家" required>
          <Input
            id="rfCountry"
            value={value.countryCode}
            onChange={(e) => onChange({ ...value, countryCode: e.target.value })}
          />
        </FormField>
        <FormField id="rfLevel" label="服务等级" required>
          <Input
            id="rfLevel"
            value={value.serviceLevel}
            placeholder="ECONOMY / STANDARD / EXPRESS"
            onChange={(e) => onChange({ ...value, serviceLevel: e.target.value })}
          />
        </FormField>
        <FormField id="rfStatus" label="状态" required>
          <Input
            id="rfStatus"
            value={value.status}
            placeholder="ACTIVE / DISABLED"
            onChange={(e) => onChange({ ...value, status: e.target.value })}
          />
        </FormField>
        <FormField
          id="rfMin"
          label="重量段下限 kg"
          required
          hint="半开区间下限，含"
        >
          <Input
            id="rfMin"
            value={String(value.weightMinKg)}
            onChange={(e) => onChange({ ...value, weightMinKg: Number(e.target.value) || 0 })}
          />
        </FormField>
        <FormField
          id="rfMax"
          label="重量段上限 kg"
          hint="留空 = 最后一段（大于下限）；填了则不含上限"
        >
          <Input
            id="rfMax"
            value={value.weightMaxKg === null ? '' : String(value.weightMaxKg)}
            onChange={(e) => onChange({ ...value, weightMaxKg: optionalNumber(e.target.value) })}
          />
        </FormField>
        <FormField id="rfDaysMin" label="时效天数下限" required>
          <Input
            id="rfDaysMin"
            value={String(value.transitDaysMin)}
            onChange={(e) => onChange({ ...value, transitDaysMin: Number(e.target.value) || 0 })}
          />
        </FormField>
        <FormField id="rfDaysMax" label="时效天数上限" required>
          <Input
            id="rfDaysMax"
            value={String(value.transitDaysMax)}
            onChange={(e) => onChange({ ...value, transitDaysMax: Number(e.target.value) || 0 })}
          />
        </FormField>
        <FormField id="rfCutoff" label="截单时间 HH:mm" required>
          <Input
            id="rfCutoff"
            value={value.cutoffTime}
            onChange={(e) => onChange({ ...value, cutoffTime: e.target.value })}
          />
        </FormField>
        <FormField id="rfFrom" label="生效起（ISO，可空）" hint="两边要么都给要么都不给">
          <Input
            id="rfFrom"
            value={value.effectiveFrom ?? ''}
            placeholder="2026-01-01T00:00:00Z"
            onChange={(e) => onChange({ ...value, effectiveFrom: e.target.value || null })}
          />
        </FormField>
        <FormField id="rfTo" label="生效止（ISO，可空）">
          <Input
            id="rfTo"
            value={value.effectiveTo ?? ''}
            placeholder="2026-12-31T00:00:00Z"
            onChange={(e) => onChange({ ...value, effectiveTo: e.target.value || null })}
          />
        </FormField>
      </div>
      <Problems problems={problems} />
      <div className="flex gap-2">
        <Button size="sm" onClick={onSubmit} disabled={busy}>
          {editing ? '保存修改' : '登记时效承诺'}
        </Button>
        {editing && (
          <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
            取消
          </Button>
        )}
      </div>
    </div>
  );
}

function SizeForm({
  value,
  editing,
  problems,
  busy,
  onChange,
  onSubmit,
  onCancel,
}: {
  value: SaveSizeLimitInput;
  editing: boolean;
  problems: string[];
  busy: boolean;
  onChange: (v: SaveSizeLimitInput) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-4">
        <FormField id="sfChannel" label="所属渠道" required>
          <Input
            id="sfChannel"
            value={value.channelId ?? ''}
            placeholder="渠道 ID"
            disabled={value.sizeLimitId !== null}
            onChange={(e) => onChange({ ...value, channelId: e.target.value })}
          />
        </FormField>
        <FormField id="sfCountry" label="国家" hint="留空 = 渠道级（所有国家）">
          <Input
            id="sfCountry"
            value={value.countryCode}
            placeholder="DE"
            onChange={(e) => onChange({ ...value, countryCode: e.target.value })}
          />
        </FormField>
        <FormField id="sfLength" label="长 cm" hint="长/宽/高要么都给要么都不给">
          <Input
            id="sfLength"
            value={value.maxLengthCm === null ? '' : String(value.maxLengthCm)}
            onChange={(e) => onChange({ ...value, maxLengthCm: optionalNumber(e.target.value) })}
          />
        </FormField>
        <FormField id="sfWidth" label="宽 cm">
          <Input
            id="sfWidth"
            value={value.maxWidthCm === null ? '' : String(value.maxWidthCm)}
            onChange={(e) => onChange({ ...value, maxWidthCm: optionalNumber(e.target.value) })}
          />
        </FormField>
        <FormField id="sfHeight" label="高 cm">
          <Input
            id="sfHeight"
            value={value.maxHeightCm === null ? '' : String(value.maxHeightCm)}
            onChange={(e) => onChange({ ...value, maxHeightCm: optionalNumber(e.target.value) })}
          />
        </FormField>
        <FormField id="sfGirth" label="周长和 cm" hint="长+宽+高×2，可单独填">
          <Input
            id="sfGirth"
            value={value.maxGirthCm === null ? '' : String(value.maxGirthCm)}
            onChange={(e) => onChange({ ...value, maxGirthCm: optionalNumber(e.target.value) })}
          />
        </FormField>
        <FormField id="sfWeight" label="重量 kg" hint="与重量段是两件事：段是时效承诺，重量上限是能不能发">
          <Input
            id="sfWeight"
            value={value.maxWeightKg === null ? '' : String(value.maxWeightKg)}
            onChange={(e) => onChange({ ...value, maxWeightKg: optionalNumber(e.target.value) })}
          />
        </FormField>
      </div>
      <Problems problems={problems} />
      <div className="flex gap-2">
        <Button size="sm" onClick={onSubmit} disabled={busy}>
          {editing ? '保存修改' : '登记上限'}
        </Button>
        {editing && (
          <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
            取消
          </Button>
        )}
      </div>
    </div>
  );
}

function MarkingForm({
  value,
  editing,
  problems,
  busy,
  onChange,
  onSubmit,
  onCancel,
}: {
  value: SaveMarkingRequirementInput;
  editing: boolean;
  problems: string[];
  busy: boolean;
  onChange: (v: SaveMarkingRequirementInput) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="space-y-3">
      <div className="grid gap-3 md:grid-cols-4">
        <FormField id="mfChannel" label="所属渠道" required>
          <Input
            id="mfChannel"
            value={value.channelId ?? ''}
            placeholder="渠道 ID"
            disabled={value.markingId !== null}
            onChange={(e) => onChange({ ...value, channelId: e.target.value })}
          />
        </FormField>
        <FormField id="mfFlag" label="标记种类" required hint="危险品是上游四种之外的第五类">
          <Input
            id="mfFlag"
            value={value.flagKind}
            placeholder="BATTERY / LIQUID / DANGEROUS_GOODS / MAGNET"
            disabled={value.markingId !== null}
            onChange={(e) => onChange({ ...value, flagKind: e.target.value })}
          />
        </FormField>
        <FormField id="mfMode" label="要求" required>
          <Input
            id="mfMode"
            value={value.requirementMode}
            placeholder="BLOCK / MANUAL_REVIEW / LABEL_REQUIRED / ALLOWED"
            onChange={(e) => onChange({ ...value, requirementMode: e.target.value })}
          />
        </FormField>
        <FormField
          id="mfCode"
          label="标识代码"
          hint="要求贴标识时必填（如 UN38.3）"
        >
          <Input
            id="mfCode"
            value={value.markingCode}
            onChange={(e) => onChange({ ...value, markingCode: e.target.value })}
          />
        </FormField>
      </div>
      <Problems problems={problems} />
      <div className="flex gap-2">
        <Button size="sm" onClick={onSubmit} disabled={busy}>
          {editing ? '保存修改' : '登记标记要求'}
        </Button>
        {editing && (
          <Button size="sm" variant="ghost" onClick={onCancel} disabled={busy}>
            取消
          </Button>
        )}
      </div>
    </div>
  );
}

/** 本地校验的问题清单：**不是安全边界**，真正的硬拦截在后端。 */
function Problems({ problems }: { problems: string[] }) {
  if (problems.length === 0) return null;
  return (
    <Notice tone="warning" title="提交前需要修正">
      <ul className="list-disc pl-4">
        {problems.map((p) => (
          <li key={p}>{p}</li>
        ))}
      </ul>
    </Notice>
  );
}
