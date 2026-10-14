'use client';

import { useCallback, useEffect, useState } from 'react';
import { Lock, RefreshCw, Snowflake, TriangleAlert } from 'lucide-react';
import { Badge, Button, Input, SectionCard } from '@starcon/ui';
import type { BomBinding } from '../../../../../packages/api-contracts/src/index';
import {
  depthIndent,
  driftHeadline,
  fetchBomBinding,
  freezeBomBinding,
  isPurchaseLine,
  requiredText,
  scrapText,
  snapshotTone,
} from './bom-binding-model';
import { errorText } from '../inventory/inventory-model';

/**
 * 6.4.1.2 BOM 关联面板：加工单的**冻结展开结果**。
 *
 * <p>蓝图：「加工单引用 BOM 版本并冻结展开结果，后续 BOM 修改不影响已下达的加工单。」
 * 输出「冻结的组件清单快照」。
 *
 * <p><b>这一块与 6.4.1.1 的「组件领用记录」不是同一份东西</b>，页面必须让人分得清：
 *
 * <ul>
 *   <li><b>组件领用记录</b>（6.4.1.1）：单层，回答「这张单要领哪些料、领了多少、还欠多少」，
 *       随仓库出库逐行推进；
 *   <li><b>BOM 冻结快照</b>（本面板）：多层展开树，回答「这批料当时是按哪棵树、哪一版、
 *       什么损耗率算出来的」—— 一个成品用半成品、半成品又由面料拉链组成时，
 *       单层清单答不出「这 3 个半成品的面料按哪一版算的」。
 * </ul>
 *
 * <p><b>界面纪律</b>：
 *
 * <ul>
 *   <li><b>冻结只在草稿态可做</b>：已下达的单已经在仓库按当时的量领过料，
 *       这时补一份快照会让「按哪棵树算的」出现两个答案。按钮直接禁用并写明原因，
 *       而不是给一个点下去 400 的按钮。
 *   <li><b>截断的快照显红</b>：被深度上限截断意味着清单不完整（规则 E4），
 *       静默显示一份不完整的清单比不显示更危险。
 *   <li><b>加载 / 空 / 错误三态齐全</b>：没冻结是正常业务状态，不是错误。
 * </ul>
 */
