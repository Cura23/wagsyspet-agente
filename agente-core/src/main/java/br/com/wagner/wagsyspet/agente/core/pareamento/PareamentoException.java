package br.com.wagner.wagsyspet.agente.core.pareamento;

/**
 * Falha ao parear, já traduzida para o lojista (mensagem pt-BR) e com o que o suporte precisa saber: se o código de
 * pareamento <b>continua válido</b> (pode tentar de novo com o mesmo) ou se precisa gerar outro no painel.
 */
public final class PareamentoException extends Exception {

    public enum Motivo {
        /** Não tem a forma de um código do painel (43 caracteres) — nem chamamos o servidor. */
        CODIGO_MAL_FORMADO,
        /** 404: inexistente, já usado, expirado ou revogado — gerar outro no painel. */
        CODIGO_INVALIDO,
        /** 426: o agente precisa ser atualizado; o código continua válido. */
        VERSAO_OBSOLETA,
        /** 429: bucket de login por IP; esperar {@link #retryAfterSegundos()}; sem retry automático. */
        LIMITE_TENTATIVAS,
        /** 503 AGENTE_NAO_CONFIGURADO: servidor sem a chave do ticket — suporte. */
        SERVIDOR_NAO_CONFIGURADO,
        /** 400: o corpo que mandamos foi recusado (hostname estranho etc.). */
        REQUISICAO_INVALIDA,
        /** 5xx ou HTML de proxy: o servidor falhou; o código PODE ter sido gasto. */
        SERVIDOR,
        /** 200 (ou 3xx) com corpo fora do contrato — portal cativo, proxy, resposta incompleta. */
        RESPOSTA_INVALIDA,
        /** Sem conexão, DNS, TLS ou prazo estourado. */
        REDE
    }

    private final Motivo motivo;
    private final boolean codigoContinuaValido;
    private final Integer retryAfterSegundos;
    private final String versaoMinima;

    public PareamentoException(Motivo motivo, String mensagem, boolean codigoContinuaValido) {
        this(motivo, mensagem, codigoContinuaValido, null, null, null);
    }

    public PareamentoException(Motivo motivo, String mensagem, boolean codigoContinuaValido, Integer retryAfterSegundos,
                               String versaoMinima, Throwable causa) {
        super(mensagem, causa);
        this.motivo = motivo;
        this.codigoContinuaValido = codigoContinuaValido;
        this.retryAfterSegundos = retryAfterSegundos;
        this.versaoMinima = versaoMinima;
    }

    public Motivo motivo() {
        return motivo;
    }

    /** true = pode tentar de novo com o MESMO código; false = gerar outro no painel (ou não dá para saber). */
    public boolean codigoContinuaValido() {
        return codigoContinuaValido;
    }

    public Integer retryAfterSegundos() {
        return retryAfterSegundos;
    }

    public String versaoMinima() {
        return versaoMinima;
    }
}
