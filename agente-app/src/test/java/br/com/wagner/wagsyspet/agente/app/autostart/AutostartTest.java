package br.com.wagner.wagsyspet.agente.app.autostart;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import br.com.wagner.wagsyspet.agente.app.autostart.ComandoExterno.Saida;

import static org.assertj.core.api.Assertions.assertThat;

/** Plano F3 D22 — autostart por SO com comandos externos FALSOS (nada toca o registro/launchd/systemd da máquina de teste). */
@DisplayName("Autostart — Windows (Run), macOS (LaunchAgent), Linux (systemd --user + .desktop)")
class AutostartTest {

    /** Grava os comandos e responde conforme a função. */
    static final class CmdFake implements ComandoExterno {
        final List<List<String>> chamadas = new ArrayList<>();
        Function<List<String>, Saida> resposta = c -> new Saida(0, "");

        @Override
        public Saida executar(List<String> comando) {
            chamadas.add(List.copyOf(comando));
            return resposta.apply(comando);
        }

        List<String> ultima() {
            return chamadas.get(chamadas.size() - 1);
        }
    }

    private static final Path LAUNCHER = Path.of("/opt/agroease-agente-impressao/bin/AgroEase-Agente-Impressao");

    @Nested
    @DisplayName("fábrica e launcher")
    class Fabrica {
        @Test
        @DisplayName("SO → implementação; launcher -cli vira o GUI ao lado; java cru → vazio")
        void fabricaELauncher(@TempDir Path home) {
            CmdFake cmd = new CmdFake();
            assertThat(Autostart.paraSo("Windows 11", home, Map.of(), cmd)).isInstanceOf(AutostartWindows.class);
            assertThat(Autostart.paraSo("Mac OS X", home, Map.of(), cmd)).isInstanceOf(AutostartMac.class);
            assertThat(Autostart.paraSo("Linux", home, Map.of(), cmd)).isInstanceOf(AutostartLinux.class);

            assertThat(Autostart.launcherGui(Path.of("/opt/x/bin/AgroEase-Agente-Impressao-cli"))).contains(Path.of("/opt/x/bin/AgroEase-Agente-Impressao"));
            assertThat(Autostart.launcherGui(Path.of("C:\\Program Files\\x\\AgroEase-Agente-Impressao-cli.exe")).map(Path::toString).orElse(""))
                    .endsWith("AgroEase-Agente-Impressao.exe");
            assertThat(Autostart.launcherGui(Path.of("/opt/x/bin/AgroEase-Agente-Impressao"))).contains(Path.of("/opt/x/bin/AgroEase-Agente-Impressao"));
            assertThat(Autostart.launcherGui(Path.of("/usr/lib/jvm/bin/java"))).isEmpty();
            assertThat(Autostart.launcherGui(Path.of("C:\\jdk\\bin\\java.exe"))).isEmpty();
        }
    }

    @Nested
    @DisplayName("Windows (F6 L3: tarefa keepalive do Agendador; Run só como fallback/transição)")
    class Windows {
        static final String SID = "S-1-5-21-1111111111-2222222222-3333333333-1001";

        /** O que o {@code schtasks /query /tn X /xml} devolve: o Enabled que vale é o de Settings (o do trigger fica sempre true). */
        private static String xmlConsultado(boolean habilitada) {
            return "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n<Task version=\"1.4\"><Triggers><LogonTrigger><Enabled>true</Enabled></LogonTrigger></Triggers>"
                    + "<Settings><MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy><Enabled>" + habilitada + "</Enabled></Settings></Task>";
        }

