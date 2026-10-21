'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  CircleAlert,
  FileCheck2,
  FileStack,
  Loader2,
  RefreshCw,
  ShieldCheck,
  TriangleAlert,
} from 'lucide-react';
import {
  addSignatory,
  batchSummary,
  CREDENTIAL_TYPES,
  createCredential,
  digestPreview,
  emptyState,
  EXPIRY_FILTERS,
  expiryStateLabel,
  expiryStateTone,
  fetchAuthorizationOptions,
  fetchCapabilities,
  fetchCredential,
  fetchCredentials,
  fetchOverview,
  fetchVerifications,
  fileSizeText,
  removeSignatory,
  revokeCredential,
  STATUS_FILTERS,
  updateCredential,
  uploadCredentialDocument,
  verificationStateLabel,
  verificationStateTone,
  verificationSummary,
  VERIFICATION_FILTERS,
  verifyCredential,
  verifyCredentialBatch,
  type BrandCredentialAuthorizationOption,
  type BrandCredentialCapabilities,
  type BrandCredentialDetail,
  type BrandCredentialOverview,
  type BrandCredentialPage,
  type BrandCredentialRecord,
  type BrandCredentialVerificationPage,
  type BrandCredentialVerificationResult,
} from './brand-credential-model';

/**
 * 16.4.2.2 授权凭证 工作区。
 *
 * 蓝图：「集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。
 * 输入：凭证上传；核验操作。输出：凭证档案 + 核验记录。
 * 关系：上游 = 16.4.2.1；下游 = 16.4.1.2。**凭证需保留原始文件与元数据**，争议时是唯一证据。」
 *
 * 🔴 页面上的五条纪律：
 *
 * 1. **「从未核验」与「核验未通过」分开展示** —— 把「还没查」显示成「假的」是误伤，
 *    把「假的」显示成「还没查」是放过。概览里三个数也是分开的。
 * 2. **核验结论原样显示后端算的**（`conclusion` / `severity` / `reasons`），前端不重算、不编原因。
 * 3. **「通过但有提醒」不是「不通过」**：WARN 级原因用提醒色，不用红字。
 * 4. **文件清单显示 sha256 摘要** —— 摘要才是真伪核验的锚点，争议时这就是证据。
 * 5. **候选读不到就说读不到**（16.4.2.1 的授权档案读不到时），不显示成「暂无候选」。
 */
const PAGE_SIZE = 10;

interface FormState {
  authorizationId: string;
  grantor: string;
  credentialNo: string;
  credentialType: string;
  subject: string;
  brandName: string;
  validFrom: string;
  validUntil: string;
  issuedOn: string;
  note: string;
}

interface SignatoryForm {
  signatoryName: string;
  signatoryTitle: string;
  signatoryOrg: string;
  contact: string;
  identityVerified: boolean;
  note: string;
}

const EMPTY_FORM: FormState = {
  authorizationId: '',
  grantor: '',
  credentialNo: '',
  credentialType: 'BRAND_LICENSE',
  subject: '',
  brandName: '',
  validFrom: '',
  validUntil: '',
  issuedOn: '',
  note: '',
};

const EMPTY_SIGNATORY: SignatoryForm = {
  signatoryName: '',
  signatoryTitle: '',
  signatoryOrg: '',
  contact: '',
  identityVerified: false,
  note: '',
};

/** 日期输入（YYYY-MM-DD）→ 后端要的 ISO 串（UTC 当日 00:00 / 当日 23:59:59.999）。 */
function toIsoStart(value: string): string {
  return value ? `${value}T00:00:00Z` : '';
}

function toIsoEnd(value: string): string {
  return value ? `${value}T23:59:59Z` : '';
}

