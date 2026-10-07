'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ArrowLeft,
  Download,
  Loader2,
  Plus,
  RefreshCw,
  Save,
  Sparkles,
  Trash2,
} from 'lucide-react';
import type {
  TitleGenerationDetail,
  TitleGenerationRow,
  TitleKeywordInput,
  TitleKeywordRow,
  TitleOverview,
  TitleRecord,
  TitleTemplateInput,
  TitleTemplateRow,
} from '../../../../../packages/api-contracts/src/catalog';
import { Editor, Field, control } from '../common/fields';
import { emptySkuFilters, fetchSkuPage } from './sku-list-model';
import {
  GENERATION_STATUSES,
  KEYWORD_TYPES,
  MAX_SKUS_PER_GENERATION,
  RECORD_STATUSES,
  VARIABLE_SOURCES,
  charLengthText,
  csvExportUrl,
  deleteKeyword,
  deleteTemplate,
  deselectRecord,
  emptyKeywordInput,
  emptyTemplateInput,
  errorText,
  fetchGenerationDetail,
  fetchGenerations,
  fetchKeywords,
  fetchRecords,
  fetchTemplate,
  fetchTemplates,
  fetchTitleOverview,
  generateTitles,
  generatedHint,
  generationEmptyHint,
  generationStatusLabel,
  generationStatusTone,
  keywordCodeProblem,
  keywordEmptyHint,
  keywordProvenance,
  keywordTextProblem,
  keywordTypeLabel,
  platformsSizeProblem,
  recordEmptyHint,
  recordStatusLabel,
  recordStatusTone,
  rejectRecord,
  saveKeyword,
  saveTemplate,
  selectRecord,
  setKeywordEnabled,
  setTemplateEnabled,
  similarityText,
  similarityTone,
  skuRejectionText,
  skusSizeProblem,
  starterKeywords,
  templateEmptyHint,
  templateProblem,
  templateSummary,
} from './title-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.4.2.1 标题库工作区。
 *
 * <p>页面按「配模板 → 建词根 → 勾 SKU 批量生成 → 逐条选用」排：
 *
 * <ul>
 *   <li><b>标题模板</b> —— 变量占位（品牌 + 品类 + 核心词 + 卖点 + 修饰词）、
 *       各平台长度上限、以及重复度上限（相似但不雷同的严格程度）。
 *   <li><b>标题词根</b> —— 核心词 / 卖点 / 修饰词三类。修饰词是差异化的主力，
 *       池子太小就凑不出不雷同的候选（页面会明说这一点）。
 *   <li><b>批量生成</b> —— 蓝图点名的三个参数：SKU 范围、平台、词根。
 *       <strong>同步执行</strong>：返回时候选已经在库里，不轮询。
 *   <li><b>候选选用</b> —— 每条候选带「与本 SKU 已有标题的最大相似度」，
 *       供 04 刊登选用或导出。
 * </ul>
 *
 * <p>界面上反复强调「相似但不雷同」与「被拒是正常结果」：重复度控制写在后端算法里，
 * 词池不足时算法会拒绝而不是放宽阈值硬凑。不说清楚，运营会以为「只产出 2 条要 3 条」
 * 是系统坏了，于是改用更差的写法，反而更容易撞上重复铺货判定。
 */
