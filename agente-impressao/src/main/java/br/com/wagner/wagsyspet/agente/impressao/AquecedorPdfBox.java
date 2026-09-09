package br.com.wagner.wagsyspet.agente.impressao;

import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/**
 * Aquece o PDFBox na subida do agente (plano F3 D12): o 1º mapeamento de fonte não embutida varre TODAS as fontes do
 * SO ({@code FileSystemFontProvider}) e grava {@code .pdfbox.cache} — no Windows isso pode passar dos 15 s que o PWA
 * espera pelo 1º cupom, virando {@code imprimir_erro} + cupom saindo atrasado (duplicata). Rodando aqui, em thread de
 * fundo, o 1º job já encontra o cache pronto.
 *
 * <p>{@code pdfbox.fontcache} aponta para a pasta do agente (não {@code user.home}), para o cache sobreviver a perfis
 * itinerantes e ser apagável pelo {@code --desinstalar}. Só tem efeito se definido ANTES do 1º uso do PDFBox — por isso
 * {@link #configurarCache(Path)} é chamado no {@code Main} antes de subir o servidor.</p>
 */
public final class AquecedorPdfBox {

    private static final Logger log = LoggerFactory.getLogger(AquecedorPdfBox.class);
    public static final String PROP_CACHE = "pdfbox.fontcache";
    /** Fonte do cupom gerado pelo backend (DocumentoTermicoHtmlBuilder): é ela que o 1º job vai pedir. */
    static final String FONTE_DO_CUPOM = "Courier New";

    private AquecedorPdfBox() {
    }

    /** Define a pasta do cache de fontes se ninguém definiu (respeita override por -D). */
    public static Path configurarCache(Path pastaDoAgente) {
        if (System.getProperty(PROP_CACHE) == null) {
            System.setProperty(PROP_CACHE, pastaDoAgente.toAbsolutePath().toString());
        }
        return Path.of(System.getProperty(PROP_CACHE));
    }

    /** Dispara o aquecimento em thread daemon; nunca lança, nunca segura a subida. */
    public static Thread aquecerEmSegundoPlano() {
        Thread t = new Thread(AquecedorPdfBox::aquecer, "agente-aquecedor-pdfbox");
        t.setDaemon(true);
        t.start();
        return t;
    }

    /** Aquecimento síncrono (testes e diagnóstico). Devolve o tempo gasto. */
    public static Duration aquecer() {
        Instant inicio = Instant.now();
        try {
            // pede a fonte do cupom ao mapper: constrói o FileSystemFontProvider (varredura + .pdfbox.cache) uma vez por processo
            FontMappers.instance().getFontBoxFont(FONTE_DO_CUPOM, null);
        } catch (RuntimeException | Error e) {
            log.warn("Aquecimento do PDFBox falhou ({}); o 1º cupom pode demorar mais", e.toString());
        }
        Duration gasto = Duration.between(inicio, Instant.now());
        Path cache = Path.of(System.getProperty(PROP_CACHE, System.getProperty("user.home"))).resolve(".pdfbox.cache");
        log.info("PDFBox aquecido em {} ms (cache de fontes: {}{})", gasto.toMillis(), cache, Files.exists(cache) ? "" : " — ainda não gravado");
        return gasto;
    }
}
