/*
 * Copyright 2026 mdudel
 * Licensed under the Apache License, Version 2.0.
 */
package com.mdudel.sapient.net;

import uk.gov.dstl.sapientmsg.bsiflex335v2.SapientMessage;

import java.net.SocketAddress;

/**
 * Callback interface for events on a Sapient TCP endpoint (server or client).
 * All callbacks may be invoked from Netty I/O threads — implementations must be
 * thread-safe and non-blocking (dispatch to a UI thread or executor for heavy work).
 */
public interface SapientMessageListener {

    /** A new peer has connected (server) or the connection to a peer has been established (client). */
    default void onConnected(SocketAddress peer) {
    }

    /** A peer connection has been closed. */
    default void onDisconnected(SocketAddress peer) {
    }

    /** A well-formed {@link SapientMessage} has been received. */
    void onMessage(SocketAddress peer, SapientMessage message);

    /** An exception occurred on the channel (parse failure, IO error, etc.). */
    default void onError(SocketAddress peer, Throwable cause) {
    }
}
