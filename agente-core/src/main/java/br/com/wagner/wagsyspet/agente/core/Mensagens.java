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
        ObjectNode n = base("impressoras");
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
        ObjectNode n = base("selecionar_impressora_ok");
        n.put("id", id);
        n.put("selecionada", selecionada);
        return n.toString();
    }

    static String imprimirOk(String id) {
        ObjectNode n = base("imprimir_ok");
        n.put("id", id);
        n.put("estado", ACEITO_SPOOLER);
        return n.toString();
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
