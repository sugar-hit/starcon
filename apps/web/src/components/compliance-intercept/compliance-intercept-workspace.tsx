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
  Gavel,
  Loader2,
  RefreshCw,
  ScrollText,
  ShieldCheck,
  ShieldAlert,
  TriangleAlert,
} from 'lucide-react';
import {
  CONCLUSION_OPTIONS,
  EXEMPTION_STATUS_OPTIONS,
  SCENE_OPTIONS,
  SUBJECT_TYPE_OPTIONS,
  applyExemption,
  approveExemption,
  blockingReasons,
  conclusionLabel,
  conclusionTone,
  decisionEmptyKind,
  decisionEmptyState,
  exemptionEmptyKind,
  exemptionEmptyState,
  fetchCapabilities,
  fetchDecisions,
  fetchExemptions,
  fetchPolicy,
  rejectExemption,
  savePolicy,
  subjectText,
  undecidableReasons,
  validityText,
  waivedReasons,
  type ComplianceInterceptCapabilities,
  type ComplianceInterceptDecision,
  type ComplianceInterceptDecisionPage,
  type ComplianceInterceptExemptionPage,
  type ComplianceInterceptExemptionRow,
  type ComplianceInterceptPolicySet,
} from './compliance-intercept-model';

/**
 * 16.2.1.2 拦截执行 工作区。
 *
 * 蓝图：「在关键业务节点执行合规校验并阻断：刊登发布前、发货前、结算提现前；
 * 展示拦截原因与解除方式（提交资质、申请豁免）。输入：合规规则集；业务动作触发的校验请求（商品/订单/店铺）。
 * 输出：校验结论（通过/警告/阻断）+ 原因 + 解除入口；豁免申请与审批。
 * 关系：上游 = 16.2.1.1；下游 = 4.3.1.1 发布、8.3.4.1 发货、12 提现。
 * **豁免必须有审批与理由**，否则阻断会被随意绕过。」
 *
 * 🔴 页面上的六条纪律：
 *
 * 1. **「判不了」是独立的一态** —— 单独成卡、单独成徽章，**绝不**并进「通过」。
 * 2. **前端不重算** —— 结论、原因、是否阻断**全部**是后端算好的，页面只显示。
 * 3. **阻断在服务端强制** —— 页面上写明这一点：改前端绕不过服务端。
 * 4. **豁免要走审批** —— 理由与有效期必填；申请人看不到「审批自己」的按钮；
 *    审批意见也必填。
 * 5. **因豁免放行照样显示** —— 被豁免放行的原因**不隐藏**，并指向豁免单 id。
 * 6. **空态三态分开说**（没有判定 / 筛选无结果 / 没有阻断 / 没有待审批豁免）。
 */
const PAGE_SIZE = 20;

