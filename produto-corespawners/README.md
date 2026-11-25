# coreSpawners (produto CorePlugins)

Geradores de mobs com stack de geradores e de mobs, drops armazenados, venda manual/automática, upgrades,
amigos, holograma e bônus. **Detecta sozinho as economias do servidor**: não é preciso editar `economies.yml`.

## Economias (detecção automática)
Nos arquivos de `spawners/` escreva `currency: <nome>`:

| Nome | De onde vem |
|---|---|
| `coins`, `cash`, ... (id do arquivo em `economies/` do coreEconomias) | **coreEconomias**: todas as moedas, inclusive as criadas depois pelo menu `/economies` |
| `core:coins`, `economias-coins` | mesmas moedas (formatos alternativos) |
| `vault` ou `money` | economia principal do Vault |
| `playerpoints` ou `points` | PlayerPoints |
| `auto` | economia padrão (`coins` do coreEconomias; senão a 1ª moeda dele; senão Vault; senão PlayerPoints) |
| qualquer nome | apelidos e integrações por comando definidos em `economies.yml` (opcional) |

`/spawners economies` mostra o que foi detectado. A detecção se refaz a cada 5 s (o coreEconomias pode iniciar
depois deste plugin).

Como funciona com o coreEconomias: cada produto roda em um ClassLoader próprio, então o acesso é por reflexão
(`CoreEconomiasLink`): o produto é localizado no registro da `ProductPlugin` e chama `EconomyService.get/add/tryRemove`
e `CurrencyManager.all/get`. Cobranças (upgrades) são feitas fora da thread principal, porque o débito confirmado no
banco pode demorar. Se o coreEconomias não existir, o plugin usa Vault/PlayerPoints; para outros plugins use a
seção `custom` de `economies.yml` (placeholder do PlaceholderAPI + comandos do console).

## Ovo, drops e boosters
- **Ovo** (`general.require-egg` no `config.yml`): `false` (padrão) = o gerador já vem com o ovo, é só colocar.
  `true` = vem vazio e só produz depois de clicar nele com o ovo de spawn do mob certo (o ovo é consumido).
- **Drops** (`general.drops-enabled` global e `drops.enabled` em cada tipo): desligado, os mobs são gerados e
  mortos (com XP), mas nada é armazenado ou vendido e o botão de drops some do menu.
- **Boosters** (`boosters.yml`): `DROPS` (mais drops), `SPEED` (geração mais rápida) e `SELL` (venda maior), por
  tempo. Dê com `/spawners booster <jogador> <id>` e o jogador usa clicando com o item em um gerador.
  1 booster de cada tipo por gerador; o menu **Boosters** mostra o que está ativo.
- **Upgrades** (menu do gerador): drops por mob e velocidade de geração, pagos na economia do tipo.

## Comandos e permissões
- `/spawners give <jogador> <tipo> [quantidade] [stack]`, `booster <jogador> <id> [quantidade]`, `list`,
  `economies`, `reload`, `help` — `corespawners.admin`
- `/spawners top [stack|kills|sales]` — ranking (`corespawners.top`, liberada para todos)
- `/spawners debug` — build, jar em uso, geradores, fila do banco e erros recentes (`corespawners.debug`, padrão: op)
- Por tipo: `permission` (colocar) e `kill-permission` (matar) no `spawners/<tipo>.yml`
- Bônus de venda: permissões de `bonus.yml` (`corespawners.bonus.vip`, `corespawners.bonus.mvp`)

## Uso
- Colocar o item no chão cria o gerador (agachado, todo o pack da mão vira um gerador só; shift + botão direito no ar junta todos os geradores iguais do inventário em 1 item); do mesmo tipo e dono dentro de `merge-radius` soma no stack.
- Botão direito no gerador abre o menu (principal, drops, upgrades, boosters e amigos): dono, amigos, ou qualquer um se público. Agachado com item na mão, coloca bloco.
- Quebrar remove 1 do stack; agachado remove tudo. Os drops armazenados precisam ser vendidos/coletados antes.
- O mob aparece ao lado do gerador (`distance` blocos, padrão 2), no lado (norte, sul, leste ou oeste) em que o jogador estava ao colocar (`mob-position: SIDE`; use `ABOVE` para ficar em cima).
- Ao bater no mob, a vida e o dano causado aparecem acima da hotbar (`general.actionbar-hit`; textos em `messages.yml`, seção `actionbar`).
- Matar o mob: sem shift mata todos os mobs do stack; com shift mata 1 (configurável por tipo).

## Para criar outro tipo
Copie `spawners/vaca.yml`, renomeie (o nome do arquivo é o id) e ajuste; `/spawners reload`.

## Ranking e placeholders (PlaceholderAPI)
O ranking é refeito a cada `ranking.refresh-seconds` (padrão 10 s) e guardado em cache, então scoreboard, holograma
e tab podem chamar os placeholders o tempo todo sem pesar. Mobs mortos e vendas são acumulados por jogador no banco
(tabelas `spw_stats` e `spw_sales`); o stack vem dos geradores que o jogador possui.

