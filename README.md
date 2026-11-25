# CorePlugins

Estrutura para vender plugins de Minecraft no modelo da yStore: o cliente instala **um único jar (o loader)**, o
servidor dele é autorizado por **IP:porta**, e os plugins vendidos são entregues pelo seu license-server e rodam
**da memória**.

```
protocol/         formato compartilhado (string canônica assinada). Java 8, zero dependências.
api/              coreplugins-api: classe base ProductPlugin. Os produtos compilam contra ela (provided).
loader/           corePlugins.jar: o único jar que o cliente instala em plugins/.
example-product/  produto de exemplo (/exemplo). Copie este módulo para cada plugin que você vender.
license-server/   Spring Boot 3 / Java 21. Autentica IP:porta, assina respostas, guarda e entrega os jars.
```

## Como funciona

1. O cliente coloca `corePlugins.jar` em `plugins/` e **não configura nada**: a URL do license-server vai embutida
   no jar durante o build, o loader não tem `config.yml` e não existe chave de licença. O `config.yml` de cada
   produto (minas, clãs...) tem só as opções daquele plugin.
2. Ao ligar, o loader chama `POST /api/v1/auth`. O servidor descobre o IP **pela conexão** (não pelo corpo) e recebe
   a porta do `server.properties`.
3. Se o IP:porta não estiver cadastrado, o console mostra (e, por padrão, o servidor desliga, como no yPlugins):

   ```
   [corePlugins] Falha na autenticação (not_found: 203.0.113.9:25565), desligando servidor....
   ```
4. O cliente copia esse `IP:porta`, abre a Área do Cliente > Licenças, cola, marca os plugins desse servidor e clica
   em **Atualizar** (na API: `PUT /api/admin/servers/{id}/products`).
5. Ao reiniciar, o servidor responde `ok` com a lista de produtos liberados (id, versão, SHA-256). O loader baixa
   cada jar para a memória, confere o SHA-256 e o carrega. O jar **nunca é gravado no disco** do cliente.
6. A cada 30 min (configurável) o loader revalida. Se um produto deixou de ser liberado (revogado/expirado), ele é
   desabilitado em tempo de execução. **Falha de rede nunca derruba o servidor**: só uma negação *assinada*.

### Segurança do protocolo

- Toda resposta de `/api/v1/auth`, **inclusive as negações**, é assinada com RSA-2048/SHA-256 sobre
  `status|address|issuedAt|nonce|id:versão:sha256;...`.
- O loader checa: assinatura, `nonce` igual ao que enviou (anti-replay) e relógio dentro de ±10 min.
- A chave pública e a URL da API ficam **embutidas no jar do loader**. O cliente não edita nada, então não há como
  apontar o loader para outro servidor nem desligar a verificação por configuração.
- Resposta sem assinatura válida (proxy fora do ar, HTML de erro, servidor falso) é tratada como falha de rede.
  Ninguém consegue forjar um `not_found` para desligar o servidor de um cliente.
- O SHA-256 do jar vem dentro da resposta assinada, então quem interceptar o download não troca o jar.
- `X-Forwarded-For` só é considerado com `license.trust-forwarded-for=true` (use apenas atrás do seu proxy).

## Site e loja

O license-server também serve o site (React + TypeScript, código em `web/`) e a API `/api/store/**`:
cadastro/login (e-mail e senha, PBKDF2), catálogo, carrinho, PIX (Mercado Pago ou simulado), Área do Cliente
(cadastrar IP:porta, trocar IP, marcar plugins → **Atualizar**). Pedido pago vira *direito de uso*; o cliente só consegue
marcar num servidor os plugins que comprou, e é essa marcação que o loader consulta. Para alterar o site:

```
cd web && npm install && npm run build
cp -r dist/* ../license-server/src/main/resources/static/     # depois recompile o license-server
```

(`npm run dev` sobe o site em modo desenvolvimento, apontando a API para `127.0.0.1:8080`.)

### Imagens e vídeo de showcase dos plugins

No painel admin (**Produtos > Gerenciar > Imagens e vídeo**) cada plugin ganha:

