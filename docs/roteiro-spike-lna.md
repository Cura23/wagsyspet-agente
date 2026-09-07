# Roteiro — spike F0(d): PWA ↔ agente em `ws://127.0.0.1` + prompt de Local Network Access (LNA)

> Objetivo: **provar no navegador real** que a página pública do WagSysPet (`https://wagsyspet-frontend.vercel.app`)
> conecta no agente local, e **capturar os textos em pt-BR** do prompt de LNA (permitir/bloquear, onde fica a
> permissão, como reativar). Esses textos entram no `mensagensImpressao.ts` e no painel do agente (F2).

## ✅ RESULTADOS (2026-09-07) — executado de forma AUTOMATIZADA no Linux do dono (Chrome 152 + Firefox 140 ESR)

Tudo abaixo foi rodado por `scripts/lna/lna_cdp.mjs` (Chrome dirigido por DevTools Protocol, perfil descartável, janela
real na tela, capturas via `xwd` → PNG) e `scripts/lna/ff_bidi.mjs` (Firefox headless via WebDriver BiDi), contra o
`AgenteMain` em 28421. Único gesto humano: **um clique em "Permitir"** no prompt (Wagner, ao vivo). Evidências em
`docs/lna/*.png`; JSONs brutos ficaram no scratchpad da sessão.

### O que o Chrome 152 mostra (pt-BR literal)

| Elemento | Texto literal |
|---|---|
| Título do prompt | **`wagsyspet-frontend.vercel.app quer`** |
| Corpo do prompt (ícone de monitor) | **`Acessar outros apps e serviços neste dispositivo`** |
| Botões | **`Bloquear`** · **`Permitir`** (+ `×` para dispensar) |
| Nome da permissão em *Configurações do site* | **`Apps no dispositivo`** (valores: `Perguntar (padrão)`, `Permitir`, `Bloquear`, `Perguntar`) |
| Permissão vizinha (NÃO é esta) | `Rede local` — fica em `Perguntar (padrão)` e não muda: o alvo 127.0.0.1 cai em **loopback**, não em "rede local" |
| Descrições da categoria (`chrome://settings/content`) | `Os sites podem pedir para acessar outros apps e serviços neste dispositivo` (padrão) · `Não permitir que os sites acessem outros apps e serviços neste dispositivo` · `Tem permissão para acessar outros apps e serviços neste dispositivo` · `Não tem permissão para acessar outros apps e serviços neste dispositivo` · page-info: `Pode pedir acesso a outros apps e serviços neste dispositivo` |
| Console após "Bloquear" | `WebSocket connection to 'ws://127.0.0.1:28421/' failed: Error in connection establishment: net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS` |
| Console quando o AGENTE recusa (Origin estranha) | `... failed: Error during WebSocket handshake: Unexpected response code: 404` |

> ⚠️ **Descoberta que muda o F2:** o Chrome 152 trata `127.0.0.1` como permissão **separada** da LNA de rede local:
> content setting `loopback_network` (Preferences do perfil), rótulo **"Apps no dispositivo"**, e na Permissions API o nome é
> **`loopback-network`** (`navigator.permissions.query({name:'loopback-network'})` → `prompt|granted|denied`).
> `local-network-access` também responde (mesmo estado no teste), `local-network` fica em `prompt` (não é a nossa), e
> **Firefox 140 lança `TypeError`** para qualquer um desses nomes → o PWA tem de fazer `try/catch` + feature-detect.
> Depois de "Bloquear" **não aparece indicador algum na barra de endereço** (captura `chrome-152-bloqueado-sem-indicador.png`) —
> o PWA precisa explicar e apontar o caminho sozinho.

### Cenários e resultados

