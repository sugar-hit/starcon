'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertTriangle, FileUp, Plus, RefreshCw, ShieldCheck } from 'lucide-react';
import {
  Badge,
  Button,
  EmptyState,
  FormField,
  Input,
  Notice,
  PageHeader,
  SectionCard,
  StatCard,
  Tabs,
  TabsContent,
  TabsList,
  TabsTrigger,
} from '@starcon/ui';

import {
  EMPTY_QUALIFICATION_FILTERS,
  EMPTY_REMINDER_FILTERS,
  createQualification,
  dateText,
  emptyDocumentForm,
  emptyQualificationForm,
  expiryText,
  expiryTone,
  fetchQualification,
  fetchQualificationBrands,
  fetchQualificationCapabilities,
  fetchQualificationOptions,
  fetchQualificationOverview,
  fetchQualificationPolicy,
  fetchQualifications,
  fetchQualificationSuppliers,
  fetchReminders,
  fetchSkuBlockingDecision,
  fetchSupplierBlockingDecision,
  fetchSupplierBrandBlockingDecision,
  fileSizeText,
  handleReminder,
  instantText,
  reminderStatusTone,
  reminderTone,
  reviewTone,
  reviewQualification,
  scanReminders,
  scopeLabel,
  uploadQualificationDocument,
  validateDocument,
  validateQualification,
  validateReview,
  validityText,
} from './qualifications-model';
import type {
  BlockingDecision,
  QualificationBrandCandidateRow,
  QualificationDetail,
  QualificationDocumentInput,
  QualificationInput,
  QualificationOverview,
  QualificationPage,
  QualificationPolicyRow,
  QualificationReminderPage,
  QualificationReviewInput,
  QualificationRow,
  QualificationSupplierCandidateRow,
  SupplierQualificationCapabilities,
  SupplierQualificationOptions,
} from './qualifications-model';

/**
 * 6.1.2.3 供应商资质。
 *
 * <p>🔴 页面顶部那句话不是提示语，是这一页的设计前提：**资质过期必须阻断相关商品的刊登与发货，
 * 不能只做提醒**。所以这一页上：
 *
 * <ul>
 *   <li>「即将过期」与「已过期」是**两个不同的状态**：不同的颜色、不同的措辞 ——
 *       前者还在有效期内（该去续期），后者已经越界（该停下来）；
 *   <li>提醒与阻断是**两件事**：提醒清单回答「谁被提醒了、有没有人处理」，
 *       阻断判定回答「现在能不能刊登 / 发货」，两者各有各的位置，不合并成一个红点；
 *   <li>到期状态**不按浏览器时钟重算**：后端按数据库时钟现算，界面原样显示 ——
 *       浏览器时钟漂移会让「这张证过期没有」在两台机器上答案不同。
 * </ul>
 *
 * <p>与 6.1.2.1 档口与货源池共用 L3「供应」入口，但**各占一个独立分区**：
 * 那边是外部货源渠道的档口与货源池，这边是供应商的资质档案与阻断判定。
 */
