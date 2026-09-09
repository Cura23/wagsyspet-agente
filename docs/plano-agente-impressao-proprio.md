# Plano — Agente Próprio de Impressão de Cupom (WagSysPet)

> **Status (2026-09-07):** PLANO APROVADO (todas as decisões tomadas — Java 21, repo público, multiplataforma).
> **F0 CONCLUÍDO** (PR #1 deste repo, CI verde em ubuntu/windows/macos; próximo = F1 backend). Detalhe dos spikes: spike de fidelidade **PASSOU** (iText ≈ Chrome, fiscal com QR e não-fiscal); spike do caminho
> Unix **PASSOU em código** (`javax.print` enumera; `lp` + PDF nativo → CUPS → `cups-pdf`, 6/6 verdes).
> **Windows PROVADO no CI** (runner `windows-latest`: `PrinterJob`+PDFBox → "Microsoft Print to PDF" em porta de
> arquivo → PDF com `Producer: Microsoft: Print To PDF`, 4/4 rodaram). CI verde em **ubuntu/windows/macos**.
> **WebSocket em 127.0.0.1 PASSOU** (`agente-core`: `ServidorAgente` + `PorteiroHandshake`, 34/34 verdes com sockets
> reais; curl: Origin do PWA → 101, Origin estranha/sem Origin/Host rebinding → recusa antes do upgrade, LAN não
> conecta). **Adversarial F0 do `agente-core` (17 agentes) → 9 confirmados, todos tratados** (ver §7.1): teto de frame
> 4 MB (a lib pré-alocava o tamanho DECLARADO — 14 bytes derrubavam o agente por OOM), frame binário → erro tipado,
> `onStart` protegido + timeout derruba, sinal `falhaFatal()` + `estaEscutando()` + watchdog no host, teste de
> socket pro Host, recusas assertadas pelo 404 real, CI em 2 passos. ⚠️ Java-WebSocket 1.6 responde **404**
> (hardcoded) em toda recusa de handshake, não 403 — cosmético, o 101 nunca sai. **Item (d) LNA FEITO, automatizado**
> (Chrome 152 dirigido por CDP na tela do dono + Firefox 140 ESR por BiDi; resultados e PNGs em `docs/roteiro-spike-lna.md`
> e `docs/lna/`): prompt pt-BR = **"wagsyspet-frontend.vercel.app quer · Acessar outros apps e serviços neste dispositivo ·
> Bloquear/Permitir"**; ⚠️ **127.0.0.1 é permissão SEPARADA da "Rede local"**: content setting `loopback_network`, rótulo
> **"Apps no dispositivo"**, Permissions API **`loopback-network`** (Firefox 140 lança TypeError → feature-detect); prompt
> aparece **sem gesto**; após "Bloquear" **nenhum indicador** na barra de endereço, console `net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS`;
> reativar em *Configurações do site → Apps no dispositivo → Permitir* vale **na hora** sem recarregar; example.com com
> permissão concedida é recusado pelo agente (404); dev `localhost:5173` sem prompt. Não coberto: 3 dispensas/embargo, Edge,
> Windows/macOS. **Item (e) Ed25519 FEITO** (`agente-protocolo`: `TicketClaims`/`ChavesTicket`/`AssinadorTicket`/
> `VerificadorTicket`, formato `v1.b64url(json).b64url(sig)`, 38 testes + **37 vetores de teste** em
> `vetores-ticket-v1.json` que o backend copia na F1; **adversarial 12 agentes → 5 confirmados, todos corrigidos**, ver
> §7.2: teto de validade 1 h, fail-fast em chave inválida, parser estrito, assinatura canônica, gerador com chave estável).
> **Item (f) empacotamento FEITO no Linux**
> (`agente-app` + perfil Maven `-Pempacotar`: jlink 59 MB → app-image 67 MB → `.deb` 38 MB; binário roda `--diagnostico`
> com Ed25519 + javax.print e imprime no cups-pdf; prova negativa: runtime sem `jdk.crypto.ec` falha com saída 2). CI ganhou
> os passos de empacotar + smoke nos 3 SOs — **Windows/macOS só provam após o push**. Repo público:
> https://github.com/Cura23/wagsyspet-agente · fachada `Impressora` por SO (TDD).
> **Este arquivo é o plano canônico — vive no repo do agente.**
> Gerado em 2026-09-06 a partir de recon multi-agente (6 lentes + 4 contra-provas céticas, fontes primárias
> de 2026) + verificação pessoal dos achados decisivos. Pré-requisito já feito: **Fase 0** (rate-limit fiscal,
> código morto, robustez do `imprimirViaIframe`) — **MERGEADA na main dos 2 repos em 2026-09-07** (backend PR #5
> @848da3ae, frontend PR #2 @4a1a3127; CI da main verde; deploy automático).

---

## 0. Resumo executivo

O agente próprio entra como **3ª estratégia de impressão** (`ModoImpressao.AGENTE`), ao lado de DIALOGO
(default) e DIRETO (kiosk), que **continuam iguais**. Ele fecha os 4 gaps que o navegador nunca fecha:
**confirmação positiva** de que o job foi aceito, **impressora por nome**, **sem atalho `--kiosk-printing`** por
máquina, e **impressora de rede / qualquer navegador**.

Três conclusões da recon mudam o desenho que tínhamos na cabeça — todas **verificadas em fonte primária**:

1. **Não precisa de certificado nenhum.** O PWA (HTTPS no Vercel) pode abrir `ws://127.0.0.1:PORTA` em texto
   claro: loopback é "potentially trustworthy" e **nunca foi mixed content** no Chromium/Firefox. Isso elimina
   CA própria, chave privada no binário e Let's Encrypt. O único "pedágio" é o **prompt de LNA** (Chrome/Edge
   147+, Firefox 154+) — um clique por máquina/navegador, que deve ser disparado por **gesto do operador**.
2. **UM motor de impressão para todos os SOs: o PDF de 80 mm que o backend já gera → PDFBox → `javax.print`.**
   O agente não renderiza HTML; imprime o PDF pela API multiplataforma do JDK, por nome de impressora e com status
   do spooler — o desenho do QZ Tray. Windows, Linux e macOS com o **mesmo código**. Risco a provar cedo: a
   fidelidade do PDF (iText) em 80 mm — spike do F0 no Linux do dono; plano B dentro do Java (Chrome/Edge headless).
3. **Stack DECIDIDA: Java 21** (Wagner, "com certeza agora") — a linguagem do dono e do backend, código compartilhado
   (protocolo, ticket Ed25519 nativo nos dois lados), repo **próprio e público**, Maven/JUnit 5 como o backend,
   testável **agora** no Linux via `javax.print` → CUPS → `cups-pdf`.

Custo monetário alvo: **R$0** (GitHub Releases; libs Apache/MIT; sem licença; sem assinatura de exe — decisão sua).

---

## 1. Decisões já tomadas (não reabrir)

| Decisão | Motivo |
|---|---|
| Agente **próprio** (não QZ Tray, não WebSerial) | QZ = cert ~US$749/ano; WebSerial exigiria reescrever o DANFCe em ESC-POS |
| Instalar software por loja é aceitável | Dono prefere download+instala a configurar atalho do Chrome |
| **Não assinar** o executável | Custo R$0; aceita o aviso "editor desconhecido" (ver §2.6 para os limites reais) |
| Download **de dentro do sistema** | Botão no app; bytes em storage grátis; Railway nunca proxia (heap 384 MB) |
| **Download em DOIS lugares: botão no painel (logado) + link PÚBLICO estável; o que exige login é o PAREAMENTO** (Wagner, 2026-09-07) | O instalador já é público (GitHub Releases, repo público) — login no download não protege nada, só define onde fica o botão. Com N caixas por loja, quem instala costuma ser técnico/funcionário sem a senha do dono: ele baixa por `app.agroease.com.br/agente` (página pública com instruções por SO) ou por `GET /api/public/agente-impressao/download/{so}` (302 pro asset). O agente **não faz nada** sem o código de pareamento, que só o dono logado com permissão de configuração gera |
| Manter DIALOGO/DIRETO intactos | AGENTE é aditivo; fallback sempre existe |
| Reusar o **HTML** existente (fiscal da Focus + builders próprios) | Não duplicar a verdade fiscal; não reescrever layout homologado |
| **Stack Java 21** (um motor `javax.print` p/ todos os SOs), repo **próprio e público** | Linguagem do dono; código compartilhado c/ backend; GitHub Releases grátis; sem segredo no binário |
| **Marca para a loja = AgroEase; código/repo/pacotes = wagsyspet** (Wagner, 2026-09-07) | O produto foi renomeado de WagSysPet para AgroEase (site `app.agroease.com.br`); o código manteve `wagsyspet` e **isso é aceito**. Regra: **tudo que a loja vê** diz *AgroEase* — nome do instalador (`AgroEase-Agente-Impressao-{versao}-{so}-{arch}.{ext}`, não `wagsyspet-agente-…`), título de janela/bandeja ("Agente de Impressão AgroEase"), textos do painel, e-mails, logs mostrados ao usuário; o prompt do navegador mostra a **Origin**, então em produção lê "**app.agroease.com.br quer**" (a URL `wagsyspet-frontend.vercel.app` é de teste). Nomes de repo, pacote Java, classes e URLs de release podem ficar `wagsyspet` |
| **Todos os SOs desde o dia 1** (Windows+Linux validados; macOS beta) | Princípio do dono: "deixar certo para todos"; sem Mac para validar |
| **Qualquer impressora** | Zero código por fabricante; tudo via driver do SO |
| **Qualquer quantidade de caixas por loja** | Tabela tenant por máquina; impressora por máquina |

---

## 2. Arquitetura recomendada

```
 Navegador da loja (PWA HTTPS no Vercel)                    Máquina da loja
 ┌──────────────────────────────────────┐   ws://127.0.0.1:PORTA   ┌──────────────────────────────┐
 │ estrategiaAgente ──► agenteClient ───┼─────────────────────────►│ Agente Java (bandeja AWT)    │
 │   hello / auth(ticket) / imprimir    │   ◄── imprimir_ok/erro   │  • WS só em 127.0.0.1        │
 │   PDF 80mm já baixado do backend     │                          │  • Origin+Host+ticket        │
 └───────────────┬──────────────────────┘                          │  • PDFBox → javax.print      │
                 │ HTTPS (Bearer)                                   │    (MESMO código Win/Linux/  │
                 ▼                                                  │     macOS; impressora p/ nome│
 Backend Railway: /release, /versao, /ticket, /pareamento           └──────────────┬───────────────┘
 (fora do caminho de impressão)                                                    │ driver do SO
                                                                                   ▼
                                                                          Térmica 80mm (USB ou rede)
```

### 2.1 Transporte navegador → agente (decisão: `ws://127.0.0.1:PORTA`, sem TLS)

- **IP literal `127.0.0.1`**, nunca `localhost` (tratamento de "localhost" por nome varia por browser; a isenção
  de LNA é por IP resolvido). Agente escuta **só** em 127.0.0.1.
- **LNA (Local Network Access):** inevitável em Chrome/Edge ≥147 e Firefox ≥154 (WebSocket incluso; a doc da MS
  ainda diz "não se aplica a WS" — está **desatualizada**). Certificado, pago ou não, **não suprime** (classifica
  pelo IP). Só política de Chrome gerenciado suprime — irreal em máquina de cliente.
  - **1 prompt por origem do PWA, persistente.** "Bloquear" trava até reset manual; **3 dispensas ≈ embargo de
    7 dias**. Logo: a 1ª conexão **sempre por gesto** (botão "Conectar agente"), **nunca** reconectar em loop no
    load do PDV.
  - Antes de conectar (**verificado no Chrome 152, 2026-09-07**): `navigator.permissions.query({name:'loopback-network'})`
    — é ESSA a permissão de `127.0.0.1` (content setting `loopback_network`, rótulo "Apps no dispositivo"), separada da
    "Rede local" (`local-network-access`, que ficou em `prompt` sem mudar). Consultar `loopback-network` primeiro, cair
    para `local-network-access`, e **try/catch obrigatório**: Firefox 140 lança `TypeError` para os dois nomes. Estados
    vistos: `prompt` → (clique) → `granted` | `denied` após Bloquear. O prompt aparece **sem gesto** (testado), mas a
    1ª conexão continua por botão, por UX e pelo embargo das dispensas.
  - Guia de reativação no painel — **textos pt-BR CAPTURADOS** (`docs/roteiro-spike-lna.md`, `docs/lna/*.png`):
    prompt = "*wagsyspet-frontend.vercel.app* quer · Acessar outros apps e serviços neste dispositivo · Bloquear / Permitir";
    reativar = ícone à esquerda da URL → *Configurações do site* → **"Apps no dispositivo"** → *Permitir* (vale na hora,
    sem recarregar). Depois de "Bloquear" o Chrome **não mostra indicador nenhum** na barra de endereço e o console diz
    `net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS` — o PWA mapeia `close 1006` + `denied` → guia. Edge (não instalado
    aqui) e Firefox ≥154 ("Dispositivos da rede local"?) ainda em inglês nas fontes — conferir na loja-piloto.
- **Safari/macOS/iPad: fora do MVP.** WebKit ainda bloqueia `ws://` loopback (bug 171934 aberto); iPad não roda o
  agente. Ficam em DIALOGO. Contingência (fase M, só se houver demanda): CA raiz **por máquina** gerada no install
  + `wss://`, com **folha ≤825 dias** (Apple rejeita mais que isso mesmo de root do usuário) e renovação ~2 anos.
- **Rejeitado:** domínio público → 127.0.0.1 + Let's Encrypt (chave no binário = LE revoga; validade pública
  caindo para 47 dias até 2029; não evita o LNA). WebTransport (WebKit não implementa `serverCertificateHashes`).
