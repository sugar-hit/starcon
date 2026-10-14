'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState } from '@starcon/ui';
import {
  ArrowLeft,
  CircleSlash,
  Eraser,
  Info,
  ListFilter,
  RefreshCw,
  TriangleAlert,
  Undo2,
} from 'lucide-react';
import type {
  SkuOverrideAttribute,
  SkuOverrideView,
} from '../../../../../packages/api-contracts/src/index';
import {
  actionable,
  clearOverride,
  effectiveText,
  errorText,
  fetchOverridePanel,
  isRequiredMissing,
  missingFromError,
  missingHint,
  type OverrideMissing,
  removeOverride,
  setOverride,
  sourceLabel,
  sourceTone,
  writable,
} from './sku-attribute-override-model';
import { Field, control } from './fields';
import { useIdentity } from '../iam/identity-shell';

/**
 * 3.2.2.2 SKU 属性覆写工作区。
 *
 * <p>回答的问题是"**这个 SKU 实际是什么参数**"，不是"数据库里存了两行什么"。
 * 所以每条属性都同时给出：最终有效值、这个值来自哪一侧、以及能不能改。
 *
 * <p>六个界面纪律：
 *
 * <ul>
 *   <li>**来源必须显示**，且四态各有各的说法（后端 sourceLabel 已经给中文，这里只做兜底）。
 *       只显示一个空值的话，运营无法分辨"这个 SKU 没有该属性"与"还没人填"。
 *   <li>**三个动作是三个按钮**，不做成"保存"一个按钮加状态下拉：清空与取消覆写
 *       是方向相反的两件事（下拉框里挨着放，迟早有人选错）。
 *   <li>**必填缺口在读的时候就标红**，不等保存失败才说。
 *   <li>**改不动的属性直接禁用并说明原因**，用后端给的 blockReason。
 *   <li>**加载 / 错误 / 空态三态齐全**：这个 SPU 一个属性都没配，与面板加载失败，
 *       是两种完全不同的空态，文案不能一样。
 *   <li>**只读角色看得见但动不了**，写操作按 catalog.attribute.update 收敛。
 * </ul>
 */
