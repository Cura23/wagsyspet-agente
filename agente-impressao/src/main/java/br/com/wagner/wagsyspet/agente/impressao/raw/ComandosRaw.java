package br.com.wagner.wagsyspet.agente.impressao.raw;

import java.util.Objects;

/**
 * Catálogo FECHADO dos únicos bytes não fiscais que o agente manda à impressora (plano F6 D10): abrir gaveta e cortar papel. É a
 * whitelist do canal RAW — o protocolo transporta um ENUM, jamais bytes; nenhum método aqui recebe bytes de fora. Com RAW o driver não
 * participa (Windows {@code pDatatype=RAW}; CUPS {@code application/vnd.cups-raw}): o que sair daqui chega cru ao dispositivo.
 * <p>NUNCA entram no catálogo: {@code ESC @} (limpa o buffer de impressão — comeria o fim do cupom anterior), {@code GS (}/{@code FS (}/
 * {@code GS F9h} (configuração, memória NV, troca de tabela de comandos) e {@code DLE DC4} (tempo real: a impressora executaria FORA
 * de ordem). O teste de propriedade varre todo o catálogo atrás desses prefixos.
 * <p>O DANFCe/cupom é o PDF do backend e NUNCA é tocado: gaveta e corte são jobs SEPARADOS na mesma fila.
 */
public final class ComandosRaw {

    /**
     * Tabela de comandos da impressora. {@code ESCPOS}: Epson, Elgin, e Bematech/Daruma configuradas em ESC/POS. {@code ESC_BEMA}:
     * Bematech como sai de fábrica — lá {@code ESC p} e {@code GS V} não existem, e mandar um dialeto ao outro imprime caracteres espúrios.
     */
    public enum Dialeto { ESCPOS, ESC_BEMA }

    public static final int PULSO_MINIMO_MS = 50;
    public static final int PULSO_MAXIMO_MS = 250;
    public static final int PULSO_PADRAO_MS = 50;
    public static final int PINO_PADRAO = 2;

    private ComandosRaw() {
    }

    /**
     * @param pino    conector da gaveta: 2 (o usual) ou 5 — ESC/Bema só tem a gaveta 1 e ignora o pino
     * @param pulsoMs largura do pulso do solenoide, {@value #PULSO_MINIMO_MS}–{@value #PULSO_MAXIMO_MS} ms
     */
    public static byte[] abrirGaveta(Dialeto dialeto, int pino, int pulsoMs) {
        Objects.requireNonNull(dialeto, "dialeto");
        if (pino != 2 && pino != 5) {
            throw new IllegalArgumentException("pino da gaveta deve ser 2 ou 5: " + pino);
        }
        if (pulsoMs < PULSO_MINIMO_MS || pulsoMs > PULSO_MAXIMO_MS) {
            throw new IllegalArgumentException("pulso da gaveta fora de " + PULSO_MINIMO_MS + "–" + PULSO_MAXIMO_MS + " ms: " + pulsoMs);
        }
        return switch (dialeto) {
            // ESC p m t1 t2 — ON = t1×2 ms; OFF = t2×2 ms (250 → 500 ms, o valor consagrado)
            case ESCPOS -> new byte[]{0x1B, 0x70, (byte) (pino == 2 ? 0 : 1), (byte) (pulsoMs / 2), (byte) 0xFA};
            // ESC v n — n em ms
            case ESC_BEMA -> new byte[]{0x1B, 0x76, (byte) pulsoMs};
        };
    }

    /** Corte PARCIAL (um ponto preso: o cupom não cai no chão). O {@code LF} na frente garante início de linha, exigido pelo {@code GS V}. */
    public static byte[] cortar(Dialeto dialeto) {
        Objects.requireNonNull(dialeto, "dialeto");
        return switch (dialeto) {
            case ESCPOS -> new byte[]{0x0A, 0x1D, 0x56, 0x42, 0x00}; // GS V 66 0: alimenta até a guilhotina (≈14 mm) e corta
            case ESC_BEMA -> new byte[]{0x0A, 0x1B, 0x6D};           // ESC m
        };
    }
}
