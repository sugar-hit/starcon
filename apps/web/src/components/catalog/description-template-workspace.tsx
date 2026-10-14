'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import { Loader2, Play, Plus, RefreshCw, Save, Trash2 } from 'lucide-react';
import type {
  DescriptionTemplateDraft,
  DescriptionRenderProduct,
  ImageSlotDraft,
  SectionDraft,
  VariableDraft,
  DescriptionRenderRow,
  DescriptionSectionInput,
  DescriptionSectionRow,
  DescriptionTemplateInput,
  DescriptionTemplateOptions,
  DescriptionTemplateOverview,
  DescriptionTemplateRow,
} from './description-template-model';
import {
  DEFAULT_PHRASE_BY_SECTION,
  IMAGE_BIND_SOURCES,
  MAX_SECTIONS,
  RENDER_STATUSES,
  SECTION_TYPES,
  VARIABLE_SOURCES,
  countImgTags,
  deleteDescriptionTemplate,
  emptyRenderInput,
  emptySectionInput,
  emptyTemplateInput,
  errorText,
  fetchDescriptionRender,
  fetchDescriptionRenders,
  fetchDescriptionSections,
  fetchDescriptionTemplateOptions,
  fetchDescriptionTemplates,
  fetchDescriptionTemplateOverview,
  htmlToReadable,
  issueKindLabel,
  normalizeDraft,
  imageSlotNumberingProblem,
  issueFix,
  missingSectionTypes,
  missingSummary,
  renderDescriptionTemplate,
  renderEmptyHint,
  renderEmptyHintForStatus,
  renderPublishable,
  renderStatusLabel,
  renderStatusTone,
  saveDescriptionTemplate,
  sectionEmptyHint,
  sectionTypeLabel,
  sectionTypesProblem,
  setDescriptionTemplateEnabled,
  templateEmptyHint,
  templateSummary,
} from './description-template-model';
import { Editor, Field, control } from '../common/fields';
import { emptySkuFilters, fetchSkuPage } from './sku-list-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/** 编辑草稿形状：契约形状归一化之后的东西（见 `normalizeDraft`）。 */
type Draft = DescriptionTemplateDraft;

/**
 * 4.2.2.2 描述模板工作区。
 *
 * <p>页面按「配骨架 → 渲染 → 核对」排：
 *
 * <ul>
 *   <li><b>描述模板</b> —— 五段骨架（产品介绍 / 使用场景 / 规格参数 / 注意事项 /
 *       售后承诺），每段带变量绑定与图片插入位。
 *   <li><b>渲染</b> —— 选模板 + SKU + 语言，<strong>同步执行</strong>：
 *       返回时 HTML 与缺失报告已落库，不轮询。
 *   <li><b>渲染结果</b> —— 逐段 HTML + 图片落点 + <strong>缺变量逐条报告</strong>。
 * </ul>
 *
 * <p>界面上反复强调三件事：
 *
 * <ol>
 *   <li><b>缺变量是一种结果，不是错误。</b>正文照样产出（占位符已剥掉），
 *       段落被标成「缺变量或缺图」不能上传，并逐条说清「哪个变量、为什么缺、怎么修」。
 *   <li><b>只有「五段全填好」才能直接上传。</b>这一句放在结果区最显眼的位置，
 *       因为 4.2.1.1 会照单上传，而发出去之后改不回来。
 *   <li><b>页面上显示的 HTML 与发给平台的字节一致。</b>所以预览只做「去标签换行」，
 *       绝不解析重排——页面好看不能作为「可以上传」的依据。
 * </ol>
 */
