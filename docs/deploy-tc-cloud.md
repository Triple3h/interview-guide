# 云服务器部署手册（tc-cloud）

本文记录项目在腾讯云单机上的 Docker Compose 部署方式与运维经验。文中统一使用 SSH 别名 `tc-cloud`（配置在本机 `~/.ssh/config`），服务器地址与密钥不入库（本仓库公开）。

## 环境总览

- 部署目录：服务器上 `/data/docker/interview-guide`。**该目录不是 git 仓库**，源码/产物由本地脚本同步。
- 服务器独有文件（本地仓库没有，任何同步都要排除）：
  - `.env`：生产环境密钥（数据库密码、AI API Key、加密密钥等），参照仓库根的 `.env.example` 编写。
  - `docker-compose.prod.yml`：生产编排，在仓库 `docker-compose.yml` 基础上调整了端口映射、薄构建入口与凭据注入。
- 构建方式：**本地出产物 + 服务器薄构建**。
  - 后端：本地 `./gradlew :app:bootJar`（jar 与 CPU 架构无关）→ 同步为服务器 `app/build/libs/app.jar`；
  - 前端：本地 `pnpm run build` 出 `frontend/dist/` → 同步到服务器；
  - 服务器用 `app/Dockerfile.prod`、`frontend/Dockerfile.prod`（只做 COPY）组装镜像，秒级完成。
  - 完整的多阶段构建 `app/Dockerfile`、`frontend/Dockerfile`（容器内跑 Gradle/pnpm）保留给 CI / 通用场景，服务器日常部署不使用。
- 容器组成：

| 容器 | 镜像来源 | 说明 |
| --- | --- | --- |
| `interview-app` | `app/Dockerfile.prod` | Spring Boot 后端，映射 8080 |
| `interview-frontend` | `frontend/Dockerfile.prod` | Nginx 托管前端产物，映射 443（HTTPS） |
| `interview-postgres` / `interview-redis` / `interview-minio` | 官方镜像 | 常驻基础组件，日常部署不动 |

- 数据全部在 docker named volumes（`postgres_data` / `redis_data` / `minio_data`），重建应用容器不丢数据。

## 统一入口网关（443）

443 由**独立网关容器**占用，interview 前端容器只映射 18080（HTTP），不再自己对外提供 TLS。

- 网关在服务器 `/data/docker/gateway/`（`docker-compose.yml` + `conf.d/default.conf` + `certs/`），仓库里留了一份备份在 `deploy/gateway/`。
- 启停与改配置：`cd /data/docker/gateway && docker compose up -d`；改完配置 `docker exec gateway-nginx nginx -s reload`。
- 它用 **host 网络**，所以能直接 `proxy_pass http://127.0.0.1:18080` 转发给 interview，不必加入任何项目的 docker network；将来接入别的项目同理（各项目的宿主端口）。
- 证书与 interview 共用同一张自签证书（SAN 是 IP，一张通用）。换证书只需替换 `certs/` 下两个文件再 reload。
- 网关对代理做了三处针对性设置：`proxy_buffering off`（否则学习帮手的 SSE 流式会被攒着一次性吐出）、`proxy_read_timeout 3600s`（语音面试单场最长 1 小时）、WebSocket 升级头映射（`/ws/voice-interview/{id}`）。
- `X-Forwarded-For` 用 `$remote_addr` **覆写**（丢弃客户端自带的），这样后端 `RateLimitAspect` 取到的第一段永远是真实来源 IP，既保证按 IP 限流准确、也不能靠伪造 XFF 绕过。
- 宿主机 80 被 `realestate-frontend` 占用，所以网关只监听 443。
- 接入新项目：在 `conf.d/default.conf` 追加带前缀的 location（文件内附注释示例），**同时该项目自己要支持子路径**（vite `base` + router `basename` + API 前缀），否则它的 `/api` 会和别人撞车。

## HTTPS 与证书

