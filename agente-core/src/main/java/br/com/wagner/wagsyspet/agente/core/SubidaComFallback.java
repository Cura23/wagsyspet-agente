package br.com.wagner.wagsyspet.agente.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.IntFunction;

/**
 * Sobe o servidor tentando as portas do contrato em ordem (plano F3 D20 × §7.1): {@code 28421} e, se ocupada por OUTRO
 * processo, <b>uma</b> tentativa em {@code 28422} — sempre com uma instância NOVA do servidor (a da lib não é reiniciável:
 * o bind falho vaza 1 descritor, aceito e documentado). Nunca loop: as duas ocupadas → {@link NenhumaPortaLivreException}
 * (o processo sai com código 4 e o supervisor NÃO deve insistir sem intervenção).
 */
public final class SubidaComFallback {

    private static final Logger log = LoggerFactory.getLogger(SubidaComFallback.class);
    /** Ordem fixa espelhada em {@code PORTAS_AGENTE} do PWA. */
    public static final int[] PORTAS_PADRAO = {AgenteMain.PORTA_PADRAO, 28422};

    public static final class NenhumaPortaLivreException extends IOException {
        public NenhumaPortaLivreException(String msg) {
            super(msg);
        }
    }

    private SubidaComFallback() {
    }

    /**
     * @param fabrica cria um servidor NOVO para a porta dada
     * @return o servidor já escutando
     * @throws NenhumaPortaLivreException todas as portas falharam no bind
     * @throws IOException                falha que não é "porta ocupada" (ex.: {@code onStart} lançou) — não tenta a próxima
     */
    public static ServidorAgente subir(IntFunction<ServidorAgente> fabrica, int[] portas, Duration timeout)
            throws IOException, InterruptedException, TimeoutException {
        List<String> falhas = new ArrayList<>();
        for (int i = 0; i < portas.length; i++) {
            ServidorAgente s = fabrica.apply(portas[i]);
            try {
                s.iniciar(timeout);
                if (i > 0) {
                    log.warn("Porta {} ocupada; agente subiu na porta de fallback {} (o PWA tenta as duas)", portas[0], portas[i]);
                }
                return s;
            } catch (IOException e) {
                if (!portaOcupada(e)) {
                    throw e;
                }
                falhas.add(portas[i] + ": " + causaRaiz(e));
                log.warn("Porta {} ocupada ({}); {}", portas[i], causaRaiz(e), i + 1 < portas.length ? "tentando " + portas[i + 1] : "sem mais portas");
            }
        }
        throw new NenhumaPortaLivreException("Nenhuma porta do agente livre em 127.0.0.1 (" + String.join("; ", falhas)
                + "). Outro agente ou programa está usando as portas 28421 e 28422.");
    }

    static boolean portaOcupada(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.BindException) {
                return true;
            }
        }
        return false;
    }

    private static String causaRaiz(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }
}
