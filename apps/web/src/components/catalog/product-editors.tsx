'use client';
import { useState, type FormEvent } from 'react';
import { Button } from '@starcon/ui';
import type {
  Product,
  SpuInput,
  Attribute,
  AttributeInput,
  Detail,
  Sku,
  SkuInput,
  ValueInput,
} from '../../../../../packages/api-contracts/src/catalog';
import { Editor, Field, control, modes, statuses, types } from './fields';

function Submit({ busy, error, close }: { busy: boolean; error: string; close: () => void }) {
  return (
    <>
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
    </>
  );
}
function useSave<T>(save: (value: T) => Promise<void>) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  async function submit(event: FormEvent, value: T) {
    event.preventDefault();
    setBusy(true);
    setError('');
    try {
      await save(value);
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }
  return { busy, error, submit };
}
export function SpuEditor({
  product,
  close,
  save,
}: {
  product?: Product;
  close: () => void;
  save: (input: SpuInput) => Promise<void>;
}) {
  const [form, setForm] = useState<SpuInput>({
    code: product?.code ?? '',
    name: product?.name ?? '',
    description: product?.description ?? '',
    brand: product?.brand ?? '',
    category: product?.category ?? '',
    status: product?.status ?? 'DRAFT',
    version: product?.version ?? 0,
  });
  const state = useSave(save);
  return (
    <Editor
      title={product ? '编辑 SPU' : '新建 SPU'}
      description="SPU 管理商品款式的公共资料；保存后配置属性与 SKU。"
      close={close}
    >
      <form className="space-y-4" onSubmit={(e) => void state.submit(e, form)}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="SPU 编码">
            <input
              required
              maxLength={64}
              className={control}
              value={form.code}
              onChange={(e) => setForm({ ...form, code: e.target.value })}
              placeholder="例如 PHONE-PRO"
            />
          </Field>
          <Field label="商品名称">
            <input
              required
              maxLength={200}
              className={control}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
          </Field>
          <Field label="品牌">
            <input
              maxLength={100}
              className={control}
              value={form.brand}
              onChange={(e) => setForm({ ...form, brand: e.target.value })}
            />
          </Field>
          <Field label="分类">
            <input
              maxLength={100}
              className={control}
              value={form.category}
              onChange={(e) => setForm({ ...form, category: e.target.value })}
            />
          </Field>
        </div>
        <Field label="商品说明">
          <textarea
            className={control}
            rows={3}
            maxLength={10000}
            value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}
          />
        </Field>
        {product && (
          <Field
            label="SPU 状态"
            hint="状态只能在「生命周期与批量规则」中按合法转换流转，并填写变更原因。"
          >
            <p className="bg-muted/40 rounded-md border px-3 py-2 text-sm">
              {statuses[product.status] ?? product.status}
            </p>
          </Field>
        )}
        <Submit {...state} close={close} />
      </form>
    </Editor>
  );
}

export function ValueControl({
  attribute,
  values,
  onChange,
  label,
}: {
  attribute: Pick<Attribute, 'dataType' | 'multiple' | 'options' | 'unit'>;
  values: string[];
  onChange: (values: string[]) => void;
  label: string;
}) {
  if (attribute.dataType === 'OPTION')
    return (
      <div className="space-y-1">
        {attribute.multiple ? (
          <div className="flex flex-wrap gap-3">
            {attribute.options.map((option) => (
              <label key={option} className="flex items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  checked={values.includes(option)}
                  onChange={(e) =>
                    onChange(
                      e.target.checked ? [...values, option] : values.filter((v) => v !== option),
                    )
                  }
                />
                {option}
              </label>
            ))}
          </div>
        ) : (
          <select
            aria-label={label}
            className={control}
            value={values[0] ?? ''}
            onChange={(e) => onChange(e.target.value ? [e.target.value] : [])}
          >
            <option value="">请选择</option>
            {attribute.options.map((o) => (
              <option key={o}>{o}</option>
            ))}
          </select>
        )}
      </div>
    );
  if (attribute.dataType === 'BOOLEAN' && !attribute.multiple)
    return (
      <select
        aria-label={label}
        className={control}
        value={values[0] ?? ''}
        onChange={(e) => onChange(e.target.value ? [e.target.value] : [])}
      >
        <option value="">未设置</option>
        <option value="true">是</option>
        <option value="false">否</option>
      </select>
    );
  if (attribute.multiple)
    return (
      <textarea
        aria-label={label}
        className={control}
        rows={3}
        placeholder="每行一个值"
        value={values.join('\n')}
        onChange={(e) => onChange(e.target.value === '' ? [] : e.target.value.split('\n'))}
      />
    );
  return (
    <div className="flex items-center gap-2">
      <input
        aria-label={label}
        type={
          attribute.dataType === 'DATE'
            ? 'date'
            : attribute.dataType === 'DECIMAL'
              ? 'number'
              : 'text'
        }
        step="any"
        maxLength={2000}
        className={control}
        value={values[0] ?? ''}
        onChange={(e) => onChange(e.target.value === '' ? [] : [e.target.value])}
      />
      {attribute.unit && <span className="text-muted-foreground text-sm">{attribute.unit}</span>}
    </div>
  );
}

