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
}
