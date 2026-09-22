package br.com.wagner.wagsyspet.agente.protocolo;

/**
 * Versão do protocolo WebSocket PWA ↔ agente. Espelho de {@code PROTOCOLO_VERSAO} em
 * {@code wagsyspet-frontend/src/services/impressao/agenteProtocolo.ts} e de {@code AGENTE_PROTOCOLO_MINIMO} no backend.
 * Sobe só em quebra de contrato (mensagem/campo incompatível), nunca por versão do binário.
 */
public final class ProtocoloVersao {

    /**
     * v1 = hello/hello_ok/auth/auth_ok/listar_impressoras/selecionar_impressora/imprimir/ping/erro (canônico §7.4).
     * As extensões da F6 são ADITIVAS e anunciadas em {@code hello_ok.capacidades} (sem subir a versão): {@code estado_impressao}
     * (push {@code impressao_estado} + {@code consultar_impressao}), {@code comando_raw} ({@code comando} ABRIR_GAVETA/CORTAR,
     * {@code extras} em selecionar_impressora/impressoras, {@code avisos} em imprimir_ok) e {@code atualizacao} (close 1001
     * {@code ATUALIZANDO}). Um PWA antigo ignora os campos a mais; um agente antigo responde TIPO_DESCONHECIDO ao que não conhece.
     */
    public static final int ATUAL = 1;

    private ProtocoloVersao() {
    }
}
