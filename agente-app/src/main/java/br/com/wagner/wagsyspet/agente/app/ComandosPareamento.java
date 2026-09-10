package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.core.ConfiguracaoLocalArquivo;
import br.com.wagner.wagsyspet.agente.core.pareamento.ClientePareamento;
import br.com.wagner.wagsyspet.agente.core.pareamento.CofreCredencial;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.core.pareamento.Pareamento;
import br.com.wagner.wagsyspet.agente.core.pareamento.PareamentoException;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * Comandos de pareamento do binário (plano F3 L2): {@code --parear <codigo> [--backend-url <url>]}, {@code --desparear},
 * {@code --status}. Sem tela: servem para o suporte por SSH/CMD e são o caminho de 1ª classe onde não há bandeja (GNOME).
 * A bandeja/janela (L4) chama as mesmas funções.
 */
final class ComandosPareamento {

    static final String ENV_BACKEND = "AGROEASE_BACKEND_URL";
    static final String PROP_BACKEND = "agente.backend";
    static final int SAIDA_OK = 0;
    static final int SAIDA_FALHA = 2;

    private final PrintStream out;
    private final PrintStream err;
    private final DiretoriosDoAgente dirs;
    private final CofreCredencial cofre;
    private final String versao;

    ComandosPareamento(PrintStream out, PrintStream err, DiretoriosDoAgente dirs, String versao) {
        this.out = out;
        this.err = err;
        this.dirs = dirs;
        this.cofre = new CofreCredencial(dirs);
        this.versao = versao;
    }

    /** URL do backend: {@code --backend-url} → env → -D → {@code agente.properties} embutido. */
    static String urlBackend(String daLinhaDeComando, Map<String, String> env, Properties props) {
        if (naoBranco(daLinhaDeComando)) {
            return daLinhaDeComando.trim();
        }
        if (naoBranco(env.get(ENV_BACKEND))) {
            return env.get(ENV_BACKEND).trim();
        }
        if (naoBranco(props.getProperty(PROP_BACKEND))) {
            return props.getProperty(PROP_BACKEND).trim();
        }
        return urlEmbutida();
    }

    static String urlEmbutida() {
        Properties p = new Properties();
        try (InputStream in = ComandosPareamento.class.getResourceAsStream("/agente.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("agente.properties ilegível", e);
        }
        String url = p.getProperty("backend.url");
        if (!naoBranco(url)) {
            throw new IllegalStateException("agente.properties sem backend.url — build inválido");
        }
        return url.trim();
    }

    private static boolean naoBranco(String s) {
        return s != null && !s.isBlank();
    }

    /** {@code --parear <codigo> [--backend-url <url>]} */
    int parear(String codigo, String backendUrlOpcional) {
        String url;
        try {
            url = urlBackend(backendUrlOpcional, System.getenv(), System.getProperties());
        } catch (IllegalStateException e) {
            err.println(e.getMessage());
            return SAIDA_FALHA;
        }
        Optional<Pareamento> atual = cofre.ler();
        atual.ifPresent(p -> out.println("Este computador já está pareado com a loja " + p.lojaId() + "; parear de novo substitui o pareamento."));
        try {
            ClientePareamento cliente = ClientePareamento.padrao(url, versao);
            Pareamento p = cliente.parear(codigo);
            cofre.gravar(p);
            out.println("Pareado com a loja " + p.lojaId() + " em " + p.backendUrl() + ".");
            cliente.ultimoDesvioDeRelogio().filter(d -> d.abs().compareTo(ClientePareamento.DESVIO_RELOGIO_ALERTA) > 0).ifPresent(d ->
                    out.println("ATENÇÃO: o relógio deste computador está " + ClientePareamento.descreverDesvio(d)
                            + " em relação ao servidor. Acerte a data/hora, senão a impressão pode ser recusada."));
            out.println("Identidade deste caixa: " + p.agenteId() + " (chave do ticket " + p.fingerprintChave() + ").");
            ComandosAutostart.padrao(out, err).ativarAposPareamento().ifPresent(out::println);
            if (agenteAberto()) {
                out.println("O agente já está aberto neste computador e vai aplicar o novo pareamento em alguns segundos.");
            } else {
                out.println("Abra o agente (menu do sistema) para ele começar a atender o PDV.");
            }
            out.println("Próximo passo: abra o PDV e escolha a impressora deste computador em Configurações → Geral → Impressão de Cupom.");
            return SAIDA_OK;
        } catch (PareamentoException e) {
            err.println("Não foi possível parear: " + e.getMessage());
            err.println(e.codigoContinuaValido()
                    ? "O código continua válido — resolva o problema acima e repita o comando com o MESMO código."
                    : "Gere um novo código de pareamento no painel da loja e tente de novo.");
            return SAIDA_FALHA;
        } catch (IllegalArgumentException e) {
            err.println(e.getMessage());
            return SAIDA_FALHA;
        } catch (IOException e) {
            err.println("Pareou no servidor, mas não consegui gravar em " + dirs.raiz() + ": " + e.getMessage());
            err.println("Confira as permissões da pasta; será preciso gerar OUTRO código (este já foi usado).");
            return SAIDA_FALHA;
        }
    }

    /** {@code --desparear}: apaga só o pareamento local (o backend não tem endpoint para o agente se revogar; o dono revoga no painel). */
    int desparear() {
        try {
            boolean havia = cofre.ler().isPresent();
            cofre.apagar();
            out.println(havia ? "Pareamento removido deste computador. Para voltar a imprimir pelo agente, pareie de novo."
                    : "Este computador não estava pareado.");
            if (havia && agenteAberto()) {
                out.println("O agente aberto vai parar de atender o PDV em alguns segundos.");
            }
            out.println("Se este caixa não vai mais ser usado, revogue-o também no painel da loja (Configurações → Geral → Impressão de Cupom).");
            return SAIDA_OK;
        } catch (IOException e) {
            err.println("Não foi possível apagar o pareamento em " + dirs.raiz() + ": " + e.getMessage());
            return SAIDA_FALHA;
        }
    }

    /** Lock de instância tomado por outro processo = agente de desktop aberto (ele relê o cofre a cada 5 s). */
    private boolean agenteAberto() {
        try {
            Optional<TravaDeInstancia> t = TravaDeInstancia.tentar(dirs.lock());
            if (t.isEmpty()) {
                return true;
            }
            t.get().close();
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /** {@code --status}: o que o suporte precisa saber, sem segredos. */
    int status() {
        out.println("Pasta de dados: " + dirs.raiz());
        Optional<Pareamento> p = cofre.ler();
        if (p.isEmpty()) {
            out.println("Pareamento: NÃO pareado. Use --parear <codigo> (o código é gerado pelo responsável da loja no painel).");
        } else {
            Pareamento x = p.get();
            out.println("Pareamento: loja " + x.lojaId() + " · agenteId " + x.agenteId() + " · desde " + x.pareadoEm());
            out.println("Backend: " + x.backendUrl() + " · chave do ticket " + x.fingerprintChave()
                    + " · origins permitidas " + x.origensPermitidas());
        }
        out.println("Impressora deste computador: " + new ConfiguracaoLocalArquivo(dirs.config()).impressoraSelecionada().orElse("(nenhuma escolhida)"));
        out.println("Iniciar com o sistema: " + ComandosAutostart.padrao(out, err).linhaStatus());
        out.println("Atualização automática: " + br.com.wagner.wagsyspet.agente.core.atualizacao.GerenteAtualizacao.resumo(
                new br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao(dirs.atualizacao().resolve("estado.json")), java.time.Clock.systemUTC()));
        return SAIDA_OK;
    }
}
