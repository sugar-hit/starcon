# 2026-09-26 — Logo v4 (品牌源 B1 精修方案)

## 用户输入

用户提供了品牌源文件 — `docs/design/brand-source-b1-refined.png` (1536×1024),6 个 B1 精修方案变体。要求:

1. 把源文件加到视觉风格描述里
2. 基于图片追加描述信息,修正到视觉风格描述
3. 修改 Logo

## 设计理念(品牌源原文)

**"四个商业节点连接,形成中心之星"**

- 4 节点 = 4 业务系统(开发 / 运营 / 物流 / 仓储,与 §2 4 系统架构呼应)
- 中央菱形 = `STAR` 概念 + 业务汇聚的双重隐喻(**圆角菱形**,不是带尖角的 4 角星)
- 几何本质:**4 旋转对称**(N/E/S/W),任意 90° 旋转视觉一致

## 6 个变体(B1-1 ~ B1-6)

| 变体 | 名称 | 用途 |
|---|---|---|
| **B1-1 经典版(推荐)** | 平衡几何,识别度高,适用最广 | **starcon 主 mark** |
| B1-2 更几何化 | 圆角克制,专业稳定 | 印刷品 |
| B1-3 更圆润 | 亲和力,全球市场 | 海外物料 |
| B1-4 星形更明显 | 中央负空间大,STAR 概念突出 | 营销大尺寸 |
| B1-5 极简版 | 线条更轻,小尺寸 | **favicon / 16px** |
| B1-6 动态感 | 微倾斜,连接/流动 | 启动屏/动画 |

## 落地

### visual-spec.md §9 (新增,Logo 品牌源与设计理念)

- §9.1 品牌源文件 (引用 PNG)
- §9.2 核心设计理念
- §9.3 B1 六个变体
- §9.4 字标规范 (STARCON + Commerce OS, 字体/字间距)
- §9.5 三种标准尺寸 (64/24/16 px)
- §9.6 色彩应用 (主用黑 / 数字化场景蓝 / 深底白 / App Icon)
- §9.7 真实场景示例
- §9.8 落地文件清单 (8 SVG)

视觉规范版本 V1.4 → **V1.5**

### Logo SVG 重做 (8 个文件,旧 v3 全部废弃)

| 文件 | 角色 | 配色 |
|---|---|---|
| `apps/web/public/logo-mark.svg` | 主 mark (B1-1 经典版) | `#0A0A0A` 黑 |
| `apps/web/public/logo-mark-dark.svg` | 深色背景 | `#FFFFFF` 白 |
| `apps/web/public/logo-mark-brand.svg` | 数字化场景 | `#1D4ED8` 品牌蓝 |
| `apps/web/public/favicon.svg` | B1-5 极简版 | `#0A0A0A` 黑 |
| `apps/web/public/logo-wordmark.svg` | 纯字标 | `#0A0A0A` 黑 |
| `apps/web/public/logo-lockup.svg` | mark + 字标 横版 | `#0A0A0A` 黑 |
| `apps/web/public/logo-app-icon-light.svg` | 浅色 App Icon | 黑 mark + `#F5F5F5` 底 |
| `apps/web/public/logo-app-icon-dark.svg` | 深色 App Icon | 白 mark + `#1D4ED8` 底 |

### Logo.tsx (副字标更新)

- 旧:`跨境电商 AI ERP`(临时品牌 tagline)
- 新:`Commerce OS`(与品牌源对齐)
- 注释更新:从"v3 蓝紫渐变多层几何" → "B1 4 节点 + 中心 4 角星"
- 文件路径注释指向新的 design 源文件

## 设计参数 (B1-1)

```
viewBox: 0 0 64 64
圆角菱形主体: M32 10 Q40 24 54 32 Q40 40 32 54 Q24 40 10 32 Q24 24 32 10 Z
  - 4 顶点: (32,10) (54,32) (32,54) (10,32) — 距中心 22
  - 4 Q 弧线 控制点: (40,24) (40,40) (24,40) (24,24) — 内接菱形 NE/SE/SW/NW, 凹向中心
  - 中央菱形负空间: 4 控制点围成的圆角菱形, 宽 ~27 (canvas 42%)
4 节点圆点: (32,6) (58,32) (32,58) (6,32), r=8, 距中心 26 — 凸出菱形顶点 2px, 形成"瓣"感
```

## 文件改动清单

```
M apps/web/public/favicon.svg              (重写, B1-5)
M apps/web/public/logo-mark-dark.svg       (重写, 白)
M apps/web/public/logo-mark.svg            (重写, 黑)
M docs/design/visual-spec.md               (§9 新增 + V1.5)
M packages/ui/src/components/common/logo.tsx (副字标 + 注释)
+ apps/web/public/logo-app-icon-dark.svg   (新增)
+ apps/web/public/logo-app-icon-light.svg  (新增)
+ apps/web/public/logo-lockup.svg          (新增)
+ apps/web/public/logo-mark-brand.svg      (新增)
+ apps/web/public/logo-wordmark.svg        (新增)
+ docs/design/brand-source-b1-refined.png  (品牌源归档)
```

## 视觉验证

- `v16-01-logo-b1-sidebar.png` — 工作台 sidebar 实际渲染:4 节点 + 中心 4 角星 + "STARCON / Commerce OS"
- `v16-02-logo-detail.png` — 3x 放大:mark 几何细节清晰,4 个圆点边界分明

## 待办 (后续)

- [ ] Logo 在 dark mode 下的渲染验证(等 AppShell 加 dark mode)
- [ ] lockup.svg / wordmark.svg / app-icon-*.svg 在 marketing / docs 场景的使用
- [ ] Logo v3 残留(如果有其他地方引用旧 SVG)全面 grep 清理

## 提交

```
c6993af feat(brand): Logo v4 — 改用品牌源 B1 4 节点 + 中心 4 角星
```
---

## 2026-09-26 修正 — Logo v4.1 (圆角菱形替代 4 角星)

### 用户反馈

> 你这个 Logo。。也和我的图不一样啊。。

### 问题

第一版 v4 把"中心之星"实现为**带尖角的 4 角星**(8 个直线段),与品牌源 B1-1 不一致。源图实际上是**圆角菱形**+ 4 凹弧连接 + 4 圆点,中央负空间是**菱形**而非**4 角星**。

| | v4 (错) | v4.1 (正) | 源图 |
|---|---|---|---|
| 主体几何 | 8 直线 V 凹 | 4 Q 凹弧 | 4 Q 凹弧 |
| 中央负空间 | 4 角星 (尖) | 圆角菱形 | 圆角菱形 |
| 4 圆点连接 | 直线段 | Q 弧线 | Q 弧线 |

### 修正

`logo-mark.svg` / `logo-mark-dark.svg` / `logo-mark-brand.svg` / `favicon.svg` / `logo-lockup.svg` / `logo-app-icon-light.svg` / `logo-app-icon-dark.svg` 共 7 个 SVG 全部更新。

新 path:
```
M32 10 Q40 24 54 32 Q40 40 32 54 Q24 40 10 32 Q24 24 32 10 Z
```

visual-spec §9.2 描述同步修正 (中心 4 角星 → 中央菱形)。
