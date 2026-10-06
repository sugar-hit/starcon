'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RefreshCw, Search } from 'lucide-react';
import type { LegalEntityView } from '../../../../../packages/api-contracts/src/catalog';
import { api } from '@/lib/api';
import { useIdentity } from '../iam/identity-shell';

/**
 * 17.1.2.1 法人档案。
 *
 * 法人是货权、收付款与记账主体，一个租户可以持有多个法人——店铺收入记到哪个主体、
 * 库存货权归谁、结算打进哪个账，都从这里决定。本位币与舍入规则也挂在法人上，
 * 决定 12 财务总账的记账基准；租户的默认币种只是界面展示默认，两者不是一回事。
 */
export function LegalEntityWorkspace() {
  const { session } = useIdentity();
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [search, setSearch] = useState('');
  const [entities, setEntities] = useState<LegalEntityView[]>([]);
  const [editing, setEditing] = useState<LegalEntityView | null | 'new'>(null);

  const can = useCallback(
    (permission: string) => session.permissions.includes(permission),
    [session.permissions],
  );
  const readable = can('identity.legal_entity.read');

  const load = useCallback(async () => {
    setError('');
    setNotice('');
    if (!readable) {
      // 没有读权限就不去请求：拿到空数组后显示「还没有法人档案」是在骗人，
      // 用户会以为公司没建法人，反复去建（而建法人同样会被 403）。
      setReady(true);
      return;
    }
    try {
      setEntities(await api<LegalEntityView[]>('identity/legal-entities'));
    } catch (e) {
      setError(e instanceof Error ? e.message : '请求失败，请重试');
    } finally {
      setReady(true);
    }
  }, [readable]);

  useEffect(() => {
    void load();
  }, [load]);

  async function remove(entity: LegalEntityView) {
    if (!window.confirm(`删除法人「${entity.legalName}」？此操作不可撤销。`)) return;
    setBusy(true);
    try {
      await api(`identity/legal-entities/${entity.id}`, 'DELETE');
      setNotice('法人已删除');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败，请重试');
    } finally {
      setBusy(false);
    }
  }

  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在加载法人档案…
      </p>
    );

  if (!readable)
    return (
      <p className="rounded-lg border p-6 text-sm">
        当前角色没有法人档案查看权限，请联系租户所有者开通。
      </p>
    );

  const term = search.trim().toLowerCase();
  const filtered = entities.filter((e) =>
    `${e.code} ${e.legalName} ${e.registrationNo} ${e.taxNo}`
      .toLowerCase()
      .includes(term),
  );

  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <p className="text-copy-13 text-muted-foreground">
          法人决定店铺收入的归属主体、库存货权与记账本位币。
        </p>
        <div className="flex items-center gap-2">
          <label className="relative flex items-center">
            <Search className="text-muted-foreground pointer-events-none absolute left-2.5 h-4 w-4" />
            <input
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              placeholder="搜索法人"
              aria-label="搜索法人"
              className="border-border bg-card text-copy-13 focus-visible:border-ring h-8 w-56 rounded-md border pl-8 pr-2 outline-none"
            />
          </label>
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
          {can('identity.legal_entity.create') && (
            <Button size="sm" onClick={() => setEditing('new')}>
              <Plus className="h-4 w-4" />
              新建法人
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

      {editing && (
        <LegalEntityForm
          entity={editing === 'new' ? null : editing}
          busy={busy}
          onCancel={() => setEditing(null)}
          onSubmit={async (input) => {
            setBusy(true);
            try {
              if (editing === 'new') {
                await api('identity/legal-entities', 'POST', input);
                setNotice('法人已创建');
              } else {
                await api(`identity/legal-entities/${editing.id}`, 'PUT', input);
                setNotice('法人档案已更新');
              }
              setEditing(null);
              await load();
            } catch (e) {
              setError(e instanceof Error ? e.message : '保存失败，请重试');
            } finally {
              setBusy(false);
            }
          }}
        />
      )}

      {filtered.length === 0 ? (
        <div className="bg-card rounded-lg border p-8 text-center">
          <p className="text-copy-13 text-muted-foreground">还没有法人档案</p>
        </div>
      ) : (
        <ul className="space-y-2">
          {filtered.map((e) => (
            <li key={e.id} className="bg-card rounded-md border p-4">
              <div className="flex flex-wrap items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-copy-15 flex flex-wrap items-center gap-2 font-medium">
                    {e.legalName}
                    <Badge tone={e.status === 'ACTIVE' ? 'success' : 'warning'}>
                      {e.status === 'ACTIVE' ? '启用' : '停用'}
                    </Badge>
                    {e.referenceCount > 0 && (
                      <Badge tone="neutral">{e.referenceCount} 项引用</Badge>
                    )}
                  </p>
                  <dl className="text-copy-12 text-muted-foreground mt-2 grid gap-x-6 gap-y-1 sm:grid-cols-2 lg:grid-cols-4">
                    <div>
                      <dt className="inline">编码：</dt>
                      <dd className="inline">{e.code}</dd>
                    </div>
                    <div>
                      <dt className="inline">本位币：</dt>
                      <dd className="inline">{e.baseCurrency}</dd>
                    </div>
                    <div>
                      <dt className="inline">注册国：</dt>
                      <dd className="inline">{e.registrationCountry}</dd>
                    </div>
                    <div>
                      <dt className="inline">登记号：</dt>
                      <dd className="inline break-all">{e.registrationNo || '—'}</dd>
                    </div>
                    <div>
                      <dt className="inline">税号：</dt>
                      <dd className="inline break-all">{e.taxNo || '—'}</dd>
                    </div>
                    <div>
                      <dt className="inline">金额精度：</dt>
                      <dd className="inline">{e.amountScale} 位 · {roundingLabel(e.rounding)}</dd>
                    </div>
                  </dl>
                </div>
                <div className="flex items-center gap-2">
                  {can('identity.legal_entity.update') && (
                    <Button size="sm" variant="ghost" onClick={() => setEditing(e)}>
                      编辑
                    </Button>
                  )}
                  {can('identity.legal_entity.delete') && (
                    <Button size="sm" variant="ghost" disabled={busy} onClick={() => void remove(e)}>
                      删除
                    </Button>
                  )}
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

function LegalEntityForm({
  entity,
  busy,
  onCancel,
  onSubmit,
}: {
  entity: LegalEntityView | null;
  busy: boolean;
  onCancel: () => void;
  onSubmit: (input: {
    code: string;
    legalName: string;
    registrationCountry: string;
    registrationNo: string;
    taxNo: string;
    baseCurrency: string;
    address: string;
    amountScale: number;
    quantityScale: number;
    rounding: string;
    status: string;
  }) => Promise<void>;
}) {
  const [code, setCode] = useState(entity?.code ?? '');
  const [legalName, setLegalName] = useState(entity?.legalName ?? '');
  const [registrationCountry, setRegistrationCountry] = useState(entity?.registrationCountry ?? 'CN');
  const [registrationNo, setRegistrationNo] = useState(entity?.registrationNo ?? '');
  const [taxNo, setTaxNo] = useState(entity?.taxNo ?? '');
  const [baseCurrency, setBaseCurrency] = useState(entity?.baseCurrency ?? 'USD');
  const [address, setAddress] = useState(entity?.address ?? '');
  const [amountScale, setAmountScale] = useState(String(entity?.amountScale ?? 2));
  const [quantityScale, setQuantityScale] = useState(String(entity?.quantityScale ?? 3));
  const [rounding, setRounding] = useState(entity?.rounding ?? 'HALF_UP');
  const [status, setStatus] = useState(entity?.status ?? 'ACTIVE');

  return (
    <div className="bg-card space-y-4 rounded-md border p-4">
      <p className="text-copy-14 font-medium">{entity ? '编辑法人' : '新建法人'}</p>
      <div className="grid gap-3 md:grid-cols-2 lg:grid-cols-3">
        <Field label="法人编码" hint="大写字母数字下划线中划线">
          <input
            value={code}
            onChange={(e) => setCode(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="法定名称">
          <input
            value={legalName}
            onChange={(e) => setLegalName(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="本位币" hint="决定总账记账基准">
          <input
            value={baseCurrency}
            onChange={(e) => setBaseCurrency(e.target.value.toUpperCase())}
            className={inputClass}
          />
        </Field>
        <Field label="注册国家" hint="两位大写代码">
          <input
            value={registrationCountry}
            onChange={(e) => setRegistrationCountry(e.target.value.toUpperCase())}
            className={inputClass}
          />
        </Field>
        <Field label="登记号">
          <input
            value={registrationNo}
            onChange={(e) => setRegistrationNo(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="税务登记号">
          <input value={taxNo} onChange={(e) => setTaxNo(e.target.value)} className={inputClass} />
        </Field>
        <Field label="金额精度" hint="0-6 位">
          <input
            type="number"
            min={0}
            max={6}
            value={amountScale}
            onChange={(e) => setAmountScale(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="数量精度" hint="0-6 位">
          <input
            type="number"
            min={0}
            max={6}
            value={quantityScale}
            onChange={(e) => setQuantityScale(e.target.value)}
            className={inputClass}
          />
        </Field>
        <Field label="舍入方式">
          <select
            value={rounding}
            onChange={(e) => setRounding(e.target.value)}
            className={inputClass}
          >
            <option value="HALF_UP">四舍五入</option>
            <option value="HALF_EVEN">银行家舍入</option>
            <option value="DOWN">向下取整</option>
            <option value="UP">向上取整</option>
          </select>
        </Field>
        <Field label="状态">
          <select value={status} onChange={(e) => setStatus(e.target.value)} className={inputClass}>
            <option value="ACTIVE">启用</option>
            <option value="SUSPENDED">停用</option>
          </select>
        </Field>
        <div className="md:col-span-2 lg:col-span-3">
          <Field label="注册地址">
            <input
              value={address}
              onChange={(e) => setAddress(e.target.value)}
              className={inputClass}
            />
          </Field>
        </div>
      </div>
      <div className="flex gap-2">
        <Button
          size="sm"
          disabled={busy || !code || !legalName || baseCurrency.length !== 3}
          onClick={() =>
            void onSubmit({
              code: code.trim(),
              legalName: legalName.trim(),
              registrationCountry: registrationCountry.trim() || 'CN',
              registrationNo: registrationNo.trim(),
              taxNo: taxNo.trim(),
              baseCurrency: baseCurrency.trim().toUpperCase(),
              address: address.trim(),
              amountScale: Number(amountScale),
              quantityScale: Number(quantityScale),
              rounding,
              status,
            })
          }
        >
          保存
        </Button>
        <Button size="sm" variant="ghost" onClick={onCancel}>
          取消
        </Button>
      </div>
    </div>
  );
}

const inputClass =
  'border-border bg-background text-copy-13 focus-visible:border-ring h-9 rounded-md border px-2 outline-none';

function Field({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="block">
      <span className="text-copy-13 mb-1 block font-medium">{label}</span>
      {children}
      {hint && <span className="text-copy-12 text-muted-foreground mt-1 block">{hint}</span>}
    </label>
  );
}

function roundingLabel(value: string) {
  return (
    { HALF_UP: '四舍五入', HALF_EVEN: '银行家舍入', DOWN: '向下取整', UP: '向上取整' }[value] ?? value
  );
}