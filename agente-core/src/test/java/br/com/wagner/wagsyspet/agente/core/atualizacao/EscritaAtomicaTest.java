package br.com.wagner.wagsyspet.agente.core.atualizacao;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;

import static org.assertj.core.api.Assertions.assertThat;

/** Extraída das 2 cópias privadas (ConfiguracaoLocalArquivo/CofreCredencial): tmp ao lado + move atômico; nunca deixa .tmp. */
@DisplayName("EscritaAtomica — tmp + move, substitui, cria a pasta, permissões só-dono opcionais")
class EscritaAtomicaTest {

    @Test
    void gravaSubstituiESemTmp(@TempDir Path dir) throws Exception {
        Path alvo = dir.resolve("sub").resolve("estado.json");
        EscritaAtomica.gravar(alvo, "um".getBytes(StandardCharsets.UTF_8), false);
        assertThat(Files.readString(alvo)).isEqualTo("um");
        EscritaAtomica.gravarTexto(alvo, "dois", false);
        assertThat(Files.readString(alvo)).isEqualTo("dois");
        try (var s = Files.list(alvo.getParent())) {
            assertThat(s.map(p -> p.getFileName().toString())).containsExactly("estado.json");
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void permissoesSoDono(@TempDir Path dir) throws Exception {
        Path alvo = dir.resolve("segredo.bin");
        EscritaAtomica.gravar(alvo, new byte[] {1, 2, 3}, true);
        assertThat(Files.getPosixFilePermissions(alvo)).containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        Path publico = dir.resolve("config.json");
        EscritaAtomica.gravar(publico, new byte[] {1}, false);
        assertThat(Files.getPosixFilePermissions(publico)).contains(PosixFilePermission.OWNER_READ);
    }
}
