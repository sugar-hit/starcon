'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CheckCircle2,
  FileText,
  Loader2,
  PackagePlus,
  RefreshCw,
  Save,
  ShieldCheck,
  TriangleAlert,
  Wand2,
} from 'lucide-react';
import {
  applyTemplate,
  buildApplyRequest,
  buildTemplateRequest,
  deleteTemplate,
  emptyTemplateDraft,
  emptyTemplatesMessage,
  evaluationStatusLabel,
  evaluationTone,
  fetchListingCandidates,
  fetchTemplate,
  fetchTemplateApplies,
  fetchTemplateCapabilities,
  fetchTemplateFilterOptions,
  fetchTemplateMatches,
  fetchTemplates,
  FIELD_OPTIONS,
  GROUP_KIND_OPTIONS,
  replayNote,
  ruleSummary,
  saveTemplate,
  SCOPE_OPTIONS,
  setTemplateStatus,
  SOURCE_OPTIONS,
  sourcesAllowedFor,
  sourceKindLabel,
  statusLabel,
  statusTone,
  toTemplateDraft,
  unavailableDimensions,
  validationSummary,
  valueLines,
  type ApplyDraft,
  type ListingTemplateApplyResult,
  type ListingTemplateCapabilities,
  type ListingTemplateDetail,
  type ListingTemplateFilterOptions,
  type ListingTemplateRow,
  type TemplateDraft,
} from './listing-template-model';

/**
 * 4.2.2.1 刊登模板 工作区。
 *
 * 蓝图：「沉淀可复用的刊登模板（类目级、店铺级），保存字段填充规则（取自商品属性、引用文案库、套用图片组），一键套用到目标商品。输入：模板
 * 定义（字段规则 + 图片组 + 必填校验）；套用目标。输出：模板记录；套用后的内容草稿。关系：上游 = 03 文案资产、03 媒体库；下游 = 4.2.1.1
 * 编辑器。<b>模板引用的是"取值规则"而非一次性拷贝</b>，商品改价或换图后套用结果跟着变。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **模板编辑面板里没有「当前值」输入框**。只有「来源引用 + 取值表达式」——
 *    页面形状本身就不许把值抄进模板（后端还有 DB 层的标识符约束兜底）。
 * 2. **没有「直接上线」入口**。套用结果恒显示为草稿，发布归 4.3.1.2。
 * 3. **「不知道」是一个状态**。SOURCE_MISSING / UNKNOWN_SOURCE 单独成态并点名归属模块，
 *    不会被渲染成「空值」或 0。
 * 4. **必填校验是算出来的**。违规清单指到具体字段与具体规则（形如 `PRODUCT_ATTRIBUTE:price`）。
 * 5. **重复套用的语义写在页面上**。带幂等键 = 返回第一次的结果；不带 = 覆盖草稿。
 */