export function AttributeEditor({
  attribute,
  version,
  close,
  save,
}: {
  attribute?: Attribute;
  version: number;
  close: () => void;
  save: (input: AttributeInput) => Promise<void>;
}) {
  const [form, setForm] = useState<AttributeInput>({
    code: attribute?.code ?? '',
    name: attribute?.name ?? '',
    dataType: attribute?.dataType ?? 'TEXT',
    valueMode: attribute?.valueMode ?? 'COMMON_LOCKED',
    multiple: attribute?.multiple ?? false,
    required: attribute?.required ?? false,
    variantAxis: attribute?.variantAxis ?? false,
    unit: attribute?.unit ?? '',
    groupName: attribute?.groupName ?? '基本参数',
    options: attribute?.options ?? [],
    defaultValues: attribute?.defaultValues ?? [],
    version,
  });
  const [optionText, setOptionText] = useState(form.options.join('\n'));
  const state = useSave(save);
  const locked = Boolean(attribute);
  return (
    <Editor
      title={attribute ? '编辑属性与公共值' : '添加自定义属性'}
      description="公共固定由所有 SKU 继承；公共默认允许 SKU 覆写；SKU 独立由各规格填写。"
      close={close}
    >
      <form
        className="space-y-4"
        onSubmit={(e) =>
          void state.submit(e, {
            ...form,
            options:
              form.dataType === 'OPTION'
                ? optionText
                    .split('\n')
                    .map((x) => x.trim())
                    .filter(Boolean)
                : [],
          })
        }
      >
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="属性编码">
            <input
              required
              disabled={locked}
              className={control}
              value={form.code}
              onChange={(e) => setForm({ ...form, code: e.target.value })}
              placeholder="例如 VIDEO"
            />
          </Field>
          <Field label="属性名称">
            <input
              required
              className={control}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="例如 视频规格"
            />
          </Field>
          <Field label="数据类型">
            <select
              disabled={locked || form.variantAxis}
              className={control}
              value={form.dataType}
              onChange={(e) =>
                setForm({ ...form, dataType: e.target.value, defaultValues: [], options: [] })
              }
            >
              {Object.entries(types).map(([value, text]) => (
                <option key={value} value={value}>
                  {text}
                </option>
              ))}
            </select>
          </Field>
          <Field label="取值方式">
            <select
              disabled={locked || form.variantAxis}
              className={control}
              value={form.valueMode}
              onChange={(e) => setForm({ ...form, valueMode: e.target.value, defaultValues: [] })}
            >
              {Object.entries(modes).map(([value, text]) => (
                <option key={value} value={value}>
                  {text}
                </option>
              ))}
            </select>
          </Field>
          <Field label="分组">
            <input
              className={control}
              value={form.groupName}
              onChange={(e) => setForm({ ...form, groupName: e.target.value })}
            />
          </Field>
          <Field label="单位（可选）">
            <input
              disabled={locked}
              className={control}
              value={form.unit}
              onChange={(e) => setForm({ ...form, unit: e.target.value })}
              placeholder="例如 GB、kg"
            />
          </Field>
        </div>
        <div className="flex flex-wrap gap-4 text-sm">
          <label>
            <input
              type="checkbox"
              disabled={locked}
              checked={form.variantAxis}
              onChange={(e) =>
                setForm({
                  ...form,
                  variantAxis: e.target.checked,
                  ...(e.target.checked
                    ? {
                        dataType: 'OPTION',
                        valueMode: 'SKU_ONLY',
                        multiple: false,
                        required: true,
                        defaultValues: [],
                      }
                    : {}),
                })
              }
            />{' '}
            作为 SKU 规格轴
          </label>
          <label>
            <input
              type="checkbox"
              disabled={locked || form.variantAxis}
              checked={form.multiple}
              onChange={(e) => setForm({ ...form, multiple: e.target.checked, defaultValues: [] })}
            />{' '}
            允许多个值
          </label>
          <label>
            <input
              type="checkbox"
              disabled={form.variantAxis}
              checked={form.required}
              onChange={(e) => setForm({ ...form, required: e.target.checked })}
            />{' '}
            启用 SKU 时必填
          </label>
        </div>
        {form.dataType === 'OPTION' && (
          <Field label="可选值" hint="每行一个选项；请先配置规格轴，再创建 SKU。">
            <textarea
              required
              disabled={locked}
              rows={3}
              className={control}
              value={optionText}
              onChange={(e) => {
                setOptionText(e.target.value);
                setForm({
                  ...form,
                  options: e.target.value
                    .split('\n')
                    .map((x) => x.trim())
                    .filter(Boolean),
                  defaultValues: [],
                });
              }}
            />
          </Field>
        )}
        {form.valueMode !== 'SKU_ONLY' && (
          <Field label="SPU 公共值">
            <ValueControl
              attribute={form}
              values={form.defaultValues}
              onChange={(v) => setForm({ ...form, defaultValues: v })}
              label="SPU 公共值"
            />
          </Field>
        )}
        {locked && (
          <p className="text-muted-foreground text-xs">
            属性结构创建后固定；需要不同类型或取值方式时，请新增属性，避免改变已有 SKU 的含义。
          </p>
        )}
        <Submit {...state} close={close} />
      </form>
    </Editor>
  );
}

