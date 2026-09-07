// Dirige um Chrome já aberto (--remote-debugging-port) para testar ws://127.0.0.1:PORTA a partir da Origin do PWA.
// Uso: node lna_cdp.mjs --cdp 9333 --origin https://wagsyspet-frontend.vercel.app --agente 28421 --modo prompt|granted|denied|default --saida DIR [--espera-prompt-ms 6000]
import { execSync } from 'node:child_process';
import { writeFileSync, mkdirSync } from 'node:fs';

const arg = (k, d) => { const i = process.argv.indexOf('--' + k); return i > 0 ? process.argv[i + 1] : d; };
const CDP = arg('cdp', '9333');
const ORIGIN = arg('origin', 'https://wagsyspet-frontend.vercel.app');
const AGENTE = arg('agente', '28421');
const MODO = arg('modo', 'prompt');
const SAIDA = arg('saida', '.');
const ESPERA_PROMPT = Number(arg('espera-prompt-ms', '6000'));
const DISPLAY = process.env.DISPLAY || ':0';
mkdirSync(SAIDA, { recursive: true });

const log = (...a) => console.log('[lna]', ...a);

class Cdp {
  constructor(url) { this.url = url; this.id = 0; this.pend = new Map(); this.eventos = []; this.ouvintes = []; }
  async conectar() {
    this.ws = new WebSocket(this.url);
    await new Promise((ok, ko) => { this.ws.onopen = ok; this.ws.onerror = e => ko(new Error('ws cdp: ' + e.message)); });
    this.ws.onmessage = ev => {
      const m = JSON.parse(ev.data);
      if (m.id && this.pend.has(m.id)) { const { ok, ko } = this.pend.get(m.id); this.pend.delete(m.id); m.error ? ko(Object.assign(new Error(m.error.message), { cdp: m.error })) : ok(m.result); }
      else if (m.method) { this.eventos.push(m); this.ouvintes.forEach(f => f(m)); }
    };
  }
  send(method, params = {}, sessionId) {
    const id = ++this.id;
    this.ws.send(JSON.stringify({ id, method, params, sessionId }));
    return new Promise((ok, ko) => this.pend.set(id, { ok, ko }));
  }
  fechar() { try { this.ws.close(); } catch { } }
}

const sleep = ms => new Promise(r => setTimeout(r, ms));

async function json(path, method = 'GET') { const r = await fetch(`http://127.0.0.1:${CDP}${path}`, { method }); return r.json(); }

function screenshot(nome) {
  try {
    const tree = execSync(`xwininfo -root -tree -display ${DISPLAY}`, { encoding: 'utf8' });
    // janelas top-level do Chrome: linhas com classe ("google-chrome" "Google-chrome") e tamanho grande
    const linhas = tree.split('\n').filter(l => /google-chrome/i.test(l) && /\d+x\d+\+/.test(l));
    const cand = linhas.map(l => { const m = l.match(/^\s*(0x[0-9a-f]+)\s.*?(\d+)x(\d+)\+/i); return m ? { id: m[1], w: +m[2], h: +m[3], l } : null; })
      .filter(Boolean).sort((a, b) => (b.w * b.h) - (a.w * a.h));
    if (!cand.length) { log('screenshot: nenhuma janela X do Chrome encontrada'); return null; }
    const alvo = cand[0];
    const xwd = `${SAIDA}/${nome}.xwd`, png = `${SAIDA}/${nome}.png`;
    execSync(`xwd -id ${alvo.id} -display ${DISPLAY} -silent -out "${xwd}"`);
    execSync(`python3 "${new URL('./xwd2png.py', import.meta.url).pathname}" "${xwd}" "${png}"`);
    log(`screenshot ${png} (janela ${alvo.id} ${alvo.w}x${alvo.h})`);
    return png;
  } catch (e) { log('screenshot falhou:', e.message.split('\n')[0]); return null; }
}

const resultado = { modo: MODO, origin: ORIGIN, agente: `ws://127.0.0.1:${AGENTE}`, console: [], eventos: [] };

// 1) alvo de página na Origin do PWA (abre se não houver)
let alvos = await json('/json/list');
let pagina = alvos.find(t => t.type === 'page' && t.url.startsWith(ORIGIN));
if (!pagina) { pagina = await json(`/json/new?${ORIGIN}/`, 'PUT'); log('nova aba', pagina.url); await sleep(4000); }
const versao = await json('/json/version');
resultado.navegador = versao.Browser;
log('navegador', versao.Browser, '| aba', pagina.url);

// 2) conexões CDP: navegador (permissões) e página
const nav = new Cdp(versao.webSocketDebuggerUrl); await nav.conectar();
const pag = new Cdp(pagina.webSocketDebuggerUrl); await pag.conectar();
await pag.send('Page.enable'); await pag.send('Runtime.enable'); await pag.send('Log.enable');
pag.ouvintes.push(m => {
  if (m.method === 'Runtime.consoleAPICalled') resultado.console.push({ tipo: m.params.type, texto: m.params.args.map(a => a.value ?? a.description ?? '').join(' ') });
  if (m.method === 'Runtime.exceptionThrown') resultado.console.push({ tipo: 'exception', texto: m.params.exceptionDetails.text + ' ' + (m.params.exceptionDetails.exception?.description ?? '') });
  if (m.method === 'Log.entryAdded') resultado.console.push({ tipo: 'log.' + m.params.entry.level, fonte: m.params.entry.source, texto: m.params.entry.text });
});
await pag.send('Page.bringToFront');

