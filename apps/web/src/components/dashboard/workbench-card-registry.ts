/**
 * 1.4.1.1 工作台布局 —— **系统可选卡片清单**（系统事实）。
 *
 * <p>蓝图原文：
 * 「**职责**：用户拖拽调整指标卡顺序、隐藏不需要的卡片、选择默认落地 Tab。
 * **输入**：拖拽/显隐操作；**系统可选卡片清单**。
 * **输出**：个性化后的工作台布局；可重置为默认布局。
 * **关系**：上游 = **前端卡片注册表**；下游 = 1.1 全部卡片渲染。」
 *
 * <p>🔴 **这张清单是「系统事实」，不是「用户偏好」** —— 两者必须严格分开：
 *
 * <ul>
 *   <li><b>系统事实</b>（本文件）：代码里注册了哪些卡片、每张卡片属于哪个分区、
 *       默认是否显示、默认顺序。改一次对所有人生效，**不该**给每个人改一次数据。</li>
 *   <li><b>个人偏好</b>（{@code user_preference} 的 {@code dashboard.layout}）：
 *       <b>这个人</b>把卡片排成什么顺序、藏了哪几张、默认落在哪个分区。
 *       存在数据库里，只对这个人、这个租户可见。</li>
 * </ul>
 *
 * <p>所以系统事实**绝不**进 {@code user_preference}：那张表里每一行都是「我这一页怎么摆」，
 * 混进「系统有哪些卡片」会让「系统上线一张新卡片」变成一件**要给所有租户所有成员改数据**的事。
 *
 * <p><b>卡片 key 必须稳定</b>：它就是存进偏好载荷里的那个字符串。用户排好序之后，
 * 服务端存的是 {@code ["todo.anomaly-orders", "risk.inventory", ...]}；
 * key 改名 = 所有人的布局里那一张凭空消失。
 *
 * <p><b>只登记「已经交付、真的能打开」的页面</b>：指向未开发模块等于让用户点进去撞到
 * 「规划中」，那是假承诺，不是引导（首页已经因为这个原则改过一次：
 * 「从一件事开始」只指向真正能用的工作区）。所以这张清单里每一张卡片都对应一条
 * **已存在的路由**。
 */
import type { LucideIcon } from 'lucide-react';
import { Boxes, ClipboardList, HeartPulse, LayoutGrid, Search } from 'lucide-react';

/**
 * 工作台分区（蓝图 1.1 的三段结构）。
 *
 * <p>「默认落地 Tab」指的就是这里的一个分区 —— 用户进工作台时先落在哪一段。
 */
export type WorkbenchTabId = 'overview' | 'todo' | 'risk';

/** 一个分区。 */
export interface WorkbenchTab {
  /** 稳定的分区标识（存进偏好载荷里的 {@code defaultTab}）。 */
  id: WorkbenchTabId;
  /** 界面上的标题。 */
  label: string;
  /** 这一段放什么。 */
  description: string;
}

/**
 * 一张可放到工作台上的卡片。
 *
 * <p>卡片**不自带数据**：它是一个入口，点进去是归属系统里那个已经交付的真实工作区。
 * 工作台是只读 + 跳转的聚合层（卷 01 硬边界），卡片本身不持有业务事实。
 */
export interface WorkbenchCard {
  /** 稳定的卡片标识（存进偏好载荷里的那个字符串）。 */
  key: string;
  /** 界面上的标题。 */
  label: string;
  /** 一句话说清这张卡片是什么。 */
  description: string;
  /** 点进去的真实路由。 */
  href: string;
  /** 这张卡片属于哪个分区。 */
  tab: WorkbenchTabId;
  /** 默认是否显示（用户可以在自己的布局里改）。 */
  defaultVisible: boolean;
  /** 卡片图标。 */
  icon: LucideIcon;
  /**
   * 这张卡片背后是哪个已交付模块（蓝图 L4 编号）。
   *
   * <p>界面上直接写出来，是为了让人知道「这张卡是哪个模块的」——
   * 也顺手让「卡片清单」和台账对得上，不至于各说各话。
   */
  module: string;
}

