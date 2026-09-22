# AgroEase — Agente de Impressão

Agente local de impressão de cupom (Java 21) do ERP **AgroEase** (`app.agroease.com.br`). O nome `wagsyspet` do repo, dos
pacotes e das classes é herança do nome antigo do produto e fica assim de propósito; **tudo que a loja vê diz AgroEase**
(instalador, bandeja, mensagens). Instalado na máquina da loja, o agente recebe do PDV web
(via WebSocket em `127.0.0.1`) o **PDF de 80 mm** que o backend já gera e o imprime, **sem diálogo**, na impressora
escolhida **por nome** — com confirmação de aceite pelo spooler. Um só código para **Windows, Linux e macOS**.

> Plano completo, decisões e fases: [`docs/plano-agente-impressao-proprio.md`](docs/plano-agente-impressao-proprio.md).

## Por que existe

O navegador não confirma que um cupom saiu, não escolhe impressora e o `--kiosk-printing` exige configurar cada
máquina na mão. O agente fecha esses gaps mantendo o cupom fiscal exatamente como a Focus NFe o entrega (o PDF é
renderizado no backend; o agente só imprime).

## Arquitetura (resumo)

```
PWA (Vercel) ──ws://127.0.0.1──► agente (bandeja) ──► javax.print / CUPS ──► impressora
   baixa o PDF 80mm                 Origin + Host + ticket           por NOME, sem diálogo
```

- **Enumeração** de impressoras: `javax.print` (mesma API em todo SO).
- **Submissão:** Windows → `PrinterJob` + PDFBox (spooler nativo); Linux/macOS → PDF nativo via `lp` (CUPS).
- **Depois do aceite:** o agente acompanha o job no spooler e avisa o PDV do estado real (`IMPRESSO` = dados entregues,
  `PENDENTE` com o motivo — sem papel, fila pausada, impressora desligada… — ou `FALHOU`).
- **Gaveta e corte** (opcionais, por impressora): comandos ESC/POS separados do cupom; pré-voo recusa quando a fila está parada.
- Segurança: bind só em loopback, allowlist de `Origin`, ticket assinado pelo backend por sessão.

## Módulos