- CSP/Permissions-Policy: o WS sai do **documento principal**, não de iframe. Se um dia houver CSP no Vercel,
  `connect-src` inclui `ws://127.0.0.1:*`.

### 2.2 Motor de impressão — **UM motor para todos os SOs: PDF → PDFBox → `javax.print`**

O agente **não renderiza HTML**. Ele imprime o **PDF de 80 mm que o backend já gera** (iText
`HtmlToPdfTermicoRenderer`) — fiscal (`/api/nfce/{id}/danfe`: DANFCe da Focus com QR PNG injetado), não-fiscal
(`/api/vendas/{id}/cupom/pdf`) e orçamento — pela API de impressão **multiplataforma do JDK**. O mesmo código roda em
Windows, Linux e macOS. É o desenho do **QZ Tray** (Java + PDFBox + `javax.print`), uma década em produção.

1. **Receber** o PDF (bytes, base64) do PWA pelo WebSocket local — o navegador já baixa o PDF autenticado (~20-100 KB).
2. **Rasterizar/paginar** com **Apache PDFBox 3** (`PDFPageable`/`PDFPrintable` com `Scaling.ACTUAL_SIZE`, ou
   `PDFRenderer.renderImageWithDPI` a ~203 dpi = resolução típica de térmica).
3. **Imprimir** — enumeração por nome via **`javax.print`** (`PrintServiceLookup`, **provado no Linux**: vê as
   impressoras do CUPS); submissão por SO:
   - **Windows:** `PrinterJob.setPrintService` + PDFBox (`PDFPageable`) → `print()` **sem diálogo** (spooler Win32
     nativo; status via `PrintJobListener`). *A validar na VM Windows.*
   - **Linux/macOS:** **PDF nativo direto ao CUPS via `lp -d <nome> -t <job> [-o media=Custom.80x<H>mm]`**
     (`ImpressoraCupsLp`). **Achado do spike F0 (2026-09-07):** o `PrinterJob` do OpenJDK no Unix **hardcoda
     `/usr/bin/lpr`** (`cups-bsd`, ausente em muitas distros — inclusive na máquina do dono) e converte tudo para
     PostScript; o `lp` (`cups-client`, presente onde há CUPS) recebe o PDF **vetorial** sem `lpr`, sem PostScript e
     sem rasterizar. Devolve `request id is <fila>-<n>` = aceito pelo spooler. `PrinterJob` fica como fallback se
     `lp` faltar. **Provado**: fiscal e não-fiscal saem no `cups-pdf`; `Custom.80x128mm` → 80×128 mm exatos;
     sem `-o media` → mídia padrão da impressora ("papel do driver", A4 no cups-pdf).
4. **Responder** `ACEITO_SPOOLER | IMPRESSORA_INDISPONIVEL | ERRO` ao PWA.

- **Papel — o elo frágil continua:** o driver da térmica pode ignorar o `Paper` custom e impor o preset (o mesmo
  fenômeno do #3421 no WebView2). Então: modo **"papel do driver"** (não definir mídia; o driver aplica seu preset
  80×N) é o **default por impressora**; **`Paper` 80 mm × H do PDF** é opt-in **validado em hardware**. Corte fica no
  driver ("cortar no fim do documento"). **Só a térmica real prova isso** → checkpoint de hardware do F3.
- **Confirmação honesta:** `printJobCompleted` / `print()` sem exceção = **aceito pelo spooler**, não "papel saiu".
  Contrato `ACEITO_SPOOLER | IMPRESSORA_INDISPONIVEL | ERRO`. O PWA diz "**enviado à impressora X**"; nunca grava
  "impresso" como fato fiscal. `PRINTED` real (fase G) só consultando o spooler/`lpstat`.
- **Listar impressoras:** `PrintServiceLookup.lookupPrintServices(null, null)` + `lookupDefaultPrintService()` —
  **multiplataforma**, sem `EnumPrinters`/`wmic`/`lpstat` na mão (no Linux/macOS o JDK conversa com o CUPS).
- **Agnóstico de marca (decisão do dono: "preparada para qualquer uma"):** **nenhum código por fabricante** — tudo pelo
  **driver do SO** via `javax.print`. Nada de ESC/POS por marca no MVP. **DoD de hardware:** validar em **≥3 marcas**
  que estiverem à mão (Elgin, Bematech, Epson são sugestões, não lista fixa) — critério: "qualquer driver instalado imprime".
- **Fidelidade — risco a provar CEDO:** o PDF do iText é **outro renderizador** que o Chromium do iframe; a fidelidade
  do DANFCe em 80 mm **nunca foi validada** (o front removeu `imprimirCupomPdf` como morto). O **spike do F0 no Linux
  do dono com `cups-pdf`** mostra o PDF de 80 mm na hora. **Plano B, dentro do Java:** o agente gera o PDF via
  Chrome/Edge **instalado** em headless (`--headless --print-to-pdf` — Chromium, mesma fidelidade do iframe; passar
  `--user-data-dir` p/ contornar a regressão do Edge 141) e imprime pelo mesmo `javax.print`. Troca o passo 1, não a língua.
- **Raw ESC/POS (USB/TCP 9100):** só **complemento**, só **não-fiscal** (gaveta/corte), fase G. Nunca para o DANFCe.
- **O que SAIU do plano com Java:** WebView2, `EnumPrinters`, SumatraPDF (GPL), shell para `lp` — substituídos por
  `javax.print` + PDFBox (Apache 2.0).

### 2.3 Stack — **DECIDIDO: Java 21** (Wagner, 2026-09-06 — "com certeza agora")

Motivo (requisitos do dono): **um código para todos os SOs**, na **linguagem que ele domina**, **código compartilhado
com o backend** (Java 21/Spring), **testável no Linux dele agora**. `javax.print` é a **única** API de impressão
multiplataforma entre as opções; .NET exigiria 2 motores (WebView2 no Windows + `lp` no Unix) e outra língua; Go/Rust
idem, sem o compartilhamento. Precedente: **QZ Tray**.

**Repositório próprio e irmão** do backend (`PROJETO AGROEASE/wagsyspet-agente/`), **público**, **Maven** (mesmo
`./mvnw` do backend), **Java 21** (mesmo Temurin do dono), **JUnit 5** (mesmo ritual TDD).

```
wagsyspet-agente/
├── pom.xml                        multi-módulo; Java 21; JUnit 5; JaCoCo
├── agente-protocolo/              mensagens (Jackson), códigos de erro, verificação de ticket Ed25519 —
│                                  COMPARTILHÁVEL com o backend (dependência ou cópia com teste de paridade)
├── agente-core/                   servidor WS 127.0.0.1 (Java-WebSocket — o mesmo do QZ), Origin/Host/ticket,
│                                  fila 1 job, pareamento (java.net.http), versão, strip @page (plano B).
│                                  Interfaces: Impressora, ListaImpressoras, ArmazenamentoCredencial, Autostart.
│                                  ZERO código de SO.
├── agente-impressao/              javax.print + PDFBox 3: lista por nome, imprime PDF (Paper 80mm×H ou "papel do
│                                  driver"), PrintJobListener → ACEITO_SPOOLER/INDISPONIVEL/ERRO. Multiplataforma.
│                                  Plano B: HTML→PDF via Chrome/Edge headless instalado.
├── agente-app/                    main: java.awt.SystemTray (3 SOs), autostart por SO (HKCU Run via `reg add`;
│                                  LaunchAgent plist; systemd --user), credencial em arquivo cifrado (AES-GCM) com
│                                  permissão 0600 / ACL do perfil, flags --instalar/--desinstalar/--silencioso,
│                                  self-update (F6). ImpressoraFake p/ dev.
└── .github/workflows/release.yml  matriz ubuntu/windows/macos (grátis em repo público): jlink (JRE enxuto) +
                                   jpackage → Windows .exe per-user (sem UAC), macOS .dmg (codesign ad-hoc),
                                   Linux .deb + tar.gz; sha256 + latest.json assinado Ed25519.
```

