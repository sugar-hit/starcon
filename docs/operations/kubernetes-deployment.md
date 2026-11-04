# Kubernetes 部署

starcon 用 Kubernetes 部署，部署件是 `infra/helm/` 下的两个 Helm chart：

| chart | 部署什么 |
| --- | --- |
| [`starcon-backend/`](../infra/helm/starcon-backend/) | 9 个 Java Spring Boot 微服务（identity / catalog / channel / warehouse / trade / crm / finance / reporting / platform） |
| [`starcon-web/`](../infra/helm/starcon-web/) | Next.js 15 前端（服务端代理 9 个后端服务） |

两个 chart 的详细参数与排错见各自的 README。本文件只讲**部署顺序与前置条件**。

## 架构前提

- **浏览器不直连后端。** 浏览器只跟前端说话，前端在服务端做代理
  （`apps/web/src/lib/api-proxy.ts` 的白名单放行）。后端的 9 个 Service 全是
  ClusterIP，默认不对外暴露。
- **每个服务独占一个 PostgreSQL 库。** 禁止跨服务访问对方的库（AGENTS.md 模块边界纪律）。
  chart **不内置** PostgreSQL —— 库由 Terraform / 云侧托管实例提供。
- **服务之间走集群内 DNS**，不共享数据库、不走跨库 SQL。
- **密钥一律走 secret manager。** chart 声明 ExternalSecret，ESO 同步成 k8s Secret，
  明文不入仓库（AGENTS.md 安全条款）。

## 前置条件清单

- [ ] Kubernetes ≥ 1.25
- [ ] 外部托管 PostgreSQL 16，**9 个库 + 9 个运行角色 + 1 个迁移角色**已建好
- [ ] External Secrets Operator 已装，SecretStore（如 `ClusterSecretStore/vault`）可用
- [ ] Ingress 控制器已装（默认 nginx）
- [ ] StorageClass 已就绪（catalog / channel / reporting / platform 需要 PVC）
- [ ] 10 个镜像已构建并推到仓库（9 个后端 + 1 个前端）

### 数据库

运行角色与迁移角色必须分开（见 `docs/operations/catalog-local-development.md`）：
运行角色不是 owner / superuser / BYPASSRLS —— RLS 是多租户隔离的兜底，
运行角色绕过 RLS 等于把隔离关掉。

| 服务 | 库 | 运行角色 |
| --- | --- | --- |
| identity | starcon_identity | identity_app |
| catalog | starcon_catalog | catalog_app |
| channel | starcon_channel | channel_app |
| warehouse | starcon_warehouse | warehouse_app |
| trade | starcon_trade | trade_app |
| crm | starcon_crm | crm_app |
| finance | starcon_finance | finance_app |
| reporting | starcon_reporting | reporting_app |
| platform | starcon_platform | platform_app |

迁移角色默认 `starcon_migrator`（`postgres.migrationUser` 可改）。

### 密钥

每个服务三个键 + channel 多一个：

| Secret key | 远端路径 | 用途 |
| --- | --- | --- |
| `db-password` | `{service}.db.password` | 运行账号密码 |
| `db-migration-password` | `postgres.migrator.password` | Flyway 迁移账号密码 |
| `internal-key` | `internal.key` | 服务间授权密钥 |
| `channel-webhook-signing-key` | `channel.webhook.signing.key` | 渠道回调签名主密钥（仅 channel，32 字节 base64） |

`INTERNAL_KEY` 与 `CHANNEL_WEBHOOK_SIGNING_KEY` **没有默认值**，缺失时服务起不来。

## 镜像构建

后端镜像按服务分别构建（`Dockerfile.backend` 收 `SERVICE` build arg）：

```sh
for s in identity catalog channel warehouse trade crm finance reporting platform; do
  docker build -f deploy/docker/Dockerfile.backend \
    --build-arg SERVICE="$s" \
    -t "registry.example.com/starcon/$s:0.1.0-SNAPSHOT" .
  docker push "registry.example.com/starcon/$s:0.1.0-SNAPSHOT"
done

docker build -f deploy/docker/Dockerfile.web \
  -t "registry.example.com/starcon/web:0.1.0" .
docker push "registry.example.com/starcon/web:0.1.0"
```

镜像名约定 `starcon/<服务名>:<tag>`，与 chart 的 `global.imageRegistry` +
`image.repository` 拼出来的一致。`chart.appVersion` 默认 `0.1.0-SNAPSHOT`
（= `backend/pom.xml` 的 version），所以 `tag` 留空就是这个值。

