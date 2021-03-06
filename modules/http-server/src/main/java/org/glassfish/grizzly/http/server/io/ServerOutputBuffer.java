/*
 * Copyright (c) 2012, 2017 Oracle and/or its affiliates. All rights reserved.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0, which is available at
 * http://www.eclipse.org/legal/epl-2.0.
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the
 * Eclipse Public License v. 2.0 are satisfied: GNU General Public License,
 * version 2 with the GNU Classpath Exception, which is available at
 * https://www.gnu.org/software/classpath/license.html.
 *
 * SPDX-License-Identifier: EPL-2.0 OR GPL-2.0 WITH Classpath-exception-2.0
 */

package org.glassfish.grizzly.http.server.io;

import org.glassfish.grizzly.CompletionHandler;
import org.glassfish.grizzly.WriteResult;
import org.glassfish.grizzly.filterchain.FilterChainContext;
import org.glassfish.grizzly.http.io.OutputBuffer;
import org.glassfish.grizzly.http.server.Response;

import java.io.File;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import org.glassfish.grizzly.localization.LogMessages;

public class ServerOutputBuffer extends OutputBuffer {

    private Response serverResponse;

    public void initialize(final Response response,
            final FilterChainContext ctx) {
        super.initialize(response.getResponse(), response.isSendFileEnabled(), ctx);
        this.serverResponse = response;
    }

    @Override
    public void sendfile(final File file, final long offset, final long length,
            final CompletionHandler<WriteResult> handler) {

        if (!sendfileEnabled) {
                   throw new IllegalStateException("sendfile support isn't available.");
        }

        // check the suspend status at the time this method was invoked
        // and take action based on this value
        final boolean suspendedAtStart = serverResponse.isSuspended();
        final CompletionHandler<WriteResult> ch;
        if (suspendedAtStart && handler != null) {
            // provided CompletionHandler assumed to manage suspend/resume
            ch = handler;
        } else if (!suspendedAtStart && handler != null) {
            // provided CompletionHandler assumed to not managed suspend/resume
            ch = suspendAndCreateHandler(handler);
        } else {
            // create internal CompletionHandler that will take the
            // appropriate action depending on the current suspend status
            ch = createInternalCompletionHandler(file, suspendedAtStart);
        }
        super.sendfile(file, offset, length, ch);
    }

    @Override
    public void recycle() {
        serverResponse = null;
        super.recycle();
    }

    @Override
    protected Executor getThreadPool() {
        return serverResponse.getRequest().getRequestExecutor();
    }
    
    private CompletionHandler<WriteResult> createInternalCompletionHandler(
                final File file, final boolean suspendedAtStart) {

            CompletionHandler<WriteResult> ch;
            if (!suspendedAtStart) {
                serverResponse.suspend();
            }
            ch = new CompletionHandler<WriteResult>() {
                @Override
                public void cancelled() {
                    if (LOGGER.isLoggable(Level.WARNING)) {
                        LOGGER.log(Level.WARNING,
                                LogMessages.WARNING_GRIZZLY_HTTP_SERVER_SERVEROUTPUTBUFFER_FILE_TRANSFER_CANCELLED(file.getAbsolutePath()));
                    }
                    serverResponse.resume();
                }

                @Override
                public void failed(Throwable throwable) {
                    if (LOGGER.isLoggable(Level.SEVERE)) {
                        LOGGER.log(Level.SEVERE,
                                LogMessages.WARNING_GRIZZLY_HTTP_SERVER_SERVEROUTPUTBUFFER_FILE_TRANSFER_FAILED(file.getAbsolutePath(), throwable.getMessage()),
                                throwable);
                    }
                    serverResponse.resume();
                }

                @Override
                public void completed(WriteResult result) {
                    serverResponse.resume();
                }

                @Override
                public void updated(WriteResult result) {
                    // no-op
                }
            };
            return ch;

        }

        private CompletionHandler<WriteResult> suspendAndCreateHandler(
                final CompletionHandler<WriteResult> handler) {
            serverResponse.suspend();
            return new CompletionHandler<WriteResult>() {

                @Override
                public void cancelled() {
                    handler.cancelled();
                    serverResponse.resume();
                }

                @Override
                public void failed(Throwable throwable) {
                    handler.failed(throwable);
                    serverResponse.resume();
                }

                @Override
                public void completed(WriteResult result) {
                    handler.completed(result);
                    serverResponse.resume();
                }

                @Override
                public void updated(WriteResult result) {
                    handler.updated(result);
                }
            };
        }
}
