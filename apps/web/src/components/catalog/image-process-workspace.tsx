'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ArrowLeft,
  Ban,
  Image as ImageIcon,
  Loader2,
  Plus,
  RefreshCw,
  Save,
  Trash2,
  Wand2,
} from 'lucide-react';
import type {
  MediaProcessJob,
  MediaProcessJobDetail,
  MediaProcessJobRow,
  MediaProcessOverview,
  MediaProcessRecipeInput,
  MediaProcessRecipeRow,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  CROP_MODES,
  JOB_STATUSES,
  TARGET_FORMATS,
  WATERMARK_POSITIONS,
  assetsSizeProblem,
  cancelJob,
  defaultRatios,
  deleteRecipe,
  emptyRecipeInput,
  errorText,
  fetchJobDetail,
  fetchJobs,
  fetchProcessingOverview,
  fetchRecipes,
  itemErrorText,
  itemStatusLabel,
  jobEmptyHint,
  jobStatusLabel,
  jobStatusTone,
  outputDimensionText,
  pollable,
  progressPercent,
  progressText,
  recipeEmptyHint,
  recipeProblem,
  recipeSummary,
  saveRecipe,
  setRecipeEnabled,
  submitJob,
  submittedHint,
  versionRelationText,
} from './image-process-model';
import { fetchMedia, type MediaListQuery } from './media-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;
/** 轮询间隔：处理中的任务每隔这么久刷一次详情。 */
const POLL_MS = 2000;

/**
 * 3.4.1.2 图片处理工作区。
 *
 * <p>页面按「配一套规格 → 勾素材提交 → 看进度与失败原因」排：
 *
 * <ul>
 *   <li><b>处理配方</b> —— 目标平台规格。六项能力（去背景 / 白底 / 水印 / 尺寸裁剪 /
 *       格式转换 / 多比例）都在同一个表单里，配方可复用。
 *   <li><b>提交处理</b> —— 勾选素材并提交。提交是**异步受理**：后端只写元数据就返回，
 *       像素加工在线程池里跑，所以这里立刻跳到任务进度而不是等待。
 *   <li><b>任务进度</b> —— 轮询任务详情。进度是库里可查的事实，不是前端自己数的。
 * </ul>
 *
 * <p>界面上反复强调「原图始终保留」：处理会把素材的当前版本推进到产物，
 * 而作为来源的原始版本仍在版本历史里。不说清楚，运营看到版本号变了会以为原图丢了，
 * 于是再传一遍——素材库里就多出一份重复内容（3.4.1.1 的同内容复用也拦不住改名后的重复）。
 */