export function TitleWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<TitleOverview | null>(null);
  const [templates, setTemplates] = useState<TitleTemplateRow[]>([]);
  const [generations, setGenerations] = useState<TitleGenerationRow[]>([]);
  const [generationTotal, setGenerationTotal] = useState(0);
  const [generationPage, setGenerationPage] = useState(0);
  const [generationKeyword, setGenerationKeyword] = useState('');
  const [generationStatus, setGenerationStatus] = useState('');

  const [detail, setDetail] = useState<TitleGenerationDetail | null>(null);
  const [records, setRecords] = useState<TitleRecord[]>([]);
  const [recordTotal, setRecordTotal] = useState(0);
  const [recordPage, setRecordPage] = useState(0);
  const [recordKeyword, setRecordKeyword] = useState('');
  const [recordPlatform, setRecordPlatform] = useState('');
  const [recordStatus, setRecordStatus] = useState('');

  const [keywords, setKeywords] = useState<TitleKeywordRow[]>([]);
  const [keywordTotal, setKeywordTotal] = useState(0);
  const [keywordType, setKeywordType] = useState('');

  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  const [editing, setEditing] = useState<TitleTemplateInput | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editingKeyword, setEditingKeyword] = useState<TitleKeywordInput | null>(null);
  const [editingKeywordId, setEditingKeywordId] = useState<string | null>(null);
  const [picking, setPicking] = useState<{ templateId: string } | null>(null);
  const [skus, setSkus] = useState<{ id: string; code: string; name: string; spuName: string }[]>([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [pickedPlatforms, setPickedPlatforms] = useState<Set<string>>(new Set());
  const [skuKeyword, setSkuKeyword] = useState('');

  const load = useCallback(async () => {
    try {
      const [o, t, g, k, r] = await Promise.all([
        fetchTitleOverview(),
        fetchTemplates(),
        fetchGenerations({
          keyword: generationKeyword,
          status: generationStatus,
          page: generationPage,
          size: PAGE_SIZE,
        }),
        fetchKeywords({ keyword: null, keywordType: keywordType || null, page: 0, size: 50 }),
        fetchRecords({
          skuId: null,
          platform: recordPlatform || null,
          status: recordStatus || null,
          keyword: recordKeyword || null,
          generationId: null,
          page: recordPage,
          size: PAGE_SIZE,
        }),
      ]);
      setOverview(o);
      setTemplates(t);
      setGenerations(g.items);
      setGenerationTotal(g.total);
      setKeywords(k.items.map((i) => i.keyword));
      setKeywordTotal(k.total);
      setRecords(r.items);
      setRecordTotal(r.total);
      setError(null);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [generationKeyword, generationStatus, generationPage, keywordType, recordPlatform, recordStatus, recordKeyword, recordPage]);

  useEffect(() => {
    void load();
  }, [load]);

  async function refreshDetail(generationId: string) {
    try {
      setDetail(await fetchGenerationDetail(generationId));
    } catch (e) {
      setActionError(errorText(e, '批次详情刷新失败'));
    }
  }

  // ------------------------------------------------------------ 模板

  async function saveTemplateForm() {
    if (!editing) return;
    const problem = templateProblem(editing);
    if (problem) {
      setActionError(problem);
      return;
    }
    setBusy(true);
    try {
      await saveTemplate(editingId, editing);
      setEditing(null);
      setEditingId(null);
      setNotice('模板已保存。');
      await load();
    } catch (e) {
      setActionError(errorText(e, '模板保存失败'));
    } finally {
      setBusy(false);
    }
  }

  function openTemplateEdit(template: TitleTemplateRow) {
    // 编辑时以**后端返回的**变量与平台为准，不用表单里存一份副本：
    // 那样两份会漂移，而「当前有哪些变量」这个问题的答案必须是库里那一份。
    setEditingId(template.id);
    setEditing({
      code: template.code,
      name: template.name,
      note: template.note,
      pattern: template.pattern,
      maxSimilarityPermille: template.maxSimilarityPermille,
      candidateCount: template.candidateCount,
      variables: template.variables.map((v) => ({ name: v.name, source: v.source, slotNo: v.slotNo })),
      platforms: template.platforms.map((p) => ({ platform: p.platform, maxLength: p.maxLength })),
      enabled: template.enabled,
      version: template.version,
    });
  }

  async function toggleTemplateEnabled(template: TitleTemplateRow) {
    setBusy(true);
    try {
      await setTemplateEnabled(template.id, !template.enabled);
      await load();
    } catch (e) {
      setActionError(errorText(e, '模板启停失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeTemplate(template: TitleTemplateRow) {
    setBusy(true);
    try {
      await deleteTemplate(template.id, template.version);
      setNotice(`模板「${template.code}」已删除。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '模板删除失败'));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 词根

  async function saveKeywordForm() {
    if (!editingKeyword) return;
    setBusy(true);
    try {
      await saveKeyword(editingKeywordId, editingKeyword);
      setEditingKeyword(null);
      setEditingKeywordId(null);
      setNotice('词根已保存。');
      await load();
    } catch (e) {
      setActionError(errorText(e, '词根保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function toggleKeywordEnabled(keyword: TitleKeywordRow) {
    setBusy(true);
    try {
      await setKeywordEnabled(keyword.id, !keyword.enabled);
      await load();
    } catch (e) {
      setActionError(errorText(e, '词根启停失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeKeyword(keyword: TitleKeywordRow) {
    setBusy(true);
    try {
      await deleteKeyword(keyword.id, keyword.version);
      setNotice(`词根「${keyword.text}」已删除。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '词根删除失败'));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 批量生成

  async function loadSkus() {
    try {
      const page = await fetchSkuPage({ ...emptySkuFilters, keyword: skuKeyword }, 0, 50);
      setSkus(
        page.items.map((s) => ({ id: s.id, code: s.code, name: s.name, spuName: s.spuName })),
      );
    } catch (e) {
      setActionError(errorText(e, 'SKU 列表加载失败'));
    }
  }

  async function openPicker(templateId: string) {
    setPickedPlatforms(new Set());
    setSelected(new Set());
    setPicking({ templateId });
    await loadSkus();
  }

  async function submitGeneration() {
    if (!picking) return;
    setBusy(true);
    try {
      const generation = await generateTitles({
        templateId: picking.templateId,
        skuIds: Array.from(selected),
        platforms: Array.from(pickedPlatforms),
        keywordCodes: [],
      });
      setNotice(generatedHint(generation));
      setPicking(null);
      setSelected(new Set());
      setPickedPlatforms(new Set());
      await load();
      await refreshDetail(generation.id);
    } catch (e) {
      setActionError(errorText(e, '批量生成失败'));
    } finally {
      setBusy(false);
    }
  }

  // ------------------------------------------------------------ 选用

  async function act(fn: () => Promise<unknown>, ok: string) {
    setBusy(true);
    try {
      await fn();
      setNotice(ok);
      await load();
      if (detail) await refreshDetail(detail.generation.id);
    } catch (e) {
      setActionError(errorText(e, '操作失败'));
    } finally {
      setBusy(false);
    }
  }

  const activeTemplates = useMemo(() => templates.filter((t) => t.enabled), [templates]);
  const pickingTemplate = useMemo(
    () => templates.find((t) => t.id === picking?.templateId) ?? null,
    [templates, picking],
  );
  const selectedIds = useMemo(() => Array.from(selected), [selected]);

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <Link
          href="/products"
          className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-sm"
        >
          <ArrowLeft className="h-4 w-4" />
          返回商品中心
        </Link>
        <h1 className="text-h2 font-semibold tracking-tight">标题库</h1>
        <p className="text-copy-13 text-muted-foreground">
          沉淀可复用的标题模板与标题记录，支持变量占位（品牌 + 品类 + 核心词 + 卖点），
          批量生成差异化标题——同一个商品在多平台给不同标题，用来规避
          <strong className="text-foreground">重复铺货判定</strong>。
          重复度由生成算法显式控制：每条候选都带着「与本 SKU 已有标题的最大相似度」，
          相似但不雷同。
        </p>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="标题模板" value={overview?.templateCount ?? 0} icon={<Sparkles className="h-4 w-4" />} />
        <StatCard
          label="词根"
          value={overview?.keywordCount ?? 0}
          delta={overview?.modifierKeywordCount ?? 0}
          comparisonLabel="其中修饰词"
        />
        <StatCard label="标题候选" value={overview?.recordCount ?? 0} />
        {/* 「未产出满额」不是坏事也不是好事，是约束在起作用，用 neutral。 */}
        <StatCard
          label="已选用"
          value={overview?.selectedCount ?? 0}
          delta={overview?.truncatedCount ?? 0}
          comparisonLabel="其中被平台上限裁剪"
          polarity={(overview?.truncatedCount ?? 0) > 0 ? 'negative' : 'positive'}
        />
      </section>

      {error && (
        <SectionCard title="加载失败">
          <div className="text-destructive space-y-2 text-sm">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
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

      {/* ------------------------------------------------------------ 标题模板 */}
      <SectionCard
        title="标题模板"
        description="变量占位（品牌 + 品类 + 核心词 + 卖点 + 修饰词）、各平台长度上限，以及重复度上限。模板可复用，编码建后不可改。"
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
          <EmptyState title="还没有标题模板" description={templateEmptyHint()} />
        ) : (
          <ul className="divide-border divide-y">
            {templates.map((template) => (
              <li key={template.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                <div className="min-w-0 space-y-1">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{template.name}</span>
                    <Badge tone={template.enabled ? 'success' : 'neutral'}>
                      {template.enabled ? '启用' : '停用'}
                    </Badge>
                    <Badge tone="neutral">{template.variables.length} 个变量</Badge>
                    <Badge tone="neutral">{template.platforms.length} 个平台</Badge>
                  </div>
                  <p className="text-muted-foreground text-xs">{templateSummary(template)}</p>
                </div>
                <div className="flex gap-2">
                  <Button
                    size="sm"
                    disabled={!writable || !template.enabled || template.platforms.length === 0}
                    onClick={() => void openPicker(template.id)}
                  >
                    批量生成
                  </Button>
                  {writable && (
                    <>
                      <Button size="sm" variant="outline" onClick={() => openTemplateEdit(template)}>
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

      {/* ------------------------------------------------------------ 词根 */}
      <SectionCard
        title="标题词根"
        description="核心词给主体、卖点给理由、修饰词拉开平台间差异。修饰词是「相似但不雷同」的主力——池子太小就凑不出不雷同的候选。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <select
              className={control}
              value={keywordType}
              onChange={(e) => setKeywordType(e.target.value)}
            >
              <option value="">全部类型</option>
              {KEYWORD_TYPES.map((t) => (
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
                  setEditingKeywordId(null);
                  setEditingKeyword(emptyKeywordInput());
                }}
              >
                <Plus className="h-4 w-4" />
                新建词根
              </Button>
            )}
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载词根…
          </p>
        ) : keywords.length === 0 ? (
          <EmptyState title="还没有词根" description={keywordEmptyHint()} />
        ) : (
          <>
            <ul className="divide-border divide-y">
              {keywords.map((keyword) => (
                <li key={keyword.id} className="flex flex-wrap items-center justify-between gap-3 py-2">
                  <div className="flex min-w-0 items-center gap-2">
                    <Badge tone="neutral">{keywordTypeLabel(keyword.keywordType)}</Badge>
                    <span className="font-medium">{keyword.text}</span>
                    <span className="text-muted-foreground text-xs">{keyword.code}</span>
                    {!keyword.enabled && <Badge tone="neutral">停用</Badge>}
                  </div>
                  {writable && (
                    <div className="flex gap-2">
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => {
                          setEditingKeywordId(keyword.id);
                          setEditingKeyword({
                            code: keyword.code,
                            text: keyword.text,
                            keywordType: keyword.keywordType,
                            enabled: keyword.enabled,
                            version: keyword.version,
                          });
                        }}
                      >
                        编辑
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void toggleKeywordEnabled(keyword)}
                        disabled={busy}
                      >
                        {keyword.enabled ? '停用' : '启用'}
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void removeKeyword(keyword)}
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
            <p className="text-muted-foreground mt-3 text-xs">共 {keywordTotal} 个词根（最多显示 50 个）</p>
          </>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 生成批次 */}
      <SectionCard
        title="生成批次"
        description="批量生成是同步的：提交后候选已落库，可以直接逐条选用。相似度超限的组合会被拒绝并给出原因，而不是放宽阈值凑数。"
      >
        <div className="mb-3 flex flex-wrap gap-2">
          <input
            className={control}
            placeholder="搜模板名"
            value={generationKeyword}
            onChange={(e) => {
              setGenerationKeyword(e.target.value);
              setGenerationPage(0);
            }}
          />
          <select
            className={control}
            value={generationStatus}
            onChange={(e) => {
              setGenerationStatus(e.target.value);
              setGenerationPage(0);
            }}
          >
            <option value="">全部状态</option>
            {GENERATION_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </div>

        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载批次…
          </p>
        ) : generations.length === 0 ? (
          <EmptyState
            title="没有生成批次"
            description={generationEmptyHint(!!(generationKeyword || generationStatus))}
          />
        ) : (
          <>
            <ul className="divide-border divide-y">
              {generations.map((generation) => (
                <li key={generation.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div className="min-w-0 space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{generation.templateName}</span>
                      <Badge tone={generationStatusTone(generation.status)}>
                        {generationStatusLabel(generation.status)}
                      </Badge>
                    </div>
                    <p className="text-muted-foreground text-xs">
                      {generation.skuCount} 个 SKU · {generation.candidateCount} 条候选
                      {generation.rejectedCount > 0 ? ` · ${generation.rejectedCount} 条被拒` : ''}
                    </p>
                  </div>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => void refreshDetail(generation.id)}
                  >
                    查看候选
                  </Button>
                </li>
              ))}
            </ul>
            {generationTotal > PAGE_SIZE && (
              <Pager
                page={generationPage}
                total={generationTotal}
                unit="个批次"
                onChange={setGenerationPage}
              />
            )}
          </>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 批次详情 */}
      {detail && (
        <SectionCard
          title="批次候选"
          description={`${detail.generation.templateName} · ${detail.generation.candidateCount} 条候选，其中 ${detail.generation.rejectedCount} 条因重复度超限被拒。相似度是与本 SKU 已有标题的最大值，越低说明离重复铺货判定越远。`}
        >
          {detail.skus.filter((s) => s.rejectedCount > 0).length > 0 && (
            <div className="border-destructive/40 bg-destructive/5 mb-3 rounded-md border p-3">
              <p className="text-sm font-medium">有 SKU 没产出满额</p>
              <ul className="text-muted-foreground mt-1 space-y-1 text-xs">
                {detail.skus
                  .filter((s) => skuRejectionText(s))
                  .map((s) => (
                    <li key={s.id}>
                      <span className="text-foreground">{s.skuCode}</span>：
                      {skuRejectionText(s)}
                    </li>
                  ))}
              </ul>
              <p className="text-muted-foreground mt-2 text-xs">
                这不是失败：重复度控制在起作用。去「标题词根」里加几个修饰词，通常就能多产出候选。
              </p>
            </div>
          )}
          <RecordTable
            records={detail.records}
            writable={writable}
            busy={busy}
            onSelect={(id) =>
              void act(() => selectRecord(id), '已选用。这条标题可供 04 刊登使用。')
            }
            onDeselect={(id) => void act(() => deselectRecord(id), '已取消选用。')}
            onReject={(id) => void act(() => rejectRecord(id), '已淘汰这条候选。')}
          />
        </SectionCard>
      )}

      {/* ------------------------------------------------------------ 候选总览 */}
      <SectionCard
        title="标题候选"
        description="全部 SKU 的候选。同一 SKU 同一平台只能选用一条；被选用的标题内容不可再改（库里与平台上已发布的必须对得上）。"
        extra={
          <a
            href={csvExportUrl({
              skuId: null,
              platform: recordPlatform || null,
              status: recordStatus || null,
            })}
            className="border-control-border inline-flex items-center gap-1 rounded-md border px-3 py-2 text-sm hover:bg-muted"
          >
            <Download className="h-4 w-4" />
            导出 CSV
          </a>
        }
      >
        <div className="mb-3 flex flex-wrap gap-2">
          <input
            className={control}
            placeholder="搜标题、SKU 编码或商品名"
            value={recordKeyword}
            onChange={(e) => {
              setRecordKeyword(e.target.value);
              setRecordPage(0);
            }}
          />
          <input
            className={control}
            placeholder="平台，如 AMAZON"
            value={recordPlatform}
            onChange={(e) => {
              setRecordPlatform(e.target.value);
              setRecordPage(0);
            }}
          />
          <select
            className={control}
            value={recordStatus}
            onChange={(e) => {
              setRecordStatus(e.target.value);
              setRecordPage(0);
            }}
          >
            <option value="">全部状态</option>
            {RECORD_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </div>

        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载候选…
          </p>
        ) : records.length === 0 ? (
          <EmptyState
            title="没有标题候选"
            description={recordEmptyHint(!!(recordKeyword || recordPlatform || recordStatus))}
          />
        ) : (
          <>
            <RecordTable
              records={records}
              writable={writable}
              busy={busy}
              onSelect={(id) => void act(() => selectRecord(id), '已选用。这条标题可供 04 刊登使用。')}
              onDeselect={(id) => void act(() => deselectRecord(id), '已取消选用。')}
              onReject={(id) => void act(() => rejectRecord(id), '已淘汰这条候选。')}
            />
            {recordTotal > PAGE_SIZE && (
              <Pager
                page={recordPage}
                total={recordTotal}
                unit="条候选"
                onChange={setRecordPage}
              />
            )}
          </>
        )}
      </SectionCard>

      {/* ------------------------------------------------------------ 模板编辑 */}
      {editing && (
        <Editor
          title={editingId ? '编辑标题模板' : '新建标题模板'}
          description="变量占位与平台长度上限整体替换。重复度上限越严，候选之间与已有标题离得越远，但可能产出得更少。"
          close={() => {
            setEditing(null);
            setEditingId(null);
          }}
        >
          <div className="max-h-[70vh] space-y-4 overflow-y-auto px-1 pb-2">
            <Field label="模板编码" hint="建后不可改：历史候选按这套变量绑定解释">
              <input
                className={control}
                value={editing.code}
                disabled={!!editingId}
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
            <Field
              label="标题模板"
              hint="用 {变量名} 占位。至少一个变量，且至少一个绑定到词池——否则每个 SKU 只有一种文本，候选会彼此雷同"
            >
              <input
                className={control}
                value={editing.pattern}
                onChange={(e) => setEditing({ ...editing, pattern: e.target.value })}
              />
            </Field>
            <div className="grid gap-3 sm:grid-cols-2">
              <Field
                label="重复度上限（‰）"
                hint="任意两条标题的相似度超过它就换下一个组合。上界 999：1000 就是允许完全一样"
              >
                <input
                  type="number"
                  min={0}
                  max={999}
                  className={control}
                  value={editing.maxSimilarityPermille ?? ''}
                  onChange={(e) =>
                    setEditing({
                      ...editing,
                      maxSimilarityPermille: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </Field>
              <Field label="每个 SKU 每平台的候选条数" hint="1~8 条。超过 8 条后候选开始出现凑数的">
                <input
                  type="number"
                  min={1}
                  max={8}
                  className={control}
                  value={editing.candidateCount ?? ''}
                  onChange={(e) =>
                    setEditing({
                      ...editing,
                      candidateCount: e.target.value === '' ? null : Number(e.target.value),
                    })
                  }
                />
              </Field>
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">变量占位</p>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setEditing({
                      ...editing,
                      variables: [...(editing.variables ?? []), { name: '', source: 'SPU_BRAND', slotNo: (editing.variables ?? []).length }],
                    })
                  }
                >
                  <Plus className="h-4 w-4" />
                  添加变量
                </Button>
              </div>
              {(editing.variables ?? []).map((variable, index) =>
                variable === null ? null : (
                  <div key={index} className="grid gap-2 sm:grid-cols-[1fr_1fr_auto]">
                    <input
                      className={control}
                      placeholder="变量名，如 brand"
                      value={variable.name}
                      onChange={(e) => {
                        const next = [...(editing.variables ?? [])];
                        next[index] = { ...variable, name: e.target.value };
                        setEditing({ ...editing, variables: next });
                      }}
                    />
                    <select
                      className={control}
                      value={variable.source}
                      onChange={(e) => {
                        const next = [...(editing.variables ?? [])];
                        next[index] = { ...variable, source: e.target.value };
                        setEditing({ ...editing, variables: next });
                      }}
                    >
                      {VARIABLE_SOURCES.map((source) => (
                        <option key={source.value} value={source.value}>
                          {source.group} · {source.label}
                        </option>
                      ))}
                    </select>
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
                  </div>
                ),
              )}
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">平台标题长度上限</p>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() =>
                    setEditing({
                      ...editing,
                      platforms: [...(editing.platforms ?? []), { platform: '', maxLength: 200 }],
                    })
                  }
                >
                  <Plus className="h-4 w-4" />
                  添加平台
                </Button>
              </div>
              {(editing.platforms ?? []).map((platform, index) =>
                platform === null ? null : (
                  <div key={index} className="grid gap-2 sm:grid-cols-[1fr_8rem_auto]">
                    <input
                      className={control}
                      placeholder="平台，如 AMAZON"
                      value={platform.platform}
                      onChange={(e) => {
                        const next = [...(editing.platforms ?? [])];
                        next[index] = { ...platform, platform: e.target.value };
                        setEditing({ ...editing, platforms: next });
                      }}
                    />
                    <input
                      type="number"
                      min={1}
                      max={255}
                      className={control}
                      value={platform.maxLength}
                      onChange={(e) => {
                        const next = [...(editing.platforms ?? [])];
                        next[index] = { ...platform, maxLength: Number(e.target.value) };
                        setEditing({ ...editing, platforms: next });
                      }}
                    />
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() =>
                        setEditing({
                          ...editing,
                          platforms: (editing.platforms ?? []).filter((_, i) => i !== index),
                        })
                      }
                    >
                      <Trash2 className="h-4 w-4" />
                    </Button>
                  </div>
                ),
              )}
              <p className="text-muted-foreground text-xs">
                没配平台的批次会被拒绝：平台标题有硬字符上限，不配就没法判断该裁到多少字。
              </p>
            </div>

            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                onClick={() => {
                  setEditing(null);
                  setEditingId(null);
                }}
              >
                取消
              </Button>
              <Button onClick={() => void saveTemplateForm()} disabled={busy}>
                <Save className="h-4 w-4" />
                保存模板
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {/* ------------------------------------------------------------ 词根编辑 */}
      {editingKeyword && (
        <Editor
          title={editingKeywordId ? '编辑词根' : '新建词根'}
          description="词根是标题的差异化素材。被标题引用过的词根只能停用不能删——标题记录上快照的是它的文本。"
          close={() => {
            setEditingKeyword(null);
            setEditingKeywordId(null);
          }}
        >
          <div className="space-y-4">
            <Field label="词根编码" hint="建后不可改：历史候选按 code 溯源">
              <input
                className={control}
                value={editingKeyword.code}
                disabled={!!editingKeywordId}
                onChange={(e) => setEditingKeyword({ ...editingKeyword, code: e.target.value })}
              />
            </Field>
            <Field label="词根内容">
              <input
                className={control}
                value={editingKeyword.text}
                onChange={(e) => setEditingKeyword({ ...editingKeyword, text: e.target.value })}
              />
            </Field>
            <Field label="词根类型">
              <select
                className={control}
                value={editingKeyword.keywordType}
                onChange={(e) =>
                  setEditingKeyword({ ...editingKeyword, keywordType: e.target.value })
                }
              >
                {KEYWORD_TYPES.map((type) => (
                  <option key={type.value} value={type.value}>
                    {type.label} —— {type.hint}
                  </option>
                ))}
              </select>
            </Field>
            <div className="border-control-border rounded-md border p-3">
              <p className="text-muted-foreground text-xs">常用起始词（点一下填进上面的输入框）</p>
              <div className="mt-2 flex flex-wrap gap-2">
                {starterKeywords(editingKeyword.keywordType).map((word) => (
                  <Button
                    key={word}
                    size="sm"
                    variant="outline"
                    onClick={() => setEditingKeyword({ ...editingKeyword, text: word })}
                  >
                    {word}
                  </Button>
                ))}
              </div>
            </div>
            <div className="flex justify-end gap-2">
              <Button
                variant="outline"
                onClick={() => {
                  setEditingKeyword(null);
                  setEditingKeywordId(null);
                }}
              >
                取消
              </Button>
              <Button onClick={() => void saveKeywordForm()} disabled={busy}>
                <Save className="h-4 w-4" />
                保存词根
              </Button>
            </div>
          </div>
        </Editor>
      )}

      {/* ------------------------------------------------------------ 批量生成面板 */}
      {picking && pickingTemplate && (
        <Editor
          title="批量生成标题"
          description="蓝图点名的三个参数：SKU 范围、平台、词根。生成是同步的，提交后候选已落库。"
          close={() => setPicking(null)}
        >
          <div className="space-y-4">
            <div className="border-control-border rounded-md border p-3">
              <p className="text-sm font-medium">模板</p>
              <p className="text-muted-foreground text-xs">{templateSummary(pickingTemplate)}</p>
            </div>

            <div className="space-y-2">
              <p className="text-sm font-medium">目标平台（{pickedPlatforms.size} 个）</p>
              <div className="flex flex-wrap gap-2">
                {pickingTemplate.platforms.map((platform) => {
                  const on = pickedPlatforms.has(platform.platform);
                  return (
                    <Button
                      key={platform.platform}
                      size="sm"
                      variant={on ? 'default' : 'outline'}
                      onClick={() => {
                        const next = new Set(pickedPlatforms);
                        if (on) next.delete(platform.platform);
                        else next.add(platform.platform);
                        setPickedPlatforms(next);
                      }}
                    >
                      {platform.platform} ≤{platform.maxLength}
                    </Button>
                  );
                })}
              </div>
              <p className="text-muted-foreground text-xs">
                平台标题有硬字符上限，超出的候选会被按字符边界裁剪并标记出来。
              </p>
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <p className="text-sm font-medium">SKU 范围（已选 {selected.size} 个）</p>
                <input
                  className={`${control} max-w-xs`}
                  placeholder="搜 SKU 编码或名称"
                  value={skuKeyword}
                  onChange={(e) => setSkuKeyword(e.target.value)}
                  onBlur={() => void loadSkus()}
                />
              </div>
              {skus.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  没有可选择的 SKU。先在商品中心建好 SKU 再回来。
                </p>
              ) : (
                <ul className="max-h-64 space-y-1 overflow-y-auto">
                  {skus.map((sku) => (
                    <li key={sku.id}>
                      <label className="flex items-center gap-2 text-sm">
                        <input
                          type="checkbox"
                          checked={selected.has(sku.id)}
                          onChange={(e) => {
                            const next = new Set(selected);
                            if (e.target.checked) next.add(sku.id);
                            else next.delete(sku.id);
                            setSelected(next);
                          }}
                        />
                        <span className="font-mono">{sku.code}</span>
                        <span className="text-muted-foreground">{sku.spuName}</span>
                      </label>
                    </li>
                  ))}
                </ul>
              )}
              <p className="text-muted-foreground text-xs">
                一次最多 {MAX_SKUS_PER_GENERATION} 个 SKU。词根留空表示用全部启用的核心词。
              </p>
            </div>

            <div className="border-destructive/40 bg-destructive/5 rounded-md border p-3">
              <p className="text-sm font-medium">重复度控制</p>
              <p className="text-muted-foreground text-xs">
                相似度上限 {similarityText(pickingTemplate.maxSimilarityPermille)}。
                任何两条标题（含该 SKU 在其它平台已生成的标题）超过它就被拒绝并给出原因——
                算法不会放宽阈值硬凑一条雷同的。词池太小就会出现「没产出满额」，那是约束在起作用。
              </p>
            </div>

            {skusSizeProblem(selected.size) && (
              <p className="text-destructive text-sm">{skusSizeProblem(selected.size)}</p>
            )}
            {platformsSizeProblem(pickedPlatforms.size) && (
              <p className="text-destructive text-sm">{platformsSizeProblem(pickedPlatforms.size)}</p>
            )}

            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setPicking(null)}>
                取消
              </Button>
              <Button
                onClick={() => void submitGeneration()}
                disabled={busy || selected.size === 0 || pickedPlatforms.size === 0}
              >
                {busy && <Loader2 className="h-4 w-4 animate-spin" />}
                生成 {selectedIds.length} 个 SKU × {pickedPlatforms.size} 个平台
              </Button>
            </div>
          </div>
        </Editor>
      )}
    </div>
  );
}

/** 候选表：相似度用 tone 标出来，因为「离重复铺货判定有多远」是这个页面的核心信息。 */
function RecordTable({
  records,
  writable,
  busy,
  onSelect,
  onDeselect,
  onReject,
}: {
  records: TitleRecord[];
  writable: boolean;
  busy: boolean;
  onSelect: (id: string) => void;
  onDeselect: (id: string) => void;
  onReject: (id: string) => void;
}) {
  return (
    <ul className="divide-border divide-y">
      {records.map((record) => (
        <li key={record.id} className="flex flex-wrap items-start justify-between gap-3 py-3">
          <div className="min-w-0 space-y-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="font-medium">{record.title}</span>
              <Badge tone={recordStatusTone(record.status)}>{recordStatusLabel(record.status)}</Badge>
              <Badge tone={similarityTone(record.similarityPermille)}>
                相似度 {similarityText(record.similarityPermille)}
              </Badge>
              {record.truncated && <Badge tone="warning">已按平台上限裁剪</Badge>}
            </div>
            <p className="text-muted-foreground text-xs">
              {record.skuCode} · {record.spuName} · {record.platform} 第 {record.variantNo} 条 ·{' '}
              {charLengthText(record, null)}
            </p>
            <p className="text-muted-foreground text-xs">{keywordProvenance(record)}</p>
          </div>
          {writable && (
            <div className="flex gap-2">
              {record.status === 'SELECTED' ? (
                <Button size="sm" variant="outline" onClick={() => onDeselect(record.id)} disabled={busy}>
                  取消选用
                </Button>
              ) : (
                <>
                  <Button size="sm" onClick={() => onSelect(record.id)} disabled={busy}>
                    选用
                  </Button>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() => onReject(record.id)}
                    disabled={busy || record.status === 'REJECTED'}
                  >
                    淘汰
                  </Button>
                </>
              )}
            </div>
          )}
        </li>
      ))}
    </ul>
  );
}

function Pager({
  page,
  total,
  unit,
  onChange,
}: {
  page: number;
  total: number;
  unit: string;
  onChange: (page: number) => void;
}) {
  return (
    <div className="mt-3 flex items-center gap-2 text-sm">
      <Button size="sm" variant="outline" disabled={page === 0} onClick={() => onChange(Math.max(0, page - 1))}>
        上一页
      </Button>
      <span className="text-muted-foreground">
        第 {page + 1} / {Math.ceil(total / PAGE_SIZE)} 页 · 共 {total} {unit}
      </span>
      <Button
        size="sm"
        variant="outline"
        disabled={(page + 1) * PAGE_SIZE >= total}
        onClick={() => onChange(page + 1)}
      >
        下一页
      </Button>
    </div>
  );
}