- **Dependências:** `org.java-websocket:Java-WebSocket` (MIT), `org.apache.pdfbox:pdfbox` 3.x (Apache 2.0), Jackson.
  Todo o resto é JDK (`java.net.http`, `java.security` Ed25519, `javax.print`, `java.awt`). **Sem JavaFX.**
- **Ed25519 mantido** (nativo no JDK 21 nos dois lados) — a troca para ECDSA que o .NET exigiria **não existe mais**.
- **Empacotamento:** `jpackage` (JDK 21) por SO, com runtime `jlink` (~40-60 MB) e `--java-options -Xmx128m`
  (agente de impressão precisa de pouco heap). Windows `--win-per-user-install` (perfil do usuário, **sem UAC**).
  Windows 7/8.1 fora (JDK 21 exige Win10+). O instalador é **1** binário não assinado → **1** aviso do SmartScreen.
- **RAM na bandeja:** ~120-200 MB com `-Xmx128m` + jlink (o QZ vive assim em milhares de lojas). Ok em 8 GB e 4 GB.
- **Testes no Linux do dono:** `./mvnw test` (JUnit 5) para core/protocolo; **impressão real** via `javax.print` →
  CUPS → `cups-pdf` (o caminho de produção, sem fake).

Registro do trade-off (para não reabrir): .NET venceria **só se** o alvo fosse "Windows-first com WebView2"; o dono
escolheu "todos os SOs, um código, minha língua". Go/Rust rejeitados (sem impressão multiplataforma; língua nova).

### 2.4 Pareamento, identidade e segurança do endpoint local (**obrigatório**, o canal é texto claro)

Qualquer site aberto no navegador — após o próprio prompt LNA — pode tentar falar com `127.0.0.1:PORTA`. Por isso
**três barreiras**, todas antes de qualquer job:

1. **Bind exclusivo 127.0.0.1**; checar `Host ∈ {127.0.0.1:porta, localhost:porta}` (anti DNS-rebinding).
2. **`Origin` exato** (scheme+host+porta) numa **allowlist** recebida no pareamento — fonte única =
   `cors.allowed-origins` do backend; previews Vercel **fora**; `Origin` ausente → recusa (HTTP 403 antes do upgrade).
3. **Ticket Ed25519** como 1ª mensagem (`{tipo:'auth', ticket}` em ≤5 s): emitido por `GET /api/agente/ticket`
   (autenticado; TTL 10 min; claims `lojaId, agenteId, exp, jti`); agente verifica assinatura (chave pública
   recebida no pareamento), `lojaId == pareado`, `agenteId == próprio`. Ed25519 nativo no **JDK 21**
   (`Signature("Ed25519")`) — **não** reusar o JWT de login (jjwt 0.11.5 não tem EdDSA; não subir versão só por isso).

Mais: limites (HTML ≤512 KB, fila ≤5, ≤30 jobs/min, 1 conexão autenticada por Origin, close 1008), só
impressoras cadastradas no agente, log local por job (origin, jti, impressora, tamanho, resultado).

**Pareamento (molde já homologado no código: `PortalContadorToken`/`PortalContadorService`):**
- Dono logado clica "Gerar código de pareamento" → `POST /api/agente-impressao/pareamento/token` (mesma
  `@PreAuthorize` do `ConfiguracaoGeralController`) → 32 B `SecureRandom` base64url, grava **só SHA-256** em tabela
  **PUBLIC** `agente_impressao_token` (`tenant_id, expira_em +15 min, usado_em, revogado, criado_por,
  agente_versao/so/hostname`); 1 pendente por loja (revoga anteriores); mostrado **1 vez**.
- Agente, no 1º run, chama `POST /api/public/agente-impressao/parear {token, versao, so, hostname}` (permitAll
  explícito **antes** de `anyRequest().authenticated()`; rate-limit no bucket **auth por IP** via `INVITE_PATHS` —
  anti força-bruta, vale até em localhost). **Tenant vem da LINHA do token, nunca de `X-Tenant-ID`.** Claim por
  **CAS** (`UPDATE … WHERE token_hash=? AND usado_em IS NULL AND revogado=false AND expira_em>now()`, rows≠1 → 404
  genérico) e **depois** `TenantContext.runWithTenant(tenant, () -> configuracaoGeralService.marcarAgentePareado(v))`
  em bean `@Transactional` separado (molde `WebhookNumeracaoTxService`). Método externo **sem** `@Transactional`.
- Resposta 1× `{agenteId, lojaId, credencial, chavePublicaTicket, origensPermitidas, portaSugerida, versaoMinima}`.
  Versão < mínima → `426 AGENTE_VERSAO_OBSOLETA`.
- **Segredo no agente (Java, por usuário):** arquivo `credencial.enc` no perfil do usuário
  (`%LOCALAPPDATA%\WagSysPet\agente` / `~/.config/wagsyspet-agente` / `~/Library/Application Support/...`), cifrado
  **AES-GCM** com chave derivada de um segredo por instalação, **permissão 0600** (Unix) / ACL do perfil (Windows,
  via `icacls` no `--instalar`). Revogável no backend a qualquer momento (é a barreira real). DPAPI via JNA fica
  como endurecimento opcional (fase G) — não entra no MVP para não puxar dependência nativa.
- **Desparear / trocar máquina:** `DELETE /api/configuracoes/geral/agente-impressao` → `agente_pareado=false`,
  `impressoraSelecionada=null`, `AGENTE→DIALOGO`, revoga tokens pendentes; tickets param de sair.
- **DECIDIDO: N caixas por loja desde o MVP** (o dono confirmou lojas com vários caixas, qualquer quantidade).
  Tabela **TENANT** `agente_impressao` (`id, nome_maquina, so, arch, versao, credencial_hash, criado_em,
  ultimo_visto, revogado_em`) criada **inline no `db/tenant/V1__baseline.sql`** (pré-produção, banco recriado) +
  regenerar `src/test/resources/tenant-schema-esperado.txt` (`validar-baseline.sh --gerar-golden`). Cada pareamento
  cria **uma linha = um caixa**; o `agenteId` vai no `hello` e nas claims do ticket (o backend só emite ticket para
  agente **ativo**). `agente_pareado` da config vira **derivado** (≥1 agente não revogado). Revogar um caixa =
  `DELETE /api/agente-impressao/{id}` (só aquele; tickets param de sair para esse `agenteId`).
- **Impressora é POR MÁQUINA, não por loja.** A escolha fica **no próprio agente** (config local), feita pelo painel do
  PWA que conversa com o agente **daquela** máquina (lista → escolhe → agente persiste). A coluna
  `impressora_selecionada` da loja **deixa de mandar no modo AGENTE** (fica como default/legado dos modos
  DIALOGO/DIRETO). Assim 2 caixas com impressoras diferentes nunca brigam.
- **Chaves separadas:** um par Ed25519 para **tickets** (privada em env do Railway) e **outro** para o **manifesto de
  release** `latest.json` (privada só no secret do CI; pública embutida no binário). Não misturar.

### 2.5 Distribuição, versão e auto-update

- **DECIDIDO (repo do agente PÚBLICO — Wagner, 2026-09-06):** o armazém é o **GitHub Releases** — banda ilimitada e
  grátis, URL estável `https://github.com/<org>/wagsyspet-agente/releases/download/v{versao}/AgroEase-Agente-Impressao-{versao}-{so}-{arch}.{ext}`
  (**nome do arquivo = marca AgroEase**, é o que o lojista vê na pasta Downloads; o caminho do repo pode ficar `wagsyspet-agente`)
  + `latest.json` como asset da release. **R2, `S3Presigner`, AWS SDK e credencial no Railway SAEM do plano.**
- **Entrega (DECIDIDO 2026-09-07 — dois lugares, PÚBLICA):** o download **não exige login**; o que exige é o pareamento.
  - `GET /api/public/agente-impressao/release` (**permitAll** em `SecurityConfig`, prefixo `/api/public/**` que já existe;
    bucket geral por IP; sem tenant) devolve JSON `{versaoAtual, versaoMinima, urls por SO/arch, sha256, tamanho}` lido de
    `app.agente-impressao.*` (env). É o mesmo JSON que o painel logado usa — **um só endpoint**, sem duplicar.
  - `GET /api/public/agente-impressao/download/{so}` (`windows | linux | macos-arm64 | macos-x64`) → **302** pro asset do
    GitHub Releases (`Cache-Control: no-store`; 404 tipado se o SO não existir). Serve pra `<a href>` e pra suporte
    mandar um link estável por WhatsApp.
  - **Página pública do front `app.agroease.com.br/agente`** (rota fora do login, molde `/extrato/crediario/:token`):
    detecta o SO pelo `navigator.userAgentData`/UA, botão "Baixar para Windows/Linux/macOS", sha256 visível, e as
    instruções por SO da §2.7 (SmartScreen/"Manter mesmo assim", macOS "Abrir mesmo assim", Linux `.deb`). Rodapé:
    "Depois de instalar, peça ao responsável da loja o **código de pareamento** em Configurações → Impressão".
  - **Painel logado** (`AgenteImpressaoPanel`) mantém o botão "Baixar agente" (chama o mesmo endpoint público) **ao lado
    de "Gerar código de pareamento"** — é o fluxo do dono; o técnico usa a página pública.
  - Por que é seguro ser público: o binário já é público no GitHub; **nenhum segredo no binário**; sem código de
    pareamento (só dono logado + `admin:configuracoes`, 1 uso, 15 min) o agente não fala com loja nenhuma.
- Repo público é seguro: **nenhum segredo no binário** — a única chave embutida é a **pública** de verificação.
- **Versão (auto-update MVP, sem troca de binário):** `hello {versao, protocolo, so, arch}`; backend expõe
  `app.agente-impressao.{versao-atual, versao-minima, protocolo-minimo}` por **env** (molde
  `TransferenciaPropriedades`; zero migration). PWA: `versao < mínima` → AGENTE indisponível → **fallback DIALOGO +
  banner bloqueante "Atualize o agente"** com o botão de download; `mínima ≤ versao < atual` → aviso discreto 1×/dia.
- **`latest.json` assinado Ed25519 desde o dia 1** (pré-requisito do self-update). **Self-update = fase G**
  (rename-swap `.old/.new` com rollback; o download feito pelo próprio agente **não** carrega Mark-of-the-Web →
  escapa do SmartScreen clássico, **mas não do Smart App Control**).
- Build/publicação: GitHub Actions (3 runners) roda `jlink` + `jpackage`, gera `sha256` e `latest.json` assinado, e
  anexa tudo à **GitHub Release** da tag.

### 2.6 Multiplataforma desde o dia 1 + desenvolvimento no Linux (decisão do dono: "deixar certo para todos os SOs")

**Princípio:** arquitetura para **todos** os SOs desde a 1ª release; cada SO só é declarado **pronto** quando o cupom
**sai no papel** naquele SO (impressão é empírica; regra fiscal é determinística). Sem hardware → **beta**.

