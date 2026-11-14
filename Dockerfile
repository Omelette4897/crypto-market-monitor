# Vueの型チェックと本番ビルド。VITE_*には公開可能な値だけを渡す。
FROM node:24-bookworm-slim AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
# Windows側のnode_modulesをLinux側のnpm ci結果へ上書きしない。
COPY frontend/index.html frontend/tsconfig.json frontend/vite.config.ts ./
COPY frontend/src/ ./src/
ARG VITE_GITHUB_REPOSITORY_URL=https://github.com/Omelette4897/crypto-market-monitor
RUN npm run build

# Maven WrapperでJavaをビルドし、Vueを同じJARに同梱して配信を検証する。
FROM eclipse-temurin:21-jdk-noble AS backend-build
# Wrapper 3.3.4はunzipがないとtar.gzへ切り替わり、固定したZIPのSHA-256と不一致になる。
# ZIPの検証を維持するため、ビルド用段階にだけ展開ツールを追加する。
RUN apt-get update && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /build/backend
COPY backend/pom.xml backend/mvnw ./
COPY backend/.mvn/ ./.mvn/
COPY backend/src/ ./src/
COPY --from=frontend-build /build/frontend/dist ./target/frontend-dist
RUN chmod +x mvnw && ./mvnw --batch-mode --no-transfer-progress -Pweb-bundle verify

# 実行時にはNode.js・Maven・ソース・テストを含めない。
FROM eclipse-temurin:21-jre-noble AS runtime
RUN groupadd --gid 10001 application && useradd --uid 10001 --gid application --no-create-home application
WORKDIR /app
COPY --from=backend-build --chown=10001:10001 /build/backend/target/crypto-market-monitor-0.0.1-SNAPSHOT.jar ./application.jar
USER 10001:10001
ENV SPRING_PROFILES_ACTIVE=prod
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:InitialRAMPercentage=10.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
# exec形式でJavaへSIGTERMを届け、既存の収集終了待機を働かせる。
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
