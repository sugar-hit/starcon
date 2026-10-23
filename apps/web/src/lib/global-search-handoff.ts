'use client';

import { useEffect, useState } from 'react';

/**
 * 1.2.1.1 全局搜索 → 目标页面筛选器的**回填**。
 *
 * <p>全局搜索选中一条命中时，跳转链接带上 `?keyword=<主标识>`
 * （见 `components/dashboard/global-search-model.ts` 的 `buildHref`）。
 * 目标页面用本钩子把那个值读回来，填进自己的关键字筛选器 ——
 * 于是「搜到 → 点 → 落在已经筛好的列表上」是一条完整的链，而不是跳过去之后还要再搜一遍。
 *
 * <p><b>为什么用 `window.location.search` 而不是 `useSearchParams()`</b>：
 * `useSearchParams` 会让整条路由放弃静态预渲染，Next 的 production build 会要求
 * 外层挂 `Suspense` 边界。本仓现有的页面一个都没用它 —— 这里不引入一个新的构建期约束。
 *
 * <p><b>只在挂载时读一次</b>：之后由用户自己的输入驱动，
 * 免得用户在目标页面改了筛选器、又被 URL 里的旧值覆盖回去。
 */
export function usePrefillKeyword(): [string, (value: string) => void] {
  const [keyword, setKeyword] = useState('');
  useEffect(() => {
    const value = new URLSearchParams(window.location.search).get('keyword');
    if (value) setKeyword(value);
  }, []);
  return [keyword, setKeyword];
}
