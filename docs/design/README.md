# 设计 (Design)

starcon 的**视觉规范、设计令牌、组件调性**集中在此。前端实际代码进 `apps/web/src/` `apps/admin/src/`，本目录是**规范层**，不是实现层。

## 子目录

- [`visual-spec.md`](visual-spec.md) — **主视觉规范手册**（Vercel/Geist 风格, 黑白灰主色 + 蓝绿强调）

## 与代码的对应

| 设计规范                                                           | 实际代码（落地时）                                               |
| ------------------------------------------------------------------ | ---------------------------------------------------------------- |
| 设计令牌 (color / font / radius / shadow / motion)                 | `apps/web/src/styles/tokens.css` + `apps/web/tailwind.config.ts` |
| 全局样式                                                           | `apps/web/src/app/globals.css`                                   |
| 组件库 (Button / Card / Tabs / Modal / Input)                      | `packages/ui/src/` (shadcn-style)                                |
| 业务组件 (SectionCard / StatCard / Badge / Segmented / EmptyState) | `packages/ui/src/`                                               |
| Logo / 品牌标识                                                    | `packages/ui/src/logo/`                                          |
| 布局 (Sidebar + Header)                                            | `packages/ui/src/layout/`                                        |

**单一真相源原则**：任何视觉改动先改本目录的规范，再改代码，**禁止代码里写死色值/字号/圆角等"绕过规范"的值**。

## 修订记录

| 版本 | 日期       | 修订                                                                  |
| ---- | ---------- | --------------------------------------------------------------------- |
| V1.0 | 2026-09-25 | 初版, 基于"能耗诊断管理系统"V1.0 视觉规范迁移 + 跨境电商 ERP 场景适配 |

详见 [`visual-spec.md`](visual-spec.md) 末尾的修订记录。
