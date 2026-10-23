'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { Badge, Button, EmptyState, Input, type CommandPaletteGroup } from '@starcon/ui';
import { Search } from 'lucide-react';
import {
  buildHref,
  countHits,
  domainLabel,
  hitSubtitle,
  mergeServiceResults,
  MIN_KEYWORD_LENGTH,
  normalizeKeyword,
  type GlobalSearchGroup,
  type GlobalSearchHit,
} from './global-search-model';
import {
  clearRecentSearches,
  fetchRecentSearches,
  recordRecentSearch,
  searchAllServices,
  type GlobalSearchRecentEntry,
} from './global-search-client';

/**
 * 1.2.1.1 全局搜索框 —— 前端工作区。
 *
 * <p><b>两处入口，同一套逻辑</b>：
 *
 * <ul>
 *   <li><b>⌘K 面板</b>：{@link GlobalSearchProvider} 把结果注入 AppShell 的命令面板
 *       （「业务对象」那个占位组由真实检索结果替换）。</li>
 *   <li><b>独立页</b>：{@link GlobalSearchPageWorkspace} 是 {@code /dashboard/search}，
 *       同一个输入框、同一套分组、同一批错误/空/加载态。</li>
 * </ul>
 *
 * <p><b>检索在四个服务上并发做，合并在这一层做</b>（蓝图的「前端聚合层」）。
 * 一个服务失败不影响其余三个域 —— 失败的服务会在状态行里点名。
 *
 * <p><b>「最近搜索」是个人偏好</b>，权威副本在 identity 服务（V330 建表）；
 * 前端不把它当唯一真相，只在检索失败时也不至于丢掉这份记录。
 */

const DEBOUNCE_MS = 220;

/** 检索状态：结果、状态行、加载中。 */
export interface GlobalSearchState {
  keyword: string;
  groups: GlobalSearchGroup[];
  statusText: string;
  loading: boolean;
  recent: GlobalSearchRecentEntry[];
  setKeyword: (keyword: string) => void;
  runNow: () => Promise<void>;
  recordSelection: (hit: GlobalSearchHit, domain: string) => Promise<void>;
  clearRecent: () => Promise<void>;
}

/** ⌘K 与独立页共用的检索状态。 */
export function useGlobalSearchState(initialKeyword = ''): GlobalSearchState {
  const router = useRouter();
  const [keyword, setKeyword] = useState(initialKeyword);
  const [groups, setGroups] = useState<GlobalSearchGroup[]>([]);
  const [statusText, setStatusText] = useState('');
  const [loading, setLoading] = useState(false);
  const [recent, setRecent] = useState<GlobalSearchRecentEntry[]>([]);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const refreshRecent = useCallback(async () => {
    try {
      setRecent(await fetchRecentSearches());
    } catch {
      // 最近搜索读不到不是致命错误：检索本身照常工作，只是没有历史可回。
      setRecent([]);
    }
  }, []);

  useEffect(() => {
    void refreshRecent();
  }, [refreshRecent]);

  const runSearch = useCallback(async (raw: string) => {
    const normalized = normalizeKeyword(raw);
    if (!normalized.ok) {
      setGroups([]);
      setStatusText(normalized.hint);
      setLoading(false);
      return;
    }
    setLoading(true);
    setStatusText('正在检索…');
    const outcomes = await searchAllServices(normalized.keyword);
    const merged = mergeServiceResults(outcomes);
    setGroups(merged.groups);
    setStatusText(describeOutcome(merged.unavailableDomains, merged.failedServices, merged.groups));
    setLoading(false);
  }, []);

  const schedule = useCallback(
    (raw: string) => {
      if (timer.current) clearTimeout(timer.current);
      timer.current = setTimeout(() => {
        void runSearch(raw);
      }, DEBOUNCE_MS);
    },
    [runSearch],
  );

  useEffect(() => () => {
    if (timer.current) clearTimeout(timer.current);
  }, []);

  const changeKeyword = useCallback(
    (next: string) => {
      setKeyword(next);
      schedule(next);
    },
    [schedule],
  );

  const runNow = useCallback(async () => {
    if (timer.current) clearTimeout(timer.current);
    await runSearch(keyword);
  }, [keyword, runSearch]);

  const recordSelection = useCallback(
    async (hit: GlobalSearchHit, domain: string) => {
      try {
        await recordRecentSearch(hit.primary, domain, hit.primary);
        await refreshRecent();
      } catch {
        // 登记失败不该打断跳转：用户已经选中了东西。
      }
    },
    [refreshRecent],
  );

  const clearRecent = useCallback(async () => {
    try {
      await clearRecentSearches();
      setRecent([]);
    } catch {
      // 同上：清空失败时保留列表，不要假装已经清掉了。
    }
  }, []);

  void router;

  return {
    keyword,
    groups,
    statusText,
    loading,
    recent,
    setKeyword: changeKeyword,
    runNow,
    recordSelection,
    clearRecent,
  };
}

/** 状态行：把「没权限的域」与「失败的域」分开说。 */
function describeOutcome(
  unavailable: string[],
  failed: string[],
  groups: GlobalSearchGroup[],
): string {
  const parts: string[] = [];
  if (groups.length) parts.push(`命中 ${countHits(groups)} 条`);
  if (unavailable.length) parts.push(`${unavailable.length} 个域无权限`);
  if (failed.length) parts.push(`${failed.join('、')} 服务未响应`);
  if (!parts.length) return '没有匹配的记录';
  return parts.join(' · ');
}