- **Galeria de imagens**: PNG, JPG, WebP ou GIF, até 5 MB cada e 10 por plugin. A primeira é a capa do card; use ← → para
  reordenar. As imagens ficam no PostgreSQL (como os jars) e saem em `GET /api/store/products/{id}/images/{imageId}`
  (pública, cacheável). O tipo é conferido pelos bytes do arquivo (SVG não é aceito de propósito).
- **Vídeo do YouTube**: cole o link (`youtube.com/watch?v=…`, `youtu.be/…`, `/shorts/…`) ou só o ID. O servidor guarda só o ID
  de 11 caracteres. No site o player usa `youtube-nocookie.com` e só carrega quando a pessoa clica em ▶.

Cada plugin passa a ter uma página própria, `#/plugin/<id>`: é o link para colocar na descrição do vídeo. A tabela
`product_images` e a coluna `products.youtube_id` são criadas sozinhas pelo `ddl-auto: update`.

## Build

Requisitos: JDK 21 e Maven 3.9+.

```bash
# 1) compile tudo uma vez (o loader sai com uma chave pública de mentira)
mvn clean package -Dlicense.api.url=https://license.seudominio.com.br

# 2) suba o license-server (gera dois pares RSA no primeiro start e grava no PostgreSQL, cifrados; precisa de DB_URL/DB_USER/DB_PASSWORD)
#    MASTER_KEY: gere UMA vez com `openssl rand -base64 32`, guarde num cofre/gerenciador e NUNCA no banco nem no git
MASTER_KEY='...' DB_PASSWORD='sua-senha' ADMIN_EMAIL=voce@seudominio.com ADMIN_PASSWORD='senha-longa-12+' java -jar license-server/target/license-server-1.0.0.jar

# 3) copie as DUAS chaves públicas (atual e próxima) DO SEU servidor para dentro do loader
curl -s http://localhost:8080/api/v1/public-keys > loader/src/main/resources/license-public.key

# 4) recompile o loader (agora com a chave certa e a URL da sua API) -> loader/target/corePlugins.jar
mvn -pl loader -am clean package -Dlicense.api.url=https://license.seudominio.com.br
```

> Faça backup do banco PostgreSQL e **também da `MASTER_KEY`**, separados: as chaves privadas ficam em `server_secrets` cifradas com AES-GCM, então o dump sozinho não as
> revela, mas sem a `MASTER_KEY` ninguém consegue decifrá-las. Se as chaves se perderem, todos os loaders distribuídos deixam de aceitar o servidor e você precisa
> redistribuir um loader novo.

### Rotação da chave de assinatura (sem redistribuir loaders)

O servidor mantém sempre dois pares RSA: o **atual** (assina) e o **próximo** (ainda não assina). O loader embute as duas públicas e aceita qualquer uma. Para trocar
(por vazamento, ou de tempos em tempos): `POST /api/admin/keys/rotate` com `{"confirm":"ROTATE"}` (sessão de admin). O "próximo" vira o atual e um novo "próximo" é gerado.
Em seguida baixe `/api/v1/public-keys` de novo, recompile o loader e distribua nas próximas versões. **Só funciona para loaders que já embutiam o "próximo" antes da rotação**:
loaders feitos só com a chave antiga (`/api/v1/public-key`) quebram. Com várias instâncias do servidor, reinicie as outras depois de rotacionar.

O `api` e o `loader` compilam contra o Spigot API **1.8.8** (`spigot.version` em `api/pom.xml` e `loader/pom.xml`), então o build quebra se algum código usar algo que não existe na 1.8. O jar roda da 1.8 até as versões atuais e exige Java 8 ou mais novo. Os produtos continuam usando o `spigot.version` do `pom.xml` raiz (1.16.5); cada produto é responsável pela própria compatibilidade com 1.8.

## Administração (curl)

Todas as chamadas `/api/admin/**` exigem uma **sessão de administrador** (`Authorization: Bearer <token>`, vale 1 hora).
O token vem do login em duas etapas (e-mail + senha, depois o código de 6 dígitos do app de autenticação). É exatamente
isto que a loja chama depois de uma compra aprovada.

