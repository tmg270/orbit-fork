package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Retry as a durable answer variant, and one-turn "Send with" (0.8.3.0-beta.3).
 *
 * <p>A retry is a request like any other until it finishes: it survives process death with its
 * target frozen, writes only a finished answer, and a retry that fails or is stopped early leaves
 * the answer it was asked beside exactly where it was. A one-turn selection is frozen with its
 * request and never becomes the chat's or the app's selection.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AnswerVariantRequestTest {
    private Context context;
    private final List<String> cancelledWork = new ArrayList<>();
    private static final AiSelection ASTRA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.ASTRA, AiStrength.HIGH);
    private static final AiSelection TERRA = AiSelection.of(Prefs.PROVIDER_CHATGPT,
            OrbitModelCatalog.GPT_5_6_TERRA, AiStrength.MEDIUM);
    private static final AiSelection CLAUDE = AiSelection.of(Prefs.PROVIDER_ANTHROPIC,
            OrbitModelCatalog.CLAUDE_SONNET_5_5, AiStrength.HIGH);
    private static final AiSelection GROK = AiSelection.of(Prefs.PROVIDER_XAI,
            OrbitModelCatalog.GROK_4_7, AiStrength.XHIGH);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).commit();
        ConversationStore.clear(context);
        OrbitRequestManager.resetForTest();
        OrbitRequestManager.setWorkCanceller(cancelledWork::add);
        context.getSharedPreferences("orbit_pending_requests", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private String chat() {
        String id = ConversationStore.newId();
        ConversationStore.save(context, id, Arrays.asList(new AssistantClient.History("user", "Explain tides"),
                new AssistantClient.History("assistant", "Answer A")
                        .withReplyProvenance("first", Collections.emptyList())));
        ConversationStore.setSelection(context, id, TERRA);
        return id;
    }

    private PendingRequestStore.Item variantRequest(String id, AiSelection selection) {
        List<AssistantClient.History> path = ConversationStore.load(context, id).messages;
        String target = PendingRequestStore.variantTarget(1, ConversationBranches.parentKey(path, 1));
        return PendingRequestStore.createVariant(context, id, "Explain tides", "",
                Collections.emptyList(), false, false, selection, false, "", target);
    }



    @Test public void aRetryStoppedBeforeAnyTextChangesNothing() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, TERRA);
        assertTrue(OrbitRequestManager.cancel(context, item.id));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("Answer A", chat.messages.get(1).content);
        assertFalse(chat.isBranched());
        assertFalse("the original answer is not marked stopped", chat.messages.get(1).isStopped());
    }

    @Test public void aRetryStoppedMidAnswerKeepsItsPartialAsAStoppedVariant() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, TERRA);
        OrbitRequestManager.dispatchDelta(item.id, "Partial B");
        assertTrue(OrbitRequestManager.cancel(context, item.id));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals("Partial B", chat.messages.get(1).content);
        assertTrue(chat.messages.get(1).isStopped());
        assertEquals("Answer A", chat.forkAt(1).variants.get(0).messages.get(0).content);
        assertFalse(chat.forkAt(1).variants.get(0).messages.get(0).isStopped());
    }

    @Test public void aRetryOfAChangedChatIsNotWritten() {
        String id = chat();
        PendingRequestStore.Item item = variantRequest(id, TERRA);
        ConversationStore.clearMessages(context, id);
        ConversationStore.save(context, id, Collections.singletonList(
                new AssistantClient.History("user", "Something else")));
        OrbitRequestWorker.completeProviderReply(context, item, new AssistantReply("Late"),
                WorkerAttempt.of(1, false));
        ConversationStore.Conversation chat = ConversationStore.load(context, id);
        assertEquals(1, chat.messages.size());
        assertEquals(PendingRequestStore.FAILED, PendingRequestStore.load(context, item.id).status);
    }


    // ---- send with -------------------------------------------------------------------------------




}
