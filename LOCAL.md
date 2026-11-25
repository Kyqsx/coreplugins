# Rodando tudo em localhost

Os valores já vêm gerados e preenchidos. Você só precisa compilar, subir o servidor, cadastrar e ligar o Minecraft.

| Valor | Onde está | O que é |
|---|---|---|
| URL da API | `pom.xml` (`license.api.url`) = `http://127.0.0.1:8080` | embutida no `corePlugins.jar` no build |
| Pares de chaves RSA (atual + próximo) | tabela `server_secrets` no PostgreSQL | gerados no primeiro start; o servidor assina com a privada atual. Cifrados com `MASTER_KEY` se ela estiver definida (opcional no teste local, obrigatória em produção) |
| Banco de dados | PostgreSQL (`docker compose up -d postgres`) | única opção: não há mais H2 nem arquivos; jars, chaves e contas ficam no banco |
| Chaves públicas | `loader/src/main/resources/license-public.key` | uma por linha; o loader aceita qualquer uma. Baixe as duas de `/api/v1/public-keys` |
| Conta de admin | variáveis `ADMIN_EMAIL` e `ADMIN_PASSWORD` (mín. 12 caracteres) ao iniciar o servidor | **você escolhe**; nada de senha/token fixo no repositório. O perfil `local` dispensa o 2FA |
| Site da loja | `http://127.0.0.1:8080/` | servido pelo próprio license-server (pasta `static/`) |
| Pagamento | modo **simulado** por padrão | botão "Simular pagamento"; Mercado Pago só com variáveis (ver abaixo) |

Use `127.0.0.1` e não `localhost`: o Java pode resolver `localhost` para IPv6 (`::1`) e o IP que o servidor
enxerga mudaria.

## Requisitos

- **JDK 21** (`java -version` deve mostrar 21) e **Maven 3.9+** (`mvn -v`).
- Um servidor **Paper ou Spigot 1.13 ou mais novo** rodando na **mesma máquina**, com `server-port=25565`.

## 1. Compilar

Na pasta `coreplugins`:

```
mvn clean package
```

Gera:

- `loader/target/corePlugins.jar` (o que o cliente instala)
- `example-product/target/example-product-1.0.0.jar` (o produto de teste)
- `license-server/target/license-server-1.0.0.jar`

Se o Maven reclamar do Spigot API, confira se o acesso a `hub.spigotmc.org` está liberado na sua rede.

## 2. Subir o license-server

Suba o PostgreSQL (na raiz do projeto) e depois o servidor. Tudo é gravado no banco; a pasta atual não importa:

```
export DB_PASSWORD='uma-senha-forte'            # Windows (cmd): set DB_PASSWORD=...
docker compose up -d postgres
cd license-server
export ADMIN_EMAIL=voce@exemplo.com ADMIN_PASSWORD='escolha-uma-senha-longa'    # Windows (cmd): set ADMIN_EMAIL=... e set ADMIN_PASSWORD=...
java -jar target/license-server-1.0.0.jar --spring.profiles.active=local
```

Na primeira subida isso cria a conta de administrador. Use os mesmos valores no `seed` e no painel (passo 3a).

Deixe esse terminal aberto. Teste em outro terminal:

```
curl http://127.0.0.1:8080/api/v1/public-keys
```

A primeira linha da resposta é a chave atual; ela precisa estar no `loader/src/main/resources/license-public.key`. Para o loader também aceitar a chave "próxima"
(necessária para rotacionar sem redistribuir), grave a resposta inteira: `curl -s http://127.0.0.1:8080/api/v1/public-keys > loader/src/main/resources/license-public.key` e recompile o loader.

## 3. Publicar o plugin de teste na loja

Em outro terminal, na pasta `coreplugins`:

- (com `ADMIN_EMAIL` e `ADMIN_PASSWORD` definidos no terminal, os mesmos do servidor)
- Linux/Mac: `bash local/seed.sh`
- Windows (cmd): `local\seed.cmd`

Isso cria o produto `exemplo` (R$ 9,90) e sobe o jar v1.0.0. Rode **uma vez** (de novo dá "já existe").

## 3a. Painel de administração (alternativa ao seed)

Abra `http://127.0.0.1:8080/#/admin`, entre com o e-mail e a senha de `ADMIN_EMAIL`/`ADMIN_PASSWORD` (no perfil `local` não pede 2FA), aba **Produtos** → *Novo produto* →
**Gerenciar** → *Enviar nova versão* (o jar fica em `example-product\target`). Dá no mesmo que o `seed`.

## 3b. Usar o site (fluxo do cliente)

