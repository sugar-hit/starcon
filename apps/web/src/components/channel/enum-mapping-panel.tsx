'use client';

import { useCallback, useEffect, useState } from 'react';
import { Badge, Button } from '@starcon/ui';
import { Plus, RotateCcw, Trash2 } from 'lucide-react';
import type {
  EnumMappingView,
  PlatformView,
} from '../../../../../packages/api-contracts/src/catalog';
import { api } from '@/lib/api';

/**
 * 2.1.2.1 平台枚举映射（平台默认 + 本租户覆盖）
 *
 * <p>这一块以前是个壳：库里有表、端点只读、页面完全没有入口，`resolveEnum` 也没有任何调用方，
 * 于是"映射"永远为空，谁也看不到"未映射"这件事。补齐之后：
 *
 * <ul>
 *   <li>平台默认来自全局码表，所有租户一致，**不能**被单个租户改写（改它等于污染所有租户的状态机）；
 *   <li>本租户覆盖写在带 tenant_id 的覆盖表里，RLS 隔离，优先级高于默认；
 *   <li>两边都没有就是"未映射"，同步到订单/售后/物流时会显式报错，不会兜底。
 * </ul>
 */
export function EnumMappingPanel({
  platform,
  can,
}: {
  platform: PlatformView;
  can: (permission: string) => boolean;
}) {
  const [open, setOpen] = useState(false);
  const [mappings, setMappings] = useState<EnumMappingView[] | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [kind, setKind] = useState('');
  const [external, setExternal] = useState('');
  const [internal, setInternal] = useState('');

  const load = useCallback(async () => {
    setError('');
    try {
      setMappings(
        await api<EnumMappingView[]>(`channel/platforms/${platform.code}/enums`),
      );
    } catch (e) {
      setError(e instanceof Error ? e.message : '映射加载失败');
      setMappings([]);
    }
  }, [platform.code]);

  useEffect(() => {
    if (open) void load();
  }, [open, load]);

  async function submit() {
    setBusy(true);
    setError('');
    try {
      await api(`channel/platforms/${platform.code}/enums`, 'POST', {
        enumKind: kind.trim(),
        externalValue: external.trim(),
        internalValue: internal.trim(),
      });
      setKind('');
      setExternal('');
      setInternal('');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '保存失败');
    } finally {
      setBusy(false);
    }
  }

  async function removeOverride(m: EnumMappingView) {
    if (!window.confirm(`删除 ${m.enumKind} / ${m.externalValue} 的租户覆盖？删除后会回落到平台默认。`))
      return;
    setBusy(true);
    setError('');
    try {
      await api(`channel/platforms/${platform.code}/enums/${m.id}`, 'DELETE');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : '删除失败');
    } finally {
      setBusy(false);
    }
  }

  if (!open)
    return (
      <Button size="sm" variant="ghost" onClick={() => setOpen(true)}>
        枚举映射
      </Button>
    );

  const overrideCount = (mappings ?? []).filter((m) => m.source === 'TENANT').length;

  return (
    <div className="space-y-2">
      <div className="flex items-center justify-between gap-2">
        <p className="text-copy-12 text-muted-foreground">
          平台枚举 → 内部枚举。订单状态、售后原因、物流轨迹都靠它对齐。
        </p>
        <Button size="sm" variant="ghost" onClick={() => setOpen(false)}>
          收起
        </Button>
      </div>

      {error && (
        <p role="alert" className="text-destructive text-copy-12">
          {error}
        </p>
      )}

      {mappings === null ? (
        <p role="status" className="text-muted-foreground text-copy-12">
          正在加载映射…
        </p>
      ) : mappings.length === 0 ? (
        // 关键：空映射必须说明后果，不能只显示一个空的框让人以为"没配置而已"
        <p className="text-muted-foreground text-copy-12">
          该平台还没有登记任何枚举映射。从平台同步订单、售后或物流轨迹时，外部枚举会显式报
          「未映射」并停在失败队列，不会被兜底成某个猜测状态。
        </p>
      ) : (
        <ul className="space-y-1">
          {mappings.map((m) => (
            <li
              key={`${m.enumKind}-${m.externalValue}`}
              className="flex flex-wrap items-center gap-2 text-copy-12"
            >
              <span className="font-mono break-all">
                {m.enumKind} · {m.externalValue} → {m.internalValue}
              </span>
              <Badge tone={m.source === 'TENANT' ? 'warning' : 'neutral'}>
                {m.source === 'TENANT' ? '本租户覆盖' : '平台默认'}
              </Badge>
              {m.source === 'TENANT' && can('channel.registry.delete') && (
                <Button
                  size="sm"
                  variant="ghost"
                  disabled={busy}
                  onClick={() => void removeOverride(m)}
                >
                  <Trash2 className="h-3.5 w-3.5" />
                  删覆盖
                </Button>
              )}
            </li>
          ))}
        </ul>
      )}

      {can('channel.registry.update') && (
        <div className="grid gap-2 md:grid-cols-3">
          <input
            value={kind}
            onChange={(e) => setKind(e.target.value)}
            placeholder="枚举种类，如 ORDER_STATUS"
            className="border-border bg-background text-copy-12 h-8 rounded-md border px-2"
          />
          <input
            value={external}
            onChange={(e) => setExternal(e.target.value)}
            placeholder="平台值，如 Canceled"
            className="border-border bg-background text-copy-12 h-8 rounded-md border px-2"
          />
          <input
            value={internal}
            onChange={(e) => setInternal(e.target.value)}
            placeholder="内部值，如 USER_CANCELLED"
            className="border-border bg-background text-copy-12 h-8 rounded-md border px-2"
          />
          <div className="md:col-span-3">
            <Button
              size="sm"
              disabled={busy || !kind.trim() || !external.trim() || !internal.trim()}
              onClick={() => void submit()}
            >
              {overrideCount > 0 ? <RotateCcw className="h-3.5 w-3.5" /> : <Plus className="h-3.5 w-3.5" />}
              保存租户覆盖
            </Button>
            <p className="text-muted-foreground text-copy-12 mt-1">
              同一个「种类 + 平台值」重复保存就是改写。平台默认不受影响，别的租户也看不到这条覆盖。
            </p>
          </div>
        </div>
      )}
    </div>
  );
}
