package br.com.wagner.wagsyspet.agente.app;

import br.com.wagner.wagsyspet.agente.app.ui.Bandeja;
import br.com.wagner.wagsyspet.agente.core.SubidaComFallback;
import br.com.wagner.wagsyspet.agente.core.pareamento.CofreCredencial;
import br.com.wagner.wagsyspet.agente.core.pareamento.DiretoriosDoAgente;
import br.com.wagner.wagsyspet.agente.impressao.Impressora;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.AssinadorTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.ChavesTicket;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.TicketClaims;
import br.com.wagner.wagsyspet.agente.protocolo.ticket.VerificadorTicket;

import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.security.KeyPair;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * {@code --diagnostico}: cada linha "OK"/"FALHA"/"INFO"; sai 0 só se tudo que é obrigatório está OK. É o que o CI executa
 * DENTRO do binário do jpackage e o que o suporte pede ao lojista ("rode o diagnóstico e me mande a saída").
 * Obrigatórios: Ed25519 no runtime, javax.print. Informativos: pasta, pareamento, bandeja, portas, autostart.
 */
final class Diagnostico {

    private Diagnostico() {
    }

    static boolean rodar(PrintStream out, DiretoriosDoAgente dirs, String linhaVersao) {
        boolean ok = true;
        out.println(linhaVersao);
        out.println("Heap máximo: " + (Runtime.getRuntime().maxMemory() / (1024 * 1024)) + " MB");
        out.println("Módulos no runtime: " + ModuleLayer.boot().modules().size()
                + (ModuleLayer.boot().findModule("jdk.crypto.ec").isPresent() ? " (jdk.crypto.ec presente)" : " (jdk.crypto.ec AUSENTE)")
                + (ModuleLayer.boot().findModule("java.logging").isPresent() ? "" : " (java.logging AUSENTE)"));

        // Ed25519 de ponta a ponta com as classes reais do ticket (o jlink sem jdk.crypto.ec falha AQUI, não em produção)
        try {
            KeyPair kp = ChavesTicket.gerar();
            Instant agora = Instant.now();
            String ticket = new AssinadorTicket(kp.getPrivate()).assinar(TicketClaims.novo(1L, "diag", agora, Duration.ofMinutes(1)));
            new VerificadorTicket(kp.getPublic(), 1L, "diag", Clock.systemUTC(), Duration.ofMinutes(1)).verificar(ticket);
            out.println("OK    Ed25519 (assinar + verificar ticket)");
        } catch (Exception | Error e) {
            ok = false;
            out.println("FALHA Ed25519: " + e);
        }

        // javax.print (módulo java.desktop) + enumeração real das impressoras do SO
        try {
            Impressora impressora = Impressora.padrao();
            List<String> nomes = impressora.listarImpressoras();
            out.println("OK    javax.print: " + nomes.size() + " impressora(s) " + nomes
                    + " · padrão: " + impressora.impressoraPadrao().orElse("(nenhuma)"));
        } catch (Exception | Error e) {
            ok = false;
            out.println("FALHA javax.print: " + e);
        }

        // pasta de dados e pareamento (informativos — o binário funciona sem eles até parear)
        out.println("INFO  pasta de dados: " + dirs.raiz() + (Files.isDirectory(dirs.raiz()) ? "" : " (ainda não criada)"));
        try {
            var p = new CofreCredencial(dirs).ler();
            out.println(p.isPresent()
                    ? "INFO  pareamento: loja " + p.get().lojaId() + " · agenteId " + p.get().agenteId() + " · chave " + p.get().fingerprintChave()
                    : "INFO  pareamento: não pareado (use --parear <codigo>)");
        } catch (RuntimeException e) {
            out.println("INFO  pareamento: ilegível (" + e + ")");
        }

        // tela / bandeja
        boolean headless = GraphicsEnvironment.isHeadless();
        out.println("INFO  tela: " + (headless ? "sem tela (headless) — só linha de comando" : "com tela")
                + " · bandeja do sistema: " + (Bandeja.disponivel() ? "suportada" : "não suportada (a janela de status é usada)"));

        // portas do contrato
        StringBuilder portas = new StringBuilder();
        for (int porta : SubidaComFallback.PORTAS_PADRAO) {
            portas.append(porta).append(portaLivre(porta) ? " livre" : " OCUPADA").append("  ");
        }
        out.println("INFO  portas 127.0.0.1: " + portas.toString().trim());
        out.println("INFO  iniciar com o sistema: " + ComandosAutostart.padrao(out, out).linhaStatus());
        out.println("INFO  relógio × servidor: " + relogioContraServidor());

        out.println(ok ? "DIAGNÓSTICO OK" : "DIAGNÓSTICO COM FALHAS");
        return ok;
    }

    /** Header Date do backend (o mesmo do pareamento) × relógio local — a causa escondida de "ticket em formato inválido". */
    private static String relogioContraServidor() {
        try {
            String url = ComandosPareamento.urlBackend(null, System.getenv(), System.getProperties());
            java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url + "/actuator/health/liveness"))
                    .timeout(java.time.Duration.ofSeconds(30)).GET().build(); // cold start do Railway ≈ 28 s
            java.net.http.HttpResponse<Void> resp = http.send(req, java.net.http.HttpResponse.BodyHandlers.discarding());
            var desvio = br.com.wagner.wagsyspet.agente.core.pareamento.ClientePareamento.desvioDeRelogio(resp.headers().firstValue("Date").orElse(null), Instant.now());
            if (desvio.isEmpty()) {
                return "servidor " + url + " respondeu sem Date";
            }
            String texto = br.com.wagner.wagsyspet.agente.core.pareamento.ClientePareamento.descreverDesvio(desvio.get());
            boolean alerta = desvio.get().abs().compareTo(br.com.wagner.wagsyspet.agente.core.pareamento.ClientePareamento.DESVIO_RELOGIO_ALERTA) > 0;
            return texto + (alerta ? "  ← ACERTE A DATA/HORA deste computador (tickets podem ser recusados)" : " (ok)");
        } catch (Exception e) {
            return "não foi possível consultar o servidor agora (" + e.getClass().getSimpleName() + ")";
        }
    }

    private static boolean portaLivre(int porta) {
        try (ServerSocket s = new ServerSocket(porta, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
