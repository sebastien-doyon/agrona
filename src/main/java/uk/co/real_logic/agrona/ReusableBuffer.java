/*
 * Copyright 2014-2015 Real Logic Ltd.
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
package uk.co.real_logic.agrona;

import java.nio.ByteBuffer;

/**
 * Buffers that can be reused by wrapping an underlying storage mechanism.
 */
public interface ReusableBuffer
{
    /**
     * Attach a view to a byte[] for providing direct access.
     *
     * @param buffer to which the view is attached.
     */
    void wrap(byte[] buffer);

    /**
     * Attach a view to a byte[] for providing direct access.
     *
     * @param buffer to which the view is attached.
     * @param offset at which the view begins.
     * @param length of the buffer included in the view
     */
    void wrap(byte[] buffer, int offset, int length);

    /**
     * Attach a view to a {@link ByteBuffer} for providing direct access, the {@link ByteBuffer} can be
     * heap based or direct.
     *
     * @param buffer to which the view is attached.
     */
    void wrap(ByteBuffer buffer);

    /**
     * Attach a view to a {@link ByteBuffer} for providing direct access.
     *
     * @param buffer to which the view is attached.
     * @param offset at which the view begins.
     * @param length of the buffer included in the view
     */
    void wrap(ByteBuffer buffer, int offset, int length);

    /**
     * Attach a view to an existing {@link DirectBuffer}
     *
     * @param buffer to which the view is attached.
     */
    void wrap(DirectBuffer buffer);

    /**
     * Attach a view to a {@link DirectBuffer} for providing direct access.
     *
     * @param buffer to which the view is attached.
     * @param offset at which the view begins.
     * @param length of the buffer included in the view
     */
    void wrap(DirectBuffer buffer, long offset, int length);

    /**
     * Attach a view to an off-heap memory region by address.
     *
     * @param address where the memory begins off-heap
     * @param length  of the buffer from the given address
     */
    void wrap(long address, int length);

}