        /**
         * schtasks/reg/whoami falsos com ESTADO: a tarefa (existe/habilitada) e o valor Run mudam com create/delete/change/add/delete,
         * como no Windows real. {@code tarefa} = a tarefa existe (habilitada) no início; {@code run} = o valor Run existe no início.
         */
        private CmdFake cmdWindows(boolean tarefa, boolean runInicial) {
            java.util.concurrent.atomic.AtomicBoolean existe = new java.util.concurrent.atomic.AtomicBoolean(tarefa);
            java.util.concurrent.atomic.AtomicBoolean habilitada = new java.util.concurrent.atomic.AtomicBoolean(true);
            java.util.concurrent.atomic.AtomicBoolean run = new java.util.concurrent.atomic.AtomicBoolean(runInicial);
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> switch (c.get(0) + " " + c.get(1)) {
                case "schtasks /query" -> existe.get() ? new Saida(0, xmlConsultado(habilitada.get())) : new Saida(1, "ERROR: The system cannot find the file specified.");
                case "schtasks /create" -> { existe.set(true); habilitada.set(true); yield new Saida(0, "SUCCESS: The scheduled task \"" + AutostartWindows.TAREFA + "\" has successfully been created."); }
                case "schtasks /delete" -> { existe.set(false); yield new Saida(0, "SUCCESS"); }
                case "schtasks /change" -> { habilitada.set(c.contains("/enable")); yield new Saida(0, "SUCCESS"); }
                case "reg query" -> run.get() ? new Saida(0, "    " + AutostartWindows.VALOR + "    REG_SZ    \"C:\\x\\A.exe\"") : new Saida(1, "ERROR: The system was unable to find the specified registry key or value.");
                case "reg add" -> { run.set(true); yield new Saida(0, "The operation completed successfully."); }
                case "reg delete" -> { run.set(false); yield new Saida(0, "The operation completed successfully."); }
                // formato REAL do whoami: nome com espaço e acento (que o pipe OEM corrompe) — só o SID é confiável
                case "whoami /user" -> new Saida(0, "\"caixa-pc\\jo\uFFFDo caixa 1\",\"" + SID + "\"");
                default -> new Saida(0, "");
            };
            return cmd;
        }

        private CmdFake cmdComTarefa(boolean tarefa) {
            return cmdWindows(tarefa, false);
        }

        private static long conta(CmdFake cmd, String... prefixo) {
            return cmd.chamadas.stream().filter(c -> c.size() >= prefixo.length && c.subList(0, prefixo.length).equals(List.of(prefixo))).count();
        }

        private String lerXml(CmdFake cmd) throws IOException {
            List<String> create = cmd.chamadas.stream().filter(c -> c.get(0).equals("schtasks") && c.get(1).equals("/create")).findFirst().orElseThrow();
            return new String(Files.readAllBytes(Path.of(create.get(create.indexOf("/xml") + 1))), java.nio.charset.StandardCharsets.UTF_16LE).substring(1);
        }

