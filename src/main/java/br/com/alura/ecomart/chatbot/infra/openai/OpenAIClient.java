package br.com.alura.ecomart.chatbot.infra.openai;

import br.com.alura.ecomart.chatbot.domain.DadosCalculoFrete;
import br.com.alura.ecomart.chatbot.domain.service.CalculadorDeFrete;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.completion.chat.ChatFunction;
import com.theokanning.openai.completion.chat.ChatFunctionCall;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.messages.Message;
import com.theokanning.openai.messages.MessageRequest;
import com.theokanning.openai.runs.Run;
import com.theokanning.openai.runs.RunCreateRequest;
import com.theokanning.openai.runs.SubmitToolOutputRequestItem;
import com.theokanning.openai.runs.SubmitToolOutputsRequest;
import com.theokanning.openai.service.FunctionExecutor;
import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.threads.Thread;
import com.theokanning.openai.threads.ThreadRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.*;

@Component
public class OpenAIClient {

    private final String apiKey;
    private final String assistantId;
    private final OpenAiService service;
    private final CalculadorDeFrete calculadorDeFrete;
    private String threadId;

    public OpenAIClient(@Value("${app.openai.api.key}") String apiKey, @Value("${app.openai.assistant.id}") String assistantId, CalculadorDeFrete calculadorDeFrete) {
        this.apiKey = apiKey;
        this.service = new OpenAiService(apiKey, Duration.ofSeconds(60));
        this.assistantId = assistantId;
        this.calculadorDeFrete = calculadorDeFrete;
    }

    public String enviarRequisicaoChatCompletion(DadosRequisicaoChatCompletion dados) {

        MessageRequest messageRequest = MessageRequest
                .builder()
                .role(ChatMessageRole.USER.value())
                .content(dados.promptUsuario())
                .build();

        if (this.threadId == null) {
            ThreadRequest threadRequest = ThreadRequest
                    .builder()
                    .messages(List.of(messageRequest))
                    .build();

            Thread thread = service.createThread(threadRequest);
            this.threadId = thread.getId();
        } else {
            service.createMessage(this.threadId, messageRequest);
        }

        RunCreateRequest runRequest = RunCreateRequest
                .builder()
                .assistantId(assistantId)
                .build();

        Run run = service.createRun(threadId, runRequest);

        boolean concluido = false;
        boolean precisaChamarFuncao = false;


        try {
            while (!concluido && !precisaChamarFuncao) {
                java.lang.Thread.sleep(1000 * 10);
                run = service.retrieveRun(threadId, run.getId());
                concluido = run.getStatus().equals("completed");
                precisaChamarFuncao = run.getRequiredAction() != null;
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (precisaChamarFuncao) {
            String precoDoFrete = chamarFuncao(run);
            SubmitToolOutputsRequest submitRequest = SubmitToolOutputsRequest
                    .builder()
                    .toolOutputs(List.of(
                                    new SubmitToolOutputRequestItem(
                                            run
                                                    .getRequiredAction()
                                                    .getSubmitToolOutputs()
                                                    .getToolCalls()
                                                    .get(0)
                                                    .getId(),
                                            precoDoFrete)
                            )
                    )
                    .build();
            service.submitToolOutputs(threadId, run.getId(), submitRequest);
        }

        try {
            int tentativas = 0;
            while (!concluido) {
                java.lang.Thread.sleep(1000 * 10);
                run = service.retrieveRun(threadId, run.getId());
                concluido = run.getStatus().equalsIgnoreCase("completed");
                if(tentativas++ == 5) {
                    throw new RuntimeException("API demorando para responder. Tente novamente mais tarde");
                }
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }


        OpenAiResponse<Message> messages = service.listMessages(threadId);

        return messages.getData()
                .stream()
                .sorted(Comparator.comparingInt(Message::getCreatedAt).reversed())
                .findFirst().get().getContent().get(0).getText().getValue()
                .replaceAll("\\\u3010.*?\\\u3011", "");
    }

    private String chamarFuncao(Run run) {
        try {
            var funcao = run.getRequiredAction().getSubmitToolOutputs().getToolCalls().get(0).getFunction();
            var funcaoCalcularFrete = ChatFunction.builder()
                    .name("calcularFrete")
                    .executor(DadosCalculoFrete.class, calculadorDeFrete::calcular)
                    .build();

            var executorDeFuncoes = new FunctionExecutor(Collections.singletonList(funcaoCalcularFrete));
            var functionCall = new ChatFunctionCall(funcao.getName(), new ObjectMapper().readTree(funcao.getArguments()));
            return executorDeFuncoes.execute(functionCall).toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public List<String> carregarHistoricoDeMensagens() {
        List<String> mensagens = new ArrayList<>();

        if (this.threadId == null) {
            mensagens.addAll(
                    service.listMessages(this.threadId)
                            .getData()
                            .stream()
                            .sorted(Comparator.comparingInt(Message::getCreatedAt))
                            .map(m -> m.getContent().get(0).getText().getValue())
                            .toList()
            );
        }

        return mensagens;
    }

    public void apagarThread() {
        if (this.threadId != null) {
            service.deleteThread(this.threadId);
            this.threadId = null;
        }
    }
}
