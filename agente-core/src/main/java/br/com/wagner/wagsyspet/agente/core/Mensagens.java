package br.com.wagner.wagsyspet.agente.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Frames que o agente ENVIA, exatamente como o parser do PWA ({@code parseMsgDoAgente}) espera: {@code protocolo} é o único
 * número; {@code id}, {@code estado}, {@code codigo}, {@code selecionada}, {@code agenteVersao}, {@code agenteId} são
 * strings; {@code nomes} é array; {@code selecionada} pode ser {@code null}. Campo faltante ou tipo errado = o PWA descarta
 * o frame em silêncio e o operador vê "sem resposta" — por isso tudo passa por aqui, e o teste de contrato confere os tipos.
 */
final class Mensagens {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** id de correlação aceito (o PWA usa UUID ou base36-base36); fora disso o frame é inválido (plano F3 D7). */
    static final Pattern ID = Pattern.compile("[A-Za-z0-9_.:-]{1,64}");

    // códigos de erro que o PWA conhece (agenteProtocolo.ts / mensagensImpressao.ts)
    static final String NAO_AUTENTICADO = "NAO_AUTENTICADO";
    static final String TICKET_INVALIDO = "TICKET_INVALIDO";
    static final String OCUPADO = "OCUPADO";
    static final String NAO_PAREADO = "NAO_PAREADO";
    static final String TIPO_DESCONHECIDO = "TIPO_DESCONHECIDO";
    static final String MENSAGEM_INVALIDA = "MENSAGEM_INVALIDA";
    static final String TIPO_BINARIO_NAO_SUPORTADO = "TIPO_BINARIO_NAO_SUPORTADO";
    static final String IMPRESSORA_INDISPONIVEL = "IMPRESSORA_INDISPONIVEL";
    static final String ERRO = "ERRO";
    static final String ACEITO_SPOOLER = "ACEITO_SPOOLER";

    private Mensagens() {
    }

    static boolean idValido(String id) {
        return id != null && ID.matcher(id).matches();
    }

    static String helloOk(InfoAgente info) {
        ObjectNode n = base("hello_ok");
        n.put("agenteVersao", info.versao());
        n.put("protocolo", info.protocolo());
        n.put("so", info.sistemaOperacional());
        n.put("agenteId", info.agenteId());
        // F6 D6: o que ESTE agente sabe além do contrato F2/F3 (o PWA antigo ignora o campo; o novo evita perguntar a quem não sabe)
        var capacidades = n.putArray("capacidades");
        CAPACIDADES.forEach(capacidades::add);
        return n.toString();
    }

    /** {@code comando_raw}: {@code comando{ABRIR_GAVETA|CORTAR}}, {@code imprimir{gaveta}} e {@code extras} na seleção da impressora. */
    /** {@code estado_impressao}: push {@code impressao_estado} + pull {@code consultar_impressao}; {@code atualizacao}: fecha 1001 'ATUALIZANDO'. */
    static final String ORIGEM_PUSH = "push";
    static final String ORIGEM_CONSULTA = "consulta";

    static final java.util.List<String> CAPACIDADES = java.util.List.of("estado_impressao", "atualizacao", "comando_raw");
    /** {@code comando} pedido com o opt-in desligado neste computador (ou para esta impressora). */
    static final String COMANDO_DESABILITADO = "COMANDO_DESABILITADO";
    static final String AVISO_GAVETA_FALHOU = "GAVETA_FALHOU";
    static final String AVISO_CORTE_FALHOU = "CORTE_FALHOU";