        @Test
        @DisplayName("instalar → tarefa por XML (schtasks /create /tn … /xml <arquivo> /f): LogonTrigger (sobe já no login) + TimeTrigger ancorado no relógio com repetição PT1M sem fim (keepalive armado em QUALQUER sessão, inclusive a do pareamento), IgnoreNew, sem limite de 72 h, prioridade normal, bateria liberada, InteractiveToken, ação com --keepalive; XML UTF-16LE com BOM; sem reg add")
        void tarefaKeepalive(@TempDir Path pasta) throws IOException {
            CmdFake cmd = cmdComTarefa(false);
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            assertThat(a.instalado()).isFalse();
            Path exe = Path.of("C:\\Users\\loja\\AppData\\Local\\AgroEase-Agente-Impressao\\AgroEase-Agente-Impressao.exe");
            a.instalar(exe);
            List<String> create = cmd.chamadas.stream().filter(c -> c.get(0).equals("schtasks") && c.get(1).equals("/create")).findFirst().orElseThrow();
            assertThat(create).containsSequence("/tn", AutostartWindows.TAREFA).contains("/xml", "/f");
            Path xml = Path.of(create.get(create.indexOf("/xml") + 1));
            assertThat(xml).hasParent(pasta);
            byte[] bytes = Files.readAllBytes(xml);
            assertThat(bytes[0] & 0xff).isEqualTo(0xFF);
            assertThat(bytes[1] & 0xff).isEqualTo(0xFE); // BOM UTF-16LE (o schtasks lê UTF-16)
            String x = new String(bytes, java.nio.charset.StandardCharsets.UTF_16LE).substring(1);
            assertThat(x).startsWith("<?xml version=\"1.0\" encoding=\"UTF-16\"?>")
                    .contains("<LogonTrigger>").contains("<TimeTrigger>")
                    .contains("<Interval>PT1M</Interval>").doesNotContain("<Duration>").contains("<StopAtDurationEnd>false</StopAtDurationEnd>")
                    .contains("<StartBoundary>2026-01-01T00:00:00</StartBoundary>")
                    .contains("<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>")
                    .contains("<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>")
                    .contains("<Priority>5</Priority>")
                    .contains("<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>")
                    .contains("<StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>")
                    .contains("<LogonType>InteractiveToken</LogonType>").contains("<RunLevel>LeastPrivilege</RunLevel>")
                    .contains("<AllowStartOnDemand>true</AllowStartOnDemand>").contains("<Enabled>true</Enabled>")
                    // caminho entre aspas (pode ter espaço); fora do Windows o toAbsolutePath prefixa o cwd, por isso só o fim é conferido
                    .contains("<Command>\"").contains("\\AgroEase-Agente-Impressao\\AgroEase-Agente-Impressao.exe\"</Command>")
                    .contains("<Arguments>--keepalive</Arguments>")
                    .contains("\\AgroEase-Agente-Impressao</WorkingDirectory>");
            // a repetição mora no TimeTrigger (a de um LogonTrigger só arma no EVENTO de logon — adversarial L3), na ordem que o próprio Windows exporta
            String logon = x.substring(x.indexOf("<LogonTrigger>"), x.indexOf("</LogonTrigger>"));
            String tempo = x.substring(x.indexOf("<TimeTrigger>"), x.indexOf("</TimeTrigger>"));
            assertThat(logon).doesNotContain("<Repetition>").contains("<UserId>" + SID + "</UserId>");
            assertThat(tempo).contains("<Repetition>");
            assertThat(tempo.indexOf("<Repetition>")).isLessThan(tempo.indexOf("<StartBoundary>"));
            assertThat(tempo.indexOf("<StartBoundary>")).isLessThan(tempo.indexOf("<Enabled>"));
            // usuário pelo SID nos DOIS lugares (nome com espaço/acento não sobrevive ao parse nem ao pipe OEM — adversarial L3)
            assertThat(x.split(java.util.regex.Pattern.quote("<UserId>" + SID + "</UserId>"), -1)).hasSize(3);
            assertThat(x).doesNotContain("caixa 1").doesNotContain("\uFFFD");
            // o que o lojista vê no Agendador (nome, descrição, URI) é AgroEase — fora dos caminhos, que aqui carregam o cwd do teste
            assertThat(x.replaceAll("<Command>.*</Command>|<WorkingDirectory>.*</WorkingDirectory>", "")).doesNotContainIgnoringCase("wagsyspet");
            // com a tarefa gravada NÃO grava o Run (duas subidas no logon)
            assertThat(conta(cmd, "reg", "add")).isZero();
            assertThat(a.descricao()).containsIgnoringCase("Agendador").contains(AutostartWindows.TAREFA).contains("1 min");
        }

