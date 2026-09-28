'use client';
import { useCallback, useEffect, useState } from 'react';
import Link from 'next/link';
import {
  ArrowLeft,
  Plus,
  RefreshCw,
  Package,
  Layers,
  SlidersHorizontal,
  Pencil,
  LogOut,
} from 'lucide-react';
import { Button } from '@starcon/ui';
import type {
  Access,
  LoginResult,
  Product,
  Detail,
  Attribute,
  Sku,
} from '../../../../../packages/api-contracts/src/catalog';
import { api, ApiError } from './api';
import { control, Field, statuses, modes, types } from './fields';
import { SpuEditor, AttributeEditor, SkuEditor } from './product-editors';
import { ProductRecords } from './product-records';
import { AdministrationPanel } from './administration-panel';
import {
  canManage,
  canAccessRecord,
  switchTenant,
  type AdministrationKind,
} from './administration-model';

type Editing =
  | { kind: 'spu'; product?: Product }
  | { kind: 'attribute'; attribute?: Attribute }
  | { kind: 'sku'; sku?: Sku }
  | null;
export type WorkspaceKind = 'products' | 'research' | 'compliance' | 'assets' | AdministrationKind;
const destinations = [
  ['products', '/products', '商品档案'],
  ['research', '/products/research', '选品与开发'],
  ['compliance', '/products/compliance', '合规与资质'],
  ['assets', '/products/assets', '素材与翻译'],
  ['roles', '/products/roles', '角色配置'],
  ['members', '/products/members', '成员管理'],
  ['departments', '/products/departments', '部门管理'],
  ['children', '/products/children', '子租户管理'],
] as const;

