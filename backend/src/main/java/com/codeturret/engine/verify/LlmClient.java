package com.codeturret.engine.verify;

/** Sends a prompt to a model and returns its JSON text response. Implemented by {@code LlmService}. */
@FunctionalInterface
public interface LlmClient {

    String completeJson(String model, String prompt) throws Exception;
}