1. Abra `http://127.0.0.1:8080/` → **Criar conta**.
2. Adicione o **Exemplo** ao carrinho → **Pagar com PIX**. No modo de teste clique em **Simular pagamento**.
3. **Área do Cliente** → *Adicionar servidor*: IP `127.0.0.1`, porta `25565`.
4. Marque **Exemplo** no servidor e clique em **Atualizar**.

Ao lado de cada plugin marcado há um seletor de versão (padrão: a mais recente). Para testar, publique outra versão
com `curl ... /api/admin/products/exemplo/versions?version=1.0.2 -F file=@...` e escolha uma versão no site.

Pronto: esse IP:porta já está autorizado. O Minecraft aplica em até 30 min, ou na hora com `/coreplugins reload`.

## 4. Ligar o Minecraft

1. Copie `loader/target/corePlugins.jar` para a pasta `plugins/` do servidor. Não precisa configurar nada.
2. Ligue o servidor. No console deve aparecer:

   ```
   [corePlugins] Servidor autenticado: 127.0.0.1:25565 (1 produto(s) liberado(s))
   [corePlugins] Produto habilitado: Exemplo v1.0.0
   ```
3. No jogo (ou no console): `/exemplo` e `/coreplugins status`. Entrar no servidor mostra a mensagem de boas-vindas.
   A pasta `plugins/Exemplo/config.yml` é criada com as opções só desse plugin.

## 5. Testar a negação

Para ver o fluxo do cliente novo: revogue o servidor (o id aparece em `GET /api/admin/servers`) e reinicie o Minecraft.

```
TOKEN=$(curl -s -H "Content-Type: application/json" -X POST http://127.0.0.1:8080/api/admin/auth/login \
  -d "{\"email\":\"$ADMIN_EMAIL\",\"password\":\"$ADMIN_PASSWORD\"}" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
curl -X POST -H "Authorization: Bearer $TOKEN" http://127.0.0.1:8080/api/admin/servers/1/revoke
```

O console passa a mostrar `Falha na autenticação (revoked: 127.0.0.1:25565), desligando servidor....`.
Para voltar: troque `revoke` por `activate`.

## Problemas comuns

| Sintoma | Causa provável |
|---|---|
| `Falha na autenticação (not_found: 127.0.0.1:25565)` | falta o passo 3b (cadastrar o IP:porta e marcar o plugin), ou a porta do `server.properties` não é 25565 |
| `Falha na autenticação (not_found: 192.168.x.x:25565)` | o Minecraft não está na mesma máquina, ou usa outra rede; cadastre o IP que aparecer no console |
| `Não foi possível verificar a licença agora` | license-server desligado ou porta 8080 ocupada |
| `Assinatura inválida` | a chave pública do loader não é a do servidor; refaça o build do loader |
| `Relógio ... fora de sincronia` | diferença de mais de 10 min entre os relógios |
| `compilado sem a URL do license-server` | build feito com valor de exemplo; use `-Dlicense.api.url=http://127.0.0.1:8080` |

## Antes de ir para produção

- **Defina a `MASTER_KEY`** (`openssl rand -base64 32`) no ambiente do servidor. Na primeira subida com ela, o servidor cifra no próprio banco os segredos que estavam em texto puro.
- **Gere um par de chaves novo**: num banco novo (ou apagando as linhas `rsa-keypair` e `rsa-keypair-next` de `server_secrets`) suba o servidor (ele cria outros), copie o resultado de
  `/api/v1/public-keys` para `loader/src/main/resources/license-public.key` e recompile o loader.
  A chave privada deste pacote não deve ser usada fora do teste local.
- **Não use o perfil `local` em produção**: ele desliga o 2FA do painel. Em produção suba só com `ADMIN_EMAIL`/`ADMIN_PASSWORD` (a senha só precisa estar no ambiente na primeira subida) e cadastre o app de autenticação no primeiro login.
- Compile com `-Dlicense.api.url=https://sua-api`. Veja o README para o checklist completo.

## Mercado Pago (PIX de verdade)

O padrão é `PAYMENT_MODE=simulated`. Para cobrar de verdade, suba o license-server com:

```
PAYMENT_MODE=mercadopago
MP_ACCESS_TOKEN=<access token da sua aplicação no Mercado Pago>
PUBLIC_URL=https://sua-loja.com.br     # opcional em localhost; habilita o webhook
```

Sem `PUBLIC_URL` (localhost) não há webhook: a página do pedido consulta o Mercado Pago a cada 4 s e libera o plugin
quando o pagamento aprova. Com `PUBLIC_URL`, o webhook `POST /api/store/mercadopago/webhook` também confirma — e o
servidor sempre reconsulta o pagamento na API do Mercado Pago, nunca confia no corpo da notificação.
Teste primeiro com credenciais de **teste** do Mercado Pago.
