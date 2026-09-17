package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw;
import br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto;

import java.util.Objects;

/**
 * Opt-in dos comandos não fiscais (gaveta/corte) DESTE computador para UMA impressora (plano F6 D10). Default: não existe = tudo
 * desligado. A autorização é do hardware: só vale enquanto {@link #impressora} for a impressora selecionada — uma impressora que não é
 * ESC/POS recebe os bytes como lixo e o spooler diz "sucesso" (uma laser cospe uma folha por comando).
 *
 * @param gavetaPino    2 (usual) ou 5
 * @param gavetaPulsoMs {@value ComandosRaw#PULSO_MINIMO_MS}–{@value ComandosRaw#PULSO_MAXIMO_MS} ms
 */
public record ExtrasImpressao(String impressora, Dialeto dialeto, boolean gaveta, boolean corte, int gavetaPino, int gavetaPulsoMs) {

    public ExtrasImpressao {
        Objects.requireNonNull(impressora, "impressora");
        Objects.requireNonNull(dialeto, "dialeto");
        if (impressora.isBlank()) {
            throw new IllegalArgumentException("impressora em branco");
        }
        ComandosRaw.abrirGaveta(dialeto, gavetaPino, gavetaPulsoMs); // valida pino e pulso pela MESMA regra do catálogo
    }

    public boolean algumLigado() {
        return gaveta || corte;
    }
}
