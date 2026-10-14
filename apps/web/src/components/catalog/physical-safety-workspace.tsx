'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState } from '@starcon/ui';
import { ArrowLeft, Info, RefreshCw, Save, TriangleAlert, Zap } from 'lucide-react';
import type { PhysicalSafetyProfile } from '../../../../../packages/api-contracts/src/index';
import {
  decimal,
  draftFrom,
  draftToPayload,
  errorText,
  fetchProfile,
  isConflict,
  saveProfile,
  SAFETY_FLAGS,
  safetyState,
  validateDraft,
  volumetricText,
  volumeText,
  writable,
  type SafetyDraft,
} from './physical-safety-model';
import { Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.7.2.1 物理与安全属性工作区。
 *
 * <p>回答两个问题：「这个 SKU 的物理参数是什么」与「它能不能发、要不要申报」。
 * 蓝图的职责句把两者写在同一句里，所以编辑区也是**一张表单**而不是两个 tab ——
 * 分成两步只会让「填了尺寸忘了填安全属性」变成常态，而那正是规则 R2 要堵的洞。
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>体积/体积重是现算的</b>，不落库（规则 R3）。除数由用户选（空运 6000 / 快递 5000），
 *       没选就明确说「未选除数，不给体积重」，而不是默默套一个默认值 ——
 *       除数是渠道口径，本模块无权替 10 物流选。
 *   <li><b>「未采集」用警示色且单独提示</b>：它意味着渠道判定一律拒发（规则 R2），
 *       界面上必须让它与「已采集 · 无特殊属性」看起来不一样。
 *   <li><b>加载 / 错误 / 空态三态齐全</b>：档案还没建 vs 面板加载失败是两种不同的空态。
 *   <li><b>只读角色看得见但动不了</b>，写操作按 {@code catalog.compliance.update} 收敛。
 * </ul>
 */
export function PhysicalSafetyWorkspace({ skuId }: { skuId: string }) {
  const { session } = useIdentity();
  const canWrite = writable(session.permissions);

  const [profile, setProfile] = useState<PhysicalSafetyProfile | null>(null);
  const [draft, setDraft] = useState<SafetyDraft | null>(null);
  const [divisor, setDivisor] = useState('6000');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await fetchProfile(skuId, divisor.trim() === '' ? undefined : divisor.trim());
      setProfile(result);
      setDraft(draftFrom(result));
    } catch (e) {
      setError(errorText(e, '物理与安全属性加载失败'));
    } finally {
      setLoading(false);
    }
  }, [skuId, divisor]);

  useEffect(() => {
    void load();
  }, [load]);

  const errors = useMemo(() => (draft ? validateDraft(draft) : []), [draft]);
  const state = useMemo(
    () =>
      safetyState(
        profile ?? {
          safetyCollected: false,
          containsBattery: false,
          containsLiquid: false,
          dangerousGoods: false,
          isMagnetic: false,
        },
      ),
    [profile],
  );

  function patch(changes: Partial<SafetyDraft>) {
    setDraft((current) => (current ? { ...current, ...changes } : current));
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
    } catch (e) {
      setActionError(
        isConflict(e)
          ? '这条档案已被他人修改，请刷新后重试（本次修改没有保存）。'
          : errorText(e, '保存失败，请检查输入后重试'),
      );
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-h-20 text-primary font-semibold tracking-tight">物理与安全属性</h1>
          <p className="text-muted-foreground text-copy-13 mt-1">
            {profile
              ? `${profile.skuCode} · ${profile.skuName}（所属 ${profile.spuCode}）`
              : '决定这个 SKU 能走哪些物流渠道、是否需要特殊申报。'}
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
          正在加载物理与安全属性…
        </div>
      ) : error ? (
        <EmptyState
          icon={<TriangleAlert className="h-5 w-5" />}
          title="物理与安全属性加载失败"
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
          title="这个 SKU 还没有物理档案"
          description="物理参数与安全属性在 SKU 建档时采集。留空时物流渠道拿不到体积重，只能按件计费。"
        />
      ) : (
        <>
          {/* 规则 R2：未采集是必须引导去补的状态，用警示色单独说清后果。 */}
          {!profile.safetyCollected && (
            <div className="border-warning-soft bg-warning-soft/40 rounded-md border px-3 py-2 text-sm">
              <p className="text-warning flex items-center gap-2 font-medium">
                <Zap className="h-4 w-4" />
                安全属性还没采集
              </p>
              <p className="text-muted-foreground mt-1 text-xs">
                未采集时渠道判定一律拒发（按「不知道」处理，不按「普通货」放行）。
                勾选下面「已采集安全属性」并保存后即可参与渠道筛选。
              </p>
            </div>
          )}

          <Card
            title="物理档案与安全属性"
            hint="长宽高、净重、毛重与包装方式决定体积重与装箱体积；四个安全标记决定渠道可用性与特殊申报。"
          >
            <div className="space-y-5">
              <section className="space-y-3">
                <h3 className="text-copy-13 font-medium">物理参数</h3>
                <div className="grid gap-3 sm:grid-cols-3">
                  <Field label="长（mm）">
                    <input
                      className={control}
                      value={draft.lengthMm}
                      disabled={busy || !canWrite}
                      placeholder="留空 = 未采集"
                      onChange={(e) => patch({ lengthMm: e.target.value })}
                    />
                  </Field>
                  <Field label="宽（mm）">
                    <input
                      className={control}
                      value={draft.widthMm}
                      disabled={busy || !canWrite}
                      placeholder="留空 = 未采集"
                      onChange={(e) => patch({ widthMm: e.target.value })}
                    />
                  </Field>
                  <Field label="高（mm）">
                    <input
                      className={control}
                      value={draft.heightMm}
                      disabled={busy || !canWrite}
                      placeholder="留空 = 未采集"
                      onChange={(e) => patch({ heightMm: e.target.value })}
                    />
                  </Field>
                </div>
                <div className="grid gap-3 sm:grid-cols-3">
                  <Field label="净重（g）">
                    <input
                      className={control}
                      value={draft.netWeightG}
                      disabled={busy || !canWrite}
                      onChange={(e) => patch({ netWeightG: e.target.value })}
                    />
                  </Field>
                  <Field label="毛重（g）">
                    <input
                      className={control}
                      value={draft.grossWeightG}
                      disabled={busy || !canWrite}
                      onChange={(e) => patch({ grossWeightG: e.target.value })}
                    />
                  </Field>
                  <Field label="包装方式">
                    <input
                      className={control}
                      value={draft.packagingType}
                      disabled={busy || !canWrite}
                      placeholder="纸箱 / 泡沫箱 / 真空袋…"
                      onChange={(e) => patch({ packagingType: e.target.value })}
                    />
                  </Field>
                </div>
              </section>

              <section className="space-y-3">
                <h3 className="text-copy-13 font-medium">安全属性</h3>
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={draft.safetyCollected}
                    disabled={busy || !canWrite}
                    onChange={(e) => patch({ safetyCollected: e.target.checked })}
                  />
                  已采集安全属性
                  <span className="text-muted-foreground text-xs">
                    （不勾 = 还没人采集过，渠道判定会拒发）
                  </span>
                </label>
                <div className="grid gap-2 sm:grid-cols-2">
                  {SAFETY_FLAGS.map((flag) => (
                    <label key={flag.key} className="flex items-center gap-2 text-sm">
                      <input
                        type="checkbox"
                        checked={draft[flag.key]}
                        disabled={busy || !canWrite || !draft.safetyCollected}
                        onChange={(e) => patch({ [flag.key]: e.target.checked } as Partial<SafetyDraft>)}
                      />
                      {flag.label}
                    </label>
                  ))}
                </div>
                <p className="text-muted-foreground text-xs">
                  四个标记互不蕴含，各自对应一个申报动作。勾选任一项都必须同时确认「已采集安全属性」。
                </p>
                {/* 规则 B3：危险品必须有类别，否则 16 合规不知道按什么申报。
                    只在勾了危险品时出现 —— 不危险时这个字段没有意义。 */}
                {draft.dangerousGoods && (
                  <Field label="危险品类别（必填）">
                    <input
                      className={control}
                      value={draft.dangerousSubstanceClass}
                      disabled={busy || !canWrite || !draft.safetyCollected}
                      placeholder="UN 编号 / IATA 类别代码 / 内部危险品编码"
                      onChange={(e) => patch({ dangerousSubstanceClass: e.target.value })}
                    />
                  </Field>
                )}
                <Field label="备注（不参与判定）">
                  <input
                    className={control}
                    value={draft.safetyNote}
                    disabled={busy || !canWrite || !draft.safetyCollected}
                    placeholder="例：磁性物已做磁场衰减处理"
                    onChange={(e) => patch({ safetyNote: e.target.value })}
                  />
                </Field>
              </section>

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

              {canWrite && (
                <div className="flex items-center gap-2">
                  <Button onClick={() => void save()} disabled={busy || errors.length > 0}>
                    <Save className="h-4 w-4" />
                    保存
                  </Button>
                  {errors.length > 0 && (
                    <span className="text-muted-foreground text-xs">有 {errors.length} 项待修正</span>
                  )}
                </div>
              )}
            </div>
          </Card>

          {/* 派生值：全部现算，不落库。规则 R3：除数是渠道口径，由用户选。 */}
          <Card
            title="体积重与计费重"
            hint="现算，不落库：体积重 = 体积 ÷ 除数。除数是渠道口径（空运 6000 / 快递 5000），本模块不替 10 物流选。"
          >
            <div className="flex flex-wrap items-end gap-3">
              <div className="w-48">
                <Field label="体积重除数（cm³/kg）">
                  <input
                    className={control}
                    value={divisor}
                    onChange={(e) => setDivisor(e.target.value)}
                    placeholder="留空 = 不给体积重"
                  />
                </Field>
              </div>
              <div className="flex gap-1.5">
                <Button variant="ghost" size="sm" onClick={() => setDivisor('6000')}>
                  空运 6000
                </Button>
                <Button variant="ghost" size="sm" onClick={() => setDivisor('5000')}>
                  快递 5000
                </Button>
              </div>
            </div>
            <dl className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
              <Metric label="体积" value={volumeText(profile)} />
              <Metric label="体积重" value={volumetricText(profile.volumetricWeightKg, divisor)} />
              <Metric
                label="计费重"
                value={
                  profile.chargeableWeightKg === null
                    ? '算不出来'
                    : `${decimal(profile.chargeableWeightKg, 'kg')}（实重与体积重取大）`
                }
              />
              <Metric label="毛重" value={decimal(profile.grossWeightG, 'g')} />
            </dl>
            {profile.requiredDeclarations.length > 0 && (
              <div className="border-warning-soft bg-warning-soft/40 mt-4 rounded-md border px-3 py-2">
                <p className="text-copy-13 font-medium">需要特殊申报</p>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {profile.requiredDeclarations.map((code) => (
                    <Badge key={code} tone="warning">
                      {code}
                    </Badge>
                  ))}
                </div>
                {profile.dangerousGoods && (
                  <p className="text-muted-foreground mt-2 text-xs">
                    危险品类别：{profile.dangerousSubstanceClass || '未填写（保存时会被拒绝）'}
                  </p>
                )}
              </div>
            )}
          </Card>

          <Card title="渠道判定口径" hint="供 10 物流调用时的判定规则预览。">
            <div className="flex flex-wrap items-center gap-2">
              <Badge tone={state.tone}>{state.label}</Badge>
              <span className="text-muted-foreground text-xs">
                {state.active.length === 0
                  ? profile.safetyCollected
                    ? '已采集且四项都不带：渠道可以放行，但无需任何特殊申报。'
                    : '未采集：任何渠道判定都拒发，先去补采集。'
                  : `命中 ${state.active.length} 项：对应渠道可能拒收，且必须办理对应申报。`}
              </span>
            </div>
          </Card>
        </>
      )}
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

/** 与同目录其他工作区（sku-detail / sku-attribute-override）同形状的分区卡。 */
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