**Com Java isso é natural:** `agente-core` e `agente-impressao` são **100% multiplataforma** (`javax.print` fala com o
spooler do Windows e com o CUPS do Linux/macOS). Só `agente-app` tem 3 implementações pequenas por SO (autostart;
pasta/permissão da credencial) — dezenas de linhas cada.

**Onde cada coisa é testada (o dono está no Linux, sem Windows e sem térmica):**
| Camada | Onde | Como |
|---|---|---|
| Core/protocolo/segurança/fila/pareamento | **Linux** | JUnit 5 (`./mvnw test`) — mesmo TDD do backend |
| **Impressão REAL no Linux** | **Linux do dono, SEM térmica** | **`cups-pdf`** (impressora virtual do CUPS → PDF em `~/PDF`): `javax.print` → CUPS → `cups-pdf` é o **caminho real**, não fake. `Paper` 80 mm gera PDF de 80 mm p/ conferir layout; `cupsdisable` simula fila offline |
| Integração navegador ↔ agente | **Linux** | `agente-app` rodando + PWA local (`npm run dev`) ou preview Vercel (origem pública → prompt LNA) imprimindo no `cups-pdf` |
| Impressão no **Windows** | **Windows (VM/PC), SEM térmica** | **mesmo jar**; impressora virtual **"Microsoft Print to PDF"** (já vem no Windows) → `javax.print` por nome → PDF; nome inexistente → `IMPRESSORA_INDISPONIVEL`. Runner `windows-latest` do CI como smoke |
| **Térmica REAL** | **Loja-piloto** (ou térmica USB ~R$200-400) | driver aceita `Paper` custom ou impõe preset? corte, avanço, contraste/leitura do QR, status sem-papel. **Checkpoint de hardware do F3** |
| Impressão em **macOS** | **Mac real** (dono não tem) | mesmo jar (`javax.print` → CUPS); sai **beta**; valida na 1ª loja Mac |

> **Sem térmica:** o F0 roda **inteiro** com impressoras virtuais — e, como o `javax.print` é o mesmo código em todo
> SO, o que passa no `cups-pdf` do Linux **já é o caminho de produção**. Bônus: nasce testado no driver **genérico**,
> sem viés de marca ("preparada para qualquer impressora").

**Toolchain (o dono já tem tudo):** JDK 21 Temurin, `./mvnw`, VS Code/IntelliJ. `jpackage` precisa rodar **no SO
alvo** → o CI (3 runners, grátis em repo público) gera os 3 instaladores; localmente o dono gera o `.deb`/tar do
Linux e roda o jar em qualquer SO.

**Onde o agente NÃO roda (impossível, não deferido):** iPad/iPadOS, tablets Android, Chromebook — seguem em
DIALOGO/DIRETO (navegador), que já funciona hoje.

**Railway não participa:** o agente é desktop; só o **backend** (F1) sobe no Railway pelo fluxo normal (merge main →
CI → deploy). Os instaladores vão para o GitHub Releases pelo CI do repo do agente.

### 2.7 Executável não assinado — o que o lojista vai ver (limites reais)

- **Windows/Edge:** download → "⋯ → Manter → Mostrar mais → **Manter mesmo assim**"; executar → "O Windows protegeu o
  computador → **Mais informações → Executar mesmo assim**". O SmartScreen **reavisa a cada versão** (reputação por
  hash) → a página de instruções é **permanente**, não one-off.
- **Smart App Control (Win11 novo, "avaliação → ligado"):** bloqueia **sem** opção de exceção. Única saída:
  Segurança do Windows → Controle de aplicativos e navegador → Smart App Control → **Desativado** (reativável sem
  reinstalar só com a atualização de abr/2026). Documentar e **medir incidência** nas lojas.
- **Defender ML:** falso-positivo `Trojan:Win32/Wacatac.B!ml` em binários não assinados (mais comum em Go/Rust).
  Mitigar: sem UPX, `VERSIONINFO`, submeter cada release em microsoft.com/wdsi/filesubmission, publicar SHA-256.
- **macOS 26 Tahoe:** binário **sem nenhuma assinatura é bloqueado sem bypass**. Solução grátis: **assinatura ad-hoc**
  no CI (`codesign --force -s - <binário>`, **sem** `--deep`, sem Apple ID). Usuário: "Apple não pôde verificar" →
  Ajustes → Privacidade e Segurança → "Abrir Mesmo Assim" (janela ~1 h) → senha. Ctrl-clique **não funciona mais**.
  → macOS entra na **fase M**, não no MVP.
- **Formato (Java/jpackage):** instalador **por SO** gerado pelo `jpackage` com runtime `jlink` embutido (o lojista
  **não** precisa ter Java): Windows `.exe` **per-user** (`--win-per-user-install`, instala no perfil, **sem UAC**,
  atalho no menu Iniciar); macOS `.dmg` com `codesign` ad-hoc; Linux `.deb` + `tar.gz`. **1 binário não assinado por
  SO → 1 aviso** (SmartScreen no instalador; o launcher instalado não carrega Mark-of-the-Web). Autostart gravado no
  1º run (**HKCU Run** via `reg add`; LaunchAgent; `systemd --user`). Flag `--desinstalar`.

---

## 3. Encaixe no código existente (o que muda, arquivo por arquivo)

### Backend — **FEITO no F1 (2026-09-07, worktree `agente-f1`, não commitado)**; DDL inline nos V1 (public + tenant), sem Vx novo. Detalhe do que foi entregue e do adversarial: `wagsyspet-backend/docs/agente-impressao-f1-backend-plano-lotes.md` (repo privado)
| Arquivo | Mudança |
|---|---|
| `dto/ConfiguracaoGeralDTO.java:85-89` | `Boolean agentePareado` **read-only** (ignorado no PUT); `@Size(max=120)` em `impressoraSelecionada` |
| `service/ConfiguracaoGeralService.java:176-182` | `impressoraSelecionada`: não-null→trim, **isBlank→null** (hoje "null preserva" impede limpar); guard `AGENTE` sem `agentePareado` → `BusinessException` (molde F-08 l.152-169); `toDTO` seta `agentePareado` |
| `service/ConfiguracaoGeralService.java` (novos) | `@Transactional marcarAgentePareado(versao)` (dentro de `runWithTenant`), `desparear()`, `@Transactional(readOnly) buscarImpressao()` → DTO leve **sem LAZY** (OSIV off) |
| `controller/ConfiguracaoGeralController.java:77` | **`GET /impressao` com `isAuthenticated()`** (ver §7 — corrige bug pré-existente) → `{modoImpressao, impressoraSelecionada, agentePareado}`; `DELETE /agente-impressao` (RBAC igual l.37) |
| `controller/AgenteImpressaoController.java` (NOVO, `/api/agente-impressao`) | `GET /ticket?agenteId=` (`isAuthenticated()`; caixa ativo NESTA loja senão 404), `GET /caixas`, `DELETE /{id}`, `POST /pareamento/token` (RBAC de config). Bucket geral do rate-limit (prefixo não é fiscal). Versão/release ficou só no endpoint público |
| `controller/AgenteImpressaoPublicController.java` (NOVO, `/api/public/agente-impressao`) + `SecurityConfig` (`permitAll` na linha dos outros `/api/public/**`) | `GET /release` (JSON versão/URLs/sha256 — **sem login**, é o que o painel E a página pública consomem) e `GET /download/{so}` (**302** pro asset do GitHub Releases, `no-store`, 404 tipado `AGENTE_SO_DESCONHECIDO`). Sem tenant (o `TenantFilter` já marca `public`). Teste: `BaseSecurityTest` prova 200/302 **sem JWT** e que `/api/agente-impressao/ticket` continua 401 sem JWT |
| `controller/AgenteImpressaoPublicController.java` (NOVO, `/api/public/agente-impressao`) | `POST /parear`; erro sempre **404 genérico** + `no-store` (molde `PortalContadorPublicController:22-25`); **não ler `X-Tenant-ID`** |
| `service/impressao/AgenteImpressaoService.java` (NOVO) | `emitirToken()` / `parear()` (CAS + `runWithTenant`) / `emitirTicket()`; `AgenteTicketService` Ed25519 JDK 21 |
| `model/AgenteImpressaoToken.java` + `repository/…` (NOVOS) | `@Table(schema="public")`, **sem `BaseEntity`** (o listener sobrescreveria `tenant_id` — molde `PortalContadorToken:7-15`); `findByTokenHash`, `@Modifying consumir(...)`, `revogarPendentesDoTenant` |
| `db/migration/V1__Create_public_schema_tables.sql:344` | `CREATE TABLE public.agente_impressao_token (…)` + índice `tenant_id`. Regime vigente = V1 public in-place + recriar banco (como `portal_contador_token`); **com dados vivos → V2 public forward-only** |
| `db/tenant/V1__baseline.sql` (após l.523) + `src/test/resources/tenant-schema-esperado.txt` | **NOVA tabela TENANT `agente_impressao`** (`id, nome_maquina, so, arch, versao, credencial_hash, criado_em, ultimo_visto, revogado_em, tenant_id`) — **N caixas por loja**. Inline no V1 (pré-produção) + **regenerar o golden** (`validar-baseline.sh --gerar-golden`). Entity `AgenteImpressao` (tenant, `extends BaseEntity` ok) + repository |
| `dto/ConfiguracaoGeralDTO.java` (complemento) | `agentePareado` passa a ser **derivado** (≥1 agente ativo); adicionar `agentes[]` resumido (`id, nomeMaquina, so, versao, ultimoVisto`) para o painel listar/revogar caixas |
| `controller/AgenteImpressaoController.java` (complemento) | `DELETE /{id}` revoga **um** caixa (RBAC de config); `GET /ticket` só emite se o `agenteId` informado estiver **ativo** no tenant |
| `config/SecurityConfig.java:109` | `.requestMatchers("/api/public/agente-impressao/**").permitAll()` **antes** de `anyRequest()` (l.152) |
| `config/RateLimitFilter.java:79-84` | **Só a rota exata** `"/api/public/agente-impressao/parear"` em `INVITE_PATHS` (o match é `startsWith`; o prefixo jogaria `/release` e `/download` no bucket auth). **Não** tocar `FISCAL_PATHS`. Bônus do adversarial F1: bucket por tenant só com `Authorization` (anônimo com `X-Tenant-ID` forjado drenava a cota da loja vítima — pré-existente) |
| `AgenteImpressaoPropriedades.java` (NOVO) + `ErrorCode` + `GlobalExceptionHandler` + `application*.properties` | `app.agente-impressao.{versao-atual, versao-minima, protocolo-minimo, token-validade-min, download.url-windows, download.url-linux, download.url-macos-arm64, download.url-macos-x64, sha256.*}` (URLs do **GitHub Releases**, por env no profile railway); `AGENTE_TOKEN_INVALIDO` (404), `AGENTE_VERSAO_OBSOLETA` (426), `AGENTE_NAO_PAREADO` |
| `pom.xml` (backend) | **Nenhuma dependência nova** (R2/AWS SDK saíram com o repo público; Ed25519 é nativo no JDK 21). Opcional: consumir `agente-protocolo` como dependência para não duplicar mensagens/erros |
| `application-railway.properties` | `cors.allowed-origins` = fonte da allowlist de `Origin` devolvida no pareamento |
| Testes | estender `ConfiguracaoGeralModoImpressaoIntegrationTest`/`ControllerTest`; novo `AgenteImpressaoPareamentoIntegrationTest` (`AbstractTenantIntegrationTest`) — inclui **adversarial `X-Tenant-ID` apontando outro tenant** (deve cair no tenant do token) |