export function SkuEditor({
  detail,
  sku,
  close,
  save,
}: {
  detail: Detail;
  sku?: Sku;
  close: () => void;
  save: (input: SkuInput) => Promise<void>;
}) {
  const [form, setForm] = useState<SkuInput>({
    code: sku?.code ?? '',
    name: sku?.name ?? detail.product.name,
    barcode: sku?.barcode ?? '',
    baseUnit: sku?.baseUnit ?? '件',
    status: sku?.status ?? 'DRAFT',
    version: detail.product.version,
    values: detail.attributes.map((a): ValueInput => {
      const v = sku?.values.find((x) => x.attributeId === a.id);
      return {
        attributeId: a.id,
        state: v?.state ?? (a.valueMode === 'SKU_ONLY' ? 'SET' : 'INHERIT'),
        values: v?.values ?? [],
      };
    }),
  });
  const state = useSave(save);
  const change = (id: string, update: Partial<ValueInput>) =>
    setForm({
      ...form,
      values: form.values.map((v) => (v.attributeId === id ? { ...v, ...update } : v)),
    });
  return (
    <Editor
      title={sku ? '编辑 SKU' : '新建 SKU'}
      description="继承公共资料，只填写具体规格的差异。所有生效属性会在 SKU 矩阵中完整显示。"
      close={close}
    >
      <form className="space-y-4" onSubmit={(e) => void state.submit(e, form)}>
        <div className="grid gap-4 sm:grid-cols-2">
          <Field label="SKU 编码">
            <input
              required
              className={control}
              value={form.code}
              onChange={(e) => setForm({ ...form, code: e.target.value })}
            />
          </Field>
          <Field label="SKU 名称">
            <input
              required
              className={control}
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
            />
          </Field>
          <Field label="条码">
            <input
              className={control}
              value={form.barcode}
              onChange={(e) => setForm({ ...form, barcode: e.target.value })}
            />
          </Field>
          <Field label="基础单位">
            <input
              required
              className={control}
              value={form.baseUnit}
              onChange={(e) => setForm({ ...form, baseUnit: e.target.value })}
            />
          </Field>
          <Field label="SKU 状态">
            <select
              className={control}
              value={form.status}
              onChange={(e) => setForm({ ...form, status: e.target.value })}
            >
              {['DRAFT', 'ACTIVE', 'INACTIVE', 'ARCHIVED'].map((s) => (
                <option key={s} value={s}>
                  {statuses[s]}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <div className="space-y-4 border-t pt-4">
          <h3 className="font-medium">完整商品属性</h3>
          {detail.attributes.map((a) => {
            const v = form.values.find((x) => x.attributeId === a.id)!;
            return (
              <div key={a.id} className="space-y-2 rounded-md border p-3">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-sm font-medium">
                    {a.name}
                    {a.required ? ' *' : ''}
                    <span className="text-muted-foreground ml-2 text-xs">
                      {a.variantAxis ? '规格轴' : a.groupName}
                    </span>
                  </span>
                  {a.valueMode !== 'COMMON_LOCKED' && (
                    <select
                      aria-label={`${a.name}取值方式`}
                      className="bg-card rounded border p-1 text-xs"
                      value={v.state}
                      onChange={(e) => change(a.id, { state: e.target.value, values: [] })}
                    >
                      <option value="SET">
                        {a.valueMode === 'SKU_ONLY' ? '填写值' : '覆写公共值'}
                      </option>
                      {a.valueMode !== 'SKU_ONLY' && <option value="INHERIT">继承公共值</option>}
                      {!a.required && <option value="CLEAR">清空此属性</option>}
                    </select>
                  )}
                </div>
                {v.state === 'SET' && a.valueMode !== 'COMMON_LOCKED' ? (
                  <ValueControl
                    attribute={a}
                    values={v.values}
                    onChange={(values) => change(a.id, { values })}
                    label={a.name}
                  />
                ) : (
                  <p className="text-muted-foreground text-sm">
                    {v.state === 'CLEAR'
                      ? '此 SKU 不设置该属性'
                      : `${a.defaultValues.join('、') || '未设置'} ${a.unit} · 继承自 SPU`}
                  </p>
                )}
              </div>
            );
          })}
          {detail.attributes.length === 0 && (
            <p className="text-muted-foreground text-sm">
              暂无自定义属性。无规格商品只能创建一个默认 SKU。
            </p>
          )}
        </div>
        <Submit {...state} close={close} />
      </form>
    </Editor>
  );
}