| Cenário | Chrome 152 (Linux) | Firefox 140.14 ESR (Linux) |
|---|---|---|
| 1. Estado inicial + conexão do PWA (https) | `permissions.query`=`prompt`; **prompt apareceu, inclusive SEM nenhum gesto** (2ª rodada com `--gesto nao`: apareceu igual; o PWA pode disparar no carregamento do PDV, mas o plano continua preferindo gesto por UX); WS ficou em `CONNECTING` ~11 s até o clique em Permitir → `open` → `hello_ok{agenteVersao:0.1.0-spike,protocolo:1,so:Linux}`; estado → `granted` | Sem LNA (<154): conecta em **5 ms**, `hello_ok`, sem prompt |
| 1b. Permissão já concedida, aba nova/reload | Conecta em **4 ms**, sem prompt novo; estado persiste `granted` | n/a |
| 2. "Bloquear" (emulado gravando `loopback_network: setting 2` no perfil = o que o clique grava) | `permissions.query`=`denied`; WS `error` em 5 ms → `close 1006`; console `net::ERR_BLOCKED_BY_LOCAL_NETWORK_ACCESS_CHECKS`; **nenhum ícone na barra de endereço** | n/a |
| 2b. Reativar em `chrome://settings/content/siteDetails?site=https://wagsyspet-frontend.vercel.app` → "Apps no dispositivo" → `Permitir` | Vale **na hora, sem recarregar o PWA** (mesmo documento que tinha sido bloqueado conectou em 32 ms) | n/a |
| 3. Prova negativa: `https://example.com` **com a permissão do navegador JÁ concedida** | Agente recusa antes do upgrade (`Origin não permitida: https://example.com` no log); Chrome: `close 1006` + `Unexpected response code: 404`; **nunca `open`** | idem esperado (mesmo porteiro); não repetido |
| 4. Dev `http://localhost:5173` (vite) | Conecta em **2 ms**, sem prompt, **sem gravar permissão** (estado segue `prompt`): loopback→loopback não é gated | n/a |
| Override por DevTools (`Browser.setPermission`) | ⚠️ **Não emula o bloqueio**: só muda `permissions.query`; a fiscalização lê o content setting real → por isso o cenário 2 usou o perfil | n/a |

### Ainda NÃO coberto (precisa de clique em UI do navegador ou de outro SO)

- **3 dispensas (`×`/Esc) → embargo** (~7 dias): exige clicar no balão, que não é alcançável por CDP nem X11 sem `xdotool`.
  Se quiser, o Chrome de teste segue aberto: `google-chrome --user-data-dir=<scratchpad>/lna/perfil-chrome` e repetir o passo 1 três vezes dispensando.
- **Edge** (não instalado aqui) e **Windows/macOS** (textos devem ser os mesmos do Chrome pt-BR; conferir na loja-piloto/F3).
- **Firefox ≥154** (LNA em WS): quando o ESR subir, repetir `scripts/lna/ff_bidi.mjs`.
- **Safari**: fora do MVP (bloqueia `ws://` loopback a partir de HTTPS).

### Como repetir

```bash
# agente de dev na 28421 (ver README) + Chrome descartável dirigido por CDP na tela (X11 p/ captura):
google-chrome --user-data-dir=/tmp/perfil-lna --remote-debugging-port=9333 --ozone-platform=x11 --disable-gpu \
  --disable-gpu-compositing --lang=pt-BR --no-first-run https://wagsyspet-frontend.vercel.app/ &
node scripts/lna/lna_cdp.mjs --cdp 9333 --modo prompt --saida ./saida       # cenário 1 (clicar Permitir na tela)
node scripts/lna/lna_cdp.mjs --cdp 9333 --modo default --saida ./saida      # 1b/2/2b (estado do perfil manda)
node scripts/lna/lna_cdp.mjs --cdp 9333 --origin https://example.com --modo default --saida ./saida-ex  # 3
node scripts/lna/query_perms.mjs                                            # nomes da Permissions API
python3 scripts/lna/pak_strings.py /opt/google/chrome/locales/pt-BR.pak 'apps e serviços' 'Apps no dispositivo'
firefox --headless --no-remote --profile /tmp/ff-lna --remote-debugging-port 9222 about:blank & node scripts/lna/ff_bidi.mjs
```
`--disable-gpu-compositing` é obrigatório para o `xwd` ver frames novos (com GPU o buffer X fica congelado).

---

## Roteiro manual original (mantido como referência)

