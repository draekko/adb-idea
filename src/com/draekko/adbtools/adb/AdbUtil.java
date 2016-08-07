package com.draekko.adbtools.adb;

import com.android.builder.model.SourceProvider;
import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.ShellCommandUnresponsiveException;
import com.android.ddmlib.TimeoutException;
import com.draekko.adbtools.adb.command.receiver.GenericReceiver;
import com.draekko.adbtools.compatibility.ActivityLocatorUtils;
import com.draekko.adbtools.compatibility.DefaultActivityLocator;
import com.google.common.collect.Lists;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.android.dom.AndroidDomUtil;
import org.jetbrains.android.dom.manifest.*;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joor.Reflect;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

//import com.android.tools.idea.run.activity.ActivityLocatorUtils;
//import com.android.tools.idea.run.activity.DefaultActivityLocator;

//import org.jetbrains.android.facet.AndroidFacet;

//import com.android.tools.idea.run.activity.ActivityLocatorUtils;
//import com.android.tools.idea.run.activity.DefaultActivityLocator;
//import org.jetbrains.android.dom.AndroidDomUtil;
//import org.jetbrains.android.dom.manifest.*;
//import org.jetbrains.android.facet.AndroidFacet;
//import org.jetbrains.android.util.AndroidUtils;

//import org.jetbrains.android.facet.AndroidFacet;

public class AdbUtil {

    public static boolean isAppInstalled(IDevice device, String packageName) throws TimeoutException, AdbCommandRejectedException, ShellCommandUnresponsiveException, IOException {
        GenericReceiver receiver = new GenericReceiver();
        // "pm list packages com.my.package" will return one line per package installed that corresponds to this package.
        // if this list is empty, we know for sure that the app is not installed
        device.executeShellCommand("pm list packages " + packageName, receiver, 15L, TimeUnit.SECONDS);

        //TODO make sure that it is the exact package name and not a subset.
        // e.g. if our app is called com.example but there is another app called com.example.another.app, it will match and return a false positive
        return !receiver.getAdbOutputLines().isEmpty();
    }


    /**
     * Computes the project's package while preserving backward compatibility between android studio 0.4.3 and 0.4.4
     */
    public static String computePackageName(AndroidFacet facet) {
        try {
            Object androidModuleInfo = facet.getClass().getMethod("getAndroidModuleInfo").invoke(facet);
            return (String) androidModuleInfo.getClass().getMethod("getPackage").invoke(androidModuleInfo);
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static String getDefaultLauncherActivityName(AndroidFacet facet) {
        try {
            SourceProvider sourceProvider = facet.getMainSourceProvider();
            File manifestIoFile = sourceProvider.getManifestFile();
            final VirtualFile manifestFile =
                    LocalFileSystem.getInstance().findFileByIoFile(manifestIoFile);

            return DefaultActivityLocator.getDefaultLauncherActivityName(facet.getManifest());
            //return DefaultActivityLocator.getDefaultLauncherActivityName(facet.getManifest());
        } catch (Exception e) {
            return Reflect.on(AndroidUtils.class).call("getDefaultLauncherActivityName", facet.getManifest()).get();
        }
    }

    @Nullable
    public static String getDefaultLauncherActivityName(@NotNull final Manifest manifest) {
        return ApplicationManager.getApplication().runReadAction(new Computable<String>() {
            @Override
            public String compute() {
                Application application = manifest.getApplication();
                if (application == null) {
                    return null;
                }
                return computeDefaultActivity(application.getActivities(), application.getActivityAliass(), null);
            }
        });
    }

    @Nullable
    private static String computeDefaultActivity(@NotNull List<Activity> activities,
                                                 @NotNull List<ActivityAlias> activityAliases,
                                                 @Nullable IDevice device) {
        List<DefaultActivityLocator.ActivityWrapper> launchableActivities = getLaunchableActivities(merge(activities, activityAliases));
        if (launchableActivities.isEmpty()) {
            return null;
        }
        else if (launchableActivities.size() == 1) {
            return launchableActivities.get(0).getQualifiedName();
        }
        // First check if we have an activity specific to the device
        if (device != null) {
            DefaultActivityLocator.ActivityWrapper activity = findLauncherActivityForDevice(launchableActivities, device);
            if (activity != null) {
                return activity.getQualifiedName();
            }
        }
        // Prefer the launcher which has the CATEGORY_DEFAULT intent filter.
        // There is no such rule, but since Context.startActivity() prefers such activities, we do the same.
        // https://code.google.com/p/android/issues/detail?id=67068
        DefaultActivityLocator.ActivityWrapper defaultLauncher = findDefaultLauncher(launchableActivities);
        if (defaultLauncher != null) {
            return defaultLauncher.getQualifiedName();
        }
        // Just return the first one we find
        return launchableActivities.get(0).getQualifiedName();
    }

    @NotNull
    private static List<DefaultActivityLocator.ActivityWrapper> getLaunchableActivities(@NotNull List<DefaultActivityLocator.ActivityWrapper> allActivities) {
        return ContainerUtil.filter(allActivities, new Condition<DefaultActivityLocator.ActivityWrapper>() {
            @Override
            public boolean value(DefaultActivityLocator.ActivityWrapper activity) {
                return ActivityLocatorUtils.containsLauncherIntent(activity.getIntentFilters());
            }
        });
    }

    private static List<DefaultActivityLocator.ActivityWrapper> merge(List<Activity> activities, List<ActivityAlias> activityAliases) {
        final List<DefaultActivityLocator.ActivityWrapper> activityWrappers = Lists.newArrayListWithExpectedSize(activities.size() + activityAliases.size());
        for (Activity a : activities) {
            activityWrappers.add(DefaultActivityLocator.ActivityWrapper.get(a));
        }
        for (ActivityAlias a : activityAliases) {
            activityWrappers.add(DefaultActivityLocator.ActivityWrapper.get(a));
        }
        return activityWrappers;
    }

    @Nullable
    private static DefaultActivityLocator.ActivityWrapper findLauncherActivityForDevice(@NotNull List<DefaultActivityLocator.ActivityWrapper> launchableActivities,
                                                                 @NotNull IDevice device) {
        // Currently, this just checks if the device is a TV, and if so, looks for the leanback launcher
        // https://code.google.com/p/android/issues/detail?id=176033
        //if (device.supportsFeature(IDevice.HardwareFeature.TV)) {
        //    return findLeanbackLauncher(launchableActivities);
        //}
        return null;
    }

    @Nullable
    private static DefaultActivityLocator.ActivityWrapper findLeanbackLauncher(@NotNull List<DefaultActivityLocator.ActivityWrapper> launcherActivities) {
        for (DefaultActivityLocator.ActivityWrapper activity : launcherActivities) {
            for (IntentFilter filter : activity.getIntentFilters()) {
                if (AndroidDomUtil.containsCategory(filter, AndroidUtils.LEANBACK_LAUNCH_CATEGORY_NAME)) {
                    return activity;
                }
            }
        }
        return null;
    }

    @Nullable
    private static DefaultActivityLocator.ActivityWrapper findDefaultLauncher(@NotNull List<DefaultActivityLocator.ActivityWrapper> launcherActivities) {
        for (DefaultActivityLocator.ActivityWrapper activity : launcherActivities) {
            for (IntentFilter filter : activity.getIntentFilters()) {
                if (AndroidDomUtil.containsCategory(filter, AndroidUtils.DEFAULT_CATEGORY_NAME)) {
                    return activity;
                }
            }
        }
        return null;
    }
}
