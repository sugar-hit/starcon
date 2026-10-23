'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import Link from 'next/link';
import { Badge, Button, EmptyState, Input, SectionCard, StatCard } from '@starcon/ui';
import { ArrowDown, ArrowUp, ExternalLink, RefreshCw, Star, Trash2, TriangleAlert } from 'lucide-react';
import { api } from '@/lib/api';
import {
  FAVORITE_PAGES,
  groupFavoritePages,
  type FavoritePage,
} from '@/lib/favorite-page-registry';
import { useIdentity } from '../iam/identity-shell';
import {
  applyMove,
  canReadFavoritePage,
  describeFavoriteSystem,
  dropTargetIndex,
  favoriteIndexOf,
  filterFavoritePages,
  moveTargetIndex,
  parseFavoriteSet,
  type PageFavorite,
  type PageFavoriteState,
} from './page-favorite-model';

/**
 * 1.2.2.1 常用页面收藏 —— 工作区（`/dashboard/favorites`）。
 *
 * <p>蓝图原文：「**职责**：用户把高频页面（某店铺订单、某仓库库存、某报表）收藏到
 * 工作台侧边快捷区，形成个人工作流。**输入**：用户收藏/取消收藏操作；页面注册表
 * （前端路由 + 标题 + 所属系统）。**输出**：收藏列表（有序、支持拖拽排序）；点击直达。」
 *
 * <p><b>本工作区的三块</b>：
 *
 * <ul>
 *   <li><b>我的收藏</b>：有序清单，点击直达（Link 跳到目标页）；
 *       拖拽排序 + 上移/下移按钮；逐条取消收藏；</li>
 *   <li><b>添加收藏</b>：从**页面注册表**里挑（真注册表，不是硬编码数组），
 *       无读权限的页面当场标出来（不让人收藏完才发现是空的）；</li>
 *   <li><b>因权限隐藏的收藏</b>：服务端把无权访问的收藏自动隐藏了，
 *       这里报数并给一个「一次性清掉」的动作 —— 否则那些条目用户永远看不见也删不掉。</li>
 * </ul>
 *
 * <p><b>排序为什么是「拖拽 + 上移/下移按钮」两套</b>：本仓前端<b>没有</b>拖拽组件，
 * 引入一个重量级依赖只为拖一条列表不划算（而且多数拖拽库默认**不**支持键盘）。
 * 所以拖拽用浏览器原生 DnD 实现（零依赖），再配一组**键盘与读屏可用**的上移/下移按钮 ——
 * 两条路径走**同一个端点、同一套语义**（把某条移到可见清单第 N 位）。
 *
 * <p><b>与 1.2.2.2 快捷入口配置不重叠</b>：那边是「按岗位/团队预置的模板」，
 * 本页是「我自己收藏的页面」；两页不共用数据、不共用端点。
 */
