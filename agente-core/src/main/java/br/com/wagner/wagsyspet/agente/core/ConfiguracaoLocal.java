package br.com.wagner.wagsyspet.agente.core;

import java.io.IOException;
import java.util.Optional;

/**
 * Configuração POR MÁQUINA que o protocolo lê e grava: hoje só a impressora selecionada neste computador
 * ({@code selecionar_impressora}, plano F3 D11 — a impressora vive no agente, não na loja). Em produção é o
 * {@code config.json} da pasta de dados do agente ({@link ConfiguracaoLocalArquivo}); nos testes, memória.
 */
public interface ConfiguracaoLocal {

    Optional<String> impressoraSelecionada();

    /** {@code null} ou branco limpa a seleção. Trocar de impressora ZERA os {@link #extras()} (o opt-in é daquele hardware). */
    void impressoraSelecionada(String nome) throws IOException;

    /**
     * Seleciona a impressora E (opcionalmente) o opt-in de gaveta/corte numa ÚNICA gravação — o painel do PWA não pode receber ERRO com a
     * impressora já trocada pela metade. {@code extrasOuNull == null}: mantém o opt-in se a impressora é a mesma, zera se mudou.
     *
     * @throws IllegalArgumentException se os extras forem de OUTRA impressora
     */
    default void selecionar(String nome, ExtrasImpressao extrasOuNull) throws IOException {
        if (extrasOuNull != null && !extrasOuNull.impressora().equals(nome)) {
            throw new IllegalArgumentException("extras de '" + extrasOuNull.impressora() + "' não valem para '" + nome + "'");
        }
        impressoraSelecionada(nome);
        if (extrasOuNull != null) {
            extras(extrasOuNull);
        }
    }

    /** F6-L5: opt-in de gaveta/corte como está gravado (pode ser de uma impressora que já não é a selecionada). */
    Optional<ExtrasImpressao> extras();

    /** {@code null} desliga tudo. */
    void extras(ExtrasImpressao extras) throws IOException;

    /** O que o protocolo usa: só vale se for da impressora SELECIONADA e houver algo ligado. */
    default Optional<ExtrasImpressao> extrasAtivos() {
        Optional<String> selecionada = impressoraSelecionada();
        return extras().filter(e -> e.algumLigado() && selecionada.isPresent() && selecionada.get().equals(e.impressora()));
    }
}
