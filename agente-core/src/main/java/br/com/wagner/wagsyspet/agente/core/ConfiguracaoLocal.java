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

    /** {@code null} ou branco limpa a seleção. */
    void impressoraSelecionada(String nome) throws IOException;
}
