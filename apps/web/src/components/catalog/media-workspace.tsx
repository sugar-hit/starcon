'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ArrowLeft,
  FileWarning,
  Image as ImageIcon,
  Plus,
  RefreshCw,
  Save,
  ShieldCheck,
  Tags,
  Trash2,
  TriangleAlert,
  Upload,
} from 'lucide-react';
import type {
  MediaDetail,
  MediaIntegrityResult,
  MediaOverview,
  MediaRow,
  MediaTagInput,
  MediaTagRow,
  MediaUpload,
  MediaUpdate,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  INTEGRITY_STATUSES,
  MEDIA_TYPES,
  PURPOSES,
  REFERENCED_FILTERS,
  addReference,
  addVersion,
  byteSizeText,
  deleteMedia,
  deleteTag,
  dimensionText,
  durationText,
  emptyHint,
  emptyTagInput,
  emptyUploadInput,
  errorText,
  fetchDetail,
  fetchMedia,
  fetchOverview,
  fetchTags,
  fileProblem,
  integrityAction,
  integrityFailing,
  integrityLabel,
  integrityTone,
  mediaTypeLabel,
  purposeLabel,
  removeReference,
  saveMedia,
  saveTag,
  setMediaEnabled,
  setTagEnabled,
  toMediaUpdate,
  uploadMedia,
  uploadProblem,
  verifyAsset,
  verifySweep,
  type ReferencedFilter,
} from './media-model';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.4.1.1 媒体库工作区。
 *
 * <p>页面按「上传 → 找素材 → 看它健不健康 → 谁在用」的顺序排，每个区块回答一个独立问题：
 *
 * <ul>
 *   <li><b>概览</b> —— 规模，以及最要紧的那个数：多少素材在存储里已经找不到了
 *       （刊登侧引用了它，用户点开却是 404）。
 *   <li><b>素材列表</b> —— 分页 + 搜索 + 筛选。每行直接带引用计数、标签与完整性状态：
 *       删除保护要让人在列表上就看见「被引用着、删不掉」。
 *   <li><b>素材详情</b> —— 探测出的元数据、版本历史、引用清单、完整性校验历史。
 *   <li><b>上传与版本</b> —— 文件本体走 multipart；格式/宽高/哈希由服务端从字节探测。
 *   <li><b>引用管理</b> —— 挂到 SPU / SKU 上，并从这里解除引用。
 *   <li><b>分类标签</b> —— 租户级标签字典，带使用数；批量刊登按标签筛素材是主路径。
 *   <li><b>完整性巡检</b> —— 真的把对象读回来算 SHA-256，不是「相信元数据里那行哈希」。
 * </ul>
 */
