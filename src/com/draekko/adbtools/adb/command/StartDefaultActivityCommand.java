package com.draekko.adbtools.adb.command;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.MultiLineReceiver;
import com.draekko.adbtools.adb.AdbUtil;
import com.google.common.base.Joiner;
import com.google.common.base.Strings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Computable;
import com.intellij.psi.PsiClass;
import org.jetbrains.android.dom.AndroidDomUtil;
import org.jetbrains.android.dom.manifest.*;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.draekko.adbtools.ui.NotificationHelper.error;
import static com.draekko.adbtools.ui.NotificationHelper.info;

public class StartDefaultActivityCommand implements Command {
    public static final String LAUNCH_ACTION_NAME = "android.intent.action.MAIN";
    public static final String LAUNCH_CATEGORY_NAME = "android.intent.category.LAUNCHER";

    @Override
    public boolean run(Project project, IDevice device, AndroidFacet facet, String packageName) {
        String defaultActivityName = getDefaultActivityName(facet);
        String component = packageName + "/" + defaultActivityName;

        try {
            StartActivityReceiver receiver = new StartActivityReceiver();
            device.executeShellCommand("am start " + component, receiver, 15L, TimeUnit.SECONDS);
            if (receiver.isSuccess()) {
                info(String.format("<b>%s</b> started on %s", packageName, device.getName()));
                return true;
            } else {
                error(String.format("<b>%s</b> could not bet started on %s. \n\n<b>ADB Output:</b> \n%s", packageName, device.getName(), receiver.getMessage()));
            }
        } catch (Exception e) {
            error("Start fail... " + e.getMessage());
        }

        return false;
    }

    private String getDefaultActivityName(final AndroidFacet facet) {
        return ApplicationManager.getApplication().runReadAction(new Computable<String>() {
            @Override
            public String compute() {
                return ApplicationManager.getApplication().runReadAction(new Computable<String>() {
                    @Override
                    public String compute() {
                        return AdbUtil.getDefaultLauncherActivityName(facet);
                    }
                });

            }
        });
    }

    public static class StartActivityReceiver extends MultiLineReceiver {

        public String message = "Nothing Received";

        public List<String> currentLines = new ArrayList<String>();

        @Override
        public void processNewLines(String[] strings) {
            for (String s : strings) {
                if (!Strings.isNullOrEmpty(s)) {
                    currentLines.add(s);
                }
            }
            computeMessage();
        }

        private void computeMessage() {
            message = Joiner.on("\n").join(currentLines);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        public String getMessage() {
            return message;
        }

        public boolean isSuccess() {
            return currentLines.size() > 0 && currentLines.size() < 3;
        }
    }

    // copied from AOSP since it changed between 0.4.3 and 0.4.4
    @Nullable
    public static String getDefaultLauncherActivityName(@NotNull Manifest manifest) {
        Application application = manifest.getApplication();
        if (application == null) {
            return null;
        }

        for (Activity activity : application.getActivities()) {
            for (IntentFilter filter : activity.getIntentFilters()) {
                if (AndroidDomUtil.containsAction(filter, LAUNCH_ACTION_NAME) && AndroidDomUtil.containsCategory(filter, LAUNCH_CATEGORY_NAME)) {
                    PsiClass c = activity.getActivityClass().getValue();
                    return c != null ? c.getQualifiedName() : null;
                }
            }
        }

        for (ActivityAlias alias : application.getActivityAliass()) {
            for (IntentFilter filter : alias.getIntentFilters()) {
                if (AndroidDomUtil.containsAction(filter, LAUNCH_ACTION_NAME) && AndroidDomUtil.containsCategory(filter, LAUNCH_CATEGORY_NAME)) {
                    return alias.getName().getStringValue();
                }
            }
        }

        return null;
    }

}
