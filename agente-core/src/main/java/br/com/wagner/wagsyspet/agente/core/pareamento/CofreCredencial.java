package br.com.wagner.wagsyspet.agente.core.pareamento;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * Guarda o {@link Pareamento} cifrado em {@code pareamento.enc} com AES-256-GCM e uma chave aleatória em {@code chave.bin}
 * (0600), na pasta do agente (plano F3 D15).
 *
 * <p><b>Honesto sobre o que protege:</b> a chave mora ao lado do arquivo, então quem lê como o MESMO usuário do SO lê os
 * dois. O que isto evita é leitura casual/backup/sync em claro e adulteração silenciosa (GCM autentica: um byte trocado é
 * detectado e o arquivo vira "corrompido", nunca meio-válido). O conteúdo em si não é segredo forte: agenteId e chave
 * pública são públicos e a {@code credencial} não tem consumidor no backend F1; a barreira real é a revogação no servidor.
 * DPAPI/Keychain/libsecret ficaram fora (Keychain com assinatura ad-hoc pede permissão a cada build).</p>
 *
 * <p>Layout: {@code magic(8) | versao(1) | iv(12) | ciphertext+tag}. AAD = magic+versao. Escrita atômica (tmp + move).</p>
 */
public final class CofreCredencial {

    private static final Logger log = LoggerFactory.getLogger(CofreCredencial.class);
    private static final byte[] MAGIC = "AGROEAG1".getBytes(StandardCharsets.US_ASCII);
    private static final byte VERSAO = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int CHAVE_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final DiretoriosDoAgente dirs;

    public CofreCredencial(DiretoriosDoAgente dirs) {
        this.dirs = Objects.requireNonNull(dirs);
    }

    /** Cifra e grava (re-parear sobrescreve). Cria a chave no 1º uso. */
    public synchronized void gravar(Pareamento p) throws IOException {
        dirs.garantir();
        SecretKey chave = chaveOuNova();
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        byte[] claro = p.paraJson().getBytes(StandardCharsets.UTF_8);
        byte[] cifrado;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad());
            cifrado = c.doFinal(claro);
        } catch (GeneralSecurityException e) {
            throw new IOException("falha ao cifrar o pareamento", e);
        }
        byte[] arquivo = new byte[MAGIC.length + 1 + IV_BYTES + cifrado.length];
        int pos = 0;
        System.arraycopy(MAGIC, 0, arquivo, pos, MAGIC.length);
        pos += MAGIC.length;
        arquivo[pos++] = VERSAO;
        System.arraycopy(iv, 0, arquivo, pos, IV_BYTES);
        pos += IV_BYTES;
        System.arraycopy(cifrado, 0, arquivo, pos, cifrado.length);
        gravarAtomico(dirs.pareamento(), arquivo);
        log.info("Pareamento gravado em {} (loja {}, agenteId {}, chave do ticket {})", dirs.pareamento(), p.lojaId(), p.agenteId(), p.fingerprintChave());
    }

    /**
     * Vazio se não pareado, se o arquivo estiver ILEGÍVEL neste instante (permissão negada, arquivo travado por antivírus — só
     * aviso, NADA é movido: é transitório e um {@code --status} não pode destruir a credencial — adversarial F3 L3-A1) ou se
     * estiver CORROMPIDO (bytes lidos mas cabeçalho/GCM/JSON inválidos, ou chave.bin ausente → renomeado para
     * {@code *.corrompido-<instante>}). Nunca lança.
     */
    public synchronized Optional<Pareamento> ler() {
        Path arq = dirs.pareamento();
        if (!Files.exists(arq)) {
            return Optional.empty();
        }
        byte[] bytes;
        byte[] chaveBytes = null;
        try {
            bytes = Files.readAllBytes(arq);
            if (Files.exists(dirs.chave())) {
                chaveBytes = lerChave();
            }
        } catch (IOException e) {
            // fase de LEITURA: não decide corrupção
            log.warn("pareamento.enc/chave.bin ilegível agora ({}); seguindo NÃO pareado nesta execução, sem mexer nos arquivos", e.toString());
            return Optional.empty();
        }
        try {
            if (bytes.length < MAGIC.length + 1 + IV_BYTES + 16 || !Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length)
                    || bytes[MAGIC.length] != VERSAO) {
                throw new IOException("cabeçalho desconhecido");
            }
            if (chaveBytes == null) {
                throw new IOException("chave.bin ausente");
            }
            SecretKey chave = new SecretKeySpec(chaveBytes, "AES");
            byte[] iv = Arrays.copyOfRange(bytes, MAGIC.length + 1, MAGIC.length + 1 + IV_BYTES);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, chave, new GCMParameterSpec(TAG_BITS, iv));
            c.updateAAD(aad());
            byte[] claro = c.doFinal(bytes, MAGIC.length + 1 + IV_BYTES, bytes.length - MAGIC.length - 1 - IV_BYTES);
            return Optional.of(Pareamento.deJson(new String(claro, StandardCharsets.UTF_8)));
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            log.warn("pareamento.enc ilegível ({}); movendo de lado e seguindo NÃO pareado", e.toString());
            moverCorrompido(arq);
            return Optional.empty();
        }
    }

    /** Desparear localmente: apaga pareamento e chave; {@code config.json} (impressora) fica. Idempotente. */
    public synchronized void apagar() throws IOException {
        Files.deleteIfExists(dirs.pareamento());
        Files.deleteIfExists(dirs.chave());
        log.info("Pareamento apagado de {}", dirs.raiz());
    }

    public boolean pareado() {
        return ler().isPresent();
    }

    private static byte[] aad() {
        byte[] aad = Arrays.copyOf(MAGIC, MAGIC.length + 1);
        aad[MAGIC.length] = VERSAO;
        return aad;
    }

    private SecretKey chaveOuNova() throws IOException {
        if (Files.exists(dirs.chave())) {
            byte[] k = lerChave();
            if (k.length == CHAVE_BYTES) {
                return new SecretKeySpec(k, "AES");
            }
            log.warn("chave.bin com tamanho inesperado ({} bytes); gerando outra", k.length);
        }
        byte[] k = new byte[CHAVE_BYTES];
        RANDOM.nextBytes(k);
        gravarAtomico(dirs.chave(), k);
        return new SecretKeySpec(k, "AES");
    }

    private byte[] lerChave() throws IOException {
        return Files.readAllBytes(dirs.chave());
    }

    private static void gravarAtomico(Path destino, byte[] conteudo) throws IOException {
        br.com.wagner.wagsyspet.agente.core.atualizacao.EscritaAtomica.gravar(destino, conteudo, true); // 0600: é segredo
    }

    private static void moverCorrompido(Path arq) {
        try {
            Path destino = arq.resolveSibling(arq.getFileName() + ".corrompido-" + Instant.now().toEpochMilli());
            Files.move(arq, destino, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("não consegui mover o arquivo corrompido: {}", e.toString());
        }
    }
}
