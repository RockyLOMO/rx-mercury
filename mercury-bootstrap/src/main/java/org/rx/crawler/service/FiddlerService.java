package org.rx.crawler.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.rx.crawler.FiddlerWatcher;
import org.rx.crawler.config.AppConfig;
import org.rx.core.Strings;
import org.rx.io.FileWatcher;
import org.rx.io.Files;
import org.rx.net.rpc.Remoting;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

@Slf4j
@Service
public class FiddlerService implements FiddlerWatcher {
    final FileWatcher watcher;

    public FiddlerService(AppConfig config) {
        String dir = Files.concatPath(config.getBaseDir(), "fiddler/");
        Files.createDirectory(dir);
        watcher = new FileWatcher(dir);
        watcher.onChanged.combine((sender, e) -> {
            log.info("File[{}] has {} change", e.getPath(), e.isModify());
            if (!e.isModify()) {
                return;
            }

            String filePath = e.getPath().toString();
            String filename = FilenameUtils.getName(filePath);
            String[] args = Strings.split(filename, "_", 3);
            raiseEvent(EVENT_CALLBACK, new CallbackEventArgs(args[0] + "_" + args[1], Files.readLines(filePath).collect(Collectors.toList())));
        });
        Remoting.register(this, config.getFiddlerListenPort(), false);
    }
}