> O que roda sozinho (testes + curl) já passou; a parte abaixo era a manual — hoje coberta pelos scripts acima, exceto os itens
> listados em "Ainda NÃO coberto".

## 0. Pré-requisitos

- Agente de dev rodando (porta **28421**, allowlist = `https://wagsyspet-frontend.vercel.app` + `http://localhost:5173`):

  ```bash
  cd "/home/wagner/Documentos/PROJETO AGROEASE/wagsyspet-agente"
  JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw -B -ntp -DskipTests install      # 1ª vez (ou após mudar código)
  JAVA_HOME=~/.jdks/temurin-21-a1 ./mvnw -B -ntp -pl agente-core exec:java # fica escutando; Ctrl+C encerra
  ```

  Deve aparecer `Agente escutando em ws://127.0.0.1:28421 (...)` e `Agente WagSysPet 0.1.0-spike pronto`.
  ⚠️ **Não use `-q`**: ele engole os logs do agente (o slf4j-simple passa pelo logger do Maven no `exec:java`).
  Conferir: `ss -ltnp | grep 28421` → `LISTEN ... 127.0.0.1:28421`.

- Navegador e versão (`chrome://version`, `edge://version`, `about:support`):

  | Navegador | LNA cobre WebSocket a partir de | Se for mais antigo |
  |---|---|---|
  | Chrome | **147** | conecta **sem prompt** (comportamento antigo). Pra forçar o prompt: `chrome://flags` → buscar "Local Network Access" → Enabled → relaunch (o nome exato da flag varia por versão) |
  | Edge | **147** | idem (`edge://flags`) |
  | Firefox | **154** | conecta sem prompt; `about:config` → buscar `network.lna` (nome pode variar) |
  | Safari | — | **bloqueia** `ws://` loopback a partir de página HTTPS (fora do MVP, plano §2.4) |

  Anote a versão usada: é parte do achado.

## 1. Conexão a partir do PWA (caso feliz + prompt)

1. Abra `https://wagsyspet-frontend.vercel.app` (não precisa logar — o `Origin` é da página, não do usuário).
2. **Clique em qualquer lugar da página** (gesto de usuário — alguns prompts só aparecem após interação).
3. `F12` → aba **Console** → cole e execute:

   ```js
   navigator.permissions?.query?.({ name: 'local-network-access' })
     .then(p => console.log('LNA antes:', p.state)).catch(e => console.log('LNA query n/d:', e.message));

   const ws = new WebSocket('ws://127.0.0.1:28421');
   ws.onopen    = () => { console.log('ABRIU'); ws.send(JSON.stringify({ tipo: 'hello', versaoProtocolo: 1 })); };
   ws.onmessage = e  => console.log('RESPOSTA:', e.data);
   ws.onerror   = e  => console.log('ERRO', e);
   ws.onclose   = e  => console.log('FECHOU code=' + e.code + ' reason=' + JSON.stringify(e.reason));
   ```

   Se **nenhum prompt** aparecer e não conectar, repita dentro de um clique (o prompt pode exigir gesto):

   ```js
   document.body.addEventListener('click', () => {
     const ws = new WebSocket('ws://127.0.0.1:28421');
     ws.onopen = () => { console.log('ABRIU'); ws.send('{"tipo":"hello"}'); };
     ws.onmessage = e => console.log('RESPOSTA:', e.data);
     ws.onclose = e => console.log('FECHOU', e.code, e.reason);
   }, { once: true });
   // agora clique na página
   ```

4. **CAPTURAR (screenshot + texto literal):**
   - o prompt inteiro: título, corpo, rótulos dos botões (ex.: "Permitir"/"Bloquear"), ícone na barra de endereço;
   - o que aparece no console **depois de Permitir**: esperado `ABRIU` e
     `RESPOSTA: {"tipo":"hello_ok","agenteVersao":"0.1.0-spike","protocolo":1,"so":"Linux"}`;
   - `navigator.permissions.query({name:'local-network-access'})` **depois**: esperado `granted`.
5. No terminal do agente deve aparecer `Conexão aberta de Origin='https://wagsyspet-frontend.vercel.app'`.

## 2. Caminho do "Bloquear" e da reativação (é o que o operador vai errar)