> `Dockerfile.backend` **不含版本号** —— 产物 jar 由构建时定位
> （`target/` 下恰好一个 `<服务名>-*.jar`），改 `backend/pom.xml` 的 `<version>`
> 不会让镜像构建失败。镜像 tag 由 CI 决定，但要**同步改 `chart.appVersion`**，
> 否则 chart 会去拉旧 tag。
>
> 网络受限（Docker Hub 不可达）时，按 `deploy/scripts/catalog-dev.sh` 里的
> `rewrite_proxy` 把代理回环地址改写成 `host.docker.internal`，再作为 build arg 传入：
> `--build-arg HTTP_PROXY=... --build-arg HTTPS_PROXY=...`。

## 部署顺序

**先后端，后前端** —— 前端启动后要立刻能连上 9 个后端。

```sh
# 1. 建命名空间
kubectl create namespace starcon

# 2. 后端
helm upgrade --install starcon-backend ./infra/helm/starcon-backend \
  -n starcon \
  --set global.imageRegistry=registry.example.com/starcon \
  --set postgres.host=pg-starcon.internal.svc.cluster.local \
  --set postgres.sslMode=verify-full \
  --set web.publicUrl=https://erp.example.com \
  -f values-prod.yaml

# 3. 等后端就绪
kubectl wait --for=condition=ready pod \
  -n starcon -l app.kubernetes.io/instance=starcon-backend --timeout=600s

# 4. 前端
helm upgrade --install starcon-web ./infra/helm/starcon-web \
  -n starcon \
  --set global.imageRegistry=registry.example.com/starcon \
  --set image.tag=0.1.0 \
  --set ingress.host=erp.example.com \
  --set ingress.tls.enabled=true \
  -f values-prod.yaml
```

第 4 步的 `backend.fullname` 用默认值 `starcon-backend` 即可 ——
它对应第 2 步的 release 名。**后端换了 release 名就要同步改这一项**，
否则前端会指向不存在的 Service（表现为页面上所有接口 503「服务暂时无法连接」）。
后端安装输出的 NOTES 里会打印实际值。

## 验证

```sh
# Pod 全就绪
kubectl get pods -n starcon

# 9 个后端 Service 都在
kubectl get svc -n starcon -l app.kubernetes.io/part-of=starcon

# 密钥都同步出来了（每个 3 个键，channel 4 个）
kubectl get secret -n starcon -l app.kubernetes.io/part-of=starcon

# 前端探针
kubectl run curl --rm -it --restart=Never -n starcon --image=curlimages/curl -- \
  curl -sS http://starcon-web.starcon.svc.cluster.local:3000/api/health
```

前端健康检查返回 `{"status":"ok",...}`。

## 升级

```sh
helm upgrade starcon-backend ./infra/helm/starcon-backend -n starcon -f values-prod.yaml
helm upgrade starcon-web ./infra/helm/starcon-web -n starcon -f values-prod.yaml
```

数据库迁移默认由应用在启动时自己跑（Flyway）。要改成「先迁移、后发版」：

```yaml
common:
  migration:
    enabled: true
```

打开后 chart 会创建 9 个 hook Job，按 `identity → catalog → channel → warehouse →
trade → crm → finance → reporting → platform` 的顺序执行，任一失败即中止发布。

> 跨库变更按向后兼容顺序发布，不承诺 9 个库一个事务回滚。某个服务的迁移成功不代表
> 其它服务也成功 —— 失败时读该服务的 Job 日志，不要重跑整个发布。

## 回滚

```sh
helm rollback starcon-backend <revision> -n starcon
helm rollback starcon-web <revision> -n starcon
```

⚠️ Helm 回滚只回滚 K8s 资源，**不回滚数据库**。Flyway 迁移一旦执行就不会自动撤销
（Flyway 不提供 down migration）。回滚前先确认目标版本的代码与已迁移的 schema 兼容。

## 这套部署件**不**包含的东西

| 项 | 原因 / 去处 |
| --- | --- |
| PostgreSQL | 外部托管，库由 Terraform 侧建（`infra/terraform/envs/`） |
| Ingress 控制器 | 集群基建，不归应用 chart 管 |
| External Secrets Operator | 同上 |
| 独立的 worker 部署 | 后端的定时任务跑在各自服务的 `@Scheduled` 里（`@EnableScheduling`），没有独立 worker 进程；`apps/worker`、`workers/*` 目前都是空占位 |
| ArgoCD / Kustomize | `infra/argocd/`、`infra/k8s/` 是另一条部署路径的占位，当前以 Helm 为准 |

## 相关文档

- [商品中心本地开发](./catalog-local-development.md) —— 本地起服务、SQL 迁移、角色约定
- [多租户与 RLS](../architecture/multi-tenancy.md) —— 为什么运行角色不能是 superuser
- [ADR-0002 多租户策略](../decisions/0002-multi-tenancy-strategy.md)
