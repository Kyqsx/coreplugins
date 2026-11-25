#!/usr/bin/env bash
# Sobe o Postgres (Docker) e o license-server dentro do Codespace.
# Roda sozinho a cada vez que o Codespace liga (postStartCommand do devcontainer).
# Log: /tmp/start-demo.log
# Secrets do Codespace: DB_PASSWORD, MASTER_KEY e (opcional, para porta pública) GH_PAT.
set -euo pipefail
cd "$(dirname "$0")"

: "${DB_PASSWORD:?defina DB_PASSWORD nos secrets do Codespaces}"
: "${MASTER_KEY:?defina MASTER_KEY nos secrets do Codespaces}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5433/licenses}"
export DB_USER="${DB_USER:-licenses}"

# Evita duas instâncias (ex.: você rodou o script à mão e o Codespace reiniciou)
if pgrep -f "license-server-1.0.0.jar" >/dev/null 2>&1; then
  echo "license-server já está rodando."
  exit 0
fi

echo "[1/4] Esperando o Docker..."
for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 2; done
docker info >/dev/null

echo "[2/4] Subindo o PostgreSQL..."
docker compose up -d postgres
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q postgres)")" = "healthy" ]; do
  sleep 2
done

JAR=license-server/target/license-server-1.0.0.jar
if [ ! -f "$JAR" ] || find license-server/src -newer "$JAR" -type f | grep -q .; then
  echo "[3/4] Compilando o license-server (pode levar alguns minutos na primeira vez)..."
  mvn -q -B -pl license-server -am package -DskipTests
fi

# Deixa a porta 8080 pública em segundo plano (precisa do secret GH_PAT, escopo "codespace").
# Se não der certo, deixe pública uma vez pela aba Ports.
if [ -n "${GH_PAT:-}" ] && [ -n "${CODESPACE_NAME:-}" ] && command -v gh >/dev/null 2>&1; then
  (
    export GH_TOKEN="$GH_PAT"
    for _ in $(seq 1 60); do
      gh codespace ports visibility 8080:public -c "$CODESPACE_NAME" >/dev/null 2>&1 && break
      sleep 5
    done
  ) &
fi

echo "[4/4] Iniciando o license-server na porta 8080..."
exec java -jar "$JAR" --spring.profiles.active=prod
