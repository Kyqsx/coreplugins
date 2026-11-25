#!/usr/bin/env bash
# Publica o plugin de teste "Exemplo" na loja (produto + jar v1.0.0).
# Rode a partir da pasta coreplugins, com o license-server já no ar (perfil local). Depois: http://127.0.0.1:8080/
# Precisa de ADMIN_EMAIL e ADMIN_PASSWORD (os mesmos com que o servidor foi iniciado).
set -e
H=http://127.0.0.1:8080
: "${ADMIN_EMAIL:?Defina ADMIN_EMAIL (o mesmo do servidor)}"
: "${ADMIN_PASSWORD:?Defina ADMIN_PASSWORD (o mesmo do servidor)}"
JAR=example-product/target/example-product-1.0.0.jar
[ -f "$JAR" ] || { echo "Falta $JAR. Rode: mvn clean package"; exit 1; }
echo "-- login de admin"
RES=$(curl -sS -H "Content-Type: application/json" -X POST $H/api/admin/auth/login \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}")
TOKEN=$(printf '%s' "$RES" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "Login falhou: $RES"; echo "(O seed só funciona com o perfil local, que dispensa o 2FA. Em produção use o painel.)"; exit 1; }
T="Authorization: Bearer $TOKEN"
J="Content-Type: application/json"
echo "-- produto";  curl -sS -H "$T" -H "$J" -X POST $H/api/admin/products -d @local/product.json; echo
echo "-- jar v1.0.0"; curl -sS -H "$T" -X POST "$H/api/admin/products/exemplo/versions?version=1.0.0" -F "file=@$JAR"; echo
echo "Pronto. Abra http://127.0.0.1:8080/ , crie uma conta e compre (modo teste)."