1. Em outra aba/anônima (ou depois de resetar a permissão), repita o passo 1 e clique **Bloquear**.
2. **CAPTURAR:** o erro no console (esperado `FECHOU code=1006` sem `ABRIU`), e se o navegador mostra algum aviso
   na barra de endereço (ícone riscado) — texto literal.
3. `navigator.permissions.query(...)` → esperado `denied`.
4. **Onde a permissão fica** — capturar o caminho e os rótulos em pt-BR:
   - Chrome: clique no ícone à esquerda da URL → "Configurações do site" → procurar a linha de rede local
     (Chrome 145+ chama de algo como **"Apps no dispositivo"**; ≤144 "Acesso à rede local") — **anotar o nome real**.
   - Edge: `edge://settings/content` → "Acesso à rede local" (ou similar) — anotar.
   - Firefox: cadeado → "Limpar permissões" / `about:preferences#privacy` → "Dispositivos da rede local" — anotar.
5. Reative (Permitir) por esse caminho e confirme que o passo 1 volta a funcionar **sem novo prompt**.
6. (Opcional, custa tempo) dispensar o prompt **3× com Esc/X** e ver se o navegador para de perguntar
   (embargo ~7 dias documentado no plano): capturar o comportamento.

## 3. Prova negativa — outro site NÃO conecta (Origin allowlist)

1. Abra `https://example.com`, `F12` → Console, cole **o mesmo snippet** do passo 1.
2. Esperado: pode até aparecer o prompt LNA (o navegador pergunta ANTES de o agente recusar — isso é normal e
   vale anotar), mas **nunca** `ABRIU`; console mostra `FECHOU code=1006`; terminal do agente mostra
   `Handshake recusado (403): Origin não permitida: https://example.com`.
3. Isso prova que, mesmo com LNA concedida a um site malicioso, o agente **não fala** com ele.

## 4. Dev local (`http://localhost:5173`)

Com o front rodando em `npm run dev`, repetir o passo 1 a partir de `http://localhost:5173`. Esperado: **conecta sem
prompt** (origem loopback → destino loopback não é acesso "à rede local"). Confirma que o dia a dia de
desenvolvimento não esbarra em LNA.

## 5. O que registrar (abre issue/nota no plano)

| Item | Chrome (versão) | Edge (versão) | Firefox (versão) |
|---|---|---|---|
| Prompt apareceu? Precisou de gesto? | | | |
| Texto literal do prompt (título/corpo/botões) | | | |
| `permissions.query` antes/depois (prompt/granted/denied) | | | |
| Nome da permissão nas configurações do site (pt-BR) | | | |
| Caminho pra reativar após "Bloquear" | | | |
| Comportamento após 3 dispensas | | | |
| `hello_ok` recebido do PWA? | | | |
| example.com recusado (1006, sem ABRIU)? | | | |

Screenshots: salvar em `docs/lna/<navegador>-<versao>-<tela>.png` e referenciar aqui.

## Achados automáticos já fechados (não precisam de repetição manual)

- `ServidorAgenteTest` (14) + `PorteiroHandshakeTest` (20): bind só loopback, hello/ping/erro, frame binário → erro,
  frame > 4 MB → close 1009 com servidor vivo, Origin estranha/ausente e Host de rebinding/porta errada recusados
  **antes** do upgrade (assertado pelo 404 real), porta ocupada e erro no onStart falham rápido — sockets reais em
  porta efêmera.
- `curl` no host de dev: Origin do PWA → **101**; `localhost:5173` → 101; Origin maliciosa, sem Origin e
  `Host: agente.evil.com:28421` (DNS rebinding) → recusa (`404 WebSocket Upgrade Failure`, corpo fixo da lib);
  `http://<IP-da-LAN>:28421` → conexão recusada (não há bind em 0.0.0.0).
- Java-WebSocket 1.6 **hardcoda 404** em recusa de handshake (`WebSocketImpl.closeConnectionDueToWrongHandshake`);
  o 403 do `PorteiroHandshake.Decisao` é semântico (log). Segurança não depende do código: o 101 nunca sai.