export function SkuAttributeOverrideWorkspace({ skuId }: { skuId: string }) {
  const { session } = useIdentity();
  const canWrite = writable(session.permissions);

  const [panel, setPanel] = useState<SkuOverrideView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [missing, setMissing] = useState<OverrideMissing[]>([]);
  const [busy, setBusy] = useState(false);
  const [onlyActionable, setOnlyActionable] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPanel(await fetchOverridePanel(skuId));
    } catch (e) {
      setError(errorText(e, '属性覆写面板加载失败'));
    } finally {
      setLoading(false);
    }
  }, [skuId]);

  useEffect(() => {
    void load();
  }, [load]);

  const attributes = useMemo(() => {
    const rows = panel?.attributes ?? [];
    return onlyActionable ? rows.filter(actionable) : rows;
  }, [panel, onlyActionable]);

  /**
   * 写完直接用响应体刷新面板：后端每个写端点都回传写入后的解析结果，
   * 不必为了看结果再拉一次（也避免两次请求之间被别人改掉而显示错的状态）。
   */
  async function run(action: () => Promise<SkuOverrideView>) {
    setBusy(true);
    setActionError(null);
    setMissing([]);
    try {
      setPanel(await action());
    } catch (e) {
      setMissing(missingFromError(e));
      setActionError(
        isRequiredMissing(e)
          ? '类目要求的必填属性没有生效值，这次修改没有保存（已回滚）。缺哪几条见下面清单。'
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
          <h1 className="text-h-20 text-primary font-semibold tracking-tight">SKU 属性覆写</h1>
          <p className="text-muted-foreground text-copy-13 mt-1">
            {panel
              ? `${panel.spuCode} · ${panel.skuCode}（${panel.skuName}）`
              : '解析后的最终有效值：SPU 公共值经 SKU 覆写或显式清空后的结果。'}
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

      {/* 四态计数不受筛选影响，所以放在筛选旁边而不是跟着结果走。 */}
      {panel && (
        <div className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
          <Stat label="属性总数" value={panel.summary.total} />
          <Stat label="SKU 覆写" value={panel.summary.overridden} />
          <Stat label="已显式清空" value={panel.summary.cleared} />
          <Stat label="继承 SPU 公共值" value={panel.summary.inherited} />
          <Stat label="未设置" value={panel.summary.unset} />
          <Stat
            label="必填补口"
            value={panel.summary.missingRequired}
            danger={panel.summary.missingRequired > 0}
          />
        </div>
      )}

      {panel && (
        <div className="flex flex-wrap items-center gap-2">
          <label className="text-muted-foreground text-copy-13 flex items-center gap-2">
            <input
              type="checkbox"
              checked={onlyActionable}
              onChange={(e) => setOnlyActionable(e.target.checked)}
            />
            只看覆写过或缺值的属性
          </label>
          {onlyActionable && (
            <Button variant="ghost" size="sm" onClick={() => setOnlyActionable(false)}>
              <ListFilter className="h-4 w-4" />
              显示全部
            </Button>
          )}
        </div>
      )}

      {actionError && (
        <div
          role="alert"
          className="border-destructive-soft bg-destructive-soft/40 text-destructive flex items-start gap-2 rounded-md border px-3 py-2 text-sm"
        >
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <span>{actionError}</span>
        </div>
      )}

      {/* 缺失清单按条渲染：只给一句"必填未填"的话，用户不知道该去补哪几条。 */}
      {missing.length > 0 && (
        <div className="border-warning-soft bg-warning-soft/40 rounded-md border px-3 py-3">
          <p className="text-copy-13 font-medium">必填补全清单 · {missing.length} 项</p>
          <ul className="mt-2 space-y-1.5">
            {missing.map((item) => (
              <li
                key={item.attributeId}
                className="text-copy-13 flex flex-wrap items-baseline gap-2"
              >
                <Badge tone="warning">{item.code}</Badge>
                <span className="font-medium">{item.name}</span>
                <span className="text-muted-foreground">{item.reason}</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      {loading ? (
        <div className="text-muted-foreground rounded-md border border-dashed p-10 text-center text-sm">
          正在加载属性有效值…
        </div>
      ) : error ? (
        <EmptyState
          icon={<TriangleAlert className="h-5 w-5" />}
          title="属性覆写面板加载失败"
          description={error}
          action={
            <Button variant="ghost" onClick={() => void load()}>
              重试
            </Button>
          }
        />
      ) : !panel || panel.attributes.length === 0 ? (
        <EmptyState
          icon={<Info className="h-5 w-5" />}
          title="这个商品还没有配置属性"
          description="属性体系在商品中心的属性字典里维护。先给这个 SPU 建属性并设好公共值，再回来做 SKU 覆写。"
          action={
            <Link href="/products/attributes">
              <Button variant="ghost">去属性字典</Button>
            </Link>
          }
        />
      ) : onlyActionable && attributes.length === 0 ? (
        <EmptyState
          icon={<Info className="h-5 w-5" />}
          title="没有覆写过，也没有缺值的属性"
          description="当前筛选下没有需要处理的属性：这个 SKU 的每条属性都直接继承 SPU 公共值，且必填项都齐了。"
          action={
            <Button variant="ghost" onClick={() => setOnlyActionable(false)}>
              显示全部属性
            </Button>
          }
        />
      ) : (
        <div className="space-y-3">
          {attributes.map((attribute) => (
            <OverrideRow
              key={attribute.attributeId}
              attribute={attribute}
              canWrite={canWrite}
              busy={busy}
              onSet={(values) => void run(() => setOverride(skuId, attribute.attributeId, values))}
              onClear={() => void run(() => clearOverride(skuId, attribute.attributeId))}
              onRemove={() => void run(() => removeOverride(skuId, attribute.attributeId))}
            />
          ))}
        </div>
      )}

      {panel?.note && (
        <p className="text-muted-foreground border-t pt-3 text-xs leading-relaxed">{panel.note}</p>
      )}
    </div>
  );
}

/**
 * 单条属性：一行说清"有效值是什么、来自哪、能不能改"。
 *
 * <p>取值控件按数据类型分：枚举给下拉（只列启用中的选项，已停用的历史值在提交时会被拒），
 * 布尔给真假二选一，其余给文本框。
 */
function OverrideRow({
  attribute,
  canWrite,
  busy,
  onSet,
  onClear,
  onRemove,
}: {
  attribute: SkuOverrideAttribute;
  canWrite: boolean;
  busy: boolean;
  onSet: (values: string[]) => void;
  onClear: () => void;
  onRemove: () => void;
}) {
  const [draft, setDraft] = useState(() => attribute.effectiveValues[0] ?? '');

  const text = effectiveText(attribute);
  const disabled = busy || !canWrite || !attribute.overridable;
  const dirty = draft.trim() !== '' && draft.trim() !== (attribute.effectiveValues[0] ?? '');
  const overridden = attribute.overrideState !== 'INHERIT';

  return (
    <div
      className={`rounded-md border p-3 ${attribute.missingRequired ? 'border-warning-soft' : ''}`}
    >
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-copy-13 font-medium">{attribute.name}</span>
            <span className="text-muted-foreground text-xs">{attribute.code}</span>
            <Badge tone={sourceTone(attribute)}>{sourceLabel(attribute)}</Badge>
            <Badge tone="neutral">{attribute.valueModeLabel}</Badge>
            {(attribute.required || attribute.categoryRequired) && (
              <Badge tone={attribute.required ? 'danger' : 'warning'}>
                {attribute.required ? '定义必填' : '类目必填'}
              </Badge>
            )}
          </div>
          <p className="text-copy-13 mt-1.5">
            {text === null ? (
              <span className="text-muted-foreground">
                {attribute.source === 'SKU_CLEARED'
                  ? '有效值：空（已显式清空，不退回 SPU 公共值）'
                  : '有效值：空（从未设置）'}
              </span>
            ) : (
              <>
                <span className="text-muted-foreground">有效值：</span>
                <span className="font-medium">{text}</span>
                {attribute.unit && <span className="text-muted-foreground"> {attribute.unit}</span>}
              </>
            )}
          </p>
          {attribute.missingRequired && (
            <p className="text-warning mt-1 text-xs">{missingHint(attribute)}</p>
          )}
          {attribute.blockReason && !attribute.overridable && (
            <p className="text-muted-foreground mt-1 text-xs">{attribute.blockReason}</p>
          )}
        </div>

        {canWrite && (
          <div className="flex shrink-0 flex-wrap items-center gap-1.5">
            {overridden && (
              <Button
                variant="ghost"
                size="sm"
                disabled={busy}
                title="删除这一行覆写，落回 SPU 公共值"
                onClick={onRemove}
              >
                <Undo2 className="h-4 w-4" />
                取消覆写
              </Button>
            )}
            <Button
              variant="ghost"
              size="sm"
              disabled={disabled}
              title="显式清空：这个 SKU 确实没有该属性，且不退回 SPU 公共值"
              onClick={onClear}
            >
              <Eraser className="h-4 w-4" />
              显式清空
            </Button>
          </div>
        )}
      </div>

      {canWrite && (
        <div className="mt-3 flex flex-wrap items-end gap-2">
          <div className="min-w-48 flex-1">
            <Field label="覆写值">
              {attribute.dataType === 'OPTION' ? (
                <select
                  className={control}
                  value={draft}
                  disabled={disabled}
                  onChange={(e) => setDraft(e.target.value)}
                >
                  <option value="">请选择</option>
                  {attribute.options.map((option) => (
                    <option key={option} value={option}>
                      {option}
                    </option>
                  ))}
                </select>
              ) : attribute.dataType === 'BOOLEAN' ? (
                <select
                  className={control}
                  value={draft}
                  disabled={disabled}
                  onChange={(e) => setDraft(e.target.value)}
                >
                  <option value="">请选择</option>
                  <option value="true">是</option>
                  <option value="false">否</option>
                </select>
              ) : (
                <input
                  className={control}
                  value={draft}
                  disabled={disabled}
                  placeholder={placeholderFor(attribute)}
                  onChange={(e) => setDraft(e.target.value)}
                />
              )}
            </Field>
          </div>
          <Button size="sm" disabled={disabled || !dirty} onClick={() => onSet([draft.trim()])}>
            保存覆写
          </Button>
          <Button
            variant="ghost"
            size="sm"
            disabled={busy}
            title="放弃这次编辑，回到当前生效值"
            onClick={() => setDraft(attribute.effectiveValues[0] ?? '')}
          >
            <CircleSlash className="h-4 w-4" />
            重置
          </Button>
        </div>
      )}
    </div>
  );
}

function placeholderFor(attribute: SkuOverrideAttribute): string {
  switch (attribute.dataType) {
    case 'DECIMAL':
      return attribute.unit ? `数值（${attribute.unit}）` : '数值';
    case 'DATE':
      return '日期，格式 2026-10-07';
    default:
      return '覆写值';
  }
}

function Stat({ label, value, danger }: { label: string; value: number; danger?: boolean }) {
  return (
    <div className={`rounded-md border p-3 ${danger ? 'border-warning-soft' : ''}`}>
      <p className="text-muted-foreground text-xs">{label}</p>
      <p className={`mt-1 text-2xl font-semibold tabular-nums ${danger ? 'text-warning' : ''}`}>
        {value}
      </p>
    </div>
  );
}