    /**
     * {@code impressao_estado{id, origem:'push'|'consulta', estado, motivo?, detalhe, encerrado}} — o que o SPOOLER disse do job depois do aceite (F6 D9).
     * {@code motivo} só quando existe (o parser do PWA é por tipo: nada de null no fio).
     */
    static String impressaoEstado(String id, br.com.wagner.wagsyspet.agente.impressao.spooler.EstadoSpooler e, String origem) {
        ObjectNode n = base("impressao_estado");
        n.put("id", id);
        n.put("origem", origem); // push e resposta de consulta são o mesmo tipo com o mesmo id: é isto que os distingue no PWA
        n.put("estado", e.estado().name());
        if (e.motivo() != null) {
            n.put("motivo", e.motivo().name());
        }
        n.put("detalhe", e.detalhe());
        n.put("encerrado", e.encerrado());
        return n.toString();
    }

    static String pong() {
        return base("pong").toString();
    }

    static String authOk() {
        return base("auth_ok").toString();
    }

    /** {@code erro{id?, codigo, mensagem, motivo?}} — {@code id} só quando a entrada tinha um válido. */
    static String erro(String id, String codigo, String mensagem, String motivo) {
        ObjectNode n = base("erro");
        if (id != null) {
            n.put("id", id);
        }
        n.put("codigo", codigo);
        n.put("mensagem", mensagem);
        if (motivo != null) {
            n.put("motivo", motivo);
        }
        return n.toString();
    }

    static String erro(String id, String codigo, String mensagem) {
        return erro(id, codigo, mensagem, null);
    }

    static String impressoras(String id, List<String> nomes, String selecionada) {
        return impressoras(id, nomes, selecionada, null);
    }

    /** {@code extras}: opt-in de gaveta/corte VALENDO para a selecionada (ausente = desligado). O parser do PWA F2 ignora campos a mais. */
    static String impressoras(String id, List<String> nomes, String selecionada, ExtrasImpressao extras) {
        ObjectNode n = base("impressoras");
        extras(n, extras);
        n.put("id", id);
        ArrayNode arr = n.putArray("nomes");
        nomes.forEach(arr::add);
        if (selecionada == null) {
            n.putNull("selecionada");
        } else {
            n.put("selecionada", selecionada);
        }
        return n.toString();
    }

    static String selecionarImpressoraOk(String id, String selecionada) {
        return selecionarImpressoraOk(id, selecionada, null);
    }

    static String comandoOk(String id) {
        ObjectNode n = base("comando_ok");
        n.put("id", id);
        return n.toString();
    }

    private static void extras(ObjectNode n, ExtrasImpressao e) {
        if (e != null) {
            n.putObject("extras").put("dialeto", e.dialeto().name()).put("gaveta", e.gaveta()).put("corte", e.corte())
                    .put("gavetaPino", e.gavetaPino()).put("gavetaPulsoMs", e.gavetaPulsoMs());
        }
    }

    static String selecionarImpressoraOk(String id, String selecionada, ExtrasImpressao extras) {
        ObjectNode n = base("selecionar_impressora_ok");
        extras(n, extras);
        n.put("id", id);
        n.put("selecionada", selecionada);
        return n.toString();
    }

    static String imprimirOk(String id) {
        return imprimirOk(id, List.of());
    }

    /** {@code avisos}: gaveta/corte que falharam — o cupom (PDF) foi aceito do mesmo jeito. Ausente quando não há. */
    static String imprimirOk(String id, List<String> avisos) {
        ObjectNode n = imprimirOkBase(id);
        if (!avisos.isEmpty()) {
            ArrayNode a = n.putArray("avisos");
            avisos.forEach(a::add);
        }
        return n.toString();
    }

    private static ObjectNode imprimirOkBase(String id) {
        ObjectNode n = base("imprimir_ok");
        n.put("id", id);
        n.put("estado", ACEITO_SPOOLER);
        return n;
    }

    static String imprimirErro(String id, String codigo, String mensagem) {
        ObjectNode n = base("imprimir_erro");
        n.put("id", id);
        n.put("codigo", codigo);
        n.put("mensagem", mensagem);
        return n.toString();
    }

    private static ObjectNode base(String tipo) {
        return JSON.createObjectNode().put("tipo", tipo);
    }
}
