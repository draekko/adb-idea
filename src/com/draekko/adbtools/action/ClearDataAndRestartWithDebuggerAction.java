package com.draekko.adbtools.action;

import com.draekko.adbtools.adb.AdbFacade;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;

public class ClearDataAndRestartWithDebuggerAction extends AdbAction {

    public void actionPerformed(AnActionEvent e, Project project) {
        AdbFacade.clearDataAndRestartWithDebugger(project);
    }

}
