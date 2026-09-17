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

    @Test
    @DisplayName("com GuardaAnterior: quando a atualização fica BAIXADA a guarda é acionada com a versão atual (antes de aplicar); guarda falhando não impede a atualização; ATUALIZADO não aciona")
    void guardaAnteriorAcionada(@TempDir Path tmp) throws Exception {
        java.util.List<String> pedidas = new java.util.ArrayList<>();
        GerenteAtualizacao g = gerente(tmp, "1.0.0");
        java.util.List<Boolean> dentroDoMonitor = new java.util.ArrayList<>();
        g.guardaAnterior(v -> { pedidas.add(v); dentroDoMonitor.add(Thread.holdsLock(g)); if (pedidas.size() == 1) { throw new IllegalStateException("rede fora"); } return Optional.of(tmp.resolve("anterior").resolve("x.exe")); });
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        assertThat(pedidas).containsExactly("1.0.0");
        assertThat(g.verificar()).as("304/já baixado: garante de novo (barato quando já está lá)").isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        assertThat(pedidas).containsExactly("1.0.0", "1.0.0");
        assertThat(dentroDoMonitor).as("a guarda baixa ~70 MB: FORA do monitor do gerente, senão 'Atualizar agora'/parear/confirmar ficam presos nela (adversarial L3)").containsOnly(false);
        publicar("1.0.0");
        GerenteAtualizacao atual = gerente(tmp.resolve("b"), "1.0.0");
        atual.guardaAnterior(v -> { pedidas.add("NAO"); return Optional.empty(); });
        assertThat(atual.verificar()).isEqualTo(GerenteAtualizacao.Situacao.ATUALIZADO);
        assertThat(pedidas).doesNotContain("NAO");
    }

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
    @DisplayName("3º boot sem confirmação → REVERTER (o 2º ainda tenta: um 1º boot SAUDÁVEL interrompido antes dos 60 s — desligaram o PC ao fechar a loja — não pode reverter uma versão boa na manhã seguinte); marcarRevertida grava recusada (24 h) e limpa emAplicacao; versão recusada não é rebaixada")
    void bootReverte(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao velho = gerente(tmp, "1.0.0");
        velho.verificar();
        velho.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);
        GerenteAtualizacao novo = gerente(tmp, "1.1.0");
        assertThat(novo.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.AGUARDAR_CONFIRMACAO); // 1º boot: caiu (ou desligaram o PC) antes de confirmar…
        assertThat(novo.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.AGUARDAR_CONFIRMACAO); // …o 2º boot AINDA tenta confirmar (keepalive de 1 min: custa 1 min a mais se for ruim de verdade)…
        assertThat(novo.avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.REVERTER);            // …só o 3º desiste
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
    @DisplayName("boots do binário ANTIGO com a troca pendente (ex.: o supervisor reabriu o agente no meio da instalação) NÃO contam como tentativa da versão nova: nunca mandam REVERTER nem gastam os boots dela (adversarial L3)")
    void bootDoAntigoNaoConta(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao velho = gerente(tmp, "1.0.0");
        velho.verificar();
        velho.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);
        for (int i = 0; i < 3; i++) {
            assertThat(gerente(tmp, "1.0.0").avaliarBoot()).isEqualTo(GerenteAtualizacao.DecisaoBoot.AGUARDAR_CONFIRMACAO);
        }
        assertThat(velho.estado().ler().tentativasBoot()).isZero();
        GerenteAtualizacao novo = gerente(tmp, "1.1.0");
        assertThat(novo.avaliarBoot()).as("1º boot DA NOVA").isEqualTo(GerenteAtualizacao.DecisaoBoot.AGUARDAR_CONFIRMACAO);
        assertThat(novo.estado().ler().tentativasBoot()).isEqualTo(1);
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

    // ---------- Fecho F6: freio das tentativas (D4 prometia "24 h / 3 tentativas"; o contador era gravado e NUNCA lido) ----------

    /** Uma rodada de falha completa: baixa, prepara, o instalador falha e a versão é recusada por 24 h. */
    private void falharUmaVez(Path tmp) throws Exception {
        GerenteAtualizacao g = gerente(tmp, "1.0.0");
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        g.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);
        g.marcarRevertida(g.emAplicacao().orElseThrow(), "msiexec 1603");
        agora = agora.plus(Duration.ofHours(25));
    }

    @Test
    @DisplayName("TETO: a MESMA versão que falhou 3 vezes aqui não é tentada de novo — nem depois de 24 h, nem de 30 dias (sem o teto era download + saída do agente + instalador TODO DIA, para sempre); versão MAIOR zera o teto")
    void tetoDeTentativas(@TempDir Path tmp) throws Exception {
        falharUmaVez(tmp);
        falharUmaVez(tmp);
        falharUmaVez(tmp);
        assertThat(gerente(tmp, "1.0.0").estado().ler().recusada().orElseThrow().tentativas()).isEqualTo(3);

        downloadsInstalador.set(0);
        GerenteAtualizacao g = gerente(tmp, "1.0.0");
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.ADIADO);
        agora = agora.plus(Duration.ofDays(30));
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.ADIADO);
        assertThat(downloadsInstalador.get()).as("esgotou: não baixa mais ESTA versão").isZero();
        assertThat(g.esgotada()).contains("1.1.0");
        assertThat(GerenteAtualizacao.resumo(g.estado(), relogio)).contains("1.1.0").contains("3 tentativas");

        publicar("1.2.0"); // o dono publicou a correção: versão MAIOR é outra história
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        assertThat(g.esgotada()).isEmpty();
    }

    @Test
    @DisplayName("o contador é POR VERSÃO nos dois caminhos de recusa: 2 falhas da 1.1.0 não contam contra a 1.2.0 (inclusive na recusa do confirmar(), que somava a de outra versão)")
    void tentativasNaoVazamEntreVersoes(@TempDir Path tmp) throws Exception {
        falharUmaVez(tmp);
        falharUmaVez(tmp);
        publicar("1.2.0");
        GerenteAtualizacao g = gerente(tmp, "1.0.0");
        assertThat(g.verificar()).isEqualTo(GerenteAtualizacao.Situacao.DISPONIVEL_BAIXADO);
        g.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);
        gerente(tmp, "1.0.0").confirmar(); // o agente ANTIGO subiu de novo: o atualizador não aplicou → recusa pela via do confirmar()
        EstadoAtualizacao.Recusada r = g.estado().ler().recusada().orElseThrow();
        assertThat(r.versao()).isEqualTo("1.2.0");
        assertThat(r.tentativas()).isEqualTo(1);
    }

    @Test
    @DisplayName("lançador do atualizador que FALHA (sem espaço para copiar o app-image, antivírus) conta como tentativa e entra no recuo de 24 h — antes só limpava o estado e o agente tentava de novo a cada ~5 min, derrubando as conexões do PDV a cada vez")
    void lancadorQueFalhaEntraNoRecuo(@TempDir Path tmp) throws Exception {
        GerenteAtualizacao g = gerente(tmp, "1.0.0");
        g.verificar();
        g.prepararAplicacao("1.0.0", Optional.empty(), ManifestoRelease.FormatoInstalado.INSTALADOR);

        g.abortarAplicacao("sem espaço em disco");

        EstadoAtualizacao.Estado e = g.estado().ler();
        assertThat(e.emAplicacao()).isEmpty();
        assertThat(e.recusada()).map(EstadoAtualizacao.Recusada::versao).contains("1.1.0");
        assertThat(g.podeAplicar(true, Duration.ofHours(1))).as("nada de tentar de novo em 5 min").isFalse();
        assertThat(gerente(tmp, "1.0.0").verificar()).isEqualTo(GerenteAtualizacao.Situacao.ADIADO);
    }
}
