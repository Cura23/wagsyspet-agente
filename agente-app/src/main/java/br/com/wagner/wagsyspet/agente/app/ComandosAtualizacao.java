package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.app.autostart.Autostart;
import br.com.wagner.wagsyspet.agente.core.atualizacao.ClienteRelease;
import br.com.wagner.wagsyspet.agente.core.atualizacao.VerificadorAtualizacao;
import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import java.time.Clock;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.LancadorAtualizador;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;

/**
 * {@code --verificar-atualizacao} (F6-L0): consulta o {@code latest.json} assinado e diz se há versão nova — dry-run para o suporte e
 * gancho do smoke do CI. NÃO baixa nem instala (isso é o desktop, L1/L2). Saída 0 = atualizado, {@value #SAIDA_DISPONIVEL} =
 * há versão nova, {@link Main#SAIDA_FALHA} = recusado/indisponível.
 *
 * <p>Fonte do manifesto: {@code -Dagente.release.manifesto.url} → {@code agente.properties}. Raiz de confiança: as chaves embutidas
 * ({@link ChavesRelease#embutidas()}), ou {@code -Dagente.release.chave.publica} para o CI instalar uma release falsa assinada com
 * par descartável (plano F6 D11) — sempre com WARN no log, porque quem controla a JVM local está fora do modelo de ameaça.</p>
 */
final class ComandosAtualizacao {

    private static final Logger log = LoggerFactory.getLogger(ComandosAtualizacao.class);
    static final int SAIDA_DISPONIVEL = 1;
    static final String PROP_MANIFESTO = "agente.release.manifesto.url";
    static final String PROP_CHAVE = "agente.release.chave.publica";
    static final Duration PRAZO = Duration.ofSeconds(30);

    private final PrintStream out;
    private final PrintStream err;
    private final String versaoAtual;
    private final URI manifesto;
    private final ChavesRelease chaves;
    private final ManifestoRelease.FormatoInstalado formato;

    ComandosAtualizacao(PrintStream out, PrintStream err, String versaoAtual, URI manifesto, ChavesRelease chaves, ManifestoRelease.FormatoInstalado formato) {
        this.out = out;
        this.err = err;
        this.versaoAtual = versaoAtual;
        this.manifesto = manifesto;
        this.chaves = chaves;
        this.formato = formato;
    }

    static ComandosAtualizacao padrao(PrintStream out, PrintStream err, String versaoAtual) {
        Properties props = System.getProperties();
        return new ComandosAtualizacao(out, err, versaoAtual, urlManifesto(props), chaves(props),
                formatoInstalado(Autostart.launcherDesteProcesso(), Path.of(System.getProperty("user.home", "."))));
    }

    int verificar() {
        out.println("Versão instalada: " + versaoAtual);
        out.println("Manifesto: " + manifesto);
        out.println("Chaves de release aceitas: " + chaves.todas().stream().map(c -> c.kid() + (c.reserva() ? " (reserva)" : "")).toList());
        ClienteRelease cliente = new ClienteRelease(manifesto, PRAZO, versaoAtual);
        Optional<ClienteRelease.ManifestoBaixado> baixado;
        try {
            baixado = cliente.buscarManifesto(null);
        } catch (ClienteRelease.ReleaseIndisponivelException e) {
            err.println("Release indisponível agora: " + e.getMessage());
            return Main.SAIDA_FALHA;
        }
        if (baixado.isEmpty()) {
            err.println("Release indisponível agora: resposta 304 sem ETag conhecido");
            return Main.SAIDA_FALHA;
        }
        VerificadorAtualizacao verificador = new VerificadorAtualizacao(chaves, versaoAtual, System.getProperty("os.name"), System.getProperty("os.arch"), formato);
        VerificadorAtualizacao.Avaliacao a = verificador.avaliar(baixado.get().json(), baixado.get().assinatura());
        switch (a.resultado()) {
            case RECUSADA -> {
                out.println("Manifesto RECUSADO: " + a.motivo() + (a.manifesto().map(m -> " (versão publicada " + m.versao() + ")").orElse("")));
                return Main.SAIDA_FALHA;
            }
            case ATUALIZADA -> {
                out.println("Agente atualizado: a versão publicada é " + a.manifesto().map(ManifestoRelease::versao).orElse("?") + ".");
                return 0;
            }
            default -> {
                ManifestoRelease m = a.manifesto().orElseThrow();
                ManifestoRelease.Artefato art = a.artefato().orElseThrow();
                out.println("Atualização DISPONÍVEL: " + m.versao() + (m.publicadoEm() != null ? " (publicada em " + m.publicadoEm() + ")" : ""));
                out.println("  arquivo: " + art.arquivo() + " (" + art.tamanho() + " bytes, sha256 " + art.sha256().substring(0, 12) + "…)");
                out.println("  formato desta instalação: " + formato.name().toLowerCase(Locale.ROOT).replace('_', '-'));
                out.println("  Nada foi baixado: o agente de desktop aplica quando estiver ocioso, ou use \"Atualizar agora\" na janela.");
                return SAIDA_DISPONIVEL;
            }
        }
    }

