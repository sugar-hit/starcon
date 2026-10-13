'use client';

import Link from 'next/link';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Plus, RefreshCw, Trash2 } from 'lucide-react';
import { Button, EmptyState, FormField, Input, Notice, PageHeader, SectionCard, StatCard } from '@starcon/ui';
import type { LocationTree, LocationTreeCapabilities } from '../../../../../packages/api-contracts/src/index';
import { useIdentity } from '../iam/identity-shell';
import {
  createLocation,
  deleteLocation,
  errorText,
  fetchLocationTree,
  fetchLocationTreeCapabilities,
  isEmptyTree,
  locationTypeLabel,
  measureText,
  occupancyText,
  updateLocation,
  utilizationText,
  type LocationDraft,
} from './warehouse-location-tree-model';

const TYPES = ['STORAGE', 'RECEIVING', 'SHIPPING', 'QUARANTINE', 'DEFECTIVE', 'FROZEN'];

const emptyDraft: LocationDraft = {
  code: '',
  name: '',
  parentId: '',
  type: 'STORAGE',
  pickable: true,
  capacity: '',
  weightLimit: '',
};

/**
 * 8.1.2.1 库位树工作区。
 *
 * <p>蓝图：「维护库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格），支持容量、承重、类型
 * （存储/暂存/待检/残次/冻结）、是否可拣。输入：库位增删改与层级调整；防环校验。
 * 输出：库位树 + 占用情况。」
 *
 * <p>界面纪律：
 *
 * <ul>
 *   <li><b>「未量」与 0.0% 分开。</b>容量没量过时利用率是「未量」而不是 0%—— 0% 是一句
 *       关于事实的断言，「未量」是一句关于知识的断言，混起来会被拿去做库位决策。
 *   <li><b>本层占用与子树占用是两个数。</b>看「区」的人要知道整棵子树压了多少货，
 *       而区自己那一层通常是 0。
 *   <li><b>手数与预占不相加。</b>预占是已被订单锁住还没走的货。
 *   <li><b>层级调整单独一个开关。</b>「改名字」和「把库位挪到另一支」是两次不同的决定，
 *       混在一个保存按钮里会误挂。
 *   <li><b>未交付的自动分配与拣货路径显示「未接入」</b>，并写明归属模块。
 *   <li>加载 / 空 / 错误三态分开渲染。
 * </ul>
 */
