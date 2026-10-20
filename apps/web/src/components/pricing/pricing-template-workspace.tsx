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
  adjustmentTone,
  applyPricingTemplate,
  assumptionGaps,
  baseMethodLabel,
  baseRateText,
  createPricingTemplate,
  decisionTone,
  deletePricingTemplate,
  emptyApply,
  emptyTemplate,
  fetchPricingTemplate,
  fetchPricingTemplateApplications,
  fetchPricingTemplateCapabilities,
  fetchPricingTemplateDirectory,
  fetchPricingTemplateSites,
  fetchPricingTemplates,
  hasTurnoverRule,
  missingGaps,
  moneyWithCurrency,
  percentText,
  productStageLabel,
  quantityText,
  scopeTypeLabel,
  suggestedPriceText,
  summaryStats,
  toTemplateInput,
  updatePricingTemplate,
  validateApply,
  validateTemplate,
} from './pricing-template-model';
import type {
  ApplyRequest,
  ApplyResult,
  ApplicationSummary,
  PricingTemplateCapabilities,
  SiteOption,
  TemplateDetail,
  TemplateDirectory,
  TemplateInput,
  TemplateSummary,
  ValidationError,
} from './pricing-template-model';

/**
 * 5.3.2.1 定价模板工作区。
 *
 * <p>蓝图：「沉淀定价策略模板：成本加成、固定毛利、竞品跟随（若允许）、按站点差异化、
 * 按库存周转调整，新品与老品用不同模板。」输入「模板定义（基准 + 调整项）；适用对象
 * （分类/品牌/店铺）」，输出「模板记录；一键应用到商品生成建议价」，并加粗
 * 「<b>模板是"怎么算"，价目表是"算完是多少"</b>」。
 *
 * <p>这是一个真实工作区而不是占位页：能力声明（四张词表 + 七条口径说明，固定在顶部）、
 * 模板台账（真分页 + 关键词 / 商品阶段 / 基准方法 / 适用对象筛选）、模板表单
 * （基准 + 调整项 + 适用对象 + 口径假设）、模板详情、一键应用面板
 * （产出**建议价** + 命中/未命中的调整项 + 5.3.1.1 的测算依据）、应用留痕，
 * 加载态 / 错误态 / 空态三态齐全。
 *
 * <p>🔴 **建议价不是生效价**：改价走 5.4.1.1 调价单。页面上建议价那一栏永远带
 * 「建议价」字样；后端拒绝给价时显示「不给建议价（成本不完整）」，**不是 0**。
 *
 * <p>🔴 **上游的真实状态如实标在页面上**：竞品价是人工登记（上游没有竞品价模块）、
 * 可售天数也是人工登记（上游 7 库存 / 09 订单没有可读端点）、
 * 平台费率与物流单价沿用 5.3.1.1 的手工假设机制。
 */
