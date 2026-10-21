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
  actionRateText,
  applyAction,
  applyTrigger,
  createPricingRule,
  currentPriceText,
  decidePricingRuleSuggestion,
  deletePricingRule,
  emptyEvaluate,
  emptyRule,
  evaluatePricingRules,
  fetchPricingRule,
  fetchPricingRuleCapabilities,
  fetchPricingRuleDirectory,
  fetchPricingRuleHits,
  fetchPricingRuleSuggestions,
  fetchPricingRules,
  hitStatusText,
  hitTone,
  percentText,
  priceChangeText,
  quantityText,
  requiresConfirmationText,
  statusTone,
  suggestionStatusLabel,
  suggestedPriceText,
  summaryStats,
  thresholdText,
  toRuleInput,
  triggerTypeLabel,
  updatePricingRule,
  validateEvaluate,
  validateRule,
} from './pricing-rule-model';
import type {
  ActionType,
  PricingRuleCapabilities,
  PricingRuleDetail,
  PricingRuleDirectory,
  PricingRuleEvaluationResult,
  PricingRuleHitView,
  PricingRuleInput,
  PricingRuleSuggestionView,
  PricingRuleSummary,
  ScopeType,
  TriggerType,
  ValidationError,
} from './pricing-rule-model';

/**
 * 5.3.2.2 价格规则工作区。
 *
 * <p>蓝图：「定义自动调价规则：触发条件（库存低于阈值/竞品降价/成本上涨/毛利低于下限/库龄超期）
 * + 动作（降价/涨价/暂停促销/下架），支持优先级与生效时段。」输入「规则条件与动作；作用范围；
 * 生效时段」，输出「规则记录 + 命中记录 + 建议调价列表」，并加粗
 * 「<b>自动调价必须先"建议"再"执行"，高风险规则（降价超过 X%）需人工确认</b>」。
 *
 * <p>这是一个真实工作区而不是占位页：能力声明（触发条件 / 动作 / 作用范围 / 状态四张词表 +
 * 上游真实交付状态）、规则台账（真分页 + 关键词 / 触发条件 / 动作筛选）、规则表单
 * （条件 + 动作 + 优先级 + 生效时段 + 作用范围 + 自动放行阈值）、评估面板（跑一次评估 →
 * 命中记录 + 建议调价列表）、建议调价的人工确认 / 驳回，加载态 / 错误态 / 空态三态齐全。
 *
 * <p>🔴 **先建议，再执行**：页面上每一个价格都带「建议价」字样；改价走 5.4.1.1 调价单。
 * 高风险建议（降价超阈值 / 成本不完整 / 非价格动作）停在「待人工确认」，页面上写明原因。
 */
