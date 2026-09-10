package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VerificadorAssinaturaRelease;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Orquestração pura do self-update (sem SO): verificar/baixar → preparar plano → sentinela de boot → confirmar ou reverter. */
@DisplayName("GerenteAtualizacao — verifica e baixa uma vez, prepara o plano, sentinela de boot confirma ou reverte")
class GerenteAtualizacaoTest {

    private HttpServer servidor;
    private String base;
    private KeyPair par;
    private ChavesRelease chaves;
    private byte[] json, sig;
    private final byte[] instalador = "instalador-falso-".repeat(100).getBytes(StandardCharsets.UTF_8);
    private final AtomicInteger downloadsInstalador = new AtomicInteger();
    private Instant agora = Instant.parse("2026-09-10T12:00:00Z");
    private final Clock relogio = new Clock() {
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return agora; }
    };

    @BeforeEach
    void subir() throws Exception {
        par = ChavesTicket.gerar();
        Properties p = new Properties();
        p.setProperty("release.chave.publica", ChavesTicket.exportarPublica(par.getPublic()));
        p.setProperty("release.kid", VerificadorAssinaturaRelease.kid(par.getPublic()));
        chaves = ChavesRelease.de(p);
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/latest.json", ex -> { ex.getResponseHeaders().add("ETag", "\"e1\""); if ("\"e1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) { ex.sendResponseHeaders(304, -1); ex.close(); return; } ex.sendResponseHeaders(200, json.length); try (OutputStream o = ex.getResponseBody()) { o.write(json); } });
        servidor.createContext("/latest.json.sig", ex -> { ex.sendResponseHeaders(200, sig.length); try (OutputStream o = ex.getResponseBody()) { o.write(sig); } });
        servidor.createContext("/i.deb", ex -> { downloadsInstalador.incrementAndGet(); ex.sendResponseHeaders(200, instalador.length); try (OutputStream o = ex.getResponseBody()) { o.write(instalador); } });
        servidor.start();
        base = "http://127.0.0.1:" + servidor.getAddress().getPort();
        publicar("1.1.0");
    }

    @AfterEach
    void derrubar() { servidor.stop(0); }

    private void publicar(String versao) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instalador));
        json = ("{\"formato\":1,\"versao\":\"" + versao + "\",\"protocolo\":1,\"kid\":\"" + chaves.kidAtual() + "\",\"artefatos\":{\"linux\":{\"arquivo\":\"AgroEase-Agente-Impressao-" + versao + "-linux-x64.deb\",\"url\":\"" + base + "/i.deb\",\"sha256\":\"" + sha + "\",\"tamanho\":" + instalador.length + "}}}").getBytes(StandardCharsets.UTF_8);
        Signature s = Signature.getInstance("Ed25519"); s.initSign(par.getPrivate()); s.update(json); sig = s.sign();
    }

    private GerenteAtualizacao gerente(Path tmp, String versaoAtual) {
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp);
        ClienteRelease cliente = new ClienteRelease(URI.create(base + "/latest.json"), Duration.ofSeconds(5), versaoAtual);
        VerificadorAtualizacao verificador = new VerificadorAtualizacao(chaves, versaoAtual, "Linux", "amd64", ManifestoRelease.FormatoInstalado.INSTALADOR);
        return new GerenteAtualizacao(dirs, new EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")), cliente, verificador, relogio);
    }

    @Test
    @DisplayName("versão nova → baixa UMA vez (sha conferido) e registra artefatoBaixado; 2ª verificação usa o ETag (304) e não rebaixa")
    void verificaEBaixaUmaVez(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao g = gerente(tmp, "1.0.0");
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        assertThat(g.versaoDisponivel()).contains("1.1.0");
        EstadoAtualizacao.Estado e = g.estado().ler();
        assertThat(e.artefatoBaixado()).isPresent();
        assertThat(Files.readAllBytes(Path.of(e.artefatoBaixado().get().caminho()))).isEqualTo(instalador);
        assertThat(e.etag()).contains("\"e1\"");
        assertThat(e.ultimaVerificacao()).contains(agora);
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        assertThat(downloadsInstalador.get()).as("304 → nada rebaixado").isEqualTo(1);
    }

    @Test
    @DisplayName("já na versão publicada → ATUALIZADO, nada baixado; servidor fora → INDISPONIVEL sem lançar")
    void atualizadoEIndisponivel(@TempDir Path tmp) throws Exception {
        assertThat(gerente(tmp, "1.1.0").verificar()).isEqualTo(GerenteAtualizacao.Situacao.ATUALIZADO);
        assertThat(downloadsInstalador.get()).isZero();
        servidor.stop(0);
        assertThat(gerente(tmp, "1.0.0").verificar()).isEqualTo(GerenteAtualizacao.Situacao.INDISPONIVEL);
    }

    @Test
    @DisplayName("prepararAplicacao: grava plano.json (versões, artefato, formato, launcher) e estado emAplicacao com tentativasBoot=0; podeAplicar exige artefato baixado E ociosidade mínima")
    void prepararAplicacao(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao g = gerente(tmp, "1.0.0");
        assertThat(g.podeAplicar(true, Duration.ofMinutes(10))).as("sem artefato baixado").isFalse();
        g.verificar();
        assertThat(g.podeAplicar(false, Duration.ofMinutes(10))).as("não ocioso").isFalse();
        assertThat(g.podeAplicar(true, Duration.ofMinutes(4))).as("ocioso há menos que o mínimo").isFalse();
        assertThat(g.podeAplicar(true, Duration.ofMinutes(5))).isTrue();

        Path plano = g.prepararAplicacao("1.0.0", Optional.of(Path.of("/opt/agroease-agente-impressao/bin/AgroEase-Agente-Impressao")), ManifestoRelease.FormatoInstalado.INSTALADOR);
        assertThat(plano).exists();
        PlanoAtualizacao lido = PlanoAtualizacao.ler(plano);
        assertThat(lido.versaoNova()).isEqualTo("1.1.0");
        assertThat(lido.versaoAnterior()).isEqualTo("1.0.0");
        assertThat(lido.artefato()).endsWith("AgroEase-Agente-Impressao-1.1.0-linux-x64.deb");
        assertThat(lido.formato()).isEqualTo(ManifestoRelease.FormatoInstalado.INSTALADOR);
        assertThat(lido.launcherAtual().map(Path::of)).as("comparar como Path: no Windows a string vira \\").contains(Path.of("/opt/agroease-agente-impressao/bin/AgroEase-Agente-Impressao"));
        assertThat(lido.dirDados()).isEqualTo(tmp.toString());
        EstadoAtualizacao.Estado e = g.estado().ler();
        assertThat(e.emAplicacao()).isPresent();
        assertThat(e.emAplicacao().get().versaoNova()).isEqualTo("1.1.0");
        assertThat(e.tentativasBoot()).isZero();
    }

    @Test
    @DisplayName("sentinela de boot: sem emAplicacao → SEGUIR; 1º boot da versão nova → AGUARDAR_CONFIRMACAO (tentativas=1); confirmar() na versão nova → confirmada e artefato apagado")
    void bootConfirma(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao velho = gerente(tmp, "1.0.0");
        assertThat(velho.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.SEGUIR);
        velho.verificar();
        velho.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);
        Path artefato = Path.of(velho.estado().ler().artefatoBaixado().get().caminho());

        GerenteAtualizacao novo = gerente(tmp, "1.1.0");
        assertThat(novo.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.AGUARDAR_CONFIRMACAO);
        assertThat(novo.estado().ler().tentativasBoot()).isEqualTo(1);
        novo.confirmar();
        EstadoAtualizacao.Estado e = novo.estado().ler();
        assertThat(e.emAplicacao()).isEmpty();
        assertThat(e.confirmadaEm()).contains(agora);
        assertThat(e.artefatoBaixado()).isEmpty();
        assertThat(Files.exists(artefato)).as("instalador baixado é apagado após confirmar").isFalse();
        assertThat(novo.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.SEGUIR);
    }

    @Test
    @DisplayName("2º boot sem confirmação → REVERTER; marcarRevertida grava recusada (24 h) e limpa emAplicacao; versão recusada não é rebaixada")
    void bootReverte(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao velho = gerente(tmp, "1.0.0");
        velho.verificar();
        velho.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);
        GerenteAtualizacao novo = gerente(tmp, "1.1.0");
        assertThat(novo.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.AGUARDAR_CONFIRMACAO); // crashou antes de confirmar…
        assertThat(novo.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.REVERTER);            // …e o supervisor relançou
        EstadoAtualizacao.EmAplicacao ap = novo.emAplicacao().orElseThrow();
        novo.marcarRevertida(ap, "não escutou em 60 s");
        EstadoAtualizacao.Estado e = novo.estado().ler();
        assertThat(e.emAplicacao()).isEmpty();
        assertThat(e.recusada()).isPresent();
        assertThat(e.recusada().get().versao()).isEqualTo("1.1.0");
        assertThat(e.recusada().get().ate()).isEqualTo(agora.plus(Duration.ofHours(24)));

        // o agente antigo volta (o instalador reverteu): a 1.1.0 está recusada → não baixa de novo até passar 24 h
        downloadsInstalador.set(0);
        GerenteAtualizacao deNovo = gerente(tmp, "1.0.0");
        assertThat(deNovo.verificar()).isEqualTo(GerenteAtualizacao.Situacao.ADIADO);
        assertThat(downloadsInstalador.get()).isZero();
        agora = agora.plus(Duration.ofHours(25));
        assertThat(deNovo.verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
    }

    @Test
    @DisplayName("agente ANTIGO sobe com emAplicacao pendente de outra versão (o atualizador não aplicou) → confirmar() marca recusada em vez de 'confirmada'")
    void antigoVoltaSemAplicar(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao velho = gerente(tmp, "1.0.0");
        velho.verificar();
        velho.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);
        GerenteAtualizacao aindaVelho = gerente(tmp, "1.0.0");
        assertThat(aindaVelho.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.AGUARDAR_CONFIRMACAO);
        aindaVelho.confirmar();
        EstadoAtualizacao.Estado e = aindaVelho.estado().ler();
        assertThat(e.confirmadaEm()).isEmpty();
        assertThat(e.recusada()).map(EstadoAtualizacao.Recusada::versao).contains("1.1.0");
        assertThat(e.emAplicacao()).isEmpty();
    }
}