### Frontend — **FEITO no F2 (2026-09-09, worktree `wagsyspet-frontend/.claude/worktrees/agente-f2`, branch `worktree-agente-f2`, não commitado)**; gates: `lint:ci` 124/131 (+0), `tsc -b` 0, vitest 3153 verdes, `build` OK. Detalhe, adversarial (44 achados) e contrato F3: `wagsyspet-backend/docs/agente-impressao-f2-frontend-plano-lotes.md` + `docs/recon-f2/` (repo privado)

> **A tabela abaixo é o PLANO; onde diverge, vale o CÓDIGO (resumo em §7.4):** `hello_ok{agenteVersao,protocolo,so,agenteId}` (não `versao`);
> `OpcoesImpressao` é tipo próprio (`modoImpressao, impressoraSelecionada, agentePareado`); `agenteClient` fecha após **20 s** ocioso (não 60), handshake
> 3 s / **45 s** quando a permissão LNA está em `prompt`, `hello` **antes** do `auth` (release/ticket entre `hello_ok` e `auth`, ticket pré-buscado quando o
> `agenteId` já é conhecido), reconexão **1×** na mesma porta se o agente fechar nessa janela ou recusar `EXPIRADO/REPETIDO`; a impressora vive **no agente**
> (`listar_impressoras` → `selecionada`; `selecionar_impressora{nome}`), erro `ImpressoraNaoDefinida` só **depois** de listar; hooks reais:
> `useConfiguracaoImpressao/useReleaseAgente/useCaixasPareados/useGerarCodigoPareamento/useRevogarCaixa/useDesparearLoja/useAgenteConexao/
> useAvisoPromptAgente/useOpcoesImpressao/useImprimirComFallback`; `mensagensImpressao.descreverErroImpressao(err, {host, publico:'operador'|'dono'})` com
> ações `IMPRIMIR_PELO_NAVEGADOR|CONECTAR_AGENTE|TENTAR_DE_NOVO|…`; `vite.config.ts` **ganhou** `NetworkOnly` para `/api/agente-impressao/ticket`
> (antes da regra genérica); o código de pareamento vive na **página** (`ConfiguracaoGeral`), some quando aparece um caixa de **id novo** na lista
> (nunca por `agentePareado`, que esconderia o código do 2º caixa), countdown informativo (não apaga); painel guarda a última verificação (`ultimaInfo`).
| Arquivo | Mudança |
|---|---|
| `services/impressao/tiposImpressao.ts:9-21` | Contrato: `imprimir(html, ctx?: {impressora?}) → Promise<ResultadoImpressao{confirmado, impressora?}>`; `OpcoesImpressao = Pick<ConfiguracaoGeral,'modoImpressao'\|'impressoraSelecionada'>`. DIALOGO/DIRETO devolvem `{confirmado:false}` |
| `services/impressao/resolverEstrategia.ts:11-14` | **1 linha:** `AGENTE: estrategiaAgente` |
| `services/impressao/cupomPrinter.ts` + `imprimirDocumentoVenda.ts` | Aceitam `OpcoesImpressao` (não só o modo) e devolvem `ResultadoImpressao`. **Bifurcação por modo na fachada:** `AGENTE` → baixa o **PDF** (`nfceService.baixarDanfe` já existe; novo `vendaService.baixarCupomVendaPdf` p/ `/api/vendas/{id}/cupom/pdf`; `orcamentoService.gerarPdf` já existe) e envia bytes ao agente; `DIALOGO/DIRETO` → HTML → iframe, como hoje. O agente Java **não recebe HTML** |
| **NOVO** `agenteProtocolo.ts` | Mensagens v1 (espelho do módulo Java `agente-protocolo`): `hello/hello_ok{agenteId,versao,so}`, `auth{ticket}`, `listar_impressoras/impressoras`, `imprimir{id, formato:'pdf', bytesBase64, impressora}/imprimir_ok{id,estado}/imprimir_erro{id,codigo,mensagem}`, `ping/pong`; erros tipados (`AgenteIndisponivel`, `AgenteNaoPareado`, `AgenteDesatualizado`, `ImpressoraNaoDefinida`, `ImpressaoRecusada`). Limite de payload: PDF ≤ 2 MB (base64) |
| **NOVO** `agenteClient.ts` (+ test com FakeSocket) | Singleton lazy; `ws://127.0.0.1:<porta>` (lista curta, porta boa em localStorage c/ try/catch); handshake 3 s; correlação por `id`; timeout 15 s; fecha após 60 s ocioso; **sem loop de reconexão** |
| **NOVO** `estrategiaAgente.ts` (+ test) | Recebe **PDF (bytes)** + impressora; sem impressora configurada no agente → erro antes de conectar; sucesso (`ACEITO_SPOOLER`) → `{confirmado:true, impressora}`; falha → `AgenteIndisponivelError` (**não** cai em DIALOGO sozinho). Não injeta CSS (o PDF já é 80 mm) |
| **NOVO** `mensagensImpressao.ts` | `descreverErroImpressao(err) → {mensagem pt-BR, acao?: 'FALLBACK_DIALOGO'\|'ABRIR_CONFIG'}` incl. orientação LNA |
| `hooks/usePDVPage.ts:1577-1610`, `pages/Vendas.tsx:151-176`, `hooks/useOrcamento.ts:705-716` | Passar `configGeral` (não `.modoImpressao`); `if (r.confirmado) success('Cupom enviado à impressora X')`; erro → toast com ação **"Imprimir pelo navegador"** (1 clique → DIALOGO). Trocar o hook pelo **`GET /impressao` leve** (§7) |
| `hooks/useConfiguracaoGeral.ts:51-54, 87-90` | `agentePareado?: boolean` (read-only); enviar `''` para limpar impressora |
| **NOVO** `hooks/useAgenteImpressao.ts` (+ test) | React Query: `useAgenteStatus` (retry:false, staleTime 15 s, refetch 30 s **não** em background), `useAgenteImpressoras`, `useReleaseAgente`, `useGerarCodigoPareamento`, `useImprimirTesteAgente`; invalidar `['configuracao-geral']` ao parear |
| `pages/ConfiguracaoGeral.tsx:470-508` | Habilitar rádio AGENTE (tirar `disabled`/"em breve"); renderizar `<AgenteImpressaoPanel/>` |
| **NOVO** `pages/AgenteImpressaoDownload.tsx` (+ test) — rota **pública** `/agente` no `App.tsx`, fora do login, ao lado de `/extrato/crediario/:token` | Página que a loja/técnico abre sem senha (`app.agroease.com.br/agente`): detecta o SO, "Baixar para Windows/Linux/macOS" via `GET /api/public/agente-impressao/release`, sha256, instruções por SO (§2.7), aviso "peça o código de pareamento ao responsável da loja". **Marca AgroEase em todo texto** |
| **NOVO** `components/configuracao/AgenteImpressaoPanel.tsx` (+ test) | Status (não detectado / vX não pareado / pareado / desatualizado); "Baixar agente" (mesmo endpoint público) + link "página de instalação" (`/agente`) + instruções **por SO** (Edge/SmartScreen/SAC/macOS); "Gerar código de pareamento" (1×, expira 15 min); **"Conectar agente" (gesto → LNA)** + estado da permissão + guia de reativação; `<select>` impressoras + "Atualizar" + "Imprimir teste" (mostra o estado devolvido); "Desparear"; aviso Safari/iPad não suportado |
| `services/impressao/imprimirViaIframe.ts` | **Sem mudança** (o caminho AGENTE usa PDF, não reusa o CSS do iframe) |
| `vite.config.ts` | **Nenhuma** regra nova (WebSocket não passa pelo Service Worker) |

### Agente (repo **novo, irmão, público** — Java 21 / Maven) — **FEITO no F3 (2026-09-09, branch `feat/agente-f3-mvp`, não commitado)**
Módulos como entregues: `agente-protocolo` (mensagens, `ProtocoloVersao.ATUAL=1`, `VerificadorTicket` Ed25519 **byte-idêntico ao do
backend** + vetores compartilhados, `VerificadorAssinaturaRelease` para o `latest.json`), `agente-core` (`ServidorAgente` Java-WebSocket em
127.0.0.1 com Origin/Host/ticket/limites/prazos do contrato §7.4, `Sessao`, `FilaImpressao` 1 thread + fila 2 + detecção de motor preso,
listagem com prazo, `PortaImpressao`, `ConfiguracaoLocalArquivo` (fonte única, relê por mtime), `SubidaComFallback` 28421→28422,
`VersaoDoBinario` do MANIFEST, `pareamento/` = `DiretoriosDoAgente`, `CofreCredencial` AES-256-GCM 0600, `IdentidadeDaMaquina`,
`ClientePareamento` HTTP/1.1 com aviso de relógio pelo header `Date`), `agente-impressao` (`ImpressoraCupsLp` via `lp` com prazo real,
`ImpressoraJavaxPrint` PrinterJob+PDFBox, `AquecedorPdfBox` com fontcache na pasta do agente), `agente-app` (`Main`/`Argumentos`/
`LogDoAgente`/`TravaDeInstancia`, `AgenteDesktop` orquestrador com zelador do cofre e re-subida 3/10/30 s, `Diagnostico` com relógio × servidor,
`ComandosPareamento`/`ComandosAutostart`, `ui/` bandeja AWT ou janela Swing com `erroFatal` bloqueante, `autostart/` HKCU Run / LaunchAgent /
systemd --user + `.desktop`, `ChaveDeRelease`), `ci.yml` reutilizável (ubuntu-22.04 / windows / macos, smoke do binário imprimindo,
autostart, codesign) e `release.yml` por tag (4 runners incl. `macos-15-intel`, `SHA256SUMS`, `latest.json` + `.sig`, envs do Railway no resumo).
Detalhe lote a lote, decisões D1–D25 e a tabela do adversarial: `wagsyspet-backend/docs/agente-impressao-f3-agente-plano-lotes.md` (repo privado).

---

