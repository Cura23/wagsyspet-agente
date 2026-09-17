package br.com.wagner.wagsyspet.agente.app.autostart;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows (plano F6 D5): tarefa do Agendador por XML com DOIS gatilhos — {@code LogonTrigger} (sobe já no login do usuário) e
 * {@code TimeTrigger} ancorado no relógio com repetição de 1 min sem fim, que é o KEEPALIVE: com
 * {@code MultipleInstancesPolicy=IgnoreNew} o disparo é ignorado enquanto a instância da tarefa roda; se o agente morrer, o próximo
 * minuto o relança. A repetição NÃO mora no LogonTrigger porque essa só arma no evento de logon — a sessão em que a loja pareou (ou
 * reabriu o agente depois de um "Sair") ficaria sem keepalive (adversarial L3). Roda com o token interativo do usuário (vê as
 * impressoras dele), sem admin, sem o limite padrão de 72 h ({@code PT0S}), prioridade normal (o padrão do Agendador é 7) e liberado
 * na bateria. O usuário vai pelo SID (nome com espaço/acento não sobrevive ao parse nem ao pipe OEM do {@code whoami}). O XML vai em
 * UTF-16LE com BOM, com os elementos na ordem em que o próprio Windows exporta.
 * <p>A ação leva {@code --keepalive}: o IgnoreNew só enxerga instâncias lançadas PELA tarefa; com o agente aberto por fora (menu
 * Iniciar, Run de transição, sessão do pareamento) o disparo de 1 min sobe uma 2ª instância, que com essa flag sai muda — sem ela
 * seria um diálogo "já está em execução" por minuto.
 * <p>Sem {@code schtasks} (política de grupo/negado) cai no {@code HKCU\…\Run} da F3 — só no logon, sem reinício.
 * <p>{@link #pausar(Optional)} desabilita a tarefa e deixa o Run só-no-logon como rede de segurança ("Sair" e atualização: o
 * keepalive não pode reabrir o agente em 1 min); {@link #retomar(Optional)} (subida do agente/pareamento) desfaz isso e MIGRA quem
 * ainda está só com o Run da v1.0.0. O estado habilitada/pausada vem do {@code <Settings><Enabled>} do {@code /query /xml} — o texto
 * do Status é localizado —, e nada é re-registrado quando já está como deve.
 */
final class AutostartWindows implements Autostart {

    private static final Logger log = LoggerFactory.getLogger(AutostartWindows.class);
    static final String CHAVE = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run";
    static final String VALOR = "AgroEaseAgenteImpressao";
    /** Nome da tarefa no Agendador (o lojista vê isto em "Agendador de Tarefas"). */
    static final String TAREFA = "AgroEase-Agente-Impressao";
    static final String ARQUIVO_XML = TAREFA + ".xml";
    /** = {@code Argumentos.FLAG_KEEPALIVE} (pacote acima; o teste do Main cruza os dois). */
    static final String ARGUMENTO_KEEPALIVE = "--keepalive";
    /** Âncora da repetição: qualquer instante no passado — o Agendador alinha os disparos de 1 min a partir dele. */
    static final String INICIO_DA_REPETICAO = "2026-01-01T00:00:00";
    private static final Pattern SID = Pattern.compile("S-1-\\d+(?:-\\d+)+");
    private static final Pattern SETTINGS = Pattern.compile("<Settings>(.*?)</Settings>", Pattern.DOTALL);
    /** Dono da tarefa no XML do {@code /query}: {@code <UserId>} do gatilho de logon / do principal (SID). */
    private static final Pattern USER_ID = Pattern.compile("<UserId>\\s*(S-1-\\d+(?:-\\d+)+)\\s*</UserId>");

    private final ComandoExterno cmd;
    private final Path pastaXml;

    /** @param pastaXml onde o XML da tarefa fica (pasta de dados do agente; ajuda o diagnóstico) */
    AutostartWindows(ComandoExterno cmd, Path pastaXml) {
        this.cmd = cmd;
        this.pastaXml = pastaXml;
    }

    @Override
    public boolean instalado() throws IOException {
        return tarefaExiste() || runExiste();
    }

    @Override
    public void instalar(Path launcher, boolean ativarAgora) throws IOException { // a tarefa age no minuto seguinte e o Run no logon: ativarAgora não se aplica
        String caminho = launcher.toAbsolutePath().toString();
        if (caminho.length() + 2 > 260) {
            throw new IOException("caminho do agente longo demais para o Windows (" + (caminho.length() + 2) + " > 260)");
        }
        if (criarTarefa(caminho)) {
            if (runExiste()) {
                apagarRun(); // migração F3 → F6, ou Run de transição de um "Sair"
            }
            log.info("Autostart Windows: tarefa {} criada (logon + keepalive 1 min) → {}", TAREFA, caminho);
            return;
        }
        gravarRun(caminho);
        log.info("Autostart Windows gravado em {}\\{} → {} (sem Agendador: só no logon)", CHAVE, VALOR, caminho);
    }

    @Override
    public void desinstalar() throws IOException {
        if (tarefaExiste()) {
            ComandoExterno.Saida s = cmd.executar(List.of("schtasks", "/delete", "/tn", TAREFA, "/f"));
            if (!s.ok()) {
                throw new IOException("schtasks /delete falhou (" + s.exit() + "): " + s.texto());
            }
            log.info("Tarefa {} removida", TAREFA);
        }
        if (runExiste()) {
            apagarRun();
        }
    }

    @Override
    public void pausar(Optional<Path> launcher) throws IOException {
        Optional<Boolean> habilitada = tarefaHabilitada();
        if (habilitada.isEmpty()) {
            return; // sem tarefa (fallback Run, ou nada instalado): não há keepalive a pausar
        }
        if (habilitada.get()) {
            mudar("/disable", "pausada");
        }
        if (launcher.isPresent() && !runExiste()) {
            gravarRun(launcher.get().toAbsolutePath().toString());
        }
    }

    @Override
    public void retomar(Optional<Path> launcher) throws IOException {
        Optional<Boolean> habilitada = tarefaHabilitada();
        if (habilitada.isEmpty()) {
            if (launcher.isPresent() && runExiste()) {
                // loja que veio da v1.0.0 (só o Run): ganha o keepalive na 1ª subida da versão nova. Onde o schtasks é negado, o
                // /create falha e o Run é regravado — uma tentativa barata por subida
                log.info("Autostart só com o Run (v1.0.0): migrando para a tarefa keepalive");
                instalar(launcher.get(), false);
            }
            return;
        }
        if (!habilitada.get()) {
            mudar("/enable", "reabilitada");
        }
        if (runExiste()) {
            apagarRun();
        }
    }

    private void mudar(String opcao, String verbo) throws IOException {
        ComandoExterno.Saida s = cmd.executar(List.of("schtasks", "/change", "/tn", TAREFA, opcao));
        if (!s.ok()) {
            throw new IOException("schtasks /change " + opcao + " falhou (" + s.exit() + "): " + s.texto());
        }
        log.info("Tarefa {} {}", TAREFA, verbo);
    }

    @Override
    public String descricao() {
        Optional<Boolean> habilitada = tarefaHabilitada();
        if (habilitada.isPresent()) {
            return "Windows: tarefa \"" + TAREFA + "\" no Agendador (logon do usuário + relança a cada 1 min se o agente cair)"
                    + (habilitada.get() ? "" : " — pausada até o próximo login (Sair)");
        }
        return "Windows: " + CHAVE + "\\" + VALOR + " (roda no logon; sem reinício automático — Agendador indisponível)";
    }

    // --- tarefa ---

    private boolean tarefaExiste() {
        return tarefaHabilitada().isPresent();
    }

    /**
     * Vazio = não existe (ou {@code schtasks} indisponível — vale como "sem tarefa", aí o Run responde); {@code false} = existe mas está
     * desabilitada. Lê o {@code <Enabled>} de {@code <Settings>} no XML da tarefa (os gatilhos têm o seu próprio {@code <Enabled>}); sem o
     * elemento, o padrão do schema é habilitada.
     */
    private Optional<Boolean> tarefaHabilitada() {
        ComandoExterno.Saida s;
        try {
            s = cmd.executar(List.of("schtasks", "/query", "/tn", TAREFA, "/xml"));
        } catch (IOException e) { // schtasks não existe/não responde
            log.debug("schtasks indisponível: {}", e.toString());
            return Optional.empty();
        }
        if (!s.ok()) {
            return Optional.empty();
        }
        // o schtasks pode devolver o XML em UTF-16 (o ComandoExterno decodifica como UTF-8): sem os NULs as tags ASCII voltam
        String xml = s.texto().replace("\u0000", "");
        if (deOutroUsuario(xml)) {
            // Fecho F6: o nome da tarefa é global na máquina. A de OUTRO usuário Windows não é a minha: tratá-la como minha fazia o
            // meu "Sair" desabilitar o autostart DELE e a minha subida apagar o MEU Run. Para mim vale "sem tarefa" → fico no Run.
            return Optional.empty();
        }
        Matcher m = SETTINGS.matcher(xml);
        return Optional.of(!(m.find() && m.group(1).replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT).contains("<enabled>false</enabled>")));
    }

    /** {@code false} = schtasks indisponível/negado (o chamador cai no Run). */
    /** A tarefa consultada pertence a OUTRO usuário? Só afirma com os dois SIDs em mãos (na dúvida, é minha — como sempre foi). */
    private boolean deOutroUsuario(String xmlDaTarefa) {
        Matcher dono = USER_ID.matcher(xmlDaTarefa);
        if (!dono.find()) {
            return false;
        }
        Optional<String> meu = sidDoUsuario();
        return meu.isPresent() && !meu.get().equalsIgnoreCase(dono.group(1));
    }

    /** Existe uma tarefa com o nosso nome que é de outro usuário? (o {@code /create /f} a sobrescreveria, trocando o dono) */
    private boolean tarefaAlheia() {
        try {
            ComandoExterno.Saida s = cmd.executar(List.of("schtasks", "/query", "/tn", TAREFA, "/xml"));
            return s.ok() && deOutroUsuario(s.texto().replace("\u0000", ""));
        } catch (IOException e) {
            return false;
        }
    }

    private boolean criarTarefa(String caminho) throws IOException {
        if (tarefaAlheia()) {
            log.warn("Já existe a tarefa {} de OUTRO usuário deste computador: não sobrescrevo — este usuário fica com o Run do registro (só no logon)", TAREFA);
            return false;
        }
        Optional<String> usuario = sidDoUsuario();
        Path xml = pastaXml.resolve(ARQUIVO_XML);
        Files.createDirectories(pastaXml);
        Files.write(xml, utf16ComBom(xmlDaTarefa(caminho, usuario)));
        ComandoExterno.Saida s;
        try {
            s = cmd.executar(List.of("schtasks", "/create", "/tn", TAREFA, "/xml", xml.toString(), "/f"));
        } catch (IOException e) { // schtasks não existe/não responde: o Run ainda dá o "iniciar no logon"
            log.warn("schtasks indisponível ({}) — usando o Run do registro", e.toString());
            return false;
        }
        if (!s.ok()) {
            log.warn("schtasks /create falhou ({}): {} — usando o Run do registro", s.exit(), s.texto());
            return false;
        }
        return true;
    }

    /**
     * SID do usuário corrente, tirado de qualquer formato do {@code whoami /user} (CSV ou tabela, qualquer idioma): é ASCII, sem
     * espaço, imune à code page do pipe — e é como o próprio Agendador grava o {@code <UserId>}. Vazio = sem {@code <UserId>}.
     */
    private Optional<String> sidDoUsuario() {
        try {
            ComandoExterno.Saida s = cmd.executar(List.of("whoami", "/user", "/fo", "csv", "/nh"));
            if (!s.ok()) {
                return Optional.empty();
            }
            Matcher m = SID.matcher(s.texto());
            return m.find() ? Optional.of(m.group()) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    static String xmlDaTarefa(String caminho, Optional<String> sid) {
        int corte = Math.max(caminho.lastIndexOf('\\'), caminho.lastIndexOf('/'));
        String pasta = corte > 0 ? caminho.substring(0, corte) : caminho;
        StringBuilder x = new StringBuilder();
        x.append("<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n");
        x.append("<Task version=\"1.4\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\">\r\n");
        x.append("  <RegistrationInfo>\r\n");
        x.append("    <Description>").append(escapar(NOME_EXIBIDO)).append(" — inicia no logon e volta sozinho se fechar.</Description>\r\n");
        x.append("    <URI>\\").append(TAREFA).append("</URI>\r\n");
        x.append("  </RegistrationInfo>\r\n");
        x.append("  <Triggers>\r\n");
        x.append("    <LogonTrigger>\r\n");
        x.append("      <Enabled>true</Enabled>\r\n");
        sid.ifPresent(u -> x.append("      <UserId>").append(escapar(u)).append("</UserId>\r\n"));
        x.append("    </LogonTrigger>\r\n");
        x.append("    <TimeTrigger>\r\n");
        x.append("      <Repetition>\r\n");
        x.append("        <Interval>PT1M</Interval>\r\n");
        x.append("        <StopAtDurationEnd>false</StopAtDurationEnd>\r\n");
        x.append("      </Repetition>\r\n");
        x.append("      <StartBoundary>").append(INICIO_DA_REPETICAO).append("</StartBoundary>\r\n");
        x.append("      <Enabled>true</Enabled>\r\n");
        x.append("    </TimeTrigger>\r\n");
        x.append("  </Triggers>\r\n");
        x.append("  <Principals>\r\n");
        x.append("    <Principal id=\"Author\">\r\n");
        sid.ifPresent(u -> x.append("      <UserId>").append(escapar(u)).append("</UserId>\r\n"));
        x.append("      <LogonType>InteractiveToken</LogonType>\r\n");
        x.append("      <RunLevel>LeastPrivilege</RunLevel>\r\n");
        x.append("    </Principal>\r\n");
        x.append("  </Principals>\r\n");
        x.append("  <Settings>\r\n");
        x.append("    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n");
        x.append("    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\r\n");
        x.append("    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\r\n");
        x.append("    <AllowHardTerminate>false</AllowHardTerminate>\r\n");
        x.append("    <StartWhenAvailable>true</StartWhenAvailable>\r\n");
        x.append("    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>\r\n");
        x.append("    <IdleSettings>\r\n");
        x.append("      <StopOnIdleEnd>false</StopOnIdleEnd>\r\n");
        x.append("      <RestartOnIdle>false</RestartOnIdle>\r\n");
        x.append("    </IdleSettings>\r\n");
        x.append("    <AllowStartOnDemand>true</AllowStartOnDemand>\r\n");
        x.append("    <Enabled>true</Enabled>\r\n");
        x.append("    <Hidden>false</Hidden>\r\n");
        x.append("    <RunOnlyIfIdle>false</RunOnlyIfIdle>\r\n");
        x.append("    <DisallowStartOnRemoteAppSession>false</DisallowStartOnRemoteAppSession>\r\n");
        x.append("    <UseUnifiedSchedulingEngine>true</UseUnifiedSchedulingEngine>\r\n");
        x.append("    <WakeToRun>false</WakeToRun>\r\n");
        x.append("    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\r\n");
        x.append("    <Priority>5</Priority>\r\n");
        x.append("  </Settings>\r\n");
        x.append("  <Actions Context=\"Author\">\r\n");
        x.append("    <Exec>\r\n");
        x.append("      <Command>\"").append(escapar(caminho)).append("\"</Command>\r\n");
        x.append("      <Arguments>").append(ARGUMENTO_KEEPALIVE).append("</Arguments>\r\n");
        x.append("      <WorkingDirectory>").append(escapar(pasta)).append("</WorkingDirectory>\r\n");
        x.append("    </Exec>\r\n");
        x.append("  </Actions>\r\n");
        x.append("</Task>\r\n");
        return x.toString();
    }

    static byte[] utf16ComBom(String texto) {
        byte[] corpo = texto.getBytes(StandardCharsets.UTF_16LE);
        return ByteBuffer.allocate(corpo.length + 2).put((byte) 0xFF).put((byte) 0xFE).put(corpo).array();
    }

    private static String escapar(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // --- Run (fallback, legado F3 e rede de segurança do pausar) ---

    private void gravarRun(String caminho) throws IOException {
        ComandoExterno.Saida s = cmd.executar(List.of("reg", "add", CHAVE, "/v", VALOR, "/t", "REG_SZ", "/d", "\"" + caminho + "\"", "/f"));
        if (!s.ok()) {
            throw new IOException("reg add falhou (" + s.exit() + "): " + s.texto());
        }
    }

    private boolean runExiste() throws IOException {
        ComandoExterno.Saida s = cmd.executar(List.of("reg", "query", CHAVE, "/v", VALOR));
        return s.ok() && s.texto().contains(VALOR);
    }

    private void apagarRun() throws IOException {
        ComandoExterno.Saida s = cmd.executar(List.of("reg", "delete", CHAVE, "/v", VALOR, "/f"));
        if (!s.ok()) {
            throw new IOException("reg delete falhou (" + s.exit() + "): " + s.texto());
        }
        log.info("Valor {} do Run removido", VALOR);
    }
}
