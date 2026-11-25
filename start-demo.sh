#!/usr/bin/env bash
# Sobe o Postgres (Docker) e o license-server dentro do Codespace.
# Requer os secrets DB_PASSWORD e MASTER_KEY (Settings > Codespaces > Secrets no GitHub).
set -euo pipefail
cd "$(dirname "$0")"

: "${DB_PASSWORD:?defina DB_PASSWORD nos secrets do Codespaces}"
: "${MASTER_KEY:?defina MASTER_KEY nos secrets do Codespaces}"
export DB_URL="${DB_URL:-jdbc:postgresql://localhost:5433/licenses}"
export DB_USER="${DB_USER:-licenses}"

echo "[1/3] Esperando o Docker..."
for _ in $(seq 1 30); do docker info >/dev/null 2>&1 && break; sleep 2; done
docker info >/dev/null

echo "[2/3] Subindo o PostgreSQL..."
docker compose up -d postgres
until [ "$(docker inspect -f '{{.State.Health.Status}}' "$(docker compose ps -q postgres)")" = "healthy" ]; do
  sleep 2
done

JAR=license-server/target/license-server-1.0.0.jar
if [ ! -f "$JAR" ] || find license-server/src -newer "$JAR" -type f | grep -q .; then
  echo "[3/3] Compilando o license-server (pode levar alguns minutos na primeira vez)..."
  mvn -q -B -pl license-server -am package -DskipTests
fi

echo "Iniciando o license-server na porta 8080..."
exec java -jar "$JAR" --spring.profiles.active=prod
