# Plano — Agente Próprio de Impressão de Cupom (WagSysPet)

> **Status (2026-09-07):** PLANO APROVADO (todas as decisões tomadas — Java 21, repo público, multiplataforma).
> **F0 em andamento:** spike de fidelidade **PASSOU** (iText ≈ Chrome, fiscal com QR e não-fiscal); spike do caminho
> Unix **PASSOU em código** (`javax.print` enumera; `lp` + PDF nativo → CUPS → `cups-pdf`, 6/6 verdes). Projeto
> semeado em `wagsyspet-agente/` (Maven, `agente-impressao`). **Este arquivo é o plano canônico — vive no repo do agente.**
> Gerado em 2026-09-06 a partir de recon multi-agente (6 lentes + 4 contra-provas céticas, fontes primárias
> de 2026) + verificação pessoal dos achados decisivos. Pré-requisito já feito: **Fase 0** (rate-limit fiscal,
> código morto, robustez do `imprimirViaIframe`) — commitada e pushada nas branches `worktree-fase0-impressao`
> dos 2 repos, ainda **não mergeada** na main.

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
| Manter DIALOGO/DIRETO intactos | AGENTE é aditivo; fallback sempre existe |
| Reusar o **HTML** existente (fiscal da Focus + builders próprios) | Não duplicar a verdade fiscal; não reescrever layout homologado |
| **Stack Java 21** (um motor `javax.print` p/ todos os SOs), repo **próprio e público** | Linguagem do dono; código compartilhado c/ backend; GitHub Releases grátis; sem segredo no binário |
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
  - Antes de conectar: `navigator.permissions.query({name:'local-network-access'})` em try/catch (devolve
    `prompt` mesmo sem LNA; `denied` em página HTTP). Pré-disparar o prompt com `fetch('http://127.0.0.1:PORTA')`
    (Edge 144+).
  - Guia de reativação no painel (textos em inglês nas fontes — **capturar o pt-BR em teste manual**):
    Chrome 145+ "Apps on device" (≤144 "Local network access"); Edge: Settings → Privacy → Site permissions →
    "Local network access"; Firefox: permissão "Local Network Devices".
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
  grátis, URL estável `https://github.com/<org>/wagsyspet-agente/releases/download/v{versao}/wagsyspet-agente-{versao}-{so}-{arch}.{ext}`
  + `latest.json` como asset da release. **R2, `S3Presigner`, AWS SDK e credencial no Railway SAEM do plano.**
- **Entrega:** `GET /api/agente-impressao/release` (autenticado, bucket geral) devolve JSON `{versaoAtual, versaoMinima,
  urls por SO/arch, sha256, tamanho}` lido das propriedades `app.agente-impressao.*` (env) — o front faz
  `window.open(url)`. (O JWT vai em header Bearer → um `302` direto não serviria em `<a href>`; JSON resolve.) O lojista
  clica no botão **dentro do sistema**; onde o arquivo mora é invisível pra ele.
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

