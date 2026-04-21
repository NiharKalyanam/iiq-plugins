package com.sailpoint.ticketManagement.util;

import com.sailpoint.ticketManagement.model.Ticket;
import com.sailpoint.ticketManagement.model.TicketComment;

import sailpoint.api.SailPointContext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class AIClient {
	
	private static final Log log = LogFactory.getLog(AIClient.class);
    private static final String CUSTOM_OBJECT_NAME = "Custom-Ticket-Creation";

    private final SailPointContext context;

    public AIClient(SailPointContext context) {
        this.context = context;
    }

    public String analyzeFailure(Ticket ticket) {
        try {
            String aiContent = ConfigUtil.getStringConfig(context, CUSTOM_OBJECT_NAME, "aiContent");
            log.debug("analyzeFailure() called. Using aiContent from custom object.");
            return callChatCompletion(buildFailureAnalysisPrompt(ticket), aiContent);
        } catch (Exception e) {
            log.error("Error in analyzeFailure()", e);
            return "AI error: " + e.getMessage();
        }
    }

    public String answerTicketQuestion(Ticket ticket,
                                       List<TicketComment> comments,
                                       String question,
                                       String additionalContext) {
        try {
            if (ticket == null) {
                log.warn("answerTicketQuestion() called with null ticket");
                return "Not available in this ticket";
            }

            if (question == null || question.trim().length() == 0) {
                log.warn("answerTicketQuestion() called with empty question for ticket id: " + ticket.getId());
                return "Not available in this ticket";
            }

            String systemPrompt =
                "You are an IAM expert helping answer questions for a selected failure ticket. " +
                "Use the provided ticket context, user information, account details, pending request details, entitlement details, AI summary, and comments. " +
                "Keep the answer focused on the selected ticket and selected identity only. " +
                "You may interpret the provided IAM data and explain it in simple language. " +
                "If the answer cannot be determined from the provided context, return exactly: Not available in this ticket";

            String prompt = buildTicketQuestionPrompt(ticket, comments, question, additionalContext);
            String response = callChatCompletion(prompt, systemPrompt);

            if (response == null || response.trim().length() == 0) {
                return "Not available in this ticket";
            }

            return response.trim();

        } catch (Exception e) {
            log.error("Error in answerTicketQuestion()", e);
            return "Not available in this ticket";
        }
    }

    private String callChatCompletion(String prompt, String systemContent) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;

        try {
            String apiKey = ConfigUtil.getStringConfig(context, CUSTOM_OBJECT_NAME, "openai.api.key");
            String aiModel = ConfigUtil.getStringConfig(context, CUSTOM_OBJECT_NAME, "aiModel");

            log.debug("Calling OpenAI. Model: " + aiModel);

            if (apiKey == null || apiKey.trim().length() == 0) {
                log.warn("OpenAI API key is not configured");
                return "AI disabled: API key not configured";
            }

            if (aiModel == null || aiModel.trim().length() == 0) {
                aiModel = "gpt-4.1-mini";
            }

            URL url = new URL("https://api.openai.com/v1/chat/completions");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setDoOutput(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(30000);

            JSONObject requestBody = new JSONObject();
            requestBody.put("model", aiModel);

            JSONArray messages = new JSONArray();

            JSONObject systemMessage = new JSONObject();
            systemMessage.put("role", "system");
            systemMessage.put("content", systemContent != null ? systemContent : "");

            JSONObject userMessage = new JSONObject();
            userMessage.put("role", "user");
            userMessage.put("content", prompt != null ? prompt : "");

            messages.put(systemMessage);
            messages.put(userMessage);

            requestBody.put("messages", messages);

            try (OutputStream outputStream = connection.getOutputStream()) {
                outputStream.write(requestBody.toString().getBytes("utf-8"));
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "utf-8"));
            } else {
                if (connection.getErrorStream() != null) {
                    reader = new BufferedReader(new InputStreamReader(connection.getErrorStream(), "utf-8"));
                } else {
                    return "AI error: HTTP " + responseCode + " with empty response";
                }
            }

            StringBuilder responseBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                responseBuilder.append(line);
            }

            String rawResponse = responseBuilder.toString();
            if (rawResponse == null || rawResponse.trim().length() == 0) {
                return "AI error: empty response from AI service";
            }

            rawResponse = rawResponse.trim();

            if (!rawResponse.startsWith("{")) {
                return "AI error: non-JSON response from AI service: " + rawResponse;
            }

            JSONObject responseJson = new JSONObject(rawResponse);

            if (responseCode != 200) {
                log.error("OpenAI returned non-200 response: " + responseJson.toString());
                return "AI error: " + responseJson.toString();
            }

            JSONArray choices = responseJson.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return "AI error: no choices returned by AI service";
            }

            JSONObject message = choices.getJSONObject(0).getJSONObject("message");
            String content = message.optString("content", "");

            if (content == null || content.trim().length() == 0) {
                return "AI error: empty AI content";
            }

            return content.trim();

        } catch (Exception e) {
            log.error("Error while calling OpenAI", e);
            return "AI error: " + e.getMessage();
        } finally {
            try {
                if (reader != null) {
                    reader.close();
                }
            } catch (Exception e) {
                log.debug("Error closing reader", e);
            }

            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String buildFailureAnalysisPrompt(Ticket ticket) {
        return "Analyze IAM provisioning failure:\n\n" +
                "Application: " + safe(ticket != null ? ticket.getApplicationName() : null) + "\n" +
                "Identity: " + safe(ticket != null ? ticket.getIdentityName() : null) + "\n" +
                "Operation: " + safe(ticket != null ? ticket.getOperation() : null) + "\n" +
                "Error: " + safe(ticket != null ? ticket.getErrorMessage() : null) + "\n\n" +
                "Return ONLY valid JSON in this exact format:\n" +
                "{\n" +
                "  \"rootCause\": \"...\",\n" +
                "  \"recommendation\": \"...\",\n" +
                "  \"severity\": \"Low|Medium|High\",\n" +
                "  \"confidence\": 0,\n" +
                "  \"assignmentGroup\": \"...\"\n" +
                "}\n" +
                "Do not include markdown. Do not include extra text.";
    }

    private String buildTicketQuestionPrompt(Ticket ticket,
                                             List<TicketComment> comments,
                                             String question,
                                             String additionalContext) {
        StringBuilder promptBuilder = new StringBuilder();

        promptBuilder.append("Answer the user's question using the selected ticket context below.\n");
        promptBuilder.append("If the answer is not available from the provided context, return exactly: Not available in this ticket\n\n");

        promptBuilder.append("Ticket Data:\n");
        promptBuilder.append("Ticket ID: ").append(safeLong(ticket.getId())).append("\n");
        promptBuilder.append("Application: ").append(safe(ticket.getApplicationName())).append("\n");
        promptBuilder.append("Identity: ").append(safe(ticket.getIdentityName())).append("\n");
        promptBuilder.append("Operation: ").append(safe(ticket.getOperation())).append("\n");
        promptBuilder.append("Affected Access: ").append(extractAffectedAccess(ticket.getErrorMessage())).append("\n");
        promptBuilder.append("Error: ").append(extractDisplayError(ticket.getErrorMessage())).append("\n");
        promptBuilder.append("AI Summary: ").append(safe(ticket.getAiSummary())).append("\n\n");

        if (additionalContext != null && additionalContext.trim().length() > 0) {
            promptBuilder.append("Additional User Context:\n");
            promptBuilder.append(additionalContext.trim()).append("\n\n");
        }

        promptBuilder.append("Comments:\n");
        if (comments == null || comments.isEmpty()) {
            promptBuilder.append("No comments available\n");
        } else {
            for (TicketComment comment : comments) {
                if (comment == null) {
                    continue;
                }

                promptBuilder.append("- ")
                             .append(safe(comment.getCommentBy()))
                             .append(": ")
                             .append(safe(comment.getCommentText()))
                             .append("\n");
            }
        }

        promptBuilder.append("\nUser Question: ").append(question != null ? question.trim() : "").append("\n\n");
        promptBuilder.append("Return a concise plain-text answer only.");

        return promptBuilder.toString();
    }

    private String extractAffectedAccess(String errorMessage) {
        if (errorMessage == null) {
            return "N/A";
        }

        String text = errorMessage.trim();
        String prefix = "Access:";
        String separator = " | Error:";

        if (text.startsWith(prefix) && text.indexOf(separator) > -1) {
            String value = text.substring(prefix.length(), text.indexOf(separator)).trim();
            value = value.replace("\\n", "\n").trim();
            return value.length() == 0 ? "N/A" : value;
        }

        return "N/A";
    }

    private String extractDisplayError(String errorMessage) {
        if (errorMessage == null) {
            return "N/A";
        }

        String text = errorMessage.trim();
        String separator = " | Error:";

        if (text.indexOf(separator) > -1) {
            String value = text.substring(text.indexOf(separator) + separator.length()).trim();
            return value.length() == 0 ? "N/A" : value;
        }

        return text.length() == 0 ? "N/A" : text;
    }

    private String safe(String value) {
        if (value == null || value.trim().length() == 0) {
            return "N/A";
        }
        return value;
    }

    private String safeLong(Long value) {
        return value == null ? "N/A" : String.valueOf(value);
    }
}