        @Test
        @DisplayName("whoami em formato de TABELA (cabeçalho localizado, ====, nome com espaço) → o SID sai do mesmo jeito; whoami falhando → sem <UserId> (o /create decide)")
        void usuarioPeloSid(@TempDir Path pasta) throws IOException {
            CmdFake cmd = cmdComTarefa(false);
            java.util.function.Function<List<String>, Saida> base = cmd.resposta;
            cmd.resposta = c -> c.get(0).equals("whoami")
                    ? new Saida(0, "\r\nINFORMA\uFFFD\uFFFDES DO USU\uFFFDRIO\r\n----------------\r\n\r\nNome de usu\uFFFDrio     SID\r\n=================== ==============\r\ndesktop-abc\\loja centro " + SID + "\r\n")
                    : base.apply(c);
            new AutostartWindows(cmd, pasta).instalar(Path.of("C:\\x\\A.exe"));
            assertThat(lerXml(cmd)).contains("<UserId>" + SID + "</UserId>").doesNotContain("loja centro");

            CmdFake semWhoami = cmdComTarefa(false);
            java.util.function.Function<List<String>, Saida> base2 = semWhoami.resposta;
            semWhoami.resposta = c -> c.get(0).equals("whoami") ? new Saida(1, "") : base2.apply(c);
            new AutostartWindows(semWhoami, pasta).instalar(Path.of("C:\\x\\A.exe"));
            assertThat(lerXml(semWhoami)).doesNotContain("<UserId>").contains("<LogonTrigger>");
        }

        @Test
        @DisplayName("schtasks negado/indisponível → cai no HKCU Run da F3 (reg add REG_SZ com o caminho entre aspas) e a descrição avisa que não há reinício")
        void fallbackRun(@TempDir Path pasta) throws IOException {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> c.get(0).equals("schtasks") ? new Saida(1, "ERROR: Access is denied.")
                    : c.get(0).equals("whoami") ? new Saida(0, "\"pc\\u\",\"" + SID + "\"")
                    : c.get(1).equals("query") ? new Saida(1, "") : new Saida(0, "");
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            a.instalar(Path.of("C:\\x\\AgroEase-Agente-Impressao.exe"));
            List<String> add = cmd.chamadas.stream().filter(c -> c.get(0).equals("reg") && c.get(1).equals("add")).findFirst().orElseThrow();
            assertThat(add).startsWith("reg", "add", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/t", "REG_SZ", "/d");
            assertThat(add.get(8)).startsWith("\"").endsWith("AgroEase-Agente-Impressao.exe\"");
            assertThat(add).endsWith("/f");
            assertThat(a.descricao()).containsIgnoringCase("sem reinício");
        }

        @Test
        @DisplayName("instalado() = tarefa OU Run; desinstalar apaga os dois que existirem (schtasks /delete /f, reg delete); nada instalado → só consultas")
        void instaladoEDesinstalar(@TempDir Path pasta) throws IOException {
            CmdFake cmd = cmdComTarefa(true);
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            assertThat(a.instalado()).isTrue();
            a.desinstalar();
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("schtasks", "/delete", "/tn", AutostartWindows.TAREFA, "/f"));
            assertThat(conta(cmd, "reg", "delete")).as("Run não existia").isZero();

            CmdFake soRun = cmdWindows(false, true);
            AutostartWindows b = new AutostartWindows(soRun, pasta);
            assertThat(b.instalado()).isTrue();
            b.desinstalar();
            assertThat(soRun.ultima()).containsExactly("reg", "delete", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/f");

            CmdFake nada = cmdComTarefa(false);
            AutostartWindows c = new AutostartWindows(nada, pasta);
            assertThat(c.instalado()).isFalse();
            c.desinstalar();
            assertThat(nada.chamadas.stream().noneMatch(x -> x.get(1).equals("/delete") || x.get(1).equals("delete"))).isTrue();
        }

        @Test
        @DisplayName("pausar(launcher) = 'Sair'/atualizar: desabilita a tarefa (o keepalive não pode reabrir em 1 min) E grava o Run só-no-logon (rede de segurança); retomar() reabilita e apaga o Run; o estado vem do <Settings><Enabled> do /query /xml (independe do idioma) e nada é re-registrado quando já está como deve")
        void pausarERetomar(@TempDir Path pasta) throws IOException {
            CmdFake cmd = cmdComTarefa(true);
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            Path exe = Path.of("C:\\x\\AgroEase-Agente-Impressao.exe");
            assertThat(cmd.chamadas).isEmpty();
            a.retomar(java.util.Optional.of(exe)); // subida normal, tarefa habilitada e sem Run: SÓ consultas (um /change re-registraria a tarefa a cada boot)
            assertThat(conta(cmd, "schtasks", "/change") + conta(cmd, "schtasks", "/create") + conta(cmd, "reg", "delete") + conta(cmd, "reg", "add")).isZero();
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("schtasks", "/query", "/tn", AutostartWindows.TAREFA, "/xml"));

            a.pausar(java.util.Optional.of(exe));
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("schtasks", "/change", "/tn", AutostartWindows.TAREFA, "/disable"));
            List<String> add = cmd.chamadas.stream().filter(c -> c.get(0).equals("reg") && c.get(1).equals("add")).findFirst().orElseThrow();
            assertThat(add).startsWith("reg", "add", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/t", "REG_SZ", "/d");
            assertThat(add.get(8)).endsWith("AgroEase-Agente-Impressao.exe\"");
            assertThat(a.instalado()).as("pausado ainda conta como 'iniciar com o sistema' (volta no logon)").isTrue();
            assertThat(a.descricao()).containsIgnoringCase("pausad");
            a.pausar(java.util.Optional.of(exe)); // idempotente: já desabilitada e com Run
            assertThat(conta(cmd, "schtasks", "/change")).isEqualTo(1);
            assertThat(conta(cmd, "reg", "add")).isEqualTo(1);

            a.retomar(java.util.Optional.of(exe));
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("schtasks", "/change", "/tn", AutostartWindows.TAREFA, "/enable"));
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("reg", "delete", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/f"));
            assertThat(a.descricao()).doesNotContainIgnoringCase("pausad");

            // sem launcher conhecido (dev): só desabilita
            CmdFake dev = cmdComTarefa(true);
            new AutostartWindows(dev, pasta).pausar(java.util.Optional.empty());
            assertThat(conta(dev, "schtasks", "/change")).isEqualTo(1);
            assertThat(conta(dev, "reg", "add")).isZero();

            // nada instalado (o lojista desativou com --desinstalar): pausar/retomar respeitam — nada é criado
            CmdFake nada = cmdComTarefa(false);
            AutostartWindows b = new AutostartWindows(nada, pasta);
            b.pausar(java.util.Optional.of(exe));
            b.retomar(java.util.Optional.of(exe));
            assertThat(conta(nada, "schtasks", "/change") + conta(nada, "schtasks", "/create") + conta(nada, "reg", "add") + conta(nada, "reg", "delete")).isZero();
        }

