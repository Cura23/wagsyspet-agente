package br.com.wagner.wagsyspet.agente.core.pareamento;

import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Plano F3 D15 — pareamento.enc (AES-256-GCM) + chave.bin 0600; corrompido → renomeia e segue NÃO pareado. */
@DisplayName("CofreCredencial — grava/lê o pareamento cifrado, tolera lixo, nunca derruba")
class CofreCredencialTest {

    private static Pareamento exemplo() {
        return new Pareamento("3f1c2b6e-0f1a-4a2b-9c3d-000000000001", 7L, "c".repeat(43),
                ChavesTicket.exportarPublica(ChavesTicket.gerar().getPublic()),
                List.of("https://app.agroease.com.br"), 28421, "1.0.0",
                "https://wagsyspet-backend-production.up.railway.app", Instant.parse("2026-09-09T12:00:00Z"));
    }

    @Test
    @DisplayName("round-trip: gravar → ler devolve igual; arquivo NÃO contém os campos em claro; chave.bin tem 32 bytes")
    void roundTrip(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("agente"));
        CofreCredencial cofre = new CofreCredencial(dirs);
        assertThat(cofre.ler()).isEmpty();

        Pareamento p = exemplo();
        cofre.gravar(p);
        assertThat(cofre.ler()).contains(p);

        byte[] bruto = Files.readAllBytes(dirs.pareamento());
        String texto = new String(bruto, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(texto).doesNotContain(p.agenteId()).doesNotContain("agroease.com.br").doesNotContain("credencial");
        assertThat(Files.size(dirs.chave())).isEqualTo(32);
        assertThat(Files.exists(dirs.raiz().resolve("pareamento.enc.tmp"))).isFalse();

        // regravar (re-parear) reusa a chave e continua legível
        Pareamento outro = new Pareamento("outro-id", 8L, null, p.chavePublicaTicket(), p.origensPermitidas(), 28421, null, p.backendUrl(), Instant.EPOCH);
        cofre.gravar(outro);
        assertThat(cofre.ler()).contains(outro);
    }

    @Test
    @DisplayName("POSIX: chave.bin e pareamento.enc ficam 0600 (só o usuário do caixa)")
    void permissoes(@TempDir Path tmp) throws Exception {
        if (!Files.getFileStore(tmp).supportsFileAttributeView("posix")) {
            return;
        }
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("agente"));
        new CofreCredencial(dirs).gravar(exemplo());
        Set<PosixFilePermission> so = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(dirs.chave())).isEqualTo(so);
        assertThat(Files.getPosixFilePermissions(dirs.pareamento())).isEqualTo(so);
    }

    @Test
    @DisplayName("arquivo adulterado (1 byte) → GCM recusa → renomeado para *.corrompido-* e ler() devolve vazio, sem exceção")
    void adulterado(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("agente"));
        CofreCredencial cofre = new CofreCredencial(dirs);
        cofre.gravar(exemplo());
        byte[] b = Files.readAllBytes(dirs.pareamento());
        b[b.length - 3] ^= 0x55;
        Files.write(dirs.pareamento(), b);

        assertThat(cofre.ler()).isEmpty();
        assertThat(Files.exists(dirs.pareamento())).as("arquivo ruim sai do caminho").isFalse();
        try (Stream<Path> s = Files.list(dirs.raiz())) {
            assertThat(s.map(x -> x.getFileName().toString())).anyMatch(n -> n.startsWith("pareamento.enc.corrompido-"));
        }
        // e o próximo pareamento grava normalmente
        cofre.gravar(exemplo());
        assertThat(cofre.ler()).isPresent();
    }

    @Test
    @DisplayName("chave.bin perdida com pareamento.enc presente → ilegível → tratado como corrompido (não pareado)")
    void semChave(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("agente"));
        CofreCredencial cofre = new CofreCredencial(dirs);
        cofre.gravar(exemplo());
        Files.delete(dirs.chave());
        assertThat(cofre.ler()).isEmpty();
        assertThat(Files.exists(dirs.pareamento())).isFalse();
    }

    @Test
    @DisplayName("arquivo ILEGÍVEL (permissão negada) NÃO é corrupção: ler() volta vazio SEM mover o pareamento; ao voltar a permissão, lê normal (adversarial L3-A1)")
    void ilegivelNaoDestroi(@TempDir Path tmp) throws Exception {
        if (!Files.getFileStore(tmp).supportsFileAttributeView("posix") || "root".equals(System.getProperty("user.name"))) {
            return; // sem POSIX (ou root, que lê tudo) não dá para simular EACCES
        }
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("agente"));
        CofreCredencial cofre = new CofreCredencial(dirs);
        cofre.gravar(exemplo());
        Files.setPosixFilePermissions(dirs.pareamento(), Set.of());
        try {
            assertThat(cofre.ler()).isEmpty();
            assertThat(Files.exists(dirs.pareamento())).as("credencial intacta").isTrue();
            try (Stream<Path> s = Files.list(dirs.raiz())) {
                assertThat(s.map(x -> x.getFileName().toString())).noneMatch(n -> n.contains("corrompido"));
            }
        } finally {
            Files.setPosixFilePermissions(dirs.pareamento(), Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        }
        assertThat(cofre.ler()).isPresent();
    }

    @Test
    @DisplayName("apagar() remove pareamento.enc e chave.bin (desparear local); config.json fica")
    void apagar(@TempDir Path tmp) throws Exception {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("agente"));
        CofreCredencial cofre = new CofreCredencial(dirs);
        cofre.gravar(exemplo());
        Files.writeString(dirs.config(), "{}");
        cofre.apagar();
        assertThat(Files.exists(dirs.pareamento())).isFalse();
        assertThat(Files.exists(dirs.chave())).isFalse();
        assertThat(Files.exists(dirs.config())).isTrue();
        assertThat(cofre.ler()).isEmpty();
        cofre.apagar(); // idempotente
    }
}