export function ComplianceInterceptWorkspace() {
  const [caps, setCaps] = useState<ComplianceInterceptCapabilities | null>(null);
  const [policy, setPolicy] = useState<ComplianceInterceptPolicySet | null>(null);
  const [decisions, setDecisions] = useState<ComplianceInterceptDecisionPage | null>(null);
  const [exemptions, setExemptions] = useState<ComplianceInterceptExemptionPage | null>(null);

  const [scene, setScene] = useState('');
  const [subjectType, setSubjectType] = useState('');
  const [conclusion, setConclusion] = useState('');
  const [subjectId, setSubjectId] = useState('');
  const [page, setPage] = useState(0);

  const [exScene, setExScene] = useState('');
  const [exStatus, setExStatus] = useState('');
  const [exPage, setExPage] = useState(0);

  const [detail, setDetail] = useState<ComplianceInterceptDecision | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // ---- 申请豁免 ----
  const [exOpen, setExOpen] = useState(false);
  const [exRuleId, setExRuleId] = useState('');
  const [exReason, setExReason] = useState('');
  const [exValidTo, setExValidTo] = useState('');
  const [exTarget, setExTarget] = useState<ComplianceInterceptDecision | null>(null);

  // ---- 审批 ----
  const [decideOpen, setDecideOpen] = useState(false);
  const [decideTarget, setDecideTarget] = useState<ComplianceInterceptExemptionRow | null>(null);
  const [decideNote, setDecideNote] = useState('');
  const [decideAction, setDecideAction] = useState<'approve' | 'reject'>('approve');

  const loadDecisions = useCallback(
    async (target: number) => {
      const result = await fetchDecisions(target, PAGE_SIZE, {
        scene,
        subjectType,
        conclusion,
        subjectId,
      });
      setDecisions(result);
      return result;
    },
    [conclusion, scene, subjectId, subjectType],
  );

  const loadExemptions = useCallback(
    async (target: number) => {
      const result = await fetchExemptions(target, PAGE_SIZE, {
        scene: exScene,
        status: exStatus,
      });
      setExemptions(result);
      return result;
    },
    [exScene, exStatus],
  );

  const refresh = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const [capsResult, policyResult, decisionResult, exemptionResult] = await Promise.all([
        fetchCapabilities(),
        fetchPolicy(),
        loadDecisions(page),
        loadExemptions(exPage),
      ]);
      setCaps(capsResult);
      setPolicy(policyResult);
      setDecisions(decisionResult);
      setExemptions(exemptionResult);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setLoading(false);
    }
  }, [exPage, loadDecisions, loadExemptions, page]);

  useEffect(() => {
    void refresh();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const run = useCallback(async (work: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await work();
    } catch (e) {
      setError(e instanceof Error ? e.message : '操作失败，请重试');
    } finally {
      setBusy(false);
    }
  }, []);

  const doSavePolicy = useCallback(
    (targetScene: string, enabled: boolean, action: string) =>
      run(async () => {
        await savePolicy(targetScene, {
          enabled: enabled ? 'true' : 'false',
          undecidableAction: action,
          note: '',
        });
        setPolicy(await fetchPolicy());
      }),
    [run],
  );

  const doApplyExemption = useCallback(
    () =>
      run(async () => {
        if (!exTarget) return;
        await applyExemption({
          scene: exTarget.scene,
          subjectType: exTarget.subjectType,
          subjectId: exTarget.subjectId,
          ruleId: exRuleId.trim(),
          reason: exReason.trim(),
          validTo: new Date(exValidTo).toISOString(),
        });
        setExOpen(false);
        setExRuleId('');
        setExReason('');
        setExValidTo('');
        setExTarget(null);
        await loadExemptions(exPage);
      }),
    [exPage, exReason, exRuleId, exTarget, exValidTo, loadExemptions, run],
  );

  const doDecide = useCallback(
    () =>
      run(async () => {
        if (!decideTarget) return;
        if (decideAction === 'approve') {
          await approveExemption(decideTarget.exemptionId, decideNote.trim());
        } else {
          await rejectExemption(decideTarget.exemptionId, decideNote.trim());
        }
        setDecideOpen(false);
        setDecideNote('');
        setDecideTarget(null);
        await loadExemptions(exPage);
      }),
    [decideAction, decideNote, decideTarget, exPage, loadExemptions, run],
  );

  const decisionFiltered = !!(scene || subjectType || conclusion || subjectId);
  const decisionEmpty = decisionEmptyState(
    decisionEmptyKind(decisions?.total ?? 0, decisionFiltered, decisions?.blockCount ?? 0),
  );
  const exemptionFiltered = !!(exScene || exStatus);
  const exemptionEmpty = exemptionEmptyState(
    exemptionEmptyKind(exemptions?.total ?? 0, exemptionFiltered, exemptions?.pendingCount ?? 0),
  );

  const policyRows = useMemo(() => policy?.items ?? [], [policy]);

  if (loading && !caps) {
    return (
      <div className="flex items-center gap-2 py-10">
        <Loader2 className="text-muted-foreground size-4 animate-spin" />
        <span className="text-label-13">正在加载拦截执行…</span>
      </div>
    );
  }

  if (error && !caps) {
    return (
      <SectionCard title="拦截执行">
        <EmptyState
          icon={<TriangleAlert className="text-destructive size-8" />}
          title="拦截执行加载失败"
          description={error}
          action={
            <Button variant="secondary" onClick={() => void refresh()}>
              <RefreshCw className="mr-2 size-4" />
              重试
            </Button>
          }
        />
      </SectionCard>
    );
  }

  return (
    <div className="space-y-4">
      <PageHeader
        title="拦截执行"
        description="在刊登发布前、发货前、结算提现前执行合规校验并阻断；展示拦截原因与解除方式；豁免必须走审批与理由。"
      />

      {/* ---- 统计卡：四态分开，「判不了」不并进「通过」 ---- */}
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <StatCard
          label="判定总数"
          value={decisions?.total ?? 0}
          polarity="neutral"
          icon={<ScrollText className="size-4" />}
        />
        <StatCard
          label="阻断"
          value={decisions?.blockCount ?? 0}
          polarity="negative"
          icon={<ShieldAlert className="size-4" />}
        />
        <StatCard
          label="判不了"
          value={decisions?.undecidableCount ?? 0}
          polarity="negative"
          icon={<CircleAlert className="size-4" />}
        />
        <StatCard
          label="当前生效的豁免"
          value={exemptions?.effectiveCount ?? 0}
          polarity="neutral"
          icon={<ShieldCheck className="size-4" />}
        />
      </div>

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive text-copy-13 rounded-md border px-4 py-3">
          {error}
        </div>
      )}

      {/* ---- 能力声明 ---- */}
      <SectionCard
        title="能力声明"
        description="哪些下游真的可用、哪些还没交付 —— 逐条可查，不是文档里的一句话。"
      >
        <ul className="space-y-2">
          {(caps?.gaps ?? []).map((gap) => (
            <li
              key={gap.moduleId}
              className={
                gap.available
                  ? 'border-border flex items-start gap-3 rounded-md border px-3 py-2'
                  : 'border-border bg-muted/40 flex items-start gap-3 rounded-md border px-3 py-2'
              }
            >
              <Badge tone={gap.available ? 'success' : 'neutral'} className="mt-0.5 shrink-0">
                {gap.available ? '已交付' : '未交付'}
              </Badge>
              <div className="min-w-0">
                <div className="text-copy-13 text-foreground font-medium">
                  {gap.moduleId} · {gap.moduleName}
                </div>
                <div className="text-copy-13 text-muted-foreground">{gap.impact}</div>
                {!gap.available && (
                  <div className="text-copy-12 text-muted-foreground mt-1">
                    归属模块：{gap.ownerModule}
                  </div>
                )}
              </div>
            </li>
          ))}
        </ul>
        {caps && (
          <div className="text-copy-12 text-muted-foreground mt-3 space-y-1">
            <div>{caps.conclusionNote}</div>
            <div>{caps.enforcementNote}</div>
            <div>{caps.exemptionNote}</div>
            <div>{caps.upstreamNote}</div>
            <div>{caps.downstreamNote}</div>
          </div>
        )}
      </SectionCard>

      {/* ---- 拦截策略 ---- */}
      <SectionCard
        title="拦截策略"
        description="拦截默认不生效：租户显式开启后，对应的放行路径才会真的读服务端结论。"
        extra={
          <Button variant="secondary" size="sm" onClick={() => void refresh()} disabled={busy}>
            <RefreshCw className="mr-2 size-4" />
            刷新
          </Button>
        }
      >
        <div className="space-y-3">
          {policyRows.map((row) => (
            <div
              key={row.scene}
              className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2"
            >
              <Badge tone={row.enabled ? 'success' : 'neutral'}>
                {row.enabled ? '已启用' : '未启用'}
              </Badge>
              <div className="text-copy-13 text-foreground font-medium">
                {row.scene} · {row.sceneLabel}
              </div>
              <div className="text-copy-12 text-muted-foreground flex-1">
                判不了时：
                <select
                  className="border-input bg-background text-copy-12 ml-2 h-8 rounded-md border px-2"
                  value={row.undecidableAction}
                  onChange={(e) => void doSavePolicy(row.scene, row.enabled, e.target.value)}
                  disabled={busy}
                >
                  <option value="BLOCK">失败关闭（拦下来）</option>
                  <option value="ALLOW">放行但留痕</option>
                </select>
              </div>
              <Button
                size="sm"
                variant={row.enabled ? 'secondary' : 'default'}
                disabled={busy}
                onClick={() => void doSavePolicy(row.scene, !row.enabled, row.undecidableAction)}
              >
                {row.enabled ? '停用拦截' : '启用拦截'}
              </Button>
            </div>
          ))}
          {policy && <div className="text-copy-12 text-muted-foreground">{policy.note}</div>}
        </div>
      </SectionCard>

      {/* ---- 判定历史 ---- */}
      <SectionCard
        title="判定历史"
        description="一次判定一行、只追加：这里看到的每一行都是当时那一次的结论，规则后来改了也不改写它。"
      >
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <Input
            className="w-56"
            placeholder="主体 id"
            value={subjectId}
            onChange={(e) => {
              setSubjectId(e.target.value);
              setPage(0);
            }}
          />
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={scene}
            onChange={(e) => {
              setScene(e.target.value);
              setPage(0);
            }}
          >
            {SCENE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={subjectType}
            onChange={(e) => {
              setSubjectType(e.target.value);
              setPage(0);
            }}
          >
            {SUBJECT_TYPE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={conclusion}
            onChange={(e) => {
              setConclusion(e.target.value);
              setPage(0);
            }}
          >
            {CONCLUSION_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        {(decisions?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<ShieldCheck className="size-8" />}
            title={decisionEmpty.title}
            description={decisionEmpty.description}
          />
        ) : (
          <div className="space-y-2">
            {(decisions?.items ?? []).map((item) => (
              <button
                key={item.decisionId}
                type="button"
                className="border-border hover:bg-muted/40 flex w-full flex-wrap items-center gap-3 rounded-md border px-3 py-2 text-left"
                onClick={() => setDetail(item)}
              >
                <Badge tone={conclusionTone(item.conclusion)}>{conclusionLabel(item)}</Badge>
                <div className="text-copy-13 text-foreground font-medium">{subjectText(item)}</div>
                <div className="text-copy-12 text-muted-foreground">{item.sceneLabel}</div>
                <div className="text-copy-12 text-muted-foreground">
                  {item.reasonCount} 条原因
                  {item.waivedReasonCount > 0 ? ` · ${item.waivedReasonCount} 条因豁免放行` : ''}
                </div>
                <div className="text-copy-12 text-muted-foreground ml-auto">
                  {item.decidedAt ? item.decidedAt.slice(0, 19).replace('T', ' ') : ''}
                </div>
              </button>
            ))}
          </div>
        )}

        {(decisions?.total ?? 0) > PAGE_SIZE && (
          <div className="mt-3 flex items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={page === 0}
              onClick={() => {
                const next = Math.max(0, page - 1);
                setPage(next);
                void loadDecisions(next);
              }}
            >
              上一页
            </Button>
            <span className="text-copy-12 text-muted-foreground">
              第 {page + 1} 页 / 共 {decisions?.total ?? 0} 条
            </span>
            <Button
              variant="secondary"
              size="sm"
              disabled={(page + 1) * PAGE_SIZE >= (decisions?.total ?? 0)}
              onClick={() => {
                const next = page + 1;
                setPage(next);
                void loadDecisions(next);
              }}
            >
              下一页
            </Button>
          </div>
        )}
        {decisions && (
          <div className="text-copy-12 text-muted-foreground mt-2">{decisions.note}</div>
        )}
      </SectionCard>

      {/* ---- 豁免单 ---- */}
      <SectionCard
        title="豁免申请与审批"
        description="豁免必须有审批与理由，否则阻断会被随意绕过：申请人不能审批自己，理由与有效期都必填。"
      >
        <div className="mb-4 flex flex-wrap items-center gap-2">
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={exScene}
            onChange={(e) => {
              setExScene(e.target.value);
              setExPage(0);
            }}
          >
            {SCENE_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
          <select
            className="border-input bg-background text-copy-13 h-9 rounded-md border px-2"
            value={exStatus}
            onChange={(e) => {
              setExStatus(e.target.value);
              setExPage(0);
            }}
          >
            {EXEMPTION_STATUS_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        {(exemptions?.items.length ?? 0) === 0 ? (
          <EmptyState
            icon={<Gavel className="size-8" />}
            title={exemptionEmpty.title}
            description={exemptionEmpty.description}
          />
        ) : (
          <div className="space-y-2">
            {(exemptions?.items ?? []).map((item) => (
              <div
                key={item.exemptionId}
                className="border-border flex flex-wrap items-center gap-3 rounded-md border px-3 py-2"
              >
                <Badge
                  tone={
                    item.status === 'APPROVED'
                      ? 'success'
                      : item.status === 'REJECTED'
                        ? 'danger'
                        : 'warning'
                  }
                >
                  {item.statusLabel}
                </Badge>
                <div className="text-copy-13 text-foreground font-medium">
                  {item.sceneLabel} · {item.subjectTypeLabel} · {item.subjectId}
                </div>
                <div className="text-copy-12 text-muted-foreground">
                  规则 {item.ruleCode ?? item.ruleId}
                </div>
                <div className="text-copy-12 text-muted-foreground">{validityText(item)}</div>
                {item.effective && (
                  <Badge tone="success" className="shrink-0">
                    当前生效
                  </Badge>
                )}
                {item.expired && (
                  <Badge tone="neutral" className="shrink-0">
                    已过期
                  </Badge>
                )}
                {item.status === 'PENDING' && !item.decidable && (
                  <span className="text-copy-12 text-muted-foreground ml-auto">
                    你是申请人 —— 这张单要**别人**批
                  </span>
                )}
                {item.decidable && (
                  <Button
                    variant="secondary"
                    size="sm"
                    className="ml-auto"
                    disabled={busy}
                    onClick={() => {
                      setDecideTarget(item);
                      setDecideAction('approve');
                      setDecideNote('');
                      setDecideOpen(true);
                    }}
                  >
                    审批
                  </Button>
                )}
              </div>
            ))}
          </div>
        )}

        {(exemptions?.total ?? 0) > PAGE_SIZE && (
          <div className="mt-3 flex items-center gap-2">
            <Button
              variant="secondary"
              size="sm"
              disabled={exPage === 0}
              onClick={() => {
                const next = Math.max(0, exPage - 1);
                setExPage(next);
                void loadExemptions(next);
              }}
            >
              上一页
            </Button>
            <span className="text-copy-12 text-muted-foreground">
              第 {exPage + 1} 页 / 共 {exemptions?.total ?? 0} 张
            </span>
            <Button
              variant="secondary"
              size="sm"
              disabled={(exPage + 1) * PAGE_SIZE >= (exemptions?.total ?? 0)}
              onClick={() => {
                const next = exPage + 1;
                setExPage(next);
                void loadExemptions(next);
              }}
            >
              下一页
            </Button>
          </div>
        )}
        {exemptions && (
          <div className="text-copy-12 text-muted-foreground mt-2">{exemptions.note}</div>
        )}
      </SectionCard>

      {/* ---- 判定详情 ---- */}
      <Modal open={!!detail} onOpenChange={(open) => !open && setDetail(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>判定详情</ModalTitle>
            <ModalDescription>
              {detail ? `${subjectText(detail)} · ${detail.sceneLabel}` : ''}
            </ModalDescription>
          </ModalHeader>
          {detail && (
            <div className="space-y-4">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone={conclusionTone(detail.conclusion)}>{conclusionLabel(detail)}</Badge>
                <span className="text-copy-12 text-muted-foreground">
                  参与判定的规则 {detail.ruleCount} 条 · 原因 {detail.reasonCount} 条 · 判不了{' '}
                  {detail.undecidableReasonCount} 条 · 因豁免放行 {detail.waivedReasonCount} 条
                </span>
              </div>
              {!detail.releaseGranted && (
                <div className="border-destructive-soft bg-destructive-soft text-destructive text-copy-12 rounded-md border px-3 py-2">
                  🔴 服务端结论：**不能放行**。阻断在服务端强制 —— 改前端绕不过去。
                </div>
              )}

              <div>
                <div className="text-copy-13 text-foreground mb-2 font-medium">逐条原因</div>
                {detail.reasons.length === 0 ? (
                  <div className="text-copy-12 text-muted-foreground">
                    这次判定没有原因（结论是通过）。
                  </div>
                ) : (
                  <ul className="space-y-2">
                    {detail.reasons.map((reason) => (
                      <li
                        key={reason.reasonId}
                        className="border-border rounded-md border px-3 py-2"
                      >
                        <div className="flex flex-wrap items-center gap-2">
                          <Badge
                            tone={
                              reason.undecidable ? 'neutral' : reason.waived ? 'info' : 'danger'
                            }
                          >
                            {reason.reasonCodeLabel}
                          </Badge>
                          {reason.ruleCode && (
                            <span className="text-copy-12 text-muted-foreground">
                              规则 {reason.ruleCode}
                            </span>
                          )}
                          {reason.ruleVersionId && (
                            <span className="text-copy-12 text-muted-foreground">
                              版本 {reason.ruleVersionId.slice(0, 8)}
                            </span>
                          )}
                          {reason.waived && (
                            <Badge tone="info" className="shrink-0">
                              因豁免放行
                            </Badge>
                          )}
                        </div>
                        <div className="text-copy-13 text-foreground mt-1">{reason.reasonText}</div>
                      </li>
                    ))}
                  </ul>
                )}
              </div>

              {blockingReasons(detail).length > 0 && (
                <div>
                  <div className="text-copy-13 text-foreground mb-2 font-medium">解除方式</div>
                  <ul className="text-copy-12 text-muted-foreground space-y-1">
                    {detail.remedies.map((remedy, index) => (
                      <li key={`${remedy.reasonCode}-${index}`}>
                        {remedy.remedy}
                        {remedy.canApplyExemption ? '' : '（这一条是「判不了」，不能靠豁免绕过）'}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {waivedReasons(detail).length > 0 && (
                <div>
                  <div className="text-copy-13 text-foreground mb-2 font-medium">
                    因豁免放行（阻断没有无声消失）
                  </div>
                  <ul className="text-copy-12 text-muted-foreground space-y-1">
                    {detail.exemptions.map((ref) => (
                      <li key={ref.exemptionId}>
                        豁免单 {ref.exemptionId}
                        {ref.ruleCode ? ` · 规则 ${ref.ruleCode}` : ''}
                        {ref.validFrom && ref.validTo
                          ? ` · 有效期 ${ref.validFrom.slice(0, 10)} ~ ${ref.validTo.slice(0, 10)}`
                          : ''}
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              {undecidableReasons(detail).length > 0 && (
                <div className="border-warning-soft bg-warning-soft text-warning text-copy-12 rounded-md border px-3 py-2">
                  🔴 这次是**判不了**：规则读不到 / 适用版本查不到 / 规则集为空 / 要求状态缺失。
                  「判不了」**不等于**「通过」—— 服务端按策略决定失败关闭还是放行并留痕。
                </div>
              )}

              <div className="text-copy-12 text-muted-foreground">{detail.note}</div>
            </div>
          )}
          <ModalFooter>
            {detail && blockingReasons(detail).length > 0 && (
              <Button
                variant="secondary"
                onClick={() => {
                  setExTarget(detail);
                  setExRuleId(blockingReasons(detail)[0]?.ruleId ?? '');
                  setExReason('');
                  setExValidTo('');
                  setExOpen(true);
                }}
                disabled={busy}
              >
                申请豁免
              </Button>
            )}
            <Button variant="secondary" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 申请豁免 ---- */}
      <Modal open={exOpen} onOpenChange={(open) => !open && setExOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>申请豁免</ModalTitle>
            <ModalDescription>
              豁免必须有理由与有效期，而且要**别人**审批 —— 申请人不能审批自己。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            <div className="text-copy-12 text-muted-foreground">
              {exTarget ? `${subjectText(exTarget)} · ${exTarget.sceneLabel}` : ''}
            </div>
            <label className="text-copy-13 block">
              <span className="text-foreground font-medium">被豁免的规则 id</span>
              <Input
                className="mt-1"
                value={exRuleId}
                onChange={(e) => setExRuleId(e.target.value)}
                placeholder="一张豁免只针对一条规则"
              />
            </label>
            <label className="text-copy-13 block">
              <span className="text-foreground font-medium">申请理由（必填）</span>
              <Input
                className="mt-1"
                value={exReason}
                onChange={(e) => setExReason(e.target.value)}
                placeholder="为什么这次可以放行"
              />
            </label>
            <label className="text-copy-13 block">
              <span className="text-foreground font-medium">有效期截止（必填）</span>
              <Input
                className="mt-1"
                type="date"
                value={exValidTo}
                onChange={(e) => setExValidTo(e.target.value)}
              />
            </label>
          </div>
          <ModalFooter>
            <Button
              onClick={() => void doApplyExemption()}
              disabled={busy || !exRuleId.trim() || !exReason.trim() || !exValidTo}
            >
              提交申请
            </Button>
            <Button variant="secondary" onClick={() => setExOpen(false)}>
              取消
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>

      {/* ---- 审批 / 驳回 ---- */}
      <Modal open={decideOpen} onOpenChange={(open) => !open && setDecideOpen(false)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>{decideAction === 'approve' ? '批准豁免' : '驳回豁免'}</ModalTitle>
            <ModalDescription>
              审批意见必填 —— 豁免单上必须留下「谁批的、为什么批」。
            </ModalDescription>
          </ModalHeader>
          <div className="space-y-3">
            {decideTarget && (
              <div className="text-copy-12 text-muted-foreground">
                {decideTarget.sceneLabel} · {decideTarget.subjectTypeLabel} ·{' '}
                {decideTarget.subjectId} · 规则 {decideTarget.ruleCode ?? decideTarget.ruleId}
              </div>
            )}
            <label className="text-copy-13 block">
              <span className="text-foreground font-medium">审批意见（必填）</span>
              <Input
                className="mt-1"
                value={decideNote}
                onChange={(e) => setDecideNote(e.target.value)}
                placeholder="核实了什么，为什么"
              />
            </label>
          </div>
          <ModalFooter>
            <Button
              onClick={() => void doDecide()}
              disabled={busy || !decideNote.trim()}
              variant={decideAction === 'approve' ? 'default' : 'secondary'}
            >
              {decideAction === 'approve' ? '批准' : '驳回'}
            </Button>
            <Button variant="secondary" onClick={() => setDecideOpen(false)}>
              取消
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}