```bash
H=http://localhost:8080
# 1) e-mail + senha -> "challenge" (valido por 5 min)
CH=$(curl -s -H "Content-Type: application/json" -X POST $H/api/admin/auth/login \
  -d '{"email":"voce@seudominio.com","password":"sua-senha"}' | sed -n 's/.*"challenge":"\([^"]*\)".*/\1/p')
# 2) código do app de autenticação -> sessão
read -p "Código 2FA: " CODE
TOKEN=$(curl -s -H "Content-Type: application/json" -X POST $H/api/admin/auth/verify \
  -d "{\"challenge\":\"$CH\",\"code\":\"$CODE\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
A=(-H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json")

# cliente
curl "${A[@]}" -X POST $H/api/admin/customers -d '{"name":"João","email":"joao@exemplo.com"}'

# produto + versão (o jar precisa ter plugin.yml na raiz)
curl "${A[@]}" -X POST $H/api/admin/products -d '{"id":"exemplo","name":"Exemplo","description":"Plugin de teste"}'
curl -H "Authorization: Bearer $TOKEN" -X POST "$H/api/admin/products/exemplo/versions?version=1.0.0" \
     -F file=@example-product/target/example-product-1.0.0.jar

# servidor do cliente: IP:porta + plugins marcados (expiresAt é opcional)
curl "${A[@]}" -X POST $H/api/admin/servers -d '{
  "customerId":1,"ip":"203.0.113.9","port":25565,"label":"Survival",
  "products":[{"productId":"exemplo","expiresAt":null}]}'

# "Atualizar": define exatamente quais plugins rodam neste IP:porta
curl "${A[@]}" -X PUT $H/api/admin/servers/1/products -d '{"products":[{"productId":"exemplo"}]}'

# trocar IP / revogar / reativar
curl "${A[@]}" -X PUT  $H/api/admin/servers/1/address -d '{"ip":"203.0.113.10","port":25565}'
curl "${A[@]}" -X POST $H/api/admin/servers/1/revoke
curl "${A[@]}" -X POST $H/api/admin/servers/1/activate

# listar
curl "${A[@]}" $H/api/admin/servers
```

Nova versão de um produto: suba com outro `version` (versões são imutáveis). Servidores em execução avisam no
console e aplicam na próxima reinicialização.

### Painel de administração

`http://127.0.0.1:8080/#/admin` (link "Administração" no rodapé). Entra com a **conta de administrador** (e-mail e senha) e o código 2FA do app de autenticação; a sessão dura 1 hora e fica só na
aba do navegador. Permite: criar/editar produtos (preço, categoria, comando, ocultar da loja), enviar jars de versões e
escolher a mais recente, ver pedidos, clientes e servidores, suspender/reativar servidores e liberar um plugin de cortesia
para um cliente. Depois de 8 tentativas erradas (senha ou código) o login é bloqueado por 10 min. Em produção, restrinja `/api/admin/**` por
IP/VPN no proxy. Os `curl` do `LOCAL.md` continuam funcionando.

### Pasta de dados dos produtos

Como na yPlugins, ao habilitar um produto o loader cria `plugins/<NomeDoProduto>/` já com **todos os arquivos padrão
do jar** (`config.yml`, `messages.yml`, `menus/...`, pastas vazias etc.). Arquivos que já existem nunca são
sobrescritos, então as edições do cliente ficam. Ficam de fora: `.class`, `META-INF/`, `plugin.yml` e tudo dentro de
`ky-internal/` (use essa pasta para recursos internos que o cliente não deve ver). O `database.db` e afins o próprio
produto cria. O loader não cria pasta nem config próprios.

## Criar / converter um produto (padrão do yPlugins)

O plugin registrado no servidor é o **loader**. A classe principal do produto **não** é um `JavaPlugin`.

