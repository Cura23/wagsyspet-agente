const list = await (await fetch('http://127.0.0.1:9333/json/list')).json();
const p = list.find(t => t.type === 'page' && t.url.startsWith('https://wagsyspet-frontend.vercel.app'));
const ws = new WebSocket(p.webSocketDebuggerUrl); await new Promise(r => ws.onopen = r);
const send = (method, params) => new Promise(r => { const id = Math.floor(Math.random()*1e6); const h = ev => { const m = JSON.parse(ev.data); if (m.id === id) { ws.removeEventListener('message', h); r(m); } }; ws.addEventListener('message', h); ws.send(JSON.stringify({ id, method, params })); });
const expr = `Promise.all(${JSON.stringify(['local-network-access','loopback-network','loopback-network-access','local-network','private-network-access'])}.map(n => navigator.permissions.query({name:n}).then(p => n+' => '+p.state).catch(e => n+' => ERRO '+e.name))).then(a=>a.join(' | '))`;
const r = await send('Runtime.evaluate', { expression: expr, awaitPromise: true, returnByValue: true });
console.log(r.result?.result?.value ?? JSON.stringify(r));
ws.close();
