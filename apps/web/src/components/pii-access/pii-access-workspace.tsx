'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import {
  CircleHelp,
  ClipboardCheck,
  Eye,
  EyeOff,
  FileDown,
  History,
  Loader2,
  RefreshCw,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  AUDIT_ACTIONS,
  AUDIT_DECISIONS,
  EXPORT_STATUSES,
  FIELDS,
  SCOPE_MODES,
  SUBJECT_KINDS,
  actorLabel,
  approveExport,
  auditActionLabel,
  auditDecisionLabel,
  auditDecisionTone,
  createExport,
  decisionFor,
  emptyState,
  executeExport,
  exportStatusLabel,
  exportStatusTone,
  fetchAudit,
  fetchCapabilities,
  fetchExports,
  fetchGrants,
  fetchPolicy,
  fetchSubject,
  fetchSubjects,
  fieldText,
  grantReason,
  isFull,
  rejectExport,
  revealableFields,
  revokeGrant,
  saveGrant,
  savePolicy,
  subjectSummary,
  valueStateLabel,
  valueStateTone,
  type PiiAccessAuditPage,
  type PiiAccessCapabilities,
  type PiiAccessExportPage,
  type PiiAccessGrant,
  type PiiAccessGrantPage,
  type PiiAccessPolicy,
  type PiiAccessSubjectDetail,
  type PiiAccessSubjectPage,
} from './pii-access-model';

/**
 * 16.6.1.1 PII 管理 工作区。
 *
 * 蓝图：「管理个人信息（买家姓名、地址、电话、邮箱）的访问与脱敏：默认脱敏展示、
 * 按角色授权查看完整信息、访问留痕、导出审批。输入：PII 访问请求；授权配置；导出申请。
 * 输出：脱敏数据或授权后的完整数据；访问审计记录。
 * 关系：上游 = 15 客户、9 订单；下游 = 客服与运营页面。
 * **完整地址查看需留痕**——批量导出 PII 是数据泄露事件的典型形态。」
 *
 * 🔴 这个页面上最容易做丢的五件事，这里都挡住了：
 *
 * 1. **脱敏态与完整态一眼能分**（徽章写明「已脱敏」/「完整值」，按后端给的 `masked` 渲染）。
 * 2. **「上游没给」与「空值」分开显示** —— `present=false` 显示「上游没给这一栏」。
 * 3. **「谁授权、为什么能看到」显示出来**（角色 + 范围 + 理由）。
 * 4. **导出的三段式在界面上是三段式**：申请 → 审批 → 执行，
 *    审批按钮在自己申请的单上**不出现**（审批人 ≠ 申请人）。
 * 5. **未交付的下游不编造**：16.6.2.1 数据出境与留存未交付，页面上没有它的入口。
 */