### Backend (poucas mudanças; **nenhuma migration em `db/tenant`** — as 3 colunas já existem)
| Arquivo | Mudança |
|---|---|
| `dto/ConfiguracaoGeralDTO.java:85-89` | `Boolean agentePareado` **read-only** (ignorado no PUT); `@Size(max=120)` em `impressoraSelecionada` |
| `service/ConfiguracaoGeralService.java:176-182` | `impressoraSelecionada`: não-null→trim, **isBlank→null** (hoje "null preserva" impede limpar); guard `AGENTE` sem `agentePareado` → `BusinessException` (molde F-08 l.152-169); `toDTO` seta `agentePareado` |
| `service/ConfiguracaoGeralService.java` (novos) | `@Transactional marcarAgentePareado(versao)` (dentro de `runWithTenant`), `desparear()`, `@Transactional(readOnly) buscarImpressao()` → DTO leve **sem LAZY** (OSIV off) |
| `controller/ConfiguracaoGeralController.java:77` | **`GET /impressao` com `isAuthenticated()`** (ver §7 — corrige bug pré-existente) → `{modoImpressao, impressoraSelecionada, agentePareado}`; `DELETE /agente-impressao` (RBAC igual l.37) |
| `controller/AgenteImpressaoController.java` (NOVO, `/api/agente-impressao`) | `GET /release`, `GET /versao`, `GET /ticket` (`isAuthenticated()`), `POST /pareamento/token` (RBAC de config). Bucket geral do rate-limit (prefixo não é fiscal) |
| `controller/AgenteImpressaoPublicController.java` (NOVO, `/api/public/agente-impressao`) | `POST /parear`; erro sempre **404 genérico** + `no-store` (molde `PortalContadorPublicController:22-25`); **não ler `X-Tenant-ID`** |
| `service/impressao/AgenteImpressaoService.java` (NOVO) | `emitirToken()` / `parear()` (CAS + `runWithTenant`) / `emitirTicket()`; `AgenteTicketService` Ed25519 JDK 21 |
| `model/AgenteImpressaoToken.java` + `repository/…` (NOVOS) | `@Table(schema="public")`, **sem `BaseEntity`** (o listener sobrescreveria `tenant_id` — molde `PortalContadorToken:7-15`); `findByTokenHash`, `@Modifying consumir(...)`, `revogarPendentesDoTenant` |
| `db/migration/V1__Create_public_schema_tables.sql:344` | `CREATE TABLE public.agente_impressao_token (…)` + índice `tenant_id`. Regime vigente = V1 public in-place + recriar banco (como `portal_contador_token`); **com dados vivos → V2 public forward-only** |
| `db/tenant/V1__baseline.sql` (após l.523) + `src/test/resources/tenant-schema-esperado.txt` | **NOVA tabela TENANT `agente_impressao`** (`id, nome_maquina, so, arch, versao, credencial_hash, criado_em, ultimo_visto, revogado_em, tenant_id`) — **N caixas por loja**. Inline no V1 (pré-produção) + **regenerar o golden** (`validar-baseline.sh --gerar-golden`). Entity `AgenteImpressao` (tenant, `extends BaseEntity` ok) + repository |
| `dto/ConfiguracaoGeralDTO.java` (complemento) | `agentePareado` passa a ser **derivado** (≥1 agente ativo); adicionar `agentes[]` resumido (`id, nomeMaquina, so, versao, ultimoVisto`) para o painel listar/revogar caixas |
| `controller/AgenteImpressaoController.java` (complemento) | `DELETE /{id}` revoga **um** caixa (RBAC de config); `GET /ticket` só emite se o `agenteId` informado estiver **ativo** no tenant |
| `config/SecurityConfig.java:109` | `.requestMatchers("/api/public/agente-impressao/**").permitAll()` **antes** de `anyRequest()` (l.152) |
| `config/RateLimitFilter.java:79-84` | `"/api/public/agente-impressao"` em `INVITE_PATHS` (bucket auth 10/min·50/h por IP). **Não** tocar `FISCAL_PATHS` |
| `AgenteImpressaoPropriedades.java` (NOVO) + `ErrorCode` + `GlobalExceptionHandler` + `application*.properties` | `app.agente-impressao.{versao-atual, versao-minima, protocolo-minimo, token-validade-min, download.url-windows, download.url-linux, download.url-macos-arm64, download.url-macos-x64, sha256.*}` (URLs do **GitHub Releases**, por env no profile railway); `AGENTE_TOKEN_INVALIDO` (404), `AGENTE_VERSAO_OBSOLETA` (426), `AGENTE_NAO_PAREADO` |
| `pom.xml` (backend) | **Nenhuma dependência nova** (R2/AWS SDK saíram com o repo público; Ed25519 é nativo no JDK 21). Opcional: consumir `agente-protocolo` como dependência para não duplicar mensagens/erros |
| `application-railway.properties` | `cors.allowed-origins` = fonte da allowlist de `Origin` devolvida no pareamento |
| Testes | estender `ConfiguracaoGeralModoImpressaoIntegrationTest`/`ControllerTest`; novo `AgenteImpressaoPareamentoIntegrationTest` (`AbstractTenantIntegrationTest`) — inclui **adversarial `X-Tenant-ID` apontando outro tenant** (deve cair no tenant do token) |

### Frontend (gate: `tsc -b` + `lint:ci` teto **131** — folga atual **7 warnings** + vitest)
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
| **NOVO** `components/configuracao/AgenteImpressaoPanel.tsx` (+ test) | Status (não detectado / vX não pareado / pareado / desatualizado); "Baixar agente" + instruções **por SO** (Edge/SmartScreen/SAC/macOS); "Gerar código de pareamento" (1×, expira 15 min); **"Conectar agente" (gesto → LNA)** + estado da permissão + guia de reativação; `<select>` impressoras + "Atualizar" + "Imprimir teste" (mostra o estado devolvido); "Desparear"; aviso Safari/iPad não suportado |
| `services/impressao/imprimirViaIframe.ts` | **Sem mudança** (o caminho AGENTE usa PDF, não reusa o CSS do iframe) |
| `vite.config.ts` | **Nenhuma** regra nova (WebSocket não passa pelo Service Worker) |

### Agente (repo **novo, irmão, público** — Java 21 / Maven)
Módulos: `agente-protocolo` (mensagens Jackson, códigos de erro, verificação de ticket Ed25519 — compartilhável com o
backend), `agente-core` (servidor Java-WebSocket em 127.0.0.1, Origin/Host/ticket/limites, fila 1 job, pareamento
`java.net.http`, versão), `agente-impressao` (`javax.print` + PDFBox: lista por nome, imprime PDF 80 mm,
`PrintJobListener` → estado), `agente-app` (`SystemTray`, autostart por SO, credencial cifrada, flags, `ImpressoraFake`),
`.github/workflows/release.yml` (jlink + jpackage nos 3 runners; sha256 + `latest.json` assinado).