    /** O gerente do self-update com a configuração embutida (manifesto do GitHub, chaves atual+reserva, formato pelo launcher). */
    static GerenteAtualizacao gerentePadrao(DiretoriosDoAgente dirs, String versaoAtual) {
        Properties props = System.getProperties();
        URI manifesto = urlManifesto(props);
        ClienteRelease cliente = new ClienteRelease(manifesto, PRAZO, versaoAtual);
        VerificadorAtualizacao verificador = VerificadorAtualizacao.destaMaquina(chaves(props), versaoAtual,
                formatoInstalado(Autostart.launcherDesteProcesso(), Path.of(System.getProperty("user.home", "."))));
        return new GerenteAtualizacao(dirs, new EstadoAtualizacao(dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_ESTADO)), cliente, verificador, Clock.systemUTC());
    }

    /**
     * {@code --atualizar}: com o agente de desktop ABERTO, orienta a usar o botão (ele é quem sabe se o caixa está ocioso); fechado,
     * verifica, baixa e entrega ao atualizador na hora (uso: suporte e smoke do CI).
     */
    int atualizar(DiretoriosDoAgente dirs, LancadorAtualizador lancador) {
        try {
            Optional<TravaDeInstancia> t = TravaDeInstancia.tentar(dirs.lock());
            if (t.isEmpty()) {
                err.println("O agente está aberto neste computador: use \"Atualizar…\" na janela/bandeja dele (ele aplica quando o caixa estiver parado).");
                return Main.SAIDA_FALHA;
            }
            t.get().close();
        } catch (IOException e) {
            log.debug("lock: {}", e.toString());
        }
        GerenteAtualizacao g = new GerenteAtualizacao(dirs, new EstadoAtualizacao(dirs.atualizacao().resolve(GerenteAtualizacao.ARQUIVO_ESTADO)),
                new ClienteRelease(manifesto, PRAZO, versaoAtual),
                new VerificadorAtualizacao(chaves, versaoAtual, System.getProperty("os.name"), System.getProperty("os.arch"), formato), Clock.systemUTC());
        GerenteAtualizacao.Situacao s = g.verificar();
        out.println("Versão instalada: " + versaoAtual + " · situação: " + s);
        switch (s) {
            case ATUALIZADO -> {
                out.println("Nada a fazer: já é a versão publicada.");
                return 0;
            }
            case DISPONIVEL_BAIXADO -> {
                try {
                    Path plano = g.prepararAplicacao(versaoAtual, Autostart.launcherDesteProcesso(), formato, br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao.Gatilho.MANUAL);
                    lancador.lancar(plano);
                    out.println("Atualizador lançado para " + g.versaoDisponivel().orElse("?") + " (plano " + plano + ").");
                    return 0;
                } catch (IOException e) {
                    g.abortarAplicacao(e.toString());
                    err.println("Não consegui iniciar a atualização: " + e.getMessage());
                    return Main.SAIDA_FALHA;
                }
            }
            default -> {
                err.println("Atualização não aplicada agora (" + s + "). Estado: " + g.resumo());
                return Main.SAIDA_FALHA;
            }
        }
    }

    /** {@code -Dagente.release.manifesto.url} → {@code agente.properties} embutido. */
    static URI urlManifesto(Properties props) {
        String override = props.getProperty(PROP_MANIFESTO, "").trim();
        if (!override.isEmpty()) {
            return URI.create(override);
        }
        Properties p = new Properties();
        try (InputStream in = ComandosAtualizacao.class.getResourceAsStream("/agente.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("agente.properties ilegível", e);
        }
        String url = p.getProperty("release.manifesto.url", "").trim();
        if (url.isEmpty()) {
            throw new IllegalStateException("agente.properties sem release.manifesto.url — build inválido");
        }
        return URI.create(url);
    }

    /** Chaves embutidas, ou UMA chave de fora por {@code -Dagente.release.chave.publica} (CI/suporte) — com WARN. */
    static ChavesRelease chaves(Properties props) {
        String override = props.getProperty(PROP_CHAVE, "").trim();
        if (!override.isEmpty()) {
            ChavesRelease c = ChavesRelease.deUmaPublica(override);
            log.warn("Raiz de confiança de release SOBRESCRITA por -D{} (kid {}) — só para CI/suporte", PROP_CHAVE, c.kidAtual());
            return c;
        }
        return ChavesRelease.embutidas();
    }

    /**
     * Como este binário foi instalado, pelo caminho do launcher: dentro do {@code $HOME/.local} = app-image (tar.gz, atualizável
     * por troca de pasta); qualquer outro lugar ({@code /opt}, {@code Program Files}, {@code AppData\Local}, {@code /Applications}) ou
     * java cru = instalador do SO.
     */
    static ManifestoRelease.FormatoInstalado formatoInstalado(Optional<Path> launcher, Path home) {
        if (launcher.isEmpty()) {
            return ManifestoRelease.FormatoInstalado.INSTALADOR;
        }
        String l = launcher.get().toString().replace('\\', '/');
        String h = home.toString().replace('\\', '/');
        if (l.startsWith(h + "/.local/")) {
            return ManifestoRelease.FormatoInstalado.APP_IMAGE;
        }
        return ManifestoRelease.FormatoInstalado.INSTALADOR;
    }
}