| Módulo | Papel |
|---|---|
| `agente-protocolo` | Contrato backend ↔ agente: **ticket Ed25519** (`AssinadorTicket` = lado backend, `VerificadorTicket` = lado agente, `ChavesTicket` SPKI/PKCS#8 base64) + **vetores de teste** `vetores-ticket-v1.json` que os dois repos rodam; `ProtocoloVersao`; `release/` (`ManifestoRelease` = `latest.json`, `VersaoSemantica`, `ChavesRelease` atual + reserva, `VerificadorAssinaturaRelease`) |
| `agente-impressao` | Imprimir PDF por nome de impressora (`ImpressoraJavaxPrint` no Windows, `ImpressoraCupsLp` no Linux/macOS), `AquecedorPdfBox` (cache de fontes na subida); `spooler/` = **estado real do cupom** depois do aceite (Windows: winspool via JNA, consulta periódica `EnumJobs`; CUPS: IPP com reserva `lpstat`), `raw/` = comandos ESC/POS e ESC/Bema **não-fiscais** de gaveta e corte (catálogo fechado; o PDF nunca é tocado) |
| `agente-core` | Servidor WebSocket em `127.0.0.1` (`ServidorAgente`): porteiro do handshake (Origin exata + Host loopback), teto de frame 4 MiB, **protocolo v1 + extensões anunciadas em `capacidades`** (tabela abaixo), uma conexão autenticada por Origin, fila de impressão fora da thread da conexão (`FilaImpressao`), fallback de porta 28421 → 28422; **pareamento** (`pareamento/`: `ClientePareamento`, `CofreCredencial` AES-GCM, `DiretoriosDoAgente` por SO); `atualizacao/` = ciclo do **self-update** (`GerenteAtualizacao`, `EstadoAtualizacao`, `GuardaAnterior`, `PlanoAtualizacao`) |
| `agente-app` | Binário instalado (`Main`): sem argumentos = programa de desktop (bandeja ou janela; headless só se pareado); `--parear`, `--desparear`, `--status`, `--diagnostico`, `--instalar`/`--desinstalar` (iniciar com o sistema), `--verificar-atualizacao` (consulta a release publicada, não instala), `--atualizar` (com o agente fechado: baixa e aplica agora), `--versao`, `--gerar-pdf-teste`, `--imprimir-teste`; opções `--sem-bandeja`, `--verboso`, `--dir-dados`; `atualizacao/` = instaladores por SO e o **atualizador externo** (`--aplicar-atualizacao plano.json`); `autostart/` = tarefa keepalive do Windows, LaunchAgent, systemd --user; log em `logs/agente-0.log`; **empacotamento** `-Pempacotar` (jlink + jpackage → `.exe`/`.deb`/`.tar.gz`/`.dmg`) |

## Protocolo (`ws://127.0.0.1:28421`, fallback `28422`)

Protocolo **v1**; as extensões da F6 são aditivas e o agente anuncia o que sabe fazer em `hello_ok.capacidades`
(`estado_impressao`, `comando_raw`, `atualizacao`) — um PWA antigo ignora os campos a mais, um agente antigo responde
`TIPO_DESCONHECIDO` ao que não conhece. Toda mensagem tem `tipo`; as pós-auth levam um `id` de correlação.

| Mensagem do PWA | Resposta do agente | Erros / fechamentos |
|---|---|---|
| `hello{versaoProtocolo}` | `hello_ok{agenteVersao, protocolo, so, agenteId, capacidades[]}` | — |
| `auth{ticket}` | `auth_ok` | `erro{TICKET_INVALIDO\|NAO_PAREADO\|OCUPADO}` + close 1008; sem `auth` em 90 s → close 1008 `AUTH_TIMEOUT` |
| `ping` | `pong` | qualquer outro tipo antes do `auth` → `erro{NAO_AUTENTICADO}` + close 1008 |
| `listar_impressoras{id}` | `impressoras{id, nomes[], selecionada\|null, extras?}` | — |
| `selecionar_impressora{id, nome, extras?}` | `selecionar_impressora_ok{id, selecionada, extras?}` | `erro{IMPRESSORA_INDISPONIVEL\|MENSAGEM_INVALIDA}` |
| `imprimir{id, formato:"pdf", bytesBase64, impressora, gaveta?}` | `imprimir_ok{id, estado:"ACEITO_SPOOLER", avisos?:[GAVETA_FALHOU\|CORTE_FALHOU]}` e depois o push `impressao_estado{id, origem:"push", estado, motivo?, detalhe, encerrado}` | `imprimir_erro{id, codigo: IMPRESSORA_INDISPONIVEL\|ERRO}`; `erro{MENSAGEM_INVALIDA}` (PDF > 2 MiB, base64 inválido…) |
| `consultar_impressao{id}` (id do job) | `impressao_estado{id, origem:"consulta", …}` | — |
| `comando{id, comando:"ABRIR_GAVETA"\|"CORTAR"}` | `comando_ok{id}` | `erro{COMANDO_DESABILITADO\|IMPRESSORA_INDISPONIVEL\|ERRO}` |

- `extras{dialeto: ESCPOS\|ESC_BEMA, gaveta, corte, gavetaPino, gavetaPulsoMs}` só aparece quando gaveta ou corte está ligado
  naquela impressora; a gaveta abre junto com o cupom da venda em dinheiro (`gaveta: true` no `imprimir`), uma vez por venda.
- `impressao_estado.estado` ∈ `IMPRESSO` (dados entregues ao dispositivo — nunca "papel saiu"), `PENDENTE`, `FALHOU`,
  `DESCONHECIDO`; `motivo` é uma lista fechada (`SEM_PAPEL`, `IMPRESSORA_OFFLINE`, `TAMPA_ABERTA`, `FILA_PARADA`,
  `INTERVENCAO`, `ERRO_DRIVER`, `CANCELADO`, `ABORTADO`, `SUMIU_DA_FILA`, `EM_ENVIO`, `NAO_ACEITO`, `SEM_REGISTRO`,
  `CONSULTA_INDISPONIVEL`, `SEM_SUPORTE`).
- Fechamentos: `1001 ATUALIZANDO` (o agente vai sair para se atualizar e volta em até 1 min), `1013 LIMITE_CONEXOES`
  (8 conexões simultâneas), `1008` nos erros de autenticação. Limites: PDF ≤ 2 MiB, frame ≤ 4 MiB, 1 job em execução + 2 à espera.

## Atualização automática

O agente verifica a release publicada 2 min depois de abrir e a cada 6 h, baixa e verifica (assinatura Ed25519 do
`latest.json` + SHA-256 do instalador) e aplica quando o caixa está ocioso, no boot seguinte ou pelo clique em
"Atualizar" — Windows, macOS e o `.tar.gz` do Linux sozinhos; o `.deb` só pelo clique (pede a senha de administrador).
Sentinela de saúde com reversão, freio de 3 tentativas por versão. Fluxo completo por sistema: [`docs/self-update.md`](docs/self-update.md).

## Desenvolvimento

```bash
JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw test          # unitários (qualquer SO)
# Spike/integração local com impressora virtual (Linux: printer-driver-cups-pdf; Windows: "Microsoft Print to PDF"):
JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw test -Djava.awt.headless=true -Dspike.pdfs=<dir com itext-fiscal.pdf e itext-naofiscal.pdf>

# Host de desenvolvimento do agente (ws://127.0.0.1:28421; NÃO use -q, ele engole os logs):
JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw -DskipTests install
JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw -pl agente-core exec:java   # -Dagente.porta=… -Dagente.origins=a,b

# Binário como o da loja (jlink + jpackage; gera o instalador do SO em que roda):
JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw -pl agente-app -am -DskipTests -Pempacotar package
agente-app/target/dist/AgroEase-Agente-Impressao/bin/AgroEase-Agente-Impressao-cli --diagnostico   # Linux
# Windows: dist\AgroEase-Agente-Impressao\AgroEase-Agente-Impressao-cli.exe · macOS: dist/…​.app/Contents/MacOS/…-cli
```

> O runtime `jlink` PRECISA de `jdk.crypto.ec` (provider Ed25519 no JDK 21; `jdeps` não enxerga porque é ServiceLoader).
> O `--diagnostico` falha com saída 2 se faltar — é a asserção do smoke no CI.

Teste manual do prompt de Local Network Access no navegador: `docs/roteiro-spike-lna.md`.

Sem impressora física: no Linux, `sudo apt install printer-driver-cups-pdf` cria a impressora virtual `PDF` (saída em `~/PDF`).

## Uso na loja (resumo)

1. Instale o pacote do seu sistema da última release: `.exe` (Windows), `.dmg` (macOS), `.deb` (Ubuntu/Debian/Mint) ou
   `.tar.gz` (qualquer Linux: extraia dentro da sua pasta pessoal e abra `bin/AgroEase-Agente-Impressao`; é o formato Linux
   que se atualiza sozinho). Já tem o agente instalado? Clique em **Sair** no ícone dele antes de instalar por cima —
   o pareamento e a impressora escolhida continuam valendo.
2. No painel AgroEase, em **Configurações → Geral → Impressão de Cupom**, gere o código de pareamento e cole no agente
   ("Parear…" na bandeja/janela, ou `AgroEase-Agente-Impressao-cli --parear <codigo>`). Ao parear, o agente passa a iniciar com o sistema.
3. Escolha a impressora deste computador ("Impressora…") e faça um teste.

Pasta de dados por usuário: `%LOCALAPPDATA%\AgroEase\agente-impressao` (Windows), `~/.config/agroease/agente-impressao` (Linux),
`~/Library/Application Support/AgroEase/agente-impressao` (macOS). `--status` e `--diagnostico` mostram tudo que o suporte precisa, sem segredos.

## Release

```bash
git tag v1.2.3 && git push origin v1.2.3     # dispara .github/workflows/release.yml
```

O `release.yml` reaproveita o `ci.yml` (testes + smoke do binário) em 4 runners (Linux, Windows, macOS arm64 e macOS x64), renomeia os
instaladores para `AgroEase-Agente-Impressao-<versão>-<so>-<arch>.<ext>`, gera `SHA256SUMS.txt`, `latest.json` e `latest.json.sig`
(assinatura Ed25519 com a chave de release — a pública, e uma **reserva**, estão em `agente-protocolo/src/main/resources/release.properties`;
a privada só no secret `RELEASE_LATEST_JSON_PRIVKEY` do ambiente `release`; o job confere a assinatura com a pública embutida antes de
publicar) e publica no GitHub Releases. O backend e os agentes instalados leem o `latest.json` assinado da release mais recente —
nada a configurar por versão (as variáveis `AGENTE_URL_*`/`AGENTE_SHA256_*` do resumo do job são só um fallback). Uma tag com sufixo
(`v1.2.3-rc1`) vira **pré-release**: aparece na página de Releases para teste manual, mas não é vista pelo manifesto "latest".
A versão do binário é única: `-Drevision=<versão>` → MANIFEST → `hello_ok` → `--app-version` do jpackage; o smoke falha se divergir.
Manifesto fixo: `…/releases/latest/download/latest.json`.

## Licença

Código do agente aberto; libs: Apache PDFBox (Apache 2.0), Java-WebSocket (MIT), Jackson (Apache 2.0), JNA (Apache 2.0 / LGPL 2.1, só no Windows).
