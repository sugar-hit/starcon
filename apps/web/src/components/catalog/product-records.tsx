'use client';
import { useCallback, useEffect, useState } from 'react';
import { Button } from '@starcon/ui';
import { Plus } from 'lucide-react';
import type {
  Access,
  Product,
  ProductRecord,
  RecordInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from './api';
import { canAccessRecord } from './administration-model';
import { control, Editor, Field, statuses } from './fields';

const copy = {
  research: {
    title: '选品与开发',
    hint: '记录目标市场、样品评估和开发决策；可关联已有商品。',
    name: '项目名称',
    notes: '评估与开发记录',
  },
  compliance: {
    title: '合规与资质',
    hint: '登记资质、证据链接与有效期。当前支持人工记录，不提供自动认证审核或到期通知。',
    name: '资质名称',
    notes: '资质说明与复核记录',
  },
  assets: {
    title: '素材与翻译',
    hint: '维护素材链接和各语言文案。当前使用已有 HTTPS 文件链接，不提供文件上传和自动翻译。',
    name: '素材或文案名称',
    notes: '文案 / 翻译内容',
  },
};
export function ProductRecords({
  kind,
  products,
  session,
  onError,
}: {
  kind: keyof typeof copy;
  products: Product[];
  session: Access;
  onError: (e: unknown) => void;
}) {
  const [rows, setRows] = useState<ProductRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [editing, setEditing] = useState<ProductRecord | 'new' | null>(null);
  const [query, setQuery] = useState('');
  const [busy, setBusy] = useState(false);
  const can = (action: string, row?: ProductRecord) =>
    canAccessRecord(session, `catalog.${kind}.${action}`, row);
  const readAllowed = canAccessRecord(session, `catalog.${kind}.read`);
  const load = useCallback(async () => {
    setLoading(true);
    setFailed(false);
    try {
      if (readAllowed) setRows(await api<ProductRecord[]>(`catalog/records/${kind}`));
    } catch (e) {
      setFailed(true);
      onError(e);
    } finally {
      setLoading(false);
    }
  }, [kind, readAllowed, onError]);
  useEffect(() => {
    void load();
  }, [load]);
  if (!can('read'))
    return <p className="bg-card rounded-lg border p-8 text-sm">当前角色没有此页面的查看权限。</p>;
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="text-lg font-semibold">{copy[kind].title}</h2>
          <p className="text-muted-foreground mt-1 max-w-2xl text-sm">{copy[kind].hint}</p>
        </div>
        {can('create') && (
          <Button onClick={() => setEditing('new')}>
            <Plus className="h-4 w-4" />
            新建记录
          </Button>
        )}
      </div>
      <input
        aria-label="搜索记录"
        className={`${control} max-w-sm`}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="搜索标题或备注"
      />
      {loading ? (
        <p role="status">正在加载记录…</p>
      ) : failed ? (
        <Button variant="outline" onClick={() => void load()}>
          重新加载
        </Button>
      ) : (
        <div className="bg-card overflow-x-auto rounded-lg border">
          <table className="w-full text-left text-sm">
            <thead className="bg-muted/50">
              <tr>
                {[
                  '名称',
                  '关联商品',
                  kind === 'assets' ? '语言' : '目标国家',
                  kind === 'compliance' ? '有效期' : '说明',
                  '状态',
                  '操作',
                ].map((h) => (
                  <th key={h} className="whitespace-nowrap px-4 py-3 font-medium">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows
                .filter((r) => `${r.title} ${r.notes}`.toLowerCase().includes(query.toLowerCase()))
                .map((r) => (
                  <tr className="border-t" key={r.id}>
                    <td className="px-4 py-3">
                      <p className="font-medium">{r.title}</p>
                      {r.reference && (
                        <a
                          href={r.reference}
                          target="_blank"
                          rel="noopener noreferrer"
                          className="text-brand text-xs hover:underline"
                        >
                          打开资料链接 ↗
                        </a>
                      )}
                    </td>
                    <td className="px-4 py-3">
                      {products.find((p) => p.id === r.spuId)?.name ?? '未关联'}
                    </td>
                    <td className="px-4 py-3">
                      {(kind === 'assets' ? r.locale : r.targetCountry) || '—'}
                    </td>
                    <td className="max-w-xs px-4 py-3">
                      <p className="line-clamp-2">
                        {kind === 'compliance' ? r.expiresOn || '未设置' : r.notes || '—'}
                      </p>
                    </td>
                    <td className="px-4 py-3">{statuses[r.status]}</td>
                    <td className="whitespace-nowrap px-4 py-3">
                      {can('update', r) && (
                        <Button size="sm" variant="ghost" onClick={() => setEditing(r)}>
                          编辑
                        </Button>
                      )}
                      {can('delete', r) && r.status === 'DRAFT' && (
                        <Button
                          size="sm"
                          variant="ghost"
                          disabled={busy}
                          onClick={async () => {
                            if (!window.confirm(`确认删除草稿「${r.title}」？`)) return;
                            setBusy(true);
                            try {
                              await api(
                                `catalog/records/${kind}/${r.id}?version=${r.version}`,
                                'DELETE',
                              );
                              await load();
                            } catch (e) {
                              onError(e);
                            } finally {
                              setBusy(false);
                            }
                          }}
                        >
                          删除
                        </Button>
                      )}
                    </td>
                  </tr>
                ))}
              {rows.length === 0 && (
                <tr>
                  <td colSpan={6} className="text-muted-foreground p-12 text-center">
                    暂无记录。新建后将保存到当前租户。
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}
      <p className="text-muted-foreground text-xs">当前展示最近 200 条记录。</p>
      {editing && (
        <RecordEditor
          kind={kind}
          products={products}
          {...(editing !== 'new' ? { row: editing } : {})}
          close={() => setEditing(null)}
          save={async (input) => {
            await api(
              `catalog/records/${kind}${editing === 'new' ? '' : `/${editing.id}`}`,
              editing === 'new' ? 'POST' : 'PUT',
              input,
            );
            setEditing(null);
            await load();
          }}
        />
      )}
    </div>
  );
}
function RecordEditor({
  kind,
  products,
  row,
  close,
  save,
}: {
  kind: keyof typeof copy;
  products: Product[];
  row?: ProductRecord;
  close: () => void;
  save: (input: RecordInput) => Promise<void>;
}) {
  const [form, setForm] = useState<RecordInput>({
    spuId: row?.spuId ?? '',
    title: row?.title ?? '',
    notes: row?.notes ?? '',
    reference: row?.reference ?? '',
    status: row?.status ?? 'DRAFT',
    locale: row?.locale ?? '',
    targetCountry: row?.targetCountry ?? '',
    expiresOn: row?.expiresOn ?? '',
    version: row?.version ?? 0,
  });
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  return (
    <Editor
      title={`${row ? '编辑' : '新建'}${copy[kind].title}记录`}
      description={copy[kind].hint}
      close={close}
    >
      <form
        className="space-y-4"
        onSubmit={async (e) => {
          e.preventDefault();
          setBusy(true);
          setError('');
          try {
            await save({ ...form, spuId: form.spuId || null, expiresOn: form.expiresOn || null });
          } catch (e) {
            setError(e instanceof Error ? e.message : '保存失败');
          } finally {
            setBusy(false);
          }
        }}
      >
        <Field label={copy[kind].name}>
          <input
            required
            maxLength={200}
            className={control}
            value={form.title}
            onChange={(e) => setForm({ ...form, title: e.target.value })}
          />
        </Field>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="关联 SPU">
            <select
              className={control}
              value={form.spuId ?? ''}
              onChange={(e) => setForm({ ...form, spuId: e.target.value })}
            >
              <option value="">未关联</option>
              {products.map((p) => (
                <option value={p.id} key={p.id}>
                  {p.name} ({p.code})
                </option>
              ))}
            </select>
          </Field>
          <Field label="记录状态">
            <select
              className={control}
              value={form.status}
              onChange={(e) => setForm({ ...form, status: e.target.value })}
            >
              {['DRAFT', 'REVIEW', 'APPROVED', 'ARCHIVED'].map((s) => (
                <option key={s} value={s}>
                  {statuses[s]}
                </option>
              ))}
            </select>
          </Field>
          {kind === 'assets' ? (
            <Field label="语言">
              <input
                className={control}
                placeholder="例如 zh-CN、en-US"
                value={form.locale}
                onChange={(e) => setForm({ ...form, locale: e.target.value })}
              />
            </Field>
          ) : (
            <Field label="目标国家">
              <input
                className={control}
                placeholder="例如 US、CN"
                maxLength={2}
                value={form.targetCountry}
                onChange={(e) => setForm({ ...form, targetCountry: e.target.value.toUpperCase() })}
              />
            </Field>
          )}
          {kind === 'compliance' && (
            <Field label="到期日期">
              <input
                type="date"
                className={control}
                value={form.expiresOn ?? ''}
                onChange={(e) => setForm({ ...form, expiresOn: e.target.value })}
              />
            </Field>
          )}
        </div>
        <Field label="资料链接">
          <input
            type="url"
            className={control}
            placeholder="https://"
            value={form.reference}
            onChange={(e) => setForm({ ...form, reference: e.target.value })}
          />
        </Field>
        <Field label={copy[kind].notes}>
          <textarea
            className={control}
            rows={5}
            maxLength={10000}
            value={form.notes}
            onChange={(e) => setForm({ ...form, notes: e.target.value })}
          />
        </Field>
        <p role="alert" className="text-destructive text-sm">
          {error}
        </p>
        <div className="flex justify-end gap-2">
          <Button variant="outline" onClick={close} disabled={busy}>
            取消
          </Button>
          <Button type="submit" disabled={busy}>
            {busy ? '保存中…' : '保存'}
          </Button>
        </div>
      </form>
    </Editor>
  );
}