export function BrandCredentialWorkspace() {
  const [capabilities, setCapabilities] = useState<BrandCredentialCapabilities | null>(null);
  const [overview, setOverview] = useState<BrandCredentialOverview | null>(null);
  const [page, setPage] = useState<BrandCredentialPage | null>(null);
  const [verifications, setVerifications] = useState<BrandCredentialVerificationPage | null>(null);
  const [detail, setDetail] = useState<BrandCredentialDetail | null>(null);
  const [options, setOptions] = useState<BrandCredentialAuthorizationOption[]>([]);
  const [optionsNote, setOptionsNote] = useState('');

  const [keyword, setKeyword] = useState('');
  const [status, setStatus] = useState('');
  const [expiryState, setExpiryState] = useState('');
  const [verificationState, setVerificationState] = useState('');
  const [pageIndex, setPageIndex] = useState(0);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [lastResult, setLastResult] = useState<BrandCredentialVerificationResult | null>(null);
  const [batchNotice, setBatchNotice] = useState('');

  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<BrandCredentialRecord | null>(null);
  const [form, setForm] = useState<FormState>(EMPTY_FORM);
  const [signatoryOpen, setSignatoryOpen] = useState(false);
  const [signatory, setSignatory] = useState<SignatoryForm>(EMPTY_SIGNATORY);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [caps, stats, list, history] = await Promise.all([
        fetchCapabilities(),
        fetchOverview(),
        fetchCredentials(pageIndex, PAGE_SIZE, {
          q: keyword || undefined,
          status: status || undefined,
          expiryState: expiryState || undefined,
          verificationState: verificationState || undefined,
        }),
        fetchVerifications(0, PAGE_SIZE),
      ]);
      setCapabilities(caps);
      setOverview(stats);
      setPage(list);
      setVerifications(history);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [keyword, status, expiryState, verificationState, pageIndex]);

  useEffect(() => {
    void load();
  }, [load]);

  // 上游 16.4.2.1 的授权候选（同库只读）。读不到时把原因显示出来，不显示成「暂无候选」。
  const loadOptions = useCallback(async () => {
    try {
      const result = await fetchAuthorizationOptions();
      setOptions(result.items);
      setOptionsNote(result.note);
    } catch (e) {
      setOptions([]);
      setOptionsNote(e instanceof Error ? e.message : '上游授权档案读不到');
    }
  }, []);

  useEffect(() => {
    void loadOptions();
  }, [loadOptions]);

  const openDetail = useCallback(async (id: string) => {
    setError('');
    try {
      setDetail(await fetchCredential(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取凭证详情失败');
    }
  }, []);

  const openCreate = useCallback(() => {
    setEditing(null);
    setForm(EMPTY_FORM);
    setFormOpen(true);
  }, []);

  const openEdit = useCallback((record: BrandCredentialRecord) => {
    setEditing(record);
    setForm({
      authorizationId: record.authorizationId ?? '',
      grantor: record.grantor,
      credentialNo: record.credentialNo,
      credentialType: record.credentialType,
      subject: record.subject,
      brandName: record.brandName,
      validFrom: record.validFrom.slice(0, 10),
      validUntil: record.validUntil.slice(0, 10),
      issuedOn: record.issuedOn ?? '',
      note: record.note,
    });
    setFormOpen(true);
  }, []);

  const submitForm = useCallback(async () => {
    setBusy(true);
    setError('');
    try {
      const input = {
        authorizationId: form.authorizationId || null,
        grantor: form.grantor,
        credentialNo: form.credentialNo,
        credentialType: form.credentialType,
        subject: form.subject,
        brandId: null,
        brandName: form.brandName,
        validFrom: toIsoStart(form.validFrom),
        validUntil: toIsoEnd(form.validUntil),
        issuedOn: form.issuedOn || null,
        note: form.note,
      };
      if (editing) {
        await updateCredential(editing.credentialId, input);
        setNotice('凭证已更新（文件版本与核验记录不受影响 —— 那是追加式凭据）');
      } else {
        await createCredential(input);
        setNotice('凭证已登记');
      }
      setFormOpen(false);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败，请检查输入后重试');
    } finally {
      setBusy(false);
    }
  }, [editing, form, load]);

  const doRevoke = useCallback(
    async (id: string) => {
      const reason = window.prompt('撤销凭证必须写明理由（撤销是终态，没有「恢复」）：');
      if (!reason || !reason.trim()) return;
      setBusy(true);
      setError('');
      try {
        await revokeCredential(id, reason.trim());
        setNotice('凭证已撤销');
        await load();
        if (detail?.record.credentialId === id) await openDetail(id);
      } catch (e) {
        setError(e instanceof Error ? e.message : '撤销失败');
      } finally {
        setBusy(false);
      }
    },
    [detail, load, openDetail],
  );

  const doUpload = useCallback(
    async (credentialId: string, file: File) => {
      setBusy(true);
      setError('');
      try {
        const buffer = await file.arrayBuffer();
        const bytes = new Uint8Array(buffer);
        // 🔴 分块转 base64：`String.fromCharCode(...bytes)` 一次传太多会爆参数上限；
        //    逐字节 `bytes[i]` 在 `noUncheckedIndexedAccess` 下是 `number | undefined`，
        //    所以用 subarray + 展开（拿到的是 number[]，不是索引取值）。
        let binary = '';
        const CHUNK = 0x8000;
        for (let i = 0; i < bytes.length; i += CHUNK) {
          binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
        }
        await uploadCredentialDocument(credentialId, {
          fileName: file.name,
          contentType: file.type || 'application/octet-stream',
          contentBase64: btoa(binary),
          note: '原件上传',
        });
        setNotice('文件已上传（新版本 —— 文件行只追加，原来交的是什么不会消失）');
        await load();
        await openDetail(credentialId);
      } catch (e) {
        setError(e instanceof Error ? e.message : '上传失败');
      } finally {
        setBusy(false);
      }
    },
    [load, openDetail],
  );

  const doVerify = useCallback(
    async (credentialId: string) => {
      setBusy(true);
      setError('');
      setBatchNotice('');
      try {
        const result = await verifyCredential(credentialId);
        setLastResult(result);
        setNotice(verificationSummary(result));
        await load();
        await openDetail(credentialId);
      } catch (e) {
        setError(e instanceof Error ? e.message : '核验失败');
      } finally {
        setBusy(false);
      }
    },
    [load, openDetail],
  );

  const doVerifyAll = useCallback(async () => {
    const ids = page?.items.map((item) => item.credentialId) ?? [];
    if (ids.length === 0) return;
    setBusy(true);
    setError('');
    try {
      const result = await verifyCredentialBatch(ids);
      setBatchNotice(batchSummary(result));
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '批量核验失败');
    } finally {
      setBusy(false);
    }
  }, [load, page]);

  const submitSignatory = useCallback(async () => {
    if (!detail) return;
    setBusy(true);
    setError('');
    try {
      const updated = await addSignatory(detail.record.credentialId, signatory);
      setDetail(updated);
      setSignatory(EMPTY_SIGNATORY);
      setSignatoryOpen(false);
      setNotice('签署方已登记');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记签署方失败');
    } finally {
      setBusy(false);
    }
  }, [detail, load, signatory]);

  const doRemoveSignatory = useCallback(
    async (signatoryId: string) => {
      if (!detail) return;
      setBusy(true);
      setError('');
      try {
        const updated = await removeSignatory(detail.record.credentialId, signatoryId);
        setDetail(updated);
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : '移除签署方失败');
      } finally {
        setBusy(false);
      }
    },
    [detail, load],
  );

  const noCredential = useMemo(() => emptyState('NO_CREDENTIAL'), []);
  const noVerification = useMemo(() => emptyState('NO_VERIFICATION'), []);
  const noDocument = useMemo(() => emptyState('NO_DOCUMENT'), []);

  return (
    <div className="space-y-4">
      <PageHeader
        title="授权凭证"
        description="集中存储与核验授权凭证文件：真伪验证记录、文件版本、签署方信息，支持一键核验。凭证需保留原始文件与元数据，争议时是唯一证据。"
        actions={
          <div className="flex gap-2">
            <Button onClick={() => void load()} disabled={loading} variant="ghost">
              {loading ? (
                <Loader2 className="size-4 animate-spin" />
              ) : (
                <RefreshCw className="size-4" />
              )}
              刷新
            </Button>
            <Button onClick={openCreate}>登记凭证</Button>
          </div>
        }
      />

      {error ? (
        <div className="border-destructive/40 bg-destructive/5 flex items-start gap-2 rounded-md border p-3 text-sm">
          <CircleAlert className="text-destructive mt-0.5 size-4 shrink-0" />
          <span>{error}</span>
        </div>
      ) : null}
      {notice ? (
        <div className="border-border bg-muted/40 flex items-start gap-2 rounded-md border p-3 text-sm">
          <ShieldCheck className="mt-0.5 size-4 shrink-0" />
          <span>{notice}</span>
        </div>
      ) : null}
      {batchNotice ? (
        <div className="border-border bg-muted/40 flex items-start gap-2 rounded-md border p-3 text-sm">
          <FileCheck2 className="mt-0.5 size-4 shrink-0" />
          <span>{batchNotice}</span>
        </div>
      ) : null}

      {/* 🔴 概览：三个核验状态分开显示（「从未核验」不是「核验未通过」） */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="凭证总数"
          value={overview?.credentialCount ?? 0}
          comparisonLabel={`文件版本 ${overview?.documentCount ?? 0} 个`}
        />
        <StatCard
          label="核验通过"
          value={overview?.verifiedCount ?? 0}
          comparisonLabel="最近一次核验结论为通过"
        />
        <StatCard
          label="核验未通过"
          value={overview?.failedCount ?? 0}
          comparisonLabel="最近一次核验有未通过项"
        />
        <StatCard
          label="从未核验"
          value={overview?.unverifiedCount ?? 0}
          comparisonLabel="🔴 还没核验 ≠ 核验不通过"
        />
      </div>
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="生效中"
          value={overview?.activeCount ?? 0}
          comparisonLabel="在有效期内且未撤销"
        />
        <StatCard
          label="即将到期"
          value={overview?.expiringCount ?? 0}
          comparisonLabel="30 天内到期"
        />
        <StatCard label="已过期" value={overview?.expiredCount ?? 0} comparisonLabel="有效期已过" />
        <StatCard label="已撤销" value={overview?.revokedCount ?? 0} comparisonLabel="撤销是终态" />
      </div>

      {/* ---- 凭证档案台账 ---- */}
      <SectionCard
        title="凭证档案"
        description="真分页 + 真筛选。凭证是「争议时是唯一证据」的那份东西，所以这里没有删除。"
      >
        <div className="flex flex-wrap items-end gap-2">
          <Input
            placeholder="搜索凭证编号 / 授权方 / 被授权主体"
            value={keyword}
            onChange={(e) => {
              setKeyword(e.target.value);
              setPageIndex(0);
            }}
          />
          <select
            className="border-input bg-background h-9 rounded-md border px-2 text-sm"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value);
              setPageIndex(0);
            }}
          >
            {STATUS_FILTERS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background h-9 rounded-md border px-2 text-sm"
            value={expiryState}
            onChange={(e) => {
              setExpiryState(e.target.value);
              setPageIndex(0);
            }}
          >
            {EXPIRY_FILTERS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background h-9 rounded-md border px-2 text-sm"
            value={verificationState}
            onChange={(e) => {
              setVerificationState(e.target.value);
              setPageIndex(0);
            }}
          >
            {VERIFICATION_FILTERS.map((item) => (
              <option key={item.value} value={item.value}>
                {item.label}
              </option>
            ))}
          </select>
          <Button
            onClick={() => void doVerifyAll()}
            disabled={busy || !page?.items.length}
            variant="secondary"
          >
            <FileCheck2 className="size-4" />
            一键核验本页
          </Button>
        </div>

        {loading ? (
          <div className="text-muted-foreground flex items-center gap-2 py-6 text-sm">
            <Loader2 className="size-4 animate-spin" />
            加载中…
          </div>
        ) : page && page.items.length === 0 ? (
          <EmptyState title={noCredential.title} description={noCredential.description} />
        ) : (
          <div className="space-y-2">
            {page?.items.map((item) => (
              <div
                key={item.credentialId}
                className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border p-3"
              >
                <div className="min-w-0 space-y-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium">{item.credentialNo}</span>
                    <Badge tone="neutral">{item.credentialTypeLabel}</Badge>
                    <Badge tone={expiryStateTone(item.expiryState)}>
                      {expiryStateLabel(item.expiryState)}
                    </Badge>
                    {/* 🔴 「从未核验」与「核验未通过」是两个徽章，不共用一个 */}
                    <Badge tone={verificationStateTone(item.verificationState)}>
                      {verificationStateLabel(item.verificationState)}
                    </Badge>
                  </div>
                  <div className="text-muted-foreground text-sm">
                    {item.grantor} → {item.subject || '（未填被授权主体）'}
                    {item.brandName ? ` · ${item.brandName}` : ''}
                  </div>
                  <div className="text-muted-foreground text-xs">
                    文件版本 {item.currentVersion} · 签署方 {item.signatoryCount} ·
                    {item.lastVerifiedAt
                      ? ` 最近核验 ${item.lastVerifiedAt.slice(0, 10)}`
                      : ' 从未核验'}
                  </div>
                </div>
                <div className="flex gap-2">
                  <Button variant="ghost" onClick={() => void openDetail(item.credentialId)}>
                    详情
                  </Button>
                  <Button
                    variant="secondary"
                    disabled={busy}
                    onClick={() => void doVerify(item.credentialId)}
                  >
                    <FileCheck2 className="size-4" />
                    一键核验
                  </Button>
                </div>
              </div>
            ))}
            <div className="text-muted-foreground flex items-center justify-between pt-1 text-sm">
              <span>
                共 {page?.total ?? 0} 条 · 第 {pageIndex + 1} 页
              </span>
              <div className="flex gap-2">
                <Button
                  variant="ghost"
                  disabled={pageIndex === 0}
                  onClick={() => setPageIndex((v) => Math.max(0, v - 1))}
                >
                  上一页
                </Button>
                <Button
                  variant="ghost"
                  disabled={!page || pageIndex * PAGE_SIZE + PAGE_SIZE >= page.total}
                  onClick={() => setPageIndex((v) => v + 1)}
                >
                  下一页
                </Button>
              </div>
            </div>
          </div>
        )}
      </SectionCard>

      {/* ---- 核验记录（追加式凭据） ---- */}
      <SectionCard
        title="核验记录"
        description="每条记录都是不可改写的凭据：什么时候、谁、按哪一版规则、把第几版文件读回来重算 sha256、结论是什么。"
      >
        {verifications && verifications.items.length === 0 ? (
          <EmptyState title={noVerification.title} description={noVerification.description} />
        ) : (
          <div className="space-y-2">
            {verifications?.items.map((item) => (
              <div
                key={item.verificationId}
                className="border-border rounded-md border p-3 text-sm"
              >
                <div className="flex flex-wrap items-center gap-2">
                  <span className="font-medium">{item.credentialNo}</span>
                  <Badge tone={item.passed ? 'success' : 'danger'}>{item.conclusionLabel}</Badge>
                  <span className="text-muted-foreground text-xs">
                    第 {item.documentVersionNo} 版 · 规则 v{item.ruleVersion} ·{' '}
                    {item.evaluatedAt.slice(0, 19).replace('T', ' ')}
                  </span>
                </div>
                {/* 🔴 原因原样显示，不通过时必带原因 */}
                {item.reasons.map((reason) => (
                  <div
                    key={`${item.verificationId}-${reason.code}`}
                    className="mt-1 flex items-start gap-2 text-xs"
                  >
                    {reason.severity === 'WARN' ? (
                      <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-amber-600" />
                    ) : (
                      <CircleAlert className="text-destructive mt-0.5 size-3.5 shrink-0" />
                    )}
                    <span
                      className={reason.severity === 'WARN' ? 'text-amber-700' : 'text-destructive'}
                    >
                      {reason.label}：{reason.message}
                    </span>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </SectionCard>

      {/* ---- 凭证详情抽屉 ---- */}
      <Modal
        open={detail !== null}
        onOpenChange={(open) => {
          if (!open) setDetail(null);
        }}
      >
        <ModalContent className="max-w-3xl">
          <ModalHeader>
            <ModalTitle>
              {detail ? `${detail.record.credentialNo} · ${detail.record.grantor}` : ''}
            </ModalTitle>
            <ModalDescription>
              🔴 文件本体在受管存储里（不进业务库）；这里显示的是元数据与 sha256 摘要 ——
              摘要才是真伪核验的锚点，争议时这就是证据。
            </ModalDescription>
          </ModalHeader>

          {detail ? (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={expiryStateTone(detail.record.expiryState)}>
                  {expiryStateLabel(detail.record.expiryState)}
                </Badge>
                <Badge tone={verificationStateTone(detail.record.verificationState)}>
                  {verificationStateLabel(detail.record.verificationState)}
                </Badge>
                <Badge tone="neutral">{detail.record.credentialTypeLabel}</Badge>
                {detail.record.status === 'REVOKED' ? <Badge tone="danger">已撤销</Badge> : null}
              </div>
              <dl className="grid grid-cols-2 gap-2 text-sm">
                <dt className="text-muted-foreground">被授权主体</dt>
                <dd>{detail.record.subject || '—'}</dd>
                <dt className="text-muted-foreground">有效期</dt>
                <dd>
                  {detail.record.validFrom.slice(0, 10)} ~ {detail.record.validUntil.slice(0, 10)}
                </dd>
                <dt className="text-muted-foreground">关联品牌</dt>
                <dd>{detail.record.brandName || '—'}</dd>
                <dt className="text-muted-foreground">上游授权（16.4.2.1）</dt>
                <dd className="break-all">{detail.record.authorizationId ?? '—'}</dd>
                {detail.record.revokedReason ? (
                  <>
                    <dt className="text-muted-foreground">撤销理由</dt>
                    <dd>{detail.record.revokedReason}</dd>
                  </>
                ) : null}
              </dl>

              {/* 文件版本 */}
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-medium">凭证文件版本（只追加）</h3>
                  <label className="inline-flex cursor-pointer items-center gap-2 text-xs">
                    <input
                      type="file"
                      className="hidden"
                      onChange={(e) => {
                        const file = e.target.files?.[0];
                        if (file) void doUpload(detail.record.credentialId, file);
                        e.target.value = '';
                      }}
                    />
                    <span className="border-input rounded-md border px-2 py-1">上传新版本</span>
                  </label>
                </div>
                {detail.documents.length === 0 ? (
                  <EmptyState title={noDocument.title} description={noDocument.description} />
                ) : (
                  <div className="space-y-1">
                    {detail.documents.map((doc) => (
                      <div
                        key={doc.documentId}
                        className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border p-2 text-sm"
                      >
                        <div className="flex items-center gap-2">
                          <FileStack className="size-4" />
                          <span>
                            第 {doc.versionNo} 版 · {doc.fileName}
                          </span>
                        </div>
                        <div className="text-muted-foreground text-xs">
                          {fileSizeText(doc.sizeBytes)} · sha256 {digestPreview(doc.sha256)}
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* 签署方 */}
              <div className="space-y-2">
                <div className="flex items-center justify-between">
                  <h3 className="text-sm font-medium">签署方</h3>
                  <Button variant="ghost" onClick={() => setSignatoryOpen(true)}>
                    登记签署方
                  </Button>
                </div>
                {detail.signatories.length === 0 ? (
                  <p className="text-muted-foreground text-sm">
                    还没有登记签署方 —— 核验会因此判为「没有登记签署方」（不通过）。
                  </p>
                ) : (
                  <div className="space-y-1">
                    {detail.signatories.map((item) => (
                      <div
                        key={item.signatoryId}
                        className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border p-2 text-sm"
                      >
                        <span>
                          {item.signatoryName}
                          {item.signatoryTitle ? ` · ${item.signatoryTitle}` : ''}
                          {item.signatoryOrg ? ` · ${item.signatoryOrg}` : ''}
                        </span>
                        <div className="flex items-center gap-2">
                          <Badge tone={item.identityVerified ? 'success' : 'warning'}>
                            {item.identityVerified ? '身份已核验' : '身份未核验'}
                          </Badge>
                          <Button
                            variant="ghost"
                            onClick={() => void doRemoveSignatory(item.signatoryId)}
                          >
                            移除
                          </Button>
                        </div>
                      </div>
                    ))}
                  </div>
                )}
              </div>

              {/* 核验记录 */}
              <div className="space-y-2">
                <h3 className="text-sm font-medium">核验记录</h3>
                {detail.verifications.length === 0 ? (
                  <EmptyState
                    title={noVerification.title}
                    description={noVerification.description}
                  />
                ) : (
                  <div className="space-y-1">
                    {detail.verifications.map((item) => (
                      <div
                        key={item.verificationId}
                        className="border-border rounded-md border p-2 text-sm"
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge tone={item.passed ? 'success' : 'danger'}>
                            {item.conclusionLabel}
                          </Badge>
                          <span className="text-muted-foreground text-xs">
                            第 {item.documentVersionNo} 版 · 规则 v{item.ruleVersion} ·{' '}
                            {item.evaluatedAt.slice(0, 19).replace('T', ' ')}
                          </span>
                        </div>
                        {item.reasons.map((reason) => (
                          <div
                            key={`${item.verificationId}-${reason.code}`}
                            className="mt-1 flex items-start gap-2 text-xs"
                          >
                            {reason.severity === 'WARN' ? (
                              <TriangleAlert className="mt-0.5 size-3.5 shrink-0 text-amber-600" />
                            ) : (
                              <CircleAlert className="text-destructive mt-0.5 size-3.5 shrink-0" />
                            )}
                            <span
                              className={
                                reason.severity === 'WARN' ? 'text-amber-700' : 'text-destructive'
                              }
                            >
                              {reason.label}：{reason.message}
                            </span>
                          </div>
                        ))}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          ) : null}

          <ModalFooter>
            {detail && detail.record.status !== 'REVOKED' ? (
              <>
                <Button
                  variant="secondary"
                  disabled={busy}
                  onClick={() => void doVerify(detail.record.credentialId)}
                >
                  <FileCheck2 className="size-4" />
                  一键核验
                </Button>
                <Button variant="ghost" onClick={() => openEdit(detail.record)}>
                  修改
                </Button>
                <Button
                  variant="destructive"
                  disabled={busy}
                  onClick={() => void doRevoke(detail.record.credentialId)}
                >
                  撤销
                </Button>
              </>
            ) : null}
            <Button variant="ghost" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 登记 / 修改凭证 ---- */}
      <Modal open={formOpen} onOpenChange={setFormOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{editing ? '修改凭证' : '登记凭证'}</ModalTitle>
            <ModalDescription>
              🔴 凭证档案与上游 16.4.2.1 的授权用**逻辑 id**关联（不建外键）——
              凭证是争议时的唯一证据，必须活得比它引用的授权更久。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">授权方 *</span>
              <Input
                value={form.grantor}
                onChange={(e) => setForm({ ...form, grantor: e.target.value })}
              />
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">凭证编号 *</span>
              <Input
                value={form.credentialNo}
                onChange={(e) => setForm({ ...form, credentialNo: e.target.value })}
              />
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">凭证类型 *</span>
              <select
                className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
                value={form.credentialType}
                onChange={(e) => setForm({ ...form, credentialType: e.target.value })}
              >
                {CREDENTIAL_TYPES.map((item) => (
                  <option key={item.value} value={item.value}>
                    {item.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">被授权主体</span>
              <Input
                value={form.subject}
                onChange={(e) => setForm({ ...form, subject: e.target.value })}
              />
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">关联品牌</span>
              <Input
                value={form.brandName}
                onChange={(e) => setForm({ ...form, brandName: e.target.value })}
              />
            </label>
            <div className="grid grid-cols-2 gap-2">
              <label className="block space-y-1 text-sm">
                <span className="text-muted-foreground">有效期起 *</span>
                <Input
                  type="date"
                  value={form.validFrom}
                  onChange={(e) => setForm({ ...form, validFrom: e.target.value })}
                />
              </label>
              <label className="block space-y-1 text-sm">
                <span className="text-muted-foreground">有效期止 *</span>
                <Input
                  type="date"
                  value={form.validUntil}
                  onChange={(e) => setForm({ ...form, validUntil: e.target.value })}
                />
              </label>
            </div>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">签发日</span>
              <Input
                type="date"
                value={form.issuedOn}
                onChange={(e) => setForm({ ...form, issuedOn: e.target.value })}
              />
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">关联上游授权（16.4.2.1，可空）</span>
              <select
                className="border-input bg-background h-9 w-full rounded-md border px-2 text-sm"
                value={form.authorizationId}
                onChange={(e) => setForm({ ...form, authorizationId: e.target.value })}
              >
                <option value="">（不关联）</option>
                {options.map((option) => (
                  <option key={option.authorizationId} value={option.authorizationId}>
                    {option.grantor} · {option.brandName || option.licenseeName}
                  </option>
                ))}
              </select>
              {options.length === 0 && optionsNote ? (
                <span className="text-muted-foreground text-xs">{optionsNote}</span>
              ) : null}
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">备注</span>
              <Input
                value={form.note}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
              />
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setFormOpen(false)}>
              取消
            </Button>
            <Button disabled={busy} onClick={() => void submitForm()}>
              {busy ? <Loader2 className="size-4 animate-spin" /> : null}
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 登记签署方 ---- */}
      <Modal open={signatoryOpen} onOpenChange={setSignatoryOpen}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>登记签署方</ModalTitle>
            <ModalDescription>
              🔴 「身份已核验」是**提醒级**的事：没核验过身份**不**让核验不通过
              （否则一键核验天天红），但一个签署方都没登记**是**不通过。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">签署人姓名 *</span>
              <Input
                value={signatory.signatoryName}
                onChange={(e) => setSignatory({ ...signatory, signatoryName: e.target.value })}
              />
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">职务</span>
              <Input
                value={signatory.signatoryTitle}
                onChange={(e) => setSignatory({ ...signatory, signatoryTitle: e.target.value })}
              />
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">所属组织</span>
              <Input
                value={signatory.signatoryOrg}
                onChange={(e) => setSignatory({ ...signatory, signatoryOrg: e.target.value })}
              />
            </label>
            <label className="block space-y-1 text-sm">
              <span className="text-muted-foreground">联系方式</span>
              <Input
                value={signatory.contact}
                onChange={(e) => setSignatory({ ...signatory, contact: e.target.value })}
              />
            </label>
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={signatory.identityVerified}
                onChange={(e) => setSignatory({ ...signatory, identityVerified: e.target.checked })}
              />
              签署方身份已人工核验
            </label>
          </div>
          <ModalFooter>
            <Button variant="ghost" onClick={() => setSignatoryOpen(false)}>
              取消
            </Button>
            <Button disabled={busy} onClick={() => void submitSignatory()}>
              保存
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {capabilities ? (
        <SectionCard title="口径说明" description="后端算出来的结论，前端原样显示，不重算。">
          <dl className="space-y-2 text-sm">
            <div>
              <dt className="font-medium">真伪怎么判</dt>
              <dd className="text-muted-foreground">{capabilities.digestSemantics}</dd>
            </div>
            <div>
              <dt className="font-medium">结论口径</dt>
              <dd className="text-muted-foreground">{capabilities.verificationSemantics}</dd>
            </div>
            <div>
              <dt className="font-medium">追加式纪律</dt>
              <dd className="text-muted-foreground">{capabilities.appendOnlySemantics}</dd>
            </div>
            <div>
              <dt className="font-medium">与 16.4.2.1 的边界</dt>
              <dd className="text-muted-foreground">{capabilities.boundaryNote}</dd>
            </div>
            <div>
              <dt className="font-medium">上游</dt>
              <dd className="text-muted-foreground">{capabilities.upstreamNote}</dd>
            </div>
            <div>
              <dt className="font-medium">下游</dt>
              <dd className="text-muted-foreground">{capabilities.downstreamNote}</dd>
            </div>
          </dl>
        </SectionCard>
      ) : null}
    </div>
  );
}
