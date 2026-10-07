'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { Download, Loader2, Plus, RefreshCw, Save, Sparkles, Trash2 } from 'lucide-react';
import type {
  ContentOverview,
  ContentPhraseInput,
  ContentPhraseRow,
  ContentRenderDetail,
  ContentRenderRow,
  ContentSection,
  ContentTemplateInput,
  ContentTemplateRow,
  ContentTranslation,
  ContentTranslationInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { Editor, Field, control } from '../common/fields';
import { emptySkuFilters, fetchSkuPage } from './sku-list-model';
import {
  CONTENT_TYPES,
  ISSUE_KINDS,
  MAX_SKUS_PER_RENDER,
  PHRASE_TYPES,
  RENDER_STATUSES,
  SECTION_STATUSES,
  TRANSLATION_ORIGINS,
  TRANSLATION_STATUSES,
  VARIABLE_SOURCES,
  bodyProblem,
  contentTypeLabel,
  csvExportUrl,
  deleteContentTemplate,
  deletePhrase,
  deleteTranslation,
  emptyPhraseInput,
  emptyRenderInput,
  emptyTemplateInput,
  emptyTranslationInput,
  errorText,
  fetchContentOverview,
  fetchContentTemplate,
  fetchContentTemplates,
  fetchPhrases,
  fetchRenderDetail,
  fetchRenders,
  fetchSections,
  fetchTranslationCoverage,
  fetchTranslations,
  issueFix,
  localeProblem,
  missingSummary,
  missingTone,
  phraseCodeProblem,
  phraseEmptyHint,
  phraseTextProblem,
  phraseTypeLabel,
  renderDescriptions,
  renderEmptyHint,
  renderStatusLabel,
  renderStatusTone,
  saveContentTemplate,
  savePhrase,
  saveTranslation,
  sectionEmptyHint,
  sectionStatusTone,
  setContentTemplateEnabled,
  setPhraseEnabled,
  templateCodeProblem,
  templateEmptyHint,
  templateNameProblem,
  templateSummary,
  translationEmptyHint,
  translationPlaceholdersProblem,
  usedPhrasesText,
  variablesSizeProblem,
  variableBindingProblem,
} from './description-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.4.2.2 描述与卖点库工作区。
 *
 * <p>页面按「配模板 → 配短语池 → 批量填充 → 逐段核对」排：
 *
 * <ul>
 *   <li><b>内容模板</b> —— 详情描述 / 使用说明 / 注意事项 / 卖点段落四类，
 *       变量占位、源语言、已配语言一览。
 *   <li><b>多语言译文</b> —— 占位符必须与源语言完全一致（AI 预填的三个典型坑都在这挡）。
 *   <li><b>卖点短语库</b> —— 四类短语池，带引用数（删除保护在列表上就看得见）。
 *   <li><b>批量填充</b> —— SKU 范围 + 模板 + 语言。<strong>同步执行</strong>：
 *       返回时候段与缺失报告已落库，不轮询。
 *   <li><b>填充结果</b> —— 逐段正文 + <strong>缺变量逐条报告</strong>。
 * </ul>
 *
 * <p>界面上反复强调「缺变量是一种结果」：正文照样产出（占位符已剥掉），
 * 段落被标成缺变量不能发布，并逐条说清「哪个变量、为什么缺、怎么修」。
 * 不说清楚，运营看到「批量填充部分失败」会以为系统坏了，于是把变量随便删掉，
 * 结果描述里少一句品牌名就发到平台上了。
 */
export function DescriptionWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<ContentOverview | null>(null);
  const [templates, setTemplates] = useState<ContentTemplateRow[]>([]);
  const [phrases, setPhrases] = useState<ContentPhraseRow[]>([]);
  const [phraseTotal, setPhraseTotal] = useState(0);
  const [phraseType, setPhraseType] = useState('');
  const [renders, setRenders] = useState<ContentRenderRow[]>([]);
  const [renderTotal, setRenderTotal] = useState(0);
  const [renderPage, setRenderPage] = useState(0);
  const [renderStatus, setRenderStatus] = useState('');
  const [sections, setSections] = useState<ContentSection[]>([]);
  const [sectionTotal, setSectionTotal] = useState(0);
  const [sectionPage, setSectionPage] = useState(0);
  const [sectionStatus, setSectionStatus] = useState('');
  const [detail, setDetail] = useState<ContentRenderDetail | null>(null);

  const [translations, setTranslations] = useState<ContentTranslation[]>([]);
  const [coverage, setCoverage] = useState<{ missingLocales: string[]; note: string } | null>(null);

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const [editing, setEditing] = useState<ContentTemplateInput | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingPhrase, setEditingPhrase] = useState<ContentPhraseInput | null>(null);
  const [editingPhraseId, setEditingPhraseId] = useState<string | null>(null);
  const [editingTranslation, setEditingTranslation] = useState<ContentTranslationInput | null>(
    null,
  );
  const [editingTranslationId, setEditingTranslationId] = useState<string | null>(null);
  const [translatingTemplate, setTranslatingTemplate] = useState<ContentTemplateRow | null>(null);

  const [rendering, setRendering] = useState<ReturnType<typeof emptyRenderInput> | null>(null);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string; spuName: string }[]>(
    [],
  );
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [skuKeyword, setSkuKeyword] = useState('');

  const load = useCallback(async () => {
    try {
      const [o, t, p, r, s] = await Promise.all([
        fetchContentOverview(),
        fetchContentTemplates(),
        fetchPhrases({
          keyword: null,
          phraseType: phraseType || null,
          enabled: null,
          page: 0,
          size: 50,
        }),
        fetchRenders({
          status: renderStatus || null,
          page: renderPage,
          size: PAGE_SIZE,
        }),
        fetchSections({
          skuId: null,
          locale: null,
          contentType: null,
          status: sectionStatus || null,
          keyword: null,
          renderId: null,
          page: sectionPage,
          size: PAGE_SIZE,
        }),
      ]);
      setOverview(o);
      setTemplates(t);
      setPhrases(p.items.map((i) => i.phrase));
      setPhraseTotal(p.total);
      setRenders(r.items);
      setRenderTotal(r.total);
      setSections(s.items);
      setSectionTotal(s.total);
      setError(null);
    } catch (e) {
      setError(errorText(e, '描述与卖点库数据加载失败'));
    } finally {
      setLoading(false);
    }
  }, [phraseType, renderStatus, renderPage, sectionStatus, sectionPage]);

  useEffect(() => {
    void load();
  }, [load]);

  // ------------------------------------------------------------ 模板

  /** 表单整体校验，返回第一条可行动原因。与服务端同一口径。 */
  function templateFormProblem(input: ContentTemplateInput): string | null {
    return (
      templateCodeProblem(input.code) ??
      templateNameProblem(input.name) ??
      bodyProblem(input.body) ??
      localeProblem(input.sourceLocale) ??
      variablesSizeProblem(input.variables?.length ?? 0) ??
      variableBindingProblem(input.body, input.variables)
    );
  }

  async function saveTemplateForm() {
    if (!editing) return;
    const problem = templateFormProblem(editing);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    try {
      await saveContentTemplate(editingId, editing);
      setEditing(null);
      setEditingId(null);
      setNotice('模板已保存。变量绑定与正文占位符已校验一致。');
      await load();
    } catch (e) {
      setActionError(errorText(e, '模板保存失败'));
    } finally {
      setBusy(false);
    }
  }

  function openTemplateEdit(template: ContentTemplateRow) {
    // 编辑时以**后端返回的**变量为准，不用表单里存一份副本：那样两份会漂移，
    // 而「当前有哪些变量」这个问题的答案必须是库里那一份。
    setEditingId(template.id);
    setEditing({
      code: template.code,
      name: template.name,
      note: template.note || null,
      contentType: template.contentType,
      body: template.body,
      sourceLocale: template.sourceLocale,
      variables: template.variables.map((v) => ({
        name: v.name,
        source: v.source,
        slotNo: v.slotNo,
      })),
      enabled: template.enabled,
      version: template.version,
    });
  }

  async function toggleTemplateEnabled(template: ContentTemplateRow) {
    setBusy(true);
    try {
      await setContentTemplateEnabled(template.id, !template.enabled);
      await load();
    } catch (e) {
      setActionError(errorText(e, '模板启停失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeTemplate(template: ContentTemplateRow) {
    setBusy(true);
    try {
      await deleteContentTemplate(template.id, template.version);
      setNotice(`模板「${template.code}」已删除。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '模板删除失败'));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 译文

  async function openTranslations(template: ContentTemplateRow) {
    setActionError(null);
    setTranslatingTemplate(template);
    setEditingTranslation(null);
    setEditingTranslationId(null);
    try {
      setTranslations(await fetchTranslations(template.id));
    } catch (e) {
      setActionError(errorText(e, '译文加载失败'));
    }
  }

  async function checkCoverage(template: ContentTemplateRow) {
    setActionError(null);
    // 常见的三种目标语言：运营最常关心的就是这几个。
    const wanted = ['en-US', 'ja-JP', 'de-DE'].map((l) =>
      l === template.sourceLocale ? template.sourceLocale : l,
    );
    try {
      const result = await fetchTranslationCoverage(template.id, wanted);
      setCoverage({ missingLocales: result.missingLocales, note: result.note });
    } catch (e) {
      setActionError(errorText(e, '译文完整度检查失败'));
    }
  }

  async function saveTranslationForm() {
    if (!editingTranslation || !translatingTemplate) return;
    const locale = editingTranslation.locale.trim();
    const bodyProblemText = bodyProblem(editingTranslation.body);
    const placeholderProblem = translationPlaceholdersProblem(
      translatingTemplate.body,
      editingTranslation.body,
    );
    const problem =
      localeProblem(locale) ??
      (!editingTranslation.name.trim() ? '译文名称不能为空' : null) ??
      bodyProblemText ??
      placeholderProblem;
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    try {
      await saveTranslation(translatingTemplate.id, editingTranslationId, {
        ...editingTranslation,
        locale,
        name: editingTranslation.name.trim(),
      });
      setEditingTranslation(null);
      setEditingTranslationId(null);
      setNotice('译文已保存。占位符与源语言完全一致。');
      setTranslations(await fetchTranslations(translatingTemplate.id));
      await load();
    } catch (e) {
      setActionError(errorText(e, '译文保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeTranslation(translation: ContentTranslation) {
    setBusy(true);
    try {
      await deleteTranslation(translation.id, translation.version);
      setNotice(`语言「${translation.locale}」的译文已删除。`);
      if (translatingTemplate) setTranslations(await fetchTranslations(translatingTemplate.id));
      await load();
    } catch (e) {
      setActionError(errorText(e, '译文删除失败'));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 短语

  async function savePhraseForm() {
    if (!editingPhrase) return;
    const problem =
      phraseCodeProblem(editingPhrase.code) ??
      phraseTextProblem(editingPhrase.text) ??
      (PHRASE_TYPES.some((t) => t.value === editingPhrase.phraseType) ? null : '请选择短语类型');
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    try {
      await savePhrase(editingPhraseId, editingPhrase);
      setEditingPhrase(null);
      setEditingPhraseId(null);
      setNotice('短语已保存。');
      await load();
    } catch (e) {
      setActionError(errorText(e, '短语保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function togglePhraseEnabled(phrase: ContentPhraseRow) {
    setBusy(true);
    try {
      await setPhraseEnabled(phrase.id, !phrase.enabled);
      await load();
    } catch (e) {
      setActionError(errorText(e, '短语启停失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removePhrase(phrase: ContentPhraseRow) {
    setBusy(true);
    try {
      await deletePhrase(phrase.id, phrase.version);
      setNotice(`短语「${phrase.text.slice(0, 20)}」已删除。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '短语删除失败'));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 批量填充

  async function openPicker(templateId: string, locale: string) {
    setActionError(null);
    setRendering(emptyRenderInput(templateId, locale));
    setSelected(new Set());
    try {
      const page = await fetchSkuPage({ ...emptySkuFilters, keyword: skuKeyword }, 0, 20);
      setSkus(page.items);
    } catch (e) {
      setActionError(errorText(e, 'SKU 列表加载失败'));
    }
  }

  async function searchSkus() {
    try {
      const page = await fetchSkuPage({ ...emptySkuFilters, keyword: skuKeyword }, 0, 20);
      setSkus(page.items);
    } catch (e) {
      setActionError(errorText(e, 'SKU 搜索失败'));
    }
  }

  async function submitRender() {
    if (!rendering) return;
    if (selected.size === 0) {
      setActionError(`请至少选择一个要填充描述的 SKU（单次最多 ${MAX_SKUS_PER_RENDER} 个）`);
      return;
    }
    setBusy(true);
    try {
      // 同步语义：返回时候选已经在库里，直接看详情，不轮询。
      const result = await renderDescriptions({ ...rendering, skuIds: [...selected] });
      setRendering(null);
      setNotice(
        `${result.statusLabel}：填出 ${result.completeCount} 段可直接引用` +
          (result.incompleteCount > 0 ? `，${result.incompleteCount} 段缺变量` : '') +
          '。',
      );
      await load();
      await openDetail(result.id);
    } catch (e) {
      setActionError(errorText(e, '批量填充失败'));
    } finally {
      setBusy(false);
    }
  }

  async function openDetail(renderId: string) {
    try {
      setDetail(await fetchRenderDetail(renderId));
    } catch (e) {
      setActionError(errorText(e, '批次详情加载失败'));
    }
  }

  // ------------------------------------------------------------ 渲染

  const currentTemplate = useMemo(
    () => (rendering ? (templates.find((t) => t.id === rendering.templateId) ?? null) : null),
    [rendering, templates],
  );

  const localeChoices = useMemo(() => {
    if (!currentTemplate) return ['zh-CN'];
    // 源语言 + 已配译文语言：只能选填得出的语言（缺译文的语言在提交时会被整批拒，
    // 所以这里干脆不给选，免得运营选完才被拒）。
    return [currentTemplate.sourceLocale, ...currentTemplate.locales];
  }, [currentTemplate]);

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="内容模板"
          value={overview?.templateCount ?? 0}
          icon={<Sparkles className="h-4 w-4" />}
        />
        <StatCard label="卖点短语" value={overview?.phraseCount ?? 0} />
        <StatCard label="已填好段落" value={overview?.completeSectionCount ?? 0} />
        <StatCard label="缺变量段落" value={overview?.incompleteSectionCount ?? 0} />
      </div>

      {overview && overview.incompleteSectionCount > 0 && (
        <SectionCard title="最该先做的一件事">
          <p className="text-sm">
            {missingSummary(
              overview.completeSectionCount,
              overview.incompleteSectionCount,
              overview.issueCount,
            )}
          </p>
        </SectionCard>
      )}

      {loading && (
        <SectionCard title="加载中">
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载描述与卖点库…
          </p>
        </SectionCard>
      )}

      {error && (
        <SectionCard title="加载失败">
          <p className="text-destructive text-sm">{error}</p>
          <div className="mt-3">
            <Button size="sm" variant="outline" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重试
            </Button>
          </div>
        </SectionCard>
      )}

      {notice && (
        <SectionCard title="最近一次操作">
          <p className="text-sm">{notice}</p>
        </SectionCard>
      )}

      {actionError && (
        <SectionCard title="操作未完成">
          <p className="text-destructive text-sm">{actionError}</p>
        </SectionCard>
      )}

      {/* ------------------------------------------------------------ 内容模板 */}
      <SectionCard
        title="内容模板"
        description="详情描述 / 使用说明 / 注意事项 / 卖点段落四类。变量用 {brand} 这样占位，保存时会校验占位符与变量绑定**双向**一致——少一个会在填充时被报成「缺变量」，多一个是死配置。"
        extra={
          <div className="flex gap-2">
            {writable && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setEditingId(null);
                  setEditing(emptyTemplateInput());
                }}
              >
                <Plus className="h-4 w-4" />
                新建模板
              </Button>
            )}
            <Button size="sm" variant="outline" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载模板…
          </p>
        ) : templates.length === 0 ? (
          <EmptyState title="还没有内容模板" description={templateEmptyHint()} />
        ) : (
          <ul className="divide-border divide-y">
            {templates.map((template) => (
              <li
                key={template.id}
                className="flex flex-wrap items-center justify-between gap-3 py-3"
              >
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{template.name}</span>
                    <Badge tone={template.enabled ? 'success' : 'neutral'}>
                      {template.enabled ? '启用' : '停用'}
                    </Badge>
                    <Badge tone="neutral">{contentTypeLabel(template.contentType)}</Badge>
                    <Badge tone="neutral">
                      {template.variables.length === 0
                        ? '固定文本'
                        : `${template.variables.length} 个变量`}
                    </Badge>
                  </div>
                  <p className="text-muted-foreground text-xs">{templateSummary(template)}</p>
                  <p className="text-muted-foreground text-xs">源语言 {template.sourceLocale}</p>
                </div>
                <div className="flex flex-wrap gap-2">
                  <Button
                    size="sm"
                    disabled={!writable || !template.enabled}
                    onClick={() => void openPicker(template.id, template.sourceLocale)}
                  >
                    批量填充
                  </Button>
                  {writable && (
                    <>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void openTranslations(template)}
                      >
                        译文
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => openTemplateEdit(template)}
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void toggleTemplateEnabled(template)}
                        disabled={busy}
                      >
                        {template.enabled ? '停用' : '启用'}
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void removeTemplate(template)}
                        disabled={busy}
                      >
                        <Trash2 className="h-4 w-4" />
                        删除
                      </Button>
                    </>
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 卖点短语库 */}
      <SectionCard
        title="卖点短语库"
        description="卖点陈述 / 特性描述 / 适用场景 / 注意事项短语。绑到 PHRASE_* 的变量从这里取值；某个池子一条短语都没有时，填充会把这个变量报成「短语池为空」，而不是渲染成空白。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className={control}
              value={phraseType}
              onChange={(e) => setPhraseType(e.target.value)}
              aria-label="按类型筛选短语"
            >
              <option value="">全部类型</option>
              {PHRASE_TYPES.map((t) => (
                <option key={t.value} value={t.value}>
                  {t.label}
                </option>
              ))}
            </select>
            {writable && (
              <Button
                size="sm"
                variant="outline"
                onClick={() => {
                  setEditingPhraseId(null);
                  setEditingPhrase(emptyPhraseInput());
                }}
              >
                <Plus className="h-4 w-4" />
                新建短语
              </Button>
            )}
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载短语…
          </p>
        ) : phrases.length === 0 ? (
          <EmptyState title="还没有卖点短语" description={phraseEmptyHint()} />
        ) : (
          <ul className="divide-border divide-y">
            {phrases.map((phrase) => (
              <li key={phrase.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    <Badge tone="neutral">{phraseTypeLabel(phrase.phraseType)}</Badge>
                    <Badge tone={phrase.enabled ? 'success' : 'neutral'}>
                      {phrase.enabled ? '启用' : '停用'}
                    </Badge>
                  </div>
                  <p className="text-sm">{phrase.text}</p>
                </div>
                {writable && (
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => {
                        setEditingPhraseId(phrase.id);
                        setEditingPhrase({
                          code: phrase.code,
                          text: phrase.text,
                          phraseType: phrase.phraseType,
                          enabled: phrase.enabled,
                          version: phrase.version,
                        });
                      }}
                    >
                      编辑
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void togglePhraseEnabled(phrase)}
                      disabled={busy}
                    >
                      {phrase.enabled ? '停用' : '启用'}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void removePhrase(phrase)}
                      disabled={busy}
                    >
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  </div>
                )}
              </li>
            ))}
          </ul>
        )}
        {!loading && phraseTotal > phrases.length && (
          <p className="text-muted-foreground mt-3 text-xs">
            只显示前 {phrases.length} 条，共 {phraseTotal} 条。可用类型筛选缩小范围。
          </p>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 填充批次 */}
      <SectionCard
        title="填充批次"
        description="同步执行：提交返回时正文与缺失报告已经落库。缺变量不是失败——正文照样产出，占位符被剥掉，逐条说清哪个变量没填上。"
        extra={
          <div className="flex items-center gap-2">
            <select
              className={control}
              value={renderStatus}
              onChange={(e) => {
                setRenderStatus(e.target.value);
                setRenderPage(0);
              }}
              aria-label="按状态筛选批次"
            >
              <option value="">全部状态</option>
              {RENDER_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载批次…
          </p>
        ) : renders.length === 0 ? (
          <EmptyState title="还没有填充批次" description={renderEmptyHint(renderStatus !== '')} />
        ) : (
          <ul className="divide-border divide-y">
            {renders.map((row) => (
              <li key={row.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{row.templateName}</span>
                    <Badge tone={renderStatusTone(row.status)}>{row.statusLabel}</Badge>
                    <Badge tone="neutral">{row.locale}</Badge>
                    <Badge tone="neutral">{row.contentTypeLabel}</Badge>
                  </div>
                  <p className="text-muted-foreground text-xs">
                    {row.skuCount} 个 SKU，{row.completeCount} 段已填好
                    {row.incompleteCount > 0 ? `，${row.incompleteCount} 段缺变量` : ''}
                    {row.issueCount > 0 ? `（${row.issueCount} 次）` : ''}
                  </p>
                </div>
                <Button size="sm" variant="outline" onClick={() => void openDetail(row.id)}>
                  查看详情
                </Button>
              </li>
            ))}
          </ul>
        )}
        {!loading && renderTotal > renders.length && (
          <div className="mt-3 flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={renderPage === 0}
              onClick={() => setRenderPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-xs">
              第 {renderPage + 1} 页 / 共 {renderTotal} 条
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={(renderPage + 1) * PAGE_SIZE >= renderTotal}
              onClick={() => setRenderPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 段落 */}
      <SectionCard
        title="填充结果"
        description="04 刊登与 15 客服只引用「已填好」的段落。缺变量的段落也会列出来，但它们不能发布。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className={control}
              value={sectionStatus}
              onChange={(e) => {
                setSectionStatus(e.target.value);
                setSectionPage(0);
              }}
              aria-label="按状态筛选段落"
            >
              <option value="">全部状态</option>
              {SECTION_STATUSES.map((s) => (
                <option key={s.value} value={s.value}>
                  {s.label}
                </option>
              ))}
            </select>
            <a
              className="border-control-border inline-flex items-center gap-2 rounded-md border px-3 py-2 text-sm"
              href={csvExportUrl({
                skuId: null,
                locale: null,
                contentType: null,
                status: sectionStatus || null,
              })}
            >
              <Download className="h-4 w-4" />
              导出 CSV
            </a>
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载段落…
          </p>
        ) : sections.length === 0 ? (
          <EmptyState title="还没有段落" description={sectionEmptyHint(sectionStatus !== '')} />
        ) : (
          <ul className="divide-border divide-y">
            {sections.map((section) => (
              <li key={section.id} className="space-y-2 py-3">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{section.skuCode}</span>
                  <span className="text-muted-foreground text-xs">{section.spuName}</span>
                  <Badge tone="neutral">{section.contentTypeLabel}</Badge>
                  <Badge tone="neutral">{section.locale}</Badge>
                  <Badge tone={sectionStatusTone(section.status)}>{section.statusLabel}</Badge>
                  <Badge tone={missingTone(section.issues.length)}>
                    {section.issues.length === 0
                      ? '变量齐全'
                      : `缺 ${section.issues.length} 个变量`}
                  </Badge>
                  <span className="text-muted-foreground text-xs">{section.charLength} 字</span>
                </div>
                <p className="whitespace-pre-wrap text-sm">{section.text}</p>
                <p className="text-muted-foreground text-xs">
                  短语快照：{usedPhrasesText(section.usedPhrases)}
                </p>
                {section.issues.length > 0 && (
                  <ul className="border-destructive/40 bg-destructive/5 space-y-1 rounded-md border p-2">
                    {section.issues.map((issue) => (
                      <li key={issue.id} className="text-xs">
                        <span className="font-medium">
                          {'{'}
                          {issue.variableName}
                          {'}'}
                        </span>
                        <span className="text-muted-foreground">
                          {' '}
                          {issue.issueKindLabel}：{issue.detail} 怎么修：{issueFix(issue.issueKind)}
                        </span>
                      </li>
                    ))}
                  </ul>
                )}
              </li>
            ))}
          </ul>
        )}
        {!loading && sectionTotal > sections.length && (
          <div className="mt-3 flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={sectionPage === 0}
              onClick={() => setSectionPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-xs">
              第 {sectionPage + 1} 页 / 共 {sectionTotal} 条
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={(sectionPage + 1) * PAGE_SIZE >= sectionTotal}
              onClick={() => setSectionPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 批次详情 */}
      {detail && (
        <SectionCard
          title="批次详情：缺了什么，怎么修"
          description="按变量汇总——「brand 缺 200 次」比 200 行罗列有用得多，它直接对应「该去补品牌字段还是该去建短语」。"
          extra={
            <Button size="sm" variant="outline" onClick={() => setDetail(null)}>
              收起
            </Button>
          }
        >
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={renderStatusTone(detail.render.status)}>
                {detail.render.statusLabel}
              </Badge>
              <Badge tone="neutral">{detail.render.templateName}</Badge>
              <Badge tone="neutral">{detail.render.locale}</Badge>
              <span className="text-muted-foreground text-xs">{detail.note}</span>
            </div>

            {detail.missingVariables.length === 0 ? (
              <p className="text-sm">这一批的变量都填上了，段落可直接被 04 刊登与 15 客服引用。</p>
            ) : (
              <ul className="divide-border divide-y">
                {detail.missingVariables.map((missing) => (
                  <li
                    key={`${missing.variableName}-${missing.issueKind}`}
                    className="space-y-1 py-2"
                  >
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-sm font-medium">
                        {'{'}
                        {missing.variableName}
                        {'}'}
                      </span>
                      <Badge tone={missingTone(1)}>{missing.issueKindLabel}</Badge>
                      <span className="text-muted-foreground text-xs">
                        缺 {missing.occurrences} 次
                      </span>
                    </div>
                    <p className="text-muted-foreground text-xs">{missing.detail}</p>
                    <p className="text-xs">怎么修：{issueFix(missing.issueKind)}</p>
                  </li>
                ))}
              </ul>
            )}

            {detail.skus.length > 0 && (
              <div className="space-y-1">
                <p className="text-sm font-medium">逐 SKU 结果</p>
                <ul className="divide-border divide-y text-xs">
                  {detail.skus.map((row) => (
                    <li key={row.id} className="flex flex-wrap items-center gap-2 py-1.5">
                      <span>{row.skuCode}</span>
                      <span className="text-muted-foreground">{row.spuName}</span>
                      <Badge tone={row.completeCount > 0 ? 'success' : 'warning'}>
                        {row.outcomeLabel}
                      </Badge>
                      {row.reason && <span className="text-muted-foreground">{row.reason}</span>}
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>
        </SectionCard>
      )}

      {/* ------------------------------------------------------------ 模板编辑器 */}
      {editing && (
        <Editor
          title={editingId ? '编辑内容模板' : '新建内容模板'}
          description="正文用 {brand} 这样占位。变量必须与占位符双向对齐；花括号要配平。不要求至少一个占位符——固定的注意事项完全可以不带变量。"
          close={() => {
            setEditing(null);
            setEditingId(null);
          }}
        >
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="模板编码" hint="英文字母、数字、下划线或短横线；建后不可改">
                <input
                  className={control}
                  value={editing.code}
                  disabled={editingId !== null}
                  onChange={(e) => setEditing({ ...editing, code: e.target.value })}
                />
              </Field>
              <Field label="模板名称">
                <input
                  className={control}
                  value={editing.name}
                  onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                />
              </Field>
            </div>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="内容类型">
                <select
                  className={control}
                  value={editing.contentType}
                  onChange={(e) => setEditing({ ...editing, contentType: e.target.value })}
                >
                  {CONTENT_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </Field>
              <Field label="源语言" hint="BCP-47 简写，如 zh-CN。源语言不需要译文行">
                <input
                  className={control}
                  value={editing.sourceLocale}
                  onChange={(e) => setEditing({ ...editing, sourceLocale: e.target.value })}
                />
              </Field>
            </div>
            <Field label="模板正文" hint="长文本（最多 20000 字符），没有平台字符上限">
              <textarea
                className={`${control} min-h-28`}
                value={editing.body}
                onChange={(e) => setEditing({ ...editing, body: e.target.value })}
              />
            </Field>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">变量绑定（{editing.variables?.length ?? 0}）</p>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setEditing({
                      ...editing,
                      variables: [
                        ...(editing.variables ?? []),
                        {
                          name: `var${(editing.variables?.length ?? 0) + 1}`,
                          source: 'SPU_BRAND',
                          slotNo: editing.variables?.length ?? 0,
                        },
                      ],
                    })
                  }
                >
                  <Plus className="h-4 w-4" />
                  添加变量
                </Button>
              </div>
              {(editing.variables ?? []).length === 0 ? (
                <p className="text-muted-foreground text-xs">
                  没有变量 = 固定文本。「请勿在充电时拆解本机」这种注意事项不需要任何变量。
                </p>
              ) : (
                <ul className="space-y-2">
                  {(editing.variables ?? []).map((variable, index) => {
                    // 契约里变量项是 `X | null`（整体替换语义下前端可能短暂持有空项），
                    // 所以这里先归一化成非空对象再改，避免 `{...null}` 展开崩溃。
                    const current = variable ?? { name: '', source: 'SPU_BRAND', slotNo: index };
                    const patch = (change: Partial<typeof current>) => {
                      const next = (editing.variables ?? []).map((v, i) =>
                        i === index
                          ? { ...(v ?? { name: '', source: 'SPU_BRAND', slotNo: i }), ...change }
                          : v,
                      );
                      setEditing({
                        ...editing,
                        variables: next.filter((v) => v != null),
                      });
                    };
                    return (
                      <li key={index} className="flex flex-wrap items-end gap-2">
                        <Field label="变量名" hint="正文里写 {名字}">
                          <input
                            className={control}
                            value={current.name}
                            onChange={(e) => patch({ name: e.target.value })}
                          />
                        </Field>
                        <Field label="绑定源">
                          <select
                            className={control}
                            value={current.source}
                            onChange={(e) => patch({ source: e.target.value })}
                          >
                            {VARIABLE_SOURCES.map((s) => (
                              <option key={s.value} value={s.value}>
                                {s.group} · {s.label}
                              </option>
                            ))}
                          </select>
                        </Field>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() =>
                            setEditing({
                              ...editing,
                              variables: (editing.variables ?? []).filter((_, i) => i !== index),
                            })
                          }
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </li>
                    );
                  })}
                </ul>
              )}
              {templateFormProblem(editing) && (
                <p className="text-destructive text-xs">{templateFormProblem(editing)}</p>
              )}
            </div>

            <Button onClick={() => void saveTemplateForm()} disabled={busy}>
              <Save className="h-4 w-4" />
              保存模板
            </Button>
          </div>
        </Editor>
      )}

      {/* ------------------------------------------------------------ 译文编辑器 */}
      {translatingTemplate && (
        <Editor
          title={`多语言版本：${translatingTemplate.name}`}
          description="译文的占位符必须与源语言完全一致。AI 预填最常见的三个坑（漏一个、多一个、拼错）都在这里被挡住。"
          close={() => {
            setTranslatingTemplate(null);
            setEditingTranslation(null);
            setEditingTranslationId(null);
            setCoverage(null);
          }}
        >
          <div className="space-y-3">
            <div className="flex flex-wrap items-center gap-2">
              <Button
                size="sm"
                variant="outline"
                onClick={() => void checkCoverage(translatingTemplate)}
              >
                检查常见语言完整度
              </Button>
              {writable && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setEditingTranslationId(null);
                    setEditingTranslation(emptyTranslationInput(translatingTemplate.body));
                  }}
                >
                  <Plus className="h-4 w-4" />
                  新建译文
                </Button>
              )}
            </div>

            {coverage && <p className="text-muted-foreground text-xs">{coverage.note}</p>}

            <div className="bg-muted/50 space-y-1 rounded-md p-2">
              <p className="text-muted-foreground text-xs">
                源语言 {translatingTemplate.sourceLocale} 正文
              </p>
              <p className="text-sm">{translatingTemplate.body}</p>
            </div>

            {translations.length === 0 ? (
              <EmptyState
                title="还没有译文"
                description={translationEmptyHint(translatingTemplate.sourceLocale)}
              />
            ) : (
              <ul className="divide-border divide-y">
                {translations.map((translation) => (
                  <li
                    key={translation.id}
                    className="flex flex-wrap items-center justify-between gap-2 py-2"
                  >
                    <div className="min-w-0 space-y-1">
                      <div className="flex items-center gap-2">
                        <Badge tone="neutral">{translation.locale}</Badge>
                        <Badge tone={translation.status === 'REVIEWED' ? 'success' : 'warning'}>
                          {translation.statusLabel}
                        </Badge>
                        <Badge tone="neutral">{translation.originLabel}</Badge>
                      </div>
                      <p className="text-sm">{translation.name}</p>
                    </div>
                    {writable && (
                      <div className="flex gap-2">
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => {
                            setEditingTranslationId(translation.id);
                            setEditingTranslation({
                              locale: translation.locale,
                              name: translation.name,
                              body: translation.body,
                              origin: translation.origin,
                              status: translation.status,
                              version: translation.version,
                            });
                          }}
                        >
                          编辑
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          onClick={() => void removeTranslation(translation)}
                          disabled={busy}
                        >
                          <Trash2 className="h-4 w-4" />
                          删除
                        </Button>
                      </div>
                    )}
                  </li>
                ))}
              </ul>
            )}

            {editingTranslation && (
              <div className="space-y-3 border-t pt-3">
                <div className="grid gap-3 sm:grid-cols-3">
                  <Field label="语言">
                    <input
                      className={control}
                      value={editingTranslation.locale}
                      disabled={editingTranslationId !== null}
                      onChange={(e) =>
                        setEditingTranslation({ ...editingTranslation, locale: e.target.value })
                      }
                    />
                  </Field>
                  <Field label="译文名称">
                    <input
                      className={control}
                      value={editingTranslation.name}
                      onChange={(e) =>
                        setEditingTranslation({ ...editingTranslation, name: e.target.value })
                      }
                    />
                  </Field>
                  <Field label="来源">
                    <select
                      className={control}
                      value={editingTranslation.origin}
                      onChange={(e) =>
                        setEditingTranslation({ ...editingTranslation, origin: e.target.value })
                      }
                    >
                      {TRANSLATION_ORIGINS.map((o) => (
                        <option key={o.value} value={o.value}>
                          {o.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>
                <Field label="译文状态">
                  <select
                    className={control}
                    value={editingTranslation.status}
                    onChange={(e) =>
                      setEditingTranslation({ ...editingTranslation, status: e.target.value })
                    }
                  >
                    {TRANSLATION_STATUSES.map((s) => (
                      <option key={s.value} value={s.value}>
                        {s.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="译文正文">
                  <textarea
                    className={`${control} min-h-24`}
                    value={editingTranslation.body}
                    onChange={(e) =>
                      setEditingTranslation({ ...editingTranslation, body: e.target.value })
                    }
                  />
                </Field>
                {translationPlaceholdersProblem(
                  translatingTemplate.body,
                  editingTranslation.body,
                ) && (
                  <p className="text-destructive text-xs">
                    {translationPlaceholdersProblem(
                      translatingTemplate.body,
                      editingTranslation.body,
                    )}
                  </p>
                )}
                <Button onClick={() => void saveTranslationForm()} disabled={busy}>
                  <Save className="h-4 w-4" />
                  保存译文
                </Button>
              </div>
            )}
          </div>
        </Editor>
      )}

      {/* ------------------------------------------------------------ 短语编辑器 */}
      {editingPhrase && (
        <Editor
          title={editingPhraseId ? '编辑卖点短语' : '新建卖点短语'}
          description="短语是直接填进正文里的文本，长句（最多 2000 字符），不能带花括号。同一类型内文本唯一——同一句写成两条会让短语池「看起来更大」而填充结果还是那几种。"
          close={() => {
            setEditingPhrase(null);
            setEditingPhraseId(null);
          }}
        >
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="短语编码">
                <input
                  className={control}
                  value={editingPhrase.code}
                  onChange={(e) => setEditingPhrase({ ...editingPhrase, code: e.target.value })}
                />
              </Field>
              <Field label="短语类型">
                <select
                  className={control}
                  value={editingPhrase.phraseType}
                  onChange={(e) =>
                    setEditingPhrase({ ...editingPhrase, phraseType: e.target.value })
                  }
                >
                  {PHRASE_TYPES.map((t) => (
                    <option key={t.value} value={t.value}>
                      {t.label}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
            <Field label="短语内容">
              <textarea
                className={`${control} min-h-20`}
                value={editingPhrase.text}
                onChange={(e) => setEditingPhrase({ ...editingPhrase, text: e.target.value })}
              />
            </Field>
            {phraseTextProblem(editingPhrase.text) && (
              <p className="text-destructive text-xs">{phraseTextProblem(editingPhrase.text)}</p>
            )}
            <Button onClick={() => void savePhraseForm()} disabled={busy}>
              <Save className="h-4 w-4" />
              保存短语
            </Button>
          </div>
        </Editor>
      )}

      {/* ------------------------------------------------------------ 批量填充 */}
      {rendering && (
        <Editor
          title="批量填充描述"
          description="选模板 + 语言 + 一批 SKU。同步执行，返回时正文与缺失报告已落库。缺变量不是失败：正文照样产出，占位符被剥掉，逐条报告缺了什么。"
          close={() => setRendering(null)}
        >
          <div className="space-y-3">
            <div className="grid gap-3 sm:grid-cols-2">
              <Field label="内容模板">
                <select
                  className={control}
                  value={rendering.templateId}
                  onChange={(e) => {
                    const next = templates.find((t) => t.id === e.target.value);
                    setRendering({
                      ...rendering,
                      templateId: e.target.value,
                      locale: next?.sourceLocale ?? rendering.locale,
                    });
                  }}
                >
                  {templates
                    .filter((t) => t.enabled)
                    .map((t) => (
                      <option key={t.id} value={t.id}>
                        {t.name}（{contentTypeLabel(t.contentType)}）
                      </option>
                    ))}
                </select>
              </Field>
              <Field
                label="输出语言"
                hint="只能选源语言与已配译文：缺译文的语言在提交时会被整批拒，不会悄悄退回源语言"
              >
                <select
                  className={control}
                  value={rendering.locale}
                  onChange={(e) => setRendering({ ...rendering, locale: e.target.value })}
                >
                  {localeChoices.map((locale) => (
                    <option key={locale} value={locale}>
                      {locale}
                      {currentTemplate && locale === currentTemplate.sourceLocale
                        ? '（源语言）'
                        : ''}
                    </option>
                  ))}
                </select>
              </Field>
            </div>

            <div className="flex items-end gap-2">
              <Field label="搜索 SKU">
                <input
                  className={control}
                  value={skuKeyword}
                  onChange={(e) => setSkuKeyword(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter') void searchSkus();
                  }}
                />
              </Field>
              <Button size="sm" variant="outline" onClick={() => void searchSkus()}>
                搜索
              </Button>
            </div>

            {skus.length === 0 ? (
              <EmptyState
                title="没有可填充的 SKU"
                description="换个关键词，或者先在 SKU 列表里确认商品已经建好。"
              />
            ) : (
              <ul className="divide-border max-h-64 divide-y overflow-y-auto">
                {skus.map((sku) => (
                  <li key={sku.id} className="flex items-center gap-2 py-1.5 text-sm">
                    <input
                      type="checkbox"
                      checked={selected.has(sku.id)}
                      onChange={(e) => {
                        const next = new Set(selected);
                        if (e.target.checked) {
                          if (next.size >= MAX_SKUS_PER_RENDER) {
                            setActionError(`单次最多为 ${MAX_SKUS_PER_RENDER} 个 SKU 填充描述`);
                            return;
                          }
                          next.add(sku.id);
                        } else next.delete(sku.id);
                        setSelected(next);
                      }}
                    />
                    <span>{sku.code}</span>
                    <span className="text-muted-foreground">{sku.spuName}</span>
                  </li>
                ))}
              </ul>
            )}

            <p className="text-muted-foreground text-xs">已选 {selected.size} 个 SKU</p>
            <Button
              onClick={() => void submitRender()}
              disabled={busy || selected.size === 0 || !rendering.templateId}
            >
              <Sparkles className="h-4 w-4" />
              开始填充
            </Button>
          </div>
        </Editor>
      )}
    </div>
  );
}
