/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * Entry point for headless / CI usage. Delegates to sub-commands:
 *
 * <pre>{@code
 * sapient-cli receive --port 12000
 * sapient-cli send    --host 10.0.0.20 --port 14000 --template registration.json
 * sapient-cli echo    --port 12000
 * }</pre>
 */
@Command(
        name = "sapient-cli",
        mixinStandardHelpOptions = true,
        version = "sapient-harness-java 0.1.0-SNAPSHOT",
        description = "SAPIENT test harness — headless CLI.",
        subcommands = {
                ReceiveCommand.class,
                SendCommand.class,
                EchoCommand.class
        })
public final class SapientCli implements Runnable {

    @Override
    public void run() {
        // No args → print usage.
        CommandLine.usage(this, System.out);
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new SapientCli()).execute(args);
        System.exit(exitCode);
    }
}
