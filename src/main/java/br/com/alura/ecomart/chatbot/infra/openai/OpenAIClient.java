package br.com.alura.ecomart.chatbot.infra.openai;

import com.theokanning.openai.OpenAiResponse;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.messages.Message;
import com.theokanning.openai.messages.MessageRequest;
import com.theokanning.openai.runs.Run;
import com.theokanning.openai.runs.RunCreateRequest;
import com.theokanning.openai.service.OpenAiService;
import com.theokanning.openai.threads.Thread;
import com.theokanning.openai.threads.ThreadRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;

@Component
public class OpenAIClient {

    private final String apiKey;
    private final String assistantId;
    private final OpenAiService service;
    private String threadId;

    public OpenAIClient(@Value("${app.openai.api.key}") String apiKey, @Value("${app.openai.assistant.id}") String assistantId) {
        this.apiKey = apiKey;
        this.service = new OpenAiService(apiKey, Duration.ofSeconds(60));
        this.assistantId = assistantId;
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

        try {
            while (!run.getStatus().equalsIgnoreCase("completed")) {
                java.lang.Thread.sleep(1000 * 10);
                run = service.retrieveRun(threadId, run.getId());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        OpenAiResponse<Message> messages = service.listMessages(threadId);

        return messages.getData()
                .stream()
                .sorted(Comparator.comparingInt(Message::getCreatedAt).reversed())
                .findFirst().get().getContent().get(0).getText().getValue();
    }

}
