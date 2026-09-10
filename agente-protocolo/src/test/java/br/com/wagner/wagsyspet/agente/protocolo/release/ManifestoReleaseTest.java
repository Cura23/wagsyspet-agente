package br.com.wagner.wagsyspet.agente.protocolo.release;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoInvalidoException.Motivo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Parser ESTRITO do latest.json (o que montar-latest.sh publica) — fixture = o latest.json REAL da v1.0.0. */
@DisplayName("ManifestoRelease — parser estrito do latest.json, aditivo em campos desconhecidos")
class ManifestoReleaseTest {

    static byte[] fixture(String nome) throws IOException {
        try (InputStream in = ManifestoReleaseTest.class.getResourceAsStream("/release/" + nome)) {
            assertThat(in).as("fixture " + nome).isNotNull();
            return in.readAllBytes();
        }
    }

    static String v1() throws IOException {
        return new String(fixture("latest-v1.0.0.json"), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("latest.json REAL da v1.0.0: formato 1, versão, protocolo, kid, 4 artefatos com url/sha/tamanho")
    void fixtureReal() throws Exception {
        ManifestoRelease m = ManifestoRelease.parse(fixture("latest-v1.0.0.json"));
        assertThat(m.formato()).isEqualTo(1);
        assertThat(m.versao()).isEqualTo("1.0.0");
        assertThat(m.protocolo()).isEqualTo(1);
        assertThat(m.kid()).isEqualTo("b36cf9634d29c4f7");
        assertThat(m.publicadoEm()).isEqualTo("2026-09-10T13:25:52Z");
        assertThat(m.artefatos()).containsOnlyKeys("windows", "linux", "macos-arm64", "macos-x64");
        ManifestoRelease.Artefato w = m.artefatos().get("windows");
        assertThat(w.arquivo()).isEqualTo("AgroEase-Agente-Impressao-1.0.0-windows-x64.exe");
        assertThat(w.url()).startsWith("https://github.com/Cura23/wagsyspet-agente/releases/download/v1.0.0/");
        assertThat(w.sha256()).isEqualTo("0d6675671d83d5dcbe5c0de98c68e659a27d32e543f5aa3741bb8cb366f89fa1");
        assertThat(w.tamanho()).isEqualTo(41625600L);
    }

    @Test
    @DisplayName("manifesto gerado pelo montar-latest.sh da F6 (com linux-tar) passa no parser e o Linux app-image acha o tar.gz")
    void fixtureComLinuxTar() throws Exception {
        ManifestoRelease m = ManifestoRelease.parse(fixture("latest-com-linux-tar.json"));
        assertThat(m.artefatos()).containsKeys("windows", "linux", "linux-tar", "macos-arm64", "macos-x64");
        assertThat(m.artefatoPara("Linux", "amd64", ManifestoRelease.FormatoInstalado.APP_IMAGE)).map(ManifestoRelease.Artefato::arquivo).contains("AgroEase-Agente-Impressao-1.1.0-linux-x64.tar.gz");
        assertThat(m.artefatoPara("Linux", "amd64", ManifestoRelease.FormatoInstalado.INSTALADOR)).map(ManifestoRelease.Artefato::arquivo).contains("AgroEase-Agente-Impressao-1.1.0-linux-x64.deb");
    }

    @Test
    @DisplayName("campos desconhecidos (raiz e artefato) são IGNORADOS — um agente antigo lê um manifesto novo")
    void aditivo() throws Exception {
        String json = v1().replace("\"formato\":1,", "\"formato\":1,\"novidade\":{\"x\":1},")
                .replace("\"tamanho\":41625600", "\"tamanho\":41625600,\"assinaturaCodigo\":\"abc\"");
        ManifestoRelease m = ManifestoRelease.parse(json.getBytes(StandardCharsets.UTF_8));
        assertThat(m.versao()).isEqualTo("1.0.0");
        assertThat(m.artefatos().get("windows").tamanho()).isEqualTo(41625600L);
    }

    @Test
    @DisplayName("formato ≠ 1 → FORMATO_NAO_SUPORTADO (agente antigo diante de um manifesto de formato futuro)")
    void formatoFuturo() throws IOException {
        String json = v1().replace("\"formato\":1", "\"formato\":2");
        assertThatThrownBy(() -> ManifestoRelease.parse(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ManifestoInvalidoException.class)
                .extracting(e -> ((ManifestoInvalidoException) e).motivo()).isEqualTo(Motivo.FORMATO_NAO_SUPORTADO);
    }

    @Test
    @DisplayName("cada campo obrigatório mutilado cai no motivo certo — nada passa 'meio válido'")
    void camposInvalidos() throws IOException {
        String v1 = v1();
        assertMotivo("{lixo", Motivo.JSON);
        assertMotivo("[]", Motivo.JSON);
        assertMotivo(v1.replace("\"versao\":\"1.0.0\"", "\"versao\":\"banana\""), Motivo.VERSAO);
        assertMotivo(v1.replace("\"versao\":\"1.0.0\",", ""), Motivo.VERSAO);
        assertMotivo(v1.replace("\"kid\":\"b36cf9634d29c4f7\"", "\"kid\":\"B36C\""), Motivo.KID);
        assertMotivo(v1.replace("\"protocolo\":1", "\"protocolo\":0"), Motivo.PROTOCOLO);
        assertMotivo(v1.replace("\"protocolo\":1", "\"protocolo\":\"1\""), Motivo.PROTOCOLO);
        // artefatos
        assertMotivo(v1.replace("windows-x64.exe\",\"url\"", "windows-x64.exe\",\"URL\""), Motivo.ARTEFATO);           // url ausente
        assertMotivo(v1.replace("\"url\":\"https://github.com/Cura23", "\"url\":\"http://github.com/Cura23"), Motivo.ARTEFATO); // http
        assertMotivo(v1.replace("\"arquivo\":\"AgroEase-Agente-Impressao-1.0.0-windows-x64.exe\"", "\"arquivo\":\"../x.exe\""), Motivo.ARTEFATO); // separador
        assertMotivo(v1.replace("0d6675671d83d5dcbe5c0de98c68e659a27d32e543f5aa3741bb8cb366f89fa1", "0d66"), Motivo.ARTEFATO); // sha curto
        assertMotivo(v1.replace("\"tamanho\":41625600", "\"tamanho\":0"), Motivo.ARTEFATO);
        assertMotivo(v1.replace("\"tamanho\":41625600", "\"tamanho\":\"41625600\""), Motivo.ARTEFATO);
        assertMotivo(v1.replace("\"artefatos\":{", "\"artefatos\":{\"win dows\":{\"arquivo\":\"a.exe\",\"url\":\"https://x/a.exe\",\"sha256\":\"" + "a".repeat(64) + "\",\"tamanho\":1},"), Motivo.ARTEFATO); // chave fora do padrão
        assertMotivo(v1.replaceAll("\"artefatos\":\\{.*\\}\\}$", "\"artefatos\":{}}"), Motivo.ARTEFATO);           // nenhum artefato
    }

    @Test
    @DisplayName("teto de 64 KiB: manifesto maior é recusado ANTES de parsear (proteção contra corpo hostil)")
    void teto() throws IOException {
        byte[] grande = (v1().replace("\"formato\":1,", "\"formato\":1,\"enchimento\":\"" + "x".repeat(70_000) + "\",")).getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> ManifestoRelease.parse(grande)).isInstanceOf(ManifestoInvalidoException.class)
                .extracting(e -> ((ManifestoInvalidoException) e).motivo()).isEqualTo(Motivo.TAMANHO);
    }

    @Test
    @DisplayName("chave do artefato por plataforma: windows só x64; linux deb × tar; macos por arch; desconhecido → vazio")
    void chavePorPlataforma() {
        assertThat(ManifestoRelease.chaveArtefato("Windows 11", "amd64", ManifestoRelease.FormatoInstalado.INSTALADOR)).contains("windows");
        assertThat(ManifestoRelease.chaveArtefato("Windows 11", "aarch64", ManifestoRelease.FormatoInstalado.INSTALADOR)).isEmpty();
        assertThat(ManifestoRelease.chaveArtefato("Linux", "amd64", ManifestoRelease.FormatoInstalado.INSTALADOR)).contains("linux");
        assertThat(ManifestoRelease.chaveArtefato("Linux", "amd64", ManifestoRelease.FormatoInstalado.APP_IMAGE)).contains("linux-tar");
        assertThat(ManifestoRelease.chaveArtefato("Mac OS X", "aarch64", ManifestoRelease.FormatoInstalado.INSTALADOR)).contains("macos-arm64");
        assertThat(ManifestoRelease.chaveArtefato("Mac OS X", "x86_64", ManifestoRelease.FormatoInstalado.INSTALADOR)).contains("macos-x64");
        assertThat(ManifestoRelease.chaveArtefato("FreeBSD", "amd64", ManifestoRelease.FormatoInstalado.INSTALADOR)).isEmpty();
    }

    private static void assertMotivo(String json, Motivo esperado) {
        assertThatThrownBy(() -> ManifestoRelease.parse(json.getBytes(StandardCharsets.UTF_8)))
                .as("json: " + (json.length() > 120 ? json.substring(0, 120) + "…" : json))
                .isInstanceOf(ManifestoInvalidoException.class)
                .extracting(e -> ((ManifestoInvalidoException) e).motivo()).isEqualTo(esperado);
    }
}
