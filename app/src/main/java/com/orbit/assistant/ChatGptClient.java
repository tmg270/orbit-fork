package com.orbit.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Experimental native client for the ChatGPT-authenticated Codex Responses path.
 * Authentication comes from ChatGptAuth; no OpenAI API key is used here.
 *
 * The backend contract used by Codex is not documented as a general third-party API,
 * so Orbit keeps the API relay provider as an explicit fallback rather than silently
 * charging for API usage.
 */
public final class ChatGptClient {
    private static final String RESPONSES_URL = "https://chatgpt.com/backend-api/codex/responses";
    private static final ExecutorService EXEC = Executors.newCachedThreadPool();
    private static final Set<String> ALLOWED_ACTIONS = DeviceActionContract.ALLOWED_ACTIONS;

    /** Moved to {@link DeviceActionContract#SYSTEM} so xAI can share it; unchanged text. */
    private static final String SYSTEM = DeviceActionContract.SYSTEM;

    /**
     * The image policy sent when Orbit is going to find the pictures itself.
     *
     * <p><b>Why this replaces the old permission rather than adding to it.</b> Orbit had two
     * remote-image systems answering the same question at once: a structured Rich Answer that Orbit
     * discovered, validated and can attribute, and a Markdown {@code ![](…)} the model wrote from
     * memory. On a real device that produced four Mallards in one answer, two from each side. The
     * renderer now suppresses the model's images whenever structured ones exist, which is the
     * defence that has to hold; this is the cheaper half of the fix, which is to stop the model
     * writing an image that was only ever going to be dropped or broken.
     *
     * <p>Ordinary Markdown links are untouched and deliberately so. A link to a gallery or a source
     * page is useful and is not a competing picture.
     *
     * <p>The second half is about counting. Discovery finishes after the answer is written, and how
     * many pictures survive a real fetch is not knowable while the model is writing, so a sentence
     * promising two photographs is a sentence that is wrong whenever the web only had one.
     */
    private static final String RICH_ANSWERS_SYSTEM =
            " Orbit supplies the pictures for this answer itself. Do not emit Markdown image syntax or any direct image embed; an image you write will not be displayed. Ordinary Markdown links to pages, galleries and sources remain welcome and unchanged. " +
            "Orbit attaches its own sourced pictures under your answer after you have finished writing, and how many of them survive real-world fetching is not knowable while you write. So never promise a number of pictures, never describe what is about to appear, and never claim the pictures are different from each other: no here are three photos, no I will attach two images, no these are two separate photographs, no below you will find. Write neutrally instead, for example Here are some useful visual references, or simply answer the question.";

    /**
     * The image policy sent when Orbit is not going to attach anything.
     *
     * <p>Exactly what every build before Beta 7 sent to everybody. A user who has turned Rich
     * Answers off still gets a model that may write a picture, because with no structured images
     * that Markdown one is the only picture there is and the renderer still draws it.
     */
    private static final String MARKDOWN_IMAGES_SYSTEM =
            " Only use Markdown image syntax when you already have a real concrete public HTTPS image URL. Never invent or guess image URLs; answer with text when no usable image URL is available. If you include image links yourself, prefer links to real public photo, gallery, or image pages about the subject.";

    private static final String LELO_SYSTEM =
            " Lelo mode is enabled. Use a casual, playful, friend-like conversational style inspired by texting: mostly lowercase when natural, short relaxed phrasing, contractions, slang such as yeah/nah/lmao when fitting, and occasional expressive emoji such as 😭. Avoid corporate, formal, or therapist-like phrasing unless the task genuinely requires formality. Be warm without pretending to be a real human friend or making relational promises. Keep facts, safety, and device-action accuracy unchanged. Never use an em dash.";

    /**
     * Which of the two image policies this request carries.
     *
     * <p>One question, asked in one place: is Orbit going to supply the pictures. When it is, the
     * model is told not to write any; when it is not, the model keeps the permission it has always
     * had. Sending both would be telling it to do and not do the same thing.
     */
    static String imagePolicy(Context context) {
        return RichAnswerCoordinator.enabled(context) ? RICH_ANSWERS_SYSTEM : MARKDOWN_IMAGES_SYSTEM;
    }

    /**
     * The extra instruction carried by a request whose hosted search is required rather than
     * offered.
     *
     * <p>The {@code tool_choice} field is what actually makes the search happen; this is what makes
     * the search <em>useful</em>. A model that has searched still has to write the trailing
     * {@code Source:} marker, because that marker is Orbit's second route to provenance and the one
     * that saves the answer when the backend's search envelope arrives in a shape Orbit cannot
     * read. Sent only on the turns that force a search, so no ordinary answer is nagged about a
     * tool it was never going to use.
     */
    private static final String FORCED_SEARCH_SYSTEM =
            " This question is about what something looks like, so the hosted web search tool is required for it rather than optional. Search real public pages before answering, base the answer on pages you actually opened, and never answer this one from memory alone. End with the mandatory Source: line naming the best page you consulted.";

    /** The search instruction this request carries, which is nothing unless it forces a search. */
    static String searchPolicy(boolean forceSearch) {
        return forceSearch ? FORCED_SEARCH_SYSTEM : "";
    }

    /**
     * Attaches the hosted search tool, and says how strongly this turn wants it used.
     *
     * <p>One place, so the offered case and the required case cannot drift apart. Every request
     * that was offered the tool before is still offered it on exactly the same terms; the only
     * difference a forced turn makes is the value of {@code tool_choice}.
     */
    static void applyHostedSearch(JSONObject root, Context context, String prompt,
                                  boolean forceSearch) throws Exception {
        if (!shouldOfferHostedWebSearch(prompt)) return;
        root.put("tools", new JSONArray().put(new JSONObject().put("type", "web_search")));
        root.put("tool_choice", forceSearch ? HostedSearchPolicy.toolChoice(context) : "auto");
    }

    /**
     * Planning instructions. Deliberately not {@link #SYSTEM}: the chat instructions require the
     * {@code {"text","actions"}} envelope, which is a different schema from the one the planning
     * prompt asks for. Sending both told the model to satisfy two incompatible shapes at once.
     */
    private static final String PLANNING_SYSTEM =
            "You are Orbit's routine planner. You convert a description of a phone routine into a " +
            "routine definition. Reply with a single raw JSON object and nothing else: no prose " +
            "before or after it, no explanation, and no code fence. Use exactly the schema and the " +
            "supported action list given in the user message, and never invent an action type or a " +
            "parameter that is not listed there.";

    private ChatGptClient() {}