export function DescriptionTemplateWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.spu.update');

  const [overview, setOverview] = useState<DescriptionTemplateOverview | null>(null);
  const [options, setOptions] = useState<DescriptionTemplateOptions | null>(null);
  const [templates, setTemplates] = useState<DescriptionTemplateRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [editing, setEditing] = useState<Draft | null>(null);
  const [editingSections, setEditingSections] = useState<DescriptionSectionRow[] | null>(null);
  const [busy, setBusy] = useState(false);

  const [renderForm, setRenderForm] = useState(emptyRenderInput());
  const [skus, setSkus] = useState<{ id: string; code: string; name: string }[]>([]);
  const [skuKeyword, setSkuKeyword] = useState('');
  const [rendering, setRendering] = useState(false);
  const [renderError, setRenderError] = useState<string | null>(null);

  const [renders, setRenders] = useState<DescriptionRenderRow[]>([]);
  const [renderTotal, setRenderTotal] = useState(0);
  const [renderPage, setRenderPage] = useState(0);
  const [renderStatus, setRenderStatus] = useState('');
  const [detail, setDetail] = useState<DescriptionRenderProduct | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const [o, t] = await Promise.all([
        fetchDescriptionTemplateOverview(),
        fetchDescriptionTemplates(),
      ]);
      setOverview(o);
      setTemplates(t);
    } catch (error) {
      setLoadError(errorText(error));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    fetchDescriptionTemplateOptions()
      .then(setOptions)
      .catch(() => setOptions(null));
  }, []);

  const loadRenders = useCallback(async (page: number, status: string) => {
    try {
      const result = await fetchDescriptionRenders({
        keyword: null,
        status: status || null,
        skuId: null,
        page,
        size: PAGE_SIZE,
      });
      setRenders(result.items ?? []);
      setRenderTotal(result.total);
    } catch (error) {
      setNotice(errorText(error));
    }
  }, []);

  useEffect(() => {
    void loadRenders(renderPage, renderStatus);
  }, [loadRenders, renderPage, renderStatus]);

  useEffect(() => {
    const timer = setTimeout(() => {
      fetchSkuPage({ ...emptySkuFilters, keyword: skuKeyword }, 0, 20)
        .then((page) =>
          setSkus(
            (page.items ?? []).map((item) => ({
              id: item.id,
              code: item.code,
              name: item.name,
            })),
          ),
        )
        .catch(() => setSkus([]));
    }, 250);
    return () => clearTimeout(timer);
  }, [skuKeyword]);

  const openNew = () => {
    setEditingSections(null);
    setEditing(normalizeDraft(emptyTemplateInput()));
    setNotice(null);
  };

  const openEdit = async (row: DescriptionTemplateRow) => {
    setNotice(null);
    try {
      const sections = await fetchDescriptionSections(row.id);
      setEditingSections(sections);
      setEditing(normalizeDraft(rowToInput(row, sections)));
    } catch (error) {
      setNotice(errorText(error));
    }
  };

  const save = async () => {
    if (!editing) return;
    const problem = validateTemplate(editing);
    if (problem) {
      setNotice(problem);
      return;
    }
    setBusy(true);
    setNotice(null);
    try {
      // 编辑已有模板时用 id 寻址；id 从 contentTemplateId 反查不到，所以用版本号定位。
      const target = editingSections
        ? templates.find(
            (row) => row.sections.length === editing.sections?.length && row.code === editing.code,
          )
        : null;
      const saved = await saveDescriptionTemplate(target?.id ?? null, editing);
      setEditing(null);
      setEditingSections(null);
      setNotice(
        `已保存「${saved.code}」，共 ${saved.sectionCount} 段、${saved.imageSlotCount} 个插图位`,
      );
      await load();
    } catch (error) {
      setNotice(errorText(error));
    } finally {
      setBusy(false);
    }
  };

  const submitRender = async () => {
    if (!renderForm.templateId || !renderForm.skuId) {
      setRenderError('请先选一个描述模板和一个 SKU');
      return;
    }
    setRendering(true);
    setRenderError(null);
    try {
      const product = await renderDescriptionTemplate({
        templateId: renderForm.templateId,
        skuId: renderForm.skuId,
        locale: renderForm.locale,
        images: [],
      });
      setDetail(product);
      setNotice(
        renderPublishable(product.status)
          ? '五段全填好，这份 HTML 可以直接交给 4.2.1.1 或平台上传'
          : missingSummary(product.issueCount),
      );
      await Promise.all([load(), loadRenders(renderPage, renderStatus)]);
    } catch (error) {
      setRenderError(errorText(error));
    } finally {
      setRendering(false);
    }
  };

  const openDetail = async (id: string) => {
    setDetailLoading(true);
    try {
      setDetail(await fetchDescriptionRender(id));
    } catch (error) {
      setNotice(errorText(error));
    } finally {
      setDetailLoading(false);
    }
  };

  const stats = useMemo(
    () =>
      overview
        ? [
            {
              label: '描述模板',
              value: overview.templateCount,
              comparisonLabel: `启用中 ${overview.enabledTemplateCount}`,
            },
            { label: '段落', value: overview.sectionCount, comparisonLabel: '五类段落骨架' },
            {
              label: '图片插入位',
              value: overview.imageSlotCount,
              comparisonLabel: '渲染时落成 img 标签',
            },
            {
              label: '缺段落模板',
              value: overview.missingSectionTypeCount,
              comparisonLabel: '还没配齐五类段落',
            },
            {
              label: '渲染结果',
              value: overview.renderCount,
              comparisonLabel: `全填好 ${overview.succeededRenderCount}`,
            },
            {
              label: '缺失报告',
              value: overview.issueCount,
              comparisonLabel: '变量未绑定 / 必填图位缺图',
            },
          ]
        : [],
    [overview],
  );

  return (
    <div className="space-y-4">
      {notice && (
        <div className="border-control-border bg-muted/40 rounded-md border px-3 py-2 text-sm">
          {notice}
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {stats.map((item) => (
          <StatCard
            key={item.label}
            label={item.label}
            value={item.value}
            comparisonLabel={item.comparisonLabel}
          />
        ))}
      </div>

      {overview && <p className="text-muted-foreground text-xs leading-relaxed">{overview.note}</p>}

      <SectionCard
        title="描述模板"
        description="五段骨架（产品介绍 / 使用场景 / 规格参数 / 注意事项 / 售后承诺）。段落内容、变量绑定与多语言译文复用 3.4.2.2 的内容模板，所以 {{尺寸}} 的绑定源在全仓只有一个答案。"
        extra={
          <>
            <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
              {loading ? <Loader2 className="animate-spin" /> : <RefreshCw />} 刷新
            </Button>
            {writable && (
              <Button size="sm" onClick={openNew}>
                <Plus /> 新建描述模板
              </Button>
            )}
          </>
        }
      >
        {loadError && <p className="text-destructive text-sm">读取失败：{loadError}</p>}
        {!loadError && loading && (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="animate-spin" /> 正在加载描述模板…
          </p>
        )}
        {!loadError && !loading && templates.length === 0 && (
          <EmptyState title="还没有描述模板" description={templateEmptyHint()} />
        )}
        {!loadError && !loading && templates.length > 0 && (
          <ul className="space-y-2">
            {templates.map((row) => {
              const missing = missingSectionTypes(row);
              return (
                <li
                  key={row.id}
                  className="border-control-border hover:bg-muted/40 rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{row.name}</span>
                    <code className="text-muted-foreground text-xs">{row.code}</code>
                    <Badge tone={row.enabled ? 'success' : 'neutral'}>
                      {row.enabled ? '启用' : '停用'}
                    </Badge>
                    {missing.length > 0 && (
                      <span className="text-xs text-amber-600">缺 {missing.join(' / ')}</span>
                    )}
                    <span className="ml-auto flex items-center gap-2">
                      {writable && (
                        <>
                          <Button variant="outline" size="sm" onClick={() => void openEdit(row)}>
                            编辑
                          </Button>
                          <Button
                            variant="outline"
                            size="sm"
                            onClick={() =>
                              void setDescriptionTemplateEnabled(row.id, !row.enabled).then(load)
                            }
                          >
                            {row.enabled ? '停用' : '启用'}
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() =>
                              void deleteDescriptionTemplate(row.id, row.version)
                                .then(load)
                                .catch((error: unknown) => setNotice(errorText(error)))
                            }
                          >
                            <Trash2 /> 删除
                          </Button>
                        </>
                      )}
                    </span>
                  </div>
                  <p className="text-muted-foreground mt-1 text-xs">
                    源语言 {row.sourceLocale} · 已配 {row.localeCount} 种语言 · 渲染{' '}
                    {row.renderCount} 次
                  </p>
                  <p className="text-muted-foreground mt-1 text-xs">{templateSummary(row)}</p>
                </li>
              );
            })}
          </ul>
        )}
      </SectionCard>

      <SectionCard
        title="渲染描述 HTML"
        description="选一个模板、一个 SKU、一种语言，同步渲染出可直接上传的详情页 HTML。缺译文不整批拒，而是把那一段标成「缺变量或缺图」并逐条报告。"
      >
        <div className="grid gap-3 md:grid-cols-4">
          <Field label="描述模板">
            <select
              className={control}
              value={renderForm.templateId}
              onChange={(event) => setRenderForm({ ...renderForm, templateId: event.target.value })}
            >
              <option value="">请选择</option>
              {templates
                .filter((row) => row.enabled)
                .map((row) => (
                  <option key={row.id} value={row.id}>
                    {row.name}（{row.code}）
                  </option>
                ))}
            </select>
          </Field>
          <Field label="语言" hint="非源语言且没配译文时，那一段会被报成缺译文">
            <input
              className={control}
              value={renderForm.locale}
              onChange={(event) => setRenderForm({ ...renderForm, locale: event.target.value })}
              placeholder="zh-CN"
            />
          </Field>
          <Field label="搜索 SKU">
            <input
              className={control}
              value={skuKeyword}
              onChange={(event) => setSkuKeyword(event.target.value)}
              placeholder="按编码或名称"
            />
          </Field>
          <Field label="SKU">
            <select
              className={control}
              value={renderForm.skuId}
              onChange={(event) => setRenderForm({ ...renderForm, skuId: event.target.value })}
            >
              <option value="">请选择</option>
              {skus.map((sku) => (
                <option key={sku.id} value={sku.id}>
                  {sku.code} {sku.name}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <div className="mt-3 flex items-center gap-3">
          <Button size="sm" onClick={() => void submitRender()} disabled={rendering || !writable}>
            {rendering ? <Loader2 className="animate-spin" /> : <Play />} 渲染
          </Button>
          {renderError && <span className="text-destructive text-sm">{renderError}</span>}
        </div>
      </SectionCard>

      <SectionCard
        title="渲染结果"
        description="只有「五段全填好」的结果可以直接交给 4.2.1.1 与平台上传。"
        extra={
          <select
            className={`${control} w-48`}
            value={renderStatus}
            onChange={(event) => {
              setRenderPage(0);
              setRenderStatus(event.target.value);
            }}
          >
            <option value="">全部状态</option>
            {RENDER_STATUSES.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        }
      >
        {renders.length === 0 ? (
          <EmptyState
            title="没有渲染记录"
            description={renderStatus ? renderEmptyHintForStatus() : renderEmptyHint()}
          />
        ) : (
          <>
            <ul className="space-y-2">
              {renders.map((row) => (
                <li
                  key={row.id}
                  className="border-control-border hover:bg-muted/40 rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className={renderStatusTone(row.status)}>
                      {renderStatusLabel(row.status)}
                    </span>
                    <span className="text-sm">
                      {row.templateName} · {row.skuCode} · {row.locale}
                    </span>
                    <span className="text-muted-foreground text-xs">
                      {row.completeCount}/{row.sectionCount} 段已填好 · {row.imageCount} 张图 ·{' '}
                      {row.issueCount} 条缺失
                    </span>
                    <Button
                      variant="outline"
                      size="sm"
                      className="ml-auto"
                      onClick={() => void openDetail(row.id)}
                    >
                      查看 HTML
                    </Button>
                  </div>
                  {row.note && <p className="text-muted-foreground mt-1 text-xs">{row.note}</p>}
                </li>
              ))}
            </ul>
            <div className="mt-3 flex items-center justify-between text-sm">
              <span className="text-muted-foreground">
                共 {renderTotal} 条 · 第 {renderPage + 1} 页
              </span>
              <span className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={renderPage === 0}
                  onClick={() => setRenderPage(renderPage - 1)}
                >
                  上一页
                </Button>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={(renderPage + 1) * PAGE_SIZE >= renderTotal}
                  onClick={() => setRenderPage(renderPage + 1)}
                >
                  下一页
                </Button>
              </span>
            </div>
          </>
        )}
      </SectionCard>

      {detail && (
        <SectionCard
          title={`渲染结果详情 · ${renderStatusLabel(detail.status)}`}
          description={`模板 ${detail.templateCode} · SKU ${detail.skuCode} · ${detail.locale}`}
          extra={
            <Button variant="ghost" size="sm" onClick={() => setDetail(null)}>
              关闭
            </Button>
          }
        >
          <div
            className={
              renderPublishable(detail.status)
                ? 'rounded-md border border-emerald-600/40 bg-emerald-500/10 px-3 py-2 text-sm'
                : 'rounded-md border border-amber-600/40 bg-amber-500/10 px-3 py-2 text-sm'
            }
          >
            {renderPublishable(detail.status)
              ? '五段全填好：这份 HTML 可以直接交给 4.2.1.1 编辑器或平台上传。'
              : '这份结果不能直接发到平台：请补齐下面的缺失后重新渲染。'}
            {detail.note && <p className="mt-1 text-xs">{detail.note}</p>}
          </div>

          <div className="mt-3 grid gap-2 text-xs md:grid-cols-4">
            <span>段落 {detail.sections.length}</span>
            <span>
              图片 {detail.imageCount} 个（HTML 里 &lt;img&gt; {countImgTags(detail.html)} 个）
            </span>
            <span>缺失报告 {detail.issueCount} 条</span>
            <span>{detail.createdAt ?? ''}</span>
          </div>

          {detail.issues.length > 0 && (
            <div className="mt-3 space-y-2">
              <h4 className="text-sm font-medium">缺什么、怎么修</h4>
              <ul className="space-y-1">
                {detail.issues.map((issue) => (
                  <li
                    key={issue.id}
                    className="border-control-border rounded-md border px-3 py-2 text-xs"
                  >
                    <span className="font-medium">{issue.variableName}</span>
                    <span className="text-muted-foreground">
                      （{issue.issueKindLabel || issueKindLabel(issue.issueKind)}）
                    </span>
                    <p className="mt-1">{issue.detail}</p>
                    <p className="text-muted-foreground mt-1">修法：{issueFix(issue)}</p>
                  </li>
                ))}
              </ul>
            </div>
          )}

          <div className="mt-4 space-y-3">
            <h4 className="text-sm font-medium">逐段 HTML（与发给平台的字节一致）</h4>
            {detail.sections.map((section) => (
              <div key={section.id} className="border-control-border rounded-md border px-3 py-2">
                <div className="flex flex-wrap items-center gap-2 text-sm">
                  <span className="font-medium">
                    {section.sectionTypeLabel || sectionTypeLabel(section.sectionType)}
                  </span>
                  <Badge tone={section.status === 'COMPLETE' ? 'success' : 'warning'}>
                    {section.status === 'COMPLETE' ? '已填好' : '缺变量或缺图'}
                  </Badge>
                  {section.images.length > 0 && (
                    <span className="text-muted-foreground text-xs">
                      图片位置 {section.images.map((image) => image.position).join(' / ')}
                    </span>
                  )}
                </div>
                <pre className="text-muted-foreground mt-2 overflow-x-auto whitespace-pre-wrap text-xs">
                  {htmlToReadable(section.html)}
                </pre>
                <details className="mt-1">
                  <summary className="text-muted-foreground cursor-pointer text-xs">
                    看原始 HTML
                  </summary>
                  <pre className="mt-1 overflow-x-auto whitespace-pre-wrap text-xs">
                    {section.html}
                  </pre>
                </details>
              </div>
            ))}
          </div>

          <details className="mt-4">
            <summary className="text-muted-foreground cursor-pointer text-xs">
              整份 HTML（data-position / data-section-type 可解析）
            </summary>
            <pre className="mt-1 overflow-x-auto whitespace-pre-wrap text-xs">{detail.html}</pre>
          </details>
        </SectionCard>
      )}

      {detailLoading && (
        <p className="text-muted-foreground flex items-center gap-2 text-sm">
          <Loader2 className="animate-spin" /> 正在读取渲染结果…
        </p>
      )}

      {editing && (
        <Editor
          title="描述模板"
          description="五段骨架。段落正文里用 {变量名} 占位，取不到值时会被剥掉并逐条报告，绝不会把占位符原样发到平台。"
          close={() => setEditing(null)}
        >
          <TemplateForm
            value={editing}
            onChange={setEditing}
            options={options}
            onSave={() => void save()}
            busy={busy}
          />
        </Editor>
      )}
    </div>
  );
}

/** 列表行 + 段落详情 → 编辑草稿。契约形状的归一化全部交给 `normalizeDraft`。 */
function rowToInput(
  row: DescriptionTemplateRow,
  sections: DescriptionSectionRow[],
): DescriptionTemplateInput {
  return {
    code: row.code,
    name: row.name,
    note: row.note ?? '',
    sourceLocale: row.sourceLocale,
    sections: sections.map((section) => ({
      sectionType: section.sectionType,
      body: section.body,
      variables: section.variables.map((variable) => ({
        name: variable.name,
        source: variable.source,
        slotNo: null,
      })),
      imageSlots: section.imageSlots.map((slot) => ({
        bindSource: slot.bindSource,
        caption: slot.caption,
        assetId: slot.assetId,
        required: slot.required,
        slotNo: slot.slotNo,
      })),
      showHeading: section.showHeading,
      contentTemplateId: section.contentTemplateId,
    })),
    enabled: row.enabled,
    version: row.version,
  };
}

/** 保存前的本地校验：与后端 `DescriptionTemplateRules` / `DescriptionRules` 同一口径。 */
export function validateTemplate(input: Draft): string | null {
  const sections = (input.sections ?? []).filter(
    (section): section is NonNullable<typeof section> => section !== null,
  );
  if (!input.code.trim()) return '模板编码不能为空';
  if (!/^[A-Za-z0-9][A-Za-z0-9_-]{0,31}$/.test(input.code.trim()))
    return '模板编码请使用英文字母、数字、下划线或短横线，长度 1~32 位';
  if (!input.name.trim()) return '模板名称不能为空';
  if (sections.length === 0) return '一个描述模板至少要有一段';
  if (sections.length > MAX_SECTIONS) return `一个描述模板最多 ${MAX_SECTIONS} 个段落`;
  const duplicate = sectionTypesProblem(sections.map((section) => section.sectionType));
  if (duplicate) return duplicate;
  if (!/^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})?$/.test(input.sourceLocale.trim()))
    return '语言标签请用 BCP-47 简写（zh-CN / en-US / ja-JP）';
  for (const section of sections) {
    const label = sectionTypeLabel(section.sectionType);
    if (!section.body.trim()) return `${label}：段落正文不能为空`;
    const binding = variableBindingOf(section);
    if (binding) return `${label}：${binding}`;
    const slots = section.imageSlots ?? [];
    const numbering = imageSlotNumberingProblem(slots);
    if (numbering) return `${label}：${numbering}`;
    for (const slot of slots)
      if (slot && slot.caption && slot.caption.length > 500)
        return `${label}：图片替代文本最多 500 个字符`;
  }
  return null;
}

function variableBindingOf(section: SectionDraft): string | null {
  const placeholders = placeholdersOfLocal(section.body);
  const names = (section.variables ?? []).map((variable) => variable.name.trim());
  for (const name of placeholders)
    if (!names.includes(name)) return `正文里的占位符 {${name}} 没有声明绑定源`;
  for (const name of names)
    if (!placeholders.includes(name)) return `变量 ${name} 在段落正文里没有出现`;
  return null;
}

function placeholdersOfLocal(body: string): string[] {
  const out: string[] = [];
  const value = body.replace(/\s+/g, ' ').trim();
  let i = 0;
  while (i < value.length) {
    const open = value.indexOf('{', i);
    if (open < 0) break;
    const close = value.indexOf('}', open + 1);
    if (close < 0) break;
    const name = value.slice(open + 1, close).trim();
    if (name && !out.includes(name)) out.push(name);
    i = close + 1;
  }
  return out;
}

function TemplateForm({
  value,
  onChange,
  options,
  onSave,
  busy,
}: {
  value: Draft;
  onChange: (next: Draft) => void;
  options: DescriptionTemplateOptions | null;
  onSave: () => void;
  busy: boolean;
}) {
  const problem = validateTemplate(value);
  const patch = (next: Partial<Draft>) => onChange({ ...value, ...next });
  const sections = value.sections;
  const used = new Set(sections.map((section) => section.sectionType));

  const patchSection = (index: number, next: Partial<SectionDraft>) => {
    patch({
      sections: value.sections.map((section, i) =>
        i === index ? { ...section, ...next } : section,
      ),
    });
  };

  return (
    <div className="space-y-4">
      <div className="grid gap-3 md:grid-cols-3">
        <Field label="模板编码" hint="建后不可改：段落背后的内容模板编码由它派生">
          <input
            className={control}
            value={value.code}
            disabled={value.version > 0}
            onChange={(event) => patch({ code: event.target.value.toUpperCase() })}
          />
        </Field>
        <Field label="模板名称">
          <input
            className={control}
            value={value.name}
            onChange={(event) => patch({ name: event.target.value })}
          />
        </Field>
        <Field label="源语言" hint="非源语言需要为每一段配译文，否则渲染时按缺译文报告">
          <input
            className={control}
            value={value.sourceLocale}
            onChange={(event) => patch({ sourceLocale: event.target.value })}
          />
        </Field>
      </div>

      <Field label="备注">
        <input
          className={control}
          value={value.note ?? ''}
          onChange={(event) => patch({ note: event.target.value })}
        />
      </Field>

      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <h4 className="text-sm font-medium">段落骨架</h4>
          <select
            className={`${control} w-44`}
            value=""
            onChange={(event) => {
              const type = event.target.value;
              if (!type) return;
              patch({ sections: [...sections, emptySectionInput(type)] });
            }}
          >
            <option value="">添加段落…</option>
            {SECTION_TYPES.filter((item) => !used.has(item.value)).map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
        </div>

        {sections.length === 0 && (
          <EmptyState
            title="还没有段落"
            description="至少配一段（建议五段齐全）才能渲染出描述 HTML。"
          />
        )}

        {sections.map((section, index) => (
          <SectionEditor
            key={`${section.sectionType}-${index}`}
            section={section}
            hint={sectionEmptyHint()}
            defaultPhrase={DEFAULT_PHRASE_BY_SECTION[section.sectionType] ?? ''}
            options={options}
            onChange={(next) => patchSection(index, next)}
            onRemove={() => patch({ sections: sections.filter((_, i) => i !== index) })}
            removable={sections.length > 1}
          />
        ))}
      </div>

      {problem && <p className="text-destructive text-sm">{problem}</p>}

      <div className="flex justify-end gap-2">
        <Button size="sm" onClick={onSave} disabled={busy || problem !== null}>
          {busy ? <Loader2 className="animate-spin" /> : <Save />} 保存
        </Button>
      </div>
    </div>
  );
}

function SectionEditor({
  section: rawSection,
  hint,
  defaultPhrase,
  onChange,
  onRemove,
  removable,
}: {
  section: SectionDraft;
  hint: string;
  defaultPhrase: string;
  options: DescriptionTemplateOptions | null;
  onChange: (next: SectionDraft) => void;
  onRemove: () => void;
  removable: boolean;
}) {
  // 归一化一次：编辑区全程只碰非 null 数组，避免十几处 `?? []`。
  const variables: VariableDraft[] = rawSection.variables ?? [];
  const imageSlots: ImageSlotDraft[] = rawSection.imageSlots ?? [];
  const section: SectionDraft = { ...rawSection, variables, imageSlots };
  return (
    <div className="border-control-border space-y-3 rounded-md border px-3 py-3">
      <div className="flex items-center gap-2">
        <span className="font-medium">{sectionTypeLabel(section.sectionType)}</span>
        <label className="text-muted-foreground ml-auto flex items-center gap-1 text-xs">
          <input
            type="checkbox"
            checked={section.showHeading}
            onChange={(event) => onChange({ ...section, showHeading: event.target.checked })}
          />
          显示小标题
        </label>
        {removable && (
          <Button variant="ghost" size="sm" onClick={onRemove}>
            <Trash2 /> 删除段落
          </Button>
        )}
      </div>

      <Field label="段落正文" hint={hint}>
        <textarea
          className={`${control} min-h-24 font-mono text-xs`}
          value={section.body}
          onChange={(event) => onChange({ ...section, body: event.target.value })}
        />
      </Field>

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium">变量绑定</span>
          <div className="flex gap-2">
            {defaultPhrase && (
              <Button
                variant="outline"
                size="sm"
                onClick={() =>
                  onChange({
                    ...section,
                    variables: [
                      ...variables,
                      { name: 'selling', source: defaultPhrase, slotNo: null },
                    ],
                  })
                }
              >
                加建议的短语变量
              </Button>
            )}
            <Button
              variant="outline"
              size="sm"
              onClick={() =>
                onChange({
                  ...section,
                  variables: [...variables, { name: '', source: 'SPU_BRAND', slotNo: null }],
                })
              }
            >
              <Plus /> 加变量
            </Button>
          </div>
        </div>
        {variables.length === 0 && (
          <p className="text-muted-foreground text-xs">
            固定句子可以不绑任何变量（例如「本店提供 7 天无理由退换」）。
          </p>
        )}
        {variables.map((variable, index) => (
          <div key={index} className="flex items-center gap-2">
            <input
              className={`${control} font-mono`}
              placeholder="变量名（如 size）"
              value={variable.name}
              onChange={(event) =>
                onChange({
                  ...section,
                  variables: variables.map((item, i) =>
                    i === index ? { ...item, name: event.target.value } : item,
                  ),
                })
              }
            />
            <select
              className={control}
              value={variable.source}
              onChange={(event) =>
                onChange({
                  ...section,
                  variables: variables.map((item, i) =>
                    i === index ? { ...item, source: event.target.value } : item,
                  ),
                })
              }
            >
              {VARIABLE_SOURCES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.group} · {item.label}
                </option>
              ))}
            </select>
            <Button
              variant="ghost"
              size="sm"
              onClick={() =>
                onChange({
                  ...section,
                  variables: variables.filter((_, i) => i !== index),
                })
              }
            >
              <Trash2 />
            </Button>
          </div>
        ))}
      </div>

      <div className="space-y-2">
        <div className="flex items-center justify-between">
          <span className="text-sm font-medium">图片插入位</span>
          <Button
            variant="outline"
            size="sm"
            onClick={() =>
              onChange({
                ...section,
                imageSlots: [
                  ...imageSlots,
                  {
                    bindSource: 'RENDER_INPUT',
                    caption: '',
                    assetId: null,
                    required: false,
                    slotNo: imageSlots.length,
                  },
                ],
              })
            }
          >
            <Plus /> 加插图位
          </Button>
        </div>
        {imageSlots.map((slot, index) => (
          <div key={index} className="flex flex-wrap items-center gap-2">
            <span className="text-muted-foreground w-14 text-xs">第 {index + 1} 张</span>
            <select
              className={`${control} w-48`}
              value={slot.bindSource}
              onChange={(event) =>
                onChange({
                  ...section,
                  imageSlots: imageSlots.map((item, i) =>
                    i === index ? { ...item, bindSource: event.target.value } : item,
                  ),
                })
              }
            >
              {IMAGE_BIND_SOURCES.map((item) => (
                <option key={item.value} value={item.value}>
                  {item.label}
                </option>
              ))}
            </select>
            <input
              className={`${control} flex-1`}
              placeholder="图片替代文本（落成 img 的 alt）"
              value={slot.caption ?? ''}
              onChange={(event) =>
                onChange({
                  ...section,
                  imageSlots: imageSlots.map((item, i) =>
                    i === index ? { ...item, caption: event.target.value } : item,
                  ),
                })
              }
            />
            <label className="text-muted-foreground flex items-center gap-1 text-xs">
              <input
                type="checkbox"
                checked={slot.required}
                onChange={(event) =>
                  onChange({
                    ...section,
                    imageSlots: imageSlots.map((item, i) =>
                      i === index ? { ...item, required: event.target.checked } : item,
                    ),
                  })
                }
              />
              必填
            </label>
            <Button
              variant="ghost"
              size="sm"
              onClick={() =>
                onChange({
                  ...section,
                  imageSlots: imageSlots
                    .filter((_, i) => i !== index)
                    .map((item, i) => ({ ...item, slotNo: i })),
                })
              }
            >
              <Trash2 />
            </Button>
          </div>
        ))}
        <p className="text-muted-foreground text-xs">
          必填位取不到图时会报「必填插图位没有图」并把这一段标成不可发布。
        </p>
      </div>
    </div>
  );
}
