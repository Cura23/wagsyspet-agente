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

    private final AtomicReference<ExtrasImpressao> extras = new AtomicReference<>();

    @Override
    public void impressoraSelecionada(String nome) {
        String novo = nome == null || nome.isBlank() ? null : nome;
        if (!java.util.Objects.equals(novo, impressora.get())) {
            extras.set(null); // o opt-in é daquele hardware
        }
        impressora.set(novo);
    }

    @Override
    public Optional<ExtrasImpressao> extras() {
        return Optional.ofNullable(extras.get());
    }

    @Override
    public void extras(ExtrasImpressao e) {
        extras.set(e);
    }

    public void limpar() {
        impressora.set(null);
        extras.set(null);
    }
}