export function PricingTemplateWorkspace() {
  const [capabilities, setCapabilities] = useState<PricingTemplateCapabilities | null>(null);
  const [directory, setDirectory] = useState<TemplateDirectory | null>(null);
  const [sites, setSites] = useState<SiteOption[]>([]);
  const [templates, setTemplates] = useState<TemplateSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [selected, setSelected] = useState<TemplateDetail | null>(null);
  const [form, setForm] = useState<TemplateInput>(emptyTemplate());
  const [editing, setEditing] = useState<string | null>(null);
  const [applyForm, setApplyForm] = useState<ApplyRequest>(emptyApply());
  const [result, setResult] = useState<ApplyResult | null>(null);
  const [applications, setApplications] = useState<ApplicationSummary[]>([]);
  const [keyword, setKeyword] = useState('');
  const [stageFilter, setStageFilter] = useState('');
  const [methodFilter, setMethodFilter] = useState('');
  const [problem, setProblem] = useState<ValidationError | null>(null);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);

  const load = useCallback(async () => {
    try {
      const [caps, dir, siteList, page, ledger] = await Promise.all([
        fetchPricingTemplateCapabilities(),
        fetchPricingTemplateDirectory().catch(() => null),
        fetchPricingTemplateSites().catch(() => [] as SiteOption[]),
        fetchPricingTemplates({ size: 50 }).catch(() => null),
        fetchPricingTemplateApplications({ size: 20 }).catch(() => null),
      ]);
      setCapabilities(caps);
      if (dir) setDirectory(dir);
      setSites(siteList);
      if (page) {
        setTemplates(page.items);
        setTotal(page.total);
      }
      if (ledger) setApplications(ledger.items);
    } catch (e) {
      setError(e instanceof Error ? e.message : '定价模板读不到');
    } finally {
      setLoaded(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const patch = useCallback((next: Partial<TemplateInput>) => {
    setForm((prev) => ({ ...prev, ...next }));
    setProblem(null);
  }, []);

  const patchApply = useCallback((next: Partial<ApplyRequest>) => {
    setApplyForm((prev) => ({ ...prev, ...next }));
    setProblem(null);
  }, []);

  async function search() {
    setBusy(true);
    setError('');
    try {
      const page = await fetchPricingTemplates({
        keyword: keyword || undefined,
        productStage: stageFilter ? (stageFilter as 'NEW' | 'EXISTING') : null,
        baseMethod: methodFilter ? (methodFilter as 'COST_PLUS') : null,
        size: 50,
      });
      setTemplates(page.items);
      setTotal(page.total);
    } catch (e) {
      setError(e instanceof Error ? e.message : '模板检索失败');
    } finally {
      setBusy(false);
    }
  }

  async function open(id: string) {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const detail = await fetchPricingTemplate(id);
      setSelected(detail);
      setForm(toTemplateInput(detail));
      setEditing(id);
      setApplyForm((prev) => ({ ...prev, siteCode: prev.siteCode }));
    } catch (e) {
      setError(e instanceof Error ? e.message : '模板详情读不到');
    } finally {
      setBusy(false);
    }
  }

  async function save() {
    const invalid = validateTemplate(form);
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setBusy(true);
    setError('');
    try {
      const saved = editing
        ? await updatePricingTemplate(editing, form)
        : await createPricingTemplate(form);
      setSelected(saved);
      setForm(toTemplateInput(saved));
      setEditing(saved.summary.id);
      setNotice(editing ? '模板已更新（调整项与适用对象整体替换）。' : '模板已创建。');
      await reloadList();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function reloadList() {
    const page = await fetchPricingTemplates({ size: 50 }).catch(() => null);
    if (page) {
      setTemplates(page.items);
      setTotal(page.total);
    }
    const ledger = await fetchPricingTemplateApplications({ size: 20 }).catch(() => null);
    if (ledger) setApplications(ledger.items);
  }

  async function remove() {
    if (!editing) return;
    setBusy(true);
    setError('');
    try {
      await deletePricingTemplate(editing);
      setSelected(null);
      setEditing(null);
      setForm(emptyTemplate());
      setNotice('模板已删除。');
      await reloadList();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    } finally {
      setBusy(false);
    }
  }

  async function runApply() {
    if (!selected) {
      setError('先在左边选一条模板，再应用。');
      return;
    }
    const invalid = validateApply(applyForm, hasTurnoverRule(selected));
    if (invalid) {
      setProblem(invalid);
      return;
    }
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const applied = await applyPricingTemplate(selected.summary.id, applyForm);
      setResult(applied);
      setNotice(
        applied.decisionReady
          ? '已按模板算出建议价（**建议价不是生效价**，改价走 5.4.1.1 调价单）。'
          : '已算出建议价，但成本不完整 —— 毛利率不可信，缺口清单见下方。',
      );
      await reloadList();
    } catch (e) {
      setError(e instanceof Error ? e.message : '应用失败');
    } finally {
      setBusy(false);
    }
  }

  const stats = useMemo(() => summaryStats(result), [result]);
  const gaps = useMemo(() => missingGaps(result), [result]);
  const assumptions = useMemo(() => assumptionGaps(result), [result]);

  if (!loaded) {
    return (
      <div className="space-y-4">
        <PageHeader title="定价模板" description="模板是「怎么算」，价目表是「算完是多少」。" />
        <Notice tone="info" title="加载中">
          正在读取定价模板…
        </Notice>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="定价模板"
        description="沉淀定价策略模板（成本加成 / 固定毛利 / 竞品跟随 + 站点差异化 / 库存周转调整），一键应用到商品生成建议价。"
        actions={
          <Button
            variant="outline"
            onClick={() => {
              setEditing(null);
              setSelected(null);
              setForm(emptyTemplate());
              setProblem(null);
            }}
          >
            新建模板
          </Button>
        }
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
        description="基准方法 / 调整项 / 适用对象 / 商品阶段，以及上游的真实交付状态。"
      >
        {capabilities ? (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-4">
              {capabilities.baseMethods.map((item) => (
                <div key={item.code} className="border-border rounded-md border p-3">
                  <div className="flex items-center gap-2">
                    <Badge tone="brand">{item.label}</Badge>
                    <span className="text-copy-12 text-muted-foreground">{item.code}</span>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-2">{item.description}</p>
                </div>
              ))}
            </div>
            <div className="grid gap-3 md:grid-cols-2">
              {capabilities.adjustmentTypes.map((item) => (
                <div key={item.code} className="border-border rounded-md border p-3">
                  <Badge tone="info">{item.label}</Badge>
                  <p className="text-copy-12 text-muted-foreground mt-2">{item.description}</p>
                </div>
              ))}
            </div>
            <Notice tone="warning" title="建议价不是生效价">
              {capabilities.priceListNote} {capabilities.downstreamNote}
            </Notice>
            <Notice tone="info" title="上游与口径">
              {capabilities.upstreamNote} {capabilities.competitorNote} {capabilities.turnoverNote}{' '}
              {capabilities.assumptionNote} {capabilities.stageNote}
            </Notice>
          </div>
        ) : (
          <EmptyState title="能力声明读不到" description="请稍后重试。" />
        )}
      </SectionCard>

      <div className="grid gap-4 lg:grid-cols-3">
        {/* ---------------- 模板台账 ---------------- */}
        <SectionCard
          className="lg:col-span-1"
          title="模板台账"
          description={`共 ${total} 条模板（真分页 + 筛选）。`}
        >
          <div className="space-y-3">
            <FormField id="pt-keyword" label="关键词（编码 / 名称）">
              <Input
                id="pt-keyword"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                placeholder="例如 OLD-PLUS-30"
              />
            </FormField>
            <div className="grid grid-cols-2 gap-2">
              <FormField id="pt-stage" label="商品阶段">
                <Input
                  id="pt-stage"
                  value={stageFilter}
                  onChange={(e) => setStageFilter(e.target.value)}
                  placeholder="NEW / EXISTING"
                />
              </FormField>
              <FormField id="pt-method" label="基准方法">
                <Input
                  id="pt-method"
                  value={methodFilter}
                  onChange={(e) => setMethodFilter(e.target.value)}
                  placeholder="COST_PLUS"
                />
              </FormField>
            </div>
            <Button onClick={() => void search()} disabled={busy}>
              检索
            </Button>
          </div>

          <div className="mt-4 space-y-2">
            {templates.length === 0 ? (
              <EmptyState
                title="还没有定价模板"
                description="模板是「怎么算」：先把基准方法与调整项定下来，再一键应用到商品。"
                action={
                  <Button
                    onClick={() => {
                      setEditing(null);
                      setSelected(null);
                      setForm(emptyTemplate());
                    }}
                  >
                    新建模板
                  </Button>
                }
              />
            ) : (
              templates.map((item) => (
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
                    <span>· {baseMethodLabel(item.baseMethod)}</span>
                    <span>· {productStageLabel(item.productStage)}</span>
                    <span>· {baseRateText(item.baseMethod, item.baseRate)}</span>
                  </div>
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    调整项 {item.adjustmentCount} 条 · 适用对象 {item.scopeCount} 条 ·{' '}
                    {item.currency}
                  </div>
                </button>
              ))
            )}
          </div>
        </SectionCard>

        {/* ---------------- 模板表单 + 详情 ---------------- */}
        <SectionCard
          className="lg:col-span-2"
          title={editing ? `编辑模板：${form.name || form.code}` : '新建模板'}
          description="基准 + 调整项 + 适用对象 + 口径假设。"
          extra={
            editing ? (
              <Button variant="destructive" size="sm" onClick={() => void remove()} disabled={busy}>
                删除
              </Button>
            ) : undefined
          }
        >
          <div className="grid gap-3 md:grid-cols-3">
            <FormField id="pt-code" label="模板编码" hint="租户内唯一" required>
              <Input
                id="pt-code"
                value={form.code}
                onChange={(e) => patch({ code: e.target.value.toUpperCase() })}
              />
            </FormField>
            <FormField id="pt-name" label="模板名称" required>
              <Input id="pt-name" value={form.name} onChange={(e) => patch({ name: e.target.value })} />
            </FormField>
            <FormField id="pt-stage-field" label="商品阶段" hint="新品与老品用不同模板">
              <Input
                id="pt-stage-field"
                value={form.productStage}
                onChange={(e) =>
                  patch({ productStage: e.target.value.toUpperCase() as 'NEW' | 'EXISTING' })
                }
              />
            </FormField>
          </div>

          <div className="mt-3 grid gap-3 md:grid-cols-4">
            <FormField id="pt-method-field" label="基准方法" hint="成本加成 / 固定毛利 / 竞品跟随">
              <Input
                id="pt-method-field"
                value={form.baseMethod}
                onChange={(e) =>
                  patch({ baseMethod: e.target.value.toUpperCase() as 'COST_PLUS' | 'FIXED_MARGIN' | 'COMPETITOR_FOLLOW' })
                }
              />
            </FormField>
            <FormField
              id="pt-rate"
              label="基准率（0.25 = 25%）"
              hint="加成率 / 目标毛利率 / 跟随率"
              error={problem?.field === 'baseRate' ? problem.message : undefined}
            >
              <Input
                id="pt-rate"
                type="number"
                step="0.01"
                value={form.baseRate ?? ''}
                onChange={(e) =>
                  patch({ baseRate: e.target.value === '' ? null : Number(e.target.value) })
                }
              />
            </FormField>
            <FormField
              id="pt-competitor"
              label="竞品基准价"
              hint="仅竞品跟随；人工登记"
              error={problem?.field === 'competitorReferencePrice' ? problem.message : undefined}
            >
              <Input
                id="pt-competitor"
                type="number"
                step="0.01"
                value={form.competitorReferencePrice ?? ''}
                onChange={(e) =>
                  patch({
                    competitorReferencePrice:
                      e.target.value === '' ? null : Number(e.target.value),
                  })
                }
              />
            </FormField>
            <FormField id="pt-currency" label="建议价币种" required>
              <Input
                id="pt-currency"
                value={form.currency}
                onChange={(e) => patch({ currency: e.target.value.toUpperCase() })}
              />
            </FormField>
          </div>

          <div className="border-border mt-4 rounded-md border p-3">
            <p className="text-label-13 font-semibold">口径假设（5.3.1.1 的手工假设机制）</p>
            <p className="text-copy-12 text-muted-foreground mt-1">
              平台费率 / 物流单价 / 汇率在上游规则模块接管前只能由人填；
              留空 = 「取不到」，**不是 0**。
            </p>
            <div className="mt-3 grid gap-3 md:grid-cols-5">
              <FormField id="pt-fee-rate" label="平台费率（0.15 = 15%）" hint="5.2.3.1">
                <Input
                  id="pt-fee-rate"
                  type="number"
                  step="0.01"
                  value={form.platformFeeRate ?? ''}
                  onChange={(e) =>
                    patch({ platformFeeRate: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
              <FormField id="pt-fee-fixed" label="平台固定费" hint="5.2.3.1">
                <Input
                  id="pt-fee-fixed"
                  type="number"
                  step="0.01"
                  value={form.platformFeeFixed ?? ''}
                  onChange={(e) =>
                    patch({ platformFeeFixed: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
              <FormField id="pt-logistics" label="物流每单位运费" hint="5.2.3.2">
                <Input
                  id="pt-logistics"
                  type="number"
                  step="0.01"
                  value={form.logisticsCostPerUnit ?? ''}
                  onChange={(e) =>
                    patch({
                      logisticsCostPerUnit: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </FormField>
              <FormField id="pt-cost-currency" label="成本币种" hint="空 = 与售价同币种">
                <Input
                  id="pt-cost-currency"
                  value={form.costCurrency ?? ''}
                  onChange={(e) =>
                    patch({ costCurrency: e.target.value ? e.target.value.toUpperCase() : null })
                  }
                />
              </FormField>
              <FormField
                id="pt-fx"
                label="汇率（1 成本币种 = X 售价币种）"
                hint="5.2.2.1；跨币种必填"
                error={problem?.field === 'fxRate' ? problem.message : undefined}
              >
                <Input
                  id="pt-fx"
                  type="number"
                  step="0.0001"
                  value={form.fxRate ?? ''}
                  onChange={(e) => patch({ fxRate: e.target.value === '' ? null : Number(e.target.value) })}
                />
              </FormField>
            </div>
          </div>

          <div className="border-border mt-4 rounded-md border p-3">
            <div className="flex items-center justify-between">
              <p className="text-label-13 font-semibold">调整项</p>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  onClick={() =>
                    patch({
                      adjustments: [
                        ...form.adjustments,
                        {
                          type: 'SITE_DIFFERENTIAL',
                          siteCode: '',
                          deltaRate: 0,
                          daysCoverThreshold: null,
                          discountRate: null,
                          note: '',
                        },
                      ],
                    })
                  }
                >
                  加站点差异化
                </Button>
                <Button
                  size="sm"
                  onClick={() =>
                    patch({
                      adjustments: [
                        ...form.adjustments,
                        {
                          type: 'INVENTORY_TURNOVER',
                          siteCode: null,
                          deltaRate: null,
                          daysCoverThreshold: 90,
                          discountRate: 0.1,
                          note: '',
                        },
                      ],
                    })
                  }
                >
                  加库存周转
                </Button>
              </div>
            </div>
            {problem?.field === 'adjustments' ? (
              <p className="text-copy-12 text-destructive mt-2">{problem.message}</p>
            ) : null}
            <div className="mt-2 space-y-2">
              {form.adjustments.length === 0 ? (
                <p className="text-copy-12 text-muted-foreground">
                  还没有调整项 —— 模板可以只用一个基准方法。
                </p>
              ) : (
                form.adjustments.map((adjustment, index) => (
                  <div key={index} className="flex flex-wrap items-end gap-2">
                    <Badge tone="info">
                      {adjustment.type === 'SITE_DIFFERENTIAL' ? '按站点差异化' : '按库存周转调整'}
                    </Badge>
                    {adjustment.type === 'SITE_DIFFERENTIAL' ? (
                      <>
                        <FormField id={`pt-adj-site-${index}`} label="站点码">
                          <Input
                            id={`pt-adj-site-${index}`}
                            value={adjustment.siteCode ?? ''}
                            onChange={(e) => {
                              const next = [...form.adjustments];
                              next[index] = { ...adjustment, siteCode: e.target.value.toUpperCase() };
                              patch({ adjustments: next });
                            }}
                          />
                        </FormField>
                        <FormField id={`pt-adj-delta-${index}`} label="基准率增量">
                          <Input
                            id={`pt-adj-delta-${index}`}
                            type="number"
                            step="0.01"
                            value={adjustment.deltaRate ?? ''}
                            onChange={(e) => {
                              const next = [...form.adjustments];
                              next[index] = {
                                ...adjustment,
                                deltaRate: e.target.value === '' ? null : Number(e.target.value),
                              };
                              patch({ adjustments: next });
                            }}
                          />
                        </FormField>
                      </>
                    ) : (
                      <>
                        <FormField id={`pt-adj-days-${index}`} label="可售天数阈值">
                          <Input
                            id={`pt-adj-days-${index}`}
                            type="number"
                            step="1"
                            value={adjustment.daysCoverThreshold ?? ''}
                            onChange={(e) => {
                              const next = [...form.adjustments];
                              next[index] = {
                                ...adjustment,
                                daysCoverThreshold:
                                  e.target.value === '' ? null : Number(e.target.value),
                              };
                              patch({ adjustments: next });
                            }}
                          />
                        </FormField>
                        <FormField id={`pt-adj-discount-${index}`} label="滞销折扣率">
                          <Input
                            id={`pt-adj-discount-${index}`}
                            type="number"
                            step="0.01"
                            value={adjustment.discountRate ?? ''}
                            onChange={(e) => {
                              const next = [...form.adjustments];
                              next[index] = {
                                ...adjustment,
                                discountRate: e.target.value === '' ? null : Number(e.target.value),
                              };
                              patch({ adjustments: next });
                            }}
                          />
                        </FormField>
                      </>
                    )}
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        patch({
                          adjustments: form.adjustments.filter((_, i) => i !== index),
                        })
                      }
                    >
                      移除
                    </Button>
                  </div>
                ))
              )}
            </div>
          </div>

          <div className="border-border mt-4 rounded-md border p-3">
            <div className="flex items-center justify-between">
              <p className="text-label-13 font-semibold">适用对象（分类 / 品牌 / 店铺）</p>
              <Button
                size="sm"
                onClick={() =>
                  patch({
                    scopes: [
                      ...form.scopes,
                      {
                        type: 'CATEGORY',
                        ref: directory?.categories[0]?.code ?? '',
                        platformCode: null,
                      },
                    ],
                  })
                }
              >
                加一条
              </Button>
            </div>
            {problem?.field === 'scopes' ? (
              <p className="text-copy-12 text-destructive mt-2">{problem.message}</p>
            ) : null}
            <div className="mt-2 space-y-2">
              {form.scopes.length === 0 ? (
                <p className="text-copy-12 text-muted-foreground">
                  不配适用对象 = 适用于全租户（显式语义，不是漏填）。
                </p>
              ) : (
                form.scopes.map((scope, index) => (
                  <div key={index} className="flex flex-wrap items-end gap-2">
                    <FormField id={`pt-scope-type-${index}`} label="类型">
                      <Input
                        id={`pt-scope-type-${index}`}
                        value={scope.type}
                        onChange={(e) => {
                          const next = [...form.scopes];
                          next[index] = {
                            ...scope,
                            type: e.target.value.toUpperCase() as 'CATEGORY' | 'BRAND' | 'SHOP',
                          };
                          patch({ scopes: next });
                        }}
                      />
                    </FormField>
                    <FormField id={`pt-scope-ref-${index}`} label="取值（分类码 / 品牌码 / 站点码）">
                      <Input
                        id={`pt-scope-ref-${index}`}
                        value={scope.ref}
                        onChange={(e) => {
                          const next = [...form.scopes];
                          next[index] = { ...scope, ref: e.target.value };
                          patch({ scopes: next });
                        }}
                      />
                    </FormField>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() =>
                        patch({ scopes: form.scopes.filter((_, i) => i !== index) })
                      }
                    >
                      移除
                    </Button>
                  </div>
                ))
              )}
            </div>
            {directory ? (
              <p className="text-copy-12 text-muted-foreground mt-2">
                可选分类 {directory.categories.length} 个、品牌 {directory.brands.length} 个。
                {directory.sourceNote}
              </p>
            ) : null}
          </div>

          <div className="mt-4 flex gap-2">
            <Button onClick={() => void save()} disabled={busy}>
              {editing ? '保存修改' : '创建模板'}
            </Button>
            <Button
              variant="outline"
              onClick={() => {
                setEditing(null);
                setSelected(null);
                setForm(emptyTemplate());
                setProblem(null);
              }}
            >
              取消
            </Button>
          </div>
        </SectionCard>
      </div>

      {/* ---------------- 一键应用 ---------------- */}
      <SectionCard
        title="一键应用到商品（生成建议价）"
        description="建议价的每一个数字都来自 5.3.1.1 的测算；成本不完整时后端会拒绝给价。"
        extra={selected ? <Badge tone="brand">{selected.summary.code}</Badge> : undefined}
      >
        {!selected ? (
          <EmptyState
            title="先选一条模板"
            description="模板是「怎么算」：选好模板之后，这里按它的基准与调整项算出建议价。"
          />
        ) : (
          <div className="space-y-4">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="pt-apply-sku" label="SKU" required error={problem?.field === 'skuId' ? problem.message : undefined}>
                <Input
                  id="pt-apply-sku"
                  value={applyForm.skuId}
                  onChange={(e) => patchApply({ skuId: e.target.value.trim() })}
                />
              </FormField>
              <FormField id="pt-apply-site" label="目标店铺（站点码）" hint="站点差异化按它匹配">
                <Input
                  id="pt-apply-site"
                  value={applyForm.siteCode ?? ''}
                  onChange={(e) => patchApply({ siteCode: e.target.value.toUpperCase() || null })}
                />
              </FormField>
              <FormField id="pt-apply-qty" label="数量" hint="用于阶梯与运费">
                <Input
                  id="pt-apply-qty"
                  type="number"
                  step="1"
                  value={applyForm.quantity ?? ''}
                  onChange={(e) =>
                    patchApply({ quantity: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
              <FormField
                id="pt-apply-days"
                label="当前可售天数"
                hint="库存周转调整用；上游未提供可读端点，人工填"
                error={problem?.field === 'daysOfCover' ? problem.message : undefined}
              >
                <Input
                  id="pt-apply-days"
                  type="number"
                  step="1"
                  value={applyForm.daysOfCover ?? ''}
                  onChange={(e) =>
                    patchApply({ daysOfCover: e.target.value === '' ? null : Number(e.target.value) })
                  }
                />
              </FormField>
            </div>

            {sites.length ? (
              <p className="text-copy-12 text-muted-foreground">
                可选店铺：{sites.map((site) => site.siteCode).join('、')}（来自 2.1.1.1 平台目录）。
              </p>
            ) : (
              <p className="text-copy-12 text-muted-foreground">
                平台目录读不到（上游不可达）—— 可以手工填站点码。
              </p>
            )}

            <Button onClick={() => void runApply()} disabled={busy}>
              生成建议价
            </Button>

            {result ? (
              <div className="space-y-3">
                <div className="grid gap-3 md:grid-cols-3">
                  {stats.map((stat) => (
                    <StatCard
                      key={stat.label}
                      label={stat.label}
                      value={stat.value}
                      polarity="neutral"
                    />
                  ))}
                </div>
                <Notice
                  tone={decisionTone(result.decisionReady)}
                  title={result.decisionReady ? '建议价可用（仍需 5.4.1.1 走调价单）' : '成本不完整'}
                >
                  {result.decisionReady
                    ? '建议价与毛利率都来自 5.3.1.1 的测算；改价走 5.4.1.1 调价单（含审批与平台回写）。'
                    : '缺项算出来的毛利率是虚高的 —— 后端已拒绝给出不可信的数字。'}
                </Notice>
                <div>
                  <p className="text-label-13 font-semibold">命中的调整项</p>
                  <div className="mt-2 space-y-1">
                    {result.appliedAdjustments.map((adjustment) => (
                      <div key={adjustment.type} className="flex flex-wrap items-center gap-2">
                        <Badge tone={adjustmentTone(adjustment.applied)}>
                          {adjustment.applied ? '命中' : '未命中'} · {adjustment.typeLabel}
                        </Badge>
                        <span className="text-copy-12 text-muted-foreground">{adjustment.reason}</span>
                      </div>
                    ))}
                  </div>
                </div>
                {gaps.length ? (
                  <Notice tone="danger" title={`缺口 ${gaps.length} 项`}>
                    <ul className="list-disc pl-4">
                      {gaps.map((gap) => (
                        <li key={gap.label}>
                          {gap.label}（归 {gap.ownerModule} 管）：{gap.impact}
                        </li>
                      ))}
                    </ul>
                  </Notice>
                ) : null}
                {assumptions.length ? (
                  <Notice tone="warning" title="含手工假设">
                    <ul className="list-disc pl-4">
                      {assumptions.map((gap) => (
                        <li key={gap.label}>
                          {gap.label}（归 {gap.ownerModule} 管）：{gap.impact}
                        </li>
                      ))}
                    </ul>
                  </Notice>
                ) : null}
                {result.calculation ? (
                  <div>
                    <p className="text-label-13 font-semibold">建议价的依据（5.3.1.1 的利润拆解）</p>
                    <div className="mt-2 space-y-1">
                      {result.calculation.waterfall.map((line) => (
                        <div key={line.code} className="flex flex-wrap items-center gap-2">
                          <span className="text-copy-12 w-32">{line.label}</span>
                          <Badge tone={line.available ? 'success' : 'danger'}>
                            {line.available ? '已计入' : (line.unavailableReason ?? '未计入')}
                          </Badge>
                          <span className="text-copy-12 text-muted-foreground">
                            {moneyWithCurrency(line.amount, result.currency, '未计入')}
                          </span>
                        </div>
                      ))}
                    </div>
                  </div>
                ) : null}
              </div>
            ) : null}
          </div>
        )}
      </SectionCard>

      {/* ---------------- 应用留痕 ---------------- */}
      <SectionCard title="应用留痕" description="每次「一键应用」都留一条：哪个模板、算出多少建议价。">
        {applications.length === 0 ? (
          <EmptyState title="还没有应用记录" description="应用一次模板就会在这里留下痕迹。" />
        ) : (
          <div className="space-y-2">
            {applications.map((item) => (
              <div key={item.id} className="border-border flex flex-wrap items-center gap-2 rounded-md border p-3">
                <Badge tone="brand">{item.templateCode}</Badge>
                <span className="text-copy-12">{item.skuCode ?? item.skuId}</span>
                <span className="text-copy-12 text-muted-foreground">
                  {item.siteCode || '未限定店铺'} · 数量 {quantityText(item.quantity)} ·{' '}
                  {item.currency}
                </span>
                <span className="text-copy-12">
                  建议价 {moneyWithCurrency(item.suggestedPrice, item.currency, '未给出')}
                </span>
                <span className="text-copy-12 text-muted-foreground">
                  毛利率 {percentText(item.grossMarginRate, '不可信（成本不完整）')}
                </span>
                <Badge tone={decisionTone(item.decisionReady)}>
                  {item.decisionReady ? '口径完整' : '成本不完整'}
                </Badge>
              </div>
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  );
}
