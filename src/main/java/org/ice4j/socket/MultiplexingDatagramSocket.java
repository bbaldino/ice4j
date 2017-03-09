/*
 * ice4j, the OpenSource Java Solution for NAT and Firewall Traversal.
 *
 * Copyright @ 2015 Atlassian Pty Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ice4j.socket;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Represents a <tt>DatagramSocket</tt> which allows filtering
 * <tt>DatagramPacket</tt>s it reads from the network using
 * <tt>DatagramPacketFilter</tt>s so that the <tt>DatagramPacket</tt>s do not
 * get received through it but through associated
 * <tt>MultiplexedDatagramSocket</tt>s.
 *
 * @author Lyubomir Marinov
 */
public class MultiplexingDatagramSocket
    extends SafeCloseDatagramSocket
{
    ArrayBlockingQueue<DatagramPacket> receivedPackets =
        new ArrayBlockingQueue<DatagramPacket>(10000);
    /**
     * The thread that does the call to receive on the actual underlying socket
     *  and puts packets in the receivedPackets queue
     */
    Thread receiverThread = null;
    /**
     * The thread which reads from the receivedPackets queue and multiplexes
     *  packets out to the MultiplexedSockets
     */
    Thread multiplexerThread = null;

    /**
     * List of multiplexed sockets that have been created with filters
     */
    private List<MultiplexedDatagramSocket> multiplexedSockets =
        new ArrayList<>();

    /**
     * Initializes a new <tt>MultiplexingDatagramSocket</tt> instance which is
     * to enable <tt>DatagramPacket</tt> filtering and binds it to any available
     * port on the local host machine. The socket will be bound to the wildcard
     * address, an IP address chosen by the kernel.
     *
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     * @see DatagramSocket#DatagramSocket()
     */
    public MultiplexingDatagramSocket()
        throws SocketException
    {
        startReceiverThread();
    }

    /**
     * Initializes a new <tt>MultiplexingDatagramSocket</tt> instance which is
     * to enable <tt>DatagramPacket</tt> filtering on a specific
     * <tt>DatagramSocket</tt>.
     *
     * @param delegate the <tt>DatagramSocket</tt> on which
     * <tt>DatagramPacket</tt> filtering is to be enabled by the new instance
     * @throws SocketException if anything goes wrong while initializing the new
     * instance
     */
    public MultiplexingDatagramSocket(DatagramSocket delegate)
        throws SocketException
    {
        super(delegate);
        startReceiverThread();
    }

    /**
     * Initializes a new <tt>MultiplexingDatagramSocket</tt> instance which is
     * to enable <tt>DatagramPacket</tt> filtering and binds it to the specified
     * port on the local host machine. The socket will be bound to the wildcard
     * address, an IP address chosen by the kernel.
     *
     * @param port the port to bind the new socket to
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     * @see DatagramSocket#DatagramSocket(int)
     */
    public MultiplexingDatagramSocket(int port)
        throws SocketException
    {
        super(port);
        startReceiverThread();
    }

    /**
     * Initializes a new <tt>MultiplexingDatagramSocket</tt> instance which is
     * to enable <tt>DatagramPacket</tt> filtering, bound to the specified local
     * address. The local port must be between 0 and 65535 inclusive. If the IP
     * address is 0.0.0.0, the socket will be bound to the wildcard address, an
     * IP address chosen by the kernel.
     *
     * @param port the local port to bind the new socket to
     * @param laddr the local address to bind the new socket to
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     * @see DatagramSocket#DatagramSocket(int, InetAddress)
     */
    public MultiplexingDatagramSocket(int port, InetAddress laddr)
        throws SocketException
    {
        super(port, laddr);
        startReceiverThread();
    }

    /**
     * Initializes a new <tt>MultiplexingDatagramSocket</tt> instance which is
     * to enable <tt>DatagramPacket</tt> filtering, bound to the specified local
     * socket address.
     * <p>
     * If the specified local socket address is <tt>null</tt>, creates an
     * unbound socket.
     * </p>
     *
     * @param bindaddr local socket address to bind, or <tt>null</tt> for an
     * unbound socket
     * @throws SocketException if the socket could not be opened, or the socket
     * could not bind to the specified local port
     * @see DatagramSocket#DatagramSocket(SocketAddress)
     */
    public MultiplexingDatagramSocket(SocketAddress bindaddr)
        throws SocketException
    {
        super(bindaddr);
        startReceiverThread();
    }

    private void startReceiverThread() {
        receiverThread = new Thread(new Runnable()
        {
            @Override
            public void run() {
                while (true)
                {
                    //System.out.println("MultiplexingDatagramSocket inner receive loop");
                    byte[] buf = new byte[1500];
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    try {
                        MultiplexingDatagramSocket.super.receive(p);
                    } catch (IOException e) {
                        //System.out.println("MultiplexingDatagramSocket inner receive loop: exception");
                        continue;
                    }
                    //System.out.println("MultiplexingDatagramSocket inner receive loop: received");
                    receivedPackets.add(p);
                }
            }
        });
        receiverThread.start();
        startMultiplexerThread();
        System.out.println("Started receiver thread");
    }

    private void startMultiplexerThread() {
        multiplexerThread = new Thread(new Runnable()
        {
            @Override
            public void run() {
                while (true) {
                    DatagramPacket p = null;
                    try {
                        p = receivedPackets.take();
                    } catch (InterruptedException e) {
                        continue;
                    }
                    //System.out.println("Multiplexer thread: read");
                    synchronized(multiplexedSockets)
                    {
                        for (MultiplexedDatagramSocket multiplexedSocket : multiplexedSockets)
                        {
                            DatagramPacketFilter filter = multiplexedSocket.getFilter();
                            if (filter.accept(p))
                            {
                                multiplexedSocket.receivedPackets.offer(MultiplexingXXXSocketSupport.clone(p, true));
                            }
                        }
                        //TODO: old code kept anything that didn't match in a central queue that could be used to fill
                        // into multiplexed queues as they were created...for now i'm just gonna drop 'em.
                    }

                }
            }
        });
        multiplexerThread.start();
    }

    /**
     * Closes a specific <tt>MultiplexedDatagramSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>DatagramSocket</tt>.
     *
     * @param multiplexed the <tt>MultiplexedDatagramSocket</tt> to close
     */
    void close(MultiplexedDatagramSocket multiplexed)
    {
        synchronized(multiplexedSockets)
        {
            multiplexedSockets.remove(multiplexed);
        }
    }

    /**
     * Gets a <tt>MultiplexedDatagramSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>DatagramSocket</tt> using a
     * specific <tt>DatagramPacketFilter</tt>. If such a
     * <tt>MultiplexedDatagramSocket</tt> does not exist in this instance, it is
     * created.
     *
     * @param filter the <tt>DatagramPacketFilter</tt> to get a
     * <tt>MultiplexedDatagramSocket</tt> for
     * @return a <tt>MultiplexedDatagramSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>DatagramSocket</tt> using the
     * specified <tt>filter</tt>
     * @throws SocketException if creating the
     * <tt>MultiplexedDatagramSocket</tt> for the specified <tt>filter</tt>
     * fails
     */
    public MultiplexedDatagramSocket getSocket(DatagramPacketFilter filter)
        throws SocketException
    {
        return getSocket(filter, /* create */ true);
    }

    /**
     * Gets a <tt>MultiplexedDatagramSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>DatagramSocket</tt> using a
     * specific <tt>DatagramPacketFilter</tt>. If <tt>create</tt> is true and
     * such a <tt>MultiplexedDatagramSocket</tt> does not exist in this
     * instance, it is created.
     *
     * @param filter the <tt>DatagramPacketFilter</tt> to get a
     * <tt>MultiplexedDatagramSocket</tt> for
     * @param create whether or not to create a
     * <tt>MultiplexedDatagramSocket</tt> if this instance does not already have
     * a socket for the given <tt>filter</tt>.
     * @return a <tt>MultiplexedDatagramSocket</tt> which filters
     * <tt>DatagramPacket</tt>s away from this <tt>DatagramSocket</tt> using the
     * specified <tt>filter</tt>
     * @throws SocketException if creating the
     * <tt>MultiplexedDatagramSocket</tt> for the specified <tt>filter</tt>
     * fails.
     */
    public MultiplexedDatagramSocket getSocket(
            DatagramPacketFilter filter,
            boolean create)
        throws SocketException
    {
        synchronized(multiplexedSockets)
        {
            for (MultiplexedDatagramSocket socket : multiplexedSockets)
            {
                if (socket.getFilter().equals(filter))
                {
                    return socket;
                }
            }
            if (!create)
            {
                return null;
            }
            MultiplexedDatagramSocket multiplexedDatagramSocket =
                new MultiplexedDatagramSocket(this, filter);
            multiplexedSockets.add(multiplexedDatagramSocket);
            return multiplexedDatagramSocket;
        }
    }

    /**
     * Receives a datagram packet from this socket. The <tt>DatagramPacket</tt>s
     * returned by this method do not match any of the
     * <tt>DatagramPacketFilter</tt>s of the <tt>MultiplexedDatagramSocket</tt>s
     * associated with this instance at the time of their receipt. When this
     * method returns, the <tt>DatagramPacket</tt>'s buffer is filled with the
     * data received. The datagram packet also contains the sender's IP address,
     * and the port number on the sender's machine.
     * <p>
     * This method blocks until a datagram is received. The <tt>length</tt>
     * field of the datagram packet object contains the length of the received
     * message. If the message is longer than the packet's length, the message
     * is truncated.
     * </p>
     *
     * @param p the <tt>DatagramPacket</tt> into which to place the incoming
     * data
     * @throws IOException if an I/O error occurs
     * @throws SocketTimeoutException if <tt>setSoTimeout(int)</tt> was
     * previously called and the timeout has expired
     * @see DatagramSocket#receive(DatagramPacket)
     */
    @Override
    public void receive(DatagramPacket p)
        throws IOException
    {
        // I don't get what calls receive on this directly (as opposed to a created
        //  multiplexed socket).  seems like this defeats the purpose and shouldn't
        //  even exist?
        System.out.println("BB: DIRECT CALL TO RECEIVE ON MULTIPLEXINGDATAGRAMSOCKET");
        for (StackTraceElement ste : Thread.currentThread().getStackTrace()) {
            System.out.println(ste);
        }
        throw new IOException();
    }

    /**
     * Receives a <tt>DatagramPacket</tt> from this <tt>DatagramSocket</tt> upon
     * request from a specific <tt>MultiplexedDatagramSocket</tt>.
     *
     * @param multiplexed the <tt>MultiplexedDatagramSocket</tt> which requests
     * the receipt of a <tt>DatagramPacket</tt> from the network
     * @param p the <tt>DatagramPacket</tt> to receive the data from the network
     * @throws IOException if an I/O error occurs
     * @throws SocketTimeoutException if <tt>setSoTimeout(int)</tt> was
     * previously called on <tt>multiplexed</tt> and the timeout has expired
     */
    void receive(MultiplexedDatagramSocket multiplexed, DatagramPacket p)
        throws IOException
    {
        multiplexed.receive(p);
    }
}
