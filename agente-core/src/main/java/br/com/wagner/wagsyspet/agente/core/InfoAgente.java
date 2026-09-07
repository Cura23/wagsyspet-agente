package br.com.wagner.wagsyspet.agente.core;

/**
 * Identidade que o agente anuncia no {@code hello_ok}: versão do binário e versão do protocolo WebSocket.
 * O PWA compara com a versão mínima publicada pelo backend (auto-update MVP, plano §2.5).
 */
public record InfoAgente(String versao, int protocolo) {

    public String sistemaOperacional() {
        return System.getProperty("os.name", "desconhecido");
    }
}