## 4. Fases (P = pequena · M = média · G = grande) — cada uma com o ritual completo
> Recon → **TDD (RED antes do GREEN)** → unitário → **integração** → **adversarial multi-agente** → conferir doc vs código.
> Trabalho em **worktree** (sessão concorrente no backend). Commits só a pedido; trailer `Co-Authored-By: Claude Fable 5.1`.

| # | Fase | Tam. | Entregas | DoD (Definition of Done) |
|---|---|---|---|---|
| **F0** | **Spikes** (sem código de produto; **sem hardware**) | P | (a) esqueleto Maven multi-módulo (`agente-protocolo/core/impressao/app`) + JUnit 5, no Linux; (b) **spike de impressão no Linux:** PDF do backend → PDFBox → `javax.print` → **`cups-pdf`** em 80 mm — **fidelidade do iText** (DANFCe fiscal + não-fiscal), `Paper` custom vs "papel do driver", `PrintJobListener`, fila offline (`cupsdisable`), nome inexistente → `IMPRESSORA_INDISPONIVEL`; **decide o plano B** (headless) se a fidelidade não bastar; (c) **spike Windows (VM, se houver):** **mesmo jar** → "Microsoft Print to PDF"; (d) **spike `ws://127.0.0.1` + LNA** em Chrome/Edge/Firefox — **capturar textos pt-BR** dos prompts/telas de reset (Java-WebSocket no Linux); (e) **Ed25519 Java↔Java:** backend assina, agente verifica; (f) `jpackage`+`jlink` smoke no CI (3 runners) | Relatório de spike com veredito por item; PDFs de 80 mm gerados no `cups-pdf` (layout conferido); decisão fidelidade/plano B. Checkpoint de **térmica real** fica no F3 (loja-piloto) |
| **F1** | **Backend mínimo** — ✅ **FEITO 2026-09-07** (worktree `agente-f1` do backend, **não commitado**) | P/M | Tudo de §3-Backend **+ o que era do F4 no backend**: `release`/`download/{so}` públicos, `GET /caixas` + `DELETE /{id}`, desparear a loja, health com avisos. 221 casos verdes (20 classes); adversarial workflow: 13 confirmados corrigidos (1 ALTA pré-existente no rate limit), 1 refutado — §7.3 | Testes de integração verdes incl. **adversarial `X-Tenant-ID`** ✅; golden regenerado ✅; adversarial workflow ✅ |
| **F2** | **Frontend** — ✅ **FEITO 2026-09-09** (worktree `agente-f2` do frontend, **não commitado**) | M | Tudo de §3-Frontend (L1–L6) **+ o que era do F4 no front** (página pública `/agente`, painel de caixas pareados com revogar/desparear, versão mínima bloqueante, KB): contrato `ResultadoImpressao`, `agenteClient` (LNA, portas, ticket, fila) + `FakeSocket`, `estrategiaAgente`, bifurcação HTML×PDF na fachada, `mensagensImpressao` (operador × dono), hooks, 7 call-sites com toast + "Imprimir pelo navegador", `AgenteImpressaoPanel`, rádio AGENTE habilitado. Adversarial: 44 achados (2 ALTA) corrigidos/registrados — §7.4 | `tsc -b` 0 ✅; `lint:ci` 124/131 ✅; vitest 3153 ✅ (34 casos do cliente com FakeSocket); `build` ✅; adversarial ✅; **sem agente funciona igual a hoje** (todo agente F0 é tratado como "desatualizado" até o F3) |
| **F3** | **Agente Java multiplataforma MVP** — ✅ **CÓDIGO FEITO 2026-09-09** (branch `feat/agente-f3-mvp`, **não commitado**; falta o teste manual real e a 1ª tag) | G | Repo novo (§2.3): `agente-protocolo` (compartilhável c/ backend), `agente-core` (Java-WebSocket 127.0.0.1, 3 barreiras, `hello/auth/listar/imprimir/ping`, fila 1 job, pareamento, versão), `agente-impressao` (`javax.print` + PDFBox: lista por nome, PDF 80 mm, "papel do driver" default / `Paper` opt-in, `PrintJobListener` → estado), `agente-app` (`SystemTray`, autostart HKCU Run/LaunchAgent/systemd, credencial AES-GCM 0600, `--instalar/--desinstalar`). CI: jlink + jpackage nos 3 runners → `.exe` per-user, `.dmg` ad-hoc, `.deb`/tar + sha256 + `latest.json` assinado | **Um só código** imprimindo nos 3 SOs. **Linux:** validado na **máquina do dono** (`cups-pdf`; térmica real quando houver). **Windows:** validado em VM/PC ("Print to PDF") + **checkpoint em térmica real na loja-piloto** (≥3 marcas: `ACEITO_SPOOLER`, papel/corte). **macOS:** construído e assinado ad-hoc, sai **BETA** (o dono não tem Mac). Ticket inválido/Origin/Host errados **recusados** (JUnit). Adversarial aprovado. **Estado:** 261 casos verdes por XML (protocolo 80, impressão 31, core 127, app 23); L0–L6 ✅; adversarial 5 lentes ✅ (§7.5); Linux validado na máquina do dono contra o `cups-pdf` (`ImpressaoRealEnvTest` + smokes); Windows/macOS só pelo CI até a 1ª release; **pendente:** roteiro manual PDV real → agente (doc privado), passo manual do ambiente `release` no GitHub, tag `v1.0.0` |
| **F4** | **Distribuição + endurecimento** (encolhida) | P/M | ~~`release` + `/download/{so}`~~ (**F1**); ~~página pública `/agente`, versão mínima bloqueante no PWA, painel de caixas pareados~~ (**F2**). Resta: preencher as envs `AGENTE_URL_*`/`AGENTE_SHA256_*` por release; submissão ao Defender por release; conferir os textos da página `/agente` contra os prompts REAIS do SmartScreen/Gatekeeper na 1ª release | Instalação do zero numa máquina limpa Win10, Win11 e Linux seguindo só a página; bloqueio por versão testado ponta a ponta; revogar um caixa corta os tickets dele |
| **F5** | **Validações remanescentes + Safari** | M | **macOS em hardware real** (sai do beta); **contingência CA por máquina + `wss://` (folha ≤825 d)** só se houver loja em **Safari**; **plano B de fidelidade** (Chrome/Edge headless → PDF) só se o spike do F0 mostrar que o iText não basta. Windows 7/8.1 **fora** (JDK 21 exige Win10+) — documentar | Mac instala e imprime validado; Safari (se houver) conecta sem aviso |
| **F6** | **Self-update + extras** | G | Self-update rename-swap verificando `latest.json` Ed25519 (chave de release separada); `PRINTED` via `GetJob/EnumJobs`; raw ESC/POS **não-fiscal** (gaveta/corte); ícone de bandeja | Update de vX→vY sem intervenção; rollback testado |
| — | **Deferido (fora do plano)** | — | Cupom **não-fiscal offline** (venda offline não tem id; HTML só existe no backend — builder no front duplicaria a verdade); iPad/Safari via LAN | — |

**Ordem recomendada:** F0 → mergear Fase 0 na main → F1 → F2 → F3 → F4 → F5 → F6. (F1 e F2 podem andar em
paralelo depois do F0; F3 depende da stack decidida no F0.)

---

## 5. Riscos principais e mitigação

| Risco | Mitigação |
|---|---|
| Papel `Custom` ignorado/travado pelo driver térmico (`javax.print`/spooler/CUPS) | "Papel do driver" default; `Paper` opt-in validado em hardware; documentar preset 80×N + corte no driver |
| **Fidelidade do PDF iText ≠ Chromium do iframe** (nunca validada em 80 mm) | Spike **F0** no `cups-pdf` decide cedo; **plano B** dentro do Java: Chrome/Edge instalado em headless gera o PDF (Chromium) e imprime igual |
| `Succeeded` sem papel (tampa aberta/sem bobina em USB) | Contrato honesto `ACEITO_SPOOLER`; nunca "impresso" como fato fiscal; `PRINTED` via `GetJob` em G |
| Operador dispensa o prompt LNA 3× → embargo ~7 dias | Conexão só por gesto; orientar **antes**; detectar `denied`; guia de reativação por browser |
| Smart App Control bloqueia sem exceção | Documentar desativação; medir incidência; assinatura fica como decisão futura sua |
| Defender falso-positivo apaga o exe | Sem UPX, `VERSIONINFO`, submissão por release, SHA-256 publicado |
| JVM na bandeja pesar em PC fraco (~120-200 MB) | `jlink` (JRE enxuto) + `-Xmx128m`; precedente QZ Tray em milhares de lojas; medir na loja-piloto |
| Java-WebSocket/PDFBox com CVE futura | Dependabot no repo público; libs Apache/MIT amplamente mantidas; poucas dependências |
| Canal em texto claro | 3 barreiras obrigatórias (bind, Origin+Host, ticket) + limites + só impressoras cadastradas |
| Chave privada do `latest.json` vaza → binário malicioso via self-update | Par de chaves **separado**, privada só no secret do CI, rotação documentada, pública com refresh no pareamento |
| `impressoraSelecionada` é por LOJA, impressora é por MÁQUINA | MVP 1 agente/loja; override local por máquina (localStorage) ou tabela tenant em F4 |
| Rollback envenenado / tenant errado no pareamento | `parear()` sem `@Transactional`; CAS primeiro; `runWithTenant` + bean `@Transactional` separado; teste adversarial `X-Tenant-ID` |
| Folga de lint = 7 warnings | Painel em componente próprio; React Query (não `useEffect+setState`) |
| Editar V1 public in-place com banco vivo | Só no regime "recriar banco" (gate de deploy já existente); senão V2 public |

---

## 6. Perguntas abertas — **TODAS DECIDIDAS (Wagner, 2026-09-06)**

1. **Stack** → **Java 21** (§2.3), "com certeza agora". Ed25519 **mantido** nos dois lados (nativo no JDK 21).
2. **Porta** → **fixa + 1 fallback**, faixa 20000–32767 (fora do ephemeral de Linux/Windows; longe de 8181-8183 do
   QZ e do 9090 do backend), ex.: 28421/28422. Cliente memoriza a que funcionou. Zero custo em LNA.
3. **Repo do agente** → **PÚBLICO**. GitHub Releases é o armazém primário; R2/presign/AWS SDK saíram (§2.5).
4. **Zona Cloudflare** → **irrelevante** (sem R2 não há domínio custom a configurar).
5. **Térmicas** → **"preparada para qualquer uma"**: design agnóstico de marca via driver do SO (§2.2); DoD = ≥3 marcas
   disponíveis, não uma lista fixa.
6. **Caixas por loja** → **N, qualquer quantidade, desde o MVP**: tabela tenant `agente_impressao` na F1; impressora
   **por máquina** (no agente), `agente_pareado` derivado, revogação por caixa (§2.4).
7. **`GET /ticket`** → **`isAuthenticated()`** (o ticket só autoriza falar com o agente local da própria máquina;
   as permissões reais já são cobradas nos endpoints do HTML).