export function QualificationWorkspace() {
  const [options, setOptions] = useState<SupplierQualificationOptions | null>(null);
  const [overview, setOverview] = useState<QualificationOverview | null>(null);
  const [capabilities, setCapabilities] = useState<SupplierQualificationCapabilities | null>(null);
  const [policy, setPolicy] = useState<QualificationPolicyRow | null>(null);
  const [page, setPage] = useState<QualificationPage | null>(null);
  const [filters, setFilters] = useState(EMPTY_QUALIFICATION_FILTERS);
  const [reminders, setReminders] = useState<QualificationReminderPage | null>(null);
  const [reminderFilters, setReminderFilters] = useState(EMPTY_REMINDER_FILTERS);
  const [detail, setDetail] = useState<QualificationDetail | null>(null);
  const [suppliers, setSuppliers] = useState<QualificationSupplierCandidateRow[]>([]);
  const [brands, setBrands] = useState<QualificationBrandCandidateRow[]>([]);
  const [form, setForm] = useState<QualificationInput | null>(null);
  const [documentForm, setDocumentForm] = useState<QualificationDocumentInput | null>(null);
  const [reviewAction, setReviewAction] = useState<QualificationReviewInput>({
    action: 'SUBMIT',
    reason: '',
  });
  const [decision, setDecision] = useState<BlockingDecision | null>(null);
  const [decisionScope, setDecisionScope] = useState<'SUPPLIER' | 'BRAND' | 'SKU'>('SUPPLIER');
  const [decisionTarget, setDecisionTarget] = useState('');
  const [decisionBrand, setDecisionBrand] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [opts, ov, caps, pol, list, rem] = await Promise.all([
        fetchQualificationOptions(),
        fetchQualificationOverview(),
        fetchQualificationCapabilities(),
        fetchQualificationPolicy(),
        fetchQualifications(filters),
        fetchReminders(reminderFilters),
      ]);
      setOptions(opts);
      setOverview(ov);
      setCapabilities(caps);
      setPolicy(pol);
      setPage(list);
      setReminders(rem);
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取供应商资质失败，请稍后重试');
    } finally {
      setLoading(false);
    }
  }, [filters, reminderFilters]);

  useEffect(() => {
    void load();
  }, [load]);

  const loadCandidates = useCallback(async () => {
    try {
      const [sup, brd] = await Promise.all([
        fetchQualificationSuppliers(),
        fetchQualificationBrands(),
      ]);
      setSuppliers(sup);
      setBrands(brd);
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取供应商 / 品牌候选失败');
    }
  }, []);

  useEffect(() => {
    void loadCandidates();
  }, [loadCandidates]);

  const openDetail = useCallback(async (id: string) => {
    setError('');
    try {
      setDetail(await fetchQualification(id));
    } catch (e) {
      setError(e instanceof Error ? e.message : '读取资质详情失败');
    }
  }, []);

  const submit = useCallback(async () => {
    if (!form) return;
    const problems = validateQualification(form);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    try {
      const saved = await createQualification({
        ...form,
        code: form.code.trim(),
        title: form.title.trim(),
        certNo: form.certNo.trim(),
        issuer: form.issuer.trim(),
        validFrom: form.validFrom.trim(),
        validUntil: form.validUntil.trim(),
        note: form.note.trim(),
      });
      setForm(null);
      setNotice(`已登记资质「${saved.code}」，当前是草稿，提交审核后才进入审核流程。`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记资质失败');
    }
  }, [form, load]);

  const upload = useCallback(async () => {
    if (!detail || !documentForm) return;
    const problems = validateDocument(documentForm);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    try {
      await uploadQualificationDocument(detail.qualification.id, documentForm);
      setDocumentForm(null);
      setNotice('资质文件已上传：文件本体不入库，只存对象存储的 key。');
      await openDetail(detail.qualification.id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '上传资质文件失败');
    }
  }, [detail, documentForm, load, openDetail]);

  const review = useCallback(async () => {
    if (!detail) return;
    const problems = validateReview(reviewAction.action, reviewAction.reason);
    if (problems.length > 0) {
      setError(problems.join('；'));
      return;
    }
    setError('');
    try {
      await reviewQualification(detail.qualification.id, reviewAction.action, reviewAction.reason);
      setReviewAction({ action: 'SUBMIT', reason: '' });
      setNotice('审核流转已记录：每一次流转都留痕，审核记录不可改写。');
      await openDetail(detail.qualification.id);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '审核流转失败');
    }
  }, [detail, reviewAction, load, openDetail]);

  const scan = useCallback(async () => {
    setError('');
    try {
      const result = await scanReminders();
      setNotice(
        `已生成 ${result.createdCount} 条提醒（即将过期 ${result.expiringCount} 条、已过期 ${result.expiredCount} 条）。提醒**不等于**阻断 —— 阻断看阻断判定。`,
      );
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '生成提醒失败');
    }
  }, [load]);

  const acknowledge = useCallback(
    async (id: string, status: string) => {
      setError('');
      try {
        await handleReminder(id, status, '');
        setNotice('提醒已处理。');
        await load();
      } catch (e) {
        setError(e instanceof Error ? e.message : '处理提醒失败');
      }
    },
    [load],
  );

  const judge = useCallback(async () => {
    if (!decisionTarget.trim()) {
      setError('请先填写要判定的供应商或商品 ID');
      return;
    }
    if (decisionScope === 'BRAND' && !decisionBrand.trim()) {
      setError(
        '品牌维度判定必须填品牌 ID：品牌授权要关联 03 品牌，没有品牌就答不出能不能卖这个牌子',
      );
      return;
    }
    setError('');
    try {
      const result =
        decisionScope === 'SUPPLIER'
          ? await fetchSupplierBlockingDecision(decisionTarget.trim())
          : decisionScope === 'BRAND'
            ? await fetchSupplierBrandBlockingDecision(decisionTarget.trim(), decisionBrand.trim())
            : await fetchSkuBlockingDecision(decisionTarget.trim());
      setDecision(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : '阻断判定失败');
    }
  }, [decisionScope, decisionTarget, decisionBrand]);

  return (
    <>
      <PageHeader
        title="供应商资质"
        description="登记与跟踪供应商资质：营业执照、质检报告、认证证书（CE / ROHS / ISO 等）、授权书，明确有效期与即将过期提醒，品牌授权关联 03 品牌。**资质过期必须阻断相关商品的刊登与发货**，不能只做提醒 —— 阻断判定在本页的「阻断判定」分区。"
      />

      {error ? (
        <Notice tone="danger" title="出错了">
          {error}
        </Notice>
      ) : null}
      {notice ? (
        <Notice tone="success" title="已完成">
          {notice}
        </Notice>
      ) : null}

      <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="资质档案"
          value={String(overview?.totalCount ?? 0)}
          comparisonLabel={`已通过 ${overview?.approvedCount ?? 0} · 待审 ${overview?.pendingCount ?? 0}`}
        />
        <StatCard
          label="即将过期"
          value={String(overview?.expiringCount ?? 0)}
          polarity="negative"
          comparisonLabel="仍在有效期内，该去续期"
        />
        <StatCard
          label="已过期"
          value={String(overview?.expiredCount ?? 0)}
          polarity="negative"
          comparisonLabel="已越过有效期终点，阻断刊登与发货"
        />
        <StatCard
          label="阻断项"
          value={String(overview?.blockingCount ?? 0)}
          polarity="negative"
          comparisonLabel={`未处理提醒 ${overview?.openReminderCount ?? 0} 条`}
        />
      </div>

      <Tabs defaultValue="list" className="mt-6">
        <TabsList>
          <TabsTrigger value="list">资质档案</TabsTrigger>
          <TabsTrigger value="reminders">过期提醒</TabsTrigger>
          <TabsTrigger value="decision">阻断判定</TabsTrigger>
          <TabsTrigger value="policy">提醒策略</TabsTrigger>
          <TabsTrigger value="boundary">能力与边界</TabsTrigger>
        </TabsList>

        {/* ============================================================ 资质档案 */}
        <TabsContent value="list">
          <SectionCard title="筛选">
            <div className="grid gap-3 md:grid-cols-4">
              <FormField id="qKeyword" label="关键词" hint="编码 / 证件名称 / 证书编号 / 供应商名">
                <Input
                  id="qKeyword"
                  value={filters.keyword}
                  placeholder="全部"
                  onChange={(e) => setFilters({ ...filters, keyword: e.target.value, page: 0 })}
                />
              </FormField>
              <FormField id="qSupplier" label="供应商">
                <select
                  id="qSupplier"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.supplierRoleId}
                  onChange={(e) =>
                    setFilters({ ...filters, supplierRoleId: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {suppliers.map((s) => (
                    <option key={s.roleId} value={s.roleId}>
                      {s.partyName}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="qType" label="资质类型">
                <select
                  id="qType"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.qualificationType}
                  onChange={(e) =>
                    setFilters({ ...filters, qualificationType: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.qualificationTypes ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="qExpiry" label="到期状态">
                <select
                  id="qExpiry"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.expiryState}
                  onChange={(e) => setFilters({ ...filters, expiryState: e.target.value, page: 0 })}
                >
                  <option value="">全部</option>
                  {(options?.expiryStates ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
            <div className="mt-3 flex flex-wrap items-center gap-2">
              <FormField id="qReview" label="审核状态">
                <select
                  id="qReview"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={filters.reviewStatus}
                  onChange={(e) =>
                    setFilters({ ...filters, reviewStatus: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.reviewStatuses ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <Button
                size="sm"
                variant="ghost"
                onClick={() => setFilters({ ...EMPTY_QUALIFICATION_FILTERS })}
              >
                重置筛选
              </Button>
              <Button size="sm" variant="outline" onClick={() => setForm(emptyQualificationForm())}>
                <Plus className="h-4 w-4" />
                登记资质
              </Button>
            </div>
            {page?.note ? <p className="text-muted-foreground mt-2 text-xs">{page.note}</p> : null}
          </SectionCard>

          {form ? (
            <SectionCard title="登记资质" className="mt-4">
              <div className="grid gap-3 md:grid-cols-3">
                <FormField id="fCode" label="资质编码" required hint="租户内唯一、建后不可改">
                  <Input
                    id="fCode"
                    value={form.code}
                    onChange={(e) => setForm({ ...form, code: e.target.value })}
                  />
                </FormField>
                <FormField id="fSupplier" label="供应商" required hint="来自 catalog 的供应商标识">
                  <select
                    id="fSupplier"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={form.supplierRoleId}
                    onChange={(e) => setForm({ ...form, supplierRoleId: e.target.value })}
                  >
                    <option value="">请选择</option>
                    {suppliers.map((s) => (
                      <option key={s.roleId} value={s.roleId}>
                        {s.partyName}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="fType" label="资质类型" required>
                  <select
                    id="fType"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={form.qualificationType}
                    onChange={(e) => setForm({ ...form, qualificationType: e.target.value })}
                  >
                    {(options?.qualificationTypes ?? []).map((o) => (
                      <option key={o.value} value={o.value}>
                        {o.label}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="fTitle" label="证件名称" required>
                  <Input
                    id="fTitle"
                    value={form.title}
                    onChange={(e) => setForm({ ...form, title: e.target.value })}
                  />
                </FormField>
                <FormField id="fCertNo" label="证书编号">
                  <Input
                    id="fCertNo"
                    value={form.certNo}
                    onChange={(e) => setForm({ ...form, certNo: e.target.value })}
                  />
                </FormField>
                <FormField id="fIssuer" label="发证机构">
                  <Input
                    id="fIssuer"
                    value={form.issuer}
                    onChange={(e) => setForm({ ...form, issuer: e.target.value })}
                  />
                </FormField>
                <FormField id="fBrand" label="关联品牌" hint="授权书是品牌授权，必须关联 03 品牌">
                  <select
                    id="fBrand"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={form.brandId ?? ''}
                    onChange={(e) => setForm({ ...form, brandId: e.target.value || null })}
                  >
                    <option value="">不关联</option>
                    {brands.map((b) => (
                      <option key={b.id} value={b.id}>
                        {b.name}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField
                  id="fFrom"
                  label="有效期起点"
                  required
                  hint="YYYY-MM-DD 或带时区的 ISO-8601"
                >
                  <Input
                    id="fFrom"
                    value={form.validFrom}
                    onChange={(e) => setForm({ ...form, validFrom: e.target.value })}
                  />
                </FormField>
                <FormField id="fUntil" label="有效期终点" required hint="终点按当日最后一刻算">
                  <Input
                    id="fUntil"
                    value={form.validUntil}
                    onChange={(e) => setForm({ ...form, validUntil: e.target.value })}
                  />
                </FormField>
              </div>
              <div className="mt-3 flex gap-2">
                <Button size="sm" onClick={() => void submit()}>
                  登记
                </Button>
                <Button size="sm" variant="ghost" onClick={() => setForm(null)}>
                  取消
                </Button>
              </div>
            </SectionCard>
          ) : null}

          <SectionCard title={`资质档案（${page?.total ?? 0}）`} className="mt-4">
            {loading ? (
              <p className="text-muted-foreground text-sm">正在读取资质档案…</p>
            ) : page && page.items.length > 0 ? (
              <div className="space-y-3">
                {page.items.map((row) => (
                  <QualificationCard
                    key={row.id}
                    row={row}
                    onOpen={() => void openDetail(row.id)}
                  />
                ))}
              </div>
            ) : (
              <EmptyState
                icon={<ShieldCheck className="h-8 w-8" />}
                title="还没有登记任何资质"
                description="资质的有效期与审核状态是「能不能刊登、能不能发货」的判据。先登记营业执照，再逐项补齐认证证书与品牌授权。"
                action={
                  <Button size="sm" onClick={() => setForm(emptyQualificationForm())}>
                    <Plus className="h-4 w-4" />
                    登记资质
                  </Button>
                }
              />
            )}
          </SectionCard>

          {detail ? (
            <SectionCard
              title={`资质详情 · ${detail.qualification.code}`}
              extra={
                <Button size="sm" variant="ghost" onClick={() => setDetail(null)}>
                  关闭
                </Button>
              }
              className="mt-4"
            >
              <p className="text-copy-13 text-muted-foreground">{detail.note}</p>
              <h4 className="text-label-13 mt-4 font-semibold">审核流转</h4>
              {detail.reviews.length > 0 ? (
                <ul className="mt-2 space-y-1">
                  {detail.reviews.map((r) => (
                    <li key={r.id} className="text-copy-13">
                      #{r.seq} {r.actionLabel}：{r.fromStatusLabel} → {r.toStatusLabel}
                      {r.reason ? `（${r.reason}）` : ''} · {instantText(r.operatedAt)}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-muted-foreground mt-2 text-xs">还没有流转记录：资质仍是草稿。</p>
              )}

              <h4 className="text-label-13 mt-4 font-semibold">审核操作</h4>
              <div className="mt-2 flex flex-wrap items-end gap-2">
                <FormField id="rAction" label="动作">
                  <select
                    id="rAction"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={reviewAction.action}
                    onChange={(e) => setReviewAction({ ...reviewAction, action: e.target.value })}
                  >
                    <option value="SUBMIT">提交审核</option>
                    <option value="APPROVE">审核通过</option>
                    <option value="REJECT">驳回</option>
                    <option value="REVOKE">撤销</option>
                    <option value="RESUBMIT">重新提交</option>
                  </select>
                </FormField>
                <FormField id="rReason" label="理由" hint="驳回与撤销必填">
                  <Input
                    id="rReason"
                    value={reviewAction.reason}
                    onChange={(e) => setReviewAction({ ...reviewAction, reason: e.target.value })}
                  />
                </FormField>
                <Button size="sm" onClick={() => void review()}>
                  执行
                </Button>
              </div>

              <h4 className="text-label-13 mt-4 font-semibold">资质文件</h4>
              {detail.documents.length > 0 ? (
                <ul className="mt-2 space-y-1">
                  {detail.documents.map((d) => (
                    <li key={d.id} className="text-copy-13">
                      {d.documentKindLabel} · {d.documentName || d.documentKey} ·{' '}
                      {fileSizeText(d.sizeBytes)} · {instantText(d.uploadedAt)}
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-muted-foreground mt-2 text-xs">还没有上传文件。</p>
              )}
              <div className="mt-3 flex flex-wrap items-end gap-2">
                <FormField id="dKind" label="文件类别">
                  <select
                    id="dKind"
                    className="border-input h-9 w-full rounded-md border px-2 text-sm"
                    value={documentForm?.documentKind ?? 'ORIGINAL'}
                    onChange={(e) =>
                      setDocumentForm({
                        ...(documentForm ?? emptyDocumentForm()),
                        documentKind: e.target.value,
                      })
                    }
                  >
                    <option value="ORIGINAL">原件</option>
                    <option value="RENEWAL">续期件</option>
                    <option value="ATTACHMENT">补充材料</option>
                  </select>
                </FormField>
                <FormField id="dKey" label="文件 key" hint="对象存储的 key，文件本体不入库">
                  <Input
                    id="dKey"
                    value={documentForm?.documentKey ?? ''}
                    onChange={(e) =>
                      setDocumentForm({
                        ...(documentForm ?? emptyDocumentForm()),
                        documentKey: e.target.value,
                      })
                    }
                  />
                </FormField>
                <FormField id="dSize" label="大小（字节）">
                  <Input
                    id="dSize"
                    value={documentForm?.sizeBytes ?? ''}
                    onChange={(e) =>
                      setDocumentForm({
                        ...(documentForm ?? emptyDocumentForm()),
                        sizeBytes: e.target.value,
                      })
                    }
                  />
                </FormField>
                <Button
                  size="sm"
                  variant="outline"
                  onClick={() => {
                    setDocumentForm(emptyDocumentForm());
                    void upload();
                  }}
                >
                  <FileUp className="h-4 w-4" />
                  上传
                </Button>
              </div>
            </SectionCard>
          ) : null}
        </TabsContent>

        {/* ============================================================ 过期提醒 */}
        <TabsContent value="reminders">
          <SectionCard
            title={`过期提醒（${reminders?.total ?? 0}）`}
            extra={
              <Button size="sm" variant="outline" onClick={() => void scan()}>
                <RefreshCw className="h-4 w-4" />
                生成 / 刷新提醒
              </Button>
            }
          >
            <div className="grid gap-3 md:grid-cols-3">
              <FormField id="rKind" label="提醒类别">
                <select
                  id="rKind"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={reminderFilters.reminderKind}
                  onChange={(e) =>
                    setReminderFilters({
                      ...reminderFilters,
                      reminderKind: e.target.value,
                      page: 0,
                    })
                  }
                >
                  <option value="">全部</option>
                  {(options?.reminderKinds ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
              <FormField id="rStatus" label="处理状态">
                <select
                  id="rStatus"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={reminderFilters.status}
                  onChange={(e) =>
                    setReminderFilters({ ...reminderFilters, status: e.target.value, page: 0 })
                  }
                >
                  <option value="">全部</option>
                  {(options?.reminderStatuses ?? []).map((o) => (
                    <option key={o.value} value={o.value}>
                      {o.label}
                    </option>
                  ))}
                </select>
              </FormField>
            </div>
            {reminders?.note ? (
              <p className="text-muted-foreground mt-2 text-xs">{reminders.note}</p>
            ) : null}
            <div className="mt-4 space-y-3">
              {loading ? (
                <p className="text-muted-foreground text-sm">正在读取提醒…</p>
              ) : reminders && reminders.items.length > 0 ? (
                reminders.items.map((r) => (
                  <div key={r.id} className="border-border rounded-md border p-3">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={reminderTone(r.reminderKind)}>{r.reminderKindLabel}</Badge>
                      <Badge tone={reminderStatusTone(r.status)}>{r.statusLabel}</Badge>
                      <span className="text-copy-13">{r.qualificationTitle}</span>
                      <span className="text-muted-foreground text-copy-12">
                        {r.supplierName} · 到期日 {dateText(r.dueDate)} · 阈值 {r.warningDays} 天
                      </span>
                    </div>
                    <div className="mt-2 flex gap-2">
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void acknowledge(r.id, 'ACKNOWLEDGED')}
                      >
                        确认
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void acknowledge(r.id, 'RESOLVED')}
                      >
                        关闭
                      </Button>
                      <Button
                        size="sm"
                        variant="ghost"
                        onClick={() => void openDetail(r.qualificationId)}
                      >
                        看档案
                      </Button>
                    </div>
                  </div>
                ))
              ) : (
                <EmptyState
                  icon={<AlertTriangle className="h-8 w-8" />}
                  title="没有待处理的过期提醒"
                  description="「即将过期」与「已过期」是两种不同的提醒，分别对应「该去续期」与「已经出事了」。点右上角生成一次，系统会按当前阈值窗口扫一遍。"
                />
              )}
            </div>
          </SectionCard>
        </TabsContent>

        {/* ============================================================ 阻断判定 */}
        <TabsContent value="decision">
          <SectionCard title="阻断判定">
            <p className="text-copy-13 text-muted-foreground">
              「资质过期必须阻断相关商品的刊登与发货」——这里给出的是给下游执行的判定结论，
              不是一句提示语。<b>即将过期不阻断</b>（仍在有效期内），<b>已过期与已撤销才阻断</b>。
            </p>
            <div className="mt-3 flex flex-wrap items-end gap-2">
              <FormField id="dScope" label="判定范围">
                <select
                  id="dScope"
                  className="border-input h-9 w-full rounded-md border px-2 text-sm"
                  value={decisionScope}
                  onChange={(e) =>
                    setDecisionScope(
                      e.target.value === 'SKU'
                        ? 'SKU'
                        : e.target.value === 'BRAND'
                          ? 'BRAND'
                          : 'SUPPLIER',
                    )
                  }
                >
                  <option value="SUPPLIER">供应商维度</option>
                  <option value="BRAND">品牌维度</option>
                  <option value="SKU">商品维度</option>
                </select>
              </FormField>
              <FormField
                id="dTarget"
                label="对象 ID"
                hint="供应商维度填供应商 ID；商品维度填内部 SKU ID"
              >
                <Input
                  id="dTarget"
                  value={decisionTarget}
                  onChange={(e) => setDecisionTarget(e.target.value)}
                />
              </FormField>
              {decisionScope === 'BRAND' ? (
                <FormField id="dBrand" label="品牌 ID" hint="品牌维度判定必须指定品牌">
                  <Input
                    id="dBrand"
                    value={decisionBrand}
                    onChange={(e) => setDecisionBrand(e.target.value)}
                  />
                </FormField>
              ) : null}
              <Button size="sm" onClick={() => void judge()}>
                判定
              </Button>
            </div>

            {decision ? (
              <div className="mt-4 space-y-2">
                <div className="flex flex-wrap items-center gap-2">
                  <Badge tone={decision.blocked ? 'danger' : 'success'}>
                    {decision.blocked ? '阻断' : '不阻断'}
                  </Badge>
                  <Badge tone="neutral">{scopeLabel(decision.scope)}</Badge>
                  {decision.blockLabel ? <Badge tone="danger">{decision.blockLabel}</Badge> : null}
                  <span className="text-muted-foreground text-copy-12">
                    判定时刻 {instantText(decision.evaluatedAt)}
                  </span>
                </div>
                <p className="text-copy-13">{decision.blockMessage}</p>
                {decision.reasons.length > 0 ? (
                  <ul className="space-y-1">
                    {decision.reasons.map((r, i) => (
                      <li key={`${r.code}-${i}`} className="text-copy-13">
                        <Badge tone="danger">{r.label}</Badge> {r.message}
                      </li>
                    ))}
                  </ul>
                ) : null}
                <p className="text-muted-foreground text-xs">{decision.note}</p>
              </div>
            ) : (
              <EmptyState
                icon={<ShieldCheck className="h-8 w-8" />}
                title="还没有做过阻断判定"
                description="选一个范围、填一个对象 ID，点「判定」。结论直接给出 blocked 与原因码，供 04 刊登与发货链路调用。"
              />
            )}
          </SectionCard>
        </TabsContent>

        {/* ============================================================ 提醒策略 */}
        <TabsContent value="policy">
          <SectionCard title="提醒策略">
            <p className="text-copy-13 text-muted-foreground">
              「即将过期」的窗口与「哪些资质是必需的」是租户级策略，不是代码常量。
              到期状态由后端按数据库时钟现算，界面上不按浏览器时钟重算。
            </p>
            <div className="mt-3 grid gap-3 md:grid-cols-2">
              <div>
                <p className="text-copy-13">
                  当前提醒窗口：<b>{policy?.warningDays ?? '—'}</b> 天
                </p>
                <p className="text-copy-13 mt-1">
                  必备资质类型：
                  {(policy?.requiredTypes ?? []).length > 0
                    ? (policy?.requiredTypes ?? []).join('、')
                    : '—'}
                </p>
                {policy?.updatedAt ? (
                  <p className="text-muted-foreground mt-1 text-xs">
                    最近更新 {instantText(policy.updatedAt)}
                  </p>
                ) : null}
              </div>
              <div>
                <p className="text-muted-foreground text-copy-12">
                  必备资质缺失时，阻断判定会报「资质缺失」；非必备类型缺失**不**阻断 ——
                  它只是还没准备好，不该拦发货。
                </p>
              </div>
            </div>
          </SectionCard>
        </TabsContent>

        {/* ============================================================ 能力与边界 */}
        <TabsContent value="boundary">
          <SectionCard title="能力与边界">
            {capabilities ? (
              <>
                <p className="text-copy-13">{capabilities.blockingSemantics}</p>
                <p className="text-muted-foreground text-copy-13 mt-2">{capabilities.note}</p>
                <h4 className="text-label-13 mt-4 font-semibold">上下游可用性</h4>
                <ul className="mt-2 space-y-2">
                  {capabilities.gaps.map((g) => (
                    <li key={g.moduleId} className="text-copy-13">
                      <Badge tone={g.available ? 'success' : 'warning'}>
                        {g.moduleId} {g.moduleName}
                      </Badge>
                      <span className="text-muted-foreground ml-2">
                        归 {g.ownerModule} · {g.impact}
                      </span>
                    </li>
                  ))}
                </ul>
              </>
            ) : (
              <p className="text-muted-foreground text-sm">正在读取能力声明…</p>
            )}
          </SectionCard>
        </TabsContent>
      </Tabs>
    </>
  );
}

/** 一行资质：类型、有效期、审核状态、到期状态各有各的位置，不揉成一个标签。 */
function QualificationCard({ row, onOpen }: { row: QualificationRow; onOpen: () => void }) {
  return (
    <div className="border-border rounded-md border p-3">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="info">{row.qualificationTypeLabel}</Badge>
        <Badge tone={reviewTone(row.reviewStatus)}>{row.reviewStatusLabel}</Badge>
        <Badge tone={expiryTone(row.expiryState)}>
          {expiryText(row.expiryState, row.daysToExpiry)}
        </Badge>
        <span className="text-copy-13">{row.title}</span>
        <span className="text-muted-foreground text-copy-12">
          {row.supplierName}
          {row.brandName ? ` · 品牌 ${row.brandName}` : ''} · {row.code}
        </span>
      </div>
      <div className="text-muted-foreground text-copy-12 mt-1 flex flex-wrap items-center gap-3">
        <span>有效期 {validityText(row.validFrom, row.validUntil)}</span>
        {row.certNo ? <span>证书编号 {row.certNo}</span> : null}
        {row.issuer ? <span>发证机构 {row.issuer}</span> : null}
        {row.documentCount > 0 ? <span>文件 {row.documentCount} 份</span> : null}
        {row.openReminderCount > 0 ? <span>未处理提醒 {row.openReminderCount} 条</span> : null}
      </div>
      <div className="mt-2">
        <Button size="sm" variant="ghost" onClick={onOpen}>
          看详情与审核留痕
        </Button>
      </div>
    </div>
  );
}
