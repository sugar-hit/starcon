import { GlobalSearchPageWorkspace } from '@/components/dashboard/global-search-workspace';

/**
 * 1.2.1.1 全局搜索框 —— 独立页（`/dashboard/search`）。
 *
 * <p>蓝图给本模块的锚点是 **⌘K**（1.2 统一搜索），所以主入口是命令面板；
 * 这一页是同一个搜索框的**可分享 / 可开标签页**的落点：
 *
 * <ul>
 *   <li>「最近搜索」点进来时带着 {@code ?q=} 落到这一页；
 *   <li>用户想在浏览器里保留某次检索结果时，可以直接停在这一页。</li>
 * </ul>
 *
 * <p><b>它不是占位页</b>：真实输入框、真实跨服务检索、真实分组结果、
 * 真实错误 / 空 / 加载态，与 ⌘K 面板共用同一套逻辑
 * （{@link GlobalSearchPageWorkspace} 与命令面板的注入走同一个状态钩子）。
 */
export default function GlobalSearchPage() {
  return <GlobalSearchPageWorkspace />;
}