8. **SOs** → Windows + Linux **validados** no MVP; **macOS BETA** (o dono **não tem Mac** — valida na 1ª loja Mac);
   iPad/tablet impossível (navegador); contingência CA só se surgir loja em Safari.

---

## 7. Bug pré-existente encontrado na recon (afeta até o modo DIRETO hoje)

`useConfiguracaoGeral` chama `GET /api/configuracoes/geral`, que exige **`admin:configuracoes` + plano**. Um
**funcionário do PDV sem essa permissão recebe 403** → `configGeral` fica `undefined` → `resolverEstrategia`
cai em **DIALOGO em silêncio** — ou seja, o modo da loja (DIRETO hoje, AGENTE amanhã) **nunca chega ao caixa
comum**. Correção (F1/F2): endpoint **leve** `GET /api/configuracoes/geral/impressao` com `isAuthenticated()`
(sem expor contas financeiras/taxas) e trocar o hook nos 5 call-sites de impressão.

### 7.1 Adversarial F0 do `agente-core` (2026-09-07, 17 agentes: 4 lentes + 1 cético por achado) — o que ficou pro F3

Confirmados e **corrigidos no F0** (todos com teste RED→GREEN em `ServidorAgenteTest`): teto de frame 4 MB
(`Draft_6455` explícito — o default aceitava 2 GB e **pré-alocava o tamanho declarado no header**: 14 bytes → OOM);
frame binário → `TIPO_BINARIO_NAO_SUPORTADO`; `RuntimeException` no `onStart` (a lib só protege `IOException`) →
`iniciar()` falha rápido e derruba; timeout do `iniciar()` derruba (sem órfão em thread não-daemon); `falhaFatal()`
(a lib, num erro fatal pós-start, chama `stop(0)` na própria selector-thread = self-join eterno, processo vivo e
mudo) + `estaEscutando()` (self-connect TCP; a lib **fecha o LISTEN em silêncio** num `EMFILE` no accept, `log.trace`,
processo segue vivo) + watchdog de 30 s no `AgenteMain` que sai com código 3; teste de socket pro Host (rebinding e
porta errada); recusas assertadas pelo **404 real** (não passam contra servidor morto); CI em 2 passos (unitários de
todos os módulos sempre; impressão virtual à parte — o reator fail-fast escondia o `agente-core` como SKIPPED).

**Herdado pro F3 (não corrigível no F0 ou é decisão de produto):**
- **Bind falho vaza 1 fd** (`ServerSocketChannel` aberto antes do bind, nunca fechado; `stop()` não alcança; o ctor
  com canal pré-bindado não aceita `drafts`). Regra: **nunca fazer retry em processo** criando instância nova (a
  instância não é reiniciável); em porta ocupada o processo **sai ≠ 0** e o supervisor do SO (serviço Windows /
  systemd / launchd) reinicia com backoff — é o mesmo caminho do watchdog.
