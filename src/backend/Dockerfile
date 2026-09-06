# 勤怠管理システムのバックエンド。
#
# マルチステージにする。ビルドに要る JDK・Gradle・依存キャッシュを実行時の像に
# 残すと、300MB 以上の余分と、攻撃面（コンパイラとシェルの道具一式）が付いてくる。

# ---------- ビルド ----------
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /build

# ★ 依存の解決を先に済ませる。ソースだけを変えたときに、この層が再利用される。
#   gradlew と wrapper のプロパティを先に入れるのはそのため
COPY gradlew gradlew.bat ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew --version --no-daemon

COPY settings.gradle.kts build.gradle.kts gradle.properties ./
COPY src ./src

# ★ テストは走らせない。統合テストは実物の PostgreSQL を要求する（H2 は使わない。
#   落とし穴 6）ので、ビルドの中では成立しない。テストは CI かローカルで通す
RUN ./gradlew bootJar --no-daemon -x test

# ---------- 実行 ----------
FROM eclipse-temurin:21-jre AS runtime

# ★ root で動かさない。アプリが読むべきものは jar と環境変数だけである
RUN groupadd --system --gid 1001 kintai \
 && useradd --system --uid 1001 --gid kintai --create-home kintai
WORKDIR /app

COPY --from=builder --chown=kintai:kintai /build/build/libs/*.jar app.jar
USER kintai

# ★ コンテナのメモリ上限を JVM に教える。教えないと物理メモリの 1/4 を
#   ヒープに取ろうとして、cgroup の上限で OOMKill される
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
ENV SPRING_PROFILES_ACTIVE=prod

# ★ タイムゾーンは設定しない。
#   ドメインは壁掛け時計時刻を扱い、変換は BusinessZone（Asia/Tokyo）に集約している
#   （アーキテクチャ設計書 6.3）。コンテナの TZ に依存させると、
#   その集約が意味を失う

EXPOSE 8080

# ★ health は未認証で 200 を返す（SecurityConfig で明示的に開けてある）。
#   開いていないとここが永久に失敗する
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=5 \
  CMD ["sh", "-c", "wget -qO- http://127.0.0.1:8080/actuator/health/readiness | grep -q UP"]

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
