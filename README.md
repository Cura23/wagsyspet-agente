# WagSysPet — Agente de Impressão

Agente local de impressão de cupom (Java 21) do ERP **WagSysPet**. Instalado na máquina da loja, recebe do PDV web
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
| `agente-impressao` | Imprimir PDF por nome de impressora (`ImpressoraJavaxPrint`, `ImpressoraCupsLp`) |
| `agente-protocolo` *(em breve)* | Mensagens do WebSocket, códigos de erro, verificação de ticket |
| `agente-core` *(em breve)* | Servidor WebSocket local, segurança, fila, pareamento |
| `agente-app` *(em breve)* | Bandeja, autostart, instalação, credencial |

## Desenvolvimento

```bash
JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw test          # unitários (qualquer SO)
# Spike/integração local com impressora virtual (Linux: printer-driver-cups-pdf; Windows: "Microsoft Print to PDF"):
JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw test -Djava.awt.headless=true -Dspike.pdfs=<dir com itext-fiscal.pdf e itext-naofiscal.pdf>
```

Sem impressora física: no Linux, `sudo apt install printer-driver-cups-pdf` cria a impressora virtual `PDF` (saída em `~/PDF`).

## Licença

Código do agente aberto; libs: Apache PDFBox (Apache 2.0), Java-WebSocket (MIT), Jackson (Apache 2.0).
