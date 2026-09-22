# Atualização automática do agente (self-update)

Como o Agente de Impressão AgroEase se mantém atualizado sozinho, por sistema operacional, e o que o suporte consegue ver.
É o desenho **entregue** (F6); o plano original falava em "rename-swap", que não é o que foi feito.

## Em uma frase

O agente **baixa e verifica** a versão nova; quem **instala** é o instalador do próprio sistema, disparado por um
**atualizador externo** (uma cópia do agente rodando fora da pasta instalada), com **sentinela de saúde** e **reversão**.

## Formatos e o que cada um faz sozinho

| Sistema | Formato instalado | Aplica sozinho? | Quem troca os arquivos |
|---|---|---|---|
| Windows | `.exe` (MSI por usuário) | sim | `msiexec` silencioso (`/qn /norestart`), upgrade in-place |
| Linux | `.tar.gz` extraído dentro da pasta do usuário (app-image) | sim | troca de pasta (`instalado` → `anterior`, `staging` → `instalado`) |
| Linux | `.deb` (em `/opt`) | **não** — avisa e pede 1 clique | `pkexec dpkg -i` (pede a senha de administrador) |
| macOS | `.dmg` (`.app`) | sim | troca de pasta do `.app` |

No Linux o agente descobre o formato pelo lugar do executável: dentro do HOME = app-image (tar.gz); fora = pacote (.deb).

## O ciclo, passo a passo

1. **Verificação** 2 min depois de abrir e a cada 6 h: baixa `releases/latest/download/latest.json` e `latest.json.sig`
   (com ETag: sem mudança, nada é baixado de novo).
2. **Assinatura antes de tudo**: o `latest.json` só é lido depois de a assinatura Ed25519 conferir com a chave de release
   embutida no agente (chave atual ou a **reserva**, identificada pelo `kid`). Assinatura errada = manifesto ignorado.
3. **Versão estritamente maior** que a instalada, com artefato para este sistema/formato; senão, "nenhuma versão nova"
   (fica no log).
4. **Download** do instalador para `<pasta de dados>/atualizacao/baixado/`, em streaming, com teto de tamanho e
   conferência do SHA-256 do manifesto. Baixado uma vez só.
5. **Windows**: antes de aplicar, guarda o instalador da versão **atual** em `atualizacao/anterior/` (é o que permite reverter).
6. **Quando aplica**: (a) caixa **ocioso** há 5 min (nenhuma sessão do PDV autenticada, fila vazia); (b) **no boot**, se o
   instalador já estava baixado de uma sessão anterior — a troca acontece na abertura, antes de a porta abrir; (c) clique em
   **"Atualizar"** na janela/bandeja. O `.deb` só pelo clique.
7. **Saída controlada**: fecha as conexões do PDV com `1001 ATUALIZANDO` (o PWA mostra "volta em até 1 minuto"), grava
   `atualizacao/plano.json` (versões, instalador, sha256, formato, launcher), pausa o supervisor do Windows (a tarefa
   keepalive não pode reabrir o agente velho no meio da instalação), lança o atualizador e sai com código 0.
8. **Atualizador** (`--aplicar-atualizacao plano.json`, rodando de uma cópia do app-image): espera o agente soltar a trava de
   instância (até 60 s), **reconfere o sha256** do instalador (proteção contra troca do arquivo entre o download e a
   execução), instala pelo caminho do sistema e **relança** o agente (tarefa do Windows, `launchctl`, `systemctl --user`
   ou direto).
9. **Sentinela de saúde**: o agente novo precisa estar escutando por 60 s para **confirmar** a versão. Sem confirmação em
   3 boots, **reverte**: Windows reinstala o `.exe` guardado (plano invertido, mesmo atualizador); macOS e tar.gz voltam
   a pasta `anterior`; `.deb` não reverte (fica na versão instalada).
10. **Freio**: versão que falhou fica recusada por 24 h; depois de 3 falhas da **mesma** versão o agente desiste dela e
    avisa (1×/dia) que ela precisa ser instalada à mão. Só uma versão **maior** zera a conta.

## Onde ver o estado

- `AgroEase-Agente-Impressao-cli --status` e `--diagnostico` imprimem a linha **"Atualização automática: …"**, por exemplo
  `em dia — última resposta da release há 3 h`, `versão 1.2.0 baixada, aguardando o caixa ficar ocioso, o próximo boot ou o
  clique em "Atualizar"`, `aplicando 1.2.0 (boot 1 de 3, anterior 1.1.0)`, `versão 1.2.0 recusada por mais 20 h (1 de 3
  tentativas)`, `versão 1.2.0 não instalou aqui (3 tentativas)`.
- `--verificar-atualizacao` consulta a release publicada e diz se há versão nova (não baixa, não instala).
- `--atualizar` (com o agente fechado) baixa e aplica agora; com o agente aberto, orienta a usar "Atualizar" na janela.
- Log: `logs/agente-0.log` registra cada verificação (inclusive "nenhuma versão nova"), o lançamento do atualizador e a
  confirmação/reversão; o atualizador escreve em `atualizacao/atualizador.log`.
- Memória do ciclo: `atualizacao/estado.json` (última verificação, versão disponível, instalador baixado, troca em curso,
  tentativas de boot, recusas). Corrompido, vai de lado e o ciclo recomeça.

## Limitações conhecidas

- Com o agente **aberto**, instalar uma versão por cima à mão não quebra nada, mas o processo antigo continua rodando até
  ser fechado ("Sair") e reaberto.
- A tarefa keepalive do Windows pertence ao usuário que pareou; desinstalar pelo painel do Windows não a remove (o
  `--desinstalar` remove).
- O `.deb` nunca se atualiza sem a pessoa: é a fronteira de privilégio do sistema, não uma escolha do agente.
