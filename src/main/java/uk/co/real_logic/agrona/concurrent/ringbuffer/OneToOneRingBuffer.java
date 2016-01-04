/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.agrona.concurrent.ringbuffer;

import uk.co.real_logic.agrona.DirectBuffer;
import uk.co.real_logic.agrona.concurrent.AtomicBuffer;
import uk.co.real_logic.agrona.concurrent.MessageHandler;

import static uk.co.real_logic.agrona.BitUtil.align;
import static uk.co.real_logic.agrona.concurrent.ringbuffer.RecordDescriptor.*;
import static uk.co.real_logic.agrona.concurrent.ringbuffer.RingBufferDescriptor.checkCapacity;

/**
 * A ring-buffer that supports the exchange of messages from a single producer to a single consumer.
 */
public class OneToOneRingBuffer implements RingBuffer
{
    /**
     * Record type is padding to prevent fragmentation in the buffer.
     */
    public static final int PADDING_MSG_TYPE_ID = -1;

    private final long capacity;
    private final long mask;
    private final long maxMsgLength;
    private final long tailPositionIndex;
    private final long headCachePositionIndex;
    private final long headPositionIndex;
    private final long correlationIdCounterIndex;
    private final long consumerHeartbeatIndex;
    private final AtomicBuffer buffer;

    /**
     * Construct a new {@link RingBuffer} based on an underlying {@link AtomicBuffer}.
     * The underlying buffer must a power of 2 in size plus sufficient space
     * for the {@link RingBufferDescriptor#TRAILER_LENGTH}.
     *
     * @param buffer via which events will be exchanged.
     * @throws IllegalStateException if the buffer capacity is not a power of 2
     *                               plus {@link RingBufferDescriptor#TRAILER_LENGTH} in capacity.
     */
    public OneToOneRingBuffer(final AtomicBuffer buffer)
    {
        this.buffer = buffer;
        checkCapacity(buffer.capacity());
        capacity = buffer.capacity() - RingBufferDescriptor.TRAILER_LENGTH;

        buffer.verifyAlignment();

        mask = capacity - 1;
        maxMsgLength = capacity / 8;
        tailPositionIndex = capacity + RingBufferDescriptor.TAIL_POSITION_OFFSET;
        headCachePositionIndex = capacity + RingBufferDescriptor.HEAD_CACHE_POSITION_OFFSET;
        headPositionIndex = capacity + RingBufferDescriptor.HEAD_POSITION_OFFSET;
        correlationIdCounterIndex = capacity + RingBufferDescriptor.CORRELATION_COUNTER_OFFSET;
        consumerHeartbeatIndex = capacity + RingBufferDescriptor.CONSUMER_HEARTBEAT_OFFSET;
    }

    /**
     * {@inheritDoc}
     */
    public long capacity()
    {
        return capacity;
    }

    /**
     * {@inheritDoc}
     */
    public boolean write(final int msgTypeId, final DirectBuffer srcBuffer, final int srcIndex, final int length)
    {
        checkTypeId(msgTypeId);
        checkMsgLength(length);

        final AtomicBuffer buffer = this.buffer;
        final long recordLength = length + HEADER_LENGTH;
        final long requiredCapacity = align(recordLength, ALIGNMENT);
        final long capacity = this.capacity;
        final long tailPositionIndex = this.tailPositionIndex;
        final long headCachePositionIndex = this.headCachePositionIndex;
        final long mask = this.mask;

        long head = buffer.getLong(headCachePositionIndex);
        final long tail = buffer.getLong(tailPositionIndex);
        final long availableCapacity = capacity - (int)(tail - head);

        if (requiredCapacity > availableCapacity)
        {
            head = buffer.getLongVolatile(headPositionIndex);

            if (requiredCapacity > (capacity - (tail - head)))
            {
                return false;
            }

            buffer.putLong(headCachePositionIndex, head);
        }

        long padding = 0;
        long recordIndex = tail & mask;
        final long toBufferEndLength = capacity - recordIndex;

        if (requiredCapacity > toBufferEndLength)
        {
            long headIndex = head & mask;

            if (requiredCapacity > headIndex)
            {
                head = buffer.getLongVolatile(headPositionIndex);
                headIndex = (int)head & mask;
                if (requiredCapacity > headIndex)
                {
                    return false;
                }

                buffer.putLong(headCachePositionIndex, head);
            }

            padding = toBufferEndLength;
        }

        if (0 != padding)
        {
            buffer.putLongOrdered(recordIndex, makeHeader(padding, PADDING_MSG_TYPE_ID));
            recordIndex = 0;
        }

        buffer.putBytes(encodedMsgOffset(recordIndex), srcBuffer, srcIndex, length);
        buffer.putLongOrdered(recordIndex, makeHeader(recordLength, msgTypeId));

        buffer.putLongOrdered(tailPositionIndex, tail + requiredCapacity + padding);

        return true;
    }

