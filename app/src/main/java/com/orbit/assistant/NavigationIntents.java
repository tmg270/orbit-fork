package com.orbit.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Turn-by-turn and ETA handoff to Google Maps or Waze.
 *
 * <p>GrokLink does not call a routing API and does not invent a travel time. "Navigate to X"
 * starts guidance. "How long to X" opens the directions screen, which is where the ETA is shown.
 * On Android Auto the same destination is also sent as {@code androidx.car.app.action.NAVIGATE}
 * with a geo URI, which is the handoff Auto documents for a non-navigation app. A start that
 * Android blocks from the background is reported as a failure rather than described as success.
 */
final class NavigationIntents {
    static final String MAPS = "com.google.android.apps.maps";
    static final String WAZE = "com.waze";
    static final String CAR_NAVIGATE = "androidx.car.app.action.NAVIGATE";

    enum Mode { NAVIGATE, ETA }
    enum App { MAPS, WAZE }

    static final class Request {
        final String destination;
        final Mode mode;
        final App app;

        Request(String destination, Mode mode, App app) {
            this.destination = destination == null ? "" : destination.trim();
            this.mode = mode == null ? Mode.NAVIGATE : mode;
            this.app = app;
        }
    }

    private NavigationIntents() {}

    /** A spoken navigation request, or null when this sentence is not one. */
    static Request parse(String phrase) {
        if (phrase == null) return null;
        String q = phrase.toLowerCase(Locale.US).replaceAll("\\s+", " ").trim();
        if (q.isEmpty() || q.length() > 120) return null;
        App app = null;
        if (q.contains("waze")) app = App.WAZE;
        else if (q.contains("google maps") || q.matches(".*\\bmaps\\b.*")) app = App.MAPS;
        q = q.replaceAll("\\s+(?:with|on|in|via|using)\\s+(?:waze|google maps|maps)\\s*$", "").trim();
        boolean eta = q.startsWith("eta") || q.startsWith("e t a") || q.contains("how long");
        String dest = null;
        String[] patterns = eta
                ? new String[] {
                    "^(?:what(?:'s| is) the )?(?:eta|e t a)(?:\\s+to)?\\s+(.+)$",
                    "^how long(?:\\s+(?:is it|until|to get|to))?\\s+to\\s+(.+)$",
                    "^how long to\\s+(.+)$" }
                : new String[] {
                    "^(?:navigate|navigation)\\s+(?:me\\s+)?(?:to\\s+)?(.+)$",
                    "^(?:get\\s+)?directions\\s+to\\s+(.+)$",
                    "^drive(?:\\s+me)?\\s+to\\s+(.+)$",
                    "^route\\s+(?:me\\s+)?to\\s+(.+)$",
                    "^(?:waze|maps|google maps)\\s+to\\s+(.+)$" };
        for (String pattern : patterns) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(pattern).matcher(q);
            if (m.matches()) { dest = m.group(1).trim(); break; }
        }
        if (dest == null || dest.isEmpty() || dest.length() > 80) return null;
        if (dest.matches("^(?:it|there|home screen|settings)$")) return null;
        return new Request(dest, eta ? Mode.ETA : Mode.NAVIGATE, app);
    }

    static Request fromParams(JSONObject params) {
        JSONObject p = params == null ? new JSONObject() : params;
        String dest = p.optString("query", p.optString("destination", "")).trim();
        String mode = p.optString("mode", "navigate").toLowerCase(Locale.US);
        String app = p.optString("app", "").toLowerCase(Locale.US);
        App chosen = app.contains("waze") ? App.WAZE : app.contains("map") ? App.MAPS : null;
        Mode kind = mode.contains("eta") || mode.contains("how long") ? Mode.ETA : Mode.NAVIGATE;
        return new Request(dest, kind, chosen);
    }

    static Intent mapsNavigation(String destination) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("google.navigation:q=" + Uri.encode(destination == null ? "" : destination)));
        intent.setPackage(MAPS);
        return intent;
    }

    /** Directions, not guidance. Maps draws the ETA. GrokLink does not. */
    static Intent mapsDirections(String destination) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.google.com/maps/dir/?api=1&travelmode=driving&destination="
                        + Uri.encode(destination == null ? "" : destination)));
        intent.setPackage(MAPS);
        return intent;
    }

    static Intent waze(String destination) {
        Intent intent = new Intent(Intent.ACTION_VIEW,
                Uri.parse("waze://?q=" + Uri.encode(destination == null ? "" : destination) + "&navigate=yes"));
        intent.setPackage(WAZE);
        return intent;
    }

    /** Android Auto's documented navigation handoff. */
    static Intent car(String destination) {
        Intent intent = new Intent(CAR_NAVIGATE);
        intent.setData(Uri.parse("geo:0,0?q=" + Uri.encode(destination == null ? "" : destination)));
        return intent;
    }

    static DeviceActionExecutor.Result launch(Context context, JSONObject params) {
        Request request = fromParams(params);
        if (request.destination.isEmpty()) return DeviceActionExecutor.Result.failed("Tell me where to go");
        if (context == null) return DeviceActionExecutor.Result.failed("Navigation isn't available");
        App app = request.app;
        if (app == App.WAZE && !installed(context, WAZE)) app = App.MAPS;
        if (app == null) app = installed(context, MAPS) || !installed(context, WAZE) ? App.MAPS : App.WAZE;
        Intent primary = app == App.WAZE
                ? waze(request.destination)
                : request.mode == Mode.ETA
                ? mapsDirections(request.destination)
                : mapsNavigation(request.destination);
        Intent auto = car(request.destination);
        boolean started = false;
        String problem = "Android blocked opening it from the background";
        try {
            start(context, auto);
            started = true;
        } catch (Exception ignored) {
            // The phone intent below is the one Maps and Waze actually register.
        }
        try {
            start(context, primary);
            started = true;
        } catch (Exception e) {
            if (!installed(context, app == App.WAZE ? WAZE : MAPS)) {
                problem = (app == App.WAZE ? "Waze" : "Google Maps") + " is not installed";
            } else if (e.getMessage() != null && !e.getMessage().isEmpty()) {
                problem = e.getMessage();
            }
        }
        if (!started) return DeviceActionExecutor.Result.failed("Couldn't navigate: " + problem);
        String where = app == App.WAZE ? "Waze" : "Google Maps";
        if (request.mode == Mode.ETA) {
            return DeviceActionExecutor.Result.success(
                    "Opening directions to " + request.destination + " in " + where
                            + ". The ETA is on the map");
        }
        return DeviceActionExecutor.Result.success(
                "Starting navigation to " + request.destination + " in " + where);
    }

    private static boolean installed(Context context, String pkg) {
        try {
            context.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void start(Context context, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(context.getPackageManager()) == null) {
            throw new IllegalStateException("No compatible app installed");
        }
        context.startActivity(intent);
    }
}