// 2b) documento NOVO a cada cenário: o Chrome guarda decisão de LNA por documento; sem reload o cenário anterior vaza
if (arg('reload', 'sim') === 'sim') {
  const carregou = new Promise(r => { const f = m => { if (m.method === 'Page.loadEventFired') { r(); } }; pag.ouvintes.push(f); });
  await pag.send('Page.reload', { ignoreCache: false });
  await Promise.race([carregou, sleep(15000)]);
  await sleep(1500);
  log('página recarregada (documento novo)');
}

// 3) permissão via CDP conforme o modo (prompt/default = não mexe)
if (MODO === 'granted' || MODO === 'denied') {
  try {
    await nav.send('Browser.setPermission', { origin: ORIGIN, permission: { name: 'local-network-access' }, setting: MODO });
    resultado.setPermission = 'ok';
  } catch (e) { resultado.setPermission = 'ERRO: ' + e.message; log('Browser.setPermission falhou:', e.message); }
} else if (MODO === 'reset') {
  try { await nav.send('Browser.resetPermissions'); resultado.resetPermissions = 'ok'; } catch (e) { resultado.resetPermissions = 'ERRO: ' + e.message; }
}

const evalJs = async (expr, awaitPromise = true) => {
  const r = await pag.send('Runtime.evaluate', { expression: expr, awaitPromise, returnByValue: true });
  if (r.exceptionDetails) return { erro: r.exceptionDetails.text + ' ' + (r.exceptionDetails.exception?.description ?? '') };
  return r.result.value;
};

resultado.estadoAntes = await evalJs(`navigator.permissions.query({name:'local-network-access'}).then(p=>p.state).catch(e=>'query n/d: '+e.message)`);
log('permissions.query antes:', resultado.estadoAntes);

// 4) gesto de usuário real (clique) — alguns prompts exigem ativação
const { result: { value: vp } } = await pag.send('Runtime.evaluate', { expression: 'JSON.stringify({w:innerWidth,h:innerHeight})', returnByValue: true });
const { w, h } = JSON.parse(vp);
if (arg('gesto', 'sim') === 'sim') for (const type of ['mousePressed', 'mouseReleased']) await pag.send('Input.dispatchMouseEvent', { type, x: Math.floor(w / 2), y: Math.floor(h / 2), button: 'left', clickCount: 1 });

// 5) abre o WebSocket a partir da PÁGINA do PWA e observa por até ESPERA total
const script = `new Promise(res => {
  const ini = performance.now(); const ev = [];
  const marca = (n, extra={}) => ev.push({t: Math.round(performance.now()-ini), n, ...extra});
  let ws; try { ws = new WebSocket('ws://127.0.0.1:${AGENTE}'); } catch (e) { return res({ev:[{n:'throw', msg:String(e)}]}); }
  window.__wsLna = ws;
  ws.onopen = () => { marca('open'); ws.send(JSON.stringify({tipo:'hello', versaoProtocolo:1})); };
  ws.onmessage = e => { marca('message', {data: String(e.data).slice(0,300)}); ws.close(1000, 'fim do teste'); };
  ws.onerror = () => marca('error');
  ws.onclose = e => { marca('close', {code: e.code, reason: e.reason, wasClean: e.wasClean}); res({ev}); };
  setTimeout(() => { if (ws.readyState === 0) { marca('timeout-connecting'); try{ws.close();}catch{} res({ev}); } }, ${ESPERA_PROMPT + 15000});
})`;
const promessa = evalJs(script);
await sleep(ESPERA_PROMPT); // janela pra o prompt aparecer
resultado.screenshotPrompt = screenshot(`${MODO}-durante`);
resultado.estadoDurante = await evalJs(`navigator.permissions.query({name:'local-network-access'}).then(p=>p.state).catch(e=>'n/d')`);
resultado.readyStateDurante = await evalJs('window.__wsLna ? window.__wsLna.readyState : -1', false);
log('durante: permission =', resultado.estadoDurante, '| ws.readyState =', resultado.readyStateDurante, '(0=CONNECTING,1=OPEN,3=CLOSED)');

if (MODO === 'prompt' && arg('conceder-durante', 'nao') === 'sim') {
  try { await nav.send('Browser.setPermission', { origin: ORIGIN, permission: { name: 'local-network-access' }, setting: 'granted' }); resultado.concederDurante = 'ok'; } catch (e) { resultado.concederDurante = 'ERRO: ' + e.message; }
}

resultado.websocket = await promessa;
resultado.estadoDepois = await evalJs(`navigator.permissions.query({name:'local-network-access'}).then(p=>p.state).catch(e=>'n/d')`);
await sleep(800);
resultado.screenshotDepois = screenshot(`${MODO}-depois`);
log('websocket:', JSON.stringify(resultado.websocket));
log('permissions.query depois:', resultado.estadoDepois);
if (resultado.console.length) log('console:', JSON.stringify(resultado.console, null, 1));

writeFileSync(`${SAIDA}/${MODO}.json`, JSON.stringify(resultado, null, 2));
log('resultado salvo em', `${SAIDA}/${MODO}.json`);
nav.fechar(); pag.fechar();