export function PageFavoriteWorkspace() {
  const { session } = useIdentity();
  const permissions = session?.permissions ?? [];

  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [favorites, setFavorites] = useState<PageFavoriteState>({
    items: [],
    hiddenByPermission: 0,
    total: 0,
  });

  const [keyword, setKeyword] = useState('');
  const [pending, setPending] = useState('');
  const [dragIndex, setDragIndex] = useState(-1);
  const [overIndex, setOverIndex] = useState(-1);

  const load = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const raw = await api<unknown>('identity/page-favorites');
      setFavorites(parseFavoriteSet(raw));
    } catch (loadError) {
      setError(loadError instanceof Error ? loadError.message : '收藏读取失败');
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // ================================================================ 收藏操作

  const addFavorite = useCallback(
    async (page: FavoritePage) => {
      setNotice('');
      setPending(page.href);
      try {
        const raw = await api<unknown>('identity/page-favorites', 'POST', {
          href: page.href,
          label: page.label,
          system: page.system,
          pageCode: page.pageCode,
        });
        setFavorites(parseFavoriteSet(raw));
        setNotice(`已收藏「${page.label}」。`);
      } catch (addError) {
        setNotice(addError instanceof Error ? addError.message : '收藏失败，请重试');
      } finally {
        setPending('');
      }
    },
    [],
  );

  const removeFavorite = useCallback(async (href: string, label: string) => {
    setNotice('');
    try {
      const raw = await api<unknown>(
        `identity/page-favorites?href=${encodeURIComponent(href)}`,
        'DELETE',
      );
      setFavorites(parseFavoriteSet(raw));
      setNotice(`已取消收藏「${label}」。`);
    } catch (removeError) {
      setNotice(removeError instanceof Error ? removeError.message : '取消收藏失败，请重试');
    }
  }, []);

  /**
   * 排序：把某条移到可见清单的第 {@code toIndex} 位。
   *
   * <p>拖拽与上移/下移按钮走的是**同一个函数** ⇒ 两条路径的语义不可能漂移。
   * 先本地乐观重排（界面立刻响应），再用服务端的返回值覆盖（服务端说了算）。
   */
  const moveFavorite = useCallback(
    async (href: string, toIndex: number) => {
      const current = favorites.items;
      const from = favoriteIndexOf(current, href);
      if (from < 0) return;
      const optimistic = applyMove(current, href, toIndex);
      setFavorites((prev) => ({ ...prev, items: optimistic }));
      setNotice('');
      try {
        const raw = await api<unknown>('identity/page-favorites/order', 'PUT', {
          href,
          toIndex,
        });
        setFavorites(parseFavoriteSet(raw));
      } catch (moveError) {
        setNotice(moveError instanceof Error ? moveError.message : '排序失败，请重试');
        await load();
      }
    },
    [favorites.items, load],
  );

  const clearHidden = useCallback(async () => {
    setNotice('');
    try {
      const raw = await api<unknown>('identity/page-favorites/hidden', 'DELETE');
      setFavorites(parseFavoriteSet(raw));
      setNotice('已清理因权限不足被隐藏的收藏。');
    } catch (clearError) {
      setNotice(clearError instanceof Error ? clearError.message : '清理失败，请重试');
    }
  }, []);

  // ================================================================ 派生数据

  const registry = useMemo(() => filterFavoritePages(FAVORITE_PAGES, keyword), [keyword]);
  const registryGroups = useMemo(() => groupFavoritePages(registry), [registry]);
  const favorited = useMemo(
    () => new Set(favorites.items.map((item) => item.href)),
    [favorites.items],
  );

  // ================================================================ 渲染

  if (loading) {
    return (
      <div className="space-y-4">
        <SectionCard title="常用页面收藏" description="正在读取你的收藏清单……">
          <p className="text-muted-foreground text-copy-13">加载中……</p>
        </SectionCard>
      </div>
    );
  }

  if (error) {
    return (
      <div className="space-y-4">
        <SectionCard title="常用页面收藏">
          <EmptyState
            icon={<TriangleAlert className="h-6 w-6" />}
            title="收藏读取失败"
            description={error}
            action={
              <Button onClick={() => void load()}>
                <RefreshCw className="mr-2 h-4 w-4" />
                重试
              </Button>
            }
          />
        </SectionCard>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-3">
        <StatCard label="收藏条目" value={favorites.items.length} />
        <StatCard label="存储总数" value={favorites.total} />
        <StatCard label="因权限隐藏" value={favorites.hiddenByPermission} />
      </div>

      {notice ? (
        <p className="border-border bg-muted text-copy-13 rounded-md border px-3 py-2" role="status">
          {notice}
        </p>
      ) : null}

      <SectionCard
        title="我的收藏"
        description="有序清单：拖拽可以调整顺序，也可以用每条右侧的上移/下移按钮（键盘与读屏可用）。点标题直接跳到那一页。"
        extra={
          <Button variant="outline" onClick={() => void load()}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
        }
      >
        {favorites.items.length === 0 ? (
          <EmptyState
            icon={<Star className="h-6 w-6" />}
            title="还没有收藏任何页面"
            description="在下面的「添加收藏」里挑几个高频页面，它们会出现在这里，随时一键直达。"
          />
        ) : (
          <ul className="divide-border divide-y">
            {favorites.items.map((item, index) => {
              const upTarget = moveTargetIndex(favorites.items, item.href, 'up');
              const downTarget = moveTargetIndex(favorites.items, item.href, 'down');
              return (
                <li
                  key={item.href}
                  draggable
                  onDragStart={() => setDragIndex(index)}
                  onDragOver={(event) => {
                    event.preventDefault();
                    setOverIndex(index);
                  }}
                  onDragEnd={() => {
                    setDragIndex(-1);
                    setOverIndex(-1);
                  }}
                  onDrop={(event) => {
                    event.preventDefault();
                    const target = dropTargetIndex(favorites.items, dragIndex, index);
                    setDragIndex(-1);
                    setOverIndex(-1);
                    if (target >= 0) void moveFavorite(item.href, target);
                  }}
                  className={`flex flex-wrap items-center gap-3 py-3 ${
                    overIndex === index ? 'bg-muted/40' : ''
                  }`}
                >
                  <span className="text-muted-foreground w-6 text-center text-copy-12">
                    {index + 1}
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <Link
                        href={item.href}
                        className="text-foreground text-copy-13 font-medium hover:underline"
                      >
                        {item.label}
                      </Link>
                      <Badge tone="neutral">{describeFavoriteSystem(item.system)}</Badge>
                    </div>
                    <div className="text-muted-foreground mt-1 flex flex-wrap gap-3 text-copy-12">
                      <span>{item.href}</span>
                      {item.pageCode ? <span>页面码 {item.pageCode}</span> : null}
                    </div>
                  </div>
                  <div className="flex items-center gap-2">
                    <Button
                      size="sm"
                      variant="ghost"
                      aria-label={`把「${item.label}」上移`}
                      disabled={upTarget === index}
                      onClick={() => void moveFavorite(item.href, upTarget)}
                    >
                      <ArrowUp className="mr-1 h-3.5 w-3.5" />
                      上移
                    </Button>
                    <Button
                      size="sm"
                      variant="ghost"
                      aria-label={`把「${item.label}」下移`}
                      disabled={downTarget === index}
                      onClick={() => void moveFavorite(item.href, downTarget)}
                    >
                      <ArrowDown className="mr-1 h-3.5 w-3.5" />
                      下移
                    </Button>
                    <Link href={item.href}>
                      <Button size="sm" variant="outline">
                        <ExternalLink className="mr-1 h-3.5 w-3.5" />
                        直达
                      </Button>
                    </Link>
                    <Button
                      size="sm"
                      variant="ghost"
                      onClick={() => void removeFavorite(item.href, item.label)}
                    >
                      <Trash2 className="mr-1 h-3.5 w-3.5" />
                      取消收藏
                    </Button>
                  </div>
                </li>
              );
            })}
          </ul>
        )}

        {favorites.hiddenByPermission > 0 ? (
          <div className="border-border mt-4 rounded-md border px-3 py-3">
            <p className="text-copy-13">
              有 {favorites.hiddenByPermission}{' '}
              条收藏因为当前没有目标页面的读权限而被自动隐藏（数据仍然保留，权限恢复后会自动回来）。
            </p>
            <Button size="sm" variant="outline" className="mt-2" onClick={() => void clearHidden()}>
              清理这些隐藏的收藏
            </Button>
          </div>
        ) : null}
      </SectionCard>

      <SectionCard
        title="添加收藏"
        description="页面来自页面注册表（导航里的真实页面），不是写死的清单。没有读权限的页面会标出来 —— 收藏了也会被自动隐藏。"
      >
        <Input
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder="按标题或路径筛选页面"
          aria-label="筛选可收藏的页面"
        />
        {registryGroups.length === 0 ? (
          <p className="text-muted-foreground mt-3 text-copy-13">
            没有匹配「{keyword}」的页面。
          </p>
        ) : (
          <div className="mt-4 space-y-4">
            {registryGroups.map((group) => (
              <div key={group.system}>
                <p className="text-muted-foreground mb-2 text-copy-13">{group.label}</p>
                <ul className="divide-border divide-y">
                  {group.pages.map((page) => {
                    const already = favorited.has(page.href);
                    const readable = canReadFavoritePage(page.pageCode, permissions);
                    return (
                      <li key={page.href} className="flex flex-wrap items-center gap-3 py-2">
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="text-foreground text-copy-13">{page.label}</span>
                            {already ? <Badge tone="success">已收藏</Badge> : null}
                            {!readable ? <Badge tone="warning">无读权限</Badge> : null}
                          </div>
                          <span className="text-muted-foreground text-copy-12">{page.href}</span>
                        </div>
                        <Button
                          size="sm"
                          variant={already ? 'ghost' : 'default'}
                          disabled={already || pending === page.href}
                          onClick={() => void addFavorite(page)}
                        >
                          {already ? '已在清单里' : '收藏'}
                        </Button>
                      </li>
                    );
                  })}
                </ul>
              </div>
            ))}
          </div>
        )}
      </SectionCard>
    </div>
  );
}
