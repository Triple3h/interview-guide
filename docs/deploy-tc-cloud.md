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
| `interview-frontend` | `frontend/Dockerfile.prod` | Nginx 托管前端产物，映射 18080 |
| `interview-postgres` / `interview-redis` / `interview-minio` | 官方镜像 | 常驻基础组件，日常部署不动 |

- 数据全部在 docker named volumes（`postgres_data` / `redis_data` / `minio_data`），重建应用容器不丢数据。

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