    /**
     * A planning request. The complete response text is handed back unparsed, because a planner
     * returns its own schema rather than a chat reply.
     */
    public static void plan(Context context, String planningPrompt, AiSelection selection,
                            AssistantClient.PlanCallback cb) {
        complete(context, PLANNING_SYSTEM, planningPrompt, selection, cb);
    }

    /**
     * One non-streaming structured request with caller-chosen instructions.
     *
     * <p>Exactly the planning request, with the Routine planner's instructions swapped for the
     * caller's: no history, no screen, no memory, no tools. Smart Vault uses it to ask for a
     * saved item's title, summary and topics as one small JSON object.
     */
    public static void complete(Context context, String instructions, String planningPrompt,
                                AiSelection selection, AssistantClient.PlanCallback cb) {
        ChatGptAuth.getValidTokens(context, null, new ChatGptAuth.TokenCallback() {
            @Override public void onSuccess(SecureStore.ChatGptTokens tokens) {
                EXEC.execute(() -> doPlan(context, instructions, planningPrompt, selection, tokens, false, cb));
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private static void doPlan(Context context, String instructions, String planningPrompt,
                               AiSelection selection,
                               SecureStore.ChatGptTokens tokens, boolean alreadyRefreshed,
                               AssistantClient.PlanCallback cb) {
        HttpURLConnection conn = null;
        final AiSelection sent = AiSelections.resolve(selection);
        String model = sent.model;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("instructions", instructions);
            body.put("store", false);
            body.put("stream", true);
            body.put("parallel_tool_calls", false);
            String effort = sent.effortId();
            if (effort != null && !effort.isEmpty() && !"none".equals(effort)) {
                body.put("reasoning", new JSONObject().put("effort", effort));
            }
            // Only the planning prompt: no history, screen text, screenshot, notifications,
            // memories, or extension secrets. No tools are offered either.
            JSONArray content = new JSONArray().put(new JSONObject()
                    .put("type", "input_text").put("text", planningPrompt));
            body.put("input", new JSONArray().put(new JSONObject()
                    .put("role", "user").put("content", content)));

            conn = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + tokens.accessToken);
            if (tokens.accountId != null && !tokens.accountId.isEmpty()) {
                conn.setRequestProperty("ChatGPT-Account-ID", tokens.accountId);
            }
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            conn.setRequestProperty("Originator", "orbit-assistant");
            conn.setRequestProperty("User-Agent", "OrbitAssistant/" + BuildConfig.VERSION_NAME + " (Android)");
            conn.setRequestProperty("session_id", UUID.randomUUID().toString());

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }

            int code = conn.getResponseCode();
            if (code == 401) {
                conn.disconnect();
                if (alreadyRefreshed) {
                    // Refused again straight after a renewal: the session itself is no good.
                    cb.onError(ChatGptAuth.sessionRejected(context, tokens));
                    return;
                }
                ChatGptAuth.getValidTokens(context, tokens.accessToken, new ChatGptAuth.TokenCallback() {
                    @Override public void onSuccess(SecureStore.ChatGptTokens fresh) {
                        EXEC.execute(() -> doPlan(context, instructions, planningPrompt,
                                selection, fresh, true, cb));
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
                return;
            }
            if (code < 200 || code >= 300) {
                if (code >= 500) ChatGptAuth.markRefreshDue();
                cb.onError(friendlyHttpError(code, ChatGptAuth.readAll(conn.getErrorStream()), model));
                return;
            }

            // No delta forwarding: partial planner JSON is never shown anywhere. Planning has no
            // visible thinking indicator either, so it never asks for or reads summaries.
            SseResult stream = readSse(conn.getInputStream(), new AssistantClient.Callback() {
                @Override public void onSuccess(AssistantReply reply) {}
                @Override public void onError(String message) {}
            }, false);
            if (stream.output == null || stream.output.trim().isEmpty()) {
                cb.onError("ChatGPT connected but returned no planning response. Try again.");
                return;
            }
            cb.onText(stream.output, "ChatGPT · " + model);
        } catch (Exception e) {
            // Includes a stream ChatGPT ended with a processing error. One renewal before the next
            // request costs nothing if the session was fine, and clears it if it had gone stale.
            ChatGptAuth.markRefreshDue();
            cb.onError("ChatGPT request failed: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot,
                            List<AssistantClient.History> history, AiSelection selection, AssistantClient.Callback cb) {
        send(context, prompt, screenText, screenshot, history, selection, false, cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot,
                            List<AssistantClient.History> history, AiSelection selection,
                            boolean explicitAttachment, AssistantClient.Callback cb) {
        send(context, prompt, screenText, screenshot, history, selection,
                explicitAttachment, "", cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot,
                            List<AssistantClient.History> history, AiSelection selection,
                            boolean explicitAttachment, String notificationContext,
                            AssistantClient.Callback cb) {
        send(context, prompt, screenText, screenshot, history, selection,
                explicitAttachment, notificationContext, MemoryStore.promptContext(context), cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot,
                            List<AssistantClient.History> history, AiSelection selection,
                            boolean explicitAttachment, String notificationContext,
                            String memoryContext, AssistantClient.Callback cb) {
        send(context, prompt, screenText, screenshot, history, selection,
                explicitAttachment, notificationContext, memoryContext, "", cb);
    }

    public static void send(Context context, String prompt, String screenText, Bitmap screenshot,
                            List<AssistantClient.History> history, AiSelection selection,
                            boolean explicitAttachment, String notificationContext,
                            String memoryContext, String trustedTaskContext,
                            AssistantClient.Callback cb) {
        send(context, prompt, screenText, screenshot, history, selection, explicitAttachment,
                notificationContext, memoryContext, trustedTaskContext, false, cb);
    }

    /**
     * As above, told whether the user has Thinking updates on for this turn.
     *
     * <p>The flag does two things and nothing else: it decides whether the request asks the
     * backend for a reasoning <em>summary</em>, and whether the stream reader turns summary and
     * hosted-search events into status updates. Model, reasoning effort, tools, instructions, and
     * every other request parameter are identical either way, so a Fast request stays Fast and no
     * answer is made slower to give the animation something to say.
     */
    public static void send(Context context, String prompt, String screenText, Bitmap screenshot,
                            List<AssistantClient.History> history, AiSelection selection,
                            boolean explicitAttachment, String notificationContext,
                            String memoryContext, String trustedTaskContext,
                            boolean thinkingUpdates, AssistantClient.Callback cb) {
        send(context, prompt, screenText,
                screenshot == null ? java.util.Collections.emptyList()
                        : java.util.Collections.singletonList(screenshot),
                history, selection, explicitAttachment, notificationContext, memoryContext,
                trustedTaskContext, thinkingUpdates, cb);
    }

    /**
     * The full entry point: a turn carrying any number of images.
     *
     * <p>All of them travel inside the one user message, in the order the user attached them,
     * because that is what makes "compare these three screenshots" a question the model can
     * actually answer. Splitting them across several user turns would describe a conversation that
     * never happened, and combining them into one picture would destroy the very thing being
     * compared.
     */
    public static void send(Context context, String prompt, String screenText, List<Bitmap> images,
                            List<AssistantClient.History> history, AiSelection selection,
                            boolean explicitAttachment, String notificationContext,
                            String memoryContext, String trustedTaskContext,
                            boolean thinkingUpdates, AssistantClient.Callback cb) {
        send(context, prompt, screenText, images, history, selection, explicitAttachment,
                notificationContext, memoryContext, trustedTaskContext, KeptContext.Prepared.NONE,
                thinkingUpdates, cb);
    }

    /** The full entry point, with what the user kept in this chat (0.8.3.0-beta.3+). */
    public static void send(Context context, String prompt, String screenText, List<Bitmap> images,
                            List<AssistantClient.History> history, AiSelection selection,
                            boolean explicitAttachment, String notificationContext,
                            String memoryContext, String trustedTaskContext,
                            KeptContext.Prepared kept,
                            boolean thinkingUpdates, AssistantClient.Callback cb) {
        ChatGptAuth.getValidTokens(context, null, new ChatGptAuth.TokenCallback() {
            @Override public void onSuccess(SecureStore.ChatGptTokens tokens) {
                EXEC.execute(() -> doSend(context, prompt, screenText, images, history,
                        selection, explicitAttachment, notificationContext, memoryContext,
                        trustedTaskContext, kept, thinkingUpdates, tokens, false, cb));
            }
            @Override public void onError(String message) { cb.onError(message); }
        });
    }

    private static void doSend(Context context, String prompt, String screenText, List<Bitmap> images,
                               List<AssistantClient.History> history, AiSelection selection,
                               boolean explicitAttachment, String notificationContext,
                               String memoryContext, String trustedTaskContext,
                               KeptContext.Prepared kept,
                               boolean thinkingUpdates, SecureStore.ChatGptTokens tokens,
                               boolean alreadyRefreshed,
                               AssistantClient.Callback cb) {
        HttpURLConnection conn = null;
        // Exactly what the user selected, already validated. Nothing below substitutes a model.
        final AiSelection sent = AiSelections.resolve(selection);
        final String model = sent.model;
        // Summaries are asked for only when the user wants them and only while the backend has
        // not already refused them on this device.
        final boolean askForSummary = thinkingUpdates && ReasoningSummarySupport.mayRequest(context);
        // Whether this turn requires the hosted search rather than merely offering it. Read here
        // and used twice: once to build the request, once to recognise a backend that refused the
        // form it was asked in. A turn carrying a picture never forces a search.
        final boolean forceSearch = HostedSearchPolicy.shouldForce(context, prompt,
                images != null && !images.isEmpty());
        try {
            JSONObject body = requestBody(context, prompt, screenText, images, history,
                    selection, explicitAttachment, notificationContext, memoryContext,
                    trustedTaskContext, askForSummary, forceSearch, model, kept, null);
            conn = (HttpURLConnection) new URL(RESPONSES_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(180000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Authorization", "Bearer " + tokens.accessToken);
            if (tokens.accountId != null && !tokens.accountId.isEmpty()) {
                conn.setRequestProperty("ChatGPT-Account-ID", tokens.accountId);
            }
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "text/event-stream");
            // Accurate client metadata; Orbit does not pretend to be the Codex CLI.
            conn.setRequestProperty("Originator", "orbit-assistant");
            conn.setRequestProperty("User-Agent", "OrbitAssistant/" + BuildConfig.VERSION_NAME + " (Android)");
            conn.setRequestProperty("session_id", UUID.randomUUID().toString());

            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = conn.getOutputStream()) { out.write(bytes); }

            int code = conn.getResponseCode();
            if (code == 401) {
                conn.disconnect();
                if (alreadyRefreshed) {
                    // Refused again straight after a renewal: the session itself is no good.
                    cb.onError(ChatGptAuth.sessionRejected(context, tokens));
                    return;
                }
                ChatGptAuth.getValidTokens(context, tokens.accessToken, new ChatGptAuth.TokenCallback() {
                    @Override public void onSuccess(SecureStore.ChatGptTokens fresh) {
                        EXEC.execute(() -> doSend(context, prompt, screenText, images, history,
                                selection, explicitAttachment, notificationContext, memoryContext,
                                trustedTaskContext, kept, thinkingUpdates, fresh, true, cb));
                    }
                    @Override public void onError(String message) { cb.onError(message); }
                });
                return;
            }
            if (code < 200 || code >= 300) {
                String err = ChatGptAuth.readAll(conn.getErrorStream());
                // A backend that will not accept the summary request must cost the user nothing.
                // The request was rejected outright, so nothing was generated and nothing was
                // charged: Orbit records the refusal, stops asking on this device, and answers the
                // turn normally instead of surfacing a failure for an optional status line.
                if (askForSummary && ReasoningSummarySupport.looksLikeSummaryRefusal(code, err)) {
                    // Written synchronously, so the retry below re-reads it and computes
                    // askForSummary false. That is what makes this one retry rather than a loop.
                    ReasoningSummarySupport.markUnsupported(context);
                    conn.disconnect();
                    conn = null;
                    // Thinking updates stay on for the retry: only the summary request is dropped,
                    // so the user still sees Orbit's own progress for this turn.
                    doSend(context, prompt, screenText, images, history, selection,
                            explicitAttachment, notificationContext, memoryContext,
                            trustedTaskContext, kept, thinkingUpdates, tokens, alreadyRefreshed, cb);
                    return;
                }
                // A backend that will not be told which tool to use must cost the user nothing
                // either. The request was rejected before anything was generated, so Orbit steps
                // down one rung - the hosted-tool object, then the generic requirement, then off -
                // and asks the same question again. The rung is committed before the retry reads
                // it, which is what makes this a short ladder rather than a loop.
                if (forceSearch && HostedSearchPolicy.looksLikeForcedSearchRefusal(code, err)) {
                    HostedSearchPolicy.degrade(context);
                    conn.disconnect();
                    conn = null;
                    doSend(context, prompt, screenText, images, history, selection,
                            explicitAttachment, notificationContext, memoryContext,
                            trustedTaskContext, kept, thinkingUpdates, tokens, alreadyRefreshed, cb);
                    return;
                }
                if (code >= 500) ChatGptAuth.markRefreshDue();
                String friendly = friendlyHttpError(code, err, model);
                // A model whose availability depends on the account gets a truthful answer
                // instead of a backend error code. Orbit does not quietly retry on a different
                // model: the user's selection stays exactly as it was, and they choose what to try.
                if (OrbitModelCatalog.looksUnavailable(model, friendly)) {
                    DiagnosticStore.recordModelUnavailable(context, model);
                    ModelAvailability.markUnavailable(context, sent.provider, model);
                    cb.onError(OrbitModelCatalog.unavailableMessage(model));
                    return;
                }
                cb.onError(friendly);
                return;
            }

            boolean hostedSearchAvailable = shouldOfferHostedWebSearch(prompt);
            DiagnosticStore.recordEffectiveModel(context, model, model);
            ModelAvailability.markAvailable(context, sent.provider, model);
            if (thinkingUpdates) {
                // True and known before a single token arrives: this request went to this model at
                // this effort, named from the model actually being sent.
                cb.onThinking(ThinkingUpdate.modelReasoning(model));
            }
            SseResult stream = readSse(conn.getInputStream(), cb, thinkingUpdates);
            // Written only for a response that actually searched, and only ever names and counts.
            HostedSearchSchemaTrace.record(context, stream.schema);
            // Whether requiring the search actually produced one. A forced request that reports no
            // search at all is the finding, and without this it would be invisible.
            if (forceSearch) HostedSearchPolicy.recordForcedRequest(context, stream.schema.searchSeen);
            if (askForSummary) ReasoningSummarySupport.record(context, stream.sawReasoningSummary);
            String output = stream.output;
            if (output.trim().isEmpty()) {
                cb.onError("ChatGPT connected but returned no assistant text. Try again.");
                return;
            }
            AssistantReply reply = parseReply(output, hostedSearchAvailable, stream.sourceUrl);
            // Provenance is attached to the finished reply rather than folded into its text: the
            // answer the user reads is unchanged, and what Rich Answers needs is the list of pages
            // rather than a sentence about one of them.
            cb.onSuccess(reply.withSourceUrls(stream.sourceUrls)
                    .withDetails(ResponseDetails.sentWith(sent)));
        } catch (Exception e) {
            // Includes a stream ChatGPT ended with a processing error. One renewal before the next
            // request costs nothing if the session was fine, and clears it if it had gone stale.
            ChatGptAuth.markRefreshDue();
            cb.onError("ChatGPT request failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** The quoted-message block a stored user turn carries, or "" for none. */
    static String quoteBlock(AssistantClient.History turn) {
        return turn == null || turn.quote == null || !"user".equalsIgnoreCase(turn.role)
                ? "" : turn.quote.promptBlock();
    }

    static JSONObject requestBody(Context context, String prompt, String screenText, List<Bitmap> images,
                                          List<AssistantClient.History> history, AiSelection selection,
                                          boolean explicitAttachment, String notificationContext,
                                          String memoryContext, String trustedTaskContext,
                                          boolean askForSummary, boolean forceSearch,
                                          String model) throws Exception {
        return requestBody(context, prompt, screenText, images, history, selection,
                explicitAttachment, notificationContext, memoryContext, trustedTaskContext,
                askForSummary, forceSearch, model, KeptContext.Prepared.NONE, null);
    }

    /**
     * The one place a ChatGPT request is assembled.
     *
     * <p>{@code kept} is what the user kept in this chat: added to the current message, and the
     * earlier turns it fully covers lose their own copy of that text, so it is sent once.
     *
     * <p>{@code ledger}, when given, is told what each piece of the request is as it is placed (see
     * {@link ContextLedger}). That is how the context meter measures the real request rather than
     * an imitation of it. A measuring ledger also stops images being read from disk and encoded,
     * and stops Diagnostics being written; nothing else about the request changes.
     */
    static JSONObject requestBody(Context context, String prompt, String screenText, List<Bitmap> images,
                                  List<AssistantClient.History> history, AiSelection selection,
                                  boolean explicitAttachment, String notificationContext,
                                  String memoryContext, String trustedTaskContext,
                                  boolean askForSummary, boolean forceSearch,
                                  String model, KeptContext.Prepared kept,
                                  ContextLedger ledger) throws Exception {
        if (kept == null) kept = KeptContext.Prepared.NONE;
        boolean measuring = ledger != null && ledger.measureOnly;
        JSONObject root = new JSONObject();
        root.put("model", model);
        String memory = memoryContext == null ? "" : memoryContext.trim();
        String notificationInstruction =
                notificationContext == null || notificationContext.trim().isEmpty()
                        ? ""
                        : "\n\nOrbit has supplied local notification history in the current user message. " +
                          "Use that supplied history to answer the user's notification question. " +
                          "Do not say you cannot access notification history when this context is present.";
        String task = trustedTaskContext == null ? "" : trustedTaskContext.trim();
        String trustedTaskInstruction = task.isEmpty() ? "" :
                "\n\nTrusted Orbit task state derived from the user's direct corrections (not from screen content):\n" + task;
        // Read at request time rather than cached, so turning Rich Answers off changes the next
        // answer rather than the one after it - the same rule the discovery side follows.
        String orbitInstructions = SYSTEM + imagePolicy(context) + searchPolicy(forceSearch)
                + (Prefs.leloMode(context) ? LELO_SYSTEM : "");
        String requestState = trustedTaskInstruction +
                notificationInstruction +
                " Current local time: " + OffsetDateTime.now() +
                "; timezone: " + TimeZone.getDefault().getID() +
                "; locale: " + java.util.Locale.getDefault().toLanguageTag() + ".";
        root.put("instructions", orbitInstructions +
                (memory.isEmpty() ? "" : "\n\n" + memory) +
                requestState);
        if (ledger != null) {
            ledger.text(ContextLedger.Category.INSTRUCTIONS, orbitInstructions + requestState);
            ledger.text(ContextLedger.Category.MEMORY, memory.isEmpty() ? "" : "\n\n" + memory);
        }
        root.put("store", false);
        root.put("stream", true);
        root.put("parallel_tool_calls", false);
        applyHostedSearch(root, context, prompt, forceSearch);

        // Already validated against the model's own strengths by AiSelections, so an effort the
        // model refuses (None on Sol or Astra) can never reach the backend.
        String effort = AiSelections.resolve(selection).effortId();
        if (effort != null && !effort.isEmpty() && !"none".equals(effort)) {
            JSONObject reasoning = new JSONObject().put("effort", effort);
            // The effort is untouched by Thinking updates: this asks the backend to also write a
            // short summary of the work it was already going to do, and never asks it to think
            // harder. Auto's calibration and a Fast request's cost are therefore unchanged.
            if (askForSummary) reasoning.put("summary", "auto");
            root.put("reasoning", reasoning);
        }

        JSONArray input = new JSONArray();
        // What the user is attaching right now, for Diagnostics only. Read from the current turn's
        // own record rather than guessed, and it is Orbit's category name, never a filename.
        String currentAttachmentKind = "none";
        // Orbit's own kind for the current turn's attachment, for the context breakdown.
        String currentKind = "";
        // The earlier message the current turn replies to, read from that turn's own record.
        QuotedMessage currentQuote = null;
        HistoryAttachments.Plan attachments = HistoryAttachments.empty();
        if (history != null) {
            int end = history.size();
            if (end > 0) {
                AssistantClient.History last = history.get(end - 1);
                if (last != null && "user".equalsIgnoreCase(last.role) && prompt != null && prompt.trim().equals(last.content == null ? "" : last.content.trim())) {
                    // The turn being asked right now. Dropped from history because its attachment
                    // travels the current-turn path below; counting it in both places is exactly
                    // the duplication this whole path exists to prevent.
                    if (last.screenAttached) currentAttachmentKind = HistoryAttachments.category(last.attachmentKind);
                    currentKind = last.attachmentKind;
                    currentQuote = last.quote;
                    end--;
                }
            }
            int start = Math.max(0, end - 10);
            if (ledger != null) ledger.olderMessagesNotSent = start;
            List<AssistantClient.History> window = history.subList(start, Math.max(start, end));
            // Bounded by construction: only turns already inside this window are eligible, so the
            // attachment policy can never reach further back than the text history does.
            attachments = HistoryAttachments.plan(window, Prefs.screenshot(context));
            for (int i = 0; i < window.size(); i++) {
                AssistantClient.History h = window.get(i);
                if (h == null || h.content == null || h.content.trim().isEmpty()) continue;
                String role = "assistant".equalsIgnoreCase(h.role) ? "assistant" : "user";
                HistoryAttachments.Turn attachment = "user".equals(role) ? attachments.at(i) : null;
                String metadata = quoteBlock(h)
                        + RenderedResultContext.block(h, i == window.size() - 1);
                String historyText = RenderedResultContext.neutralizeMarkers(
                        safe(h.content, 6000));
                if (ledger != null) {
                    ledger.message();
                    ledger.text(ContextLedger.Category.CONVERSATION, historyText + metadata);
                }
                if (attachment == null) {
                    input.put(new JSONObject().put("role", role)
                            .put("content", historyText + metadata));
                    continue;
                }
                // Text the user kept for the whole chat travels once, in the kept block below, so
                // the turn it came from does not send its own copy as well.
                String attachmentText = !kept.coveredTurns.isEmpty()
                        && kept.coveredTurns.contains(ConversationBranches.fingerprint(h))
                        ? "" : attachment.text;
                String attachmentBlock = HistoryAttachments.wrap(attachment.kind, attachmentText);
                if (ledger != null) {
                    ledger.text(ContextLedger.categoryForKind(h.attachmentKind), attachmentBlock);
                }
                // The attachment is rebuilt onto the turn it was shared with, so the model reads
                // the conversation the way the user remembers having it: the picture is part of
                // the question they asked back then, not part of the one they are asking now.
                JSONArray parts = new JSONArray();
                parts.put(new JSONObject().put("type", "input_text")
                        .put("text", historyText + metadata + attachmentBlock));
                if (measuring) {
                    // Counted, not read: the plan has already established which files exist.
                    ledger.images(attachment.imagePaths.size());
                    input.put(new JSONObject().put("role", role).put("content", parts));
                    continue;
                }
                // Every image that turn still owns, in the order it was shared, on that turn's own
                // message. A file that will not decode is treated exactly like one that is gone:
                // the turn keeps its text and Orbit invents nothing to stand in for the image.
                int imagesSent = 0;
                for (String storedPath : attachment.imagePaths) {
                    Bitmap stored = AttachmentStore.load(storedPath);
                    if (stored == null) continue;
                    parts.put(new JSONObject()
                            .put("type", "input_image")
                            .put("image_url", "data:image/jpeg;base64," + bitmapToBase64(stored)));
                    imagesSent++;
                }
                if (ledger != null) ledger.images(imagesSent);
                input.put(new JSONObject().put("role", role).put("content", parts));
            }
        }
        if (!measuring) {
            DiagnosticStore.recordAttachmentContext(context, currentAttachmentKind,
                    attachments.size(), attachments.images, attachments.kindLabel(),
                    attachments.missingAssets);
        }

        JSONArray currentContent = new JSONArray();
        StringBuilder text = new StringBuilder(prompt == null ? "" : prompt.trim());
        if (currentQuote != null) text.append(currentQuote.promptBlock());
        if (ledger != null) {
            ledger.message();
            ledger.text(ContextLedger.Category.CONVERSATION, text.toString());
        }
        if ((Prefs.screenContext(context) || explicitAttachment) &&
                screenText != null && !screenText.trim().isEmpty()) {
            String tag = explicitAttachment ? "orbit_user_attachment" : "orbit_screen_context";
            String block = "\n\n<" + tag + " untrusted=\"true\">\n"
                    + safe(screenText, explicitAttachment ? 105000 : 18000)
                    + "\n</" + tag + ">";
            text.append(block);
            if (ledger != null) {
                ledger.currentAttachment(block, explicitAttachment
                        ? ContextLedger.categoryForKind(currentKind)
                        : ContextLedger.Category.SCREEN);
            }
        }
        // Notification history must be appended BEFORE input_text is created.
        // Previously input_text captured text.toString() first, so the later
        // notification append never reached ChatGPT even though Orbit had
        // successfully prepared the local notification history.
        if (notificationContext != null && !notificationContext.trim().isEmpty()) {
            String block = "\n\n<orbit_notification_context untrusted=\"true\">\n"
                    + safe(notificationContext, 24000)
                    + "\n</orbit_notification_context>";
            text.append(block);
            if (ledger != null) ledger.text(ContextLedger.Category.NOTIFICATIONS, block);
        }
        // What the user kept for this whole chat, once, after everything this message carries.
        if (!kept.block.isEmpty()) {
            text.append(kept.block);
            if (ledger != null) ledger.text(ContextLedger.Category.KEPT, kept.block);
        }
        currentContent.put(new JSONObject().put("type", "input_text").put("text", text.toString()));
        // Every image the user attached to this message, in their order, inside this one user
        // turn. Not one turn per photo, which would describe a conversation that never happened,
        // and not a stitched composite, which would destroy the thing being compared.
        if (Prefs.screenshot(context) || explicitAttachment) {
            List<Bitmap> current = images == null ? java.util.Collections.emptyList() : images;
            for (Bitmap image : current) {
                if (image == null) continue;
                if (ledger != null) ledger.images(1);
                if (measuring) continue;
                currentContent.put(new JSONObject()
                        .put("type", "input_image")
                        .put("image_url", "data:image/jpeg;base64," + bitmapToBase64(image)));
            }
        }
        input.put(new JSONObject().put("role", "user").put("content", currentContent));
        root.put("input", input);
        return root;
    }

    private static final class SseResult {
        final String output;
        final String sourceUrl;
        /**
         * Every page this stream's hosted search reported consulting, first seen first.
         *
         * <p>Orbit already read one URL out of these events, to put a source chip under the answer.
         * That was enough while a source was one line of attribution and is not enough for a
         * picture, which has to belong to a page the answer actually used. So the whole list is
         * collected rather than the first match, and it is collected from the events themselves -
         * never from the answer's prose, and never from a field this build has not seen.
         */
        final java.util.List<String> sourceUrls;
        /** True if the backend actually published a user-facing reasoning summary on this stream. */
        final boolean sawReasoningSummary;
        /**
         * The shape this stream arrived in, for Diagnostics. Names and counts only.
         *
         * <p>Carried out of the reader rather than written from inside it, because the reader has
         * no Context and must not acquire one: it is on the response path and everything it touches
         * has to stay cheap and failure-proof.
         */
        final HostedSearchSchemaTrace.Snapshot schema;
        SseResult(String output, String sourceUrl, java.util.List<String> sourceUrls,
                  boolean sawReasoningSummary, HostedSearchSchemaTrace.Snapshot schema) {
            this.output = output == null ? "" : output;
            this.sourceUrl = sourceUrl == null ? "" : sourceUrl;
            this.sourceUrls = sourceUrls == null
                    ? java.util.Collections.emptyList()
                    : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(sourceUrls));
            this.sawReasoningSummary = sawReasoningSummary;
            this.schema = schema == null ? new HostedSearchSchemaTrace.Snapshot() : schema;
        }
    }

    /**
     * The only event families Orbit will ever turn into visible thinking text.
     *
     * <p>{@code response.reasoning_summary_*} carries the summary the backend produces <em>for
     * display</em> in answer to {@code reasoning.summary}. That is a different thing from the
     * model's own reasoning: {@code response.reasoning_text.*} and the {@code encrypted_content}
     * on a reasoning item are hidden chain-of-thought, and Orbit reads neither, here or anywhere
     * else in the app. The match below is anchored to {@code _summary_} on purpose, so a future
     * event name cannot quietly widen what Orbit is willing to show.
     */
    private static final String SUMMARY_TEXT_PREFIX = "response.reasoning_summary_text.";
    private static final String SUMMARY_PART_ADDED = "response.reasoning_summary_part.added";

    private static SseResult readSse(InputStream in, AssistantClient.Callback cb,
                                     boolean thinkingUpdates) throws Exception {
        StringBuilder raw = new StringBuilder();
        String completedFallback = "";
        String discoveredSource = "";
        // Insertion-ordered and de-duplicated: a search tool reports the same page across its
        // in-progress, searching and completed events, and a list of one page written six times is
        // not a list of six sources.
        java.util.LinkedHashSet<String> discoveredSources = new java.util.LinkedHashSet<>();
        HostedSearchSchemaTrace.Snapshot schema = new HostedSearchSchemaTrace.Snapshot();
        String lastVisible = "";
        boolean sawSummary = false;
        boolean answerStarted = false;
        // Allocated either way; it holds nothing at all until something feeds it.
        ThinkingUpdateStream summary = new ThinkingUpdateStream();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.startsWith("data:")) continue;
                String data = line.substring(5).trim();
                if (data.isEmpty() || "[DONE]".equals(data)) continue;
                JSONObject event;
                try { event = new JSONObject(data); }
                catch (Exception ignored) { continue; }
                String type = event.optString("type", "");
                // Shape first, then content. The schema of an envelope Orbit fails to understand is
                // exactly the envelope worth describing, so it is described before the parser gets
                // its chance rather than after it succeeds.
                HostedSearchSchemaTrace.observe(schema, event);
                int known = discoveredSources.size();
                collectHostedProvenance(event, discoveredSources);
                if (discoveredSources.size() > known) {
                    int seen = 0;
                    for (String url : discoveredSources) {
                        if (seen++ >= known) HostedSearchSchemaTrace.recognized(schema, url);
                    }
                }
                if ("response.output_text.delta".equals(type)) {
                    raw.append(event.optString("delta", ""));
                    String visible = removeEmDashes(extractPartialText(raw.toString()));
                    if (!visible.isEmpty() && !visible.equals(lastVisible)) {
                        lastVisible = visible;
                        // The answer has begun. Nothing past this point may emit another status
                        // update: answer delivery wins outright, and a summary that arrives late
                        // must never reappear above text the user is already reading.
                        answerStarted = true;
                        cb.onDelta(visible);
                    }
                } else if ("response.output_text.done".equals(type) && raw.length() == 0) {
                    raw.append(event.optString("text", ""));
                } else if ("response.completed".equals(type)) {
                    completedFallback = extractCompletedText(event);
                } else if (thinkingUpdates && !answerStarted && SUMMARY_PART_ADDED.equals(type)) {
                    summary.beginPart();
                } else if (thinkingUpdates && !answerStarted && type.startsWith(SUMMARY_TEXT_PREFIX)) {
                    sawSummary = true;
                    String phrase = type.endsWith(".done")
                            ? summary.finishPart(System.currentTimeMillis())
                            : summary.accept(event.optString("delta", ""), System.currentTimeMillis());
                    if (!phrase.isEmpty()) cb.onThinking(ThinkingUpdate.providerSummary(phrase));
                } else if (type.toLowerCase(java.util.Locale.US).contains("web_search")) {
                    if (thinkingUpdates && !answerStarted) {
                        // Orbit's own words for something it has genuinely watched happen: the
                        // hosted search tool reporting that it began, and then that it finished.
                        cb.onThinking(ThinkingUpdate.progress(type.endsWith(".completed")
                                ? ThinkingUpdate.Stage.WEB_RESULTS : ThinkingUpdate.Stage.WEB_SEARCH));
                    }
                } else if ("response.failed".equals(type) || "error".equals(type)) {
                    String message = extractEventError(event);
                    throw new Exception(message.isEmpty() ? "ChatGPT response failed." : message);
                }
            }
        }
        if (!discoveredSources.isEmpty()) discoveredSource = discoveredSources.iterator().next();
        return new SseResult(raw.length() > 0 ? raw.toString() : completedFallback,
                discoveredSource, new java.util.ArrayList<>(discoveredSources), sawSummary, schema);
    }

    /** Sources and results reported by a hosted-search tool, excluding query/prose fields. */
    static void collectSourceUrls(JSONObject event, java.util.Set<String> into) {
        if (event == null || into == null) return;
        collectSearchSourceFields(event, into);
        JSONObject item = event.optJSONObject("item");
        if (item != null) collectSearchSourceFields(item, into);
    }

    private static void collectSearchSourceFields(JSONObject call, java.util.Set<String> into) {
        for (String key : new String[]{"results", "sources", "citations", "url", "link", "source_url", "sourceUrl"}) {
            collectUrls(call.opt(key), into);
        }
        JSONObject action = call.optJSONObject("action");
        if (action != null) {
            collectUrls(action.opt("sources"), into);
            collectUrls(action.opt("results"), into);
            collectUrls(action.opt("citations"), into);
            // A page the search actually opened is a consulted source; a query it typed is not.
            // Only the actions that name a page are read, and "search" is deliberately not one of
            // them even though its query field routinely contains a URL-looking string.
            String kind = action.optString("type");
            if (PAGE_ACTIONS.contains(kind)) collectUrls(action.opt("url"), into);
        }
        // Some builds wrap the payload one level deeper instead of inlining it on the call.
        JSONObject nested = call.optJSONObject("web_search_call");
        if (nested != null) collectSearchSourceFields(nested, into);
    }

    /** Hosted-search action types that name a page rather than describe a query. */
    private static final java.util.Set<String> PAGE_ACTIONS = new java.util.HashSet<>(
            java.util.Arrays.asList("open_page", "find_in_page", "open_url", "fetch"));

    /**
     * Read provider tool/citation envelopes, never URLs from answer text or action arguments.
     *
     * <p>Deliberately a list of known structural positions rather than a walk of the whole event.
     * Every address accepted here is one the provider placed somewhere that means "this page was
     * consulted": inside a hosted-search call's results or sources, on an action that opened a
     * page, or in a citation attached to the output text. A URL sitting in a function call's
     * arguments, in a search query, or in the answer's own prose is none of those and is refused,
     * which is what stops a model mentioning a website from becoming Orbit fetching it.
     */
    static void collectHostedProvenance(JSONObject event, java.util.Set<String> into) {
        String type = event.optString("type", "");
        // Widened from response.web_search_call. because the family name is not stable across
        // backends: the same envelope has arrived as response.web_search.completed.
        if (type.startsWith("response.web_search")) collectSourceUrls(event, into);
        if ("response.output_item.added".equals(type) || "response.output_item.done".equals(type)) {
            collectHostedItem(event.optJSONObject("item"), into);
        } else if ("response.completed".equals(type) || "response.incomplete".equals(type)) {
            JSONObject response = event.optJSONObject("response");
            JSONArray output = response == null ? null : response.optJSONArray("output");
            if (output != null) for (int i = 0; i < output.length(); i++) {
                collectHostedItem(output.optJSONObject(i), into);
            }
        } else if ("response.output_text.annotation.added".equals(type)) {
            JSONObject annotation = event.optJSONObject("annotation");
            // One event, one annotation: when it is inlined rather than nested, the event itself is
            // the citation object and its own url field is that citation's address.
            collectCitation(annotation == null ? event : annotation, into);
        } else if ("response.content_part.done".equals(type)
                || "response.content_part.added".equals(type)) {
            collectAnnotations(event.optJSONObject("part"), into);
        } else if ("response.output_text.done".equals(type)) {
            collectAnnotations(event, into);
        }
    }

    private static void collectHostedItem(JSONObject item, java.util.Set<String> into) {
        if (item == null) return;
        if ("web_search_call".equals(item.optString("type"))) {
            collectSourceUrls(item, into);
        } else if ("message".equals(item.optString("type"))) {
            JSONArray content = item.optJSONArray("content");
            if (content != null) for (int i = 0; i < content.length(); i++) {
                collectAnnotations(content.optJSONObject(i), into);
            }
        }
    }

    private static void collectAnnotations(JSONObject part, java.util.Set<String> into) {
        if (part == null) return;
        // "citations" is the same list under a different name on some builds. Both are lists of
        // citation objects attached to output text, which is a structural position, not prose.
        for (String key : new String[]{"annotations", "citations"}) {
            JSONArray annotations = part.optJSONArray(key);
            if (annotations != null) for (int i = 0; i < annotations.length(); i++) {
                collectCitation(annotations.optJSONObject(i), into);
            }
        }
    }

    /**
     * One citation object, in any of the shapes a backend has used for it.
     *
     * <p>{@code {"type":"url_citation","url":...}} is the Responses shape,
     * {@code {"type":"url_citation","url_citation":{"url":...}}} the Chat Completions one, and an
     * untyped entry inside an annotations array is still an annotation on the answer's text. What
     * is refused is anything typed as something other than a URL citation, so a file citation or a
     * future annotation kind cannot become a page Orbit fetches.
     */
    private static void collectCitation(JSONObject citation, java.util.Set<String> into) {
        if (citation == null) return;
        JSONObject nested = citation.optJSONObject("url_citation");
        String type = citation.optString("type", "");
        boolean cited = "url_citation".equals(type) || nested != null
                || type.isEmpty() || "response.output_text.annotation.added".equals(type);
        if (!cited) return;
        String url = (nested == null ? citation : nested).optString("url", "").trim();
        if (into.size() < AssistantReply.MAX_SOURCE_URLS && RichAnswerUrlPolicy.isOpenableWebUrl(url)) {
            into.add(url);
        }
    }

    /** Walks any JSON value adding the http/https addresses it finds, up to the reply's ceiling. */
    private static void collectUrls(Object value, java.util.Set<String> into) {
        if (value == null || value == JSONObject.NULL) return;
        if (into.size() >= AssistantReply.MAX_SOURCE_URLS) return;
        try {
            if (value instanceof JSONObject) {
                JSONObject o = (JSONObject) value;
                for (String key : new String[]{"url", "link", "source_url", "sourceUrl"}) {
                    String candidate = o.optString(key, "");
                    if (RichAnswerUrlPolicy.isOpenableWebUrl(candidate)) into.add(candidate);
                }
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext() && into.size() < AssistantReply.MAX_SOURCE_URLS) {
                    collectUrls(o.opt(it.next()), into);
                }
            } else if (value instanceof JSONArray) {
                JSONArray a = (JSONArray) value;
                for (int i = 0; i < a.length() && into.size() < AssistantReply.MAX_SOURCE_URLS; i++) {
                    collectUrls(a.opt(i), into);
                }
            } else if (value instanceof String) {
                String s = ((String) value).trim();
                if (RichAnswerUrlPolicy.isOpenableWebUrl(s)) into.add(s);
            }
        } catch (Exception ignored) {}
    }

    /**
     * Extracts the currently available JSON string value of the first `text` field.
     * It intentionally tolerates an unfinished string so the UI can stream natural text
     * without ever exposing the later action JSON.
     */
    private static String extractPartialText(String raw) {
        return DeviceActionContract.extractPartialText(raw);
    }

    private static String extractCompletedText(JSONObject event) {
        try {
            JSONObject response = event.optJSONObject("response");
            if (response == null) return "";
            JSONArray output = response.optJSONArray("output");
            if (output == null) return "";
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.optJSONObject(i);
                if (item == null) continue;
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int j = 0; j < content.length(); j++) {
                    JSONObject c = content.optJSONObject(j);
                    if (c == null) continue;
                    String t = c.optString("text", "");
                    if (!t.isEmpty()) b.append(t);
                }
            }
            return b.toString();
        } catch (Exception ignored) { return ""; }
    }

    private static String extractEventError(JSONObject event) {
        JSONObject error = event.optJSONObject("error");
        if (error != null) return error.optString("message", error.toString());
        JSONObject response = event.optJSONObject("response");
        if (response != null) {
            JSONObject e = response.optJSONObject("error");
            if (e != null) return e.optString("message", e.toString());
        }
        return event.optString("message", "");
    }

    private static AssistantReply parseReply(String raw, boolean suppressExternalWebSearch, String fallbackSourceUrl) throws Exception {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline >= 0 && lastFence > firstNewline) cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
        }
        JSONObject o;
        try {
            o = new JSONObject(cleaned);
        } catch (Exception parse) {
            // Do not discard a useful answer if the model failed only the JSON envelope.
            return new AssistantReply(appendSourceIfMissing(removeEmDashes(cleaned), fallbackSourceUrl), new java.util.ArrayList<>());
        }
        AssistantReply parsed = AssistantReply.fromJson(o);
        java.util.ArrayList<AssistantReply.Action> safeActions = new java.util.ArrayList<>();
        for (AssistantReply.Action a : parsed.actions) {
            if (a == null || !ALLOWED_ACTIONS.contains(a.type)) continue;
            if (suppressExternalWebSearch && "WEB_SEARCH".equals(a.type)) continue;
            safeActions.add(a);
        }
        return new AssistantReply(appendSourceIfMissing(removeEmDashes(parsed.text), fallbackSourceUrl), safeActions);
    }

    static boolean shouldOfferHostedWebSearch(String prompt) {
        if (prompt == null) return false;
        String q = prompt.toLowerCase(java.util.Locale.US).trim();
        if (q.isEmpty()) return false;
        // Normal informational questions get the hosted search tool as an option,
        // letting the model browse when freshness or online lookup is useful.
        // Only explicit browser-opening intent keeps the external WEB_SEARCH path.
        if (q.startsWith("google ") || q.contains("open google") ||
                q.contains("open a browser") || q.contains("open browser") ||
                q.contains("in my browser") || q.contains("browser search")) return false;
        return true;
    }

    private static String appendSourceIfMissing(String text, String fallbackSourceUrl) {
        String cleaned = text == null ? "" : text.trim();
        if (fallbackSourceUrl == null || fallbackSourceUrl.trim().isEmpty()) return cleaned;
        if (!SourceLinkUtil.sourceUrl(cleaned).isEmpty()) return cleaned;
        return cleaned + (cleaned.isEmpty() ? "" : "\n\n") + "Source: " + fallbackSourceUrl.trim();
    }

    private static String extractSourceUrl(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        try {
            if (value instanceof JSONObject) {
                JSONObject o = (JSONObject) value;
                // Prefer explicit URL-like fields before recursively scanning.
                String[] keys = {"url", "link", "source_url", "sourceUrl"};
                for (String key : keys) {
                    String candidate = o.optString(key, "");
                    if (candidate.startsWith("http://") || candidate.startsWith("https://")) return candidate;
                }
                java.util.Iterator<String> it = o.keys();
                while (it.hasNext()) {
                    String found = extractSourceUrl(o.opt(it.next()));
                    if (!found.isEmpty()) return found;
                }
            } else if (value instanceof JSONArray) {
                JSONArray a = (JSONArray) value;
                for (int i = 0; i < a.length(); i++) {
                    String found = extractSourceUrl(a.opt(i));
                    if (!found.isEmpty()) return found;
                }
            } else if (value instanceof String) {
                String s = ((String) value).trim();
                if (s.startsWith("http://") || s.startsWith("https://")) return s;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://[^\\s\"<>]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(s);
                if (m.find()) return m.group();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String friendlyHttpError(int code, String body, String model) {
        String detail = extractHttpMessage(body);
        if (code == 401) return "Your ChatGPT session was rejected after refresh. Open Orbit settings, sign out, and sign in with ChatGPT again." + detail;
        if (code == 429) return "Your ChatGPT/Codex account allowance appears to be at its current limit." + detail;
        if (code == 404) return "The ChatGPT/Codex backend did not make " + model + " available for this request. Choose another model, or use Orbit's API-relay fallback." + detail;
        if (code == 403) return "Your ChatGPT account or workspace did not allow this Codex-backed request." + detail;
        return "ChatGPT/Codex backend error " + code + detail;
    }

    private static String extractHttpMessage(String body) {
        if (body == null || body.trim().isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(body);
            Object err = o.opt("error");
            if (err instanceof JSONObject) {
                String m = ((JSONObject) err).optString("message", "");
                if (!m.isEmpty()) return " - " + safe(m, 400);
            } else if (err instanceof String && !((String) err).isEmpty()) {
                return " - " + safe((String) err, 400);
            }
            String m = o.optString("message", "");
            if (!m.isEmpty()) return " - " + safe(m, 400);
        } catch (Exception ignored) {}
        return " - " + safe(body.replace('\n', ' '), 400);
    }

    private static String removeEmDashes(String s) {
        if (s == null) return "";
        return s.replace(" \u2014 ", " - ").replace("\u2014", "-");
    }

    private static String bitmapToBase64(Bitmap source) {
        Bitmap bmp = source;
        int max = 1280;
        if (source.getWidth() > max || source.getHeight() > max) {
            float scale = Math.min(max / (float) source.getWidth(), max / (float) source.getHeight());
            bmp = Bitmap.createScaledBitmap(source, Math.max(1, Math.round(source.getWidth() * scale)), Math.max(1, Math.round(source.getHeight() * scale)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.JPEG, 78, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static String safe(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
