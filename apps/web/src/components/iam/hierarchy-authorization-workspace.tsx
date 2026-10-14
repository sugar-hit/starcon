'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { AlertTriangle, Check, RefreshCw, ShieldAlert, X } from 'lucide-react';
import type {
  AccessDecisionView,
  ChildView,
  HierarchyGrantInput,
  HierarchyGrantUpdate,
  HierarchyGrantView,
} from '../../../../../packages/api-contracts/src/index';
import { api } from '@/lib/api';
import { Field, control } from '../common/fields';
import { useIdentity } from './identity-shell';

/**
 * 17.1.1.3 层级授权。
 *
 * <p>这个页面管的是「父租户能看到子租户的哪些数据、到什么时候为止」。蓝图的立场很明确：
 * 父子关系只表示结构，**不自动赋权**——所以默认状态是「没有授权」，界面上也必须把这件事说清楚，
 * 否则用户会以为集团天然就能看子公司数据。
 *
 * <p>两件事在同一个页面：上半部分是授权配置（增改查 + 撤销），下半部分是**访问判定**。
 * 后者不是调试工具——它和配置端点读同一套权限，判定结果里的 reason 正是「为什么现在没有权限」
 * 的答案。把判定放在页面上，管理员就不用去猜是授权过期了还是压根没配过。
 *
 * <p>前端只做展示与录入。真正的判定永远在服务端（{@code evaluate_hierarchy_access}），
 * 这里的允许/拒绝不是权限控制，只是给配置者一个即时反馈。
 */

type Form = {
  childTenantId: string;
  domains: string;
  accessLevel: 'READ' | 'WRITE';
  validFrom: string;
  validUntil: string;
  note: string;
};

const emptyForm: Form = {
  childTenantId: '',
  domains: '',
  accessLevel: 'READ',
  validFrom: '',
  validUntil: '',
  note: '',
};

/** reason → 人话。服务端返回的是稳定枚举，这里只做展示，不参与判定。 */
const reasons: Record<string, string> = {
  GRANTED: '已授权',
  NO_GRANT: '没有配置任何授权',
  EXPIRED: '授权已过期',
  NOT_YET_VALID: '授权尚未生效',
  REVOKED: '授权已撤销',
  DOMAIN_NOT_GRANTED: '该数据域不在授权范围内',
  WRITE_NOT_GRANTED: '只有只读权限，不能写入',
  ROLE_NOT_AUTHORIZED: '当前角色没有层级授权权限',
  SESSION_INVALID: '登录已失效，请重新登录',
  TARGET_UNSPECIFIED: '未指定目标租户',
};

/** 未知状态按「未生效」展示：宁可显得更保守，也不要把一个认不出的状态当成生效中。 */
const unknownEffective = { label: '未知', tone: 'neutral' } as const;

const effectiveLabels: Record<string, { label: string; tone: 'success' | 'warning' | 'neutral' }> =
  {
    ACTIVE: { label: '生效中', tone: 'success' },
    PENDING: { label: '未生效', tone: 'neutral' },
    EXPIRED: { label: '已过期', tone: 'warning' },
    REVOKED: { label: '已撤销', tone: 'neutral' },
  };

function moment(value: string | null) {
  if (!value) return '—';
  const at = new Date(value);
  return Number.isNaN(at.getTime()) ? '—' : at.toLocaleString('zh-CN', { hour12: false });
}

/** datetime-local 的值是「本地墙上时间」，提交前补上时区偏移，让后端拿到真实瞬间。 */
function toInstant(local: string) {
  if (!local) return null;
  const at = new Date(local);
  return Number.isNaN(at.getTime()) ? null : at.toISOString();
}

/** 解析逗号分隔的数据域。空串返回 null 表示「还没填」，由调用方给提示而不是静默当成全域。 */
function parseDomains(raw: string): string[] | null {
  const parts = raw
    .split(/[,，\s]+/)
    .map((d) => d.trim())
    .filter(Boolean);
  if (parts.length === 0) return null;
  const valid = /^([a-z][a-z0-9_]*\.[a-z][a-z0-9_]*)+$/;
  return parts.every((d) => valid.test(d)) ? parts : null;
}

