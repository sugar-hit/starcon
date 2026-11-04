# starcon-web

starcon 前端 —— Next.js 15 standalone 的 Helm 部署件。

浏览器**不直接**访问后端微服务。前端在服务端做代理：

```
浏览器 → /api/<服务>/<路径> → 前端 Next.js route handler → 后端微服务
```

放行清单在 `apps/web/src/lib/api-proxy.ts` 里；清单之外的路径一律 404，
不做转发（避免把前端变成开放代理）。

## 前置条件

- Kubernetes ≥ 1.25
- Ingress 控制器（默认 `nginx`）
- 后端 `starcon-backend` 已装在同一命名空间
- 前端镜像已推到仓库

## 安装

```sh
helm upgrade --install starcon-web ./infra/helm/starcon-web \
  -n starcon --create-namespace \
  -f values-prod.yaml
```

```yaml
global:
  imageRegistry: registry.example.com/starcon

image:
  repository: starcon/web
  tag: "0.1.0"

backend:
  # 后端 chart 的 fullname —— 见下面「与后端的耦合」
  fullname: starcon-backend

ingress:
  className: nginx
  host: erp.example.com
  tls:
    enabled: true
```

## 与后端的耦合

前端要访问 9 个后端服务，所以要告诉它**后端的 Service 叫什么**。

后端 chart 里每个服务的 Service 名是 `<后端 fullname>-<服务名>`，而 fullname 由
**后端 release 名**决定。所以：

| 后端安装命令 | 后端 fullname | 前端要填 |
| --- | --- | --- |
| `helm install starcon-backend ./infra/helm/starcon-backend` | `starcon-backend` | `starcon-backend`（默认值即可） |
| `helm install prod ./infra/helm/starcon-backend` | `prod-starcon-backend` | `prod-starcon-backend` |
| `helm install starcon-backend ./infra/helm/starcon-backend --set fullnameOverride=starcon-be` | `starcon-be` | `starcon-be` |

后端装完，`helm install` 的输出（NOTES）里会打印实际值，直接抄进前端即可。

地址默认推导成 `http://<fullname>-<服务名>.<ns>.svc.cluster.local:<端口>`。
走 API 网关或跨集群时逐项覆盖：

```yaml
backend:
  urls:
    IDENTITY_URL: "https://api.example.com/identity"
```

## ⚠️ Ingress 必须转发 X-Forwarded-Proto / X-Forwarded-Host

前端有一道**同源校验**：写请求（POST / PUT / DELETE）会比对请求的 `origin` 与
`request.nextUrl.origin`，不一致直接返回 403。

`request.nextUrl` 来自 Next.js 对 `X-Forwarded-Proto` / `X-Forwarded-Host` 的解析。
所以 Ingress **必须**正确转发这两个头，否则所有写操作都会 403。

nginx-ingress 默认就带这两个头 —— **不要**用注解把它们覆盖掉。

## TLS

会话 Cookie 的 `secure` 标记取决于 `request.nextUrl.protocol`，走 https 时才会带上。
生产环境请开 TLS：

```yaml
ingress:
  tls:
    enabled: true
    secretName: erp-tls   # 留空则用 <fullname>-tls
```

## 运行时环境变量

| 变量 | 说明 |
| --- | --- |
| `NODE_ENV` | `production` |
| `PORT` | `3000` |
| `HOSTNAME` | `0.0.0.0`（Next.js standalone 取它做监听地址，不给会只绑 127.0.0.1） |
| `NEXT_TELEMETRY_DISABLED` | `1` |
| `IDENTITY_URL` … `PLATFORM_URL` | 9 个后端服务地址，运行时读取 |

这些变量是**运行时**读取的（`process.env[service.env]`），不是构建期烘焙的，
所以同一个镜像可以指向不同的后端地址。

## 探针

走 `/api/health`（Next.js route handler，返回 `{"status":"ok"}`）。

## 安全上下文

`readOnlyRootFilesystem` 保持 `false`：Next.js standalone 会把图片优化缓存写进应用
目录下的 `.next/cache`，只读根文件系统会让它写失败。`/tmp` 挂了 emptyDir。

## 校验

```sh
helm lint ./infra/helm/starcon-web
helm template starcon-web ./infra/helm/starcon-web -n starcon
```

## 排错

| 现象 | 多半是 |
| --- | --- |
| 页面能开，写操作全 403 | Ingress 没转发 `X-Forwarded-Proto` / `X-Forwarded-Host` |
| 页面报「服务暂时无法连接」（503） | 前端到后端的地址不对：查 `backend.fullname` 与后端 Service 名是否一致 |
| 登录后立刻掉线 | `X-Forwarded-Proto` 不是 https → Cookie 的 secure 标记与浏览器不一致 |
| 首屏很慢 | 后端 Pod 没就绪；看前端 readiness 与后端 Pod 状态 |