export function PricingRuleWorkspace() {
  const [capabilities, setCapabilities] = useState<PricingRuleCapabilities | null>(null);
  const [directory, setDirectory] = useState<PricingRuleDirectory | null>(null);
  const [rules, setRules] = useState<PricingRuleSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<PricingRuleDetail | null>(null);
  const [form, setForm] = useState<PricingRuleInput>(emptyRule());
  const [editing, setEditing] = useState<string | null>(null);
  const [evaluateForm, setEvaluateForm] = useState(emptyEvaluate());
  const [evaluation, setEvaluation] = useState<PricingRuleEvaluationResult | null>(null);
  const [hits, setHits] = useState<PricingRuleHitView[]>([]);
  const [suggestions, setSuggestions] = useState<PricingRuleSuggestionView[]>([]);
  const [keyword, setKeyword] = useState('');
  const [triggerFilter, setTriggerFilter] = useState('');
  const [actionFilter, setActionFilter] = useState('');
  const [problem, setProblem] = useState<ValidationError | null>(null);
  const [evaluateProblem, setEvaluateProblem] = useState<ValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [caps, dir, page, hitPage, suggestionPage] = await Promise.all([
        fetchPricingRuleCapabilities(),
        fetchPricingRuleDirectory().catch(() => null),
        fetchPricingRules({ size: 50 }).catch(() => null),
        fetchPricingRuleHits({ size: 20 }).catch(() => null),
        fetchPricingRuleSuggestions({ size: 20 }).catch(() => null),
      ]);
      setCapabilities(caps);
      if (dir) setDirectory(dir);
      if (page) {
        setRules(page.items);
        setTotal(page.total);
      }
      if (hitPage) setHits(hitPage.items);
      if (suggestionPage) setSuggestions(suggestionPage.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '价格规则读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = useCallback((next: Partial<PricingRuleInput>) => {
    setForm((prev) => ({ ...prev, ...next }));
    setProblem(null);
  }, []);

  const stats = useMemo(() => summaryStats(evaluation), [evaluation]);

  async function search() {
    setBusy(true);
    setError('');
    try {
      const page = await fetchPricingRules({
        keyword: keyword.trim() || undefined,
        triggerType: (triggerFilter.trim() as TriggerType) || null,
        actionType: (actionFilter.trim() as ActionType) || null,
        size: 50,
      });
      setRules(page.items);
      setTotal(page.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则检索失败');
    } finally {
      setBusy(false);
    }
  }

  async function open(id: string) {
    setBusy(true);
    setError('');
    try {
      const detail = await fetchPricingRule(id);
      setSelected(detail);
      setForm(toRuleInput(detail));
      setEditing(id);
    } catch (e) {
      setError(e instanceof Error ? e.message : '规则详情读不到');
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    const invalid = validateRule(form);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = editing
        ? await updatePricingRule(editing, form)
        : await createPricingRule(form);
      setSelected(saved);
      setForm(toRuleInput(saved));
      setEditing(saved.summary.id);
      setNotice(editing ? '规则已更新（作用范围整体替换）。' : '规则已创建。');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function remove() {
    if (!editing) return;
    setBusy(true);
    setError('');
    try {
      await deletePricingRule(editing);
      setSelected(null);
      setEditing(null);
      setForm(emptyRule());
      setNotice('规则已删除。');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    } finally {
      setBusy(false);
    }
  }

  async function runEvaluate() {
    const invalid = validateEvaluate(evaluateForm);
    if (invalid) {
      setEvaluateProblem(invalid);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const result = await evaluatePricingRules(evaluateForm);
      setEvaluation(result);
      setHits(result.hits);
      setSuggestions(result.suggestions);
      setNotice(
        result.suggestionCount === 0
          ? '本次评估没有产出建议调价（命中记录与原因在下面）。'
          : `本次评估产出 ${result.suggestionCount} 条**建议调价**（不是生效价），其中 ${result.pendingConfirmationCount} 条待人工确认。`,
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '评估失败');
    } finally {
      setBusy(false);
    }
  }

  async function decide(id: string, decision: 'CONFIRM' | 'REJECT') {
    setBusy(true);
    setError('');
    try {
      await decidePricingRuleSuggestion(id, {
        decision,
        note: decision === 'REJECT' ? '人工驳回（规则误判）' : '人工确认放行',
      });
      setNotice(decision === 'REJECT' ? '建议已驳回。' : '建议已确认放行。');
      const page = await fetchPricingRuleSuggestions({ size: 20 }).catch(() => null);
      if (page) setSuggestions(page.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '改判失败');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="价格规则"
        description="定义自动调价规则：触发条件 + 动作 + 优先级 + 生效时段；产出命中记录与建议调价列表。"
      />

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      {/* ---------------- 能力声明：固定在顶部 ---------------- */}
      <SectionCard
        title="能力声明"
        description="触发条件 / 动作 / 作用范围 / 状态，以及上游的真实交付状态。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-5">
              {capabilities.triggerTypes.map((item) => (
                <div key={item.code} className="border-border rounded-md border p-3">
                  <div className="flex items-center gap-2">
                    <Badge tone="brand">{item.label}</Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-2">{item.description}</p>
                </div>
              ))}
            </div>
            <div className="grid gap-3 md:grid-cols-4">
              {capabilities.actionTypes.map((item) => (
                <div key={item.code} className="border-border rounded-md border p-3">
                  <Badge tone="info">{item.label}</Badge>
                  <p className="text-copy-12 text-muted-foreground mt-2">{item.description}</p>
                </div>
              ))}
            </div>
            <Notice tone="warning" title="自动调价先「建议」再「执行」">
              {capabilities.suggestFirstNote} {capabilities.downstreamNote}
            </Notice>
            <Notice tone="info" title="上游与口径">
              {capabilities.upstreamNote} {capabilities.competitorNote}{' '}
              {capabilities.priorityNote} {capabilities.assumptionNote} {capabilities.sourceNote}{' '}
              {capabilities.actionNote}
            </Notice>
          </div>
        ) : (
          <EmptyState title="能力声明读不到" description="请稍后重试。" />
        )}
      </SectionCard>

      {/* ---------------- 评估统计 ---------------- */}
      {stats.length > 0 ? (
        <div className="grid gap-3 md:grid-cols-4">
          {stats.map((stat) => (
            <StatCard
              key={stat.label}
              label={stat.label}
              value={stat.value}
              comparisonLabel={stat.hint}
              polarity={
                stat.tone === 'danger'
                  ? 'negative'
                  : stat.tone === 'warning'
                    ? 'neutral'
                    : stat.tone === 'success'
                      ? 'positive'
                      : 'neutral'
              }
            />
          ))}
        </div>
      ) : null}

      <div className="grid gap-4 lg:grid-cols-3">
        {/* ---------------- 规则台账 ---------------- */}
        <SectionCard
          className="lg:col-span-1"
          title="规则台账"
          description={`共 ${total} 条规则（真分页 + 筛选）。`}
        >
          <div className="space-y-3">
            <FormField id="pr-keyword" label="关键词（编码 / 名称）">
              <Input
                id="pr-keyword"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="例如 CLEAR-STOCK"
              />
            </FormField>
            <div className="grid grid-cols-2 gap-2">
              <FormField id="pr-trigger" label="触发条件">
                <Input
                  id="pr-trigger"
                  value={triggerFilter}
                  onChange={(e) => setTriggerFilter(e.target.value)}
                  placeholder="COST_INCREASE"
                />
              </FormField>
              <FormField id="pr-action" label="动作">
                <Input
                  id="pr-action"
                  value={actionFilter}
                  onChange={(e) => setActionFilter(e.target.value)}
                  placeholder="LOWER_PRICE"
                />
              </FormField>
            </div>
            <Button onClick={() => void search()} disabled={busy}>
              检索
            </Button>
          </div>

          <div className="mt-4 space-y-2">
            {rules.length === 0 ? (
              <EmptyState
                title="还没有价格规则"
                description="规则是「什么时候改价、改成什么价」：先定触发条件与动作，再跑一次评估。"
                action={
                  <Button
                    onClick={() => {
                      setEditing(null);
                      setSelected(null);
                      setForm(emptyRule());
                    }}
                  >
                    新建规则
                  </Button>
                }
              />
            ) : (
              rules.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  onClick={() => void open(item.id)}
                  className="border-border hover:bg-muted w-full rounded-md border p-3 text-left"
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-label-13 font-semibold">{item.name}</span>
                    <Badge tone={item.enabled ? 'success' : 'neutral'}>
                      {item.enabled ? '启用' : '停用'}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-2">
                    <span>{item.code}</span>
                    <span>· {triggerTypeLabel(item.triggerType)}</span>
                    <span>· {actionRateText(item.actionType, item.actionRate)}</span>
                    <span>· 优先级 {item.priority}</span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    {thresholdText(item)} · 自动放行上限{' '}
                    {percentText(item.maxAutoPriceDropRate, '0%')} · 作用范围 {item.scopeCount} 条 ·{' '}
                    {item.currency}
                  </div>
                </button>
              ))
            )}
          </div>
        </SectionCard>

        {/* ---------------- 规则表单 ---------------- */}
        <SectionCard
          className="lg:col-span-2"
          title={editing ? `编辑规则：${form.name || form.code}` : '新建规则'}
          description="触发条件 + 动作 + 优先级 + 生效时段 + 作用范围。"
          extra={
            editing ? (
              <Button variant="destructive" size="sm" onClick={() => void remove()} disabled={busy}>
                删除
              </Button>
            ) : undefined
          }
        >
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="pr-code" label="规则编码" hint="租户内唯一" required>
              <Input
                id="pr-code"
                value={form.code}
                onChange={(e) => patch({ code: e.target.value.toUpperCase() })}
              />
            </FormField>
            <FormField id="pr-name" label="规则名称" required>
              <Input id="pr-name" value={form.name} onChange={(e) => patch({ name: e.target.value })} />
            </FormField>
            <FormField id="pr-currency" label="建议价币种" required>
              <Input
                id="pr-currency"
                value={form.currency}
                onChange={(e) => patch({ currency: e.target.value.toUpperCase() })}
              />
            </FormField>
          </div>

          <div className="mt-3 grid gap-3 md:grid-cols-3">
            <FormField id="pr-trigger-field" label="触发条件" hint="五类，各有各的阈值列">
              <Input
                id="pr-trigger-field"
                value={form.triggerType}
                onChange={(e) =>
                  patch(applyTrigger(form, e.target.value.toUpperCase() as TriggerType))
                }
              />
            </FormField>
            <FormField id="pr-action-field" label="动作" hint="降价 / 涨价 / 暂停促销 / 下架">
              <Input
                id="pr-action-field"
                value={form.actionType}
                onChange={(e) => patch(applyAction(form, e.target.value.toUpperCase() as ActionType))}
              />
            </FormField>
            <FormField id="pr-priority" label="优先级" hint="数字小者优先">
              <Input
                id="pr-priority"
                type="number"
                value={form.priority ?? ''}
                onChange={(e) =>
                  patch({ priority: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
          </div>

          <div className="border-border mt-4 rounded-md border p-3">
            <p className="text-label-13 font-semibold">触发条件阈值（按条件只填一列）</p>
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <FormField
                id="pr-threshold-quantity"
                label="库存阈值（件）"
                hint="仅库存低于阈值"
                error={problem?.field === 'thresholdQuantity' ? problem.message : undefined}
              >
                <Input
                  id="pr-threshold-quantity"
                  type="number"
                  value={form.thresholdQuantity ?? ''}
                  onChange={(e) =>
                    patch({
                      thresholdQuantity: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField
                id="pr-threshold-rate"
                label="涨跌幅阈值（0.1 = 10%）"
                hint="竞品降价 / 成本上涨"
                error={problem?.field === 'thresholdRate' ? problem.message : undefined}
              >
                <Input
                  id="pr-threshold-rate"
                  type="number"
                  step="0.01"
                  value={form.thresholdRate ?? ''}
                  onChange={(e) =>
                    patch({ thresholdRate: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
              <FormField
                id="pr-margin-floor"
                label="毛利下限（0.2 = 20%）"
                hint="仅毛利低于下限"
                error={problem?.field === 'marginFloorRate' ? problem.message : undefined}
              >
                <Input
                  id="pr-margin-floor"
                  type="number"
                  step="0.01"
                  value={form.marginFloorRate ?? ''}
                  onChange={(e) =>
                    patch({ marginFloorRate: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
              <FormField
                id="pr-aging-days"
                label="库龄阈值（天）"
                hint="仅库龄超期"
                error={problem?.field === 'agingDaysThreshold' ? problem.message : undefined}
              >
                <Input
                  id="pr-aging-days"
                  type="number"
                  value={form.agingDaysThreshold ?? ''}
                  onChange={(e) =>
                    patch({
                      agingDaysThreshold: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
            </div>
          </div>

          <div className="border-border mt-4 rounded-md border p-3">
            <p className="text-label-13 font-semibold">动作与生效时段</p>
            <div className="mt-3 grid gap-3 md:grid-cols-4">
              <FormField
                id="pr-action-rate"
                label="调价幅度（0.05 = 5%）"
                hint="降价 / 涨价必填；非价格动作留空"
                error={problem?.field === 'actionRate' ? problem.message : undefined}
              >
                <Input
                  id="pr-action-rate"
                  type="number"
                  step="0.01"
                  value={form.actionRate ?? ''}
                  onChange={(e) =>
                    patch({ actionRate: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
              <FormField
                id="pr-max-auto-drop"
                label="自动放行的最大降价幅度"
                hint="超过它 ⇒ 需人工确认"
                error={problem?.field === 'maxAutoPriceDropRate' ? problem.message : undefined}
              >
                <Input
                  id="pr-max-auto-drop"
                  type="number"
                  step="0.01"
                  value={form.maxAutoPriceDropRate ?? ''}
                  onChange={(e) =>
                    patch({
                      maxAutoPriceDropRate:
                        e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="pr-effective-from" label="生效起点" required>
                <Input
                  id="pr-effective-from"
                  value={form.effectiveFrom}
                  onChange={(e) => patch({ effectiveFrom: e.target.value })}
                />
              </FormField>
              <FormField
                id="pr-effective-to"
                label="生效终点"
                hint="空 = 长期有效"
                error={problem?.field === 'effectiveTo' ? problem.message : undefined}
              >
                <Input
                  id="pr-effective-to"
                  value={form.effectiveTo ?? ''}
                  onChange={(e) => patch({ effectiveTo: e.target.value || null })}
                />
              </FormField>
            </div>
          </div>

          <div className="border-border mt-4 rounded-md border p-3">
            <p className="text-label-13 font-semibold">作用范围</p>
            <p className="text-copy-12 text-muted-foreground mt-1">
              留空 = 适用于全租户（显式语义，不是漏填）。
            </p>
            <div className="mt-3 space-y-2">
              {form.scopes.length === 0 ? (
                <p className="text-copy-12 text-muted-foreground">未限定（适用于全租户）</p>
              ) : (
                form.scopes.map((scope, index) => (
                  <div key={`${scope.type}-${scope.ref}-${index}`} className="grid gap-2 md:grid-cols-4">
                    <FormField id={`pr-scope-type-${index}`} label="类型">
                      <Input
                        id={`pr-scope-type-${index}`}
                        value={scope.type}
                        onChange={(e) =>
                          patch({
                            scopes: form.scopes.map((item, i) =>
                              i === index
                                ? { ...item, type: e.target.value.toUpperCase() as ScopeType }
                                : item,
                            ),
                          })
                        }
                      />
                    </FormField>
                    <FormField id={`pr-scope-ref-${index}`} label="取值">
                      <Input
                        id={`pr-scope-ref-${index}`}
                        value={scope.ref}
                        onChange={(e) =>
                          patch({
                            scopes: form.scopes.map((item, i) =>
                              i === index ? { ...item, ref: e.target.value } : item,
                            ),
                          })
                        }
                      />
                    </FormField>
                    <FormField id={`pr-scope-platform-${index}`} label="平台码（仅店铺）">
                      <Input
                        id={`pr-scope-platform-${index}`}
                        value={scope.platformCode ?? ''}
                        onChange={(e) =>
                          patch({
                            scopes: form.scopes.map((item, i) =>
                              i === index
                                ? { ...item, platformCode: e.target.value || null }
                                : item,
                            ),
                          })
                        }
                      />
                    </FormField>
                    <div className="flex items-end">
                      <Button
                        variant="destructive"
                        size="sm"
                        onClick={() =>
                          patch({
                            scopes: form.scopes.filter((_, i) => i !== index),
                          })
                        }
                      >
                        移除
                      </Button>
                    </div>
                  </div>
                ))
              )}
              <Button
                size="sm"
                onClick={() =>
                  patch({
                    scopes: [...form.scopes, { type: 'CATEGORY', ref: '', platformCode: null }],
                  })
                }
              >
                添加作用范围
              </Button>
              {problem?.field === 'scopes' ? (
                <p className="text-copy-12 text-destructive" role="alert">
                  {problem.message}
                </p>
              ) : null}
            </div>
          </div>

          <div className="mt-4 flex items-center gap-2">
            <Button onClick={() => void save()} disabled={busy}>
              {editing ? '保存修改' : '创建规则'}
            </Button>
            {editing ? (
              <Button
                variant="secondary"
                onClick={() => {
                  setEditing(null);
                  setSelected(null);
                  setForm(emptyRule());
                }}
              >
                取消编辑
              </Button>
            ) : null}
          </div>
        </SectionCard>
      </div>

      {/* ---------------- 评估面板 ---------------- */}
      <SectionCard
        title="跑一次规则评估"
        description="产出命中记录与建议调价列表（不改价：改价走 5.4.1.1 调价单）。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <FormField
            id="pr-eval-skus"
            label="SKU（逗号分隔的 UUID）"
            hint="一次最多 50 个"
            required
            error={evaluateProblem?.field === 'skuIds' ? evaluateProblem.message : undefined}
          >
            <Input
              id="pr-eval-skus"
              value={evaluateForm.skuIds.join(',')}
              onChange={(e) =>
                setEvaluateForm((prev) => ({
                  ...prev,
                  skuIds: e.target.value
                    .split(',')
                    .map((item) => item.trim())
                    .filter((item) => item.length > 0),
                }))
              }
            />
          </FormField>
          <FormField
            id="pr-eval-site"
            label="目标店铺"
            required
            error={evaluateProblem?.field === 'siteCode' ? evaluateProblem.message : undefined}
          >
            <Input
              id="pr-eval-site"
              value={evaluateForm.siteCode ?? ''}
              onChange={(e) => setEvaluateForm((prev) => ({ ...prev, siteCode: e.target.value }))}
            />
          </FormField>
          <FormField
            id="pr-eval-platform"
            label="目标平台"
            required
            error={evaluateProblem?.field === 'platformCode' ? evaluateProblem.message : undefined}
          >
            <Input
              id="pr-eval-platform"
              value={evaluateForm.platformCode ?? ''}
              onChange={(e) =>
                setEvaluateForm((prev) => ({ ...prev, platformCode: e.target.value }))
              }
            />
          </FormField>
          <FormField
            id="pr-eval-currency"
            label="币种"
            required
            error={evaluateProblem?.field === 'currency' ? evaluateProblem.message : undefined}
          >
            <Input
              id="pr-eval-currency"
              value={evaluateForm.currency}
              onChange={(e) =>
                setEvaluateForm((prev) => ({ ...prev, currency: e.target.value.toUpperCase() }))
              }
            />
          </FormField>
        </div>

        <div className="border-border mt-4 rounded-md border p-3">
          <p className="text-label-13 font-semibold">口径假设（5.3.1.1 的手工假设机制）</p>
          <p className="text-copy-12 text-muted-foreground mt-1">
            平台费率 / 物流单价 / 汇率留空 = 「取不到」，**不是 0**；缺了它们，复核不了毛利，
            高风险建议也就无法自动放行。
          </p>
          <div className="mt-3 grid gap-3 md:grid-cols-5">
            <FormField id="pr-eval-fee-rate" label="平台费率（0.15 = 15%）">
              <Input
                id="pr-eval-fee-rate"
                type="number"
                step="0.01"
                value={evaluateForm.platformFeeRate ?? ''}
                onChange={(e) =>
                  setEvaluateForm((prev) => ({
                    ...prev,
                    platformFeeRate: e.target.value === '' ? null : Number(e.target.value),
                  }))
                }
              />
            </FormField>
            <FormField id="pr-eval-fee-fixed" label="平台固定费">
              <Input
                id="pr-eval-fee-fixed"
                type="number"
                step="0.01"
                value={evaluateForm.platformFeeFixed ?? ''}
                onChange={(e) =>
                  setEvaluateForm((prev) => ({
                    ...prev,
                    platformFeeFixed: e.target.value === '' ? null : Number(e.target.value),
                  }))
                }
              />
            </FormField>
            <FormField id="pr-eval-logistics" label="物流每单位运费">
              <Input
                id="pr-eval-logistics"
                type="number"
                step="0.01"
                value={evaluateForm.logisticsCostPerUnit ?? ''}
                onChange={(e) =>
                  setEvaluateForm((prev) => ({
                    ...prev,
                    logisticsCostPerUnit: e.target.value === '' ? null : Number(e.target.value),
                  }))
                }
              />
            </FormField>
            <FormField
              id="pr-eval-cost-currency"
              label="成本币种"
              hint="空 = 与售价同币种"
              error={evaluateProblem?.field === 'fxRate' ? evaluateProblem.message : undefined}
            >
              <Input
                id="pr-eval-cost-currency"
                value={evaluateForm.costCurrency ?? ''}
                onChange={(e) =>
                  setEvaluateForm((prev) => ({
                    ...prev,
                    costCurrency: e.target.value.toUpperCase() || null,
                  }))
                }
              />
            </FormField>
            <FormField id="pr-eval-fx" label="汇率（1 成本币种 = X 售价币种）">
              <Input
                id="pr-eval-fx"
                type="number"
                step="0.0001"
                value={evaluateForm.fxRate ?? ''}
                onChange={(e) =>
                  setEvaluateForm((prev) => ({
                    ...prev,
                    fxRate: e.target.value === '' ? null : Number(e.target.value),
                  }))
                }
              />
            </FormField>
          </div>
        </div>

        <div className="mt-4 flex items-center gap-2">
          <Button onClick={() => void runEvaluate()} disabled={busy}>
            跑评估
          </Button>
          {directory ? (
            <span className="text-copy-12 text-muted-foreground">
              分类 {directory.categories.length} 个 · 品牌 {directory.brands.length} 个（作用范围目录）
            </span>
          ) : null}
        </div>
      </SectionCard>

      <div className="grid gap-4 lg:grid-cols-3">
        {/* ---------------- 命中记录 ---------------- */}
        <SectionCard
          className="lg:col-span-1"
          title="命中记录"
          description="含未命中的判定留痕（为什么这条规则没动我的商品）。"
        >
          <div className="space-y-2">
            {hits.length === 0 ? (
              <EmptyState title="还没有命中记录" description="跑一次评估就会留下判定留痕。" />
            ) : (
              hits.map((hit) => (
                <div key={hit.id} className="border-border rounded-md border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-label-13 font-semibold">
                      {hit.skuCode ?? hit.skuName ?? hit.skuId}
                    </span>
                    <Badge tone={hitTone(hit.matched, hit.superseded)}>
                      {hitStatusText(hit)}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-2">
                    <span>{hit.ruleCode}</span>
                    <span>· {hit.triggerTypeLabel}</span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">{hit.reason}</p>
                </div>
              ))
            )}
          </div>
        </SectionCard>

        {/* ---------------- 建议调价列表 ---------------- */}
        <SectionCard
          className="lg:col-span-2"
          title="建议调价列表"
          description="🔴 建议价，不是生效价。高风险建议必须人工确认后才放行。"
        >
          <div className="space-y-2">
            {suggestions.length === 0 ? (
              <EmptyState
                title="还没有建议调价"
                description="跑一次评估就会产出建议调价（取不到当前价的 SKU 不会产出建议）。"
              />
            ) : (
              suggestions.map((suggestion) => (
                <div key={suggestion.id} className="border-border rounded-md border p-3">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-label-13 font-semibold">
                      {suggestion.skuCode ?? suggestion.skuName ?? suggestion.skuId}
                    </span>
                    <Badge tone={statusTone(suggestion.status)}>
                      {suggestionStatusLabel(suggestion.status)}
                    </Badge>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1 flex flex-wrap gap-2">
                    <span>{suggestion.ruleCode}</span>
                    <span>· {suggestion.actionTypeLabel}</span>
                    <span>· {suggestion.siteCode ?? '不限店铺'}</span>
                  </div>
                  <div className="mt-2 grid gap-1 md:grid-cols-4">
                    <span className="text-copy-12">
                      当前价：{currentPriceText(suggestion)}
                    </span>
                    <span className="text-copy-12">
                      建议价：{suggestedPriceText(suggestion)}
                    </span>
                    <span className="text-copy-12">
                      变动：{priceChangeText(suggestion.priceChangeRate)}
                    </span>
                    <span className="text-copy-12">
                      建议价毛利率：
                      {percentText(suggestion.grossMarginRate, '不可信（成本不完整）')}
                    </span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">
                    {requiresConfirmationText(suggestion)}
                  </p>
                  <p className="text-copy-12 text-muted-foreground mt-1">{suggestion.reason}</p>
                  {suggestion.status === 'PENDING_CONFIRMATION' ? (
                    <div className="mt-2 flex gap-2">
                      <Button size="sm" onClick={() => void decide(suggestion.id, 'CONFIRM')} disabled={busy}>
                        确认放行
                      </Button>
                      <Button
                        variant="destructive"
                        size="sm"
                        onClick={() => void decide(suggestion.id, 'REJECT')}
                        disabled={busy}
                      >
                        驳回
                      </Button>
                    </div>
                  ) : null}
                </div>
              ))
            )}
          </div>
        </SectionCard>
      </div>

      {selected ? (
        <SectionCard
          title={`规则详情：${selected.summary.code}`}
          description="优先级数字小者优先；作用范围与生效时段决定它什么时候、对谁生效。"
        >
          <div className="grid gap-2 md:grid-cols-4">
            <span className="text-copy-12">
              触发条件：{triggerTypeLabel(selected.triggerType)} ·{' '}
              {thresholdText(selected)}
            </span>
            <span className="text-copy-12">
              动作：{actionRateText(selected.actionType, selected.actionRate)}
            </span>
            <span className="text-copy-12">
              优先级：{selected.priority}（数字小者优先）
            </span>
            <span className="text-copy-12">
              自动放行上限：{percentText(selected.maxAutoPriceDropRate, '0%')}
            </span>
            <span className="text-copy-12">
              生效：{selected.effectiveFrom} ~ {selected.effectiveTo ?? '长期'}
            </span>
            <span className="text-copy-12">作用范围：{selected.summary.scopeCount} 条</span>
            <span className="text-copy-12">建议价币种：{selected.currency}</span>
            <span className="text-copy-12">
              {selected.enabled ? '已启用' : '已停用'} · 版本 {selected.version}
            </span>
          </div>
          {selected.note ? (
            <p className="text-copy-12 text-muted-foreground mt-2">备注：{selected.note}</p>
          ) : null}
        </SectionCard>
      ) : null}

      {!loaded ? <p className="text-copy-12 text-muted-foreground">加载中……</p> : null}
    </div>
  );
}
