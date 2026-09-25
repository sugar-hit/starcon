import {
  LayoutDashboard,
  Package,
  Boxes,
  ClipboardList,
  Tag,
  ShoppingCart,
  Factory,
  Warehouse,
  Ship,
  HeadphonesIcon,
  CircleDollarSign,
  TrendingUp,
  BarChart3,
  Users,
  Shield,
} from 'lucide-react';
import type { LucideIcon } from 'lucide-react';

/**
 * starcon 导航配置 — 单一真相源
 *
 * 架构（来自用户提供的 AI ERP 总功能地图）:
 *   全部 (AI ERP)
 *     └─ 系统 × 3        ← 顶部 Segmented Tab（用户硬规则：不含"全部"）
 *          ├─ 商品供应链
 *          ├─ 交易履约
 *          └─ 经营管理
 *              └─ Domain (功能) × 15
 *
 *   顶层固定项 × 2      ← 侧栏顶部，不属于任何系统
 *     ├─ 工作台          （首页 = /）
 *     └─ AI Copilot
 *
 * 路由约定:
 *   /            = 工作台
 *   /products    = 商品中心（商品供应链）
 *   /orders      = 订单中心（交易履约）
 *   /finance     = 财务中心（经营管理）
 */

export type SystemId = 'product' | 'trade' | 'manage';

export interface NavChildItem {
  label: string;
  href: string;
}

export interface NavItem {
  /** Domain / 功能 名，如 "订单中心" */
  label: string;
  /** 主路径 */
  href: string;
  /** 图标 */
  icon: LucideIcon;
  /** 简短描述（hover tooltip / meta） */
  description?: string;
  /** 二级页面（后续展开） */
  children?: NavChildItem[];
}

export interface SystemDef {
  id: SystemId;
  /** 系统名，如 "交易履约" */
  label: string;
  /** 简短描述 */
  description: string;
  /** 该系统下的功能列表 */
  items: NavItem[];
  /** 系统默认进入页（切换系统时跳这里） */
  defaultHref: string;
}

/** 商品供应链 — 5 个 domain */
const productSystem: SystemDef = {
  id: 'product',
  label: '商品供应链',
  description: '商品 / 刊登 / 定价 / 采购 / 供应',
  defaultHref: '/products',
  items: [
    { label: '商品中心', href: '/products', icon: Package, description: 'SKU 与变型管理' },
    { label: '刊登中心', href: '/listings', icon: ClipboardList, description: '多平台商品刊登' },
    { label: '定价中心', href: '/pricing', icon: Tag, description: '价格策略与汇率' },
    { label: '采购中心', href: '/purchase', icon: ShoppingCart, description: '采购单与供应商' },
    { label: '供应中心', href: '/supply', icon: Factory, description: '供应商与档口管理' },
  ],
};

/** 交易履约 — 5 个 domain */
const tradeSystem: SystemDef = {
  id: 'trade',
  label: '交易履约',
  description: '订单 / 库存 / 仓储 / 物流 / 售后',
  defaultHref: '/orders',
  items: [
    { label: '订单中心', href: '/orders', icon: Package, description: '多平台订单聚合' },
    { label: '库存中心', href: '/inventory', icon: Boxes, description: '全渠道库存协同' },
    { label: '仓储中心', href: '/warehouse', icon: Warehouse, description: '仓位与库内作业' },
    { label: '物流中心', href: '/logistics', icon: Ship, description: '物流轨迹与配送' },
    { label: '售后中心', href: '/aftersales', icon: HeadphonesIcon, description: '退款 / 退货 / 客诉' },
  ],
};

/** 经营管理 — 5 个 domain */
const manageSystem: SystemDef = {
  id: 'manage',
  label: '经营管理',
  description: '财务 / 利润 / 数据 / CRM / 组织',
  defaultHref: '/finance',
  items: [
    { label: '财务中心', href: '/finance', icon: CircleDollarSign, description: '对账与结算' },
    { label: '利润中心', href: '/profit', icon: TrendingUp, description: '毛利与分润' },
    { label: '数据中心', href: '/analytics', icon: BarChart3, description: '数据看板与报表' },
    { label: 'CRM / 客服', href: '/crm', icon: Users, description: '客户档案与工单' },
    { label: '组织 / 权限', href: '/org', icon: Shield, description: '员工与角色权限' },
  ],
};

/** 系统列表 — 顶部 Segmented Tab 用（用户硬规则：不包含"全部"） */
export const SYSTEMS: SystemDef[] = [productSystem, tradeSystem, manageSystem];

/** 顶层固定项 — 侧栏顶部，不属于任何系统 */
export const TOP_LEVEL_ITEMS: NavItem[] = [
  { label: '工作台', href: '/', icon: LayoutDashboard, description: '今日待办与关键指标' },
  { label: 'AI Copilot', href: '/copilot', icon: TrendingUp, description: 'AI 助手与自动化' },
];

/** 按 id 查系统 */
export function getSystem(id: SystemId): SystemDef {
  return SYSTEMS.find((s) => s.id === id) ?? productSystem;
}

/**
 * 由 pathname 推断当前 system
 * 规则：精确前缀匹配 → 第一个匹配胜出
 *   /products...      → product
 *   /orders...        → trade
 *   /finance...       → manage
 *   其它（含 /, /copilot, /styleguide, /api）→ product (默认)
 */
export function resolveSystemFromPath(pathname: string): SystemDef {
  for (const sys of SYSTEMS) {
    for (const item of sys.items) {
      if (pathname === item.href || pathname.startsWith(item.href + '/')) {
        return sys;
      }
    }
  }
  return productSystem;
}

/** 由 pathname 推断当前 feature (NavItem) — 用于 PageHeader title + 当前高亮 */
export function resolveFeatureFromPath(pathname: string, system: SystemDef): NavItem | null {
  return (
    system.items.find((it) => pathname === it.href || pathname.startsWith(it.href + '/')) ??
    TOP_LEVEL_ITEMS.find((it) => pathname === it.href || pathname.startsWith(it.href + '/')) ??
    null
  );
}