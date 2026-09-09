package br.com.wagner.wagsyspet.agente.core;

import br.com.wagner.wagsyspet.agente.core.pareamento.CofreCredencial;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.core.pareamento.Pareamento;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.AssinadorTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ponta a ponta do L2: pareamento gravado no cofre → servidor montado → o PWA (cliente real) faz hello/auth com um ticket
 * assinado pela chave do pareamento; Origin fora da lista do pareamento é recusada no handshake; impressora fica no config.json.
 */
@DisplayName("MontadorServidor — do pareamento.enc ao servidor autenticando de verdade")
class MontadorServidorTest {

    @Test
    @DisplayName("cofre → montar → hello_ok com o agenteId do backend; auth com ticket da chave pareada → auth_ok; Origin estranha → handshake recusado")
    void pontaAPonta(@TempDir Path tmp) throws Exception {
        KeyPair chaves = ChavesTicket.gerar();
        Pareamento p = new Pareamento("caixa-uid-001", 7L, null, ChavesTicket.exportarPublica(chaves.getPublic()),
                List.of("https://app.agroease.com.br"), 28421, "1.0.0",
                "https://wagsyspet-backend-production.up.railway.app", Instant.now());
        DiretoriosDoAgente dirs = new DiretoriosDoAgente(tmp.resolve("agente"));
        CofreCredencial cofre = new CofreCredencial(dirs);
        cofre.gravar(p);

        Pareamento relido = cofre.ler().orElseThrow();
        ServidorAgente servidor = MontadorServidor.montar(relido, "1.0.0-teste", dirs, 0, new ServidorAgente.Prazos(),
                new PortaImpressaoFake("PDF"));
        servidor.iniciar(Duration.ofSeconds(10));
        try {
            // Origin da lista do pareamento entra; a de dev (Vercel) NÃO está na lista deste pareamento → recusada
            ClienteTeste estranha = ClienteTeste.conectar(servidor.getPort(), Map.of("Origin", "https://wagsyspet-frontend.vercel.app"));
            assertThat(estranha.fechou.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(estranha.abriu.getCount()).as("handshake não pode completar").isEqualTo(1);

            ClienteTeste c = ClienteTeste.conectarAberto(servidor.getPort(), "https://app.agroease.com.br");
            c.send("{\"tipo\":\"hello\",\"versaoProtocolo\":1}");
            JsonNode hello = c.proximaMensagem();
            assertThat(hello.get("agenteId").asText()).isEqualTo("caixa-uid-001");
            assertThat(hello.get("agenteVersao").asText()).isEqualTo("1.0.0-teste");

            String ticket = new AssinadorTicket(chaves.getPrivate()).assinar(TicketClaims.novo(7L, "caixa-uid-001", Instant.now(), Duration.ofMinutes(10)));
            c.send("{\"tipo\":\"auth\",\"ticket\":\"" + ticket + "\"}");
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("auth_ok");

            c.send("{\"tipo\":\"selecionar_impressora\",\"id\":\"s1\",\"nome\":\"PDF\"}");
            assertThat(c.proximaMensagem().get("tipo").asText()).isEqualTo("selecionar_impressora_ok");
            assertThat(new ConfiguracaoLocalArquivo(dirs.config()).impressoraSelecionada()).as("persistida no config.json da pasta do agente").contains("PDF");
            c.close();
        } finally {
            servidor.stop(1000);
        }
    }
}
