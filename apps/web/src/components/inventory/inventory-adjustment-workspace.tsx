'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { FileWarning, PackageMinus, Plus, RefreshCw, ScrollText, Trash2 } from 'lucide-react';
import {
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import type {
  BucketRow,
  InventoryAdjustmentCapabilities,
  InventoryAdjustmentOrder,
  InventoryAdjustmentOrderDetail,
  InventoryAdjustmentOrderPage,
} from '../../../../../packages/api-contracts/src/index';
import { fetchBuckets, type BucketParams } from './inventory-model';
import {
  actionBalanceNotes,
  actionLabels,
  addAdjustmentLine,
  allowedActions,
  approveAdjustment,
  approveProblem,
  attachAdjustmentEvidence,
  attachmentSummary,
  cancelAdjustment,
  createAdjustment,
  createProblem,
  errorText,
  fetchAdjustment,
  fetchAdjustments,
  fetchAdjustmentCapabilities,
  formatMoment,
  lineProblem,
  lineStateText,
  postAdjustment,
  qty,
  rejectAdjustment,
  rejectProblem,
  removeAdjustmentEvidence,
  removeAdjustmentLine,
  selectableReasons,
  signedQty,
  signedTone,
  statusLabels,
  statusTone,
  submitAdjustment,
} from './inventory-adjustment-model';
import { useIdentity } from '../iam/identity-shell';

/**
 * 7.4.1.1 调整单管理工作区。
 *
 * <p>蓝图：「**发起库存调整**（盘盈盘亏、损坏、报废、错发纠正、平台仓差异），填写原因与凭证，
 * **支持批量调整**。」输出：「调整单（草稿/待审/已批/已执行）；库存分录（反向或正向）。」
 * 以及红线：「**调整必须走单据**，不允许在明细页直接改余额——没有单据就没有责任人与原因。」
 *
 * <p>界面纪律（每一条都对应一个会被误显示的坑）：
 *
 * <ul>
 *   <li>**建单、提交、批准都不改余额**，只有「过账」那一步改。每个动作按钮旁边都写着
 *       这一步改不改余额 —— 处理人最怕的是以为「已经调完账了」。
 *   <li>**调整必须走单据**：本页没有任何「直接改某个库位数量」的入口。
 *   <li>**平台仓差异不可用**要显示成不可选并说明去向，而不是让用户填一个必然失败的码。
 *   <li>**凭证是逻辑引用**：挂的是 catalog 媒体库里的 id + 快照，本页不传文件。
 *   <li>**过期行要标出来**：库位明细在建单之后又动过时，这一行不能直接过账。
 * </ul>
 */

/** 一行待建单的调整（草稿态，尚未落库）。 */
interface DraftLine {
  key: string;
  bucket: BucketRow | null;
  qty: string;
  reasonCode: string;
  note: string;
}

/** 一份待挂的凭证（catalog 媒体库的逻辑引用 + 快照元数据）。 */
interface DraftAttachment {
  key: string;
  mediaId: string;
  fileName: string;
  mediaType: string;
  sizeBytes: string;
}

/**
 * 从 7.1.1.2 库存明细「发起调整」行内入口带过来的 bucket 定位参数。
 *
 * <p>那是一条**路由契约**：从某一行点进来时直接定位到那批货，而不是回到一个「从哪开始」的列表页。
 */
export interface AdjustmentFocus {
  skuId?: string;
  warehouseId?: string;
  locationId?: string;
  lotId?: string;
}

export function InventoryAdjustmentWorkspace({ focus }: { focus?: AdjustmentFocus }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.inventory.read');
  const writable = session.permissions.includes('warehouse.inventory.update');

  const [capabilities, setCapabilities] = useState<InventoryAdjustmentCapabilities | null>(null);
  const [orders, setOrders] = useState<InventoryAdjustmentOrderPage | null>(null);
  const [open, setOpen] = useState<InventoryAdjustmentOrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [status, setStatus] = useState('');
  const [reasonFilter, setReasonFilter] = useState('');
  const [keyword, setKeyword] = useState('');
  const [page, setPage] = useState(0);

  // 建单草稿
  const [reasonCode, setReasonCode] = useState('DAMAGE');
  const [orderNote, setOrderNote] = useState('');
  const [lines, setLines] = useState<DraftLine[]>([]);
  const [attachments, setAttachments] = useState<DraftAttachment[]>([]);

  // 凭证附件草稿（catalog 媒体库的逻辑引用 + 快照元数据）
  const [mediaId, setMediaId] = useState('');
  const [mediaFileName, setMediaFileName] = useState('');
  const [mediaType, setMediaType] = useState('');
  const [mediaSize, setMediaSize] = useState('');

  // 库位明细挑选
  const [bucketKeyword, setBucketKeyword] = useState('');
  const [buckets, setBuckets] = useState<BucketRow[]>([]);
  const [bucketLoading, setBucketLoading] = useState(false);

  // 动作对话框
  const [action, setAction] = useState<'' | 'approve' | 'reject' | 'cancel' | 'post'>('');
  const [actionNote, setActionNote] = useState('');

  // 给已存在的草稿单补挂凭证（凭证一旦随单提交就不可更换）
  const [attachMediaId, setAttachMediaId] = useState('');
  const [attachFileName, setAttachFileName] = useState('');
  const [attachMediaType, setAttachMediaType] = useState('');
  const [attachSizeBytes, setAttachSizeBytes] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, page_] = await Promise.all([
        fetchAdjustmentCapabilities(),
        fetchAdjustments({ status, reasonCode: reasonFilter, keyword, page, size: 20 }),
      ]);
      setCapabilities(caps);
      setOrders(page_);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [status, reasonFilter, keyword, page]);

  useEffect(() => {
    if (!readable) return;
    void load();
  }, [readable, load]);

  const loadBuckets = useCallback(async () => {
    setBucketLoading(true);
    try {
      // exactOptionalPropertyTypes 下**不能**显式传 undefined：
      // BucketParams 的可选字段是「有值才带」，显式 undefined 会让它变成
      // 「带了一个 undefined 值」，语义不同且类型不兼容。
      // 所以先筛掉空值再拼。
      const params: BucketParams = { keyword: bucketKeyword, size: 20 };
      if (focus?.skuId) params.skuId = focus.skuId;
      if (focus?.warehouseId) params.warehouseId = focus.warehouseId;
      if (focus?.locationId) params.locationId = focus.locationId;
      if (focus?.lotId) params.lotId = focus.lotId;
      const detail = await fetchBuckets(params);
      setBuckets(detail.rows);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBucketLoading(false);
    }
  }, [bucketKeyword, focus?.skuId, focus?.warehouseId, focus?.locationId, focus?.lotId]);

  useEffect(() => {
    if (!readable) return;
    void loadBuckets();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [readable]);

  const reasons = selectableReasons(capabilities?.reasons);
  const unavailableReasons = (capabilities?.reasons ?? []).filter((r) => !r.available);

  const openOrder = useCallback(async (id: string) => {
    setError('');
    setNotice('');
    setAction('');
    setActionNote('');
    try {
      setOpen(await fetchAdjustment(id));
    } catch (e) {
      setError(errorText(e));
    }
  }, []);

  const afterMutation = useCallback(
    async (message: string) => {
      setNotice(message);
      await load();
      if (open) {
        try {
          setOpen(await fetchAdjustment(open.order.id));
        } catch (e) {
          setError(errorText(e));
        }
      }
    },
    [load, open],
  );

  // ------------------------------------------------------------ 建单

  const addDraftLine = useCallback((bucket: BucketRow) => {
    setLines((current) => [
      ...current,
      { key: `${bucket.bucketId}-${current.length}`, bucket, qty: '', reasonCode: '', note: '' },
    ]);
  }, []);

  const submitCreate = useCallback(async () => {
    const problem = createProblem(
      reasonCode,
      orderNote,
      lines.map((l) => ({
        reasonCode: l.reasonCode || reasonCode,
        qty: l.qty,
        expectedVersion: l.bucket ? l.bucket.version : null,
      })),
    );
    if (problem) {
      setError(problem);
      return;
    }
    setSaving(true);
    setError('');
    try {
      const detail = await createAdjustment({
        reasonCode,
        note: orderNote || null,
        lines: lines.map((l) => ({
          bucketId: l.bucket!.bucketId,
          qtyDelta: Number(l.qty),
          reasonCode: l.reasonCode || reasonCode,
          expectedVersion: l.bucket!.version,
          note: l.note || null,
        })),
        attachments: attachments.map((a) => ({
          mediaId: a.mediaId,
          fileName: a.fileName,
          mediaType: a.mediaType || null,
          sizeBytes: a.sizeBytes ? Number(a.sizeBytes) : 0,
        })),
        sourceOrderId: null,
        sourceInstructionId: null,
      });
      setLines([]);
      setAttachments([]);
      setOrderNote('');
      setOpen(detail);
      await load();
      setNotice('草稿已创建。此刻余额仍未改动 —— 调整必须走单据，过账之后才动账。');
    } catch (e) {
      setError(errorText(e));
    } finally {
      setSaving(false);
    }
  }, [reasonCode, orderNote, lines, attachments, load]);

  // ------------------------------------------------------------ 动作

  const runAction = useCallback(
    async (name: 'submit' | 'approve' | 'reject' | 'cancel' | 'post') => {
      if (!open) return;
      if (name === 'approve') {
        const problem = approveProblem(actionNote);
        if (problem) {
          setError(problem);
          return;
        }
      }
      if (name === 'reject') {
        const problem = rejectProblem(actionNote);
        if (problem) {
          setError(problem);
          return;
        }
      }
      setSaving(true);
      setError('');
      try {
        const id = open.order.id;
        if (name === 'submit') {
          await submitAdjustment(id, actionNote || undefined);
          await afterMutation('已提交待审。**余额仍未改动** —— 批准之后还要再走过账这一步。');
        } else if (name === 'approve') {
          await approveAdjustment(id, actionNote);
          await afterMutation('已批准。**余额仍未改动** —— 批准只是允许入账。');
        } else if (name === 'reject') {
          await rejectAdjustment(id, actionNote);
          await afterMutation('已驳回，可改完重提。');
        } else if (name === 'cancel') {
          await cancelAdjustment(id, actionNote || undefined);
          await afterMutation('已作废（留痕，不删单）。');
        } else {
          const result = await postAdjustment(id, actionNote || undefined);
          await afterMutation(
            `已过账：写 ${result.entryCount} 条库存分录，净调整 ${signedQty(result.postedQty)}。` +
              '分录已落进 7.1.2.1 库存流水台账。',
          );
        }
        setAction('');
        setActionNote('');
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [open, actionNote, afterMutation],
  );

  const addLineToDraft = useCallback(
    async (bucket: BucketRow) => {
      if (!open) return;
      const problem = lineProblem(open.order.reasonCode, '-1', bucket.version);
      if (problem && !problem.includes('正数') && !problem.includes('负数')) {
        setError(problem);
        return;
      }
      setSaving(true);
      setError('');
      try {
        const detail = await addAdjustmentLine(open.order.id, {
          bucketId: bucket.bucketId,
          qtyDelta: -1,
          reasonCode: open.order.reasonCode,
          expectedVersion: bucket.version,
          note: null,
        });
        setOpen(detail);
        setNotice('已加行。');
      } catch (e) {
        setError(errorText(e));
      } finally {
        setSaving(false);
      }
    },
    [open],
  );

  const stats = useMemo(() => {
    const rows = orders?.orders ?? [];
    return {
      total: orders?.total ?? 0,
      pending: rows.filter((o) => o.status === 'PENDING_APPROVAL').length,
      posted: rows.filter((o) => o.status === 'POSTED').length,
      net: rows.reduce((sum, o) => sum + (o.netQty ?? 0), 0),
    };
  }, [orders]);

  if (!readable) {
    return (
      <PageHeader title="库存调整单">
        <Notice tone="warning" title="没有查看权限">
          当前角色没有 <code>warehouse.inventory.read</code> 权限，看不到调整单。
        </Notice>
      </PageHeader>
    );
  }

  return (
    <div className="space-y-5">
      <PageHeader
        title="库存调整单"
        description="盘盈盘亏、损坏、报废、错发纠正都走单据：填原因与凭证，审批通过后过账落分录。调整必须走单据 —— 不允许在明细页直接改余额。"
        actions={
          <Button size="sm" variant="outline" onClick={() => void load()} disabled={loading}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <Notice tone="danger" title="操作未完成">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      )}

      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="调整单" value={stats.total} unit="张" polarity="neutral" />
        <StatCard
          label="本页待审批"
          value={stats.pending}
          unit="张"
          polarity="neutral"
          icon={<ScrollText className="h-4 w-4" />}
        />
        <StatCard
          label="本页已过账"
          value={stats.posted}
          unit="张"
          polarity="neutral"
          icon={<FileWarning className="h-4 w-4" />}
        />
        <StatCard
          label="本页净调整"
          value={signedQty(stats.net)}
          polarity="neutral"
          icon={<PackageMinus className="h-4 w-4" />}
        />
      </div>

      {capabilities && (
        <SectionCard title="边界说明" description="这些是本模块**不**做或**尚未接入**的部分，先讲清楚免得误以为有入口。">
          <div className="space-y-3 text-copy-13">
            <div>
              <div className="text-foreground font-medium">审批归属</div>
              <div className="text-muted-foreground">{capabilities.approvalNote}</div>
            </div>
            <div>
              <div className="text-foreground font-medium">过账与流水</div>
              <div className="text-muted-foreground">{capabilities.postingNote}</div>
            </div>
            <div>
              <div className="text-foreground font-medium">凭证附件</div>
              <div className="text-muted-foreground">{capabilities.attachmentNote}</div>
            </div>
            {unavailableReasons.length > 0 && (
              <div>
                <div className="text-foreground font-medium">不可用的原因码</div>
                <ul className="text-muted-foreground list-disc pl-5">
                  {unavailableReasons.map((r) => (
                    <li key={r.value}>
                      {r.label}（{r.value}，归属 {r.ownerModule}）：{r.note}
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {capabilities.dependencies
              .filter((d) => !d.available)
              .map((d) => (
                <div key={d.key}>
                  <div className="text-foreground font-medium">
                    {d.label}（未接入，归属 {d.ownerModule}）
                  </div>
                  <div className="text-muted-foreground">{d.note}</div>
                </div>
              ))}
          </div>
        </SectionCard>
      )}

      <SectionCard
        title="发起调整"
        description="批量调整：一次挑多个库位明细，落成一张草稿单。草稿阶段余额一个数都不会动。"
        extra={
          <Button size="sm" disabled={!writable || saving} onClick={() => void submitCreate()}>
            <Plus className="h-4 w-4" />
            创建草稿
          </Button>
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <FormField
              id="adjust-reason"
              label="单据级原因码"
              required
              hint="受控词表；行可以覆盖它。「盘盈」只接受正数、「盘亏」只接受负数。"
            >
              <select
                id="adjust-reason"
                className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                value={reasonCode}
                onChange={(e) => setReasonCode(e.target.value)}
              >
                {reasons.map((r) => (
                  <option key={r.value} value={r.value}>
                    {r.label}（{r.value}）
                  </option>
                ))}
              </select>
            </FormField>
            <FormField
              id="adjust-note"
              label="单据说明"
              hint="给人看的原文。原因**码**是受控值，原文是自由文本。"
            >
              <Input
                id="adjust-note"
                value={orderNote}
                maxLength={500}
                onChange={(e) => setOrderNote(e.target.value)}
                placeholder="例如：外箱破损，按现场照片调整"
              />
            </FormField>
          </div>

          <div>
            <div className="text-label-13 text-foreground mb-2">
              调整行（已选 {lines.length} 行）
            </div>
            {lines.length === 0 ? (
              <p className="text-muted-foreground text-copy-13">
                还没有行。从下面的库位明细里挑需要调整的格子 —— 调整按库位/批次/质量状态定位，
                不是按 SKU 定位。
              </p>
            ) : (
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left text-xs">
                  <tr>
                    <th className="py-2">商品 / 库位</th>
                    <th className="py-2 text-right">在手</th>
                    <th className="py-2 text-right">调整量</th>
                    <th className="py-2">原因</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {lines.map((line) => (
                    <tr key={line.key} className="border-control-border border-t">
                      <td className="py-2">
                        <div className="font-medium">
                          {line.bucket?.skuCode} {line.bucket?.skuName}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          {line.bucket?.warehouseCode}/{line.bucket?.locationCode}
                          {line.bucket?.lotCode ? ` · ${line.bucket.lotCode}` : ''} ·{' '}
                          {line.bucket?.qualityStatusCode}
                        </div>
                      </td>
                      <td className="py-2 text-right tabular-nums">{qty(line.bucket?.onHand)}</td>
                      <td className="py-2 text-right">
                        <input
                          aria-label="调整数量"
                          className="border-control-border bg-background h-9 w-28 rounded-md border px-2 text-right text-sm"
                          value={line.qty}
                          onChange={(e) =>
                            setLines((current) =>
                              current.map((l) =>
                                l.key === line.key ? { ...l, qty: e.target.value } : l,
                              ),
                            )
                          }
                        />
                      </td>
                      <td className="py-2">
                        <select
                          aria-label="行原因码"
                          className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
                          value={line.reasonCode || reasonCode}
                          onChange={(e) =>
                            setLines((current) =>
                              current.map((l) =>
                                l.key === line.key ? { ...l, reasonCode: e.target.value } : l,
                              ),
                            )
                          }
                        >
                          {reasons.map((r) => (
                            <option key={r.value} value={r.value}>
                              {r.label}
                            </option>
                          ))}
                        </select>
                      </td>
                      <td className="py-2 text-right">
                        <Button
                          size="sm"
                          variant="outline"
                          disabled={!writable}
                          onClick={() =>
                            setLines((current) => current.filter((l) => l.key !== line.key))
                          }
                        >
                          <Trash2 className="h-4 w-4" />
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>

          <div>
            <div className="text-label-13 text-foreground mb-2">
              凭证附件（{attachments.length} 份）
            </div>
            <p className="text-muted-foreground text-copy-13 mb-2">
              凭证是 catalog 媒体库里的**逻辑引用**：这里登记媒体 id 与文件名/类型/大小，
              本页不上传文件（媒体库是独立库，上传在那边做）。
            </p>
            <div className="flex flex-wrap items-end gap-2">
              <FormField id="media-id" label="媒体 ID">
                <Input
                  id="media-id"
                  value={mediaId}
                  onChange={(e) => setMediaId(e.target.value)}
                  placeholder="catalog 媒体 id"
                />
              </FormField>
              <FormField id="media-name" label="文件名">
                <Input
                  id="media-name"
                  value={mediaFileName}
                  onChange={(e) => setMediaFileName(e.target.value)}
                  placeholder="破损照片.jpg"
                />
              </FormField>
              <FormField id="media-type" label="类型">
                <Input
                  id="media-type"
                  value={mediaType}
                  onChange={(e) => setMediaType(e.target.value)}
                  placeholder="image/jpeg"
                />
              </FormField>
              <FormField id="media-size" label="大小（字节）">
                <Input
                  id="media-size"
                  value={mediaSize}
                  onChange={(e) => setMediaSize(e.target.value)}
                  placeholder="20480"
                />
              </FormField>
              <Button
                size="sm"
                variant="outline"
                disabled={!writable}
                onClick={() => {
                  if (!mediaId.trim() || !mediaFileName.trim()) {
                    setError('凭证附件必须带媒体 ID 与文件名：没有文件名的凭证在单据上认不出来。');
                    return;
                  }
                  setAttachments((current) => [
                    ...current,
                    {
                      key: `${mediaId.trim()}-${current.length}`,
                      mediaId: mediaId.trim(),
                      fileName: mediaFileName.trim(),
                      mediaType: mediaType.trim(),
                      sizeBytes: mediaSize.trim(),
                    },
                  ]);
                  setMediaId('');
                  setMediaFileName('');
                  setMediaType('');
                  setMediaSize('');
                }}
              >
                <Plus className="h-4 w-4" />
                加入凭证
              </Button>
            </div>
            {attachments.length > 0 && (
              <ul className="text-muted-foreground mt-2 list-disc pl-5 text-copy-13">
                {attachments.map((a) => (
                  <li key={a.key}>
                    {a.fileName}（{a.mediaId}）
                    <button
                      type="button"
                      className="text-destructive ml-2 text-xs underline"
                      onClick={() =>
                        setAttachments((current) => current.filter((x) => x.key !== a.key))
                      }
                    >
                      移除
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </div>

          <div>
            <div className="text-label-13 text-foreground mb-2">库位明细（7.1.1.2）</div>
            <div className="mb-2 flex items-center gap-2">
              <Input
                value={bucketKeyword}
                onChange={(e) => setBucketKeyword(e.target.value)}
                placeholder="按 SKU / 库位 / 批次搜索"
              />
              <Button size="sm" variant="outline" onClick={() => void loadBuckets()}>
                搜索
              </Button>
            </div>
            {bucketLoading ? (
              <p className="text-muted-foreground text-copy-13">正在读取库位明细…</p>
            ) : buckets.length === 0 ? (
              <p className="text-muted-foreground text-copy-13">
                没有匹配的库位明细。调整必须落在具体的事实单位上 —— 没有可调的库位就没有可调的账。
              </p>
            ) : (
              <div className="max-h-72 overflow-auto">
                <table className="w-full text-sm">
                  <thead className="text-muted-foreground sticky top-0 bg-card text-left text-xs">
                    <tr>
                      <th className="py-2">商品 / 库位</th>
                      <th className="py-2 text-right">在手</th>
                      <th className="py-2 text-right">可用</th>
                      <th className="py-2 text-right">版本</th>
                      <th className="py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {buckets.map((bucket) => (
                      <tr key={bucket.bucketId} className="border-control-border border-t">
                        <td className="py-2">
                          <div className="font-medium">
                            {bucket.skuCode} {bucket.skuName}
                          </div>
                          <div className="text-muted-foreground text-xs">
                            {bucket.warehouseCode}/{bucket.locationCode}
                            {bucket.lotCode ? ` · ${bucket.lotCode}` : ''} ·{' '}
                            {bucket.qualityStatusCode}
                          </div>
                        </td>
                        <td className="py-2 text-right tabular-nums">{qty(bucket.onHand)}</td>
                        <td className="py-2 text-right tabular-nums">{qty(bucket.available)}</td>
                        <td className="text-muted-foreground py-2 text-right tabular-nums">
                          {bucket.version}
                        </td>
                        <td className="py-2 text-right">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={!writable}
                            onClick={() => addDraftLine(bucket)}
                          >
                            加入
                          </Button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      </SectionCard>

      <SectionCard title="调整单列表" description="真分页；行数与增/减调数量由明细现算。">
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <select
            aria-label="状态筛选"
            className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部状态</option>
            {Object.entries(statusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
          <select
            aria-label="原因码筛选"
            className="border-control-border bg-background h-9 rounded-md border px-2 text-sm"
            value={reasonFilter}
            onChange={(e) => {
              setReasonFilter(e.target.value);
              setPage(0);
            }}
          >
            <option value="">全部原因</option>
            {(capabilities?.reasons ?? []).map((r) => (
              <option key={r.value} value={r.value}>
                {r.label}
              </option>
            ))}
          </select>
          <Input
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPage(0);
            }}
            placeholder="单号 / 备注 / SKU / 库位 / 批次"
          />
        </div>

        {orders === null || orders.orders.length === 0 ? (
          <EmptyState
            icon={<ScrollText className="h-5 w-5" />}
            title={loading ? '加载中' : '还没有调整单'}
            description={
              loading
                ? '正在读取调整单。'
                : '调整必须走单据：从上面的「发起调整」建第一张草稿单。'
            }
          />
        ) : (
          <table className="w-full text-sm">
            <thead className="text-muted-foreground text-left text-xs">
              <tr>
                <th className="py-2">单号</th>
                <th className="py-2">状态</th>
                <th className="py-2">原因</th>
                <th className="py-2 text-right">行数</th>
                <th className="py-2 text-right">净调整</th>
                <th className="py-2">创建</th>
                <th className="py-2" />
              </tr>
            </thead>
            <tbody>
              {orders.orders.map((order: InventoryAdjustmentOrder) => (
                <tr key={order.id} className="border-control-border border-t">
                  <td className="py-2 font-medium">{order.code}</td>
                  <td className="py-2">
                    <span
                      className={
                        statusTone(order.status) === 'bad'
                          ? 'text-destructive'
                          : statusTone(order.status) === 'warn'
                            ? 'text-amber-600 dark:text-amber-400'
                            : 'text-emerald-600 dark:text-emerald-400'
                      }
                    >
                      {statusLabels[order.status] ?? order.status}
                    </span>
                  </td>
                  <td className="py-2">{order.reasonLabel || order.reasonCode}</td>
                  <td className="py-2 text-right tabular-nums">{order.lineCount}</td>
                  <td
                    className={
                      signedTone(order.netQty) === 'bad'
                        ? 'text-destructive py-2 text-right tabular-nums'
                        : 'py-2 text-right tabular-nums'
                    }
                  >
                    {signedQty(order.netQty)}
                  </td>
                  <td className="text-muted-foreground py-2 text-xs">
                    {formatMoment(order.createdAt)}
                  </td>
                  <td className="py-2 text-right">
                    <Button size="sm" variant="outline" onClick={() => void openOrder(order.id)}>
                      打开
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {orders && orders.total > orders.size && (
          <div className="mt-3 flex items-center gap-2">
            <Button
              size="sm"
              variant="outline"
              disabled={page === 0}
              onClick={() => setPage((p) => Math.max(0, p - 1))}
            >
              上一页
            </Button>
            <span className="text-muted-foreground text-xs">
              第 {page + 1} 页 · 共 {orders.total} 张
            </span>
            <Button
              size="sm"
              variant="outline"
              disabled={(page + 1) * orders.size >= orders.total}
              onClick={() => setPage((p) => p + 1)}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {open && (
        <SectionCard
          title={`调整单 ${open.order.code}`}
          description={`${statusLabels[open.order.status] ?? open.order.status} · ${open.order.lineCount} 行 · 净调整 ${signedQty(open.order.netQty)}`}
        >
          <div className="space-y-4">
            <div className="text-muted-foreground text-copy-13">
              {open.order.status === 'POSTED'
                ? '已过账：库存余额已改，分录已落进 7.1.2.1 库存流水台账。冲正请发起新的调整单。'
                : '未过账：库存余额**一个数都没动**。调整必须走单据 —— 过账之后才改账。'}
            </div>

            {open.order.sourceReference && (
              <div className="text-muted-foreground text-copy-13">
                上游来源：6.4.2.2 不良品处置单 {open.order.sourceReference}
              </div>
            )}

            <div className="flex flex-wrap items-center gap-2">
              {allowedActions(open.order.status).map((name) => (
                <Button
                  key={name}
                  size="sm"
                  variant={name === 'post' ? 'default' : 'outline'}
                  disabled={!writable || saving}
                  onClick={() => {
                    setAction(name as 'approve' | 'reject' | 'cancel' | 'post');
                    setActionNote('');
                    setError('');
                  }}
                >
                  {actionLabels[name]}
                </Button>
              ))}
              {allowedActions(open.order.status).length === 0 && (
                <span className="text-muted-foreground text-copy-13">
                  这张单已到终态，没有可执行的动作。冲正请发起新的调整单。
                </span>
              )}
            </div>

            {action && (
              <div className="border-control-border space-y-2 rounded-md border p-3">
                <div className="text-copy-13">
                  {actionLabels[action]}：{actionBalanceNotes[action]}
                </div>
                <FormField
                  id="action-note"
                  label="理由"
                  required={action === 'approve' || action === 'reject'}
                  hint="批准与驳回都必须写：只留一个状态，事后没人说得清批的是哪条判断。"
                >
                  <Input
                    id="action-note"
                    value={actionNote}
                    maxLength={500}
                    onChange={(e) => setActionNote(e.target.value)}
                  />
                </FormField>
                <div className="flex gap-2">
                  <Button size="sm" disabled={saving} onClick={() => void runAction(action)}>
                    确认{actionLabels[action]}
                  </Button>
                  <Button size="sm" variant="outline" onClick={() => setAction('')}>
                    取消
                  </Button>
                </div>
              </div>
            )}

            <div>
              <div className="text-label-13 text-foreground mb-2">明细行</div>
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left text-xs">
                  <tr>
                    <th className="py-2">商品 / 库位</th>
                    <th className="py-2 text-right">调整量</th>
                    <th className="py-2">原因</th>
                    <th className="py-2">状态</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {open.lines.map((line) => (
                    <tr key={line.id} className="border-control-border border-t">
                      <td className="py-2">
                        <div className="font-medium">
                          {line.skuCode} {line.skuName}
                        </div>
                        <div className="text-muted-foreground text-xs">
                          {line.warehouseCode}/{line.locationCode}
                          {line.lotCode ? ` · ${line.lotCode}` : ''} · {line.qualityStatusCode}
                        </div>
                      </td>
                      <td className="py-2 text-right tabular-nums">{signedQty(line.qtyDelta)}</td>
                      <td className="py-2">{line.reasonLabel || line.reasonCode}</td>
                      <td className="text-muted-foreground py-2 text-xs">
                        {lineStateText(line)}
                        {line.postedAt && (
                          <div>{formatMoment(line.postedAt)}</div>
                        )}
                      </td>
                      <td className="py-2 text-right">
                        {open.order.status === 'DRAFT' && (
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={!writable || saving}
                            onClick={async () => {
                              setSaving(true);
                              setError('');
                              try {
                                setOpen(
                                  await removeAdjustmentLine(open.order.id, line.id),
                                );
                                setNotice('已删行（只有草稿能删行 —— 已提交的行是证据）。');
                              } catch (e) {
                                setError(errorText(e));
                              } finally {
                                setSaving(false);
                              }
                            }}
                          >
                            删行
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {open.attachments.length > 0 && (
              <div>
                <div className="text-label-13 text-foreground mb-2">凭证</div>
                <ul className="space-y-1 text-copy-13">
                  {open.attachments.map((attachment) => (
                    <li key={attachment.id} className="flex items-center gap-2">
                      <span>{attachmentSummary(attachment)}</span>
                      <span className="text-muted-foreground text-xs">
                        {formatMoment(attachment.mountedAt)}
                      </span>
                      {open.order.status === 'DRAFT' && (
                        <button
                          type="button"
                          className="text-destructive text-xs underline"
                          onClick={async () => {
                            setSaving(true);
                            setError('');
                            try {
                              setOpen(
                                await removeAdjustmentEvidence(open.order.id, attachment.id),
                              );
                              setNotice('已摘凭证。');
                            } catch (e) {
                              setError(errorText(e));
                            } finally {
                              setSaving(false);
                            }
                          }}
                        >
                          摘
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              </div>
            )}

            {open.order.status === 'DRAFT' && (
              <div>
                <div className="text-label-13 text-foreground mb-2">补挂凭证</div>
                <p className="text-muted-foreground text-copy-13 mb-2">
                  凭证一旦随单提交就不可更换 —— 它是这次调整的依据。
                </p>
                <div className="flex flex-wrap items-end gap-2">
                  <FormField id="attach-media-id" label="媒体 ID">
                    <Input
                      id="attach-media-id"
                      value={attachMediaId}
                      onChange={(e) => setAttachMediaId(e.target.value)}
                      placeholder="catalog 媒体 id"
                    />
                  </FormField>
                  <FormField id="attach-file-name" label="文件名">
                    <Input
                      id="attach-file-name"
                      value={attachFileName}
                      onChange={(e) => setAttachFileName(e.target.value)}
                      placeholder="破损照片.jpg"
                    />
                  </FormField>
                  <FormField id="attach-media-type" label="类型">
                    <Input
                      id="attach-media-type"
                      value={attachMediaType}
                      onChange={(e) => setAttachMediaType(e.target.value)}
                      placeholder="image/jpeg"
                    />
                  </FormField>
                  <FormField id="attach-size" label="大小（字节）">
                    <Input
                      id="attach-size"
                      value={attachSizeBytes}
                      onChange={(e) => setAttachSizeBytes(e.target.value)}
                      placeholder="20480"
                    />
                  </FormField>
                  <Button
                    size="sm"
                    variant="outline"
                    disabled={!writable || saving}
                    onClick={async () => {
                      if (!attachMediaId.trim() || !attachFileName.trim()) {
                        setError('凭证附件必须带媒体 ID 与文件名。');
                        return;
                      }
                      setSaving(true);
                      setError('');
                      try {
                        setOpen(
                          await attachAdjustmentEvidence(open.order.id, {
                            mediaId: attachMediaId.trim(),
                            fileName: attachFileName.trim(),
                            mediaType: attachMediaType.trim() || null,
                            sizeBytes: attachSizeBytes.trim() ? Number(attachSizeBytes) : 0,
                          }),
                        );
                        setAttachMediaId('');
                        setAttachFileName('');
                        setAttachMediaType('');
                        setAttachSizeBytes('');
                        setNotice('已挂凭证。');
                      } catch (e) {
                        setError(errorText(e));
                      } finally {
                        setSaving(false);
                      }
                    }}
                  >
                    <Plus className="h-4 w-4" />
                    挂凭证
                  </Button>
                </div>
              </div>
            )}

            <div>
              <div className="text-label-13 text-foreground mb-2">审计时间线</div>
              <ul className="space-y-2 text-copy-13">
                {open.auditTrail.map((entry) => (
                  <li key={entry.id} className="border-control-border border-t pt-2">
                    <div className="font-medium">
                      {entry.actionLabel} · {formatMoment(entry.occurredAt)}
                    </div>
                    <div className="text-muted-foreground">{entry.detail}</div>
                  </li>
                ))}
              </ul>
            </div>
          </div>
        </SectionCard>
      )}
    </div>
  );
}
