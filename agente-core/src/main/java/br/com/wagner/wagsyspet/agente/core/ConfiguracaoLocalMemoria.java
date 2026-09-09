package br.com.wagner.wagsyspet.agente.core;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/** Configuração só em memória: testes e o host de desenvolvimento ({@code AgenteMain} sem pareamento). */
public final class ConfiguracaoLocalMemoria implements ConfiguracaoLocal {

    private final AtomicReference<String> impressora = new AtomicReference<>();

    @Override
    public Optional<String> impressoraSelecionada() {
        return Optional.ofNullable(impressora.get());
    }

    @Override
    public void impressoraSelecionada(String nome) {
        impressora.set(nome == null || nome.isBlank() ? null : nome);
    }

    public void limpar() {
        impressora.set(null);
    }
}
