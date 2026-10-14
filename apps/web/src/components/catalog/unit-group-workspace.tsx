'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, SectionCard, StatCard } from '@starcon/ui';
import {
  ArrowLeft,
  Calculator,
  Layers,
  Plus,
  RefreshCw,
  Save,
  Scale,
  Trash2,
  TriangleAlert,
} from 'lucide-react';
import type {
  UnitGroupConvertResult,
  UnitGroupDetail,
  UnitGroupOverview,
  UnitGroupPage,
  UnitGroupRow,
  UnitOfMeasure,
} from '../../../../../packages/api-contracts/src/index';
import { Field, control } from '../common/fields';
import {
  DIMENSIONS,
  DIMENSION_LABELS,
  addConversion,
  convertGroup,
  createGroup,
  createUnit,
  deleteConversion,
  deleteGroup,
  deleteUnit,
  denyAction,
  denyLabel,
  dimensionLabel,
  draftToConvertInput,
  draftToGroupConversionInput,
  draftToGroupInput,
  emptyConvertDraft,
  emptyGroupConversionDraft,
  emptyGroupDraft,
  errorText,
  fetchGroup,
  fetchGroups,
  fetchOverview,
  fetchUnits,
  groupDraft,
  isConflict,
  isNotFound,
  linkableUnits,
  quantityText,
  saveGroup,
  setGroupEnabled,
  timestamp,
  updateUnit,
  validateConvert,
  validateGroup,
  validateGroupConversion,
  windowText,
  type ConvertDraft,
  type GroupConversionDraft,
  type GroupDraft,
} from './unit-group-model';
import { Editor } from './fields';
import { useIdentity } from '../iam/identity-shell';

const PAGE_SIZE = 20;

/**
 * 3.3.3.1 计量单位（字典视角）工作区。
 *
 * <p>页面按「字典 → 组 → 换算」的顺序排，与一次换算治理真正要走的顺序一致：
 *
 * <ul>
 *   <li><b>概览</b> —— 规模 + 三个治理缺口指标。最要紧的是「有多少单位没进任何单位组」
 *       和「有多少组存在缺生效版本的成员」：这两类都会让换算查询直接报一个原因码。
 *   <li><b>单位字典</b> —— 编码 / 名称 / 量纲 / 数量精度 / 采购·销售·存储可用性 / 启停。
 *       这一块的写入口是 3.1.2.4 的 {@code unit-conversion/units}，本模块只读字典。
 *   <li><b>单位组</b> —— 组字典与成员维护。成员候选只列**同量纲且启用**的单位，
 *       所以「件」不会出现在重量组的候选里，不必提交后才读错误消息。
 *   <li><b>组内换算版本</b> —— 版本化 + 生效区间，当前生效的那一版单独标出来。
 *   <li><b>换算试算</b> —— 蓝图的输出「换算查询能力」。换不动时显示原因码与该做什么，
 *       而不是一句"换算失败"。
 * </ul>
 *
 * <p>三条界面纪律：
 *
 * <ul>
 *   <li>**换算判定一律来自后端**。`denied` / `denyCode` 是 `UnitGroupService.convert` 的唯一输出，
 *       前端只把码翻成话。在浏览器里再写一份算法，症状是「页面说能换算、接口说不能」。
 *   <li>**「没有」不画成 0**。未归组单位数、缺生效版本的成员数都和自己的含义一起出现。
 *   <li>**历史版本照常可读**。停用挡的是"不许再新建"，不是"抹掉过去"——
 *       已闭合的历史换算版本在界面上照常显示，只是当前生效的那一版会被标出来。
 * </ul>
 */
