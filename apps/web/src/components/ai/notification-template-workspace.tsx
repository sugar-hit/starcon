'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import { CircleAlert, FileText, Loader2, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  appendVersion,
  enabledVersionOf,
  archiveTemplate,
  createTemplate,
  disableVersion,
  enableVersion,
  fetchCapabilities,
  fetchContentAssets,
  fetchOverview,
  fetchTemplateDetail,
  fetchTemplates,
  formatMoment,
  normalizeLanguage,
  normalizeTemplateKey,
  placeholderNames,
  renderErrorLabel,
  renderErrorTone,
  renderPreview,
  resolveTemplate,
  sceneLabel,
  undeclaredVariables,
  unusedVariables,
  valueInputs,
  versionStateLabel,
  type TemplateListParams,
} from './notification-template-model';
import type {
  NotifyTemplateCapabilities,
  NotifyTemplateContentAssetView,
  NotifyTemplateDetail,
  NotifyTemplateRenderResult,
  NotifyTemplateResolved,
  NotifyTemplateRow,
  NotifyTemplateVariableDefinition,
} from '../../../../../packages/api-contracts/src/index';

/**
 * 18.5.1.1「模板管理」工作区（前端入口 `/ai/templates`）。
 *
 * 蓝图：「管理通知与消息模板：模板内容、变量、适用场景、语言版本、启用状态；修改后新产生的通知使用新版本，
 * 历史通知保留当时内容。输入：模板编辑；变量定义。输出：模板记录 + 渲染预览。
 * 关系：上游 = 3.4.2 文案资产；下游 = 1.3.1.1、15.1.1.2、18.5.2。
 * **模板必须版本化**，已发出的通知不能被新模板"篡改"。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **没有「覆盖保存」**：编辑只有一条路 —— **新增一个版本**。老版本永远留在版本历史里。
 * 2. **渲染预览失败要说清缺哪个变量**：预览返回 400 时后端带结构化清单（每条带变量名），
 *    页面**逐条显示**（缺变量 / 未声明变量 / 多余取值 / 语法错误），**不**显示成一段空白。
 * 3. **按时刻解析**能翻到「那一刻生效的那一版」：这是「历史通知保留当时内容」的落地。
 * 4. **版本历史显示生效区间**：哪一版在什么时候生效、什么时候被换掉，一眼能看清。
 * 5. **上游读不到 ≠ 没有**：3.4.2 文案资产读不到时如实显示「读不到」，不显示成「暂无」。
 * 6. **加载 / 空 / 错误三态齐全**：空态区分「筛选无结果」与「还没建过模板」。
 */

const PAGE_SIZE = 10;

const BADGE_TONE: Record<string, 'neutral' | 'success' | 'warning' | 'danger'> = {
  ok: 'success',
  warn: 'warning',
  danger: 'danger',
  muted: 'neutral',
};

interface TemplateForm {
  templateKey: string;
  scene: string;
  language: string;
  name: string;
  description: string;
  subject: string;
  body: string;
}

const EMPTY_TEMPLATE_FORM: TemplateForm = {
  templateKey: '',
  scene: 'ORDER_STATUS',
  language: 'zh-CN',
  name: '',
  description: '',
  subject: '',
  body: '',
};

interface VersionForm {
  subject: string;
  body: string;
  changeNote: string;
}

const EMPTY_VERSION_FORM: VersionForm = { subject: '', body: '', changeNote: '' };