    /**
     * {@inheritDoc}
     */
    public int read(final MessageHandler handler)
    {
        return read(handler, Integer.MAX_VALUE);
    }

    /**
     * {@inheritDoc}
     */
    public int read(final MessageHandler handler, final int messageCountLimit)
    {
        int messagesRead = 0;

        final AtomicBuffer buffer = this.buffer;
        final long head = buffer.getLong(headPositionIndex);

        int bytesRead = 0;

        final long headIndex = head & mask;
        final long contiguousBlockLength = capacity - headIndex;

        try
        {
            while ((bytesRead < contiguousBlockLength) && (messagesRead < messageCountLimit))
            {
                final long recordIndex = headIndex + bytesRead;
                final long header = buffer.getLongVolatile(recordIndex);

                final int recordLength = recordLength(header);
                if (recordLength <= 0)
                {
                    break;
                }

                bytesRead += align(recordLength, ALIGNMENT);

                final int messageTypeId = messageTypeId(header);
                if (PADDING_MSG_TYPE_ID == messageTypeId)
                {
                    continue;
                }

                ++messagesRead;
                handler.onMessage(messageTypeId, buffer, recordIndex + HEADER_LENGTH, recordLength - HEADER_LENGTH);
            }
        }
        finally
        {
            if (bytesRead != 0)
            {
                buffer.setMemory(headIndex, bytesRead, (byte)0);
                buffer.putLongOrdered(headPositionIndex, head + bytesRead);
            }
        }

        return messagesRead;
    }

    /**
     * {@inheritDoc}
     */
    public long maxMsgLength()
    {
        return maxMsgLength;
    }

    /**
     * {@inheritDoc}
     */
    public long nextCorrelationId()
    {
        return buffer.getAndAddLong(correlationIdCounterIndex, 1);
    }

    /**
     * {@inheritDoc}
     */
    public AtomicBuffer buffer()
    {
        return buffer;
    }

    /**
     * {@inheritDoc}
     */
    public void consumerHeartbeatTime(final long time)
    {
        buffer.putLongOrdered(consumerHeartbeatIndex, time);
    }

    /**
     * {@inheritDoc}
     */
    public long consumerHeartbeatTime()
    {
        return buffer.getLongVolatile(consumerHeartbeatIndex);
    }

    /**
     * {@inheritDoc}
     */
    public long producerPosition()
    {
        return buffer.getLongVolatile(tailPositionIndex);
    }

    /**
     * {@inheritDoc}
     */
    public long consumerPosition()
    {
        return buffer.getLongVolatile(headPositionIndex);
    }

    /**
     * {@inheritDoc}
     */
    public int size()
    {
        long headBefore;
        long tail;
        long headAfter = buffer.getLongVolatile(headPositionIndex);

        do
        {
            headBefore = headAfter;
            tail = buffer.getLongVolatile(tailPositionIndex);
            headAfter = buffer.getLongVolatile(headPositionIndex);
        }
        while (headAfter != headBefore);

        return (int)(tail - headAfter);
    }

    /**
     * {@inheritDoc}
     */
    public boolean unblock()
    {
        return false;
    }

    private void checkMsgLength(final int length)
    {
        if (length > maxMsgLength)
        {
            final String msg = String.format("encoded message exceeds maxMsgLength of %d, length=%d", maxMsgLength, length);

            throw new IllegalArgumentException(msg);
        }
    }
}
