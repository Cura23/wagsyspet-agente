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

    /** F6-L4: quando != null, todo job ACEITO nasce com este acompanhamento do spooler (recebe o nomeJob). */
    volatile java.util.function.Function<String, br.com.wagner.wagsyspet.agente.impressao.spooler.AcompanhamentoSpooler> acompanhamento;

    /** F6-L5: comandos crus recebidos (gaveta/corte), na ordem; e a ORDEM geral de chegada ao motor ("raw:<job>" / "pdf:<job>"). */
    record Raw(byte[] bytes, String impressora, String nomeJob) {
    }

    final List<Raw> raws = new CopyOnWriteArrayList<>();
    /** Fecho F6: quando não-nulo, o pré-voo da gaveta diz que a impressora NÃO está pronta (offline, fila parada, pulso já preso). */
    volatile String impedimentoDaGaveta;

    @Override
    public java.util.Optional<String> impedimentoDaGaveta(String impressora) {
        return java.util.Optional.ofNullable(impedimentoDaGaveta);
    }
    final List<String> ordem = new CopyOnWriteArrayList<>();
    /** Quando true, todo enviarRaw devolve ERRO (gaveta/corte falhando não pode derrubar o cupom). */
    volatile boolean rawComErro;

    /** Atraso (ms) de todo enviarRaw cujo job contém "corte" — spooler lento depois do PDF. */
    volatile long atrasoDoCorteMs;

    @Override
    public Resultado enviarRaw(byte[] bytes, String impressora, String nomeJob) {
        if (atrasoDoCorteMs > 0 && nomeJob.contains("corte")) {
            try {
                Thread.sleep(atrasoDoCorteMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        raws.add(new Raw(bytes, impressora, nomeJob));
        ordem.add("raw:" + nomeJob);
        return rawComErro ? new Resultado(Estado.ERRO, impressora, "raw recusado (fake)") : new Resultado(Estado.ACEITO_SPOOLER, impressora, "raw aceito (fake)");
    }

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
        ordem.add("pdf:" + nomeJob);
        if (!nomes.contains(impressora)) {
            return new Resultado(Estado.IMPRESSORA_INDISPONIVEL, impressora, "impressora '" + impressora + "' não existe (fake)");
        }
        if (impressora.equals(impressoraComErro)) {
            return new Resultado(Estado.ERRO, impressora, "spooler recusou (fake)");
        }
        var a = acompanhamento;
        return new Resultado(Estado.ACEITO_SPOOLER, impressora, "job aceito (fake)", a == null ? null : a.apply(nomeJob));
    }
}