export function NotificationTemplateWorkspace() {
  const [capabilities, setCapabilities] = useState<NotifyTemplateCapabilities | null>(null);
  const [overview, setOverview] = useState<{ templates: number; activeTemplates: number } | null>(
    null,
  );
  const [assets, setAssets] = useState<NotifyTemplateContentAssetView | null>(null);
  const [assetError, setAssetError] = useState('');

  const [page, setPage] = useState<{
    items: NotifyTemplateRow[];
    total: number;
    totalPages: number;
  }>({ items: [], total: 0, totalPages: 0 });
  const [detail, setDetail] = useState<NotifyTemplateDetail | null>(null);

  const [keyword, setKeyword] = useState('');
  const [scene, setScene] = useState('');
  const [language, setLanguage] = useState('');
  const [enabledFilter, setEnabledFilter] = useState('');
  const [status, setStatus] = useState('ACTIVE');
  const [pageIndex, setPageIndex] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [showCreate, setShowCreate] = useState(false);
  const [templateForm, setTemplateForm] = useState<TemplateForm>(EMPTY_TEMPLATE_FORM);
  const [versionForm, setVersionForm] = useState<VersionForm>(EMPTY_VERSION_FORM);
  const [variables, setVariables] = useState<NotifyTemplateVariableDefinition[]>([]);
  const [variableName, setVariableName] = useState('');
  const [variableType, setVariableType] = useState('TEXT');
  const [variableRequired, setVariableRequired] = useState(true);
  const [variableDefault, setVariableDefault] = useState('');

  const [values, setValues] = useState<Record<string, string>>({});
  const [preview, setPreview] = useState<NotifyTemplateRenderResult | null>(null);
  const [previewing, setPreviewing] = useState(false);

  const [resolveAt, setResolveAt] = useState('');
  const [resolved, setResolved] = useState<NotifyTemplateResolved | null>(null);
  const [resolveError, setResolveError] = useState('');

  const fail = (e: unknown) => {
    const message = e instanceof Error ? e.message : '请求失败，请稍后重试';
    setError(message);
  };

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const params: TemplateListParams = {
        keyword: keyword || undefined,
        scene: scene || undefined,
        language: language || undefined,
        enabled: enabledFilter === '' ? undefined : enabledFilter === 'true',
        status,
      };
      const [caps, ov, list] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchTemplates(pageIndex, PAGE_SIZE, params),
      ]);
      setCapabilities(caps);
      setOverview({ templates: ov.templates, activeTemplates: ov.activeTemplates });
      setPage({ items: list.items, total: list.total, totalPages: list.totalPages });
    } catch (e) {
      fail(e);
    } finally {
      setLoading(false);
    }
  }, [keyword, scene, language, enabledFilter, status, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // 上游文案资产（3.4.2）：读不到就如实显示「读不到」，不假装「没有」。
  useEffect(() => {
    fetchContentAssets()
      .then(setAssets)
      .catch((e) => setAssetError(e instanceof Error ? e.message : '上游文案资产读不到'));
  }, []);

  const openDetail = useCallback(async (templateId: string) => {
    setBusy(true);
    setError('');
    try {
      const result = await fetchTemplateDetail(templateId);
      setDetail(result);
      setVersionForm({
        subject: enabledVersionOf(result)?.subject ?? '',
        body: enabledVersionOf(result)?.body ?? '',
        changeNote: '',
      });
      setVariables(enabledVersionOf(result)?.variables ?? []);
      setValues({});
      setPreview(null);
      setResolved(null);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  }, []);

  const run = async (work: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      await work();
      setNotice(ok);
      await load();
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const onCreate = async () => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const created = await createTemplate({
        templateKey: normalizeTemplateKey(templateForm.templateKey),
        scene: templateForm.scene,
        language: normalizeLanguage(templateForm.language),
        name: templateForm.name,
        description: templateForm.description,
        subject: templateForm.subject,
        body: templateForm.body,
        variables,
        changeNote: '第 1 版',
      });
      setNotice(`模板 ${created.templateKey} 已创建（第 1 版已落下，尚未启用）。`);
      setShowCreate(false);
      setTemplateForm(EMPTY_TEMPLATE_FORM);
      setVariables([]);
      await load();
      await openDetail(created.id);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const onAppendVersion = async () => {
    if (!detail) return;
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const saved = await appendVersion(detail.template.id, {
        subject: versionForm.subject,
        body: versionForm.body,
        variables,
        changeNote: versionForm.changeNote,
      });
      setNotice(`已新增第 ${saved.version.versionNo} 版（老版本原样保留）。`);
      setVersionForm({ subject: '', body: '', changeNote: '' });
      await load();
      await openDetail(detail.template.id);
    } catch (e) {
      fail(e);
    } finally {
      setBusy(false);
    }
  };

  const onPreview = async () => {
    if (!detail) return;
    setPreviewing(true);
    setPreview(null);
    try {
      const target = enabledVersionOf(detail);
      const result = await renderPreview({
        templateId: detail.template.id,
        versionId: target?.id ?? '',
        subject: '',
        body: '',
        variables: [],
        values,
      });
      setPreview(result);
    } catch (e) {
      fail(e);
    } finally {
      setPreviewing(false);
    }
  };

  const onResolve = async () => {
    if (!detail) return;
    setResolveError('');
    setResolved(null);
    try {
      const result = await resolveTemplate(
        detail.template.templateKey,
        detail.template.scene,
        detail.template.language,
        resolveAt ? new Date(resolveAt).toISOString() : new Date().toISOString(),
      );
      setResolved(result);
    } catch (e) {
      setResolveError(e instanceof Error ? e.message : '按时刻解析失败');
    }
  };

  const addVariable = () => {
    const name = variableName.trim();
    if (!name) return;
    if (variables.some((v) => v.name === name)) return;
    setVariables([
      ...variables,
      {
        name,
        type: variableType,
        required: variableRequired,
        defaultValue: variableRequired ? '' : variableDefault,
        description: '',
      },
    ]);
    setVariableName('');
    setVariableDefault('');
  };

  const editorContent = `${versionForm.subject}\n${versionForm.body}`;
  const undeclared = undeclaredVariables(editorContent, variables);
  const unused = unusedVariables(editorContent, variables);
  const hasFilter = Boolean(keyword || scene || language || enabledFilter || status !== 'ACTIVE');
  // 当前生效的版本（可能没有 —— 模板可以全部停用）。
  const activeVersion = enabledVersionOf(detail);

  return (
    <div className="space-y-4">
      <PageHeader
        title="模板管理"
        description="通知与消息模板：内容、变量、适用场景、语言版本与启用状态。编辑即新增版本，老版本不可篡改 —— 已发出的通知始终按当时的内容解释。"
        actions={
          <div className="flex items-center gap-2">
            <Button onClick={() => setShowCreate((v) => !v)} disabled={busy}>
              <FileText className="h-4 w-4" />
              新建模板
            </Button>
            <Button onClick={() => void load()} disabled={loading}>
              {loading ? (
                <Loader2 className="h-4 w-4 animate-spin" />
              ) : (
                <RefreshCw className="h-4 w-4" />
              )}
              刷新
            </Button>
          </div>
        }
      />

      {error && (
        <div className="text-destructive flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{error}</span>
        </div>
      )}
      {notice && (
        <div className="text-success flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{notice}</span>
        </div>
      )}

      {/* 能力与边界：场景词表、语言写法、变量约定，以及还没交付的那几格。 */}
      {capabilities && (
        <SectionCard title="能力与边界" description="这一页真接通了什么、缺在谁身上。">
          <div className="grid gap-3 md:grid-cols-2">
            {capabilities.capabilities.map((capability) => (
              <div key={capability.name} className="border-border rounded-md border p-3">
                <div className="flex items-center gap-2">
                  <Badge tone={capability.available ? 'success' : 'warning'}>
                    {capability.available ? '已接通' : '未接通'}
                  </Badge>
                  <span className="text-copy-13 text-foreground">{capability.name}</span>
                </div>
                <p className="text-copy-12 text-muted-foreground mt-1.5">{capability.evidence}</p>
                {capability.note && (
                  <p className="text-copy-12 text-muted-foreground mt-1">{capability.note}</p>
                )}
                {capability.ownerModule && (
                  <p className="text-copy-12 text-warning mt-1">
                    归 {capability.ownerModule} —— 本页不代它实现。
                  </p>
                )}
              </div>
            ))}
          </div>
          <div className="text-copy-12 text-muted-foreground mt-3 space-y-1">
            <div>
              <span className="text-foreground">场景词表</span>：
              {capabilities.conventions.scenes.map((s) => `${s.code}（${s.label}）`).join(' · ')}
            </div>
            <div>
              <span className="text-foreground">语言写法</span>：
              {capabilities.conventions.languageRule}
            </div>
            <div>
              <span className="text-foreground">变量类型</span>：
              {capabilities.conventions.variableTypes.map((t) => t.code).join(' · ')}
            </div>
            <div>
              <span className="text-foreground">渲染报错口径</span>：
              {capabilities.conventions.renderErrorCodes.map((e) => e.code).join(' · ')}
            </div>
          </div>
          {capabilities.gaps.length > 0 && (
            <div className="text-copy-12 text-muted-foreground mt-3 space-y-1">
              {capabilities.gaps.map((gap) => (
                <div key={gap.moduleId}>
                  <Badge tone="neutral">{gap.relation}</Badge>{' '}
                  <span className="text-foreground">{gap.moduleId}</span> {gap.name} —— {gap.note}
                </div>
              ))}
            </div>
          )}
        </SectionCard>
      )}

      {overview && (
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <StatCard label="模板" value={overview.templates} />
          <StatCard label="已启用" value={overview.activeTemplates} />
          <StatCard
            label="场景数"
            value={capabilities ? capabilities.conventions.scenes.length : 0}
          />
          <StatCard label="筛选结果" value={page.total} />
        </div>
      )}

      {/* 新建模板 */}
      {showCreate && (
        <SectionCard title="新建模板" description="创建时同时落下第 1 版内容；新建不等于启用。">
          <div className="grid gap-3 md:grid-cols-2">
            <label className="space-y-1">
              <span className="text-label-13">模板 key（小写机器名）</span>
              <Input
                value={templateForm.templateKey}
                placeholder="order_paid"
                onChange={(e) => setTemplateForm({ ...templateForm, templateKey: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13">适用场景</span>
              <select
                className="border-border bg-background h-9 w-full rounded-md border px-3 text-sm"
                value={templateForm.scene}
                onChange={(e) => setTemplateForm({ ...templateForm, scene: e.target.value })}
              >
                {(capabilities?.conventions.scenes ?? []).map((s) => (
                  <option key={s.code} value={s.code}>
                    {s.code} · {s.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1">
              <span className="text-label-13">语言（IANA 写法）</span>
              <Input
                value={templateForm.language}
                placeholder="zh-CN"
                onChange={(e) => setTemplateForm({ ...templateForm, language: e.target.value })}
              />
            </label>
            <label className="space-y-1">
              <span className="text-label-13">模板名称</span>
              <Input
                value={templateForm.name}
                placeholder="订单已发货通知"
                onChange={(e) => setTemplateForm({ ...templateForm, name: e.target.value })}
              />
            </label>
            <label className="space-y-1 md:col-span-2">
              <span className="text-label-13">标题（可空）</span>
              <Input
                value={templateForm.subject}
                placeholder="订单 {{order_no}} 已发货"
                onChange={(e) => setTemplateForm({ ...templateForm, subject: e.target.value })}
              />
            </label>
            <label className="space-y-1 md:col-span-2">
              <span className="text-label-13">正文</span>
              <textarea
                className="border-border bg-background min-h-24 w-full rounded-md border px-3 py-2 text-sm"
                value={templateForm.body}
                placeholder="买家 {{buyer_name}}，您的订单 {{order_no}} 已发货"
                onChange={(e) => setTemplateForm({ ...templateForm, body: e.target.value })}
              />
            </label>
          </div>
          <VariableEditor
            variables={variables}
            variableName={variableName}
            variableType={variableType}
            variableRequired={variableRequired}
            variableDefault={variableDefault}
            onName={setVariableName}
            onType={setVariableType}
            onRequired={setVariableRequired}
            onDefault={setVariableDefault}
            onAdd={addVariable}
            onRemove={(name) => setVariables(variables.filter((v) => v.name !== name))}
            content={`${templateForm.subject}\n${templateForm.body}`}
          />
          <div className="mt-4 flex items-center gap-2">
            <Button onClick={() => void onCreate()} disabled={busy}>
              {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
              创建（第 1 版）
            </Button>
            <Button variant="secondary" onClick={() => setShowCreate(false)} disabled={busy}>
              取消
            </Button>
          </div>
        </SectionCard>
      )}

      {/* 模板列表 */}
      <SectionCard
        title="模板"
        description="按场景 / 语言 / 启用状态筛选；点一行看它的版本历史。"
        extra={
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="h-9 w-40"
              placeholder="搜索 key 或名称"
              value={keyword}
              onChange={(e) => {
                setKeyword(e.target.value);
                setPageIndex(0);
              }}
            />
            <select
              className="border-border bg-background h-9 rounded-md border px-3 text-sm"
              value={scene}
              onChange={(e) => {
                setScene(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部场景</option>
              {(capabilities?.conventions.scenes ?? []).map((s) => (
                <option key={s.code} value={s.code}>
                  {s.label}
                </option>
              ))}
            </select>
            <Input
              className="h-9 w-28"
              placeholder="语言"
              value={language}
              onChange={(e) => {
                setLanguage(e.target.value);
                setPageIndex(0);
              }}
            />
            <select
              className="border-border bg-background h-9 rounded-md border px-3 text-sm"
              value={enabledFilter}
              onChange={(e) => {
                setEnabledFilter(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="">全部状态</option>
              <option value="true">已启用</option>
              <option value="false">未启用</option>
            </select>
            <select
              className="border-border bg-background h-9 rounded-md border px-3 text-sm"
              value={status}
              onChange={(e) => {
                setStatus(e.target.value);
                setPageIndex(0);
              }}
            >
              <option value="ACTIVE">未归档</option>
              <option value="ARCHIVED">已归档</option>
              <option value="ALL">全部</option>
            </select>
          </div>
        }
      >
        {loading ? (
          <div className="text-muted-foreground flex items-center gap-2 py-8 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载模板…
          </div>
        ) : page.items.length === 0 ? (
          <EmptyState
            icon={<FileText className="h-6 w-6" />}
            title={hasFilter ? '没有符合条件的模板' : '还没有建过模板'}
            description={
              hasFilter
                ? '换个筛选条件试试，或清空筛选看全部模板。'
                : '新建一个模板：内容、变量、适用场景与语言。'
            }
          />
        ) : (
          <div className="space-y-2">
            {page.items.map((template) => (
              <button
                key={template.id}
                type="button"
                onClick={() => void openDetail(template.id)}
                className="border-border hover:bg-muted/40 flex w-full items-start justify-between gap-3 rounded-md border p-3 text-left"
              >
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">
                      {template.templateKey}
                    </span>
                    <Badge tone="info">{sceneLabel(capabilities, template.scene)}</Badge>
                    <Badge tone="neutral">{template.language}</Badge>
                    <Badge tone={template.enabled ? 'success' : 'neutral'}>
                      {template.enabled ? `生效中 v${template.enabledVersionNo ?? '-'}` : '未启用'}
                    </Badge>
                    {template.archived && <Badge tone="warning">已归档</Badge>}
                  </div>
                  <div className="text-copy-12 text-muted-foreground">
                    {template.name} · 共 {template.versionCount} 版（最新 v
                    {template.latestVersionNo}）
                  </div>
                </div>
              </button>
            ))}
            <div className="text-copy-12 text-muted-foreground flex items-center justify-between pt-1">
              <span>
                共 {page.total} 个模板 · 第 {pageIndex + 1} / {Math.max(page.totalPages, 1)} 页
              </span>
              <div className="flex items-center gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={pageIndex <= 0}
                  onClick={() => setPageIndex(Math.max(pageIndex - 1, 0))}
                >
                  上一页
                </Button>
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={pageIndex + 1 >= page.totalPages}
                  onClick={() => setPageIndex(pageIndex + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {/* 详情：版本历史 + 编辑 + 预览 */}
      {detail && (
        <>
          <SectionCard
            title={`版本历史 · ${detail.template.templateKey}`}
            description="每一版都原样留着：编辑只新增版本，不覆盖老版本。生效区间说明哪一版在什么时候管用。"
            extra={
              <div className="flex items-center gap-2">
                <Button
                  variant="secondary"
                  size="sm"
                  disabled={busy}
                  onClick={() =>
                    void run(
                      () => archiveTemplate(detail.template.id),
                      '模板已归档（停用 + 归档）：版本与启用历史原样保留。',
                    ).then(() => setDetail(null))
                  }
                >
                  归档（不删历史）
                </Button>
              </div>
            }
          >
            {detail.versions.length === 0 ? (
              <EmptyState title="还没有版本" description="新增一版内容就能开始用这个模板。" />
            ) : (
              <div className="space-y-2">
                {detail.versions.map((version) => (
                  <div
                    key={version.id}
                    className="border-border flex flex-wrap items-start justify-between gap-3 rounded-md border p-3"
                  >
                    <div className="min-w-0 space-y-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="text-copy-13 text-foreground font-medium">
                          v{version.versionNo}
                        </span>
                        <Badge
                          tone={
                            BADGE_TONE[
                              versionStateLabel(version) === '生效中'
                                ? 'ok'
                                : versionStateLabel(version) === '已失效'
                                  ? 'muted'
                                  : 'warn'
                            ]
                          }
                        >
                          {versionStateLabel(version)}
                        </Badge>
                        <span className="text-copy-12 text-muted-foreground">
                          {formatMoment(version.createdAt)} 创建
                          {version.activatedAt
                            ? ` · ${formatMoment(version.activatedAt)} 起生效`
                            : ''}
                          {version.deactivatedAt
                            ? ` · ${formatMoment(version.deactivatedAt)} 失效`
                            : ''}
                        </span>
                      </div>
                      <div className="text-copy-12 text-muted-foreground line-clamp-2 whitespace-pre-wrap">
                        {version.subject ? `${version.subject}\n` : ''}
                        {version.body}
                      </div>
                      {version.variables.length > 0 && (
                        <div className="text-copy-12 text-muted-foreground">
                          变量：
                          {version.variables.map((v) => `{{${v.name}}}`).join(' ')}
                        </div>
                      )}
                    </div>
                    <div className="flex shrink-0 items-center gap-2">
                      {version.active ? (
                        <Button
                          variant="secondary"
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => disableVersion(detail.template.id, version.id),
                              '已停用（版本行原样保留）。',
                            ).then(() => openDetail(detail.template.id))
                          }
                        >
                          停用
                        </Button>
                      ) : (
                        <Button
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            void run(
                              () => enableVersion(detail.template.id, version.id),
                              '已启用该版本。',
                            ).then(() => openDetail(detail.template.id))
                          }
                        >
                          启用
                        </Button>
                      )}
                    </div>
                  </div>
                ))}
              </div>
            )}
          </SectionCard>

          <SectionCard
            title="新增版本（= 编辑）"
            description="老版本不会被改动。模板当前有生效版本时，新版本接管。"
          >
            <div className="grid gap-3 md:grid-cols-2">
              <label className="space-y-1">
                <span className="text-label-13">标题（可空）</span>
                <Input
                  value={versionForm.subject}
                  placeholder="订单 {{order_no}} 已发货"
                  onChange={(e) => setVersionForm({ ...versionForm, subject: e.target.value })}
                />
              </label>
              <label className="space-y-1">
                <span className="text-label-13">改动说明</span>
                <Input
                  value={versionForm.changeNote}
                  placeholder="改了措辞"
                  onChange={(e) => setVersionForm({ ...versionForm, changeNote: e.target.value })}
                />
              </label>
              <label className="space-y-1 md:col-span-2">
                <span className="text-label-13">正文</span>
                <textarea
                  className="border-border bg-background min-h-24 w-full rounded-md border px-3 py-2 text-sm"
                  value={versionForm.body}
                  placeholder="买家 {{buyer_name}}，您的订单 {{order_no}} 已发货"
                  onChange={(e) => setVersionForm({ ...versionForm, body: e.target.value })}
                />
              </label>
            </div>
            <VariableEditor
              variables={variables}
              variableName={variableName}
              variableType={variableType}
              variableRequired={variableRequired}
              variableDefault={variableDefault}
              onName={setVariableName}
              onType={setVariableType}
              onRequired={setVariableRequired}
              onDefault={setVariableDefault}
              onAdd={addVariable}
              onRemove={(name) => setVariables(variables.filter((v) => v.name !== name))}
              content={editorContent}
            />
            <div className="mt-4">
              <Button onClick={() => void onAppendVersion()} disabled={busy}>
                {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                新增版本
              </Button>
            </div>
          </SectionCard>

          <SectionCard
            title="渲染预览"
            description="预览不落库。缺变量 / 未声明变量 / 多余取值 / 语法错误都会点名报出来，不会渲染成空白。"
          >
            {activeVersion ? (
              <div className="space-y-3">
                <div className="text-copy-12 text-muted-foreground">
                  预览的是当前生效的 v{activeVersion.versionNo}。
                </div>
                {activeVersion.variables.length === 0 ? (
                  <div className="text-copy-13 text-muted-foreground">这一版没有变量。</div>
                ) : (
                  <div className="grid gap-2 md:grid-cols-2">
                    {valueInputs(activeVersion.variables, values).map((input) => (
                      <label key={input.name} className="space-y-1">
                        <span className="text-label-13">
                          {`{{${input.name}}}`}
                          <span className="text-muted-foreground">（{input.type}）</span>
                        </span>
                        <Input
                          value={input.value}
                          onChange={(e) => setValues({ ...values, [input.name]: e.target.value })}
                        />
                      </label>
                    ))}
                  </div>
                )}
                <Button onClick={() => void onPreview()} disabled={previewing}>
                  {previewing ? <Loader2 className="h-4 w-4 animate-spin" /> : null}
                  渲染预览
                </Button>
                {preview && (
                  <div className="space-y-2">
                    {preview.ok ? (
                      <div className="border-border bg-muted/40 space-y-2 rounded-md border p-3">
                        <Badge tone="success">渲染成功</Badge>
                        <pre className="text-copy-13 text-foreground whitespace-pre-wrap">
                          {preview.renderedSubject ? `${preview.renderedSubject}\n` : ''}
                          {preview.renderedBody}
                        </pre>
                      </div>
                    ) : (
                      <div className="border-destructive space-y-2 rounded-md border p-3">
                        <div className="text-destructive flex items-center gap-2 text-sm font-medium">
                          <TriangleAlert className="h-4 w-4" />
                          渲染失败：{preview.message}
                        </div>
                        <ul className="space-y-1">
                          {preview.errors.map((item) => (
                            <li
                              key={`${item.code}-${item.variables.join(',')}`}
                              className="text-sm"
                            >
                              <Badge tone={BADGE_TONE[renderErrorTone(item.code)]}>
                                {renderErrorLabel(item.code)}
                              </Badge>{' '}
                              <span className="text-copy-13 text-foreground">{item.message}</span>
                            </li>
                          ))}
                        </ul>
                      </div>
                    )}
                  </div>
                )}
              </div>
            ) : (
              <EmptyState
                title="当前没有生效的版本"
                description="先启用一个版本，才能预览它的渲染结果。"
              />
            )}
          </SectionCard>

          <SectionCard
            title="按时刻解析"
            description="取「那一刻生效的那一版」—— 已经发出的通知按当时的内容解释，后来改模板篡改不了它。"
          >
            <div className="flex flex-wrap items-end gap-2">
              <label className="space-y-1">
                <span className="text-label-13">时刻（留空 = 现在）</span>
                <Input
                  type="datetime-local"
                  value={resolveAt}
                  onChange={(e) => setResolveAt(e.target.value)}
                />
              </label>
              <Button onClick={() => void onResolve()} disabled={busy}>
                解析
              </Button>
            </div>
            {resolveError && (
              <div className="text-destructive mt-3 flex items-start gap-2 text-sm">
                <CircleAlert className="mt-0.5 h-4 w-4 shrink-0" />
                <span>{resolveError}</span>
              </div>
            )}
            {resolved && (
              <div className="border-border mt-3 space-y-1 rounded-md border p-3">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone="success">v{resolved.versionNo}</Badge>
                  <span className="text-copy-12 text-muted-foreground">
                    {formatMoment(resolved.activatedAt)} 起生效
                    {resolved.deactivatedAt
                      ? ` · ${formatMoment(resolved.deactivatedAt)} 失效`
                      : ''}
                  </span>
                </div>
                <pre className="text-copy-13 text-foreground whitespace-pre-wrap">
                  {resolved.subject ? `${resolved.subject}\n` : ''}
                  {resolved.body}
                </pre>
              </div>
            )}
          </SectionCard>
        </>
      )}

      {/* 上游文案资产 */}
      <SectionCard
        title="上游文案资产（3.4.2）"
        description="标题库与描述与卖点库 —— 只读，不在本页复制一份。"
      >
        {assetError ? (
          <div className="text-destructive flex items-start gap-2 text-sm">
            <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
            <span>{assetError}（是「读不到」，不是「没有数据」）</span>
          </div>
        ) : !assets ? (
          <div className="text-muted-foreground flex items-center gap-2 py-6 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在读取上游…
          </div>
        ) : (
          <div className="grid gap-3 md:grid-cols-2">
            <div>
              <div className="text-copy-13 text-foreground mb-1">
                标题库（{assets.titles.length}）
              </div>
              {assets.titles.length === 0 ? (
                <div className="text-copy-12 text-muted-foreground">上游没有标题模板。</div>
              ) : (
                <ul className="space-y-1">
                  {assets.titles.map((asset) => (
                    <li key={asset.id} className="text-copy-12 text-muted-foreground">
                      {asset.code} · {asset.name}
                    </li>
                  ))}
                </ul>
              )}
            </div>
            <div>
              <div className="text-copy-13 text-foreground mb-1">
                描述与卖点库（{assets.descriptions.length}）
              </div>
              {assets.descriptions.length === 0 ? (
                <div className="text-copy-12 text-muted-foreground">上游没有描述模板。</div>
              ) : (
                <ul className="space-y-1">
                  {assets.descriptions.map((asset) => (
                    <li key={asset.id} className="text-copy-12 text-muted-foreground">
                      {asset.code} · {asset.name}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

/** 变量定义编辑器（**声明了才能填**）。 */
function VariableEditor({
  variables,
  variableName,
  variableType,
  variableRequired,
  variableDefault,
  onName,
  onType,
  onRequired,
  onDefault,
  onAdd,
  onRemove,
  content,
}: {
  variables: NotifyTemplateVariableDefinition[];
  variableName: string;
  variableType: string;
  variableRequired: boolean;
  variableDefault: string;
  onName: (value: string) => void;
  onType: (value: string) => void;
  onRequired: (value: boolean) => void;
  onDefault: (value: string) => void;
  onAdd: () => void;
  onRemove: (name: string) => void;
  content: string;
}) {
  const undeclared = useMemo(() => undeclaredVariables(content, variables), [content, variables]);
  const unused = useMemo(() => unusedVariables(content, variables), [content, variables]);
  const used = useMemo(() => placeholderNames(content), [content]);

  return (
    <div className="mt-4 space-y-2">
      <div className="text-label-13">变量定义</div>
      {variables.length === 0 ? (
        <div className="text-copy-12 text-muted-foreground">
          还没有变量。正文里写了 <code>{'{{变量名}}'}</code> 的话，请在这里声明它 ——
          没声明的变量服务端填不上。
        </div>
      ) : (
        <ul className="space-y-1">
          {variables.map((variable) => (
            <li
              key={variable.name}
              className="border-border flex items-center gap-2 rounded-md border px-3 py-2"
            >
              <span className="text-copy-13 text-foreground">{`{{${variable.name}}}`}</span>
              <Badge tone="neutral">{variable.type}</Badge>
              <Badge tone={variable.required ? 'warning' : 'info'}>
                {variable.required ? '必填' : `默认「${variable.defaultValue}」`}
              </Badge>
              {!used.includes(variable.name) && <Badge tone="neutral">正文里没用</Badge>}
              <button
                type="button"
                className="text-muted-foreground hover:text-destructive text-copy-12 ml-auto"
                onClick={() => onRemove(variable.name)}
              >
                移除
              </button>
            </li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap items-center gap-2">
        <Input
          className="h-9 w-44"
          placeholder="变量名（buyer_name）"
          value={variableName}
          onChange={(e) => onName(e.target.value)}
        />
        <select
          className="border-border bg-background h-9 rounded-md border px-3 text-sm"
          value={variableType}
          onChange={(e) => onType(e.target.value)}
        >
          <option value="TEXT">TEXT</option>
          <option value="NUMBER">NUMBER</option>
          <option value="DATE">DATE</option>
          <option value="CURRENCY">CURRENCY</option>
        </select>
        <label className="text-copy-13 flex items-center gap-1.5">
          <input
            type="checkbox"
            checked={variableRequired}
            onChange={(e) => onRequired(e.target.checked)}
          />
          必填
        </label>
        {!variableRequired && (
          <Input
            className="h-9 w-40"
            placeholder="默认值"
            value={variableDefault}
            onChange={(e) => onDefault(e.target.value)}
          />
        )}
        <Button variant="secondary" size="sm" onClick={onAdd} disabled={!variableName.trim()}>
          添加变量
        </Button>
      </div>
      {undeclared.length > 0 && (
        <div className="text-destructive text-copy-12 flex items-start gap-2">
          <TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>正文里用了还没声明的变量：{undeclared.map((n) => `{{${n}}}`).join(' ')}</span>
        </div>
      )}
      {unused.length > 0 && (
        <div className="text-muted-foreground text-copy-12">
          声明了但正文里没用的变量：{unused.map((n) => `{{${n}}}`).join(' ')}（允许，只是提醒一下）
        </div>
      )}
    </div>
  );
}
