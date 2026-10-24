#!/bin/sh
set -eu
# Only for the isolated localhost measurement runtime; no .env, compose, worker or external services.
BENCHMARK_ROOT="$1"; BENCHMARK_PHASE="$2"; BENCHMARK_JAR="$3"
exec java -Xms256m -Xmx768m -XX:+ExitOnOutOfMemoryError "-Xlog:gc*:file=$BENCHMARK_ROOT/evidence/$BENCHMARK_PHASE/gc.log:time,uptime,level,tags" -Duser.timezone=Asia/Seoul -jar "$BENCHMARK_JAR" \
 --server.address=127.0.0.1 --server.port=29480 --spring.profiles.active=e2e --spring.config.import= --spring.docker.compose.enabled=false \
 "--spring.config.additional-location=file:$BENCHMARK_ROOT/runtime/application-benchmark.properties" \
 --spring.datasource.hikari.maximum-pool-size=10 --cors.allowed-origins=http://127.0.0.1:3440 \
 --analysis.metrics.scheduling-enabled=false --service-usage.metrics.scheduling-enabled=false --work.purge.scheduling-enabled=false --episode.source-purge.scheduling-enabled=false \
 --member.withdrawal.scheduling-enabled=false --world-image.cleanup.scheduling-enabled=false --relationship.processing.scheduling-enabled=false --chat.session-cleanup.scheduling-enabled=false \
 --chat.metrics-enabled=false --chat.recovery-delay-ms=86400000 --analysis.notifications.enabled=false --management.endpoints.web.exposure.include=health,prometheus \
 --management.health.redis.enabled=false --logging.level.org.hibernate.SQL=OFF
