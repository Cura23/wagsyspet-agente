package br.com.wagner.wagsyspet.agente.protocolo.release;

/** {@code latest.json} que não passa no parser estrito — o motivo é whitelist para log/diagnóstico, nunca texto livre do arquivo. */
public final class ManifestoInvalidoException extends Exception {

    public enum Motivo { TAMANHO, JSON, FORMATO_NAO_SUPORTADO, VERSAO, PROTOCOLO, KID, ARTEFATO }

    private final Motivo motivo;

    public ManifestoInvalidoException(Motivo motivo, String detalhe) {
        super(motivo + ": " + detalhe);
        this.motivo = motivo;
    }

    public Motivo motivo() {
        return motivo;
    }
}
