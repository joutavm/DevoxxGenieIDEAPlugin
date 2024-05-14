package com.devoxx.genie.service;

import com.devoxx.genie.model.request.ChatMessageContext;
import com.devoxx.genie.model.request.EditorInfo;
import com.devoxx.genie.ui.listener.ChatChangeListener;
import com.devoxx.genie.ui.topic.AppTopics;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.util.messages.MessageBus;
import com.intellij.util.messages.MessageBusConnection;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.output.Response;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PromptExecutionServiceImpl implements PromptExecutionService, ChatChangeListener {

    public static final String YOU_ARE_A_SOFTWARE_DEVELOPER_WITH_EXPERT_KNOWLEDGE_IN =
        "You are a software developer with expert knowledge in ";
    public static final String PROGRAMMING_LANGUAGE = " programming language.";
    public static final String ALWAYS_RETURN_THE_RESPONSE_IN_MARKDOWN =
        "Always return the response in Markdown.";
    public static final String COMMANDS_INFO =
        "The Devoxx Genie plugin supports the following commands: /test: write unit tests on selected code\n/explain: explain the selected code\n/review: review selected code\n/custom: set custom prompt in settings";
    public static final String MORE_INFO =
        "The Devoxx Genie is open source and available at https://github.com/devoxx/DevoxxGenieIDEAPlugin.";
    public static final String NO_HALLUCINATIONS =
        "Do not include any more info which might be incorrect, like discord, twitter, documentation or website info. Only provide info that is correct and relevant to the code or plugin.";
    public static final String QUESTION = "The user question: ";
    public static final String CONTEXT_PROMPT = "Question context: \n";

    private final MessageWindowChatMemory messageWindowChatMemory = MessageWindowChatMemory.withMaxMessages(10);

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private CompletableFuture<Optional<AiMessage>> futureTask = null;
    private boolean running = false;

    /**
     * Constructor.
     */
    public PromptExecutionServiceImpl() {
        MessageBus bus = ApplicationManager.getApplication().getMessageBus();
        MessageBusConnection connection = bus.connect();
        connection.subscribe(AppTopics.CHAT_MESSAGES_CHANGED_TOPIC, this);
    }

    /**
     * Execute the query with the given language text pair and chat language model.
     * @param chatMessageContext the chat message context
     * @return the response
     */
    @Override
    public @NotNull CompletableFuture<Optional<AiMessage>> executeQuery(@NotNull ChatMessageContext chatMessageContext) {

        synchronized (this) {
            // If the future task is not null this means we need to cancel it
            if (futureTask != null && !futureTask.isDone()) {
                futureTask.cancel(true);
                running = false;
                return futureTask;
            } else {
                running = true;
            }

            // Start a new future task
            futureTask = CompletableFuture.supplyAsync(() -> {
                initChatMessage(chatMessageContext);

                try {
                    Response<AiMessage> response = chatMessageContext.getChatLanguageModel().generate(messageWindowChatMemory.messages());
                    messageWindowChatMemory.add(response.content());
                    return Optional.of(response.content());
                } catch (Exception e) {
                    throw new CompletionException(new RuntimeException("Failed to execute prompt!\n" + e.getMessage()));
                }
            }, executorService);
        }

        return futureTask;
    }

    /**
     * Clear the chat messages.
     */
    @Override
    public void clearChatMessages() {
        messageWindowChatMemory.clear();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /**
     * Set up the chat message.
     * @param chatMessageContext the chat message context
     */
    private void initChatMessage(ChatMessageContext chatMessageContext) {
        if (messageWindowChatMemory.messages().isEmpty()) {
            messageWindowChatMemory.add(createSystemMessage(chatMessageContext));
        }

        createUserMessage(chatMessageContext);

        messageWindowChatMemory.add(chatMessageContext.getUserMessage());
    }

    /**
     * Create a system message.
     * @param chatMessageContext the language text pair
     * @return the system message
     */
    private @NotNull SystemMessage createSystemMessage(@NotNull ChatMessageContext chatMessageContext) {
        String language = Optional.ofNullable(chatMessageContext.getEditorInfo())
            .map(EditorInfo::getLanguage)
            .orElse("programming");

        return new SystemMessage(
            YOU_ARE_A_SOFTWARE_DEVELOPER_WITH_EXPERT_KNOWLEDGE_IN +
                language + PROGRAMMING_LANGUAGE +
                ALWAYS_RETURN_THE_RESPONSE_IN_MARKDOWN + "\n" +
                COMMANDS_INFO + "\n" +
                MORE_INFO + "\n" +
                NO_HALLUCINATIONS);
    }

    /**
     * Create a user message with context.
     * @param chatMessageContext the chat message context
     */
    private void createUserMessage(@NotNull ChatMessageContext chatMessageContext) {
        String context = chatMessageContext.getContext();
        String selectedText = chatMessageContext.getEditorInfo() != null ? chatMessageContext.getEditorInfo().getSelectedText() : "";
        if ((selectedText != null && !selectedText.isEmpty()) ||
            (context != null && !context.isEmpty())) {
            StringBuilder sb = new StringBuilder(QUESTION);
            addContext(chatMessageContext, sb, selectedText, context);
            chatMessageContext.setUserMessage(new UserMessage(sb.toString()));
        } else {
            chatMessageContext.setUserMessage(new UserMessage(QUESTION + " " + chatMessageContext.getUserPrompt()));
        }
    }

    /**
     * Add prompt context (selected code snippet) to the chat message.
     * @param chatMessageContext the chat message context
     * @param sb the string builder
     * @param selectedText the selected text
     * @param context the context
     */
    private static void addContext(@NotNull ChatMessageContext chatMessageContext,
                                   @NotNull StringBuilder sb,
                                   String selectedText,
                                   String context) {
        sb.append(chatMessageContext.getUserPrompt());
        sb.append(CONTEXT_PROMPT);

        if (selectedText != null && !selectedText.isEmpty()) {
            sb.append(selectedText).append("\n");
        }

        if (context != null && !context.isEmpty()) {
            sb.append(context);
        }
    }

    /**
     * Removes the user and AI response message from the chat memory.
     * @param chatMessageContext the chat message context
     */
    public void removeMessagePair(ChatMessageContext chatMessageContext) {
        List<ChatMessage> messages = messageWindowChatMemory.messages();

        messages.removeIf(m -> (m.equals(chatMessageContext.getAiMessage()) || m.equals(chatMessageContext.getUserMessage())));

        messageWindowChatMemory.clear();
        messages.forEach(messageWindowChatMemory::add);
    }
}