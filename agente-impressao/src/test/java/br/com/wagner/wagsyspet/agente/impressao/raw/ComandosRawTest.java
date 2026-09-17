package br.com.wagner.wagsyspet.agente.impressao.raw;

import br.com.wagner.wagsyspet.agente.impressao.raw.ComandosRaw.Dialeto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plano F6 D10 — catálogo FECHADO de bytes não fiscais. O protocolo transporta um ENUM, jamais bytes: com RAW o driver não protege
 * nada, e uma página comprometida que pudesse mandar bytes imprimiria um "cupom" falso no papel do DANFCe. Bytes conferidos nos manuais
 * (Epson ESC/POS Command Reference: ESC p / GS V; Bematech MP-4200 TH Programmer's Manual: ESC v / ESC m).
 */
@DisplayName("ComandosRaw — golden bytes da gaveta e do corte (ESC/POS e ESC/Bema) e a lista do que NUNCA pode sair")
class ComandosRawTest {

    private static String hex(byte[] b) {
        return HexFormat.ofDelimiter(" ").withUpperCase().formatHex(b);
    }

    @Test
    @DisplayName("ESC/POS: gaveta = ESC p m t1 t2 (m = pino 2→0 / pino 5→1; t1 = ms/2; t2 = 250 → 500 ms OFF); corte = LF + GS V 66 0 (o LF garante início de linha; a Função B alimenta até a guilhotina e corta parcial — GS V 1 puro cortaria as últimas linhas)")
    void escPos() {
        assertThat(hex(ComandosRaw.abrirGaveta(Dialeto.ESCPOS, 2, 50))).isEqualTo("1B 70 00 19 FA");
        assertThat(hex(ComandosRaw.abrirGaveta(Dialeto.ESCPOS, 5, 100))).isEqualTo("1B 70 01 32 FA");
        assertThat(hex(ComandosRaw.abrirGaveta(Dialeto.ESCPOS, 2, 250))).isEqualTo("1B 70 00 7D FA");
        assertThat(hex(ComandosRaw.cortar(Dialeto.ESCPOS))).isEqualTo("0A 1D 56 42 00");
    }

    @Test
    @DisplayName("ESC/Bema (Bematech de fábrica — lá ESC p e GS V NÃO existem): gaveta = ESC v <ms>; corte = LF + ESC m (parcial). Misturar dialetos imprime caracteres espúrios: por isso é enum, nunca 'os dois para garantir'")
    void escBema() {
        assertThat(hex(ComandosRaw.abrirGaveta(Dialeto.ESC_BEMA, 2, 100))).isEqualTo("1B 76 64");
        assertThat(hex(ComandosRaw.abrirGaveta(Dialeto.ESC_BEMA, 2, 50))).isEqualTo("1B 76 32");
        assertThat(hex(ComandosRaw.cortar(Dialeto.ESC_BEMA))).isEqualTo("0A 1B 6D");
    }

    @Test
    @DisplayName("faixas: pulso 50–250 ms; pino 2 ou 5; fora disso IllegalArgumentException (nada de byte calculado a partir de lixo)")
    void faixas() {
        assertThatThrownBy(() -> ComandosRaw.abrirGaveta(Dialeto.ESCPOS, 2, 49)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComandosRaw.abrirGaveta(Dialeto.ESCPOS, 2, 251)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComandosRaw.abrirGaveta(Dialeto.ESCPOS, 3, 50)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ComandosRaw.abrirGaveta(null, 2, 50)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("PROPRIEDADE: nenhum comando do catálogo (todo dialeto × pino × pulso) contém ESC @ (limpa o buffer: comeria o rabo do cupom anterior), GS ( / FS ( / GS F9 (configuração, NV, troca de modo) nem DLE DC4 (tempo real: sairia FORA de ordem)")
    void nuncaSai() {
        List<byte[]> todos = new ArrayList<>();
        for (Dialeto d : Dialeto.values()) {
            todos.add(ComandosRaw.cortar(d));
            for (int pino : new int[]{2, 5}) {
                for (int ms = 50; ms <= 250; ms++) {
                    todos.add(ComandosRaw.abrirGaveta(d, pino, ms));
                }
            }
        }
        byte[][] proibidos = {{0x1B, 0x40}, {0x1D, 0x28}, {0x1C, 0x28}, {0x1D, (byte) 0xF9}, {0x10, 0x14}};
        for (byte[] comando : todos) {
            assertThat(comando.length).as("comandos curtos: só gaveta e corte").isLessThanOrEqualTo(5);
            for (byte[] p : proibidos) {
                for (int i = 0; i + 1 < comando.length; i++) {
                    assertThat(comando[i] == p[0] && comando[i + 1] == p[1]).as("%s contém %s", hex(comando), hex(p)).isFalse();
                }
            }
        }
    }
}
