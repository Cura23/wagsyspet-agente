package br.com.wagner.wagsyspet.agente.protocolo.ticket;

/** Recusa explícita do ticket, com motivo tipado (o agente devolve o código ao PWA e loga; nunca engole). */
public final class TicketInvalidoException extends Exception {

    public enum Motivo {
        /** Não tem a forma {@code v1.payload.assinatura}, tamanho absurdo, base64 inválido, JSON inválido ou null. */
        FORMATO,
        /** Prefixo de versão desconhecido — o verificador só conhece {@code v1}. */
        VERSAO,
        /** Assinatura Ed25519 não bate com a chave pública pareada (backend falso ou payload adulterado). */
        ASSINATURA,
        /** Payload assinado, mas sem um campo obrigatório ou com tipo errado. */
        CAMPO_AUSENTE,
        /** {@code exp} passou (além da tolerância de relógio). */
        EXPIRADO,
        /**
         * Validade implausível: {@code exp − iat} acima do teto ou {@code exp} muito além do relógio do agente (bug de
         * unidade ms/s no emissor, ticket "eterno"). Recusado mesmo com assinatura legítima.
         */
        VALIDADE_ABSURDA,
        /** Ticket de outra loja. */
        LOJA_DIVERGENTE,
        /** Ticket para outro caixa (outro agenteId). */
        AGENTE_DIVERGENTE,
        /** Mesmo {@code jti} já aceito antes (replay). */
        REPETIDO
    }

    private final Motivo motivo;

    public TicketInvalidoException(Motivo motivo, String detalhe) {
        super(motivo + ": " + detalhe);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
