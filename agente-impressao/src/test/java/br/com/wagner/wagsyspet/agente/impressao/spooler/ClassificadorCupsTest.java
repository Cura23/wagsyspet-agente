package br.com.wagner.wagsyspet.agente.impressao.spooler;

import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Estado;
import br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler.Motivo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Plano F6 D9 — classificação PURA da saída do {@code lpstat} (LC_ALL=C). As fixtures em {@code src/test/resources/lpstat} são saídas
 * REAIS do CUPS 2.4.10 (Debian 13), colhidas em 17/09/2026 com uma fila de teste apontando para {@code socket://127.0.0.1:9}.
 */
@DisplayName("ClassificadorCups — saída real do lpstat → IMPRESSO / FALHOU / PENDENTE / DESCONHECIDO com motivo whitelist")
class ClassificadorCupsTest {

    private static String fixture(String nome) {
        try (InputStream in = ClassificadorCupsTest.class.getResourceAsStream("/lpstat/" + nome)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("job na lista de completos com 'job-completed-successfully' → IMPRESSO encerrado; o detalhe diz o que isso garante (dados entregues ao dispositivo, não 'papel saiu')")
    void impresso() {
        EstadoSpooler e = ClassificadorCups.classificar("PDF-48", "", fixture("completos-sucesso.txt"), fixture("impressora-ociosa.txt"));
        assertThat(e.estado()).isEqualTo(Estado.IMPRESSO);
        assertThat(e.motivo()).isNull();
        assertThat(e.encerrado()).isTrue();
        assertThat(e.detalhe()).contains("PDF-48").contains("job-completed-successfully");
    }

    @Test
    @DisplayName("'-W completed' inclui cancelado e abortado (RFC 8011): 'job-canceled-by-user' → FALHOU{CANCELADO}; 'job-aborted-by-system' → FALHOU{ABORTADO}; 'job-completed-with-errors' → FALHOU{ERRO_DRIVER}")
    void falhou() {
        EstadoSpooler cancelado = ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-49", "", fixture("completos-cancelado.txt"), "");
        assertThat(cancelado.estado()).isEqualTo(Estado.FALHOU);
        assertThat(cancelado.motivo()).isEqualTo(Motivo.CANCELADO);
        assertThat(cancelado.encerrado()).isTrue();

        // (sintético sobre a fixture real: a keyword do CUPS é 'aborted-by-system', sem o prefixo job-)
        String abortado = fixture("completos-cancelado.txt").replace("job-canceled-by-user", "aborted-by-system");
        assertThat(ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-49", "", abortado, "").motivo()).isEqualTo(Motivo.ABORTADO);
        // erro de filtro/driver: o CUPS PARA o job (IPP stopped) e ele continua em NOT-completed com 'job-completed-with-errors' —
        // não vai sair sozinho → FALHOU{ERRO_DRIVER} (sintético sobre a fixture real de not-completed)
        String comErros = fixture("nao-completos-fila-parada.txt").replace("Alerts: none", "Alerts: job-completed-with-errors");
        EstadoSpooler filtro = ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-50", comErros, "", fixture("impressora-ociosa.txt"));
        assertThat(filtro.estado()).isEqualTo(Estado.FALHOU);
        assertThat(filtro.motivo()).isEqualTo(Motivo.ERRO_DRIVER);
    }

    @Test
    @DisplayName("lpstat é a RESERVA (só quando o IPP local não responde) e é CONSERVADOR: razão terminal que ele não reconhece — 'Alerts: none' (CUPS 2.4.10: cancelado antes de processar) ou 'processing-to-stop-point' (CUPS < 2.4.8: job IMPRESSO com sucesso, Issue #832) — é DESCONHECIDO, nunca FALHOU nem IMPRESSO: pelo texto as duas coisas são indistinguíveis")
    void razaoTerminalNaoReconhecida() {
        EstadoSpooler none = ClassificadorCups.classificar("AGROEASE_PARADA-54", "", fixture("completos-cancelado-antes-de-processar.txt"), "");
        assertThat(none.estado()).isEqualTo(Estado.DESCONHECIDO);
        assertThat(none.encerrado()).isTrue();
        String antigo = fixture("completos-sucesso.txt").replace("job-completed-successfully", "processing-to-stop-point");
        assertThat(ClassificadorCups.classificar("PDF-48", "", antigo, "").estado()).isEqualTo(Estado.DESCONHECIDO);
    }

    @Test
    @DisplayName("job ainda em not-completed → PENDENTE não encerrado, com o motivo tirado da FILA: 'disabled since'/'Alerts: paused' → FILA_PARADA; 'may not exist or is unavailable' ou connecting-to-device → IMPRESSORA_OFFLINE; media-empty → SEM_PAPEL; door-open → TAMPA_ABERTA; nada reconhecido → sem motivo")
    void pendente() {
        EstadoSpooler parada = ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-50", fixture("nao-completos-fila-parada.txt"), "", fixture("impressora-fila-parada.txt"));
        assertThat(parada.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(parada.motivo()).isEqualTo(Motivo.FILA_PARADA);
        assertThat(parada.encerrado()).isFalse();

        EstadoSpooler inalcancavel = ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-49", fixture("nao-completos-inalcancavel.txt"), "", fixture("impressora-inalcancavel.txt"));
        assertThat(inalcancavel.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(inalcancavel.motivo()).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        assertThat(inalcancavel.detalhe()).contains("may not exist or is unavailable");

        String semPapel = fixture("impressora-ociosa.txt").replace("Alerts: none", "Alerts: media-empty-warning");
        assertThat(ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-49", fixture("nao-completos-inalcancavel.txt"), "", semPapel).motivo()).isEqualTo(Motivo.SEM_PAPEL);
        String tampa = fixture("impressora-ociosa.txt").replace("Alerts: none", "Alerts: door-open-report");
        assertThat(ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-49", fixture("nao-completos-inalcancavel.txt"), "", tampa).motivo()).isEqualTo(Motivo.TAMPA_ABERTA);
        String conectando = fixture("impressora-ociosa.txt").replace("Alerts: none", "Alerts: connecting-to-device");
        assertThat(ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-49", fixture("nao-completos-inalcancavel.txt"), "", conectando).motivo()).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        // o PRÓPRIO job diz que a impressora está inalcançável, mesmo com a fila "ociosa" no lpstat -p
        assertThat(ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-49", fixture("nao-completos-inalcancavel.txt"), "", fixture("impressora-ociosa.txt")).motivo()).isEqualTo(Motivo.IMPRESSORA_OFFLINE);
        // na fila sem nenhuma pista (só esperando a vez): PENDENTE sem motivo, nunca um palpite
        EstadoSpooler semPista = ClassificadorCups.classificar("AGROEASE_TESTE_MORTA-50", fixture("nao-completos-fila-parada.txt"), "", fixture("impressora-ociosa.txt"));
        assertThat(semPista.estado()).isEqualTo(Estado.PENDENTE);
        assertThat(semPista.motivo()).isNull();
    }

    @Test
    @DisplayName("id casa a LINHA inteira do job: 'PDF-4' não pode casar com 'PDF-48' nem 'PDF-46' (seria o estado do cupom de OUTRA venda)")
    void idExato() {
        EstadoSpooler e = ClassificadorCups.classificar("PDF-4", "", fixture("completos-sucesso.txt"), "");
        assertThat(e.estado()).isEqualTo(Estado.DESCONHECIDO);
        assertThat(e.motivo()).isEqualTo(Motivo.SUMIU_DA_FILA);
    }

    @Test
    @DisplayName("ausente nas duas listas (PreserveJobHistory No, ou histórico rodou) → DESCONHECIDO{SUMIU_DA_FILA} — NUNCA 'impresso' por inferência")
    void sumiu() {
        EstadoSpooler e = ClassificadorCups.classificar("PDF-999", "", "", fixture("impressora-ociosa.txt"));
        assertThat(e.estado()).isEqualTo(Estado.DESCONHECIDO);
        assertThat(e.motivo()).isEqualTo(Motivo.SUMIU_DA_FILA);
        assertThat(e.encerrado()).isTrue();
    }
}