        @Test
        @DisplayName("Fecho F6 — DOIS usuários Windows no mesmo PC (dono e funcionário): o nome da tarefa é global. A tarefa de OUTRO usuário (<UserId> com outro SID) NÃO é a minha: 'Sair' não a desabilita (o agente do outro não subiria mais sozinho), a subida não apaga o MEU Run por causa dela, instalar não a sobrescreve (/create /f trocaria o dono) e desinstalar não a apaga — eu fico no Run do registro")
        void tarefaDeOutroUsuario(@TempDir Path pasta) throws IOException {
            String sidDoOutro = "S-1-5-21-1111111111-2222222222-3333333333-1002";
            String xmlDoOutro = "<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n<Task version=\"1.4\"><Triggers><LogonTrigger><Enabled>true</Enabled><UserId>" + sidDoOutro
                    + "</UserId></LogonTrigger></Triggers><Principals><Principal id=\"Author\"><UserId>" + sidDoOutro + "</UserId></Principal></Principals>"
                    + "<Settings><Enabled>true</Enabled></Settings></Task>";
            java.util.concurrent.atomic.AtomicBoolean run = new java.util.concurrent.atomic.AtomicBoolean(true);
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> switch (c.get(0) + " " + c.get(1)) {
                case "schtasks /query" -> new Saida(0, xmlDoOutro);
                case "reg query" -> run.get() ? new Saida(0, "    " + AutostartWindows.VALOR + "    REG_SZ    \"C:\\x\\A.exe\"") : new Saida(1, "ERROR");
                case "reg add" -> { run.set(true); yield new Saida(0, "ok"); }
                case "reg delete" -> { run.set(false); yield new Saida(0, "ok"); }
                case "whoami /user" -> new Saida(0, "\"caixa-pc\\funcionario\",\"" + SID + "\"");
                default -> new Saida(0, "");
            };
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            Path exe = Path.of("C:\\x\\AgroEase-Agente-Impressao.exe");

            a.retomar(java.util.Optional.of(exe)); // subida do MEU agente: a tarefa é do outro → meu Run fica
            assertThat(run.get()).as("o Run é o MEU autostart: não pode ser apagado por causa da tarefa de outro usuário").isTrue();
            a.pausar(java.util.Optional.of(exe)); // meu "Sair": não mexe na tarefa do outro
            a.instalar(exe, false); // --instalar / pareamento: não sobrescreve a tarefa do outro
            a.desinstalar();
            assertThat(conta(cmd, "schtasks", "/change")).as("nunca desabilita/reabilita a tarefa de outro usuário").isZero();
            assertThat(conta(cmd, "schtasks", "/create")).as("nunca sobrescreve (o /f trocaria o dono)").isZero();
            assertThat(conta(cmd, "schtasks", "/delete")).isZero();
            assertThat(a.descricao()).doesNotContain("Agendador (logon");
        }