---

## 4. Fases (P = pequena · M = média · G = grande) — cada uma com o ritual completo
> Recon → **TDD (RED antes do GREEN)** → unitário → **integração** → **adversarial multi-agente** → conferir doc vs código.
> Trabalho em **worktree** (sessão concorrente no backend). Commits só a pedido; trailer `Co-Authored-By: Claude Fable 5.1`.

| # | Fase | Tam. | Entregas | DoD (Definition of Done) |
|---|---|---|---|---|
| **F0** | **Spikes** (sem código de produto; **sem hardware**) | P | (a) esqueleto Maven multi-módulo (`agente-protocolo/core/impressao/app`) + JUnit 5, no Linux; (b) **spike de impressão no Linux:** PDF do backend → PDFBox → `javax.print` → **`cups-pdf`** em 80 mm — **fidelidade do iText** (DANFCe fiscal + não-fiscal), `Paper` custom vs "papel do driver", `PrintJobListener`, fila offline (`cupsdisable`), nome inexistente → `IMPRESSORA_INDISPONIVEL`; **decide o plano B** (headless) se a fidelidade não bastar; (c) **spike Windows (VM, se houver):** **mesmo jar** → "Microsoft Print to PDF"; (d) **spike `ws://127.0.0.1` + LNA** em Chrome/Edge/Firefox — **capturar textos pt-BR** dos prompts/telas de reset (Java-WebSocket no Linux); (e) **Ed25519 Java↔Java:** backend assina, agente verifica; (f) `jpackage`+`jlink` smoke no CI (3 runners) | Relatório de spike com veredito por item; PDFs de 80 mm gerados no `cups-pdf` (layout conferido); decisão fidelidade/plano B. Checkpoint de **térmica real** fica no F3 (loja-piloto) |
| **F1** | **Backend mínimo** | P/M | Tudo de §3-Backend: `agentePareado` read-only, limpar impressora, guard AGENTE, **`GET /impressao` leve**, `AgenteImpressaoController` (+ público `parear`), tabela public `agente_impressao_token`, ticket Ed25519, props, ErrorCodes, rate-limit/SecurityConfig | Testes de integração verdes incl. **adversarial `X-Tenant-ID`**; `validar-baseline.sh` ok; adversarial workflow aprovado |
| **F2** | **Frontend** | M | Tudo de §3-Frontend: contrato `ResultadoImpressao`, `agenteClient/estrategiaAgente/mensagens`, hooks, `AgenteImpressaoPanel`, call-sites com toast real + ação fallback, hook `/impressao` no PDV, rádio AGENTE habilitado | `tsc -b` limpo; `lint:ci ≤131`; vitest verde (novos testes com FakeSocket); adversarial aprovado; **sem agente ainda funciona igual a hoje** |
| **F3** | **Agente Java multiplataforma MVP** | G | Repo novo (§2.3): `agente-protocolo` (compartilhável c/ backend), `agente-core` (Java-WebSocket 127.0.0.1, 3 barreiras, `hello/auth/listar/imprimir/ping`, fila 1 job, pareamento, versão), `agente-impressao` (`javax.print` + PDFBox: lista por nome, PDF 80 mm, "papel do driver" default / `Paper` opt-in, `PrintJobListener` → estado), `agente-app` (`SystemTray`, autostart HKCU Run/LaunchAgent/systemd, credencial AES-GCM 0600, `--instalar/--desinstalar`). CI: jlink + jpackage nos 3 runners → `.exe` per-user, `.dmg` ad-hoc, `.deb`/tar + sha256 + `latest.json` assinado | **Um só código** imprimindo nos 3 SOs. **Linux:** validado na **máquina do dono** (`cups-pdf`; térmica real quando houver). **Windows:** validado em VM/PC ("Print to PDF") + **checkpoint em térmica real na loja-piloto** (≥3 marcas: `ACEITO_SPOOLER`, papel/corte). **macOS:** construído e assinado ad-hoc, sai **BETA** (o dono não tem Mac). Ticket inválido/Origin/Host errados **recusados** (JUnit). Adversarial aprovado |
| **F4** | **Distribuição + endurecimento** | M | `/release` devolvendo as URLs do **GitHub Releases** (props/env) + sha256; página de instruções permanente **por SO** (Edge/SmartScreen/SAC/Defender; macOS "Abrir Mesmo Assim"; Linux `.deb`) ; submissão ao Defender por release; **versão mínima bloqueante** no PWA; painel de **caixas pareados** (listar/revogar por máquina — a tabela `agente_impressao` já vem da F1) | Instalação do zero numa máquina limpa Win10, Win11 e Linux seguindo só a página; bloqueio por versão testado; revogar um caixa corta os tickets dele |
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
