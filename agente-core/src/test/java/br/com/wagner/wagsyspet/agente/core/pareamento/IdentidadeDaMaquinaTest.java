package br.com.wagner.wagsyspet.agente.core.pareamento;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plano F3 D14 — o que vai no corpo do /parear: so ≤ 20, arch ≤ 20, hostname ≤ 120, sem caracteres de controle. */
@DisplayName("IdentidadeDaMaquina — so/arch/hostname nos limites do backend")
class IdentidadeDaMaquinaTest {

    @Test
    @DisplayName("so normalizado para windows|linux|macos (o painel do dono rotula por regex); desconhecido vai cru e curto")
    void so() {
        assertThat(IdentidadeDaMaquina.normalizarSo("Windows 11")).isEqualTo("windows");
        assertThat(IdentidadeDaMaquina.normalizarSo("Linux")).isEqualTo("linux");
        assertThat(IdentidadeDaMaquina.normalizarSo("Mac OS X")).isEqualTo("macos");
        assertThat(IdentidadeDaMaquina.normalizarSo("FreeBSD com nome comprido demais para o campo")).hasSizeLessThanOrEqualTo(20);
        assertThat(IdentidadeDaMaquina.normalizarSo(null)).isEqualTo("desconhecido");
    }

    @Test
    @DisplayName("arch: amd64/x86_64 → x64; aarch64/arm64 → arm64; outro vai cru e curto")
    void arch() {
        assertThat(IdentidadeDaMaquina.normalizarArch("amd64")).isEqualTo("x64");
        assertThat(IdentidadeDaMaquina.normalizarArch("x86_64")).isEqualTo("x64");
        assertThat(IdentidadeDaMaquina.normalizarArch("aarch64")).isEqualTo("arm64");
        assertThat(IdentidadeDaMaquina.normalizarArch("arm64")).isEqualTo("arm64");
        assertThat(IdentidadeDaMaquina.normalizarArch("riscv64")).isEqualTo("riscv64");
        assertThat(IdentidadeDaMaquina.normalizarArch(null)).isNull();
    }

    @Test
    @DisplayName("hostname: COMPUTERNAME → HOSTNAME → fallback; sem controle/CRLF (forjaria log do backend), ≤ 120, vazio → null")
    void hostname() {
        assertThat(IdentidadeDaMaquina.hostnameDe(Map.of("COMPUTERNAME", "CAIXA-01", "HOSTNAME", "outro"), () -> "fallback")).isEqualTo("CAIXA-01");
        assertThat(IdentidadeDaMaquina.hostnameDe(Map.of("HOSTNAME", "caixa-linux"), () -> "fallback")).isEqualTo("caixa-linux");
        assertThat(IdentidadeDaMaquina.hostnameDe(Map.of(), () -> "do-inetaddress")).isEqualTo("do-inetaddress");
        assertThat(IdentidadeDaMaquina.hostnameDe(Map.of(), () -> { throw new RuntimeException("sem rede"); })).isNull();
        assertThat(IdentidadeDaMaquina.hostnameDe(Map.of("COMPUTERNAME", "CAIXA\r\nFORJADO\tx"), () -> null)).isEqualTo("CAIXAFORJADOx");
        assertThat(IdentidadeDaMaquina.hostnameDe(Map.of("COMPUTERNAME", "a".repeat(300)), () -> null)).hasSize(120);
        assertThat(IdentidadeDaMaquina.hostnameDe(Map.of("COMPUTERNAME", "   "), () -> null)).isNull();
    }
}