/**
 * ⌘K 面板的接线。
 *
 * <p>用 render prop 而不是 Context：注入内容要交给 AppShell（**父**组件），
 * 而状态在 Provider（**子**组件）里 —— Context 只能向下传，接不上父组件的属性。
 */
export function GlobalSearchProvider({
  children,
}: {
  children: (binding: {
    groups: CommandPaletteGroup[];
    placeholder: string;
    emptyMessage: string;
    statusText: string;
    onQueryChange: (query: string) => void;
  }) => React.ReactNode;
}) {
  const state = useGlobalSearchState();
  const { groups: resultGroups, recent, recordSelection } = state;
  const groups = useMemo<CommandPaletteGroup[]>(() => {
    const paletteGroups: CommandPaletteGroup[] = resultGroups.map((group) => ({
      heading: group.label || domainLabel(group.domain),
      items: group.hits.map((hit) => ({
        id: `${group.domain}-${hit.kind}-${hit.id}`,
        label: hit.primary,
        description: hitSubtitle(hit),
        href: buildHref(group.domain, hit),
        onSelect: () => {
          void recordSelection(hit, group.domain);
        },
      })),
    }));
    if (recent.length) {
      paletteGroups.push({
        heading: '最近搜索',
        items: recent.map((entry) => ({
          id: `recent-${entry.keyword}`,
          label: entry.keyword,
          description: entry.domain ? domainLabel(entry.domain) : '最近搜过',
          href: `/dashboard/search?q=${encodeURIComponent(entry.keyword)}`,
        })),
      });
    }
    return paletteGroups;
  }, [resultGroups, recent, recordSelection]);

  return children({
    groups,
    placeholder: '搜索 SKU、订单号、店铺、客户…',
    emptyMessage: '没有匹配的结果。试试更完整的编码，或到各系统列表页做模糊检索。',
    statusText: state.statusText,
    onQueryChange: state.setKeyword,
  });
}

/** 独立页工作区（`/dashboard/search`）。 */
export function GlobalSearchPageWorkspace() {
  const state = useGlobalSearchState();
  const input = useRef<HTMLInputElement>(null);

  // 从 URL 带进来的关键词（从「最近搜索」点进来时）：读一次就跑检索。
  useEffect(() => {
    const value = new URLSearchParams(window.location.search).get('q');
    if (value && value !== state.keyword) state.setKeyword(value);
    // 只在挂载时读一次：之后由用户输入驱动。
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    input.current?.focus();
  }, []);

  return (
    <div className="space-y-4">
      <form
        className="flex items-center gap-2"
        onSubmit={(event) => {
          event.preventDefault();
          void state.runNow();
        }}
      >
        <Input
          ref={input}
          value={state.keyword}
          onChange={(event) => state.setKeyword(event.target.value)}
          placeholder="输入 SKU / SPU / 订单号 / 采购单号 / 店名 / 供应商 / 物流单号 / 客户"
          aria-label="全局搜索关键词"
          aria-describedby="global-search-status"
        />
        <Button type="submit" disabled={state.loading}>
          <Search className="mr-1.5 h-4 w-4" />
          搜索
        </Button>
      </form>

      <p id="global-search-status" role="status" className="text-copy-12 text-muted-foreground">
        {state.statusText}
      </p>

      {state.groups.map((group) => (
        <section key={group.domain} className="space-y-2">
          <div className="flex items-center gap-2">
            <h2 className="text-copy-14 font-semibold">{group.label || domainLabel(group.domain)}</h2>
            <Badge tone="neutral">{group.hits.length}</Badge>
          </div>
          <ul className="divide-border divide-y rounded-md border">
            {group.hits.map((hit) => (
              <li key={`${hit.kind}-${hit.id}`}>
                <Link
                  href={buildHref(group.domain, hit)}
                  className="hover:bg-muted flex items-center justify-between gap-3 px-3 py-2"
                  onClick={() => void state.recordSelection(hit, group.domain)}
                >
                  <span className="min-w-0">
                    <span className="text-copy-14 block truncate font-medium">{hit.primary}</span>
                    <span className="text-copy-12 text-muted-foreground block truncate">
                      {hitSubtitle(hit)}
                    </span>
                  </span>
                  <span className="text-label-12 text-muted-foreground shrink-0">{hit.kind}</span>
                </Link>
              </li>
            ))}
          </ul>
        </section>
      ))}

      {!state.loading && !state.groups.length && (
        <EmptyState
          icon={<Search className="h-6 w-6" />}
          title={
            state.keyword.trim().length < MIN_KEYWORD_LENGTH
              ? '输入至少 2 个字符开始检索'
              : '没有匹配的结果'
          }
          description="全局搜索只做前缀 / 精确匹配；模糊检索请到各系统自己的列表页。"
        />
      )}

      {!!state.recent.length && (
        <section className="space-y-2">
          <div className="flex items-center justify-between">
            <h2 className="text-copy-14 font-semibold">最近搜索</h2>
            <Button variant="ghost" size="sm" onClick={() => void state.clearRecent()}>
              清空
            </Button>
          </div>
          <ul className="flex flex-wrap gap-2">
            {state.recent.map((entry) => (
              <li key={entry.keyword}>
                <Link
                  href={`/dashboard/search?q=${encodeURIComponent(entry.keyword)}`}
                  className="border-border hover:bg-muted rounded-md border px-2.5 py-1 text-copy-13"
                >
                  {entry.keyword}
                </Link>
              </li>
            ))}
          </ul>
        </section>
      )}
    </div>
  );
}
