package br.com.wagner.wagsyspet.agente.impressao;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("AquecedorPdfBox — cache de fontes na pasta do agente, aquecimento sem exceção")
class AquecedorPdfBoxTest {

    private final String anterior = System.getProperty(AquecedorPdfBox.PROP_CACHE);

    @AfterEach
    void restaurar() {
        if (anterior == null) {
            System.clearProperty(AquecedorPdfBox.PROP_CACHE);
        } else {
            System.setProperty(AquecedorPdfBox.PROP_CACHE, anterior);
        }
    }

    @Test
    @DisplayName("configurarCache aponta pdfbox.fontcache para a pasta do agente; um -D já definido vence")
    void configurar(@TempDir Path tmp) {
        System.clearProperty(AquecedorPdfBox.PROP_CACHE);
        assertThat(AquecedorPdfBox.configurarCache(tmp)).isEqualTo(tmp.toAbsolutePath());
        assertThat(System.getProperty(AquecedorPdfBox.PROP_CACHE)).isEqualTo(tmp.toAbsolutePath().toString());

        System.setProperty(AquecedorPdfBox.PROP_CACHE, tmp.resolve("outro").toString());
        assertThat(AquecedorPdfBox.configurarCache(tmp)).isEqualTo(tmp.resolve("outro"));
    }

    @Test
    @DisplayName("aquecer() roda a varredura de fontes sem lançar; a 2ª chamada é imediata (provider já construído)")
    void aquece(@TempDir Path tmp) {
        System.setProperty(AquecedorPdfBox.PROP_CACHE, tmp.toString());
        Duration primeira = AquecedorPdfBox.aquecer();
        Duration segunda = AquecedorPdfBox.aquecer();
        System.out.println("[PDFBOX] 1ª " + primeira.toMillis() + " ms, 2ª " + segunda.toMillis() + " ms, cache em " + tmp
                + " existe=" + Files.exists(tmp.resolve(".pdfbox.cache")));
        assertThat(segunda).isLessThan(Duration.ofSeconds(2));
        // o provider é singleton por processo: se OUTRO teste já o construiu com outra pasta, o arquivo pode não estar aqui —
        // por isso não se asserta a existência do .pdfbox.cache (é log), só que o aquecimento não lança e não repete o custo
    }

    @Test
    @DisplayName("aquecerEmSegundoPlano devolve thread daemon e não segura quem chamou")
    void segundoPlano() throws Exception {
        Thread t = AquecedorPdfBox.aquecerEmSegundoPlano();
        assertThat(t.isDaemon()).isTrue();
        t.join(60_000);
        assertThat(t.isAlive()).isFalse();
    }
}