export function ListingTemplateWorkspace() {
  const [caps, setCaps] = useState<ListingTemplateCapabilities | null>(null);
  const [options, setOptions] = useState<ListingTemplateFilterOptions | null>(null);
  const [templates, setTemplates] = useState<ListingTemplateRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [filters, setFilters] = useState({
    scope: '',
    platformCode: '',
    shopCode: '',
    status: '',
    keyword: '',
  });

  const [draft, setDraft] = useState<TemplateDraft>(emptyTemplateDraft);
  const [editing, setEditing] = useState(false);
  const [detail, setDetail] = useState<ListingTemplateDetail | null>(null);
  const [matches, setMatches] = useState<Awaited<ReturnType<typeof fetchTemplateMatches>> | null>(
    null,
  );

  const [applyDraft, setApplyDraft] = useState<ApplyDraft>({
    templateId: '',
    listingId: '',
    idempotencyKey: '',
    note: '',
  });
  const [search, setSearch] = useState('');
  const [candidates, setCandidates] = useState<
    { id: string; externalItemId: string; title: string; shopCode: string; platformCode: string }[]
  >([]);
  const [result, setResult] = useState<ListingTemplateApplyResult | null>(null);
  const [applies, setApplies] = useState<Awaited<ReturnType<typeof fetchTemplateApplies>> | null>(
    null,
  );

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const PAGE_SIZE = 20;

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, optionsResult, list, applyList] = await Promise.all([
        fetchTemplateCapabilities(),
        fetchTemplateFilterOptions(),
        fetchTemplates({ ...filters, page, size: PAGE_SIZE }),
        fetchTemplateApplies(undefined, undefined, 0, 20),
      ]);
      setCaps(capsResult);
      setOptions(optionsResult);
      setTemplates(list.items);
      setTotal(list.total);
      setApplies(applyList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [filters, page]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const openTemplate = useCallback(
    (id: string) =>
      run(async () => {
        const [loaded, matched] = await Promise.all([
          fetchTemplate(id),
          fetchTemplateMatches(id, 0, PAGE_SIZE),
        ]);
        setDetail(loaded);
        setMatches(matched);
        setDraft(toTemplateDraft(loaded));
        setEditing(false);
        setApplyDraft((prev) => ({ ...prev, templateId: id }));
        setResult(null);
      }),
    [run],
  );

  const searchCandidates = useCallback(async () => {
    try {
      setCandidates(await fetchListingCandidates(search));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载候选商品失败，请重试');
    }
  }, [search]);

  const payload = useMemo(() => buildTemplateRequest(draft), [draft]);
  const applyPayload = useMemo(() => buildApplyRequest(applyDraft), [applyDraft]);

  const platformOptions = useMemo(() => options?.platforms ?? [], [options]);
  const marketplaceOptions = useMemo(() => {
    const list = options?.marketplaces ?? [];
    return draft.platformId ? list.filter((m) => m.platformId === draft.platformId) : list;
  }, [options, draft.platformId]);
  const shopOptions = useMemo(() => options?.shops ?? [], [options]);

  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-muted-foreground">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载刊登模板…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="刊登模板">
        <EmptyState
          icon={<TriangleAlert className="size-8 text-destructive" />}
          title="刊登模板加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="刊登模板"
        description="类目级 / 店铺级的字段填充规则：取自商品属性、引用文案库、套用图片组，一键套用到目标商品。模板存的是取值规则，不是值的快照。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}
      {notice && (
        <div className="border-brand/40 bg-brand-soft/40 text-brand flex items-start gap-2 rounded-lg border px-4 py-3">
          <CheckCircle2 className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{notice}</p>
        </div>
      )}

      {/* ---- 三条边界的界面措辞（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground mb-1 text-label-13 font-medium">刊登模板的三条硬边界</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            <li className="flex items-start gap-2">
              <Wand2 className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.rulesOnlyNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <ShieldCheck className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.draftNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <PackagePlus className="mt-0.5 size-3.5 shrink-0 text-brand" />
              <span>{caps.idempotencyNote}</span>
            </li>
          </ul>
        </div>
      )}

      {/* ---- 未接入维度明账 ---- */}
      {caps && unavailableDimensions(caps).length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning mb-1 text-label-13 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground space-y-1 text-label-12">
            {unavailableDimensions(caps).map((d) => (
              <li key={d.dimension}>
                <span className="text-foreground">{d.description}</span>
                ：归 {d.ownerModule} —— {d.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 概览 ---- */}
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="模板数" value={total} />
        <StatCard label="字段规则" value={detail ? detail.fields.length : 0} />
        <StatCard
          label="必填违规"
          value={result ? result.violationCount : 0}
          comparisonLabel={result ? validationSummary(result) : '还没有套用记录'}
        />
      </div>

      {/* ---- 模板列表（真分页 + 真筛选） ---- */}
      <SectionCard
        title="模板列表"
        description="按作用域 / 平台 / 店铺 / 状态 / 关键字筛选，点一条看它的规则与适用范围。"
        extra={
          <div className="flex items-center gap-2">
            <Button
              variant="ghost"
              size="sm"
              onClick={() =>
                setDraft((prev) => {
                  const next = emptyTemplateDraft();
                  next.platformId = prev.platformId;
                  return next;
                })
              }
              disabled={busy}
            >
              <FileText className="mr-2 size-4" />
              新建模板
            </Button>
            <Button variant="ghost" size="sm" onClick={() => void refresh()} disabled={busy}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
          </div>
        }
      >
        <div className="space-y-3">
          <div className="grid gap-2 sm:grid-cols-5">
            <select
              aria-label="适用范围"
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={filters.scope}
              onChange={(e) => {
                setPage(0);
                setFilters((prev) => ({ ...prev, scope: e.target.value }));
              }}
            >
              <option value="">全部作用域</option>
              {SCOPE_OPTIONS.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <select
              aria-label="平台"
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={filters.platformCode}
              onChange={(e) => {
                setPage(0);
                setFilters((prev) => ({ ...prev, platformCode: e.target.value }));
              }}
            >
              <option value="">全部平台</option>
              {platformOptions.map((p) => (
                <option key={p.id} value={p.code}>
                  {p.name}
                </option>
              ))}
            </select>
            <select
              aria-label="店铺"
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={filters.shopCode}
              onChange={(e) => {
                setPage(0);
                setFilters((prev) => ({ ...prev, shopCode: e.target.value }));
              }}
            >
              <option value="">全部店铺</option>
              {(options?.shops ?? []).map((s) => (
                <option key={s.id} value={s.code}>
                  {s.name}
                </option>
              ))}
            </select>
            <select
              aria-label="状态"
              className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
              value={filters.status}
              onChange={(e) => {
                setPage(0);
                setFilters((prev) => ({ ...prev, status: e.target.value }));
              }}
            >
              <option value="">全部状态</option>
              <option value="DRAFT">未启用</option>
              <option value="ACTIVE">可套用</option>
              <option value="DISABLED">已停用</option>
            </select>
            <Input
              placeholder="按编码或名称搜索"
              value={filters.keyword}
              onChange={(e) => {
                setPage(0);
                setFilters((prev) => ({ ...prev, keyword: e.target.value }));
              }}
            />
          </div>

          {templates.length === 0 ? (
            <EmptyState title="没有匹配的模板" description={emptyTemplatesMessage()} />
          ) : (
            <ul className="space-y-1">
              {templates.map((t) => (
                <li key={t.id}>
                  <button
                    type="button"
                    onClick={() => void openTemplate(t.id)}
                    className="border-border hover:border-foreground/40 hover:text-foreground text-muted-foreground w-full rounded-md border px-3 py-2 text-left"
                  >
                    <span className="text-foreground text-label-13">
                      {t.name}
                      <span className="text-muted-foreground">（{t.code}）</span>
                    </span>
                    <span className="text-muted-foreground ml-2 text-label-12">
                      {t.scope === 'CATEGORY' ? '类目级' : '店铺级'} · {t.platformCode} ·{' '}
                      {t.scope === 'CATEGORY' ? t.scopeTarget : t.scopeTarget} ·{' '}
                      {t.fieldCount} 条规则 · {t.requiredFieldCount} 条必填
                    </span>
                    <span className="ml-2">
                      <Badge tone={statusTone(t.status)}>{statusLabel(t.status)}</Badge>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          <div className="flex items-center justify-between">
            <span className="text-muted-foreground text-label-12">
              共 {total} 条，第 {page + 1} 页
            </span>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={page === 0 || busy}
                onClick={() => setPage((p) => Math.max(0, p - 1))}
              >
                上一页
              </Button>
              <Button
                variant="secondary"
                size="sm"
                disabled={(page + 1) * PAGE_SIZE >= total || busy}
                onClick={() => setPage((p) => p + 1)}
              >
                下一页
              </Button>
            </div>
          </div>
        </div>
      </SectionCard>

      {/* ---- 模板定义编辑 ---- */}
      <SectionCard
        title={editing ? '编辑模板定义' : '新建模板定义'}
        description="🔴 这里配的是「取值规则」：来源引用 + 取值表达式。模板里不存值 —— 商品改价或换图之后重新套用，产出的草稿会跟着变。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="block font-medium">模板编码</span>
              <Input
                value={draft.code}
                onChange={(e) => setDraft((prev) => ({ ...prev, code: e.target.value }))}
                placeholder="例：AMAZON-ELEC-BASE"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">模板名</span>
              <Input
                value={draft.name}
                onChange={(e) => setDraft((prev) => ({ ...prev, name: e.target.value }))}
                placeholder="例：亚马逊电子类目标准刊登模板"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">适用范围</span>
              <select
                className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                value={draft.scope}
                onChange={(e) =>
                  setDraft((prev) => ({ ...prev, scope: e.target.value, categoryPath: '', shopId: '' }))
                }
              >
                {SCOPE_OPTIONS.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label} —— {s.hint}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">平台</span>
              <select
                className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                value={draft.platformId}
                onChange={(e) =>
                  setDraft((prev) => ({
                    ...prev,
                    platformId: e.target.value,
                    marketplaceId: '',
                  }))
                }
              >
                <option value="">选择平台</option>
                {platformOptions.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}（{p.code}）
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">站点</span>
              <select
                className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                value={draft.marketplaceId}
                onChange={(e) => setDraft((prev) => ({ ...prev, marketplaceId: e.target.value }))}
              >
                <option value="">该平台全站点</option>
                {marketplaceOptions.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.code}
                  </option>
                ))}
              </select>
            </label>
            {draft.scope === 'CATEGORY' ? (
              <label className="space-y-1 text-sm">
                <span className="block font-medium">类目路径</span>
                <Input
                  value={draft.categoryPath}
                  onChange={(e) =>
                    setDraft((prev) => ({ ...prev, categoryPath: e.target.value }))
                  }
                  placeholder="例：Electronics > Audio"
                />
              </label>
            ) : (
              <label className="space-y-1 text-sm">
                <span className="block font-medium">店铺</span>
                <select
                  className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                  value={draft.shopId}
                  onChange={(e) => setDraft((prev) => ({ ...prev, shopId: e.target.value }))}
                >
                  <option value="">选择店铺</option>
                  {shopOptions.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}（{s.code}）
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label className="space-y-1 text-sm">
              <span className="block font-medium">语言</span>
              <Input
                value={draft.language}
                onChange={(e) => setDraft((prev) => ({ ...prev, language: e.target.value }))}
                placeholder="en-US"
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">状态</span>
              <select
                className="border-control-border bg-card h-9 w-full rounded-md border px-2 text-copy-13"
                value={draft.status}
                onChange={(e) => setDraft((prev) => ({ ...prev, status: e.target.value }))}
              >
                <option value="DRAFT">未启用</option>
                <option value="ACTIVE">可套用</option>
                <option value="DISABLED">已停用</option>
              </select>
            </label>
          </div>

          {/* ---- 字段规则 ---- */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">字段填充规则</p>
            <div className="space-y-2">
              {draft.fields.map((field, index) => {
                const option = FIELD_OPTIONS.find((o) => o.value === field.field);
                const allowed = (option?.sources ?? []).includes(field.sourceKind);
                return (
                  <div
                    key={`${field.field}-${index}`}
                    className="border-border space-y-2 rounded-md border px-3 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <select
                        aria-label="字段"
                        className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                        value={field.field}
                        onChange={(e) =>
                          setDraft((prev) => ({
                            ...prev,
                            fields: prev.fields.map((f, i) =>
                              i === index ? { ...f, field: e.target.value } : f,
                            ),
                          }))
                        }
                      >
                        {FIELD_OPTIONS.map((o) => (
                          <option key={o.value} value={o.value}>
                            {o.label}
                          </option>
                        ))}
                      </select>
                      <select
                        aria-label="取值来源"
                        className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                        value={field.sourceKind}
                        onChange={(e) =>
                          setDraft((prev) => ({
                            ...prev,
                            fields: prev.fields.map((f, i) =>
                              i === index ? { ...f, sourceKind: e.target.value } : f,
                            ),
                          }))
                        }
                      >
                        {SOURCE_OPTIONS.map((s) => (
                          <option key={s.value} value={s.value}>
                            {s.label}
                          </option>
                        ))}
                      </select>
                      {field.sourceKind !== 'LITERAL' && (
                        <Input
                          className="max-w-xs"
                          placeholder="来源引用（属性码 / 模板编码 / 图片组编码）"
                          value={field.sourceRef}
                          onChange={(e) =>
                            setDraft((prev) => ({
                              ...prev,
                              fields: prev.fields.map((f, i) =>
                                i === index ? { ...f, sourceRef: e.target.value } : f,
                              ),
                            }))
                          }
                        />
                      )}
                      <label className="text-muted-foreground flex items-center gap-1 text-label-12">
                        <input
                          type="checkbox"
                          checked={field.required}
                          onChange={(e) =>
                            setDraft((prev) => ({
                              ...prev,
                              fields: prev.fields.map((f, i) =>
                                i === index ? { ...f, required: e.target.checked } : f,
                              ),
                            }))
                          }
                        />
                        必填
                      </label>
                      <Button
                        variant="ghost"
                        size="sm"
                        disabled={draft.fields.length <= 1 || busy}
                        onClick={() =>
                          setDraft((prev) => ({
                            ...prev,
                            fields: prev.fields.filter((_, i) => i !== index),
                          }))
                        }
                      >
                        删除
                      </Button>
                    </div>
                    <Input
                      placeholder={
                        field.sourceKind === 'LITERAL'
                          ? '常量（多值字段按行拆）'
                          : '取值表达式：常量前后缀 + {{value}} 或 {{属性码}}'
                      }
                      value={field.valueExpression}
                      onChange={(e) =>
                        setDraft((prev) => ({
                          ...prev,
                          fields: prev.fields.map((f, i) =>
                            i === index ? { ...f, valueExpression: e.target.value } : f,
                          ),
                        }))
                      }
                    />
                    {!allowed && (
                      <p className="text-destructive text-label-12">
                        字段「{field.field}」不支持来源「{sourceKindLabel(field.sourceKind)}」。
                      </p>
                    )}
                  </div>
                );
              })}
            </div>
            <Button
              variant="secondary"
              size="sm"
              className="mt-2"
              disabled={busy}
              onClick={() =>
                setDraft((prev) => ({
                  ...prev,
                  fields: [
                    ...prev.fields,
                    {
                      field: 'BULLETS',
                      sourceKind: 'LITERAL',
                      sourceRef: '',
                      valueExpression: '',
                      required: false,
                      slotNo: prev.fields.length,
                    },
                  ],
                }))
              }
            >
              <PlusIcon /> 添加字段规则
            </Button>
          </div>

          {/* ---- 图片组 ---- */}
          <div>
            <p className="text-foreground mb-2 text-label-13 font-medium">
              图片组
              <span className="text-muted-foreground font-normal">
                （素材 id 指向 03 媒体库，跨服务只读；套用时解析成当前存储键）
              </span>
            </p>
            <div className="space-y-2">
              {draft.groups.map((group, index) => (
                <div key={`${group.groupCode}-${index}`} className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2">
                  <Input
                    className="max-w-40"
                    placeholder="组编码"
                    value={group.groupCode}
                    onChange={(e) =>
                      setDraft((prev) => ({
                        ...prev,
                        groups: prev.groups.map((g, i) =>
                          i === index ? { ...g, groupCode: e.target.value } : g,
                        ),
                      }))
                    }
                  />
                  <select
                    aria-label="取组方式"
                    className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                    value={group.groupKind}
                    onChange={(e) =>
                      setDraft((prev) => ({
                        ...prev,
                        groups: prev.groups.map((g, i) =>
                          i === index ? { ...g, groupKind: e.target.value } : g,
                        ),
                      }))
                    }
                  >
                    {GROUP_KIND_OPTIONS.map((g) => (
                      <option key={g.value} value={g.value}>
                        {g.label}
                      </option>
                    ))}
                  </select>
                  {group.groupKind === 'TAG' ? (
                    <Input
                      className="max-w-40"
                      placeholder="媒体库标签编码"
                      value={group.tagCode}
                      onChange={(e) =>
                        setDraft((prev) => ({
                          ...prev,
                          groups: prev.groups.map((g, i) =>
                            i === index ? { ...g, tagCode: e.target.value } : g,
                          ),
                        }))
                      }
                    />
                  ) : (
                    <Input
                      className="max-w-md"
                      placeholder="素材 id（逗号分隔）"
                      value={group.assetIds.join(',')}
                      onChange={(e) =>
                        setDraft((prev) => ({
                          ...prev,
                          groups: prev.groups.map((g, i) =>
                            i === index
                              ? {
                                  ...g,
                                  assetIds: e.target.value
                                    .split(',')
                                    .map((v) => v.trim())
                                    .filter(Boolean),
                                }
                              : g,
                          ),
                        }))
                      }
                    />
                  )}
                  <Input
                    className="max-w-24"
                    type="number"
                    min={1}
                    max={20}
                    value={group.maxCount}
                    onChange={(e) =>
                      setDraft((prev) => ({
                        ...prev,
                        groups: prev.groups.map((g, i) =>
                          i === index ? { ...g, maxCount: Number(e.target.value) } : g,
                        ),
                      }))
                    }
                  />
                  <Button
                    variant="ghost"
                    size="sm"
                    disabled={busy}
                    onClick={() =>
                      setDraft((prev) => ({
                        ...prev,
                        groups: prev.groups.filter((_, i) => i !== index),
                      }))
                    }
                  >
                    删除
                  </Button>
                </div>
              ))}
            </div>
            <Button
              variant="secondary"
              size="sm"
              className="mt-2"
              disabled={busy}
              onClick={() =>
                setDraft((prev) => ({
                  ...prev,
                  groups: [
                    ...prev.groups,
                    {
                      groupCode: '',
                      groupKind: 'ASSET_LIST',
                      tagCode: '',
                      assetIds: [],
                      maxCount: 9,
                      slotNo: prev.groups.length,
                    },
                  ],
                }))
              }
            >
              <PlusIcon /> 添加图片组
            </Button>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <Button
              onClick={() =>
                void run(async () => {
                  if (!payload) {
                    throw new Error(
                      '模板定义还差一点：编码、名称、平台必填；类目级要类目路径、店铺级要店铺；至少一条字段规则；LITERAL 要给常量、其它来源要给引用。',
                    );
                  }
                  const saved = await saveTemplate(payload);
                  setDetail(saved);
                  setDraft(toTemplateDraft(saved));
                  setNotice(`已保存模板「${saved.template.name}」（${saved.template.code}）`);
                })
              }
              disabled={busy || payload === null}
            >
              <Save className="mr-2 size-4" />
              保存模板
            </Button>
            {payload === null && (
              <span className="text-muted-foreground text-label-12">
                模板定义不完整，先补齐上面的必填项。
              </span>
            )}
          </div>
        </div>
      </SectionCard>

      {/* ---- 选中的模板：规则 + 适用范围命中 ---- */}
      {detail && (
        <SectionCard
          title={`模板「${detail.template.name}」`}
          description={`${ruleSummary(detail)} · ${detail.template.scope === 'CATEGORY' ? '类目级' : '店铺级'} · ${detail.template.platformCode}`}
          extra={
            <div className="flex items-center gap-2">
              <Button
                variant="secondary"
                size="sm"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    const next =
                      detail.template.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE';
                    const updated = await setTemplateStatus(detail.template.id, next);
                    setDetail(updated);
                    setNotice(`模板状态已改为 ${statusLabel(updated.template.status)}`);
                  })
                }
              >
                {detail.template.status === 'ACTIVE' ? '停用' : '启用'}
              </Button>
              <Button
                variant="ghost"
                size="sm"
                disabled={busy}
                onClick={() =>
                  void run(async () => {
                    await deleteTemplate(detail.template.id);
                    setDetail(null);
                    setMatches(null);
                    setNotice('模板已删除');
                    await refresh();
                  })
                }
              >
                删除
              </Button>
            </div>
          }
        >
          <div className="space-y-4">
            <div>
              <p className="text-foreground mb-2 text-label-13 font-medium">字段规则（存的是规则，不是值）</p>
              <ul className="space-y-1">
                {detail.fields.map((f) => (
                  <li
                    key={f.id}
                    className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
                  >
                    <span className="text-foreground w-32 text-label-13">
                      {FIELD_OPTIONS.find((o) => o.value === f.field)?.label ?? f.field}
                    </span>
                    <span className="text-muted-foreground w-32 text-label-12">
                      {sourceKindLabel(f.sourceKind)}
                    </span>
                    <span className="text-muted-foreground flex-1 text-label-12">
                      {f.sourceKind === 'LITERAL'
                        ? f.valueExpression
                        : `${f.sourceRef}${f.valueExpression ? ` · ${f.valueExpression}` : ''}`}
                    </span>
                    {f.required && <Badge tone="warning">必填</Badge>}
                  </li>
                ))}
              </ul>
            </div>

            <div>
              <p className="text-foreground mb-2 text-label-13 font-medium">
                适用范围命中的商品
                <span className="text-muted-foreground font-normal">
                  （{matches?.scopeNote ?? '按真实 listing 查出来的命中行，不是写死的字符串比较'}）
                </span>
              </p>
              {matches && matches.items.length === 0 ? (
                <p className="text-muted-foreground text-label-12">
                  当前没有商品落在这个模板的适用范围内。
                </p>
              ) : (
                <ul className="space-y-1">
                  {(matches?.items ?? []).map((m) => (
                    <li
                      key={m.listingId}
                      className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
                    >
                      <span className="text-foreground text-label-13">{m.title}</span>
                      <span className="text-muted-foreground text-label-12">
                        {m.shopCode} / {m.platformCode} / {m.externalItemId} / {m.categoryPath}
                      </span>
                      {m.hasDraft && <Badge tone="warning">已有待发布草稿</Badge>}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---- 套用 ---- */}
      <SectionCard
        title="一键套用到目标商品"
        description="① 选目标商品 → ② 套用 → ③ 看逐字段结果与必填违规。套用只产出草稿，线上内容一个字节都不动。"
      >
        <div className="space-y-4">
          <div className="flex flex-wrap gap-2">
            <Input
              className="max-w-sm"
              placeholder="按平台商品 ID 或标题搜索目标商品"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void searchCandidates();
              }}
            />
            <Button variant="secondary" onClick={() => void searchCandidates()} disabled={busy}>
              搜索
            </Button>
          </div>
          <ul className="max-h-56 space-y-1 overflow-auto">
            {candidates.length === 0 ? (
              <li className="text-muted-foreground text-label-12">
                没有候选商品。搜一下平台商品 ID 或标题。
              </li>
            ) : (
              candidates.map((c) => (
                <li key={c.id}>
                  <button
                    type="button"
                    onClick={() => setApplyDraft((prev) => ({ ...prev, listingId: c.id }))}
                    className="border-border hover:border-foreground/40 hover:text-foreground text-muted-foreground w-full rounded-md border px-3 py-1.5 text-left text-label-13"
                  >
                    {c.title}
                    <span className="text-muted-foreground">
                      （{c.shopCode} / {c.platformCode} / {c.externalItemId}）
                    </span>
                  </button>
                </li>
              ))
            )}
          </ul>

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="space-y-1 text-sm">
              <span className="block font-medium">幂等键（可空）</span>
              <Input
                placeholder="给了则同一个键重复套用返回第一次的结果"
                value={applyDraft.idempotencyKey}
                onChange={(e) =>
                  setApplyDraft((prev) => ({ ...prev, idempotencyKey: e.target.value }))
                }
              />
            </label>
            <label className="space-y-1 text-sm">
              <span className="block font-medium">备注</span>
              <Input
                value={applyDraft.note}
                onChange={(e) => setApplyDraft((prev) => ({ ...prev, note: e.target.value }))}
              />
            </label>
          </div>

          <div className="flex flex-wrap items-center gap-3">
            <Button
              onClick={() =>
                void run(async () => {
                  if (!applyPayload) {
                    throw new Error('先选一个模板与一个目标商品再套用。');
                  }
                  const applied = await applyTemplate(applyPayload);
                  setResult(applied);
                  setNotice(
                    `已套用：${applied.appliedCount} 个字段取到值，必填校验 ${
                      applied.validationStatus === 'PASS' ? '通过' : '未通过'
                    }（${applied.violationCount} 项违规）`,
                  );
                  const list = await fetchTemplateApplies(
                    applied.templateId,
                    applied.listingId,
                    0,
                    20,
                  );
                  setApplies(list);
                })
              }
              disabled={busy || applyPayload === null}
            >
              <PackagePlus className="mr-2 size-4" />
              套用模板
            </Button>
            {applyPayload === null && (
              <span className="text-muted-foreground text-label-12">先选模板与目标商品。</span>
            )}
          </div>

          {result && (
            <div className="space-y-3">
              <div className="border-border bg-muted/30 rounded-lg border px-3 py-2">
                <p className="text-foreground text-label-13">
                  {result.templateName} → {result.listingExternalItemId}
                  <span className="text-muted-foreground">
                    （产出草稿 {result.targetDraftId}，状态 {result.draftState}）
                  </span>
                </p>
                <p className="text-muted-foreground mt-1 text-label-12">
                  {validationSummary(result)} · 取到 {result.appliedCount} 项 · 处于「不知道」{' '}
                  {result.unknownCount} 项
                </p>
                {replayNote(result) && (
                  <p className="text-warning mt-1 text-label-12">{replayNote(result)}</p>
                )}
              </div>

              {result.violations.length > 0 && (
                <div className="border-destructive/40 bg-destructive/5 rounded-lg border px-3 py-2">
                  <p className="text-destructive mb-1 text-label-13 font-medium">
                    必填违规（指到具体字段与规则）
                  </p>
                  <ul className="text-muted-foreground space-y-1 text-label-12">
                    {result.violations.map((v) => (
                      <li key={v.field}>
                        <span className="text-foreground">{v.field}</span> · {v.ruleRef} ·{' '}
                        {evaluationStatusLabel(v.status)}
                        {v.ownerModule ? ` · 归 ${v.ownerModule}` : ''} —— {v.reason}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <div>
                <p className="text-foreground mb-2 text-label-13 font-medium">逐字段结果</p>
                <ul className="space-y-1">
                  {result.fields.map((f) => (
                    <li
                      key={f.field}
                      className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
                    >
                      <span className="text-foreground w-32 text-label-13">{f.field}</span>
                      <span className="text-muted-foreground w-40 text-label-12">{f.ruleRef}</span>
                      <Badge tone={evaluationTone(f.status)}>{evaluationStatusLabel(f.status)}</Badge>
                      <span className="text-muted-foreground flex-1 text-label-12">
                        {valueLines(f.field, f.value).join(' / ') || f.reason}
                      </span>
                      {f.ownerModule && (
                        <span className="text-warning text-label-12">归 {f.ownerModule}</span>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---- 套用记录 ---- */}
      <SectionCard title="套用记录" description="谁在什么时候把哪个模板套到了哪个商品上，产出了什么草稿。">
        {!applies || applies.items.length === 0 ? (
          <p className="text-muted-foreground text-label-12">还没有套用记录。</p>
        ) : (
          <ul className="space-y-1">
            {applies.items.map((a) => (
              <li
                key={a.applyId}
                className="border-border flex flex-wrap items-center gap-2 rounded-md border px-3 py-2"
              >
                <span className="text-foreground text-label-13">
                  {a.templateName}
                  <span className="text-muted-foreground"> → {a.listingExternalItemId}</span>
                </span>
                <Badge tone={a.validationStatus === 'PASS' ? 'success' : 'warning'}>
                  {a.validationStatus === 'PASS' ? '必填通过' : `${a.violationCount} 项违规`}
                </Badge>
                <span className="text-muted-foreground text-label-12">
                  取到 {a.appliedCount} 项 · 不知道 {a.unknownCount} 项 · 草稿 {a.targetDraftId}
                  {a.idempotencyKey ? ` · 幂等键 ${a.idempotencyKey}` : ''}
                </span>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>
    </div>
  );
}

/** 加号图标（lucide 里的 Plus，单独 import 会多一行，这里内联保持依赖最小）。 */
function PlusIcon() {
  return (
    <svg className="mr-2 size-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2">
      <path d="M12 5v14M5 12h14" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
