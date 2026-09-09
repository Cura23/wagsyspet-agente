package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado;
import br.com.wagner.wagsyspet.agente.impressao.ImpressoraJavaxPrint.Resultado.Estado;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Motor de impressão FALSO para os testes do protocolo: lista fixa de impressoras, resultado configurável por nome e um
 * "trinco" opcional que segura o job (prova que a fila não bloqueia o servidor e que o timeout interno responde).
 */
final class PortaImpressaoFake implements PortaImpressao {

    record Job(byte[] pdf, String impressora, String nomeJob) {
    }

    private final List<String> nomes;
    final List<Job> jobs = new CopyOnWriteArrayList<>();
    /** Impressora que devolve ERRO (simula falha do spooler). */
    volatile String impressoraComErro;
    /** Enquanto != null, todo imprimir() espera o trinco abrir (job travado). */
    final AtomicReference<CountDownLatch> trinco = new AtomicReference<>();
    /** Sinaliza que um job ENTROU no motor (para o teste saber que a fila está ocupada). */
    final AtomicReference<CountDownLatch> entrou = new AtomicReference<>();
    /** Enquanto != null, listar() espera o trinco (motor de listagem preso — spooler/CUPS fora). */
    final AtomicReference<CountDownLatch> trincoListar = new AtomicReference<>();

    PortaImpressaoFake(String... nomes) {
        this.nomes = List.of(nomes);
    }

    @Override
    public List<String> listar() {
        CountDownLatch t = trincoListar.get();
        if (t != null) {
            try {
                t.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        return nomes;
    }

    @Override
    public Optional<String> padrao() {
        return nomes.isEmpty() ? Optional.empty() : Optional.of(nomes.get(0));
    }

    @Override
    public Resultado imprimir(byte[] pdf, String impressora, String nomeJob) {
        CountDownLatch e = entrou.get();
        if (e != null) {
            e.countDown();
        }
        CountDownLatch t = trinco.get();
        if (t != null) {
            try {
                t.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        jobs.add(new Job(pdf, impressora, nomeJob));
        if (!nomes.contains(impressora)) {
            return new Resultado(Estado.IMPRESSORA_INDISPONIVEL, impressora, "impressora '" + impressora + "' não existe (fake)");
        }
        if (impressora.equals(impressoraComErro)) {
            return new Resultado(Estado.ERRO, impressora, "spooler recusou (fake)");
        }
        return new Resultado(Estado.ACEITO_SPOOLER, impressora, "job aceito (fake)");
    }
}
