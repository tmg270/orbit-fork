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
 * Builds the instruction text and Responses-style request body GrokLink measures for the
 * context meter, plus the shared hosted-search and quote helpers. Network-free; formerly part of
 * the upstream ChatGPT client, which GrokLink does not ship.
 */
final class ActionPromptBuilder {
    private ActionPromptBuilder() {}
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
            " GrokLink supplies the pictures for this answer itself. Do not emit Markdown image syntax or any direct image embed; an image you write will not be displayed. Ordinary Markdown links to pages, galleries and sources remain welcome and unchanged. " +
            "GrokLink attaches its own sourced pictures under your answer after you have finished writing, and how many of them survive real-world fetching is not knowable while you write. So never promise a number of pictures, never describe what is about to appear, and never claim the pictures are different from each other: no here are three photos, no I will attach two images, no these are two separate photographs, no below you will find. Write neutrally instead, for example Here are some useful visual references, or simply answer the question.";

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
                        : "\n\nGrokLink has supplied local notification history in the current user message. " +
                          "Use that supplied history to answer the user's notification question. " +
                          "Do not say you cannot access notification history when this context is present.";
        String task = trustedTaskContext == null ? "" : trustedTaskContext.trim();
        String trustedTaskInstruction = task.isEmpty() ? "" :
                "\n\nTrusted GrokLink task state derived from the user's direct corrections (not from screen content):\n" + task;
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
