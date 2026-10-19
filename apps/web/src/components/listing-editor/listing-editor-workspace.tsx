'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import {
  CircleAlert,
  FileCheck2,
  Image as ImageIcon,
  Loader2,
  RefreshCw,
  Save,
  Send,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  constraintNote,
  draftBadge,
  emptyFieldDraft,
  fetchEditorCapabilities,
  fetchEditorDrafts,
  fetchEditorSubmissions,
  fetchFormSchema,
  fetchListingCandidates,
  fetchMediaAssets,
  fetchShops,
  fetchSkuCandidates,
  fieldItemCount,
  fieldLength,
  idempotencyKey,
  localeHasDraft,
  requirementLabel,
  requirementTone,
  saveEditorDraft,
  siteOptions,
  submissionBadge,
  submitEditorDraft,
  toFieldDrafts,
  toFieldValues,
  validateEditorContent,
  violationTone,
  type EditorDraftRow,
  type EditorFieldSchema,
  type EditorFieldValue,
  type EditorFormSchema,
  type EditorSubmissionRow,
  type EditorValidation,
  type FieldDraft,
  type FieldDrafts,
} from './listing-editor-model';

/**
 * 4.2.1.1 刊登编辑器 工作区。
 *
 * 蓝图：「单商品的平台内容编辑表单：按目标平台动态渲染字段（标题、要点、描述、变体维度、图片集、视频、A+
 * 模块、类目属性、合规声明），左侧编辑右侧实时预览，支持多语言 Tab、保存草稿、提交审核。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **字段集不在前端写死**。左侧每一栏都来自后端推导的 schema（含必填性、上限、能力状态）。
 *    前端**不自己判断必填**，约束未知就显示"上限未知"，细则在别的模块就显示归属模块。
 * 2. **草稿态一眼可见**。保存与提交都不改线上；徽章写"草稿 · 未上线"。
 * 3. **提交审核只是入队**。按钮写"提交审核（入队）"，状态写"已入队 · 等 4.3.1.1 派发"。
 * 4. **多语言 Tab 真存多语言**。每个语言一份草稿，切换语言加载的是那一份。
 * 5. **校验结果指到具体字段**。点违规项跳到那一栏并标红。
 */
