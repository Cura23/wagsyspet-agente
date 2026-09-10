package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoInvalidoException;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.VersaoSemantica;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * A cadeia de verificação do self-update, na ORDEM FIXA do plano F6 D3: tamanho → assinatura Ed25519 (ANTES de qualquer parse, com
 * qualquer chave embutida) → parse estrito → {@code kid} do JSON tem de ser o da chave que assinou (manifesto remendado) → versão
 * ESTRITAMENTE maior que a atual → artefato publicado para esta plataforma. Puro (sem I/O): quem baixa é o {@link ClienteRelease}.
 */
public final class VerificadorAtualizacao {

    private static final Logger log = LoggerFactory.getLogger(VerificadorAtualizacao.class);

    public enum Motivo { TAMANHO, ASSINATURA, MANIFESTO, KID, VERSAO, SEM_ARTEFATO }

    /** Resultado da avaliação de um manifesto assinado. */
    public record Avaliacao(Resultado resultado, Motivo motivo, Optional<ManifestoRelease> manifesto, Optional<ManifestoRelease.Artefato> artefato) {
        public enum Resultado { ATUALIZADA, DISPONIVEL, RECUSADA }

        static Avaliacao recusada(Motivo m, ManifestoRelease manifesto) {
            return new Avaliacao(Resultado.RECUSADA, m, Optional.ofNullable(manifesto), Optional.empty());
        }
    }

    private final ChavesRelease chaves;
    private final String versaoAtual;
    private final String osName;
    private final String osArch;
    private final ManifestoRelease.FormatoInstalado formato;

    public VerificadorAtualizacao(ChavesRelease chaves, String versaoAtual, String osName, String osArch, ManifestoRelease.FormatoInstalado formato) {
        this.chaves = Objects.requireNonNull(chaves);
        this.versaoAtual = Objects.requireNonNull(versaoAtual);
        this.osName = osName;
        this.osArch = osArch;
        this.formato = Objects.requireNonNull(formato);
    }

    public static VerificadorAtualizacao destaMaquina(ChavesRelease chaves, String versaoAtual, ManifestoRelease.FormatoInstalado formato) {
        return new VerificadorAtualizacao(chaves, versaoAtual, System.getProperty("os.name"), System.getProperty("os.arch"), formato);
    }

    public String versaoAtual() {
        return versaoAtual;
    }

    public Avaliacao avaliar(byte[] json, byte[] assinatura) {
        if (json == null || json.length > ManifestoRelease.TETO_BYTES) {
            return Avaliacao.recusada(Motivo.TAMANHO, null);
        }
        Optional<ChavesRelease.Chave> signatario = chaves.quemAssinou(json, assinatura);
        if (signatario.isEmpty()) {
            log.warn("latest.json com assinatura que não confere com nenhuma chave embutida ({})", chaves.todas().size());
            return Avaliacao.recusada(Motivo.ASSINATURA, null);
        }
        ManifestoRelease m;
        try {
            m = ManifestoRelease.parse(json);
        } catch (ManifestoInvalidoException e) {
            log.warn("latest.json assinado mas inválido: {}", e.getMessage());
            return Avaliacao.recusada(Motivo.MANIFESTO, null);
        }
        if (!m.kid().equals(signatario.get().kid())) {
            log.warn("latest.json diz kid {} mas foi assinado pela chave {}", m.kid(), signatario.get().kid());
            return Avaliacao.recusada(Motivo.KID, null);
        }
        if (signatario.get().reserva()) {
            log.warn("latest.json assinado pela chave RESERVA ({}): rotação de chave de release em curso", m.kid());
        }
        boolean maior;
        try {
            maior = VersaoSemantica.maiorQue(m.versao(), versaoAtual);
        } catch (IllegalArgumentException e) {
            return Avaliacao.recusada(Motivo.VERSAO, m);
        }
        if (!maior) {
            return new Avaliacao(Avaliacao.Resultado.ATUALIZADA, null, Optional.of(m), Optional.empty());
        }
        Optional<ManifestoRelease.Artefato> artefato = m.artefatoPara(osName, osArch, formato);
        if (artefato.isEmpty()) {
            return Avaliacao.recusada(Motivo.SEM_ARTEFATO, m);
        }
        return new Avaliacao(Avaliacao.Resultado.DISPONIVEL, null, Optional.of(m), artefato);
    }
}
