'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { Badge, Button, EmptyState, Input, PageHeader, SectionCard } from '@starcon/ui';
import { Boxes, Loader2, RefreshCw, TriangleAlert } from 'lucide-react';
import {
  activeFilterCount,
  addComponent,
  applyFilterChange,
  effectiveStateLabel,
  effectiveWindow,
  emptyFilters,
  fetchBinding,
  fetchBindings,
  fetchCapabilities,
  fetchFilterOptions,
  fetchSellableCheck,
  formatMoment,
  formatQuantity,
  hasNextPage,
  hasPrevPage,
  pageSummary,
  quantityInvalidReason,
  quantityStep,
  removeComponent,
  resetFilters,
  saveBinding,
  sellableBanner,
  sellableText,
  sortLabel,
  unavailableDimensions,
  type BundleFilters,
  type ListingBundleBindingDetail,
  type ListingBundleBindingPage,
  type ListingBundleComponentCapabilities,
  type ListingBundleComponentSellable,
  type ListingBundleFilterOptions,
  type ListingBundleSellableCheck,
} from './bundle-component-model';

/**
 * 4.1.2.3 套装组件映射 工作区。
 *
 * <p>蓝图：「当平台商品对应内部虚拟套装时，维护组件 SKU 与数量的映射，支持平台变体与不同套装版本对应，并校验组件 SKU 在目标仓库可售。输入：组件映射维护；套装版本选择。输出：<b>组件映射记录 + 可售性校验结果</b>。关系：上游 = 3.5.1 套装版本、4.1.2.1；下游 = 07 库存（按组件预占）。<b>组件数量必须大于 0</b>，缺任一组件时整个套装不可售。」
 *
 * 🔴 这个页面上最容易做丢的四件事，这里都挡住了：
 *
 * <ol>
 *   <li><b>整套能不能卖是一个整体结论</b>：可售性校验的结果用一条置顶横幅给出来
 *       （{@link sellableBanner}），而不是让用户盯着三行「可售」自己去数。蓝图那句
 *       「缺任一组件时整个套装不可售」就是这句话存在的理由。</li>
 *   <li><b>「查不到」进错误态，绝不进空态</b>：可售量来自 07 库存，读不到时后端返回 503，
 *       这里显示红色错误条。显示成「没有库存记录」等于把「我们查不到」说成「确实没有」——
 *       而那正是超卖的第一步。</li>
 *   <li><b>组件数量必须大于 0，在提交前就说清</b>：输入框下方实时给出与后端同一句的原因，
 *       并在非法时禁用提交。蓝图加粗句不是提示，是硬约束。</li>
 *   <li><b>未交付段如实显示，且不画空列表</b>：构成冻结快照与按组件预占两项
 *       {@code available=false}，这里逐条列出归属模块与影响。</li>
 * </ol>
 */
