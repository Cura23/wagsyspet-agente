package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VerificadorAssinaturaRelease;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Properties;

import static br.com.wagner.wagsyspet.agente.core.atualizacao.VerificadorAtualizacao.Avaliacao.Resultado;
import static org.assertj.core.api.Assertions.assertThat;

/** A cadeia de verificação do self-update, na ordem fixa: tamanho → assinatura (ANTES de parsear) → formato → kid → versão → artefato da plataforma. */
@DisplayName("VerificadorAtualizacao — assinatura antes do parse, kid embutido, versão estritamente maior, artefato do SO")
class VerificadorAtualizacaoTest {

    private KeyPair atual, reserva, impostor;
    private ChavesRelease chaves;

    @BeforeEach
    void chaves() {
        atual = ChavesTicket.gerar(); reserva = ChavesTicket.gerar(); impostor = ChavesTicket.gerar();
        Properties p = new Properties();
        p.setProperty("release.chave.publica", ChavesTicket.exportarPublica(atual.getPublic()));
        p.setProperty("release.kid", VerificadorAssinaturaRelease.kid(atual.getPublic()));
        p.setProperty("release.chave.publica.reserva", ChavesTicket.exportarPublica(reserva.getPublic()));
        p.setProperty("release.kid.reserva", VerificadorAssinaturaRelease.kid(reserva.getPublic()));
        chaves = ChavesRelease.de(p);
    }

    static byte[] assinar(KeyPair par, byte[] dados) throws Exception {
        Signature s = Signature.getInstance("Ed25519"); s.initSign(par.getPrivate()); s.update(dados); return s.sign();
    }

    static byte[] manifesto(String versao, String kid, String... chavesArtefato) {
        StringBuilder sb = new StringBuilder("{\"formato\":1,\"versao\":\"" + versao + "\",\"protocolo\":1,\"publicadoEm\":\"2026-09-10T00:00:00Z\",\"kid\":\"" + kid + "\",\"artefatos\":{");
        for (int i = 0; i < chavesArtefato.length; i++) {
            if (i > 0) sb.append(',');
            sb.append("\"").append(chavesArtefato[i]).append("\":{\"arquivo\":\"AgroEase-Agente-Impressao-").append(versao).append("-x.bin\",\"url\":\"https://github.com/Cura23/wagsyspet-agente/releases/download/v").append(versao).append("/a.bin\",\"sha256\":\"").append("ab".repeat(32)).append("\",\"tamanho\":10}");
        }
        return sb.append("}}").toString().getBytes(StandardCharsets.UTF_8);
    }

    private VerificadorAtualizacao verificador(String versaoAtual) {
        return new VerificadorAtualizacao(chaves, versaoAtual, "Linux", "amd64", ManifestoRelease.FormatoInstalado.INSTALADOR);
    }

    @Test
    @DisplayName("versão maior, assinada pela chave atual, com artefato do meu SO → DISPONIVEL com o artefato certo")
    void disponivel() throws Exception {
        byte[] m = manifesto("1.1.0", chaves.kidAtual(), "windows", "linux", "linux-tar");
        VerificadorAtualizacao.Avaliacao a = verificador("1.0.0").avaliar(m, assinar(atual, m));
        assertThat(a.resultado()).isEqualTo(Resultado.DISPONIVEL);
        assertThat(a.artefato()).isPresent();
        assertThat(a.artefato().get().arquivo()).contains("1.1.0");
        assertThat(a.manifesto()).isPresent();
        assertThat(a.manifesto().get().versao()).isEqualTo("1.1.0");
    }