export function MediaWorkspace() {
  const { session } = useIdentity();
  const writable = session.permissions.includes('catalog.assets.update');

  const [overview, setOverview] = useState<MediaOverview | null>(null);
  const [rows, setRows] = useState<MediaRow[]>([]);
  const [total, setTotal] = useState(0);
  const [failingCount, setFailingCount] = useState(0);
  const [unreferencedCount, setUnreferencedCount] = useState(0);
  const [disabledCount, setDisabledCount] = useState(0);
  const [note, setNote] = useState('');
  const [tags, setTags] = useState<MediaTagRow[]>([]);
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');

  const [keyword, setKeyword] = useState('');
  const [mediaType, setMediaType] = useState('');
  const [purpose, setPurpose] = useState('');
  const [tagCode, setTagCode] = useState('');
  const [integrityStatus, setIntegrityStatus] = useState('');
  const [referenced, setReferenced] = useState<ReferencedFilter>('');
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [index, setIndex] = useState(0);

  const [detail, setDetail] = useState<MediaDetail | null>(null);
  const [uploading, setUploading] = useState(false);
  const [uploadInput, setUploadInput] = useState<MediaUpload>(emptyUploadInput());
  const [uploadFile, setUploadFile] = useState<File | null>(null);
  const [versionFile, setVersionFile] = useState<File | null>(null);
  const [versionNote, setVersionNote] = useState('');
  const [editing, setEditing] = useState<MediaUpdate | null>(null);
  const [editTags, setEditTags] = useState<string[]>([]);
  const [referenceTarget, setReferenceTarget] = useState('SPU');
  const [referenceId, setReferenceId] = useState('');
  const [tagInput, setTagInput] = useState<MediaTagInput | null>(null);
  const [editingTagId, setEditingTagId] = useState<string | null>(null);
  const [sweep, setSweep] = useState<MediaIntegrityResult[] | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);
  const versionInput = useRef<HTMLInputElement | null>(null);

  const filtered =
    Boolean(keyword.trim()) ||
    mediaType !== '' ||
    purpose !== '' ||
    tagCode !== '' ||
    integrityStatus !== '' ||
    referenced !== '' ||
    enabled !== null;

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [overviewResult, pageResult, tagsResult] = await Promise.all([
        fetchOverview(),
        fetchMedia({
          keyword,
          mediaType,
          purpose,
          tagCode,
          integrityStatus,
          referenced: referenced === '' ? null : referenced === 'used',
          enabled,
          page: index,
          size: PAGE_SIZE,
        }),
        ready ? Promise.resolve(null) : fetchTags(),
      ]);
      setOverview(overviewResult);
      setRows(pageResult.items ?? []);
      setTotal(pageResult.total);
      setFailingCount(pageResult.failingIntegrityCount);
      setUnreferencedCount(pageResult.unreferencedCount);
      setDisabledCount(pageResult.disabledCount);
      setNote(pageResult.note);
      if (tagsResult) {
        setTags(tagsResult);
        setReady(true);
      }
    } catch (e) {
      setError(errorText(e, '素材数据加载失败'));
    } finally {
      setLoading(false);
    }
    // ready 只在首次加载后才有意义，留在依赖里会让每次刷新都重取标签字典。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword, mediaType, purpose, tagCode, integrityStatus, referenced, enabled, index]);

  useEffect(() => {
    void load();
  }, [load]);

  const enabledTags = useMemo(() => tags.filter((tag) => tag.enabled), [tags]);
  const lastPage = Math.max(0, Math.ceil(total / PAGE_SIZE) - 1);

  async function openDetail(id: string) {
    setBusy(true);
    setActionError(null);
    setUploading(false);
    setVersionFile(null);
    setVersionNote('');
    setSweep(null);
    try {
      const next = await fetchDetail(id);
      setDetail(next);
      setEditing(null);
      setEditTags(Object.keys(next.tags ?? {}));
      setReferenceTarget('SPU');
      setReferenceId('');
    } catch (e) {
      setActionError(errorText(e, '素材详情加载失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitUpload() {
    const fileProblemText = fileProblem(uploadFile);
    if (fileProblemText) {
      setActionError(fileProblemText);
      return;
    }
    const problem = uploadProblem(uploadInput);
    if (problem) {
      setActionError(problem);
      return;
    }
    if (!uploadFile) return;
    setBusy(true);
    setActionError(null);
    try {
      const saved = await uploadMedia(uploadFile, uploadInput);
      setNotice(
        saved.deduplicated
          ? '这份内容素材库里已经有了，已复用既有素材（没有新建、也没有重复存一份文件）'
          : '素材已上传：格式、尺寸与哈希都由服务端从文件内容探测得出',
      );
      setUploading(false);
      setUploadFile(null);
      setUploadInput(emptyUploadInput());
      if (fileInput.current) fileInput.current.value = '';
      await load();
      await openDetail(saved.id);
    } catch (e) {
      setActionError(errorText(e, '素材上传失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitVersion() {
    const fileProblemText = fileProblem(versionFile);
    if (fileProblemText) {
      setActionError(fileProblemText);
      return;
    }
    if (!detail || !versionFile) return;
    setBusy(true);
    setActionError(null);
    try {
      await addVersion(detail.asset.id, versionFile, versionNote.trim() || null);
      setNotice('新版本已保存：旧版本仍然保留（3.4.1.2 要求原图不被覆盖）');
      setVersionFile(null);
      setVersionNote('');
      if (versionInput.current) versionInput.current.value = '';
      await openDetail(detail.asset.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '新版本保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitEdit() {
    if (!detail || !editing) return;
    setBusy(true);
    setActionError(null);
    try {
      await saveMedia(detail.asset.id, { ...editing, tagCodes: editTags });
      setNotice('素材已保存');
      setEditing(null);
      await openDetail(detail.asset.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '素材保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function toggleEnabled(row: MediaRow) {
    setBusy(true);
    setActionError(null);
    try {
      await setMediaEnabled(row.id, !row.enabled);
      setNotice(row.enabled ? '素材已停用：不再出现在新建引用的候选里' : '素材已启用');
      await load();
      if (detail?.asset.id === row.id) await openDetail(row.id);
    } catch (e) {
      setActionError(errorText(e, '启用停用失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeAsset() {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      await deleteMedia(detail.asset.id, detail.asset.version);
      setNotice('素材及其全部版本对象已删除');
      setDetail(null);
      setEditing(null);
      await load();
    } catch (e) {
      setActionError(errorText(e, '素材删除失败'));
    } finally {
      setBusy(false);
    }
  }

  async function verify(id: string) {
    setBusy(true);
    setActionError(null);
    try {
      const result = await verifyAsset(id);
      setNotice(
        result.healthy
          ? '校验通过：存储里的字节数与 SHA-256 都与登记时一致'
          : `校验发现问题：${integrityLabel(result.integrityStatus)}。${integrityAction(result.integrityStatus) ?? ''}`,
      );
      await openDetail(id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '完整性校验失败'));
    } finally {
      setBusy(false);
    }
  }

  async function runSweep() {
    setBusy(true);
    setActionError(null);
    try {
      const result = await verifySweep();
      setSweep(result.results ?? []);
      setNotice(
        `巡检完成：共校验 ${result.checkedCount} 个，正常 ${result.healthyCount} 个，缺失 ${result.missingCount} 个`,
      );
      await load();
    } catch (e) {
      setActionError(errorText(e, '巡检失败'));
    } finally {
      setBusy(false);
    }
  }

  async function submitReference() {
    if (!detail) return;
    if (!referenceId.trim()) {
      setActionError('请填写要引用的商品 / SKU 的 ID');
      return;
    }
    setBusy(true);
    setActionError(null);
    try {
      const saved = await addReference(detail.asset.id, {
        ownerType: referenceTarget,
        spuId: referenceTarget === 'SPU' ? referenceId.trim() : null,
        skuId: referenceTarget === 'SKU' ? referenceId.trim() : null,
        note: null,
      });
      setNotice(
        saved.deletable
          ? '引用已登记'
          : `引用已登记。素材现在被 ${saved.referenceCount} 处引用，删除保护已生效`,
      );
      setReferenceId('');
      await openDetail(detail.asset.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '引用登记失败'));
    } finally {
      setBusy(false);
    }
  }

  async function unlink(referenceIdValue: string) {
    if (!detail) return;
    setBusy(true);
    setActionError(null);
    try {
      const saved = await removeReference(detail.asset.id, referenceIdValue);
      setNotice(
        saved.deletable
          ? '引用已解除：这个素材现在可以删除了'
          : `引用已解除，仍有 ${saved.referenceCount} 处引用`,
      );
      await openDetail(detail.asset.id);
      await load();
    } catch (e) {
      setActionError(errorText(e, '解除引用失败'));
    } finally {
      setBusy(false);
    }
  }

  /**
   * 保存标签。`editingTagId` 为空即新建——用一个独立的 id state 表达
   * 「正在改哪一条」，比在表单对象里塞一个契约里没有的 id 字段干净。
   */
  async function submitTag() {
    if (!tagInput) return;
    setBusy(true);
    setActionError(null);
    try {
      await saveTag(editingTagId, tagInput);
      setNotice('标签已保存');
      setTagInput(null);
      setEditingTagId(null);
      setTags(await fetchTags());
    } catch (e) {
      setActionError(errorText(e, '标签保存失败'));
    } finally {
      setBusy(false);
    }
  }

  async function removeTag(tag: MediaTagRow) {
    setBusy(true);
    setActionError(null);
    try {
      await deleteTag(tag.id, tag.version);
      setNotice('标签已删除');
      setTags(await fetchTags());
    } catch (e) {
      setActionError(errorText(e, '标签删除失败'));
    } finally {
      setBusy(false);
    }
  }

  async function toggleTag(tag: MediaTagRow) {
    setBusy(true);
    setActionError(null);
    try {
      await setTagEnabled(tag.id, !tag.enabled);
      setTags(await fetchTags());
    } catch (e) {
      setActionText(e, '标签启停失败');
    } finally {
      setBusy(false);
    }
  }

  function setActionText(e: unknown, fallback: string) {
    setActionError(errorText(e, fallback));
  }

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
        <h1 className="text-h-20 font-semibold tracking-tight">媒体库</h1>
        <p className="text-copy-13 text-muted-foreground">
          图片与视频的集中管理：上传、分类标签、引用计数、版本与失效检测。
          文件本体存在对象存储，这里管理的是元数据与「谁在用它」。
        </p>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="素材总数"
          value={overview?.assetCount ?? 0}
          icon={<ImageIcon className="h-4 w-4" />}
        />
        <StatCard
          label="图片 / 视频"
          value={`${overview?.imageCount ?? 0} / ${overview?.videoCount ?? 0}`}
        />
        <StatCard
          label="文件缺失"
          value={overview?.missingCount ?? 0}
          icon={<FileWarning className="h-4 w-4" />}
          polarity={overview && overview.missingCount > 0 ? 'negative' : 'positive'}
          delta={failingCount}
          comparisonLabel="含大小/内容不符"
        />
        <StatCard
          label="无引用可清理"
          value={overview?.unreferencedAssetCount ?? 0}
          polarity={overview && overview.unreferencedAssetCount > 0 ? 'negative' : 'positive'}
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
          title="上传素材"
          description="格式、尺寸、时长与哈希都由服务端从文件内容探测得出，不需要（也不允许）手填。"
          extra={
            !uploading && (
              <Button size="sm" onClick={() => setUploading(true)}>
                <Upload className="h-4 w-4" />
                选择文件上传
              </Button>
            )
          }
        >
          {uploading ? (
            <div className="space-y-3">
              <Field
                label="文件"
                hint="单文件上限 50 MiB；支持 PNG / JPEG / GIF / BMP，MP4 / MOV / WEBM / AVI"
              >
                <input
                  ref={fileInput}
                  type="file"
                  className={control}
                  accept="image/png,image/jpeg,image/gif,image/bmp,video/mp4,video/quicktime,video/webm,video/x-msvideo"
                  onChange={(e) => {
                    const file = e.target.files?.[0] ?? null;
                    setUploadFile(file);
                    // 名称默认取文件名去掉扩展名：运营上传时最不想做的一件事就是再打一遍名字。
                    if (file && !uploadInput.name.trim())
                      setUploadInput((prev) => ({
                        ...prev,
                        name: file.name.replace(/\.[^.]+$/, ''),
                      }));
                    // 媒体类型跟着探测提示走，但**不自动定**——最终以服务端探测为准。
                  }}
                />
              </Field>
              {uploadFile && fileProblem(uploadFile) && (
                <p className="text-destructive text-sm">{fileProblem(uploadFile)}</p>
              )}
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="素材名称">
                  <input
                    className={control}
                    value={uploadInput.name}
                    placeholder="白底主图-正面"
                    onChange={(e) => setUploadInput((prev) => ({ ...prev, name: e.target.value }))}
                  />
                </Field>
                <Field label="媒体类型">
                  <select
                    className={control}
                    value={uploadInput.mediaType}
                    onChange={(e) =>
                      setUploadInput((prev) => ({
                        ...prev,
                        mediaType: e.target.value,
                        purpose:
                          prev.purpose === 'OTHER' && e.target.value === 'IMAGE'
                            ? prev.purpose
                            : e.target.value === 'VIDEO'
                              ? 'VIDEO'
                              : prev.purpose,
                      }))
                    }
                  >
                    {MEDIA_TYPES.map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="用途">
                  <select
                    className={control}
                    value={uploadInput.purpose}
                    onChange={(e) =>
                      setUploadInput((prev) => ({ ...prev, purpose: e.target.value }))
                    }
                  >
                    {PURPOSES.map((item) => (
                      <option key={item.value} value={item.value}>
                        {item.label}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="标签编码" hint="逗号分隔；不存在的编码会自动建标签">
                  <input
                    className={control}
                    value={(uploadInput.tagCodes ?? []).join(',')}
                    placeholder="WHITE-BG, HERO"
                    onChange={(e) =>
                      setUploadInput((prev) => ({
                        ...prev,
                        tagCodes: e.target.value
                          .split(',')
                          .map((item) => item.trim())
                          .filter(Boolean),
                      }))
                    }
                  />
                </Field>
                <Field label="替代文本" hint="亚马逊等平台强制要求；给用户读屏用的描述">
                  <input
                    className={control}
                    value={uploadInput.altText ?? ''}
                    onChange={(e) =>
                      setUploadInput((prev) => ({ ...prev, altText: e.target.value }))
                    }
                  />
                </Field>
                <Field label="说明">
                  <input
                    className={control}
                    value={uploadInput.description ?? ''}
                    onChange={(e) =>
                      setUploadInput((prev) => ({ ...prev, description: e.target.value }))
                    }
                  />
                </Field>
              </div>
              <div className="flex gap-2">
                <Button size="sm" disabled={busy} onClick={() => void submitUpload()}>
                  <Upload className="h-4 w-4" />
                  上传
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setUploading(false);
                    setUploadFile(null);
                    if (fileInput.current) fileInput.current.value = '';
                  }}
                >
                  取消
                </Button>
              </div>
            </div>
          ) : (
            <p className="text-muted-foreground text-sm">
              同内容的素材不会重复存一份：重复上传同一张图会直接复用既有素材。 WEBP
              暂不支持直接上传（服务端无法可靠读出它的宽高），请先转成 PNG 或 JPEG。
            </p>
          )}
        </SectionCard>
      )}

      <SectionCard
        title="素材列表"
        description="每行带引用计数、标签与完整性状态：被引用着的素材在这里就能看出删不掉。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <Field label="关键词">
              <input
                className={control}
                value={keyword}
                placeholder="名称 / 文件名 / 描述 / 标签"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setIndex(0);
                }}
              />
            </Field>
            <Field label="媒体类型">
              <select
                className={control}
                value={mediaType}
                onChange={(e) => {
                  setMediaType(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {MEDIA_TYPES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="用途">
              <select
                className={control}
                value={purpose}
                onChange={(e) => {
                  setPurpose(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {PURPOSES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="标签">
              <select
                className={control}
                value={tagCode}
                onChange={(e) => {
                  setTagCode(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {tags.map((tag) => (
                  <option key={tag.id} value={tag.code}>
                    {tag.name}（{tag.code}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="完整性">
              <select
                className={control}
                value={integrityStatus}
                onChange={(e) => {
                  setIntegrityStatus(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {INTEGRITY_STATUSES.map((item) => (
                  <option key={item} value={item}>
                    {integrityLabel(item)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="引用状态">
              <select
                className={control}
                value={referenced}
                onChange={(e) => {
                  setReferenced(e.target.value as ReferencedFilter);
                  setIndex(0);
                }}
              >
                {REFERENCED_FILTERS.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="启用状态">
              <select
                className={control}
                value={enabled === null ? '' : String(enabled)}
                onChange={(e) => {
                  const value = e.target.value;
                  setEnabled(value === '' ? null : value === 'true');
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                <option value="true">启用</option>
                <option value="false">停用</option>
              </select>
            </Field>
            {writable && (
              <Field label="巡检">
                <Button size="sm" variant="outline" disabled={busy} onClick={() => void runSweep()}>
                  <ShieldCheck className="h-4 w-4" />
                  校验存储完整性
                </Button>
              </Field>
            )}
          </div>

          {loading ? (
            <p className="text-muted-foreground text-sm">正在读取素材库…</p>
          ) : rows.length === 0 ? (
            <EmptyState title="没有素材" description={emptyHint(filtered)} />
          ) : (
            <div className="space-y-2">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2">素材</th>
                    <th className="py-2">类型 / 用途</th>
                    <th className="py-2">规格</th>
                    <th className="py-2">引用</th>
                    <th className="py-2">完整性</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-border border-t align-top">
                      <td className="py-2">
                        <div className="font-medium">{row.name}</div>
                        <div className="text-muted-foreground text-xs">{row.originalFilename}</div>
                        {row.tagCodes.length > 0 && (
                          <div className="mt-1 flex flex-wrap gap-1">
                            {row.tagCodes.map((code) => (
                              <Badge key={code} tone="neutral">
                                {code}
                              </Badge>
                            ))}
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        <div className="flex flex-wrap gap-1">
                          <Badge tone="neutral">{mediaTypeLabel(row.mediaType)}</Badge>
                          <Badge tone="neutral">{purposeLabel(row.purpose)}</Badge>
                          {!row.enabled && <Badge tone="neutral">已停用</Badge>}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          v{row.currentVersion}（共 {row.versionCount} 版）
                        </div>
                      </td>
                      <td className="py-2 text-xs">
                        <div>{dimensionText(row.widthPx, row.heightPx)}</div>
                        <div className="text-muted-foreground">
                          {row.fileFormat.toUpperCase()} · {byteSizeText(row.byteSize)}
                        </div>
                        {row.durationSeconds !== null && (
                          <div className="text-muted-foreground">
                            {durationText(row.durationSeconds)}
                          </div>
                        )}
                      </td>
                      <td className="py-2">
                        {row.referenceCount === 0 ? (
                          <span className="text-muted-foreground">无引用</span>
                        ) : (
                          <Badge tone="warning">{row.referenceCount} 处 · 不可删</Badge>
                        )}
                      </td>
                      <td className="py-2">
                        <div className="space-y-1">
                          <Badge tone={integrityTone(row.integrityStatus)}>
                            {integrityLabel(row.integrityStatus)}
                          </Badge>
                          {integrityFailing(row.integrityStatus) && (
                            <div className="text-muted-foreground text-xs">
                              {integrityAction(row.integrityStatus)}
                            </div>
                          )}
                        </div>
                      </td>
                      <td className="py-2">
                        <div className="flex flex-wrap justify-end gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            onClick={() => void openDetail(row.id)}
                          >
                            查看
                          </Button>
                          {writable && (
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={busy}
                              onClick={() => void toggleEnabled(row)}
                            >
                              {row.enabled ? '停用' : '启用'}
                            </Button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="text-muted-foreground flex items-center justify-between text-xs">
                <span>
                  共 {total} 条 · 校验不通过 {failingCount} 条 · 无引用 {unreferencedCount} 条 ·
                  停用 {disabledCount} 条{note ? ` · ${note}` : ''}
                </span>
                <div className="flex items-center gap-2">
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={index <= 0}
                    onClick={() => setIndex(Math.max(0, index - 1))}
                  >
                    上一页
                  </Button>
                  <span>
                    {index + 1} / {lastPage + 1}
                  </span>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={index >= lastPage}
                    onClick={() => setIndex(index + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}

          {sweep && (
            <div className="border-border space-y-2 border-t pt-3">
              <h3 className="text-sm font-medium">巡检结果</h3>
              {sweep.length === 0 ? (
                <p className="text-muted-foreground text-sm">没有素材可校验。</p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {sweep.map((result) => (
                    <li key={result.assetId} className="flex flex-wrap items-center gap-2">
                      <Badge tone={integrityTone(result.integrityStatus)}>
                        {integrityLabel(result.integrityStatus)}
                      </Badge>
                      <span className="text-muted-foreground">{result.detail}</span>
                      <Button
                        size="sm"
                        variant="outline"
                        onClick={() => void openDetail(result.assetId)}
                      >
                        查看
                      </Button>
                    </li>
                  ))}
                </ul>
              )}
            </div>
          )}
        </div>
      </SectionCard>

      {detail && (
        <SectionCard
          title={`素材详情：${detail.asset.name}`}
          description={`存储键 ${detail.asset.storageKey}`}
          extra={
            <div className="flex flex-wrap gap-2">
              {writable && (
                <Button
                  size="sm"
                  variant="outline"
                  disabled={busy}
                  onClick={() => void verify(detail.asset.id)}
                >
                  <ShieldCheck className="h-4 w-4" />
                  校验完整性
                </Button>
              )}
              {writable && !editing && (
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setEditing(toMediaUpdate(detail, Object.keys(detail.tags ?? {})));
                    setEditTags(Object.keys(detail.tags ?? {}));
                  }}
                >
                  编辑
                </Button>
              )}
              {writable && (
                <Button
                  size="sm"
                  variant="destructive"
                  disabled={busy || !detail.deletable}
                  title={detail.deletable ? '删除这个素材' : (detail.deletableReason ?? '')}
                  onClick={() => void removeAsset()}
                >
                  <Trash2 className="h-4 w-4" />
                  删除
                </Button>
              )}
            </div>
          }
        >
          <div className="space-y-4">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone="neutral">{mediaTypeLabel(detail.asset.mediaType)}</Badge>
              <Badge tone="neutral">{purposeLabel(detail.asset.purpose)}</Badge>
              <Badge tone={integrityTone(detail.asset.integrityStatus)}>
                {integrityLabel(detail.asset.integrityStatus)}
              </Badge>
              <Badge tone={detail.deletable ? 'neutral' : 'warning'}>
                {detail.deletable ? '可删除' : '有引用，不可删除'}
              </Badge>
            </div>
            {!detail.deletable && detail.deletableReason && (
              <p className="text-warn-foreground text-sm">{detail.deletableReason}</p>
            )}
            {integrityFailing(detail.asset.integrityStatus) && (
              <p className="text-destructive text-sm">
                {integrityLabel(detail.asset.integrityStatus)}：
                {integrityAction(detail.asset.integrityStatus)}
                {detail.asset.integrityDetail ? `（${detail.asset.integrityDetail}）` : ''}
              </p>
            )}

            <dl className="grid gap-2 text-sm sm:grid-cols-2">
              <div>
                <dt className="text-muted-foreground">尺寸 / 时长</dt>
                <dd>
                  {dimensionText(detail.asset.widthPx, detail.asset.heightPx)} ·{' '}
                  {durationText(detail.asset.durationSeconds)}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">格式与大小</dt>
                <dd>
                  {detail.asset.fileFormat.toUpperCase()}（{detail.asset.mimeType}） ·{' '}
                  {byteSizeText(detail.asset.byteSize)}
                </dd>
              </div>
              <div>
                <dt className="text-muted-foreground">内容哈希</dt>
                <dd className="font-mono text-xs">{detail.asset.contentSha256}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">原始文件名</dt>
                <dd>{detail.asset.originalFilename}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">替代文本</dt>
                <dd>{detail.asset.altText ?? '未设置'}</dd>
              </div>
              <div>
                <dt className="text-muted-foreground">说明</dt>
                <dd>{detail.asset.description || '未填写'}</dd>
              </div>
            </dl>

            {editing && (
              <div className="border-border space-y-3 border-t pt-3">
                <h3 className="text-sm font-medium">编辑素材</h3>
                <div className="grid gap-3 sm:grid-cols-2">
                  <Field label="素材名称">
                    <input
                      className={control}
                      value={editing.name}
                      onChange={(e) => setEditing({ ...editing, name: e.target.value })}
                    />
                  </Field>
                  <Field label="用途" hint="媒体类型与文件本体建后不可改，文件本体请用「新版本」">
                    <select
                      className={control}
                      value={editing.purpose}
                      onChange={(e) => setEditing({ ...editing, purpose: e.target.value })}
                    >
                      {PURPOSES.map((item) => (
                        <option key={item.value} value={item.value}>
                          {item.label}
                        </option>
                      ))}
                    </select>
                  </Field>
                  <Field label="标签编码" hint="逗号分隔；整体替换（提交什么就等于什么）">
                    <input
                      className={control}
                      value={editTags.join(',')}
                      onChange={(e) =>
                        setEditTags(
                          e.target.value
                            .split(',')
                            .map((item) => item.trim().toUpperCase())
                            .filter(Boolean),
                        )
                      }
                    />
                  </Field>
                  <Field label="替代文本">
                    <input
                      className={control}
                      value={editing.altText ?? ''}
                      onChange={(e) => setEditing({ ...editing, altText: e.target.value })}
                    />
                  </Field>
                </div>
                <div className="flex gap-2">
                  <Button size="sm" disabled={busy} onClick={() => void submitEdit()}>
                    <Save className="h-4 w-4" />
                    保存
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setEditing(null)}>
                    取消
                  </Button>
                </div>
              </div>
            )}

            <div className="space-y-2">
              <h3 className="text-sm font-medium">版本历史</h3>
              {detail.versions.length === 0 ? (
                <p className="text-muted-foreground text-sm">没有版本记录。</p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {detail.versions.map((version) => (
                    <li key={version.id} className="border-border border-t pt-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <span className="font-medium">v{version.versionNo}</span>
                        {version.current && <Badge>当前版本</Badge>}
                        <span className="text-muted-foreground text-xs">
                          {version.originalFilename} ·{' '}
                          {dimensionText(version.widthPx, version.heightPx)} ·{' '}
                          {byteSizeText(version.byteSize)}
                        </span>
                      </div>
                      {version.note && (
                        <div className="text-muted-foreground text-xs">{version.note}</div>
                      )}
                      <div className="text-muted-foreground font-mono text-xs">
                        {version.storageKey}
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              {writable && (
                <div className="space-y-2">
                  <Field
                    label="上传新版本"
                    hint="旧版本永远保留：图片处理的结果落成新版本，原图不被覆盖"
                  >
                    <input
                      ref={versionInput}
                      type="file"
                      className={control}
                      onChange={(e) => setVersionFile(e.target.files?.[0] ?? null)}
                    />
                  </Field>
                  {versionFile && fileProblem(versionFile) && (
                    <p className="text-destructive text-sm">{fileProblem(versionFile)}</p>
                  )}
                  <Field label="版本说明">
                    <input
                      className={control}
                      value={versionNote}
                      placeholder="换成了 2000×2000 的版本"
                      onChange={(e) => setVersionNote(e.target.value)}
                    />
                  </Field>
                  <Button
                    size="sm"
                    disabled={busy || !versionFile}
                    onClick={() => void submitVersion()}
                  >
                    <Plus className="h-4 w-4" />
                    保存为新版本
                  </Button>
                </div>
              )}
            </div>

            <div className="space-y-2">
              <div className="flex items-center justify-between">
                <h3 className="text-sm font-medium">
                  引用（被哪些 SPU / SKU 使用）· {detail.references.length}
                </h3>
              </div>
              {detail.references.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  还没有引用。没有引用的素材可以删除；一旦被商品或 SKU 引用就会受删除保护。
                </p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {detail.references.map((reference) => (
                    <li
                      key={reference.id}
                      className="border-border flex flex-wrap items-center gap-2 border-t pt-1"
                    >
                      <Badge tone="neutral">{reference.ownerTypeLabel}</Badge>
                      <span>
                        {reference.ownerName ?? '（已失效）'}
                        {reference.ownerCode ? ` · ${reference.ownerCode}` : ''}
                      </span>
                      {reference.note && (
                        <span className="text-muted-foreground text-xs">{reference.note}</span>
                      )}
                      {writable && (
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={busy}
                          className="ml-auto"
                          onClick={() => void unlink(reference.id)}
                        >
                          解除引用
                        </Button>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {writable && (
                <div className="flex flex-wrap items-end gap-2">
                  <Field label="引用到">
                    <select
                      className={control}
                      value={referenceTarget}
                      onChange={(e) => setReferenceTarget(e.target.value)}
                    >
                      <option value="SPU">商品（SPU）</option>
                      <option value="SKU">SKU</option>
                    </select>
                  </Field>
                  <Field
                    label="ID"
                    hint="刊登侧的 listing 引用由 04 刊登交付后通过事件写入，不在这里直接填"
                  >
                    <input
                      className={control}
                      value={referenceId}
                      onChange={(e) => setReferenceId(e.target.value)}
                    />
                  </Field>
                  <Button size="sm" disabled={busy} onClick={() => void submitReference()}>
                    登记引用
                  </Button>
                </div>
              )}
            </div>

            <div className="space-y-2">
              <h3 className="text-sm font-medium">完整性校验历史</h3>
              {detail.integrityChecks.length === 0 ? (
                <p className="text-muted-foreground text-sm">
                  还没有校验过。校验会把对象真的读回来算 SHA-256，而不是相信元数据里登记的哈希。
                </p>
              ) : (
                <ul className="space-y-1 text-sm">
                  {detail.integrityChecks.map((check) => (
                    <li key={check.id} className="border-border border-t pt-1">
                      <div className="flex flex-wrap items-center gap-2">
                        <Badge tone={integrityTone(check.integrityStatus)}>
                          {integrityLabel(check.integrityStatus)}
                        </Badge>
                        <span className="text-muted-foreground text-xs">{check.checkedAt}</span>
                      </div>
                      <div className="text-muted-foreground text-xs">{check.detail}</div>
                      {check.sha256Actual && (
                        <div className="text-muted-foreground font-mono text-xs">
                          实际哈希 {check.sha256Actual}
                        </div>
                      )}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="分类标签"
        description="租户级标签字典：批量刊登按标签筛素材是主路径，所以标签要是可共享的字典而不是每行自由文本。"
        extra={
          writable &&
          !tagInput && (
            <Button
              size="sm"
              onClick={() => {
                setTagInput(emptyTagInput());
                setEditingTagId(null);
              }}
            >
              <Plus className="h-4 w-4" />
              新建标签
            </Button>
          )
        }
      >
        <div className="space-y-3">
          {tagInput && (
            <div className="border-border space-y-3 border-t pt-3">
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="标签编码" hint="英文字母、数字、下划线或短横线；建后不可改">
                  <input
                    className={control}
                    value={tagInput.code}
                    // 改既有标签时编码是只读的：筛选条件与批量刊登按 code 对齐它。
                    disabled={editingTagId !== null}
                    onChange={(e) => setTagInput({ ...tagInput, code: e.target.value })}
                  />
                </Field>
                <Field label="标签名称">
                  <input
                    className={control}
                    value={tagInput.name}
                    onChange={(e) => setTagInput({ ...tagInput, name: e.target.value })}
                  />
                </Field>
                <Field label="备注">
                  <input
                    className={control}
                    value={tagInput.note ?? ''}
                    onChange={(e) => setTagInput({ ...tagInput, note: e.target.value })}
                  />
                </Field>
              </div>
              <div className="flex gap-2">
                <Button size="sm" disabled={busy} onClick={() => void submitTag()}>
                  <Save className="h-4 w-4" />
                  保存
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setTagInput(null);
                    setEditingTagId(null);
                  }}
                >
                  取消
                </Button>
              </div>
            </div>
          )}

          {tags.length === 0 ? (
            <p className="text-muted-foreground text-sm">
              还没有标签。上传素材时填入不存在的编码会自动建标签，所以不建也能开始用。
            </p>
          ) : (
            <div className="space-y-2">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2">编码</th>
                    <th className="py-2">名称</th>
                    <th className="py-2">使用数</th>
                    <th className="py-2">状态</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {tags.map((tag) => (
                    <tr key={tag.id} className="border-border border-t">
                      <td className="py-2 font-mono text-xs">{tag.code}</td>
                      <td className="py-2">
                        {tag.name}
                        {tag.note && (
                          <div className="text-muted-foreground text-xs">{tag.note}</div>
                        )}
                      </td>
                      <td className="py-2">{tag.usageCount}</td>
                      <td className="py-2">
                        <Badge tone={tag.enabled ? 'neutral' : 'info'}>
                          {tag.enabled ? '启用' : '停用'}
                        </Badge>
                      </td>
                      <td className="py-2">
                        {writable && (
                          <div className="flex flex-wrap justify-end gap-2">
                            <Button
                              size="sm"
                              variant="outline"
                              onClick={() => {
                                setTagInput({
                                  code: tag.code,
                                  name: tag.name,
                                  note: tag.note || null,
                                  enabled: tag.enabled,
                                  version: tag.version,
                                });
                                setEditingTagId(tag.id);
                              }}
                            >
                              编辑
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              disabled={busy}
                              onClick={() => void toggleTag(tag)}
                            >
                              {tag.enabled ? '停用' : '启用'}
                            </Button>
                            <Button
                              size="sm"
                              variant="destructive"
                              disabled={busy || tag.usageCount > 0}
                              title={
                                tag.usageCount > 0 ? '仍被素材使用，请改用停用' : '删除这个标签'
                              }
                              onClick={() => void removeTag(tag)}
                            >
                              <Trash2 className="h-4 w-4" />
                              删除
                            </Button>
                          </div>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <p className="text-muted-foreground text-xs">
                <Tags className="mr-1 inline h-3 w-3" />
                仍被素材使用的标签不允许删除（那会静默清掉一批素材的分类），请改用停用：
                停用后不再出现在新建筛选里，历史关联保留。
              </p>
            </div>
          )}
        </div>
      </SectionCard>

      {overview && (
        <SectionCard title="口径说明">
          <ul className="text-muted-foreground space-y-1 text-sm">
            <li className="flex items-start gap-2">
              <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
              {overview.note}
            </li>
            <li>
              存储：{overview.storageHealthy === 1 ? '可写' : '不可写'} · 存储占用约{' '}
              {byteSizeText(overview.byteSizeTotal)} · 引用 {overview.referenceCount} 处 · 版本{' '}
              {overview.versionCount} 条
            </li>
            <li>{detail?.note}</li>
          </ul>
        </SectionCard>
      )}
    </div>
  );
}
