package com.orbit.assistant;

import static org.junit.Assert.*;

import android.content.Context;

import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;

/**
 * The Beta 5 failure, reproduced: a visible source and a discovery that never started.
 *
 * <p>On a real Galaxy S25 Ultra, "Show me pictures of a black widow" came back with links and
 * Orbit's own "Open source · commons.wikimedia.org" control, while Rich Answers diagnostics said
 * "Sources received: 0". Every synthetic provenance test was green at the time, because every one
 * of them fed Orbit a hosted-search envelope the device was not actually receiving. These tests
 * start from the other end: a reply that carries no structured provenance at all and only the
 * explicit {@code Source:} marker Orbit already turns into a tappable control.
 *
 * <p>The other half is what must <em>not</em> happen. A Markdown link, a bare URL in prose, a
 * search query and a device-action argument are all things a model wrote, and none of them may
 * cause Orbit to fetch a page.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public class RichAnswerSourceFallbackTest {
    private static final String PROMPT = "Show me pictures of a black widow";
    private static final String LONG_PROMPT = "Search the web and describe what a Northern black "
            + "widow looks like, including its important identifying features.";
    private static final String SOURCE = "https://pubs.example.edu/entry";
    private static final String OTHER = "https://other.example.edu/entry";
    private static final String ANSWER = "A shiny black spider with a red hourglass.";

    private Context context;
    private RichAnswerPageFetcher.PageSource previousPages;
    private RemoteImageLoader.Transport previousImages;
    private final List<String> pages = new CopyOnWriteArrayList<>();

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().putBoolean(Prefs.BACKGROUND_NOTIFICATIONS, false).commit();
        ConversationStore.clear(context);
        RichAnswerTrace.clear(context);
        HostedSearchSchemaTrace.clear(context);
        OrbitRequestManager.resetForTest();
        previousPages = RichAnswerPageFetcher.installSourceForTest(url -> {
            pages.add(url);
            return RichAnswerPageFetcher.parse("<article><h1>Northern black widow</h1>"
                    + "<img src='" + url + "/northern-black-widow.png' width='800' height='600' "
                    + "alt='Northern black widow showing the hourglass'></article>",
                    url, 200, "text/html", 0);
        });
        previousImages = RemoteImageLoader.installTransportForTest(new RemoteImageLoader.Transport() {
            public boolean allowsHost(String url) { return true; }
            public RemoteImageLoader.Response open(String url) {
                byte[] png = TestPng.photo(800, 600, Math.abs(url.hashCode() % 7) + 1);
                return new RemoteImageLoader.Response(200, "image/png", null, png.length,
                        new ByteArrayInputStream(png));
            }
        });
        ConversationStore.save(context, "chat",
                Collections.singletonList(new AssistantClient.History("user", PROMPT)));
    }

    @After public void tearDown() throws Exception {
        drainDiscovery();
        RichAnswerPageFetcher.installSourceForTest(previousPages);
        RemoteImageLoader.installTransportForTest(previousImages);
        OrbitRequestManager.resetForTest();
    }

    private static void drainDiscovery() throws Exception {
        Field field = RichAnswerCoordinator.class.getDeclaredField("EXECUTOR");
        field.setAccessible(true);
        ((ExecutorService) field.get(null)).submit(() -> {}).get(15, TimeUnit.SECONDS);
    }

    private static String marked(String url) { return ANSWER + "\n\nSource: " + url; }

    // ---- the failure this release exists to fix --------------------------------------------------




    /** A late picture from the previous request cannot decorate the answer that replaced it. */
    @Test public void theOldRequestsImageCannotAttachToTheRegeneratedAnswer() throws Exception {
        PendingRequestStore.Item original = complete(marked(SOURCE));
        drainDiscovery();
        AssistantClient.History first = ConversationStore.load(context, "chat").messages.get(1);
        ConversationStore.removeLastAssistantTurn(context, "chat");
        PendingRequestStore.Item regenerated = complete(marked(SOURCE));
        drainDiscovery();

        assertFalse(ConversationStore.attachRichImages(context, "chat", original.id,
                marked(SOURCE), first.richImages, Collections.singletonList(SOURCE)));
        AssistantClient.History stored = ConversationStore.load(context, "chat").messages.get(1);
        assertEquals(regenerated.id, stored.replyRequestId);
    }

    // ---- what must never become provenance --------------------------------------------------------




    private void assertRefused(String answer) throws Exception {
        RichAnswerCoordinator.discover(context, "chat", "request", PROMPT, new AssistantReply(answer));
        drainDiscovery();
        RichAnswerTrace.Attempt trace = RichAnswerTrace.last(context);
        assertNotNull(trace);
        assertEquals(RichAnswerTrace.Provenance.NONE, trace.provenance);
        assertEquals(0, trace.sourcesReceived);
        assertEquals(RichAnswerTrace.Outcome.NO_SOURCE_URLS_RECEIVED, trace.outcome);
        assertTrue("nothing may be fetched for a link the model merely wrote", pages.isEmpty());
    }


    /** A marker under an answer nobody wanted a picture for starts nothing. */
    @Test public void aMarkerOnAnOrdinaryAnswerNeverStartsDiscovery() throws Exception {
        RichAnswerCoordinator.discover(context, "chat", "request", "what is 2 + 2",
                new AssistantReply("4\n\nSource: " + SOURCE));
        drainDiscovery();
        assertTrue(pages.isEmpty());
        assertNull("an ordinary chat answer is not worth a trace slot", RichAnswerTrace.last(context));
    }

    /** Rich Answers switched off means switched off, marker or not. */
    @Test public void theFallbackRespectsTheRichAnswersPreference() throws Exception {
        Prefs.get(context).edit().putBoolean(Prefs.RICH_ANSWERS, false).commit();
        RichAnswerCoordinator.discover(context, "chat", "request", PROMPT,
                new AssistantReply(marked(SOURCE)));
        drainDiscovery();
        assertEquals(RichAnswerTrace.Outcome.DISABLED, RichAnswerTrace.last(context).outcome);
        assertEquals(RichAnswerTrace.Provenance.NONE, RichAnswerTrace.last(context).provenance);
        assertTrue(pages.isEmpty());
    }


    // ---- structured provenance stays primary -------------------------------------------------------



    /** The resolver itself, checked directly on both branches. */
    @Test public void theResolverPrefersStructuredAndOnlyEverReadsTheMarker() {
        AssistantReply structured = new AssistantReply(marked(SOURCE))
                .withSourceUrls(Collections.singletonList(OTHER));
        RichAnswerProvenance.Resolved won = RichAnswerProvenance.resolve(structured, true);
        assertEquals(RichAnswerProvenance.Route.STRUCTURED_HOSTED_SEARCH, won.route);
        assertEquals(Collections.singletonList(OTHER), won.urls);

        RichAnswerProvenance.Resolved fallback =
                RichAnswerProvenance.resolve(new AssistantReply(marked(SOURCE)), true);
        assertEquals(RichAnswerProvenance.Route.EXPLICIT_SOURCE_MARKER, fallback.route);
        assertEquals(Collections.singletonList(SOURCE), fallback.urls);

        RichAnswerProvenance.Resolved refused =
                RichAnswerProvenance.resolve(new AssistantReply(marked(SOURCE)), false);
        assertEquals(RichAnswerProvenance.Route.NONE, refused.route);
        assertFalse(refused.hasSources());
        assertEquals(RichAnswerProvenance.Route.NONE,
                RichAnswerProvenance.resolve(null, true).route);
    }

    // ---- diagnostics ordering ---------------------------------------------------------------------



    private PendingRequestStore.Item complete(String answer) throws Exception {
        PendingRequestStore.Item item = PendingRequestStore.create(context, "chat", PROMPT,
                "", "", false, false, AiSelections.FALLBACK, false, "");
        item = PendingRequestStore.load(context, item.id);
        OrbitRequestWorker.completeProviderReply(context, item, new AssistantReply(answer),
                WorkerAttempt.of(1, false));
        assertTrue(PendingRequestStore.isCommitted(context, item.id));
        return item;
    }
}