export function PiiAccessWorkspace() {
  const [caps, setCaps] = useState<PiiAccessCapabilities | null>(null);
  const [policy, setPolicy] = useState<PiiAccessPolicy | null>(null);

  const [kind, setKind] = useState('ORDER');
  const [keyword, setKeyword] = useState('');
  const [subjects, setSubjects] = useState<PiiAccessSubjectPage | null>(null);
  const [detail, setDetail] = useState<PiiAccessSubjectDetail | null>(null);
  const [revealReason, setRevealReason] = useState('');

  const [grants, setGrants] = useState<PiiAccessGrantPage | null>(null);
  const [grantRole, setGrantRole] = useState('');
  const [grantField, setGrantField] = useState('ADDRESS');
  const [grantScope, setGrantScope] = useState('ALL');
  const [grantNote, setGrantNote] = useState('');

  const [exports, setExports] = useState<PiiAccessExportPage | null>(null);
  const [exportStatus, setExportStatus] = useState('');
  const [exportKind, setExportKind] = useState('');
  const [exportField, setExportField] = useState('ADDRESS');
  const [exportPurpose, setExportPurpose] = useState('');
  const [selectedRefs, setSelectedRefs] = useState<string[]>([]);

  const [audit, setAudit] = useState<PiiAccessAuditPage | null>(null);
  const [auditAction, setAuditAction] = useState('');
  const [auditDecision, setAuditDecision] = useState('');

  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const PAGE_SIZE = 10;

  const loadSubjects = useCallback(async (nextKind: string, nextKeyword: string) => {
    setSubjects(await fetchSubjects(nextKind, nextKeyword, 0, PAGE_SIZE));
  }, []);

  const loadGrants = useCallback(async (role: string) => {
    setGrants(await fetchGrants(role, '', 0, PAGE_SIZE));
  }, []);

  const loadExports = useCallback(async (status: string, nextKind: string) => {
    setExports(await fetchExports(status, nextKind, 0, PAGE_SIZE));
  }, []);

  const loadAudit = useCallback(async (action: string, decision: string) => {
    setAudit(await fetchAudit('', '', action, decision, 0, PAGE_SIZE));
  }, []);

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, policyResult] = await Promise.all([fetchCapabilities(), fetchPolicy()]);
      setCaps(capsResult);
      setPolicy(policyResult);
      await loadSubjects(kind, keyword);
      await loadGrants(grantRole);
      await loadExports(exportStatus, exportKind);
      await loadAudit(auditAction, auditDecision);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [
    kind,
    keyword,
    grantRole,
    exportStatus,
    exportKind,
    auditAction,
    auditDecision,
    loadSubjects,
    loadGrants,
    loadExports,
    loadAudit,
  ]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ---------------- 策略 ----------------

  const submitPolicy = useCallback(async () => {
    if (!policy) return;
    setBusy(true);
    setError(null);
    try {
      const saved = await savePolicy({
        policyName: policy.policyName || 'PII 访问策略',
        maxExportRecords: policy.maxExportRecords,
        exportApprovalTtlHours: policy.exportApprovalTtlHours,
        maxRevealPerDay: policy.maxRevealPerDay,
        requireReasonOnReveal: policy.requireReasonOnReveal,
        version: policy.version,
      });
      setPolicy(saved);
      setNotice('策略已保存。🔴 脱敏与导出审批是**硬规则**，不在这张表里 —— 任何配置都关不掉它们。');
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存策略失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [policy]);

  // ---------------- 主体 ----------------

  const openSubject = useCallback(
    async (subjectKind: string, ref: string) => {
      setError(null);
      try {
        setDetail(await fetchSubject(subjectKind, ref));
        setRevealReason('');
      } catch (e) {
        setError(e instanceof Error ? e.message : '加载主体失败，请重试');
      }
    },
    [],
  );

  const reveal = useCallback(
    async (fieldCode: string) => {
      if (!detail) return;
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        setDetail(
          await fetchSubject(detail.subjectKind, detail.subjectRef, true, [fieldCode], revealReason),
        );
        setNotice(`已按授权返回「${fieldLabelOf(fieldCode)}」的完整值。🔴 这次查看已留痕（谁、何时、哪一栏）。`);
      } catch (e) {
        setError(e instanceof Error ? e.message : '查看完整值失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [detail, revealReason],
  );

  // ---------------- 授权配置 ----------------

  const submitGrant = useCallback(async () => {
    setBusy(true);
    setError(null);
    try {
      const saved = await saveGrant({
        roleCode: grantRole.trim(),
        fieldCode: grantField,
        scopeMode: grantScope,
        reasonRequired: true,
        effectiveFrom: new Date().toISOString(),
        effectiveTo: null,
        note: grantNote,
      });
      setNotice(
        `授权已登记：角色「${saved.roleCode}」可以查看「${saved.fieldLabel}」的完整值（范围：${saved.scopeLabel}）。`,
      );
      setGrantNote('');
      await loadGrants(grantRole);
    } catch (e) {
      setError(e instanceof Error ? e.message : '登记授权失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [grantRole, grantField, grantScope, grantNote, loadGrants]);

  const doRevokeGrant = useCallback(
    async (grant: PiiAccessGrant) => {
      const reason = window.prompt('撤销理由（必填）：');
      if (!reason || !reason.trim()) return;
      setBusy(true);
      setError(null);
      try {
        await revokeGrant(grant.grantId, reason.trim());
        setNotice('授权已撤销。🔴 撤销之后再看完整值会被拒 —— 撤销理由会留在授权配置上。');
        await loadGrants(grantRole);
      } catch (e) {
        setError(e instanceof Error ? e.message : '撤销授权失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [grantRole, loadGrants],
  );

  // ---------------- 导出 ----------------

  const submitExport = useCallback(async () => {
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      const created = await createExport({
        subjectKind: exportKind || kind,
        fieldCode: exportField,
        recordCount: selectedRefs.length,
        purpose: exportPurpose.trim(),
        subjectRefs: selectedRefs,
      });
      setNotice(
        `导出申请已提交（${created.requestNo}，${created.recordCount} 条）。🔴 **必须由另一个成员审批**，自己批自己的审批形同虚设。`,
      );
      setExportPurpose('');
      setSelectedRefs([]);
      await loadExports(exportStatus, exportKind);
    } catch (e) {
      setError(e instanceof Error ? e.message : '提交导出申请失败，请重试');
    } finally {
      setBusy(false);
    }
  }, [exportKind, kind, exportField, exportPurpose, selectedRefs, exportStatus, loadExports]);

  const doApprove = useCallback(
    async (requestId: string) => {
      const note = window.prompt('审批意见（可选）：') ?? '';
      setBusy(true);
      setError(null);
      try {
        await approveExport(requestId, note.trim());
        setNotice('已批准。批准后必须在有效期内执行，超时作废。');
        await loadExports(exportStatus, exportKind);
      } catch (e) {
        setError(e instanceof Error ? e.message : '审批失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [exportStatus, exportKind, loadExports],
  );

  const doReject = useCallback(
    async (requestId: string) => {
      const note = window.prompt('驳回理由（必填）：');
      if (!note || !note.trim()) return;
      setBusy(true);
      setError(null);
      try {
        await rejectExport(requestId, note.trim());
        setNotice('已驳回。');
        await loadExports(exportStatus, exportKind);
      } catch (e) {
        setError(e instanceof Error ? e.message : '驳回失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [exportStatus, exportKind, loadExports],
  );

  const doExecute = useCallback(
    async (requestId: string) => {
      setBusy(true);
      setError(null);
      setNotice(null);
      try {
        const result = await executeExport(requestId);
        setNotice(
          `导出完成：${result.exportNo}，共 ${result.recordCount} 条。🔴 导出已留痕（谁、何时、导出什么、审批单号）。`,
        );
        await loadExports(exportStatus, exportKind);
        await loadAudit(auditAction, auditDecision);
      } catch (e) {
        setError(e instanceof Error ? e.message : '执行导出失败，请重试');
      } finally {
        setBusy(false);
      }
    },
    [exportStatus, exportKind, auditAction, auditDecision, loadExports, loadAudit],
  );

  const toggleRef = useCallback((ref: string) => {
    setSelectedRefs((prev) => (prev.includes(ref) ? prev.filter((r) => r !== ref) : [...prev, ref]));
  }, []);

  const policyLimit = useMemo(() => {
    const configured = policy?.configured ? policy.maxExportRecords : 100;
    return Math.max(1, configured);
  }, [policy]);

  if (loading) {
    return (
      <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
        <Loader2 className="h-4 w-4 animate-spin" />
        正在加载 PII 管理…
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="PII 管理"
        description="买家姓名 / 地址 / 电话 / 邮箱的访问与脱敏：默认脱敏展示、按角色授权查看完整信息、访问留痕、导出审批。"
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-start gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
        </div>
      )}
      {notice && (
        <div className="border-info-soft bg-info-soft text-info flex items-start gap-2 rounded-lg border px-4 py-3">
          <CircleHelp className="mt-0.5 h-4 w-4 shrink-0" />
          <span className="text-copy-13 whitespace-pre-line">{notice}</span>
        </div>
      )}

      {/* ---------------- 概览 ---------------- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard label="访问留痕" value={audit?.counts.total ?? 0} polarity="neutral" />
        <StatCard label="完整值查看" value={audit?.counts.viewFull ?? 0} polarity="neutral" />
        <StatCard
          label="越权 / 被拒尝试"
          value={audit?.counts.denied ?? 0}
          polarity="negative"
        />
        <StatCard label="待审批导出" value={exports?.counts.pending ?? 0} polarity="neutral" />
      </div>

      {/* ---------------- 能力声明 ---------------- */}
      {caps && (
        <SectionCard
          title="能力声明"
          description="脱敏规则、授权模型、留痕与导出规则 —— 界面上显示的每一句口径都来自这里。"
        >
          <div className="space-y-4">
            <div className="grid gap-2 md:grid-cols-2">
              {caps.fields.map((f) => (
                <div key={f.fieldCode} className="border-border rounded-md border px-3 py-2">
                  <div className="flex items-center justify-between gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{f.fieldLabel}</span>
                    <Badge tone="neutral">{f.fieldCode}</Badge>
                  </div>
                  <p className="text-copy-12 text-muted-foreground mt-1">{f.maskRule}</p>
                </div>
              ))}
            </div>
            <details className="text-copy-12 text-muted-foreground">
              <summary className="cursor-pointer">口径说明（脱敏 / 授权 / 留痕 / 导出 / 存储 / 边界）</summary>
              <div className="mt-2 space-y-2">
                <p>{caps.maskingSemantics}</p>
                <p>{caps.authorizationSemantics}</p>
                <p>{caps.auditSemantics}</p>
                <p>{caps.exportSemantics}</p>
                <p>{caps.storageSemantics}</p>
                <p>{caps.downstreamNote}</p>
                <p>{caps.boundaryNote}</p>
              </div>
            </details>
            <div className="flex flex-wrap gap-2">
              {caps.sources.map((s) => (
                <Badge key={s.sourceCode} tone={s.available ? 'info' : 'warning'}>
                  {s.sourceLabel}：{s.available ? '可读' : '读不到'}
                </Badge>
              ))}
            </div>
          </div>
        </SectionCard>
      )}

      {/* ---------------- 策略 ---------------- */}
      <SectionCard
        title="访问与导出策略"
        description="只管额度：脱敏与导出审批是硬规则，不在这张表里，关不掉。"
        extra={
          <Button size="sm" variant="outline" onClick={submitPolicy} disabled={busy || !policy}>
            保存策略
          </Button>
        }
      >
        {!policy?.configured ? (
          <EmptyState
            icon={<ShieldAlert className="h-6 w-6" />}
            title={emptyState('NO_POLICY').title}
            description={emptyState('NO_POLICY').description}
          />
        ) : (
          <div className="grid gap-3 md:grid-cols-4">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>策略名</span>
              <Input
                value={policy.policyName}
                onChange={(e) => setPolicy({ ...policy, policyName: e.target.value })}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>单次导出条数上限</span>
              <Input
                type="number"
                min={1}
                max={500}
                value={policy.maxExportRecords}
                onChange={(e) =>
                  setPolicy({ ...policy, maxExportRecords: Number(e.target.value) || 1 })
                }
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>审批有效期（小时）</span>
              <Input
                type="number"
                min={1}
                max={720}
                value={policy.exportApprovalTtlHours}
                onChange={(e) =>
                  setPolicy({ ...policy, exportApprovalTtlHours: Number(e.target.value) || 1 })
                }
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>每日完整值查看上限（0 = 不限）</span>
              <Input
                type="number"
                min={0}
                value={policy.maxRevealPerDay}
                onChange={(e) =>
                  setPolicy({ ...policy, maxRevealPerDay: Number(e.target.value) || 0 })
                }
              />
            </label>
            <label className="text-copy-13 text-foreground md:col-span-4 space-y-1">
              <span>查看完整值是否必须写理由</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={policy.requireReasonOnReveal ? 'yes' : 'no'}
                onChange={(e) =>
                  setPolicy({ ...policy, requireReasonOnReveal: e.target.value === 'yes' })
                }
              >
                <option value="yes">必须写理由</option>
                <option value="no">可以不写</option>
              </select>
            </label>
          </div>
        )}
      </SectionCard>

      {/* ---------------- PII 主体 ---------------- */}
      <SectionCard
        title="PII 主体"
        description="默认脱敏展示。要看完整值必须走一次授权判定，且每一次查看都会留痕。"
        extra={
          <Button
            size="sm"
            variant="outline"
            onClick={() => void loadSubjects(kind, keyword)}
            disabled={busy}
          >
            <RefreshCw className="mr-1 h-3.5 w-3.5" />
            刷新
          </Button>
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-3">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>来源</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={kind}
                onChange={(e) => {
                  setKind(e.target.value);
                  void loadSubjects(e.target.value, keyword);
                }}
              >
                {SUBJECT_KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-2">
              <span>搜索</span>
              <Input
                value={keyword}
                placeholder={kind === 'ORDER' ? '订单号' : '买家标识'}
                onChange={(e) => setKeyword(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === 'Enter') void loadSubjects(kind, keyword);
                }}
              />
            </label>
            <div className="text-copy-13 text-muted-foreground flex items-end">
              共 {subjects?.total ?? 0} 个主体
            </div>
          </div>

          {!subjects || subjects.items.length === 0 ? (
            <EmptyState
              icon={<EyeOff className="h-6 w-6" />}
              title={emptyState('NO_SUBJECT').title}
              description={emptyState('NO_SUBJECT').description}
            />
          ) : (
            <div className="space-y-2">
              {subjects.items.map((view) => (
                <button
                  key={`${view.subjectKind}:${view.subjectRef}`}
                  type="button"
                  className="border-border hover:bg-muted/40 flex w-full flex-col gap-1 rounded-md border px-3 py-2 text-left"
                  onClick={() => void openSubject(view.subjectKind, view.subjectRef)}
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">
                      {view.known ? view.subjectLabel : view.subjectRef}
                    </span>
                    <Badge tone="info">{view.sourceLabel}</Badge>
                    {!view.known && <Badge tone="warning">查无此人</Badge>}
                  </div>
                  <span className="text-copy-12 text-muted-foreground">{subjectSummary(view)}</span>
                </button>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 主体详情 ---------------- */}
      {detail && (
        <SectionCard
          title="主体详情"
          description={`${detail.subjectKind} · ${detail.subjectRef}`}
          extra={
            <Button size="sm" variant="outline" onClick={() => setDetail(null)}>
              关闭
            </Button>
          }
        >
          {!detail.known ? (
            <EmptyState
              icon={<EyeOff className="h-6 w-6" />}
              title={emptyState('UNKNOWN_SUBJECT').title}
              description={emptyState('UNKNOWN_SUBJECT').description}
            />
          ) : (
            <div className="space-y-4">
              <div className="grid gap-2 md:grid-cols-2">
                {detail.fields.map((f) => {
                  const decision = decisionFor(detail, f.fieldCode);
                  return (
                    <div key={f.fieldCode} className="border-border rounded-md border px-3 py-2">
                      <div className="flex flex-wrap items-center justify-between gap-2">
                        <span className="text-copy-13 text-foreground font-medium">{f.label}</span>
                        <Badge tone={valueStateTone(detail, f.fieldCode)}>
                          {valueStateLabel(detail, f.fieldCode)}
                        </Badge>
                      </div>
                      <p className="text-copy-13 text-foreground mt-1 break-all">
                        {fieldText(detail, f.fieldCode)}
                      </p>
                      {decision && (
                        <p className="text-copy-12 text-muted-foreground mt-1">
                          {decision.allowed
                            ? grantReason(detail, f.fieldCode)
                            : `未授权：${decision.reason}`}
                        </p>
                      )}
                      {f.present && f.masked && (
                        <div className="mt-2 flex flex-wrap items-center gap-2">
                          <Button
                            size="sm"
                            variant="outline"
                            disabled={busy}
                            onClick={() => void reveal(f.fieldCode)}
                          >
                            <Eye className="mr-1 h-3.5 w-3.5" />
                            查看完整值
                          </Button>
                          {decision && !decision.allowed && (
                            <span className="text-copy-12 text-muted-foreground">
                              需要一条「{f.label}」的授权配置
                            </span>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>

              <div className="border-border space-y-2 rounded-md border px-3 py-2">
                <span className="text-copy-13 text-foreground font-medium">查看完整值的理由</span>
                <Input
                  value={revealReason}
                  placeholder="例如：买家来电要求核对收货地址"
                  onChange={(e) => setRevealReason(e.target.value)}
                />
                <p className="text-copy-12 text-muted-foreground">
                  {detail.revealNote}
                  {revealableFields(detail).length > 0 &&
                    ` · 还能查看完整值的栏：${revealableFields(detail)
                      .map((c) => fieldLabelOf(c))
                      .join('、')}`}
                </p>
              </div>
            </div>
          )}
        </SectionCard>
      )}

      {/* ---------------- 授权配置 ---------------- */}
      <SectionCard
        title="授权配置"
        description="按角色 × 字段授权查看完整值，并限定成员范围。撤销要写理由。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>角色 code</span>
              <Input
                value={grantRole}
                placeholder="例如 SUPPORT"
                onChange={(e) => setGrantRole(e.target.value)}
              />
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>字段</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={grantField}
                onChange={(e) => setGrantField(e.target.value)}
              >
                {FIELDS.map((f) => (
                  <option key={f.value} value={f.value}>
                    {f.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>成员范围</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={grantScope}
                onChange={(e) => setGrantScope(e.target.value)}
              >
                {SCOPE_MODES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="flex items-end">
              <Button size="sm" onClick={submitGrant} disabled={busy || !grantRole.trim()}>
                登记授权
              </Button>
            </div>
          </div>

          {!grants || grants.items.length === 0 ? (
            <EmptyState
              icon={<ClipboardCheck className="h-6 w-6" />}
              title="还没有授权配置"
              description="没有授权配置时，看完整值一律被拒 —— 界面只会显示脱敏值，不会退化成明文。"
            />
          ) : (
            <div className="space-y-2">
              {grants.items.map((g) => (
                <div
                  key={g.grantId}
                  className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
                >
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-copy-13 text-foreground font-medium">{g.roleCode}</span>
                    <Badge tone="info">{g.fieldLabel}</Badge>
                    <Badge tone="neutral">{g.scopeLabel}</Badge>
                    <Badge tone={g.status === 'ACTIVE' ? 'success' : 'danger'}>
                      {g.status === 'ACTIVE' ? '生效中' : '已撤销'}
                    </Badge>
                  </div>
                  <div className="flex items-center gap-2">
                    {g.status === 'ACTIVE' && (
                      <Button size="sm" variant="outline" disabled={busy} onClick={() => void doRevokeGrant(g)}>
                        撤销
                      </Button>
                    )}
                    {g.revokeReason && (
                      <span className="text-copy-12 text-muted-foreground">撤销理由：{g.revokeReason}</span>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 导出申请 ---------------- */}
      <SectionCard
        title="导出申请"
        description="申请 → 审批 → 执行。审批人不能是申请人本人，导出对象必须逐条列明，没有「一键全量导出」。"
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-4">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>主体类型</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={exportKind}
                onChange={(e) => setExportKind(e.target.value)}
              >
                <option value="">跟随当前来源</option>
                {SUBJECT_KINDS.map((k) => (
                  <option key={k.value} value={k.value}>
                    {k.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>导出字段</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={exportField}
                onChange={(e) => setExportField(e.target.value)}
              >
                {FIELDS.map((f) => (
                  <option key={f.value} value={f.value}>
                    {f.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-2">
              <span>导出理由（必填）</span>
              <Input
                value={exportPurpose}
                placeholder="例如：批量发货异常排查"
                onChange={(e) => setExportPurpose(e.target.value)}
              />
            </label>
            <div className="flex items-end">
              <Button
                size="sm"
                onClick={submitExport}
                disabled={busy || selectedRefs.length === 0 || !exportPurpose.trim()}
              >
                <FileDown className="mr-1 h-3.5 w-3.5" />
                提交申请（{selectedRefs.length} 条）
              </Button>
            </div>
          </div>

          <div className="border-border rounded-md border px-3 py-2">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-copy-13 text-foreground font-medium">导出对象（逐条列明）</span>
              <span className="text-copy-12 text-muted-foreground">
                已选 {selectedRefs.length} 条 · 单次上限 {policyLimit} 条
              </span>
            </div>
            {!subjects || subjects.items.length === 0 ? (
              <p className="text-copy-12 text-muted-foreground mt-2">
                先在上面「PII 主体」里挑对象 —— 这里列的是**主体标识**，不是 PII 明文。
              </p>
            ) : (
              <div className="mt-2 flex flex-wrap gap-2">
                {subjects.items.map((view) => (
                  <button
                    key={view.subjectRef}
                    type="button"
                    onClick={() => toggleRef(view.subjectRef)}
                    className={`border-border rounded-md border px-2 py-1 text-copy-12 ${
                      selectedRefs.includes(view.subjectRef)
                        ? 'bg-brand-soft text-brand'
                        : 'text-muted-foreground'
                    }`}
                  >
                    {view.subjectLabel}
                  </button>
                ))}
              </div>
            )}
          </div>

          <div className="grid gap-3 md:grid-cols-2">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>按状态筛选</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={exportStatus}
                onChange={(e) => {
                  setExportStatus(e.target.value);
                  void loadExports(e.target.value, exportKind);
                }}
              >
                <option value="">全部</option>
                {EXPORT_STATUSES.map((s) => (
                  <option key={s.value} value={s.value}>
                    {s.label}
                  </option>
                ))}
              </select>
            </label>
            <div className="text-copy-13 text-muted-foreground flex items-end">
              待审批 {exports?.counts.pending ?? 0} · 已批准 {exports?.counts.approved ?? 0} · 已导出{' '}
              {exports?.counts.executed ?? 0}
            </div>
          </div>

          {!exports || exports.items.length === 0 ? (
            <EmptyState
              icon={<FileDown className="h-6 w-6" />}
              title={emptyState('NO_EXPORT').title}
              description={emptyState('NO_EXPORT').description}
            />
          ) : (
            <div className="space-y-2">
              {exports.items.map((r) => (
                <div
                  key={r.requestId}
                  className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
                >
                  <div className="min-w-0 space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <span className="text-copy-13 text-foreground font-medium">{r.requestNo}</span>
                      <Badge tone={exportStatusTone(r.status)}>{exportStatusLabel(r.status)}</Badge>
                      <Badge tone="info">{r.fieldLabel}</Badge>
                      <Badge tone="neutral">{r.recordCount} 条</Badge>
                    </div>
                    <p className="text-copy-12 text-muted-foreground">
                      理由：{r.purpose} · 申请人 {actorLabel(r.requestedBy)}
                      {r.approvedBy ? ` · 审批人 ${actorLabel(r.approvedBy)}` : ''}
                      {r.decisionNote ? ` · 审批意见：${r.decisionNote}` : ''}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    {r.status === 'PENDING' && (
                      <>
                        <Button size="sm" variant="outline" disabled={busy} onClick={() => void doReject(r.requestId)}>
                          驳回
                        </Button>
                        <Button size="sm" disabled={busy} onClick={() => void doApprove(r.requestId)}>
                          批准
                        </Button>
                      </>
                    )}
                    {r.status === 'APPROVED' && (
                      <Button size="sm" disabled={busy} onClick={() => void doExecute(r.requestId)}>
                        <FileDown className="mr-1 h-3.5 w-3.5" />
                        执行导出
                      </Button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------- 访问审计 ---------------- */}
      <SectionCard
        title="访问审计"
        description="脱敏展示、完整值查看、越权尝试与导出都逐条留痕 —— 审计表是只增不改的。"
        extra={
          <Button
            size="sm"
            variant="outline"
            onClick={() => void loadAudit(auditAction, auditDecision)}
            disabled={busy}
          >
            <History className="mr-1 h-3.5 w-3.5" />
            刷新
          </Button>
        }
      >
        <div className="space-y-4">
          <div className="grid gap-3 md:grid-cols-2">
            <label className="text-copy-13 text-foreground space-y-1">
              <span>动作</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={auditAction}
                onChange={(e) => {
                  setAuditAction(e.target.value);
                  void loadAudit(e.target.value, auditDecision);
                }}
              >
                <option value="">全部</option>
                {AUDIT_ACTIONS.map((a) => (
                  <option key={a.value} value={a.value}>
                    {a.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="text-copy-13 text-foreground space-y-1">
              <span>判定</span>
              <select
                className="border-control-border bg-card text-copy-14 text-foreground h-9 w-full rounded-md border px-3"
                value={auditDecision}
                onChange={(e) => {
                  setAuditDecision(e.target.value);
                  void loadAudit(auditAction, e.target.value);
                }}
              >
                <option value="">全部</option>
                {AUDIT_DECISIONS.map((d) => (
                  <option key={d.value} value={d.value}>
                    {d.label}
                  </option>
                ))}
              </select>
            </label>
          </div>

          {!audit || audit.items.length === 0 ? (
            <EmptyState
              icon={<History className="h-6 w-6" />}
              title={emptyState('NO_AUDIT').title}
              description={emptyState('NO_AUDIT').description}
            />
          ) : (
            <div className="space-y-2">
              {audit.items.map((row) => (
                <div
                  key={row.auditId}
                  className="border-border flex flex-wrap items-center justify-between gap-2 rounded-md border px-3 py-2"
                >
                  <div className="min-w-0 space-y-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <Badge tone={auditDecisionTone(row.decision)}>
                        {auditDecisionLabel(row.decision)}
                      </Badge>
                      <Badge tone="neutral">{auditActionLabel(row.action)}</Badge>
                      <Badge tone="info">{row.fieldCode}</Badge>
                      <span className="text-copy-13 text-foreground">{row.subjectLabel}</span>
                    </div>
                    <p className="text-copy-12 text-muted-foreground">
                      {actorLabel(row.actorId)}
                      {row.grantRoleCode ? ` · 依据角色「${row.grantRoleCode}」` : ''}
                      {row.reason ? ` · 理由：${row.reason}` : ''}
                      {row.decisionReason ? ` · ${row.decisionReason}` : ''}
                    </p>
                  </div>
                  <span className="text-copy-12 text-muted-foreground">{row.occurredAt}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      </SectionCard>
    </div>
  );
}

/** 字段码 → 中文标签（与后端 `PiiAccessRules.fieldLabel` 同一套词）。 */
function fieldLabelOf(fieldCode: string): string {
  return FIELDS.find((f) => f.value === fieldCode)?.label ?? fieldCode;
}
