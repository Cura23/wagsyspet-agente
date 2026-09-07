package br.com.wagner.wagsyspet.agente.core;

import java.util.Objects;
import java.util.Set;

/**
 * Porteiro do handshake WebSocket do agente — as duas primeiras barreiras do endpoint local (plano §2.4):
 * <ol>
 *   <li><b>Origin exata</b> numa allowlist (scheme + host + porta, igualdade estrita). É o "crachá" da PÁGINA que
 *       o navegador envia automaticamente: só o PWA do WagSysPet passa; qualquer outro site aberto no mesmo
 *       navegador recebe 403 antes do upgrade. Origin ausente (cliente não-navegador) também é recusada.</li>
 *   <li><b>Host loopback</b> ({@code 127.0.0.1}, {@code localhost} ou {@code [::1]} na porta do agente) — barra
 *       DNS rebinding (nome público resolvendo para 127.0.0.1) e acesso pela LAN.</li>
 * </ol>
 * A 3ª barreira (ticket assinado pelo backend) é mensagem, não header — fica no protocolo (F3).
 * Regras puras: sem socket, sem estado, testáveis em isolamento.
 */
public final class PorteiroHandshake {

    /**
     * Decisão do porteiro. {@code status} é a SEMÂNTICA HTTP da decisão (403 = proibido) usada em log/diagnóstico.
     * ⚠️ O que o Java-WebSocket 1.6 escreve no socket ao recusar é sempre {@code 404 WebSocket Upgrade Failure}
     * (hardcoded em {@code WebSocketImpl.closeConnectionDueToWrongHandshake}); não há gancho pra trocar o código.
     * Isso é cosmético: a garantia de segurança é o upgrade (101) NUNCA acontecer — o navegador só vê "falhou".
     */
    public record Decisao(boolean permitido, int status, String motivo) {
        static Decisao ok() {
            return new Decisao(true, 101, "ok");
        }

        static Decisao recusa(String motivo) {
            return new Decisao(false, 403, motivo);
        }
    }

    private final Set<String> originsPermitidas;
    private final Set<String> hostsPermitidos;

    /**
     * @param originsPermitidas Origins exatas (ex.: {@code https://wagsyspet-frontend.vercel.app}); vêm do
     *                          {@code cors.allowed-origins} do backend no pareamento
     * @param porta             porta em que o agente escuta (compõe os Hosts loopback aceitos)
     */
    public PorteiroHandshake(Set<String> originsPermitidas, int porta) {
        this.originsPermitidas = Set.copyOf(Objects.requireNonNull(originsPermitidas));
        this.hostsPermitidos = Set.of("127.0.0.1:" + porta, "localhost:" + porta, "[::1]:" + porta);
    }

    /** Avalia os headers {@code Origin} e {@code Host} do handshake (ambos podem vir nulos). */
    public Decisao avaliar(String origin, String host) {
        if (origin == null || origin.isBlank()) {
            return Decisao.recusa("Origin ausente — só a página do AgroEase pode usar o agente");
        }
        if (!originsPermitidas.contains(origin)) {
            return Decisao.recusa("Origin não permitida: " + origin);
        }
        if (host == null || host.isBlank()) {
            return Decisao.recusa("Host ausente");
        }
        if (!hostsPermitidos.contains(host)) {
            return Decisao.recusa("Host não é loopback na porta do agente: " + host);
        }
        return Decisao.ok();
    }
}
