package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * The one-time move from intelligence modes to explicit selections, and per-chat selections.
 *
 * <p>Every legacy mode is mapped deterministically, a valid Custom choice is preserved, corrupt
 * values fall back safely, the migration runs once, and nothing it does can later overwrite a
 * choice the user makes in the new controls.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class AiSelectionMigrationTest {
    private Context context;

    private static final AiSelection LUNA_MEDIUM =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.MEDIUM);
    private static final AiSelection LUNA_LOW =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.LUNA, AiStrength.LOW);
    private static final AiSelection SOL_HIGH =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.SOL, AiStrength.HIGH);
    private static final AiSelection ASTRA_MAX =
            AiSelection.of(Prefs.PROVIDER_CHATGPT, OrbitModelCatalog.ASTRA, AiStrength.MAX);

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        ConversationStore.clear(context);
    }

    private void legacy(String mode, String model, String reasoning) {
        SharedPreferences.Editor e = Prefs.get(context).edit();
        if (mode != null) e.putString(Prefs.INTELLIGENCE_MODE, mode);
        if (model != null) e.putString(Prefs.MODEL, model);
        if (reasoning != null) e.putString(Prefs.REASONING, reasoning);
        e.commit();
    }

    // ---- mapping -------------------------------------------------------------------------------







    // ---- once, and never over a new choice -----------------------------------------------------




    @Test public void legacyKeysAreLeftInPlaceForDowngrades() {
        legacy("deep", "gpt-5.6-sol", "high");
        AiSelections.globalDefault(context);
        SharedPreferences p = Prefs.get(context);
        assertEquals("deep", p.getString(Prefs.INTELLIGENCE_MODE, ""));
        assertEquals("gpt-5.6-sol", p.getString(Prefs.MODEL, ""));
        assertEquals("high", p.getString(Prefs.REASONING, ""));
    }

    // ---- chats ---------------------------------------------------------------------------------

    /** Writes a chat the way 0.8.2.0 did: an intelligenceMode field and no aiSelection. */
    private void legacyChat(String id, String mode) throws Exception {
        SharedPreferences store = context.getSharedPreferences("orbit_conversations", Context.MODE_PRIVATE);
        JSONArray all = new JSONArray(store.getString("items_v1", "[]"));
        JSONObject chat = new JSONObject().put("id", id).put("title", "Old " + id)
                .put("updatedAt", 1L).put("intelligenceMode", mode)
                .put("messages", new JSONArray().put(new JSONObject()
                        .put("role", "user").put("content", "hello " + id)));
        all.put(chat);
        store.edit().putString("items_v1", all.toString()).commit();
    }





    // ---- per chat ------------------------------------------------------------------------------

    private void chat(String id) {
        List<AssistantClient.History> history = new ArrayList<>();
        history.add(new AssistantClient.History("user", "question " + id));
        ConversationStore.save(context, id, history);
    }





    @Test public void encodingRoundTripsAndRejectsJunk() {
        assertEquals(ASTRA_MAX, AiSelection.decode(ASTRA_MAX.encode()));
        AiSelection local = AiSelections.resolve(AiSelection.of("local", "orbit-local", null));
        assertEquals(local, AiSelection.decode(local.encode()));
        assertNull(AiSelection.decode(""));
        assertNull(AiSelection.decode("garbage"));
        assertNull(AiSelection.decode("2|chatgpt|gpt-6-luna|low"));
        assertNull(AiSelection.decode("1||gpt-6-luna|low"));
    }

}
