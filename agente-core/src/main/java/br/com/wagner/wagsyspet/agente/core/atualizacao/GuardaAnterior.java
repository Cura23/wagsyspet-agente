package br.com.wagner.wagsyspet.agente.core.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ChavesRelease;
import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Guarda o instalador da versão ATUAL para o rollback (plano F6 D4). No Windows o MSI novo faz upgrade in-place e apaga o antigo,
 * então "voltar" exige ter o {@code .exe} da versão que está rodando ANTES da troca. Ele é baixado 1× por versão da release dessa
 * versão ({@code releases/download/v<atual>/latest.json} + {@code .sig}), passando pela MESMA cadeia do update (assinatura antes do
 * parse → kid → artefato desta plataforma → download com teto/sha) mais a exigência de a versão do manifesto ser IGUAL à atual.
 * Em {@code anterior/} só fica o instalador da versão ATUAL: o de outra versão é apagado ANTES de tentar a rede — se a guarda falhar, a
 * pasta fica vazia, nunca com o {@code .exe} errado (rollback para N-2 dizendo que voltou para N-1 — adversarial L3). Quem usa o
 * guardado passa por {@link #guardado(Path, String)} (versão + arquivo + sha do {@code versao.txt}), nunca "o primeiro .exe da pasta".
 * Nunca lança: sem release (versão de dev → 404, rede fora) devolve vazio e a atualização segue — sem rollback automático.
 */
public final class GuardaAnterior implements GerenteAtualizacao.GuardaDoAnterior {

    private static final Logger log = LoggerFactory.getLogger(GuardaAnterior.class);
    static final String MARCADOR = "versao.txt";
    private static final String SUFIXO_LATEST = "/releases/latest/download/";

    private final URI manifestoLatest;
    private final ChavesRelease chaves;
    private final String osName;
    private final String osArch;
    private final Path pasta;
    private final Duration prazo;

    public GuardaAnterior(URI manifestoLatest, ChavesRelease chaves, String osName, String osArch, Path pasta, Duration prazo) {
        this.manifestoLatest = Objects.requireNonNull(manifestoLatest);
        this.chaves = Objects.requireNonNull(chaves);
        this.osName = osName;
        this.osArch = osArch;
        this.pasta = Objects.requireNonNull(pasta);
        this.prazo = Objects.requireNonNull(prazo);
    }

    public static GuardaAnterior destaMaquina(URI manifestoLatest, ChavesRelease chaves, Path pasta, Duration prazo) {
        return new GuardaAnterior(manifestoLatest, chaves, System.getProperty("os.name"), System.getProperty("os.arch"), pasta, prazo);
    }

    /**
     * URL do {@code latest.json} da release {@code v<versao>}: no GitHub, {@code releases/latest/download/X} → {@code releases/download/vN/X};
     * em outra base (servidor do CI), {@code /vN/} entra antes do nome do arquivo.
     */
    public static URI manifestoDaVersao(URI latest, String versao) {
        String s = latest.toString();
        int i = s.indexOf(SUFIXO_LATEST);
        if (i >= 0) {
            return URI.create(s.substring(0, i) + "/releases/download/v" + versao + "/" + s.substring(i + SUFIXO_LATEST.length()));
        }
        int barra = s.lastIndexOf('/');
        return URI.create(s.substring(0, barra) + "/v" + versao + s.substring(barra));
    }

    /** Só o disco (marcador + sha): é o que o gatilho de boot consulta antes de aplicar sem rollback. */
    @Override
    public Optional<Path> guardado(String versaoAtual) {
        return guardado(pasta, versaoAtual);
    }

    /** Instalador da versão atual em {@code anterior/}, baixando se preciso. Vazio = não há como guardar (a atualização segue). */
    @Override
    public synchronized Optional<Path> garantir(String versaoAtual) {
        Optional<Path> guardado = guardado(pasta, versaoAtual);
        if (guardado.isPresent()) {
            return guardado;
        }
        esvaziar(); // o que houver ali é de OUTRA versão (ou não confere): não pode sobrar para um rollback errado
        URI manifesto = manifestoDaVersao(manifestoLatest, versaoAtual);
        ClienteRelease cliente = new ClienteRelease(manifesto, prazo, versaoAtual);
        Optional<ClienteRelease.ManifestoBaixado> baixado;
        try {
            baixado = cliente.buscarManifesto(null);
        } catch (ClienteRelease.ReleaseIndisponivelException e) {
            log.info("Release v{} indisponível ({}); sem instalador anterior por enquanto", versaoAtual, e.getMessage());
            return Optional.empty();
        }
        if (baixado.isEmpty()) {
            return Optional.empty();
        }
        // base 0.0.0: qualquer versão publicada passa pela cadeia (assinatura → parse → kid → artefato); a IGUALDADE é conferida aqui
        VerificadorAtualizacao.Avaliacao a = new VerificadorAtualizacao(chaves, "0.0.0", osName, osArch, ManifestoRelease.FormatoInstalado.INSTALADOR)
                .avaliar(baixado.get().json(), baixado.get().assinatura());
        if (a.resultado() != VerificadorAtualizacao.Avaliacao.Resultado.DISPONIVEL) {
            log.warn("latest.json da release v{} recusado ({}); sem instalador anterior", versaoAtual, a.motivo());
            return Optional.empty();
        }
        ManifestoRelease m = a.manifesto().orElseThrow();
        if (!m.versao().equals(versaoAtual)) { // igualdade EXATA: '1.1.0-rc1' não serve de anterior da '1.1.0' (comparar() ignora o sufixo)
            log.warn("latest.json da release v{} declara {}; não serve como instalador anterior", versaoAtual, m.versao());
            return Optional.empty();
        }
        ManifestoRelease.Artefato art = a.artefato().orElseThrow();
        Path destino = pasta.resolve(art.arquivo());
        try {
            cliente.baixarArtefato(art, destino);
            EscritaAtomica.gravarTexto(pasta.resolve(MARCADOR), versaoAtual + "\n" + art.arquivo() + "\n" + art.sha256().toLowerCase(java.util.Locale.ROOT) + "\n", false);
        } catch (ClienteRelease.ReleaseIndisponivelException | IOException e) {
            log.warn("Não consegui guardar o instalador da versão {}: {}", versaoAtual, e.getMessage());
            return Optional.empty();
        }
        log.info("Instalador da versão atual ({}) guardado para rollback em {}", versaoAtual, destino);
        return Optional.of(destino);
    }

    /**
     * O instalador guardado da {@code versao}, só se o {@code versao.txt} disser essa versão, o arquivo existir e o sha256 bater.
     * É por aqui que o rollback (InstaladorMsi/ReversorMsiDeFora) acha o que executar.
     */
    public static Optional<Path> guardado(Path pasta, String versao) {
        Path marcador = pasta.resolve(MARCADOR);
        if (versao == null || !Files.isRegularFile(marcador)) {
            return Optional.empty();
        }
        try {
            List<String> linhas = Files.readAllLines(marcador, StandardCharsets.UTF_8);
            if (linhas.size() < 3 || !linhas.get(0).trim().equals(versao)) {
                return Optional.empty();
            }
            String nome = linhas.get(1).trim();
            if (nome.isEmpty() || nome.contains("/") || nome.contains("\\") || nome.contains("..")) {
                return Optional.empty();
            }
            Path exe = pasta.resolve(nome);
            if (Files.isRegularFile(exe) && sha256(exe).equals(linhas.get(2).trim())) {
                return Optional.of(exe);
            }
        } catch (IOException | RuntimeException e) {
            log.debug("marcador do instalador anterior ilegível: {}", e.toString());
        }
        return Optional.empty();
    }

    /**
     * O sha256 (hex) do instalador guardado e CONFERIDO da {@code versao} — vai no plano de reversão para o atualizador de fora
     * reconferir o arquivo na hora de executar (Fecho F6: entre gravar o plano e o msiexec há a saída do agente e a espera da trava).
     */
    public static Optional<String> shaGuardado(Path pasta, String versao) {
        if (guardado(pasta, versao).isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readAllLines(pasta.resolve(MARCADOR), StandardCharsets.UTF_8).get(2).trim());
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Só ARQUIVOS da pasta (uma subpasta alheia ali não é nossa para apagar). Falha ao apagar não impede a guarda. */
    private void esvaziar() {
        if (!Files.isDirectory(pasta)) {
            return;
        }
        try (Stream<Path> s = Files.list(pasta)) {
            for (Path p : s.toList()) {
                if (Files.isRegularFile(p)) {
                    Files.deleteIfExists(p);
                }
            }
        } catch (IOException e) {
            log.warn("Não consegui limpar {}: {}", pasta, e.toString());
        }
    }

    private static String sha256(Path arquivo) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(arquivo)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
