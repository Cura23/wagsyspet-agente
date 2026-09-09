package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.core.pareamento.Pareamento;
import br.com.wagner.wagsyspet.agente.protocolo.ProtocoloVersao;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.VerificadorTicket;

import java.time.Clock;
import java.time.Duration;
import java.util.Set;

/**
 * Monta o {@link ServidorAgente} de PRODUÇÃO a partir do pareamento gravado (plano F3 D16): identidade do
 * {@code hello_ok} = agenteId do backend; 3ª barreira = {@link VerificadorTicket} com a chave pública, loja e agenteId
 * pareados; Origins = as do pareamento (nunca a lista de dev); impressora deste computador em {@code config.json}.
 * Não pareado ⇒ não há servidor (quem chama decide o que fazer — CLI/bandeja).
 */
public final class MontadorServidor {

    /** Relógio do caixa pode estar adiantado alguns minutos; o backend emite tickets de 10 min. */
    public static final Duration TOLERANCIA_RELOGIO = Duration.ofMinutes(5);

    private MontadorServidor() {
    }

    public static ServidorAgente montar(Pareamento p, String versao, DiretoriosDoAgente dirs, int porta, ServidorAgente.Prazos prazos) {
        return montar(p, versao, dirs, porta, prazos, PortaImpressao.real());
    }

    public static ServidorAgente montar(Pareamento p, String versao, DiretoriosDoAgente dirs, int porta, ServidorAgente.Prazos prazos,
                                        PortaImpressao impressao) {
        return montar(p, versao, porta, prazos, impressao, new ConfiguracaoLocalArquivo(dirs.config()));
    }

    /**
     * Variante com a {@link ConfiguracaoLocal} JÁ existente do processo (a mesma que a bandeja/janela usa): uma verdade só para
     * a impressora selecionada — adversarial F3 A1 (duas instâncias em cache = PWA via {@code selecionada: null} logo após o
     * lojista escolher na bandeja).
     */
    public static ServidorAgente montar(Pareamento p, String versao, int porta, ServidorAgente.Prazos prazos,
                                        PortaImpressao impressao, ConfiguracaoLocal config) {
        VerificadorTicket verificador = new VerificadorTicket(p.chavePublica(), p.lojaId(), p.agenteId(), Clock.systemUTC(), TOLERANCIA_RELOGIO);
        InfoAgente info = new InfoAgente(versao, ProtocoloVersao.ATUAL, p.agenteId());
        return new ServidorAgente(porta, Set.copyOf(p.origensPermitidas()),
                new ServidorAgente.Dependencias(info, verificador, impressao, config), prazos);
    }
}