export function UnitGroupWorkspace() {
  const { session } = useIdentity();
  const readable = session.permissions.includes('catalog.spu.read');
  // 写权限：单位组走 catalog.spu.*（与同 L3 的分类树、品牌一致），
  // 单位字典行走 catalog.sku.*（3.1.2.4 的写入所有权）。两个都要才都能全权维护。
  const groupWritable = session.permissions.includes('catalog.spu.update');
  const unitWritable = session.permissions.includes('catalog.sku.update');

  const [overview, setOverview] = useState<UnitGroupOverview | null>(null);
  const [page, setPage] = useState<UnitGroupPage | null>(null);
  const [units, setUnits] = useState<UnitOfMeasure[]>([]);
  const [ready, setReady] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState('');

  const [keyword, setKeyword] = useState('');
  const [dimension, setDimension] = useState('');
  const [enabled, setEnabled] = useState<boolean | null>(null);
  const [index, setIndex] = useState(0);

  const [detail, setDetail] = useState<UnitGroupDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState('');
  const [editing, setEditing] = useState<GroupDraft | 'new' | null>(null);
  const [conversionDraft, setConversionDraft] = useState<GroupConversionDraft | null>(null);
  const [unitEditing, setUnitEditing] = useState<{
    id: string | null;
    draft: Partial<UnitOfMeasure> & { code: string; name: string };
  } | null>(null);

  const [convert, setConvert] = useState<ConvertDraft>(emptyConvertDraft());
  const [convertResult, setConvertResult] = useState<UnitGroupConvertResult | null>(null);
  const [convertError, setConvertError] = useState('');
  const [convertBusy, setConvertBusy] = useState(false);

  const load = useCallback(async () => {
    if (!readable) {
      setLoading(false);
      return;
    }
    setLoading(true);
    setError('');
    try {
      const [summary, list, dictionary] = await Promise.all([
        fetchOverview(),
        fetchGroups({ keyword, dimension, enabled, page: index, size: PAGE_SIZE }),
        fetchUnits(),
      ]);
      setOverview(summary);
      setPage(list);
      setUnits(dictionary);
    } catch (e) {
      setError(errorText(e, '计量单位加载失败'));
    } finally {
      setLoading(false);
      setReady(true);
    }
  }, [readable, keyword, dimension, enabled, index]);

  useEffect(() => {
    void load();
  }, [load]);

  async function openDetail(groupId: string) {
    setDetailLoading(true);
    setDetailError('');
    setConvertResult(null);
    try {
      setDetail(await fetchGroup(groupId, ''));
    } catch (e) {
      // 404 是"这条被别人删了"而不是页面坏了，单独说，别让整页变成"加载失败"。
      setDetailError(
        isNotFound(e) ? '这个单位组已被删除，请刷新列表。' : errorText(e, '单位组加载失败'),
      );
    } finally {
      setDetailLoading(false);
    }
  }

  async function run(action: () => Promise<unknown>, failure: string, message?: string) {
    setBusy(true);
    setActionError(null);
    try {
      await action();
      if (message) setNotice(message);
      await load();
      if (detail) await openDetail(detail.group.id);
      return true;
    } catch (e) {
      setActionError(errorText(e, failure));
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function runConvert() {
    const problems = validateConvert(convert);
    if (problems.length > 0) {
      setConvertError(problems.join('；'));
      return;
    }
    setConvertBusy(true);
    setConvertError('');
    try {
      setConvertResult(await convertGroup(draftToConvertInput(convert)));
    } catch (e) {
      setConvertError(errorText(e, '换算查询失败'));
      setConvertResult(null);
    } finally {
      setConvertBusy(false);
    }
  }

  const rows = page?.items ?? [];
  const lastPage = Math.max(0, Math.ceil((page?.total ?? 0) / PAGE_SIZE) - 1);
  const selectable = useMemo(
    () =>
      units
        .filter((unit) => unit.enabled)
        .sort((l, r) => l.dimension.localeCompare(r.dimension) || l.code.localeCompare(r.code)),
    [units],
  );
  // 编辑时的候选成员：只列**同量纲且启用**的单位。
  // 量纲过滤放在候选里，运营在多选框里就能看出「件」进不了重量组。
  const memberCandidates = useMemo(
    () => (editing && editing !== 'new' ? linkableUnits(units, editing.dimension) : []),
    [units, editing],
  );

  if (!readable) {
    return (
      <div className="space-y-4">
        <h1 className="text-h-20 font-semibold tracking-tight">计量单位</h1>
        <SectionCard title="没有查看权限">
          <p className="text-muted-foreground text-sm">
            当前角色没有商品主数据的查看权限，无法读取单位字典与单位组。
          </p>
        </SectionCard>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <Link
          href="/products/sku/units"
          className="text-muted-foreground hover:text-foreground inline-flex items-center gap-1 text-sm"
        >
          <ArrowLeft className="h-4 w-4" />
          返回 SKU 单位与换算
        </Link>
        <h1 className="text-h-20 font-semibold tracking-tight">计量单位</h1>
        <p className="text-copy-13 text-muted-foreground">
          单位字典（编码 / 量纲 / 数量精度 / 采购·销售·存储可用性）与单位组：同一量纲的一组单位
          之间可换算，跨组与跨量纲不允许。组内换算与 SKU 换算是两件事——后者服务单据生成与冻结快照。
        </p>
      </header>

      <section className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard
          label="单位总数"
          value={overview?.unitCount ?? 0}
          icon={<Scale className="h-4 w-4" />}
        />
        <StatCard
          label="单位组"
          value={overview?.groupCount ?? 0}
          delta={overview?.enabledGroupCount ?? 0}
          comparisonLabel="启用中"
          icon={<Layers className="h-4 w-4" />}
        />
        <StatCard
          label="未归组单位"
          value={overview?.ungroupedUnitCount ?? 0}
          icon={<TriangleAlert className="h-4 w-4" />}
          polarity={overview && overview.ungroupedUnitCount > 0 ? 'negative' : 'positive'}
        />
        <StatCard
          label="缺生效版本的组"
          value={overview?.incompleteGroupCount ?? 0}
          icon={<TriangleAlert className="h-4 w-4" />}
          polarity={overview && overview.incompleteGroupCount > 0 ? 'negative' : 'positive'}
        />
      </section>

      {overview && overview.ungroupedUnitCount > 0 && (
        <p className="text-copy-13 text-muted-foreground">
          {overview.ungroupedUnitCount} 个单位不属于任何单位组：它们能配 SKU 侧换算，但组级换算查询
          查不到它们——请把相关单位放进同量纲的单位组。
        </p>
      )}
      {overview?.note && <p className="text-copy-13 text-muted-foreground">{overview.note}</p>}

      {error && (
        <SectionCard title="加载失败">
          <div className="text-destructive space-y-2 text-sm">
            <p>{error}</p>
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              重试
            </Button>
          </div>
        </SectionCard>
      )}

      {actionError && (
        <SectionCard title="操作未完成">
          <div className="text-destructive space-y-2 text-sm">
            <p>{actionError}</p>
            <Button variant="outline" size="sm" onClick={() => setActionError(null)}>
              知道了
            </Button>
          </div>
        </SectionCard>
      )}
      {notice && !actionError && (
        <p className="text-copy-13 text-muted-foreground" role="status">
          {notice}
        </p>
      )}

      {/* ---------------------------------------------------------- 单位字典 */}
      <SectionCard
        title="单位字典"
        description="编码 / 名称 / 量纲 / 数量精度 / 采购·销售·存储可用性 / 启停。这一块的写入所有权是 3.1.2.4，本模块只读它——所以编码与量纲建后不可改。"
        extra={
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" onClick={() => void load()}>
              <RefreshCw className="h-4 w-4" />
              刷新
            </Button>
            {unitWritable && (
              <Button
                size="sm"
                onClick={() =>
                  setUnitEditing({
                    id: null,
                    draft: {
                      code: '',
                      name: '',
                      dimension: 'WEIGHT',
                      scale: 0,
                      allowPurchase: true,
                      allowSale: true,
                      allowStorage: true,
                      enabled: true,
                      version: 0,
                    },
                  })
                }
              >
                <Plus className="h-4 w-4" />
                新建单位
              </Button>
            )}
          </div>
        }
      >
        {loading ? (
          <p className="text-muted-foreground text-sm">正在读取单位字典…</p>
        ) : units.length === 0 ? (
          <EmptyState
            title="单位字典是空的"
            description="没有任何单位时，单位组无从建立（组内成员必须是已登记的单位）。先新建至少一个单位，例如「克」。"
            action={
              unitWritable ? (
                <Button
                  size="sm"
                  onClick={() => setUnitEditing({ id: null, draft: { code: '', name: '' } })}
                >
                  新建第一个单位
                </Button>
              ) : undefined
            }
          />
        ) : (
          <div className="space-y-2">
            <table className="w-full text-sm">
              <thead className="text-muted-foreground text-left">
                <tr>
                  <th className="py-2">单位</th>
                  <th className="py-2">量纲</th>
                  <th className="py-2">精度</th>
                  <th className="py-2">可用于</th>
                  <th className="py-2">状态</th>
                  <th className="py-2" />
                </tr>
              </thead>
              <tbody>
                {units.map((unit) => (
                  <tr key={unit.id} className="border-border border-t align-top">
                    <td className="py-2">
                      <div className="font-medium">{unit.name}</div>
                      <div className="text-muted-foreground text-xs">{unit.code}</div>
                    </td>
                    <td className="py-2">
                      <Badge tone="neutral">{dimensionLabel(unit.dimension)}</Badge>
                    </td>
                    <td className="py-2">{unit.scale} 位小数</td>
                    <td className="py-2">
                      <div className="flex flex-wrap gap-1">
                        {unit.allowPurchase && <Badge tone="info">采购</Badge>}
                        {unit.allowSale && <Badge tone="info">销售</Badge>}
                        {unit.allowStorage && <Badge tone="info">存储</Badge>}
                        {!unit.allowPurchase && !unit.allowSale && !unit.allowStorage && (
                          <span className="text-muted-foreground">都不适用</span>
                        )}
                      </div>
                    </td>
                    <td className="py-2">
                      <Badge tone={unit.enabled ? 'success' : 'neutral'}>
                        {unit.enabled ? '启用' : '停用'}
                      </Badge>
                    </td>
                    <td className="py-2">
                      {unitWritable && (
                        <div className="flex justify-end gap-1">
                          <Button
                            variant="ghost"
                            size="sm"
                            onClick={() =>
                              setUnitEditing({
                                id: unit.id,
                                draft: {
                                  code: unit.code,
                                  name: unit.name,
                                  dimension: unit.dimension,
                                  scale: unit.scale,
                                  allowPurchase: unit.allowPurchase,
                                  allowSale: unit.allowSale,
                                  allowStorage: unit.allowStorage,
                                  enabled: unit.enabled,
                                  version: unit.version,
                                },
                              })
                            }
                          >
                            编辑
                          </Button>
                          <Button
                            variant="ghost"
                            size="sm"
                            disabled={busy}
                            onClick={() =>
                              void run(
                                () => deleteUnit(unit.id, unit.version),
                                '单位删除失败',
                                `单位「${unit.code}」已删除`,
                              )
                            }
                          >
                            <Trash2 className="h-4 w-4" />
                          </Button>
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            <p className="text-muted-foreground text-xs">
              停用的单位不再出现在新建换算的候选里，但已配置的组内换算版本与 3.1.2.4
              的单据快照照常可读—— 停用是「不许再新建」，不是「抹掉过去」。
            </p>
          </div>
        )}
      </SectionCard>

      {/* ---------------------------------------------------------- 单位组 */}
      <SectionCard
        title="单位组"
        description="同一量纲内的一组单位，组内任意两个单位可换算。成员候选只列同量纲且启用的单位——量纲守卫不必等提交后才报错。"
        extra={
          groupWritable ? (
            <Button size="sm" onClick={() => setEditing('new')}>
              <Plus className="h-4 w-4" />
              新建单位组
            </Button>
          ) : undefined
        }
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3">
            <Field label="关键词" hint="编码 / 名称 / 基准单位 / 成员单位名">
              <input
                className={control}
                value={keyword}
                placeholder="例如：千克"
                onChange={(e) => {
                  setKeyword(e.target.value);
                  setIndex(0);
                }}
              />
            </Field>
            <Field label="量纲">
              <select
                className={control}
                value={dimension}
                onChange={(e) => {
                  setDimension(e.target.value);
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                {DIMENSIONS.map((value) => (
                  <option key={value} value={value}>
                    {DIMENSION_LABELS[value]}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="启用状态">
              <select
                className={control}
                value={enabled === null ? '' : String(enabled)}
                onChange={(e) => {
                  const value = e.target.value;
                  setEnabled(value === '' ? null : value === 'true');
                  setIndex(0);
                }}
              >
                <option value="">全部</option>
                <option value="true">启用</option>
                <option value="false">停用</option>
              </select>
            </Field>
          </div>

          {loading ? (
            <p className="text-muted-foreground text-sm">正在读取单位组…</p>
          ) : rows.length === 0 ? (
            <EmptyState
              title={keyword || dimension || enabled !== null ? '没有匹配的单位组' : '还没有单位组'}
              description={
                keyword || dimension || enabled !== null
                  ? '筛选条件下没有单位组。换个关键词，或把筛选清空看看全部。'
                  : '单位组是「同一量纲的一组单位」，比如「重量组」包含克 / 千克 / 磅。没有组就没有组级换算。'
              }
            />
          ) : (
            <div className="space-y-2">
              <table className="w-full text-sm">
                <thead className="text-muted-foreground text-left">
                  <tr>
                    <th className="py-2">单位组</th>
                    <th className="py-2">量纲</th>
                    <th className="py-2">基准单位</th>
                    <th className="py-2">成员</th>
                    <th className="py-2">换算版本</th>
                    <th className="py-2">状态</th>
                    <th className="py-2" />
                  </tr>
                </thead>
                <tbody>
                  {rows.map((row) => (
                    <tr key={row.id} className="border-border border-t align-top">
                      <td className="py-2">
                        <div className="font-medium">{row.name}</div>
                        <div className="text-muted-foreground text-xs">{row.code}</div>
                        {row.note && (
                          <div className="text-muted-foreground text-xs">{row.note}</div>
                        )}
                      </td>
                      <td className="py-2">
                        <Badge tone="neutral">{row.dimensionLabel}</Badge>
                      </td>
                      <td className="py-2">
                        {row.baseUnitCode}
                        {row.baseUnitName && (
                          <span className="text-muted-foreground">（{row.baseUnitName}）</span>
                        )}
                      </td>
                      <td className="py-2">{row.memberCount}</td>
                      <td className="py-2">
                        <div>{row.conversionVersionCount} 条</div>
                        <div className="text-muted-foreground text-xs">
                          {row.allMembersConvertible ? (
                            '每个成员都有当天生效的比例'
                          ) : (
                            <span className="text-destructive-foreground">
                              有成员缺当天生效的比例
                            </span>
                          )}
                        </div>
                      </td>
                      <td className="py-2">
                        <Badge tone={row.enabled ? 'success' : 'neutral'}>
                          {row.enabled ? '启用' : '停用'}
                        </Badge>
                      </td>
                      <td className="py-2">
                        <div className="flex justify-end gap-1">
                          <Button variant="ghost" size="sm" onClick={() => void openDetail(row.id)}>
                            详情
                          </Button>
                          {groupWritable && (
                            <>
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={busy}
                                onClick={() =>
                                  void run(
                                    () => setGroupEnabled(row.id, !row.enabled, row.version),
                                    '启停失败',
                                    `单位组「${row.code}」已${row.enabled ? '停用' : '启用'}`,
                                  )
                                }
                              >
                                {row.enabled ? '停用' : '启用'}
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                disabled={busy}
                                onClick={() =>
                                  void run(
                                    () => deleteGroup(row.id, row.version),
                                    '删除失败',
                                    '单位组已删除',
                                  )
                                }
                              >
                                <Trash2 className="h-4 w-4" />
                              </Button>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <div className="flex items-center justify-between text-sm">
                <span className="text-muted-foreground">
                  第 {index + 1} / {lastPage + 1} 页，共 {page?.total ?? 0} 个组（启用{' '}
                  {page?.enabledCount ?? 0}）
                </span>
                <div className="flex gap-2">
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={index === 0}
                    onClick={() => setIndex(Math.max(0, index - 1))}
                  >
                    上一页
                  </Button>
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={index >= lastPage}
                    onClick={() => setIndex(index + 1)}
                  >
                    下一页
                  </Button>
                </div>
              </div>
            </div>
          )}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 组详情 */}
      {detailLoading && (
        <SectionCard title="单位组详情">
          <p className="text-muted-foreground text-sm">正在读取单位组详情…</p>
        </SectionCard>
      )}
      {detailError && (
        <SectionCard title="单位组详情">
          <div className="text-destructive space-y-2 text-sm">
            <p>{detailError}</p>
            <Button variant="outline" size="sm" onClick={() => setDetail(null)}>
              关闭
            </Button>
          </div>
        </SectionCard>
      )}
      {detail && !detailLoading && (
        <GroupDetailPanel
          detail={detail}
          writable={groupWritable}
          busy={busy}
          onClose={() => setDetail(null)}
          onEdit={() => setEditing(groupDraft(detail))}
          onAddConversion={() =>
            setConversionDraft(emptyGroupConversionDraft(detail.group.baseUnitCode))
          }
          onDeleteConversion={(id) =>
            void run(
              () => deleteConversion(detail.group.id, id),
              '换算版本删除失败',
              '该组内换算版本已删除',
            )
          }
        />
      )}

      {/* ---------------------------------------------------------- 换算试算 */}
      <SectionCard
        title="换算试算"
        description="蓝图的输出「换算查询能力」。换不动时显示原因码与该做什么，而不是一句「换算失败」。"
      >
        <div className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
            <Field label="源单位">
              <select
                className={control}
                value={convert.fromUnitCode}
                onChange={(e) => setConvert({ ...convert, fromUnitCode: e.target.value })}
              >
                <option value="">请选择</option>
                {selectable.map((unit) => (
                  <option key={unit.id} value={unit.code}>
                    {unit.code}（{unit.name}·{dimensionLabel(unit.dimension)}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="目标单位">
              <select
                className={control}
                value={convert.toUnitCode}
                onChange={(e) => setConvert({ ...convert, toUnitCode: e.target.value })}
              >
                <option value="">请选择</option>
                {selectable.map((unit) => (
                  <option key={unit.id} value={unit.code}>
                    {unit.code}（{unit.name}·{dimensionLabel(unit.dimension)}）
                  </option>
                ))}
              </select>
            </Field>
            <Field label="数量">
              <input
                className={control}
                value={convert.quantity}
                onChange={(e) => setConvert({ ...convert, quantity: e.target.value })}
              />
            </Field>
            <Field label="基准日" hint="留空取今天。补录历史比例时用当时的日期。">
              <input
                className={control}
                placeholder="yyyy-MM-dd"
                value={convert.asOf}
                onChange={(e) => setConvert({ ...convert, asOf: e.target.value })}
              />
            </Field>
          </div>
          <Button size="sm" disabled={convertBusy} onClick={() => void runConvert()}>
            <Calculator className="h-4 w-4" />
            试算
          </Button>

          {convertError && <p className="text-destructive text-sm">{convertError}</p>}
          {!convertError && !convertResult && (
            <p className="text-muted-foreground text-sm">
              选好两个单位与数量后点「试算」。基准日决定用哪一版比例——查不到生效版本时会明确报错，
              不会拿最近的一版凑。
            </p>
          )}
          {convertResult && <ConvertResultPanel result={convertResult} />}
        </div>
      </SectionCard>

      {/* ---------------------------------------------------------- 编辑器 */}
      {editing && groupWritable && (
        <GroupEditor
          draft={editing === 'new' ? emptyGroupDraft() : editing}
          isNew={editing === 'new'}
          // 编辑时必须带上组 id：整体替换语义下前端要先取回当前全集再提交，
          // 而 PUT 的目标路径需要知道改的是哪个组。
          groupId={editing === 'new' ? null : (detail?.group.id ?? null)}
          candidates={memberCandidates}
          busy={busy}
          close={() => setEditing(null)}
          save={async (draft) => {
            const target = editing === 'new' ? null : (detail?.group.id ?? null);
            if (editing !== 'new' && !target) {
              setActionError('没有正在编辑的单位组，请先在列表里打开它的详情');
              return;
            }
            const ok = await run(
              () =>
                target
                  ? saveGroup(target, draftToGroupInput(draft))
                  : createGroup(draftToGroupInput(draft)),
              '保存失败',
              target ? '单位组已保存' : '单位组已创建',
            );
            if (ok) setEditing(null);
          }}
        />
      )}
      {conversionDraft && detail && groupWritable && (
        <GroupConversionEditor
          draft={conversionDraft}
          baseUnitCode={detail.group.baseUnitCode}
          members={detail.members}
          busy={busy}
          close={() => setConversionDraft(null)}
          save={async (draft) => {
            const ok = await run(
              () => addConversion(detail.group.id, draftToGroupConversionInput(draft)),
              '换算版本保存失败',
              '组内换算版本已新增',
            );
            if (ok) setConversionDraft(null);
          }}
        />
      )}
      {unitEditing && unitWritable && (
        <UnitEditor
          unit={unitEditing}
          busy={busy}
          close={() => setUnitEditing(null)}
          save={async (input) => {
            const ok = await run(
              () => {
                const body = {
                  code: input.code.trim(),
                  name: input.name.trim(),
                  dimension: input.dimension,
                  scale: input.scale,
                  allowPurchase: input.allowPurchase,
                  allowSale: input.allowSale,
                  allowStorage: input.allowStorage,
                  enabled: input.enabled,
                  version: input.version ?? 0,
                };
                return unitEditing.id ? updateUnit(unitEditing.id, body) : createUnit(body);
              },
              '单位保存失败',
              unitEditing.id ? '单位已保存' : '单位已创建',
            );
            if (ok) setUnitEditing(null);
          }}
        />
      )}
    </div>
  );
}

/** 换算结果：成功给算式，被拒给原因码 + 该做什么。 */
function ConvertResultPanel({ result }: { result: UnitGroupConvertResult }) {
  if (result.denied) {
    return (
      <div className="border-border space-y-2 rounded-md border p-3 text-sm">
        <div className="flex items-center gap-2">
          <Badge tone="danger">{denyLabel(result.denyCode)}</Badge>
          <span className="text-muted-foreground text-xs">{result.denyCode}</span>
        </div>
        <p className="text-destructive">{result.denyMessage}</p>
        <p className="text-muted-foreground text-xs">{denyAction(result.denyCode)}</p>
        {result.note && <p className="text-muted-foreground text-xs">{result.note}</p>}
      </div>
    );
  }
  return (
    <div className="border-border space-y-2 rounded-md border p-3 text-sm">
      <div className="flex items-center gap-2">
        <Badge tone="success">可换算</Badge>
        {result.groupCode && (
          <span className="text-muted-foreground text-xs">
            单位组 {result.groupCode}（{result.groupName}）
          </span>
        )}
      </div>
      <p className="text-copy-14 font-medium">
        {result.quantity} {result.fromUnitCode} = {quantityText(result.convertedQuantity)}{' '}
        {result.toUnitCode}
      </p>
      <p className="text-muted-foreground text-xs">{result.note}</p>
      <p className="text-muted-foreground text-xs">
        基准日 {result.asOf}；基准单位 {result.baseUnitCode}；源比例 {result.fromQuantityPerBase}
        ，目标比例 {result.toQuantityPerBase}
      </p>
    </div>
  );
}

/** 单位组详情：成员（含当天生效比例）+ 组内换算版本。 */
function GroupDetailPanel({
  detail,
  writable,
  busy,
  onClose,
  onEdit,
  onAddConversion,
  onDeleteConversion,
}: {
  detail: UnitGroupDetail;
  writable: boolean;
  busy: boolean;
  onClose: () => void;
  onEdit: () => void;
  onAddConversion: () => void;
  onDeleteConversion: (id: string) => void;
}) {
  const byUnit = useMemo(() => {
    const map = new Map<string, typeof detail.conversions>();
    for (const version of detail.conversions) {
      map.set(version.unitCode, [...(map.get(version.unitCode) ?? []), version]);
    }
    return map;
  }, [detail]);

  return (
    <SectionCard
      title={`单位组详情：${detail.group.name}`}
      description={`基准日 ${detail.asOf}。成员按 position 排序；每一版换算都带生效区间，当前生效的那一版单独标出。`}
      extra={
        <div className="flex items-center gap-2">
          {writable && (
            <>
              <Button variant="outline" size="sm" onClick={onEdit}>
                编辑组
              </Button>
              <Button size="sm" onClick={onAddConversion}>
                <Plus className="h-4 w-4" />
                新增换算版本
              </Button>
            </>
          )}
          <Button variant="ghost" size="sm" onClick={onClose}>
            关闭
          </Button>
        </div>
      }
    >
      <div className="space-y-4">
        <table className="w-full text-sm">
          <thead className="text-muted-foreground text-left">
            <tr>
              <th className="py-2">成员单位</th>
              <th className="py-2">当天生效比例</th>
              <th className="py-2">生效区间</th>
              <th className="py-2">历史版本</th>
              <th className="py-2" />
            </tr>
          </thead>
          <tbody>
            {detail.members.map((member) => (
              <tr key={member.unitCode} className="border-border border-t align-top">
                <td className="py-2">
                  <div className="font-medium">
                    {member.unitCode}
                    {member.unitName && (
                      <span className="text-muted-foreground">（{member.unitName}）</span>
                    )}
                  </div>
                  <div className="mt-1 flex flex-wrap gap-1">
                    {member.base && <Badge tone="info">组基础单位（比例恒为 1）</Badge>}
                    {!member.unitEnabled && <Badge tone="neutral">单位已停用</Badge>}
                  </div>
                </td>
                <td className="py-2">
                  {member.base ? (
                    <span className="text-muted-foreground">1（不占换算行）</span>
                  ) : member.currentQuantityPerBase === null ? (
                    <span className="text-destructive-foreground">没有生效版本</span>
                  ) : (
                    quantityText(member.currentQuantityPerBase, member.scale)
                  )}
                </td>
                <td className="py-2 text-xs">
                  {member.base
                    ? '—'
                    : windowText(member.currentEffectiveFrom, member.currentEffectiveTo)}
                </td>
                <td className="py-2">
                  {(byUnit.get(member.unitCode) ?? []).length === 0 ? (
                    <span className="text-muted-foreground">—</span>
                  ) : (
                    <div className="space-y-1">
                      {(byUnit.get(member.unitCode) ?? []).map((version) => (
                        <div key={version.id} className="text-xs">
                          <div className="flex items-center gap-1">
                            {version.current && <Badge tone="success">当前生效</Badge>}
                            <span>{quantityText(version.quantityPerBase)}</span>
                            <span className="text-muted-foreground">
                              {windowText(version.effectiveFrom, version.effectiveTo)}
                            </span>
                          </div>
                          {version.note && (
                            <div className="text-muted-foreground">{version.note}</div>
                          )}
                          {writable && version.effectiveTo !== null && (
                            <Button
                              variant="ghost"
                              size="sm"
                              disabled={busy}
                              onClick={() => onDeleteConversion(version.id)}
                            >
                              <Trash2 className="h-4 w-4" />
                              删除该历史版本
                            </Button>
                          )}
                        </div>
                      ))}
                    </div>
                  )}
                </td>
                <td className="py-2" />
              </tr>
            ))}
          </tbody>
        </table>
        <p className="text-muted-foreground text-xs">
          已闭合的历史版本可以删除；当前仍开放生效的那一版不能删——删掉等于静默把这个单位变成不可换算，
          正确做法是先闭合它的生效区间，再为下一个区间配一版。
        </p>
      </div>
    </SectionCard>
  );
}

/** 单位组编辑器：编码 / 名称 / 量纲 / 基准单位 / 成员多选 / 备注 / 启停。 */
function GroupEditor({
  draft: initial,
  isNew,
  groupId,
  candidates,
  busy,
  close,
  save,
}: {
  draft: GroupDraft;
  isNew: boolean;
  groupId: string | null;
  candidates: UnitOfMeasure[];
  busy: boolean;
  close: () => void;
  save: (draft: GroupDraft) => Promise<void>;
}) {
  const [draft, setDraft] = useState<GroupDraft>(initial);
  const problems = validateGroup(draft, isNew);

  function toggleUnit(unitCode: string) {
    setDraft((current) => {
      const exists = current.members.some((member) => member.unitCode === unitCode);
      const members = exists
        ? current.members.filter((member) => member.unitCode !== unitCode)
        : [...current.members, { unitCode, position: current.members.length }];
      return {
        ...current,
        members: members.map((member, index) => ({ ...member, position: index })),
        baseUnitCode: exists && current.baseUnitCode === unitCode ? '' : current.baseUnitCode,
      };
    });
  }

  return (
    <Editor
      title={isNew ? '新建单位组' : '编辑单位组'}
      description={
        isNew
          ? '同一量纲内的一组单位。基准单位必须在成员里，它不占换算行（比例恒为 1）。'
          : '编码与量纲建后不可改：下游按编码引用换算关系，改量纲会让已配置的换算突然不合法。成员是整体替换。'
      }
      close={close}
    >
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="编码" hint={isNew ? '允许中文，如「重量组」。租户内唯一。' : '建后不可改'}>
            <input
              className={control}
              value={draft.code}
              disabled={!isNew}
              onChange={(e) => setDraft({ ...draft, code: e.target.value })}
            />
          </Field>
          <Field label="名称">
            <input
              className={control}
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
          </Field>
          <Field label="量纲" hint="决定成员候选：只有同量纲的单位能进这个组。">
            <select
              className={control}
              value={draft.dimension}
              disabled={!isNew}
              onChange={(e) => setDraft({ ...draft, dimension: e.target.value, members: [] })}
            >
              {DIMENSIONS.map((value) => (
                <option key={value} value={value}>
                  {DIMENSION_LABELS[value]}
                </option>
              ))}
            </select>
          </Field>
          <Field label="备注">
            <input
              className={control}
              value={draft.note}
              onChange={(e) => setDraft({ ...draft, note: e.target.value })}
            />
          </Field>
        </div>

        <Field label="成员单位" hint="已选成员决定基准单位可选项；基准单位必须在成员里。">
          <div className="border-border max-h-56 space-y-1 overflow-y-auto rounded-md border p-2">
            {candidates.length === 0 ? (
              <p className="text-muted-foreground text-sm">
                没有同量纲且启用的单位可加入。请先到单位字典新建，或换个量纲。
              </p>
            ) : (
              candidates.map((unit) => (
                <label key={unit.id} className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={draft.members.some((member) => member.unitCode === unit.code)}
                    onChange={() => toggleUnit(unit.code)}
                  />
                  <span>
                    {unit.code}（{unit.name}）
                  </span>
                </label>
              ))
            )}
            {/* 已选但被停用 / 量纲不符的单位仍要显示，否则编辑时它们会凭空消失。 */}
            {draft.members
              .filter((member) => !candidates.some((unit) => unit.code === member.unitCode))
              .map((member) => (
                <label key={member.unitCode} className="flex items-center gap-2 text-sm">
                  <input type="checkbox" checked onChange={() => toggleUnit(member.unitCode)} />
                  <span className="text-muted-foreground">
                    {member.unitCode}（已停用或不属于本量纲）
                  </span>
                </label>
              ))}
          </div>
        </Field>

        <Field label="组基础单位" hint="组内换算比例都相对它定义；它的比例恒为 1，不占换算行。">
          <select
            className={control}
            value={draft.baseUnitCode}
            onChange={(e) => setDraft({ ...draft, baseUnitCode: e.target.value })}
          >
            <option value="">请选择</option>
            {draft.members.map((member) => (
              <option key={member.unitCode} value={member.unitCode}>
                {member.unitCode}
              </option>
            ))}
          </select>
        </Field>

        <label className="flex items-center gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.enabled}
            onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
          />
          启用（停用只挡住新建换算版本，历史版本照常可读）
        </label>

        {problems.length > 0 && (
          <ul className="text-destructive space-y-1 text-sm">
            {problems.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={close}>
            取消
          </Button>
          <Button
            size="sm"
            disabled={busy || problems.length > 0 || (isNew ? false : !groupId)}
            onClick={() => void save(draft)}
          >
            <Save className="h-4 w-4" />
            保存
          </Button>
        </div>
      </div>
    </Editor>
  );
}

/** 组内换算版本编辑器。 */
function GroupConversionEditor({
  draft: initial,
  baseUnitCode,
  members,
  busy,
  close,
  save,
}: {
  draft: GroupConversionDraft;
  baseUnitCode: string;
  members: UnitGroupDetail['members'];
  busy: boolean;
  close: () => void;
  save: (draft: GroupConversionDraft) => Promise<void>;
}) {
  const [draft, setDraft] = useState(initial);
  const problems = validateGroupConversion(draft, baseUnitCode);

  return (
    <Editor
      title="新增组内换算版本"
      description="比例 = 1 个该单位等于多少个组基础单位。区间不重叠：同一组的同一单位，同一时刻只能有一个版本生效。"
      close={close}
    >
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="单位" hint={`组基础单位「${baseUnitCode}」比例恒为 1，不需要版本。`}>
            <select
              className={control}
              value={draft.unitCode}
              onChange={(e) => setDraft({ ...draft, unitCode: e.target.value })}
            >
              <option value="">请选择</option>
              {members
                .filter((member) => !member.base)
                .map((member) => (
                  <option key={member.unitCode} value={member.unitCode}>
                    {member.unitCode}
                    {member.unitName ? `（${member.unitName}）` : ''}
                  </option>
                ))}
            </select>
          </Field>
          <Field
            label="比例"
            hint="1 个该单位 = 多少个组基础单位（组基础单位为克时，1 千克 → 1000）"
          >
            <input
              className={control}
              value={draft.quantityPerBase}
              placeholder="1000"
              onChange={(e) => setDraft({ ...draft, quantityPerBase: e.target.value })}
            />
          </Field>
          <Field label="生效起始日">
            <input
              className={control}
              value={draft.effectiveFrom}
              onChange={(e) => setDraft({ ...draft, effectiveFrom: e.target.value })}
            />
          </Field>
          <Field label="生效截止日" hint="留空表示当前仍生效。">
            <input
              className={control}
              placeholder="yyyy-MM-dd"
              value={draft.effectiveTo}
              onChange={(e) => setDraft({ ...draft, effectiveTo: e.target.value })}
            />
          </Field>
        </div>
        <Field label="备注">
          <input
            className={control}
            value={draft.note}
            onChange={(e) => setDraft({ ...draft, note: e.target.value })}
          />
        </Field>

        {problems.length > 0 && (
          <ul className="text-destructive space-y-1 text-sm">
            {problems.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={close}>
            取消
          </Button>
          <Button size="sm" disabled={busy || problems.length > 0} onClick={() => void save(draft)}>
            <Save className="h-4 w-4" />
            保存
          </Button>
        </div>
      </div>
    </Editor>
  );
}

/** 单位字典编辑器（写入走 3.1.2.4 的端点，编码与量纲建后不可改）。 */
function UnitEditor({
  unit,
  busy,
  close,
  save,
}: {
  unit: { id: string | null; draft: Partial<UnitOfMeasure> & { code: string; name: string } };
  busy: boolean;
  close: () => void;
  save: (input: {
    code: string;
    name: string;
    dimension: string;
    scale: number;
    allowPurchase: boolean;
    allowSale: boolean;
    allowStorage: boolean;
    enabled: boolean;
    version?: number;
  }) => Promise<void>;
}) {
  const [draft, setDraft] = useState({
    code: unit.draft.code,
    name: unit.draft.name,
    dimension: unit.draft.dimension ?? 'WEIGHT',
    scale: String(unit.draft.scale ?? 0),
    allowPurchase: unit.draft.allowPurchase ?? true,
    allowSale: unit.draft.allowSale ?? true,
    allowStorage: unit.draft.allowStorage ?? true,
    enabled: unit.draft.enabled ?? true,
    version: unit.draft.version ?? 0,
  });
  const isNew = unit.id === null;
  const problems: string[] = [];
  if (
    isNew &&
    !/^[\p{Script=Han}A-Za-z0-9][\p{Script=Han}A-Za-z0-9_-]{0,31}$/u.test(draft.code.trim())
  )
    problems.push('单位编码只能使用中文、英文字母、数字、下划线或短横线，长度 1~32 位');
  if (!draft.name.trim()) problems.push('单位名称不能为空');
  const scaleNumber = Number(draft.scale);
  if (!Number.isInteger(scaleNumber) || scaleNumber < 0 || scaleNumber > 6)
    problems.push('数量精度需在 0~6 位小数之间');

  return (
    <Editor
      title={isNew ? '新建单位' : `编辑单位：${unit.draft.code}`}
      description={
        isNew
          ? '单位码允许中文（历史数据里单位码本来就是「件」「箱」）。量纲决定了它能和哪些单位换算。'
          : '编码与量纲建后不可改：换算行与已冻结快照按编码引用它，改量纲会让既有换算突然不通过。'
      }
      close={close}
    >
      <div className="space-y-4">
        <div className="grid gap-3 sm:grid-cols-2">
          <Field label="编码" hint={isNew ? '如 件 / 千克' : '建后不可改'}>
            <input
              className={control}
              value={draft.code}
              disabled={!isNew}
              onChange={(e) => setDraft({ ...draft, code: e.target.value })}
            />
          </Field>
          <Field label="名称">
            <input
              className={control}
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
            />
          </Field>
          <Field label="量纲" {...(isNew ? {} : { hint: '建后不可改' })}>
            <select
              className={control}
              value={draft.dimension}
              disabled={!isNew}
              onChange={(e) => setDraft({ ...draft, dimension: e.target.value })}
            >
              {DIMENSIONS.map((value) => (
                <option key={value} value={value}>
                  {DIMENSION_LABELS[value]}
                </option>
              ))}
            </select>
          </Field>
          <Field label="数量精度" hint="该单位数量允许的小数位，0~6">
            <input
              className={control}
              value={draft.scale}
              onChange={(e) => setDraft({ ...draft, scale: e.target.value })}
            />
          </Field>
        </div>

        <div className="space-y-2">
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={draft.allowPurchase}
              onChange={(e) => setDraft({ ...draft, allowPurchase: e.target.checked })}
            />
            可用于采购
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={draft.allowSale}
              onChange={(e) => setDraft({ ...draft, allowSale: e.target.checked })}
            />
            可用于销售
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={draft.allowStorage}
              onChange={(e) => setDraft({ ...draft, allowStorage: e.target.checked })}
            />
            可用于存储
          </label>
          <label className="flex items-center gap-2 text-sm">
            <input
              type="checkbox"
              checked={draft.enabled}
              onChange={(e) => setDraft({ ...draft, enabled: e.target.checked })}
            />
            启用
          </label>
        </div>

        {problems.length > 0 && (
          <ul className="text-destructive space-y-1 text-sm">
            {problems.map((problem) => (
              <li key={problem}>{problem}</li>
            ))}
          </ul>
        )}

        <div className="flex justify-end gap-2">
          <Button variant="outline" size="sm" onClick={close}>
            取消
          </Button>
          <Button
            size="sm"
            disabled={busy || problems.length > 0}
            onClick={() =>
              void save({
                code: draft.code,
                name: draft.name,
                dimension: draft.dimension,
                scale: scaleNumber,
                allowPurchase: draft.allowPurchase,
                allowSale: draft.allowSale,
                allowStorage: draft.allowStorage,
                enabled: draft.enabled,
                version: draft.version,
              })
            }
          >
            <Save className="h-4 w-4" />
            保存
          </Button>
        </div>
      </div>
    </Editor>
  );
}
