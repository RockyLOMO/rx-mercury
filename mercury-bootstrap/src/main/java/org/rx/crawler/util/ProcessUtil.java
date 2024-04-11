package org.rx.crawler.util;

import org.apache.commons.io.FilenameUtils;
import org.rx.core.Arrays;
import org.rx.core.Linq;
import org.rx.core.StringBuilder;

import java.util.Optional;

import static org.rx.core.Sys.toJsonString;

public class ProcessUtil {
    public static boolean killProcesses(String... processNames) {
        return getProcesses(processNames).all(p -> killProcess(p.pid()));
    }

    public static boolean killProcessTrees(String... processNames) {
        return getProcesses(processNames).all(p -> killProcess(p.pid(), true));
    }

    public static Linq<ProcessHandle> getProcesses(String... processNames) {
        Linq<String> n = Linq.from(processNames);
        return Linq.from(ProcessHandle.allProcesses()).where(p -> p.isAlive() && n.any(x -> FilenameUtils.getName(p.info().command().orElse("")).equalsIgnoreCase(FilenameUtils.getName(x))));
    }

    public static boolean killProcess(long pid) {
        return killProcess(pid, false);
    }

    public static boolean killProcess(long pid, boolean killTree) {
        ProcessHandle p = ProcessHandle.of(pid).orElse(null);
        if (p == null) {
            return false;
        }
        boolean r = true;
        if (killTree) {
            r = p.descendants().allMatch(ProcessHandle::destroyForcibly);
        }
        return r && p.destroyForcibly();
    }

    public static String dump(ProcessHandle processHandle) {
        StringBuilder sb = new StringBuilder();
        ProcessHandle.Info pInfo = processHandle.info();
        Optional<ProcessHandle> parent = processHandle.parent();
        parent.ifPresent(handle -> sb.appendFormat("parentPid: %s\t", handle.pid()));
        sb.appendFormat("descendantsPids: %s\t", Linq.from(processHandle.descendants()).toJoinString(",", p -> String.valueOf(p.pid())));
        sb.appendFormat("pid: %s\t", processHandle.pid());
        sb.appendFormat("startTime: %s\t", processHandle.info().startInstant().orElse(null));
        sb.appendFormat("command: %s\t", pInfo.command().orElse(null));
        sb.appendFormat("commandLine: %s\t", pInfo.commandLine().orElse(null));
        sb.appendFormat("arguments: %s", toJsonString(pInfo.arguments().orElse(Arrays.EMPTY_STRING_ARRAY)));
        return sb.toString();
    }
}
