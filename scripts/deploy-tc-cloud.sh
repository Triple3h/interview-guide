#!/usr/bin/env bash
# ==============================================================================
# tc-cloud 生产部署脚本：本地出产物 + 服务器薄构建
#
# 流程：本地 bootJar / pnpm build → rsync 产物到服务器 → 服务器用
# Dockerfile.prod（仅 COPY）秒级组装镜像 → compose 重启 app/frontend。
# 服务器上不再跑 Gradle/pnpm，也不依赖国内镜像源。
#
# 用法：scripts/deploy-tc-cloud.sh
# 依赖：本机 JDK 25（SDKMAN）、pnpm、rsync；服务器别名 tc-cloud（~/.ssh/config）
# 首次/重装部署：先在服务器放好 .env 与 docker-compose.prod.yml（见 docs/deploy-tc-cloud.md）
# ==============================================================================
set -euo pipefail

REMOTE_HOST="${REMOTE_HOST:-tc-cloud}"
REMOTE_DIR="${REMOTE_DIR:-/data/docker/interview-guide}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 非交互 shell 没有 SDKMAN 的 JAVA_HOME，兜底设置
if [ -z "${JAVA_HOME:-}" ] && [ -d "$HOME/.sdkman/candidates/java/current" ]; then
  export JAVA_HOME="$HOME/.sdkman/candidates/java/current"
fi

cd "$REPO_ROOT"

echo "==> [1/4] 本地构建后端 bootJar"
./gradlew :app:bootJar --no-daemon

echo "==> [2/4] 本地构建前端 dist"
(cd frontend && pnpm run build)

echo "==> [3/4] 同步产物与薄构建文件到 $REMOTE_HOST:$REMOTE_DIR"
# 产物目录首次部署时不存在，先创建
ssh "$REMOTE_HOST" "mkdir -p $REMOTE_DIR/app/build/libs $REMOTE_DIR/frontend/dist"
JAR_PATH=$(ls -t app/build/libs/app-*.jar | head -n 1)
rsync -az -e ssh "$JAR_PATH" "$REMOTE_HOST:$REMOTE_DIR/app/build/libs/app.jar"
rsync -az --delete -e ssh frontend/dist/ "$REMOTE_HOST:$REMOTE_DIR/frontend/dist/"
rsync -az -e ssh app/Dockerfile.prod "$REMOTE_HOST:$REMOTE_DIR/app/Dockerfile.prod"
rsync -az -e ssh frontend/Dockerfile.prod frontend/nginx.conf frontend/.dockerignore "$REMOTE_HOST:$REMOTE_DIR/frontend/"
# docker/（postgres 初始化脚本）体积小，每次带上，保证重装后可直接全量启动
rsync -az --delete -e ssh docker/ "$REMOTE_HOST:$REMOTE_DIR/docker/"

echo "==> [4/4] 远程组装镜像并重启容器"
ssh "$REMOTE_HOST" "cd $REMOTE_DIR && \
  docker compose -f docker-compose.prod.yml build app frontend && \
  docker compose -f docker-compose.prod.yml up -d app frontend"

echo "==> 部署完成，请在服务器上自行验证"