/** 工作台的分区（默认落地 Tab 的候选）。 */
export const WORKBENCH_TABS: readonly WorkbenchTab[] = [
  {
    id: 'overview',
    label: '概览',
    description: '今天的整体水位与常用入口。',
  },
  {
    id: 'todo',
    label: '待办与待决策',
    description: '卡住了、需要人处理的事。',
  },
  {
    id: 'risk',
    label: '风险速览',
    description: '缺货、超卖、呆滞、超储等正在变坏的事。',
  },
];

/** 默认落地区分区。 */
export const DEFAULT_WORKBENCH_TAB: WorkbenchTabId = 'overview';

/**
 * 卡片注册表 —— **系统事实**。
 *
 * <p>顺序 = 默认顺序（用户在个人布局里没动过时就是它）。默认按「先要处理的、
 * 再要看的、最后是工具」排。
 */
export const WORKBENCH_CARDS: readonly WorkbenchCard[] = [
  {
    key: 'todo.anomaly-orders',
    label: '异常订单队列',
    description: '平台超时未回传发货、地址不可达、库存不足卡单等卡住的订单。',
    href: '/dashboard/todo',
    tab: 'todo',
    defaultVisible: true,
    icon: ClipboardList,
    module: '1.1.1.2 异常订单队列',
  },
  {
    key: 'risk.inventory',
    label: '库存风险',
    description: '缺货、超卖、呆滞、超储四类库存问题，区分本地仓 / 海外仓 / FBA / 在途。',
    href: '/dashboard/inventory-risk',
    tab: 'risk',
    defaultVisible: true,
    icon: Boxes,
    module: '1.1.3.1 库存风险',
  },
  {
    key: 'metric.fulfillment-health',
    label: '履约健康',
    description: '待发货积压、平均出库时长、超时发货率与平台绩效水位。',
    href: '/dashboard/fulfillment-health',
    tab: 'overview',
    defaultVisible: true,
    icon: HeartPulse,
    module: '1.1.2.3 履约健康',
  },
  {
    key: 'nav.global-search',
    label: '全局搜索',
    description: '一个输入框跨系统检索 SKU、订单号、店铺名、物流单号。',
    href: '/dashboard/search',
    tab: 'overview',
    defaultVisible: true,
    icon: Search,
    module: '1.2.1.1 全局搜索框',
  },
  {
    key: 'nav.shortcuts',
    label: '快捷入口配置',
    description: '按岗位 / 团队预置的快捷入口模板，与你的个人覆盖项合并后的生效集合。',
    href: '/dashboard/shortcuts',
    tab: 'overview',
    defaultVisible: true,
    icon: LayoutGrid,
    module: '1.2.2.2 快捷入口配置',
  },
];

/** 卡片 key 集合（注册表里的全集）。 */
export const WORKBENCH_CARD_KEYS: readonly string[] = WORKBENCH_CARDS.map((card) => card.key);

/** 默认顺序（注册表顺序的副本）。 */
export function defaultWorkbenchOrder(): string[] {
  return WORKBENCH_CARDS.map((card) => card.key);
}

/** 按 key 查卡片；查不到返回 undefined（**不抛** —— 载荷里可能有早就下线的卡片）。 */
export function findWorkbenchCard(key: string): WorkbenchCard | undefined {
  return WORKBENCH_CARDS.find((card) => card.key === key);
}

/** 按 key 查分区；查不到返回 undefined。 */
export function findWorkbenchTab(id: string): WorkbenchTab | undefined {
  return WORKBENCH_TABS.find((tab) => tab.id === id);
}

/** 这张卡片默认是否显示。 */
export function isDefaultVisible(key: string): boolean {
  return findWorkbenchCard(key)?.defaultVisible ?? true;
}
