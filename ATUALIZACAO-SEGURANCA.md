# Atualização de segurança: login de admin + 2FA

Copie estas pastas por cima do seu `coreplugins/` (mesmos caminhos). Depois:

1. **Apague** o bundle antigo: `license-server/src/main/resources/static/assets/index-BeFg_o5c.js`
   (o novo é `index-Bsrwp5aQ.js`; o `index.html` já aponta para ele).
2. Recompile: `mvn clean package` (o `license-server/target/` do seu zip é da versão antiga).
3. Suba o servidor com as variáveis (a senha precisa de 12+ caracteres):
   `ADMIN_EMAIL=voce@seudominio.com ADMIN_PASSWORD='...' java -jar license-server/target/license-server-1.0.0.jar`
4. Abra `/#/admin`, entre com e-mail e senha e **cadastre o app de autenticação na hora** (primeiro login).
   Depois remova `ADMIN_PASSWORD` do ambiente.
5. O `ADMIN_TOKEN` não existe mais. Revogue qualquer cópia dele (estava em LOCAL.md, seed.* e application-local.yml).

Perdeu a senha ou o celular? Suba uma vez com `ADMIN_RESET=true` + `ADMIN_EMAIL` + `ADMIN_PASSWORD` novos.

Em produção: nunca use o perfil `local` nem `ADMIN_REQUIRE_2FA=false` (desligam o 2FA).

---

# Atualização: somente PostgreSQL (sem H2, sem arquivos)

O license-server agora guarda **tudo no PostgreSQL**: contas, pedidos, licenças, os **jars dos produtos**
(`product_jars`) e os **segredos do servidor** (`server_secrets`: par RSA e segredo das sessões). `data/`, `products/`
e o H2 deixaram de existir.

1. Suba o banco: `DB_PASSWORD='senha-forte' docker compose up -d postgres` (ou use o seu PostgreSQL via `DB_URL`/`DB_USER`/`DB_PASSWORD`).
2. **Já distribuiu loaders?** Na primeira subida, passe as chaves antigas para o servidor reaproveitá-las
   (senão ele gera um par novo e os loaders atuais deixam de aceitar o servidor):
   `SEED_RSA_PRIVATE_KEY="$(cat data/keys/private.key)" SEED_RSA_PUBLIC_KEY="$(cat data/keys/public.key)" SEED_TOKEN_SECRET="$(cat data/keys/token.secret)"`
   Depois que subiu uma vez, remova essas variáveis.
3. Reenvie os jars dos produtos pelo painel admin (ou `local/seed.sh`). Contas do H2 antigo **não** são migradas
   automaticamente.
4. Apague `license-server/data/` e `license-server/products/` depois de confirmar que tudo funciona.
5. O `pg_dump` passa a conter a chave privada: guarde backups com o mesmo cuidado.

---

# Atualização: segredos cifrados, rotação de chave e sessões revogáveis

## 1. Segredos cifrados no banco (`MASTER_KEY`)

Os segredos de `server_secrets` (pares RSA e segredo das sessões) agora são cifrados com AES-256-GCM. A chave vem **só do ambiente**.

1. Gere uma vez: `openssl rand -base64 32`. Guarde fora do repositório e fora do banco, e faça backup separado do dump.
2. Suba o servidor com `MASTER_KEY='...'`. Segredos que já estavam em texto puro são cifrados automaticamente na primeira leitura (`enc:v1:...` na coluna `secret_value`).
3. No perfil `prod` o servidor **não sobe** sem `MASTER_KEY`. Sem o perfil, ele sobe e avisa que está em texto puro (só desenvolvimento).
4. Não troque a `MASTER_KEY` depois: ainda não existe comando para recifrar, e os valores gravados com a antiga ficam ilegíveis (o servidor para com um erro claro).

## 2. Rotação de chave sem redistribuir loaders

O servidor agora mantém dois pares RSA (`rsa-keypair` = atual, `rsa-keypair-next` = próximo; o segundo é criado sozinho na próxima subida). O loader aceita várias chaves públicas no `license-public.key`, uma por linha.

1. `curl -s http://SEU-SERVIDOR/api/v1/public-keys > loader/src/main/resources/license-public.key` e recompile o loader. **Loaders já distribuídos só com a chave antiga não têm a "próxima"**: eles não sobrevivem a uma rotação.
2. Para rotacionar: `POST /api/admin/keys/rotate` com `{"confirm":"ROTATE"}` (sessão de admin). A "próxima" passa a assinar; uma nova "próxima" é gerada.
3. Depois da rotação, baixe `/api/v1/public-keys` de novo e use na próxima build do loader.

## 3. Sessões revogáveis

Cada conta tem um número de "versão de sessão" gravado nos tokens. Subir o número derruba todos os tokens da conta, inclusive os de outros navegadores:

- `POST /api/store/logout` (site) e `POST /api/admin/auth/logout` (painel): os botões "Sair" do front já chamam essas rotas.
- `PUT /api/store/password` com `{"currentPassword","newPassword"}`: troca a senha, revoga as sessões antigas e devolve uma sessão nova.
- `ADMIN_RESET` também revoga as sessões do admin.
- Contas de admin e de cliente são a mesma conta: sair do painel também derruba o login dela no site.
- **Todos os tokens emitidos antes desta atualização deixam de valer** (formato novo): todo mundo precisa entrar de novo uma vez.
- A coluna `session_version` é criada sozinha (`ddl-auto: update`); contas antigas valem 0.
- Recompile o front: o bundle novo é `index-VMBtw5PO.js` e o `index.html` já aponta para ele. Apague `static/assets/index-Bsrwp5aQ.js`.
