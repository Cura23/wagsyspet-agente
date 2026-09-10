package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.app.AplicadorAtualizacao;
import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno;
import br.com.wagner.wagsyspet.agente.core.atualizacao.EstadoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.PlanoAtualizacao;
import br.com.wagner.wagsyspet.agente.core.atualizacao.Reversor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Linux com {@code .deb} em {@code /opt} (root): o agente NUNCA pede senha sozinho — um diálogo do pkexec de madrugada sem ninguém é
 * falha perpétua (plano F6 D1). Só por clique ({@link PlanoAtualizacao.Gatilho#MANUAL}) roda {@code pkexec dpkg -i}; 126/127 = o
 * lojista cancelou ou não há agente de autenticação. Sem reversão (o .deb anterior não fica guardado; o dpkg mantém o pacote íntegro
 * quando falha). Quem quer atualização silenciosa no Linux instala o {@code tar.gz} por usuário.
 */
public final class InstaladorDpkgAssistido implements AplicadorAtualizacao.Instalador, Reversor {

    private static final Logger log = LoggerFactory.getLogger(InstaladorDpkgAssistido.class);

    private final ComandoExterno cmd;

    public InstaladorDpkgAssistido(ComandoExterno cmd) {
        this.cmd = cmd;
    }

    @Override
    public Resultado aplicar(PlanoAtualizacao plano) {
        if (plano.gatilho() != PlanoAtualizacao.Gatilho.MANUAL) {
            return new Resultado(false, "o pacote .deb precisa da sua senha de administrador: use \"Atualizar…\" na janela do agente "
                    + "(ou instale a versão por usuário .tar.gz, que se atualiza sozinha)", Optional.empty());
        }
        try {
            ComandoExterno.Saida s = cmd.executar(List.of("pkexec", "--disable-internal-agent", "dpkg", "-i", plano.artefato()));
            if (s.exit() == 0) {
                return new Resultado(true, "dpkg -i concluído", plano.launcherAtual().map(Path::of));
            }
            if (s.exit() == 126 || s.exit() == 127) {
                return new Resultado(false, "instalação cancelada (senha não informada ou sem agente de autenticação); tente de novo pela janela, ou: sudo dpkg -i " + plano.artefato(), Optional.empty());
            }
            return new Resultado(false, "dpkg saiu com " + s.exit() + ": " + s.texto(), Optional.empty());
        } catch (IOException e) {
            return new Resultado(false, "pkexec/dpkg não executou: " + e.getMessage(), Optional.empty());
        }
    }

    @Override
    public boolean reverter(EstadoAtualizacao.EmAplicacao ap) {
        log.warn("Reversão de .deb não é automática (pacote anterior não é guardado); a versão {} fica recusada", ap.versaoNova());
        return false;
    }
}