export function ListingEditorWorkspace() {
  const [caps, setCaps] = useState<Awaited<ReturnType<typeof fetchEditorCapabilities>> | null>(
    null,
  );
  const [shops, setShops] = useState<Awaited<ReturnType<typeof fetchShops>>>([]);
  const [shopId, setShopId] = useState('');
  const [marketplaceId, setMarketplaceId] = useState('');
  const [mode, setMode] = useState<'LISTING' | 'SKU'>('LISTING');
  const [search, setSearch] = useState('');
  const [candidates, setCandidates] = useState<{ id: string; label: string; sub: string }[]>([]);
  const [listingId, setListingId] = useState('');
  const [sourceSkuId, setSourceSkuId] = useState('');
  const [categoryPath, setCategoryPath] = useState('');

  const [schema, setSchema] = useState<EditorFormSchema | null>(null);
  const [drafts, setDrafts] = useState<EditorDraftRow[]>([]);
  const [activeLocale, setActiveLocale] = useState('');
  const [fields, setFields] = useState<FieldDrafts>({});
  const [validation, setValidation] = useState<EditorValidation | null>(null);
  const [submissions, setSubmissions] = useState<EditorSubmissionRow[]>([]);
  const [focusField, setFocusField] = useState<string | null>(null);
  const [mediaAssets, setMediaAssets] = useState<{ id: string; label: string }[]>([]);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const shop = useMemo(() => shops.find((s) => s.id === shopId) ?? null, [shops, shopId]);
  const sites = useMemo(() => siteOptions(shop), [shop]);

  // ---- 首屏：能力声明 + 店铺清单
  const refreshBase = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capabilities, shopList] = await Promise.all([fetchEditorCapabilities(), fetchShops()]);
      setCaps(capabilities);
      setShops(shopList);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refreshBase();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---- 目标变了 → 重新解一次表单 schema 与草稿
  const refreshTarget = useCallback(async () => {
    if (!shopId || !marketplaceId) {
      setSchema(null);
      setDrafts([]);
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const [formSchema, draftList, submissionList] = await Promise.all([
        fetchFormSchema(shopId, marketplaceId, categoryPath, activeLocale),
        fetchEditorDrafts(shopId),
        fetchEditorSubmissions(),
      ]);
      setSchema(formSchema);
      setDrafts(draftList);
      setSubmissions(submissionList);
      const locales = formSchema.locales.map((l) => l.locale);
      setActiveLocale((prev) => (prev && locales.includes(prev) ? prev : (locales[0] ?? '')));
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载表单失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [shopId, marketplaceId, categoryPath, activeLocale]);

  useEffect(() => {
    if (shopId && marketplaceId) void refreshTarget();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [shopId, marketplaceId, categoryPath]);

  // ---- 语言切换 → 载入那一份草稿（真存多语言，不是换文案）
  useEffect(() => {
    const draft = drafts.find((d) => d.locale === activeLocale);
    setFields(draft ? toFieldDrafts(draft.fieldValues) : {});
    setValidation(draft?.validation ?? null);
    setFocusField(null);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeLocale, drafts]);

  const searchCandidates = useCallback(async () => {
    if (!shopId) return;
    setBusy(true);
    setError(null);
    try {
      if (mode === 'LISTING') {
        const rows = await fetchListingCandidates(shopId, search);
        setCandidates(
          rows.map((r) => ({
            id: r.id,
            label: r.title,
            sub: `${r.externalItemId} · ${r.marketplaceCode ?? '未归站点'}`,
          })),
        );
      } else {
        const page = await fetchSkuCandidates(search);
        setCandidates(
          page.items.map((s) => ({
            id: s.id,
            label: s.name,
            sub: `${s.code} · ${s.spuName}`,
          })),
        );
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : '搜索失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [mode, search, shopId]);

  const pickCandidate = useCallback(
    (id: string) => {
      if (mode === 'LISTING') {
        setListingId(id);
        setSourceSkuId('');
      } else {
        setSourceSkuId(id);
        setListingId('');
      }
    },
    [mode],
  );

  const setField = useCallback((field: string, patch: Partial<FieldDraft>) => {
    setFields((prev) => ({ ...prev, [field]: { ...emptyFieldDraft(), ...prev[field], ...patch } }));
  }, []);

  const currentDraft = useMemo(
    () => drafts.find((d) => d.locale === activeLocale) ?? null,
    [drafts, activeLocale],
  );

  const buildRequest = useCallback(() => {
    if (!shopId || !marketplaceId || !activeLocale) return null;
    if (!listingId && !sourceSkuId) return null;
    return {
      shopId,
      marketplaceId,
      locale: activeLocale,
      sourceSkuId: sourceSkuId || null,
      listingId: listingId || null,
      categoryPath,
      fieldValues: toFieldValues(fields, schema?.fields),
      note: '',
    };
  }, [shopId, marketplaceId, activeLocale, listingId, sourceSkuId, categoryPath, fields, schema]);

  const doSave = useCallback(async () => {
    const request = buildRequest();
    if (!request) {
      setError('先选目标店铺与站点，再选一个商品（线上 listing 或源 SKU）');
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await saveEditorDraft(request);
      setNotice('草稿已保存。线上 listing 没有被改动 —— 发布是 4.3.1.1 的事。');
      setValidation(saved.validation);
      await refreshTarget();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存草稿失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [buildRequest, refreshTarget]);

  const doValidate = useCallback(async () => {
    const request = buildRequest();
    if (!request) {
      setError('先选目标店铺与站点，再选一个商品');
      return;
    }
    setBusy(true);
    setError(null);
    try {
      const result = await validateEditorContent({
        shopId: request.shopId,
        marketplaceId: request.marketplaceId,
        locale: request.locale,
        categoryPath: request.categoryPath,
        fieldValues: request.fieldValues,
      });
      setValidation(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : '校验失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [buildRequest]);

  const doSubmit = useCallback(async () => {
    if (!currentDraft) {
      setError('还没有草稿，先保存草稿再提交审核');
      return;
    }
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const submission = await submitEditorDraft(
        currentDraft.draftId,
        idempotencyKey(currentDraft.draftId, Date.now()),
      );
      setNotice(
        `已入队（${submission.submissionId}）。真正发给平台是 4.3.1.1 一键刊登的职责，现在还没有派发。`,
      );
      await refreshTarget();
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交审核失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [currentDraft, refreshTarget]);

  const openMedia = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const assets = await fetchMediaAssets();
      setMediaAssets(assets.map((a) => ({ id: a.id, label: `${a.name}（${a.storageKey}）` })));
    } catch (e) {
      setError(e instanceof Error ? e.message : '素材库读不到，请稍后重试');
    } finally {
      setBusy(false);
    }
  }, []);

  if (loading) {
    return (
      <div className="text-muted-foreground flex items-center justify-center gap-2 py-16">
        <Loader2 className="size-4 animate-spin" />
        <span className="text-label-13">正在加载刊登编辑器…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="刊登编辑器">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="刊登编辑器加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refreshBase()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  const unavailable = (caps?.dimensions ?? []).filter((d) => !d.available);
  const renderable = schema?.fields ?? [];

  return (
    <div className="space-y-4">
      <PageHeader
        title="刊登编辑器"
        description="按目标平台动态渲染字段：左侧编辑、右侧实时预览，草稿按平台 + 站点 + 语言分别保存，提交审核只是入队。"
      />

      {error && (
        <div className="border-destructive/40 bg-destructive/5 text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{error}</p>
        </div>
      )}

      {notice && (
        <div className="border-brand/40 bg-brand-soft/40 flex items-start gap-2 rounded-lg border px-4 py-3">
          <ShieldCheck className="text-brand mt-0.5 size-4 shrink-0" />
          <p className="text-label-13">{notice}</p>
        </div>
      )}

      {/* ---- 🔴 三条硬边界的界面措辞（后端给原文，前端原样展示） ---- */}
      {caps && (
        <div className="border-brand/40 bg-brand-soft/40 rounded-lg border px-4 py-3">
          <p className="text-foreground text-label-13 mb-1 font-medium">这个页面的三条硬边界</p>
          <ul className="text-muted-foreground text-label-12 space-y-1">
            <li className="flex items-start gap-2">
              <ShieldCheck className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.draftIsolationNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <Send className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.submitNote}</span>
            </li>
            <li className="flex items-start gap-2">
              <FileCheck2 className="text-brand mt-0.5 size-3.5 shrink-0" />
              <span>{caps.templateNote}</span>
            </li>
          </ul>
        </div>
      )}

      {/* ---- 未接入维度明账 ---- */}
      {unavailable.length > 0 && (
        <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
          <p className="text-warning text-label-13 mb-1 font-medium">部分能力未接入</p>
          <ul className="text-muted-foreground text-label-12 space-y-1">
            {unavailable.map((d) => (
              <li key={d.dimension}>
                <span className="text-foreground">{d.description}</span>
                ：归 {d.ownerModule} —— {d.impact}
              </li>
            ))}
          </ul>
        </div>
      )}

      {/* ---- 目标选择 ---- */}
      <SectionCard
        title="① 目标店铺、站点与商品"
        description="目标店铺决定平台，目标站点与语言决定内容落在哪一份草稿里（内容按平台 + 站点 + 语言分别保存）。"
      >
        <div className="space-y-4 px-4 py-4">
          <div className="grid gap-3 sm:grid-cols-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={shopId}
              onChange={(e) => {
                setShopId(e.target.value);
                setMarketplaceId('');
                setListingId('');
                setSourceSkuId('');
                setCandidates([]);
              }}
              disabled={busy}
            >
              <option value="">选择目标店铺</option>
              {shops.map((s) => (
                <option key={s.id} value={s.id}>
                  {s.code} · {s.name}（{s.platformCode}）
                </option>
              ))}
            </select>
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={marketplaceId}
              onChange={(e) => setMarketplaceId(e.target.value)}
              disabled={busy || !shop}
            >
              <option value="">选择目标站点</option>
              {sites.map((m) => (
                <option key={m.marketplaceId} value={m.marketplaceId}>
                  {m.marketplaceCode} · {m.marketplaceName}
                </option>
              ))}
            </select>
          </div>

          <div className="flex flex-wrap items-center gap-2">
            <select
              className="border-control-border bg-card text-copy-13 h-9 rounded-md border px-2"
              value={mode}
              onChange={(e) => {
                setMode(e.target.value as 'LISTING' | 'SKU');
                setCandidates([]);
                setListingId('');
                setSourceSkuId('');
              }}
              disabled={busy}
            >
              <option value="LISTING">编辑线上 listing 的内容</option>
              <option value="SKU">为源商品准备待刊登内容</option>
            </select>
            <Input
              className="max-w-sm"
              placeholder={
                mode === 'LISTING' ? '按平台商品 ID 或标题搜索' : '按 SKU 编码或名称搜索'
              }
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') void searchCandidates();
              }}
            />
            <Button
              variant="secondary"
              onClick={() => void searchCandidates()}
              disabled={busy || !shopId}
            >
              搜索
            </Button>
          </div>

          {candidates.length > 0 && (
            <ul className="border-border divide-border divide-y rounded-lg border">
              {candidates.map((c) => (
                <li key={c.id}>
                  <button
                    type="button"
                    className="hover:bg-muted/40 w-full px-3 py-2 text-left"
                    onClick={() => pickCandidate(c.id)}
                  >
                    <span className="text-foreground text-label-13">{c.label}</span>
                    <span className="text-muted-foreground text-label-12 ml-2">{c.sub}</span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          <div className="grid gap-3 sm:grid-cols-2">
            <Input
              placeholder="目标平台类目路径（平台侧原文）"
              value={categoryPath}
              onChange={(e) => setCategoryPath(e.target.value)}
            />
            <div className="text-muted-foreground text-label-12 flex items-center">
              {listingId
                ? '商品来源：线上 listing（内容与线上对照列会一起给出）'
                : sourceSkuId
                  ? '商品来源：源商品 SKU（03 主数据）'
                  : '还没选商品 —— 选一个线上 listing 或源 SKU 才能编辑'}
            </div>
          </div>
        </div>
      </SectionCard>

      {/* ---- 表单 + 预览 ---- */}
      <SectionCard
        title="② 编辑内容"
        description="字段集、必填性、长度 / 条数上限全部来自平台要求（后端推导），本页不写死一套表单。"
        extra={
          <div className="flex items-center gap-2">
            <Button variant="ghost" size="sm" onClick={() => void refreshTarget()} disabled={busy}>
              <RefreshCw className="mr-2 size-4" />
              刷新
            </Button>
            <Button variant="secondary" size="sm" onClick={() => void doValidate()} disabled={busy}>
              校验
            </Button>
            <Button variant="secondary" size="sm" onClick={() => void doSave()} disabled={busy}>
              <Save className="mr-2 size-4" />
              保存草稿
            </Button>
            <Button size="sm" onClick={() => void doSubmit()} disabled={busy || !currentDraft}>
              <Send className="mr-2 size-4" />
              提交审核（入队）
            </Button>
          </div>
        }
      >
        <div className="space-y-4 px-4 py-4">
          {/* 多语言 Tab */}
          {schema && (
            <div className="border-border flex flex-wrap items-center gap-2 border-b pb-2">
              {(schema.locales ?? []).map((l) => (
                <button
                  key={l.locale}
                  type="button"
                  onClick={() => setActiveLocale(l.locale)}
                  className={
                    l.locale === activeLocale
                      ? 'border-primary text-primary text-label-13 inline-flex items-center gap-1 border-b-2 px-3 py-1.5 font-semibold'
                      : 'text-muted-foreground hover:text-foreground text-label-13 inline-flex items-center gap-1 border-b-2 border-transparent px-3 py-1.5'
                  }
                >
                  {l.locale}
                  {l.hasDraft ? (
                    <Badge tone={draftBadge(l.draftState).tone}>
                      {draftBadge(l.draftState).label}
                    </Badge>
                  ) : (
                    <Badge tone="neutral">未建草稿</Badge>
                  )}
                </button>
              ))}
              <Input
                className="h-8 w-32"
                placeholder="新增语言"
                onKeyDown={(e) => {
                  const value = (e.target as HTMLInputElement).value.trim();
                  if (e.key === 'Enter' && value) {
                    setActiveLocale(value);
                    (e.target as HTMLInputElement).value = '';
                  }
                }}
              />
            </div>
          )}

          {!schema && (
            <EmptyState
              icon={<CircleAlert className="text-muted-foreground size-8" />}
              title="先选目标店铺与站点"
              description="表单字段由平台要求驱动生成，选定目标后才会解出该平台要填的字段。"
            />
          )}

          {schema && schema.requirementsState === 'UNKNOWN' && (
            <div className="border-warning/40 bg-warning/5 rounded-lg border px-4 py-3">
              <p className="text-warning text-label-13">
                该平台的字段要求取不到（归 {schema.requirementsOwnerModule ?? '上游模块'}）。
                下面是空表单，**不是**「这个平台没有要填的字段」。
              </p>
            </div>
          )}

          {schema && renderable.length === 0 && schema.requirementsState !== 'UNKNOWN' && (
            <EmptyState
              icon={<CircleAlert className="text-muted-foreground size-8" />}
              title="该平台没有登记任何字段"
              description="平台字段要求注册表里没有这个平台的记录，表单因此是空的。"
            />
          )}

          {schema && renderable.length > 0 && (
            <div className="grid gap-6 lg:grid-cols-2">
              {/* 左侧：动态字段表单 */}
              <div className="space-y-4">
                {renderable.map((field) => (
                  <EditorField
                    key={field.field}
                    schema={field}
                    draft={fields[field.field]}
                    invalid={
                      focusField === field.field ||
                      (validation?.violations ?? []).some(
                        (v) => v.field === field.field && v.severity === 'BLOCKING',
                      )
                    }
                    disabled={busy || !field.editable}
                    onChange={(patch) => setField(field.field, patch)}
                    onPickMedia={openMedia}
                  />
                ))}
              </div>

              {/* 右侧：实时预览 + 校验结果 + 线上对照 */}
              <div className="space-y-4">
                <SectionCard
                  title="实时预览"
                  description="右侧是你正在编辑的内容，不是平台最终渲染结果。"
                >
                  <div className="space-y-3 px-4 py-3">
                    {renderable.length === 0 && (
                      <p className="text-muted-foreground text-label-12">没有可预览的字段。</p>
                    )}
                    {renderable.map((field) => {
                      const draft = fields[field.field];
                      if (!draft) return null;
                      return (
                        <div key={field.field}>
                          <p className="text-muted-foreground text-label-12">{field.label}</p>
                          <p className="text-foreground text-label-13 whitespace-pre-wrap break-words">
                            {previewText(draft) || (
                              <span className="text-muted-foreground">（空）</span>
                            )}
                          </p>
                        </div>
                      );
                    })}
                  </div>
                </SectionCard>

                {currentDraft?.liveValues && currentDraft.liveValues.length > 0 && (
                  <SectionCard
                    title="线上当前内容"
                    description="来自 4.1.1.2 的内容快照与 listing 本身（对照列，不是你要写的内容）。"
                  >
                    <div className="space-y-3 px-4 py-3">
                      {currentDraft.liveValues.map((v) => (
                        <div key={v.field}>
                          <p className="text-muted-foreground text-label-12">{v.field}</p>
                          <p className="text-foreground text-label-13 whitespace-pre-wrap break-words">
                            {livePreviewText(v)}
                          </p>
                        </div>
                      ))}
                    </div>
                  </SectionCard>
                )}

                <SectionCard title="校验结果" description={validation?.summary ?? '还没有校验过。'}>
                  <div className="space-y-2 px-4 py-3">
                    {!validation && (
                      <p className="text-muted-foreground text-label-12">
                        点「校验」算一次：必填缺失、长度超限、条数超限都会逐条指到具体字段。
                      </p>
                    )}
                    {validation && validation.violations.length === 0 && (
                      <p className="text-success text-label-13">没有违规项。</p>
                    )}
                    {(validation?.violations ?? []).map((v, index) => (
                      <button
                        key={`${v.field}-${v.code}-${index}`}
                        type="button"
                        onClick={() => setFocusField(v.field)}
                        className="border-border hover:bg-muted/40 flex w-full items-start gap-2 rounded-lg border px-3 py-2 text-left"
                      >
                        <Badge tone={violationTone(v.severity)}>
                          {v.severity === 'BLOCKING' ? '阻断' : '提醒'}
                        </Badge>
                        <span className="text-foreground text-label-13">{v.message}</span>
                        {v.ownerModule && (
                          <span className="text-muted-foreground text-label-12">
                            归 {v.ownerModule}
                          </span>
                        )}
                      </button>
                    ))}
                  </div>
                </SectionCard>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---- 入队队列 ---- */}
      <SectionCard
        title="③ 提交审核队列"
        description="提交审核只是入队：真正发给平台是 4.3.1.1 一键刊登（尚未交付）。"
      >
        <div className="space-y-2 px-4 py-3">
          {submissions.length === 0 && (
            <p className="text-muted-foreground text-label-12">队列里还没有记录。</p>
          )}
          {submissions.map((s) => (
            <div
              key={s.submissionId}
              className="border-border flex flex-wrap items-center gap-2 rounded-lg border px-3 py-2"
            >
              <Badge tone={submissionBadge(s.state).tone}>{submissionBadge(s.state).label}</Badge>
              <span className="text-foreground text-label-13">
                {s.platformCode} · {s.shopCode} · {s.marketplaceCode} · {s.locale}
              </span>
              <span className="text-muted-foreground text-label-12">
                入队于 {new Date(s.queuedAt).toLocaleString('zh-CN')}
              </span>
            </div>
          ))}
        </div>
      </SectionCard>

      {/* ---- 素材库（图片集字段的来源） ---- */}
      {mediaAssets.length > 0 && (
        <SectionCard
          title="媒体库素材"
          description="03 媒体库（跨服务只读）。点一下把素材存储键填进图片集。"
          extra={
            <Button variant="ghost" size="sm" onClick={() => setMediaAssets([])}>
              关闭
            </Button>
          }
        >
          <ul className="space-y-1 px-4 py-3">
            {mediaAssets.map((a) => (
              <li key={a.id}>
                <button
                  type="button"
                  className="hover:bg-muted/40 text-foreground text-label-13 w-full rounded px-2 py-1 text-left"
                  onClick={() => {
                    const current = fields['IMAGES']?.items ?? [];
                    setField('IMAGES', { items: [...current, a.label] });
                  }}
                >
                  {a.label}
                </button>
              </li>
            ))}
          </ul>
        </SectionCard>
      )}
    </div>
  );
}

/** 一栏字段的动态表单（按 valueKind 渲染，前端不认平台）。 */
function EditorField({
  schema,
  draft,
  invalid,
  disabled,
  onChange,
  onPickMedia,
}: {
  schema: EditorFieldSchema;
  draft: FieldDraft | undefined;
  invalid: boolean;
  disabled: boolean;
  onChange: (patch: Partial<FieldDraft>) => void;
  onPickMedia: () => void;
}) {
  const value = draft ?? emptyFieldDraft();
  const note = constraintNote(schema);
  const length = fieldLength(value);
  const count = fieldItemCount(value);

  return (
    <div
      className={
        invalid
          ? 'border-destructive/60 bg-destructive/5 rounded-lg border p-3'
          : 'border-border rounded-lg border p-3'
      }
    >
      <div className="mb-2 flex flex-wrap items-center gap-2">
        <span className="text-foreground text-label-13 font-medium">{schema.label}</span>
        <Badge tone={requirementTone(schema.requirement)}>
          {requirementLabel(schema.requirement)}
        </Badge>
        {schema.capabilityState === 'UNKNOWN' && <Badge tone="warning">平台支持未知</Badge>}
        {schema.capabilityState === 'UNSUPPORTED' && <Badge tone="danger">平台不支持</Badge>}
        {schema.valueSource === 'LISTING_VARIANT' && (
          <Badge tone="info">来自变体映射（只读）</Badge>
        )}
        {note && <span className="text-muted-foreground text-label-12">{note}</span>}
      </div>

      {schema.valueKind === 'TEXT' && (
        <Input
          value={value.text}
          disabled={disabled}
          onChange={(e) => onChange({ text: e.target.value })}
          placeholder={schema.label}
        />
      )}

      {schema.valueKind === 'LONG_TEXT' && (
        <textarea
          className="border-control-border bg-card text-copy-13 min-h-28 w-full rounded-md border px-2 py-1.5"
          value={value.text}
          disabled={disabled}
          onChange={(e) => onChange({ text: e.target.value })}
          placeholder={schema.label}
        />
      )}

      {schema.valueKind === 'VIDEO_REF' && (
        <Input
          value={value.text}
          disabled={disabled}
          onChange={(e) => onChange({ text: e.target.value })}
          placeholder="视频地址"
        />
      )}

      {(schema.valueKind === 'TEXT_LIST' || schema.valueKind === 'IMAGE_LIST') && (
        <div className="space-y-2">
          {value.items.map((item, index) => (
            <div key={index} className="flex items-center gap-2">
              <Input
                value={item}
                disabled={disabled}
                onChange={(e) => {
                  const next = [...value.items];
                  next[index] = e.target.value;
                  onChange({ items: next });
                }}
                placeholder={
                  schema.valueKind === 'IMAGE_LIST' ? '图片引用（素材存储键 / URL）' : schema.label
                }
              />
              <Button
                variant="ghost"
                size="sm"
                disabled={disabled}
                onClick={() => onChange({ items: value.items.filter((_, i) => i !== index) })}
              >
                移除
              </Button>
            </div>
          ))}
          <div className="flex items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={disabled}
              onClick={() => onChange({ items: [...value.items, ''] })}
            >
              添加一条
            </Button>
            {schema.valueKind === 'IMAGE_LIST' && (
              <Button variant="ghost" size="sm" disabled={disabled} onClick={onPickMedia}>
                <ImageIcon className="mr-2 size-4" />
                从媒体库选
              </Button>
            )}
          </div>
        </div>
      )}

      {schema.valueKind === 'MODULE_LIST' && (
        <div className="space-y-2">
          {value.modules.map((module, index) => (
            <div key={index} className="border-border space-y-1 rounded border p-2">
              <Input
                value={module.type}
                disabled={disabled}
                onChange={(e) => {
                  const next = [...value.modules];
                  next[index] = { ...module, type: e.target.value };
                  onChange({ modules: next });
                }}
                placeholder="模块类型"
              />
              <Input
                value={module.heading}
                disabled={disabled}
                onChange={(e) => {
                  const next = [...value.modules];
                  next[index] = { ...module, heading: e.target.value };
                  onChange({ modules: next });
                }}
                placeholder="标题"
              />
              <textarea
                className="border-control-border bg-card text-copy-13 min-h-16 w-full rounded-md border px-2 py-1.5"
                value={module.body}
                disabled={disabled}
                onChange={(e) => {
                  const next = [...value.modules];
                  next[index] = { ...module, body: e.target.value };
                  onChange({ modules: next });
                }}
                placeholder="正文"
              />
              <Button
                variant="ghost"
                size="sm"
                disabled={disabled}
                onClick={() => onChange({ modules: value.modules.filter((_, i) => i !== index) })}
              >
                移除模块
              </Button>
            </div>
          ))}
          <Button
            variant="secondary"
            size="sm"
            disabled={disabled}
            onClick={() =>
              onChange({
                modules: [...value.modules, { type: '', heading: '', body: '', imageRef: '' }],
              })
            }
          >
            添加模块
          </Button>
        </div>
      )}

      {(schema.valueKind === 'ATTRIBUTE_MAP' || schema.valueKind === 'STATEMENT_MAP') && (
        <div className="space-y-2">
          {value.pairs.map((pair, index) => (
            <div key={index} className="flex items-center gap-2">
              <Input
                className="w-40"
                value={pair.key}
                disabled={disabled}
                onChange={(e) => {
                  const next = [...value.pairs];
                  next[index] = { ...pair, key: e.target.value };
                  onChange({ pairs: next });
                }}
                placeholder="名称"
              />
              <Input
                value={pair.value}
                disabled={disabled}
                onChange={(e) => {
                  const next = [...value.pairs];
                  next[index] = { ...pair, value: e.target.value };
                  onChange({ pairs: next });
                }}
                placeholder="值"
              />
              <Button
                variant="ghost"
                size="sm"
                disabled={disabled}
                onClick={() => onChange({ pairs: value.pairs.filter((_, i) => i !== index) })}
              >
                移除
              </Button>
            </div>
          ))}
          <Button
            variant="secondary"
            size="sm"
            disabled={disabled}
            onChange={() => onChange({ pairs: [...value.pairs, { key: '', value: '' }] })}
          >
            添加一项
          </Button>
        </div>
      )}

      <p className="text-muted-foreground text-label-12 mt-2">
        {schema.maxLength != null
          ? `${length} / ${schema.maxLength} 字`
          : `${length} 字（上限未知）`}
        {schema.maxItems != null ? ` · ${count} / ${schema.maxItems} 条` : ` · ${count} 条`}
      </p>
    </div>
  );
}

/** 本地编辑态 → 预览文本。 */
function previewText(draft: FieldDraft): string {
  if (draft.text) return draft.text;
  if (draft.items.length) return draft.items.filter(Boolean).join('\n');
  if (draft.modules.length)
    return draft.modules
      .map((m) => [m.heading, m.body].filter(Boolean).join('\n'))
      .filter(Boolean)
      .join('\n');
  if (draft.pairs.length)
    return draft.pairs
      .map((p) => `${p.key}：${p.value}`)
      .filter((s) => s !== '：')
      .join('\n');
  return '';
}

/** 线上内容 → 预览文本。 */
function livePreviewText(value: EditorFieldValue): string {
  if (value.text) return value.text;
  if (value.items?.length) return value.items.map((i) => i ?? '').join('\n');
  if (value.pairs?.length)
    return value.pairs.map((p) => `${p?.key ?? ''}：${p?.value ?? ''}`).join('\n');
  return '（线上为空）';
}