export function WarehouseLocationTreeWorkspace({ warehouseId }: { warehouseId: string }) {
  const { session } = useIdentity();
  const readable = session.permissions.includes('warehouse.facilities.read');
  const creatable = session.permissions.includes('warehouse.facilities.create');
  const updatable = session.permissions.includes('warehouse.facilities.update');

  const [tree, setTree] = useState<LocationTree | null>(null);
  const [caps, setCaps] = useState<LocationTreeCapabilities | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');

  const [editingId, setEditingId] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [draft, setDraft] = useState<LocationDraft>(emptyDraft);
  const [moveToo, setMoveToo] = useState(false);
  const [saving, setSaving] = useState(false);
  const [draftError, setDraftError] = useState('');

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [data, capability] = await Promise.all([
        fetchLocationTree(warehouseId),
        fetchLocationTreeCapabilities(),
      ]);
      setTree(data);
      setCaps(capability);
    } catch (e) {
      setError(errorText(e));
    } finally {
      setLoading(false);
    }
  }, [warehouseId, readable]);

  useEffect(() => {
    void load();
  }, [load]);

  const rows = useMemo(() => tree?.levels ?? [], [tree]);
  const totals = tree?.totals;

  const startCreate = () => {
    setDraft(emptyDraft);
    setMoveToo(false);
    setDraftError('');
    setCreating(true);
    setEditingId(null);
  };

  const startEdit = (id: string) => {
    const row = rows.find((r) => r.id === id);
    if (!row) return;
    setDraft({
      code: row.code,
      name: row.name,
      parentId: row.parentId ?? '',
      type: row.type,
      pickable: row.pickable,
      capacity: row.capacity === null || row.capacity === undefined ? '' : String(row.capacity),
      weightLimit: row.weightLimit === null || row.weightLimit === undefined ? '' : String(row.weightLimit),
    });
    // 改属性默认**不动层级**：挪层级是另一个决定，要显式勾。
    setMoveToo(false);
    setDraftError('');
    setCreating(false);
    setEditingId(id);
  };

  const cancelEdit = () => {
    setCreating(false);
    setEditingId(null);
    setDraftError('');
  };

  const submit = async () => {
    if (draft.code.trim() === '' || draft.name.trim() === '') {
      setDraftError('库位编码与名称都不能为空');
      return;
    }
    setSaving(true);
    setDraftError('');
    try {
      const saved = editingId
        ? await updateLocation(warehouseId, editingId, draft, moveToo)
        : await createLocation(warehouseId, draft);
      setNotice(
        saved.action === 'MOVED'
          ? `已把「${saved.code}」挪到新父节点下。`
          : saved.action === 'CREATED'
            ? `已新建库位「${saved.code}」。`
            : `已保存「${saved.code}」的属性。`,
      );
      cancelEdit();
      await load();
    } catch (e) {
      setDraftError(errorText(e));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (id: string, code: string) => {
    try {
      await deleteLocation(warehouseId, id);
      setNotice(`已删除空库位「${code}」。`);
      await load();
    } catch (e) {
      // 409 是有子节点或压着库存 —— 后端把话说清楚了，原样透出比另写一句有用。
      setError(errorText(e));
    }
  };

  return (
    <>
      <PageHeader
        title="库位树"
        description="库位层级与属性（区 → 巷 → 排 → 架 → 层 → 格）、容量承重、类型与是否可拣，含实时占用。"
        actions={
          <>
            <Button variant="secondary" onClick={() => void load()} disabled={loading}>
              <RefreshCw className="mr-2 h-4 w-4" />
              刷新
            </Button>
            {creatable && (
              <Button onClick={() => (creating ? cancelEdit() : startCreate())}>
                <Plus className="mr-2 h-4 w-4" />
                新建库位
              </Button>
            )}
          </>
        }
      />

      {error && (
        <Notice tone="danger" title="库位树操作失败">
          {error}
        </Notice>
      )}
      {notice && (
        <Notice tone="success" title="操作完成">
          {notice}
        </Notice>
      )}
      {!readable && (
        <Notice tone="warning" title="没有查看权限">
          当前角色未被授予仓库档案（warehouse.facilities.read）的权限。
        </Notice>
      )}

      {loading && !tree && <p className="py-8 text-sm text-muted-foreground">正在加载库位树…</p>}

      {tree && totals && (
        <>
          <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
            <StatCard
              label="库位总数"
              value={String(totals.locationCount)}
              comparisonLabel={`顶层 ${totals.rootCount} 个 · 叶子 ${totals.leafCount} 个`}
            />
            <StatCard
              label="可拣库位"
              value={String(totals.pickableCount)}
              comparisonLabel="待检 / 残次 / 冻结天然不可拣"
            />
            <StatCard
              label="在手 / 预占"
              value={`${measureText(totals.onHand)} / ${measureText(totals.reserved)}`}
              comparisonLabel="两个数不相加：预占已被订单锁定"
            />
            <StatCard
              label="已量容量"
              value={`${totals.capacityMeasuredCount} / ${totals.locationCount}`}
              comparisonLabel="未量过的库位利用率显示「未量」而不是 0%"
            />
          </div>

          <SectionCard
            title="库位层级"
            description="按缩进展示层级；占用为该库位直接压着的货，右侧另给整棵子树合计。"
          >
            {isEmptyTree(tree) ? (
              <EmptyState
                title="这个仓还没有库位"
                description="库位是库存落到实处的位置。先建一个顶层「区」，再往下挂巷 / 排 / 架 / 层 / 格。"
                action={
                  creatable ? (
                    <Button onClick={startCreate}>
                      <Plus className="mr-2 h-4 w-4" />
                      新建第一个库位
                    </Button>
                  ) : undefined
                }
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-sm">
                  <thead>
                    <tr className="border-b text-left text-xs text-muted-foreground">
                      <th className="py-2 pr-3">库位</th>
                      <th className="py-2 pr-3">类型</th>
                      <th className="py-2 pr-3">可拣</th>
                      <th className="py-2 pr-3">容量</th>
                      <th className="py-2 pr-3">承重</th>
                      <th className="py-2 pr-3">本层在手 / 预占</th>
                      <th className="py-2 pr-3">利用率</th>
                      <th className="py-2 pr-3">子树在手</th>
                      <th className="py-2 pr-3" />
                    </tr>
                  </thead>
                  <tbody>
                    {rows.map((node) => {
                      const occ = occupancyText(node);
                      const depth = node.depth;
                      return (
                        <tr key={node.id} className="border-b last:border-0">
                          <td className="py-2 pr-3">
                            <span style={{ paddingLeft: `${depth * 16}px` }}>
                              <span className="mr-1 text-muted-foreground">
                                {depth > 0 ? '└' : ''}
                              </span>
                              <span className="font-medium">{node.code}</span>
                              <span className="ml-2 text-muted-foreground">{node.name}</span>
                            </span>
                          </td>
                          <td className="py-2 pr-3">{locationTypeLabel(node.type)}</td>
                          <td className="py-2 pr-3">
                            {node.pickable ? '可拣' : '不可拣'}
                            {occ.pickableHint && (
                              <span className="ml-1 text-xs text-muted-foreground">（{occ.pickableHint}）</span>
                            )}
                          </td>
                          <td className="py-2 pr-3">{measureText(node.capacity)}</td>
                          <td className="py-2 pr-3">{measureText(node.weightLimit)}</td>
                          <td className="py-2 pr-3">
                            {occ.onHand} / {occ.reserved}
                          </td>
                          <td className="py-2 pr-3">
                            {utilizationText(node.occupied.capacityUtilization)}
                            {node.occupied.capacityExceeded && (
                              <span className="ml-1 text-xs text-destructive">超容</span>
                            )}
                          </td>
                          <td className="py-2 pr-3">{measureText(node.subtreeOccupancy.onHand)}</td>
                          <td className="py-2 pr-3 text-right">
                            {updatable && (
                              <>
                                <Button variant="ghost" size="sm" onClick={() => startEdit(node.id)}>
                                  编辑
                                </Button>
                                <Button
                                  variant="ghost"
                                  size="sm"
                                  onClick={() => void remove(node.id, node.code)}
                                >
                                  <Trash2 className="mr-1 h-3 w-3" />
                                  删除
                                </Button>
                              </>
                            )}
                          </td>
                        </tr>
                      );
                    })}
                  </tbody>
                </table>
              </div>
            )}
          </SectionCard>

          {(creating || editingId) && (
            <SectionCard title={creating ? '新建库位' : '改库位属性'} description="层级调整是独立的一步，需显式勾选。">
              <div className="grid gap-3 md:grid-cols-2">
                <FormField id="loc-code" label="库位编码">
                  <Input
                    value={draft.code}
                    disabled={!creating}
                    onChange={(e) => setDraft({ ...draft, code: e.target.value })}
                  />
                </FormField>
                <FormField id="loc-name" label="库位名称">
                  <Input value={draft.name} onChange={(e) => setDraft({ ...draft, name: e.target.value })} />
                </FormField>
                <FormField id="loc-type" label="库位类型">
                  <select
                    className="h-9 w-full rounded-md border border-input bg-background px-2 text-sm"
                    value={draft.type}
                    onChange={(e) => setDraft({ ...draft, type: e.target.value })}
                  >
                    {TYPES.map((t) => (
                      <option key={t} value={t}>
                        {locationTypeLabel(t)}
                      </option>
                    ))}
                  </select>
                </FormField>
                <FormField id="loc-pickable" label="是否可拣">
                  <input
                    id="loc-pickable"
                    type="checkbox"
                    checked={draft.pickable}
                    onChange={(e) => setDraft({ ...draft, pickable: e.target.checked })}
                  />
                </FormField>
                <FormField id="loc-capacity" label="容量" hint="留空表示未量过（不是 0）">
                  <Input
                    value={draft.capacity}
                    onChange={(e) => setDraft({ ...draft, capacity: e.target.value })}
                  />
                </FormField>
                <FormField id="loc-weight" label="承重上限" hint="留空表示未量过（不是 0）">
                  <Input
                    value={draft.weightLimit}
                    onChange={(e) => setDraft({ ...draft, weightLimit: e.target.value })}
                  />
                </FormField>
                {!creating && (
                  <FormField id="loc-move" label="调整层级" hint="勾选后父库位才生效；留空表示升为顶层">
                    <input
                      id="loc-move"
                      type="checkbox"
                      checked={moveToo}
                      onChange={(e) => setMoveToo(e.target.checked)}
                    />
                  </FormField>
                )}
                {(!creating || moveToo || creating) && (
                  <FormField id="loc-parent" label="父库位" hint="留空 = 顶层根节点（区）">
                    <Input
                      value={draft.parentId}
                      onChange={(e) => setDraft({ ...draft, parentId: e.target.value })}
                      placeholder="库位 id，留空即顶层"
                    />
                  </FormField>
                )}
              </div>
              {draftError && (
                <Notice tone="danger" title="保存失败">
                  {draftError}
                </Notice>
              )}
              <div className="mt-3 flex gap-2">
                <Button onClick={() => void submit()} disabled={saving}>
                  {saving ? '保存中…' : '保存'}
                </Button>
                <Button variant="secondary" onClick={cancelEdit}>
                  取消
                </Button>
              </div>
            </SectionCard>
          )}

          {caps && (
            <SectionCard title="能力声明" description="哪些维度有真相来源、哪些尚未交付。">
              <ul className="space-y-1 text-sm">
                <li>层级维护：{caps.hierarchyMaintained ? '已交付（防环与跨仓库挂接由数据库拒绝）' : '未交付'}</li>
                <li>容量与承重：{caps.capacityTracked && caps.weightTracked ? '已交付' : '未交付'}</li>
                <li>占用情况：{caps.occupancyLive ? '实时来自库存明细' : '未交付'}</li>
                <li>
                  库位自动分配：
                  {caps.autoAllocationAvailable
                    ? '已交付'
                    : `未接入（属 ${caps.autoAllocationOwnerModule}）`}
                </li>
                <li>拣货路径：{caps.pickPathOwnerModule ? `未接入（属 ${caps.pickPathOwnerModule}）` : '已交付'}</li>
                <li>层级上限：{caps.maxDepth} 层（区 → 巷 → 排 → 架 → 层 → 格）</li>
              </ul>
              <p className="mt-2 text-xs text-muted-foreground">{caps.note}</p>
            </SectionCard>
          )}
        </>
      )}

      <p className="mt-6 text-xs text-muted-foreground">
        <Link href="/warehouse/facilities" className="underline">
          返回仓库档案
        </Link>
      </p>
    </>
  );
}
