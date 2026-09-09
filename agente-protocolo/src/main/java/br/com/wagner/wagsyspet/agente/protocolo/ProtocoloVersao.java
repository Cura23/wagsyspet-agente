package br.com.wagner.wagsyspet.agente.protocolo;

/**
 * Versão do protocolo WebSocket PWA ↔ agente. Espelho de {@code PROTOCOLO_VERSAO} em
 * {@code wagsyspet-frontend/src/services/impressao/agenteProtocolo.ts} e de {@code AGENTE_PROTOCOLO_MINIMO} no backend.
 * Sobe só em quebra de contrato (mensagem/campo incompatível), nunca por versão do binário.
 */
public final class ProtocoloVersao {

    /** v1 = hello/hello_ok/auth/auth_ok/listar_impressoras/selecionar_impressora/imprimir/ping/erro (canônico §7.4). */
    public static final int ATUAL = 1;

    private ProtocoloVersao() {
    }
}