| Antes (standalone)                          | Depois (produto)                                              |
|---------------------------------------------|---------------------------------------------------------------|
| `extends JavaPlugin`                        | `extends ProductPlugin` (`br.com.coreplugins.api`)               |
| `registerEvents(l, plugin)`                 | `registerEvents(l)` ou `registerEvents(l, getLoader())`       |
| `runTask(plugin, ...)` / `BukkitRunnable`   | `runTask(getLoader(), ...)` / `.runTaskTimer(getLoader(), …)` |
| `getCommand("x")`                           | igual; o comando vem do `plugin.yml` do produto               |
| init pesado no `onEnable`                   | `runTaskLater(getLoader(), this::register, 1L)`               |
| `getDataFolder()` / `getConfig()` / `saveDefaultConfig()` | iguais (pasta `plugins/<Nome>/`)                |

Checklist:

1. `pom.xml`: `coreplugins-api` com `<scope>provided</scope>`. **Nunca** faça shade dela no produto.
2. `plugin.yml` dentro do jar: `name`, `main`, `version`, `commands`, `permissions`, `softdepend`/`depend`.
3. Comandos: declare em `commands:` e ligue o executor com `getCommand("x").setExecutor(...)`. Se a classe principal
   implementar `CommandExecutor`/`TabCompleter`, o loader liga sozinho.
4. Use `registerEvents(listener)` da `ProductPlugin` para que os listeners sejam removidos se a licença for revogada.
5. Cancele suas próprias tasks no `onDisable()` (o Bukkit não deixa o loader cancelar só as de um produto).
6. `softdepend` do produto precisa de plugins que carreguem antes? Liste-os também no `plugin.yml` **do loader**.
   Os produtos só são habilitados 1 tick depois do start, então os plugins já estão carregados.
7. Bibliotecas próprias do produto podem ir dentro do jar (as classes do produto são carregadas *child-first*);
   Java, Bukkit/Spigot/Paper, a API e o loader sempre vêm do servidor.

Veja `example-product/` (`ExampleProduct.java`, `plugin.yml`, `config.yml`).

## Produção (checklist)

- API **atrás de HTTPS** (Nginx/Caddy/Cloudflare). Com proxy próprio: `TRUST_FORWARDED_FOR=true`.
- Painel admin: conta própria + **2FA obrigatório**. Crie o primeiro admin com `ADMIN_EMAIL` e `ADMIN_PASSWORD` (mín. 12
  caracteres) e faça o **primeiro login logo em seguida** para cadastrar o app de autenticação (até confirmar, quem souber a senha
  conseguiria cadastrar o 2FA dele). Depois tire `ADMIN_PASSWORD` do ambiente. Nunca use o perfil `local` em produção
  (ele desliga o 2FA) nem `ADMIN_REQUIRE_2FA=false`.
- Perdeu a senha ou o celular do 2FA? Suba uma vez com `ADMIN_RESET=true` + `ADMIN_EMAIL` + `ADMIN_PASSWORD` novos: troca a senha e
  zera o 2FA para recadastrar (derruba as sessões). Depois remova `ADMIN_RESET`.
- Ações de escrita no painel são registradas no log (`coreplugins.admin.audit`: quem, método e rota).
- `/api/admin/**` acessível só pela loja/VPN/IP fixo no proxy: o login protege, mas não precisa ficar exposto à internet.
- Rate limit por IP em `/api/v1/auth` e `/api/v1/products/**/download` no Nginx.
- PostgreSQL é o único banco: `DB_URL`, `DB_USER`, `DB_PASSWORD` (padrão `localhost:5432/licenses`). Troque
  `ddl-auto: update` por Flyway/Liquibase antes de ter clientes de verdade.
- Pagamento real: `PAYMENT_MODE=mercadopago`, `MP_ACCESS_TOKEN`, `PUBLIC_URL` (HTTPS). Veja `LOCAL.md`.
- `MASTER_KEY` obrigatória no perfil `prod` (o servidor recusa subir sem ela). Perdeu a `MASTER_KEY`? Os segredos do banco ficam ilegíveis: trate como perda das chaves.
- Backup do PostgreSQL: ele guarda contas, pedidos, os jars dos produtos (`product_jars`) e os segredos do servidor (`server_secrets`: pares RSA e o segredo que assina as sessões, cifrados com a `MASTER_KEY`). Guarde o dump e a `MASTER_KEY` em lugares diferentes.
- Sessões revogáveis: `POST /api/store/logout` (site) e `POST /api/admin/auth/logout` (painel) derrubam **todos** os tokens da conta; `PUT /api/store/password` troca a senha e faz o mesmo.
- Migrando de uma instalação com arquivos? Na primeira subida num banco vazio, passe `SEED_RSA_PRIVATE_KEY`, `SEED_RSA_PUBLIC_KEY` e `SEED_TOKEN_SECRET` com o conteúdo de `data/keys/private.key`, `public.key` e `token.secret`, e reenvie os jars pelo painel. Depois remova essas variáveis.
- Ofusque o `corePlugins.jar` e os jars dos produtos (ProGuard/Allatori).