        @Test
        @DisplayName("MIGRAÇÃO F3→F6 na subida: loja que já tem o Run da v1.0.0 e nenhuma tarefa → retomar(launcher) cria a tarefa e apaga o Run (sem launcher — dev — não mexe)")
        void migraRunDaF3NaSubida(@TempDir Path pasta) throws IOException {
            CmdFake cmd = cmdWindows(false, true);
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            a.retomar(java.util.Optional.empty());
            assertThat(conta(cmd, "schtasks", "/create")).isZero();
            a.retomar(java.util.Optional.of(Path.of("C:\\x\\AgroEase-Agente-Impressao.exe")));
            assertThat(conta(cmd, "schtasks", "/create")).isEqualTo(1);
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("reg", "delete", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/f"));
            assertThat(a.descricao()).containsIgnoringCase("Agendador");
            a.retomar(java.util.Optional.of(Path.of("C:\\x\\AgroEase-Agente-Impressao.exe"))); // já migrado: não recria
            assertThat(conta(cmd, "schtasks", "/create")).isEqualTo(1);
        }

        @Test
        @DisplayName("instalar de novo com a tarefa pausada (--instalar ou pareamento) → recria (/f, volta habilitada) e o Run é apagado")
        void reinstalarReabilitaEMigraRun(@TempDir Path pasta) throws IOException {
            CmdFake cmd = cmdComTarefa(true);
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            Path exe = Path.of("C:\\x\\AgroEase-Agente-Impressao.exe");
            a.pausar(java.util.Optional.of(exe)); // tarefa desabilitada + Run
            a.instalar(exe);
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).contains("/create", "/f"));
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("reg", "delete", AutostartWindows.CHAVE, "/v", AutostartWindows.VALOR, "/f"));
            assertThat(a.descricao()).doesNotContainIgnoringCase("pausad").containsIgnoringCase("Agendador");
        }

        @Test
        @DisplayName("schtasks INEXISTENTE (IOException ao executar) → não é erro: cai no Run, e instalado()/pausar()/retomar() tratam como 'sem tarefa'")
        void schtasksAusente(@TempDir Path pasta) throws IOException {
            java.util.concurrent.atomic.AtomicBoolean run = new java.util.concurrent.atomic.AtomicBoolean(false);
            ComandoExterno semSchtasks = c -> {
                if (c.get(0).equals("schtasks")) { throw new IOException("Cannot run program \"schtasks\""); }
                if (c.get(0).equals("whoami")) { return new Saida(0, "\"pc\\u\",\"" + SID + "\""); }
                if (c.get(1).equals("query")) { return run.get() ? new Saida(0, AutostartWindows.VALOR + " REG_SZ x") : new Saida(1, ""); }
                if (c.get(1).equals("add")) { run.set(true); }
                if (c.get(1).equals("delete")) { run.set(false); }
                return new Saida(0, "");
            };
            AutostartWindows a = new AutostartWindows(semSchtasks, pasta);
            Path exe = Path.of("C:\\x\\A.exe");
            assertThat(a.instalado()).isFalse();
            a.instalar(exe);
            assertThat(a.instalado()).isTrue();
            a.pausar(java.util.Optional.of(exe));
            a.retomar(java.util.Optional.of(exe)); // tenta migrar, o schtasks não existe → o Run continua valendo, sem lançar
            assertThat(a.instalado()).as("Run intacto").isTrue();
            a.desinstalar();
            assertThat(a.instalado()).isFalse();
        }

        @Test
        @DisplayName("schtasks E reg add falhando → IOException com a saída do reg")
        void falha(@TempDir Path pasta) {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> c.get(0).equals("whoami") ? new Saida(0, "\"pc\\u\",\"" + SID + "\"") : new Saida(1, "ERROR: Access is denied.");
            AutostartWindows a = new AutostartWindows(cmd, pasta);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> a.instalar(Path.of("C:\\x.exe")))
                    .isInstanceOf(IOException.class).hasMessageContaining("Access is denied");
        }
    }

    @Nested
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "gerador só roda em macOS; os caminhos POSIX do teste absolutizam como D:\\... no Windows — CI F3")
    @DisplayName("macOS")
    class Mac {
        @Test
        @DisplayName("instalar grava o plist (KeepAlive SuccessfulExit=false, RunAtLoad) e faz bootout+bootstrap gui/<uid>; desinstalar faz bootout e apaga")
        void fluxo(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> c.get(0).equals("id") ? new Saida(0, "501") : new Saida(0, "");
            AutostartMac a = new AutostartMac(home, Map.of(), cmd);
            assertThat(a.instalado()).isFalse();

            a.instalar(Path.of("/Applications/AgroEase-Agente-Impressao.app/Contents/MacOS/AgroEase-Agente-Impressao"));
            assertThat(a.instalado()).isTrue();
            String plist = Files.readString(a.plist());
            assertThat(a.plist()).isEqualTo(home.resolve("Library/LaunchAgents/" + Autostart.ID + ".plist"));
            assertThat(plist).contains("<string>" + Autostart.ID + "</string>")
                    .contains("<string>/Applications/AgroEase-Agente-Impressao.app/Contents/MacOS/AgroEase-Agente-Impressao</string>")
                    .contains("<key>RunAtLoad</key>\n    <true/>")
                    .contains("<key>SuccessfulExit</key>\n        <false/>")
                    .contains("<key>ThrottleInterval</key>\n    <integer>10</integer>");
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("launchctl", "bootout", "gui/501/" + Autostart.ID));
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("launchctl", "bootstrap", "gui/501", a.plist().toString()));

            a.desinstalar();
            assertThat(a.instalado()).isFalse();
            assertThat(cmd.ultima()).containsExactly("launchctl", "bootout", "gui/501/" + Autostart.ID);
            a.desinstalar(); // idempotente
        }

        @Test
        @DisplayName("ativarAgora=false (agente já rodando): grava o plist e NÃO chama launchctl — senão o launchd abriria uma 2ª instância (adversarial L4-A5)")
        void semAtivarAgora(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            AutostartMac a = new AutostartMac(home, Map.of(), cmd);
            a.instalar(Path.of("/Applications/X.app/Contents/MacOS/X"), false);
            assertThat(a.instalado()).isTrue();
            assertThat(cmd.chamadas).noneMatch(c -> c.get(0).equals("launchctl"));
        }

        @Test
        @DisplayName("bootstrap falhando (sem sessão gráfica, ex.: CI) NÃO desfaz a instalação — vale no próximo login")
        void bootstrapFalha(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> c.get(0).equals("id") ? new Saida(0, "501") : new Saida(5, "Bootstrap failed: 5: Input/output error");
            AutostartMac a = new AutostartMac(home, Map.of(), cmd);
            a.instalar(Path.of("/Applications/X.app/Contents/MacOS/X"));
            assertThat(a.instalado()).isTrue();
        }
    }

    @Nested
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "gerador só roda em Linux; os caminhos POSIX do teste absolutizam como D:\\... no Windows — CI F3")
    @DisplayName("Linux")
    class Linux {
        @Test
        @DisplayName("com systemd --user: unit Restart=on-failure + .desktop que importa DISPLAY e dá start; desinstalar para, apaga e recarrega")
        void comSystemd(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            AutostartLinux a = new AutostartLinux(home, Map.of(), cmd);
            assertThat(a.instalado()).isFalse();
            a.instalar(LAUNCHER);

            assertThat(a.unit()).isEqualTo(home.resolve(".config/systemd/user/" + AutostartLinux.UNIT));
            assertThat(a.desktop()).isEqualTo(home.resolve(".config/autostart/" + AutostartLinux.DESKTOP));
            String unit = Files.readString(a.unit());
            assertThat(unit).contains("ExecStart=\"" + LAUNCHER + "\"").contains("Restart=on-failure").contains("RestartSec=10")
                    .contains("StartLimitBurst=5").contains("PartOf=graphical-session.target").contains("SuccessExitStatus=0");
            String desktop = Files.readString(a.desktop());
            assertThat(desktop).contains("[Desktop Entry]").contains("Name=" + Autostart.NOME_EXIBIDO)
                    .contains("import-environment DISPLAY WAYLAND_DISPLAY").contains("systemctl --user start " + AutostartLinux.UNIT)
                    .contains("X-GNOME-Autostart-enabled=true").doesNotContainIgnoringCase("wagsyspet");
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("systemctl", "--user", "daemon-reload"));
            assertThat(a.instalado()).isTrue();

            a.instalar(LAUNCHER); // idempotente: sobrescreve
            a.desinstalar();
            assertThat(Files.exists(a.unit())).isFalse();
            assertThat(Files.exists(a.desktop())).isFalse();
            assertThat(cmd.chamadas).anySatisfy(c -> assertThat(c).containsExactly("systemctl", "--user", "stop", AutostartLinux.UNIT));
            a.desinstalar();
        }

        @Test
        @DisplayName("sem systemd --user (show-environment falha): só o .desktop, chamando o binário direto; XDG_CONFIG_HOME respeitado; espaço no caminho escapado")
        void semSystemd(@TempDir Path home) throws IOException {
            CmdFake cmd = new CmdFake();
            cmd.resposta = c -> new Saida(1, "Failed to connect to bus");
            AutostartLinux a = new AutostartLinux(home, Map.of("XDG_CONFIG_HOME", home.resolve("cfg").toString()), cmd);
            Path launcher = Path.of("/opt/Agro Ease/bin/AgroEase-Agente-Impressao");
            a.instalar(launcher);
            assertThat(a.desktop()).isEqualTo(home.resolve("cfg/autostart/" + AutostartLinux.DESKTOP));
            assertThat(Files.exists(a.unit())).isFalse();
            assertThat(Files.readString(a.desktop())).contains("Exec=\"/opt/Agro Ease/bin/AgroEase-Agente-Impressao\"").doesNotContain("systemctl");
            assertThat(a.descricao()).contains("chama o binário direto");
        }
    }
}
