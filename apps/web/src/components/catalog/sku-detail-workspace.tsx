'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button } from '@starcon/ui';
import {
  ArrowLeft,
  History,
  Info,
  Package,
  Pencil,
  RefreshCw,
  Save,
  TriangleAlert,
  X,
} from 'lucide-react';
import type {
  SkuDetail,
  SkuEffectiveAttribute,
} from '../../../../../packages/api-contracts/src/index';
import {
  attributeSources,
  decimal,
  detailDraft,
  dimensionText,
  draftToInput,
  effectiveText,
  fetchSkuDetail,
  groupAttributes,
  isConflict,
  isEmptyProfile,
  isNotFound,
  saveSkuDetail,
  skuStatusLabels,
  spuStatusLabels,
  timestamp,
  unitConversionText,
  validateDraft,
  weightText,
  type SkuDetailDraft,
} from './sku-detail-model';
import { Editor, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.1.2.2 SKU 详情工作区。
 *
 * <p>分区顺序按"运营排查一件事的顺序"排：先看这是谁（编码规格）→ 这个 SKU 实际是什么参数
 * （属性有效值）→ 实物与计量（包装、换算、条码）→ 别的系统怎么说（库存、listing、成本售价、
 * 单据引用）→ 变更历史。
 *
 * <p>每个分区都自己标数据归属：catalog 自有的显示真实值，别的服务的事实显示「未接入」并写明
 * 归谁。**绝不把未接入显示成 0**——运营看到 0 会真的去补货或改价。
 */
export function SkuDetailWorkspace({ skuId }: { skuId: string }) {
  const { session } = useIdentity();
  const [detail, setDetail] = useState<SkuDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [missing, setMissing] = useState(false);
  const [editing, setEditing] = useState(false);
  const readable = session.permissions.includes('catalog.sku.read');
  const writable = session.permissions.includes('catalog.sku.update');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    setMissing(false);
    try {
      setDetail(await fetchSkuDetail(skuId));
    } catch (e) {
      setDetail(null);
      if (isNotFound(e)) setMissing(true);
      else setError(e instanceof Error ? e.message : 'SKU 详情加载失败');
    } finally {
      setLoading(false);
    }
  }, [skuId, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  // 切换 SKU 时关掉编辑面板：留着上一个 SKU 的表单去编辑另一个 SKU 是很容易犯的错。
  useEffect(() => setEditing(false), [skuId]);

  if (!readable)
    return (
      <Section frame>
        <p className="text-sm">当前角色没有 SKU 查看权限，请联系租户管理员。</p>
      </Section>
    );

  if (loading)
    return (
      <Section frame>
        <p role="status" className="text-muted-foreground text-sm">
          正在加载 SKU 详情…
        </p>
      </Section>
    );

  if (missing)
    return (
      <Section frame>
        <BackLink />
        <p role="alert" className="text-destructive text-sm">
          SKU 不存在或当前角色没有访问权限。
        </p>
        <p className="text-muted-foreground mt-1 text-sm">
          这两种情况返回同一个结果，避免用 403 / 404 的差别探测别人的 SKU 是否存在。
        </p>
      </Section>
    );

  if (error || !detail)
    return (
      <Section frame>
        <BackLink />
        <p role="alert" className="text-destructive text-sm">
          {error || 'SKU 详情加载失败'}
        </p>
        <Button variant="outline" size="sm" className="mt-3" onClick={() => void load()}>
          <RefreshCw className="h-4 w-4" />
          重试
        </Button>
      </Section>
    );

  const groups = groupAttributes(detail.attributes);
  const empty = isEmptyProfile(detail);

  return (
    <Section frame>
      <BackLink />

      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h2 className="truncate text-xl font-semibold">{detail.name}</h2>
          <p className="text-muted-foreground mt-1 text-sm">
            <span className="tabular-nums">{detail.code}</span> · {detail.spuCode} {detail.spuName}
            {detail.category ? ` · ${detail.category}` : ''}
          </p>
          <p className="text-muted-foreground mt-1 text-xs">
            基础单位 {detail.baseUnit} · 版本 {detail.version} · 更新于{' '}
            {timestamp(detail.updatedAt)}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <Badge
            tone={
              detail.status === 'ACTIVE'
                ? 'success'
                : detail.status === 'DRAFT'
                  ? 'info'
                  : 'neutral'
            }
          >
            {skuStatusLabels[detail.status] ?? detail.status}
          </Badge>
          <Badge tone="neutral">{spuStatusLabels[detail.spuStatus] ?? detail.spuStatus}</Badge>
          {writable && !editing && (
            <Button size="sm" onClick={() => setEditing(true)}>
              <Pencil className="h-4 w-4" />
              编辑档案
            </Button>
          )}
          <Button variant="ghost" size="sm" onClick={() => void load()}>
            <RefreshCw className="h-4 w-4" />
            刷新
          </Button>
        </div>
      </header>

      {empty && (
        <div className="bg-card rounded-lg border p-6 text-center">
          <Package className="text-muted-foreground mx-auto mb-3 h-7 w-7" />
          <p className="font-medium">这个 SKU 还没有档案数据</p>
          <p className="text-muted-foreground mt-2 text-sm">
            属性、包装、换算、素材都是空的。先补属性有效值与包装尺寸重量， 10 物流的体积重与 08
            拣货的装箱核算才拿得到数据。
          </p>
          {writable && (
            <Button size="sm" className="mt-3" onClick={() => setEditing(true)}>
              <Pencil className="h-4 w-4" />
              编辑档案
            </Button>
          )}
        </div>
      )}

      {detail.attributeSummary.missingRequired > 0 && (
        <div
          role="alert"
          className="border-warning/30 bg-warning/5 text-warning rounded-md border p-3 text-sm"
        >
          <p className="flex items-center gap-2 font-medium">
            <TriangleAlert className="h-4 w-4" />有 {detail.attributeSummary.missingRequired}{' '}
            个必填属性还没有生效值
          </p>
          <p className="mt-1 text-xs">
            「有效值」是 SPU 公共值经 SKU 覆写后的结果。SKU 显式清空过的属性不会退回公共值，
            因此清空一个必填属性同样会进这份清单。
          </p>
        </div>
      )}

      <div className="grid gap-4 lg:grid-cols-2">
        <Card
          title="编码与规格"
          hint="内部编码由 catalog 拥有；店铺的 seller_sku 是平台属性，不在这里。"
        >
          <dl className="grid grid-cols-3 gap-3 text-sm">
            <Field label="内部编码" value={detail.code} mono />
            <Field label="内部条码" value={detail.barcode.primary || '—'} mono />
            <Field label="基础单位" value={detail.baseUnit} />
            <Field label="所属 SPU" value={`${detail.spuCode} ${detail.spuName}`} />
            <Field label="品牌" value={detail.brand || '—'} />
            <Field label="分类" value={detail.category || '—'} />
            <Field label="创建时间" value={timestamp(detail.createdAt)} />
            <Field label="最近更新" value={timestamp(detail.updatedAt)} />
            <Field label="规格签名" value={detail.variantSignature || '—'} mono />
          </dl>
          <div className="mt-3">
            <p className="text-muted-foreground text-xs">规格轴取值</p>
            <div className="mt-1 flex flex-wrap gap-1.5">
              {detail.specs.length === 0 ? (
                <span className="text-muted-foreground text-sm">该 SPU 未定义规格轴</span>
              ) : (
                detail.specs.map((spec) => (
                  <Badge key={`${spec.axis}:${spec.value}`} tone="info">
                    {spec.axis} = {spec.value}
                  </Badge>
                ))
              )}
            </div>
          </div>
        </Card>

        <Card
          title="属性有效值"
          hint="显示的是有效值（SPU 公共值经 SKU 覆写 / 清空后的解析结果），不是原始存的两行值。"
        >
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="text-muted-foreground flex flex-wrap gap-x-4 gap-y-1 text-xs">
              <span>共 {detail.attributeSummary.total} 项</span>
              <span>SKU 覆写 {detail.attributeSummary.overridden}</span>
              <span>SKU 清空 {detail.attributeSummary.cleared}</span>
              <span>继承公共值 {detail.attributeSummary.inherited}</span>
              <span>未赋值 {detail.attributeSummary.unset}</span>
              <span>规格轴 {detail.attributeSummary.variantAxes}</span>
            </div>
            {/* 覆写与清空归 3.2.2.2：详情页只读有效值，改动在覆写工作区做。 */}
            <Link href={`/products/sku/${detail.id}/attributes`}>
              <Button variant="ghost" size="sm">
                去覆写属性
              </Button>
            </Link>
          </div>
          {groups.length === 0 ? (
            <p className="text-muted-foreground mt-3 text-sm">
              该 SPU 还没有配置属性。属性体系在 3.2.1 属性定义里维护。
            </p>
          ) : (
            <div className="mt-3 space-y-4">
              {groups.map((group) => (
                <div key={group.name}>
                  <p className="text-muted-foreground text-xs">{group.name}</p>
                  <ul className="mt-1.5 space-y-1.5">
                    {group.items.map((attribute) => (
                      <AttributeRow key={attribute.attributeId} attribute={attribute} />
                    ))}
                  </ul>
                </div>
              ))}
            </div>
          )}
        </Card>

        <Card
          title="包装尺寸重量"
          hint="决定 10 物流的体积重与 08 拣货的装箱体积核算。缺一维就不给体积，不用 0 顶替。"
        >
          <div className="mb-3 flex justify-end">
            {/* 物理参数 + 安全属性（3.7.2.1）：本页只读展示，编辑在工作区做。 */}
            <Link href={`/products/sku/${detail.id}/physical`}>
              <Button variant="ghost" size="sm">
                去维护物理与安全属性
              </Button>
            </Link>
          </div>
          {detail.packaging === null ? (
            <p className="text-muted-foreground text-sm">
              还没有维护包装尺寸重量。留空时各物流渠道拿不到体积重，只能按件计费。
            </p>
          ) : (
            <dl className="grid grid-cols-3 gap-3 text-sm">
              <Field label="长宽高" value={dimensionText(detail.packaging)} />
              <Field
                label="体积"
                value={
                  detail.packaging.volumeCm3 === null
                    ? '—'
                    : `${decimal(detail.packaging.volumeCm3)} cm³`
                }
              />
              <Field label="包装方式" value={detail.packaging.packagingType || '—'} />
              <Field label="重量" value={weightText(detail.packaging)} wide />
              <Field label="更新于" value={timestamp(detail.packaging.updatedAt)} wide />
            </dl>
          )}
        </Card>

        <Card
          title="单位与换算"
          hint="换算比例必须版本化并有生效区间，历史版本仍然可见——单据冻结的是当时的比例。"
        >
          <p className="text-sm">
            基础单位：<span className="font-medium">{detail.baseUnit}</span>
          </p>
          {detail.unitConversions.length === 0 ? (
            <p className="text-muted-foreground mt-2 text-sm">
              还没有登记换算关系。换算关系的版本维护属 3.1.2.4 单位与换算，该模块尚未开发；
              上面这一行是 catalog 自有的基础单位，是真实数据。
            </p>
          ) : (
            <ul className="mt-2 space-y-1.5 text-sm">
              {detail.unitConversions.map((conversion) => (
                <li key={`${conversion.unitCode}:${conversion.role}:${conversion.effectiveFrom}`}>
                  <span className="font-medium">{conversion.roleLabel}</span>
                  <span className="text-muted-foreground ml-2">
                    {unitConversionText(conversion, detail.baseUnit)}
                  </span>
                  {conversion.current && <Badge tone="success">当前生效</Badge>}
                </li>
              ))}
            </ul>
          )}
        </Card>

        <Card
          title="条码"
          hint="内部条码与外部条码不是一回事：外部条码按 scheme+value 唯一，由 3.1.2.3 维护。"
        >
          <dl className="grid grid-cols-2 gap-3 text-sm">
            <Field label="内部条码" value={detail.barcode.primary || '—'} mono />
            <Field label="外部条码归属" value={detail.barcode.identifierOwner} />
          </dl>
          <p className="text-muted-foreground mt-3 flex items-start gap-1.5 text-xs">
            <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            {detail.barcode.identifierNote}
          </p>
        </Card>

        <Card title="媒体" hint="只读现有可用列；该表结构缺陷见下方说明。">
          {!detail.mediaAvailable ? (
            <p className="text-muted-foreground text-sm">媒体区未加载：{detail.mediaLimitation}</p>
          ) : detail.media.length === 0 ? (
            <p className="text-muted-foreground text-sm">这个 SPU 下还没有登记任何素材资料。</p>
          ) : (
            <ul className="space-y-2 text-sm">
              {detail.media.map((asset) => (
                <li key={asset.id} className="border-t pt-2 first:border-0 first:pt-0">
                  <p className="font-medium">{asset.title}</p>
                  {asset.notes && <p className="text-muted-foreground text-xs">{asset.notes}</p>}
                  <p className="text-muted-foreground text-xs">
                    {asset.reference || '无外部链接'} · {asset.locale || '未标语言'} ·{' '}
                    {asset.targetCountry || '未标国家'}
                    {asset.expiresOn ? ` · ${asset.expiresOn} 过期` : ''}
                  </p>
                </li>
              ))}
            </ul>
          )}
          <p className="text-muted-foreground mt-3 flex items-start gap-1.5 text-xs">
            <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            {detail.mediaLimitation}
          </p>
        </Card>

        {detail.sources.map((source) => (
          <Card key={source.code} title={source.label} hint={`事实来源：${source.owner}`}>
            <SourceSection code={source.code} note={source.note} available={source.available} />
          </Card>
        ))}
      </div>

      <Card title="状态变更历史" hint="业务时间与系统时间分列存储，列表由 3.1.2.1 的批量操作写入。">
        {detail.statusHistory.length === 0 ? (
          <p className="text-muted-foreground text-sm">还没有状态变更记录。</p>
        ) : (
          <ul className="space-y-2 text-sm">
            {detail.statusHistory.map((change) => (
              <li key={change.id} className="border-t pt-2 first:border-0 first:pt-0">
                <p>
                  <Badge tone="neutral">
                    {skuStatusLabels[change.fromStatus] ?? change.fromStatus}
                  </Badge>
                  <span className="mx-1.5">→</span>
                  <Badge tone={change.toStatus === 'ACTIVE' ? 'success' : 'neutral'}>
                    {skuStatusLabels[change.toStatus] ?? change.toStatus}
                  </Badge>
                  <span className="text-muted-foreground ml-2">
                    {change.reason || '未填写原因'}
                  </span>
                </p>
                <p className="text-muted-foreground text-xs">
                  {change.source === 'BATCH' ? '批量操作' : '人工操作'} · 业务时间{' '}
                  {timestamp(change.occurredAt)} · 记录于 {timestamp(change.createdAt)}
                </p>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {editing && detail && (
        <EditPanel
          detail={detail}
          onClose={() => setEditing(false)}
          onSaved={(saved) => {
            setDetail((current) => (current ? { ...current, version: saved.version } : current));
            setEditing(false);
            void load();
          }}
        />
      )}
    </Section>
  );
}

/**
 * 跨服务子分区。
 *
 * <p>未接入时整区写「未接入」+ 归属服务，而不是显示 0 或空列表：
 * 0 会被读成"库存为零"，空列表会被读成"没有任何平台 listing"，两者都是编造的事实。
 */
function SourceSection({
  code,
  note,
  available,
}: {
  code: string;
  note: string;
  available: boolean;
}) {
  if (available)
    return (
      <p className="text-muted-foreground text-sm">已接入 {code} 的投影，明细行由该分区返回。</p>
    );
  const detail: Record<string, string> = {
    INVENTORY_DISTRIBUTION:
      '库存分布按仓库拆分的在库、在途、可售数量。库存数以 07 库存为准，catalog 不另存一份，否则两边迟早对不上且没人说得清该信谁。',
    COST: '单位成本与采购价归 06 采购。采购价带供应商与有效期，catalog 无权威数据。',
    SALE_PRICE:
      '售价归 05 定价。同一 SKU 在不同平台可以有不同售价，catalog 只有一个 code 表达不了。',
    LISTINGS:
      '各平台的 listing 归 04 刊登。店铺的 seller_sku 是平台属性，映射关系在 04 维护，不会反向写回内部编码。',
    DOCUMENT_REFERENCES:
      '刊登、采购单、出入库单、订单、售后单对 SKU 的引用分属 04 / 06 / 07 / 08 / 09 / 11，各服务尚未开发。',
  };
  return (
    <div className="space-y-2">
      <p className="text-muted-foreground text-sm">
        <Badge tone="warning">未接入</Badge>
      </p>
      <p className="text-muted-foreground text-xs">{detail[code] ?? ''}</p>
      <p className="text-muted-foreground text-xs">{note}</p>
    </div>
  );
}

function AttributeRow({ attribute }: { attribute: SkuEffectiveAttribute }) {
  const tone =
    attribute.source === 'SKU_OVERRIDE'
      ? 'info'
      : attribute.source === 'SKU_CLEARED'
        ? 'warning'
        : attribute.source === 'SPU_COMMON'
          ? 'neutral'
          : 'neutral';
  return (
    <li className="flex flex-wrap items-baseline gap-x-2 gap-y-1">
      <span className="min-w-24 text-sm">{attribute.name}</span>
      <span className="text-sm">
        {effectiveText(attribute)} {attribute.unit}
      </span>
      <Badge tone={tone}>{attribute.sourceLabel || attributeSources[attribute.source]}</Badge>
      {attribute.valueModeLabel && (
        <span className="text-muted-foreground text-xs">{attribute.valueModeLabel}</span>
      )}
      {attribute.required && <span className="text-muted-foreground text-xs">必填</span>}
      {attribute.missingRequired && <span className="text-destructive text-xs">待补全</span>}
      {attribute.overrideState === 'CLEAR' && attribute.rawValues.length === 0 && (
        <span className="text-muted-foreground text-xs">（已清空，不退回公共值）</span>
      )}
      {attribute.source === 'UNSET' && attribute.options.length > 0 && (
        <span className="text-muted-foreground text-xs">可选：{attribute.options.join(' / ')}</span>
      )}
    </li>
  );
}

function EditPanel({
  detail,
  onClose,
  onSaved,
}: {
  detail: SkuDetail;
  onClose: () => void;
  onSaved: (saved: { version: number }) => void;
}) {
  const [draft, setDraft] = useState<SkuDetailDraft>(() => detailDraft(detail));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [conflict, setConflict] = useState(false);
  const errors = useMemo(() => validateDraft(draft), [draft]);
  const update = (patch: Partial<SkuDetailDraft>) =>
    setDraft((current) => ({ ...current, ...patch }));

  const submit = async () => {
    if (errors.length > 0) return;
    setBusy(true);
    setError('');
    setConflict(false);
    try {
      onSaved(await saveSkuDetail(detail.id, draftToInput(draft)));
    } catch (e) {
      // 409 单独处理：不是"保存失败"，是"你看到的这一版已经不是最新的一版"。
      if (isConflict(e)) setConflict(true);
      else setError(e instanceof Error ? e.message : '保存失败，请重试');
    } finally {
      setBusy(false);
    }
  };

  return (
    <Editor
      title="编辑 SKU 档案"
      description="只覆盖 3.1.2.2 拥有的字段：名称、条码、基础单位与包装尺寸重量。属性覆写属 3.2.2.2、外部条码属 3.1.2.3、单位换算版本维护属 3.1.2.4，都不在这里代劳。提交会带上当前版本号，别人先改过就会拒绝而不是覆盖。"
      close={onClose}
    >
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">SKU 名称</span>
            <input
              value={draft.name}
              onChange={(e) => update({ name: e.target.value })}
              className={control}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">内部条码</span>
            <input
              value={draft.barcode}
              onChange={(e) => update({ barcode: e.target.value })}
              className={control}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">基础单位</span>
            <input
              value={draft.baseUnit}
              onChange={(e) => update({ baseUnit: e.target.value })}
              className={control}
            />
          </label>
          <label className="space-y-1 text-sm">
            <span className="text-muted-foreground">包装方式</span>
            <input
              value={draft.packagingType}
              onChange={(e) => update({ packagingType: e.target.value })}
              placeholder="如 彩盒 / 塑封 / 裸装"
              className={control}
            />
          </label>
        </div>

        <fieldset className="space-y-3">
          <legend className="text-sm font-medium">包装尺寸重量（单位：mm / g）</legend>
          <p className="text-muted-foreground text-xs">
            留空表示「没有维护这一项」，不是 0。全部留空且包装方式也为空时，包装档案会被删除。
          </p>
          <div className="grid gap-3 sm:grid-cols-3">
            {(
              [
                ['lengthMm', '长'],
                ['widthMm', '宽'],
                ['heightMm', '高'],
                ['netWeightG', '净重'],
                ['grossWeightG', '毛重'],
              ] as [keyof SkuDetailDraft, string][]
            ).map(([key, label]) => (
              <label key={key} className="space-y-1 text-sm">
                <span className="text-muted-foreground">{label}</span>
                <input
                  value={String(draft[key])}
                  onChange={(e) => update({ [key]: e.target.value } as Partial<SkuDetailDraft>)}
                  inputMode="decimal"
                  className={control}
                />
              </label>
            ))}
          </div>
        </fieldset>

        {(errors.length > 0 || error || conflict) && (
          <div
            role="alert"
            className="border-destructive/30 bg-destructive/5 text-destructive rounded-md border p-3 text-sm"
          >
            {conflict ? (
              <p className="flex items-center gap-2">
                <History className="h-4 w-4" />
                这个 SKU 已被他人修改，本次没有保存。刷新后会看到对方的改动，请重新编辑。
              </p>
            ) : (
              <>
                {error && <p>{error}</p>}
                {errors.length > 0 && (
                  <ul className="list-disc space-y-0.5 pl-4">
                    {errors.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                )}
              </>
            )}
          </div>
        )}

        <div className="flex flex-wrap items-center justify-end gap-2">
          <span className="text-muted-foreground mr-auto text-xs">当前版本 {draft.version}</span>
          <Button variant="ghost" size="sm" onClick={onClose}>
            <X className="h-4 w-4" />
            取消
          </Button>
          <Button size="sm" disabled={busy || errors.length > 0} onClick={() => void submit()}>
            <Save className="h-4 w-4" />
            保存
          </Button>
        </div>
      </div>
    </Editor>
  );
}

function Section({ children, frame = false }: { children: React.ReactNode; frame?: boolean }) {
  return <section className={frame ? 'space-y-4' : undefined}>{children}</section>;
}

function BackLink() {
  return (
    <Link
      href="/products/sku"
      className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1.5 text-sm"
    >
      <ArrowLeft className="h-4 w-4" />
      返回 SKU 列表
    </Link>
  );
}

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

function Field({
  label,
  value,
  mono = false,
  wide = false,
}: {
  label: string;
  value: string;
  mono?: boolean;
  wide?: boolean;
}) {
  return (
    <div className={wide ? 'col-span-2' : undefined}>
      <dt className="text-muted-foreground text-xs">{label}</dt>
      <dd className={mono ? 'break-all tabular-nums' : 'break-words'}>{value}</dd>
    </div>
  );
}
