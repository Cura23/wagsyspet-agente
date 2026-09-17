package br.com.wagner.wagsyspet.agente.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fecho F6 — durante a atualização quem segura a trava de instância é o ATUALIZADOR. O lojista que abre o agente nessa hora
 * (o PDV disse "agente não encontrado") não pode ler "procure o ícone na bandeja": não há ícone — o agente saiu para atualizar.
 */
@DisplayName("Main — mensagem da 2ª instância: outro agente aberto × atualização em curso")
class MainSegundaInstanciaTest {

    @Test
    @DisplayName("com o plano de atualização presente: 'está se atualizando… volta sozinho em até 1 minuto'; sem ele: 'já está em execução'. Sempre AgroEase, nunca WagSysPet")
    void mensagens() {
        String atualizando = Main.mensagemDeSegundaInstancia(true);
        assertThat(atualizando).contains("se atualizando").contains("1 minuto").doesNotContain("bandeja");
        String aberto = Main.mensagemDeSegundaInstancia(false);
        assertThat(aberto).contains("já está em execução").contains("bandeja");
        assertThat(atualizando + aberto).contains("AgroEase").doesNotContainIgnoringCase("wagsyspet");
    }
}
