'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  EmptyState,
  Input,
  PageHeader,
  SectionCard,
  StatCard,
} from '@starcon/ui';
import {
  BadgeCheck,
  CalendarClock,
  ClipboardList,
  FileUp,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  attachCategory,
  attachSku,
  createCertification,
  createRequirement,
  deleteRequirement,
  deleteType,
  detachCategory,
  detachSku,
  documentKindLabel,
  daysRemainingText,
  emptyState,
  expiryStateLabel,
  expiryStateTone,
  fetchCapabilities,
  fetchCategoryCandidates,
  fetchCertification,
  fetchCertifications,
  fetchCoverage,
  fetchExpiryReminders,
  fetchOverview,
  fetchPolicy,
  fetchRequirements,
  fetchSkuCandidates,
  fetchTypes,
  fileSizeText,
  revokeCertification,
  savePolicy,
  saveType,
  statusLabel,
  updateCertification,
  upstreamUnavailableText,
  uploadDocument,
  type CertificationDetail,
  type CertificationDocumentRow,
  type CertificationOverview,
  type CertificationPage,
  type CertificationPolicyView,
  type CertificationSummary,
  type CertificationTypeRow,
  type CoverageReport,
  type ExpiryReminderPage,
  type ProductCertificationCapabilities,
  type RequirementPage,
} from './product-certification-model';

/**
 * 16.2.2.2 认证与资质 工作区。
 *
 * 蓝图：「管理产品认证与资质（CE、FCC、RoHS、PSE、FDA、CCC 等）：认证编号、有效期、适用 SKU、
 * 对应类目要求，支持上传扫描件与到期提醒。输入：认证录入；文件上传；有效期。输出：认证档案 +
 * 到期提醒 + 覆盖情况统计（哪些 SKU 缺认证）。关系：上游 = 6.1.2.3 供应商资质；下游 = 16.2.2.1 预检、
 * 4 刊登。**认证缺失是 listing 被抑制与下架的高频原因**，且属于"上架时不管、出事才补"的典型滞后项。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * 1. **到期状态与剩余天数原样显示后端算好的值**（按数据库时钟算）。前端用浏览器时钟重算会因
 *    时区与时钟漂移说错，而「还剩 3 天」直接决定运营今天要不要去补。
 * 2. **「读不到」不显示成「没有」**：catalog 不可达时后端 503，页面显示「读不到上游数据」。
 * 3. **覆盖统计是后端算的**：前端不自己数「哪些 SKU 缺认证」。
 * 4. **认证类型下拉来自后端词表**：前端不写死 CE / FCC 之类，租户加了新类型立刻能用。
 */
