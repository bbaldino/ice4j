package org.ice4j.socket;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Created by brian on 3/9/17.
 */
public class MultiplexingDatagramSocket
  extends SafeCloseDatagramSocket {


  ArrayBlockingQueue<DatagramPacket> received
          = new ArrayBlockingQueue<>(100);
  int numDroppedPackets = 0;
  /*
  private final List<DatagramPacket> received
      = new SocketReceiveBuffer()
  {
    private static final long serialVersionUID
        = 3125772367019091216L;

    @Override
    public int getReceiveBufferSize()
        throws SocketException
    {
      return MultiplexingDatagramSocket.this.getReceiveBufferSize();
    }
  };
  */

  Object receiveLock = new Object();
  boolean doingReceive;
  private boolean setReceiveBufferSize = false;
  private int receiveBufferSize;



  private int soTimeout = 0;

  private final List<MultiplexedDatagramSocket> sockets = new ArrayList<>();

  public MultiplexingDatagramSocket()
    throws SocketException
  {
  }

  public MultiplexingDatagramSocket(DatagramSocket delegate)
    throws SocketException
  {
    super(delegate);
  }

  public MultiplexingDatagramSocket(int port, InetAddress laddr)
      throws SocketException
  {
    super(port, laddr);
  }

  public MultiplexingDatagramSocket(SocketAddress bindaddr)
      throws SocketException
  {
    super(bindaddr);
  }

  void close(MultiplexedDatagramSocket multiplexed)
  {
    synchronized (sockets)
    {
      sockets.remove(multiplexed);
    }
  }

  public MultiplexedDatagramSocket getSocket(DatagramPacketFilter filter)
      throws SocketException
  {
    return getSocket(filter, /* create */ true);
  }

  public MultiplexedDatagramSocket getSocket(
      DatagramPacketFilter filter,
      boolean create)
      throws SocketException
  {
      if (filter == null)
      {
          throw new NullPointerException("filter");
      }
      // find or create a socket with the given filter
      synchronized(sockets)
      {
          for (MultiplexedDatagramSocket socket : sockets)
          {
              if (filter.equals(socket.getFilter()))
              {
                  return socket;
              }
          }
          if (!create)
          {
              return null;
          }
          MultiplexedDatagramSocket socket = new MultiplexedDatagramSocket(this, filter);
          if (socket != null)
          {
              sockets.add(socket);
              moveReceivedFromThisToSocket(socket);
          }
          return socket;
      }
  }

  private void moveReceivedFromThisToSocket(MultiplexedDatagramSocket socket)
  {
    // Pull the packets which have been received already and are accepted by
    // the specified multiplexed socket out of the multiplexing socket.
    //List<DatagramPacket> thisReceived = received;
    DatagramPacketFilter socketFilter = socket.getFilter();
    List<DatagramPacket> toMove = null;

    if (received.isEmpty())
    {
        return;
    }
    for (DatagramPacket p : received)
    {
        if (socketFilter.accept(p))
        {
            if (toMove == null)
            {
                toMove = new LinkedList<>();
            }
            toMove.add(p);
        }
    }

    if (toMove != null)
    {
        for (DatagramPacket p : toMove)
        {
            received.remove(p);
            if (!socket.received.offer(p))
            {
                System.out.println("BJB: No room to move packet to new multiplexed socket");
            }
        }
    }
  }

  @Override
  public int getSoTimeout()
  {
    return soTimeout;
  }

  private void newReceiveHelper(ArrayBlockingQueue<DatagramPacket> received, DatagramPacket p)
          throws IOException
  {
      long startTime = System.currentTimeMillis();
      DatagramPacket r = null;

      while (true) {
          long now = System.currentTimeMillis();

          // Check if there's already a packet waiting
          r = received.poll();
          if (r != null) {
              break;
          }
          long remainingTimeout;
          if (soTimeout > 0)
          {
              remainingTimeout = soTimeout - (now - startTime);
              if (remainingTimeout <= 0L)
              {
                  throw new SocketTimeoutException(Long.toString(remainingTimeout));
              }
          }
          else
          {
              remainingTimeout = 1000L;
          }

          // If there isn't a packet waiting yet, check if the receive is already being done
          boolean wait = false;
          synchronized (receiveLock) {
              if (!doingReceive) {
                  doingReceive = true;
              }
              else
              {
                  wait = true;
              }
          }
          if (wait) {
              try
              {
                  System.out.println("BJB: MultiplexingSocket thread " + Thread.currentThread().getName() +
                          " waiting for someone to do the receive");
                  r = received.poll(remainingTimeout, TimeUnit.MILLISECONDS);
                  if (r != null)
                  {
                      System.out.println("BJB: MultiplexingSocket thread " + Thread.currentThread().getName() +
                              " got data after waiting for someone to do the receive");
                      break;
                  }
              }
              catch (InterruptedException e)
              {
              }
              continue;
          }
          else
          {
              r = MultiplexingXXXSocketSupport.clone(p, false);
              try
              {
                  System.out.println("BJB: MultiplexingSocket thread " + Thread.currentThread().getName() +
                          " doing the actual receive");
                  super.receive(r);
                  System.out.println("BJB: MultiplexingSocket thread " + Thread.currentThread().getName() +
                          " got data from the actual receive");
                  break;

              }
              finally
              {
                  synchronized (receiveLock)
                  {
                      doingReceive = false;
                  }
              }
          }
      }
      MultiplexingXXXSocketSupport.copy(r, p);
  }

  private void receiveHelper(List<DatagramPacket> received, DatagramPacket p, int timeout)
      throws IOException
  {
    long startTime = System.currentTimeMillis();
    DatagramPacket r = null;

    do
    {
      long now = System.currentTimeMillis();
      synchronized (received)
      {
        if (!received.isEmpty())
        {
          r = received.remove(0);
          if (r != null)
          {
            break;
          }
        }
      }

      long remainingTimeout;
      if (soTimeout > 0)
      {
        remainingTimeout = soTimeout - (now - startTime);
        if (remainingTimeout <= 0L)
        {
          throw new SocketTimeoutException(Long.toString(remainingTimeout));
        }
      }
      else
      {
        remainingTimeout = 1000L;
      }

      boolean wait;
      synchronized(receiveLock)
      {
        if (doingReceive)
        {
          wait = true;
        }
        else
        {
          wait = false;
          doingReceive = true;
        }
      }
      try
      {
        if (wait)
        {
          synchronized(received)
          {
            if (received.isEmpty())
            {
              try
              {
                received.wait(remainingTimeout);
              } catch (InterruptedException e)
              {
              }
            }
            else
            {
              received.notifyAll();;
            }
          }
          continue;
        }

        DatagramPacket c = MultiplexingXXXSocketSupport.clone(p, false);
        /*
        synchronized(receiveLock)
        {
          if (setReceiveBufferSize)
          {
            setReceiveBufferSize = false;
            try
            {
              super.setReceiveBufferSize(receiveBufferSize);
            } catch (Throwable t)
            {
              if (t instanceof ThreadDeath)
              {
                throw (ThreadDeath)t;
              }
            }
          }
        }
        */
        super.receive(c);
        acceptBySocketsOrThis(c);
      }
      finally
      {
        synchronized(receiveLock)
        {
          if (!wait)
          {
            doingReceive = false;
          }
        }
      }
    } while(true);
    MultiplexingXXXSocketSupport.copy(r, p);
  }

  private void acceptBySocketsOrThis(DatagramPacket p)
  {
      boolean accepted = false;
      System.out.println("BJB: Checking if any multiplexed sockets will take packet");
      synchronized (sockets)
      {
          for (MultiplexedDatagramSocket socket : sockets)
          {
              System.out.println("BJB: Checking if socket with filter " + socket.getFilter().getClass().getName() + " will take packet");
              if (socket.getFilter().accept(p))
              {
                  System.out.println("BJB: socket with filter " + socket.getFilter().getClass().getName() + " will take packet!");
                  ArrayBlockingQueue<DatagramPacket> socketReceived = socket.received;

                  if (!socketReceived.offer(accepted ? MultiplexingXXXSocketSupport.clone(p, /* arraycopy */ true) : p))
                  {
                      ++numDroppedPackets;
                      System.out.println("BJB: No room to accept packet in multiplexed socket");
                  }
                  accepted = true;

                  // Emil Ivov: Don't break because we want all
                  // filtering sockets to get the received packet.
              }
          }
      }
      if (!accepted)
      {
          if (!received.offer(p))
          {
              ++numDroppedPackets;
              System.out.println("BJB: No room to accept packet in multiplexed socket");
          }
      }
      if (numDroppedPackets % 100 == 0)
      {
          System.out.println("BB: Multiplexing socket " + hashCode() + " has dropped " + numDroppedPackets + " due to full queues");
      }
  }

  @Override
  public void receive(DatagramPacket p)
      throws IOException
  {
      //System.out.println("BJB: " + Thread.currentThread().getName() + " reading from multiplexing socket " + hashCode());
      newReceiveHelper(received, p);
      //System.out.println("BJB: " + Thread.currentThread().getName() + " read from multiplexing socket " + hashCode());
  }

  void receive(MultiplexedDatagramSocket multiplexed, DatagramPacket p)
      throws IOException
  {
      //System.out.println("BJB: " + Thread.currentThread().getName() + " reading from multiplexed socket " + multiplexed.hashCode());
      newReceiveHelper(multiplexed.received, p);
      //System.out.println("BJB: " + Thread.currentThread().getName() + " read from multiplexed socket " + multiplexed.hashCode());
  }

  @Override
  public void setReceiveBufferSize(int receiveBufferSize)
      throws SocketException
  {
    synchronized (receiveLock)
    {
      this.receiveBufferSize = receiveBufferSize;
      if (doingReceive)
      {
        setReceiveBufferSize = true;
      }
      else
      {
        super.setReceiveBufferSize(receiveBufferSize);
        setReceiveBufferSize = false;
      }
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSoTimeout(int timeout)
      throws SocketException
  {
    super.setSoTimeout(timeout);

    soTimeout = timeout;
  }
}