export function CatalogWorkspace({ kind = 'products' }: { kind?: WorkspaceKind }) {
  const [session, setSession] = useState<Access | null>(null);
  const [ready, setReady] = useState(false);
  const [error, setError] = useState('');
  const [username, setUsername] = useState('local-admin');
  const [password, setPassword] = useState('');
  const [memberships, setMemberships] = useState<LoginResult['memberships']>([]);
  const [availableMemberships, setAvailableMemberships] = useState<LoginResult['memberships']>([]);
  const [membershipId, setMembershipId] = useState('');
  const [busy, setBusy] = useState(false);
  const [products, setProducts] = useState<Product[]>([]);
  const [detail, setDetail] = useState<Detail | null>(null);
  const [search, setSearch] = useState('');
  const [editing, setEditing] = useState<Editing>(null);
  const [section, setSection] = useState<'attributes' | 'skus'>('attributes');
  const [notice, setNotice] = useState('');
  const can = (permission: string, record = detail?.product) =>
    session !== null && canAccessRecord(session, `catalog.${permission}`, record);
  const handleError = useCallback((e: unknown) => {
    setError(e instanceof Error ? e.message : '请求失败，请重试');
    if (e instanceof ApiError && e.status === 401) {
      setSession(null);
      setDetail(null);
      setProducts([]);
    }
  }, []);
  const load = useCallback(async () => {
    try {
      setError('');
      const access = await api<Access>('auth/session');
      setSession(access);
      setAvailableMemberships(await api<LoginResult['memberships']>('auth/memberships'));
      setDetail(null);
      setProducts([]);
      if (access.permissions.includes('catalog.spu.read'))
        setProducts(await api<Product[]>('catalog/products'));
    } catch (e) {
      if (e instanceof ApiError && e.status === 401) {
        setSession(null);
        setDetail(null);
        setProducts([]);
      } else handleError(e);
    } finally {
      setReady(true);
    }
  }, [handleError]);
  useEffect(() => {
    void load();
  }, [load]);
  async function refresh(id?: string) {
    setProducts(await api<Product[]>('catalog/products'));
    if (id) setDetail(await api<Detail>(`catalog/products/${id}`));
  }
  async function open(id: string) {
    try {
      setError('');
      setDetail(await api<Detail>(`catalog/products/${id}`));
    } catch (e) {
      handleError(e);
    }
  }
  async function remove(path: string, message: string, parent?: string) {
    if (!window.confirm(message)) return;
    setBusy(true);
    try {
      await api(path, 'DELETE');
      if (!parent) setDetail(null);
      await refresh(parent);
      setNotice('已删除草稿记录');
    } catch (e) {
      handleError(e);
    } finally {
      setBusy(false);
    }
  }
  if (!ready)
    return (
      <p role="status" className="text-muted-foreground p-6 text-sm">
        正在连接商品工作区…
      </p>
    );
  if (!session)
    return (
      <div className="bg-card mx-auto mt-8 max-w-md rounded-lg border p-6">
        <Package className="text-brand mb-3 h-6 w-6" />
        <h2 className="text-lg font-semibold">登录商品工作区</h2>
        <p className="text-muted-foreground mb-5 mt-2 text-sm">
          使用你的账号登录，并选择已获授权的租户。
        </p>
        <form
          className="space-y-4"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError('');
            try {
              const result = await api<LoginResult>('auth/login', 'POST', {
                username,
                password,
                membershipId: membershipId || null,
              });
              if (result.memberships.length > 1 && !membershipId) {
                setMemberships(result.memberships);
                setMembershipId(result.memberships[0]?.id ?? '');
              } else {
                setPassword('');
                await load();
              }
            } catch (e) {
              handleError(e);
            } finally {
              setBusy(false);
            }
          }}
        >
          <Field label="账号">
            <input
              required
              autoComplete="username"
              className={control}
              value={username}
              onChange={(e) => {
                setUsername(e.target.value);
                setMemberships([]);
                setMembershipId('');
              }}
            />
          </Field>
          <Field label="密码">
            <input
              required
              type="password"
              autoComplete="current-password"
              className={control}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </Field>
          {memberships.length > 1 && (
            <Field label="选择租户">
              <select
                className={control}
                value={membershipId}
                onChange={(e) => setMembershipId(e.target.value)}
              >
                {memberships.map((m) => (
                  <option key={m.id} value={m.id}>
                    {m.name}
                  </option>
                ))}
              </select>
            </Field>
          )}
          <p role="alert" className="text-destructive text-sm">
            {error}
          </p>
          <Button type="submit" disabled={busy} className="w-full">
            {busy ? '登录中…' : '登录'}
          </Button>
        </form>
      </div>
    );
  const filtered = products.filter((p) =>
    `${p.code} ${p.name} ${p.brand} ${p.category}`.toLowerCase().includes(search.toLowerCase()),
  );
  return (
    <div className="space-y-5">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <nav aria-label="商品中心功能" className="flex flex-wrap gap-1">
          {destinations
            .filter(
              ([key]) =>
                !['roles', 'members', 'departments', 'children'].includes(key) ||
                canManage(session, key as AdministrationKind),
            )
            .map(([key, href, label]) => (
              <Link
                key={key}
                href={href}
                aria-current={kind === key ? 'page' : undefined}
                className={`rounded-md px-3 py-2 text-sm ${kind === key ? 'bg-muted text-foreground font-medium' : 'text-muted-foreground hover:bg-muted'}`}
              >
                {label}
              </Link>
            ))}
        </nav>
        <div className="text-muted-foreground flex items-center gap-2 text-xs">
          <span>{session.tenantName}</span>
          {availableMemberships.length > 1 && (
            <select
              aria-label="切换租户"
              className={control}
              value={session.membershipId}
              disabled={busy}
              onChange={async (e) => {
                setBusy(true);
                try {
                  await switchTenant(e.target.value);
                  setDetail(null);
                  setEditing(null);
                  setProducts([]);
                  await load();
                } catch (error) {
                  handleError(error);
                } finally {
                  setBusy(false);
                }
              }}
            >
              {availableMemberships.map((m) => (
                <option key={m.id} value={m.id}>
                  {m.name}
                </option>
              ))}
            </select>
          )}
          <Button
            variant="ghost"
            size="sm"
            onClick={async () => {
              try {
                await api('auth/logout', 'POST');
                setSession(null);
                setDetail(null);
                setProducts([]);
              } catch (e) {
                handleError(e);
              }
            }}
          >
            <LogOut className="h-3.5 w-3.5" />
            退出
          </Button>
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
      {kind === 'roles' || kind === 'members' || kind === 'departments' || kind === 'children' ? (
        <AdministrationPanel
          key={`${session.tenantId}:${kind}`}
          kind={kind}
          session={session}
          reloadSession={load}
          onError={handleError}
        />
      ) : kind !== 'products' ? (
        <ProductRecords
          key={`${session.tenantId}:${kind}`}
          kind={kind}
          products={products}
          session={session}
          onError={handleError}
        />
      ) : !can('spu.read') ? (
        <p className="bg-card rounded-lg border p-8 text-sm">
          当前角色没有商品查看权限，请联系租户管理员。
        </p>
      ) : detail ? (
        <>
          <div className="flex flex-wrap items-start justify-between gap-3">
            <div>
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setDetail(null);
                  setNotice('');
                }}
              >
                <ArrowLeft className="h-4 w-4" />
                返回商品列表
              </Button>
              <h2 className="mt-2 text-xl font-semibold">{detail.product.name}</h2>
              <p className="text-muted-foreground mt-1 text-sm">
                {detail.product.code} · {detail.product.brand || '未设置品牌'} ·{' '}
                {detail.product.category || '未分类'}
              </p>
            </div>
            <div className="flex items-center gap-2">
              <span className="rounded border px-2 py-1 text-xs">
                {statuses[detail.product.status]}
              </span>
              {can('spu.update') && (
                <Button
                  variant="outline"
                  onClick={() => setEditing({ kind: 'spu', product: detail.product })}
                >
                  <Pencil className="h-4 w-4" />
                  编辑资料
                </Button>
              )}
            </div>
          </div>
          {detail.product.description && (
            <p className="text-muted-foreground whitespace-pre-wrap text-sm">
              {detail.product.description}
            </p>
          )}
          <div className="flex flex-wrap items-center justify-between gap-3 border-b pb-3">
            <div className="flex flex-wrap gap-2">
              <Button
                variant={section === 'attributes' ? 'secondary' : 'ghost'}
                onClick={() => setSection('attributes')}
              >
                <SlidersHorizontal className="h-4 w-4" />
                属性配置与公共值 ({detail.attributes.length})
              </Button>
              <Button
                variant={section === 'skus' ? 'secondary' : 'ghost'}
                onClick={() => setSection('skus')}
              >
                <Layers className="h-4 w-4" />
                SKU 完整属性 ({detail.skus.length})
              </Button>
            </div>
            {section === 'attributes' && can('attribute.create') && (
              <Button onClick={() => setEditing({ kind: 'attribute' })}>
                <Plus className="h-4 w-4" />
                添加属性
              </Button>
            )}
            {section === 'skus' && can('sku.create') && can('attribute.read') && (
              <Button onClick={() => setEditing({ kind: 'sku' })}>
                <Plus className="h-4 w-4" />
                新建 SKU
              </Button>
            )}
          </div>
          {section === 'attributes' ? (
            <div className="bg-card overflow-x-auto rounded-lg border">
              <table className="w-full text-left text-sm">
                <thead className="bg-muted/50">
                  <tr>
                    {['属性 / 编码', '类型', '取值方式', '公共值', '规则', '操作'].map((h) => (
                      <th className="whitespace-nowrap px-4 py-3 font-medium" key={h}>
                        {h}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {detail.attributes.map((a) => (
                    <tr key={a.id} className="border-t">
                      <td className="px-4 py-3">
                        <p className="font-medium">{a.name}</p>
                        <p className="text-muted-foreground text-xs">
                          {a.code} · {a.groupName}
                        </p>
                      </td>
                      <td className="px-4 py-3">
                        {types[a.dataType]}
                        {a.multiple ? ' · 多值' : ''}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3">{modes[a.valueMode]}</td>
                      <td className="px-4 py-3">
                        {a.defaultValues.join('、') || '—'} {a.unit}
                      </td>
                      <td className="px-4 py-3">
                        {a.variantAxis ? '规格轴 · 必填' : a.required ? '必填' : '选填'}
                      </td>
                      <td className="whitespace-nowrap px-4 py-3">
                        {can('attribute.update') && (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setEditing({ kind: 'attribute', attribute: a })}
                          >
                            编辑
                          </Button>
                        )}
                        {can('attribute.delete') && detail.skus.length === 0 && (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={busy}
                            onClick={() =>
                              void remove(
                                `catalog/products/${detail.product.id}/attributes/${a.id}?version=${detail.product.version}`,
                                `确认删除属性「${a.name}」及其公共值？`,
                                detail.product.id,
                              )
                            }
                          >
                            删除
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                  {detail.attributes.length === 0 && (
                    <tr>
                      <td colSpan={6} className="text-muted-foreground p-12 text-center">
                        先添加公共参数及颜色、容量等规格轴，再创建 SKU。
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          ) : (
            <div className="bg-card overflow-x-auto rounded-lg border">
              <table className="w-full text-left text-sm">
                <thead className="bg-muted/50">
                  <tr>
                    <th className="px-4 py-3 font-medium">SKU / 名称</th>
                    <th className="px-4 py-3 font-medium">状态</th>
                    {detail.attributes.map((a) => (
                      <th key={a.id} className="whitespace-nowrap px-4 py-3 font-medium">
                        {a.name}
                      </th>
                    ))}
                    <th className="px-4 py-3 font-medium">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {detail.skus.map((s) => (
                    <tr key={s.id} className="border-t">
                      <td className="whitespace-nowrap px-4 py-3">
                        <p className="font-medium">{s.code}</p>
                        <p className="text-muted-foreground text-xs">{s.name}</p>
                      </td>
                      <td className="whitespace-nowrap px-4 py-3">{statuses[s.status]}</td>
                      {detail.attributes.map((a) => {
                        const v = s.values.find((x) => x.attributeId === a.id);
                        return (
                          <td key={a.id} className="min-w-32 px-4 py-3">
                            <p>
                              {v?.effectiveValues
                                .map((x) =>
                                  a.dataType === 'BOOLEAN' ? (x === 'true' ? '是' : '否') : x,
                                )
                                .join('、') || '—'}{' '}
                              {a.unit}
                            </p>
                            <span className="text-muted-foreground text-xs">
                              {v?.source === 'SPU'
                                ? '继承自 SPU'
                                : v?.state === 'CLEAR'
                                  ? '已清空'
                                  : v?.source === 'SKU'
                                    ? 'SKU 独立值'
                                    : '未设置'}
                            </span>
                          </td>
                        );
                      })}
                      <td className="whitespace-nowrap px-4 py-3">
                        {can('sku.update') && can('attribute.read') && (
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() => setEditing({ kind: 'sku', sku: s })}
                          >
                            编辑
                          </Button>
                        )}
                        {s.status === 'DRAFT' && can('sku.delete') && (
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={busy}
                            onClick={() =>
                              void remove(
                                `catalog/products/${detail.product.id}/skus/${s.id}?version=${detail.product.version}`,
                                `确认删除草稿 SKU「${s.code}」？`,
                                detail.product.id,
                              )
                            }
                          >
                            删除
                          </Button>
                        )}
                      </td>
                    </tr>
                  ))}
                  {detail.skus.length === 0 && (
                    <tr>
                      <td
                        colSpan={detail.attributes.length + 3}
                        className="text-muted-foreground p-12 text-center"
                      >
                        尚未创建 SKU。请先确认规格轴，再新建具体规格。
                      </td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : (
        <>
          <div className="grid gap-3 sm:grid-cols-3">
            {[
              ['SPU 商品', products.length],
              ['SKU 规格', products.reduce((sum, p) => sum + p.skuCount, 0)],
              ['启用商品', products.filter((p) => p.status === 'ACTIVE').length],
            ].map(([label, count]) => (
              <div key={label} className="bg-card rounded-lg border px-4 py-3">
                <p className="text-muted-foreground text-xs">{label}</p>
                <p className="mt-1 text-2xl font-medium tabular-nums">{count}</p>
              </div>
            ))}
          </div>
          <div className="flex flex-wrap gap-3">
            <input
              aria-label="搜索商品"
              className={`${control} max-w-sm`}
              placeholder="搜索名称、SPU 编码、品牌或分类"
              value={search}
              onChange={(e) => setSearch(e.target.value)}
            />
            <div className="ml-auto flex gap-2">
              <Button variant="outline" onClick={() => void refresh().catch(handleError)}>
                <RefreshCw className="h-4 w-4" />
                刷新
              </Button>
              {can('spu.create') && (
                <Button onClick={() => setEditing({ kind: 'spu' })}>
                  <Plus className="h-4 w-4" />
                  新建 SPU
                </Button>
              )}
            </div>
          </div>
          <div className="bg-card overflow-x-auto rounded-lg border">
            <table className="w-full text-left text-sm">
              <thead className="bg-muted/50">
                <tr>
                  {['商品 / SPU 编码', '品牌 / 分类', 'SKU 数', '状态', '操作'].map((h) => (
                    <th className="whitespace-nowrap px-4 py-3 font-medium" key={h}>
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {filtered.map((p) => (
                  <tr key={p.id} className="hover:bg-muted/30 border-t">
                    <td className="px-4 py-4">
                      <button
                        className="text-brand font-medium hover:underline"
                        onClick={() => void open(p.id)}
                      >
                        {p.name}
                      </button>
                      <p className="text-muted-foreground mt-1 text-xs">{p.code}</p>
                    </td>
                    <td className="px-4 py-4">
                      {p.brand || '—'} / {p.category || '—'}
                    </td>
                    <td className="px-4 py-4 tabular-nums">{p.skuCount}</td>
                    <td className="px-4 py-4">{statuses[p.status]}</td>
                    <td className="whitespace-nowrap px-4 py-4">
                      <Button variant="ghost" size="sm" onClick={() => void open(p.id)}>
                        管理商品
                      </Button>
                      {p.status === 'DRAFT' && p.skuCount === 0 && can('spu.delete', p) && (
                        <Button
                          variant="ghost"
                          size="sm"
                          disabled={busy}
                          onClick={() =>
                            void remove(
                              `catalog/products/${p.id}?version=${p.version}`,
                              `确认删除草稿商品「${p.name}」？`,
                            )
                          }
                        >
                          删除
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
                {filtered.length === 0 && (
                  <tr>
                    <td colSpan={5} className="p-12 text-center">
                      <Package className="text-muted-foreground mx-auto mb-3 h-7 w-7" />
                      <p className="font-medium">
                        {products.length ? '没有匹配的商品' : '创建你的第一款商品'}
                      </p>
                      <p className="text-muted-foreground mt-2">
                        {products.length
                          ? '试试其他关键词，或清空搜索。'
                          : '从 SPU 开始，配置公共属性，再建立不同规格的 SKU。'}
                      </p>
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <p className="text-muted-foreground text-xs">
            当前展示最近 200 款商品，统计和搜索基于当前列表。
          </p>
        </>
      )}
      {editing?.kind === 'spu' && (
        <SpuEditor
          {...(editing.product ? { product: editing.product } : {})}
          close={() => setEditing(null)}
          save={async (input) => {
            const id = await api<string>(
              `catalog/products${editing.product ? `/${editing.product.id}` : ''}`,
              editing.product ? 'PUT' : 'POST',
              input,
            );
            await refresh(id);
            setEditing(null);
            setNotice('商品资料已保存');
          }}
        />
      )}
      {editing?.kind === 'attribute' && detail && (
        <AttributeEditor
          {...(editing.attribute ? { attribute: editing.attribute } : {})}
          version={detail.product.version}
          close={() => setEditing(null)}
          save={async (input) => {
            await api(
              `catalog/products/${detail.product.id}/attributes${editing.attribute ? `/${editing.attribute.id}` : ''}`,
              editing.attribute ? 'PUT' : 'POST',
              input,
            );
            await refresh(detail.product.id);
            setEditing(null);
            setNotice('属性与公共值已保存');
          }}
        />
      )}
      {editing?.kind === 'sku' && detail && (
        <SkuEditor
          {...(editing.sku ? { sku: editing.sku } : {})}
          detail={detail}
          close={() => setEditing(null)}
          save={async (input) => {
            await api(
              `catalog/products/${detail.product.id}/skus${editing.sku ? `/${editing.sku.id}` : ''}`,
              editing.sku ? 'PUT' : 'POST',
              input,
            );
            await refresh(detail.product.id);
            setEditing(null);
            setNotice('SKU 已保存');
          }}
        />
      )}
    </div>
  );
}
