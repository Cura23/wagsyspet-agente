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
| `agente-protocolo` | Contrato backend ↔ agente: **ticket Ed25519** (`AssinadorTicket` = lado backend, `VerificadorTicket` = lado agente, `ChavesTicket` SPKI/PKCS#8 base64) + **vetores de teste** `vetores-ticket-v1.json` que os dois repos rodam; *(F3)* mensagens do WebSocket |
| `agente-impressao` | Imprimir PDF por nome de impressora (`ImpressoraJavaxPrint`, `ImpressoraCupsLp`) |
| `agente-core` | Servidor WebSocket em `127.0.0.1` (`ServidorAgente`), porteiro do handshake (`PorteiroHandshake`: Origin exata + Host loopback), teto de frame 4 MB, protocolo mínimo `hello/ping`; *(F3)* fila, pareamento, ticket |
| `agente-app` | Ponto de entrada do binário (`Main`: `--versao`, `--diagnostico`, `--gerar-pdf-teste`, `--imprimir-teste`; sem args = servidor) + **empacotamento** `-Pempacotar` (jlink + jpackage → app-image e `.deb`/`.exe`/`.dmg`); *(F3)* bandeja, autostart, credencial |

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

## Licença

Código do agente aberto; libs: Apache PDFBox (Apache 2.0), Java-WebSocket (MIT), Jackson (Apache 2.0).
