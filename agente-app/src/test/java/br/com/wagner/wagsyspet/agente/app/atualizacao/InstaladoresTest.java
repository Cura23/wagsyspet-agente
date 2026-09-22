package br.com.wagner.wagsyspet.agente.app.atualizacao;

import br.com.wagner.wagsyspet.agente.protocolo.release.ManifestoRelease.FormatoInstalado;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fecho F6: {@code aplicaSozinho} decide se o agente pode sair por ociosidade/boot para o atualizador instalar sem ninguém na
 * frente. Só o Linux instalado pelo pacote do sistema (.deb em /opt) não pode: {@code pkexec dpkg -i} pede a senha de administrador
 * — se o agente saísse sozinho, o instalador recusaria o gatilho AUTO, o download seria apagado e o clique ficaria bloqueado por 24 h.
 */
@DisplayName("Instaladores.aplicaSozinho — só o Linux .deb exige a pessoa; Windows, macOS e o app-image do Linux aplicam sozinhos")
class InstaladoresTest {

    @Test
    @DisplayName("Linux + INSTALADOR (.deb) → false; Linux + APP_IMAGE (tar.gz no HOME) → true")
    void linuxDependeDoFormato() {
        assertThat(Instaladores.aplicaSozinho("Linux", FormatoInstalado.INSTALADOR)).isFalse();
        assertThat(Instaladores.aplicaSozinho("linux", FormatoInstalado.INSTALADOR)).as("comparação sem distinguir maiúsculas").isFalse();
        assertThat(Instaladores.aplicaSozinho("Linux", FormatoInstalado.APP_IMAGE)).isTrue();
    }

    @Test
    @DisplayName("Windows e macOS aplicam sozinhos nos dois formatos (MSI por usuário / troca do .app)")
    void windowsEMacSempreSozinhos() {
        for (String os : new String[]{"Windows 10", "Windows 11", "Mac OS X"}) {
            for (FormatoInstalado f : FormatoInstalado.values()) {
                assertThat(Instaladores.aplicaSozinho(os, f)).as(os + " + " + f).isTrue();
            }
        }
    }

    @Test
    @DisplayName("os.name ausente (null/vazio) não é Linux: aplica sozinho — nunca deixa uma instalação sem update por falta do nome do SO")
    void semNomeDoSo() {
        assertThat(Instaladores.aplicaSozinho(null, FormatoInstalado.INSTALADOR)).isTrue();
        assertThat(Instaladores.aplicaSozinho("", FormatoInstalado.INSTALADOR)).isTrue();
    }
}