export function BundleComponentWorkspace() {
  const [caps, setCaps] = useState<ListingBundleComponentCapabilities | null>(null);
  const [options, setOptions] = useState<ListingBundleFilterOptions | null>(null);
  const [filters, setFilters] = useState<BundleFilters>(emptyFilters());
  const [page, setPage] = useState<ListingBundleBindingPage | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // 选中的绑定：逐组件明细 + 可售性校验
  const [detail, setDetail] = useState<ListingBundleBindingDetail | null>(null);
  const [check, setCheck] = useState<ListingBundleSellableCheck | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [checkError, setCheckError] = useState<string | null>(null);
  const [checkLoading, setCheckLoading] = useState(false);

  // 写入表单
  const [warehouseId, setWarehouseId] = useState('');
  const [newSku, setNewSku] = useState('');
  const [newQty, setNewQty] = useState('1');
  const [newRole, setNewRole] = useState('');
  const [writeError, setWriteError] = useState<string | null>(null);
  const [writeNotice, setWriteNotice] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setPage(await fetchBindings(filters));
    } catch (e) {
      setError(e instanceof Error ? e.message : '套装版本绑定列表加载失败');
    } finally {
      setLoading(false);
    }
  }, [filters]);

  useEffect(() => {
    void load();
  }, [load]);

  useEffect(() => {
    void (async () => {
      try {
        setCaps(await fetchCapabilities());
        setOptions(await fetchFilterOptions());
      } catch {
        // 能力声明读不到不该让整个页面瘫掉：列表才是主内容
        setCaps(null);
      }
    })();
  }, []);

  const openBinding = useCallback(async (id: string) => {
    setDetailLoading(true);
    setDetailError(null);
    setWriteNotice(null);
    setWriteError(null);
    try {
      setDetail(await fetchBinding(id));
      // 换绑定时**清掉**上一条的可售性结论：留着旧结论就是最危险的一种错误显示
      setCheck(null);
      setCheckError(null);
    } catch (e) {
      setDetail(null);
      setCheck(null);
      setDetailError(e instanceof Error ? e.message : '绑定详情加载失败');
    } finally {
      setDetailLoading(false);
    }
  }, []);

  const runCheck = useCallback(
    async (id: string, warehouse: string) => {
      const trimmed = warehouse.trim();
      if (!trimmed) {
        setCheckError('必须填写目标仓库：可售量按仓库计算，不指定仓库就没有唯一的答案');
        setCheck(null);
        return;
      }
      setCheckLoading(true);
      setCheckError(null);
      try {
        setCheck(await fetchSellableCheck(id, trimmed));
      } catch (e) {
        // 🔴 查不到就是查不到：不保留上一次的结论，也不显示「全部可售」
        setCheck(null);
        setCheckError(e instanceof Error ? e.message : '可售性校验失败');
      } finally {
        setCheckLoading(false);
      }
    },
    [],
  );

  const missing = useMemo(() => unavailableDimensions(caps), [caps]);
  const banner = useMemo(() => sellableBanner(check), [check]);
  const qtyReason = useMemo(() => quantityInvalidReason(newQty), [newQty]);

  async function submitComponent() {
    if (!detail) return;
    if (qtyReason) {
      setWriteError(qtyReason);
      return;
    }
    const sku = newSku.trim();
    if (!sku) {
      setWriteError('必须填写组件 SKU：它必须是所选套装版本在商品中心登记过的组件');
      return;
    }
    setSubmitting(true);
    setWriteError(null);
    setWriteNotice(null);
    try {
      await addComponent({
        bindingId: detail.binding.id,
        componentSkuId: sku,
        quantity: Number(newQty),
        role: newRole.trim(),
        note: '',
      });
      setNewSku('');
      setNewRole('');
      setNotice('组件已登记');
      setDetail(await fetchBinding(detail.binding.id));
    } catch (e) {
      setWriteError(e instanceof Error ? e.message : '组件登记失败');
    } finally {
      setSubmitting(false);
    }
  }

  function setNotice(text: string) {
    setWriteNotice(text);
    setWriteError(null);
  }

  async function dropComponent(id: string) {
    if (!detail) return;
    setSubmitting(true);
    setWriteError(null);
    setWriteNotice(null);
    try {
      const removed = await removeComponent(id);
      setWriteNotice(removed.note);
      setDetail(await fetchBinding(detail.binding.id));
      // 组件集合变了，上一次的可售性结论立刻作废：留着会让人按旧结论继续卖
      setCheck(null);
      setCheckError(null);
    } catch (e) {
      setWriteError(e instanceof Error ? e.message : '删除组件失败');
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        title="套装组件映射"
        description="平台变体对应内部套装时，逐条维护组件 SKU 与数量，并校验它们在目标仓库的可售性。组件数量必须大于 0；缺任一组件时整个套装不可售。"
        actions={
          <Button variant="outline" size="sm" onClick={() => void load()} disabled={loading}>
            {loading ? (
              <Loader2 className="h-4 w-4 animate-spin" />
            ) : (
              <RefreshCw className="h-4 w-4" />
            )}
            刷新
          </Button>
        }
      />

      {error && (
        <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
          <TriangleAlert className="h-4 w-4 shrink-0" />
          <span className="text-copy-13">{error}</span>
          <Button variant="outline" size="sm" onClick={() => void load()} className="ml-auto">
            重试
          </Button>
        </div>
      )}

      {/* 🔴 未交付段明账：逐条列出归属模块与影响，不画 0 也不画占位 */}
      {missing.length > 0 && (
        <SectionCard
          title="本模块未接入的能力"
          description="如实列出而不是填 0 或留空：0 会被读成「这件事已经做完了」。"
        >
          <ul className="space-y-2">
            {missing.map((d) => (
              <li key={d.dimension} className="text-copy-13 flex flex-wrap items-baseline gap-2">
                <Badge tone="warning">{d.dimension}</Badge>
                <span className="text-foreground">{d.description}</span>
                <span className="text-muted-foreground">归 {d.ownerModule}</span>
                <span className="text-muted-foreground">{d.impact}</span>
              </li>
            ))}
          </ul>
          {caps?.gapNote && (
            <p className="text-copy-13 text-muted-foreground mt-3 border-t pt-3">{caps.gapNote}</p>
          )}
        </SectionCard>
      )}

      {/* 筛选 */}
      <SectionCard
        title="筛选"
        description="筛选与分页都在服务端：前端只翻译 query string，不在浏览器里过滤已分页的数据。"
      >
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Field label="映射 ID">
            <Input
              value={filters.mappingId}
              onChange={(e) => setFilters(applyFilterChange(filters, { mappingId: e.target.value }))}
              placeholder="4.1.2.1 套装映射的 UUID"
            />
          </Field>
          <Field label="平台商品 ID">
            <Input
              value={filters.search}
              onChange={(e) => setFilters(applyFilterChange(filters, { search: e.target.value }))}
              placeholder="商品 ID / 变体 / 店铺 / 变更原因"
            />
          </Field>
          <Field label="套装版本 ID">
            <Input
              value={filters.bundleVersionId}
              onChange={(e) =>
                setFilters(applyFilterChange(filters, { bundleVersionId: e.target.value }))
              }
              placeholder="3.5.1 套装版本的 UUID"
            />
          </Field>
          <Field label="生效状态">
            <select
              className="border-input h-9 w-full rounded-md border px-3 text-copy-13"
              value={filters.effectiveState}
              onChange={(e) =>
                setFilters(applyFilterChange(filters, { effectiveState: e.target.value }))
              }
            >
              <option value="">全部</option>
              {(options?.effectiveStates ?? ['CURRENT', 'RETIRED']).map((s) => (
                <option key={s} value={s}>
                  {effectiveStateLabel(s)}
                </option>
              ))}
            </select>
          </Field>
        </div>
        <p className="text-copy-13 text-muted-foreground mt-3">
          已启用 {activeFilterCount(filters)} 个筛选条件。
          <button
            type="button"
            className="ml-3 underline underline-offset-2"
            onClick={() => setFilters(resetFilters(filters))}
          >
            清空
          </button>
        </p>
      </SectionCard>

      {/* 套装版本绑定列表 */}
      <SectionCard
        title="套装版本选择"
        description="平台变体的套装映射当前指向哪个内部套装版本。换版生成新的一行并退役旧的，不就地改。"
        extra={<span className="text-copy-13 text-muted-foreground">{pageSummary(page)}</span>}
      >
        {loading && !page ? (
          <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
            <Loader2 className="h-4 w-4 animate-spin" />
            加载套装版本绑定…
          </div>
        ) : page && page.items.length === 0 ? (
          <EmptyState
            title="还没有套装版本绑定"
            description="先在 4.1.2.1 把平台变体映射成一对多（套装），再回到这里选择内部套装版本并登记组件。"
          />
        ) : (
          <div className="overflow-x-auto">
            <table className="w-full text-left text-copy-13">
              <thead className="text-muted-foreground border-b">
                <tr>
                  <th className="px-3 py-2">状态</th>
                  <th className="px-3 py-2">平台商品 / 变体</th>
                  <th className="px-3 py-2">套装版本</th>
                  <th className="px-3 py-2">生效区间</th>
                  <th className="px-3 py-2">组件</th>
                  <th className="px-3 py-2">变更原因</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {page?.items.map((b) => (
                  <tr key={b.id} className="border-b last:border-0">
                    <td className="px-3 py-2">
                      <Badge tone={b.current ? 'success' : 'neutral'}>
                        {b.current ? '当前生效' : '已退役'}
                      </Badge>
                    </td>
                    <td className="px-3 py-2">
                      <div>{b.externalItemId || '—'}</div>
                      <div className="text-muted-foreground">
                        {b.externalVariantId || '—'} · {b.shopCode || '—'}
                      </div>
                    </td>
                    <td className="px-3 py-2 font-mono text-xs">{b.bundleVersionId}</td>
                    <td className="px-3 py-2">{effectiveWindow(b)}</td>
                    <td className="px-3 py-2">
                      <div>
                        {b.componentCount} 个 · 每件共 {formatQuantity(b.totalQuantity)}
                      </div>
                      {b.componentCount === 0 && (
                        <div className="text-destructive">没有组件 ⇒ 整套不可售</div>
                      )}
                    </td>
                    <td className="px-3 py-2">{b.changeReason || '—'}</td>
                    <td className="px-3 py-2">
                      <Button variant="outline" size="sm" onClick={() => void openBinding(b.id)}>
                        组件明细
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}

        {(hasPrevPage(page) || hasNextPage(page)) && (
          <div className="mt-3 flex items-center justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              disabled={!hasPrevPage(page)}
              onClick={() => setFilters({ ...filters, page: filters.page - 1 })}
            >
              上一页
            </Button>
            <span className="text-copy-13 text-muted-foreground">
              第 {(filters.page + 1).toString()} 页 · {sortLabel(filters.sort)}
            </span>
            <Button
              variant="outline"
              size="sm"
              disabled={!hasNextPage(page)}
              onClick={() => setFilters({ ...filters, page: filters.page + 1 })}
            >
              下一页
            </Button>
          </div>
        )}
      </SectionCard>

      {/* 组件映射记录 + 可售性校验 */}
      <SectionCard
        title="组件映射记录"
        description="逐条维护组件 SKU 与数量。构成的真值在商品中心，这里记的是刊登侧的扣减口径。"
      >
        {detailLoading ? (
          <div className="text-copy-13 text-muted-foreground flex items-center gap-2">
            <Loader2 className="h-4 w-4 animate-spin" />
            加载绑定详情…
          </div>
        ) : detailError ? (
          <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
            <TriangleAlert className="h-4 w-4 shrink-0" />
            <span className="text-copy-13">{detailError}</span>
          </div>
        ) : !detail ? (
          <EmptyState
            title="尚未选择一个套装版本绑定"
            description="在上方列表点「组件明细」，即可查看并维护这一版的组件。"
          />
        ) : (
          <div className="space-y-4">
            <div className="text-copy-13 text-muted-foreground flex flex-wrap items-center gap-2">
              <Badge tone={detail.binding.current ? 'success' : 'neutral'}>
                {detail.binding.current ? '当前生效' : '已退役'}
              </Badge>
              <span className="font-mono text-xs">{detail.binding.bundleVersionId}</span>
              <span>登记于 {formatMoment(detail.binding.createdAt)}</span>
            </div>

            {/* 组件维护表单：数量非法时禁用提交，并把原因写在输入框下方 */}
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-4">
              <Field label="组件 SKU（商品中心该套装版本的组件）">
                <Input
                  value={newSku}
                  onChange={(e) => setNewSku(e.target.value)}
                  placeholder="03 的 SKU UUID"
                  disabled={!detail.binding.current}
                />
              </Field>
              <Field label="数量（每卖 1 件平台套装扣几件）">
                <Input
                  value={newQty}
                  inputMode="decimal"
                  onChange={(e) => setNewQty(e.target.value)}
                  step={quantityStep()}
                  disabled={!detail.binding.current}
                />
                {qtyReason && (
                  <span className="text-copy-13 text-destructive">{qtyReason}</span>
                )}
              </Field>
              <Field label="角色">
                <Input
                  value={newRole}
                  onChange={(e) => setNewRole(e.target.value)}
                  placeholder="主机 / 配件 / 赠品…"
                  disabled={!detail.binding.current}
                />
              </Field>
              <div className="flex items-end">
                <Button
                  size="sm"
                  onClick={() => void submitComponent()}
                  disabled={submitting || !detail.binding.current || Boolean(qtyReason)}
                >
                  <Boxes className="h-4 w-4" />
                  登记组件
                </Button>
              </div>
            </div>
            {!detail.binding.current && (
              <p className="text-copy-13 text-muted-foreground">
                这一版已退役：它的组件是历史，只读。要改组件请换到当前生效的版本上改。
              </p>
            )}

            {writeError && (
              <div className="border-destructive-soft bg-destructive-soft text-destructive flex items-center gap-2 rounded-lg border px-4 py-3">
                <TriangleAlert className="h-4 w-4 shrink-0" />
                <span className="text-copy-13">{writeError}</span>
              </div>
            )}
            {writeNotice && (
              <p className="text-copy-13 text-muted-foreground">{writeNotice}</p>
            )}

            {detail.components.length === 0 ? (
              <EmptyState
                title="这一版还没有登记任何组件"
                description="按规则，缺任一组件的套装不可售 —— 没有组件的套装同样不可售。"
              />
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left text-copy-13">
                  <thead className="text-muted-foreground border-b">
                    <tr>
                      <th className="px-3 py-2">组件 SKU</th>
                      <th className="px-3 py-2">数量</th>
                      <th className="px-3 py-2">角色</th>
                      <th className="px-3 py-2">更新时刻</th>
                      <th className="px-3 py-2" />
                    </tr>
                  </thead>
                  <tbody>
                    {detail.components.map((c) => (
                      <tr key={c.id} className="border-b last:border-0">
                        <td className="px-3 py-2 font-mono text-xs">{c.componentSkuId}</td>
                        <td className="px-3 py-2">{formatQuantity(c.quantity)}</td>
                        <td className="px-3 py-2">{c.role || '—'}</td>
                        <td className="px-3 py-2">{formatMoment(c.updatedAt)}</td>
                        <td className="px-3 py-2">
                          {detail.binding.current && (
                            <Button
                              variant="outline"
                              size="sm"
                              disabled={submitting}
                              onClick={() => void dropComponent(c.id)}
                            >
                              删除
                            </Button>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}

            <p className="text-copy-13 text-muted-foreground">{detail.note}</p>

            {/* 可售性校验：目标仓库必填，读不到进错误态而不是空态 */}
            <div className="border-t pt-4">
              <div className="flex flex-wrap items-end gap-3">
                <Field label="目标仓库" className="w-full sm:w-80">
                  <Input
                    value={warehouseId}
                    onChange={(e) => setWarehouseId(e.target.value)}
                    placeholder="07 库存的仓库 UUID"
                  />
                </Field>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={checkLoading}
                  onClick={() => void runCheck(detail.binding.id, warehouseId)}
                >
                  {checkLoading ? (
                    <Loader2 className="h-4 w-4 animate-spin" />
                  ) : (
                    <Boxes className="h-4 w-4" />
                  )}
                  校验可售性
                </Button>
              </div>

              {checkError && (
                <div className="border-destructive-soft bg-destructive-soft text-destructive mt-3 flex items-center gap-2 rounded-lg border px-4 py-3">
                  <TriangleAlert className="h-4 w-4 shrink-0" />
                  <span className="text-copy-13">
                    {checkError}（可售量来自 07 库存；这里显示不出来时请不要按「可售」处理）
                  </span>
                </div>
              )}

              <div
                className={`mt-3 rounded-lg border px-4 py-3 ${
                  banner.tone === 'ok'
                    ? 'border-success-soft bg-success-soft text-success'
                    : banner.tone === 'bad'
                      ? 'border-destructive-soft bg-destructive-soft text-destructive'
                      : 'text-muted-foreground border'
                }`}
              >
                <p className="text-copy-13">
                  <strong>整套可售性：</strong>
                  {banner.text}
                </p>
              </div>

              {check && check.components.length > 0 && (
                <div className="mt-3 overflow-x-auto">
                  <table className="w-full text-left text-copy-13">
                    <thead className="text-muted-foreground border-b">
                      <tr>
                        <th className="px-3 py-2">组件 SKU</th>
                        <th className="px-3 py-2">每件用量</th>
                        <th className="px-3 py-2">目标仓库可售量</th>
                        <th className="px-3 py-2">还能成套卖</th>
                        <th className="px-3 py-2">判定</th>
                      </tr>
                    </thead>
                    <tbody>
                      {check.components.map((c: ListingBundleComponentSellable) => (
                        <tr key={c.componentId} className="border-b last:border-0">
                          <td className="px-3 py-2 font-mono text-xs">{c.componentSkuId}</td>
                          <td className="px-3 py-2">{formatQuantity(c.quantity)}</td>
                          <td className="px-3 py-2">{sellableText(c)}</td>
                          <td className="px-3 py-2">{formatQuantity(c.setsAvailable)} 件</td>
                          <td className="px-3 py-2">
                            <Badge tone={c.sellable ? 'success' : 'danger'}>
                              {c.sellable ? '可售' : '不可售'}
                            </Badge>
                            {c.reason && (
                              <div className="text-muted-foreground">{c.reason}</div>
                            )}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
              {check?.note && (
                <p className="text-copy-13 text-muted-foreground mt-3">{check.note}</p>
              )}
            </div>
          </div>
        )}
      </SectionCard>
    </div>
  );
}

function Field({
  label,
  children,
  className,
}: {
  label: string;
  children: React.ReactNode;
  className?: string;
}) {
  return (
    <label className={`flex flex-col gap-1 ${className ?? ''}`}>
      <span className="text-copy-13 text-muted-foreground">{label}</span>
      {children}
    </label>
  );
}
