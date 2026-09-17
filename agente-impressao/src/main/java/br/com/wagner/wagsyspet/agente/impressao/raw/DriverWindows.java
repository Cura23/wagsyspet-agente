package br.com.wagner.wagsyspet.agente.impressao.raw;

import br.com.wagner.wagsyspet.agente.impressao.spooler.ComandoSpooler;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * O driver desta impressora do Windows é v4/XPSDrv? (adversarial L5). O JDK manda o comando cru SEMPRE como {@code pDatatype="RAW"} e
 * aceita o flavor para qualquer fila sem consultar o driver; driver v4 é baseado em XPS e DESCARTA RAW em silêncio (Microsoft KB 2779300:
 * "produces a 0-byte spool file") — a gaveta nunca abriria e o agente responderia "sucesso" a cada venda. Drivers v3 (Epson APD, Elgin,
 * Bematech, Generic/Text Only) aceitam. Detecção sem código nativo: drivers instalados ficam em
 * {@code HKLM\\…\\Print\\Environments\\<arquitetura>\\Drivers\\Version-3|Version-4\\<nome do driver>}.
 * Na dúvida (driver desconhecido, {@code reg} indisponível) devolve {@code false}: não bloquear por palpite.
 */
public final class DriverWindows {

    private static final String BASE = "HKLM\\SYSTEM\\CurrentControlSet\\Control\\Print\\Environments\\";
    private static final List<String> ARQUITETURAS = List.of("Windows x64", "Windows ARM64", "Windows NT x86");

    private DriverWindows() {
    }

    /** Para o motor real: fora do Windows nunca é v4; no Windows consulta o nome do driver (winspool) e o registro. */
    public static boolean v4(String impressora) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        return v4(impressora, DriverWindows::nomeDoDriver, ComandoSpooler.real(Duration.ofSeconds(3)));
    }

    static boolean v4(String impressora, Function<String, Optional<String>> nomeDoDriver, Function<List<String>, ComandoSpooler.Saida> reg) {
        Optional<String> driver;
        try {
            driver = nomeDoDriver.apply(impressora);
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        if (driver.isEmpty() || driver.get().isBlank()) {
            return false;
        }
        for (String arquitetura : ARQUITETURAS) {
            ComandoSpooler.Saida s = reg.apply(List.of("reg", "query", BASE + arquitetura + "\\Drivers\\Version-4\\" + driver.get()));
            if (s.ok()) {
                return true;
            }
        }
        return false;
    }

    /** {@code PRINTER_INFO_2.pDriverName} (JNA/winspool, já usado no acompanhamento do L4). */
    private static Optional<String> nomeDoDriver(String impressora) {
        try {
            return Optional.ofNullable(com.sun.jna.platform.win32.WinspoolUtil.getPrinterInfo2(impressora).pDriverName);
        } catch (RuntimeException | LinkageError e) {
            return Optional.empty();
        }
    }
}
