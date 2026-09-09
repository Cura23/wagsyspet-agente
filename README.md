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
- Segurança: bind só em loopback, allowlist de `Origin`, ticket assinado pelo backend por sessão.

## Módulos

| Módulo | Papel |
|---|---|
| `agente-protocolo` | Contrato backend ↔ agente: **ticket Ed25519** (`AssinadorTicket` = lado backend, `VerificadorTicket` = lado agente, `ChavesTicket` SPKI/PKCS#8 base64) + **vetores de teste** `vetores-ticket-v1.json` que os dois repos rodam; `ProtocoloVersao`; `VerificadorAssinaturaRelease` (assinatura do `latest.json`) |
| `agente-impressao` | Imprimir PDF por nome de impressora (`ImpressoraJavaxPrint` no Windows, `ImpressoraCupsLp` no Linux/macOS), `AquecedorPdfBox` (cache de fontes na subida) |
| `agente-core` | Servidor WebSocket em `127.0.0.1` (`ServidorAgente`): porteiro do handshake (Origin exata + Host loopback), teto de frame 4 MiB, **protocolo v1** (`hello` → `auth` com ticket → `listar_impressoras`/`selecionar_impressora`/`imprimir`), uma conexão autenticada por Origin, fila de impressão fora da thread da conexão (`FilaImpressao`), fallback de porta 28421 → 28422; **pareamento** (`pareamento/`: `ClientePareamento`, `CofreCredencial` AES-GCM, `DiretoriosDoAgente` por SO) |
| `agente-app` | Binário instalado (`Main`): sem argumentos = programa de desktop (bandeja ou janela; headless só se pareado); `--parear`, `--desparear`, `--status`, `--diagnostico`, `--instalar`/`--desinstalar` (iniciar com o sistema), `--versao`, `--gerar-pdf-teste`, `--imprimir-teste`; log em `logs/agente-0.log`; **empacotamento** `-Pempacotar` (jlink + jpackage → `.deb`/`.exe`/`.dmg`) |

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

1. Instale o pacote do seu sistema (`.exe`, `.deb` ou `.dmg` da última release).
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
(assinatura Ed25519 com a chave de release — a pública está em `agente-app/src/main/resources/release.properties`; a privada só no
secret `RELEASE_LATEST_JSON_PRIVKEY` do ambiente `release`) e publica no GitHub Releases. O resumo do job traz as variáveis
`AGENTE_URL_*` / `AGENTE_SHA256_*` / `AGENTE_VERSAO_ATUAL` para o backend. A versão do binário é única: `-Drevision=<versão>` → MANIFEST →
`hello_ok` → `--app-version` do jpackage; o smoke falha se divergir. Manifesto fixo: `…/releases/latest/download/latest.json`.

## Licença

Código do agente aberto; libs: Apache PDFBox (Apache 2.0), Java-WebSocket (MIT), Jackson (Apache 2.0).
