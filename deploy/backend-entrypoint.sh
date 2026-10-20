#!/bin/sh
set -eu
# Preserve the existing operator-owned JAVA_OPTS word splitting. Do not eval it.
set -f

case "${BACKEND_TRACING_ENABLED:-false}" in
  false)
    exec java ${JAVA_OPTS:-} -jar /app/app.jar "$@"
    ;;
  true)
    ;;
  *)
    echo 'BACKEND_TRACING_ENABLED must be true or false.' >&2
    exit 2
    ;;
esac

# Only this deployment's private OTLP/HTTP contract is accepted.
# Credentials, query strings, fragments, public IPs and alternate ports are rejected.
if ! printf '%s\n' "${BACKEND_TRACING_OTLP_ENDPOINT:-}" | awk '
  /^http:\/\/[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+:4318\/v1\/traces$/ {
    value = $0
    sub(/^http:\/\//, "", value)
    sub(/:4318\/v1\/traces$/, "", value)
    split(value, octet, ".")
    for (i = 1; i <= 4; i++) {
      if (octet[i] > 255 || octet[i] !~ /^(0|[1-9][0-9]*)$/) exit 1
    }
    if (octet[1] == 10 ||
        (octet[1] == 172 && octet[2] >= 16 && octet[2] <= 31) ||
        (octet[1] == 192 && octet[2] == 168)) valid = 1
  }
  END { if (!valid || NR != 1) exit 1 }
'; then
  echo 'Tracing requires http://<RFC1918 IPv4>:4318/v1/traces.' >&2
  exit 2
fi

for tracing_file in /app/otel/opentelemetry-javaagent.jar \
  /app/otel/catchhole-tracing-privacy.jar /app/otel/agent-prod.properties; do
  if [ ! -f "$tracing_file" ] || [ ! -r "$tracing_file" ]; then
    echo 'Required bundled tracing artifact is missing; refusing unsanitized startup.' >&2
    exit 2
  fi
done
tracing_instance="$(cat /proc/sys/kernel/random/uuid)"

exec java ${JAVA_OPTS:-} \
  -javaagent:/app/otel/opentelemetry-javaagent.jar \
  -Dotel.javaagent.extensions=/app/otel/catchhole-tracing-privacy.jar \
  -Dotel.javaagent.configuration-file=/app/otel/agent-prod.properties \
  "-Dotel.exporter.otlp.traces.endpoint=$BACKEND_TRACING_OTLP_ENDPOINT" \
  "-Dotel.resource.attributes=deployment.environment.name=prod,service.instance.id=$tracing_instance" \
  -jar /app/app.jar "$@"
