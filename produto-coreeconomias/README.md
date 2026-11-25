# coreEconomias (produto CorePlugins)

Multi economias para servidores de rankup. Uma economia por arquivo em `plugins/coreEconomias/economies/`.
Para criar outra, copie `coins.yml`, renomeie e ajuste nome, comando e mensagens; depois `/economies reload`.

## Comandos
- `/economies` — abre o menu de configuração (permissão `coreeconomias.menu`).
- `/economies reload | list` — administração (permissão `coreeconomias.admin`).
- Para cada economia `<id>` (comando configurável, ex.: `/coins`):
  `/<cmd>`, `/<cmd> <jogador>`, `/<cmd> pagar <jogador> <quantia>`, `/<cmd> top`,
  `/<cmd> adicionar|remover|setar <jogador> <quantia>`, `/<cmd> forceupdate`.
- Quantias aceitam sufixos: `100`, `2.5k`, `1M`, `3B`... (lista em `suffixes` no config.yml).

## Permissões
- `coreeconomias.<id>.use`, `look`, `pay`, `top` — comandos comuns: **ninguém tem por padrão**, é preciso conceder (veja os pacotes abaixo).
- `coreeconomias.player` — pacote: libera `use`, `look`, `pay` e `top` de **todas** as economias.
- `coreeconomias.<id>.player` — pacote: libera esses quatro só da economia `<id>`.
- `coreeconomias.<id>.add`, `remove`, `set`, `forceupdate` — apenas OP.
- `coreeconomias.admin` — tudo.

## Integrações (opcionais)
- **Vault:** a economia indicada em `vault-currency` vira a economia do Vault.
- **PlaceholderAPI:** `%economias_<id>%`, `%economias_<id>_formatted%`,
  `%economias_top_<id>_<pos>_name%`, `%economias_top_<id>_<pos>_value%`.

## Banco de dados
`SQLITE` (padrão) ou `MYSQL` em `database.type`. Trocar de banco exige reiniciar o servidor.

### Vários servidores no mesmo MySQL
- Cada operação (adicionar, remover, setar, pagar) é gravada no banco como **variação** (`saldo = saldo + x`),
  em transação, com um id que impede aplicar a mesma operação duas vezes. Dois servidores mexendo no mesmo
  jogador ao mesmo tempo **não sobrescrevem** o saldo um do outro. Pagamentos gravam os dois lados na mesma transação.
- O saldo local muda na hora; depois de gravar, o plugin relê o saldo do banco e adota o valor real.
- Se o banco cair, as alterações ficam na fila e são repetidas até funcionar. Se o servidor desligar nesse estado,
  elas vão para `plugins/coreEconomias/pending-ops.log` e são reenviadas no próximo início (sem duplicar).
- **Débito confirmado (`safety.strict-debit`, ligado por padrão com MySQL):** pagar, remover e comprar (Vault) só
  são liberados depois que o **banco** confirmar o débito com um `UPDATE ... WHERE saldo >= valor`. Dois servidores
  nunca gastam o mesmo saldo e ele nunca fica negativo. Banco fora do ar = débito recusado (nunca liberado de graça).
  Se o banco gravar mas a resposta se perder, o plugin confere o registro de operações e estorna se o jogador
  tiver sido recusado. Créditos continuam instantâneos e assíncronos (não podem causar prejuízo).
- Por que não o Redis como dono do saldo? Um segundo "dono" do dinheiro (Redis) pode perder dados se reiniciar e
  precisaria ser sincronizado com o MySQL; o débito condicional no próprio MySQL já é atômico e não tem esse risco.
  Por isso o Redis fica só como aviso entre servidores.
- Recomendado no `parameters` do MySQL: `connectTimeout=3000&socketTimeout=8000` (já é o padrão novo; em
  instalações antigas, adicione à mão) para um MySQL travado não congelar o servidor.

### Redis (opcional)
Ligue em `redis.enabled`. O Redis **não guarda saldo**: só avisa os outros servidores que o saldo de um jogador mudou,
para eles relerem do banco na hora (dezenas de milissegundos). Se o Redis cair, nada para: o `resync-seconds`
(padrão 30 s) relê os jogadores online e o plugin reconecta sozinho. Use o mesmo `redis.channel` em todos os
servidores da mesma rede. Não precisa de biblioteca extra (cliente embutido).

Sem Redis, mudanças feitas por outro servidor só aparecem no próximo `resync-seconds`; reduza o valor se precisar.


## Menu de configuração
`/economies` (ou `/economies menu`) abre o menu: lista as economias, permite **criar**
uma nova (a partir do modelo) e editar cada uma: nomes, formato, saldo inicial, comando e apelidos, intervalo entre
usos, pagamentos (liga/desliga, mínimo, bloquear offline), tamanho do TOP e a economia do Vault. Textos e números
são digitados no chat (`cancelar` desiste). As alterações são gravadas em `economies/<id>.yml` **preservando os
comentários**, validadas (se o arquivo ficar inválido, volta sozinho) e aplicadas na hora (comandos e permissões
são recarregados). O arquivo anterior fica em `economies/<id>.yml.bak`. Trocar a economia do Vault exige reiniciar.

## Permissões (LuckPerms, PermissionsEx, GroupManager etc.)
O plugin só usa a API padrão do Bukkit (`hasPermission`), então funciona com qualquer plugin de permissões.
Os nós ficam registrados com curingas, que aparecem no editor web do LuckPerms:

| Nó | Padrão | O que libera |
|---|---|---|
| `coreeconomias.<id>.use`, `.look`, `.pay`, `.top` | ninguém | ver o próprio saldo, ver o de outros, pagar, TOP |
| `coreeconomias.<id>.player` | ninguém | pacote: os quatro acima, só da economia `<id>` |
| `coreeconomias.player` | ninguém | pacote: os quatro acima, em todas as economias |
| `coreeconomias.<id>.add`, `.remove`, `.set`, `.forceupdate` | OP | administrar saldos e atualizar o TOP |
| `coreeconomias.<id>.*` | OP | tudo da economia `<id>` |
| `coreeconomias.menu` | OP | abrir o menu de configuração |
| `coreeconomias.admin` | OP | tudo, inclusive `/economies reload` e `list` |
| `coreeconomias.*` | OP | tudo (todas as economias + admin + menu) |

Exemplos no LuckPerms:
- Liberar os comandos comuns a todos os jogadores: `/lp group default permission set coreeconomias.player true`
- Liberar só a economia coins: `/lp group default permission set coreeconomias.coins.player true`
- Dar acesso total à staff: `/lp group staff permission set coreeconomias.admin true`
- Só administrar saldos de coins: `/lp group moderador permission set coreeconomias.coins.* true`
- Impedir que um grupo pague: `/lp group novato permission set coreeconomias.coins.pay false`
- Uma permissão específica negada (`false`) vale mesmo que o grupo tenha `coreeconomias.admin`.
O alias `/eco` foi removido do comando principal para não disputar o comando com o EssentialsX.
