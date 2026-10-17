package com.orbit.assistant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;

import org.json.JSONObject;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Theme Studio Pro: the first Orbit Pro feature that actually draws something.
 *
 * <p>Three failures would each be quiet and expensive, and most of this file is about them.
 *
 * <p>The first is a Beta 1 install looking different after the update. Nobody asked for a change in
 * the shape of their messages or the finish of their floating controls, so every default here has
 * to be the number Orbit already drew, and that has to be asserted against the real constants
 * rather than restated as a literal that could drift with them.
 *
 * <p>The second is premium styling drawing without Pro. There are four routes to it - a tester
 * switching Pro Preview off, a Beta replaced by Stable, a theme file carrying premium fields, and a
 * premium preset applied as a way round the whole thing - and the answer to all four is the same
 * resolver, asked at render time rather than at the controls.
 *
 * <p>The third is the opposite one, and it is the one that would be found last: entitlement
 * lapsing and taking the user's settings with it. Free must stop <em>drawing</em> premium styling
 * without ever erasing it, because a tester flipping the override twice has to get their theme
 * back rather than rebuild it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {29, 35})
public final class ThemeStudioProTest {

    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.get(context).edit().clear().commit();
        OrbitThemeStore.clearForTests(context);
        UiKit.syncTheme(context);
    }

    private void asPro() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertTrue(OrbitProEntitlement.hasPro(context));
    }

    private void asFree() {
        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_FREE);
        assertFalse(OrbitProEntitlement.hasPro(context));
    }

    /** Something premium in every field, so a value surviving by accident cannot pass for a pass. */
    private static OrbitProStyle styled() {
        return OrbitProStyle.of(24, OrbitProStyle.OUTLINE_DEFINED, 190, 160, 150);
    }

    // ---- 6. the defaults are the shipped appearance, not an approximation of it --------------------

    /**
     * Every premium default is the constant Orbit already draws with.
     *
     * <p>Asserted against the constants themselves. Writing 18 and 214 here would pass today and
     * would go on passing on the day somebody retuned {@code RADIUS_BUBBLE}, at which point the
     * default would silently stop being the shipped appearance.
     */
    @Test public void defaultProStyleIsExactlyOrbitsShippedConfiguration() {
        OrbitProStyle style = OrbitProStyle.DEFAULT;
        assertEquals("bubble roundness must default to GrokLink's own corner",
                Math.round(UiKit.RADIUS_BUBBLE), style.bubbleRadiusDp);
        assertEquals("GrokLink has never drawn a bubble outline",
                OrbitProStyle.OUTLINE_OFF, style.bubbleOutline);
        assertEquals("glass opacity must default to the shipped fill alpha",
                OrbitGlass.FILL_ALPHA, style.glassOpacity);
        assertEquals(100, style.glassTint);
        assertEquals(100, style.glassEdge);
        assertTrue(style.isDefault());
    }

    /** And the derivations at those defaults reproduce the shipped numbers exactly. */
    @Test public void defaultGlassDerivationsReproduceTheShippedTreatment() {
        OrbitProStyle style = OrbitProStyle.DEFAULT;
        assertEquals(OrbitGlass.BASE_FILL_ACCENT_SHARE, style.glassFillAccentShare(), 0.0001f);
        assertEquals(OrbitGlass.BASE_BORDER_ACCENT_SHARE, style.glassBorderAccentShare(), 0.0001f);
        assertEquals(OrbitGlass.BASE_HAZE_SHARE, style.glassHazeShare(), 0.0001f);
        assertEquals(OrbitGlass.BASE_HIGHLIGHT_SHARE, style.glassHighlightShare(), 0.0001f);
        assertEquals(OrbitGlass.BORDER_ALPHA, style.glassBorderAlpha());
    }

    // ---- 19. glass defaults reproduce the existing appearance ---------------------------------------


    // ---- 1. Beta 1 data migrates unchanged -----------------------------------------------------------

    /**
     * 1. An install that predates this release keeps the appearance it had.
     *
     * <p>Written as the preferences a Beta 1 install actually holds - the theme keys with no
     * premium ones beside them - rather than as a default-constructed theme, because "the keys are
     * absent" is the specific condition every default here exists to handle.
     */
    @Test public void betaOneAppearanceMigratesWithNothingChanged() {
        Prefs.get(context).edit()
                .putString(Prefs.ACCENT, "violet")
                .putString(Prefs.USER_BUBBLE_COLOR, OrbitTheme.CLASSIC)
                .putString(Prefs.ASSISTANT_BUBBLE_COLOR, OrbitTheme.CLASSIC)
                .putString(Prefs.THEME_SURFACE, OrbitTheme.CLASSIC)
                .putString(Prefs.THEME_BACKGROUND, OrbitTheme.CLASSIC)
                .putBoolean(Prefs.AMOLED_MODE, false)
                .putInt(Prefs.THEME_SCHEMA, OrbitTheme.SCHEMA)
                .commit();

        OrbitTheme active = OrbitThemeStore.active(context);
        assertEquals("violet", active.accent);
        assertTrue("an install with no premium keys is GrokLink as it shipped",
                active.pro.isDefault());
        assertTrue(OrbitThemeStore.activeProStyle(context).isDefault());
    }

    /** 5. And a theme document from before this release does the same. */
    @Test public void anOlderThemeFileGetsTheShippedDefaults() throws Exception {
        JSONObject old = new JSONObject()
                .put("format", OrbitTheme.FORMAT)
                .put("schema", 1)
                .put("id", "t_old")
                .put("name", "From Beta 1")
                .put("accent", "mint")
                .put("userBubble", OrbitTheme.CLASSIC)
                .put("assistantBubble", OrbitTheme.CLASSIC)
                .put("surface", OrbitTheme.CLASSIC)
                .put("background", OrbitTheme.CLASSIC)
                .put("amoled", false);

        OrbitTheme imported = OrbitThemeFileCodec.decode(old.toString());
        assertNotNull("a file with no premium block is still a theme", imported);
        assertEquals("From Beta 1", imported.name);
        assertEquals("mint", imported.accent);
        assertTrue("and gets GrokLink's own styling rather than a mixture",
                imported.pro.isDefault());

        assertNotNull("the same file still parses through the model",
                OrbitTheme.fromJson(old));
        assertTrue(OrbitTheme.fromJson(old).pro.isDefault());
    }

    // ---- 2, 3, 4. theme files -------------------------------------------------------------------------

    /** 4. Premium styling survives a round trip through a theme document. */
    @Test public void proStyleRoundTripsThroughAThemeFile() throws Exception {
        OrbitTheme source = OrbitTheme.custom("Styled", "violet", OrbitTheme.CLASSIC,
                OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false, styled());

        OrbitTheme back = OrbitThemeFileCodec.decode(OrbitThemeFileCodec.encode(source));
        assertNotNull(back);
        assertTrue("every premium field must come back", back.pro.same(styled()));
        assertEquals(source.accent, back.accent);
    }

    /** And through the saved-theme library, which is a different serialiser's job. */
    @Test public void proStyleRoundTripsThroughTheSavedThemeLibrary() {
        OrbitTheme saved = OrbitThemeStore.savePreset(context,
                OrbitTheme.custom("Styled", "violet", OrbitTheme.CLASSIC, OrbitTheme.CLASSIC,
                        OrbitTheme.CLASSIC, OrbitTheme.CLASSIC, false, styled()));
        assertNotNull(saved);

        OrbitTheme reloaded = OrbitThemeStore.preset(context, saved.id);
        assertNotNull(reloaded);
        assertTrue(reloaded.pro.same(styled()));
    }


    /** A file cannot promote itself to an Orbit Pro preset, however it is written. */
    @Test public void aThemeFileCannotClaimPremiumIdentity() throws Exception {
        JSONObject forged = OrbitTheme.builtIn(OrbitTheme.ID_SIGNAL_VIOLET).toJson();
        OrbitTheme imported = OrbitThemeFileCodec.decode(forged.toString());
        assertNotNull(imported);
        assertFalse("an imported theme is never one of GrokLink's premium presets",
                imported.premium());
        assertFalse(imported.builtIn);
        assertFalse(OrbitTheme.ID_SIGNAL_VIOLET.equals(imported.id));
        assertTrue("and it can be applied, because it is just a theme",
                OrbitThemeStore.canApply(context, imported));
    }

    // ---- 7, 8, 9, 10. entitlement at render time -------------------------------------------------------


    /** 8. Pro draws what was stored. */
    @Test public void proResolvesStoredPremiumStyling() {
        asPro();
        assertTrue(OrbitProStyle.resolve(context, styled()).same(styled()));
    }



    // ---- 16, 17. one bubble implementation --------------------------------------------------------------

    /**
     * 16 and 17. Full chat, the overlay and the preview cannot disagree about a bubble.
     *
     * <p>Asserted structurally rather than by comparing two drawables, because the failure this
     * prevents is a future call site writing its own literal again. Every place that draws a
     * conversation bubble must reach {@code UiKit.bubbleSurface}, and none of them may carry a
     * corner radius of their own.
     */
    @Test public void everyConversationBubbleIsDrawnByOneSharedCall() {
        for (String file : new String[]{"ChatActivity.java", "OrbitSession.java",
                "OrbitRichResponseRenderer.java", "ThemePreviewView.java"}) {
            String body = readSource(file);
            assertTrue(file + " must draw its bubbles through UiKit.bubbleSurface",
                    body.contains("UiKit.bubbleSurface("));
            assertFalse(file + " must not carry its own bubble corner radius",
                    body.contains("UiKit.rounded(fill, 18")
                            || body.contains("UiKit.RADIUS_BUBBLE"));
        }
        // And the constant itself is only a default now. Asserted on the form that draws with it -
        // passing it as an argument - rather than on the name, which legitimately appears in prose
        // explaining that this is the default the premium control starts from.
        List<String> readers = new ArrayList<>();
        for (Path source : mainSources()) {
            if (source.getFileName().toString().equals("UiKit.java")) continue;
            String body = read(source);
            if (body.contains("RADIUS_BUBBLE,") || body.contains("RADIUS_BUBBLE)")) {
                readers.add(source.getFileName().toString());
            }
        }
        assertTrue("RADIUS_BUBBLE is a default, not a drawing instruction: " + readers,
                readers.isEmpty());
    }

    /** The shared call really does follow the setting, in both directions. */
    @Test public void theSharedBubbleCallFollowsTheProStyle() {
        asPro();
        GradientDrawable squared = UiKit.bubbleSurface(context, 0xFF202030,
                UiKit.accent(context), OrbitProStyle.DEFAULT.withBubbleRadiusDp(9));
        GradientDrawable round = UiKit.bubbleSurface(context, 0xFF202030,
                UiKit.accent(context), OrbitProStyle.DEFAULT.withBubbleRadiusDp(26));
        assertTrue("a squarer bubble must actually have a smaller corner",
                squared.getCornerRadius() < round.getCornerRadius());
        assertEquals("and the default corner must be GrokLink's own",
                UiKit.dp(context, UiKit.RADIUS_BUBBLE),
                Math.round(UiKit.bubbleSurface(context, 0xFF202030,
                        UiKit.accent(context), OrbitProStyle.DEFAULT).getCornerRadius()));
    }

    /** The outline is off by default, visible when asked for, and readable against its bubble. */
    @Test public void theBubbleOutlineIsOffByDefaultAndReadableWhenOn() {
        assertEquals(0, OrbitProStyle.DEFAULT.bubbleOutlineWidthPx(context));
        assertEquals(0, OrbitProStyle.DEFAULT.bubbleOutlineAlpha());

        OrbitProStyle subtle = OrbitProStyle.DEFAULT.withBubbleOutline(OrbitProStyle.OUTLINE_SUBTLE);
        OrbitProStyle defined =
                OrbitProStyle.DEFAULT.withBubbleOutline(OrbitProStyle.OUTLINE_DEFINED);
        assertTrue(subtle.bubbleOutlineWidthPx(context) > 0);
        assertTrue("defined must read more strongly than subtle",
                defined.bubbleOutlineAlpha() > subtle.bubbleOutlineAlpha());
        assertTrue("and never as a thick border",
                defined.bubbleOutlineWidthPx(context) <= UiKit.dp(context, 2));

        // On a dark bubble and on a light one alike, the outline has to separate from the fill it
        // borders rather than disappear into it.
        for (int fill : new int[]{0xFF12141C, 0xFFE8E9F0, 0xFF3A2E63}) {
            int outline = defined.bubbleOutlineColor(UiKit.accent(context), fill);
            assertTrue("an outline must be visible against " + Integer.toHexString(fill),
                    OrbitContrast.contrastRatio(outline | 0xFF000000, fill) > 1.2);
        }
    }

    // ---- 18. one glass implementation --------------------------------------------------------------------

    /**
     * 18. {@code OrbitGlass} is still the only floating-glass implementation.
     *
     * <p>The premium controls were the obvious moment to grow a second one - a "Pro glass" beside
     * the existing treatment - which would have left Chats and the Vault on the old path and the
     * preview on the new one.
     */
    @Test public void orbitGlassRemainsTheOnlyFloatingGlassImplementation() {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("OrbitGlass.java")) continue;
            String body = read(source);
            if (body.contains("class OrbitProGlass") || body.contains("class GlassSurface")
                    || body.contains("premiumGlass(")) {
                offenders.add(name);
            }
        }
        assertTrue("there must be exactly one glass: " + offenders, offenders.isEmpty());

        // And every consumer reaches it rather than assembling its own translucent surface.
        // GlassStylePreview replaced ThemePreviewView here in v0.8.0.0-beta.3: the glass sample
        // moved out of the general overview and into a dedicated preview sitting directly above
        // the glass controls. Which file draws it changed; that OrbitGlass draws it did not.
        for (String file : new String[]{"MainActivity.java", "OrbitVaultActivity.java",
                "GlassStylePreview.java"}) {
            assertTrue(file + " must get its glass from OrbitGlass",
                    readSource(file).contains("OrbitGlass."));
        }
    }

    /** Each glass control moves the treatment, and each stays inside its own ceiling. */
    @Test public void eachGlassControlMovesTheTreatmentWithinItsBounds() {
        asPro();
        OrbitGlass.Palette neutral = OrbitGlass.Palette.of(
                OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault()),
                OrbitProStyle.DEFAULT.withGlassTint(OrbitProStyle.GLASS_TINT_MIN));
        OrbitGlass.Palette tinted = OrbitGlass.Palette.of(
                OrbitThemeTokens.resolve(context, OrbitTheme.orbitDefault()),
                OrbitProStyle.DEFAULT.withGlassTint(OrbitProStyle.GLASS_TINT_MAX));
        assertTrue("tint must change the haze",
                OrbitGlass.hazeColor(neutral) != OrbitGlass.hazeColor(tinted));

        OrbitProStyle dim = OrbitProStyle.DEFAULT.withGlassEdge(OrbitProStyle.GLASS_EDGE_MIN);
        OrbitProStyle lit = OrbitProStyle.DEFAULT.withGlassEdge(OrbitProStyle.GLASS_EDGE_MAX);
        assertTrue("edge must change the hairline",
                lit.glassBorderAlpha() > dim.glassBorderAlpha());
        assertTrue("and never become a solid border",
                lit.glassBorderAlpha() <= OrbitProStyle.MAX_BORDER_ALPHA);
        assertTrue("the accent share stays a tint on the fill",
                OrbitProStyle.DEFAULT.withGlassTint(OrbitProStyle.GLASS_TINT_MAX)
                        .glassFillAccentShare() <= OrbitProStyle.MAX_FILL_ACCENT_SHARE);
    }

    /**
     * Readability survives the extremes, which is what the opacity bounds are for.
     *
     * <p>The glass is translucent, so what a label is read against is the fill composited over the
     * page. At both ends of the allowed range that has to stay a surface text can sit on.
     */
    @Test public void glassStaysReadableAtEveryAllowedOpacity() {
        for (int opacity : new int[]{OrbitProStyle.GLASS_OPACITY_MIN,
                OrbitProStyle.GLASS_OPACITY_DEFAULT, OrbitProStyle.GLASS_OPACITY_MAX}) {
            for (OrbitTheme preset : OrbitTheme.builtIns()) {
                OrbitGlass.Palette palette = OrbitGlass.Palette.of(
                        OrbitThemeTokens.resolve(context, preset),
                        OrbitProStyle.DEFAULT.withGlassOpacity(opacity));
                int fill = OrbitGlass.effectiveFill(palette);
                int ink = OrbitContrast.inkOn(fill);
                assertTrue(preset.name + " glass at opacity " + opacity + " must stay readable",
                        OrbitContrast.contrastRatio(ink, fill) >= OrbitContrast.BODY_TEXT_MIN);
            }
        }
    }

    /**
     * No blur was added, and the reason it was never added is still recorded.
     *
     * <p>Asserted on the calls rather than on the words, because {@code OrbitGlass}'s own
     * documentation names both APIs in the course of explaining why neither can be used. A test
     * that banned the name would force Orbit to delete the explanation to keep the guard.
     */
    @Test public void noAdjustableBlurWasIntroduced() {
        for (Path source : mainSources()) {
            String body = read(source);
            assertFalse(source.getFileName() + " must not blur a backdrop per frame",
                    body.contains(".setRenderEffect(")
                            || body.contains(".setBackgroundBlurRadius("));
        }
        assertTrue("and the reason must stay written down",
                readSource("OrbitGlass.java").contains("There is no blur"));

        // No control may offer one either, faked or real. Checked against the strings a person
        // would actually see rather than against the word appearing anywhere in the file.
        String studio = readSource("ThemeStudioActivity.java");
        assertFalse("no premium control may claim to adjust blur",
                studio.contains("\"Blur") || studio.contains("\"blur")
                        || studio.contains("Glass blur"));
    }

    // ---- 11 to 15. premium presets -------------------------------------------------------------------------


    /** 12 and 13. Both premium presets are marked premium through stable identity. */
    @Test public void thePremiumPresetsAreMarkedThroughStableMetadata() {
        OrbitTheme violet = OrbitTheme.builtIn(OrbitTheme.ID_SIGNAL_VIOLET);
        OrbitTheme glass = OrbitTheme.builtIn(OrbitTheme.ID_NEBULA_GLASS);
        OrbitTheme aurora = OrbitTheme.builtIn(OrbitTheme.ID_AURORA);
        OrbitTheme nova = OrbitTheme.builtIn(OrbitTheme.ID_NOVA_ULTRA);
        assertNotNull(violet);
        assertNotNull(glass);
        assertNotNull(aurora);
        assertNotNull(nova);
        assertEquals("Signal Violet", violet.name);
        assertEquals("Nebula Glass", glass.name);
        assertEquals("Aurora", aurora.name);
        assertEquals("Nova Ultra", nova.name);
        for (OrbitTheme preset : new OrbitTheme[]{violet, glass, aurora, nova}) {
            assertTrue(preset.name + " must be premium", preset.premium());
            assertTrue(preset.name + " must be premium by id",
                    OrbitTheme.isPremiumId(preset.id));
        }

        assertEquals("the four, and only the four", 4, OrbitTheme.premiumBuiltIns().size());
        for (OrbitTheme preset : OrbitTheme.freeBuiltIns()) assertFalse(preset.premium());

        // Each actually uses the premium layer, or it is premium in name only.
        assertFalse("Signal Violet must use the premium styling layer", violet.pro.isDefault());
        assertFalse("Nebula Glass must use the premium styling layer", glass.pro.isDefault());
        assertTrue("and Nebula Glass must be the glass-forward one",
                glass.pro.glassTint > OrbitProStyle.GLASS_TINT_DEFAULT
                        && glass.pro.glassEdge > OrbitProStyle.GLASS_EDGE_DEFAULT);

        // The two v0.8.0.0-beta.4 presets exist to show the two new background modes, so each is
        // asserted to actually be in that mode rather than merely to be new and premium.
        assertEquals("Aurora is the linear one",
                OrbitProStyle.BACKGROUND_LINEAR, aurora.pro.backgroundMode);
        assertEquals("Nova Ultra is the glow one",
                OrbitProStyle.BACKGROUND_GLOW, nova.pro.backgroundMode);
        // A glow preset that shipped with AMOLED on would suppress its own reason for existing.
        assertFalse("Nova Ultra must not hide its own glow behind AMOLED", nova.amoled);
        assertFalse("Aurora must not hide its own gradient behind AMOLED", aurora.amoled);
        // And Nova Ultra has to be a theme of its own rather than Signal Violet turned up.
        assertFalse("Nova Ultra must not be Signal Violet with a glow",
                nova.sameColours(violet.withPro(nova.pro)));
        assertTrue("Nova Ultra sits on a darker page than Signal Violet's surfaces",
                !nova.background.equals(violet.background));
    }



    /** 15. And Pro Preview applies them normally. */
    @Test public void proPreviewCanApplyPremiumPresets() {
        asPro();
        for (OrbitTheme preset : OrbitTheme.premiumBuiltIns()) {
            assertTrue(preset.name + " must apply on Pro",
                    OrbitThemeStore.applyActive(context, preset));
            OrbitTheme active = OrbitThemeStore.active(context);
            assertEquals(preset.id, active.id);
            assertTrue(preset.name + "'s styling must be what GrokLink draws",
                    OrbitProStyle.live(context).same(preset.pro));
        }
    }

    /** A premium preset is never offered as a first-run starting point. */
    @Test public void onboardingNeverOffersAPremiumPreset() {
        for (OrbitTheme preset : OrbitThemeStore.onboardingPresets()) {
            assertFalse(preset.name + " must not be offered during setup", preset.premium());
        }
    }

    // ---- 20. the preview and the app resolve the same way -----------------------------------------------------


    // ---- 21 to 25. the entitlement boundary --------------------------------------------------------------------


    /** 22. And the premium ones do use the central API rather than anything of their own. */
    @Test public void premiumStylingUsesTheCentralEntitlementApi() {
        assertTrue("the styling layer must be what asks",
                readSource("OrbitProStyle.java").contains("OrbitProEntitlement.hasPro("));
        assertTrue("and Theme Studio asks it for the section it draws",
                readSource("ThemeStudioActivity.java").contains("OrbitProEntitlement.hasPro("));

        // Every caller in the app, named, so a new one is a deliberate change rather than a drift.
        List<String> callers = new ArrayList<>();
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("OrbitProEntitlement.java")) continue;
            if (read(source).contains("OrbitProEntitlement.hasPro(")) callers.add(name);
        }
        java.util.Collections.sort(callers);
        assertEquals("the entitlement is asked in exactly these places: " + callers,
                java.util.Arrays.asList("DeckActivity.java", "DeckLayoutStore.java",
                        "DeckTileAppearance.java", "OrbitProStyle.java", "OrbitThemeStore.java",
                        "ThemeStudioActivity.java"),
                callers);
    }

    /** 23. No premium feature reaches past the boundary for the developer preview preference. */
    @Test public void noPremiumFeatureReadsTheDeveloperPreviewPreference() {
        for (Path source : mainSources()) {
            String name = source.getFileName().toString();
            if (name.equals("OrbitProEntitlement.java") || name.equals("Prefs.java")
                    || name.equals("DiagnosticsActivity.java")) continue;
            String body = read(source);
            assertFalse(name + " must not read the preview override directly",
                    body.contains("proPreviewSelected(") || body.contains("Prefs.PRO_PREVIEW"));
        }
    }

    /** 24. And nothing premium knows a store exists. */
    @Test public void noPremiumFeatureKnowsAboutBilling() {
        for (String file : new String[]{"OrbitProStyle.java", "OrbitTheme.java",
                "OrbitThemeStore.java", "ThemeStudioActivity.java", "ThemePreviewView.java",
                "OrbitGlass.java", "OrbitSlider.java"}) {
            String body = readSource(file);
            for (String billing : new String[]{"BillingClient", "com.android.billingclient",
                    "ProductDetails", "purchaseToken", "Google Play Billing"}) {
                assertFalse(file + " must not mention " + billing, body.contains(billing));
            }
        }
    }

    /**
     * And Theme Studio offers no purchase, because there is nothing to buy.
     *
     * <p>Checked against quoted literals, which is what reaches a person, rather than against the
     * words anywhere in the file. Theme Studio's own comments say "no price, no checkout" while
     * explaining the locked state, and a test that could not tell those apart would be asserting
     * that Orbit must never write down why it does not sell anything.
     */
    @Test public void themeStudioNeverOffersAPurchase() {
        String studio = readSource("ThemeStudioActivity.java");
        for (String selling : new String[]{"\"Upgrade", "\"Unlock Pro", "\"Buy ", "\"Subscribe",
                "\"Restore purchase", "\"Get GrokLink Pro", "\"Continue to checkout"}) {
            assertFalse("Theme Studio must not read like a store: " + selling,
                    studio.contains(selling));
        }
        assertTrue("the locked state explains the developer override instead",
                readSource("ThemeStudioActivity.java").contains("Enable Pro Preview in GrokLink Diagnostics"));
        assertTrue("and only on a build that may honour one, which OrbitProEntitlement decides",
                readSource("ThemeStudioActivity.java").contains("OrbitProEntitlement.lockedGuidance("));
    }

    /**
     * 25. Phase 0's Stable protection is untouched.
     *
     * <p>Re-asserted here rather than left to the Phase 0 file, because this is the release that
     * gave the override something to unlock. The rule is the same one: a Stable build never reads
     * the stored preview selection, so a premium theme styled on a Beta draws as Orbit's own after
     * a Stable is installed over it, and the values stay for the next Beta.
     */
    @Test public void stablePreviewProtectionSurvivesThisRelease() {
        assertFalse(OrbitProEntitlement.previewAvailable(false, "0.8.0.0"));
        assertTrue(OrbitProEntitlement.previewAvailable(false, "0.8.0.0-beta.2"));

        Prefs.setProPreview(context, Prefs.PRO_PREVIEW_PRO);
        assertFalse("a Stable build must resolve Free with the Beta's choice still stored",
                OrbitProEntitlement.resolve(
                        OrbitProEntitlement.previewAvailable(false, "0.8.0.0"),
                        Prefs.proPreviewSelected(context),
                        OrbitProEntitlement.providers(), context));
        assertTrue("and the choice is kept for the next Beta",
                Prefs.proPreviewSelected(context));
    }

    // ---- the editor's own contract ----------------------------------------------------------------------------

    /**
     * The premium controls edit the draft and nothing else.
     *
     * <p>Theme Studio's whole model is that editing changes a draft and Apply changes Orbit. A
     * slider that wrote a preference as it was dragged would break that for the five newest
     * controls only, which is exactly the kind of inconsistency nobody notices until a drag is
     * undone by Revert and the glass stays changed.
     */
    @Test public void theProControlsEditTheDraftRatherThanPreferences() {
        String studio = readSource("ThemeStudioActivity.java");
        assertTrue("premium edits go through the draft", studio.contains("draft.withPro("));
        assertTrue("and are applied by the same Apply everything else uses",
                studio.contains("OrbitThemeStore.applyActive(this, draft)"));
        assertFalse("a slider must never write a preference as it moves",
                studio.contains("Prefs.get(this).edit()"));
    }

    /** The Pro section is rebuilt when entitlement changes, rather than cached for the process. */
    @Test public void themeStudioRefreshesWhenEntitlementChanges() {
        String studio = readSource("ThemeStudioActivity.java");
        int resume = studio.indexOf("protected void onResume()");
        assertTrue("Theme Studio must have an onResume", resume > 0);
        String body = studio.substring(resume, Math.min(studio.length(), resume + 600));
        assertTrue("onResume must re-ask the entitlement",
                body.contains("OrbitProEntitlement.hasPro(this)"));
        // An entitlement change is the one thing allowed to rebuild the Pro card, because what
        // that card contains genuinely differs between Free and Pro. Beta 3 split that from the
        // ordinary draft updates, which must never rebuild a control.
        assertTrue("and rebuild the Pro section when the answer moved",
                body.contains("rebuildProSection()"));
        assertTrue("then bring every control onto the current draft",
                body.contains("syncAllToDraft()"));
    }

    // ---- reading the source tree -------------------------------------------------------------------------------

    private static int countOccurrences(String body, String needle) {
        int count = 0;
        for (int at = body.indexOf(needle); at >= 0; at = body.indexOf(needle, at + 1)) count++;
        return count;
    }

    private static String readSource(String fileName) {
        return read(sourceRoot().resolve(fileName));
    }

    /** The same read, for the Beta 3 interaction tests, so the repository walk lives in one place. */
    static String readSourceFile(String fileName) {
        return readSource(fileName);
    }

    private static Path sourceRoot() {
        Path start = Paths.get("").toAbsolutePath();
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.isRegularFile(directory.resolve("settings.gradle"))) {
                return directory.resolve("app/src/main/java/com/orbit/assistant");
            }
        }
        throw new AssertionError("repository root was not found above " + start);
    }

    /** Every Orbit source file, shared with the Beta 4 architecture tests. */
    static List<Path> mainSources() {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceRoot())) {
            walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(found::add);
        } catch (Exception e) {
            throw new AssertionError("could not read the main source tree", e);
        }
        assertTrue("the main source tree must have been found", found.size() > 100);
        return found;
    }

    private static String read(Path file) {
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("could not read " + file, e);
        }
    }
}
