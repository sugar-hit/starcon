'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import {
  Badge,
  Button,
  EmptyState,
  Modal,
  ModalContent,
  ModalDescription,
  ModalFooter,
  ModalHeader,
  ModalTitle,
} from '@starcon/ui';
import {
  ArrowLeft,
  CircleCheck,
  Info,
  Plus,
  RefreshCw,
  Save,
  Send,
  Trash2,
  TriangleAlert,
  X,
} from 'lucide-react';
import type { CustomsProfile, DeclarationReady } from '../../../../../packages/api-contracts/src/catalog';
import {
  blockerLines,
  canEdit,
  canReview,
  canSubmit,
  draftFrom,
  draftToPayload,
  emptyElement,
  errorText,
  fetchDeclarationReady,
  fetchProfile,
  incompleteRows,
  isConflict,
  MAX_ELEMENTS,
  readinessText,
  reviewable,
  reviewProfile,
  reviewState,
  saveProfile,
  submitForReview,
  validateDraft,
  writable,
  type CustomsDraft,
  type ElementDraft,
} from './customs-attribute-model';
import { Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.7.1.1 HS 编码与申报要素工作区。
 *
 * <p>这一页要比别的属性页多承担一件事：**它是 10 报关能否引用的闸门**。
 * 蓝图写死「HS 编码错误直接导致申报失败与罚款，必须允许审核流程而非任意填写」，
 * 所以界面必须把三件事分清楚，一件都不能糊：
 *
 * <ol>
 *   <li><b>内容齐备性</b>（必填申报要素是否都填了）—— 决定「能不能提交审核」；
 *   <li><b>审核态</b>（草稿 / 待审核 / 已通过 / 已驳回）—— 决定「谁下一步做什么」；
 *   <li><b>可报关性</b>（{@code declarationReady}）—— 决定「10 报关现在能不能引用」。
 * </ol>
 * 把后两者塌缩成「填完了」是本模块最容易犯、代价也最大的错。
 *
 * <p>界面纪律：
 * <ul>
 *   <li><b>写与审核按两个权限分开收敛</b>（{@code catalog.compliance.update} /
 *       {@code catalog.compliance.review}）。能填的人未必有权批，
 *       合并成一个权限等于允许「自己填自己批」。
 *   <li><b>提交 / 通过 / 驳回都要二次确认</b>：它们直接决定这份资料能不能报关，
 *       确认框写清后果，而不是弹一个「确定吗」。
 *   <li><b>阻断原因逐条读中文</b>：后端已经给了 {@code blockerLabels}，
 *       不把 {@code REQUIRED_ELEMENTS_INCOMPLETE} 这种码甩给运营。
 *   <li><b>加载 / 错误 / 空态三态齐全</b>：档案没维护 vs 面板加载失败是两回事。
 * </ul>
 */
export function CustomsAttributeWorkspace({ skuId }: { skuId: string }) {
  const { session } = useIdentity();
  const canWrite = writable(session.permissions);
  const canApprove = reviewable(session.permissions);

  const [profile, setProfile] = useState<CustomsProfile | null>(null);
  const [draft, setDraft] = useState<CustomsDraft | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [confirm, setConfirm] = useState<'submit' | 'approve' | 'reject' | null>(null);
  const [rejectReason, setRejectReason] = useState('');
  const [ready, setReady] = useState<DeclarationReady | null>(null);
  const [readyError, setReadyError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchProfile(skuId);
      setProfile(result);
      setDraft(draftFrom(result));
    } catch (e) {
      setError(errorText(e, '报关属性加载失败'));
    } finally {
      setLoading(false);
    }
  }, [skuId]);

  useEffect(() => {
    void load();
  }, [load]);

  const errors = useMemo(() => (draft ? validateDraft(draft) : []), [draft]);
  const blockers = useMemo(() => (profile ? blockerLines(profile) : []), [profile]);
  const state = useMemo(
    () =>
      profile
        ? reviewState(profile)
        : { key: 'NOT_RECORDED' as const, label: '未维护', tone: 'neutral' as const, next: '' },
    [profile],
  );
  const missing = useMemo(
    () => (profile?.elements ? incompleteRows(profile.elements) : []),
    [profile],
  );

  function patch(changes: Partial<CustomsDraft>) {
    setDraft((current) => (current ? { ...current, ...changes } : current));
  }

  function patchElement(index: number, changes: Partial<ElementDraft>) {
    setDraft((current) =>
      current
        ? {
            ...current,
            elements: current.elements.map((element, at) =>
              at === index ? { ...element, ...changes } : element,
            ),
          }
        : current,
    );
  }

  function addElement() {
    setDraft((current) =>
      current && current.elements.length < MAX_ELEMENTS
        ? { ...current, elements: [...current.elements, emptyElement()] }
        : current,
    );
  }

  function removeElement(index: number) {
    setDraft((current) =>
      current
        ? { ...current, elements: current.elements.filter((_, at) => at !== index) }
        : current,
    );
  }

  async function save() {
    if (!draft || errors.length > 0) return;
    setBusy(true);
    setActionError(null);
    try {
      // 用写端点回传的档案刷新：不必为了看结果再拉一次（也避免两次请求之间被别人改掉）。
      const saved = await saveProfile(skuId, draftToPayload(draft));
      setProfile(saved.profile);
      setDraft(draftFrom(saved.profile));
      setReady(null);
      setReadyError(null);
    } catch (e) {
      setActionError(
        isConflict(e)
          ? '这条报关属性已被他人修改，或已审核通过而内容被锁。请刷新后重试（本次修改没有保存）。'
          : errorText(e, '保存失败，请检查输入后重试'),
      );
    } finally {
      setBusy(false);
    }
  }

  async function runConfirm() {
    if (!profile) return;
    setBusy(true);
    setActionError(null);
    try {
      const next =
        confirm === 'submit'
          ? await submitForReview(skuId, profile.version ?? 0)
          : await reviewProfile(skuId, {
              approve: confirm === 'approve',
              rejectReason: confirm === 'reject' ? rejectReason.trim() : '',
            });
      setProfile(next);
      setDraft(draftFrom(next));
      setReady(null);
      setReadyError(null);
      setConfirm(null);
      setRejectReason('');
    } catch (e) {
      setActionError(
        isConflict(e)
          ? '这一步没有生效：资料可能已被别人处理过，或必填申报要素还没填齐。请刷新后重试。'
          : errorText(e, '操作失败，请重试'),
      );
      setConfirm(null);
    } finally {
      setBusy(false);
    }
  }

  /** 供 10 报关的取数口：未审核通过时后端直接 409，这里把那句话原样带回。 */
  async function peekDeclarationReady() {
    setBusy(true);
    setReadyError(null);
    setReady(null);
    try {
      setReady(await fetchDeclarationReady(skuId));
    } catch (e) {
      setReadyError(errorText(e, '取数失败'));
    } finally {
      setBusy(false);
    }
  }

  const editable = profile !== null && draft !== null && canWrite && canEdit(profile) && !busy;

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-h-20 text-primary font-semibold tracking-tight">
            HS 编码与申报要素
          </h1>
          <p className="text-muted-foreground text-copy-13 mt-1">
            {profile
              ? `${profile.skuCode} · ${profile.skuName}（所属 ${profile.spuCode}）`
              : '决定这个 SKU 能不能报关、10 报关按什么资料申报。'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Link href={`/products/sku/${skuId}`}>
            <Button variant="ghost" size="sm">
              <ArrowLeft className="h-4 w-4" />
              回到 SKU 详情
            </Button>
          </Link>
          <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
            <RefreshCw className={`h-4 w-4 ${loading ? 'animate-spin' : ''}`} />
            刷新
          </Button>
        </div>
      </div>

      {actionError && (
        <div
          role="alert"
          className="border-destructive-soft bg-destructive-soft/40 text-destructive flex items-start gap-2 rounded-md border px-3 py-2 text-sm"
        >
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {loading ? (
        <div className="text-muted-foreground rounded-md border border-dashed p-10 text-center text-sm">
          正在加载报关属性…
        </div>
      ) : error ? (
        <EmptyState
          icon={<TriangleAlert className="h-5 w-5" />}
          title="报关属性加载失败"
          description={error}
          action={
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      ) : !profile || !draft ? (
        <EmptyState
          icon={<Info className="h-5 w-5" />}
          title="没有可显示的报关属性"
          description="这个 SKU 可能不存在，或者当前租户看不到它。"
        />
      ) : (
        <>
          {/* 可报关性：与「字段填没填全」是两件事，必须单独一条横幅说清。 */}
          <div
            className={`rounded-md border px-3 py-2 text-sm ${
              profile.declarationReady
                ? 'border-success-soft bg-success-soft/40'
                : 'border-warning-soft bg-warning-soft/40'
            }`}
          >
            <p className="flex items-center gap-2 font-medium">
              {profile.declarationReady ? (
                <CircleCheck className="text-success h-4 w-4" />
              ) : (
                <TriangleAlert className="text-warning h-4 w-4" />
              )}
              <span className={profile.declarationReady ? 'text-success' : 'text-warning'}>
                {profile.declarationReady ? '可被 10 报关引用' : '当前不可被 10 报关引用'}
              </span>
              <Badge tone={state.tone}>{state.label}</Badge>
            </p>
            <p className="text-muted-foreground mt-1 text-xs">{readinessText(profile)}</p>
            {state.next && <p className="text-muted-foreground mt-0.5 text-xs">下一步：{state.next}</p>}
            {/* 阻断原因逐条读中文，不显示英文码。 */}
            {blockers.length > 0 && (
              <ul className="text-muted-foreground mt-2 space-y-1 text-xs">
                {blockers.map((blocker) => (
                  <li key={blocker.code} className="flex items-start gap-1.5">
                    <span className="text-warning mt-0.5">·</span>
                    <span>{blocker.label}</span>
                  </li>
                ))}
              </ul>
            )}
          </div>

          {/* 审核留痕：报关单上出问题时要能回答「谁在什么时候放行了这个申报事实」。 */}
          {profile.rejectReason && profile.status === 'REJECTED' && (
            <div className="border-destructive-soft bg-destructive-soft/40 rounded-md border px-3 py-2 text-sm">
              <p className="text-destructive font-medium">驳回原因</p>
              <p className="text-muted-foreground mt-1 text-xs">{profile.rejectReason}</p>
            </div>
          )}
          {(profile.submittedAt || profile.reviewedAt) && (
            <p className="text-muted-foreground text-xs">
              提交于 {profile.submittedAt ?? '—'}
              {profile.reviewedAt ? ` · 审核于 ${profile.reviewedAt}` : ''}
            </p>
          )}

          {state.key === 'NOT_RECORDED' && (
            <div className="border-warning-soft bg-warning-soft/40 rounded-md border px-3 py-2 text-sm">
              <p className="text-warning font-medium">这个 SKU 还没有报关属性</p>
              <p className="text-muted-foreground mt-1 text-xs">
                在下面填好 HS 编码、原产地与申报要素并保存，才能提交审核。
                未经审核通过的资料，10 报关取数会被直接拒绝。
              </p>
            </div>
          )}

          <Card
            title="报关属性"
            hint="HS 编码、原产地、申报品名与法定计量单位会原样出现在报关单上；申报价值参考只是参考值，不是申报货值。"
          >
            <div className="space-y-4">
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="HS 编码" hint="6–10 位纯数字，例：851712">
                  <input
                    className={control}
                    value={draft.hsCode}
                    disabled={!editable}
                    placeholder="851712"
                    onChange={(e) => patch({ hsCode: e.target.value })}
                  />
                </Field>
                <Field label="原产地（ISO 3166-1 alpha-2）" hint="例：CN / VN / DE">
                  <input
                    className={control}
                    value={draft.originCountryCode}
                    disabled={!editable}
                    placeholder="CN"
                    onChange={(e) => patch({ originCountryCode: e.target.value })}
                  />
                </Field>
              </div>
              <Field label="申报品名" hint="原样出现在报关单上，最多 200 字">
                <input
                  className={control}
                  value={draft.declaredName}
                  disabled={!editable}
                  placeholder="移动电话机"
                  onChange={(e) => patch({ declaredName: e.target.value })}
                />
              </Field>
              <div className="grid gap-3 sm:grid-cols-2">
                <Field label="申报价值参考" hint="参考值，不是申报货值；留空 = 不填">
                  <input
                    className={control}
                    value={draft.declaredValueReference}
                    disabled={!editable}
                    placeholder="12.50"
                    inputMode="decimal"
                    onChange={(e) => patch({ declaredValueReference: e.target.value })}
                  />
                </Field>
                <Field label="法定计量单位代码" hint="例：PCE 件 / KGM 千克">
                  <input
                    className={control}
                    value={draft.statutoryUnitCode}
                    disabled={!editable}
                    placeholder="PCE"
                    onChange={(e) => patch({ statutoryUnitCode: e.target.value })}
                  />
                </Field>
              </div>
              <label className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={draft.preferentialOriginEligible}
                  disabled={!editable}
                  onChange={(e) => patch({ preferentialOriginEligible: e.target.checked })}
                />
                可享惠（自贸协定）
                <span className="text-muted-foreground text-xs">
                  （勾了必须填协定代码；不勾就不该填 —— 两个答案矛盾会让下游无法判断）
                </span>
              </label>
              {draft.preferentialOriginEligible && (
                <Field label="协定代码" hint="例：RCEP / CPTPP">
                  <input
                    className={control}
                    value={draft.preferenceAgreement}
                    disabled={!editable}
                    placeholder="RCEP"
                    onChange={(e) => patch({ preferenceAgreement: e.target.value })}
                  />
                </Field>
              )}
              <Field label="备注（不参与判定）">
                <input
                  className={control}
                  value={draft.note}
                  disabled={!editable}
                  placeholder="例：原产地已取得原产地证"
                  onChange={(e) => patch({ note: e.target.value })}
                />
              </Field>
            </div>
          </Card>

          <Card
            title={`申报要素（${draft.elements.length} 条）`}
            hint="海关按申报要素逐项比对，缺项会被判为填报不符并退单。位次按行序自动排定。"
          >
            <div className="space-y-3">
              {draft.elements.length === 0 ? (
                <p className="text-muted-foreground rounded-md border border-dashed p-6 text-center text-sm">
                  还没有申报要素。一条都没有时审核无法通过，10 报关也拿不到可申报的资料。
                </p>
              ) : (
                <ul className="space-y-2">
                  {draft.elements.map((element, index) => {
                    const incomplete = element.required && element.elementValue.trim() === '';
                    return (
                      <li
                        key={index}
                        className={`rounded-md border p-3 transition-colors duration-base ease-out-expo ${
                          incomplete
                            ? 'border-destructive-soft bg-destructive-soft/20'
                            : 'border-control-border'
                        }`}
                      >
                        <div className="grid gap-2 sm:grid-cols-[2rem_7rem_1fr_1fr_2rem]">
                          <span className="text-muted-foreground self-center text-center text-xs tabular-nums">
                            {index + 1}
                          </span>
                          <input
                            className={control}
                            value={element.elementCode}
                            disabled={!editable}
                            placeholder="代码"
                            aria-label={`第 ${index + 1} 条申报要素代码`}
                            onChange={(e) =>
                              patchElement(index, { elementCode: e.target.value })
                            }
                          />
                          <input
                            className={control}
                            value={element.elementName}
                            disabled={!editable}
                            placeholder="名称，例：品牌类型"
                            aria-label={`第 ${index + 1} 条申报要素名称`}
                            onChange={(e) =>
                              patchElement(index, { elementName: e.target.value })
                            }
                          />
                          <input
                            className={control}
                            value={element.elementValue}
                            disabled={!editable}
                            placeholder={element.required ? '必填，尚未填写' : '选填'}
                            aria-label={`第 ${index + 1} 条申报要素值`}
                            onChange={(e) =>
                              patchElement(index, { elementValue: e.target.value })
                            }
                          />
                          <button
                            type="button"
                            className="text-muted-foreground hover:text-destructive hover:bg-destructive-soft/40 self-center rounded-md border p-2 transition-colors duration-base ease-out-expo disabled:opacity-50"
                            disabled={!editable}
                            aria-label={`删除第 ${index + 1} 条申报要素`}
                            onClick={() => removeElement(index)}
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </div>
                        <label className="mt-2 flex items-center gap-2 text-xs">
                          <input
                            type="checkbox"
                            checked={element.required}
                            disabled={!editable}
                            onChange={(e) => patchElement(index, { required: e.target.checked })}
                          />
                          必填
                          {incomplete && (
                            <span className="text-destructive font-medium">
                              这一条是必填但还空着 —— 审核过不去
                            </span>
                          )}
                        </label>
                      </li>
                    );
                  })}
                </ul>
              )}
              {canWrite && (
                <Button
                  variant="ghost"
                  size="sm"
                  onClick={addElement}
                  disabled={!editable || draft.elements.length >= MAX_ELEMENTS}
                >
                  <Plus className="h-4 w-4" />
                  添加申报要素
                </Button>
              )}
              {missing.length > 0 && (
                <p className="text-destructive text-xs">
                  服务端算出还有 {missing.length} 条必填申报要素是空的（按保存时的档案判定）。
                </p>
              )}
            </div>
          </Card>

          {errors.length > 0 && (
            <div className="border-destructive-soft bg-destructive-soft/40 rounded-md border px-3 py-2">
              <p className="text-destructive text-copy-13 font-medium">
                这次修改没有保存，请先解决：
              </p>
              <ul className="text-muted-foreground mt-1 space-y-0.5 text-xs">
                {errors.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          )}

          {/* 审核动作：三个按钮的可见性由「权限 × 当前审核态」共同决定。 */}
          <Card title="审核流转" hint="只有「已审核通过」的资料才能被 10 报关引用。">
            <div className="flex flex-wrap items-center gap-2">
              {canWrite && canEdit(profile) && (
                <Button
                  onClick={() => void save()}
                  disabled={busy || errors.length > 0}
                  title={errors.length > 0 ? '先解决上面的校验问题' : undefined}
                >
                  <Save className="h-4 w-4" />
                  保存
                </Button>
              )}
              {canWrite && canSubmit(profile) && (
                <Button variant="outline" onClick={() => setConfirm('submit')} disabled={busy}>
                  <Send className="h-4 w-4" />
                  提交审核
                </Button>
              )}
              {canApprove && canReview(profile) && (
                <>
                  <Button variant="outline" onClick={() => setConfirm('approve')} disabled={busy}>
                    <CircleCheck className="h-4 w-4" />
                    审核通过
                  </Button>
                  <Button
                    variant="outline"
                    onClick={() => setConfirm('reject')}
                    disabled={busy}
                    className="text-destructive border-destructive-soft"
                  >
                    <X className="h-4 w-4" />
                    驳回
                  </Button>
                </>
              )}
              {profile.status === 'APPROVED' && (
                <span className="text-muted-foreground text-xs">
                  已通过审核，内容被锁定：要改必须先在审核里驳回。
                </span>
              )}
              {!canWrite && !canApprove && (
                <span className="text-muted-foreground text-xs">
                  当前角色只能查看，既不能编辑也不能审核。
                </span>
              )}
              {canWrite && !canApprove && (
                <span className="text-muted-foreground text-xs">
                  你可以填报并提交审核，但审核需要另外的 `catalog.compliance.review` 权限。
                </span>
              )}
            </div>
          </Card>

          <Card
            title="10 报关取数口径"
            hint="这就是清关时真正被引用的那份资料。未审核通过时接口直接返回 409，不会外发半成品。"
          >
            <div className="space-y-3">
              <Button variant="ghost" size="sm" onClick={() => void peekDeclarationReady()} disabled={busy}>
                <RefreshCw className="h-4 w-4" />
                查看报关将拿到的资料
              </Button>
              {readyError && (
                <div className="border-destructive-soft bg-destructive-soft/40 rounded-md border px-3 py-2 text-sm">
                  <p className="text-destructive text-copy-13 font-medium">取不到：</p>
                  <p className="text-muted-foreground mt-1 text-xs">{readyError}</p>
                </div>
              )}
              {ready && (
                <dl className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
                  <Metric label="HS 编码" value={`${ready.hsCode}（${ready.hsCodeSource}）`} />
                  <Metric label="申报品名" value={ready.declaredName} />
                  <Metric label="法定计量单位" value={ready.statutoryUnitCode} />
                  <Metric label="要素状态" value={ready.declarationElementStatus} />
                </dl>
              )}
            </div>
          </Card>
        </>
      )}

      {/* 二次确认：三个动作都直接决定这份资料能不能报关，不能一点就生效。 */}
      <Modal open={confirm !== null} onOpenChange={(open) => !open && setConfirm(null)}>
        <ModalContent>
          <ModalHeader>
            <ModalTitle>
              {confirm === 'submit'
                ? '确认提交审核？'
                : confirm === 'approve'
                  ? '确认审核通过？'
                  : '确认驳回？'}
            </ModalTitle>
            <ModalDescription>
              {confirm === 'submit'
                ? '提交后内容将被锁定，要改必须先在审核里被驳回。HS 编码错误会直接导致申报失败与罚款，而扣货与罚款由卖家承担。'
                : confirm === 'approve'
                  ? '通过后 10 报关立即可以引用这份申报资料，海关会按上面的 HS 编码与申报要素逐项比对。'
                  : '驳回后这条资料不可被报关引用，运营需要按你写的原因改完再重新提交。'}
            </ModalDescription>
          </ModalHeader>
          {confirm === 'reject' && (
            <div className="space-y-2 py-2">
              <Field label="驳回原因（必填）" hint="不写理由，运营就只能猜要怎么改">
                <textarea
                  className={`${control} min-h-20`}
                  value={rejectReason}
                  placeholder="例：第 2 条申报要素的品牌类型与实际不符"
                  onChange={(e) => setRejectReason(e.target.value)}
                />
              </Field>
            </div>
          )}
          <ModalFooter>
            <Button variant="outline" onClick={() => setConfirm(null)}>
              取消
            </Button>
            <Button
              variant={confirm === 'reject' ? 'outline' : 'default'}
              className={confirm === 'reject' ? 'text-destructive border-destructive-soft' : ''}
              onClick={() => void runConfirm()}
              disabled={busy || (confirm === 'reject' && rejectReason.trim() === '')}
            >
              {busy ? '处理中…' : '确认'}
            </Button>
          </ModalFooter>
        </ModalContent>
      </Modal>
    </div>
  );
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md border p-3">
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className="mt-1 text-sm font-medium tabular-nums">{value}</dd>
    </div>
  );
}

/** 与同目录其他工作区（physical-safety / sku-detail）同形状的分区卡。 */
function Card({
  title,
  hint,
  children,
}: {
  title: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <div className="bg-card rounded-lg border p-4">
      <h3 className="font-medium">{title}</h3>
      {hint && <p className="text-muted-foreground mt-1 text-xs">{hint}</p>}
      <div className="mt-3">{children}</div>
    </div>
  );
}