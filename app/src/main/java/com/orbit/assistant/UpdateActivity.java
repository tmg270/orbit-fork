package com.orbit.assistant;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.method.LinkMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.Locale;

/** Orbit-styled stable-release update UI. Installation always remains user-confirmed Android UI. */
public final class UpdateActivity extends Activity {
    private TextView status;
    private Button action;

    /** Interactive Back for this page. Its classification lives in OrbitNavigation. */
    private OrbitPredictiveBack navigation;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiKit.syncTheme(this);
        Window window = getWindow();
        window.setStatusBarColor(UiKit.BG);
        window.setNavigationBarColor(UiKit.BG);
        View content = buildContent();
        setContentView(content);
        UiKit.applyActivityInsets(this, content, true);
        navigation = OrbitPredictiveBack.install(this);
        // GrokLink never updates itself. Clear any update check an older build scheduled.
        try {
            androidx.work.WorkManager.getInstance(getApplicationContext())
                    .cancelUniqueWork("orbit-stable-update-check");
        } catch (Exception ignored) {}
    }

    @Override protected void onResume() {
        super.onResume();
        UiPresence.enter(this);
    }

    @Override protected void onPause() {
        UiPresence.leave(this);
        super.onPause();
    }


    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        OrbitBackground.applyPage(scroll);
        scroll.setForceDarkAllowed(false);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int padding = UiKit.dp(this, 20);
        page.setPadding(padding, UiKit.dp(this, 30), padding, UiKit.dp(this, 48));
        scroll.addView(page, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this);
        back.setImageResource(R.drawable.ic_back);
        back.setColorFilter(UiKit.TEXT);
        back.setBackground(UiKit.ripple(UiKit.SURFACE_2, UiKit.TEXT, 18, this));
        back.setPadding(UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10), UiKit.dp(this, 10));
        back.setOnClickListener(v -> navigation.performBack());
        header.addView(back, new LinearLayout.LayoutParams(UiKit.dp(this, 44), UiKit.dp(this, 44)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(UiKit.dp(this, 13), 0, 0, 0);
        titles.addView(UiKit.text(this, "About GrokLink", 24, UiKit.TEXT, true));
        titles.addView(UiKit.text(this, "Unofficial Grok-only assistant", 13, UiKit.MUTED, false));
        header.addView(titles, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        page.addView(header);

        TextView section = UiKit.text(this, "GROKLINK", 12, UiKit.MUTED, true);
        section.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams sectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sectionLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 28), 0, UiKit.dp(this, 9));
        page.addView(section, sectionLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 18),
                UiKit.dp(this, 18), UiKit.dp(this, 18));
        card.setBackground(UiKit.outlined(
                UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 38), 24, this));
        card.setElevation(UiKit.dp(this, 2));

        // The name line carries a restrained Beta pill when the running APK is itself a Beta
        // build. That is a fact about the installed binary, not about the update channel, and the
        // two are deliberately shown separately.
        LinearLayout nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.addView(UiKit.text(this, "GrokLink", 18, UiKit.TEXT, true));
        if (OrbitVersion.installedIsBeta()) {
            TextView pill = betaPill("BETA BUILD");
            LinearLayout.LayoutParams pillLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pillLp.setMargins(UiKit.dp(this, 9), UiKit.dp(this, 2), 0, 0);
            nameRow.addView(pill, pillLp);
        }
        card.addView(nameRow);

        TextView current = UiKit.text(this,
                "Current version: " + OrbitVersion.installedDisplayName()
                        + " (" + BuildConfig.VERSION_CODE + ")",
                13, UiKit.MUTED, false);
        current.setPadding(0, UiKit.dp(this, 5), 0, UiKit.dp(this, 11));
        card.addView(current);

        status = UiKit.text(this, "GrokLink does not update itself. New builds are published on "
                + "the GitHub fork and install over this one, keeping your sign-in and chats.",
                14, UiKit.TEXT, false);
        status.setLineSpacing(0, 1.12f);
        status.setPadding(0, 0, 0, UiKit.dp(this, 14));
        card.addView(status);

        action = primaryButton("Get new builds");
        action.setOnClickListener(v -> openExternal(this, BUILDS_URL));
        card.addView(action, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 48)));

        Button whatsNew = secondaryButton("What's New");
        whatsNew.setOnClickListener(v -> startActivity(new Intent(this, WhatsNewActivity.class)));
        LinearLayout.LayoutParams whatsNewLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(this, 44));
        whatsNewLp.setMargins(0, UiKit.dp(this, 10), 0, 0);
        card.addView(whatsNew, whatsNewLp);
        page.addView(card);

        TextView roadmapSection = UiKit.text(this, "ROADMAP", 12, UiKit.MUTED, true);
        roadmapSection.setLetterSpacing(0.13f);
        LinearLayout.LayoutParams roadmapSectionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        roadmapSectionLp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, 22), 0, UiKit.dp(this, 9));
        page.addView(roadmapSection, roadmapSectionLp);

        LinearLayout roadmap = new LinearLayout(this);
        roadmap.setOrientation(LinearLayout.HORIZONTAL);
        roadmap.setGravity(Gravity.CENTER_VERTICAL);
        roadmap.setPadding(UiKit.dp(this, 18), UiKit.dp(this, 16),
                UiKit.dp(this, 16), UiKit.dp(this, 16));
        roadmap.setBackground(UiKit.rippleOutlined(UiKit.SURFACE,
                UiKit.withAlpha(UiKit.accent(this), 38), UiKit.accent(this), 22, this));
        roadmap.setElevation(UiKit.dp(this, 2));
        roadmap.setClickable(true);
        roadmap.setFocusable(true);
        LinearLayout roadmapCopy = new LinearLayout(this);
        roadmapCopy.setOrientation(LinearLayout.VERTICAL);
        roadmapCopy.addView(UiKit.text(this, "Roadmap", 17, UiKit.TEXT, true));
        TextView roadmapDescription = UiKit.text(this,
                "See what's next for GrokLink", 12, UiKit.MUTED, false);
        roadmapDescription.setPadding(0, UiKit.dp(this, 3), 0, 0);
        roadmapCopy.addView(roadmapDescription);
        roadmap.addView(roadmapCopy, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        roadmap.addView(UiKit.text(this, "›", 28, UiKit.accent(this), false));
        roadmap.setOnClickListener(v -> startActivity(new Intent(this, RoadmapActivity.class)));
        UiKit.pressScale(roadmap);
        page.addView(roadmap);

        page.addView(linksSection(), sectionLp(22));
        page.addView(linksCard());

        TextView privacy = UiKit.text(this, "GrokLink is a modified fork of Orbit Assistant by lpnovi, used under the Mozilla Public License 2.0. It is not made, endorsed or supported by xAI. Grok and SuperGrok are xAI trademarks.",
                12, UiKit.MUTED, false);
        privacy.setLineSpacing(0, 1.12f);
        LinearLayout.LayoutParams privacyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        privacyLp.setMargins(UiKit.dp(this, 3), UiKit.dp(this, 15), UiKit.dp(this, 3), 0);
        page.addView(privacy, privacyLp);
        UiKit.applyTypography(page);
        return scroll;
    }

    /** One external page the About screen links to. */
    static final class AboutLink {
        final String title;
        final String subtitle;
        final String uri;

        AboutLink(String title, String subtitle, String uri) {
            this.title = title;
            this.subtitle = subtitle;
            this.uri = uri;
        }
    }

    static final String BUILDS_URL = "https://github.com/tmg270/orbit-fork/actions/workflows/build-apk.yml";

    /** Everything on the Links card, in order. A new link is one more entry here. */
    static final AboutLink[] LINKS = {
            new AboutLink("GrokLink on GitHub", "Source code and new builds",
                    "https://github.com/tmg270/orbit-fork"),
            new AboutLink("Orbit Assistant", "The original app GrokLink is built on, by lpnovi",
                    "https://github.com/lpnovi/Orbit-Assistant"),
            new AboutLink("Licence", "Mozilla Public License 2.0",
                    "https://www.mozilla.org/MPL/2.0/"),
    };

    private TextView linksSection() {
        TextView label = UiKit.text(this, "LINKS", 12, UiKit.MUTED, true);
        label.setLetterSpacing(0.13f);
        return label;
    }

    private LinearLayout.LayoutParams sectionLp(int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(UiKit.dp(this, 4), UiKit.dp(this, topDp), 0, UiKit.dp(this, 9));
        return lp;
    }

    private LinearLayout linksCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6), UiKit.dp(this, 6));
        card.setBackground(UiKit.outlined(
                UiKit.SURFACE, UiKit.withAlpha(UiKit.accent(this), 38), 22, this));
        card.setElevation(UiKit.dp(this, 2));
        for (AboutLink link : LINKS) card.addView(linkRow(link));
        return card;
    }

    /** A full-width tappable row: title, subtitle and an external-link mark, opened in a browser. */
    private View linkRow(AboutLink link) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12), UiKit.dp(this, 12));
        row.setMinimumHeight(UiKit.dp(this, 56));
        row.setBackground(UiKit.ripple(UiKit.SURFACE, UiKit.accent(this), 16, this));
        row.setClickable(true);
        row.setFocusable(true);
        row.setContentDescription(link.title + ", " + link.subtitle + ". Opens in your browser.");
        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.addView(UiKit.text(this, link.title, 16, UiKit.TEXT, true));
        TextView subtitle = UiKit.text(this, link.subtitle, 12, UiKit.MUTED, false);
        subtitle.setPadding(0, UiKit.dp(this, 3), 0, 0);
        copy.addView(subtitle);
        row.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView mark = UiKit.text(this, "↗", 18, UiKit.accent(this), true);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(mark);
        row.setTag(link.uri);
        row.setOnClickListener(v -> openExternal(this, link.uri));
        UiKit.pressScale(row);
        return row;
    }

    /**
     * Hands a web address to the user's browser. Never a WebView. A phone with nothing that can
     * open it gets a short message instead of a crash.
     */
    static boolean openExternal(android.content.Context c, String uri) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(uri))
                    .addCategory(Intent.CATEGORY_BROWSABLE));
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException e) {
            Toast.makeText(c, "No app on this phone can open that link", Toast.LENGTH_SHORT).show();
            return false;
        }
    }





    /**
     * What Orbit says when there is nothing newer to install.
     *
     * <p>Three genuinely different situations. On Beta, Orbit has looked at prereleases too. On
     * Stable, it is simply current. And a Stable-channel user who is still running a Beta build is
     * neither of those: there is a newer build installed than the newest Stable release, and Orbit
     * will not downgrade it, so it says exactly that instead of pretending to be up to date.
     */
    private String upToDateMessage() {
        if (Prefs.betaChannel(this)) return "You're on the newest available GrokLink build.";
        if (OrbitVersion.installedIsBeta()) {
            return "You're running a newer Beta build. GrokLink will return to Stable updates when a "
                    + "newer Stable release becomes available.";
        }
        return "GrokLink is up to date.";
    }

    // ---- update channel -------------------------------------------------------------------------


    private String channelLabel() {
        return Prefs.betaChannel(this) ? "Beta" : "Stable";
    }






    private TextView betaPill(String label) {
        TextView pill = UiKit.text(this, label, 10, UiKit.onAccent(this), true);
        pill.setLetterSpacing(0.09f);
        pill.setPadding(UiKit.dp(this, 8), UiKit.dp(this, 3), UiKit.dp(this, 8), UiKit.dp(this, 4));
        pill.setBackground(UiKit.rounded(UiKit.accent(this), 9, this));
        return pill;
    }







    private Button primaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.onAccent(this));
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.ripple(
                UiKit.accent(this), UiKit.onAccent(this), 15, this));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }

    private Button secondaryButton(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(UiKit.TEXT);
        button.setTextSize(14);
        button.setAllCaps(false);
        button.setBackground(UiKit.rippleOutlined(UiKit.SURFACE_2,
                Color.rgb(53, 58, 72), UiKit.accent(this), 15, this));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setStateListAnimator(null);
        UiKit.pressScale(button);
        return button;
    }


    private void styleOrbitDialog(AlertDialog dialog) {
        UiKit.styleOrbitDialog(dialog, this, false);
    }



    private boolean alive() {
        return !isFinishing() && !isDestroyed();
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0L) return "Unknown";
        return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
    }
}
