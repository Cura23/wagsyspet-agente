#!/usr/bin/env bash
# Monta os metadados de uma release do Agente de Impressão AgroEase (plano F3 D23):
#   - renomeia os instaladores para AgroEase-Agente-Impressao-<V>-<so>-<arch>.<ext> (se ainda não estiverem)
#   - SHA256SUMS.txt + <arquivo>.sha256
#   - latest.json (versao, protocolo, publicadoEm, kid, artefatos por SO) + latest.json.sig (Ed25519 detached, OpenSSL)
# Uso: montar-latest.sh <versao> <dir-artefatos> <repo owner/nome> <chave-privada.pem> [protocolo]
# Só usa bash + coreutils + openssl (o job "publicar" roda no ubuntu). Testável localmente com um par descartável.
set -euo pipefail

VERSAO="${1:?versao (ex.: 1.2.3)}"
DIR="${2:?dir com os instaladores}"
REPO="${3:?owner/repo}"
CHAVE="${4:?chave privada Ed25519 (PEM)}"
PROTOCOLO="${5:-1}"
NOME="AgroEase-Agente-Impressao"
BASE_URL="https://github.com/${REPO}/releases/download/v${VERSAO}"

[[ "$VERSAO" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$ ]] || { echo "versão inválida: $VERSAO" >&2; exit 2; }
[[ "$PROTOCOLO" =~ ^[0-9]+$ ]] || { echo "protocolo inválido: $PROTOCOLO" >&2; exit 2; }
[ -r "$CHAVE" ] || { echo "chave não legível: $CHAVE" >&2; exit 2; }
case "$CHAVE" in /*) ;; *) CHAVE="$PWD/$CHAVE" ;; esac   # o cd abaixo quebraria um caminho relativo
cd "$DIR"

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }
tamanho() { stat -c %s "$1" 2>/dev/null || stat -f %z "$1"; }

# ── 1) nomes canônicos: <so>-<arch> a partir do que o jpackage gerou em cada runner ────────────────────────────────
# O nome do arquivo já vem com o sufixo quando o ci.yml renomeou; aqui é só rede de segurança para arquivos "crus".
for f in *.exe *.deb *.dmg *.tar.gz; do
  [ -e "$f" ] || continue
  case "$f" in
    "$NOME-$VERSAO-"*-*.exe|"$NOME-$VERSAO-"*-*.deb|"$NOME-$VERSAO-"*-*.dmg|"$NOME-$VERSAO-"*-*.tar.gz) ;; # já canônico
    *.exe) mv -v "$f" "$NOME-$VERSAO-windows-x64.exe" ;;
    *.deb) mv -v "$f" "$NOME-$VERSAO-linux-x64.deb" ;;
    *.tar.gz) mv -v "$f" "$NOME-$VERSAO-linux-x64.tar.gz" ;;
    *.dmg) echo "dmg sem arch no nome ($f): renomeie no runner (macos-arm64|macos-x64)" >&2; exit 3 ;;
  esac
done

# ── 2) sha256 ────────────────────────────────────────────────────────────────────────────────────────────────────
: > SHA256SUMS.txt
for f in "$NOME-$VERSAO-"*.exe "$NOME-$VERSAO-"*.deb "$NOME-$VERSAO-"*.dmg "$NOME-$VERSAO-"*.tar.gz; do
  [ -e "$f" ] || continue
  h=$(sha256 "$f")
  echo "$h  $f" >> SHA256SUMS.txt
  echo "$h" > "$f.sha256"
done
[ -s SHA256SUMS.txt ] || { echo "nenhum instalador em $DIR" >&2; exit 4; }

# ── 3) latest.json ───────────────────────────────────────────────────────────────────────────────────────────────
chave_so() { # arquivo → chave que o backend F1 usa (AGENTE_URL_<SO>)
  case "$1" in
    *-windows-x64.exe) echo windows ;;
    *-linux-x64.deb)   echo linux ;;
    *-linux-x64.tar.gz) echo linux-tar ;;   # F6: app-image por usuário (self-update no Linux)
    *-macos-arm64.dmg) echo macos-arm64 ;;
    *-macos-x64.dmg)   echo macos-x64 ;;
    *) echo "" ;;
  esac
}
PUB_DER_KID=$(openssl pkey -in "$CHAVE" -pubout -outform DER | (sha256sum 2>/dev/null || shasum -a 256) | cut -c1-16)
PUBLICADO_EM=$(date -u +%Y-%m-%dT%H:%M:%SZ)
{
  printf '{"formato":1,"versao":"%s","protocolo":%s,"publicadoEm":"%s","kid":"%s","artefatos":{' "$VERSAO" "$PROTOCOLO" "$PUBLICADO_EM" "$PUB_DER_KID"
  primeiro=1
  while read -r h f; do
    so=$(chave_so "$f"); [ -n "$so" ] || { echo "arquivo fora do padrão: $f" >&2; exit 5; }
    [ $primeiro -eq 1 ] || printf ','
    primeiro=0
    printf '"%s":{"arquivo":"%s","url":"%s/%s","sha256":"%s","tamanho":%s}' "$so" "$f" "$BASE_URL" "$f" "$h" "$(tamanho "$f")"
  done < SHA256SUMS.txt
  printf '}}'
} > latest.json

# ── 4) assinatura detached Ed25519 sobre os bytes crus (o agente verifica com VerificadorAssinaturaRelease) ─────────
openssl pkeyutl -sign -inkey "$CHAVE" -rawin -in latest.json -out latest.json.sig
openssl pkey -in "$CHAVE" -pubout -out /tmp/release-pub.pem
openssl pkeyutl -verify -pubin -inkey /tmp/release-pub.pem -rawin -in latest.json -sigfile latest.json.sig >/dev/null
rm -f /tmp/release-pub.pem

echo "== latest.json"; cat latest.json; echo
echo "== SHA256SUMS.txt"; cat SHA256SUMS.txt
echo "== assinatura: $(tamanho latest.json.sig) bytes, kid $PUB_DER_KID"

# ── 5) envs do Railway prontas para copiar (passo manual enquanto a automação está desligada) ───────────────────────
{
  echo "AGENTE_VERSAO_ATUAL=$VERSAO"
  while read -r h f; do
    so=$(chave_so "$f" | tr 'a-z-' 'A-Z_')
    [ "$so" = "LINUX_TAR" ] && continue   # o backend F1 não tem env para o tar.gz; o self-update lê direto do latest.json
    echo "AGENTE_URL_${so}=$BASE_URL/$f"
    echo "AGENTE_SHA256_${so}=$h"
  done < SHA256SUMS.txt
} > envs-railway.txt
echo "== envs-railway.txt"; cat envs-railway.txt
