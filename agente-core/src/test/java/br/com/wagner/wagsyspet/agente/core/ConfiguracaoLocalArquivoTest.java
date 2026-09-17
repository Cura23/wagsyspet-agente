package br.com.wagner.wagsyspet.agente.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfiguracaoLocalArquivo — config.json por máquina, escrita atômica, tolerante a lixo")
class ConfiguracaoLocalArquivoTest {

    @Test
    @DisplayName("grava e relê a impressora selecionada; limpar remove o campo; sem .tmp sobrando")
    void gravaERele(@TempDir Path dir) throws Exception {
        Path arquivo = dir.resolve("sub/config.json"); // diretório ainda não existe: deve criar
        ConfiguracaoLocalArquivo c = new ConfiguracaoLocalArquivo(arquivo);
        assertThat(c.impressoraSelecionada()).isEmpty();

        c.impressoraSelecionada("EPSON TM-T20");
        assertThat(c.impressoraSelecionada()).contains("EPSON TM-T20");
        assertThat(new ConfiguracaoLocalArquivo(arquivo).impressoraSelecionada()).as("relido do disco").contains("EPSON TM-T20");
        assertThat(Files.readString(arquivo)).contains("\"impressoraSelecionada\"");
        assertThat(Files.exists(dir.resolve("sub/config.json.tmp"))).as("tmp movido").isFalse();

        c.impressoraSelecionada("  ");
        assertThat(c.impressoraSelecionada()).isEmpty();
        assertThat(new ConfiguracaoLocalArquivo(arquivo).impressoraSelecionada()).isEmpty();
    }

    @Test
    @DisplayName("arquivo corrompido ou com campo de tipo errado → sem seleção, sem exceção (não derruba o agente)")
    void corrompidoNaoDerruba(@TempDir Path dir) throws Exception {
        Path arquivo = dir.resolve("config.json");
        Files.writeString(arquivo, "{isso nao e json", StandardCharsets.UTF_8);
        assertThat(new ConfiguracaoLocalArquivo(arquivo).impressoraSelecionada()).isEmpty();

        Files.writeString(arquivo, "{\"impressoraSelecionada\": 42}", StandardCharsets.UTF_8);
        ConfiguracaoLocalArquivo c = new ConfiguracaoLocalArquivo(arquivo);
        // 42 vira "42" pelo asText — aceitamos como nome (o listar filtra o que não existe); o que importa é não lançar
        assertThat(c.impressoraSelecionada()).isPresent();

        c.impressoraSelecionada("PDF"); // sobrescreve o lixo
        assertThat(new ConfiguracaoLocalArquivo(arquivo).impressoraSelecionada()).contains("PDF");
    }

    @Test
    @DisplayName("F6-L5 extras (gaveta/corte): round-trip no config.json junto da impressora; outra instância (bandeja × servidor) relê; config.json ANTIGO (só a impressora) → extras desligados")
    void extrasRoundTrip(@TempDir Path dir) throws Exception {
        Path arquivo = dir.resolve("config.json");
        ConfiguracaoLocalArquivo c = new ConfiguracaoLocalArquivo(arquivo);
        c.impressoraSelecionada("EPSON TM-T20");
        assertThat(c.extrasAtivos()).as("default: tudo desligado").isEmpty();
        c.extras(new ExtrasImpressao("EPSON TM-T20", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESC_BEMA, true, false, 5, 120));
        ExtrasImpressao lido = new ConfiguracaoLocalArquivo(arquivo).extrasAtivos().orElseThrow();
        assertThat(lido).isEqualTo(new ExtrasImpressao("EPSON TM-T20", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESC_BEMA, true, false, 5, 120));
        assertThat(new ConfiguracaoLocalArquivo(arquivo).impressoraSelecionada()).contains("EPSON TM-T20");

        Path antigo = dir.resolve("antigo.json");
        Files.writeString(antigo, "{ \"impressoraSelecionada\": \"PDF\" }");
        ConfiguracaoLocalArquivo a = new ConfiguracaoLocalArquivo(antigo);
        assertThat(a.impressoraSelecionada()).contains("PDF");
        assertThat(a.extrasAtivos()).isEmpty();
    }