export function BomBindingPanel({
  orderId,
  orderStatus,
}: {
  orderId: string;
  orderStatus: string;
}) {
  const [binding, setBinding] = useState<BomBinding | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [warehouseId, setWarehouseId] = useState('');
  const [note, setNote] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setBinding(await fetchBomBinding(orderId));
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [orderId]);

  useEffect(() => {
    void load();
  }, [load]);

  const freeze = async () => {
    setBusy(true);
    setError(null);
    try {
      await freezeBomBinding(orderId, {
        bomVersionId: binding?.snapshot?.bomVersionId ?? '',
        outputQuantity: Number(binding?.snapshot?.outputQuantity ?? 0),
        warehouseId: warehouseId.trim() || null,
        asOf: null,
        maxDepth: null,
        note: note.trim() || null,
      });
      await load();
    } catch (e) {
      setError(errorText(e));
    } finally {
      setBusy(false);
    }
  };

  const snapshot = binding?.snapshot ?? null;
  const freezeable = orderStatus === 'DRAFT';
  const headline = binding ? driftHeadline(binding) : null;

  return (
    <SectionCard title="BOM 冻结快照（6.4.1.2）">
      <p className="text-muted-foreground mb-3 text-xs">
        加工单引用 BOM 版本并冻结<b>展开结果</b>，之后 BOM 怎么改都不影响这张单 ——
        这是「冻结与订单冻结组件同理」： 加工周期长，BOM
        中途改配会导致实物与系统不符。这里与上面的「组件领用记录」不是同一份：
        领用记录是单层、按仓库出库推进；冻结快照是多层展开树，记录当时按哪一版、什么损耗率算的。
      </p>

      {error && (
        <div className="mb-3 flex items-start gap-2 rounded-md border border-red-500/40 bg-red-500/10 p-3 text-sm text-red-600">
          <TriangleAlert className="mt-0.5 size-4 shrink-0" />
          <p>{error}</p>
        </div>
      )}

      {loading && <p className="text-muted-foreground text-sm">正在加载冻结快照…</p>}

      {!loading && !snapshot && !error && (
        <div className="flex flex-col gap-3">
          <p className="text-muted-foreground text-sm">
            这张加工单还没有冻结 BOM 展开结果。加工周期内 BOM
            一旦改配，就无法回答「这批料当时按哪一版、什么损耗率领的」。
          </p>
          {freezeable ? (
            <div className="flex flex-wrap items-end gap-2">
              <label className="flex flex-col gap-1 text-sm">
                <span className="text-muted-foreground">仓库（可空 = 全仓合计）</span>
                <Input
                  value={warehouseId}
                  placeholder="仓库 ID"
                  onChange={(e) => setWarehouseId(e.target.value)}
                  className="w-64"
                />
              </label>
              <Button disabled={busy} onClick={() => void freeze()}>
                <Snowflake />
                冻结展开结果
              </Button>
            </div>
          ) : (
            <p className="text-xs text-amber-600">
              当前状态是「{orderStatus}」，已下达的加工单不能补冻结 ——
              它已经按当时的组件领料清单发出去了。 要按新 BOM 加工请另开一张加工单。
            </p>
          )}
        </div>
      )}

      {!loading && snapshot && (
        <div className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center gap-2 text-sm">
            <Badge tone={snapshotTone(snapshot)}>
              {snapshot.truncated ? '清单不完整（按深度上限截断）' : '清单完整'}
            </Badge>
            <span className="text-muted-foreground">
              BOM 版本 {snapshot.bomVersionNo} · 成品 {snapshot.outputSkuCode} · 产量{' '}
              {requiredText(snapshot.outputQuantity)} · {snapshot.lineCount} 行
            </span>
            <Button variant="ghost" size="sm" onClick={() => void load()} disabled={loading}>
              <RefreshCw className={loading ? 'animate-spin' : ''} />
              刷新
            </Button>
          </div>

          {snapshot.truncated && (
            <p className="rounded-md border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-700">
              这份快照按第 {snapshot.reachedDepth} 层的深度上限被截断，清单不完整：
              展开到的部分已冻结，未展开的部分这张单答不出「按哪一版算」。
            </p>
          )}

          {headline && (
            <p className="rounded-md border border-sky-500/40 bg-sky-500/10 p-2 text-xs">
              {headline}
              。已下达的加工单不受影响（领料按冻结快照走）；按新版本生产的下一批货要另开单。
            </p>
          )}

          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="px-2 py-1">组件</th>
                  <th className="px-2 py-1">来源版本</th>
                  <th className="px-2 py-1">单位用量</th>
                  <th className="px-2 py-1">损耗率</th>
                  <th className="px-2 py-1">需求量</th>
                  <th className="px-2 py-1">可用 / 在途</th>
                  <th className="px-2 py-1">缺口</th>
                </tr>
              </thead>
              <tbody>
                {(snapshot.lines ?? []).map((line) => (
                  <tr key={line.id} className="border-t">
                    <td
                      className="px-2 py-1"
                      style={{ paddingLeft: `${8 + depthIndent(line) * 16}px` }}
                    >
                      {line.skuCode}
                      {line.isSubAssembly && (
                        <span className="text-muted-foreground ml-1 text-xs">（子装配）</span>
                      )}
                      {isPurchaseLine(line) && (
                        <span className="ml-1 text-xs text-amber-600">需采购</span>
                      )}
                    </td>
                    <td className="text-muted-foreground px-2 py-1 text-xs">
                      {line.sourceBomCode} {line.sourceVersionNo}
                    </td>
                    <td className="px-2 py-1">{requiredText(line.unitQuantity)}</td>
                    <td className="px-2 py-1">{scrapText(line.scrapRate)}</td>
                    <td className="px-2 py-1">{requiredText(line.requiredQuantity)}</td>
                    <td className="text-muted-foreground px-2 py-1 text-xs">
                      {requiredText(line.available)} / {requiredText(line.inTransit)}
                    </td>
                    <td className="px-2 py-1">
                      {Number(line.gap) > 0 ? (
                        <span className="text-amber-600">{requiredText(line.gap)}</span>
                      ) : (
                        <span className="text-muted-foreground">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="text-muted-foreground flex items-start gap-1 text-xs">
            <Lock className="mt-0.5 size-3 shrink-0" />
            快照已冻结：单位用量 / 损耗率 / 需求量都是冻结那一刻的值，读它们不 join 现在的 BOM。
            损耗率是<b>百分数</b>（5 = 5%），需求量按规则 R7 = 单位用量 × 产量 × (1 + 损耗率/100)，
            6 位小数不取整（取整成采购量是 3.5.2.2 与 06 采购建议的职责）。
          </p>

          {binding && binding.notes.length > 0 && (
            <ul className="text-muted-foreground rounded-md border p-2 text-xs">
              {binding.notes.map((n) => (
                <li key={n}>· {n}</li>
              ))}
            </ul>
          )}
        </div>
      )}
    </SectionCard>
  );
}
