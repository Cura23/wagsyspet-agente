package br.com.wagner.wagsyspet.agente.app.ui;

/** Superfície visual do agente (bandeja ou janela): recebe o estado do orquestrador. Thread-safe (despacha para a EDT). */
public interface Superficie {

    void estado(String titulo, String detalhe, boolean pareado);

    void aviso(String titulo, String mensagem);

    void erro(String titulo, String mensagem);

    /** Bloqueia até o lojista fechar o diálogo — usado ANTES de encerrar o processo (um erro assíncrono seguido de exit nunca era visto). */
    void erroFatal(String titulo, String mensagem);

    /** Versão nova baixada e pronta (habilita "Atualizar agora"); vazio = nada pendente. Default: superfícies antigas ignoram. */
    default void atualizacao(java.util.Optional<String> versaoDisponivel) {
    }
}