- **3ª barreira = ticket Ed25519 via `auth`, mas DEPOIS do `hello`** (decisão do F2, 2026-09-07/09 — substitui o "ticket como
  1ª mensagem"): `hello` continua pré-auth e `hello_ok` carrega `agenteId` (UID público, inútil sem o JWT da loja) — o PWA precisa
  dele para pedir o ticket na 1ª conexão da máquina. Antes do `auth` o agente aceita só `hello`/`ping`; qualquer outro tipo →
  `erro` + `close 1008`. **Prazo do `auth`: ≥ 60 s contados do `hello_ok`** (o cliente pode fazer `GET /release` + `GET /ticket`,
  cada um com axios 45 s no cold start do Railway; 30 s não cobre). Detalhe completo do contrato em §7.4.
- **Teto de frame vale PRÉ-auth** (camada WS, antes do ticket) — já é assim; manter ao trocar de draft/lib.
- **Recusa é 404, não 403** (hardcoded na lib) — só cosmético; não gastar tempo.
- `estaEscutando()` faz conexão TCP crua: a lib **não** chama `onOpen/onClose` para conexão sem handshake (conn nunca
  entra em `connections`) → sem ruído de log. Se um dia o log de handshake incompleto virar INFO, reavaliar.
- Processo local hostil "se passando pelo agente" = fora do modelo de ameaça (já roda no caixa; lê tela/cookies).

### 7.2 Adversarial F0 do ticket Ed25519 (`agente-protocolo`, 2026-09-07, 12 agentes: 3 lentes + céticos)

**Verificado por fora e ficou de pé:** assinatura conferida com **OpenSSL 3.5** (`pkeyutl -verify -rawin`) e com oráculo Java
standalone sem as classes do repo; `KeyFactory("Ed25519")` recusa SPKI de Ed448/X25519/RSA; assinatura de 63/65/114 bytes
recusada; sem maleabilidade criptográfica (JDK rejeita S≥L); **fuzz de 85.800 entradas + 80.000 chamadas em 16 threads**
→ zero exceção fora de `TicketInvalidoException`, replay concorrente aceito exatamente 1×; `Signature.getInstance` custa
68 ns (verify ≈ 0,5 ms); purga do cache é O(n) mas n só cresce com tickets **válidos**; Jackson 2.19 já limita profundidade
e tamanho de número.

**Confirmados e corrigidos (RED→GREEN, 10 testes novos + 21 vetores novos = 37 no arquivo):**
- **Sem teto de validade**: `exp` em milissegundos (bug de unidade no emissor) virava ticket de 58 mil anos, aceito, com
  `jti` eterno no cache; `Long.MAX` só caía por overflow. Agora `exp − iat ≤ 1 h` **e** `exp − agora ≤ 1 h`, senão
  `VALIDADE_ABSURDA`; comparações sem soma (sem overflow); `iat > 0` e `exp ≥ iat` obrigatórios.
- **Chave pública inválida sem fail-fast**: o `KeyFactory` aceita SPKI Ed25519 com ponto fora da curva (metade dos 32
  bytes aleatórios); só o `initVerify` descobre — no 1º ticket, com mensagem "falta jdk.crypto.ec". Agora
  `ChavesTicket.exigirPublicaUtilizavel` roda no `importarPublica` e no construtor do verificador → `IllegalArgumentException`
  clara na subida; o catch separa `InvalidKeyException` de `NoSuchAlgorithmException`.
- **Parser leniente**: chave duplicada ("último vence") e lixo após o objeto passavam. Agora parser estrito
  (`STRICT_DUPLICATE_DETECTION` + checagem de token após o objeto) → `FORMATO`; `agenteId`/`jti` casam
  `[A-Za-z0-9_-]{1,64}` (nada de quebra de linha indo pro log).
- **Assinatura sem forma canônica**: 32 strings distintas decodificavam pros mesmos 64 bytes (inofensivo pelo `jti`, mas
  ambíguo). Agora exatamente 86 chars url-safe, senão `FORMATO`.
- **Gerador de vetores trocava a chave a cada execução** (diff irrevisável). Agora reusa a chave do arquivo; regenerar
  muda só o caso alterado (+ o do impostor). O JSON ganhou bloco `protocolo` com ordem das checagens, semântica de
  `REPETIDO` (verificar 2×, verificador novo por caso), base64url sem padding, forma canônica, teto e tamanho máximo.

**Herdado pro F3:**
- **Cache de replay é só em memória**: reiniciar o agente reabre a janela do `jti` dentro do TTL (10 min). Aceito como
  trade-off (ticket é de uso único por conexão e exige a chave do backend); se quiser fechar, persistir `jti→exp` no
  diretório do agente e descartar vencidos no boot.
- **Raiz de confiança**: o módulo confia na tripla (chave, lojaId, agenteId) que recebe. O F3 grava isso no arquivo de
  pareamento com ACL do usuário (0600) + fingerprint SHA-256 da chave, valida `exigirPublicaUtilizavel` ao carregar e
  prevê `kid` pra rotação. A chave do ticket assina **só tickets** (o `latest.json` do auto-update usa outro par).
- No F3 o `onMessage` fecha a conexão (1008) no 1º ticket inválido e não verifica de novo — evita gastar 0,5 ms por lixo.

### 7.3 Adversarial F1 do backend (2026-09-07, workflow 6 lentes + refutação) — resumo; tabela completa no doc privado

**13 confirmados, todos corrigidos com teste (RED antes):** (ALTA, pré-existente) anônimo com `X-Tenant-ID` forjado drenava
o bucket geral da loja vítima → bucket por tenant só com `Authorization`; health DOWN por URL de download ausente derrubava
`/actuator/health` em dev/CI → avisos, DOWN só em produção sem chave; `save()` da entidade desfazia revogação concorrente
(lost update) → UPDATEs dirigidos `… WHERE revogado_em IS NULL`; guard "AGENTE só se pareado" travava a tela da loja que
perdeu o último caixa → guard só na transição; TTL do ticket sem clamp (1440 min gerava ticket que o agente recusa) → 1..60;
`versao-minima` inválida → 503 e versão ilegível do cliente → 426, ambos **antes** de gastar o código; chave lida depois do
CAS → antes; `parear()` ganhou `Propagation.NEVER`; CR/LF em `hostname` forjava log → `@Pattern`; URL de download com
espaço/`http://` → trim + só https com host; origins sem filtro → só `https://` ou `http://localhost`; teste do "expirado"
não exercitava o ramo do CAS → reordenado; par de chaves inválido fora de produção caía em efêmero silencioso → aviso.
**Refutado:** "`runWithTenant` vaza o schema" — restaura o anterior em `finally` (verificado).

**Para o deploy (Railway):** `AGENTE_TICKET_CHAVE_PRIVADA` + `AGENTE_TICKET_CHAVE_PUBLICA` (Ed25519; sem elas `/parear` e
`/ticket` → 503 e health DOWN), `AGENTE_ORIGENS_PERMITIDAS` **não precisa** (default = `cors.allowed-origins`, que já tem o endereço do Vercel **e** `app.agroease.com.br` — o PWA é servido pelos dois, o agente aceita os dois), `AGENTE_URL_*` + `AGENTE_SHA256_*`
do GitHub Releases. Banco de produção precisa ser recriado (DDL entrou inline nos V1 — gate já conhecido do go-live).

**Herdado pro F3 (agente):** `ORIGINS_PADRAO` do host de dev não tem `app.agroease.com.br` — passa a vir da resposta do
pareamento (`origensPermitidas`); o `hello_ok` ganha `agenteId`; `auth` com o ticket do `GET /ticket`.

### 7.4 Adversarial F2 do frontend (2026-09-09, workflow 5 lentes + 1 cético por achado) — resumo; **CONTRATO que a F3 tem de cumprir**

**44 achados (2 ALTA, 29 MÉDIA, 13 BAIXA), 0 refutados; 29 correções com teste, 4 dívidas registradas** (tabela no doc privado).
As 2 ALTA: resposta de tipo inesperado do agente deixava a Promise pendente para sempre e travava a fila do cliente (o
`reject` não alcançava o wrapper — só reproduzível com entrega **assíncrona** da mensagem, como no navegador); e a loja
já pareada nunca via o código do 2º caixa (a tela escondia o código por `agentePareado`). Lição de custo: 49 agentes =
38 % do limite mensal do plano — adversarial passa a ter **teto ~10 agentes** (5 lentes + 1 cético por lente).

**O cliente F2 é a verdade do protocolo. O agente F3 precisa (tudo com JUnit no `agente-core`):**
1. `open` → PWA envia `{tipo:'hello', versaoProtocolo:1}` e espera `hello_ok{agenteVersao, protocolo:<número JSON>, so, agenteId}` em **3 s**
   (45 s se a permissão LNA estiver em `prompt`). `protocolo` como **string** → o parser devolve `null` → "sem resposta". Com `versaoProtocolo`
   desconhecido o agente **ainda responde `hello_ok`** (o PWA compara com `protocoloMinimo` do `/release`); responder `erro`+`close` vira
   `RECUSADO` → PWA tenta a 2ª porta → "agente não encontrado".
2. PWA valida `agenteId` (string obrigatória), `agenteVersao ≥ versaoMinima`, `protocolo ≥ protocoloMinimo` — se falhar, `close 1000 'desatualizado'` e
   **nunca** envia `auth`. Agente F0 (`0.1.0-spike`, sem `agenteId`) é "desatualizado" por definição.
3. `auth{ticket}` → `auth_ok{}` (PWA espera **10 s**) ou `erro{codigo:'TICKET_INVALIDO', motivo:<Motivo de TicketInvalidoException>, mensagem}` **antes** do
   `close 1008`. `EXPIRADO|REPETIDO` → PWA busca ticket novo e reconecta **1×** na mesma porta; `LOJA_DIVERGENTE|AGENTE_DIVERGENTE` → "caixa não pareado";
   `TIPO_DESCONHECIDO` no `auth` → "desatualizado". Só `close` sem frame `erro` → PWA lê o `reason` como motivo (sem renovação).
   **Prazo do agente para o `auth`: ≥ 60 s do `hello_ok`** (ver §7.1). Se fechar nessa janela, o PWA reconecta 1× já com o ticket em mão.
4. Pós-auth: `listar_impressoras{id}` → `impressoras{id, nomes[], selecionada|null}`; `selecionar_impressora{id, nome}` → `selecionar_impressora_ok{id, selecionada}`
   (persistida no agente, por máquina); `imprimir{id, formato:'pdf', bytesBase64, impressora}` (**sempre** com `impressora`) → `imprimir_ok{id, estado:'ACEITO_SPOOLER'}`
   | `imprimir_erro{id, codigo:'IMPRESSORA_INDISPONIVEL'|'ERRO', mensagem}`; `erro{id?, codigo, mensagem}` **ecoa `id`** quando a entrada tinha (sem `id`,
   o PWA rejeita o único job em voo — ele serializa 1 job). Timeouts do PWA: listar 5 s, imprimir 15 s. PDF ≤ 2 MiB (base64 dentro do frame de 4 MiB).
5. **OCUPADO** (2ª conexão autenticada da mesma Origin com uma viva): sinalizar **depois** do handshake WS — `erro{codigo:'OCUPADO'}` + `close 1008` com
   `reason 'OCUPADO'` (no `auth` ou depois). Recusar no upgrade HTTP = `RECUSADO` → PWA tenta a 2ª porta. Qualquer outro `close 1008` durante um job o PWA
   trata como "fechado" (ex.: caixa revogado), **não** como "outra aba".
6. O PWA fecha `1000` com reasons `timeout|desatualizado|sem ticket|auth|ocioso|fechado pelo PWA`; **ocioso = 20 s** sem job (a 1ª conexão libera a vaga
   para a 2ª aba); `pagehide` fecha. Não há loop de reconexão no PWA.
7. Portas **fixas** `28421` (padrão) → `28422` (fallback), nessa ordem; o PWA **não lê** `portaSugerida` do backend. Origins = `origensPermitidas` do
   pareamento (inclui `app.agroease.com.br` e o host do Vercel), substituindo `ORIGINS_PADRAO`. `agenteVersao` do binário = versão do instalador (≥ 1.0.0).
8. Divergências só documentais já absorvidas em §3/§7.1 (ordem `hello→auth`, `agenteVersao`, ocioso 20 s, impressora no agente, PDF baixado ANTES de abrir o socket,
   versão/protocolo validados ANTES do `auth`). O `roteiroF3` de `src/test/FakeSocket.ts` (frontend) materializa exatamente este contrato — é o oráculo do F3.

### 7.5 Adversarial F3 do agente (2026-09-09, workflow de **5 lentes SEM céticos** — 5 agentes; reverificação pessoal de cada achado) — resumo; tabela completa no doc privado
Lentes: contrato × cliente (`roteiroF3` do PWA), concorrência/recursos, pareamento/credencial/segurança, casca/SO/UI/autostart, release/CI/build.
25 achados (2 ALTA), todos tratados em 5 passes com teste; nenhum refutado — o formato "≤5 por lente, sem céticos" achou o mesmo que os
workflows grandes das fases anteriores por ~1/5 do custo.
1. **ALTA — split-brain do `config.json`**: bandeja e servidor tinham caches distintas; a impressora escolhida na janela não chegava ao `imprimir`.
   Fix: `ConfiguracaoLocalArquivo` relê por mtime em toda leitura e o orquestrador injeta a **mesma** instância no servidor; teste ponta a ponta (WS + ticket real).
2. **ALTA (release) — nomes de asset divergentes** entre `ci.yml` (sem `-rc1`) e `montar-latest.sh` (com): a 1ª pré-release morreria no `publicar`. Fix: nome completo no CI.
3. Contrato: lista vazia zerava a `selecionada`; listagem sem prazo; OCUPADO concorrente gastava o ticket do perdedor; lacunas do `ContratoF3Test`
   (peer morto, teto de 8 conexões → 1013 `LIMITE_CONEXOES`, frames fragmentados/limite inclusivo, hello/auth repetidos). Tudo com caso novo.
4. Concorrência: motor de impressão preso (job > 10× o prazo) agora responde `imprimir_erro` na hora em vez de aceitar para sempre; CLI que mexe no cofre
   com o agente aberto é aplicada pelo **zelador** em ≤ 5 s; `parear`/`desparear` serializados; erro fatal com UI é **bloqueante** e sai 0 (o launchd
   não entra em loop); sem supervisor (Windows Run) o processo **re-sobe sozinho** (3/10/30 s) antes de desistir com 3.
5. Pareamento: erro de leitura do cofre não destrói mais um `pareamento.enc` válido; "código continua válido" só quando é verdade (consumido/timeout → não);
   **relógio do caixa** (≥ 50 min atrasado → `VALIDADE_ABSURDA`, ≥ 10 min adiantado → `EXPIRADO`) passou a ser **diagnosticado** (aviso no `--parear` e no
   `--diagnostico` pelo header `Date`; dica "confira a data/hora" no `erro` que chega ao PWA) — o `VerificadorTicket` em si **não mudou** (contrato
   compartilhado com o backend; rever o teto de 1 h é decisão cross-repo da F6).
6. Casca/SO: macOS sem Dock (`apple.awt.UIElement`) + `QuitHandler` (⌘Q não mata); autostart ativado com o agente aberto não lança 2ª instância.
7. Release/CI: `MSYS_NO_PATHCONV` no `reg query`; runner **ubuntu-22.04** + guarda anti-`t64` no `.deb`; dispatch de release só a partir da tag;
   `codesign --verify` com dentes (inclusive dentro do `.dmg`); `inputs.tag` via `env`; protocolo do `latest.json` lido de `ProtocoloVersao.ATUAL`.
Dívidas registradas (F6/loja-piloto): código de pareamento no `argv`, proxy explícito da loja, cache de replay zera no re-parear, `tar.gz` Linux
genérico, MSI com mesma `ProductVersion` para rc/final, `macos-15-intel` é o último runner x86_64 (ago/2027).

---

## 8. Fontes primárias principais (verificadas em 2026)
- Mixed content/loopback: Chromium `mixed_content_checker.cc`; MS Learn *Adapting your website for LNA* ("localhost is
  considered a secure origin … won't be blocked as mixed content"); Intent to Ship "LNA restrictions for WebSockets"
  (Chrome 147); Firefox bug 2042339 (LNA em WS, FF 154); WebKit 171934 (Safari bloqueia ws:// loopback).
- WebView2: `CoreWebView2.PrintAsync` docs; WebView2Feedback #3717 (MediaSize=Custom), #3421 (POS-80 enfileirou sem
  papel), #2434 (Sessão 0 "not planned"), #5499/#5577 (regressões 2026); distribuição Evergreen/bootstrapper;
  fim de suporte Win7/8.1 (runtime 109).
- Spooler/CUPS: `EnumPrinters` (Level 4); remoção do WMIC; `lp`/`lpstat`/`lpoptions` (CUPS docs).
- SumatraPDF: command-line args + exit codes; `COPYING` (GPLv3); issue #183 (escala).
- Go: release policy (1.25 EOL 19/08/2026); `go-webview2` (sem Print); `webview2-com` (Rust); `minio/selfupdate`;
  FAQ antivírus. Bun/pkg: docs oficiais + GHSA-22r3-9w55-cj54.
- R2: pricing (egress $0, free tier) e public-buckets (`r2.dev` dev-only); exemplo oficial AWS SDK Java
  (`pathStyleAccessEnabled(true)`).
- Segurança: Apple 103769 (≤825 d), mkcert, Let's Encrypt *certificates-for-localhost*, CA/B SC-081v3; DPAPI
  `CryptProtectData`; Smart App Control (MS Support); macOS Tahoe/Gatekeeper (Apple mh40616, eclecticlight).
- Stack Java (decisão final): `javax.print` (`PrintServiceLookup`, `DocPrintJob`, `PrintJobListener`), `java.awt.print`
  (`PrinterJob`, `Paper`), Apache PDFBox 3 (`PDFPageable`/`PDFRenderer`), Java-WebSocket (TooTallNate), JEP 339
  (Ed25519 nativo), `jpackage`/`jlink` (JDK 21), `cups-pdf`; precedente QZ Tray (Java + PDFBox + `javax.print`).
  WebView2/Sumatra/Go: avaliados na recon e **descartados** com a escolha do Java.
- Código do próprio projeto: `PortalContadorToken/Service`, `WebhookNumeracaoTxService`, `TenantContext.runWithTenant`,
  `RateLimitFilter` (pós-Fase 0), `SecurityConfig`, `ConfiguracaoGeral*`, `resolverEstrategia`, `imprimirViaIframe`,
  `HtmlToPdfTermicoRenderer` (o PDF de 80 mm que o agente imprime).
