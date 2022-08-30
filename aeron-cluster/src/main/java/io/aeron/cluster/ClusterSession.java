/*
 * Copyright 2014-2022 Real Logic Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.aeron.cluster;

import io.aeron.*;
import io.aeron.archive.client.AeronArchive;
import io.aeron.cluster.client.ClusterException;
import io.aeron.cluster.codecs.CloseReason;
import io.aeron.cluster.codecs.EventCode;
import io.aeron.exceptions.AeronException;
import io.aeron.exceptions.RegistrationException;
import io.aeron.logbuffer.BufferClaim;
import org.agrona.*;
import org.agrona.collections.ArrayUtil;

import java.util.Arrays;

final class ClusterSession
{
    static final byte[] NULL_PRINCIPAL = ArrayUtil.EMPTY_BYTE_ARRAY;
    static final int MAX_ENCODED_PRINCIPAL_LENGTH = 4 * 1024;
    static final int MAX_ENCODED_MEMBERSHIP_QUERY_LENGTH = 4 * 1024;

    enum State
    {
        INIT, CONNECTING, CONNECTED, CHALLENGED, AUTHENTICATED, REJECTED, OPEN, CLOSING, INVALID, CLOSED
    }

    enum Action
    {
        CLIENT, BACKUP, HEARTBEAT
    }

    private boolean hasNewLeaderEventPending = false;
    private boolean hasOpenEventPending = true;
    private boolean isBackupSession = false;
    private final long id;
    private long correlationId;
    private long openedLogPosition = AeronArchive.NULL_POSITION;
    private long closedLogPosition = AeronArchive.NULL_POSITION;
    private long timeOfLastActivityNs;
    private long responsePublicationId = Aeron.NULL_VALUE;
    private final int responseStreamId;
    private final String responseChannel;
    private Publication responsePublication;
    private State state;
    private String responseDetail = null;
    private EventCode eventCode = null;
    private CloseReason closeReason = CloseReason.NULL_VAL;
    private byte[] encodedPrincipal = NULL_PRINCIPAL;
    private Action action = Action.CLIENT;

    ClusterSession(final long sessionId, final int responseStreamId, final String responseChannel)
    {
        this.id = sessionId;
        this.responseStreamId = responseStreamId;
        this.responseChannel = responseChannel;
        state(State.INIT);
    }

    ClusterSession(
        final long id,
        final long correlationId,
        final long openedLogPosition,
        final long timeOfLastActivityNs,
        final int responseStreamId,
        final String responseChannel,
        final CloseReason closeReason)
    {
        this.id = id;
        this.responseStreamId = responseStreamId;
        this.responseChannel = responseChannel;
        this.openedLogPosition = openedLogPosition;
        this.timeOfLastActivityNs = timeOfLastActivityNs;
        this.correlationId = correlationId;
        this.closeReason = closeReason;

        if (CloseReason.NULL_VAL != closeReason)
        {
            state(State.CLOSED);
        }
        else
        {
            state(State.OPEN);
        }
    }

    public void close(final Aeron aeron, final ErrorHandler errorHandler)
    {
        if (null == responsePublication)
        {
            aeron.asyncRemovePublication(responsePublicationId);
        }
        else
        {
            CloseHelper.close(errorHandler, responsePublication);
            responsePublication = null;
        }

        state(State.CLOSED);
    }

    long id()
    {
        return id;
    }

    int responseStreamId()
    {
        return responseStreamId;
    }

    String responseChannel()
    {
        return responseChannel;
    }

    void closing(final CloseReason closeReason)
    {
        this.closeReason = closeReason;
        this.hasOpenEventPending = false;
        this.hasNewLeaderEventPending = false;
        this.timeOfLastActivityNs = 0;
        state(State.CLOSING);
    }

    CloseReason closeReason()
    {
        return closeReason;
    }

    void resetCloseReason()
    {
        closedLogPosition = AeronArchive.NULL_POSITION;
        closeReason = CloseReason.NULL_VAL;
    }

    void asyncConnect(final Aeron aeron)
    {
        responsePublicationId = aeron.asyncAddPublication(responseChannel, responseStreamId);
    }

    void connect(final ErrorHandler errorHandler, final Aeron aeron)
    {
        if (null != responsePublication)
        {
            throw new ClusterException("response publication already added");
        }

        try
        {
            responsePublication = aeron.addPublication(responseChannel, responseStreamId);
        }
        catch (final RegistrationException ex)
        {
            errorHandler.onError(new ClusterException(
                "failed to connect session response publication: " + ex.getMessage(), AeronException.Category.WARN));
        }
    }

    void disconnect(final Aeron aeron, final ErrorHandler errorHandler)
    {
        if (null == responsePublication)
        {
            aeron.asyncRemovePublication(responsePublicationId);
        }
        else
        {
            CloseHelper.close(errorHandler, responsePublication);
            responsePublication = null;
        }
    }

    boolean isResponsePublicationConnected(final Aeron aeron, final long nowNs)
    {
        if (null == responsePublication)
        {
            if (!aeron.isCommandActive(responsePublicationId))
            {
                responsePublication = aeron.getPublication(responsePublicationId);
                if (null != responsePublication)
                {
                    responsePublicationId = Aeron.NULL_VALUE;
                    timeOfLastActivityNs = nowNs;
                    state(State.CONNECTING);
                }
                else
                {
                    responsePublicationId = Aeron.NULL_VALUE;
                    state(State.INVALID);
                }
            }
        }

        return null != responsePublication && responsePublication.isConnected();
    }

    public long tryClaim(final int length, final BufferClaim bufferClaim)
    {
        if (null == responsePublication)
        {
            return Publication.NOT_CONNECTED;
        }
        else
        {
            return responsePublication.tryClaim(length, bufferClaim);
        }
    }

    public long offer(final DirectBuffer buffer, final int offset, final int length)
    {
        if (null == responsePublication)
        {
            return Publication.NOT_CONNECTED;
        }
        else
        {
            return responsePublication.offer(buffer, offset, length);
        }
    }

    State state()
    {
        return state;
    }

    void state(final State newState)
    {
        //System.out.println("ClusterSession " + id + " " + state + " -> " + newState);
        this.state = newState;
    }

    void authenticate(final byte[] encodedPrincipal)
    {
        if (encodedPrincipal != null)
        {
            this.encodedPrincipal = encodedPrincipal;
        }

        state(State.AUTHENTICATED);
    }

    void open(final long openedLogPosition)
    {
        this.openedLogPosition = openedLogPosition;
        encodedPrincipal = null;
        state(State.OPEN);
    }

    byte[] encodedPrincipal()
    {
        return encodedPrincipal;
    }

    void lastActivityNs(final long timeNs, final long correlationId)
    {
        timeOfLastActivityNs = timeNs;
        this.correlationId = correlationId;
    }

    void reject(final EventCode code, final String responseDetail)
    {
        this.eventCode = code;
        this.responseDetail = responseDetail;
        state(State.REJECTED);
    }

    EventCode eventCode()
    {
        return eventCode;
    }

    String responseDetail()
    {
        return responseDetail;
    }

    long timeOfLastActivityNs()
    {
        return timeOfLastActivityNs;
    }

    void timeOfLastActivityNs(final long timeNs)
    {
        timeOfLastActivityNs = timeNs;
    }

    long correlationId()
    {
        return correlationId;
    }

    long openedLogPosition()
    {
        return openedLogPosition;
    }

    void closedLogPosition(final long closedLogPosition)
    {
        this.closedLogPosition = closedLogPosition;
    }

    long closedLogPosition()
    {
        return closedLogPosition;
    }

    void hasNewLeaderEventPending(final boolean flag)
    {
        hasNewLeaderEventPending = flag;
    }

    boolean hasNewLeaderEventPending()
    {
        return hasNewLeaderEventPending;
    }

    boolean hasOpenEventPending()
    {
        return hasOpenEventPending;
    }

    void clearOpenEventPending()
    {
        hasOpenEventPending = false;
    }

    Action action()
    {
        return action;
    }

    void action(final Action action)
    {
        this.action = action;
    }

    Publication responsePublication()
    {
        return responsePublication;
    }

    static void checkEncodedPrincipalLength(final byte[] encodedPrincipal)
    {
        if (null != encodedPrincipal && encodedPrincipal.length > MAX_ENCODED_PRINCIPAL_LENGTH)
        {
            throw new ClusterException(
                "encoded principal max length " + MAX_ENCODED_PRINCIPAL_LENGTH +
                " exceeded: length=" + encodedPrincipal.length);
        }
    }

    public String toString()
    {
        return "ClusterSession{" +
            "id=" + id +
            ", correlationId=" + correlationId +
            ", openedLogPosition=" + openedLogPosition +
            ", closedLogPosition=" + closedLogPosition +
            ", timeOfLastActivityNs=" + timeOfLastActivityNs +
            ", responseStreamId=" + responseStreamId +
            ", responseChannel='" + responseChannel + '\'' +
            ", responsePublicationId=" + responsePublicationId +
            ", closeReason=" + closeReason +
            ", state=" + state +
            ", hasNewLeaderEventPending=" + hasNewLeaderEventPending +
            ", hasOpenEventPending=" + hasOpenEventPending +
            ", encodedPrincipal=" + Arrays.toString(encodedPrincipal) +
            '}';
    }
}
