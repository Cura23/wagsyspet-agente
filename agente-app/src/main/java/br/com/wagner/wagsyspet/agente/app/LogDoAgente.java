package br.com.wagner.wagsyspet.agente.app;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.ConsoleHandler;
import java.util.logging.FileHandler;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.logging.Formatter;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Log do binário (plano F3 D19): SLF4J → {@code java.util.logging} ({@code slf4j-jdk14}) → arquivo rotativo
 * {@code logs/agente-N.log} ({@value #TAMANHO_BYTES} bytes × {@value #ARQUIVOS}) na pasta do agente. O launcher GUI do
 * Windows não tem stderr: sem isto o agente instalado não logava NADA. Console só nos comandos de linha.
 *
 * <p>Nunca vai para o log: PDF, ticket inteiro, código de pareamento (as classes que os manipulam já não os logam).</p>
 */
final class LogDoAgente {

    static final int TAMANHO_BYTES = 1024 * 1024;
    static final int ARQUIVOS = 5;
    static final String PADRAO_ARQUIVO = "agente-%g.log";
    private static final DateTimeFormatter DATA = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    private LogDoAgente() {
    }

    /**
     * {@code 2026-09-09 14:03:07 INFO    br.com…ServidorAgente - mensagem}. Nível pelo nome INTERNO ({@code INFO}, não
     * "INFORMAÇÕES" do locale): o suporte faz grep no log de qualquer máquina com a mesma palavra.
     */
    static final class FormatoLinha extends Formatter {
        @Override
        public String format(LogRecord r) {
            StringBuilder sb = new StringBuilder(160);
            sb.append(DATA.format(Instant.ofEpochMilli(r.getMillis()))).append(' ')
                    .append(String.format("%-7s", r.getLevel().getName())).append(' ')
                    .append(r.getLoggerName()).append(" - ").append(formatMessage(r));
            if (r.getThrown() != null) {
                StringWriter sw = new StringWriter();
                r.getThrown().printStackTrace(new PrintWriter(sw));
                sb.append(System.lineSeparator()).append(sw);
            }
            return sb.append(System.lineSeparator()).toString();
        }
    }

    /**
     * @param pastaLogs  {@code <dados>/logs}
     * @param verboso    FINE em vez de INFO
     * @param console    também no stderr (comandos de linha / dev)
     * @return o caminho do arquivo ativo (para o {@code --status} e o menu "Ver log")
     */
    static Path configurar(Path pastaLogs, boolean verboso, boolean console) throws IOException {
        Files.createDirectories(pastaLogs);
        LogManager.getLogManager().reset();
        Logger raiz = Logger.getLogger("");
        for (Handler h : raiz.getHandlers()) {
            raiz.removeHandler(h);
        }
        Level nivel = verboso ? Level.FINE : Level.INFO;
        raiz.setLevel(nivel);

        FileHandler arquivo = new FileHandler(pastaLogs.resolve(PADRAO_ARQUIVO).toString(), TAMANHO_BYTES, ARQUIVOS, true);
        arquivo.setEncoding(StandardCharsets.UTF_8.name());
        arquivo.setFormatter(new FormatoLinha());
        arquivo.setLevel(nivel);
        raiz.addHandler(arquivo);

        if (console) {
            ConsoleHandler ch = new ConsoleHandler();
            ch.setFormatter(new FormatoLinha());
            ch.setLevel(nivel);
            raiz.addHandler(ch);
        }
        // bibliotecas tagarelas ficam em WARNING mesmo no verboso
        Logger.getLogger("org.java_websocket").setLevel(Level.WARNING);
        Logger.getLogger("org.apache.pdfbox").setLevel(Level.WARNING);
        Logger.getLogger("org.apache.fontbox").setLevel(Level.WARNING);
        return pastaLogs.resolve("agente-0.log");
    }

    /** Só console (comandos de linha sem pasta de dados), nível WARNING para não poluir a saída do comando. */
    static void soConsole(boolean verboso) {
        LogManager.getLogManager().reset();
        Logger raiz = Logger.getLogger("");
        for (Handler h : raiz.getHandlers()) {
            raiz.removeHandler(h);
        }
        Level nivel = verboso ? Level.FINE : Level.WARNING;
        raiz.setLevel(nivel);
        ConsoleHandler ch = new ConsoleHandler();
        ch.setFormatter(new FormatoLinha());
        ch.setLevel(nivel);
        raiz.addHandler(ch);
    }
}