export function ImageProcessWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.assets.update');

  const [overview, setOverview] = useState<MediaProcessOverview | null>(null);
  const [recipes, setRecipes] = useState<MediaProcessRecipeRow[]>([]);
  const [jobs, setJobs] = useState<MediaProcessJobRow[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');

  const [detail, setDetail] = useState<MediaProcessJobDetail | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);

  // 配方编辑
  const [editing, setEditing] = useState<MediaProcessRecipeInput | null>(null);
  // 提交面板：选中的配方与素材
  const [picking, setPicking] = useState<{ recipeId: string } | null>(null);
  const [assets, setAssets] = useState<
    { id: string; name: string; widthPx: number | null; heightPx: number | null }[]
  >([]);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [assetKeyword, setAssetKeyword] = useState('');

  const pollTimer = useRef<ReturnType<typeof setInterval> | null>(null);

  const stopPolling = useCallback(() => {
    if (pollTimer.current) {
      clearInterval(pollTimer.current);
      pollTimer.current = null;
    }
  }, []);

  const load = useCallback(async () => {
    try {
      const [o, r, j] = await Promise.all([
        fetchProcessingOverview(),
        fetchRecipes(),
        fetchJobs({ keyword, status, page, size: PAGE_SIZE }),
      ]);
      setOverview(o);
      setRecipes(r);
      setJobs(j.items);
      setTotal(j.total);
      setError(null);
    } catch (e) {
      setError(errorText(e, '图片处理数据加载失败'));
    } finally {
      setLoading(false);
    }
  }, [keyword, status, page]);

  useEffect(() => {
    void load();
  }, [load]);

  /**
   * 轮询当前打开的任务。
   *
   * <p>只在任务还在 PENDING / RUNNING 时轮：终态继续轮就是永不停止的请求循环。
   * 「全部成功」和「部分失败」都会停——后者的失败原因已经在详情里摆着了。
   */
  const refreshDetail = useCallback(
    async (jobId: string) => {
      try {
        const next = await fetchJobDetail(jobId);
        setDetail(next);
        // 侧栏计数与列表一起跟上，否则顶部还显示上一次的数字。
        void load();
        if (!pollable(next.job.status)) stopPolling();
      } catch (e) {
        stopPolling();
        setActionError(errorText(e, '任务进度刷新失败'));
      }
    },
    [load, stopPolling],
  );

  useEffect(() => {
    stopPolling();
    if (detail && pollable(detail.job.status)) {
      pollTimer.current = setInterval(() => {
        void refreshDetail(detail.job.id);
      }, POLL_MS);
    }
    return stopPolling;
  }, [detail, refreshDetail, stopPolling]);

  async function openJob(jobId: string) {
    setActionError(null);
    await refreshDetail(jobId);
  }

  async function submit(recipeId: string, assetIds: string[]) {
    setBusy(true);
    try {
      const job: MediaProcessJob = await submitJob(recipeId, assetIds);
      setNotice(submittedHint(job));
      setPicking(null);
      setSelected(new Set());
      await load();
      await openJob(job.id);
    } catch (e) {
      setActionError(errorText(e, '处理提交失败'));
    } finally {
      setBusy(false);
    }
  }

  async function onCancel(jobId: string) {
    setBusy(true);
    try {
      await cancelJob(jobId);
      setNotice('已请求取消。正在加工的那一张会做完，剩下的素材不会再处理。');
      await refreshDetail(jobId);
    } catch (e) {
      setActionError(errorText(e, '取消失败'));
    } finally {
      setBusy(false);
    }
  }

  async function saveRecipeForm() {
    if (!editing) return;
    setBusy(true);
    try {
      await saveRecipe(editing.version > 0 ? await recipeIdOf(editing) : null, editing);
      setEditing(null);
      setNotice('配方已保存。');
      await load();
    } catch (e) {
      setActionError(errorText(e, '配方保存失败'));
    } finally {
      setBusy(false);
    }
  }

  /** 编辑表单里只有 code（后端返回 id）；用 code 反查一次 id 比在表单里存一份 id 干净。 */
  async function recipeIdOf(input: MediaProcessRecipeInput): Promise<string | null> {
    const found = recipes.find((r) => r.code === input.code.toUpperCase());
    return found?.id ?? null;
  }

  async function toggleRecipeEnabled(recipe: MediaProcessRecipeRow) {
    setBusy(true);
    try {
      await setRecipeEnabled(recipe.id, !recipe.enabled);
      await load();
    } catch (e) {
      setActionError(errorText(e, '配方启停失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeRecipe(recipe: MediaProcessRecipeRow) {
    setBusy(true);
    try {
      await deleteRecipe(recipe.id, recipe.version);
      setNotice(`配方「${recipe.code}」已删除。`);
      await load();
    } catch (e) {
      setActionError(errorText(e, '配方删除失败'));
    } finally {
      setBusy(false);
    }
  }

  async function loadAssets() {
    // 逐字段构造而不是写 `keyword: undefined`：开了 exactOptionalPropertyTypes，
    // 显式 undefined 不等于「不给这个键」。空关键词就干脆不带这个键。
    const query: MediaListQuery = {
      mediaType: 'IMAGE',
      referenced: null,
      enabled: true,
      page: 0,
      size: 50,
    };
    const trimmed = assetKeyword.trim();
    try {
      const pageData = await fetchMedia(trimmed ? { ...query, keyword: trimmed } : query);
      setAssets(
        pageData.items.map((item) => ({
          id: item.id,
          name: item.name,
          widthPx: item.widthPx,
          heightPx: item.heightPx,
        })),
      );
    } catch (e) {
      setActionError(errorText(e, '素材列表加载失败'));
    }
  }

  const selectedIds = useMemo(() => Array.from(selected), [selected]);
  const activeRecipes = useMemo(() => recipes.filter((r) => r.enabled), [recipes]);
  const openCount = overview?.openJobCount ?? 0;

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <Link
          href="/products/media"
          className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-sm"
        >
          <ArrowLeft className="h-4 w-4" />
          返回媒体库
        </Link>
        <h1 className="text-h2 font-semibold tracking-tight">图片处理</h1>
        <p className="text-copy-13 text-muted-foreground">
          刊登前的图片加工：白底、去背景、水印添加、尺寸裁剪到平台要求、格式转换，以及
          多比例版本（主图 1:1 + 缩略图）。处理是异步任务，长批量不阻塞页面；
          <strong className="text-foreground">原图始终保留</strong>
          —— 产物写成新文件与新版本，原始版本仍在素材的版本历史里。
        </p>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="处理任务"
          value={overview?.jobCount ?? 0}
          icon={<Wand2 className="h-4 w-4" />}
        />
        {/* 「进行中」用 neutral：不是坏事也不是好事，只是在跑。
            StatCard 的 polarity 只有 positive/negative/neutral，没有 warning——
            自造一个名字会编译不过，而改 UI 包是另一个模块的写入所有权。 */}
        <StatCard label="进行中" value={openCount} polarity="neutral" />
        {/* 「部分失败」是 negative：有人得回头处理这批里的失败素材。 */}
        <StatCard
          label="部分失败"
          value={overview?.partialJobCount ?? 0}
          polarity={overview && overview.partialJobCount > 0 ? 'negative' : 'positive'}
        />
        <StatCard
          label="产物文件"
          value={overview?.outputCount ?? 0}
          delta={overview?.outputByteSize ?? 0}
          comparisonLabel="字节"
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

      {writable && (
        <SectionCard
          title="处理配方"
          description="目标平台规格，可复用。去背景 / 白底 / 水印 / 裁剪 / 格式 / 多比例都在这里配好，提交时只选素材。"
          extra={
            <div className="flex gap-2">
              <Button
                size="sm"
                variant="outline"
                onClick={() => setEditing({ ...emptyRecipeInput(), ratios: defaultRatios() })}
              >
                <Plus className="h-4 w-4" />
                新建配方
              </Button>
              <Button
                size="sm"
                disabled={activeRecipes.length === 0}
                onClick={() => setPicking({ recipeId: activeRecipes[0]?.id ?? '' })}
              >
                <Wand2 className="h-4 w-4" />
                提交处理
              </Button>
            </div>
          }
        >
          {loading ? (
            <p className="text-muted-foreground flex items-center gap-2 text-sm">
              <Loader2 className="h-4 w-4 animate-spin" />
              正在加载配方…
            </p>
          ) : recipes.length === 0 ? (
            <EmptyState title="还没有处理配方" description={recipeEmptyHint()} />
          ) : (
            <ul className="divide-border divide-y">
              {recipes.map((recipe) => (
                <li
                  key={recipe.id}
                  className="flex flex-wrap items-center justify-between gap-3 py-3"
                >
                  <div className="min-w-0 space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{recipe.name}</span>
                      <Badge tone={recipe.enabled ? 'success' : 'neutral'}>
                        {recipe.enabled ? '启用' : '停用'}
                      </Badge>
                    </div>
                    <p className="text-muted-foreground text-xs">{recipeSummary(recipe)}</p>
                  </div>
                  <div className="flex gap-2">
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => setPicking({ recipeId: recipe.id })}
                      disabled={!recipe.enabled}
                    >
                      用它处理
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void toggleRecipeEnabled(recipe)}
                      disabled={busy}
                    >
                      {recipe.enabled ? '停用' : '启用'}
                    </Button>
                    <Button
                      size="sm"
                      variant="outline"
                      onClick={() => void removeRecipe(recipe)}
                      disabled={busy}
                    >
                      <Trash2 className="h-4 w-4" />
                      删除
                    </Button>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      )}

      <SectionCard
        title="处理任务"
        description="处理是异步任务：提交后立刻返回，进度从这里查。产出会成为素材的新版本，原图版本保留。"
        extra={
          <Button size="sm" variant="outline" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        }
      >
        <div className="mb-3 flex flex-wrap gap-2">
          <input
            className={control}
            placeholder="搜配方名或素材名"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
          />
          <select
            className={control}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部状态</option>
            {JOB_STATUSES.map((s) => (
              <option key={s.value} value={s.value}>
                {s.label}
              </option>
            ))}
          </select>
        </div>

        {loading ? (
          <p className="text-muted-foreground flex items-center gap-2 text-sm">
            <Loader2 className="h-4 w-4 animate-spin" />
            正在加载任务…
          </p>
        ) : jobs.length === 0 ? (
          <EmptyState title="没有处理任务" description={jobEmptyHint(!!(keyword || status))} />
        ) : (
          <>
            <ul className="divide-border divide-y">
              {jobs.map((job) => (
                <li key={job.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
                  <div className="min-w-0 space-y-1">
                    <div className="flex items-center gap-2">
                      <span className="font-medium">{job.recipeName}</span>
                      <Badge tone={jobStatusTone(job.status)}>{jobStatusLabel(job.status)}</Badge>
                      {pollable(job.status) && (
                        <Loader2 className="text-muted-foreground h-3 w-3 animate-spin" />
                      )}
                    </div>
                    <p className="text-muted-foreground text-xs">
                      {progressText(job)} · 平台 {job.targetPlatform || '—'} · 输出{' '}
                      {job.targetFormat.toUpperCase()}
                    </p>
                  </div>
                  <Button size="sm" variant="outline" onClick={() => void openJob(job.id)}>
                    查看进度
                  </Button>
                </li>
              ))}
            </ul>
            {total > PAGE_SIZE && (
              <div className="mt-3 flex items-center gap-2 text-sm">
                <Button
                  size="sm"
                  variant="outline"
                  disabled={page === 0}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                >
                  上一页
                </Button>
                <span className="text-muted-foreground">
                  第 {page + 1} / {Math.ceil(total / PAGE_SIZE)} 页 · 共 {total} 个任务
                </span>
                <Button
                  size="sm"
                  variant="outline"
                  disabled={(page + 1) * PAGE_SIZE >= total}
                  onClick={() => setPage((p) => p + 1)}
                >
                  下一页
                </Button>
              </div>
            )}
          </>
        )}
      </SectionCard>

      {detail && (
        <SectionCard
          title={`任务详情 · ${detail.job.recipeName}`}
          description={`${progressText(detail.job)}（${progressPercent(detail.job)}%）`}
          extra={
            <div className="flex items-center gap-2">
              {pollable(detail.job.status) && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => void onCancel(detail.job.id)}
                  disabled={busy}
                >
                  <Ban className="h-4 w-4" />
                  取消
                </Button>
              )}
              <Button size="sm" variant="outline" onClick={() => void openJob(detail.job.id)}>
                <RefreshCw className="h-4 w-4" />
                刷新
              </Button>
            </div>
          }
        >
          <div className="bg-muted mb-3 h-2 w-full overflow-hidden rounded">
            <div
              className="bg-primary h-full transition-[width]"
              style={{ width: `${progressPercent(detail.job)}%` }}
            />
          </div>
          <p className="text-muted-foreground mb-3 text-xs">
            配方比例档：
            {(detail.ratios ?? [])
              .map((r) => `${r.label} ${r.widthPx}×${r.heightPx}`)
              .join(' + ') || '（无比例档，按配方自身尺寸输出一版）'}
          </p>
          {detail.items.length === 0 ? (
            <EmptyState
              title="这个任务还没有条目"
              description="提交处理后，每个素材会在这里占一行。"
            />
          ) : (
            <ul className="divide-border divide-y">
              {detail.items.map((item) => (
                <li key={item.id} className="space-y-1 py-3">
                  <div className="flex flex-wrap items-center justify-between gap-2">
                    <span className="flex items-center gap-2 font-medium">
                      <ImageIcon className="h-4 w-4" />
                      {item.assetName}
                    </span>
                    <Badge
                      tone={
                        item.status === 'SUCCEEDED'
                          ? 'success'
                          : item.status === 'FAILED'
                            ? 'danger'
                            : 'neutral'
                      }
                    >
                      {itemStatusLabel(item.status)}
                    </Badge>
                  </div>
                  {itemErrorText(item) && (
                    <p className="text-destructive text-xs">{itemErrorText(item)}</p>
                  )}
                  {item.outputs.length > 0 && (
                    <ul className="text-muted-foreground space-y-1 text-xs">
                      {item.outputs.map((out) => (
                        <li key={out.id}>
                          {out.ratioLabel} · {outputDimensionText(out.widthPx, out.heightPx)} ·{' '}
                          {out.fileFormat.toUpperCase()} ·{' '}
                          {versionRelationText(out.fromVersion, out.toVersion)}
                        </li>
                      ))}
                    </ul>
                  )}
                </li>
              ))}
            </ul>
          )}
        </SectionCard>
      )}

      {editing && (
        <RecipeEditor
          value={editing}
          busy={busy}
          onChange={setEditing}
          onClose={() => setEditing(null)}
          onSave={() => void saveRecipeForm()}
        />
      )}

      {picking && (
        <Picker
          recipes={activeRecipes}
          recipeId={picking.recipeId}
          assets={assets}
          selected={selected}
          busy={busy}
          keyword={assetKeyword}
          onKeywordChange={setAssetKeyword}
          onRecipeChange={(id) => setPicking({ recipeId: id })}
          onToggle={(id) =>
            setSelected((prev) => {
              const next = new Set(prev);
              if (next.has(id)) next.delete(id);
              else next.add(id);
              return next;
            })
          }
          onLoadAssets={() => void loadAssets()}
          onClose={() => setPicking(null)}
          onSubmit={() => void submit(picking.recipeId, selectedIds)}
        />
      )}
    </div>
  );
}

/** 配方编辑器：六项能力 + 多比例档。校验与服务层同口径（image-process-model）。 */
function RecipeEditor({
  value,
  busy,
  onChange,
  onClose,
  onSave,
}: {
  value: MediaProcessRecipeInput;
  busy: boolean;
  onChange: (next: MediaProcessRecipeInput) => void;
  onClose: () => void;
  onSave: () => void;
}) {
  const problem = recipeProblem(value);
  const set = <K extends keyof MediaProcessRecipeInput>(key: K, next: MediaProcessRecipeInput[K]) =>
    onChange({ ...value, [key]: next });
  const ratios = (value.ratios ?? []).filter((r): r is NonNullable<typeof r> => r !== null);

  return (
    <SectionCard
      title={value.version > 0 ? '编辑配方' : '新建处理配方'}
      description="配方是一套可复用的加工规格。编码建后不可改：产物的比例档是按这套规格解释的。"
      extra={
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={onClose}>
            取消
          </Button>
          <Button size="sm" onClick={onSave} disabled={busy || !!problem}>
            <Save className="h-4 w-4" />
            保存
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        {problem && <p className="text-destructive text-sm">{problem}</p>}
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="配方编码" hint="英文字母、数字、下划线或短横线；建后不可改">
            <input
              className={control}
              value={value.code}
              onChange={(e) => set('code', e.target.value)}
            />
          </Field>
          <Field label="配方名称">
            <input
              className={control}
              value={value.name}
              onChange={(e) => set('name', e.target.value)}
            />
          </Field>
          <Field label="目标平台" hint="给这套规格的用途说明，如 AMAZON / SHOPIFY">
            <input
              className={control}
              value={value.targetPlatform ?? ''}
              onChange={(e) => set('targetPlatform', e.target.value)}
            />
          </Field>
          <Field label="输出格式" hint="服务端用 JDK ImageIO 写出；WEBP 没有内置写入器">
            <select
              className={control}
              value={value.targetFormat}
              onChange={(e) => set('targetFormat', e.target.value)}
            >
              {TARGET_FORMATS.map((f) => (
                <option key={f.value} value={f.value}>
                  {f.label} —— {f.hint}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={value.removeBackground}
              onChange={(e) => set('removeBackground', e.target.checked)}
            />
            去背景（从四角泛洪）
          </label>
          <Field label="去背景容差" hint="0~255：与背景色距离小于它的像素算背景">
            <input
              type="number"
              className={control}
              value={value.backgroundTolerance ?? 0}
              onChange={(e) => set('backgroundTolerance', Number(e.target.value))}
            />
          </Field>
          <label className="flex items-center gap-2 self-end pb-2 text-sm">
            <input
              type="checkbox"
              checked={value.whiteBackground}
              onChange={(e) => set('whiteBackground', e.target.checked)}
            />
            白底（去背景后铺纯白）
          </label>
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="水印文字" hint="留空表示不加水印">
            <input
              className={control}
              value={value.watermarkText ?? ''}
              onChange={(e) => set('watermarkText', e.target.value)}
            />
          </Field>
          <Field label="水印位置">
            <select
              className={control}
              value={value.watermarkPosition ?? 'BOTTOM_RIGHT'}
              onChange={(e) => set('watermarkPosition', e.target.value)}
            >
              {WATERMARK_POSITIONS.map((p) => (
                <option key={p.value} value={p.value}>
                  {p.label}
                  {'hint' in p && p.hint ? ` —— ${p.hint}` : ''}
                </option>
              ))}
            </select>
          </Field>
          <Field label="水印不透明度" hint="0~100">
            <input
              type="number"
              className={control}
              value={value.watermarkOpacity ?? 30}
              onChange={(e) => set('watermarkOpacity', Number(e.target.value))}
            />
          </Field>
        </div>

        <div className="grid gap-3 sm:grid-cols-3">
          <Field label="目标宽（px）" hint="留空表示这一档不缩放">
            <input
              type="number"
              className={control}
              value={value.targetWidthPx ?? ''}
              onChange={(e) =>
                set('targetWidthPx', e.target.value === '' ? null : Number(e.target.value))
              }
            />
          </Field>
          <Field label="目标高（px）" hint="与宽同填或同空">
            <input
              type="number"
              className={control}
              value={value.targetHeightPx ?? ''}
              onChange={(e) =>
                set('targetHeightPx', e.target.value === '' ? null : Number(e.target.value))
              }
            />
          </Field>
          <Field label="适配方式">
            <select
              className={control}
              value={value.cropMode ?? 'FIT'}
              onChange={(e) => set('cropMode', e.target.value)}
            >
              {CROP_MODES.map((m) => (
                <option key={m.value} value={m.value}>
                  {m.label}
                </option>
              ))}
            </select>
          </Field>
        </div>

        <div className="space-y-2">
          <div className="flex items-center justify-between">
            <span className="text-sm font-medium">比例档（多比例版本）</span>
            <Button
              size="sm"
              variant="outline"
              onClick={() =>
                set('ratios', [
                  ...ratios,
                  { label: `R${ratios.length + 1}`, widthPx: 800, heightPx: 800, cropMode: 'FIT' },
                ])
              }
            >
              <Plus className="h-4 w-4" />
              添加一档
            </Button>
          </div>
          {ratios.length === 0 ? (
            <p className="text-muted-foreground text-xs">
              不配比例档时，提交会按配方自身的目标尺寸输出一版。
            </p>
          ) : (
            <ul className="space-y-2">
              {ratios.map((ratio, index) => (
                <li key={index} className="grid items-end gap-2 sm:grid-cols-5">
                  <Field label="标签">
                    <input
                      className={control}
                      value={ratio.label}
                      onChange={(e) => {
                        const next = [...ratios];
                        next[index] = { ...ratio, label: e.target.value };
                        set('ratios', next);
                      }}
                    />
                  </Field>
                  <Field label="宽">
                    <input
                      type="number"
                      className={control}
                      value={ratio.widthPx}
                      onChange={(e) => {
                        const next = [...ratios];
                        next[index] = { ...ratio, widthPx: Number(e.target.value) };
                        set('ratios', next);
                      }}
                    />
                  </Field>
                  <Field label="高">
                    <input
                      type="number"
                      className={control}
                      value={ratio.heightPx}
                      onChange={(e) => {
                        const next = [...ratios];
                        next[index] = { ...ratio, heightPx: Number(e.target.value) };
                        set('ratios', next);
                      }}
                    />
                  </Field>
                  <Field label="适配">
                    <select
                      className={control}
                      value={ratio.cropMode ?? 'FIT'}
                      onChange={(e) => {
                        const next = [...ratios];
                        next[index] = { ...ratio, cropMode: e.target.value };
                        set('ratios', next);
                      }}
                    >
                      {CROP_MODES.map((m) => (
                        <option key={m.value} value={m.value}>
                          {m.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Button
                    size="sm"
                    variant="outline"
                    onClick={() =>
                      set(
                        'ratios',
                        ratios.filter((_, i) => i !== index),
                      )
                    }
                  >
                    <Trash2 className="h-4 w-4" />
                  </Button>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </SectionCard>
  );
}

/** 素材勾选面板。提交是异步受理，所以这里只负责「选哪些 + 用哪套配方」。 */
function Picker({
  recipes,
  recipeId,
  assets,
  selected,
  busy,
  keyword,
  onKeywordChange,
  onRecipeChange,
  onToggle,
  onLoadAssets,
  onClose,
  onSubmit,
}: {
  recipes: MediaProcessRecipeRow[];
  recipeId: string;
  assets: { id: string; name: string; widthPx: number | null; heightPx: number | null }[];
  selected: Set<string>;
  busy: boolean;
  keyword: string;
  onKeywordChange: (value: string) => void;
  onRecipeChange: (id: string) => void;
  onToggle: (id: string) => void;
  onLoadAssets: () => void;
  onClose: () => void;
  onSubmit: () => void;
}) {
  const sizeProblem = assetsSizeProblem(selected.size);
  const chosen = recipes.find((r) => r.id === recipeId);

  return (
    <SectionCard
      title="提交图片处理"
      description="处理是异步任务：提交后立刻返回，像素加工在线程池里跑，进度会自动刷新。"
      extra={
        <div className="flex gap-2">
          <Button size="sm" variant="outline" onClick={onClose}>
            取消
          </Button>
          <Button size="sm" onClick={onSubmit} disabled={busy || !!sizeProblem}>
            <Wand2 className="h-4 w-4" />
            提交 {selected.size} 个素材
          </Button>
        </div>
      }
    >
      <div className="space-y-3">
        {sizeProblem && <p className="text-destructive text-sm">{sizeProblem}</p>}

        <Field label="处理配方">
          <select
            className={control}
            value={recipeId}
            onChange={(e) => onRecipeChange(e.target.value)}
          >
            {recipes.map((r) => (
              <option key={r.id} value={r.id}>
                {r.name} —— {recipeSummary(r)}
              </option>
            ))}
          </select>
        </Field>

        {chosen && (
          <p className="text-muted-foreground text-xs">
            每个素材会产出 {Math.max(1, chosen.ratios.length)}{' '}
            个版本；原图版本保留在素材的版本历史里。
          </p>
        )}

        <div className="flex gap-2">
          <input
            className={control}
            placeholder="搜素材名"
            value={keyword}
            onChange={(e) => onKeywordChange(e.target.value)}
          />
          <Button size="sm" variant="outline" onClick={onLoadAssets}>
            搜索素材
          </Button>
        </div>

        {assets.length === 0 ? (
          <EmptyState
            title="还没有可选素材"
            description="先搜索素材（只列出图片）。视频不参与本模块的处理。"
          />
        ) : (
          <ul className="divide-border divide-y">
            {assets.map((asset) => (
              <li key={asset.id} className="flex items-center justify-between gap-3 py-2">
                <label className="flex min-w-0 items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={selected.has(asset.id)}
                    onChange={() => onToggle(asset.id)}
                  />
                  <span className="truncate">{asset.name}</span>
                  <span className="text-muted-foreground text-xs">
                    {outputDimensionText(asset.widthPx, asset.heightPx)}
                  </span>
                </label>
              </li>
            ))}
          </ul>
        )}
      </div>
    </SectionCard>
  );
}