export function HierarchyAuthorizationWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [grants, setGrants] = useState<HierarchyGrantView[]>([]);
  const [children, setChildren] = useState<ChildView[]>([]);
  const [form, setForm] = useState<Form | null>(null);
  const [editing, setEditing] = useState<string | null>(null);
  const [formError, setFormError] = useState('');

  // 判定面板
  const [checkChild, setCheckChild] = useState('');
  const [checkDomain, setCheckDomain] = useState('');
  const [checkWrite, setCheckWrite] = useState(false);
  const [decision, setDecision] = useState<AccessDecisionView | null>(null);
  const [checking, setChecking] = useState(false);

  const load = useCallback(async () => {
    setError('');
    try {
      // 授权记录与候选子租户一次取回：可选的目标集合如果来自另一个快照，
      // 会出现「列表里有这条授权，但下拉框里选不到对应子租户」的中间态。
      const [list, kids] = await Promise.all([
        api<HierarchyGrantView[]>('identity/hierarchy-authorizations'),
        api<ChildView[]>('identity/children'),
      ]);
      setGrants(list);
      setChildren(kids);
    } catch (e) {
      setError(e instanceof Error ? e.message : '加载失败，请重试');
    } finally {
      setReady(true);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const startCreate = () => {
    setEditing(null);
    setForm({ ...emptyForm, validFrom: new Date().toISOString().slice(0, 16) });
    setFormError('');
  };

  const startEdit = (grant: HierarchyGrantView) => {
    setEditing(grant.id);
    setForm({
      childTenantId: grant.childTenantId,
      domains: grant.domains.join(', '),
      accessLevel: grant.accessLevel === 'WRITE' ? 'WRITE' : 'READ',
      validFrom: grant.validFrom.slice(0, 16),
      validUntil: grant.validUntil ? grant.validUntil.slice(0, 16) : '',
      note: grant.note ?? '',
    });
    setFormError('');
  };

  const save = async () => {
    if (!form) return;
    const domains = parseDomains(form.domains);
    if (!domains) {
      setFormError('数据域格式不正确，例如 catalog.spu、catalog.sku');
      return;
    }
    const validFrom = toInstant(form.validFrom);
    if (!validFrom) {
      setFormError('请填写有效期开始时间');
      return;
    }
    const validUntil = toInstant(form.validUntil);
    if (form.validUntil && !validUntil) {
      setFormError('有效期结束时间格式不正确');
      return;
    }
    if (validUntil && new Date(validUntil) <= new Date(validFrom)) {
      setFormError('有效期结束时间必须晚于开始时间');
      return;
    }
    setBusy(true);
    setFormError('');
    setError('');
    try {
      if (editing) {
        const body: HierarchyGrantUpdate = {
          domains,
          accessLevel: form.accessLevel,
          validFrom,
          validUntil,
          note: form.note.trim() || null,
        };
        await api(`identity/hierarchy-authorizations/${editing}`, 'PUT', body);
        setNotice('授权范围已更新');
      } else {
        const body: HierarchyGrantInput = {
          childTenantId: form.childTenantId,
          domains,
          accessLevel: form.accessLevel,
          validFrom,
          validUntil,
          note: form.note.trim() || null,
        };
        await api('identity/hierarchy-authorizations', 'POST', body);
        setNotice('已为该子租户开通授权');
      }
      setForm(null);
      setEditing(null);
      await load();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : '保存失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (grant: HierarchyGrantView) => {
    // 撤销是终态：服务端不允许改回生效，这里必须先说清楚，否则用户以为只是暂时收起。
    if (
      !window.confirm(
        `撤销对「${grant.childName}」的授权？\n撤销后立即失效且不可恢复，需要重新配置。`,
      )
    )
      return;
    setBusy(true);
    setError('');
    try {
      await api(`identity/hierarchy-authorizations/${grant.id}/revoke`, 'POST');
      setNotice('授权已撤销');
      if (editing === grant.id) {
        setForm(null);
        setEditing(null);
      }
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '撤销失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  const runCheck = async () => {
    if (!checkChild) {
      setError('请选择要判定的目标子租户');
      return;
    }
    if (!parseDomains(checkDomain)) {
      setError('请填写要访问的数据域，例如 catalog.spu');
      return;
    }
    setChecking(true);
    setError('');
    try {
      setDecision(
        await api<AccessDecisionView>('identity/hierarchy-authorizations/check', 'POST', {
          childTenantId: checkChild,
          domain: parseDomains(checkDomain)![0],
          requireWrite: checkWrite,
        }),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '判定失败，请重试');
    } finally {
      setChecking(false);
    }
  };

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载层级授权…
      </p>
    );

  const managed = session.grants.some((g) => g.roleCode === 'OWNER' && g.scopeMode === 'ALL');

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          父子租户之间默认互相隔离。集团要看子公司的数据，必须在这里显式授权到具体数据域与有效期。
          判定在服务端完成，前端菜单隐藏不构成权限控制。
        </p>
        <div className="flex gap-2">
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {managed && (
            <Button size="sm" onClick={startCreate}>
              新增授权
            </Button>
          )}
        </div>
      </div>

      {error && (
        <div
          role="alert"
          className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
        >
          {error}
          <Button variant="link" onClick={() => void load()}>
            重试
          </Button>
        </div>
      )}
      {notice && (
        <p role="status" className="text-success text-sm">
          {notice}
        </p>
      )}

      {form && (
        <section className="bg-card space-y-4 rounded-lg border p-6">
          <h2 className="text-copy-15 font-semibold">{editing ? '修改授权' : '新增授权'}</h2>
          <div className="grid gap-4 sm:grid-cols-2">
            <Field label="目标子租户" hint="只能授权给本租户的后代，且一对子租户只有一条授权">
              <select
                className={control}
                value={form.childTenantId}
                disabled={editing !== null}
                onChange={(e) => setForm({ ...form, childTenantId: e.target.value })}
              >
                <option value="">请选择子租户</option>
                {children.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}（{c.code}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="权限级别" hint="只读不能推出可写，写权限必须显式选择">
              <select
                className={control}
                value={form.accessLevel}
                onChange={(e) =>
                  setForm({ ...form, accessLevel: e.target.value as 'READ' | 'WRITE' })
                }
              >
                <option value="READ">只读</option>
                <option value="WRITE">可读可写</option>
              </select>
            </Field>
            <Field label="数据域" hint="多个用逗号分隔，例如 catalog.spu, catalog.sku">
              <input
                className={control}
                value={form.domains}
                placeholder="catalog.spu, catalog.sku"
                onChange={(e) => setForm({ ...form, domains: e.target.value })}
              />
            </Field>
            <Field label="备注" hint="记下授权原因，审计里会一并留存">
              <input
                className={control}
                value={form.note}
                maxLength={500}
                onChange={(e) => setForm({ ...form, note: e.target.value })}
              />
            </Field>
            <Field label="有效期开始" hint="留空表示立即生效">
              <input
                type="datetime-local"
                className={control}
                value={form.validFrom}
                onChange={(e) => setForm({ ...form, validFrom: e.target.value })}
              />
            </Field>
            <Field label="有效期结束" hint="留空表示长期有效；到期后自动失效但记录仍在">
              <input
                type="datetime-local"
                className={control}
                value={form.validUntil}
                onChange={(e) => setForm({ ...form, validUntil: e.target.value })}
              />
            </Field>
          </div>
          {formError && (
            <p role="alert" className="text-destructive text-sm">
              {formError}
            </p>
          )}
          <div className="flex gap-2">
            <Button disabled={busy} onClick={() => void save()}>
              {busy ? '保存中…' : '保存'}
            </Button>
            <Button
              variant="outline"
              disabled={busy}
              onClick={() => {
                setForm(null);
                setEditing(null);
                setFormError('');
              }}
            >
              <X className="h-4 w-4" />
              取消
            </Button>
          </div>
        </section>
      )}

      <section className="space-y-3">
        <h2 className="text-copy-15 font-semibold">已配置的授权</h2>
        {grants.length === 0 ? (
          <div className="bg-card rounded-lg border p-8 text-center">
            <p className="text-copy-13 text-muted-foreground">
              还没有为任何子租户开通授权。这正是默认状态：集团看不到子公司的业务数据，
              直到有人显式授权。
            </p>
          </div>
        ) : (
          <ul className="space-y-2">
            {grants.map((grant) => {
              const effective = effectiveLabels[grant.effectiveStatus] ?? unknownEffective;
              const locked = grant.effectiveStatus === 'REVOKED';
              return (
                <li
                  key={grant.id}
                  className={`bg-card rounded-md border p-4 ${locked ? 'opacity-70' : ''}`}
                  data-effective={grant.effectiveStatus}
                >
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div className="min-w-0 space-y-1">
                      <p className="text-copy-15 flex flex-wrap items-center gap-2 font-medium">
                        {grant.childName}
                        <span className="text-copy-13 text-muted-foreground">
                          {grant.childCode}
                        </span>
                        <Badge tone={grant.accessLevel === 'WRITE' ? 'info' : 'neutral'}>
                          {grant.accessLevel === 'WRITE' ? '可读可写' : '只读'}
                        </Badge>
                        <Badge tone={effective.tone}>{effective.label}</Badge>
                        {grant.childStatus !== 'ACTIVE' && (
                          <Badge tone="warning">子租户已停用</Badge>
                        )}
                      </p>
                      <p className="text-copy-13 text-muted-foreground">
                        数据域：{grant.domains.join('、')}
                      </p>
                      <p className="text-copy-13 text-muted-foreground">
                        有效期：{moment(grant.validFrom)} — {moment(grant.validUntil)}
                        {grant.validUntil === null && '（长期）'}
                      </p>
                      {grant.note && (
                        <p className="text-copy-13 text-muted-foreground">备注：{grant.note}</p>
                      )}
                      <p className="text-copy-13 text-muted-foreground">
                        配置于 {moment(grant.createdAt)}
                        {grant.revokedAt && ` · 撤销于 ${moment(grant.revokedAt)}`}
                      </p>
                    </div>
                    {managed && !locked && (
                      <div className="flex gap-2">
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => startEdit(grant)}
                        >
                          修改
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          disabled={busy}
                          onClick={() => void revoke(grant)}
                        >
                          撤销
                        </Button>
                      </div>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </section>

      <section className="bg-card space-y-4 rounded-lg border p-6">
        <h2 className="text-copy-15 font-semibold">访问判定</h2>
        <p className="text-copy-13 text-muted-foreground">
          用当前身份试一次：父租户能不能访问某个子租户的某个数据域。结果与服务端对真实业务请求的判定一致。
        </p>
        <div className="grid gap-4 sm:grid-cols-3">
          <Field label="目标子租户">
            <select
              className={control}
              value={checkChild}
              onChange={(e) => setCheckChild(e.target.value)}
            >
              <option value="">请选择子租户</option>
              {children.map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}（{c.code}）
                </option>
              ))}
            </select>
          </Field>
          <Field label="数据域">
            <input
              className={control}
              value={checkDomain}
              placeholder="catalog.spu"
              onChange={(e) => setCheckDomain(e.target.value)}
            />
          </Field>
          <Field label="所需权限" hint="勾选表示需要写入权限">
            <label className="flex items-center gap-2 text-sm">
              <input
                type="checkbox"
                checked={checkWrite}
                onChange={(e) => setCheckWrite(e.target.checked)}
              />
              需要可写
            </label>
          </Field>
        </div>
        <Button variant="outline" disabled={checking} onClick={() => void runCheck()}>
          <ShieldAlert className="h-4 w-4" />
          {checking ? '判定中…' : '执行判定'}
        </Button>
        {decision && (
          <div
            role="status"
            data-allowed={decision.allowed}
            className={`rounded-md border p-4 text-sm ${
              decision.allowed
                ? 'border-success/30 bg-success/5 text-success'
                : 'border-destructive/30 bg-destructive/5 text-destructive'
            }`}
          >
            <p className="flex items-center gap-2 font-medium">
              {decision.allowed ? (
                <Check className="h-4 w-4" />
              ) : (
                <AlertTriangle className="h-4 w-4" />
              )}
              {decision.allowed ? '允许访问' : '拒绝访问'}：
              {reasons[decision.reason] ?? decision.reason}
            </p>
            {decision.grantId && (
              <p className="text-copy-13 mt-2">
                命中授权 {decision.grantId}，级别{' '}
                {decision.accessLevel === 'WRITE' ? '可读可写' : '只读'}，有效期{' '}
                {moment(decision.validFrom)} — {moment(decision.validUntil)}
              </p>
            )}
          </div>
        )}
      </section>
    </div>
  );
}