    @Test
    @DisplayName("assinada pela RESERVA (rotação) também vale; assinada por impostor → RECUSADA ASSINATURA sem nem parsear")
    void reservaEImpostor() throws Exception {
        String kidReserva = VerificadorAssinaturaRelease.kid(reserva.getPublic());
        byte[] m = manifesto("1.1.0", kidReserva, "linux");
        assertThat(verificador("1.0.0").avaliar(m, assinar(reserva, m)).resultado()).isEqualTo(Resultado.DISPONIVEL);
        VerificadorAtualizacao.Avaliacao ruim = verificador("1.0.0").avaliar(m, assinar(impostor, m));
        assertThat(ruim.resultado()).isEqualTo(Resultado.RECUSADA);
        assertThat(ruim.motivo()).isEqualTo(VerificadorAtualizacao.Motivo.ASSINATURA);
        assertThat(ruim.manifesto()).as("não parseia o que não está assinado").isEmpty();
    }

    @Test
    @DisplayName("kid do JSON diferente da chave que assinou → RECUSADA KID (manifesto remendado); kid desconhecido idem")
    void kid() throws Exception {
        byte[] m = manifesto("1.1.0", VerificadorAssinaturaRelease.kid(reserva.getPublic()), "linux");
        VerificadorAtualizacao.Avaliacao a = verificador("1.0.0").avaliar(m, assinar(atual, m)); // assinou a atual, diz reserva
        assertThat(a.resultado()).isEqualTo(Resultado.RECUSADA);
        assertThat(a.motivo()).isEqualTo(VerificadorAtualizacao.Motivo.KID);
        byte[] m2 = manifesto("1.1.0", "0000000000000000", "linux");
        assertThat(verificador("1.0.0").avaliar(m2, assinar(atual, m2)).motivo()).isEqualTo(VerificadorAtualizacao.Motivo.KID);
    }

    @Test
    @DisplayName("versão igual, menor ou mesma base com sufixo → ATUALIZADA (nada a fazer); manifesto inválido → RECUSADA MANIFESTO; tamanho > 64 KiB → RECUSADA TAMANHO antes de tudo")
    void versoesEInvalidos() throws Exception {
        for (String v : new String[] {"1.0.0", "0.9.9", "1.0.0-rc2"}) {
            byte[] m = manifesto(v, chaves.kidAtual(), "linux");
            assertThat(verificador("1.0.0").avaliar(m, assinar(atual, m)).resultado()).as(v).isEqualTo(Resultado.ATUALIZADA);
        }
        byte[] m = manifesto("1.1.0", chaves.kidAtual(), "linux");
        assertThat(verificador("1.0.0-SNAPSHOT").avaliar(m, assinar(atual, m)).resultado()).as("SNAPSHOT conta como 1.0.0").isEqualTo(Resultado.DISPONIVEL);
        byte[] quebrado = "{\"formato\":1".getBytes(StandardCharsets.UTF_8);
        VerificadorAtualizacao.Avaliacao inv = verificador("1.0.0").avaliar(quebrado, assinar(atual, quebrado));
        assertThat(inv.resultado()).isEqualTo(Resultado.RECUSADA);
        assertThat(inv.motivo()).isEqualTo(VerificadorAtualizacao.Motivo.MANIFESTO);
        byte[] grande = new byte[70_000];
        assertThat(verificador("1.0.0").avaliar(grande, new byte[64]).motivo()).isEqualTo(VerificadorAtualizacao.Motivo.TAMANHO);
    }

    @Test
    @DisplayName("manifesto válido sem artefato para o meu SO/formato → RECUSADA SEM_ARTEFATO (ex.: Linux tar quando só há .deb)")
    void semArtefato() throws Exception {
        byte[] m = manifesto("1.1.0", chaves.kidAtual(), "windows", "linux");
        VerificadorAtualizacao tar = new VerificadorAtualizacao(chaves, "1.0.0", "Linux", "amd64", ManifestoRelease.FormatoInstalado.APP_IMAGE);
        VerificadorAtualizacao.Avaliacao a = tar.avaliar(m, assinar(atual, m));
        assertThat(a.resultado()).isEqualTo(Resultado.RECUSADA);
        assertThat(a.motivo()).isEqualTo(VerificadorAtualizacao.Motivo.SEM_ARTEFATO);
        assertThat(a.manifesto()).as("o manifesto em si é válido e fica disponível para o aviso").isPresent();
    }
}
