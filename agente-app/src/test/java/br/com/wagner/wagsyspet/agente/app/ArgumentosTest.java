package br.com.wagner.wagsyspet.agente.app;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Argumentos — linha de comando do binário (plano F3 D18)")
class ArgumentosTest {

    @Test
    @DisplayName("sem argumentos → SERVIR; -D… no 1º argumento → DEV (compatível com o F0)")
    void padrao() {
        assertThat(Argumentos.parse(new String[0]).comando()).isEqualTo(Argumentos.Comando.SERVIR);
        Argumentos dev = Argumentos.parse(new String[]{"-Dagente.porta=28421", "-Dagente.origins=http://localhost:5173"});
        assertThat(dev.comando()).isEqualTo(Argumentos.Comando.DEV);
        assertThat(dev.posicionais()).hasSize(2);
    }

    @Test
    @DisplayName("--parear <codigo> --backend-url <url>: comando, posicional e opção; ordem das opções não importa")
    void parear() {
        Argumentos a = Argumentos.parse(new String[]{"--parear", "ABC", "--backend-url", "http://localhost:9090"});
        assertThat(a.comando()).isEqualTo(Argumentos.Comando.PAREAR);
        assertThat(a.posicionais()).containsExactly("ABC");
        assertThat(a.opcao(Argumentos.OPT_BACKEND)).isEqualTo("http://localhost:9090");
        assertThat(a.erros()).isEmpty();

        Argumentos b = Argumentos.parse(new String[]{"--backend-url=http://localhost:9090", "--parear", "ABC", "--verboso"});
        assertThat(b.opcao(Argumentos.OPT_BACKEND)).isEqualTo("http://localhost:9090");
        assertThat(b.flag(Argumentos.FLAG_VERBOSO)).isTrue();
        assertThat(b.erros()).isEmpty();
    }

    @Test
    @DisplayName("--imprimir-teste exige 2 posicionais; faltando ou sobrando → erro acumulado, nunca exceção")
    void posicionais() {
        assertThat(Argumentos.parse(new String[]{"--imprimir-teste", "PDF"}).erros()).anyMatch(e -> e.contains("exige 2"));
        assertThat(Argumentos.parse(new String[]{"--status", "sobra"}).erros()).anyMatch(e -> e.contains("inesperado"));
        assertThat(Argumentos.parse(new String[]{"--imprimir-teste", "PDF", "x.pdf"}).erros()).isEmpty();
    }

    @Test
    @DisplayName("opções: --porta validada, --dir-dados, --sem-bandeja; valor faltando e opção desconhecida viram erro")
    void opcoes() {
        Argumentos a = Argumentos.parse(new String[]{"--porta", "28422", "--dir-dados", "/tmp/x", "--sem-bandeja"});
        assertThat(a.comando()).isEqualTo(Argumentos.Comando.SERVIR);
        assertThat(a.porta()).isEqualTo(28422);
        assertThat(a.opcao(Argumentos.OPT_DIR)).isEqualTo("/tmp/x");
        assertThat(a.flag(Argumentos.FLAG_SEM_BANDEJA)).isTrue();
        assertThat(a.erros()).isEmpty();

        assertThat(Argumentos.parse(new String[]{"--porta", "abc"}).erros()).anyMatch(e -> e.contains("número"));
        assertThat(Argumentos.parse(new String[]{"--porta", "70000"}).erros()).anyMatch(e -> e.contains("1..65535"));
        assertThat(Argumentos.parse(new String[]{"--porta"}).erros()).anyMatch(e -> e.contains("exige um valor"));
        assertThat(Argumentos.parse(new String[]{"--formatar-disco"}).erros()).anyMatch(e -> e.contains("desconhecida"));
        assertThat(Argumentos.parse(new String[]{"--status", "--versao"}).erros()).anyMatch(e -> e.contains("só um comando"));
    }

    @Test
    @DisplayName("--ajuda/-h/--help e --version são reconhecidos")
    void ajuda() {
        for (String h : new String[]{"--ajuda", "-h", "--help"}) {
            assertThat(Argumentos.parse(new String[]{h}).comando()).isEqualTo(Argumentos.Comando.AJUDA);
        }
        assertThat(Argumentos.parse(new String[]{"--version"}).comando()).isEqualTo(Argumentos.Comando.VERSAO);
        assertThat(Argumentos.USO).contains("--parear").contains("--status").doesNotContainIgnoringCase("wagsyspet");
    }
}
