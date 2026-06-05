package com.example.aispringvideo.config;

import dev.langchain4j.model.audio.AudioTranscriptionModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiAudioTranscriptionModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class LangChain4jConfig {

    @Value("${siliconflow.api-key}")
    private String apiKey;

    @Value("${siliconflow.base-url}")
    private String baseUrl;

    @Value("${siliconflow.whisper.model}")
    private String whisperModel;

    @Value("${siliconflow.gpt.model}")
    private String gptModel;

    @Bean
    public AudioTranscriptionModel audioTranscriptionModel() {
        return OpenAiAudioTranscriptionModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(whisperModel)
                .maxRetries(2)
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    @Bean
    public ChatModel chatModel() {
        return OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(gptModel)
                .temperature(0.3)
                .timeout(Duration.ofMinutes(5))
                .build();
    }

    @Bean
    public StreamingChatModel streamingChatModel() {
        return OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(gptModel)
                .temperature(0.3)
                .timeout(Duration.ofMinutes(5))
                .build();
    }
}