export function ProductCertificationWorkspace() {
  const [caps, setCaps] = useState<ProductCertificationCapabilities | null>(null);
  const [overview, setOverview] = useState<CertificationOverview | null>(null);
  const [types, setTypes] = useState<CertificationTypeRow[]>([]);
  const [policy, setPolicy] = useState<CertificationPolicyView | null>(null);
  const [reminders, setReminders] = useState<ExpiryReminderPage | null>(null);
  const [coverage, setCoverage] = useState<CoverageReport | null>(null);
  const [list, setList] = useState<CertificationPage | null>(null);
  const [detail, setDetail] = useState<CertificationDetail | null>(null);
  const [requirements, setRequirements] = useState<RequirementPage | null>(null);
  const [skuCandidates, setSkuCandidates] = useState<
    { id: string; code: string; name: string; category: string }[]
  >([]);
  const [categoryCandidates, setCategoryCandidates] = useState<
    { id: string; code: string; name: string; path: string }[]
  >([]);

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [page, setPage] = useState(0);
  const [filterType, setFilterType] = useState('');
  const [filterExpiry, setFilterExpiry] = useState('');
  const [keyword, setKeyword] = useState('');
  const [selectedId, setSelectedId] = useState<string | null>(null);

  const [newCode, setNewCode] = useState('');
  const [newTitle, setNewTitle] = useState('');
  const [newType, setNewType] = useState('');
  const [newCertNo, setNewCertNo] = useState('');
  const [newIssuer, setNewIssuer] = useState('');
  const [newValidFrom, setNewValidFrom] = useState('');
  const [newValidUntil, setNewValidUntil] = useState('');

  const [newTypeCode, setNewTypeCode] = useState('');
  const [newTypeName, setNewTypeName] = useState('');
  const [newTypeScope, setNewTypeScope] = useState('GLOBAL');

  const [requirementSkuId, setRequirementSkuId] = useState('');
  const [requirementSkuCode, setRequirementSkuCode] = useState('');
  const [requirementSkuName, setRequirementSkuName] = useState('');
  const [requirementType, setRequirementType] = useState('');
  const [requirementCategoryId, setRequirementCategoryId] = useState('');
  const [requirementCategoryCode, setRequirementCategoryCode] = useState('');
  const [requirementCategoryName, setRequirementCategoryName] = useState('');

  const [attachSkuId, setAttachSkuId] = useState('');
  const [attachCategoryId, setAttachCategoryId] = useState('');
  const [uploadKind, setUploadKind] = useState('ORIGINAL');
  const [uploadNote, setUploadNote] = useState('');

  const PAGE_SIZE = 10;

  const loadList = useCallback(
    async (nextPage: number) => {
      const result = await fetchCertifications({
        certificationTypeCode: filterType || undefined,
        expiryState: filterExpiry || undefined,
        q: keyword || undefined,
        page: nextPage,
        size: PAGE_SIZE,
      });
      setList(result);
      return result;
    },
    [filterType, filterExpiry, keyword],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, overviewResult, typesResult, policyResult, reminderResult, coverageResult, listResult, requirementResult] =
        await Promise.all([
          fetchCapabilities(),
          fetchOverview(),
          fetchTypes(),
          fetchPolicy(),
          fetchExpiryReminders(0, 20),
          fetchCoverage(),
          loadList(0),
          fetchRequirements({ page: 0, size: PAGE_SIZE }),
        ]);
      setCaps(capsResult);
      setOverview(overviewResult);
      setTypes(typesResult);
      setPolicy(policyResult);
      setReminders(reminderResult);
      setCoverage(coverageResult);
      setRequirements(requirementResult);
      setPage(0);
      setSelectedId((current) => {
        if (current && listResult.items.some((item) => item.id === current)) return current;
        return listResult.items[0]?.id ?? null;
      });
    } catch (e) {
      setError(
        e instanceof Error && e.message.includes('读不到')
          ? upstreamUnavailableText(e.message)
          : e instanceof Error
            ? e.message
            : '加载失败，请重试',
      );
    } finally {
      setLoading(false);
    }
  }, [loadList]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedId) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const result = await fetchCertification(selectedId);
        if (!cancelled) setDetail(result);
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : '读取认证详情失败，请重试');
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [selectedId]);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await work();
    } catch (e) {
      setError(
        e instanceof Error && e.message.includes('读不到')
          ? upstreamUnavailableText(e.message)
          : e instanceof Error
            ? e.message
            : '操作失败，请重试',
      );
    } finally {
      setBusy(false);
    }
  }, []);

  const enabledTypes = useMemo(() => types.filter((row) => row.enabled), [types]);

  const openDetail = useCallback(
    (id: string) =>
      run(async () => {
        setSelectedId(id);
      }),
    [run],
  );

  const submitCertification = useCallback(
    () =>
      run(async () => {
        if (!newCode.trim() || !newType || !newValidFrom || !newValidUntil) {
          setError('认证编号、认证类型、有效期起止都必须填');
          return;
        }
        const created = await createCertification({
          code: newCode.trim(),
          certificationTypeCode: newType,
          title: newTitle.trim() || newCode.trim(),
          certNo: newCertNo.trim(),
          issuer: newIssuer.trim(),
          validFrom: new Date(newValidFrom).toISOString(),
          validUntil: new Date(newValidUntil).toISOString(),
        });
        setNotice(`已登记认证 ${created.code}`);
        setNewCode('');
        setNewTitle('');
        setNewCertNo('');
        setNewIssuer('');
        setNewValidFrom('');
        setNewValidUntil('');
        await refresh();
        setSelectedId(created.id);
      }),
    [newCode, newType, newTitle, newCertNo, newIssuer, newValidFrom, newValidUntil, refresh, run],
  );

  const submitType = useCallback(
    () =>
      run(async () => {
        if (!newTypeCode.trim() || !newTypeName.trim()) {
          setError('认证类型编码与名称都必须填');
          return;
        }
        await saveType(newTypeCode.trim().toUpperCase(), {
          code: newTypeCode.trim().toUpperCase(),
          name: newTypeName.trim(),
          scope: newTypeScope,
          enabled: true,
        });
        setNotice(`已保存认证类型 ${newTypeCode.trim().toUpperCase()}`);
        setNewTypeCode('');
        setNewTypeName('');
        setTypes(await fetchTypes());
      }),
    [newTypeCode, newTypeName, newTypeScope, run],
  );

  const toggleType = useCallback(
    (row: CertificationTypeRow) =>
      run(async () => {
        if (row.tenantDefined) {
          await saveType(row.code, {
            code: row.code,
            name: row.name,
            scope: row.scope,
            description: row.description,
            enabled: !row.enabled,
          });
        } else {
          await saveType(row.code, {
            code: row.code,
            name: row.name,
            scope: row.scope,
            description: row.description,
            enabled: !row.enabled,
          });
        }
        setTypes(await fetchTypes());
      }),
    [run],
  );

  const removeType = useCallback(
    (row: CertificationTypeRow) =>
      run(async () => {
        await deleteType(row.code);
        setNotice(`已删除租户自定义类型 ${row.code}（回到内置默认）`);
        setTypes(await fetchTypes());
      }),
    [run],
  );

  const changePolicy = useCallback(
    (days: number) =>
      run(async () => {
        const updated = await savePolicy({ warningDays: days });
        setPolicy(updated);
        setReminders(await fetchExpiryReminders(0, 20));
      }),
    [run],
  );

  const submitRequirement = useCallback(
    () =>
      run(async () => {
        if (!requirementSkuId.trim() || !requirementType) {
          setError('SKU 与认证类型都必须选');
          return;
        }
        await createRequirement({
          skuId: requirementSkuId.trim(),
          skuCode: requirementSkuCode.trim(),
          skuName: requirementSkuName.trim(),
          certificationTypeCode: requirementType,
          categoryId: requirementCategoryId.trim() || undefined,
          categoryCode: requirementCategoryCode.trim(),
          categoryName: requirementCategoryName.trim(),
        });
        setNotice('已登记一条认证要求');
        setRequirementSkuId('');
        setRequirementSkuCode('');
        setRequirementSkuName('');
        setRequirementType('');
        setRequirementCategoryId('');
        setRequirementCategoryCode('');
        setRequirementCategoryName('');
        setRequirements(await fetchRequirements({ page: 0, size: PAGE_SIZE }));
        setCoverage(await fetchCoverage());
        setOverview(await fetchOverview());
      }),
    [
      requirementSkuId,
      requirementSkuCode,
      requirementSkuName,
      requirementType,
      requirementCategoryId,
      requirementCategoryCode,
      requirementCategoryName,
      run,
    ],
  );

  const removeRequirement = useCallback(
    (id: string) =>
      run(async () => {
        await deleteRequirement(id);
        setRequirements(await fetchRequirements({ page: 0, size: PAGE_SIZE }));
        setCoverage(await fetchCoverage());
        setOverview(await fetchOverview());
      }),
    [run],
  );

  const attachSkuToCertification = useCallback(
    () =>
      run(async () => {
        if (!detail || !attachSkuId) {
          setError('先选一个认证，再选要挂的 SKU');
          return;
        }
        const candidate = skuCandidates.find((row) => row.id === attachSkuId);
        await attachSku(detail.id, {
          skuId: attachSkuId,
          skuCode: candidate?.code ?? '',
          skuName: candidate?.name ?? '',
        });
        setAttachSkuId('');
        setDetail(await fetchCertification(detail.id));
        await loadList(page);
      }),
    [detail, attachSkuId, skuCandidates, loadList, page, run],
  );

  const detachSkuFromCertification = useCallback(
    (skuId: string) =>
      run(async () => {
        if (!detail) return;
        await detachSku(detail.id, skuId);
        setDetail(await fetchCertification(detail.id));
        await loadList(page);
      }),
    [detail, loadList, page, run],
  );

  const attachCategoryToCertification = useCallback(
    () =>
      run(async () => {
        if (!detail || !attachCategoryId) {
          setError('先选一个认证，再选要挂的类目');
          return;
        }
        const candidate = categoryCandidates.find((row) => row.id === attachCategoryId);
        await attachCategory(detail.id, {
          categoryId: attachCategoryId,
          categoryCode: candidate?.code ?? '',
          categoryName: candidate?.name ?? '',
        });
        setAttachCategoryId('');
        setDetail(await fetchCertification(detail.id));
        await loadList(page);
      }),
    [detail, attachCategoryId, categoryCandidates, loadList, page, run],
  );

  const detachCategoryFromCertification = useCallback(
    (categoryId: string) =>
      run(async () => {
        if (!detail) return;
        await detachCategory(detail.id, categoryId);
        setDetail(await fetchCertification(detail.id));
        await loadList(page);
      }),
    [detail, loadList, page, run],
  );

  const upload = useCallback(
    (file: File) =>
      run(async () => {
        if (!detail) {
          setError('先选一张认证再上传扫描件');
          return;
        }
        await uploadDocument(detail.id, file, uploadKind, uploadNote);
        setUploadNote('');
        setDetail(await fetchCertification(detail.id));
        await loadList(page);
      }),
    [detail, uploadKind, uploadNote, loadList, page, run],
  );

  const revoke = useCallback(
    (row: CertificationSummary) =>
      run(async () => {
        const reason = window.prompt('撤销这张认证的理由（必填）：');
        if (reason === null) return;
        await revokeCertification(row.id, reason.trim() || '未填写理由');
        await loadList(page);
        setReminders(await fetchExpiryReminders(0, 20));
        setCoverage(await fetchCoverage());
        setOverview(await fetchOverview());
        if (selectedId === row.id) setDetail(await fetchCertification(row.id));
      }),
    [loadList, page, selectedId, run],
  );

  const searchCandidates = useCallback(
    () =>
      run(async () => {
        const [skus, categories] = await Promise.all([
          fetchSkuCandidates(keyword || undefined, 0, 20),
          fetchCategoryCandidates(),
        ]);
        setSkuCandidates(skus.items);
        setCategoryCandidates(categories.items);
        if (skus.items.length === 0 && categories.items.length === 0) {
          setNotice('catalog 没有返回候选数据（可能是没有匹配项，也可能是上游读不到）');
        }
      }),
    [keyword, run],
  );

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-muted-foreground">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载认证与资质…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="认证与资质"
        description="产品认证档案（CE / FCC / RoHS / PSE / FDA / CCC 等）、到期提醒与覆盖情况统计。认证缺失是 listing 被抑制与下架的高频原因。"
        actions={
          <Button variant="outline" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive/40 bg-destructive-soft text-destructive rounded-md border px-4 py-3 text-copy-13">
          {error}
        </div>
      )}
      {notice && (
        <div className="border-info/40 bg-info-soft text-info rounded-md border px-4 py-3 text-copy-13">
          {notice}
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="认证档案"
          value={overview?.certificationCount ?? 0}
          unit="张"
          icon={<BadgeCheck className="h-4 w-4" />}
        />
        <StatCard
          label="即将过期"
          value={overview?.expiringCount ?? 0}
          unit="张"
          polarity="negative"
          icon={<CalendarClock className="h-4 w-4" />}
        />
        <StatCard
          label="已过期"
          value={overview?.expiredCount ?? 0}
          unit="张"
          polarity="negative"
          icon={<TriangleAlert className="h-4 w-4" />}
        />
        <StatCard
          label="缺认证的 SKU"
          value={overview?.missingSkuCount ?? 0}
          unit="个"
          polarity="negative"
          icon={<ShieldAlert className="h-4 w-4" />}
        />
      </div>

      {/* ---------------- 到期提醒 ---------------- */}
      <SectionCard
        title="到期提醒"
        description="临近到期与已过期的认证，按剩余天数排序（剩得少的排前面）。剩余天数由后端按数据库时钟算好。"
        extra={
          <div className="flex items-center gap-2 text-copy-13">
            <span className="text-muted-foreground">即将过期窗口</span>
            <Input
              className="w-20"
              type="number"
              min={1}
              max={365}
              value={policy?.warningDays ?? 30}
              onChange={(e) => changePolicy(Number(e.target.value) || 30)}
            />
            <span className="text-muted-foreground">天</span>
          </div>
        }
      >
        {reminders && reminders.items.length > 0 ? (
          <table className="w-full text-copy-13">
            <thead className="text-muted-foreground">
              <tr className="border-border border-b text-left">
                <th className="px-3 py-2 font-medium">认证编号</th>
                <th className="px-3 py-2 font-medium">类型</th>
                <th className="px-3 py-2 font-medium">证书</th>
                <th className="px-3 py-2 font-medium">到期日</th>
                <th className="px-3 py-2 font-medium">状态</th>
                <th className="px-3 py-2 font-medium">剩余</th>
                <th className="px-3 py-2 font-medium">适用 SKU</th>
              </tr>
            </thead>
            <tbody>
              {reminders.items.map((row) => (
                <tr key={row.certificationId} className="border-border border-b last:border-0">
                  <td className="px-3 py-2">{row.code}</td>
                  <td className="px-3 py-2">{row.certificationTypeName}</td>
                  <td className="px-3 py-2">{row.title}</td>
                  <td className="px-3 py-2">{row.validUntil.slice(0, 10)}</td>
                  <td className="px-3 py-2">
                    <Badge tone={expiryStateTone(row.expiryState)}>
                      {expiryStateLabel(row.expiryState)}
                    </Badge>
                  </td>
                  <td className="px-3 py-2">{daysRemainingText(row.daysRemaining)}</td>
                  <td className="px-3 py-2">{row.skuCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <EmptyState
            icon={<CalendarClock className="h-6 w-6" />}
            title={emptyState('NO_REMINDER').title}
            description={emptyState('NO_REMINDER').description}
          />
        )}
      </SectionCard>

      {/* ---------------- 覆盖情况统计 ---------------- */}
      <SectionCard
        title="覆盖情况统计"
        description="哪些 SKU 缺认证：要求侧（SKU 需要哪些认证类型）对上覆盖侧（当前有效且适用该 SKU 的认证）。"
      >
        {coverage && coverage.requiredRequirementCount > 0 ? (
          <div className="space-y-4">
            <div className="grid gap-3 sm:grid-cols-3">
              <StatCard label="已覆盖的要求" value={coverage.coveredRequirementCount} unit="条" />
              <StatCard
                label="仍缺的要求"
                value={coverage.missingRequirementCount}
                unit="条"
                polarity="negative"
              />
              <StatCard
                label="缺认证的 SKU"
                value={coverage.missingSkuCount}
                unit="个"
                polarity="negative"
              />
            </div>

            <div>
              <h4 className="text-copy-13 text-foreground mb-2 font-medium">按认证类型</h4>
              <table className="w-full text-copy-13">
                <thead className="text-muted-foreground">
                  <tr className="border-border border-b text-left">
                    <th className="px-3 py-2 font-medium">认证类型</th>
                    <th className="px-3 py-2 font-medium">要求数</th>
                    <th className="px-3 py-2 font-medium">已覆盖</th>
                    <th className="px-3 py-2 font-medium">仍缺</th>
                    <th className="px-3 py-2 font-medium">缺认证的 SKU</th>
                  </tr>
                </thead>
                <tbody>
                  {coverage.byType.map((row) => (
                    <tr key={row.certificationTypeCode} className="border-border border-b last:border-0">
                      <td className="px-3 py-2">{row.certificationTypeName}</td>
                      <td className="px-3 py-2">{row.requiredCount}</td>
                      <td className="px-3 py-2">{row.coveredCount}</td>
                      <td className="px-3 py-2">{row.missingCount}</td>
                      <td className="px-3 py-2">{row.missingSkuCount}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>

            {coverage.byCategory.length > 0 && (
              <div>
                <h4 className="text-copy-13 text-foreground mb-2 font-medium">按类目</h4>
                <table className="w-full text-copy-13">
                  <thead className="text-muted-foreground">
                    <tr className="border-border border-b text-left">
                      <th className="px-3 py-2 font-medium">类目</th>
                      <th className="px-3 py-2 font-medium">要求数</th>
                      <th className="px-3 py-2 font-medium">已覆盖</th>
                      <th className="px-3 py-2 font-medium">仍缺</th>
                    </tr>
                  </thead>
                  <tbody>
                    {coverage.byCategory.map((row) => (
                      <tr
                        key={row.categoryId ?? row.categoryCode}
                        className="border-border border-b last:border-0"
                      >
                        <td className="px-3 py-2">
                          {row.categoryName || row.categoryCode || '（未挂类目）'}
                        </td>
                        <td className="px-3 py-2">{row.requiredCount}</td>
                        <td className="px-3 py-2">{row.coveredCount}</td>
                        <td className="px-3 py-2">{row.missingCount}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <div>
              <h4 className="text-copy-13 text-foreground mb-2 font-medium">缺认证的 SKU</h4>
              {coverage.missingSkus.length > 0 ? (
                <ul className="space-y-1">
                  {coverage.missingSkus.map((row) => (
                    <li key={row.skuId} className="text-copy-13 flex items-center gap-2">
                      <span className="text-foreground">{row.skuCode || row.skuName || row.skuId}</span>
                      <span className="text-muted-foreground">{row.skuName}</span>
                      <Badge tone="danger">缺 {row.missingTypeCodes.join(' / ')}</Badge>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-copy-13 text-muted-foreground">
                  {emptyState('NO_GAP').title} —— {emptyState('NO_GAP').description}
                </p>
              )}
            </div>
          </div>
        ) : (
          <EmptyState
            icon={<ClipboardList className="h-6 w-6" />}
            title={emptyState('NO_GAP').title}
            description={`${emptyState('NO_GAP').description}（当前还没有登记任何认证要求）`}
          />
        )}
      </SectionCard>

      {/* ---------------- 认证档案台账 ---------------- */}
      <SectionCard
        title="认证档案"
        description="认证编号、有效期、适用 SKU、对应类目要求与扫描件。"
        extra={
          <Button variant="outline" size="sm" onClick={() => void searchCandidates()} disabled={busy}>
            拉取 catalog 候选
          </Button>
        }
      >
        <div className="mb-3 flex flex-wrap items-center gap-2">
          <Input
            className="w-56"
            placeholder="搜证书名称 / 编号 / 认证编号"
            value={keyword}
            onChange={(e) => setKeyword(e.target.value)}
          />
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={filterType}
            onChange={(e) => setFilterType(e.target.value)}
          >
            <option value="">全部认证类型</option>
            {types.map((row) => (
              <option key={row.code} value={row.code}>
                {row.name}
              </option>
            ))}
          </select>
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={filterExpiry}
            onChange={(e) => setFilterExpiry(e.target.value)}
          >
            <option value="">全部到期状态</option>
            <option value="VALID">有效</option>
            <option value="EXPIRING">即将过期</option>
            <option value="EXPIRED">已过期</option>
            <option value="FUTURE">尚未生效</option>
            <option value="REVOKED">已撤销</option>
          </select>
          <Button
            variant="secondary"
            size="sm"
            onClick={() =>
              void run(async () => {
                await loadList(0);
                setPage(0);
              })
            }
          >
            筛选
          </Button>
        </div>

        {list && list.items.length > 0 ? (
          <>
            <table className="w-full text-copy-13">
              <thead className="text-muted-foreground">
                <tr className="border-border border-b text-left">
                  <th className="px-3 py-2 font-medium">认证编号</th>
                  <th className="px-3 py-2 font-medium">类型</th>
                  <th className="px-3 py-2 font-medium">证书</th>
                  <th className="px-3 py-2 font-medium">认证编号号</th>
                  <th className="px-3 py-2 font-medium">有效期至</th>
                  <th className="px-3 py-2 font-medium">状态</th>
                  <th className="px-3 py-2 font-medium">适用</th>
                  <th className="px-3 py-2 font-medium">操作</th>
                </tr>
              </thead>
              <tbody>
                {list.items.map((row) => (
                  <tr
                    key={row.id}
                    className={`border-border border-b last:border-0 ${
                      selectedId === row.id ? 'bg-muted' : ''
                    }`}
                  >
                    <td className="px-3 py-2">
                      <button
                        type="button"
                        className="text-brand underline-offset-4 hover:underline"
                        onClick={() => void openDetail(row.id)}
                      >
                        {row.code}
                      </button>
                    </td>
                    <td className="px-3 py-2">{row.certificationTypeName}</td>
                    <td className="px-3 py-2">{row.title}</td>
                    <td className="px-3 py-2">{row.certNo || '—'}</td>
                    <td className="px-3 py-2">{row.validUntil.slice(0, 10)}</td>
                    <td className="px-3 py-2">
                      <Badge tone={expiryStateTone(row.expiryState)}>
                        {expiryStateLabel(row.expiryState)}
                      </Badge>
                    </td>
                    <td className="px-3 py-2 text-muted-foreground">
                      SKU {row.skuCount} · 类目 {row.categoryCount} · 扫描件 {row.documentCount}
                    </td>
                    <td className="px-3 py-2">
                      {row.status === 'ACTIVE' && (
                        <Button variant="ghost" size="sm" onClick={() => void revoke(row)}>
                          撤销
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <div className="text-muted-foreground mt-2 flex items-center gap-2 text-copy-13">
              <span>
                共 {list.total} 张，当前第 {page + 1} 页
              </span>
              <Button
                variant="outline"
                size="sm"
                disabled={page === 0}
                onClick={() =>
                  void run(async () => {
                    const next = page - 1;
                    await loadList(next);
                    setPage(next);
                  })
                }
              >
                上一页
              </Button>
              <Button
                variant="outline"
                size="sm"
                disabled={(page + 1) * PAGE_SIZE >= list.total}
                onClick={() =>
                  void run(async () => {
                    const next = page + 1;
                    await loadList(next);
                    setPage(next);
                  })
                }
              >
                下一页
              </Button>
            </div>
          </>
        ) : (
          <EmptyState
            icon={<BadgeCheck className="h-6 w-6" />}
            title={emptyState('NO_CERTIFICATION').title}
            description={emptyState('NO_CERTIFICATION').description}
          />
        )}
      </SectionCard>

      {/* ---------------- 登记一张认证 ---------------- */}
      <SectionCard
        title="登记认证"
        description="认证类型来自生效词表（内置 ∪ 租户字典），有效期以带时区的时刻提交，服务端按 UTC 存 timestamptz。"
      >
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
          <Input
            placeholder="认证编号（租户内唯一）"
            value={newCode}
            onChange={(e) => setNewCode(e.target.value)}
          />
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={newType}
            onChange={(e) => setNewType(e.target.value)}
          >
            <option value="">选择认证类型</option>
            {enabledTypes.map((row) => (
              <option key={row.code} value={row.code}>
                {row.name}（{row.code}）
              </option>
            ))}
          </select>
          <Input
            placeholder="证书名称"
            value={newTitle}
            onChange={(e) => setNewTitle(e.target.value)}
          />
          <Input
            placeholder="证书编号"
            value={newCertNo}
            onChange={(e) => setNewCertNo(e.target.value)}
          />
          <Input
            placeholder="发证机构"
            value={newIssuer}
            onChange={(e) => setNewIssuer(e.target.value)}
          />
          <Input
            type="datetime-local"
            value={newValidFrom}
            onChange={(e) => setNewValidFrom(e.target.value)}
          />
          <Input
            type="datetime-local"
            value={newValidUntil}
            onChange={(e) => setNewValidUntil(e.target.value)}
          />
          <div className="flex items-end">
            <Button onClick={() => void submitCertification()} disabled={busy}>
              登记
            </Button>
          </div>
        </div>
      </SectionCard>

      {/* ---------------- 认证详情 ---------------- */}
      {detail && (
        <SectionCard
          title={`认证详情 · ${detail.code}`}
          description={`${detail.certificationTypeName} · ${detail.title} · ${statusLabel(detail.status)}`}
        >
          <div className="grid gap-4 lg:grid-cols-2">
            <div className="space-y-2">
              <h4 className="text-copy-13 text-foreground font-medium">适用 SKU</h4>
              {detail.skus.length > 0 ? (
                <ul className="space-y-1">
                  {detail.skus.map((row) => (
                    <li key={row.id} className="flex items-center gap-2 text-copy-13">
                      <span>{row.skuCode || row.skuId}</span>
                      <span className="text-muted-foreground">{row.skuName}</span>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => void detachSkuFromCertification(row.skuId)}
                      >
                        解挂
                      </Button>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-copy-13 text-muted-foreground">还没有挂任何 SKU。</p>
              )}
              <div className="flex flex-wrap items-center gap-2">
                <select
                  className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                  value={attachSkuId}
                  onChange={(e) => setAttachSkuId(e.target.value)}
                >
                  <option value="">选择 SKU</option>
                  {skuCandidates.map((row) => (
                    <option key={row.id} value={row.id}>
                      {row.code} · {row.name}
                    </option>
                  ))}
                </select>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => void attachSkuToCertification()}
                  disabled={busy}
                >
                  挂到这张认证
                </Button>
              </div>
            </div>

            <div className="space-y-2">
              <h4 className="text-copy-13 text-foreground font-medium">对应类目要求</h4>
              {detail.categories.length > 0 ? (
                <ul className="space-y-1">
                  {detail.categories.map((row) => (
                    <li key={row.id} className="flex items-center gap-2 text-copy-13">
                      <span>{row.categoryCode}</span>
                      <span className="text-muted-foreground">{row.categoryName}</span>
                      <span className="text-muted-foreground">{row.requirementNote}</span>
                      <Button
                        variant="ghost"
                        size="sm"
                        onClick={() => void detachCategoryFromCertification(row.categoryId)}
                      >
                        解挂
                      </Button>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="text-copy-13 text-muted-foreground">还没有挂对应类目要求。</p>
              )}
              <div className="flex flex-wrap items-center gap-2">
                <select
                  className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                  value={attachCategoryId}
                  onChange={(e) => setAttachCategoryId(e.target.value)}
                >
                  <option value="">选择类目</option>
                  {categoryCandidates.map((row) => (
                    <option key={row.id} value={row.id}>
                      {row.code} · {row.name}
                    </option>
                  ))}
                </select>
                <Button
                  variant="secondary"
                  size="sm"
                  onClick={() => void attachCategoryToCertification()}
                  disabled={busy}
                >
                  挂到这张认证
                </Button>
              </div>
            </div>
          </div>

          <div className="mt-4 space-y-2">
            <h4 className="text-copy-13 text-foreground font-medium">扫描件</h4>
            {detail.documents.length > 0 ? (
              <ul className="space-y-1">
                {detail.documents.map((row: CertificationDocumentRow) => (
                  <li key={row.id} className="text-copy-13 flex flex-wrap items-center gap-2">
                    <Badge tone="info">{documentKindLabel(row.documentKind)}</Badge>
                    <span>{row.documentName}</span>
                    <span className="text-muted-foreground">{row.mimeType}</span>
                    <span className="text-muted-foreground">{fileSizeText(Number(row.sizeBytes))}</span>
                    <span className="text-muted-foreground">{row.uploadedAt.slice(0, 10)}</span>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="text-copy-13 text-muted-foreground">
                还没有上传扫描件。扫描件真存进受管存储，元数据入库，续期是再上传一份而不是覆盖。
              </p>
            )}
            <div className="flex flex-wrap items-center gap-2">
              <select
                className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
                value={uploadKind}
                onChange={(e) => setUploadKind(e.target.value)}
              >
                <option value="ORIGINAL">原件</option>
                <option value="RENEWAL">续期件</option>
                <option value="ATTACHMENT">补充材料</option>
              </select>
              <Input
                className="w-48"
                placeholder="备注"
                value={uploadNote}
                onChange={(e) => setUploadNote(e.target.value)}
              />
              <label className="border-control-border bg-card hover:bg-muted inline-flex h-9 cursor-pointer items-center gap-2 rounded-md border px-4 text-btn-14">
                <FileUp className="h-4 w-4" />
                选择文件并上传
                <input
                  type="file"
                  className="hidden"
                  onChange={(e) => {
                    const file = e.target.files?.[0];
                    if (file) void upload(file);
                    e.target.value = '';
                  }}
                />
              </label>
              {caps && (
                <span className="text-muted-foreground text-copy-13">
                  经浏览器代理上传时单份上限约 {caps.browserProxyBase64LimitBytes} 字节
                </span>
              )}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 要求侧登记 ---------------- */}
      <SectionCard
        title="认证要求登记"
        description="登记「这个 SKU 需要这个认证类型」—— 没有要求侧就答不出「谁缺认证」。SKU 与类目来自 catalog。"
      >
        <div className="grid gap-2 sm:grid-cols-2 lg:grid-cols-4">
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={requirementSkuId}
            onChange={(e) => {
              const candidate = skuCandidates.find((row) => row.id === e.target.value);
              setRequirementSkuId(e.target.value);
              setRequirementSkuCode(candidate?.code ?? '');
              setRequirementSkuName(candidate?.name ?? '');
            }}
          >
            <option value="">选择 SKU</option>
            {skuCandidates.map((row) => (
              <option key={row.id} value={row.id}>
                {row.code} · {row.name}
              </option>
            ))}
          </select>
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={requirementType}
            onChange={(e) => setRequirementType(e.target.value)}
          >
            <option value="">需要的认证类型</option>
            {enabledTypes.map((row) => (
              <option key={row.code} value={row.code}>
                {row.name}（{row.code}）
              </option>
            ))}
          </select>
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={requirementCategoryId}
            onChange={(e) => {
              const candidate = categoryCandidates.find((row) => row.id === e.target.value);
              setRequirementCategoryId(e.target.value);
              setRequirementCategoryCode(candidate?.code ?? '');
              setRequirementCategoryName(candidate?.name ?? '');
            }}
          >
            <option value="">来自哪个类目要求（可空）</option>
            {categoryCandidates.map((row) => (
              <option key={row.id} value={row.id}>
                {row.code} · {row.name}
              </option>
            ))}
          </select>
          <div className="flex items-end">
            <Button onClick={() => void submitRequirement()} disabled={busy}>
              登记要求
            </Button>
          </div>
        </div>

        {requirements && requirements.items.length > 0 ? (
          <table className="mt-3 w-full text-copy-13">
            <thead className="text-muted-foreground">
              <tr className="border-border border-b text-left">
                <th className="px-3 py-2 font-medium">SKU</th>
                <th className="px-3 py-2 font-medium">需要的认证类型</th>
                <th className="px-3 py-2 font-medium">来自类目</th>
                <th className="px-3 py-2 font-medium">操作</th>
              </tr>
            </thead>
            <tbody>
              {requirements.items.map((row) => (
                <tr key={row.id} className="border-border border-b last:border-0">
                  <td className="px-3 py-2">
                    {row.skuCode || row.skuId}
                    <span className="text-muted-foreground ml-1">{row.skuName}</span>
                  </td>
                  <td className="px-3 py-2">{row.certificationTypeName}</td>
                  <td className="px-3 py-2">{row.categoryName || row.categoryCode || '—'}</td>
                  <td className="px-3 py-2">
                    <Button variant="ghost" size="sm" onClick={() => void removeRequirement(row.id)}>
                      撤销登记
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        ) : (
          <p className="text-muted-foreground mt-3 text-copy-13">
            还没有登记认证要求。先「拉取 catalog 候选」，再登记「哪个 SKU 需要哪个认证类型」。
          </p>
        )}
      </SectionCard>

      {/* ---------------- 认证类型词表 ---------------- */}
      <SectionCard
        title="认证类型词表"
        description="生效词表 = 内置类型 ∪ 本租户字典（租户行优先，可改名、可停用）。停用只影响新登记。"
      >
        <div className="grid gap-2 sm:grid-cols-4">
          <Input
            placeholder="编码（CE / FCC / UL …）"
            value={newTypeCode}
            onChange={(e) => setNewTypeCode(e.target.value)}
          />
          <Input
            placeholder="名称"
            value={newTypeName}
            onChange={(e) => setNewTypeName(e.target.value)}
          />
          <select
            className="border-control-border bg-card h-9 rounded-md border px-2 text-copy-13"
            value={newTypeScope}
            onChange={(e) => setNewTypeScope(e.target.value)}
          >
            <option value="GLOBAL">全球通用</option>
            <option value="MARKETPLACE">平台 / 市场特定</option>
          </select>
          <div className="flex items-end">
            <Button onClick={() => void submitType()} disabled={busy}>
              保存到租户字典
            </Button>
          </div>
        </div>

        <table className="mt-3 w-full text-copy-13">
          <thead className="text-muted-foreground">
            <tr className="border-border border-b text-left">
              <th className="px-3 py-2 font-medium">编码</th>
              <th className="px-3 py-2 font-medium">名称</th>
              <th className="px-3 py-2 font-medium">适用范围</th>
              <th className="px-3 py-2 font-medium">来源</th>
              <th className="px-3 py-2 font-medium">状态</th>
              <th className="px-3 py-2 font-medium">操作</th>
            </tr>
          </thead>
          <tbody>
            {types.map((row) => (
              <tr key={row.code} className="border-border border-b last:border-0">
                <td className="px-3 py-2">{row.code}</td>
                <td className="px-3 py-2">{row.name}</td>
                <td className="px-3 py-2">
                  {row.scope === 'GLOBAL' ? '全球通用' : '平台 / 市场特定'}
                </td>
                <td className="px-3 py-2">{row.tenantDefined ? '本租户自定义' : '内置'}</td>
                <td className="px-3 py-2">
                  <Badge tone={row.enabled ? 'success' : 'neutral'}>
                    {row.enabled ? '启用' : '停用'}
                  </Badge>
                </td>
                <td className="px-3 py-2">
                  <Button variant="ghost" size="sm" onClick={() => void toggleType(row)}>
                    {row.enabled ? '停用' : '启用'}
                  </Button>
                  {row.tenantDefined && (
                    <Button variant="ghost" size="sm" onClick={() => void removeType(row)}>
                      删除
                    </Button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </SectionCard>

      {/* ---------------- 能力声明 ---------------- */}
      {caps && (
        <SectionCard title="能力声明" description="口径与边界由后端给出，页面原样显示。">
          <dl className="space-y-2 text-copy-13">
            <div>
              <dt className="text-foreground font-medium">到期状态口径</dt>
              <dd className="text-muted-foreground">{caps.expirySemantics}</dd>
            </div>
            <div>
              <dt className="text-foreground font-medium">覆盖统计口径</dt>
              <dd className="text-muted-foreground">{caps.coverageSemantics}</dd>
            </div>
            <div>
              <dt className="text-foreground font-medium">认证类型词表</dt>
              <dd className="text-muted-foreground">{caps.vocabularyNote}</dd>
            </div>
            <div>
              <dt className="text-foreground font-medium">上游</dt>
              <dd className="text-muted-foreground">{caps.upstreamNote}</dd>
            </div>
            <div>
              <dt className="text-foreground font-medium">边界</dt>
              <dd className="text-muted-foreground">{caps.boundaryNote}</dd>
            </div>
          </dl>
        </SectionCard>
      )}
    </div>
  );
}