## Limites (leia)

- **Nenhuma licença é inquebrável**: o cliente controla a JVM dele e pode, por exemplo, despejar as classes da memória.
  O ganho real é que o jar do produto não existe no disco e só é entregue a um IP:porta autorizado, e que
  crackear o loader sozinho não dá acesso ao código.
- A licença é por **IP:porta**. Quem muda de host precisa atualizar o cadastro (Área do Cliente ou `PUT .../address`).
  Hospedagens que expõem a porta pública diferente da do `server.properties` precisam cadastrar o que o console mostra.
- Só existe loader para **Bukkit/Spigot/Paper**. BungeeCord e Velocity ainda não foram implementados.
- **Servidor novo não é desligado:** se o servidor ainda não tem nenhuma pasta de produto gerada pelo loader (instalação nova do
  corePlugins, IP ainda não cadastrado), a negação só gera um aviso no console. O desligamento (na partida ou após a carência) só vale
  para quem já usa plugins da loja. O loader guarda o registro em `plugins/corePlugins/installed.txt` (interno, não é configuração).
- Se a licença for negada com o servidor já ligado, os produtos são desligados e o servidor desliga após uma carência
  (`denied-grace-minutes`, padrão 5, em `pluginstore.properties`); uma checagem bem-sucedida cancela o desligamento.
- **Versão por servidor:** na Área do Cliente o cliente escolhe, plugin a plugin, "sempre a mais recente" ou uma versão
  específica (campo `version` em `server_products`; também aceito em `PUT /api/admin/servers/{id}/products`).
  A verificação automática (a cada 30 min) liga/desliga plugins mas **não troca código em execução**: ela só avisa no
  console. A troca de versão é aplicada no `/coreplugins reload` (desliga o produto e carrega o jar novo, sem reiniciar o
  servidor) ou na próxima reinicialização. Plugins que mantêm tasks/conexões próprias podem preferir o restart.
- Revogação em tempo de execução desabilita o produto (`onDisable`, comandos e listeners removidos), mas tasks do
  Bukkit que o produto não cancelou continuam. Um restart é o único corte 100% limpo.

## Migração do nome antigo (Coreplugins / kyPlugins / kyEconomias)
O projeto passou a se chamar **CorePlugins**. O que muda para quem já tinha instalado:

| Antes | Agora |
|---|---|
| `kyPlugins.jar`, plugin `kyPlugins` | `corePlugins.jar`, plugin `corePlugins` |
| comando `/kyplugins` (alias `/kyp`) | `/coreplugins` (alias `/cpl`) |
| permissão `kyplugins.admin` | `coreplugins.admin` (atualize os grupos no seu plugin de permissões) |
| pasta `plugins/kyPlugins/` | `plugins/corePlugins/` (**movida sozinha** na primeira inicialização) |
| `plugins/kyEconomias/` | `plugins/coreEconomias/` (**movida sozinha** pelo loader ao carregar o produto) |
| pacote Java `br.com.coreplugins` | `br.com.coreplugins` (produtos antigos precisam ser recompilados contra o `coreplugins-api`) |
| canal Redis padrão `kyeconomias` | `coreeconomias` (use o mesmo canal em todos os servidores da rede) |

Continuam iguais: o prefixo `ky-internal/` dentro dos jars dos produtos, as permissões `economias.*` e as chaves RSA (agora
no banco). Os produtos já enviados ao license-server (jars compilados com o pacote antigo) devem ser
recompilados e reenviados (`local/seed.sh` refaz o produto de exemplo).