- 站点使用**自建 CA 签发的自签证书**（SAN = `IP:159.75.135.213`，10 年有效期至 2036，无需续期）。CA 与私钥存放在本机 `~/.keys/interview-guide-tls/`，部署副本在 `frontend/certs/`（已在 `frontend/.gitignore` 中，不会入库）。
- `frontend/nginx.conf` 用**同一个 server 块同时 `listen 80` 与 `listen 443 ssl`**，`/`、`/api/`、`/ws/` 三处 location 天然共用 —— 刻意不拆成两个块，避免漏配（漏掉 `/ws/` 会让语音面试直接挂掉）。
- `frontend/Dockerfile.prod` 会 `COPY certs /etc/nginx/certs`；`scripts/deploy-tc-cloud.sh` 负责把 `frontend/certs/` 同步到服务器。**换证书只需替换本地文件后重跑一次部署。**
- 安卓 APK 内置了这张 CA（`frontend/android/app/src/main/res/xml/network_security_config.xml` + `res/raw/home_ca.crt`），WebView 不会报证书错误；手机浏览器直接访问会有自签警告，点「继续访问」即可。
- **18080（HTTP）已于 2026-09-19 撤销**，公网只保留 443。要临时恢复：把 `docker-compose.prod.yml` 的 `- "443:443"` 旁边加回 `- "18080:80"` 并 `up -d frontend`（备份见 `docker-compose.prod.yml.bak.before-no18080`）。
- 切换入口后 origin 改变，浏览器 `localStorage` 里的登录 token 不通用，**学员端与管理端各需重新登录一次**。

## 日常部署流程

一条命令：

```bash
scripts/deploy-tc-cloud.sh
```

脚本做的事（均可单独手动执行）：

1. 本地 `./gradlew :app:bootJar`（M 系芯片上秒级）；
2. 本地 `frontend && pnpm run build`；
3. rsync 同步到服务器：
   - bootJar → `app/build/libs/app.jar`（固定名，避开 `*-plain.jar` 变体）；
   - `frontend/dist/`（`--delete` 镜像）、`app/Dockerfile.prod`、`frontend/Dockerfile.prod`、`frontend/nginx.conf`、`docker/`（postgres 初始化脚本，体积小，保证重装后可直接全量启动）；
4. 远程 `docker compose -f docker-compose.prod.yml build app frontend && up -d app frontend`。

服务器地址/目录可用环境变量覆盖：`REMOTE_HOST=xxx REMOTE_DIR=/path scripts/deploy-tc-cloud.sh`。

部署完成后的线上验证由使用者自行进行（容器状态、HTTP 探活、日志等）。

## 备用：完整源码构建（不常用）

多阶段 Dockerfile（容器内 Gradle/pnpm）仍可用：同步整个源码树后 `docker compose -f docker-compose.prod.yml build`。注意此时：

- 服务器访问 `services.gradle.org` 超时，需把 `gradle/wrapper/gradle-wrapper.properties` 的 distributionUrl 指向 `https://mirrors.cloud.tencent.com/gradle`（Maven 依赖已在 `settings.gradle` / `app/build.gradle` 配好阿里云镜像，不受影响）；
- `frontend/Dockerfile` 里 pnpm 版本必须与 `package.json` 的 `packageManager` 字段一致（固定 `pnpm@<版本>`），否则 pnpm 10+ 的包管理器身份校验会报 `ERR_PNPM_PNPM_ENGINE_IDENTITY_UNVERIFIABLE`。

## 已踩过的坑

- **容器时区**：容器默认 UTC，后端 `LocalDateTime`（题目生成任务的时间戳、容器日志时间）会整体偏移 8 小时，前端按本地时区解析后显示成凌晨时间。已在 `app/Dockerfile` / `app/Dockerfile.prod` 固定 `ENV TZ=Asia/Shanghai`。注意：修复前入库的时间戳没有时区信息、不会自动纠正，相关任务重新生成后即为正确时间。
- **rsync 源路径受 shell 当前目录影响**：曾因 shell 工作目录停在 `frontend/`，把前端文件拷到服务器项目根、并 `--delete` 误删服务器后端源码树。任何手写 rsync 一律用绝对路径；`--exclude '.env'` 和 `--exclude 'docker-compose.prod.yml'` 在全量同步时永远不能省（服务器上仅此两份）。
- **服务器构建 Gradle 下载超时**：`Read timed out` 即国内网络问题，按上文备用方案处理；薄构建路径完全不受影响。
- **pnpm 身份校验**：见上，只影响多阶段构建路径。

## 首次部署 / 服务器重装后

1. 服务器上放好 `.env`（参照 `.env.example`，`chmod 600 .env`）与 `docker-compose.prod.yml`（当前内容可从服务器备份 `docker-compose.prod.yml.bak` 恢复，或按仓库 `docker-compose.yml` 调整端口与薄构建入口）；
2. SSH 主机密钥会更换：本地 `ssh-keygen -R <服务器地址>` 清旧记录，重连时与新指纹核对一致后再确认；
3. 恢复公钥登录：控制台重置密码登录后执行 `ssh-copy-id tc-cloud`；
4. 跑一次 `scripts/deploy-tc-cloud.sh`（会同步 `docker/` 初始化脚本并启动应用容器），基础组件用 `docker compose -f docker-compose.prod.yml up -d` 全量启动（postgres/redis/minio 有 healthcheck，app 会等它们就绪）。