    @Test
    @DisplayName("REGRA DURA: a autorização é daquela IMPRESSORA — trocar de impressora zera os extras (nunca herdar um opt-in dado a outro hardware: uma laser receberia bytes de gaveta e cuspiria folha em branco); reescolher a MESMA mantém; extras de outra impressora ou com tudo desligado → extrasAtivos vazio")
    void extrasSaoDaImpressora(@TempDir Path dir) throws Exception {
        ConfiguracaoLocalArquivo c = new ConfiguracaoLocalArquivo(dir.resolve("config.json"));
        c.impressoraSelecionada("EPSON");
        c.extras(new ExtrasImpressao("EPSON", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESCPOS, true, true, 2, 50));
        c.impressoraSelecionada("EPSON");
        assertThat(c.extrasAtivos()).as("mesma impressora: mantém").isPresent();
        c.impressoraSelecionada("HP LaserJet");
        assertThat(c.extrasAtivos()).as("outra impressora: zera").isEmpty();
        c.impressoraSelecionada("EPSON");
        assertThat(c.extrasAtivos()).as("voltar não ressuscita o opt-in").isEmpty();

        c.extras(new ExtrasImpressao("OUTRA", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESCPOS, true, true, 2, 50));
        assertThat(c.extrasAtivos()).as("extras de impressora que não é a selecionada").isEmpty();
        c.extras(new ExtrasImpressao("EPSON", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESCPOS, false, false, 2, 50));
        assertThat(c.extrasAtivos()).as("nada ligado = desligado").isEmpty();
        c.extras(null);
        assertThat(c.extrasAtivos()).isEmpty();
    }

    @Test
    @DisplayName("extras com lixo (dialeto inexistente, pulso fora de 50–250, pino 3, tipo errado) → desligados, sem exceção; a impressora selecionada continua valendo")
    void extrasComLixo(@TempDir Path dir) throws Exception {
        for (String extras : new String[]{
                "{\"impressora\":\"EPSON\",\"dialeto\":\"STAR\",\"gaveta\":true}",
                "{\"impressora\":\"EPSON\",\"dialeto\":\"ESCPOS\",\"gaveta\":true,\"gavetaPulsoMs\":9000}",
                "{\"impressora\":\"EPSON\",\"dialeto\":\"ESCPOS\",\"gaveta\":true,\"gavetaPino\":3}",
                "{\"impressora\":\"EPSON\",\"dialeto\":\"ESCPOS\",\"gaveta\":\"sim\"}",
                "\"texto\""}) {
            Path arquivo = dir.resolve("c" + Math.abs(extras.hashCode()) + ".json");
            Files.writeString(arquivo, "{ \"impressoraSelecionada\": \"EPSON\", \"extras\": " + extras + " }");
            ConfiguracaoLocalArquivo c = new ConfiguracaoLocalArquivo(arquivo);
            assertThat(c.impressoraSelecionada()).as(extras).contains("EPSON");
            assertThat(c.extrasAtivos()).as(extras).isEmpty();
        }
    }

    @Test
    @DisplayName("selecionar(nome, extras) grava impressora E opt-in numa ÚNICA escrita (o painel do PWA não pode receber ERRO com a impressora já trocada pela metade — adversarial L5): extras nulos mantêm o opt-in se a impressora é a mesma e zeram se mudou")
    void selecionarEmUmaEscrita(@TempDir Path dir) throws Exception {
        Path arquivo = dir.resolve("config.json");
        ConfiguracaoLocalArquivo c = new ConfiguracaoLocalArquivo(arquivo);
        var epson = new ExtrasImpressao("EPSON", br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto.ESCPOS, true, true, 2, 50);
        c.selecionar("EPSON", epson);
        String depoisDeUma = Files.readString(arquivo);
        assertThat(depoisDeUma).contains("\"impressoraSelecionada\" : \"EPSON\"").contains("\"gaveta\" : true");
        c.selecionar("EPSON", null);
        assertThat(c.extrasAtivos()).as("mesma impressora sem extras no pedido: mantém").isPresent();
        c.selecionar("HP", null);
        assertThat(c.extrasAtivos()).isEmpty();
        assertThat(c.impressoraSelecionada()).contains("HP");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> c.selecionar("HP", epson)).as("extras de OUTRA impressora não entram").isInstanceOf(IllegalArgumentException.class);
    }
}
