package br.com.wagner.wagsyspet.agente.core;

import java.util.Objects;

/**
 * Identidade que o agente anuncia no {@code hello_ok} (contrato §7.4-1): versão ÚNICA do binário
 * ({@link VersaoDoBinario}), versão do protocolo WebSocket e o {@code agenteId} recebido do backend no pareamento
 * (UID público — sem o JWT da loja não serve para nada; o PWA precisa dele para pedir o ticket na 1ª conexão da máquina).
 *
 * <p>O PWA compara {@code versao} com a mínima publicada pelo backend e exige {@code agenteId} como string: sem ele fecha
 * {@code 1000 'desatualizado'} e nunca envia {@code auth}.</p>
 */
public record InfoAgente(String versao, int protocolo, String agenteId) {

    public InfoAgente {
        Objects.requireNonNull(versao, "versao");
        Objects.requireNonNull(agenteId, "agenteId");
        if (versao.isBlank()) {
            throw new IllegalArgumentException("versao em branco");
        }
        if (agenteId.isBlank()) {
            throw new IllegalArgumentException("agenteId em branco");
        }
        if (protocolo <= 0) {
            throw new IllegalArgumentException("protocolo deve ser >= 1");
        }
    }

    public String sistemaOperacional() {
        return System.getProperty("os.name", "desconhecido");
    }
}