Todo valor numérico aceita o sufixo `_short` (1.5K) e `_raw` (1500). Sem sufixo sai no padrão brasileiro (1.500).

| Placeholder | Valor |
|---|---|
| `%corespawners_stack%` | soma dos stacks dos geradores do jogador |
| `%corespawners_spawners%` | blocos de gerador do jogador |
| `%corespawners_drops%` | drops guardados nos geradores do jogador |
| `%corespawners_kills%` | mobs mortos pelo jogador |
| `%corespawners_sold%` | total vendido na moeda do ranking (`ranking.sales-currency`) |
| `%corespawners_sold_<moeda>%` | total vendido em uma moeda específica (ex.: `sold_coins`) |
| `%corespawners_sold_items%` | quantidade de drops vendidos |
| `%corespawners_limit%`, `_limit_used`, `_limit_left` | limite de geradores (símbolo `ranking.placeholders.unlimited` se não houver) |
| `%corespawners_position_stack%`, `_position_kills`, `_position_sales` | posição do jogador (`-` se fora do ranking) |
| `%corespawners_top_stack_1_name%`, `%corespawners_top_stack_1_value%` | 1º do top stack (troque `stack` por `kills` ou `sales` e o `1` pela posição) |

O `/papi reload` não derruba a expansão. Sem o PlaceholderAPI o plugin funciona normalmente.

## Limite de geradores
- `corespawners.limit.<N>`: limite N (vale o maior que o jogador tiver; dê a um grupo no LuckPerms).
- `corespawners.limit.0`, `.unlimited`, `.*` e `.bypass`: sem limite (a administração já tem o `.bypass`).
- Sem nenhuma delas vale `limits.default` do `config.yml`; `0` = ilimitado.
- `limits.count`: `STACK` conta a soma dos stacks; `BLOCKS` conta só os blocos colocados.

## Proteção de terreno e ilha
`protection.enabled: true` (padrão): o gerador só é criado depois que os plugins de terreno/ilha liberaram a colocação
do bloco, então vale para qualquer plugin que cancele a colocação (WorldGuard, PlotSquared, GriefPrevention, Towny,
Residence, Lands, SuperiorSkyblock, BentoBox...). `false` volta ao comportamento antigo.

## Permissões valem para o dono
Ser dono do gerador não dispensa permissão: colocar, quebrar, abrir o menu, matar, vender, coletar, upgrades, boosters,
amigos, ovo e os botões do menu exigem a respectiva `corespawners.<ação>`, e o menu confere `corespawners.use` a cada
clique. A mensagem de negação mostra qual permissão falta.

## Limitações conhecidas (v1)
- Não tem missões, limites em item (stack/mobs/drops), partículas, vidro nem mcMMO.
- A proteção de terreno depende do plugin de terreno cancelar o `BlockPlaceEvent`; não há integração direta com a API de cada um.
- Cabeças com textura só funcionam em versões cujo `SkullMeta` tem o campo `profile` como `GameProfile`
  (até a 1.20.x); nas demais vira a cabeça padrão.
- Marca de entidades (limpeza após crash) exige 1.11+; em versões antigas as entidades são removidas só ao
  descarregar o chunk/desligar.
- Não foi compilado nem testado em servidor real.

## Permissões

Todas já vêm liberadas para todos (`default: true`, exceto `debug` e `limit.bypass`); negue no LuckPerms (ou outro) o que quiser bloquear.
`corespawners.admin` (padrão: op) inclui todas as outras e ainda dá acesso aos geradores de qualquer jogador.

| Permissão | O que libera |
|---|---|
| `corespawners.place` | Colocar geradores |
| `corespawners.break` | Quebrar os próprios geradores |
| `corespawners.use` | Abrir o menu do gerador (botão direito) |
| `corespawners.kill` | Matar os mobs |
| `corespawners.sell` | Vender os drops pelo menu |
| `corespawners.collect` | Coletar os drops como itens |
| `corespawners.autosell` | Ligar e usar a venda automática |
| `corespawners.upgrade` | Comprar upgrades |
| `corespawners.booster` | Aplicar boosters |
| `corespawners.friends` | Gerenciar amigos |
| `corespawners.toggle` | Ligar/desligar o gerador, modo público e holograma |
| `corespawners.pack` | Shift para colocar o pack inteiro e shift + botão direito no ar para juntar |
| `corespawners.egg` | Colocar o ovo de spawn no gerador |
| `corespawners.top` | Ver o ranking (`/spawners top`) |
| `corespawners.debug` | Usar `/spawners debug` (padrão: op) |
| `corespawners.limit.<N>` / `.bypass` | Limite de geradores (veja acima); `.bypass` vem com `corespawners.admin` |

As permissões por tipo (`permission`, `kill-permission`, `sell-permission`, `collect-permission` nos arquivos de `spawners/`)
e `general.autosell-permission` continuam valendo, somadas a estas.

